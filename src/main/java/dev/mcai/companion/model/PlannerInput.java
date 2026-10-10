package dev.mcai.companion.model;

import java.util.Objects;
import java.util.Optional;

public record PlannerInput(
        DecisionContext decisionContext,
        String systemPrompt,
        String observationJson,
        int maxOutputTokens,
        double temperature,
        Optional<ModelImageInput> imageInput
) {
    /*
     * The system prompt contains the bounded local skill catalogue plus up
     * to 16 KiB of trusted runtime evidence (for example a completed fair
     * survey). 24 KiB made a legal survey capable of terminating an active
     * goal before another request could be sent. Keep the per-field boundary
     * large enough for every independently accepted component while the
     * stricter 80 KiB aggregate boundary below remains authoritative.
     */
    public static final int MAX_SYSTEM_PROMPT_CHARACTERS = 48_000;
    public static final int MAX_OBSERVATION_JSON_CHARACTERS = 65_536;
    public static final int MAX_TOTAL_INPUT_CHARACTERS = 80_000;

    public PlannerInput(
        final DecisionContext decisionContext,
        final String systemPrompt,
        final String observationJson,
        final int maxOutputTokens
    ) {
        this(
            decisionContext,
            systemPrompt,
            observationJson,
            maxOutputTokens,
            0.2,
            Optional.empty()
        );
    }

    public PlannerInput(
            final DecisionContext decisionContext,
            final String systemPrompt,
            final String observationJson,
            final int maxOutputTokens,
            final double temperature
    ) {
        this(
                decisionContext,
                systemPrompt,
                observationJson,
                maxOutputTokens,
                temperature,
                Optional.empty()
        );
    }

    public PlannerInput {
        Objects.requireNonNull(decisionContext, "decisionContext");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(observationJson, "observationJson");
        Objects.requireNonNull(imageInput, "imageInput");
        if (systemPrompt.length() > MAX_SYSTEM_PROMPT_CHARACTERS
                || systemPrompt.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                "systemPrompt exceeds the local context boundary"
            );
        }
        if (observationJson.length()
                > MAX_OBSERVATION_JSON_CHARACTERS
                || observationJson.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                "observationJson exceeds the local context boundary"
            );
        }
        final long totalCharacters =
            (long) systemPrompt.length() + observationJson.length();
        if (totalCharacters > MAX_TOTAL_INPUT_CHARACTERS) {
            throw new IllegalArgumentException(
                "Planner input exceeds the local context boundary"
            );
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 16_384) {
            throw new IllegalArgumentException("maxOutputTokens must be between 1 and 16384");
        }
        if (!Double.isFinite(temperature)
                || temperature < 0.0
                || temperature > 1.0) {
            throw new IllegalArgumentException(
                "temperature must be between 0.0 and 1.0"
            );
        }
    }
}
