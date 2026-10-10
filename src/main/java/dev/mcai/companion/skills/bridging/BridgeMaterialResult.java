package dev.mcai.companion.skills.bridging;

import java.util.Objects;
import java.util.Optional;

public record BridgeMaterialResult(
        boolean ready,
        String itemId,
        int availableCount,
        Optional<String> failureCode
) {
    public BridgeMaterialResult {
        itemId = Objects.requireNonNullElse(itemId, "");
        failureCode = Objects.requireNonNull(
                failureCode,
                "failureCode"
        );
        if (availableCount < 0
                || ready == failureCode.isPresent()
                || ready && (itemId.isBlank() || availableCount < 1)
                || !ready && (!itemId.isEmpty()
                    || availableCount != 0)) {
            throw new IllegalArgumentException(
                    "Invalid bridge material result"
            );
        }
    }

    public static BridgeMaterialResult ready(
            final String itemId,
            final int count
    ) {
        return new BridgeMaterialResult(
                true,
                itemId,
                count,
                Optional.empty()
        );
    }

    public static BridgeMaterialResult failed(final String code) {
        return new BridgeMaterialResult(
                false,
                "",
                0,
                Optional.of(Objects.requireNonNull(code, "code"))
        );
    }
}
