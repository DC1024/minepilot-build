package dev.mcai.companion.navigation;

import java.time.Duration;
import java.util.Objects;

public record LocalPlanningBudget(int maximumExpandedNodes, Duration maximumWallTime) {
    public static final int MAXIMUM_NODE_LIMIT = 1_000_000;
    public static final Duration MAXIMUM_TIME_LIMIT = Duration.ofSeconds(10);

    public LocalPlanningBudget {
        Objects.requireNonNull(maximumWallTime, "maximumWallTime");
        if (maximumExpandedNodes < 1 || maximumExpandedNodes > MAXIMUM_NODE_LIMIT) {
            throw new IllegalArgumentException("Expanded-node budget is out of range");
        }
        if (maximumWallTime.isZero()
            || maximumWallTime.isNegative()
            || maximumWallTime.compareTo(MAXIMUM_TIME_LIMIT) > 0) {
            throw new IllegalArgumentException("Wall-time budget is out of range");
        }
    }

    public static LocalPlanningBudget interactive() {
        return new LocalPlanningBudget(25_000, Duration.ofMillis(50));
    }
}
