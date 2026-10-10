package dev.mcai.companion.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class GoalCoordinatorTest {
    @Test
    void startsAndCancelsOrdinaryGoalWithMonotonicRevisions() {
        final GoalCoordinator coordinator = new GoalCoordinator(new InMemoryGoalRevisionStore(7));

        final GoalCoordinator.MutationResult started =
            coordinator.setGoal("  收小麦、补种并存入箱子  ", GoalSource.PLAYER_CHAT);
        final GoalCoordinator.MutationResult cancelled =
            coordinator.requestCancel(GoalSource.MCP);

        assertTrue(started.accepted());
        assertEquals(8, started.snapshot().revision());
        assertEquals("收小麦、补种并存入箱子", started.snapshot().goal());
        assertTrue(cancelled.accepted());
        assertEquals(9, cancelled.snapshot().revision());
        assertEquals(GoalStatus.CANCEL_PENDING, cancelled.snapshot().status());
    }

    @Test
    void hardcoreEvaluationRejectsAllLaterExternalWrites() {
        final GoalCoordinator coordinator = new GoalCoordinator(new InMemoryGoalRevisionStore());

        final GoalCoordinator.MutationResult wrongInitial =
            coordinator.startHardcoreEvaluation("先给我一些钻石再通关");
        final GoalCoordinator.MutationResult initial =
            coordinator.startHardcoreEvaluation("通关 Minecraft");
        final GoalCoordinator.MutationResult replacement =
            coordinator.setGoal("停下来", GoalSource.PLAYER_CHAT);
        final GoalCoordinator.MutationResult cancellation =
            coordinator.requestCancel(GoalSource.MCP);
        final GoalCoordinator.MutationResult recoveryReplacement =
            coordinator.setGoal("内部恢复替换", GoalSource.RECOVERY);
        final GoalCoordinator.MutationResult recoveryCancellation =
            coordinator.requestCancel(GoalSource.RECOVERY);

        assertFalse(wrongInitial.accepted());
        assertEquals("invalid_evaluation_goal", wrongInitial.code());
        assertEquals(0L, wrongInitial.snapshot().revision());
        assertTrue(initial.accepted());
        assertTrue(initial.snapshot().externalWritesLocked());
        assertFalse(replacement.accepted());
        assertEquals("evaluation_locked", replacement.code());
        assertFalse(cancellation.accepted());
        assertEquals("evaluation_locked", cancellation.code());
        assertFalse(recoveryReplacement.accepted());
        assertEquals("evaluation_locked", recoveryReplacement.code());
        assertFalse(recoveryCancellation.accepted());
        assertEquals("evaluation_locked", recoveryCancellation.code());
        assertEquals("通关 Minecraft", coordinator.snapshot().goal());
    }

    @Test
    void foundationEvaluationUsesOneFixedAuditableGoal() {
        final GoalCoordinator coordinator = new GoalCoordinator(
                new InMemoryGoalRevisionStore()
        );

        final GoalCoordinator.MutationResult started =
                coordinator.startHardcoreEvaluation(
                        GoalCoordinator.HARDCORE_FOUNDATION_GOAL
                );

        assertTrue(started.accepted());
        assertEquals(
                "建立安全据点并生存到第二天",
                started.snapshot().goal()
        );
        assertEquals(
                GoalSource.HARDCORE_EVALUATION,
                started.snapshot().source()
        );
        assertTrue(started.snapshot().externalWritesLocked());
    }

    @Test
    void restoresHardcoreGoalAndPermanentWriteLockAcrossRestarts() {
        final InMemoryGoalRevisionStore revisions = new InMemoryGoalRevisionStore();
        final InMemoryGoalStateStore persistence = new InMemoryGoalStateStore();
        final GoalCoordinator first = new GoalCoordinator(revisions, persistence);

        final GoalCoordinator.MutationResult started =
            first.startHardcoreEvaluation("通关 Minecraft");
        final GoalCoordinator restoredRunning = new GoalCoordinator(revisions, persistence);

        assertEquals(started.snapshot().goalId(), restoredRunning.snapshot().goalId());
        assertEquals(GoalStatus.RUNNING, restoredRunning.snapshot().status());
        assertEquals(GoalSource.HARDCORE_EVALUATION, restoredRunning.snapshot().source());
        assertTrue(restoredRunning.snapshot().externalWritesLocked());
        assertFalse(restoredRunning.setGoal("替换任务", GoalSource.PLAYER_CHAT).accepted());
        assertFalse(restoredRunning.requestCancel(GoalSource.MCP).accepted());

        restoredRunning.markTerminal(GoalStatus.FAILED, "hardcore_death");
        final GoalCoordinator restoredTerminal = new GoalCoordinator(revisions, persistence);

        assertEquals(GoalStatus.FAILED, restoredTerminal.snapshot().status());
        assertEquals("hardcore_death", restoredTerminal.snapshot().detailCode());
        assertTrue(restoredTerminal.snapshot().externalWritesLocked());
        assertFalse(restoredTerminal.setGoal("重试", GoalSource.PLAYER_CHAT).accepted());
    }

    @Test
    void rejectsEmptyOversizedAndControlCharacterGoals() {
        final GoalCoordinator coordinator = new GoalCoordinator(new InMemoryGoalRevisionStore());

        assertFalse(coordinator.setGoal("  ", GoalSource.MCP).accepted());
        assertFalse(coordinator.setGoal(
            "a".repeat(GoalCoordinator.MAX_GOAL_CHARACTERS + 1),
            GoalSource.MCP
        ).accepted());
        assertFalse(coordinator.setGoal("mine\u0000craft", GoalSource.MCP).accepted());
        assertEquals(0, coordinator.snapshot().revision());
    }

    @Test
    void enforcesTerminalTransition() {
        final GoalCoordinator coordinator = new GoalCoordinator(new InMemoryGoalRevisionStore());
        assertThrows(IllegalStateException.class,
            () -> coordinator.markTerminal(GoalStatus.COMPLETED, "done"));

        coordinator.setGoal("build shelter", GoalSource.MCP);
        coordinator.markTerminal(GoalStatus.COMPLETED, "Dragon Defeated");

        assertEquals(GoalStatus.COMPLETED, coordinator.snapshot().status());
        assertEquals("dragon_defeated", coordinator.snapshot().detailCode());
    }
}
