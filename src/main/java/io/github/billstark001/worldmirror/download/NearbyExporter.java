package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.conflict.OverwriteResolver;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.ContainerTracker;
import io.github.billstark001.worldmirror.core.EntityTracker;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.ChunkSerializer;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.io.WorldSettingsSnapshot;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.util.WMPlayerMessages;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Captures nearby chunks and materializes them as a separate playable save. */
public final class NearbyExporter {
    public enum Choice { INHERIT_ORIGINAL, CURRENT_MIRROR, INDEPENDENT }

    public record Lineage(String sourceId, String sourceType, String parentMirrorId) { }

    private NearbyExporter() { }

    public static Lineage resolveLineage(Choice choice, WorldMetadata currentMirror,
                                         String fallbackSourceId, String fallbackSourceType) {
        if (currentMirror == null) {
            return new Lineage(fallbackSourceId, fallbackSourceType, null);
        }
        if (currentMirror.mirrorId == null || currentMirror.mirrorId.isBlank()) {
            throw new IllegalArgumentException("current mirror must have a persistent mirrorId");
        }
        return switch (choice) {
            case INHERIT_ORIGINAL -> new Lineage(currentMirror.sourceId,
                    currentMirror.sourceType, currentMirror.mirrorId);
            case CURRENT_MIRROR -> new Lineage("mirror:" + currentMirror.mirrorId,
                    "mirror", currentMirror.mirrorId);
            case INDEPENDENT -> new Lineage("snapshot:" + UUID.randomUUID(),
                    "nearby_export", null);
        };
    }

    /** Captures on the game thread, then writes the new save in a dedicated worker. */
    public static void export(Minecraft client, String worldName, int radiusChunks,
                              Choice lineageChoice) {
        ClientLevel world = client.level;
        if (world == null || client.player == null) {
            WMPlayerMessages.sendSystemMessage(client.player,
                    Component.translatable("msg.worldmirror.nearbyNoWorld")
                            .withStyle(ChatFormatting.RED));
            return;
        }

        ResourceKey<Level> dimension = world.dimension();
        int playerCX = client.player.getBlockX() >> 4;
        int playerCZ = client.player.getBlockZ() >> 4;
        int playerBX = client.player.getBlockX();
        int playerBY = client.player.getBlockY();
        int playerBZ = client.player.getBlockZ();

        Map<ChunkPos, ChunkListener.CapturedChunk> nearbyChunks = new HashMap<>();
        for (int cx = playerCX - radiusChunks; cx <= playerCX + radiusChunks; cx++) {
            for (int cz = playerCZ - radiusChunks; cz <= playerCZ + radiusChunks; cz++) {
                ChunkAccess chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk worldChunk)) continue;
                try {
                    if (ChunkSerializer.isChunkEmpty(worldChunk)) continue;
                    CompoundTag nbt = ChunkSerializer.serialize(world, worldChunk);
                    nearbyChunks.put(worldChunk.getPos(), new ChunkListener.CapturedChunk(
                            nbt, System.currentTimeMillis(), 0L));
                } catch (Exception e) {
                    WMLogger.warnRateLimited("nearby-capture", 30_000L,
                            "Nearby export capture failed chunk=" + worldChunk.getPos(), e);
                }
            }
        }

        if (nearbyChunks.isEmpty()) {
            WMPlayerMessages.sendSystemMessage(client.player,
                    Component.translatable("msg.worldmirror.nearbyNoChunks", radiusChunks)
                            .withStyle(ChatFormatting.RED));
            return;
        }

        ChunkListener.DirtySnapshot snapshot = ChunkListener.snapshotOf(
                Map.of(dimension, nearbyChunks));
        Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entitySnapshot = Map.of();
        Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containerSnapshot =
                ContainerTracker.snapshotSavedData();

        String safeName = worldName.isBlank() ? "NearbyExport" : worldName;
        Path base = FabricLoader.getInstance().getGameDir().resolve("saves");
        Path outFolder = nextAvailableFolder(base, sanitiseFolderName(safeName));
        MirrorWorldContext.Snapshot currentMirror = MirrorWorldContext.current();
        ensureCurrentMirrorIdentity(currentMirror);
        Lineage lineage = resolveLineage(lineageChoice,
                currentMirror.isMirror() ? currentMirror.metadata() : null,
                WorldMetadata.detectSourceId(client), WorldMetadata.detectSourceType(client));
        WorldSettingsSnapshot worldSettings = WorldStructureCreator.resolveNewWorldSettings(
                WorldStructureCreator.captureWorldSettings(world));

        WMLogger.info("Exporting " + nearbyChunks.size() + " nearby chunk(s) to '"
                + outFolder.getFileName() + "'...");
        Thread worker = new Thread(() -> writeSave(client, safeName, outFolder,
                playerBX, playerBY, playerBZ, snapshot, entitySnapshot, containerSnapshot,
                lineage, worldSettings), "WM-NearbyExport");
        worker.setDaemon(false);
        worker.start();
    }

    private static void writeSave(Minecraft client, String worldName, Path output,
                                  int spawnX, int spawnY, int spawnZ,
                                  ChunkListener.DirtySnapshot snapshot,
                                  Map<ResourceKey<Level>, Map<ChunkPos, EntityTracker.ChunkUpdate>> entities,
                                  Map<ResourceKey<Level>, Map<BlockPos, CompoundTag>> containers,
                                  Lineage lineage,
                                  WorldSettingsSnapshot worldSettings) {
        try {
            Files.createDirectories(output);
            try (ChunkDatabase database = ChunkDatabase.open(output, lineage.sourceId())) {
                ChunkExporter.ExportResult result = ChunkExporter.exportChunks(
                        output, snapshot, entities, containers,
                        new OverwriteResolver(), database);
                commitDurabilityIndex(database, result);
                if (!result.chunkWritesSuccessful() || !result.entityWritesSuccessful()) {
                    throw new java.io.IOException("One or more nearby-export region writes failed");
                }
            }
            if (!WorldStructureCreator.createLoadableWorldWithSpawn(
                    output, worldName, spawnX, spawnY, spawnZ, worldSettings)) {
                throw new IllegalStateException("Could not create nearby-export world structure");
            }
            WorldMetadata metadata = WorldMetadata.create(
                    lineage.sourceId(), lineage.sourceType(), "nearby_export");
            metadata.parentMirrorId = lineage.parentMirrorId();
            metadata.markWorldgenCurrent(
                    SharedConstants.getCurrentVersion().dataVersion().version(),
                    MirrorWorldgenAssets.ASSET_REVISION);
            metadata.markSyncComplete(output);
            WMLogger.info("Nearby export complete: " + output.toAbsolutePath());
            client.execute(() -> WMPlayerMessages.sendSystemMessage(client.player,
                    Component.translatable("msg.worldmirror.nearbyDone", output.getFileName())
                            .withStyle(ChatFormatting.GREEN)));
        } catch (Exception e) {
            WMLogger.warn("Nearby export failed output=" + output, e);
            client.execute(() -> WMPlayerMessages.sendSystemMessage(client.player,
                    Component.translatable("msg.worldmirror.nearbyFailed")
                            .withStyle(ChatFormatting.RED)));
        }
    }

    private static void commitDurabilityIndex(ChunkDatabase database,
                                               ChunkExporter.ExportResult result)
            throws SQLException {
        for (Map.Entry<ResourceKey<Level>, Set<ChunkPos>> entry
                : result.unreadableChunks().entrySet()) {
            if (!database.removeUnreadableUpdates(
                    entry.getKey().identifier().toString(), entry.getValue())) {
                throw new SQLException("Could not remove unreadable nearby-export durability rows");
            }
        }
        for (Map.Entry<ResourceKey<Level>, Map<ChunkPos, ChunkExporter.WriteStamp>> entry
                : result.written().entrySet()) {
            Map<ChunkPos, Long> timestamps = new HashMap<>();
            entry.getValue().forEach((pos, stamp) -> timestamps.put(pos, stamp.capturedAtMs()));
            if (!database.recordUpdates(entry.getKey().identifier().toString(), timestamps,
                    "world_mirror")) {
                throw new SQLException("Could not commit nearby-export durability index");
            }
        }
    }

    private static Path nextAvailableFolder(Path base, String folderName) {
        Path candidate = base.resolve(folderName);
        int suffix = 1;
        while (candidate.toFile().exists()) {
            candidate = base.resolve(folderName + "_" + suffix++);
        }
        return candidate;
    }

    private static void ensureCurrentMirrorIdentity(MirrorWorldContext.Snapshot currentMirror) {
        if (!currentMirror.isMirror() || currentMirror.metadata() == null
                || currentMirror.worldFolder() == null) return;
        WorldMetadata metadata = currentMirror.metadata();
        if (metadata.mirrorId != null && !metadata.mirrorId.isBlank()) return;
        metadata.ensureMirrorId();
        metadata.save(currentMirror.worldFolder());
    }

    private static String sanitiseFolderName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\.\\.", "_")
                .strip();
    }
}
