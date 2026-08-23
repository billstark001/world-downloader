package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Read-only classification of a mirror save before an operation may write it.
 * This deliberately contains no client or UI API so it can be shared by the
 * download path, world-open hook, and tests.
 */
public final class MirrorMigrationPlan {
    public enum State {
        NEW,
        CURRENT,
        OUTDATED,
        FUTURE,
        UNREADABLE,
        UNMANAGED
    }

    public record Inspection(Path worldFolder, State state, WorldMetadata metadata,
                             boolean migrateWorldgen, boolean refreshAssets,
                             boolean cleanupLegacyVoidChunks) {
        public boolean requiresApproval() {
            return state == State.OUTDATED;
        }

        public boolean mayCreateOrWriteWithoutMigration() {
            return state == State.NEW || state == State.CURRENT;
        }
    }

    private MirrorMigrationPlan() {}

    public static Inspection inspect(Path worldFolder, int dataVersion) {
        Path normalized = worldFolder.toAbsolutePath().normalize();
        Path metadataFile = normalized.resolve(WorldMetadata.FILE_NAME);
        boolean hasLevelDat = Files.isRegularFile(normalized.resolve("level.dat"));
        if (!Files.isRegularFile(metadataFile)) {
            return new Inspection(normalized, hasLevelDat ? State.UNMANAGED : State.NEW,
                    null, false, false, false);
        }

        Optional<WorldMetadata> loaded = WorldMetadata.loadIfPresent(normalized);
        if (loaded.isEmpty()) {
            return new Inspection(normalized, State.UNREADABLE, null, false, false, false);
        }
        WorldMetadata metadata = loaded.get();
        if (metadata.sourceId == null || metadata.sourceId.isBlank()) {
            return new Inspection(normalized, State.UNREADABLE, metadata, false, false, false);
        }
        if (metadata.hasFutureWorldgenSchema()
                || metadata.hasFutureWorldgenAssets(dataVersion, MirrorWorldgenAssets.ASSET_REVISION)) {
            return new Inspection(normalized, State.FUTURE, metadata, false, false, false);
        }
        boolean migrateWorldgen = metadata.needsWorldgenMigration();
        boolean refreshAssets = metadata.needsWorldgenAssetRefresh(
                dataVersion, MirrorWorldgenAssets.ASSET_REVISION);
        boolean cleanupLegacyVoidChunks = metadata.needsLegacyVoidChunkCleanup();
        return new Inspection(normalized,
                migrateWorldgen || refreshAssets || cleanupLegacyVoidChunks
                        ? State.OUTDATED : State.CURRENT,
                metadata, migrateWorldgen, refreshAssets, cleanupLegacyVoidChunks);
    }
}
