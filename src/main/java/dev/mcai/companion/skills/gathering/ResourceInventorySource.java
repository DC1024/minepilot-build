package dev.mcai.companion.skills.gathering;

import java.util.Optional;

@FunctionalInterface
public interface ResourceInventorySource {
    Optional<ResourceInventoryState> current();
}
