package dev.mcai.companion.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EndpointValidatorTest {
    private final EndpointValidator validator = new EndpointValidator();

    @Test
    void normalizesApiPrefixAndBuildsProtocolEndpoints() throws Exception {
        ModelEndpoint endpoint = validator.validate(
                "https://API.OpenAI.com/v1/",
                "gpt-test"
        );

        assertEquals("https://api.openai.com/v1", endpoint.baseUri().toString());
        assertEquals(
                "https://api.openai.com/v1/responses",
                endpoint.endpoint(Protocol.RESPONSES).toString()
        );
        assertEquals(
                "https://api.openai.com/v1/chat/completions",
                endpoint.endpoint(Protocol.CHAT_COMPLETIONS).toString()
        );
    }

    @Test
    void defaultsBareProviderHostToTheOpenAiCompatibleV1Prefix() throws Exception {
        ModelEndpoint endpoint = validator.validate(
                "https://api.example.test",
                "mimo-v2.5"
        );

        assertEquals("https://api.example.test/v1", endpoint.baseUri().toString());
        assertEquals(
                "https://api.example.test/v1/responses",
                endpoint.endpoint(Protocol.RESPONSES).toString()
        );
        assertEquals(
                "https://api.example.test/v1/chat/completions",
                endpoint.endpoint(Protocol.CHAT_COMPLETIONS).toString()
        );
    }

    @Test
    void allowsOnlyLoopbackForPlainHttp() throws Exception {
        ModelEndpoint endpoint = validator.validate(
                "http://127.0.0.1:8080/v1",
                "local-model"
        );
        assertEquals("http://127.0.0.1:8080/v1", endpoint.baseUri().toString());

        EndpointValidationException exception = assertThrows(
                EndpointValidationException.class,
                () -> validator.validate("http://example.com/v1", "model")
        );
        assertEquals("insecure_remote_http", exception.code());
    }

    @Test
    void acceptsIpv6Loopback() throws Exception {
        ModelEndpoint endpoint = validator.validate(
                "http://[::1]:8080/v1",
                "local-model"
        );
        assertEquals(
                "http://[::1]:8080/v1/chat/completions",
                endpoint.endpoint(Protocol.CHAT_COMPLETIONS).toString()
        );
    }

    @Test
    void rejectsCredentialsQueriesFragmentsTraversalAndConcreteEndpoints() {
        assertThrows(
                EndpointValidationException.class,
                () -> validator.validate("https://user:pass@example.com/v1", "model")
        );
        assertThrows(
                EndpointValidationException.class,
                () -> validator.validate("https://example.com/v1?debug=true", "model")
        );
        assertThrows(
                EndpointValidationException.class,
                () -> validator.validate("https://example.com/v1#fragment", "model")
        );
        assertThrows(
                EndpointValidationException.class,
                () -> validator.validate("https://example.com/v1/../admin", "model")
        );
        assertThrows(
                EndpointValidationException.class,
                () -> validator.validate("https://example.com/v1/responses", "model")
        );
        assertThrows(
                EndpointValidationException.class,
                () -> validator.validate("https://example.com/v1/chat/completions", "model")
        );
    }

    @Test
    void rejectsWhitespaceAndControlCharacters() {
        assertThrows(
                EndpointValidationException.class,
                () -> validator.validate(" https://example.com/v1", "model")
        );
        assertThrows(
                EndpointValidationException.class,
                () -> validator.validate("https://example.com/v1", "bad model")
        );
    }
}
