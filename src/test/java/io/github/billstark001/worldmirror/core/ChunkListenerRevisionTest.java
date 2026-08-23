package io.github.billstark001.worldmirror.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ChunkListenerRevisionTest {
    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clearCache() {
        ChunkListener.clear();
        ContainerTracker.clear();
        EntityTracker.clear();
    }

    @Test
    void invalidationOnlyRemovesTheExactDurableRevision() {
        ChunkPos pos = new ChunkPos(7, -2);
        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, new CompoundTag());
        assertEquals(1, ChunkListener.getDirtyCount());
        long first = ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision();

        ChunkListener.markChunkDirty(Level.OVERWORLD, pos);
        assertEquals(1, ChunkListener.getDirtyCount());
        long second = ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision();

        ChunkListener.acknowledge(Map.of(Level.OVERWORLD, Map.of(pos, first)), true);
        assertNotNull(ChunkListener.getDimension(Level.OVERWORLD).get(pos));
        assertEquals(second, ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision());
        assertEquals(1, ChunkListener.getDirtyCount());

        ChunkListener.acknowledge(Map.of(Level.OVERWORLD, Map.of(pos, second)), true);
        assertNull(ChunkListener.getDimension(Level.OVERWORLD).get(pos));
        assertEquals(0, ChunkListener.getDirtyCount());
    }

    @Test
    void dirtySnapshotExcludesDurableCacheEntries() {
        ChunkPos pos = new ChunkPos(-4, 9);
        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, new CompoundTag());
        long revision = ChunkListener.getDimension(Level.OVERWORLD).get(pos).revision();
        assertEquals(1, ChunkListener.snapshotDirtyReferences().get(Level.OVERWORLD).size());

        ChunkListener.acknowledge(Map.of(Level.OVERWORLD, Map.of(pos, revision)), false);
        assertEquals(0, ChunkListener.getDirtyCount());
        assertEquals(0, ChunkListener.snapshotDirtyReferences().size());

        ChunkListener.markChunkDirty(Level.OVERWORLD, pos);
        assertEquals(1, ChunkListener.getDirtyCount());
        assertEquals(1, ChunkListener.snapshotDirtyReferences().get(Level.OVERWORLD).size());
    }

    @Test
    void dirtySnapshotKeepsItsLightingAfterLiveCacheIsCleared() {
        ChunkPos pos = new ChunkPos(2, 3);
        byte[] light = new byte[2048];
        java.util.Arrays.fill(light, (byte) 9);
        CompoundTag chunk = new CompoundTag();
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 4);
        section.putByteArray("BlockLight", light);
        ListTag sections = new ListTag();
        sections.add(section);
        chunk.put("sections", sections);

        ChunkListener.addChunkNbt(Level.OVERWORLD, pos, chunk);
        ChunkListener.DirtySnapshot snapshot = ChunkListener.snapshotDirtyState();
        ChunkListener.CapturedChunk captured = snapshot.chunks().get(Level.OVERWORLD).get(pos);

        ChunkListener.clear();

        CompoundTag materialized = snapshot.materialize(Level.OVERWORLD, pos, captured).nbt();
        CompoundTag materializedSection = materialized.getListOrEmpty("sections")
                .getCompound(0).orElseThrow();
        assertArrayEquals(light, materializedSection.getByteArray("BlockLight").orElseThrow());
    }
}
