package io.github.billstark001.worldmirror.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-world metadata written to {@code <mirror>/worldmirror_meta.json}.
 * <p>
 * Survives being copied into {@code saves/} so that future sync sessions can
 * resume from where they left off.
 * <p>
 * This class is designed to be read/written from a background thread —
 * it does not access any Minecraft game-thread objects after construction.
 */
public class WorldMetadata {

    /** Current semantic format of the generated mirror-world dimensions. */
    public static final int CURRENT_WORLDGEN_SCHEMA = 1;
    public static final int CURRENT_METADATA_SCHEMA = 1;
    /** Cleanup revision independent from {@link #CURRENT_WORLDGEN_SCHEMA}. */
    public static final int CURRENT_VOID_CHUNK_CLEANUP_REVISION = 1;
    public static final String FORMAT = "worldmirror";

    // ── JSON fields ───────────────────────────────────────────────────────────

    /** Mod version that created / last updated this mirror. */
    public String modVersion = "unknown";

    /** Stable marker for read-only mirror-world discovery. */
    public String format = FORMAT;

    /** Format of this metadata document, independent from world-generation schema. */
    public int metadataSchema = CURRENT_METADATA_SCHEMA;

    /** Whether this is a synchronized mirror or a standalone nearby export. */
    public String mirrorKind = "synchronized";

    /** Stable save identity.  Unlike a folder name, it survives copies and moves. */
    public String mirrorId;

    /** Optional identity of the mirror this save was exported from. */
    public String parentMirrorId;

    /** {@code "singleplayer"} or {@code "server"}. */
    public String sourceType = "unknown";

    /** Server address or local world folder name. */
    public String sourceId = "unknown";

    /** Unix millis of the most recent completed sync. */
    public long lastSyncTime = 0;

    /**
     * Semantic world-generation schema.  Gson assigns {@code 0} when this field
     * is absent, which deliberately treats all pre-schema mirrors as migratable.
     */
    public int worldgenSchema = 0;

    /** Revision of the vanilla-readable world data pack embedded in the save. */
    public int worldgenAssetRevision = 0;

    /** Minecraft data version used when the embedded assets were last refreshed. */
    public int worldgenAssetDataVersion = 0;

    /**
     * Completion marker for removing only the all-air {@code minecraft:the_void}
     * chunks made by the pre-schema generator.  It intentionally does not bump
     * {@link #worldgenSchema}: schema 1 remains the only released schema.
     */
    public int legacyVoidChunkCleanupRevision = 0;

    /**
     * Legacy per-chunk last-write timestamp map — kept for reading old JSON files
     * during migration to {@code data/world_mirror.sqlite}.
     * <p>
     * This field is <em>not</em> written back to JSON after migration (it is set to
     * {@code null}, and GSON skips null fields by default).  New code should use
     * {@link ChunkDatabase} instead.
     *
     * @deprecated Use {@link ChunkDatabase} for chunk timestamp tracking.
     */
    @Deprecated
    public Map<String, Long> chunkUpdateTimes = null;

    // ── Persistence ───────────────────────────────────────────────────────────

    public static final String FILE_NAME = "worldmirror_meta.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Loads existing metadata from {@code worldFolder/worldmirror_meta.json}, or
     * creates a fresh instance with the supplied source information.
     * Safe to call from any thread.
     * <p>
     * If the loaded file contains a legacy {@code chunkUpdateTimes} field, that data
     * is preserved in the returned instance so the caller can migrate it to the
     * SQLite database via {@link #migrateAndCleanChunkTimes(ChunkDatabase, Path)}.
     */
    public static WorldMetadata loadOrCreate(Path worldFolder,
                                             String sourceId,
                                             String sourceType) {
        Path metaFile = worldFolder.resolve(FILE_NAME);
        if (metaFile.toFile().exists()) {
            try (Reader r = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
                WorldMetadata loaded = GSON.fromJson(r, WorldMetadata.class);
                if (loaded != null) {
                    // chunkUpdateTimes may be non-null if this is an old JSON file;
                    // keep it so the caller can trigger migration if needed.
                    return loaded;
                }
            } catch (Exception e) {
                WMLogger.warn("World metadata read failed; creating fresh file=" + metaFile, e);
            }
        }
        return create(sourceId, sourceType, "synchronized");
    }

    /** Creates metadata for a newly-created mirror world. */
    public static WorldMetadata create(String sourceId, String sourceType, String mirrorKind) {
        WorldMetadata meta = new WorldMetadata();
        meta.modVersion = currentModVersion();
        meta.sourceType = sourceType;
        meta.sourceId = sourceId;
        meta.mirrorKind = mirrorKind;
        meta.ensureMirrorId();
        return meta;
    }

    /** Reads metadata without creating or modifying a file. */
    public static Optional<WorldMetadata> loadIfPresent(Path worldFolder) {
        Path metaFile = worldFolder.resolve(FILE_NAME);
        if (!metaFile.toFile().exists()) return Optional.empty();
        try (Reader r = Files.newBufferedReader(metaFile, StandardCharsets.UTF_8)) {
            return Optional.ofNullable(GSON.fromJson(r, WorldMetadata.class));
        } catch (Exception e) {
            WMLogger.warn("World metadata read failed file=" + metaFile, e);
            return Optional.empty();
        }
    }

    /** Writes this metadata to {@code worldFolder/worldmirror_meta.json}. */
    public void save(Path worldFolder) {
        ensureMirrorId();
        Path metadataFile = worldFolder.resolve(FILE_NAME);
        Path temporaryFile = metadataFile.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(worldFolder);
            Files.writeString(temporaryFile, GSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(temporaryFile, metadataFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, metadataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            WMLogger.warn("World metadata save failed file=" + metadataFile, e);
            try { Files.deleteIfExists(temporaryFile); } catch (Exception ignored) {}
        }
    }

    // ── Per-chunk time tracking (legacy — replaced by ChunkDatabase) ─────────

    /**
     * If this instance was loaded from an old JSON file that contained a non-empty
     * {@code chunkUpdateTimes} map, this method migrates that data into {@code db}
     * and then clears the field so it is not written back to JSON.
     * <p>
     * Safe to call even if migration has already been performed (idempotent).
     *
     * @param db          open {@link ChunkDatabase} to migrate into
     * @param worldFolder mirror-world root (used to re-save cleaned JSON)
     */
    @SuppressWarnings("deprecation")
    public void migrateAndCleanChunkTimes(ChunkDatabase db, Path worldFolder) {
        if (chunkUpdateTimes != null && !chunkUpdateTimes.isEmpty()) {
            db.migrateFromChunkUpdateTimes(chunkUpdateTimes);
        }
        if (chunkUpdateTimes != null) {
            chunkUpdateTimes = null; // null → GSON skips field on next save
            save(worldFolder);      // write clean JSON without chunkUpdateTimes
        }
    }

    /** Whether the dimension generator itself must be replaced. */
    public boolean needsWorldgenMigration() {
        return worldgenSchema < CURRENT_WORLDGEN_SCHEMA;
    }

    /** Assigns an identity to legacy metadata the next time it is written. */
    public void ensureMirrorId() {
        if (mirrorId == null || mirrorId.isBlank()) mirrorId = UUID.randomUUID().toString();
    }

    /** True when this save was written by a newer world-generation schema. */
    public boolean hasFutureWorldgenSchema() {
        return worldgenSchema > CURRENT_WORLDGEN_SCHEMA;
    }

    /** Whether the embedded vanilla data pack must be refreshed for this game version. */
    public boolean needsWorldgenAssetRefresh(int dataVersion, int assetRevision) {
        return worldgenAssetDataVersion < dataVersion || worldgenAssetRevision < assetRevision;
    }

    /** True when refreshing assets would downgrade a newer mirror save. */
    public boolean hasFutureWorldgenAssets(int dataVersion, int assetRevision) {
        return worldgenAssetDataVersion > dataVersion || worldgenAssetRevision > assetRevision;
    }

    /** Whether an old, blank void chunk cleanup has not yet been performed. */
    public boolean needsLegacyVoidChunkCleanup() {
        return legacyVoidChunkCleanupRevision < CURRENT_VOID_CHUNK_CLEANUP_REVISION;
    }

    /** Marks both layers of world-generation migration as complete after a successful write. */
    public void markWorldgenCurrent(int dataVersion, int assetRevision) {
        worldgenSchema = CURRENT_WORLDGEN_SCHEMA;
        worldgenAssetDataVersion = dataVersion;
        worldgenAssetRevision = assetRevision;
        legacyVoidChunkCleanupRevision = CURRENT_VOID_CHUNK_CLEANUP_REVISION;
        modVersion = currentModVersion();
    }

    // ── Convenience update ────────────────────────────────────────────────────

    /**
     * Returns {@code true} if a {@code worldmirror_meta.json} exists in {@code worldFolder}
     * and its {@code sourceId} field matches the given value.
     * Returns {@code false} if the file does not exist or cannot be read.
     * Safe to call from any thread.
     */
    public static boolean isOwnedBy(Path worldFolder, String sourceId) {
        return loadIfPresent(worldFolder)
                .map(meta -> sourceId.equals(meta.sourceId))
                .orElse(false);
    }

    /**
     * Load-or-create, update {@code lastSyncTime} and {@code modVersion}, then save.
     * <p>
     * Chunk write timestamps are no longer stored in JSON; they are managed by
     * {@link ChunkDatabase} instead.
     */
    public static void update(Path worldFolder,
                              String sourceId,
                              String sourceType) {
        WorldMetadata meta = loadOrCreate(worldFolder, sourceId, sourceType);
        long now = System.currentTimeMillis();
        meta.lastSyncTime = now;
        meta.modVersion = currentModVersion();
        meta.chunkUpdateTimes = null; // ensure legacy field is not written back
        meta.save(worldFolder);
    }

    /** Updates normal sync bookkeeping on an already-loaded metadata object. */
    public void markSyncComplete(Path worldFolder) {
        lastSyncTime = System.currentTimeMillis();
        modVersion = currentModVersion();
        chunkUpdateTimes = null;
        save(worldFolder);
    }

    // ── Source detection (call on game thread) ────────────────────────────────

    public static String detectSourceType(Minecraft client) {
        try {
            if (client.getSingleplayerServer() != null) return "singleplayer";
        } catch (Exception ignored) {}
        return "server";
    }

    public static String detectSourceId(Minecraft client) {
        try {
            if (client.getCurrentServer() != null) {
                return "server:" + client.getCurrentServer().ip;
            }
        } catch (Exception ignored) {}
        try {
            if (client.getSingleplayerServer() != null) {
                return "local:" + client.getSingleplayerServer().getWorldData().getLevelName();
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private static String currentModVersion() {
        return FabricLoader.getInstance()
                .getModContainer("worldmirror")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
