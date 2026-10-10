package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
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
 * Mines one currently visible block through vanilla destroy packets.
 */
public final class BreakBlockSkill implements Skill<BreakBlockParameters> {
    private static final String NAME = "break_block";

    private final UUID expectedPlayerId;
    private final InteractionSkillActuator actuator;
    private final InteractionSkillFrameSource frames;
    private final InteractionSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long startedAtGameTick = -1;
    private long lastObservationRevision = -1;
    private BlockInteractionTarget boundTarget;

    public BreakBlockSkill(
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
    public SkillParameterParser<BreakBlockParameters> parameters() {
        return InteractionSkillParameters::parseBreakBlock;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            BreakBlockParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        var validation = validateFrame(parameters, -1);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        return InteractionSkillValidation.resolveRetainedVisibleBlock(
                NAME,
                frames,
                validation.frame().orElseThrow(),
                parameters.target(),
                policy
        ).failure();
    }

    @Override
    public void start(
            SkillContext context,
            BreakBlockParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        InteractionSkillFrame frame = requireStartFrame(parameters);
        var resolution =
                InteractionSkillValidation.resolveRetainedVisibleBlock(
                NAME,
                frames,
                frame,
                parameters.target(),
                policy
        );
        if (resolution.failure().isPresent()) {
            throw new IllegalStateException(
                    "Observed mining target changed before binding"
            );
        }
        phase = Phase.READY;
        failure = null;
        boundSessionGeneration = frame.sessionGeneration();
        startedAtGameTick = context.gameTick();
        lastObservationRevision = frame.observationRevision();
        boundTarget = resolution.target().orElseThrow();
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            BreakBlockParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase != Phase.READY && phase != Phase.MINING) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        if (timedOut(context)) {
            return fail("break_block.timed_out");
        }
        var validation = validateFrame(
                parameters,
                boundSessionGeneration
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        InteractionSkillFrame frame = validation.frame().orElseThrow();
        if (frame.observationRevision() < lastObservationRevision) {
            return fail("break_block.stale_observation");
        }
        lastObservationRevision = frame.observationRevision();

        if (phase == Phase.READY) {
            ActionOutcome outcome = actuator.beginMining(
                    Objects.requireNonNull(boundTarget)
            );
            if (outcome == ActionOutcome.COMPLETED) {
                phase = Phase.COMPLETED;
                return SkillTickResult.completed();
            }
            if (!outcome.accepted()) {
                return fail(
                        InteractionSkillValidation.actionFailure(
                                NAME,
                                outcome
                        )
                );
            }
            phase = Phase.MINING;
            return SkillTickResult.running(true, true);
        }

        ActionOutcome outcome = actuator.continueMining();
        if (outcome == ActionOutcome.COMPLETED) {
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
            BreakBlockParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"x\":%d,\"y\":%d,\"z\":%d,"
                                + "\"session\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.target().x(),
                        parameters.target().y(),
                        parameters.target().z(),
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            SkillContext context,
            BreakBlockParameters parameters
    ) {
        releaseMiningIfStillBound();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            SkillContext context,
            BreakBlockParameters parameters
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
            BreakBlockParameters parameters,
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
            BreakBlockParameters parameters
    ) {
        var validation = validateFrame(parameters, -1);
        if (validation.failure().isPresent()) {
            throw new IllegalStateException("Interaction binding changed");
        }
        return validation.frame().orElseThrow();
    }

    private boolean timedOut(SkillContext context) {
        return context.gameTick() - startedAtGameTick
                >= policy.blockBreakTimeoutTicks();
    }

    private SkillTickResult fail(String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(SkillFailure reason) {
        releaseMiningIfStillBound();
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private void releaseMiningIfStillBound() {
        OptionalLong current = actuator.sessionGeneration();
        if (current.isPresent()
                && current.orElseThrow() == boundSessionGeneration) {
            actuator.abortMining();
        }
    }

    private enum Phase {
        IDLE,
        READY,
        MINING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
