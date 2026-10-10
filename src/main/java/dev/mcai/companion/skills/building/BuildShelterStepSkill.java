package dev.mcai.companion.skills.building;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.bridging.BridgeMaterialResult;
import dev.mcai.companion.skills.bridging.BridgeToParameters;
import dev.mcai.companion.skills.bridging.BridgeToSkill;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.MoveToParameters;
import dev.mcai.companion.skills.core.MoveToSkill;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.EquipmentTarget;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.survey.SurveyResultBuffer;
import dev.mcai.companion.skills.survey.SurveySurroundingsParameters;
import dev.mcai.companion.skills.survey.SurveySurroundingsSkill;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Executes a bounded locally generated shelter work batch through the
 * ordinary first-person use-on-block path.
 *
 * <p>One invocation continues while the next step uses the already equipped
 * material and remains reachable in a fresh fair observation. It yields when
 * equipment or position must change. This keeps model delay off the 20 TPS
 * construction path while every placement is still independently observed
 * and checkpointed.</p>
 */
public final class BuildShelterStepSkill
        implements Skill<BuildShelterStepParameters> {
    public static final int MAXIMUM_OBSERVATION_AGE_TICKS = 20;
    public static final int CONFIRMATION_TIMEOUT_TICKS = 60;
    /*
     * Construction traversal reasons about exact GridPos stands. A radius of
     * 0.5 or more lets centre-targeted movement finish on the neighbouring
     * side of the cell boundary, so the parent can repeatedly select the same
     * stand without ever entering it.
     */
    static final double CONSTRUCTION_STAND_ARRIVAL_RADIUS = 0.35;
    /*
     * A centre-targeted doorway exit can settle only a few centimetres past
     * the cell boundary and drift back into the open doorway while the
     * panoramic survey rotates. Bias the target further outside while
     * keeping its grid goal on the same verified apron cell.
     */
    static final double EXTERIOR_DOORWAY_OUTWARD_BIAS = 0.25;
    /*
     * The ordinary survival block-interaction attribute is 4.5 blocks.
     * Keep a small numerical margin because remembered face centres are
     * aiming hints, while the final exact hit is still checked by vanilla.
     */
    public static final double MAXIMUM_BUILD_REACH = 4.45;
    private static final int AIM_TIMEOUT_TICKS = 40;
    private static final int MAXIMUM_AIM_REPOSITION_ATTEMPTS = 3;
    private static final int RELOCATION_ARRIVAL_OBSERVATION_TIMEOUT_TICKS = 20;
    private static final int ROOF_APRON_REFRESH_TIMEOUT_TICKS = 40;
    private static final double ROOF_APRON_REFRESH_ALIGNMENT_DEGREES = 3.0;
    private static final int AIM_REPOSITION_STALL_TICKS = 60;
    private static final int AIM_REPOSITION_MAXIMUM_TICKS = 160;
    private static final double AIM_REPOSITION_PROGRESS_EPSILON = 0.10;
    private static final double AIM_ALIGNMENT_DEGREES = 2.0;
    private static final double JUMP_AIM_ALIGNMENT_DEGREES = 4.0;
    private static final double TOP_FACE_EYE_CLEARANCE = 0.05;
    private static final int MAXIMUM_JUMP_AIM_ATTEMPTS = 4;
    private static final int MAXIMUM_PLACEMENT_RECOVERY_ATTEMPTS = 3;
    private static final int MAXIMUM_PLACEMENT_REPAIR_SURVEYS = 2;
    private static final int PLACEMENT_OBSTRUCTION_PUSH_TICKS = 80;
    private static final double PLACEMENT_PUSH_ALIGNMENT_DEGREES = 8.0;
    private static final double PLACEMENT_PUSH_DISTANCE = 2.0;
    private static final int MAXIMUM_INITIAL_SITE_RELOCATIONS = 4;
    private static final int MAXIMUM_ROOF_RETURN_SLOPE = 1;
    private static final double PLAYER_COLLISION_HALF_WIDTH = 0.30;
    private static final double PLAYER_COLLISION_HEIGHT = 1.80;
    private static final String NAME = "build_shelter_step";

    private final UUID expectedPlayerId;
    private final InteractionSkillActuator actuator;
    private final ShelterFrameSource frames;
    private final DynamicShelterPlanner planner;
    private final BiConsumer<Long, ShelterPlan> shelterCompleted;
    private final OwnedStructureBlockIndex protectedStructures;
    private final Optional<InventorySkillActuator> inventoryActuator;
    private final Optional<CoreSkillActuator> coreActuator;
    private final Optional<CoreSkillFrameSource> coreFrames;
    private final Optional<SurveySurroundingsSkill> surveySkill;
    private final Optional<MoveToSkill> relocationSkill;
    private final Optional<BridgeToSkill> roofEdgeBridgeSkill;

    private ShelterPlan activePlan;
    private long planGoalRevision = -1;
    private long planSessionGeneration = -1;
    private final BitSet confirmed = new BitSet();
    private final BitSet deferredAimSteps = new BitSet();
    private final BitSet attemptedRoofEdgeBridges = new BitSet();
    private final BitSet exhaustedExteriorRoofSteps = new BitSet();
    private int roofInteriorFallbackPriority = -1;
    private final Set<GridPos> avoidedPlacementTargets =
            new HashSet<>();

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private ShelterBuildStep executingStep;
    private BlockInteractionTarget interactionTarget;
    private EquipItemParameters pendingEquipment;
    private SurveySurroundingsParameters activeSurveyParameters;
    private MoveToParameters activeRelocationParameters;
    private long relocationArrivalWaitStartedAtGameTick = -1;
    private MoveToParameters activeRepositionParameters;
    /*
     * A compound owns its private MoveTo instance.  A completed/cancelled
     * child can nevertheless be observed once more after a safe checkpoint
     * boundary (for example when a roof return is re-entered in the same
     * server tick).  Re-arm that child once with the exact same target; a
     * second inactive-state report remains a real bounded failure.
     */
    private int inactiveMoveRecoveryAttempts;
    private GridPos activeRoofApronSurveyStand;
    private GridPos activeRoofApronRefreshTarget;
    private long roofApronRefreshStartedAtGameTick = -1;
    private long roofApronRefreshAlignedRevision = -1;
    private BridgeToParameters activeRoofEdgeBridgeParameters;
    private boolean relocationPerformed;
    private int activePlanTraversalRelocationAttempts;
    private boolean activePlanTraversalDestinationWasExplored;
    private boolean returningInsideForRoof;
    private int aimRepositionAttempts;
    private boolean aimRecoverySurveyPerformed;
    private long roofExteriorSurveyRevision = -1;
    private boolean pendingRoofDoorExit;
    private final Set<GridPos> attemptedAimVantages =
            new HashSet<>();
    /*
     * Observation staging cells are not failed aiming vantages. Keeping
     * their history separate prevents a valid side-face aim at the very cell
     * where the first-person apron survey just completed from being skipped.
     */
    private final Set<GridPos> attemptedRoofObservationStands =
            new HashSet<>();
    private final Set<PlacementSupportIdentity> abandonedAimSupports =
            new HashSet<>();
    /*
     * A completed shelter door is a real interactive block, not a hole in the
     * navigation map.  If the body exits while the door is closed, the fair
     * centre ray can repeatedly hit that door before the roof support behind
     * it.  Remember the door cells we opened through the ordinary interaction
     * actuator so a recovery tick never toggles an already-open door closed.
     */
    private final Set<GridPos> openedOccludingDoors = new HashSet<>();
    private final BoundedRepositionProgress aimRepositionWatchdog =
            new BoundedRepositionProgress(
                    AIM_REPOSITION_STALL_TICKS,
                    AIM_REPOSITION_MAXIMUM_TICKS,
                    AIM_REPOSITION_PROGRESS_EPSILON
            );
    private long aimStartedAtGameTick = -1;
    private long aimStartedObservationRevision = -1;
    private AimProgress aimProgress = AimProgress.NOT_STARTED;
    private int jumpAimStepIndex = -1;
    private int jumpAimAttempts;
    private long boundSessionGeneration = -1;
    private long dispatchedObservationRevision = -1;
    private long dispatchedAtGameTick = -1;
    private String dispatchedMainHandItem = "";
    private int dispatchedMainHandCount = -1;
    private boolean dispatchedActionCompleted;
    private boolean dispatchedSupportWasSolid;
    private UUID placementObstructionEntityId;
    private PerceptionVec3 placementPushDestination;
    private List<GridPos> placementPushCorridor = List.of();
    private long placementRecoveryStartedAtGameTick = -1;
    private long placementRecoveryStartedRevision = -1;
    private int placementRecoveryAttempts;
    private int placementRepairSurveyAttempts;
    private int unconfirmedPlacementRetries;
    private long initialSiteSurveyGoalRevision = -1;
    private long initialSiteSurveySessionGeneration = -1;
    private long initialSiteSearchGoalRevision = -1;
    private long initialSiteSearchSessionGeneration = -1;
    private ShelterScale initialSiteSearchScale;
    private int initialSiteRelocationAttempts;
    private final Set<GridPos> rejectedInitialSiteCenters =
            new HashSet<>();
    /*
     * Retained while the same planned block remains unconfirmed. A bounded
     * survey retry must advance its exterior frontier instead of alternating
     * between the doorway and the first apron cell forever.
     */
    private final Set<GridPos> exploredActivePlanTraversalStands =
            new HashSet<>();
    private final Set<GridPos> rejectedActivePlanTraversalStands =
            new HashSet<>();
    /*
     * Ground cells physically occupied by this body while the same shelter
     * plan was active. Unlike a planned destination, these are first-person
     * proprioceptive facts. They survive individual placements so a later
     * roof return can reconnect a partially sampled semantic fan to the
     * doorway corridor the body just walked. The set is discarded whenever
     * the plan, goal, or body session is replaced.
     */
    private final Set<GridPos> bodyVerifiedActivePlanTransitStands =
            new HashSet<>();

    public BuildShelterStepSkill(
            UUID expectedPlayerId,
            InteractionSkillActuator actuator,
            ShelterFrameSource frames
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                null,
                new DynamicShelterPlanner(),
                (ignoredRevision, ignoredPlan) -> {
                }
        );
    }

    public BuildShelterStepSkill(
            UUID expectedPlayerId,
            InteractionSkillActuator actuator,
            ShelterFrameSource frames,
            DynamicShelterPlanner planner
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                null,
                planner,
                (ignoredRevision, ignoredPlan) -> {
                }
        );
    }

    public BuildShelterStepSkill(
            UUID expectedPlayerId,
            InteractionSkillActuator actuator,
            ShelterFrameSource frames,
            DynamicShelterPlanner planner,
            BiConsumer<Long, ShelterPlan> shelterCompleted
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                null,
                planner,
                shelterCompleted
        );
    }

    public BuildShelterStepSkill(
            UUID expectedPlayerId,
            InteractionSkillActuator actuator,
            ShelterFrameSource frames,
            InventorySkillActuator inventoryActuator,
            DynamicShelterPlanner planner,
            BiConsumer<Long, ShelterPlan> shelterCompleted
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                inventoryActuator,
                null,
                null,
                null,
                planner,
                shelterCompleted
        );
    }

    public BuildShelterStepSkill(
            UUID expectedPlayerId,
            InteractionSkillActuator actuator,
            ShelterFrameSource frames,
            InventorySkillActuator inventoryActuator,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            SurveyResultBuffer surveyResults,
            DynamicShelterPlanner planner,
            BiConsumer<Long, ShelterPlan> shelterCompleted
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                inventoryActuator,
                coreActuator,
                coreFrames,
                surveyResults,
                planner,
                shelterCompleted,
                new OwnedStructureBlockIndex()
        );
    }

    public BuildShelterStepSkill(
            UUID expectedPlayerId,
            InteractionSkillActuator actuator,
            ShelterFrameSource frames,
            InventorySkillActuator inventoryActuator,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            SurveyResultBuffer surveyResults,
            DynamicShelterPlanner planner,
            BiConsumer<Long, ShelterPlan> shelterCompleted,
            OwnedStructureBlockIndex protectedStructures
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.inventoryActuator = Optional.ofNullable(
                inventoryActuator
        );
        if ((coreActuator == null) != (coreFrames == null)
                || (coreActuator == null) != (surveyResults == null)) {
            throw new IllegalArgumentException(
                    "Shelter survey dependencies must be all present"
            );
        }
        this.coreActuator = Optional.ofNullable(coreActuator);
        this.coreFrames = Optional.ofNullable(coreFrames);
        this.surveySkill = coreActuator == null
                ? Optional.empty()
                : Optional.of(new SurveySurroundingsSkill(
                        expectedPlayerId,
                        coreActuator,
                        coreFrames,
                        surveyResults
                ));
        this.relocationSkill = coreActuator == null
                ? Optional.empty()
                : Optional.of(new MoveToSkill(
                        expectedPlayerId,
                        coreActuator,
                        coreFrames
                ));
        this.roofEdgeBridgeSkill = coreActuator == null
                ? Optional.empty()
                : Optional.of(new BridgeToSkill(
                        expectedPlayerId,
                        coreActuator,
                        coreFrames,
                        this::currentRoofMaterial
                ));
        this.planner = Objects.requireNonNull(planner, "planner");
        this.shelterCompleted = Objects.requireNonNull(
                shelterCompleted,
                "shelterCompleted"
        );
        this.protectedStructures = Objects.requireNonNull(
                protectedStructures,
                "protectedStructures"
        );
    }

    @Override
    public SkillParameterParser<BuildShelterStepParameters> parameters() {
        return BuildingSkillParameters::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            BuildShelterStepParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Preparation preparation = prepare(
                parameters,
                context.goalRevision()
        );
        if (preparation.failure().isPresent()
                || !preparation.surveyRequired()) {
            return preparation.failure();
        }
        final SurveySurroundingsParameters survey =
                surveyParameters(parameters);
        return surveySkill.orElseThrow().preconditions(
                context,
                survey
        );
    }

    @Override
    public void start(
            SkillContext context,
            BuildShelterStepParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        Preparation preparation = prepare(
                parameters,
                context.goalRevision()
        );
        if (preparation.failure().isPresent()) {
            throw new IllegalStateException(
                    "Shelter binding changed after precondition validation"
            );
        }
        activeRelocationParameters = null;
        relocationArrivalWaitStartedAtGameTick = -1;
        activeRepositionParameters = null;
        inactiveMoveRecoveryAttempts = 0;
        activeRoofApronSurveyStand = null;
        clearRoofApronRefresh();
        activeRoofEdgeBridgeParameters = null;
        aimRepositionWatchdog.clear();
        jumpAimStepIndex = -1;
        jumpAimAttempts = 0;
        relocationPerformed = false;
        activePlanTraversalRelocationAttempts = 0;
        activePlanTraversalDestinationWasExplored = false;
        rejectedActivePlanTraversalStands.clear();
        aimRecoverySurveyPerformed = false;
        clearPlacementObstructionRecovery();
        if (preparation.surveyRequired()) {
            activeSurveyParameters = surveyParameters(parameters);
            final SurveySurroundingsSkill survey =
                    surveySkill.orElseThrow();
            if (survey.preconditions(
                    context,
                    activeSurveyParameters
            ).isPresent()) {
                throw new IllegalStateException(
                        "Shelter survey binding changed after validation"
                );
            }
            survey.start(context, activeSurveyParameters);
            phase = Phase.SURVEYING;
            failure = null;
            pendingEquipment = null;
            executingStep = null;
            interactionTarget = null;
            abandonedAimSupports.clear();
            boundSessionGeneration = preparation.frame()
                    .orElseThrow()
                    .sessionGeneration();
            dispatchedObservationRevision = -1;
            dispatchedAtGameTick = -1;
            dispatchedActionCompleted = false;
            dispatchedSupportWasSolid = false;
            placementRecoveryAttempts = 0;
            unconfirmedPlacementRetries = 0;
            return;
        }
        commitPreparation(context, preparation);
    }

    private void commitPreparation(
            SkillContext context,
            Preparation preparation
    ) {
        ShelterFrame frame = preparation.frame().orElseThrow();
        final boolean replacingPlan = activePlan == null
                || planGoalRevision != context.goalRevision()
                || planSessionGeneration
                != frame.sessionGeneration();
        if (replacingPlan) {
            activePlan = preparation.plan().orElseThrow();
            planGoalRevision = context.goalRevision();
            planSessionGeneration = frame.sessionGeneration();
            protectedStructures.activateShelter(
                    context.goalRevision(),
                    activePlan
            );
            clearInitialSiteSearch();
            confirmed.clear();
            deferredAimSteps.clear();
            attemptedRoofEdgeBridges.clear();
            exhaustedExteriorRoofSteps.clear();
            roofInteriorFallbackPriority = -1;
            avoidedPlacementTargets.clear();
            roofExteriorSurveyRevision = -1;
            pendingRoofDoorExit = false;
            activeRoofApronSurveyStand = null;
            clearRoofApronRefresh();
            exploredActivePlanTraversalStands.clear();
            rejectedActivePlanTraversalStands.clear();
            bodyVerifiedActivePlanTransitStands.clear();
            activePlanTraversalDestinationWasExplored = false;
            returningInsideForRoof = false;
        }
        final ShelterBuildStep previousStep = executingStep;
        pendingEquipment = preparation.equipment().orElse(null);
        phase = preparation.complete()
                ? Phase.READY_COMPLETE
                : pendingEquipment == null
                        ? placementReadyPhase()
                        : Phase.EQUIPPING;
        failure = null;
        executingStep = preparation.step().orElse(null);
        interactionTarget = preparation.target().orElse(null);
        if (replacingPlan
                || previousStep == null && executingStep != null
                || previousStep != null && executingStep == null
                || previousStep != null && executingStep != null
                        && previousStep.index()
                                != executingStep.index()) {
            aimRepositionAttempts = 0;
            resetJumpAim(executingStep);
            attemptedAimVantages.clear();
            attemptedRoofObservationStands.clear();
            abandonedAimSupports.clear();
            aimRepositionWatchdog.clear();
            activeRoofApronSurveyStand = null;
            clearRoofApronRefresh();
            placementRecoveryAttempts = 0;
            unconfirmedPlacementRetries = 0;
            clearPlacementObstructionRecovery();
        }
        boundSessionGeneration = frame.sessionGeneration();
        dispatchedObservationRevision = -1;
        dispatchedAtGameTick = -1;
        dispatchedActionCompleted = false;
        dispatchedSupportWasSolid = false;
        if (phase == Phase.AIMING) {
            beginAim(context, frame);
        }
    }

    private SkillTickResult startRelocation(
            final SkillContext context,
            final BuildShelterStepParameters parameters,
            final ShelterFrame frame
    ) {
        if (relocationSkill.isEmpty()) {
            return fail("shelter.insufficient_observation");
        }
        final boolean traversingActivePlan =
                hasCurrentActivePlan(
                        context.goalRevision(),
                        frame.sessionGeneration()
                );
        if (traversingActivePlan
                && !activePlanTraversalRelocationAvailable(
                        activePlanTraversalRelocationAttempts,
                        Objects.requireNonNull(activePlan)
                )) {
            return fail(
                    "build_shelter_step."
                            + "active_plan_traversal_exhausted"
            );
        }
        if (traversingActivePlan) {
            exploredActivePlanTraversalStands.add(frame.feet());
        }
        final Optional<GridPos> destination;
        if (!traversingActivePlan) {
            destination = planner.relocationTarget(
                    frame,
                    parameters.scale(),
                    rejectedInitialSiteCenters
            );
        } else {
            final ShelterPlan plan =
                    Objects.requireNonNull(activePlan);
            final boolean roofPending = currentRole(
                    plan,
                    confirmed
            ).filter(role ->
                    role == ShelterStepRole.ROOF
            ).isPresent();
            Optional<GridPos> candidate = Optional.empty();
            if (returningInsideForRoof) {
                roofInteriorFallbackPriority =
                        roofInteriorTraversalFallbackPriority(
                                plan,
                                confirmed,
                                roofInteriorFallbackPriority
                        );
                if (isInteriorFloorPosition(
                        plan,
                        frame.feet()
                )) {
                    returningInsideForRoof = false;
                } else {
                    candidate =
                            roofInteriorReturnTraversalTarget(
                                    frame,
                                    plan,
                                    exploredActivePlanTraversalStands,
                                    rejectedActivePlanTraversalStands,
                                    bodyVerifiedActivePlanTransitStands
                            );
                }
            }
            if (!returningInsideForRoof) {
                candidate = activePlanTraversalTarget(
                        frame,
                        plan,
                        confirmed,
                        exploredActivePlanTraversalStands,
                        roofInteriorFallbackPriority >= 0
                                && roofPending
                                && isInteriorFloorPosition(
                                        plan,
                                        frame.feet()
                                )
                );
                if (candidate.isEmpty()
                        && roofPending
                        && !isInteriorFloorPosition(
                                plan,
                                frame.feet()
                        )) {
                    candidate =
                            roofInteriorReturnTraversalTarget(
                                    frame,
                                    plan,
                                    exploredActivePlanTraversalStands,
                                    rejectedActivePlanTraversalStands,
                                    bodyVerifiedActivePlanTransitStands
                            );
                    returningInsideForRoof =
                            candidate.isPresent();
                    if (returningInsideForRoof) {
                        roofInteriorFallbackPriority =
                                roofInteriorTraversalFallbackPriority(
                                        plan,
                                        confirmed,
                                        roofInteriorFallbackPriority
                                );
                        exploredActivePlanTraversalStands.clear();
                        exploredActivePlanTraversalStands.add(
                                frame.feet()
                        );
                    }
                }
            }
            destination = candidate;
        }
        if (destination.isEmpty()) {
            if (traversingActivePlan) {
                logActivePlanTraversalDeadEnd(
                        frame,
                        Objects.requireNonNull(activePlan),
                        confirmed,
                        exploredActivePlanTraversalStands,
                        returningInsideForRoof
                );
            }
            return fail(
                    traversingActivePlan
                            ? "build_shelter_step."
                                + "no_observed_traversal_stand"
                            : "shelter.no_observed_relocation_site"
            );
        }
        final GridPos stand = destination.orElseThrow();
        final boolean knownActivePlanTransit =
                traversingActivePlan
                        && (exploredActivePlanTraversalStands
                                .contains(stand)
                            || bodyVerifiedActivePlanTransitStands
                                .contains(stand));
        activeRelocationParameters = new MoveToParameters(
                parameters.dimension(),
                stand.x() + 0.5,
                stand.y(),
                stand.z() + 0.5,
                CONSTRUCTION_STAND_ARRIVAL_RADIUS
        );
        final MoveToSkill movement = relocationSkill.orElseThrow();
        final Optional<SkillFailure> rejected =
                movement.preconditions(
                        context,
                        activeRelocationParameters
                );
        if (rejected.isPresent()) {
            activeRelocationParameters = null;
            activePlanTraversalDestinationWasExplored = false;
            return fail(rejected.orElseThrow());
        }
        movement.start(context, activeRelocationParameters);
        inactiveMoveRecoveryAttempts = 0;
        activePlanTraversalDestinationWasExplored =
                knownActivePlanTransit;
        relocationArrivalWaitStartedAtGameTick = -1;
        relocationPerformed = true;
        if (!traversingActivePlan) {
            initialSiteRelocationAttempts++;
            rejectedInitialSiteCenters.add(stand);
            MinecraftAiCompanion.LOGGER.info(
                    "Walking through observed terrain to inspect another "
                            + "shelter site attempt={}/{} from={} stand={} "
                            + "rejectedCenters={}",
                    initialSiteRelocationAttempts,
                    MAXIMUM_INITIAL_SITE_RELOCATIONS,
                    frame.feet(),
                    stand,
                    rejectedInitialSiteCenters.size()
            );
        }
        phase = Phase.RELOCATING;
        if (traversingActivePlan) {
            activePlanTraversalRelocationAttempts++;
            exploredActivePlanTraversalStands.add(stand);
            MinecraftAiCompanion.LOGGER.info(
                    "Walking through observed terrain to continue active "
                            + "shelter plan planOrigin={} confirmed={} "
                            + "from={} stand={} attempt={}/{} "
                            + "exploredStands={} returningInside={}",
                    activePlan.origin(),
                    confirmed.cardinality(),
                    frame.feet(),
                    stand,
                    activePlanTraversalRelocationAttempts,
                    maximumActivePlanTraversalRelocations(
                            activePlan
                    ),
                    exploredActivePlanTraversalStands.size(),
                    returningInsideForRoof
            );
        }
        return SkillTickResult.running(true, true);
    }

    private static void logActivePlanTraversalDeadEnd(
            final ShelterFrame frame,
            final ShelterPlan plan,
            final BitSet planConfirmed,
            final Set<GridPos> exploredStands,
            final boolean returningInside
    ) {
        final List<GridPos> observedApron =
                frame.navigation().observedVoxels()
                        .values()
                        .stream()
                        .map(ObservedVoxel::position)
                        .distinct()
                        .filter(candidate ->
                                isExteriorRoofApronPosition(
                                        plan,
                                        candidate
                                ))
                        .sorted(Comparator
                                .comparingInt(GridPos::x)
                                .thenComparingInt(GridPos::z))
                        .toList();
        final List<GridPos> safeApron =
                observedApron.stream()
                        .filter(candidate ->
                                isObservedUnobstructedStand(
                                        frame,
                                        candidate
                                ))
                        .toList();
        final List<GridPos> cardinalSafeNeighbours =
                safeApron.stream()
                        .filter(candidate ->
                                cardinallyAdjacent(
                                        frame.feet(),
                                        candidate
                                ))
                        .toList();
        final GridPos exteriorDoor =
                exteriorDoorwayStand(plan);
        MinecraftAiCompanion.LOGGER.warn(
                "Shelter traversal dead end planOrigin={} feet={} "
                        + "confirmed={} role={} returningInside={} "
                        + "navRevision={} observedApron={} safeApron={} "
                        + "cardinalSafeNeighbours={} exteriorDoor={} "
                        + "exteriorDoorSafe={} exploredStands={}",
                plan.origin(),
                frame.feet(),
                planConfirmed.cardinality(),
                currentRole(plan, planConfirmed).orElse(null),
                returningInside,
                frame.navigation().revision(),
                observedApron,
                safeApron,
                cardinalSafeNeighbours,
                exteriorDoor,
                isObservedUnobstructedStand(
                        frame,
                        exteriorDoor
                ),
                exploredStands
        );
    }

    private SkillTickResult tickRelocation(
            final SkillContext context,
            final BuildShelterStepParameters parameters
    ) {
        final MoveToParameters movementParameters =
                Objects.requireNonNull(activeRelocationParameters);
        final SkillTickResult movement =
                relocationSkill.orElseThrow().tick(
                        context,
                        movementParameters
                );
        if (movement.status() == SkillTickResult.Status.FAILED) {
            final SkillFailure reason =
                    movement.failure().orElseThrow();
            if ("move_to.invalid_state".equals(reason.code())
                    && inactiveMoveRecoveryAttempts++ < 1) {
                MinecraftAiCompanion.LOGGER.warn(
                        "Re-arming inactive shelter relocation child "
                                + "target={} attempt={}",
                        movementParameters.gridGoal(),
                        inactiveMoveRecoveryAttempts
                );
                relocationSkill.orElseThrow().start(
                        context,
                        movementParameters
                );
                return SkillTickResult.running(true, true);
            }
            inactiveMoveRecoveryAttempts = 0;
            activeRelocationParameters = null;
            relocationArrivalWaitStartedAtGameTick = -1;
            activePlanTraversalDestinationWasExplored = false;
            if (returningInsideForRoof
                    && activePlan != null
                    && recoverableRoofReturnMoveFailure(
                            reason.code()
                    )) {
                rejectedActivePlanTraversalStands.add(
                        movementParameters.gridGoal()
                );
                final FrameValidation recoveryFrame =
                        validateFrame(
                                parameters,
                                boundSessionGeneration,
                                false
                        );
                if (recoveryFrame.failure().isPresent()) {
                    return fail(
                            recoveryFrame.failure().orElseThrow()
                    );
                }
                final ShelterFrame current =
                        recoveryFrame.frame().orElseThrow();
                MinecraftAiCompanion.LOGGER.warn(
                        "Rejecting stalled shelter return destination "
                                + "planOrigin={} stepIndex={} feet={} "
                                + "rejected={} reason={} attempt={}/{}",
                        activePlan.origin(),
                        executingStep == null
                                ? -1
                                : executingStep.index(),
                        current.feet(),
                        movementParameters.gridGoal(),
                        reason.code(),
                        activePlanTraversalRelocationAttempts,
                        maximumActivePlanTraversalRelocations(
                                activePlan
                        )
                );
                return startRelocation(
                        context,
                        parameters,
                        current
                );
            }
            return fail(reason);
        }
        if (movement.status() == SkillTickResult.Status.RUNNING) {
            return movement;
        }

        final FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                false
        );
        if (validation.failure().isPresent()) {
            activeRelocationParameters = null;
            relocationArrivalWaitStartedAtGameTick = -1;
            return fail(validation.failure().orElseThrow());
        }
        final ShelterFrame arrived = validation.frame().orElseThrow();
        final GridPos expectedArrival =
                movementParameters.gridGoal();
        if (!expectedArrival.equals(arrived.feet())) {
            if (relocationArrivalWaitStartedAtGameTick < 0) {
                relocationArrivalWaitStartedAtGameTick =
                        context.gameTick();
            }
            if (context.gameTick()
                    - relocationArrivalWaitStartedAtGameTick
                    < RELOCATION_ARRIVAL_OBSERVATION_TIMEOUT_TICKS) {
                /*
                 * MoveTo is authoritative for physical arrival, while the
                 * semantic navigation frame runs at a lower frequency. Do
                 * not chain a second move from the stale pre-arrival feet.
                 */
                return SkillTickResult.running(false, true);
            }
        } else if (hasCurrentActivePlan(
                context.goalRevision(),
                arrived.sessionGeneration()
        )) {
            final Optional<GridPos> continuation =
                    observedRoofReturnContinuation(
                            returningInsideForRoof,
                            arrived,
                            activePlan,
                            expectedArrival,
                            exploredActivePlanTraversalStands,
                            rejectedActivePlanTraversalStands
                    );
            if (continuation.isPresent()) {
                activeRelocationParameters = null;
                relocationArrivalWaitStartedAtGameTick = -1;
                MinecraftAiCompanion.LOGGER.info(
                        "Continuing across observed shelter return "
                                + "corridor without panoramic survey "
                                + "planOrigin={} confirmed={} from={} "
                                + "next={}",
                        activePlan.origin(),
                        confirmed.cardinality(),
                        arrived.feet(),
                        continuation.orElseThrow()
                );
                return startRelocation(
                        context,
                        parameters,
                        arrived
                );
            }
            final Optional<GridPos> transitContinuation =
                    observedActivePlanTransitContinuation(
                            returningInsideForRoof,
                            activePlanTraversalDestinationWasExplored,
                            arrived,
                            activePlan,
                            confirmed,
                            expectedArrival,
                            exploredActivePlanTraversalStands
                    );
            if (transitContinuation.isPresent()) {
                activeRelocationParameters = null;
                relocationArrivalWaitStartedAtGameTick = -1;
                MinecraftAiCompanion.LOGGER.info(
                        "Continuing across an already surveyed shelter "
                                + "transit cell without another panorama "
                                + "planOrigin={} confirmed={} from={} "
                                + "next={}",
                        activePlan.origin(),
                        confirmed.cardinality(),
                        arrived.feet(),
                        transitContinuation.orElseThrow()
                );
                return startRelocation(
                        context,
                        parameters,
                        arrived
                );
            }
            if (returningInsideForRoof
                    && isInteriorFloorPosition(
                            activePlan,
                            arrived.feet()
                    )) {
                returningInsideForRoof = false;
            }
        }

        activeRelocationParameters = null;
        relocationArrivalWaitStartedAtGameTick = -1;
        inactiveMoveRecoveryAttempts = 0;
        activePlanTraversalDestinationWasExplored = false;
        activeSurveyParameters =
                activePlan != null
                        && currentRole(activePlan, confirmed)
                                .filter(role ->
                                        role == ShelterStepRole.ROOF)
                                .isPresent()
                        && isExteriorRoofReturnPosition(
                                activePlan,
                                arrived.feet()
                        )
                    ? activePlanTraversalSurveyParameters(
                            parameters
                    )
                    : surveyParameters(parameters);
        final SurveySurroundingsSkill survey =
                surveySkill.orElseThrow();
        final Optional<SkillFailure> rejected =
                survey.preconditions(
                        context,
                        activeSurveyParameters
                );
        if (rejected.isPresent()) {
            activeSurveyParameters = null;
            return fail(rejected.orElseThrow());
        }
        survey.start(context, activeSurveyParameters);
        phase = Phase.SURVEYING;
        return SkillTickResult.running(true, true);
    }

    static boolean recoverableRoofReturnMoveFailure(
            final String code
    ) {
        return "move_to.turn_stuck".equals(code)
                || "move_to.stuck".equals(code)
                || "move_to.route_unknown".equals(code);
    }

    private SkillTickResult tickAimReposition(
            final SkillContext context,
            final BuildShelterStepParameters parameters
    ) {
        final MoveToParameters movementParameters =
                Objects.requireNonNull(activeRepositionParameters);
        observeAimReposition(context, movementParameters);
        final BoundedRepositionProgress.Expiration expiration =
                aimRepositionWatchdog.expirationAt(
                        context.gameTick()
                );
        if (expiration
                != BoundedRepositionProgress.Expiration.NONE) {
            return recoverExpiredAimReposition(
                    context,
                    parameters,
                    movementParameters,
                    expiration
            );
        }
        final SkillTickResult movement =
                relocationSkill.orElseThrow().tick(
                        context,
                        movementParameters
                );
        if (movement.status() == SkillTickResult.Status.FAILED) {
            final SkillFailure reason =
                    movement.failure().orElseThrow();
            if ("move_to.invalid_state".equals(reason.code())
                    && inactiveMoveRecoveryAttempts++ < 1) {
                MinecraftAiCompanion.LOGGER.warn(
                        "Re-arming inactive shelter aim child target={} "
                                + "attempt={}",
                        movementParameters.gridGoal(),
                        inactiveMoveRecoveryAttempts
                );
                relocationSkill.orElseThrow().start(
                        context,
                        movementParameters
                );
                return SkillTickResult.running(true, true);
            }
            inactiveMoveRecoveryAttempts = 0;
            activeRepositionParameters = null;
            activeRoofApronSurveyStand = null;
            aimRepositionWatchdog.clear();
            if (recoverableAimRouteFailure(reason)) {
                final Optional<SkillTickResult> recovered =
                        recoverAimReposition(
                                context,
                                parameters
                        );
                if (recovered.isPresent()) {
                    return recovered.orElseThrow();
                }
            }
            return fail(reason);
        }
        if (movement.status() == SkillTickResult.Status.RUNNING) {
            return movement;
        }
        final boolean observationStaging =
                isRoofObservationStagingDestination(
                        movementParameters
                );
        final boolean exteriorDoorExit =
                isRoofExteriorDoorwayDestination(
                        movementParameters
                );
        final boolean roofApronStaging =
                isRoofApronSurveyStagingDestination(
                        movementParameters
                );
        activeRepositionParameters = null;
        aimRepositionWatchdog.clear();
        inactiveMoveRecoveryAttempts = 0;
        final FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                false
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        /*
         * Jump attempts are a per-stance budget. A completed ordinary
         * MoveTo proves that the body physically entered another observed
         * cell, so this new stance receives its own bounded jump attempts.
         * Merely restarting the skill or surveying in place never resets
         * the budget.
         */
        resetJumpAim(executingStep);
        if (observationStaging
                && requiresPanoramicAimRecoverySurvey(
                        true,
                        false,
                        false
                )
                && startAimRecoverySurvey(
                        context,
                        parameters
                )) {
            pendingRoofDoorExit = true;
            return SkillTickResult.running(true, true);
        }
        if (exteriorDoorExit
                && requiresPanoramicAimRecoverySurvey(
                        false,
                        true,
                        false
                )
                && startAimRecoverySurvey(
                        context,
                        parameters
                )) {
            roofExteriorSurveyRevision =
                    validation.frame().orElseThrow()
                            .observationRevision();
            return SkillTickResult.running(true, true);
        }
        if (roofApronStaging) {
            activeRoofApronSurveyStand = null;
            roofExteriorSurveyRevision =
                    validation.frame().orElseThrow()
                            .observationRevision();
            /*
             * The initial doorway panorama has already established the
             * observed-safe apron. Repeating all 24 stationary views after
             * every one-cell frontier hop made two adjacent roof blocks take
             * minutes and produced visibly robotic full rotations. Turn
             * directly toward the active support instead; beginAim waits for
             * a newer semantic sample before trusting the crosshair. If that
             * targeted refresh still cannot expose a route, the existing
             * bounded recovery path may perform one panorama as a fallback.
             */
            if (requiresPanoramicAimRecoverySurvey(
                    false,
                    false,
                    true
            ) && startAimRecoverySurvey(context, parameters)) {
                return SkillTickResult.running(true, true);
            }
            final ShelterFrame arrived =
                    validation.frame().orElseThrow();
            if (executingStep != null
                    && preferredVisibleSupport(
                            arrived,
                            executingStep
                    ).isEmpty()
                    && startRoofApronTargetedRefresh(
                            context,
                            arrived
                    )) {
                return SkillTickResult.running(true, true);
            }
        }
        phase = Phase.AIMING;
        beginAim(context, validation.frame().orElseThrow());
        return SkillTickResult.running(true, true);
    }

    static boolean requiresPanoramicAimRecoverySurvey(
            final boolean observationStaging,
            final boolean exteriorDoorExit,
            final boolean roofApronStaging
    ) {
        final int destinations =
                (observationStaging ? 1 : 0)
                + (exteriorDoorExit ? 1 : 0)
                + (roofApronStaging ? 1 : 0);
        if (destinations > 1) {
            throw new IllegalArgumentException(
                    "Aim reposition destination kinds overlap"
            );
        }
        return observationStaging || exteriorDoorExit;
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            BuildShelterStepParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        rememberCurrentBodyTransit(context, parameters);
        if (phase == Phase.RELOCATING) {
            return tickRelocation(context, parameters);
        }
        if (phase == Phase.REPOSITIONING_FOR_AIM) {
            return tickAimReposition(context, parameters);
        }
        if (phase == Phase.REFRESHING_ROOF_APRON) {
            return tickRoofApronRefresh(context, parameters);
        }
        if (phase == Phase.CLEARING_PLACEMENT_OBSTRUCTION) {
            return tickPlacementObstructionRecovery(
                    context,
                    parameters
            );
        }
        if (phase == Phase.SURVEYING_PLACEMENT_REPAIR) {
            return tickPlacementRepairSurvey(
                    context,
                    parameters
            );
        }
        if (phase == Phase.ROOF_EDGE_BRIDGING) {
            return tickRoofEdgeBridge(context, parameters);
        }
        if (phase == Phase.SURVEYING) {
            final SkillTickResult surveyed =
                    surveySkill.orElseThrow().tick(
                            context,
                            Objects.requireNonNull(
                                    activeSurveyParameters
                            )
                    );
            if (surveyed.status()
                    == SkillTickResult.Status.FAILED) {
                return fail(surveyed.failure().orElseThrow());
            }
            if (surveyed.status()
                    == SkillTickResult.Status.RUNNING) {
                return surveyed;
            }
            /*
             * A new plan is never synthesized from an old block map alone.
             * The completed first-person survey has now refreshed both
             * terrain and short-lived visible-entity memory for this exact
             * goal/body session, so planning may proceed once.
             */
            initialSiteSurveyGoalRevision =
                    context.goalRevision();
            initialSiteSurveySessionGeneration =
                    boundSessionGeneration;
            if (pendingRoofDoorExit) {
                final Optional<SkillTickResult> exit =
                        startRoofExteriorDoorExit(
                                context,
                                parameters
                        );
                if (exit.isPresent()) {
                    return exit.orElseThrow();
                }
                pendingRoofDoorExit = false;
            }
            if (returningInsideForRoof && activePlan != null) {
                final FrameValidation returnFrame =
                        validateFrame(
                                parameters,
                                boundSessionGeneration,
                                false
                        );
                if (returnFrame.failure().isPresent()) {
                    return fail(
                            returnFrame.failure().orElseThrow()
                    );
                }
                final ShelterFrame current =
                        returnFrame.frame().orElseThrow();
                if (roofInteriorReturnStillPending(
                        true,
                        activePlan,
                        current.feet()
                )) {
                    /*
                     * A corridor survey may expose a roof face. It must not
                     * preempt the cardinal compound return and send an aim
                     * route diagonally through the one-cell doorway.
                     */
                    return startRelocation(
                            context,
                            parameters,
                            current
                    );
                }
                returningInsideForRoof = false;
            }
            final Preparation prepared = prepare(
                    parameters,
                    context.goalRevision(),
                    PreparationAdmission.BOUND_INTERNAL_SURVEY
            );
            if (prepared.failure().isPresent()) {
                return fail(prepared.failure().orElseThrow());
            }
            if (prepared.surveyRequired()) {
                if (!prepared.relocationAllowed()) {
                    return fail(
                            "build_shelter_step.no_visible_build_step"
                    );
                }
                final ShelterFrame preparedFrame =
                        prepared.frame().orElseThrow();
                final boolean traversingActivePlan =
                        hasCurrentActivePlan(
                                context.goalRevision(),
                                preparedFrame.sessionGeneration()
                        );
                if (traversingActivePlan) {
                    if (!activePlanTraversalRelocationAvailable(
                            activePlanTraversalRelocationAttempts,
                            Objects.requireNonNull(activePlan)
                    )) {
                        return fail(
                                "shelter.insufficient_observation"
                        );
                    }
                } else {
                    bindInitialSiteSearch(
                            context.goalRevision(),
                            preparedFrame.sessionGeneration(),
                            parameters.scale()
                    );
                    rejectedInitialSiteCenters.add(
                            preparedFrame.feet()
                    );
                    if (!initialSiteRelocationAvailable(
                            initialSiteRelocationAttempts
                    )) {
                        return fail(
                                "shelter.insufficient_observation"
                        );
                    }
                }
                return startRelocation(
                        context,
                        parameters,
                        preparedFrame
                );
            }
            commitPreparation(context, prepared);
            return SkillTickResult.running(true, true);
        }
        if (phase == Phase.READY_COMPLETE) {
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (phase == Phase.EQUIPPING) {
            final InventoryOperationResult outcome =
                    inventoryActuator.orElseThrow().equip(
                            Objects.requireNonNull(pendingEquipment)
                    );
            if (!outcome.succeeded()) {
                return fail(outcome.failure().orElseThrow());
            }
            pendingEquipment = null;
            phase = placementReadyPhase();
            if (phase == Phase.AIMING) {
                final ShelterFrame frame = frames.current()
                        .orElseThrow(() -> new IllegalStateException(
                                "Shelter frame disappeared after equip"
                        ));
                beginAim(context, frame);
            }
            /*
             * Equipping is a verified vanilla menu transaction. Yield at
             * this atomic boundary before dispatching the placement action.
             */
            return SkillTickResult.running(true, true);
        }
        if (phase == Phase.AIMING) {
            return tickAim(context, parameters, false);
        }
        if (phase == Phase.SNEAKING_FOR_PLACEMENT) {
            final ActionOutcome sneaking = coreActuator.orElseThrow()
                    .move(new MovementIntent(
                            0.0,
                            0.0,
                            false,
                            true
                    ));
            if (!sneaking.accepted()) {
                return fail(
                        "build_shelter_step.sneak_placement_rejected"
                );
            }
            /*
             * The leased core actuator applies this as an ordinary
             * ServerboundPlayerInputPacket in the post-tick action frame.
             * Crouching lowers the vanilla eye position, so the standing
             * crosshair hit is no longer authoritative. Wait one tick, then
             * reacquire and align an exact first-person hit while continuing
             * to hold sneak before right-clicking an interactive support.
             */
            final ShelterFrame frame = frames.current().orElseThrow(
                    () -> new IllegalStateException(
                            "Shelter frame disappeared before sneak aim"
                    )
            );
            phase = Phase.AIMING_WHILE_SNEAKING;
            beginAim(context, frame);
            return SkillTickResult.running(true, true);
        }
        if (phase == Phase.AIMING_WHILE_SNEAKING) {
            return tickAim(context, parameters, true);
        }
        if (phase == Phase.READY) {
            return dispatchPlacement(
                    context,
                    parameters,
                    frames.current().orElse(null)
            );
        }
        if (phase == Phase.VERIFYING_PLACEMENT) {
            return tickPlacementVerification(context, parameters);
        }
        if (phase != Phase.WAITING_FOR_CONFIRMATION) {
            return SkillTickResult.failed(
                    "build_shelter_step.invalid_state"
            );
        }
        if (context.gameTick() - dispatchedAtGameTick
                >= CONFIRMATION_TIMEOUT_TICKS) {
            return resolvePlacementConfirmationTimeout(
                    context,
                    parameters
            );
        }
        FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                false
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        ShelterFrame frame = validation.frame().orElseThrow();
        if (frame.observationRevision()
                <= dispatchedObservationRevision) {
            return SkillTickResult.running(false, false);
        }
        if (!placementEvidencePresent(frame)) {
            return SkillTickResult.running(false, false);
        }
        return acceptConfirmedPlacement(context, frame);
    }

    private SkillTickResult dispatchPlacement(
            final SkillContext context,
            final BuildShelterStepParameters parameters,
            final ShelterFrame beforeDispatch
    ) {
        if (executingStep == null
                || interactionTarget == null
                || !interactionPlacesStep(
                        interactionTarget,
                        executingStep
                )) {
            return fail(
                    "build_shelter_step.target_binding_mismatch"
            );
        }
        if (beforeDispatch != null) {
            final Optional<SkillTickResult> obstructionRecovery =
                    startPlacementObstructionRecovery(
                            context,
                            parameters,
                            beforeDispatch
                    );
            if (obstructionRecovery.isPresent()) {
                return obstructionRecovery.orElseThrow();
            }
        }
        final BlockInteractionTarget clicked =
                Objects.requireNonNull(interactionTarget);
        final ActionOutcome outcome = actuator.useOnBlock(
                ActionHand.MAIN_HAND,
                clicked
        );
        if (!outcome.accepted()) {
            return fail(actionFailure(outcome));
        }
        coreActuator.ifPresent(CoreSkillActuator::stop);
        dispatchedObservationRevision = beforeDispatch == null
                ? parameters.sampleSequence()
                : beforeDispatch.observationRevision();
        dispatchedAtGameTick = context.gameTick();
        dispatchedMainHandItem = beforeDispatch == null
                ? ""
                : beforeDispatch.mainHand().itemId();
        dispatchedMainHandCount = beforeDispatch == null
                ? -1
                : beforeDispatch.mainHand().count();
        dispatchedActionCompleted =
                outcome == ActionOutcome.COMPLETED;
        dispatchedSupportWasSolid =
                beforeDispatch != null
                        && beforeDispatch.navigation()
                                .voxelAt(new GridPos(
                                        clicked.x(),
                                        clicked.y(),
                                        clicked.z()
                                ))
                                .map(voxel ->
                                        voxel.kind()
                                                .supportsWeight())
                                .orElse(false);
        phase = coreActuator.isPresent()
                ? Phase.VERIFYING_PLACEMENT
                : Phase.WAITING_FOR_CONFIRMATION;
        // The vanilla packet is atomic, but the local plan must not be
        // cancellable/checkpointed until a newer fair observation confirms
        // which block actually appeared.
        return SkillTickResult.running(true, false);
    }

    private Optional<SkillTickResult>
            recoverMismatchedInteractionTarget(
                    final SkillContext context,
                    final BuildShelterStepParameters parameters,
                    final ShelterFrame frame
            ) {
        if (executingStep == null
                || interactionTarget == null
                || interactionPlacesStep(
                        interactionTarget,
                        executingStep
                )) {
            return Optional.empty();
        }
        final BlockInteractionTarget stale = interactionTarget;
        final Optional<StepTarget> rebound =
                resolveTarget(frame, executingStep)
                        .or(() -> rememberedTarget(
                                frame,
                                executingStep
                        ));
        if (rebound.isEmpty()
                || !interactionPlacesStep(
                        rebound.orElseThrow().target(),
                        executingStep
                )) {
            MinecraftAiCompanion.LOGGER.warn(
                    "Could not repair mismatched shelter interaction "
                            + "binding planOrigin={} stepIndex={} "
                            + "stepTarget={} staleTarget={}",
                    activePlan == null ? null : activePlan.origin(),
                    executingStep.index(),
                    executingStep.target(),
                    stale
            );
            return Optional.of(
                    deferCurrentAimStep(context, parameters)
                            .orElseGet(() -> fail(
                                    "build_shelter_step."
                                            + "target_binding_mismatch"
                            ))
            );
        }
        interactionTarget = rebound.orElseThrow().target();
        aimRepositionAttempts = 0;
        aimRecoverySurveyPerformed = false;
        attemptedAimVantages.clear();
        attemptedRoofObservationStands.clear();
        abandonedAimSupports.clear();
        activeRoofApronSurveyStand = null;
        clearRoofApronRefresh();
        aimRepositionWatchdog.clear();
        resetJumpAim(executingStep);
        beginAim(context, frame);
        MinecraftAiCompanion.LOGGER.warn(
                "Repaired mismatched shelter interaction binding "
                        + "planOrigin={} stepIndex={} stepTarget={} "
                        + "staleTarget={} reboundTarget={}",
                activePlan == null ? null : activePlan.origin(),
                executingStep.index(),
                executingStep.target(),
                stale,
                interactionTarget
        );
        return Optional.of(
                SkillTickResult.running(true, true)
        );
    }

    /**
     * Reacquires the generated target after the crouch key is released.
     * Vanilla eye height rises when leaving sneak, so the low click ray that
     * placed a block can pass just above it on the next tick. Looking at the
     * known target centre obtains new first-person evidence without querying
     * the level or trusting inventory consumption alone.
     */
    private SkillTickResult tickPlacementVerification(
            final SkillContext context,
            final BuildShelterStepParameters parameters
    ) {
        if (context.gameTick() - dispatchedAtGameTick
                >= CONFIRMATION_TIMEOUT_TICKS) {
            return resolvePlacementConfirmationTimeout(
                    context,
                    parameters
            );
        }
        final Optional<CoreSkillFrame> maybeCore =
                coreFrames.orElseThrow().current();
        if (maybeCore.isEmpty()) {
            return fail(
                    "build_shelter_step.confirm_pose_unavailable"
            );
        }
        final CoreSkillFrame core = maybeCore.orElseThrow();
        if (!expectedPlayerId.equals(core.playerId())
                || !parameters.dimension().equals(core.dimension())) {
            return fail("build_shelter_step.confirm_pose_mismatch");
        }
        final FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                false
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final ShelterFrame frame = validation.frame().orElseThrow();
        final GridPos intended = Objects.requireNonNull(
                executingStep
        ).target();
        final PerceptionVec3 targetCentre = new PerceptionVec3(
                intended.x() + 0.5,
                intended.y() + 0.5,
                intended.z() + 0.5
        );
        final PerceptionVec3 delta =
                targetCentre.subtract(core.eyePosition());
        if (delta.lengthSquared() <= 1.0E-12) {
            return fail("build_shelter_step.confirm_aim_invalid");
        }
        final CoreSkillActuator actions =
                coreActuator.orElseThrow();
        final ActionOutcome stopped = actions.stop();
        final ActionOutcome looked = actions.look(
                lookAt(core.eyePosition(), targetCentre)
        );
        if (!stopped.accepted() || !looked.accepted()) {
            return fail("build_shelter_step.confirm_aim_rejected");
        }
        if (angularErrorDegrees(core.lookDirection(), delta)
                > AIM_ALIGNMENT_DEGREES
                || frame.observationRevision()
                        <= dispatchedObservationRevision) {
            return SkillTickResult.running(true, false);
        }
        if (!placementEvidencePresent(frame)) {
            return SkillTickResult.running(false, false);
        }
        return acceptConfirmedPlacement(context, frame);
    }

    private boolean placementEvidencePresent(
            final ShelterFrame frame
    ) {
        return crosshairPlacementConfirmed(
                Objects.requireNonNull(executingStep),
                Objects.requireNonNull(activePlan)
        ) || placementConfirmed(
                frame,
                Objects.requireNonNull(executingStep),
                Objects.requireNonNull(activePlan),
                dispatchedObservationRevision
        );
    }

    private SkillTickResult resolvePlacementConfirmationTimeout(
            final SkillContext context,
            final BuildShelterStepParameters parameters
    ) {
        final FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                false
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final ShelterFrame frame =
                validation.frame().orElseThrow();
        if (causalPlacementEvidence(frame)) {
            MinecraftAiCompanion.LOGGER.info(
                    "Accepted causal vanilla placement receipt "
                            + "planOrigin={} stepIndex={} target={} "
                            + "clickedTarget={} hand={}x{}->{}",
                    activePlan == null ? null : activePlan.origin(),
                    executingStep == null
                            ? -1
                            : executingStep.index(),
                    executingStep == null
                            ? null
                            : executingStep.target(),
                    interactionTarget,
                    dispatchedMainHandItem,
                    dispatchedMainHandCount,
                    frame.mainHand()
            );
            return acceptConfirmedPlacement(context, frame);
        }
        logPlacementConfirmationTimeout();
        final Optional<SkillTickResult> obstructionRecovery =
                startPlacementObstructionRecovery(
                        context,
                        parameters,
                        frame
                );
        if (obstructionRecovery.isPresent()) {
            return obstructionRecovery.orElseThrow();
        }
        if (isFreshNoEffectPlacement(frame)
                && unconfirmedPlacementRetries
                        < MAXIMUM_PLACEMENT_RECOVERY_ATTEMPTS) {
            unconfirmedPlacementRetries++;
            resetDispatchedPlacement();
            final Optional<SkillTickResult> repositioned =
                    startAimReposition(
                            context,
                            parameters,
                            frame,
                            Objects.requireNonNull(interactionTarget)
                    );
            if (repositioned.isPresent()) {
                MinecraftAiCompanion.LOGGER.info(
                        "Recovering no-effect shelter placement from "
                                + "another observed stance planOrigin={} "
                                + "stepIndex={} retry={}",
                        activePlan == null
                                ? null
                                : activePlan.origin(),
                        executingStep == null
                                ? -1
                                : executingStep.index(),
                        unconfirmedPlacementRetries
                );
                return repositioned.orElseThrow();
            }
            phase = Phase.AIMING;
            beginAim(context, frame);
            MinecraftAiCompanion.LOGGER.info(
                    "Retrying transient no-effect shelter placement "
                            + "after a newer fair observation "
                            + "planOrigin={} stepIndex={} retry={}",
                    activePlan == null ? null : activePlan.origin(),
                    executingStep == null
                            ? -1
                            : executingStep.index(),
                    unconfirmedPlacementRetries
            );
            return SkillTickResult.running(true, true);
        }
        return fail("build_shelter_step.place_unconfirmed");
    }

    private boolean causalPlacementEvidence(
            final ShelterFrame frame
    ) {
        final ShelterPlan plan =
                Objects.requireNonNull(activePlan);
        final ShelterBuildStep step =
                Objects.requireNonNull(executingStep);
        return CausalPlacementEvidence.confirms(
                requiredItem(plan, step.role()),
                step.target(),
                Objects.requireNonNull(interactionTarget),
                dispatchedActionCompleted,
                dispatchedSupportWasSolid,
                dispatchedMainHandItem,
                dispatchedMainHandCount,
                frame.mainHand()
        );
    }

    private Optional<SkillTickResult>
            startPlacementObstructionRecovery(
                    final SkillContext context,
                    final BuildShelterStepParameters parameters,
                    final ShelterFrame frame
            ) {
        if (coreActuator.isEmpty()
                || coreFrames.isEmpty()
                || activePlan == null
                || executingStep == null) {
            return Optional.empty();
        }
        final Optional<RecentVisibleEntity> obstruction =
                DynamicShelterPlanner.visiblePlacementObstruction(
                        frame,
                        executingStep.target()
                );
        if (obstruction.isEmpty()) {
            return Optional.empty();
        }
        final RecentVisibleEntity remembered =
                obstruction.orElseThrow();
        final var entity = remembered.entity();
        resetDispatchedPlacement();
        avoidedPlacementTargets.add(
                executingStep.target()
        );
        if (executingStep.role()
                .usesStructuralMaterial()) {
            final Optional<SkillTickResult> replanned =
                    replanAroundPlacementObstruction(
                            context,
                            parameters,
                            frame
                    );
            if (replanned.isPresent()) {
                return replanned;
            }
        }
        if (entity.hostile()
                || "minecraft:player".equals(
                        entity.entityTypeId()
                )
                || placementRecoveryAttempts
                        >= MAXIMUM_PLACEMENT_RECOVERY_ATTEMPTS) {
            return Optional.of(
                    deferCurrentAimStep(context, parameters)
                            .orElseGet(() -> fail(
                                    entity.hostile()
                                            ? "build_shelter_step."
                                                + "hostile_obstruction"
                                            : "build_shelter_step."
                                                + "placement_obstruction"
                            ))
            );
        }
        final Optional<PerceptionVec3> destination =
                placementPushDestination(
                        frame,
                        activePlan,
                        executingStep
                );
        if (destination.isEmpty()) {
            return Optional.of(
                    deferCurrentAimStep(context, parameters)
                            .orElseGet(() -> fail(
                                    "build_shelter_step."
                                            + "obstruction_route_unknown"
                            ))
            );
        }
        final ActionOutcome stopped =
                coreActuator.orElseThrow().stop();
        if (!stopped.accepted()) {
            return Optional.of(fail(
                    "build_shelter_step."
                            + "obstruction_actuator_rejected"
            ));
        }
        placementObstructionEntityId =
                entity.entityId();
        placementPushDestination =
                destination.orElseThrow();
        placementPushCorridor = observedPushCorridor(
                frame,
                new PerceptionVec3(
                        frame.feet().x() + 0.5,
                        frame.feet().y(),
                        frame.feet().z() + 0.5
                ),
                placementPushDestination,
                activePlan.origin().y()
        ).orElseThrow();
        placementRecoveryStartedAtGameTick =
                context.gameTick();
        placementRecoveryStartedRevision =
                frame.observationRevision();
        placementRecoveryAttempts++;
        phase = Phase.CLEARING_PLACEMENT_OBSTRUCTION;
        MinecraftAiCompanion.LOGGER.info(
                "Started ordinary movement recovery for visible "
                        + "shelter placement obstruction "
                        + "planOrigin={} stepIndex={} target={} "
                        + "entityType={} entityId={} entityPosition={} "
                        + "destination={} attempt={}",
                activePlan.origin(),
                executingStep.index(),
                executingStep.target(),
                entity.entityTypeId(),
                entity.entityId(),
                entity.position(),
                placementPushDestination,
                placementRecoveryAttempts
        );
        return Optional.of(
                SkillTickResult.running(true, false)
        );
    }

    private Optional<SkillTickResult>
            replanAroundPlacementObstruction(
                    final SkillContext context,
                    final BuildShelterStepParameters parameters,
                    final ShelterFrame frame
            ) {
        final ShelterPlan previousPlan =
                Objects.requireNonNull(activePlan);
        final Set<GridPos> reusable =
                previousPlan.steps().stream()
                        .filter(step ->
                                confirmed.get(step.index()))
                        .filter(step ->
                                step.role()
                                        .usesStructuralMaterial())
                        .map(ShelterBuildStep::target)
                        .collect(java.util.stream.Collectors.toSet());
        final ShelterPlanningResult repaired =
                planner.repair(
                        frame,
                        previousPlan.scale(),
                        previousPlan.structuralItemId(),
                        reusable,
                        avoidedPlacementTargets
                );
        if (repaired.plan().isEmpty()) {
            final Optional<SkillFailure> repairFailure =
                    repaired.failure();
            MinecraftAiCompanion.LOGGER.info(
                    "Could not yet locally replan around visible "
                            + "shelter obstruction planOrigin={} "
                            + "stepIndex={} target={} reusable={} "
                            + "avoided={} reason={}",
                    previousPlan.origin(),
                    executingStep == null
                            ? -1
                            : executingStep.index(),
                    executingStep == null
                            ? null
                            : executingStep.target(),
                    reusable.size(),
                    avoidedPlacementTargets,
                    repairFailure
                            .map(SkillFailure::code)
                            .orElse("unknown")
            );
            if (repairFailure
                    .map(SkillFailure::code)
                    .filter(BuildShelterStepSkill
                            ::recoverableRepairObservationFailure)
                    .isPresent()) {
                final Optional<SkillTickResult> surveying =
                        startPlacementRepairSurvey(
                                context,
                                parameters,
                                repairFailure.orElseThrow()
                        );
                if (surveying.isPresent()) {
                    return surveying;
                }
            }
            return Optional.empty();
        }
        final ShelterPlan replacement =
                repaired.plan().orElseThrow();
        final BitSet replacementConfirmed =
                new BitSet();
        replacement.steps().stream()
                .filter(step ->
                        step.role().usesStructuralMaterial()
                                && reusable.contains(
                                        step.target()
                                ))
                .forEach(step ->
                        replacementConfirmed.set(
                                step.index()
                        ));

        activePlan = replacement;
        planGoalRevision = context.goalRevision();
        planSessionGeneration =
                frame.sessionGeneration();
        protectedStructures.activateShelter(
                context.goalRevision(),
                replacement
        );
        confirmed.clear();
        confirmed.or(replacementConfirmed);
        deferredAimSteps.clear();
        attemptedRoofEdgeBridges.clear();
        exhaustedExteriorRoofSteps.clear();
        roofInteriorFallbackPriority = -1;
        exploredActivePlanTraversalStands.clear();
        rejectedActivePlanTraversalStands.clear();
        bodyVerifiedActivePlanTransitStands.clear();
        activePlanTraversalDestinationWasExplored = false;
        returningInsideForRoof = false;
        activePlanTraversalRelocationAttempts = 0;
        roofExteriorSurveyRevision = -1;
        pendingRoofDoorExit = false;
        executingStep = null;
        resetJumpAim(null);
        interactionTarget = null;
        pendingEquipment = null;
        aimRepositionAttempts = 0;
        aimRecoverySurveyPerformed = false;
        attemptedAimVantages.clear();
        attemptedRoofObservationStands.clear();
        activeRoofApronSurveyStand = null;
        clearRoofApronRefresh();
        openedOccludingDoors.clear();
        aimRepositionWatchdog.clear();
        placementRecoveryAttempts = 0;
        unconfirmedPlacementRetries = 0;
        clearPlacementObstructionRecovery();

        final Preparation prepared = prepare(
                parameters,
                context.goalRevision(),
                PreparationAdmission.BOUND_INTERNAL_SURVEY
        );
        final boolean needsRecoverySurvey =
                prepared.surveyRequired()
                || prepared.failure()
                        .map(SkillFailure::code)
                        .filter(code ->
                                "build_shelter_step."
                                        .concat(
                                                "no_visible_build_step"
                                        )
                                        .equals(code)
                                || "shelter."
                                        .concat(
                                                "insufficient_observation"
                                        )
                                        .equals(code))
                        .isPresent();
        if (needsRecoverySurvey
                && surveySkill.isPresent()) {
            activeSurveyParameters =
                    surveyParameters(parameters);
            final SurveySurroundingsSkill survey =
                    surveySkill.orElseThrow();
            final Optional<SkillFailure> rejected =
                    survey.preconditions(
                            context,
                            activeSurveyParameters
                    );
            if (rejected.isEmpty()) {
                survey.start(
                        context,
                        activeSurveyParameters
                );
                phase = Phase.SURVEYING;
                MinecraftAiCompanion.LOGGER.info(
                        "Started local survey for repaired shelter plan "
                                + "oldOrigin={} newOrigin={} reused={}",
                        previousPlan.origin(),
                        replacement.origin(),
                        replacementConfirmed.cardinality()
                );
                return Optional.of(
                        SkillTickResult.running(true, true)
                );
            }
        }
        if (prepared.failure().isPresent()
                || prepared.surveyRequired()) {
            /*
             * The replacement itself is still valid and persisted in this
             * executor. End the current atomic batch so the ordinary
             * supervisor can supply a fresh authored sample; do not restore
             * the now-known blocked plan.
             */
            phase = Phase.COMPLETED;
            MinecraftAiCompanion.LOGGER.info(
                    "Persisted locally repaired shelter plan for a "
                            + "fresh execution batch oldOrigin={} "
                            + "newOrigin={} reused={} followup={}",
                    previousPlan.origin(),
                    replacement.origin(),
                    replacementConfirmed.cardinality(),
                    prepared.failure()
                            .map(SkillFailure::code)
                            .orElse("survey")
            );
            return Optional.of(
                    SkillTickResult.completed()
            );
        }
        commitPreparation(context, prepared);
        MinecraftAiCompanion.LOGGER.info(
                "Locally replanned shelter around visible placement "
                        + "obstruction oldOrigin={} newOrigin={} "
                        + "oldPlan={} newPlan={} reused={} avoided={}",
                previousPlan.origin(),
                replacement.origin(),
                previousPlan.planId(),
                replacement.planId(),
                replacementConfirmed.cardinality(),
                avoidedPlacementTargets
        );
        return Optional.of(
                SkillTickResult.running(true, true)
        );
    }

    /**
     * Keeps an obstruction-triggered site repair inside the same physical
     * transaction while acquiring a broader fair first-person map.
     *
     * <p>The ordinary survey phase calls {@link #prepare} when it completes.
     * That is correct for initial planning, but wrong here: the active plan is
     * known to contain a forbidden occupied cell. Re-entering normal
     * preparation simply selects that plan again and hands the failure back
     * to the model. This dedicated phase preserves the causal confirmations,
     * forbidden target, and original scale, then retries
     * {@link DynamicShelterPlanner#repair} directly.</p>
     */
    private Optional<SkillTickResult> startPlacementRepairSurvey(
            final SkillContext context,
            final BuildShelterStepParameters parameters,
            final SkillFailure repairFailure
    ) {
        if (surveySkill.isEmpty()
                || placementRepairSurveyAttempts
                        >= MAXIMUM_PLACEMENT_REPAIR_SURVEYS) {
            return Optional.empty();
        }
        final SurveySurroundingsParameters surveyParameters =
                placementRepairSurveyParameters(parameters);
        final SurveySurroundingsSkill survey =
                surveySkill.orElseThrow();
        final Optional<SkillFailure> rejected =
                survey.preconditions(
                        context,
                        surveyParameters
                );
        if (rejected.isPresent()) {
            return Optional.empty();
        }
        activeSurveyParameters = surveyParameters;
        survey.start(context, surveyParameters);
        placementRepairSurveyAttempts++;
        phase = Phase.SURVEYING_PLACEMENT_REPAIR;
        MinecraftAiCompanion.LOGGER.info(
                "Started fair local survey before shelter obstruction "
                        + "replan planOrigin={} stepIndex={} target={} "
                        + "attempt={} reason={}",
                activePlan == null ? null : activePlan.origin(),
                executingStep == null ? -1 : executingStep.index(),
                executingStep == null ? null : executingStep.target(),
                placementRepairSurveyAttempts,
                repairFailure.code()
        );
        return Optional.of(
                SkillTickResult.running(true, true)
        );
    }

    private SkillTickResult tickPlacementRepairSurvey(
            final SkillContext context,
            final BuildShelterStepParameters parameters
    ) {
        final SurveySurroundingsSkill survey =
                surveySkill.orElseThrow();
        final SkillTickResult surveyed = survey.tick(
                context,
                Objects.requireNonNull(activeSurveyParameters)
        );
        if (surveyed.status() == SkillTickResult.Status.FAILED) {
            return fail(surveyed.failure().orElseThrow());
        }
        if (surveyed.status() == SkillTickResult.Status.RUNNING) {
            return surveyed;
        }
        activeSurveyParameters = null;

        final FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                false
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final ShelterFrame frame = validation.frame().orElseThrow();
        final Optional<SkillTickResult> replanned =
                replanAroundPlacementObstruction(
                        context,
                        parameters,
                        frame
                );
        if (replanned.isPresent()) {
            return replanned.orElseThrow();
        }

        /*
         * Observation is now broad enough or the bounded survey budget is
         * exhausted. Retain the ordinary movement fallback for a visible
         * non-hostile actor, but do not return a recoverable observation miss
         * to the model for another identical skill invocation.
         */
        return startPlacementObstructionRecovery(
                context,
                parameters,
                frame
        ).orElseGet(() -> fail(
                "build_shelter_step.obstruction_replan_unavailable"
        ));
    }

    private SkillTickResult tickPlacementObstructionRecovery(
            final SkillContext context,
            final BuildShelterStepParameters parameters
    ) {
        final Optional<CoreSkillFrame> maybeCore =
                coreFrames.orElseThrow().current();
        if (maybeCore.isEmpty()) {
            return fail(
                    "build_shelter_step.obstruction_pose_unavailable"
            );
        }
        final CoreSkillFrame core = maybeCore.orElseThrow();
        final FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                false
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final ShelterFrame frame = validation.frame().orElseThrow();
        final ShelterBuildStep step =
                Objects.requireNonNull(executingStep);
        final UUID obstructionId =
                Objects.requireNonNull(
                        placementObstructionEntityId
                );
        final Optional<RecentVisibleEntity> rememberedObstruction =
                frame.recentVisibleEntities().stream()
                        .filter(remembered ->
                                remembered.entity()
                                        .entityId()
                                        .equals(obstructionId))
                        .filter(remembered ->
                                DynamicShelterPlanner
                                        .visibleEntityIntersectsBlock(
                                                remembered,
                                                step.target()
                                        ))
                        .findFirst();
        final boolean entityStillObstructs =
                rememberedObstruction.isPresent();
        final boolean bodyStillObstructs =
                playerBodyIntersectsBlock(
                        core.position(),
                        step.target()
                );
        if (frame.observationRevision()
                        > placementRecoveryStartedRevision
                && !entityStillObstructs
                && !bodyStillObstructs) {
            coreActuator.orElseThrow().stop();
            MinecraftAiCompanion.LOGGER.info(
                    "Cleared visible shelter placement obstruction "
                            + "through ordinary movement "
                            + "planOrigin={} stepIndex={} target={} "
                            + "entityId={} attempts={}",
                    activePlan == null
                            ? null
                            : activePlan.origin(),
                    step.index(),
                    step.target(),
                    obstructionId,
                    placementRecoveryAttempts
            );
            clearPlacementObstructionRecovery();
            phase = Phase.AIMING;
            beginAim(context, frame);
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick()
                        - placementRecoveryStartedAtGameTick
                >= PLACEMENT_OBSTRUCTION_PUSH_TICKS) {
            coreActuator.orElseThrow().stop();
            final boolean obstructionReobservedAfterPush =
                    rememberedObstruction
                            .map(RecentVisibleEntity
                                    ::observationRevision)
                            .filter(revision ->
                                    revision
                                        > placementRecoveryStartedRevision)
                            .isPresent();
            if (!bodyStillObstructs
                    && !obstructionReobservedAfterPush) {
                MinecraftAiCompanion.LOGGER.info(
                        "Ordinary shelter obstruction push completed "
                                + "without a fresh target-facing entity "
                                + "sample; retrying vanilla placement "
                                + "instead of treating stale memory as "
                                + "collision proof planOrigin={} "
                                + "stepIndex={} target={} entityId={} "
                                + "attempt={}",
                        activePlan == null
                                ? null
                                : activePlan.origin(),
                        step.index(),
                        step.target(),
                        obstructionId,
                        placementRecoveryAttempts
                );
                clearPlacementObstructionRecovery();
                phase = Phase.AIMING;
                beginAim(context, frame);
                return SkillTickResult.running(true, true);
            }
            MinecraftAiCompanion.LOGGER.warn(
                    "Visible shelter placement obstruction recovery "
                            + "timed out planOrigin={} stepIndex={} "
                            + "target={} entityId={} bodyPosition={} "
                            + "destination={} entityStillObstructs={} "
                            + "bodyStillObstructs={}",
                    activePlan == null
                            ? null
                            : activePlan.origin(),
                    step.index(),
                    step.target(),
                    obstructionId,
                    core.position(),
                    placementPushDestination,
                    entityStillObstructs,
                    bodyStillObstructs
            );
            clearPlacementObstructionRecovery();
            return deferCurrentAimStep(context, parameters)
                    .orElseGet(() -> fail(
                            "build_shelter_step."
                                    + "placement_obstruction_persisted"
                    ));
        }
        final PerceptionVec3 destination =
                Objects.requireNonNull(
                        placementPushDestination
                );
        if (!placementPushCorridorStillSafe(frame)) {
            coreActuator.orElseThrow().stop();
            MinecraftAiCompanion.LOGGER.warn(
                    "Visible shelter placement obstruction corridor "
                            + "gained a fresh conflict planOrigin={} "
                            + "stepIndex={} target={} bodyPosition={} "
                            + "destination={} corridor={}",
                    activePlan == null
                            ? null
                            : activePlan.origin(),
                    step.index(),
                    step.target(),
                    core.position(),
                    placementPushDestination,
                    placementPushCorridor
            );
            clearPlacementObstructionRecovery();
            return deferCurrentAimStep(context, parameters)
                    .orElseGet(() -> fail(
                            "build_shelter_step."
                                    + "obstruction_route_changed"
                    ));
        }
        final double horizontalDistance =
                Math.hypot(
                        destination.x() - core.position().x(),
                        destination.z() - core.position().z()
                );
        if (horizontalDistance <= 0.45) {
            coreActuator.orElseThrow().stop();
            return SkillTickResult.running(false, false);
        }
        final PerceptionVec3 lookTarget =
                new PerceptionVec3(
                        destination.x(),
                        core.eyePosition().y(),
                        destination.z()
                );
        final PerceptionVec3 delta =
                lookTarget.subtract(core.eyePosition());
        final CoreSkillActuator actions =
                coreActuator.orElseThrow();
        final ActionOutcome looked =
                actions.look(lookAt(
                        core.eyePosition(),
                        lookTarget
                ));
        if (!looked.accepted()) {
            return fail(
                    "build_shelter_step."
                            + "obstruction_actuator_rejected"
            );
        }
        if (angularErrorDegrees(
                core.lookDirection(),
                delta
        ) > PLACEMENT_PUSH_ALIGNMENT_DEGREES) {
            actions.stop();
            return SkillTickResult.running(true, false);
        }
        final ActionOutcome moved = actions.move(
                new MovementIntent(
                        1.0,
                        0.0,
                        false,
                        false
                )
        );
        if (!moved.accepted()) {
            return fail(
                    "build_shelter_step."
                            + "obstruction_actuator_rejected"
            );
        }
        return SkillTickResult.running(true, false);
    }

    static Optional<PerceptionVec3> placementPushDestination(
            final ShelterFrame frame,
            final ShelterPlan plan,
            final ShelterBuildStep step
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        if (step.target().y() != plan.origin().y()) {
            return Optional.empty();
        }
        final double targetX = step.target().x() + 0.5;
        final double targetZ = step.target().z() + 0.5;
        double directionX =
                targetX - (frame.feet().x() + 0.5);
        double directionZ =
                targetZ - (frame.feet().z() + 0.5);
        double length = Math.hypot(directionX, directionZ);
        if (length <= 1.0E-6) {
            final double centreX = plan.origin().x()
                    + (plan.exteriorWidth() - 1) / 2.0;
            final double centreZ = plan.origin().z()
                    + (plan.exteriorDepth() - 1) / 2.0;
            directionX = targetX - centreX;
            directionZ = targetZ - centreZ;
            length = Math.hypot(directionX, directionZ);
        }
        if (length <= 1.0E-6) {
            directionZ = 1.0;
            length = 1.0;
        }
        final PerceptionVec3 destination =
                new PerceptionVec3(
                        targetX + directionX / length
                                * PLACEMENT_PUSH_DISTANCE,
                        plan.origin().y(),
                        targetZ + directionZ / length
                                * PLACEMENT_PUSH_DISTANCE
                );
        return observedPushCorridorSafe(
                frame,
                new PerceptionVec3(
                        frame.feet().x() + 0.5,
                        frame.feet().y(),
                        frame.feet().z() + 0.5
                ),
                destination,
                plan.origin().y()
        ) ? Optional.of(destination) : Optional.empty();
    }

    static boolean observedPushCorridorSafe(
            final ShelterFrame frame,
            final PerceptionVec3 start,
            final PerceptionVec3 destination,
            final int floorY
    ) {
        return observedPushCorridor(
                frame,
                start,
                destination,
                floorY
        ).isPresent();
    }

    private static Optional<List<GridPos>> observedPushCorridor(
            final ShelterFrame frame,
            final PerceptionVec3 start,
            final PerceptionVec3 destination,
            final int floorY
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(destination, "destination");
        final double deltaX = destination.x() - start.x();
        final double deltaZ = destination.z() - start.z();
        final int samples = Math.max(
                1,
                (int) Math.ceil(
                        Math.max(
                                Math.abs(deltaX),
                                Math.abs(deltaZ)
                        ) * 2.0
                )
        );
        final List<GridPos> corridor = new ArrayList<>();
        for (int sample = 0; sample <= samples; sample++) {
            final double fraction =
                    sample / (double) samples;
            final GridPos stand = new GridPos(
                    floorToGrid(start.x() + deltaX * fraction),
                    floorY,
                    floorToGrid(start.z() + deltaZ * fraction)
            );
            if (!isObservedSafeStand(frame, stand)) {
                return Optional.empty();
            }
            if (corridor.isEmpty()
                    || !corridor.getLast().equals(stand)) {
                corridor.add(stand);
            }
        }
        return Optional.of(List.copyOf(corridor));
    }

    private boolean placementPushCorridorStillSafe(
            final ShelterFrame frame
    ) {
        for (GridPos stand : placementPushCorridor) {
            final Optional<ObservedVoxel> feet =
                    frame.navigation().voxelAt(stand);
            final Optional<ObservedVoxel> head =
                    frame.navigation().voxelAt(stand.above());
            final Optional<ObservedVoxel> support =
                    frame.navigation().voxelAt(stand.below());
            if (freshTraversalConflict(feet)
                    || freshTraversalConflict(head)
                    || support.isPresent()
                            && support.orElseThrow()
                                    .observationRevision()
                                    > placementRecoveryStartedRevision
                            && (!support.orElseThrow()
                                    .kind()
                                    .supportsWeight()
                                    || support.orElseThrow()
                                            .effectiveDanger()
                                            > DynamicShelterPlanner
                                                    .MAXIMUM_SITE_DANGER)) {
                return false;
            }
        }
        return true;
    }

    private boolean freshTraversalConflict(
            final Optional<ObservedVoxel> observed
    ) {
        return observed.isPresent()
                && observed.orElseThrow()
                        .observationRevision()
                        > placementRecoveryStartedRevision
                && (!NavigationEvidence.hasTraversalClearance(
                        observed.orElseThrow()
                ) || observed.orElseThrow()
                        .effectiveDanger()
                        > DynamicShelterPlanner
                                .MAXIMUM_SITE_DANGER);
    }

    static boolean playerBodyIntersectsBlock(
            final PerceptionVec3 position,
            final GridPos block
    ) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(block, "block");
        return position.x() + PLAYER_COLLISION_HALF_WIDTH
                        > block.x()
                && position.x() - PLAYER_COLLISION_HALF_WIDTH
                        < block.x() + 1.0
                && position.y() + PLAYER_COLLISION_HEIGHT
                        > block.y()
                && position.y() < block.y() + 1.0
                && position.z() + PLAYER_COLLISION_HALF_WIDTH
                        > block.z()
                && position.z() - PLAYER_COLLISION_HALF_WIDTH
                        < block.z() + 1.0;
    }

    private boolean isFreshNoEffectPlacement(
            final ShelterFrame frame
    ) {
        if (executingStep == null
                || frame.observationRevision()
                        <= dispatchedObservationRevision
                || !dispatchedMainHandItem.equals(
                        frame.mainHand().itemId()
                )
                || dispatchedMainHandCount
                        != frame.mainHand().count()) {
            return false;
        }
        return frame.navigation()
                .voxelAt(executingStep.target())
                .filter(voxel ->
                        voxel.observationRevision()
                                > dispatchedObservationRevision)
                .map(ObservedVoxel::kind)
                .filter(kind -> kind == VoxelKind.AIR)
                .isPresent();
    }

    private void resetDispatchedPlacement() {
        dispatchedObservationRevision = -1;
        dispatchedAtGameTick = -1;
        dispatchedMainHandItem = "";
        dispatchedMainHandCount = -1;
        dispatchedActionCompleted = false;
        dispatchedSupportWasSolid = false;
    }

    private void clearPlacementObstructionRecovery() {
        placementObstructionEntityId = null;
        placementPushDestination = null;
        placementPushCorridor = List.of();
        placementRecoveryStartedAtGameTick = -1;
        placementRecoveryStartedRevision = -1;
        placementRepairSurveyAttempts = 0;
    }

    private SkillTickResult acceptConfirmedPlacement(
            final SkillContext context,
            final ShelterFrame frame
    ) {
        confirmed.set(executingStep.index());
        exploredActivePlanTraversalStands.clear();
        rejectedActivePlanTraversalStands.clear();
        activePlanTraversalDestinationWasExplored = false;
        returningInsideForRoof = false;
        activePlanTraversalRelocationAttempts = 0;
        clearPlacementObstructionRecovery();
        placementRecoveryAttempts = 0;
        unconfirmedPlacementRetries = 0;
        deferredAimSteps.clear(executingStep.index());
        attemptedRoofEdgeBridges.clear(executingStep.index());
        exhaustedExteriorRoofSteps.clear(executingStep.index());
        roofInteriorFallbackPriority =
                roofInteriorFallbackPriorityAfterPlacement(
                        Objects.requireNonNull(activePlan),
                        confirmed,
                        roofInteriorFallbackPriority
                );
        dispatchedActionCompleted = false;
        dispatchedSupportWasSolid = false;
        if (confirmed.cardinality()
                == Objects.requireNonNull(activePlan).steps().size()) {
            protectedStructures.completeShelter(
                    context.goalRevision(),
                    activePlan
            );
            shelterCompleted.accept(
                    context.goalRevision(),
                    activePlan
            );
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        final Optional<ShelterStepRole> nextRole = currentRole(
                activePlan,
                confirmed
        );
        if (nextRole.isEmpty()
                || !requiredItem(
                        activePlan,
                        nextRole.orElseThrow()
                ).equals(frame.mainHand().itemId())) {
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        Optional<StepTarget> next = selectReachableStep(
                frame,
                activePlan,
                excludingDeferred(
                        confirmed,
                        deferredAimSteps
                ),
                confirmed,
                nextRole.orElseThrow(),
                coreActuator.isPresent()
        );
        if (next.isEmpty()
                && !deferredAimSteps.isEmpty()) {
            deferredAimSteps.clear();
            next = selectReachableStep(
                    frame,
                    activePlan,
                    confirmed,
                    confirmed,
                    nextRole.orElseThrow(),
                    coreActuator.isPresent()
            );
        }
        if (next.isEmpty()) {
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        executingStep = next.orElseThrow().step();
        resetJumpAim(executingStep);
        interactionTarget = next.orElseThrow().target();
        abandonedAimSupports.clear();
        aimRepositionAttempts = 0;
        aimRecoverySurveyPerformed = false;
        attemptedAimVantages.clear();
        attemptedRoofObservationStands.clear();
        activeRoofApronSurveyStand = null;
        clearRoofApronRefresh();
        phase = placementReadyPhase();
        if (phase == Phase.AIMING) {
            beginAim(context, frame);
        }
        return SkillTickResult.running(true, true);
    }

    private void rememberCurrentBodyTransit(
            final SkillContext context,
            final BuildShelterStepParameters parameters
    ) {
        if (activePlan == null
                || !hasCurrentActivePlan(
                        context.goalRevision(),
                        boundSessionGeneration
                )
                || planSessionGeneration
                        != boundSessionGeneration) {
            return;
        }
        final ShelterFrame currentShelterFrame =
                frames.current().orElse(null);
        if (currentShelterFrame == null
                || currentShelterFrame.sessionGeneration()
                        != planSessionGeneration
                || !expectedPlayerId.equals(
                        currentShelterFrame.playerId()
                )
                || !parameters.dimension().equals(
                        currentShelterFrame.dimension()
                )) {
            return;
        }
        coreFrames.flatMap(CoreSkillFrameSource::current)
                .filter(frame ->
                        expectedPlayerId.equals(frame.playerId()))
                .filter(frame ->
                        parameters.dimension().equals(
                                frame.dimension()
                        ))
                .filter(CoreSkillFrame::onGround)
                .map(CoreSkillFrame::feet)
                .filter(feet ->
                        isExteriorRoofReturnPosition(
                                activePlan,
                                feet
                        ))
                .ifPresent(
                        bodyVerifiedActivePlanTransitStands::add
                );
    }

    @Override
    public SkillCheckpoint checkpoint(
            SkillContext context,
            BuildShelterStepParameters parameters
    ) {
        ShelterPlan plan = activePlan;
        String planId = plan == null ? "" : plan.planId();
        String origin = plan == null
                ? "null"
                : "[%d,%d,%d]".formatted(
                        plan.origin().x(),
                        plan.origin().y(),
                        plan.origin().z()
                );
        int stepIndex = executingStep == null
                ? -1
                : executingStep.index();
        final String relocation =
                activeRelocationParameters == null
                        ? "null"
                        : "[%.3f,%.3f,%.3f]".formatted(
                                activeRelocationParameters.x(),
                                activeRelocationParameters.y(),
                                activeRelocationParameters.z()
                        );
        final String aimReposition =
                activeRepositionParameters == null
                        ? "null"
                        : "[%.3f,%.3f,%.3f]".formatted(
                                activeRepositionParameters.x(),
                                activeRepositionParameters.y(),
                                activeRepositionParameters.z()
                        );
        return new SkillCheckpoint(
                1,
                """
                {"phase":"%s","planId":"%s","origin":%s,\
                "stepIndex":%d,"confirmed":%d,"goalRevision":%d,\
                "session":%d,"relocation":%s,\
                "aimReposition":%s,"aimRepositionAttempts":%d,\
                "aimVantagesTried":%d,"deferredAimSteps":%d,\
                "aimRepositionElapsedTicks":%d,\
                "aimRepositionTicksSinceProgress":%d,\
                "relocationPerformed":%s,\
                "initialSiteScale":"%s",\
                "initialSiteRelocations":%d,\
                "rejectedInitialSites":%d,\
                "activeTraversalAttempts":%d,\
                "activeTraversalStands":%d,\
                "bodyVerifiedTraversalStands":%d,\
                "activeTraversalKnownTransit":%s,\
                "rejectedTraversalStands":%d,\
                "exhaustedExteriorRoofSteps":%d,\
                "roofInteriorFallbackPriority":%d,\
                "returningInsideForRoof":%s,\
                "placementRecoveryAttempts":%d,\
                "placementRepairSurveyAttempts":%d,\
                "unconfirmedPlacementRetries":%d,\
                "avoidedPlacementTargets":%d,\
                "placementObstructionEntity":"%s"}
                """.formatted(
                        phase.name(),
                        planId,
                        origin,
                        stepIndex,
                        confirmed.cardinality(),
                        planGoalRevision,
                        boundSessionGeneration,
                        relocation,
                        aimReposition,
                        aimRepositionAttempts,
                        attemptedAimVantages.size(),
                        deferredAimSteps.cardinality(),
                        aimRepositionWatchdog.elapsedTicks(
                                context.gameTick()
                        ),
                        aimRepositionWatchdog.ticksSinceProgress(
                                context.gameTick()
                        ),
                        relocationPerformed,
                        initialSiteSearchScale == null
                                ? ""
                                : initialSiteSearchScale.name(),
                        initialSiteRelocationAttempts,
                        rejectedInitialSiteCenters.size(),
                        activePlanTraversalRelocationAttempts,
                        exploredActivePlanTraversalStands.size(),
                        bodyVerifiedActivePlanTransitStands.size(),
                        activePlanTraversalDestinationWasExplored,
                        rejectedActivePlanTraversalStands.size(),
                        exhaustedExteriorRoofSteps.cardinality(),
                        roofInteriorFallbackPriority,
                        returningInsideForRoof,
                        placementRecoveryAttempts,
                        placementRepairSurveyAttempts,
                        unconfirmedPlacementRetries,
                        avoidedPlacementTargets.size(),
                        placementObstructionEntityId == null
                                ? ""
                                : placementObstructionEntityId
                ).strip()
        );
    }

    @Override
    public void cancel(
            SkillContext context,
            BuildShelterStepParameters parameters
    ) {
        if ((phase == Phase.SURVEYING
                || phase == Phase.SURVEYING_PLACEMENT_REPAIR)
                && activeSurveyParameters != null
                && surveySkill.isPresent()) {
            surveySkill.orElseThrow().cancel(
                    context,
                    activeSurveyParameters
            );
        }
        if (phase == Phase.RELOCATING
                && activeRelocationParameters != null
                && relocationSkill.isPresent()) {
            relocationSkill.orElseThrow().cancel(
                    context,
                    activeRelocationParameters
            );
        }
        if (phase == Phase.REPOSITIONING_FOR_AIM
                && activeRepositionParameters != null
                && relocationSkill.isPresent()) {
            relocationSkill.orElseThrow().cancel(
                    context,
                    activeRepositionParameters
            );
        }
        if (phase == Phase.ROOF_EDGE_BRIDGING
                && activeRoofEdgeBridgeParameters != null
                && roofEdgeBridgeSkill.isPresent()) {
            roofEdgeBridgeSkill.orElseThrow().cancel(
                    context,
                    activeRoofEdgeBridgeParameters
            );
        }
        activeSurveyParameters = null;
        activeRelocationParameters = null;
        relocationArrivalWaitStartedAtGameTick = -1;
        activePlanTraversalDestinationWasExplored = false;
        activeRepositionParameters = null;
        activeRoofApronSurveyStand = null;
        clearRoofApronRefresh();
        activeRoofEdgeBridgeParameters = null;
        aimRepositionWatchdog.clear();
        clearPlacementObstructionRecovery();
        coreActuator.ifPresent(CoreSkillActuator::stop);
        releaseUseIfStillBound();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            SkillContext context,
            BuildShelterStepParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(
                    SkillFailure.of(
                            "build_shelter_step.invalid_state"
                    )
            );
        };
    }

    public Optional<ShelterPlan> activePlan() {
        return Optional.ofNullable(activePlan);
    }

    public int confirmedStepCount() {
        return confirmed.cardinality();
    }

    Optional<ShelterBuildStep> executingStepForDiagnostics() {
        return Optional.ofNullable(executingStep);
    }

    public boolean shelterComplete() {
        return activePlan != null
                && confirmed.cardinality() == activePlan.steps().size();
    }

    private boolean hasCurrentActivePlan(
            final long goalRevision,
            final long sessionGeneration
    ) {
        return activePlan != null
                && planGoalRevision == goalRevision
                && planSessionGeneration == sessionGeneration;
    }

    boolean bindInitialSiteSearch(
            final long goalRevision,
            final long sessionGeneration,
            final ShelterScale scale
    ) {
        Objects.requireNonNull(scale, "scale");
        if (initialSiteSearchGoalRevision == goalRevision
                && initialSiteSearchSessionGeneration
                        == sessionGeneration
                && initialSiteSearchScale == scale) {
            return false;
        }
        initialSiteSearchGoalRevision = goalRevision;
        initialSiteSearchSessionGeneration = sessionGeneration;
        initialSiteSearchScale = scale;
        initialSiteRelocationAttempts = 0;
        rejectedInitialSiteCenters.clear();
        return true;
    }

    private void clearInitialSiteSearch() {
        initialSiteSearchGoalRevision = -1;
        initialSiteSearchSessionGeneration = -1;
        initialSiteSearchScale = null;
        initialSiteRelocationAttempts = 0;
        rejectedInitialSiteCenters.clear();
    }

    static boolean initialSiteRelocationAvailable(
            final int completedAttempts
    ) {
        return completedAttempts >= 0
                && completedAttempts
                        < MAXIMUM_INITIAL_SITE_RELOCATIONS;
    }

    static int maximumActivePlanTraversalRelocations(
            final ShelterPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        /*
         * The exterior apron is the perimeter of a rectangle two cells
         * wider and deeper than the shell. One complete circuit discovers
         * every frontier, but an interrupted observation graph can require
         * walking back across already verified transit cells to reach the
         * remaining frontier. Two perimeter lengths are still a strict local
         * bound and prevent the former 24-hop budget from expiring merely
         * because a safe cell had to be revisited.
         */
        final int apronPerimeter = 2 * (
                plan.exteriorWidth()
                        + plan.exteriorDepth()
        ) + 4;
        return Math.multiplyExact(apronPerimeter, 2);
    }

    static boolean activePlanTraversalRelocationAvailable(
            final int completedAttempts,
            final ShelterPlan plan
    ) {
        return completedAttempts >= 0
                && completedAttempts
                        < maximumActivePlanTraversalRelocations(plan);
    }

    private Preparation prepare(
            BuildShelterStepParameters parameters,
            long goalRevision
    ) {
        return prepare(
                parameters,
                goalRevision,
                PreparationAdmission.EXTERNAL_MODEL_DECISION
        );
    }

    private Preparation prepare(
            BuildShelterStepParameters parameters,
            long goalRevision,
            PreparationAdmission admission
    ) {
        Objects.requireNonNull(admission, "admission");
        FrameValidation validation = validateFrame(
                parameters,
                -1,
                admission.requiresExactAuthoredSample()
        );
        if (validation.failure().isPresent()) {
            return Preparation.failed(
                    validation.failure().orElseThrow()
            );
        }
        ShelterFrame frame = validation.frame().orElseThrow();
        ShelterPlan plan = activePlan;
        if (plan != null
                && (planGoalRevision != goalRevision
                || planSessionGeneration
                != frame.sessionGeneration())) {
            plan = null;
        }
        if (plan == null) {
            if (surveySkill.isPresent()
                    && (initialSiteSurveyGoalRevision
                                != goalRevision
                            || initialSiteSurveySessionGeneration
                                != frame.sessionGeneration())) {
                return Preparation.survey(frame);
            }
            ShelterPlanningResult planning = planner.plan(
                    frame,
                    parameters.scale()
            );
            if (planning.failure().isPresent()) {
                final SkillFailure reason =
                        planning.failure().orElseThrow();
                if (surveySkill.isPresent()
                        && ("shelter.insufficient_observation".equals(
                                reason.code()
                        ) || "shelter.no_safe_footprint".equals(
                                reason.code()
                        ))) {
                    return Preparation.survey(frame);
                }
                return Preparation.failed(
                        reason
                );
            }
            plan = planning.plan().orElseThrow();
        } else {
            if (!plan.dimension().equals(parameters.dimension())) {
                return Preparation.failed(
                        "build_shelter_step.dimension_mismatch"
                );
            }
            /*
             * Scale is an initial planning preference, not permission to
             * replace an in-progress physical transaction. A later model
             * response can legitimately be compressed or paraphrased and
             * choose another enum value; continuing the persisted plan keeps
             * already placed blocks and inventory consumption authoritative.
             */
        }

        /*
         * Preconditions for a replacement goal/session must evaluate the new
         * plan with a fresh confirmation set. The old set is retained until
         * start() commits the replacement, so a rejected precondition cannot
         * corrupt the currently active plan.
         */
        final BitSet planConfirmed =
                plan == activePlan ? confirmed : new BitSet();
        Optional<SkillFailure> conflict = validateConstruction(
                frame,
                plan,
                planConfirmed
        );
        if (conflict.isPresent()) {
            return Preparation.failed(conflict.orElseThrow());
        }
        Optional<ShelterStepRole> role = currentRole(
                plan,
                planConfirmed
        );
        if (role.isEmpty()) {
            return Preparation.complete(frame, plan);
        }
        ShelterStepRole requiredRole = role.orElseThrow();
        String requiredItem = requiredItem(plan, requiredRole);
        Optional<EquipItemParameters> equipment = Optional.empty();
        if (!requiredItem.equals(frame.mainHand().itemId())
                || frame.mainHand().count() < 1) {
            if (inventoryActuator.isEmpty()) {
                return Preparation.failed(equipFailure(requiredRole));
            }
            final EquipItemParameters equip =
                    new EquipItemParameters(
                            requiredItem,
                            EquipmentTarget.MAINHAND
                    );
            final InventoryOperationResult check =
                    inventoryActuator.orElseThrow().checkEquip(equip);
            if (!check.succeeded()) {
                return Preparation.failed(
                        check.failure().orElseThrow()
                );
            }
            equipment = Optional.of(equip);
        }

        Optional<StepTarget> selected = selectReachableStep(
                frame,
                plan,
                plan == activePlan
                        ? excludingDeferred(
                                planConfirmed,
                                deferredAimSteps
                        )
                        : planConfirmed,
                planConfirmed,
                requiredRole,
                coreActuator.isPresent()
        );
        if (selected.isEmpty()
                && plan == activePlan
                && !deferredAimSteps.isEmpty()) {
            /*
             * A new skill invocation is one bounded retry after the local
             * executor already worked every other reachable step.
             */
            deferredAimSteps.clear();
            selected = selectReachableStep(
                    frame,
                    plan,
                    planConfirmed,
                    planConfirmed,
                    requiredRole,
                    coreActuator.isPresent()
            );
        }
        if (selected.isEmpty()) {
            if (surveySkill.isPresent()) {
                return Preparation.surveyForStep(frame);
            }
            return Preparation.failed(
                    "build_shelter_step.no_visible_build_step"
            );
        }
        StepTarget target = selected.orElseThrow();
        return Preparation.ready(
                frame,
                plan,
                target.step(),
                target.target(),
                equipment
        );
    }

    private static SurveySurroundingsParameters surveyParameters(
            BuildShelterStepParameters parameters
    ) {
        return new SurveySurroundingsParameters(
                parameters.dimension(),
                8,
                true
        );
    }

    private static SurveySurroundingsParameters
            activePlanTraversalSurveyParameters(
                    BuildShelterStepParameters parameters
            ) {
        /*
         * A newly reached roof frontier needs vertical support evidence, but
         * four cardinal headings already cover the local exterior ring. The
         * initial site survey remains the denser eight-heading panorama.
         */
        return new SurveySurroundingsParameters(
                parameters.dimension(),
                4,
                true
        );
    }

    private static SurveySurroundingsParameters
            placementRepairSurveyParameters(
                    BuildShelterStepParameters parameters
            ) {
        return new SurveySurroundingsParameters(
                parameters.dimension(),
                16,
                true
        );
    }

    static boolean recoverableRepairObservationFailure(
            final String code
    ) {
        return "shelter.insufficient_observation".equals(code)
                || "shelter.no_safe_footprint".equals(code);
    }

    private static Optional<StepTarget> selectReachableStep(
            final ShelterFrame frame,
            final ShelterPlan plan,
            final BitSet selectionExcluded,
            final BitSet dependencyConfirmed,
            final ShelterStepRole requiredRole,
            final boolean allowRememberedSupport
    ) {
        final int requiredConstructionPriority = plan.steps().stream()
                .filter(step ->
                        !dependencyConfirmed.get(step.index()))
                .filter(step -> step.role() == requiredRole)
                .mapToInt(step -> constructionPriority(plan, step))
                .min()
                .orElse(Integer.MAX_VALUE);
        /*
         * A currently visible support is actionable evidence; an old
         * navigation voxel is only an aiming hint.  Never let the smallest
         * plan index backed by memory hide another pending block that the
         * body can actually see and click now.  Construction order remains
         * generated from the same plan, but follows the surfaces available
         * from the body's present first-person position.
         */
        final Optional<StepTarget> visible = plan.steps().stream()
                .filter(step ->
                        !selectionExcluded.get(step.index()))
                .filter(step -> step.role() == requiredRole)
                .filter(step ->
                        constructionPriority(plan, step)
                                == requiredConstructionPriority)
                .map(step -> resolveTarget(frame, step))
                .flatMap(Optional::stream)
                .min(Comparator
                        .comparingDouble((StepTarget target) ->
                                matchingFaceDistance(
                                        frame.visibleBlockFaces(),
                                        target.target()
                                ))
                        .thenComparingInt(target ->
                                target.step().index()));
        if (visible.isPresent() || !allowRememberedSupport) {
            return visible;
        }
        final Optional<StepTarget> remembered = plan.steps().stream()
                .filter(step ->
                        !selectionExcluded.get(step.index()))
                .filter(step -> step.role() == requiredRole)
                .filter(step ->
                        constructionPriority(plan, step)
                                == requiredConstructionPriority)
                .map(step -> rememberedTarget(frame, step))
                .flatMap(Optional::stream)
                .min(Comparator.comparingInt(target ->
                        target.step().index()));
        if (remembered.isPresent()) {
            return remembered;
        }
        /*
         * The planner already observed and validated every foundation cell
         * when it fixed this immutable plan. Retain that provenance as an
         * aiming hint for the final door/light after roof traversal has rolled
         * the original cell out of the local navigation window. A fresh
         * centre ray is still mandatory before the vanilla interaction.
         */
        return plan.steps().stream()
                .filter(step ->
                        !selectionExcluded.get(step.index()))
                .filter(step -> step.role() == requiredRole)
                .filter(step ->
                        constructionPriority(plan, step)
                                == requiredConstructionPriority)
                .map(step -> plannedFunctionalTarget(plan, step)
                        .map(target -> new StepTarget(step, target)))
                .flatMap(Optional::stream)
                .min(Comparator.comparingInt(target ->
                        target.step().index()));
    }

    static int constructionPriority(
            final ShelterPlan plan,
            final ShelterBuildStep step
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        final int maximumX = plan.origin().x()
                + plan.exteriorWidth() - 1;
        final int maximumZ = plan.origin().z()
                + plan.exteriorDepth() - 1;
        if (step.role() == ShelterStepRole.ROOF) {
            /*
             * Complete each generated ring before moving inward. From an
             * interior stance, the open target cell exposes the inward face
             * of the completed outer ring, exactly like a player filling a
             * roof from its perimeter toward the centre. This is derived
             * from the current plan dimensions, not a stored block blueprint.
             */
            return Math.min(
                    Math.min(
                            step.target().x() - plan.origin().x(),
                            maximumX - step.target().x()
                    ),
                    Math.min(
                            step.target().z() - plan.origin().z(),
                            maximumZ - step.target().z()
                    )
            );
        }
        if (step.role() != ShelterStepRole.LOWER_WALL
                && step.role() != ShelterStepRole.UPPER_WALL) {
            return 0;
        }
        final boolean xCorner = step.target().x()
                == plan.origin().x()
                || step.target().x() == maximumX;
        final boolean zCorner = step.target().z()
                == plan.origin().z()
                || step.target().z() == maximumZ;
        return xCorner && zCorner ? 0 : 1;
    }

    static Optional<BlockInteractionTarget> plannedFunctionalTarget(
            final ShelterPlan plan,
            final ShelterBuildStep step
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        final boolean plannedDoor =
                step.role() == ShelterStepRole.DOOR
                        && step.target().equals(plan.doorLower());
        final boolean plannedLight =
                step.role() == ShelterStepRole.LIGHT
                        && step.target().equals(plan.lightPosition());
        if (!plannedDoor && !plannedLight) {
            return Optional.empty();
        }
        final GridPos support = step.target().below();
        return Optional.of(new BlockInteractionTarget(
                support.x(),
                support.y(),
                support.z(),
                BlockFace.UP,
                faceCenter(support, BlockFace.UP)
        ));
    }

    static BitSet excludingDeferred(
            final BitSet confirmedSteps,
            final BitSet deferredSteps
    ) {
        Objects.requireNonNull(
                confirmedSteps,
                "confirmedSteps"
        );
        Objects.requireNonNull(
                deferredSteps,
                "deferredSteps"
        );
        final BitSet excluded =
                (BitSet) confirmedSteps.clone();
        excluded.or(deferredSteps);
        return excluded;
    }

    private FrameValidation validateFrame(
            BuildShelterStepParameters parameters,
            long boundSession,
            boolean requireExactSample
    ) {
        Optional<ShelterFrame> current = frames.current();
        if (current.isEmpty()) {
            return FrameValidation.failed(
                    "build_shelter_step.observation_unavailable"
            );
        }
        ShelterFrame frame = current.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return FrameValidation.failed(
                    "build_shelter_step.player_mismatch"
            );
        }
        if (!parameters.dimension().equals(frame.dimension())) {
            return FrameValidation.failed(
                    "build_shelter_step.dimension_mismatch"
            );
        }
        if (requireExactSample) {
            final Optional<ShelterFrame> authored =
                    frames.atObservation(
                            parameters.sampleSequence()
                    );
            if (authored.isEmpty()) {
                return FrameValidation.failed(
                        "build_shelter_step.observation_expired"
                );
            }
            final ShelterFrame retained = authored.orElseThrow();
            if (!expectedPlayerId.equals(retained.playerId())
                    || !parameters.dimension().equals(
                            retained.dimension()
                    )
                    || retained.sessionGeneration()
                    != frame.sessionGeneration()) {
                return FrameValidation.failed(
                        "build_shelter_step.observation_expired"
                );
            }
        }
        if (frame.observationAgeTicks()
                > MAXIMUM_OBSERVATION_AGE_TICKS) {
            return FrameValidation.failed(
                    "build_shelter_step.stale_observation"
            );
        }
        OptionalLong actuatorSession = actuator.sessionGeneration();
        if (actuatorSession.isEmpty()
                || actuatorSession.orElseThrow()
                != frame.sessionGeneration()
                || boundSession >= 0
                && boundSession != frame.sessionGeneration()) {
            return FrameValidation.failed(
                    "build_shelter_step.session_mismatch"
            );
        }
        return FrameValidation.valid(frame);
    }

    private Optional<SkillFailure> validateConstruction(
            ShelterFrame frame,
            ShelterPlan plan,
            BitSet planConfirmed
    ) {
        for (ShelterBuildStep step : plan.steps()) {
            Optional<ObservedVoxel> observed = frame.navigation()
                    .voxelAt(step.target());
            if (planConfirmed.get(step.index())) {
                if (confirmedPlacementContradicted(
                        frame,
                        plan,
                        step
                )) {
                    MinecraftAiCompanion.LOGGER.warn(
                            "Confirmed shelter placement contradicted by "
                                    + "direct current evidence "
                                    + "planOrigin={} stepIndex={} role={} "
                                    + "target={} expected={} voxel={} "
                                    + "targetFaces={} frameRevision={} "
                                    + "planSourceRevision={} feet={}",
                            plan.origin(),
                            step.index(),
                            step.role(),
                            step.target(),
                            requiredItem(plan, step.role()),
                            observed.orElse(null),
                            frame.visibleBlockFaces().stream()
                                    .filter(face ->
                                            face.block().x()
                                                    == step.target().x()
                                            && face.block().y()
                                                    == step.target().y()
                                            && face.block().z()
                                                    == step.target().z())
                                    .toList(),
                            frame.observationRevision(),
                            plan.sourceRevision(),
                            frame.feet()
                    );
                    return Optional.of(SkillFailure.of(
                            "build_shelter_step.completed_block_missing"
                    ));
                }
                continue;
            }
            if (observed.isPresent()
                    && observed.orElseThrow().observationRevision()
                    > plan.sourceRevision()
                    && observed.orElseThrow().kind()
                    != VoxelKind.AIR) {
                return Optional.of(SkillFailure.of(
                        "build_shelter_step.plan_conflict"
                ));
            }
        }
        if (!planConfirmed.get(doorStep(plan).index())) {
            Optional<ObservedVoxel> upper = frame.navigation().voxelAt(
                    plan.doorUpper()
            );
            if (upper.isPresent()
                    && upper.orElseThrow().observationRevision()
                    > plan.sourceRevision()
                    && upper.orElseThrow().kind() != VoxelKind.AIR) {
                return Optional.of(SkillFailure.of(
                        "build_shelter_step.plan_conflict"
                ));
            }
        }
        return Optional.empty();
    }

    /**
     * Detects a real contradiction without confusing navigation passability
     * with block existence.
     *
     * <p>Navigation AIR is deliberately only heuristic when it came from
     * clear sight rays: even multiple infinitesimal rays are not a full-block
     * occupancy fact. They therefore cannot revoke an already causal vanilla
     * placement receipt. A directly ray-hit different block at the exact
     * target contradicts every role. For a structural block, the player's
     * own body occupying that exact cell is also direct missing-block proof.
     * Doors, torches, and wall torches are partial/collisionless blocks, so
     * body occupancy is not a contradiction for those roles.</p>
     */
    static boolean confirmedPlacementContradicted(
            final ShelterFrame frame,
            final ShelterPlan plan,
            final ShelterBuildStep step
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        final List<VisibleBlockFace> targetFaces =
                frame.visibleBlockFaces().stream()
                        .filter(face ->
                                face.block().x() == step.target().x()
                                        && face.block().y()
                                        == step.target().y()
                                        && face.block().z()
                                        == step.target().z())
                        .toList();
        if (targetFaces.isEmpty()) {
            if (!step.role().usesStructuralMaterial()) {
                return false;
            }
            return frame.navigation().voxelAt(step.target())
                    .filter(voxel ->
                            voxel.observationRevision()
                                    > plan.sourceRevision())
                    .filter(voxel ->
                            voxel.kind() == VoxelKind.AIR)
                    .map(ObservedVoxel::occupancyEvidence)
                    .filter(evidence ->
                            evidence.isFullBodyFact())
                    .isPresent();
        }
        final String expected = requiredItem(
                plan,
                step.role()
        );
        return targetFaces.stream().noneMatch(face ->
                blockMatches(
                        step.role(),
                        expected,
                        face.blockTypeId()
                ));
    }

    private static Optional<ShelterStepRole> currentRole(
            ShelterPlan plan,
            BitSet planConfirmed
    ) {
        for (ShelterStepRole role : ShelterStepRole.values()) {
            boolean pending = plan.steps().stream().anyMatch(step ->
                    step.role() == role
                            && !planConfirmed.get(step.index())
            );
            if (pending) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    private static String requiredItem(
            ShelterPlan plan,
            ShelterStepRole role
    ) {
        if (role.usesStructuralMaterial()) {
            return plan.structuralItemId();
        }
        return role == ShelterStepRole.DOOR
                ? plan.doorItemId()
                : plan.lightItemId();
    }

    private static String equipFailure(ShelterStepRole role) {
        if (role.usesStructuralMaterial()) {
            return "build_shelter_step.equip_material";
        }
        return role == ShelterStepRole.DOOR
                ? "build_shelter_step.equip_door"
                : "build_shelter_step.equip_light";
    }

    private static Optional<StepTarget> resolveTarget(
            ShelterFrame frame,
            ShelterBuildStep step
    ) {
        if (step.role() == ShelterStepRole.DOOR) {
            Optional<ObservedVoxel> upper = frame.navigation().voxelAt(
                    step.target().above()
            );
            if (upper.isEmpty()
                    || upper.orElseThrow().kind() != VoxelKind.AIR) {
                return Optional.empty();
            }
        }
        return preferredVisibleSupport(frame, step)
                .map(target -> new StepTarget(step, target));
    }

    static Optional<BlockInteractionTarget> preferredVisibleSupport(
            final ShelterFrame frame,
            final ShelterBuildStep step
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(step, "step");
        return frame.visibleBlockFaces().stream()
                .filter(face -> face.distance() <= MAXIMUM_BUILD_REACH)
                .filter(face -> step.role().usesStructuralMaterial()
                        || face.face().equals("up")
                        && face.block().x() == step.target().x()
                        && face.block().y() == step.target().y() - 1
                        && face.block().z() == step.target().z())
                .filter(face -> adjacent(face).equals(step.target()))
                .map(BuildShelterStepSkill::toTarget)
                .flatMap(Optional::stream)
                .filter(target ->
                        PlacementSupportPreference.rank(
                                step.role(),
                                target.face()
                        ) != Integer.MAX_VALUE)
                .min(Comparator
                        .comparingInt((BlockInteractionTarget target) ->
                                PlacementSupportPreference.rank(
                                        step.role(),
                                        target.face()
                                ))
                        .thenComparingDouble(target ->
                                matchingFaceDistance(
                                        frame.visibleBlockFaces(),
                                        target
                                )));
    }

    /**
     * Produces only an aiming hint from fairly remembered voxel evidence.
     * The hint is never sent to the vanilla interaction actuator directly:
     * {@link #tickAim(SkillContext, BuildShelterStepParameters, boolean)}
     * first turns the head, waits for a newer first-person sample, and
     * replaces it with the exact outline hit from that sample.
     */
    static Optional<StepTarget> rememberedTarget(
            final ShelterFrame frame,
            final ShelterBuildStep step
    ) {
        final Optional<ObservedVoxel> target =
                frame.navigation().voxelAt(step.target());
        if (target.isEmpty()
                || target.orElseThrow().kind() != VoxelKind.AIR) {
            return Optional.empty();
        }
        if (step.role() == ShelterStepRole.DOOR) {
            final Optional<ObservedVoxel> upper =
                    frame.navigation().voxelAt(step.target().above());
            if (upper.isEmpty()
                    || upper.orElseThrow().kind() != VoxelKind.AIR) {
                return Optional.empty();
            }
        }

        /*
         * A remembered support is only an aiming hint. The exact centre ray
         * must still see this face before vanilla receives a use action.
         */
        final List<BlockFace> allowedFaces =
                step.role().usesStructuralMaterial()
                        ? PlacementSupportPreference.orderedFaces(
                                step.role()
                        )
                        : List.of(BlockFace.UP);
        for (BlockFace face : allowedFaces) {
            final Optional<StepTarget> support = rememberedSupport(
                    frame,
                    step,
                    PlacementSupportPreference.support(
                            step.target(),
                            face
                    ),
                    face
            );
            if (support.isPresent()) {
                return support;
            }
        }
        return Optional.empty();
    }

    private static Optional<StepTarget> rememberedSupport(
            final ShelterFrame frame,
            final ShelterBuildStep step,
            final GridPos support,
            final BlockFace face
    ) {
        final Optional<ObservedVoxel> observed =
                frame.navigation().voxelAt(support);
        if (observed.isEmpty()
                || !observed.orElseThrow().kind().supportsWeight()) {
            return Optional.empty();
        }
        final ActionVec3 hit = faceCenter(support, face);
        final double eyeX = frame.feet().x() + 0.5;
        final double eyeY = frame.feet().y() + 1.62;
        final double eyeZ = frame.feet().z() + 0.5;
        final double x = hit.x() - eyeX;
        final double y = hit.y() - eyeY;
        final double z = hit.z() - eyeZ;
        if (x * x + y * y + z * z
                > MAXIMUM_BUILD_REACH * MAXIMUM_BUILD_REACH) {
            return Optional.empty();
        }
        return Optional.of(new StepTarget(
                step,
                new BlockInteractionTarget(
                        support.x(),
                        support.y(),
                        support.z(),
                        face,
                        hit
                )
        ));
    }

    private static ActionVec3 faceCenter(
            final GridPos block,
            final BlockFace face
    ) {
        return switch (face) {
            case DOWN -> new ActionVec3(
                    block.x() + 0.5,
                    block.y(),
                    block.z() + 0.5
            );
            case UP -> new ActionVec3(
                    block.x() + 0.5,
                    block.y() + 1.0,
                    block.z() + 0.5
            );
            case NORTH -> new ActionVec3(
                    block.x() + 0.5,
                    block.y() + 0.5,
                    block.z()
            );
            case SOUTH -> new ActionVec3(
                    block.x() + 0.5,
                    block.y() + 0.5,
                    block.z() + 1.0
            );
            case WEST -> new ActionVec3(
                    block.x(),
                    block.y() + 0.5,
                    block.z() + 0.5
            );
            case EAST -> new ActionVec3(
                    block.x() + 1.0,
                    block.y() + 0.5,
                    block.z() + 0.5
            );
        };
    }

    private Phase placementReadyPhase() {
        return coreActuator.isPresent() && coreFrames.isPresent()
                ? Phase.AIMING
                : Phase.READY;
    }

    private void beginAim(
            final SkillContext context,
            final ShelterFrame frame
    ) {
        aimStartedAtGameTick = context.gameTick();
        aimStartedObservationRevision =
                frame.observationRevision();
        aimProgress = AimProgress.WAITING_ALIGNMENT;
    }

    /**
     * Opens a closed shelter door that is physically occluding a pending roof
     * support.  This is deliberately an ordinary first-person use action: the
     * block remains authoritative and the next semantic frame must still show
     * the roof face before a placement is dispatched.  We never close a door
     * here and we never mutate its block state directly.
     */
    private Optional<SkillTickResult> openOccludingDoor(
            final SkillContext context,
            final ShelterFrame frame,
            final VisibleBlockFace face
    ) {
        if (executingStep == null
                || executingStep.role() != ShelterStepRole.ROOF
                || !face.blockTypeId().endsWith("_door")
                || !"false".equals(
                        face.stateProperties().get("open")
                )) {
            return Optional.empty();
        }
        final GridPos door = new GridPos(
                face.block().x(),
                face.block().y(),
                face.block().z()
        );
        if (!openedOccludingDoors.add(door)) {
            return Optional.empty();
        }
        final Optional<BlockInteractionTarget> target = toTarget(face);
        if (target.isEmpty()) {
            openedOccludingDoors.remove(door);
            return Optional.empty();
        }
        final ActionOutcome opened = actuator.useOnBlock(
                ActionHand.MAIN_HAND,
                target.orElseThrow()
        );
        if (!opened.accepted()) {
            openedOccludingDoors.remove(door);
            return Optional.empty();
        }
        coreActuator.ifPresent(CoreSkillActuator::stop);
        beginAim(context, frame);
        MinecraftAiCompanion.LOGGER.info(
                "Opened closed shelter door that occluded roof aim "
                        + "planOrigin={} stepIndex={} door={} outcome={}",
                activePlan == null ? null : activePlan.origin(),
                executingStep.index(),
                door,
                opened
        );
        return Optional.of(
                SkillTickResult.running(true, true)
        );
    }

    /**
     * Converts a peripheral fair ray into the same centre-crosshair action a
     * real client would perform. The actuator is intentionally allowed to
     * reject stale or occluded targets; no block placement packet is sent
     * until a newer semantic frame both aligns the head and re-observes the
     * exact supporting face.
     */
    private SkillTickResult tickAim(
            final SkillContext context,
            final BuildShelterStepParameters parameters,
            final boolean holdSneak
    ) {
        final Optional<CoreSkillFrame> maybeCore =
                coreFrames.orElseThrow().current();
        if (maybeCore.isEmpty()) {
            return fail("build_shelter_step.aim_pose_unavailable");
        }
        final CoreSkillFrame core = maybeCore.orElseThrow();
        if (!expectedPlayerId.equals(core.playerId())
                || !parameters.dimension().equals(core.dimension())) {
            return fail("build_shelter_step.aim_pose_mismatch");
        }
        final FrameValidation validated = validateFrame(
                parameters,
                boundSessionGeneration,
                false
        );
        if (validated.failure().isPresent()) {
            return fail(validated.failure().orElseThrow());
        }
        final ShelterFrame frame = validated.frame().orElseThrow();
        final Optional<SkillTickResult> rebound =
                recoverMismatchedInteractionTarget(
                        context,
                        parameters,
                        frame
                );
        if (rebound.isPresent()) {
            return rebound.orElseThrow();
        }
        final Optional<SkillTickResult> roofEdgeBridge =
                startRoofEdgeBridge(
                        context,
                        parameters,
                        core,
                        frame
                );
        if (roofEdgeBridge.isPresent()) {
            return roofEdgeBridge.orElseThrow();
        }
        final Optional<BlockInteractionTarget> visibleRetarget =
                visibleRetargetForCurrentStep(
                        frame,
                        Objects.requireNonNull(executingStep),
                        Objects.requireNonNull(interactionTarget),
                        abandonedAimSupports
                );
        if (visibleRetarget.isPresent()) {
            final BlockInteractionTarget previous =
                    interactionTarget;
            abandonedAimSupports.add(
                    PlacementSupportIdentity.from(previous)
            );
            interactionTarget = visibleRetarget.orElseThrow();
            aimRepositionAttempts = 0;
            attemptedAimVantages.clear();
            attemptedRoofObservationStands.clear();
            activeRoofApronSurveyStand = null;
            clearRoofApronRefresh();
            aimRepositionWatchdog.clear();
            beginAim(context, frame);
            MinecraftAiCompanion.LOGGER.info(
                    "Retargeted shelter placement to newly visible support "
                            + "planOrigin={} stepIndex={} previous={} next={}",
                    activePlan == null ? null : activePlan.origin(),
                    executingStep.index(),
                    previous,
                    interactionTarget
            );
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - aimStartedAtGameTick
                >= AIM_TIMEOUT_TICKS) {
            final VisibleBlockFace crosshair =
                    frames.currentCrosshairBlock().orElse(null);
            MinecraftAiCompanion.LOGGER.warn(
                    "Shelter centre-aim timeout state={} expected={} "
                            + "posePosition={} poseEye={} poseLook={} "
                            + "crosshair={} planOrigin={} stepTarget={} "
                            + "crosshairAdjacent={} adjacentVoxel={} "
                            + "adjacentPlanStep={}",
                    aimProgress.failureSuffix(),
                    interactionTarget,
                    core.position(),
                    core.eyePosition(),
                    core.lookDirection(),
                    crosshair,
                    activePlan == null ? null : activePlan.origin(),
                    executingStep == null
                            ? null
                            : executingStep.target(),
                    crosshair == null ? null : adjacent(crosshair),
                    crosshair == null
                            ? null
                            : frameVoxelAtCrosshairAdjacent(crosshair),
                    crosshair == null
                            ? null
                            : planStepAtCrosshairAdjacent(crosshair)
            );
            if (activePlan != null
                    && executingStep != null
                    && aimTimeoutRepositionAvailable(
                            activePlan,
                            executingStep,
                            aimRepositionAttempts
                    )) {
                final Optional<SkillTickResult> repositioned =
                        startAimReposition(
                                context,
                                parameters,
                                frame,
                                Objects.requireNonNull(
                                        interactionTarget
                                )
                        );
                if (repositioned.isPresent()) {
                    MinecraftAiCompanion.LOGGER.info(
                            "Recovering shelter aim timeout from another "
                                    + "observed stance planOrigin={} "
                                    + "stepIndex={} attempt={}",
                            activePlan.origin(),
                            executingStep.index(),
                            aimRepositionAttempts
                    );
                    return repositioned.orElseThrow();
                }
            }
            final Optional<SkillTickResult> deferred =
                    deferCurrentAimStep(
                            context,
                            parameters
                    );
            if (deferred.isPresent()) {
                return deferred.orElseThrow();
            }
            return fail("build_shelter_step.aim_timeout_"
                    + aimProgress.failureSuffix());
        }
        final BlockInteractionTarget target =
                Objects.requireNonNull(interactionTarget);
        final PerceptionVec3 targetPoint = new PerceptionVec3(
                target.hitPoint().x(),
                target.hitPoint().y(),
                target.hitPoint().z()
        );
        final PerceptionVec3 delta =
                targetPoint.subtract(core.eyePosition());
        if (delta.lengthSquared() <= 1.0E-12) {
            return fail("build_shelter_step.aim_invalid");
        }
        final CoreSkillActuator coreActions =
                coreActuator.orElseThrow();
        final ActionOutcome stopped = holdSneak
                ? coreActions.move(new MovementIntent(
                        0.0,
                        0.0,
                        false,
                        true
                ))
                : coreActions.stop();
        final ActionOutcome looked =
                coreActions.look(lookAt(core.eyePosition(), targetPoint));
        if (!stopped.accepted() || !looked.accepted()) {
            return fail("build_shelter_step.aim_rejected");
        }
        final double error = angularErrorDegrees(
                core.lookDirection(),
                delta
        );
        final boolean trackingAirborneJump =
                airborneJumpAim(core, target);
        if (!aimAlignmentSatisfied(
                error,
                trackingAirborneJump
        )) {
            aimProgress = AimProgress.WAITING_ALIGNMENT;
            return SkillTickResult.running(true, true);
        }
        /*
         * The first roof ring of a compact shelter is two blocks above the
         * interior floor. At ordinary standing eye height, a ray aimed at
         * the supporting wall's top centre necessarily enters a side face
         * first; walking elsewhere on that floor cannot change the vertical
         * geometry. A player solves this by jump-placing. Queue the same
         * vanilla jump input only after the head is aligned, preserve normal
         * airborne physics on later ticks, and wait until the body's own eye
         * is observed above the top plane before accepting the ray.
         */
        if (requiresJumpToSeePlacementFace(
                core.eyePosition().y(),
                target,
                core.feet(),
                Objects.requireNonNull(executingStep).target()
        )) {
            bindJumpAimStep();
            /*
             * A confirmed roof block two cells above the body's feet leaves
             * normal standing clearance but physically prevents a jump.
             * The same is true for any non-passable cell already present in
             * the fair navigation map. Do not spend four deterministic jump
             * attempts under that ceiling: walk through the ordinary
             * movement skill to an observed stand with a third clear cell,
             * then reacquire the face from a fresh first-person sample.
             */
            if (core.onGround()
                    && jumpHeadroomBlocked(frame, core.feet())) {
                final Optional<SkillTickResult> repositioned =
                        startAimReposition(
                                context,
                                parameters,
                                frame,
                                target,
                                true
                        );
                if (repositioned.isPresent()) {
                    return repositioned.orElseThrow();
                }
                logBlockedJumpRecoveryGeometry(
                        frame,
                        core,
                        target
                );
                aimProgress = AimProgress.JUMP_HEADROOM_BLOCKED;
                return deferCurrentAimStep(context, parameters)
                        .orElseGet(() -> fail(
                                "build_shelter_step."
                                        + AimProgress
                                                .JUMP_HEADROOM_BLOCKED
                                                .failureSuffix()
                        ));
            }
            if (core.onGround()
                    && jumpAimAttempts
                            < MAXIMUM_JUMP_AIM_ATTEMPTS) {
                final ActionOutcome jumped = coreActions.jump();
                if (!jumped.accepted()) {
                    return fail(
                            "build_shelter_step.jump_aim_rejected"
                    );
                }
                jumpAimAttempts++;
                /*
                 * The jump creates a new physical aiming attempt. Its
                 * deadline and fresh-observation barrier must begin here,
                 * not at the earlier ground reposition, otherwise a valid
                 * airborne top-face ray can arrive on the old timeout tick
                 * and be discarded.
                 */
                aimStartedAtGameTick = context.gameTick();
                aimStartedObservationRevision =
                        frame.observationRevision();
                aimProgress = AimProgress.WAITING_JUMP_HEIGHT;
                return SkillTickResult.running(true, true);
            }
            if (jumpAimRepositionRequired(
                    core.onGround(),
                    jumpAimAttempts
            )) {
                final Optional<SkillTickResult> repositioned =
                        startAimReposition(
                                context,
                                parameters,
                                frame,
                                target,
                                true
                        );
                if (repositioned.isPresent()) {
                    return repositioned.orElseThrow();
                }
                aimProgress =
                        AimProgress.JUMP_ATTEMPTS_EXHAUSTED;
                MinecraftAiCompanion.LOGGER.warn(
                        "Shelter jump aim exhausted planOrigin={} "
                                + "stepIndex={} attempts={} feet={} "
                                + "position={} eye={} target={} "
                                + "headroomBlocked={} "
                                + "abandonedSupports={}",
                        activePlan == null ? null : activePlan.origin(),
                        executingStep == null
                                ? -1
                                : executingStep.index(),
                        jumpAimAttempts,
                        core.feet(),
                        core.position(),
                        core.eyePosition(),
                        target,
                        jumpHeadroomBlocked(frame, core.feet()),
                        abandonedAimSupports
                );
                return deferCurrentAimStep(context, parameters)
                        .orElseGet(() -> fail(
                                "build_shelter_step."
                                        + AimProgress
                                                .JUMP_ATTEMPTS_EXHAUSTED
                                                .failureSuffix()
                        ));
            }
            aimProgress = AimProgress.WAITING_JUMP_HEIGHT;
            return SkillTickResult.running(true, true);
        }
        if (frame.observationRevision()
                <= aimStartedObservationRevision) {
            aimProgress = AimProgress.WAITING_OBSERVATION;
            return SkillTickResult.running(true, true);
        }

        final Optional<VisibleBlockFace> crosshair =
                frames.currentCrosshairBlock();
        if (crosshair.isEmpty()) {
            if (airborneJumpAim(core, target)) {
                aimProgress = AimProgress.CROSSHAIR_EMPTY;
                return SkillTickResult.running(true, true);
            }
            final Optional<SkillTickResult> repositioned =
                    startAimReposition(
                            context,
                            parameters,
                            frame,
                            target
                    );
            if (repositioned.isPresent()) {
                return repositioned.orElseThrow();
            }
            aimProgress = AimProgress.CROSSHAIR_EMPTY;
            return SkillTickResult.running(false, true);
        }
        final VisibleBlockFace face = crosshair.orElseThrow();
        final Optional<SkillTickResult> openedDoor =
                openOccludingDoor(
                        context,
                        frame,
                        face
                );
        if (openedDoor.isPresent()) {
            return openedDoor.orElseThrow();
        }
        final Optional<StepTarget> adapted =
                adaptToCrosshair(frame, face);
        if (adapted.isPresent()) {
            final ShelterBuildStep previousStep = executingStep;
            final BlockInteractionTarget previousTarget =
                    interactionTarget;
            final ShelterBuildStep adaptedStep =
                    adapted.orElseThrow().step();
            final boolean carryRoofInteriorFallback =
                    activePlan != null
                            && previousStep != null
                            && shouldCarryRoofInteriorFallback(
                                    activePlan,
                                    previousStep,
                                    adaptedStep,
                                    frame.feet(),
                                    exteriorRoofSearchExhausted(
                                            previousStep
                                    )
                            );
            executingStep = adaptedStep;
            interactionTarget = adapted.orElseThrow().target();
            if (previousStep != null
                    && previousStep.index()
                            == executingStep.index()
                    && previousTarget != null
                    && !PlacementSupportIdentity.from(previousTarget)
                            .equals(PlacementSupportIdentity.from(
                                    interactionTarget
                            ))) {
                abandonedAimSupports.add(
                        PlacementSupportIdentity.from(previousTarget)
                );
            }
            if (carryRoofInteriorFallback) {
                exhaustedExteriorRoofSteps.set(
                        executingStep.index()
                );
            }
            if (previousStep == null
                    || previousStep.index()
                            != executingStep.index()) {
                aimRepositionAttempts = 0;
                resetJumpAim(executingStep);
                attemptedAimVantages.clear();
                attemptedRoofObservationStands.clear();
                abandonedAimSupports.clear();
                activeRoofApronSurveyStand = null;
                clearRoofApronRefresh();
            }
            if (previousStep != null
                    && previousStep.index()
                            != executingStep.index()) {
                MinecraftAiCompanion.LOGGER.info(
                        "Adapted shelter aim to visible pending step "
                                + "planOrigin={} previousStep={} "
                                + "nextStep={} feet={} "
                                + "carriedRoofInteriorFallback={}",
                        activePlan == null
                                ? null
                                : activePlan.origin(),
                        previousStep.index(),
                        executingStep.index(),
                        frame.feet(),
                        carryRoofInteriorFallback
                );
            }
            aimRecoverySurveyPerformed = false;
            aimProgress = AimProgress.READY;
            phase = holdSneak
                    ? Phase.READY
                    : placementInteractionPhase();
            if (phase == Phase.READY) {
                return dispatchPlacement(
                        context,
                        parameters,
                        frame
                );
            }
            return SkillTickResult.running(true, true);
        }
        if (face.block().x() != target.x()
                || face.block().y() != target.y()
                || face.block().z() != target.z()) {
            if (airborneJumpAim(core, target)) {
                aimProgress = AimProgress.CROSSHAIR_WRONG_BLOCK;
                return SkillTickResult.running(true, true);
            }
            final Optional<SkillTickResult> repositioned =
                    startAimReposition(
                            context,
                            parameters,
                            frame,
                            target
                    );
            if (repositioned.isPresent()) {
                return repositioned.orElseThrow();
            }
            aimProgress = AimProgress.CROSSHAIR_WRONG_BLOCK;
            return SkillTickResult.running(false, true);
        }
        if (!face.face().equals(
                target.face().name().toLowerCase(Locale.ROOT))) {
            if (airborneJumpAim(core, target)) {
                aimProgress = AimProgress.CROSSHAIR_WRONG_FACE;
                return SkillTickResult.running(true, true);
            }
            final Optional<SkillTickResult> repositioned =
                    startAimReposition(
                            context,
                            parameters,
                            frame,
                            target
                    );
            if (repositioned.isPresent()) {
                return repositioned.orElseThrow();
            }
            aimProgress = AimProgress.CROSSHAIR_WRONG_FACE;
            return SkillTickResult.running(false, true);
        }
        if (face.distance() > MAXIMUM_BUILD_REACH) {
            aimProgress = AimProgress.CROSSHAIR_OUT_OF_REACH;
            return SkillTickResult.running(false, true);
        }
        final Optional<BlockInteractionTarget> centered =
                toTarget(face);
        if (centered.isEmpty()) {
            aimProgress = AimProgress.CROSSHAIR_INVALID;
            return SkillTickResult.running(false, true);
        }
        interactionTarget = centered.orElseThrow();
        aimProgress = AimProgress.READY;
        phase = holdSneak
                ? Phase.READY
                : placementInteractionPhase();
        if (phase == Phase.READY) {
            return dispatchPlacement(
                    context,
                    parameters,
                    frame
            );
        }
        return SkillTickResult.running(true, true);
    }

    private void logBlockedJumpRecoveryGeometry(
            final ShelterFrame frame,
            final CoreSkillFrame core,
            final BlockInteractionTarget target
    ) {
        if (activePlan == null || executingStep == null) {
            return;
        }
        final GridPos placement = executingStep.target();
        final GridPos belowPlacement = new GridPos(
                placement.x(),
                activePlan.origin().y(),
                placement.z()
        );
        MinecraftAiCompanion.LOGGER.warn(
                "Shelter blocked-jump recovery has no vantage "
                        + "planOrigin={} stepIndex={} feet={} "
                        + "placement={} interaction={} below={} "
                        + "belowFeet={} belowHead={} belowRoof={} "
                        + "safe={} unobstructed={} aimLine={} "
                        + "permitted={} distance={} "
                        + "needsJumpHeadroom={} observedJumpHeadroom={}",
                activePlan.origin(),
                executingStep.index(),
                core.feet(),
                placement,
                target,
                belowPlacement,
                frame.navigation().voxelAt(belowPlacement)
                        .orElse(null),
                frame.navigation().voxelAt(
                        belowPlacement.above()
                ).orElse(null),
                frame.navigation().voxelAt(
                        belowPlacement.above(2)
                ).orElse(null),
                isObservedSafeStand(frame, belowPlacement),
                isObservedUnobstructedStand(
                        frame,
                        belowPlacement
                ),
                hasObservedAimLine(
                        frame,
                        belowPlacement,
                        target
                ),
                isPermittedAimTraversalStand(
                        activePlan,
                        executingStep,
                        core.feet(),
                        belowPlacement,
                        exteriorRoofSearchExhausted(
                                executingStep
                        )
                ),
                core.feet().euclideanDistance(
                        belowPlacement
                ),
                aimVantageNeedsObservedJumpHeadroom(
                        true,
                        target,
                        belowPlacement,
                        placement
                ),
                hasObservedJumpHeadroom(
                        frame,
                        belowPlacement
                )
        );
    }

    static boolean requiresJumpToSeeTopFace(
            final double eyeY,
            final BlockInteractionTarget target
    ) {
        Objects.requireNonNull(target, "target");
        return target.face() == BlockFace.UP
                && eyeY
                <= target.hitPoint().y()
                        + TOP_FACE_EYE_CLEARANCE;
    }

    static boolean requiresJumpToSeePlacementFace(
            final double eyeY,
            final BlockInteractionTarget target
    ) {
        Objects.requireNonNull(target, "target");
        if (target.face() == BlockFace.UP) {
            return requiresJumpToSeeTopFace(
                    eyeY,
                    target
            );
        }
        if (target.face() == BlockFace.DOWN) {
            return false;
        }
        /*
         * From a shelter floor the standing eye is slightly below the
         * bottom plane of a roof block two cells overhead. A ray aimed at
         * that block's horizontal side therefore intersects its underside
         * first. Jumping raises the real ServerPlayer eye above the bottom
         * edge so the requested side face becomes physically visible.
         */
        return eyeY
                <= target.y() + TOP_FACE_EYE_CLEARANCE;
    }

    static boolean requiresJumpToSeePlacementFace(
            final double eyeY,
            final BlockInteractionTarget target,
            final GridPos feet,
            final GridPos placementCell
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(feet, "feet");
        Objects.requireNonNull(placementCell, "placementCell");
        final GridPos support = new GridPos(
                target.x(),
                target.y(),
                target.z()
        );
        final boolean horizontalSupportFace =
                target.face() != BlockFace.UP
                        && target.face() != BlockFace.DOWN;
        final boolean standingBelowPlacementCell =
                feet.x() == placementCell.x()
                        && feet.z() == placementCell.z()
                        && placementCell.y() >= feet.y() + 2;
        final boolean supportActuallyPlacesIntoCell =
                PlacementSupportPreference.support(
                        placementCell,
                        target.face()
                ).equals(support);
        /*
         * The last opening in an inward-filled roof is a special but fully
         * vanilla geometry. Standing directly below that empty cell leaves
         * the player's body clear of the block at ground height, and a ray
         * travels through the empty column into the low edge of an adjacent
         * roof block's side. Jumping is counterproductive: it raises the
         * body into the cell being placed and vanilla rejects the collision.
         */
        if (horizontalSupportFace
                && standingBelowPlacementCell
                && supportActuallyPlacesIntoCell) {
            return false;
        }
        return requiresJumpToSeePlacementFace(eyeY, target);
    }

    static boolean jumpAimRepositionRequired(
            final boolean onGround,
            final int jumpAttempts
    ) {
        if (jumpAttempts < 0) {
            throw new IllegalArgumentException(
                    "jumpAttempts must be non-negative"
            );
        }
        return onGround
                && jumpAttempts >= MAXIMUM_JUMP_AIM_ATTEMPTS;
    }

    static boolean aimAlignmentSatisfied(
            final double errorDegrees,
            final boolean trackingAirborneJump
    ) {
        if (!Double.isFinite(errorDegrees)
                || errorDegrees < 0.0) {
            throw new IllegalArgumentException(
                    "errorDegrees must be finite and non-negative"
            );
        }
        return errorDegrees
                <= (trackingAirborneJump
                        ? JUMP_AIM_ALIGNMENT_DEGREES
                        : AIM_ALIGNMENT_DEGREES);
    }

    private void bindJumpAimStep() {
        final int current = Objects.requireNonNull(
                executingStep
        ).index();
        if (jumpAimStepIndex != current) {
            jumpAimStepIndex = current;
            jumpAimAttempts = 0;
        }
    }

    private boolean airborneJumpAim(
            final CoreSkillFrame core,
            final BlockInteractionTarget target
    ) {
        return jumpAimAttempts > 0
                && jumpAimStepIndex
                        == Objects.requireNonNull(
                                executingStep
                        ).index()
                && target.face() != BlockFace.DOWN
                && !core.onGround();
    }

    private void resetJumpAim(
            final ShelterBuildStep step
    ) {
        jumpAimStepIndex = step == null ? -1 : step.index();
        jumpAimAttempts = 0;
    }

    private boolean jumpHeadroomBlocked(
            final ShelterFrame frame,
            final GridPos feet
    ) {
        final GridPos headroom = feet.above(2);
        if (activePlan != null
                && activePlan.steps().stream()
                        .filter(step ->
                                confirmed.get(step.index()))
                        .filter(step ->
                                step.role()
                                        .usesStructuralMaterial())
                        .anyMatch(step ->
                                step.target().equals(headroom))) {
            return true;
        }
        return frame.navigation().voxelAt(headroom)
                .map(voxel ->
                        !NavigationEvidence
                                .hasTraversalClearance(voxel))
                .orElse(false);
    }

    private Optional<SkillTickResult> startRoofEdgeBridge(
            final SkillContext context,
            final BuildShelterStepParameters parameters,
            final CoreSkillFrame core,
            final ShelterFrame frame
    ) {
        final ShelterBuildStep step =
                Objects.requireNonNull(executingStep);
        if (roofEdgeBridgeSkill.isEmpty()
                || attemptedRoofEdgeBridges.get(step.index())
                || !core.onGround()
                || core.inWater()
                || !isAdjacentRoofEdgeStand(core.feet(), step)) {
            return Optional.empty();
        }
        final BridgeToParameters bridge = new BridgeToParameters(
                parameters.dimension(),
                step.target().x() + 0.5,
                core.position().y(),
                step.target().z() + 0.5,
                0.5,
                1
        );
        final BridgeToSkill skill =
                roofEdgeBridgeSkill.orElseThrow();
        final Optional<SkillFailure> rejected =
                skill.preconditions(context, bridge);
        if (rejected.isPresent()) {
            attemptedRoofEdgeBridges.set(step.index());
            return Optional.empty();
        }
        coreActuator.orElseThrow().stop();
        activeRoofEdgeBridgeParameters = bridge;
        skill.start(context, bridge);
        phase = Phase.ROOF_EDGE_BRIDGING;
        MinecraftAiCompanion.LOGGER.info(
                "Started crouched roof-edge placement "
                        + "planOrigin={} stepIndex={} from={} target={}",
                activePlan == null ? null : activePlan.origin(),
                step.index(),
                core.feet(),
                step.target()
        );
        return Optional.of(
                SkillTickResult.running(true, false)
        );
    }

    private SkillTickResult tickRoofEdgeBridge(
            final SkillContext context,
            final BuildShelterStepParameters parameters
    ) {
        final BridgeToParameters bridge =
                Objects.requireNonNull(activeRoofEdgeBridgeParameters);
        final SkillTickResult tick =
                roofEdgeBridgeSkill.orElseThrow().tick(context, bridge);
        if (tick.status() == SkillTickResult.Status.RUNNING) {
            return tick;
        }
        activeRoofEdgeBridgeParameters = null;
        if (tick.status() == SkillTickResult.Status.FAILED) {
            final int stepIndex = executingStep == null
                    ? -1
                    : executingStep.index();
            if (stepIndex >= 0) {
                attemptedRoofEdgeBridges.set(stepIndex);
            }
            MinecraftAiCompanion.LOGGER.warn(
                    "Crouched roof-edge placement did not complete "
                            + "planOrigin={} stepIndex={} reason={}",
                    activePlan == null ? null : activePlan.origin(),
                    stepIndex,
                    tick.failure().map(SkillFailure::code)
                            .orElse("unknown")
            );
            final FrameValidation validation = validateFrame(
                    parameters,
                    boundSessionGeneration,
                    false
            );
            if (validation.failure().isPresent()) {
                return fail(validation.failure().orElseThrow());
            }
            phase = Phase.AIMING;
            beginAim(context, validation.frame().orElseThrow());
            return deferCurrentAimStep(context, parameters)
                    .orElseGet(() ->
                            SkillTickResult.running(true, true));
        }
        final FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                false
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        MinecraftAiCompanion.LOGGER.info(
                "Completed crouched roof-edge placement "
                        + "planOrigin={} stepIndex={} target={}",
                activePlan == null ? null : activePlan.origin(),
                executingStep == null ? -1 : executingStep.index(),
                executingStep == null ? null : executingStep.target()
        );
        return acceptConfirmedPlacement(
                context,
                validation.frame().orElseThrow()
        );
    }

    private BridgeMaterialResult currentRoofMaterial() {
        if (activePlan == null) {
            return BridgeMaterialResult.failed(
                    "build_shelter_step.plan_unavailable"
            );
        }
        final Optional<ShelterFrame> current = frames.current();
        if (current.isEmpty()) {
            return BridgeMaterialResult.failed(
                    "build_shelter_step.observation_unavailable"
            );
        }
        final ShelterFrame frame = current.orElseThrow();
        final String itemId = activePlan.structuralItemId();
        if (!itemId.equals(frame.mainHand().itemId())
                || frame.mainHand().count() < 1) {
            return BridgeMaterialResult.failed(
                    "build_shelter_step.equip_material"
            );
        }
        final int available = Math.max(
                frame.mainHand().count(),
                frame.inventory().stream()
                        .filter(item -> item.itemId().equals(itemId))
                        .mapToInt(item -> item.count())
                        .sum()
        );
        return BridgeMaterialResult.ready(itemId, available);
    }

    static boolean isAdjacentRoofEdgeStand(
            final GridPos feet,
            final ShelterBuildStep step
    ) {
        Objects.requireNonNull(feet, "feet");
        Objects.requireNonNull(step, "step");
        return step.role() == ShelterStepRole.ROOF
                && feet.y() == step.target().y() + 1
                && Math.abs(feet.x() - step.target().x())
                        + Math.abs(feet.z() - step.target().z()) == 1;
    }

    static boolean isInteriorFloorPosition(
            final ShelterPlan plan,
            final GridPos feet
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(feet, "feet");
        return feet.y() == plan.origin().y()
                && feet.x() > plan.origin().x()
                && feet.x()
                        < plan.origin().x()
                                + plan.exteriorWidth() - 1
                && feet.z() > plan.origin().z()
                && feet.z()
                        < plan.origin().z()
                                + plan.exteriorDepth() - 1;
    }

    /**
     * Selects a fairly observed working stance for an already committed
     * shelter when a stationary survey cannot expose the next support.
     *
     * <p>The active plan is construction intent, not world knowledge. Every
     * candidate still needs player-sized clearance and solid support in the
     * incremental first-person navigation map. The ordinary movement skill
     * then has to find and physically traverse a route before another survey
     * and any placement. Preferring a central interior cell mirrors how a
     * player steps inside a small shell to reach its far walls.</p>
     */
    static Optional<GridPos> activePlanTraversalTarget(
            final ShelterFrame frame,
            final ShelterPlan plan,
            final BitSet planConfirmed
    ) {
        return activePlanTraversalTarget(
                frame,
                plan,
                planConfirmed,
                Set.of()
        );
    }

    static Optional<GridPos> activePlanTraversalTarget(
            final ShelterFrame frame,
            final ShelterPlan plan,
            final BitSet planConfirmed,
            final Set<GridPos> exploredStands
    ) {
        return activePlanTraversalTarget(
                frame,
                plan,
                planConfirmed,
                exploredStands,
                false
        );
    }

    static Optional<GridPos> activePlanTraversalTarget(
            final ShelterFrame frame,
            final ShelterPlan plan,
            final BitSet planConfirmed,
            final Set<GridPos> exploredStands,
            final boolean preferInteriorRoofTraversal
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(planConfirmed, "planConfirmed");
        Objects.requireNonNull(exploredStands, "exploredStands");
        final Optional<ShelterStepRole> role =
                currentRole(plan, planConfirmed);
        if (role.isEmpty()) {
            return Optional.empty();
        }
        final ShelterStepRole pendingRole = role.orElseThrow();
        final int priority = plan.steps().stream()
                .filter(step ->
                        !planConfirmed.get(step.index()))
                .filter(step -> step.role() == pendingRole)
                .mapToInt(step ->
                        constructionPriority(plan, step))
                .min()
                .orElse(Integer.MAX_VALUE);
        final List<ShelterBuildStep> pending =
                plan.steps().stream()
                        .filter(step ->
                                !planConfirmed.get(step.index()))
                        .filter(step ->
                                step.role() == pendingRole)
                        .filter(step ->
                                constructionPriority(plan, step)
                                        == priority)
                        .toList();
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        final List<GridPos> candidates =
                frame.navigation().observedVoxels().values()
                .stream()
                .map(ObservedVoxel::position)
                .distinct()
                .filter(stand ->
                        !stand.equals(frame.feet()))
                .filter(stand ->
                        isObservedSafeStand(frame, stand))
                .filter(stand ->
                        DynamicShelterPlanner
                                .visiblePlacementObstruction(
                                        frame,
                                        stand
                                )
                                .isEmpty())
                .filter(stand ->
                        pending.stream().anyMatch(step ->
                                isPermittedConstructionStand(
                                        plan,
                                        step,
                                        stand
                                )))
                .filter(stand ->
                        plan.steps().stream().noneMatch(step ->
                                step.target().equals(stand)))
                .toList();
        final List<GridPos> preferred;
        if (pendingRole == ShelterStepRole.ROOF
                && priority == 0
                && !preferInteriorRoofTraversal) {
            final List<GridPos> exterior =
                    candidates.stream()
                            .filter(stand ->
                                    isExteriorRoofApronPosition(
                                            plan,
                                            stand
                                    ))
                            .toList();
            preferred = exterior.isEmpty()
                    ? candidates.stream()
                            .filter(stand ->
                                    isInteriorFloorPosition(
                                            plan,
                                            stand
                                    ))
                            .toList()
                    : exterior;
        } else {
            preferred = candidates.stream()
                    .filter(stand ->
                            isInteriorFloorPosition(plan, stand))
                    .toList();
        }
        final Comparator<GridPos> candidateOrder = Comparator
                .comparingDouble((GridPos stand) ->
                        frame.feet()
                                .euclideanDistance(stand))
                .thenComparingInt(stand ->
                        -reachablePendingCount(
                                stand,
                                pending
                        ))
                .thenComparingDouble(stand ->
                        maximumPendingDistance(
                                stand,
                                pending
                        ))
                .thenComparingInt(GridPos::x)
                .thenComparingInt(GridPos::z);
        if (pendingRole == ShelterStepRole.ROOF
                && priority == 0
                && !preferInteriorRoofTraversal
                && isExteriorRoofApronPosition(
                        plan,
                        frame.feet()
                )) {
            /*
             * Once outside, route over the observed-safe one-cell ring and
             * return only the first cardinal hop. Explored stands remain
             * valid transit cells: a player's verified previous footprint
             * may be the only route around a one-sided observation gap.
             * The destination of the bounded path must still be a different
             * unvisited frontier, so this cannot oscillate between two
             * already exhausted cells. A direct route to a far observed
             * apron cell would let stale AIR in the newly filled shell
             * attract MoveTo, hence the explicit ring graph.
             */
            return observedApronFrontierStep(
                    frame.feet(),
                    preferred,
                    exploredStands,
                    candidateOrder
            );
        }
        return preferred.stream()
                .filter(stand ->
                        !exploredStands.contains(stand))
                .min(candidateOrder);
    }

    private static Optional<GridPos> observedApronFrontierStep(
            final GridPos start,
            final List<GridPos> observedSafeApron,
            final Set<GridPos> exploredStands,
            final Comparator<GridPos> candidateOrder
    ) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(
                observedSafeApron,
                "observedSafeApron"
        );
        Objects.requireNonNull(exploredStands, "exploredStands");
        Objects.requireNonNull(candidateOrder, "candidateOrder");
        if (observedSafeApron.isEmpty()) {
            return Optional.empty();
        }

        final Set<GridPos> graph =
                new HashSet<>(observedSafeApron);
        final ArrayDeque<GridPos> queue = new ArrayDeque<>();
        final Map<GridPos, GridPos> predecessor =
                new HashMap<>();
        final Map<GridPos, Integer> distance =
                new HashMap<>();
        queue.add(start);
        distance.put(start, 0);
        while (!queue.isEmpty()) {
            final GridPos current = queue.removeFirst();
            final List<GridPos> neighbours = graph.stream()
                    .filter(candidate ->
                            cardinallyAdjacent(
                                    current,
                                    candidate
                            ))
                    .filter(candidate ->
                            !distance.containsKey(candidate))
                    .sorted(candidateOrder)
                    .toList();
            for (GridPos neighbour : neighbours) {
                predecessor.put(neighbour, current);
                distance.put(
                        neighbour,
                        distance.get(current) + 1
                );
                queue.addLast(neighbour);
            }
        }

        final Optional<GridPos> frontier = graph.stream()
                .filter(candidate ->
                        !exploredStands.contains(candidate))
                .filter(distance::containsKey)
                .min(Comparator
                        .comparingInt((GridPos candidate) ->
                                distance.get(candidate))
                        .thenComparing(candidateOrder));
        if (frontier.isEmpty()) {
            return Optional.empty();
        }
        GridPos step = frontier.orElseThrow();
        GridPos prior = predecessor.get(step);
        while (prior != null && !prior.equals(start)) {
            step = prior;
            prior = predecessor.get(step);
        }
        return prior == null
                ? Optional.empty()
                : Optional.of(step);
    }

    private static boolean cardinallyAdjacent(
            final GridPos first,
            final GridPos second
    ) {
        return first.y() == second.y()
                && Math.abs(first.x() - second.x())
                        + Math.abs(first.z() - second.z()) == 1;
    }

    private static boolean roofReturnStepAdjacent(
            final GridPos first,
            final GridPos second
    ) {
        return Math.abs(first.y() - second.y()) <= 1
                && Math.abs(first.x() - second.x())
                        + Math.abs(first.z() - second.z()) == 1;
    }

    private static int reachablePendingCount(
            final GridPos stand,
            final List<ShelterBuildStep> pending
    ) {
        return (int) pending.stream()
                .filter(step ->
                        constructionEyeDistanceSquared(
                                stand,
                                step.target()
                        ) <= MAXIMUM_BUILD_REACH
                                * MAXIMUM_BUILD_REACH)
                .count();
    }

    private static double maximumPendingDistance(
            final GridPos stand,
            final List<ShelterBuildStep> pending
    ) {
        return pending.stream()
                .mapToDouble(step ->
                        constructionEyeDistanceSquared(
                                stand,
                                step.target()
                        ))
                .max()
                .orElse(Double.POSITIVE_INFINITY);
    }

    private static double constructionEyeDistanceSquared(
            final GridPos stand,
            final GridPos target
    ) {
        final double x = target.x() - stand.x();
        final double y = target.y() + 0.5
                - (stand.y() + 1.62);
        final double z = target.z() - stand.z();
        return x * x + y * y + z * z;
    }

    static boolean isPermittedConstructionStand(
            final ShelterPlan plan,
            final ShelterBuildStep step,
            final GridPos stand
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(stand, "stand");
        return switch (step.role()) {
            case LOWER_WALL, UPPER_WALL ->
                    isInteriorFloorPosition(plan, stand);
            /*
             * An interior roof target is two cells above the floor. Its
             * empty column is therefore a legal standing position and is
             * sometimes the only low-angle line to the final neighbouring
             * support face. Outer-ring target columns are wall cells, not
             * interior floor positions, so this does not authorize walking
             * inside a completed wall.
             */
            case ROOF -> isInteriorFloorPosition(plan, stand)
                    || isExteriorRoofApronPosition(plan, stand);
            default -> true;
        };
    }

    static boolean isPermittedAimTraversalStand(
            final ShelterPlan plan,
            final ShelterBuildStep step,
            final GridPos currentFeet,
            final GridPos candidate
    ) {
        return isPermittedAimTraversalStand(
                plan,
                step,
                currentFeet,
                candidate,
                false
        );
    }

    static boolean isPermittedAimTraversalStand(
            final ShelterPlan plan,
            final ShelterBuildStep step,
            final GridPos currentFeet,
            final GridPos candidate,
            final boolean exteriorSearchExhausted
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(currentFeet, "currentFeet");
        Objects.requireNonNull(candidate, "candidate");
        if (!isPermittedConstructionStand(
                plan,
                step,
                candidate
        )) {
            return false;
        }
        if (exteriorSearchExhausted
                && step.role() == ShelterStepRole.ROOF
                && isExteriorRoofApronPosition(
                        plan,
                        candidate
                )) {
            return false;
        }
        /*
         * The doorway is the only known passage through a completed shell.
         * While standing on the exterior apron, an interior aiming candidate
         * cannot be handed directly to MoveTo: an old AIR sample from before
         * construction may otherwise attract the route through a wall.
         */
        return step.role() != ShelterStepRole.ROOF
                || !isExteriorRoofApronPosition(
                        plan,
                        currentFeet
                )
                || !isInteriorFloorPosition(
                        plan,
                        candidate
                );
    }

    /**
     * Allows the same one-block exterior working strip a player uses when
     * the last roof faces are hidden by completed walls.
     *
     * <p>The candidate must still be present as safe, traversable
     * first-person navigation evidence, pass the ray-line and reach checks,
     * and be reached by the ordinary movement skill. This predicate only
     * removes the previous categorical ban on every exterior roof stance;
     * it does not reveal or trust unseen terrain.</p>
     */
    static boolean isExteriorRoofApronPosition(
            final ShelterPlan plan,
            final GridPos stand
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(stand, "stand");
        if (stand.y() != plan.origin().y()) {
            return false;
        }
        final int minimumX = plan.origin().x();
        final int maximumX = minimumX
                + plan.exteriorWidth() - 1;
        final int minimumZ = plan.origin().z();
        final int maximumZ = minimumZ
                + plan.exteriorDepth() - 1;
        final boolean xApron = (stand.x() == minimumX - 1
                || stand.x() == maximumX + 1)
                && stand.z() >= minimumZ - 1
                && stand.z() <= maximumZ + 1;
        final boolean zApron = (stand.z() == minimumZ - 1
                || stand.z() == maximumZ + 1)
                && stand.x() >= minimumX - 1
                && stand.x() <= maximumX + 1;
        return xApron || zApron;
    }

    static boolean shouldReturnInsideForRoofReposition(
            final ShelterPlan plan,
            final ShelterBuildStep step,
            final GridPos feet
    ) {
        return shouldReturnInsideForRoofReposition(
                plan,
                step,
                feet,
                0
        );
    }

    static boolean shouldReturnInsideForRoofReposition(
            final ShelterPlan plan,
            final ShelterBuildStep step,
            final GridPos feet,
            final int completedExteriorAttempts
    ) {
        return shouldReturnInsideForRoofReposition(
                plan,
                step,
                feet,
                completedExteriorAttempts,
                false
        );
    }

    static boolean shouldReturnInsideForRoofReposition(
            final ShelterPlan plan,
            final ShelterBuildStep step,
            final GridPos feet,
            final int completedExteriorAttempts,
            final boolean exteriorSearchExhausted
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(feet, "feet");
        return step.role() == ShelterStepRole.ROOF
                && isExteriorRoofReturnPosition(plan, feet)
                && (constructionPriority(plan, step) > 0
                        || exteriorSearchExhausted
                        || completedExteriorAttempts
                                >= exteriorRoofInteriorFallbackAttempts(
                                        plan
                                ));
    }

    static boolean shouldCarryRoofInteriorFallback(
            final ShelterPlan plan,
            final ShelterBuildStep previousStep,
            final ShelterBuildStep nextStep,
            final GridPos feet,
            final boolean previousExteriorSearchExhausted
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(previousStep, "previousStep");
        Objects.requireNonNull(nextStep, "nextStep");
        Objects.requireNonNull(feet, "feet");
        /*
         * Crosshair adaptation is intentionally allowed to choose the exact
         * pending roof cell exposed from the body's current first-person
         * view. Once a bounded exterior search has returned the body through
         * the door, however, that adaptation is still part of the same
         * interior fallback. Keeping exhaustion only on the old target makes
         * the new target stage another exterior survey; at the doorway the
         * crosshair can select the old exhausted target again, creating an
         * endless door loop without a placement. Carry the mode only while
         * physically inside. Exterior roof work remains target-specific.
         */
        return previousExteriorSearchExhausted
                && previousStep.role() == ShelterStepRole.ROOF
                && nextStep.role() == ShelterStepRole.ROOF
                && isInteriorFloorPosition(plan, feet);
    }

    static boolean roofInteriorFallbackApplies(
            final ShelterPlan plan,
            final ShelterBuildStep step,
            final int activePriority
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        return activePriority >= 0
                && step.role() == ShelterStepRole.ROOF
                && constructionPriority(plan, step)
                        <= activePriority;
    }

    static int roofInteriorFallbackPriorityAfterPlacement(
            final ShelterPlan plan,
            final BitSet planConfirmed,
            final int activePriority
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(
                planConfirmed,
                "planConfirmed"
        );
        if (activePriority < 0) {
            return -1;
        }
        final boolean pendingFallbackTarget =
                plan.steps().stream()
                        .filter(step ->
                                !planConfirmed.get(step.index()))
                        .anyMatch(step ->
                                roofInteriorFallbackApplies(
                                        plan,
                                        step,
                                        activePriority
                                ));
        return pendingFallbackTarget
                ? activePriority
                : -1;
    }

    /**
     * Commits an ordinary traversal-driven doorway return to the same
     * interior roof mode used by exhausted aiming.
     *
     * <p>Without this transition, reaching an interior floor cell clears the
     * temporary {@code returningInsideForRoof} flag while the fallback
     * priority remains disabled. The next traversal therefore selects the
     * exterior apron again and can loop around the building until its budget
     * expires. The value is derived only from the already generated plan and
     * server-confirmed placement bitset.</p>
     */
    static int roofInteriorTraversalFallbackPriority(
            final ShelterPlan plan,
            final BitSet planConfirmed,
            final int activePriority
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(
                planConfirmed,
                "planConfirmed"
        );
        final int pendingPriority = plan.steps().stream()
                .filter(step ->
                        !planConfirmed.get(step.index()))
                .filter(step ->
                        step.role() == ShelterStepRole.ROOF)
                .mapToInt(step ->
                        constructionPriority(plan, step))
                .min()
                .orElse(-1);
        return pendingPriority < 0
                ? activePriority
                : Math.max(activePriority, pendingPriority);
    }

    static boolean deferredRoofFallbackCycleExhausted(
            final ShelterPlan plan,
            final BitSet planConfirmed,
            final BitSet deferredSteps,
            final int activePriority
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(
                planConfirmed,
                "planConfirmed"
        );
        Objects.requireNonNull(
                deferredSteps,
                "deferredSteps"
        );
        if (activePriority < 0) {
            return false;
        }
        final List<ShelterBuildStep> pending =
                plan.steps().stream()
                        .filter(step ->
                                !planConfirmed.get(step.index()))
                        .filter(step ->
                                roofInteriorFallbackApplies(
                                        plan,
                                        step,
                                        activePriority
                                ))
                        .toList();
        return !pending.isEmpty()
                && pending.stream().allMatch(step ->
                        deferredSteps.get(step.index()));
    }

    static boolean shouldClearTraversalHistoryAfterAimDeferral(
            final int activeRoofFallbackPriority
    ) {
        return activeRoofFallbackPriority < 0;
    }

    private boolean exteriorRoofSearchExhausted(
            final ShelterBuildStep step
    ) {
        Objects.requireNonNull(step, "step");
        return exhaustedExteriorRoofSteps.get(step.index())
                || activePlan != null
                        && roofInteriorFallbackApplies(
                                activePlan,
                                step,
                                roofInteriorFallbackPriority
                        );
    }

    static int exteriorRoofInteriorFallbackAttempts(
            final ShelterPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        /*
         * Give a fresh outer-ring target enough movement samples to inspect
         * a complete side plus the corner on the compact footprint. The
         * previous one-side minimum (four for the compact plan) could stop
         * immediately before the only first-person ray around a finished
         * corner became available. Keep the budget bounded by two short
         * side traversals; it remains finite and never authorizes an
         * unobserved cell.
         */
        final int side = Math.min(
                plan.exteriorWidth(),
                plan.exteriorDepth()
        );
        return Math.max(8, Math.multiplyExact(side, 2));
    }

    /**
     * Returns one fairly observed step along the only known corridor from the
     * roof apron back into the shelter: perimeter, exterior doorway cell,
     * open planned door cell, then the adjacent interior floor cell.
     */
    static Optional<GridPos> observedRoofReturnContinuation(
            final boolean returning,
            final ShelterFrame frame,
            final ShelterPlan plan,
            final GridPos expectedArrival,
            final Set<GridPos> exploredStands
    ) {
        return observedRoofReturnContinuation(
                returning,
                frame,
                plan,
                expectedArrival,
                exploredStands,
                Set.of()
        );
    }

    static Optional<GridPos> observedRoofReturnContinuation(
            final boolean returning,
            final ShelterFrame frame,
            final ShelterPlan plan,
            final GridPos expectedArrival,
            final Set<GridPos> exploredStands,
            final Set<GridPos> rejectedStands
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(
                expectedArrival,
                "expectedArrival"
        );
        Objects.requireNonNull(exploredStands, "exploredStands");
        Objects.requireNonNull(rejectedStands, "rejectedStands");
        if (!returning
                || !expectedArrival.equals(frame.feet())
                || !roofInteriorReturnStillPending(
                        true,
                        plan,
                        frame.feet()
                )) {
            return Optional.empty();
        }
        return roofInteriorReturnTraversalTarget(
                frame,
                plan,
                exploredStands,
                rejectedStands
        );
    }

    /**
     * Continues through a previously body-verified apron cell without paying
     * for another stationary panorama.
     *
     * <p>The next destination is still selected exclusively from the current
     * fair navigation snapshot. A first visit to a frontier returns empty so
     * the normal semantic survey runs there and can extend observation around
     * an opaque corner.</p>
     */
    static Optional<GridPos> observedActivePlanTransitContinuation(
            final boolean returningInside,
            final boolean destinationWasExplored,
            final ShelterFrame frame,
            final ShelterPlan plan,
            final BitSet planConfirmed,
            final GridPos expectedArrival,
            final Set<GridPos> exploredStands
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(planConfirmed, "planConfirmed");
        Objects.requireNonNull(
                expectedArrival,
                "expectedArrival"
        );
        Objects.requireNonNull(exploredStands, "exploredStands");
        if (returningInside
                || !destinationWasExplored
                || !expectedArrival.equals(frame.feet())
                || currentRole(plan, planConfirmed)
                        .filter(role ->
                                role == ShelterStepRole.ROOF)
                        .isEmpty()) {
            return Optional.empty();
        }
        return activePlanTraversalTarget(
                frame,
                plan,
                planConfirmed,
                exploredStands
        );
    }

    static Optional<GridPos> roofInteriorReturnTraversalTarget(
            final ShelterFrame frame,
            final ShelterPlan plan
    ) {
        return roofInteriorReturnTraversalTarget(
                frame,
                plan,
                Set.of()
        );
    }

    static Optional<GridPos> roofInteriorReturnTraversalTarget(
            final ShelterFrame frame,
            final ShelterPlan plan,
            final Set<GridPos> exploredStands
    ) {
        return roofInteriorReturnTraversalTarget(
                frame,
                plan,
                exploredStands,
                Set.of()
        );
    }

    static Optional<GridPos> roofInteriorReturnTraversalTarget(
            final ShelterFrame frame,
            final ShelterPlan plan,
            final Set<GridPos> exploredStands,
            final Set<GridPos> rejectedStands
    ) {
        return roofInteriorReturnTraversalTarget(
                frame,
                plan,
                exploredStands,
                rejectedStands,
                Set.of()
        );
    }

    static Optional<GridPos> roofInteriorReturnTraversalTarget(
            final ShelterFrame frame,
            final ShelterPlan plan,
            final Set<GridPos> exploredStands,
            final Set<GridPos> rejectedStands,
            final Set<GridPos> bodyVerifiedTransitStands
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(exploredStands, "exploredStands");
        Objects.requireNonNull(rejectedStands, "rejectedStands");
        Objects.requireNonNull(
                bodyVerifiedTransitStands,
                "bodyVerifiedTransitStands"
        );
        final GridPos feet = frame.feet();
        if (isInteriorFloorPosition(plan, feet)) {
            return Optional.empty();
        }
        final GridPos exteriorDoor = exteriorDoorwayStand(plan);
        final GridPos doorway = plan.doorLower();
        final int inwardX = doorway.x() - exteriorDoor.x();
        final int inwardZ = doorway.z() - exteriorDoor.z();
        if (Math.abs(inwardX) + Math.abs(inwardZ) != 1) {
            return Optional.empty();
        }
        final GridPos interior = doorway.offset(
                inwardX,
                0,
                inwardZ
        );
        if (feet.equals(doorway)) {
            return isObservedUnobstructedStand(frame, interior)
                    ? Optional.of(interior)
                    : Optional.empty();
        }
        if (feet.equals(exteriorDoor)) {
            return isObservedUnobstructedStand(frame, doorway)
                    ? Optional.of(doorway)
                    : Optional.empty();
        }
        if (!isExteriorRoofReturnPosition(plan, feet)) {
            return Optional.empty();
        }
        /*
         * The one-cell apron is often a ring, but natural ground or a visible
         * obstruction can interrupt it. Include a small exterior band of
         * ordinary, fairly observed safe terrain so the body can take the
         * same short detour a player would. The complete building footprint
         * is excluded categorically; stale AIR sampled before construction
         * therefore cannot become a route through a finished wall.
         *
         * Search only the incremental first-person navigation evidence. If
         * the full doorway route is proven, return its first ordinary
         * cardinal hop. Otherwise advance toward the closest unvisited safe
         * frontier and survey again. This permits both a necessary temporary
         * move away from the door and incremental discovery of the last
         * doorway cells without authorizing hidden terrain or a direct move
         * through the newly built shell.
         */
        final Set<GridPos> observedExterior =
                frame.navigation().observedVoxels()
                .values()
                .stream()
                .map(ObservedVoxel::position)
                .distinct()
                .filter(candidate ->
                        isExteriorRoofReturnPosition(
                                plan,
                                candidate
                        ))
                .filter(candidate ->
                        !rejectedStands.contains(candidate))
                .filter(candidate ->
                        isObservedUnobstructedStand(
                                frame,
                                candidate
                        ))
                .collect(java.util.stream.Collectors.toSet());
        /*
         * A panoramic fan can retain one voxel at a recently crossed cell
         * while lacking the simultaneous feet/head/support proof required
         * to classify that cell as a new safe stand. If this same body
         * actually stood there on the ground during the current plan, the
         * cell is valid bounded route memory. Keep requiring it to remain
         * present in the incremental first-person map; ordinary MoveTo still
         * revalidates collision and can reject a changed route.
         */
        bodyVerifiedTransitStands.stream()
                .filter(candidate ->
                        isExteriorRoofReturnPosition(
                                plan,
                                candidate
                        ))
                .filter(candidate ->
                        !rejectedStands.contains(candidate))
                .filter(candidate ->
                        frame.navigation()
                                .voxelAt(candidate)
                                .isPresent())
                .forEach(observedExterior::add);
        observedExterior.add(feet);
        if (observedExterior.size() <= 1) {
            return Optional.empty();
        }
        final Comparator<GridPos> returnOrder = Comparator
                .comparingDouble((GridPos candidate) ->
                        horizontalDistance(
                                candidate,
                                exteriorDoor
                        ))
                .thenComparingInt(GridPos::x)
                .thenComparingInt(GridPos::z);
        final ArrayDeque<GridPos> queue = new ArrayDeque<>();
        final Map<GridPos, GridPos> predecessor =
                new HashMap<>();
        final Map<GridPos, Integer> distance =
                new HashMap<>();
        final Set<GridPos> visited = new HashSet<>();
        queue.add(feet);
        visited.add(feet);
        distance.put(feet, 0);
        while (!queue.isEmpty()) {
            final GridPos current = queue.removeFirst();
            final List<GridPos> neighbours =
                    observedExterior.stream()
                            .filter(candidate ->
                                    !visited.contains(candidate))
                            .filter(candidate ->
                                    roofReturnStepAdjacent(
                                            current,
                                            candidate
                                    ))
                            .sorted(returnOrder)
                            .toList();
            for (GridPos neighbour : neighbours) {
                if (!visited.add(neighbour)) {
                    continue;
                }
                predecessor.put(neighbour, current);
                distance.put(
                        neighbour,
                        distance.get(current) + 1
                );
                queue.addLast(neighbour);
            }
        }
        final Optional<GridPos> destination =
                visited.contains(exteriorDoor)
                        ? Optional.of(exteriorDoor)
                        : visited.stream()
                                .filter(candidate ->
                                        !candidate.equals(feet))
                                .filter(candidate ->
                                        !exploredStands.contains(
                                                candidate
                                        ))
                                .min(Comparator
                                        .comparingDouble(
                                                (GridPos candidate) ->
                                                    horizontalDistance(
                                                        candidate,
                                                        exteriorDoor
                                                    )
                                        )
                                        .thenComparingInt(candidate ->
                                                distance.get(candidate))
                                        .thenComparingInt(GridPos::x)
                                        .thenComparingInt(GridPos::z));
        if (destination.isEmpty()) {
            return Optional.empty();
        }
        GridPos step = destination.orElseThrow();
        GridPos prior = predecessor.get(step);
        while (prior != null && !prior.equals(feet)) {
            step = prior;
            prior = predecessor.get(step);
        }
        return prior == null
                ? Optional.empty()
                : Optional.of(step);
    }

    static boolean isExteriorRoofReturnPosition(
            final ShelterPlan plan,
            final GridPos stand
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(stand, "stand");
        /*
         * Natural ground immediately outside a valid flat footprint may rise
         * or fall by one block. Such a cell is not a legal construction apron
         * (that predicate remains exact-plan-Y), but a body stalled there is
         * still outside the completed shell and must use the doorway return
         * state machine instead of aiming through placed roof blocks.
         */
        if (Math.abs(stand.y() - plan.origin().y())
                > MAXIMUM_ROOF_RETURN_SLOPE) {
            return false;
        }
        final int minimumX = plan.origin().x();
        final int maximumX = minimumX
                + plan.exteriorWidth() - 1;
        final int minimumZ = plan.origin().z();
        final int maximumZ = minimumZ
                + plan.exteriorDepth() - 1;
        final int margin = 3;
        final boolean nearShelter =
                stand.x() >= minimumX - margin
                        && stand.x() <= maximumX + margin
                        && stand.z() >= minimumZ - margin
                        && stand.z() <= maximumZ + margin;
        final boolean insideBuildingFootprint =
                stand.x() >= minimumX
                        && stand.x() <= maximumX
                        && stand.z() >= minimumZ
                        && stand.z() <= maximumZ;
        return nearShelter && !insideBuildingFootprint;
    }

    static boolean roofInteriorReturnStillPending(
            final boolean returning,
            final ShelterPlan plan,
            final GridPos feet
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(feet, "feet");
        return returning && !isInteriorFloorPosition(plan, feet);
    }

    private static boolean isObservedUnobstructedStand(
            final ShelterFrame frame,
            final GridPos stand
    ) {
        return isObservedSafeStand(frame, stand)
                && DynamicShelterPlanner
                        .visiblePlacementObstruction(
                                frame,
                                stand
                        )
                        .isEmpty();
    }

    /**
     * Replaces a remembered support hint when the body's latest semantic ray
     * fan reveals another ordinary face that places into the same target cell.
     *
     * <p>This is the construction equivalent of a player shifting their
     * crosshair from the hidden top of the block below to the exposed side of
     * a neighbouring block. Only the support identity is compared: ray hit
     * coordinates naturally vary between samples and must not restart aiming
     * every tick.</p>
     */
    static Optional<BlockInteractionTarget> visibleRetargetForCurrentStep(
            final ShelterFrame frame,
            final ShelterBuildStep step,
            final BlockInteractionTarget current
    ) {
        return visibleRetargetForCurrentStep(
                frame,
                step,
                current,
                Set.of()
        );
    }

    static Optional<BlockInteractionTarget> visibleRetargetForCurrentStep(
            final ShelterFrame frame,
            final ShelterBuildStep step,
            final BlockInteractionTarget current,
            final Set<PlacementSupportIdentity> abandonedSupports
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(
                abandonedSupports,
                "abandonedSupports"
        );
        return preferredVisibleSupport(frame, step)
                .filter(candidate ->
                        candidate.x() != current.x()
                                || candidate.y() != current.y()
                                || candidate.z() != current.z()
                                || candidate.face() != current.face())
                .filter(candidate ->
                        !abandonedSupports.contains(
                                PlacementSupportIdentity.from(candidate)
                        ));
    }

    /**
     * Keeps the generated building plan fixed while allowing a human-like
     * construction order. If the centre crosshair lands on a surface whose
     * adjacent cell is another pending step of the same phase, that exact
     * visible surface is a better next placement than an occluded remembered
     * face.
     */
    private Optional<StepTarget> adaptToCrosshair(
            final ShelterFrame frame,
            final VisibleBlockFace face
    ) {
        if (face.distance() > MAXIMUM_BUILD_REACH
                || activePlan == null
                || executingStep == null) {
            return Optional.empty();
        }
        final Optional<BlockInteractionTarget> exact = toTarget(face);
        if (exact.isEmpty()) {
            return Optional.empty();
        }
        final GridPos adjacent = adjacent(face);
        final Optional<ObservedVoxel> target =
                frame.navigation().voxelAt(adjacent);
        if (target.isEmpty()
                || target.orElseThrow().kind() != VoxelKind.AIR) {
            return Optional.empty();
        }
        return activePlan.steps().stream()
                .filter(step -> !confirmed.get(step.index()))
                .filter(step ->
                        !deferredAimSteps.get(step.index()))
                .filter(step ->
                        canAdaptCrosshairToRole(
                                executingStep.role(),
                                step.role()
                        ))
                .filter(step -> step.target().equals(adjacent))
                .filter(step -> step.role() != ShelterStepRole.DOOR
                        || frame.navigation()
                                .voxelAt(step.target().above())
                                .map(voxel ->
                                        voxel.kind() == VoxelKind.AIR)
                                .orElse(false))
                .findFirst()
                .map(step -> new StepTarget(
                        step,
                        exact.orElseThrow()
                ));
    }

    /**
     * A centre-ray retarget is a local visual shortcut, not permission to
     * reopen an earlier construction phase.  A roof ray can hit a completed
     * wall and its adjacent AIR cell; accepting that cell as a generic
     * structural target changes the executor to a wall step at the wrong
     * height and makes it appear to aim forever.  The normal planner remains
     * responsible for changing roles after the current role is complete.
     */
    static boolean canAdaptCrosshairToRole(
            final ShelterStepRole currentRole,
            final ShelterStepRole candidateRole
    ) {
        return Objects.requireNonNull(currentRole, "currentRole")
                == Objects.requireNonNull(candidateRole, "candidateRole");
    }

    private Optional<SkillTickResult> deferCurrentAimStep(
            final SkillContext context,
            final BuildShelterStepParameters parameters
    ) {
        if (activePlan == null
                || executingStep == null) {
            return Optional.empty();
        }
        final FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                false
        );
        if (validation.failure().isPresent()) {
            return Optional.of(
                    fail(validation.failure().orElseThrow())
            );
        }
        final ShelterFrame frame =
                validation.frame().orElseThrow();
        final int deferredIndex = executingStep.index();
        deferredAimSteps.set(deferredIndex);
        final Optional<ShelterStepRole> role = currentRole(
                activePlan,
                confirmed
        );
        if (role.isEmpty()
                || !requiredItem(
                        activePlan,
                        role.orElseThrow()
                ).equals(frame.mainHand().itemId())) {
            return Optional.empty();
        }
        final Optional<StepTarget> next = selectReachableStep(
                frame,
                activePlan,
                excludingDeferred(
                        confirmed,
                        deferredAimSteps
                ),
                confirmed,
                role.orElseThrow(),
                true
        );
        if (next.isEmpty()) {
            if (deferredRoofFallbackCycleExhausted(
                    activePlan,
                    confirmed,
                    deferredAimSteps,
                    roofInteriorFallbackPriority
            )
                    && relocationSkill.isPresent()
                    && surveySkill.isPresent()
                    && isInteriorFloorPosition(
                            activePlan,
                            frame.feet()
                    )) {
                /*
                 * If every remaining roof cell was deferred from the
                 * interior, the completed wall may have made the last outer
                 * corner reachable only from the apron.  The old fallback
                 * kept the body in the interior mode and re-selected the
                 * same deferred corner forever.  Start one fresh, bounded
                 * exterior search instead: it still uses observed apron
                 * cells and the normal MoveTo/ray/placement path, but clears
                 * only the per-step exhaustion marker so the player can try
                 * the other side of the finished shell.
                 */
                coreActuator.ifPresent(
                        CoreSkillActuator::stop
                );
                attemptedAimVantages.clear();
                attemptedRoofObservationStands.clear();
                activeRoofApronSurveyStand = null;
                clearRoofApronRefresh();
                aimRepositionWatchdog.clear();
                exhaustedExteriorRoofSteps.clear(deferredIndex);
                roofInteriorFallbackPriority = -1;
                returningInsideForRoof = false;
                deferredAimSteps.clear(deferredIndex);
                MinecraftAiCompanion.LOGGER.info(
                        "Restarting observed exterior roof recovery after "
                                + "deferred interior cycle planOrigin={} "
                                + "deferredStep={} deferredCount={} feet={}",
                        activePlan.origin(),
                        deferredIndex,
                        deferredAimSteps.cardinality(),
                        frame.feet()
                );
                return Optional.of(
                        startRelocation(
                                context,
                                parameters,
                                frame
                        )
                );
            }
            return Optional.empty();
        }
        coreActuator.ifPresent(CoreSkillActuator::stop);
        executingStep = next.orElseThrow().step();
        resetJumpAim(executingStep);
        interactionTarget = next.orElseThrow().target();
        abandonedAimSupports.clear();
        aimRepositionAttempts = 0;
        aimRecoverySurveyPerformed = false;
        attemptedAimVantages.clear();
        attemptedRoofObservationStands.clear();
        activeRoofApronSurveyStand = null;
        clearRoofApronRefresh();
        if (shouldClearTraversalHistoryAfterAimDeferral(
                roofInteriorFallbackPriority
        )) {
            exploredActivePlanTraversalStands.clear();
        }
        aimRepositionWatchdog.clear();
        phase = Phase.AIMING;
        beginAim(context, frame);
        MinecraftAiCompanion.LOGGER.info(
                "Deferred occluded shelter step planOrigin={} "
                        + "deferredStep={} nextStep={} deferredCount={}",
                activePlan.origin(),
                deferredIndex,
                executingStep.index(),
                deferredAimSteps.cardinality()
        );
        return Optional.of(
                SkillTickResult.running(true, true)
        );
    }

    private Phase placementInteractionPhase() {
        if (coreActuator.isEmpty()) {
            return Phase.READY;
        }
        /*
         * A roof step is supported only by this plan's already-confirmed
         * upper wall or roof material. It cannot be a chest, furnace, door,
         * or another block whose menu needs sneak suppression. Forcing the
         * normal protective crouch here lowers the eye after a successful
         * jump aim and makes the top face geometrically unreachable again.
         * Dispatch the exact, freshly sampled roof hit directly; all other
         * roles retain the conservative crouch-and-reacquire path.
         */
        return requiresProtectivePlacementSneak(executingStep)
                ? Phase.SNEAKING_FOR_PLACEMENT
                : Phase.READY;
    }

    static boolean requiresProtectivePlacementSneak(
            final ShelterBuildStep step
    ) {
        return step == null
                || step.role() != ShelterStepRole.ROOF;
    }

    /**
     * Walks to an observed stand on the exposed side of a hidden face.
     *
     * <p>Rotating cannot reveal the outside face of a wall when the body is
     * standing inside it. Candidate feet, head, and support cells are taken
     * only from the incremental first-person navigation map. The ordinary
     * movement skill must still plan and physically traverse the route; the
     * block click is retried only after a fresh centre-ray sample.</p>
     */
    private Optional<SkillTickResult> startAimReposition(
            final SkillContext context,
            final BuildShelterStepParameters parameters,
            final ShelterFrame frame,
            final BlockInteractionTarget target
    ) {
        return startAimReposition(
                context,
                parameters,
                frame,
                target,
                false
        );
    }

    private Optional<SkillTickResult> startAimReposition(
            final SkillContext context,
            final BuildShelterStepParameters parameters,
            final ShelterFrame frame,
            final BlockInteractionTarget target,
            final boolean requireJumpHeadroom
    ) {
        final int maximumAttempts =
                activePlan == null || executingStep == null
                        ? MAXIMUM_AIM_REPOSITION_ATTEMPTS
                        : aimRepositionAttemptBudget(
                                activePlan,
                                executingStep
                        );
        if (relocationSkill.isEmpty()) {
            return Optional.empty();
        }
        if (activePlan != null
                && executingStep != null
                && shouldReturnInsideForRoofReposition(
                        activePlan,
                        executingStep,
                        frame.feet(),
                        aimRepositionAttempts,
                        exteriorRoofSearchExhausted(
                                executingStep
                        )
                )) {
            /*
             * A completed outer roof ring hides the inner ring from every
             * ground-level exterior stance. A blocked outer-ring face can
             * likewise exhaust its useful exterior search. Reuse the
             * bounded, fairly observed exterior -> doorway -> interior path
             * instead of walking another circuit and looking through solid
             * blocks.
             */
            coreActuator.ifPresent(CoreSkillActuator::stop);
            exhaustedExteriorRoofSteps.set(
                    executingStep.index()
            );
            roofInteriorFallbackPriority = Math.max(
                    roofInteriorFallbackPriority,
                    constructionPriority(
                            activePlan,
                            executingStep
                    )
            );
            returningInsideForRoof = true;
            activePlanTraversalRelocationAttempts = 0;
            activePlanTraversalDestinationWasExplored = false;
            roofExteriorSurveyRevision = -1;
            attemptedAimVantages.clear();
            attemptedRoofObservationStands.clear();
            activeRoofApronSurveyStand = null;
            clearRoofApronRefresh();
            exploredActivePlanTraversalStands.clear();
            rejectedActivePlanTraversalStands.clear();
            aimRepositionWatchdog.clear();
            MinecraftAiCompanion.LOGGER.info(
                    "Returning through observed doorway for inner roof "
                            + "placement planOrigin={} stepIndex={} "
                            + "from={} target={}",
                    activePlan.origin(),
                    executingStep.index(),
                    frame.feet(),
                    executingStep.target()
            );
            return Optional.of(startRelocation(
                    context,
                    parameters,
                    frame
            ));
        }
        if (aimRepositionAttempts >= maximumAttempts) {
            return Optional.empty();
        }
        final MoveToSkill movement = relocationSkill.orElseThrow();
        final List<GridPos> vantageCandidates =
                aimingVantageCandidates(
                frame,
                target,
                requireJumpHeadroom
        );
        if (vantageCandidates.isEmpty()
                && activePlan != null
                && executingStep != null
                && executingStep.role() == ShelterStepRole.ROOF) {
            final GridPos feet = frame.feet();
            MinecraftAiCompanion.LOGGER.warn(
                    "No shelter aim vantage planOrigin={} stepIndex={} "
                            + "feet={} target={} requireJumpHeadroom={} "
                            + "feetSafe={} head={} support={} "
                            + "jumpHeadroom={} exteriorExhausted={} "
                            + "targetBlocksFeet={} attemptedAims={} "
                            + "observationStands={}",
                    activePlan.origin(),
                    executingStep.index(),
                    feet,
                    target,
                    requireJumpHeadroom,
                    isObservedSafeStand(frame, feet),
                    frame.navigation().voxelAt(feet.above()).orElse(null),
                    frame.navigation().voxelAt(feet.below()).orElse(null),
                    hasObservedJumpHeadroom(frame, feet),
                    exteriorRoofSearchExhausted(executingStep),
                    planTargetBlocksAimVantage(activePlan, feet),
                    attemptedAimVantages,
                    attemptedRoofObservationStands
            );
        }
        for (GridPos stand : vantageCandidates) {
            if (attemptedAimVantages.contains(stand)) {
                continue;
            }
            final MoveToParameters candidate = new MoveToParameters(
                    parameters.dimension(),
                    stand.x() + 0.5,
                    stand.y(),
                    stand.z() + 0.5,
                    CONSTRUCTION_STAND_ARRIVAL_RADIUS
            );
            if (movement.preconditions(context, candidate).isPresent()) {
                continue;
            }
            movement.start(context, candidate);
            inactiveMoveRecoveryAttempts = 0;
            attemptedAimVantages.add(stand);
            activeRoofApronSurveyStand = null;
            activeRepositionParameters = candidate;
            aimRepositionWatchdog.start(
                    context.gameTick(),
                    currentDistanceTo(candidate)
            );
            aimRepositionAttempts++;
            phase = Phase.REPOSITIONING_FOR_AIM;
            aimProgress = AimProgress.REPOSITIONING;
            MinecraftAiCompanion.LOGGER.info(
                    "Starting shelter aim reposition planOrigin={} "
                            + "stepIndex={} stand={} exteriorRoofApron={}",
                    activePlan == null
                            ? null
                            : activePlan.origin(),
                    executingStep == null
                            ? -1
                            : executingStep.index(),
                    stand,
                    activePlan != null
                            && isExteriorRoofApronPosition(
                                    activePlan,
                                    stand
                            )
            );
            return Optional.of(
                    SkillTickResult.running(true, true)
            );
        }
        final Optional<GridPos> observationStaging =
                roofObservationStaging(frame);
        if (observationStaging.isPresent()) {
            final GridPos stand =
                    observationStaging.orElseThrow();
            final MoveToParameters candidate =
                    new MoveToParameters(
                            parameters.dimension(),
                            stand.x() + 0.5,
                            stand.y(),
                            stand.z() + 0.5,
                            CONSTRUCTION_STAND_ARRIVAL_RADIUS
                    );
            if (movement.preconditions(context, candidate).isEmpty()) {
                movement.start(context, candidate);
                inactiveMoveRecoveryAttempts = 0;
                attemptedRoofObservationStands.add(stand);
                activeRoofApronSurveyStand = null;
                activeRepositionParameters = candidate;
                aimRepositionWatchdog.start(
                        context.gameTick(),
                        currentDistanceTo(candidate)
                );
                aimRepositionAttempts++;
                phase = Phase.REPOSITIONING_FOR_AIM;
                aimProgress = AimProgress.REPOSITIONING;
                MinecraftAiCompanion.LOGGER.info(
                        "Moving to open shelter doorway before "
                                + "surveying exterior roof stances "
                                + "planOrigin={} stepIndex={} stand={}",
                        activePlan == null
                                ? null
                                : activePlan.origin(),
                        executingStep == null
                                ? -1
                                : executingStep.index(),
                        stand
                );
                return Optional.of(
                        SkillTickResult.running(true, true)
                );
            }
        }
        if (activePlan != null
                && executingStep != null
                && !exteriorRoofSearchExhausted(
                        executingStep
                )) {
            for (GridPos stand :
                    roofApronObservationStagingCandidates(
                            frame,
                            activePlan,
                            executingStep,
                            attemptedRoofObservationStands
                    )) {
                final MoveToParameters candidate =
                        new MoveToParameters(
                                parameters.dimension(),
                                stand.x() + 0.5,
                                stand.y(),
                                stand.z() + 0.5,
                                CONSTRUCTION_STAND_ARRIVAL_RADIUS
                        );
                if (movement.preconditions(
                        context,
                        candidate
                ).isPresent()) {
                    continue;
                }
                movement.start(context, candidate);
                inactiveMoveRecoveryAttempts = 0;
                attemptedRoofObservationStands.add(stand);
                activeRoofApronSurveyStand = stand;
                activeRepositionParameters = candidate;
                aimRepositionWatchdog.start(
                        context.gameTick(),
                        currentDistanceTo(candidate)
                );
                aimRepositionAttempts++;
                phase = Phase.REPOSITIONING_FOR_AIM;
                aimProgress = AimProgress.REPOSITIONING;
                MinecraftAiCompanion.LOGGER.info(
                        "Following observed exterior shelter apron before "
                                + "surveying far roof support "
                                + "planOrigin={} stepIndex={} stand={} "
                                + "target={} attempt={}",
                        activePlan.origin(),
                        executingStep.index(),
                        stand,
                        executingStep.target(),
                        aimRepositionAttempts
                );
                return Optional.of(
                        SkillTickResult.running(true, true)
                );
            }
        }
        return Optional.empty();
    }

    /**
     * Bounds physical repositioning while allowing a roof search to walk the
     * complete generated apron.
     *
     * <p>An apron search returns one cardinal hop at a time. Those hops may
     * include previously visited transit cells on the route to a new
     * first-person frontier, so the former fixed budget of eight could stop
     * halfway around even the 7x7 compact apron. Two perimeter traversals are
     * finite for every generated scale and leave one return route of margin
     * without turning repeated successful movement into an unbounded loop.</p>
     */
    static int aimRepositionAttemptBudget(
            final ShelterPlan plan,
            final ShelterBuildStep step
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        if (step.role() != ShelterStepRole.ROOF) {
            return MAXIMUM_AIM_REPOSITION_ATTEMPTS;
        }
        final int apronWidth =
                plan.exteriorWidth() + 2;
        final int apronDepth =
                plan.exteriorDepth() + 2;
        final int perimeter =
                2 * (apronWidth + apronDepth) - 4;
        return Math.multiplyExact(perimeter, 2);
    }

    static boolean aimTimeoutRepositionAvailable(
            final ShelterPlan plan,
            final ShelterBuildStep step,
            final int completedAttempts
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        return completedAttempts >= 0
                && completedAttempts
                        < aimRepositionAttemptBudget(plan, step);
    }

    private Optional<GridPos> roofObservationStaging(
            final ShelterFrame frame
    ) {
        if (activePlan == null
                || executingStep == null
                || !shouldStageExteriorRoofObservation(
                        activePlan,
                        executingStep,
                        frame.feet(),
                        exteriorRoofSearchExhausted(
                                executingStep
                        )
                )) {
            return Optional.empty();
        }
        final GridPos doorway = activePlan.doorLower();
        if (attemptedRoofObservationStands.contains(doorway)
                || frame.feet().euclideanDistance(doorway)
                        < 0.75
                || !isObservedSafeStand(frame, doorway)) {
            return Optional.empty();
        }
        return Optional.of(doorway);
    }

    static boolean shouldStageExteriorRoofObservation(
            final ShelterPlan plan,
            final ShelterBuildStep step,
            final GridPos feet
    ) {
        return shouldStageExteriorRoofObservation(
                plan,
                step,
                feet,
                false
        );
    }

    static boolean shouldStageExteriorRoofObservation(
            final ShelterPlan plan,
            final ShelterBuildStep step,
            final GridPos feet,
            final boolean exteriorSearchExhausted
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(feet, "feet");
        /*
         * Exterior staging exists to expose the outside face of the first
         * roof ring. Once that ring is complete, inner cells are worked from
         * the shelter floor. In particular, a model-authorized batch resume
         * must not send an already-interior body out through the doorway only
         * for startAimReposition to route it straight back inside.
         */
        return step.role() == ShelterStepRole.ROOF
                && constructionPriority(plan, step) == 0
                && !exteriorSearchExhausted
                && !isExteriorRoofApronPosition(plan, feet);
    }

    /**
     * Selects one adjacent, not-yet-observed apron footing for a brief
     * player-like path glance.
     *
     * <p>The exterior work strip is discovered incrementally. Reusing only
     * the already safe cells can send the body back around an opaque corner
     * even though the next cardinal footing is one block away. The returned
     * cell is never assumed traversable and is never passed to movement: it
     * is only a camera target. A later semantic sample must independently
     * establish feet, head, and support evidence before movement can use it.</p>
     */
    static Optional<GridPos> roofApronTargetedRefreshCandidate(
            final ShelterFrame frame,
            final ShelterPlan plan,
            final ShelterBuildStep step,
            final Set<GridPos> attempted
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(attempted, "attempted");
        if (step.role() != ShelterStepRole.ROOF
                || !isExteriorRoofApronPosition(
                        plan,
                        frame.feet()
                )) {
            return Optional.empty();
        }
        return List.of(
                        frame.feet().offset(-1, 0, 0),
                        frame.feet().offset(1, 0, 0),
                        frame.feet().offset(0, 0, -1),
                        frame.feet().offset(0, 0, 1)
                ).stream()
                .filter(candidate ->
                        isExteriorRoofApronPosition(
                                plan,
                                candidate
                        ))
                .filter(candidate ->
                        !attempted.contains(candidate))
                .filter(candidate ->
                        !hasCompleteStandObservation(
                                frame,
                                candidate
                        ))
                .min(Comparator
                        .comparingDouble((GridPos candidate) ->
                                horizontalDistance(
                                        candidate,
                                        step.target()
                                ))
                        .thenComparingInt(GridPos::x)
                        .thenComparingInt(GridPos::z));
    }

    private static boolean hasCompleteStandObservation(
            final ShelterFrame frame,
            final GridPos stand
    ) {
        return frame.navigation().voxelAt(stand.below())
                        .isPresent()
                && frame.navigation().voxelAt(stand).isPresent()
                && frame.navigation().voxelAt(stand.above())
                        .isPresent();
    }

    private boolean startRoofApronTargetedRefresh(
            final SkillContext context,
            final ShelterFrame frame
    ) {
        if (activePlan == null
                || executingStep == null
                || coreActuator.isEmpty()
                || coreFrames.isEmpty()) {
            return false;
        }
        final Optional<GridPos> target =
                roofApronTargetedRefreshCandidate(
                        frame,
                        activePlan,
                        executingStep,
                        attemptedRoofObservationStands
                );
        if (target.isEmpty()) {
            return false;
        }
        activeRoofApronRefreshTarget =
                target.orElseThrow();
        roofApronRefreshStartedAtGameTick =
                context.gameTick();
        roofApronRefreshAlignedRevision = -1;
        phase = Phase.REFRESHING_ROOF_APRON;
        aimProgress = AimProgress.REPOSITIONING;
        MinecraftAiCompanion.LOGGER.info(
                "Glancing toward unobserved shelter apron footing "
                        + "planOrigin={} stepIndex={} feet={} target={}",
                activePlan.origin(),
                executingStep.index(),
                frame.feet(),
                activeRoofApronRefreshTarget
        );
        return true;
    }

    private SkillTickResult tickRoofApronRefresh(
            final SkillContext context,
            final BuildShelterStepParameters parameters
    ) {
        final FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                false
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final ShelterFrame frame =
                validation.frame().orElseThrow();
        final Optional<CoreSkillFrame> maybeCore =
                coreFrames.orElseThrow().current();
        if (maybeCore.isEmpty()) {
            return fail(
                    "build_shelter_step."
                            + "roof_apron_refresh_pose_unavailable"
            );
        }
        final CoreSkillFrame core =
                maybeCore.orElseThrow();
        final GridPos refresh =
                Objects.requireNonNull(
                        activeRoofApronRefreshTarget
                );
        if (context.gameTick()
                - roofApronRefreshStartedAtGameTick
                >= ROOF_APRON_REFRESH_TIMEOUT_TICKS) {
            MinecraftAiCompanion.LOGGER.warn(
                    "Shelter apron path glance timed out "
                            + "planOrigin={} stepIndex={} feet={} target={}",
                    activePlan == null
                            ? null
                            : activePlan.origin(),
                    executingStep == null
                            ? -1
                            : executingStep.index(),
                    frame.feet(),
                    refresh
            );
            clearRoofApronRefresh();
            phase = Phase.AIMING;
            beginAim(context, frame);
            return SkillTickResult.running(true, true);
        }

        final PerceptionVec3 targetPoint =
                new PerceptionVec3(
                        refresh.x() + 0.5,
                        refresh.y() + 0.05,
                        refresh.z() + 0.5
                );
        final PerceptionVec3 delta =
                targetPoint.subtract(core.eyePosition());
        if (delta.lengthSquared() <= 1.0E-12) {
            clearRoofApronRefresh();
            phase = Phase.AIMING;
            beginAim(context, frame);
            return SkillTickResult.running(true, true);
        }
        final CoreSkillActuator actions =
                coreActuator.orElseThrow();
        final ActionOutcome stopped = actions.stop();
        final ActionOutcome looked =
                actions.look(lookAt(
                        core.eyePosition(),
                        targetPoint
                ));
        if (!stopped.accepted() || !looked.accepted()) {
            return fail(
                    "build_shelter_step."
                            + "roof_apron_refresh_rejected"
            );
        }
        final double error = angularErrorDegrees(
                core.lookDirection(),
                delta
        );
        if (error
                > ROOF_APRON_REFRESH_ALIGNMENT_DEGREES) {
            roofApronRefreshAlignedRevision = -1;
            return SkillTickResult.running(false, false);
        }
        if (roofApronRefreshAlignedRevision < 0) {
            roofApronRefreshAlignedRevision =
                    frame.observationRevision();
            return SkillTickResult.running(true, true);
        }
        if (frame.observationRevision()
                <= roofApronRefreshAlignedRevision) {
            return SkillTickResult.running(false, false);
        }
        MinecraftAiCompanion.LOGGER.info(
                "Completed targeted shelter apron footing refresh "
                        + "planOrigin={} stepIndex={} feet={} target={} "
                        + "revision={}",
                activePlan == null
                        ? null
                        : activePlan.origin(),
                executingStep == null
                        ? -1
                        : executingStep.index(),
                frame.feet(),
                refresh,
                frame.observationRevision()
        );
        clearRoofApronRefresh();
        phase = Phase.AIMING;
        beginAim(context, frame);
        return SkillTickResult.running(true, true);
    }

    private void clearRoofApronRefresh() {
        activeRoofApronRefreshTarget = null;
        roofApronRefreshStartedAtGameTick = -1;
        roofApronRefreshAlignedRevision = -1;
    }

    /**
     * Chooses ordinary, already observed feet positions along the one-block
     * exterior working strip when the shelter itself occludes the far side.
     *
     * <p>A stationary survey at the doorway cannot see around an opaque
     * corner. These are observation staging positions, not assumed build
     * vantages: each cell still needs known feet/head clearance and support,
     * the normal movement planner must reach it, and another first-person
     * survey runs on arrival before any click is considered.</p>
     */
    static List<GridPos> roofApronObservationStagingCandidates(
            final ShelterFrame frame,
            final ShelterPlan plan,
            final ShelterBuildStep step,
            final Set<GridPos> attempted
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(attempted, "attempted");
        if (step.role() != ShelterStepRole.ROOF) {
            return List.of();
        }
        final int minimumX = plan.origin().x() - 1;
        final int maximumX = plan.origin().x()
                + plan.exteriorWidth();
        final int minimumZ = plan.origin().z() - 1;
        final int maximumZ = plan.origin().z()
                + plan.exteriorDepth();
        final List<GridPos> candidates = new ArrayList<>();
        for (int x = minimumX; x <= maximumX; x++) {
            for (int z = minimumZ; z <= maximumZ; z++) {
                if (x != minimumX && x != maximumX
                        && z != minimumZ && z != maximumZ) {
                    continue;
                }
                final GridPos stand =
                        new GridPos(x, plan.origin().y(), z);
                if (!isExteriorRoofApronPosition(plan, stand)
                        || !isObservedUnobstructedStand(
                                frame,
                                stand
                        )) {
                    continue;
                }
                candidates.add(stand);
            }
        }
        /*
         * A desired support face can be hidden around a convex corner. The
         * first legal move may therefore increase target distance. Traverse
         * the observed-safe apron graph toward an unvisited frontier instead
         * of enforcing monotonic geometric progress. Only the first cardinal
         * hop is returned; another survey must extend the graph before the
         * next move, so stale AIR inside the newly built wall is never a
         * shortcut.
         */
        final Set<GridPos> explored = new HashSet<>(attempted);
        explored.add(frame.feet());
        final Comparator<GridPos> frontierOrder = Comparator
                        .comparingDouble((GridPos stand) ->
                                horizontalDistance(
                                        stand,
                                        step.target()
                                ))
                        .thenComparingDouble(stand ->
                                frame.feet()
                                        .euclideanDistance(stand))
                        .thenComparingInt(GridPos::x)
                        .thenComparingInt(GridPos::z);
        return observedApronFrontierStep(
                frame.feet(),
                candidates,
                explored,
                frontierOrder
        ).map(List::of).orElseGet(List::of);
    }

    private static double horizontalDistance(
            final GridPos first,
            final GridPos second
    ) {
        return Math.hypot(
                first.x() - second.x(),
                first.z() - second.z()
        );
    }

    private boolean isRoofObservationStagingDestination(
            final MoveToParameters parameters
    ) {
        if (activePlan == null
                || executingStep == null
                || executingStep.role()
                        != ShelterStepRole.ROOF) {
            return false;
        }
        final GridPos doorway = activePlan.doorLower();
        return Math.abs(parameters.x()
                        - (doorway.x() + 0.5)) <= 1.0E-6
                && Math.abs(parameters.y()
                        - doorway.y()) <= 1.0E-6
                && Math.abs(parameters.z()
                        - (doorway.z() + 0.5)) <= 1.0E-6;
    }

    private boolean isRoofApronSurveyStagingDestination(
            final MoveToParameters parameters
    ) {
        final GridPos stand = activeRoofApronSurveyStand;
        return stand != null
                && Math.abs(parameters.x()
                        - (stand.x() + 0.5)) <= 1.0E-6
                && Math.abs(parameters.y()
                        - stand.y()) <= 1.0E-6
                && Math.abs(parameters.z()
                        - (stand.z() + 0.5)) <= 1.0E-6;
    }

    private Optional<SkillTickResult> startRoofExteriorDoorExit(
            final SkillContext context,
            final BuildShelterStepParameters parameters
    ) {
        if (activePlan == null
                || executingStep == null
                || executingStep.role()
                        != ShelterStepRole.ROOF
                || relocationSkill.isEmpty()) {
            return Optional.empty();
        }
        final FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                false
        );
        if (validation.failure().isPresent()) {
            return Optional.of(
                    fail(validation.failure().orElseThrow())
            );
        }
        final ShelterFrame frame =
                validation.frame().orElseThrow();
        final GridPos exterior =
                exteriorDoorwayStand(activePlan);
        if (!isObservedSafeStand(frame, exterior)) {
            MinecraftAiCompanion.LOGGER.warn(
                    "Shelter doorway survey did not establish a safe "
                            + "exterior step planOrigin={} stepIndex={} "
                            + "exterior={} feet={} head={} support={}",
                    activePlan.origin(),
                    executingStep.index(),
                    exterior,
                    frame.navigation()
                            .voxelAt(exterior)
                            .orElse(null),
                    frame.navigation()
                            .voxelAt(exterior.above())
                            .orElse(null),
                    frame.navigation()
                            .voxelAt(exterior.below())
                            .orElse(null)
            );
            return Optional.empty();
        }
        final PerceptionVec3 exteriorTarget =
                exteriorDoorwayTarget(activePlan);
        final MoveToParameters move =
                new MoveToParameters(
                        parameters.dimension(),
                        exteriorTarget.x(),
                        exteriorTarget.y(),
                        exteriorTarget.z(),
                        CONSTRUCTION_STAND_ARRIVAL_RADIUS
                );
        final MoveToSkill movement =
                relocationSkill.orElseThrow();
        if (movement.preconditions(context, move).isPresent()) {
            return Optional.empty();
        }
        movement.start(context, move);
        inactiveMoveRecoveryAttempts = 0;
        activeRepositionParameters = move;
        attemptedRoofObservationStands.add(exterior);
        activeRoofApronSurveyStand = null;
        aimRepositionWatchdog.start(
                context.gameTick(),
                currentDistanceTo(move)
        );
        aimRepositionAttempts++;
        pendingRoofDoorExit = false;
        phase = Phase.REPOSITIONING_FOR_AIM;
        aimProgress = AimProgress.REPOSITIONING;
        MinecraftAiCompanion.LOGGER.info(
                "Stepping through open shelter doorway before exterior "
                        + "roof survey planOrigin={} stepIndex={} stand={}",
                activePlan.origin(),
                executingStep.index(),
                exterior
        );
        return Optional.of(
                SkillTickResult.running(true, true)
        );
    }

    static GridPos exteriorDoorwayStand(
            final ShelterPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        final GridPos door = plan.doorLower();
        final int maximumX = plan.origin().x()
                + plan.exteriorWidth() - 1;
        final int maximumZ = plan.origin().z()
                + plan.exteriorDepth() - 1;
        if (door.x() == plan.origin().x()) {
            return door.offset(-1, 0, 0);
        }
        if (door.x() == maximumX) {
            return door.offset(1, 0, 0);
        }
        if (door.z() == plan.origin().z()) {
            return door.offset(0, 0, -1);
        }
        if (door.z() == maximumZ) {
            return door.offset(0, 0, 1);
        }
        throw new IllegalStateException(
                "Shelter door is not on the exterior wall"
        );
    }

    static PerceptionVec3 exteriorDoorwayTarget(
            final ShelterPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        final GridPos door = plan.doorLower();
        final GridPos exterior = exteriorDoorwayStand(plan);
        final int outwardX = Integer.compare(
                exterior.x(),
                door.x()
        );
        final int outwardZ = Integer.compare(
                exterior.z(),
                door.z()
        );
        return new PerceptionVec3(
                exterior.x() + 0.5
                        + outwardX * EXTERIOR_DOORWAY_OUTWARD_BIAS,
                exterior.y(),
                exterior.z() + 0.5
                        + outwardZ * EXTERIOR_DOORWAY_OUTWARD_BIAS
        );
    }

    private boolean isRoofExteriorDoorwayDestination(
            final MoveToParameters parameters
    ) {
        if (activePlan == null
                || executingStep == null
                || executingStep.role()
                        != ShelterStepRole.ROOF) {
            return false;
        }
        final PerceptionVec3 exterior =
                exteriorDoorwayTarget(activePlan);
        return Math.abs(parameters.x()
                        - exterior.x()) <= 1.0E-6
                && Math.abs(parameters.y()
                        - exterior.y()) <= 1.0E-6
                && Math.abs(parameters.z()
                        - exterior.z()) <= 1.0E-6;
    }

    private boolean startAimRecoverySurvey(
            final SkillContext context,
            final BuildShelterStepParameters parameters
    ) {
        if (surveySkill.isEmpty()) {
            return false;
        }
        activeSurveyParameters =
                surveyParameters(parameters);
        final SurveySurroundingsSkill survey =
                surveySkill.orElseThrow();
        final Optional<SkillFailure> rejected =
                survey.preconditions(
                        context,
                        activeSurveyParameters
                );
        if (rejected.isPresent()) {
            activeSurveyParameters = null;
            return false;
        }
        survey.start(context, activeSurveyParameters);
        aimRecoverySurveyPerformed = true;
        phase = Phase.SURVEYING;
        return true;
    }

    private static boolean recoverableAimRouteFailure(
            final SkillFailure failure
    ) {
        return switch (failure.code()) {
            case "move_to.route_unknown",
                    "move_to.planning_budget_exceeded",
                    "move_to.unsupported_micro_vertical",
                    "move_to.turn_stuck",
                    "move_to.stuck" -> true;
            default -> false;
        };
    }

    private void observeAimReposition(
            final SkillContext context,
            final MoveToParameters parameters
    ) {
        aimRepositionWatchdog.observe(
                context.gameTick(),
                currentDistanceTo(parameters)
        );
    }

    private double currentDistanceTo(
            final MoveToParameters parameters
    ) {
        final Optional<CoreSkillFrame> current =
                coreFrames.flatMap(CoreSkillFrameSource::current);
        if (current.isPresent()) {
            return current.orElseThrow()
                    .position()
                    .subtract(parameters.target())
                    .length();
        }
        final ShelterFrame shelter =
                frames.current().orElseThrow();
        final double dx = shelter.feet().x() + 0.5
                - parameters.x();
        final double dy = shelter.feet().y()
                - parameters.y();
        final double dz = shelter.feet().z() + 0.5
                - parameters.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private SkillTickResult recoverExpiredAimReposition(
            final SkillContext context,
            final BuildShelterStepParameters parameters,
            final MoveToParameters expiredTarget,
            final BoundedRepositionProgress.Expiration expiration
    ) {
        final CoreSkillFrame core = coreFrames
                .flatMap(CoreSkillFrameSource::current)
                .orElse(null);
        MinecraftAiCompanion.LOGGER.warn(
                "Shelter aim reposition expired reason={} "
                        + "planOrigin={} stepIndex={} intendedTarget={} "
                        + "candidate=[{},{},{}] feet={} position={} "
                        + "attempt={} tried={} elapsedTicks={} "
                        + "ticksSinceProgress={} bestDistance={}",
                expiration.name().toLowerCase(Locale.ROOT),
                activePlan == null ? null : activePlan.origin(),
                executingStep == null
                        ? -1
                        : executingStep.index(),
                interactionTarget,
                expiredTarget.x(),
                expiredTarget.y(),
                expiredTarget.z(),
                core == null ? null : core.feet(),
                core == null ? null : core.position(),
                aimRepositionAttempts,
                attemptedAimVantages.size(),
                aimRepositionWatchdog.elapsedTicks(
                        context.gameTick()
                ),
                aimRepositionWatchdog.ticksSinceProgress(
                        context.gameTick()
                ),
                aimRepositionWatchdog.bestDistance()
        );
        relocationSkill.orElseThrow().cancel(
                context,
                expiredTarget
        );
        activeRepositionParameters = null;
        activeRoofApronSurveyStand = null;
        aimRepositionWatchdog.clear();
        return recoverAimReposition(context, parameters)
                .orElseGet(() -> fail(
                        "build_shelter_step.aim_reposition_"
                                + expiration.name()
                                        .toLowerCase(Locale.ROOT)
                ));
    }

    private Optional<SkillTickResult> recoverAimReposition(
            final SkillContext context,
            final BuildShelterStepParameters parameters
    ) {
        final FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                false
        );
        if (validation.failure().isPresent()) {
            return Optional.of(
                    fail(validation.failure().orElseThrow())
            );
        }
        final ShelterFrame frame =
                validation.frame().orElseThrow();
        final BlockInteractionTarget target =
                Objects.requireNonNull(interactionTarget);
        final Optional<SkillTickResult> next =
                startAimReposition(
                        context,
                        parameters,
                        frame,
                        target
                );
        if (next.isPresent()) {
            return next;
        }
        if (aimRecoverySurveyPerformed
                || surveySkill.isEmpty()) {
            return Optional.empty();
        }
        return startAimRecoverySurvey(context, parameters)
                ? Optional.of(
                        SkillTickResult.running(true, true)
                )
                : Optional.empty();
    }

    private List<GridPos> aimingVantageCandidates(
            final ShelterFrame frame,
            final BlockInteractionTarget target,
            final boolean requireJumpHeadroom
    ) {
        final int[] basis = switch (target.face()) {
            case NORTH -> new int[]{0, -1, 1, 0};
            case SOUTH -> new int[]{0, 1, 1, 0};
            case WEST -> new int[]{-1, 0, 0, 1};
            case EAST -> new int[]{1, 0, 0, 1};
            case UP, DOWN -> null;
        };
        final GridPos support = new GridPos(
                target.x(),
                target.y(),
                target.z()
        );
        final List<VantageCandidate> candidates = new ArrayList<>();
        final int[] verticalOffsets = {0, -1, 1, -2, 2};
        if (basis == null) {
            /*
             * Looking down at the top of a wall or floor needs a horizontal
             * ring, not a single face normal. This also lets a body that
             * climbed onto its own wall step back down to the surrounding
             * floor before stacking the next layer or roof.
             */
            /*
             * A top face is reachable from the adjacent square as well as
             * from the two-block ring. Starting at radius one matters for a
             * roof edge: the observed exterior apron is exactly one block
             * beyond the completed wall, while a radius-two candidate can
             * fall outside the permitted working strip and leave no legal
             * jump stance at all.
             */
            for (int radius = 1; radius <= 3; radius++) {
                for (int offsetX = -radius;
                        offsetX <= radius;
                        offsetX++) {
                    for (int offsetZ = -radius;
                            offsetZ <= radius;
                            offsetZ++) {
                        if (Math.max(
                                Math.abs(offsetX),
                                Math.abs(offsetZ)
                        ) != radius) {
                            continue;
                        }
                        for (int deltaY : verticalOffsets) {
                            addAimVantageCandidate(
                                    frame,
                                    target,
                                    new GridPos(
                                            support.x() + offsetX,
                                            frame.feet().y() + deltaY,
                                            support.z() + offsetZ
                                    ),
                                    Math.hypot(offsetX, offsetZ)
                                            + Math.abs(deltaY) * 0.2,
                                    requireJumpHeadroom,
                                    candidates
                            );
                        }
                    }
                }
            }
        } else {
            final int normalX = basis[0];
            final int normalZ = basis[1];
            final int tangentX = basis[2];
            final int tangentZ = basis[3];
            final int[] lateralOffsets = {0, -1, 1, -2, 2};
            for (int distance = minimumSideFaceAimDistance();
                    distance <= 3;
                    distance++) {
                for (int lateral : lateralOffsets) {
                    for (int deltaY : verticalOffsets) {
                        addAimVantageCandidate(
                                frame,
                                target,
                                new GridPos(
                                        support.x()
                                                + normalX * distance
                                                + tangentX * lateral,
                                        frame.feet().y() + deltaY,
                                        support.z()
                                                + normalZ * distance
                                                + tangentZ * lateral
                                ),
                                Math.abs(lateral) * 0.15
                                        + distance * 0.05
                                        + Math.abs(deltaY) * 0.2,
                                requireJumpHeadroom,
                                candidates
                        );
                    }
                }
            }
        }
        return candidates.stream()
                .sorted(Comparator
                        .comparingDouble(VantageCandidate::score)
                        .thenComparingInt(candidate ->
                                candidate.stand().y())
                        .thenComparingInt(candidate ->
                                candidate.stand().x())
                        .thenComparingInt(candidate ->
                                candidate.stand().z()))
                .map(VantageCandidate::stand)
                .toList();
    }

    static int minimumSideFaceAimDistance() {
        return 1;
    }

    private void addAimVantageCandidate(
            final ShelterFrame frame,
            final BlockInteractionTarget target,
            final GridPos stand,
            final double geometryPenalty,
            final boolean requireJumpHeadroom,
            final List<VantageCandidate> candidates
    ) {
        final boolean jumpHeadroomRequiredAtStand =
                requireJumpHeadroom
                        && (executingStep == null
                                || aimVantageNeedsObservedJumpHeadroom(
                                        true,
                                        target,
                                        stand,
                                        executingStep.target()
                                ));
        final boolean safeStand = isObservedSafeStand(frame, stand);
        final boolean jumpHeadroom = !jumpHeadroomRequiredAtStand
                || hasObservedJumpHeadroom(frame, stand);
        final boolean separated = frame.feet().euclideanDistance(stand)
                >= 1.0;
        final boolean aimLine = hasObservedAimLine(frame, stand, target);
        final boolean permitted = activePlan == null
                || executingStep == null
                || isPermittedAimTraversalStand(
                        activePlan,
                        executingStep,
                        frame.feet(),
                        stand,
                        exteriorRoofSearchExhausted(executingStep)
                );
        final boolean targetBlocks = activePlan != null
                && planTargetBlocksAimVantage(activePlan, stand);
        if (!safeStand || !jumpHeadroom || !separated || !aimLine
                || !permitted || targetBlocks) {
            return;
        }
        final double eyeX = stand.x() + 0.5;
        final double eyeY = stand.y() + 1.62;
        final double eyeZ = stand.z() + 0.5;
        final double dx = target.hitPoint().x() - eyeX;
        final double dy = target.hitPoint().y() - eyeY;
        final double dz = target.hitPoint().z() - eyeZ;
        if (dx * dx + dy * dy + dz * dz
                > MAXIMUM_BUILD_REACH * MAXIMUM_BUILD_REACH) {
            return;
        }
        final boolean doorwaySurveyedForCurrentStep =
                activePlan != null
                        && roofExteriorSurveyRevision >= 0;
        final boolean interiorAfterDoorwaySurvey =
                doorwaySurveyedForCurrentStep
                        && executingStep != null
                        && executingStep.role()
                                == ShelterStepRole.ROOF
                        && !isExteriorRoofApronPosition(
                                activePlan,
                                stand
                        );
        candidates.add(new VantageCandidate(
                stand,
                frame.feet().euclideanDistance(stand)
                        + geometryPenalty
                        + (interiorAfterDoorwaySurvey
                                ? MAXIMUM_BUILD_REACH * 4.0
                                : 0.0)
        ));
    }

    static boolean planTargetBlocksAimVantage(
            final ShelterPlan plan,
            final GridPos stand
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(stand, "stand");
        /*
         * Structural targets describe future solid collision. Functional
         * targets do not: the generated centre light is still AIR while the
         * roof is built, and an open doorway is deliberately traversable.
         * Current observed collision remains the authority for both.
         */
        return plan.steps().stream()
                .filter(step ->
                        step.role().usesStructuralMaterial())
                .anyMatch(step ->
                        step.target().equals(stand));
    }

    static boolean aimVantageNeedsObservedJumpHeadroom(
            final boolean recoveryRequiresJumpHeadroom,
            final BlockInteractionTarget target,
            final GridPos stand,
            final GridPos placementCell
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(stand, "stand");
        Objects.requireNonNull(placementCell, "placementCell");
        return recoveryRequiresJumpHeadroom
                && requiresJumpToSeePlacementFace(
                        stand.y() + 1.62,
                        target,
                        stand,
                        placementCell
                );
    }

    static boolean hasObservedJumpHeadroom(
            final ShelterFrame frame,
            final GridPos stand
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(stand, "stand");
        return frame.navigation().voxelAt(stand.above(2))
                .filter(NavigationEvidence::hasTraversalClearance)
                .filter(voxel ->
                        voxel.effectiveDanger()
                                <= DynamicShelterPlanner
                                        .MAXIMUM_SITE_DANGER)
                .isPresent();
    }

    /**
     * Rejects vantage points whose eye ray crosses a fairly observed solid
     * voxel before the clicked face. Unknown cells are not treated as hidden
     * knowledge; the later centre-ray check remains authoritative.
     */
    private static boolean hasObservedAimLine(
            final ShelterFrame frame,
            final GridPos stand,
            final BlockInteractionTarget target
    ) {
        final double startX = stand.x() + 0.5;
        final double startY = stand.y() + 1.62;
        final double startZ = stand.z() + 0.5;
        final double dx = target.hitPoint().x() - startX;
        final double dy = target.hitPoint().y() - startY;
        final double dz = target.hitPoint().z() - startZ;
        final int samples = Math.max(
                2,
                (int) Math.ceil(Math.sqrt(
                        dx * dx + dy * dy + dz * dz
                ) * 6.0)
        );
        final GridPos support = new GridPos(
                target.x(),
                target.y(),
                target.z()
        );
        for (int sample = 1; sample < samples; sample++) {
            final double fraction = sample / (double) samples;
            final GridPos voxelPosition = new GridPos(
                    floorToGrid(startX + dx * fraction),
                    floorToGrid(startY + dy * fraction),
                    floorToGrid(startZ + dz * fraction)
            );
            if (voxelPosition.equals(support)) {
                continue;
            }
            final Optional<ObservedVoxel> observed =
                    frame.navigation().voxelAt(voxelPosition);
            if (observed.isPresent()
                    && !observed.orElseThrow().kind().isPassable()) {
                return false;
            }
        }
        return true;
    }

    private static int floorToGrid(final double coordinate) {
        final double floor = Math.floor(coordinate);
        if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Aim coordinate is outside the grid"
            );
        }
        return (int) floor;
    }

    private static boolean isObservedSafeStand(
            final ShelterFrame frame,
            final GridPos stand
    ) {
        final Optional<ObservedVoxel> feet =
                frame.navigation().voxelAt(stand);
        final Optional<ObservedVoxel> head =
                frame.navigation().voxelAt(stand.above());
        final Optional<ObservedVoxel> support =
                frame.navigation().voxelAt(stand.below());
        return feet.isPresent()
                && head.isPresent()
                && support.isPresent()
                && NavigationEvidence.hasTraversalClearance(
                        feet.orElseThrow()
                )
                && NavigationEvidence.hasTraversalClearance(
                        head.orElseThrow()
                )
                && support.orElseThrow().kind().supportsWeight()
                && Math.max(
                        feet.orElseThrow().effectiveDanger(),
                        Math.max(
                                head.orElseThrow().effectiveDanger(),
                                support.orElseThrow().effectiveDanger()
                        )
                ) <= DynamicShelterPlanner.MAXIMUM_SITE_DANGER;
    }

    private String frameVoxelAtCrosshairAdjacent(
            final VisibleBlockFace face
    ) {
        return frames.current()
                .flatMap(frame ->
                        frame.navigation().voxelAt(adjacent(face)))
                .map(voxel ->
                        voxel.kind() + "@"
                                + voxel.observationRevision())
                .orElse("unobserved");
    }

    private String planStepAtCrosshairAdjacent(
            final VisibleBlockFace face
    ) {
        if (activePlan == null) {
            return "no_plan";
        }
        final GridPos target = adjacent(face);
        return activePlan.steps().stream()
                .filter(step -> step.target().equals(target))
                .findFirst()
                .map(step -> step.index() + ":"
                        + step.role() + ":confirmed="
                        + confirmed.get(step.index()))
                .orElse("none");
    }

    private static LookIntent lookAt(
            final PerceptionVec3 eye,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta = target.subtract(eye);
        final float yaw = (float) Math.toDegrees(
                Math.atan2(-delta.x(), delta.z())
        );
        final float pitch = (float) Math.toDegrees(Math.atan2(
                -delta.y(),
                Math.hypot(delta.x(), delta.z())
        ));
        return new LookIntent(yaw, pitch);
    }

    private static double angularErrorDegrees(
            final PerceptionVec3 current,
            final PerceptionVec3 target
    ) {
        final double dot = current.normalized()
                .dot(target.normalized());
        return Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
    }

    private static GridPos adjacent(VisibleBlockFace face) {
        GridPos block = new GridPos(
                face.block().x(),
                face.block().y(),
                face.block().z()
        );
        return switch (face.face()) {
            case "down" -> block.offset(0, -1, 0);
            case "up" -> block.offset(0, 1, 0);
            case "north" -> block.offset(0, 0, -1);
            case "south" -> block.offset(0, 0, 1);
            case "west" -> block.offset(-1, 0, 0);
            case "east" -> block.offset(1, 0, 0);
            default -> block;
        };
    }

    static boolean interactionPlacesStep(
            final BlockInteractionTarget target,
            final ShelterBuildStep step
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(step, "step");
        final GridPos support = new GridPos(
                target.x(),
                target.y(),
                target.z()
        );
        final GridPos placed = switch (target.face()) {
            case DOWN -> support.offset(0, -1, 0);
            case UP -> support.offset(0, 1, 0);
            case NORTH -> support.offset(0, 0, -1);
            case SOUTH -> support.offset(0, 0, 1);
            case WEST -> support.offset(-1, 0, 0);
            case EAST -> support.offset(1, 0, 0);
        };
        return placed.equals(step.target());
    }

    private static Optional<BlockInteractionTarget> toTarget(
            VisibleBlockFace face
    ) {
        final BlockFace blockFace;
        try {
            blockFace = BlockFace.valueOf(
                    face.face().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BlockInteractionTarget(
                    face.block().x(),
                    face.block().y(),
                    face.block().z(),
                    blockFace,
                    new ActionVec3(
                            face.hitPosition().x(),
                            face.hitPosition().y(),
                            face.hitPosition().z()
                    )
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static double matchingFaceDistance(
            List<VisibleBlockFace> faces,
            BlockInteractionTarget target
    ) {
        return faces.stream()
                .filter(face -> face.block().x() == target.x()
                        && face.block().y() == target.y()
                        && face.block().z() == target.z()
                        && face.face().equals(
                                target.face().name().toLowerCase(
                                        Locale.ROOT
                                )
                        ))
                .mapToDouble(VisibleBlockFace::distance)
                .min()
                .orElse(Double.POSITIVE_INFINITY);
    }

    private static boolean placementConfirmed(
            ShelterFrame frame,
            ShelterBuildStep step,
            ShelterPlan plan,
            long afterRevision
    ) {
        String expected = requiredItem(plan, step.role());
        boolean visibleMatch = frame.visibleBlockFaces().stream()
                .filter(face -> face.block().x() == step.target().x()
                        && face.block().y() == step.target().y()
                        && face.block().z() == step.target().z())
                .anyMatch(face -> blockMatches(
                        step.role(),
                        expected,
                        face.blockTypeId()
                ));
        if (visibleMatch) {
            return true;
        }
        Optional<ObservedVoxel> voxel = frame.navigation().voxelAt(
                step.target()
        );
        if (voxel.isEmpty()
                || voxel.orElseThrow().observationRevision()
                <= afterRevision) {
            return false;
        }
        VoxelKind kind = voxel.orElseThrow().kind();
        if (step.role() == ShelterStepRole.DOOR) {
            return kind == VoxelKind.CLOSED_DOOR
                    || kind == VoxelKind.OPEN_DOOR;
        }
        return kind == VoxelKind.SOLID;
    }

    /**
     * Confirms the placed target from the body's exact current centre ray.
     *
     * <p>The semantic fan is deliberately rate-limited and can retain the
     * supporting block for several ticks after a placement.  The new block
     * commonly becomes the centre-ray hit immediately, which is equally
     * fair and stronger evidence than waiting for a peripheral ray to sample
     * it.  This method only accepts the generated target coordinate and
     * expected block type; it does not query the world.</p>
     */
    private boolean crosshairPlacementConfirmed(
            final ShelterBuildStep step,
            final ShelterPlan plan
    ) {
        final String expected = requiredItem(plan, step.role());
        return frames.currentCrosshairBlock()
                .filter(face ->
                        face.block().x() == step.target().x()
                                && face.block().y()
                                == step.target().y()
                                && face.block().z()
                                == step.target().z())
                .map(face -> blockMatches(
                            step.role(),
                            expected,
                            face.blockTypeId()
                ))
                .orElse(false);
    }

    /**
     * Records only the body's fair construction evidence when a dispatched
     * vanilla use packet cannot be confirmed. This deliberately avoids a
     * direct level lookup: diagnostics must not turn into a hidden-world
     * perception path, even in a failure case.
     */
    private void logPlacementConfirmationTimeout() {
        final ShelterFrame frame = frames.current().orElse(null);
        final VisibleBlockFace crosshair =
                frames.currentCrosshairBlock().orElse(null);
        final ShelterBuildStep step = executingStep;
        final Optional<ObservedVoxel> targetVoxel =
                frame == null || step == null
                        ? Optional.empty()
                        : frame.navigation().voxelAt(step.target());
        final List<VisibleBlockFace> targetFaces =
                frame == null || step == null
                        ? List.of()
                        : frame.visibleBlockFaces().stream()
                                .filter(face ->
                                        face.block().x()
                                                == step.target().x()
                                        && face.block().y()
                                                == step.target().y()
                                        && face.block().z()
                                                == step.target().z())
                                .limit(4)
                                .toList();
        MinecraftAiCompanion.LOGGER.warn(
                "Shelter placement confirmation timeout planOrigin={} "
                        + "stepIndex={} role={} intendedTarget={} "
                        + "clickedTarget={} confirmedSteps={} "
                        + "dispatchedRevision={} currentRevision={} "
                        + "dispatchedHand={}x{} currentHand={} "
                        + "feet={} crosshair={} targetVoxel={} "
                        + "targetFaces={}",
                activePlan == null ? null : activePlan.origin(),
                step == null ? -1 : step.index(),
                step == null ? null : step.role(),
                step == null ? null : step.target(),
                interactionTarget,
                confirmed.cardinality(),
                dispatchedObservationRevision,
                frame == null ? -1 : frame.observationRevision(),
                dispatchedMainHandItem,
                dispatchedMainHandCount,
                frame == null ? null : frame.mainHand(),
                frame == null ? null : frame.feet(),
                crosshair,
                targetVoxel.orElse(null),
                targetFaces
        );
    }

    private static boolean blockMatches(
            ShelterStepRole role,
            String itemId,
            String blockId
    ) {
        if (role.usesStructuralMaterial()) {
            return itemId.equals(blockId);
        }
        if (role == ShelterStepRole.DOOR) {
            return itemId.equals(blockId) && blockId.endsWith("_door");
        }
        if (itemId.equals(blockId)) {
            return true;
        }
        return itemId.endsWith(":torch")
                && blockId.endsWith("_wall_torch");
    }

    private static ShelterBuildStep doorStep(ShelterPlan plan) {
        return plan.steps().stream()
                .filter(step -> step.role() == ShelterStepRole.DOOR)
                .findFirst()
                .orElseThrow();
    }

    private SkillTickResult fail(String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(SkillFailure reason) {
        /*
         * A failed parent skill is terminal. Do not leave a nested MoveTo or
         * edge-bridge movement intent running for another tick while the
         * brain records/replans the failure.
         */
        coreActuator.ifPresent(CoreSkillActuator::stop);
        releaseUseIfStillBound();
        activeRelocationParameters = null;
        relocationArrivalWaitStartedAtGameTick = -1;
        activePlanTraversalDestinationWasExplored = false;
        activeRepositionParameters = null;
        activeRoofApronSurveyStand = null;
        clearRoofApronRefresh();
        activeRoofEdgeBridgeParameters = null;
        aimRepositionWatchdog.clear();
        clearPlacementObstructionRecovery();
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private void releaseUseIfStillBound() {
        OptionalLong current = actuator.sessionGeneration();
        if (current.isPresent()
                && current.orElseThrow() == boundSessionGeneration) {
            actuator.releaseUse();
        }
    }

    private static String actionFailure(ActionOutcome outcome) {
        return "build_shelter_step.action_"
                + outcome.name().toLowerCase(Locale.ROOT);
    }

    enum PreparationAdmission {
        EXTERNAL_MODEL_DECISION(true),
        BOUND_INTERNAL_SURVEY(false);

        private final boolean exactAuthoredSample;

        PreparationAdmission(final boolean exactAuthoredSample) {
            this.exactAuthoredSample = exactAuthoredSample;
        }

        boolean requiresExactAuthoredSample() {
            return exactAuthoredSample;
        }
    }

    private enum Phase {
        IDLE,
        SURVEYING,
        SURVEYING_PLACEMENT_REPAIR,
        RELOCATING,
        REPOSITIONING_FOR_AIM,
        REFRESHING_ROOF_APRON,
        CLEARING_PLACEMENT_OBSTRUCTION,
        ROOF_EDGE_BRIDGING,
        EQUIPPING,
        AIMING,
        SNEAKING_FOR_PLACEMENT,
        AIMING_WHILE_SNEAKING,
        READY,
        VERIFYING_PLACEMENT,
        READY_COMPLETE,
        WAITING_FOR_CONFIRMATION,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private enum AimProgress {
        NOT_STARTED("not_started"),
        WAITING_ALIGNMENT("alignment"),
        JUMP_HEADROOM_BLOCKED("jump_headroom_blocked"),
        JUMP_ATTEMPTS_EXHAUSTED("jump_attempts_exhausted"),
        WAITING_JUMP_HEIGHT("jump_height"),
        WAITING_OBSERVATION("observation"),
        CROSSHAIR_EMPTY("crosshair_empty"),
        CROSSHAIR_WRONG_BLOCK("crosshair_wrong_block"),
        CROSSHAIR_WRONG_FACE("crosshair_wrong_face"),
        CROSSHAIR_OUT_OF_REACH("crosshair_out_of_reach"),
        CROSSHAIR_INVALID("crosshair_invalid"),
        REPOSITIONING("repositioning"),
        READY("ready");

        private final String failureSuffix;

        AimProgress(final String failureSuffix) {
            this.failureSuffix = failureSuffix;
        }

        private String failureSuffix() {
            return failureSuffix;
        }
    }

    private record FrameValidation(
            Optional<ShelterFrame> frame,
            Optional<SkillFailure> failure
    ) {
        private static FrameValidation valid(ShelterFrame frame) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.empty()
            );
        }

        private static FrameValidation failed(String code) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }

    private record StepTarget(
            ShelterBuildStep step,
            BlockInteractionTarget target
    ) {
    }

    private record VantageCandidate(
            GridPos stand,
            double score
    ) {
    }

    record PlacementSupportIdentity(
            int x,
            int y,
            int z,
            BlockFace face
    ) {
        static PlacementSupportIdentity from(
                final BlockInteractionTarget target
        ) {
            Objects.requireNonNull(target, "target");
            return new PlacementSupportIdentity(
                    target.x(),
                    target.y(),
                    target.z(),
                    target.face()
            );
        }
    }

    private record Preparation(
            Optional<ShelterFrame> frame,
            Optional<ShelterPlan> plan,
            Optional<ShelterBuildStep> step,
            Optional<BlockInteractionTarget> target,
            Optional<EquipItemParameters> equipment,
            boolean surveyRequired,
            boolean relocationAllowed,
            boolean complete,
            Optional<SkillFailure> failure
    ) {
        private static Preparation ready(
                ShelterFrame frame,
                ShelterPlan plan,
                ShelterBuildStep step,
                BlockInteractionTarget target,
                Optional<EquipItemParameters> equipment
        ) {
            return new Preparation(
                    Optional.of(frame),
                    Optional.of(plan),
                    Optional.of(step),
                    Optional.of(target),
                    equipment,
                    false,
                    false,
                    false,
                    Optional.empty()
            );
        }

        private static Preparation complete(
                ShelterFrame frame,
                ShelterPlan plan
        ) {
            return new Preparation(
                    Optional.of(frame),
                    Optional.of(plan),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    false,
                    false,
                    true,
                    Optional.empty()
            );
        }

        private static Preparation survey(ShelterFrame frame) {
            return new Preparation(
                    Optional.of(frame),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    true,
                    true,
                    false,
                    Optional.empty()
            );
        }

        private static Preparation surveyForStep(
                ShelterFrame frame
        ) {
            return new Preparation(
                    Optional.of(frame),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    true,
                    true,
                    false,
                    Optional.empty()
            );
        }

        private static Preparation failed(SkillFailure failure) {
            return new Preparation(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    false,
                    false,
                    false,
                    Optional.of(failure)
            );
        }

        private static Preparation failed(String code) {
            return failed(SkillFailure.of(code));
        }
    }
}
