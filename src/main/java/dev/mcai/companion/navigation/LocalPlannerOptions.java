package dev.mcai.companion.navigation;

import java.util.Objects;

public record LocalPlannerOptions(
    NavigationRiskProfile riskProfile,
    LocalPlanningBudget budget,
    int maximumDrop,
    boolean allowSprint,
    boolean allowBuilding
) {
    public LocalPlannerOptions {
        Objects.requireNonNull(riskProfile, "riskProfile");
        Objects.requireNonNull(budget, "budget");
        if (maximumDrop < 0 || maximumDrop > 16) {
            throw new IllegalArgumentException("Maximum drop must be between 0 and 16");
        }
    }

    public static LocalPlannerOptions normal(LocalPlanningBudget budget) {
        return new LocalPlannerOptions(
            NavigationRiskProfile.NORMAL,
            budget,
            3,
            false,
            false
        );
    }

    public static LocalPlannerOptions hardcore(LocalPlanningBudget budget) {
        return new LocalPlannerOptions(
            NavigationRiskProfile.HARDCORE,
            budget,
            3,
            false,
            false
        );
    }
}
