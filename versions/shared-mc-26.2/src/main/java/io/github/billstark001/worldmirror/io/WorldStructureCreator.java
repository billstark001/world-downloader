package io.github.billstark001.worldmirror.io;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.mojang.serialization.Lifecycle;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;

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
        // --- dimensions ---
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

    public static PrimaryLevelData createWorldData(String levelName) {
        String resolvedName = (levelName != null && !levelName.isEmpty())
                ? levelName
                : "Downloaded World";
        LevelSettings settings = new LevelSettings(
                resolvedName,
                GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                true,
                WorldDataConfiguration.DEFAULT
        );
        PrimaryLevelData data = new PrimaryLevelData(
                settings,
                PrimaryLevelData.SpecialWorldProperty.NONE,
                Lifecycle.stable()
        );
        data.setInitialized(true);
        data.setGameTime(6000L);
        data.setSpawn(LevelData.RespawnData.of(Level.OVERWORLD, new BlockPos(0, 80, 0), 0.0F, 0.0F));
        return data;
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

    private static GameRules createGameRules(WorldDataConfiguration dataConfiguration) {
        GameRules gameRules = new GameRules(dataConfiguration.enabledFeatures());
        gameRules.set(GameRules.ADVANCE_TIME, false, null);
        gameRules.set(GameRules.SPAWN_MOBS, false, null);
        gameRules.set(GameRules.RANDOM_TICK_SPEED, 0, null);
        return gameRules;
    }

    private static CompoundTag createWeatherData() {
        CompoundTag weather = new CompoundTag();
        weather.putInt("clear_weather_time", 0);
        weather.putInt("rain_time", 0);
        weather.putInt("thunder_time", 0);
        weather.putBoolean("raining", false);
        weather.putBoolean("thundering", false);
        return weather;
    }

    private static CompoundTag createWorldClocksData() {
        CompoundTag clocks = new CompoundTag();
        CompoundTag clockStates = new CompoundTag();
        clockStates.put("minecraft:overworld", createClockState(6000L));
        clockStates.put("minecraft:the_end", createClockState(6000L));
        clocks.put("clocks", clockStates);
        return clocks;
    }

    private static CompoundTag createClockState(long totalTicks) {
        CompoundTag state = new CompoundTag();
        state.putLong("total_ticks", totalTicks);
        state.putFloat("partial_tick", 0.0F);
        state.putFloat("rate", 1.0F);
        state.putBoolean("paused", false);
        return state;
    }

    /**
     * Creates the {@code level.dat} for a new nearby-export world, setting the
     * spawn point to the player's current block position.
     *
     * @param worldFolderPath root directory of the new world
     * @param levelName       human-readable name for the save
     * @param spawnX          spawn block X coordinate
     * @param spawnY          spawn block Y coordinate
     * @param spawnZ          spawn block Z coordinate
     */
    public static boolean createLoadableWorldWithSpawn(Path worldFolderPath, String levelName,
                                                       int spawnX, int spawnY, int spawnZ) {
        try {
            if (!createLoadableWorld(worldFolderPath, levelName, true, true)) return false;

            PrimaryLevelData data = createWorldData(levelName);
            data.setSpawn(LevelData.RespawnData.of(
                    Level.OVERWORLD, new BlockPos(spawnX, spawnY, spawnZ), 0.0F, 0.0F));

            UUID singleplayerUuid = UUID.nameUUIDFromBytes(
                    ("worldmirror:" + levelName).getBytes(StandardCharsets.UTF_8));
            writeLevelDat(worldFolderPath.resolve("level.dat").toFile(), data, singleplayerUuid);
            writeCompressed(
                    worldFolderPath.resolve("players/data/" + singleplayerUuid + ".dat").toFile(),
                    createPlayerData(spawnX, spawnY, spawnZ));
            WMLogger.debug("Nearby-export world created at: " + worldFolderPath.toAbsolutePath());
            return true;
        } catch (Exception e) {
            WMLogger.warn("Nearby export world structure creation failed path=" + worldFolderPath, e);
            return false;
        }
    }

    /**
     * Creates or updates the {@code level.dat} and supporting directory structure
     * for a mirror world.
     *
     * <p>On the <em>first</em> call (no {@code level.dat} yet), a full {@code level.dat}
     * is written and "World structure created" is logged.  Subsequent calls only
     * update World Mirror-owned world data.
     *
     * @param worldFolder  root directory of the mirror world
     * @param levelName    human-readable name to embed in {@code level.dat}
     */
    public static boolean createLoadableWorld(java.nio.file.Path worldFolderPath, String levelName,
                                              boolean migrateWorldgen, boolean refreshAssets) {
        File worldFolder = worldFolderPath.toFile();
        try {
            boolean firstTime = !(new File(worldFolder, "level.dat")).exists();

            if (!worldFolder.exists()) {
                worldFolder.mkdirs();
            }

            String[] subDirs = worldSubDirs();
            for (String dir : subDirs) {
                mkdirs(worldFolder, dir);
            }

            if (firstTime) {
                MirrorWorldgenAssets.install(worldFolderPath, net.minecraft.SharedConstants.DATA_PACK_FORMAT_MAJOR);
                UUID singleplayerUuid = UUID.nameUUIDFromBytes(
                        ("worldmirror:" + levelName).getBytes(StandardCharsets.UTF_8));
                PrimaryLevelData data = createWorldData(levelName);
                writeLevelDat(new File(worldFolder, "level.dat"), data, singleplayerUuid);
                writeCompressed(new File(worldFolder, "players/data/" + singleplayerUuid + ".dat"), createPlayerData());
                writeWorldGenSettings(worldFolderPath);
                LevelStorageSource.writeGameRules(data, worldFolderPath, createGameRules(data.getDataConfiguration()));
                writeSavedData(new File(worldFolder, "data/minecraft/weather.dat"), createWeatherData());
                writeSavedData(new File(worldFolder, "data/minecraft/world_clocks.dat"), createWorldClocksData());
                WMLogger.debug("World structure created at: " + worldFolder.getAbsolutePath()
                        + " (name: " + data.getLevelName() + ")");
            } else {
                if (migrateWorldgen || refreshAssets) {
                    MirrorWorldgenAssets.install(worldFolderPath, net.minecraft.SharedConstants.DATA_PACK_FORMAT_MAJOR);
                    patchEnabledDataPack(worldFolderPath.resolve("level.dat"));
                    if (migrateWorldgen) writeWorldGenSettings(worldFolderPath);
                }
                WMLogger.debug("World structure updated (incremental sync): " + worldFolder.getAbsolutePath());
            }
            return true;
        } catch (Exception e) {
            WMLogger.warn("Mirror world structure update failed path=" + worldFolder, e);
            return false;
        }
    }

    private static void mkdirs(File worldFolder, String relativePath) {
        (new File(worldFolder, relativePath)).mkdirs();
    }

    private static String[] worldSubDirs() {
        String[] common = {
                "players/advancements",
                "players/data",
                "players/stats",
                "data/minecraft",
                "datapacks",
                "resourcepacks"
        };
        String[] dimensionDirs = {
                "dimensions/minecraft/overworld/region",
                "dimensions/minecraft/overworld/entities",
                "dimensions/minecraft/overworld/poi",
                "dimensions/minecraft/overworld/data/minecraft",
                "dimensions/minecraft/the_nether/region",
                "dimensions/minecraft/the_nether/entities",
                "dimensions/minecraft/the_nether/poi",
                "dimensions/minecraft/the_nether/data/minecraft",
                "dimensions/minecraft/the_end/region",
                "dimensions/minecraft/the_end/entities",
                "dimensions/minecraft/the_end/poi",
                "dimensions/minecraft/the_end/data/minecraft"
        };
        String[] subDirs = new String[dimensionDirs.length + common.length];
        System.arraycopy(dimensionDirs, 0, subDirs, 0, dimensionDirs.length);
        System.arraycopy(common, 0, subDirs, dimensionDirs.length, common.length);
        return subDirs;
    }

    private static void writeLevelDat(File file, PrimaryLevelData data, UUID singleplayerUuid) throws Exception {
        CompoundTag root = new CompoundTag();
        CompoundTag levelData = data.createTag(singleplayerUuid);
        enableDataPack(levelData);
        root.put("Data", levelData);
        writeCompressed(file, root);
    }

    private static void writeWorldGenSettings(java.nio.file.Path worldFolderPath) throws Exception {
        writeSavedData(worldFolderPath.resolve("data/minecraft/world_gen_settings.dat").toFile(),
                createMirrorWorldGenSettings());
    }

    private static void patchEnabledDataPack(Path levelDat) throws Exception {
        CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
        CompoundTag levelData = root.getCompoundOrEmpty("Data");
        enableDataPack(levelData);
        root.put("Data", levelData);
        writeCompressed(levelDat.toFile(), root);
    }

    private static void enableDataPack(CompoundTag levelData) {
        CompoundTag packs = levelData.getCompoundOrEmpty("DataPacks");
        ListTag enabled = packs.getListOrEmpty("Enabled");
        for (int i = 0; i < enabled.size(); i++) {
            if (MirrorWorldgenAssets.PACK_ID.equals(enabled.getStringOr(i, ""))) return;
        }
        enabled.add(StringTag.valueOf(MirrorWorldgenAssets.PACK_ID));
        packs.put("Enabled", enabled);
        if (!packs.contains("Disabled")) packs.put("Disabled", new ListTag());
        levelData.put("DataPacks", packs);
    }

    private static void writeSavedData(File file, CompoundTag data) throws Exception {
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtUtils.addCurrentDataVersion(root);
        writeCompressed(file, root);
    }

    private static void writeCompressed(File file, CompoundTag tag) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            NbtIo.writeCompressed(tag, fos);
        }
    }
}
