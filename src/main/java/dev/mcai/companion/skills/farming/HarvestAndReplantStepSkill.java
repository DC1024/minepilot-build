package dev.mcai.companion.skills.farming;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Harvests one mature, ray-visible crop and does not reach a successful safe
 * checkpoint until the same plot has been replanted through vanilla actions.
 */
public final class HarvestAndReplantStepSkill
        implements Skill<HarvestAndReplantParameters> {
    private static final String NAME = "harvest_and_replant_step";
    private static final double LOOK_EPSILON = 1.0E-12;
    private static final double AIM_ALIGNMENT_DEGREES = 0.5;
    private static final double PICKUP_ALIGNMENT_DEGREES = 5.0;
    private static final String ITEM_ENTITY_ID = "minecraft:item";
    private static final double HARVEST_PICKUP_DISTANCE = 0.75;
    private static final double HARVEST_DROP_ORIGIN_RADIUS = 3.5;
    private static final long PICKUP_DROP_MEMORY_MAXIMUM_REVISION_AGE = 24;
    /* A verified side-step may be oblique when a one-block water gap blocks
     * the dominant cardinal; it still must shorten the drop distance. */
    private static final double MINIMUM_PICKUP_DIRECTION_DOT = 0.25;
    private static final int HARVEST_PICKUP_TICKS = 240;
    private static final double PICKUP_STEP_MAXIMUM_DANGER = 0.20;
    /*
     * Semantic samples are intentionally throttled while an item settles.
     * Keep a short, transaction-local window so a stable field corridor does
     * not expire before the next first-person sample arrives; ordinary
     * navigation retains its stricter freshness rules.
     */
    private static final long PICKUP_EVIDENCE_MAXIMUM_REVISION_AGE = 40;
    private static final long PICKUP_BODY_CONTACT_MAXIMUM_REVISION_AGE = 40;
    private static final long PICKUP_SUPPORT_MAXIMUM_REVISION_AGE = 128;
    private static final int[][] CARDINAL_STEPS = {
        {1, 0},
        {-1, 0},
        {0, 1},
        {0, -1}
    };
    private static final double[][] PICKUP_SEARCH_DIRECTIONS = {
        {1.0, 0.0},
        {0.7071067811865476, 0.7071067811865476},
        {0.0, 1.0},
        {-0.7071067811865476, 0.7071067811865476},
        {-1.0, 0.0},
        {-0.7071067811865476, -0.7071067811865476},
        {0.0, -1.0},
        {0.7071067811865476, -0.7071067811865476}
    };
    /* Vanilla drops have a short pickup delay; do not walk into the plot. */
    private static final int HARVEST_PICKUP_SETTLE_TICKS = 12;
    /* Keep each fair first-person search ray long enough for the normal
     * sampler and locomotion controller to converge.  Advancing the ray on
     * every semantic frame made a root-crop drop search spin eight headings
     * before an item could enter the view cone. */
    private static final int PICKUP_SEARCH_DWELL_TICKS = 8;
    private static final int PICKUP_STEP_MAXIMUM_TICKS = 24;
    /* The server-owned player applies the same acceleration and collision
     * integration as a client.  A one-cell agricultural correction can move
     * only a few centimetres per tick at a crop/water edge; five ticks made
     * the planner abandon a valid step and start rotating its search again. */
    private static final int PICKUP_STEP_MAXIMUM_STALL_TICKS = 12;
    private static final int MAX_SUBSTRATE_AIM_ATTEMPTS = 3;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator coreActuator;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator actuator;
    private final InteractionSkillFrameSource frames;
    private final FarmingSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long startedAtGameTick = -1;
    private long harvestObservationRevision = -1;
    private long plantObservationRevision = -1;
    private long plantedAtGameTick = -1;
    /**
     * A bounded handoff target selected only from the current fair frame when
     * the model supplied a stale coordinate.  It is populated only when one
     * and exactly one mature crop remains visible, so a malformed model
     * argument can recover without guessing among multiple plants.
     */
    private ObservedBlockTarget effectiveTarget;
    private int seedsBeforePlant = -1;
    private int replantRecoveryAttempts;
    private int harvestItemsBefore = -1;
    private long harvestPickupStartedAtGameTick = -1;
    private Set<UUID> preExistingHarvestDropIds = Set.of();
    private UUID rememberedHarvestDropId;
    private PerceptionVec3 rememberedHarvestDropPoint;
    private long rememberedHarvestDropRevision = -1;
    private long retryAfterObservationRevision = -1;
    private PerceptionVec3 returnSurveyPoint;
    private String lastPickupSafety = "none";
    private GridPos authorizedReplantedPlot;
    private int pickupSearchIndex;
    private long pickupSearchObservationRevision = -1;
    private long pickupSearchLastAdvanceTick = -1;
    private String lastPlantObservation = "none";
    private PerceptionVec3 pickupInspectionPoint;
    private long pickupInspectionObservationRevision = -1;
    private PerceptionVec3 pickupStepPoint;
    private GridPos pickupStepCell;
    private int pickupStepTicks;
    private PerceptionVec3 pickupStepLastPosition;
    private int pickupStepStallTicks;
    private PerceptionVec3 plantStepPoint;
    private GridPos plantStepCell;
    private int plantStepTicks;
    private PerceptionVec3 plantStepLastPosition;
    private int plantStepStallTicks;
    private int substrateAimAttempts;
    private long substrateAimObservationRevision = -1;
    private PerceptionVec3 waterEscapeStepPoint;
    private GridPos waterEscapeStepCell;
    private boolean waterEscapeActive;
    /**
     * Water escape may issue at most one jump for the currently verified
     * non-agricultural landing.  Repeating jump every tick is both unlike a
     * player and unsafe around farmland; the next fresh navigation frame must
     * establish a new destination before another jump can be considered.
     */
    private boolean waterEscapeJumpIssued;

    public HarvestAndReplantStepSkill(
            UUID expectedPlayerId,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            InteractionSkillActuator actuator,
            InteractionSkillFrameSource frames,
            FarmingSkillPolicy policy
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.coreActuator = Objects.requireNonNull(
                coreActuator,
                "coreActuator"
        );
        this.coreFrames = Objects.requireNonNull(
                coreFrames,
                "coreFrames"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public SkillParameterParser<HarvestAndReplantParameters> parameters() {
        return FarmingSkillParameters::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            HarvestAndReplantParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        effectiveTarget = null;
        FrameValidation validation = validateFrame(parameters, -1);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        InteractionSkillFrame frame = validation.frame().orElseThrow();
        BlockResolution crop = resolveInitialCrop(frame, parameters);
        if (crop.failure().isPresent()) {
            final Optional<ObservedBlockTarget> handoff =
                    uniqueCurrentMatureTarget(frame, parameters);
            if (handoff.isEmpty()) {
                return crop.failure();
            }
            effectiveTarget = handoff.orElseThrow();
            crop = resolveInitialCrop(frame, parameters);
            if (crop.failure().isPresent()) {
                return crop.failure();
            }
        }
        if (seedCount(frame, parameters.crop()) < 1) {
            return Optional.of(failure("seed_unavailable"));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            SkillContext context,
            HarvestAndReplantParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        FrameValidation validation = validateFrame(parameters, -1);
        if (validation.failure().isPresent()
                || resolveInitialCrop(
                        validation.frame().orElseThrow(),
                        parameters
                ).failure().isPresent()) {
            throw new IllegalStateException(
                    "Farming observation binding changed"
            );
        }
        InteractionSkillFrame frame = validation.frame().orElseThrow();
        phase = Phase.READY;
        failure = null;
        boundSessionGeneration = frame.sessionGeneration();
        startedAtGameTick = context.gameTick();
        harvestObservationRevision = frame.observationRevision();
        plantObservationRevision = -1;
        plantedAtGameTick = -1;
        seedsBeforePlant = -1;
        harvestItemsBefore = itemCount(
                frame.inventory(),
                parameters.crop().harvestItemId()
        );
        harvestPickupStartedAtGameTick = -1;
        preExistingHarvestDropIds = new HashSet<>();
        rememberedHarvestDropId = null;
        rememberedHarvestDropPoint = null;
        rememberedHarvestDropRevision = -1;
        frame.visibleEntities().stream()
                .filter(entity -> ITEM_ENTITY_ID.equals(
                        entity.entityTypeId()
                ))
                .filter(entity -> parameters.crop().harvestItemId().equals(
                        entity.visibleProperties().get("itemId")
                ))
                .map(VisibleEntity::entityId)
                .forEach(preExistingHarvestDropIds::add);
        retryAfterObservationRevision = -1;
        returnSurveyPoint = coreFrames.current()
                .filter(core ->
                        expectedPlayerId.equals(core.playerId())
                                && parameters.dimension().equals(
                                core.dimension()
                        )
                )
                .map(core -> core.eyePosition().add(
                        core.lookDirection().normalized().scale(4.0)
                ))
                .orElseGet(() ->
                        postPlantSurveyPoint(parameters)
                );
        authorizedReplantedPlot = null;
        pickupSearchIndex = 0;
        pickupSearchObservationRevision = -1;
        pickupSearchLastAdvanceTick = -1;
        lastPlantObservation = "none";
        replantRecoveryAttempts = 0;
        pickupInspectionPoint = null;
        pickupInspectionObservationRevision = -1;
        pickupStepPoint = null;
        pickupStepCell = null;
        pickupStepTicks = 0;
        pickupStepLastPosition = null;
        pickupStepStallTicks = 0;
        plantStepPoint = null;
        plantStepCell = null;
        plantStepTicks = 0;
        plantStepLastPosition = null;
        plantStepStallTicks = 0;
        substrateAimAttempts = 0;
        substrateAimObservationRevision = -1;
        waterEscapeStepPoint = null;
        waterEscapeStepCell = null;
        waterEscapeActive = false;
        waterEscapeJumpIssued = false;
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            HarvestAndReplantParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase == Phase.COMPLETED) {
            return SkillTickResult.completed();
        }
        if (phase == Phase.FAILED || phase == Phase.CANCELLED
                || phase == Phase.IDLE) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        if (context.gameTick() - startedAtGameTick
                >= policy.totalTimeoutTicks()) {
            return fail(
                    "timed_out_" + phase.name().toLowerCase(
                            Locale.ROOT
                    )
            );
        }

        FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        InteractionSkillFrame frame = validation.frame().orElseThrow();
        CoreFrameValidation coreValidation = validateCoreFrame(parameters);
        if (coreValidation.failure().isPresent()) {
            return fail(coreValidation.failure().orElseThrow());
        }
        CoreSkillFrame coreFrame = coreValidation.frame().orElseThrow();
        if (phase != Phase.READY && phase != Phase.HARVESTING) {
            if (coreFrame.inWater()) {
                waterEscapeActive = true;
            } else if (waterEscapeActive
                    && coreFrame.onGround()) {
                waterEscapeActive = false;
                waterEscapeStepPoint = null;
                waterEscapeStepCell = null;
                waterEscapeJumpIssued = false;
            }
            if (waterEscapeActive) {
                return escapeWaterTowardField(
                        coreFrame,
                        parameters
                );
            }
        }
        return switch (phase) {
            case READY -> aimAndBeginHarvest(
                    coreFrame,
                    frame,
                    parameters
            );
            case HARVESTING -> continueHarvest(frame);
            case WAITING_FOR_SUBSTRATE ->
                    aimAndPlantWhenVerified(
                            coreFrame,
                            frame,
                            context,
                            parameters
                    );
            case WAITING_FOR_REPLANT ->
                    verifyReplant(
                            coreFrame,
                            frame,
                            context,
                            parameters
                    );
            case COLLECTING_HARVEST ->
                    collectHarvest(
                            coreFrame,
                            context,
                            parameters
                    );
            default -> SkillTickResult.failed(NAME + ".invalid_state");
        };
    }

    @Override
    public SkillCheckpoint checkpoint(
            SkillContext context,
            HarvestAndReplantParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"crop\":\"%s\","
                                + "\"dimension\":\"%s\","
                                + "\"x\":%d,\"y\":%d,\"z\":%d,"
                                + "\"session\":%d,"
                                + "\"pickupSafety\":\"%s\","
                                + "\"plantObservation\":\"%s\"}",
                        phase.name(),
                        parameters.crop().blockId(),
                        parameters.dimension().id(),
                        targetFor(parameters).x(),
                        targetFor(parameters).y(),
                        targetFor(parameters).z(),
                        boundSessionGeneration,
                        lastPickupSafety,
                        lastPlantObservation
                )
        );
    }

    @Override
    public void cancel(
            SkillContext context,
            HarvestAndReplantParameters parameters
    ) {
        releaseMiningIfStillBound();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            SkillContext context,
            HarvestAndReplantParameters parameters
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

    private SkillTickResult aimAndBeginHarvest(
            CoreSkillFrame coreFrame,
            InteractionSkillFrame frame,
            HarvestAndReplantParameters parameters
    ) {
        if (!coreFrame.onGround() || coreFrame.inWater()) {
            return fail("unsafe_harvest_pose");
        }
        BlockResolution crop = resolveInitialCrop(frame, parameters);
        if (crop.failure().isPresent()) {
            return fail(crop.failure().orElseThrow());
        }
        if (seedCount(frame, parameters.crop()) < 1) {
            return fail("seed_unavailable");
        }
        VisibleBlockFace face = crop.face().orElseThrow();
        AimResult aim = aimAt(
                coreFrame,
                face.hitPosition()
        );
        if (!aim.accepted()) {
            return fail("look_rejected");
        }
        if (aim.errorDegrees() > AIM_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        face = currentCrosshairCrop(parameters).orElse(face);
        if (frame.observationRevision()
                <= retryAfterObservationRevision) {
            return SkillTickResult.running(false, false);
        }
        ActionOutcome outcome = actuator.beginMining(
                interactionTarget(face)
        );
        if (outcome == ActionOutcome.COMPLETED) {
            phase = Phase.WAITING_FOR_SUBSTRATE;
            harvestObservationRevision = frame.observationRevision();
            retryAfterObservationRevision = -1;
            return SkillTickResult.running(true, false);
        }
        if (!outcome.accepted()) {
            if (outcome == ActionOutcome.TARGET_OCCLUDED) {
                retryAfterObservationRevision =
                        frame.observationRevision();
                return SkillTickResult.running(false, true);
            }
            return fail(actionFailure("harvest", outcome));
        }
        phase = Phase.HARVESTING;
        retryAfterObservationRevision = -1;
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult continueHarvest(
            InteractionSkillFrame frame
    ) {
        ActionOutcome outcome = actuator.continueMining();
        if (outcome == ActionOutcome.COMPLETED) {
            phase = Phase.WAITING_FOR_SUBSTRATE;
            harvestObservationRevision = frame.observationRevision();
            return SkillTickResult.running(true, false);
        }
        if (outcome.accepted()) {
            return SkillTickResult.running(true, false);
        }
        return fail(actionFailure("harvest", outcome));
    }

    private SkillTickResult aimAndPlantWhenVerified(
            CoreSkillFrame coreFrame,
            InteractionSkillFrame frame,
            SkillContext context,
            HarvestAndReplantParameters parameters
    ) {
        if (frame.observationRevision() <= harvestObservationRevision) {
            return SkillTickResult.running(false, false);
        }
        Optional<VisibleBlockFace> crosshair = frames.currentCrosshairBlock();
        Optional<VisibleBlockFace> substrate = visibleSubstrate(
                frame,
                parameters
        );
        lastPlantObservation = plantObservationDiagnostic(
                frame,
                parameters,
                crosshair,
                substrate
        );
        if (substrate.isEmpty()) {
            /*
                 * The mature plant's outline ray normally points above the soil.
             * Once the plant is gone, holding that old ray can pass over the
             * farmland forever. The crop coordinate is already fair,
             * model-visible evidence, so look at the expected plot surface
             * to obtain a fresh first-person observation. No use action is
                 * allowed until visibleSubstrate() verifies the actual block and
                 * its upward face.
                 */
            final Optional<SkillTickResult> approach =
                    approachSubstrate(
                            coreFrame,
                            parameters
                    );
            if (approach.isPresent()) {
                return approach.orElseThrow();
            }
            AimResult reveal = aimAt(
                    coreFrame,
                    expectedSubstrateSurface(parameters)
            );
            if (!reveal.accepted()) {
                return fail("look_rejected");
            }
            return SkillTickResult.running(true, false);
        }
        int currentSeeds = seedCount(frame, parameters.crop());
        if (currentSeeds < 1) {
            return fail("seed_unavailable_after_harvest");
        }
        /*
         * A semantic fan ray is enough to prove that the plot is visible, but
         * its hit point is not necessarily the point selected by the current
         * centre crosshair.  Sending that retained fan hit directly can make
         * the vanilla actuator reject a perfectly visible plot as
         * TARGET_OCCLUDED (especially at a one-block irrigation edge).  First
         * turn toward a conservative point inside the verified voxel, then
         * wait for a fresh centre-ray sample.  The interaction target below is
         * always taken from that fresh first-person hit, never from a stale
         * semantic ray.
         */
        Optional<VisibleBlockFace> crosshairSubstrate = crosshair.filter(
                face -> isSubstrate(face, parameters)
        );
        if (crosshairSubstrate.isEmpty()) {
            /* If the plot is already visible in the semantic fan, try a
             * bounded centre-ray correction before walking.  This handles a
             * player who is simply looking level/skyward after the harvest;
             * after three fresh observations the local stepper takes over so
             * an occluding crop or irrigation edge cannot create a look-only
             * loop. */
            if (substrate.isPresent()
                    && plantStepPoint == null
                    && frame.observationRevision()
                        != substrateAimObservationRevision
                    && substrateAimAttempts < MAX_SUBSTRATE_AIM_ATTEMPTS) {
                substrateAimObservationRevision =
                        frame.observationRevision();
                substrateAimAttempts++;
                AimResult reveal = aimAt(
                        coreFrame,
                        expectedSubstrateSurface(parameters)
                );
                if (!reveal.accepted()) {
                    return fail("look_rejected");
                }
                return SkillTickResult.running(true, false);
            }
            /*
             * A neighbouring mature plant can be visible in the semantic fan
             * while still covering the plot from the centre ray.  Prefer one
             * observed, non-destructive cardinal correction before repeatedly
             * pitching the camera at an occluder.  This is the same local
             * navigation evidence used by pickup; it never scans or guesses a
             * hidden destination.
             */
            final Optional<SkillTickResult> approach = approachSubstrate(
                    coreFrame,
                    parameters
            );
            if (approach.isPresent()) {
                return approach.orElseThrow();
            }
            substrateAimAttempts = 0;
            substrateAimObservationRevision = -1;
            AimResult reveal = aimAt(
                    coreFrame,
                    expectedSubstrateSurface(parameters)
            );
            if (!reveal.accepted()) {
                return fail("look_rejected");
            }
            return SkillTickResult.running(true, false);
        }
        VisibleBlockFace substrateFace = crosshairSubstrate.orElseThrow();
        substrateAimAttempts = 0;
        substrateAimObservationRevision = -1;
        ActionOutcome stopped = coreActuator.stop();
        if (!stopped.accepted()) {
            return fail("stop_rejected");
        }
        if (frame.observationRevision()
                <= retryAfterObservationRevision) {
            return SkillTickResult.running(false, false);
        }

        ActionHand hand;
        if (parameters.crop().seedItemId().equals(
                frame.offHand().itemId()
        )) {
            hand = ActionHand.OFF_HAND;
        } else if (parameters.crop().seedItemId().equals(
                frame.mainHand().itemId()
        )) {
            hand = ActionHand.MAIN_HAND;
        } else {
            ActionOutcome equipped = actuator.equipMainHand(
                    parameters.crop().seedItemId()
            );
            if (equipped != ActionOutcome.COMPLETED) {
                return fail(actionFailure("equip_seed", equipped));
            }
            hand = ActionHand.MAIN_HAND;
        }

        ActionOutcome planted = actuator.useOnBlock(
                hand,
                interactionTarget(substrateFace)
        );
        if (!planted.accepted()) {
            if (planted == ActionOutcome.TARGET_OCCLUDED) {
                retryAfterObservationRevision =
                        frame.observationRevision();
                return SkillTickResult.running(false, true);
            }
            return fail(actionFailure("replant", planted));
        }
        phase = Phase.WAITING_FOR_REPLANT;
        authorizedReplantedPlot = new GridPos(
                targetFor(parameters).x(),
                targetFor(parameters).y(),
                targetFor(parameters).z()
        );
        retryAfterObservationRevision = -1;
        plantObservationRevision = frame.observationRevision();
        plantedAtGameTick = context.gameTick();
        seedsBeforePlant = currentSeeds;
        replantRecoveryAttempts = 0;
        plantStepPoint = null;
        plantStepCell = null;
        plantStepTicks = 0;
        plantStepLastPosition = null;
        plantStepStallTicks = 0;
        substrateAimAttempts = 0;
        substrateAimObservationRevision = -1;
        return SkillTickResult.running(true, false);
    }

    /**
     * A neighbouring mature crop can occlude the target substrate from the
     * current standing cell.  Walk to the already-authorized target cell (or
     * another observed safe field cell) before retrying the vanilla crosshair
     * sample.  No block is considered usable unless the same fair navigation
     * evidence and transaction-local authorization pass as for pickup.
     */
    private Optional<SkillTickResult> approachSubstrate(
            final CoreSkillFrame frame,
            final HarvestAndReplantParameters parameters
    ) {
        final GridPos targetCell = new GridPos(
                targetFor(parameters).x(),
                targetFor(parameters).y(),
                targetFor(parameters).z()
        );
        final GridPos feet = new GridPos(
                floor(frame.position().x()),
                (int) Math.ceil(frame.position().y() - 1.0E-6),
                floor(frame.position().z())
        );
        if (feet.equals(targetCell)) {
            plantStepPoint = null;
            plantStepCell = null;
            return Optional.empty();
        }
        if (plantStepPoint != null && plantStepCell != null) {
            if (plantStepLastPosition != null
                    && frame.position().subtract(
                            plantStepLastPosition
                    ).length() < 0.03) {
                plantStepStallTicks++;
            } else {
                plantStepStallTicks = 0;
                plantStepLastPosition = frame.position();
            }
            if (feet.equals(plantStepCell)
                    || plantStepTicks++ >= PICKUP_STEP_MAXIMUM_TICKS
                    || plantStepStallTicks
                        >= PICKUP_STEP_MAXIMUM_STALL_TICKS) {
                plantStepPoint = null;
                plantStepCell = null;
                plantStepTicks = 0;
                plantStepLastPosition = null;
                plantStepStallTicks = 0;
            } else {
                return Optional.of(drivePlantStep(frame, plantStepPoint));
            }
        }
        final Optional<GridPos> routeStep = nextPlantRouteStep(
                frame,
                feet,
                targetCell,
                parameters.crop(),
                parameters.authorizedPickupCells()
        );
        if (routeStep.isEmpty()) {
            return Optional.empty();
        }
        final GridPos next = routeStep.orElseThrow();
        final int stepX = Integer.compare(next.x(), feet.x());
        final int stepZ = Integer.compare(next.z(), feet.z());
        plantStepPoint = cardinalStepPoint(
                frame,
                next,
                new int[] {stepX, stepZ}
        );
        plantStepCell = new GridPos(
                next.x(),
                feet.y(),
                next.z()
        );
        plantStepTicks = 0;
        plantStepLastPosition = frame.position();
        plantStepStallTicks = 0;
        return Optional.of(drivePlantStep(frame, plantStepPoint));
    }

    /**
     * Finds the first cardinal step of a short route through the current
     * fair navigation snapshot.  This is intentionally not a world lookup:
     * every expanded node still passes the same transaction-local safety and
     * authorization predicate used by the ordinary stepper.  In particular,
     * an unseen crop or liquid cannot become a route node merely because a
     * graph search wants to reach the target.
     */
    private Optional<GridPos> nextPlantRouteStep(
            final CoreSkillFrame frame,
            final GridPos start,
            final GridPos target,
            final CropKind crop,
            final Set<GridPos> authorizedPickupCells
    ) {
        if (start.equals(target)) {
            return Optional.empty();
        }
        final int radius = 8;
        final int minimumX = Math.min(start.x(), target.x()) - radius;
        final int maximumX = Math.max(start.x(), target.x()) + radius;
        final int minimumZ = Math.min(start.z(), target.z()) - radius;
        final int maximumZ = Math.max(start.z(), target.z()) + radius;
        final Deque<GridPos> frontier = new ArrayDeque<>();
        final Set<GridPos> visited = new HashSet<>();
        final Map<GridPos, GridPos> previous = new HashMap<>();
        frontier.add(start);
        visited.add(start);
        int expanded = 0;
        while (!frontier.isEmpty() && expanded++ < 192) {
            final GridPos current = frontier.removeFirst();
            for (int[] cardinal : CARDINAL_STEPS) {
                final GridPos next = current.offset(
                        cardinal[0],
                        0,
                        cardinal[1]
                );
                if (next.x() < minimumX || next.x() > maximumX
                        || next.z() < minimumZ || next.z() > maximumZ
                        || !visited.add(next)) {
                    continue;
                }
                if (!safeFieldDestination(
                        frame,
                        next,
                        crop,
                        authorizedReplantedPlot,
                        authorizedPickupCells
                )) {
                    continue;
                }
                previous.put(next, current);
                if (next.equals(target)) {
                    GridPos first = next;
                    GridPos parent = previous.get(first);
                    while (parent != null && !parent.equals(start)) {
                        first = parent;
                        parent = previous.get(parent);
                    }
                    lastPickupSafety = "plantRoute;rev="
                            + frame.navigation().revision()
                            + ";expanded=" + expanded
                            + ";first=" + first.x() + "/" + first.y()
                            + "/" + first.z()
                            + ";target=" + target.x() + "/" + target.y()
                            + "/" + target.z();
                    return Optional.of(first);
                }
                frontier.addLast(next);
            }
        }
        lastPickupSafety = "plantRoute=none;rev="
                + frame.navigation().revision()
                + ";expanded=" + expanded
                + ";start=" + start.x() + "/" + start.y() + "/" + start.z()
                + ";target=" + target.x() + "/" + target.y() + "/" + target.z();
        return Optional.empty();
    }

    private SkillTickResult drivePlantStep(
            final CoreSkillFrame frame,
            final PerceptionVec3 stepPoint
    ) {
        final AimResult aim = aimForMovement(frame, stepPoint);
        if (!aim.accepted()) {
            return fail("look_rejected");
        }
        if (aim.errorDegrees() > PICKUP_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        final ActionOutcome moved = coreActuator.move(
                /* Sneak is the vanilla no-trample input for this retained
                 * field support.  The larger stall window below prevents a
                 * slow edge correction from being mistaken for a deadlock. */
                new MovementIntent(
                        0.80,
                        0.0,
                        false,
                        true
                )
        );
        if (!moved.accepted()) {
            return fail(actionFailure("plant_move", moved));
        }
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyReplant(
            CoreSkillFrame coreFrame,
            InteractionSkillFrame frame,
            SkillContext context,
            HarvestAndReplantParameters parameters
    ) {
        if (frame.observationRevision() <= plantObservationRevision) {
            return waitForReplantOrFail(context);
        }
        boolean visibleNewPlant = frame.visibleBlockFaces().stream()
                .filter(face -> sameBlock(face, targetFor(parameters)))
                .anyMatch(parameters.crop()::isNewPlant);
        Optional<VisibleBlockFace> crosshair = frames.currentCrosshairBlock();
        boolean crosshairNewPlant = crosshair
                .filter(face -> sameBlock(face, targetFor(parameters)))
                .filter(parameters.crop()::isNewPlant)
                .isPresent();
        boolean seedConsumed =
                seedCount(frame, parameters.crop()) < seedsBeforePlant;
        if (!visibleNewPlant && !crosshairNewPlant
                && seedConsumed
                && replantRecoveryAttempts < 2
                && crosshair.filter(face -> isSubstrate(
                        face,
                        parameters
                )).isPresent()) {
            /* A vanilla use packet can consume the seed while the target
             * farmland remains under the crosshair (for example when an
             * adjacent crop occluded the first interaction). Retry only on
             * that exact first-person substrate hit, with a small bound; this
             * repairs a genuine no-op without ever targeting an unseen block. */
            final int currentSeeds = seedCount(
                    frame,
                    parameters.crop()
            );
            if (currentSeeds < 1) {
                return waitForReplantOrFail(context);
            }
            final ActionHand hand;
            if (parameters.crop().seedItemId().equals(
                    frame.offHand().itemId()
            )) {
                hand = ActionHand.OFF_HAND;
            } else if (parameters.crop().seedItemId().equals(
                    frame.mainHand().itemId()
            )) {
                hand = ActionHand.MAIN_HAND;
            } else {
                ActionOutcome equipped = actuator.equipMainHand(
                        parameters.crop().seedItemId()
                );
                if (equipped != ActionOutcome.COMPLETED) {
                    return fail(actionFailure("equip_seed_retry", equipped));
                }
                hand = ActionHand.MAIN_HAND;
            }
            ActionOutcome retry = actuator.useOnBlock(
                    hand,
                    interactionTarget(crosshair.orElseThrow())
            );
            if (!retry.accepted()) {
                if (retry == ActionOutcome.TARGET_OCCLUDED) {
                    retryAfterObservationRevision =
                            frame.observationRevision();
                    return SkillTickResult.running(false, true);
                }
                return fail(actionFailure("replant_retry", retry));
            }
            replantRecoveryAttempts++;
            seedsBeforePlant = currentSeeds;
            plantObservationRevision = frame.observationRevision();
            plantedAtGameTick = context.gameTick();
            return SkillTickResult.running(true, false);
        }
        if (visibleNewPlant || crosshairNewPlant) {
            if (!harvestCollected(coreFrame, parameters.crop())) {
                harvestPickupStartedAtGameTick = context.gameTick();
                phase = Phase.COLLECTING_HARVEST;
                return SkillTickResult.running(true, false);
            }
            /*
             * Restore a natural field-level view before completing. Runtime
             * quiescence intentionally freezes the last applied look when an
             * atomic skill ends; completing while aimed at the soil would
             * deprive the next planner turn of nearby mature crops.
             */
            AimResult surveyAim = aimAt(
                    coreFrame,
                    Objects.requireNonNull(returnSurveyPoint)
            );
            if (!surveyAim.accepted()) {
                return fail("look_rejected");
            }
            if (surveyAim.errorDegrees()
                    > AIM_ALIGNMENT_DEGREES) {
                return waitForReplantOrFail(context);
            }
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        return waitForReplantOrFail(context);
    }

    /**
     * A player normally takes a short step over a crop immediately after
     * harvesting. Do the same inside this atomic operation instead of paying
     * for another model turn while a vanilla item entity is aging on the
     * ground. The destination is the exact crop coordinate that was already
     * authorized by a retained first-person observation; no entity scan or
     * hidden world query is involved.
     */
    private SkillTickResult collectHarvest(
            CoreSkillFrame coreFrame,
            SkillContext context,
            HarvestAndReplantParameters parameters
    ) {
        if (harvestCollected(coreFrame, parameters.crop())) {
            ActionOutcome stopped = coreActuator.stop();
            if (!stopped.accepted()) {
                return fail("pickup_stop_rejected");
            }
            AimResult surveyAim = aimAt(
                    coreFrame,
                    Objects.requireNonNull(returnSurveyPoint)
            );
            if (!surveyAim.accepted()) {
                return fail("look_rejected");
            }
            if (surveyAim.errorDegrees()
                    > AIM_ALIGNMENT_DEGREES) {
                return SkillTickResult.running(true, false);
            }
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (context.gameTick() - harvestPickupStartedAtGameTick
                >= HARVEST_PICKUP_TICKS) {
            return fail("harvest_drop_not_collected");
        }
        if (context.gameTick() - harvestPickupStartedAtGameTick
                < HARVEST_PICKUP_SETTLE_TICKS) {
            final ActionOutcome stopped = coreActuator.stop();
            return stopped.accepted()
                    ? SkillTickResult.running(false, true)
                    : fail("pickup_stop_rejected");
        }

        if (pickupStepPoint != null && pickupStepCell != null) {
            final GridPos feet = new GridPos(
                    floor(coreFrame.position().x()),
                    (int) Math.ceil(coreFrame.position().y() - 1.0E-6),
                    floor(coreFrame.position().z())
            );
            if (pickupStepLastPosition != null
                    && coreFrame.position().subtract(
                            pickupStepLastPosition
                    ).length() < 0.03) {
                pickupStepStallTicks++;
            } else {
                pickupStepStallTicks = 0;
                pickupStepLastPosition = coreFrame.position();
            }
            if (feet.equals(pickupStepCell)
                    || pickupStepTicks++ >= PICKUP_STEP_MAXIMUM_TICKS
                    || pickupStepStallTicks
                        >= PICKUP_STEP_MAXIMUM_STALL_TICKS) {
                pickupStepPoint = null;
                pickupStepCell = null;
                pickupStepTicks = 0;
                pickupStepLastPosition = null;
                pickupStepStallTicks = 0;
            } else {
                return drivePickupStep(
                        coreFrame,
                        pickupStepPoint,
                        parameters.crop(),
                        parameters.authorizedPickupCells()
                );
            }
        }

        /* Hold a support inspection until a newer fair frame arrives.  If a
         * crop corridor became safe on one frame but the next tick immediately
         * switched to a downward inspection ray, the headless body oscillated
         * its pitch and never renewed forward input.  A real player would keep
         * looking at the same cell until the view refreshed. */
        if (pickupInspectionPoint != null
                && coreFrame.observationRevision()
                        <= pickupInspectionObservationRevision) {
            return holdPickupInspection(coreFrame);
        }
        pickupInspectionPoint = null;
        pickupInspectionObservationRevision = -1;

        final Optional<VisibleEntity> visibleDrop = visibleHarvestDrop(
                coreFrame,
                parameters
        );
        final PerceptionVec3 pickupPoint = visibleDrop
                .map(VisibleEntity::position).orElseGet(() ->
                new PerceptionVec3(
                    targetFor(parameters).x() + 0.5,
                    coreFrame.position().y(),
                    targetFor(parameters).z() + 0.5
                )
        );
        /*
         * Locomotion is horizontal. Preserve the observed drop's X/Z while
         * aiming at eye height so pitch cannot suppress a valid forward
         * movement intent.
         */
        final PerceptionVec3 target = new PerceptionVec3(
                pickupPoint.x(),
                coreFrame.eyePosition().y(),
                pickupPoint.z()
        );
        final double dx = target.x() - coreFrame.position().x();
        final double dz = target.z() - coreFrame.position().z();
        final double horizontalDistance = Math.hypot(dx, dz);
        if (horizontalDistance <= HARVEST_PICKUP_DISTANCE) {
            if (visibleDrop.isEmpty()) {
                return searchForHarvestDrop(
                        coreFrame,
                        context.gameTick(),
                        parameters
                );
            }
            ActionOutcome stopped = coreActuator.stop();
            return stopped.accepted()
                    ? SkillTickResult.running(false, false)
                    : fail("pickup_stop_rejected");
        }

        /*
         * A vanilla crop drop is allowed to scatter into the block behind
         * the replanted plot.  The old cardinal stepper deliberately stopped
         * at the crop centre, which left a visible drop roughly 1.5--2
         * blocks away and then spent the rest of the pickup window rotating
         * through its search rays.  When the ordinary perception sampler has
         * actually supplied a visible, matching item entity, use that fair
         * position as the short approach target.  The approach remains
         * bounded to the just-replanted plot and refuses water/airborne
         * movement; it never scans entities or edits inventory directly.
         */
        if (visibleDrop.isPresent()
                && canUseObservedDropApproach(
                        coreFrame,
                        parameters,
                        visibleDrop.orElseThrow()
                )) {
            return approachVisibleHarvestDrop(
                    coreFrame,
                    visibleDrop.orElseThrow()
            );
        }

        final Optional<PerceptionVec3> safeStep = safestFieldStep(
                coreFrame,
                target,
                parameters.crop(),
                /* A visible drop is an observed point target.  Allow a
                 * verified sideways detour when its dominant cardinal is
                 * blocked by irrigation, instead of selecting a stationary
                 * or backwards-only fallback that burns the pickup window. */
                visibleDrop.isPresent(),
                parameters.authorizedPickupCells()
        );
        if (safeStep.isEmpty()) {
            if (canUseBoundedPickupApproach(
                    coreFrame,
                    parameters,
                    visibleDrop.isPresent()
            )) {
                return boundedVisiblePickupApproach(
                        coreFrame,
                        parameters,
                        visibleDrop.isPresent()
                );
            }
            return inspectPickupCorridor(
                    coreFrame,
                    target
            );
        }
        pickupStepPoint = safeStep.orElseThrow();
        pickupStepCell = new GridPos(
                floor(pickupStepPoint.x()),
                (int) Math.ceil(coreFrame.position().y() - 1.0E-6),
                floor(pickupStepPoint.z())
        );
        pickupStepTicks = 0;
        pickupStepLastPosition = coreFrame.position();
        pickupStepStallTicks = 0;
        return drivePickupStep(
                coreFrame,
                pickupStepPoint,
                parameters.crop(),
                parameters.authorizedPickupCells()
        );
    }

    /**
     * Move toward a currently visible matching item instead of guessing from
     * the crop centre.  The caller has already proved that the player is on
     * solid ground, within the transaction-local pickup corridor, and that
     * the entity is the crop's harvest item.  Keeping the target at eye
     * height preserves the normal horizontal locomotion contract.
     */
    private SkillTickResult approachVisibleHarvestDrop(
            final CoreSkillFrame frame,
            final VisibleEntity drop
    ) {
        final PerceptionVec3 target = new PerceptionVec3(
                drop.position().x(),
                frame.eyePosition().y(),
                drop.position().z()
        );
        final AimResult aim = aimForMovement(frame, target);
        if (!aim.accepted()) {
            return fail("look_rejected");
        }
        if (aim.errorDegrees() > PICKUP_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        final ActionOutcome moved = coreActuator.move(
                new MovementIntent(
                        0.80,
                        0.0,
                        false,
                        /* The observed drop may be behind the just-replanted
                         * crop.  Keep the entire direct approach sneaking so
                         * the normal server-side farmland trampling rule can
                         * never turn that authorized support into dirt. */
                        true
                )
        );
        return moved.accepted()
                ? SkillTickResult.running(true, false)
                : fail(actionFailure("pickup_visible_drop_move", moved));
    }

    private SkillTickResult drivePickupStep(
            final CoreSkillFrame frame,
            final PerceptionVec3 stepPoint,
            final CropKind crop,
            final java.util.Set<GridPos> authorizedPickupCells
    ) {
        final AimResult aim = aimForMovement(frame, stepPoint);
        if (!aim.accepted()) {
            return fail("look_rejected");
        }
        if (aim.errorDegrees() > PICKUP_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        final ActionOutcome moved = coreActuator.move(
                        new MovementIntent(
                                0.80,
                                0.0,
                                false,
                                true
                        )
                );
        if (!moved.accepted()) {
            return fail(actionFailure("pickup_move", moved));
        }
        return SkillTickResult.running(true, false);
    }

    /**
     * Vanilla block drops can scatter behind the player. Once the crop centre
     * has been reached without an inventory increment, glance around the
     * harvest origin at ground level. The direction advances only after a new
     * fair semantic frame, so this is a bounded first-person search rather
     * than a hidden entity scan or a per-tick spin.
     */
    private SkillTickResult searchForHarvestDrop(
            final CoreSkillFrame frame,
            final long gameTick,
            final HarvestAndReplantParameters parameters
    ) {
        if (pickupSearchObservationRevision < 0) {
            pickupSearchLastAdvanceTick = gameTick;
        } else if (gameTick - pickupSearchLastAdvanceTick
                >= PICKUP_SEARCH_DWELL_TICKS) {
            pickupSearchIndex = (pickupSearchIndex + 1)
                    % PICKUP_SEARCH_DIRECTIONS.length;
            pickupSearchLastAdvanceTick = gameTick;
        }
        pickupSearchObservationRevision = Math.max(
                pickupSearchObservationRevision,
                frame.observationRevision()
        );
        final double[] direction = PICKUP_SEARCH_DIRECTIONS[
                pickupSearchIndex
        ];
        final PerceptionVec3 searchPoint = new PerceptionVec3(
                targetFor(parameters).x() + 0.5
                        + direction[0] * 2.75,
                /* Keep the scan horizontal. A low target makes the headless
                 * player pitch down, then the next movement tick pitches it
                 * back up; that oscillation consumes the pickup window while
                 * the drop remains within ordinary reach. */
                frame.eyePosition().y(),
                targetFor(parameters).z() + 0.5
                        + direction[1] * 2.75
        );
        lastPickupSafety = "search=" + pickupSearchIndex
                + ";rev=" + frame.observationRevision();
        final Optional<PerceptionVec3> safeStep = safestFieldStep(
                frame,
                searchPoint,
                parameters.crop(),
                true,
                parameters.authorizedPickupCells()
        );
        if (safeStep.isPresent()) {
            pickupStepPoint = safeStep.orElseThrow();
            pickupStepCell = new GridPos(
                    floor(pickupStepPoint.x()),
                    (int) Math.ceil(frame.position().y() - 1.0E-6),
                    floor(pickupStepPoint.z())
            );
            pickupStepTicks = 0;
            pickupStepLastPosition = frame.position();
            pickupStepStallTicks = 0;
            return drivePickupStep(
                    frame,
                    pickupStepPoint,
                    parameters.crop(),
                    parameters.authorizedPickupCells()
            );
        }
        final AimResult aim = aimAt(frame, searchPoint);
        if (!aim.accepted()) {
            return fail("look_rejected");
        }
        return SkillTickResult.running(true, false);
    }

    /**
     * An item beside the current safe cell can be reached without entering
     * the crop cell at all. Stop at vanilla pickup range and never use this
     * fallback when airborne, swimming, or farther than the neighbouring
     * plot that this same atomic transaction just replanted. Current visual
     * evidence is preferred; the adjacent authorized plot is the bounded
     * fallback when intervening foliage occludes its new seedling.
     */
    private SkillTickResult boundedVisiblePickupApproach(
            final CoreSkillFrame frame,
            final HarvestAndReplantParameters parameters,
            final boolean visibleDrop
    ) {
        final PerceptionVec3 target = new PerceptionVec3(
                targetFor(parameters).x() + 0.5,
                frame.eyePosition().y(),
                targetFor(parameters).z() + 0.5
        );
        final double plotHorizontalDistance = Math.hypot(
                target.x() - frame.position().x(),
                target.z() - frame.position().z()
        );
        final boolean targetVisible = visibleDrop
                || frame.visibleBlockFaces().stream()
                .anyMatch(face ->
                        sameBlock(face, targetFor(parameters))
                                && parameters.crop().isPlant(face)
                );
        final int feetY = (int) Math.ceil(
                frame.position().y() - 1.0E-6
        );
        final boolean adjacentReplantedPlot =
                targetFor(parameters).y() == feetY
                && Math.abs(targetFor(parameters).x()
                        - floor(frame.position().x())) <= 1
                && Math.abs(targetFor(parameters).z()
                        - floor(frame.position().z())) <= 1;
        if (!frame.onGround()
                || frame.inWater()
                || plotHorizontalDistance > 1.75
                || !targetVisible && !adjacentReplantedPlot) {
            final ActionOutcome stopped = coreActuator.stop();
            return stopped.accepted()
                    ? SkillTickResult.running(false, true)
                    : fail("pickup_stop_rejected");
        }
        final AimResult aim = aimForMovement(frame, target);
        if (!aim.accepted()) {
            return fail("look_rejected");
        }
        if (aim.errorDegrees() > PICKUP_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        final ActionOutcome moved = coreActuator.move(
                new MovementIntent(
                        0.25,
                        0.0,
                        false,
                        true
                )
        );
        return moved.accepted()
                ? SkillTickResult.running(true, false)
                : fail(actionFailure("pickup_move", moved));
    }

    /**
     * A currently visible drop may be several blocks behind the replanted
     * cell.  Once the ordinary first-person navigation sample proves that the
     * next cardinal cell is safe and points toward that same observed item,
     * keep walking toward the item instead of repeatedly stopping at the
     * one-cell waypoint.  The check is repeated on every semantic frame; no
     * hidden entity query or retained route is used.
     */
    private boolean canUseObservedDropApproach(
            final CoreSkillFrame frame,
            final HarvestAndReplantParameters parameters,
            final VisibleEntity drop
    ) {
        if (!frame.onGround() || frame.inWater()) {
            return false;
        }
        final PerceptionVec3 target = drop.position();
        final double dx = target.x() - frame.position().x();
        final double dz = target.z() - frame.position().z();
        final double distance = Math.hypot(dx, dz);
        if (distance > HARVEST_DROP_ORIGIN_RADIUS + 0.75
                || distance <= HARVEST_PICKUP_DISTANCE) {
            return false;
        }
        /* The observed item position is a point, but the direct input path
         * between the player and that point can cross an irrigation cell.
         * The first cardinal waypoint may be safe while a later direct tick
         * walks into water and forces a destructive jump-out recovery.  Keep
         * this fast path only when every sampled body/support voxel along the
         * bounded segment is non-liquid; otherwise the cardinal fair stepper
         * must route around the water from fresh evidence. */
        if (observedLiquidOnDirectCorridor(frame, target)) {
            return false;
        }
        final Optional<PerceptionVec3> safeStep = safestFieldStep(
                frame,
                new PerceptionVec3(
                        target.x(),
                        frame.eyePosition().y(),
                        target.z()
                ),
                parameters.crop(),
                false,
                parameters.authorizedPickupCells()
        );
        if (safeStep.isEmpty()) {
            return false;
        }
        final PerceptionVec3 step = safeStep.orElseThrow();
        final double stepX = step.x() - frame.position().x();
        final double stepZ = step.z() - frame.position().z();
        /* Do not use this straight-line branch when the verified step is a
         * sideways fallback around an irrigation cell. The normal cardinal
         * stepper must own that detour. */
        return stepX * dx + stepZ * dz > 0.0;
    }

    private static boolean observedLiquidOnDirectCorridor(
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        final double dx = target.x() - frame.position().x();
        final double dz = target.z() - frame.position().z();
        final int steps = Math.max(
                1,
                (int) Math.ceil(Math.hypot(dx, dz) * 2.0)
        );
        final int feetY = (int) Math.ceil(
                frame.position().y() - 1.0E-6
        );
        for (int index = 1; index <= steps; index++) {
            final double fraction = index / (double) steps;
            final GridPos cell = new GridPos(
                    floor(frame.position().x() + dx * fraction),
                    feetY,
                    floor(frame.position().z() + dz * fraction)
            );
            /* A direct item approach is the only farming branch allowed to
             * issue a long, continuous input.  If any body/support voxel in
             * that corridor is absent from the current first-person map, the
             * player cannot distinguish solid ground from an unseen water
             * source.  Refuse the shortcut and let the bounded cardinal
             * stepper obtain a fresh observation instead of falling into an
             * unobserved irrigation cell. */
            final Optional<ObservedVoxel> body = frame.navigation()
                    .voxelAt(cell);
            final Optional<ObservedVoxel> support = frame.navigation()
                    .voxelAt(cell.below());
            if (body.isEmpty()
                    || support.isEmpty()
                    || body.orElseThrow().kind().isLiquid()
                    || support.orElseThrow().kind().isLiquid()
                    || frame.visibleBlockFaces().stream().anyMatch(face ->
                            "minecraft:water".equals(face.blockTypeId())
                                    && (sameGrid(face, cell)
                                        || sameGrid(face, cell.below())))
            ) {
                return true;
            }
        }
        return false;
    }

    private boolean canUseBoundedPickupApproach(
            final CoreSkillFrame frame,
            final HarvestAndReplantParameters parameters,
            final boolean visibleDrop
    ) {
        final PerceptionVec3 target = new PerceptionVec3(
                targetFor(parameters).x() + 0.5,
                frame.eyePosition().y(),
                targetFor(parameters).z() + 0.5
        );
        final boolean targetVisible = visibleDrop
                || frame.visibleBlockFaces().stream()
                .anyMatch(face ->
                        sameBlock(face, targetFor(parameters))
                                && parameters.crop().isPlant(face)
                );
        final int feetY = (int) Math.ceil(
                frame.position().y() - 1.0E-6
        );
        final boolean adjacentReplantedPlot =
                targetFor(parameters).y() == feetY
                && Math.abs(targetFor(parameters).x()
                        - floor(frame.position().x())) <= 1
                && Math.abs(targetFor(parameters).z()
                        - floor(frame.position().z())) <= 1;
        return frame.onGround()
                && !frame.inWater()
                && Math.hypot(
                        target.x() - frame.position().x(),
                        target.z() - frame.position().z()
                ) <= 1.75
                && (targetVisible || adjacentReplantedPlot)
                && !observedLiquidOnDirectCorridor(frame, target);
    }

    /**
     * Continues an already-started harvest transaction after an accidental
     * step into irrigation water. It uses only fresh first-person navigation
     * evidence and never abandons a broken-but-unplanted plot.
     */
    private SkillTickResult escapeWaterTowardField(
            final CoreSkillFrame frame,
            final HarvestAndReplantParameters parameters
    ) {
        if (waterEscapeStepPoint != null && waterEscapeStepCell != null) {
            final GridPos feet = new GridPos(
                    floor(frame.position().x()),
                    (int) Math.ceil(frame.position().y() - 1.0E-6),
                    floor(frame.position().z())
            );
            if (feet.equals(waterEscapeStepCell)
                    || !safeFieldDestination(
                            frame,
                            waterEscapeStepCell,
                            parameters.crop(),
                            authorizedReplantedPlot,
                        parameters.authorizedPickupCells()
                    )) {
                waterEscapeStepPoint = null;
                waterEscapeStepCell = null;
                waterEscapeJumpIssued = false;
            } else {
                return driveWaterEscapeStep(
                        frame,
                        waterEscapeStepPoint,
                        parameters.crop(),
                        parameters.authorizedPickupCells()
                );
            }
        }
        final PerceptionVec3 desired = new PerceptionVec3(
                targetFor(parameters).x() + 0.5,
                frame.eyePosition().y(),
                targetFor(parameters).z() + 0.5
        );
        final Optional<PerceptionVec3> safeStep = safestWaterEscapeStep(
                frame,
                desired,
                parameters
        );
        if (safeStep.isEmpty()) {
            final ActionOutcome stopped = coreActuator.stop();
            /*
             * No verified support means that a jump would be blind.  In a
             * compact irrigated plot that can land on farmland and convert
             * it to dirt, so hold position and request a fresh first-person
             * observation instead.  The bounded skill timeout is the
             * liveness guard if the water pocket has no legal visible exit;
             * it is preferable to a destructive guess.
             */
            return stopped.accepted()
                    ? SkillTickResult.running(false, true)
                    : fail("water_escape_rejected");
        }
        waterEscapeStepPoint = safeStep.orElseThrow();
        waterEscapeStepCell = new GridPos(
                floor(waterEscapeStepPoint.x()),
                (int) Math.ceil(frame.position().y() - 1.0E-6),
                floor(waterEscapeStepPoint.z())
        );
        waterEscapeJumpIssued = false;
        return driveWaterEscapeStep(
                frame,
                waterEscapeStepPoint,
                parameters.crop(),
                parameters.authorizedPickupCells()
        );
    }

    /**
     * Prefer a verified non-field landing when leaving irrigation water. A
     * field-cell step may be perfectly walkable but jumping onto it can
     * trample farmland. The candidate list is still derived solely from the
     * current first-person navigation snapshot; no level lookup or arbitrary
     * route is introduced.
     */
    private Optional<PerceptionVec3> safestWaterEscapeStep(
            final CoreSkillFrame frame,
            final PerceptionVec3 desired,
            final HarvestAndReplantParameters parameters
    ) {
        final PerceptionVec3 current = frame.position();
        final List<PerceptionVec3> targets = List.of(
                desired,
                new PerceptionVec3(
                        current.x() + 3.0,
                        frame.eyePosition().y(),
                        current.z()
                ),
                new PerceptionVec3(
                        current.x() - 3.0,
                        frame.eyePosition().y(),
                        current.z()
                ),
                new PerceptionVec3(
                        current.x(),
                        frame.eyePosition().y(),
                        current.z() + 3.0
                ),
                new PerceptionVec3(
                        current.x(),
                        frame.eyePosition().y(),
                        current.z() - 3.0
                )
        );
        Optional<PerceptionVec3> firstSafe = Optional.empty();
        int safeCandidates = 0;
        final List<String> rejected = new ArrayList<>();
        for (PerceptionVec3 target : targets) {
            Optional<PerceptionVec3> candidate = safestFieldStep(
                    frame,
                    target,
                    parameters.crop(),
                    true,
                    parameters.authorizedPickupCells()
            );
            if (candidate.isEmpty()) {
                rejected.add(
                        floor(target.x()) + "/" + floor(target.z())
                                + "=none"
                );
                continue;
            }
            safeCandidates++;
            if (firstSafe.isEmpty()) {
                firstSafe = candidate;
            }
            GridPos destination = waterStepCell(
                    frame,
                    candidate.orElseThrow()
            );
            final boolean agricultural = waterLandingIsAgricultural(
                    frame,
                    destination,
                    parameters.crop(),
                    parameters.authorizedPickupCells()
            );
            if (!agricultural) {
                lastPickupSafety = "water;rev="
                        + frame.navigation().revision()
                        + ";step=" + destination.x() + "/"
                        + destination.y() + "/" + destination.z()
                        + ";agri=false;safe=" + safeCandidates
                        + ";rejected=" + String.join(",", rejected);
                return candidate;
            }
        }
        lastPickupSafety = "water;rev=" + frame.navigation().revision()
                + ";safe=" + safeCandidates
                + ";step=" + (firstSafe.isPresent()
                        ? waterStepCell(frame, firstSafe.orElseThrow()).x()
                            + "/"
                            + waterStepCell(frame, firstSafe.orElseThrow()).y()
                            + "/"
                            + waterStepCell(frame, firstSafe.orElseThrow()).z()
                        : "none")
                + ";agri=" + firstSafe.map(candidate ->
                        waterLandingIsAgricultural(
                                frame,
                                waterStepCell(frame, candidate),
                                parameters.crop(),
                                parameters.authorizedPickupCells()
                        )
                ).orElse(false)
                + ";rejected=" + String.join(",", rejected);
        /* If every observed exit is an agricultural cell, keep the first
         * verified step but suppress its jump below.  This can wait for a
         * fresh side corridor rather than destroying a crop. */
        return firstSafe;
    }

    private static GridPos waterStepCell(
            final CoreSkillFrame frame,
            final PerceptionVec3 stepPoint
    ) {
        return new GridPos(
                floor(stepPoint.x()),
                (int) Math.ceil(frame.position().y() - 1.0E-6),
                floor(stepPoint.z())
        );
    }

    private SkillTickResult driveWaterEscapeStep(
            final CoreSkillFrame frame,
            final PerceptionVec3 stepPoint,
            final CropKind crop,
            final java.util.Set<GridPos> authorizedPickupCells
    ) {
        final AimResult aim = aimForMovement(frame, stepPoint);
        if (!aim.accepted()) {
            return fail("look_rejected");
        }
        if (aim.errorDegrees() > AIM_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        final boolean agriculturalLanding = waterLandingIsAgricultural(
                frame,
                waterEscapeStepCell,
                crop,
                authorizedPickupCells
        );
        lastPickupSafety = lastPickupSafety
                + ";drive=" + waterEscapeStepCell.x() + "/"
                + waterEscapeStepCell.y() + "/"
                + waterEscapeStepCell.z()
                + ";agri=" + agriculturalLanding
                + ";inWater=" + frame.inWater()
                + ";jumped=" + waterEscapeJumpIssued;
        final ActionOutcome moved = coreActuator.move(
                /* Keep the no-trample key held for an agricultural landing
                 * even on the jump tick.  A compact irrigated plot can have
                 * farmland on every adjacent exit; releasing sneak for that
                 * final horizontal movement lets vanilla turn the landing
                 * block into dirt.  The queued jump supplies the upward
                 * water escape; the local vanilla input path still owns the
                 * resulting swim/step physics. */
                new MovementIntent(
                        0.80,
                        0.0,
                        false,
                        agriculturalLanding
                )
        );
        /*
         * Falling onto farmland is a normal vanilla interaction: it can
         * trample the just-replanted crop and turn its support into dirt.
         * Water escape may still jump into an ordinary observed solid cell,
         * but never into this transaction's crop/work corridor or a visible
         * crop/substrate cell.  Walking out of a one-block irrigation edge
         * is the fair, non-destructive fallback there.
         */
        /* Farming is a flat, atomic field transaction.  Never jump from its
         * water escape: a jump is the one vanilla input that can convert a
         * verified farmland support to dirt and invalidate the replant.  If
         * walking/swimming cannot find a fresh safe exit, the bounded skill
         * timeout fails safely instead of guessing. */
        if (!moved.accepted()) {
            return fail("water_escape_rejected");
        }
        if (frame.inWater() && !waterEscapeJumpIssued) {
            /* A one-block irrigation cell is often surrounded entirely by
             * farmland.  Vanilla cannot walk horizontally from the water
             * voxel into a solid farmland voxel at the same Y; the player
             * must press jump to swim/step up.  This jump is issued while
             * submerged, before the landing, so it does not by itself
             * trample the verified crop.  Keep it one-shot and continue to
             * release sneak while submerged (sneak is the vanilla descend
             * input in water). */
            final ActionOutcome jumped = coreActuator.jump();
            if (!jumped.accepted()) {
                return fail("water_escape_jump_rejected");
            }
            waterEscapeJumpIssued = true;
            lastPickupSafety = lastPickupSafety + ";jumpIssued=true";
        }
        return SkillTickResult.running(true, true);
    }

    private static boolean waterLandingIsAgricultural(
            final CoreSkillFrame frame,
            final GridPos destination,
            final CropKind crop,
            final java.util.Set<GridPos> authorizedPickupCells
    ) {
        if (destination == null) {
            return false;
        }
        /* During a water escape the candidate cell is the water/feet voxel,
         * while the entity's eventual standing cell is one block above it.
         * Treat both coordinates as transaction-authorized so a jump cannot
         * land on the row merely because the swimming sample is one block
         * lower than the ordinary standing sample. */
        if (authorizedPickupCells.contains(destination)
                || authorizedPickupCells.contains(destination.above())) {
            return true;
        }
        return frame.visibleBlockFaces().stream().anyMatch(face ->
                (sameGrid(face, destination)
                        && agriculturalBlockId(face.blockTypeId(), crop))
                || (sameGrid(face, destination.above())
                        && agriculturalBlockId(face.blockTypeId(), crop))
                || (sameGrid(face, destination.below())
                        && agriculturalBlockId(face.blockTypeId(), crop))
        );
    }

    private static boolean sameGrid(
            final VisibleBlockFace face,
            final GridPos position
    ) {
        return face.block().x() == position.x()
                && face.block().y() == position.y()
                && face.block().z() == position.z();
    }

    /**
     * A jump is destructive if it lands on any observed agricultural block,
     * not only the crop kind currently being harvested.  The broader vanilla
     * row check protects neighbouring rows as well as the transaction's own
     * plot when the player is recovering from irrigation water.
     */
    private static boolean agriculturalBlockId(
            final String blockId,
            final CropKind crop
    ) {
        return "minecraft:farmland".equals(blockId)
                || crop.substrateBlockId().equals(blockId)
                || "minecraft:wheat".equals(blockId)
                || "minecraft:carrots".equals(blockId)
                || "minecraft:potatoes".equals(blockId)
                || "minecraft:beetroots".equals(blockId)
                || "minecraft:nether_wart".equals(blockId);
    }

    private Optional<PerceptionVec3> safestFieldStep(
            final CoreSkillFrame frame,
            final PerceptionVec3 desiredTarget,
            final CropKind crop,
            final boolean allowAnyDirection,
            final java.util.Set<GridPos> authorizedPickupCells
    ) {
        final PerceptionVec3 desired = new PerceptionVec3(
                desiredTarget.x() - frame.position().x(),
                0.0,
                desiredTarget.z() - frame.position().z()
        );
        if (desired.lengthSquared() <= LOOK_EPSILON) {
            return Optional.empty();
        }
        final PerceptionVec3 direction = desired.normalized();
        final int feetY = (int) Math.ceil(
                frame.position().y() - 1.0E-6
        );
        final GridPos current = new GridPos(
                floor(frame.position().x()),
                feetY,
                floor(frame.position().z())
        );
        final GridPos primary = Math.abs(direction.x())
                >= Math.abs(direction.z())
                ? current.offset(direction.x() < 0.0 ? -1 : 1, 0, 0)
                : current.offset(0, 0, direction.z() < 0.0 ? -1 : 1);
        final boolean detourAroundKnownBlock =
                knownBlockedFieldDestination(frame, primary);
        final double minimumDirectionDot = detourAroundKnownBlock
                ? 0.0
                : MINIMUM_PICKUP_DIRECTION_DOT;
        PerceptionVec3 best = null;
        PerceptionVec3 fallback = null;
        GridPos bestDestination = null;
        GridPos fallbackDestination = null;
        int safeCandidates = 0;
        final List<String> rejected = new ArrayList<>();
        double bestScore = allowAnyDirection
                ? Double.NEGATIVE_INFINITY
                : 0.0;
        double fallbackScore = Double.NEGATIVE_INFINITY;
        for (int[] cardinal : CARDINAL_STEPS) {
            final GridPos destination = current.offset(
                    cardinal[0],
                    0,
                    cardinal[1]
            );
            if (!safeFieldDestination(
                    frame,
                    destination,
                    crop,
                    authorizedReplantedPlot,
                    authorizedPickupCells
            )) {
                rejected.add(fieldDestinationDiagnostic(
                        frame,
                        destination,
                        crop
                ));
                continue;
            }
            safeCandidates++;
            final double score = direction.dot(new PerceptionVec3(
                    cardinal[0],
                    0.0,
                    cardinal[1]
            ));
            if (score > fallbackScore) {
                fallbackScore = score;
                final PerceptionVec3 candidatePoint = cardinalStepPoint(
                        frame,
                        destination,
                        cardinal
                );
                fallback = new PerceptionVec3(
                        candidatePoint.x(),
                        candidatePoint.y(),
                        candidatePoint.z()
                );
                fallbackDestination = destination;
            }
            if (score < minimumDirectionDot
                    || score <= bestScore) {
                continue;
            }
            bestScore = score;
            best = cardinalStepPoint(frame, destination, cardinal);
            bestDestination = destination;
        }
        if (best == null && fallback != null) {
            /* A verified side-step is preferable to a stationary loop when
             * every safe cardinal temporarily moves sideways or backward.
             * The next semantic frame re-evaluates the drop direction; no
             * unobserved destination is admitted by this fallback. */
            best = fallback;
            bestDestination = fallbackDestination;
        }
        lastPickupSafety = "rev=" + frame.navigation().revision()
                + ";feet=" + current.x() + "/" + current.y()
                + "/" + current.z()
                + ";safe=" + safeCandidates
                + ";detour=" + detourAroundKnownBlock
                + ";authNear=" + authorizedPickupCells.stream()
                        .filter(cell -> Math.abs(cell.x() - current.x()) <= 2
                                && Math.abs(cell.z() - current.z()) <= 2)
                        .map(cell -> cell.x() + "/" + cell.y() + "/"
                                + cell.z())
                        .sorted()
                        .toList()
                + ";best=" + (bestDestination == null
                        ? "none"
                        : bestDestination.x() + "/"
                            + bestDestination.y() + "/"
                            + bestDestination.z())
                + ";rejected=" + String.join(",", rejected);
        return Optional.ofNullable(best);
    }

    /**
     * Keep a one-cell correction cardinal.  Aiming at the destination centre
     * from an off-centre body can create a diagonal input and clip a liquid or
     * drop-off corner that neither the source nor destination voxel permits.
     */
    private static PerceptionVec3 cardinalStepPoint(
            final CoreSkillFrame frame,
            final GridPos destination,
            final int[] cardinal
    ) {
        return new PerceptionVec3(
                cardinal[0] == 0
                        ? frame.position().x()
                        : destination.x() + 0.5,
                frame.eyePosition().y(),
                cardinal[1] == 0
                        ? frame.position().z()
                        : destination.z() + 0.5
        );
    }

    private static boolean knownBlockedFieldDestination(
            final CoreSkillFrame frame,
            final GridPos destination
    ) {
        final long revision = frame.navigation().revision();
        final Optional<ObservedVoxel> body = frame.navigation().voxelAt(
                destination
        );
        final Optional<ObservedVoxel> head = frame.navigation().voxelAt(
                destination.above()
        );
        final Optional<ObservedVoxel> support = frame.navigation().voxelAt(
                destination.below()
        );
        /* Unknown is not walkable, but it is also not a reason to freeze:
         * make the direction scorer consider a verified side-step so the
         * ordinary observation pass can expose another corridor.  The final
         * safeFieldDestination check below still rejects every unknown
         * destination unless this transaction's bounded field authorization
         * proves it. */
        if (body.isEmpty() || head.isEmpty() || support.isEmpty()) {
            return true;
        }
        if (body.filter(voxel -> recentEvidence(voxel, revision))
                .filter(voxel -> !NavigationEvidence
                        .hasTraversalClearance(voxel)
                        || voxel.effectiveDanger()
                            > PICKUP_STEP_MAXIMUM_DANGER)
                .isPresent()
                || head.filter(voxel -> recentEvidence(voxel, revision))
                .filter(voxel -> !NavigationEvidence
                        .hasTraversalClearance(voxel)
                        || voxel.effectiveDanger()
                            > PICKUP_STEP_MAXIMUM_DANGER)
                .isPresent()) {
            return true;
        }
        return support.filter(voxel ->
                voxel.observationRevision() <= revision
                        && revision - voxel.observationRevision()
                            <= PICKUP_SUPPORT_MAXIMUM_REVISION_AGE
        ).filter(voxel -> !voxel.kind().supportsWeight()
                || voxel.effectiveDanger()
                    > PICKUP_STEP_MAXIMUM_DANGER
        ).isPresent();
    }

    /**
     * A safe sideways step is not progress toward a scattered vanilla drop.
     * When the forward cardinal lacks current support evidence, inspect that
     * exact next crop/ground cell and wait for the ordinary semantic sampler
     * to refresh it. No movement is issued from retained evidence alone.
     */
    private SkillTickResult inspectPickupCorridor(
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        final double dx = target.x() - frame.position().x();
        final double dz = target.z() - frame.position().z();
        final int stepX;
        final int stepZ;
        if (Math.abs(dx) >= Math.abs(dz)) {
            stepX = dx < 0.0 ? -1 : 1;
            stepZ = 0;
        } else {
            stepX = 0;
            stepZ = dz < 0.0 ? -1 : 1;
        }
        final PerceptionVec3 inspectionPoint = new PerceptionVec3(
                floor(frame.position().x()) + stepX + 0.5,
                /* Observation is still first-person and bounded to the next
                 * cell, but looking at eye height prevents a repeated
                 * down/up pitch cycle from suppressing forward input. */
                frame.eyePosition().y(),
                floor(frame.position().z()) + stepZ + 0.5
        );
        pickupInspectionPoint = inspectionPoint;
        pickupInspectionObservationRevision = frame.observationRevision();
        final AimResult aim = aimAt(frame, inspectionPoint);
        if (!aim.accepted()) {
            return fail("look_rejected");
        }
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult holdPickupInspection(
            final CoreSkillFrame frame
    ) {
        final AimResult aim = aimAt(
                frame,
                Objects.requireNonNull(pickupInspectionPoint)
        );
        if (!aim.accepted()) {
            return fail("look_rejected");
        }
        return SkillTickResult.running(true, false);
    }

    private static boolean safeFieldDestination(
            final CoreSkillFrame frame,
            final GridPos destination,
            final CropKind crop,
            final GridPos authorizedReplantedPlot,
            final java.util.Set<GridPos> authorizedPickupCells
    ) {
        final long revision = frame.navigation().revision();
        final Optional<ObservedVoxel> body = frame.navigation().voxelAt(
                destination
        );
        final Optional<ObservedVoxel> head = frame.navigation().voxelAt(
                destination.above()
        );
        final Optional<ObservedVoxel> support = frame.navigation().voxelAt(
                destination.below()
        );
        final boolean visibleCropCorridor = frame.visibleBlockFaces()
                .stream().anyMatch(face ->
                        face.block().x() == destination.x()
                                && face.block().y() == destination.y()
                                && face.block().z() == destination.z()
                                && crop.isPlant(face)
                );
        if (body.isEmpty()
                || head.isEmpty()
                || Math.max(
                        body.orElseThrow().effectiveDanger(),
                        head.orElseThrow().effectiveDanger()
                ) > PICKUP_STEP_MAXIMUM_DANGER) {
            return false;
        }
        /* Irrigation water is traversable in vanilla, but it is not a
         * valid pickup standing cell for this atomic field operation.  A
         * visible liquid must be routed around (or crossed while sneaking
         * only after an agricultural landing has been verified), never
         * selected as the normal forward step. */
        if (body.orElseThrow().kind().isLiquid()
                || frame.visibleBlockFaces().stream().anyMatch(face ->
                        face.block().x() == destination.x()
                                && face.block().y() == destination.y()
                                && face.block().z() == destination.z()
                                && "minecraft:water".equals(
                                    face.blockTypeId()
                                ))) {
            return false;
        }
        if (frame.visibleBlockFaces().stream().anyMatch(face ->
                "minecraft:water".equals(face.blockTypeId())
                        && face.block().x() == destination.x()
                        && face.block().y() == destination.y() - 1
                        && face.block().z() == destination.z())) {
            /* A water source immediately below an otherwise clear voxel is
             * not a standing destination.  This explicit face check also
             * covers a same-frame body-contact record that must not upgrade
             * the liquid support to solid merely because the player is
             * straddling the edge. */
            return false;
        }
        if (support.isPresent()
                && !authorizedPickupCells.contains(destination)
                && support.orElseThrow().occupancyEvidence()
                        == OccupancyEvidence.BODY_CONTACT) {
            /* BODY_CONTACT is only a full fact for the current standing
             * support.  A future cardinal cell carrying the old contact
             * evidence is stale by construction; require a surface/route
             * observation before stepping there. */
            return false;
        }
        if (destination.equals(authorizedReplantedPlot)
                && support.isPresent()
                && !support.orElseThrow().kind().isLiquid()
                && recentTraversalClearance(
                        body.orElseThrow(),
                        revision
                )
                && recentTraversalClearance(
                        head.orElseThrow(),
                        revision
                )) {
            return true;
        }
        if (authorizedPickupCells.contains(destination)
                && recentTraversalClearance(
                        body.orElseThrow(),
                        revision
                )
                && recentTraversalClearance(
                        head.orElseThrow(),
                        revision
                )
                && support.isPresent()
                && !support.orElseThrow().kind().isLiquid()
                && (support.orElseThrow().kind().supportsWeight()
                    || support.orElseThrow().kind() == VoxelKind.AIR
                        && visibleCropCorridor)) {
            /* The parent survey proved this exact field cell's substrate.
             * A crop may hide that support from the current fair ray, or an
             * older Forge patch may publish its support as AIR while the crop
             * occupies the body ray. The exception is bounded to this
             * transaction's survey-derived pickup corridor and never covers
             * arbitrary terrain or a liquid support. The movement actuator
             * keeps this landing sneaking so a verified crop cell cannot be
             * trampled while the item is collected. */
            return true;
        }
        /*
         * A crop's non-solid body can hide the farmland voxel directly below
         * it from the current ray sample.  When the crop itself is visible
         * and both traversal cells are fresh and clear, walking across that
         * one field cell is fair and bounded to this just-replanted pickup.
         * This never treats an unseen support, water, or an arbitrary block as
         * walkable.
         */
        if (support.isEmpty()
                && visibleCropCorridor
                && recentTraversalClearance(body.orElseThrow(), revision)
                && recentTraversalClearance(
                        head.orElseThrow(),
                        revision
                )) {
            return true;
        }
        if (support.isEmpty()
                || support.orElseThrow().effectiveDanger()
                    > PICKUP_STEP_MAXIMUM_DANGER) {
            return false;
        }
        if (support.orElseThrow().kind().isLiquid()) {
            return false;
        }
        if (visibleCropCorridor
                && NavigationEvidence.hasTraversalClearance(
                        body.orElseThrow()
                )
                && NavigationEvidence.hasTraversalClearance(
                        head.orElseThrow()
                )
                && support.orElseThrow().kind().supportsWeight()) {
            return true;
        }
        if (!recentTraversalClearance(body.orElseThrow(), revision)
                || !recentTraversalClearance(
                        head.orElseThrow(),
                        revision
                )) {
            return false;
        }
        if (NavigationEvidence.isRecentBodyContactSupport(
                support.orElseThrow(),
                revision,
                PICKUP_BODY_CONTACT_MAXIMUM_REVISION_AGE
        )) {
            return true;
        }
        if (support.orElseThrow().topSupportAffordance()
                    .safelySupportsStanding()
                && support.orElseThrow().observationRevision()
                    <= revision
                && revision - support.orElseThrow()
                    .observationRevision()
                    <= PICKUP_SUPPORT_MAXIMUM_REVISION_AGE
                && support.orElseThrow().kind().supportsWeight()) {
            return true;
        }
        if (recentStandingSupport(support.orElseThrow(), revision)) {
            return true;
        }
        return recentEvidence(support.orElseThrow(), revision)
                && support.orElseThrow().kind().supportsWeight()
                && visibleCropCorridor;
    }

    private static String fieldDestinationDiagnostic(
            final CoreSkillFrame frame,
            final GridPos destination,
            final CropKind crop
    ) {
        final Optional<ObservedVoxel> body = frame.navigation().voxelAt(
                destination
        );
        final Optional<ObservedVoxel> head = frame.navigation().voxelAt(
                destination.above()
        );
        final Optional<ObservedVoxel> support = frame.navigation().voxelAt(
                destination.below()
        );
        final boolean visible = frame.visibleBlockFaces().stream()
                .anyMatch(face ->
                        face.block().x() == destination.x()
                                && face.block().y() == destination.y()
                                && face.block().z() == destination.z()
                                && crop.isPlant(face)
                );
        return destination.x() + "/" + destination.y() + "/"
                + destination.z()
                + ":b=" + voxelDiagnostic(body)
                + ":h=" + voxelDiagnostic(head)
                + ":s=" + voxelDiagnostic(support)
                + ":v=" + visible;
    }

    private static String voxelDiagnostic(
            final Optional<ObservedVoxel> voxel
    ) {
        return voxel.map(value -> value.kind().name()
                + "/" + value.observationRevision()
                + "/" + value.occupancyEvidence().name()
                + "/" + value.topSupportAffordance().name()
        ).orElse("missing");
    }

    private static boolean recentTraversalClearance(
            final ObservedVoxel voxel,
            final long revision
    ) {
        return recentEvidence(voxel, revision)
                && NavigationEvidence.hasTraversalClearance(voxel);
    }

    private static boolean recentStandingSupport(
            final ObservedVoxel voxel,
            final long revision
    ) {
        return recentEvidence(voxel, revision)
                && voxel.kind().supportsWeight()
                && voxel.topSupportAffordance()
                        == TopSupportAffordance.STURDY_FULL_TOP
                && (voxel.occupancyEvidence()
                        == OccupancyEvidence.SURFACE_HIT
                    || voxel.occupancyEvidence()
                        == OccupancyEvidence.BODY_CONTACT);
    }

    private static boolean recentEvidence(
            final ObservedVoxel voxel,
            final long revision
    ) {
        return voxel.observationRevision() <= revision
                && revision - voxel.observationRevision()
                        <= PICKUP_EVIDENCE_MAXIMUM_REVISION_AGE;
    }

    private static int floor(final double value) {
        return (int) Math.floor(value);
    }

    private static double horizontalDistance(
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        return Math.hypot(
                target.x() - frame.position().x(),
                target.z() - frame.position().z()
        );
    }

    /**
     * Uses only a drop that passed the companion's ordinary distance, FOV,
     * and block-clip perception. Matching both the harvest item id and the
     * already-authorized crop origin prevents a nearby unrelated item from
     * steering the atomic farming step.
     */
    private Optional<VisibleEntity> visibleHarvestDrop(
            final CoreSkillFrame frame,
            final HarvestAndReplantParameters parameters
    ) {
        final PerceptionVec3 origin = new PerceptionVec3(
                targetFor(parameters).x() + 0.5,
                targetFor(parameters).y() + 0.5,
                targetFor(parameters).z() + 0.5
        );
        final List<VisibleEntity> candidates = frame.visibleEntities().stream()
                .filter(entity ->
                        ITEM_ENTITY_ID.equals(entity.entityTypeId())
                                && parameters.crop().harvestItemId()
                                    .equals(
                                        entity.visibleProperties()
                                            .get("itemId")
                                    )
                && entity.position()
                    .subtract(origin)
                                    .length()
                                    <= HARVEST_DROP_ORIGIN_RADIUS)
                .toList();
        final List<VisibleEntity> freshCandidates = candidates.stream()
                .filter(entity -> !preExistingHarvestDropIds.contains(
                        entity.entityId()
                ))
                .toList();
        final List<VisibleEntity> associated = freshCandidates.isEmpty()
                ? candidates
                : freshCandidates;
        final Optional<VisibleEntity> current = associated.stream()
                /* Root crops use the same item for both seed and harvest.
                 * Several already-collected drops can therefore be visible
                 * at once.  Associate the item with this transaction by the
                 * authorized crop origin, not by whichever old item happens
                 * to be closest to the player's feet. */
                .min(Comparator.comparingDouble(entity ->
                        entity.position()
                            .subtract(origin)
                            .lengthSquared()));
        if (current.isPresent()) {
            final VisibleEntity observed = current.orElseThrow();
            rememberedHarvestDropId = observed.entityId();
            rememberedHarvestDropPoint = observed.position();
            rememberedHarvestDropRevision = frame.observationRevision();
            return current;
        }
        /* A crop item can leave the view cone while the body takes a legal
         * one-cell detour around irrigation.  Retain only the last ordinary
         * first-person observation for a short, transaction-local window;
         * this is not an entity scan and cannot survive a long route. */
        if (rememberedHarvestDropId != null
                && rememberedHarvestDropPoint != null
                && rememberedHarvestDropRevision >= 0
                && frame.observationRevision()
                        - rememberedHarvestDropRevision
                    <= PICKUP_DROP_MEMORY_MAXIMUM_REVISION_AGE
                && rememberedHarvestDropPoint.subtract(origin).length()
                    <= HARVEST_DROP_ORIGIN_RADIUS + 0.75) {
            final PerceptionVec3 relative =
                    rememberedHarvestDropPoint.subtract(frame.position());
            return Optional.of(new VisibleEntity(
                    rememberedHarvestDropId,
                    ITEM_ENTITY_ID,
                    rememberedHarvestDropPoint,
                    relative,
                    relative.length(),
                    false,
                    false,
                    PerceptionProvenance.ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                    Map.of(
                            "itemId",
                            parameters.crop().harvestItemId()
                    )
            ));
        }
        return Optional.empty();
    }

    private SkillTickResult waitForReplantOrFail(
            SkillContext context
    ) {
        if (context.gameTick() - plantedAtGameTick
                >= policy.replantConfirmationTicks()) {
            return fail("replant_not_confirmed");
        }
        return SkillTickResult.running(false, false);
    }

    private ObservedBlockTarget targetFor(
            final HarvestAndReplantParameters parameters
    ) {
        return effectiveTarget == null
                ? parameters.target()
                : effectiveTarget;
    }

    /**
     * Repairs only a stale/imagined model coordinate when the current fair
     * first-person sample contains exactly one mature crop of the requested
     * kind.  Multiple candidates remain fail-closed so the model still owns
     * the target choice; no world lookup or hidden scan is performed here.
     */
    private Optional<ObservedBlockTarget> uniqueCurrentMatureTarget(
            final InteractionSkillFrame frame,
            final HarvestAndReplantParameters parameters
    ) {
        final Map<GridPos, VisibleBlockFace> candidates = new HashMap<>();
        for (VisibleBlockFace face : frame.visibleBlockFaces()) {
            if (!parameters.crop().isMature(face)
                    || face.block() == null) {
                continue;
            }
            final GridPos position = new GridPos(
                    face.block().x(),
                    face.block().y(),
                    face.block().z()
            );
            candidates.merge(
                    position,
                    face,
                    (left, right) -> left.distance() <= right.distance()
                            ? left
                            : right
            );
        }
        if (candidates.size() != 1) {
            return Optional.empty();
        }
        final VisibleBlockFace face = candidates.values().iterator().next();
        try {
            return Optional.of(new ObservedBlockTarget(
                    frame.observationRevision(),
                    face.block().x(),
                    face.block().y(),
                    face.block().z(),
                    BlockFace.valueOf(face.face().toUpperCase(Locale.ROOT))
            ));
        } catch (IllegalArgumentException invalidFace) {
            return Optional.empty();
        }
    }

    private BlockResolution resolveInitialCrop(
            InteractionSkillFrame frame,
            HarvestAndReplantParameters parameters
    ) {
        ObservedBlockTarget target = targetFor(parameters);
        final Optional<InteractionSkillFrame> historical =
                frames.atObservation(target.sampleSequence());
        if (historical.isEmpty()) {
            return BlockResolution.failed(failure(
                    "stale_observation_id"
            ));
        }
        final InteractionSkillFrame observed = historical.orElseThrow();
        if (!expectedPlayerId.equals(observed.playerId())
                || !parameters.dimension().equals(observed.dimension())
                || observed.sessionGeneration() != frame.sessionGeneration()) {
            return BlockResolution.failed(failure(
                    "observation_binding_changed"
            ));
        }
        final Optional<VisibleBlockFace> authorized = observed
                .visibleBlockFaces()
                .stream()
                .filter(face -> sameBlockAndFace(face, target))
                .filter(parameters.crop()::isMature)
                .findFirst();
        if (authorized.isEmpty()) {
            boolean targetVisible = observed.visibleBlockFaces().stream()
                    .anyMatch(face -> sameBlockAndFace(face, target));
            return BlockResolution.failed(failure(
                    targetVisible
                            ? "crop_not_mature"
                            : "target_not_visible"
            ));
        }
        /*
         * A network model normally answers several semantic frames after the
         * sample it saw. Resolve authorization against that exact retained
         * fair sample, then require the same crop coordinate and face to
         * remain visible and mature in the latest frame. The current ray hit
         * supplies the actual vanilla interaction target, so retained data
         * can never authorize an action against a changed or hidden block.
         */
        final Optional<VisibleBlockFace> current = frame.visibleBlockFaces()
                .stream()
                .filter(face -> sameBlock(face, target))
                .filter(parameters.crop()::isMature)
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
        if (current.isEmpty()) {
            return BlockResolution.failed(failure(
                    "target_not_currently_visible"
            ));
        }
        VisibleBlockFace face = current.orElseThrow();
        if (face.distance() > policy.maximumCandidateDistance()) {
            return BlockResolution.failed(failure(
                    "target_out_of_range"
            ));
        }
        return BlockResolution.resolved(face);
    }

    private CoreFrameValidation validateCoreFrame(
            HarvestAndReplantParameters parameters
    ) {
        Optional<CoreSkillFrame> maybeFrame = coreFrames.current();
        if (maybeFrame.isEmpty()) {
            return CoreFrameValidation.failed(failure(
                    "pose_unavailable"
            ));
        }
        CoreSkillFrame frame = maybeFrame.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return CoreFrameValidation.failed(failure(
                    "pose_player_mismatch"
            ));
        }
        if (!parameters.dimension().equals(frame.dimension())) {
            return CoreFrameValidation.failed(failure(
                    "pose_dimension_mismatch"
            ));
        }
        return CoreFrameValidation.valid(frame);
    }

    private AimResult aimAt(
            CoreSkillFrame frame,
            PerceptionVec3 targetPosition
    ) {
        ActionOutcome stopped = coreActuator.stop();
        if (!stopped.accepted()) {
            return new AimResult(false, Double.POSITIVE_INFINITY);
        }
        PerceptionVec3 delta = targetPosition.subtract(
                frame.eyePosition()
        );
        if (delta.lengthSquared() <= LOOK_EPSILON) {
            return new AimResult(true, 0.0);
        }
        ActionOutcome looked = coreActuator.look(
                lookAt(frame.eyePosition(), targetPosition)
        );
        double dot = frame.lookDirection()
                .normalized()
                .dot(delta.normalized());
        double error = Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
        return new AimResult(looked.accepted(), error);
    }

    /**
     * Steers a locomotion input without clearing the already-accelerating
     * movement lease.  The interaction-oriented {@link #aimAt} deliberately
     * calls stop before a precise use/attack, but doing that on every movement
     * tick resets vanilla acceleration and makes a sneaking player appear to
     * rotate in place at crop edges.  Movement steering keeps the same
     * first-person absolute yaw/pitch and only omits that interaction stop.
     */
    private AimResult aimForMovement(
            final CoreSkillFrame frame,
            final PerceptionVec3 targetPosition
    ) {
        PerceptionVec3 delta = targetPosition.subtract(
                frame.eyePosition()
        );
        if (delta.lengthSquared() <= LOOK_EPSILON) {
            return new AimResult(true, 0.0);
        }
        ActionOutcome looked = coreActuator.look(
                lookAt(frame.eyePosition(), targetPosition)
        );
        double dot = frame.lookDirection()
                .normalized()
                .dot(delta.normalized());
        double error = Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
        return new AimResult(looked.accepted(), error);
    }

    private PerceptionVec3 expectedSubstrateSurface(
            HarvestAndReplantParameters parameters
    ) {
        /*
         * Aim into the already-known substrate voxel rather than at the crop
         * block's lower boundary. Farmland's outline is 15/16 of a block
         * high, so aiming exactly at crop Y sends the centre ray 1/16 above
         * its selectable top and can miss forever. Aiming at the substrate
         * centre is also wrong from a shallow angle: the ray descends early
         * and can strike the floor in front of the requested plot. An inset
         * one eighth below crop Y intersects farmland's real top within the
         * requested voxel and also works for full-height substrates. The
         * crosshair sampler must still verify the actual block id and UP face
         * before use.
         */
        return new PerceptionVec3(
                targetFor(parameters).x() + 0.5,
                targetFor(parameters).y() - 0.125,
                targetFor(parameters).z() + 0.5
        );
    }

    private PerceptionVec3 postPlantSurveyPoint(
            HarvestAndReplantParameters parameters
    ) {
        return new PerceptionVec3(
                targetFor(parameters).x() + 0.5,
                targetFor(parameters).y() + 0.3,
                targetFor(parameters).z() + 0.5
        );
    }

    private static LookIntent lookAt(
            PerceptionVec3 eye,
            PerceptionVec3 target
    ) {
        PerceptionVec3 delta = target.subtract(eye);
        float yaw = (float) Math.toDegrees(
                Math.atan2(-delta.x(), delta.z())
        );
        float pitch = (float) Math.toDegrees(Math.atan2(
                -delta.y(),
                Math.hypot(delta.x(), delta.z())
        ));
        return new LookIntent(yaw, pitch);
    }

    private Optional<VisibleBlockFace> visibleSubstrate(
            InteractionSkillFrame frame,
            HarvestAndReplantParameters parameters
    ) {
        Optional<VisibleBlockFace> crosshair =
                frames.currentCrosshairBlock()
                        .filter(face -> isSubstrate(
                                face,
                                parameters
                        ));
        if (crosshair.isPresent()) {
            return crosshair;
        }
        return frame.visibleBlockFaces().stream()
                .filter(face -> isSubstrate(face, parameters))
                .findFirst();
    }

    private String plantObservationDiagnostic(
            final InteractionSkillFrame frame,
            final HarvestAndReplantParameters parameters,
            final Optional<VisibleBlockFace> crosshair,
            final Optional<VisibleBlockFace> substrate
    ) {
        final String faces = frame.visibleBlockFaces().stream()
                .filter(face -> sameBlock(face, targetFor(parameters))
                        || (face.block().x() == targetFor(parameters).x()
                            && face.block().y()
                                == targetFor(parameters).y() - 1
                            && face.block().z() == targetFor(parameters).z()))
                .limit(4)
                .map(HarvestAndReplantStepSkill::faceDiagnostic)
                .reduce((left, right) -> left + ";" + right)
                .orElse("none");
        return "rev=" + frame.observationRevision()
                + ";crosshair=" + crosshair.map(
                        HarvestAndReplantStepSkill::faceDiagnostic
                ).orElse("none")
                + ";substrate=" + substrate.map(
                        HarvestAndReplantStepSkill::faceDiagnostic
                ).orElse("none")
                + ";faces=" + faces;
    }

    private static String faceDiagnostic(final VisibleBlockFace face) {
        return face.block().x() + "/" + face.block().y() + "/"
                + face.block().z() + "/" + face.blockTypeId()
                + "/" + face.face();
    }

    private Optional<VisibleBlockFace> currentCrosshairCrop(
            HarvestAndReplantParameters parameters
    ) {
        return frames.currentCrosshairBlock()
                .filter(face ->
                        sameBlock(face, targetFor(parameters))
                                && parameters.crop().isMature(face)
                                && face.distance()
                                <= policy.maximumCandidateDistance()
                );
    }

    private boolean isSubstrate(
            VisibleBlockFace face,
            HarvestAndReplantParameters parameters
    ) {
        int x = targetFor(parameters).x();
        int y = targetFor(parameters).y() - 1;
        int z = targetFor(parameters).z();
        return face.block().x() == x
                        && face.block().y() == y
                        && face.block().z() == z
                        && face.face().equals("up")
                        && face.blockTypeId().equals(
                                parameters.crop().substrateBlockId()
                        )
                        && face.distance()
                                <= policy.maximumCandidateDistance();
    }

    private FrameValidation validateFrame(
            HarvestAndReplantParameters parameters,
            long expectedSessionGeneration
    ) {
        Optional<InteractionSkillFrame> maybeFrame = frames.current();
        if (maybeFrame.isEmpty()) {
            return FrameValidation.failed(failure(
                    "observation_unavailable"
            ));
        }
        InteractionSkillFrame frame = maybeFrame.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return FrameValidation.failed(frame, failure(
                    "player_mismatch"
            ));
        }
        if (!parameters.dimension().equals(frame.dimension())) {
            return FrameValidation.failed(frame, failure(
                    "dimension_mismatch"
            ));
        }
        if (frame.observationAgeTicks()
                > policy.maximumObservationAgeTicks()) {
            return FrameValidation.failed(frame, failure(
                    "stale_observation"
            ));
        }
        OptionalLong actuatorSession = actuator.sessionGeneration();
        if (actuatorSession.isEmpty()) {
            return FrameValidation.failed(frame, failure(
                    "player_unavailable"
            ));
        }
        long actualSession = actuatorSession.orElseThrow();
        if (frame.sessionGeneration() != actualSession
                || expectedSessionGeneration >= 0
                && expectedSessionGeneration != actualSession) {
            return FrameValidation.failed(frame, failure(
                    "session_mismatch"
            ));
        }
        return FrameValidation.valid(frame);
    }

    private static int seedCount(
            InteractionSkillFrame frame,
            CropKind crop
    ) {
        return itemCount(frame.inventory(), crop.seedItemId());
    }

    private boolean harvestCollected(
            CoreSkillFrame frame,
            CropKind crop
    ) {
        return itemCount(
                frame.inventory(),
                crop.harvestItemId()
        ) > harvestItemsBefore;
    }

    private static int itemCount(
            java.util.List<InventoryItemSummary> inventory,
            String itemId
    ) {
        return inventory.stream()
                .filter(item -> itemId.equals(item.itemId()))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static boolean sameBlockAndFace(
            VisibleBlockFace visible,
            ObservedBlockTarget target
    ) {
        return sameBlock(visible, target)
                && visible.face().equals(
                        target.face().name().toLowerCase(Locale.ROOT)
                );
    }

    private static boolean sameBlock(
            VisibleBlockFace visible,
            ObservedBlockTarget target
    ) {
        return visible.block().x() == target.x()
                && visible.block().y() == target.y()
                && visible.block().z() == target.z();
    }

    private static BlockInteractionTarget interactionTarget(
            VisibleBlockFace face
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

    private SkillTickResult fail(String suffix) {
        return fail(failure(suffix));
    }

    private SkillTickResult fail(SkillFailure reason) {
        releaseMiningIfStillBound();
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private void releaseMiningIfStillBound() {
        if (phase != Phase.HARVESTING) {
            return;
        }
        OptionalLong session = actuator.sessionGeneration();
        if (session.isPresent()
                && session.orElseThrow() == boundSessionGeneration) {
            actuator.abortMining();
        }
    }

    private static SkillFailure actionFailure(
            String operation,
            ActionOutcome outcome
    ) {
        return SkillFailure.of(
                NAME
                        + "."
                        + operation
                        + "_"
                        + outcome.name().toLowerCase(Locale.ROOT)
        );
    }

    private static SkillFailure failure(String suffix) {
        return SkillFailure.of(NAME + "." + suffix);
    }

    private enum Phase {
        IDLE,
        READY,
        HARVESTING,
        WAITING_FOR_SUBSTRATE,
        WAITING_FOR_REPLANT,
        COLLECTING_HARVEST,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private record FrameValidation(
            Optional<InteractionSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        private FrameValidation {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(failure, "failure");
        }

        private static FrameValidation valid(
                InteractionSkillFrame frame
        ) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.empty()
            );
        }

        private static FrameValidation failed(
                SkillFailure failure
        ) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(failure)
            );
        }

        private static FrameValidation failed(
                InteractionSkillFrame frame,
                SkillFailure failure
        ) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.of(failure)
            );
        }
    }

    private record CoreFrameValidation(
            Optional<CoreSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        private CoreFrameValidation {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(failure, "failure");
        }

        private static CoreFrameValidation valid(
                CoreSkillFrame frame
        ) {
            return new CoreFrameValidation(
                    Optional.of(frame),
                    Optional.empty()
            );
        }

        private static CoreFrameValidation failed(
                SkillFailure failure
        ) {
            return new CoreFrameValidation(
                    Optional.empty(),
                    Optional.of(failure)
            );
        }
    }

    private record BlockResolution(
            Optional<VisibleBlockFace> face,
            Optional<SkillFailure> failure
    ) {
        private BlockResolution {
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(failure, "failure");
        }

        private static BlockResolution resolved(
                VisibleBlockFace face
        ) {
            return new BlockResolution(
                    Optional.of(face),
                    Optional.empty()
            );
        }

        private static BlockResolution failed(
                SkillFailure failure
        ) {
            return new BlockResolution(
                    Optional.empty(),
                    Optional.of(failure)
            );
        }
    }

    private record AimResult(
            boolean accepted,
            double errorDegrees
    ) {
    }
}
