package dev.mcai.companion.brain;

import dev.mcai.companion.control.GoalExecutionPlan;
import java.util.Objects;
import java.util.Optional;

/**
 * Why the companion is about to spend a high-level planning call.
 *
 * <p>A planner call is the most expensive thing the companion does. The
 * reference implementation this project measures against answered the
 * question "when should I look again?" with an explicit table of triggers,
 * and drove its call count from 78 to 8 — then back up to 35, which it
 * reports as the new balance point. The lesson is not "call less"; it is
 * that every call should have a nameable reason, and that some reasons are
 * worth a call while others are not.</p>
 *
 * <p>{@code PlannerTrigger} is that table. It is a pure function of the
 * observable control-plane state — goal, milestone, dimension, failure
 * counters and elapsed time — and it has no side effects, so the same
 * evaluation can be replayed in a test without a gateway, a world or a
 * model.</p>
 *
 * <h2>What this class does not do</h2>
 *
 * <p>It does not gate anything yet. Turning the table into a hard gate would
 * suppress calls whose only current replacement is nothing, and the
 * companion would stand still — the failure mode this project has already
 * paid for twice. The table is therefore shipped as the analysis and
 * observability layer: the orchestrator classifies every call it makes and
 * emits the name, so the distribution becomes measurable before anything is
 * removed. Narrowing the trigger set is a follow-up that needs that data,
 * not a guess.</p>
 *
 * <p>{@link #REPEATED_FAILURE} shows why measuring first matters: the
 * reference reduces calls after two errors, but this companion's own retry
 * policy already escalates through {@code repeated_skill_failure_without_progress}
 * and gives up after three. A second, independent throttle would be
 * redundant, so the trigger is reported rather than acted on.</p>
 */
public enum PlannerTrigger {
    /**
     * No plan is installed for the current goal yet. Always worth a call:
     * there is nothing to continue.
     */
    INITIAL("initial"),

    /**
     * The model responded but committed to no action, so the body has no
     * work. Worth a call, with the existing correction attached.
     */
    NO_USEFUL_ACTIONS("no_useful_actions"),

    /**
     * The world dimension changed — a portal transition, a respawn, a
     * teleport the server observed. The previous plan refers to a place the
     * companion is no longer in, so continuing it is meaningless.
     */
    DIMENSION_CHANGED("dimension_changed"),

    /**
     * The verified route milestone advanced. Progress is the one event that
     * genuinely invalidates the rest of a plan.
     */
    MILESTONE_CHANGED("milestone_changed"),

    /**
     * A skill failed twice or more without world progress. Reported for
     * observability; the companion's own escalation already handles it, so
     * this trigger is not independently throttled.
     */
    REPEATED_FAILURE("repeated_failure"),

    /**
     * A route reached its declared terminal milestone, or the server
     * verified the goal's physical completion. Usually resolved without a
     * model call at all, so this is mostly a bookkeeping label.
     */
    MILESTONE_REACHED("milestone_reached"),

    /**
     * Nothing changed and no failure occurred, but enough time has passed
     * that a review is due. This is the periodic floor that keeps a long
     * silent stretch from becoming a dead companion.
     */
    PERIODIC_REVIEW("periodic_review");

    private final String code;

    PlannerTrigger(final String code) {
        this.code = code;
    }

    /**
     * A stable, wire-safe name for logs and notices. Deliberately separate
     * from {@link #name()} so the enum can be renamed without changing what
     * operators have already seen.
     */
    public String code() {
        return code;
    }

    /**
     * Whether a call with this trigger is expected to change the companion's
     * behaviour. {@link #PERIODIC_REVIEW} is the only one that may merely
     * confirm the status quo.
     */
    public boolean isProgressDriven() {
        return this != PERIODIC_REVIEW;
    }

    /**
     * Everything the table needs, captured as one immutable value so the
     * decision is reproducible and testable.
     *
     * @param hasPlanInstalled     whether a route plan is attached to the goal
     * @param milestoneAdvanced    whether the verified route milestone
     *                             reached a new target since the last call
     * @param dimensionChanged     whether the companion's dimension changed
     *                             since the last call
     * @param consecutiveNoActions how many consecutive actionless responses
     *                             the model has produced for this goal
     * @param consecutiveFailures  how many consecutive skill or model failures
     *                             have occurred without world progress
     * @param terminalReached      whether the goal's declared terminal
     *                             milestone or physical completion is verified
     * @param millisSinceLastPlan  time since the last planner call
     * @param periodicReviewMillis the configured interval beyond which a
     *                             review is due
     */
    public record Signals(
            boolean hasPlanInstalled,
            boolean milestoneAdvanced,
            boolean dimensionChanged,
            int consecutiveNoActions,
            int consecutiveFailures,
            boolean terminalReached,
            long millisSinceLastPlan,
            long periodicReviewMillis
    ) {
        /** Failures that count as "repeated" rather than incidental. */
        public static final int REPEATED_FAILURE_THRESHOLD = 2;

        /** Actionless responses that indicate the body is genuinely stuck. */
        public static final int NO_USEFUL_ACTION_THRESHOLD = 1;

        public Signals {
            if (consecutiveNoActions < 0) {
                throw new IllegalArgumentException(
                        "consecutiveNoActions must be non-negative"
                );
            }
            if (consecutiveFailures < 0) {
                throw new IllegalArgumentException(
                        "consecutiveFailures must be non-negative"
                );
            }
            if (millisSinceLastPlan < 0) {
                throw new IllegalArgumentException(
                        "millisSinceLastPlan must be non-negative"
                );
            }
            if (periodicReviewMillis <= 0) {
                throw new IllegalArgumentException(
                        "periodicReviewMillis must be positive"
                );
            }
        }

        /** A goal that has just started, with nothing yet observed. */
        public static Signals fresh() {
            return new Signals(
                    false, false, false, 0, 0, false, 0L, 120_000L
            );
        }
    }

    /**
     * The most specific reason to spend a planner call, or empty when none
     * applies.
     *
     * <p>Order is the contract. A newly installed goal must be planned
     * before anything about it can have changed; a completed goal must be
     * recognised before its staleness is reported as a failure. The list
     * below mirrors the reference implementation's order, with this
     * project's own milestones inserted where they are observable.</p>
     *
     * @return the trigger, or empty when the companion should not call
     */
    public static Optional<PlannerTrigger> classify(final Signals signals) {
        Objects.requireNonNull(signals, "signals");
        if (!signals.hasPlanInstalled()) {
            return Optional.of(INITIAL);
        }
        if (signals.terminalReached()) {
            return Optional.of(MILESTONE_REACHED);
        }
        if (signals.dimensionChanged()) {
            return Optional.of(DIMENSION_CHANGED);
        }
        if (signals.milestoneAdvanced()) {
            return Optional.of(MILESTONE_CHANGED);
        }
        if (signals.consecutiveFailures()
                >= Signals.REPEATED_FAILURE_THRESHOLD) {
            return Optional.of(REPEATED_FAILURE);
        }
        if (signals.consecutiveNoActions()
                >= Signals.NO_USEFUL_ACTION_THRESHOLD) {
            return Optional.of(NO_USEFUL_ACTIONS);
        }
        if (signals.millisSinceLastPlan()
                >= signals.periodicReviewMillis()) {
            return Optional.of(PERIODIC_REVIEW);
        }
        return Optional.empty();
    }

    /**
     * The default interval beyond which a silent stretch is reviewed even
     * though nothing changed.
     *
     * <p>Two minutes, matching the reference table. It is deliberately
     * longer than the autonomy driver's shortest wake interval: autonomy
     * proposes new objectives when there is none, whereas this reviews an
     * existing one that has produced no news.</p>
     */
    public static final long DEFAULT_PERIODIC_REVIEW_MILLIS = 120_000L;

    /**
     * Reads the milestone an installed plan is aiming at.
     *
     * <p>Extracted here rather than duplicated at call sites so the trigger
     * table and the orchestrator cannot drift on what "the plan's milestone"
     * means.</p>
     */
    public static Optional<GoalExecutionPlan.Target> plannedTarget(
            final String detailCode
    ) {
        return GoalExecutionPlan.fromDetailCode(detailCode)
                .map(GoalExecutionPlan::terminalTarget)
                .filter(target -> target != GoalExecutionPlan.Target.NONE);
    }
}
