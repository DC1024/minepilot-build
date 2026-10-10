package dev.mcai.companion.runtime;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import dev.mcai.companion.model.CapabilityProbeOutcome;
import dev.mcai.companion.modelsetup.EvaluationModelLock;
import dev.mcai.companion.security.CompanionCommandAccess;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * Explicit, secret-free model setup diagnostics. No command accepts an API
 * key as chat text.
 */
final class ModelCommands {
    private ModelCommands() {
    }

    static void register(final RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("mcai")
                .requires(Commands.hasPermission(Commands.LEVEL_ALL))
                .then(Commands.literal("model")
                    .executes(ModelCommands::status)
                    .then(Commands.literal("status")
                        .executes(ModelCommands::status))
                    .then(Commands.literal("probe")
                        .requires(CompanionCommandAccess::mayAdmin)
                        .executes(ModelCommands::probe)))
        );
    }

    private static int status(
        final CommandContext<CommandSourceStack> context
    ) {
        final CommandSourceStack source = context.getSource();
        final var runtime = CompanionRuntime.active()
            .filter(active -> active.server() == source.getServer());
        if (runtime.isEmpty()) {
            source.sendFailure(Component.literal("[AI] Model runtime is unavailable."));
            return 0;
        }
        final ModelRuntime.SetupSnapshot status =
            runtime.orElseThrow().model().snapshot();
        final boolean evaluationLocked = EvaluationModelLock.isLocked(
            source.getServer(),
            runtime.orElseThrow().model()
        );
        final String endpoint = status.endpointConfigured()
            ? status.origin() + " model=" + status.modelName()
            : "not configured (" + status.configurationErrorCode() + ")";
        final String profile = status.capabilities()
            .map(value -> value.protocol() + "/" + value.outputContract())
            .orElse("unverified");
        source.sendSuccess(
            () -> Component.literal(
                "[AI] model: endpoint="
                    + endpoint
                    + ", credential="
                    + status.credentialAvailable()
                    + ", profile="
                    + profile
                    + ", ready="
                    + status.gatewayReady()
                    + ", probing="
                    + status.probeInFlight()
                    + ", evaluationFrozen="
                    + evaluationLocked
            ),
            false
        );
        return Command.SINGLE_SUCCESS;
    }

    private static int probe(
        final CommandContext<CommandSourceStack> context
    ) {
        final CommandSourceStack source = context.getSource();
        if (!CompanionCommandAccess.mayAdmin(source)) {
            source.sendFailure(Component.literal(
                "Only the local owner or a server gamemaster may probe the model."
            ));
            return 0;
        }
        final var runtime = CompanionRuntime.active()
            .filter(active -> active.server() == source.getServer());
        if (runtime.isEmpty()) {
            source.sendFailure(Component.literal("[AI] Model runtime is unavailable."));
            return 0;
        }
        if (EvaluationModelLock.isLocked(
                source.getServer(),
                runtime.orElseThrow().model()
        )) {
            source.sendFailure(Component.literal(
                "[AI] Model probing is disabled after a Hardcore evaluation starts."
            ));
            return 0;
        }
        if (runtime.orElseThrow().model().snapshot().evaluationModelFrozen()) {
            source.sendFailure(Component.literal(
                "[AI] Model probing is disabled while the Hardcore evaluation model is reserved or frozen."
            ));
            return 0;
        }

        source.sendSuccess(
            () -> Component.literal(
                "[AI] Starting one explicit capability probe; only explicit unsupported responses may trigger a fallback request."
            ),
            false
        );
        runtime.orElseThrow().model().probeExplicitly().whenComplete(
            (outcome, throwable) -> source.getServer().execute(() ->
                reportProbe(source, outcome, throwable)
            )
        );
        return Command.SINGLE_SUCCESS;
    }

    private static void reportProbe(
        final CommandSourceStack source,
        final CapabilityProbeOutcome outcome,
        final Throwable throwable
    ) {
        if (throwable != null || outcome == null) {
            source.sendFailure(Component.literal(
                "[AI] Capability probe failed locally; no exception details were logged to chat."
            ));
            return;
        }
        if (outcome instanceof CapabilityProbeOutcome.Supported supported) {
            source.sendSuccess(
                () -> Component.literal(
                    "[AI] Capability probe verified "
                        + supported.capabilities().protocol()
                        + "/"
                        + supported.capabilities().outputContract()
                        + " in "
                        + supported.requestsMade()
                        + " request(s)."
                ),
                false
            );
            return;
        }
        final CapabilityProbeOutcome.Failure failure =
            (CapabilityProbeOutcome.Failure) outcome;
        source.sendFailure(Component.literal(
            "[AI] Capability probe stopped safely: "
                + failure.error().kind()
                + " after "
                + failure.requestsMade()
                + " request(s)."
        ));
    }
}
