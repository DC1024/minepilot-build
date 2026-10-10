package dev.mcai.companion.embodiment;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Supplies the tiny subset of client acknowledgements required by a
 * clientless player and releases every outbound packet.
 *
 * <p>Chunk payloads are discarded because there is no renderer, but every
 * completed vanilla chunk batch is acknowledged at the normal client's
 * initial rate. Without that acknowledgement the server permanently
 * throttles the player's moving simulation window after its first batch,
 * leaving projectiles and mobs unticked after ordinary long travel.</p>
 */
final class HeadlessConnectionPump {
    private static final float DESIRED_CHUNKS_PER_TICK = 3.5F;

    private final Connection connection;
    private final EmbeddedChannel channel;
    private final ServerGamePacketListenerImpl listener;
    private final EndCreditsResponseGate endCreditsResponseGate =
            new EndCreditsResponseGate();

    private long discardedPackets;
    private long keepAliveAcknowledgements;
    private long teleportAcknowledgements;
    private long chunkBatchAcknowledgements;
    private long endCreditsRespawnRequests;
    private int largestDrain;
    private int outboundQueueHighWatermark;
    private int unreleasedOutboundPackets;
    private boolean disconnectionHandled;

    HeadlessConnectionPump(
            Connection connection,
            EmbeddedChannel channel,
            ServerGamePacketListenerImpl listener
    ) {
        this.connection = connection;
        this.channel = channel;
        this.listener = listener;
    }

    void tick() {
        if (connection.isConnected()) {
            connection.tick();
            if (!connection.isConnected()) {
                // Connection.tick() performs vanilla's one-shot disconnect
                // callback when the channel closes during listener ticking.
                disconnectionHandled = true;
            }
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            outboundQueueHighWatermark = Math.max(
                    outboundQueueHighWatermark,
                    channel.outboundMessages().size()
            );
            drainOutbound();
            channel.runPendingTasks();
            unreleasedOutboundPackets = channel.outboundMessages().size();
        } else {
            // A connection observed closed at a tick boundary has either been
            // handled by Connection.tick() or by close() below. Avoid invoking
            // vanilla's disconnect callback twice.
            disconnectionHandled = true;
            drainOutbound();
            unreleasedOutboundPackets = channel.outboundMessages().size();
        }
    }

    AuditSnapshot auditSnapshot() {
        return new AuditSnapshot(
                discardedPackets,
                keepAliveAcknowledgements,
                teleportAcknowledgements,
                chunkBatchAcknowledgements,
                endCreditsRespawnRequests,
                largestDrain,
                outboundQueueHighWatermark,
                unreleasedOutboundPackets,
                disconnectionHandled
        );
    }

    void handleDisconnectionOnce() {
        if (!disconnectionHandled) {
            disconnectionHandled = true;
            connection.handleDisconnection();
        }
    }

    /**
     * Refreshes the final release gauge after the owning session finishes the
     * EmbeddedChannel.  A disconnect itself can enqueue one last vanilla
     * packet, so taking the snapshot before {@code finishAndReleaseAll()}
     * would falsely report a live outbound item as leaked.
     */
    void markClosedAfterChannelFinish() {
        disconnectionHandled = true;
        unreleasedOutboundPackets = channel.outboundMessages().size();
    }

    private void drainOutbound() {
        int drained = 0;
        Object message;
        while ((message = channel.readOutbound()) != null) {
            drained++;
            try {
                if (message instanceof ClientboundKeepAlivePacket keepAlive) {
                    listener.handleKeepAlive(new ServerboundKeepAlivePacket(keepAlive.getId()));
                    keepAliveAcknowledgements++;
                } else if (message instanceof ClientboundPlayerPositionPacket position) {
                    listener.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(position.id()));
                    teleportAcknowledgements++;
                } else if (message
                        instanceof ClientboundChunkBatchFinishedPacket) {
                    listener.handleChunkBatchReceived(
                        new ServerboundChunkBatchReceivedPacket(
                            DESIRED_CHUNKS_PER_TICK
                        )
                    );
                    chunkBatchAcknowledgements++;
                } else if (message
                        instanceof ClientboundGameEventPacket gameEvent
                        && endCreditsResponseGate.claim(
                            gameEvent.getEvent()
                        )) {
                    listener.handleClientCommand(
                        new ServerboundClientCommandPacket(
                            ServerboundClientCommandPacket.Action
                                .PERFORM_RESPAWN
                        )
                    );
                    endCreditsRespawnRequests++;
                } else {
                    discardedPackets++;
                }
            } finally {
                ReferenceCountUtil.release(message);
            }
        }
        largestDrain = Math.max(largestDrain, drained);
        outboundQueueHighWatermark = Math.max(
                outboundQueueHighWatermark,
                drained
        );
        unreleasedOutboundPackets = channel.outboundMessages().size();
    }

    record AuditSnapshot(
            long discardedPackets,
            long keepAliveAcknowledgements,
            long teleportAcknowledgements,
            long chunkBatchAcknowledgements,
            long endCreditsRespawnRequests,
            int largestDrain,
            int outboundQueueHighWatermark,
            int unreleasedOutboundPackets,
            boolean disconnectionHandled
    ) {
    }
}
