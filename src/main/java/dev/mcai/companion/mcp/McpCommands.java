package dev.mcai.companion.mcp;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;

import dev.mcai.companion.runtime.CompanionRuntime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;

public final class McpCommands {
    private McpCommands() {
    }

    public static void register(final RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("mcai")
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .then(Commands.literal("mcp")
                    .executes(McpCommands::status)
                    .then(Commands.literal("status")
                        .executes(McpCommands::status)))
        );
    }

    private static int status(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final var info = CompanionRuntime.mcpConnectionInfo(source.getServer());
        if (info.isEmpty()) {
            source.sendSuccess(
                () -> Component.literal("[AI] MCP is disabled or failed to bind. Enable mcp.enabled and restart."),
                false
            );
            return 0;
        }
        source.sendSuccess(
            () -> Component.literal("[AI] MCP: http://127.0.0.1:"
                + info.orElseThrow().port() + LoopbackMcpServer.PATH),
            false
        );
        return Command.SINGLE_SUCCESS;
    }
}
