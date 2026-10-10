package dev.mcai.companion.skills.menu;

import java.util.Objects;

/**
 * One bounded batch in an already-open vanilla furnace-family menu.
 *
 * <p>The model names only items visible in the player's open menu. The local
 * skill binds the current semantic sample, transfers through vanilla menu
 * clicks, waits for normal furnace ticks, and takes only the requested
 * observed output.</p>
 */
public record SmeltMenuBatchParameters(
        long sampleSequence,
        String inputItemId,
        String outputItemId,
        int count,
        String fuelItemId,
        int fuelCount
) {
    private static final String IDENTIFIER =
            "[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}";

    public SmeltMenuBatchParameters {
        if (sampleSequence < 0
                || count < 1
                || count > 64
                || fuelCount < 1
                || fuelCount > 64) {
            throw new IllegalArgumentException(
                    "Smelting counters are outside their bounds"
            );
        }
        inputItemId = identifier(inputItemId);
        outputItemId = identifier(outputItemId);
        fuelItemId = identifier(fuelItemId);
        if ("minecraft:air".equals(inputItemId)
                || "minecraft:air".equals(outputItemId)
                || "minecraft:air".equals(fuelItemId)) {
            throw new IllegalArgumentException(
                    "Smelting items cannot be air"
            );
        }
    }

    private static String identifier(final String value) {
        final String normalized = Objects.requireNonNull(
                value,
                "item identifier"
        );
        if (!normalized.matches(IDENTIFIER)) {
            throw new IllegalArgumentException(
                    "Smelting item identifier is invalid"
            );
        }
        return normalized;
    }
}
