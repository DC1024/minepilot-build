package dev.mcai.companion.integration.xaero;

/**
 * Structured waypoint prefixes accepted from Xaero's chat sharing protocol.
 */
public enum XaeroShareFormat {
    MODERN("xaero-waypoint:"),
    LEGACY("xaero_waypoint:");

    private final String prefix;

    XaeroShareFormat(String prefix) {
        this.prefix = prefix;
    }

    String prefix() {
        return prefix;
    }
}
