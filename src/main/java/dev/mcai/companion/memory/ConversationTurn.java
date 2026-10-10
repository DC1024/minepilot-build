package dev.mcai.companion.memory;

import java.time.Instant;
import java.util.Objects;

/** One durable, bounded player/companion conversation turn. */
public record ConversationTurn(
        long sequence,
        Instant occurredAt,
        String player,
        String agent
) {
    public static final int MAX_TEXT_CHARACTERS = 2_048;

    public ConversationTurn {
        if (sequence < 0) {
            throw new IllegalArgumentException(
                    "Conversation sequence must be non-negative"
            );
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        player = bounded(player, "player");
        agent = bounded(agent, "agent");
    }

    public ConversationTurn(
            final Instant occurredAt,
            final String player,
            final String agent
    ) {
        this(0L, occurredAt, player, agent);
    }

    private static String bounded(
            final String value,
            final String label
    ) {
        Objects.requireNonNull(value, label);
        if (value.length() > MAX_TEXT_CHARACTERS) {
            throw new IllegalArgumentException(
                    label + " exceeds the conversation storage limit"
            );
        }
        return value;
    }
}
