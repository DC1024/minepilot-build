package dev.mcai.companion.skills.memory;

import java.util.Optional;

@FunctionalInterface
public interface CurrentPositionSource {
    Optional<ObservedCurrentPosition> current();
}
