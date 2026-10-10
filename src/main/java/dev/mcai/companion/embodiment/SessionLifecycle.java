package dev.mcai.companion.embodiment;

import java.util.Locale;
import java.util.Objects;

/**
 * Small, Minecraft-independent lifecycle guard.
 *
 * <p>Only controlled failure codes are retained. Exception messages can
 * contain paths, addresses, or other information that must not be exposed by
 * the status command.</p>
 */
public final class SessionLifecycle {
    private static final int MAX_FAILURE_CODE_LENGTH = 64;

    private SessionState state = SessionState.ABSENT;
    private String failureCode = "";

    public SessionState state() {
        return state;
    }

    public String failureCode() {
        return failureCode;
    }

    public void beginSpawn() {
        require(SessionState.ABSENT);
        failureCode = "";
        state = SessionState.PREPARING;
    }

    public void activate() {
        require(SessionState.PREPARING);
        state = SessionState.ACTIVE;
    }

    public void beginStop() {
        if (state != SessionState.PREPARING
                && state != SessionState.ACTIVE
                && state != SessionState.FAILED) {
            throw invalidTransition(SessionState.STOPPING);
        }
        state = SessionState.STOPPING;
    }

    public void stopped() {
        require(SessionState.STOPPING);
        failureCode = "";
        state = SessionState.ABSENT;
    }

    public void fail(String code) {
        if (state == SessionState.ABSENT || state == SessionState.STOPPING) {
            throw invalidTransition(SessionState.FAILED);
        }
        failureCode = sanitizeFailureCode(code);
        state = SessionState.FAILED;
    }

    private void require(SessionState expected) {
        if (state != expected) {
            throw invalidTransition(expected);
        }
    }

    private IllegalStateException invalidTransition(SessionState target) {
        return new IllegalStateException("Invalid session transition: " + state + " -> " + target);
    }

    static String sanitizeFailureCode(String code) {
        String input = Objects.requireNonNullElse(code, "unknown").toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(Math.min(input.length(), MAX_FAILURE_CODE_LENGTH));
        for (int index = 0; index < input.length() && result.length() < MAX_FAILURE_CODE_LENGTH; index++) {
            char character = input.charAt(index);
            boolean allowed = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    || character == '-'
                    || character == '.';
            result.append(allowed ? character : '_');
        }
        return result.isEmpty() ? "unknown" : result.toString();
    }
}
