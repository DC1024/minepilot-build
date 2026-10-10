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
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Taps or holds one item-use input, then deterministically releases it.
 */
public final class UseItemSkill implements Skill<UseItemParameters> {
    private static final String NAME = "use_item";

    private final UUID expectedPlayerId;
    private final InteractionSkillActuator actuator;
    private final InteractionSkillFrameSource frames;
    private final InteractionSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long startedAtGameTick = -1;
    private long useStartedAtGameTick = -1;

    public UseItemSkill(
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
    public SkillParameterParser<UseItemParameters> parameters() {
        return InteractionSkillParameters::parseUseItem;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            UseItemParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (parameters.holdTicks() > policy.maximumUseHoldTicks()) {
            return Optional.of(
                    SkillFailure.of("use_item.duration_too_long")
            );
        }
        var validation = validateFrame(parameters, -1);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        return InteractionSkillValidation.heldItem(
                NAME,
                validation.frame().orElseThrow(),
                parameters.hand()
        );
    }

    @Override
    public void start(
            SkillContext context,
            UseItemParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        InteractionSkillFrame frame = requireStartFrame(parameters);
        phase = Phase.READY;
        failure = null;
        boundSessionGeneration = frame.sessionGeneration();
        startedAtGameTick = context.gameTick();
        useStartedAtGameTick = -1;
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            UseItemParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase != Phase.READY && phase != Phase.USING) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        long maximumDuration = Math.addExact(
                policy.oneShotTimeoutTicks(),
                parameters.holdTicks()
        );
        if (context.gameTick() - startedAtGameTick >= maximumDuration) {
            return fail(NAME + ".timed_out");
        }
        var validation = validateFrame(
                parameters,
                boundSessionGeneration
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }

        if (phase == Phase.READY) {
            Optional<SkillFailure> itemFailure =
                    InteractionSkillValidation.heldItem(
                            NAME,
                            validation.frame().orElseThrow(),
                            parameters.hand()
                    );
            if (itemFailure.isPresent()) {
                return fail(itemFailure.orElseThrow());
            }
            ActionOutcome outcome = actuator.useItem(parameters.hand());
            if (!outcome.accepted()) {
                return fail(
                        InteractionSkillValidation.actionFailure(
                                NAME,
                                outcome
                        )
                );
            }
            useStartedAtGameTick = context.gameTick();
            if (parameters.holdTicks() == 0) {
                return releaseAndComplete();
            }
            phase = Phase.USING;
            return SkillTickResult.running(true, true);
        }

        if (context.gameTick() - useStartedAtGameTick
                >= parameters.holdTicks()) {
            return releaseAndComplete();
        }
        ActionOutcome outcome = actuator.continueUsing(parameters.hand());
        if (outcome == ActionOutcome.NO_ACTIVE_ACTION
                || outcome == ActionOutcome.COMPLETED) {
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (outcome.accepted()) {
            return SkillTickResult.running(true, true);
        }
        return fail(
                InteractionSkillValidation.actionFailure(NAME, outcome)
        );
    }

    @Override
    public SkillCheckpoint checkpoint(
            SkillContext context,
            UseItemParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"hand\":\"%s\",\"holdTicks\":%d,"
                                + "\"session\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.hand().name(),
                        parameters.holdTicks(),
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            SkillContext context,
            UseItemParameters parameters
    ) {
        releaseUseIfStillBound();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            SkillContext context,
            UseItemParameters parameters
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
            UseItemParameters parameters,
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
            UseItemParameters parameters
    ) {
        var validation = validateFrame(parameters, -1);
        if (validation.failure().isPresent()) {
            throw new IllegalStateException("Interaction binding changed");
        }
        return validation.frame().orElseThrow();
    }

    private SkillTickResult releaseAndComplete() {
        ActionOutcome released = actuator.releaseUse();
        if (!InteractionSkillValidation.releaseSucceeded(released)) {
            return fail(
                    InteractionSkillValidation.actionFailure(
                            NAME,
                            released
                    )
            );
        }
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private SkillTickResult fail(String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(SkillFailure reason) {
        releaseUseIfStillBound();
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private void releaseUseIfStillBound() {
        OptionalLong current = actuator.sessionGeneration();
        if (current.isPresent()
                && current.orElseThrow() == boundSessionGeneration) {
            actuator.releaseUse();
        }
    }

    private enum Phase {
        IDLE,
        READY,
        USING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
