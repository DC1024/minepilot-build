package dev.mcai.companion.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.control.Hold;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the two questions the driver asks before spending a model call.
 *
 * <p>{@code canTakeOver} exists because the original rule accepted only
 * {@code IDLE}. Nothing in the goal machine ever returns a goal to
 * {@code IDLE}, so the driver probed once at server start and then went
 * permanently silent — and since autonomy is the only source of objectives
 * when nobody is directing the companion, she stood still forever. That
 * regression is worth a test.</p>
 *
 * <p>{@code mayPropose} adds the hold, because an absent body reports
 * {@code SAFE_IDLE} exactly like an ordinary cancel: a status-only check
 * would pay for a request whose answer must be discarded.</p>
 */
final class AutonomyDriverGateTest {

    @Test
    void everyTerminalStatusCanBeTakenOver() {
        /*
         * The regression, stated directly. Each of these statuses describes a
         * goal that is finished rather than pursued, so the companion is
         * unowned and autonomy may propose.
         */
        for (GoalStatus status : GoalStatus.values()) {
            if (status == GoalStatus.RUNNING
                    || status == GoalStatus.CANCEL_PENDING) {
                continue;
            }
            assertTrue(
                    AutonomyDriver.canTakeOver(snapshot(status, false)),
                    status + " is a finished goal, not a pursued one, so "
                            + "autonomy must be allowed to take over"
            );
        }
    }

    @Test
    void aLiveGoalIsNotTakenOver() {
        assertFalse(
                AutonomyDriver.canTakeOver(
                        snapshot(GoalStatus.RUNNING, false)
                ),
                "A running goal still has an owner"
        );
        assertFalse(
                AutonomyDriver.canTakeOver(
                        snapshot(GoalStatus.CANCEL_PENDING, false)
                ),
                "A goal mid-cancel is still being wound down"
        );
    }

    @Test
    void aLockedEvaluationIsNeverTakenOver() {
        /*
         * A hardcore evaluation holds external writes for its whole run.
         * Autonomy proposing a new objective there would replace the single
         * graded command and void the run.
         */
        for (GoalStatus status : GoalStatus.values()) {
            assertFalse(
                    AutonomyDriver.canTakeOver(snapshot(status, true)),
                    status + " is locked by an evaluation and must never be "
                            + "replaced by an autonomous objective"
            );
        }
    }

    @Test
    void aProposalNeedsBothAFreeGoalAndAClearableHold() {
        assertTrue(AutonomyDriver.mayPropose(
                snapshot(GoalStatus.COMPLETED, false),
                Optional.of(Hold.EXTERNAL)
        ));

        /*
         * Same status, different hold: this is the collapse the two tables
         * exist to prevent, and it must be visible here too.
         */
        assertFalse(
                AutonomyDriver.mayPropose(
                        snapshot(GoalStatus.SAFE_IDLE, false),
                        Optional.of(Hold.DEAD)
                ),
                "An absent body reports SAFE_IDLE but cannot act on any "
                        + "objective"
        );
        assertFalse(
                AutonomyDriver.mayPropose(
                        snapshot(GoalStatus.SAFE_IDLE, false),
                        Optional.of(Hold.OWNER_STOP)
                ),
                "Autonomy must not restart what the owner stopped"
        );
        assertFalse(
                AutonomyDriver.mayPropose(
                        snapshot(GoalStatus.SAFE_IDLE, false),
                        Optional.of(Hold.BLOCKED)
                ),
                "With no endpoint the request cannot even be made"
        );
        assertFalse(
                AutonomyDriver.mayPropose(
                        snapshot(GoalStatus.SAFE_IDLE, false),
                        Optional.of(Hold.FAILED)
                ),
                "An exhausted attempt must wait for its owner"
        );
    }

    @Test
    void anUnknownHoldIsNotEnoughToPropose() {
        /*
         * Empty means "no hold is known", which is the state of a live goal.
         * Treating it as permission would let the driver act on a goal it
         * cannot see the state of.
         */
        assertFalse(
                AutonomyDriver.mayPropose(
                        snapshot(GoalStatus.COMPLETED, false),
                        Optional.empty()
                )
        );
    }

    @Test
    void onlyTheSelfClearingHoldPermitsAutonomy() {
        /*
         * Stated as a property of the hold table rather than as five cases,
         * so adding a hold forces this decision instead of defaulting it.
         */
        for (Hold hold : Hold.values()) {
            final boolean proposed = AutonomyDriver.mayPropose(
                    snapshot(GoalStatus.COMPLETED, false),
                    Optional.of(hold)
            );
            assertEquals(
                    hold.selfClearing(),
                    proposed,
                    hold + " must be proposeable exactly when it is "
                            + "self-clearing"
            );
        }
        assertEquals(
                1,
                java.util.Arrays.stream(Hold.values())
                        .filter(Hold::selfClearing)
                        .count(),
                "Exactly one hold is self-clearing; more would mean two "
                        + "different meanings for 'the companion may proceed'"
        );
    }

    private static GoalSnapshot snapshot(
            final GoalStatus status,
            final boolean locked
    ) {
        return new GoalSnapshot(
                Optional.of(UUID.randomUUID()),
                1L,
                status,
                GoalSource.PLAYER_CHAT,
                "砍木头",
                "",
                Instant.EPOCH,
                locked
        );
    }
}
