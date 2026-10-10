package dev.mcai.companion.communication;

import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;

import dev.mcai.companion.evaluation.EvaluationRoute;
import dev.mcai.companion.runtime.CompanionRuntime;
import dev.mcai.companion.security.CompanionCommandAccess;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;

/**
 * Natural player chat and evaluation commands. Chat is heard by the same fair
 * companion model; it is not promoted directly into a gameplay goal here.
 */
public final class CommunicationModule {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private CommunicationModule() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ServerChatEvent.BUS.addListener(CommunicationModule::onChat);
        RegisterCommandsEvent.BUS.addListener(CommunicationModule::registerCommands);
    }

    private static void onChat(final ServerChatEvent event) {
        final var source = event.getPlayer().createCommandSourceStack();
        final var runtime = CompanionRuntime.active()
            .filter(candidate -> candidate.server() == source.getServer());
        if (runtime.isEmpty()) {
            return;
        }
        final var active = runtime.orElseThrow();
        if (event.getPlayer().getUUID().equals(
                active.worldData().companionUuid()
        )) {
            return;
        }
        final String raw = event.getRawText();
        final ChatAddressing.Parsed addressed =
            ChatAddressing.parse(
                raw,
                active.worldData().displayName()
            );
        /*
         * Server implementation type is not the same thing as conversation
         * audience: an integrated server may be opened to LAN, while a
         * dedicated server can temporarily have only one human. Count only
         * real human players and exclude this companion's visible
         * ServerPlayer. In a one-human world ordinary chat is addressed; with
         * multiple humans the normal explicit @/name boundary applies.
         */
        final int humanPlayerCount = (int) source.getServer()
                .getPlayerList()
                .getPlayers()
                .stream()
                .filter(player -> !player.getUUID().equals(
                        active.worldData().companionUuid()
                ))
                .count();
        final boolean singlePlayer = humanPlayerCount <= 1;
        active.conversation().submit(
            event.getPlayer(),
            raw,
            addressed.message(),
            ChatAddressing.addressedForServer(addressed, humanPlayerCount),
            CompanionCommandAccess.mayControlCompanion(source),
            singlePlayer
        );
    }

    private static void registerCommands(final RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("mcai")
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .then(Commands.literal("goal")
                    .requires(CompanionCommandAccess::mayAdmin)
                    .then(Commands.literal("status").executes(CommunicationModule::goalStatus)))
                .then(Commands.literal("evaluation")
                    .then(Commands.literal("start")
                        .requires(CompanionCommandAccess::mayAdmin)
                        .executes(context -> startEvaluation(
                                context,
                                EvaluationRoute.COMPLETION
                        ))
                        .then(Commands.literal("completion")
                            .executes(context -> startEvaluation(
                                    context,
                                    EvaluationRoute.COMPLETION
                            )))
                        .then(Commands.literal("foundation")
                            .executes(context -> startEvaluation(
                                    context,
                                    EvaluationRoute.FOUNDATION
                            )))))
        );
    }

    private static int goalStatus(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final var runtime = CompanionRuntime.active()
            .filter(candidate -> candidate.server() == source.getServer());
        if (runtime.isEmpty()) {
            source.sendFailure(Component.literal("[AI] Companion runtime is not ready."));
            return 0;
        }
        final var goal = runtime.orElseThrow().goals().snapshot();
        source.sendSuccess(
            () -> Component.literal("[AI] goal=" + goal.status()
                + ", revision=" + goal.revision()
                + ", locked=" + goal.externalWritesLocked()
                + (goal.goal().isEmpty() ? "" : ", text=" + goal.goal())),
            false
        );
        return Command.SINGLE_SUCCESS;
    }

    private static int startEvaluation(
            final CommandContext<CommandSourceStack> context,
            final EvaluationRoute route
    ) {
        final CommandSourceStack source = context.getSource();
        if (!CompanionCommandAccess.mayAdmin(source)) {
            source.sendFailure(Component.literal("[AI] Evaluation start is restricted."));
            return 0;
        }
        final var runtime = CompanionRuntime.active()
            .filter(candidate -> candidate.server() == source.getServer());
        if (runtime.isEmpty()) {
            source.sendFailure(Component.literal("[AI] Companion runtime is not ready."));
            return 0;
        }
        final var start = runtime.orElseThrow()
            .modelBootstrap()
            .requestEvaluationStart(route);
        if (!start.accepted()) {
            source.sendFailure(Component.literal(
                "[AI] Evaluation start rejected: " + start.code()
            ));
            return 0;
        }
        source.sendSuccess(
            () -> Component.literal(
                start.started()
                    ? "[AI] Hardcore evaluation started. Chat, MCP "
                        + "writes, new waypoints, and model profile "
                        + "changes are now locked. Route="
                        + route.name()
                        + "."
                    : "[AI] Verifying the configured model once. If the "
                        + "probe succeeds, the same command will "
                        + "automatically start the body, goal, and timer "
                        + "after all Hardcore conditions are rechecked."
            ),
            false
        );
        return Command.SINGLE_SUCCESS;
    }
}
