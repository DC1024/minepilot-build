package dev.mcai.companion.integration.xaero;

/**
 * How much routing authority can safely be derived from Xaero's destination
 * description.
 */
public enum XaeroDestinationKind {
    /** A small, exact allowlist identified a vanilla dimension. */
    EXPLICIT_DIMENSION,
    /** Xaero sent "Internal"; only the caller can decide what current means. */
    CALLER_CURRENT_DIMENSION,
    /** Valid display metadata that is deliberately not interpreted as a dimension. */
    OPAQUE
}
