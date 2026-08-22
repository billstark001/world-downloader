package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.LegacyVoidChunkCleanup;
import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import io.github.billstark001.worldmirror.io.WorldStructureCreator;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.SharedConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Performs approved, offline mirror upgrades.  Export workers only inspect this
 * coordinator; they never invoke migration implicitly.
 */
public final class MirrorMigrationCoordinator {
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    public record Result(boolean success, boolean changed, Path backup, String failure) {
        static Result success(boolean changed, Path backup) {
            return new Result(true, changed, backup, null);
        }

        static Result failure(String failure) {
            return new Result(false, false, null, failure);
        }
    }

    public enum Phase {
        SCANNING_VOID_CHUNKS,
        BACKING_UP,
        WRITING_WORLD_DATA,
        REMOVING_VOID_CHUNKS
    }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = (phase, completed, total) -> {};

        void update(Phase phase, int completed, int total);
    }

    private MirrorMigrationCoordinator() {}

    public static MirrorMigrationPlan.Inspection inspect(Path worldFolder) {
        return MirrorMigrationPlan.inspect(worldFolder,
                SharedConstants.getCurrentVersion().dataVersion().version());
    }

    /**
     * Upgrades an existing mirror after explicit user confirmation.  The caller
     * must ensure the world is not loaded by an integrated server.
     */
    public static Result migrateApproved(Path worldFolder) {
        return migrateApproved(worldFolder, ProgressListener.NONE);
    }

    public static Result migrateApproved(Path worldFolder, ProgressListener progress) {
        Path normalized = worldFolder.toAbsolutePath().normalize();
        Object lock = LOCKS.computeIfAbsent(normalized, ignored -> new Object());
        synchronized (lock) {
            MirrorMigrationPlan.Inspection plan = inspect(normalized);
            if (plan.state() == MirrorMigrationPlan.State.CURRENT) {
                return Result.success(false, null);
            }
            if (plan.state() != MirrorMigrationPlan.State.OUTDATED || plan.metadata() == null) {
                return Result.failure("mirror_not_migratable:" + plan.state().name().toLowerCase());
            }

            LegacyVoidChunkCleanup.Plan voidCleanup;
            try {
                progress.update(Phase.SCANNING_VOID_CHUNKS, 0, 1);
                voidCleanup = plan.cleanupLegacyVoidChunks()
                        ? LegacyVoidChunkCleanup.plan(normalized,
                                (completed, total) -> progress.update(
                                        Phase.SCANNING_VOID_CHUNKS, completed, total))
                        : LegacyVoidChunkCleanup.Plan.empty();
            } catch (Exception e) {
                WMLogger.warn("Failed to scan legacy void chunks in " + normalized, e);
                return Result.failure("legacy_void_scan_failed");
            }

            Path backup;
            try {
                progress.update(Phase.BACKING_UP, 0, 1);
                backup = backupTouchedFiles(normalized, voidCleanup);
                progress.update(Phase.BACKING_UP, 1, 1);
            } catch (IOException e) {
                WMLogger.warn("Mirror migration backup failed world=" + normalized, e);
                return Result.failure("backup_failed");
            }

            progress.update(Phase.WRITING_WORLD_DATA, 0, 1);
            boolean created = WorldStructureCreator.createLoadableWorld(
                    normalized,
                    displayName(normalized, plan.metadata()),
                    plan.migrateWorldgen(),
                    plan.refreshAssets());
            if (!created) {
                return Result.failure("worldgen_write_failed");
            }
            progress.update(Phase.WRITING_WORLD_DATA, 1, 1);

            try {
                LegacyVoidChunkCleanup.apply(voidCleanup,
                        (completed, total) -> progress.update(
                                Phase.REMOVING_VOID_CHUNKS, completed, total));
            } catch (Exception e) {
                WMLogger.warn("Failed to clean legacy void chunks in " + normalized, e);
                return Result.failure("legacy_void_cleanup_failed");
            }

            // Commit the schema marker only after all mutable world files were
            // written successfully.  This is intentionally the final operation.
            WorldMetadata metadata = plan.metadata();
            metadata.ensureMirrorId();
            metadata.markWorldgenCurrent(
                    SharedConstants.getCurrentVersion().dataVersion().version(),
                    MirrorWorldgenAssets.ASSET_REVISION);
            metadata.save(normalized);
            return Result.success(true, backup);
        }
    }

    private static String displayName(Path worldFolder, WorldMetadata metadata) {
        Path name = worldFolder.getFileName();
        if (name != null && !name.toString().isBlank()) return name.toString();
        return metadata.sourceId;
    }

    /** Creates a compact, recoverable backup of every file this migration may change. */
    private static Path backupTouchedFiles(Path worldFolder, LegacyVoidChunkCleanup.Plan voidCleanup)
            throws IOException {
        Path backupRoot = worldFolder.resolve("backups");
        Files.createDirectories(backupRoot);
        String timestamp = Instant.now().toString().replace(':', '-');
        Path archive = backupRoot.resolve("worldmirror-schema-backup-" + timestamp + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            addFileIfPresent(zip, worldFolder, worldFolder.resolve("level.dat"));
            addFileIfPresent(zip, worldFolder, worldFolder.resolve(WorldMetadata.FILE_NAME));
            addFileIfPresent(zip, worldFolder,
                    worldFolder.resolve("data/minecraft/world_gen_settings.dat"));
            addTreeIfPresent(zip, worldFolder,
                    worldFolder.resolve("datapacks").resolve(MirrorWorldgenAssets.PACK_DIRECTORY));
            for (LegacyVoidChunkCleanup.RegionPlan region : voidCleanup.regions()) {
                addFileIfPresent(zip, worldFolder, region.regionFile());
            }
        }
        return archive;
    }

    private static void addTreeIfPresent(ZipOutputStream zip, Path root, Path tree) throws IOException {
        if (!Files.isDirectory(tree)) return;
        try (var files = Files.walk(tree)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                addFileIfPresent(zip, root, file);
            }
        }
    }

    private static void addFileIfPresent(ZipOutputStream zip, Path root, Path file) throws IOException {
        if (!Files.isRegularFile(file)) return;
        String name = root.relativize(file).toString().replace('\\', '/');
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(file, zip);
        zip.closeEntry();
    }
}
