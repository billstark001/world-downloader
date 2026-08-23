package io.github.billstark001.worldmirror.conflict;

import io.github.billstark001.worldmirror.io.ChunkExporter;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictManagerPersistenceTest {
    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void streamsConflictChunksToVanillaRegionFiles(@TempDir Path world) {
        ChunkPos pos = new ChunkPos(37, -12);
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        chunk.putInt("xPos", pos.getMinBlockX() >> 4);
        chunk.putInt("zPos", pos.getMinBlockZ() >> 4);
        chunk.putString("Status", "minecraft:full");

        assertTrue(ConflictManager.saveConflict(world, pos, chunk, Level.OVERWORLD));
        assertTrue(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
    }

    @Test
    void reportsPersistenceFailureInsteadOfSettlingTheConflict(@TempDir Path root) throws Exception {
        Path notDirectory = root.resolve("not-a-directory");
        Files.writeString(notDirectory, "occupied");

        assertFalse(ConflictManager.saveConflict(
                notDirectory, new ChunkPos(0, 0), new CompoundTag(), Level.OVERWORLD));
    }

    @Test
    void bulkOverwriteRetainsConflictWhenWorldWriteFails(@TempDir Path world) throws Exception {
        ChunkPos pos = new ChunkPos(0, 0);
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        chunk.putInt("xPos", 0);
        chunk.putInt("zPos", 0);
        chunk.putString("Status", "minecraft:full");
        assertTrue(ConflictManager.saveConflict(world, pos, chunk, Level.OVERWORLD));

        Path blockedRegionFile = ChunkExporter.regionDirForDimension(world, Level.OVERWORLD)
                .resolve("r.0.0.mca");
        Files.createDirectories(blockedRegionFile);

        ConflictManager.clearAllConflicts(world, true);

        assertTrue(ConflictManager.hasConflict(world, pos, Level.OVERWORLD));
    }
}
