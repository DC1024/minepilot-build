package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class CompanionRuntimeEnvironmentProfileTest {
    @Test
    void acceptsCompleteValidatedDedicatedServerProfile() {
        final var profile = CompanionRuntime.injectedModelProfile(
                Map.of(
                        "MCAI_BASE_URL",
                        "https://provider.example/v1",
                        "MCAI_MODEL",
                        "model-1"
                )
        ).orElseThrow();

        assertEquals(
                "https://provider.example/v1",
                profile.baseUrl()
        );
        assertEquals("model-1", profile.modelName());
    }

    @Test
    void trimsInjectedCoordinatesWithoutPersistingAKey() {
        final var profile = CompanionRuntime.injectedModelProfile(
                Map.of(
                        "MCAI_BASE_URL",
                        "  https://provider.example/v1/  ",
                        "MCAI_MODEL",
                        "  model-2  ",
                        "MCAI_API_KEY",
                        "must-not-be-read-here"
                )
        ).orElseThrow();

        assertEquals(
                "https://provider.example/v1",
                profile.baseUrl()
        );
        assertEquals("model-2", profile.modelName());
    }

    @Test
    void rejectsPartialOrInvalidOverrides() {
        assertTrue(CompanionRuntime.injectedModelProfile(
                Map.of("MCAI_BASE_URL", "https://provider.example/v1")
        ).isEmpty());
        assertTrue(CompanionRuntime.injectedModelProfile(
                Map.of(
                        "MCAI_BASE_URL",
                        "http://public.example/v1",
                        "MCAI_MODEL",
                        "model"
                )
        ).isEmpty());
    }
}
