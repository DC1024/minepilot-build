package dev.mcai.companion.skills.end;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalAStarPlanner;
import dev.mcai.companion.navigation.LocalPlannerOptions;
import dev.mcai.companion.navigation.LocalPlanningBudget;
import dev.mcai.companion.navigation.LocalRoute;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.NavigationRiskProfile;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.CollisionAffordance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.bridging.BridgeMaterialActuator;
import dev.mcai.companion.skills.bridging.BridgeToParameters;
import dev.mcai.companion.skills.bridging.BridgeToSkill;
import dev.mcai.companion.skills.bridging.TowerUpParameters;
import dev.mcai.companion.skills.bridging.TowerUpSkill;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.TravelToParameters;
import dev.mcai.companion.skills.core.TravelToSkill;
import dev.mcai.companion.skills.interaction.BreakBlockParameters;
import dev.mcai.companion.skills.interaction.BreakBlockSkill;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillPolicy;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Reaches the natural central End island using only fair observations.
 *
 * <p>The only global fact used here is vanilla's seed-independent horizontal
 * End origin. It is a heading, never a landing coordinate. Every future
 * standing cell must be supplied by the current first-person semantic frame.
 * Unknown space is inspected, one bridge block is authorized at a time, and
 * completion requires a new observation of natural End stone directly under
 * the authoritative body.</p>
 */
public final class EndIslandIngressSkill
        implements Skill<EndIslandIngressParameters> {
    public static final String NAME = "reach_end_island";

    private static final String END_STONE = "minecraft:end_stone";
    private static final double BRIDGE_ARRIVAL_RADIUS = 0.65;
    /* BridgeToSkill intentionally accepts no arrival radius below 0.5.  A
     * neighbouring cell center can nevertheless be within that radius when
     * the body is standing on a cell edge, which would complete a side-step
     * without changing feet().  Aim at the far, still-inside edge of the
     * observed destination cell so the vanilla child must actually cross the
     * grid boundary. */
    private static final double SIDE_STEP_EDGE_OFFSET = 0.44;
    /* Keep a player-scale arrival envelope: TravelTo must enter the observed
     * cell, after which the parent performs the exact support/clearance
     * verification before accepting the landfall. */
    private static final double LANDFALL_ARRIVAL_RADIUS = 0.75;
    private static final double MINIMUM_CENTER_PROGRESS = 0.20;
    private static final double CURRENT_SUPPORT_CENTER_TOLERANCE = 0.72;
    private static final int MINIMUM_SCAN_INTERVAL_TICKS = 2;
    /* A fight re-entry can reach its final occupied bridge cell on the same
     * observation that the child deadline expires.  Allow a small, finite
     * evidence grace for the completion proof; never extend an ordinary
     * initial-ingress timeout. */
    private static final int REENTRY_COMPLETION_GRACE_TICKS = 80;
    /* Rotation is applied through the vanilla input packet at post-tick.  A
     * fresh semantic frame can therefore arrive while the real crosshair is
     * still on the neighbouring wall.  Keep the retry finite, but allow
     * several full look/observation cycles before abandoning a legal target. */
    private static final int BLOCK_ALIGNMENT_TIMEOUT_TICKS = 100;
    private static final int STABLE_GROUND_RECOVERY_TIMEOUT_TICKS = 80;
    private static final List<String> PICKAXE_PRIORITY = List.of(
            "minecraft:netherite_pickaxe",
            "minecraft:diamond_pickaxe",
            "minecraft:iron_pickaxe",
            "minecraft:stone_pickaxe",
            "minecraft:golden_pickaxe",
            "minecraft:wooden_pickaxe"
    );
    private static final LocalPlanningBudget LANDFALL_PLANNING_BUDGET =
            new LocalPlanningBudget(2_048, Duration.ofMillis(2));

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;
    private final BridgeMaterialActuator materials;
    private final LongSupplier sessionGeneration;
    private final InteractionSkillActuator interactionActuator;
    private final InteractionSkillFrameSource interactionFrames;
    private final LongConsumer completionSink;
    private final double completionRadiusOverride;
    private final LocalAStarPlanner landfallPlanner =
            new LocalAStarPlanner();

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;
    private long lastObservationRevision = -1;
    private long requiredFreshRevision = -1;
    private long nextScanTick = -1;
    private double initialRadius = Double.NaN;
    private double bestRadius = Double.NaN;
    private int bridgeSteps;
    private int towerSteps;
    private int minedBlocks;
    private int scanTurns;
    private int childFailures;
    private int landfallAttempts;
    private int frontierProbeIndex;
    private boolean frontierProbePending;
    private long frontierProbeRevision = -1;
    /**
     * Feet cells visited by the current fair route frontier.  Side-step
     * selection must not bounce between the same two observed cells while a
     * solid centerward wall is still unresolved.  This is only an in-memory
     * route guard; it never supplies terrain or support evidence.
     */
    private final Set<GridPos> visitedIngressCells = new LinkedHashSet<>();
    /** Fairly observed landfall cells whose vanilla travel became stuck. */
    private final Set<GridPos> failedLandfallSupports =
            new LinkedHashSet<>();
    private String lastChildFailureCode = "";
    private GridPos candidateSupport;
    private BridgeToSkill bridge;
    private BridgeToParameters bridgeParameters;
    private TowerUpSkill tower;
    private TowerUpParameters towerParameters;
    private BreakBlockSkill blockBreak;
    private BreakBlockParameters blockBreakParameters;
    private BlockCoordinate pendingBreakBlock;
    private String lastBreakTarget = "";
    private double bridgeStartRadius = Double.NaN;
    private GridPos bridgeStartFeet;
    private GridPos bridgeExpectedFeet;
    private boolean bridgeRequiresCenterProgress;
    private long blockAlignmentStartedTick = -1;
    private long blockAlignmentReadyRevision = -1;
    private long stableGroundRecoveryStartedTick = -1;
    private TravelToSkill travel;
    private TravelToParameters travelParameters;
    private boolean completionPublished;
    private boolean initialObservationPending;

    public EndIslandIngressSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final BridgeMaterialActuator materials,
            final LongSupplier sessionGeneration
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                materials,
                sessionGeneration,
                null,
                null,
                ignored -> {
                },
                Double.NaN
        );
    }

    public EndIslandIngressSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final BridgeMaterialActuator materials,
            final LongSupplier sessionGeneration,
            final InteractionSkillActuator interactionActuator,
            final InteractionSkillFrameSource interactionFrames
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                materials,
                sessionGeneration,
                interactionActuator,
                interactionFrames,
                ignored -> {
                },
                Double.NaN
        );
    }

    public EndIslandIngressSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final BridgeMaterialActuator materials,
            final LongSupplier sessionGeneration,
            final InteractionSkillActuator interactionActuator,
            final InteractionSkillFrameSource interactionFrames,
            final LongConsumer completionSink
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                materials,
                sessionGeneration,
                interactionActuator,
                interactionFrames,
                completionSink,
                Double.NaN
        );
    }

    /**
     * Creates an ingress controller with a stricter centerward completion
     * radius. The ordinary player-facing ingress keeps its parameter-defined
     * handoff radius; a dragon-fight recovery can require the body to continue
     * through observed routes until it is close enough for first-person entity
     * perception, without exposing a hidden destination.
     */
    public EndIslandIngressSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final BridgeMaterialActuator materials,
            final LongSupplier sessionGeneration,
            final InteractionSkillActuator interactionActuator,
            final InteractionSkillFrameSource interactionFrames,
            final LongConsumer completionSink,
            final double completionRadiusOverride
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.sessionGeneration = Objects.requireNonNull(
                sessionGeneration,
                "sessionGeneration"
        );
        if ((interactionActuator == null) != (interactionFrames == null)) {
            throw new IllegalArgumentException(
                    "Interaction dependencies must be supplied together"
            );
        }
        this.interactionActuator = interactionActuator;
        this.interactionFrames = interactionFrames;
        this.completionSink = Objects.requireNonNull(
                completionSink,
                "completionSink"
        );
        if (Double.isFinite(completionRadiusOverride)
                && (completionRadiusOverride < 1.0
                    || completionRadiusOverride > 64.0)) {
            throw new IllegalArgumentException(
                    "completionRadiusOverride must be in [1, 64]"
            );
        }
        this.completionRadiusOverride = completionRadiusOverride;
    }

    @Override
    public SkillParameterParser<EndIslandIngressParameters> parameters() {
        return arguments -> arguments != null && arguments.isEmpty()
                ? SkillParameterResult.valid(
                        EndIslandIngressParameters
                                .localControllerDefaults()
                )
                : SkillParameterResult.invalid(
                        NAME + ".invalid_arguments"
                );
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final EndIslandIngressParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<CoreSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return failure(NAME + ".observation_unavailable");
        }
        final CoreSkillFrame frame = current.orElseThrow();
        final Optional<SkillFailure> bodyFailure = validateBody(frame);
        if (bodyFailure.isPresent()) {
            return bodyFailure;
        }
        if (!DimensionRef.END.equals(frame.dimension())) {
            return failure(NAME + ".end_dimension_required");
        }
        if (!frame.onGround() || frame.inWater()) {
            return failure(NAME + ".stable_ground_required");
        }
        if (EndArenaTopology.horizontalRadius(frame.position())
                > parameters.maximumStartRadius()) {
            return failure(NAME + ".outside_end_spawn_envelope");
        }
        if (currentSessionGeneration() < 0) {
            return failure(NAME + ".session_unavailable");
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final EndIslandIngressParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final CoreSkillFrame frame = frames.current().orElseThrow(
                () -> new IllegalStateException(
                        "End ingress body changed before start"
                )
        );
        boundSessionGeneration = currentSessionGeneration();
        if (boundSessionGeneration < 0) {
            throw new IllegalStateException(
                    "End ingress session is unavailable"
            );
        }
        phase = Phase.SCANNING;
        failure = null;
        startedAtTick = context.gameTick();
        lastObservationRevision = frame.observationRevision();
        requiredFreshRevision = frame.observationRevision();
        nextScanTick = context.gameTick();
        initialRadius = EndArenaTopology.horizontalRadius(
                frame.position()
        );
        bestRadius = initialRadius;
        bridgeSteps = 0;
        towerSteps = 0;
        minedBlocks = 0;
        scanTurns = 0;
        childFailures = 0;
        landfallAttempts = 0;
        frontierProbeIndex = 0;
        frontierProbePending = false;
        frontierProbeRevision = -1;
        visitedIngressCells.clear();
        visitedIngressCells.add(frame.feet());
        failedLandfallSupports.clear();
        lastChildFailureCode = "";
        candidateSupport = null;
        bridge = null;
        bridgeParameters = null;
        tower = null;
        towerParameters = null;
        blockBreak = null;
        blockBreakParameters = null;
        pendingBreakBlock = null;
        lastBreakTarget = "";
        bridgeStartRadius = Double.NaN;
        bridgeStartFeet = null;
        bridgeExpectedFeet = null;
        bridgeRequiresCenterProgress = false;
        blockAlignmentStartedTick = -1;
        blockAlignmentReadyRevision = -1;
        stableGroundRecoveryStartedTick = -1;
        travel = null;
        travelParameters = null;
        completionPublished = false;
        initialObservationPending = true;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final EndIslandIngressParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context, parameters);
        } catch (RuntimeException exception) {
            return fail(NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final EndIslandIngressParameters parameters
    ) {
        final Optional<CoreSkillFrame> current = frames.current();
        final PerceptionVec3 position = current
                .map(CoreSkillFrame::position)
                .orElse(new PerceptionVec3(0.0, 0.0, 0.0));
        final double radius = current
                .map(CoreSkillFrame::position)
                .map(EndArenaTopology::horizontalRadius)
                .orElse(Double.NaN);
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"x\":%.3f,"
                                + "\"y\":%.3f,\"z\":%.3f,"
                                + "\"radius\":%.3f,"
                                + "\"initialRadius\":%.3f,"
                                + "\"bridgeSteps\":%d,"
                                + "\"towerSteps\":%d,"
                                + "\"minedBlocks\":%d,"
                                + "\"scanTurns\":%d,"
                                + "\"childFailures\":%d,"
                                + "\"landfallAttempts\":%d,"
                                + "\"frontierProbeIndex\":%d,"
                                + "\"frontierProbePending\":%s,"
                                + "\"lastBreakTarget\":\"%s\","
                                + "\"lastChildFailure\":\"%s\","
                                + "\"candidateSupport\":\"%s\","
                                + "\"reentryOverride\":%.3f,"
                                + "\"reentryEligible\":%s}",
                        phase.name(),
                        position.x(),
                        position.y(),
                        position.z(),
                        radius,
                        initialRadius,
                        bridgeSteps,
                        towerSteps,
                        minedBlocks,
                        scanTurns,
                        childFailures,
                        landfallAttempts,
                        frontierProbeIndex,
                        frontierProbePending,
                        lastBreakTarget,
                        lastChildFailureCode,
                        candidateSupport == null
                                ? ""
                                : candidateSupport.toString(),
                        completionRadiusOverride,
                        current.map(frame ->
                                verifiedCurrentReentrySupport(
                                        frame,
                                        parameters
                                )
                        ).orElse(false)
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final EndIslandIngressParameters parameters
    ) {
        cancelChildren(context);
        quiesce();
        phase = Phase.CANCELLED;
        candidateSupport = null;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final EndIslandIngressParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(
                    SkillFailure.of(NAME + ".invalid_state")
            );
        };
    }

    /**
     * Returns a bounded diagnostic for the most recent ingress failure.
     *
     * <p>A retry-exhaustion identifier is assembled from a child failure and
     * can exceed the public {@link SkillFailure} length contract.  The
     * failure object correctly fails closed to {@code skill_failure} in that
     * case, but callers still need the last stable child code to diagnose a
     * physical gate without logging exception text or world data.</p>
     */
    public Optional<String> diagnosticFailureCode() {
        if (failure == null) {
            return lastChildFailureCode.isBlank()
                    ? Optional.empty()
                    : Optional.of(lastChildFailureCode);
        }
        if ("skill_failure".equals(failure.code())
                && !lastChildFailureCode.isBlank()) {
            return Optional.of(
                    "retry_exhausted." + lastChildFailureCode
            );
        }
        return Optional.of(failure.code());
    }

    private SkillTickResult tickSafely(
            final SkillContext context,
            final EndIslandIngressParameters parameters
    ) {
        final long elapsedTicks = context.gameTick() - startedAtTick;
        if (elapsedTicks >= parameters.timeoutTicks()
                && (!Double.isFinite(completionRadiusOverride)
                    || elapsedTicks >= parameters.timeoutTicks()
                        + REENTRY_COMPLETION_GRACE_TICKS)) {
            return fail(NAME + ".timed_out");
        }
        if (currentSessionGeneration() != boundSessionGeneration) {
            return fail(NAME + ".body_session_changed");
        }
        final Optional<CoreSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return fail(NAME + ".observation_unavailable");
        }
        final CoreSkillFrame frame = current.orElseThrow();
        final Optional<SkillFailure> bodyFailure = validateBody(frame);
        if (bodyFailure.isPresent()) {
            return fail(bodyFailure.orElseThrow());
        }
        if (!DimensionRef.END.equals(frame.dimension())) {
            return fail(NAME + ".end_dimension_lost");
        }
        if (frame.observationRevision() < lastObservationRevision) {
            return fail(NAME + ".stale_observation");
        }
        final double currentRadius = EndArenaTopology.horizontalRadius(
                frame.position()
        );
        if (Double.isNaN(bestRadius)
                || currentRadius + MINIMUM_CENTER_PROGRESS < bestRadius) {
            bestRadius = currentRadius;
        }
        final boolean fresh = frame.observationRevision()
                > lastObservationRevision;
        if (fresh) {
            lastObservationRevision = frame.observationRevision();
        }

        /* Re-entry may finish on a currently occupied player-built bridge
         * cell, but only after a fresh frame proves its body-contact support
         * and clear feet/head voxels.  Keep this narrow completion check ahead
         * of any active recovery child so a stale TowerUp/BreakBlock child
         * cannot hold a safely recovered fighter until the ingress timeout.
         * The initial ingress still reaches its strict End-stone completion
         * check below after the active child has yielded. */
        if (Double.isFinite(completionRadiusOverride)
                && !initialObservationPending
                && frame.observationRevision() >= requiredFreshRevision
                && verifiedCurrentReentrySupport(frame, parameters)) {
            quiesce();
            if (!completionPublished) {
                completionSink.accept(context.goalRevision());
                completionPublished = true;
            }
            phase = Phase.COMPLETED;
            candidateSupport = frame.feet().below();
            return SkillTickResult.completed();
        }

        /* TowerUp owns the jump/placement transaction, but it must not keep
         * looking upward when the newest first-person frame already proves
         * that the current column is capped by a reachable End-stone block.
         * A natural spawn wall can expose that ceiling only after a lateral
         * step; waiting for TowerUp's scan budget leaves the parent in
         * TOWERING_FOR_LANDFALL until the whole ingress timeout expires.  End
         * the child here and mine the exact observed face through the normal
         * alignment/break path.  This remains fair: the target, material and
         * reach all come from the same semantic frame, and no level lookup is
         * performed.
         */
        if (phase == Phase.TOWERING_FOR_LANDFALL
                && interactionActuator != null) {
            final Optional<VisibleBlockFace> overhead =
                    visibleIngressObstruction(frame).filter(face ->
                            isCurrentColumnOverhead(frame, face)
                    );
            if (overhead.isPresent()) {
                if (tower != null && towerParameters != null) {
                    tower.cancel(context, towerParameters);
                }
                tower = null;
                towerParameters = null;
                lastChildFailureCode =
                        "tower_up.overhead_clearance_unverified";
                return startVisibleBlockBreak(
                        context,
                        parameters,
                        frame,
                        overhead.orElseThrow()
                );
            }
        }

        if (phase == Phase.BRIDGING_ONE_STEP) {
            return tickBridge(context, parameters, frame, fresh);
        }
        if (phase == Phase.TOWERING_FOR_LANDFALL) {
            return tickTower(context, parameters, frame, fresh);
        }
        if (phase == Phase.TRAVELLING_TO_OBSERVED_END_STONE) {
            return tickTravel(context, parameters, frame, fresh);
        }
        if (phase == Phase.RECOVERING_STABLE_GROUND) {
            return tickStableGroundRecovery(context, frame);
        }

        /* Active child skills own their transient jumping/falling pose and
         * enforce their own safety envelope. Stable ground is required only
         * before this parent authorizes a new child or accepts completion. */
        if (!frame.onGround() || frame.inWater()) {
            cancelChildren(context);
            pendingBreakBlock = null;
            blockAlignmentStartedTick = -1;
            blockAlignmentReadyRevision = -1;
            stableGroundRecoveryStartedTick = context.gameTick();
            phase = Phase.RECOVERING_STABLE_GROUND;
            quiesce();
            return SkillTickResult.running(true, false);
        }
        if (phase == Phase.MINING_VISIBLE_END_STONE) {
            return tickBlockBreak(context, parameters, frame, fresh);
        }
        if (phase == Phase.ALIGNING_VISIBLE_BLOCK_BREAK) {
            return tickBlockAlignment(context, parameters, frame, fresh);
        }

        final boolean mayComplete = !initialObservationPending;
        initialObservationPending = false;
        if (mayComplete
                && frame.observationRevision() >= requiredFreshRevision
                && verifiedCurrentEndStoneSupport(frame, parameters)) {
            quiesce();
            if (!completionPublished) {
                completionSink.accept(context.goalRevision());
                completionPublished = true;
            }
            phase = Phase.COMPLETED;
            candidateSupport = frame.feet().below();
            return SkillTickResult.completed();
        }

        /* A natural End-stone cell can be visible before the navigation
         * mapper has refreshed the two-block head clearance.  Repeating the
         * centerward floor scan in that state burns the route budget while a
         * real player would simply look up once and re-check the landing
         * cell.  Ask for that fair first-person observation explicitly; the
         * completion predicate still requires the same-revision support,
         * feet and head voxels and never infers a hidden island. */
        if (EndArenaTopology.horizontalRadius(frame.position())
                    <= completionRadius(parameters)
                && visibleCurrentEndStoneSupport(frame)
                && !verifiedCurrentEndStoneSupport(frame, parameters)) {
            return orientForFreshSkyObservation(frame);
        }

        /* A knockback can leave the body standing on a legal bridge cell
         * whose mapper record contains only BODY_CONTACT and no top-face
         * affordance.  Do not reject every neighbouring route in that state:
         * aim at the current support first, then let the next semantic frame
         * prove its top.  This is the same player-visible observation a human
         * makes before stepping around a pillar and never reads the level. */
        if (historicalNavigationAllowed()
                && EndArenaTopology.horizontalRadius(frame.position()) < 60.0
                && currentSupportNeedsTopObservation(frame)) {
            return orientForCurrentSupport(frame);
        }

        if (phase == Phase.VERIFYING_CURRENT_SUPPORT) {
            if (fresh
                    && frame.observationRevision()
                        > requiredFreshRevision) {
                candidateSupport = null;
                return recoverChildFailure(
                        context,
                        parameters,
                        frame,
                        "landfall_verification"
                );
            }
            return orientForFreshObservation(context, parameters, frame);
        }
        if (context.gameTick() < nextScanTick
                || frame.observationRevision() <= requiredFreshRevision) {
            return orientForFreshObservation(context, parameters, frame);
        }

        if (frontierProbePending) {
            if (!fresh
                    || frame.observationRevision() <= frontierProbeRevision) {
                return orientForFrontierProbe(context, frame);
            }
            frontierProbePending = false;
            final Optional<SkillTickResult> attached =
                    startObservedAttachedStep(
                            context,
                            parameters,
                            frame
                    );
            if (attached.isPresent()) {
                return attached.orElseThrow();
            }
            final Optional<SkillTickResult> detour =
                    startObservedSideStep(context, parameters, frame);
            if (detour.isPresent()) {
                return detour.orElseThrow();
            }
        }

        /* A failed tower is a route-local observation, not a reason to lose
         * the next actionable mining frame.  Handle this before landfall/A*
         * selection: the natural End spawn can expose a side End-stone face
         * while the current-column head cell remains blocked, and asking the
         * planner to score that frame first otherwise sends the controller
         * back into the same opaque-wall probe loop. */
        if (lastChildFailureCode.startsWith("tower_up.")
                && interactionActuator != null) {
            final Optional<VisibleBlockFace> recoveryTarget =
                    visibleReentryExcavationTarget(frame);
            if (recoveryTarget.isPresent()) {
                return startVisibleBlockBreak(
                        context,
                        parameters,
                        frame,
                        recoveryTarget.orElseThrow()
                );
            }
        }

        scanTurns++;
        if (scanTurns > parameters.maximumScanTurns()) {
            return fail(NAME + ".scan_budget_exhausted");
        }
        final Optional<GridPos> landfall = selectLandfall(
                frame,
                parameters,
                context.hardcore()
        );
        if (landfall.isPresent()) {
            return startLandfallTravel(
                    context,
                    parameters,
                    frame,
                landfall.orElseThrow()
            );
        }
        final Optional<VisibleBlockFace> obstruction =
                visibleIngressObstruction(frame);
        if (obstruction.filter(face ->
                isCurrentColumnOverhead(frame, face)
        ).isPresent()) {
            return startVisibleBlockBreak(
                    context,
                    parameters,
                    frame,
                obstruction.orElseThrow()
            );
        }
        /* A visible wall at the body/head columns is a legal, bounded
         * excavation opportunity.  Prefer it when the body has been pushed
         * outward, or when real interaction wiring is available.  The
         * old tower-first branch could repeatedly pillar on the outside of
         * the natural End spawn wall and spend every retry without gaining
         * centerward distance. */
        final Optional<VisibleBlockFace> forwardWall =
                visibleCenterwardBodyWall(frame);
        final boolean centerwardRecoveryNeeded = currentRadius
                > bestRadius + MINIMUM_CENTER_PROGRESS;
        if (interactionActuator != null && forwardWall.isPresent()) {
            return startVisibleBlockBreak(
                    context,
                    parameters,
                    frame,
                forwardWall.orElseThrow()
            );
        }
        /* A natural End crystal pillar can occupy the centerward column while
         * its two lateral cells are ordinary End stone.  A player opens one
         * observed side cell and walks around the pillar; repeatedly placing
         * a bridge into the opaque pillar cannot make progress.  The helper
         * below admits only a lateral column with fresh support and head
         * clearance (including an adjacent overhead lip), so this remains a
         * fair, reversible vanilla mining action rather than an inferred
         * detour. */
        final Optional<VisibleBlockFace> lateralWall =
                visibleLateralIngressWall(frame);
        if (interactionActuator != null && lateralWall.isPresent()) {
            return startVisibleBlockBreak(
                    context,
                    parameters,
                    frame,
                    lateralWall.orElseThrow()
            );
        }
        /* During bounded re-entry, a directly adjacent side face is a legal
         * excavation target even when its lower support is not yet visible.
         * Waiting for a complete landing column creates a deadlock: the wall
         * hides the very support sample needed to approve the detour. Break
         * only a nearby End-stone side face from the newest fan (at most two
         * horizontal cells, still inside ordinary reach), then reacquire
         * navigation before moving. */
        final Optional<VisibleBlockFace> lateralObstruction =
                visibleReentryExcavationTarget(frame);
        if (interactionActuator != null
                && historicalNavigationAllowed()
                && currentRadius < 60.0
                && lateralObstruction.isPresent()) {
            return startVisibleBlockBreak(
                    context,
                    parameters,
                    frame,
                    lateralObstruction.orElseThrow()
            );
        }
        /* End crystal pillars are opaque obsidian and cannot be mined with
         * the survival pickaxe.  When the fair frame proves that such a wall
         * occupies the centerward feet/head column, climb one owned block to
         * obtain a new eye-line instead of starting a bridge into occupied
         * terrain.  TowerUp owns the placement, jump and landing checks. */
        if (visibleCenterwardObsidianWall(frame)) {
            final Optional<SkillTickResult> attached =
                    startObservedAttachedStep(
                            context,
                            parameters,
                            frame
                    );
            if (attached.isPresent()) {
                return attached.orElseThrow();
            }
            final Optional<SkillTickResult> sideStep =
                    startObservedSideStep(context, parameters, frame);
            if (sideStep.isPresent()) {
                return sideStep.orElseThrow();
            }
            if (lastChildFailureCode.startsWith("tower_up.")) {
                /* A pillar-side jump can be physically blocked by a newly
                 * exposed overhang.  Rotate through the normal first-person
                 * frontier probes before authorizing another tower attempt;
                 * otherwise the same opaque wall consumes every retry. */
                return prepareFrontierProbe(context, frame);
            }
            return startOneBlockTower(context, parameters, frame);
        }
        if (centerwardRecoveryNeeded) {
            return startOneBlockBridge(context, parameters, frame);
        }
        if (visibleCenterwardEndStoneWall(frame)) {
            return startOneBlockTower(context, parameters, frame);
        }
        if (obstruction.isPresent()) {
            return startVisibleBlockBreak(
                    context,
                    parameters,
                    frame,
                    obstruction.orElseThrow()
            );
        }
        return startOneBlockBridge(context, parameters, frame);
    }

    private SkillTickResult tickBridge(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (bridge == null || bridgeParameters == null) {
            return fail(NAME + ".bridge_binding_missing");
        }
        final SkillTickResult child = bridge.tick(
                context,
                bridgeParameters
        );
        if (child.status() == SkillTickResult.Status.COMPLETED) {
            visitedIngressCells.add(frame.feet());
            if (bridgeRequiresCenterProgress
                    && !bridgeMadeCenterProgress(frame)) {
                bridge = null;
                bridgeParameters = null;
                bridgeStartRadius = Double.NaN;
                bridgeStartFeet = null;
                bridgeExpectedFeet = null;
                bridgeRequiresCenterProgress = false;
                return recoverChildFailure(
                        context,
                        parameters,
                        frame,
                        "bridge_to.no_center_progress"
                );
            }
            bridge = null;
            bridgeParameters = null;
            bridgeStartRadius = Double.NaN;
            bridgeStartFeet = null;
            bridgeExpectedFeet = null;
            bridgeRequiresCenterProgress = false;
            bridgeSteps++;
            childFailures = 0;
            failedLandfallSupports.clear();
            phase = Phase.SCANNING;
            requiredFreshRevision = frame.observationRevision();
            nextScanTick = context.gameTick()
                    + MINIMUM_SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        if (child.status() == SkillTickResult.Status.FAILED) {
            bridge = null;
            bridgeParameters = null;
            bridgeStartRadius = Double.NaN;
            bridgeStartFeet = null;
            bridgeExpectedFeet = null;
            bridgeRequiresCenterProgress = false;
            final String code = child.failure()
                    .map(SkillFailure::code)
                    .orElse("bridge");
            if ("bridge_to.destination_column_unverified".equals(code)) {
                final Optional<SkillTickResult> attached =
                        startObservedAttachedStep(
                                context,
                                parameters,
                                frame
                        );
                if (attached.isPresent()) {
                    return attached.orElseThrow();
                }
                final Optional<SkillTickResult> detour =
                        startObservedSideStep(
                                context,
                                parameters,
                                frame
                        );
                if (detour.isPresent()) {
                    return detour.orElseThrow();
                }
                return prepareFrontierProbe(context, frame);
            }
            return recoverChildFailure(
                    context,
                    parameters,
                    frame,
                    code
            );
        }
        return SkillTickResult.running(
                child.madeProgress() || fresh,
                child.safeCheckpoint()
        );
    }

    private SkillTickResult tickTravel(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (travel == null || travelParameters == null) {
            return fail(NAME + ".travel_binding_missing");
        }
        final SkillTickResult child = travel.tick(
                context,
                travelParameters
        );
        if (child.status() == SkillTickResult.Status.COMPLETED) {
            travel = null;
            travelParameters = null;
            visitedIngressCells.clear();
            visitedIngressCells.add(frame.feet());
            childFailures = 0;
            failedLandfallSupports.clear();
            phase = Phase.VERIFYING_CURRENT_SUPPORT;
            requiredFreshRevision = frame.observationRevision();
            nextScanTick = context.gameTick()
                    + MINIMUM_SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        if (child.status() == SkillTickResult.Status.FAILED) {
            travel = null;
            travelParameters = null;
            if (candidateSupport != null) {
                failedLandfallSupports.add(candidateSupport);
                if (failedLandfallSupports.size() > 32) {
                    final GridPos oldest = failedLandfallSupports.iterator()
                            .next();
                    failedLandfallSupports.remove(oldest);
                }
            }
            candidateSupport = null;
            final String code = child.failure()
                    .map(SkillFailure::code)
                    .orElse("travel");
            return recoverChildFailure(
                    context,
                    parameters,
                    frame,
                    code
            );
        }
        return SkillTickResult.running(
                child.madeProgress() || fresh,
                child.safeCheckpoint()
        );
    }

    private SkillTickResult tickTower(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (tower == null || towerParameters == null) {
            return fail(NAME + ".tower_binding_missing");
        }
        final SkillTickResult child = tower.tick(
                context,
                towerParameters
        );
        if (child.status() == SkillTickResult.Status.COMPLETED) {
            tower = null;
            towerParameters = null;
            visitedIngressCells.clear();
            visitedIngressCells.add(frame.feet());
            towerSteps++;
            childFailures = 0;
            failedLandfallSupports.clear();
            phase = Phase.SCANNING;
            requiredFreshRevision = frame.observationRevision();
            nextScanTick = context.gameTick()
                    + MINIMUM_SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        if (child.status() == SkillTickResult.Status.FAILED) {
            tower = null;
            towerParameters = null;
            final String code = child.failure()
                    .map(SkillFailure::code)
                    .orElse("tower");
            return recoverChildFailure(
                    context,
                    parameters,
                    frame,
                    code
            );
        }
        return SkillTickResult.running(
                child.madeProgress() || fresh,
                child.safeCheckpoint()
        );
    }

    private SkillTickResult tickBlockBreak(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (blockBreak == null || blockBreakParameters == null) {
            return fail(NAME + ".block_break_binding_missing");
        }
        final SkillTickResult child = blockBreak.tick(
                context,
                blockBreakParameters
        );
        if (child.status() == SkillTickResult.Status.COMPLETED) {
            blockBreak = null;
            blockBreakParameters = null;
            visitedIngressCells.clear();
            visitedIngressCells.add(frame.feet());
            minedBlocks++;
            childFailures = 0;
            failedLandfallSupports.clear();
            phase = Phase.SCANNING;
            requiredFreshRevision = frame.observationRevision();
            nextScanTick = context.gameTick()
                    + MINIMUM_SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        if (child.status() == SkillTickResult.Status.FAILED) {
            blockBreak = null;
            blockBreakParameters = null;
            final String code = child.failure()
                    .map(SkillFailure::code)
                    .orElse("block_break");
            return recoverChildFailure(
                    context,
                    parameters,
                    frame,
                    code
            );
        }
        return SkillTickResult.running(
                child.madeProgress() || fresh,
                child.safeCheckpoint()
        );
    }

    private SkillTickResult tickBlockAlignment(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (interactionActuator == null
                || interactionFrames == null
                || pendingBreakBlock == null) {
            return fail(NAME + ".block_alignment_binding_missing");
        }
        /* Never mine the authoritative support voxel under the body.  A
         * centre-crosshair sample can legitimately hit its top face while a
         * queued turn is settling, but breaking it would be a self-inflicted
         * fall and vanilla correctly rejects the transaction. */
        if (grid(pendingBreakBlock).equals(frame.feet().below())) {
            pendingBreakBlock = null;
            blockAlignmentStartedTick = -1;
            blockAlignmentReadyRevision = -1;
            return recoverChildFailure(
                    context,
                    parameters,
                    frame,
                    "support_mining_guard"
            );
        }
        if (context.gameTick() - blockAlignmentStartedTick
                >= BLOCK_ALIGNMENT_TIMEOUT_TICKS) {
            pendingBreakBlock = null;
            blockAlignmentStartedTick = -1;
            blockAlignmentReadyRevision = -1;
            return recoverChildFailure(
                    context,
                    parameters,
                    frame,
                    "block_alignment"
            );
        }
        Optional<VisibleBlockFace> visibleTarget =
                frame.visibleBlockFaces().stream()
                        .filter(face -> END_STONE.equals(
                                face.blockTypeId()
                        ))
                        .filter(face -> face.block().equals(
                                pendingBreakBlock
                        ))
                        .min(Comparator.comparingDouble(
                                VisibleBlockFace::distance
                        ));
        /* The semantic fan and the interaction sampler are both fair, but
         * their nearest hit can differ while a queued turn is still being
         * applied.  If the live centre crosshair now proves another End-stone
         * face in this same semantic frame, bind that exact observed block
         * instead of repeatedly aiming at a stale neighbour.  No hidden block
         * state is consulted and BreakBlock still performs its own retained
         * observation/crosshair validation. */
        final Optional<VisibleBlockFace> liveCrosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> END_STONE.equals(face.blockTypeId()))
                        .filter(face -> frame.visibleBlockFaces().stream()
                                .anyMatch(observed -> observed.block().equals(
                                        face.block()
                                ) && END_STONE.equals(
                                        observed.blockTypeId()
                                )))
                        .filter(face -> !grid(face.block()).equals(
                                frame.feet().below()
                        ));
        if (liveCrosshair.isPresent()
                && (visibleTarget.isEmpty()
                || !liveCrosshair.orElseThrow().block().equals(
                        pendingBreakBlock
                ))) {
            final VisibleBlockFace rebound = liveCrosshair.orElseThrow();
            pendingBreakBlock = rebound.block();
            lastBreakTarget = String.format(
                    Locale.ROOT,
                    "%d,%d,%d/%s/%s",
                    rebound.block().x(),
                    rebound.block().y(),
                    rebound.block().z(),
                    rebound.blockTypeId(),
                    rebound.face()
            );
            blockAlignmentReadyRevision = -1;
            visibleTarget = Optional.of(rebound);
        }
        if (visibleTarget.isEmpty()) {
            if (fresh
                    && frame.observationRevision()
                        > requiredFreshRevision) {
                pendingBreakBlock = null;
                blockAlignmentStartedTick = -1;
                blockAlignmentReadyRevision = -1;
                return recoverChildFailure(
                        context,
                        parameters,
                        frame,
                        "block_alignment_target_lost"
                );
            }
            return SkillTickResult.running(false, true);
        }
        final VisibleBlockFace aim = visibleTarget.orElseThrow();
        if (!fresh
                || frame.observationRevision()
                    <= requiredFreshRevision) {
            if (!actuator.move(MovementIntent.STOPPED).accepted()
                    || !actuator.look(lookAt(
                            frame.eyePosition(),
                            aim.hitPosition()
                    )).accepted()) {
                return fail(NAME + ".block_alignment_rejected");
            }
            return SkillTickResult.running(true, true);
        }
        final Optional<dev.mcai.companion.skills.interaction
                .InteractionSkillFrame> interactionFrame =
                interactionFrames.current().filter(candidate ->
                        candidate.observationRevision()
                                == frame.observationRevision()
                );
        if (interactionFrame.isEmpty()) {
            if (!actuator.move(MovementIntent.STOPPED).accepted()
                    || !actuator.look(lookAt(
                            frame.eyePosition(),
                            aim.hitPosition()
                    )).accepted()) {
                return fail(NAME + ".block_alignment_rejected");
            }
            return SkillTickResult.running(true, true);
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> END_STONE.equals(
                                face.blockTypeId()
                        ))
                        .filter(face -> face.block().equals(
                                pendingBreakBlock
                        ));
        if (crosshair.isEmpty()) {
            if (!actuator.move(MovementIntent.STOPPED).accepted()
                    || !actuator.look(lookAt(
                            frame.eyePosition(),
                            aim.hitPosition()
                    )).accepted()) {
                return fail(NAME + ".block_alignment_rejected");
            }
            return SkillTickResult.running(true, true);
        }
        final VisibleBlockFace crosshairFace = crosshair.orElseThrow();
        final Optional<VisibleBlockFace> executable =
                interactionFrame.orElseThrow()
                        .visibleBlockFaces().stream()
                        .filter(face -> END_STONE.equals(
                                face.blockTypeId()
                        ))
                        .filter(face -> face.block().equals(
                                pendingBreakBlock
                        ))
                        .findFirst();
        if (executable.isEmpty()) {
            if (!actuator.move(MovementIntent.STOPPED).accepted()
                    || !actuator.look(lookAt(
                            frame.eyePosition(),
                            aim.hitPosition()
                    )).accepted()) {
                return fail(NAME + ".block_alignment_rejected");
            }
            return SkillTickResult.running(true, true);
        }
        if (blockAlignmentReadyRevision < 0) {
            /* The look intent is applied by the vanilla server player after
             * this tick.  Require one newer first-person frame before
             * beginning mining; otherwise a mathematically matching semantic
             * face can still be rejected by the real crosshair actuator as
             * TARGET_OCCLUDED. */
            blockAlignmentReadyRevision = frame.observationRevision();
            if (!actuator.move(MovementIntent.STOPPED).accepted()
                    || !actuator.look(lookAt(
                            frame.eyePosition(),
                            executable.orElseThrow().hitPosition()
                    )).accepted()) {
                return fail(NAME + ".block_alignment_rejected");
            }
            return SkillTickResult.running(true, true);
        }
        if (!fresh
                || frame.observationRevision()
                    <= blockAlignmentReadyRevision) {
            return SkillTickResult.running(false, true);
        }
        pendingBreakBlock = null;
        blockAlignmentStartedTick = -1;
        blockAlignmentReadyRevision = -1;
        return beginAlignedBlockBreak(
                context,
                parameters,
                frame,
                /* Use the tick-local crosshair hit point for the vanilla
                 * actuator.  The interaction frame proves block/face
                 * identity; its semantic fan hit can be a few tenths away
                 * from the exact ray clip and is rejected as occluded by the
                 * real ServerPlayer reach check. */
                crosshairFace
        );
    }

    private SkillTickResult tickStableGroundRecovery(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (frame.onGround() && !frame.inWater()) {
            stableGroundRecoveryStartedTick = -1;
            phase = Phase.SCANNING;
            requiredFreshRevision = frame.observationRevision();
            nextScanTick = context.gameTick()
                    + MINIMUM_SCAN_INTERVAL_TICKS;
            childFailures = 0;
            quiesce();
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - stableGroundRecoveryStartedTick
                >= STABLE_GROUND_RECOVERY_TIMEOUT_TICKS) {
            return fail(NAME + ".stable_ground_recovery_exhausted");
        }
        quiesce();
        return SkillTickResult.running(false, false);
    }

    private SkillTickResult recoverChildFailure(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame,
            final String child
    ) {
        childFailures++;
        lastChildFailureCode = child;
        if (childFailures > parameters.maximumChildFailures()) {
            return fail(NAME + "." + child + "_retry_exhausted");
        }
        phase = Phase.SCANNING;
        requiredFreshRevision = frame.observationRevision();
        nextScanTick = context.gameTick() + MINIMUM_SCAN_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult orientForFrontierProbe(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double deltaX = EndArenaTopology.CENTER_X
                - frame.position().x();
        final double deltaZ = EndArenaTopology.CENTER_Z
                - frame.position().z();
        final double length = Math.hypot(deltaX, deltaZ);
        if (!Double.isFinite(length) || length <= 1.0E-9) {
            return SkillTickResult.running(true, true);
        }
        final double forwardX = deltaX / length;
        final double forwardZ = deltaZ / length;
        final int probePattern = frontierProbeIndex & 7;
        final double sideSign = (probePattern & 1) == 0
                ? -1.0
                : 1.0;
        /*
         * A horizontal-only glance is not enough at a natural End pillar:
         * the fair mapper can see the wall and the void while still having
         * no proof that the adjacent feet cell carries the player.  Alternate
         * a low, floor-seeking ray with the normal eye-level ray.  Both are
         * ordinary first-person looks and the destination remains unknown
         * until a newer semantic frame proves clearance and support.
         */
        /* The old floor ray still aimed at the body-height horizon.  At the
         * foot of a natural obsidian pillar that ray clips the upper pillar
         * face and never publishes the adjacent bridge/support cell.  Keep
         * the ordinary eye-level sweeps, but dedicate the first four probes
         * to a lower, one-block-neighbour ray so a real player can inspect
         * the side of their current bridge before deciding to place or step.
         */
        final boolean lowGroundProbe = probePattern < 4;
        final double targetY = lowGroundProbe
                ? frame.position().y() - 0.65
                : frame.eyePosition().y() - 0.20;
        final double directionX;
        final double directionZ;
        if (probePattern == 0 || probePattern == 1) {
            directionX = forwardX;
            directionZ = forwardZ;
        } else if (probePattern == 2 || probePattern == 3) {
            directionX = -forwardZ * sideSign;
            directionZ = forwardX * sideSign;
        } else if (probePattern == 4 || probePattern == 5) {
            directionX = forwardX - forwardZ * sideSign;
            directionZ = forwardZ + forwardX * sideSign;
        } else {
            directionX = forwardX + forwardZ * sideSign;
            directionZ = forwardZ - forwardX * sideSign;
        }
        final double directionLength = Math.hypot(directionX, directionZ);
        final double normalX = directionX / directionLength;
        final double normalZ = directionZ / directionLength;
        final double distance = lowGroundProbe ? 1.10 : 2.0;
        final PerceptionVec3 target = new PerceptionVec3(
                frame.eyePosition().x() + normalX * distance,
                targetY,
                frame.eyePosition().z() + normalZ * distance
        );
        if (!actuator.move(MovementIntent.STOPPED).accepted()
                || !actuator.look(lookAt(
                        frame.eyePosition(),
                        target
                )).accepted()) {
            return fail(NAME + ".frontier_probe_rejected");
        }
        frontierProbeRevision = frame.observationRevision();
        requiredFreshRevision = frame.observationRevision();
        nextScanTick = context.gameTick() + MINIMUM_SCAN_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult prepareFrontierProbe(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        frontierProbeIndex++;
        frontierProbePending = true;
        phase = Phase.SCANNING;
        return orientForFrontierProbe(context, frame);
    }

    private SkillTickResult startOneBlockBridge(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame
    ) {
        if (bridgeSteps >= parameters.maximumBridgeBlocks()) {
            return fail(NAME + ".bridge_budget_exhausted");
        }
        final PerceptionVec3 target = bridgeTargetTowardCenter(
                frame.position()
        );
        if (target.equals(frame.position())) {
            return fail(NAME + ".center_heading_unavailable");
        }
        final BridgeToParameters childParameters =
                new BridgeToParameters(
                        DimensionRef.END,
                        target.x(),
                        frame.position().y(),
                        target.z(),
                        BRIDGE_ARRIVAL_RADIUS,
                        1
                );
        final BridgeToSkill child = new BridgeToSkill(
                expectedPlayerId,
                actuator,
                frames,
                materials
        );
        final Optional<SkillFailure> rejected = child.preconditions(
                context,
                childParameters
        );
        if (rejected.isPresent()) {
            if ("bridge_to.destination_column_unverified".equals(
                    rejected.orElseThrow().code()
            )) {
                final Optional<SkillTickResult> attached =
                        startObservedAttachedStep(
                                context,
                                parameters,
                                frame
                        );
                if (attached.isPresent()) {
                    return attached.orElseThrow();
                }
                final Optional<SkillTickResult> detour =
                        startObservedSideStep(
                                context,
                                parameters,
                                frame
                        );
                if (detour.isPresent()) {
                    return detour.orElseThrow();
                }
                return prepareFrontierProbe(context, frame);
            }
            childFailures++;
            if (childFailures > parameters.maximumChildFailures()) {
                return fail(NAME + ".bridge_retry_exhausted");
            }
            requiredFreshRevision = frame.observationRevision();
            nextScanTick = context.gameTick()
                    + MINIMUM_SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(false, true);
        }
        child.start(context, childParameters);
        bridge = child;
        bridgeParameters = childParameters;
        bridgeStartRadius = EndArenaTopology.horizontalRadius(
                frame.position()
        );
        bridgeStartFeet = frame.feet();
        bridgeExpectedFeet = new GridPos(
                (int) Math.floor(target.x()),
                frame.feet().y(),
                (int) Math.floor(target.z())
        );
        bridgeRequiresCenterProgress = true;
        phase = Phase.BRIDGING_ONE_STEP;
        return tickBridge(context, parameters, frame, true);
    }

    private boolean bridgeMadeCenterProgress(final CoreSkillFrame frame) {
        if (!bridgeRequiresCenterProgress || bridgeStartFeet == null
                || bridgeExpectedFeet == null
                || !Double.isFinite(bridgeStartRadius)) {
            return true;
        }
        final double currentRadius = EndArenaTopology.horizontalRadius(
                frame.position()
        );
        return frame.feet().equals(bridgeExpectedFeet)
                || currentRadius
                    <= bridgeStartRadius - MINIMUM_CENTER_PROGRESS;
    }

    /**
     * Routes around an observed solid frontier when the centerward adjacent
     * column is not yet a fair bridge destination.  The candidate set is
     * limited to the four horizontal neighbours in the current navigation
     * snapshot; no heightmap, chunk read, or synthesized landing point is
     * allowed.  A successful side step re-enters the ordinary scan phase and
     * lets a later fresh frame choose the centerward route again.
     */
    private Optional<SkillTickResult> startObservedSideStep(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame
    ) {
        final GridPos current = frame.feet();
        final long revision = frame.navigation().revision();
        final Optional<GridPos> candidate = List.of(
                current.offset(0, 0, -1),
                current.offset(0, 0, 1),
                current.offset(-1, 0, 0),
                current.offset(1, 0, 0)
        ).stream()
                .filter(step -> !step.equals(current))
                /* A tower rejection invalidates the previous eye-line, not
                 * the neighbouring cell itself.  Permit one freshly observed
                 * side-step retry in that case even if an earlier probe
                 * touched the same cell; otherwise a natural spawn-platform
                 * wall can mark all safe lateral cells visited before the
                 * controller has a usable overhead view.  Successful bridge
                 * progress still resets the child-failure budget and the
                 * route-level scan/bridge limits remain bounded. */
                .filter(step -> !visitedIngressCells.contains(step)
                        || lastChildFailureCode.startsWith("tower_up."))
                .filter(step -> radiusOf(step)
                        <= EndArenaTopology.horizontalRadius(frame.position())
                                + 2.5)
                .filter(step -> frame.navigation().voxelAt(step)
                        .filter(voxel -> navigationTraversalClearance(
                                voxel,
                                revision
                        )).isPresent())
                .filter(step -> frame.navigation().voxelAt(step.above())
                        .filter(voxel -> navigationTraversalClearance(
                                voxel,
                                revision
                        )).isPresent())
                .filter(step -> frame.navigation().voxelAt(step.below())
                        .filter(voxel -> navigationStandingSupport(
                                voxel,
                                revision
                        )).isPresent())
                .min(Comparator.comparingDouble(
                        EndIslandIngressSkill::radiusOf
                ));
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        final GridPos step = candidate.orElseThrow();
        final PerceptionVec3 target = sideStepTarget(frame.position(), step);
        final BridgeToParameters childParameters =
                new BridgeToParameters(
                        DimensionRef.END,
                        target.x(),
                        frame.position().y(),
                        target.z(),
                        BRIDGE_ARRIVAL_RADIUS,
                        1
                );
        final BridgeToSkill child = new BridgeToSkill(
                expectedPlayerId,
                actuator,
                frames,
                materials
        );
        final Optional<SkillFailure> rejected = child.preconditions(
                context,
                childParameters
        );
        if (rejected.isPresent()) {
            return Optional.empty();
        }
        child.start(context, childParameters);
        bridge = child;
        bridgeParameters = childParameters;
        phase = Phase.BRIDGING_ONE_STEP;
        return Optional.of(
                tickBridge(context, parameters, frame, true)
        );
    }

    static PerceptionVec3 sideStepTarget(
            final PerceptionVec3 position,
            final GridPos destination
    ) {
        final double centerX = destination.x() + 0.5;
        final double centerZ = destination.z() + 0.5;
        final double deltaX = centerX - position.x();
        final double deltaZ = centerZ - position.z();
        /* The step is cardinal.  Offsetting both coordinates pushed an
         * already-aligned body toward the far corner of the destination
         * cell, so BridgeTo's one-cell child could walk into the cell and
         * still remain outside its arrival radius forever.  Keep the edge
         * offset only on the movement axis.  On the orthogonal axis retain
         * the body's coordinate when it is already inside the destination
         * cell; otherwise enter that cell through its far edge.  This is a
         * geometric target only--BridgeTo still requires fresh support,
         * clearance and vanilla collision before crossing.
         */
        final boolean xStep = Math.abs(deltaX) >= Math.abs(deltaZ);
        final double targetX = xStep
                ? centerX + Math.copySign(
                        SIDE_STEP_EDGE_OFFSET,
                        Math.abs(deltaX) <= 1.0E-9 ? 1.0 : deltaX
                )
                : coordinateInsideDestinationCell(
                        position.x(), destination.x()
                );
        final double targetZ = xStep
                ? coordinateInsideDestinationCell(
                        position.z(), destination.z()
                )
                : centerZ + Math.copySign(
                        SIDE_STEP_EDGE_OFFSET,
                        Math.abs(deltaZ) <= 1.0E-9 ? 1.0 : deltaZ
                );
        return new PerceptionVec3(targetX, position.y(), targetZ);
    }

    private static double coordinateInsideDestinationCell(
            final double coordinate,
            final int cell
    ) {
        final double minimum = cell + 0.06;
        final double maximum = cell + 0.94;
        if (coordinate >= minimum && coordinate <= maximum) {
            return coordinate;
        }
        final double center = cell + 0.5;
        return center + Math.copySign(
                SIDE_STEP_EDGE_OFFSET,
                center - coordinate
        );
    }

    /**
     * Places one owned block directly into a currently unknown neighbour cell
     * when a solid attachment face for that exact cell is visible in the
     * current first-person sample.  This is the legal escape used beside a
     * natural End pillar: it never assumes the void below the cell is safe,
     * and BridgeToSkill will not cross until a newer frame proves the placed
     * block carries the body.
     */
    private Optional<SkillTickResult> startObservedAttachedStep(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame
    ) {
        final GridPos current = frame.feet();
        final List<GridPos> neighbours = List.of(
                current.offset(0, 0, -1),
                current.offset(0, 0, 1),
                current.offset(-1, 0, 0),
                current.offset(1, 0, 0)
        );
        final Optional<GridPos> destination = frame.visibleBlockFaces()
                .stream()
                .filter(face -> !"minecraft:air".equals(
                        face.blockTypeId()
                ))
                .filter(face -> face.collisionAffordance()
                        != CollisionAffordance.EMPTY)
                .map(face -> {
                    final BlockFace parsed;
                    try {
                        parsed = blockFace(face.face());
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    }
                    final GridPos clicked = grid(face.block());
                    final GridPos attached = adjacent(clicked, parsed);
                    /* A side face of the block beneath the body is the
                     * normal bridge attachment point.  Its adjacent voxel is
                     * a support cell one level below the destination feet;
                     * comparing it directly with the feet grid silently
                     * discarded every legal side extension around a pillar.
                     * Lift only that observed support cell and keep all other
                     * coordinates unchanged. */
                    return attached.y() == current.y() - 1
                            ? attached.above()
                            : attached;
                })
                .filter(Objects::nonNull)
                .filter(neighbours::contains)
                .filter(step -> radiusOf(step)
                        <= EndArenaTopology.horizontalRadius(frame.position())
                                + 1.5)
                .filter(step -> !visitedIngressCells.contains(step))
                /* An attached extension may target an unknown frontier, but
                 * it must never be selected when the newest fair fan already
                 * proves that exact destination is occupied.  Otherwise a
                 * spawn-platform wall is repeatedly handed to BridgeTo and
                 * consumes the route budget without placing or moving. */
                .filter(step -> frame.visibleBlockFaces().stream()
                        .noneMatch(face -> grid(face.block()).equals(step)
                                && face.collisionAffordance()
                                    != CollisionAffordance.EMPTY))
                .filter(step -> frame.navigation().voxelAt(step)
                        .map(voxel -> voxel.kind().isPassable())
                        .orElse(true))
                .filter(step -> frame.navigation().voxelAt(step.above())
                        .map(voxel -> voxel.kind().isPassable())
                        .orElse(true))
                .min(Comparator.comparingDouble(
                        EndIslandIngressSkill::radiusOf
                ));
        if (destination.isEmpty()) {
            return Optional.empty();
        }
        final GridPos step = destination.orElseThrow();
        final BridgeToParameters childParameters =
                new BridgeToParameters(
                        DimensionRef.END,
                        step.x() + 0.5,
                        frame.position().y(),
                        step.z() + 0.5,
                        BRIDGE_ARRIVAL_RADIUS,
                        1,
                        true
                );
        final BridgeToSkill child = new BridgeToSkill(
                expectedPlayerId,
                actuator,
                frames,
                materials
        );
        final Optional<SkillFailure> rejected = child.preconditions(
                context,
                childParameters
        );
        if (rejected.isPresent()) {
            return Optional.empty();
        }
        child.start(context, childParameters);
        bridge = child;
        bridgeParameters = childParameters;
        phase = Phase.BRIDGING_ONE_STEP;
        return Optional.of(
                tickBridge(context, parameters, frame, true)
        );
    }

    private SkillTickResult startLandfallTravel(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame,
            final GridPos support
    ) {
        final GridPos feet = support.above();
        final TravelToParameters childParameters =
                new TravelToParameters(
                        DimensionRef.END,
                        feet.x() + 0.5,
                        feet.y(),
                        feet.z() + 0.5,
                        LANDFALL_ARRIVAL_RADIUS
                );
        final TravelToSkill child = new TravelToSkill(
                expectedPlayerId,
                actuator,
                frames,
                sessionGeneration
        );
        final Optional<SkillFailure> rejected = child.preconditions(
                context,
                childParameters
        );
        if (rejected.isPresent()) {
            childFailures++;
            if (childFailures > parameters.maximumChildFailures()) {
                return fail(NAME + ".travel_retry_exhausted");
            }
            requiredFreshRevision = frame.observationRevision();
            nextScanTick = context.gameTick()
                    + MINIMUM_SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(false, true);
        }
        child.start(context, childParameters);
        travel = child;
        travelParameters = childParameters;
        candidateSupport = support;
        landfallAttempts++;
        phase = Phase.TRAVELLING_TO_OBSERVED_END_STONE;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult startOneBlockTower(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame
    ) {
        if (towerSteps >= parameters.maximumTowerBlocks()) {
            return fail(NAME + ".tower_budget_exhausted");
        }
        final TowerUpParameters childParameters =
                new TowerUpParameters(
                        DimensionRef.END,
                        frame.position().y() + 1.0,
                        0.35,
                        1
                );
        final TowerUpSkill child = new TowerUpSkill(
                expectedPlayerId,
                actuator,
                frames,
                materials
        );
        final Optional<SkillFailure> rejected = child.preconditions(
                context,
                childParameters
        );
        if (rejected.isPresent()) {
            lastChildFailureCode = rejected.orElseThrow().code();
            childFailures++;
            if (childFailures > parameters.maximumChildFailures()) {
                return fail(NAME + ".tower_retry_exhausted");
            }
            requiredFreshRevision = frame.observationRevision();
            nextScanTick = context.gameTick()
                    + MINIMUM_SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(false, true);
        }
        child.start(context, childParameters);
        tower = child;
        towerParameters = childParameters;
        phase = Phase.TOWERING_FOR_LANDFALL;
        return tickTower(context, parameters, frame, true);
    }

    private SkillTickResult startVisibleBlockBreak(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame,
            final VisibleBlockFace face
    ) {
        if (interactionActuator == null || interactionFrames == null) {
            return fail(NAME + ".mining_unavailable");
        }
        if (grid(face.block()).equals(frame.feet().below())) {
            /* A top-face hit on the block carrying the body is an observation
             * of support, not authorization to remove that support.  Let the
             * next scan select a side/overhead obstruction instead. */
            pendingBreakBlock = null;
            phase = Phase.SCANNING;
            requiredFreshRevision = frame.observationRevision();
            nextScanTick = context.gameTick() + MINIMUM_SCAN_INTERVAL_TICKS;
            lastChildFailureCode = "support_mining_guard";
            return SkillTickResult.running(true, true);
        }
        if (minedBlocks >= parameters.maximumMinedBlocks()) {
            return fail(NAME + ".mining_budget_exhausted");
        }
        final Optional<String> pickaxe = ownedPickaxe(frame);
        if (pickaxe.isEmpty()) {
            return fail(NAME + ".pickaxe_required");
        }
        pendingBreakBlock = face.block();
        lastBreakTarget = String.format(
                Locale.ROOT,
                "%d,%d,%d/%s/%s",
                face.block().x(),
                face.block().y(),
                face.block().z(),
                face.blockTypeId(),
                face.face()
        );
        blockAlignmentStartedTick = context.gameTick();
        blockAlignmentReadyRevision = -1;
        requiredFreshRevision = frame.observationRevision();
        phase = Phase.ALIGNING_VISIBLE_BLOCK_BREAK;
        if (!actuator.move(MovementIntent.STOPPED).accepted()
                || !actuator.look(lookAt(
                        frame.eyePosition(),
                        face.hitPosition()
                )).accepted()) {
            return fail(NAME + ".block_alignment_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult beginAlignedBlockBreak(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame,
            final VisibleBlockFace face
    ) {
        final Optional<String> pickaxe = ownedPickaxe(frame);
        if (pickaxe.isEmpty()) {
            return fail(NAME + ".pickaxe_required");
        }
        final ActionOutcome equipped = interactionActuator.equipMainHand(
                pickaxe.orElseThrow()
        );
        if (!equipped.accepted()) {
            return fail(NAME + ".pickaxe_equip_rejected");
        }
        final BlockCoordinate block = face.block();
        final BreakBlockParameters childParameters =
                new BreakBlockParameters(
                        DimensionRef.END,
                        new ObservedBlockTarget(
                                frame.observationRevision(),
                                block.x(),
                                block.y(),
                                block.z(),
                                blockFace(face.face())
                        )
                );
        final BreakBlockSkill child = new BreakBlockSkill(
                expectedPlayerId,
                interactionActuator,
                interactionFrames,
                InteractionSkillPolicy.defaults()
        );
        final Optional<SkillFailure> rejected = child.preconditions(
                context,
                childParameters
        );
        if (rejected.isPresent()) {
            return recoverChildFailure(
                    context,
                    parameters,
                    frame,
                    "block_break_precondition"
            );
        }
        child.start(context, childParameters);
        blockBreak = child;
        blockBreakParameters = childParameters;
        phase = Phase.MINING_VISIBLE_END_STONE;
        return tickBlockBreak(context, parameters, frame, true);
    }

    private SkillTickResult orientForFreshObservation(
            final SkillContext context,
            final EndIslandIngressParameters parameters,
            final CoreSkillFrame frame
    ) {
        if (scanTurns >= parameters.maximumScanTurns()) {
            return fail(NAME + ".scan_budget_exhausted");
        }
        if (!actuator.move(MovementIntent.STOPPED).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        if (!actuator.look(towardCenterFloor(frame)).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        /* Set one observation deadline, then let ticks advance toward it.
         * Moving the deadline forward on every early tick would keep the
         * controller orienting forever and never admit the fresh frame. */
        if (nextScanTick <= context.gameTick()) {
            nextScanTick = context.gameTick()
                    + MINIMUM_SCAN_INTERVAL_TICKS;
        }
        return SkillTickResult.running(true, true);
    }


    private SkillTickResult orientForFreshSkyObservation(
            final CoreSkillFrame frame
    ) {
        if (!actuator.move(MovementIntent.STOPPED).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        final PerceptionVec3 direction = frame.lookDirection();
        final float yaw = (float) Math.toDegrees(Math.atan2(
                -direction.x(),
                direction.z()
        ));
        if (!actuator.look(new LookIntent(yaw, -89.0F)).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult orientForCurrentSupport(
            final CoreSkillFrame frame
    ) {
        if (!actuator.move(MovementIntent.STOPPED).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        final GridPos support = frame.feet().below();
        final PerceptionVec3 target = new PerceptionVec3(
                support.x() + 0.5,
                support.y() + 1.0,
                support.z() + 0.5
        );
        if (!actuator.look(lookAt(frame.eyePosition(), target)).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private static double completionRadius(
            final EndIslandIngressParameters parameters,
            final double override
    ) {
        return Double.isFinite(override)
                ? override
                : parameters.arenaReadyRadius();
    }

    private double completionRadius(
            final EndIslandIngressParameters parameters
    ) {
        return completionRadius(parameters, completionRadiusOverride);
    }

    private static boolean visibleCurrentEndStoneSupport(
            final CoreSkillFrame frame
    ) {
        final GridPos support = frame.feet().below();
        return frame.visibleBlockFaces().stream().anyMatch(face ->
                END_STONE.equals(face.blockTypeId())
                        && "up".equals(face.face())
                        && face.topSupportAffordance()
                            .safelySupportsStanding()
                        && grid(face.block()).equals(support)
        );
    }

    private static boolean currentSupportNeedsTopObservation(
            final CoreSkillFrame frame
    ) {
        return frame.navigation().voxelAt(frame.feet().below())
                .filter(voxel -> voxel.kind().supportsWeight())
                .filter(voxel -> !voxel.topSupportAffordance()
                        .safelySupportsStanding())
                .filter(voxel -> voxel.occupancyEvidence()
                        == dev.mcai.companion.navigation
                            .OccupancyEvidence.BODY_CONTACT)
                .isPresent();
    }

    private Optional<GridPos> selectLandfall(
            final CoreSkillFrame frame,
            final EndIslandIngressParameters parameters,
            final boolean hardcore
    ) {
        if (frame.navigation().revision()
                != frame.observationRevision()) {
            return Optional.empty();
        }
        final double currentRadius = EndArenaTopology.horizontalRadius(
                frame.position()
        );
        final java.util.List<GridPos> candidates =
                frame.visibleBlockFaces().stream()
                .filter(face -> END_STONE.equals(face.blockTypeId()))
                .filter(face -> "up".equals(face.face()))
                .filter(face -> face.topSupportAffordance()
                        .safelySupportsStanding())
                .filter(face -> face.distance()
                        <= parameters.maximumVisibleLandfallDistance())
                .map(VisibleBlockFace::block)
                .map(EndIslandIngressSkill::grid)
                .filter(support -> horizontalDistance(
                        frame.position(),
                        support
                ) <= parameters.maximumVisibleLandfallDistance())
                .filter(support -> !support.equals(frame.feet().below()))
                .filter(support -> !failedLandfallSupports.contains(support))
                .filter(support -> radiusOf(support)
                        <= currentRadius - Math.max(
                                MINIMUM_CENTER_PROGRESS,
                                LANDFALL_ARRIVAL_RADIUS
                        ))
                .filter(support -> safeFreshDestination(frame, support))
                .distinct()
                .sorted(Comparator.comparingDouble(
                        EndIslandIngressSkill::radiusOf
                ))
                .toList();
        final LocalPlannerOptions options = new LocalPlannerOptions(
                hardcore
                        ? NavigationRiskProfile.HARDCORE
                        : NavigationRiskProfile.NORMAL,
                LANDFALL_PLANNING_BUDGET,
                hardcore ? 1 : 3,
                true,
                false
        );
        final Set<GridPos> candidateFeet = candidates.stream()
                .map(GridPos::above)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (candidateFeet.isEmpty()) {
            return Optional.empty();
        }
        final LocalRoute route = landfallPlanner.planToAny(
                frame.navigation(),
                frame.feet(),
                candidateFeet,
                options,
                true
        );
        return route.found()
                ? Optional.of(route.reached().below())
                : Optional.empty();
    }


    private static boolean visibleCenterwardEndStoneWall(
            final CoreSkillFrame frame
    ) {
        final PerceptionVec3 target =
                EndArenaTopology.oneCardinalStepTowardCenter(
                        frame.position()
                );
        if (target.equals(frame.position())) {
            return false;
        }
        final GridPos targetFeet = new GridPos(
                (int) Math.floor(target.x()),
                frame.feet().y(),
                (int) Math.floor(target.z())
        );
        return frame.visibleBlockFaces().stream().anyMatch(face -> {
            final GridPos block = grid(face.block());
            return END_STONE.equals(face.blockTypeId())
                    && (block.equals(targetFeet)
                        || block.equals(targetFeet.above()));
        });
    }

    /**
     * BridgeToSkill accepts a bounded arrival radius.  Aim half a block
     * beyond the next cell center so that its legal radius cannot complete
     * while the body is still standing in the previous cell.  The target
     * remains the adjacent observed column; this only fixes the local
     * stopping geometry and does not reveal an unobserved block.
     */
    private static PerceptionVec3 bridgeTargetTowardCenter(
            final PerceptionVec3 position
    ) {
        final PerceptionVec3 step =
                EndArenaTopology.oneCardinalStepTowardCenter(position);
        if (step.equals(position)) {
            return step;
        }
        final double deltaX = EndArenaTopology.CENTER_X - position.x();
        final double deltaZ = EndArenaTopology.CENTER_Z - position.z();
        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            return new PerceptionVec3(
                    step.x() + Math.copySign(0.5, deltaX),
                    step.y(),
                    step.z()
            );
        }
        return new PerceptionVec3(
                step.x(),
                step.y(),
                step.z() + Math.copySign(0.5, deltaZ)
        );
    }

    private static Optional<VisibleBlockFace> visibleCenterwardBodyWall(
            final CoreSkillFrame frame
    ) {
        final PerceptionVec3 target =
                EndArenaTopology.oneCardinalStepTowardCenter(
                        frame.position()
                );
        if (target.equals(frame.position())) {
            return Optional.empty();
        }
        final GridPos targetFeet = new GridPos(
                (int) Math.floor(target.x()),
                frame.feet().y(),
                (int) Math.floor(target.z())
        );
        return frame.visibleBlockFaces().stream()
                .filter(face -> END_STONE.equals(face.blockTypeId()))
                .filter(face -> {
                    final GridPos block = grid(face.block());
                    return block.equals(targetFeet)
                            || block.equals(targetFeet.above());
                })
                .min(Comparator.comparingInt(face ->
                        grid(face.block()).y()
                ));
    }

    private Optional<VisibleBlockFace> visibleLateralIngressWall(
            final CoreSkillFrame frame
    ) {
        final GridPos feet = frame.feet();
        final double centerwardX = EndArenaTopology.CENTER_X
                - frame.position().x();
        final double centerwardZ = EndArenaTopology.CENTER_Z
                - frame.position().z();
        final boolean centerwardAlongX = Math.abs(centerwardX)
                >= Math.abs(centerwardZ);
        return frame.visibleBlockFaces().stream()
                .filter(face -> END_STONE.equals(face.blockTypeId()))
                .filter(face -> {
                    final GridPos block = grid(face.block());
                    final int dx = block.x() - feet.x();
                    final int dz = block.z() - feet.z();
                    if (Math.abs(dx) + Math.abs(dz) != 1
                            || block.y() < feet.y()
                            || block.y() > feet.y() + 3) {
                        return false;
                    }
                    if (block.y() == feet.y()
                            && ("up".equals(face.face())
                                || "down".equals(face.face()))) {
                        return false;
                    }
                    /* If the route points along X, only Z neighbours are
                     * lateral; the inverse applies when it points along Z.
                     * A tie admits either axis because both are equally fair
                     * first-person alternatives. */
                    return centerwardAlongX ? dz != 0 : dx != 0;
                })
                .filter(face -> {
                    final GridPos block = grid(face.block());
                    final Optional<ObservedVoxel> destination = frame
                            .navigation()
                            .voxelAt(block);
                    final Optional<ObservedVoxel> head = frame.navigation()
                            .voxelAt(block.above());
                    final Optional<ObservedVoxel> support = frame.navigation()
                            .voxelAt(block.below());
                    return destination
                            .filter(voxel -> navigationStandingSupport(
                                    voxel,
                                    frame.observationRevision()
                            )).isPresent()
                        && head.filter(voxel -> navigationTraversalClearance(
                                voxel,
                                frame.observationRevision()
                        )).isPresent()
                        && support.filter(voxel -> navigationStandingSupport(
                                voxel,
                                frame.observationRevision()
                        )).isPresent();
                })
                .min(Comparator.comparingDouble(VisibleBlockFace::distance));
    }

    private static Optional<VisibleBlockFace> visibleLateralBreakableWall(
            final CoreSkillFrame frame
    ) {
        final GridPos feet = frame.feet();
        final double centerwardX = EndArenaTopology.CENTER_X
                - frame.position().x();
        final double centerwardZ = EndArenaTopology.CENTER_Z
                - frame.position().z();
        final boolean centerwardAlongX = Math.abs(centerwardX)
                >= Math.abs(centerwardZ);
        return frame.visibleBlockFaces().stream()
                .filter(face -> END_STONE.equals(face.blockTypeId()))
                .filter(face -> {
                    final GridPos block = grid(face.block());
                    final int dx = block.x() - feet.x();
                    final int dz = block.z() - feet.z();
                    if (Math.abs(dx) + Math.abs(dz) != 1
                            || block.y() < feet.y()
                            || block.y() > feet.y() + 3) {
                        return false;
                    }
                    if (centerwardAlongX ? dz == 0 : dx == 0) {
                        return false;
                    }
                    final String faceName = face.face().toLowerCase(
                            Locale.ROOT
                    );
                    return !faceName.endsWith(":up")
                            && !faceName.equals("up")
                            && !faceName.endsWith(":down")
                            && !faceName.equals("down");
                })
                .min(Comparator.comparingDouble(VisibleBlockFace::distance));
    }

    /**
     * Selects one directly adjacent or current-column upper End-stone face
     * from the newest first-person fan while a fight is re-entering the
     * island.  Natural island skirts often expose a forward wall, a lateral
     * shoulder, and a one-block ceiling in alternating revisions; restricting
     * the fallback to only the perpendicular face left the body oscillating
     * between an uncleared face and a bridge target.  Every candidate remains
     * an observed vanilla block, and the re-entry caller still requires an
     * interaction actuator and a bounded completion-radius override.
     */
    private static Optional<VisibleBlockFace>
            visibleReentryExcavationTarget(final CoreSkillFrame frame) {
        final GridPos feet = frame.feet();
        return frame.visibleBlockFaces().stream()
                .filter(face -> END_STONE.equals(face.blockTypeId()))
                .filter(face -> {
                    final GridPos block = grid(face.block());
                    final int horizontalDistance =
                            Math.abs(block.x() - feet.x())
                                    + Math.abs(block.z() - feet.z());
                    if (horizontalDistance > 2
                            || block.y() < feet.y() - 1
                            || block.y() > feet.y() + 3) {
                        return false;
                    }
                    /* A lower shoulder can be the only visible side of a
                     * natural island wall after a knockback.  It is safe to
                     * open that observed block when it is adjacent, but the
                     * exact support voxel under the body remains protected. */
                    if (block.equals(feet.below())) {
                        return false;
                    }
                    final String faceName = face.face().toLowerCase(
                            Locale.ROOT
                    );
                    return !faceName.endsWith(":up")
                            && !faceName.equals("up")
                            && !faceName.endsWith(":down")
                            && !faceName.equals("down");
                })
                .min(Comparator.comparingDouble(VisibleBlockFace::distance));
    }

    private boolean navigationTraversalClearance(
            final ObservedVoxel voxel,
            final long snapshotRevision
    ) {
        if (!historicalNavigationAllowed()) {
            return NavigationEvidence.hasFreshTraversalClearance(
                    voxel,
                    snapshotRevision
            );
        }
        final long age = snapshotRevision - voxel.observationRevision();
        return age >= 0L
                && age <= 20L
                && NavigationEvidence.hasTraversalClearance(voxel);
    }

    private boolean navigationStandingSupport(
            final ObservedVoxel voxel,
            final long snapshotRevision
    ) {
        if (!historicalNavigationAllowed()) {
            return NavigationEvidence.isFreshStandingSupport(
                    voxel,
                    snapshotRevision
            );
        }
        final long age = snapshotRevision - voxel.observationRevision();
        return age >= 0L
                && age <= 20L
                && voxel.kind().supportsWeight()
                && voxel.topSupportAffordance().safelySupportsStanding()
                && (voxel.occupancyEvidence()
                            == dev.mcai.companion.navigation
                                .OccupancyEvidence.SURFACE_HIT
                    || voxel.occupancyEvidence()
                            == dev.mcai.companion.navigation
                                .OccupancyEvidence.BODY_CONTACT);
    }

    private boolean historicalNavigationAllowed() {
        return Double.isFinite(completionRadiusOverride);
    }

    private static boolean visibleCenterwardObsidianWall(
            final CoreSkillFrame frame
    ) {
        final PerceptionVec3 target = EndArenaTopology
                .oneCardinalStepTowardCenter(frame.position());
        final GridPos targetFeet = new GridPos(
                (int) Math.floor(target.x()),
                frame.feet().y(),
                (int) Math.floor(target.z())
        );
        return frame.visibleBlockFaces().stream()
                .filter(face -> "minecraft:obsidian".equals(
                        face.blockTypeId()
                ))
                .map(face -> grid(face.block()))
                .anyMatch(block -> block.equals(targetFeet)
                        || block.equals(targetFeet.above()));
    }

    private static Optional<VisibleBlockFace> visibleIngressObstruction(
            final CoreSkillFrame frame
    ) {
        final GridPos feet = frame.feet();
        final PerceptionVec3 target =
                EndArenaTopology.oneCardinalStepTowardCenter(
                        frame.position()
                );
        final GridPos targetFeet = new GridPos(
                (int) Math.floor(target.x()),
                feet.y(),
                (int) Math.floor(target.z())
        );
        return frame.visibleBlockFaces().stream()
                .filter(face -> END_STONE.equals(face.blockTypeId()))
                .filter(face -> {
                    final GridPos block = grid(face.block());
                    final boolean overhead = block.x() == feet.x()
                            && block.z() == feet.z()
                            && block.y() >= feet.y() + 2
                            && block.y() <= feet.y() + 3;
                    final boolean forward = block.equals(targetFeet)
                            || block.equals(targetFeet.above());
                    return overhead || forward;
                })
                .min(Comparator.comparingInt(face -> {
                    final GridPos block = grid(face.block());
                    return block.x() == feet.x()
                            && block.z() == feet.z() ? 0 : 1;
                }));
    }

    private static boolean isCurrentColumnOverhead(
            final CoreSkillFrame frame,
            final VisibleBlockFace face
    ) {
        final GridPos feet = frame.feet();
        final GridPos block = grid(face.block());
        return block.x() == feet.x()
                && block.z() == feet.z()
                && block.y() >= feet.y() + 2
                && block.y() <= feet.y() + 3;
    }

    private static Optional<String> ownedPickaxe(
            final CoreSkillFrame frame
    ) {
        for (String item : PICKAXE_PRIORITY) {
            if ((item.equals(frame.mainHand().itemId())
                    && frame.mainHand().count() > 0)
                    || frame.inventory().stream().anyMatch(entry ->
                            item.equals(entry.itemId())
                                && entry.count() > 0
                    )) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    private static BlockFace blockFace(final String face) {
        return switch (face) {
            case "down" -> BlockFace.DOWN;
            case "up" -> BlockFace.UP;
            case "north" -> BlockFace.NORTH;
            case "south" -> BlockFace.SOUTH;
            case "west" -> BlockFace.WEST;
            case "east" -> BlockFace.EAST;
            default -> throw new IllegalArgumentException(
                    "Unknown observed block face"
            );
        };
    }

    private static GridPos adjacent(
            final GridPos position,
            final BlockFace face
    ) {
        return switch (face) {
            case DOWN -> position.offset(0, -1, 0);
            case UP -> position.offset(0, 1, 0);
            case NORTH -> position.offset(0, 0, -1);
            case SOUTH -> position.offset(0, 0, 1);
            case WEST -> position.offset(-1, 0, 0);
            case EAST -> position.offset(1, 0, 0);
        };
    }

    private static boolean safeFreshDestination(
            final CoreSkillFrame frame,
            final GridPos support
    ) {
        final long revision = frame.navigation().revision();
        final Optional<ObservedVoxel> supportVoxel =
                frame.navigation().voxelAt(support);
        final Optional<ObservedVoxel> feet =
                frame.navigation().voxelAt(support.above());
        final Optional<ObservedVoxel> head =
                frame.navigation().voxelAt(support.above(2));
        return supportVoxel.filter(voxel ->
                        NavigationEvidence.isFreshStandingSupport(
                                voxel,
                                revision
                        )).isPresent()
                && feet.filter(voxel ->
                        NavigationEvidence.hasFreshTraversalClearance(
                                voxel,
                                revision
                        )).isPresent()
                && head.filter(voxel ->
                        NavigationEvidence.hasFreshTraversalClearance(
                                voxel,
                                revision
                        )).isPresent();
    }

    private boolean verifiedCurrentEndStoneSupport(
            final CoreSkillFrame frame,
            final EndIslandIngressParameters parameters
    ) {
        final double completionRadius = Double.isFinite(
                completionRadiusOverride
        ) ? completionRadiusOverride : parameters.arenaReadyRadius();
        return EndIslandRallyEvidence.supportsCurrentStandingCell(
                frame,
                completionRadius
        );
    }

    /**
     * Re-entry after dragon knockback may finish on a bridge cell that the
     * companion itself placed and then physically occupied.  Requiring a
     * natural End-stone top face again would discard that fair, player-owned
     * route and make the child rebuild until timeout.  This relaxed predicate
     * is intentionally available only with the fight's finite completion
     * override; the initial End ingress continues to use the strict natural
     * End-stone evidence above.  Body contact is direct vanilla physics
     * evidence, while both feet and head clearance must still be fresh in the
     * same navigation revision.
     */
    private boolean verifiedCurrentReentrySupport(
            final CoreSkillFrame frame,
            final EndIslandIngressParameters parameters
    ) {
        if (!Double.isFinite(completionRadiusOverride)
                || !frame.onGround()
                || frame.inWater()
                || EndArenaTopology.horizontalRadius(frame.position())
                    > completionRadius(parameters)
                || frame.navigation().revision()
                    != frame.observationRevision()) {
            return false;
        }
        final GridPos support = frame.feet().below();
        final double centerDistance = Math.hypot(
                frame.position().x() - (support.x() + 0.5),
                frame.position().z() - (support.z() + 0.5)
        );
        if (centerDistance > 0.72) {
            return false;
        }
        final long revision = frame.observationRevision();
        return frame.navigation().voxelAt(support).filter(voxel ->
                    NavigationEvidence.supportsCurrentBody(
                            voxel,
                            revision
                    )
                ).isPresent()
                && frame.navigation().voxelAt(support.above()).filter(
                    voxel -> NavigationEvidence.hasFreshTraversalClearance(
                            voxel,
                            revision
                    )
                ).isPresent()
                && frame.navigation().voxelAt(support.above(2)).filter(
                    voxel -> NavigationEvidence.hasFreshTraversalClearance(
                            voxel,
                            revision
                    )
                ).isPresent();
    }

    private Optional<SkillFailure> validateBody(
            final CoreSkillFrame frame
    ) {
        if (!expectedPlayerId.equals(frame.playerId())) {
            return failure(NAME + ".player_mismatch");
        }
        return Optional.empty();
    }

    private long currentSessionGeneration() {
        try {
            return sessionGeneration.getAsLong();
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    private void cancelChildren(final SkillContext context) {
        if (bridge != null && bridgeParameters != null) {
            bridge.cancel(context, bridgeParameters);
        }
        if (travel != null && travelParameters != null) {
            travel.cancel(context, travelParameters);
        }
        if (tower != null && towerParameters != null) {
            tower.cancel(context, towerParameters);
        }
        if (blockBreak != null && blockBreakParameters != null) {
            blockBreak.cancel(context, blockBreakParameters);
        }
        bridge = null;
        bridgeParameters = null;
        travel = null;
        travelParameters = null;
        tower = null;
        towerParameters = null;
        blockBreak = null;
        blockBreakParameters = null;
        pendingBreakBlock = null;
        blockAlignmentStartedTick = -1;
        blockAlignmentReadyRevision = -1;
        stableGroundRecoveryStartedTick = -1;
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        quiesce();
        failure = Objects.requireNonNull(reason, "reason");
        phase = Phase.FAILED;
        bridge = null;
        bridgeParameters = null;
        travel = null;
        travelParameters = null;
        tower = null;
        towerParameters = null;
        blockBreak = null;
        blockBreakParameters = null;
        return SkillTickResult.failed(failure);
    }

    private void quiesce() {
        actuator.releaseUse();
        actuator.stop();
        if (interactionActuator != null) {
            interactionActuator.abortMining();
        }
    }

    private static Optional<SkillFailure> failure(final String code) {
        return Optional.of(SkillFailure.of(code));
    }

    private static LookIntent towardCenterFloor(
            final CoreSkillFrame frame
    ) {
        final double deltaX = EndArenaTopology.CENTER_X
                - frame.position().x();
        final double deltaZ = EndArenaTopology.CENTER_Z
                - frame.position().z();
        final double horizontal = Math.hypot(deltaX, deltaZ);
        final double scale = horizontal <= 1.0E-9
                ? 0.0
                : Math.min(4.0, horizontal) / horizontal;
        final PerceptionVec3 target = new PerceptionVec3(
                frame.position().x() + deltaX * scale,
                frame.position().y() - 0.20,
                frame.position().z() + deltaZ * scale
        );
        final PerceptionVec3 look = target.subtract(frame.eyePosition());
        final float yaw = (float) Math.toDegrees(Math.atan2(
                -look.x(),
                look.z()
        ));
        final float pitch = (float) Math.toDegrees(Math.atan2(
                -look.y(),
                Math.hypot(look.x(), look.z())
        ));
        return new LookIntent(yaw, pitch);
    }

    private static LookIntent lookAt(
            final PerceptionVec3 eye,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta = target.subtract(eye);
        final float yaw = (float) Math.toDegrees(Math.atan2(
                -delta.x(),
                delta.z()
        ));
        final float pitch = (float) Math.toDegrees(Math.atan2(
                -delta.y(),
                Math.hypot(delta.x(), delta.z())
        ));
        return new LookIntent(yaw, pitch);
    }

    private static double horizontalDistance(
            final PerceptionVec3 position,
            final GridPos support
    ) {
        return Math.hypot(
                position.x() - (support.x() + 0.5),
                position.z() - (support.z() + 0.5)
        );
    }

    private static double radiusOf(final GridPos position) {
        return Math.hypot(position.x() + 0.5, position.z() + 0.5);
    }

    private static GridPos grid(final BlockCoordinate block) {
        return new GridPos(block.x(), block.y(), block.z());
    }

    enum Phase {
        IDLE,
        SCANNING,
        BRIDGING_ONE_STEP,
        TOWERING_FOR_LANDFALL,
        ALIGNING_VISIBLE_BLOCK_BREAK,
        MINING_VISIBLE_END_STONE,
        TRAVELLING_TO_OBSERVED_END_STONE,
        RECOVERING_STABLE_GROUND,
        VERIFYING_CURRENT_SUPPORT,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return this == SCANNING
                    || this == BRIDGING_ONE_STEP
                    || this == TOWERING_FOR_LANDFALL
                    || this == ALIGNING_VISIBLE_BLOCK_BREAK
                    || this == MINING_VISIBLE_END_STONE
                    || this == TRAVELLING_TO_OBSERVED_END_STONE
                    || this == RECOVERING_STABLE_GROUND
                    || this == VERIFYING_CURRENT_SUPPORT;
        }
    }
}
