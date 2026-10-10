package dev.mcai.companion.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.control.GoalCoordinator;
import dev.mcai.companion.control.GoalExecutionPlan;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.model.DecisionContext;
import dev.mcai.companion.model.DecisionEnvelope;
import dev.mcai.companion.model.DecisionKind;
import dev.mcai.companion.model.GatewayStatus;
import dev.mcai.companion.model.ModelFailure;
import dev.mcai.companion.model.ModelFailureKind;
import dev.mcai.companion.model.ModelGateway;
import dev.mcai.companion.model.ModelOutcome;
import dev.mcai.companion.model.PlannerInput;
import dev.mcai.companion.model.RequestTrace;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillSupervisor;
import dev.mcai.companion.skills.gathering.ResourceGatheringSkills;
import dev.mcai.companion.runtime.MinecraftPlannerInputFactory;
import dev.mcai.companion.waypoint.DimensionRef;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Pure-Java, server-tick coordinator for goals, model planning, and local
 * atomic skills.
 *
 * <p>Network completions can only offer one immutable value to the mailbox.
 * They never touch the goal coordinator, skill supervisor, observation
 * provider, or event sink. {@link #tick()} is the sole application point.</p>
 */
public final class BrainOrchestrator {
    /**
     * A valid envelope that repeatedly declines to start an admitted local
     * skill is not harmless reasoning: it is the externally visible
     * "talk-only" failure mode.  Keep a few bounded replans for transient
     * perception/model races, then stop spending requests while the body has
     * no authorized action.
     */
    private static final int MAX_CONSECUTIVE_NO_ACTION_DECISIONS = 4;
    /**
     * A long model request may overlap one or more local emergency ticks.
     * Melee is the one skill that can safely revalidate its target from the
     * retained first-person sample: the skill binds the original observed
     * UUID, then requires that same UUID to be visible again before starting.
     * Permit only this narrow, bounded world-drift handoff; every other
     * stale decision remains rejected.
     */
    private static final long MAX_COMBAT_DECISION_REBASE_EPOCH_DRIFT = 256L;

    private final GoalCoordinator goals;
    private final ModelGateway modelGateway;
    private final SkillSupervisor skills;
    private final ObservationProvider observations;
    private final PlannerInputFactory plannerInputs;
    private final BrainEventSink events;
    private final BrainPolicy policy;
    private final LongSupplier nanoTime;
    private final GoalCompletionVerifier completionVerifier;

    private final AtomicReference<PlannerCompletion> mailbox = new AtomicReference<>();
    private final AtomicLong droppedMailboxCompletions = new AtomicLong();

    private InFlight inFlight;
    private long requestSequence;
    private long nextRequestNotBeforeNanos;
    private int consecutiveModelFailures;
    private int consecutiveRateLimits;
    private int consecutiveNoActionDecisions;
    private long knownGoalRevision = -1;
    private long lastObservationEpoch = -1;
    private String lastAppliedRequestId = "";
    private long lastRouteGuardDiagnosticTick = -1;
    private String lastRouteGuardDiagnosticSignature = "";
    private boolean waitingForPlayer;
    private long waitingGoalRevision = -1;
    private boolean sawActiveSkill;
    /** Whether this goal has ever acquired an accepted local skill lease. */
    private boolean acceptedSkillForGoal;
    /**
     * An explicit player food-consumption task has a stronger completion
     * condition than a generic goal: pickup/equip is not the requested
     * outcome. This flag is set only after the ordinary consume skill reaches
     * its inventory-delta-verified COMPLETED result.
     */
    private boolean verifiedFoodConsumptionForGoal;
    private String lastModelDecisionFailureCode = "";
    private String lastRejectedSkillName = "";
    private String lastRejectedSkillCode = "";
    private long lastRejectedObservationEpoch = -1;
    private int repeatedUnchangedStartRejections;
    private String lastFailedSkillName = "";
    private String lastFailedSkillCode = "";
    private int repeatedIdenticalSkillFailures;
    /*
     * A player can explicitly bind a follow request to their own server
     * identity.  If that player is briefly out of the companion's fair
     * view, permit one first-person survey before yielding the rest of the
     * route back to the ordinary planner.  This prevents both talk-only
     * latency and an unbounded local scanning loop.
     */
    private long immediateFollowSearchGoalRevision = -1L;
    /** One bounded local reacquisition sweep for a deictic item request. */
    private long immediateItemSurveyGoalRevision = -1L;
    /**
     * At most one provider-soft-deadline handoff for an explicit nearby-wood
     * request. This is not a general fallback planner: the player already
     * chose the goal, the admitted compound skill takes no model-generated
     * target, and every body action remains first-person and vanilla-bound.
     */
    private long immediateWoodHandoffGoalRevision = -1L;
    /**
     * A typed route may select one parameterless compound for its current
     * server-verified phase without another provider round trip. If that
     * compound rejects its preconditions, remember the exact objective and
     * yield to the model instead of retrying a local script loop.
     */
    private long encodedRouteRejectedGoalRevision = -1L;
    private String encodedRouteRejectedObjective = "";
    private volatile boolean closed;

    public BrainOrchestrator(
            GoalCoordinator goals,
            ModelGateway modelGateway,
            SkillSupervisor skills,
            ObservationProvider observations,
            PlannerInputFactory plannerInputs,
            BrainEventSink events
    ) {
        this(
                goals,
                modelGateway,
                skills,
                observations,
                plannerInputs,
                events,
                BrainPolicy.DEFAULT,
                System::nanoTime,
                GoalCompletionVerifier.ALLOW_ORDINARY_GOALS
        );
    }

    public BrainOrchestrator(
            GoalCoordinator goals,
            ModelGateway modelGateway,
            SkillSupervisor skills,
            ObservationProvider observations,
            PlannerInputFactory plannerInputs,
            BrainEventSink events,
            BrainPolicy policy
    ) {
        this(
                goals,
                modelGateway,
                skills,
                observations,
                plannerInputs,
                events,
                policy,
                System::nanoTime,
                GoalCompletionVerifier.ALLOW_ORDINARY_GOALS
        );
    }

    public BrainOrchestrator(
            GoalCoordinator goals,
            ModelGateway modelGateway,
            SkillSupervisor skills,
            ObservationProvider observations,
            PlannerInputFactory plannerInputs,
            BrainEventSink events,
            BrainPolicy policy,
            GoalCompletionVerifier completionVerifier
    ) {
        this(
                goals,
                modelGateway,
                skills,
                observations,
                plannerInputs,
                events,
                policy,
                System::nanoTime,
                completionVerifier
        );
    }

    BrainOrchestrator(
            GoalCoordinator goals,
            ModelGateway modelGateway,
            SkillSupervisor skills,
            ObservationProvider observations,
            PlannerInputFactory plannerInputs,
            BrainEventSink events,
            BrainPolicy policy,
            LongSupplier nanoTime
    ) {
        this(
                goals,
                modelGateway,
                skills,
                observations,
                plannerInputs,
                events,
                policy,
                nanoTime,
                GoalCompletionVerifier.ALLOW_ORDINARY_GOALS
        );
    }

    BrainOrchestrator(
            GoalCoordinator goals,
            ModelGateway modelGateway,
            SkillSupervisor skills,
            ObservationProvider observations,
            PlannerInputFactory plannerInputs,
            BrainEventSink events,
            BrainPolicy policy,
            LongSupplier nanoTime,
            GoalCompletionVerifier completionVerifier
    ) {
        this.goals = Objects.requireNonNull(goals, "goals");
        this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.plannerInputs = Objects.requireNonNull(plannerInputs, "plannerInputs");
        this.events = Objects.requireNonNull(events, "events");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.completionVerifier = Objects.requireNonNull(
                completionVerifier,
                "completionVerifier"
        );
    }

    /**
     * Advances orchestration once on the server thread.
     */
    public synchronized Snapshot tick() {
        return tick(true);
    }

    /**
     * Advances only the high-level control plane while a local emergency
     * reflex owns this tick's body actions. It may issue/receive a model
     * request and start a skill, but it never ticks an already active skill.
     * The newly started skill first receives body authority on a later server
     * tick after the emergency lane re-evaluates its declared ownership.
     */
    public synchronized Snapshot tickPlanningOnly() {
        return tick(false);
    }

    private Snapshot tick(final boolean allowActiveSkillTick) {
        if (closed) {
            return snapshot();
        }
        long now = nanoTime.getAsLong();
        GoalSnapshot goal = goals.snapshot();
        onGoalRevision(goal);
        publishDroppedMailboxNotice(goal.revision());

        PlannerCompletion completion = mailbox.getAndSet(null);
        boolean completionMatches = matchesCurrentRequest(completion);
        if (completion != null && !completionMatches) {
            emitNotice(goal.revision(), "stale_or_duplicate_completion");
        }
        if (completionMatches) {
            inFlight = null;
        }

        if (goal.status() != GoalStatus.RUNNING
                && goal.status() != GoalStatus.CANCEL_PENDING) {
            if (completionMatches) {
                emitNotice(goal.revision(), "completion_after_terminal_goal");
            }
            return snapshot();
        }

        if (goal.status() == GoalStatus.RUNNING
                && inFlight != null
                && !inFlight.softDeadlineReported
                && requestSoftDeadlineReached(now, inFlight)) {
            inFlight.softDeadlineReported = true;
            emitNotice(goal.revision(), "model_request_soft_deadline");
        }

        if (goal.status() == GoalStatus.RUNNING
                && inFlight != null
                && requestTimedOut(now, inFlight)) {
            handleRequestTimeout(goal, now);
            return snapshot();
        }

        final BrainObservation observation;
        try {
            observation = Objects.requireNonNull(
                    observations.observe(goal),
                    "observations.observe()"
            );
        } catch (Exception exception) {
            /*
             * Observation assembly contains no provider credential or model
             * response. Preserve its server-side stack trace so a physical
             * controller failure cannot be reduced to the indistinguishable
             * symptom "the companion stopped moving". Player-authored goal
             * text and semantic JSON are deliberately not included.
             */
            MinecraftAiCompanion.LOGGER.error(
                    "Companion observation failed for goal revision {}",
                    goal.revision(),
                    exception
            );
            terminal(goal, GoalStatus.SAFE_IDLE, "observation_unavailable");
            emitNotice(goal.revision(), "observation_unavailable");
            return snapshot();
        }
        lastObservationEpoch = observation.epoch();

        if (goal.status() == GoalStatus.CANCEL_PENDING) {
            if (completionMatches) {
                emitNotice(goal.revision(), "completion_after_cancel_request");
            }
            handleGoalCancellation(goal, observation);
            return snapshot();
        }

        if (observation.skillContext().goalRevision() != goal.revision()) {
            terminal(goal, GoalStatus.SAFE_IDLE, "observation_revision_mismatch");
            emitNotice(goal.revision(), "observation_revision_mismatch");
            return snapshot();
        }

        SkillSupervisor.Snapshot skillSnapshot = skills.snapshot();
        if (isActive(skillSnapshot)) {
            sawActiveSkill = true;
            if (completionMatches) {
                emitNotice(goal.revision(), "decision_while_skill_active");
            }
            if (skillSnapshot.boundGoalRevision() != goal.revision()) {
                retireSkillForReplacementGoal(
                    goal,
                    observation,
                    skillSnapshot,
                    now
                );
                return snapshot();
            }
            if (retireCompletedRouteSkill(
                    goal,
                    observation,
                    skillSnapshot
            )) {
                return snapshot();
            }
            if (!allowActiveSkillTick) {
                return snapshot();
            }
            SkillSupervisor.Snapshot afterTick =
                    skills.tick(observation.skillContext());
            handleSkillOutcome(goal, afterTick, now);
            return snapshot();
        }
        handleSkillOutcome(goal, skillSnapshot, now);
        if (goals.snapshot().status() != GoalStatus.RUNNING) {
            return snapshot();
        }

        /*
         * An explicit server-verified acceptance route does not need a model
         * to echo COMPLETE_GOAL after its last physical milestone. Waiting
         * for that echo let a provider return paid SEMANTIC_REFRESH replans
         * forever even though the completion predicate was already true.
         * Ordinary goals never enter this branch, and locked evaluations
         * remain owned by their separate victory tracker.
         */
        if (!goal.externalWritesLocked()
                && applyAutonomousVerifiedCompletion(
                        goal,
                        completionMatches
                )) {
            return snapshot();
        }

        if (completionMatches) {
            applyCompletion(goal, observation, completion, now);
            goal = goals.snapshot();
            if (goal.status() != GoalStatus.RUNNING
                    || isActive(skills.snapshot())
                    || waitingForPlayer) {
                return snapshot();
            }
        }

        /*
         * The conversation model has already encoded this player request as
         * a bounded route and terminal milestone. Early survival phases each
         * admit exactly one parameterless compound controller; asking the
         * same model to rediscover that forced choice adds several seconds of
         * idle latency per phase without adding judgment. Start only that
         * server-owned compound from trusted route state. Physical progress
         * and completion remain independently verified by the server.
         */
        if (inFlight == null
                && !waitingForPlayer
                && now >= nextRequestNotBeforeNanos
                && tryStartEncodedRouteStep(goal, observation, now)) {
            return snapshot();
        }

        /*
         * A clear "chop nearby wood" command already names the high-level
         * outcome, and its compound executor needs no target coordinates or
         * model-authored arguments. If the provider misses the configured
         * conversational soft deadline, cancel only that request and let the
         * ordinary fair skill start. This prevents a transient provider stall
         * from turning the body into a motionless acknowledgement while still
         * giving the high-level model the first opportunity to decide.
         */
        if (inFlight != null
                && inFlight.softDeadlineReported
                && !waitingForPlayer
                && tryStartSoftDeadlineWoodHandoff(
                        goal,
                        observation,
                        now
                )) {
            return snapshot();
        }

        /*
         * A literal, player-authored "follow me" command already provides
         * the high-level goal and the authorized player identity.  Do not
         * make that narrow, low-risk request wait for a slow, unavailable or
         * conversational model response.  The candidate below is built only
         * from the companion's current fair semantic sample, starts the
         * normal typed follow/survey skill, and never carries a coordinate,
         * hidden target position or teleport authority.
         */
        if (!modelGateway.highLevelDecisionReady()
                && inFlight == null
                && !waitingForPlayer
                && tryStartImmediatePlayerFollow(goal, observation, now)) {
            return snapshot();
        }

        if (inFlight == null
                && !waitingForPlayer
                && now >= nextRequestNotBeforeNanos
                && !isActive(skills.snapshot())) {
            issuePlannerRequest(goal, observation, now);
        }
        return snapshot();
    }

    private boolean tryStartEncodedRouteStep(
            final GoalSnapshot goal,
            final BrainObservation observation,
            final long now
    ) {
        final Optional<EncodedRouteStep> candidate = encodedRouteStep(
                goal,
                observation
        );
        if (candidate.isEmpty()) {
            return false;
        }
        final EncodedRouteStep step = candidate.orElseThrow();
        if (encodedRouteRejectedGoalRevision == goal.revision()
                && encodedRouteRejectedObjective.equals(
                        step.objective()
                )) {
            return false;
        }
        final DecisionEnvelope direct = new DecisionEnvelope(
                "encoded-route-" + goal.revision() + "-"
                        + observation.epoch(),
                observation.epoch(),
                goal.revision(),
                DecisionKind.START_SKILL,
                step.skillName(),
                List.of(),
                dev.mcai.companion.model.RequestedObservation.none(),
                "",
                1.0
        );
        applyStartSkill(
                goal,
                observation,
                direct,
                Optional.empty(),
                now,
                "encoded_route_skill_started"
        );
        if (isActive(skills.snapshot())) {
            encodedRouteRejectedGoalRevision = -1L;
            encodedRouteRejectedObjective = "";
            return true;
        }
        encodedRouteRejectedGoalRevision = goal.revision();
        encodedRouteRejectedObjective = step.objective();
        return true;
    }

    static Optional<EncodedRouteStep> encodedRouteStep(
            final GoalSnapshot goal,
            final BrainObservation observation
    ) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(observation, "observation");
        if (goal.externalWritesLocked()
                || goal.source() != GoalSource.PLAYER_CHAT) {
            return Optional.empty();
        }
        final Optional<GoalExecutionPlan> plan = GoalExecutionPlan
                .fromDetailCode(goal.detailCode())
                .filter(value -> value.route()
                        == GoalExecutionPlan.Route.FOUNDATION
                        || value.route()
                            == GoalExecutionPlan.Route.COMPLETION);
        if (plan.isEmpty()) {
            return Optional.empty();
        }
        final Optional<JsonArray> objectives = trustedRouteArray(
                observation,
                "nextObjectives"
        );
        if (objectives.isEmpty()
                || objectives.orElseThrow().isEmpty()) {
            return Optional.empty();
        }
        final String objective = objectives.orElseThrow()
                .get(0)
                .getAsString();
        final String skillName = switch (objective) {
            case "GATHER_VISIBLE_WOOD" -> "gather_nearby_wood";
            case "PREPARE_BASIC_CRAFTING" ->
                    "prepare_basic_crafting";
            case "CRAFT_AND_MINE_STONE" -> "prepare_stone_tools";
            case "DISTRIBUTE_LOG_STORAGE" ->
                    "prepare_distributed_log_storage";
            case "RETRIEVE_CONTAINER_WOOD_AND_PLACE_DOOR" ->
                    "prepare_container_wood_door";
            case "SECURE_FOOD_RESERVE" ->
                    "secure_visible_food_reserve";
            case "ACQUIRE_IRON_TOOLKIT" -> "prepare_iron_toolkit";
            default -> "";
        };
        return skillName.isEmpty()
                ? Optional.empty()
                : Optional.of(new EncodedRouteStep(
                        objective,
                        skillName
                ));
    }

    private boolean tryStartSoftDeadlineWoodHandoff(
            final GoalSnapshot goal,
            final BrainObservation observation,
            final long now
    ) {
        if (goal.source() != GoalSource.PLAYER_CHAT
                || goal.externalWritesLocked()
                || immediateWoodHandoffGoalRevision == goal.revision()
                || !MinecraftPlannerInputFactory
                        .isImmediateNearbyWoodGoal(goal.goal())
                || inFlight == null
                || !inFlight.softDeadlineReported) {
            return false;
        }
        final InFlight yielded = inFlight;
        yielded.cancellationRequested = true;
        final long differentRevision = goal.revision() == Long.MAX_VALUE
                ? goal.revision() - 1L
                : goal.revision() + 1L;
        modelGateway.cancelForGoalRevision(differentRevision);
        if (inFlight == yielded) {
            inFlight = null;
        }
        mailbox.set(null);
        immediateWoodHandoffGoalRevision = goal.revision();
        emitNotice(
                goal.revision(),
                "soft_deadline_nearby_wood_handoff"
        );
        final DecisionEnvelope direct = new DecisionEnvelope(
                "direct-wood-" + goal.revision() + "-"
                        + observation.epoch(),
                observation.epoch(),
                goal.revision(),
                DecisionKind.START_SKILL,
                "gather_nearby_wood",
                List.of(),
                dev.mcai.companion.model.RequestedObservation.none(),
                "",
                1.0
        );
        applyStartSkill(
                goal,
                observation,
                direct,
                Optional.empty(),
                now
        );
        return isActive(skills.snapshot());
    }

    /**
     * A route-owned action may satisfy its server-verified milestone before
     * the provider's requested parameter budget is exhausted. Leaving that
     * action attached can invalidate its bound world epoch on the next
     * observation and surface as a false {@code stale_world_revision}
     * failure (especially after a dragon kill). Retire only the small set of
     * route-owned skills for a trusted FOUNDATION/COMPLETION route, and only
     * when the corresponding milestone is already verified. Ordinary user
     * goals and arbitrary skills remain untouched.
     */
    private boolean retireCompletedRouteSkill(
            final GoalSnapshot goal,
            final BrainObservation observation,
            final SkillSupervisor.Snapshot active
    ) {
        final String skillName = active.skillName();
        if (skillName.isBlank()) {
            return false;
        }
        final Optional<String> routeProfile = trustedRouteString(
                observation,
                "profile"
        );
        if (Boolean.getBoolean("mcai.liveModelTest")) {
            final String verifiedMilestones = trustedRouteArray(
                    observation,
                    "verifiedMilestones"
            ).map(JsonArray::toString).orElse("");
            final String diagnosticSignature = skillName
                    + "|profile=" + routeProfile.orElse("")
                    + "|verified=" + verifiedMilestones
                    + "|target=" + routeMilestoneForSkill(
                            skillName,
                            observation,
                            goal
                    )
                    + "|state=" + active.state()
                    + "|cancelPending=" + active.cancelPending();
            if (!diagnosticSignature.equals(
                    lastRouteGuardDiagnosticSignature
            )) {
                lastRouteGuardDiagnosticSignature = diagnosticSignature;
                lastRouteGuardDiagnosticTick =
                        observation.skillContext().gameTick();
                MinecraftAiCompanion.LOGGER.info(
                    "Route skill guard: active={}, profile={}, "
                            + "verifiedMilestones={}, targetMilestone={}, "
                            + "state={}, cancelPending={}, gameTick={}, "
                            + "trustedRuntimeLength={}",
                        skillName,
                        routeProfile.orElse(""),
                        verifiedMilestones,
                        routeMilestoneForSkill(
                                skillName,
                                observation,
                                goal
                        ),
                        active.state(),
                        active.cancelPending(),
                        observation.skillContext().gameTick(),
                        observation.trustedRuntimeJson().length()
                );
            }
        }
        final boolean foundationRoute = routeProfile
                .filter("FOUNDATION"::equals)
                .isPresent();
        final boolean completionRoute = routeProfile
                .filter("COMPLETION"::equals)
                .isPresent();
        final boolean completionSkill = skillName.equals(
                "fight_ender_dragon"
        ) || skillName.equals("find_and_enter_observed_portal");
        if (!foundationRoute && !(completionRoute && completionSkill)) {
            return false;
        }
        final Optional<JsonArray> verified = trustedRouteArray(
                observation,
                "verifiedMilestones"
        );
        final String targetMilestone = routeMilestoneForSkill(
                skillName,
                observation,
                goal
        );
        if (targetMilestone.isEmpty()
                || verified.isEmpty()
                || verified.orElseThrow().asList().stream()
                    .noneMatch(element -> targetMilestone.equals(
                            element.getAsString()
                    ))) {
            return false;
        }
        if (Boolean.getBoolean("mcai.liveModelTest")) {
            MinecraftAiCompanion.LOGGER.info(
                    "Route skill guard matched milestone: skill={}, "
                            + "milestone={}, state={}, gameTick={}",
                    skillName,
                    targetMilestone,
                    active.state(),
                    observation.skillContext().gameTick()
            );
        }
        final SkillContext boundContext = rebindForActiveSkill(
                observation.skillContext(),
                active
        );
        final SkillSupervisor.MutationOutcome cancellation =
                skills.requestCancel(boundContext);
        if (Boolean.getBoolean("mcai.liveModelTest")) {
            MinecraftAiCompanion.LOGGER.info(
                    "Route skill guard cancel result: accepted={}, "
                            + "state={}, cancelPending={}, result={}",
                    cancellation.accepted(),
                    cancellation.snapshot().state(),
                    cancellation.snapshot().cancelPending(),
                    cancellation.snapshot().terminalResult()
                            .map(SkillResult::status)
                            .map(Enum::name)
                            .orElse("")
            );
        }
        if (!cancellation.accepted()
                && isActive(cancellation.snapshot())) {
            emitNotice(goal.revision(), "route_skill_retire_rejected");
            return false;
        }
        SkillSupervisor.Snapshot after = cancellation.snapshot();
        if (isActive(after)) {
            /*
             * requestCancel marks the skill CANCEL_PENDING when it is not
             * already at a safe checkpoint.  Do not call skills.tick here:
             * the caller owns the one-and-only skill tick for this server
             * cycle, and a second call would both violate the arbiter's
             * single-tick contract and make a pending cancellation appear
             * frozen.  Returning false lets the ordinary active-skill lane
             * advance it to its next checkpoint.
             */
            return false;
        }
        sawActiveSkill = false;
        emitNotice(
                goal.revision(),
                "route_skill_retired_after_verified_milestone"
        );
        return true;
    }

    private static String routeMilestoneForSkill(final String skillName) {
        return switch (skillName) {
            case "gather_visible_block_cluster", "gather_nearby_wood" ->
                    "WOOD_OBTAINED";
            case "prepare_basic_crafting" -> "BASIC_CRAFTING_READY";
            case "prepare_stone_tools" -> "STONE_TOOL_OBTAINED";
            case "prepare_distributed_log_storage" ->
                    "LOG_STORAGE_DISTRIBUTED";
            case "prepare_container_wood_door" ->
                    "CONTAINER_WOOD_DOOR_PLACED";
            case "secure_visible_food_reserve" -> "FOOD_SECURED";
            case "prepare_iron_toolkit" -> "IRON_TOOLKIT_OBTAINED";
            case "establish_foundation_workstations" ->
                    "WORKSTATIONS_ESTABLISHED";
            case "store_surplus_supplies" -> "SUPPLIES_STORED";
            case "prepare_foundation_shelter_materials" ->
                    "SHELTER_MATERIALS_PREPARED";
            case "build_dynamic_shelter" -> "SHELTER_COMPLETED";
            case "fight_ender_dragon" -> "DRAGON_KILLED";
            case "find_and_enter_observed_portal" -> "RETURNED_FROM_END";
            default -> "";
        };
    }

    /**
     * A single compound controller owns both the workstation placement and
     * the following chest-deposit handoff.  The route objective, not the
     * model's repeated skill name, determines which verified milestone may
     * retire it.  Without this distinction STORE_SURPLUS_SUPPLIES was
     * immediately cancelled as soon as the workstation evidence existed.
     */
    private static String routeMilestoneForSkill(
            final String skillName,
            final BrainObservation observation,
            final GoalSnapshot goal
    ) {
        if ("establish_foundation_workstations".equals(skillName)
                && trustedRouteArray(observation, "nextObjectives")
                        .filter(array -> !array.isEmpty())
                        .map(array -> array.get(0).getAsString())
                        .filter("STORE_SURPLUS_SUPPLIES"::equals)
                        .isPresent()) {
            return "SUPPLIES_STORED";
        }
        if ("prepare_iron_toolkit".equals(skillName)
                && GoalExecutionPlan.fromDetailCode(goal.detailCode())
                        .map(GoalExecutionPlan::terminalTarget)
                        .filter(GoalExecutionPlan.Target.IRON_OBTAINED::equals)
                        .isPresent()) {
            return "IRON_OBTAINED";
        }
        return routeMilestoneForSkill(skillName);
    }

    private static Optional<String> trustedRouteString(
            final BrainObservation observation,
            final String field
    ) {
        try {
            final JsonObject root = JsonParser.parseString(
                    observation.trustedRuntimeJson()
            ).getAsJsonObject();
            final JsonObject route = root.getAsJsonObject(
                    "verifiedCompletionRouteData"
            );
            if (route == null || !route.has(field)) {
                return Optional.empty();
            }
            return Optional.of(route.get(field).getAsString());
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<JsonArray> trustedRouteArray(
            final BrainObservation observation,
            final String field
    ) {
        try {
            final JsonObject root = JsonParser.parseString(
                    observation.trustedRuntimeJson()
            ).getAsJsonObject();
            final JsonObject route = root.getAsJsonObject(
                    "verifiedCompletionRouteData"
            );
            if (route == null || !route.has(field)
                    || !route.get(field).isJsonArray()) {
                return Optional.empty();
            }
            return Optional.of(route.getAsJsonArray(field));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private boolean applyAutonomousVerifiedCompletion(
            final GoalSnapshot goal,
            final boolean completionMatches
    ) {
        final Optional<GoalCompletionVerification> candidate;
        try {
            candidate = Objects.requireNonNull(
                    completionVerifier.verifyAutonomousCompletion(goal),
                    "completionVerifier.verifyAutonomousCompletion()"
            );
        } catch (RuntimeException exception) {
            emitNotice(
                    goal.revision(),
                    "goal_completion_verifier_failed"
            );
            return false;
        }
        if (candidate.isEmpty()
                || !candidate.orElseThrow().accepted()) {
            return false;
        }
        if (completionMatches) {
            emitNotice(
                    goal.revision(),
                    "model_completion_superseded_by_verified_route"
            );
        }
        emitNotice(goal.revision(), "server_verified_auto_complete");
        terminal(
                goal,
                GoalStatus.COMPLETED,
                "server_verified_complete"
        );
        return true;
    }

    /**
     * A replacement goal cannot inherit controls from an atomic skill that
     * was authorized for the previous goal revision. Retire that skill using
     * its original binding and wait for its safe checkpoint before any model
     * request for the replacement is allowed.
     */
    private void retireSkillForReplacementGoal(
            GoalSnapshot replacement,
            BrainObservation observation,
            SkillSupervisor.Snapshot active,
            long now
    ) {
        final SkillContext boundContext = rebindForActiveSkill(
                observation.skillContext(),
                active
        );
        SkillSupervisor.Snapshot after = active;
        if (!active.cancelPending()) {
            final SkillSupervisor.MutationOutcome cancellation =
                    skills.requestCancel(boundContext);
            after = cancellation.snapshot();
            if (!cancellation.accepted() && isActive(after)) {
                terminal(
                        replacement,
                        GoalStatus.SAFE_IDLE,
                        "replacement_skill_cancel_rejected"
                );
                emitNotice(
                        replacement.revision(),
                        "replacement_skill_cancel_rejected"
                );
                return;
            }
        }
        if (isActive(after)) {
            after = skills.tick(boundContext);
        }
        if (isActive(after)) {
            return;
        }

        sawActiveSkill = false;
        if (after.terminalResult().isPresent()
                && after.terminalResult().orElseThrow().status()
                == SkillResult.Status.FAILED) {
            emitNotice(replacement.revision(), "replacement_skill_retire_failed");
        } else {
            emitNotice(replacement.revision(), "replacement_skill_retired");
        }
        scheduleBackoff(now, policy.minimumReplanBackoff());
    }

    public synchronized Snapshot snapshot() {
        GoalSnapshot goal = goals.snapshot();
        SkillSupervisor.Snapshot skill = skills.snapshot();
        State derivedState;
        if (closed) {
            derivedState = State.CLOSED;
        } else if (goal.status() == GoalStatus.CANCEL_PENDING) {
            derivedState = State.CANCEL_PENDING;
        } else if (goal.status() != GoalStatus.RUNNING) {
            derivedState = State.IDLE;
        } else if (waitingForPlayer) {
            derivedState = State.WAITING_FOR_PLAYER;
        } else if (isActive(skill)) {
            derivedState = State.EXECUTING_SKILL;
        } else if (inFlight != null) {
            derivedState = State.REQUESTING_MODEL;
        } else if (nanoTime.getAsLong() < nextRequestNotBeforeNanos) {
            derivedState = State.BACKOFF;
        } else {
            derivedState = State.READY;
        }
        return new Snapshot(
                derivedState,
                goal.revision(),
                lastObservationEpoch,
                inFlight == null ? "" : inFlight.requestId,
                mailbox.get() != null,
                waitingForPlayer,
                consecutiveModelFailures,
                droppedMailboxCompletions.get()
        );
    }

    /**
     * Invalidates every action and model decision bound to a removed,
     * disconnected, or replaced player session.
     */
    public synchronized void onBodySessionChanged() {
        if (closed) {
            return;
        }
        if (inFlight != null) {
            final long currentRevision = goals.snapshot().revision();
            final long invalidRevision = currentRevision == Long.MAX_VALUE
                    ? currentRevision - 1L
                    : currentRevision + 1L;
            modelGateway.cancelForGoalRevision(invalidRevision);
            inFlight = null;
        }
        mailbox.set(null);
        skills.abandonForSessionEnd();
        sawActiveSkill = false;
        acceptedSkillForGoal = false;
        verifiedFoodConsumptionForGoal = false;
        waitingForPlayer = false;
        waitingGoalRevision = -1;
        lastModelDecisionFailureCode = "";
        consecutiveNoActionDecisions = 0;
        immediateFollowSearchGoalRevision = -1L;
        immediateItemSurveyGoalRevision = -1L;
        immediateWoodHandoffGoalRevision = -1L;
        encodedRouteRejectedGoalRevision = -1L;
        encodedRouteRejectedObjective = "";
        clearRepeatedStartRejection();
        nextRequestNotBeforeNanos = 0L;
    }

    /**
     * Gives an explicit player conversation priority over a pending
     * high-level replan. Atomic local skills keep running at 20 TPS; only the
     * model request is cancelled and retried after the reply lane is idle.
     */
    public synchronized void prioritizePlayerConversation() {
        if (closed) {
            return;
        }
        /*
         * A previous planner ASK_PLAYER is a pause in the current plan, not
         * a permanent latch.  A fresh player message is the explicit answer
         * or correction that wakes the body.  The old implementation only
         * cleared this state when another request happened to be in flight;
         * short follow-up messages such as "走啊" therefore left the brain in
         * WAITING_FOR_PLAYER forever while the conversation lane continued to
         * acknowledge the player.
         */
        waitingForPlayer = false;
        waitingGoalRevision = -1L;
        consecutiveNoActionDecisions = 0;
        nextRequestNotBeforeNanos = 0L;
        if (inFlight == null) {
            emitNotice(
                    goals.snapshot().revision(),
                    "planner_resumed_by_player_conversation"
            );
            return;
        }
        final long revision = goals.snapshot().revision();
        final long differentRevision = revision == Long.MAX_VALUE
                ? revision - 1L
                : revision + 1L;
        inFlight.cancellationRequested = true;
        modelGateway.cancelForGoalRevision(differentRevision);
        inFlight = null;
        mailbox.set(null);
        scheduleBackoff(
                nanoTime.getAsLong(),
                policy.minimumReplanBackoff()
        );
        emitNotice(revision, "planner_yielded_to_player_conversation");
    }

    /**
     * Stops future brain work without closing the injected gateway or skill
     * supervisor, whose lifecycle remains owned by the runtime.
     */
    public synchronized void close() {
        closed = true;
        mailbox.set(null);
        waitingForPlayer = false;
    }

    private void onGoalRevision(GoalSnapshot goal) {
        if (knownGoalRevision == goal.revision()) {
            return;
        }
        if (inFlight != null
                && inFlight.goalRevision != goal.revision()
                && !inFlight.cancellationRequested) {
            inFlight.cancellationRequested = true;
            modelGateway.cancelForGoalRevision(goal.revision());
        }
        if (waitingGoalRevision != goal.revision()) {
            waitingForPlayer = false;
            waitingGoalRevision = -1;
        }
        knownGoalRevision = goal.revision();
        lastModelDecisionFailureCode = "";
        acceptedSkillForGoal = false;
        verifiedFoodConsumptionForGoal = false;
        clearRepeatedStartRejection();
        clearRepeatedSkillFailure();
        skills.clearStartRejection();
        nextRequestNotBeforeNanos = 0;
        consecutiveModelFailures = 0;
        consecutiveRateLimits = 0;
        consecutiveNoActionDecisions = 0;
        immediateFollowSearchGoalRevision = -1L;
        immediateItemSurveyGoalRevision = -1L;
        immediateWoodHandoffGoalRevision = -1L;
    }

    private void handleGoalCancellation(
            GoalSnapshot goal,
            BrainObservation observation
    ) {
        if (inFlight != null && !inFlight.cancellationRequested) {
            inFlight.cancellationRequested = true;
            modelGateway.cancelForGoalRevision(goal.revision());
        }

        SkillSupervisor.Snapshot skillSnapshot = skills.snapshot();
        if (!isActive(skillSnapshot)) {
            terminal(goal, GoalStatus.SAFE_IDLE, "goal_cancelled");
            sawActiveSkill = false;
            return;
        }

        SkillContext cancellationContext = rebindForActiveSkill(
                observation.skillContext(),
                skillSnapshot
        );
        SkillSupervisor.MutationOutcome cancellation =
                skills.requestCancel(cancellationContext);
        SkillSupervisor.Snapshot afterCancellation = cancellation.snapshot();
        if (!cancellation.accepted()
                && isActive(afterCancellation)) {
            terminal(goal, GoalStatus.FAILED, "skill_cancel_rejected");
            emitNotice(goal.revision(), "skill_cancel_rejected");
            return;
        }
        if (isActive(afterCancellation)) {
            afterCancellation = skills.tick(cancellationContext);
        }

        if (isActive(afterCancellation)) {
            return;
        }
        Optional<SkillResult> result = afterCancellation.terminalResult();
        if (result.isPresent()
                && result.get().status() == SkillResult.Status.FAILED) {
            terminal(goal, GoalStatus.FAILED, "skill_cancel_failed");
            emitNotice(goal.revision(), "skill_cancel_failed");
        } else {
            terminal(goal, GoalStatus.SAFE_IDLE, "goal_cancelled");
            if (result.isPresent()
                    && result.get().status() == SkillResult.Status.CANCELLED) {
                /*
                 * Cancellation can complete the skill in this same server
                 * tick.  The normal handleSkillOutcome path deliberately
                 * does not run again after sawActiveSkill is cleared, so
                 * publish the lifecycle transition here while the
                 * SkillSupervisor terminal result is authoritative.  This is
                 * what lets a real teammate see "stopped" instead of only a
                 * silent safe-idle goal revision.
                 */
                emitNotice(goal.revision(), "skill_cancelled");
                emitSkillNotice(
                        goal.revision(),
                        "skill_cancelled",
                        skillSnapshot.skillName()
                );
            }
        }
        sawActiveSkill = false;
    }

    private void handleSkillOutcome(
            GoalSnapshot goal,
            SkillSupervisor.Snapshot skill,
            long now
    ) {
        if (isActive(skill)) {
            sawActiveSkill = true;
            return;
        }
        if (!sawActiveSkill || skill.terminalResult().isEmpty()) {
            return;
        }
        sawActiveSkill = false;
        SkillResult result = skill.terminalResult().orElseThrow();
        switch (result.status()) {
            case SAFE_IDLE -> {
                terminal(goal, GoalStatus.SAFE_IDLE, "skill_safe_idle");
                emitNotice(goal.revision(), "skill_safe_idle");
            }
            case FAILED -> {
                emitNotice(goal.revision(), "skill_failed");
                emitSkillNotice(
                        goal.revision(),
                        "skill_failed",
                        skill.skillName()
                );
                final String failureCode = result.failure()
                        .map(SkillFailure::code)
                        .orElse("skill_failed");
                emitNotice(goal.revision(), failureCode);
                recordSkillFailure(
                        skill.skillName(),
                        failureCode
                );
                if (repeatedIdenticalSkillFailures >= 3) {
                    emitNotice(
                            goal.revision(),
                            "repeated_identical_skill_failure"
                    );
                    terminal(
                            goal,
                            GoalStatus.SAFE_IDLE,
                            "repeated_skill_failure_without_progress"
                    );
                } else {
                    scheduleBackoff(
                            now,
                            policy.minimumReplanBackoff()
                    );
                }
            }
            case COMPLETED -> {
                clearRepeatedSkillFailure();
                if ("consume_owned_food".equals(skill.skillName())) {
                    verifiedFoodConsumptionForGoal = true;
                }
                emitNotice(goal.revision(), "skill_completed");
                emitSkillNotice(
                        goal.revision(),
                        "skill_completed",
                        skill.skillName()
                );
                if (goal.source() == GoalSource.PLAYER_CHAT
                        && !goal.externalWritesLocked()
                        && "gather_nearby_wood".equals(skill.skillName())
                        && GoalExecutionPlan.fromDetailCode(
                                goal.detailCode()
                            ).isEmpty()
                        && MinecraftPlannerInputFactory
                                .isImmediateNearbyWoodGoal(goal.goal())) {
                    /*
                     * This compound skill reports COMPLETED only after the
                     * server observes that owned wood increased. For the
                     * exact one-action player request, another model call
                     * cannot add completion evidence; it can only repeat the
                     * action or leave the body waiting. Close the goal at the
                     * verified physical boundary.
                     */
                    emitNotice(
                            goal.revision(),
                            "server_verified_nearby_wood_complete"
                    );
                    terminal(
                            goal,
                            GoalStatus.COMPLETED,
                            "server_verified_nearby_wood_complete"
                    );
                    break;
                }
                scheduleBackoff(now, policy.minimumReplanBackoff());
            }
            case CANCELLED -> {
                clearRepeatedSkillFailure();
                emitNotice(goal.revision(), "skill_cancelled");
                emitSkillNotice(
                        goal.revision(),
                        "skill_cancelled",
                        skill.skillName()
                );
                scheduleBackoff(now, policy.minimumReplanBackoff());
            }
        }
    }

    private void applyCompletion(
            GoalSnapshot goal,
            BrainObservation observation,
            PlannerCompletion completion,
            long now
    ) {
        if (goal.revision() != completion.goalRevision) {
            emitNotice(goal.revision(), "stale_or_duplicate_completion");
            scheduleBackoff(now, policy.minimumReplanBackoff());
            return;
        }
        if (completion.transportFailure || completion.outcome == null) {
            handleTransientModelFailure(
                    goal,
                    "model_transport_failure",
                    now,
                    null,
                    ModelFailureKind.NETWORK_TRANSIENT
            );
            return;
        }
        if (completion.outcome instanceof ModelOutcome.Failure failure) {
            if (failure.error().kind() == ModelFailureKind.MALFORMED_RESPONSE) {
                final DecisionEnvelope recoverySeed = new DecisionEnvelope(
                        completion.requestId,
                        completion.observationEpoch,
                        completion.goalRevision,
                        DecisionKind.REPLAN,
                        "",
                        List.of(),
                        dev.mcai.companion.model.RequestedObservation.none(),
                        "",
                        0.0
                );
                final Optional<DecisionEnvelope> recoveredAction =
                        recoverPlayerBoundAction(
                                goal,
                                observation,
                                recoverySeed
                        );
                if (recoveredAction.isPresent()) {
                    emitNotice(
                            goal.revision(),
                            recoveredActionNotice(
                                    goal,
                                    "malformed_response",
                                    recoveredAction.orElseThrow()
                            )
                    );
                    applyStartSkill(
                            goal,
                            observation,
                            recoveredAction.orElseThrow(),
                            Optional.empty(),
                            now
                    );
                    return;
                }
            }
            handleModelFailure(goal, failure.error(), now);
            return;
        }

        ModelOutcome.Success success = (ModelOutcome.Success) completion.outcome;
        lastModelDecisionFailureCode = "";
        consecutiveRateLimits = 0;
        emitUsage(
                goal.revision(),
                completion.requestId,
                success.usage()
        );
        DecisionEnvelope decision = success.decision();
        final long worldDrift = observation.epoch()
                - completion.observationEpoch;
        if (observation.epoch() != completion.observationEpoch) {
            final Optional<DecisionEnvelope> rebased =
                    rebaseCombatDecision(
                            decision,
                            completion,
                            observation,
                            worldDrift
                    );
            if (rebased.isPresent()) {
                decision = rebased.orElseThrow();
                emitNotice(goal.revision(), "combat_decision_world_drift_rebased");
            } else {
                final Optional<DecisionEnvelope> nonActionRebased =
                        rebaseNonActionDecision(
                                decision,
                                completion,
                                observation,
                                worldDrift
                        );
                if (nonActionRebased.isEmpty()) {
                    emitNotice(goal.revision(), "stale_or_duplicate_completion");
                    scheduleBackoff(now, policy.minimumReplanBackoff());
                    return;
                }
                decision = nonActionRebased.orElseThrow();
                emitNotice(
                        goal.revision(),
                        "non_action_decision_world_drift_rebased"
                );
            }
        }
        emitModelAudit(
            goal.revision(),
            completion.requestId,
            BrainEvent.ModelAuditStage.MODEL_RESPONSE_RECEIVED,
            completion.observationEpoch,
            Optional.of(decision.decision()),
            decision.skillName(),
            Optional.of(success.trace())
        );
        emitModelAudit(
            goal.revision(),
            completion.requestId,
            BrainEvent.ModelAuditStage.DECISION_SCHEMA_VALIDATED,
            completion.observationEpoch,
            Optional.of(decision.decision()),
            decision.skillName(),
            Optional.of(success.trace())
        );
        if (!decision.requestId().equals(completion.requestId)
                || decision.goalRevision() != completion.goalRevision
                || decision.observedWorldRevision() != observation.epoch()
                || goal.revision() != completion.goalRevision
                || decision.requestId().equals(lastAppliedRequestId)) {
            emitNotice(goal.revision(), "stale_or_duplicate_decision");
            scheduleBackoff(now, policy.minimumReplanBackoff());
            return;
        }
        lastAppliedRequestId = decision.requestId();
        decision = bindAuthoritativeSampleSequence(
                decision,
                completion.semanticSampleSequence
        );
        emitModelAudit(
            goal.revision(),
            completion.requestId,
            BrainEvent.ModelAuditStage.DECISION_REVISION_ACCEPTED,
            completion.observationEpoch,
            Optional.of(decision.decision()),
            decision.skillName(),
            Optional.of(success.trace())
        );

        if (decision.requestedObservation().kind()
                != dev.mcai.companion.model.ObservationKind.NONE) {
            final ObservationRequestStatus requestStatus;
            try {
                requestStatus = Objects.requireNonNull(
                        observations.requestObservation(
                                decision.requestedObservation()
                        ),
                        "observations.requestObservation()"
                );
            } catch (RuntimeException exception) {
                emitNotice(
                        goal.revision(),
                        "observation_request_rejected"
                );
                scheduleBackoff(now, policy.minimumReplanBackoff());
                return;
            }
            emitNotice(
                    goal.revision(),
                    switch (requestStatus) {
                        case ACCEPTED ->
                                "observation_request_accepted";
                        case UNSUPPORTED ->
                                "observation_request_unsupported";
                        case REJECTED ->
                                "observation_request_rejected";
                    }
            );
            if (requestStatus != ObservationRequestStatus.ACCEPTED) {
                scheduleBackoff(now, policy.minimumReplanBackoff());
                return;
            }
        }

        switch (decision.decision()) {
            case START_SKILL -> applyStartSkill(
                goal,
                observation,
                decision,
                Optional.of(success.trace()),
                now
            );
            case CONTINUE, REPLAN -> {
                final Optional<DecisionEnvelope> recoveredAction =
                        recoverPlayerBoundAction(
                                goal,
                                observation,
                                decision
                        );
                if (recoveredAction.isPresent()) {
                    /*
                     * A player-authored, server-bound follow goal is one of
                     * the few cases where the runtime can prove the first
                     * high-level action without inventing a route.  If the
                     * provider returns a syntactically valid speech-only
                     * response while the bound player is visibly present,
                     * recover the already-authored follow skill instead of
                     * leaving a teammate standing still.  The generated
                     * envelope still goes through the normal SkillSupervisor
                     * binding, fair target revalidation, leases, and vanilla
                     * movement; it never contains a coordinate or teleport.
                     */
                    emitNotice(
                            goal.revision(),
                            recoveredActionNotice(
                                    goal,
                                    "no_action",
                                    recoveredAction.orElseThrow()
                            )
                    );
                    applyStartSkill(
                            goal,
                            observation,
                            recoveredAction.orElseThrow(),
                            Optional.of(success.trace()),
                            now
                    );
                    break;
                }
                consecutiveModelFailures = 0;
                /*
                 * A planner response that did not start an action is private
                 * reasoning, not evidence that anything is happening in the
                 * world. Broadcasting "I'm coming", "surveying" or "I'm
                 * crafting" from this branch made the companion repeatedly
                 * promise actions that the skill layer had never accepted.
                 * Action speech is emitted only after START_SKILL succeeds.
                 */
                if (!decision.optionalSpeech().isBlank()) {
                    emitNotice(
                            goal.revision(),
                            "inactive_planner_speech_suppressed"
                    );
                }
                emitNotice(goal.revision(), decision.decision() == DecisionKind.CONTINUE
                        ? "model_continue"
                        : "model_replan");
                if (recordNoActionDecision(goal)) {
                    break;
                }
                scheduleBackoff(
                        now,
                        noActionBackoff(
                                policy.minimumReplanBackoff(),
                                consecutiveNoActionDecisions
                        )
                );
            }
            case SAFE_IDLE -> {
                consecutiveModelFailures = 0;
                /*
                 * A bound follow request already carries an authorized
                 * player identity from the server chat boundary.  Providers
                 * occasionally answer that concrete task with SAFE_IDLE
                 * (usually after a short acknowledgement or an uncertain
                 * visual turn).  Treating that terminal enum as an ordinary
                 * stop leaves the body motionless even though the local fair
                 * sample can prove the player or can start the bounded survey
                 * reacquisition.  Recover only this narrow, player-authored
                 * follow case from player chat or an authorized MCP goal;
                 * general goals still require the model to choose a skill,
                 * and the recovered decision goes through the normal
                 * SkillSupervisor/vanilla actuator path and model audit trace.
                 */
                final Optional<DecisionEnvelope> recoveredAction =
                        (goal.source() == GoalSource.PLAYER_CHAT
                                || goal.source() == GoalSource.MCP)
                                && !goal.externalWritesLocked()
                                ? recoverPlayerBoundAction(
                                        goal,
                                        observation,
                                        decision
                                )
                                : Optional.empty();
                if (recoveredAction.isPresent()) {
                    emitNotice(
                            goal.revision(),
                            recoveredActionNotice(
                                    goal,
                                    "safe_idle",
                                    recoveredAction.orElseThrow()
                            )
                    );
                    applyStartSkill(
                            goal,
                            observation,
                            recoveredAction.orElseThrow(),
                            Optional.of(success.trace()),
                            now
                    );
                    break;
                }
                if (goal.source() == GoalSource.PLAYER_CHAT
                        || goal.source() == GoalSource.MCP
                        || goal.externalWritesLocked()) {
                    /*
                     * SAFE_IDLE is an explicit stop, not a synonym for a
                     * provider's "okay/I'm here" acknowledgement.  A live
                     * player task (and the locked Hardcore command) must not
                     * disappear merely because a provider chose the wrong
                     * terminal enum.  Keep the goal authoritative, suppress
                     * the unaccepted speech, and ask the same model lane for
                     * an actionable correction.  The ordinary PLAYER_CHAT
                     * no-action bound eventually waits for a new player
                     * message; Hardcore remains locked and never self-closes.
                     */
                    emitNotice(
                            goal.revision(),
                            goal.externalWritesLocked()
                                    ? "evaluation_safe_idle_rejected"
                                    : "model_safe_idle_rejected_for_active_goal"
                    );
                    if (goal.externalWritesLocked()) {
                        scheduleBackoff(
                                now,
                                policy.minimumReplanBackoff()
                        );
                    } else {
                        if (recordNoActionDecision(goal)) {
                            break;
                        }
                        scheduleBackoff(
                                now,
                                noActionBackoff(
                                        policy.minimumReplanBackoff(),
                                        consecutiveNoActionDecisions
                                )
                        );
                    }
                } else {
                    consecutiveNoActionDecisions = 0;
                    emitSpeech(goal, decision);
                    terminal(goal, GoalStatus.SAFE_IDLE, "model_safe_idle");
                }
            }
            case COMPLETE_GOAL -> {
                consecutiveModelFailures = 0;
                final boolean playerControlledGoal =
                        goal.source() == GoalSource.PLAYER_CHAT
                        || (goal.source() == GoalSource.MCP
                            && !completionVerifier
                                .allowModelCompletionWithoutAction(goal));
                final boolean explicitFoodStillUnconsumed =
                        requiresExplicitFoodConsumptionProof(goal)
                                && !verifiedFoodConsumptionForGoal;
                /*
                 * Do not let a provider turn an explicit, server-bound
                 * "follow me" request into a speech-only completion loop.
                 * COMPLETE_GOAL is just as non-actionable as CONTINUE for
                 * this exceptionally narrow goal until the body has actually
                 * begun following.  The player identity comes from the chat
                 * boundary and must still be visible in the fair sample;
                 * otherwise the ordinary completion guard below remains in
                 * charge.  This is not a general local planner and cannot
                 * create a route, coordinate, or interaction action.
                 */
                final Optional<DecisionEnvelope> recoveredAction =
                        playerControlledGoal
                                && !goal.externalWritesLocked()
                                && !acceptedSkillForGoal
                                ? recoverPlayerBoundAction(
                                        goal,
                                        observation,
                                        decision
                                )
                                : Optional.empty();
                if (recoveredAction.isPresent()) {
                    emitNotice(
                            goal.revision(),
                            recoveredActionNotice(
                                    goal,
                                    "premature_completion",
                                    recoveredAction.orElseThrow()
                            )
                    );
                    applyStartSkill(
                            goal,
                            observation,
                            recoveredAction.orElseThrow(),
                            Optional.of(success.trace()),
                            now
                    );
                    break;
                }
                if (playerControlledGoal
                        && !goal.externalWritesLocked()
                        && (!acceptedSkillForGoal
                            || explicitFoodStillUnconsumed)) {
                    /*
                     * A live player or MCP gameplay task must not be
                     * self-completed before any local skill has started.
                     * Otherwise a provider's "任务完成" is the same
                     * talk-only failure as "我这就来". Keep the goal
                     * authoritative, suppress the claim, and ask for a
                     * concrete allow-listed action. A skill that already
                     * completed for this goal sets the lease bit and may
                     * then be completed normally.
                     */
                    lastModelDecisionFailureCode =
                            explicitFoodStillUnconsumed
                                    ? "completion_before_food_consumption"
                                    : "completion_without_action";
                    emitNotice(
                        goal.revision(),
                        explicitFoodStillUnconsumed
                                ? "model_completion_before_food_consumption"
                                : "model_completion_without_action"
                    );
                    if (!recordNoActionDecision(goal)) {
                        scheduleBackoff(
                                now,
                                noActionBackoff(
                                        policy.minimumReplanBackoff(),
                                        consecutiveNoActionDecisions
                                )
                        );
                    }
                    break;
                }
                consecutiveNoActionDecisions = 0;
                if (goal.externalWritesLocked()) {
                    emitNotice(goal.revision(), "evaluation_completion_unverified");
                    scheduleBackoff(now, policy.minimumReplanBackoff());
                } else {
                    final GoalCompletionVerification verification;
                    try {
                        verification = Objects.requireNonNull(
                                completionVerifier.verify(goal),
                                "completionVerifier.verify()"
                        );
                    } catch (RuntimeException exception) {
                        emitNotice(
                                goal.revision(),
                                "goal_completion_verifier_failed"
                        );
                        scheduleBackoff(
                                now,
                                policy.minimumReplanBackoff()
                        );
                        break;
                    }
                    if (!verification.accepted()) {
                        emitNotice(
                                goal.revision(),
                                verification.detailCode()
                        );
                        scheduleBackoff(
                                now,
                                policy.minimumReplanBackoff()
                        );
                        break;
                    }
                    emitSpeech(goal, decision);
                    terminal(
                            goal,
                            GoalStatus.COMPLETED,
                            "server_verified_complete"
                    );
                }
            }
            case ASK_PLAYER -> {
                /*
                 * A bound follow request already names the authorized player
                 * and the current fair sample can prove that player is
                 * visible.  Some providers still answer that concrete goal
                 * with a clarification-shaped ASK_PLAYER envelope.  Waiting
                 * for another message in that case is the field symptom
                 * "答应了但站着不动".  Recover only this narrow case; the
                 * generated envelope remains subject to the normal skill
                 * preconditions, leases, and vanilla actuator.
                 */
                final Optional<DecisionEnvelope> recoveredAction =
                        recoverPlayerBoundAction(
                                goal,
                                observation,
                                decision
                        );
                if (recoveredAction.isPresent()) {
                    emitNotice(
                            goal.revision(),
                            recoveredActionNotice(
                                    goal,
                                    "ask_player",
                                    recoveredAction.orElseThrow()
                            )
                    );
                    applyStartSkill(
                            goal,
                            observation,
                            recoveredAction.orElseThrow(),
                            Optional.of(success.trace()),
                            now
                    );
                } else if (goal.source() == GoalSource.PLAYER_CHAT
                        && looksLikeActionCommitment(
                                decision.optionalSpeech()
                        )) {
                    /*
                     * A planner ASK_PLAYER envelope whose text is an
                     * unqualified action promise ("我这就来", "I'm on my
                     * way") is not a real clarification. Waiting here
                     * leaves a player-authored goal in WAITING_FOR_PLAYER
                     * while the body stands still. Keep the goal, suppress
                     * the promise, and ask the same model for a concrete
                     * allow-listed START_SKILL on the next bounded retry.
                     * This does not invent a skill or mutate the world.
                     */
                    replanAfterActionPromise(goal, now);
                } else {
                    applyAskPlayer(goal, decision);
                }
            }
        }
    }

    private void replanAfterActionPromise(
            final GoalSnapshot goal,
            final long now
    ) {
        waitingForPlayer = false;
        waitingGoalRevision = -1L;
        consecutiveModelFailures = 0;
        emitNotice(
                goal.revision(),
                "ask_player_action_commitment_replanned"
        );
        if (recordNoActionDecision(goal)) {
            return;
        }
        scheduleBackoff(
                now,
                noActionBackoff(
                        policy.minimumReplanBackoff(),
                        consecutiveNoActionDecisions
                )
        );
    }

    /**
     * Records one decision that left the body without a skill lease.
     *
     * <p>The model remains the only component allowed to choose a general
     * skill.  This method never invents a route, target, inventory action, or
     * world mutation.  Its only authority is to bound an otherwise infinite
     * retry loop and expose its real state: a normal chat goal can wait for a
     * corrected player request; a locked evaluation cannot solicit input and
     * therefore ends safely instead of silently burning requests forever.</p>
     *
     * @return {@code true} when no further planner request may be scheduled
     *         for the current goal.
     */
    private boolean recordNoActionDecision(final GoalSnapshot goal) {
        consecutiveNoActionDecisions = Math.min(
                MAX_CONSECUTIVE_NO_ACTION_DECISIONS,
                consecutiveNoActionDecisions + 1
        );
        if (consecutiveNoActionDecisions >= 1) {
            /*
             * Keep the correction attached to the next planner request.  A
             * valid CONTINUE/REPLAN is syntactically acceptable, but
             * first response without an action is already the field symptom
             * "只说不做" from the player's perspective.  The model still
             * chooses the skill; this flag only asks it to make an
             * actionable choice from the server-authored allow-list instead
             * of emitting another promise.
             */
            lastModelDecisionFailureCode = "planner_no_action";
            emitNotice(goal.revision(), "planner_no_action_backoff");
        }
        if (consecutiveNoActionDecisions
                < MAX_CONSECUTIVE_NO_ACTION_DECISIONS) {
            return false;
        }

        if (goal.source() == GoalSource.PLAYER_CHAT
                && !goal.externalWritesLocked()) {
            waitingForPlayer = true;
            waitingGoalRevision = goal.revision();
            emitNotice(
                    goal.revision(),
                    "planner_no_action_waiting_for_player"
            );
            return true;
        }

        emitNotice(goal.revision(), "planner_no_action_exhausted");
        terminal(goal, GoalStatus.SAFE_IDLE, "planner_no_action_exhausted");
        return true;
    }

    /**
     * Recognizes only the narrow, user-visible task form where a named food
     * is explicitly to be consumed. It is a completion-integrity rule, not a
     * local planner: the model still selects pickup/consume from the current
     * skill schema, while the server refuses to treat a pickup or a sentence
     * as the requested eating result.
     */
    private static boolean requiresExplicitFoodConsumptionProof(
            final GoalSnapshot goal
    ) {
        if (goal.source() != GoalSource.PLAYER_CHAT
                && goal.source() != GoalSource.MCP) {
            return false;
        }
        final String text = Objects.requireNonNullElse(goal.goal(), "");
        final String lower = text.toLowerCase(Locale.ROOT);
        final boolean consumes = text.contains("吃")
                || text.contains("喝")
                || text.contains("食用")
                || lower.matches(".*\\b(?:eat|consume|drink)\\b.*");
        final boolean namedFood = text.contains("金苹果")
                || lower.contains("golden apple")
                || lower.contains("enchanted golden apple");
        return consumes && namedFood;
    }

    /**
     * Recognizes only a promise-shaped sentence, never a task or a target.
     * A question remains a legitimate clarification and is handled by the
     * ordinary ASK_PLAYER path.
     */
    static boolean looksLikeActionCommitment(final String speech) {
        final String value = Objects.requireNonNullElse(speech, "").strip();
        if (value.isEmpty()
                || value.indexOf('?') >= 0
                || value.indexOf('？') >= 0) {
            return false;
        }
        /*
         * Some providers answer a concrete player task with a bare
         * acknowledgement in ASK_PLAYER ("好的", "收到", "OK").  That is
         * neither a clarification nor evidence that a skill started.  Treat
         * only these short, punctuation-free acknowledgements as an action
         * commitment so the planner is woken for a real START_SKILL instead
         * of entering WAITING_FOR_PLAYER with the body standing still.
         */
        final String compact = value
                .replaceAll("[\\s!！。.,，~～]+", "")
                .toLowerCase(Locale.ROOT);
        if (compact.length() <= 16 && Set.of(
                "好",
                "好的",
                "好好好",
                "收到",
                "明白",
                "明白了",
                "没问题",
                "可以",
                "行",
                "ok",
                "okay",
                "gotit",
                "sure",
                "understood",
                "onit",
                "willdo"
        ).contains(compact)) {
            return true;
        }
        final String lower = value.toLowerCase(Locale.ROOT);
        return value.startsWith("目标已接受")
                || value.startsWith("任务已接受")
                || value.startsWith("任务已创建")
                || value.startsWith("已接受任务")
                || value.startsWith("开始执行")
                || value.startsWith("开始行动")
                || value.startsWith("正在执行")
                || value.startsWith("正在前往")
                || value.startsWith("正在跟随")
                || value.startsWith("正在移动")
                || value.startsWith("已经开始")
                || value.contains("我这就")
                || value.contains("我马上")
                || value.contains("我先去")
                || value.contains("我跟着")
                || value.contains("我来了")
                || value.contains("我来啦")
                || value.contains("我来帮")
                || value.contains("开始做")
                || value.contains("我会去")
                || value.contains("我将前往")
                || value.contains("我将开始")
                || lower.startsWith("i'll ")
                || lower.startsWith("i will ")
                || lower.startsWith("i'm on my way")
                || lower.startsWith("i am on my way")
                || lower.startsWith("task accepted")
                || lower.startsWith("goal accepted")
                || lower.startsWith("task created")
                || lower.startsWith("starting now")
                || lower.startsWith("proceeding ")
                || lower.startsWith("i'm heading ")
                || lower.startsWith("i am heading ")
                || lower.startsWith("starting ")
                || lower.startsWith("following ");
    }

    /**
     * Recovers the first action for an explicit player-bound follow request
     * when a provider emits a valid but non-actionable planner response.
     *
     * <p>This is deliberately narrower than a general local planner: the
     * bound player name is installed by the server-side chat classifier, the
     * matching entity must be in the current fair semantic sample, and the
     * resulting typed decision is still admitted by the registered
     * {@code follow_entity} skill.  If the player is temporarily outside the
     * current camera sample, return a bounded first-person survey instead of
     * leaving the body in a speech-only loop.  The survey does not pursue a
     * hidden position; it only rotates through fresh fair samples, after
     * which the ordinary planner can bind the newly visible player.
     * A malformed or incomplete sample simply returns empty and leaves the
     * ordinary model retry path unchanged.</p>
     */
    static Optional<DecisionEnvelope> recoverBoundFollowDecision(
            final GoalSnapshot goal,
            final BrainObservation observation,
            final DecisionEnvelope noActionDecision
    ) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(noActionDecision, "noActionDecision");
        if (noActionDecision.decision() != DecisionKind.CONTINUE
                && noActionDecision.decision() != DecisionKind.REPLAN
                && noActionDecision.decision() != DecisionKind.ASK_PLAYER
                && noActionDecision.decision() != DecisionKind.SAFE_IDLE
                && noActionDecision.decision() != DecisionKind.COMPLETE_GOAL) {
            return Optional.empty();
        }
        Optional<String> boundName = boundFollowPlayerName(goal.goal());
        if (boundName.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonObject root = JsonParser.parseString(
                    observation.semanticJson()
            ).getAsJsonObject();
            long sampleSequence = root.get("sampleSequence").getAsLong();
            if (sampleSequence < 0L) {
                return Optional.empty();
            }
            JsonObject self = root.getAsJsonObject("self");
            if (self == null
                    || !self.has("dimension")
                    || !self.get("dimension").isJsonPrimitive()) {
                return Optional.empty();
            }
            final String dimension = self.get("dimension").getAsString();
            /* Validate before handing the value to the normal skill parser. */
            DimensionRef.parse(dimension);
            JsonArray entities = root.getAsJsonArray("visibleEntities");
            if (entities == null) {
                return Optional.empty();
            }
            for (int index = 0; index < entities.size(); index++) {
                JsonObject entity = entities.get(index).getAsJsonObject();
                if (!entity.has("type")
                        || !entity.has("hostile")
                        || !"minecraft:player".equals(
                            entity.get("type").getAsString()
                        )
                        || entity.get("hostile").getAsBoolean()) {
                    continue;
                }
                JsonObject properties = entity.getAsJsonObject(
                        "properties"
                );
                if (properties == null
                        || !properties.has("playerName")
                        || !boundName.orElseThrow().equalsIgnoreCase(
                            properties.get("playerName").getAsString()
                        )) {
                    continue;
                }
                String observationId = "visible-" + index;
                return Optional.of(new DecisionEnvelope(
                        noActionDecision.requestId(),
                        noActionDecision.observedWorldRevision(),
                        noActionDecision.goalRevision(),
                        DecisionKind.START_SKILL,
                        "follow_entity",
                        List.of(
                                new SkillArgument(
                                        "observationId",
                                        observationId
                                ),
                                new SkillArgument(
                                        "sampleSequence",
                                        Long.toString(sampleSequence)
                                ),
                                new SkillArgument(
                                        "followDistance",
                                        "2.5"
                                ),
                                new SkillArgument(
                                        "lostGraceTicks",
                                        "120"
                                )
                        ),
                        dev.mcai.companion.model.RequestedObservation.none(),
                        "",
                        0.98
                ));
            }
            return Optional.of(new DecisionEnvelope(
                    noActionDecision.requestId(),
                    noActionDecision.observedWorldRevision(),
                    noActionDecision.goalRevision(),
                    DecisionKind.START_SKILL,
                    "survey_surroundings",
                    List.of(
                            new SkillArgument("dimension", dimension),
                            /*
                             * This is the low-latency reacquire path for an
                             * already server-bound follow request, not a
                             * general terrain survey. Four horizon sectors
                             * cover the first-person 360-degree search while
                             * avoiding the several-second stationary spin
                             * that made "跟我走" look like a speech-only
                             * action. The normal model-selected survey keeps
                             * its larger view budget for terrain work.
                             */
                            new SkillArgument("horizontalSteps", "4"),
                            new SkillArgument("includeVertical", "false"),
                            new SkillArgument("observationWaitTicks", "12")
                    ),
                    dev.mcai.companion.model.RequestedObservation.none(),
                    "",
                    0.95
            ));
        } catch (RuntimeException malformedObservation) {
            return Optional.empty();
        }
    }

    /**
     * Recovers the next step of an explicit food-consumption handoff after a
     * model returned a valid but non-actionable decision.  The handoff is
     * intentionally narrower than a general planner fallback: the player (or
     * an authorized MCP caller) already named the food, and the planner input
     * factory has independently proven either a first-person-visible dropped
     * stack or a positive owned-inventory entry.  No target, count, route, or
     * item is inferred here; the resulting envelope copies only that fair
     * evidence and still enters SkillSupervisor for normal preconditions.
     */
    private static Optional<DecisionEnvelope> recoverBoundFoodDecision(
            final GoalSnapshot goal,
            final BrainObservation observation,
            final DecisionEnvelope noActionDecision
    ) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(noActionDecision, "noActionDecision");
        if ((goal.source() != GoalSource.PLAYER_CHAT
                    && goal.source() != GoalSource.MCP)
                || goal.externalWritesLocked()
                || (noActionDecision.decision() != DecisionKind.CONTINUE
                    && noActionDecision.decision() != DecisionKind.REPLAN
                    && noActionDecision.decision() != DecisionKind.ASK_PLAYER
                    && noActionDecision.decision() != DecisionKind.SAFE_IDLE
                    && noActionDecision.decision() != DecisionKind.COMPLETE_GOAL)) {
            return Optional.empty();
        }
        final Optional<MinecraftPlannerInputFactory.ImmediateFoodHandoff>
                handoff = MinecraftPlannerInputFactory
                        .immediateFoodHandoffForRecovery(
                                goal.goal(),
                                observation.semanticJson()
                        );
        if (handoff.isEmpty()) {
            return Optional.empty();
        }
        final MinecraftPlannerInputFactory.ImmediateFoodHandoff value =
                handoff.orElseThrow();
        final List<SkillArgument> arguments;
        final String skillName;
        if (value.visibleDrop()) {
            skillName = "collect_observed_item";
            arguments = List.of(
                    new SkillArgument(
                            "sampleSequence",
                            Long.toString(value.sampleSequence())
                    ),
                    new SkillArgument(
                            "observationId",
                            value.observationId()
                    ),
                    new SkillArgument("maximumTicks", "300")
            );
        } else {
            skillName = "consume_owned_food";
            arguments = List.of(
                    new SkillArgument("dimension", value.dimension()),
                    new SkillArgument("itemId", value.itemId())
            );
        }
        return Optional.of(new DecisionEnvelope(
                noActionDecision.requestId(),
                noActionDecision.observedWorldRevision(),
                noActionDecision.goalRevision(),
                DecisionKind.START_SKILL,
                skillName,
                arguments,
                dev.mcai.companion.model.RequestedObservation.none(),
                "",
                0.97
        ));
    }

    /**
     * Recovers an explicit ordinary item-pickup task after a valid model
     * response failed to start a skill.  The planner input factory must first
     * prove exactly one matching dropped item in the companion's current fair
     * semantic frame; this method only copies that sample handle into the
     * normal observation-bound skill envelope.
     */
    private static Optional<DecisionEnvelope> recoverBoundItemCollectionDecision(
            final GoalSnapshot goal,
            final BrainObservation observation,
            final DecisionEnvelope noActionDecision
    ) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(noActionDecision, "noActionDecision");
        if ((goal.source() != GoalSource.PLAYER_CHAT
                    && goal.source() != GoalSource.MCP)
                || goal.externalWritesLocked()
                || (noActionDecision.decision() != DecisionKind.CONTINUE
                    && noActionDecision.decision() != DecisionKind.REPLAN
                    && noActionDecision.decision() != DecisionKind.ASK_PLAYER
                    && noActionDecision.decision() != DecisionKind.SAFE_IDLE
                    && noActionDecision.decision() != DecisionKind.COMPLETE_GOAL)) {
            return Optional.empty();
        }
        final Optional<MinecraftPlannerInputFactory
                .ImmediateItemCollectionHandoff> handoff =
                MinecraftPlannerInputFactory
                        .immediateItemCollectionHandoffForRecovery(
                                goal.goal(),
                                observation.semanticJson()
                        );
        if (handoff.isEmpty()) {
            return Optional.empty();
        }
        final MinecraftPlannerInputFactory.ImmediateItemCollectionHandoff value =
                handoff.orElseThrow();
        return Optional.of(new DecisionEnvelope(
                noActionDecision.requestId(),
                noActionDecision.observedWorldRevision(),
                noActionDecision.goalRevision(),
                DecisionKind.START_SKILL,
                "collect_observed_item",
                List.of(
                        new SkillArgument(
                                "sampleSequence",
                                Long.toString(value.sampleSequence())
                        ),
                        new SkillArgument(
                                "observationId",
                                value.observationId()
                        ),
                        new SkillArgument("maximumTicks", "300")
                ),
                dev.mcai.companion.model.RequestedObservation.none(),
                "",
                0.97
        ));
    }

    /**
     * Combines the bounded observation-bound recoveries that are safe for a
     * live player task. Follow remains first so a goal carrying both a social
     * phrase and incidental food text cannot be redirected to an inventory
     * action; food and ordinary item pickup are considered only when the
     * server-bound follow marker is absent.
     */
    private Optional<DecisionEnvelope> recoverPlayerBoundAction(
            final GoalSnapshot goal,
            final BrainObservation observation,
            final DecisionEnvelope noActionDecision
    ) {
        final Optional<DecisionEnvelope> follow =
                recoverBoundFollowDecision(
                        goal,
                        observation,
                        noActionDecision
                );
        if (follow.isPresent()) {
            return follow;
        }
        final Optional<DecisionEnvelope> combat =
                recoverExplicitCombatDecision(
                        goal,
                        observation,
                        noActionDecision
                );
        if (combat.isPresent()) {
            return combat;
        }
        final Optional<DecisionEnvelope> food = recoverBoundFoodDecision(
                goal,
                observation,
                noActionDecision
        );
        if (food.isPresent()) {
            return food;
        }
        final Optional<DecisionEnvelope> item =
                recoverBoundItemCollectionDecision(
                        goal,
                        observation,
                        noActionDecision
                );
        if (item.isPresent()) {
            return item;
        }
        final Optional<DecisionEnvelope> container =
                recoverBoundContainerWithdrawalDecision(
                        goal,
                        observation,
                        noActionDecision
                );
        if (container.isPresent()) {
            return container;
        }
        final Optional<DecisionEnvelope> drop =
                recoverBoundOwnedItemDropDecision(
                        goal,
                        observation,
                        noActionDecision
                );
        if (drop.isPresent()) {
            return drop;
        }
        if (immediateItemSurveyGoalRevision == goal.revision()) {
            return Optional.empty();
        }
        final Optional<DecisionEnvelope> survey =
                recoverBoundItemSurveyDecision(
                        goal,
                        observation,
                        noActionDecision
                );
        if (survey.isPresent()) {
            immediateItemSurveyGoalRevision = goal.revision();
        }
        return survey;
    }

    private static Optional<DecisionEnvelope>
            recoverBoundOwnedItemDropDecision(
                    final GoalSnapshot goal,
                    final BrainObservation observation,
                    final DecisionEnvelope noActionDecision
            ) {
        if ((goal.source() != GoalSource.PLAYER_CHAT
                    && goal.source() != GoalSource.MCP)
                || goal.externalWritesLocked()
                || (noActionDecision.decision() != DecisionKind.CONTINUE
                    && noActionDecision.decision() != DecisionKind.REPLAN
                    && noActionDecision.decision() != DecisionKind.ASK_PLAYER
                    && noActionDecision.decision() != DecisionKind.SAFE_IDLE
                    && noActionDecision.decision() != DecisionKind.COMPLETE_GOAL)) {
            return Optional.empty();
        }
        final Optional<MinecraftPlannerInputFactory.ImmediateItemDropHandoff>
                handoff = MinecraftPlannerInputFactory
                        .immediateOwnedItemDropHandoffForRecovery(
                                goal.goal(),
                                observation.semanticJson()
                        );
        if (handoff.isEmpty()) {
            return Optional.empty();
        }
        final MinecraftPlannerInputFactory.ImmediateItemDropHandoff value =
                handoff.orElseThrow();
        return Optional.of(new DecisionEnvelope(
                noActionDecision.requestId(),
                noActionDecision.observedWorldRevision(),
                noActionDecision.goalRevision(),
                DecisionKind.START_SKILL,
                "drop_item",
                List.of(
                        new SkillArgument("itemId", value.itemId()),
                        new SkillArgument("count", Integer.toString(value.count()))
                ),
                dev.mcai.companion.model.RequestedObservation.none(),
                "",
                0.98
        ));
    }

    /**
     * Keeps an explicit visible-container task from becoming a speech-only
     * loop when a provider returns REPLAN/SAFE_IDLE or an otherwise malformed
     * envelope.  The handoff is entirely derived from the current fair
     * semantic frame; the normal validator, skill supervisor and vanilla
     * menu transaction still own execution.
     */
    private static Optional<DecisionEnvelope>
            recoverBoundContainerWithdrawalDecision(
                    final GoalSnapshot goal,
                    final BrainObservation observation,
                    final DecisionEnvelope noActionDecision
            ) {
        if ((goal.source() != GoalSource.PLAYER_CHAT
                    && goal.source() != GoalSource.MCP)
                || goal.externalWritesLocked()
                || (noActionDecision.decision() != DecisionKind.CONTINUE
                    && noActionDecision.decision() != DecisionKind.REPLAN
                    && noActionDecision.decision() != DecisionKind.ASK_PLAYER
                    && noActionDecision.decision() != DecisionKind.SAFE_IDLE
                    && noActionDecision.decision() != DecisionKind.COMPLETE_GOAL)) {
            return Optional.empty();
        }
        final Optional<MinecraftPlannerInputFactory
                .ImmediateContainerWithdrawalHandoff> handoff =
                MinecraftPlannerInputFactory
                        .immediateContainerWithdrawalHandoffForRecovery(
                                goal.goal(),
                                observation.semanticJson()
                        );
        if (handoff.isEmpty()) {
            return Optional.empty();
        }
        final MinecraftPlannerInputFactory
                .ImmediateContainerWithdrawalHandoff value = handoff.orElseThrow();
        if (!value.open()) {
            return Optional.of(new DecisionEnvelope(
                    noActionDecision.requestId(),
                    noActionDecision.observedWorldRevision(),
                    noActionDecision.goalRevision(),
                    DecisionKind.START_SKILL,
                    "use_block",
                    List.of(
                            new SkillArgument("dimension", value.dimension()),
                            new SkillArgument(
                                    "sampleSequence",
                                    Long.toString(value.sampleSequence())
                            ),
                            new SkillArgument("x", Integer.toString(value.x())),
                            new SkillArgument("y", Integer.toString(value.y())),
                            new SkillArgument("z", Integer.toString(value.z())),
                            new SkillArgument("face", value.face()),
                            new SkillArgument("hand", "main_hand")
                    ),
                    dev.mcai.companion.model.RequestedObservation.none(),
                    "",
                    0.98
            ));
        }
        return Optional.of(new DecisionEnvelope(
                noActionDecision.requestId(),
                noActionDecision.observedWorldRevision(),
                noActionDecision.goalRevision(),
                DecisionKind.START_SKILL,
                "transfer_menu_item",
                List.of(
                        new SkillArgument(
                                "sampleSequence",
                                Long.toString(value.sampleSequence())
                        ),
                        new SkillArgument(
                                "containerId",
                                Integer.toString(value.containerId())
                        ),
                        new SkillArgument(
                                "stateId",
                                Integer.toString(value.stateId())
                        ),
                        new SkillArgument(
                                "sourceSlot",
                                Integer.toString(value.sourceSlot())
                        ),
                        new SkillArgument(
                                "destinationSlot",
                                Integer.toString(value.destinationSlot())
                        ),
                        new SkillArgument("count", Integer.toString(value.count()))
                ),
                dev.mcai.companion.model.RequestedObservation.none(),
                "",
                0.98
        ));
    }

    /**
     * Recovers a concrete melee action when a player has explicitly asked for
     * combat and the provider returned a valid non-action response. This is
     * not a general combat planner: it copies one currently visible hostile
     * observation into the registered skill envelope, and the skill still
     * performs its UUID/reach/occlusion revalidation before any attack. The
     * fallback is limited to ordinary player/MCP goals and never runs for a
     * locked Hardcore evaluation.
     */
    private static Optional<DecisionEnvelope> recoverExplicitCombatDecision(
            final GoalSnapshot goal,
            final BrainObservation observation,
            final DecisionEnvelope noActionDecision
    ) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(noActionDecision, "noActionDecision");
        if ((goal.source() != GoalSource.PLAYER_CHAT
                    && goal.source() != GoalSource.MCP)
                || goal.externalWritesLocked()
                || !looksLikeExplicitCombatGoal(goal.goal())
                || (noActionDecision.decision() != DecisionKind.CONTINUE
                    && noActionDecision.decision() != DecisionKind.REPLAN
                    && noActionDecision.decision() != DecisionKind.ASK_PLAYER
                    && noActionDecision.decision() != DecisionKind.SAFE_IDLE
                    && noActionDecision.decision() != DecisionKind.COMPLETE_GOAL)) {
            return Optional.empty();
        }
        try {
            final JsonObject root = JsonParser.parseString(
                    observation.semanticJson()
            ).getAsJsonObject();
            final long sampleSequence = root.get("sampleSequence")
                    .getAsLong();
            if (sampleSequence < 0L) {
                return Optional.empty();
            }
            final JsonArray visible = root.getAsJsonArray(
                    "visibleEntities"
            );
            if (visible == null) {
                return Optional.empty();
            }
            for (int index = 0; index < visible.size(); index++) {
                if (!visible.get(index).isJsonObject()) {
                    continue;
                }
                final JsonObject entity = visible.get(index)
                        .getAsJsonObject();
                final String type = entity.has("type")
                        ? entity.get("type").getAsString()
                        : "";
                final boolean hostile = entity.has("hostile")
                        && entity.get("hostile").getAsBoolean();
                final boolean projectile = entity.has("projectile")
                        && entity.get("projectile").getAsBoolean();
                if (projectile || !combatTargetType(type, hostile, goal.goal())) {
                    continue;
                }
                return Optional.of(new DecisionEnvelope(
                        noActionDecision.requestId(),
                        noActionDecision.observedWorldRevision(),
                        noActionDecision.goalRevision(),
                        DecisionKind.START_SKILL,
                        "engage_observed_entity",
                        List.of(
                                new SkillArgument(
                                        "sampleSequence",
                                        Long.toString(sampleSequence)
                                ),
                                new SkillArgument(
                                        "observationId",
                                        "visible-" + index
                                )
                        ),
                        dev.mcai.companion.model.RequestedObservation.none(),
                        "",
                        0.96
                ));
            }
            return Optional.empty();
        } catch (RuntimeException malformedObservation) {
            return Optional.empty();
        }
    }

    private static boolean combatTargetType(
            final String type,
            final boolean hostile,
            final String goal
    ) {
        if (hostile) {
            return true;
        }
        final String lowerGoal = Objects.requireNonNullElse(
                goal,
                ""
        ).toLowerCase(Locale.ROOT);
        if ("minecraft:iron_golem".equals(type)) {
            return lowerGoal.contains("golem")
                    || goal.contains("铁傀儡");
        }
        return "minecraft:player".equals(type)
                && (lowerGoal.contains("pvp")
                    || lowerGoal.contains("player")
                    || lowerGoal.contains("duel")
                    || goal.contains("玩家")
                    || goal.contains("单挑"));
    }

    private static boolean looksLikeExplicitCombatGoal(final String goal) {
        final String value = Objects.requireNonNullElse(goal, "");
        final String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("protect")
                || lower.contains("attack")
                || lower.contains("fight")
                || lower.contains("kill")
                || lower.contains("defend")
                || lower.contains("combat")
                || lower.contains("zombie")
                || lower.contains("skeleton")
                || lower.contains("golem")
                || lower.contains("pvp")
                || value.contains("僵尸")
                || value.contains("骷髅")
                || value.contains("铁傀儡")
                || value.contains("保护")
                || value.contains("攻击")
                || value.contains("战斗")
                || value.contains("击杀")
                || value.contains("击退");
    }

    /**
     * Reorients the body once when a player explicitly names a dropped item
     * that is not in the current first-person entity frame.  It is bounded to
     * four horizontal sectors and twelve observation-wait ticks, mirroring
     * the existing follow reacquisition path; no coordinates or hidden
     * entities are inferred.
     */
    private static Optional<DecisionEnvelope> recoverBoundItemSurveyDecision(
            final GoalSnapshot goal,
            final BrainObservation observation,
            final DecisionEnvelope noActionDecision
    ) {
        if ((goal.source() != GoalSource.PLAYER_CHAT
                    && goal.source() != GoalSource.MCP)
                || goal.externalWritesLocked()
                || isFoodGoal(goal.goal())
                || !isItemCollectionGoal(goal.goal())
                || (noActionDecision.decision() != DecisionKind.CONTINUE
                    && noActionDecision.decision() != DecisionKind.REPLAN
                    && noActionDecision.decision() != DecisionKind.ASK_PLAYER
                    && noActionDecision.decision() != DecisionKind.SAFE_IDLE
                    && noActionDecision.decision() != DecisionKind.COMPLETE_GOAL)) {
            return Optional.empty();
        }
        try {
            final JsonObject root = JsonParser.parseString(
                    observation.semanticJson()
            ).getAsJsonObject();
            final JsonObject self = root.getAsJsonObject("self");
            if (self == null || !self.has("dimension")) {
                return Optional.empty();
            }
            final String dimension = self.get("dimension").getAsString();
            DimensionRef.parse(dimension);
            return Optional.of(new DecisionEnvelope(
                    noActionDecision.requestId(),
                    noActionDecision.observedWorldRevision(),
                    noActionDecision.goalRevision(),
                    DecisionKind.START_SKILL,
                    "survey_surroundings",
                    List.of(
                            new SkillArgument("dimension", dimension),
                            new SkillArgument("horizontalSteps", "4"),
                            new SkillArgument("includeVertical", "false"),
                            new SkillArgument("observationWaitTicks", "12")
                    ),
                    dev.mcai.companion.model.RequestedObservation.none(),
                    "",
                    0.95
            ));
        } catch (RuntimeException malformedObservation) {
            return Optional.empty();
        }
    }

    private static boolean isItemCollectionGoal(final String goal) {
        final String normalized = Objects.requireNonNullElse(goal, "")
                .strip();
        final String lower = normalized.toLowerCase(Locale.ROOT);
        return normalized.contains("捡")
                || normalized.contains("拾取")
                || normalized.contains("拿起地上")
                || normalized.contains("收起掉落")
                || lower.contains("pick up")
                || lower.contains("pickup the")
                || lower.contains("collect the dropped")
                || lower.contains("collect that dropped")
                || lower.contains("grab the dropped");
    }

    private static String recoveredActionNotice(
            final GoalSnapshot goal,
            final String phase,
            final DecisionEnvelope decision
    ) {
        final String prefix = switch (decision.skillName()) {
            case "follow_entity" -> "follow_action";
            case "survey_surroundings" ->
                    isItemCollectionGoal(goal.goal())
                            ? "item_search"
                            : "follow_search";
            case "engage_observed_entity" -> "combat_action";
            case "collect_observed_item" ->
                    isFoodGoal(goal.goal()) ? "food_pickup" : "item_pickup";
            case "consume_owned_food" -> "food_consumption";
            default -> "observation_bound_action";
        };
        return prefix + "_recovered_from_" + phase;
    }

    private static boolean isFoodGoal(final String goal) {
        final String lower = Objects.requireNonNullElse(
                goal,
                ""
        ).toLowerCase(Locale.ROOT);
        return goal.contains("吃")
                || goal.contains("食用")
                || lower.matches(".*\\b(?:eat|consume|drink)\\b.*");
    }

    private static Optional<String> boundFollowPlayerName(
            final String goalText
    ) {
        String goal = Objects.requireNonNullElse(goalText, "");
        String marker = "serverBoundPlayerName=";
        int markerIndex = goal.indexOf(marker);
        if (markerIndex < 0) {
            return Optional.empty();
        }
        int start = markerIndex + marker.length();
        int end = goal.indexOf(';', start);
        if (end < 0) {
            end = goal.length();
        }
        String name = goal.substring(start, end).strip()
                .toLowerCase(Locale.ROOT);
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /**
     * Starts a direct, server-bound follow request without asking the model
     * to repeat a command the player has already made explicitly.
     *
     * <p>The only admitted direct action is {@code follow_entity}, or one
     * bounded {@code survey_surroundings} when the authorized player is not
     * in the current fair view.  All world interaction still flows through
     * {@link SkillSupervisor}; this method does not route to a coordinate,
     * inspect hidden entities, or bypass vanilla movement.</p>
     */
    private boolean tryStartImmediatePlayerFollow(
            final GoalSnapshot goal,
            final BrainObservation observation,
            final long now
    ) {
        if (goal.source() != GoalSource.PLAYER_CHAT
                || goal.externalWritesLocked()
                || boundFollowPlayerName(goal.goal()).isEmpty()) {
            return false;
        }
        final DecisionEnvelope seed = new DecisionEnvelope(
                "direct-follow-" + goal.revision() + "-"
                        + observation.epoch(),
                observation.epoch(),
                goal.revision(),
                DecisionKind.REPLAN,
                "",
                List.of(),
                dev.mcai.companion.model.RequestedObservation.none(),
                "",
                1.0
        );
        final Optional<DecisionEnvelope> candidate =
                recoverBoundFollowDecision(goal, observation, seed);
        if (candidate.isEmpty()) {
            return false;
        }
        final DecisionEnvelope direct = candidate.orElseThrow();
        if (direct.skillName().equals("survey_surroundings")) {
            if (immediateFollowSearchGoalRevision == goal.revision()) {
                return false;
            }
            immediateFollowSearchGoalRevision = goal.revision();
            emitNotice(
                    goal.revision(),
                    "immediate_player_follow_search_started"
            );
        } else if (direct.skillName().equals("follow_entity")) {
            emitNotice(
                    goal.revision(),
                    "immediate_player_follow_started"
            );
        } else {
            return false;
        }
        applyStartSkill(
                goal,
                observation,
                direct,
                Optional.empty(),
                now
        );
        return true;
    }

    private void applyStartSkill(
            GoalSnapshot goal,
            BrainObservation observation,
            DecisionEnvelope decision,
            Optional<RequestTrace> trace,
            long now
    ) {
        applyStartSkill(
                goal,
                observation,
                decision,
                trace,
                now,
                "direct_player_skill_started"
        );
    }

    private void applyStartSkill(
            GoalSnapshot goal,
            BrainObservation observation,
            DecisionEnvelope decision,
            Optional<RequestTrace> trace,
            long now,
            String untracedStartNotice
    ) {
        Objects.requireNonNull(
                untracedStartNotice,
                "untracedStartNotice"
        );
        SkillSupervisor.StartOutcome started =
                skills.start(decision, observation.skillContext());
        if (!started.accepted()) {
            emitNotice(goal.revision(), "skill_start_rejected");
            final String failureCode = started.failure()
                    .map(SkillFailure::code)
                    .orElse("skill_start_rejected");
            emitNotice(goal.revision(), failureCode);
            lastModelDecisionFailureCode = failureCode;
            recordStartRejection(
                    decision.skillName(),
                    failureCode,
                    observation.epoch()
            );
            consecutiveModelFailures++;
            if (repeatedUnchangedStartRejections >= 3) {
                emitNotice(
                        goal.revision(),
                        "repeated_skill_start_rejection"
                );
                terminal(
                        goal,
                        GoalStatus.SAFE_IDLE,
                        "repeated_skill_rejection_without_world_change"
                );
            } else if (consecutiveModelFailures
                    >= policy.maxConsecutiveModelFailures()) {
                terminal(goal, GoalStatus.SAFE_IDLE, "skill_start_failures");
            } else {
                scheduleBackoff(now, policy.minimumReplanBackoff());
            }
            return;
        }
        sawActiveSkill = true;
        acceptedSkillForGoal = true;
        consecutiveModelFailures = 0;
        consecutiveNoActionDecisions = 0;
        clearRepeatedStartRejection();
        emitSkillNotice(
                goal.revision(),
                "skill_started",
                started.snapshot().skillName()
        );
        if (trace.isPresent()) {
            emitModelAudit(
                goal.revision(),
                decision.requestId(),
                BrainEvent.ModelAuditStage.SKILL_STARTED,
                decision.observedWorldRevision(),
                Optional.of(decision.decision()),
                started.snapshot().skillName(),
                trace
            );
        } else {
            /*
             * Preserve the audit distinction: this skill was not selected by
             * a gameplay-planner response and therefore must never acquire a
             * fabricated provider trace. The caller supplies the exact local
             * authority source (direct player binding or encoded route).
             */
            emitNotice(goal.revision(), untracedStartNotice);
        }
        emitSpeech(goal, decision);
    }

    record EncodedRouteStep(String objective, String skillName) {
        EncodedRouteStep {
            Objects.requireNonNull(objective, "objective");
            Objects.requireNonNull(skillName, "skillName");
        }
    }

    private void recordStartRejection(
            final String skillName,
            final String failureCode,
            final long observationEpoch
    ) {
        if (lastRejectedObservationEpoch == observationEpoch
                && lastRejectedSkillName.equals(skillName)
                && lastRejectedSkillCode.equals(failureCode)) {
            repeatedUnchangedStartRejections++;
            return;
        }
        lastRejectedSkillName = skillName;
        lastRejectedSkillCode = failureCode;
        lastRejectedObservationEpoch = observationEpoch;
        repeatedUnchangedStartRejections = 1;
    }

    private void clearRepeatedStartRejection() {
        lastRejectedSkillName = "";
        lastRejectedSkillCode = "";
        lastRejectedObservationEpoch = -1;
        repeatedUnchangedStartRejections = 0;
    }

    private void recordSkillFailure(
            final String skillName,
            final String failureCode
    ) {
        if (lastFailedSkillName.equals(skillName)
                && lastFailedSkillCode.equals(failureCode)) {
            repeatedIdenticalSkillFailures++;
            return;
        }
        lastFailedSkillName = skillName;
        lastFailedSkillCode = failureCode;
        repeatedIdenticalSkillFailures = 1;
    }

    private void clearRepeatedSkillFailure() {
        lastFailedSkillName = "";
        lastFailedSkillCode = "";
        repeatedIdenticalSkillFailures = 0;
    }

    private void applyAskPlayer(GoalSnapshot goal, DecisionEnvelope decision) {
        if (goal.externalWritesLocked()) {
            emitNotice(goal.revision(), "evaluation_requires_input");
            terminal(goal, GoalStatus.SAFE_IDLE, "evaluation_requires_input");
            return;
        }
        if (decision.optionalSpeech().isBlank()) {
            emitNotice(goal.revision(), "invalid_ask_player");
            terminal(goal, GoalStatus.SAFE_IDLE, "invalid_ask_player");
            return;
        }
        emitSpeech(goal, decision);
        consecutiveModelFailures = 0;
        consecutiveNoActionDecisions = 0;
        waitingForPlayer = true;
        waitingGoalRevision = goal.revision();
    }

    private void handleModelFailure(
            GoalSnapshot goal,
            ModelFailure failure,
            long now
    ) {
        lastModelDecisionFailureCode =
                plannerCorrectionCode(failure);
        if (Boolean.getBoolean("mcai.liveModelTest")) {
            /*
             * ModelFailure.safeMessage is deliberately constructed without
             * response bodies, prompts, headers, credentials, or player text.
             * Keeping this diagnostic behind the explicit live-test flag
             * makes provider-contract failures actionable in the headless
             * GameTest server without adding noisy production logs.
             */
            MinecraftAiCompanion.LOGGER.info(
                    "Live-model decision rejected: kind={}, message={}",
                    failure.kind(),
                    failure.safeMessage()
            );
        }
        emitNotice(goal.revision(), "model_request_failed");
        emitNotice(
                goal.revision(),
                "model_failure."
                    + failure.kind().name().toLowerCase(Locale.ROOT)
        );
        if (isFatalModelFailure(failure.kind())) {
            if (failure.kind() == ModelFailureKind.AUTHENTICATION) {
                modelGateway.invalidateAfterAuthenticationFailure();
                emitSystemSpeech(
                    goal,
                    "model-authentication-" + goal.revision(),
                    "模型 API Key 无效，我已暂停自动操作；请在 AI 陪玩设置中重新验证 API Key。"
                );
            } else if (failure.kind() == ModelFailureKind.PERMISSION
                    || failure.kind() == ModelFailureKind.BILLING) {
                emitSystemSpeech(
                    goal,
                    "model-access-" + goal.revision(),
                    "模型服务拒绝了当前请求，我已暂停自动操作；请检查模型权限或账户额度。"
                );
            }
            terminal(goal, GoalStatus.SAFE_IDLE, "model_unavailable");
            return;
        }
        if (failure.kind() == ModelFailureKind.RATE_LIMITED) {
            handleRateLimit(goal, failure, now);
            return;
        }
        consecutiveRateLimits = 0;
        handleTransientModelFailure(
                goal,
                "model_transient_failure",
                now,
                failure.retryAfter().orElse(null),
                failure.kind()
        );
    }

    /**
     * A provider throttle is an instruction to slow down, not evidence that
     * the player's goal is invalid. Retrying at the ordinary 250 ms replan
     * cadence only amplifies the throttle and used to terminate a valid goal
     * after three rapid failures. Keep the goal installed, preserve local
     * 20 TPS safety behaviour, and retry at 10/20/40/60 second production
     * intervals when the provider omits Retry-After.
     */
    private void handleRateLimit(
            final GoalSnapshot goal,
            final ModelFailure failure,
            final long now
    ) {
        consecutiveModelFailures++;
        consecutiveRateLimits = Math.min(
                consecutiveRateLimits + 1,
                4
        );
        if (consecutiveRateLimits == 1) {
            try {
                events.emit(new BrainEvent.Speech(
                        goal.revision(),
                        "local-rate-limit-" + goal.revision(),
                        "模型服务正在限流，我会保持安全并自动重试，不用重复发送任务。"
                ));
            } catch (RuntimeException ignored) {
                // Status reporting cannot affect goal continuity.
            }
        }
        final Duration providerDelay =
                failure.retryAfter().orElse(Duration.ZERO);
        final Duration localDelay = rateLimitBackoff(
                policy.minimumReplanBackoff(),
                consecutiveRateLimits
        );
        scheduleBackoff(
                now,
                providerDelay.compareTo(localDelay) > 0
                        ? providerDelay
                        : localDelay
        );
        emitNotice(goal.revision(), "model_rate_limit_backoff");
    }

    private void handleTransientModelFailure(
            GoalSnapshot goal,
            String eventCode,
            long now,
            Duration retryAfter,
            ModelFailureKind failureKind
    ) {
        emitNotice(goal.revision(), eventCode);
        consecutiveModelFailures = saturatingIncrement(
                consecutiveModelFailures
        );
        if (isProviderOutageFailure(failureKind)) {
            Duration delay = providerOutageBackoff(
                    policy.minimumReplanBackoff(),
                    consecutiveModelFailures
            );
            if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
                delay = retryAfter;
            }
            scheduleBackoff(now, delay);
            emitNotice(
                    goal.revision(),
                    "model_provider_outage_backoff"
            );
            return;
        }
        if (consecutiveModelFailures >= policy.maxConsecutiveModelFailures()) {
            terminal(goal, GoalStatus.SAFE_IDLE, "model_failures_exhausted");
            return;
        }
        Duration delay = policy.minimumReplanBackoff();
        if (retryAfter != null && retryAfter.compareTo(delay) > 0) {
            delay = retryAfter;
        }
        scheduleBackoff(now, delay);
    }

    private void issuePlannerRequest(
            GoalSnapshot goal,
            BrainObservation observation,
            long now
    ) {
        if (goal.status() != GoalStatus.RUNNING
                || observation.skillContext().goalRevision() != goal.revision()
                || inFlight != null
                || isActive(skills.snapshot())) {
            return;
        }
        GatewayStatus gatewayStatus = modelGateway.status();
        if (gatewayStatus == GatewayStatus.CLOSED) {
            terminal(goal, GoalStatus.SAFE_IDLE, "model_gateway_closed");
            emitNotice(goal.revision(), "model_gateway_closed");
            return;
        }
        if (gatewayStatus != GatewayStatus.IDLE) {
            scheduleBackoff(now, policy.minimumReplanBackoff());
            return;
        }

        String requestId = "brain-" + goal.revision() + "-" + (++requestSequence);
        final BrainObservation plannerObservation =
                withPlannerCorrection(
                        observation,
                        lastModelDecisionFailureCode
                );
        final PlannerInput input;
        try {
            input = Objects.requireNonNull(
                    plannerInputs.create(
                            requestId,
                            goal,
                            plannerObservation
                    ),
                    "plannerInputs.create()"
            );
        } catch (Exception exception) {
            if (Boolean.getBoolean("mcai.liveModelTest")) {
                MinecraftAiCompanion.LOGGER.info(
                        "Live-model planner input rejected: type={}, "
                            + "message={}, semanticChars={}, "
                            + "trustedRuntimeChars={}",
                        exception.getClass().getSimpleName(),
                        Objects.requireNonNullElse(
                                exception.getMessage(),
                                "unspecified"
                        ),
                        observation.semanticJson().length(),
                        observation.trustedRuntimeJson().length()
                );
            }
            terminal(goal, GoalStatus.SAFE_IDLE, "planner_input_invalid");
            emitNotice(goal.revision(), "planner_input_invalid");
            return;
        }
        if (!validPlannerBinding(
                input,
                requestId,
                goal,
                plannerObservation
        )) {
            terminal(goal, GoalStatus.SAFE_IDLE, "planner_input_invalid");
            emitNotice(goal.revision(), "planner_input_invalid");
            return;
        }
        emitModelAudit(
            goal.revision(),
            requestId,
            BrainEvent.ModelAuditStage.AI_PERCEPTION_RECEIVED,
            observation.epoch(),
            Optional.empty(),
            "",
            Optional.empty()
        );

        InFlight request = new InFlight(
                requestId,
                goal.revision(),
                observation.epoch(),
                semanticSampleSequence(observation),
                now
        );
        inFlight = request;
        final CompletionStage<ModelOutcome> stage;
        try {
            stage = modelGateway.decide(input);
        } catch (RuntimeException exception) {
            inFlight = null;
            handleTransientModelFailure(
                    goal,
                    "model_transport_failure",
                    now,
                    null,
                    ModelFailureKind.NETWORK_TRANSIENT
            );
            return;
        }
        if (stage == null) {
            inFlight = null;
            handleTransientModelFailure(
                    goal,
                    "model_transport_failure",
                    now,
                    null,
                    ModelFailureKind.NETWORK_TRANSIENT
            );
            return;
        }
        emitModelAudit(
            goal.revision(),
            requestId,
            BrainEvent.ModelAuditStage.MODEL_REQUEST_STARTED,
            observation.epoch(),
            Optional.empty(),
            "",
            Optional.empty()
        );
        try {
            stage.whenComplete((outcome, throwable) ->
                    offerCompletion(new PlannerCompletion(
                            requestId,
                            goal.revision(),
                            observation.epoch(),
                            request.semanticSampleSequence,
                            outcome,
                            throwable != null
                    )));
        } catch (RuntimeException exception) {
            if (inFlight == request) {
                inFlight = null;
            }
            handleTransientModelFailure(
                    goal,
                    "model_transport_failure",
                    now,
                    null,
                    ModelFailureKind.NETWORK_TRANSIENT
            );
        }
    }

    static BrainObservation withPlannerCorrection(
            final BrainObservation observation,
            final String correctionCode
    ) {
        Objects.requireNonNull(observation, "observation");
        final String normalized =
                Objects.requireNonNullElse(
                        correctionCode,
                        ""
                );
        if (normalized.isEmpty()) {
            return observation;
        }
        try {
            final var trusted = JsonParser
                    .parseString(observation.trustedRuntimeJson())
                    .getAsJsonObject();
            trusted.addProperty(
                    "lastModelDecisionFailureCode",
                    normalized
            );
            final String encoded = trusted.toString();
            if (encoded.length()
                    > BrainObservation
                        .MAX_TRUSTED_RUNTIME_JSON_CHARACTERS) {
                return observation;
            }
            return new BrainObservation(
                    observation.epoch(),
                    observation.skillContext(),
                    observation.semanticJson(),
                    encoded
            );
        } catch (RuntimeException invalidTrustedRuntime) {
            return observation;
        }
    }

    static String plannerCorrectionCode(
            final ModelFailure failure
    ) {
        if (failure.kind()
                == ModelFailureKind.CONTEXT_LIMIT) {
            return "context_limit";
        }
        if (failure.kind()
                != ModelFailureKind.MALFORMED_RESPONSE) {
            return "";
        }
        if (failure.safeMessage().endsWith(
                "invalid_skill_arguments"
        )) {
            return "invalid_skill_arguments";
        }
        if (failure.safeMessage().endsWith("unknown_skill")) {
            return "unknown_skill";
        }
        return "malformed_response";
    }

    private static boolean validPlannerBinding(
            PlannerInput input,
            String requestId,
            GoalSnapshot goal,
            BrainObservation observation
    ) {
        DecisionContext context = input.decisionContext();
        return context.requestId().equals(requestId)
                && context.goalRevision() == goal.revision()
                && context.observedWorldRevision() == observation.epoch()
                && !context.activeSkill();
    }

    private void offerCompletion(PlannerCompletion completion) {
        if (closed || !mailbox.compareAndSet(null, completion)) {
            droppedMailboxCompletions.incrementAndGet();
        }
    }

    /**
     * The sample sequence is a server-authored binding, not a creative model
     * choice. Providers sometimes copy a nearby counter incorrectly even
     * while selecting the right public observation ID. Replacing only this
     * one argument with the exact sequence from the request preserves the
     * target the model actually saw; the local skill still resolves the ID
     * against retained fair perception and revalidates current visibility.
     *
     * <p>The gatherer is deliberately exempt because it may bind a block
     * from {@code recentFairSurveyData}, whose per-block sample sequence is
     * older than the post-survey camera sample. Its local implementation
     * resolves that exact retained ray and then requires fresh visibility
     * before mining.</p>
     */
    static DecisionEnvelope bindAuthoritativeSampleSequence(
            final DecisionEnvelope decision,
            final long semanticSampleSequence
    ) {
        if (decision.decision() != DecisionKind.START_SKILL
                || semanticSampleSequence < 0L
                || ResourceGatheringSkills
                    .GATHER_VISIBLE_BLOCK_CLUSTER
                    .equals(decision.skillName())
                || "engage_observed_entity".equals(decision.skillName())
                || decision.typedArguments().stream().noneMatch(
                    argument -> "sampleSequence".equals(
                            argument.name()
                    )
                )) {
            return decision;
        }
        final var arguments = decision.typedArguments().stream()
                .map(argument -> "sampleSequence".equals(argument.name())
                        ? new SkillArgument(
                            argument.name(),
                            Long.toString(semanticSampleSequence)
                        )
                        : argument
                )
                .toList();
        return new DecisionEnvelope(
                decision.requestId(),
                decision.observedWorldRevision(),
                decision.goalRevision(),
                decision.decision(),
                decision.skillName(),
                arguments,
                decision.requestedObservation(),
                decision.optionalSpeech(),
                decision.confidence()
        );
    }

    /**
     * Rebinds only a melee intent that crossed a local emergency tick while
     * the provider was thinking.  The original observation sequence remains
     * in the decision so {@code EngageObservedEntitySkill} can resolve and
     * retain the original visible UUID; it then requires that exact UUID to
     * be visible in the current first-person sample before any attack starts.
     * This prevents index retargeting while avoiding a false no-action loop
     * caused by a legal shield/backstep changing the world epoch.
     */
    private static Optional<DecisionEnvelope> rebaseCombatDecision(
            final DecisionEnvelope decision,
            final PlannerCompletion completion,
            final BrainObservation observation,
            final long worldDrift
    ) {
        if (worldDrift <= 0L
                || worldDrift > MAX_COMBAT_DECISION_REBASE_EPOCH_DRIFT
                || decision.decision() != DecisionKind.START_SKILL
                || !"engage_observed_entity".equals(
                        decision.skillName()
                )
                || !decision.requestId().equals(completion.requestId)
                || decision.goalRevision() != completion.goalRevision
                || decision.observedWorldRevision()
                    != completion.observationEpoch
                || decision.typedArguments().stream().noneMatch(argument ->
                        "sampleSequence".equals(argument.name()))
                || decision.typedArguments().stream().noneMatch(argument ->
                        "observationId".equals(argument.name()))) {
            return Optional.empty();
        }
        return Optional.of(new DecisionEnvelope(
                decision.requestId(),
                observation.epoch(),
                decision.goalRevision(),
                decision.decision(),
                decision.skillName(),
                decision.typedArguments(),
                decision.requestedObservation(),
                decision.optionalSpeech(),
                decision.confidence()
        ));
    }

    /**
     * Rebinds a pure planning response after a small observation drift. A
     * CONTINUE or REPLAN envelope carries no world mutation, target UUID,
     * route, or skill lease, so accepting it against the newest fair sample
     * is safe and avoids making a teammate appear frozen while the model was
     * thinking. Action decisions remain strict: only the narrow combat
     * rebase above may cross an epoch boundary.
     */
    private static Optional<DecisionEnvelope> rebaseNonActionDecision(
            final DecisionEnvelope decision,
            final PlannerCompletion completion,
            final BrainObservation observation,
            final long worldDrift
    ) {
        if (worldDrift <= 0L
                || worldDrift > MAX_COMBAT_DECISION_REBASE_EPOCH_DRIFT
                || (decision.decision() != DecisionKind.CONTINUE
                    && decision.decision() != DecisionKind.REPLAN)
                || !decision.requestId().equals(completion.requestId)
                || decision.goalRevision() != completion.goalRevision
                || decision.observedWorldRevision()
                    != completion.observationEpoch
                || !decision.skillName().isBlank()
                || !decision.typedArguments().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DecisionEnvelope(
                decision.requestId(),
                observation.epoch(),
                decision.goalRevision(),
                decision.decision(),
                "",
                List.of(),
                decision.requestedObservation(),
                decision.optionalSpeech(),
                decision.confidence()
        ));
    }

    private static long semanticSampleSequence(
            final BrainObservation observation
    ) {
        try {
            final long value = JsonParser
                    .parseString(observation.semanticJson())
                    .getAsJsonObject()
                    .get("sampleSequence")
                    .getAsLong();
            if (value < 0L) {
                throw new IllegalArgumentException(
                        "Negative semantic sample sequence"
                );
            }
            return value;
        } catch (RuntimeException malformedObservation) {
            // Loader-independent unit observations may intentionally omit
            // Minecraft's semantic sequence. In that case there is no local
            // value to canonicalize and ordinary validation remains in force.
            return -1L;
        }
    }

    private boolean matchesCurrentRequest(PlannerCompletion completion) {
        return completion != null
                && inFlight != null
                && completion.requestId.equals(inFlight.requestId)
                && completion.goalRevision == inFlight.goalRevision
                && completion.observationEpoch == inFlight.observationEpoch;
    }

    private boolean requestTimedOut(long now, InFlight request) {
        long elapsed = now - request.startedAtNanos;
        return elapsed < 0 || elapsed >= policy.requestTimeout().toNanos();
    }

    private boolean requestSoftDeadlineReached(long now, InFlight request) {
        long elapsed = now - request.startedAtNanos;
        return elapsed >= 0
                && elapsed >= policy.softRequestTimeout().toNanos();
    }

    private void handleRequestTimeout(
            final GoalSnapshot goal,
            final long now
    ) {
        final InFlight timedOut = inFlight;
        if (timedOut == null) {
            return;
        }
        timedOut.cancellationRequested = true;
        final long differentRevision = goal.revision() == Long.MAX_VALUE
                ? goal.revision() - 1L
                : goal.revision() + 1L;
        modelGateway.cancelForGoalRevision(differentRevision);
        if (inFlight == timedOut) {
            inFlight = null;
        }
        mailbox.set(null);
        handleTransientModelFailure(
                goal,
                "model_request_timeout",
                now,
                null,
                ModelFailureKind.TIMEOUT
        );
    }

    private void scheduleBackoff(long now, Duration delay) {
        long delayNanos;
        try {
            delayNanos = delay.toNanos();
        } catch (ArithmeticException exception) {
            delayNanos = Long.MAX_VALUE;
        }
        nextRequestNotBeforeNanos = saturatingAdd(now, Math.max(1, delayNanos));
    }

    private void terminal(
            GoalSnapshot expected,
            GoalStatus status,
            String code
    ) {
        GoalSnapshot current = goals.snapshot();
        if (current.revision() != expected.revision()
                || (current.status() != GoalStatus.RUNNING
                && current.status() != GoalStatus.CANCEL_PENDING)) {
            return;
        }
        goals.markTerminal(status, code);
        waitingForPlayer = false;
        waitingGoalRevision = -1;
        if (inFlight != null && !inFlight.cancellationRequested) {
            inFlight.cancellationRequested = true;
            modelGateway.cancelForGoalRevision(goals.snapshot().revision());
        }
    }

    private void emitSpeech(GoalSnapshot goal, DecisionEnvelope decision) {
        if (decision.optionalSpeech().isBlank()) {
            return;
        }
        try {
            events.emit(new BrainEvent.Speech(
                    goal.revision(),
                    decision.requestId(),
                    decision.optionalSpeech()
            ));
        } catch (RuntimeException exception) {
            emitNotice(goal.revision(), "speech_event_rejected");
        }
    }

    private void emitSystemSpeech(
            final GoalSnapshot goal,
            final String requestId,
            final String message
    ) {
        try {
            events.emit(new BrainEvent.Speech(
                    goal.revision(),
                    requestId,
                    message
            ));
        } catch (RuntimeException exception) {
            emitNotice(goal.revision(), "system_speech_rejected");
        }
    }

    private void emitNotice(long goalRevision, String code) {
        try {
            events.emit(new BrainEvent.Notice(goalRevision, code));
        } catch (RuntimeException ignored) {
            // Event consumers cannot affect game decisions or expose exceptions.
        }
    }

    private void emitSkillNotice(
            final long goalRevision,
            final String transition,
            final String skillName
    ) {
        if (skillName == null || skillName.isBlank()) {
            return;
        }
        emitNotice(
                goalRevision,
                transition + "." + skillName
        );
    }

    private void emitUsage(
            final long goalRevision,
            final String requestId,
            final dev.mcai.companion.model.TokenUsage usage
    ) {
        try {
            events.emit(new BrainEvent.Usage(
                goalRevision,
                requestId,
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens()
            ));
        } catch (RuntimeException ignored) {
            // Audit output must not gain authority over body safety.
        }
    }

    private void emitModelAudit(
        final long goalRevision,
        final String requestId,
        final BrainEvent.ModelAuditStage stage,
        final long observedWorldRevision,
        final Optional<DecisionKind> decision,
        final String skillName,
        final Optional<RequestTrace> trace
    ) {
        try {
            events.emit(new BrainEvent.ModelAudit(
                goalRevision,
                requestId,
                stage,
                observedWorldRevision,
                decision,
                skillName,
                trace
            ));
        } catch (RuntimeException ignored) {
            // Audit output cannot affect model or world decisions.
        }
    }

    private void publishDroppedMailboxNotice(long goalRevision) {
        long dropped = droppedMailboxCompletions.getAndSet(0);
        if (dropped > 0) {
            emitNotice(goalRevision, "mailbox_completion_dropped");
        }
    }

    private static boolean isActive(SkillSupervisor.Snapshot snapshot) {
        return snapshot.state() == SkillSupervisor.State.RUNNING
                || snapshot.state() == SkillSupervisor.State.CANCEL_PENDING;
    }

    private static SkillContext rebindForActiveSkill(
            SkillContext observed,
            SkillSupervisor.Snapshot active
    ) {
        return new SkillContext(
                active.boundGoalRevision(),
                active.boundWorldRevision(),
                observed.gameTick(),
                observed.hardcore(),
                observed.modelConnected(),
                observed.riskScore()
        );
    }

    private static boolean isFatalModelFailure(ModelFailureKind kind) {
        return switch (kind) {
            case INVALID_CONFIGURATION,
                    AUTHENTICATION,
                    PERMISSION,
                    BILLING,
                    MODEL_NOT_FOUND,
                    ENDPOINT_UNSUPPORTED,
                    CAPABILITY_UNSUPPORTED,
                    INVALID_REQUEST,
                    CONTENT_FILTERED,
                    INTERNAL -> true;
            case RATE_LIMITED,
                    CONTEXT_LIMIT,
                    SERVER_TRANSIENT,
                    NETWORK_TRANSIENT,
                    TIMEOUT,
                    MALFORMED_RESPONSE,
                    CANCELLED,
                    STALE_RESPONSE,
                    BUSY -> false;
        };
    }

    private static boolean isProviderOutageFailure(
            final ModelFailureKind kind
    ) {
        return kind == ModelFailureKind.NETWORK_TRANSIENT
                || kind == ModelFailureKind.SERVER_TRANSIENT
                || kind == ModelFailureKind.TIMEOUT;
    }

    private static int saturatingIncrement(final int value) {
        return value == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : value + 1;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static Duration rateLimitBackoff(
            final Duration minimum,
            final int streak
    ) {
        final long minimumNanos = minimum.toNanos();
        final long base = saturatingMultiply(minimumNanos, 40L);
        final int exponent = Math.max(0, Math.min(2, streak - 1));
        final long expanded = saturatingMultiply(
                base,
                1L << exponent
        );
        final long cap = saturatingMultiply(minimumNanos, 240L);
        return Duration.ofNanos(Math.max(
                minimumNanos,
                Math.min(expanded, cap)
        ));
    }

    /**
     * A provider outage is external availability state, not evidence that the
     * installed player goal is invalid. Retry slowly enough to avoid a
     * reconnect storm while the local safety controller continues at 20 TPS.
     * With the production 250 ms minimum this yields 2/4/8/16/32/60 second
     * attempts and remains capped at one request per minute.
     */
    private static Duration providerOutageBackoff(
            final Duration minimum,
            final int streak
    ) {
        final long minimumNanos = minimum.toNanos();
        final long base = saturatingMultiply(minimumNanos, 8L);
        final int exponent = Math.max(0, Math.min(5, streak - 1));
        final long expanded = saturatingMultiply(
                base,
                1L << exponent
        );
        final long cap = Math.max(
                minimumNanos,
                Duration.ofSeconds(60).toNanos()
        );
        return Duration.ofNanos(Math.max(
                minimumNanos,
                Math.min(expanded, cap)
        ));
    }

    /**
     * A syntactically valid CONTINUE/REPLAN without an active local skill
     * changes nothing in the world. Retrying such a response every 250 ms
     * can burn an entire context repeatedly while the body stands still.
     * Exponential backoff is reset by an accepted skill or goal change and
     * capped at two seconds, so recovery stays responsive without a
     * high-frequency token loop.
     */
    private static Duration noActionBackoff(
            final Duration minimum,
            final int streak
    ) {
        final long minimumNanos = minimum.toNanos();
        final int exponent = Math.max(
                0,
                Math.min(6, streak - 1)
        );
        final long expanded = saturatingMultiply(
                minimumNanos,
                1L << exponent
        );
        /*
         * A speech-only response is not progress.  A ten-second cap made a
         * live teammate appear frozen after a provider returned several
         * valid but non-actionable plans in succession.  Keep the retry
         * budget bounded, but make the next correction visible within a
         * normal conversational pause.
         */
        final long cap = Duration.ofSeconds(2).toNanos();
        return Duration.ofNanos(Math.max(
                minimumNanos,
                Math.min(expanded, cap)
        ));
    }

    private static long saturatingMultiply(
            final long value,
            final long multiplier
    ) {
        if (value <= 0L || multiplier <= 0L) {
            return 0L;
        }
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }

    public enum State {
        IDLE,
        READY,
        REQUESTING_MODEL,
        EXECUTING_SKILL,
        BACKOFF,
        WAITING_FOR_PLAYER,
        CANCEL_PENDING,
        CLOSED
    }

    public record Snapshot(
            State state,
            long goalRevision,
            long observationEpoch,
            String inFlightRequestId,
            boolean mailboxOccupied,
            boolean waitingForPlayer,
            int consecutiveModelFailures,
            long droppedMailboxCompletions
    ) {
        public Snapshot {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(inFlightRequestId, "inFlightRequestId");
        }
    }

    private static final class InFlight {
        private final String requestId;
        private final long goalRevision;
        private final long observationEpoch;
        private final long semanticSampleSequence;
        private final long startedAtNanos;
        private boolean cancellationRequested;
        private boolean softDeadlineReported;

        private InFlight(
                String requestId,
                long goalRevision,
                long observationEpoch,
                long semanticSampleSequence,
                long startedAtNanos
        ) {
            this.requestId = requestId;
            this.goalRevision = goalRevision;
            this.observationEpoch = observationEpoch;
            this.semanticSampleSequence = semanticSampleSequence;
            this.startedAtNanos = startedAtNanos;
        }
    }

    private record PlannerCompletion(
            String requestId,
            long goalRevision,
            long observationEpoch,
            long semanticSampleSequence,
            ModelOutcome outcome,
            boolean transportFailure
    ) {}
}
