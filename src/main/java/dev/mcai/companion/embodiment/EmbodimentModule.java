package dev.mcai.companion.embodiment;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import dev.mcai.companion.skin.AiProfileMarker;
import dev.mcai.companion.runtime.CompanionRuntime;
import net.minecraft.server.level.ServerPlayer;

/**
 * Entry point for the M0 embodiment subsystem.
 *
 * <p>The mod constructor should call {@link #register()} exactly once.</p>
 */
public final class EmbodimentModule {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private EmbodimentModule() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        RegisterCommandsEvent.BUS.addListener(EmbodimentCommands::register);
        PlayerEvent.TabListNameFormat.BUS.addListener(event -> {
            if (event.getEntity() instanceof ServerPlayer player
                    && AiProfileMarker.isMarked(
                            player.getGameProfile()
                    )) {
                final MinecraftServer server = player.level().getServer();
                final boolean online = server != null
                        && AiPlayerManager.status(server).online();
                final Component presence = Component.literal(
                        online ? "  ● online" : "  ○ offline"
                ).withStyle(
                        online
                                ? ChatFormatting.GREEN
                                : ChatFormatting.DARK_GRAY
                );
                event.setDisplayName(
                        Component.literal("[AI] ")
                                .withStyle(ChatFormatting.AQUA)
                                .append(Component.literal(
                                        player.getGameProfile().name()
                                ))
                                .append(presence)
                );
            }
        });
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> {
            if (!(event.getEntity() instanceof ServerPlayer player)
                    || AiProfileMarker.isMarked(
                            player.getGameProfile()
                    )) {
                return;
            }
            AiPlayerManager.ensureSpawnNear(
                    player.level().getServer(),
                    player
            );
        });
        TickEvent.ServerTickEvent.Post.BUS.addListener(event -> AiPlayerManager.tickServer(event.server()));
        ServerStoppingEvent.BUS.addListener(event -> {
            final var finalAudit = AiPlayerManager.shutdownWithAudit(
                    event.getServer()
            );
            CompanionRuntime.recordFinalTransportAudit(
                    event.getServer(),
                    finalAudit
            );
        });
        ServerStoppedEvent.BUS.addListener(event -> AiPlayerManager.shutdown(event.getServer()));
    }
}
