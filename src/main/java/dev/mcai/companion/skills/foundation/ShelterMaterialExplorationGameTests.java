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
import dev.mcai.companion.skills.gathering.ServerResourceInventorySource;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Physical boundary for the shelter-material wood-search fallback.
 */
public final class ShelterMaterialExplorationGameTests {
    private static final BlockPos TEST_ORIGIN =
            new BlockPos(16, 8, 16);
    private static final int BODY_START_TIMEOUT_TICKS = 3_000;

    private ShelterMaterialExplorationGameTests() {
    }

    /**
     * Starts with exactly sixty potential planks while the dynamic shelter
     * needs sixty-one before its door is crafted. The final oak log begins
     * beyond the normal 24-block semantic ray range. Completion therefore
     * requires ordinary exploration, fresh first-person discovery, mining,
     * pickup, table return, and vanilla recipe transactions.
     */
    public static void shelterMaterialWoodExploration(
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
                "Shelter-material exploration requires an isolated body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Shelter-material exploration body spawn was rejected"
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
                    "Shelter-material exploration body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Shelter-material exploration body timed out"
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
        private static final int EXECUTION_TIMEOUT_TICKS = 5_000;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final BlockPos table;
        private final BlockPos distantLog;
        private final Vec3 initialPosition;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerInteractionSkillFrameSource
                interactionFrames;
        private final ServerMenuSkillFrameSource menuFrames;
        private final ServerOwnedCoreSkillActuator core;
        private final PrepareFoundationShelterMaterialsSkill skill;
        private final long createdAt;
        private final int initialMinedLogs;
        private final int initialPickedUpLogs;
        private final int initialDoors;
        private final int initialTorches;

        private boolean initialOcclusionVerified;
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
            table = this.origin.south(24).east(2);
            distantLog = this.origin.south(27);
            createdAt = helper.getTick();
            prepareFixture();
            initialPosition = player.position();
            initialMinedLogs = mined(Blocks.OAK_LOG);
            initialPickedUpLogs = pickedUp(Items.OAK_LOG);
            initialDoors = crafted(Items.OAK_DOOR);
            initialTorches = crafted(Items.TORCH);

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
            skill = new PrepareFoundationShelterMaterialsSkill(
                    player.getUUID(),
                    core,
                    coreFrames,
                    interactions,
                    interactionFrames,
                    new ServerInventorySkillActuator(
                            server,
                            player.getUUID()
                    ),
                    new ServerResourceInventorySource(
                            server,
                            player.getUUID()
                    ),
                    new ServerMenuSkillActuator(
                            server,
                            player.getUUID(),
                            menuFrames
                    ),
                    menuFrames,
                    ignored -> Optional.of(location(table)),
                    ignored -> Optional.empty()
            );
        }

        private void prepareFixture() {
            final var level = helper.getLevel();
            for (int x = -5; x <= 5; x++) {
                for (int z = -5; z <= 31; z++) {
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
            level.setBlockAndUpdate(
                    distantLog,
                    Blocks.OAK_LOG.defaultBlockState()
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
                    new ItemStack(Items.OAK_LOG, 15)
            );
            player.getInventory().setItem(
                    2,
                    new ItemStack(Items.STICK)
            );
            player.getInventory().setItem(
                    3,
                    new ItemStack(Items.COAL)
            );
            unlock("minecraft:oak_planks");
            unlock("minecraft:oak_door");
            unlock("minecraft:torch");
            player.inventoryMenu.broadcastChanges();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.setDeltaMovement(Vec3.ZERO);
            player.teleportTo(
                    origin.getX() + 0.5,
                    origin.getY(),
                    origin.getZ() + 0.5
            );
            player.setXRot(0.0F);
            player.setYRot(0.0F);
            player.setYHeadRot(0.0F);
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
                    "Shelter-material exploration timed out: "
                            + checkpoint()
                            + ", position=" + player.position()
                            + ", look=" + player.getLookAngle()
            );
            try {
                final var observation = sampler.sample(player);
                if (!initialOcclusionVerified) {
                    helper.assertTrue(
                            observation.visibleBlockFaces().stream()
                                    .noneMatch(face ->
                                            face.blockTypeId().equals(
                                                    "minecraft:oak_log"
                                            )
                                    ),
                            "Distant wood was visible before exploration"
                    );
                    initialOcclusionVerified = true;
                }
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
                    final var rejected = skill.preconditions(
                            context,
                            NoParameters.INSTANCE
                    );
                    helper.assertTrue(
                            rejected.isEmpty(),
                            "Shelter-material exploration start rejected: "
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
                            "Shelter-material exploration failed: "
                                    + result.failure()
                                    + ", checkpoint=" + checkpoint()
                                    + ", position="
                                    + player.position()
                                    + ", target=" + distantLog
                                    + ", targetDistance="
                                    + player.position().distanceTo(
                                            Vec3.atCenterOf(distantLog)
                                    )
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
                    initialOcclusionVerified,
                    "Initial out-of-view condition was not verified"
            );
            helper.assertTrue(
                    player.position().distanceTo(initialPosition) >= 8.0,
                    "Body never physically explored toward distant wood"
            );
            helper.assertTrue(
                    helper.getLevel().getBlockState(distantLog)
                            .isAir(),
                    "Distant log was not physically mined"
            );
            helper.assertTrue(
                    mined(Blocks.OAK_LOG) > initialMinedLogs
                            && pickedUp(Items.OAK_LOG)
                                > initialPickedUpLogs,
                    "Distant wood lacked vanilla mining/pickup evidence"
            );
            helper.assertTrue(
                    crafted(Items.OAK_DOOR) > initialDoors
                            && crafted(Items.TORCH)
                                > initialTorches,
                    "Door or torch did not come from vanilla recipes"
            );
            helper.assertTrue(
                    player.getInventory().countItem(
                            Items.OAK_PLANKS
                    ) >= 55,
                    "Structural plank reserve is below the shelter minimum"
            );
            helper.assertTrue(
                    helper.getLevel().getBlockState(table)
                            .is(Blocks.CRAFTING_TABLE),
                    "Verified table changed during material preparation"
            );
            finished = true;
            helper.succeed();
        }

        private int mined(
                final net.minecraft.world.level.block.Block block
        ) {
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
