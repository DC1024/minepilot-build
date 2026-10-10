package dev.mcai.companion.skills.bridging;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.CollisionAffordance;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds a level, one-block-wide bridge through ordinary crouching and
 * first-person block placement.
 *
 * <p>The skill never reads the level. A destination body column must have
 * been observed passable, the clicked support face must be present in the
 * latest semantic ray sample, and a placement is not trusted until a newer
 * sample observes the new support as weight-bearing. Crossing starts only
 * after that confirmation.</p>
 */
public final class BridgeToSkill
        implements Skill<BridgeToParameters> {
    public static final String NAME = "bridge_to";

    private static final double NORMAL_MAXIMUM_DANGER = 0.20;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.08;
    private static final double NORMAL_MINIMUM_HEALTH_RATIO = 0.50;
    private static final double HARDCORE_MINIMUM_HEALTH_RATIO = 0.75;
    private static final double MAXIMUM_VERTICAL_DIFFERENCE = 0.75;
    private static final double MAXIMUM_HORIZONTAL_DISTANCE = 64.0;
    private static final double MOVEMENT_ALIGNMENT_DEGREES = 8.0;
    private static final double INTERACTION_ALIGNMENT_DEGREES = 2.5;
    private static final double EDGE_OVERSHOOT = 0.06;
    private static final double MAXIMUM_EDGE_OVERSHOOT = 0.29;
    private static final int MAXIMUM_EDGE_TICKS = 80;
    private static final int MAXIMUM_SCAN_SAMPLES = 12;
    private static final int MAXIMUM_ALIGNMENT_TICKS = 80;
    private static final int PLACEMENT_CONFIRMATION_TICKS = 60;
    private static final int BASE_TIMEOUT_TICKS = 240;
    private static final int TIMEOUT_TICKS_PER_BLOCK = 120;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;
    private final BridgeMaterialActuator materials;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long lastObservationRevision = -1;
    private long phaseStartedAtTick = -1;
    private long phaseObservationRevision = -1;
    private int scanSamples;
    private int edgeTicks;
    private int alignmentTicks;
    private int sneakTicks;
    private int blocksPlaced;
    private GridPos stepFrom;
    private GridPos stepTo;
    private GridPos desiredSupport;
    private Direction2D stepDirection;
    private BlockInteractionTarget placementTarget;
    private String materialItemId;
    private int materialCountBeforePlacement;

    public BridgeToSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final BridgeMaterialActuator materials
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.materials = Objects.requireNonNull(
                materials,
                "materials"
        );
    }

    @Override
    public SkillParameterParser<BridgeToParameters> parameters() {
        return BridgeSkillParameters::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final BridgeToParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final FrameValidation validation = validateFrame(parameters);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        final CoreSkillFrame frame = validation.frame().orElseThrow();
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return unsafe;
        }
        if (!frame.onGround() || frame.inWater()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stable_ground_required"
            ));
        }
        if (Math.abs(parameters.y() - frame.position().y())
                > MAXIMUM_VERTICAL_DIFFERENCE) {
            return Optional.of(SkillFailure.of(
                    NAME + ".level_target_required"
            ));
        }
        if (horizontalDistance(
                frame.position(),
                parameters.target()
        ) > MAXIMUM_HORIZONTAL_DISTANCE) {
            return Optional.of(SkillFailure.of(
                    NAME + ".target_too_far"
            ));
        }
        if (!isObservedSupport(frame, frame.feet().below())) {
            return Optional.of(SkillFailure.of(
                    NAME + ".current_support_unverified"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final BridgeToParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        phase = Phase.READY;
        failure = null;
        startedAtTick = context.gameTick();
        lastObservationRevision = -1;
        phaseStartedAtTick = context.gameTick();
        phaseObservationRevision = -1;
        scanSamples = 0;
        edgeTicks = 0;
        alignmentTicks = 0;
        sneakTicks = 0;
        blocksPlaced = 0;
        clearStep();
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final BridgeToParameters parameters
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
            final BridgeToParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,"
                                + "\"blocksPlaced\":%d,\"maxBlocks\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.x(),
                        parameters.y(),
                        parameters.z(),
                        blocksPlaced,
                        parameters.maxBlocks()
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final BridgeToParameters parameters
    ) {
        quiesce();
        phase = Phase.CANCELLED;
        clearStep();
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final BridgeToParameters parameters
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

    private SkillTickResult tickSafely(
            final SkillContext context,
            final BridgeToParameters parameters
    ) {
        if (context.gameTick() - startedAtTick
                >= BASE_TIMEOUT_TICKS
                    + (long) parameters.maxBlocks()
                    * TIMEOUT_TICKS_PER_BLOCK) {
            return fail(NAME + ".timed_out");
        }
        final FrameValidation validation = validateFrame(parameters);
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final CoreSkillFrame frame = validation.frame().orElseThrow();
        if (frame.observationRevision() < lastObservationRevision) {
            return fail(NAME + ".stale_observation");
        }
        final boolean freshObservation =
                frame.observationRevision() > lastObservationRevision;
        if (freshObservation) {
            lastObservationRevision = frame.observationRevision();
        }
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return fail(unsafe.orElseThrow());
        }
        if (!frame.onGround() || frame.inWater()) {
            return fail(NAME + ".stable_ground_lost");
        }

        return switch (phase) {
            case READY -> prepareStep(
                    context,
                    parameters,
                    frame,
                    freshObservation
            );
            case SCANNING -> scan(
                    context,
                    parameters,
                    frame,
                    freshObservation
            );
            case CROSSING -> cross(
                    context,
                    parameters,
                    frame,
                    freshObservation
            );
            case APPROACHING_EDGE -> approachEdge(
                    context,
                    frame,
                    freshObservation
            );
            case ALIGNING -> alignAndPlace(
                    context,
                    frame,
                    freshObservation
            );
            case VERIFYING -> verifyPlacement(
                    context,
                    parameters,
                    frame,
                    freshObservation
            );
            default -> SkillTickResult.failed(
                    NAME + ".invalid_state"
            );
        };
    }

    private SkillTickResult prepareStep(
            final SkillContext context,
            final BridgeToParameters parameters,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        if (arrived(parameters, frame)
                && isObservedSupport(frame, frame.feet().below())) {
            quiesce();
            phase = Phase.COMPLETED;
            clearStep();
            return SkillTickResult.completed();
        }
        if (Math.abs(parameters.y() - frame.position().y())
                > MAXIMUM_VERTICAL_DIFFERENCE) {
            return fail(NAME + ".level_target_required");
        }

        stepFrom = frame.feet();
        stepDirection = directionToward(
                frame.position(),
                parameters.target()
        );
        if (stepDirection == null) {
            return fail(NAME + ".target_cell_unreachable");
        }
        stepTo = stepFrom.offset(
                stepDirection.deltaX,
                0,
                stepDirection.deltaZ
        );
        desiredSupport = stepTo.below();
        if (!isObservedSupport(frame, stepFrom.below())) {
            return fail(NAME + ".current_support_unverified");
        }
        final boolean destinationClear = observedPassable(frame, stepTo)
                && observedPassable(frame, stepTo.above());
        final Optional<BlockInteractionTarget> directAttachment =
                parameters.allowObservedAttachment()
                        ? visibleAttachmentTarget(frame, stepTo)
                        : Optional.empty();
        /* A side face of the current support is also a valid vanilla
         * attachment for the support block one level below the next feet
         * cell.  This is the ordinary way to turn a one-block bridge around
         * a pillar.  Previously the attached-only branch looked exclusively
         * for a face adjacent to the feet cell, so a freshly observed bridge
         * edge was discarded even though the player could place the next
         * support block against it. */
        final Optional<BlockInteractionTarget> supportAttachment =
                parameters.allowObservedAttachment()
                        && !destinationClear
                        ? visiblePlacementTarget(frame)
                        : Optional.empty();
        if (!destinationClear
                && (directAttachment.isPresent()
                    || supportAttachment.isPresent())) {
            /*
             * A player can legally place the next feet block against a
             * freshly observed wall even when the block below that cell is
             * still unknown (for example, the side of a natural End pillar).
             * The ordinary bridge path places desiredSupport below the next
             * cell; this bounded mode instead places directly into stepTo and
             * only crosses after a newer frame proves that placed block is
             * weight-bearing.  No destination is inferred from absence.
            */
            final BridgeMaterialResult material = materials.ensureEquipped();
            if (!material.ready()) {
                return fail(material.failureCode().orElseThrow());
            }
            materialItemId = material.itemId();
            materialCountBeforePlacement = inventoryCount(
                    frame,
                    materialItemId
            );
            if (materialCountBeforePlacement < 1) {
                materialCountBeforePlacement = material.availableCount();
            }
            final boolean placingDirectly = directAttachment.isPresent();
            desiredSupport = placingDirectly
                    ? stepTo
                    : stepTo.below();
            beginPhase(
                    Phase.APPROACHING_EDGE,
                    context,
                    frame
            );
            placementTarget = placingDirectly
                    ? directAttachment.orElseThrow()
                    : supportAttachment.orElseThrow();
            edgeTicks = 0;
            sneakTicks = 0;
            return approachEdge(context, frame, freshObservation);
        }
        if (!destinationClear) {
            beginPhase(
                    Phase.SCANNING,
                    context,
                    frame
            );
            scanSamples = 0;
            return scan(
                    context,
                    parameters,
                    frame,
                    freshObservation
            );
        }

        final Optional<ObservedVoxel> support =
                frame.navigation().voxelAt(desiredSupport);
        if (support.isPresent()
                && support.orElseThrow().kind().supportsWeight()) {
            beginPhase(Phase.CROSSING, context, frame);
            return cross(
                    context,
                    parameters,
                    frame,
                    freshObservation
            );
        }
        if (support.isPresent()
                && support.orElseThrow().kind() != VoxelKind.AIR) {
            return fail(NAME + ".unsafe_support_space");
        }
        if (blocksPlaced >= parameters.maxBlocks()) {
            return fail(NAME + ".block_budget_exhausted");
        }

        final BridgeMaterialResult material =
                materials.ensureEquipped();
        if (!material.ready()) {
            return fail(material.failureCode().orElseThrow());
        }
        materialItemId = material.itemId();
        materialCountBeforePlacement =
                inventoryCount(frame, materialItemId);
        if (materialCountBeforePlacement < 1) {
            materialCountBeforePlacement = material.availableCount();
        }
        beginPhase(Phase.APPROACHING_EDGE, context, frame);
        edgeTicks = 0;
        sneakTicks = 0;
        return approachEdge(context, frame, freshObservation);
    }

    private SkillTickResult scan(
            final SkillContext context,
            final BridgeToParameters parameters,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        if (freshObservation
                && frame.observationRevision()
                    > phaseObservationRevision) {
            scanSamples++;
            phaseObservationRevision = frame.observationRevision();
            if (observedPassable(frame, stepTo)
                    && observedPassable(frame, stepTo.above())) {
                phase = Phase.READY;
                return SkillTickResult.running(true, true);
            }
            final Optional<BlockInteractionTarget> directAttachment =
                    parameters.allowObservedAttachment()
                            ? visibleAttachmentTarget(frame, stepTo)
                            : Optional.empty();
            final Optional<BlockInteractionTarget> supportAttachment =
                    parameters.allowObservedAttachment()
                            ? visiblePlacementTarget(frame)
                            : Optional.empty();
            if (parameters.allowObservedAttachment()
                    && (directAttachment.isPresent()
                        || supportAttachment.isPresent())) {
                final BridgeMaterialResult material =
                        materials.ensureEquipped();
                if (!material.ready()) {
                    return fail(material.failureCode().orElseThrow());
                }
                materialItemId = material.itemId();
                materialCountBeforePlacement = inventoryCount(
                        frame,
                        materialItemId
                );
                if (materialCountBeforePlacement < 1) {
                    materialCountBeforePlacement = material.availableCount();
                }
                final boolean placingDirectly = directAttachment.isPresent();
                desiredSupport = placingDirectly
                        ? stepTo
                        : stepTo.below();
                phase = Phase.APPROACHING_EDGE;
                placementTarget = placingDirectly
                        ? directAttachment.orElseThrow()
                        : supportAttachment.orElseThrow();
                edgeTicks = 0;
                sneakTicks = 0;
                return approachEdge(
                        context,
                        frame,
                        freshObservation
                );
            }
        }
        if (scanSamples >= MAXIMUM_SCAN_SAMPLES) {
            return fail(NAME + ".destination_column_unverified");
        }
        if (!holdStill(false)) {
            return fail(NAME + ".actuator_rejected");
        }
        final PerceptionVec3 scanTarget = new PerceptionVec3(
                parameters.x(),
                stepTo.y() + 0.35,
                parameters.z()
        );
        if (!actuator.look(
                lookAt(frame.eyePosition(), scanTarget)
        ).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        return SkillTickResult.running(freshObservation, true);
    }

    private SkillTickResult approachEdge(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        if (!nearCurrentStep(frame)) {
            return fail(NAME + ".edge_position_lost");
        }
        edgeTicks++;
        if (edgeTicks > MAXIMUM_EDGE_TICKS) {
            return fail(NAME + ".edge_approach_timed_out");
        }
        if (pastMaximumEdge(frame.position(), stepFrom, stepDirection)) {
            return fail(NAME + ".edge_overshoot");
        }

        final PerceptionVec3 horizontalTarget =
                horizontalTarget(frame, stepDirection);
        final LookIntent look =
                lookAt(frame.eyePosition(), horizontalTarget);
        final ActionOutcome looked = actuator.look(look);
        final double error = horizontalAngularErrorDegrees(
                frame.lookDirection(),
                horizontalTarget.subtract(frame.eyePosition())
        );
        final ActionOutcome moved = actuator.move(new MovementIntent(
                error <= MOVEMENT_ALIGNMENT_DEGREES ? 0.25 : 0.0,
                0.0,
                false,
                true
        ));
        if (!looked.accepted() || !moved.accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        sneakTicks++;
        if (!pastEdge(
                frame.position(),
                stepFrom,
                stepDirection
        )) {
            return SkillTickResult.running(
                    freshObservation || error
                            > MOVEMENT_ALIGNMENT_DEGREES,
                    false
            );
        }
        beginPhase(Phase.ALIGNING, context, frame);
        alignmentTicks = 0;
        placementTarget = null;
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult alignAndPlace(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        if (!nearCurrentStep(frame)) {
            return fail(NAME + ".edge_position_lost");
        }
        if (pastMaximumEdge(frame.position(), stepFrom, stepDirection)) {
            return fail(NAME + ".edge_overshoot");
        }
        alignmentTicks++;
        if (alignmentTicks > MAXIMUM_ALIGNMENT_TICKS) {
            return fail(NAME + ".visible_placement_face_unavailable");
        }

        if (placementTarget == null) {
            placementTarget = visiblePlacementTarget(frame)
                    .orElse(null);
        }
        final PerceptionVec3 target = placementTarget == null
                ? syntheticSideCenter()
                : new PerceptionVec3(
                        placementTarget.hitPoint().x(),
                        placementTarget.hitPoint().y(),
                        placementTarget.hitPoint().z()
                );
        final ActionOutcome moved = actuator.move(
                new MovementIntent(0.0, 0.0, false, true)
        );
        final ActionOutcome looked = actuator.look(
                lookAt(frame.eyePosition(), target)
        );
        if (!moved.accepted() || !looked.accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        sneakTicks++;
        if (placementTarget == null) {
            return SkillTickResult.running(
                    freshObservation,
                    false
            );
        }

        final double error = angularErrorDegrees(
                frame.lookDirection(),
                target.subtract(frame.eyePosition())
        );
        if (error > INTERACTION_ALIGNMENT_DEGREES
                || sneakTicks < 2) {
            return SkillTickResult.running(
                    freshObservation || error
                            > INTERACTION_ALIGNMENT_DEGREES,
                    false
            );
        }
        final BridgeMaterialResult material =
                materials.ensureEquipped();
        if (!material.ready()
                || !material.itemId().equals(materialItemId)) {
            return fail(material.failureCode().orElse(
                    NAME + ".material_changed"
            ));
        }
        final ActionOutcome used =
                actuator.useMainHandOn(placementTarget);
        if (!used.accepted()) {
            placementTarget = null;
            return SkillTickResult.running(true, false);
        }
        beginPhase(Phase.VERIFYING, context, frame);
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyPlacement(
            final SkillContext context,
            final BridgeToParameters parameters,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        if (!nearCurrentStep(frame)) {
            return fail(NAME + ".placement_position_lost");
        }
        final PerceptionVec3 supportCenter = new PerceptionVec3(
                desiredSupport.x() + 0.5,
                desiredSupport.y() + 0.75,
                desiredSupport.z() + 0.5
        );
        final ActionOutcome moved = actuator.move(
                new MovementIntent(0.0, 0.0, false, true)
        );
        final ActionOutcome looked = actuator.look(
                lookAt(frame.eyePosition(), supportCenter)
        );
        if (!moved.accepted() || !looked.accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        final Optional<ObservedVoxel> support =
                frame.navigation().voxelAt(desiredSupport);
        final boolean newlyVerified = support.isPresent()
                && support.orElseThrow().kind().supportsWeight()
                && support.orElseThrow().observationRevision()
                    > phaseObservationRevision;
        if (newlyVerified) {
            final int countAfter =
                    inventoryCount(frame, materialItemId);
            if (countAfter >= materialCountBeforePlacement) {
                return fail(NAME + ".item_consumption_unverified");
            }
            if (materialCountBeforePlacement - countAfter != 1) {
                return fail(NAME + ".unexpected_material_consumption");
            }
            blocksPlaced++;
            beginPhase(Phase.CROSSING, context, frame);
            return cross(
                    context,
                    parameters,
                    frame,
                    true
            );
        }
        if (context.gameTick() - phaseStartedAtTick
                >= PLACEMENT_CONFIRMATION_TICKS) {
            return fail(NAME + ".placement_unverified");
        }
        return SkillTickResult.running(freshObservation, false);
    }

    private SkillTickResult cross(
            final SkillContext context,
            final BridgeToParameters parameters,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        if (!isObservedSupport(frame, desiredSupport)) {
            return fail(NAME + ".support_confirmation_lost");
        }
        if (frame.feet().equals(stepTo)
                && distanceToCellCenter(frame.position(), stepTo)
                    <= 0.34) {
            if (!actuator.stop().accepted()) {
                return fail(NAME + ".actuator_rejected");
            }
            clearStep();
            phase = Phase.READY;
            return arrived(parameters, frame)
                    ? complete()
                    : SkillTickResult.running(true, true);
        }
        if (!nearCurrentStep(frame)) {
            return fail(NAME + ".crossing_position_lost");
        }

        final PerceptionVec3 target = new PerceptionVec3(
                stepTo.x() + 0.5,
                frame.eyePosition().y(),
                stepTo.z() + 0.5
        );
        final ActionOutcome looked = actuator.look(
                lookAt(frame.eyePosition(), target)
        );
        final double error = horizontalAngularErrorDegrees(
                frame.lookDirection(),
                target.subtract(frame.eyePosition())
        );
        final ActionOutcome moved = actuator.move(new MovementIntent(
                error <= MOVEMENT_ALIGNMENT_DEGREES ? 0.65 : 0.0,
                0.0,
                false,
                false
        ));
        if (!looked.accepted() || !moved.accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        return SkillTickResult.running(
                freshObservation || error
                        > MOVEMENT_ALIGNMENT_DEGREES,
                false
        );
    }

    private SkillTickResult complete() {
        quiesce();
        phase = Phase.COMPLETED;
        clearStep();
        return SkillTickResult.completed();
    }

    private Optional<BlockInteractionTarget> visiblePlacementTarget(
            final CoreSkillFrame frame
    ) {
        for (VisibleBlockFace visible :
                frame.visibleBlockFaces()) {
            final Optional<BlockFace> parsed =
                    parseFace(visible.face());
            if (parsed.isEmpty()) {
                continue;
            }
            final BlockFace face = parsed.orElseThrow();
            final GridPos clicked = new GridPos(
                    visible.block().x(),
                    visible.block().y(),
                    visible.block().z()
            );
            final GridPos adjacent = offset(clicked, face);
            if (!adjacent.equals(desiredSupport)) {
                continue;
            }
            try {
                return Optional.of(new BlockInteractionTarget(
                        clicked.x(),
                        clicked.y(),
                        clicked.z(),
                        face,
                        new ActionVec3(
                                visible.hitPosition().x(),
                                visible.hitPosition().y(),
                                visible.hitPosition().z()
                        )
                ));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Optional<BlockInteractionTarget> visibleAttachmentTarget(
            final CoreSkillFrame frame,
            final GridPos destination
    ) {
        final Optional<ObservedVoxel> existing = frame.navigation()
                .voxelAt(destination);
        if (existing.isPresent()
                && !existing.orElseThrow().kind().isPassable()) {
            return Optional.empty();
        }
        for (VisibleBlockFace visible : frame.visibleBlockFaces()) {
            if (visible.collisionAffordance()
                    == CollisionAffordance.EMPTY) {
                continue;
            }
            final Optional<BlockFace> parsed = parseFace(visible.face());
            if (parsed.isEmpty()) {
                continue;
            }
            final BlockFace face = parsed.orElseThrow();
            final GridPos clicked = new GridPos(
                    visible.block().x(),
                    visible.block().y(),
                    visible.block().z()
            );
            if (!offset(clicked, face).equals(destination)) {
                continue;
            }
            try {
                return Optional.of(new BlockInteractionTarget(
                        clicked.x(),
                        clicked.y(),
                        clicked.z(),
                        face,
                        new ActionVec3(
                                visible.hitPosition().x(),
                                visible.hitPosition().y(),
                                visible.hitPosition().z()
                        )
                ));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private PerceptionVec3 syntheticSideCenter() {
        final GridPos clicked = stepFrom.below();
        return new PerceptionVec3(
                clicked.x() + 0.5
                        + stepDirection.deltaX * 0.5,
                clicked.y() + 0.5,
                clicked.z() + 0.5
                        + stepDirection.deltaZ * 0.5
        );
    }

    private static Optional<BlockFace> parseFace(
            final String value
    ) {
        final int separator = value.lastIndexOf(':');
        final String name = separator >= 0
                ? value.substring(separator + 1)
                : value;
        try {
            return Optional.of(BlockFace.valueOf(
                    name.toUpperCase(Locale.ROOT)
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static GridPos offset(
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

    private Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        if (context.riskScore() > maximumDanger
                || frame.danger() > maximumDanger) {
            return Optional.of(SkillFailure.of(
                    NAME + ".danger_detected"
            ));
        }
        final double minimumHealth = context.hardcore()
                ? HARDCORE_MINIMUM_HEALTH_RATIO
                : NORMAL_MINIMUM_HEALTH_RATIO;
        if (frame.health() / frame.maxHealth() < minimumHealth) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_reserve_required"
            ));
        }
        if (frame.foodLevel() < 6) {
            return Optional.of(SkillFailure.of(
                    NAME + ".food_reserve_required"
            ));
        }
        return Optional.empty();
    }

    private FrameValidation validateFrame(
            final BridgeToParameters parameters
    ) {
        final Optional<CoreSkillFrame> current =
                frames.current();
        if (current.isEmpty()) {
            return FrameValidation.failed(
                    NAME + ".observation_unavailable"
            );
        }
        final CoreSkillFrame frame = current.orElseThrow();
        if (!frame.playerId().equals(expectedPlayerId)) {
            return FrameValidation.failed(
                    NAME + ".player_mismatch"
            );
        }
        if (!frame.dimension().equals(parameters.dimension())) {
            return FrameValidation.failed(
                    NAME + ".dimension_mismatch"
            );
        }
        return FrameValidation.valid(frame);
    }

    private boolean holdStill(final boolean sneak) {
        return actuator.move(new MovementIntent(
                0.0,
                0.0,
                false,
                sneak
        )).accepted();
    }

    private void beginPhase(
            final Phase next,
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        phase = next;
        phaseStartedAtTick = context.gameTick();
        phaseObservationRevision = frame.observationRevision();
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        quiesce();
        failure = Objects.requireNonNull(reason, "reason");
        phase = Phase.FAILED;
        clearStep();
        return SkillTickResult.failed(failure);
    }

    private void quiesce() {
        actuator.stop();
        actuator.releaseUse();
    }

    private void clearStep() {
        stepFrom = null;
        stepTo = null;
        desiredSupport = null;
        stepDirection = null;
        placementTarget = null;
        materialItemId = null;
        materialCountBeforePlacement = 0;
        edgeTicks = 0;
        alignmentTicks = 0;
        sneakTicks = 0;
    }

    private static boolean observedPassable(
            final CoreSkillFrame frame,
            final GridPos position
    ) {
        return frame.navigation().voxelAt(position)
                .map(ObservedVoxel::kind)
                .map(VoxelKind::isPassable)
                .orElse(false);
    }

    private static boolean isObservedSupport(
            final CoreSkillFrame frame,
            final GridPos position
    ) {
        return frame.navigation().voxelAt(position)
                .map(ObservedVoxel::kind)
                .map(VoxelKind::supportsWeight)
                .orElse(false);
    }

    private static int inventoryCount(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().equals(itemId))
                .mapToInt(item -> item.count())
                .sum();
    }

    private static boolean arrived(
            final BridgeToParameters parameters,
            final CoreSkillFrame frame
    ) {
        return Math.abs(parameters.y() - frame.position().y())
                    <= MAXIMUM_VERTICAL_DIFFERENCE
                && horizontalDistance(
                        parameters.target(),
                        frame.position()
                ) <= parameters.arrivalRadius();
    }

    private static Direction2D directionToward(
            final PerceptionVec3 from,
            final PerceptionVec3 target
    ) {
        final double deltaX = target.x() - from.x();
        final double deltaZ = target.z() - from.z();
        if (Math.abs(deltaX) <= 0.05
                && Math.abs(deltaZ) <= 0.05) {
            return null;
        }
        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            return deltaX >= 0.0
                    ? Direction2D.EAST
                    : Direction2D.WEST;
        }
        return deltaZ >= 0.0
                ? Direction2D.SOUTH
                : Direction2D.NORTH;
    }

    private static PerceptionVec3 horizontalTarget(
            final CoreSkillFrame frame,
            final Direction2D direction
    ) {
        return new PerceptionVec3(
                frame.eyePosition().x()
                        + direction.deltaX * 4.0,
                frame.eyePosition().y(),
                frame.eyePosition().z()
                        + direction.deltaZ * 4.0
        );
    }

    private static boolean pastEdge(
            final PerceptionVec3 position,
            final GridPos from,
            final Direction2D direction
    ) {
        return switch (direction) {
            case EAST -> position.x()
                    >= from.x() + 1.0 + EDGE_OVERSHOOT;
            case WEST -> position.x()
                    <= from.x() - EDGE_OVERSHOOT;
            case SOUTH -> position.z()
                    >= from.z() + 1.0 + EDGE_OVERSHOOT;
            case NORTH -> position.z()
                    <= from.z() - EDGE_OVERSHOOT;
        };
    }

    private static boolean pastMaximumEdge(
            final PerceptionVec3 position,
            final GridPos from,
            final Direction2D direction
    ) {
        return switch (direction) {
            case EAST -> position.x()
                    > from.x() + 1.0 + MAXIMUM_EDGE_OVERSHOOT;
            case WEST -> position.x()
                    < from.x() - MAXIMUM_EDGE_OVERSHOOT;
            case SOUTH -> position.z()
                    > from.z() + 1.0 + MAXIMUM_EDGE_OVERSHOOT;
            case NORTH -> position.z()
                    < from.z() - MAXIMUM_EDGE_OVERSHOOT;
        };
    }

    private boolean nearCurrentStep(final CoreSkillFrame frame) {
        final GridPos feet = frame.feet();
        return feet.equals(stepFrom) || feet.equals(stepTo);
    }

    private static double distanceToCellCenter(
            final PerceptionVec3 position,
            final GridPos cell
    ) {
        return Math.hypot(
                position.x() - (cell.x() + 0.5),
                position.z() - (cell.z() + 0.5)
        );
    }

    private static double horizontalDistance(
            final PerceptionVec3 first,
            final PerceptionVec3 second
    ) {
        return Math.hypot(
                first.x() - second.x(),
                first.z() - second.z()
        );
    }

    private static LookIntent lookAt(
            final PerceptionVec3 eye,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta = target.subtract(eye);
        if (delta.lengthSquared() <= 1.0E-12) {
            throw new IllegalArgumentException(
                    "Look target coincides with eye"
            );
        }
        return new LookIntent(
                (float) Math.toDegrees(
                        Math.atan2(-delta.x(), delta.z())
                ),
                (float) Math.toDegrees(Math.atan2(
                        -delta.y(),
                        Math.hypot(delta.x(), delta.z())
                ))
        );
    }

    private static double angularErrorDegrees(
            final PerceptionVec3 current,
            final PerceptionVec3 target
    ) {
        if (target.lengthSquared() <= 1.0E-12) {
            return 0.0;
        }
        final double dot = current.normalized()
                .dot(target.normalized());
        return Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
    }

    private static double horizontalAngularErrorDegrees(
            final PerceptionVec3 current,
            final PerceptionVec3 target
    ) {
        final double currentLength =
                Math.hypot(current.x(), current.z());
        final double targetLength =
                Math.hypot(target.x(), target.z());
        if (targetLength <= 1.0E-12) {
            return 0.0;
        }
        if (currentLength <= 1.0E-12) {
            return 180.0;
        }
        final double dot = (
                current.x() * target.x()
                        + current.z() * target.z()
        ) / (currentLength * targetLength);
        return Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
    }

    private enum Phase {
        IDLE,
        READY,
        SCANNING,
        CROSSING,
        APPROACHING_EDGE,
        ALIGNING,
        VERIFYING,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return this == READY
                    || this == SCANNING
                    || this == CROSSING
                    || this == APPROACHING_EDGE
                    || this == ALIGNING
                    || this == VERIFYING;
        }
    }

    private enum Direction2D {
        NORTH(0, -1),
        SOUTH(0, 1),
        WEST(-1, 0),
        EAST(1, 0);

        private final int deltaX;
        private final int deltaZ;

        Direction2D(
                final int deltaX,
                final int deltaZ
        ) {
            this.deltaX = deltaX;
            this.deltaZ = deltaZ;
        }
    }

    private record FrameValidation(
            Optional<CoreSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        private static FrameValidation valid(
                final CoreSkillFrame frame
        ) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.empty()
            );
        }

        private static FrameValidation failed(
                final String code
        ) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }
}
