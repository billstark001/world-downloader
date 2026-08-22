package io.github.billstark001.worldmirror;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.download.ChunkDatabase;
import io.github.billstark001.worldmirror.download.DownloadManager;
import io.github.billstark001.worldmirror.download.MirrorWorldContext;
import io.github.billstark001.worldmirror.ui.ChunkMapScreen;
import io.github.billstark001.worldmirror.ui.StatusScreen;
import io.github.billstark001.worldmirror.util.WMLogger;
import io.github.billstark001.worldmirror.xaero.XaeroBridgeOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

@Environment(EnvType.CLIENT)
public class WorldMirrorClient implements ClientModInitializer {

    /**
     * Translation key for the keybinding category.
     * Using a plain string avoids the double-namespace expansion that produced
     * {@code key.category.minecraft.category.worldmirror}.
     */
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("worldmirror", "default"));

    private static KeyMapping toggleKey;
    private static KeyMapping exportKey;
    private static KeyMapping clearKey;
    private static KeyMapping statusKey;
    private static KeyMapping chunkMapKey;

    @Override
    public void onInitializeClient() {
        ModConfig.register();
        ChunkDatabase.configureSqliteNativeDirectory();
        installXaeroBridgeOverlay();

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.toggle",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P,
                CATEGORY));

        exportKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.export",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O,
                CATEGORY));

        clearKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.clear",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L,
                CATEGORY));

        statusKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.status",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I,
                CATEGORY));

        chunkMapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.worldmirror.chunkMap",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M,
                CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                DownloadManager.toggle(client);
            }
            while (exportKey.consumeClick()) {
                DownloadManager.exportNow(client);
            }
            while (clearKey.consumeClick()) {
                DownloadManager.clearAll(client);
            }
            while (statusKey.consumeClick()) {
                StatusScreen.open();
            }
            while (chunkMapKey.consumeClick()) {
                ChunkMapScreen.open();
            }
            DownloadManager.onClientTick(client);
        });
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) ->
                DownloadManager.queueChunkCapture(world, chunk.getPos(), "chunk-load"));
        ClientChunkEvents.CHUNK_UNLOAD.register(DownloadManager::captureChunkBeforeUnload);

        // Apply the configured on-join behaviour whenever the player enters a world.
        // ClientPlayConnectionEvents.JOIN fires after the world object is available,
        // which is exactly when we need it.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin(client));

        // Reset lifecycle tracking state when the player leaves a server / world.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            MirrorWorldContext.leave();
            DownloadManager.onLeaveWorld(client);
        });
    }

    private static void onJoin(net.minecraft.client.Minecraft client) {
        MirrorWorldContext.enter(currentLocalSave(client),
                SharedConstants.getCurrentVersion().dataVersion().version());
        DownloadManager.onJoinWorld(client);
    }

    private static Path currentLocalSave(net.minecraft.client.Minecraft client) {
        try {
            return client.getSingleplayerServer() != null
                    ? client.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                    : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void installXaeroBridgeOverlay() {
        if (!FabricLoader.getInstance().isModLoaded("xaero_world_map_bridge")) return;
        try {
            XaeroBridgeOverlay.install();
        } catch (LinkageError error) {
            WMLogger.warn("Xaero World Map Bridge is present but could not be linked; skipping overlay", error);
        }
    }
}

