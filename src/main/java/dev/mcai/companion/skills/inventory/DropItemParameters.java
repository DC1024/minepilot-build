package dev.mcai.companion.skills.inventory;

import java.util.Objects;

public record DropItemParameters(String itemId, int count) {
    public DropItemParameters {
        itemId = Objects.requireNonNull(itemId, "itemId");
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("count must be between 1 and 64");
        }
    }
}
