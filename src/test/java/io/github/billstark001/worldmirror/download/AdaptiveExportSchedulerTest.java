package io.github.billstark001.worldmirror.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdaptiveExportSchedulerTest {
    @Test
    void highWatermarkRequiresCooldownAndLowWatermarkRearmsIt() {
        AdaptiveExportScheduler scheduler = new AdaptiveExportScheduler();
        scheduler.reset(0L);

        assertEquals(AdaptiveExportScheduler.Decision.NONE,
                scheduler.evaluate(9_999L, 512, true, 512, 10_000L, 30_000L));
        assertEquals(AdaptiveExportScheduler.Decision.HIGH_WATERMARK,
                scheduler.evaluate(10_000L, 512, true, 512, 10_000L, 30_000L));
        scheduler.onExportStarted(10_000L, 512, 512);

        assertEquals(AdaptiveExportScheduler.Decision.NONE,
                scheduler.evaluate(20_000L, 600, true, 512, 10_000L, 30_000L));
        assertEquals(AdaptiveExportScheduler.Decision.NONE,
                scheduler.evaluate(20_001L, 256, true, 512, 10_000L, 30_000L));
        assertEquals(AdaptiveExportScheduler.Decision.HIGH_WATERMARK,
                scheduler.evaluate(20_001L, 512, true, 512, 10_000L, 30_000L));
    }

    @Test
    void criticalPressureBypassesRearmButNotCooldown() {
        AdaptiveExportScheduler scheduler = new AdaptiveExportScheduler();
        scheduler.reset(0L);
        scheduler.onExportStarted(10_000L, 512, 512);

        assertEquals(AdaptiveExportScheduler.Decision.NONE,
                scheduler.evaluate(19_999L, 2048, true, 512, 10_000L, 30_000L));
        assertEquals(AdaptiveExportScheduler.Decision.HIGH_WATERMARK,
                scheduler.evaluate(20_000L, 2048, true, 512, 10_000L, 30_000L));
    }

    @Test
    void maximumLatencyFlushesWorkWithoutPressure() {
        AdaptiveExportScheduler scheduler = new AdaptiveExportScheduler();
        scheduler.reset(5_000L);

        assertEquals(AdaptiveExportScheduler.Decision.MAX_LATENCY,
                scheduler.evaluate(35_000L, 1, true, 512, 10_000L, 30_000L));
        assertEquals(AdaptiveExportScheduler.Decision.NONE,
                scheduler.evaluate(35_000L, 0, false, 512, 10_000L, 30_000L));
    }
}
