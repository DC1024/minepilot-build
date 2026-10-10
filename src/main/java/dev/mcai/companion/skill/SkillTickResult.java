package dev.mcai.companion.skill;

import java.util.Objects;
import java.util.Optional;

/**
 * The bounded outcome of one 20 TPS local skill step.
 */
public record SkillTickResult(
        Status status,
        boolean madeProgress,
        boolean safeCheckpoint,
        Optional<SkillFailure> failure
) {
    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED
    }

    public SkillTickResult {
        Objects.requireNonNull(status, "status");
        failure = Objects.requireNonNull(failure, "failure");
        if (status == Status.FAILED && failure.isEmpty()) {
            throw new IllegalArgumentException("FAILED requires a failure");
        }
        if (status != Status.FAILED && failure.isPresent()) {
            throw new IllegalArgumentException("Only FAILED may contain a failure");
        }
        if (status == Status.COMPLETED && !safeCheckpoint) {
            throw new IllegalArgumentException("COMPLETED must be a safe checkpoint");
        }
    }

    public static SkillTickResult running(boolean madeProgress, boolean safeCheckpoint) {
        return new SkillTickResult(
                Status.RUNNING,
                madeProgress,
                safeCheckpoint,
                Optional.empty()
        );
    }

    public static SkillTickResult completed() {
        return new SkillTickResult(Status.COMPLETED, true, true, Optional.empty());
    }

    public static SkillTickResult failed(String failureCode) {
        return failed(SkillFailure.of(failureCode));
    }

    public static SkillTickResult failed(SkillFailure failure) {
        return new SkillTickResult(Status.FAILED, false, false, Optional.of(failure));
    }
}
