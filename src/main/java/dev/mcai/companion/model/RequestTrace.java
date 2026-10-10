package dev.mcai.companion.model;

import java.util.Objects;

/**
 * Safe request metadata suitable for audit logs.
 */
public record RequestTrace(
        String clientRequestId,
        String providerRequestId,
        Protocol protocol,
        int httpStatus,
        long elapsedMillis
) {
    public RequestTrace {
        clientRequestId = Objects.requireNonNullElse(clientRequestId, "");
        providerRequestId = Objects.requireNonNullElse(providerRequestId, "");
        Objects.requireNonNull(protocol, "protocol");
    }
}
