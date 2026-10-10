package dev.mcai.companion.skills.building;

import dev.mcai.companion.skill.SkillFailure;
import java.util.Objects;
import java.util.Optional;

public record ShelterPlanningResult(
        Optional<ShelterPlan> plan,
        Optional<SkillFailure> failure
) {
    public ShelterPlanningResult {
        plan = Objects.requireNonNull(plan, "plan");
        failure = Objects.requireNonNull(failure, "failure");
        if (plan.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException(
                    "Planning requires exactly one outcome"
            );
        }
    }

    public static ShelterPlanningResult planned(ShelterPlan plan) {
        return new ShelterPlanningResult(
                Optional.of(Objects.requireNonNull(plan, "plan")),
                Optional.empty()
        );
    }

    public static ShelterPlanningResult failed(String code) {
        return new ShelterPlanningResult(
                Optional.empty(),
                Optional.of(SkillFailure.of(code))
        );
    }
}
