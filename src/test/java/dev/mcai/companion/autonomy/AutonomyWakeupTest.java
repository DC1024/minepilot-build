package dev.mcai.companion.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.control.HaltReason;
import dev.mcai.companion.control.Hold;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the wake gate's two promises: a ripe wake always has work, and a rest
 * that nobody can act on never wakes anybody.
 *
 * <p>The scenarios below are the field cases that motivated replacing the
 * fixed 45-second timer, written down so a future simplification cannot
 * quietly reintroduce them.</p>
 */
final class AutonomyWakeupTest {
    private static final long SECOND = 1_000L;

    @Test
    void aFinishedObjectiveWakesTheCompanionOnTheNextTick() {
        final AutonomyWakeup gate = new AutonomyWakeup(1);
        gate.observe(
                Optional.of(HaltReason.SERVER_VERIFIED_COMPLETE),
                Optional.of(Hold.EXTERNAL),
                0L
        );
        assertEquals(Ripeness.URGENT, gate.ripeness(0L));
        assertTrue(gate.isRipe(0L));
    }

    @Test
    void aFailedAttemptWaitsForItsOwnerRatherThanForAutonomy() {
        final AutonomyWakeup gate = new AutonomyWakeup(1);
        gate.observe(
                Optional.of(HaltReason.PLANNER_NO_ACTION_EXHAUSTED),
                Optional.of(Hold.FAILED),
                0L
        );
        /*
         * Hold.FAILED is not self-clearing, so an exhausted objective must
         * not be offered to the driver at all: only the owner can restart
         * it. If this ever changes, autonomy would silently retry a goal the
         * companion has already proven it cannot do.
         */
        assertEquals(Ripeness.NOT_YET, gate.ripeness(0L));
        assertEquals(0, gate.pendingSignals());
    }

    @Test
    void aHoldAutonomyCannotClearNeverWakesAnybody() {
        /*
         * An absent body reports SAFE_IDLE exactly like an ordinary cancel.
         * Waking for it would spend a model call whose answer must be thrown
         * away, so the gate must stay silent no matter how long she waits.
         */
        final AutonomyWakeup gate = new AutonomyWakeup(1);
        for (long tick = 0; tick < 40_000L; tick += 20L) {
            gate.observe(
                    Optional.of(HaltReason.BODY_ABSENT),
                    Optional.of(Hold.DEAD),
                    tick * 50L
            );
        }
        assertEquals(0, gate.pendingSignals());
        assertEquals(Ripeness.NOT_YET, gate.ripeness(2_000_000L));
    }

    @Test
    void anOwnerStopIsAlsoSilentUntilTheOwnerSpeaks() {
        final AutonomyWakeup gate = new AutonomyWakeup(1);
        for (long tick = 0; tick < 20_000L; tick += 20L) {
            gate.observe(
                    Optional.of(HaltReason.GOAL_CANCELLED),
                    Optional.of(Hold.OWNER_STOP),
                    tick * 50L
            );
        }
        assertEquals(
                0,
                gate.pendingSignals(),
                "Autonomy must not restart work the owner just cancelled"
        );
    }

    @Test
    void oneRestCountsOnceNoMatterHowManyTicksItLasts() {
        final AutonomyWakeup gate = new AutonomyWakeup(1);
        for (long tick = 0; tick < 20_000L; tick += 20L) {
            gate.observe(
                    Optional.of(HaltReason.SERVER_VERIFIED_COMPLETE),
                    Optional.of(Hold.EXTERNAL),
                    tick * 50L
            );
        }
        assertEquals(
                1,
                gate.pendingSignals(),
                "A single rest must collapse to a single signal"
        );
    }

    @Test
    void aConsumedRestRetriesOnTheConfiguredCadenceNotEveryTick() {
        /*
         * The failed-wake case. The objective did not install, so the
         * companion is still idle; she must try again, but at the wait-time
         * cadence rather than at 20 TPS.
         */
        final AutonomyWakeup gate = new AutonomyWakeup(1);
        final long wait = Ripeness.maxWaitMillisOf(1);
        gate.observe(
                Optional.of(HaltReason.SERVER_VERIFIED_COMPLETE),
                Optional.of(Hold.EXTERNAL),
                0L
        );
        assertEquals(Ripeness.URGENT, gate.consume(0L));

        int wakes = 0;
        final long horizonMillis = wait * 5L;
        for (long now = 50L; now <= horizonMillis; now += 50L) {
            gate.observe(
                    Optional.of(HaltReason.SERVER_VERIFIED_COMPLETE),
                    Optional.of(Hold.EXTERNAL),
                    now
            );
            if (gate.consume(now).atLeast(Ripeness.ENOUGH)) {
                wakes++;
            }
        }
        /*
         * One retry per wait interval, not one per tick. Bounds are wide
         * because the exact count depends on how the 50 ms tick grid aligns
         * with the interval boundary.
         */
        assertTrue(
                wakes >= 4 && wakes <= 6,
                "Expected about one retry per wait interval, saw " + wakes
                        + " over " + (horizonMillis / wait) + " intervals"
        );
    }

    @Test
    void aLiveGoalClearsTheRestSoTheNextRestStillWakes() {
        /*
         * The regression that mattered: a latch that survived the return of
         * work would silence the companion after her first objective.
         */
        final AutonomyWakeup gate = new AutonomyWakeup(1);
        gate.observe(
                Optional.of(HaltReason.SERVER_VERIFIED_COMPLETE),
                Optional.of(Hold.EXTERNAL),
                0L
        );
        assertEquals(Ripeness.URGENT, gate.consume(0L));

        gate.observe(Optional.empty(), Optional.empty(), 50L);
        assertEquals(Ripeness.NOT_YET, gate.ripeness(50L));

        gate.observe(
                Optional.of(HaltReason.SERVER_VERIFIED_COMPLETE),
                Optional.of(Hold.EXTERNAL),
                100L
        );
        assertEquals(
                Ripeness.URGENT,
                gate.ripeness(100L),
                "A second completed objective must wake her again"
        );
    }

    @Test
    void ripenessIsPureAndConsumeAgreesWithIt() {
        final AutonomyWakeup gate = new AutonomyWakeup(1);
        gate.signal(AutonomyWakeup.Signal.IDLE_TICK, 0L);

        final Ripeness before = gate.ripeness(0L);
        assertEquals(before, gate.ripeness(0L), "ripeness must not mutate");
        assertEquals(1, gate.pendingSignals(), "ripeness must not consume");

        if (before.atLeast(Ripeness.ENOUGH)) {
            assertEquals(before, gate.consume(0L));
        } else {
            assertEquals(Ripeness.NOT_YET, gate.consume(0L));
            assertEquals(
                    1,
                    gate.pendingSignals(),
                    "A refused consume must leave the signals pending"
            );
        }
    }

    @Test
    void enoughSignalsAreReachedWithoutWaitingOutTheInterval() {
        final AutonomyWakeup gate = new AutonomyWakeup(1);
        gate.signal(AutonomyWakeup.Signal.IDLE_TICK, 0L);
        assertEquals(Ripeness.NOT_YET, gate.ripeness(0L));

        /*
         * A second, different signal arrives immediately. Two pending
         * signals is the batch threshold, so no wait is required — and
         * HOLD_CLEARED is itself urgent, so this is the strongest form of
         * the case.
         */
        gate.signal(AutonomyWakeup.Signal.HOLD_CLEARED, 50L);
        assertEquals(Ripeness.URGENT, gate.ripeness(50L));
    }

    @Test
    void aQuietWorldStillGetsPeriodicReviews() {
        final AutonomyWakeup gate = new AutonomyWakeup(1);
        final long wait = Ripeness.maxWaitMillisOf(1);
        gate.signal(AutonomyWakeup.Signal.IDLE_TICK, 0L);

        assertEquals(Ripeness.NOT_YET, gate.ripeness(wait - 1L));
        assertEquals(Ripeness.LONG_ENOUGH, gate.ripeness(wait));
        assertFalse(gate.isRipe(wait - 1L));
    }

    @Test
    void theWaitCurveIsQuadraticAndClamped() {
        assertEquals(20 * SECOND, Ripeness.maxWaitMillisOf(1));
        assertEquals(80 * SECOND, Ripeness.maxWaitMillisOf(2));
        assertEquals(3 * 60 * SECOND, Ripeness.maxWaitMillisOf(3));
        assertEquals(
                Ripeness.maxWaitMillisOf(Ripeness.MAX_LEVEL),
                Ripeness.maxWaitMillisOf(Ripeness.MAX_LEVEL + 50),
                "A level beyond the curve must clamp, not extrapolate"
        );
        assertEquals(
                Ripeness.maxWaitMillisOf(1),
                Ripeness.maxWaitMillisOf(0),
                "A level below the curve must clamp to its floor"
        );
        assertTrue(
                Ripeness.maxWaitMillisOf(10) >= 30 * 60 * SECOND,
                "Level 10 is a background liveness check, not a cadence"
        );
    }

    @Test
    void ripenessLevelsAreOrdered() {
        assertTrue(Ripeness.URGENT.atLeast(Ripeness.ENOUGH));
        assertTrue(Ripeness.ENOUGH.atLeast(Ripeness.LONG_ENOUGH));
        assertTrue(Ripeness.LONG_ENOUGH.atLeast(Ripeness.NOT_YET));
        assertFalse(Ripeness.NOT_YET.atLeast(Ripeness.LONG_ENOUGH));
        assertTrue(Ripeness.URGENT.rank() > Ripeness.NOT_YET.rank());
    }
}
