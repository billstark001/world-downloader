package io.github.billstark001.worldmirror.mixin;

import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.core.ChunkListener;
import io.github.billstark001.worldmirror.core.LightingUpdate;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin({ClientPacketListener.class})
public abstract class ChunkDataMixin {

    @Shadow
    public abstract ClientLevel getLevel();

    @Inject(method = {"handleLevelChunkWithLight"}, at = {@At("TAIL")})
    private void onChunkData(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        if (!DownloadManager.isActive()) return;

        ClientLevel world = this.getLevel();
        if (world == null) {
            WMLogger.warnRateLimited("chunk-packet-no-world", 30_000L,
                    "Chunk packet ignored because the client world is unavailable");
            return;
        }

        DownloadManager.queueChunkCapture(world,
                new ChunkPos(packet.getX(), packet.getZ()), "full-chunk-packet");
    }

    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void onBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        ClientLevel world = this.getLevel();
        if (world != null) DownloadManager.queueChunkCapture(world,
                new ChunkPos(packet.getPos().getX() >> 4, packet.getPos().getZ() >> 4),
                "block-update");
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void onSectionBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        ClientLevel world = this.getLevel();
        if (world == null || !DownloadManager.isActive()) return;
        packet.runUpdates((pos, state) -> DownloadManager.queueChunkCapture(
                world, new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4),
                "section-block-update"));
    }

    @Inject(method = "handleBlockEntityData", at = @At("TAIL"))
    private void onBlockEntityData(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        ClientLevel world = this.getLevel();
        if (world != null) DownloadManager.queueChunkCapture(world,
                new ChunkPos(packet.getPos().getX() >> 4, packet.getPos().getZ() >> 4),
                "block-entity-update");
    }

    @Inject(method = "handleChunksBiomes", at = @At("TAIL"))
    private void onChunksBiomes(ClientboundChunksBiomesPacket packet, CallbackInfo ci) {
        ClientLevel world = this.getLevel();
        if (world == null || !DownloadManager.isActive()) return;
        packet.chunkBiomeData().forEach(data ->
                DownloadManager.queueChunkCapture(world, data.pos(), "biome-update"));
    }

    @Inject(method = "handleAddEntity", at = @At("TAIL"))
    private void onAddEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        DownloadManager.markEntitiesDirty();
    }

    @Inject(method = "handleRemoveEntities", at = @At("TAIL"))
    private void onRemoveEntities(ClientboundRemoveEntitiesPacket packet, CallbackInfo ci) {
        DownloadManager.markEntitiesDirty();
    }

    @Inject(method = "handleMoveEntity", at = @At("TAIL"))
    private void onMoveEntity(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
        DownloadManager.markEntitiesDirty();
    }

    @Inject(method = "handleTeleportEntity", at = @At("TAIL"))
    private void onTeleportEntity(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        DownloadManager.markEntitiesDirty();
    }

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void onSetEntityData(ClientboundSetEntityDataPacket packet, CallbackInfo ci) {
        DownloadManager.markEntitiesDirty();
    }

    /**
     * Vanilla queues both initial and light-only packet data before applying it.
     * Hooking this method, rather than the packet handler tail, therefore sees
     * the exact masks after the client light engine has accepted them.
     */
    @Inject(method = "applyLightData", at = @At("TAIL"))
    private void onLightDataApplied(
            int chunkX,
            int chunkZ,
            ClientboundLightUpdatePacketData lightData,
            boolean markDirty,
            CallbackInfo ci) {
        if (!DownloadManager.isActive()) return;

        ClientLevel world = this.getLevel();
        if (world == null) return;

        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        try {
            var lightEngine = world.getChunkSource().getLightEngine();
            boolean applied = ChunkListener.applyLightUpdate(
                    world.dimension(), pos, new LightingUpdate(
                            lightEngine.getMinLightSection(), lightEngine.getLightSectionCount(),
                            lightData.getBlockYMask(), lightData.getEmptyBlockYMask(),
                            lightData.getBlockUpdates(),
                            lightData.getSkyYMask(), lightData.getEmptySkyYMask(),
                            lightData.getSkyUpdates()));
            if (applied) {
                DownloadManager.markLightUpdateDirty(world, pos);
            } else {
                DownloadManager.queueLightUpdateCapture(world, pos);
            }
        } catch (Exception e) {
            WMLogger.warnRateLimited("light-update-capture", 30_000L,
                    "Applied light update capture failed chunk=" + pos, e);
        }
    }
}

