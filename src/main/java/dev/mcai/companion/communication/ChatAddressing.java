package dev.mcai.companion.communication;

import java.util.Locale;
import java.util.Objects;

/**
 * Removes an optional spoken or @-style Agent address without interpreting
 * the remaining player text.
 */
public final class ChatAddressing {
    private ChatAddressing() {
    }

    public static Parsed parse(
            final String raw,
            final String agentName
    ) {
        final String message =
                Objects.requireNonNullElse(raw, "").strip();
        final String name =
                Objects.requireNonNullElse(agentName, "").strip();
        final int named = prefixLength(message, name);
        if (named >= 0) {
            return new Parsed(
                    true,
                    stripSeparator(message.substring(named))
            );
        }
        final int legacy = legacyMcaiPrefixLength(message);
        if (legacy >= 0) {
            return new Parsed(
                    true,
                    stripSeparator(message.substring(legacy))
            );
        }
        return new Parsed(false, message);
    }

    /**
     * In an integrated single-player world the only human chat source is the
     * owner, so ordinary chat is addressed to the companion even without an
     * {@code @Agent} prefix.  Dedicated multiplayer keeps the explicit
     * addressing bit so an agent does not answer every unrelated team chat.
     */
    public static boolean addressedForServer(
            final Parsed parsed,
            final boolean singlePlayer
    ) {
        Objects.requireNonNull(parsed, "parsed");
        return addressedForServer(parsed, singlePlayer ? 1 : 2);
    }

    /**
     * Applies the player-facing addressing rule using the number of online
     * human players, rather than the server implementation type. An
     * integrated server can be opened to LAN and then has multiple humans;
     * treating every non-dedicated server as single-player made unrelated
     * LAN chat look like an AI command. The caller must exclude the visible
     * companion ServerPlayer (and any future AI bodies) from this count.
     */
    public static boolean addressedForServer(
            final Parsed parsed,
            final int humanPlayerCount
    ) {
        Objects.requireNonNull(parsed, "parsed");
        if (humanPlayerCount < 0) {
            throw new IllegalArgumentException(
                    "humanPlayerCount cannot be negative"
            );
        }
        return humanPlayerCount <= 1 || parsed.explicit();
    }

    private static int prefixLength(
            final String message,
            final String name
    ) {
        if (name.isEmpty()) {
            return -1;
        }
        final int offset = message.startsWith("@") ? 1 : 0;
        if (message.length() < offset + name.length()
                || !message.regionMatches(
                    true,
                    offset,
                    name,
                    0,
                    name.length()
                )) {
            return -1;
        }
        final int end = offset + name.length();
        return boundaryAt(message, end) ? end : -1;
    }

    private static int legacyMcaiPrefixLength(
            final String message
    ) {
        final String lower = message.toLowerCase(Locale.ROOT);
        int index = lower.startsWith("@") ? 1 : 0;
        if (!lower.startsWith("mc", index)) {
            return -1;
        }
        index += 2;
        while (index < lower.length()
                && Character.isWhitespace(lower.charAt(index))) {
            index++;
        }
        if (!lower.startsWith("ai", index)) {
            return -1;
        }
        index += 2;
        return boundaryAt(message, index) ? index : -1;
    }

    private static boolean boundaryAt(
            final String message,
            final int index
    ) {
        if (index >= message.length()) {
            return true;
        }
        final char next = message.charAt(index);
        return Character.isWhitespace(next)
                || next == ':'
                || next == '：'
                || next == ','
                || next == '，';
    }

    private static String stripSeparator(final String suffix) {
        String result = suffix.stripLeading();
        if (!result.isEmpty()) {
            final char first = result.charAt(0);
            if (first == ':' || first == '：'
                    || first == ',' || first == '，') {
                result = result.substring(1).stripLeading();
            }
        }
        return result;
    }

    public record Parsed(boolean explicit, String message) {
        public Parsed {
            Objects.requireNonNull(message, "message");
        }
    }
}
