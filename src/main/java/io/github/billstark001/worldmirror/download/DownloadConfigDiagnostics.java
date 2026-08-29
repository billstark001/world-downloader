package io.github.billstark001.worldmirror.download;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.billstark001.worldmirror.config.ModConfig;
import io.github.billstark001.worldmirror.util.WMLogger;
import net.minecraft.client.Minecraft;

/** Emits reproducible global and current-world configuration at diagnostic startup. */
final class DownloadConfigDiagnostics {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private DownloadConfigDiagnostics() { }

    static void log(Minecraft client) {
        ModConfig config = ModConfig.get();
        String sourceId = WorldMetadata.detectSourceId(client);
        String sourceType = WorldMetadata.detectSourceType(client);
        MirrorMapping mapping = MirrorMapping.getInstance();

        WMLogger.info("[perf] config.global=" + globalJson(config));
        WMLogger.info("[perf] config.local="
                + localJson(config, mapping, sourceId, sourceType));
    }

    static String globalJson(ModConfig config) {
        return GSON.toJson(config);
    }

    static String localJson(
            ModConfig config, MirrorMapping mapping, String sourceId, String sourceType) {
        String saveLocationOverride = mapping.getPerWorldSaveLocation(sourceId);
        ModConfig.SaveLocation effectiveSaveLocation =
                effectiveSaveLocation(config, saveLocationOverride);
        String conflictStrategyOverride = mapping.getPerWorldConflictStrategy(sourceId);
        ModConfig.ConflictStrategy effectiveConflictStrategy =
                effectiveConflictStrategy(config, conflictStrategyOverride);
        String resolvedFolderName = mapping.previewResolvedFolderName(
                sourceId, effectiveSaveLocation.name());

        return GSON.toJson(new LocalConfigSnapshot(
                sourceId,
                sourceType,
                saveLocationOverride,
                effectiveSaveLocation,
                conflictStrategyOverride,
                effectiveConflictStrategy,
                mapping.previewBaseFolderName(sourceId),
                resolvedFolderName));
    }

    private static ModConfig.ConflictStrategy effectiveConflictStrategy(
            ModConfig config, String override) {
        if (override != null) {
            try {
                return ModConfig.ConflictStrategy.valueOf(override);
            } catch (IllegalArgumentException ignored) {
                // Invalid persisted values fall back to the current global setting.
            }
        }
        return config.defaultConflictStrategy;
    }

    private static ModConfig.SaveLocation effectiveSaveLocation(
            ModConfig config, String override) {
        if (override != null) {
            try {
                return ModConfig.SaveLocation.valueOf(override);
            } catch (IllegalArgumentException ignored) {
                // Invalid persisted values fall back to the current global setting.
            }
        }
        return config.defaultSaveLocation;
    }

    private record LocalConfigSnapshot(
            String sourceId,
            String sourceType,
            String saveLocationOverride,
            ModConfig.SaveLocation effectiveSaveLocation,
            String conflictStrategyOverride,
            ModConfig.ConflictStrategy effectiveConflictStrategy,
            String mirrorBaseFolderName,
            String resolvedFolderName) { }
}
