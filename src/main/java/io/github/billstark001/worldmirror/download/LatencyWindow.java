package io.github.billstark001.worldmirror.download;

import java.util.Arrays;

/** Bounded recent-latency window used only by optional performance diagnostics. */
final class LatencyWindow {
    record Summary(long observations, int samples, long average, long p95, long p99, long maximum) {
        static Summary empty() {
            return new Summary(0L, 0, 0L, 0L, 0L, 0L);
        }
    }

    private final long[] values;
    private int size;
    private int next;
    private long observations;

    LatencyWindow(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        values = new long[capacity];
    }

    synchronized void record(long value) {
        values[next] = Math.max(0L, value);
        next = (next + 1) % values.length;
        if (size < values.length) size++;
        observations++;
    }

    synchronized Summary snapshotAndReset() {
        if (size == 0) {
            observations = 0L;
            return Summary.empty();
        }
        long[] sample = Arrays.copyOf(values, size);
        Arrays.sort(sample);
        long sum = 0L;
        for (long value : sample) sum += value;
        Summary summary = new Summary(
                observations,
                sample.length,
                sum / sample.length,
                percentile(sample, 0.95),
                percentile(sample, 0.99),
                sample[sample.length - 1]);
        size = 0;
        next = 0;
        observations = 0L;
        return summary;
    }

    synchronized void reset() {
        size = 0;
        next = 0;
        observations = 0L;
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.length) - 1);
        return sorted[index];
    }
}
