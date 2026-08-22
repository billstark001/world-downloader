package io.github.billstark001.worldmirror.core;

import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Pure reconciliation state kept separate from live Minecraft entity access. */
final class EntitySnapshotStore {
    private static final AtomicLong REVISIONS = new AtomicLong();

    private static final class MutableUpdate {
        long revision;
        EntityTracker.Observation observation;
        final Map<UUID, EntityTracker.EntityRecord> upserts = new LinkedHashMap<>();
        final Set<UUID> tombstones = new HashSet<>();

        MutableUpdate(long revision, EntityTracker.Observation observation) {
            this.revision = revision;
            this.observation = observation;
        }
    }

    private final Map<ChunkPos, MutableUpdate> pending = new HashMap<>();
    private final Map<UUID, ChunkPos> knownLocations = new HashMap<>();
    private final Map<UUID, ChunkPos> persistedLocations = new HashMap<>();
    private final Map<UUID, EntityTracker.EntityRecord> persistedRecords = new HashMap<>();
    private final Set<ChunkPos> persistedCompleteChunks = new HashSet<>();

    void capture(
            Map<ChunkPos, Map<UUID, EntityTracker.EntityRecord>> observed,
            Set<ChunkPos> completeChunks) {
        Map<UUID, ChunkPos> currentLocations = new HashMap<>();
        observed.forEach((chunk, records) -> records.keySet().forEach(uuid -> currentLocations.put(uuid, chunk)));
        currentLocations.forEach((uuid, chunk) -> {
            ChunkPos previous = knownLocations.get(uuid);
            if (previous != null && !previous.equals(chunk)) remove(uuid, previous);
        });

        for (ChunkPos chunk : completeChunks) {
            Map<UUID, EntityTracker.EntityRecord> records = observed.getOrDefault(chunk, Map.of());
            MutableUpdate current = pending.get(chunk);
            if (current == null || current.observation != EntityTracker.Observation.COMPLETE
                    || !current.upserts.equals(records) || !current.tombstones.isEmpty()) {
                if (persistedCompleteChunks.contains(chunk)
                        && persistedRecordsForChunk(chunk).equals(records)) {
                    pending.remove(chunk);
                } else {
                    MutableUpdate update = new MutableUpdate(
                            nextRevision(), EntityTracker.Observation.COMPLETE);
                    records.forEach((uuid, record) -> update.upserts.put(uuid, record.copy()));
                    pending.put(chunk, update);
                }
            }
            knownLocations.entrySet().removeIf(entry ->
                    entry.getValue().equals(chunk) && !records.containsKey(entry.getKey()));
        }

        observed.forEach((chunk, records) -> {
            if (!completeChunks.contains(chunk)) {
                records.forEach((uuid, record) -> upsert(chunk, record));
            }
            records.keySet().forEach(uuid -> knownLocations.put(uuid, chunk));
        });
    }

    void remove(UUID uuid, ChunkPos chunk) {
        MutableUpdate update = pending.computeIfAbsent(chunk,
                ignored -> new MutableUpdate(0L, EntityTracker.Observation.PARTIAL));
        if (update.upserts.get(uuid) == null && update.tombstones.contains(uuid)) return;
        update.revision = nextRevision();
        update.upserts.remove(uuid);
        update.tombstones.add(uuid);
        knownLocations.remove(uuid, chunk);
    }

    Map<ChunkPos, EntityTracker.ChunkUpdate> snapshot() {
        Map<ChunkPos, EntityTracker.ChunkUpdate> result = new HashMap<>();
        pending.forEach((chunk, update) -> result.put(chunk, new EntityTracker.ChunkUpdate(
                update.revision, update.observation, update.upserts, update.tombstones)));
        return Map.copyOf(result);
    }

    void acknowledge(Map<ChunkPos, Long> writtenRevisions) {
        writtenRevisions.forEach((chunk, revision) -> {
            MutableUpdate current = pending.get(chunk);
            if (current == null || current.revision != revision) return;

            if (current.observation == EntityTracker.Observation.COMPLETE) {
                persistedLocations.entrySet().removeIf(entry -> entry.getValue().equals(chunk));
                persistedRecords.keySet().removeIf(uuid -> !persistedLocations.containsKey(uuid));
                persistedCompleteChunks.add(chunk);
            }
            current.tombstones.forEach(uuid -> {
                if (persistedLocations.remove(uuid, chunk)) persistedRecords.remove(uuid);
            });
            current.upserts.forEach((uuid, record) -> {
                persistedLocations.put(uuid, chunk);
                persistedRecords.put(uuid, record.copy());
            });
            pending.remove(chunk);
        });
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    int knownEntityCount() {
        return knownLocations.size();
    }

    private void upsert(ChunkPos chunk, EntityTracker.EntityRecord record) {
        MutableUpdate update = pending.get(chunk);
        if (update != null && record.equals(update.upserts.get(record.rootUuid()))
                && !update.tombstones.contains(record.rootUuid())) return;
        if (update == null && chunk.equals(persistedLocations.get(record.rootUuid()))
                && record.equals(persistedRecords.get(record.rootUuid()))) return;
        if (update == null) {
            update = new MutableUpdate(0L, EntityTracker.Observation.PARTIAL);
            pending.put(chunk, update);
        }
        update.revision = nextRevision();
        update.tombstones.remove(record.rootUuid());
        update.upserts.put(record.rootUuid(), record.copy());
    }

    private Map<UUID, EntityTracker.EntityRecord> persistedRecordsForChunk(ChunkPos chunk) {
        Map<UUID, EntityTracker.EntityRecord> result = new HashMap<>();
        persistedLocations.forEach((uuid, location) -> {
            if (location.equals(chunk)) result.put(uuid, persistedRecords.get(uuid));
        });
        return result;
    }

    private static long nextRevision() {
        return REVISIONS.incrementAndGet();
    }
}
