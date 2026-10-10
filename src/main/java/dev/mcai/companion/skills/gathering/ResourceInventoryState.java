package dev.mcai.companion.skills.gathering;

/**
 * Live self-owned inventory capacity, never world or container contents.
 */
public record ResourceInventoryState(
        long sessionGeneration,
        int emptyMainInventorySlots
) {
    public ResourceInventoryState {
        if (sessionGeneration < 0
                || emptyMainInventorySlots < 0
                || emptyMainInventorySlots > 36) {
            throw new IllegalArgumentException(
                    "Invalid resource inventory state"
            );
        }
    }
}
