package dev.mcai.companion.skills.core;

import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SafeIdleSkill implements Skill<NoParameters> {
    private static final SkillFailure REQUESTED_SAFE_IDLE =
            SkillFailure.of("requested_safe_idle");

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;
    private Phase phase = Phase.IDLE;

    public SafeIdleSkill(
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
    public SkillParameterParser<NoParameters> parameters() {
        return CoreSkillParameters::parseNone;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        Optional<CoreSkillFrame> frame = frames.current();
        if (frame.isEmpty()) {
            return Optional.of(SkillFailure.of("safe_idle.observation_unavailable"));
        }
        return frame.orElseThrow().playerId().equals(expectedPlayerId)
                ? Optional.empty()
                : Optional.of(SkillFailure.of("safe_idle.player_mismatch"));
    }

    @Override
    public void start(SkillContext context, NoParameters parameters) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        phase = Phase.RUNNING;
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            NoParameters parameters
    ) {
        if (phase != Phase.RUNNING) {
            return SkillTickResult.failed("safe_idle.invalid_state");
        }
        Optional<CoreSkillFrame> frame = frames.current();
        if (frame.isEmpty()
                || !frame.orElseThrow().playerId().equals(expectedPlayerId)
                || !CoreSkillSafety.quiesce(actuator, frame.orElseThrow())) {
            phase = Phase.FAILED;
            return SkillTickResult.failed("safe_idle.actuator_rejected");
        }
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    @Override
    public SkillCheckpoint checkpoint(
            SkillContext context,
            NoParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                "{\"phase\":\"" + phase.name() + "\"}"
        );
    }

    @Override
    public void cancel(SkillContext context, NoParameters parameters) {
        frames.current().ifPresent(frame -> CoreSkillSafety.quiesce(actuator, frame));
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            SkillContext context,
            NoParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.safeIdle(REQUESTED_SAFE_IDLE);
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    SkillFailure.of("safe_idle.actuator_rejected")
            );
            default -> SkillResult.failed(
                    SkillFailure.of("safe_idle.invalid_state")
            );
        };
    }

    private enum Phase {
        IDLE,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
