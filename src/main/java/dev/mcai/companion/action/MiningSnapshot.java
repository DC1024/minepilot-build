package dev.mcai.companion.action;

import java.util.Objects;

public record MiningSnapshot(
        BlockInteractionTarget target,
        MiningPhase phase,
        long startedAtGameTime,
        long lastContinuedAtGameTime
) {
    public MiningSnapshot {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(phase, "phase");
        if (startedAtGameTime < 0 || lastContinuedAtGameTime < startedAtGameTime) {
            throw new IllegalArgumentException("Invalid mining timestamps");
        }
    }
}
