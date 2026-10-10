package dev.mcai.companion.model;

import java.util.Objects;

public sealed interface ModelOutcome permits ModelOutcome.Success, ModelOutcome.Failure {
    record Success(
            DecisionEnvelope decision,
            TokenUsage usage,
            RequestTrace trace
    ) implements ModelOutcome {
        public Success {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(usage, "usage");
            Objects.requireNonNull(trace, "trace");
        }
    }

    record Failure(ModelFailure error) implements ModelOutcome {
        public Failure {
            Objects.requireNonNull(error, "error");
        }
    }
}
