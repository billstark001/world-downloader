package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.io.MirrorWorldgenAssets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorWorldContextTest {

    @Test
    void recognizesLegacyMetadataWithoutAWorldgenSchema(@TempDir Path world) throws Exception {
        Files.writeString(world.resolve(WorldMetadata.FILE_NAME), """
                { "sourceId": "server:example.test", "sourceType": "server" }
                """);

        assertEquals(MirrorWorldContext.State.OUTDATED, MirrorWorldContext.inspect(world, 100).state());
    }

    @Test
    void rejectsNewerSchemasInsteadOfDowngradingThem(@TempDir Path world) {
        WorldMetadata metadata = metadata();
        metadata.worldgenSchema = WorldMetadata.CURRENT_WORLDGEN_SCHEMA + 1;
        metadata.worldgenAssetRevision = MirrorWorldgenAssets.ASSET_REVISION + 1;
        metadata.worldgenAssetDataVersion = 101;
        metadata.save(world);

        assertEquals(MirrorWorldContext.State.FUTURE, MirrorWorldContext.inspect(world, 100).state());
        assertTrue(metadata.hasFutureWorldgenSchema());
        assertTrue(metadata.hasFutureWorldgenAssets(100, MirrorWorldgenAssets.ASSET_REVISION));
        assertFalse(metadata.needsWorldgenAssetRefresh(100, MirrorWorldgenAssets.ASSET_REVISION));
    }

    @Test
    void classifiesFreshAndUnmanagedFoldersSeparately(@TempDir Path root) throws Exception {
        Path fresh = root.resolve("fresh");
        Files.createDirectories(fresh);
        assertEquals(MirrorMigrationPlan.State.NEW, MirrorMigrationPlan.inspect(fresh, 100).state());

        Path unmanaged = root.resolve("unmanaged");
        Files.createDirectories(unmanaged);
        Files.writeString(unmanaged.resolve("level.dat"), "not a real level file");
        assertEquals(MirrorMigrationPlan.State.UNMANAGED,
                MirrorMigrationPlan.inspect(unmanaged, 100).state());
    }

    @Test
    void schedulesTheOneTimeVoidCleanupWithoutChangingSchemaOne(@TempDir Path world) {
        WorldMetadata metadata = metadata();
        metadata.worldgenSchema = WorldMetadata.CURRENT_WORLDGEN_SCHEMA;
        metadata.worldgenAssetRevision = MirrorWorldgenAssets.ASSET_REVISION;
        metadata.worldgenAssetDataVersion = 100;
        metadata.save(world);

        MirrorMigrationPlan.Inspection pending = MirrorMigrationPlan.inspect(world, 100);
        assertEquals(MirrorMigrationPlan.State.OUTDATED, pending.state());
        assertTrue(pending.cleanupLegacyVoidChunks());

        metadata.legacyVoidChunkCleanupRevision = WorldMetadata.CURRENT_VOID_CHUNK_CLEANUP_REVISION;
        metadata.save(world);
        assertEquals(MirrorMigrationPlan.State.CURRENT, MirrorMigrationPlan.inspect(world, 100).state());
    }

    @Test
    void nearbyExportLineageKeepsTheOriginalSourceOrCreatesAStableDerivedIdentity() {
        WorldMetadata current = metadata();
        current.mirrorId = "mirror-id";

        NearbyExporter.Lineage inherited = NearbyExporter.resolveLineage(
                NearbyExporter.Choice.INHERIT_ORIGINAL, current, "fallback", "server");
        assertEquals("server:example.test", inherited.sourceId());
        assertEquals("mirror-id", inherited.parentMirrorId());

        NearbyExporter.Lineage derived = NearbyExporter.resolveLineage(
                NearbyExporter.Choice.CURRENT_MIRROR, current, "fallback", "server");
        assertEquals("mirror:mirror-id", derived.sourceId());
        assertEquals("mirror", derived.sourceType());
    }

    private static WorldMetadata metadata() {
        WorldMetadata metadata = new WorldMetadata();
        metadata.sourceId = "server:example.test";
        metadata.sourceType = "server";
        return metadata;
    }
}
