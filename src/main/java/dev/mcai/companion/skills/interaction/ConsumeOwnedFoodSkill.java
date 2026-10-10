package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.VanillaFoodItems;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Equips, consumes and verifies one owned vanilla food item.
 *
 * <p>This compound transaction exists so a natural "eat the golden apple"
 * request cannot stop after conversational acknowledgement or after merely
 * selecting the item. Every mutation uses the ordinary inventory swap and
 * vanilla held-use path. Completion requires a newer own-inventory
 * observation proving that the requested stack count decreased.</p>
 */
public final class ConsumeOwnedFoodSkill
        implements Skill<ConsumeOwnedFoodParameters> {
    public static final String NAME = "consume_owned_food";
    private static final long MAXIMUM_TICKS = 120;
    private static final long VERIFY_TICKS = 30;

    private final UUID expectedPlayerId;
    private final InteractionSkillActuator actuator;
    private final InteractionSkillFrameSource frames;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long sessionGeneration = -1;
    private long startedTick = -1;
    private long useStartedTick = -1;
    private long verifyStartedTick = -1;
    private long baselineObservation = -1;
    private int baselineCount;
    private boolean observedActiveUse;

    public ConsumeOwnedFoodSkill(
            final UUID expectedPlayerId,
            final InteractionSkillActuator actuator,
            final InteractionSkillFrameSource frames
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public SkillParameterParser<ConsumeOwnedFoodParameters> parameters() {
        return InteractionSkillParameters::parseConsumeOwnedFood;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final ConsumeOwnedFoodParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (!VanillaFoodItems.isSafeFood(parameters.itemId())) {
            return Optional.of(SkillFailure.of(
                    NAME + ".unsafe_or_unknown_food"
            ));
        }
        final Optional<InteractionSkillFrame> current = frames.current();
        if (current.isEmpty()
                || !validBinding(current.orElseThrow(), parameters)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".body_unavailable"
            ));
        }
        if (count(current.orElseThrow(), parameters.itemId()) <= 0) {
            return Optional.of(SkillFailure.of(
                    NAME + ".item_not_owned"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final ConsumeOwnedFoodParameters parameters
    ) {
        final InteractionSkillFrame frame = frames.current()
                .filter(value -> validBinding(value, parameters))
                .orElseThrow(() -> new IllegalStateException(
                        "Interaction binding changed"
                ));
        phase = Phase.EQUIPPING;
        failure = null;
        sessionGeneration = frame.sessionGeneration();
        startedTick = context.gameTick();
        baselineObservation = frame.observationRevision();
        baselineCount = count(frame, parameters.itemId());
        observedActiveUse = false;
        useStartedTick = -1;
        verifyStartedTick = -1;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final ConsumeOwnedFoodParameters parameters
    ) {
        if (context.gameTick() - startedTick >= MAXIMUM_TICKS) {
            return fail(NAME + ".timed_out");
        }
        final Optional<InteractionSkillFrame> current = frames.current();
        if (current.isEmpty()
                || !validBoundSession(
                        current.orElseThrow(),
                        parameters
                )) {
            return fail(NAME + ".body_changed");
        }
        final InteractionSkillFrame frame = current.orElseThrow();

        if (phase == Phase.EQUIPPING) {
            final ActionOutcome equipped =
                    actuator.equipMainHand(parameters.itemId());
            if (!equipped.accepted()) {
                return fail(actionFailure("equip", equipped));
            }
            phase = Phase.READY;
            return SkillTickResult.running(true, true);
        }
        if (phase == Phase.READY) {
            final ActionOutcome started =
                    actuator.useItem(ActionHand.MAIN_HAND);
            if (!started.accepted()) {
                return fail(actionFailure("use", started));
            }
            phase = Phase.USING;
            useStartedTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (phase == Phase.USING) {
            final ActionOutcome use =
                    actuator.continueUsing(ActionHand.MAIN_HAND);
            if (use == ActionOutcome.IN_PROGRESS
                    || use == ActionOutcome.QUEUED
                    || use == ActionOutcome.DISPATCHED) {
                observedActiveUse = true;
                return SkillTickResult.running(true, true);
            }
            if (use == ActionOutcome.NO_ACTIVE_ACTION
                    || use == ActionOutcome.COMPLETED) {
                if (!observedActiveUse
                        && context.gameTick() - useStartedTick <= 2L) {
                    return SkillTickResult.running(false, true);
                }
                if (!observedActiveUse) {
                    return fail(NAME + ".use_not_started");
                }
                phase = Phase.VERIFYING;
                verifyStartedTick = context.gameTick();
                return SkillTickResult.running(false, true);
            }
            return fail(actionFailure("continue", use));
        }
        if (phase == Phase.VERIFYING) {
            if (frame.observationRevision() > baselineObservation
                    && count(frame, parameters.itemId())
                        < baselineCount) {
                phase = Phase.COMPLETED;
                return SkillTickResult.completed();
            }
            if (context.gameTick() - verifyStartedTick
                    >= VERIFY_TICKS) {
                return fail(NAME + ".consumption_not_verified");
            }
            return SkillTickResult.running(false, true);
        }
        return SkillTickResult.failed(NAME + ".invalid_state");
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final ConsumeOwnedFoodParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                            + "\"itemId\":\"%s\",\"baseline\":%d}",
                        phase,
                        parameters.dimension().id(),
                        parameters.itemId(),
                        baselineCount
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final ConsumeOwnedFoodParameters parameters
    ) {
        actuator.releaseUse();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final ConsumeOwnedFoodParameters parameters
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

    private boolean validBinding(
            final InteractionSkillFrame frame,
            final ConsumeOwnedFoodParameters parameters
    ) {
        return expectedPlayerId.equals(frame.playerId())
                && parameters.dimension().equals(frame.dimension())
                && actuator.sessionGeneration().isPresent()
                && actuator.sessionGeneration().orElseThrow()
                    == frame.sessionGeneration();
    }

    private boolean validBoundSession(
            final InteractionSkillFrame frame,
            final ConsumeOwnedFoodParameters parameters
    ) {
        return validBinding(frame, parameters)
                && frame.sessionGeneration() == sessionGeneration;
    }

    private static int count(
            final InteractionSkillFrame frame,
            final String itemId
    ) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().equals(itemId))
                .mapToInt(item -> item.count())
                .sum();
    }

    private SkillTickResult fail(final String code) {
        actuator.releaseUse();
        failure = SkillFailure.of(code);
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    private static String actionFailure(
            final String operation,
            final ActionOutcome outcome
    ) {
        return NAME + "." + operation + "_"
                + outcome.name().toLowerCase(Locale.ROOT);
    }

    private enum Phase {
        IDLE,
        EQUIPPING,
        READY,
        USING,
        VERIFYING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
}
