package dev.mcai.companion.skills.bridging;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.DangerKind;
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
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Deliberately steps from a verified ledge toward a visible landing, predicts
 * horizontal inertia from consecutive self poses, and sends one ordinary
 * use-item action for the owned water bucket once the exact observed support
 * face is aligned and in reach. The production emergency controller remains
 * an independent final-tick fallback. Neither path edits position, inventory,
 * or world state directly.
 */
public final class WaterClutchDescendSkill
        implements Skill<WaterClutchDescendParameters> {
    public static final String NAME = "water_clutch_descend";

    private static final String WATER_BUCKET =
            "minecraft:water_bucket";
    private static final String EMPTY_BUCKET = "minecraft:bucket";
    private static final double MINIMUM_DROP = 3.5;
    private static final double MAXIMUM_HORIZONTAL_STEP = 1.8;
    private static final double MINIMUM_HORIZONTAL_STEP = 0.55;
    private static final double ALIGNMENT_DEGREES = 4.0;
    private static final double NORMAL_MAXIMUM_DANGER = 0.12;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.04;
    private static final double NORMAL_MINIMUM_HEALTH_RATIO = 0.80;
    private static final double HARDCORE_MINIMUM_HEALTH_RATIO = 0.95;
    private static final int ALIGNMENT_TIMEOUT_TICKS = 80;
    private static final int STEP_TIMEOUT_TICKS = 50;
    private static final int DRY_LANDING_GRACE_TICKS = 2;
    private static final int BASE_TIMEOUT_TICKS = 120;
    private static final int TIMEOUT_TICKS_PER_BLOCK = 12;
    private static final double MAXIMUM_WATER_REACH = 4.75;
    private static final double MAXIMUM_CLUTCH_COLUMN_OFFSET = 1.45;
    private static final double WATER_ALIGNMENT_DEGREES = 5.0;
    private static final double STEP_FORWARD_INPUT = 0.35;
    private static final double AIRBORNE_HORIZONTAL_DRAG = 0.91;
    private static final double VANILLA_VERTICAL_DRAG = 0.98;
    private static final double VANILLA_GRAVITY = 0.08;
    private static final double BODY_HALF_WIDTH = 0.30;
    private static final double BODY_HEIGHT = 1.80;
    private static final double COLLISION_EPSILON = 1.0E-4;
    private static final double SAFE_LANDING_CENTER_RADIUS = 0.16;
    private static final double EXIT_CORRIDOR_PROBE_OFFSET = 0.26;
    private static final int MAXIMUM_PREDICTION_TICKS = 40;
    private static final int MAXIMUM_POSE_SAMPLE_GAP_TICKS = 5;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long phaseStartedAtTick = -1;
    private long lastObservationRevision = -1;
    private double startingY;
    private float startingHealth;
    private int startingWaterBuckets;
    private int startingEmptyBuckets;
    private boolean initialLandingVerified;
    private boolean waterUseRequested;
    private long dryLandingObservedAtTick = -1;
    private PerceptionVec3 previousPosition;
    private long previousPoseGameTime = -1;
    private MotionEstimate motionEstimate = MotionEstimate.unavailable();

    public WaterClutchDescendSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(
                actuator,
                "actuator"
        );
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public SkillParameterParser<WaterClutchDescendParameters>
            parameters() {
        return WaterClutchDescendSkillParameters::parse;
    }

    /**
     * The fall is the action this skill deliberately supervises. Only that
     * one BODY_HAZARD may cross the global Hardcore ceiling; fire, low air,
     * combat/contact danger, degraded health, a lost trajectory, or a
     * no-longer-verified landing keep the normal fail-closed threshold.
     */
    @Override
    public OptionalDouble hardcoreRiskThresholdOverride(
            final SkillContext context,
            final WaterClutchDescendParameters parameters
    ) {
        if (!context.hardcore()
                || phase != Phase.STEPPING_OFF
                    && phase != Phase.DESCENDING) {
            return OptionalDouble.empty();
        }
        final FrameValidation validation = validateFrame(parameters);
        if (validation.failure().isPresent()) {
            return OptionalDouble.empty();
        }
        final CoreSkillFrame frame = validation.frame().orElseThrow();
        if (frame.onGround()
                || frame.inWater()
                || safetyFailure(context, frame, true).isPresent()
                || !verifiedLanding(context, frame, parameters)
                || horizontalDistance(frame.position(), parameters)
                    > parameters.arrivalRadius() + 1.25) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(1.0);
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final WaterClutchDescendParameters parameters
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
        if (parameters.dimension().equals(DimensionRef.NETHER)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".water_unavailable_in_nether"
            ));
        }
        if (!frame.onGround() || frame.inWater()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stable_dry_ledge_required"
            ));
        }
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame, false);
        if (unsafe.isPresent()) {
            return unsafe;
        }
        if (inventoryCount(frame, WATER_BUCKET) < 1) {
            return Optional.of(SkillFailure.of(
                    NAME + ".water_bucket_required"
            ));
        }
        final double drop =
                frame.position().y() - parameters.y();
        if (drop < MINIMUM_DROP) {
            return Optional.of(SkillFailure.of(
                    NAME + ".drop_too_short"
            ));
        }
        if (drop > parameters.maximumDropBlocks() + 0.75) {
            return Optional.of(SkillFailure.of(
                    NAME + ".drop_budget_exceeded"
            ));
        }
        final double horizontal = horizontalDistance(
                frame.position(),
                parameters
        );
        if (horizontal < MINIMUM_HORIZONTAL_STEP
                || horizontal > MAXIMUM_HORIZONTAL_STEP) {
            return Optional.of(SkillFailure.of(
                    NAME + ".adjacent_landing_required"
            ));
        }
        if (!verifiedLanding(context, frame, parameters)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".visible_safe_landing_required"
            ));
        }
        final Optional<GridPos> exitObstruction =
                observedExitCorridorObstruction(
                        frame,
                        parameters
                );
        if (exitObstruction.isPresent()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".landing_exit_corridor_obstructed"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final WaterClutchDescendParameters parameters
    ) {
        final CoreSkillFrame frame = validateFrame(parameters)
                .frame()
                .orElseThrow(() -> new IllegalStateException(
                        "Water-clutch body changed before start"
                ));
        startingWaterBuckets =
                inventoryCount(frame, WATER_BUCKET);
        startingEmptyBuckets =
                inventoryCount(frame, EMPTY_BUCKET);
        initialLandingVerified =
                verifiedLanding(context, frame, parameters);
        if (!initialLandingVerified) {
            throw new IllegalStateException(
                    "Water-clutch landing changed before start"
            );
        }
        phase = Phase.ALIGNING;
        failure = null;
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        lastObservationRevision = -1;
        startingY = frame.position().y();
        startingHealth = frame.health();
        waterUseRequested = false;
        dryLandingObservedAtTick = -1;
        previousPosition = frame.position();
        previousPoseGameTime = frame.gameTime();
        motionEstimate = MotionEstimate.unavailable();
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final WaterClutchDescendParameters parameters
    ) {
        if (!phase.active()) {
            return SkillTickResult.failed(
                    NAME + ".invalid_state"
            );
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
            final WaterClutchDescendParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                            + "\"landing\":[%.3f,%.3f,%.3f],"
                            + "\"maximumDropBlocks\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.x(),
                        parameters.y(),
                        parameters.z(),
                        parameters.maximumDropBlocks()
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final WaterClutchDescendParameters parameters
    ) {
        quiesce();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final WaterClutchDescendParameters parameters
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
            final WaterClutchDescendParameters parameters
    ) {
        if (context.gameTick() - startedAtTick
                >= BASE_TIMEOUT_TICKS
                    + (long) parameters.maximumDropBlocks()
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
        final boolean fresh = frame.observationRevision()
                > lastObservationRevision;
        lastObservationRevision = Math.max(
                lastObservationRevision,
                frame.observationRevision()
        );
        final MotionEstimate motion = updateMotionEstimate(frame);
        if (frame.health() + 1.0E-4F < startingHealth) {
            return fail(NAME + ".fall_damage_taken");
        }
        if (completedDescent(frame, parameters)) {
            quiesce();
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        final boolean intentionalFall =
                !frame.onGround()
                    && !frame.inWater()
                    && (phase == Phase.STEPPING_OFF
                        || phase == Phase.DESCENDING);
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame, intentionalFall);
        if (unsafe.isPresent()) {
            return fail(unsafe.orElseThrow());
        }
        if ((phase == Phase.STEPPING_OFF
                || phase == Phase.DESCENDING)
                && !verifiedLanding(context, frame, parameters)) {
            return fail(NAME + ".landing_became_unverified");
        }
        if (frame.onGround()
                && frame.position().y()
                    < startingY - 0.75
                && !frame.inWater()) {
            /*
             * The emergency controller owns the final actuator lease and
             * runs immediately after this skill in the same server tick.  A
             * one-frame dry-contact flag can therefore precede its legal
             * bucket packet even though the water is present before the next
             * physics step.  Keep a tiny grace window; real fall damage is
             * still caught by the health check above, and a genuinely dry
             * landing fails on the following ticks.
             */
            if (dryLandingObservedAtTick < 0) {
                dryLandingObservedAtTick = context.gameTick();
                return SkillTickResult.running(true, false);
            }
            if (context.gameTick() - dryLandingObservedAtTick
                    > DRY_LANDING_GRACE_TICKS) {
                return fail(NAME + ".landed_without_water");
            }
            return SkillTickResult.running(false, false);
        }
        dryLandingObservedAtTick = -1;
        return switch (phase) {
            case ALIGNING -> align(
                    context,
                    parameters,
                    frame,
                    fresh
            );
            case STEPPING_OFF -> stepOff(
                    context,
                    parameters,
                    frame,
                    fresh
            );
            case DESCENDING -> descend(
                    parameters,
                    frame,
                    fresh,
                    motion
            );
            default -> SkillTickResult.failed(
                    NAME + ".invalid_state"
            );
        };
    }

    private SkillTickResult align(
            final SkillContext context,
            final WaterClutchDescendParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (!frame.onGround()) {
            phase = Phase.DESCENDING;
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, false);
        }
        if (context.gameTick() - phaseStartedAtTick
                > ALIGNMENT_TIMEOUT_TICKS) {
            return fail(NAME + ".alignment_timed_out");
        }
        if (!verifiedLanding(context, frame, parameters)) {
            return fail(NAME + ".landing_became_unverified");
        }
        if (observedExitCorridorObstruction(
                frame,
                parameters
        ).isPresent()) {
            return fail(
                    NAME + ".landing_exit_corridor_obstructed"
            );
        }
        final PerceptionVec3 target =
                landingTop(parameters);
        if (!actuator.move(new MovementIntent(
                0.0,
                0.0,
                false,
                true
        )).accepted()
                || !actuator.look(
                        lookAt(frame.eyePosition(), target)
                ).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        if (angularErrorDegrees(
                frame.lookDirection(),
                target.subtract(frame.eyePosition())
        ) <= ALIGNMENT_DEGREES) {
            phase = Phase.STEPPING_OFF;
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, false);
        }
        return SkillTickResult.running(fresh, true);
    }

    private SkillTickResult stepOff(
            final SkillContext context,
            final WaterClutchDescendParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (!frame.onGround()
                || frame.position().y() < startingY - 0.1) {
            actuator.move(MovementIntent.STOPPED);
            phase = Phase.DESCENDING;
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, false);
        }
        if (context.gameTick() - phaseStartedAtTick
                > STEP_TIMEOUT_TICKS) {
            return fail(NAME + ".ledge_exit_timed_out");
        }
        if (observedExitCorridorObstruction(
                frame,
                parameters
        ).isPresent()) {
            return fail(
                    NAME + ".landing_exit_corridor_obstructed"
            );
        }
        final PerceptionVec3 target =
                landingTop(parameters);
        if (!actuator.look(
                lookAt(frame.eyePosition(), target)
        ).accepted()
                || !actuator.move(new MovementIntent(
                        STEP_FORWARD_INPUT,
                        0.0,
                        false,
                        false
                )).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        return SkillTickResult.running(fresh, false);
    }

    private SkillTickResult descend(
            final WaterClutchDescendParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh,
            final MotionEstimate motion
    ) {
        final double horizontal =
                horizontalDistance(frame.position(), parameters);
        if (horizontal > parameters.arrivalRadius() + 1.25) {
            return fail(NAME + ".fall_trajectory_lost");
        }
        final TrajectoryPrediction prediction =
                predictTrajectory(frame, parameters, motion);
        final MovementIntent movement = correctiveMovement(
                frame,
                parameters,
                prediction
        );
        final Optional<VisibleBlockFace> landingFace =
                exactLandingSupportFace(frame, parameters);
        if (!waterUseRequested
                && frame.mainHand().itemId().equals(WATER_BUCKET)
                && landingFace.isPresent()) {
            final VisibleBlockFace face =
                    landingFace.orElseThrow();
            final PerceptionVec3 sightline =
                    face.hitPosition().subtract(
                            frame.eyePosition()
                    );
            final double liveReach = sightline.length();
            final double columnOffset = Math.hypot(
                    face.block().x() + 0.5
                        - frame.position().x(),
                    face.block().z() + 0.5
                        - frame.position().z()
            );
            final double lookError = angularErrorDegrees(
                    frame.lookDirection(),
                    sightline
            );
            if (liveReach <= MAXIMUM_WATER_REACH
                    && columnOffset
                        <= MAXIMUM_CLUTCH_COLUMN_OFFSET) {
                if (!actuator.move(movement)
                        .accepted()
                        || !actuator.look(
                                lookAt(
                                        frame.eyePosition(),
                                        face.hitPosition()
                                )
                        ).accepted()) {
                    return fail(NAME + ".actuator_rejected");
                }
                if (lookError <= WATER_ALIGNMENT_DEGREES) {
                    final var use = actuator.useItem(
                            ActionHand.MAIN_HAND
                    );
                    if (!use.accepted()) {
                        return fail(
                                NAME + ".water_use_rejected"
                        );
                    }
                    /*
                     * The ordinary use-item packet is dispatched
                     * synchronously by the fair player actuator. Never issue
                     * it again while semantic inventory/water state catches
                     * up: a second packet could scoop the just-placed source
                     * back into the bucket.
                     */
                    waterUseRequested = true;
                    return SkillTickResult.running(true, false);
                }
                return SkillTickResult.running(fresh, true);
            }
        }
        if (!actuator.move(movement).accepted()
                || !actuator.look(
                        lookAt(
                                frame.eyePosition(),
                                landingTop(parameters)
                        )
                ).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        return SkillTickResult.running(fresh, false);
    }

    /**
     * Estimates only the body's own velocity from consecutive 20 TPS live
     * poses. No world, chunk, entity, or hidden block state is consulted.
     */
    private MotionEstimate updateMotionEstimate(
            final CoreSkillFrame frame
    ) {
        if (previousPosition == null
                || previousPoseGameTime < 0) {
            previousPosition = frame.position();
            previousPoseGameTime = frame.gameTime();
            motionEstimate = MotionEstimate.unavailable();
            return motionEstimate;
        }
        final long elapsed =
                frame.gameTime() - previousPoseGameTime;
        if (elapsed <= 0) {
            return motionEstimate;
        }
        if (elapsed > MAXIMUM_POSE_SAMPLE_GAP_TICKS) {
            motionEstimate = MotionEstimate.unavailable();
        } else {
            final PerceptionVec3 displacement =
                    frame.position().subtract(previousPosition);
            motionEstimate = new MotionEstimate(
                    displacement.x() / elapsed,
                    displacement.y() / elapsed,
                    displacement.z() / elapsed,
                    true
            );
        }
        previousPosition = frame.position();
        previousPoseGameTime = frame.gameTime();
        return motionEstimate;
    }

    /**
     * Projects the uncorrected vanilla fall from self velocity. The result is
     * used both for feed-forward braking and for a fail-closed collision gate
     * against geometry already present in the fair navigation snapshot.
     */
    private static TrajectoryPrediction predictTrajectory(
            final CoreSkillFrame frame,
            final WaterClutchDescendParameters parameters,
            final MotionEstimate motion
    ) {
        double x = frame.position().x();
        double y = frame.position().y();
        double z = frame.position().z();
        double velocityX = motion.available()
                ? motion.x()
                : 0.0;
        double velocityY = motion.available()
                ? Math.min(0.0, motion.y())
                : 0.0;
        double velocityZ = motion.available()
                ? motion.z()
                : 0.0;
        Optional<GridPos> obstacle = Optional.empty();
        int ticks = 0;
        while (ticks < MAXIMUM_PREDICTION_TICKS
                && y > parameters.y()) {
            ticks++;
            x += velocityX;
            y += velocityY;
            z += velocityZ;
            final Optional<GridPos> collision =
                    observedBodyCollision(
                            frame,
                            parameters,
                            x,
                            y,
                            z
                    );
            if (collision.isPresent()) {
                obstacle = collision;
                break;
            }
            velocityX *= AIRBORNE_HORIZONTAL_DRAG;
            velocityZ *= AIRBORNE_HORIZONTAL_DRAG;
            velocityY = (velocityY - VANILLA_GRAVITY)
                    * VANILLA_VERTICAL_DRAG;
        }
        return new TrajectoryPrediction(
                x,
                z,
                Math.max(1, ticks),
                obstacle
        );
    }

    /**
     * Before the irreversible step, probes a body-width slice just beyond
     * the landing center along the approach direction. An already observed
     * elevated solid there can catch residual momentum above the water cell,
     * so the skill rejects the route while it is still safely grounded.
     */
    private static Optional<GridPos>
            observedExitCorridorObstruction(
                    final CoreSkillFrame frame,
                    final WaterClutchDescendParameters parameters
            ) {
        final double deltaX =
                parameters.x() - frame.position().x();
        final double deltaZ =
                parameters.z() - frame.position().z();
        final double distance = Math.hypot(deltaX, deltaZ);
        if (distance <= 1.0E-9) {
            return Optional.empty();
        }
        return observedBodyCollision(
                frame,
                parameters,
                parameters.x()
                    + deltaX / distance
                    * EXIT_CORRIDOR_PROBE_OFFSET,
                parameters.y(),
                parameters.z()
                    + deltaZ / distance
                    * EXIT_CORRIDOR_PROBE_OFFSET
        );
    }

    /**
     * Tests the predicted 0.6 x 1.8 player body only against solid evidence
     * refreshed by the current semantic navigation sample. The mapper keeps
     * older cells as route memory, but a retained stale solid is unknown now
     * and cannot prove that the live exit corridor is obstructed. The
     * intended landing support itself is a legal boundary, not a collision;
     * currently observed elevated/adjacent geometry that would catch an
     * overshoot is not.
     */
    private static Optional<GridPos> observedBodyCollision(
            final CoreSkillFrame frame,
            final WaterClutchDescendParameters parameters,
            final double x,
            final double y,
            final double z
    ) {
        final GridPos intendedSupport =
                landingFeet(parameters).below();
        final int minimumX = floor(x - BODY_HALF_WIDTH
                + COLLISION_EPSILON);
        final int maximumX = floor(x + BODY_HALF_WIDTH
                - COLLISION_EPSILON);
        final int minimumY = floor(y + COLLISION_EPSILON);
        final int maximumY = floor(y + BODY_HEIGHT
                - COLLISION_EPSILON);
        final int minimumZ = floor(z - BODY_HALF_WIDTH
                + COLLISION_EPSILON);
        final int maximumZ = floor(z + BODY_HALF_WIDTH
                - COLLISION_EPSILON);
        for (int blockY = minimumY;
                blockY <= maximumY;
                blockY++) {
            for (int blockX = minimumX;
                    blockX <= maximumX;
                    blockX++) {
                for (int blockZ = minimumZ;
                        blockZ <= maximumZ;
                        blockZ++) {
                    final GridPos position =
                            new GridPos(blockX, blockY, blockZ);
                    if (position.equals(intendedSupport)) {
                        continue;
                    }
                    final Optional<ObservedVoxel> voxel =
                            frame.navigation().voxelAt(position);
                    if (voxel.isPresent()) {
                        final ObservedVoxel observedVoxel =
                                voxel.orElseThrow();
                        if (observedVoxel.observationRevision()
                                    == frame.navigation().revision()
                                && observedVoxel.kind()
                                    .supportsWeight()) {
                            return Optional.of(position);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Converts a world-space correction for the predicted landing point into
     * Minecraft's player-relative forward/left axes. This actively cancels
     * residual air momentum instead of relying on 0.91 drag alone.
     */
    private static MovementIntent correctiveMovement(
            final CoreSkillFrame frame,
            final WaterClutchDescendParameters parameters,
            final TrajectoryPrediction prediction
    ) {
        final double correctionX =
                parameters.x() - prediction.x();
        final double correctionZ =
                parameters.z() - prediction.z();
        final double correction =
                Math.hypot(correctionX, correctionZ);
        if (correction <= SAFE_LANDING_CENTER_RADIUS) {
            return MovementIntent.STOPPED;
        }
        final double horizontalLook = Math.hypot(
                frame.lookDirection().x(),
                frame.lookDirection().z()
        );
        if (horizontalLook <= 1.0E-12) {
            return MovementIntent.STOPPED;
        }
        final double desiredX = correctionX / correction;
        final double desiredZ = correctionZ / correction;
        final double forwardX =
                frame.lookDirection().x() / horizontalLook;
        final double forwardZ =
                frame.lookDirection().z() / horizontalLook;
        final double leftX = forwardZ;
        final double leftZ = -forwardX;
        final double throttle = prediction.obstacle().isPresent()
                ? 1.0
                : Math.min(
                        1.0,
                        Math.max(0.35, correction * 1.25)
                );
        return new MovementIntent(
                throttle
                    * (desiredX * forwardX
                        + desiredZ * forwardZ),
                throttle
                    * (desiredX * leftX
                        + desiredZ * leftZ),
                false,
                false
        );
    }

    /**
     * Returns only the already observed upper face of the exact support block
     * below the requested landing cell. Nearby floors are never substituted.
     */
    private static Optional<VisibleBlockFace>
            exactLandingSupportFace(
                    final CoreSkillFrame frame,
                    final WaterClutchDescendParameters parameters
            ) {
        final GridPos support = landingFeet(parameters).below();
        return frame.visibleBlockFaces().stream()
                .filter(face ->
                        face.block().x() == support.x()
                            && face.block().y() == support.y()
                            && face.block().z() == support.z()
                            && faceName(face).equals("up")
                )
                .findFirst();
    }

    private boolean completedDescent(
            final CoreSkillFrame frame,
            final WaterClutchDescendParameters parameters
    ) {
        return frame.inWater()
                && frame.position().y()
                    <= parameters.y() + 1.25
                && horizontalDistance(
                        frame.position(),
                        parameters
                ) <= parameters.arrivalRadius() + 0.5
                && inventoryCount(frame, WATER_BUCKET)
                    == startingWaterBuckets - 1
                && inventoryCount(frame, EMPTY_BUCKET)
                    >= startingEmptyBuckets + 1;
    }

    private Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean allowIntentionalFall
    ) {
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        final double nonFallingDanger = frame.dangerSignals().stream()
                .filter(signal ->
                        !allowIntentionalFall
                            || signal.kind() != DangerKind.FALLING
                )
                .mapToDouble(signal -> signal.severity())
                .max()
                .orElse(0.0);
        if (nonFallingDanger > maximumDanger
                || !allowIntentionalFall
                    && (context.riskScore() > maximumDanger
                        || frame.danger() > maximumDanger)) {
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

    private boolean verifiedLanding(
            final SkillContext context,
            final CoreSkillFrame frame,
            final WaterClutchDescendParameters parameters
    ) {
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        final GridPos feet = landingFeet(parameters);
        final GridPos support = feet.below();
        final Optional<ObservedVoxel> supportVoxel =
                frame.navigation().voxelAt(support);
        final Optional<ObservedVoxel> feetVoxel =
                frame.navigation().voxelAt(feet);
        final Optional<ObservedVoxel> headVoxel =
                frame.navigation().voxelAt(feet.above());
        if (supportVoxel.isEmpty()
                || feetVoxel.isEmpty()
                || headVoxel.isEmpty()
                || !supportVoxel.orElseThrow()
                    .kind().supportsWeight()
                || supportVoxel.orElseThrow()
                    .effectiveDanger() > maximumDanger
                || !safePassable(
                        feetVoxel.orElseThrow(),
                        maximumDanger
                )
                || !safePassable(
                        headVoxel.orElseThrow(),
                        maximumDanger
                )) {
            MinecraftAiCompanion.LOGGER.debug(
                "{} rejected observed landing support={}({}) feet={}({}) "
                    + "head={}({}), limit={}",
                NAME,
                supportVoxel.map(voxel -> voxel.kind().name())
                    .orElse("UNKNOWN"),
                supportVoxel.map(ObservedVoxel::effectiveDanger)
                    .map(Object::toString)
                    .orElse("-"),
                feetVoxel.map(voxel -> voxel.kind().name())
                    .orElse("UNKNOWN"),
                feetVoxel.map(ObservedVoxel::effectiveDanger)
                    .map(Object::toString)
                    .orElse("-"),
                headVoxel.map(voxel -> voxel.kind().name())
                    .orElse("UNKNOWN"),
                headVoxel.map(ObservedVoxel::effectiveDanger)
                    .map(Object::toString)
                    .orElse("-"),
                maximumDanger
            );
            return false;
        }
        final boolean ownedWaterNowPresent =
                phase == Phase.DESCENDING
                    && initialLandingVerified
                    && frame.visibleBlockFaces().stream().anyMatch(face ->
                        face.block().x() == feet.x()
                            && face.block().y() == feet.y()
                            && face.block().z() == feet.z()
                            && face.blockTypeId().equals(
                                "minecraft:water"
                            )
                            && faceName(face).equals("up")
                            && face.stateProperties()
                                .getOrDefault("level", "")
                                .equals("0")
                    )
                    &&
                feetVoxel.orElseThrow().kind() == VoxelKind.WATER
                    && inventoryCount(frame, WATER_BUCKET)
                        == startingWaterBuckets - 1
                    && inventoryCount(frame, EMPTY_BUCKET)
                        >= startingEmptyBuckets + 1;
        if (ownedWaterNowPresent) {
            return true;
        }
        final boolean visible = frame.visibleBlockFaces().stream().anyMatch(face ->
                face.block().x() == support.x()
                    && face.block().y() == support.y()
                    && face.block().z() == support.z()
                    && faceName(face).equals("up")
        );
        if (!visible) {
            /*
             * Turning from the initially observed far edge toward the
             * requested landing column can hide that floor behind the
             * player's own ledge for a few grounded frames. Retain the
             * already verified support only while aligning and stepping off;
             * once airborne, a live visible face is required again before
             * the skill may continue or place water.
             */
            if (initialLandingVerified
                    && (phase == Phase.ALIGNING
                        || phase == Phase.STEPPING_OFF)) {
                return true;
            }
            MinecraftAiCompanion.LOGGER.debug(
                "{} rejected landing because support {} was not among "
                    + "{} fair visible block faces; body={}, look={}, "
                    + "faces={}",
                NAME,
                support,
                frame.visibleBlockFaces().size(),
                frame.position(),
                frame.lookDirection(),
                frame.visibleBlockFaces().stream()
                    .map(face -> face.block() + ":" + faceName(face))
                    .toList()
            );
        }
        return visible;
    }

    private static boolean safePassable(
            final ObservedVoxel voxel,
            final double maximumDanger
    ) {
        return voxel.kind().isPassable()
                && voxel.kind() != VoxelKind.LAVA
                && voxel.effectiveDanger()
                    <= maximumDanger;
    }

    private FrameValidation validateFrame(
            final WaterClutchDescendParameters parameters
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

    private static int inventoryCount(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().equals(itemId))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static GridPos landingFeet(
            final WaterClutchDescendParameters parameters
    ) {
        return new GridPos(
                floor(parameters.x()),
                floor(parameters.y()),
                floor(parameters.z())
        );
    }

    private static PerceptionVec3 landingTop(
            final WaterClutchDescendParameters parameters
    ) {
        final GridPos feet = landingFeet(parameters);
        return new PerceptionVec3(
                parameters.x(),
                feet.y(),
                parameters.z()
        );
    }

    private static double horizontalDistance(
            final PerceptionVec3 position,
            final WaterClutchDescendParameters parameters
    ) {
        return Math.hypot(
                position.x() - parameters.x(),
                position.z() - parameters.z()
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

    private static String faceName(
            final VisibleBlockFace face
    ) {
        final String value = face.face();
        final int separator = value.lastIndexOf(':');
        return (separator >= 0
                ? value.substring(separator + 1)
                : value).toLowerCase(Locale.ROOT);
    }

    private static int floor(final double value) {
        final int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    private void quiesce() {
        actuator.stop();
        actuator.releaseUse();
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        quiesce();
        failure = Objects.requireNonNull(reason, "reason");
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private record MotionEstimate(
            double x,
            double y,
            double z,
            boolean available
    ) {
        private static MotionEstimate unavailable() {
            return new MotionEstimate(0.0, 0.0, 0.0, false);
        }
    }

    private record TrajectoryPrediction(
            double x,
            double z,
            int ticks,
            Optional<GridPos> obstacle
    ) {
        private TrajectoryPrediction {
            Objects.requireNonNull(obstacle, "obstacle");
        }
    }

    private enum Phase {
        IDLE,
        ALIGNING,
        STEPPING_OFF,
        DESCENDING,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return this == ALIGNING
                    || this == STEPPING_OFF
                    || this == DESCENDING;
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
