package io.github.billstark001.worldmirror.io;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSettingsCreationTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void creationUsesTheCapturedSourceSettings(@TempDir Path world) throws Exception {
        WorldSettingsSnapshot settings = new WorldSettingsSnapshot(
                12_345L, 6_789L, true, true, 3);

        assertTrue(WorldStructureCreator.createLoadableWorld(
                world, "test", true, true, settings));

        CompoundTag data = readLevelData(world);
        assertEquals(12_345L, data.getLongOr("Time", -1L));
        assertEquals(3, difficultyId(data));
        if (data.contains("DayTime")) {
            assertEquals(6_789L, data.getLongOr("DayTime", -1L));
            assertTrue(data.getBooleanOr("raining", false));
            assertTrue(data.getBooleanOr("thundering", false));
        } else {
            CompoundTag weather = readSavedData(
                    world.resolve("data/minecraft/weather.dat"));
            assertTrue(weather.getBooleanOr("raining", false));
            assertTrue(weather.getBooleanOr("thundering", false));
        }
    }

    @Test
    void manualActionsChangeOnlyTheSelectedSetting(@TempDir Path world) throws Exception {
        assertTrue(WorldStructureCreator.createLoadableWorld(
                world, "test", true, true, WorldSettingsSnapshot.defaults()));
        WorldSettingsSnapshot changed = new WorldSettingsSnapshot(
                22_222L, 9_999L, true, false, 2);

        assertTrue(WorldStructureCreator.syncWorldSetting(
                world, changed, WorldStructureCreator.Setting.DIFFICULTY));
        CompoundTag afterDifficulty = readLevelData(world);
        assertEquals(2, difficultyId(afterDifficulty));
        assertEquals(WorldSettingsSnapshot.DEFAULT_TIME,
                afterDifficulty.getLongOr("Time", -1L));

        assertTrue(WorldStructureCreator.syncWorldSetting(
                world, changed, WorldStructureCreator.Setting.TIME));
        CompoundTag afterTime = readLevelData(world);
        assertEquals(22_222L, afterTime.getLongOr("Time", -1L));
        assertEquals(2, difficultyId(afterTime));

        assertTrue(WorldStructureCreator.syncWorldSetting(
                world, changed, WorldStructureCreator.Setting.WEATHER));
        if (afterTime.contains("DayTime")) {
            CompoundTag weather = readLevelData(world);
            assertTrue(weather.getBooleanOr("raining", false));
            assertFalse(weather.getBooleanOr("thundering", true));
        } else {
            CompoundTag weather = readSavedData(
                    world.resolve("data/minecraft/weather.dat"));
            assertTrue(weather.getBooleanOr("raining", false));
            assertFalse(weather.getBooleanOr("thundering", true));
        }
    }

    private static CompoundTag readLevelData(Path world) throws Exception {
        return NbtIo.readCompressed(
                world.resolve("level.dat"), NbtAccounter.unlimitedHeap())
                .getCompoundOrEmpty("Data");
    }

    private static CompoundTag readSavedData(Path file) throws Exception {
        assertTrue(Files.isRegularFile(file));
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
                .getCompoundOrEmpty("data");
    }

    private static int difficultyId(CompoundTag data) {
        if (data.contains("difficulty_settings")) {
            String name = data.getCompoundOrEmpty("difficulty_settings")
                    .getStringOr("difficulty", "");
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "peaceful" -> 0;
                case "easy" -> 1;
                case "normal" -> 2;
                case "hard" -> 3;
                default -> -1;
            };
        }
        return data.getByteOr("Difficulty", (byte) -1);
    }
}
