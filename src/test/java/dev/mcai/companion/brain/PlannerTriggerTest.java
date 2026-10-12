package dev.mcai.companion.brain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.control.GoalExecutionPlan;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the planner-trigger table: the reason a high-level call is about to be
 * spent, and the order in which reasons outrank each other.
 *
 * <p>{@link PlannerTrigger} does not gate anything yet — see its class comment
 * for why removing calls before measuring them would stand the companion
 * still. What it does have to be is <em>correct and total</em>, because the
 * distribution it reports is the evidence a later decision to narrow the set
 * would rest on. A table that mislabels a call makes that evidence worse than
 * no evidence at all.</p>
 */
final class PlannerTriggerTest {

    @Test
    void aGoalWithNoPlanIsAlwaysPlanned() {
        /*
         * INITIAL outranks everything. A goal that has never been planned has
         * nothing to continue, so no counter or elapsed interval should be
         * able to suppress the first call.
         */
        assertEquals(
                Optional.of(PlannerTrigger.INITIAL),
                PlannerTrigger.classify(new PlannerTrigger.Signals(
                        false, true, true, 9, 9, true, 999_999L, 1L
                ))
        );
        assertEquals(
                Optional.of(PlannerTrigger.INITIAL),
                PlannerTrigger.classify(PlannerTrigger.Signals.fresh())
        );
    }

    @Test
    void aReachedMilestoneIsRecognisedBeforeStalenessIsCalledAFailure() {
        /*
         * Order is the contract. A goal that reached its terminal milestone
         * while a skill also failed must be read as finished, not as broken;
         * otherwise the companion reports a failure about a goal that
         * actually succeeded.
         */
        final PlannerTrigger.Signals reached = new PlannerTrigger.Signals(
                true, false, false, 0, 5, true, 10L, 120_000L
        );
        assertEquals(
                Optional.of(PlannerTrigger.MILESTONE_REACHED),
                PlannerTrigger.classify(reached)
        );
    }

    @Test
    void aDimensionChangeOutranksAMilestoneAdvance() {
        /*
         * Both are progress events, but a dimension change invalidates the
         * plan's geography entirely while a milestone advance invalidates
         * only its remainder. Reporting the weaker cause would hide the
         * stronger one whenever they coincide.
         */
        final PlannerTrigger.Signals both = new PlannerTrigger.Signals(
                true, true, true, 0, 0, false, 10L, 120_000L
        );
        assertEquals(
                Optional.of(PlannerTrigger.DIMENSION_CHANGED),
                PlannerTrigger.classify(both)
        );
    }

    @Test
    void aRepeatedFailureOutranksAnActionlessResponse() {
        /*
         * A model that returns nothing and a skill that fails twice are
         * different problems. When both counters are up the failure is the
         * more specific diagnosis and must be the one reported.
         */
        final PlannerTrigger.Signals stuck = new PlannerTrigger.Signals(
                true, false, false, 3, 2, false, 10L, 120_000L
        );
        assertEquals(
                Optional.of(PlannerTrigger.REPEATED_FAILURE),
                PlannerTrigger.classify(stuck)
        );
    }

    @Test
    void oneFailureIsIncidentalButTwoAreRepeated() {
        final PlannerTrigger.Signals one = new PlannerTrigger.Signals(
                true, false, false, 0, 1, false, 10L, 120_000L
        );
        assertEquals(
                Optional.empty(),
                PlannerTrigger.classify(one),
                "A single failure is not yet a repeated failure"
        );
        final PlannerTrigger.Signals two = new PlannerTrigger.Signals(
                true, false, false, 0, 2, false, 10L, 120_000L
        );
        assertEquals(
                Optional.of(PlannerTrigger.REPEATED_FAILURE),
                PlannerTrigger.classify(two)
        );
    }

    @Test
    void oneActionlessResponseIsEnoughToWarrantACall() {
        /*
         * Lower threshold than failure on purpose: an empty response leaves
         * the body with literally nothing queued, so waiting for a second one
         * would mean a tick with no lead to follow at all.
         */
        final PlannerTrigger.Signals idle = new PlannerTrigger.Signals(
                true, false, false, 1, 0, false, 10L, 120_000L
        );
        assertEquals(
                Optional.of(PlannerTrigger.NO_USEFUL_ACTIONS),
                PlannerTrigger.classify(idle)
        );
    }

    @Test
    void aQuietStretchIsReviewedOnlyWhenTheIntervalIsDue() {
        final PlannerTrigger.Signals quiet = new PlannerTrigger.Signals(
                true, false, false, 0, 0, false, 10L, 120_000L
        );
        assertEquals(
                Optional.empty(),
                PlannerTrigger.classify(quiet),
                "Nothing changed and nothing is due, so nothing is planned"
        );

        final PlannerTrigger.Signals due = new PlannerTrigger.Signals(
                true, false, false, 0, 0, false, 120_000L, 120_000L
        );
        assertEquals(
                Optional.of(PlannerTrigger.PERIODIC_REVIEW),
                PlannerTrigger.classify(due),
                "The interval is inclusive: exactly due is due"
        );
    }

    @Test
    void progressOutranksThePeriodicFloor() {
        /*
         * The floor must never mask a real cause. If a goal advanced and the
         * interval also elapsed, the call is attributed to the advance.
         */
        final PlannerTrigger.Signals both = new PlannerTrigger.Signals(
                true, true, false, 0, 0, false, 999_999L, 120_000L
        );
        assertEquals(
                Optional.of(PlannerTrigger.MILESTONE_CHANGED),
                PlannerTrigger.classify(both)
        );
    }

    @Test
    void aFreshSignalSetPlansImmediatelyAndQuietly() {
        /*
         * fresh() is the state at goal start. It must classify as INITIAL,
         * and it must not accidentally carry a due interval or a failure
         * count, which would make the first call look like a retry.
         */
        final PlannerTrigger.Signals fresh = PlannerTrigger.Signals.fresh();
        assertFalse(fresh.hasPlanInstalled());
        assertEquals(0, fresh.consecutiveNoActions());
        assertEquals(0, fresh.consecutiveFailures());
        assertEquals(0L, fresh.millisSinceLastPlan());
        assertFalse(fresh.terminalReached());
        assertEquals(
                Optional.of(PlannerTrigger.INITIAL),
                PlannerTrigger.classify(fresh)
        );
    }

    @Test
    void onlyThePeriodicReviewIsNotProgressDriven() {
        /*
         * This flag is what a later hard gate would key off. If a progress
         * trigger ever became periodic, gating on this flag would silently
         * drop the very calls that carry news.
         */
        for (PlannerTrigger trigger : PlannerTrigger.values()) {
            assertEquals(
                    trigger != PlannerTrigger.PERIODIC_REVIEW,
                    trigger.isProgressDriven(),
                    trigger + " has an unexpected progress classification"
            );
        }
        assertFalse(PlannerTrigger.PERIODIC_REVIEW.isProgressDriven());
    }

    @Test
    void codesAreStableAndDistinct() {
        final java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        for (PlannerTrigger trigger : PlannerTrigger.values()) {
            assertTrue(
                    codes.add(trigger.code()),
                    "Duplicate trigger code: " + trigger.code()
            );
            assertTrue(
                    trigger.code().matches("[a-z][a-z0-9_]*"),
                    "Trigger code is not wire-safe: " + trigger.code()
            );
        }
        assertEquals(PlannerTrigger.values().length, codes.size());
        /*
         * Notices are keyed on these spellings, so renaming the enum constant
         * must not change what operators have already seen.
         */
        assertEquals("initial", PlannerTrigger.INITIAL.code());
        assertEquals("no_useful_actions",
                PlannerTrigger.NO_USEFUL_ACTIONS.code());
        assertEquals("dimension_changed",
                PlannerTrigger.DIMENSION_CHANGED.code());
        assertEquals("milestone_changed",
                PlannerTrigger.MILESTONE_CHANGED.code());
        assertEquals("repeated_failure",
                PlannerTrigger.REPEATED_FAILURE.code());
        assertEquals("milestone_reached",
                PlannerTrigger.MILESTONE_REACHED.code());
        assertEquals("periodic_review",
                PlannerTrigger.PERIODIC_REVIEW.code());
    }

    @Test
    void impossibleSignalsAreRejectedRatherThanSilentlyAccepted() {
        /*
         * A negative counter or a non-positive interval is a bug in the
         * caller, not a state the world can produce. Accepting one would make
         * the classification depend on nonsense; the arithmetic that
         * compares them would still "work", which is worse.
         */
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlannerTrigger.Signals(
                        true, false, false, -1, 0, false, 0L, 120_000L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlannerTrigger.Signals(
                        true, false, false, 0, -1, false, 0L, 120_000L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlannerTrigger.Signals(
                        true, false, false, 0, 0, false, -1L, 120_000L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlannerTrigger.Signals(
                        true, false, false, 0, 0, false, 0L, 0L
                )
        );
        assertThrows(
                NullPointerException.class,
                () -> PlannerTrigger.classify(null)
        );
    }

    @Test
    void aPlannedRouteIsReadFromTheDetailCodeTheModelAuthored() {
        /*
         * "The plan's milestone" must mean one thing in both the table and
         * the orchestrator, so it is derived here rather than duplicated at
         * the call site.
         */
        final String foundationRoute = GoalExecutionPlan.foundation(
                GoalExecutionPlan.Target.STONE_TOOL_OBTAINED
        ).detailCode();
        assertEquals(
                Optional.of(GoalExecutionPlan.Target.STONE_TOOL_OBTAINED),
                PlannerTrigger.plannedTarget(foundationRoute)
        );

        final String completionRoute = GoalExecutionPlan.completion(
                GoalExecutionPlan.Target.DRAGON_KILLED
        ).detailCode();
        assertEquals(
                Optional.of(GoalExecutionPlan.Target.DRAGON_KILLED),
                PlannerTrigger.plannedTarget(completionRoute)
        );
    }

    @Test
    void anAbsentOrStandaloneRouteHasNoMilestone() {
        /*
         * A standalone action encodes as NONE/NONE and a malformed code
         * decodes to nothing. Neither is a milestone, and reporting one would
         * make a goal with no route look already planned — which would
         * suppress the INITIAL trigger and leave the body with no lead.
         */
        assertEquals(
                Optional.empty(),
                PlannerTrigger.plannedTarget(
                        GoalExecutionPlan.none().detailCode()
                )
        );
        assertEquals(
                "",
                GoalExecutionPlan.none().detailCode(),
                "An unplanned goal carries no detail code at all, so it can "
                        + "never be mistaken for an installed route"
        );
        /*
         * The plan record refuses to pair FOUNDATION with NONE, so a
         * route-shaped goal always carries a real target. The NONE filter in
         * plannedTarget therefore protects the decode path rather than the
         * constructor path.
         */
        assertThrows(
                IllegalArgumentException.class,
                () -> GoalExecutionPlan.foundation(
                        GoalExecutionPlan.Target.NONE
                )
        );
        assertEquals(
                Optional.empty(),
                PlannerTrigger.plannedTarget("")
        );
        assertEquals(
                Optional.empty(),
                PlannerTrigger.plannedTarget(null)
        );
        assertEquals(
                Optional.empty(),
                PlannerTrigger.plannedTarget("not-a-plan-code")
        );
        assertEquals(
                Optional.empty(),
                PlannerTrigger.plannedTarget("plan:BROKEN")
        );
        assertEquals(
                Optional.empty(),
                PlannerTrigger.plannedTarget("plan:FOUNDATION:NONE"),
                "Even a hand-written NONE target is not a milestone"
        );
    }

    @Test
    void thePeriodicFloorSitsAboveTheShortestAutonomyWake() {
        /*
         * Autonomy proposes a new objective when there is none; this table
         * reviews an existing one that has produced no news. The review must
         * be the lazier of the two, or a quiet stretch turns into a stream of
         * planner calls that all report the same unchanged world.
         */
        assertTrue(
                PlannerTrigger.DEFAULT_PERIODIC_REVIEW_MILLIS >= 60_000L,
                "A review interval under a minute would re-ask before the "
                        + "world can plausibly have changed"
        );
        assertEquals(
                PlannerTrigger.DEFAULT_PERIODIC_REVIEW_MILLIS,
                PlannerTrigger.Signals.fresh().periodicReviewMillis(),
                "The default factory and the constant must agree"
        );
    }
}
