package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.EntityTracker;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadExportCoordinatorTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void preparedStopSnapshotRemainsAnImmutableBundleWhenAnotherSourceDefers() {
        CompoundTag oldContainer = new CompoundTag();
        oldContainer.putString("source", "old");
        CompoundTag newContainer = new CompoundTag();
        newContainer.putString("source", "new");
        ChunkListener.DirtySnapshot oldTerrain = ChunkListener.snapshotOf(Map.of());
        Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> oldEntities = Map.of();

        DownloadExportCoordinator.Request pending = new DownloadExportCoordinator.Request(
                DownloadExportCoordinator.Trigger.STOP, false, true,
                "server:old", "server", containers(oldContainer), oldEntities, oldTerrain);
        DownloadExportCoordinator.Request request = new DownloadExportCoordinator.Request(
                DownloadExportCoordinator.Trigger.MANUAL, true, false,
                "server:new", "server", containers(newContainer));

        DownloadExportCoordinator.Request merged =
                DownloadExportCoordinator.mergeDeferredRequests(pending, request);

        assertEquals(DownloadExportCoordinator.Trigger.STOP, merged.trigger());
        assertTrue(merged.shouldNotify());
        assertEquals("server:old", merged.preferredSourceId());
        assertEquals("old", merged.containerSnapshot().get(Level.OVERWORLD)
                .get(BlockPos.ZERO).getStringOr("source", ""));
        assertSame(oldEntities, merged.entitySnapshot());
        assertSame(oldTerrain, merged.terrainSnapshot());
    }

    private static Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containers(
            CompoundTag value) {
        return Map.of(Level.OVERWORLD, Map.of(BlockPos.ZERO, value));
    }
}
