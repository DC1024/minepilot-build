package dev.mcai.companion.model;

import java.util.List;
import java.util.Objects;

/**
 * The complete and deliberately small model-to-runtime decision contract.
 *
 * <p>All fields are required on the wire. Empty strings and empty lists are
 * used instead of nullable fields so the same schema works across providers
 * with different strict-JSON subsets.</p>
 */
public record DecisionEnvelope(
        String requestId,
        long observedWorldRevision,
        long goalRevision,
        DecisionKind decision,
        String skillName,
        List<SkillArgument> typedArguments,
        RequestedObservation requestedObservation,
        String optionalSpeech,
        double confidence
) {
    public DecisionEnvelope {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(skillName, "skillName");
        typedArguments = List.copyOf(Objects.requireNonNull(typedArguments, "typedArguments"));
        Objects.requireNonNull(requestedObservation, "requestedObservation");
        Objects.requireNonNull(optionalSpeech, "optionalSpeech");
    }
}
