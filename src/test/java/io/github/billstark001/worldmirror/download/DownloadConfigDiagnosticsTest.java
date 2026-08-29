package io.github.billstark001.worldmirror.download;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.billstark001.worldmirror.config.ModConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadConfigDiagnosticsTest {
    @Test
    void globalSnapshotIncludesEveryConfigurationGroup() {
        ModConfig config = new ModConfig();
        JsonObject json = JsonParser.parseString(
                DownloadConfigDiagnostics.globalJson(config)).getAsJsonObject();

        assertEquals(config.defaultSaveLocation.name(), json.get("defaultSaveLocation").getAsString());
        assertEquals(config.syncIntervalSeconds, json.get("syncIntervalSeconds").getAsInt());
        assertTrue(json.has("pipelineMode"));
        assertTrue(json.has("defaultConflictStrategy"));
        assertTrue(json.has("newWorldTime"));
        assertTrue(json.has("newWorldWeather"));
        assertTrue(json.has("newWorldDifficulty"));
        assertTrue(json.getAsJsonObject("cache").has("invalidateAfterExport"));
        assertTrue(json.getAsJsonObject("performance").has("diagnosticPerformanceLogging"));
        assertTrue(json.getAsJsonObject("chunkMap").has("xaeroWorldMapOverlayMaxCells"));
        assertTrue(json.getAsJsonObject("lifecycle").has("exportAllCachedOnStop"));
    }

    @Test
    void localSnapshotShowsOverridesAndEffectiveValuesSeparately() {
        ModConfig config = new ModConfig();
        JsonObject json = JsonParser.parseString(DownloadConfigDiagnostics.localJson(
                config, new MirrorMapping(), "server:example.test", "server"))
                .getAsJsonObject();

        assertTrue(json.get("saveLocationOverride").isJsonNull());
        assertEquals(config.defaultSaveLocation.name(),
                json.get("effectiveSaveLocation").getAsString());
        assertTrue(json.get("conflictStrategyOverride").isJsonNull());
        assertEquals(config.defaultConflictStrategy.name(),
                json.get("effectiveConflictStrategy").getAsString());
        assertEquals("example.test", json.get("mirrorBaseFolderName").getAsString());
        assertTrue(json.get("resolvedFolderName").isJsonNull());
    }
}
