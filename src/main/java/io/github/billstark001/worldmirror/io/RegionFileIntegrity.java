package io.github.billstark001.worldmirror.io;

import net.minecraft.world.level.ChunkPos;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Read-only validation for the location table in a vanilla Anvil region file. */
final class RegionFileIntegrity {
    private static final int HEADER_BYTES = 8192;
    private static final int LOCATION_BYTES = 4096;
    private static final int SECTOR_BYTES = 4096;
    private static final int LOCATION_COUNT = 1024;

    private RegionFileIntegrity() {}

    record Inspection(boolean usable, Set<ChunkPos> invalidChunks, String failure) {
        static Inspection valid(Set<ChunkPos> invalidChunks) {
            return new Inspection(true, Set.copyOf(invalidChunks), null);
        }

        static Inspection unusable(String failure) {
            return new Inspection(false, Set.of(), failure);
        }
    }

    static Inspection inspect(Path regionFile, int regionX, int regionZ) throws IOException {
        if (Files.notExists(regionFile) || Files.size(regionFile) == 0L) {
            return Inspection.valid(Set.of());
        }

        long fileSize = Files.size(regionFile);
        if (fileSize < HEADER_BYTES) {
            return Inspection.unusable("truncated header bytes=" + fileSize);
        }
        if (fileSize % SECTOR_BYTES != 0L) {
            return Inspection.unusable("file is not sector-aligned bytes=" + fileSize);
        }

        ByteBuffer locations = ByteBuffer.allocate(LOCATION_BYTES);
        try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
            long position = 0L;
            while (locations.hasRemaining()) {
                int read = channel.read(locations, position);
                if (read < 0) throw new EOFException("truncated location table");
                position += read;
            }
        }
        locations.flip();

        Set<ChunkPos> invalid = new HashSet<>();
        Map<Integer, ChunkPos> sectorOwners = new HashMap<>();
        for (int index = 0; index < LOCATION_COUNT; index++) {
            int packed = locations.getInt();
            if (packed == 0) continue;

            int sectorOffset = packed >>> 8;
            int sectorCount = packed & 0xFF;
            ChunkPos chunk = chunkPos(regionX, regionZ, index);
            long endOffset = ((long) sectorOffset + sectorCount) * SECTOR_BYTES;
            if (sectorOffset < 2 || sectorCount == 0 || endOffset > fileSize) {
                invalid.add(chunk);
                continue;
            }

            for (int sector = sectorOffset; sector < sectorOffset + sectorCount; sector++) {
                ChunkPos previous = sectorOwners.putIfAbsent(sector, chunk);
                if (previous != null && !previous.equals(chunk)) {
                    invalid.add(previous);
                    invalid.add(chunk);
                }
            }
        }
        return Inspection.valid(invalid);
    }

    private static ChunkPos chunkPos(int regionX, int regionZ, int index) {
        return new ChunkPos(regionX * 32 + (index & 31), regionZ * 32 + (index >>> 5));
    }
}
