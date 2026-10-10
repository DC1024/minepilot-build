package dev.mcai.companion.skills.core;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.perception.PerceptionVec3;
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
import java.util.UUID;

public final class LookAtSkill implements Skill<LookAtParameters> {
    private static final double COMPLETION_TOLERANCE_DEGREES = 2.0;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private double lastError = Double.POSITIVE_INFINITY;
    private long lastObservationRevision = -1;

    public LookAtSkill(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public SkillParameterParser<LookAtParameters> parameters() {
        return CoreSkillParameters::parseLookAt;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            LookAtParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        return validateFrame(parameters).failure();
    }

    @Override
    public void start(SkillContext context, LookAtParameters parameters) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        phase = Phase.RUNNING;
        failure = null;
        lastError = Double.POSITIVE_INFINITY;
        lastObservationRevision = -1;
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            LookAtParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase != Phase.RUNNING) {
            return SkillTickResult.failed("look_at.invalid_state");
        }
        FrameValidation validation = validateFrame(parameters);
        if (validation.failure().isPresent()) {
            return fail(validation.frame(), validation.failure().orElseThrow());
        }
        CoreSkillFrame frame = validation.frame().orElseThrow();
        PerceptionVec3 delta = parameters.target().subtract(frame.eyePosition());
        if (delta.lengthSquared() <= 1.0E-12) {
            return complete(frame);
        }
        double error = CoreSkillGeometry.angularErrorDegrees(
                frame.lookDirection(),
                delta
        );
        if (error <= COMPLETION_TOLERANCE_DEGREES) {
            return complete(frame);
        }

        LookIntent look = CoreSkillGeometry.lookAt(
                frame.eyePosition(),
                parameters.target()
        );
        ActionOutcome stopOutcome = actuator.stop();
        ActionOutcome lookOutcome = actuator.look(look);
        if (!stopOutcome.accepted() || !lookOutcome.accepted()) {
            return fail(
                    Optional.of(frame),
                    SkillFailure.of("look_at.actuator_rejected")
            );
        }
        boolean newObservation =
                frame.observationRevision() > lastObservationRevision;
        boolean madeProgress = lastObservationRevision < 0
                || newObservation && error + 0.1 < lastError;
        lastObservationRevision = frame.observationRevision();
        lastError = error;
        return SkillTickResult.running(madeProgress, true);
    }

    @Override
    public SkillCheckpoint checkpoint(
            SkillContext context,
            LookAtParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"x\":%.6f,\"y\":%.6f,\"z\":%.6f}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.x(),
                        parameters.y(),
                        parameters.z()
                )
        );
    }

    @Override
    public void cancel(SkillContext context, LookAtParameters parameters) {
        frames.current().ifPresent(frame -> CoreSkillSafety.quiesce(actuator, frame));
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(SkillContext context, LookAtParameters parameters) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(Objects.requireNonNull(failure));
            default -> SkillResult.failed(SkillFailure.of("look_at.invalid_state"));
        };
    }

    private SkillTickResult complete(CoreSkillFrame frame) {
        if (!CoreSkillSafety.quiesce(actuator, frame)) {
            return fail(
                    Optional.of(frame),
                    SkillFailure.of("look_at.actuator_rejected")
            );
        }
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private SkillTickResult fail(
            Optional<CoreSkillFrame> frame,
            SkillFailure reason
    ) {
        frame.ifPresent(value -> CoreSkillSafety.quiesce(actuator, value));
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private FrameValidation validateFrame(LookAtParameters parameters) {
        Optional<CoreSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return FrameValidation.failed("look_at.observation_unavailable");
        }
        CoreSkillFrame frame = current.orElseThrow();
        if (!frame.playerId().equals(expectedPlayerId)) {
            return FrameValidation.failed(frame, "look_at.player_mismatch");
        }
        if (!frame.dimension().equals(parameters.dimension())) {
            return FrameValidation.failed(frame, "look_at.dimension_mismatch");
        }
        return FrameValidation.valid(frame);
    }

    private enum Phase {
        IDLE,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private record FrameValidation(
            Optional<CoreSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        private static FrameValidation valid(CoreSkillFrame frame) {
            return new FrameValidation(Optional.of(frame), Optional.empty());
        }

        private static FrameValidation failed(String code) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }

        private static FrameValidation failed(CoreSkillFrame frame, String code) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }
}
