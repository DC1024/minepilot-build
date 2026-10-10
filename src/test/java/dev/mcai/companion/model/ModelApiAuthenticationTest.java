package dev.mcai.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.Test;

class ModelApiAuthenticationTest {
    @Test
    void usesDocumentedApiKeyHeaderForXiaomiHosts() {
        ModelEndpoint endpoint = new ModelEndpoint(
                URI.create("https://token-plan-cn.xiaomimimo.com/v1"),
                "mimo-v2.5"
        );
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                endpoint.endpoint(Protocol.CHAT_COMPLETIONS)
        );
        char[] secret = "tp-contract-secret".toCharArray();

        ModelApiAuthentication.apply(builder, endpoint, secret);
        HttpRequest request = builder.build();

        assertEquals("tp-contract-secret", request.headers()
                .firstValue("api-key")
                .orElseThrow());
        assertTrue(request.headers().firstValue("Authorization").isEmpty());
        java.util.Arrays.fill(secret, '\0');
    }

    @Test
    void retainsBearerHeaderForOtherOpenAiCompatibleHosts() {
        ModelEndpoint endpoint = new ModelEndpoint(
                URI.create("https://example.test/v1"),
                "model"
        );
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                endpoint.endpoint(Protocol.RESPONSES)
        );
        char[] secret = "opaque-contract-secret".toCharArray();

        ModelApiAuthentication.apply(builder, endpoint, secret);
        HttpRequest request = builder.build();

        assertEquals("Bearer opaque-contract-secret", request.headers()
                .firstValue("Authorization")
                .orElseThrow());
        assertFalse(request.headers().firstValue("api-key").isPresent());
        java.util.Arrays.fill(secret, '\0');
    }
}
