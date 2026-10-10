package dev.mcai.companion.skills.core;

import dev.mcai.companion.navigation.LocalPlanningBudget;
import java.time.Duration;
import java.util.Objects;

public record CoreSkillPolicy(
        LocalPlanningBudget planningBudget,
        int scanTurnIntervalTicks,
        float scanYawDegrees,
        int maximumScanTurns,
        double movementAlignmentDegrees,
        double interactionAlignmentDegrees,
        double hardcoreMaximumDanger
) {
    public static final Duration MAXIMUM_SKILL_PLANNING_TIME =
            Duration.ofMillis(2);

    public CoreSkillPolicy {
        Objects.requireNonNull(planningBudget, "planningBudget");
        if (planningBudget.maximumWallTime().compareTo(
                MAXIMUM_SKILL_PLANNING_TIME
        ) > 0
                || scanTurnIntervalTicks < 1
                || scanTurnIntervalTicks > 40
                || !Float.isFinite(scanYawDegrees)
                || scanYawDegrees < 5.0F
                || scanYawDegrees > 90.0F
                || maximumScanTurns < 4
                || maximumScanTurns > 128
                || !Double.isFinite(movementAlignmentDegrees)
                || movementAlignmentDegrees < 1.0
                || movementAlignmentDegrees > 45.0
                || !Double.isFinite(interactionAlignmentDegrees)
                || interactionAlignmentDegrees < 0.5
                || interactionAlignmentDegrees > 15.0
                || !Double.isFinite(hardcoreMaximumDanger)
                || hardcoreMaximumDanger < 0.0
                || hardcoreMaximumDanger > 1.0) {
            throw new IllegalArgumentException("Core skill policy is outside hard bounds");
        }
    }

    public static CoreSkillPolicy defaults() {
        return new CoreSkillPolicy(
                new LocalPlanningBudget(2_048, MAXIMUM_SKILL_PLANNING_TIME),
                4,
                30.0F,
                24,
                12.0,
                3.0,
                0.10
        );
    }
}
