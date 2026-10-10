package dev.mcai.companion.action;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Tick-local player actuator for the clientless companion.
 *
 * <p>Position is never assigned. Movement is advanced once per server tick by
 * vanilla {@link ServerPlayer#travel(Vec3)} collision/physics, while player
 * input, rotation and sprint transitions pass through the normal play-packet
 * listener.</p>
 */
public final class FairPlayerActuator {
    private static final double INPUT_EPSILON = 1.0E-5;

    private final UUID expectedPlayerId;
    private final ServerGamePacketListenerImpl listener;
    private final ActionLimits limits;
    private final LocalInputController inputController;
    private MiningOperation mining;
    private int packetSequence;

    public FairPlayerActuator(ServerPlayer player) {
        this(player, ActionLimits.defaults());
    }

    public FairPlayerActuator(ServerPlayer player, ActionLimits limits) {
        Objects.requireNonNull(player, "player");
        if (player.connection == null) {
            throw new IllegalArgumentException("player must have a play connection");
        }
        expectedPlayerId = player.getUUID();
        listener = player.connection;
        this.limits = Objects.requireNonNull(limits, "limits");
        inputController = new LocalInputController(limits);
    }

    public ActionOutcome setMovement(MovementIntent movement) {
        Objects.requireNonNull(movement, "movement");
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        inputController.setMovement(movement);
        return ActionOutcome.QUEUED;
    }

    public ActionOutcome turnTo(LookIntent look) {
        Objects.requireNonNull(look, "look");
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        inputController.setLook(look);
        return ActionOutcome.QUEUED;
    }

    public ActionOutcome jump() {
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        inputController.queueJump();
        return ActionOutcome.QUEUED;
    }

    /**
     * Clears input without cancelling physical inertia, matching key release.
     */
    public ActionOutcome stop() {
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        ServerPlayer player = access.player();
        inputController.stopImmediately();
        listener.handlePlayerInput(new ServerboundPlayerInputPacket(Input.EMPTY));
        player.setJumping(false);
        if (player.isSprinting()) {
            listener.handlePlayerCommand(new ServerboundPlayerCommandPacket(
                    player,
                    ServerboundPlayerCommandPacket.Action.STOP_SPRINTING
            ));
        }
        return ActionOutcome.DISPATCHED;
    }

    /**
     * Advances at most one movement/turn frame. Call exactly once per normal
     * server tick.
     */
    public ActionOutcome tick() {
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        ServerPlayer player = access.player();
        InputFrame frame = inputController.nextFrame(
                player.getYRot(),
                player.getXRot()
        );
        if (frame.rotationChanged()) {
            listener.handleMovePlayer(new ServerboundMovePlayerPacket.Rot(
                    frame.yaw(),
                    frame.pitch(),
                    player.onGround(),
                    player.horizontalCollision
            ));
        }

        boolean sprint = frame.sprint()
                && !frame.sneak()
                && frame.forward() > INPUT_EPSILON
                && !player.isUsingItem()
                && player.canSprint();
        Input input = new Input(
                frame.forward() > INPUT_EPSILON,
                frame.forward() < -INPUT_EPSILON,
                frame.strafeLeft() > INPUT_EPSILON,
                frame.strafeLeft() < -INPUT_EPSILON,
                frame.jump(),
                frame.sneak(),
                sprint
        );
        listener.handlePlayerInput(new ServerboundPlayerInputPacket(input));
        if (sprint != player.isSprinting()) {
            listener.handlePlayerCommand(new ServerboundPlayerCommandPacket(
                    player,
                    sprint
                            ? ServerboundPlayerCommandPacket.Action.START_SPRINTING
                            : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING
            ));
        }

        ActionOutcome jumpOutcome = applyJump(player, frame.jump());
        // A normal client supplies rider input while the vehicle owns its
        // movement. Applying Player.travel as well would add a second,
        // non-vanilla movement path to a passenger.
        if (!player.isPassenger()) {
            applyVanillaTravel(player, frame);
        }
        player.setJumping(false);
        ActionOutcome miningOutcome = mining == null
                ? ActionOutcome.NO_ACTIVE_ACTION
                : continueMiningInternal(player);
        if (miningOutcome == ActionOutcome.COMPLETED
                || miningOutcome == ActionOutcome.TARGET_CHANGED
                || miningOutcome == ActionOutcome.TARGET_UNLOADED
                || miningOutcome == ActionOutcome.TARGET_OUT_OF_REACH
                || miningOutcome == ActionOutcome.TARGET_OCCLUDED
                || miningOutcome == ActionOutcome.WORLD_DENIED
                || miningOutcome == ActionOutcome.TIMED_OUT) {
            return miningOutcome;
        }
        return jumpOutcome == ActionOutcome.INVALID_PLAYER_STATE
                ? jumpOutcome
                : ActionOutcome.DISPATCHED;
    }

    public ActionOutcome swing(ActionHand hand) {
        Objects.requireNonNull(hand, "hand");
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        listener.handleAnimate(new ServerboundSwingPacket(hand.minecraftHand()));
        return ActionOutcome.DISPATCHED;
    }

    /**
     * Attacks only the entity currently under the companion's own crosshair.
     * The UUID must therefore refer to a loaded, visible entity in vanilla
     * attack reach.
     */
    public ActionOutcome attack(UUID entityId) {
        Objects.requireNonNull(entityId, "entityId");
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        ServerPlayer player = access.player();
        EntityAccess targetAccess = accessEntity(player, entityId, true);
        if (targetAccess.failure() != null) {
            return targetAccess.failure();
        }
        Entity target = targetAccess.entity();
        ItemStack weapon = player.getMainHandItem();
        if (!weapon.isItemEnabled(player.level().enabledFeatures())
                || player.cannotAttackWithItem(weapon, 5)) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }
        listener.handleAttack(new ServerboundAttackPacket(target.getId()));
        listener.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        return ActionOutcome.DISPATCHED;
    }

    public ActionOutcome useItem(ActionHand hand) {
        Objects.requireNonNull(hand, "hand");
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        ServerPlayer player = access.player();
        InteractionHand minecraftHand = hand.minecraftHand();
        ItemStack item = player.getItemInHand(minecraftHand);
        if (item.isEmpty()
                || !item.isItemEnabled(player.level().enabledFeatures())) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }
        if (player.getCooldowns().isOnCooldown(item)) {
            return ActionOutcome.ITEM_ON_COOLDOWN;
        }
        listener.handleUseItem(new ServerboundUseItemPacket(
                minecraftHand,
                nextSequence(),
                player.getYRot(),
                player.getXRot()
        ));
        return ActionOutcome.DISPATCHED;
    }

    public ActionOutcome releaseUse() {
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        if (!access.player().isUsingItem()) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
        listener.handlePlayerAction(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM,
                BlockPos.ZERO,
                Direction.DOWN,
                nextSequence()
        ));
        return ActionOutcome.DISPATCHED;
    }

    /**
     * Uses or places the held item on the block surface currently under the
     * companion's own crosshair.
     */
    public ActionOutcome useOnBlock(
            ActionHand hand,
            BlockInteractionTarget target
    ) {
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(target, "target");
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        ServerPlayer player = access.player();
        BlockAccess blockAccess = accessBlock(player, target, false);
        if (blockAccess.failure() != null) {
            return blockAccess.failure();
        }
        InteractionHand minecraftHand = hand.minecraftHand();
        ItemStack item = player.getItemInHand(minecraftHand);
        if (!item.isItemEnabled(player.level().enabledFeatures())) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }
        final net.minecraft.world.item.Item beforeItem =
                item.getItem();
        final int beforeCount = item.getCount();
        final BlockHitResult hit = blockHit(target);
        /*
         * A clientless connection has no empty-hand item-use packet to feed
         * through ServerGamePacketListenerImpl.  The listener's packet
         * method is intentionally guarded by the client item path, so an
         * empty main hand would be reported as dispatched while vanilla
         * useWithoutItem (buttons, doors, containers, levers, etc.) never
         * runs.  The fair access/ray checks above have already reproduced
         * the complete client-side target contract; invoke the same vanilla
         * ServerPlayerGameMode transaction for this one headless case.
         */
        if (item.isEmpty() && minecraftHand == InteractionHand.MAIN_HAND) {
            final InteractionResult result = player.gameMode.useItemOn(
                    player,
                    player.level(),
                    item,
                    hand.minecraftHand(),
                    hit
            );
            if (result.consumesAction()) {
                player.swing(minecraftHand, true);
                return ActionOutcome.COMPLETED;
            }
            return ActionOutcome.DISPATCHED;
        }
        listener.handleUseItemOn(new ServerboundUseItemOnPacket(
                minecraftHand,
                hit,
                nextSequence()
        ));
        final ItemStack after =
                player.getItemInHand(minecraftHand);
        return (after.getItem() == beforeItem
                && after.getCount() == beforeCount - 1)
                || (beforeCount == 1 && after.isEmpty())
                ? ActionOutcome.COMPLETED
                : ActionOutcome.DISPATCHED;
    }

    public ActionOutcome interactEntity(UUID entityId, ActionHand hand) {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(hand, "hand");
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        ServerPlayer player = access.player();
        EntityAccess targetAccess = accessEntity(player, entityId, false);
        if (targetAccess.failure() != null) {
            return targetAccess.failure();
        }
        Entity target = targetAccess.entity();
        Vec3 relativeHit = targetAccess.crosshairHit().subtract(target.position());
        return dispatchEntityInteraction(player, target, hand, relativeHit);
    }

    public ActionOutcome interactEntityAt(
            EntityInteractionPoint interaction,
            ActionHand hand
    ) {
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(hand, "hand");
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        ServerPlayer player = access.player();
        EntityAccess targetAccess = accessEntity(
                player,
                interaction.entityId(),
                false
        );
        if (targetAccess.failure() != null) {
            return targetAccess.failure();
        }
        Entity target = targetAccess.entity();
        ActionVec3 supplied = interaction.relativePoint();
        Vec3 relative = new Vec3(supplied.x(), supplied.y(), supplied.z());
        Vec3 absolute = target.position().add(relative);
        if (!target.getBoundingBox().inflate(1.0E-3).contains(absolute)
                || absolute.distanceToSqr(targetAccess.crosshairHit()) > 0.0625) {
            return ActionOutcome.TARGET_OCCLUDED;
        }
        return dispatchEntityInteraction(player, target, hand, relative);
    }

    public ActionOutcome beginMining(BlockInteractionTarget target) {
        Objects.requireNonNull(target, "target");
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        if (mining != null) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }
        ServerPlayer player = access.player();
        BlockAccess blockAccess = accessBlock(player, target, true);
        if (blockAccess.failure() != null) {
            return blockAccess.failure();
        }
        BlockState state = blockAccess.state();
        if (state.isAir()
                || state.getDestroyProgress(
                        player,
                        player.level(),
                        blockAccess.position()
                ) <= 0.0F) {
            return ActionOutcome.WORLD_DENIED;
        }

        listener.handlePlayerAction(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                blockAccess.position(),
                target.face().direction(),
                nextSequence()
        ));
        listener.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        if (player.level().getBlockState(blockAccess.position()).isAir()) {
            return ActionOutcome.COMPLETED;
        }
        long gameTime = player.level().getGameTime();
        mining = new MiningOperation(
                target,
                state,
                gameTime,
                gameTime,
                MiningPhase.ACTIVE,
                -1L
        );
        return ActionOutcome.IN_PROGRESS;
    }

    public ActionOutcome continueMining() {
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        if (mining == null) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
        return continueMiningInternal(access.player());
    }

    public ActionOutcome abortMining() {
        PlayerAccess access = accessPlayer();
        if (access.failure() != null) {
            return access.failure();
        }
        if (mining == null) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
        sendMiningAction(
                mining.target(),
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
        );
        mining = null;
        return ActionOutcome.COMPLETED;
    }

    public ActionState state() {
        Optional<MiningSnapshot> miningSnapshot = mining == null
                ? Optional.empty()
                : Optional.of(mining.snapshot());
        return inputController.snapshot(miningSnapshot);
    }

    private static ActionOutcome applyJump(ServerPlayer player, boolean jump) {
        player.setJumping(jump);
        if (!jump) {
            return ActionOutcome.DISPATCHED;
        }
        if (!player.onGround() || player.isPassenger() || player.isSleeping()) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }
        player.jumpFromGround();
        return ActionOutcome.DISPATCHED;
    }

    private static void applyVanillaTravel(ServerPlayer player, InputFrame frame) {
        // LocalPlayer applies a 0.98 input factor before Player.travel. Keeping
        // that factor here avoids a subtle two-percent headless speed bonus.
        double forward = frame.forward() * 0.98;
        double strafe = frame.strafeLeft() * 0.98;
        if (player.isUsingItem() && !player.isPassenger()) {
            forward *= 0.2;
            strafe *= 0.2;
        }
        if (frame.sneak()) {
            double sneakMultiplier = Math.max(
                    0.0,
                    player.getAttributeValue(Attributes.SNEAKING_SPEED)
            );
            forward *= sneakMultiplier;
            strafe *= sneakMultiplier;
        }

        Vec3 before = player.position();
        player.travel(new Vec3(strafe, 0.0, forward));
        Vec3 movement = player.position().subtract(before);
        player.doCheckFallDamage(
                movement.x(),
                movement.y(),
                movement.z(),
                player.onGround()
        );
        player.checkMovementStatistics(
                movement.x(),
                movement.y(),
                movement.z()
        );
        if (movement.lengthSqr() > 0.0) {
            player.level().getChunkSource().move(player);
        }
    }

    private ActionOutcome continueMiningInternal(ServerPlayer player) {
        MiningOperation operation = mining;
        ServerLevel level = player.level();
        BlockPos position = blockPosition(operation.target());
        long gameTime = level.getGameTime();

        if (level.getBlockState(position).isAir()) {
            mining = null;
            return ActionOutcome.COMPLETED;
        }
        if (MiningProgressPolicy.timedOut(
                operation.startedAtGameTime(),
                gameTime,
                limits.miningTimeoutTicks()
        )) {
            sendMiningAction(
                    operation.target(),
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
            );
            mining = null;
            return ActionOutcome.TIMED_OUT;
        }
        if (!level.getBlockState(position).equals(operation.originalState())) {
            sendMiningAction(
                    operation.target(),
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
            );
            mining = null;
            return ActionOutcome.TARGET_CHANGED;
        }

        BlockAccess access = accessBlock(player, operation.target(), true);
        if (access.failure() != null) {
            sendMiningAction(
                    operation.target(),
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK
            );
            mining = null;
            return access.failure();
        }
        if (operation.phase() == MiningPhase.STOP_SENT) {
            if (gameTime - operation.stopSentAtGameTime() > 20) {
                mining = null;
                return ActionOutcome.TIMED_OUT;
            }
            return ActionOutcome.IN_PROGRESS;
        }
        if (gameTime == operation.lastContinuedAtGameTime()) {
            return ActionOutcome.IN_PROGRESS;
        }

        listener.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        float progressPerTick = operation.originalState().getDestroyProgress(
                player,
                level,
                position
        );
        long elapsedTicks = gameTime - operation.startedAtGameTime() + 1L;
        if (MiningProgressPolicy.readyToStop(progressPerTick, elapsedTicks)) {
            sendMiningAction(
                    operation.target(),
                    ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
            );
            mining = operation.withStopSent(gameTime);
            if (level.getBlockState(position).isAir()) {
                mining = null;
                return ActionOutcome.COMPLETED;
            }
            return ActionOutcome.IN_PROGRESS;
        }
        mining = operation.withLastContinued(gameTime);
        return ActionOutcome.IN_PROGRESS;
    }

    private ActionOutcome dispatchEntityInteraction(
            ServerPlayer player,
            Entity target,
            ActionHand hand,
            Vec3 relativeHit
    ) {
        InteractionHand minecraftHand = hand.minecraftHand();
        ItemStack item = player.getItemInHand(minecraftHand);
        if (!item.isItemEnabled(player.level().enabledFeatures())) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }
        listener.handleInteract(new ServerboundInteractPacket(
                target.getId(),
                minecraftHand,
                relativeHit,
                player.isShiftKeyDown()
        ));
        return ActionOutcome.DISPATCHED;
    }

    private EntityAccess accessEntity(
            ServerPlayer player,
            UUID entityId,
            boolean attack
    ) {
        ServerLevel level = player.level();
        Entity target = level.getEntity(entityId);
        if (target == null || target.level() != level || !target.isAlive()) {
            return EntityAccess.failed(ActionOutcome.TARGET_NOT_FOUND);
        }
        if (!level.isLoaded(target.blockPosition())) {
            return EntityAccess.failed(ActionOutcome.TARGET_UNLOADED);
        }
        if (!level.getWorldBorder().isWithinBounds(target.blockPosition())) {
            return EntityAccess.failed(ActionOutcome.WORLD_DENIED);
        }
        boolean dragonRootAttack = attack
                && target instanceof EnderDragon;
        if (target == player
                || target.isInvisibleTo(player)
                || !target.isPickable() && !dragonRootAttack
                || attack && invalidAttackTarget(target)) {
            return EntityAccess.failed(ActionOutcome.TARGET_OCCLUDED);
        }

        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(16.0));
        AABB searchBounds = player.getBoundingBox()
                .expandTowards(end.subtract(eye))
                .inflate(1.0);
        EntityHitResult picked = ProjectileUtil.getEntityHitResult(
                player,
                eye,
                end,
                searchBounds,
                EntitySelector.CAN_BE_PICKED.and(entity -> entity != player),
                16.0 * 16.0
        );
        if (picked == null
                || !crosshairEntityMatches(
                        target,
                        picked.getEntity(),
                        attack
                )
                || !rayLoaded(level, eye, picked.getLocation())) {
            return EntityAccess.failed(ActionOutcome.TARGET_OCCLUDED);
        }
        Entity actionTarget = picked.getEntity();
        AABB actionBounds = actionTarget.getBoundingBox();
        boolean inRange = attack
                ? player.isWithinAttackRange(
                        player.getMainHandItem(),
                        actionBounds,
                        0.0
                )
                : player.isWithinEntityInteractionRange(
                        actionBounds,
                        0.0
                );
        if (!inRange) {
            return EntityAccess.failed(ActionOutcome.TARGET_OUT_OF_REACH);
        }
        BlockHitResult obstruction = level.clip(new ClipContext(
                eye,
                picked.getLocation(),
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        ));
        if (obstruction.getType() != HitResult.Type.MISS
                && obstruction.getLocation().distanceToSqr(eye)
                + 1.0E-6 < picked.getLocation().distanceToSqr(eye)) {
            return EntityAccess.failed(ActionOutcome.TARGET_OCCLUDED);
        }
        return EntityAccess.available(
                actionTarget,
                picked.getLocation()
        );
    }

    /**
     * A normal client selects an {@link EnderDragonPart}, while semantic
     * perception deliberately exposes the stable UUID of its parent dragon.
     * Accept only that exact parent/part relationship and dispatch the packet
     * against the part that was genuinely under the companion's crosshair.
     */
    private static boolean crosshairEntityMatches(
            Entity requested,
            Entity picked,
            boolean attack
    ) {
        if (picked == requested) {
            return true;
        }
        return attack
                && requested instanceof EnderDragon dragon
                && picked instanceof EnderDragonPart part
                && part.parentMob == dragon;
    }

    private BlockAccess accessBlock(
            ServerPlayer player,
            BlockInteractionTarget target,
            boolean miningAction
    ) {
        ServerLevel level = player.level();
        BlockPos position = blockPosition(target);
        if (position.getY() < level.getMinY()
                || position.getY() > level.getMaxY()
                || !level.isLoaded(position)) {
            return BlockAccess.failed(ActionOutcome.TARGET_UNLOADED);
        }
        if (!level.getWorldBorder().isWithinBounds(position)) {
            return BlockAccess.failed(ActionOutcome.WORLD_DENIED);
        }
        if (!player.isWithinBlockInteractionRange(position, 0.0)) {
            return BlockAccess.failed(ActionOutcome.TARGET_OUT_OF_REACH);
        }
        if (level.getServer().isUnderSpawnProtection(level, position, player)
                || !level.mayInteract(player, position)
                || miningAction && player.blockActionRestricted(
                        level,
                        position,
                        player.gameMode.getGameModeForPlayer()
                )) {
            return BlockAccess.failed(ActionOutcome.WORLD_DENIED);
        }
        if (!isBlockUnderCrosshair(player, target)) {
            return BlockAccess.failed(ActionOutcome.TARGET_OCCLUDED);
        }
        return BlockAccess.available(position, level.getBlockState(position));
    }

    private static boolean isBlockUnderCrosshair(
            ServerPlayer player,
            BlockInteractionTarget target
    ) {
        ServerLevel level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(
                player.getViewVector(1.0F).scale(player.blockInteractionRange())
        );
        if (!rayLoaded(level, eye, end)) {
            return false;
        }
        BlockHitResult actual = level.clip(new ClipContext(
                eye,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));
        ActionVec3 expectedHit = target.hitPoint();
        return actual.getType() == HitResult.Type.BLOCK
                && actual.getBlockPos().equals(blockPosition(target))
                && actual.getDirection() == target.face().direction()
                && actual.getLocation().distanceToSqr(new Vec3(
                        expectedHit.x(),
                        expectedHit.y(),
                        expectedHit.z()
                )) <= 0.0625;
    }

    private static boolean rayLoaded(ServerLevel level, Vec3 start, Vec3 end) {
        double distance = start.distanceTo(end);
        int samples = Math.max(1, (int) Math.ceil(distance));
        for (int sample = 0; sample <= samples; sample++) {
            double fraction = sample / (double) samples;
            Vec3 point = start.lerp(end, fraction);
            if (!level.isLoaded(BlockPos.containing(point))) {
                return false;
            }
        }
        return true;
    }

    private static boolean invalidAttackTarget(Entity target) {
        return !target.isAttackable()
                || target instanceof ItemEntity
                || target instanceof ExperienceOrb
                || target instanceof AbstractArrow arrow && !arrow.isAttackable();
    }

    private void sendMiningAction(
            BlockInteractionTarget target,
            ServerboundPlayerActionPacket.Action action
    ) {
        listener.handlePlayerAction(new ServerboundPlayerActionPacket(
                action,
                blockPosition(target),
                target.face().direction(),
                nextSequence()
        ));
    }

    private int nextSequence() {
        int current = packetSequence;
        packetSequence = packetSequence == Integer.MAX_VALUE
                ? 0
                : packetSequence + 1;
        return current;
    }

    private static BlockPos blockPosition(BlockInteractionTarget target) {
        return new BlockPos(target.x(), target.y(), target.z());
    }

    private static BlockHitResult blockHit(BlockInteractionTarget target) {
        ActionVec3 hit = target.hitPoint();
        return new BlockHitResult(
                new Vec3(hit.x(), hit.y(), hit.z()),
                target.face().direction(),
                blockPosition(target),
                false
        );
    }

    private PlayerAccess accessPlayer() {
        ServerPlayer player = listener.getPlayer();
        if (player == null
                || !expectedPlayerId.equals(player.getUUID())
                || player.connection != listener
                || player.isRemoved()) {
            return PlayerAccess.failed(ActionOutcome.PLAYER_UNAVAILABLE);
        }
        if (!player.level().getServer().isSameThread()) {
            return PlayerAccess.failed(ActionOutcome.WRONG_THREAD);
        }
        if (!player.isAlive() || player.isSpectator() || player.isSleeping()) {
            return PlayerAccess.failed(ActionOutcome.PLAYER_INCAPACITATED);
        }
        return PlayerAccess.available(player);
    }

    private record PlayerAccess(ServerPlayer player, ActionOutcome failure) {
        private static PlayerAccess available(ServerPlayer player) {
            return new PlayerAccess(player, null);
        }

        private static PlayerAccess failed(ActionOutcome outcome) {
            return new PlayerAccess(null, outcome);
        }
    }

    private record EntityAccess(
            Entity entity,
            Vec3 crosshairHit,
            ActionOutcome failure
    ) {
        private static EntityAccess available(Entity entity, Vec3 hit) {
            return new EntityAccess(entity, hit, null);
        }

        private static EntityAccess failed(ActionOutcome outcome) {
            return new EntityAccess(null, null, outcome);
        }
    }

    private record BlockAccess(
            BlockPos position,
            BlockState state,
            ActionOutcome failure
    ) {
        private static BlockAccess available(BlockPos position, BlockState state) {
            return new BlockAccess(position, state, null);
        }

        private static BlockAccess failed(ActionOutcome outcome) {
            return new BlockAccess(null, null, outcome);
        }
    }

    private record MiningOperation(
            BlockInteractionTarget target,
            BlockState originalState,
            long startedAtGameTime,
            long lastContinuedAtGameTime,
            MiningPhase phase,
            long stopSentAtGameTime
    ) {
        private MiningOperation {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(originalState, "originalState");
            Objects.requireNonNull(phase, "phase");
        }

        private MiningOperation withLastContinued(long gameTime) {
            return new MiningOperation(
                    target,
                    originalState,
                    startedAtGameTime,
                    gameTime,
                    phase,
                    stopSentAtGameTime
            );
        }

        private MiningOperation withStopSent(long gameTime) {
            return new MiningOperation(
                    target,
                    originalState,
                    startedAtGameTime,
                    gameTime,
                    MiningPhase.STOP_SENT,
                    gameTime
            );
        }

        private MiningSnapshot snapshot() {
            return new MiningSnapshot(
                    target,
                    phase,
                    startedAtGameTime,
                    lastContinuedAtGameTime
            );
        }
    }
}
