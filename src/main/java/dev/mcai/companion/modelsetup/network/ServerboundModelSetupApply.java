package dev.mcai.companion.modelsetup.network;

import java.util.Arrays;
import java.util.Objects;

/**
 * Mutable secret-bearing packet. Encoders and consumers must call
 * {@link #destroy()} in a finally block.
 */
public final class ServerboundModelSetupApply implements AutoCloseable {
    private final long requestId;
    private final byte[] sessionToken;
    private final byte[] apiKeyUtf8;
    private final String baseUrl;
    private final String modelName;
    private final String agentName;
    private final String accentColor;
    private final double temperature;
    private final String systemPrompt;
    private final boolean onboardingCompleted;
    private final boolean preferPersistentCredential;

    public ServerboundModelSetupApply(
        final long requestId,
        final byte[] sessionToken,
        final byte[] apiKeyUtf8,
        final String baseUrl,
        final String modelName,
        final String agentName,
        final String accentColor,
        final double temperature,
        final String systemPrompt,
        final boolean onboardingCompleted,
        final boolean preferPersistentCredential
    ) {
        if (requestId <= 0) {
            throw new IllegalArgumentException("requestId must be positive");
        }
        this.requestId = requestId;
        this.sessionToken = Objects.requireNonNull(
            sessionToken,
            "sessionToken"
        ).clone();
        this.apiKeyUtf8 = Objects.requireNonNull(
            apiKeyUtf8,
            "apiKeyUtf8"
        ).clone();
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.accentColor = Objects.requireNonNull(
            accentColor,
            "accentColor"
        );
        this.temperature = temperature;
        this.systemPrompt = Objects.requireNonNull(
            systemPrompt,
            "systemPrompt"
        );
        this.onboardingCompleted = onboardingCompleted;
        this.preferPersistentCredential = preferPersistentCredential;
        ModelSetupWireLimits.requireSessionToken(this.sessionToken);
        ModelSetupWireLimits.requireApiKeyBytes(this.apiKeyUtf8);
        ModelSetupWireLimits.requireBaseUrl(this.baseUrl);
        ModelSetupWireLimits.requireModelName(this.modelName);
        ModelSetupWireLimits.requireAgentName(this.agentName);
        ModelSetupWireLimits.requireAccentColor(this.accentColor);
        ModelSetupWireLimits.requireTemperature(this.temperature);
        ModelSetupWireLimits.requireSystemPrompt(this.systemPrompt);
    }

    public long requestId() {
        return requestId;
    }

    public byte[] sessionToken() {
        return sessionToken.clone();
    }

    byte[] sessionTokenUnsafe() {
        return sessionToken;
    }

    public byte[] apiKeyUtf8() {
        return apiKeyUtf8.clone();
    }

    byte[] apiKeyUtf8Unsafe() {
        return apiKeyUtf8;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String modelName() {
        return modelName;
    }

    public String agentName() {
        return agentName;
    }

    public String accentColor() {
        return accentColor;
    }

    public double temperature() {
        return temperature;
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public boolean onboardingCompleted() {
        return onboardingCompleted;
    }

    public boolean preferPersistentCredential() {
        return preferPersistentCredential;
    }

    public void destroy() {
        Arrays.fill(sessionToken, (byte) 0);
        Arrays.fill(apiKeyUtf8, (byte) 0);
    }

    @Override
    public void close() {
        destroy();
    }
}
