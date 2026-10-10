package dev.mcai.companion.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class PlannerInputTest {
    private static final DecisionContext CONTEXT =
        new DecisionContext("request-1", 1L, 1L, false, Map.of());

    @Test
    void enforcesIndependentAndCombinedInputContextBoundaries() {
        assertDoesNotThrow(() -> new PlannerInput(
            CONTEXT,
            "s".repeat(
                PlannerInput.MAX_SYSTEM_PROMPT_CHARACTERS
            ),
            "{}",
            2_048
        ));
        assertThrows(IllegalArgumentException.class, () ->
            new PlannerInput(
                CONTEXT,
                "s".repeat(
                    PlannerInput.MAX_SYSTEM_PROMPT_CHARACTERS + 1
                ),
                "{}",
                2_048
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new PlannerInput(
                CONTEXT,
                "system",
                "o".repeat(
                    PlannerInput.MAX_OBSERVATION_JSON_CHARACTERS + 1
                ),
                2_048
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new PlannerInput(
                CONTEXT,
                "s".repeat(20_000),
                "o".repeat(60_001),
                2_048
            )
        );
    }

    @Test
    void rejectsNulBearingTrustedOrObservationInput() {
        assertThrows(IllegalArgumentException.class, () ->
            new PlannerInput(CONTEXT, "system\0", "{}", 128)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new PlannerInput(CONTEXT, "system", "{\0}", 128)
        );
    }

    @Test
    void constrainsProviderTemperatureToTheConfiguredUiRange() {
        assertDoesNotThrow(() ->
            new PlannerInput(CONTEXT, "system", "{}", 128, 0.0)
        );
        assertDoesNotThrow(() ->
            new PlannerInput(CONTEXT, "system", "{}", 128, 1.0)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new PlannerInput(CONTEXT, "system", "{}", 128, -0.1)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new PlannerInput(CONTEXT, "system", "{}", 128, 1.1)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new PlannerInput(
                CONTEXT,
                "system",
                "{}",
                128,
                Double.NaN
            )
        );
    }
}
