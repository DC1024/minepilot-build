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
    /**
     * Failure code for a transaction refused by the per-tick budget. Kept
     * identical to the menu-side code so a single helper can map both results
     * without depending on either concrete type.
     */
    public static final String THROTTLED_CODE = "inventory.operation_throttled";

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

    /**
     * Rejects a transaction the per-tick operation budget already spent.
     *
     * <p>Nothing ran and nothing changed, so the caller can retry on a later
     * tick without any compensating action.</p>
     */
    public static InventoryOperationResult throttled() {
        return rejected(THROTTLED_CODE);
    }
}
