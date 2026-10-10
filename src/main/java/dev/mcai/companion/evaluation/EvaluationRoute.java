package dev.mcai.companion.evaluation;

import dev.mcai.companion.control.GoalCoordinator;

/**
 * Locked hidden-seed route selected before the only autonomous command.
 */
public enum EvaluationRoute {
    COMPLETION(GoalCoordinator.HARDCORE_INITIAL_GOAL),
    FOUNDATION(GoalCoordinator.HARDCORE_FOUNDATION_GOAL);

    private final String initialGoal;

    EvaluationRoute(final String initialGoal) {
        this.initialGoal = initialGoal;
    }

    public String initialGoal() {
        return initialGoal;
    }
}
