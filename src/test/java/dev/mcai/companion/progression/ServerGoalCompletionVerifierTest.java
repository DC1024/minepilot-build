package dev.mcai.companion.progression;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.world.CompanionWorldData;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ServerGoalCompletionVerifierTest {
    @Test
    void completionRouteNeedsDragonAndReturnEvidence() {
        final CompanionWorldData data = new CompanionWorldData();
        final ServerGoalCompletionVerifier verifier =
                new ServerGoalCompletionVerifier(data);
        final GoalSnapshot goal = goal("从零通关 Minecraft");

        assertFalse(verifier.verify(goal).accepted());
        data.markVerifiedRouteMilestones(
                0,
                Set.of(SurvivalMilestone.DRAGON_KILLED)
        );
        assertFalse(verifier.verify(goal).accepted());
        data.markVerifiedRouteMilestones(
                0,
                Set.of(SurvivalMilestone.RETURNED_FROM_END)
        );
        assertTrue(verifier.verify(goal).accepted());
        assertTrue(
                verifier.verifyAutonomousCompletion(goal)
                        .orElseThrow()
                        .accepted()
        );
    }

    @Test
    void foundationRouteNeedsEveryAcceptanceMilestone() {
        final CompanionWorldData data = new CompanionWorldData();
        final ServerGoalCompletionVerifier verifier =
                new ServerGoalCompletionVerifier(data);
        final GoalSnapshot goal =
                goal("建立安全据点并生存到第二天");

        data.markVerifiedRouteMilestones(
                0,
                Set.of(
                        SurvivalMilestone.BODY_ACTIVE,
                        SurvivalMilestone.WOOD_OBTAINED,
                        SurvivalMilestone.BASIC_CRAFTING_READY,
                        SurvivalMilestone.FOOD_SECURED,
                        SurvivalMilestone.STONE_TOOL_OBTAINED,
                        SurvivalMilestone.IRON_OBTAINED,
                        SurvivalMilestone.IRON_TOOLKIT_OBTAINED,
                        SurvivalMilestone.WORKSTATIONS_ESTABLISHED,
                        SurvivalMilestone.SHELTER_COMPLETED
                )
        );
        assertFalse(verifier.verify(goal).accepted());
        data.markVerifiedRouteMilestones(
                0,
                Set.of(
                        SurvivalMilestone.FIRST_NIGHT_SURVIVED,
                        SurvivalMilestone.SUPPLIES_STORED
                )
        );
        assertTrue(verifier.verify(goal).accepted());
    }

    @Test
    void ordinaryCompanionGoalsRemainModelCompletable() {
        final ServerGoalCompletionVerifier verifier =
                new ServerGoalCompletionVerifier(
                        new CompanionWorldData()
                );
        final GoalSnapshot ordinary = goal(
                "收小麦、补种并放进箱子"
        );
        assertTrue(verifier.verify(ordinary).accepted());
        assertTrue(
                verifier.verifyAutonomousCompletion(ordinary).isEmpty()
        );
        assertFalse(
                verifier.allowModelCompletionWithoutAction(ordinary)
        );
    }

    private static GoalSnapshot goal(final String text) {
        return new GoalSnapshot(
                Optional.empty(),
                0,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                text,
                "",
                Instant.EPOCH,
                false
        );
    }
}
