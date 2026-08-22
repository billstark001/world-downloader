package io.github.billstark001.worldmirror.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Persistent mapping from source-ID → local mirror folder name and per-world settings.
 * <p>
 * Stored in {@code config/worldmirror/mirrors.json}.
 * Keys are the {@code sourceId} strings produced by
 * {@link WorldMetadata#detectSourceId(net.minecraft.client.Minecraft)},
 * e.g. {@code "server:play.example.com"} or {@code "local:MyWorld"}.
 * Values are sanitised folder names.
 * <p>
 * Two distinct base directories are recognised:
 * <ul>
 *   <li>{@code downloaded_worlds/} — the mod's own mirror store
 *       ({@link io.github.billstark001.worldmirror.config.ModConfig.SaveLocation#DOWNLOADED}).</li>
 *   <li>{@code saves/} — the vanilla saves directory
 *       ({@link io.github.billstark001.worldmirror.config.ModConfig.SaveLocation#SAVES}).</li>
 * </ul>
 * Resolved folder names are tracked <em>per save-location</em> (using compound keys of the
 * form {@code "sourceId\0saveLoc"}) so that changing the save-location for a world never
 * reuses a stale resolved name from the other directory.
 * <p>
 * Per-world save location and conflict strategy are stored as string maps (enum names).
 * A {@code null} value means "use the global config default".
 * <p>
 * All internal maps are {@code private}; callers must use the provided accessor methods.
 * The accessors validate that returned paths still exist and are still owned by the expected
 * source before handing them back, so callers do not need to re-check.
 */
public class MirrorMapping {

    // ── Persisted fields (Gson accesses private fields via reflection) ─────────

    /** Maps {@code sourceId} → mirror folder base name (never contains a generated suffix). */
    private Map<String, String> entries = new HashMap<>();

    /**
     * Maps a compound key {@code "sourceId\0saveLocationName"} → the collision-resolved
     * folder name that was actually used for that specific (source, location) combination.
     * Stored separately from {@code entries} so the base name is never contaminated with
     * numeric suffixes, and keyed by save-location so that switching between
     * {@code DOWNLOADED} and {@code SAVES} always triggers a fresh resolution scan in the
     * correct directory.
     */
    private Map<String, String> resolvedFolderNames = new HashMap<>();

    /**
     * Per-world save location override.
     * Key: {@code sourceId}; value: {@link io.github.billstark001.worldmirror.config.ModConfig.SaveLocation} name.
     * Absent or {@code null} → use global config.
     */
    private Map<String, String> perWorldSaveLocation = new HashMap<>();

    /**
     * Per-world conflict strategy override.
     * Key: {@code sourceId}; value: {@link io.github.billstark001.worldmirror.config.ModConfig.ConflictStrategy} name.
     * Absent or {@code null} → use global config.
     */
    private Map<String, String> perWorldConflictStrategy = new HashMap<>();

    // ── Persistence ───────────────────────────────────────────────────────────

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Characters that are NOT safe for folder names and will be replaced with underscores. */
    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9._\\-]");

    /** Separator used inside compound map keys (not valid in sourceId or location names). */
    private static final char KEY_SEP = '\0';

    private static volatile MirrorMapping instance;

    /** Returns the singleton, loading from disk lazily. */
    public static synchronized MirrorMapping getInstance() {
        if (instance == null) {
            instance = loadOrCreate();
        }
        return instance;
    }

    private static MirrorMapping loadOrCreate() {
        Path configDir = configDir();
        try {
            Files.createDirectories(configDir);
        } catch (Exception e) {
            WMLogger.warn("Mirror mapping directory creation failed path=" + configDir, e);
        }

        Path file = configDir.resolve("mirrors.json");
        if (file.toFile().exists()) {
            try (Reader r = new FileReader(file.toFile())) {
                MirrorMapping m = GSON.fromJson(r, MirrorMapping.class);
                if (m != null) {
                    if (m.entries == null) m.entries = new HashMap<>();
                    if (m.resolvedFolderNames == null) m.resolvedFolderNames = new HashMap<>();
                    if (m.perWorldSaveLocation == null) m.perWorldSaveLocation = new HashMap<>();
                    if (m.perWorldConflictStrategy == null) m.perWorldConflictStrategy = new HashMap<>();
                    return m;
                }
            } catch (Exception e) {
                WMLogger.warn("Mirror mapping load failed file=" + file, e);
            }
        }
        return new MirrorMapping();
    }

    /** Persists the current mapping to disk. */
    public synchronized void save() {
        Path configDir = configDir();
        Path file = configDir.resolve("mirrors.json");
        try {
            Files.createDirectories(configDir);
            try (Writer w = new FileWriter(file.toFile())) {
                GSON.toJson(this, w);
            }
        } catch (Exception e) {
            WMLogger.warn("Mirror mapping save failed file=" + file, e);
        }
    }

    // ── Query / update ────────────────────────────────────────────────────────

    /**
     * Returns the folder base name assigned to {@code sourceId}, auto-generating
     * (and persisting) a new name if none has been set yet.
     * Always returns the <em>base</em> name — never a suffixed collision-resolved name.
     */
    public synchronized String getMirrorFolderName(String sourceId) {
        String existing = entries.get(sourceId);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String generated = generateFolderName(sourceId);
        entries.put(sourceId, generated);
        save();
        return generated;
    }

    /**
     * Explicitly assigns a folder base name to a source ID (e.g. from a future UI).
     * Also clears all previously cached resolved names for this source so the next
     * call to {@link #getResolvedFolderName} re-runs the collision scan.
     */
    public synchronized void setMirrorFolderName(String sourceId, String folderName) {
        entries.put(sourceId, folderName);
        // Invalidate resolved names for every save-location since the base changed.
        resolvedFolderNames.entrySet().removeIf(e -> e.getKey().startsWith(sourceId + KEY_SEP));
        save();
    }

    // ── Resolved folder names (per save-location) ─────────────────────────────

    /**
     * Returns the previously persisted collision-resolved folder name for
     * {@code (sourceId, saveLocationName)}, but <em>only</em> if:
     * <ol>
     *   <li>such a value has been recorded, and</li>
     *   <li>the folder {@code base.resolve(resolvedName)} actually exists on disk, and</li>
     *   <li>the folder is still owned by {@code sourceId} (checked via
     *       {@link WorldMetadata#isOwnedBy}).</li>
     * </ol>
     * If any check fails the stale entry is removed and {@code null} is returned so
     * that the caller performs a fresh resolution scan.
     *
     * @param sourceId        the world's source identifier
     * @param saveLocationName the enum name of the save-location
     *                         ({@link io.github.billstark001.worldmirror.config.ModConfig.SaveLocation#name()})
     * @param base            the root directory that corresponds to {@code saveLocationName}
     * @return the validated resolved folder name, or {@code null}
     */
    public synchronized String getResolvedFolderName(String sourceId, String saveLocationName, Path base) {
        String key = sourceId + KEY_SEP + saveLocationName;
        String v = resolvedFolderNames.get(key);
        if (v == null || v.isBlank()) return null;

        // Validate: directory must exist and still belong to this source.
        Path candidate = base.resolve(v);
        if (!candidate.toFile().exists()) {
            WMLogger.debug("Resolved folder no longer exists, invalidating: " + candidate);
            resolvedFolderNames.remove(key);
            save();
            return null;
        }
        if (!WorldMetadata.isOwnedBy(candidate, sourceId)) {
            WMLogger.debug("Resolved folder ownership mismatch, invalidating: " + candidate);
            resolvedFolderNames.remove(key);
            save();
            return null;
        }
        return v;
    }

    /**
     * Persists the collision-resolved folder name for {@code (sourceId, saveLocationName)}.
     * The value must be the <em>actual</em> folder name that was chosen (may include a
     * numeric suffix).  It is stored under a compound key that includes the save-location
     * so that different directories never share the same cached name.
     *
     * @param sourceId        the world's source identifier
     * @param saveLocationName the enum name of the save-location
     * @param resolvedName    the folder name that was chosen (relative to the location root)
     */
    public synchronized void setResolvedFolderName(String sourceId, String saveLocationName,
                                      String resolvedName) {
        resolvedFolderNames.put(sourceId + KEY_SEP + saveLocationName, resolvedName);
        save();
    }

    /**
     * Removes all cached resolved folder names for {@code sourceId} across every
     * save-location.  Call this whenever a directory is known to have been deleted or
     * moved by the user so that the next resolution scan starts fresh.
     *
     * @param sourceId the world's source identifier
     */
    public synchronized void invalidateResolvedFolderNames(String sourceId) {
        boolean changed = resolvedFolderNames.entrySet()
                .removeIf(e -> e.getKey().startsWith(sourceId + KEY_SEP));
        if (changed) save();
    }

    // ── Per-world save location ───────────────────────────────────────────────

    /**
     * Returns the per-world save-location override for {@code sourceId},
     * or {@code null} if none has been set (meaning: use the global config).
     */
    public synchronized String getPerWorldSaveLocation(String sourceId) {
        String v = perWorldSaveLocation.get(sourceId);
        return (v != null && !v.isBlank()) ? v : null;
    }

    /**
     * Sets a per-world save-location override.
     * Pass {@code null} or an empty string to remove the override.
     * Also invalidates all cached resolved folder names for this source because the
     * effective base directory may have changed.
     */
    public synchronized void setPerWorldSaveLocation(String sourceId, String locationName) {
        if (locationName == null || locationName.isBlank()) {
            perWorldSaveLocation.remove(sourceId);
        } else {
            perWorldSaveLocation.put(sourceId, locationName);
        }
        // Invalidate resolved names — the base directory changed.
        invalidateResolvedFolderNames(sourceId);
        save();
    }

    // ── Per-world conflict strategy ───────────────────────────────────────────

    /**
     * Returns the per-world conflict-strategy override for {@code sourceId},
     * or {@code null} if none has been set (meaning: use the global config).
     */
    public synchronized String getPerWorldConflictStrategy(String sourceId) {
        String v = perWorldConflictStrategy.get(sourceId);
        return (v != null && !v.isBlank()) ? v : null;
    }

    /**
     * Sets a per-world conflict-strategy override.
     * Pass {@code null} or an empty string to remove the override.
     */
    public synchronized void setPerWorldConflictStrategy(String sourceId, String strategyName) {
        if (strategyName == null || strategyName.isBlank()) {
            perWorldConflictStrategy.remove(sourceId);
        } else {
            perWorldConflictStrategy.put(sourceId, strategyName);
        }
        save();
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static String generateFolderName(String sourceId) {
        if (sourceId == null || sourceId.isBlank() || "unknown".equals(sourceId)) {
            return "unknown_world";
        }
        // Strip type prefix so "server:play.example.com" → "play.example.com"
        String name = sourceId;
        if (sourceId.startsWith("server:")) {
            name = sourceId.substring("server:".length());
        } else if (sourceId.startsWith("local:")) {
            name = sourceId.substring("local:".length());
        }
        // Replace dots/colons with underscores, then replace remaining unsafe chars
        name = name.replace(':', '_').replace('/', '_');
        name = UNSAFE.matcher(name).replaceAll("_");
        // Collapse multiple underscores
        name = name.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        if (name.isBlank()) name = "unnamed";
        if (name.length() > 64) name = name.substring(0, 64);
        return name;
    }

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("worldmirror");
    }
}
