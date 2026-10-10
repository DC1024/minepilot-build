package dev.mcai.companion.modelsetup.network;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.modelsetup.ModelSetupModule;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

public final class ModelSetupNetwork {
    private static final int PROTOCOL_VERSION = 3;
    private static final SimpleChannel CHANNEL = createChannel();

    private ModelSetupNetwork() {
    }

    public static void initialize() {
        // Forces channel construction during common mod initialization.
    }

    public static void requestState(final long requestId) {
        CHANNEL.send(
            new ServerboundModelSetupOpen(requestId),
            PacketDistributor.SERVER.noArg()
        );
    }

    public static void apply(final ServerboundModelSetupApply message) {
        CHANNEL.send(message, PacketDistributor.SERVER.noArg());
    }

    public static void sendState(
        final ServerPlayer player,
        final ClientboundModelSetupState state
    ) {
        if (CHANNEL.isRemotePresent(player.connection.getConnection())) {
            CHANNEL.send(state, PacketDistributor.PLAYER.with(player));
        }
    }

    private static SimpleChannel createChannel() {
        final SimpleChannel channel = ChannelBuilder.named(
            Identifier.fromNamespaceAndPath(
                MinecraftAiCompanion.MOD_ID,
                "model_setup"
            )
        )
            .networkProtocolVersion(PROTOCOL_VERSION)
            .simpleChannel();
        channel.messageBuilder(
                ServerboundModelSetupOpen.class,
                0,
                NetworkDirection.PLAY_TO_SERVER
            )
            .encoder(ModelSetupNetwork::encodeOpen)
            .decoder(ModelSetupNetwork::decodeOpen)
            .consumerMainThread((message, context) -> {
                if (context.isServerSide() && context.getSender() != null) {
                    ModelSetupModule.handleOpen(context.getSender(), message);
                }
            })
            .add();
        channel.messageBuilder(
                ServerboundModelSetupApply.class,
                1,
                NetworkDirection.PLAY_TO_SERVER
            )
            .encoder(ModelSetupNetwork::encodeApply)
            .decoder(ModelSetupNetwork::decodeApply)
            .consumerMainThread((message, context) -> {
                try {
                    if (context.isServerSide()
                        && context.getSender() != null) {
                        ModelSetupModule.handleApply(
                            context.getSender(),
                            message
                        );
                    }
                } finally {
                    message.destroy();
                }
            })
            .add();
        channel.messageBuilder(
                ClientboundModelSetupState.class,
                2,
                NetworkDirection.PLAY_TO_CLIENT
            )
            .encoder(ModelSetupNetwork::encodeState)
            .decoder(ModelSetupNetwork::decodeState)
            .consumerMainThread((message, context) -> {
                if (context.isClientSide()) {
                    ModelSetupClientBridge.accept(message);
                }
            })
            .add();
        return channel.build();
    }

    static void encodeOpen(
        final ServerboundModelSetupOpen message,
        final FriendlyByteBuf buffer
    ) {
        buffer.writeVarLong(message.requestId());
    }

    static ServerboundModelSetupOpen decodeOpen(
        final FriendlyByteBuf buffer
    ) {
        return new ServerboundModelSetupOpen(buffer.readVarLong());
    }

    static void encodeApply(
        final ServerboundModelSetupApply message,
        final FriendlyByteBuf buffer
    ) {
        try {
            buffer.writeVarLong(message.requestId());
            buffer.writeByteArray(message.sessionTokenUnsafe());
            buffer.writeByteArray(message.apiKeyUtf8Unsafe());
            buffer.writeUtf(
                message.baseUrl(),
                ModelSetupWireLimits.MAX_BASE_URL_CHARACTERS
            );
            buffer.writeUtf(
                message.modelName(),
                ModelSetupWireLimits.MAX_MODEL_NAME_CHARACTERS
            );
            buffer.writeUtf(
                message.agentName(),
                ModelSetupWireLimits.MAX_AGENT_NAME_CHARACTERS
            );
            buffer.writeUtf(
                message.accentColor(),
                ModelSetupWireLimits.MAX_ACCENT_COLOR_CHARACTERS
            );
            buffer.writeDouble(message.temperature());
            buffer.writeUtf(
                message.systemPrompt(),
                ModelSetupWireLimits.MAX_SYSTEM_PROMPT_CHARACTERS
            );
            buffer.writeBoolean(message.onboardingCompleted());
            buffer.writeBoolean(message.preferPersistentCredential());
        } finally {
            message.destroy();
        }
    }

    static ServerboundModelSetupApply decodeApply(
        final FriendlyByteBuf buffer
    ) {
        return new ServerboundModelSetupApply(
            buffer.readVarLong(),
            buffer.readByteArray(
                dev.mcai.companion.modelsetup.ModelSetupSessionRegistry
                    .TOKEN_BYTES
            ),
            buffer.readByteArray(
                ModelSetupWireLimits.MAX_API_KEY_UTF8_BYTES
            ),
            buffer.readUtf(ModelSetupWireLimits.MAX_BASE_URL_CHARACTERS),
            buffer.readUtf(ModelSetupWireLimits.MAX_MODEL_NAME_CHARACTERS),
            buffer.readUtf(
                ModelSetupWireLimits.MAX_AGENT_NAME_CHARACTERS
            ),
            buffer.readUtf(
                ModelSetupWireLimits.MAX_ACCENT_COLOR_CHARACTERS
            ),
            buffer.readDouble(),
            buffer.readUtf(
                ModelSetupWireLimits.MAX_SYSTEM_PROMPT_CHARACTERS
            ),
            buffer.readBoolean(),
            buffer.readBoolean()
        );
    }

    static void encodeState(
        final ClientboundModelSetupState message,
        final FriendlyByteBuf buffer
    ) {
        buffer.writeVarLong(message.requestId());
        buffer.writeByteArray(message.sessionTokenUnsafe());
        buffer.writeUtf(
            message.baseUrl(),
            ModelSetupWireLimits.MAX_BASE_URL_CHARACTERS
        );
        buffer.writeUtf(
            message.modelName(),
            ModelSetupWireLimits.MAX_MODEL_NAME_CHARACTERS
        );
        buffer.writeUtf(
            message.agentName(),
            ModelSetupWireLimits.MAX_AGENT_NAME_CHARACTERS
        );
        buffer.writeUtf(
            message.accentColor(),
            ModelSetupWireLimits.MAX_ACCENT_COLOR_CHARACTERS
        );
        buffer.writeDouble(message.temperature());
        buffer.writeUtf(
            message.systemPrompt(),
            ModelSetupWireLimits.MAX_SYSTEM_PROMPT_CHARACTERS
        );
        buffer.writeBoolean(message.onboardingCompleted());
        buffer.writeBoolean(message.bodyActive());
        buffer.writeBoolean(message.canEdit());
        buffer.writeBoolean(message.credentialAvailable());
        buffer.writeBoolean(message.evaluationLocked());
        buffer.writeBoolean(message.probeInFlight());
        buffer.writeBoolean(message.gatewayReady());
        buffer.writeBoolean(message.restartRequired());
        buffer.writeUtf(
            message.statusCode(),
            ModelSetupWireLimits.MAX_STATUS_CODE_CHARACTERS
        );
    }

    static ClientboundModelSetupState decodeState(
        final FriendlyByteBuf buffer
    ) {
        return new ClientboundModelSetupState(
            buffer.readVarLong(),
            buffer.readByteArray(
                dev.mcai.companion.modelsetup.ModelSetupSessionRegistry
                    .TOKEN_BYTES
            ),
            buffer.readUtf(ModelSetupWireLimits.MAX_BASE_URL_CHARACTERS),
            buffer.readUtf(ModelSetupWireLimits.MAX_MODEL_NAME_CHARACTERS),
            buffer.readUtf(
                ModelSetupWireLimits.MAX_AGENT_NAME_CHARACTERS
            ),
            buffer.readUtf(
                ModelSetupWireLimits.MAX_ACCENT_COLOR_CHARACTERS
            ),
            buffer.readDouble(),
            buffer.readUtf(
                ModelSetupWireLimits.MAX_SYSTEM_PROMPT_CHARACTERS
            ),
            buffer.readBoolean(),
            buffer.readBoolean(),
            buffer.readBoolean(),
            buffer.readBoolean(),
            buffer.readBoolean(),
            buffer.readBoolean(),
            buffer.readBoolean(),
            buffer.readBoolean(),
            buffer.readUtf(ModelSetupWireLimits.MAX_STATUS_CODE_CHARACTERS)
        );
    }
}
