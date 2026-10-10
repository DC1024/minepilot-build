package dev.mcai.companion.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A log-safe error. It deliberately excludes raw provider bodies, prompts,
 * headers, and credentials.
 */
public record ModelFailure(
        ModelFailureKind kind,
        int httpStatus,
        String providerCode,
        String providerParam,
        String clientRequestId,
        String providerRequestId,
        Optional<Duration> retryAfter,
        String diagnosticHash,
        String safeMessage
) {
    public ModelFailure {
        Objects.requireNonNull(kind, "kind");
        providerCode = Objects.requireNonNullElse(providerCode, "");
        providerParam = Objects.requireNonNullElse(providerParam, "");
        clientRequestId = Objects.requireNonNullElse(clientRequestId, "");
        providerRequestId = Objects.requireNonNullElse(providerRequestId, "");
        retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
        diagnosticHash = Objects.requireNonNullElse(diagnosticHash, "");
        safeMessage = Objects.requireNonNullElse(safeMessage, "");
    }

    public boolean allowsCapabilityFallback() {
        return kind == ModelFailureKind.ENDPOINT_UNSUPPORTED
                || kind == ModelFailureKind.CAPABILITY_UNSUPPORTED;
    }
}
