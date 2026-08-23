package io.github.billstark001.worldmirror.download;

import io.github.billstark001.worldmirror.config.ModConfig;

/** Scheduling contract shared by every download durability strategy. */
interface DownloadPipeline {
    enum Decision {
        NONE,
        PERIODIC,
        HIGH_WATERMARK,
        MAX_LATENCY
    }

    ModConfig.DownloadPipelineMode mode();

    void reset(long nowMs);

    Decision evaluate(long nowMs, int dirtyChunks, boolean hasDurabilityWork,
                      ModConfig config);

    void onExportStarted(long nowMs, int dirtyChunks, ModConfig config);

    static DownloadPipeline create(ModConfig.DownloadPipelineMode mode) {
        return switch (mode) {
            case STABLE_PERIODIC -> new StablePeriodic();
            case EXPERIMENTAL_ADAPTIVE -> new Adaptive();
        };
    }

    /** Conservative fixed-maximum-latency strategy. */
    final class StablePeriodic implements DownloadPipeline {
        private long lastExportStartedMs;

        @Override
        public ModConfig.DownloadPipelineMode mode() {
            return ModConfig.DownloadPipelineMode.STABLE_PERIODIC;
        }

        @Override
        public void reset(long nowMs) {
            lastExportStartedMs = nowMs;
        }

        @Override
        public Decision evaluate(long nowMs, int dirtyChunks, boolean hasDurabilityWork,
                                 ModConfig config) {
            if (!hasDurabilityWork) return Decision.NONE;
            long maximumLatencyMs = (long) config.syncIntervalSeconds * 1_000L;
            return elapsed(nowMs, lastExportStartedMs) >= maximumLatencyMs
                    ? Decision.PERIODIC : Decision.NONE;
        }

        @Override
        public void onExportStarted(long nowMs, int dirtyChunks, ModConfig config) {
            lastExportStartedMs = nowMs;
        }
    }

    /** Event-pressure strategy with hysteresis, cooldown, and a latency ceiling. */
    final class Adaptive implements DownloadPipeline {
        private boolean highWatermarkArmed = true;
        private long lastExportStartedMs;

        @Override
        public ModConfig.DownloadPipelineMode mode() {
            return ModConfig.DownloadPipelineMode.EXPERIMENTAL_ADAPTIVE;
        }

        @Override
        public void reset(long nowMs) {
            highWatermarkArmed = true;
            lastExportStartedMs = nowMs;
        }

        @Override
        public Decision evaluate(long nowMs, int dirtyChunks, boolean hasDurabilityWork,
                                 ModConfig config) {
            int high = Math.max(1, config.performance.adaptiveDirtyHighWatermark);
            int low = Math.max(1, high / 2);
            if (dirtyChunks <= low) highWatermarkArmed = true;
            if (!hasDurabilityWork) return Decision.NONE;

            long elapsed = elapsed(nowMs, lastExportStartedMs);
            long critical = Math.min(Integer.MAX_VALUE, (long) high * 4L);
            boolean pressure = (highWatermarkArmed && dirtyChunks >= high)
                    || dirtyChunks >= critical;
            long cooldownMs = (long) config.performance.adaptiveExportCooldownSeconds * 1_000L;
            if (pressure && elapsed >= cooldownMs) return Decision.HIGH_WATERMARK;
            long maximumLatencyMs = (long) config.syncIntervalSeconds * 1_000L;
            if (elapsed >= maximumLatencyMs) return Decision.MAX_LATENCY;
            return Decision.NONE;
        }

        @Override
        public void onExportStarted(long nowMs, int dirtyChunks, ModConfig config) {
            lastExportStartedMs = nowMs;
            if (dirtyChunks >= Math.max(1, config.performance.adaptiveDirtyHighWatermark)) {
                highWatermarkArmed = false;
            }
        }
    }

    private static long elapsed(long nowMs, long sinceMs) {
        return Math.max(0L, nowMs - sinceMs);
    }
}
