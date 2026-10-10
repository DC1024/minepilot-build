package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RuntimeTickMetricsTest {
    @Test
    void reportsBoundedRollingP95AndLifetimeCounters() {
        final RuntimeTickMetrics metrics = new RuntimeTickMetrics(32);
        for (int index = 1; index <= 40; index++) {
            metrics.record(index * 100_000L);
        }

        final RuntimeTickMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(40, snapshot.lifetimeSamples());
        assertEquals(32, snapshot.windowSamples());
        assertEquals(4_000_000L, snapshot.windowMaximumNanos());
        assertEquals(3_900_000L, snapshot.windowP95Nanos());
        assertEquals(20, snapshot.lifetimeOverP95Target());
        assertFalse(snapshot.averageTargetMet());
        assertFalse(snapshot.p95TargetMet());
    }

    @Test
    void emptyAndFastWindowsMeetTargetsWithoutInventingSamples() {
        final RuntimeTickMetrics metrics = new RuntimeTickMetrics(32);
        final RuntimeTickMetrics.Snapshot empty = metrics.snapshot();
        assertEquals(0, empty.windowSamples());
        assertEquals(0, empty.windowP95Nanos());
        assertTrue(empty.averageTargetMet());
        assertTrue(empty.p95TargetMet());

        metrics.record(500_000L);
        assertTrue(metrics.snapshot().averageTargetMet());
        assertThrows(
            IllegalArgumentException.class,
            () -> metrics.record(-1)
        );
    }

    @Test
    void intervalMeasurementIgnoresEarlierSlowSamples() {
        final RuntimeTickMetrics metrics = new RuntimeTickMetrics(32);
        for (int index = 0; index < 40; index++) {
            metrics.record(4_000_000L);
        }
        final RuntimeTickMetrics.Cursor start = metrics.cursor();
        for (int index = 0; index < 40; index++) {
            metrics.record(500_000L);
        }

        final RuntimeTickMetrics.IntervalSnapshot interval =
            metrics.snapshotSince(start);
        assertEquals(40, interval.samples());
        assertEquals(32, interval.windowSamples());
        assertEquals(500_000.0, interval.averageNanos());
        assertEquals(500_000L, interval.windowP95Nanos());
        assertEquals(0, interval.overP95Target());
        assertTrue(interval.averageTargetMet());
        assertTrue(interval.p95TargetMet());
        assertFalse(metrics.snapshot().averageTargetMet());
    }

    @Test
    void intervalWindowContainsOnlySamplesAfterCursor() {
        final RuntimeTickMetrics metrics = new RuntimeTickMetrics(32);
        for (int index = 0; index < 31; index++) {
            metrics.record(4_000_000L);
        }
        final RuntimeTickMetrics.Cursor start = metrics.cursor();
        metrics.record(400_000L);
        metrics.record(600_000L);

        final RuntimeTickMetrics.IntervalSnapshot interval =
            metrics.snapshotSince(start);
        assertEquals(2, interval.samples());
        assertEquals(2, interval.windowSamples());
        assertEquals(600_000L, interval.windowMaximumNanos());
        assertEquals(600_000L, interval.windowP95Nanos());
    }
}
