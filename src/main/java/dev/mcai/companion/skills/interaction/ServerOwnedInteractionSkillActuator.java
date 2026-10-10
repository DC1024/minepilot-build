package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.AcceptedLowLevelAction;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.FairPlayerActuator;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.progression.FoundationActionAudit;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;

/**
 * Server-thread production bridge from interaction skills to the fair player
 * action layer.
 *
 * <p>The binding is resolved for every call and is invalidated when either
 * the lifecycle generation, {@link ServerPlayer}, or connection object
 * changes. This prevents a skill created for a dead or disconnected body from
 * dispatching into its replacement.</p>
 */
public final class ServerOwnedInteractionSkillActuator
        implements InteractionSkillActuator {
    private static final int MAIN_INVENTORY_SLOT_COUNT = 36;

    private final MinecraftServer server;
    private final UUID expectedPlayerId;
    private final Optional<FoundationActionAudit> foundationAudit;
    private final Consumer<AcceptedLowLevelAction> actionAudit;
    private final BlockBreakProtection blockBreakProtection;

    private ServerPlayer boundPlayer;
    private ServerGamePacketListenerImpl boundConnection;
    private FairPlayerActuator boundActuator;
    private long boundSessionGeneration = -1;

    public ServerOwnedInteractionSkillActuator(
            MinecraftServer server,
            UUID expectedPlayerId
    ) {
        this(
                server,
                expectedPlayerId,
                null,
                ignored -> {
                },
                BlockBreakProtection.none()
        );
    }

    public ServerOwnedInteractionSkillActuator(
            final MinecraftServer server,
            final UUID expectedPlayerId,
            final FoundationActionAudit foundationAudit
    ) {
        this(
                server,
                expectedPlayerId,
                foundationAudit,
                ignored -> {
                },
                BlockBreakProtection.none()
        );
    }

    public ServerOwnedInteractionSkillActuator(
            final MinecraftServer server,
            final UUID expectedPlayerId,
            final FoundationActionAudit foundationAudit,
            final Consumer<AcceptedLowLevelAction> actionAudit
    ) {
        this(
                server,
                expectedPlayerId,
                foundationAudit,
                actionAudit,
                BlockBreakProtection.none()
        );
    }

    public ServerOwnedInteractionSkillActuator(
            final MinecraftServer server,
            final UUID expectedPlayerId,
            final FoundationActionAudit foundationAudit,
            final Consumer<AcceptedLowLevelAction> actionAudit,
            final BlockBreakProtection blockBreakProtection
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.foundationAudit = Optional.ofNullable(foundationAudit);
        this.actionAudit = Objects.requireNonNull(
                actionAudit,
                "actionAudit"
        );
        this.blockBreakProtection = Objects.requireNonNull(
                blockBreakProtection,
                "blockBreakProtection"
        );
    }

    @Override
    public OptionalLong sessionGeneration() {
        if (!server.isSameThread()) {
            return OptionalLong.empty();
        }
        Optional<Binding> binding = resolve();
        return binding.isEmpty()
                ? OptionalLong.empty()
                : OptionalLong.of(
                        binding.orElseThrow().sessionGeneration()
                );
    }

    @Override
    public ActionOutcome beginMining(BlockInteractionTarget target) {
        Objects.requireNonNull(target, "target");
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        final Optional<Binding> binding = resolve();
        if (binding.isEmpty()) {
            return ActionOutcome.PLAYER_UNAVAILABLE;
        }
        final Binding active = binding.orElseThrow();
        final DimensionRef dimension = DimensionRef.parse(
                active.player()
                        .level()
                        .dimension()
                        .identifier()
                        .toString()
        );
        final GridPos position =
                new GridPos(target.x(), target.y(), target.z());
        if (PlayerSupportBlockGuard.protects(
                active.player(),
                position
        ) || blockBreakProtection.protects(
                dimension,
                position
        )) {
            return ActionOutcome.WORLD_DENIED;
        }
        final ActionOutcome outcome = safeAction(() ->
                active.actuator().beginMining(target)
        );
        audit("begin_mining", outcome);
        return outcome;
    }

    @Override
    public ActionOutcome continueMining() {
        return dispatch(
                "continue_mining",
                FairPlayerActuator::continueMining
        );
    }

    @Override
    public ActionOutcome abortMining() {
        return dispatch(
                "abort_mining",
                FairPlayerActuator::abortMining
        );
    }

    @Override
    public ActionOutcome useOnBlock(
            ActionHand hand,
            BlockInteractionTarget target
    ) {
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(target, "target");
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        final Optional<Binding> binding = resolve();
        if (binding.isEmpty()) {
            return ActionOutcome.PLAYER_UNAVAILABLE;
        }
        final Binding active = binding.orElseThrow();
        final ActionOutcome outcome = safeAction(() ->
                active.actuator().useOnBlock(hand, target)
        );
        foundationAudit.ifPresent(audit ->
                audit.observeBlockUse(
                        active.player(),
                        target,
                        outcome
                )
        );
        audit("use_on_block", outcome);
        return outcome;
    }

    @Override
    public ActionOutcome attack(UUID entityId) {
        Objects.requireNonNull(entityId, "entityId");
        return dispatch(
                "attack_entity",
                actuator -> actuator.attack(entityId)
        );
    }

    @Override
    public ActionOutcome interactEntity(
            final UUID entityId,
            final ActionHand hand
    ) {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(hand, "hand");
        return dispatch(
                "interact_entity",
                actuator -> actuator.interactEntity(entityId, hand)
        );
    }

    @Override
    public OptionalDouble attackStrengthScale() {
        if (!server.isSameThread()) {
            return OptionalDouble.empty();
        }
        Optional<Binding> binding = resolve();
        if (binding.isEmpty()) {
            return OptionalDouble.empty();
        }
        float scale = binding.orElseThrow()
                .player()
                .getAttackStrengthScale(0.5F);
        return Float.isFinite(scale)
                ? OptionalDouble.of(Math.max(0.0F, Math.min(1.0F, scale)))
                : OptionalDouble.empty();
    }

    @Override
    public ActionOutcome useItem(ActionHand hand) {
        Objects.requireNonNull(hand, "hand");
        return dispatch(
                "use_item",
                actuator -> actuator.useItem(hand)
        );
    }

    @Override
    public ActionOutcome continueUsing(ActionHand hand) {
        Objects.requireNonNull(hand, "hand");
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        Optional<Binding> binding = resolve();
        if (binding.isEmpty()) {
            return ActionOutcome.PLAYER_UNAVAILABLE;
        }
        ServerPlayer player = binding.orElseThrow().player();
        if (!player.isUsingItem()) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
        InteractionHand expected = hand == ActionHand.MAIN_HAND
                ? InteractionHand.MAIN_HAND
                : InteractionHand.OFF_HAND;
        final ActionOutcome outcome = player.getUsedItemHand() == expected
                ? ActionOutcome.IN_PROGRESS
                : ActionOutcome.INVALID_PLAYER_STATE;
        audit("continue_using", outcome);
        return outcome;
    }

    @Override
    public ActionOutcome releaseUse() {
        return dispatch(
                "release_use",
                FairPlayerActuator::releaseUse
        );
    }

    @Override
    public ActionOutcome equipMainHand(String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        Optional<Binding> binding = resolve();
        if (binding.isEmpty()) {
            return ActionOutcome.PLAYER_UNAVAILABLE;
        }
        Identifier identifier = Identifier.tryParse(itemId);
        if (identifier == null
                || !BuiltInRegistries.ITEM.containsKey(identifier)) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }
        Optional<Item> resolved = BuiltInRegistries.ITEM.getOptional(
                identifier
        );
        if (resolved.isEmpty()) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }
        ServerPlayer player = binding.orElseThrow().player();
        if (!player.isAlive() || player.isSpectator()) {
            return ActionOutcome.PLAYER_INCAPACITATED;
        }
        Item item = resolved.orElseThrow();
        if (player.getMainHandItem().is(item)) {
            return ActionOutcome.COMPLETED;
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        int source = findInventoryItem(player, item);
        if (source < 0) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }
        var sourceMenuSlot = player.inventoryMenu.findSlot(
                player.getInventory(),
                source
        );
        if (sourceMenuSlot.isEmpty()) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }
        player.resetLastActionTime();
        player.inventoryMenu.clicked(
                sourceMenuSlot.getAsInt(),
                player.getInventory().getSelectedSlot(),
                ContainerInput.SWAP,
                player
        );
        player.inventoryMenu.broadcastChanges();
        final ActionOutcome outcome = player.getMainHandItem().is(item)
                && player.inventoryMenu.getCarried().isEmpty()
                ? ActionOutcome.COMPLETED
                : ActionOutcome.INVALID_PLAYER_STATE;
        audit("equip_main_hand", outcome);
        return outcome;
    }

    /**
     * Best-effort release for body/session/runtime terminal transitions.
     */
    public QuiesceReport quiesceNow() {
        if (!server.isSameThread()) {
            return new QuiesceReport(
                    false,
                    ActionOutcome.WRONG_THREAD,
                    ActionOutcome.WRONG_THREAD
            );
        }
        Optional<Binding> current = resolve();
        if (current.isEmpty()) {
            return QuiesceReport.unavailable();
        }
        FairPlayerActuator actuator = current.orElseThrow().actuator();
        return quiesce(actuator);
    }

    private ActionOutcome dispatch(
            final String action,
            Function<FairPlayerActuator, ActionOutcome> operation
    ) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(operation, "operation");
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        Optional<Binding> binding = resolve();
        if (binding.isEmpty()) {
            return ActionOutcome.PLAYER_UNAVAILABLE;
        }
        final ActionOutcome outcome = safeAction(
                () -> operation.apply(binding.orElseThrow().actuator())
        );
        audit(action, outcome);
        return outcome;
    }

    private void audit(
            final String action,
            final ActionOutcome outcome
    ) {
        if (!outcome.accepted()) {
            return;
        }
        try {
            actionAudit.accept(AcceptedLowLevelAction.from(
                    action,
                    Integer.toUnsignedLong(server.getTickCount()),
                    outcome
            ));
        } catch (RuntimeException ignored) {
            // Evidence output must never gain authority over a legal action.
        }
    }

    private Optional<Binding> resolve() {
        AiPlayerManager.Status status = AiPlayerManager.status(server);
        ServerPlayer player = server.getPlayerList().getPlayer(
                expectedPlayerId
        );
        if (status.state() != SessionState.ACTIVE
                || !status.online()
                || player == null
                || player.connection == null
                || player.isRemoved()
                || !expectedPlayerId.equals(player.getUUID())) {
            releaseOldBinding();
            return Optional.empty();
        }
        boolean rebound = boundActuator == null
                || boundSessionGeneration != status.sessionGeneration()
                || boundPlayer != player
                || boundConnection != player.connection;
        if (rebound) {
            releaseOldBinding();
            boundPlayer = player;
            boundConnection = player.connection;
            boundActuator = new FairPlayerActuator(player);
            boundSessionGeneration = status.sessionGeneration();
        }
        return Optional.of(new Binding(
                player,
                boundActuator,
                boundSessionGeneration
        ));
    }

    private void releaseOldBinding() {
        if (boundActuator != null) {
            quiesce(boundActuator);
        }
        boundPlayer = null;
        boundConnection = null;
        boundActuator = null;
        boundSessionGeneration = -1;
    }

    private static QuiesceReport quiesce(FairPlayerActuator actuator) {
        ActionOutcome release = safeAction(actuator::releaseUse);
        ActionOutcome abort = safeAction(actuator::abortMining);
        return new QuiesceReport(true, release, abort);
    }

    private static ActionOutcome safeAction(
            Supplier<ActionOutcome> operation
    ) {
        try {
            return Objects.requireNonNull(
                    operation.get(),
                    "action outcome"
            );
        } catch (RuntimeException exception) {
            return ActionOutcome.PLAYER_UNAVAILABLE;
        }
    }

    private static int findInventoryItem(
            ServerPlayer player,
            Item item
    ) {
        for (int slot = 0; slot < MAIN_INVENTORY_SLOT_COUNT; slot++) {
            if (player.getInventory().getItem(slot).is(item)) {
                return slot;
            }
        }
        return -1;
    }

    private record Binding(
            ServerPlayer player,
            FairPlayerActuator actuator,
            long sessionGeneration
    ) {
    }

    public record QuiesceReport(
            boolean available,
            ActionOutcome releaseUse,
            ActionOutcome abortMining
    ) {
        public QuiesceReport {
            Objects.requireNonNull(releaseUse, "releaseUse");
            Objects.requireNonNull(abortMining, "abortMining");
        }

        public boolean successful() {
            return available
                    && InteractionSkillValidation.releaseSucceeded(
                            releaseUse
                    )
                    && InteractionSkillValidation.releaseSucceeded(
                            abortMining
                    );
        }

        private static QuiesceReport unavailable() {
            return new QuiesceReport(
                    false,
                    ActionOutcome.PLAYER_UNAVAILABLE,
                    ActionOutcome.PLAYER_UNAVAILABLE
            );
        }
    }
}
