package dev.mcai.companion.skills.loot;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntityPlacementEnvelope;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.MoveToParameters;
import dev.mcai.companion.skills.core.MoveToSkill;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.interaction.BreakBlockParameters;
import dev.mcai.companion.skills.interaction.BreakBlockSkill;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillPolicy;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds a small two-block-high Enderman safety roof through ordinary player
 * placement.
 *
 * <p>The plan is generated around the body's current feet after a bounded
 * first-person survey. It first places a two-block edge pillar, jump-places
 * the roof starter, then extends a 3x3 ceiling from visible side faces. Every
 * step requires a current centre-ray support hit, an accepted vanilla
 * interaction, owned-item consumption, and a fresh fair observation of the
 * placed block. No level, chunk, registry, or direct block mutation is
 * available to this skill.</p>
 */
public final class BuildEndermanSafetyRoofSkill
        implements Skill<NoParameters> {
    public static final String NAME =
            "build_enderman_safety_roof";
    static final int REQUIRED_BLOCKS = 11;

    /*
     * This is the aggregate bound for eleven ordinary placements, the
     * intervening player movement, two ordinary pillar breaks, and the final
     * fair-evidence scan. Each individual phase has its own much tighter
     * timeout below. The former 1,600-tick aggregate could expire during the
     * second cleanup block even though every phase was still progressing.
     */
    private static final int MAXIMUM_TICKS = 3_200;
    private static final int MAXIMUM_AIM_TICKS = 100;
    private static final int MAXIMUM_CONFIRM_TICKS = 60;
    private static final int MAXIMUM_JUMP_ATTEMPTS = 6;
    private static final int SURVEY_INTERVAL_TICKS = 3;
    private static final int MAXIMUM_SCAN_ALIGNMENT_TICKS = 40;
    private static final float SCAN_ALIGNMENT_TOLERANCE_DEGREES =
            2.0F;
    private static final double MAXIMUM_SITE_DANGER = 0.08;
    private static final double MAXIMUM_CONTROLLED_JUMP_FALL_SEVERITY =
            0.075;
    private static final double MAXIMUM_CONTROLLED_JUMP_HEIGHT = 1.50;
    private static final double POSITION_ARRIVAL_RADIUS = 0.30;
    private static final int MAXIMUM_POSITIONING_TICKS = 800;
    private static final long MAXIMUM_SITE_OBSERVATION_AGE = 128;
    private static final Set<String> FULL_BLOCK_ITEMS = Set.of(
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:stone",
            "minecraft:granite",
            "minecraft:diorite",
            "minecraft:andesite",
            "minecraft:tuff",
            "minecraft:dirt",
            "minecraft:coarse_dirt",
            "minecraft:netherrack",
            "minecraft:nether_bricks",
            "minecraft:blackstone"
    );
    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final MoveToSkill positioningMovement;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1L;
    private long phaseStartedAtTick = -1L;
    private long nextScanTick = -1L;
    private long dispatchedObservationRevision = -1L;
    private long surveyAlignedRevision = -1L;
    private DimensionRef boundDimension;
    private GridPos anchor;
    private List<PlacementStep> plan = List.of();
    private List<PerceptionVec3> siteSurveyTargets = List.of();
    private List<PerceptionVec3> roofSurveyTargets = List.of();
    private int stepIndex;
    private int surveyDirectionIndex;
    private int surveyView;
    private int roofScanView;
    private int scanAlignmentTicks;
    private long roofAlignedRevision = -1L;
    private long cleanupAlignedRevision = -1L;
    private long cleanupDispatchedObservationRevision = -1L;
    private int jumpAttempts;
    private int placementsConfirmed;
    private int cleanupIndex;
    private String activeMaterial;
    private int materialCountBefore;
    private boolean controlledPlacementJumpInFlight;
    private boolean temporaryPillarCleanupComplete;
    private MoveToParameters positioningParameters;
    private BreakBlockSkill cleanupBreak;
    private BreakBlockParameters cleanupBreakParameters;

    public BuildEndermanSafetyRoofSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.core = Objects.requireNonNull(core, "core");
        this.coreFrames = Objects.requireNonNull(
                coreFrames,
                "coreFrames"
        );
        this.interactions = Objects.requireNonNull(
                interactions,
                "interactions"
        );
        this.interactionFrames = Objects.requireNonNull(
                interactionFrames,
                "interactionFrames"
        );
        positioningMovement = new MoveToSkill(
                expectedPlayerId,
                core,
                coreFrames
        );
    }

    @Override
    public SkillParameterParser<NoParameters> parameters() {
        return arguments -> arguments.isEmpty()
                ? SkillParameterResult.valid(NoParameters.INSTANCE)
                : SkillParameterResult.invalid(
                        NAME + ".invalid_arguments"
                );
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<CoreSkillFrame> frame = ownedFrame();
        if (frame.isEmpty()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".body_unavailable"
            ));
        }
        final CoreSkillFrame current = frame.orElseThrow();
        if (!current.onGround() || current.inWater()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stable_pose_required"
            ));
        }
        if (!centered(current.position())) {
            return Optional.of(SkillFailure.of(
                    NAME + ".centered_pose_required"
            ));
        }
        if (!AcquireShelteredEnderPearlSkill
                .hasObservedTwoBlockShelter(current)
                && buildingBlockCount(current) < REQUIRED_BLOCKS) {
            return Optional.of(SkillFailure.of(
                    NAME + ".building_blocks_required"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final CoreSkillFrame frame = ownedFrame().orElseThrow(
                () -> new IllegalStateException(
                        "Companion body changed before safety-roof build"
                )
        );
        phase = Phase.CHECKING_EXISTING_ROOF;
        failure = null;
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        nextScanTick = context.gameTick();
        dispatchedObservationRevision = -1L;
        surveyAlignedRevision = -1L;
        boundDimension = frame.dimension();
        anchor = frame.feet();
        plan = List.of();
        roofSurveyTargets = List.of();
        surveyDirectionIndex = 0;
        siteSurveyTargets = siteSurveyTargets(
                anchor,
                PillarDirection.values()[surveyDirectionIndex]
        );
        roofSurveyTargets = roofSurveyTargets(anchor);
        stepIndex = 0;
        surveyView = 0;
        roofScanView = 0;
        scanAlignmentTicks = 0;
        roofAlignedRevision = -1L;
        cleanupAlignedRevision = -1L;
        cleanupDispatchedObservationRevision = -1L;
        jumpAttempts = 0;
        placementsConfirmed = 0;
        cleanupIndex = 0;
        activeMaterial = null;
        materialCountBefore = -1;
        controlledPlacementJumpInFlight = false;
        temporaryPillarCleanupComplete = true;
        positioningParameters = null;
        cleanupBreak = null;
        cleanupBreakParameters = null;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final NoParameters parameters
    ) {
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context);
        } catch (RuntimeException exception) {
            return fail(NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"step\":%d,"
                            + "\"planSize\":%d,\"placements\":%d,"
                            + "\"cleanupIndex\":%d,"
                            + "\"pillarCleanupComplete\":%s,"
                            + "\"surveyDirection\":%d,"
                            + "\"surveyView\":%d,\"roofScanView\":%d}",
                        phase.name(),
                        stepIndex,
                        plan.size(),
                        placementsConfirmed,
                        cleanupIndex,
                        temporaryPillarCleanupComplete,
                        surveyDirectionIndex,
                        surveyView,
                        roofScanView
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        cancelPositioning(context);
        cancelCleanup(context);
        core.stop();
        controlledPlacementJumpInFlight = false;
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(SkillFailure.of(
                    NAME + ".invalid_state"
            ));
        };
    }

    GridPos anchor() {
        return anchor;
    }

    int placementsConfirmed() {
        return placementsConfirmed;
    }

    boolean revalidatingExistingRoof() {
        return phase == Phase.CHECKING_EXISTING_ROOF
                && placementsConfirmed == 0;
    }

    /**
     * Identifies only the short descent caused by this builder's own
     * jump-placement. The compound owner may keep ticking through that
     * ordinary hop, but only while the body remains over the original,
     * freshly observed support. A knockback, missing floor, lateral drift,
     * or longer fall is deliberately not absorbed here.
     */
    boolean managesControlledPlacementLanding(
            final CoreSkillFrame frame,
            final DangerSignal signal
    ) {
        return isControlledPlacementLanding(
                frame,
                signal,
                anchor,
                controlledPlacementJumpInFlight
        );
    }

    static boolean isControlledPlacementLanding(
            final CoreSkillFrame frame,
            final DangerSignal signal,
            final GridPos expectedAnchor,
            final boolean placementJumpInFlight
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(signal, "signal");
        if (!placementJumpInFlight
                || expectedAnchor == null
                || signal.kind() != DangerKind.FALLING
                || signal.provenance()
                    != PerceptionProvenance.BODY_HAZARD
                || signal.severity()
                    > MAXIMUM_CONTROLLED_JUMP_FALL_SEVERITY
                || frame.onGround()
                || frame.inWater()
                || !sameHorizontalCell(
                        expectedAnchor,
                        frame.feet()
                )) {
            return false;
        }
        final double height =
                frame.position().y() - expectedAnchor.y();
        return height >= 0.0
                && height <= MAXIMUM_CONTROLLED_JUMP_HEIGHT
                && safeSupport(frame, expectedAnchor.below());
    }

    private SkillTickResult tickSafely(
            final SkillContext context
    ) {
        if (context.gameTick() - startedAtTick >= MAXIMUM_TICKS) {
            return fail(NAME + ".timed_out");
        }
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        if (frame == null) {
            return fail(NAME + ".body_unavailable");
        }
        if (!Objects.equals(boundDimension, frame.dimension())) {
            return fail(NAME + ".dimension_changed");
        }
        if (!withinBuildFootprint(anchor, frame.feet())) {
            return fail(NAME + ".build_footprint_left");
        }
        if (controlledPlacementJumpInFlight && frame.onGround()) {
            controlledPlacementJumpInFlight = false;
        }
        if (AcquireShelteredEnderPearlSkill
                .hasObservedTwoBlockShelter(frame)
                && mayCompleteObservedRoof(
                        placementsConfirmed,
                        temporaryPillarCleanupComplete
                )) {
            core.stop();
            controlledPlacementJumpInFlight = false;
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        return switch (phase) {
            case CHECKING_EXISTING_ROOF ->
                    tickExistingRoofCheck(context, frame);
            case SURVEYING -> tickSurvey(context, frame);
            case PREPARING_STEP -> prepareStep(context, frame);
            case POSITIONING_STEP ->
                    tickPositioning(
                            context,
                            frame,
                            PositioningCompletion.NEXT_PLACEMENT
                    );
            case RETURNING_FOR_CLEANUP ->
                    tickPositioning(
                            context,
                            frame,
                            PositioningCompletion.CLEANUP
                    );
            case EQUIPPING -> tickEquip(context, frame);
            case AIMING -> tickAim(context, frame);
            case VERIFYING -> tickVerification(context, frame);
            case RETURNING_TO_ANCHOR ->
                    tickPositioning(
                            context,
                            frame,
                            PositioningCompletion.ROOF_VERIFICATION
                    );
            case PREPARING_CLEANUP ->
                    prepareCleanup(context, frame);
            case AIMING_CLEANUP ->
                    tickCleanupAim(context, frame);
            case MINING_CLEANUP ->
                    tickCleanupMining(context);
            case VERIFYING_CLEANUP ->
                    tickCleanupVerification(context, frame);
            case VERIFYING_ROOF ->
                    tickRoofVerification(context, frame);
            default -> fail(NAME + ".invalid_state");
        };
    }

    private SkillTickResult tickExistingRoofCheck(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (AcquireShelteredEnderPearlSkill
                .hasObservedTwoBlockShelter(frame)) {
            core.stop();
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (roofScanView >= roofSurveyTargets.size()) {
            surveyDirectionIndex = 0;
            siteSurveyTargets = siteSurveyTargets(
                    anchor,
                    PillarDirection.values()[surveyDirectionIndex]
            );
            surveyView = 0;
            scanAlignmentTicks = 0;
            surveyAlignedRevision = -1L;
            nextScanTick = context.gameTick();
            return transition(context, Phase.SURVEYING);
        }
        return tickRoofScanStep(context, frame);
    }

    private SkillTickResult tickSurvey(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (surveyView >= siteSurveyTargets.size()) {
            final List<PlacementStep> candidate = plan(
                    anchor,
                    PillarDirection.values()[
                        surveyDirectionIndex
                    ]
            );
            if (siteObservedClear(frame, candidate)) {
                plan = candidate;
                temporaryPillarCleanupComplete = false;
                stepIndex = 0;
                return transition(
                        context,
                        Phase.PREPARING_STEP
                );
            }
            surveyDirectionIndex++;
            if (surveyDirectionIndex
                    >= PillarDirection.values().length) {
                return fail(NAME + ".observed_clear_site_required");
            }
            siteSurveyTargets = siteSurveyTargets(
                    anchor,
                    PillarDirection.values()[
                        surveyDirectionIndex
                    ]
            );
            surveyView = 0;
            scanAlignmentTicks = 0;
            surveyAlignedRevision = -1L;
            nextScanTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(false, true);
        }
        final PerceptionVec3 target =
                siteSurveyTargets.get(surveyView);
        final LookAngles angles = lookAngles(frame, target);
        final float yaw = angles.yaw();
        final float pitch = angles.pitch();
        if (!core.stop().accepted()
                || !core.look(new LookIntent(yaw, pitch)).accepted()) {
            return fail(NAME + ".survey_rejected");
        }
        if (!aligned(frame, yaw, pitch)) {
            surveyAlignedRevision = -1L;
            scanAlignmentTicks++;
            if (scanAlignmentTicks
                    > MAXIMUM_SCAN_ALIGNMENT_TICKS) {
                return fail(NAME + ".survey_alignment_timed_out");
            }
            return SkillTickResult.running(false, false);
        }
        if (surveyAlignedRevision < 0L) {
            surveyAlignedRevision = frame.observationRevision();
            return SkillTickResult.running(false, true);
        }
        if (frame.observationRevision()
                <= surveyAlignedRevision) {
            return SkillTickResult.running(false, true);
        }
        surveyView++;
        scanAlignmentTicks = 0;
        surveyAlignedRevision = -1L;
        nextScanTick =
                context.gameTick() + SURVEY_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult prepareStep(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (stepIndex >= plan.size()) {
            if (!atPosition(frame, anchor)) {
                return beginPositioning(
                        context,
                        frame,
                        anchor,
                        Phase.RETURNING_FOR_CLEANUP
                );
            }
            return transition(context, Phase.PREPARING_CLEANUP);
        }
        final PlacementStep step = plan.get(stepIndex);
        if (!targetRemainsPassable(frame, step.target())) {
            return fail(NAME + ".placement_target_changed");
        }
        if (requiresPositioning(step)) {
            final GridPos vantage = placementVantage(step);
            if (!atPosition(frame, vantage)) {
                return beginPositioning(
                        context,
                        frame,
                        vantage,
                        Phase.POSITIONING_STEP
                );
            }
        } else if (!sameHorizontalCell(anchor, frame.feet())) {
            return beginPositioning(
                    context,
                    frame,
                    anchor,
                    Phase.POSITIONING_STEP
            );
        }
        final Optional<String> material = selectMaterial(frame);
        if (material.isEmpty()) {
            return fail(NAME + ".building_blocks_exhausted");
        }
        activeMaterial = material.orElseThrow();
        jumpAttempts = 0;
        if (activeMaterial.equals(frame.mainHand().itemId())) {
            return transition(context, Phase.AIMING);
        }
        final ActionOutcome equipped =
                interactions.equipMainHand(activeMaterial);
        if (!equipped.accepted()) {
            return fail(NAME + ".material_equip_rejected");
        }
        return transition(context, Phase.EQUIPPING);
    }

    private SkillTickResult beginPositioning(
            final SkillContext context,
            final CoreSkillFrame frame,
            final GridPos destination,
            final Phase movementPhase
    ) {
        positioningParameters = new MoveToParameters(
                frame.dimension(),
                destination.x() + 0.5,
                destination.y(),
                destination.z() + 0.5,
                POSITION_ARRIVAL_RADIUS
        );
        final Optional<SkillFailure> rejected =
                positioningMovement.preconditions(
                        context,
                        positioningParameters
                );
        if (rejected.isPresent()) {
            positioningParameters = null;
            return fail(
                    NAME + ".positioning_rejected."
                        + rejected.orElseThrow().code()
            );
        }
        positioningMovement.start(
                context,
                positioningParameters
        );
        return transition(context, movementPhase);
    }

    private SkillTickResult tickPositioning(
            final SkillContext context,
            final CoreSkillFrame frame,
            final PositioningCompletion completion
    ) {
        if (positioningParameters == null) {
            return fail(NAME + ".positioning_binding_missing");
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_POSITIONING_TICKS) {
            cancelPositioning(context);
            return fail(NAME + ".positioning_timed_out");
        }
        final SkillTickResult result = positioningMovement.tick(
                context,
                positioningParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            final String childCode = result.failure()
                    .map(SkillFailure::code)
                    .orElse("unknown");
            MinecraftAiCompanion.LOGGER.warn(
                    "Enderman roof positioning failed child={} "
                        + "phase={} target={} body={} checkpoint={}",
                    childCode,
                    phase,
                    positioningParameters,
                    frame.position(),
                    checkpoint(
                            context,
                            NoParameters.INSTANCE
                    ).payload()
            );
            positioningParameters = null;
            /*
             * Preserve the already namespaced child failure. Prefixing the
             * longest move_to codes exceeds SkillFailure's bounded 64-byte
             * audit format and used to collapse useful evidence to the
             * generic "skill_failure" fallback.
             */
            return fail(childCode);
        }
        if (result.status()
                != SkillTickResult.Status.COMPLETED) {
            return SkillTickResult.running(
                    result.madeProgress(),
                    result.safeCheckpoint()
            );
        }
        positioningParameters = null;
        if (completion != PositioningCompletion.NEXT_PLACEMENT) {
            if (!atPosition(frame, anchor)) {
                return fail(NAME + ".anchor_return_unconfirmed");
            }
        }
        if (completion == PositioningCompletion.CLEANUP) {
            return transition(context, Phase.PREPARING_CLEANUP);
        }
        if (completion == PositioningCompletion.ROOF_VERIFICATION) {
            return beginRoofVerification(context);
        }
        return transition(context, Phase.PREPARING_STEP);
    }

    private SkillTickResult tickEquip(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (activeMaterial != null
                && activeMaterial.equals(
                        frame.mainHand().itemId()
                )
                && frame.mainHand().count() > 0) {
            return transition(context, Phase.AIMING);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            return fail(NAME + ".material_equip_unconfirmed");
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult tickAim(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (stepIndex >= plan.size() || activeMaterial == null) {
            return fail(NAME + ".placement_binding_missing");
        }
        final PlacementStep step = plan.get(stepIndex);
        if (!activeMaterial.equals(frame.mainHand().itemId())) {
            return transition(context, Phase.PREPARING_STEP);
        }
        if (!core.stop().accepted()
                || !lookAt(frame, step.hitPoint()).accepted()) {
            return fail(NAME + ".aim_rejected");
        }
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock();
        if (crosshair.filter(face ->
                matchesSupport(face, step)).isPresent()) {
            final VisibleBlockFace actual = crosshair.orElseThrow();
            final ActionOutcome placed = interactions.useOnBlock(
                    ActionHand.MAIN_HAND,
                    interactionTarget(actual)
            );
            if (!placed.accepted()) {
                return fail(NAME + ".placement_rejected");
            }
            materialCountBefore =
                    inventoryCount(frame, activeMaterial);
            dispatchedObservationRevision =
                    frame.observationRevision();
            return transition(context, Phase.VERIFYING);
        }
        if (step.jumpPlacement()) {
            if (frame.onGround()) {
                if (jumpAttempts >= MAXIMUM_JUMP_ATTEMPTS
                        || !core.jump().accepted()) {
                    return fail(NAME + ".jump_placement_rejected");
                }
                jumpAttempts++;
                controlledPlacementJumpInFlight = true;
            }
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_AIM_TICKS) {
            return fail(NAME + ".aim_timed_out");
        }
        return SkillTickResult.running(false, false);
    }

    private SkillTickResult tickVerification(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (stepIndex >= plan.size() || activeMaterial == null) {
            return fail(NAME + ".verification_binding_missing");
        }
        final PlacementStep step = plan.get(stepIndex);
        lookAt(frame, center(step.target()));
        final boolean consumed =
                inventoryCount(frame, activeMaterial)
                    < materialCountBefore;
        final boolean visible = targetVisibleAs(
                frame,
                step.target(),
                activeMaterial
        );
        final boolean observedSolid =
                frame.navigation().voxelAt(step.target())
                    .filter(voxel ->
                            voxel.observationRevision()
                                > dispatchedObservationRevision)
                    .map(ObservedVoxel::kind)
                    .filter(kind -> kind == VoxelKind.SOLID)
                    .isPresent();
        if (consumed && (visible || observedSolid)) {
            placementsConfirmed++;
            stepIndex++;
            activeMaterial = null;
            materialCountBefore = -1;
            return transition(context, Phase.PREPARING_STEP);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            return fail(NAME + ".placement_unconfirmed");
        }
        return SkillTickResult.running(false, false);
    }

    private SkillTickResult prepareCleanup(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (cleanupIndex >= 2) {
            return finishCleanup(context, frame);
        }
        if (plan.size() < 3) {
            return fail(NAME + ".cleanup_plan_missing");
        }
        final GridPos target = cleanupTarget();
        if (freshPassableAfter(
                frame,
                target,
                cleanupDispatchedObservationRevision
        )) {
            cleanupIndex++;
            cleanupDispatchedObservationRevision = -1L;
            return transition(context, Phase.PREPARING_CLEANUP);
        }
        cleanupAlignedRevision = -1L;
        scanAlignmentTicks = 0;
        nextScanTick = context.gameTick();
        return transition(context, Phase.AIMING_CLEANUP);
    }

    private SkillTickResult tickCleanupAim(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (cleanupIndex >= 2 || plan.size() < 3) {
            return fail(NAME + ".cleanup_binding_missing");
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_AIM_TICKS) {
            return fail(NAME + ".cleanup_aim_timed_out");
        }
        final GridPos target = cleanupTarget();
        final LookAngles angles = lookAngles(
                frame,
                new PerceptionVec3(
                        target.x() + 0.5,
                        target.y() + 0.5,
                        target.z() + 0.5
                )
        );
        if (!core.stop().accepted()
                || !core.look(new LookIntent(
                        angles.yaw(),
                        angles.pitch()
                )).accepted()) {
            return fail(NAME + ".cleanup_aim_rejected");
        }
        if (!aligned(frame, angles.yaw(), angles.pitch())) {
            cleanupAlignedRevision = -1L;
            scanAlignmentTicks++;
            if (scanAlignmentTicks
                    > MAXIMUM_SCAN_ALIGNMENT_TICKS) {
                return fail(
                        NAME + ".cleanup_alignment_timed_out"
                );
            }
            return SkillTickResult.running(false, false);
        }
        if (cleanupAlignedRevision < 0L) {
            cleanupAlignedRevision = frame.observationRevision();
            return SkillTickResult.running(false, true);
        }
        final Optional<InteractionSkillFrame> interactionFrame =
                interactionFrames.current();
        if (frame.observationRevision()
                    <= cleanupAlignedRevision
                || interactionFrame.isEmpty()
                || interactionFrame.orElseThrow()
                    .observationRevision()
                    <= cleanupAlignedRevision) {
            return SkillTickResult.running(false, true);
        }
        final InteractionSkillFrame current =
                interactionFrame.orElseThrow();
        final Optional<VisibleBlockFace> crosshair =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> sameBlock(face, target));
        if (crosshair.isEmpty()) {
            return SkillTickResult.running(false, false);
        }
        final VisibleBlockFace selected = crosshair.orElseThrow();
        final Optional<VisibleBlockFace> retainedFace =
                current.visibleBlockFaces().stream()
                        .filter(face ->
                                sameBlock(face, target)
                                    && face.face().equals(
                                        selected.face()
                                    )
                                    && face.blockTypeId().equals(
                                        selected.blockTypeId()
                                    )
                        )
                        .findFirst();
        if (retainedFace.isEmpty()) {
            return SkillTickResult.running(false, false);
        }
        final Optional<BlockFace> face =
                blockFace(selected.face());
        if (face.isEmpty()) {
            return fail(NAME + ".cleanup_face_invalid");
        }
        cleanupBreakParameters = new BreakBlockParameters(
                boundDimension,
                new ObservedBlockTarget(
                        current.observationRevision(),
                        target.x(),
                        target.y(),
                        target.z(),
                        face.orElseThrow()
                )
        );
        cleanupBreak = new BreakBlockSkill(
                expectedPlayerId,
                interactions,
                interactionFrames,
                InteractionSkillPolicy.defaults()
        );
        final Optional<SkillFailure> rejected =
                cleanupBreak.preconditions(
                        context,
                        cleanupBreakParameters
                );
        if (rejected.isPresent()) {
            cleanupBreak = null;
            cleanupBreakParameters = null;
            if (transientCleanupBinding(
                    rejected.orElseThrow().code()
            )) {
                cleanupAlignedRevision = -1L;
                return SkillTickResult.running(false, true);
            }
            return fail(
                    NAME + ".cleanup_break_rejected."
                        + rejected.orElseThrow().code()
            );
        }
        cleanupDispatchedObservationRevision =
                current.observationRevision();
        cleanupBreak.start(context, cleanupBreakParameters);
        return transition(context, Phase.MINING_CLEANUP);
    }

    private SkillTickResult tickCleanupMining(
            final SkillContext context
    ) {
        if (cleanupBreak == null
                || cleanupBreakParameters == null) {
            return fail(NAME + ".cleanup_break_binding_missing");
        }
        final SkillTickResult result = cleanupBreak.tick(
                context,
                cleanupBreakParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            final String childCode = result.failure()
                    .map(SkillFailure::code)
                    .orElse("unknown");
            cleanupBreak = null;
            cleanupBreakParameters = null;
            return fail(childCode);
        }
        if (result.status()
                != SkillTickResult.Status.COMPLETED) {
            return SkillTickResult.running(
                    result.madeProgress(),
                    result.safeCheckpoint()
            );
        }
        cleanupBreak = null;
        cleanupBreakParameters = null;
        return transition(context, Phase.VERIFYING_CLEANUP);
    }

    private SkillTickResult tickCleanupVerification(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (cleanupIndex >= 2 || plan.size() < 3) {
            return fail(NAME + ".cleanup_verification_binding_missing");
        }
        final GridPos target = cleanupTarget();
        lookAt(frame, center(target));
        if (freshPassableAfter(
                frame,
                target,
                cleanupDispatchedObservationRevision
        )) {
            cleanupIndex++;
            cleanupDispatchedObservationRevision = -1L;
            return cleanupIndex >= 2
                    ? finishCleanup(context, frame)
                    : transition(
                            context,
                            Phase.PREPARING_CLEANUP
                    );
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_CONFIRM_TICKS) {
            return fail(NAME + ".cleanup_unconfirmed");
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult finishCleanup(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        temporaryPillarCleanupComplete = true;
        if (!atPosition(frame, anchor)) {
            return beginPositioning(
                    context,
                    frame,
                    anchor,
                    Phase.RETURNING_TO_ANCHOR
            );
        }
        return beginRoofVerification(context);
    }

    private SkillTickResult beginRoofVerification(
            final SkillContext context
    ) {
        roofScanView = 0;
        scanAlignmentTicks = 0;
        roofAlignedRevision = -1L;
        roofSurveyTargets = roofSurveyTargets(anchor);
        nextScanTick = context.gameTick();
        return transition(context, Phase.VERIFYING_ROOF);
    }

    private SkillTickResult tickRoofVerification(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (AcquireShelteredEnderPearlSkill
                .hasObservedTwoBlockShelter(frame)) {
            core.stop();
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (roofScanView >= roofSurveyTargets.size()) {
            return fail(NAME + ".roof_not_verified");
        }
        return tickRoofScanStep(context, frame);
    }

    private SkillTickResult tickRoofScanStep(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(false, true);
        }
        final LookAngles angles = lookAngles(
                frame,
                roofSurveyTargets.get(roofScanView)
        );
        final float yaw = angles.yaw();
        final float pitch = angles.pitch();
        if (!core.stop().accepted()
                || !core.look(new LookIntent(yaw, pitch)).accepted()) {
            return fail(NAME + ".roof_scan_rejected");
        }
        if (!aligned(frame, yaw, pitch)) {
            roofAlignedRevision = -1L;
            scanAlignmentTicks++;
            if (scanAlignmentTicks
                    > MAXIMUM_SCAN_ALIGNMENT_TICKS) {
                return fail(
                        NAME + ".roof_scan_alignment_timed_out"
                );
            }
            return SkillTickResult.running(false, false);
        }
        if (roofAlignedRevision < 0L) {
            roofAlignedRevision = frame.observationRevision();
            return SkillTickResult.running(false, true);
        }
        if (frame.observationRevision()
                <= roofAlignedRevision) {
            return SkillTickResult.running(false, true);
        }
        roofScanView++;
        scanAlignmentTicks = 0;
        roofAlignedRevision = -1L;
        nextScanTick =
                context.gameTick() + SURVEY_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private boolean siteObservedClear(
            final CoreSkillFrame frame,
            final List<PlacementStep> candidate
    ) {
        final PlacementStep first = candidate.getFirst();
        if (!safeSupport(frame, first.support())) {
            return false;
        }
        for (PlacementStep step : candidate) {
            if (!targetRemainsPassable(frame, step.target())) {
                return false;
            }
            if (requiresPositioning(step)) {
                final GridPos vantage = placementVantage(step);
                if (!safeSupport(frame, vantage.below())
                        || !currentPassable(frame, vantage)
                        || !currentPassable(
                                frame,
                                vantage.above()
                        )) {
                    return false;
                }
            }
            if (frame.visibleEntities().stream().anyMatch(entity ->
                    VisibleEntityPlacementEnvelope.intersectsBlock(
                            entity,
                            step.target().x(),
                            step.target().y(),
                            step.target().z()
                    )
            )) {
                return false;
            }
        }
        return safeSupport(frame, anchor.below())
                && currentPassable(frame, anchor)
                && currentPassable(frame, anchor.above());
    }

    private static List<PlacementStep> plan(
            final GridPos center,
            final PillarDirection direction
    ) {
        final GridPos lower = center.offset(
                direction.dx(),
                0,
                direction.dz()
        );
        final GridPos upper = lower.above();
        final GridPos starter = upper.above();
        final List<PlacementStep> steps = new ArrayList<>();
        steps.add(step(lower, lower.below(), BlockFace.UP, false));
        steps.add(step(upper, lower, BlockFace.UP, false));
        steps.add(step(starter, upper, BlockFace.UP, true));

        final int sideX = direction.dz();
        final int sideZ = -direction.dx();
        final GridPos sideA = starter.offset(sideX, 0, sideZ);
        final GridPos sideB = starter.offset(-sideX, 0, -sideZ);
        final GridPos inward = starter.offset(
                -direction.dx(),
                0,
                -direction.dz()
        );
        final GridPos inwardA = sideA.offset(
                -direction.dx(),
                0,
                -direction.dz()
        );
        final GridPos inwardB = sideB.offset(
                -direction.dx(),
                0,
                -direction.dz()
        );
        final GridPos far = inward.offset(
                -direction.dx(),
                0,
                -direction.dz()
        );
        final GridPos farA = inwardA.offset(
                -direction.dx(),
                0,
                -direction.dz()
        );
        final GridPos farB = inwardB.offset(
                -direction.dx(),
                0,
                -direction.dz()
        );

        steps.add(adjacentStep(starter, sideA));
        steps.add(adjacentStep(starter, sideB));
        steps.add(adjacentStep(starter, inward));
        steps.add(adjacentStep(sideA, inwardA));
        steps.add(adjacentStep(sideB, inwardB));
        steps.add(adjacentStep(inward, far));
        steps.add(adjacentStep(inwardA, farA));
        steps.add(adjacentStep(inwardB, farB));
        return List.copyOf(steps);
    }

    private static List<PerceptionVec3> siteSurveyTargets(
            final GridPos center,
            final PillarDirection direction
    ) {
        final LinkedHashSet<GridPos> positions =
                new LinkedHashSet<>();
        positions.add(center.below());
        positions.add(center);
        positions.add(center.above());
        for (PlacementStep step : plan(center, direction)) {
            positions.add(step.target());
            if (requiresPositioning(step)) {
                final GridPos vantage =
                        placementVantage(step);
                positions.add(vantage.below());
            }
        }
        positions.add(center.offset(
                direction.dx(),
                -1,
                direction.dz()
        ));
        return positions.stream()
                .map(position -> new PerceptionVec3(
                        position.x() + 0.5,
                        position.y() + 0.5,
                        position.z() + 0.5
                ))
                .toList();
    }

    private static List<PerceptionVec3> roofSurveyTargets(
            final GridPos center
    ) {
        final List<PerceptionVec3> targets = new ArrayList<>(9);
        final double undersideY = center.y() + 2.0;
        for (int xOffset = -1; xOffset <= 1; xOffset++) {
            for (int zOffset = -1; zOffset <= 1; zOffset++) {
                targets.add(new PerceptionVec3(
                        center.x() + xOffset + 0.5,
                        undersideY,
                        center.z() + zOffset + 0.5
                ));
            }
        }
        return List.copyOf(targets);
    }

    private static PlacementStep adjacentStep(
            final GridPos support,
            final GridPos target
    ) {
        final int dx = target.x() - support.x();
        final int dz = target.z() - support.z();
        final BlockFace face;
        if (dx == 1 && dz == 0) {
            face = BlockFace.EAST;
        } else if (dx == -1 && dz == 0) {
            face = BlockFace.WEST;
        } else if (dx == 0 && dz == 1) {
            face = BlockFace.SOUTH;
        } else if (dx == 0 && dz == -1) {
            face = BlockFace.NORTH;
        } else {
            throw new IllegalArgumentException(
                    "Roof steps must share one horizontal face"
            );
        }
        return step(target, support, face, false);
    }

    private static PlacementStep step(
            final GridPos target,
            final GridPos support,
            final BlockFace face,
            final boolean jumpPlacement
    ) {
        return new PlacementStep(
                target,
                support,
                face,
                hitPoint(support, face),
                jumpPlacement
        );
    }

    private static ActionVec3 hitPoint(
            final GridPos support,
            final BlockFace face
    ) {
        final double x = support.x() + 0.5;
        final double y = support.y() + 0.5;
        final double z = support.z() + 0.5;
        return switch (face) {
            case DOWN -> new ActionVec3(x, support.y(), z);
            case UP -> new ActionVec3(x, support.y() + 1.0, z);
            case NORTH ->
                    new ActionVec3(x, y, support.z());
            case SOUTH ->
                    new ActionVec3(x, y, support.z() + 1.0);
            case WEST ->
                    new ActionVec3(support.x(), y, z);
            case EAST ->
                    new ActionVec3(support.x() + 1.0, y, z);
        };
    }

    private Optional<String> selectMaterial(
            final CoreSkillFrame frame
    ) {
        return frame.inventory().stream()
                .filter(item -> isFullBlockItem(item.itemId()))
                .sorted(Comparator
                        .comparingInt(
                                InventoryItemSummary::count
                        )
                        .reversed()
                        .thenComparing(
                                InventoryItemSummary::itemId
                        ))
                .map(InventoryItemSummary::itemId)
                .findFirst();
    }

    static int buildingBlockCount(final CoreSkillFrame frame) {
        return frame.inventory().stream()
                .filter(item -> isFullBlockItem(item.itemId()))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    static boolean isFullBlockItem(final String itemId) {
        return FULL_BLOCK_ITEMS.contains(itemId)
                || itemId.startsWith("minecraft:")
                    && itemId.endsWith("_planks");
    }

    private static int inventoryCount(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().equals(itemId))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static boolean safeSupport(
            final CoreSkillFrame frame,
            final GridPos position
    ) {
        return frame.navigation().voxelAt(position)
                .filter(voxel ->
                        voxel.kind().supportsWeight())
                .filter(voxel ->
                        freshAndSafe(frame, voxel))
                .isPresent();
    }

    private static boolean currentPassable(
            final CoreSkillFrame frame,
            final GridPos position
    ) {
        return frame.navigation().voxelAt(position)
                .filter(voxel ->
                        voxel.kind().isPassable()
                                && !voxel.kind().isLiquid())
                .filter(voxel ->
                        freshAndSafe(frame, voxel))
                .isPresent();
    }

    private static boolean targetRemainsPassable(
            final CoreSkillFrame frame,
            final GridPos position
    ) {
        return currentPassable(frame, position);
    }

    private static boolean freshPassableAfter(
            final CoreSkillFrame frame,
            final GridPos position,
            final long observationRevision
    ) {
        return frame.navigation().voxelAt(position)
                .filter(voxel ->
                        voxel.observationRevision()
                            > observationRevision)
                .filter(voxel ->
                        voxel.kind().isPassable()
                                && !voxel.kind().isLiquid())
                .filter(voxel ->
                        freshAndSafe(frame, voxel))
                .isPresent();
    }

    private static boolean freshAndSafe(
            final CoreSkillFrame frame,
            final ObservedVoxel voxel
    ) {
        final long age = frame.navigation().revision()
                - voxel.observationRevision();
        return age >= 0
                && age <= MAXIMUM_SITE_OBSERVATION_AGE
                && voxel.effectiveDanger() <= MAXIMUM_SITE_DANGER;
    }

    private static boolean targetVisibleAs(
            final CoreSkillFrame frame,
            final GridPos target,
            final String itemId
    ) {
        return frame.visibleBlockFaces().stream()
                .anyMatch(face ->
                        face.block().x() == target.x()
                                && face.block().y() == target.y()
                                && face.block().z() == target.z()
                                && itemId.equals(face.blockTypeId())
                );
    }

    private static boolean matchesSupport(
            final VisibleBlockFace face,
            final PlacementStep step
    ) {
        return face.block().x() == step.support().x()
                && face.block().y() == step.support().y()
                && face.block().z() == step.support().z()
                && face.face().equals(
                        step.face().name().toLowerCase(Locale.ROOT)
                );
    }

    private static boolean sameBlock(
            final VisibleBlockFace face,
            final GridPos target
    ) {
        return face.block().x() == target.x()
                && face.block().y() == target.y()
                && face.block().z() == target.z();
    }

    private static Optional<BlockFace> blockFace(
            final String name
    ) {
        try {
            return Optional.of(BlockFace.valueOf(
                    name.toUpperCase(Locale.ROOT)
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static BlockInteractionTarget interactionTarget(
            final VisibleBlockFace face
    ) {
        return new BlockInteractionTarget(
                face.block().x(),
                face.block().y(),
                face.block().z(),
                BlockFace.valueOf(
                        face.face().toUpperCase(Locale.ROOT)
                ),
                new ActionVec3(
                        face.hitPosition().x(),
                        face.hitPosition().y(),
                        face.hitPosition().z()
                )
        );
    }

    private ActionOutcome lookAt(
            final CoreSkillFrame frame,
            final ActionVec3 target
    ) {
        final LookAngles angles = lookAngles(
                frame,
                new PerceptionVec3(
                        target.x(),
                        target.y(),
                        target.z()
                )
        );
        return core.look(new LookIntent(
                angles.yaw(),
                angles.pitch()
        ));
    }

    private static LookAngles lookAngles(
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        final double dx = target.x() - frame.eyePosition().x();
        final double dy = target.y() - frame.eyePosition().y();
        final double dz = target.z() - frame.eyePosition().z();
        return new LookAngles(
                normalizeDegrees(
                        (float) Math.toDegrees(
                                Math.atan2(-dx, dz)
                        )
                ),
                (float) -Math.toDegrees(
                        Math.atan2(dy, Math.hypot(dx, dz))
                )
        );
    }

    private static ActionVec3 center(final GridPos position) {
        return new ActionVec3(
                position.x() + 0.5,
                position.y() + 0.5,
                position.z() + 0.5
        );
    }

    private static boolean aligned(
            final CoreSkillFrame frame,
            final float targetYaw,
            final float targetPitch
    ) {
        final float yawError = Math.abs(normalizeDegrees(
                yaw(frame) - targetYaw
        ));
        final float pitchError = Math.abs(
                pitch(frame) - targetPitch
        );
        return yawError <= SCAN_ALIGNMENT_TOLERANCE_DEGREES
                && pitchError
                    <= SCAN_ALIGNMENT_TOLERANCE_DEGREES;
    }

    private static float yaw(final CoreSkillFrame frame) {
        return normalizeDegrees((float) Math.toDegrees(Math.atan2(
                -frame.lookDirection().x(),
                frame.lookDirection().z()
        )));
    }

    private static float pitch(final CoreSkillFrame frame) {
        return (float) -Math.toDegrees(Math.atan2(
                frame.lookDirection().y(),
                Math.hypot(
                        frame.lookDirection().x(),
                        frame.lookDirection().z()
                )
        ));
    }

    private static float normalizeDegrees(float value) {
        value %= 360.0F;
        if (value >= 180.0F) {
            value -= 360.0F;
        }
        if (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }

    private static boolean centered(
            final PerceptionVec3 position
    ) {
        final double fractionalX =
                position.x() - Math.floor(position.x());
        final double fractionalZ =
                position.z() - Math.floor(position.z());
        return fractionalX >= 0.25
                && fractionalX <= 0.75
                && fractionalZ >= 0.25
                && fractionalZ <= 0.75;
    }

    private static boolean requiresPositioning(
            final PlacementStep step
    ) {
        return !step.jumpPlacement()
                && step.target().y() == step.support().y();
    }

    private static GridPos placementVantage(
            final PlacementStep step
    ) {
        return step.target().offset(0, -2, 0);
    }

    private static boolean atPosition(
            final CoreSkillFrame frame,
            final GridPos destination
    ) {
        if (destination == null
                || frame.feet().y() != destination.y()) {
            return false;
        }
        final double dx = frame.position().x()
                - (destination.x() + 0.5);
        final double dz = frame.position().z()
                - (destination.z() + 0.5);
        return dx * dx + dz * dz
                <= POSITION_ARRIVAL_RADIUS
                    * POSITION_ARRIVAL_RADIUS;
    }

    private static boolean sameHorizontalCell(
            final GridPos expected,
            final GridPos actual
    ) {
        return expected != null
                && actual != null
                && expected.x() == actual.x()
                && expected.z() == actual.z();
    }

    private static boolean withinBuildFootprint(
            final GridPos expected,
            final GridPos actual
    ) {
        return expected != null
                && actual != null
                && Math.abs(expected.x() - actual.x()) <= 2
                && Math.abs(expected.z() - actual.z()) <= 2
                && Math.abs(expected.y() - actual.y()) <= 1;
    }

    static boolean mayCompleteObservedRoof(
            final int placements,
            final boolean cleanupComplete
    ) {
        return placements == 0 || cleanupComplete;
    }

    private GridPos cleanupTarget() {
        return cleanupIndex == 0
                ? plan.get(1).target()
                : plan.get(0).target();
    }

    private static boolean transientCleanupBinding(
            final String code
    ) {
        return code.endsWith(".observation_expired")
                || code.endsWith(".target_not_visible")
                || code.endsWith(".stale_observation");
    }

    private void cancelPositioning(final SkillContext context) {
        if (positioningParameters == null) {
            return;
        }
        positioningMovement.cancel(
                context,
                positioningParameters
        );
        positioningParameters = null;
    }

    private void cancelCleanup(final SkillContext context) {
        if (cleanupBreak != null
                && cleanupBreakParameters != null) {
            cleanupBreak.cancel(
                    context,
                    cleanupBreakParameters
            );
        }
        cleanupBreak = null;
        cleanupBreakParameters = null;
    }

    private SkillTickResult transition(
            final SkillContext context,
            final Phase next
    ) {
        phase = next;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult fail(final String code) {
        if (cleanupBreak != null) {
            interactions.abortMining();
            cleanupBreak = null;
            cleanupBreakParameters = null;
        }
        core.stop();
        failure = SkillFailure.of(code);
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    private Optional<CoreSkillFrame> ownedFrame() {
        return coreFrames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
        );
    }

    private record PlacementStep(
            GridPos target,
            GridPos support,
            BlockFace face,
            ActionVec3 hitPoint,
            boolean jumpPlacement
    ) {
    }

    private record LookAngles(float yaw, float pitch) {
    }

    private enum PillarDirection {
        EAST(1, 0),
        SOUTH(0, 1),
        WEST(-1, 0),
        NORTH(0, -1);

        private final int dx;
        private final int dz;

        PillarDirection(final int dx, final int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        int dx() {
            return dx;
        }

        int dz() {
            return dz;
        }
    }

    private enum PositioningCompletion {
        NEXT_PLACEMENT,
        CLEANUP,
        ROOF_VERIFICATION
    }

    private enum Phase {
        IDLE(false),
        CHECKING_EXISTING_ROOF(true),
        SURVEYING(true),
        PREPARING_STEP(true),
        POSITIONING_STEP(true),
        RETURNING_FOR_CLEANUP(true),
        EQUIPPING(true),
        AIMING(true),
        VERIFYING(true),
        PREPARING_CLEANUP(true),
        AIMING_CLEANUP(true),
        MINING_CLEANUP(true),
        VERIFYING_CLEANUP(true),
        RETURNING_TO_ANCHOR(true),
        VERIFYING_ROOF(true),
        COMPLETED(false),
        CANCELLED(false),
        FAILED(false);

        private final boolean active;

        Phase(final boolean active) {
            this.active = active;
        }

        boolean active() {
            return active;
        }
    }
}
