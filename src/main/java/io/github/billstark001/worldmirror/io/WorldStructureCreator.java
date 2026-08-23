package io.github.billstark001.worldmirror.io;

import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/** Version-neutral orchestration for creating and updating playable mirror saves. */
@Environment(EnvType.CLIENT)
public final class WorldStructureCreator {
    private WorldStructureCreator() { }

    public static CompoundTag createMirrorWorldGenSettings() {
        CompoundTag worldGenSettings = new CompoundTag();
        CompoundTag dimensions = new CompoundTag();
        for (MirrorWorldgenDefinition.Dimension definition : MirrorWorldgenDefinition.DIMENSIONS) {
            CompoundTag dimension = new CompoundTag();
            dimension.put("generator", createVoidNoiseGenerator(definition.biome(),
                    definition.minY(), definition.height(), definition.seaLevel(),
                    definition.horizontalSize(), definition.verticalSize()));
            dimension.putString("type", definition.dimensionType());
            dimensions.put(definition.dimensionType(), dimension);
        }
        worldGenSettings.put("dimensions", dimensions);
        worldGenSettings.putByte("bonus_chest", (byte) 0);
        worldGenSettings.putByte("generate_structures", (byte) 0);
        worldGenSettings.putLong("seed", 0L);
        return worldGenSettings;
    }

    public static boolean createLoadableWorldWithSpawn(Path worldFolder, String levelName,
                                                        int spawnX, int spawnY, int spawnZ) {
        try {
            if (!createLoadableWorld(worldFolder, levelName, true, true)) return false;
            UUID playerId = singleplayerUuid(levelName);
            WorldStructureApi.writeSpawnedLevelData(
                    worldFolder, levelName, playerId, spawnX, spawnY, spawnZ,
                    createMirrorWorldGenSettings());
            writeCompressed(WorldStructureApi.playerDataPath(worldFolder, playerId).toFile(),
                    createPlayerData(spawnX, spawnY, spawnZ));
            WMLogger.debug("Nearby-export world created at: " + worldFolder.toAbsolutePath());
            return true;
        } catch (Exception e) {
            WMLogger.warn("Nearby export world structure creation failed path=" + worldFolder, e);
            return false;
        }
    }

    public static boolean createLoadableWorld(Path worldFolder, String levelName,
                                              boolean migrateWorldgen, boolean refreshAssets) {
        File folder = worldFolder.toFile();
        try {
            boolean firstTime = !worldFolder.resolve("level.dat").toFile().exists();
            if (!folder.exists()) folder.mkdirs();
            for (String directory : WorldStructureApi.worldSubdirectories()) {
                new File(folder, directory).mkdirs();
            }

            if (firstTime) {
                MirrorWorldgenAssets.install(worldFolder, WorldStructureApi.dataPackFormat());
                UUID playerId = singleplayerUuid(levelName);
                WorldStructureApi.createInitialWorld(worldFolder, levelName, playerId,
                        createMirrorWorldGenSettings());
                writeCompressed(WorldStructureApi.playerDataPath(worldFolder, playerId).toFile(),
                        createPlayerData(0, 80, 0));
                WMLogger.debug("World structure created at: " + folder.getAbsolutePath()
                        + " (name: " + resolvedLevelName(levelName) + ")");
            } else {
                if (migrateWorldgen || refreshAssets) {
                    MirrorWorldgenAssets.install(worldFolder, WorldStructureApi.dataPackFormat());
                    WorldStructureApi.updateOwnedLevelData(worldFolder, migrateWorldgen,
                            createMirrorWorldGenSettings());
                }
                WMLogger.debug("World structure updated (incremental sync): "
                        + folder.getAbsolutePath());
            }
            return true;
        } catch (Exception e) {
            WMLogger.warn("Mirror world structure update failed path=" + folder, e);
            return false;
        }
    }

    static String resolvedLevelName(String levelName) {
        return levelName != null && !levelName.isEmpty() ? levelName : "Downloaded World";
    }

    static void writeCompressed(File file, CompoundTag tag) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream output = new FileOutputStream(file)) {
            NbtIo.writeCompressed(tag, output);
        }
    }

    static CompoundTag createPlayerData(int x, int y, int z) {
        CompoundTag player = new CompoundTag();
        NbtUtils.addCurrentDataVersion(player);
        player.putString("Dimension", "minecraft:overworld");

        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x + 0.5D));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z + 0.5D));
        player.put("Pos", pos);

        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(0.0F));
        rotation.add(FloatTag.valueOf(0.0F));
        player.put("Rotation", rotation);

        ListTag motion = new ListTag();
        motion.add(DoubleTag.valueOf(0.0D));
        motion.add(DoubleTag.valueOf(0.0D));
        motion.add(DoubleTag.valueOf(0.0D));
        player.put("Motion", motion);

        player.putFloat("Health", 20.0F);
        player.putInt("playerGameType", 1);
        player.putBoolean("OnGround", true);
        player.putInt("Score", 0);
        player.putShort("Air", (short) 300);
        player.putShort("Fire", (short) -20);
        player.put("Inventory", new ListTag());
        player.put("EnderItems", new ListTag());
        return player;
    }

    private static UUID singleplayerUuid(String levelName) {
        return UUID.nameUUIDFromBytes(
                ("worldmirror:" + levelName).getBytes(StandardCharsets.UTF_8));
    }

    private static CompoundTag createVoidNoiseGenerator(String biome, int minY, int height,
                                                        int seaLevel, int horizontalSize,
                                                        int verticalSize) {
        CompoundTag generator = new CompoundTag();
        CompoundTag settings = new CompoundTag();
        CompoundTag noise = new CompoundTag();
        noise.putInt("min_y", minY);
        noise.putInt("height", height);
        noise.putInt("size_horizontal", horizontalSize);
        noise.putInt("size_vertical", verticalSize);
        settings.put("noise", noise);
        settings.put("default_block", blockState("minecraft:air"));
        settings.put("default_fluid", blockState("minecraft:air"));
        settings.putInt("sea_level", seaLevel);
        settings.putBoolean("disable_mob_generation", true);
        settings.putBoolean("aquifers_enabled", false);
        settings.putBoolean("ore_veins_enabled", false);
        settings.putBoolean("legacy_random_source", false);
        settings.put("spawn_target", new ListTag());
        CompoundTag router = new CompoundTag();
        for (String field : MirrorWorldgenDefinition.ZERO_NOISE_ROUTER_FIELDS) {
            router.putDouble(field, 0.0D);
        }
        router.putDouble("final_density", MirrorWorldgenDefinition.VOID_FINAL_DENSITY);
        settings.put("noise_router", router);
        CompoundTag rule = new CompoundTag();
        rule.putString("type", "minecraft:block");
        rule.put("result_state", blockState("minecraft:air"));
        settings.put("surface_rule", rule);
        generator.put("settings", settings);
        CompoundTag biomeSource = new CompoundTag();
        biomeSource.putString("type", "minecraft:fixed");
        biomeSource.putString("biome", biome);
        generator.put("biome_source", biomeSource);
        generator.putString("type", "minecraft:noise");
        return generator;
    }

    private static CompoundTag blockState(String block) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", block);
        return state;
    }
}
