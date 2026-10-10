package dev.mcai.companion.modelsetup.network;

import java.util.Objects;

/**
 * Secret-free status returned to the setup screen. It intentionally exposes
 * only whether a credential exists, never its value, prefix, or length.
 */
public record ClientboundModelSetupState(
    long requestId,
    byte[] sessionToken,
    String baseUrl,
    String modelName,
    String agentName,
    String accentColor,
    double temperature,
    String systemPrompt,
    boolean onboardingCompleted,
    boolean bodyActive,
    boolean canEdit,
    boolean credentialAvailable,
    boolean evaluationLocked,
    boolean probeInFlight,
    boolean gatewayReady,
    boolean restartRequired,
    String statusCode
) {
    public ClientboundModelSetupState {
        if (requestId <= 0) {
            throw new IllegalArgumentException("requestId must be positive");
        }
        sessionToken = Objects.requireNonNull(
            sessionToken,
            "sessionToken"
        ).clone();
        if (sessionToken.length != 0) {
            ModelSetupWireLimits.requireSessionToken(sessionToken);
        }
        ModelSetupWireLimits.requireBaseUrl(
            Objects.requireNonNull(baseUrl, "baseUrl")
        );
        ModelSetupWireLimits.requireModelName(
            Objects.requireNonNull(modelName, "modelName")
        );
        ModelSetupWireLimits.requireAgentName(
            Objects.requireNonNull(agentName, "agentName")
        );
        ModelSetupWireLimits.requireAccentColor(
            Objects.requireNonNull(accentColor, "accentColor")
        );
        ModelSetupWireLimits.requireTemperature(temperature);
        ModelSetupWireLimits.requireSystemPrompt(
            Objects.requireNonNull(systemPrompt, "systemPrompt")
        );
        ModelSetupWireLimits.requireStatusCode(
            Objects.requireNonNull(statusCode, "statusCode")
        );
        if (!canEdit && sessionToken.length != 0) {
            throw new IllegalArgumentException(
                "A denied state must not contain a session token"
            );
        }
    }

    @Override
    public byte[] sessionToken() {
        return sessionToken.clone();
    }

    byte[] sessionTokenUnsafe() {
        return sessionToken;
    }
}
