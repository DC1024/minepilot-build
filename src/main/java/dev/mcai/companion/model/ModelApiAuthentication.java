package dev.mcai.companion.model;

import java.net.http.HttpRequest;
import java.util.Locale;
import java.util.Objects;

/**
 * Applies the provider's documented API-key header without exposing the
 * credential to any caller or evidence record.
 *
 * <p>Most OpenAI-compatible endpoints use {@code Authorization: Bearer}.
 * Xiaomi MiMo's OpenAI-compatible endpoint documents {@code api-key}
 * instead, including its {@code tp-} Token Plan credentials.  Header choice
 * is pinned to the validated endpoint host, never inferred from model output
 * or a response body.</p>
 */
final class ModelApiAuthentication {
    private ModelApiAuthentication() {
    }

    static void apply(
            final HttpRequest.Builder request,
            final ModelEndpoint endpoint,
            final char[] credential
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(credential, "credential");
        final String value = new String(credential);
        if (usesApiKeyHeader(endpoint)) {
            request.header("api-key", value);
        } else {
            request.header("Authorization", "Bearer " + value);
        }
    }

    static boolean usesApiKeyHeader(final ModelEndpoint endpoint) {
        final String host = endpoint.baseUri().getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        final String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("xiaomimimo.com")
                || normalized.endsWith(".xiaomimimo.com");
    }
}
