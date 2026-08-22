package io.github.billstark001.worldmirror.io;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import io.github.billstark001.worldmirror.conflict.ConflictContext;
import io.github.billstark001.worldmirror.conflict.ConflictResolver;
import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;


@Environment(EnvType.CLIENT)
public class ChunkExporter {

    public record WriteStamp(long revision, long capturedAtMs) { }

    public record ExportTimings(long databaseLookupMs, long materializeMs,
                                long chunkReadMs, long resolveMergeWriteMs,
                                long flushMs, long verificationMs, long entityMs) { }

    public record ExportResult(
            Map<ResourceKey<Level>, Map<ChunkPos, WriteStamp>> written,
            Map<ResourceKey<Level>, Map<ChunkPos, WriteStamp>> settledWithoutWrite,
            Map<ResourceKey<Level>, Set<ChunkPos>> unreadableChunks,
            Map<ResourceKey<Level>, Map<ChunkPos, Long>> entityRevisions,
            boolean chunkWritesSuccessful,
            boolean entityWritesSuccessful,
            ExportTimings timings
    ) {
        public int totalWritten() {
            return written.values().stream().mapToInt(Map::size).sum();
        }

        public Map<ResourceKey<Level>, Map<ChunkPos, Long>> writtenRevisions() {
            return revisions(written);
        }

        public Map<ResourceKey<Level>, Map<ChunkPos, Long>> settledRevisions() {
            return revisions(settledWithoutWrite);
        }

        private static Map<ResourceKey<Level>, Map<ChunkPos, Long>> revisions(
                Map<ResourceKey<Level>, Map<ChunkPos, WriteStamp>> source) {
            Map<ResourceKey<Level>, Map<ChunkPos, Long>> result = new HashMap<>();
            source.forEach((dimension, stamps) -> {
                Map<ChunkPos, Long> byPos = new HashMap<>();
                stamps.forEach((pos, stamp) -> byPos.put(pos, stamp.revision()));
                result.put(dimension, byPos);
            });
            return result;
        }
    }

    private record DimensionResult(
            Map<ChunkPos, WriteStamp> written,
            Map<ChunkPos, WriteStamp> settledWithoutWrite,
            Set<ChunkPos> unreadableChunks,
            boolean successful,
            long databaseLookupNs,
            long materializeNs,
            long chunkReadNs,
            long resolveMergeWriteNs,
            long flushNs,
            long verificationNs
    ) { }

    private record Verification(
            boolean regionUsable,
            Set<ChunkPos> verified,
            Set<ChunkPos> unreadable,
            long elapsedNs) { }

    /**
     * Exports all chunks in the snapshot to the given world folder.
     * <p>
     * Each dimension is exported to the directory required by the target
     * Minecraft save format.
     * Only "dirty" chunks are written — i.e. chunks whose {@link ChunkListener.CapturedChunk#capturedAtMs()}
     * is newer than their last recorded write time, and whose update source is not
     * outranked by a higher-priority source in {@code db}.
     * <p>
     * Previously written block-entity data is merged back before overwrite, then
     * container overlays are applied through {@link BlockEntityNbtSupport}.
     * <p>
     * This method is safe to call from a background thread.
     *
     * @param worldFolder    Root directory of the mirror world.
     * @param snapshot       Dirty reference snapshot produced by {@link ChunkListener#snapshotDirtyReferences()}.
     * @param entitySnapshot Immutable snapshot produced by {@link EntityTracker#snapshot()}.
     * @param containerSnapshot Immutable snapshot produced by {@code ContainerTracker.snapshotSavedData()}.
     * @param resolver       Conflict resolver for chunks that already exist on disk.
     * @param db             Chunk database for dirty-check and priority enforcement.
     * @return Exact revisions durably written, plus revisions settled without a write.
     */
    public static ExportResult exportChunks(
            Path worldFolder,
            ChunkListener.DirtySnapshot snapshot,
            Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entitySnapshot,
            Map<ResourceKey<Level>, Map<BlockPos, net.minecraft.nbt.CompoundTag>> containerSnapshot,
            ConflictResolver resolver,
            ChunkDatabase db) throws Exception {

        Map<ResourceKey<Level>, Map<ChunkPos, WriteStamp>> allWritten = new HashMap<>();
        Map<ResourceKey<Level>, Map<ChunkPos, WriteStamp>> allSettled = new HashMap<>();
        Map<ResourceKey<Level>, Set<ChunkPos>> allUnreadable = new HashMap<>();
        boolean chunkWritesSuccessful = true;
        long databaseLookupNs = 0L, materializeNs = 0L, chunkReadNs = 0L;
        long resolveMergeWriteNs = 0L, flushNs = 0L, verificationNs = 0L, entityNs = 0L;

        for (ResourceKey<Level> dimension : snapshot.chunks().keySet()) {
            Map<ChunkPos, ChunkListener.CapturedChunk> dimChunks =
                    snapshot.chunks().getOrDefault(dimension, Map.of());

            Path dimensionDir = dimensionDirForDimension(worldFolder, dimension);
            Path regionDir = dimensionDir.resolve("region");
            Files.createDirectories(regionDir);

            DimensionResult dimensionResult = exportDimensionChunks(
                    regionDir, dimChunks, containerSnapshot, resolver, db,
                    dimension, worldFolder, snapshot);
            chunkWritesSuccessful &= dimensionResult.successful();
            allWritten.put(dimension, dimensionResult.written());
            allSettled.put(dimension, dimensionResult.settledWithoutWrite());
            allUnreadable.put(dimension, dimensionResult.unreadableChunks());
            databaseLookupNs += dimensionResult.databaseLookupNs();
            materializeNs += dimensionResult.materializeNs();
            chunkReadNs += dimensionResult.chunkReadNs();
            resolveMergeWriteNs += dimensionResult.resolveMergeWriteNs();
            flushNs += dimensionResult.flushNs();
            verificationNs += dimensionResult.verificationNs();

        }
        long entityStartedNs = System.nanoTime();
        Map<ResourceKey<Level>, Map<ChunkPos, Long>> entityRevisions =
                EntityRegionWriter.write(worldFolder, entitySnapshot);
        entityNs = System.nanoTime() - entityStartedNs;
        boolean entityWritesSuccessful = entitySnapshot.entrySet().stream().allMatch(entry -> {
            Map<ChunkPos, Long> written = entityRevisions.get(entry.getKey());
            return written != null && written.size() == entry.getValue().size();
        });
        return new ExportResult(allWritten, allSettled, allUnreadable, entityRevisions,
                chunkWritesSuccessful, entityWritesSuccessful,
                new ExportTimings(toMillis(databaseLookupNs), toMillis(materializeNs),
                        toMillis(chunkReadNs), toMillis(resolveMergeWriteNs),
                        toMillis(flushNs), toMillis(verificationNs), toMillis(entityNs)));
    }

    // ── Per-dimension export ──────────────────────────────────────────────────

    private static DimensionResult exportDimensionChunks(
            Path regionDir,
            Map<ChunkPos, ChunkListener.CapturedChunk> dimChunks,
            Map<ResourceKey<Level>, Map<BlockPos, net.minecraft.nbt.CompoundTag>> containerSnapshot,
            ConflictResolver resolver,
            ChunkDatabase db,
            ResourceKey<Level> dimension,
            Path worldFolder,
            ChunkListener.DirtySnapshot snapshot) {

        Map<String, List<Map.Entry<ChunkPos, ChunkListener.CapturedChunk>>> chunksByRegion =
                new HashMap<>();
        for (Map.Entry<ChunkPos, ChunkListener.CapturedChunk> entry : dimChunks.entrySet()) {
            ChunkPos pos = entry.getKey();
            chunksByRegion.computeIfAbsent(regionKey(pos.getRegionX(), pos.getRegionZ()),
                    ignored -> new ArrayList<>()).add(entry);
        }

        Map<ChunkPos, WriteStamp> written = new HashMap<>();
        Map<ChunkPos, WriteStamp> settled = new HashMap<>();
        Set<ChunkPos> unreadable = new HashSet<>();
        String dimStr = dimension.identifier().toString();
        long databaseLookupNs = 0L, materializeNs = 0L, chunkReadNs = 0L;
        long resolveMergeWriteNs = 0L, flushNs = 0L, verificationNs = 0L;
        boolean successful = true;

        for (Map.Entry<String, List<Map.Entry<ChunkPos, ChunkListener.CapturedChunk>>> regionEntry
                : chunksByRegion.entrySet()) {
            String regionKey = regionEntry.getKey();
            String[] coords = regionKey.split(",");
            int regionX = Integer.parseInt(coords[0]);
            int regionZ = Integer.parseInt(coords[1]);
            Path regionFile = regionDir.resolve(String.format("r.%d.%d.mca", regionX, regionZ));

            synchronized (McaWriteSupport.lockFor(regionFile)) {
                long regionStartedNs = System.nanoTime();
                RegionStorageInfo storageInfo =
                        new RegionStorageInfo("world_mirror", dimension, "chunk");
                Map<ChunkPos, WriteStamp> staged = new HashMap<>();
                List<Map.Entry<ChunkPos, ChunkListener.CapturedChunk>> dirty = new ArrayList<>();
                try {
                    RegionFileIntegrity.Inspection before =
                            RegionFileIntegrity.inspect(regionFile, regionX, regionZ);
                    if (!before.usable()) {
                        successful = false;
                        regionEntry.getValue().forEach(entry -> unreadable.add(entry.getKey()));
                        WMLogger.warn("Region validation failed before write file=" + regionFile
                                + " reason=" + before.failure()
                                + "; retainedChunks=" + regionEntry.getValue().size());
                        continue;
                    }
                    unreadable.addAll(before.invalidChunks());

                    for (Map.Entry<ChunkPos, ChunkListener.CapturedChunk> entry
                            : regionEntry.getValue()) {
                        ChunkPos pos = entry.getKey();
                        ChunkListener.CapturedChunk captured = entry.getValue();
                        if (before.invalidChunks().contains(pos)) {
                            dirty.add(entry);
                            continue;
                        }
                        long lookupStartedNs = System.nanoTime();
                        boolean skip = db.shouldSkipUpdate(dimStr, pos.getMinBlockX() >> 4,
                                pos.getMinBlockZ() >> 4, "world_mirror", captured.capturedAtMs());
                        databaseLookupNs += System.nanoTime() - lookupStartedNs;
                        if (skip) settled.put(pos, stamp(captured));
                        else dirty.add(entry);
                    }
                    if (dirty.isEmpty()) continue;

                    try (RegionFile vanillaRegion =
                                 new RegionFile(storageInfo, regionFile, regionDir, false)) {
                        for (Map.Entry<ChunkPos, ChunkListener.CapturedChunk> entry : dirty) {
                            ChunkPos chunkPos = entry.getKey();
                            ChunkListener.CapturedChunk captured = entry.getValue();
                            try {
                                long stageStartedNs = System.nanoTime();
                                ChunkListener.CapturedChunk materialized =
                                        snapshot.materialize(dimension, chunkPos, captured);
                                materializeNs += System.nanoTime() - stageStartedNs;
                                net.minecraft.nbt.CompoundTag chunkNbt = materialized.nbt();
                                stageStartedNs = System.nanoTime();
                                net.minecraft.nbt.CompoundTag localNbt =
                                        readChunk(vanillaRegion, chunkPos);
                                chunkReadNs += System.nanoTime() - stageStartedNs;
                                stageStartedNs = System.nanoTime();
                                boolean existsLocally = localNbt != null;
                                if (!resolver.shouldWriteChunk(new ConflictContext(
                                        chunkPos, existsLocally, chunkNbt, dimension, worldFolder))) {
                                    settled.put(chunkPos, stamp(materialized));
                                    resolveMergeWriteNs += System.nanoTime() - stageStartedNs;
                                    continue;
                                }

                                if (localNbt != null) {
                                    BlockEntityNbtSupport.mergeChunkBlockEntities(chunkNbt, localNbt);
                                }
                                BlockEntityNbtSupport.applyContainerOverlays(
                                        dimension, chunkNbt, containerSnapshot);
                                try (DataOutputStream output =
                                             vanillaRegion.getChunkDataOutputStream(chunkPos)) {
                                    NbtIo.write(chunkNbt, output);
                                }
                                staged.put(chunkPos, stamp(materialized));
                                resolveMergeWriteNs += System.nanoTime() - stageStartedNs;
                            } catch (Exception e) {
                                successful = false;
                                unreadable.add(chunkPos);
                                WMLogger.warnRateLimited("chunk-process-" + dimension.identifier(),
                                        30_000L, "Chunk processing failed dimension="
                                                + dimension.identifier() + " chunk=" + chunkPos
                                                + "; retained for retry", e);
                            }
                        }
                        if (!staged.isEmpty()) {
                            long flushStartedNs = System.nanoTime();
                            vanillaRegion.flush();
                            flushNs += System.nanoTime() - flushStartedNs;
                        }
                    }

                    if (!staged.isEmpty()) {
                        Verification verification = verifyWrittenRegion(
                                storageInfo, regionFile, regionDir, regionX, regionZ,
                                staged.keySet());
                        verificationNs += verification.elapsedNs();
                        if (!verification.regionUsable()) {
                            regionEntry.getValue().forEach(entry -> unreadable.add(entry.getKey()));
                            regionEntry.getValue().forEach(entry -> settled.remove(entry.getKey()));
                        }
                        unreadable.addAll(verification.unreadable());
                        unreadable.removeAll(verification.verified());
                        verification.verified().forEach(
                                pos -> written.put(pos, staged.get(pos)));
                        if (verification.verified().size() != staged.size()) {
                            successful = false;
                            WMLogger.warn("Region durability verification failed file=" + regionFile
                                    + " verified=" + verification.verified().size()
                                    + " failed=" + verification.unreadable().size()
                                    + "; failed chunks retained for retry");
                        }
                    }
                } catch (Exception e) {
                    successful = false;
                    regionEntry.getValue().forEach(entry -> unreadable.add(entry.getKey()));
                    regionEntry.getValue().forEach(entry -> settled.remove(entry.getKey()));
                    regionEntry.getValue().forEach(entry -> written.remove(entry.getKey()));
                    WMLogger.warn("Region write failed file=" + regionFile
                            + " retainedChunks=" + regionEntry.getValue().size(), e);
                } finally {
                    logSlowRegion(dimension, regionFile, dirty.size(), staged.size(),
                            regionStartedNs, "chunk");
                }
            }
        }

        return new DimensionResult(written, settled, Set.copyOf(unreadable), successful,
                databaseLookupNs, materializeNs,
                chunkReadNs, resolveMergeWriteNs, flushNs, verificationNs);
    }

    private static Verification verifyWrittenRegion(
            RegionStorageInfo storageInfo,
            Path regionFile,
            Path regionDirectory,
            int regionX,
            int regionZ,
            Set<ChunkPos> staged) {
        long startedNs = System.nanoTime();
        Set<ChunkPos> verified = new HashSet<>();
        Set<ChunkPos> unreadable = new HashSet<>(staged);
        boolean regionUsable = false;
        try {
            RegionFileIntegrity.Inspection inspection =
                    RegionFileIntegrity.inspect(regionFile, regionX, regionZ);
            if (!inspection.usable()) {
                return new Verification(false, Set.of(), Set.copyOf(unreadable),
                        System.nanoTime() - startedNs);
            }
            unreadable.addAll(inspection.invalidChunks());
            try (RegionFile reader = new RegionFile(
                    storageInfo, regionFile, regionDirectory, false)) {
                regionUsable = true;
                for (ChunkPos pos : staged) {
                    if (inspection.invalidChunks().contains(pos)) continue;
                    try {
                        if (readChunk(reader, pos) != null) {
                            verified.add(pos);
                            unreadable.remove(pos);
                        }
                    } catch (Exception ignored) {
                        // The summary warning contains the region and failure count.
                    }
                }
            }
        } catch (Exception ignored) {
            // Treat an inspection/open failure as a failed durability check.
        }
        return new Verification(regionUsable,
                Set.copyOf(verified), Set.copyOf(unreadable),
                System.nanoTime() - startedNs);
    }

    private static WriteStamp stamp(ChunkListener.CapturedChunk captured) {
        return new WriteStamp(captured.revision(), captured.capturedAtMs());
    }

    private static net.minecraft.nbt.CompoundTag readChunk(
            RegionFile regionFile, ChunkPos chunkPos) throws IOException {
        try (DataInputStream input = regionFile.getChunkDataInputStream(chunkPos)) {
            return input == null ? null : NbtIo.read(input);
        }
    }

    // ── Dimension → directory mapping ────────────────────────────────────────

    public static Path dimensionDirForDimension(Path worldFolder, ResourceKey<Level> dimension) {
        return WorldSaveLayout.dimensionDirectory(worldFolder, dimension);
    }

    public static Path regionDirForDimension(Path worldFolder, ResourceKey<Level> dimension) {
        return dimensionDirForDimension(worldFolder, dimension).resolve("region");
    }

    private static long toMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static void logSlowRegion(ResourceKey<Level> dimension, Path file,
                                      int candidates, int written, long startedNs,
                                      String kind) {
        if (!ModConfig.get().performance.diagnosticPerformanceLogging) return;
        long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
        if (elapsedMs < ModConfig.get().performance.slowRegionMillis) return;
        WMLogger.info("[perf] slowRegion kind=" + kind
                + " dimension=" + dimension.identifier()
                + " file=" + file.getFileName()
                + " candidates=" + candidates
                + " written=" + written
                + " elapsedMs=" + elapsedMs);
    }

    private static String regionKey(int regionX, int regionZ) {
        return regionX + "," + regionZ;
    }
}
