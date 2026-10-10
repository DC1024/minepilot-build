package dev.mcai.companion.skills.redstone;

import com.mojang.authlib.GameProfile;
import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.FairPlayerActuator;
import dev.mcai.companion.perception.FirstPersonCrosshairSampler;
import dev.mcai.companion.perception.VisibleBlockFace;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.commands.arguments.EntityAnchorArgument;

/**
 * Development-only redstone contracts using the same first-person interaction
 * path as a live companion skill.
 */
public final class RedstoneGameTests {
    private RedstoneGameTests() {
    }

    /**
     * A visible button is pressed through the ordinary ServerPlayer use path;
     * the vanilla button powers a dispenser and the dispenser creates an
     * arrow.  The fixture may initialize the dispenser inventory, but neither
     * the test nor production interaction code invokes the dispenser directly.
     */
    public static void dispenserButtonActivation(
            final GameTestHelper helper
    ) {
        final ServerPlayer player = createRegisteredPlayer(helper);
        player.initInventoryMenu();
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        final BlockPos dispenserPos = helper.absolutePos(
                new BlockPos(8, 1, 8)
        );
        final BlockPos buttonPos = dispenserPos.north();
        helper.getLevel().setBlockAndUpdate(
                dispenserPos,
                Blocks.DISPENSER.defaultBlockState().setValue(
                        DispenserBlock.FACING,
                        Direction.SOUTH
                )
        );
        final DispenserBlockEntity dispenser = helper.getLevel()
                .getBlockEntity(dispenserPos, net.minecraft.world.level.block.entity.BlockEntityTypes.DISPENSER)
                .orElse(null);
        helper.assertTrue(
                dispenser != null,
                "The dispenser fixture did not create its block entity"
        );
        dispenser.setItem(0, new ItemStack(Items.ARROW));
        helper.getLevel().setBlockAndUpdate(
                buttonPos,
                Blocks.STONE_BUTTON.defaultBlockState()
                        .setValue(ButtonBlock.FACE, AttachFace.WALL)
                        .setValue(ButtonBlock.FACING, Direction.SOUTH)
        );
        player.teleportTo(
                buttonPos.getX() + 0.5D,
                buttonPos.getY(),
                buttonPos.getZ() - 3.0D
        );
        player.lookAt(
                EntityAnchorArgument.Anchor.EYES,
                Vec3.atCenterOf(buttonPos)
        );
        player.setYHeadRot(player.getYRot());
        /*
         * This isolated GameTest player is intentionally not installed in
         * AiPlayerManager.  Use the same FairPlayerActuator that the
         * production owned wrapper delegates to; it still enforces the
         * first-person ray, range, loaded-chunk and vanilla packet checks.
         */
        final FairPlayerActuator interactions = new FairPlayerActuator(
                player
        );
        final AtomicBoolean requested = new AtomicBoolean();
        final AtomicInteger waitTicks = new AtomicInteger();
        helper.addCleanup(ignored -> {
            interactions.stop();
            if (helper.getLevel().getServer().getPlayerList()
                    .getPlayer(player.getUUID()) != null) {
                helper.getLevel().getServer().getPlayerList().remove(player);
            }
            player.discard();
        });
        helper.onEachTick(() -> {
            if (!player.connection.hasClientLoaded()) {
                // IForgeGameTestHelper's embedded connection starts with the
                // same vanilla 60-tick client-load guard as a newly joined
                // player.  Let that guard expire before dispatching the
                // ordinary use packet; production HeadlessConnectionPump
                // reaches this state by consuming the join/keepalive flow.
                player.connection.tickClientLoadTimeout();
                return;
            }
            if (!requested.get()) {
                final VisibleBlockFace crosshair =
                        FirstPersonCrosshairSampler.sample(player)
                                .orElse(null);
                helper.assertTrue(
                        crosshair != null
                                && crosshair.block().x() == buttonPos.getX()
                                && crosshair.block().y() == buttonPos.getY()
                                && crosshair.block().z() == buttonPos.getZ()
                                && "minecraft:stone_button".equals(
                                        crosshair.blockTypeId()
                                ),
                        "The button was not under the first-person crosshair: "
                                + crosshair
                );
                final ActionOutcome outcome = interactions.useOnBlock(
                        ActionHand.MAIN_HAND,
                        target(crosshair)
                );
                helper.assertTrue(
                        outcome == ActionOutcome.DISPATCHED
                                || outcome == ActionOutcome.COMPLETED,
                        "Vanilla button use was not dispatched: " + outcome
                );
                requested.set(true);
                return;
            }
            final int elapsed = waitTicks.incrementAndGet();
            if (elapsed < 10) {
                return;
            }
            if (elapsed == 10) {
                helper.assertTrue(
                        helper.getLevel().getBlockState(buttonPos)
                                .getValue(ButtonBlock.POWERED),
                        "Vanilla button packet did not power the observed button"
                );
                helper.assertTrue(
                        dispenser.getItem(0).isEmpty(),
                        "Dispenser did not consume its observed arrow"
                );
                final AABB search = new AABB(dispenserPos).inflate(8.0D);
                helper.assertTrue(
                        !helper.getLevel().getEntitiesOfClass(
                                AbstractArrow.class,
                                search
                        ).isEmpty(),
                        "Powered dispenser did not produce an arrow"
                );
            }
            if (elapsed < 25) {
                return;
            }
            helper.assertTrue(
                    !helper.getLevel().getBlockState(buttonPos)
                            .getValue(ButtonBlock.POWERED),
                    "Button did not return to its unpowered state"
            );
            helper.succeed();
        });
    }

    /**
     * A real first-person empty-hand use opens and closes a wooden door. This
     * is deliberately separate from the button/dispenser test: doors are the
     * common shelter interaction that previously looked like a stuck body when
     * the clientless packet path only reported dispatch without applying the
     * vanilla no-item use effect.
     */
    public static void doorOpenClose(
            final GameTestHelper helper
    ) {
        final ServerPlayer player = createRegisteredPlayer(helper);
        player.initInventoryMenu();
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        final BlockPos doorPos = helper.absolutePos(
                new BlockPos(8, 1, 8)
        );
        final var lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.OPEN, false)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                .setValue(DoorBlock.POWERED, false);
        final var upper = lower.setValue(
                DoorBlock.HALF,
                DoubleBlockHalf.UPPER
        );
        helper.getLevel().setBlockAndUpdate(doorPos, lower);
        helper.getLevel().setBlockAndUpdate(doorPos.above(), upper);
        player.teleportTo(
                doorPos.getX() + 0.5D,
                doorPos.getY(),
                doorPos.getZ() - 3.0D
        );
        player.lookAt(
                EntityAnchorArgument.Anchor.EYES,
                Vec3.atCenterOf(doorPos)
        );
        player.setYHeadRot(player.getYRot());
        final FairPlayerActuator interactions = new FairPlayerActuator(
                player
        );
        final AtomicInteger phase = new AtomicInteger();
        final AtomicInteger waitTicks = new AtomicInteger();
        helper.addCleanup(ignored -> {
            interactions.stop();
            if (helper.getLevel().getServer().getPlayerList()
                    .getPlayer(player.getUUID()) != null) {
                helper.getLevel().getServer().getPlayerList().remove(player);
            }
            player.discard();
        });
        helper.onEachTick(() -> {
            if (!player.connection.hasClientLoaded()) {
                player.connection.tickClientLoadTimeout();
                return;
            }
            if (phase.get() == 0) {
                final VisibleBlockFace crosshair =
                        FirstPersonCrosshairSampler.sample(player)
                                .orElse(null);
                helper.assertTrue(
                        crosshair != null
                                && crosshair.block().x() == doorPos.getX()
                                && crosshair.block().y() == doorPos.getY()
                                && crosshair.block().z() == doorPos.getZ()
                                && "minecraft:oak_door".equals(
                                        crosshair.blockTypeId()
                                ),
                        "The door was not under the first-person crosshair: "
                                + crosshair
                );
                final ActionOutcome outcome = interactions.useOnBlock(
                        ActionHand.MAIN_HAND,
                        target(crosshair)
                );
                helper.assertTrue(
                        outcome == ActionOutcome.DISPATCHED
                                || outcome == ActionOutcome.COMPLETED,
                        "Vanilla door open was not dispatched: " + outcome
                );
                phase.set(1);
                waitTicks.set(0);
                return;
            }
            if (phase.get() == 1) {
                if (waitTicks.incrementAndGet() < 2) {
                    return;
                }
                helper.assertTrue(
                        helper.getLevel().getBlockState(doorPos)
                                .getValue(DoorBlock.OPEN),
                        "Vanilla empty-hand use did not open the door"
                );
                phase.set(2);
                waitTicks.set(0);
                return;
            }
            if (phase.get() == 2) {
                final VisibleBlockFace crosshair = findDoorCrosshair(
                        player,
                        doorPos
                );
                helper.assertTrue(
                        crosshair != null
                                && crosshair.block().x() == doorPos.getX()
                                && crosshair.block().y() == doorPos.getY()
                                && crosshair.block().z() == doorPos.getZ(),
                        "The open door was not under the crosshair: "
                                + crosshair
                );
                final ActionOutcome outcome = interactions.useOnBlock(
                        ActionHand.MAIN_HAND,
                        target(crosshair)
                );
                helper.assertTrue(
                        outcome == ActionOutcome.DISPATCHED
                                || outcome == ActionOutcome.COMPLETED,
                        "Vanilla door close was not dispatched: " + outcome
                );
                phase.set(3);
                waitTicks.set(0);
                return;
            }
            if (waitTicks.incrementAndGet() < 2) {
                return;
            }
            helper.assertTrue(
                    !helper.getLevel().getBlockState(doorPos)
                            .getValue(DoorBlock.OPEN),
                    "Vanilla empty-hand use did not close the door"
            );
            helper.succeed();
        });
    }

    private static VisibleBlockFace findDoorCrosshair(
            final ServerPlayer player,
            final BlockPos doorPos
    ) {
        final double[] offsets = {-0.35D, 0.0D, 0.35D};
        for (double x : offsets) {
            for (double y : offsets) {
                player.lookAt(
                        EntityAnchorArgument.Anchor.EYES,
                        new Vec3(
                                doorPos.getX() + 0.5D + x,
                                doorPos.getY() + 0.9D + y,
                                doorPos.getZ() + 0.5D
                        )
                );
                player.setYHeadRot(player.getYRot());
                final VisibleBlockFace face =
                        FirstPersonCrosshairSampler.sample(player)
                                .orElse(null);
                if (face != null
                        && face.block().x() == doorPos.getX()
                        && face.block().y() == doorPos.getY()
                        && face.block().z() == doorPos.getZ()
                        && "minecraft:oak_door".equals(
                                face.blockTypeId()
                        )) {
                    return face;
                }
            }
        }
        return null;
    }

    private static ServerPlayer createRegisteredPlayer(
            final GameTestHelper helper
    ) {
        final GameProfile profile = new GameProfile(
                UUID.randomUUID(),
                "MCAITest"
        );
        final CommonListenerCookie cookie =
                CommonListenerCookie.createInitial(profile, false);
        final ServerPlayer player = new ServerPlayer(
                helper.getLevel().getServer(),
                helper.getLevel(),
                profile,
                cookie.clientInformation()
        );
        final Connection connection = new Connection(PacketFlow.SERVERBOUND);
        /* EmbeddedChannel keeps the same packet sink used by the real
         * headless connection pump alive for the duration of this test. */
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(
                connection,
                player,
                cookie
        );
        player.setGameMode(GameType.SURVIVAL);
        player.connection.handleAcceptPlayerLoad(
                new ServerboundPlayerLoadedPacket()
        );
        return player;
    }

    private static BlockInteractionTarget target(
            final VisibleBlockFace face
    ) {
        return new BlockInteractionTarget(
                face.block().x(),
                face.block().y(),
                face.block().z(),
                BlockFace.valueOf(
                        face.face().toUpperCase(Locale.ROOT)
                ),
                new ActionVec3(
                        face.hitPosition().x(),
                        face.hitPosition().y(),
                        face.hitPosition().z()
                )
        );
    }
}
