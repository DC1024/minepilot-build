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
 * Mounts exactly one boat from a current fair semantic entity reference.
 */
public final class EnterObservedBoatSkill
        implements Skill<EnterObservedBoatParameters> {
    private static final String NAME = "enter_observed_boat";

    private final UUID expectedPlayerId;
    private final BoatSkillActuator actuator;
    private final BoatSkillFrameSource frames;
    private final BoatSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private UUID boundBoatId;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;

    public EnterObservedBoatSkill(
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
    public SkillParameterParser<EnterObservedBoatParameters> parameters() {
        return BoatSkillParameters::parseEnter;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            EnterObservedBoatParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        Resolution resolution = resolveInitial(parameters);
        return resolution.failure();
    }

    @Override
    public void start(
            SkillContext context,
            EnterObservedBoatParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        Resolution resolution = resolveInitial(parameters);
        if (resolution.failure().isPresent()) {
            throw new IllegalStateException(
                    "Boat observation changed before start"
            );
        }
        BoatSkillFrame frame = resolution.frame().orElseThrow();
        phase = Phase.READY;
        failure = null;
        boundBoatId = resolution.entity().orElseThrow().entityId();
        boundSessionGeneration = frame.sessionGeneration();
        startedAtTick = context.gameTick();
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            EnterObservedBoatParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
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
            SkillContext context,
            EnterObservedBoatParameters parameters
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
            SkillContext context,
            EnterObservedBoatParameters parameters
    ) {
        if (boundBoatId != null) {
            actuator.stopBoat(boundBoatId);
        }
        phase = Phase.CANCELLED;
        boundBoatId = null;
    }

    @Override
    public SkillResult result(
            SkillContext context,
            EnterObservedBoatParameters parameters
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
            EnterObservedBoatParameters parameters
    ) {
        if (context.gameTick() - startedAtTick
                >= policy.mountTimeoutTicks()) {
            return fail(NAME + ".timed_out");
        }
        FrameValidation validation = validateFrame(
                parameters.dimension(),
                boundSessionGeneration
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        BoatSkillFrame frame = validation.frame().orElseThrow();
        Optional<BoatState> controlled = frame.controlledBoat();
        if (controlled.isPresent()) {
            if (!controlled.orElseThrow().boatId().equals(boundBoatId)) {
                return fail(NAME + ".different_vehicle");
            }
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (phase == Phase.MOUNTING) {
            return SkillTickResult.running(false, true);
        }

        ActionOutcome outcome = actuator.enterBoat(boundBoatId);
        if (!outcome.accepted()) {
            return fail(actionFailure(outcome));
        }
        phase = Phase.MOUNTING;
        return SkillTickResult.running(true, true);
    }

    private Resolution resolveInitial(
            EnterObservedBoatParameters parameters
    ) {
        FrameValidation validation = validateFrame(
                parameters.dimension(),
                -1
        );
        if (validation.failure().isPresent()) {
            return Resolution.failed(
                    validation.failure().orElseThrow()
            );
        }
        BoatSkillFrame frame = validation.frame().orElseThrow();
        if (frame.controlledBoat().isPresent()) {
            return Resolution.failed(NAME + ".already_in_vehicle");
        }
        if (frame.observationRevision()
                != parameters.sampleSequence()) {
            return Resolution.failed(NAME + ".observation_expired");
        }
        int index = parameters.observationIndex();
        if (index < 0 || index >= frame.visibleEntities().size()) {
            return Resolution.failed(NAME + ".target_not_visible");
        }
        VisibleEntity entity = frame.visibleEntities().get(index);
        if (!isVanillaBoat(entity.entityTypeId())) {
            return Resolution.failed(NAME + ".target_not_boat");
        }
        return Resolution.resolved(frame, entity);
    }

    private FrameValidation validateFrame(
            dev.mcai.companion.waypoint.DimensionRef dimension,
            long expectedSession
    ) {
        Optional<BoatSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return FrameValidation.failed(NAME + ".observation_unavailable");
        }
        BoatSkillFrame frame = current.orElseThrow();
        if (!frame.playerId().equals(expectedPlayerId)) {
            return FrameValidation.failed(NAME + ".player_mismatch");
        }
        if (!frame.dimension().equals(dimension)) {
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
        return FrameValidation.valid(frame);
    }

    static boolean isVanillaBoat(String entityTypeId) {
        if (!entityTypeId.startsWith("minecraft:")) {
            return false;
        }
        String path = entityTypeId.substring("minecraft:".length());
        return path.equals("boat")
                || path.equals("raft")
                || path.endsWith("_boat")
                || path.endsWith("_raft");
    }

    private SkillTickResult fail(String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(SkillFailure reason) {
        failure = reason;
        phase = Phase.FAILED;
        if (boundBoatId != null) {
            actuator.stopBoat(boundBoatId);
        }
        return SkillTickResult.failed(reason);
    }

    private static String actionFailure(ActionOutcome outcome) {
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

    private record Resolution(
            Optional<BoatSkillFrame> frame,
            Optional<VisibleEntity> entity,
            Optional<SkillFailure> failure
    ) {
        static Resolution resolved(
                BoatSkillFrame frame,
                VisibleEntity entity
        ) {
            return new Resolution(
                    Optional.of(frame),
                    Optional.of(entity),
                    Optional.empty()
            );
        }

        static Resolution failed(String code) {
            return failed(SkillFailure.of(code));
        }

        static Resolution failed(SkillFailure failure) {
            return new Resolution(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(failure)
            );
        }
    }
}
