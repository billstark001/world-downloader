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
    private static long lastPeriodicSyncMs = 0;
    private static long lastCacheEvictionMs = 0;
    private static final AtomicBoolean exportInProgress = new AtomicBoolean(false);
    /** Guards against starting a second initial-capture while one is still running. */
    private static final AtomicBoolean captureInProgress = new AtomicBoolean(false);
    private static final int INITIAL_CAPTURE_RANGE = 33;
    private static final int PRE_EXPORT_CAPTURE_RANGE = 8;
    private static final int STOP_CAPTURE_RANGE = 6;
    private static final int MAX_CAPTURE_CHUNKS_PER_TICK = 64;
    private static final int LIGHT_UPDATE_COALESCE_TICKS = 2;
    private static final long CACHE_EVICTION_INTERVAL_MS = 5_000L;
    private static final Object captureQueueLock = new Object();
    private static final ArrayDeque<PendingChunkCapture> pendingCaptures = new ArrayDeque<>();
    private static final Set<CaptureKey> pendingCaptureSet = new HashSet<>();
    private static final Map<CaptureKey, Long> pendingLightUpdates = new HashMap<>();
    private static PendingExportRequest pendingExport = null;
    private static long clientTick = 0;
    private static volatile boolean mirrorCaptureWarningShown;
    private static volatile ModConfig.DownloadPipelineMode activePipelineMode =
            ModConfig.DownloadPipelineMode.STABLE_PERIODIC;
    private static final AtomicLong coalescedCaptureHints = new AtomicLong();
    private static final AtomicLong droppedCaptureHints = new AtomicLong();
    private static final AtomicBoolean captureReconciliationNeeded = new AtomicBoolean();
    private static final AtomicLong exportFailures = new AtomicLong();
    private static final AtomicLong entityRevision = new AtomicLong(1L);
    private static final AtomicLong durableEntityRevision = new AtomicLong();
    private static volatile long lastCaptureMicros;
    private static volatile long lastExportMillis;
    private static volatile int lastExportWritten;
    private static volatile int lastExportSettled;
    private static volatile ChunkExporter.ExportTimings lastExportTimings =
            new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0);
    private static volatile long lastDurabilityIndexMillis;
    private static volatile long lastDiagnosticLogMs;
    private static volatile long lastGcCount = -1L;
    private static volatile long lastGcTimeMs = -1L;
    private static final ThreadPoolExecutor exportExecutor = new ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "WM-Export");
                thread.setDaemon(false);
                return thread;
            });

    private record CaptureKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) { }
    private record PendingChunkCapture(CaptureKey key, String reason, long enqueuedAtMs) { }
    private record PendingExportRequest(
            boolean shouldNotify,
            boolean requiresPreCapture,
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
            long lastCaptureMicros,
            long lastExportMillis,
            int lastExportWritten,
            int lastExportSettled,
            long exportFailures) { }

    public static PipelineMetrics getPipelineMetrics() {
        synchronized (captureQueueLock) {
            PendingChunkCapture oldest = pendingCaptures.peekFirst();
            long age = oldest == null ? 0L
                    : Math.max(0L, System.currentTimeMillis() - oldest.enqueuedAtMs());
            return new PipelineMetrics(activePipelineMode, pendingCaptures.size(),
                    ChunkListener.getDirtyCount(), age, coalescedCaptureHints.get(),
                    droppedCaptureHints.get(), lastCaptureMicros, lastExportMillis,
                    lastExportWritten, lastExportSettled, exportFailures.get());
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
            WMLogger.warnRateLimited("capture-unload", 30_000L,
                    "Final capture before unload failed chunk=" + chunk.getPos()
                            + "; cached data may be stale", e);
        }
    }

    private static boolean queueCaptureKey(CaptureKey key, String reason) {
        synchronized (captureQueueLock) {
            if (!pendingCaptureSet.add(key)) {
                coalescedCaptureHints.incrementAndGet();
                return false;
            }
            int limit = ModConfig.get().performance.maxPendingCaptureHints;
            if (pendingCaptures.size() >= limit) {
                pendingCaptureSet.remove(key);
                droppedCaptureHints.incrementAndGet();
                captureReconciliationNeeded.set(true);
                WMLogger.warnRateLimited("capture-queue-capacity", 30_000L,
                        "Capture-hint queue reached its configured limit (" + limit
                                + "); coalescing overflow and scheduling a loaded-chunk reconciliation.");
                return false;
            }
            pendingCaptures.add(new PendingChunkCapture(key, reason, System.currentTimeMillis()));
            captureInProgress.set(true);
            return true;
        }
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
            deferExport(true, true, null, null, ContainerTracker.snapshotSavedData());
            WMLogger.debug("Export already in progress; coalesced another export request.");
            return;
        }
        startBackgroundSync(client, true);
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
        long interval = activePipelineMode == ModConfig.DownloadPipelineMode.EXPERIMENTAL_ADAPTIVE
                ? (long) cfg.performance.adaptiveMaxLatencySeconds * 1000L
                : (long) cfg.syncIntervalSeconds * 1000L;
        int dirty = ChunkListener.getDirtyCount();
        boolean entityDirty = entityRevision.get() > durableEntityRevision.get();
        boolean highWatermark = activePipelineMode == ModConfig.DownloadPipelineMode.EXPERIMENTAL_ADAPTIVE
                && dirty >= cfg.performance.adaptiveDirtyHighWatermark;
        boolean due = now - lastPeriodicSyncMs >= interval;
        if ((due || highWatermark) && (dirty > 0 || entityDirty)) {
            lastPeriodicSyncMs = now;
            startBackgroundSync(client, false);
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
     * <p>Resolution strategy (prevents both collision clobbering and the {@code _2_2_2…}
     * suffix-accumulation bug):
     * <ol>
     *   <li>Determine the <em>base</em> folder name from {@code entries} (never contains a
     *       generated suffix).</li>
     *   <li>If {@code resolvedFolderNames} already contains an entry for this source
     *       <em>and</em> that folder still exists and is still owned by us, reuse it
     *       immediately — no rescan needed.</li>
     *   <li>Otherwise run the full collision scan starting from the base name, find a free
     *       or owned folder, and persist the winner into {@code resolvedFolderNames} only
     *       (the base name in {@code entries} is never touched).</li>
     * </ol>
     */
    public static Path getOutputPath(Minecraft client) {
        String sourceId   = WorldMetadata.detectSourceId(client);
        return getOutputPathForSource(sourceId);
    }

    private static Path getOutputPathForSource(String sourceId) {
        // Per-world override wins over global config
        String perWorldLoc = MirrorMapping.getInstance().getPerWorldSaveLocation(sourceId);
        ModConfig.SaveLocation saveLocation;
        if (perWorldLoc != null) {
            try {
                saveLocation = ModConfig.SaveLocation.valueOf(perWorldLoc);
            } catch (IllegalArgumentException e) {
                saveLocation = ModConfig.get().defaultSaveLocation;
            }
        } else {
            saveLocation = ModConfig.get().defaultSaveLocation;
        }

        return getOutputPathForLocation(sourceId, saveLocation);
    }

    /** Resolves a source's mirror location for an explicit save-location choice. */
    public static Path getOutputPathForLocation(String sourceId, ModConfig.SaveLocation saveLocation) {
        String baseName = MirrorMapping.getInstance().getMirrorFolderName(sourceId);
        Path base = (saveLocation == ModConfig.SaveLocation.SAVES)
                ? FabricLoader.getInstance().getGameDir().resolve("saves")
                : FabricLoader.getInstance().getGameDir().resolve("downloaded_worlds");

        String saveLocName = saveLocation.name();

        // Fast path: if we already recorded a validated resolved name for this
        // (source, save-location) pair, getResolvedFolderName() has already
        // checked existence and ownership — reuse it immediately.
        String cachedResolved = MirrorMapping.getInstance()
                .getResolvedFolderName(sourceId, saveLocName, base);
        if (cachedResolved != null) {
            return base.resolve(cachedResolved);
        }

        // Full collision scan starting from the base name.
        Path resolved = resolveOutputPath(base, baseName, sourceId);
        String resolvedName = resolved.getFileName().toString();

        // Persist the winner — keyed by (sourceId, saveLocName) — never touch entries.
        MirrorMapping.getInstance().setResolvedFolderName(sourceId, saveLocName, resolvedName);
        if (!resolvedName.equals(baseName)) {
            WMLogger.debug("Folder name collision resolved: '"
                    + baseName + "' → '" + resolvedName + "'");
        }
        return resolved;
    }

    /**
     * Records a per-world location change when no mirror directory exists yet.
     * Resolving and storing the exact folder name here ensures later downloads do
     * not continue using a stale location from a previous configuration.
     */
    public static void setMirrorSaveLocation(String sourceId, ModConfig.SaveLocation saveLocation) {
        Path target = getOutputPathForLocation(sourceId, saveLocation);
        MirrorMapping mapping = MirrorMapping.getInstance();
        mapping.setPerWorldSaveLocation(sourceId, saveLocation.name());
        mapping.setResolvedFolderName(sourceId, saveLocation.name(), target.getFileName().toString());
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
        Path source = getOutputPath(client);
        if (!Files.isDirectory(source)) {
            return MirrorMoveResult.failure("source_missing");
        }
        Path target = getOutputPathForLocation(sourceId, targetLocation);
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

        for (int suffix = 2; suffix < 1000; suffix++) {
            candidate = base.resolve(folderName + "_" + suffix);
            if (isFolderFreeOrOwned(candidate, sourceId)) return candidate;
        }
        // Fallback (should never happen in practice)
        return base.resolve(folderName + "_" + System.currentTimeMillis());
    }

    /**
     * Returns {@code true} if the folder is safe to use:
     * either it does not exist yet, or it already belongs to {@code sourceId}.
     */
    private static boolean isFolderFreeOrOwned(Path folder, String sourceId) {
        if (!folder.toFile().exists()) return true;
        return WorldMetadata.isOwnedBy(folder, sourceId);
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
        Path output = getOutputPathForSource(sourceId);
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
        activePipelineMode = ModConfig.get().pipelineMode;
        entityRevision.incrementAndGet();
        lastPeriodicSyncMs = System.currentTimeMillis();
        lastCacheEvictionMs = lastPeriodicSyncMs;
        if (client.level != null) captureLoadedChunksAsync(client);
        WMPlayerMessages.sendOverlayMessage(client.player, Component.translatable("msg.worldmirror.downloadStart"));
        WMLogger.info("Download activated with pipeline=" + activePipelineMode
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
                    + ") for " + reason + ".");
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
            startBackgroundSync(client, false, true,
                    lastSourceId, lastSourceType);
        }
    }

    /**
     * Prepares a snapshot on the game thread, then hands it off to a background
     * background thread for the actual I/O work.
     */
    private static void startBackgroundSync(Minecraft client, boolean notify) {
        startBackgroundSync(client, notify, false, null, null, null);
    }

    private static void startBackgroundSync(Minecraft client, boolean notify, boolean preCaptureAlreadyDone,
                                            String preferredSourceId,
                                            String preferredSourceType) {
        startBackgroundSync(client, notify, preCaptureAlreadyDone, preferredSourceId, preferredSourceType, null);
    }

    private static void startBackgroundSync(Minecraft client, boolean notify, boolean preCaptureAlreadyDone,
                                            String preferredSourceId,
                                            String preferredSourceType,
                                            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> preferredContainerSnapshot) {
        if (exportInProgress.get()) {
            deferExport(notify, notify && !preCaptureAlreadyDone,
                    preferredSourceId, preferredSourceType,
                    preferredContainerSnapshot != null
                            ? preferredContainerSnapshot
                            : ContainerTracker.snapshotSavedData());
            WMLogger.debug("Export already in progress; queued another export pass.");
            return;
        }

        // A light overlay is already up to date, but its dirty timestamp is
        // deliberately delayed so a burst exports as one coherent chunk write.
        if (!preCaptureAlreadyDone && hasPendingLightUpdates()) {
            deferExport(notify, notify, preferredSourceId, preferredSourceType,
                    preferredContainerSnapshot);
            return;
        }

        // Queue nearby capture work and defer export until that incremental
        // capture has finished. This keeps all chunk/world access on the main
        // thread without blocking it for hundreds of serialisations at once.
        if (!preCaptureAlreadyDone && notify && ModConfig.get().lifecycle.captureNearbyBeforeExport
                && client.level != null && client.player != null) {
            int playerCX = client.player.getBlockX() >> 4;
            int playerCZ = client.player.getBlockZ() >> 4;
            int queued = queueLoadedChunks(client.level, playerCX, playerCZ,
                    PRE_EXPORT_CAPTURE_RANGE, "pre-export");
            if (queued > 0 || captureInProgress.get()) {
                deferExport(notify, false, preferredSourceId, preferredSourceType,
                        preferredContainerSnapshot);
                return;
            }
        }

        // ── Game-thread preparations ──────────────────────────────────────────
        Map<ResourceKey<Level>, Map<ChunkPos, ChunkListener.CapturedChunk>> snapshot =
                ChunkListener.snapshotDirtyReferences();
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
                preferredContainerSnapshot != null
                        ? preferredContainerSnapshot
                        : ContainerTracker.snapshotSavedData();

        // Collect source info while on the game thread
        String sourceId = preferredSourceId;
        String sourceType = preferredSourceType;
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
            worldFolder = getOutputPathForSource(finalSourceId);
            Files.createDirectories(worldFolder);
        } catch (Exception e) {
            WMLogger.warn("Export output directory preparation failed source=" + finalSourceId, e);
            return;
        }

        int totalChunks = snapshot.values().stream().mapToInt(Map::size).sum();
        WMLogger.debug("Queued export: dirtySnapshot=" + totalChunks + " dimensions="
                + snapshot.size() + " pipeline=" + activePipelineMode);

        // ── Background thread ─────────────────────────────────────────────────
        exportInProgress.set(true);
        final Path finalWorldFolder = worldFolder;

        Runnable worker = () -> {
            ChunkDatabase db = null;
            long exportStartedNs = System.nanoTime();
            try {
                MirrorMigrationPlan.Inspection readiness = MirrorMigrationCoordinator.inspect(finalWorldFolder);
                if (!readiness.mayCreateOrWriteWithoutMigration()) {
                    WMLogger.warn("Mirror requires an explicit upgrade or is not writable ("
                            + readiness.state() + "); export aborted without modifying it.");
                    notifyExportFailure(notify);
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
                    notifyExportFailure(notify);
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
                    notifyExportFailure(notify);
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
                lastExportWritten = totalWritten;
                lastExportSettled = result.settledWithoutWrite().values().stream()
                        .mapToInt(Map::size).sum();
                long elapsedMs = (System.nanoTime() - exportStartedNs) / 1_000_000L;
                WMLogger.info("Export pass complete: status="
                        + (passSuccessful ? "success" : "partial")
                        + " pipeline=" + activePipelineMode
                        + " dirtySnapshot=" + totalChunks
                        + " written=" + totalWritten
                        + " settled=" + lastExportSettled
                        + " dirtyRemaining=" + ChunkListener.getDirtyCount()
                        + " elapsedMs=" + elapsedMs);

                if (notify && passSuccessful) {
                    Minecraft.getInstance().execute(() -> {
                        Minecraft mc = Minecraft.getInstance();
                        WMPlayerMessages.sendSystemMessage(
                                mc.player, Component.translatable("msg.worldmirror.exportDone"));
                    });
                } else if (notify) {
                    notifyExportFailure(true);
                }
            } catch (Exception e) {
                exportFailures.incrementAndGet();
                WMLogger.warn("Export pass failed world=" + finalWorldFolder, e);
                notifyExportFailure(notify);
            } finally {
                lastExportMillis = (System.nanoTime() - exportStartedNs) / 1_000_000L;
                if (db != null) db.close();
                exportInProgress.set(false);
                Minecraft.getInstance().execute(() ->
                        tryStartDeferredExport(Minecraft.getInstance()));
            }
        };
        exportExecutor.execute(worker);
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
        long budgetNs = (long) captureBudgetMicros() * 1_000L;

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

            try {
                if (ChunkSerializer.isChunkEmpty(wc)) continue;
                CompoundTag nbt = ChunkSerializer.serialize(world, wc);
                ChunkListener.addChunkNbt(request.key().dimension(), wc.getPos(), nbt);
                captured++;
            } catch (Exception e) {
                WMLogger.warnRateLimited("capture-incremental-" + request.reason(), 30_000L,
                        "Incremental capture failed chunk=" + wc.getPos()
                                + " reason=" + request.reason(), e);
            }
        }

        lastCaptureMicros = (System.nanoTime() - startedNs) / 1_000L;

        if (captured > 0 && !captureInProgress.get()) {
            WMLogger.debug("Incremental chunk capture finished with " + captured + " chunk(s) on the last tick.");
        }
        if (!captureInProgress.get() && captureReconciliationNeeded.compareAndSet(true, false)) {
            captureLoadedChunksAsync(client);
        }
    }

    private static int captureBudgetMicros() {
        int base = ModConfig.get().performance.captureBudgetMicros;
        if (activePipelineMode != ModConfig.DownloadPipelineMode.EXPERIMENTAL_ADAPTIVE) return base;
        synchronized (captureQueueLock) {
            int backlog = pendingCaptures.size();
            int high = Math.max(1, ModConfig.get().performance.adaptiveDirtyHighWatermark);
            return Math.min(5000, base + (base * Math.min(backlog, high) / high));
        }
    }

    private static void deferExport(
            boolean notify,
            boolean requiresPreCapture,
            String preferredSourceId,
            String preferredSourceType,
            Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot) {
        synchronized (captureQueueLock) {
            if (pendingExport == null) {
                pendingExport = new PendingExportRequest(
                        notify, requiresPreCapture, preferredSourceId,
                        preferredSourceType, containerSnapshot);
            } else {
                pendingExport = new PendingExportRequest(
                        pendingExport.shouldNotify() || notify,
                        pendingExport.requiresPreCapture() || requiresPreCapture,
                        choosePreferred(preferredSourceId, pendingExport.preferredSourceId()),
                        choosePreferred(preferredSourceType, pendingExport.preferredSourceType()),
                        containerSnapshot != null ? containerSnapshot : pendingExport.containerSnapshot());
            }
        }
    }

    private static void tryStartDeferredExport(Minecraft client) {
        PendingExportRequest request;
        synchronized (captureQueueLock) {
            if (hasPendingCaptureWorkLocked() || exportInProgress.get() || pendingExport == null) {
                return;
            }
            request = pendingExport;
            pendingExport = null;
        }
        startBackgroundSync(client, request.shouldNotify(), !request.requiresPreCapture(),
                request.preferredSourceId(), request.preferredSourceType(),
                request.containerSnapshot());
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
            collectors.append(bean.getName().replace(' ', '_'));
            if (bean.getCollectionCount() >= 0) gcCount += bean.getCollectionCount();
            if (bean.getCollectionTime() >= 0) gcTimeMs += bean.getCollectionTime();
        }
        long gcCountDelta = lastGcCount < 0 ? 0 : Math.max(0, gcCount - lastGcCount);
        long gcTimeDelta = lastGcTimeMs < 0 ? 0 : Math.max(0, gcTimeMs - lastGcTimeMs);
        lastGcCount = gcCount;
        lastGcTimeMs = gcTimeMs;
        ChunkExporter.ExportTimings timings = lastExportTimings;
        WMLogger.info("[perf] pipeline=" + m.mode()
                + " cache=" + ChunkListener.getTotalCount()
                + " dirty=" + m.dirtyChunks()
                + " captureQueue=" + m.pendingCaptures()
                + " oldestHintMs=" + m.oldestCaptureAgeMs()
                + " coalesced=" + m.coalescedHints()
                + " dropped=" + m.droppedHints()
                + " lastCaptureUs=" + m.lastCaptureMicros()
                + " lastExportMs=" + m.lastExportMillis()
                + " dbLookupMs=" + timings.databaseLookupMs()
                + " materializeMs=" + timings.materializeMs()
                + " regionReadMs=" + timings.chunkReadMs()
                + " resolveMergeWriteMs=" + timings.resolveMergeWriteMs()
                + " regionFlushMs=" + timings.flushMs()
                + " entityWriteMs=" + timings.entityMs()
                + " dbCommitMs=" + lastDurabilityIndexMillis
                + " written=" + m.lastExportWritten()
                + " settled=" + m.lastExportSettled()
                + " failures=" + m.exportFailures()
                + " heapMiB=" + usedMiB + "/" + committedMiB
                + " gcCountDelta=" + gcCountDelta
                + " gcTimeMsDelta=" + gcTimeDelta
                + " gcCollectors=" + collectors);
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

        Map<ResourceKey<Level>, Map<ChunkPos, ChunkListener.CapturedChunk>> snapshot =
                Map.of(dimension, nearbyChunks);
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

