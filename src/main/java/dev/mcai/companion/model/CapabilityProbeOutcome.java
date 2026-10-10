package dev.mcai.companion.model;

import java.util.Objects;

/**
 * Result of preparing a verified provider capability profile. A successful
 * profile can come either from live probing ({@code requestsMade > 0}) or
 * from an exact endpoint-bound cache restore ({@code requestsMade == 0}).
 */
public sealed interface CapabilityProbeOutcome
        permits CapabilityProbeOutcome.Supported, CapabilityProbeOutcome.Failure {
    int requestsMade();

    record Supported(
            ProviderCapabilities capabilities,
            int requestsMade
    ) implements CapabilityProbeOutcome {
        public Supported {
            Objects.requireNonNull(capabilities, "capabilities");
            if (requestsMade < 0) {
                throw new IllegalArgumentException(
                        "Provider request count must be non-negative"
                );
            }
        }
    }

    record Failure(
            ModelFailure error,
            int requestsMade
    ) implements CapabilityProbeOutcome {
        public Failure {
            Objects.requireNonNull(error, "error");
            if (requestsMade < 0) {
                throw new IllegalArgumentException(
                        "Probe request count must be non-negative"
                );
            }
        }
    }
}
