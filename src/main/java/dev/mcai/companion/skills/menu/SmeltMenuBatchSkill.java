package dev.mcai.companion.skills.menu;

import dev.mcai.companion.perception.MenuSlotSummary;
import dev.mcai.companion.perception.OpenMenuSnapshot;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Drives one normal smelting batch while a furnace-family GUI remains open.
 *
 * <p>This class reads only the fair {@link MenuSkillFrame} published from the
 * companion's open GUI. It does not inspect a block entity, recipe registry,
 * hidden container, or world chunk. Every mutation delegates to the same
 * exact, rollback-capable vanilla click actuator used by the primitive menu
 * skills.</p>
 */
public final class SmeltMenuBatchSkill
        implements Skill<SmeltMenuBatchParameters> {
    private static final String NAME = "smelt_menu_batch";
    private static final int INPUT_SLOT = 0;
    private static final int FUEL_SLOT = 1;
    private static final int OUTPUT_SLOT = 2;
    private static final long BASE_TIMEOUT_TICKS = 400L;
    private static final long TIMEOUT_TICKS_PER_ITEM = 240L;

    private final UUID expectedPlayerId;
    private final MenuSkillActuator actuator;
    private final MenuSkillFrameSource frames;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private int boundContainerId = -1;
    private long lastSampleSequence = -1;
    private long startedAtTick = -1;
    private int remainingInput;
    private int remainingFuel;
    private int collectedOutput;

    public SmeltMenuBatchSkill(
            final UUID expectedPlayerId,
            final MenuSkillActuator actuator,
            final MenuSkillFrameSource frames
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public SkillParameterParser<SmeltMenuBatchParameters> parameters() {
        return MenuSkillParameters::parseSmeltBatch;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final SmeltMenuBatchParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<MenuSkillFrame> current =
                frames.retained(parameters.sampleSequence());
        if (current.isEmpty()) {
            return Optional.of(failure("menu_unavailable"));
        }
        final MenuSkillFrame frame = current.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return Optional.of(failure("player_mismatch"));
        }
        if (frame.sampleSequence() != parameters.sampleSequence()) {
            return Optional.of(failure("sample_not_current"));
        }
        final OptionalLong session = actuator.sessionGeneration();
        if (session.isEmpty()
                || session.orElseThrow()
                        != frame.sessionGeneration()) {
            return Optional.of(failure("session_mismatch"));
        }
        final Optional<SkillFailure> menuFailure =
                validateFurnaceFrame(frame);
        if (menuFailure.isPresent()) {
            return menuFailure;
        }
        final OpenMenuSnapshot menu = frame.menu();
        if (!isEmpty(slot(menu, INPUT_SLOT))
                || !isEmpty(slot(menu, OUTPUT_SLOT))) {
            return Optional.of(failure("clean_input_output_required"));
        }
        if (!isEmpty(slot(menu, FUEL_SLOT))) {
            return Optional.of(failure("clean_fuel_slot_required"));
        }
        final int requiredInput = parameters.inputItemId().equals(
                parameters.fuelItemId()
        )
                ? Math.addExact(
                        parameters.count(),
                        parameters.fuelCount()
                )
                : parameters.count();
        if (playerCount(menu, parameters.inputItemId())
                < requiredInput
                || !parameters.inputItemId().equals(
                        parameters.fuelItemId()
                )
                && playerCount(menu, parameters.fuelItemId())
                        < parameters.fuelCount()) {
            return Optional.of(failure("items_unavailable"));
        }
        final MenuBinding binding = binding(frame);
        final MenuSlotSummary inputSource = playerSource(
                menu,
                parameters.inputItemId()
        ).orElseThrow();
        final MenuOperationResult inputCheck = actuator.checkTransfer(
                new TransferMenuItemParameters(
                        binding,
                        inputSource.slot(),
                        INPUT_SLOT,
                        Math.min(inputSource.count(), parameters.count())
                )
        );
        if (!inputCheck.succeeded()) {
            return Optional.of(inputCheck.failure().orElseThrow());
        }
        /*
         * When input and fuel use the same item, the first transfer changes
         * the source stack. The actual fuel preflight therefore happens only
         * after the fresh post-input observation.
         */
        if (!parameters.inputItemId().equals(
                parameters.fuelItemId()
        )) {
            final MenuSlotSummary fuelSource = playerSource(
                    menu,
                    parameters.fuelItemId()
            ).orElseThrow();
            final MenuOperationResult fuelCheck = actuator.checkTransfer(
                    new TransferMenuItemParameters(
                            binding,
                            fuelSource.slot(),
                            FUEL_SLOT,
                            Math.min(
                                    fuelSource.count(),
                                    parameters.fuelCount()
                            )
                    )
            );
            if (!fuelCheck.succeeded()) {
                return Optional.of(fuelCheck.failure().orElseThrow());
            }
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final SmeltMenuBatchParameters parameters
    ) {
        final MenuSkillFrame frame = frames
                .retained(parameters.sampleSequence())
                .orElseThrow(() -> new IllegalStateException(
                        "Smelting menu disappeared before start"
                ));
        if (!expectedPlayerId.equals(frame.playerId())
                || frame.sampleSequence()
                        != parameters.sampleSequence()) {
            throw new IllegalStateException(
                    "Smelting binding changed before start"
            );
        }
        phase = Phase.LOAD_INPUT;
        failure = null;
        boundSessionGeneration = frame.sessionGeneration();
        boundContainerId = frame.menu().containerId();
        lastSampleSequence = frame.sampleSequence() - 1L;
        startedAtTick = context.gameTick();
        remainingInput = parameters.count();
        remainingFuel = parameters.fuelCount();
        collectedOutput = 0;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final SmeltMenuBatchParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        if (context.gameTick() - startedAtTick
                >= timeoutTicks(parameters.count())) {
            return fail("timeout");
        }
        final Optional<MenuSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return fail("menu_closed");
        }
        final MenuSkillFrame frame = current.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return fail("player_mismatch");
        }
        if (frame.sessionGeneration()
                    != boundSessionGeneration) {
            return fail("session_mismatch");
        }
        if (frame.menu().containerId() != boundContainerId) {
            return fail("menu_replaced");
        }
        final OptionalLong session = actuator.sessionGeneration();
        if (session.isEmpty()
                || session.orElseThrow()
                        != boundSessionGeneration) {
            return fail("session_mismatch");
        }
        final Optional<SkillFailure> menuFailure =
                validateFurnaceFrame(frame);
        if (menuFailure.isPresent()) {
            return fail(menuFailure.orElseThrow());
        }
        if (frame.sampleSequence() < lastSampleSequence) {
            return fail("stale_observation");
        }
        if (frame.sampleSequence() == lastSampleSequence) {
            return SkillTickResult.running(false, true);
        }
        lastSampleSequence = frame.sampleSequence();

        return switch (phase) {
            case LOAD_INPUT -> load(
                    frame,
                    parameters.inputItemId(),
                    INPUT_SLOT,
                    true
            );
            case LOAD_FUEL -> load(
                    frame,
                    parameters.fuelItemId(),
                    FUEL_SLOT,
                    false
            );
            case WAIT_OUTPUT -> collectOutput(frame, parameters);
            default -> SkillTickResult.failed(
                    NAME + ".invalid_state"
            );
        };
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final SmeltMenuBatchParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                "{\"phase\":\"" + phase.name()
                        + "\",\"containerId\":" + boundContainerId
                        + ",\"remainingInput\":" + remainingInput
                        + ",\"remainingFuel\":" + remainingFuel
                        + ",\"collectedOutput\":" + collectedOutput
                        + ",\"requestedOutput\":" + parameters.count()
                        + ",\"lastSample\":" + lastSampleSequence
                        + "}"
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final SmeltMenuBatchParameters parameters
    ) {
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final SmeltMenuBatchParameters parameters
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

    private SkillTickResult load(
            final MenuSkillFrame frame,
            final String itemId,
            final int destinationSlot,
            final boolean input
    ) {
        final OpenMenuSnapshot menu = frame.menu();
        final MenuSlotSummary destination = slot(
                menu,
                destinationSlot
        );
        if (!isEmpty(destination)
                && !destination.itemId().equals(itemId)) {
            return fail(
                    input
                            ? "input_slot_changed"
                            : "fuel_slot_changed"
            );
        }
        final int remaining = input
                ? remainingInput
                : remainingFuel;
        if (remaining <= 0) {
            phase = input ? Phase.LOAD_FUEL : Phase.WAIT_OUTPUT;
            return SkillTickResult.running(true, true);
        }
        final Optional<MenuSlotSummary> source =
                playerSource(menu, itemId);
        if (source.isEmpty()) {
            return fail("items_unavailable");
        }
        final int transferCount = Math.min(
                remaining,
                source.orElseThrow().count()
        );
        final MenuOperationResult transferred = actuator.transfer(
                new TransferMenuItemParameters(
                        binding(frame),
                        source.orElseThrow().slot(),
                        destinationSlot,
                        transferCount
                )
        );
        if (!transferred.succeeded()) {
            return fail(transferred.failure().orElseThrow());
        }
        if (transferred.affectedCount() < 1
                || transferred.affectedCount() > transferCount) {
            return fail("invalid_transfer_count");
        }
        if (input) {
            remainingInput -= transferred.affectedCount();
            if (remainingInput == 0) {
                phase = Phase.LOAD_FUEL;
            }
        } else {
            remainingFuel -= transferred.affectedCount();
            if (remainingFuel == 0) {
                phase = Phase.WAIT_OUTPUT;
            }
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult collectOutput(
            final MenuSkillFrame frame,
            final SmeltMenuBatchParameters parameters
    ) {
        final MenuSlotSummary output = slot(
                frame.menu(),
                OUTPUT_SLOT
        );
        if (isEmpty(output)) {
            return SkillTickResult.running(true, true);
        }
        if (!output.itemId().equals(parameters.outputItemId())) {
            return fail("unexpected_output");
        }
        final int stillNeeded =
                parameters.count() - collectedOutput;
        if (output.count() > stillNeeded) {
            return fail("unexpected_output_count");
        }
        final MenuOperationResult taken = actuator.quickMove(
                new ObservedMenuSlotParameters(
                        binding(frame),
                        OUTPUT_SLOT
                ),
                true
        );
        if (!taken.succeeded()) {
            return fail(taken.failure().orElseThrow());
        }
        if (taken.affectedCount() != output.count()) {
            return fail("invalid_output_count");
        }
        collectedOutput += taken.affectedCount();
        if (collectedOutput >= parameters.count()) {
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        return SkillTickResult.running(true, true);
    }

    private static Optional<SkillFailure> validateFurnaceFrame(
            final MenuSkillFrame frame
    ) {
        final OpenMenuSnapshot menu = frame.menu();
        if (!isFurnaceMenu(menu)
                || menu.slots().size() < 3) {
            return Optional.of(failure("furnace_menu_required"));
        }
        if (!menu.carried().itemId().equals("minecraft:air")
                || menu.carried().count() != 0) {
            return Optional.of(failure("cursor_not_empty"));
        }
        for (int slot = 0; slot <= OUTPUT_SLOT; slot++) {
            final MenuSlotSummary summary = slot(menu, slot);
            if (summary.playerInventory()
                    || summary.slot() != slot) {
                return Optional.of(failure("furnace_slots_invalid"));
            }
        }
        return Optional.empty();
    }

    private static boolean isFurnaceMenu(
            final OpenMenuSnapshot menu
    ) {
        return switch (menu.menuType()) {
            case "minecraft:furnace",
                    "minecraft:blast_furnace",
                    "minecraft:smoker" -> true;
            default -> false;
        };
    }

    private static MenuSlotSummary slot(
            final OpenMenuSnapshot menu,
            final int slot
    ) {
        return menu.slots().stream()
                .filter(summary -> summary.slot() == slot)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Observed menu slot is missing"
                ));
    }

    private static Optional<MenuSlotSummary> playerSource(
            final OpenMenuSnapshot menu,
            final String itemId
    ) {
        return menu.slots().stream()
                .filter(MenuSlotSummary::playerInventory)
                .filter(MenuSlotSummary::mayPickup)
                .filter(slot -> slot.itemId().equals(itemId))
                .filter(slot -> slot.count() > 0)
                .min(java.util.Comparator.comparingInt(
                        MenuSlotSummary::slot
                ));
    }

    private static int playerCount(
            final OpenMenuSnapshot menu,
            final String itemId
    ) {
        return menu.slots().stream()
                .filter(MenuSlotSummary::playerInventory)
                .filter(slot -> slot.itemId().equals(itemId))
                .mapToInt(MenuSlotSummary::count)
                .sum();
    }

    private static boolean isEmpty(
            final MenuSlotSummary slot
    ) {
        return slot.count() == 0
                && slot.itemId().equals("minecraft:air");
    }

    private static MenuBinding binding(
            final MenuSkillFrame frame
    ) {
        return new MenuBinding(
                frame.sampleSequence(),
                frame.menu().containerId(),
                frame.menu().stateId()
        );
    }

    private static long timeoutTicks(final int count) {
        return Math.addExact(
                BASE_TIMEOUT_TICKS,
                Math.multiplyExact(
                        TIMEOUT_TICKS_PER_ITEM,
                        count
                )
        );
    }

    private SkillTickResult fail(final String suffix) {
        return fail(failure(suffix));
    }

    private SkillTickResult fail(
            final SkillFailure cause
    ) {
        failure = cause;
        phase = Phase.FAILED;
        return SkillTickResult.failed(cause);
    }

    private static SkillFailure failure(final String suffix) {
        return SkillFailure.of(NAME + "." + suffix);
    }

    private enum Phase {
        IDLE,
        LOAD_INPUT,
        LOAD_FUEL,
        WAIT_OUTPUT,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return this == LOAD_INPUT
                    || this == LOAD_FUEL
                    || this == WAIT_OUTPUT;
        }
    }
}
