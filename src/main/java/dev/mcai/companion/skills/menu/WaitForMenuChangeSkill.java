package dev.mcai.companion.skills.menu;

import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.Objects;
import java.util.Optional;

final class WaitForMenuChangeSkill
        implements Skill<WaitForMenuChangeParameters> {
    private final MenuSkillActuator actuator;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick;
    private long boundSessionGeneration = -1;

    WaitForMenuChangeSkill(final MenuSkillActuator actuator) {
        this.actuator = Objects.requireNonNull(actuator, "actuator");
    }

    @Override
    public SkillParameterParser<WaitForMenuChangeParameters> parameters() {
        return MenuSkillParameters::parseWait;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final WaitForMenuChangeParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        return actuator.checkBinding(parameters.binding()).failure();
    }

    @Override
    public void start(
            final SkillContext context,
            final WaitForMenuChangeParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final var generation = actuator.sessionGeneration();
        if (generation.isEmpty()) {
            failure = SkillFailure.of(
                    "wait_for_menu_change.player_unavailable"
            );
            phase = Phase.FAILED;
            return;
        }
        startedAtTick = context.gameTick();
        boundSessionGeneration = generation.orElseThrow();
        failure = null;
        phase = Phase.RUNNING;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final WaitForMenuChangeParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase == Phase.FAILED) {
            return SkillTickResult.failed(
                    Objects.requireNonNull(failure)
            );
        }
        if (phase != Phase.RUNNING) {
            return SkillTickResult.failed(
                    "wait_for_menu_change.invalid_state"
            );
        }
        final MenuChangeState change = actuator.observeChange(
                parameters.binding(),
                boundSessionGeneration
        );
        switch (change) {
            case CHANGED, CLOSED -> {
                phase = Phase.COMPLETED;
                return SkillTickResult.completed();
            }
            case PLAYER_UNAVAILABLE -> {
                return fail(
                        "wait_for_menu_change.player_unavailable"
                );
            }
            case SESSION_MISMATCH -> {
                return fail(
                        "wait_for_menu_change.session_mismatch"
                );
            }
            case MENU_REPLACED -> {
                return fail("wait_for_menu_change.menu_replaced");
            }
            case UNCHANGED -> {
                if (context.gameTick() - startedAtTick
                        >= parameters.timeoutTicks()) {
                    return fail("wait_for_menu_change.timeout");
                }
                return SkillTickResult.running(true, true);
            }
            default -> throw new IllegalStateException(
                    "Unhandled menu change state"
            );
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final WaitForMenuChangeParameters parameters
    ) {
        final long elapsed = Math.max(
                0,
                context.gameTick() - startedAtTick
        );
        return new SkillCheckpoint(
                1,
                "{\"phase\":\""
                        + phase.name()
                        + "\",\"containerId\":"
                        + parameters.binding().containerId()
                        + ",\"stateId\":"
                        + parameters.binding().stateId()
                        + ",\"elapsedTicks\":"
                        + elapsed
                        + "}"
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final WaitForMenuChangeParameters parameters
    ) {
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final WaitForMenuChangeParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(
                    SkillFailure.of(
                            "wait_for_menu_change.invalid_state"
                    )
            );
        };
    }

    private SkillTickResult fail(final String code) {
        failure = SkillFailure.of(code);
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    private enum Phase {
        IDLE,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
