package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.io.ChunkSerializer;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Main-thread chunk capture queue, light coalescing, and capture diagnostics. */
final class DownloadCaptureQueue {
    private static final int MAX_CAPTURE_CHUNKS_PER_TICK = 64;
    private static final int MAX_DIAGNOSTIC_CAPTURE_REASONS = 32;
    private static final int LIGHT_UPDATE_COALESCE_TICKS = 2;

    record Key(ResourceKey<Level> dimension, int chunkX, int chunkZ) { }
    private record Pending(Key key, String reason, long enqueuedAtMs) { }

    record Metrics(int pendingCaptures, long oldestCaptureAgeMs,
                   long coalescedHints, long droppedHints) { }

    record DiagnosticSnapshot(
            LatencyWindow.Summary captureTickLatency,
            LatencyWindow.Summary unloadLatency,
            long processed,
            long completed,
            long budgetOverruns,
            long maximumBudgetOverrunUs,
            long unloadFailures,
            String hintSummary,
            String reasonLatencySummary) { }

    private static final class HintCounters {
        private long received;
        private long queued;
        private long coalesced;
        private long dropped;
    }

    private final Object lock = new Object();
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final Set<Key> pendingSet = new HashSet<>();
    private final Map<Key, Long> pendingLightUpdates = new HashMap<>();
    private final AtomicBoolean reconciliationNeeded = new AtomicBoolean();
    private final AtomicBoolean captureInProgress = new AtomicBoolean();
    private final BooleanSupplier recordPerformance;
    private final int reconciliationRange;

    private long clientTick;
    private long coalescedHints;
    private long droppedHints;
    private final Map<String, HintCounters> hintsByReason = new HashMap<>();
    private final Map<String, LatencyWindow> latenciesByReason = new HashMap<>();
    private final LatencyWindow captureTickLatencies = new LatencyWindow(4096);
    private final LatencyWindow unloadLatencies = new LatencyWindow(4096);
    private final AtomicLong unloadFailures = new AtomicLong();
    private final AtomicLong budgetOverruns = new AtomicLong();
    private final AtomicLong maximumBudgetOverrunUs = new AtomicLong();
    private final AtomicLong processedCaptures = new AtomicLong();
    private final AtomicLong completedCaptures = new AtomicLong();

    DownloadCaptureQueue(BooleanSupplier recordPerformance, int reconciliationRange) {
        this.recordPerformance = recordPerformance;
        this.reconciliationRange = reconciliationRange;
    }

    boolean isCaptureInProgress() {
        return captureInProgress.get();
    }

    Metrics metrics() {
        synchronized (lock) {
            Pending oldest = pending.peekFirst();
            long age = oldest == null ? 0L
                    : Math.max(0L, System.currentTimeMillis() - oldest.enqueuedAtMs());
            return new Metrics(pending.size(), age, coalescedHints, droppedHints);
        }
    }

    void queueChunk(ClientLevel world, ChunkPos pos, String reason) {
        if (world == null || pos == null) return;
        queue(new Key(world.dimension(), pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4), reason);
    }

    void captureBeforeUnload(ClientLevel world, LevelChunk chunk) {
        if (world == null || chunk == null) return;
        long startedNs = System.nanoTime();
        Key key = new Key(world.dimension(), chunk.getPos().getMinBlockX() >> 4,
                chunk.getPos().getMinBlockZ() >> 4);
        synchronized (lock) {
            pendingLightUpdates.remove(key);
            captureInProgress.set(hasWorkLocked());
        }
        try {
            if (!ChunkSerializer.isChunkEmpty(chunk)) {
                ChunkListener.addChunkNbt(world.dimension(), chunk.getPos(),
                        ChunkSerializer.serialize(world, chunk));
            }
        } catch (Exception e) {
            unloadFailures.incrementAndGet();
            WMLogger.warnRateLimited("capture-unload", 30_000L,
                    "Final capture before unload failed chunk=" + chunk.getPos()
                            + "; cached data may be stale", e);
        } finally {
            if (recordPerformance.getAsBoolean()) {
                long elapsedUs = (System.nanoTime() - startedNs) / 1_000L;
                unloadLatencies.record(elapsedUs);
                recordReasonLatency("unload-final", elapsedUs);
                logSlowCapture("unload", "unload-final", key, 0L, elapsedUs);
            }
        }
    }

    void queueLightUpdate(ClientLevel world, ChunkPos pos) {
        queueChunk(world, pos, "light-update-no-base");
    }

    void markLightDirty(ClientLevel world, ChunkPos pos) {
        if (world == null || pos == null) return;
        Key key = new Key(world.dimension(), pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4);
        synchronized (lock) {
            pendingLightUpdates.putIfAbsent(key, clientTick + LIGHT_UPDATE_COALESCE_TICKS);
            captureInProgress.set(hasWorkLocked());
        }
    }

    int queueLoaded(ClientLevel world, int playerCX, int playerCZ, int range, String reason) {
        if (world == null || range <= 0) return 0;
        ResourceKey<Level> dimension = world.dimension();
        int queued = 0;
        for (int cx = playerCX - range; cx <= playerCX + range; cx++) {
            for (int cz = playerCZ - range; cz <= playerCZ + range; cz++) {
                ChunkAccess chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk)) continue;
                if (queue(new Key(dimension, cx, cz), reason)) queued++;
            }
        }
        return queued;
    }

    void tick(Minecraft client) {
        clientTick++;
        flushLightUpdates();
        processPending(client);
    }

    boolean hasPendingLightUpdates() {
        synchronized (lock) {
            return !pendingLightUpdates.isEmpty();
        }
    }

    boolean hasWork() {
        synchronized (lock) {
            return hasWorkLocked();
        }
    }

    void clear() {
        synchronized (lock) {
            pending.clear();
            pendingSet.clear();
            pendingLightUpdates.clear();
            reconciliationNeeded.set(false);
            captureInProgress.set(false);
        }
    }

    void resetDiagnostics() {
        synchronized (lock) {
            coalescedHints = 0L;
            droppedHints = 0L;
            hintsByReason.clear();
            latenciesByReason.clear();
        }
        captureTickLatencies.reset();
        unloadLatencies.reset();
        unloadFailures.set(0L);
        budgetOverruns.set(0L);
        maximumBudgetOverrunUs.set(0L);
        processedCaptures.set(0L);
        completedCaptures.set(0L);
    }

    DiagnosticSnapshot snapshotDiagnostics() {
        return new DiagnosticSnapshot(
                captureTickLatencies.snapshotAndReset(),
                unloadLatencies.snapshotAndReset(),
                processedCaptures.getAndSet(0L),
                completedCaptures.getAndSet(0L),
                budgetOverruns.getAndSet(0L),
                maximumBudgetOverrunUs.getAndSet(0L),
                unloadFailures.get(),
                hintSummary(),
                reasonLatencySummary());
    }

    private boolean queue(Key key, String reason) {
        synchronized (lock) {
            HintCounters counters = null;
            if (recordPerformance.getAsBoolean()) {
                String metricReason = metricReasonLocked(reason, hintsByReason);
                counters = hintsByReason.computeIfAbsent(metricReason, ignored -> new HintCounters());
                counters.received++;
            }
            if (!pendingSet.add(key)) {
                coalescedHints++;
                if (counters != null) counters.coalesced++;
                return false;
            }
            int limit = ModConfig.get().performance.maxPendingCaptureHints;
            if (pending.size() >= limit) {
                pendingSet.remove(key);
                droppedHints++;
                if (counters != null) counters.dropped++;
                reconciliationNeeded.set(true);
                WMLogger.warnRateLimited("capture-queue-capacity", 30_000L,
                        "Capture-hint queue reached its configured limit (" + limit
                                + "); coalescing overflow and scheduling a loaded-chunk reconciliation.");
                return false;
            }
            pending.add(new Pending(key, reason, System.currentTimeMillis()));
            if (counters != null) counters.queued++;
            captureInProgress.set(true);
            return true;
        }
    }

    private void processPending(Minecraft client) {
        ClientLevel world = client.level;
        if (world == null) return;
        ResourceKey<Level> currentDimension = world.dimension();
        int processed = 0;
        int captured = 0;
        long startedNs = System.nanoTime();
        long budgetNs = (long) ModConfig.get().performance.captureBudgetMicros * 1_000L;

        while (processed < MAX_CAPTURE_CHUNKS_PER_TICK
                && (processed == 0 || System.nanoTime() - startedNs < budgetNs)) {
            Pending request;
            synchronized (lock) {
                request = pending.pollFirst();
                if (request != null) pendingSet.remove(request.key());
                captureInProgress.set(hasWorkLocked());
            }
            if (request == null) break;
            processed++;
            if (!currentDimension.equals(request.key().dimension())) continue;
            ChunkAccess chunk = world.getChunk(request.key().chunkX(), request.key().chunkZ(),
                    ChunkStatus.FULL, false);
            if (!(chunk instanceof LevelChunk levelChunk)) continue;

            long captureStartedNs = System.nanoTime();
            try {
                if (ChunkSerializer.isChunkEmpty(levelChunk)) continue;
                CompoundTag nbt = ChunkSerializer.serialize(world, levelChunk);
                ChunkListener.addChunkNbt(request.key().dimension(), levelChunk.getPos(), nbt);
                captured++;
            } catch (Exception e) {
                WMLogger.warnRateLimited("capture-incremental-" + request.reason(), 30_000L,
                        "Incremental capture failed chunk=" + levelChunk.getPos()
                                + " reason=" + request.reason(), e);
            } finally {
                if (recordPerformance.getAsBoolean()) {
                    long elapsedUs = (System.nanoTime() - captureStartedNs) / 1_000L;
                    String metricReason = recordReasonLatency(request.reason(), elapsedUs);
                    logSlowCapture("incremental", metricReason, request.key(),
                            Math.max(0L, System.currentTimeMillis() - request.enqueuedAtMs()), elapsedUs);
                }
            }
        }

        if (processed > 0 && recordPerformance.getAsBoolean()) {
            long elapsedNs = System.nanoTime() - startedNs;
            captureTickLatencies.record(elapsedNs / 1_000L);
            processedCaptures.addAndGet(processed);
            completedCaptures.addAndGet(captured);
            if (elapsedNs > budgetNs) {
                budgetOverruns.incrementAndGet();
                maximumBudgetOverrunUs.accumulateAndGet((elapsedNs - budgetNs) / 1_000L, Math::max);
            }
        }
        if (!captureInProgress.get() && reconciliationNeeded.compareAndSet(true, false)) {
            if (client.player != null) {
                queueLoaded(world, client.player.getBlockX() >> 4, client.player.getBlockZ() >> 4,
                        reconciliationRange, "initial-capture");
            }
        }
    }

    private void flushLightUpdates() {
        List<Key> ready = new ArrayList<>();
        synchronized (lock) {
            Iterator<Map.Entry<Key, Long>> iterator = pendingLightUpdates.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Key, Long> entry = iterator.next();
                if (entry.getValue() <= clientTick) {
                    ready.add(entry.getKey());
                    iterator.remove();
                }
            }
            captureInProgress.set(hasWorkLocked());
        }
        for (Key key : ready) {
            ChunkListener.markChunkDirty(key.dimension(), new ChunkPos(key.chunkX(), key.chunkZ()));
        }
    }

    private boolean hasWorkLocked() {
        return !pending.isEmpty() || !pendingLightUpdates.isEmpty();
    }

    private String recordReasonLatency(String reason, long elapsedUs) {
        synchronized (lock) {
            String metricReason = metricReasonLocked(reason, latenciesByReason);
            latenciesByReason.computeIfAbsent(metricReason, ignored -> new LatencyWindow(512))
                    .record(elapsedUs);
            return metricReason;
        }
    }

    private static String metricReasonLocked(String reason, Map<String, ?> metrics) {
        String normalized = reason == null || reason.isBlank() ? "unknown" : reason;
        if (metrics.containsKey(normalized)) return normalized;
        return metrics.size() < MAX_DIAGNOSTIC_CAPTURE_REASONS - 1 ? normalized : "other";
    }

    private void logSlowCapture(String origin, String reason, Key key,
                                long queueAgeMs, long elapsedUs) {
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

    private String hintSummary() {
        synchronized (lock) {
            if (hintsByReason.isEmpty()) return "none";
            List<Map.Entry<String, HintCounters>> entries = new ArrayList<>(hintsByReason.entrySet());
            entries.sort((left, right) -> Long.compare(
                    right.getValue().received, left.getValue().received));
            StringBuilder summary = new StringBuilder();
            for (Map.Entry<String, HintCounters> entry : entries) {
                if (summary.length() > 0) summary.append('|');
                HintCounters counters = entry.getValue();
                summary.append(entry.getKey()).append(':').append(counters.received)
                        .append('/').append(counters.queued)
                        .append('/').append(counters.coalesced)
                        .append('/').append(counters.dropped);
            }
            return summary.toString();
        }
    }

    private String reasonLatencySummary() {
        synchronized (lock) {
            if (latenciesByReason.isEmpty()) return "none";
            List<Map.Entry<String, LatencyWindow.Summary>> entries = new ArrayList<>();
            latenciesByReason.forEach((reason, latency) ->
                    entries.add(Map.entry(reason, latency.snapshotAndReset())));
            entries.removeIf(entry -> entry.getValue().observations() == 0L);
            if (entries.isEmpty()) return "none";
            entries.sort((left, right) -> Long.compare(
                    right.getValue().observations(), left.getValue().observations()));
            StringBuilder summary = new StringBuilder();
            for (Map.Entry<String, LatencyWindow.Summary> entry : entries) {
                if (summary.length() > 0) summary.append('|');
                LatencyWindow.Summary latency = entry.getValue();
                summary.append(entry.getKey()).append(':').append(latency.observations())
                        .append('/').append(latency.average())
                        .append('/').append(latency.p95())
                        .append('/').append(latency.p99())
                        .append('/').append(latency.maximum());
            }
            return summary.toString();
        }
    }
}
