package dev.mcai.companion.mechanism;

import java.util.Objects;
import java.util.Optional;

/** Bounded result of one fair site constraint search. */
public record MechanismPlanningResult(
        Optional<MechanismPlan> plan,
        Optional<String> failureCode
) {
    public MechanismPlanningResult {
        plan = Objects.requireNonNull(plan, "plan");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        if (plan.isPresent() == failureCode.isPresent()) {
            throw new IllegalArgumentException(
                    "Mechanism result must be planned or failed"
            );
        }
    }

    public static MechanismPlanningResult planned(
            final MechanismPlan plan
    ) {
        return new MechanismPlanningResult(
                Optional.of(Objects.requireNonNull(plan, "plan")),
                Optional.empty()
        );
    }

    public static MechanismPlanningResult failed(final String code) {
        final String checked = Objects.requireNonNull(code, "code");
        if (!checked.matches("[a-z0-9_.-]{1,96}")) {
            throw new IllegalArgumentException("Invalid failure code");
        }
        return new MechanismPlanningResult(
                Optional.empty(),
                Optional.of(checked)
        );
    }
}
