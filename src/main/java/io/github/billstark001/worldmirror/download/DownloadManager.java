package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.conflict.ConflictResolver;
import io.github.billstark001.worldmirror.conflict.IgnoreResolver;
import io.github.billstark001.worldmirror.conflict.ManualResolver;
import io.github.billstark001.worldmirror.conflict.OverwriteResolver;
import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.ChunkSerializer;
import io.github.billstark001.worldmirror.core.ContainerTracker;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.ui.ClientDialogs;
import io.github.billstark001.worldmirror.ui.MirrorPrompt;
import net.fabricmc.loader.api.FabricLoader;
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
import java.sql.SQLException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
    private static final AtomicBoolean exportInProgress = new AtomicBoolean(false);
    /** Guards against starting a second initial-capture while one is still running. */
    private static final AtomicBoolean captureInProgress = new AtomicBoolean(false);
    private static final int INITIAL_CAPTURE_RANGE = 33;
    private static final int PRE_EXPORT_CAPTURE_RANGE = 8;
    private static final int STOP_CAPTURE_RANGE = 6;
    private static final int MAX_CAPTURE_CHUNKS_PER_TICK = 64;
    private static final int MAX_DIAGNOSTIC_CAPTURE_REASONS = 32;
    private static final int LIGHT_UPDATE_COALESCE_TICKS = 2;
    private static final long CACHE_EVICTION_INTERVAL_MS = 5_000L;
    private static final Object captureQueueLock = new Object();
    private static final ArrayDeque<PendingChunkCapture> pendingCaptures = new ArrayDeque<>();
    private static final Set<CaptureKey> pendingCaptureSet = new HashSet<>();
    private static final Map<CaptureKey, Long> pendingLightUpdates = new HashMap<>();
    private static ExportRequest pendingExport = null;
    private static long clientTick = 0;
    private static long nextAutomaticExportAttemptMs;
    private static volatile boolean mirrorCaptureWarningShown;
    private static volatile DownloadPipeline activePipeline =
            DownloadPipeline.create(ModConfig.DownloadPipelineMode.STABLE_PERIODIC);
    private static long coalescedCaptureHints;
    private static long droppedCaptureHints;
    private static final Map<String, CaptureHintCounters> captureHintsByReason = new HashMap<>();
    private static final Map<String, LatencyWindow> captureLatenciesByReason = new HashMap<>();
    private static final AtomicBoolean captureReconciliationNeeded = new AtomicBoolean();
    private static final AtomicLong exportFailures = new AtomicLong();
    private static final AtomicLong entityRevision = new AtomicLong(1L);
    private static final AtomicLong durableEntityRevision = new AtomicLong();
    private static final LatencyWindow captureTickLatencies = new LatencyWindow(4096);
    private static final LatencyWindow unloadCaptureLatencies = new LatencyWindow(4096);
    private static final LatencyWindow worldFrameIntervals = new LatencyWindow(8192);
    private static final LatencyWindow worldMirrorTickWork = new LatencyWindow(4096);
    private static final AtomicLong unloadCaptureFailures = new AtomicLong();
    private static final AtomicLong captureBudgetOverruns = new AtomicLong();
    private static final AtomicLong captureBudgetMaxOverrunUs = new AtomicLong();
    private static final AtomicLong captureProcessed = new AtomicLong();
    private static final AtomicLong captureCompleted = new AtomicLong();
    private static final AtomicLong automaticExportSuppressed = new AtomicLong();
    private static final AtomicLong deferredExportCoalesced = new AtomicLong();
    private static final AtomicLong exportWorkerWallMs = new AtomicLong();
    private static volatile long lastExportMillis;
    private static volatile int lastExportWritten;
    private static volatile int lastExportSettled;
    private static volatile int lastExportUnreadable;
    private static volatile ChunkExporter.ExportTimings lastExportTimings =
            new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0);
    private static volatile long lastDurabilityIndexMillis;
    private static volatile long lastDiagnosticLogMs;
    private static volatile long diagnosticSessionStartedMs;
    private static volatile boolean diagnosticSessionActive;
    private static volatile boolean recordPerformanceTimings;
    private static volatile long lastWorldFrameNs;
    private static final Map<String, GcSnapshot> lastGcByCollector = new HashMap<>();
    private static final ThreadPoolExecutor exportExecutor = new ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "WM-Export");
                thread.setDaemon(false);
                return thread;
            });

    private record CaptureKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) { }
    private record PendingChunkCapture(CaptureKey key, String reason, long enqueuedAtMs) { }
    private record GcSnapshot(long count, long timeMs) { }

    private enum ExportTrigger {
        MANUAL(false, 2),
        STOP(false, 3),
        PERIODIC(true, 0),
        ADAPTIVE_HIGH_WATERMARK(true, 0),
        ADAPTIVE_MAX_LATENCY(true, 0);

        private final boolean automatic;
        private final int priority;

        ExportTrigger(boolean automatic, int priority) {
            this.automatic = automatic;
            this.priority = priority;
        }
    }

    private static final class CaptureHintCounters {
        private long received;
        private long queued;
        private long coalesced;
        private long dropped;
    }
    private record ExportRequest(
            ExportTrigger trigger,
            boolean shouldNotify,
            boolean preCaptureAlreadyDone,
            String preferredSourceId,
            String preferredSourceType,
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot
    ) { }

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
        return exportInProgress.get();
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
        if (currentActive.get()) entityRevision.incrementAndGet();
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
        synchronized (captureQueueLock) {
            PendingChunkCapture oldest = pendingCaptures.peekFirst();
            long age = oldest == null ? 0L
                    : Math.max(0L, System.currentTimeMillis() - oldest.enqueuedAtMs());
            return new PipelineMetrics(activePipeline.mode(), pendingCaptures.size(),
                    ChunkListener.getDirtyCount(), age, coalescedCaptureHints,
                    droppedCaptureHints, lastExportMillis,
                    lastExportWritten, lastExportSettled, lastExportUnreadable,
                    exportFailures.get());
        }
    }

    /** Coalesces a packet/event hint into one main-thread capture per chunk. */
    public static void queueChunkCapture(ClientLevel world, ChunkPos pos, String reason) {
        if (!currentActive.get() || world == null || pos == null) return;
        queueCaptureKey(new CaptureKey(world.dimension(),
                pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4), reason);
    }

    /** Last-chance main-thread capture before Fabric removes a loaded chunk. */
    public static void captureChunkBeforeUnload(ClientLevel world, LevelChunk chunk) {
        if (!currentActive.get() || world == null || chunk == null) return;
        long startedNs = System.nanoTime();
        CaptureKey key = new CaptureKey(world.dimension(),
                chunk.getPos().getMinBlockX() >> 4, chunk.getPos().getMinBlockZ() >> 4);
        synchronized (captureQueueLock) {
            pendingLightUpdates.remove(key);
            captureInProgress.set(hasPendingCaptureWorkLocked());
        }
        try {
            if (!ChunkSerializer.isChunkEmpty(chunk)) {
                ChunkListener.addChunkNbt(world.dimension(), chunk.getPos(),
                        ChunkSerializer.serialize(world, chunk));
            }
        } catch (Exception e) {
            unloadCaptureFailures.incrementAndGet();
            WMLogger.warnRateLimited("capture-unload", 30_000L,
                    "Final capture before unload failed chunk=" + chunk.getPos()
                            + "; cached data may be stale", e);
        } finally {
            if (recordPerformanceTimings) {
                long elapsedUs = (System.nanoTime() - startedNs) / 1_000L;
                unloadCaptureLatencies.record(elapsedUs);
                recordCaptureReasonLatency("unload-final", elapsedUs);
                logSlowCapture("unload", "unload-final", key, 0L, elapsedUs);
            }
        }
    }

    private static boolean queueCaptureKey(CaptureKey key, String reason) {
        synchronized (captureQueueLock) {
            CaptureHintCounters reasonCounters = null;
            if (recordPerformanceTimings) {
                String metricReason = captureMetricReasonLocked(reason);
                reasonCounters = captureHintsByReason.computeIfAbsent(
                        metricReason, ignored -> new CaptureHintCounters());
                reasonCounters.received++;
            }
            if (!pendingCaptureSet.add(key)) {
                coalescedCaptureHints++;
                if (reasonCounters != null) reasonCounters.coalesced++;
                return false;
            }
            int limit = ModConfig.get().performance.maxPendingCaptureHints;
            if (pendingCaptures.size() >= limit) {
                pendingCaptureSet.remove(key);
                droppedCaptureHints++;
                if (reasonCounters != null) reasonCounters.dropped++;
                captureReconciliationNeeded.set(true);
                WMLogger.warnRateLimited("capture-queue-capacity", 30_000L,
                        "Capture-hint queue reached its configured limit (" + limit
                                + "); coalescing overflow and scheduling a loaded-chunk reconciliation.");
                return false;
            }
            pendingCaptures.add(new PendingChunkCapture(key, reason, System.currentTimeMillis()));
            if (reasonCounters != null) reasonCounters.queued++;
            captureInProgress.set(true);
            return true;
        }
    }

    /** Keeps diagnostic cardinality bounded even if an integration supplies arbitrary reasons. */
    private static String captureMetricReasonLocked(String reason) {
        String normalized = reason == null || reason.isBlank() ? "unknown" : reason;
        if (captureHintsByReason.containsKey(normalized)) return normalized;
        if (captureHintsByReason.size() < MAX_DIAGNOSTIC_CAPTURE_REASONS - 1) return normalized;
        return "other";
    }

    /** Queues a normal capture when a light packet arrives before its base chunk. */
    public static void queueLightUpdateCapture(ClientLevel world, ChunkPos pos) {
        if (world == null || pos == null) return;

        CaptureKey key = new CaptureKey(world.dimension(),
                pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4);
        queueCaptureKey(key, "light-update-no-base");
    }

    /**
     * Coalesces repeated light-only packets before marking their cached chunk
     * dirty.  The overlay itself is updated immediately; only export dirtiness
     * waits for the short fixed window.
     */
    public static void markLightUpdateDirty(ClientLevel world, ChunkPos pos) {
        if (world == null || pos == null) return;

        CaptureKey key = new CaptureKey(world.dimension(),
                pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4);
        synchronized (captureQueueLock) {
            pendingLightUpdates.putIfAbsent(key, clientTick + LIGHT_UPDATE_COALESCE_TICKS);
            captureInProgress.set(hasPendingCaptureWorkLocked());
        }
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
        if (ChunkListener.isEmpty() && !canPreCapture) {
            Component msg = Component.translatable("msg.worldmirror.noChunks");
            WMPlayerMessages.sendSystemMessage(client.player, msg);
            WMLogger.debug("Manual export ignored because no chunks are cached.");
            return;
        }
        if (exportInProgress.get()) {
            Component msg = Component.translatable("msg.worldmirror.exportBusy");
            WMPlayerMessages.sendSystemMessage(client.player, msg);
            deferExport(new ExportRequest(ExportTrigger.MANUAL, true, false,
                    null, null, ContainerTracker.snapshotSavedData()));
            WMLogger.debug("Export already in progress; coalesced another export request.");
            return;
        }
        startBackgroundSync(client, new ExportRequest(
                ExportTrigger.MANUAL, true, false, null, null, null));
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

        clientTick++;
        flushPendingLightUpdates();
        processPendingCaptures(client);
        tryStartDeferredExport(client);

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
        boolean entityDirty = entityRevision.get() > durableEntityRevision.get();
        boolean hasDurabilityWork = dirty > 0 || entityDirty;
        DownloadPipeline.Decision decision = activePipeline.evaluate(
                now, dirty, hasDurabilityWork, cfg);
        ExportTrigger automaticTrigger = switch (decision) {
            case PERIODIC -> ExportTrigger.PERIODIC;
            case HIGH_WATERMARK -> ExportTrigger.ADAPTIVE_HIGH_WATERMARK;
            case MAX_LATENCY -> ExportTrigger.ADAPTIVE_MAX_LATENCY;
            case NONE -> null;
        };
        if (automaticTrigger != null && now >= nextAutomaticExportAttemptMs) {
            boolean started = startBackgroundSync(client, new ExportRequest(
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
        return previewOutputPathForSource(sourceId);
    }

    private static Path previewOutputPathForSource(String sourceId) {
        return previewOutputPathForLocation(sourceId, effectiveSaveLocation(sourceId));
    }

    private static ModConfig.SaveLocation effectiveSaveLocation(String sourceId) {
        String perWorldLoc = MirrorMapping.getInstance().getPerWorldSaveLocation(sourceId);
        if (perWorldLoc != null) {
            try {
                return ModConfig.SaveLocation.valueOf(perWorldLoc);
            } catch (IllegalArgumentException e) {
                return ModConfig.get().defaultSaveLocation;
            }
        }
        return ModConfig.get().defaultSaveLocation;
    }

    /** Computes a source's mirror location without creating folders or writing configuration. */
    public static Path previewOutputPathForLocation(
            String sourceId, ModConfig.SaveLocation saveLocation) {
        String baseName = MirrorMapping.getInstance().previewBaseFolderName(sourceId);
        Path base = outputRoot(saveLocation);

        String saveLocName = saveLocation.name();

        String cachedResolved = MirrorMapping.getInstance()
                .previewResolvedFolderName(sourceId, saveLocName);
        return base.resolve(cachedResolved == null ? baseName : cachedResolved);
    }

    private static Path outputRoot(ModConfig.SaveLocation saveLocation) {
        return saveLocation == ModConfig.SaveLocation.SAVES
                ? FabricLoader.getInstance().getGameDir().resolve("saves")
                : FabricLoader.getInstance().getGameDir().resolve("downloaded_worlds");
    }

    /**
     * Records a per-world location change when no mirror directory exists yet.
     * Resolving and storing the exact folder name here ensures later downloads do
     * not continue using a stale location from a previous configuration.
     */
    public static void setMirrorSaveLocation(String sourceId, ModConfig.SaveLocation saveLocation) {
        Path target = selectOutputPathForOperation(sourceId, saveLocation);
        MirrorMapping.getInstance().recordMirrorLocation(
                sourceId, saveLocation.name(), target.getFileName().toString());
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
        Path source = selectOutputPathForOperation(sourceId, effectiveSaveLocation(sourceId));
        if (!Files.isDirectory(source)) {
            return MirrorMoveResult.failure("source_missing");
        }
        Path target = selectOutputPathForOperation(sourceId, targetLocation);
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

    /**
     * Resolves a collision-free output path under {@code base} for the given
     * {@code folderName} and {@code sourceId}.
     *
     * <ul>
     *   <li>If the candidate folder does not exist → return it.</li>
     *   <li>If it exists and is owned by {@code sourceId} (matching
     *       {@code worldmirror_meta.json}) → return it.</li>
     *   <li>Otherwise append {@code _2}, {@code _3}, … until a free or owned
     *       folder is found.</li>
     * </ul>
     */
    private static Path resolveOutputPath(Path base, String folderName, String sourceId) {
        Path candidate = base.resolve(folderName);
        if (isFolderFreeOrOwned(candidate, sourceId)) return candidate;

        for (int suffix = 2; suffix < Integer.MAX_VALUE; suffix++) {
            candidate = base.resolve(folderName + "_" + suffix);
            if (isFolderFreeOrOwned(candidate, sourceId)) return candidate;
        }
        throw new IllegalStateException("No collision-free mirror folder name is available");
    }

    /**
     * Returns {@code true} if the folder is safe to use:
     * either it does not exist yet, or it already belongs to {@code sourceId}.
     */
    private static boolean isFolderFreeOrOwned(Path folder, String sourceId) {
        if (!folder.toFile().exists()) return true;
        return WorldMetadata.isOwnedBy(folder, sourceId);
    }

    /** Performs filesystem validation only at an explicit write or move boundary. */
    private static Path selectOutputPathForOperation(
            String sourceId, ModConfig.SaveLocation saveLocation) {
        Path base = outputRoot(saveLocation);
        String baseName = MirrorMapping.getInstance().previewBaseFolderName(sourceId);
        String resolved = MirrorMapping.getInstance()
                .previewResolvedFolderName(sourceId, saveLocation.name());
        if (resolved != null) {
            Path reserved = base.resolve(resolved);
            if (isFolderFreeOrOwned(reserved, sourceId)) return reserved;
        }
        return resolveOutputPath(base, baseName, sourceId);
    }

    /** Atomically claims and records the output directory for a real write operation. */
    private static Path claimOutputDirectory(String sourceId) throws java.io.IOException {
        ModConfig.SaveLocation saveLocation = effectiveSaveLocation(sourceId);
        Path base = outputRoot(saveLocation);
        Files.createDirectories(base);

        String baseName = MirrorMapping.getInstance().previewBaseFolderName(sourceId);
        Path candidate = selectOutputPathForOperation(sourceId, saveLocation);
        while (true) {
            try {
                Files.createDirectory(candidate);
                break;
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                if (WorldMetadata.isOwnedBy(candidate, sourceId)) break;
            }
            candidate = selectOutputPathForOperation(sourceId, saveLocation);
        }

        String resolvedName = candidate.getFileName().toString();
        MirrorMapping.getInstance().recordResolvedFolderName(
                sourceId, saveLocation.name(), resolvedName);
        if (!resolvedName.equals(baseName)) {
            WMLogger.debug("Folder name collision resolved: '"
                    + baseName + "' → '" + resolvedName + "'");
        }
        return candidate;
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
        Path output = selectOutputPathForOperation(sourceId, effectiveSaveLocation(sourceId));
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
        entityRevision.incrementAndGet();
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
        int queued = queueLoadedChunks(world, playerCX, playerCZ, INITIAL_CAPTURE_RANGE,
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
     * normal gameplay, {@link #startBackgroundSync} instead schedules incremental
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
            startBackgroundSync(client, new ExportRequest(
                    ExportTrigger.STOP, false, true,
                    lastSourceId, lastSourceType, null));
        }
    }

    /**
     * Prepares a snapshot on the game thread, then hands it off to a background
     * background thread for the actual I/O work.
     */
    private static boolean startBackgroundSync(Minecraft client, ExportRequest request) {
        if (exportInProgress.get()) {
            if (request.trigger().automatic) {
                automaticExportSuppressed.incrementAndGet();
                return false;
            }
            deferExport(withContainerSnapshot(request));
            WMLogger.debug("Export already in progress; queued trigger="
                    + request.trigger() + " for one deferred pass.");
            return false;
        }

        // A light overlay is already up to date, but its dirty timestamp is
        // deliberately delayed so a burst exports as one coherent chunk write.
        if (!request.preCaptureAlreadyDone() && hasPendingLightUpdates()) {
            if (request.trigger().automatic) {
                automaticExportSuppressed.incrementAndGet();
            } else {
                deferExport(request);
            }
            return false;
        }

        // Queue nearby capture work and defer export until that incremental
        // capture has finished. This keeps all chunk/world access on the main
        // thread without blocking it for hundreds of serialisations at once.
        if (!request.preCaptureAlreadyDone() && request.shouldNotify()
                && ModConfig.get().lifecycle.captureNearbyBeforeExport
                && client.level != null && client.player != null) {
            int playerCX = client.player.getBlockX() >> 4;
            int playerCZ = client.player.getBlockZ() >> 4;
            int queued = queueLoadedChunks(client.level, playerCX, playerCZ,
                    PRE_EXPORT_CAPTURE_RANGE, "pre-export");
            if (queued > 0 || captureInProgress.get()) {
                deferExport(new ExportRequest(request.trigger(), request.shouldNotify(), true,
                        request.preferredSourceId(), request.preferredSourceType(),
                        request.containerSnapshot()));
                return false;
            }
        }

        // ── Game-thread preparations ──────────────────────────────────────────
        ChunkListener.DirtySnapshot snapshot = ChunkListener.snapshotDirtyState();
        EntityTracker.pruneToMatchCapturedChunks();

        // Capture entities only when an entity packet/lifecycle event advanced
        // their revision. This retains periodic correctness without rewriting
        // every entity region on terrain-only passes.
        long entitySnapshotRevision = entityRevision.get();
        boolean captureEntities = entitySnapshotRevision > durableEntityRevision.get();
        if (captureEntities && client.level != null) {
            EntityTracker.captureEntitiesForWorld(client.level);
            EntityTracker.pruneToMatchCapturedChunks();
        }
        Map<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>> entitySnapshot =
                captureEntities ? EntityTracker.snapshot() : Map.of();
        Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot =
                request.containerSnapshot() != null
                        ? request.containerSnapshot()
                        : ContainerTracker.snapshotSavedData();

        // Collect source info while on the game thread
        String sourceId = request.preferredSourceId();
        String sourceType = request.preferredSourceType();
        if (isUnknownSourceId(sourceId)) {
            sourceId = WorldMetadata.detectSourceId(client);
        }
        if (isBlank(sourceType)) {
            sourceType = WorldMetadata.detectSourceType(client);
        }
        if (isUnknownSourceId(sourceId) && lastSourceId != null) {
            sourceId = lastSourceId;
        }
        if (isBlank(sourceType) && lastSourceType != null) {
            sourceType = lastSourceType;
        }
        if (isUnknownSourceId(sourceId)) sourceId = "unknown";
        if (isBlank(sourceType)) sourceType = "server";

        lastSourceId = sourceId;
        lastSourceType = sourceType;

        final String finalSourceId = sourceId;
        final String finalSourceType = sourceType;

        Path worldFolder;
        try {
            worldFolder = claimOutputDirectory(finalSourceId);
        } catch (Exception e) {
            WMLogger.warn("Export output directory preparation failed source=" + finalSourceId, e);
            return false;
        }

        int totalChunks = snapshot.chunks().values().stream().mapToInt(Map::size).sum();
        WMLogger.debug("Queued export: trigger=" + request.trigger()
                + " dirtySnapshot=" + totalChunks + " dimensions="
                + snapshot.chunks().size() + " pipeline=" + activePipeline.mode());

        // ── Background thread ─────────────────────────────────────────────────
        exportInProgress.set(true);
        activePipeline.onExportStarted(System.currentTimeMillis(), totalChunks, ModConfig.get());
        final Path finalWorldFolder = worldFolder;
        final boolean diagnosticExport =
                ModConfig.get().performance.diagnosticPerformanceLogging;

        Runnable worker = () -> {
            ChunkDatabase db = null;
            long exportStartedNs = System.nanoTime();
            long exportStartedCpuNs = currentThreadCpuTimeNs();
            try {
                MirrorMigrationPlan.Inspection readiness = MirrorMigrationCoordinator.inspect(finalWorldFolder);
                if (!readiness.mayCreateOrWriteWithoutMigration()) {
                    WMLogger.warn("Mirror requires an explicit upgrade or is not writable ("
                            + readiness.state() + "); export aborted without modifying it.");
                    notifyExportFailure(request.shouldNotify());
                    return;
                }

                boolean createFreshWorld = readiness.state() == MirrorMigrationPlan.State.NEW;
                WorldMetadata meta = createFreshWorld
                        ? WorldMetadata.create(finalSourceId, finalSourceType, "synchronized")
                        : readiness.metadata();

                // New worlds still need their initial generator and embedded pack.
                // Existing worlds are never migrated from this background worker.
                boolean worldgenReady = WorldStructureCreator.createLoadableWorld(
                        finalWorldFolder,
                        finalSourceId,
                        createFreshWorld,
                        createFreshWorld);
                if (!worldgenReady) {
                    WMLogger.warn("World generation setup failed; export aborted before writing chunks.");
                    notifyExportFailure(request.shouldNotify());
                    return;
                }
                if (createFreshWorld) {
                    meta.markWorldgenCurrent(
                            net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(),
                            io.github.billstark001.worldmirror.io.MirrorWorldgenAssets.ASSET_REVISION);
                    meta.save(finalWorldFolder);
                }

                // Open (or create) the chunk database for this mirror world
                try {
                    db = ChunkDatabase.open(finalWorldFolder, finalSourceId);
                } catch (SQLException e) {
                    WMLogger.warn("Chunk database open failed; export aborted world="
                            + finalWorldFolder, e);
                    notifyExportFailure(request.shouldNotify());
                    return;
                }

                // Migrate legacy chunkUpdateTimes from JSON → SQLite (idempotent)
                meta.migrateAndCleanChunkTimes(db, finalWorldFolder);

                ConflictResolver resolver = buildResolverForSource(finalSourceId);

                ChunkExporter.ExportResult result = ChunkExporter.exportChunks(
                        finalWorldFolder, snapshot, entitySnapshot,
                        containerSnapshot, resolver, db);
                lastExportTimings = result.timings();

                Map<ResourceKey<Level>, Map<ChunkPos, Long>> durableWritten = new HashMap<>();
                boolean durabilityIndexSuccessful = true;
                long durabilityIndexStartedNs = System.nanoTime();
                for (Map.Entry<ResourceKey<Level>, Set<ChunkPos>> dimEntry
                        : result.unreadableChunks().entrySet()) {
                    if (dimEntry.getValue().isEmpty()) continue;
                    String dimStr = dimEntry.getKey().identifier().toString();
                    if (db.removeUnreadableUpdates(dimStr, dimEntry.getValue())) {
                        WMLogger.warn("Removed stale durability claims for unreadable region chunks dimension="
                                + dimStr + " chunks=" + dimEntry.getValue().size());
                    } else {
                        durabilityIndexSuccessful = false;
                    }
                }
                for (Map.Entry<ResourceKey<Level>, Map<ChunkPos, ChunkExporter.WriteStamp>> dimEntry
                        : result.written().entrySet()) {
                    String dimStr = dimEntry.getKey().identifier().toString();
                    Map<ChunkPos, Long> timestamps = new HashMap<>();
                    Map<ChunkPos, Long> revisions = new HashMap<>();
                    dimEntry.getValue().forEach((pos, stamp) -> {
                        timestamps.put(pos, stamp.capturedAtMs());
                        revisions.put(pos, stamp.revision());
                    });
                    if (db.recordUpdates(dimStr, timestamps, "world_mirror")) {
                        durableWritten.put(dimEntry.getKey(), revisions);
                    } else {
                        durabilityIndexSuccessful = false;
                        WMLogger.warn("Durability-index commit failed for [" + dimStr
                                + "]; retaining " + revisions.size() + " revision(s) for retry.");
                    }
                }
                lastDurabilityIndexMillis =
                        (System.nanoTime() - durabilityIndexStartedNs) / 1_000_000L;

                boolean invalidate = ModConfig.get().cache.invalidateAfterExport;
                ChunkListener.acknowledge(result.settledRevisions(), invalidate);
                ChunkListener.acknowledge(durableWritten, invalidate);
                if (result.entityWritesSuccessful()) {
                    durableEntityRevision.accumulateAndGet(entitySnapshotRevision, Math::max);
                    if (entityRevision.get() == entitySnapshotRevision) {
                        EntityTracker.discardDurableEmptyMarkers(entitySnapshot);
                    }
                }

                boolean passSuccessful = result.chunkWritesSuccessful()
                        && result.entityWritesSuccessful()
                        && durabilityIndexSuccessful;
                if (passSuccessful) {
                    meta.markSyncComplete(finalWorldFolder);
                } else {
                    exportFailures.incrementAndGet();
                }

                int totalWritten = result.totalWritten();
                int totalUnreadable = result.unreadableChunks().values().stream()
                        .mapToInt(Set::size).sum();
                lastExportWritten = totalWritten;
                lastExportUnreadable = totalUnreadable;
                lastExportSettled = result.settledWithoutWrite().values().stream()
                        .mapToInt(Map::size).sum();
                long elapsedMs = (System.nanoTime() - exportStartedNs) / 1_000_000L;
                if (diagnosticExport) {
                    long cpuNowNs = currentThreadCpuTimeNs();
                    long cpuMs = exportStartedCpuNs < 0L || cpuNowNs < exportStartedCpuNs
                            ? -1L : (cpuNowNs - exportStartedCpuNs) / 1_000_000L;
                    ChunkExporter.ExportTimings timings = result.timings();
                    WMLogger.info("[perf] export trigger=" + request.trigger()
                            + " pipeline=" + activePipeline.mode()
                            + " dirtySnapshot=" + totalChunks
                            + " written=" + totalWritten
                            + " unreadable=" + totalUnreadable
                            + " dbLookupMs=" + timings.databaseLookupMs()
                            + " materializeMs=" + timings.materializeMs()
                            + " regionReadMs=" + timings.chunkReadMs()
                            + " resolveMergeWriteMs=" + timings.resolveMergeWriteMs()
                            + " regionFlushMs=" + timings.flushMs()
                            + " regionVerifyMs=" + timings.verificationMs()
                            + " entityWriteMs=" + timings.entityMs()
                            + " dbCommitMs=" + lastDurabilityIndexMillis
                            + " workerCpuMs=" + cpuMs
                            + " elapsedMs=" + elapsedMs);
                }
                WMLogger.info("Export pass complete: status="
                        + (passSuccessful ? "success" : "partial")
                        + " pipeline=" + activePipeline.mode()
                        + " trigger=" + request.trigger()
                        + " dirtySnapshot=" + totalChunks
                        + " written=" + totalWritten
                        + " unreadable=" + totalUnreadable
                        + " settled=" + lastExportSettled
                        + " dirtyRemaining=" + ChunkListener.getDirtyCount()
                        + " elapsedMs=" + elapsedMs);

                if (request.shouldNotify() && passSuccessful) {
                    Minecraft.getInstance().execute(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        WMPlayerMessages.sendSystemMessage(
                                mc.player, Component.translatable("msg.worldmirror.exportDone"));
                    });
                } else if (request.shouldNotify()) {
                    notifyExportFailure(true);
                }
            } catch (Exception e) {
                exportFailures.incrementAndGet();
                WMLogger.warn("Export pass failed trigger=" + request.trigger()
                        + " world=" + finalWorldFolder, e);
                notifyExportFailure(request.shouldNotify());
            } finally {
                lastExportMillis = (System.nanoTime() - exportStartedNs) / 1_000_000L;
                if (diagnosticExport) exportWorkerWallMs.addAndGet(lastExportMillis);
                if (db != null) db.close();
                exportInProgress.set(false);
                Minecraft.getInstance().execute(() ->
                        tryStartDeferredExport(Minecraft.getInstance()));
            }
        };
        exportExecutor.execute(worker);
        return true;
    }

    private static long currentThreadCpuTimeNs() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()
                ? bean.getCurrentThreadCpuTime() : -1L;
    }

    private static int queueLoadedChunks(ClientLevel world, int playerCX, int playerCZ,
                                         int range, String reason) {
        if (world == null || range <= 0) return 0;

        ResourceKey<Level> dimension = world.dimension();
        int queued = 0;
        synchronized (captureQueueLock) {
            for (int cx = playerCX - range; cx <= playerCX + range; cx++) {
                for (int cz = playerCZ - range; cz <= playerCZ + range; cz++) {
                    ChunkAccess chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (!(chunk instanceof LevelChunk)) continue;
                    CaptureKey key = new CaptureKey(dimension, cx, cz);
                    if (queueCaptureKey(key, reason)) {
                        queued++;
                    }
                }
            }
        }
        return queued;
    }

    private static void notifyExportFailure(boolean notify) {
        if (!notify) return;
        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            WMPlayerMessages.sendSystemMessage(mc.player,
                    Component.translatable("msg.worldmirror.exportFailed")
                            .withStyle(ChatFormatting.RED));
        });
    }

    private static void processPendingCaptures(Minecraft client) {
        ClientLevel world = client.level;
        if (world == null) return;

        ResourceKey<Level> currentDimension = world.dimension();
        int processed = 0;
        int captured = 0;
        long startedNs = System.nanoTime();
        long budgetNs = (long) ModConfig.get().performance.captureBudgetMicros * 1_000L;

        while (processed < MAX_CAPTURE_CHUNKS_PER_TICK
                && (processed == 0 || System.nanoTime() - startedNs < budgetNs)) {
            PendingChunkCapture request;
            synchronized (captureQueueLock) {
                request = pendingCaptures.pollFirst();
                if (request != null) {
                    pendingCaptureSet.remove(request.key());
                }
                captureInProgress.set(hasPendingCaptureWorkLocked());
            }

            if (request == null) {
                break;
            }

            processed++;
            if (!currentDimension.equals(request.key().dimension())) {
                continue;
            }

            ChunkAccess chunk = world.getChunk(request.key().chunkX(), request.key().chunkZ(),
                    ChunkStatus.FULL, false);
            if (!(chunk instanceof LevelChunk wc)) {
                continue;
            }

            long captureStartedNs = System.nanoTime();
            try {
                if (ChunkSerializer.isChunkEmpty(wc)) continue;
                CompoundTag nbt = ChunkSerializer.serialize(world, wc);
                ChunkListener.addChunkNbt(request.key().dimension(), wc.getPos(), nbt);
                captured++;
            } catch (Exception e) {
                WMLogger.warnRateLimited("capture-incremental-" + request.reason(), 30_000L,
                        "Incremental capture failed chunk=" + wc.getPos()
                                + " reason=" + request.reason(), e);
            } finally {
                if (recordPerformanceTimings) {
                    long elapsedUs = (System.nanoTime() - captureStartedNs) / 1_000L;
                    String metricReason = recordCaptureReasonLatency(request.reason(), elapsedUs);
                    logSlowCapture("incremental", metricReason, request.key(),
                            Math.max(0L, System.currentTimeMillis() - request.enqueuedAtMs()),
                            elapsedUs);
                }
            }
        }

        if (processed > 0 && recordPerformanceTimings) {
            long elapsedNs = System.nanoTime() - startedNs;
            captureTickLatencies.record(elapsedNs / 1_000L);
            captureProcessed.addAndGet(processed);
            captureCompleted.addAndGet(captured);
            if (elapsedNs > budgetNs) {
                captureBudgetOverruns.incrementAndGet();
                captureBudgetMaxOverrunUs.accumulateAndGet(
                        (elapsedNs - budgetNs) / 1_000L, Math::max);
            }
        }

        if (!captureInProgress.get() && captureReconciliationNeeded.compareAndSet(true, false)) {
            captureLoadedChunksAsync(client);
        }
    }

    private static String recordCaptureReasonLatency(String reason, long elapsedUs) {
        synchronized (captureQueueLock) {
            String metricReason = captureLatencyReasonLocked(reason);
            captureLatenciesByReason.computeIfAbsent(
                    metricReason, ignored -> new LatencyWindow(512)).record(elapsedUs);
            return metricReason;
        }
    }

    private static String captureLatencyReasonLocked(String reason) {
        String normalized = reason == null || reason.isBlank() ? "unknown" : reason;
        if (captureLatenciesByReason.containsKey(normalized)) return normalized;
        if (captureLatenciesByReason.size() < MAX_DIAGNOSTIC_CAPTURE_REASONS - 1) {
            return normalized;
        }
        return "other";
    }

    private static void logSlowCapture(
            String origin, String reason, CaptureKey key, long queueAgeMs, long elapsedUs) {
        long thresholdUs = Math.max(5_000L,
                (long) ModConfig.get().performance.captureBudgetMicros * 4L);
        if (elapsedUs < thresholdUs) return;
        WMLogger.infoRateLimited("slow-capture-" + origin + '-' + reason, 30_000L,
                "[perf] slowCapture origin=" + origin
                        + " reason=" + reason
                        + " dimension=" + key.dimension().identifier()
                        + " chunk=" + key.chunkX() + ',' + key.chunkZ()
                        + " queueAgeMs=" + queueAgeMs
                        + " elapsedUs=" + elapsedUs
                        + " thresholdUs=" + thresholdUs);
    }

    private static void deferExport(ExportRequest request) {
        synchronized (captureQueueLock) {
            if (pendingExport == null) {
                pendingExport = request;
            } else {
                deferredExportCoalesced.incrementAndGet();
                pendingExport = new ExportRequest(
                        preferredTrigger(request.trigger(), pendingExport.trigger()),
                        pendingExport.shouldNotify() || request.shouldNotify(),
                        pendingExport.preCaptureAlreadyDone()
                                && request.preCaptureAlreadyDone(),
                        choosePreferred(request.preferredSourceId(),
                                pendingExport.preferredSourceId()),
                        choosePreferred(request.preferredSourceType(),
                                pendingExport.preferredSourceType()),
                        request.containerSnapshot() != null
                                ? request.containerSnapshot()
                                : pendingExport.containerSnapshot());
            }
        }
    }

    private static void tryStartDeferredExport(Minecraft client) {
        ExportRequest request;
        synchronized (captureQueueLock) {
            if (hasPendingCaptureWorkLocked() || exportInProgress.get() || pendingExport == null) {
                return;
            }
            request = pendingExport;
            pendingExport = null;
        }
        startBackgroundSync(client, request);
    }

    private static void clearPendingCaptureState() {
        synchronized (captureQueueLock) {
            pendingCaptures.clear();
            pendingCaptureSet.clear();
            pendingLightUpdates.clear();
            pendingExport = null;
            captureReconciliationNeeded.set(false);
            captureInProgress.set(false);
        }
    }

    private static void flushPendingLightUpdates() {
        List<CaptureKey> ready = new java.util.ArrayList<>();
        synchronized (captureQueueLock) {
            java.util.Iterator<Map.Entry<CaptureKey, Long>> iterator =
                    pendingLightUpdates.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<CaptureKey, Long> entry = iterator.next();
                if (entry.getValue() <= clientTick) {
                    ready.add(entry.getKey());
                    iterator.remove();
                }
            }
            captureInProgress.set(hasPendingCaptureWorkLocked());
        }

        for (CaptureKey key : ready) {
            ChunkListener.markChunkDirty(key.dimension(), new ChunkPos(key.chunkX(), key.chunkZ()));
        }
    }

    private static boolean hasPendingCaptureWorkLocked() {
        return !pendingCaptures.isEmpty() || !pendingLightUpdates.isEmpty();
    }

    private static boolean hasPendingLightUpdates() {
        synchronized (captureQueueLock) {
            return !pendingLightUpdates.isEmpty();
        }
    }

    private static boolean isUnknownSourceId(String sourceId) {
        return sourceId == null || sourceId.isBlank() || "unknown".equals(sourceId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String choosePreferred(String candidate, String fallback) {
        return !isBlank(candidate) ? candidate : fallback;
    }

    /** Drops source-scoped captured data before a different logical world becomes active. */
    private static void clearCapturedWorldState() {
        ChunkListener.clear();
        EntityTracker.clear();
    }

    private static ExportTrigger preferredTrigger(
            ExportTrigger candidate, ExportTrigger fallback) {
        return candidate.priority >= fallback.priority ? candidate : fallback;
    }

    private static ExportRequest withContainerSnapshot(ExportRequest request) {
        if (request.containerSnapshot() != null) return request;
        return new ExportRequest(request.trigger(), request.shouldNotify(),
                request.preCaptureAlreadyDone(), request.preferredSourceId(),
                request.preferredSourceType(), ContainerTracker.snapshotSavedData());
    }

    /**
     * Builds a conflict resolver for the given source, checking per-world overrides first.
     * If {@code sourceId} is {@code null}, the global config is used directly.
     */
    public static ConflictResolver buildResolverForSource(String sourceId) {
        ModConfig.ConflictStrategy strategy = ModConfig.get().defaultConflictStrategy;
        if (sourceId != null) {
            String perWorld = MirrorMapping.getInstance().getPerWorldConflictStrategy(sourceId);
            if (perWorld != null) {
                try {
                    strategy = ModConfig.ConflictStrategy.valueOf(perWorld);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return switch (strategy) {
            case IGNORE -> new IgnoreResolver();
            case MANUAL -> new ManualResolver();
            default     -> new OverwriteResolver();
        };
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
        synchronized (captureQueueLock) {
            coalescedCaptureHints = 0L;
            droppedCaptureHints = 0L;
            captureHintsByReason.clear();
            captureLatenciesByReason.clear();
        }
        captureTickLatencies.reset();
        unloadCaptureLatencies.reset();
        worldFrameIntervals.reset();
        worldMirrorTickWork.reset();
        lastWorldFrameNs = 0L;
        unloadCaptureFailures.set(0L);
        captureBudgetOverruns.set(0L);
        captureBudgetMaxOverrunUs.set(0L);
        captureProcessed.set(0L);
        captureCompleted.set(0L);
        automaticExportSuppressed.set(0L);
        deferredExportCoalesced.set(0L);
        exportWorkerWallMs.set(0L);
        exportFailures.set(0L);
        lastExportMillis = 0L;
        lastExportWritten = 0;
        lastExportSettled = 0;
        lastExportUnreadable = 0;
        lastDurabilityIndexMillis = 0L;
        lastExportTimings = new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0);
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
        LatencyWindow.Summary captureLatency = captureTickLatencies.snapshotAndReset();
        LatencyWindow.Summary unloadLatency = unloadCaptureLatencies.snapshotAndReset();
        LatencyWindow.Summary frameInterval = worldFrameIntervals.snapshotAndReset();
        LatencyWindow.Summary tickWork = worldMirrorTickWork.snapshotAndReset();
        long processed = captureProcessed.getAndSet(0L);
        long completed = captureCompleted.getAndSet(0L);
        long budgetOverruns = captureBudgetOverruns.getAndSet(0L);
        long maxBudgetOverrunUs = captureBudgetMaxOverrunUs.getAndSet(0L);
        long automaticSuppressed = automaticExportSuppressed.getAndSet(0L);
        long deferredCoalesced = deferredExportCoalesced.getAndSet(0L);
        long exportDutyMs = exportWorkerWallMs.getAndSet(0L);
        ChunkExporter.ExportTimings timings = lastExportTimings;
        WMLogger.info("[perf] pipeline=" + m.mode()
                + " sessionMs=" + Math.max(0L, nowMs - diagnosticSessionStartedMs)
                + " cache=" + ChunkListener.getTotalCount()
                + " dirty=" + m.dirtyChunks()
                + " captureQueue=" + m.pendingCaptures()
                + " oldestHintMs=" + m.oldestCaptureAgeMs()
                + " coalesced=" + m.coalescedHints()
                + " dropped=" + m.droppedHints()
                + " hintReasons=" + captureHintSummary()
                + " captureReasonLatency=" + captureReasonLatencySummary()
                + " captureTickCount=" + captureLatency.observations()
                + " captureTickAvgUs=" + captureLatency.average()
                + " captureTickP95Us=" + captureLatency.p95()
                + " captureTickP99Us=" + captureLatency.p99()
                + " captureTickMaxUs=" + captureLatency.maximum()
                + " captureProcessed=" + processed
                + " captureCompleted=" + completed
                + " captureBudgetUs=" + ModConfig.get().performance.captureBudgetMicros
                + " captureBudgetOverruns=" + budgetOverruns
                + " captureBudgetMaxOverrunUs=" + maxBudgetOverrunUs
                + " unloadCaptureCount=" + unloadLatency.observations()
                + " unloadCaptureAvgUs=" + unloadLatency.average()
                + " unloadCaptureP95Us=" + unloadLatency.p95()
                + " unloadCaptureP99Us=" + unloadLatency.p99()
                + " unloadCaptureMaxUs=" + unloadLatency.maximum()
                + " unloadCaptureFailures=" + unloadCaptureFailures.get()
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
                + " automaticExportSuppressed=" + automaticSuppressed
                + " deferredExportCoalesced=" + deferredCoalesced
                + " exportWorkerDutyMs=" + exportDutyMs
                + " lastExportMs=" + m.lastExportMillis()
                + " dbLookupMs=" + timings.databaseLookupMs()
                + " materializeMs=" + timings.materializeMs()
                + " regionReadMs=" + timings.chunkReadMs()
                + " resolveMergeWriteMs=" + timings.resolveMergeWriteMs()
                + " regionFlushMs=" + timings.flushMs()
                + " regionVerifyMs=" + timings.verificationMs()
                + " entityWriteMs=" + timings.entityMs()
                + " dbCommitMs=" + lastDurabilityIndexMillis
                + " written=" + m.lastExportWritten()
                + " settled=" + m.lastExportSettled()
                + " unreadable=" + m.lastExportUnreadable()
                + " failures=" + m.exportFailures()
                + " heapMiB=" + usedMiB + "/" + committedMiB
                + " gcCountDelta=" + gcCount
                + " gcTimeMsDelta=" + gcTimeMs
                + " gcCollectors=" + collectors);
    }

    private static String captureHintSummary() {
        synchronized (captureQueueLock) {
            if (captureHintsByReason.isEmpty()) return "none";
            List<Map.Entry<String, CaptureHintCounters>> entries =
                    new java.util.ArrayList<>(captureHintsByReason.entrySet());
            entries.sort((left, right) -> Long.compare(
                    right.getValue().received, left.getValue().received));
            StringBuilder summary = new StringBuilder();
            for (Map.Entry<String, CaptureHintCounters> entry : entries) {
                if (summary.length() > 0) summary.append('|');
                CaptureHintCounters counters = entry.getValue();
                summary.append(entry.getKey())
                        .append(':').append(counters.received)
                        .append('/').append(counters.queued)
                        .append('/').append(counters.coalesced)
                        .append('/').append(counters.dropped);
            }
            return summary.toString();
        }
    }

    private static String captureReasonLatencySummary() {
        synchronized (captureQueueLock) {
            if (captureLatenciesByReason.isEmpty()) return "none";
            List<Map.Entry<String, LatencyWindow.Summary>> entries =
                    new java.util.ArrayList<>();
            for (Map.Entry<String, LatencyWindow> entry : captureLatenciesByReason.entrySet()) {
                entries.add(Map.entry(entry.getKey(), entry.getValue().snapshotAndReset()));
            }
            entries.removeIf(entry -> entry.getValue().observations() == 0L);
            if (entries.isEmpty()) return "none";
            entries.sort((left, right) -> Long.compare(
                    right.getValue().observations(), left.getValue().observations()));
            StringBuilder summary = new StringBuilder();
            for (Map.Entry<String, LatencyWindow.Summary> entry : entries) {
                if (summary.length() > 0) summary.append('|');
                LatencyWindow.Summary latency = entry.getValue();
                summary.append(entry.getKey())
                        .append(':').append(latency.observations())
                        .append('/').append(latency.average())
                        .append('/').append(latency.p95())
                        .append('/').append(latency.p99())
                        .append('/').append(latency.maximum());
            }
            return summary.toString();
        }
    }

    // ── Export nearby region ──────────────────────────────────────────────────

    /**
     * Captures all loaded chunks within {@code radiusChunks} of the player's
     * current position and exports them to a freshly created save with the given
     * {@code worldName}.  The spawn point of the new world is set to the player's
     * current block position.
     *
     * <p>This runs the capture synchronously on the calling (game) thread, then
     * hands off the MCA export and world-creation to a background thread.
     *
     * @param client       the Minecraft client instance
     * @param worldName    the name for the new singleplayer save
     * @param radiusChunks capture radius in chunks (e.g. 16 = a 33×33 chunk area)
     */
    public static void exportNearbyToNewSave(Minecraft client,
                                              String worldName, int radiusChunks,
                                              NearbyExportLineage.Choice lineageChoice) {
        ClientLevel world = client.level;
        if (world == null || client.player == null) {
            WMPlayerMessages.sendSystemMessage(client.player,
                    Component.translatable("msg.worldmirror.nearbyNoWorld").withStyle(ChatFormatting.RED));
            return;
        }

        ResourceKey<Level> dimension = world.dimension();
        int playerCX = client.player.getBlockX() >> 4;
        int playerCZ = client.player.getBlockZ() >> 4;
        int playerBX = client.player.getBlockX();
        int playerBY = client.player.getBlockY();
        int playerBZ = client.player.getBlockZ();

        // Capture nearby chunks synchronously on the game thread
        Map<ChunkPos, ChunkListener.CapturedChunk> nearbyChunks = new HashMap<>();
        for (int cx = playerCX - radiusChunks; cx <= playerCX + radiusChunks; cx++) {
            for (int cz = playerCZ - radiusChunks; cz <= playerCZ + radiusChunks; cz++) {
                ChunkAccess chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk wc)) continue;
                try {
                    if (ChunkSerializer.isChunkEmpty(wc)) continue;
                    CompoundTag nbt = ChunkSerializer.serialize(world, wc);
                    nearbyChunks.put(wc.getPos(),
                            new ChunkListener.CapturedChunk(nbt, System.currentTimeMillis(), 0L));
                } catch (Exception e) {
                    WMLogger.warnRateLimited("nearby-capture", 30_000L,
                            "Nearby export capture failed chunk=" + wc.getPos(), e);
                }
            }
        }

        if (nearbyChunks.isEmpty()) {
            WMPlayerMessages.sendSystemMessage(client.player,
                    Component.translatable("msg.worldmirror.nearbyNoChunks", radiusChunks)
                            .withStyle(ChatFormatting.RED));
            return;
        }

        ChunkListener.DirtySnapshot snapshot = ChunkListener.snapshotOf(
                Map.of(dimension, nearbyChunks));
        Map<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>> entitySnapshot = Map.of();
        Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot =
                ContainerTracker.snapshotSavedData();

        // Determine output path — always in the saves folder for easy play
        String safeName = worldName.isBlank() ? "NearbyExport" : worldName;
        Path base = FabricLoader.getInstance().getGameDir().resolve("saves");
        Path outFolder = base.resolve(sanitiseFolderName(safeName));
        // Avoid overwriting an existing save
        int suffix = 1;
        while (outFolder.toFile().exists()) {
            outFolder = base.resolve(sanitiseFolderName(safeName) + "_" + suffix++);
        }
        final Path finalOut = outFolder;
        final int finalBX = playerBX, finalBY = playerBY, finalBZ = playerBZ;
        MirrorWorldContext.Snapshot currentMirror = MirrorWorldContext.current();
        ensureCurrentMirrorIdentity(currentMirror);
        NearbyExportLineage.Result lineage = NearbyExportLineage.resolve(
                lineageChoice,
                currentMirror.isMirror() ? currentMirror.metadata() : null,
                WorldMetadata.detectSourceId(client), WorldMetadata.detectSourceType(client));
        final String sourceId = lineage.sourceId();
        final String sourceType = lineage.sourceType();
        final String parentMirrorId = lineage.parentMirrorId();

        WMLogger.info("Exporting " + nearbyChunks.size() + " nearby chunk(s) to '"
                + finalOut.getFileName() + "'...");

        Thread worker = new Thread(() -> {
            try {
                Files.createDirectories(finalOut);
                ChunkDatabase db = ChunkDatabase.open(finalOut, sourceId);
                try {
                    ChunkExporter.ExportResult result = ChunkExporter.exportChunks(
                            finalOut, snapshot, entitySnapshot, containerSnapshot,
                            new OverwriteResolver(), db);
                    for (Map.Entry<ResourceKey<Level>, Set<ChunkPos>> dimEntry
                            : result.unreadableChunks().entrySet()) {
                        if (!db.removeUnreadableUpdates(
                                dimEntry.getKey().identifier().toString(), dimEntry.getValue())) {
                            throw new SQLException(
                                    "Could not remove unreadable nearby-export durability rows");
                        }
                    }
                    for (Map.Entry<ResourceKey<Level>, Map<ChunkPos, ChunkExporter.WriteStamp>> dimEntry
                            : result.written().entrySet()) {
                        Map<ChunkPos, Long> timestamps = new HashMap<>();
                        dimEntry.getValue().forEach((pos, stamp) ->
                                timestamps.put(pos, stamp.capturedAtMs()));
                        if (!db.recordUpdates(dimEntry.getKey().identifier().toString(),
                                timestamps, "world_mirror")) {
                            throw new SQLException("Could not commit nearby-export durability index");
                        }
                    }
                    if (!result.chunkWritesSuccessful() || !result.entityWritesSuccessful()) {
                        throw new java.io.IOException(
                                "One or more nearby-export region writes failed");
                    }
                } finally {
                    db.close();
                }
                if (!WorldStructureCreator.createLoadableWorldWithSpawn(
                        finalOut, safeName, finalBX, finalBY, finalBZ)) {
                    throw new IllegalStateException("Could not create nearby-export world structure");
                }
                WorldMetadata metadata = WorldMetadata.create(sourceId, sourceType, "nearby_export");
                metadata.parentMirrorId = parentMirrorId;
                metadata.markWorldgenCurrent(
                        net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(),
                        io.github.billstark001.worldmirror.io.MirrorWorldgenAssets.ASSET_REVISION);
                metadata.markSyncComplete(finalOut);
                WMLogger.info("Nearby export complete: " + finalOut.toAbsolutePath());
                client.execute(() -> WMPlayerMessages.sendSystemMessage(client.player,
                        Component.translatable("msg.worldmirror.nearbyDone", finalOut.getFileName())
                                .withStyle(ChatFormatting.GREEN)));
            } catch (Exception e) {
                WMLogger.warn("Nearby export failed output=" + finalOut, e);
                client.execute(() -> WMPlayerMessages.sendSystemMessage(client.player,
                        Component.translatable("msg.worldmirror.nearbyFailed")
                                .withStyle(ChatFormatting.RED)));
            }
        }, "WM-NearbyExport");
        worker.setDaemon(false);
        worker.start();
    }

    private static void ensureCurrentMirrorIdentity(MirrorWorldContext.Snapshot currentMirror) {
        if (!currentMirror.isMirror() || currentMirror.metadata() == null
                || currentMirror.worldFolder() == null) return;
        WorldMetadata metadata = currentMirror.metadata();
        if (metadata.mirrorId != null && !metadata.mirrorId.isBlank()) return;
        metadata.ensureMirrorId();
        metadata.save(currentMirror.worldFolder());
    }

    private static String sanitiseFolderName(String name) {
        // Remove path separators, control characters, and any ".." sequences
        return name.replaceAll("[\\\\/:*?\"<>|]", "_")
                   .replaceAll("\\.\\.", "_")
                   .strip();
    }
}

