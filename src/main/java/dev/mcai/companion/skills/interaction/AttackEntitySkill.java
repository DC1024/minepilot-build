package dev.mcai.companion.skills.interaction;

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
import java.util.UUID;

/**
 * Performs one vanilla attack against a currently visible entity UUID.
 */
public final class AttackEntitySkill
        implements Skill<AttackEntityParameters> {
    private static final String NAME = "attack_entity";

    private final UUID expectedPlayerId;
    private final InteractionSkillActuator actuator;
    private final InteractionSkillFrameSource frames;
    private final InteractionSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long startedAtGameTick = -1;

    public AttackEntitySkill(
            UUID expectedPlayerId,
            InteractionSkillActuator actuator,
            InteractionSkillFrameSource frames,
            InteractionSkillPolicy policy
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
    public SkillParameterParser<AttackEntityParameters> parameters() {
        return InteractionSkillParameters::parseAttackEntity;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            AttackEntityParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        var validation = validateFrame(parameters, -1);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        return InteractionSkillValidation.resolveRetainedVisibleEntity(
                NAME,
                frames,
                validation.frame().orElseThrow(),
                parameters,
                policy
        ).failure();
    }

    @Override
    public void start(
            SkillContext context,
            AttackEntityParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        InteractionSkillFrame frame = requireStartFrame(parameters);
        phase = Phase.READY;
        failure = null;
        boundSessionGeneration = frame.sessionGeneration();
        startedAtGameTick = context.gameTick();
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            AttackEntityParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase != Phase.READY) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        if (context.gameTick() - startedAtGameTick
                >= policy.oneShotTimeoutTicks()) {
            return fail(NAME + ".timed_out");
        }
        var validation = validateFrame(
                parameters,
                boundSessionGeneration
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        var target =
                InteractionSkillValidation.resolveRetainedVisibleEntity(
                NAME,
                frames,
                validation.frame().orElseThrow(),
                parameters,
                policy
        );
        if (target.failure().isPresent()) {
            return fail(target.failure().orElseThrow());
        }
        ActionOutcome outcome = actuator.attack(
                target.entityId().orElseThrow()
        );
        if (!outcome.accepted()) {
            return fail(
                    InteractionSkillValidation.actionFailure(NAME, outcome)
            );
        }
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    @Override
    public SkillCheckpoint checkpoint(
            SkillContext context,
            AttackEntityParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"sampleSequence\":%d,"
                                + "\"observationId\":\"%s\","
                                + "\"session\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.sampleSequence(),
                        parameters.observationId(),
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            SkillContext context,
            AttackEntityParameters parameters
    ) {
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            SkillContext context,
            AttackEntityParameters parameters
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

    private InteractionSkillValidation.FrameValidation validateFrame(
            AttackEntityParameters parameters,
            long sessionGeneration
    ) {
        return InteractionSkillValidation.frame(
                NAME,
                expectedPlayerId,
                parameters.dimension(),
                sessionGeneration,
                actuator,
                frames,
                policy
        );
    }

    private InteractionSkillFrame requireStartFrame(
            AttackEntityParameters parameters
    ) {
        var validation = validateFrame(parameters, -1);
        if (validation.failure().isPresent()) {
            throw new IllegalStateException("Interaction binding changed");
        }
        return validation.frame().orElseThrow();
    }

    private SkillTickResult fail(String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(SkillFailure reason) {
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private enum Phase {
        IDLE,
        READY,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
