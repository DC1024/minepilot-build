package dev.mcai.companion.progression;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.control.GoalExecutionPlan;
import dev.mcai.companion.world.CompanionWorldData;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.level.block.Blocks;

/**
 * Records only successful player-path actions used as M1 foundation
 * evidence. The high-level model cannot write this audit.
 */
public final class FoundationActionAudit {
    private final CompanionWorldData worldData;
    private PendingFixture pendingFixture;

    public FoundationActionAudit(final CompanionWorldData worldData) {
        this.worldData = Objects.requireNonNull(
                worldData,
                "worldData"
        );
    }

    public void observeBlockUse(
            final ServerPlayer player,
            final BlockInteractionTarget target,
            final ActionOutcome outcome
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(outcome, "outcome");
        if (!outcome.accepted() || !activeFoundationGoal()) {
            return;
        }
        final BlockPos position = new BlockPos(
                target.x(),
                target.y(),
                target.z()
        );
        final Optional<FoundationFixtureKind> kind = fixtureKind(
                player,
                position
        );
        if (kind.isEmpty()) {
            return;
        }
        if (menuMatches(kind.orElseThrow(), player)) {
            record(kind.orElseThrow(), player, position);
        } else {
            /*
             * A headless ServerPlayer can dispatch the vanilla use packet
             * before the server opens the menu on the following tick. Keep
             * only this exact successful target as a short-lived receipt;
             * tick(ServerPlayer) verifies the actual menu before recording
             * spatial memory. This is not a level scan or a placement claim.
             */
            pendingFixture = new PendingFixture(
                    kind.orElseThrow(),
                    player.level().dimension().identifier().toString(),
                    position,
                    0
            );
        }
    }

    /**
     * Completes the one-tick-late menu receipt from the server thread.
     */
    public void tick(final ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        if (!activeFoundationGoal()) {
            pendingFixture = null;
            return;
        }
        final PendingFixture pending = pendingFixture;
        if (pending == null) {
            return;
        }
        if (pending.ageTicks() >= 40
                || !pending.dimension().equals(
                        player.level().dimension().identifier().toString()
                )) {
            pendingFixture = null;
            return;
        }
        if (menuMatches(pending.kind(), player)
                && isFixtureBlock(
                        player.level().getBlockState(pending.position()),
                        pending.kind()
                )) {
            record(pending.kind(), player, pending.position());
            pendingFixture = null;
        } else {
            pendingFixture = pending.withAge(pending.ageTicks() + 1);
        }
    }

    private Optional<FoundationFixtureKind> fixtureKind(
            final ServerPlayer player,
            final BlockPos position
    ) {
        final var state = player.level().getBlockState(position);
        if (state.is(Blocks.CRAFTING_TABLE)) {
            return Optional.of(FoundationFixtureKind.CRAFTING_TABLE);
        }
        if (state.is(Blocks.FURNACE)) {
            return Optional.of(FoundationFixtureKind.FURNACE);
        }
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST)) {
            return Optional.of(FoundationFixtureKind.STORAGE);
        }
        return Optional.empty();
    }

    private static boolean menuMatches(
            final FoundationFixtureKind kind,
            final ServerPlayer player
    ) {
        return switch (kind) {
            case CRAFTING_TABLE -> player.containerMenu instanceof CraftingMenu;
            case FURNACE -> player.containerMenu instanceof FurnaceMenu;
            case STORAGE -> player.containerMenu instanceof ChestMenu;
        };
    }

    private static boolean isFixtureBlock(
            final net.minecraft.world.level.block.state.BlockState state,
            final FoundationFixtureKind kind
    ) {
        return switch (kind) {
            case CRAFTING_TABLE -> state.is(Blocks.CRAFTING_TABLE);
            case FURNACE -> state.is(Blocks.FURNACE);
            case STORAGE -> state.is(Blocks.CHEST)
                    || state.is(Blocks.TRAPPED_CHEST);
        };
    }

    private void record(
            final FoundationFixtureKind kind,
            final ServerPlayer player,
            final BlockPos position
    ) {
        worldData.recordFoundationFixture(
                worldData.goalRevision(),
                kind,
                new VerifiedFixtureLocation(
                        player.level().dimension().identifier().toString(),
                        position.getX(),
                        position.getY(),
                        position.getZ()
                )
        );
    }

    public void observeMenuDeposit(
            final ServerPlayer player,
            final AbstractContainerMenu menu,
            final String itemId,
            final int affectedItems
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(menu, "menu");
        Objects.requireNonNull(itemId, "itemId");
        if (affectedItems <= 0
                || !(menu instanceof ChestMenu)
                || !activeFoundationGoal()) {
            return;
        }
        worldData.recordFoundationStorageDeposit(
                worldData.goalRevision(),
                itemId,
                affectedItems
        );
    }

    /**
     * Package-visible so the route contract can be verified without
     * fabricating a block interaction or trusting an action log.
     */
    boolean activeFoundationGoal() {
        if (!"RUNNING".equals(worldData.goalStatus())) {
            return false;
        }
        final Optional<GoalExecutionPlan> executionPlan =
                GoalExecutionPlan.fromDetailCode(
                        worldData.goalDetail()
                );
        if (executionPlan.isPresent()) {
            final GoalExecutionPlan.Route route = executionPlan
                    .orElseThrow()
                    .route();
            return route == GoalExecutionPlan.Route.FOUNDATION
                    || route == GoalExecutionPlan.Route.COMPLETION;
        }
        return SurvivalRouteTracker.isFoundationGoalText(
                worldData.activeGoal()
        );
    }

    private record PendingFixture(
            FoundationFixtureKind kind,
            String dimension,
            BlockPos position,
            int ageTicks
    ) {
        private PendingFixture {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(position, "position");
            if (ageTicks < 0) {
                throw new IllegalArgumentException("ageTicks must be non-negative");
            }
        }

        private PendingFixture withAge(final int nextAge) {
            return new PendingFixture(kind, dimension, position, nextAge);
        }
    }
}
