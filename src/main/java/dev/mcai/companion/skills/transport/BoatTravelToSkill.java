package dev.mcai.companion.skills.transport;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Fair same-dimension boat travel driven at 20 TPS.
 *
 * <p>The destination is an explicit coordinate. Steering consumes only the
 * controlled boat's own pose and recent semantic danger/surface samples.
 * Vanilla collision response is the only obstacle oracle.</p>
 */
public final class BoatTravelToSkill
        implements Skill<BoatTravelToParameters> {
    private static final String NAME = "boat_travel_to";
    /*
     * Vanilla 26.2 applies at most 0.9 horizontal retention to a normal
     * water/air boat before movement. The estimate intentionally ignores the
     * extra 0.005 reverse input used below, so it starts braking early rather
     * than claiming a radius that momentum can carry the boat back out of.
     */
    private static final double VANILLA_MAXIMUM_NEUTRAL_DRAG = 0.9;

    private final UUID expectedPlayerId;
    private final BoatSkillActuator actuator;
    private final BoatSkillFrameSource frames;
    private final BoatSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private UUID boundBoatId;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;
    private long lastProgressTick = -1;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private int recoveries;
    private int recoveryTicksRemaining;
    private int collisionTicks;
    private int brakeTicks;
    private int dismountWaitTicks;

    public BoatTravelToSkill(
            UUID expectedPlayerId,
            BoatSkillActuator actuator,
            BoatSkillFrameSource frames,
            BoatSkillPolicy policy
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public SkillParameterParser<BoatTravelToParameters> parameters() {
        return BoatSkillParameters::parseTravel;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            BoatTravelToParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (parameters.timeoutTicks()
                > policy.hardMaximumTravelTicks()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".timeout_out_of_bounds"
            ));
        }
        FrameValidation validation = validateFrame(
                parameters,
                -1,
                false
        );
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        BoatSkillFrame frame = validation.frame().orElseThrow();
        BoatState boat = frame.controlledBoat().orElseThrow();
        if (boat.underwater()) {
            return Optional.of(SkillFailure.of(NAME + ".boat_underwater"));
        }
        if (frame.danger() > policy.maximumDanger(context.hardcore())) {
            return Optional.of(SkillFailure.of(NAME + ".danger_observed"));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            SkillContext context,
            BoatTravelToParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        FrameValidation validation = validateFrame(
                parameters,
                -1,
                false
        );
        if (validation.failure().isPresent()) {
            throw new IllegalStateException(
                    "Boat binding changed before start"
            );
        }
        BoatSkillFrame frame = validation.frame().orElseThrow();
        BoatState boat = frame.controlledBoat().orElseThrow();
        phase = Phase.CRUISING;
        failure = null;
        boundBoatId = boat.boatId();
        boundSessionGeneration = frame.sessionGeneration();
        startedAtTick = context.gameTick();
        lastProgressTick = context.gameTick();
        bestDistance = parameters.horizontalDistance(boat);
        recoveries = 0;
        recoveryTicksRemaining = 0;
        collisionTicks = 0;
        brakeTicks = 0;
        dismountWaitTicks = 0;
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            BoatTravelToParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase != Phase.CRUISING
                && phase != Phase.RECOVERING
                && phase != Phase.BRAKING
                && phase != Phase.WAITING_DISMOUNT) {
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
            SkillContext context,
            BoatTravelToParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,"
                                + "\"bestDistance\":%.3f,"
                                + "\"recoveries\":%d,\"session\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.x(),
                        parameters.y(),
                        parameters.z(),
                        bestDistance,
                        recoveries,
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            SkillContext context,
            BoatTravelToParameters parameters
    ) {
        stopBoundBoat();
        phase = Phase.CANCELLED;
        boundBoatId = null;
    }

    @Override
    public SkillResult result(
            SkillContext context,
            BoatTravelToParameters parameters
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
            SkillContext context,
            BoatTravelToParameters parameters
    ) {
        if (context.gameTick() - startedAtTick
                >= parameters.timeoutTicks()) {
            return fail(NAME + ".timed_out");
        }

        FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                phase == Phase.WAITING_DISMOUNT
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        BoatSkillFrame frame = validation.frame().orElseThrow();
        if (phase == Phase.WAITING_DISMOUNT
                && frame.controlledBoat().isEmpty()) {
            phase = Phase.COMPLETED;
            boundBoatId = null;
            return SkillTickResult.completed();
        }
        BoatState boat = frame.controlledBoat().orElseThrow();
        if (!boat.boatId().equals(boundBoatId)) {
            return fail(NAME + ".vehicle_changed");
        }
        if (boat.underwater()) {
            return fail(NAME + ".boat_underwater");
        }
        if (frame.danger() > policy.maximumDanger(context.hardcore())) {
            return fail(NAME + ".danger_observed");
        }
        if (phase == Phase.WAITING_DISMOUNT) {
            return requestDismount(frame);
        }
        if (phase == Phase.BRAKING) {
            return brake(context, frame, parameters, boat);
        }

        double distance = parameters.horizontalDistance(boat);
        if (shouldBeginBraking(parameters, boat, distance)) {
            phase = Phase.BRAKING;
            brakeTicks = 0;
            return brake(context, frame, parameters, boat);
        }
        boolean madeProgress = false;
        if (bestDistance - distance >= policy.progressEpsilon()) {
            bestDistance = distance;
            lastProgressTick = context.gameTick();
            madeProgress = true;
        }
        collisionTicks = boat.horizontalCollision()
                ? collisionTicks + 1
                : 0;

        if (phase == Phase.RECOVERING) {
            return recover(context);
        }
        if (collisionTicks >= 3
                || context.gameTick() - lastProgressTick
                >= policy.stuckTicks()) {
            if (recoveries >= policy.maximumRecoveries()) {
                return fail(NAME + ".stuck");
            }
            recoveries++;
            recoveryTicksRemaining = policy.recoveryTicks();
            phase = Phase.RECOVERING;
            return recover(context);
        }

        BoatControlIntent intent = steeringForPolicy(parameters, boat);
        ActionOutcome outcome = actuator.driveBoat(boundBoatId, intent);
        if (!outcome.accepted()) {
            return fail(actionFailure(outcome));
        }
        return SkillTickResult.running(madeProgress, true);
    }

    private SkillTickResult recover(SkillContext context) {
        boolean turnLeft = recoveries % 2 == 1;
        BoatControlIntent intent = turnLeft
                ? BoatControlIntent.backwardLeft()
                : BoatControlIntent.backwardRight();
        ActionOutcome outcome = actuator.driveBoat(boundBoatId, intent);
        if (!outcome.accepted()) {
            return fail(actionFailure(outcome));
        }
        recoveryTicksRemaining--;
        if (recoveryTicksRemaining <= 0) {
            phase = Phase.CRUISING;
            collisionTicks = 0;
            lastProgressTick = context.gameTick();
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult brake(
            SkillContext context,
            BoatSkillFrame frame,
            BoatTravelToParameters parameters,
            BoatState boat
    ) {
        ActionOutcome drive = actuator.driveBoat(
                boundBoatId,
                brakingIntent(boat)
        );
        if (!drive.accepted()) {
            return fail(actionFailure(drive));
        }
        brakeTicks++;
        if (boat.horizontalSpeed() > policy.stoppedSpeed()
                && brakeTicks < policy.maximumBrakeTicks()) {
            return SkillTickResult.running(true, true);
        }
        if (boat.horizontalSpeed() > policy.stoppedSpeed()) {
            return fail(NAME + ".braking_timed_out");
        }
        ActionOutcome stop = actuator.stopBoat(boundBoatId);
        if (!releaseSucceeded(stop)) {
            return fail(actionFailure(stop));
        }
        final double distance =
                parameters.horizontalDistance(boat);
        if (distance > parameters.arrivalRadius()) {
            /*
             * A current or collision can still displace a stopped boat after
             * predictive braking. Never report arrival outside the requested
             * radius: resume ordinary steering from the newly observed pose.
             */
            phase = Phase.CRUISING;
            brakeTicks = 0;
            bestDistance = distance;
            lastProgressTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (!parameters.dismountAtArrival()) {
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (!frame.hasObservedSafeDismountSurface(
                policy.maximumDismountSurfaceDistance()
        )) {
            dismountWaitTicks++;
            if (dismountWaitTicks
                    >= policy.maximumDismountWaitTicks()) {
                return fail(NAME + ".safe_dismount_not_observed");
            }
            return SkillTickResult.running(false, true);
        }
        return requestDismount(frame);
    }

    private boolean shouldBeginBraking(
            BoatTravelToParameters parameters,
            BoatState boat,
            double distance
    ) {
        if (distance <= parameters.arrivalRadius()) {
            return true;
        }
        if (distance <= 1.0E-9) {
            return true;
        }
        final double deltaX =
                parameters.x() - boat.position().x();
        final double deltaZ =
                parameters.z() - boat.position().z();
        final double closingSpeed =
                (boat.velocity().x() * deltaX
                    + boat.velocity().z() * deltaZ)
                    / distance;
        if (closingSpeed <= 0.0) {
            return false;
        }
        final double remainingBeforeRadius =
                distance - parameters.arrivalRadius();
        return estimatedNeutralStoppingDistance(
                boat.horizontalSpeed()
        ) >= remainingBeforeRadius;
    }

    /**
     * Uses only the controlled boat's observed velocity. One full-speed
     * observation margin accounts for the action reaching the following
     * entity tick; the geometric tail then follows vanilla's maximum normal
     * horizontal retention for the bounded braking window.
     */
    private double estimatedNeutralStoppingDistance(
            double horizontalSpeed
    ) {
        double estimate = horizontalSpeed;
        double speed = horizontalSpeed;
        for (int tick = 0;
                tick < policy.maximumBrakeTicks()
                    && speed > policy.stoppedSpeed();
                tick++) {
            speed *= VANILLA_MAXIMUM_NEUTRAL_DRAG;
            estimate += speed;
        }
        return estimate;
    }

    /**
     * Opposes longitudinal momentum through the same vanilla forward/back
     * controls a client uses. Sideways drift remains neutral and is handled
     * by collision plus drag; no velocity or position is written directly.
     */
    private BoatControlIntent brakingIntent(BoatState boat) {
        final double radians =
                Math.toRadians(boat.yawDegrees());
        final double forwardX = -Math.sin(radians);
        final double forwardZ = Math.cos(radians);
        final double longitudinal =
                boat.velocity().x() * forwardX
                    + boat.velocity().z() * forwardZ;
        final double threshold =
                policy.stoppedSpeed() * 0.5;
        if (longitudinal > threshold) {
            return BoatControlIntent.backwardIntent();
        }
        if (longitudinal < -threshold) {
            return BoatControlIntent.forwardIntent();
        }
        return BoatControlIntent.NEUTRAL;
    }

    private SkillTickResult requestDismount(BoatSkillFrame frame) {
        if (frame.controlledBoat().isEmpty()) {
            phase = Phase.COMPLETED;
            boundBoatId = null;
            return SkillTickResult.completed();
        }
        ActionOutcome outcome = actuator.dismountBoat(boundBoatId);
        if (!outcome.accepted()) {
            return fail(actionFailure(outcome));
        }
        phase = Phase.WAITING_DISMOUNT;
        return SkillTickResult.running(true, true);
    }

    private FrameValidation validateFrame(
            BoatTravelToParameters parameters,
            long expectedSession,
            boolean allowNoBoat
    ) {
        Optional<BoatSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return FrameValidation.failed(NAME + ".observation_unavailable");
        }
        BoatSkillFrame frame = current.orElseThrow();
        if (!frame.playerId().equals(expectedPlayerId)) {
            return FrameValidation.failed(NAME + ".player_mismatch");
        }
        if (!frame.dimension().equals(parameters.dimension())) {
            return FrameValidation.failed(NAME + ".dimension_mismatch");
        }
        if (frame.observationAgeTicks()
                > policy.maximumObservationAgeTicks()) {
            return FrameValidation.failed(NAME + ".stale_observation");
        }
        OptionalLong session = actuator.sessionGeneration();
        if (session.isEmpty()
                || session.orElseThrow() != frame.sessionGeneration()
                || expectedSession >= 0
                && session.orElseThrow() != expectedSession) {
            return FrameValidation.failed(NAME + ".session_mismatch");
        }
        if (!allowNoBoat && frame.controlledBoat().isEmpty()) {
            return FrameValidation.failed(NAME + ".not_controlling_boat");
        }
        return FrameValidation.valid(frame);
    }

    static BoatControlIntent defaultSteering(
            BoatTravelToParameters parameters,
            BoatState boat
    ) {
        double deltaX = parameters.x() - boat.position().x();
        double deltaZ = parameters.z() - boat.position().z();
        double desiredYaw = Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        double delta = wrapDegrees(desiredYaw - boat.yawDegrees());
        BoatSkillPolicy defaults = BoatSkillPolicy.defaults();
        boolean left = delta < -defaults.turnDeadbandDegrees();
        boolean right = delta > defaults.turnDeadbandDegrees();
        boolean forward = Math.abs(delta)
                <= defaults.forwardTurnLimitDegrees();
        return new BoatControlIntent(left, right, forward, false);
    }

    private BoatControlIntent steeringForPolicy(
            BoatTravelToParameters parameters,
            BoatState boat
    ) {
        double deltaX = parameters.x() - boat.position().x();
        double deltaZ = parameters.z() - boat.position().z();
        double desiredYaw = Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        double delta = wrapDegrees(desiredYaw - boat.yawDegrees());
        boolean left = delta < -policy.turnDeadbandDegrees();
        boolean right = delta > policy.turnDeadbandDegrees();
        boolean forward = Math.abs(delta)
                <= policy.forwardTurnLimitDegrees();
        return new BoatControlIntent(left, right, forward, false);
    }

    private static double wrapDegrees(double value) {
        double wrapped = value % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    private void stopBoundBoat() {
        if (boundBoatId != null) {
            actuator.stopBoat(boundBoatId);
        }
    }

    private SkillTickResult fail(String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(SkillFailure reason) {
        failure = reason;
        phase = Phase.FAILED;
        stopBoundBoat();
        return SkillTickResult.failed(reason);
    }

    private static boolean releaseSucceeded(ActionOutcome outcome) {
        return outcome.accepted()
                || outcome == ActionOutcome.NO_ACTIVE_ACTION;
    }

    private static String actionFailure(ActionOutcome outcome) {
        return NAME + ".action_"
                + outcome.name().toLowerCase(Locale.ROOT);
    }

    private enum Phase {
        IDLE,
        CRUISING,
        RECOVERING,
        BRAKING,
        WAITING_DISMOUNT,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private record FrameValidation(
            Optional<BoatSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        static FrameValidation valid(BoatSkillFrame frame) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.empty()
            );
        }

        static FrameValidation failed(String code) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }
}
