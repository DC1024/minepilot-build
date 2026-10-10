package dev.mcai.companion.skills.foundation;

import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.GameTestCompanionSpawn;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.perception.FairPerceptionSampler;
import dev.mcai.companion.progression.VerifiedFixtureLocation;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.core.ServerCoreSkillFrameSource;
import dev.mcai.companion.skills.core.ServerOwnedCoreSkillActuator;
import dev.mcai.companion.skills.gathering
        .ServerResourceInventorySource;
import dev.mcai.companion.skills.interaction
        .ServerInteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction
        .ServerOwnedInteractionSkillActuator;
import dev.mcai.companion.skills.inventory
        .ServerInventorySkillActuator;
import dev.mcai.companion.skills.menu.ServerMenuSkillActuator;
import dev.mcai.companion.skills.menu.ServerMenuSkillFrameSource;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Physical contract for deterministic workstation prerequisite composition.
 */
public final class WorkstationPrerequisiteGameTests {
    private static final BlockPos TEST_ORIGIN =
            new BlockPos(16, 8, 16);
    private static final int BODY_START_TIMEOUT_TICKS = 3_000;

    private WorkstationPrerequisiteGameTests() {
    }

    /**
     * Starts the public workstation compound with only four potential planks.
     * The one high-level action must gather the missing wood through ordinary
     * perception and mining before it can craft, place, open and use a chest.
     */
    public static void workstationWoodPrerequisiteComposition(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicReference<Scenario> scenario =
                new AtomicReference<>();
        final long startedAt = helper.getTick();

        helper.addCleanup(ignored -> {
            final Scenario current = scenario.get();
            if (current != null) {
                current.cleanup();
            }
            if (AiPlayerManager.status(server).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(server);
            }
        });
        GameTestCompanionSpawn.resetForIsolatedFixture(server);

        helper.assertTrue(
                AiPlayerManager.status(server).state()
                        == SessionState.ABSENT,
                "Workstation prerequisite gate requires an isolated body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Workstation prerequisite body spawn was rejected"
        );

        helper.onEachTick(() -> {
            final Scenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Workstation prerequisite companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Workstation prerequisite body timed out"
                );
                return;
            }
            scenario.set(new Scenario(
                    helper,
                    AiPlayerManager.onlinePlayer(server).orElseThrow(),
                    helper.absolutePos(TEST_ORIGIN)
            ));
        });
    }

    private static final class Scenario {
        private static final int EXECUTION_TIMEOUT_TICKS = 9_000;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final BlockPos table;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerInteractionSkillFrameSource
                interactionFrames;
        private final ServerMenuSkillFrameSource menuFrames;
        private final ServerOwnedCoreSkillActuator core;
        private final EstablishFoundationWorkstationsSkill skill;
        private final long createdAt;
        private final int initialMinedLogs;
        private final int initialPickedUpLogs;
        private final int initialCraftedDoors;
        private final int initialCraftedTorches;
        private final int initialCraftedChests;

        private boolean started;
        private boolean finished;
        private boolean cleaned;

        private Scenario(
                final GameTestHelper helper,
                final ServerPlayer player,
                final BlockPos origin
        ) {
            this.helper = helper;
            this.player = player;
            this.origin = origin.immutable();
            table = this.origin.west(2);
            createdAt = helper.getTick();
            prepareFixture();
            initialMinedLogs = mined(Blocks.OAK_LOG);
            initialPickedUpLogs = pickedUp(Items.OAK_LOG);
            initialCraftedDoors = crafted(Items.OAK_DOOR);
            initialCraftedTorches = crafted(Items.TORCH);
            initialCraftedChests = crafted(Items.CHEST);

            final var server = helper.getLevel().getServer();
            coreFrames = new ServerCoreSkillFrameSource(
                    server,
                    player.getUUID()
            );
            interactionFrames =
                    new ServerInteractionSkillFrameSource(
                            server,
                            player.getUUID()
                    );
            menuFrames = new ServerMenuSkillFrameSource(
                    server,
                    player.getUUID()
            );
            core = new ServerOwnedCoreSkillActuator(
                    server,
                    player.getUUID()
            );
            final var interactions =
                    new ServerOwnedInteractionSkillActuator(
                            server,
                            player.getUUID()
                    );
            final var inventory =
                    new ServerInventorySkillActuator(
                            server,
                            player.getUUID()
                    );
            final var resources =
                    new ServerResourceInventorySource(
                            server,
                            player.getUUID()
                    );
            final var menus = new ServerMenuSkillActuator(
                    server,
                    player.getUUID(),
                    menuFrames
            );
            skill = new EstablishFoundationWorkstationsSkill(
                    player.getUUID(),
                    core,
                    coreFrames,
                    interactions,
                    interactionFrames,
                    inventory,
                    resources,
                    menus,
                    menuFrames,
                    ignored -> Optional.of(location(table)),
                    ignored -> Optional.empty(),
                    ignored -> Optional.empty()
            );
        }

        private void prepareFixture() {
            final var level = helper.getLevel();
            for (int x = -10; x <= 10; x++) {
                for (int z = -10; z <= 10; z++) {
                    level.setBlockAndUpdate(
                            origin.offset(x, -1, z),
                            Blocks.DIRT.defaultBlockState()
                    );
                    for (int y = 0; y <= 5; y++) {
                        level.setBlockAndUpdate(
                                origin.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            level.setBlockAndUpdate(
                    table,
                    Blocks.CRAFTING_TABLE.defaultBlockState()
            );
            int logs = 0;
            for (int x = 4; x <= 7; x++) {
                for (int z = -2; z <= 2; z++) {
                    if (logs >= 17) {
                        break;
                    }
                    level.setBlockAndUpdate(
                            origin.offset(x, 0, z),
                            Blocks.OAK_LOG.defaultBlockState()
                    );
                    logs++;
                }
            }
            helper.assertTrue(
                    logs == 17,
                    "Fixture did not create the required wood cluster"
            );

            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            player.setItemInHand(
                    InteractionHand.MAIN_HAND,
                    new ItemStack(Items.IRON_PICKAXE)
            );
            player.getInventory().setItem(
                    1,
                    new ItemStack(Items.OAK_LOG)
            );
            player.getInventory().setItem(
                    2,
                    new ItemStack(Items.WOODEN_PICKAXE)
            );
            player.getInventory().setItem(
                    3,
                    new ItemStack(Items.STICK)
            );
            player.getInventory().setItem(
                    4,
                    new ItemStack(Items.COAL)
            );
            unlock("minecraft:oak_planks");
            unlock("minecraft:oak_door");
            unlock("minecraft:torch");
            unlock("minecraft:chest");
            player.inventoryMenu.broadcastChanges();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.setDeltaMovement(Vec3.ZERO);
            player.teleportTo(
                    origin.getX() + 0.5,
                    origin.getY(),
                    origin.getZ() + 0.5
            );
            player.setXRot(12.0F);
            player.setYRot(-90.0F);
            player.setYHeadRot(-90.0F);
        }

        private void unlock(final String recipeId) {
            final ResourceKey<Recipe<?>> key = ResourceKey.create(
                    Registries.RECIPE,
                    Identifier.parse(recipeId)
            );
            player.getRecipeBook().add(
                    player.level()
                            .getServer()
                            .getRecipeManager()
                            .byKey(key)
                            .orElseThrow()
                            .id()
            );
        }

        private void tick() {
            if (finished) {
                return;
            }
            helper.assertTrue(
                    helper.getTick() - createdAt
                            <= EXECUTION_TIMEOUT_TICKS,
                    "Workstation prerequisite skill timed out: "
                            + checkpoint()
            );
            try {
                final var observation = sampler.sample(player);
                coreFrames.publish(observation);
                interactionFrames.publish(observation);
                menuFrames.publish(observation);
                final SkillContext context = new SkillContext(
                        1,
                        observation.sequence(),
                        helper.getTick(),
                        true,
                        true,
                        0.0
                );
                if (!started) {
                    helper.assertTrue(
                            player.getInventory().countItem(
                                    Items.OAK_LOG
                            ) == 1,
                            "Fixture no longer starts below chest wood target"
                    );
                    final var rejected = skill.preconditions(
                            context,
                            NoParameters.INSTANCE
                    );
                    helper.assertTrue(
                            rejected.isEmpty(),
                            "Composed workstation start rejected: "
                                    + rejected
                    );
                    skill.start(context, NoParameters.INSTANCE);
                    started = true;
                }
                final SkillTickResult result = skill.tick(
                        context,
                        NoParameters.INSTANCE
                );
                if (result.status()
                        == SkillTickResult.Status.FAILED) {
                    finished = true;
                    helper.assertTrue(
                            false,
                            "Composed workstation skill failed: "
                                    + result.failure()
                                    + ", checkpoint=" + checkpoint()
                    );
                    return;
                }
                if (result.status()
                        == SkillTickResult.Status.COMPLETED) {
                    verifyCompletion();
                }
            } finally {
                if (!finished) {
                    core.postServerTick();
                }
            }
        }

        private void verifyCompletion() {
            helper.assertTrue(
                    mined(Blocks.OAK_LOG) > initialMinedLogs
                            && pickedUp(Items.OAK_LOG)
                                > initialPickedUpLogs,
                    "Prerequisite wood lacked vanilla mining/pickup evidence"
            );
            helper.assertTrue(
                    crafted(Items.OAK_DOOR) > initialCraftedDoors
                            && crafted(Items.TORCH)
                                > initialCraftedTorches,
                    "Material prerequisite skipped vanilla recipes"
            );
            helper.assertTrue(
                    crafted(Items.CHEST) > initialCraftedChests,
                    "Chest did not come from a vanilla recipe"
            );
            helper.assertTrue(
                    player.getInventory().countItem(
                            Items.OAK_PLANKS
                    ) >= 55,
                    "Chest consumed the reserved shelter structure planks"
            );
            final BlockPos chest = BlockPos.betweenClosedStream(
                    origin.offset(-10, 0, -10),
                    origin.offset(10, 2, 10)
            ).filter(pos ->
                    helper.getLevel().getBlockState(pos)
                            .is(Blocks.CHEST)
            ).findFirst().orElseThrow(() ->
                    helper.assertionException(
                            "No physical chest was placed"
                    )
            );
            final Object blockEntity =
                    helper.getLevel().getBlockEntity(chest);
            helper.assertTrue(
                    blockEntity instanceof Container,
                    "Placed chest has no vanilla container"
            );
            final Container contents = (Container) blockEntity;
            helper.assertTrue(
                    IntStream.range(0, contents.getContainerSize())
                            .mapToObj(contents::getItem)
                            .anyMatch(stack ->
                                    stack.is(Items.WOODEN_PICKAXE)),
                    "Surplus wooden pickaxe was not transferred into chest"
            );
            helper.assertTrue(
                    player.getInventory().countItem(
                            Items.WOODEN_PICKAXE
                    ) == 0,
                    "Deposited surplus remained in player inventory"
            );
            helper.assertTrue(
                    helper.getLevel().getBlockState(table)
                            .is(Blocks.CRAFTING_TABLE),
                    "Verified crafting table changed during composition"
            );
            finished = true;
            helper.succeed();
        }

        private int mined(final Block block) {
            return player.getStats().getValue(
                    Stats.BLOCK_MINED.get(block)
            );
        }

        private int pickedUp(final Item item) {
            return player.getStats().getValue(
                    Stats.ITEM_PICKED_UP.get(item)
            );
        }

        private int crafted(final Item item) {
            return player.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(item)
            );
        }

        private VerifiedFixtureLocation location(
                final BlockPos position
        ) {
            return new VerifiedFixtureLocation(
                    helper.getLevel().dimension()
                            .identifier().toString(),
                    position.getX(),
                    position.getY(),
                    position.getZ()
            );
        }

        private String checkpoint() {
            return skill.checkpoint(
                    new SkillContext(
                            1,
                            0,
                            helper.getTick(),
                            true,
                            true,
                            0.0
                    ),
                    NoParameters.INSTANCE
            ).payload();
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            core.quiesceNow();
            if (player.containerMenu != player.inventoryMenu) {
                player.closeContainer();
            }
        }
    }
}
