package dev.mcai.companion.security;

import dev.mcai.companion.CompanionConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.UUID;

public final class CompanionCommandAccess {
    private CompanionCommandAccess() {
    }

    public static boolean mayAdmin(final CommandSourceStack source) {
        final MinecraftServer server = source.getServer();
        if (server.isSingleplayer()) {
            return source.getEntity() instanceof ServerPlayer player
                && server.isSingleplayerOwner(
                    new NameAndId(player.getGameProfile())
                );
        }
        return Commands.hasPermission(
                Commands.LEVEL_GAMEMASTERS
        ).test(source);
    }

    /**
     * Returns whether a human may issue an ordinary gameplay task in chat.
     * This is deliberately narrower than a general command permission and
     * deliberately broader than admin-only commands: a dedicated server can
     * opt in named teammates by UUID without making them operators.
     */
    public static boolean mayControlCompanion(
            final CommandSourceStack source
    ) {
        if (mayAdmin(source)) {
            return true;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return false;
        }
        return isExplicitlyAllowed(
                player.getUUID(),
                CompanionConfig.CHAT_ALLOWED_SENDERS.get()
        );
    }

    /**
     * Pure allow-list check kept separate from the command source so the
     * multiplayer boundary can be tested without constructing a Forge server.
     */
    static boolean isExplicitlyAllowed(
            final UUID playerId,
            final Iterable<? extends String> configuredIds
    ) {
        if (playerId == null || configuredIds == null) {
            return false;
        }
        for (String configured : configuredIds) {
            if (configured == null || configured.isBlank()) {
                continue;
            }
            try {
                if (playerId.equals(UUID.fromString(configured.strip()))) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // Forge validates the config, but fail closed if it is edited
                // or supplied by a custom config backend at runtime.
            }
        }
        return false;
    }
}
