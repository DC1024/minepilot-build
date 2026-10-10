package dev.mcai.companion.modelsetup.network;

import dev.mcai.companion.modelsetup.ModelSetupSessionRegistry;

public final class ModelSetupWireLimits {
    public static final int MAX_API_KEY_UTF8_BYTES = 8_192;
    public static final int MAX_BASE_URL_CHARACTERS = 2_048;
    public static final int MAX_MODEL_NAME_CHARACTERS = 256;
    public static final int MAX_AGENT_NAME_CHARACTERS = 16;
    public static final int MAX_ACCENT_COLOR_CHARACTERS = 16;
    public static final int MAX_SYSTEM_PROMPT_CHARACTERS = 4_096;
    public static final int MAX_STATUS_CODE_CHARACTERS = 64;

    private ModelSetupWireLimits() {
    }

    static void requireSessionToken(final byte[] value) {
        if (value.length != ModelSetupSessionRegistry.TOKEN_BYTES) {
            throw new IllegalArgumentException("Invalid setup session token");
        }
    }

    static void requireApiKeyBytes(final byte[] value) {
        if (value.length > MAX_API_KEY_UTF8_BYTES) {
            throw new IllegalArgumentException("API key payload is too large");
        }
    }

    static void requireBaseUrl(final String value) {
        if (value.length() > MAX_BASE_URL_CHARACTERS) {
            throw new IllegalArgumentException("Base URL payload is too large");
        }
    }

    static void requireModelName(final String value) {
        if (value.length() > MAX_MODEL_NAME_CHARACTERS) {
            throw new IllegalArgumentException("Model name payload is too large");
        }
    }

    static void requireAgentName(final String value) {
        if (value.length() > MAX_AGENT_NAME_CHARACTERS) {
            throw new IllegalArgumentException(
                "Agent name payload is too large"
            );
        }
    }

    static void requireAccentColor(final String value) {
        if (value.isBlank()
            || value.length() > MAX_ACCENT_COLOR_CHARACTERS
            || !value.matches("[a-z_]+")) {
            throw new IllegalArgumentException(
                "Invalid Agent accent color"
            );
        }
    }

    static void requireTemperature(final double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                "Agent temperature is outside [0.0,1.0]"
            );
        }
    }

    static void requireSystemPrompt(final String value) {
        if (value.length() > MAX_SYSTEM_PROMPT_CHARACTERS
            || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                "Agent system prompt payload is too large"
            );
        }
    }

    static void requireStatusCode(final String value) {
        if (value.isBlank()
            || value.length() > MAX_STATUS_CODE_CHARACTERS
            || !value.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid setup status code");
        }
    }
}
