package io.github.billstark001.worldmirror.download;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LatencyWindowTest {
    @Test
    void reportsNearestRankPercentilesAndResets() {
        LatencyWindow window = new LatencyWindow(200);
        for (int value = 1; value <= 100; value++) window.record(value);

        LatencyWindow.Summary summary = window.snapshotAndReset();

        assertEquals(100, summary.observations());
        assertEquals(100, summary.samples());
        assertEquals(50, summary.average());
        assertEquals(95, summary.p95());
        assertEquals(99, summary.p99());
        assertEquals(100, summary.maximum());
        assertEquals(LatencyWindow.Summary.empty(), window.snapshotAndReset());
    }

    @Test
    void remainsBoundedWhileRetainingObservationCount() {
        LatencyWindow window = new LatencyWindow(3);
        window.record(1);
        window.record(2);
        window.record(3);
        window.record(100);

        LatencyWindow.Summary summary = window.snapshotAndReset();

        assertEquals(4, summary.observations());
        assertEquals(3, summary.samples());
        assertEquals(100, summary.maximum());
    }
}
