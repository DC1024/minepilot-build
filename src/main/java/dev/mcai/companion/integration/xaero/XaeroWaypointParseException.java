package dev.mcai.companion.integration.xaero;

/**
 * Controlled parse failure. Messages intentionally never include attacker
 * supplied chat content.
 */
public final class XaeroWaypointParseException extends Exception {
    private final Reason reason;

    XaeroWaypointParseException(Reason reason) {
        super("Xaero waypoint rejected: " + reason.name().toLowerCase());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        NOT_XAERO_MESSAGE,
        MESSAGE_TOO_LONG,
        INVALID_FORMATTING,
        WRONG_FIELD_COUNT,
        INVALID_TEXT,
        INVALID_NUMBER,
        OUT_OF_RANGE,
        INVALID_DESTINATION
    }
}
