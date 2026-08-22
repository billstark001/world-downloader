package io.github.billstark001.worldmirror.core;

import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.TagValueOutput;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tracks client-known entity snapshots independently from the terrain cache. */
@Environment(EnvType.CLIENT)
public final class EntityTracker {
    private static final int COMPLETE_OBSERVATION_RADIUS_CHUNKS = 2;
    private static final long LOAD_GRACE_MS = 750L;

    public enum Observation {
        PARTIAL,
        COMPLETE
    }

    /** A top-level entity and every UUID owned by its recursively saved passenger tree. */
    public record EntityRecord(UUID rootUuid, Set<UUID> containedUuids, CompoundTag nbt) {
        public EntityRecord {
            containedUuids = Set.copyOf(containedUuids);
            nbt = nbt.copy();
        }

        public EntityRecord copy() {
            return new EntityRecord(rootUuid, containedUuids, nbt);
        }

        @Override
        public CompoundTag nbt() {
            return nbt.copy();
        }
    }

    /** Immutable, versioned write intent for one entity chunk. */
    public record ChunkUpdate(
            long revision,
            Observation observation,
            Map<UUID, EntityRecord> upserts,
            Set<UUID> tombstones) {
        public ChunkUpdate {
            Map<UUID, EntityRecord> copied = new LinkedHashMap<>();
            upserts.forEach((uuid, record) -> copied.put(uuid, record.copy()));
            upserts = Map.copyOf(copied);
            tombstones = Set.copyOf(tombstones);
        }

    }

    private static final Map<ResourceKey<Level>, EntitySnapshotStore> dimensions = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<ChunkPos, Long>> loadedSinceByDimension = new HashMap<>();

    private EntityTracker() { }

    public static synchronized void onChunkLoaded(ClientLevel world, ChunkPos pos) {
        if (world == null || pos == null) return;
        loadedSinceByDimension.computeIfAbsent(world.dimension(), ignored -> new HashMap<>())
                .put(pos, System.currentTimeMillis());
    }

    /** Ends the observation epoch without deleting saved or pending entity data. */
    public static synchronized void onChunkUnloaded(ClientLevel world, ChunkPos pos) {
        if (world == null || pos == null) return;
        Map<ChunkPos, Long> loaded = loadedSinceByDimension.get(world.dimension());
        if (loaded != null) loaded.remove(pos);
    }

    /** Captures the complete client entity store on the game thread. */
    public static synchronized void captureEntitiesForWorld(ClientLevel world) {
        if (world == null) {
            WMLogger.debug("Entity capture skipped because the client world is unavailable");
            return;
        }

        ResourceKey<Level> dimension = world.dimension();
        EntitySnapshotStore store = dimensions.computeIfAbsent(dimension, ignored -> new EntitySnapshotStore());
        Map<ChunkPos, Map<UUID, EntityRecord>> observed = new HashMap<>();
        int total = 0;

        for (Entity entity : world.entitiesForRendering()) {
            if (entity == null || entity instanceof Player || entity.isPassenger()) continue;
            EntityRecord record = serializeEntity(world, entity);
            if (record == null) continue;
            observed.computeIfAbsent(entity.chunkPosition(), ignored -> new LinkedHashMap<>())
                    .put(record.rootUuid(), record);
            total++;
        }

        long now = System.currentTimeMillis();
        Set<ChunkPos> completeChunks = collectCompletelyObservedChunks(world, now);
        store.capture(observed, completeChunks);
        WMLogger.debug("Captured " + total + " root entities and " + completeChunks.size()
                + " complete entity chunk(s) for [" + dimension.identifier() + "]");
    }

    public static synchronized Map<ResourceKey<Level>, Map<ChunkPos, ChunkUpdate>> snapshot() {
        Map<ResourceKey<Level>, Map<ChunkPos, ChunkUpdate>> result = new HashMap<>();
        dimensions.forEach((dimension, store) -> {
            Map<ChunkPos, ChunkUpdate> updates = store.snapshot();
            if (!updates.isEmpty()) result.put(dimension, updates);
        });
        return Map.copyOf(result);
    }

    public static synchronized void acknowledge(
            Map<ResourceKey<Level>, Map<ChunkPos, Long>> writtenRevisions) {
        writtenRevisions.forEach((dimension, revisions) -> {
            EntitySnapshotStore store = dimensions.get(dimension);
            if (store != null) store.acknowledge(revisions);
        });
    }

    public static synchronized boolean hasDirtyUpdates() {
        return dimensions.values().stream().anyMatch(EntitySnapshotStore::hasPending);
    }

    /** Forces already-loaded chunks to satisfy the load grace period again. */
    public static synchronized void resetObservationEpochs() {
        loadedSinceByDimension.clear();
    }

    public static synchronized void clear() {
        int total = getTotalTrackedEntities();
        dimensions.clear();
        loadedSinceByDimension.clear();
        WMLogger.debug("Cleared " + total + " tracked entities");
    }

    public static synchronized int getTotalTrackedEntities() {
        return dimensions.values().stream().mapToInt(EntitySnapshotStore::knownEntityCount).sum();
    }

    private static EntityRecord serializeEntity(ClientLevel world, Entity entity) {
        try {
            ProblemReporter.Collector problems = new ProblemReporter.Collector();
            TagValueOutput output = TagValueOutput.createWithContext(problems, world.registryAccess());
            if (!entity.save(output)) return null;

            CompoundTag nbt = output.buildResult();
            if (!nbt.contains("id") || !nbt.contains("UUID")) {
                WMLogger.warnRateLimited("entity-identity", 30_000L,
                        "Vanilla entity serialization omitted id or UUID type=" + entity.getType());
                return null;
            }
            if (!problems.isEmpty()) {
                WMLogger.warnRateLimited("entity-codec", 30_000L,
                        "Entity serialization reported codec problems type=" + entity.getType()
                                + " report=" + problems.getReport());
            }

            Set<UUID> contained = new HashSet<>();
            entity.getSelfAndPassengers().forEach(passenger -> contained.add(passenger.getUUID()));
            return new EntityRecord(entity.getUUID(), contained, nbt);
        } catch (Exception e) {
            WMLogger.warnRateLimited("entity-serialize", 30_000L,
                    "Entity serialization failed type=" + entity.getType(), e);
            return null;
        }
    }

    private static Set<ChunkPos> collectCompletelyObservedChunks(ClientLevel world, long now) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.level() != world) return Set.of();

        int centerX = minecraft.player.getBlockX() >> 4;
        int centerZ = minecraft.player.getBlockZ() >> 4;
        Map<ChunkPos, Long> loaded = loadedSinceByDimension.computeIfAbsent(
                world.dimension(), ignored -> new HashMap<>());
        Set<ChunkPos> complete = new HashSet<>();

        for (int x = centerX - COMPLETE_OBSERVATION_RADIUS_CHUNKS;
                x <= centerX + COMPLETE_OBSERVATION_RADIUS_CHUNKS; x++) {
            for (int z = centerZ - COMPLETE_OBSERVATION_RADIUS_CHUNKS;
                    z <= centerZ + COMPLETE_OBSERVATION_RADIUS_CHUNKS; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                if (world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) {
                    loaded.remove(pos);
                    continue;
                }
                long since = loaded.computeIfAbsent(pos, ignored -> now);
                if (now - since >= LOAD_GRACE_MS) complete.add(pos);
            }
        }
        return complete;
    }

}
