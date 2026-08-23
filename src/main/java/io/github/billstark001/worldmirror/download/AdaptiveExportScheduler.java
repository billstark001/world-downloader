package io.github.billstark001.worldmirror.download;

/** Stateful hysteresis and cooldown policy for automatic adaptive exports. */
final class AdaptiveExportScheduler {
    enum Decision {
        NONE,
        HIGH_WATERMARK,
        MAX_LATENCY
    }

    private boolean highWatermarkArmed = true;
    private long lastExportStartedMs;

    void reset(long nowMs) {
        highWatermarkArmed = true;
        lastExportStartedMs = nowMs;
    }

    Decision evaluate(long nowMs, int dirtyChunks, boolean hasDurabilityWork,
                      int highWatermark, long cooldownMs, long maximumLatencyMs) {
        int high = Math.max(1, highWatermark);
        int low = Math.max(1, high / 2);
        if (dirtyChunks <= low) highWatermarkArmed = true;
        if (!hasDurabilityWork) return Decision.NONE;

        long elapsed = Math.max(0L, nowMs - lastExportStartedMs);
        long critical = Math.min(Integer.MAX_VALUE, (long) high * 4L);
        boolean pressure = (highWatermarkArmed && dirtyChunks >= high)
                || dirtyChunks >= critical;
        if (pressure && elapsed >= cooldownMs) return Decision.HIGH_WATERMARK;
        if (elapsed >= maximumLatencyMs) return Decision.MAX_LATENCY;
        return Decision.NONE;
    }

    void onExportStarted(long nowMs, int dirtyChunks, int highWatermark) {
        lastExportStartedMs = nowMs;
        if (dirtyChunks >= Math.max(1, highWatermark)) highWatermarkArmed = false;
    }

    boolean maximumLatencyReached(long nowMs, long maximumLatencyMs) {
        return Math.max(0L, nowMs - lastExportStartedMs) >= maximumLatencyMs;
    }
}
