package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.ContainerTracker;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.ChunkSerializer;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import io.github.billstark001.worldmirror.ui.ClientDialogs;
import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central controller for the download lifecycle.
 *
 * <ul>
 *   <li>Tracks whether downloading is currently active.</li>
 *   <li>Drives the periodic sync tick (called from the client tick event).</li>
 *   <li>Runs the actual export on a background thread to avoid freezing the game.</li>
 *   <li>Exposes helpers for a one-shot manual export and for cache clearing.</li>
 * </ul>
 */
public final class DownloadManager {

    private DownloadManager() {}

    // ── State ─────────────────────────────────────────────────────────────────

    private static final AtomicBoolean currentActive = new AtomicBoolean(false);
    private static long lastCacheEvictionMs = 0;
    private static final int INITIAL_CAPTURE_RANGE = 33;
    private static final int PRE_EXPORT_CAPTURE_RANGE = 8;
    private static final int STOP_CAPTURE_RANGE = 6;
    private static final long CACHE_EVICTION_INTERVAL_MS = 5_000L;
    private static long nextAutomaticExportAttemptMs;
    private static volatile boolean mirrorCaptureWarningShown;
    private static volatile DownloadPipeline activePipeline =
            DownloadPipeline.create(ModConfig.DownloadPipelineMode.STABLE_PERIODIC);
    private static final LatencyWindow worldFrameIntervals = new LatencyWindow(8192);
    private static final LatencyWindow worldMirrorTickWork = new LatencyWindow(4096);
    private static volatile long lastDiagnosticLogMs;
    private static volatile long diagnosticSessionStartedMs;
    private static volatile boolean diagnosticSessionActive;
    private static volatile boolean recordPerformanceTimings;
    private static final DownloadCaptureQueue captureQueue =
            new DownloadCaptureQueue(() -> recordPerformanceTimings, INITIAL_CAPTURE_RANGE);
    private static final DownloadExportCoordinator exportCoordinator =
            new DownloadExportCoordinator(captureQueue, () -> activePipeline);
    private static volatile long lastWorldFrameNs;
    private static final Map<String, GcSnapshot> lastGcByCollector = new HashMap<>();

    private record GcSnapshot(long count, long timeMs) { }

    // ── Lifecycle tracking ────────────────────────────────────────────────────

    /**
     * The dimension registry key observed on the previous client tick.
     * Used to detect dimension changes (e.g. Overworld → Nether).
     */
    private static volatile ResourceKey<Level> lastDimension = null;

    /**
     * The sourceId observed on the previous client tick.
     * Used to detect server-side world changes (e.g. Multiverse world switch)
     * while remaining connected to the same server address.
     */
    private static volatile String lastSourceId = null;
    private static volatile String lastSourceType = null;

    public static boolean isActive() {
        return currentActive.get();
    }

    public static boolean isExportInProgress() {
        return exportCoordinator.isInProgress();
    }

    /** Records gameplay frame spacing without doing work when diagnostics are disabled. */
    public static void recordWorldFrame() {
        if (!recordPerformanceTimings || !currentActive.get()) {
            lastWorldFrameNs = 0L;
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.isPaused()) {
            lastWorldFrameNs = 0L;
            return;
        }
        long nowNs = System.nanoTime();
        long previousNs = lastWorldFrameNs;
        lastWorldFrameNs = nowNs;
        if (previousNs > 0L) worldFrameIntervals.record((nowNs - previousNs) / 1_000L);
    }

    public static void markEntitiesDirty() {
        if (currentActive.get()) exportCoordinator.markEntitiesDirty();
    }

    public record PipelineMetrics(
            ModConfig.DownloadPipelineMode mode,
            int pendingCaptures,
            int dirtyChunks,
            long oldestCaptureAgeMs,
            long coalescedHints,
            long droppedHints,
            long lastExportMillis,
            int lastExportWritten,
            int lastExportSettled,
            int lastExportUnreadable,
            long exportFailures) { }

    public static PipelineMetrics getPipelineMetrics() {
        DownloadCaptureQueue.Metrics capture = captureQueue.metrics();
        DownloadExportCoordinator.Metrics export = exportCoordinator.metrics();
        return new PipelineMetrics(activePipeline.mode(), capture.pendingCaptures(),
                ChunkListener.getDirtyCount(), capture.oldestCaptureAgeMs(),
                capture.coalescedHints(), capture.droppedHints(), export.lastExportMillis(),
                export.lastWritten(), export.lastSettled(), export.lastUnreadable(),
                export.failures());
    }

    /** Coalesces a packet/event hint into one main-thread capture per chunk. */
    public static void queueChunkCapture(ClientLevel world, ChunkPos pos, String reason) {
        if (!currentActive.get() || world == null || pos == null) return;
        captureQueue.queueChunk(world, pos, reason);
    }

    /** Last-chance main-thread capture before Fabric removes a loaded chunk. */
    public static void captureChunkBeforeUnload(ClientLevel world, LevelChunk chunk) {
        if (!currentActive.get() || world == null || chunk == null) return;
        captureQueue.captureBeforeUnload(world, chunk);
    }

    /** Queues a normal capture when a light packet arrives before its base chunk. */
    public static void queueLightUpdateCapture(ClientLevel world, ChunkPos pos) {
        if (!currentActive.get()) return;
        captureQueue.queueLightUpdate(world, pos);
    }

    /**
     * Coalesces repeated light-only packets before marking their cached chunk
     * dirty.  The overlay itself is updated immediately; only export dirtiness
     * waits for the short fixed window.
     */
    public static void markLightUpdateDirty(ClientLevel world, ChunkPos pos) {
        if (!currentActive.get()) return;
        captureQueue.markLightDirty(world, pos);
    }

    // ── Public commands ───────────────────────────────────────────────────────

    /**
     * Toggles downloading on/off.
     * When enabling, all currently loaded chunks in the active dimension are
     * immediately captured so that the player does not need to reload them.
     */
    public static void toggle(Minecraft client) {
        if (currentActive.get()) {
            currentActive.set(false);
            clearPendingCaptureState();
            finalizeCaptureOnStop(client, "manual-toggle");
            WMPlayerMessages.sendOverlayMessage(client.player,
                    Component.translatable("msg.worldmirror.downloadStop"));
            WMLogger.debug("Download deactivated");
            return;
        }
        requestDownloadStart(client, "manual-toggle");
    }

    /**
     * Performs an immediate one-shot export regardless of the toggle state.
     */
    public static void exportNow(Minecraft client) {
        requestOutputReady(client, () -> exportNowReady(client));
    }

    private static void exportNowReady(Minecraft client) {
        boolean canPreCapture = ModConfig.get().lifecycle.captureNearbyBeforeExport
                && client.level != null && client.player != null;
        if (ChunkListener.isEmpty() && !exportCoordinator.hasEntityWork()
                && !EntityTracker.hasDirtyUpdates() && !canPreCapture) {
            Component msg = Component.translatable("msg.worldmirror.noChunks");
            WMPlayerMessages.sendSystemMessage(client.player, msg);
            WMLogger.debug("Manual export ignored because no chunks are cached.");
            return;
        }
        if (exportCoordinator.isInProgress()) {
            Component msg = Component.translatable("msg.worldmirror.exportBusy");
            WMPlayerMessages.sendSystemMessage(client.player, msg);
            exportCoordinator.start(client, new DownloadExportCoordinator.Request(
                    DownloadExportCoordinator.Trigger.MANUAL, true, false,
                    null, null, ContainerTracker.snapshotSavedData()));
            WMLogger.debug("Export already in progress; coalesced another export request.");
            return;
        }
        exportCoordinator.start(client, new DownloadExportCoordinator.Request(
                DownloadExportCoordinator.Trigger.MANUAL,
                true, false, null, null, null));
    }

    /** Clears all in-memory caches. */
    public static void clearAll(Minecraft client) {
        clearPendingCaptureState();
        int chunks     = ChunkListener.getTotalCount();
        int entities   = EntityTracker.getTotalTrackedEntities();
        int containers = ContainerTracker.getTotalSavedContainers();
        ChunkListener.clear();
        EntityTracker.clear();
        ContainerTracker.clear();
        Component msg = Component.translatable("msg.worldmirror.cleared");
        WMPlayerMessages.sendSystemMessage(client.player, msg);
        WMLogger.debug("Cleared: " + chunks + " chunks, " + entities
                + " entities, " + containers + " containers.");
    }

    // ── Lifecycle event handlers ──────────────────────────────────────────────

    /**
     * Called when the player joins (or re-joins) a world / server.
     * Resets lifecycle tracking state and applies the configured
     * {@link ModConfig.LifecycleConfig#onJoinWorld} behaviour.
     */
    public static void onJoinWorld(Minecraft client) {
        clearPendingCaptureState();
        clearCapturedWorldState();
        ContainerTracker.clear();
        mirrorCaptureWarningShown = false;
        applyTransition(client, ModConfig.get().lifecycle.onJoinWorld, "join-world");

        // Reset tracking so subsequent change-detection starts fresh.
        lastDimension = (client.level != null) ? client.level.dimension() : null;
        lastSourceId  = WorldMetadata.detectSourceId(client);
        lastSourceType = WorldMetadata.detectSourceType(client);
    }

    /**
     * Called when the player disconnects from a world / server.
     * Resets lifecycle tracking state so the next join starts clean.
     * If download was active, runs stop-time finalisation (optional capture/export)
     * before clearing lifecycle tracking state.
     */
    public static void onLeaveWorld(Minecraft client) {
        if (currentActive.get()) {
            currentActive.set(false);
            clearPendingCaptureState();
            finalizeCaptureOnStop(client, "leave-world");
        } else {
            clearPendingCaptureState();
        }
        lastDimension = null;
        lastSourceId  = null;
        lastSourceType = null;
        currentActive.set(false); // no world to download — always stop
        ContainerTracker.clear();
        WMLogger.debug("Left world; download deactivated.");
    }

    /**
     * Should be called on every client tick.
     * Triggers a periodic background sync when the configured interval elapses,
     * applies cache-eviction rules, and detects dimension / server-world changes.
     */
    public static void onClientTick(Minecraft client) {
        long startedNs = System.nanoTime();
        boolean shouldRecordPerformance = currentActive.get()
                && ModConfig.get().performance.diagnosticPerformanceLogging;
        if (shouldRecordPerformance && !diagnosticSessionActive) {
            resetDiagnosticSession();
            diagnosticSessionActive = true;
        } else if (!shouldRecordPerformance) {
            diagnosticSessionActive = false;
        }
        recordPerformanceTimings = shouldRecordPerformance;
        try {
            processClientTick(client);
        } finally {
            if (recordPerformanceTimings) {
                worldMirrorTickWork.record((System.nanoTime() - startedNs) / 1_000L);
            }
        }
    }

    private static void processClientTick(Minecraft client) {
        if (client.level == null) return;

        captureQueue.tick(client);
        exportCoordinator.tryStartDeferred(client);

        ResourceKey<Level> currentDim = client.level.dimension();
        String currentSourceId = WorldMetadata.detectSourceId(client);
        String currentSourceType = WorldMetadata.detectSourceType(client);

        // ── Detect server-side world change (same address, different logical world) ──
        // Must be checked BEFORE dimension change, as a world change also implies
        // a dimension change.
        if (lastSourceId != null && !lastSourceId.equals(currentSourceId)) {
            WMLogger.debug("Server world change detected: '" + lastSourceId
                    + "' → '" + currentSourceId + "'");
            clearPendingCaptureState();
            applyTransition(client, ModConfig.get().lifecycle.onServerWorldChange,
                    "server-world-change");
            clearCapturedWorldState();
            lastSourceId  = currentSourceId;
            lastSourceType = currentSourceType;
            lastDimension = currentDim;
            // Re-capture loaded chunks according to the new active state
            if (currentActive.get()) captureLoadedChunksAsync(client);
            if (!currentActive.get()) return;
        }

        // ── Detect dimension change (Overworld ↔ Nether ↔ End, etc.) ──────────
        if (lastDimension != null && !lastDimension.equals(currentDim)) {
            WMLogger.debug("Dimension change detected: '" + lastDimension.identifier()
                    + "' → '" + currentDim.identifier() + "'");
            lastDimension = currentDim;
            clearPendingCaptureState();
            applyTransition(client, ModConfig.get().lifecycle.onDimensionChange,
                    "dimension-change");
            // Re-capture loaded chunks according to the new active state
            if (currentActive.get()) captureLoadedChunksAsync(client);
        }

        // Update tracking even if player was in null world on the previous tick
        if (lastDimension == null) lastDimension = currentDim;
        if (lastSourceId  == null) lastSourceId  = currentSourceId;
        if (lastSourceType == null) lastSourceType = currentSourceType;

        if (!currentActive.get()) return;

        long now = System.currentTimeMillis();
        ModConfig cfg = ModConfig.get();
        int dirty = ChunkListener.getDirtyCount();
        boolean hasDurabilityWork = dirty > 0 || exportCoordinator.hasEntityWork();
        DownloadPipeline.Decision decision = activePipeline.evaluate(
                now, dirty, hasDurabilityWork, cfg);
        DownloadExportCoordinator.Trigger automaticTrigger = switch (decision) {
            case PERIODIC -> DownloadExportCoordinator.Trigger.PERIODIC;
            case HIGH_WATERMARK -> DownloadExportCoordinator.Trigger.ADAPTIVE_HIGH_WATERMARK;
            case MAX_LATENCY -> DownloadExportCoordinator.Trigger.ADAPTIVE_MAX_LATENCY;
            case NONE -> null;
        };
        if (automaticTrigger != null && now >= nextAutomaticExportAttemptMs) {
            boolean started = exportCoordinator.start(client, new DownloadExportCoordinator.Request(
                    automaticTrigger, false, false, null, null, null));
            nextAutomaticExportAttemptMs = started ? 0L : now + 1_000L;
        }
        if (now - lastCacheEvictionMs >= CACHE_EVICTION_INTERVAL_MS) {
            lastCacheEvictionMs = now;
            applyCacheEviction(client);
        }
        maybeLogPerformanceSnapshot(now);
    }

    /**
     * Applies a {@link ModConfig.TransitionBehavior} to the download state.
     * Sends an appropriate HUD message when the state actually changes.
     *
     * @param eventName human-readable event name used only for logging
     */
    private static void applyTransition(Minecraft client,
                                        ModConfig.TransitionBehavior behavior,
                                        String eventName) {
        boolean wasActive = currentActive.get();
        boolean desired;
        switch (behavior) {
            case START -> desired = true;
            case STOP  -> desired = false;
            default    -> { return; } // KEEP — do nothing
        }
        if (wasActive == desired) return; // already in the right state

        if (desired) {
            requestDownloadStart(client, eventName);
            return;
        }

        currentActive.set(false);
        clearPendingCaptureState();
        finalizeCaptureOnStop(client, eventName);

        Component msg = Component.translatable("msg.worldmirror.downloadStop");
        WMPlayerMessages.sendOverlayMessage(client.player, msg);
        WMLogger.debug("Download deactivated lifecycleEvent=" + eventName);
    }

    // ── Output path ───────────────────────────────────────────────────────────

    /**
     * Returns the root folder for the mirror world.
     * Per-world save-location (from {@link MirrorMapping}) takes precedence over the global config.
     *
     * This preview performs no filesystem access and writes no configuration, so it is safe
     * for render and map-integration call sites. Real operations revalidate ownership and
     * resolve collisions immediately before creating or moving a directory.
     */
    public static Path previewOutputPath(Minecraft client) {
        String sourceId   = WorldMetadata.detectSourceId(client);
        return MirrorMapping.getInstance().previewOutputPath(sourceId);
    }

    /** Computes a source's mirror location without creating folders or writing configuration. */
    public static Path previewOutputPathForLocation(
            String sourceId, ModConfig.SaveLocation saveLocation) {
        return MirrorMapping.getInstance().previewOutputPath(sourceId, saveLocation);
    }

    /**
     * Records a per-world location change when no mirror directory exists yet.
     * Resolving and storing the exact folder name here ensures later downloads do
     * not continue using a stale location from a previous configuration.
     */
    public static void setMirrorSaveLocation(String sourceId, ModConfig.SaveLocation saveLocation) {
        MirrorMapping.getInstance().setSaveLocation(sourceId, saveLocation);
    }

    /**
     * Moves an existing mirror to another supported storage root and then records
     * that root as this world's per-world override. Existing target data is never
     * merged or overwritten implicitly.
     */
    public static MirrorMoveResult moveMirrorWorld(Minecraft client, ModConfig.SaveLocation targetLocation) {
        if (isActive()) {
            return MirrorMoveResult.failure("download_active");
        }
        if (isExportInProgress()) {
            return MirrorMoveResult.failure("export_in_progress");
        }
        String sourceId = WorldMetadata.detectSourceId(client);
        MirrorMapping mapping = MirrorMapping.getInstance();
        Path source = mapping.selectOutputPath(sourceId, mapping.effectiveSaveLocation(sourceId));
        if (!Files.isDirectory(source)) {
            return MirrorMoveResult.failure("source_missing");
        }
        Path target = mapping.selectOutputPath(sourceId, targetLocation);
        if (source.normalize().equals(target.normalize())) {
            setMirrorSaveLocation(sourceId, targetLocation);
            return MirrorMoveResult.success(source, target);
        }
        try {
            if (Files.exists(target)) {
                return MirrorMoveResult.failure("target_exists");
            }
            Files.createDirectories(target.getParent());
            Files.move(source, target);
            setMirrorSaveLocation(sourceId, targetLocation);
            return MirrorMoveResult.success(source, target);
        } catch (Exception e) {
            WMLogger.warn("Could not move mirror world from " + source + " to " + target, e);
            return MirrorMoveResult.failure("io_error");
        }
    }

    public record MirrorMoveResult(boolean success, String failureCode, Path source, Path target) {
        private static MirrorMoveResult success(Path source, Path target) {
            return new MirrorMoveResult(true, null, source, target);
        }

        private static MirrorMoveResult failure(String failureCode) {
            return new MirrorMoveResult(false, failureCode, null, null);
        }
    }

    private static void requestDownloadStart(Minecraft client, String reason) {
        Runnable continueStart = () -> requestOutputReady(client, () -> activateDownload(client, reason));
        MirrorWorldContext.Snapshot currentMirror = MirrorWorldContext.current();
        if (currentMirror.isMirror() && !mirrorCaptureWarningShown) {
            String bodyKey = currentMirror.state() == MirrorWorldContext.State.OUTDATED
                    ? "screen.worldmirror.captureMirror.outdated"
                    : "screen.worldmirror.captureMirror.body";
            ClientDialogs.confirm(client, new MirrorPrompt.Confirmation(
                    new MirrorPrompt.Text("screen.worldmirror.captureMirror.title"),
                    new MirrorPrompt.Text(bodyKey),
                    new MirrorPrompt.Text("screen.worldmirror.captureMirror.continue"),
                    new MirrorPrompt.Text("gui.cancel")), () -> {
                mirrorCaptureWarningShown = true;
                continueStart.run();
            }, () -> {});
            return;
        }
        continueStart.run();
    }

    /** Ensures an output world is current only after an explicit confirmation. */
    private static void requestOutputReady(Minecraft client, Runnable onReady) {
        String sourceId = WorldMetadata.detectSourceId(client);
        MirrorMapping mapping = MirrorMapping.getInstance();
        Path output = mapping.selectOutputPath(sourceId, mapping.effectiveSaveLocation(sourceId));
        MirrorMigrationPlan.Inspection plan = MirrorMigrationCoordinator.inspect(output);
        switch (plan.state()) {
            case NEW, CURRENT -> onReady.run();
            case OUTDATED -> ClientDialogs.confirm(client, new MirrorPrompt.Confirmation(
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.title"),
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.downloadBody", output.getFileName()),
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.downloadConfirm"),
                    new MirrorPrompt.Text("gui.cancel")),
                    () -> migrateOutputAndContinue(client, output, onReady), () -> {});
            case FUTURE -> ClientDialogs.alert(client, new MirrorPrompt.Alert(
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.futureTitle"),
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.futureBody"),
                    new MirrorPrompt.Text("gui.done")), () -> {});
            default -> ClientDialogs.alert(client, new MirrorPrompt.Alert(
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableTitle"),
                    new MirrorPrompt.Text("screen.worldmirror.upgrade.unavailableBody", plan.state().name()),
                    new MirrorPrompt.Text("gui.done")), () -> {});
        }
    }

    private static void migrateOutputAndContinue(Minecraft client, Path output, Runnable onReady) {
        MirrorPrompt.ProgressHandle progress = ClientDialogs.progress(client,
                new MirrorPrompt.Text("screen.worldmirror.upgrade.progressTitle"));
        Thread worker = new Thread(() -> {
            MirrorMigrationCoordinator.Result result = MirrorMigrationCoordinator.migrateApproved(output,
                    new MirrorMigrationProgress(client, progress));
            client.execute(() -> {
                progress.close();
                if (!result.success()) {
                    ClientDialogs.alert(client, new MirrorPrompt.Alert(
                            new MirrorPrompt.Text("screen.worldmirror.upgrade.failedTitle"),
                            new MirrorPrompt.Text("screen.worldmirror.upgrade.failedBody", result.failure()),
                            new MirrorPrompt.Text("gui.done")), () -> {});
                    return;
                }
                ClientDialogs.toast(client,
                        new MirrorPrompt.Text("screen.worldmirror.upgrade.completeTitle"),
                        new MirrorPrompt.Text("screen.worldmirror.upgrade.completeBody"));
                onReady.run();
            });
        }, "WM-MirrorMigration");
        worker.setDaemon(false);
        worker.start();
    }

    private static void activateDownload(Minecraft client, String reason) {
        if (!currentActive.compareAndSet(false, true)) return;
        activePipeline = DownloadPipeline.create(ModConfig.get().pipelineMode);
        resetDiagnosticSession();
        diagnosticSessionActive = ModConfig.get().performance.diagnosticPerformanceLogging;
        recordPerformanceTimings = diagnosticSessionActive;
        if (diagnosticSessionActive) DownloadConfigDiagnostics.log(client);
        EntityTracker.resetObservationEpochs();
        exportCoordinator.markEntitiesDirty();
        long nowMs = System.currentTimeMillis();
        activePipeline.reset(nowMs);
        nextAutomaticExportAttemptMs = 0L;
        lastCacheEvictionMs = nowMs;
        if (client.level != null) captureLoadedChunksAsync(client);
        WMPlayerMessages.sendOverlayMessage(client.player, Component.translatable("msg.worldmirror.downloadStart"));
        WMLogger.info("Download activated with pipeline=" + activePipeline.mode()
                + (reason == null ? "" : " by " + reason));
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Queues a wide area of already-loaded chunks for incremental capture on the
     * game thread. Capturing is spread across subsequent ticks to avoid long render
     * thread stalls while still keeping all chunk/world access on the correct thread.
     */
    private static void captureLoadedChunksAsync(Minecraft client) {
        ClientLevel world = client.level;
        if (world == null || client.player == null) return;

        ResourceKey<Level> dimension = world.dimension();
        int playerCX = client.player.getBlockX() >> 4;
        int playerCZ = client.player.getBlockZ() >> 4;
        int queued = captureQueue.queueLoaded(world, playerCX, playerCZ, INITIAL_CAPTURE_RANGE,
                "initial-capture");
        if (queued > 0) {
            WMLogger.debug("Queued " + queued + " loaded chunks for incremental capture in ["
                    + dimension.identifier() + "]...");
        }
    }

    /**
     * Captures loaded chunks around the player synchronously on the game thread.
     *
     * <p><b>Use only at disconnect / toggle-off time</b> (a one-shot event where a
     * brief freeze is acceptable and the world is still fully accessible). During
     * normal gameplay, the export coordinator instead schedules incremental
     * chunk capture across subsequent client ticks so large nearby refreshes do not
     * block a single render frame.</p>
     */
    private static void captureNearbyLoadedChunksSync(Minecraft client, String reason) {
        ClientLevel world = client.level;
        if (world == null || client.player == null) return;

        long startedNs = System.nanoTime();
        ResourceKey<Level> dimension = world.dimension();
        int playerCX = client.player.getBlockX() >> 4;
        int playerCZ = client.player.getBlockZ() >> 4;
        int captured = 0;

        for (int cx = playerCX - STOP_CAPTURE_RANGE; cx <= playerCX + STOP_CAPTURE_RANGE; cx++) {
            for (int cz = playerCZ - STOP_CAPTURE_RANGE; cz <= playerCZ + STOP_CAPTURE_RANGE; cz++) {
                ChunkAccess chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk wc)) continue;
                try {
                    if (ChunkSerializer.isChunkEmpty(wc)) continue;
                    CompoundTag nbt = ChunkSerializer.serialize(world, wc);
                    ChunkListener.addChunkNbt(dimension, wc.getPos(), nbt);
                    captured++;
                } catch (Exception e) {
                    WMLogger.warnRateLimited("capture-stop-" + reason, 30_000L,
                            "Stop-time capture failed chunk=" + wc.getPos()
                                    + " reason=" + reason, e);
                }
            }
        }

        if (captured > 0) {
            WMLogger.debug("Captured " + captured + " nearby chunks (range=" + STOP_CAPTURE_RANGE
                    + ") for " + reason + " elapsedMs="
                    + ((System.nanoTime() - startedNs) / 1_000_000L) + ".");
        }
    }

    /**
     * Finalisation path when download is deactivated.
     * Optionally captures nearby chunks and then writes all cached chunks once.
     */
    private static void finalizeCaptureOnStop(Minecraft client, String reason) {
        ModConfig.LifecycleConfig lifecycle = ModConfig.get().lifecycle;
        if (lifecycle.captureNearbyOnStop) {
            captureNearbyLoadedChunksSync(client, "stop-" + reason);
        }

        if (lifecycle.exportAllCachedOnStop) {
            exportCoordinator.start(client, new DownloadExportCoordinator.Request(
                    DownloadExportCoordinator.Trigger.STOP, false, true,
                    lastSourceId, lastSourceType, null));
        }
    }

    /**
     * Prepares a snapshot on the game thread, then hands it off to a background
     * background thread for the actual I/O work.
     */
    private static void clearPendingCaptureState() {
        captureQueue.clear();
        exportCoordinator.clearDeferred();
    }

    /** Drops source-scoped captured data before a different logical world becomes active. */
    private static void clearCapturedWorldState() {
        ChunkListener.clear();
        EntityTracker.clear();
    }

    /**
     * Applies cache-eviction rules from {@link ModConfig} to the chunk cache.
     * Safe to call on the game thread.
     */
    private static void applyCacheEviction(Minecraft client) {
        ModConfig cfg = ModConfig.get();
        long maxAgeMs = (long) cfg.cache.maxCacheAgeSeconds * 1000L;
        int maxCount = cfg.cache.maxCachedChunks;
        int maxDist = cfg.cache.maxCacheDistanceChunks;

        if (maxAgeMs <= 0 && maxCount <= 0 && maxDist <= 0) return;

        ResourceKey<Level> playerDim = (client.level != null)
                ? client.level.dimension() : null;
        int playerCX = (client.player != null) ? (client.player.getBlockX() >> 4) : 0;
        int playerCZ = (client.player != null) ? (client.player.getBlockZ() >> 4) : 0;

        ChunkListener.evictStale(maxAgeMs, maxCount, playerDim, playerCX, playerCZ, maxDist);
    }

    private static void resetDiagnosticSession() {
        diagnosticSessionStartedMs = System.currentTimeMillis();
        lastDiagnosticLogMs = diagnosticSessionStartedMs;
        captureQueue.resetDiagnostics();
        exportCoordinator.resetDiagnostics();
        worldFrameIntervals.reset();
        worldMirrorTickWork.reset();
        lastWorldFrameNs = 0L;
        lastGcByCollector.clear();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            lastGcByCollector.put(bean.getName(), new GcSnapshot(
                    Math.max(0L, bean.getCollectionCount()),
                    Math.max(0L, bean.getCollectionTime())));
        }
    }

    private static void maybeLogPerformanceSnapshot(long nowMs) {
        if (!ModConfig.get().performance.diagnosticPerformanceLogging
                || nowMs - lastDiagnosticLogMs < 30_000L) return;
        lastDiagnosticLogMs = nowMs;
        PipelineMetrics m = getPipelineMetrics();
        Runtime runtime = Runtime.getRuntime();
        long usedMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long committedMiB = runtime.totalMemory() / (1024L * 1024L);
        long gcCount = 0L;
        long gcTimeMs = 0L;
        StringBuilder collectors = new StringBuilder();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collectors.length() > 0) collectors.append('|');
            long currentCount = Math.max(0L, bean.getCollectionCount());
            long currentTimeMs = Math.max(0L, bean.getCollectionTime());
            GcSnapshot previous = lastGcByCollector.get(bean.getName());
            long countDelta = previous == null ? 0L
                    : Math.max(0L, currentCount - previous.count());
            long timeDelta = previous == null ? 0L
                    : Math.max(0L, currentTimeMs - previous.timeMs());
            gcCount += countDelta;
            gcTimeMs += timeDelta;
            collectors.append(bean.getName().replace(' ', '_'))
                    .append(':').append(countDelta).append('/').append(timeDelta);
            lastGcByCollector.put(bean.getName(), new GcSnapshot(currentCount, currentTimeMs));
        }
        DownloadCaptureQueue.DiagnosticSnapshot capture = captureQueue.snapshotDiagnostics();
        LatencyWindow.Summary captureLatency = capture.captureTickLatency();
        LatencyWindow.Summary unloadLatency = capture.unloadLatency();
        LatencyWindow.Summary frameInterval = worldFrameIntervals.snapshotAndReset();
        LatencyWindow.Summary tickWork = worldMirrorTickWork.snapshotAndReset();
        DownloadExportCoordinator.DiagnosticSnapshot export =
                exportCoordinator.snapshotDiagnostics();
        ChunkExporter.ExportTimings timings = export.timings();
        WMLogger.info("[perf] pipeline=" + m.mode()
                + " sessionMs=" + Math.max(0L, nowMs - diagnosticSessionStartedMs)
                + " cache=" + ChunkListener.getTotalCount()
                + " dirty=" + m.dirtyChunks()
                + " captureQueue=" + m.pendingCaptures()
                + " oldestHintMs=" + m.oldestCaptureAgeMs()
                + " coalesced=" + m.coalescedHints()
                + " dropped=" + m.droppedHints()
                + " hintReasons=" + capture.hintSummary()
                + " captureReasonLatency=" + capture.reasonLatencySummary()
                + " captureTickCount=" + captureLatency.observations()
                + " captureTickAvgUs=" + captureLatency.average()
                + " captureTickP95Us=" + captureLatency.p95()
                + " captureTickP99Us=" + captureLatency.p99()
                + " captureTickMaxUs=" + captureLatency.maximum()
                + " captureProcessed=" + capture.processed()
                + " captureCompleted=" + capture.completed()
                + " captureBudgetUs=" + ModConfig.get().performance.captureBudgetMicros
                + " captureBudgetOverruns=" + capture.budgetOverruns()
                + " captureBudgetMaxOverrunUs=" + capture.maximumBudgetOverrunUs()
                + " unloadCaptureCount=" + unloadLatency.observations()
                + " unloadCaptureAvgUs=" + unloadLatency.average()
                + " unloadCaptureP95Us=" + unloadLatency.p95()
                + " unloadCaptureP99Us=" + unloadLatency.p99()
                + " unloadCaptureMaxUs=" + unloadLatency.maximum()
                + " unloadCaptureFailures=" + capture.unloadFailures()
                + " frameCount=" + frameInterval.observations()
                + " frameAvgUs=" + frameInterval.average()
                + " frameP95Us=" + frameInterval.p95()
                + " frameP99Us=" + frameInterval.p99()
                + " frameMaxUs=" + frameInterval.maximum()
                + " wmTickCount=" + tickWork.observations()
                + " wmTickAvgUs=" + tickWork.average()
                + " wmTickP95Us=" + tickWork.p95()
                + " wmTickP99Us=" + tickWork.p99()
                + " wmTickMaxUs=" + tickWork.maximum()
                + " automaticExportSuppressed=" + export.automaticSuppressed()
                + " deferredExportCoalesced=" + export.deferredCoalesced()
                + " exportWorkerDutyMs=" + export.workerWallMillis()
                + " lastExportMs=" + m.lastExportMillis()
                + " dbLookupMs=" + timings.databaseLookupMs()
                + " materializeMs=" + timings.materializeMs()
                + " regionReadMs=" + timings.chunkReadMs()
                + " resolveMergeWriteMs=" + timings.resolveMergeWriteMs()
                + " regionFlushMs=" + timings.flushMs()
                + " regionVerifyMs=" + timings.verificationMs()
                + " entityWriteMs=" + timings.entityMs()
                + " dbCommitMs=" + export.durabilityIndexMillis()
                + " written=" + m.lastExportWritten()
                + " settled=" + m.lastExportSettled()
                + " unreadable=" + m.lastExportUnreadable()
                + " failures=" + m.exportFailures()
                + " heapMiB=" + usedMiB + "/" + committedMiB
                + " gcCountDelta=" + gcCount
                + " gcTimeMsDelta=" + gcTimeMs
                + " gcCollectors=" + collectors);
    }

    public static void exportNearbyToNewSave(Minecraft client,
                                              String worldName, int radiusChunks,
                                              NearbyExporter.Choice lineageChoice) {
        NearbyExporter.export(client, worldName, radiusChunks, lineageChoice);
    }
}

