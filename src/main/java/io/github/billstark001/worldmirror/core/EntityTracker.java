package io.github.billstark001.worldmirror.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.billstark001.worldmirror.io.NbtWriteView;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

@Environment(EnvType.CLIENT)
public class EntityTracker {

    // dimension → (chunkPos → entity list)
    private static final ConcurrentHashMap<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>>
            dimChunkEntities = new ConcurrentHashMap<>();

    /**
     * Captures all non-player entities in the given world and stores them under
     * that world's dimension key.  Must be called on the game thread.
     * <p>
     * Each entity is serialized via {@link Entity#saveWithoutId(ValueOutput)}, which
     * covers every entity type — including paintings (motive / facing / attachment
     * position), item frames (held item, rotation), armour stands (pose, equipment,
     * flags), dropped items, mobs, animals, and more.  The entity type identifier
     * is added as the {@code id} key after the client-visible data is captured so
     * the resulting NBT is compatible with Minecraft's region-file format.
     */
    public static void captureEntitiesForWorld(ClientLevel world) {
        if (world == null) {
            WMLogger.warn("ClientLevel is null, cannot capture entities.");
            return;
        }

        ResourceKey<Level> dimension = world.dimension();
        Map<ChunkPos, ChunkListener.CapturedChunk> capturedChunks =
                ChunkListener.getDimension(dimension);

        Map<ChunkPos, List<CompoundTag>> dimEntities = new ConcurrentHashMap<>();
        int total = 0;

        for (Entity entity : world.entitiesForRendering()) {
            if (entity == null || entity instanceof net.minecraft.world.entity.player.Player) continue;

            Vec3 pos = entity.position();
            int cx = (int) Math.floor(pos.x) >> 4;
            int cz = (int) Math.floor(pos.z) >> 4;
            ChunkPos chunkPos = new ChunkPos(cx, cz);

            if (capturedChunks.containsKey(chunkPos)) {
                try {
                    CompoundTag nbt = serializeEntity(entity);
                    if (nbt != null) {
                        dimEntities.computeIfAbsent(chunkPos, k -> new ArrayList<>()).add(nbt);
                        total++;
                    }
                } catch (Exception e) {
                    WMLogger.warnRateLimited("entity-serialize", 30_000L,
                            "Failed to serialize an entity near " + pos + ": " + e.getMessage());
                }
            }
        }

        Map<ChunkPos, List<CompoundTag>> previous = dimChunkEntities.get(dimension);
        if (previous != null) {
            for (ChunkPos previousPos : previous.keySet()) {
                boolean stillLoaded = world.getChunk(
                        previousPos.getMinBlockX() >> 4,
                        previousPos.getMinBlockZ() >> 4,
                        ChunkStatus.FULL, false) instanceof LevelChunk;
                if (stillLoaded && capturedChunks.containsKey(previousPos)) {
                    dimEntities.putIfAbsent(previousPos, new ArrayList<>());
                }
            }
        }

        dimChunkEntities.put(dimension, dimEntities);
        WMLogger.debug("Captured " + total + " entities for [" + dimension.identifier() + "]");
    }

    /**
     * Returns an immutable snapshot of all entity data, safe to read from any thread.
     */
    public static Map<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>> snapshot() {
        Map<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>> result = new HashMap<>();
        for (Map.Entry<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>> dimEntry
                : dimChunkEntities.entrySet()) {
            Map<ChunkPos, List<CompoundTag>> dimCopy = new HashMap<>();
            for (Map.Entry<ChunkPos, List<CompoundTag>> chunkEntry : dimEntry.getValue().entrySet()) {
                dimCopy.put(chunkEntry.getKey(), List.copyOf(chunkEntry.getValue()));
            }
            result.put(dimEntry.getKey(), Map.copyOf(dimCopy));
        }
        return Map.copyOf(result);
    }

    /** Drops one-shot empty markers after their exact entity snapshot is durable. */
    public static void discardDurableEmptyMarkers(
            Map<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>> durableSnapshot) {
        for (Map.Entry<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>> dimEntry
                : durableSnapshot.entrySet()) {
            Map<ChunkPos, List<CompoundTag>> live = dimChunkEntities.get(dimEntry.getKey());
            if (live == null) continue;
            for (Map.Entry<ChunkPos, List<CompoundTag>> entry : dimEntry.getValue().entrySet()) {
                if (entry.getValue().isEmpty()) {
                    List<CompoundTag> current = live.get(entry.getKey());
                    if (current != null && current.isEmpty()) {
                        live.remove(entry.getKey(), current);
                    }
                }
            }
            if (live.isEmpty()) dimChunkEntities.remove(dimEntry.getKey(), live);
        }
    }

    public static void clear() {
        int total = getTotalTrackedEntities();
        dimChunkEntities.clear();
        WMLogger.debug("Cleared " + total + " tracked entities");
    }

    public static int getTotalTrackedEntities() {
        return dimChunkEntities.values().stream()
                .mapToInt(m -> m.values().stream().mapToInt(List::size).sum())
                .sum();
    }

    public static void pruneToMatchCapturedChunks() {
        int removedChunks = 0;
        List<ResourceKey<Level>> emptyDims = new ArrayList<>();

        for (Map.Entry<ResourceKey<Level>, Map<ChunkPos, List<CompoundTag>>> dimEntry
                : dimChunkEntities.entrySet()) {
            ResourceKey<Level> dimension = dimEntry.getKey();
            Map<ChunkPos, ChunkListener.CapturedChunk> liveChunks =
                    ChunkListener.getDimension(dimension);
            Map<ChunkPos, List<CompoundTag>> entitiesByChunk = dimEntry.getValue();

            java.util.Iterator<ChunkPos> it = entitiesByChunk.keySet().iterator();
            while (it.hasNext()) {
                ChunkPos pos = it.next();
                if (!liveChunks.containsKey(pos)) {
                    it.remove();
                    removedChunks++;
                }
            }
            if (entitiesByChunk.isEmpty()) {
                emptyDims.add(dimension);
            }
        }

        for (ResourceKey<Level> dim : emptyDims) {
            dimChunkEntities.remove(dim);
        }
        if (removedChunks > 0) {
            WMLogger.debug("Pruned entity cache for " + removedChunks + " chunk(s).");
        }
    }

    // ── Entity serialisation ──────────────────────────────────────────────────

    /**
     * Serializes {@code entity} to NBT for storage in the entities region file.
     * <p>
     * Minecraft writes entity data through {@code WriteView}; the local
     * {@link NbtWriteView} adapter captures that output as a {@link CompoundTag}.
     */
    private static CompoundTag serializeEntity(Entity entity) {
        try {
            NbtWriteView view = new NbtWriteView();
            entity.saveWithoutId(view);
            CompoundTag nbt = view.getCompound();
            nbt.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            return nbt;
        } catch (Exception e) {
            WMLogger.warnRateLimited("entity-single-serialize", 30_000L,
                    "Failed to serialize entity type " + entity.getType() + ": " + e.getMessage());
            return null;
        }
    }
}

