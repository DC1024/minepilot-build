package dev.mcai.companion.model;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Validates a user-supplied OpenAI-compatible API prefix before a secret can
 * be attached to a request.
 */
public final class EndpointValidator {
    private static final int MAX_BASE_URL_CHARS = 2_048;
    private static final int MAX_MODEL_NAME_CHARS = 256;

    public ModelEndpoint validate(String rawBaseUrl, String rawModelName)
            throws EndpointValidationException {
        String baseUrl = requireTrimmed(rawBaseUrl, "base_url", MAX_BASE_URL_CHARS);
        String modelName = requireTrimmed(rawModelName, "model_name", MAX_MODEL_NAME_CHARS);
        validateNoControlCharacters(modelName, "model_name");

        final URI parsed;
        try {
            parsed = new URI(baseUrl);
        } catch (URISyntaxException exception) {
            throw failure("invalid_uri", "Base URL is not a valid URI");
        }

        if (!parsed.isAbsolute() || parsed.isOpaque()) {
            throw failure("invalid_uri", "Base URL must be an absolute hierarchical URI");
        }
        if (parsed.getRawUserInfo() != null) {
            throw failure("userinfo_forbidden", "Base URL must not contain user information");
        }
        if (parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw failure("query_or_fragment_forbidden", "Base URL must not contain a query or fragment");
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw failure("missing_host", "Base URL must contain a host");
        }

        String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw failure("invalid_scheme", "Only HTTPS and loopback HTTP are supported");
        }

        String rawPath = parsed.getRawPath();
        String path = rawPath == null || rawPath.isEmpty() ? "" : rawPath;
        String lowerRawPath = path.toLowerCase(Locale.ROOT);
        if (containsDotSegment(path) || lowerRawPath.contains("%2e")) {
            throw failure("path_traversal", "Base URL path must not contain dot segments");
        }
        if (hasEndpointSuffix(path)) {
            throw failure(
                    "endpoint_suffix_forbidden",
                    "Base URL must be an API prefix, not a concrete completion endpoint"
            );
        }

        String parsedHost = parsed.getHost();
        if (parsedHost.startsWith("[") && parsedHost.endsWith("]")) {
            parsedHost = parsedHost.substring(1, parsedHost.length() - 1);
        }
        String asciiHost;
        try {
            asciiHost = parsedHost.contains(":")
                    ? parsedHost.toLowerCase(Locale.ROOT)
                    : IDN.toASCII(parsedHost).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw failure("invalid_host", "Base URL host is invalid");
        }
        if (scheme.equals("http") && !isLoopbackHost(asciiHost)) {
            throw failure("insecure_remote_http", "Plain HTTP is allowed only for loopback hosts");
        }

        while (path.endsWith("/") && !path.isEmpty()) {
            path = path.substring(0, path.length() - 1);
        }
        /*
         * OpenAI-compatible providers conventionally expose both supported
         * protocols below /v1.  The setup screen deliberately accepts the
         * short, human-friendly host form (for example
         * https://api.example.test) as well as an explicit API prefix.  Do
         * not make a root URL accidentally call /responses: a number of
         * gateways answer that unknown route with HTTP 200 and a wrapper
         * error, which is indistinguishable from a malformed model response
         * and prevents capability negotiation from reaching chat or
         * responses correctly.
         */
        if (path.isEmpty()) {
            path = "/v1";
        }
        try {
            URI normalized = new URI(
                    scheme,
                    null,
                    asciiHost,
                    parsed.getPort(),
                    path,
                    null,
                    null
            );
            return new ModelEndpoint(normalized, modelName);
        } catch (URISyntaxException exception) {
            throw failure("invalid_uri", "Base URL could not be normalized");
        }
    }

    private static boolean isLoopbackHost(String host) throws EndpointValidationException {
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return true;
        }
        final boolean numericIpv4 = host.matches("[0-9.]+");
        final boolean numericIpv6 = host.indexOf(':') >= 0
                && host.matches("[0-9a-f:.%]+");
        if (!numericIpv4 && !numericIpv6) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress();
        } catch (Exception exception) {
            throw failure("invalid_loopback_host", "HTTP host is not a valid loopback address");
        }
    }

    private static boolean containsDotSegment(String path) {
        for (String segment : path.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEndpointSuffix(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.endsWith("/responses")
                || normalized.endsWith("/chat/completions");
    }

    private static String requireTrimmed(String value, String field, int maxLength)
            throws EndpointValidationException {
        if (value == null || value.isBlank()) {
            throw failure("missing_" + field, field + " must not be blank");
        }
        if (!value.equals(value.trim())) {
            throw failure("surrounding_whitespace", field + " must not contain surrounding whitespace");
        }
        if (value.length() > maxLength) {
            throw failure("value_too_long", field + " exceeds the configured length limit");
        }
        validateNoControlCharacters(value, field);
        return value;
    }

    private static void validateNoControlCharacters(String value, String field)
            throws EndpointValidationException {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
                throw failure("invalid_character", field + " contains whitespace or a control character");
            }
            offset += Character.charCount(codePoint);
        }
    }

    private static EndpointValidationException failure(String code, String message) {
        return new EndpointValidationException(code, message);
    }
}
