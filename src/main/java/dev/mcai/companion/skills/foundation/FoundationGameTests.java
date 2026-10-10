package dev.mcai.companion.skills.foundation;

import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.GameTestCompanionSpawn;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.navigation.GridPos;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Focused real-server contracts for early survival compound skills.
 */
public final class FoundationGameTests {
    private static final BlockPos TEST_ORIGIN =
            new BlockPos(16, 8, 16);
    private static final int BODY_START_TIMEOUT_TICKS = 3_000;

    private FoundationGameTests() {
    }

    /**
     * Runs the production basic-crafting state machine through ordinary
     * recipes, first-person table placement, menu opening, and result clicks.
     * A closer high-column top is deliberately visible; the skill must reject
     * it and place a reachable table on the local floor.
     */
    public static void reachableBasicCrafting(
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
                "Basic-crafting gate requires an isolated body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Basic-crafting companion spawn was rejected"
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
                    "Basic-crafting companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Basic-crafting companion did not become active"
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

    /**
     * Reproduces the post-smelting field failure: a verified table is within
     * raw reach but hidden by the newly placed furnace and adjacent blocks.
     * Success requires an ordinary side-step route, fresh first-person table
     * verification, a real menu, and vanilla recipe result clicks.
     */
    public static void occludedIronToolkitTable(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicReference<OccludedTableScenario> scenario =
                new AtomicReference<>();
        final long startedAt = helper.getTick();

        helper.addCleanup(ignored -> {
            final OccludedTableScenario current = scenario.get();
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
                "Occluded-table gate requires an isolated body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Occluded-table companion spawn was rejected"
        );

        helper.onEachTick(() -> {
            final OccludedTableScenario current =
                    scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Occluded-table companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Occluded-table companion did not become active"
                );
                return;
            }
            scenario.set(new OccludedTableScenario(
                    helper,
                    AiPlayerManager.onlinePlayer(server).orElseThrow(),
                    helper.absolutePos(TEST_ORIGIN)
            ));
        });
    }

    public static void containerWoodDoorSpruce(
            final GameTestHelper helper
    ) {
        containerWoodDoor(
                helper,
                Items.SPRUCE_LOG,
                Items.SPRUCE_PLANKS,
                Items.SPRUCE_DOOR,
                Blocks.SPRUCE_DOOR,
                true,
                1
        );
    }

    public static void containerWoodDoorWarped(
            final GameTestHelper helper
    ) {
        containerWoodDoor(
                helper,
                Items.WARPED_STEM,
                Items.WARPED_PLANKS,
                Items.WARPED_DOOR,
                Blocks.WARPED_DOOR,
                false,
                9
        );
    }

    public static void containerWoodDoorOak(final GameTestHelper helper) {
        containerWoodDoor(
                helper,
                Items.OAK_LOG,
                Items.OAK_PLANKS,
                Items.OAK_DOOR,
                Blocks.OAK_DOOR,
                false,
                0
        );
    }

    public static void containerWoodDoorBirch(final GameTestHelper helper) {
        containerWoodDoor(
                helper,
                Items.BIRCH_LOG,
                Items.BIRCH_PLANKS,
                Items.BIRCH_DOOR,
                Blocks.BIRCH_DOOR,
                false,
                2
        );
    }

    public static void containerWoodDoorJungle(final GameTestHelper helper) {
        containerWoodDoor(
                helper,
                Items.JUNGLE_LOG,
                Items.JUNGLE_PLANKS,
                Items.JUNGLE_DOOR,
                Blocks.JUNGLE_DOOR,
                true,
                3
        );
    }

    public static void containerWoodDoorAcacia(final GameTestHelper helper) {
        containerWoodDoor(
                helper,
                Items.ACACIA_LOG,
                Items.ACACIA_PLANKS,
                Items.ACACIA_DOOR,
                Blocks.ACACIA_DOOR,
                false,
                4
        );
    }

    public static void containerWoodDoorDarkOak(
            final GameTestHelper helper
    ) {
        containerWoodDoor(
                helper,
                Items.DARK_OAK_LOG,
                Items.DARK_OAK_PLANKS,
                Items.DARK_OAK_DOOR,
                Blocks.DARK_OAK_DOOR,
                true,
                5
        );
    }

    public static void containerWoodDoorMangrove(
            final GameTestHelper helper
    ) {
        containerWoodDoor(
                helper,
                Items.MANGROVE_LOG,
                Items.MANGROVE_PLANKS,
                Items.MANGROVE_DOOR,
                Blocks.MANGROVE_DOOR,
                false,
                6
        );
    }

    public static void containerWoodDoorCherry(final GameTestHelper helper) {
        containerWoodDoor(
                helper,
                Items.CHERRY_LOG,
                Items.CHERRY_PLANKS,
                Items.CHERRY_DOOR,
                Blocks.CHERRY_DOOR,
                true,
                7
        );
    }

    public static void containerWoodDoorCrimson(
            final GameTestHelper helper
    ) {
        containerWoodDoor(
                helper,
                Items.CRIMSON_STEM,
                Items.CRIMSON_PLANKS,
                Items.CRIMSON_DOOR,
                Blocks.CRIMSON_DOOR,
                false,
                8
        );
    }

    private static void containerWoodDoor(
            final GameTestHelper helper,
            final Item wood,
            final Item planks,
            final Item door,
            final Block doorBlock,
            final boolean startWithChestMenuOpen,
            final int geometryVariant
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicReference<ContainerWoodDoorScenario> scenario =
                new AtomicReference<>();
        final long startedAt = helper.getTick();
        helper.addCleanup(ignored -> {
            final ContainerWoodDoorScenario current = scenario.get();
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
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Container woodwork companion spawn was rejected"
        );
        helper.onEachTick(() -> {
            final ContainerWoodDoorScenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Container woodwork companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Container woodwork companion did not become active"
                );
                return;
            }
            scenario.set(new ContainerWoodDoorScenario(
                    helper,
                    AiPlayerManager.onlinePlayer(server).orElseThrow(),
                    helper.absolutePos(TEST_ORIGIN),
                    wood,
                    planks,
                    door,
                    doorBlock,
                    startWithChestMenuOpen,
                    geometryVariant
            ));
        });
    }

    private static final class ContainerWoodDoorScenario {
        private static final int EXECUTION_TIMEOUT_TICKS = 4_000;
        private static final int CHEST_START_COUNT = 5;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final Item wood;
        private final Item planks;
        private final Item door;
        private final Block doorBlock;
        private final List<BlockPos> chests;
        private final BlockPos table;
        private final BlockPos expectedDoor;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerInteractionSkillFrameSource interactionFrames;
        private final ServerMenuSkillFrameSource menuFrames;
        private final ServerOwnedCoreSkillActuator core;
        private final PrepareContainerWoodDoorSkill skill;
        private final AtomicBoolean completionEvidence =
                new AtomicBoolean();
        private final long createdAt;
        private final int initialPlanksCrafted;
        private final int initialDoorsCrafted;

        private boolean started;
        private boolean finished;
        private boolean cleaned;

        private ContainerWoodDoorScenario(
                final GameTestHelper helper,
                final ServerPlayer player,
                final BlockPos origin,
                final Item wood,
                final Item planks,
                final Item door,
                final Block doorBlock,
                final boolean startWithChestMenuOpen,
                final int geometryVariant
        ) {
            this.helper = helper;
            this.player = player;
            this.origin = origin.immutable();
            this.wood = wood;
            this.planks = planks;
            this.door = door;
            this.doorBlock = doorBlock;
            final boolean northSouthGroup = geometryVariant % 2 != 0;
            chests = northSouthGroup
                    ? List.of(
                            this.origin.north(3),
                            this.origin.north(),
                            this.origin.south(),
                            this.origin.south(3)
                    )
                    : List.of(
                            this.origin.west(3),
                            this.origin.west(),
                            this.origin.east(),
                            this.origin.east(3)
                    );
            table = northSouthGroup
                    ? this.origin.west(3)
                    : this.origin.north(3);
            expectedDoor = northSouthGroup
                    ? this.origin.east(3)
                    : this.origin.south(3);
            createdAt = helper.getTick();
            prepareFixture(northSouthGroup, geometryVariant);
            if (startWithChestMenuOpen) {
                final BlockPos openChest = chests.getFirst();
                player.openMenu(
                        helper.getLevel()
                                .getBlockState(openChest)
                                .getMenuProvider(
                                        helper.getLevel(),
                                        openChest
                                )
                );
                helper.assertTrue(
                        player.containerMenu != player.inventoryMenu,
                        "Source chest menu did not open before handoff"
                );
            }
            initialPlanksCrafted = crafted(planks);
            initialDoorsCrafted = crafted(door);
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
            final var inventory = new ServerInventorySkillActuator(
                    server,
                    player.getUUID()
            );
            skill = new PrepareContainerWoodDoorSkill(
                    player.getUUID(),
                    core,
                    coreFrames,
                    interactions,
                    interactionFrames,
                    inventory,
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
                    ignored -> Optional.empty(),
                    ignored -> Optional.of(location(chests.getFirst())),
                    ignored -> completionEvidence.set(true)
            );
        }

        private void prepareFixture(
                final boolean northSouthGroup,
                final int geometryVariant
        ) {
            final var level = helper.getLevel();
            for (int x = -10; x <= 10; x++) {
                for (int z = -10; z <= 10; z++) {
                    final int floorY = terrainFloorOffset(
                            geometryVariant,
                            northSouthGroup,
                            x,
                            z
                    );
                    level.setBlockAndUpdate(
                            origin.offset(x, floorY, z),
                            Blocks.DIRT.defaultBlockState()
                    );
                    for (int y = floorY + 1; y <= 4; y++) {
                        level.setBlockAndUpdate(
                                origin.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            placeTerrainObstacles(
                    level,
                    northSouthGroup,
                    geometryVariant
            );
            level.setBlockAndUpdate(
                    table,
                    Blocks.CRAFTING_TABLE.defaultBlockState()
            );
            for (BlockPos chestPos : chests) {
                level.setBlockAndUpdate(
                        chestPos,
                        Blocks.CHEST.defaultBlockState()
                );
                final var entity = level.getBlockEntity(chestPos);
                helper.assertTrue(
                        entity instanceof ChestBlockEntity,
                        "Container fixture lacked a chest block entity"
                );
                final ChestBlockEntity chest = (ChestBlockEntity) entity;
                chest.setItem(
                        0,
                        new ItemStack(wood, CHEST_START_COUNT)
                );
                chest.setChanged();
            }
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getEnderChestInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.broadcastChanges();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.setDeltaMovement(Vec3.ZERO);
            final int spawnOffset = 8;
            final int spawnFloor = terrainFloorOffset(
                    geometryVariant,
                    northSouthGroup,
                    northSouthGroup ? spawnOffset : 0,
                    northSouthGroup ? 0 : spawnOffset
            );
            player.teleportTo(
                    northSouthGroup
                        ? origin.getX() + spawnOffset + 0.5
                        : origin.getX() + 0.5,
                    origin.getY() + spawnFloor + 1.0,
                    northSouthGroup
                        ? origin.getZ() + 0.5
                        : origin.getZ() + spawnOffset + 0.5
            );
            player.setYRot(northSouthGroup ? 90.0F : 180.0F);
            player.setYHeadRot(northSouthGroup ? 90.0F : 180.0F);
            player.setXRot(20.0F);
        }

        /**
         * Ten deterministic land approaches. The workstation area itself
         * remains a legal vanilla work site; only the route from the body to
         * that site changes. This avoids testing an impossible request (for
         * example a chest buried inside terrain) while exercising ordinary
         * step-up, drop, detour and rolling-ground navigation.
         */
        private static int terrainFloorOffset(
                final int variant,
                final boolean northSouth,
                final int x,
                final int z
        ) {
            final int forward = northSouth ? x : z;
            final int lateral = northSouth ? z : x;
            if (forward <= 4) {
                return -1;
            }
            return switch (variant) {
                case 0 -> -1;
                case 1 -> 0;
                case 2 -> -2;
                case 3 -> lateral >= 0 ? 0 : -1;
                case 4 -> forward >= 7 ? 1 : 0;
                case 5 -> forward <= 6 ? -2 : -1;
                case 6 -> forward == 5 && Math.abs(lateral) <= 2
                        ? 0 : -1;
                case 7 -> Math.floorMod(forward + lateral, 3) == 0
                        ? -2 : -1;
                case 8 -> -1;
                case 9 -> forward >= 7 ? 0
                        : forward == 6 && Math.abs(lateral) <= 1
                            ? -2 : -1;
                default -> throw new IllegalArgumentException(
                        "Unknown terrain variant: " + variant
                );
            };
        }

        private void placeTerrainObstacles(
                final net.minecraft.server.level.ServerLevel level,
                final boolean northSouth,
                final int variant
        ) {
            if (variant != 8) {
                return;
            }
            for (int[] coordinate : List.of(
                    new int[]{6, 0},
                    new int[]{5, 1}
            )) {
                final int x = northSouth
                        ? coordinate[0] : coordinate[1];
                final int z = northSouth
                        ? coordinate[1] : coordinate[0];
                level.setBlockAndUpdate(
                        origin.offset(x, 0, z),
                        Blocks.COBBLESTONE.defaultBlockState()
                );
                level.setBlockAndUpdate(
                        origin.offset(x, 1, z),
                        Blocks.COBBLESTONE.defaultBlockState()
                );
            }
        }

        private void tick() {
            if (finished) {
                return;
            }
            helper.assertTrue(
                    helper.getTick() - createdAt
                            <= EXECUTION_TIMEOUT_TICKS,
                    "Container woodwork skill timed out: " + checkpoint()
                        + ", fixtureEvidence=" + fixtureEvidence()
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
                        false,
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
                            "Container woodwork start rejected: " + rejected
                    );
                    skill.start(context, NoParameters.INSTANCE);
                    started = true;
                }
                final SkillTickResult result = skill.tick(
                        context,
                        NoParameters.INSTANCE
                );
                if (result.status() == SkillTickResult.Status.FAILED) {
                    finished = true;
                    helper.assertTrue(
                            false,
                            "Container woodwork skill failed: "
                                    + result.failure()
                                    + ", checkpoint=" + checkpoint()
                                    + ", doorEvidence=" + doorEvidence()
                    );
                } else if (result.status()
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
            for (BlockPos chestPos : chests) {
                final ChestBlockEntity chest =
                        (ChestBlockEntity) helper.getLevel()
                                .getBlockEntity(chestPos);
                helper.assertTrue(
                        chest.getItem(0).is(wood)
                                && chest.getItem(0).getCount()
                                    == CHEST_START_COUNT - 1,
                        "A chest did not lose exactly one wood item: "
                                + chestPos + "=" + chest.getItem(0)
                );
            }
            helper.assertTrue(
                    player.getInventory().countItem(wood) == 0,
                    "Withdrawn wood was not fully crafted into planks"
            );
            helper.assertTrue(
                    crafted(planks) >= initialPlanksCrafted + 4,
                    "Four ordinary plank recipes were not completed"
            );
            helper.assertTrue(
                    crafted(door) > initialDoorsCrafted,
                    "The door did not come from an ordinary recipe"
            );
            helper.assertTrue(
                    helper.getLevel().getBlockState(expectedDoor)
                            .is(doorBlock),
                    "The matching door was not placed three blocks in front: "
                            + expectedDoor
            );
            helper.assertTrue(
                    completionEvidence.get(),
                    "Door placement completed without route evidence"
            );
            finished = true;
            helper.succeed();
        }

        private int crafted(final Item item) {
            return player.getStats().getValue(
                    net.minecraft.stats.Stats.ITEM_CRAFTED.get(item)
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
                            false,
                            true,
                            0.0
                    ),
                    NoParameters.INSTANCE
            ).payload();
        }

        private String doorEvidence() {
            final var frame = coreFrames.current().orElse(null);
            if (frame == null) {
                return "missing-frame";
            }
            final GridPos lower = new GridPos(
                    expectedDoor.getX(),
                    expectedDoor.getY(),
                    expectedDoor.getZ()
            );
            return "expected=" + expectedDoor
                    + ",body=" + frame.position()
                    + ",navRevision=" + frame.navigation().revision()
                    + ",lower=" + frame.navigation().voxelAt(lower)
                    + ",upper="
                    + frame.navigation().voxelAt(lower.above());
        }

        private String fixtureEvidence() {
            final var frame = coreFrames.current();
            if (frame.isEmpty()) {
                return "frame=missing,body=" + player.position();
            }
            final var current = frame.orElseThrow();
            return "body=" + current.position()
                    + ",look=" + current.lookDirection()
                    + ",navRevision=" + current.navigation().revision()
                    + ",visibleChests=" + current.visibleBlockFaces()
                        .stream()
                        .filter(face -> face.blockTypeId()
                                .equals("minecraft:chest"))
                        .map(face -> face.block() + "/" + face.face()
                                + "/" + face.distance())
                        .toList()
                    + ",expectedChests=" + chests;
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

    private static final class Scenario {
        private static final int EXECUTION_TIMEOUT_TICKS = 1_200;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerInteractionSkillFrameSource
                interactionFrames;
        private final ServerOwnedCoreSkillActuator core;
        private final PrepareBasicCraftingSkill skill;
        private final long createdAt;
        private final int initialCraftedTables;
        private final int initialCraftedPickaxes;

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
            createdAt = helper.getTick();
            prepareFixture();
            initialCraftedTables = player.getStats().getValue(
                    net.minecraft.stats.Stats.ITEM_CRAFTED.get(
                            Items.CRAFTING_TABLE
                    )
            );
            initialCraftedPickaxes = player.getStats().getValue(
                    net.minecraft.stats.Stats.ITEM_CRAFTED.get(
                            Items.WOODEN_PICKAXE
                    )
            );
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
            core = new ServerOwnedCoreSkillActuator(
                    server,
                    player.getUUID()
            );
            skill = new PrepareBasicCraftingSkill(
                    player.getUUID(),
                    core,
                    coreFrames,
                    new ServerOwnedInteractionSkillActuator(
                            server,
                            player.getUUID()
                    ),
                    interactionFrames,
                    new ServerInventorySkillActuator(
                            server,
                            player.getUUID()
                    )
            );
        }

        private void prepareFixture() {
            final var level = helper.getLevel();
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    level.setBlockAndUpdate(
                            origin.offset(x, -1, z),
                            Blocks.DIRT.defaultBlockState()
                    );
                    for (int y = 0; y <= 6; y++) {
                        level.setBlockAndUpdate(
                                origin.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            final BlockPos highColumn = origin.south(2);
            for (int y = -1; y <= 2; y++) {
                level.setBlockAndUpdate(
                        highColumn.above(y),
                        Blocks.STONE.defaultBlockState()
                );
            }
            /*
             * Match the real zero-human foundation fixture that exposed the
             * regression: nearby livestock occupies the most attractive
             * floor supports. A normal player sees the animals and chooses a
             * clear square instead of sending six placement packets into
             * entity collision and waiting for the whole skill deadline.
             */
            for (int index = 0; index < 8; index++) {
                final Cow cow = EntityTypes.COW.create(
                        level,
                        EntitySpawnReason.COMMAND
                );
                helper.assertTrue(
                        cow != null,
                        "Basic-crafting fixture could not create cow"
                );
                cow.setBaby(false);
                cow.setNoAi(true);
                final int column = index % 4;
                final int row = index / 4;
                cow.setPos(
                        origin.getX() - 3.0 + column * 1.75,
                        origin.getY(),
                        origin.getZ() + 1.0 + row * 1.75
                );
                helper.assertTrue(
                        level.addFreshEntity(cow),
                        "Basic-crafting fixture could not add cow"
                );
            }
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            player.setItemInHand(
                    InteractionHand.MAIN_HAND,
                    new ItemStack(Items.OAK_LOG, 3)
            );
            unlock("minecraft:oak_planks");
            unlock("minecraft:crafting_table");
            unlock("minecraft:stick");
            unlock("minecraft:wooden_pickaxe");
            player.inventoryMenu.broadcastChanges();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.setDeltaMovement(Vec3.ZERO);
            player.teleportTo(
                    origin.getX() + 0.5,
                    origin.getY(),
                    origin.getZ() + 0.5
            );
            player.setXRot(48.0F);
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
                    "Basic-crafting skill timed out: "
                            + checkpoint()
            );
            try {
                final var observation = sampler.sample(player);
                coreFrames.publish(observation);
                interactionFrames.publish(observation);
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
                            "Basic-crafting start rejected: " + rejected
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
                            "Basic-crafting skill failed: "
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
                    player.getInventory().countItem(
                            Items.WOODEN_PICKAXE
                    ) == 1,
                    "Basic crafting completed without an owned pickaxe"
            );
            helper.assertTrue(
                    player.getStats().getValue(
                            net.minecraft.stats.Stats.ITEM_CRAFTED.get(
                                    Items.CRAFTING_TABLE
                            )
                    ) > initialCraftedTables,
                    "Crafting table did not come from a vanilla recipe"
            );
            helper.assertTrue(
                    player.getStats().getValue(
                            net.minecraft.stats.Stats.ITEM_CRAFTED.get(
                                    Items.WOODEN_PICKAXE
                            )
                    ) > initialCraftedPickaxes,
                    "Wooden pickaxe did not come from a vanilla recipe"
            );
            final BlockPos table = BlockPos.betweenClosedStream(
                    origin.offset(-10, -1, -10),
                    origin.offset(10, 2, 10)
            ).filter(pos ->
                    helper.getLevel().getBlockState(pos)
                            .is(Blocks.CRAFTING_TABLE)
            ).findFirst().orElseThrow(() ->
                    helper.assertionException(
                            "No physical crafting table was placed"
                    )
            );
            helper.assertTrue(
                    Math.abs(table.getY() - origin.getY()) <= 1,
                    "Crafting table was placed on the high-column trap: "
                            + table
            );
            finished = true;
            helper.succeed();
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

    private static final class OccludedTableScenario {
        private static final int EXECUTION_TIMEOUT_TICKS = 2_000;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final BlockPos table;
        private final BlockPos furnace;
        private final Vec3 initialPosition;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerInteractionSkillFrameSource
                interactionFrames;
        private final ServerMenuSkillFrameSource menuFrames;
        private final ServerOwnedCoreSkillActuator core;
        private final PrepareIronToolkitSkill skill;
        private final long createdAt;
        private final int initialIronPickaxes;
        private final int initialBuckets;
        private final int initialShields;

        private boolean initialOcclusionVerified;
        private boolean started;
        private boolean finished;
        private boolean cleaned;

        private OccludedTableScenario(
                final GameTestHelper helper,
                final ServerPlayer player,
                final BlockPos origin
        ) {
            this.helper = helper;
            this.player = player;
            this.origin = origin.immutable();
            table = this.origin.immutable();
            furnace = this.origin.south();
            createdAt = helper.getTick();
            prepareFixture();
            initialPosition = player.position();
            initialIronPickaxes = crafted(Items.IRON_PICKAXE);
            initialBuckets = crafted(Items.BUCKET);
            initialShields = crafted(Items.SHIELD);
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
            final var interaction =
                    new ServerOwnedInteractionSkillActuator(
                            server,
                            player.getUUID()
                    );
            final var inventory =
                    new ServerInventorySkillActuator(
                            server,
                            player.getUUID()
                    );
            skill = new PrepareIronToolkitSkill(
                    player.getUUID(),
                    core,
                    coreFrames,
                    interaction,
                    interactionFrames,
                    inventory,
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
                    ignored -> Optional.of(location(furnace))
            );
        }

        private void prepareFixture() {
            final var level = helper.getLevel();
            for (int x = -7; x <= 7; x++) {
                for (int z = -7; z <= 7; z++) {
                    level.setBlockAndUpdate(
                            origin.offset(x, -1, z),
                            Blocks.DIRT.defaultBlockState()
                    );
                    for (int y = 0; y <= 4; y++) {
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
                    furnace,
                    Blocks.FURNACE.defaultBlockState()
            );
            level.setBlockAndUpdate(
                    furnace.west(),
                    Blocks.STONE.defaultBlockState()
            );
            level.setBlockAndUpdate(
                    furnace.east(),
                    Blocks.STONE.defaultBlockState()
            );
            for (BlockPos lower : List.of(
                    furnace.west(),
                    furnace,
                    furnace.east()
            )) {
                level.setBlockAndUpdate(
                        lower.above(),
                        Blocks.STONE.defaultBlockState()
                );
            }
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            player.setItemInHand(
                    InteractionHand.MAIN_HAND,
                    new ItemStack(Items.STONE_PICKAXE)
            );
            player.getInventory().setItem(
                    1,
                    new ItemStack(Items.IRON_INGOT, 7)
            );
            player.getInventory().setItem(
                    2,
                    new ItemStack(Items.STICK, 2)
            );
            player.getInventory().setItem(
                    3,
                    new ItemStack(Items.OAK_PLANKS, 6)
            );
            unlock("minecraft:iron_pickaxe");
            unlock("minecraft:bucket");
            unlock("minecraft:shield");
            player.inventoryMenu.broadcastChanges();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.setDeltaMovement(Vec3.ZERO);
            player.teleportTo(
                    origin.getX() + 0.5,
                    origin.getY(),
                    origin.getZ() + 3.5
            );
            player.setXRot(12.0F);
            player.setYRot(180.0F);
            player.setYHeadRot(180.0F);
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
                    "Occluded-table iron toolkit timed out: "
                            + checkpoint()
            );
            try {
                final var observation = sampler.sample(player);
                if (!initialOcclusionVerified) {
                    helper.assertTrue(
                            observation.visibleBlockFaces()
                                    .stream()
                                    .noneMatch(face ->
                                            face.blockTypeId().equals(
                                                    "minecraft:"
                                                            + "crafting_table"
                                            )
                                    ),
                            "Fixture did not initially occlude the table"
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
                            "Occluded-table start rejected: "
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
                            "Occluded-table skill failed: "
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
                    initialOcclusionVerified,
                    "Initial table occlusion was not verified"
            );
            helper.assertTrue(
                    player.position().distanceTo(initialPosition)
                            >= 1.0,
                    "Body never physically left the occluded stance"
            );
            helper.assertTrue(
                    player.getInventory().countItem(
                            Items.IRON_PICKAXE
                    ) == 1
                            && player.getInventory().countItem(
                                    Items.BUCKET
                            ) == 1
                            && player.getInventory().countItem(
                                    Items.SHIELD
                            ) == 1,
                    "Iron toolkit outputs are incomplete"
            );
            helper.assertTrue(
                    crafted(Items.IRON_PICKAXE)
                            > initialIronPickaxes
                            && crafted(Items.BUCKET)
                                > initialBuckets
                            && crafted(Items.SHIELD)
                                > initialShields,
                    "Toolkit outputs did not come from vanilla recipes"
            );
            helper.assertTrue(
                    helper.getLevel().getBlockState(table)
                            .is(Blocks.CRAFTING_TABLE)
                            && helper.getLevel()
                                .getBlockState(furnace)
                                .is(Blocks.FURNACE),
                    "Fixture blocks changed during recovery"
            );
            finished = true;
            helper.succeed();
        }

        private int crafted(
                final net.minecraft.world.item.Item item
        ) {
            return player.getStats().getValue(
                    net.minecraft.stats.Stats.ITEM_CRAFTED.get(item)
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
