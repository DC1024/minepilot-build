package dev.mcai.companion.brain;

import dev.mcai.companion.skill.SkillContext;

import java.util.Objects;

/**
 * One fair semantic observation and the matching local-skill body context.
 */
public record BrainObservation(
        long epoch,
        SkillContext skillContext,
        String semanticJson,
        String trustedRuntimeJson
) {
    public static final int MAX_SEMANTIC_JSON_CHARACTERS = 1_048_576;
    public static final int MAX_TRUSTED_RUNTIME_JSON_CHARACTERS = 16_384;

    public BrainObservation(
            final long epoch,
            final SkillContext skillContext,
            final String semanticJson
    ) {
        this(epoch, skillContext, semanticJson, "{}");
    }

    public BrainObservation {
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must be non-negative");
        }
        Objects.requireNonNull(skillContext, "skillContext");
        semanticJson = Objects.requireNonNull(semanticJson, "semanticJson");
        trustedRuntimeJson = Objects.requireNonNull(
            trustedRuntimeJson,
            "trustedRuntimeJson"
        );
        if (epoch != skillContext.worldRevision()) {
            throw new IllegalArgumentException(
                    "Observation epoch and skill world revision must match"
            );
        }
        if (semanticJson.length() > MAX_SEMANTIC_JSON_CHARACTERS) {
            throw new IllegalArgumentException("semanticJson exceeds the local limit");
        }
        if (trustedRuntimeJson.length() > MAX_TRUSTED_RUNTIME_JSON_CHARACTERS
                || trustedRuntimeJson.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                "trustedRuntimeJson exceeds the local limit"
            );
        }
    }
}
