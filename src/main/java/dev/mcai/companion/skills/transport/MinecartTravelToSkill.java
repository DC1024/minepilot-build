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
 * Monitors vanilla rail travel and supplies only ordinary rider input.
 */
public final class MinecartTravelToSkill
        implements Skill<MinecartTravelToParameters> {
    public static final String NAME = "minecart_travel_to";

    private final UUID expectedPlayerId;
    private final MinecartSkillActuator actuator;
    private final MinecartSkillFrameSource frames;
    private final MinecartSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private UUID boundMinecartId;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;
    private long lastProgressTick = -1;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private int recoveries;
    private int recoveryTicksRemaining;
    private int dismountWaitTicks;

    public MinecartTravelToSkill(
            final UUID expectedPlayerId,
            final MinecartSkillActuator actuator,
            final MinecartSkillFrameSource frames,
            final MinecartSkillPolicy policy
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
    public SkillParameterParser<MinecartTravelToParameters>
            parameters() {
        return MinecartSkillParameters::parseTravel;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final MinecartTravelToParameters parameters
    ) {
        if (parameters.timeoutTicks()
                > policy.hardMaximumTravelTicks()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".timeout_out_of_bounds"
            ));
        }
        final FrameValidation validation =
                validateFrame(parameters, -1, false);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        if (validation.frame().orElseThrow().danger()
                > policy.maximumDanger(context.hardcore())) {
            return Optional.of(
                    SkillFailure.of(NAME + ".danger_observed")
            );
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final MinecartTravelToParameters parameters
    ) {
        final FrameValidation validation =
                validateFrame(parameters, -1, false);
        if (validation.failure().isPresent()) {
            throw new IllegalStateException(
                    "Minecart binding changed before start"
            );
        }
        final MinecartSkillFrame frame =
                validation.frame().orElseThrow();
        final MinecartState minecart =
                frame.riddenMinecart().orElseThrow();
        phase = Phase.RIDING;
        failure = null;
        boundMinecartId = minecart.minecartId();
        boundSessionGeneration = frame.sessionGeneration();
        startedAtTick = context.gameTick();
        lastProgressTick = context.gameTick();
        bestDistance = parameters.distance(minecart);
        recoveries = 0;
        recoveryTicksRemaining = 0;
        dismountWaitTicks = 0;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final MinecartTravelToParameters parameters
    ) {
        if (phase != Phase.RIDING
                && phase != Phase.RECOVERING
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
            final SkillContext context,
            final MinecartTravelToParameters parameters
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
            final SkillContext context,
            final MinecartTravelToParameters parameters
    ) {
        stopInput();
        phase = Phase.CANCELLED;
        boundMinecartId = null;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final MinecartTravelToParameters parameters
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
            final MinecartTravelToParameters parameters
    ) {
        if (context.gameTick() - startedAtTick
                >= parameters.timeoutTicks()) {
            return fail(NAME + ".timed_out");
        }
        final FrameValidation validation = validateFrame(
                parameters,
                boundSessionGeneration,
                phase == Phase.WAITING_DISMOUNT
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final MinecartSkillFrame frame =
                validation.frame().orElseThrow();
        if (phase == Phase.WAITING_DISMOUNT
                && frame.riddenMinecart().isEmpty()) {
            phase = Phase.COMPLETED;
            boundMinecartId = null;
            return SkillTickResult.completed();
        }
        final MinecartState minecart =
                frame.riddenMinecart().orElseThrow();
        if (!minecart.minecartId().equals(boundMinecartId)) {
            return fail(NAME + ".vehicle_changed");
        }
        if (frame.danger()
                > policy.maximumDanger(context.hardcore())) {
            return fail(NAME + ".danger_observed");
        }
        if (phase == Phase.WAITING_DISMOUNT) {
            return requestDismount(frame);
        }

        final double distance = parameters.distance(minecart);
        if (distance <= parameters.arrivalRadius()) {
            final ActionOutcome stop =
                    actuator.stopMinecartInput(boundMinecartId);
            if (!releaseSucceeded(stop)) {
                return fail(actionFailure(stop));
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
                    return fail(
                            NAME + ".safe_dismount_not_observed"
                    );
                }
                return SkillTickResult.running(false, true);
            }
            return requestDismount(frame);
        }

        boolean madeProgress = false;
        if (bestDistance - distance >= policy.progressEpsilon()) {
            bestDistance = distance;
            lastProgressTick = context.gameTick();
            madeProgress = true;
        }
        final float targetYaw = targetYaw(parameters, minecart);
        if (phase == Phase.RECOVERING) {
            final ActionOutcome recovery = actuator.driveMinecart(
                    boundMinecartId,
                    targetYaw,
                    false,
                    true
            );
            if (!recovery.accepted()) {
                return fail(actionFailure(recovery));
            }
            recoveryTicksRemaining--;
            if (recoveryTicksRemaining <= 0) {
                phase = Phase.RIDING;
                lastProgressTick = context.gameTick();
            }
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - lastProgressTick
                >= policy.stuckTicks()) {
            if (recoveries >= policy.maximumRecoveries()) {
                return fail(NAME + ".stuck");
            }
            recoveries++;
            recoveryTicksRemaining =
                    policy.reverseRecoveryTicks();
            phase = Phase.RECOVERING;
            return SkillTickResult.running(true, true);
        }
        final ActionOutcome drive = actuator.driveMinecart(
                boundMinecartId,
                targetYaw,
                true,
                false
        );
        if (!drive.accepted()) {
            return fail(actionFailure(drive));
        }
        return SkillTickResult.running(madeProgress, true);
    }

    private SkillTickResult requestDismount(
            final MinecartSkillFrame frame
    ) {
        if (frame.riddenMinecart().isEmpty()) {
            phase = Phase.COMPLETED;
            boundMinecartId = null;
            return SkillTickResult.completed();
        }
        final ActionOutcome outcome =
                actuator.dismountMinecart(boundMinecartId);
        if (!outcome.accepted()) {
            return fail(actionFailure(outcome));
        }
        phase = Phase.WAITING_DISMOUNT;
        return SkillTickResult.running(true, true);
    }

    private FrameValidation validateFrame(
            final MinecartTravelToParameters parameters,
            final long expectedSession,
            final boolean allowNoMinecart
    ) {
        final Optional<MinecartSkillFrame> current =
                frames.current();
        if (current.isEmpty()) {
            return FrameValidation.failed(
                    NAME + ".observation_unavailable"
            );
        }
        final MinecartSkillFrame frame = current.orElseThrow();
        if (!frame.playerId().equals(expectedPlayerId)) {
            return FrameValidation.failed(NAME + ".player_mismatch");
        }
        if (!frame.dimension().equals(parameters.dimension())) {
            return FrameValidation.failed(
                    NAME + ".dimension_mismatch"
            );
        }
        if (frame.observationAgeTicks()
                > policy.maximumObservationAgeTicks()) {
            return FrameValidation.failed(
                    NAME + ".stale_observation"
            );
        }
        final OptionalLong session =
                actuator.sessionGeneration();
        if (session.isEmpty()
                || session.orElseThrow()
                        != frame.sessionGeneration()
                || expectedSession >= 0
                && session.orElseThrow() != expectedSession) {
            return FrameValidation.failed(
                    NAME + ".session_mismatch"
            );
        }
        if (!allowNoMinecart
                && frame.riddenMinecart().isEmpty()) {
            return FrameValidation.failed(
                    NAME + ".not_riding_minecart"
            );
        }
        return FrameValidation.valid(frame);
    }

    private static float targetYaw(
            final MinecartTravelToParameters parameters,
            final MinecartState minecart
    ) {
        return (float) Math.toDegrees(Math.atan2(
                -(parameters.x() - minecart.position().x()),
                parameters.z() - minecart.position().z()
        ));
    }

    private void stopInput() {
        if (boundMinecartId != null) {
            actuator.stopMinecartInput(boundMinecartId);
        }
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        failure = reason;
        phase = Phase.FAILED;
        stopInput();
        return SkillTickResult.failed(reason);
    }

    private static boolean releaseSucceeded(
            final ActionOutcome outcome
    ) {
        return outcome.accepted()
                || outcome == ActionOutcome.NO_ACTIVE_ACTION;
    }

    private static String actionFailure(
            final ActionOutcome outcome
    ) {
        return NAME + ".action_"
                + outcome.name().toLowerCase(Locale.ROOT);
    }

    private enum Phase {
        IDLE,
        RIDING,
        RECOVERING,
        WAITING_DISMOUNT,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private record FrameValidation(
            Optional<MinecartSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        static FrameValidation valid(
                final MinecartSkillFrame frame
        ) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.empty()
            );
        }

        static FrameValidation failed(final String code) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }
}
