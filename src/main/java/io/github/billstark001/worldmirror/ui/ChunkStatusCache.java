package io.github.billstark001.worldmirror.ui;

import io.github.billstark001.worldmirror.conflict.ConflictManager;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.MirrorWorldContext;
import io.github.billstark001.worldmirror.download.WorldMetadata;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class ChunkStatusCache {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "WorldMirror Chunk Status Loader");
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicLong VERSION = new AtomicLong();

    private static volatile ChunkStatusSnapshot snapshot = ChunkStatusSnapshot.EMPTY;
    private static volatile CacheKey snapshotKey;
    private static volatile long lastRefreshMs;
    private static volatile CompletableFuture<?> refreshFuture;

    private ChunkStatusCache() {}

    public static ChunkStatusSnapshot getOrScheduleRefresh(Minecraft client,
                                                           ResourceKey<Level> dimension,
                                                           long refreshIntervalMs) {
        CacheKey key = createKey(client, dimension);
        if (key == null) return ChunkStatusSnapshot.EMPTY;

        long now = System.currentTimeMillis();
        boolean contextChanged = !key.equals(snapshotKey);
        boolean stale = now - lastRefreshMs >= refreshIntervalMs;
        if ((contextChanged || stale) && (refreshFuture == null || refreshFuture.isDone())) {
            refreshFuture = CompletableFuture.runAsync(() -> {
                ChunkStatusSnapshot loaded = loadSnapshot(key);
                snapshot = loaded;
                snapshotKey = key;
                lastRefreshMs = System.currentTimeMillis();
            }, EXECUTOR);
        }
        return key.equals(snapshotKey) ? snapshot : ChunkStatusSnapshot.EMPTY;
    }

    public static void invalidate() {
        lastRefreshMs = 0L;
    }

    /**
     * Resolves the save whose persistent capture state should be drawn.  When a
     * mirror save itself is open, its own database is the authoritative source;
     * otherwise the source world's configured output mirror remains the target.
     */
    public static StatusTarget targetFor(Minecraft client) {
        MirrorWorldContext.Snapshot current = MirrorWorldContext.current();
        if (current.isMirror() && current.worldFolder() != null && current.sourceId() != null) {
            return new StatusTarget(current.worldFolder(), current.sourceId(), true);
        }
        if (client == null) return null;
        String sourceId = WorldMetadata.detectSourceId(client);
        Path worldFolder = DownloadManager.previewOutputPath(client);
        if (sourceId == null || worldFolder == null) return null;
        return new StatusTarget(worldFolder, sourceId, false);
    }

    private static CacheKey createKey(Minecraft client, ResourceKey<Level> dimension) {
        ResourceKey<Level> effectiveDimension = dimension != null ? dimension : Level.OVERWORLD;
        StatusTarget target = targetFor(client);
        if (target == null) return null;
        return new CacheKey(target.worldFolder(), target.sourceId(), effectiveDimension);
    }

    private static ChunkStatusSnapshot loadSnapshot(CacheKey key) {
        try {
            String dimensionId = key.dimension().identifier().toString();
            List<ChunkDatabase.ChunkRecord> records =
                    ChunkDatabase.queryAllReadOnly(key.worldFolder(), key.sourceId(), dimensionId);
            Set<ChunkPos> conflicts = ConflictManager.listConflicts(key.worldFolder(), key.dimension());
            return new ChunkStatusSnapshot(records, conflicts, VERSION.incrementAndGet());
        } catch (Throwable t) {
            WMLogger.warnRateLimited("chunk-status-snapshot", 30_000L,
                    "Chunk status snapshot load failed", t);
            return ChunkStatusSnapshot.EMPTY;
        }
    }

    private record CacheKey(Path worldFolder, String sourceId, ResourceKey<Level> dimension) {
        private CacheKey {
            worldFolder = worldFolder.toAbsolutePath().normalize();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof CacheKey key)) return false;
            return Objects.equals(worldFolder, key.worldFolder)
                    && Objects.equals(sourceId, key.sourceId)
                    && Objects.equals(dimension, key.dimension);
        }

        @Override
        public int hashCode() {
            return Objects.hash(worldFolder, sourceId, dimension);
        }
    }

    public record StatusTarget(Path worldFolder, String sourceId, boolean currentWorldIsMirror) {}
}
