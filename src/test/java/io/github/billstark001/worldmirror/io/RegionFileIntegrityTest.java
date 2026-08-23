package io.github.billstark001.worldmirror.io;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionFileIntegrityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOwnedDataSectors() throws Exception {
        Path region = writeRegion("r.-2.3.mca", MapEntry.at(17, 2, 1));

        RegionFileIntegrity.Inspection inspection =
                RegionFileIntegrity.inspect(region, -2, 3);

        assertTrue(inspection.usable());
        assertTrue(inspection.invalidChunks().isEmpty());
    }

    @Test
    void rejectsLocationsInsideTheHeader() throws Exception {
        Path region = writeRegion("r.0.0.mca", MapEntry.at(65, 1, 2));

        RegionFileIntegrity.Inspection inspection =
                RegionFileIntegrity.inspect(region, 0, 0);

        assertTrue(inspection.usable());
        assertEquals(java.util.Set.of(new ChunkPos(1, 2)), inspection.invalidChunks());
    }

    @Test
    void rejectsOverlappingAllocations() throws Exception {
        Path region = writeRegion("r.0.0.mca",
                MapEntry.at(0, 2, 2), MapEntry.at(1, 3, 1));

        RegionFileIntegrity.Inspection inspection =
                RegionFileIntegrity.inspect(region, 0, 0);

        assertEquals(java.util.Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0)),
                inspection.invalidChunks());
    }

    @Test
    void rejectsTruncatedNonEmptyHeaders() throws Exception {
        Path region = temporaryDirectory.resolve("r.0.0.mca");
        Files.write(region, new byte[64]);

        RegionFileIntegrity.Inspection inspection =
                RegionFileIntegrity.inspect(region, 0, 0);

        assertFalse(inspection.usable());
    }

    private Path writeRegion(String name, MapEntry... entries) throws Exception {
        ByteBuffer data = ByteBuffer.allocate(4 * 4096);
        for (MapEntry entry : entries) {
            data.putInt(entry.index() * 4, entry.sectorOffset() << 8 | entry.sectorCount());
        }
        Path region = temporaryDirectory.resolve(name);
        Files.write(region, data.array());
        return region;
    }

    private record MapEntry(int index, int sectorOffset, int sectorCount) {
        static MapEntry at(int index, int sectorOffset, int sectorCount) {
            return new MapEntry(index, sectorOffset, sectorCount);
        }
    }
}
