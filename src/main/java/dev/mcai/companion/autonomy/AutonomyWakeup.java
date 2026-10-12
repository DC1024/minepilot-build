package dev.mcai.companion.autonomy;

import dev.mcai.companion.control.HaltReason;
import dev.mcai.companion.control.Hold;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Decides when an idle companion should spend a model call on a new
 * objective, and guarantees a spent call has something to work with.
 *
 * <p>The previous rule was a fixed interval: every 45 seconds, ask the model
 * for an objective, whether or not anything had changed. That was wrong in
 * both directions. A goal that failed one tick ago left the companion
 * standing still until the timer expired. A world where nothing had changed
 * produced the same question and the same answer, paid for again.</p>
 *
 * <p>This class answers "is there something to decide?" instead of "has
 * enough time passed?". Two properties make it safe:</p>
 *
 * <ol>
 *   <li><b>Signals accumulate and are taken together.</b> Signals arrive on
 *       the tick but are consumed only when a decision is actually made, so
 *       a burst costs one call rather than one call per signal.</li>
 *   <li><b>Ripeness and consumption read the same state.</b> {@link
 *       #ripeness} and {@link #consume} inspect the same pending fields.
 *       {@code consume} returns a ripe level exactly when {@code ripeness}
 *       reported one for the same input, so a ripe wake always has something
 *       to take. The "empty run" — waking, spending a call, and finding no
 *       work — cannot be constructed.</li>
 * </ol>
 *
 * <p>Only holds autonomy can actually clear produce a signal. A dead body,
 * an owner's stop or a missing endpoint also leave the companion idle, but
 * the driver would be refused on every subsequent tick, so counting them
 * would manufacture precisely the wasted call this class removes.</p>
 *
 * <h2>The retry path</h2>
 *
 * <p>A consumed signal is a spent budget, not a solved problem: the model
 * call it triggered may fail, or return an objective the coordinator
 * rejects. {@link #observe} therefore treats a rest that is <em>still</em>
 * in place after its wake as a new signal — but only once
 * {@link Ripeness#maxWaitMillisOf} has elapsed since that wake. The result
 * is a bounded retry cadence rather than a hot loop.</p>
 *
 * <p>Two earlier drafts of this logic were wrong in instructive ways, and
 * the shape below is deliberately chosen to avoid both:</p>
 * <ul>
 *   <li>A latch that survived {@code consume} permanently silenced the
 *       companion after one failed wake — the exact field failure this
 *       driver exists to fix.</li>
 *   <li>Clearing the latch unconditionally on {@code consume} let the very
 *       next tick emit a fresh signal, producing a wake every other tick.</li>
 * </ul>
 * <p>Keeping the rest identity separate from the time it was paid for
 * handles both: the identity prevents per-tick accumulation, and the
 * paid-at timestamp supplies the pause.</p>
 *
 * <p>Not thread-safe by design: the server tick is the only caller, and the
 * state is a few counters and two timestamps.</p>
 */
public final class AutonomyWakeup {
    /**
     * How many independent non-urgent signals justify one call.
     *
     * <p>Two: one observed change is a moment, two is a pattern.</p>
     */
    public static final int ENOUGH_SIGNALS = 2;

    /** Signals urgent enough that batching them would be visible as a freeze. */
    private static final Set<Signal> URGENT_SIGNALS = Set.of(
            Signal.OBJECTIVE_FAILED,
            Signal.OBJECTIVE_COMPLETED,
            Signal.HOLD_CLEARED
    );

    private final int wakeLevel;

    /** Signals arrived since the last consume; never reset by ripeness. */
    private int pendingCount;

    /** Whether any pending signal is individually urgent. */
    private boolean pendingUrgent;

    /**
     * The rest the companion is currently in, used to collapse the many
     * ticks of one rest into a single signal. {@code null} when she is
     * working or held by something autonomy cannot clear.
     */
    private Signal restIdentity;

    /**
     * When the current rest was last paid for by a consuming wake, or
     * {@code -1} if it has not been. A still-present rest only re-signals
     * after {@link Ripeness#maxWaitMillisOf} has elapsed from this instant.
     */
    private long restPaidAtMillis = -1L;

    /** Timestamp of the oldest pending signal; {@code -1} when none. */
    private long oldestPendingMillis = -1L;

    /** Timestamp of the last wake that actually consumed its signals. */
    private long lastConsumedMillis = -1L;

    /**
     * @param wakeLevel configured wake level, clamped to
     *                  {@code [1, Ripeness.MAX_LEVEL]} because it indexes
     *                  the wait-time curve
     */
    public AutonomyWakeup(final int wakeLevel) {
        this.wakeLevel = Math.max(
                1,
                Math.min(Ripeness.MAX_LEVEL, wakeLevel)
        );
    }

    public int wakeLevel() {
        return wakeLevel;
    }

    /**
     * Records where the companion currently is.
     *
     * <p>A signal is emitted on the <em>transition</em> into a clearable
     * rest, then again only after that rest has been paid for and the
     * retry interval has elapsed. A companion who finishes one objective
     * and idles for a minute emits one signal; one whose autonomous wake
     * fails three times emits four over the same period.</p>
     *
     * @param reason    stop reason for the current goal, if one is known
     * @param hold      the hold the companion is in, if she is in one
     * @param nowMillis current time on the same clock as {@link #ripeness}
     */
    public void observe(
            final Optional<HaltReason> reason,
            final Optional<Hold> hold,
            final long nowMillis
    ) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(hold, "hold");
        if (hold.filter(Hold::selfClearing).isEmpty()) {
            /*
             * A live goal, or a hold autonomy cannot clear. Either way the
             * driver has nothing it may act on. Clearing the rest identity
             * means the next genuine rest still registers as a change.
             */
            restIdentity = null;
            restPaidAtMillis = -1L;
            return;
        }
        final Signal identity = restingSignal(reason);
        if (identity != restIdentity) {
            /*
             * The companion just arrived in a new rest. This is the one tick
             * that counts as an event; every later tick of the same rest is
             * the same situation observed again, not a new reason to wake.
             */
            restIdentity = identity;
            restPaidAtMillis = -1L;
            record(identity, nowMillis);
            return;
        }
        if (restPaidAtMillis < 0L) {
            /*
             * This rest is already represented by a pending signal that no
             * wake has consumed yet. Counting it again would make the batch
             * threshold meaningless and report a companion who has been
             * waiting as though she had just arrived.
             */
            return;
        }
        /*
         * This rest has already produced one wake. Re-signal only after the
         * retry interval, so a wake that failed to install an objective is
         * retried at the configured cadence rather than every tick.
         */
        if (nowMillis - restPaidAtMillis
                < Ripeness.maxWaitMillisOf(wakeLevel)) {
            return;
        }
        restPaidAtMillis = -1L;
        record(identity, nowMillis);
    }

    /**
     * Records a signal that is not attached to a settled stop reason, for
     * example an idle tick that merely elapsed. Consecutive recordings of
     * the same signal collapse into one, exactly as a repeated rest does.
     */
    public void signal(final Signal signal, final long nowMillis) {
        Objects.requireNonNull(signal, "signal");
        if (signal == restIdentity) {
            return;
        }
        restIdentity = signal;
        restPaidAtMillis = -1L;
        record(signal, nowMillis);
    }

    private void record(final Signal signal, final long nowMillis) {
        if (URGENT_SIGNALS.contains(signal)) {
            pendingUrgent = true;
        }
        pendingCount++;
        if (oldestPendingMillis < 0L) {
            oldestPendingMillis = nowMillis;
        }
    }

    /**
     * How ready the pending work is.
     *
     * <p>Pure: this neither consumes anything nor advances a timestamp, so
     * it is safe to call every tick for status reporting.</p>
     *
     * @param nowMillis current time on the same clock as {@link #observe}
     */
    public Ripeness ripeness(final long nowMillis) {
        if (pendingCount <= 0) {
            return Ripeness.NOT_YET;
        }
        if (pendingUrgent) {
            return Ripeness.URGENT;
        }
        if (pendingCount >= ENOUGH_SIGNALS) {
            return Ripeness.ENOUGH;
        }
        if (oldestPendingMillis >= 0L
                && nowMillis - oldestPendingMillis
                        >= Ripeness.maxWaitMillisOf(wakeLevel)) {
            return Ripeness.LONG_ENOUGH;
        }
        return Ripeness.NOT_YET;
    }

    /**
     * Whether the companion may spend a model call now.
     *
     * <p>A thin wrapper over {@link #ripeness} so the two can never
     * disagree.</p>
     */
    public boolean isRipe(final long nowMillis) {
        return ripeness(nowMillis).atLeast(Ripeness.ENOUGH);
    }

    /**
     * Takes every pending signal, returning the level that justified the
     * take.
     *
     * <p>This is the contract that removes empty runs: it returns a ripe
     * level exactly when {@link #ripeness} reported one for the same input.
     * A caller that checks {@link #isRipe} and then calls this therefore
     * always receives the signals it was promised.</p>
     *
     * @param nowMillis current time on the same clock as {@link #observe}
     * @return the level consumed, or {@link Ripeness#NOT_YET} if not ripe
     */
    public Ripeness consume(final long nowMillis) {
        final Ripeness ready = ripeness(nowMillis);
        if (!ready.atLeast(Ripeness.ENOUGH)) {
            return Ripeness.NOT_YET;
        }
        pendingCount = 0;
        pendingUrgent = false;
        oldestPendingMillis = -1L;
        if (restIdentity != null) {
            /*
             * Mark the current rest as paid for. If it is still her
             * situation later, {@link #observe} will re-signal it after the
             * retry interval instead of on the next tick.
             */
            restPaidAtMillis = nowMillis;
        }
        lastConsumedMillis = nowMillis;
        return ready;
    }

    /** Pending signal count, for status reporting and tests. */
    public int pendingSignals() {
        return pendingCount;
    }

    /** Timestamp of the last consuming wake, or {@code -1} if none yet. */
    public long lastConsumedMillis() {
        return lastConsumedMillis;
    }

    private static Signal restingSignal(
            final Optional<HaltReason> reason
    ) {
        final HaltReason value = reason.orElse(null);
        if (value == null) {
            return Signal.HOLD_CLEARED;
        }
        return switch (value.status()) {
            case COMPLETED -> Signal.OBJECTIVE_COMPLETED;
            case FAILED, SAFE_IDLE -> Signal.OBJECTIVE_FAILED;
            /*
             * IDLE, RUNNING and CANCEL_PENDING cannot reach here: a
             * self-clearing hold is only produced by a terminal status.
             */
            default -> Signal.HOLD_CLEARED;
        };
    }

    /**
     * Reasons the companion might want a new objective. Kept small and
     * explicit: a signal nobody can name is a signal nobody can prioritise.
     */
    public enum Signal {
        /** The previous objective reached its target. */
        OBJECTIVE_COMPLETED,
        /** The previous objective gave up or rested. */
        OBJECTIVE_FAILED,
        /** A hold was released without a settled reason. */
        HOLD_CLEARED,
        /** A non-urgent tick while idle; batched until ENOUGH. */
        IDLE_TICK
    }
}
