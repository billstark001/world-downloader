package io.github.billstark001.worldmirror.download;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDatabaseDurabilityTest {
    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void recordsEachCapturedTimestampAndNeverRegressesIt(@TempDir Path world) throws Exception {
        ChunkPos older = new ChunkPos(1, 2);
        ChunkPos newer = new ChunkPos(3, 4);
        try (ChunkDatabase db = ChunkDatabase.open(world, "server:test")) {
            assertTrue(db.recordUpdates("minecraft:overworld",
                    Map.of(older, 100L, newer, 200L), "world_mirror"));

            assertFalse(db.shouldSkipUpdate("minecraft:overworld", 1, 2,
                    "world_mirror", 150L));
            assertTrue(db.shouldSkipUpdate("minecraft:overworld", 3, 4,
                    "world_mirror", 150L));

            assertTrue(db.recordUpdates("minecraft:overworld",
                    Map.of(newer, 50L), "world_mirror"));
            assertTrue(db.shouldSkipUpdate("minecraft:overworld", 3, 4,
                    "world_mirror", 150L));
        }
    }

    @Test
    void removesOnlyUnreadableDurabilityClaims(@TempDir Path world) throws Exception {
        ChunkPos unreadable = new ChunkPos(1, 2);
        ChunkPos healthy = new ChunkPos(3, 4);
        try (ChunkDatabase db = ChunkDatabase.open(world, "server:test")) {
            assertTrue(db.recordUpdates("minecraft:overworld",
                    Map.of(unreadable, 100L, healthy, 100L), "world_mirror"));

            assertTrue(db.removeUnreadableUpdates(
                    "minecraft:overworld", Set.of(unreadable)));

            assertFalse(db.shouldSkipUpdate("minecraft:overworld", 1, 2,
                    "world_mirror", 50L));
            assertTrue(db.shouldSkipUpdate("minecraft:overworld", 3, 4,
                    "world_mirror", 50L));
        }
    }
}
