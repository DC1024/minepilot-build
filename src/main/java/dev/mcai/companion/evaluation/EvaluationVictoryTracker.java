package dev.mcai.companion.evaluation;

import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.runtime.CompanionRuntime;
import dev.mcai.companion.progression.SurvivalMilestone;
import dev.mcai.companion.progression.ServerGoalCompletionVerifier;
import dev.mcai.companion.progression.SurvivalRouteTracker;
import dev.mcai.companion.world.CompanionWorldData;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * Server-authoritative victory evidence for locked Hardcore evaluations.
 *
 * <p>A model cannot self-certify this state. The dragon death must be caused
 * by the companion player and that same body must subsequently leave the End
 * through the vanilla end-conquered flow.</p>
 */
public final class EvaluationVictoryTracker {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private EvaluationVictoryTracker() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        LivingDeathEvent.BUS.addListener(
            EvaluationVictoryTracker::onLivingDeath
        );
        PlayerEvent.PlayerRespawnEvent.BUS.addListener(
            EvaluationVictoryTracker::onPlayerRespawn
        );
        TickEvent.ServerTickEvent.Post.BUS.addListener(
            event -> verifyCompletion(event.server())
        );
        CommandEvent.BUS.addListener(
            EvaluationVictoryTracker::cancelContaminatingCommand
        );
    }

    private static void onLivingDeath(final LivingDeathEvent event) {
        if (!(event.getEntity() instanceof EnderDragon dragon)) {
            return;
        }
        final ServerPlayer killer;
        if (event.getSource().getEntity()
                instanceof ServerPlayer sourcePlayer) {
            killer = sourcePlayer;
        } else if (dragon.getKillCredit()
                instanceof ServerPlayer creditedPlayer) {
            /*
             * Vanilla kill credit covers legitimate indirect player damage
             * such as arrows and explosions. It does not allow the model to
             * self-certify a kill.
             */
            killer = creditedPlayer;
        } else {
            return;
        }
        final var data = CompanionWorldData.get(killer.level().getServer());
        if (data.companionUuid().equals(killer.getUUID())) {
            data.markVerifiedRouteMilestones(
                data.goalRevision(),
                java.util.Set.of(SurvivalMilestone.DRAGON_KILLED)
            );
        }
        if (data.evaluationLocked()
                && data.companionUuid().equals(killer.getUUID())) {
            data.markEvaluationDragonKilled();
        }
    }

    private static void onPlayerRespawn(
        final PlayerEvent.PlayerRespawnEvent event
    ) {
        if (event.isEndConquered()
                && event.getEntity() instanceof ServerPlayer player) {
            markReturned(player);
        }
    }

    private static void markReturned(final ServerPlayer player) {
        final var data = CompanionWorldData.get(player.level().getServer());
        if (data.companionUuid().equals(player.getUUID())) {
            if (data.verifiedRouteProgress(data.goalRevision())
                    .milestones()
                    .contains(SurvivalMilestone.DRAGON_KILLED)) {
                data.markVerifiedRouteMilestones(
                    data.goalRevision(),
                    java.util.Set.of(
                        SurvivalMilestone.RETURNED_FROM_END
                    )
                );
            }
            data.markEvaluationReturnedFromEnd();
        }
    }

    private static void verifyCompletion(
        final net.minecraft.server.MinecraftServer server
    ) {
        final var runtime = CompanionRuntime.active()
            .filter(candidate -> candidate.server() == server);
        if (runtime.isEmpty()) {
            return;
        }
        if (runtime.orElseThrow().worldData().evaluationLocked()
                && evaluationIntegrityViolated(
                    server,
                    runtime.orElseThrow().worldData()
                )) {
            contaminateEvaluation(
                runtime.orElseThrow(),
                "evaluation_observer_or_body_state_changed"
            );
        }
        final var state = runtime.orElseThrow().goals().snapshot();
        if (state.source() == GoalSource.HARDCORE_EVALUATION
                && SurvivalRouteTracker.isFoundationGoal(state)) {
            final CompanionWorldData data =
                    runtime.orElseThrow().worldData();
            final boolean foundationVerified =
                    state.status() == GoalStatus.RUNNING
                        && !data.evaluationContaminated()
                        && !data.hardcoreDead()
                        && data.evaluationFinishedGameTick() < 0
                        && data.evaluationClockValid(
                                server.overworld().getGameTime()
                        )
                        && new ServerGoalCompletionVerifier(data)
                                .verify(state)
                                .accepted();
            if (foundationVerified) {
                runtime.orElseThrow().goals().markTerminal(
                        GoalStatus.COMPLETED,
                        "foundation_route_verified"
                );
                data.finishEvaluation(
                        server.overworld().getGameTime()
                );
                EvaluationResultWriter.write(
                        server,
                        data,
                        runtime.orElseThrow().goals().snapshot()
                );
            } else {
                finishTerminalEvaluation(
                        server,
                        runtime.orElseThrow()
                );
            }
            return;
        }
        if (state.status() != GoalStatus.RUNNING
                || state.source() != GoalSource.HARDCORE_EVALUATION
                || !runtime.orElseThrow().worldData()
                    .evaluationVictoryVerified()) {
            finishTerminalEvaluation(server, runtime.orElseThrow());
            return;
        }
        runtime.orElseThrow().goals().markTerminal(
            GoalStatus.COMPLETED,
            "dragon_killed_and_returned"
        );
        runtime.orElseThrow().worldData().finishEvaluation(
            server.overworld().getGameTime()
        );
        EvaluationResultWriter.write(
            server,
            runtime.orElseThrow().worldData(),
            runtime.orElseThrow().goals().snapshot()
        );
    }

    private static void finishTerminalEvaluation(
        final net.minecraft.server.MinecraftServer server,
        final dev.mcai.companion.runtime.ServerRuntime runtime
    ) {
        final var state = runtime.goals().snapshot();
        final var data = runtime.worldData();
        if (state.source() == GoalSource.HARDCORE_EVALUATION
                && state.status() != GoalStatus.RUNNING
                && state.status() != GoalStatus.CANCEL_PENDING
                && data.evaluationStartedGameTick() >= 0
                && data.evaluationFinishedGameTick() < 0) {
            data.finishEvaluation(server.overworld().getGameTime());
            EvaluationResultWriter.write(
                server,
                data,
                state
            );
        }
    }

    private static boolean cancelContaminatingCommand(
            final CommandEvent event
    ) {
        final var source =
            event.getParseResults().getContext().getSource();
        final var runtime = CompanionRuntime.active()
            .filter(candidate ->
                candidate.server() == source.getServer()
            );
        if (runtime.isEmpty()
                || !runtime.orElseThrow().worldData()
                    .evaluationLocked()) {
            return false;
        }
        final String command = normalizeCommand(
            event.getParseResults().getReader().getString()
        );
        if (isReadOnlyEvaluationCommand(command)) {
            return false;
        }
        contaminateEvaluation(
            runtime.orElseThrow(),
            "evaluation_command_attempted"
        );
        source.sendFailure(Component.literal(
            "[AI] Command blocked: the locked Hardcore evaluation "
                + "has been marked contaminated."
        ));
        return true;
    }

    private static boolean evaluationIntegrityViolated(
            final net.minecraft.server.MinecraftServer server,
            final CompanionWorldData data
    ) {
        final long gameTick = server.overworld().getGameTime();
        if (!isTrueHardcoreWorld(server)
                || data.evaluationContaminated()
                || data.hardcoreDead()
                || !data.evaluationClockValid(gameTick)) {
            return true;
        }
        int companionPlayers = 0;
        for (ServerPlayer player :
                server.getPlayerList().getPlayers()) {
            if (data.companionUuid().equals(player.getUUID())) {
                companionPlayers++;
                final var abilities = player.getAbilities();
                if (player.gameMode.getGameModeForPlayer()
                            != GameType.SURVIVAL
                        || !player.isAlive()
                        || player.getHealth() <= 0.0F
                        || abilities.instabuild
                        || abilities.flying
                        || abilities.mayfly
                        || abilities.invulnerable) {
                    return true;
                }
            } else if (player.gameMode.getGameModeForPlayer()
                    != GameType.SPECTATOR) {
                return true;
            }
        }
        /*
         * During the asynchronous initial spawn bodyEverSpawned is false.
         * Once a body has become active, disappearance or duplication is a
         * permanent integrity failure rather than an invitation to respawn.
         */
        if (companionPlayers > 1) {
            return true;
        }
        if (!data.bodyEverSpawned() || companionPlayers == 1) {
            return false;
        }
        /*
         * A persisted RUNNING evaluation legitimately has no online body for
         * the short vanilla PrepareSpawnTask window after a server restart.
         * Every other post-spawn disappearance is represented by FAILED or
         * ABSENT and remains a permanent integrity violation.
         */
        return companionPlayers != 0
            || AiPlayerManager.status(server).state()
                != SessionState.PREPARING;
    }

    public static boolean isTrueHardcoreWorld(
            final net.minecraft.server.MinecraftServer server
    ) {
        return server.isHardcore()
            && server.getWorldData().getDifficulty() == Difficulty.HARD
            && hasStandardSurvivalRules(
                server.overworld().getGameRules()
            );
    }

    private static boolean hasStandardSurvivalRules(
            final GameRules rules
    ) {
        return !rules.get(GameRules.KEEP_INVENTORY)
            && rules.get(GameRules.FALL_DAMAGE)
            && rules.get(GameRules.FIRE_DAMAGE)
            && rules.get(GameRules.DROWNING_DAMAGE)
            && rules.get(GameRules.FREEZE_DAMAGE)
            && rules.get(GameRules.SPAWN_MOBS)
            && rules.get(GameRules.SPAWN_MONSTERS)
            && rules.get(GameRules.MOB_DROPS)
            && rules.get(GameRules.ENTITY_DROPS)
            && rules.get(GameRules.BLOCK_DROPS)
            && rules.get(GameRules.MOB_GRIEFING)
            && rules.get(GameRules.NATURAL_HEALTH_REGENERATION)
            && rules.get(GameRules.PLAYER_MOVEMENT_CHECK)
            && rules.get(GameRules.ELYTRA_MOVEMENT_CHECK)
            && rules.get(GameRules.TNT_EXPLODES)
            && rules.get(GameRules.RANDOM_TICK_SPEED) == 3;
    }

    private static void contaminateEvaluation(
            final dev.mcai.companion.runtime.ServerRuntime runtime,
            final String code
    ) {
        runtime.worldData().markEvaluationContaminated();
        final var goal = runtime.goals().snapshot();
        if (goal.source() == GoalSource.HARDCORE_EVALUATION
                && (goal.status() == GoalStatus.RUNNING
                    || goal.status()
                        == GoalStatus.CANCEL_PENDING)) {
            runtime.goals().markTerminal(
                GoalStatus.FAILED,
                code
            );
        }
    }

    static String normalizeCommand(final String raw) {
        String command = raw == null
            ? ""
            : raw.strip().toLowerCase(java.util.Locale.ROOT);
        while (command.startsWith("/")) {
            command = command.substring(1).stripLeading();
        }
        return command.replaceAll("\\s+", " ");
    }

    static boolean isReadOnlyEvaluationCommand(
            final String command
    ) {
        return command.equals("stop")
            || command.equals("save-all")
            || command.equals("save-all flush")
            || command.equals("list")
            || command.equals("help")
            || command.startsWith("help ")
            || command.equals("mcai goal status")
            || command.equals("mcai mcp")
            || command.equals("mcai mcp status")
            || command.equals("mcai model status")
            || command.equals("mcai embodiment status")
            || command.equals("mcai skin status");
    }
}
