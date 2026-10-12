package dev.mcai.companion.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link GoalCoordinator} to the reason/hold lookups that replaced
 * reading a bare status string.
 *
 * <p>Before these methods, every caller decided for itself what {@code
 * SAFE_IDLE} meant. That produced the collapse {@link Hold} documents: an
 * absent body and an owner stop share the status but need opposite handling,
 * and only one of them is cleared by a chat message. These tests pin that the
 * coordinator now answers the question instead of the caller guessing.</p>
 */
final class GoalCoordinatorHaltTest {

    @Test
    void aLiveGoalHasNeitherAReasonNorAHold() {
        final GoalCoordinator coordinator =
                new GoalCoordinator(new InMemoryGoalRevisionStore());
        assertEquals(Optional.empty(), coordinator.haltReason());
        assertEquals(Optional.empty(), coordinator.hold());

        coordinator.setGoal("砍木头", GoalSource.PLAYER_CHAT);
        assertEquals(Optional.empty(), coordinator.haltReason());
        assertEquals(
                Optional.empty(),
                coordinator.hold(),
                "A running goal is not resting anywhere"
        );

        coordinator.requestCancel(GoalSource.PLAYER_CHAT);
        assertEquals(GoalStatus.CANCEL_PENDING, coordinator.snapshot().status());
        assertEquals(
                Optional.empty(),
                coordinator.hold(),
                "A goal still winding down is not yet held, either"
        );
    }

    @Test
    void aCancelledGoalReportsTheOwnerStopThatClearsIt() {
        final GoalCoordinator coordinator =
                new GoalCoordinator(new InMemoryGoalRevisionStore());
        coordinator.setGoal("挖石头", GoalSource.PLAYER_CHAT);
        coordinator.requestCancel(GoalSource.PLAYER_CHAT);
        coordinator.markTerminal(
                GoalStatus.SAFE_IDLE,
                HaltReason.GOAL_CANCELLED.code()
        );

        assertEquals(
                Optional.of(HaltReason.GOAL_CANCELLED),
                coordinator.haltReason()
        );
        assertEquals(Optional.of(Hold.OWNER_STOP), coordinator.hold());
        assertTrue(
                coordinator.hold().orElseThrow().curableByOwner(),
                "The owner's next instruction must release an owner stop"
        );
    }

    @Test
    void anAbsentBodyAndAnOwnerStopAreToldApartDespiteTheSameStatus() {
        /*
         * The whole point of the two tables, stated as one assertion pair.
         * Both goals are SAFE_IDLE and both carry a registered reason; a
         * caller that inspected only the status would treat them as the same
         * event.
         */
        final GoalCoordinator stopped =
                new GoalCoordinator(new InMemoryGoalRevisionStore());
        stopped.setGoal("造房子", GoalSource.PLAYER_CHAT);
        stopped.requestCancel(GoalSource.PLAYER_CHAT);
        stopped.markTerminal(
                GoalStatus.SAFE_IDLE,
                HaltReason.GOAL_CANCELLED.code()
        );

        final GoalCoordinator bodyless =
                new GoalCoordinator(new InMemoryGoalRevisionStore());
        bodyless.setGoal("造房子", GoalSource.PLAYER_CHAT);
        bodyless.requestCancel(GoalSource.PLAYER_CHAT);
        bodyless.markTerminal(
                GoalStatus.SAFE_IDLE,
                HaltReason.BODY_ABSENT.code()
        );

        assertEquals(stopped.snapshot().status(), bodyless.snapshot().status());
        assertNotEquals(stopped.hold(), bodyless.hold());
        assertEquals(Optional.of(Hold.DEAD), bodyless.hold());
        assertFalse(
                bodyless.hold().orElseThrow().curableByOwner(),
                "No message may clear an absent body"
        );
        assertTrue(
                bodyless.hold().orElseThrow().isBodyRelated(),
                "Only the body-related hold should demand a respawn first"
        );
        assertFalse(stopped.hold().orElseThrow().isBodyRelated());
    }

    @Test
    void aBlockedReasonIsNotConfusedWithAFailedOne() {
        /*
         * MODEL_UNAVAILABLE and PLANNER_NO_ACTION_EXHAUSTED share SAFE_IDLE.
         * The first means the endpoint is missing and will come back; the
         * second means the plan was tried and did not work. They need
         * different recoveries, so they must not collapse into one hold.
         */
        final GoalCoordinator blocked =
                new GoalCoordinator(new InMemoryGoalRevisionStore());
        blocked.setGoal("去矿洞", GoalSource.PLAYER_CHAT);
        blocked.markTerminal(
                GoalStatus.SAFE_IDLE,
                HaltReason.MODEL_UNAVAILABLE.code()
        );

        final GoalCoordinator failed =
                new GoalCoordinator(new InMemoryGoalRevisionStore());
        failed.setGoal("去矿洞", GoalSource.PLAYER_CHAT);
        failed.markTerminal(
                GoalStatus.SAFE_IDLE,
                HaltReason.PLANNER_NO_ACTION_EXHAUSTED.code()
        );

        assertEquals(Optional.of(Hold.BLOCKED), blocked.hold());
        assertEquals(Optional.of(Hold.FAILED), failed.hold());
        assertNotEquals(blocked.hold(), failed.hold());
    }

    @Test
    void aStatusWithoutARegisteredReasonStillYieldsAHoldButNoReason() {
        /*
         * Controllers outside the table can mark a terminal status directly.
         * The hold must still be answerable so callers never see "unknown",
         * but no specific reason may be invented for them.
         */
        final GoalCoordinator coordinator =
                new GoalCoordinator(new InMemoryGoalRevisionStore());
        coordinator.setGoal("随便走走", GoalSource.MCP);
        coordinator.markTerminal(
                GoalStatus.COMPLETED,
                "a_code_outside_the_table"
        );

        assertEquals(
                Optional.empty(),
                coordinator.haltReason(),
                "An unregistered code must not resolve to a specific reason"
        );
        assertEquals(
                Optional.of(Hold.EXTERNAL),
                coordinator.hold(),
                "A finished goal still rests, and the status says where"
        );
    }

    @Test
    void aCodeFromTheWrongStatusColumnDoesNotResolve() {
        /*
         * GOAL_CANCELLED is registered as SAFE_IDLE. If a controller marked
         * the goal FAILED with that code, the reason must not be reported:
         * the two columns would then contradict each other, and a caller
         * switching on the reason would act on the wrong status.
         */
        final GoalCoordinator coordinator =
                new GoalCoordinator(new InMemoryGoalRevisionStore());
        coordinator.setGoal("收小麦", GoalSource.PLAYER_CHAT);
        coordinator.markTerminal(
                GoalStatus.FAILED,
                HaltReason.GOAL_CANCELLED.code()
        );

        assertEquals(GoalStatus.FAILED, coordinator.snapshot().status());
        assertEquals(
                Optional.empty(),
                coordinator.haltReason(),
                "A reason whose status disagrees with the goal is not this "
                        + "goal's reason"
        );
        assertEquals(
                Optional.of(Hold.FAILED),
                coordinator.hold(),
                "The hold falls back to the status rather than to the "
                        + "contradictory reason"
        );
    }

    @Test
    void restoreKeepsTheReasonAndHoldIntactAcrossARestart() {
        /*
         * The reason is persisted as detailCode, so a restored goal must
         * report the same hold. A restart that lost this would leave the
         * companion standing in an absent body with autonomy free to
         * propose new work.
         */
        final InMemoryGoalRevisionStore revisions =
                new InMemoryGoalRevisionStore();
        final InMemoryGoalStateStore persistence =
                new InMemoryGoalStateStore();
        final GoalCoordinator first =
                new GoalCoordinator(revisions, persistence);
        first.setGoal("通关 Minecraft", GoalSource.PLAYER_CHAT);
        first.markTerminal(
                GoalStatus.SAFE_IDLE,
                HaltReason.BODY_ABSENT.code()
        );

        final GoalCoordinator restored =
                new GoalCoordinator(revisions, persistence);

        assertEquals(
                Optional.of(HaltReason.BODY_ABSENT),
                restored.haltReason()
        );
        assertEquals(Optional.of(Hold.DEAD), restored.hold());
        assertFalse(restored.hold().orElseThrow().selfClearing());
    }

    @Test
    void theHoldIsFullySpecifiedForEveryTerminalStatus() {
        /*
         * A caller must never receive "no hold" for a goal that has already
         * stopped. Enumerating the terminal statuses keeps that true when a
         * status is added to GoalStatus later.
         */
        for (GoalStatus status : GoalStatus.values()) {
            if (status == GoalStatus.RUNNING
                    || status == GoalStatus.CANCEL_PENDING) {
                assertFalse(
                        Hold.isHold(status),
                        status + " is still live and must not report a hold"
                );
                continue;
            }
            assertTrue(
                    Hold.isHold(status),
                    status + " is terminal and must specify a hold"
            );
            /*
             * of() throws rather than returning null, so calling it is the
             * assertion: a status added without a hold mapping fails here.
             */
            final Hold hold = Hold.of(status);
            assertTrue(
                    hold.selfClearing() != hold.curableByOwner(),
                    status + " must name exactly one way out of its hold"
            );
        }
    }
}
