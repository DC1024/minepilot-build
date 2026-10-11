package dev.mcai.companion.autonomy;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dev.mcai.companion.CompanionConfig;
import dev.mcai.companion.control.GoalCoordinator;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.model.DecisionContext;
import dev.mcai.companion.model.ModelGateway;
import dev.mcai.companion.model.ModelOutcome;
import dev.mcai.companion.model.PlannerInput;
import dev.mcai.companion.model.SkillArgumentValidator;
import dev.mcai.companion.runtime.ServerRuntime;

/**
 * Periodically asks the configured model for the next objective while the
 * companion is idle and no external principal (player chat, MCP, or a locked
 * hardcore evaluation) owns the goal. The proposed objective is fed back
 * through the normal {@link GoalCoordinator} so the existing brain, skill
 * supervisor and fair actuator pursue it exactly like any player-assigned
 * goal. Execution never bypasses {@code FairPlayerActuator}, so autonomous
 * goals stay within the same honest, packet-level movement envelope.
 *
 * <p>This driver is intentionally thin: it does not parse a skill or issue any
 * world mutation itself. It only reads the latest bounded observation, asks the
 * model to speak one objective, and calls {@link GoalCoordinator#setGoal}.</p>
 */
public final class AutonomyDriver {
    /** Never probe more often than this many ticks, regardless of config. */
    private static final int MIN_INTERVAL_TICKS = 20;
    /** Hard ceiling so a misconfigured interval cannot starve the tick loop. */
    private static final int MAX_INTERVAL_TICKS = 600 * 20;

    private final AtomicLong lastProbeTick = new AtomicLong(-1L);
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    public void tick(final ServerRuntime runtime, final long currentTick) {
        if (!CompanionConfig.AUTONOMY_ENABLED.get()) {
            return;
        }
        final GoalSnapshot goal = runtime.goals().snapshot();
        if (goal.status() != GoalStatus.IDLE || goal.externalWritesLocked()) {
            // A goal is already running, or a hardcore evaluation has locked
            // external writes. Either way, the companion is not ours to direct.
            return;
        }
        final ModelGateway gateway = runtime.model().gateway();
        if (!gateway.configured() || !gateway.highLevelDecisionReady()) {
            // No verified provider: do not invent objectives offline.
            return;
        }
        final int intervalTicks = clampInterval(
            CompanionConfig.AUTONOMY_INTERVAL_SECONDS.get() * 20
        );
        final long last = lastProbeTick.get();
        if (last >= 0 && currentTick - last < intervalTicks) {
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            // A previous model request is still resolving; skip this tick.
            return;
        }
        lastProbeTick.set(currentTick);
        final PlannerInput input = buildInput(runtime, goal);
        gateway.decide(input).whenComplete((outcome, error) -> {
            inFlight.set(false);
            if (error != null || outcome == null) {
                return;
            }
            if (outcome instanceof ModelOutcome.Success success) {
                final String proposed = success.decision().optionalSpeech();
                if (proposed != null && !proposed.isBlank()) {
                    runtime.goals().setGoal(proposed.strip(), GoalSource.AUTONOMY);
                }
            }
        });
    }

    private static int clampInterval(final int requested) {
        if (requested < MIN_INTERVAL_TICKS) {
            return MIN_INTERVAL_TICKS;
        }
        if (requested > MAX_INTERVAL_TICKS) {
            return MAX_INTERVAL_TICKS;
        }
        return requested;
    }

    private static PlannerInput buildInput(
        final ServerRuntime runtime,
        final GoalSnapshot goal
    ) {
        final String requestId = "autonomy-" + UUID.randomUUID();
        final Map<String, SkillArgumentValidator> availableSkills =
            runtime.skills().modelArgumentValidators();
        final DecisionContext context = new DecisionContext(
            requestId,
            runtime.observations().latestDecisionEpoch(),
            goal.revision(),
            false,
            availableSkills
        );
        String observation = runtime.observations().latestSemanticJson().orElse("{}");
        if (observation.length() > 60_000) {
            observation = observation.substring(0, 60_000);
        }
        final String systemPrompt = autonomySystemPrompt(
            CompanionConfig.AUTONOMY_GOAL_STYLE.get()
        );
        // Low token budget: we only need a short spoken objective, not a skill
        // plan. Temperature is raised slightly above the gameplay default so
        // repeated idle probes do not converge on one identical objective.
        return new PlannerInput(context, systemPrompt, observation, 256, 0.6);
    }

    private static String autonomySystemPrompt(final String rawStyle) {
        final String style = rawStyle == null
            ? "BALANCED"
            : rawStyle.trim().toUpperCase(Locale.ROOT);
        final String bias = switch (style) {
            case "SURVIVAL" ->
                "Prioritize staying safe and fed: shelter, food, light, and gear.";
            case "BUILD" ->
                "Prioritize building and improving a home base and useful structures.";
            case "EXPLORE" ->
                "Prioritize exploring the world, mapping terrain, and finding resources.";
            default ->
                "Balance survival, building, and exploration for steady long-term progress.";
        };
        return "You are the long-term planner for a Minecraft companion AI. "
            + "The player is not currently directing you. " + bias + " "
            + "Propose exactly ONE concrete, safe, immediately actionable next "
            + "objective the companion should pursue on its own (for example: "
            + "gather wood, build a shelter, find food, explore a nearby cave, "
            + "or farm crops). Reply with a single short sentence and use the "
            + "SPEAK decision, putting the objective text in optionalSpeech. "
            + "Do not invoke any gameplay skill; only speak the objective.";
    }
}
