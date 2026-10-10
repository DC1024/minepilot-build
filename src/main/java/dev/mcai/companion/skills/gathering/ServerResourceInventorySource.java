package dev.mcai.companion.skills.gathering;

import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Reads only the companion's own ordinary 36-slot inventory capacity.
 */
public final class ServerResourceInventorySource
        implements ResourceInventorySource {
    private static final int MAIN_INVENTORY_SLOTS = 36;

    private final MinecraftServer server;
    private final UUID expectedPlayerId;

    public ServerResourceInventorySource(
            MinecraftServer server,
            UUID expectedPlayerId
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
    }

    @Override
    public Optional<ResourceInventoryState> current() {
        if (!server.isSameThread()) {
            return Optional.empty();
        }
        AiPlayerManager.Status status = AiPlayerManager.status(server);
        ServerPlayer player = server.getPlayerList().getPlayer(
                expectedPlayerId
        );
        if (status.state() != SessionState.ACTIVE
                || !status.online()
                || player == null
                || player.connection == null
                || player.isRemoved()
                || !expectedPlayerId.equals(player.getUUID())) {
            return Optional.empty();
        }
        int empty = 0;
        for (int slot = 0; slot < MAIN_INVENTORY_SLOTS; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                empty++;
            }
        }
        return Optional.of(new ResourceInventoryState(
                status.sessionGeneration(),
                empty
        ));
    }
}
