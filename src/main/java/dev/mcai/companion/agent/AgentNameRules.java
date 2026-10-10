package dev.mcai.companion.agent;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;

/**
 * Server-authoritative player-profile naming rules.
 *
 * <p>Names deliberately use the same conservative ASCII surface as ordinary
 * Java player profile names. Comparisons are case-insensitive so names which
 * would be visually or operationally ambiguous cannot coexist.</p>
 */
public final class AgentNameRules {
    public static final int MIN_LENGTH = 3;
    public static final int MAX_LENGTH = 16;
    private static final Pattern VALID =
        Pattern.compile("[A-Za-z0-9_]{" + MIN_LENGTH + "," + MAX_LENGTH + "}");

    private AgentNameRules() {
    }

    public static Validation validateSyntax(final String candidate) {
        final String normalized = Objects.requireNonNullElse(
            candidate,
            ""
        ).strip();
        if (normalized.length() < MIN_LENGTH) {
            return Validation.rejected("name_too_short");
        }
        if (normalized.length() > MAX_LENGTH) {
            return Validation.rejected("name_too_long");
        }
        if (!VALID.matcher(normalized).matches()) {
            return Validation.rejected("name_invalid_characters");
        }
        return Validation.accepted(normalized);
    }

    public static Validation validateAvailable(
        final MinecraftServer server,
        final UUID companionUuid,
        final String currentName,
        final String candidate
    ) {
        return validateAvailable(
            server,
            companionUuid,
            currentName,
            candidate,
            java.util.List.of()
        );
    }

    public static Validation validateAvailable(
        final MinecraftServer server,
        final UUID companionUuid,
        final String currentName,
        final String candidate,
        final Collection<String> knownPlayerNames
    ) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(companionUuid, "companionUuid");
        final Validation syntax = validateSyntax(candidate);
        if (!syntax.accepted()) {
            return syntax;
        }
        final String folded = fold(syntax.normalized());
        final String currentFolded = fold(
            Objects.requireNonNullElse(currentName, "")
        );
        for (final String knownPlayerName : Objects.requireNonNull(
            knownPlayerNames,
            "knownPlayerNames"
        )) {
            if (fold(knownPlayerName).equals(folded)) {
                return Validation.rejected("name_occupied_by_player");
            }
        }
        for (final var player : server.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(companionUuid)) {
                continue;
            }
            if (fold(player.getGameProfile().name()).equals(folded)) {
                return Validation.rejected("name_occupied_by_player");
            }
        }
        if (!currentFolded.equals(folded)
            && server.getPlayerList().getPlayerByName(
                syntax.normalized()
            ) != null) {
            return Validation.rejected("name_occupied");
        }
        return syntax;
    }

    public static String requireValid(final String candidate) {
        final Validation validation = validateSyntax(candidate);
        if (!validation.accepted()) {
            throw new IllegalArgumentException(validation.code());
        }
        return validation.normalized();
    }

    private static String fold(final String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public record Validation(
        boolean accepted,
        String normalized,
        String code
    ) {
        private static Validation accepted(final String normalized) {
            return new Validation(true, normalized, "name_available");
        }

        private static Validation rejected(final String code) {
            return new Validation(false, "", code);
        }
    }
}
