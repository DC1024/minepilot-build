package dev.mcai.companion.embodiment;

import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.core.SectionPos;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Owns transport state for one headless login.
 *
 * <p>The current {@link ServerPlayer} is always resolved through the listener;
 * vanilla swaps it when the player respawns.</p>
 */
final class AiPlayerSession implements AutoCloseable {
    private static final int RESPAWN_DELAY_TICKS = 20;

    private final MinecraftServer server;
    private final UUID playerId;
    private final Connection connection;
    private final EmbeddedChannel channel;
    private final ServerGamePacketListenerImpl listener;
    private final HeadlessConnectionPump pump;

    private int deadTicks;
    private boolean closed;
    /**
     * The level whose vanilla player-tracking ticket was last refreshed.
     * Dimension changes can retain the same section coordinate, so a
     * section-only check is insufficient for a clientless player.
     */
    private ServerLevel lastChunkWindowLevel;

    private AiPlayerSession(
            MinecraftServer server,
            UUID playerId,
            Connection connection,
            EmbeddedChannel channel,
            ServerGamePacketListenerImpl listener
    ) {
        this.server = server;
        this.playerId = playerId;
        this.connection = connection;
        this.channel = channel;
        this.listener = listener;
        this.pump = new HeadlessConnectionPump(connection, channel, listener);
    }

    static AiPlayerSession connected(
            MinecraftServer server,
            UUID playerId,
            Connection connection,
            EmbeddedChannel channel,
            ServerPlayer initialPlayer
    ) {
        if (initialPlayer.connection == null) {
            throw new IllegalStateException("Vanilla did not install a play listener");
        }
        ServerGamePacketListenerImpl listener = initialPlayer.connection;
        AiPlayerSession session = new AiPlayerSession(server, playerId, connection, channel, listener);
        session.lastChunkWindowLevel = initialPlayer.level();
        listener.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        session.pump.tick();
        return session;
    }

    TickResult tick() {
        if (closed) {
            return TickResult.DISCONNECTED;
        }

        pump.tick();
        if (!connection.isConnected()) {
            return TickResult.DISCONNECTED;
        }

        ServerPlayer player = currentPlayer();
        if (player == null || server.getPlayerList().getPlayer(playerId) != player) {
            return TickResult.DISCONNECTED;
        }

        /*
         * A real client sends a movement packet while crossing a section.
         * ServerGamePacketListenerImpl handles that packet by calling
         * ServerChunkCache.move(player), which transfers PLAYER_LOADING and
         * PLAYER_SIMULATION tickets to the new chunk. Our body is driven by
         * server-side vanilla physics and therefore sends no redundant
         * position packet. Reproduce that one server-side consequence when
         * the section actually changes; otherwise a sole headless player can
         * walk into an unloaded area while its simulation ticket remains at
         * the old location. This is the ordinary player-ticket path, not a
         * forced chunk.
         */
        final ServerLevel currentLevel = player.level();
        final boolean dimensionChanged =
                lastChunkWindowLevel != currentLevel;
        if (dimensionChanged
                || !SectionPos.of(player).equals(
                    player.getLastSectionPos()
                )) {
            /*
             * This is the same vanilla ticket refresh caused by a real
             * client movement packet.  The dimension check is essential:
             * portal travel may leave the section coordinate unchanged while
             * replacing the ServerLevel and its tracking window.
             */
            currentLevel.getChunkSource().move(player);
            lastChunkWindowLevel = currentLevel;
        }

        if (player.getHealth() > 0.0F) {
            deadTicks = 0;
            return TickResult.ACTIVE;
        }

        deadTicks++;
        if (server.isHardcore()) {
            return TickResult.HARDCORE_DEATH;
        }
        if (deadTicks < RESPAWN_DELAY_TICKS) {
            return TickResult.ACTIVE;
        }

        listener.handleClientCommand(new ServerboundClientCommandPacket(
                ServerboundClientCommandPacket.Action.PERFORM_RESPAWN
        ));
        listener.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        deadTicks = 0;
        return TickResult.RESPAWNED;
    }

    boolean isOnline() {
        return !closed
                && connection.isConnected()
                && currentPlayer() != null
                && server.getPlayerList().getPlayer(playerId) == currentPlayer();
    }

    HeadlessConnectionPump.AuditSnapshot auditSnapshot() {
        return pump.auditSnapshot();
    }

    ServerPlayer currentPlayer() {
        ServerPlayer player = listener.getPlayer();
        return player != null && playerId.equals(player.getUUID()) ? player : null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (connection.isConnected()) {
            connection.disconnect(new DisconnectionDetails(Component.literal("AI companion removed")));
        }
        pump.handleDisconnectionOnce();
        channel.finishAndReleaseAll();
        pump.markClosedAfterChannelFinish();
    }

    enum TickResult {
        ACTIVE,
        RESPAWNED,
        HARDCORE_DEATH,
        DISCONNECTED
    }
}
