package dev.mcai.companion.skills.farming;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.mechanism.CropFieldVariant;
import dev.mcai.companion.mechanism.HydratedCropFieldPlanService;
import dev.mcai.companion.mechanism.MechanismConstructionStep;
import dev.mcai.companion.mechanism.MechanismPlan;
import dev.mcai.companion.mechanism.MechanismPlanningResult;
import dev.mcai.companion.mechanism.MechanismSiteSurvey;
import dev.mcai.companion.mechanism.MechanismSiteSurveyAccumulator;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
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
import dev.mcai.companion.skills.core.MoveToParameters;
import dev.mcai.companion.skills.core.MoveToSkill;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Surveys, generates, constructs, and immediately verifies one hydrated
 * vanilla crop field. The model selects only crop and scale; every coordinate
 * is solved from bounded first-person evidence. Movement and block changes use
 * the same ordinary local skills as independent player commands.
 */
public final class BuildHydratedCropFieldSkill
        implements Skill<BuildHydratedCropFieldParameters> {
    public static final String NAME = "build_hydrated_crop_field";
    private static final int SURVEY_HORIZONTAL_STEPS = 8;
    private static final int MAXIMUM_TOTAL_TICKS = 24_000;
    private static final int MAXIMUM_PLANNING_WAIT_TICKS = 400;
    private static final int MAXIMUM_AIM_TICKS = 80;
    private static final int COMMISSION_SETTLE_TICKS = 160;
    private static final double AIM_ALIGNMENT_DEGREES = 2.0;
    /*
     * A player is 0.6 blocks wide.  Stopping 0.25-0.35 blocks away from the
     * centre of a work cell can leave the body straddling the adjacent plot,
     * and the authoritative support guard must then reject excavation.  This
     * radius leaves at least 0.05 blocks of horizontal clearance from every
     * neighbouring support while still using ordinary movement.
     */
    private static final double STAND_ARRIVAL_RADIUS = 0.15;
    private static final double NORMAL_MAXIMUM_DANGER = 0.20;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.08;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator coreActuator;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactionActuator;
    private final InteractionSkillFrameSource interactionFrames;
    private final ShelterFrameSource shelterFrames;
    private final HydratedCropFieldPlanService planService;
    private final FarmingSkillPolicy farmingPolicy;
    private final MechanismSiteSurveyAccumulator surveyAccumulator;
    private final SurveyResultBuffer surveyResults =
            new SurveyResultBuffer();

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long phaseStartedAtTick = -1;
    private long boundSessionGeneration = -1;
    private long boundGoalRevision = -1;
    private long lastAccumulatedRevision = -1;
    private SurveySurroundingsSkill surveySkill;
    private SurveySurroundingsParameters surveyParameters;
    private CompletableFuture<MechanismPlanningResult> pendingPlan;
    private MechanismPlan plan;
    private List<WorkJob> jobs = List.of();
    private int jobIndex;
    private final Set<GridPos> completedGrounds = new HashSet<>();
    private List<GridPos> activeStandCandidates = List.of();
    private int activeStandIndex;
    private MoveToSkill movement;
    private MoveToParameters movementParameters;
    private long aimStartedRevision = -1;
    private PrepareWaterSourceSkill waterSkill;
    private PrepareWaterSourceParameters waterParameters;
    private PrepareAndPlantPlotSkill plotSkill;
    private PrepareAndPlantPlotParameters plotParameters;

    public BuildHydratedCropFieldSkill(
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

    BuildHydratedCropFieldSkill(
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
        this.planService = Objects.requireNonNull(
                planService,
                "planService"
        );
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
    public SkillParameterParser<BuildHydratedCropFieldParameters>
            parameters() {
        return FarmingSkillParameters::parseBuildHydratedCropField;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<SkillFailure> binding = bindingFailure(parameters);
        if (binding.isPresent()) {
            return binding;
        }
        final CoreSkillFrame core = coreFrames.current().orElseThrow();
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
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
            final BuildHydratedCropFieldParameters parameters
    ) {
        final Optional<SkillFailure> invalid = preconditions(
                context,
                parameters
        );
        if (invalid.isPresent()) {
            throw new IllegalStateException(
                    "Crop-field binding changed before start"
            );
        }
        clearExecutionState();
        final ShelterFrame shelter = shelterFrames.current()
                .orElseThrow();
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        boundSessionGeneration = shelter.sessionGeneration();
        boundGoalRevision = context.goalRevision();
        startSurvey(context, parameters, Phase.SURVEYING);
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
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
        final CoreSkillFrame core = coreFrames.current().orElseThrow();
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        if (context.riskScore() > maximumDanger
                || core.danger() > maximumDanger) {
            return fail(context, parameters, "danger_detected");
        }
        return switch (phase) {
            case SURVEYING -> tickSurvey(context, parameters, false);
            case PLANNING -> tickPlanning(context, parameters);
            case SELECTING_JOB -> startNextJob(context, parameters);
            case MOVING -> tickMovement(context, parameters);
            case AIMING -> tickAiming(context, parameters);
            case EXECUTING_WATER -> tickWater(context, parameters);
            case EXECUTING_PLOT -> tickPlot(context, parameters);
            case SETTLING -> tickSettling(context, parameters);
            case RESURVEYING -> tickSurvey(context, parameters, true);
            default -> SkillTickResult.failed(failure("invalid_state"));
        };
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"crop\":\"%s\",\"minimumPlots\":%d,"
                                + "\"planId\":\"%s\",\"job\":%d,"
                                + "\"jobs\":%d,\"session\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.crop().name().toLowerCase(Locale.ROOT),
                        parameters.minimumPlots(),
                        plan == null ? "" : plan.planId(),
                        jobIndex,
                        jobs.size(),
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
    ) {
        cancelChildren(context, parameters);
        coreActuator.stop();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
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
            final BuildHydratedCropFieldParameters parameters,
            final boolean commissioning
    ) {
        accumulateCurrentSurvey();
        final SkillTickResult surveyed = surveySkill.tick(
                context,
                surveyParameters
        );
        accumulateCurrentSurvey();
        if (surveyed.status() == SkillTickResult.Status.FAILED) {
            return fail(context, parameters, commissioning
                    ? "commission_survey_failed"
                    : "site_survey_failed");
        }
        if (surveyed.status() != SkillTickResult.Status.COMPLETED) {
            return surveyed;
        }
        if (commissioning) {
            return verifyCommissioning(context, parameters);
        }
        final Optional<MechanismSiteSurvey> survey =
                surveyAccumulator.current();
        if (survey.isEmpty()) {
            return fail(context, parameters, "site_survey_empty");
        }
        try {
            pendingPlan = Objects.requireNonNull(planService.plan(
                    survey.orElseThrow(),
                    parameters.request()
            ));
        } catch (RuntimeException rejected) {
            return fail(context, parameters, "planner_unavailable");
        }
        phase = Phase.PLANNING;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickPlanning(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
    ) {
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_PLANNING_WAIT_TICKS) {
            return fail(context, parameters, "planning_timed_out");
        }
        if (pendingPlan == null || !pendingPlan.isDone()) {
            return SkillTickResult.running(false, true);
        }
        final MechanismPlanningResult result;
        try {
            result = pendingPlan.join();
        } catch (RuntimeException failure) {
            return fail(context, parameters, "planning_failed");
        }
        pendingPlan = null;
        if (result.plan().isEmpty()) {
            return fail(context, parameters, result.failureCode()
                    .orElse("planning_failed"));
        }
        plan = result.plan().orElseThrow();
        if (!plan.dimension().equals(parameters.dimension())
                || plan.sourceRevision()
                        > shelterFrames.current().orElseThrow()
                                .observationRevision()) {
            return fail(context, parameters, "plan_binding_changed");
        }
        final MechanismSiteSurvey survey = surveyAccumulator.current()
                .orElseThrow();
        jobs = constructionJobs(plan, survey);
        if (jobs.size() != plan.productionCells() + 1) {
            return fail(context, parameters, "plan_job_mismatch");
        }
        jobIndex = 0;
        completedGrounds.clear();
        phase = Phase.SELECTING_JOB;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult startNextJob(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
    ) {
        if (jobIndex >= jobs.size()) {
            coreActuator.stop();
            phase = Phase.SETTLING;
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        final WorkJob job = jobs.get(jobIndex);
        activeStandCandidates = job.stands().stream()
                .filter(stand -> !completedGrounds.contains(stand))
                .toList();
        if (activeStandCandidates.isEmpty()) {
            activeStandCandidates = job.stands();
        }
        activeStandIndex = 0;
        return startMovement(context, parameters);
    }

    private SkillTickResult startMovement(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
    ) {
        if (activeStandIndex >= activeStandCandidates.size()) {
            return fail(context, parameters, "no_reachable_work_stand");
        }
        final GridPos standGround = activeStandCandidates.get(
                activeStandIndex
        );
        movementParameters = new MoveToParameters(
                parameters.dimension(),
                standGround.x() + 0.5,
                standGround.y() + 1.0,
                standGround.z() + 0.5,
                STAND_ARRIVAL_RADIUS
        );
        movement = new MoveToSkill(
                expectedPlayerId,
                coreActuator,
                coreFrames
        );
        final Optional<SkillFailure> rejected = movement.preconditions(
                context,
                movementParameters
        );
        if (rejected.isPresent()) {
            activeStandIndex++;
            return startMovement(context, parameters);
        }
        movement.start(context, movementParameters);
        phase = Phase.MOVING;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickMovement(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
    ) {
        final SkillTickResult result = movement.tick(
                context,
                movementParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            activeStandIndex++;
            movement = null;
            movementParameters = null;
            return startMovement(context, parameters);
        }
        if (result.status() != SkillTickResult.Status.COMPLETED) {
            return result;
        }
        movement = null;
        movementParameters = null;
        aimStartedRevision = coreFrames.current().orElseThrow()
                .observationRevision();
        phase = Phase.AIMING;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickAiming(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
    ) {
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_AIM_TICKS) {
            return fail(context, parameters, "target_reobserve_timed_out");
        }
        final WorkJob job = jobs.get(jobIndex);
        final CoreSkillFrame core = coreFrames.current().orElseThrow();
        final LookIntent intent = lookAt(
                core.eyePosition(),
                new PerceptionVec3(
                        job.ground().x() + 0.5,
                        job.ground().y() + 1.0,
                        job.ground().z() + 0.5
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
        final Optional<InteractionSkillFrame> available =
                interactionFrames.current();
        if (available.isEmpty()
                || available.orElseThrow().observationRevision()
                        <= aimStartedRevision) {
            return SkillTickResult.running(false, false);
        }
        final InteractionSkillFrame frame = available.orElseThrow();
        if (alreadyCompleted(job, frame, parameters.crop())) {
            completedGrounds.add(job.ground());
            jobIndex++;
            phase = Phase.SELECTING_JOB;
            return SkillTickResult.running(true, true);
        }
        final Optional<VisibleBlockFace> ground = frame
                .visibleBlockFaces().stream()
                .filter(face -> sameBlock(face, job.ground()))
                .filter(face -> "up".equals(face.face()))
                .filter(face -> Set.of(
                        "minecraft:dirt",
                        "minecraft:grass_block",
                        "minecraft:farmland"
                ).contains(face.blockTypeId()))
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
        if (ground.isEmpty()) {
            return SkillTickResult.running(false, false);
        }
        final ObservedBlockTarget target = new ObservedBlockTarget(
                frame.observationRevision(),
                job.ground().x(),
                job.ground().y(),
                job.ground().z(),
                BlockFace.UP
        );
        if (job.kind() == JobKind.WATER) {
            waterParameters = new PrepareWaterSourceParameters(
                    parameters.dimension(),
                    target
            );
            waterSkill = new PrepareWaterSourceSkill(
                    expectedPlayerId,
                    coreActuator,
                    coreFrames,
                    interactionActuator,
                    interactionFrames,
                    farmingPolicy
            );
            final Optional<SkillFailure> rejected =
                    waterSkill.preconditions(context, waterParameters);
            if (rejected.isPresent()) {
                return fail(context, parameters, "water_step_rejected");
            }
            waterSkill.start(context, waterParameters);
            phase = Phase.EXECUTING_WATER;
        } else {
            plotParameters = new PrepareAndPlantPlotParameters(
                    parameters.dimension(),
                    cropKind(parameters.crop()),
                    target
            );
            plotSkill = new PrepareAndPlantPlotSkill(
                    expectedPlayerId,
                    coreActuator,
                    coreFrames,
                    interactionActuator,
                    interactionFrames,
                    farmingPolicy
            );
            final Optional<SkillFailure> rejected =
                    plotSkill.preconditions(context, plotParameters);
            if (rejected.isPresent()) {
                return fail(context, parameters, "plot_step_rejected");
            }
            plotSkill.start(context, plotParameters);
            phase = Phase.EXECUTING_PLOT;
        }
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickWater(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
    ) {
        final SkillTickResult result = waterSkill.tick(
                context,
                waterParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            return fail(context, parameters, result.failure()
                    .orElseThrow());
        }
        if (result.status() != SkillTickResult.Status.COMPLETED) {
            return result;
        }
        waterSkill = null;
        waterParameters = null;
        return finishCurrentJob();
    }

    private SkillTickResult tickPlot(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
    ) {
        final SkillTickResult result = plotSkill.tick(
                context,
                plotParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            return fail(context, parameters, result.failure()
                    .orElseThrow());
        }
        if (result.status() != SkillTickResult.Status.COMPLETED) {
            return result;
        }
        plotSkill = null;
        plotParameters = null;
        return finishCurrentJob();
    }

    private SkillTickResult finishCurrentJob() {
        completedGrounds.add(jobs.get(jobIndex).ground());
        jobIndex++;
        phase = Phase.SELECTING_JOB;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickSettling(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
    ) {
        coreActuator.stop();
        if (context.gameTick() - phaseStartedAtTick
                < COMMISSION_SETTLE_TICKS) {
            return SkillTickResult.running(false, true);
        }
        startSurvey(context, parameters, Phase.RESURVEYING);
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult verifyCommissioning(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters
    ) {
        final MechanismSiteSurvey survey = surveyAccumulator.current()
                .orElseThrow();
        final boolean water = survey.surfaces().stream()
                .map(MechanismSiteSurvey.SurfaceObservation::face)
                .anyMatch(face -> sameBlock(face, plan.anchor())
                        && "minecraft:water".equals(
                                face.blockTypeId()
                        ));
        final Set<GridPos> observedCrops = new HashSet<>();
        survey.surfaces().stream()
                .map(MechanismSiteSurvey.SurfaceObservation::face)
                .filter(face -> parameters.crop().plantedBlockId()
                        .equals(face.blockTypeId()))
                .map(face -> new GridPos(
                        face.block().x(),
                        face.block().y(),
                        face.block().z()
                ))
                .forEach(observedCrops::add);
        final boolean crops = jobs.stream()
                .filter(job -> job.kind() == JobKind.PLOT)
                .map(job -> job.ground().above())
                .allMatch(observedCrops::contains);
        if (!water || !crops) {
            return fail(context, parameters,
                    "commission_observation_incomplete");
        }
        coreActuator.stop();
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private void startSurvey(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters,
            final Phase surveyPhase
    ) {
        surveyAccumulator.reset();
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
            final BuildHydratedCropFieldParameters parameters
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
                    surveyAccumulator.ingest(frame, false);
                    lastAccumulatedRevision = frame.observationRevision();
                });
    }

    private Optional<SkillFailure> bindingFailure(
            final BuildHydratedCropFieldParameters parameters
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
            final BuildHydratedCropFieldParameters parameters
    ) {
        if (pendingPlan != null) {
            pendingPlan.cancel(true);
            pendingPlan = null;
        }
        if (movement != null && movementParameters != null) {
            movement.cancel(context, movementParameters);
        }
        if (waterSkill != null && waterParameters != null) {
            waterSkill.cancel(context, waterParameters);
        }
        if (plotSkill != null && plotParameters != null) {
            plotSkill.cancel(context, plotParameters);
        }
        if (surveySkill != null && surveyParameters != null
                && (phase == Phase.SURVEYING
                        || phase == Phase.RESURVEYING)) {
            surveySkill.cancel(context, surveyParameters);
        }
    }

    private SkillTickResult fail(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters,
            final String code
    ) {
        return fail(context, parameters, failure(code));
    }

    private SkillTickResult fail(
            final SkillContext context,
            final BuildHydratedCropFieldParameters parameters,
            final SkillFailure reason
    ) {
        cancelChildren(context, parameters);
        coreActuator.stop();
        failure = childFailure(reason);
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    private static SkillFailure failure(final String suffix) {
        final String code = suffix.startsWith(NAME + ".")
                ? suffix
                : NAME + "." + suffix;
        return SkillFailure.of(code);
    }

    static SkillFailure childFailure(final SkillFailure reason) {
        final String code = reason.code();
        if (code.startsWith(NAME + ".")) {
            return reason;
        }
        final String waterPrefix = PrepareWaterSourceSkill.NAME + ".";
        if (code.startsWith(waterPrefix)) {
            return failure("water_" + code.substring(
                    waterPrefix.length()
            ));
        }
        final String plotPrefix = PrepareAndPlantPlotSkill.NAME + ".";
        if (code.startsWith(plotPrefix)) {
            return failure("plot_" + code.substring(
                    plotPrefix.length()
            ));
        }
        return failure("child_failed");
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
        jobs = List.of();
        jobIndex = 0;
        completedGrounds.clear();
        activeStandCandidates = List.of();
        activeStandIndex = 0;
        movement = null;
        movementParameters = null;
        aimStartedRevision = -1;
        waterSkill = null;
        waterParameters = null;
        plotSkill = null;
        plotParameters = null;
        surveyAccumulator.reset();
    }

    static List<WorkJob> constructionJobs(
            final MechanismPlan plan,
            final MechanismSiteSurvey survey
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(survey, "survey");
        final List<GridPos> plots = plan.steps().stream()
                .filter(step -> step.action()
                        == MechanismConstructionStep.Action.TILL)
                .map(MechanismConstructionStep::target)
                .distinct()
                .toList();
        final Map<Integer, List<GridPos>> layers = new HashMap<>();
        for (GridPos plot : plots) {
            layers.computeIfAbsent(
                    distanceFromAisleLayer(plan, plot),
                    ignored -> new ArrayList<>()
            ).add(plot);
        }
        final List<Integer> layerOrder = layers.keySet().stream()
                .sorted(Comparator.reverseOrder())
                .toList();
        final List<GridPos> orderedPlots = new ArrayList<>();
        for (int layerIndex = 0;
                layerIndex < layerOrder.size();
                layerIndex++) {
            final List<GridPos> row = new ArrayList<>(
                    layers.get(layerOrder.get(layerIndex))
            );
            row.sort(Comparator.comparingInt(position ->
                    crossCoordinate(plan, position)
            ));
            if ((layerIndex & 1) == 1) {
                java.util.Collections.reverse(row);
            }
            orderedPlots.addAll(row);
        }
        final List<WorkJob> result = new ArrayList<>();
        result.add(new WorkJob(
                JobKind.WATER,
                plan.anchor(),
                safeWorkStands(plan, survey, plan.anchor(), true)
        ));
        for (GridPos plot : orderedPlots) {
            result.add(new WorkJob(
                    JobKind.PLOT,
                    plot,
                    safeWorkStands(plan, survey, plot, false)
            ));
        }
        if (result.stream().anyMatch(job -> job.stands().isEmpty())) {
            return List.of();
        }
        return List.copyOf(result);
    }

    private static List<GridPos> safeWorkStands(
            final MechanismPlan plan,
            final MechanismSiteSurvey survey,
            final GridPos target,
            final boolean water
    ) {
        final Delta toward = towardAisle(plan.serviceFacing());
        final List<Delta> directions = List.of(
                toward,
                new Delta(-toward.z(), 0, toward.x()),
                new Delta(toward.z(), 0, -toward.x()),
                new Delta(-toward.x(), 0, -toward.z())
        );
        final LinkedHashSet<GridPos> candidates = new LinkedHashSet<>();
        for (Delta direction : directions) {
            final GridPos stand = target.offset(
                    direction.x(),
                    direction.y(),
                    direction.z()
            );
            if (stand.equals(target)
                    || !water && stand.equals(plan.anchor())
                    || !safeStandingCell(survey, stand)) {
                continue;
            }
            candidates.add(stand);
        }
        return List.copyOf(candidates);
    }

    private static boolean safeStandingCell(
            final MechanismSiteSurvey survey,
            final GridPos ground
    ) {
        final Optional<ObservedVoxel> support = survey.voxelAt(ground);
        final Optional<ObservedVoxel> feet = survey.voxelAt(
                ground.above()
        );
        final Optional<ObservedVoxel> head = survey.voxelAt(
                ground.above(2)
        );
        return support.isPresent()
                && feet.isPresent()
                && head.isPresent()
                && support.orElseThrow().kind().supportsWeight()
                && NavigationEvidence.hasTraversalClearance(
                        feet.orElseThrow()
                )
                && NavigationEvidence.hasTraversalClearance(
                        head.orElseThrow()
                )
                && support.orElseThrow().effectiveDanger() <= 0.20
                && feet.orElseThrow().effectiveDanger() <= 0.20
                && head.orElseThrow().effectiveDanger() <= 0.20;
    }

    private static int distanceFromAisleLayer(
            final MechanismPlan plan,
            final GridPos target
    ) {
        final int minimumX = minimumCoordinate(plan, true);
        final int maximumX = maximumCoordinate(plan, true);
        final int minimumZ = minimumCoordinate(plan, false);
        final int maximumZ = maximumCoordinate(plan, false);
        return switch (plan.serviceFacing()) {
            case NORTH -> target.z() - minimumZ;
            case SOUTH -> maximumZ - target.z();
            case WEST -> target.x() - minimumX;
            case EAST -> maximumX - target.x();
        };
    }

    private static int crossCoordinate(
            final MechanismPlan plan,
            final GridPos target
    ) {
        return switch (plan.serviceFacing()) {
            case NORTH, SOUTH -> target.x();
            case EAST, WEST -> target.z();
        };
    }

    private static int minimumCoordinate(
            final MechanismPlan plan,
            final boolean x
    ) {
        return plan.steps().stream()
                .filter(step -> step.action()
                        == MechanismConstructionStep.Action.TILL
                        || step.action()
                        == MechanismConstructionStep.Action.EXCAVATE)
                .mapToInt(step -> x
                        ? step.target().x()
                        : step.target().z())
                .min()
                .orElseThrow();
    }

    private static int maximumCoordinate(
            final MechanismPlan plan,
            final boolean x
    ) {
        return plan.steps().stream()
                .filter(step -> step.action()
                        == MechanismConstructionStep.Action.TILL
                        || step.action()
                        == MechanismConstructionStep.Action.EXCAVATE)
                .mapToInt(step -> x
                        ? step.target().x()
                        : step.target().z())
                .max()
                .orElseThrow();
    }

    private static Delta towardAisle(
            final MechanismPlan.Orientation facing
    ) {
        return switch (facing) {
            case NORTH -> new Delta(0, 0, -1);
            case SOUTH -> new Delta(0, 0, 1);
            case WEST -> new Delta(-1, 0, 0);
            case EAST -> new Delta(1, 0, 0);
        };
    }

    private static boolean alreadyCompleted(
            final WorkJob job,
            final InteractionSkillFrame frame,
            final CropFieldVariant crop
    ) {
        if (job.kind() == JobKind.WATER) {
            return frame.visibleBlockFaces().stream().anyMatch(face ->
                    sameBlock(face, job.ground())
                            && "minecraft:water".equals(
                                    face.blockTypeId()
                            )
            );
        }
        return frame.visibleBlockFaces().stream().anyMatch(face ->
                sameBlock(face, job.ground().above())
                        && crop.plantedBlockId().equals(
                                face.blockTypeId()
                        )
        );
    }

    private static boolean sameBlock(
            final VisibleBlockFace face,
            final GridPos position
    ) {
        return face.block().x() == position.x()
                && face.block().y() == position.y()
                && face.block().z() == position.z();
    }

    private static CropKind cropKind(final CropFieldVariant crop) {
        return CropKind.fromBlockId(crop.plantedBlockId())
                .orElseThrow();
    }

    private static LookIntent lookAt(
            final PerceptionVec3 eye,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta = target.subtract(eye);
        final double horizontal = Math.hypot(delta.x(), delta.z());
        return new LookIntent(
                (float) Math.toDegrees(Math.atan2(
                        -delta.x(),
                        delta.z()
                )),
                (float) -Math.toDegrees(Math.atan2(
                        delta.y(),
                        horizontal
                ))
        );
    }

    private static PerceptionVec3 direction(final LookIntent look) {
        final double yaw = Math.toRadians(look.yawDegrees());
        final double pitch = Math.toRadians(look.pitchDegrees());
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
        final double dot = current.normalized().dot(
                target.normalized()
        );
        return Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
    }

    enum JobKind {
        WATER,
        PLOT
    }

    record WorkJob(
            JobKind kind,
            GridPos ground,
            List<GridPos> stands
    ) {
        WorkJob {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(ground, "ground");
            stands = List.copyOf(Objects.requireNonNull(
                    stands,
                    "stands"
            ));
        }
    }

    private record Delta(int x, int y, int z) {
    }

    private enum Phase {
        IDLE,
        SURVEYING,
        PLANNING,
        SELECTING_JOB,
        MOVING,
        AIMING,
        EXECUTING_WATER,
        EXECUTING_PLOT,
        SETTLING,
        RESURVEYING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
