package dev.mcai.companion.autonomy;

/**
 * How ready the companion is to spend a model call on a new objective.
 *
 * <p>Looking at the world is expensive for an agent and free for a human
 * watching her. The old driver answered that cost with a fixed timer: every
 * 45 seconds, ask the model for an objective, whether or not anything had
 * changed. That is wrong in both directions. When the previous objective has
 * just failed, 45 seconds of standing still is 45 seconds of visibly broken
 * behaviour. When nothing has changed at all, the same question produces the
 * same answer and the call was wasted.</p>
 *
 * <p>Ripeness replaces "has enough time passed?" with "is there something to
 * decide?". It is a pure function of the pending signals and the clock, so it
 * can be tested without a runtime, and — more importantly — it is the exact
 * same rule the consumer uses. Because ripeness and consumption read one
 * declaration, a ripe wake always has something to take: an empty run cannot
 * be constructed.</p>
 *
 * <p>The three levels are ordered. {@link #URGENT} outranks {@link #ENOUGH},
 * which outranks {@link #LONG_ENOUGH}, which outranks {@link #NOT_YET}.</p>
 */
public enum Ripeness {
    /**
     * A pending signal cannot wait: it describes a state the companion is
     * already in and must react to. Nothing is gained by batching it.
     */
    URGENT(3),

    /**
     * Enough has accumulated to justify a look, even though no single
     * pending item is urgent. This is the batch-drain case.
     */
    ENOUGH(2),

    /**
     * Nothing urgent and not enough accumulated, but the oldest pending
     * signal has now waited longer than the configured budget. This is the
     * floor that keeps a quiet world from starving her of decisions.
     */
    LONG_ENOUGH(1),

    /**
     * Not ripe. The caller must not spend a model call, and must not consume
     * the pending signals either — they are still accumulating.
     */
    NOT_YET(0);

    /**
     * The widest level the wait-time curve is defined for.
     *
     * <p>Beyond this the derived wait exceeds the point where a player would
     * reasonably conclude the companion has stopped working, so the level is
     * clamped rather than extrapolated.</p>
     */
    public static final int MAX_LEVEL = 10;

    /** One second, in milliseconds; the curve is expressed in milliseconds. */
    private static final long MILLIS_PER_SECOND = 1_000L;

    private static final long MILLIS_PER_MINUTE = 60L * MILLIS_PER_SECOND;

    private final int rank;

    Ripeness(final int rank) {
        this.rank = rank;
    }

    /**
     * Ordering rank, so callers can compare levels without a switch.
     */
    public int rank() {
        return rank;
    }

    public boolean atLeast(final Ripeness other) {
        return rank >= other.rank;
    }

    /**
     * The longest a pending signal may wait at the given wake level before
     * {@link #LONG_ENOUGH} fires.
     *
     * <p>The curve is quadratic on purpose. Level 1 waits 20 seconds — about
     * as often as a player expects a teammate to reconsider. Level 3 waits 3
     * minutes. Level 10 waits 33 minutes, which is a background liveness
     * check rather than a working cadence. Squaring the level means the
     * configured number has a usable meaning across its whole range instead
     * of only near the bottom.</p>
     *
     * <p>Values: 1 → 20 s, 2 → 80 s, 3 → 3 min, 5 → 8 min 20 s,
     * 10 → 33 min 20 s.</p>
     *
     * @param level configured wake level; clamped to
     *              {@code [1, MAX_LEVEL]}
     */
    public static long maxWaitMillisOf(final int level) {
        final int clamped = Math.max(1, Math.min(MAX_LEVEL, level));
        /*
         * Quadratic growth at level 10 reaches 100 million, far inside a
         * long, so the multiply cannot overflow for any clamped input.
         */
        return (MILLIS_PER_MINUTE * clamped * clamped) / 3L;
    }
}
