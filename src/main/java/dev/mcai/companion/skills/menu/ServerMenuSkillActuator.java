package dev.mcai.companion.skills.menu;

import dev.mcai.companion.action.AcceptedLowLevelAction;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.perception.MenuOptionSummary;
import dev.mcai.companion.perception.MenuSlotSummary;
import dev.mcai.companion.progression.FoundationActionAudit;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Executes observed menu actions through ordinary vanilla click
 * transactions.
 *
 * <p>This class never calls {@code Container#setItem}, changes an item count,
 * or manufactures an output. Exact transfers are expressed as primary and
 * secondary {@link AbstractContainerMenu#clicked} operations. Output
 * consumption uses vanilla quick-move, preserving {@code ResultSlot} and
 * furnace-result callbacks.</p>
 */
public final class ServerMenuSkillActuator
        implements MenuSkillActuator {
    private final MinecraftServer server;
    private final UUID expectedPlayerId;
    private final MenuSkillFrameSource frames;
    private final Supplier<Optional<ServerPlayer>> playerLookup;
    private final LongSupplier sessionGeneration;
    private final Optional<FoundationActionAudit> foundationAudit;
    private final Consumer<AcceptedLowLevelAction> actionAudit;

    public ServerMenuSkillActuator(
            final MinecraftServer server,
            final UUID expectedPlayerId,
            final MenuSkillFrameSource frames
    ) {
        this(server, expectedPlayerId, frames, null, ignored -> {
        });
    }

    public ServerMenuSkillActuator(
            final MinecraftServer server,
            final UUID expectedPlayerId,
            final MenuSkillFrameSource frames,
            final FoundationActionAudit foundationAudit
    ) {
        this(
                server,
                expectedPlayerId,
                frames,
                foundationAudit,
                ignored -> {
                }
        );
    }

    public ServerMenuSkillActuator(
            final MinecraftServer server,
            final UUID expectedPlayerId,
            final MenuSkillFrameSource frames,
            final FoundationActionAudit foundationAudit,
            final Consumer<AcceptedLowLevelAction> actionAudit
    ) {
        this(
                server,
                expectedPlayerId,
                frames,
                () -> AiPlayerManager.onlinePlayer(server),
                () -> {
                    final AiPlayerManager.Status status =
                            AiPlayerManager.status(server);
                    return status.state() == SessionState.ACTIVE
                            && status.online()
                            ? status.sessionGeneration()
                            : -1;
                },
                foundationAudit,
                actionAudit
        );
    }

    ServerMenuSkillActuator(
            final MinecraftServer server,
            final UUID expectedPlayerId,
            final MenuSkillFrameSource frames,
            final Supplier<Optional<ServerPlayer>> playerLookup,
            final LongSupplier sessionGeneration
    ) {
        this(
                server,
                expectedPlayerId,
                frames,
                playerLookup,
                sessionGeneration,
                null,
                ignored -> {
                }
        );
    }

    ServerMenuSkillActuator(
            final MinecraftServer server,
            final UUID expectedPlayerId,
            final MenuSkillFrameSource frames,
            final Supplier<Optional<ServerPlayer>> playerLookup,
            final LongSupplier sessionGeneration,
            final FoundationActionAudit foundationAudit
    ) {
        this(
                server,
                expectedPlayerId,
                frames,
                playerLookup,
                sessionGeneration,
                foundationAudit,
                ignored -> {
                }
        );
    }

    ServerMenuSkillActuator(
            final MinecraftServer server,
            final UUID expectedPlayerId,
            final MenuSkillFrameSource frames,
            final Supplier<Optional<ServerPlayer>> playerLookup,
            final LongSupplier sessionGeneration,
            final FoundationActionAudit foundationAudit,
            final Consumer<AcceptedLowLevelAction> actionAudit
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.frames = Objects.requireNonNull(frames, "frames");
        this.playerLookup = Objects.requireNonNull(
                playerLookup,
                "playerLookup"
        );
        this.sessionGeneration = Objects.requireNonNull(
                sessionGeneration,
                "sessionGeneration"
        );
        this.foundationAudit = Optional.ofNullable(foundationAudit);
        this.actionAudit = Objects.requireNonNull(
                actionAudit,
                "actionAudit"
        );
    }

    @Override
    public OptionalLong sessionGeneration() {
        final PlayerCheck player = checkPlayer();
        if (player.failure().isPresent()) {
            return OptionalLong.empty();
        }
        final long generation = sessionGeneration.getAsLong();
        return generation < 0
                ? OptionalLong.empty()
                : OptionalLong.of(generation);
    }

    @Override
    public MenuOperationResult checkTransfer(
            final TransferMenuItemParameters parameters
    ) {
        Objects.requireNonNull(parameters, "parameters");
        final BindingCheck binding = checkedBinding(parameters.binding());
        if (binding.failure().isPresent()) {
            return binding.failure().orElseThrow();
        }
        final CheckedMenu checked = binding.checked().orElseThrow();
        final SlotResolution source = resolveObservedSlot(
                checked,
                parameters.sourceSlot()
        );
        if (source.failure().isPresent()) {
            return source.failure().orElseThrow();
        }
        final SlotResolution destination = resolveObservedSlot(
                checked,
                parameters.destinationSlot()
        );
        if (destination.failure().isPresent()) {
            return destination.failure().orElseThrow();
        }
        final Slot sourceSlot = source.slot().orElseThrow();
        final Slot destinationSlot = destination.slot().orElseThrow();
        final MenuSlotSummary sourceSummary =
                source.summary().orElseThrow();
        final MenuSlotSummary destinationSummary =
                destination.summary().orElseThrow();
        final ServerPlayer player = checked.player();
        final ItemStack sourceStack = sourceSlot.getItem();
        final ItemStack destinationStack = destinationSlot.getItem();

        if (sourceSummary.playerInventory()
                == destinationSummary.playerInventory()) {
            return MenuOperationResult.rejected(
                    "transfer_menu_item.same_partition"
            );
        }
        if (!sourceSlot.isActive()
                || !destinationSlot.isActive()
                || !sourceSlot.mayPickup(player)
                || !destinationSlot.mayPickup(player)) {
            return MenuOperationResult.rejected(
                    "transfer_menu_item.slot_locked"
            );
        }
        if (!sourceSlot.mayPlace(sourceStack)) {
            return MenuOperationResult.rejected(
                    "transfer_menu_item.output_requires_quick_move"
            );
        }
        if (sourceStack.getCount() < parameters.count()) {
            return MenuOperationResult.rejected(
                    "transfer_menu_item.insufficient_items"
            );
        }
        if (!destinationSlot.mayPlace(sourceStack)) {
            return MenuOperationResult.rejected(
                    "transfer_menu_item.destination_rejects_item"
            );
        }
        if (!destinationStack.isEmpty()
                && !ItemStack.isSameItemSameComponents(
                        sourceStack,
                        destinationStack
                )) {
            return MenuOperationResult.rejected(
                    "transfer_menu_item.destination_mismatch"
            );
        }
        final int capacity = destinationSlot.getMaxStackSize(sourceStack)
                - destinationStack.getCount();
        return capacity >= parameters.count()
                ? MenuOperationResult.success()
                : MenuOperationResult.rejected(
                        "transfer_menu_item.destination_full"
                );
    }

    @Override
    public MenuOperationResult transfer(
            final TransferMenuItemParameters parameters
    ) {
        final MenuOperationResult preflight = checkTransfer(parameters);
        if (!preflight.succeeded()) {
            return preflight;
        }
        final CheckedMenu checked = checkedBinding(parameters.binding())
                .checked()
                .orElseThrow();
        final AbstractContainerMenu menu = checked.menu();
        final ServerPlayer player = checked.player();
        final Slot source = menu.getSlot(parameters.sourceSlot());
        final Slot destination = menu.getSlot(
                parameters.destinationSlot()
        );
        final ItemStack sourceBefore = source.getItem().copy();
        final ItemStack destinationBefore = destination.getItem().copy();
        int deposited = 0;

        player.resetLastActionTime();
        menu.clicked(
                parameters.sourceSlot(),
                0,
                ContainerInput.PICKUP,
                player
        );
        if (!stackWithCount(
                menu.getCarried(),
                sourceBefore,
                sourceBefore.getCount()
        ) || !source.getItem().isEmpty()) {
            restoreInitialPickup(
                    menu,
                    player,
                    parameters.sourceSlot(),
                    sourceBefore
            );
            menu.broadcastChanges();
            return MenuOperationResult.rejected(
                    "transfer_menu_item.pickup_rejected"
            );
        }

        for (int index = 0; index < parameters.count(); index++) {
            final int cursorBefore = menu.getCarried().getCount();
            final int destinationCount =
                    destination.getItem().getCount();
            menu.clicked(
                    parameters.destinationSlot(),
                    1,
                    ContainerInput.PICKUP,
                    player
            );
            if (menu.getCarried().getCount() != cursorBefore - 1
                    || destination.getItem().getCount()
                            != destinationCount + 1) {
                final boolean restored = rollbackTransfer(
                        menu,
                        player,
                        parameters.sourceSlot(),
                        parameters.destinationSlot(),
                        deposited,
                        sourceBefore,
                        destinationBefore
                );
                menu.broadcastChanges();
                return MenuOperationResult.rejected(
                        restored
                                ? "transfer_menu_item.transaction_rejected"
                                : "transfer_menu_item.rollback_failed"
                );
            }
            deposited++;
        }

        if (!menu.getCarried().isEmpty()) {
            menu.clicked(
                    parameters.sourceSlot(),
                    0,
                    ContainerInput.PICKUP,
                    player
            );
        }
        final boolean exact = menu.getCarried().isEmpty()
                && stackWithCount(
                        source.getItem(),
                        sourceBefore,
                        sourceBefore.getCount() - parameters.count()
                )
                && stackWithCount(
                        destination.getItem(),
                        sourceBefore,
                        destinationBefore.getCount()
                                + parameters.count()
                );
        if (!exact) {
            final boolean restored = rollbackTransfer(
                    menu,
                    player,
                    parameters.sourceSlot(),
                    parameters.destinationSlot(),
                    deposited,
                    sourceBefore,
                    destinationBefore
            );
            menu.broadcastChanges();
            return MenuOperationResult.rejected(
                    restored
                            ? "transfer_menu_item.transaction_rejected"
                            : "transfer_menu_item.rollback_failed"
            );
        }
        menu.broadcastChanges();
        if (source.container == player.getInventory()) {
            foundationAudit.ifPresent(audit ->
                    audit.observeMenuDeposit(
                            player,
                            menu,
                            BuiltInRegistries.ITEM
                                    .getKey(sourceBefore.getItem())
                                    .toString(),
                            parameters.count()
                    )
            );
        }
        audit("transfer_menu_item");
        return MenuOperationResult.success(parameters.count());
    }

    @Override
    public MenuOperationResult checkQuickMove(
            final ObservedMenuSlotParameters parameters,
            final boolean outputOnly
    ) {
        Objects.requireNonNull(parameters, "parameters");
        final BindingCheck binding = checkedBinding(parameters.binding());
        if (binding.failure().isPresent()) {
            return binding.failure().orElseThrow();
        }
        final CheckedMenu checked = binding.checked().orElseThrow();
        final SlotResolution resolved = resolveObservedSlot(
                checked,
                parameters.slot()
        );
        if (resolved.failure().isPresent()) {
            return resolved.failure().orElseThrow();
        }
        final Slot slot = resolved.slot().orElseThrow();
        final MenuSlotSummary summary = resolved.summary().orElseThrow();
        final ItemStack stack = slot.getItem();
        if (!slot.isActive()
                || stack.isEmpty()
                || !slot.mayPickup(checked.player())) {
            return MenuOperationResult.rejected(
                    outputOnly
                            ? "take_menu_output.output_unavailable"
                            : "quick_move_observed_slot.slot_unavailable"
            );
        }
        if (outputOnly
                && (summary.playerInventory()
                || slot.mayPlace(stack))) {
            return MenuOperationResult.rejected(
                    "take_menu_output.not_output_slot"
            );
        }
        return MenuOperationResult.success();
    }

    @Override
    public MenuOperationResult quickMove(
            final ObservedMenuSlotParameters parameters,
            final boolean outputOnly
    ) {
        final MenuOperationResult preflight = checkQuickMove(
                parameters,
                outputOnly
        );
        if (!preflight.succeeded()) {
            return preflight;
        }
        final CheckedMenu checked = checkedBinding(parameters.binding())
                .checked()
                .orElseThrow();
        final AbstractContainerMenu menu = checked.menu();
        final ServerPlayer player = checked.player();
        final Slot source = menu.getSlot(parameters.slot());
        final ItemStack expected = source.getItem().copy();
        final boolean fromPlayer =
                source.container == player.getInventory();
        final int playerBefore = countPlayerMenuItem(
                menu,
                player,
                expected
        );
        final ItemStack sourceBefore = source.getItem().copy();

        player.resetLastActionTime();
        menu.clicked(
                parameters.slot(),
                0,
                ContainerInput.QUICK_MOVE,
                player
        );
        menu.broadcastChanges();
        if (!menu.getCarried().isEmpty()) {
            return MenuOperationResult.rejected(
                    outputOnly
                            ? "take_menu_output.cursor_not_empty"
                            : "quick_move_observed_slot.cursor_not_empty"
            );
        }
        final int playerAfter = countPlayerMenuItem(
                menu,
                player,
                expected
        );
        final int affected = fromPlayer
                ? playerBefore - playerAfter
                : playerAfter - playerBefore;
        if (affected <= 0
                && ItemStack.matches(sourceBefore, source.getItem())) {
            return MenuOperationResult.rejected(
                    outputOnly
                            ? "take_menu_output.transaction_rejected"
                            : "quick_move_observed_slot.transaction_rejected"
            );
        }
        if (fromPlayer && affected > 0) {
            foundationAudit.ifPresent(audit ->
                    audit.observeMenuDeposit(
                            player,
                            menu,
                            BuiltInRegistries.ITEM
                                    .getKey(expected.getItem())
                                    .toString(),
                            affected
                    )
            );
        }
        audit(outputOnly
                ? "take_menu_output"
                : "quick_move_observed_slot");
        return MenuOperationResult.success(Math.max(affected, 1));
    }

    @Override
    public MenuOperationResult checkSelectOption(
            final SelectMenuOptionParameters parameters
    ) {
        Objects.requireNonNull(parameters, "parameters");
        final BindingCheck binding = checkedBinding(parameters.binding());
        if (binding.failure().isPresent()) {
            return binding.failure().orElseThrow();
        }
        final CheckedMenu checked = binding.checked().orElseThrow();
        final Optional<MenuOptionSummary> observed =
                checked.frame().menu().options().stream()
                        .filter(option ->
                                option.optionId() == parameters.optionId()
                        )
                        .findFirst();
        if (observed.isEmpty()) {
            return MenuOperationResult.rejected(
                    "select_menu_option.option_not_observed"
            );
        }
        if (!observed.orElseThrow().available()) {
            return MenuOperationResult.rejected(
                    "select_menu_option.option_unavailable"
            );
        }
        final AbstractContainerMenu menu = checked.menu();
        if (!(menu instanceof EnchantmentMenu)
                && !(menu instanceof StonecutterMenu)
                && !(menu instanceof LoomMenu)
                && !(menu instanceof MerchantMenu)) {
            return MenuOperationResult.rejected(
                    "select_menu_option.unsupported_menu"
            );
        }
        if (menu instanceof MerchantMenu merchant
                && (parameters.optionId() >= merchant.getOffers().size()
                || merchant.getOffers()
                        .get(parameters.optionId())
                        .isOutOfStock())) {
            return MenuOperationResult.rejected(
                    "select_menu_option.option_changed"
            );
        }
        return MenuOperationResult.success();
    }

    @Override
    public MenuOperationResult selectOption(
            final SelectMenuOptionParameters parameters
    ) {
        final MenuOperationResult preflight =
                checkSelectOption(parameters);
        if (!preflight.succeeded()) {
            return preflight;
        }
        final CheckedMenu checked =
                checkedBinding(parameters.binding())
                        .checked()
                        .orElseThrow();
        final AbstractContainerMenu menu = checked.menu();
        final ServerPlayer player = checked.player();
        player.resetLastActionTime();
        final boolean accepted;
        if (menu instanceof MerchantMenu merchant) {
            merchant.setSelectionHint(parameters.optionId());
            merchant.tryMoveItems(parameters.optionId());
            accepted = true;
        } else {
            accepted = menu.clickMenuButton(
                    player,
                    parameters.optionId()
            );
        }
        menu.broadcastChanges();
        if (!accepted) {
            return MenuOperationResult.rejected(
                    "select_menu_option.transaction_rejected"
            );
        }
        audit("select_menu_option");
        return MenuOperationResult.success(1);
    }

    @Override
    public MenuOperationResult checkClose(
            final CloseMenuParameters parameters
    ) {
        Objects.requireNonNull(parameters, "parameters");
        final BindingCheck checked = checkedBinding(parameters.binding());
        return checked.failure().orElseGet(
                MenuOperationResult::success
        );
    }

    @Override
    public MenuOperationResult close(
            final CloseMenuParameters parameters
    ) {
        final MenuOperationResult preflight = checkClose(parameters);
        if (!preflight.succeeded()) {
            return preflight;
        }
        final ServerPlayer player = checkedBinding(parameters.binding())
                .checked()
                .orElseThrow()
                .player();
        player.resetLastActionTime();
        player.closeContainer();
        if (player.containerMenu != player.inventoryMenu
                || !player.containerMenu.getCarried().isEmpty()) {
            return MenuOperationResult.rejected(
                    "close_menu.transaction_rejected"
            );
        }
        audit("close_menu");
        return MenuOperationResult.success();
    }

    @Override
    public MenuOperationResult checkBinding(final MenuBinding binding) {
        Objects.requireNonNull(binding, "binding");
        final BindingCheck checked = checkedBinding(binding);
        return checked.failure().orElseGet(
                MenuOperationResult::success
        );
    }

    @Override
    public MenuChangeState observeChange(
            final MenuBinding binding,
            final long expectedSessionGeneration
    ) {
        Objects.requireNonNull(binding, "binding");
        final PlayerCheck checked = checkPlayer();
        if (checked.failure().isPresent()) {
            return MenuChangeState.PLAYER_UNAVAILABLE;
        }
        if (sessionGeneration.getAsLong()
                != expectedSessionGeneration) {
            return MenuChangeState.SESSION_MISMATCH;
        }
        final ServerPlayer player = checked.player().orElseThrow();
        final AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu) {
            return MenuChangeState.CLOSED;
        }
        if (!menu.stillValid(player)
                || menu.containerId != binding.containerId()) {
            return MenuChangeState.MENU_REPLACED;
        }
        return menu.getStateId() == binding.stateId()
                ? MenuChangeState.UNCHANGED
                : MenuChangeState.CHANGED;
    }

    private BindingCheck checkedBinding(final MenuBinding binding) {
        if (!server.isSameThread()) {
            return BindingCheck.failed("menu.wrong_thread");
        }
        final PlayerCheck playerCheck = checkPlayer();
        if (playerCheck.failure().isPresent()) {
            return BindingCheck.failed(
                    playerCheck.failure()
                            .orElseThrow()
                            .failure()
                            .orElseThrow()
                            .code()
            );
        }
        final Optional<MenuSkillFrame> maybeFrame =
                frames.retained(binding.sampleSequence());
        if (maybeFrame.isEmpty()) {
            return BindingCheck.failed("menu.observation_unavailable");
        }
        final MenuSkillFrame frame = maybeFrame.orElseThrow();
        final ServerPlayer player = playerCheck.player().orElseThrow();
        final long currentGeneration = sessionGeneration.getAsLong();
        if (currentGeneration < 0
                || frame.sessionGeneration() != currentGeneration) {
            return BindingCheck.failed("menu.session_mismatch");
        }
        if (!frame.playerId().equals(expectedPlayerId)
                || !frame.dimensionId().equals(
                        player.level()
                                .dimension()
                                .identifier()
                                .toString()
                )) {
            return BindingCheck.failed("menu.player_mismatch");
        }
        if (frame.sampleSequence() != binding.sampleSequence()) {
            return BindingCheck.failed("menu.observation_expired");
        }
        if (frame.menu().containerId() != binding.containerId()
                || frame.menu().stateId() != binding.stateId()) {
            return BindingCheck.failed("menu.forged_binding");
        }
        final AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu
                || !menu.stillValid(player)
                || menu.containerId != binding.containerId()) {
            return BindingCheck.failed("menu.menu_replaced");
        }
        if (menu.getStateId() != binding.stateId()) {
            return BindingCheck.failed("menu.state_changed");
        }
        if (!frame.menu().carried().emptyHand()
                || !menu.getCarried().isEmpty()) {
            return BindingCheck.failed("menu.cursor_not_empty");
        }
        if (menu.slots.size() != frame.menu().slots().size()) {
            return BindingCheck.failed("menu.slot_layout_changed");
        }
        return BindingCheck.success(
                new CheckedMenu(player, menu, frame)
        );
    }

    private void audit(final String action) {
        try {
            actionAudit.accept(new AcceptedLowLevelAction(
                    action,
                    Integer.toUnsignedLong(server.getTickCount()),
                    "COMPLETED"
            ));
        } catch (RuntimeException ignored) {
            // Evidence output must never gain authority over a legal action.
        }
    }

    private PlayerCheck checkPlayer() {
        if (!server.isSameThread()) {
            return PlayerCheck.failed("menu.wrong_thread");
        }
        final Optional<ServerPlayer> maybePlayer = playerLookup.get();
        if (maybePlayer.isEmpty()
                || !expectedPlayerId.equals(
                        maybePlayer.orElseThrow().getUUID()
                )) {
            return PlayerCheck.failed("menu.player_offline");
        }
        final ServerPlayer player = maybePlayer.orElseThrow();
        if (!player.isAlive()) {
            return PlayerCheck.failed("menu.player_dead");
        }
        if (player.isSpectator()) {
            return PlayerCheck.failed("menu.player_spectator");
        }
        return PlayerCheck.success(player);
    }

    private static SlotResolution resolveObservedSlot(
            final CheckedMenu checked,
            final int slotNumber
    ) {
        if (slotNumber < 0
                || slotNumber >= checked.menu().slots.size()) {
            return SlotResolution.failed("menu.slot_not_observed");
        }
        final Optional<MenuSlotSummary> maybeSummary =
                checked.frame().menu().slots().stream()
                        .filter(slot -> slot.slot() == slotNumber)
                        .findFirst();
        if (maybeSummary.isEmpty()) {
            return SlotResolution.failed("menu.slot_not_observed");
        }
        final MenuSlotSummary summary = maybeSummary.orElseThrow();
        final Slot slot = checked.menu().getSlot(slotNumber);
        if (summary.playerInventory()
                != (slot.container == checked.player().getInventory())
                || !matchesSummary(slot.getItem(), summary)
                || summary.mayPickup()
                != slot.mayPickup(checked.player())) {
            return SlotResolution.failed("menu.observed_slot_changed");
        }
        return SlotResolution.success(slot, summary);
    }

    private static boolean matchesSummary(
            final ItemStack stack,
            final MenuSlotSummary summary
    ) {
        if (stack.isEmpty()) {
            return summary.count() == 0
                    && summary.itemId().equals("minecraft:air");
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString()
                .equals(summary.itemId())
                && stack.getCount() == summary.count()
                && stack.getDamageValue() == summary.damage()
                && stack.getMaxDamage() == summary.maxDamage();
    }

    private static void restoreInitialPickup(
            final AbstractContainerMenu menu,
            final ServerPlayer player,
            final int sourceSlot,
            final ItemStack sourceBefore
    ) {
        if (stackWithCount(
                menu.getCarried(),
                sourceBefore,
                sourceBefore.getCount()
        ) && menu.getSlot(sourceSlot).getItem().isEmpty()) {
            menu.clicked(
                    sourceSlot,
                    0,
                    ContainerInput.PICKUP,
                    player
            );
        }
    }

    private static boolean rollbackTransfer(
            final AbstractContainerMenu menu,
            final ServerPlayer player,
            final int sourceSlotNumber,
            final int destinationSlotNumber,
            final int deposited,
            final ItemStack sourceBefore,
            final ItemStack destinationBefore
    ) {
        final Slot source = menu.getSlot(sourceSlotNumber);
        final Slot destination = menu.getSlot(destinationSlotNumber);
        if (!menu.getCarried().isEmpty()) {
            menu.clicked(
                    sourceSlotNumber,
                    0,
                    ContainerInput.PICKUP,
                    player
            );
        }
        if (deposited > 0) {
            menu.clicked(
                    destinationSlotNumber,
                    0,
                    ContainerInput.PICKUP,
                    player
            );
            for (int index = 0; index < deposited; index++) {
                menu.clicked(
                        sourceSlotNumber,
                        1,
                        ContainerInput.PICKUP,
                        player
                );
            }
            if (!menu.getCarried().isEmpty()) {
                menu.clicked(
                        destinationSlotNumber,
                        0,
                        ContainerInput.PICKUP,
                        player
                );
            }
        }
        return menu.getCarried().isEmpty()
                && ItemStack.matches(sourceBefore, source.getItem())
                && ItemStack.matches(
                        destinationBefore,
                        destination.getItem()
                );
    }

    private static boolean stackWithCount(
            final ItemStack actual,
            final ItemStack expectedItem,
            final int expectedCount
    ) {
        if (expectedCount == 0) {
            return actual.isEmpty();
        }
        return actual.getCount() == expectedCount
                && ItemStack.isSameItemSameComponents(
                        actual,
                        expectedItem
                );
    }

    private static int countPlayerMenuItem(
            final AbstractContainerMenu menu,
            final ServerPlayer player,
            final ItemStack expected
    ) {
        int count = 0;
        final Set<Integer> visited = new HashSet<>();
        for (Slot slot : menu.slots) {
            if (slot.container == player.getInventory()
                    && visited.add(slot.getContainerSlot())
                    && ItemStack.isSameItemSameComponents(
                            slot.getItem(),
                            expected
                    )) {
                count += slot.getItem().getCount();
            }
        }
        return count;
    }

    private record CheckedMenu(
            ServerPlayer player,
            AbstractContainerMenu menu,
            MenuSkillFrame frame
    ) {
        private CheckedMenu {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(menu, "menu");
            Objects.requireNonNull(frame, "frame");
        }
    }

    private record BindingCheck(
            Optional<CheckedMenu> checked,
            Optional<MenuOperationResult> failure
    ) {
        private BindingCheck {
            Objects.requireNonNull(checked, "checked");
            Objects.requireNonNull(failure, "failure");
            if (checked.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "Binding check requires exactly one outcome"
                );
            }
        }

        static BindingCheck success(final CheckedMenu checked) {
            return new BindingCheck(
                    Optional.of(checked),
                    Optional.empty()
            );
        }

        static BindingCheck failed(final String code) {
            return new BindingCheck(
                    Optional.empty(),
                    Optional.of(MenuOperationResult.rejected(code))
            );
        }
    }

    private record PlayerCheck(
            Optional<ServerPlayer> player,
            Optional<MenuOperationResult> failure
    ) {
        private PlayerCheck {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(failure, "failure");
            if (player.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "Player check requires exactly one outcome"
                );
            }
        }

        static PlayerCheck success(final ServerPlayer player) {
            return new PlayerCheck(
                    Optional.of(player),
                    Optional.empty()
            );
        }

        static PlayerCheck failed(final String code) {
            return new PlayerCheck(
                    Optional.empty(),
                    Optional.of(MenuOperationResult.rejected(code))
            );
        }
    }

    private record SlotResolution(
            Optional<Slot> slot,
            Optional<MenuSlotSummary> summary,
            Optional<MenuOperationResult> failure
    ) {
        private SlotResolution {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(failure, "failure");
            if (failure.isEmpty()
                    && (slot.isEmpty() || summary.isEmpty())) {
                throw new IllegalArgumentException(
                        "Successful slot resolution requires values"
                );
            }
        }

        static SlotResolution success(
                final Slot slot,
                final MenuSlotSummary summary
        ) {
            return new SlotResolution(
                    Optional.of(slot),
                    Optional.of(summary),
                    Optional.empty()
            );
        }

        static SlotResolution failed(final String code) {
            return new SlotResolution(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(MenuOperationResult.rejected(code))
            );
        }
    }
}
