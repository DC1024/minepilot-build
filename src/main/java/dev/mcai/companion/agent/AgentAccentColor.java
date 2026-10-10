package dev.mcai.companion.agent;

import java.util.Locale;

/**
 * Small vanilla-friendly palette used by the configuration screen and future
 * Agent markers. Values are persisted by stable identifier, not ordinal.
 */
public enum AgentAccentColor {
    EMERALD(0xFF55FF55),
    AQUA(0xFF55FFFF),
    LAPIS(0xFF5555FF),
    AMETHYST(0xFFFF55FF),
    GOLD(0xFFFFAA00),
    REDSTONE(0xFFFF5555);

    private final int argb;

    AgentAccentColor(final int argb) {
        this.argb = argb;
    }

    public int argb() {
        return argb;
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public AgentAccentColor next() {
        final AgentAccentColor[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static AgentAccentColor parse(final String value) {
        if (value == null) {
            return EMERALD;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return EMERALD;
        }
    }

    public static AgentAccentColor requireKnown(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("accent_color_unknown");
        }
        for (final AgentAccentColor candidate : values()) {
            if (candidate.serializedName().equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("accent_color_unknown");
    }
}
