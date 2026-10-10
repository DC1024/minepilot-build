package dev.mcai.companion.runtime;

import java.util.Arrays;

/**
 * Allocation-free rolling measurements for the companion's server-tick work.
 *
 * <p>The server thread is the only writer. Snapshot creation is intentionally
 * infrequent (status/audit requests) and copies the bounded ring so percentile
 * calculation never delays the 20 TPS path.</p>
 */
public final class RuntimeTickMetrics {
    public static final int DEFAULT_WINDOW_SIZE = 4_096;
    public static final long TARGET_AVERAGE_NANOS = 1_000_000L;
    public static final long TARGET_P95_NANOS = 2_000_000L;

    private final long[] samples;
    private int nextIndex;
    private int sampleCount;
    private long lifetimeSamples;
    private long lifetimeTotalNanos;
    private long lifetimeMaximumNanos;
    private long lifetimeOverP95Target;

    public RuntimeTickMetrics() {
        this(DEFAULT_WINDOW_SIZE);
    }

    public RuntimeTickMetrics(final int windowSize) {
        if (windowSize < 32 || windowSize > 65_536) {
            throw new IllegalArgumentException(
                "Runtime metric window is outside its bound"
            );
        }
        samples = new long[windowSize];
    }

    public synchronized void record(final long elapsedNanos) {
        if (elapsedNanos < 0) {
            throw new IllegalArgumentException(
                "Runtime tick duration must be non-negative"
            );
        }
        samples[nextIndex] = elapsedNanos;
        nextIndex = (nextIndex + 1) % samples.length;
        if (sampleCount < samples.length) {
            sampleCount++;
        }
        lifetimeSamples++;
        lifetimeTotalNanos = saturatedAdd(
            lifetimeTotalNanos,
            elapsedNanos
        );
        lifetimeMaximumNanos = Math.max(
            lifetimeMaximumNanos,
            elapsedNanos
        );
        if (elapsedNanos > TARGET_P95_NANOS) {
            lifetimeOverP95Target++;
        }
    }

    public synchronized Snapshot snapshot() {
        final long[] window = Arrays.copyOf(samples, sampleCount);
        Arrays.sort(window);
        final long p95 = percentile(window, 0.95);
        final long windowMaximum = window.length == 0
            ? 0
            : window[window.length - 1];
        final double average = lifetimeSamples == 0
            ? 0.0
            : (double) lifetimeTotalNanos / lifetimeSamples;
        return new Snapshot(
            lifetimeSamples,
            sampleCount,
            average,
            p95,
            windowMaximum,
            lifetimeMaximumNanos,
            lifetimeOverP95Target,
            average <= TARGET_AVERAGE_NANOS,
            p95 <= TARGET_P95_NANOS
        );
    }

    /**
     * Captures a constant-size boundary for a later interval measurement.
     *
     * <p>This is useful for scenario and operational audits that share one
     * long-lived server runtime. Comparing lifetime averages makes the result
     * depend on whatever ran before the audited interval.</p>
     */
    public synchronized Cursor cursor() {
        return new Cursor(
            lifetimeSamples,
            lifetimeTotalNanos,
            lifetimeOverP95Target
        );
    }

    /**
     * Measures only samples recorded after {@code cursor}.
     *
     * <p>The average and over-target counter cover the complete interval. The
     * percentile covers the most recent bounded window inside that interval,
     * matching the runtime's ordinary rolling-p95 contract.</p>
     */
    public synchronized IntervalSnapshot snapshotSince(
            final Cursor cursor
    ) {
        if (cursor == null) {
            throw new NullPointerException("cursor");
        }
        if (cursor.lifetimeSamples() > lifetimeSamples
                || cursor.lifetimeTotalNanos() > lifetimeTotalNanos
                || cursor.lifetimeOverP95Target()
                    > lifetimeOverP95Target) {
            throw new IllegalArgumentException(
                "Runtime metric cursor belongs to a future or reset stream"
            );
        }
        final long intervalSamples =
            lifetimeSamples - cursor.lifetimeSamples();
        final long intervalTotalNanos =
            lifetimeTotalNanos - cursor.lifetimeTotalNanos();
        final long intervalOverP95Target =
            lifetimeOverP95Target
                - cursor.lifetimeOverP95Target();
        final int intervalWindowSamples = (int) Math.min(
            intervalSamples,
            sampleCount
        );
        final long[] window = newestSamples(intervalWindowSamples);
        Arrays.sort(window);
        final long p95 = percentile(window, 0.95);
        final long windowMaximum = window.length == 0
            ? 0
            : window[window.length - 1];
        final double average = intervalSamples == 0
            ? 0.0
            : (double) intervalTotalNanos / intervalSamples;
        return new IntervalSnapshot(
            intervalSamples,
            intervalWindowSamples,
            average,
            p95,
            windowMaximum,
            intervalOverP95Target,
            average <= TARGET_AVERAGE_NANOS,
            p95 <= TARGET_P95_NANOS
        );
    }

    private long[] newestSamples(final int count) {
        final long[] newest = new long[count];
        if (count == 0) {
            return newest;
        }
        int source = Math.floorMod(nextIndex - count, samples.length);
        for (int index = 0; index < count; index++) {
            newest[index] = samples[source];
            source = (source + 1) % samples.length;
        }
        return newest;
    }

    private static long percentile(
        final long[] sorted,
        final double fraction
    ) {
        if (sorted.length == 0) {
            return 0;
        }
        final int index = Math.min(
            sorted.length - 1,
            Math.max(
                0,
                (int) Math.ceil(sorted.length * fraction) - 1
            )
        );
        return sorted[index];
    }

    private static long saturatedAdd(
        final long left,
        final long right
    ) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public record Snapshot(
        long lifetimeSamples,
        int windowSamples,
        double lifetimeAverageNanos,
        long windowP95Nanos,
        long windowMaximumNanos,
        long lifetimeMaximumNanos,
        long lifetimeOverP95Target,
        boolean averageTargetMet,
        boolean p95TargetMet
    ) {
    }

    public record Cursor(
        long lifetimeSamples,
        long lifetimeTotalNanos,
        long lifetimeOverP95Target
    ) {
    }

    public record IntervalSnapshot(
        long samples,
        int windowSamples,
        double averageNanos,
        long windowP95Nanos,
        long windowMaximumNanos,
        long overP95Target,
        boolean averageTargetMet,
        boolean p95TargetMet
    ) {
    }
}
