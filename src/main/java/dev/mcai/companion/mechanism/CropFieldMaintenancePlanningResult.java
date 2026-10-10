package dev.mcai.companion.mechanism;

import java.util.Objects;
import java.util.Optional;

public record CropFieldMaintenancePlanningResult(
        Optional<CropFieldMaintenancePlan> plan,
        Optional<String> failureCode
) {
    public CropFieldMaintenancePlanningResult {
        plan = Objects.requireNonNull(plan, "plan");
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        if (plan.isPresent() == failureCode.isPresent()) {
            throw new IllegalArgumentException(
                    "Exactly one planning outcome is required"
            );
        }
    }

    public static CropFieldMaintenancePlanningResult planned(
            final CropFieldMaintenancePlan plan
    ) {
        return new CropFieldMaintenancePlanningResult(
                Optional.of(Objects.requireNonNull(plan, "plan")),
                Optional.empty()
        );
    }

    public static CropFieldMaintenancePlanningResult failed(
            final String code
    ) {
        return new CropFieldMaintenancePlanningResult(
                Optional.empty(),
                Optional.of(Objects.requireNonNull(code, "code"))
        );
    }
}
