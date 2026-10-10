package dev.mcai.companion.skills.stronghold;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.perception.InventoryItemSummary;
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
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.core.TravelToParameters;
import dev.mcai.companion.skills.core.TravelToSkill;
import dev.mcai.companion.skills.gathering.ResourceInventorySource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.mining.ExcavateSafeTunnelParameters;
import dev.mcai.companion.skills.mining.ExcavateSafeTunnelSkill;
import dev.mcai.companion.skills.mining.MiningSkillPolicy;
import dev.mcai.companion.skills.mining.TunnelDirection;
import dev.mcai.companion.skills.mining.TunnelMode;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Reaches the measured stronghold search point and exposes real stronghold
 * blocks through ordinary travel and observation-driven mining.
 *
 * <p>The Eye intersection is only a horizontal search estimate. This skill
 * never treats arrival at that estimate as structure evidence. Once close,
 * it cuts a bounded square descending stair: east, south, west, north. Every
 * block removal, torch placement, and body step is delegated to
 * {@link ExcavateSafeTunnelSkill}; success requires a stronghold block in the
 * companion's current first-person semantic frame. No level, chunk, seed,
 * heightmap, structure, or hidden-block accessor is available here.</p>
 */
public final class ReachObservedStrongholdSkill
        implements Skill<NoParameters> {
    public static final String NAME = "reach_observed_stronghold";

    static final int EXCAVATION_LEG_STEPS = 12;
    static final int MAXIMUM_EXCAVATION_LEGS = 12;
    static final int DEPTH_PROBE_BASE_STEPS = 1;
    static final int MAXIMUM_DEPTH_PROBE_LEGS = 24;
    static final double MAXIMUM_APPROACH_SEGMENT = 192.0;

    private static final int MAXIMUM_TICKS = 150_000;
    private static final int MAXIMUM_ENTRY_STEPS = 6;
    private static final int MAXIMUM_ENTRY_DEPTH_ADJUSTMENTS = 6;
    private static final int MAXIMUM_ENTRY_HEIGHT_ADJUSTMENTS = 12;
    private static final int MAXIMUM_TRAVEL_RECOVERIES = 24;
    private static final int MAXIMUM_ALIGNMENT_TICKS = 80;
    private static final int MAXIMUM_ENTRY_VERIFICATION_TICKS = 200;
    private static final float ENTRY_SCAN_PITCH = 20.0F;
    private static final float ENTRY_SCAN_ALIGNMENT_DEGREES = 2.0F;
    private static final float[] ENTRY_SCAN_YAW_OFFSETS = {
        0.0F,
        90.0F,
        180.0F,
        -90.0F
    };
    private static final double MINIMUM_TRAVEL_RECOVERY_PROGRESS = 2.0;
    private static final double MINIMUM_APPROACH_RADIUS = 6.0;
    private static final double MAXIMUM_APPROACH_RADIUS = 12.0;
    private static final int MINIMUM_SAFE_DESTINATION_Y = -58;
    private static final double NORMAL_MINIMUM_HEALTH = 0.55;
    private static final double HARDCORE_MINIMUM_HEALTH = 0.85;
    private static final int NORMAL_MINIMUM_FOOD = 7;
    private static final int HARDCORE_MINIMUM_FOOD = 12;
    private static final double NORMAL_MAXIMUM_DANGER = 0.25;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.10;

    private static final List<TunnelDirection> DESCENDING_DIRECTIONS =
            List.of(
                    TunnelDirection.EAST,
                    TunnelDirection.SOUTH,
                    TunnelDirection.WEST,
                    TunnelDirection.NORTH
            );
    private static final List<String> STRONGHOLD_TARGET_BLOCKS =
            List.of(
                    "minecraft:end_portal_frame",
                    "minecraft:stone_bricks",
                    "minecraft:cracked_stone_bricks",
                    "minecraft:mossy_stone_bricks",
                    "minecraft:infested_stone_bricks",
                    "minecraft:infested_cracked_stone_bricks",
                    "minecraft:infested_mossy_stone_bricks",
                    "minecraft:iron_bars"
            );
    private static final Set<String> STRONGHOLD_TARGET_SET =
            Set.copyOf(STRONGHOLD_TARGET_BLOCKS);
    private static final List<String> PICKAXE_PREFERENCE = List.of(
            "minecraft:netherite_pickaxe",
            "minecraft:diamond_pickaxe",
            "minecraft:iron_pickaxe",
            "minecraft:stone_pickaxe"
    );
    private static final List<String> ENTRY_STOP_BLOCKS = List.of(
            "minecraft:end_portal_frame"
    );

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final ResourceInventorySource inventory;
    private final EyeTraceResultBuffer eyeTraces;
    private final LongSupplier sessionGeneration;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1L;
    private long startedAtTick = -1L;
    private long alignmentStartedAtTick = -1L;
    private long requiredObservationRevision = -1L;
    private double searchX;
    private double searchZ;
    private double approachRadius;
    private double bestHorizontalDistance;
    private double childStartHorizontalDistance;
    private int travelRecoveries;
    private int completedExcavationLegs;
    private int completedDepthProbeLegs;
    private String selectedPickaxe = "";
    private GridPos observedStrongholdBlock;
    private PerceptionVec3 entryStartPosition;
    private GridPos entryStartFeet;
    private GridPos verifiedEntrySupportFeet;
    private boolean verifiedEntrySupportIsStronghold;
    private TunnelDirection entryDirection;
    private TunnelMode entryMode;
    private int entryMaximumSteps;
    private int entryDepthAdjustments;
    private int entryHeightAdjustments;
    private boolean entryDepthAdjustmentPending;
    private boolean entryHeightAdjustmentPending;
    private boolean entryRetreatPending;
    private int entryRetreatSteps;
    private long entryVerificationStartedAtTick = -1L;
    private float entryVerificationBaseYaw;
    private int entryVerificationScanView;
    private TravelToSkill travel;
    private TravelToParameters travelParameters;
    private ExcavateSafeTunnelSkill excavation;
    private ExcavateSafeTunnelParameters excavationParameters;

    public ReachObservedStrongholdSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final ResourceInventorySource inventory,
            final EyeTraceResultBuffer eyeTraces,
            final LongSupplier sessionGeneration
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
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.eyeTraces = Objects.requireNonNull(
                eyeTraces,
                "eyeTraces"
        );
        this.sessionGeneration = Objects.requireNonNull(
                sessionGeneration,
                "sessionGeneration"
        );
    }

    @Override
    public SkillParameterParser<NoParameters> parameters() {
        return arguments -> arguments != null && arguments.isEmpty()
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
        final Optional<CoreSkillFrame> current = ownedFrame();
        if (current.isEmpty()) {
            return Optional.of(failure("body_unavailable"));
        }
        final CoreSkillFrame frame = current.orElseThrow();
        if (!DimensionRef.OVERWORLD.equals(frame.dimension())) {
            return Optional.of(failure("overworld_required"));
        }
        if (!frame.onGround() || frame.inWater()) {
            return Optional.of(failure("stable_dry_pose_required"));
        }
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return unsafe;
        }
        if (intersection(context.goalRevision()).isEmpty()) {
            return Optional.of(failure(
                    "triangulated_search_area_required"
            ));
        }
        final long generation;
        try {
            generation = sessionGeneration.getAsLong();
        } catch (RuntimeException exception) {
            return Optional.of(failure("session_unavailable"));
        }
        if (generation < 0L) {
            return Optional.of(failure("session_unavailable"));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final CoreSkillFrame frame = ownedFrame().orElseThrow(
                () -> new IllegalStateException(
                        "Stronghold-search body changed before start"
                )
        );
        final EyeTraceHistorySnapshot.Intersection intersection =
                intersection(context.goalRevision()).orElseThrow(
                        () -> new IllegalStateException(
                                "Stronghold intersection changed before start"
                        )
                );
        cancelChildren(context);
        failure = null;
        boundSessionGeneration = sessionGeneration.getAsLong();
        startedAtTick = context.gameTick();
        alignmentStartedAtTick = -1L;
        requiredObservationRevision = -1L;
        searchX = intersection.x();
        searchZ = intersection.z();
        approachRadius = Math.max(
                MINIMUM_APPROACH_RADIUS,
                Math.min(
                        MAXIMUM_APPROACH_RADIUS,
                        intersection.uncertaintyRadius() * 0.5
                )
        );
        bestHorizontalDistance = horizontalDistance(frame);
        childStartHorizontalDistance = bestHorizontalDistance;
        travelRecoveries = 0;
        completedExcavationLegs = 0;
        completedDepthProbeLegs = 0;
        selectedPickaxe = "";
        observedStrongholdBlock = null;
        entryStartPosition = null;
        entryStartFeet = null;
        verifiedEntrySupportFeet = null;
        verifiedEntrySupportIsStronghold = false;
        entryDirection = null;
        entryMode = null;
        entryMaximumSteps = 0;
        entryDepthAdjustments = 0;
        entryHeightAdjustments = 0;
        entryDepthAdjustmentPending = false;
        entryHeightAdjustmentPending = false;
        entryRetreatPending = false;
        entryRetreatSteps = 0;
        entryVerificationStartedAtTick = -1L;
        entryVerificationBaseYaw = 0.0F;
        entryVerificationScanView = 0;
        phase = Phase.SELECTING;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context);
        } catch (RuntimeException exception) {
            return fail(context, failure("internal_failure"));
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        final GridPos position = frame == null
                ? new GridPos(0, 0, 0)
                : frame.feet();
        final String observed = observedStrongholdBlock == null
                ? "null"
                : String.format(
                        Locale.ROOT,
                        "[%d,%d,%d]",
                        observedStrongholdBlock.x(),
                        observedStrongholdBlock.y(),
                        observedStrongholdBlock.z()
                );
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"searchX\":%.3f,"
                            + "\"searchZ\":%.3f,\"approachRadius\":%.2f,"
                            + "\"position\":[%d,%d,%d],"
                            + "\"bestHorizontalDistance\":%.3f,"
                            + "\"travelRecoveries\":%d,"
                            + "\"completedExcavationLegs\":%d,"
                            + "\"completedDepthProbeLegs\":%d,"
                            + "\"selectedPickaxe\":\"%s\","
                            + "\"entryMode\":\"%s\","
                            + "\"entryMaximumSteps\":%d,"
                            + "\"entryDepthAdjustments\":%d,"
                            + "\"entryHeightAdjustments\":%d,"
                            + "\"entryScanView\":%d,"
                            + "\"observedStrongholdBlock\":%s}",
                        phase.name(),
                        searchX,
                        searchZ,
                        approachRadius,
                        position.x(),
                        position.y(),
                        position.z(),
                        bestHorizontalDistance,
                        travelRecoveries,
                        completedExcavationLegs,
                        completedDepthProbeLegs,
                        selectedPickaxe,
                        entryMode == null ? "" : entryMode.name(),
                        entryMaximumSteps,
                        entryDepthAdjustments,
                        entryHeightAdjustments,
                        entryVerificationScanView,
                        observed
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        cancelChildren(context);
        core.stop();
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
            default -> SkillResult.failed(
                    failure("invalid_state")
            );
        };
    }

    private SkillTickResult tickSafely(final SkillContext context) {
        if (context.gameTick() - startedAtTick > MAXIMUM_TICKS) {
            return fail(context, failure("timed_out"));
        }
        final Optional<CoreSkillFrame> current = ownedFrame();
        if (current.isEmpty()) {
            return fail(context, failure("body_unavailable"));
        }
        final CoreSkillFrame frame = current.orElseThrow();
        if (!DimensionRef.OVERWORLD.equals(frame.dimension())) {
            return fail(context, failure("dimension_changed"));
        }
        if (sessionGeneration.getAsLong() != boundSessionGeneration) {
            return fail(context, failure("session_changed"));
        }
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return fail(context, unsafe.orElseThrow());
        }
        final Optional<VisibleBlockFace> visibleStronghold =
                visibleStronghold(frame);
        if (visibleStronghold.isPresent()) {
            final VisibleBlockFace observed =
                    visibleStronghold.orElseThrow();
            observedStrongholdBlock = new GridPos(
                    observed.block().x(),
                    observed.block().y(),
                    observed.block().z()
            );
            if (phase == Phase.VERIFYING_ENTRY) {
                return verifyStrongholdEntry(context, frame);
            }
            if (phase != Phase.EXCAVATING
                    && phase != Phase.DEPTH_PROBING
                    && phase != Phase.ALIGNING_ENTRY_FLOOR
                    && phase != Phase.ENTERING_STRONGHOLD
                    && phase != Phase.ADJUSTING_ENTRY_DEPTH
                    && phase != Phase.RETREATING_ENTRY_PROBE
                    && phase != Phase.ADJUSTING_ENTRY_HEIGHT) {
                if (hasAccessibleStrongholdSupport(frame)) {
                    cancelChildren(context);
                    core.stop();
                    phase = Phase.COMPLETED;
                    return SkillTickResult.completed();
                }
                return beginStrongholdEntryAlignment(
                        context,
                        frame,
                        observed
                );
            }
        } else if (phase == Phase.VERIFYING_ENTRY) {
            return verifyStrongholdEntry(context, frame);
        }
        bestHorizontalDistance = Math.min(
                bestHorizontalDistance,
                horizontalDistance(frame)
        );
        return switch (phase) {
            case SELECTING -> selectNextAction(context, frame);
            case APPROACHING -> tickApproach(context, frame);
            case ALIGNING_FLOOR -> alignFloor(context, frame);
            case EXCAVATING -> tickExcavation(context, frame);
            case DEPTH_PROBING -> tickExcavation(context, frame);
            case ALIGNING_ENTRY_FLOOR ->
                    alignStrongholdEntryFloor(context, frame);
            case ENTERING_STRONGHOLD ->
                    tickStrongholdEntry(context, frame);
            case ADJUSTING_ENTRY_DEPTH ->
                    tickStrongholdEntry(context, frame);
            case RETREATING_ENTRY_PROBE ->
                    tickStrongholdEntry(context, frame);
            case ADJUSTING_ENTRY_HEIGHT ->
                    tickStrongholdEntry(context, frame);
            case VERIFYING_ENTRY ->
                    verifyStrongholdEntry(context, frame);
            default -> fail(context, failure("invalid_state"));
        };
    }

    private SkillTickResult beginStrongholdEntryAlignment(
            final SkillContext context,
            final CoreSkillFrame frame,
            final VisibleBlockFace observed
    ) {
        final int deltaX = observed.block().x() - frame.feet().x();
        final int deltaZ = observed.block().z() - frame.feet().z();
        if (deltaX == 0 && deltaZ == 0) {
            return fail(context, failure(
                    "stronghold_entry_direction_unavailable"
            ));
        }
        cancelChildren(context);
        entryStartPosition = frame.position();
        entryDirection = cardinalDirection(deltaX, deltaZ);
        /*
         * The outer search is already descending when it first exposes a
         * buried stronghold. A masonry ray below eye level is commonly the
         * lower part of a wall or the room floor; descending again while
         * crossing that wall can strand the body in the floor layer. Keep
         * the verified dry support elevation and cut a two-block-high
         * horizontal doorway. A true floor directly beneath the body is
         * handled by isAccessibleStrongholdSupport before this method.
         */
        entryMode = TunnelMode.HORIZONTAL;
        entryDepthAdjustments = 0;
        entryHeightAdjustments = 0;
        entryDepthAdjustmentPending = false;
        entryHeightAdjustmentPending = false;
        entryRetreatPending = false;
        entryRetreatSteps = 0;
        entryMaximumSteps = Math.max(
                1,
                Math.min(
                        MAXIMUM_ENTRY_STEPS,
                        Math.max(Math.abs(deltaX), Math.abs(deltaZ)) + 1
                )
        );
        alignmentStartedAtTick = context.gameTick();
        requiredObservationRevision =
                frame.observationRevision() + 1L;
        phase = Phase.ALIGNING_ENTRY_FLOOR;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult alignStrongholdEntryFloor(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!frame.onGround() || frame.inWater()) {
            return fail(context, failure(
                    "stable_dry_pose_required"
            ));
        }
        final ActionOutcome stopped = core.stop();
        final ActionOutcome looked = core.look(lookAt(
                frame.eyePosition(),
                new PerceptionVec3(
                        frame.feet().x() + 0.5,
                        frame.feet().y() - 0.99,
                        frame.feet().z() + 0.5
                )
        ));
        if (!stopped.accepted() || !looked.accepted()) {
            return fail(context, failure(
                    "entry_floor_alignment_rejected"
            ));
        }
        if (context.gameTick() - alignmentStartedAtTick
                > MAXIMUM_ALIGNMENT_TICKS) {
            return fail(context, failure(
                    "entry_floor_observation_timed_out"
            ));
        }
        if (frame.observationRevision()
                < requiredObservationRevision
                || !visibleFloorSupport(frame)) {
            return SkillTickResult.running(false, false);
        }
        return beginStrongholdEntry(context, frame);
    }

    private SkillTickResult beginStrongholdEntry(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (entryDirection == null
                || entryMode == null
                || entryMaximumSteps < 1) {
            return fail(context, failure(
                    "stronghold_entry_binding_missing"
            ));
        }
        final Optional<InteractionSkillFrame> interaction =
                interactionFrames.current();
        if (interaction.isEmpty()
                || interaction.orElseThrow().observationRevision()
                    != frame.observationRevision()
                || interaction.orElseThrow().sessionGeneration()
                    != boundSessionGeneration) {
            return fail(context, failure(
                    "interaction_observation_unavailable"
            ));
        }
        selectedPickaxe = preferredPickaxe(frame).orElse("");
        if (selectedPickaxe.isEmpty()) {
            return fail(context, failure("pickaxe_required"));
        }
        if (inventoryCount(frame, "minecraft:torch") <= 0) {
            return fail(context, failure("torch_required"));
        }
        final boolean adjustingDepth =
                entryDepthAdjustmentPending;
        final boolean adjustingHeight =
                entryHeightAdjustmentPending;
        final boolean retreating = entryRetreatPending;
        final TunnelDirection childDirection;
        final TunnelMode childMode;
        final int childMaximumSteps;
        final Phase childPhase;
        if (retreating) {
            childDirection = opposite(entryDirection);
            childMode = TunnelMode.HORIZONTAL;
            childMaximumSteps = entryRetreatSteps;
            childPhase = Phase.RETREATING_ENTRY_PROBE;
        } else if (adjustingHeight) {
            childDirection =
                    entryHeightAdjustmentDirection(
                            entryDirection,
                            entryHeightAdjustments
                    );
            childMode = TunnelMode.ASCENDING;
            childMaximumSteps = 1;
            childPhase = Phase.ADJUSTING_ENTRY_HEIGHT;
        } else if (adjustingDepth) {
            childDirection = entryDepthAdjustmentDirection(
                        entryDirection,
                        entryDepthAdjustments
            );
            childMode = TunnelMode.DESCENDING;
            childMaximumSteps = 1;
            childPhase = Phase.ADJUSTING_ENTRY_DEPTH;
        } else {
            childDirection = entryDirection;
            childMode = TunnelMode.HORIZONTAL;
            childMaximumSteps = entryMaximumSteps;
            childPhase = Phase.ENTERING_STRONGHOLD;
            entryStartPosition = frame.position();
            entryStartFeet = frame.feet();
            verifiedEntrySupportFeet = null;
            verifiedEntrySupportIsStronghold = false;
        }
        entryMode = childMode;
        excavationParameters = new ExcavateSafeTunnelParameters(
                DimensionRef.OVERWORLD,
                frame.observationRevision(),
                childDirection,
                childMode,
                childMaximumSteps,
                6,
                selectedPickaxe,
                ENTRY_STOP_BLOCKS
        );
        excavation = new ExcavateSafeTunnelSkill(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames,
                inventory,
                MiningSkillPolicy.defaults()
        );
        final Optional<SkillFailure> rejected =
                excavation.preconditions(
                        context,
                        excavationParameters
                );
        if (rejected.isPresent()) {
            clearExcavation();
            return fail(context, rejected.orElseThrow());
        }
        excavation.start(context, excavationParameters);
        phase = childPhase;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickStrongholdEntry(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (excavation == null || excavationParameters == null) {
            return fail(context, failure(
                    "stronghold_entry_binding_missing"
            ));
        }
        final boolean adjustingDepth =
                phase == Phase.ADJUSTING_ENTRY_DEPTH;
        final boolean adjustingHeight =
                phase == Phase.ADJUSTING_ENTRY_HEIGHT;
        final boolean retreating =
                phase == Phase.RETREATING_ENTRY_PROBE;
        final SkillTickResult result = excavation.tick(
                context,
                excavationParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            clearExcavation();
            if (retreating) {
                entryRetreatPending = false;
                entryHeightAdjustments++;
                entryHeightAdjustmentPending = true;
                alignmentStartedAtTick = context.gameTick();
                requiredObservationRevision =
                        frame.observationRevision() + 1L;
                phase = Phase.ALIGNING_ENTRY_FLOOR;
                return SkillTickResult.running(true, true);
            }
            if (adjustingHeight) {
                entryHeightAdjustmentPending = false;
                if (!shouldProbeWallAfterHeightAdjustment(
                        entryHeightAdjustments
                )) {
                    if (entryHeightAdjustments
                            >= MAXIMUM_ENTRY_HEIGHT_ADJUSTMENTS) {
                        return fail(context, failure(
                                "stronghold_entry_height_limit"
                        ));
                    }
                    entryHeightAdjustments++;
                    entryHeightAdjustmentPending = true;
                    alignmentStartedAtTick = context.gameTick();
                    requiredObservationRevision =
                            frame.observationRevision() + 1L;
                    phase = Phase.ALIGNING_ENTRY_FLOOR;
                    return SkillTickResult.running(true, true);
                }
                entryMode = TunnelMode.HORIZONTAL;
                alignmentStartedAtTick = context.gameTick();
                requiredObservationRevision =
                        frame.observationRevision() + 1L;
                phase = Phase.ALIGNING_ENTRY_FLOOR;
                return SkillTickResult.running(true, true);
            }
            if (adjustingDepth) {
                entryDepthAdjustmentPending = false;
                entryMode = TunnelMode.HORIZONTAL;
                alignmentStartedAtTick = context.gameTick();
                requiredObservationRevision =
                        frame.observationRevision() + 1L;
                phase = Phase.ALIGNING_ENTRY_FLOOR;
                return SkillTickResult.running(true, true);
            }
            requiredObservationRevision =
                    frame.observationRevision() + 1L;
            entryVerificationStartedAtTick = context.gameTick();
            entryVerificationBaseYaw =
                    yawOf(frame.lookDirection());
            entryVerificationScanView = 0;
            phase = Phase.VERIFYING_ENTRY;
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            final SkillFailure childFailure = result.failure()
                    .orElseGet(() -> failure(
                            "stronghold_entry_failed"
                    ));
            cancelExcavation(context);
            if (phase == Phase.ENTERING_STRONGHOLD
                    && childFailure.code().endsWith(
                        ".unsafe_support"
                    )
                    && entryDepthAdjustments
                        < MAXIMUM_ENTRY_DEPTH_ADJUSTMENTS) {
                entryDepthAdjustments++;
                entryDepthAdjustmentPending = true;
                alignmentStartedAtTick = context.gameTick();
                requiredObservationRevision =
                        frame.observationRevision() + 1L;
                phase = Phase.ALIGNING_ENTRY_FLOOR;
                return SkillTickResult.running(true, true);
            }
            return fail(context, childFailure);
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult verifyStrongholdEntry(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (entryStartPosition == null
                || observedStrongholdBlock == null) {
            return fail(context, failure(
                    "stronghold_entry_binding_missing"
            ));
        }
        if (context.gameTick() - entryVerificationStartedAtTick
                > MAXIMUM_ENTRY_VERIFICATION_TICKS) {
            return fail(context, failure(
                    "stronghold_entry_evidence_not_observed"
            ));
        }
        if (frame.observationRevision()
                < requiredObservationRevision) {
            return SkillTickResult.running(false, false);
        }
        if (verifiedEntrySupportFeet != null
                && !verifiedEntrySupportFeet.equals(frame.feet())) {
            verifiedEntrySupportFeet = null;
            verifiedEntrySupportIsStronghold = false;
        }
        /*
         * The semantic view is first-person. A ray that proves the sturdy
         * masonry directly under the body cannot simultaneously look far
         * enough forward to prove an adjacent room frontier. Persist the
         * floor proof for this stationary pose, then scan the room. Requiring
         * both facts in one frame made a legal entry practically
         * unconfirmable and caused the height search to climb past it.
         */
        if (verifiedEntrySupportFeet == null) {
            if (hasVisibleSafeFloorSupport(frame)) {
                verifiedEntrySupportFeet = frame.feet();
                verifiedEntrySupportIsStronghold =
                        hasAccessibleStrongholdSupport(frame);
                requiredObservationRevision =
                        frame.observationRevision() + 1L;
                return SkillTickResult.running(true, true);
            }
            final ActionOutcome stopped = core.stop();
            final ActionOutcome looked = core.look(lookAt(
                    frame.eyePosition(),
                    new PerceptionVec3(
                            frame.feet().x() + 0.5,
                            frame.feet().y() - 0.99,
                            frame.feet().z() + 0.5
                    )
            ));
            if (!stopped.accepted() || !looked.accepted()) {
                return fail(context, failure(
                        "stronghold_entry_support_scan_rejected"
                ));
            }
            return SkillTickResult.running(false, false);
        }
        final Optional<VisibleBlockFace> current =
                visibleStronghold(frame);
        final double displacement = Math.hypot(
                frame.position().x() - entryStartPosition.x(),
                frame.position().z() - entryStartPosition.z()
        );
        if (!frame.onGround()
                || frame.inWater()
                || displacement < 0.75) {
            return SkillTickResult.running(false, false);
        }
        if (current.isPresent()
                && entryDirection != null
                && verifiedEntrySupportFeet.equals(frame.feet())
                && verifiedEntrySupportIsStronghold
                && SearchObservedStrongholdPortalRoomSkill
                    .hasObservedAdjacentFrontier(
                            frame,
                            context.hardcore(),
                            entryDirection.stepX(),
                            entryDirection.stepZ()
                    )) {
            cancelChildren(context);
            core.stop();
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (entryVerificationScanView
                >= ENTRY_SCAN_YAW_OFFSETS.length) {
            return scheduleHigherEntryProbe(context, frame);
        }

        final float desiredYaw = normalizeDegrees(
                entryVerificationBaseYaw
                    + ENTRY_SCAN_YAW_OFFSETS[
                        entryVerificationScanView
                    ]
        );
        final ActionOutcome stopped = core.stop();
        final ActionOutcome looked = core.look(new LookIntent(
                desiredYaw,
                ENTRY_SCAN_PITCH
        ));
        if (!stopped.accepted() || !looked.accepted()) {
            return fail(context, failure(
                    "stronghold_entry_verification_rejected"
            ));
        }
        final boolean aligned =
                Math.abs(normalizeDegrees(
                        yawOf(frame.lookDirection()) - desiredYaw
                )) <= ENTRY_SCAN_ALIGNMENT_DEGREES
                    && Math.abs(
                            pitchOf(frame.lookDirection())
                                - ENTRY_SCAN_PITCH
                    ) <= ENTRY_SCAN_ALIGNMENT_DEGREES;
        if (!aligned) {
            return SkillTickResult.running(false, false);
        }
        entryVerificationScanView++;
        requiredObservationRevision =
                frame.observationRevision() + 1L;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult scheduleHigherEntryProbe(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (entryDirection == null
                || entryStartPosition == null
                || entryStartFeet == null) {
            return fail(context, failure(
                    "stronghold_entry_binding_missing"
            ));
        }
        if (entryHeightAdjustments
                >= MAXIMUM_ENTRY_HEIGHT_ADJUSTMENTS) {
            return fail(context, failure(
                    "stronghold_entry_no_observed_interior_frontier"
            ));
        }
        entryRetreatSteps = entryRetreatSteps(
                entryStartFeet,
                frame.feet(),
                entryDirection
        );
        entryRetreatPending = true;
        verifiedEntrySupportFeet = null;
        verifiedEntrySupportIsStronghold = false;
        entryHeightAdjustmentPending = false;
        entryDepthAdjustmentPending = false;
        entryVerificationScanView = 0;
        alignmentStartedAtTick = context.gameTick();
        requiredObservationRevision =
                frame.observationRevision() + 1L;
        phase = Phase.ALIGNING_ENTRY_FLOOR;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult selectNextAction(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (completedExcavationLegs > 0
                || completedDepthProbeLegs > 0
                || shouldResumeUndergroundSearch(
                        frame.position().x(),
                        frame.position().y(),
                        frame.position().z(),
                        searchX,
                        searchZ,
                        approachRadius
                )
                || horizontalDistance(frame) <= approachRadius) {
            return beginFloorAlignment(context);
        }
        return beginApproach(context, frame);
    }

    private SkillTickResult beginApproach(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double deltaX = searchX - frame.position().x();
        final double deltaZ = searchZ - frame.position().z();
        final double distance = Math.hypot(deltaX, deltaZ);
        if (distance <= approachRadius) {
            return beginFloorAlignment(context);
        }
        final double segmentDistance = Math.min(
                MAXIMUM_APPROACH_SEGMENT,
                Math.max(0.0, distance - approachRadius * 0.5)
        );
        final double scale = segmentDistance / distance;
        travelParameters = new TravelToParameters(
                DimensionRef.OVERWORLD,
                frame.position().x() + deltaX * scale,
                frame.position().y(),
                frame.position().z() + deltaZ * scale,
                Math.min(3.0, approachRadius)
        );
        travel = new TravelToSkill(
                expectedPlayerId,
                core,
                coreFrames,
                sessionGeneration
        );
        final Optional<SkillFailure> rejected =
                travel.preconditions(context, travelParameters);
        if (rejected.isPresent()) {
            clearTravel();
            return fail(context, rejected.orElseThrow());
        }
        childStartHorizontalDistance = horizontalDistance(frame);
        travel.start(context, travelParameters);
        phase = Phase.APPROACHING;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickApproach(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (travel == null || travelParameters == null) {
            return fail(context, failure("travel_binding_missing"));
        }
        final SkillTickResult result = travel.tick(
                context,
                travelParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            clearTravel();
            phase = Phase.SELECTING;
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            final double progress = childStartHorizontalDistance
                    - horizontalDistance(frame);
            final SkillFailure childFailure = result.failure()
                    .orElseGet(() -> failure("travel_failed"));
            cancelTravel(context);
            if (progress >= MINIMUM_TRAVEL_RECOVERY_PROGRESS
                    && travelRecoveries
                        < MAXIMUM_TRAVEL_RECOVERIES) {
                travelRecoveries++;
                phase = Phase.SELECTING;
                return SkillTickResult.running(true, true);
            }
            return fail(context, childFailure);
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult beginFloorAlignment(
            final SkillContext context
    ) {
        if (completedExcavationLegs >= MAXIMUM_EXCAVATION_LEGS) {
            return fail(context, failure("search_depth_exhausted"));
        }
        cancelExcavation(context);
        alignmentStartedAtTick = context.gameTick();
        requiredObservationRevision = ownedFrame()
                .map(CoreSkillFrame::observationRevision)
                .orElse(-1L) + 1L;
        phase = Phase.ALIGNING_FLOOR;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult alignFloor(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!frame.onGround() || frame.inWater()) {
            return fail(context, failure(
                    "stable_dry_pose_required"
            ));
        }
        final boolean depthLimited =
                frame.feet().y() - EXCAVATION_LEG_STEPS
                    < MINIMUM_SAFE_DESTINATION_Y;
        final ActionOutcome stopped = core.stop();
        final ActionOutcome looked = core.look(lookAt(
                frame.eyePosition(),
                new PerceptionVec3(
                        frame.feet().x() + 0.5,
                        frame.feet().y() - 0.99,
                        frame.feet().z() + 0.5
                )
        ));
        if (!stopped.accepted() || !looked.accepted()) {
            return fail(context, failure(
                    "floor_alignment_rejected"
            ));
        }
        if (context.gameTick() - alignmentStartedAtTick
                > MAXIMUM_ALIGNMENT_TICKS) {
            return fail(context, failure(
                    "floor_observation_timed_out"
            ));
        }
        if (frame.observationRevision()
                < requiredObservationRevision
                || !visibleFloorSupport(frame)) {
            return SkillTickResult.running(false, false);
        }
        if (depthLimited) {
            if (completedDepthProbeLegs
                    >= MAXIMUM_DEPTH_PROBE_LEGS) {
                return fail(context, failure(
                        "search_depth_exhausted"
                ));
            }
            return beginDepthProbe(context, frame);
        }
        return beginExcavation(context, frame);
    }

    private SkillTickResult beginExcavation(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        return beginExcavationChild(
                context,
                frame,
                descendingDirection(completedExcavationLegs),
                TunnelMode.DESCENDING,
                EXCAVATION_LEG_STEPS,
                Phase.EXCAVATING
        );
    }

    private SkillTickResult beginDepthProbe(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        return beginExcavationChild(
                context,
                frame,
                radialDepthProbeDirection(
                        frame.position().x(),
                        frame.position().z(),
                        searchX,
                        searchZ,
                        completedDepthProbeLegs
                ),
                TunnelMode.ASCENDING,
                depthProbeMaximumSteps(completedDepthProbeLegs),
                Phase.DEPTH_PROBING
        );
    }

    private SkillTickResult beginExcavationChild(
            final SkillContext context,
            final CoreSkillFrame frame,
            final TunnelDirection direction,
            final TunnelMode mode,
            final int maximumSteps,
            final Phase activePhase
    ) {
        final Optional<InteractionSkillFrame> interaction =
                interactionFrames.current();
        if (interaction.isEmpty()
                || interaction.orElseThrow().observationRevision()
                    != frame.observationRevision()
                || interaction.orElseThrow().sessionGeneration()
                    != boundSessionGeneration) {
            return fail(context, failure(
                    "interaction_observation_unavailable"
            ));
        }
        selectedPickaxe = preferredPickaxe(frame).orElse("");
        if (selectedPickaxe.isEmpty()) {
            return fail(context, failure("pickaxe_required"));
        }
        if (inventoryCount(frame, "minecraft:torch") <= 0) {
            return fail(context, failure("torch_required"));
        }
        excavationParameters = new ExcavateSafeTunnelParameters(
                DimensionRef.OVERWORLD,
                frame.observationRevision(),
                direction,
                mode,
                maximumSteps,
                6,
                selectedPickaxe,
                STRONGHOLD_TARGET_BLOCKS
        );
        excavation = new ExcavateSafeTunnelSkill(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames,
                inventory,
                MiningSkillPolicy.defaults()
        );
        final Optional<SkillFailure> rejected =
                excavation.preconditions(
                        context,
                        excavationParameters
                );
        if (rejected.isPresent()) {
            clearExcavation();
            return fail(context, rejected.orElseThrow());
        }
        excavation.start(context, excavationParameters);
        phase = activePhase;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickExcavation(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (excavation == null || excavationParameters == null) {
            return fail(context, failure(
                    "excavation_binding_missing"
            ));
        }
        final boolean depthProbe =
                phase == Phase.DEPTH_PROBING;
        final SkillTickResult result = excavation.tick(
                context,
                excavationParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            clearExcavation();
            if (depthProbe) {
                completedDepthProbeLegs++;
            } else {
                completedExcavationLegs++;
            }
            /*
             * The mining child can complete because its final fresh frame
             * exposed a requested block. Give the outer detector one tick to
             * consume that same fair evidence before enforcing the depth
             * budget or beginning another leg.
             */
            phase = Phase.SELECTING;
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            final SkillFailure childFailure = result.failure()
                    .orElseGet(() -> failure("excavation_failed"));
            cancelExcavation(context);
            if (depthProbe
                    && recoverableDepthProbeFailure(childFailure)
                    && completedDepthProbeLegs
                        < MAXIMUM_DEPTH_PROBE_LEGS) {
                completedDepthProbeLegs++;
                phase = Phase.SELECTING;
                return SkillTickResult.running(true, true);
            }
            return fail(context, childFailure);
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        if (Math.max(context.riskScore(), frame.danger())
                > maximumDanger) {
            return Optional.of(failure("danger_detected"));
        }
        final double health = frame.health() / frame.maxHealth();
        final double minimumHealth = context.hardcore()
                ? HARDCORE_MINIMUM_HEALTH
                : NORMAL_MINIMUM_HEALTH;
        if (health < minimumHealth) {
            return Optional.of(failure("health_reserve_required"));
        }
        final int minimumFood = context.hardcore()
                ? HARDCORE_MINIMUM_FOOD
                : NORMAL_MINIMUM_FOOD;
        if (frame.foodLevel() < minimumFood) {
            return Optional.of(failure("food_reserve_required"));
        }
        return Optional.empty();
    }

    private Optional<EyeTraceHistorySnapshot.Intersection> intersection(
            final long goalRevision
    ) {
        return eyeTraces.snapshot(goalRevision).flatMap(
                EyeTraceHistorySnapshot::estimatedIntersection
        );
    }

    private Optional<CoreSkillFrame> ownedFrame() {
        return coreFrames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
        );
    }

    private static Optional<VisibleBlockFace> visibleStronghold(
            final CoreSkillFrame frame
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> STRONGHOLD_TARGET_SET.contains(
                        face.blockTypeId()
                ))
                .min(java.util.Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private static boolean isAccessibleStrongholdSupport(
            final CoreSkillFrame frame,
            final VisibleBlockFace observed
    ) {
        return observed.block().x() == frame.feet().x()
                && observed.block().y() == frame.feet().y() - 1
                && observed.block().z() == frame.feet().z()
                && "up".equals(observed.face())
                && observed.topSupportAffordance()
                    .safelySupportsStanding();
    }

    static boolean hasAccessibleStrongholdSupport(
            final CoreSkillFrame frame
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> STRONGHOLD_TARGET_SET.contains(
                        face.blockTypeId()
                ))
                .anyMatch(face ->
                        isAccessibleStrongholdSupport(frame, face)
                );
    }

    static boolean hasVisibleSafeFloorSupport(
            final CoreSkillFrame frame
    ) {
        final GridPos support = frame.feet().below();
        return frame.visibleBlockFaces().stream().anyMatch(face ->
                face.block().x() == support.x()
                    && face.block().y() == support.y()
                    && face.block().z() == support.z()
                    && "up".equals(face.face())
                    && face.topSupportAffordance()
                        .safelySupportsStanding()
        );
    }

    static TunnelDirection cardinalDirection(
            final int deltaX,
            final int deltaZ
    ) {
        if (deltaX == 0 && deltaZ == 0) {
            throw new IllegalArgumentException(
                    "Stronghold entry direction needs horizontal displacement"
            );
        }
        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            return deltaX >= 0
                    ? TunnelDirection.EAST
                    : TunnelDirection.WEST;
        }
        return deltaZ >= 0
                ? TunnelDirection.SOUTH
                : TunnelDirection.NORTH;
    }

    static TunnelDirection entryDepthAdjustmentDirection(
            final TunnelDirection wallDirection,
            final int oneBasedAttempt
    ) {
        Objects.requireNonNull(wallDirection, "wallDirection");
        if (oneBasedAttempt < 1) {
            throw new IllegalArgumentException(
                    "Entry depth attempt must be positive"
            );
        }
        final boolean positiveSide =
                oneBasedAttempt % 2 == 1;
        return switch (wallDirection) {
            case EAST, WEST -> positiveSide
                    ? TunnelDirection.SOUTH
                    : TunnelDirection.NORTH;
            case NORTH, SOUTH -> positiveSide
                    ? TunnelDirection.EAST
                    : TunnelDirection.WEST;
        };
    }

    static TunnelDirection entryHeightAdjustmentDirection(
            final TunnelDirection wallDirection,
            final int oneBasedAttempt
    ) {
        Objects.requireNonNull(wallDirection, "wallDirection");
        if (oneBasedAttempt < 1) {
            throw new IllegalArgumentException(
                    "Entry height attempt must be positive"
            );
        }
        /*
         * Rise in a two-cell-wide switchback outside the wall. A direct
         * left/right alternation steps onto a previously excavated head
         * cell, while a single tangent drifts beyond a finite room. Each
         * four-leg run advances two tangent cells and the next run reverses,
         * so the footprint stays bounded and revisits columns only after
         * their higher support layer remains untouched.
         */
        final TunnelDirection tangent = switch (wallDirection) {
            case EAST, WEST -> TunnelDirection.SOUTH;
            case NORTH, SOUTH -> TunnelDirection.EAST;
        };
        final int run = (oneBasedAttempt - 1) / 4;
        final TunnelDirection runTangent = run % 2 == 0
                ? tangent
                : opposite(tangent);
        return switch ((oneBasedAttempt - 1) % 4) {
            case 0, 2 -> runTangent;
            case 1 -> opposite(wallDirection);
            default -> wallDirection;
        };
    }

    static boolean shouldProbeWallAfterHeightAdjustment(
            final int oneBasedAttempt
    ) {
        if (oneBasedAttempt < 1) {
            throw new IllegalArgumentException(
                    "Entry height attempt must be positive"
            );
        }
        /*
         * In the two-wide switchback, legs one and four are adjacent to the
         * wall. Legs two and three are the outer lane; probing the wall from
         * there would cross above the lower probe corridor, whose head cells
         * are intentionally air and cannot safely support a player.
         */
        final int leg = (oneBasedAttempt - 1) % 4;
        return leg == 0 || leg == 3;
    }

    static int entryRetreatSteps(
            final GridPos startFeet,
            final GridPos currentFeet,
            final TunnelDirection entryDirection
    ) {
        Objects.requireNonNull(startFeet, "startFeet");
        Objects.requireNonNull(currentFeet, "currentFeet");
        Objects.requireNonNull(entryDirection, "entryDirection");
        /*
         * Count blocks actually crossed along the entry bearing. Physical
         * body centres carry ordinary floating-point drift, so ceil() of
         * their Euclidean displacement can turn a three-block probe into a
         * four-block retreat. That extra block may leave the verified
         * corridor and step onto an earlier staircase's cleared head cell.
         */
        final int forwardBlocks =
                (currentFeet.x() - startFeet.x())
                    * entryDirection.stepX()
                + (currentFeet.z() - startFeet.z())
                    * entryDirection.stepZ();
        return Math.max(
                1,
                Math.min(MAXIMUM_ENTRY_STEPS, forwardBlocks)
        );
    }

    private static boolean visibleFloorSupport(
            final CoreSkillFrame frame
    ) {
        final GridPos support = frame.feet().below();
        return frame.visibleBlockFaces().stream().anyMatch(face ->
                face.block().x() == support.x()
                    && face.block().y() == support.y()
                    && face.block().z() == support.z()
                    && "up".equals(face.face())
        );
    }

    private static Optional<String> preferredPickaxe(
            final CoreSkillFrame frame
    ) {
        return PICKAXE_PREFERENCE.stream()
                .filter(itemId -> inventoryCount(frame, itemId) > 0)
                .findFirst();
    }

    static TunnelDirection descendingDirection(
            final int completedLegs
    ) {
        if (completedLegs < 0) {
            throw new IllegalArgumentException(
                    "completedLegs must be non-negative"
            );
        }
        return DESCENDING_DIRECTIONS.get(
                completedLegs % DESCENDING_DIRECTIONS.size()
        );
    }

    static TunnelDirection depthProbeDirection(
            final int completedLegs
    ) {
        return descendingDirection(completedLegs);
    }

    static TunnelDirection radialDepthProbeDirection(
            final double bodyX,
            final double bodyZ,
            final double targetX,
            final double targetZ,
            final int completedLegs
    ) {
        final double deltaX = bodyX - targetX;
        final double deltaZ = bodyZ - targetZ;
        if (Math.max(Math.abs(deltaX), Math.abs(deltaZ)) < 0.75) {
            return depthProbeDirection(completedLegs);
        }
        return cardinalDirection(
                (int) Math.copySign(
                        Math.max(1.0, Math.abs(deltaX)),
                        deltaX
                ),
                (int) Math.copySign(
                        Math.max(1.0, Math.abs(deltaZ)),
                        deltaZ
                )
        );
    }

    static int depthProbeMaximumSteps(final int completedLegs) {
        if (completedLegs < 0
                || completedLegs >= MAXIMUM_DEPTH_PROBE_LEGS) {
            throw new IllegalArgumentException(
                    "completed depth probes out of range"
            );
        }
        return DEPTH_PROBE_BASE_STEPS;
    }

    static boolean shouldResumeUndergroundSearch(
            final double bodyX,
            final double bodyY,
            final double bodyZ,
            final double targetX,
            final double targetZ,
            final double radius
    ) {
        return bodyY <= MINIMUM_SAFE_DESTINATION_Y
                    + EXCAVATION_LEG_STEPS
                && Math.hypot(
                        bodyX - targetX,
                        bodyZ - targetZ
                ) <= radius + MAXIMUM_DEPTH_PROBE_LEGS;
    }

    private static boolean recoverableDepthProbeFailure(
            final SkillFailure reason
    ) {
        final String code = reason.code();
        return code.endsWith(".unsafe_support")
                || code.endsWith(".support_not_observed")
                || code.endsWith(".fluid_exposed")
                || code.endsWith(".unstable_block_exposed");
    }

    private static TunnelDirection opposite(
            final TunnelDirection direction
    ) {
        return switch (direction) {
            case NORTH -> TunnelDirection.SOUTH;
            case SOUTH -> TunnelDirection.NORTH;
            case EAST -> TunnelDirection.WEST;
            case WEST -> TunnelDirection.EAST;
        };
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

    private double horizontalDistance(final CoreSkillFrame frame) {
        return Math.hypot(
                frame.position().x() - searchX,
                frame.position().z() - searchZ
        );
    }

    private void cancelChildren(final SkillContext context) {
        cancelTravel(context);
        cancelExcavation(context);
    }

    private void cancelTravel(final SkillContext context) {
        if (travel != null && travelParameters != null) {
            try {
                travel.cancel(context, travelParameters);
            } catch (RuntimeException ignored) {
                core.stop();
            }
        }
        clearTravel();
    }

    private void clearTravel() {
        travel = null;
        travelParameters = null;
    }

    private void cancelExcavation(final SkillContext context) {
        if (excavation != null && excavationParameters != null) {
            try {
                excavation.cancel(context, excavationParameters);
            } catch (RuntimeException ignored) {
                interactions.abortMining();
                core.stop();
            }
        }
        clearExcavation();
    }

    private void clearExcavation() {
        excavation = null;
        excavationParameters = null;
    }

    private SkillTickResult fail(
            final SkillContext context,
            final SkillFailure reason
    ) {
        cancelChildren(context);
        core.stop();
        failure = Objects.requireNonNull(reason, "reason");
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    private static SkillFailure failure(final String suffix) {
        return SkillFailure.of(NAME + "." + suffix);
    }

    private static LookIntent lookAt(
            final PerceptionVec3 eye,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta = target.subtract(eye);
        if (delta.lengthSquared() <= 1.0E-12) {
            return new LookIntent(0.0F, 90.0F);
        }
        return new LookIntent(
                (float) Math.toDegrees(Math.atan2(
                        -delta.x(),
                        delta.z()
                )),
                (float) Math.toDegrees(Math.atan2(
                        -delta.y(),
                        Math.hypot(delta.x(), delta.z())
                ))
        );
    }

    private static float yawOf(final PerceptionVec3 look) {
        return normalizeDegrees((float) Math.toDegrees(
                Math.atan2(-look.x(), look.z())
        ));
    }

    private static float pitchOf(final PerceptionVec3 look) {
        return (float) -Math.toDegrees(Math.atan2(
                look.y(),
                Math.hypot(look.x(), look.z())
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

    private enum Phase {
        IDLE(false),
        SELECTING(true),
        APPROACHING(true),
        ALIGNING_FLOOR(true),
        EXCAVATING(true),
        DEPTH_PROBING(true),
        ALIGNING_ENTRY_FLOOR(true),
        ENTERING_STRONGHOLD(true),
        ADJUSTING_ENTRY_DEPTH(true),
        RETREATING_ENTRY_PROBE(true),
        ADJUSTING_ENTRY_HEIGHT(true),
        VERIFYING_ENTRY(true),
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
