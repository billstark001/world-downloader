package io.github.billstark001.worldmirror.io;

import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;

import java.nio.file.Path;
import java.util.UUID;

/** Minecraft 26.x translation for level.dat and the dimension-first save layout. */
final class WorldStructureApi {
    private WorldStructureApi() { }

    static int dataPackFormat() {
        return SharedConstants.DATA_PACK_FORMAT_MAJOR;
    }

    static String[] worldSubdirectories() {
        return new String[] {
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
                "dimensions/minecraft/the_end/data/minecraft",
                "players/advancements", "players/data", "players/stats",
                "data/minecraft", "datapacks", "resourcepacks"
        };
    }

    static Path playerDataPath(Path worldFolder, UUID playerId) {
        return worldFolder.resolve("players/data/" + playerId + ".dat");
    }

    static void createInitialWorld(Path worldFolder, String levelName, UUID playerId,
                                   CompoundTag worldGenSettings) throws Exception {
        PrimaryLevelData data = createLevelData(levelName, 0, 80, 0);
        writeLevelData(worldFolder.resolve("level.dat"), data, playerId);
        writeSavedData(worldFolder.resolve("data/minecraft/world_gen_settings.dat"),
                worldGenSettings);
        LevelStorageSource.writeGameRules(data, worldFolder,
                createGameRules(data.getDataConfiguration()));
        writeSavedData(worldFolder.resolve("data/minecraft/weather.dat"), createWeatherData());
        writeSavedData(worldFolder.resolve("data/minecraft/world_clocks.dat"),
                createWorldClocksData());
    }

    static void writeSpawnedLevelData(Path worldFolder, String levelName, UUID playerId,
                                      int spawnX, int spawnY, int spawnZ,
                                      CompoundTag worldGenSettings) throws Exception {
        writeLevelData(worldFolder.resolve("level.dat"),
                createLevelData(levelName, spawnX, spawnY, spawnZ), playerId);
    }

    static void updateOwnedLevelData(Path worldFolder, boolean migrateWorldgen,
                                     CompoundTag worldGenSettings) throws Exception {
        patchEnabledDataPack(worldFolder.resolve("level.dat"));
        if (migrateWorldgen) {
            writeSavedData(worldFolder.resolve("data/minecraft/world_gen_settings.dat"),
                    worldGenSettings);
        }
    }

    private static PrimaryLevelData createLevelData(String levelName,
                                                    int spawnX, int spawnY, int spawnZ) {
        LevelSettings settings = new LevelSettings(
                WorldStructureCreator.resolvedLevelName(levelName),
                GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                true,
                WorldDataConfiguration.DEFAULT);
        PrimaryLevelData data = new PrimaryLevelData(settings,
                PrimaryLevelData.SpecialWorldProperty.NONE, Lifecycle.stable());
        data.setInitialized(true);
        data.setGameTime(6000L);
        data.setSpawn(LevelData.RespawnData.of(Level.OVERWORLD,
                new BlockPos(spawnX, spawnY, spawnZ), 0.0F, 0.0F));
        return data;
    }

    private static GameRules createGameRules(WorldDataConfiguration configuration) {
        GameRules rules = new GameRules(configuration.enabledFeatures());
        rules.set(GameRules.ADVANCE_TIME, false, null);
        rules.set(GameRules.SPAWN_MOBS, false, null);
        rules.set(GameRules.RANDOM_TICK_SPEED, 0, null);
        return rules;
    }

    private static void writeLevelData(Path file, PrimaryLevelData data, UUID playerId)
            throws Exception {
        CompoundTag root = new CompoundTag();
        CompoundTag levelData = data.createTag(playerId);
        enableDataPack(levelData);
        root.put("Data", levelData);
        WorldStructureCreator.writeCompressed(file.toFile(), root);
    }

    private static void patchEnabledDataPack(Path levelDat) throws Exception {
        CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
        CompoundTag levelData = root.getCompoundOrEmpty("Data");
        enableDataPack(levelData);
        root.put("Data", levelData);
        WorldStructureCreator.writeCompressed(levelDat.toFile(), root);
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

    private static void writeSavedData(Path file, CompoundTag data) throws Exception {
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtUtils.addCurrentDataVersion(root);
        WorldStructureCreator.writeCompressed(file.toFile(), root);
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
        CompoundTag states = new CompoundTag();
        states.put("minecraft:overworld", createClockState(6000L));
        states.put("minecraft:the_end", createClockState(6000L));
        clocks.put("clocks", states);
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
}
