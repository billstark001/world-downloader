package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadPipelineTest {
    @Test
    void stablePipelineExportsOnlyAtMaximumLatency() {
        ModConfig config = config();
        DownloadPipeline pipeline = DownloadPipeline.create(
                ModConfig.DownloadPipelineMode.STABLE_PERIODIC);
        pipeline.reset(1_000L);

        assertEquals(DownloadPipeline.Decision.NONE,
                pipeline.evaluate(30_999L, 900, true, config));
        assertEquals(DownloadPipeline.Decision.PERIODIC,
                pipeline.evaluate(31_000L, 1, true, config));
        assertEquals(DownloadPipeline.Decision.NONE,
                pipeline.evaluate(60_000L, 0, false, config));
    }

    @Test
    void adaptivePipelineUsesHighWatermarkAndHysteresis() {
        ModConfig config = config();
        DownloadPipeline pipeline = DownloadPipeline.create(
                ModConfig.DownloadPipelineMode.EXPERIMENTAL_ADAPTIVE);
        pipeline.reset(1_000L);

        assertEquals(DownloadPipeline.Decision.HIGH_WATERMARK,
                pipeline.evaluate(11_000L, 512, true, config));
        pipeline.onExportStarted(11_000L, 512, config);
        assertEquals(DownloadPipeline.Decision.NONE,
                pipeline.evaluate(21_000L, 512, true, config));
        assertEquals(DownloadPipeline.Decision.NONE,
                pipeline.evaluate(21_000L, 256, true, config));
        assertEquals(DownloadPipeline.Decision.HIGH_WATERMARK,
                pipeline.evaluate(21_000L, 512, true, config));
    }

    @Test
    void adaptivePipelineKeepsMaximumLatencyCeiling() {
        ModConfig config = config();
        DownloadPipeline pipeline = DownloadPipeline.create(
                ModConfig.DownloadPipelineMode.EXPERIMENTAL_ADAPTIVE);
        pipeline.reset(5_000L);

        assertEquals(DownloadPipeline.Decision.NONE,
                pipeline.evaluate(34_999L, 1, true, config));
        assertEquals(DownloadPipeline.Decision.MAX_LATENCY,
                pipeline.evaluate(35_000L, 1, true, config));
    }

    private static ModConfig config() {
        ModConfig config = new ModConfig();
        config.syncIntervalSeconds = 30;
        config.performance.adaptiveDirtyHighWatermark = 512;
        config.performance.adaptiveExportCooldownSeconds = 10;
        return config;
    }
}
