package dev.mcai.companion.progression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.control.GoalExecutionPlan;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.control.PersistedGoalState;
import dev.mcai.companion.world.CompanionWorldData;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class FoundationActionAuditTest {
    @Test
    void modelEncodedFoundationPlanDoesNotDependOnGoalKeywords() {
        final CompanionWorldData data = runningGoal(
                "Please do the useful thing I described.",
                GoalExecutionPlan.foundation(
                        GoalExecutionPlan.Target.STONE_TOOL_OBTAINED
                ).detailCode()
        );

        assertTrue(new FoundationActionAudit(data).activeFoundationGoal());
    }

    @Test
    void modelEncodedCompletionPlanAlsoAuditsEarlyWorkstations() {
        final CompanionWorldData data = runningGoal(
                "Let's get this done.",
                GoalExecutionPlan.completion(
                        GoalExecutionPlan.Target.RETURNED_FROM_END
                ).detailCode()
        );

        assertTrue(new FoundationActionAudit(data).activeFoundationGoal());
    }

    @Test
    void unrelatedTypedGoalCannotCreateFoundationEvidence() {
        final CompanionWorldData data = runningGoal(
                "Tell me about the weather.",
                ""
        );

        assertFalse(new FoundationActionAudit(data).activeFoundationGoal());
    }

    private static CompanionWorldData runningGoal(
            final String goal,
            final String detailCode
    ) {
        final CompanionWorldData data = new CompanionWorldData();
        data.updateGoalState(new PersistedGoalState(
                0L,
                Optional.of(UUID.randomUUID()),
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                goal,
                detailCode,
                Instant.EPOCH,
                false
        ));
        return data;
    }
}
