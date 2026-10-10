package dev.mcai.companion.integration.xaero;

/**
 * Explicit policy for resolving an unqualified Xaero "Internal" target.
 */
public enum CurrentDimensionPolicy {
    REJECT_UNQUALIFIED,
    USE_CALLER_CURRENT
}
