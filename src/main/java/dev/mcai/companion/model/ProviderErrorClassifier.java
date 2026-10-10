package dev.mcai.companion.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Maps HTTP and common OpenAI-compatible error envelopes into stable,
 * non-sensitive local categories.
 */
public final class ProviderErrorClassifier {
    private static final int MAX_ERROR_BODY_CHARS = 65_536;
    private static final int MAX_ERROR_DEPTH = 16;
    private static final int MAX_ERROR_NODES = 4_096;

    public Optional<ModelFailure> classify(
            int httpStatus,
            HttpHeaders headers,
            String body,
            String clientRequestId,
            Set<String> capabilityFields
    ) {
        String boundedBody = body == null
                ? ""
                : body.substring(0, Math.min(body.length(), MAX_ERROR_BODY_CHARS));
        ErrorFields fields = parseFields(boundedBody);

        int effectiveStatus = httpStatus;
        if (isHttpSuccess(httpStatus) && fields.businessStatusCode != 0) {
            effectiveStatus = mapBusinessStatus(fields.businessStatusCode);
        }
        if (isHttpSuccess(effectiveStatus)) {
            return Optional.empty();
        }

        ModelFailureKind kind = classifyKind(effectiveStatus, fields, capabilityFields);
        String providerParam = fields.param;
        if (kind == ModelFailureKind.CAPABILITY_UNSUPPORTED && providerParam.isBlank()) {
            providerParam = rejectedCapabilityField(
                    fields.code,
                    fields.param,
                    fields.message,
                    capabilityFields
            ).orElse("");
        }
        String providerRequestId = headers.firstValue("x-request-id").orElse("");
        return Optional.of(new ModelFailure(
                kind,
                effectiveStatus,
                fields.code,
                providerParam,
                clientRequestId,
                providerRequestId,
                parseRetryAfter(headers),
                sha256(boundedBody),
                safeMessage(kind)
        ));
    }

    private static ModelFailureKind classifyKind(
            int status,
            ErrorFields fields,
            Set<String> capabilityFields
    ) {
        String code = fields.code.toLowerCase(Locale.ROOT);
        String param = fields.param.toLowerCase(Locale.ROOT);
        String message = fields.message.toLowerCase(Locale.ROOT);

        if (fields.businessStatusCode == 1004 || status == 401) {
            return ModelFailureKind.AUTHENTICATION;
        }
        if (status == 403) {
            return ModelFailureKind.PERMISSION;
        }
        if (fields.businessStatusCode == 1008 || status == 402) {
            return ModelFailureKind.BILLING;
        }
        if (fields.businessStatusCode == 1002 || status == 429) {
            return ModelFailureKind.RATE_LIMITED;
        }
        if (fields.businessStatusCode == 1026
                || fields.businessStatusCode == 1027
                || code.contains("content_filter")
                || message.contains("content filter")) {
            return ModelFailureKind.CONTENT_FILTERED;
        }
        if (status == 408) {
            return ModelFailureKind.TIMEOUT;
        }
        if (status == 413
                || code.contains("context_length")
                || code.contains("token_limit")
                || message.contains("context length")) {
            return ModelFailureKind.CONTEXT_LIMIT;
        }
        /*
         * Some OpenAI-compatible relays wrap an unavailable or unauthorized
         * model id in HTTP 5xx even though the structured provider code is
         * model_not_found. Preserve the semantic error instead of presenting
         * it as a retryable server outage and repeatedly spending requests.
         */
        if ((status == 404 && isModelError(code, param, message))
                || isModelError(code, "", message)) {
            return ModelFailureKind.MODEL_NOT_FOUND;
        }
        if ((status == 400 || status == 422)
                && explicitlyRejectsEndpoint(code, message)) {
            return ModelFailureKind.ENDPOINT_UNSUPPORTED;
        }
        if (status == 404 || status == 405) {
            return ModelFailureKind.ENDPOINT_UNSUPPORTED;
        }
        if ((status == 400 || status == 422)
                && explicitlyRejectsCapability(
                        code,
                        param,
                        message,
                        capabilityFields
                )) {
            return ModelFailureKind.CAPABILITY_UNSUPPORTED;
        }
        if (status == 409 || status >= 500 || fields.businessStatusCode == 1024) {
            return ModelFailureKind.SERVER_TRANSIENT;
        }
        if (status == 400 || status == 422) {
            return ModelFailureKind.INVALID_REQUEST;
        }
        return ModelFailureKind.INVALID_REQUEST;
    }

    private static boolean isModelError(String code, String param, String message) {
        return param.equals("model")
                || code.contains("model_not_found")
                || code.contains("invalid_model")
                || message.contains("model not found")
                || message.contains("unknown model")
                || message.contains("no such model")
                || (message.contains("model")
                        && (message.contains("does not exist")
                                || message.contains("could not be found")));
    }

    private static boolean explicitlyRejectsCapability(
            String code,
            String param,
            String message,
            Set<String> capabilityFields
    ) {
        return rejectedCapabilityField(
                code,
                param,
                message,
                capabilityFields
        ).isPresent();
    }

    private static Optional<String> rejectedCapabilityField(
            String code,
            String param,
            String message,
            Set<String> capabilityFields
    ) {
        if (capabilityFields == null
                || capabilityFields.isEmpty()
                || !hasExplicitUnsupportedWording(code, message)) {
            return Optional.empty();
        }
        return capabilityFields.stream()
                .map(field -> field.toLowerCase(Locale.ROOT))
                .sorted(
                        Comparator.comparingInt(String::length)
                                .reversed()
                                .thenComparing(Comparator.naturalOrder())
                )
                .filter(field -> isSameOrDescendant(param, field)
                        || mentionsField(message, field)
                        || field.indexOf('.') >= 0
                            && mentionsField(
                                message,
                                field.replace('.', '_')
                            ))
                .findFirst();
    }

    private static boolean isSameOrDescendant(String value, String field) {
        return value.equals(field)
                || value.startsWith(field + ".")
                || value.startsWith(field + "[");
    }

    private static boolean hasExplicitUnsupportedWording(String code, String message) {
        return code.contains("unsupported")
                || code.contains("unknown_parameter")
                || code.contains("unrecognized")
                || code.contains("extra_forbidden")
                || message.contains("unsupported")
                || message.contains("not supported")
                || message.contains("does not support")
                || message.contains("unknown parameter")
                || message.contains("unrecognized")
                || message.contains("extra field")
                || message.contains("extra input")
                || message.contains("not permitted");
    }

    private static boolean explicitlyRejectsEndpoint(String code, String message) {
        boolean namesEndpoint = code.contains("endpoint")
                || code.contains("route")
                || message.contains("endpoint")
                || message.contains("route");
        boolean explicitCode = code.contains("endpoint_not_found")
                || code.contains("route_not_found")
                || code.contains("no_such_endpoint");
        return explicitCode
                || (namesEndpoint && hasExplicitUnsupportedWording(code, message));
    }

    private static boolean mentionsField(String message, String field) {
        int fromIndex = 0;
        while (fromIndex <= message.length() - field.length()) {
            int index = message.indexOf(field, fromIndex);
            if (index < 0) {
                return false;
            }
            int end = index + field.length();
            boolean boundedBefore = index == 0
                    || !isFieldIdentifierCharacter(message.charAt(index - 1));
            boolean boundedAfter = end == message.length()
                    || !isFieldIdentifierCharacter(message.charAt(end));
            if (boundedBefore && boundedAfter) {
                return true;
            }
            fromIndex = index + 1;
        }
        return false;
    }

    private static boolean isFieldIdentifierCharacter(char character) {
        return Character.isLetterOrDigit(character)
                || character == '_'
                || character == '.';
    }

    private static ErrorFields parseFields(String body) {
        if (body.isBlank()) {
            return ErrorFields.EMPTY;
        }
        try {
            JsonElement parsed = BoundedJsonParser.parse(
                    body,
                    MAX_ERROR_BODY_CHARS,
                    MAX_ERROR_DEPTH,
                    MAX_ERROR_NODES
            );
            if (!parsed.isJsonObject()) {
                return ErrorFields.EMPTY;
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonObject error = object(root.get("error")).orElse(root);
            String code = string(error.get("code")).orElseGet(
                    () -> string(error.get("type")).orElse("")
            );
            String param = string(error.get("param")).orElse("");
            String message = string(error.get("message")).orElse("");

            int businessStatus = 0;
            Optional<JsonObject> baseResponse = object(root.get("base_resp"));
            if (baseResponse.isPresent()) {
                businessStatus = integer(baseResponse.get().get("status_code")).orElse(0);
                if (message.isEmpty()) {
                    message = string(baseResponse.get().get("status_msg")).orElse("");
                }
                if (code.isEmpty() && businessStatus != 0) {
                    code = Integer.toString(businessStatus);
                }
            }
            return new ErrorFields(code, param, message, businessStatus);
        } catch (IOException | RuntimeException exception) {
            return ErrorFields.EMPTY;
        }
    }

    private static Optional<JsonObject> object(JsonElement element) {
        return element != null && element.isJsonObject()
                ? Optional.of(element.getAsJsonObject())
                : Optional.empty();
    }

    private static Optional<String> string(JsonElement element) {
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()
                ? Optional.of(element.getAsString())
                : Optional.empty();
    }

    private static Optional<Integer> integer(JsonElement element) {
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(element.getAsString()).intValueExact());
        } catch (ArithmeticException | NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static int mapBusinessStatus(int businessStatus) {
        return switch (businessStatus) {
            case 1002 -> 429;
            case 1004 -> 401;
            case 1008 -> 402;
            case 1026, 1027 -> 400;
            case 1024, 1033 -> 500;
            default -> 400;
        };
    }

    private static boolean isHttpSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static Optional<Duration> parseRetryAfter(HttpHeaders headers) {
        Optional<String> value = headers.firstValue("retry-after");
        if (value.isEmpty()) {
            return Optional.empty();
        }
        String raw = value.get().trim();
        try {
            long seconds = Long.parseLong(raw);
            return Optional.of(Duration.ofSeconds(Math.max(0, seconds)));
        } catch (NumberFormatException ignored) {
            // Try an RFC 1123 HTTP date below.
        }
        try {
            Instant retryAt = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant();
            Duration duration = Duration.between(Instant.now(), retryAt);
            return Optional.of(duration.isNegative() ? Duration.ZERO : duration);
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safeMessage(ModelFailureKind kind) {
        return switch (kind) {
            case AUTHENTICATION -> "The model provider rejected the API credential";
            case PERMISSION -> "The credential cannot access the requested resource";
            case BILLING -> "The model provider reported insufficient balance";
            case MODEL_NOT_FOUND -> "The configured model was not found or is unavailable";
            case ENDPOINT_UNSUPPORTED -> "The configured API endpoint is not supported";
            case CAPABILITY_UNSUPPORTED -> "The provider does not support the requested output capability";
            case RATE_LIMITED -> "The model provider rate-limited the request";
            case SERVER_TRANSIENT -> "The model provider reported a temporary server failure";
            case TIMEOUT -> "The model request timed out";
            case CONTENT_FILTERED -> "The provider filtered the request or response";
            case CONTEXT_LIMIT -> "The model context or output token limit was exceeded";
            case INVALID_REQUEST -> "The provider rejected the request";
            default -> "The model request failed";
        };
    }

    private record ErrorFields(String code, String param, String message, int businessStatusCode) {
        private static final ErrorFields EMPTY = new ErrorFields("", "", "", 0);
    }
}
