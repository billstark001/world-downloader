package io.github.billstark001.worldmirror.conflict;

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
}
