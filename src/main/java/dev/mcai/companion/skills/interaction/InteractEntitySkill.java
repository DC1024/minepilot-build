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
 * Performs one ordinary vanilla interaction with a currently visible entity.
 * The semantic observation id is resolved locally and never exposes an
 * entity UUID to the model.
 */
public final class InteractEntitySkill
        implements Skill<InteractEntityParameters> {
    private static final String NAME = "interact_entity";

    private final UUID expectedPlayerId;
    private final InteractionSkillActuator actuator;
    private final InteractionSkillFrameSource frames;
    private final InteractionSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long startedAtGameTick = -1;

    public InteractEntitySkill(
            final UUID expectedPlayerId,
            final InteractionSkillActuator actuator,
            final InteractionSkillFrameSource frames,
            final InteractionSkillPolicy policy
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
    public SkillParameterParser<InteractEntityParameters> parameters() {
        return InteractionSkillParameters::parseInteractEntity;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final InteractEntityParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final var validation = validateFrame(parameters, -1);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        return InteractionSkillValidation.resolveRetainedVisibleEntity(
                NAME,
                frames,
                validation.frame().orElseThrow(),
                parameters.observedTarget(),
                policy
        ).failure();
    }

    @Override
    public void start(
            final SkillContext context,
            final InteractEntityParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final InteractionSkillFrame frame =
                requireStartFrame(parameters);
        phase = Phase.READY;
        failure = null;
        boundSessionGeneration = frame.sessionGeneration();
        startedAtGameTick = context.gameTick();
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final InteractEntityParameters parameters
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
        final var validation = validateFrame(
                parameters,
                boundSessionGeneration
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final var target =
                InteractionSkillValidation.resolveRetainedVisibleEntity(
                        NAME,
                        frames,
                        validation.frame().orElseThrow(),
                        parameters.observedTarget(),
                        policy
                );
        if (target.failure().isPresent()) {
            return fail(target.failure().orElseThrow());
        }
        final ActionOutcome outcome = actuator.interactEntity(
                target.entityId().orElseThrow(),
                parameters.hand()
        );
        if (!outcome.accepted()) {
            return fail(
                    InteractionSkillValidation.actionFailure(
                            NAME,
                            outcome
                    )
            );
        }
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final InteractEntityParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"sampleSequence\":%d,"
                                + "\"observationId\":\"%s\","
                                + "\"hand\":\"%s\",\"session\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.sampleSequence(),
                        parameters.observationId(),
                        parameters.hand().name(),
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final InteractEntityParameters parameters
    ) {
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final InteractEntityParameters parameters
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
            final InteractEntityParameters parameters,
            final long sessionGeneration
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
            final InteractEntityParameters parameters
    ) {
        final var validation = validateFrame(parameters, -1);
        if (validation.failure().isPresent()) {
            throw new IllegalStateException(
                    "Interaction binding changed"
            );
        }
        return validation.frame().orElseThrow();
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
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
