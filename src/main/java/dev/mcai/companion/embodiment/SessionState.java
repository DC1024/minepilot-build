package dev.mcai.companion.embodiment;

/**
 * Coarse lifecycle of the headless companion.
 *
 * <p>The state intentionally describes the session rather than a particular
 * {@code ServerPlayer}: vanilla replaces that object during respawn.</p>
 */
public enum SessionState {
    ABSENT,
    PREPARING,
    ACTIVE,
    STOPPING,
    FAILED
}
