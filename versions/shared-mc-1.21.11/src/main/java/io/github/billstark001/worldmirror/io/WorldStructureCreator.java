package io.github.billstark001.worldmirror.io;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;

@Environment(EnvType.CLIENT)
public class WorldStructureCreator {

    private static CompoundTag createVoidNoiseGenerator(String biome, int minY, int height,
                                                        int seaLevel, int horizontalSize, int verticalSize) {
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

    public static CompoundTag createMirrorWorldGenSettings() {
        CompoundTag worldGenSettings = new CompoundTag();
        CompoundTag dimensions = new CompoundTag();

        for (MirrorWorldgenDefinition.Dimension definition : MirrorWorldgenDefinition.DIMENSIONS) {
            CompoundTag dimension = new CompoundTag();
            dimension.put("generator", createVoidNoiseGenerator(definition.biome(), definition.minY(),
                    definition.height(), definition.seaLevel(), definition.horizontalSize(), definition.verticalSize()));
            dimension.putString("type", definition.dimensionType());
            dimensions.put(definition.dimensionType(), dimension);
        }

        worldGenSettings.put("dimensions", dimensions);
        worldGenSettings.putByte("bonus_chest", (byte) 0);
        worldGenSettings.putByte("generate_structures", (byte) 0);
        worldGenSettings.putLong("seed", 0L);

        return worldGenSettings;
    }

    public static CompoundTag createWorldData(String levelName) {
        return createWorldData(levelName, 0, 80, 0);
    }

    public static CompoundTag createWorldData(String levelName, int spawnX, int spawnY, int spawnZ) {
        CompoundTag data = new CompoundTag();
        data.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        data.putString("LevelName", (levelName != null && !levelName.isEmpty())
                ? levelName
                : "Downloaded World");
        data.putLong("RandomSeed", 0L);
        data.putInt("version", 19133);
        data.putBoolean("initialized", true);
        data.putInt("GameType", 1);
        data.putBoolean("allowCommands", true);
        data.putBoolean("hardcore", false);
        data.putInt("Difficulty", 0);
        data.putBoolean("DifficultyLocked", false);
        data.put("WorldGenSettings", createMirrorWorldGenSettings());
        data.put("DataPacks", createDataPacks());
        data.put("spawn", createSpawnSettings(spawnX, spawnY, spawnZ));
        data.putLong("Time", 6000L);
        data.putLong("DayTime", 6000L);
        data.putLong("LastPlayed", System.currentTimeMillis());
        data.put("WorldBorder", createWorldBorder());
        data.put("game_rules", createGameRules());
        data.put("Player", createPlayerData(spawnX, spawnY, spawnZ));
        return data;
    }

    private static CompoundTag createSpawnSettings(int x, int y, int z) {
        CompoundTag spawn = new CompoundTag();
        spawn.putString("dimension", "minecraft:overworld");
        spawn.putFloat("pitch", 0.0F);
        spawn.putFloat("yaw", 0.0F);
        spawn.put("pos", new IntArrayTag(new int[] {x, y, z}));
        return spawn;
    }

    private static CompoundTag createWorldBorder() {
        CompoundTag worldBorder = new CompoundTag();
        worldBorder.putDouble("BorderCenterX", 0.0D);
        worldBorder.putDouble("BorderCenterZ", 0.0D);
        worldBorder.putDouble("BorderSize", 5.9999968E7D);
        worldBorder.putDouble("BorderSizeLerpTarget", 5.9999968E7D);
        worldBorder.putLong("BorderSizeLerpTime", 0L);
        worldBorder.putDouble("BorderSafeZone", 5.0D);
        worldBorder.putDouble("BorderDamagePerBlock", 0.2D);
        worldBorder.putInt("BorderWarningBlocks", 5);
        worldBorder.putInt("BorderWarningTime", 15);
        return worldBorder;
    }

    private static CompoundTag createGameRules() {
        CompoundTag gameRules = new CompoundTag();
        gameRules.putString("doDaylightCycle", "false");
        gameRules.putString("doMobSpawning", "false");
        gameRules.putString("randomTickSpeed", "0");
        return gameRules;
    }

    private static CompoundTag createPlayerData() {
        return createPlayerData(0, 80, 0);
    }

    private static CompoundTag createPlayerData(int x, int y, int z) {
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

    public static boolean createLoadableWorldWithSpawn(Path worldFolderPath, String levelName,
                                                       int spawnX, int spawnY, int spawnZ) {
        try {
            if (!createLoadableWorld(worldFolderPath, levelName, true, true)) return false;
            CompoundTag data = createWorldData(levelName, spawnX, spawnY, spawnZ);
            UUID singleplayerUuid = UUID.nameUUIDFromBytes(
                    ("worldmirror:" + levelName).getBytes(StandardCharsets.UTF_8));
            writeLevelDat(worldFolderPath.resolve("level.dat").toFile(), data);
            writeCompressed(
                    worldFolderPath.resolve("playerdata/" + singleplayerUuid + ".dat").toFile(),
                    createPlayerData(spawnX, spawnY, spawnZ));
            WMLogger.debug("Nearby-export world created at: " + worldFolderPath.toAbsolutePath());
            return true;
        } catch (Exception e) {
            WMLogger.warn("Nearby export world structure creation failed path=" + worldFolderPath, e);
            return false;
        }
    }

    public static boolean createLoadableWorld(Path worldFolderPath, String levelName,
                                              boolean migrateWorldgen, boolean refreshAssets) {
        File worldFolder = worldFolderPath.toFile();
        try {
            boolean firstTime = !(new File(worldFolder, "level.dat")).exists();

            if (!worldFolder.exists()) {
                worldFolder.mkdirs();
            }

            for (String dir : worldSubDirs()) {
                mkdirs(worldFolder, dir);
            }

            if (firstTime) {
                MirrorWorldgenAssets.install(worldFolderPath, SharedConstants.DATA_PACK_FORMAT_MAJOR);
                CompoundTag data = createWorldData(levelName);
                UUID singleplayerUuid = UUID.nameUUIDFromBytes(
                        ("worldmirror:" + levelName).getBytes(StandardCharsets.UTF_8));
                writeLevelDat(new File(worldFolder, "level.dat"), data);
                writeCompressed(new File(worldFolder, "playerdata/" + singleplayerUuid + ".dat"), createPlayerData());
                WMLogger.debug("World structure created at: " + worldFolder.getAbsolutePath()
                        + " (name: " + data.getString("LevelName").orElse("Downloaded World") + ")");
            } else {
                if (migrateWorldgen || refreshAssets) {
                    MirrorWorldgenAssets.install(worldFolderPath, SharedConstants.DATA_PACK_FORMAT_MAJOR);
                    CompoundTag root = NbtIo.readCompressed(worldFolderPath.resolve("level.dat"), NbtAccounter.unlimitedHeap());
                    CompoundTag data = root.getCompoundOrEmpty("Data");
                    if (migrateWorldgen) data.put("WorldGenSettings", createMirrorWorldGenSettings());
                    enableDataPack(data);
                    root.put("Data", data);
                    writeCompressed(new File(worldFolder, "level.dat"), root);
                }
                WMLogger.debug("World structure updated (incremental sync): " + worldFolder.getAbsolutePath());
            }
            return true;
        } catch (Exception e) {
            WMLogger.warn("Mirror world structure update failed path=" + worldFolder, e);
            return false;
        }
    }

    private static CompoundTag createDataPacks() {
        CompoundTag packs = new CompoundTag();
        ListTag enabled = new ListTag();
        enabled.add(StringTag.valueOf(MirrorWorldgenAssets.PACK_ID));
        packs.put("Enabled", enabled);
        packs.put("Disabled", new ListTag());
        return packs;
    }

    private static void enableDataPack(CompoundTag data) {
        CompoundTag packs = data.getCompoundOrEmpty("DataPacks");
        ListTag enabled = packs.getListOrEmpty("Enabled");
        for (int i = 0; i < enabled.size(); i++) {
            if (MirrorWorldgenAssets.PACK_ID.equals(enabled.getStringOr(i, ""))) {
                data.put("DataPacks", packs);
                return;
            }
        }
        enabled.add(StringTag.valueOf(MirrorWorldgenAssets.PACK_ID));
        packs.put("Enabled", enabled);
        data.put("DataPacks", packs);
    }

    private static void mkdirs(File worldFolder, String relativePath) {
        (new File(worldFolder, relativePath)).mkdirs();
    }

    private static String[] worldSubDirs() {
        return new String[] {
                "region",
                "entities",
                "poi",
                "DIM-1/region",
                "DIM-1/entities",
                "DIM-1/poi",
                "DIM1/region",
                "DIM1/entities",
                "DIM1/poi",
                "playerdata",
                "advancements",
                "stats",
                "data",
                "datapacks",
                "resourcepacks"
        };
    }

    private static void writeLevelDat(File file, CompoundTag data) throws Exception {
        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        writeCompressed(file, root);
    }

    private static void writeCompressed(File file, CompoundTag tag) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            NbtIo.writeCompressed(tag, fos);
        }
    }
}
