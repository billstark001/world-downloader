package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.ensgijs.nbt.io.BinaryNbtDeserializer;
import io.github.ensgijs.nbt.io.BinaryNbtSerializer;
import io.github.ensgijs.nbt.io.CompressionType;
import io.github.ensgijs.nbt.io.NamedTag;
import io.github.ensgijs.nbt.mca.EntitiesChunk;
import io.github.ensgijs.nbt.mca.McaEntitiesFile;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.CompoundTag;
import io.github.ensgijs.nbt.tag.ListTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read/merge/write support for authoritative and partial entity chunk updates. */
final class EntityRegionWriter {
    private EntityRegionWriter() { }

    private static final class MutableUpdate {
        final long revision;
        EntityTracker.Observation observation;
        final Map<UUID, EntityTracker.EntityRecord> upserts = new LinkedHashMap<>();
        final Set<UUID> tombstones = new HashSet<>();

        MutableUpdate(EntityTracker.ChunkUpdate source) {
            revision = source.revision();
            observation = source.observation();
            upserts.putAll(source.upserts());
            tombstones.addAll(source.tombstones());
        }

        MutableUpdate() {
            revision = 0L;
            observation = EntityTracker.Observation.PARTIAL;
        }
    }

    private record RegionKey(int x, int z) { }

    static Map<ResourceKey<Level>, Map<ChunkPos, Long>> write(
            Path worldFolder,
            Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> snapshot) {
        Map<ResourceKey<Level>, Map<ChunkPos, Long>> written = new HashMap<>();
        snapshot.forEach((dimension, updates) -> {
            Path entitiesDir = ChunkExporter.dimensionDirForDimension(worldFolder, dimension).resolve("entities");
            try {
                Files.createDirectories(entitiesDir);
                if (writeDimension(entitiesDir, updates)) {
                    Map<ChunkPos, Long> revisions = new HashMap<>();
                    updates.forEach((chunk, update) -> revisions.put(chunk, update.revision()));
                    written.put(dimension, Map.copyOf(revisions));
                }
            } catch (Exception e) {
                WMLogger.warn("Failed to write entity updates for [" + dimension.identifier()
                        + "]; keeping revisions dirty for retry", e);
            }
        });
        return Map.copyOf(written);
    }

    private static boolean writeDimension(
            Path entitiesDir,
            Map<ChunkPos, EntityTracker.ChunkUpdate> sourceUpdates) throws IOException {
        if (sourceUpdates.isEmpty()) return true;

        Map<ChunkPos, MutableUpdate> updates = new HashMap<>();
        sourceUpdates.forEach((chunk, update) -> updates.put(chunk, new MutableUpdate(update)));
        Set<UUID> affectedUuids = validateIncomingAndCollectUuids(sourceUpdates);

        // Rebuild the relevant part of the UUID index from disk on every pass.
        // It makes a restart self-healing and also removes duplicates produced by
        // older World Mirror versions without a second source of truth.
        addOnDiskDuplicateRemovals(entitiesDir, affectedUuids, updates);

        Map<RegionKey, Map<ChunkPos, MutableUpdate>> byRegion = new HashMap<>();
        updates.forEach((chunk, update) -> byRegion
                .computeIfAbsent(new RegionKey(chunk.getRegionX(), chunk.getRegionZ()), ignored -> new HashMap<>())
                .put(chunk, update));

        // Seed every destination before removing any old copy. This explicit
        // first phase also covers swaps and regions that are simultaneously a
        // source and destination: interruption can leave a retryable duplicate,
        // but cannot delete the only durable copy of a moving entity. A single
        // region needs no seed because its final replacement is already atomic.
        Map<RegionKey, Map<ChunkPos, MutableUpdate>> seedsByRegion = new HashMap<>();
        if (byRegion.size() > 1) {
            sourceUpdates.forEach((chunk, source) -> {
                if (source.upserts().isEmpty()) return;
                MutableUpdate seed = new MutableUpdate();
                seed.upserts.putAll(source.upserts());
                seedsByRegion
                        .computeIfAbsent(new RegionKey(chunk.getRegionX(), chunk.getRegionZ()),
                                ignored -> new HashMap<>())
                        .put(chunk, seed);
            });
            for (Map.Entry<RegionKey, Map<ChunkPos, MutableUpdate>> seed : seedsByRegion.entrySet()) {
                if (!writeRegion(entitiesDir, seed.getKey(), seed.getValue())) return false;
            }
        }

        boolean allSucceeded = true;
        for (Map.Entry<RegionKey, Map<ChunkPos, MutableUpdate>> entry : byRegion.entrySet()) {
            if (!writeRegion(entitiesDir, entry.getKey(), entry.getValue())) allSucceeded = false;
        }
        return allSucceeded;
    }

    private static Set<UUID> validateIncomingAndCollectUuids(
            Map<ChunkPos, EntityTracker.ChunkUpdate> updates) throws IOException {
        Set<UUID> all = new HashSet<>();
        Set<UUID> upserted = new HashSet<>();
        for (EntityTracker.ChunkUpdate update : updates.values()) {
            all.addAll(update.tombstones());
            for (Map.Entry<UUID, EntityTracker.EntityRecord> entry : update.upserts().entrySet()) {
                EntityTracker.EntityRecord record = entry.getValue();
                if (!entry.getKey().equals(record.rootUuid())
                        || !record.containedUuids().contains(record.rootUuid())) {
                    throw new IOException("Inconsistent root UUID in incoming entity snapshot: "
                            + record.rootUuid());
                }
                net.minecraft.nbt.CompoundTag serializedNbt = record.nbt();
                if (!serializedNbt.contains("id") || !serializedNbt.contains("UUID")) {
                    throw new IOException("Entity NBT is missing id or UUID: " + record.rootUuid());
                }
                Set<UUID> serializedUuids = collectUuids(
                        convertToQuerz(serializedNbt));
                if (!serializedUuids.equals(record.containedUuids())) {
                    throw new IOException("Entity UUID index does not match serialized NBT: "
                            + record.rootUuid());
                }
                for (UUID uuid : record.containedUuids()) {
                    if (!upserted.add(uuid)) {
                        throw new IOException("Duplicate UUID in incoming entity snapshot: " + uuid);
                    }
                    all.add(uuid);
                }
            }
        }
        return all;
    }

    private static void addOnDiskDuplicateRemovals(
            Path entitiesDir,
            Set<UUID> affectedUuids,
            Map<ChunkPos, MutableUpdate> updates) throws IOException {
        if (affectedUuids.isEmpty()) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(entitiesDir, "r.*.*.mca")) {
            for (Path file : files) {
                McaEntitiesFile mca;
                synchronized (McaWriteSupport.lockFor(file)) {
                    try {
                        mca = McaFileHelpers.readEntities(file);
                    } catch (Exception e) {
                        throw new IOException("Cannot safely index existing entity region "
                                + file.getFileName(), e);
                    }
                }

                for (EntitiesChunk chunk : mca) {
                    if (chunk == null) continue;
                    if (chunk.getRegionX() != mca.getRegionX()
                            || chunk.getRegionZ() != mca.getRegionZ()) {
                        throw new IOException("Entity chunk position does not match region "
                                + file.getFileName() + ": " + chunk.getChunkX()
                                + "," + chunk.getChunkZ());
                    }
                    ChunkPos pos = new ChunkPos(chunk.getChunkX(), chunk.getChunkZ());
                    ListTag<CompoundTag> roots = entityList(chunk);
                    if (roots == null) continue;
                    for (CompoundTag root : roots) {
                        Set<UUID> contained = collectUuids(root);
                        Set<UUID> intersection = new HashSet<>(contained);
                        intersection.retainAll(affectedUuids);
                        if (!intersection.isEmpty()) {
                            updates.computeIfAbsent(pos, ignored -> new MutableUpdate())
                                    .tombstones.addAll(intersection);
                        }
                    }
                }
            }
        }
    }

    private static boolean writeRegion(
            Path entitiesDir,
            RegionKey key,
            Map<ChunkPos, MutableUpdate> updates) {
        Path target = entitiesDir.resolve("r." + key.x() + "." + key.z() + ".mca");
        synchronized (McaWriteSupport.lockFor(target)) {
            McaEntitiesFile mca;
            if (Files.exists(target)) {
                try {
                    mca = McaFileHelpers.readEntities(target);
                } catch (Exception e) {
                    WMLogger.warn("Could not read existing entity region " + target.getFileName()
                            + "; refusing to overwrite it", e);
                    return false;
                }
            } else {
                mca = new McaEntitiesFile(key.x(), key.z());
            }

            for (Map.Entry<ChunkPos, MutableUpdate> entry : updates.entrySet()) {
                ChunkPos pos = entry.getKey();
                MutableUpdate update = entry.getValue();
                try {
                    List<CompoundTag> merged = mergeChunk(
                            mca.getChunk(pos.getRegionLocalX(), pos.getRegionLocalZ()), update);
                    mca.setChunk(pos.getRegionLocalX(), pos.getRegionLocalZ(), createChunk(pos, merged));
                } catch (Exception e) {
                    WMLogger.warnRateLimited("entity-stage", 30_000L,
                            "Entity staging failed file=" + target.getFileName()
                                    + " chunk=" + pos, e);
                    return false;
                }
            }

            try {
                int count = McaWriteSupport.writeAtomicallyLocked(mca, target);
                if (count <= 0) {
                    WMLogger.warn("Entity region " + target.getFileName()
                            + " wrote no chunks; keeping revisions dirty for retry.");
                    return false;
                }
                WMLogger.debug("Wrote entity region " + target.getFileName()
                        + " with " + updates.size() + " updated chunk(s).");
                return true;
            } catch (Exception e) {
                WMLogger.warn("Failed to write entity region " + target.getFileName()
                        + "; keeping revisions dirty for retry", e);
                return false;
            }
        }
    }

    static List<CompoundTag> mergeChunk(EntitiesChunk existing, MutableUpdate update) {
        List<CompoundTag> result = new ArrayList<>();
        if (update.observation == EntityTracker.Observation.PARTIAL && existing != null) {
            ListTag<CompoundTag> old = entityList(existing);
            if (old != null) old.forEach(tag -> result.add(tag.clone()));
        }

        Set<UUID> replaced = new HashSet<>(update.tombstones);
        update.upserts.values().forEach(record -> replaced.addAll(record.containedUuids()));
        result.removeIf(root -> {
            Set<UUID> existingUuids = collectUuids(root);
            existingUuids.retainAll(replaced);
            return !existingUuids.isEmpty();
        });

        update.upserts.values().forEach(record ->
                result.add(convertToQuerz(record.nbt()).clone()));
        return result;
    }

    private static EntitiesChunk createChunk(ChunkPos pos, List<CompoundTag> entities) {
        net.minecraft.nbt.CompoundTag chunk = new net.minecraft.nbt.CompoundTag();
        chunk.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        chunk.put("Position", new IntArrayTag(new int[] {
                pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4}));
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        entities.forEach(entity -> list.add(convertToMinecraft(entity)));
        chunk.put("Entities", list);
        return new EntitiesChunk(convertToQuerz(chunk));
    }

    private static ListTag<CompoundTag> entityList(EntitiesChunk chunk) {
        if (chunk == null || chunk.getHandle() == null) return null;
        return chunk.getHandle().getCompoundList("Entities");
    }

    static Set<UUID> collectUuids(CompoundTag entity) {
        Set<UUID> result = new HashSet<>();
        collectUuids(entity, result);
        return result;
    }

    private static void collectUuids(CompoundTag entity, Set<UUID> result) {
        int[] raw = entity.getIntArray("UUID");
        if (raw.length == 4) {
            long most = ((long) raw[0] << 32) | (raw[1] & 0xffffffffL);
            long least = ((long) raw[2] << 32) | (raw[3] & 0xffffffffL);
            result.add(new UUID(most, least));
        }
        ListTag<CompoundTag> passengers = entity.getCompoundList("Passengers");
        if (passengers != null) passengers.forEach(passenger -> collectUuids(passenger, result));
    }

    private static CompoundTag convertToQuerz(net.minecraft.nbt.CompoundTag minecraftNbt) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                net.minecraft.nbt.NbtIo.writeUnnamedTagWithFallback(minecraftNbt, output);
            }
            try (DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(bytes.toByteArray()))) {
                NamedTag named = new BinaryNbtDeserializer(CompressionType.NONE).fromStream(input);
                return (CompoundTag) named.getTag();
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to convert Minecraft entity NBT", e);
        }
    }

    private static net.minecraft.nbt.CompoundTag convertToMinecraft(CompoundTag querzNbt) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            new BinaryNbtSerializer(CompressionType.NONE)
                    .toStream(new NamedTag(null, querzNbt), bytes);
            try (DataInputStream input = new DataInputStream(
                    new ByteArrayInputStream(bytes.toByteArray()))) {
                return net.minecraft.nbt.NbtIo.read(input);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to convert stored entity NBT", e);
        }
    }
}
