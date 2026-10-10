package dev.mcai.companion.progression;

import dev.mcai.companion.brain.GoalCompletionVerification;
import dev.mcai.companion.brain.GoalCompletionVerifier;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.world.CompanionWorldData;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Prevents a high-level model from self-certifying route goals. Ordinary
 * conversational goals remain model-completable, while survival acceptance
 * routes require server evidence produced by local skills. Structural and
 * owned-resource acceptance facts are dynamically revoked when their
 * underlying world or inventory state is no longer valid.
 */
public final class ServerGoalCompletionVerifier
        implements GoalCompletionVerifier {
    private static final Set<SurvivalMilestone> FOUNDATION_REQUIRED =
            Set.of(
                    SurvivalMilestone.BODY_ACTIVE,
                    SurvivalMilestone.WOOD_OBTAINED,
                    SurvivalMilestone.BASIC_CRAFTING_READY,
                    SurvivalMilestone.FOOD_SECURED,
                    SurvivalMilestone.STONE_TOOL_OBTAINED,
                    SurvivalMilestone.IRON_OBTAINED,
                    SurvivalMilestone.IRON_TOOLKIT_OBTAINED,
                    SurvivalMilestone.WORKSTATIONS_ESTABLISHED,
                    SurvivalMilestone.SUPPLIES_STORED,
                    SurvivalMilestone.SHELTER_COMPLETED,
                    SurvivalMilestone.FIRST_NIGHT_SURVIVED
            );
    private static final Set<SurvivalMilestone> COMPLETION_REQUIRED =
            Set.of(
                    SurvivalMilestone.DRAGON_KILLED,
                    SurvivalMilestone.RETURNED_FROM_END
            );

    private final CompanionWorldData worldData;

    public ServerGoalCompletionVerifier(
            final CompanionWorldData worldData
    ) {
        this.worldData = Objects.requireNonNull(
                worldData,
                "worldData"
        );
    }

    @Override
    public GoalCompletionVerification verify(
            final GoalSnapshot goal
    ) {
        Objects.requireNonNull(goal, "goal");
        final Set<SurvivalMilestone> verified = worldData
                .verifiedRouteProgress(goal.revision())
                .milestones();
        final Optional<Set<SurvivalMilestone>> plannedRequired =
                SurvivalRouteTracker.explicitlyRequiredMilestones(goal);
        if (plannedRequired.isPresent()) {
            return verified.containsAll(plannedRequired.orElseThrow())
                    ? GoalCompletionVerification.approved()
                    : GoalCompletionVerification.rejected(
                            "planned_route_unverified"
                    );
        }
        if (SurvivalRouteTracker.isFoundationGoal(goal)) {
            return verified.containsAll(FOUNDATION_REQUIRED)
                    ? GoalCompletionVerification.approved()
                    : GoalCompletionVerification.rejected(
                            "foundation_route_unverified"
                    );
        }
        if (SurvivalRouteTracker.isCompletionGoal(goal)) {
            return verified.containsAll(COMPLETION_REQUIRED)
                    ? GoalCompletionVerification.approved()
                    : GoalCompletionVerification.rejected(
                            "completion_route_unverified"
                    );
        }
        return GoalCompletionVerification.approved();
    }

    @Override
    public boolean allowModelCompletionWithoutAction(
            final GoalSnapshot goal
    ) {
        Objects.requireNonNull(goal, "goal");
        return goal.source() != dev.mcai.companion.control.GoalSource.PLAYER_CHAT
                && goal.source() != dev.mcai.companion.control.GoalSource.MCP;
    }

    @Override
    public Optional<GoalCompletionVerification>
            verifyAutonomousCompletion(final GoalSnapshot goal) {
        Objects.requireNonNull(goal, "goal");
        if (!SurvivalRouteTracker.isFoundationGoal(goal)
                && !SurvivalRouteTracker.isCompletionGoal(goal)) {
            return Optional.empty();
        }
        return Optional.of(verify(goal));
    }
}
