package dev.mcai.companion.skin.network;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.skin.ArmType;
import dev.mcai.companion.skin.SkinWireSnapshot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

public final class SkinNetwork {
    private static final int PROTOCOL_VERSION = 1;
    private static final SimpleChannel CHANNEL = createChannel();

    private SkinNetwork() {
    }

    /**
     * Forces channel creation during normal mod initialization.
     */
    public static void initialize() {
    }

    public static boolean canSendTo(final ServerPlayer player) {
        return CHANNEL.isRemotePresent(player.connection.getConnection());
    }

    public static void send(
        final ServerPlayer player,
        final SkinWireSnapshot snapshot
    ) {
        if (!canSendTo(player)) {
            return;
        }
        for (ClientboundSkinChunk chunk : SkinWireProtocol.chunks(snapshot)) {
            CHANNEL.send(chunk, PacketDistributor.PLAYER.with(player));
        }
    }

    public static void clear(
        final ServerPlayer player,
        final java.util.UUID companionId
    ) {
        if (canSendTo(player)) {
            CHANNEL.send(
                new ClientboundSkinClear(companionId),
                PacketDistributor.PLAYER.with(player)
            );
        }
    }

    public static void broadcast(
        final MinecraftServer server,
        final SkinWireSnapshot snapshot
    ) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player, snapshot);
        }
    }

    public static void broadcastClear(
        final MinecraftServer server,
        final java.util.UUID companionId
    ) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            clear(player, companionId);
        }
    }

    private static SimpleChannel createChannel() {
        final SimpleChannel channel = ChannelBuilder.named(
            Identifier.fromNamespaceAndPath(
                MinecraftAiCompanion.MOD_ID,
                "skin_sync"
            )
        )
            .networkProtocolVersion(PROTOCOL_VERSION)
            .simpleChannel();
        channel.messageBuilder(
                ClientboundSkinChunk.class,
                0,
                NetworkDirection.PLAY_TO_CLIENT
            )
            .encoder(SkinNetwork::encodeChunk)
            .decoder(SkinNetwork::decodeChunk)
            .consumerMainThread((message, context) -> {
                if (context.isClientSide()) {
                    SkinClientBridge.accept(message);
                }
            })
            .add();
        channel.messageBuilder(
                ClientboundSkinClear.class,
                1,
                NetworkDirection.PLAY_TO_CLIENT
            )
            .encoder((message, buffer) ->
                buffer.writeUUID(message.companionId())
            )
            .decoder(buffer ->
                new ClientboundSkinClear(buffer.readUUID())
            )
            .consumerMainThread((message, context) -> {
                if (context.isClientSide()) {
                    SkinClientBridge.clear(message.companionId());
                }
            })
            .add();
        return channel.build();
    }

    static void encodeChunk(
        final ClientboundSkinChunk message,
        final FriendlyByteBuf buffer
    ) {
        buffer.writeUUID(message.companionId());
        buffer.writeUtf(message.sha256(), 64);
        buffer.writeByte(message.armType().wireId());
        buffer.writeVarInt(message.totalLength());
        buffer.writeVarInt(message.chunkIndex());
        buffer.writeVarInt(message.chunkCount());
        buffer.writeByteArray(message.bytes());
    }

    static ClientboundSkinChunk decodeChunk(final FriendlyByteBuf buffer) {
        return new ClientboundSkinChunk(
            buffer.readUUID(),
            buffer.readUtf(64),
            ArmType.fromWireId(buffer.readUnsignedByte()),
            buffer.readVarInt(),
            buffer.readVarInt(),
            buffer.readVarInt(),
            buffer.readByteArray(SkinWireProtocol.CHUNK_BYTES)
        );
    }
}
