package dev.mcai.companion.skill;

import java.util.Objects;
import java.util.Optional;

/**
 * A terminal local skill outcome.
 */
public record SkillResult(Status status, Optional<SkillFailure> failure) {
    public enum Status {
        COMPLETED,
        FAILED,
        CANCELLED,
        SAFE_IDLE
    }

    public SkillResult {
        Objects.requireNonNull(status, "status");
        failure = Objects.requireNonNull(failure, "failure");
        boolean requiresFailure = status == Status.FAILED || status == Status.SAFE_IDLE;
        if (requiresFailure != failure.isPresent()) {
            throw new IllegalArgumentException(
                    "FAILED and SAFE_IDLE require exactly one bounded failure"
            );
        }
    }

    public static SkillResult completed() {
        return new SkillResult(Status.COMPLETED, Optional.empty());
    }

    public static SkillResult failed(SkillFailure failure) {
        return new SkillResult(Status.FAILED, Optional.of(failure));
    }

    public static SkillResult cancelled() {
        return new SkillResult(Status.CANCELLED, Optional.empty());
    }

    public static SkillResult safeIdle(SkillFailure reason) {
        return new SkillResult(Status.SAFE_IDLE, Optional.of(reason));
    }
}
