package dev.mcai.companion.embodiment;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.runtime.CompanionRuntime;
import dev.mcai.companion.security.CompanionCommandAccess;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;

final class EmbodimentCommands {
    private EmbodimentCommands() {
    }

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("mcai")
                        .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                        .then(Commands.literal("embodiment")
                                .executes(EmbodimentCommands::status)
                                .then(Commands.literal("status")
                                        .executes(EmbodimentCommands::status))
                                .then(Commands.literal("spawn")
                                        .requires(CompanionCommandAccess::mayAdmin)
                                        .executes(EmbodimentCommands::spawn))
                                .then(Commands.literal("remove")
                                        .requires(CompanionCommandAccess::mayAdmin)
                                        .executes(EmbodimentCommands::remove)))
        );
    }

    private static int spawn(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!CompanionCommandAccess.mayAdmin(source)) {
            source.sendFailure(Component.literal("Only the integrated-server owner or a server gamemaster may spawn the AI."));
            return 0;
        }
        if (evaluationLocked(source)) {
            source.sendFailure(Component.literal(
                "[AI] Embodiment commands are locked for the active Hardcore evaluation."
            ));
            return 0;
        }

        AiPlayerManager.OperationResult result = AiPlayerManager.requestSpawn(source.getServer());
        sendOperationResult(source, result);
        return result.accepted() ? Command.SINGLE_SUCCESS : 0;
    }

    private static int remove(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!CompanionCommandAccess.mayAdmin(source)) {
            source.sendFailure(Component.literal("Only the integrated-server owner or a server gamemaster may remove the AI."));
            return 0;
        }
        if (evaluationLocked(source)) {
            source.sendFailure(Component.literal(
                "[AI] Embodiment commands are locked for the active Hardcore evaluation."
            ));
            return 0;
        }

        AiPlayerManager.OperationResult result = AiPlayerManager.requestRemove(source.getServer());
        if (result.accepted()) {
            CompanionRuntime.active()
                .filter(runtime -> runtime.server() == source.getServer())
                .filter(runtime -> runtime.goals().snapshot().status() == GoalStatus.RUNNING)
                .ifPresent(runtime ->
                    runtime.goals().requestCancel(GoalSource.PLAYER_CHAT)
                );
        }
        sendOperationResult(source, result);
        return result.accepted() ? Command.SINGLE_SUCCESS : 0;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        AiPlayerManager.Status status = AiPlayerManager.status(source.getServer());
        String failure = status.failureCode().isEmpty() ? "" : ", reason=" + status.failureCode();
        source.sendSuccess(
                () -> Component.literal("[AI] " + status.profileName()
                        + ": state=" + status.state()
                        + ", online=" + status.online()
                        + ", session=" + status.sessionGeneration()
                        + failure),
                false
        );
        return Command.SINGLE_SUCCESS;
    }

    private static void sendOperationResult(
            CommandSourceStack source,
            AiPlayerManager.OperationResult result
    ) {
        Component message = Component.literal("[AI] embodiment: " + result.code());
        if (result.accepted()) {
            source.sendSuccess(() -> message, false);
        } else {
            source.sendFailure(message);
        }
    }

    private static boolean evaluationLocked(final CommandSourceStack source) {
        return CompanionRuntime.active()
            .filter(runtime -> runtime.server() == source.getServer())
            .map(runtime -> runtime.goals().snapshot().externalWritesLocked())
            .orElse(false);
    }
}
