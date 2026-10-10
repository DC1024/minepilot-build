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
 * Performs bounded vertical pillar-jumping with normal jump and use packets.
 */
public final class TowerUpSkill
        implements Skill<TowerUpParameters> {
    public static final String NAME = "tower_up";

    private static final double NORMAL_MAXIMUM_DANGER = 0.18;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.06;
    private static final double NORMAL_MINIMUM_HEALTH_RATIO = 0.60;
    private static final double HARDCORE_MINIMUM_HEALTH_RATIO = 0.85;
    private static final double INTERACTION_ALIGNMENT_DEGREES = 2.5;
    private static final double MAXIMUM_HORIZONTAL_DRIFT = 0.28;
    private static final double PLACEMENT_HEIGHT = 1.01;
    private static final int MAXIMUM_SCAN_SAMPLES = 8;
    private static final int MAXIMUM_ALIGNMENT_TICKS = 80;
    private static final int MAXIMUM_RISING_TICKS = 35;
    private static final int MAXIMUM_VERIFICATION_TICKS = 70;
    private static final int BASE_TIMEOUT_TICKS = 200;
    private static final int TIMEOUT_TICKS_PER_BLOCK = 120;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;
    private final BridgeMaterialActuator materials;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long phaseStartedAtTick = -1;
    private long phaseObservationRevision = -1;
    private long lastObservationRevision = -1;
    private double columnX;
    private double columnZ;
    private double baseY;
    private int blocksPlaced;
    private int scanSamples;
    private int alignmentTicks;
    private GridPos intendedBlock;
    private GridPos clickedSupport;
    private BlockInteractionTarget placementTarget;
    private String materialItemId;
    private int materialCountBeforePlacement;

    public TowerUpSkill(
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
    public SkillParameterParser<TowerUpParameters> parameters() {
        return TowerSkillParameters::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final TowerUpParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final FrameValidation validation =
                validateFrame(parameters);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        final CoreSkillFrame frame =
                validation.frame().orElseThrow();
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
        if (parameters.targetY()
                < frame.position().y()
                    - parameters.arrivalTolerance()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".upward_target_required"
            ));
        }
        if (requiredBlocks(
                frame.position().y(),
                parameters
        ) > parameters.maxBlocks()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".block_budget_insufficient"
            ));
        }
        if (!isObservedSupport(
                frame,
                frame.feet().below()
        )) {
            return Optional.of(SkillFailure.of(
                    NAME + ".current_support_unverified"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final TowerUpParameters parameters
    ) {
        final CoreSkillFrame frame = validateFrame(parameters)
                .frame()
                .orElseThrow(() -> new IllegalStateException(
                        "Tower body changed before start"
                ));
        phase = Phase.READY;
        failure = null;
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        phaseObservationRevision = frame.observationRevision();
        lastObservationRevision = -1;
        columnX = frame.position().x();
        columnZ = frame.position().z();
        baseY = frame.position().y();
        blocksPlaced = 0;
        scanSamples = 0;
        alignmentTicks = 0;
        clearAttempt();
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final TowerUpParameters parameters
    ) {
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
            final TowerUpParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"targetY\":%.3f,"
                                + "\"blocksPlaced\":%d,"
                                + "\"maxBlocks\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.targetY(),
                        blocksPlaced,
                        parameters.maxBlocks()
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final TowerUpParameters parameters
    ) {
        quiesce();
        phase = Phase.CANCELLED;
        clearAttempt();
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final TowerUpParameters parameters
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
            final TowerUpParameters parameters
    ) {
        if (context.gameTick() - startedAtTick
                >= BASE_TIMEOUT_TICKS
                    + (long) parameters.maxBlocks()
                    * TIMEOUT_TICKS_PER_BLOCK) {
            return fail(NAME + ".timed_out");
        }
        final FrameValidation validation =
                validateFrame(parameters);
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final CoreSkillFrame frame =
                validation.frame().orElseThrow();
        if (frame.observationRevision()
                < lastObservationRevision) {
            return fail(NAME + ".stale_observation");
        }
        final boolean freshObservation =
                frame.observationRevision()
                    > lastObservationRevision;
        if (freshObservation) {
            lastObservationRevision =
                    frame.observationRevision();
        }
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return fail(unsafe.orElseThrow());
        }
        if (frame.inWater()) {
            return fail(NAME + ".entered_water");
        }
        if (horizontalDistance(frame.position())
                > MAXIMUM_HORIZONTAL_DRIFT) {
            return fail(NAME + ".horizontal_drift");
        }

        return switch (phase) {
            case READY -> prepareAttempt(
                    context,
                    parameters,
                    frame,
                    freshObservation
            );
            case SCANNING_UP -> scanUp(
                    context,
                    parameters,
                    frame,
                    freshObservation
            );
            case ALIGNING_SUPPORT -> alignSupport(
                    context,
                    frame,
                    freshObservation
            );
            case RISING -> riseAndPlace(
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

    private SkillTickResult prepareAttempt(
            final SkillContext context,
            final TowerUpParameters parameters,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        if (!frame.onGround()) {
            return fail(NAME + ".stable_ground_required");
        }
        if (arrived(parameters, frame)) {
            return complete();
        }
        final int remaining =
                parameters.maxBlocks() - blocksPlaced;
        if (requiredBlocks(
                frame.position().y(),
                parameters
        ) > remaining) {
            return fail(NAME + ".block_budget_exhausted");
        }
        intendedBlock = frame.feet();
        clickedSupport = intendedBlock.below();
        if (!isObservedSupport(frame, clickedSupport)) {
            return fail(NAME + ".current_support_unverified");
        }
        final GridPos clearance = intendedBlock.above(2);
        if (!observedPassable(frame, clearance)) {
            beginPhase(
                    Phase.SCANNING_UP,
                    context,
                    frame
            );
            scanSamples = 0;
            return scanUp(
                    context,
                    parameters,
                    frame,
                    freshObservation
            );
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
            materialCountBeforePlacement =
                    material.availableCount();
        }
        placementTarget = null;
        alignmentTicks = 0;
        beginPhase(
                Phase.ALIGNING_SUPPORT,
                context,
                frame
        );
        return alignSupport(
                context,
                frame,
                freshObservation
        );
    }

    private SkillTickResult scanUp(
            final SkillContext context,
            final TowerUpParameters parameters,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        if (!frame.onGround()) {
            return fail(NAME + ".stable_ground_required");
        }
        if (freshObservation
                && frame.observationRevision()
                    > phaseObservationRevision) {
            scanSamples++;
            phaseObservationRevision =
                    frame.observationRevision();
            if (observedPassable(
                    frame,
                    frame.feet().above(2)
            )) {
                phase = Phase.READY;
                return SkillTickResult.running(true, true);
            }
        }
        if (scanSamples >= MAXIMUM_SCAN_SAMPLES) {
            return fail(NAME + ".overhead_clearance_unverified");
        }
        if (!actuator.move(MovementIntent.STOPPED).accepted()
                || !actuator.look(new LookIntent(
                    frame.lookDirection().x() >= 0.0
                        ? -90.0F
                        : 90.0F,
                    -89.0F
                )).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        return SkillTickResult.running(
                freshObservation,
                true
        );
    }

    private SkillTickResult alignSupport(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        if (!frame.onGround()
                || !frame.feet().equals(intendedBlock)) {
            return fail(NAME + ".launch_position_lost");
        }
        alignmentTicks++;
        if (alignmentTicks > MAXIMUM_ALIGNMENT_TICKS) {
            return fail(NAME + ".visible_support_face_unavailable");
        }
        if (placementTarget == null) {
            placementTarget = visibleTopFace(frame)
                    .orElse(null);
        }
        final PerceptionVec3 target =
                placementTarget == null
                    ? topCenter(clickedSupport)
                    : hit(placementTarget);
        if (!actuator.move(MovementIntent.STOPPED).accepted()
                || !actuator.look(
                    lookAt(frame.eyePosition(), target)
                ).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        if (placementTarget == null) {
            return SkillTickResult.running(
                    freshObservation,
                    true
            );
        }
        final double error = angularErrorDegrees(
                frame.lookDirection(),
                target.subtract(frame.eyePosition())
        );
        if (error > INTERACTION_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, true);
        }
        if (!actuator.jump().accepted()) {
            return fail(NAME + ".jump_rejected");
        }
        baseY = frame.position().y();
        beginPhase(Phase.RISING, context, frame);
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult riseAndPlace(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        if (context.gameTick() - phaseStartedAtTick
                > MAXIMUM_RISING_TICKS) {
            return fail(NAME + ".jump_timed_out");
        }
        final PerceptionVec3 target = hit(placementTarget);
        if (!actuator.move(MovementIntent.STOPPED).accepted()
                || !actuator.look(
                    lookAt(frame.eyePosition(), target)
                ).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        if (frame.position().y() - baseY
                < PLACEMENT_HEIGHT) {
            if (context.gameTick() - phaseStartedAtTick > 4
                    && frame.onGround()) {
                return fail(NAME + ".jump_did_not_rise");
            }
            return SkillTickResult.running(
                    freshObservation,
                    false
            );
        }
        final double error = angularErrorDegrees(
                frame.lookDirection(),
                target.subtract(frame.eyePosition())
        );
        if (error > INTERACTION_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
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
            return fail(NAME + ".placement_rejected");
        }
        beginPhase(Phase.VERIFYING, context, frame);
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyPlacement(
            final SkillContext context,
            final TowerUpParameters parameters,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        final PerceptionVec3 target =
                new PerceptionVec3(
                    intendedBlock.x() + 0.5,
                    intendedBlock.y() + 1.0,
                    intendedBlock.z() + 0.5
                );
        if (!actuator.move(MovementIntent.STOPPED).accepted()
                || !actuator.look(
                    lookAt(frame.eyePosition(), target)
                ).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        final Optional<ObservedVoxel> placed =
                frame.navigation().voxelAt(intendedBlock);
        final boolean verified = placed.isPresent()
                && placed.orElseThrow().kind().supportsWeight()
                && placed.orElseThrow().observationRevision()
                    > phaseObservationRevision;
        if (verified
                && frame.onGround()
                && frame.position().y() >= baseY + 0.98) {
            final int countAfter =
                    inventoryCount(frame, materialItemId);
            if (countAfter >= materialCountBeforePlacement) {
                return fail(NAME + ".item_consumption_unverified");
            }
            if (materialCountBeforePlacement - countAfter != 1) {
                return fail(NAME + ".unexpected_material_consumption");
            }
            blocksPlaced++;
            clearAttempt();
            phase = Phase.READY;
            return arrived(parameters, frame)
                    ? complete()
                    : SkillTickResult.running(true, true);
        }
        if (context.gameTick() - phaseStartedAtTick
                > MAXIMUM_VERIFICATION_TICKS) {
            return fail(NAME + ".placement_unverified");
        }
        return SkillTickResult.running(
                freshObservation,
                false
        );
    }

    private Optional<BlockInteractionTarget> visibleTopFace(
            final CoreSkillFrame frame
    ) {
        for (VisibleBlockFace visible :
                frame.visibleBlockFaces()) {
            if (visible.block().x() != clickedSupport.x()
                    || visible.block().y()
                        != clickedSupport.y()
                    || visible.block().z()
                        != clickedSupport.z()
                    || !faceName(visible.face()).equals("up")) {
                continue;
            }
            try {
                return Optional.of(new BlockInteractionTarget(
                    clickedSupport.x(),
                    clickedSupport.y(),
                    clickedSupport.z(),
                    BlockFace.UP,
                    new ActionVec3(
                        visible.hitPosition().x(),
                        visible.hitPosition().y(),
                        visible.hitPosition().z()
                    )
                ));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }
        return Optional.empty();
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
        if (frame.health() / frame.maxHealth()
                < minimumHealth) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_reserve_required"
            ));
        }
        if (frame.foodLevel() < 8) {
            return Optional.of(SkillFailure.of(
                    NAME + ".food_reserve_required"
            ));
        }
        return Optional.empty();
    }

    private FrameValidation validateFrame(
            final TowerUpParameters parameters
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

    private void beginPhase(
            final Phase next,
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        phase = next;
        phaseStartedAtTick = context.gameTick();
        phaseObservationRevision =
                frame.observationRevision();
    }

    private SkillTickResult complete() {
        quiesce();
        phase = Phase.COMPLETED;
        clearAttempt();
        return SkillTickResult.completed();
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        quiesce();
        failure = Objects.requireNonNull(reason, "reason");
        phase = Phase.FAILED;
        clearAttempt();
        return SkillTickResult.failed(failure);
    }

    private void quiesce() {
        actuator.stop();
        actuator.releaseUse();
    }

    private void clearAttempt() {
        intendedBlock = null;
        clickedSupport = null;
        placementTarget = null;
        materialItemId = null;
        materialCountBeforePlacement = 0;
        alignmentTicks = 0;
        scanSamples = 0;
    }

    private boolean arrived(
            final TowerUpParameters parameters,
            final CoreSkillFrame frame
    ) {
        return frame.onGround()
                && frame.position().y()
                    >= parameters.targetY()
                        - parameters.arrivalTolerance()
                && isObservedSupport(
                    frame,
                    frame.feet().below()
                );
    }

    private static int requiredBlocks(
            final double currentY,
            final TowerUpParameters parameters
    ) {
        return Math.max(
            0,
            (int) Math.ceil(
                parameters.targetY()
                    - parameters.arrivalTolerance()
                    - currentY
            )
        );
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

    private double horizontalDistance(
            final PerceptionVec3 position
    ) {
        return Math.hypot(
            position.x() - columnX,
            position.z() - columnZ
        );
    }

    private static String faceName(final String face) {
        final int separator = face.lastIndexOf(':');
        return (separator >= 0
                ? face.substring(separator + 1)
                : face).toLowerCase(Locale.ROOT);
    }

    private static PerceptionVec3 topCenter(
            final GridPos support
    ) {
        return new PerceptionVec3(
            support.x() + 0.5,
            support.y() + 1.0,
            support.z() + 0.5
        );
    }

    private static PerceptionVec3 hit(
            final BlockInteractionTarget target
    ) {
        return new PerceptionVec3(
            target.hitPoint().x(),
            target.hitPoint().y(),
            target.hitPoint().z()
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

    private enum Phase {
        IDLE,
        READY,
        SCANNING_UP,
        ALIGNING_SUPPORT,
        RISING,
        VERIFYING,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return this == READY
                || this == SCANNING_UP
                || this == ALIGNING_SUPPORT
                || this == RISING
                || this == VERIFYING;
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
