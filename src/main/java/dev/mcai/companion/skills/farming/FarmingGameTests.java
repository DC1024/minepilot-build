package dev.mcai.companion.skills.farming;

import dev.mcai.companion.action.AcceptedLowLevelAction;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.GameTestCompanionSpawn;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.mechanism.AsyncHydratedCropFieldPlanService;
import dev.mcai.companion.mechanism.CropFieldVariant;
import dev.mcai.companion.perception.FairPerceptionSampler;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.runtime.CompanionRuntime;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.ServerCoreSkillFrameSource;
import dev.mcai.companion.skills.core.ServerOwnedCoreSkillActuator;
import dev.mcai.companion.skills.building.ServerShelterFrameSource;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction
        .ServerInteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction
        .ServerOwnedInteractionSkillActuator;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.BeetrootBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/**
 * Release-excluded real-Forge contracts for production farming skills.
 * Fixture setup is complete before a skill starts; every asserted mutation
 * after that boundary must come from the ordinary headless player path.
 */
public final class FarmingGameTests {
    private static final BlockPos TEST_ORIGIN =
            new BlockPos(16, 8, 16);
    private static final int BODY_START_TIMEOUT_TICKS = 3_000;

    private FarmingGameTests() {
    }

    /**
     * Proves that one fairly observed dirt surface is tilled and planted by
     * the production skill through normal player packets. The final block
     * states alone are insufficient: the gate also requires hoe durability,
     * seed consumption, and both accepted block-use actions.
     */
    public static void realPrepareAndPlantPlot(
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
                "Farming gate requires an isolated companion body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Farming companion spawn was rejected"
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
                    "Farming companion body failed to spawn"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Farming companion did not become active"
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
     * Proves the generated field's central-water primitive with an ordinary
     * shovel break followed by ordinary water-bucket placement.
     */
    public static void realPrepareWaterSource(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicReference<WaterScenario> scenario =
                new AtomicReference<>();
        final long startedAt = helper.getTick();

        helper.addCleanup(ignored -> {
            final WaterScenario current = scenario.get();
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
                "Water-source gate requires an isolated companion body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Water-source companion spawn was rejected"
        );

        helper.onEachTick(() -> {
            final WaterScenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Water-source companion body failed to spawn"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Water-source companion did not become active"
                );
                return;
            }
            scenario.set(new WaterScenario(
                    helper,
                    AiPlayerManager.onlinePlayer(server).orElseThrow(),
                    helper.absolutePos(TEST_ORIGIN)
            ));
        });
    }

    /**
     * Proves the observation-bound sugar-cane atom against a real Forge
     * ServerPlayer. The fixture only provides one support, one adjacent water
     * block and owned sugar cane; the skill must perform the ordinary item
     * swap/block-use path and confirm the resulting plant.
     */
    public static void realPlantObservedSugarcane(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicReference<SugarcaneScenario> scenario =
                new AtomicReference<>();
        final long startedAt = helper.getTick();
        helper.addCleanup(ignored -> {
            final SugarcaneScenario current = scenario.get();
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
                "Sugar-cane gate requires an isolated companion body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(helper, TEST_ORIGIN)
                        .accepted(),
                "Sugar-cane companion spawn was rejected"
        );
        helper.onEachTick(() -> {
            final SugarcaneScenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Sugar-cane companion body failed to spawn"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Sugar-cane companion did not become active"
                );
                return;
            }
            scenario.set(new SugarcaneScenario(
                    helper,
                    AiPlayerManager.onlinePlayer(server).orElseThrow(),
                    helper.absolutePos(TEST_ORIGIN)
            ));
        });
    }

    /**
     * Runs the whole coordinate-free 3x3 field task against a real headless
     * player. Test code prepares only the initial flat site and materials;
     * after the skill boundary it is read-only and scores ordinary actions.
     */
    public static void realBuildHydratedCropField(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicReference<FieldScenario> scenario =
                new AtomicReference<>();
        final long startedAt = helper.getTick();

        helper.addCleanup(ignored -> {
            final FieldScenario current = scenario.get();
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
                "Whole-field gate requires an isolated companion body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Whole-field companion spawn was rejected"
        );

        helper.onEachTick(() -> {
            final FieldScenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Whole-field companion body failed to spawn"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Whole-field companion did not become active"
                );
                return;
            }
            scenario.set(new FieldScenario(
                    helper,
                    AiPlayerManager.onlinePlayer(server).orElseThrow(),
                    helper.absolutePos(TEST_ORIGIN)
            ));
        });
    }

    /**
     * Proves a coordinate-free whole-field maintenance pass.  The fixture
     * creates a mature hydrated field before the boundary; afterwards the
     * production skill must survey, walk, harvest, collect, replant, and
     * verify every selected cell through the ordinary player path.
     */
    public static void realMaintainObservedCropField(
            final GameTestHelper helper
    ) {
        realMaintainObservedCropField(
                helper,
                MaintenanceFixture.compact(CropFieldVariant.WHEAT)
        );
    }

    /**
     * Exercises the same coordinate-free field workflow with a root crop
     * whose planting item is also its harvested output. This catches wheat-
     * only inventory accounting and crop-state assumptions.
     */
    public static void realMaintainObservedCarrotField(
            final GameTestHelper helper
    ) {
        realMaintainObservedCropField(
                helper,
                MaintenanceFixture.compact(CropFieldVariant.CARROT)
        );
    }

    public static void realMaintainObservedPotatoField(
            final GameTestHelper helper
    ) {
        realMaintainObservedCropField(
                helper,
                MaintenanceFixture.compact(CropFieldVariant.POTATO)
        );
    }

    public static void realMaintainObservedBeetrootField(
            final GameTestHelper helper
    ) {
        realMaintainObservedCropField(
                helper,
                MaintenanceFixture.compact(CropFieldVariant.BEETROOT)
        );
    }

    public static void realMaintainObservedExpandedField(
            final GameTestHelper helper
    ) {
        realMaintainObservedCropField(
                helper,
                MaintenanceFixture.expandedWheat()
        );
    }

    private static void realMaintainObservedCropField(
            final GameTestHelper helper,
            final MaintenanceFixture fixture
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicReference<MaintenanceScenario> scenario =
                new AtomicReference<>();
        final long startedAt = helper.getTick();

        helper.addCleanup(ignored -> {
            final MaintenanceScenario current = scenario.get();
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
                "Crop-maintenance gate requires an isolated companion body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Crop-maintenance companion spawn was rejected"
        );

        helper.onEachTick(() -> {
            final MaintenanceScenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Crop-maintenance companion body failed to spawn"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Crop-maintenance companion did not become active"
                );
                return;
            }
            scenario.set(new MaintenanceScenario(
                    helper,
                    AiPlayerManager.onlinePlayer(server).orElseThrow(),
                    helper.absolutePos(TEST_ORIGIN),
                    fixture
            ));
        });
    }

    private record MaintenanceFixture(
            CropFieldVariant crop,
            List<BlockPos> cropOffsets,
            BlockPos waterOffset,
            double spawnOffsetX,
            double spawnOffsetZ,
            int maximumPlants,
            int skillWindowTicks
    ) {
        private MaintenanceFixture {
            cropOffsets = List.copyOf(cropOffsets);
            if (cropOffsets.size() != maximumPlants
                    || maximumPlants < 1
                    || maximumPlants > 80
                    || cropOffsets.contains(waterOffset)
                    || cropOffsets.stream().distinct().count()
                            != cropOffsets.size()
                    || skillWindowTicks < 1_000) {
                throw new IllegalArgumentException(
                        "Invalid crop-maintenance fixture"
                );
            }
        }

        private static MaintenanceFixture compact(
                final CropFieldVariant crop
        ) {
            final BlockPos water = BlockPos.ZERO;
            return new MaintenanceFixture(
                    crop,
                    rectangle(-1, 1, -1, 1, water),
                    water,
                    0.5,
                    4.5,
                    8,
                    7_000
            );
        }

        private static MaintenanceFixture expandedWheat() {
            /* Deliberately offset water breaks the compact radial symmetry. */
            final BlockPos water = new BlockPos(-1, 0, -1);
            return new MaintenanceFixture(
                    CropFieldVariant.WHEAT,
                    rectangle(-2, 1, -2, 1, water),
                    water,
                    0.5,
                    5.5,
                    15,
                    14_000
            );
        }

        private static List<BlockPos> rectangle(
                final int minimumX,
                final int maximumX,
                final int minimumZ,
                final int maximumZ,
                final BlockPos water
        ) {
            final List<BlockPos> result = new ArrayList<>();
            for (int x = minimumX; x <= maximumX; x++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    final BlockPos offset = new BlockPos(x, 0, z);
                    if (!offset.equals(water)) {
                        result.add(offset);
                    }
                }
            }
            return List.copyOf(result);
        }
    }

    private static final class SugarcaneScenario {
        private static final int START_WINDOW_TICKS = 200;
        private static final int SKILL_WINDOW_TICKS = 500;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final BlockPos support;
        private final BlockPos water;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerInteractionSkillFrameSource interactionFrames;
        private final ServerOwnedCoreSkillActuator core;
        private final ServerOwnedInteractionSkillActuator interactions;
        private final List<AcceptedLowLevelAction> actionAudit =
                new ArrayList<>();
        private final long createdAt;
        private final int initialCaneCount;
        private PlantObservedSugarcaneSkill skill;
        private PlantObservedSugarcaneParameters parameters;
        private long skillStartedAt = -1;
        private boolean finished;
        private boolean cleaned;

        private SugarcaneScenario(
                final GameTestHelper helper,
                final ServerPlayer player,
                final BlockPos origin
        ) {
            this.helper = helper;
            this.player = player;
            this.origin = origin.immutable();
            support = this.origin.south(2).below();
            water = support.east();
            createdAt = helper.getTick();
            prepareFixture();
            initialCaneCount = player.getInventory().countItem(
                    Items.SUGAR_CANE
            );
            final var server = helper.getLevel().getServer();
            coreFrames = new ServerCoreSkillFrameSource(
                    server,
                    player.getUUID()
            );
            interactionFrames = new ServerInteractionSkillFrameSource(
                    server,
                    player.getUUID()
            );
            core = CompanionRuntime.active()
                    .filter(runtime -> runtime.server() == server)
                    .orElseThrow(() -> new IllegalStateException(
                            "Sugar-cane runtime is not active"
                    ))
                    .coreActions();
            interactions = new ServerOwnedInteractionSkillActuator(
                    server,
                    player.getUUID(),
                    null,
                    actionAudit::add
            );
        }

        private void prepareFixture() {
            final var level = helper.getLevel();
            for (int x = -4; x <= 4; x++) {
                for (int z = -3; z <= 5; z++) {
                    level.setBlockAndUpdate(
                            origin.offset(x, -1, z),
                            Blocks.DIRT.defaultBlockState()
                    );
                    for (int y = 0; y <= 3; y++) {
                        level.setBlockAndUpdate(
                                origin.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            // Sand is gravity-affected in the real world.  Give the observed
            // support a solid foundation so the fixture does not disappear
            // between the observation and the ordinary use packet.
            level.setBlockAndUpdate(
                    support.below(),
                    Blocks.DIRT.defaultBlockState()
            );
            level.setBlockAndUpdate(support, Blocks.SAND.defaultBlockState());
            // Keep the observed support as a real, ordinary first-person
            // interaction target.  Neighbouring floor blocks at the same
            // height can win vanilla's OUTLINE clip on the shared edge even
            // when semantic perception reports the sand face.  Clear only
            // the three non-water neighbours; the floor below remains intact.
            level.setBlockAndUpdate(support.north(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(support.south(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(support.west(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(
                    support.above(),
                    Blocks.AIR.defaultBlockState()
            );
            level.setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
            player.stopRiding();
            if (player.isSleeping()) {
                player.stopSleepInBed(true, false);
            }
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getEnderChestInventory().clearContent();
            player.removeAllEffects();
            player.clearFire();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            player.getInventory().setItem(0, new ItemStack(Items.STONE));
            player.getInventory().setItem(
                    1,
                    new ItemStack(Items.SUGAR_CANE, 2)
            );
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.broadcastChanges();
            player.teleportTo(
                    origin.getX() + 0.5,
                    origin.getY(),
                    origin.getZ() + 0.5
            );
            lookAtSupport();
        }

        private void tick() {
            if (finished) {
                return;
            }
            try {
                if (skill == null) {
                    tryStart();
                } else {
                    tickSkill();
                }
            } finally {
                core.postServerTick();
            }
        }

        private void tryStart() {
            lookAtSupport();
            final SemanticObservation observation = publish();
            final Optional<VisibleBlockFace> observed = observation
                    .visibleBlockFaces()
                    .stream()
                    .filter(face -> face.block().x() == support.getX()
                            && face.block().y() == support.getY()
                            && face.block().z() == support.getZ()
                            && "up".equals(face.face())
                            && "minecraft:sand".equals(
                                    face.blockTypeId()
                            ))
                    .findFirst();
            if (observed.isEmpty()) {
                helper.assertTrue(
                        helper.getTick() - createdAt <= START_WINDOW_TICKS,
                        "Sugar-cane support never entered fair first-person view: "
                                + observation.visibleBlockFaces()
                );
                return;
            }
            parameters = new PlantObservedSugarcaneParameters(
                    DimensionRef.OVERWORLD,
                    new ObservedBlockTarget(
                            observation.sequence(),
                            support.getX(),
                            support.getY(),
                            support.getZ(),
                            BlockFace.UP
                    )
            );
            final PlantObservedSugarcaneSkill candidate =
                    new PlantObservedSugarcaneSkill(
                            player.getUUID(),
                            core,
                            coreFrames,
                            interactions,
                            interactionFrames,
                            FarmingSkillPolicy.defaults()
                    );
            final SkillContext context = context(observation);
            helper.assertTrue(
                    candidate.preconditions(context, parameters).isEmpty(),
                    "Sugar-cane preconditions rejected: "
                            + candidate.preconditions(context, parameters)
            );
            candidate.start(context, parameters);
            helper.assertTrue(
                    candidate.checkpoint(context, parameters).payload()
                            .contains("\"phase\":\"AIMING\""),
                    "Sugar-cane skill did not enter AIMING after start"
            );
            skill = candidate;
            skillStartedAt = helper.getTick();
        }

        private void tickSkill() {
            final SemanticObservation observation = publish();
            final SkillTickResult result = skill.tick(
                    context(observation),
                    parameters
            );
            helper.assertTrue(
                    result.status() != SkillTickResult.Status.FAILED,
                    "Real sugar-cane plant failed: "
                            + result.failure().map(SkillFailure::code)
                                    .orElse("unknown")
            );
            if (result.status() != SkillTickResult.Status.COMPLETED) {
                helper.assertTrue(
                        helper.getTick() - skillStartedAt
                                <= SKILL_WINDOW_TICKS,
                        "Real sugar-cane plant exceeded its bounded window"
                );
                return;
            }
            helper.assertTrue(
                    helper.getLevel().getBlockState(support.above())
                            .is(Blocks.SUGAR_CANE),
                    "Skill completed without physically planting sugar cane"
            );
            helper.assertTrue(
                    helper.getLevel().getBlockState(water).is(Blocks.WATER),
                    "Adjacent sugar-cane water disappeared"
            );
            helper.assertTrue(
                    player.getInventory().countItem(Items.SUGAR_CANE)
                            == initialCaneCount - 1,
                    "Planting did not consume exactly one owned sugar cane"
            );
            final long uses = actionAudit.stream()
                    .filter(action -> "use_on_block".equals(
                            action.action()
                    ))
                    .count();
            helper.assertTrue(
                    uses == 1,
                    "Expected one ordinary sugar-cane use, got "
                            + actionAudit
            );
            finished = true;
            helper.succeed();
        }

        private SemanticObservation publish() {
            final SemanticObservation observation = sampler.sample(player);
            coreFrames.publish(observation);
            interactionFrames.publish(observation);
            return observation;
        }

        private SkillContext context(
                final SemanticObservation observation
        ) {
            return new SkillContext(
                    1,
                    observation.sequence(),
                    helper.getLevel().getGameTime(),
                    true,
                    true,
                    0.0
            );
        }

        private void lookAtSupport() {
            player.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(support).add(0.0, 0.5, 0.0)
            );
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            if (!finished && skill != null && parameters != null) {
                skill.cancel(
                        context(publish()),
                        parameters
                );
            }
            core.quiesceNow();
            interactions.quiesceNow();
        }
    }

    private static final class Scenario {
        private static final int START_WINDOW_TICKS = 160;
        private static final int SKILL_WINDOW_TICKS = 400;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final BlockPos ground;
        private final BlockPos water;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerInteractionSkillFrameSource
                interactionFrames;
        private final ServerOwnedCoreSkillActuator core;
        private final ServerOwnedInteractionSkillActuator interactions;
        private final List<AcceptedLowLevelAction> actionAudit =
                new ArrayList<>();
        private final long createdAt;
        private final int initialSeedCount;
        private final int initialHoeDamage;

        private PrepareAndPlantPlotSkill skill;
        private PrepareAndPlantPlotParameters parameters;
        private long skillStartedAt = -1;
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
            ground = this.origin.south(2).below();
            water = ground.east();
            createdAt = helper.getTick();
            prepareFixture();
            initialSeedCount = player.getInventory().countItem(
                    Items.WHEAT_SEEDS
            );
            initialHoeDamage = ownedHoeDamage();

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
            interactions = new ServerOwnedInteractionSkillActuator(
                    server,
                    player.getUUID(),
                    null,
                    actionAudit::add
            );
        }

        private void prepareFixture() {
            final var level = helper.getLevel();
            for (int x = -5; x <= 5; x++) {
                for (int z = -4; z <= 6; z++) {
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
                    ground,
                    Blocks.DIRT.defaultBlockState()
            );
            level.setBlockAndUpdate(
                    ground.above(),
                    Blocks.AIR.defaultBlockState()
            );
            level.setBlockAndUpdate(
                    water,
                    Blocks.WATER.defaultBlockState()
            );

            player.stopRiding();
            if (player.isSleeping()) {
                player.stopSleepInBed(true, false);
            }
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getEnderChestInventory().clearContent();
            player.removeAllEffects();
            player.clearFire();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            player.getInventory().setItem(
                    0,
                    new ItemStack(Items.STONE_HOE)
            );
            player.getInventory().setItem(
                    1,
                    new ItemStack(Items.WHEAT_SEEDS, 4)
            );
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.broadcastChanges();
            player.teleportTo(
                    origin.getX() + 0.5,
                    origin.getY(),
                    origin.getZ() + 0.5
            );
            lookAtGround();
        }

        private void tick() {
            if (finished) {
                return;
            }
            try {
                if (skill == null) {
                    tryStart();
                } else {
                    tickSkill();
                }
            } finally {
                core.postServerTick();
            }
        }

        private void tryStart() {
            lookAtGround();
            final SemanticObservation observation = publish();
            final Optional<VisibleBlockFace> observedGround =
                    observedGround(observation);
            if (observedGround.isEmpty()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                                <= START_WINDOW_TICKS,
                        "Dirt plot never entered fair first-person view: "
                                + observation.visibleBlockFaces()
                );
                return;
            }
            final VisibleBlockFace face = observedGround.orElseThrow();
            parameters = new PrepareAndPlantPlotParameters(
                    DimensionRef.OVERWORLD,
                    CropKind.WHEAT,
                    new ObservedBlockTarget(
                            observation.sequence(),
                            ground.getX(),
                            ground.getY(),
                            ground.getZ(),
                            dev.mcai.companion.action.BlockFace.UP
                    )
            );
            final PrepareAndPlantPlotSkill candidate =
                    new PrepareAndPlantPlotSkill(
                            player.getUUID(),
                            core,
                            coreFrames,
                            interactions,
                            interactionFrames,
                            FarmingSkillPolicy.defaults()
                    );
            final SkillContext context = context(observation);
            helper.assertTrue(
                    "minecraft:dirt".equals(face.blockTypeId()),
                    "Farming fixture changed before the skill boundary"
            );
            helper.assertTrue(
                    candidate.preconditions(context, parameters).isEmpty(),
                    "Real prepare-and-plant preconditions were rejected: "
                            + candidate.preconditions(
                                    context,
                                    parameters
                            )
            );
            candidate.start(context, parameters);
            skill = candidate;
            skillStartedAt = helper.getTick();
        }

        private void tickSkill() {
            final SemanticObservation observation = publish();
            final SkillTickResult result = skill.tick(
                    context(observation),
                    parameters
            );
            helper.assertTrue(
                    result.status() != SkillTickResult.Status.FAILED,
                    "Real prepare-and-plant failed: "
                            + result.failure()
                                    .map(failure -> failure.code())
                                    .orElse("unknown")
                            + ", checkpoint="
                            + skill.checkpoint(
                                    context(observation),
                                    parameters
                            ).payload()
            );
            if (result.status()
                    != SkillTickResult.Status.COMPLETED) {
                helper.assertTrue(
                        helper.getTick() - skillStartedAt
                                <= SKILL_WINDOW_TICKS,
                        "Real prepare-and-plant exceeded its bounded window"
                );
                return;
            }
            verifyCompletion();
        }

        private void verifyCompletion() {
            final var level = helper.getLevel();
            helper.assertTrue(
                    level.getBlockState(ground).is(Blocks.FARMLAND),
                    "Skill completed without physically tilling the dirt"
            );
            helper.assertTrue(
                    level.getBlockState(ground.above()).is(Blocks.WHEAT)
                            && level.getBlockState(ground.above())
                                    .getValue(CropBlock.AGE) == 0,
                    "Skill completed without a newly planted wheat crop"
            );
            helper.assertTrue(
                    level.getBlockState(water).is(Blocks.WATER),
                    "Adjacent hydration source disappeared during farming"
            );
            helper.assertTrue(
                    player.getInventory().countItem(Items.WHEAT_SEEDS)
                            == initialSeedCount - 1,
                    "Planting did not consume exactly one owned seed"
            );
            helper.assertTrue(
                    ownedHoeDamage() == initialHoeDamage + 1,
                    "Tilling did not apply ordinary hoe durability"
            );
            final long uses = actionAudit.stream()
                    .filter(action -> "use_on_block".equals(
                            action.action()
                    ))
                    .count();
            helper.assertTrue(
                    uses == 2,
                    "Expected one ordinary till and one plant action, got "
                            + actionAudit
            );
            finished = true;
            helper.succeed();
        }

        private SemanticObservation publish() {
            final SemanticObservation observation =
                    sampler.sample(player);
            coreFrames.publish(observation);
            interactionFrames.publish(observation);
            return observation;
        }

        private Optional<VisibleBlockFace> observedGround(
                final SemanticObservation observation
        ) {
            return observation.visibleBlockFaces().stream()
                    .filter(face -> face.block().x() == ground.getX()
                            && face.block().y() == ground.getY()
                            && face.block().z() == ground.getZ()
                            && "up".equals(face.face()))
                    .findFirst();
        }

        private SkillContext context(
                final SemanticObservation observation
        ) {
            return new SkillContext(
                    1,
                    observation.sequence(),
                    helper.getLevel().getGameTime(),
                    true,
                    true,
                    0.0
            );
        }

        private void lookAtGround() {
            player.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(ground).add(0.0, 0.5, 0.0)
            );
        }

        private int ownedHoeDamage() {
            for (int slot = 0;
                    slot < player.getInventory().getContainerSize();
                    slot++) {
                final ItemStack stack =
                        player.getInventory().getItem(slot);
                if (stack.is(Items.STONE_HOE)) {
                    return stack.getDamageValue();
                }
            }
            return -1;
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            if (!finished && skill != null && parameters != null) {
                skill.cancel(
                        new SkillContext(
                                1,
                                0,
                                helper.getLevel().getGameTime(),
                                true,
                                true,
                                0.0
                        ),
                        parameters
                );
            }
            core.quiesceNow();
            interactions.quiesceNow();
        }
    }

    private static final class WaterScenario {
        private static final int START_WINDOW_TICKS = 160;
        private static final int SKILL_WINDOW_TICKS = 500;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final BlockPos ground;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerInteractionSkillFrameSource
                interactionFrames;
        private final ServerOwnedCoreSkillActuator core;
        private final ServerOwnedInteractionSkillActuator interactions;
        private final List<AcceptedLowLevelAction> actionAudit =
                new ArrayList<>();
        private final long createdAt;
        private final int initialShovelDamage;

        private PrepareWaterSourceSkill skill;
        private PrepareWaterSourceParameters parameters;
        private long skillStartedAt = -1;
        private boolean finished;
        private boolean cleaned;

        private WaterScenario(
                final GameTestHelper helper,
                final ServerPlayer player,
                final BlockPos origin
        ) {
            this.helper = helper;
            this.player = player;
            this.origin = origin.immutable();
            /*
             * The atomic skill expects a player-reachable work face. Keep the
             * body on the adjacent support so the newly exposed pit floor is
             * selectable after excavation; the compound field executor owns
             * travel between plots.
             */
            ground = this.origin.south().below();
            createdAt = helper.getTick();
            prepareFixture();
            initialShovelDamage = ownedShovelDamage();

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
            interactions = new ServerOwnedInteractionSkillActuator(
                    server,
                    player.getUUID(),
                    null,
                    actionAudit::add
            );
        }

        private void prepareFixture() {
            final var level = helper.getLevel();
            for (int x = -5; x <= 5; x++) {
                for (int z = -4; z <= 6; z++) {
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
                    ground.below(),
                    Blocks.STONE.defaultBlockState()
            );
            level.setBlockAndUpdate(
                    ground,
                    Blocks.DIRT.defaultBlockState()
            );
            level.setBlockAndUpdate(
                    ground.above(),
                    Blocks.AIR.defaultBlockState()
            );

            player.stopRiding();
            if (player.isSleeping()) {
                player.stopSleepInBed(true, false);
            }
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getEnderChestInventory().clearContent();
            player.removeAllEffects();
            player.clearFire();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            player.getInventory().setItem(
                    0,
                    new ItemStack(Items.STONE_SHOVEL)
            );
            player.getInventory().setItem(
                    1,
                    new ItemStack(Items.WATER_BUCKET)
            );
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.broadcastChanges();
            player.teleportTo(
                    origin.getX() + 0.5,
                    origin.getY(),
                    origin.getZ() + 0.5
            );
            lookAtGround();
        }

        private void tick() {
            if (finished) {
                return;
            }
            try {
                if (skill == null) {
                    tryStart();
                } else {
                    tickSkill();
                }
            } finally {
                core.postServerTick();
            }
        }

        private void tryStart() {
            lookAtGround();
            final SemanticObservation observation = publish();
            final Optional<VisibleBlockFace> observed =
                    observedGround(observation);
            if (observed.isEmpty()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                                <= START_WINDOW_TICKS,
                        "Water-source dirt never entered fair view: "
                                + observation.visibleBlockFaces()
                );
                return;
            }
            parameters = new PrepareWaterSourceParameters(
                    DimensionRef.OVERWORLD,
                    new ObservedBlockTarget(
                            observation.sequence(),
                            ground.getX(),
                            ground.getY(),
                            ground.getZ(),
                            dev.mcai.companion.action.BlockFace.UP
                    )
            );
            final PrepareWaterSourceSkill candidate =
                    new PrepareWaterSourceSkill(
                            player.getUUID(),
                            core,
                            coreFrames,
                            interactions,
                            interactionFrames,
                            FarmingSkillPolicy.defaults()
                    );
            final SkillContext context = context(observation);
            final var rejected = candidate.preconditions(
                    context,
                    parameters
            );
            helper.assertTrue(
                    rejected.isEmpty(),
                    "Real water-source preconditions were rejected: "
                            + rejected
            );
            candidate.start(context, parameters);
            skill = candidate;
            skillStartedAt = helper.getTick();
        }

        private void tickSkill() {
            final SemanticObservation observation = publish();
            final SkillTickResult result = skill.tick(
                    context(observation),
                    parameters
            );
            helper.assertTrue(
                    result.status() != SkillTickResult.Status.FAILED,
                    "Real water-source skill failed: "
                            + result.failure()
                                    .map(failure -> failure.code())
                                    .orElse("unknown")
                            + ", checkpoint="
                            + skill.checkpoint(
                                    context(observation),
                                    parameters
                            ).payload()
                            + ", visible="
                            + observation.visibleBlockFaces()
            );
            if (result.status()
                    != SkillTickResult.Status.COMPLETED) {
                helper.assertTrue(
                        helper.getTick() - skillStartedAt
                                <= SKILL_WINDOW_TICKS,
                        "Real water-source skill exceeded its window"
                );
                return;
            }
            verifyCompletion();
        }

        private void verifyCompletion() {
            helper.assertTrue(
                    helper.getLevel().getBlockState(ground)
                            .is(Blocks.WATER),
                    "Skill completed without a physical water source"
            );
            helper.assertTrue(
                    helper.getLevel().getBlockState(ground.below())
                            .is(Blocks.STONE),
                    "Water-source excavation broke through its floor"
            );
            helper.assertTrue(
                    player.getInventory().countItem(Items.WATER_BUCKET)
                            == 0
                            && player.getInventory().countItem(Items.BUCKET)
                                    == 1,
                    "Water placement did not produce one ordinary bucket"
            );
            helper.assertTrue(
                    ownedShovelDamage() == initialShovelDamage + 1,
                    "Excavation did not apply ordinary shovel durability"
            );
            final long miningStarts = countAction("begin_mining");
            final long waterUses = countAction("use_item");
            helper.assertTrue(
                    miningStarts == 1 && waterUses == 1,
                    "Expected one excavation and one bucket use: "
                            + actionAudit
            );
            finished = true;
            helper.succeed();
        }

        private long countAction(final String actionName) {
            return actionAudit.stream().filter(action ->
                    actionName.equals(action.action())
            ).count();
        }

        private SemanticObservation publish() {
            final SemanticObservation observation =
                    sampler.sample(player);
            coreFrames.publish(observation);
            interactionFrames.publish(observation);
            return observation;
        }

        private Optional<VisibleBlockFace> observedGround(
                final SemanticObservation observation
        ) {
            return observation.visibleBlockFaces().stream()
                    .filter(face -> face.block().x() == ground.getX()
                            && face.block().y() == ground.getY()
                            && face.block().z() == ground.getZ()
                            && "up".equals(face.face())
                            && "minecraft:dirt".equals(
                                    face.blockTypeId()
                            ))
                    .findFirst();
        }

        private SkillContext context(
                final SemanticObservation observation
        ) {
            return new SkillContext(
                    1,
                    observation.sequence(),
                    helper.getLevel().getGameTime(),
                    true,
                    true,
                    0.0
            );
        }

        private void lookAtGround() {
            player.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(ground).add(0.0, 0.5, 0.0)
            );
        }

        private int ownedShovelDamage() {
            for (int slot = 0;
                    slot < player.getInventory().getContainerSize();
                    slot++) {
                final ItemStack stack =
                        player.getInventory().getItem(slot);
                if (stack.is(Items.STONE_SHOVEL)) {
                    return stack.getDamageValue();
                }
            }
            return -1;
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            if (!finished && skill != null && parameters != null) {
                skill.cancel(
                        new SkillContext(
                                1,
                                0,
                                helper.getLevel().getGameTime(),
                                true,
                                true,
                                0.0
                        ),
                        parameters
                );
            }
            core.quiesceNow();
            interactions.quiesceNow();
        }
    }

    private static final class MaintenanceScenario {
        private static final int START_WINDOW_TICKS = 240;
        private static final int SEMANTIC_INTERVAL_TICKS = 5;
        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final MaintenanceFixture fixture;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerInteractionSkillFrameSource interactionFrames;
        private final ServerShelterFrameSource shelterFrames;
        private final ServerOwnedCoreSkillActuator core;
        private final ServerOwnedInteractionSkillActuator interactionDelegate;
        private final InteractionSkillActuator interactions;
        private final AsyncHydratedCropFieldPlanService plans =
                new AsyncHydratedCropFieldPlanService();
        private final List<AcceptedLowLevelAction> actionAudit =
                new ArrayList<>();
        private final List<String> interactionTargetTrace =
                new ArrayList<>();
        private final List<String> visibleItemTrace = new ArrayList<>();
        private final List<ServerOwnedCoreSkillActuator.AcceptedAction>
                coreActionAudit = new ArrayList<>();
        private final long createdAt;
        private final int initialRandomTickSpeed;
        private final int initialHarvestItemCount;

        private MaintainObservedCropFieldSkill skill;
        private MaintainObservedCropFieldParameters parameters;
        private long skillStartedAt = -1;
        private long lastSemanticTick = Long.MIN_VALUE;
        private SemanticObservation latestObservation;
        private final List<String> positionTrace = new ArrayList<>();
        /* Test-only transaction trace: root crops share their harvested item
         * with their planting item, so action counts alone cannot prove that
         * the vanilla use packet left a plant at the intended cell. */
        private final List<String> completionBlockTrace = new ArrayList<>();
        private long tracedUseOnBlockActions;
        private boolean finished;
        private boolean cleaned;

        private MaintenanceScenario(
                final GameTestHelper helper,
                final ServerPlayer player,
                final BlockPos origin,
                final MaintenanceFixture fixture
        ) {
            this.helper = helper;
            this.player = player;
            this.origin = origin.immutable();
            this.fixture = fixture;
            createdAt = helper.getTick();
            initialRandomTickSpeed = helper.getLevel().getGameRules()
                    .get(GameRules.RANDOM_TICK_SPEED);
            prepareFixture();
            initialHarvestItemCount = player.getInventory().countItem(
                    harvestItem(fixture.crop())
            );

            final var server = helper.getLevel().getServer();
            coreFrames = new ServerCoreSkillFrameSource(
                    server,
                    player.getUUID()
            );
            interactionFrames = new ServerInteractionSkillFrameSource(
                    server,
                    player.getUUID()
            );
            shelterFrames = new ServerShelterFrameSource(
                    server,
                    player.getUUID()
            );
            core = new ServerOwnedCoreSkillActuator(
                    server,
                    player.getUUID(),
                    coreActionAudit::add
            );
            interactionDelegate = new ServerOwnedInteractionSkillActuator(
                            server,
                            player.getUUID(),
                            null,
                            actionAudit::add
                    );
            interactions = (InteractionSkillActuator) Proxy
                    .newProxyInstance(
                            InteractionSkillActuator.class.getClassLoader(),
                            new Class<?>[] {InteractionSkillActuator.class},
                            (proxy, method, args) -> {
                                if ((method.getName().equals("beginMining")
                                        || method.getName().equals(
                                            "useOnBlock"))
                                        && args != null
                                        && args.length > 0) {
                                    Object target = args[args.length - 1];
                                    interactionTargetTrace.add(
                                            method.getName() + "=" + target
                                    );
                                }
                                try {
                                    return method.invoke(
                                            interactionDelegate,
                                            args
                                    );
                                } catch (InvocationTargetException exception) {
                                    throw exception.getCause();
                                }
                            }
                    );
        }

        private void prepareFixture() {
            final var level = helper.getLevel();
            helper.setTime(6_000L);
            level.getGameRules().set(
                    GameRules.RANDOM_TICK_SPEED,
                    0,
                    level.getServer()
            );
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    level.setBlockAndUpdate(
                            origin.offset(x, -2, z),
                            Blocks.STONE.defaultBlockState()
                    );
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
            final BlockPos water = fixture.waterOffset();
            level.setBlockAndUpdate(
                    origin.offset(
                            water.getX(),
                            -1,
                            water.getZ()
                    ),
                    Blocks.WATER.defaultBlockState()
            );
            for (BlockPos offset : fixture.cropOffsets()) {
                final BlockPos ground = origin.offset(
                        offset.getX(),
                        -1,
                        offset.getZ()
                );
                level.setBlockAndUpdate(
                        ground,
                        Blocks.FARMLAND.defaultBlockState()
                                .setValue(
                                        FarmlandBlock.MOISTURE,
                                        FarmlandBlock.MAX_MOISTURE
                                )
                );
                level.setBlockAndUpdate(
                        ground.above(),
                        matureCropState(fixture.crop())
                );
            }
            player.stopRiding();
            if (player.isSleeping()) {
                player.stopSleepInBed(true, false);
            }
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getEnderChestInventory().clearContent();
            player.removeAllEffects();
            player.clearFire();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            player.getInventory().setItem(
                    0,
                    new ItemStack(
                            plantingItem(fixture.crop()),
                            fixture.maximumPlants()
                    )
            );
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.broadcastChanges();
            player.teleportTo(
                    origin.getX() + fixture.spawnOffsetX(),
                    origin.getY(),
                    origin.getZ() + fixture.spawnOffsetZ()
            );
            player.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(origin)
            );
        }

        private void tick() {
            if (finished) {
                return;
            }
            try {
                final SemanticObservation observation = publish();
                if (skill == null) {
                    tryStart(observation);
                } else {
                    tickSkill(observation);
                }
            } finally {
                core.postServerTick();
            }
        }

        private void tryStart(final SemanticObservation observation) {
            if (observation.visibleBlockFaces().stream().noneMatch(face ->
                    fixture.crop().plantedBlockId()
                            .equals(face.blockTypeId())
                            && Integer.toString(
                                    fixture.crop().matureAge()
                            ).equals(
                                    face.stateProperties().get("age")
                            )
            )) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                                <= START_WINDOW_TICKS,
                        "Mature " + fixture.crop().name()
                                + " never entered fair first-person view"
                );
                return;
            }
            parameters = new MaintainObservedCropFieldParameters(
                    DimensionRef.OVERWORLD,
                    fixture.crop(),
                    fixture.maximumPlants()
            );
            final MaintainObservedCropFieldSkill candidate =
                    new MaintainObservedCropFieldSkill(
                            player.getUUID(),
                            core,
                            coreFrames,
                            interactions,
                            interactionFrames,
                            shelterFrames,
                            plans
                    );
            final SkillContext context = context(observation);
            final var rejected = candidate.preconditions(
                    context,
                    parameters
            );
            helper.assertTrue(
                    rejected.isEmpty(),
                    "Crop-maintenance preconditions were rejected: "
                            + rejected
            );
            candidate.start(context, parameters);
            skill = candidate;
            skillStartedAt = helper.getTick();
        }

        private void tickSkill(
                final SemanticObservation observation
        ) {
            final SkillContext context = context(observation);
            positionTrace.add(String.format(
                    java.util.Locale.ROOT,
                    "t=%d pos=%.3f/%.3f/%.3f yaw=%.1f pitch=%.1f "
                            + "ground=%s/collision=%s/water=%s "
                            + "vel=%.3f/%.3f/%.3f rev=%d",
                    helper.getTick(),
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    player.getYRot(),
                    player.getXRot(),
                    player.onGround(),
                    player.horizontalCollision,
                    player.isInWater(),
                    player.getDeltaMovement().x(),
                    player.getDeltaMovement().y(),
                    player.getDeltaMovement().z(),
                    observation.sequence()
            ));
            if (positionTrace.size() > 80) {
                positionTrace.removeFirst();
            }
            final List<String> visibleItems = observation.visibleEntities()
                    .stream()
                    .filter(entity -> "minecraft:item".equals(
                            entity.entityTypeId()
                    ))
                    .map(entity -> entity.visibleProperties().get("itemId")
                            + "@" + String.format(
                                    java.util.Locale.ROOT,
                                    "%.3f/%.3f/%.3f",
                                    entity.position().x(),
                                    entity.position().y(),
                                    entity.position().z()
                            )
                            + "/rev=" + observation.sequence())
                    .toList();
            if (!visibleItems.isEmpty()) {
                visibleItemTrace.addAll(visibleItems);
                while (visibleItemTrace.size() > 40) {
                    visibleItemTrace.removeFirst();
                }
            }
            final SkillTickResult result = skill.tick(context, parameters);
            recordCompletionBlocks();
            helper.assertTrue(
                    result.status() != SkillTickResult.Status.FAILED,
                    "Crop-maintenance skill failed: "
                            + result.failure()
                                    .map(reason -> reason.code())
                                    .orElse("unknown")
                            + ", checkpoint="
                            + skill.checkpoint(context, parameters).payload()
                            + ", pos=" + player.position()
                            + ", harvestInventory="
                            + player.getInventory().countItem(
                                    harvestItem(fixture.crop())
                            )
                            + ", nearbyDrops=" + nearbyHarvestDrops()
                            + ", visibleItems=" + visibleItemTrace
                            + ", positionTrace=" + positionTrace
                            + ", localBlocks=" + localBlockSummary()
                            + ", actions=" + actionAudit
                            + ", interactionTargets="
                            + interactionTargetTrace
                            + ", visibleItems=" + visibleItemTrace
                            + ", completionBlocks=" + completionBlockTrace
                            + ", coreActions=" + coreActionAudit
                            + ", coreState=" + core.snapshot()
                            + ", usingItem=" + player.isUsingItem()
                            + ", mainHand=" + player.getMainHandItem()
            );
            if (result.status() != SkillTickResult.Status.COMPLETED) {
                helper.assertTrue(
                        helper.getTick() - skillStartedAt
                                <= fixture.skillWindowTicks(),
                        "Crop-maintenance exceeded its bounded window; "
                                + skill.checkpoint(
                                        context,
                                        parameters
                                ).payload()
                );
                return;
            }
            verifyResult();
        }

        private void verifyResult() {
            int replanted = 0;
            for (BlockPos offset : fixture.cropOffsets()) {
                final var state = helper.getLevel().getBlockState(
                        origin.offset(
                                offset.getX(),
                                0,
                                offset.getZ()
                        )
                );
                if (isAgeZeroCrop(state, fixture.crop())) {
                    replanted++;
                }
            }
            helper.assertTrue(
                    replanted == fixture.maximumPlants(),
                    "Expected " + fixture.maximumPlants()
                            + " age-zero replants, got " + replanted
                            + ", missing=" + missingReplants()
                            + ", actions=" + actionAudit
                            + ", interactionTargets="
                            + interactionTargetTrace
                            + ", completionBlocks=" + completionBlockTrace
                            + ", pos=" + player.position()
                            + ", positionTrace=" + positionTrace
                            + ", localBlocks=" + localBlockSummary()
                            + ", coreActions=" + coreActionAudit
            );
            helper.assertTrue(
                    player.getInventory().countItem(
                            harvestItem(fixture.crop())
                    )
                            > initialHarvestItemCount,
                    "Harvest output was not collected into owned inventory"
            );
            helper.assertTrue(
                    countAction("begin_mining")
                            == fixture.maximumPlants()
                            && countAction("use_on_block")
                                == fixture.maximumPlants(),
                    "Maintenance ordinary action counts were wrong: "
                            + actionAudit
            );
            finished = true;
            helper.succeed();
        }

        private void recordCompletionBlocks() {
            final long useOnBlockActions = countAction("use_on_block");
            while (tracedUseOnBlockActions < useOnBlockActions) {
                completionBlockTrace.add(
                        "tick=" + helper.getTick() + " "
                                + cropStateSummary()
                );
                tracedUseOnBlockActions++;
            }
        }

        private List<String> cropStateSummary() {
            final List<String> states = new ArrayList<>();
            for (BlockPos offset : fixture.cropOffsets()) {
                final BlockPos crop = origin.offset(
                        offset.getX(),
                        0,
                        offset.getZ()
                );
                states.add(crop + "="
                        + helper.getLevel().getBlockState(crop));
            }
            return states;
        }

        private List<String> missingReplants() {
            final List<String> missing = new ArrayList<>();
            for (BlockPos offset : fixture.cropOffsets()) {
                final BlockPos crop = origin.offset(
                        offset.getX(),
                        0,
                        offset.getZ()
                );
                if (!isAgeZeroCrop(
                        helper.getLevel().getBlockState(crop),
                        fixture.crop()
                )) {
                    final BlockPos support = crop.below();
                    missing.add(crop + "="
                            + helper.getLevel().getBlockState(crop));
                    missing.add("support=" + support + "="
                            + helper.getLevel().getBlockState(support));
                }
            }
            return missing;
        }

        private SemanticObservation publish() {
            if (latestObservation != null
                    && helper.getTick() - lastSemanticTick
                        < SEMANTIC_INTERVAL_TICKS) {
                return latestObservation;
            }
            final SemanticObservation observation = sampler.sample(player);
            coreFrames.publish(observation);
            interactionFrames.publish(observation);
            shelterFrames.publish(observation);
            latestObservation = observation;
            lastSemanticTick = helper.getTick();
            return observation;
        }

        private SkillContext context(
                final SemanticObservation observation
        ) {
            return new SkillContext(
                    1,
                    observation.sequence(),
                    helper.getLevel().getGameTime(),
                    true,
                    true,
                    0.0
            );
        }

        private long countAction(final String action) {
            return actionAudit.stream().filter(accepted ->
                    action.equals(accepted.action())
            ).count();
        }

        private List<String> nearbyHarvestDrops() {
            return helper.getLevel().getEntitiesOfClass(
                    ItemEntity.class,
                    player.getBoundingBox().inflate(8.0)
            ).stream().map(drop -> drop.getItem().getItem()
                    + "=" + drop.getItem().getCount()
                    + "@" + String.format(
                            java.util.Locale.ROOT,
                            "%.3f/%.3f/%.3f",
                            drop.getX(),
                            drop.getY(),
                            drop.getZ()
                    )
                    ).toList();
        }

        private List<String> localBlockSummary() {
            final BlockPos feet = BlockPos.containing(player.position());
            final List<String> blocks = new ArrayList<>();
            for (int x = feet.getX() - 2; x <= feet.getX() + 2; x++) {
                for (int z = feet.getZ() - 3; z <= feet.getZ() + 2; z++) {
                    final BlockPos body = new BlockPos(x, feet.getY(), z);
                    final BlockPos support = body.below();
                    blocks.add(x + "/" + feet.getY() + "/" + z
                            + "=" + BuiltInRegistries.BLOCK.getKey(
                                    helper.getLevel().getBlockState(body)
                                            .getBlock()
                            )
                            + "/" + BuiltInRegistries.BLOCK.getKey(
                                    helper.getLevel().getBlockState(support)
                                            .getBlock()
                            ));
                }
            }
            return blocks;
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            if (!finished && skill != null && parameters != null) {
                skill.cancel(
                        new SkillContext(
                                1,
                                0,
                                helper.getLevel().getGameTime(),
                                true,
                                true,
                                0.0
                        ),
                        parameters
                );
            }
            helper.getLevel().getGameRules().set(
                    GameRules.RANDOM_TICK_SPEED,
                    initialRandomTickSpeed,
                    helper.getLevel().getServer()
            );
            plans.close();
            core.quiesceNow();
            interactionDelegate.quiesceNow();
        }

        private static net.minecraft.world.item.Item plantingItem(
                final CropFieldVariant crop
        ) {
            return switch (crop) {
                case WHEAT -> Items.WHEAT_SEEDS;
                case CARROT -> Items.CARROT;
                case POTATO -> Items.POTATO;
                case BEETROOT -> Items.BEETROOT_SEEDS;
            };
        }

        private static net.minecraft.world.item.Item harvestItem(
                final CropFieldVariant crop
        ) {
            return switch (crop) {
                case WHEAT -> Items.WHEAT;
                case CARROT -> Items.CARROT;
                case POTATO -> Items.POTATO;
                case BEETROOT -> Items.BEETROOT;
            };
        }

        private static BlockState matureCropState(
                final CropFieldVariant crop
        ) {
            return switch (crop) {
                case WHEAT -> Blocks.WHEAT.defaultBlockState()
                        .setValue(CropBlock.AGE, crop.matureAge());
                case CARROT -> Blocks.CARROTS.defaultBlockState()
                        .setValue(CropBlock.AGE, crop.matureAge());
                case POTATO -> Blocks.POTATOES.defaultBlockState()
                        .setValue(CropBlock.AGE, crop.matureAge());
                case BEETROOT -> Blocks.BEETROOTS.defaultBlockState()
                        .setValue(BeetrootBlock.AGE, crop.matureAge());
            };
        }

        private static boolean isAgeZeroCrop(
                final BlockState state,
                final CropFieldVariant crop
        ) {
            return switch (crop) {
                case WHEAT -> state.is(Blocks.WHEAT)
                        && state.getValue(CropBlock.AGE) == 0;
                case CARROT -> state.is(Blocks.CARROTS)
                        && state.getValue(CropBlock.AGE) == 0;
                case POTATO -> state.is(Blocks.POTATOES)
                        && state.getValue(CropBlock.AGE) == 0;
                case BEETROOT -> state.is(Blocks.BEETROOTS)
                        && state.getValue(BeetrootBlock.AGE) == 0;
            };
        }
    }

    private static final class FieldScenario {
        private static final int START_WINDOW_TICKS = 200;
        private static final int SKILL_WINDOW_TICKS = 7_000;
        private static final int PRODUCTION_WINDOW_TICKS = 1_200;
        /*
         * Test-only acceleration of vanilla random ticks. Production code
         * never changes this rule, and cleanup restores the prior value.
         * This gate proves mechanics and commissioning, not default-speed
         * items/hour.
         */
        private static final int COMMISSION_RANDOM_TICK_SPEED = 128;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerInteractionSkillFrameSource interactionFrames;
        private final ServerShelterFrameSource shelterFrames;
        private final ServerOwnedCoreSkillActuator core;
        private final ServerOwnedInteractionSkillActuator interactions;
        private final AsyncHydratedCropFieldPlanService plans =
                new AsyncHydratedCropFieldPlanService();
        private final List<AcceptedLowLevelAction> actionAudit =
                new ArrayList<>();
        private final long createdAt;
        private final int initialRandomTickSpeed;
        private final int initialHoeDamage;
        private final int initialShovelDamage;

        private BuildHydratedCropFieldSkill skill;
        private BuildHydratedCropFieldParameters parameters;
        private long skillStartedAt = -1;
        private long commissionedAt = -1;
        private boolean constructionComplete;
        private boolean finished;
        private boolean cleaned;

        private FieldScenario(
                final GameTestHelper helper,
                final ServerPlayer player,
                final BlockPos origin
        ) {
            this.helper = helper;
            this.player = player;
            this.origin = origin.immutable();
            createdAt = helper.getTick();
            initialRandomTickSpeed = helper.getLevel().getGameRules()
                    .get(GameRules.RANDOM_TICK_SPEED);
            prepareFixture();
            initialHoeDamage = ownedDamage(Items.STONE_HOE);
            initialShovelDamage = ownedDamage(Items.STONE_SHOVEL);

            final var server = helper.getLevel().getServer();
            coreFrames = new ServerCoreSkillFrameSource(
                    server,
                    player.getUUID()
            );
            interactionFrames = new ServerInteractionSkillFrameSource(
                    server,
                    player.getUUID()
            );
            shelterFrames = new ServerShelterFrameSource(
                    server,
                    player.getUUID()
            );
            core = new ServerOwnedCoreSkillActuator(
                    server,
                    player.getUUID()
            );
            interactions = new ServerOwnedInteractionSkillActuator(
                    server,
                    player.getUUID(),
                    null,
                    actionAudit::add
            );
        }

        private void prepareFixture() {
            final var level = helper.getLevel();
            helper.setTime(6_000L);
            level.getGameRules().set(
                    GameRules.RANDOM_TICK_SPEED,
                    COMMISSION_RANDOM_TICK_SPEED,
                    level.getServer()
            );
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    level.setBlockAndUpdate(
                            origin.offset(x, -2, z),
                            Blocks.STONE.defaultBlockState()
                    );
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
            player.stopRiding();
            if (player.isSleeping()) {
                player.stopSleepInBed(true, false);
            }
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getEnderChestInventory().clearContent();
            player.removeAllEffects();
            player.clearFire();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            player.getInventory().setItem(
                    0,
                    new ItemStack(Items.STONE_HOE)
            );
            player.getInventory().setItem(
                    1,
                    new ItemStack(Items.STONE_SHOVEL)
            );
            player.getInventory().setItem(
                    2,
                    new ItemStack(Items.WATER_BUCKET)
            );
            player.getInventory().setItem(
                    3,
                    new ItemStack(Items.WHEAT_SEEDS, 8)
            );
            player.getInventory().setSelectedSlot(0);
            player.inventoryMenu.broadcastChanges();
            player.teleportTo(
                    origin.getX() + 0.5,
                    origin.getY(),
                    origin.getZ() + 0.5
            );
            player.setYRot(0.0F);
            player.setXRot(20.0F);
            player.setYHeadRot(0.0F);
        }

        private void tick() {
            if (finished) {
                return;
            }
            try {
                if (constructionComplete) {
                    tickProductionVerification();
                    return;
                }
                final SemanticObservation observation = publish();
                if (skill == null) {
                    tryStart(observation);
                } else {
                    tickSkill(observation);
                }
            } finally {
                core.postServerTick();
            }
        }

        private void tryStart(final SemanticObservation observation) {
            if (observation.visibleBlockFaces().stream().noneMatch(face ->
                    "minecraft:dirt".equals(face.blockTypeId())
                            && "up".equals(face.face())
                            && face.adjacentLightLevel() >= 9
            )) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                                <= START_WINDOW_TICKS,
                        "Lit dirt never entered fair first-person view"
                );
                return;
            }
            parameters = new BuildHydratedCropFieldParameters(
                    DimensionRef.OVERWORLD,
                    dev.mcai.companion.mechanism.CropFieldVariant.WHEAT,
                    8,
                    false
            );
            final BuildHydratedCropFieldSkill candidate =
                    new BuildHydratedCropFieldSkill(
                            player.getUUID(),
                            core,
                            coreFrames,
                            interactions,
                            interactionFrames,
                            shelterFrames,
                            plans
                    );
            final SkillContext context = context(observation);
            final var rejected = candidate.preconditions(
                    context,
                    parameters
            );
            helper.assertTrue(
                    rejected.isEmpty(),
                    "Whole-field preconditions were rejected: "
                            + rejected
            );
            candidate.start(context, parameters);
            skill = candidate;
            skillStartedAt = helper.getTick();
        }

        private void tickSkill(
                final SemanticObservation observation
        ) {
            final SkillContext context = context(observation);
            final SkillTickResult result = skill.tick(
                    context,
                    parameters
            );
            helper.assertTrue(
                    result.status() != SkillTickResult.Status.FAILED,
                    "Whole-field skill failed: "
                            + result.failure()
                                    .map(reason -> reason.code())
                                    .orElse("unknown")
                            + ", checkpoint="
                            + skill.checkpoint(
                                    context,
                                    parameters
                            ).payload()
                            + ", pos=" + player.position()
                            + ", actions=" + actionAudit
            );
            if (result.status()
                    != SkillTickResult.Status.COMPLETED) {
                helper.assertTrue(
                        helper.getTick() - skillStartedAt
                                <= SKILL_WINDOW_TICKS,
                        "Whole-field skill exceeded its bounded window; "
                                + skill.checkpoint(
                                    context,
                                    parameters
                                ).payload()
                );
                return;
            }
            verifyConstruction();
        }

        private void verifyConstruction() {
            int water = 0;
            int farmland = 0;
            int wheat = 0;
            for (int x = -6; x <= 6; x++) {
                for (int z = -6; z <= 6; z++) {
                    final BlockPos ground = origin.offset(x, -1, z);
                    if (helper.getLevel().getBlockState(ground)
                            .is(Blocks.WATER)) {
                        water++;
                    }
                    if (helper.getLevel().getBlockState(ground)
                            .is(Blocks.FARMLAND)) {
                        farmland++;
                    }
                    if (helper.getLevel().getBlockState(ground.above())
                            .is(Blocks.WHEAT)) {
                        wheat++;
                    }
                }
            }
            helper.assertTrue(
                    water == 1 && farmland == 8 && wheat == 8,
                    "Expected one water and eight planted farmland cells, "
                            + "got water=" + water + ", farmland="
                            + farmland + ", wheat=" + wheat
            );
            helper.assertTrue(
                    player.getInventory().countItem(Items.WHEAT_SEEDS)
                            == 0,
                    "Whole field did not consume exactly eight seeds"
            );
            helper.assertTrue(
                    player.getInventory().countItem(Items.WATER_BUCKET)
                            == 0
                            && player.getInventory().countItem(Items.BUCKET)
                                    == 1,
                    "Whole field did not perform the bucket transition"
            );
            helper.assertTrue(
                    ownedDamage(Items.STONE_HOE)
                            == initialHoeDamage + 8,
                    "Whole field did not apply eight hoe durability uses"
            );
            helper.assertTrue(
                    ownedDamage(Items.STONE_SHOVEL)
                            == initialShovelDamage + 1,
                    "Whole field did not apply one shovel durability use"
            );
            helper.assertTrue(
                    countAction("begin_mining") == 1
                            && countAction("use_item") == 1
                            && countAction("use_on_block") == 16,
                    "Whole-field ordinary action counts were wrong: "
                            + actionAudit
            );
            constructionComplete = true;
            commissionedAt = helper.getTick();
            core.quiesceNow();
            interactions.quiesceNow();
        }

        private void tickProductionVerification() {
            int water = 0;
            int farmland = 0;
            int hydrated = 0;
            int wheat = 0;
            int totalAge = 0;
            int sufficientlyLit = 0;
            for (int x = -6; x <= 6; x++) {
                for (int z = -6; z <= 6; z++) {
                    final BlockPos ground = origin.offset(x, -1, z);
                    final var groundState = helper.getLevel()
                            .getBlockState(ground);
                    final var cropState = helper.getLevel()
                            .getBlockState(ground.above());
                    if (groundState.is(Blocks.WATER)) {
                        water++;
                    }
                    if (groundState.is(Blocks.FARMLAND)) {
                        farmland++;
                        if (groundState.getValue(
                                FarmlandBlock.MOISTURE
                        ) == FarmlandBlock.MAX_MOISTURE) {
                            hydrated++;
                        }
                    }
                    if (cropState.is(Blocks.WHEAT)) {
                        wheat++;
                        totalAge += cropState.getValue(CropBlock.AGE);
                        if (helper.getLevel().getRawBrightness(
                                ground.above(),
                                0
                        ) >= 9) {
                            sufficientlyLit++;
                        }
                    }
                }
            }
            helper.assertTrue(
                    water == 1 && farmland == 8 && wheat == 8,
                    "Commissioned field regressed while awaiting vanilla "
                            + "random ticks: water=" + water
                            + ", farmland=" + farmland
                            + ", wheat=" + wheat
            );
            if (hydrated == 8 && sufficientlyLit == 8
                    && totalAge > 0) {
                finished = true;
                helper.succeed();
                return;
            }
            helper.assertTrue(
                    helper.getTick() - commissionedAt
                            <= PRODUCTION_WINDOW_TICKS,
                    "Field never entered production through vanilla random "
                            + "ticks: hydrated=" + hydrated
                            + "/8, lit=" + sufficientlyLit
                            + "/8, totalWheatAge=" + totalAge
                            + ", randomTickSpeed="
                            + COMMISSION_RANDOM_TICK_SPEED
            );
        }

        private SemanticObservation publish() {
            final SemanticObservation observation = sampler.sample(player);
            coreFrames.publish(observation);
            interactionFrames.publish(observation);
            shelterFrames.publish(observation);
            return observation;
        }

        private SkillContext context(
                final SemanticObservation observation
        ) {
            return new SkillContext(
                    1,
                    observation.sequence(),
                    helper.getLevel().getGameTime(),
                    true,
                    true,
                    0.0
            );
        }

        private int ownedDamage(
                final net.minecraft.world.item.Item item
        ) {
            for (int slot = 0;
                    slot < player.getInventory().getContainerSize();
                    slot++) {
                final ItemStack stack = player.getInventory().getItem(slot);
                if (stack.is(item)) {
                    return stack.getDamageValue();
                }
            }
            return -1;
        }

        private long countAction(final String action) {
            return actionAudit.stream().filter(accepted ->
                    action.equals(accepted.action())
            ).count();
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            if (!constructionComplete
                    && !finished
                    && skill != null
                    && parameters != null) {
                skill.cancel(
                        new SkillContext(
                                1,
                                0,
                                helper.getLevel().getGameTime(),
                                true,
                                true,
                                0.0
                        ),
                        parameters
                );
            }
            helper.getLevel().getGameRules().set(
                    GameRules.RANDOM_TICK_SPEED,
                    initialRandomTickSpeed,
                    helper.getLevel().getServer()
            );
            plans.close();
            core.quiesceNow();
            interactions.quiesceNow();
        }
    }
}
