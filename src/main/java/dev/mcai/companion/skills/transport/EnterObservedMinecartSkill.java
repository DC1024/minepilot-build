package dev.mcai.companion.skills.transport;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.perception.VisibleEntity;
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
 * Mounts one ordinary rideable minecart from a current fair entity reference.
 */
public final class EnterObservedMinecartSkill
        implements Skill<EnterObservedMinecartParameters> {
    public static final String NAME = "enter_observed_minecart";

    private final UUID expectedPlayerId;
    private final MinecartSkillActuator actuator;
    private final MinecartSkillFrameSource frames;
    private final MinecartSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private UUID boundMinecartId;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;

    public EnterObservedMinecartSkill(
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
    public SkillParameterParser<EnterObservedMinecartParameters>
            parameters() {
        return MinecartSkillParameters::parseEnter;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final EnterObservedMinecartParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        return resolveInitial(parameters).failure();
    }

    @Override
    public void start(
            final SkillContext context,
            final EnterObservedMinecartParameters parameters
    ) {
        final Resolution resolution = resolveInitial(parameters);
        if (resolution.failure().isPresent()) {
            throw new IllegalStateException(
                    "Minecart observation changed before start"
            );
        }
        final MinecartSkillFrame frame =
                resolution.frame().orElseThrow();
        phase = Phase.READY;
        failure = null;
        boundMinecartId =
                resolution.entity().orElseThrow().entityId();
        boundSessionGeneration = frame.sessionGeneration();
        startedAtTick = context.gameTick();
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final EnterObservedMinecartParameters parameters
    ) {
        if (phase != Phase.READY && phase != Phase.MOUNTING) {
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
            final EnterObservedMinecartParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"sampleSequence\":%d,"
                                + "\"observationId\":\"%s\","
                                + "\"session\":%d}",
                        phase.name(),
                        parameters.sampleSequence(),
                        parameters.observationId(),
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final EnterObservedMinecartParameters parameters
    ) {
        if (boundMinecartId != null) {
            actuator.stopMinecartInput(boundMinecartId);
        }
        phase = Phase.CANCELLED;
        boundMinecartId = null;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final EnterObservedMinecartParameters parameters
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
            final EnterObservedMinecartParameters parameters
    ) {
        if (context.gameTick() - startedAtTick
                >= policy.mountTimeoutTicks()) {
            return fail(NAME + ".timed_out");
        }
        final FrameValidation validation = validateFrame(
                parameters.dimension(),
                boundSessionGeneration
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final MinecartSkillFrame frame =
                validation.frame().orElseThrow();
        if (frame.riddenMinecart().isPresent()) {
            if (!frame.riddenMinecart()
                    .orElseThrow()
                    .minecartId()
                    .equals(boundMinecartId)) {
                return fail(NAME + ".different_vehicle");
            }
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (phase == Phase.MOUNTING) {
            return SkillTickResult.running(false, true);
        }
        final ActionOutcome outcome =
                actuator.enterMinecart(boundMinecartId);
        if (!outcome.accepted()) {
            return fail(actionFailure(outcome));
        }
        phase = Phase.MOUNTING;
        return SkillTickResult.running(true, true);
    }

    private Resolution resolveInitial(
            final EnterObservedMinecartParameters parameters
    ) {
        final FrameValidation validation = validateFrame(
                parameters.dimension(),
                -1
        );
        if (validation.failure().isPresent()) {
            return Resolution.failed(
                    validation.failure().orElseThrow()
            );
        }
        final MinecartSkillFrame frame =
                validation.frame().orElseThrow();
        if (frame.riddenMinecart().isPresent()) {
            return Resolution.failed(NAME + ".already_in_vehicle");
        }
        if (frame.observationRevision()
                != parameters.sampleSequence()) {
            return Resolution.failed(NAME + ".observation_expired");
        }
        final int index = parameters.observationIndex();
        if (index < 0 || index >= frame.visibleEntities().size()) {
            return Resolution.failed(NAME + ".target_not_visible");
        }
        final VisibleEntity entity =
                frame.visibleEntities().get(index);
        if (!"minecraft:minecart".equals(
                entity.entityTypeId()
        )) {
            return Resolution.failed(NAME + ".target_not_rideable");
        }
        return Resolution.resolved(frame, entity);
    }

    private FrameValidation validateFrame(
            final dev.mcai.companion.waypoint.DimensionRef dimension,
            final long expectedSession
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
        if (!frame.dimension().equals(dimension)) {
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
        return FrameValidation.valid(frame);
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        failure = reason;
        phase = Phase.FAILED;
        if (boundMinecartId != null) {
            actuator.stopMinecartInput(boundMinecartId);
        }
        return SkillTickResult.failed(reason);
    }

    private static String actionFailure(
            final ActionOutcome outcome
    ) {
        return NAME + ".action_"
                + outcome.name().toLowerCase(Locale.ROOT);
    }

    private enum Phase {
        IDLE,
        READY,
        MOUNTING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private record Resolution(
            Optional<MinecartSkillFrame> frame,
            Optional<VisibleEntity> entity,
            Optional<SkillFailure> failure
    ) {
        static Resolution resolved(
                final MinecartSkillFrame frame,
                final VisibleEntity entity
        ) {
            return new Resolution(
                    Optional.of(frame),
                    Optional.of(entity),
                    Optional.empty()
            );
        }

        static Resolution failed(final String code) {
            return failed(SkillFailure.of(code));
        }

        static Resolution failed(final SkillFailure failure) {
            return new Resolution(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(failure)
            );
        }
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
