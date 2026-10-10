package dev.mcai.companion.skill;

import java.time.Duration;
import java.util.Objects;

/**
 * Local safety and liveness limits for the 20 TPS supervisor.
 */
public record SkillRuntimePolicy(
        Duration tickBudget,
        Duration skillTimeout,
        int maxConsecutiveNoProgressTicks,
        double hardcoreRiskThreshold,
        double disconnectedRiskThreshold,
        int maxConsecutiveTickBudgetBreaches
) {
    private static final int DEFAULT_CONSECUTIVE_BUDGET_BREACHES = 3;

    public static final SkillRuntimePolicy DEFAULT = new SkillRuntimePolicy(
            Duration.ofMillis(2),
            Duration.ofMinutes(5),
            200,
            0.35,
            0.15,
            DEFAULT_CONSECUTIVE_BUDGET_BREACHES
    );

    public SkillRuntimePolicy {
        tickBudget = requirePositive(tickBudget, "tickBudget");
        skillTimeout = requirePositive(skillTimeout, "skillTimeout");
        if (maxConsecutiveNoProgressTicks < 1) {
            throw new IllegalArgumentException(
                    "maxConsecutiveNoProgressTicks must be positive"
            );
        }
        requireProbability(hardcoreRiskThreshold, "hardcoreRiskThreshold");
        requireProbability(disconnectedRiskThreshold, "disconnectedRiskThreshold");
        if (maxConsecutiveTickBudgetBreaches < 1) {
            throw new IllegalArgumentException(
                "maxConsecutiveTickBudgetBreaches must be positive"
            );
        }
    }

    /**
     * Compatibility constructor using the production default sustained
     * overrun gate. A wall-clock sample can include JIT, GC, or scheduler
     * pauses, so one sample is never treated as proof of runaway skill code.
     */
    public SkillRuntimePolicy(
            final Duration tickBudget,
            final Duration skillTimeout,
            final int maxConsecutiveNoProgressTicks,
            final double hardcoreRiskThreshold,
            final double disconnectedRiskThreshold
    ) {
        this(
            tickBudget,
            skillTimeout,
            maxConsecutiveNoProgressTicks,
            hardcoreRiskThreshold,
            disconnectedRiskThreshold,
            DEFAULT_CONSECUTIVE_BUDGET_BREACHES
        );
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        try {
            duration.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " is too large", exception);
        }
        return duration;
    }

    private static void requireProbability(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between zero and one");
        }
    }
}
