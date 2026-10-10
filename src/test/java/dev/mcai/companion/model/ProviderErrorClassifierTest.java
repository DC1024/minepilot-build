package dev.mcai.companion.model;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderErrorClassifierTest {
    private final ProviderErrorClassifier classifier = new ProviderErrorClassifier();

    @Test
    void neverTreatsAuthenticationOrRateLimitAsCapabilityFallback() {
        ModelFailure authentication = classify(
                401,
                "{\"error\":{\"message\":\"bad key\",\"type\":\"auth\",\"code\":\"invalid_api_key\"}}",
                Set.of("response_format"),
                Map.of()
        );
        assertEquals(ModelFailureKind.AUTHENTICATION, authentication.kind());
        assertFalse(authentication.allowsCapabilityFallback());

        ModelFailure rateLimit = classify(
                429,
                "{\"error\":{\"message\":\"slow down\",\"code\":\"rate_limit\"}}",
                Set.of("response_format"),
                Map.of("retry-after", List.of("7"))
        );
        assertEquals(ModelFailureKind.RATE_LIMITED, rateLimit.kind());
        assertEquals(Duration.ofSeconds(7), rateLimit.retryAfter().orElseThrow());
        assertFalse(rateLimit.allowsCapabilityFallback());
    }

    @Test
    void separatesModelNotFoundFromEndpointNotFound() {
        assertEquals(
                ModelFailureKind.MODEL_NOT_FOUND,
                classify(
                        404,
                        "{\"error\":{\"message\":\"unknown model\",\"param\":\"model\","
                                + "\"code\":\"model_not_found\"}}",
                        Set.of(),
                        Map.of()
                ).kind()
        );
        assertEquals(
                ModelFailureKind.MODEL_NOT_FOUND,
                classify(
                        503,
                        "{\"error\":{\"message\":\"model unavailable\","
                                + "\"code\":\"model_not_found\"}}",
                        Set.of(),
                        Map.of()
                ).kind()
        );
        ModelFailure endpoint = classify(
                404,
                "{\"error\":{\"message\":\"route not found\"}}",
                Set.of(),
                Map.of()
        );
        assertEquals(ModelFailureKind.ENDPOINT_UNSUPPORTED, endpoint.kind());
        assertTrue(endpoint.allowsCapabilityFallback());
    }

    @Test
    void downgradesOnlyWhenOptionalCapabilityFieldIsExplicitlyRejected() {
        ModelFailure capability = classify(
                400,
                "{\"error\":{\"message\":\"Unsupported parameter: response_format\","
                        + "\"param\":\"response_format\",\"code\":\"invalid_request\"}}",
                Set.of("response_format"),
                Map.of()
        );
        assertEquals(ModelFailureKind.CAPABILITY_UNSUPPORTED, capability.kind());

        ModelFailure unrelated = classify(
                400,
                "{\"error\":{\"message\":\"Invalid request body\",\"param\":\"model\"}}",
                Set.of("response_format"),
                Map.of()
        );
        assertEquals(ModelFailureKind.INVALID_REQUEST, unrelated.kind());

        ModelFailure malformedCapabilityValue = classify(
                400,
                "{\"error\":{\"message\":\"response_format must be an object\","
                        + "\"param\":\"response_format\",\"code\":\"invalid_request\"}}",
                Set.of("response_format"),
                Map.of()
        );
        assertEquals(
                ModelFailureKind.INVALID_REQUEST,
                malformedCapabilityValue.kind()
        );
        assertFalse(malformedCapabilityValue.allowsCapabilityFallback());

        ModelFailure unrelatedSubstring = classify(
                400,
                "{\"error\":{\"message\":\"unsupported context window\","
                        + "\"code\":\"unsupported_value\"}}",
                Set.of("text"),
                Map.of()
        );
        assertEquals(ModelFailureKind.INVALID_REQUEST, unrelatedSubstring.kind());
    }

    @Test
    void recognizesOnlyExplicitEndpointRejectionOnValidationStatuses() {
        ModelFailure unsupported = classify(
                400,
                "{\"error\":{\"message\":\"This endpoint is not supported\","
                        + "\"code\":\"unsupported_endpoint\"}}",
                Set.of(),
                Map.of()
        );
        assertEquals(ModelFailureKind.ENDPOINT_UNSUPPORTED, unsupported.kind());
        assertTrue(unsupported.allowsCapabilityFallback());

        ModelFailure invalid = classify(
                400,
                "{\"error\":{\"message\":\"endpoint must be a string\","
                        + "\"code\":\"invalid_request\"}}",
                Set.of(),
                Map.of()
        );
        assertEquals(ModelFailureKind.INVALID_REQUEST, invalid.kind());
        assertFalse(invalid.allowsCapabilityFallback());
    }

    @Test
    void recognizesExplicitUnderscoreAliasForNestedCapabilityField() {
        final ModelFailure capability = classify(
                400,
                "{\"error\":{\"message\":\"This model does not support "
                        + "`reasoning_effort` value `none`.\","
                        + "\"code\":\"bad_response_status_code\","
                        + "\"param\":\"\"}}",
                Set.of("reasoning", "reasoning.effort"),
                Map.of()
        );

        assertEquals(
                ModelFailureKind.CAPABILITY_UNSUPPORTED,
                capability.kind()
        );
        assertEquals("reasoning.effort", capability.providerParam());
        assertTrue(capability.allowsCapabilityFallback());
    }

    @Test
    void understandsMinimaxStyleBusinessErrorsInsideHttp200() {
        ModelFailure failure = classifier.classify(
                200,
                HttpHeaders.of(Map.of(), (left, right) -> true),
                "{\"base_resp\":{\"status_code\":1008,\"status_msg\":\"no balance\"}}",
                "req",
                Set.of()
        ).orElseThrow();

        assertEquals(ModelFailureKind.BILLING, failure.kind());
        assertFalse(failure.diagnosticHash().isEmpty());
        assertFalse(failure.safeMessage().contains("no balance"));
    }

    private ModelFailure classify(
            int status,
            String body,
            Set<String> fields,
            Map<String, List<String>> headers
    ) {
        return classifier.classify(
                status,
                HttpHeaders.of(headers, (left, right) -> true),
                body,
                "req",
                fields
        ).orElseThrow();
    }
}
