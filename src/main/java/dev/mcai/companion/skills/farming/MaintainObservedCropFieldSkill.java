package dev.mcai.companion.skills.farming;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.mechanism.CropFieldMaintenancePlan;
import dev.mcai.companion.mechanism.CropFieldMaintenancePlanningResult;
import dev.mcai.companion.mechanism.CropFieldMaintenanceRequest;
import dev.mcai.companion.mechanism.CropFieldVariant;
import dev.mcai.companion.mechanism.HydratedCropFieldPlanService;
import dev.mcai.companion.mechanism.MechanismSiteSurvey;
import dev.mcai.companion.mechanism.MechanismSiteSurveyAccumulator;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.building.ShelterFrame;
import dev.mcai.companion.skills.building.ShelterFrameSource;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.CoreSkillPolicy;
import dev.mcai.companion.skills.core.MoveToParameters;
import dev.mcai.companion.skills.core.MoveToSkill;
import dev.mcai.companion.skills.core.TravelSkillPolicy;
import dev.mcai.companion.skills.core.TravelToParameters;
import dev.mcai.companion.skills.core.TravelToSkill;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.skills.survey.SurveyResultBuffer;
import dev.mcai.companion.skills.survey.SurveySurroundingsParameters;
import dev.mcai.companion.skills.survey.SurveySurroundingsSkill;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Maintains mature crops found by a deliberate first-person survey.  The
 * initial survey may order work, but each harvest is separately approached,
 * re-observed, authorized, performed, replanted, and verified through vanilla
 * player actions.
 */
public final class MaintainObservedCropFieldSkill
        implements Skill<MaintainObservedCropFieldParameters> {
    public static final String NAME = "maintain_observed_crop_field";
    private static final int SURVEY_HORIZONTAL_STEPS = 8;
    private static final int MAXIMUM_TOTAL_TICKS = 24_000;
    private static final int MAXIMUM_PLANNING_WAIT_TICKS = 400;
    private static final int MAXIMUM_AIM_TICKS = 80;
    private static final int MAXIMUM_DISCOVERY_ROUNDS = 16;
    private static final int MAXIMUM_VERIFICATION_REDISCOVERY_ROUNDS = 4;
    private static final int FINAL_SETTLE_TICKS = 80;
    private static final double AIM_ALIGNMENT_DEGREES = 2.0;
    private static final double STAND_ARRIVAL_RADIUS = 0.35;
    /*
     * This only decides whether to try a current first-person look. It does
     * not authorize a block action: tickAiming and the interaction actuator
     * still require the current ray-visible face, policy distance, vanilla
     * reach, and occlusion checks. Three and a half blocks is conservative
     * player reach and avoids routing to an obsolete marker beside a crop
     * that is already plainly within interaction range.
     */
    private static final double DIRECT_HARVEST_HORIZONTAL_DISTANCE = 3.50;
    private static final double NORMAL_MAXIMUM_DANGER = 0.20;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.08;
    private static final TravelSkillPolicy PATROL_TRAVEL_POLICY =
            new TravelSkillPolicy(
                    4.0,
                    12,
                    64,
                    240,
                    80,
                    40,
                    8,
                    2,
                    30.0F,
                    128,
                    NORMAL_MAXIMUM_DANGER,
                    HARDCORE_MAXIMUM_DANGER
            );

    private final UUID expectedPlayerId;
    private final CoreSkillActuator coreActuator;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactionActuator;
    private final InteractionSkillFrameSource interactionFrames;
    private final ShelterFrameSource shelterFrames;
    private final HydratedCropFieldPlanService planService;
    private final FarmingSkillPolicy farmingPolicy;
    private final MechanismSiteSurveyAccumulator surveyAccumulator;
    private final SurveyResultBuffer surveyResults = new SurveyResultBuffer();

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long phaseStartedAtTick = -1;
    private long boundSessionGeneration = -1;
    private long boundGoalRevision = -1;
    private long lastAccumulatedRevision = -1;
    private SurveySurroundingsSkill surveySkill;
    private SurveySurroundingsParameters surveyParameters;
    private CompletableFuture<CropFieldMaintenancePlanningResult> pendingPlan;
    private CropFieldMaintenancePlan plan;
    private int cellIndex;
    private int discoveryRounds;
    private int initialOutputCount = -1;
    private final Set<GridPos> completedCrops = new HashSet<>();
    private final List<CropFieldMaintenancePlan.Cell> completedCells =
            new ArrayList<>();
    private final Set<GridPos> verifiedCrops = new HashSet<>();
    private int verificationIndex;
    private int verificationRediscoveryRounds;
    private List<GridPos> activeStandCandidates = List.of();
    private int activeStandIndex;
    private MoveToSkill movement;
    private MoveToParameters movementParameters;
    private TravelToSkill patrolMovement;
    private TravelToParameters patrolMovementParameters;
    private boolean verificationMovement;
    private boolean discoveryMovement;
    private final Set<GridPos> patrolSupports = new HashSet<>();
    private final Map<GridPos, Integer> patrolVisits = new HashMap<>();
    private long aimStartedRevision = -1;
    private HarvestAndReplantStepSkill harvestSkill;
    private HarvestAndReplantParameters harvestParameters;
    private String lastChildFailure = "";
    /* Diagnostic-only nested checkpoint. It records the bounded child state
     * in the parent checkpoint so a failed real-world farming run can show
     * the exact observed water escape without granting extra authority. */
    private String lastChildCheckpoint = "";

    public MaintainObservedCropFieldSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator coreActuator,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactionActuator,
            final InteractionSkillFrameSource interactionFrames,
            final ShelterFrameSource shelterFrames,
            final HydratedCropFieldPlanService planService
    ) {
        this(
                expectedPlayerId,
                coreActuator,
                coreFrames,
                interactionActuator,
                interactionFrames,
                shelterFrames,
                planService,
                FarmingSkillPolicy.defaults(),
                new MechanismSiteSurveyAccumulator()
        );
    }

    MaintainObservedCropFieldSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator coreActuator,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactionActuator,
            final InteractionSkillFrameSource interactionFrames,
            final ShelterFrameSource shelterFrames,
            final HydratedCropFieldPlanService planService,
            final FarmingSkillPolicy farmingPolicy,
            final MechanismSiteSurveyAccumulator surveyAccumulator
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.coreActuator = Objects.requireNonNull(
                coreActuator,
                "coreActuator"
        );
        this.coreFrames = Objects.requireNonNull(coreFrames, "coreFrames");
        this.interactionActuator = Objects.requireNonNull(
                interactionActuator,
                "interactionActuator"
        );
        this.interactionFrames = Objects.requireNonNull(
                interactionFrames,
                "interactionFrames"
        );
        this.shelterFrames = Objects.requireNonNull(
                shelterFrames,
                "shelterFrames"
        );
        this.planService = Objects.requireNonNull(planService, "planService");
        this.farmingPolicy = Objects.requireNonNull(
                farmingPolicy,
                "farmingPolicy"
        );
        this.surveyAccumulator = Objects.requireNonNull(
                surveyAccumulator,
                "surveyAccumulator"
        );
    }

    @Override
    public SkillParameterParser<MaintainObservedCropFieldParameters>
            parameters() {
        return FarmingSkillParameters::parseMaintainObservedCropField;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<SkillFailure> binding = bindingFailure(parameters);
        if (binding.isPresent()) {
            return binding;
        }
        final CoreSkillFrame core = coreFrames.current().orElseThrow();
        final double maximumDanger = maximumDanger(context);
        if (context.riskScore() > maximumDanger
                || core.danger() > maximumDanger) {
            return Optional.of(failure("danger_detected"));
        }
        return newSurveySkill().preconditions(
                context,
                surveyParameters(parameters)
        ).map(reason -> failure("survey_rejected"));
    }

    @Override
    public void start(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        final Optional<SkillFailure> invalid = preconditions(
                context,
                parameters
        );
        if (invalid.isPresent()) {
            throw new IllegalStateException(
                    "Crop-maintenance binding changed before start"
            );
        }
        clearExecutionState();
        final ShelterFrame shelter = shelterFrames.current().orElseThrow();
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        boundSessionGeneration = shelter.sessionGeneration();
        boundGoalRevision = context.goalRevision();
        startSurvey(context, parameters, Phase.SURVEYING);
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        if (phase == Phase.COMPLETED) {
            return SkillTickResult.completed();
        }
        if (phase == Phase.FAILED) {
            return SkillTickResult.failed(Objects.requireNonNull(failure));
        }
        if (phase == Phase.IDLE || phase == Phase.CANCELLED) {
            return SkillTickResult.failed(failure("invalid_state"));
        }
        if (context.goalRevision() != boundGoalRevision) {
            return fail(context, parameters, "goal_changed");
        }
        if (context.gameTick() - startedAtTick >= MAXIMUM_TOTAL_TICKS) {
            return fail(context, parameters, "timed_out");
        }
        final Optional<SkillFailure> binding = bindingFailure(parameters);
        if (binding.isPresent()) {
            return fail(context, parameters, binding.orElseThrow());
        }
        /* Keep the bounded site map current while walking and interacting,
         * not only while deliberately surveying. This replaces a harvested
         * mature-crop sample with the newly seen substrate/replant and adds
         * ordinary body-route evidence without any world accessor. */
        accumulateCurrentSurvey();
        final CoreSkillFrame core = coreFrames.current().orElseThrow();
        final double maximumDanger = maximumDanger(context);
        if (context.riskScore() > maximumDanger
                || core.danger() > maximumDanger) {
            return fail(context, parameters, "danger_detected");
        }
        if (visibleReplantContradiction(parameters).isPresent()) {
            return fail(context, parameters, "replant_regressed");
        }
        return switch (phase) {
            case SURVEYING -> tickSurvey(context, parameters, false);
            case PLANNING -> tickPlanning(context, parameters);
            case SELECTING_CELL -> startNextCell(context, parameters);
            case SELECTING_VERIFICATION -> startNextVerification(
                    context,
                    parameters
            );
            case MOVING -> tickMovement(context, parameters);
            case AIMING -> tickAiming(context, parameters);
            case VERIFYING -> tickVerificationAim(context, parameters);
            case HARVESTING -> tickHarvest(context, parameters);
            case SETTLING -> tickSettling(context, parameters);
            case RESURVEYING -> tickSurvey(context, parameters, true);
            default -> SkillTickResult.failed(failure("invalid_state"));
        };
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        final PerceptionVec3 position = coreFrames.current()
                .map(CoreSkillFrame::position)
                .orElse(new PerceptionVec3(0.0, 0.0, 0.0));
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"crop\":\"%s\",\"cell\":%d,"
                                + "\"cells\":%d,\"completed\":%d,"
                        + "\"verified\":%d,"
                        + "\"stand\":%d,\"stands\":%d,"
                        + "\"target\":\"%s\","
                        + "\"moveTarget\":\"%s\","
                        + "\"moveState\":\"%s\","
                                + "\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,"
                                + "\"lastChildFailure\":\"%s\","
                                + "\"lastChildCheckpoint\":\"%s\","
                                + "\"session\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.crop().name().toLowerCase(Locale.ROOT),
                        cellIndex,
                        plan == null ? 0 : plan.cells().size(),
                        completedCrops.size(),
                        verifiedCrops.size(),
                        activeStandIndex,
                        activeStandCandidates.size(),
                        checkpointTarget(),
                        checkpointMovementTarget(),
                        checkpointMovementState(context),
                        position.x(),
                        position.y(),
                        position.z(),
                        lastChildFailure,
                        lastChildCheckpoint,
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        cancelChildren(context, parameters);
        coreActuator.stop();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(failure("invalid_state"));
        };
    }

    private SkillTickResult tickSurvey(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters,
            final boolean finalVerification
    ) {
        accumulateCurrentSurvey();
        final SkillTickResult surveyed = surveySkill.tick(
                context,
                surveyParameters
        );
        accumulateCurrentSurvey();
        if (surveyed.status() == SkillTickResult.Status.FAILED) {
            return fail(
                    context,
                    parameters,
                    finalVerification
                            ? "final_survey_failed"
                            : "site_survey_failed"
            );
        }
        if (surveyed.status() != SkillTickResult.Status.COMPLETED) {
            return surveyed;
        }
        if (finalVerification) {
            phase = Phase.SELECTING_VERIFICATION;
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        final Optional<MechanismSiteSurvey> survey =
                surveyAccumulator.current();
        if (survey.isEmpty()) {
            return fail(context, parameters, "site_survey_empty");
        }
        try {
            pendingPlan = Objects.requireNonNull(
                    planService.planMaintenance(
                            survey.orElseThrow(),
                            new CropFieldMaintenanceRequest(
                                    parameters.crop(),
                                    Math.max(
                                            1,
                                            parameters.maximumPlants()
                                                    - completedCrops.size()
                                    )
                            )
                    )
            );
        } catch (RuntimeException rejected) {
            return fail(context, parameters, "planner_unavailable");
        }
        phase = Phase.PLANNING;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickPlanning(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_PLANNING_WAIT_TICKS) {
            return fail(context, parameters, "planning_timed_out");
        }
        if (pendingPlan == null || !pendingPlan.isDone()) {
            return SkillTickResult.running(false, true);
        }
        final CropFieldMaintenancePlanningResult result;
        try {
            result = pendingPlan.join();
        } catch (RuntimeException exception) {
            return fail(context, parameters, "planning_failed");
        }
        pendingPlan = null;
        if (result.plan().isEmpty()) {
            final String plannerCode = result.failureCode().orElse("");
            if ("maintenance.no_mature_crop".equals(plannerCode)
                    && !completedCrops.isEmpty()) {
                coreActuator.stop();
                phase = Phase.SETTLING;
                phaseStartedAtTick = context.gameTick();
                return SkillTickResult.running(true, true);
            }
            if ("maintenance.no_safe_stand".equals(plannerCode)
                    && !completedCrops.isEmpty()) {
                lastChildFailure = plannerCode;
                return rediscover(context, parameters);
            }
            return fail(
                    context,
                    parameters,
                    plannerFailure(plannerCode)
            );
        }
        plan = result.plan().orElseThrow();
        final ShelterFrame current = shelterFrames.current().orElseThrow();
        if (!plan.dimension().equals(parameters.dimension())
                || plan.sourceRevision() > current.observationRevision()
                || plan.crop() != parameters.crop()) {
            return fail(context, parameters, "plan_binding_changed");
        }
        if (initialOutputCount < 0) {
            initialOutputCount = itemCount(
                    surveyAccumulator.current().orElseThrow().inventory(),
                    parameters.crop().outputItemId()
            );
        }
        plan.cells().stream()
                .flatMap(cell -> cell.workStandSupports().stream())
                .forEach(patrolSupports::add);
        cellIndex = 0;
        phase = Phase.SELECTING_CELL;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult startNextCell(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        if (cellIndex >= plan.cells().size()) {
            if (completedCrops.size() < parameters.maximumPlants()) {
                return rediscover(context, parameters);
            }
            coreActuator.stop();
            phase = Phase.SETTLING;
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        activeStandCandidates = plan.cells().get(cellIndex)
                .workStandSupports();
        /*
         * The survey just proved this crop was visible from the present
         * observation area. Re-aim and demand a newer interaction frame
         * before adding movement. If the target is now occluded or outside
         * vanilla reach, the bounded work-stand routes remain the fallback.
         */
        final CoreSkillFrame core = coreFrames.current().orElseThrow();
        if (directHarvestPose(core, currentCell().cropPosition())) {
            activeStandIndex = -1;
            aimStartedRevision = interactionFrames.current()
                    .map(InteractionSkillFrame::observationRevision)
                    .orElse(-1L);
            phase = Phase.AIMING;
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        activeStandIndex = 0;
        return startMovement(context, parameters);
    }

    private SkillTickResult startMovement(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        return startMovement(context, parameters, false);
    }

    private SkillTickResult startMovement(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters,
            final boolean verification
    ) {
        if (activeStandIndex >= activeStandCandidates.size()) {
            return verification
                    ? retryVerificationSurvey(context, parameters)
                    : deferCurrentCell(context, parameters);
        }
        final GridPos stand = activeStandCandidates.get(activeStandIndex);
        movementParameters = new MoveToParameters(
                parameters.dimension(),
                stand.x() + 0.5,
                stand.y() + 1.0,
                stand.z() + 0.5,
                STAND_ARRIVAL_RADIUS
        );
        movement = new MoveToSkill(
                expectedPlayerId,
                coreActuator,
                coreFrames,
                true,
                true
        );
        final Optional<SkillFailure> rejected = movement.preconditions(
                context,
                movementParameters
        );
        if (rejected.isPresent()) {
            activeStandIndex++;
            return startMovement(context, parameters, verification);
        }
        movement.start(context, movementParameters);
        verificationMovement = verification;
        discoveryMovement = false;
        phase = Phase.MOVING;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickMovement(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        if (discoveryMovement && patrolMovement != null) {
            return tickDiscoveryMovement(context, parameters);
        }
        final CoreSkillFrame core = coreFrames.current().orElseThrow();
        if (!verificationMovement
                && movement != null
                && movementParameters != null
                && directHarvestPose(
                        core,
                        currentCell().cropPosition()
                )) {
            /*
             * A work stand is only a navigation hint. Vanilla movement or a
             * previous pickup can bring the body into legitimate reach before
             * that exact marker is reached. A human stops routing at this
             * point; cancel the stale child and require the normal newer
             * first-person crop authorization instead of orbiting the field.
             */
            movement.cancel(context, movementParameters);
            movement = null;
            movementParameters = null;
            aimStartedRevision = interactionFrames.current()
                    .map(InteractionSkillFrame::observationRevision)
                    .orElse(-1L);
            phase = Phase.AIMING;
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        final SkillTickResult result = movement.tick(
                context,
                movementParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            lastChildFailure = result.failure()
                    .map(SkillFailure::code)
                    .orElse("move_to.unknown_failure");
            movement = null;
            movementParameters = null;
            activeStandIndex++;
            if (discoveryMovement) {
                startDiscoveryMovement(context, parameters);
                return SkillTickResult.running(true, true);
            }
            return startMovement(
                    context,
                    parameters,
                    verificationMovement
            );
        }
        if (result.status() != SkillTickResult.Status.COMPLETED) {
            return result;
        }
        movement = null;
        movementParameters = null;
        if (discoveryMovement) {
            discoveryMovement = false;
            coreActuator.stop();
            startSurvey(context, parameters, Phase.SURVEYING);
            return SkillTickResult.running(true, true);
        }
        aimStartedRevision = interactionFrames.current()
                .map(InteractionSkillFrame::observationRevision)
                .orElse(-1L);
        phase = verificationMovement ? Phase.VERIFYING : Phase.AIMING;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickAiming(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        if (context.gameTick() - phaseStartedAtTick >= MAXIMUM_AIM_TICKS) {
            lastChildFailure = "target_not_reobserved";
            activeStandIndex++;
            return startMovement(context, parameters);
        }
        final GridPos cropPosition = currentCell().cropPosition();
        final CoreSkillFrame core = coreFrames.current().orElseThrow();
        if (!core.onGround() || core.inWater()) {
            lastChildFailure = "unsafe_harvest_pose";
            return rediscover(context, parameters);
        }
        final LookIntent intent = lookAt(
                core.eyePosition(),
                new PerceptionVec3(
                        cropPosition.x() + 0.5,
                        cropPosition.y() + 0.5,
                        cropPosition.z() + 0.5
                )
        );
        final ActionOutcome stopped = coreActuator.stop();
        final ActionOutcome looked = coreActuator.look(intent);
        if (!stopped.accepted() || !looked.accepted()) {
            return fail(context, parameters, "look_rejected");
        }
        if (angularError(core.lookDirection(), direction(intent))
                > AIM_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        final Optional<InteractionSkillFrame> current =
                interactionFrames.current();
        if (current.isEmpty()
                || current.orElseThrow().observationRevision()
                        <= aimStartedRevision) {
            return SkillTickResult.running(false, false);
        }
        final InteractionSkillFrame frame = current.orElseThrow();
        final CropKind crop = cropKind(parameters.crop());
        final Optional<VisibleBlockFace> visible = frame
                .visibleBlockFaces().stream()
                .filter(face -> sameBlock(face, cropPosition))
                .filter(crop::isMature)
                .filter(face -> face.distance()
                        <= farmingPolicy.maximumCandidateDistance())
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
        if (visible.isEmpty()) {
            return SkillTickResult.running(false, false);
        }
        final VisibleBlockFace face = visible.orElseThrow();
        final Optional<BlockFace> blockFace = blockFace(face.face());
        if (blockFace.isEmpty()) {
            return fail(context, parameters, "invalid_observed_face");
        }
        harvestParameters = new HarvestAndReplantParameters(
                parameters.dimension(),
                crop,
                new ObservedBlockTarget(
                        frame.observationRevision(),
                        cropPosition.x(),
                        cropPosition.y(),
                        cropPosition.z(),
                        blockFace.orElseThrow()
                ),
                authorizedPickupCells()
        );
        harvestSkill = new HarvestAndReplantStepSkill(
                expectedPlayerId,
                coreActuator,
                coreFrames,
                interactionActuator,
                interactionFrames,
                farmingPolicy
        );
        final Optional<SkillFailure> rejected = harvestSkill.preconditions(
                context,
                harvestParameters
        );
        if (rejected.isPresent()) {
            lastChildFailure = rejected.orElseThrow().code();
            return fail(context, parameters, "harvest_step_rejected");
        }
        harvestSkill.start(context, harvestParameters);
        phase = Phase.HARVESTING;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    /**
     * The field plan is itself the parent survey's authority for bounded
     * pickup transit.  Convert only its already-proven work-stand supports to
     * player feet cells; no new world or block lookup is performed here.
     */
    private Set<GridPos> authorizedPickupCells() {
        if (plan == null) {
            return Set.of();
        }
        final Set<GridPos> result = new HashSet<>();
        plan.cells().forEach(cell -> {
            result.add(cell.cropPosition());
            cell.workStandSupports().forEach(
                    support -> result.add(support.above())
            );
        });
        return Set.copyOf(result);
    }

    private SkillTickResult tickHarvest(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        final SkillTickResult result = harvestSkill.tick(
                context,
                harvestParameters
        );
        lastChildCheckpoint = harvestSkill.checkpoint(
                context,
                harvestParameters
        ).payload().replace('"', '\'');
        if (result.status() == SkillTickResult.Status.FAILED) {
            lastChildFailure = result.failure().orElseThrow().code()
                    + "@"
                    + harvestSkill.checkpoint(context, harvestParameters)
                            .payload().replace('"', '\'');
            return fail(context, parameters, "harvest_step_failed");
        }
        if (result.status() != SkillTickResult.Status.COMPLETED) {
            return result;
        }
        completedCrops.add(currentCell().cropPosition());
        completedCells.add(currentCell());
        /* The child now requires a fresh visible crop face (semantic or
         * current crosshair) after the vanilla use, and can retry an exact
         * substrate hit when a first packet consumed a seed without placing.
         * Preserve that stronger per-cell transaction proof for final state. */
        verifiedCrops.add(currentCell().cropPosition());
        discoveryRounds = 0;
        harvestSkill = null;
        harvestParameters = null;
        cellIndex++;
        phase = Phase.SELECTING_CELL;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickSettling(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        coreActuator.stop();
        if (context.gameTick() - phaseStartedAtTick < FINAL_SETTLE_TICKS) {
            return SkillTickResult.running(false, true);
        }
        verificationIndex = 0;
        phase = Phase.SELECTING_VERIFICATION;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult startNextVerification(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        if (verificationIndex >= completedCells.size()) {
            return verifyFinalState(context, parameters);
        }
        final GridPos cropPosition = completedCells.get(
                verificationIndex
        ).cropPosition();
        if (verifiedCrops.contains(cropPosition)) {
            verificationIndex++;
            return SkillTickResult.running(true, true);
        }
        final CropKind crop = cropKind(parameters.crop());
        final boolean currentlyVisible = interactionFrames.current()
                .stream()
                .flatMap(frame -> frame.visibleBlockFaces().stream())
                .filter(face -> sameBlock(face, cropPosition))
                .anyMatch(crop::isPlant);
        if (currentlyVisible) {
            verifiedCrops.add(cropPosition);
            verificationRediscoveryRounds = 0;
            verificationIndex++;
            return SkillTickResult.running(true, true);
        }
        activeStandCandidates = completedCells.get(verificationIndex)
                .workStandSupports();
        /*
         * Look from the current body position before walking back to the
         * historical work stand. A small field is often directly visible,
         * and forcing an old route first needlessly depends on stale local
         * navigation evidence.
         */
        activeStandIndex = -1;
        verificationMovement = true;
        aimStartedRevision = interactionFrames.current()
                .map(InteractionSkillFrame::observationRevision)
                .orElse(-1L);
        phase = Phase.VERIFYING;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickVerificationAim(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        if (context.gameTick() - phaseStartedAtTick >= MAXIMUM_AIM_TICKS) {
            activeStandIndex++;
            return startMovement(context, parameters, true);
        }
        final GridPos cropPosition = completedCells.get(
                verificationIndex
        ).cropPosition();
        final CoreSkillFrame core = coreFrames.current().orElseThrow();
        final LookIntent intent = lookAt(
                core.eyePosition(),
                new PerceptionVec3(
                        cropPosition.x() + 0.5,
                        cropPosition.y() + 0.3,
                        cropPosition.z() + 0.5
                )
        );
        final ActionOutcome stopped = coreActuator.stop();
        final ActionOutcome looked = coreActuator.look(intent);
        if (!stopped.accepted() || !looked.accepted()) {
            return fail(context, parameters, "verification_look_rejected");
        }
        if (angularError(core.lookDirection(), direction(intent))
                > AIM_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        final Optional<InteractionSkillFrame> current =
                interactionFrames.current();
        if (current.isEmpty()
                || current.orElseThrow().observationRevision()
                        <= aimStartedRevision) {
            return SkillTickResult.running(false, false);
        }
        final List<VisibleBlockFace> targetFaces = current.orElseThrow()
                .visibleBlockFaces().stream()
                .filter(face -> sameBlock(face, cropPosition))
                .toList();
        if (targetFaces.stream().anyMatch(
                cropKind(parameters.crop())::isPlant
        )) {
            verifiedCrops.add(cropPosition);
            verificationRediscoveryRounds = 0;
            verificationIndex++;
            phase = Phase.SELECTING_VERIFICATION;
            return SkillTickResult.running(true, true);
        }
        if (!targetFaces.isEmpty()) {
            return fail(context, parameters, "replant_regressed");
        }
        return SkillTickResult.running(false, false);
    }

    private Optional<GridPos> visibleReplantContradiction(
            final MaintainObservedCropFieldParameters parameters
    ) {
        if (completedCrops.isEmpty()) {
            return Optional.empty();
        }
        final CropKind crop = cropKind(parameters.crop());
        return interactionFrames.current().stream()
                .flatMap(frame -> frame.visibleBlockFaces().stream())
                .filter(face -> {
                    final GridPos position = new GridPos(
                            face.block().x(),
                            face.block().y(),
                            face.block().z()
                    );
                    return completedCrops.contains(position)
                            && !crop.isPlant(face);
                })
                .map(face -> new GridPos(
                        face.block().x(),
                        face.block().y(),
                        face.block().z()
                ))
                .findFirst();
    }

    private SkillTickResult rediscover(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        discoveryRounds++;
        if (discoveryRounds > MAXIMUM_DISCOVERY_ROUNDS) {
            return fail(context, parameters, "rediscovery_exhausted");
        }
        if (movement != null && movementParameters != null) {
            movement.cancel(context, movementParameters);
            movement = null;
            movementParameters = null;
        }
        if (patrolMovement != null && patrolMovementParameters != null) {
            patrolMovement.cancel(context, patrolMovementParameters);
            patrolMovement = null;
            patrolMovementParameters = null;
        }
        coreActuator.stop();
        if (startDiscoveryPatrol(context, parameters)) {
            return SkillTickResult.running(true, true);
        }
        startSurvey(context, parameters, Phase.SURVEYING);
        return SkillTickResult.running(true, true);
    }

    private boolean startDiscoveryPatrol(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        final PerceptionVec3 position = coreFrames.current()
                .orElseThrow().position();
        activeStandCandidates = patrolSupports.stream()
                .filter(stand -> Math.hypot(
                        stand.x() + 0.5 - position.x(),
                        stand.z() + 0.5 - position.z()
                ) > 0.75)
                .sorted(Comparator
                        .comparingInt((GridPos stand) ->
                                patrolVisits.getOrDefault(stand, 0))
                        .thenComparingDouble(stand -> Math.hypot(
                                stand.x() + 0.5 - position.x(),
                                stand.z() + 0.5 - position.z()
                        ))
                        .thenComparing(GridPos::compareTo))
                .toList();
        activeStandIndex = 0;
        if (activeStandCandidates.isEmpty()) {
            return false;
        }
        startDiscoveryMovement(context, parameters);
        return true;
    }

    private boolean startDiscoveryMovement(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        while (activeStandIndex < activeStandCandidates.size()) {
            final GridPos stand = activeStandCandidates.get(
                    activeStandIndex
            );
            patrolVisits.merge(stand, 1, Integer::sum);
            patrolMovementParameters = new TravelToParameters(
                    parameters.dimension(),
                    stand.x() + 0.5,
                    stand.y() + 1.0,
                    stand.z() + 0.5,
                    0.5
            );
            patrolMovement = new TravelToSkill(
                    expectedPlayerId,
                    coreActuator,
                    coreFrames,
                    () -> shelterFrames.current()
                            .filter(frame -> expectedPlayerId.equals(
                                    frame.playerId()
                            ))
                            .map(ShelterFrame::sessionGeneration)
                            .orElse(-1L),
                    new dev.mcai.companion.navigation.LocalAStarPlanner(),
                    CoreSkillPolicy.defaults(),
                    PATROL_TRAVEL_POLICY
            );
            if (patrolMovement.preconditions(
                    context,
                    patrolMovementParameters
            )
                    .isPresent()) {
                patrolMovement = null;
                patrolMovementParameters = null;
                activeStandIndex++;
                continue;
            }
            patrolMovement.start(context, patrolMovementParameters);
            verificationMovement = false;
            discoveryMovement = true;
            phase = Phase.MOVING;
            phaseStartedAtTick = context.gameTick();
            return true;
        }
        patrolMovement = null;
        patrolMovementParameters = null;
        discoveryMovement = false;
        startSurvey(context, parameters, Phase.SURVEYING);
        return false;
    }

    private SkillTickResult tickDiscoveryMovement(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        final SkillTickResult result = patrolMovement.tick(
                context,
                patrolMovementParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            lastChildFailure = result.failure()
                    .map(SkillFailure::code)
                    .orElse("travel_to.unknown_failure");
            patrolMovement = null;
            patrolMovementParameters = null;
            activeStandIndex++;
            startDiscoveryMovement(context, parameters);
            return SkillTickResult.running(true, true);
        }
        if (result.status() != SkillTickResult.Status.COMPLETED) {
            return result;
        }
        patrolMovement = null;
        patrolMovementParameters = null;
        discoveryMovement = false;
        coreActuator.stop();
        startSurvey(context, parameters, Phase.SURVEYING);
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult retryVerificationSurvey(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        verificationRediscoveryRounds++;
        if (verificationRediscoveryRounds
                > MAXIMUM_VERIFICATION_REDISCOVERY_ROUNDS) {
            return fail(context, parameters, "verification_unreachable");
        }
        if (movement != null && movementParameters != null) {
            movement.cancel(context, movementParameters);
            movement = null;
            movementParameters = null;
        }
        coreActuator.stop();
        startSurvey(context, parameters, Phase.RESURVEYING);
        return SkillTickResult.running(true, true);
    }

    /**
     * A stale or occluded crop candidate must not monopolize every rolling
     * survey. Try the rest of the fairly observed plan first; after the plan
     * is exhausted, a fresh survey may rediscover this crop from the new
     * body position. No mutation is authorized until the crop is separately
     * re-observed by {@link #tickAiming}.
     */
    private SkillTickResult deferCurrentCell(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        cellIndex++;
        activeStandCandidates = List.of();
        activeStandIndex = 0;
        if (cellIndex < plan.cells().size()) {
            phase = Phase.SELECTING_CELL;
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        return rediscover(context, parameters);
    }

    private SkillTickResult verifyFinalState(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        if (completedCrops.isEmpty()
                || verifiedCrops.size() != completedCrops.size()
                || !verifiedCrops.containsAll(completedCrops)) {
            return fail(
                    context,
                    parameters,
                    "final_replant_incomplete"
            );
        }
        final int currentOutputCount = itemCount(
                interactionFrames.current().orElseThrow().inventory(),
                parameters.crop().outputItemId()
        );
        if (currentOutputCount
                < initialOutputCount + completedCrops.size()) {
            return fail(context, parameters, "output_not_collected");
        }
        coreActuator.stop();
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private void startSurvey(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters,
            final Phase surveyPhase
    ) {
        /*
         * Keep the bounded, expiring site map across rolling surveys. The
         * accumulator replaces conflicting block samples and prunes by time,
         * radius, session, and dimension; clearing it here made the body
         * forget every safe field-edge stand as soon as it moved. Historical
         * evidence may nominate a candidate only. Every crop mutation still
         * requires the current first-person frame in tickAiming().
         */
        lastAccumulatedRevision = -1;
        surveyResults.clear();
        surveySkill = newSurveySkill();
        surveyParameters = surveyParameters(parameters);
        surveySkill.start(context, surveyParameters);
        phase = surveyPhase;
        phaseStartedAtTick = context.gameTick();
        accumulateCurrentSurvey();
    }

    private SurveySurroundingsSkill newSurveySkill() {
        return new SurveySurroundingsSkill(
                expectedPlayerId,
                coreActuator,
                coreFrames,
                surveyResults
        );
    }

    private static SurveySurroundingsParameters surveyParameters(
            final MaintainObservedCropFieldParameters parameters
    ) {
        return new SurveySurroundingsParameters(
                parameters.dimension(),
                SURVEY_HORIZONTAL_STEPS,
                true
        );
    }

    private void accumulateCurrentSurvey() {
        shelterFrames.current()
                .filter(frame -> frame.observationRevision()
                        > lastAccumulatedRevision)
                .ifPresent(frame -> {
                    surveyAccumulator.observe(frame, false);
                    lastAccumulatedRevision = frame.observationRevision();
                });
    }

    private Optional<SkillFailure> bindingFailure(
            final MaintainObservedCropFieldParameters parameters
    ) {
        final Optional<CoreSkillFrame> core = coreFrames.current();
        final Optional<InteractionSkillFrame> interaction =
                interactionFrames.current();
        final Optional<ShelterFrame> shelter = shelterFrames.current();
        if (core.isEmpty() || interaction.isEmpty() || shelter.isEmpty()) {
            return Optional.of(failure("observation_unavailable"));
        }
        if (!expectedPlayerId.equals(core.orElseThrow().playerId())
                || !expectedPlayerId.equals(
                        interaction.orElseThrow().playerId()
                )
                || !expectedPlayerId.equals(
                        shelter.orElseThrow().playerId()
                )) {
            return Optional.of(failure("player_mismatch"));
        }
        if (!parameters.dimension().equals(core.orElseThrow().dimension())
                || !parameters.dimension().equals(
                        interaction.orElseThrow().dimension()
                )
                || !parameters.dimension().equals(
                        shelter.orElseThrow().dimension()
                )) {
            return Optional.of(failure("dimension_mismatch"));
        }
        final long session = shelter.orElseThrow().sessionGeneration();
        if (interaction.orElseThrow().sessionGeneration() != session
                || boundSessionGeneration >= 0
                        && boundSessionGeneration != session) {
            return Optional.of(failure("session_mismatch"));
        }
        return Optional.empty();
    }

    private void cancelChildren(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters
    ) {
        if (pendingPlan != null) {
            pendingPlan.cancel(true);
            pendingPlan = null;
        }
        if (movement != null && movementParameters != null) {
            movement.cancel(context, movementParameters);
        }
        if (patrolMovement != null && patrolMovementParameters != null) {
            patrolMovement.cancel(context, patrolMovementParameters);
        }
        if (harvestSkill != null && harvestParameters != null) {
            harvestSkill.cancel(context, harvestParameters);
        }
        if (surveySkill != null && surveyParameters != null
                && (phase == Phase.SURVEYING
                        || phase == Phase.RESURVEYING)) {
            surveySkill.cancel(context, surveyParameters);
        }
    }

    private SkillTickResult fail(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters,
            final String suffix
    ) {
        return fail(context, parameters, failure(suffix));
    }

    private SkillTickResult fail(
            final SkillContext context,
            final MaintainObservedCropFieldParameters parameters,
            final SkillFailure reason
    ) {
        cancelChildren(context, parameters);
        coreActuator.stop();
        failure = reason.code().startsWith(NAME + ".")
                ? reason
                : failure("child_failed");
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    private static SkillFailure failure(final String suffix) {
        final String code = suffix.startsWith(NAME + ".")
                ? suffix
                : NAME + "." + suffix;
        return SkillFailure.of(code);
    }

    private static String plannerFailure(final String code) {
        return switch (code) {
            case "maintenance.no_mature_crop" -> "no_mature_crop";
            case "maintenance.no_safe_stand" -> "no_safe_stand";
            case "maintenance.insufficient_planting_items" ->
                    "insufficient_planting_items";
            default -> "planning_failed";
        };
    }

    private CropFieldMaintenancePlan.Cell currentCell() {
        return plan.cells().get(cellIndex);
    }

    private String checkpointTarget() {
        if ((phase == Phase.SELECTING_VERIFICATION
                || phase == Phase.VERIFYING
                || phase == Phase.RESURVEYING
                || phase == Phase.MOVING && verificationMovement)
                && verificationIndex >= 0
                && verificationIndex < completedCells.size()) {
            final GridPos target = completedCells.get(
                    verificationIndex
            ).cropPosition();
            return target.x() + "/" + target.y() + "/" + target.z();
        }
        if (plan == null || cellIndex < 0
                || cellIndex >= plan.cells().size()) {
            return "none";
        }
        final GridPos target = plan.cells().get(cellIndex).cropPosition();
        return target.x() + "/" + target.y() + "/" + target.z();
    }

    private String checkpointMovementTarget() {
        if (movementParameters == null) {
            return "none";
        }
        return String.format(
                Locale.ROOT,
                "%.3f/%.3f/%.3f/r%.3f",
                movementParameters.x(),
                movementParameters.y(),
                movementParameters.z(),
                movementParameters.arrivalRadius()
        );
    }

    private String checkpointMovementState(final SkillContext context) {
        if (movement == null || movementParameters == null) {
            return "none";
        }
        return movement.checkpoint(context, movementParameters)
                .payload().replace('"', '\'');
    }

    private void clearExecutionState() {
        phase = Phase.IDLE;
        failure = null;
        startedAtTick = -1;
        phaseStartedAtTick = -1;
        boundSessionGeneration = -1;
        boundGoalRevision = -1;
        lastAccumulatedRevision = -1;
        surveySkill = null;
        surveyParameters = null;
        pendingPlan = null;
        plan = null;
        cellIndex = 0;
        discoveryRounds = 0;
        initialOutputCount = -1;
        completedCrops.clear();
        completedCells.clear();
        verifiedCrops.clear();
        verificationIndex = 0;
        verificationRediscoveryRounds = 0;
        activeStandCandidates = List.of();
        activeStandIndex = 0;
        movement = null;
        movementParameters = null;
        patrolMovement = null;
        patrolMovementParameters = null;
        verificationMovement = false;
        discoveryMovement = false;
        patrolSupports.clear();
        patrolVisits.clear();
        aimStartedRevision = -1;
        harvestSkill = null;
        harvestParameters = null;
        lastChildFailure = "";
        lastChildCheckpoint = "";
        surveyAccumulator.reset();
    }

    private static CropKind cropKind(final CropFieldVariant crop) {
        return switch (crop) {
            case WHEAT -> CropKind.WHEAT;
            case CARROT -> CropKind.CARROTS;
            case POTATO -> CropKind.POTATOES;
            case BEETROOT -> CropKind.BEETROOTS;
        };
    }

    private static boolean sameBlock(
            final VisibleBlockFace face,
            final GridPos position
    ) {
        return face.block().x() == position.x()
                && face.block().y() == position.y()
                && face.block().z() == position.z();
    }

    private static Optional<BlockFace> blockFace(final String face) {
        if (face == null
                || !face.equals(face.toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        try {
            return Optional.of(BlockFace.valueOf(
                    face.toUpperCase(Locale.ROOT)
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
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

    private static PerceptionVec3 direction(final LookIntent intent) {
        final double yaw = Math.toRadians(intent.yawDegrees());
        final double pitch = Math.toRadians(intent.pitchDegrees());
        final double horizontal = Math.cos(pitch);
        return new PerceptionVec3(
                -Math.sin(yaw) * horizontal,
                -Math.sin(pitch),
                Math.cos(yaw) * horizontal
        ).normalized();
    }

    private static double angularError(
            final PerceptionVec3 current,
            final PerceptionVec3 target
    ) {
        final double dot = current.normalized().dot(target.normalized());
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    private static int itemCount(
            final List<InventoryItemSummary> inventory,
            final String itemId
    ) {
        return inventory.stream()
                .filter(item -> itemId.equals(item.itemId()))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static double maximumDanger(final SkillContext context) {
        return context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
    }

    private static boolean directHarvestPose(
            final CoreSkillFrame core,
            final GridPos crop
    ) {
        final double dx = crop.x() + 0.5 - core.position().x();
        final double dz = crop.z() + 0.5 - core.position().z();
        return core.onGround()
                && !core.inWater()
                && Math.hypot(dx, dz)
                        <= DIRECT_HARVEST_HORIZONTAL_DISTANCE;
    }

    private enum Phase {
        IDLE,
        SURVEYING,
        PLANNING,
        SELECTING_CELL,
        SELECTING_VERIFICATION,
        MOVING,
        AIMING,
        VERIFYING,
        HARVESTING,
        SETTLING,
        RESURVEYING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
