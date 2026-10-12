package dev.mcai.companion.autonomy;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dev.mcai.companion.CompanionConfig;
import dev.mcai.companion.control.GoalCoordinator;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.control.Hold;
import dev.mcai.companion.model.DecisionContext;
import dev.mcai.companion.model.ModelGateway;
import dev.mcai.companion.model.ModelOutcome;
import dev.mcai.companion.model.PlannerInput;
import dev.mcai.companion.model.SkillArgumentValidator;
import dev.mcai.companion.runtime.ServerRuntime;

/**
 * Asks the configured model for the next objective while the companion is
 * idle and no external principal (player chat, MCP, or a locked hardcore
 * evaluation) owns the goal. The proposed objective is fed back through the
 * normal {@link GoalCoordinator} so the existing brain, skill supervisor and
 * fair actuator pursue it exactly like any player-assigned goal. Execution
 * never bypasses {@code FairPlayerActuator}, so autonomous goals stay within
 * the same honest, packet-level movement envelope.
 *
 * <p>This driver is intentionally thin: it does not parse a skill or issue
 * any world mutation itself. It reads the latest bounded observation, asks
 * the model to speak one objective, and calls {@link GoalCoordinator#setGoal}.</p>
 *
 * <h2>When it asks</h2>
 *
 * <p>It used to ask on a fixed 45-second timer, which was wrong in both
 * directions: a goal that had just failed still waited out the remainder of
 * the interval, and a world where nothing had changed was re-examined for
 * the same answer. Asking is now gated by {@link AutonomyWakeup}, which
 * tracks <em>what changed</em> rather than how much time has passed:</p>
 *
 * <ul>
 *   <li>a {@link GoalStatus#COMPLETED} or {@link GoalStatus#FAILED} goal,
 *       and any hold the companion can leave on her own, is urgent and
 *       wakes her on the next tick;</li>
 *   <li>a quiet world only wakes her once {@link
 *       Ripeness#maxWaitMillisOf} has elapsed, so the periodic review still
 *       happens but at a cost proportional to how idle she really is;</li>
 *   <li>a hold she cannot clear — an absent body, an owner's stop, a
 *       missing endpoint — produces no signal at all, because the request
 *       would be discarded.</li>
 * </ul>
 *
 * <p>{@code autonomy.minimumIntervalSeconds} remains as an anti-hammering
 * guard. It is not the cadence; it only bites when proposals keep failing to
 * install.</p>
 */
public final class AutonomyDriver {
    private final AtomicLong lastProbeTick = new AtomicLong(-1L);
    private final AtomicBoolean inFlight = new AtomicBoolean(false);

    /**
     * The wake gate. Accumulates the reason the companion is idle and
     * decides when that reason is worth a model call.
     *
     * <p>One instance for the whole server is correct: the companion has one
     * body, so there is only ever one idle situation to track. The state is
     * touched only from the server tick, which is the sole caller of
     * {@link #tick}.</p>
     *
     * <p>Created lazily rather than in a field initialiser. The runtime
     * holds this driver in a {@code static final} field, so a field
     * initialiser would read the Forge configuration while the mod class is
     * still loading — before the config has been registered. The level is
     * read on the first tick instead, and re-read whenever the configured
     * value changes so an operator can retune it without a restart.</p>
     */
    private AutonomyWakeup wakeup;

    /** The wake level {@link #wakeup} was built with, or {@code -1}. */
    private int appliedWakeLevel = -1;

    public void tick(final ServerRuntime runtime, final long currentTick) {
        if (!CompanionConfig.AUTONOMY_ENABLED.get()) {
            return;
        }
        final GoalSnapshot goal = runtime.goals().snapshot();
        final long nowMillis = currentTick * 50L;
        final AutonomyWakeup gate = wakeup();
        /*
         * Record where the companion is before deciding. Doing this on every
         * tick keeps the idle bookkeeping continuous, so a burst of changes
         * is counted once and a quiet stretch is measured from its true
         * start rather than from whenever a request last happened to run.
         */
        gate.observe(
                runtime.goals().haltReason(),
                runtime.goals().hold(),
                nowMillis
        );
        if (!mayPropose(goal, runtime.goals().hold())) {
            // Either a goal still has an owner (running, cancelling, or a
            // hardcore evaluation holding external writes), or the companion
            // is in a hold autonomy cannot clear. Either way the driver is
            // not the component that resolves this.
            return;
        }
        final ModelGateway gateway = runtime.model().gateway();
        if (!gateway.configured() || !gateway.highLevelDecisionReady()) {
            // No verified provider: do not invent objectives offline.
            return;
        }
        if (!gate.isRipe(nowMillis)) {
            // Nothing has changed and no wait budget has expired. Do not
            // spend a call: ripeness would have to be recomputed anyway.
            return;
        }
        final int minimumIntervalTicks = clampMinimumIntervalTicks(
            CompanionConfig.AUTONOMY_MINIMUM_INTERVAL_SECONDS.get() * 20
        );
        final long last = lastProbeTick.get();
        if (last >= 0 && currentTick - last < minimumIntervalTicks) {
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            // A previous model request is still resolving; skip this tick.
            return;
        }
        /*
         * Consume only after every other guard has passed, and read the
         * level from the take itself. A wake that is rejected by the
         * minimum-interval guard leaves its signals pending, so the next
         * eligible tick still has something to act on.
         */
        final Ripeness consumed = gate.consume(nowMillis);
        if (!consumed.atLeast(Ripeness.ENOUGH)) {
            inFlight.set(false);
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

    /**
     * The wake gate, rebuilt when the operator changes the configured level.
     */
    private AutonomyWakeup wakeup() {
        final int level = CompanionConfig.AUTONOMY_WAKE_LEVEL.get();
        if (wakeup == null || appliedWakeLevel != level) {
            wakeup = new AutonomyWakeup(level);
            appliedWakeLevel = level;
        }
        return wakeup;
    }

    /**
     * Whether the companion has no live owner and the driver may therefore
     * propose an autonomous objective.
     *
     * <p>Only {@link GoalStatus#IDLE} used to qualify. That is wrong for every
     * goal that reaches a terminal state: {@code COMPLETED}, {@code FAILED} and
     * {@code SAFE_IDLE} describe a goal that is finished, not one that is still
     * being pursued, and nothing in the goal machine ever moves them back to
     * {@code IDLE}. The driver therefore probed exactly once (at server start,
     * while the goal was still {@code IDLE}), then went permanently silent —
     * after which the companion stood still forever, because autonomy is the
     * only source of objectives when nobody is directing it.
     *
     * <p>{@link GoalStatus#RUNNING} still has an active owner, and
     * {@link GoalStatus#CANCEL_PENDING} is mid-cancel, so both are excluded.
     *
     * <p>The hold is checked as well, not only the status: a body that is
     * absent reports {@code SAFE_IDLE} exactly like an ordinary cancel, but
     * no proposal can be carried out until a respawn. Rejecting it here
     * avoids paying for a request whose answer must be thrown away.</p>
     */
    static boolean canTakeOver(final GoalSnapshot goal) {
        if (goal.externalWritesLocked()) {
            return false;
        }
        return switch (goal.status()) {
            case IDLE, COMPLETED, FAILED, SAFE_IDLE -> true;
            case RUNNING, CANCEL_PENDING -> false;
        };
    }

    /**
     * Whether a proposal is worth making at all: the goal must be ready for
     * one and the companion must be clearable back into work.
     *
     * <p>Separated from {@link #canTakeOver} so the status rule and the hold
     * rule stay independently testable. The two together are what make the
     * gate honest: a status check alone would treat an absent body as idle
     * and pay for a request whose answer must be discarded.</p>
     */
    static boolean mayPropose(
            final GoalSnapshot goal,
            final Optional<Hold> hold
    ) {
        return canTakeOver(goal)
                && hold.filter(Hold::selfClearing).isPresent();
    }

    private static int clampMinimumIntervalTicks(final int requested) {
        if (requested < 0) {
            return 0;
        }
        /*
         * A ceiling of ten minutes: the configured maximum is 600 seconds,
         * so anything beyond it is a unit error rather than an intent.
         */
        return Math.min(requested, 600 * 20);
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
