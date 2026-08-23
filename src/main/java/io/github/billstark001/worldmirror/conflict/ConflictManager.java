package io.github.billstark001.worldmirror.conflict;

import io.github.ensgijs.nbt.mca.McaRegionFile;
import io.github.ensgijs.nbt.mca.TerrainChunk;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.CompoundTag;
import io.github.billstark001.worldmirror.io.ChunkExporter;
import io.github.billstark001.worldmirror.io.McaWriteSupport;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Manages chunk conflict data stored in the {@code conflict_chunks/} sub-folder of the
 * mirror world directory.
 *
 * <p>When the {@link ManualResolver} encounters a conflict, it calls
 * {@link #saveConflict} to persist the incoming (server-side) chunk as an MCA file
 * under {@code conflict_chunks/}.  The folder mirrors the world's region layout:
 * <ul>
 *   <li>{@code conflict_chunks/region/r.X.Z.mca} — Overworld conflicts</li>
 *   <li>{@code conflict_chunks/DIM-1/region/r.X.Z.mca} — Nether conflicts</li>
 *   <li>{@code conflict_chunks/DIM1/region/r.X.Z.mca} — End conflicts</li>
 *   <li>{@code conflict_chunks/dimensions/<ns>/<path>/region/r.X.Z.mca} — custom dims</li>
 * </ul>
 *
 * <p>Conflicts can be resolved per-chunk or in bulk via {@link #clearAllConflicts}.
 */
public final class ConflictManager {

    private ConflictManager() {}

    static final String CONFLICT_ROOT = "conflict_chunks";

    // ── Path helpers ──────────────────────────────────────────────────────────

    /**
     * Returns the region directory for conflict files of the given dimension,
     * mirroring the layout used by {@link ChunkExporter#regionDirForDimension}.
     */
    public static Path getConflictDir(Path worldFolder, ResourceKey<Level> dimension) {
        Identifier id = dimension.identifier();
        Identifier OVERWORLD = Identifier.fromNamespaceAndPath("minecraft", "overworld");
        Identifier NETHER    = Identifier.fromNamespaceAndPath("minecraft", "the_nether");
        Identifier END       = Identifier.fromNamespaceAndPath("minecraft", "the_end");
        Path root = worldFolder.resolve(CONFLICT_ROOT);
        if (id.equals(OVERWORLD)) {
            return root.resolve("region");
        } else if (id.equals(NETHER)) {
            return root.resolve("DIM-1").resolve("region");
        } else if (id.equals(END)) {
            return root.resolve("DIM1").resolve("region");
        } else {
            return root.resolve("dimensions")
                    .resolve(id.getNamespace())
                    .resolve(id.getPath())
                    .resolve("region");
        }
    }

    // ── Conflict persistence ──────────────────────────────────────────────────

    /**
     * Saves the incoming server-side {@code chunkNbt} for {@code pos} as a conflict
     * entry in the appropriate MCA file under {@code conflict_chunks/}.
     */
    public static boolean saveConflict(Path worldFolder, ChunkPos pos,
                                       net.minecraft.nbt.CompoundTag chunkNbt,
                                       ResourceKey<Level> dimension) {
        Path conflictDir = getConflictDir(worldFolder, dimension);
        try {
            Files.createDirectories(conflictDir);
            int regionX = pos.getRegionX();
            int regionZ = pos.getRegionZ();
            Path regionFile = conflictDir.resolve(
                    String.format("r.%d.%d.mca", regionX, regionZ));

            synchronized (McaWriteSupport.lockFor(regionFile)) {
                RegionStorageInfo storageInfo =
                        new RegionStorageInfo("world_mirror_conflicts", dimension, "chunk");
                try (RegionFile vanillaRegion =
                             new RegionFile(storageInfo, regionFile, conflictDir, false)) {
                    try (DataOutputStream output = vanillaRegion.getChunkDataOutputStream(pos)) {
                        NbtIo.write(chunkNbt, output);
                    }
                    vanillaRegion.flush();
                }
            }
            return true;
        } catch (Exception e) {
            WMLogger.warn("Conflict save failed chunk=" + pos
                    + " dimension=" + dimension.identifier(), e);
            return false;
        }
    }

    // ── Conflict queries ──────────────────────────────────────────────────────

    /** Returns {@code true} if a conflict entry exists for the given chunk. */
    public static boolean hasConflict(Path worldFolder, ChunkPos pos,
                                      ResourceKey<Level> dimension) {
        Path conflictDir = getConflictDir(worldFolder, dimension);
        int regionX = pos.getRegionX();
        int regionZ = pos.getRegionZ();
        Path regionFile = conflictDir.resolve(String.format("r.%d.%d.mca", regionX, regionZ));
        if (!regionFile.toFile().exists()) return false;
        try {
            McaRegionFile mca = McaFileHelpers.readAuto(regionFile.toFile());
            return mca.getChunk(pos.getRegionLocalX(), pos.getRegionLocalZ()) != null;
        } catch (Exception e) {
            WMLogger.warnRateLimited("conflict-presence-read", 30_000L,
                    "Conflict presence check failed file=" + regionFile + " chunk=" + pos, e);
            return false;
        }
    }

    /**
     * Returns all chunk positions that have a conflict entry for the given dimension.
     */
    public static Set<ChunkPos> listConflicts(Path worldFolder,
                                              ResourceKey<Level> dimension) {
        Path conflictDir = getConflictDir(worldFolder, dimension);
        Set<ChunkPos> result = new HashSet<>();
        File dir = conflictDir.toFile();
        if (!dir.exists()) return result;
        File[] mcaFiles = dir.listFiles((d, name) -> name.matches("r\\.-?\\d+\\.-?\\d+\\.mca"));
        if (mcaFiles == null) return result;
        for (File f : mcaFiles) {
            try {
                String name = f.getName(); // "r.X.Z.mca"
                String[] parts = name.substring(2, name.length() - 4).split("\\.");
                int regionX = Integer.parseInt(parts[0]);
                int regionZ = Integer.parseInt(parts[1]);
                McaRegionFile mca = McaFileHelpers.readAuto(f);
                for (int lx = 0; lx < 32; lx++) {
                    for (int lz = 0; lz < 32; lz++) {
                        if (mca.getChunk(lx, lz) != null) {
                            result.add(new ChunkPos(regionX * 32 + lx, regionZ * 32 + lz));
                        }
                    }
                }
            } catch (Exception e) {
                WMLogger.warnRateLimited("conflict-list-read", 30_000L,
                        "Conflict index read failed file=" + f.getName(), e);
            }
        }
        return result;
    }

    /** Returns the total number of conflict entries across all dimensions. */
    public static int countAllConflicts(Path worldFolder) {
        Path root = worldFolder.resolve(CONFLICT_ROOT);
        if (!root.toFile().exists()) return 0;
        int[] count = {0};
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".mca")).forEach(mcaPath -> {
                try {
                    McaRegionFile mca = McaFileHelpers.readAuto(mcaPath.toFile());
                    for (int lx = 0; lx < 32; lx++) {
                        for (int lz = 0; lz < 32; lz++) {
                            if (mca.getChunk(lx, lz) != null) count[0]++;
                        }
                    }
                } catch (Exception ignored) {}
            });
        } catch (IOException e) {
            WMLogger.warnRateLimited("conflict-count", 30_000L,
                    "Conflict count failed world=" + worldFolder, e);
        }
        return count[0];
    }

    // ── Conflict resolution ───────────────────────────────────────────────────

    /**
     * Resolves a single conflict for the given chunk.
     *
     * @param overwrite If {@code true}, the conflict (server) chunk is applied to
     *                  the world's region file.  If {@code false}, the conflict
     *                  entry is simply removed (keeping the existing local chunk).
     */
    public static void resolveConflict(Path worldFolder, ChunkPos pos,
                                       ResourceKey<Level> dimension, boolean overwrite) {
        Path conflictDir = getConflictDir(worldFolder, dimension);
        int regionX = pos.getRegionX();
        int regionZ = pos.getRegionZ();
        Path conflictFile = conflictDir.resolve(
                String.format("r.%d.%d.mca", regionX, regionZ));
        if (!conflictFile.toFile().exists()) return;

        try {
            synchronized (McaWriteSupport.lockFor(conflictFile)) {
                McaRegionFile conflictMca = McaFileHelpers.readAuto(conflictFile.toFile());
                int localX = pos.getRegionLocalX();
                int localZ = pos.getRegionLocalZ();
                TerrainChunk conflictChunk = conflictMca.getChunk(localX, localZ);

                if (overwrite && conflictChunk != null) {
                    // Apply conflict chunk to the live world region file
                    Path worldRegionDir = ChunkExporter.regionDirForDimension(worldFolder, dimension);
                    Path worldRegionFile = worldRegionDir.resolve(
                            String.format("r.%d.%d.mca", regionX, regionZ));
                    synchronized (McaWriteSupport.lockFor(worldRegionFile)) {
                        McaRegionFile worldMca;
                        if (worldRegionFile.toFile().exists()) {
                            worldMca = McaFileHelpers.readAuto(worldRegionFile.toFile());
                        } else {
                            Files.createDirectories(worldRegionDir);
                            worldMca = new McaRegionFile(regionX, regionZ);
                        }
                        worldMca.setChunk(localX, localZ, conflictChunk);
                        McaWriteSupport.writeAtomicallyLocked(worldMca, worldRegionFile);
                    }
                }

                // Remove the conflict entry
                conflictMca.setChunk(localX, localZ, null);
                boolean empty = isRegionEmpty(conflictMca);
                if (empty) {
                    Files.deleteIfExists(conflictFile);
                } else {
                    McaWriteSupport.writeAtomicallyLocked(conflictMca, conflictFile);
                }
            }
        } catch (Exception e) {
            WMLogger.warn("Conflict resolution failed chunk=" + pos
                    + " overwrite=" + overwrite, e);
        }
    }

    /**
     * Resolves all conflicts across every dimension in the mirror world.
     *
     * @param overwrite If {@code true}, all conflict chunks are applied to the world
     *                  before the conflict files are deleted.  If {@code false}, all
     *                  conflict entries are simply discarded.
     */
    public static void clearAllConflicts(Path worldFolder, boolean overwrite) {
        Path conflictRoot = worldFolder.resolve(CONFLICT_ROOT);
        if (!conflictRoot.toFile().exists()) return;

        try (Stream<Path> stream = Files.walk(conflictRoot)) {
            List<Path> mcaFiles = stream
                    .filter(p -> p.toString().endsWith(".mca"))
                    .toList();

            for (Path conflictMcaPath : mcaFiles) {
                synchronized (McaWriteSupport.lockFor(conflictMcaPath)) {
                    if (overwrite) {
                        Path worldMcaPath = worldFileForConflictFile(
                                worldFolder, conflictRoot, conflictMcaPath);
                        if (!applyConflictRegionToWorld(conflictMcaPath, worldMcaPath)) {
                            continue;
                        }
                    }
                    Files.deleteIfExists(conflictMcaPath);
                }
            }
        } catch (IOException e) {
            WMLogger.warn("Conflict cleanup failed world=" + worldFolder
                    + " overwrite=" + overwrite, e);
        }

        // Clean up any empty directories left behind
        deleteEmptyDirs(conflictRoot.toFile());
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static boolean applyConflictRegionToWorld(Path conflictFile, Path worldFile) {
        try {
            McaRegionFile conflictMca = McaFileHelpers.readAuto(conflictFile.toFile());
            synchronized (McaWriteSupport.lockFor(worldFile)) {
                McaRegionFile worldMca;
                if (worldFile.toFile().exists()) {
                    worldMca = McaFileHelpers.readAuto(worldFile.toFile());
                } else {
                    Files.createDirectories(worldFile.getParent());
                    // Derive region coords from file name
                    String name = worldFile.getFileName().toString();
                    String[] parts = name.substring(2, name.length() - 4).split("\\.");
                    int rx = Integer.parseInt(parts[0]);
                    int rz = Integer.parseInt(parts[1]);
                    worldMca = new McaRegionFile(rx, rz);
                }
                for (int lx = 0; lx < 32; lx++) {
                    for (int lz = 0; lz < 32; lz++) {
                        TerrainChunk chunk = conflictMca.getChunk(lx, lz);
                        if (chunk != null) {
                            worldMca.setChunk(lx, lz, chunk);
                        }
                    }
                }
                McaWriteSupport.writeAtomicallyLocked(worldMca, worldFile);
            }
            return true;
        } catch (Exception e) {
            WMLogger.warn("Conflict region apply failed source=" + conflictFile
                    + " target=" + worldFile, e);
            return false;
        }
    }

    private static Path worldFileForConflictFile(Path worldFolder, Path conflictRoot, Path conflictFile) {
        Path relPath = conflictRoot.relativize(conflictFile);
        if (relPath.getNameCount() >= 2) {
            String first = relPath.getName(0).toString();
            String second = relPath.getName(1).toString();
            String fileName = conflictFile.getFileName().toString();
            if ("region".equals(first)) {
                return ChunkExporter.regionDirForDimension(worldFolder, Level.OVERWORLD).resolve(fileName);
            }
            if ("DIM-1".equals(first) && "region".equals(second)) {
                return ChunkExporter.regionDirForDimension(worldFolder, Level.NETHER).resolve(fileName);
            }
            if ("DIM1".equals(first) && "region".equals(second)) {
                return ChunkExporter.regionDirForDimension(worldFolder, Level.END).resolve(fileName);
            }
        }
        return worldFolder.resolve(relPath);
    }

    private static boolean isRegionEmpty(McaRegionFile mca) {
        for (int lx = 0; lx < 32; lx++) {
            for (int lz = 0; lz < 32; lz++) {
                if (mca.getChunk(lx, lz) != null) return false;
            }
        }
        return true;
    }

    private static void deleteEmptyDirs(File dir) {
        if (!dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteEmptyDirs(child);
            }
        }
        File[] remaining = dir.listFiles();
        if (remaining != null && remaining.length == 0) {
            dir.delete();
        }
    }
}
