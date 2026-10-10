package dev.mcai.companion.brain;

import java.util.Objects;

/**
 * Server-side result for a model's request to finish an ordinary goal.
 */
public record GoalCompletionVerification(
        boolean accepted,
        String detailCode
) {
    public GoalCompletionVerification {
        detailCode = Objects.requireNonNull(detailCode, "detailCode")
                .strip();
        if (!detailCode.matches("[a-z0-9_.-]{1,96}")) {
            throw new IllegalArgumentException(
                    "Goal completion detail code is invalid"
            );
        }
    }

    public static GoalCompletionVerification approved() {
        return new GoalCompletionVerification(
                true,
                "goal_completion_verified"
        );
    }

    public static GoalCompletionVerification rejected(
            final String detailCode
    ) {
        return new GoalCompletionVerification(false, detailCode);
    }
}
