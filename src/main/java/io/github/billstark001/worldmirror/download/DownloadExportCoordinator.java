package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.conflict.ConflictResolver;
import io.github.billstark001.worldmirror.conflict.IgnoreResolver;
import io.github.billstark001.worldmirror.conflict.ManualResolver;
import io.github.billstark001.worldmirror.conflict.OverwriteResolver;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.ContainerTracker;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Serializes export requests and owns the complete background durability transaction. */
final class DownloadExportCoordinator {
    enum Trigger {
        MANUAL(false, 2),
        STOP(false, 3),
        PERIODIC(true, 0),
        ADAPTIVE_HIGH_WATERMARK(true, 0),
        ADAPTIVE_MAX_LATENCY(true, 0);

        private final boolean automatic;
        private final int priority;

        Trigger(boolean automatic, int priority) {
            this.automatic = automatic;
            this.priority = priority;
        }
    }

    record Request(Trigger trigger, boolean shouldNotify, boolean preCaptureAlreadyDone,
                   String preferredSourceId, String preferredSourceType,
                   Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot,
                   Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entitySnapshot,
                   ChunkListener.DirtySnapshot terrainSnapshot,
                   WorldSettingsSnapshot worldSettings) {
        Request(Trigger trigger, boolean shouldNotify, boolean preCaptureAlreadyDone,
                String preferredSourceId, String preferredSourceType,
                Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot,
                Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entitySnapshot,
                ChunkListener.DirtySnapshot terrainSnapshot) {
            this(trigger, shouldNotify, preCaptureAlreadyDone, preferredSourceId,
                    preferredSourceType, containerSnapshot, entitySnapshot, terrainSnapshot, null);
        }

        Request(Trigger trigger, boolean shouldNotify, boolean preCaptureAlreadyDone,
                String preferredSourceId, String preferredSourceType,
                Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot) {
            this(trigger, shouldNotify, preCaptureAlreadyDone, preferredSourceId,
                    preferredSourceType, containerSnapshot, null, null, null);
        }
    }

    record Metrics(long lastExportMillis, int lastWritten, int lastSettled,
                   int lastUnreadable, long failures) { }

    record DiagnosticSnapshot(ChunkExporter.ExportTimings timings,
                              long durabilityIndexMillis,
                              long workerWallMillis,
                              long automaticSuppressed,
                              long deferredCoalesced) { }

    private final DownloadCaptureQueue captureQueue;
    private final Supplier<DownloadPipeline> pipelineSupplier;
    private final AtomicBoolean inProgress = new AtomicBoolean();
    private final AtomicLong entityRevision = new AtomicLong(1L);
    private final AtomicLong durableEntityRevision = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong automaticSuppressed = new AtomicLong();
    private final AtomicLong deferredCoalesced = new AtomicLong();
    private final AtomicLong workerWallMillis = new AtomicLong();
    private final Object requestLock = new Object();
    private Request pending;

    private volatile long lastExportMillis;
    private volatile int lastWritten;
    private volatile int lastSettled;
    private volatile int lastUnreadable;
    private volatile long lastDurabilityIndexMillis;
    private volatile ChunkExporter.ExportTimings lastTimings = emptyTimings();

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1), runnable -> {
        Thread thread = new Thread(runnable, "WM-Export");
        thread.setDaemon(false);
        return thread;
    });

    DownloadExportCoordinator(DownloadCaptureQueue captureQueue,
                              Supplier<DownloadPipeline> pipelineSupplier) {
        this.captureQueue = captureQueue;
        this.pipelineSupplier = pipelineSupplier;
    }

    boolean isInProgress() {
        return inProgress.get();
    }

    void markEntitiesDirty() {
        entityRevision.incrementAndGet();
    }

    boolean hasEntityWork() {
        return entityRevision.get() > durableEntityRevision.get()
                || EntityTracker.hasDirtyUpdates();
    }

    Metrics metrics() {
        return new Metrics(lastExportMillis, lastWritten, lastSettled, lastUnreadable,
                failures.get());
    }

    DiagnosticSnapshot snapshotDiagnostics() {
        return new DiagnosticSnapshot(lastTimings, lastDurabilityIndexMillis,
                workerWallMillis.getAndSet(0L), automaticSuppressed.getAndSet(0L),
                deferredCoalesced.getAndSet(0L));
    }

    void resetDiagnostics() {
        failures.set(0L);
        automaticSuppressed.set(0L);
        deferredCoalesced.set(0L);
        workerWallMillis.set(0L);
        lastExportMillis = 0L;
        lastWritten = 0;
        lastSettled = 0;
        lastUnreadable = 0;
        lastDurabilityIndexMillis = 0L;
        lastTimings = emptyTimings();
    }

    void clearDeferred() {
        synchronized (requestLock) {
            if (pending != null && pending.entitySnapshot() == null
                    && pending.terrainSnapshot() == null) {
                pending = null;
            }
        }
    }

    boolean start(Minecraft client, Request request) {
        request = withWorldSettings(client, request);
        if (inProgress.get()) {
            if (request.trigger().automatic) {
                automaticSuppressed.incrementAndGet();
                return false;
            }
            Request deferred = request.preCaptureAlreadyDone()
                    ? withPreparedSnapshots(client, request) : request;
            defer(withContainerSnapshot(deferred));
            WMLogger.debug("Export already in progress; queued trigger="
                    + request.trigger() + " for one deferred pass.");
            return false;
        }

        if (!request.preCaptureAlreadyDone() && captureQueue.hasPendingLightUpdates()) {
            if (request.trigger().automatic) automaticSuppressed.incrementAndGet();
            else defer(request);
            return false;
        }

        if (!request.preCaptureAlreadyDone() && request.shouldNotify()
                && ModConfig.get().lifecycle.captureNearbyBeforeExport
                && client.level != null && client.player != null) {
            int playerCX = client.player.getBlockX() >> 4;
            int playerCZ = client.player.getBlockZ() >> 4;
            int queued = captureQueue.queueLoaded(client.level, playerCX, playerCZ,
                    8, "pre-export");
            if (queued > 0 || captureQueue.isCaptureInProgress()) {
                defer(new Request(request.trigger(), request.shouldNotify(), true,
                        request.preferredSourceId(), request.preferredSourceType(),
                        request.containerSnapshot(), request.entitySnapshot(),
                        request.terrainSnapshot(), request.worldSettings()));
                return false;
            }
        }

        ChunkListener.DirtySnapshot snapshot = request.terrainSnapshot() != null
                ? request.terrainSnapshot() : ChunkListener.snapshotDirtyState();
        long entitySnapshotRevision = entityRevision.get();
        boolean captureEntities = entitySnapshotRevision > durableEntityRevision.get();
        if (request.entitySnapshot() == null && captureEntities && client.level != null) {
            EntityTracker.captureEntitiesForWorld(client.level);
        }
        Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entitySnapshot =
                request.entitySnapshot() != null
                        ? request.entitySnapshot() : EntityTracker.snapshot();
        Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot =
                request.containerSnapshot() != null
                        ? request.containerSnapshot() : ContainerTracker.snapshotSavedData();

        String sourceId = request.preferredSourceId();
        String sourceType = request.preferredSourceType();
        if (isUnknownSourceId(sourceId)) sourceId = WorldMetadata.detectSourceId(client);
        if (isBlank(sourceType)) sourceType = WorldMetadata.detectSourceType(client);
        if (isUnknownSourceId(sourceId)) sourceId = "unknown";
        if (isBlank(sourceType)) sourceType = "server";
        final String finalSourceId = sourceId;
        final String finalSourceType = sourceType;

        Path worldFolder;
        try {
            worldFolder = MirrorMapping.getInstance().claimOutputDirectory(finalSourceId);
        } catch (Exception e) {
            WMLogger.warn("Export output directory preparation failed source=" + finalSourceId, e);
            return false;
        }

        int totalChunks = snapshot.chunks().values().stream().mapToInt(Map::size).sum();
        DownloadPipeline pipeline = pipelineSupplier.get();
        ModConfig.DownloadPipelineMode pipelineMode = pipeline.mode();
        WMLogger.debug("Queued export: trigger=" + request.trigger()
                + " dirtySnapshot=" + totalChunks + " dimensions="
                + snapshot.chunks().size() + " pipeline=" + pipelineMode);

        inProgress.set(true);
        pipeline.onExportStarted(System.currentTimeMillis(), totalChunks, ModConfig.get());
        final Path finalWorldFolder = worldFolder;
        final boolean diagnosticExport = ModConfig.get().performance.diagnosticPerformanceLogging;
        final Request preparedRequest = request;
        executor.execute(() -> runWorker(preparedRequest, snapshot, entitySnapshot, containerSnapshot,
                entitySnapshotRevision, totalChunks, finalSourceId, finalSourceType,
                finalWorldFolder, pipelineMode, diagnosticExport,
                preparedRequest.worldSettings()));
        return true;
    }

    void tryStartDeferred(Minecraft client) {
        Request request;
        synchronized (requestLock) {
            if (captureQueue.hasWork() || inProgress.get() || pending == null) return;
            request = pending;
            pending = null;
        }
        start(client, request);
    }

    private void runWorker(Request request, ChunkListener.DirtySnapshot snapshot,
                           Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entitySnapshot,
                           Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot,
                           long entitySnapshotRevision, int totalChunks,
                           String sourceId, String sourceType, Path worldFolder,
                           ModConfig.DownloadPipelineMode pipelineMode,
                           boolean diagnosticExport,
                           WorldSettingsSnapshot worldSettings) {
        ChunkDatabase db = null;
        long startedNs = System.nanoTime();
        long startedCpuNs = currentThreadCpuTimeNs();
        try {
            MirrorMigrationPlan.Inspection readiness = MirrorMigrationCoordinator.inspect(worldFolder);
            if (!readiness.mayCreateOrWriteWithoutMigration()) {
                WMLogger.warn("Mirror requires an explicit upgrade or is not writable ("
                        + readiness.state() + "); export aborted without modifying it.");
                notifyFailure(request.shouldNotify());
                return;
            }

            boolean createFreshWorld = readiness.state() == MirrorMigrationPlan.State.NEW;
            WorldMetadata metadata = createFreshWorld
                    ? WorldMetadata.create(sourceId, sourceType, "synchronized")
                    : readiness.metadata();
            boolean worldgenReady = WorldStructureCreator.createLoadableWorld(
                    worldFolder, sourceId, createFreshWorld, createFreshWorld, worldSettings);
            if (!worldgenReady) {
                WMLogger.warn("World generation setup failed; export aborted before writing chunks.");
                notifyFailure(request.shouldNotify());
                return;
            }
            if (createFreshWorld) {
                metadata.markWorldgenCurrent(
                        net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version(),
                        MirrorWorldgenAssets.ASSET_REVISION);
                metadata.save(worldFolder);
            }

            try {
                db = ChunkDatabase.open(worldFolder, sourceId);
            } catch (SQLException e) {
                WMLogger.warn("Chunk database open failed; export aborted world=" + worldFolder, e);
                notifyFailure(request.shouldNotify());
                return;
            }
            metadata.migrateAndCleanChunkTimes(db, worldFolder);
            ChunkExporter.ExportResult result = ChunkExporter.exportChunks(
                    worldFolder, snapshot, entitySnapshot, containerSnapshot,
                    buildResolver(sourceId), db);
            lastTimings = result.timings();

            Map<ResourceKey<Level>, Map<ChunkPos, Long>> durableWritten = new HashMap<>();
            boolean durabilityIndexSuccessful = true;
            long durabilityStartedNs = System.nanoTime();
            for (Map.Entry<ResourceKey<Level>, Set<ChunkPos>> dimension
                    : result.unreadableChunks().entrySet()) {
                if (dimension.getValue().isEmpty()) continue;
                String dimensionId = dimension.getKey().identifier().toString();
                if (db.removeUnreadableUpdates(dimensionId, dimension.getValue())) {
                    WMLogger.warn("Removed stale durability claims for unreadable region chunks dimension="
                            + dimensionId + " chunks=" + dimension.getValue().size());
                } else {
                    durabilityIndexSuccessful = false;
                }
            }
            for (Map.Entry<ResourceKey<Level>, Map<ChunkPos, ChunkExporter.WriteStamp>> dimension
                    : result.written().entrySet()) {
                String dimensionId = dimension.getKey().identifier().toString();
                Map<ChunkPos, Long> timestamps = new HashMap<>();
                Map<ChunkPos, Long> revisions = new HashMap<>();
                dimension.getValue().forEach((pos, stamp) -> {
                    timestamps.put(pos, stamp.capturedAtMs());
                    revisions.put(pos, stamp.revision());
                });
                if (db.recordUpdates(dimensionId, timestamps, "world_mirror")) {
                    durableWritten.put(dimension.getKey(), revisions);
                } else {
                    durabilityIndexSuccessful = false;
                    WMLogger.warn("Durability-index commit failed for [" + dimensionId
                            + "]; retaining " + revisions.size() + " revision(s) for retry.");
                }
            }
            lastDurabilityIndexMillis = (System.nanoTime() - durabilityStartedNs) / 1_000_000L;

            boolean invalidate = ModConfig.get().cache.invalidateAfterExport;
            ChunkListener.acknowledge(result.settledRevisions(), invalidate);
            ChunkListener.acknowledge(durableWritten, invalidate);
            EntityTracker.acknowledge(result.entityRevisions());
            if (result.entityWritesSuccessful()) {
                durableEntityRevision.accumulateAndGet(entitySnapshotRevision, Math::max);
            }

            boolean successful = result.chunkWritesSuccessful()
                    && result.entityWritesSuccessful() && durabilityIndexSuccessful;
            if (successful) metadata.markSyncComplete(worldFolder);
            else failures.incrementAndGet();

            int written = result.totalWritten();
            int unreadable = result.unreadableChunks().values().stream().mapToInt(Set::size).sum();
            lastWritten = written;
            lastUnreadable = unreadable;
            lastSettled = result.settledWithoutWrite().values().stream().mapToInt(Map::size).sum();
            long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
            if (diagnosticExport) logPerformance(request, pipelineMode, totalChunks, written,
                    unreadable, elapsedMs, startedCpuNs, result.timings());
            WMLogger.info("Export pass complete: status=" + (successful ? "success" : "partial")
                    + " pipeline=" + pipelineMode + " trigger=" + request.trigger()
                    + " dirtySnapshot=" + totalChunks + " written=" + written
                    + " unreadable=" + unreadable + " settled=" + lastSettled
                    + " dirtyRemaining=" + ChunkListener.getDirtyCount()
                    + " elapsedMs=" + elapsedMs);

            if (request.shouldNotify() && successful) {
                Minecraft.getInstance().execute(() -> WMPlayerMessages.sendSystemMessage(
                        Minecraft.getInstance().player,
                        Component.translatable("msg.worldmirror.exportDone")));
            } else if (request.shouldNotify()) {
                notifyFailure(true);
            }
        } catch (Exception e) {
            failures.incrementAndGet();
            WMLogger.warn("Export pass failed trigger=" + request.trigger()
                    + " world=" + worldFolder, e);
            notifyFailure(request.shouldNotify());
        } finally {
            lastExportMillis = (System.nanoTime() - startedNs) / 1_000_000L;
            if (diagnosticExport) workerWallMillis.addAndGet(lastExportMillis);
            if (db != null) db.close();
            inProgress.set(false);
            Minecraft.getInstance().execute(() -> tryStartDeferred(Minecraft.getInstance()));
        }
    }

    private void defer(Request request) {
        synchronized (requestLock) {
            if (pending == null) {
                pending = request;
                return;
            }
            deferredCoalesced.incrementAndGet();
            pending = mergeDeferredRequests(pending, request);
        }
    }

    static Request mergeDeferredRequests(Request pending, Request request) {
        if (hasPreparedWorldSnapshot(pending)) {
            return new Request(preferredTrigger(request.trigger(), pending.trigger()),
                    pending.shouldNotify() || request.shouldNotify(), true,
                    pending.preferredSourceId(), pending.preferredSourceType(),
                    pending.containerSnapshot(), pending.entitySnapshot(),
                    pending.terrainSnapshot(), pending.worldSettings());
        }
        if (hasPreparedWorldSnapshot(request)) {
            return new Request(preferredTrigger(request.trigger(), pending.trigger()),
                    pending.shouldNotify() || request.shouldNotify(), true,
                    request.preferredSourceId(), request.preferredSourceType(),
                    request.containerSnapshot(), request.entitySnapshot(),
                    request.terrainSnapshot(), request.worldSettings());
        }
        return new Request(preferredTrigger(request.trigger(), pending.trigger()),
                    pending.shouldNotify() || request.shouldNotify(),
                    pending.preCaptureAlreadyDone() && request.preCaptureAlreadyDone(),
                    choosePreferred(request.preferredSourceId(), pending.preferredSourceId()),
                    choosePreferred(request.preferredSourceType(), pending.preferredSourceType()),
                    request.containerSnapshot() != null
                            ? request.containerSnapshot() : pending.containerSnapshot(),
                    request.entitySnapshot() != null
                            ? request.entitySnapshot() : pending.entitySnapshot(),
                    request.terrainSnapshot() != null
                            ? request.terrainSnapshot() : pending.terrainSnapshot(),
                    request.worldSettings() != null
                            ? request.worldSettings() : pending.worldSettings());
    }

    private static boolean hasPreparedWorldSnapshot(Request request) {
        return request.entitySnapshot() != null || request.terrainSnapshot() != null;
    }

    private static Request withContainerSnapshot(Request request) {
        if (request.containerSnapshot() != null) return request;
        return new Request(request.trigger(), request.shouldNotify(), request.preCaptureAlreadyDone(),
                request.preferredSourceId(), request.preferredSourceType(),
                ContainerTracker.snapshotSavedData(), request.entitySnapshot(),
                request.terrainSnapshot(), request.worldSettings());
    }

    /** Captures stop-time state before a running export can outlive the client world. */
    private static Request withPreparedSnapshots(Minecraft client, Request request) {
        Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entities =
                request.entitySnapshot();
        if (entities == null) {
            if (client.level != null) EntityTracker.captureEntitiesForWorld(client.level);
            entities = EntityTracker.snapshot();
        }
        ChunkListener.DirtySnapshot terrain = request.terrainSnapshot() != null
                ? request.terrainSnapshot() : ChunkListener.snapshotDirtyState();
        return new Request(request.trigger(), request.shouldNotify(), true,
                request.preferredSourceId(), request.preferredSourceType(),
                request.containerSnapshot(), entities, terrain, request.worldSettings());
    }

    private static Request withWorldSettings(Minecraft client, Request request) {
        if (request.worldSettings() != null) return request;
        WorldSettingsSnapshot captured = WorldStructureCreator.resolveNewWorldSettings(
                WorldStructureCreator.captureWorldSettings(client.level));
        return new Request(request.trigger(), request.shouldNotify(),
                request.preCaptureAlreadyDone(), request.preferredSourceId(),
                request.preferredSourceType(), request.containerSnapshot(),
                request.entitySnapshot(), request.terrainSnapshot(), captured);
    }

    private static ConflictResolver buildResolver(String sourceId) {
        ModConfig.ConflictStrategy strategy = ModConfig.get().defaultConflictStrategy;
        String perWorld = MirrorMapping.getInstance().getPerWorldConflictStrategy(sourceId);
        if (perWorld != null) {
            try {
                strategy = ModConfig.ConflictStrategy.valueOf(perWorld);
            } catch (IllegalArgumentException ignored) { }
        }
        return switch (strategy) {
            case IGNORE -> new IgnoreResolver();
            case MANUAL -> new ManualResolver();
            default -> new OverwriteResolver();
        };
    }

    private void logPerformance(Request request, ModConfig.DownloadPipelineMode pipelineMode,
                                int totalChunks, int written, int unreadable, long elapsedMs,
                                long startedCpuNs, ChunkExporter.ExportTimings timings) {
        long cpuNowNs = currentThreadCpuTimeNs();
        long cpuMs = startedCpuNs < 0L || cpuNowNs < startedCpuNs
                ? -1L : (cpuNowNs - startedCpuNs) / 1_000_000L;
        WMLogger.info("[perf] export trigger=" + request.trigger()
                + " pipeline=" + pipelineMode + " dirtySnapshot=" + totalChunks
                + " written=" + written + " unreadable=" + unreadable
                + " dbLookupMs=" + timings.databaseLookupMs()
                + " materializeMs=" + timings.materializeMs()
                + " regionReadMs=" + timings.chunkReadMs()
                + " resolveMergeWriteMs=" + timings.resolveMergeWriteMs()
                + " regionFlushMs=" + timings.flushMs()
                + " regionVerifyMs=" + timings.verificationMs()
                + " entityWriteMs=" + timings.entityMs()
                + " dbCommitMs=" + lastDurabilityIndexMillis
                + " workerCpuMs=" + cpuMs + " elapsedMs=" + elapsedMs);
    }

    private static void notifyFailure(boolean notify) {
        if (!notify) return;
        Minecraft.getInstance().execute(() -> WMPlayerMessages.sendSystemMessage(
                Minecraft.getInstance().player,
                Component.translatable("msg.worldmirror.exportFailed")
                        .withStyle(ChatFormatting.RED)));
    }

    private static long currentThreadCpuTimeNs() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled()
                ? bean.getCurrentThreadCpuTime() : -1L;
    }

    private static Trigger preferredTrigger(Trigger candidate, Trigger fallback) {
        return candidate.priority >= fallback.priority ? candidate : fallback;
    }

    private static String choosePreferred(String candidate, String fallback) {
        return !isBlank(candidate) ? candidate : fallback;
    }

    private static boolean isUnknownSourceId(String sourceId) {
        return isBlank(sourceId) || "unknown".equals(sourceId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ChunkExporter.ExportTimings emptyTimings() {
        return new ChunkExporter.ExportTimings(0, 0, 0, 0, 0, 0, 0);
    }
}
