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
 * Performs one ordinary right-click on a visible block surface.
 */
public final class UseBlockSkill implements Skill<UseBlockParameters> {
    private static final String NAME = "use_block";

    /**
     * Ticks allowed for the fair actuator to finish turning onto the target
     * face before an occluded dispatch is reported as a real failure.
     *
     * <p>{@code ActionLimits.defaults()} caps yaw at 20 and pitch at 15
     * degrees per tick, so the worst legal turn - 180 degrees of pitch - needs
     * 12 ticks. Staying inside that bound keeps a genuinely unreachable face
     * reporting the occlusion it actually saw, instead of waiting out the
     * one-shot budget and blaming a timeout.</p>
     */
    private static final int AIM_CONVERGENCE_TICKS = 12;

    private final UUID expectedPlayerId;
    private final InteractionSkillActuator actuator;
    private final InteractionSkillFrameSource frames;
    private final InteractionSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long startedAtGameTick = -1;

    public UseBlockSkill(
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
    public SkillParameterParser<UseBlockParameters> parameters() {
        return InteractionSkillParameters::parseUseBlock;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            UseBlockParameters parameters
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
            UseBlockParameters parameters
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
            UseBlockParameters parameters
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
                InteractionSkillValidation.resolveRetainedVisibleBlock(
                NAME,
                frames,
                validation.frame().orElseThrow(),
                parameters.target(),
                policy
        );
        if (target.failure().isPresent()) {
            return fail(target.failure().orElseThrow());
        }
        BlockInteractionTarget resolved = target.target().orElseThrow();
        /*
         * Turn toward the observed hit point before dispatching.  The
         * actuator independently revalidates that the target is genuinely
         * under the crosshair, so a skill that never turns can only act on
         * whatever the body already happened to face and reports every other
         * legal target as TARGET_OCCLUDED.
         *
         * The resolution above already proved that this exact face was hit by
         * a real eye ray in both the authored and the current frame, so the
         * face is reachable by construction; the only thing missing was the
         * rotation.  Aiming is an intent, and the fair actuator honours the
         * ordinary per-tick rotation limit, so the turn needs a few ticks.
         * Retry inside a bounded convergence window instead of failing on the
         * first tick, but report the original occlusion code once that window
         * closes: an aim that never arrives is still an occlusion, and
         * masking it as a timeout would hide the real cause.
         */
        boolean aiming = actuator.lookAt(resolved.hitPoint()).accepted();
        ActionOutcome outcome = actuator.useOnBlock(
                parameters.hand(),
                resolved
        );
        if (!outcome.accepted()) {
            if (aiming
                    && outcome == ActionOutcome.TARGET_OCCLUDED
                    && context.gameTick() - startedAtGameTick
                        < AIM_CONVERGENCE_TICKS) {
                return SkillTickResult.running(true, true);
            }
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
            UseBlockParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"x\":%d,\"y\":%d,\"z\":%d,"
                                + "\"hand\":\"%s\",\"session\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.target().x(),
                        parameters.target().y(),
                        parameters.target().z(),
                        parameters.hand().name(),
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            SkillContext context,
            UseBlockParameters parameters
    ) {
        releaseUseIfStillBound();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            SkillContext context,
            UseBlockParameters parameters
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
            UseBlockParameters parameters,
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
            UseBlockParameters parameters
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
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
