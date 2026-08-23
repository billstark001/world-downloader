package io.github.billstark001.worldmirror.io;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs for both 26.x targets and skips on 1.21.11, where world clocks do not exist. */
class WorldClocksDataTest {
    private static Class<?> packedClockStates;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try {
            packedClockStates = Class.forName("net.minecraft.world.clock.PackedClockStates");
        } catch (ClassNotFoundException unsupportedVersion) {
            packedClockStates = null;
        }
    }

    @BeforeEach
    void requireWorldClockTarget() {
        assumeTrue(packedClockStates != null, "Minecraft target has no PackedClockStates");
    }

    @Test
    void generatedPayloadRoundTripsThroughMinecraftCodec() throws Exception {
        CompoundTag data = generatedWorldClocksData();

        assertEquals(Set.of("minecraft:overworld", "minecraft:the_end"), data.keySet());
        assertFalse(data.contains("clocks"));
        assertEquals(data, roundTrip(data));
    }

    @Test
    void repairsOnlyTheExactWorldMirrorWrapper() throws Exception {
        Path file = tempDir.resolve("data/minecraft/world_clocks.dat");
        Files.createDirectories(file.getParent());
        CompoundTag expected = generatedWorldClocksData();
        CompoundTag malformedData = new CompoundTag();
        malformedData.put("clocks", expected.copy());
        writeSavedData(file, malformedData);

        repairOwnedSavedData(tempDir);

        CompoundTag repaired = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
                .getCompoundOrEmpty("data");
        assertEquals(expected, repaired);
        assertEquals(repaired, roundTrip(repaired));

        CompoundTag nonExact = new CompoundTag();
        nonExact.put("clocks", expected.copy());
        nonExact.putBoolean("third_party_marker", true);
        writeSavedData(file, nonExact);
        repairOwnedSavedData(tempDir);
        CompoundTag preserved = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
                .getCompoundOrEmpty("data");
        assertTrue(preserved.contains("clocks"));
        assertTrue(preserved.getBooleanOr("third_party_marker", false));
    }

    private static CompoundTag generatedWorldClocksData() throws Exception {
        Method method = WorldStructureApi.class.getDeclaredMethod("createWorldClocksData");
        method.setAccessible(true);
        return (CompoundTag) method.invoke(null);
    }

    private static void repairOwnedSavedData(Path worldFolder) throws Exception {
        Method method = WorldStructureApi.class.getDeclaredMethod(
                "repairOwnedSavedData", Path.class);
        method.setAccessible(true);
        method.invoke(null, worldFolder);
    }

    private static void writeSavedData(Path file, CompoundTag data) throws Exception {
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        NbtUtils.addCurrentDataVersion(root);
        WorldStructureCreator.writeCompressed(file.toFile(), root);
    }

    private static CompoundTag roundTrip(CompoundTag data) throws Exception {
        Method method = WorldStructureApi.class.getDeclaredMethod(
                "roundTripWorldClocksData", CompoundTag.class);
        method.setAccessible(true);
        return (CompoundTag) method.invoke(null, data);
    }
}
