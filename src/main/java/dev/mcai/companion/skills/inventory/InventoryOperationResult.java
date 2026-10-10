package dev.mcai.companion.skills.inventory;

import dev.mcai.companion.skill.SkillFailure;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded result of one server-thread inventory transaction.
 */
public record InventoryOperationResult(
        boolean succeeded,
        int affectedCount,
        Optional<SkillFailure> failure
) {
    public InventoryOperationResult {
        if (affectedCount < 0) {
            throw new IllegalArgumentException("affectedCount must be non-negative");
        }
        failure = Objects.requireNonNull(failure, "failure");
        if (succeeded == failure.isPresent()) {
            throw new IllegalArgumentException(
                    "Successful results cannot fail and rejected results must fail"
            );
        }
    }

    public static InventoryOperationResult success() {
        return success(0);
    }

    public static InventoryOperationResult success(final int affectedCount) {
        return new InventoryOperationResult(
                true,
                affectedCount,
                Optional.empty()
        );
    }

    public static InventoryOperationResult rejected(final String code) {
        return new InventoryOperationResult(
                false,
                0,
                Optional.of(SkillFailure.of(code))
        );
    }
}
