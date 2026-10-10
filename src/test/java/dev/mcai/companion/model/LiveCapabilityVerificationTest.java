package dev.mcai.companion.model;

import dev.mcai.companion.credential.ApiKeyManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit one-shot verification used during provider bring-up.
 *
 * <p>It is disabled in ordinary builds and intentionally performs only
 * capability negotiation plus one image handshake, so the preferred
 * Responses path consumes at most three provider requests.</p>
 */
@EnabledIfEnvironmentVariable(
        named = "MCAI_LIVE_CAPABILITY_TEST",
        matches = "true"
)
final class LiveCapabilityVerificationTest {
    @Test
    void verifiesConfiguredProviderWithoutExposingItsCredential() throws Exception {
        String baseUrl = requiredEnvironment("MCAI_LIVE_BASE_URL");
        String modelName = requiredEnvironment("MCAI_LIVE_MODEL");
        ModelEndpoint endpoint = new EndpointValidator().validate(baseUrl, modelName);

        try (ApiKeyManager keys = new ApiKeyManager()) {
            if (!keys.unlockPersisted()) {
                throw new IllegalStateException(
                        "No persisted provider credential is available"
                );
            }
            try (JdkProviderCapabilityProbe probe =
                         new JdkProviderCapabilityProbe(
                                 endpoint,
                                 keys,
                                 Duration.ofSeconds(5),
                                 Duration.ofSeconds(90),
                                 true
                         )) {
                CapabilityProbeOutcome outcome = probe.probe()
                        .toCompletableFuture()
                        .get(300, TimeUnit.SECONDS);
                CapabilityProbeOutcome.Supported supported = assertInstanceOf(
                        CapabilityProbeOutcome.Supported.class,
                        outcome,
                        () -> safeFailure(outcome)
                );
                assertTrue(
                        supported.capabilities().imageInput(),
                        "The configured model did not accept image input"
                );
                assertTrue(
                        supported.requestsMade() <= 3,
                        "Verification exceeded its three-request safety budget"
                );
            }
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String safeFailure(CapabilityProbeOutcome outcome) {
        if (outcome instanceof CapabilityProbeOutcome.Failure failure) {
            return "Provider capability verification stopped with "
                    + failure.error().kind()
                    + " after "
                    + failure.requestsMade()
                    + " request(s): "
                    + failure.error().safeMessage();
        }
        return "Provider capability verification returned no supported profile";
    }
}
