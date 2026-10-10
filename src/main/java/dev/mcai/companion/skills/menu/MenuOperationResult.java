package dev.mcai.companion.skills.menu;

import dev.mcai.companion.skill.SkillFailure;
import java.util.Objects;
import java.util.Optional;

public record MenuOperationResult(
        boolean succeeded,
        int affectedCount,
        Optional<SkillFailure> failure
) {
    public MenuOperationResult {
        if (affectedCount < 0) {
            throw new IllegalArgumentException(
                    "affectedCount must be non-negative"
            );
        }
        failure = Objects.requireNonNull(failure, "failure");
        if (succeeded == failure.isPresent()) {
            throw new IllegalArgumentException(
                    "A menu result requires exactly one outcome"
            );
        }
    }

    public static MenuOperationResult success() {
        return success(0);
    }

    public static MenuOperationResult success(final int affectedCount) {
        return new MenuOperationResult(
                true,
                affectedCount,
                Optional.empty()
        );
    }

    public static MenuOperationResult rejected(final String code) {
        return new MenuOperationResult(
                false,
                0,
                Optional.of(SkillFailure.of(code))
        );
    }
}
