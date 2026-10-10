package dev.mcai.companion.skills.building;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.GameTestCompanionSpawn;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.perception.FairPerceptionSampler;
import dev.mcai.companion.perception.FirstPersonCrosshairSampler;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.ServerCoreSkillFrameSource;
import dev.mcai.companion.skills.core.ServerOwnedCoreSkillActuator;
import dev.mcai.companion.skills.inventory
        .ServerInventorySkillActuator;
import dev.mcai.companion.skills.interaction
        .ServerOwnedInteractionSkillActuator;
import dev.mcai.companion.skills.survey.SurveyResultBuffer;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Development-only real-Forge contracts for dynamic building.
 */
public final class BuildingGameTests {
    private static final BlockPos TEST_ORIGIN =
            new BlockPos(16, 8, 16);
    private static final int BODY_START_TIMEOUT_TICKS = 3_000;

    private BuildingGameTests() {
    }

    /**
     * Proves the production interaction boundary will not mine the collision
     * surface carrying the body, while the same observed block remains a
     * legal ordinary survival target after the body steps aside.
     */
    public static void currentSupportMiningGuard(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicReference<SupportMiningScenario> scenario =
                new AtomicReference<>();
        final long startedAt = helper.getTick();

        helper.addCleanup(ignored -> {
            if (AiPlayerManager.status(server).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(server);
            }
        });
        GameTestCompanionSpawn.resetForIsolatedFixture(server);
        helper.assertTrue(
                AiPlayerManager.status(server).state()
                        == SessionState.ABSENT,
                "Support-mining gate requires an isolated body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Support-mining companion spawn was rejected"
        );

        helper.onEachTick(() -> {
            final SupportMiningScenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Support-mining companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Support-mining companion did not become active"
                );
                return;
            }
            scenario.set(new SupportMiningScenario(
                    helper,
                    AiPlayerManager.onlinePlayer(server).orElseThrow(),
                    helper.absolutePos(TEST_ORIGIN)
            ));
        });
    }

    /**
     * Reproduces the field failure exactly enough to prove causality:
     * vanilla rejects a plank whose target collision box contains a cow.
     * It also proves that the production shelter frame retains that cow when
     * the player turns away, then expires it without querying the level.
     */
    public static void visibleEntityPlacementOccupancy(
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
                "Building occupancy gate requires an isolated body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Building occupancy companion spawn was rejected"
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
                    "Building occupancy companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Building occupancy companion did not become active"
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
     * Runs the actual building skill after an animal enters a wall cell only
     * after the plan is fixed. Success requires ordinary player movement to
     * push the animal clear, then the normal use-on-block path to consume and
     * place the plank. The oracle reads physical state only after execution.
     */
    public static void placementObstructionRecovery(
            final GameTestHelper helper
    ) {
        placementObstructionRecovery(helper, 0, false);
    }

    /**
     * Reproduces the live failure boundary after the builder has already
     * consumed and causally confirmed several wall blocks. The late animal
     * makes the current shell invalid while the partial structure narrows the
     * immediately known replacement sites. Recovery must remain inside the
     * same local transaction, acquire any missing first-person map, preserve
     * useful placed blocks, avoid the occupied cell and leave the animal
     * unharmed.
     */
    public static void partialShelterObstructionRecovery(
            final GameTestHelper helper
    ) {
        placementObstructionRecovery(helper, 3, true);
    }

    private static final class SupportMiningScenario {
        private static final int MAXIMUM_TICKS = 500;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos anchor;
        private final BlockPos support;
        private final ServerOwnedInteractionSkillActuator interactions;
        private final long createdAt;

        private SupportMiningPhase phase =
                SupportMiningPhase.ON_SUPPORT;
        private int stableTicks;

        private SupportMiningScenario(
                final GameTestHelper helper,
                final ServerPlayer player,
                final BlockPos anchor
        ) {
            this.helper = helper;
            this.player = player;
            this.anchor = anchor;
            support = anchor.below();
            interactions = new ServerOwnedInteractionSkillActuator(
                    helper.getLevel().getServer(),
                    player.getUUID()
            );
            createdAt = helper.getTick();
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getInventory().setItem(
                    0,
                    new ItemStack(Items.DIAMOND_PICKAXE)
            );
            player.getInventory().setSelectedSlot(0);
            player.teleportTo(
                    anchor.getX() + 0.5D,
                    anchor.getY(),
                    anchor.getZ() + 0.5D
            );
            player.setDeltaMovement(Vec3.ZERO);
        }

        private void tick() {
            helper.assertTrue(
                    helper.getTick() - createdAt <= MAXIMUM_TICKS,
                    "Support-mining contract timed out in " + phase
            );
            switch (phase) {
                case ON_SUPPORT -> tickOnSupport();
                case BESIDE_SUPPORT -> tickBesideSupport();
                case MINING -> tickMining();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void tickOnSupport() {
            if (!player.onGround() || ++stableTicks < 5) {
                return;
            }
            final VisibleBlockFace crosshair = lookAtSupport();
            if (crosshair == null) {
                return;
            }
            final ActionOutcome denied = interactions.beginMining(
                    target(crosshair)
            );
            helper.assertTrue(
                    denied == ActionOutcome.WORLD_DENIED
                            && helper.getLevel()
                                .getBlockState(support)
                                .is(Blocks.SMOOTH_STONE),
                    "Current support mining was not denied before vanilla "
                            + "mutation: outcome=" + denied
            );
            player.teleportTo(
                    anchor.getX() + 1.5D,
                    anchor.getY(),
                    anchor.getZ() + 0.5D
            );
            player.setDeltaMovement(Vec3.ZERO);
            stableTicks = 0;
            phase = SupportMiningPhase.BESIDE_SUPPORT;
        }

        private void tickBesideSupport() {
            if (!player.onGround() || ++stableTicks < 5) {
                return;
            }
            final VisibleBlockFace crosshair = lookAtSupport();
            if (crosshair == null) {
                return;
            }
            final ActionOutcome started = interactions.beginMining(
                    target(crosshair)
            );
            helper.assertTrue(
                    started == ActionOutcome.IN_PROGRESS
                            || started == ActionOutcome.COMPLETED,
                    "The same block remained denied after stepping aside: "
                            + started
            );
            if (started == ActionOutcome.COMPLETED) {
                finish();
                return;
            }
            phase = SupportMiningPhase.MINING;
        }

        private void tickMining() {
            final ActionOutcome outcome =
                    interactions.continueMining();
            if (outcome == ActionOutcome.COMPLETED
                    || helper.getLevel()
                        .getBlockState(support)
                        .isAir()) {
                finish();
                return;
            }
            helper.assertTrue(
                    outcome.accepted(),
                    "Ordinary support mining failed after stepping aside: "
                            + outcome
            );
        }

        private VisibleBlockFace lookAtSupport() {
            player.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    new Vec3(
                            support.getX() + 0.5D,
                            support.getY() + 1.0D,
                            support.getZ() + 0.5D
                    )
            );
            player.setYHeadRot(player.getYRot());
            return FirstPersonCrosshairSampler.sample(player)
                    .filter(face ->
                            face.block().x() == support.getX()
                                    && face.block().y()
                                        == support.getY()
                                    && face.block().z()
                                        == support.getZ()
                    )
                    .orElse(null);
        }

        private void finish() {
            helper.assertTrue(
                    helper.getLevel().getBlockState(support).isAir(),
                    "Stepping aside did not permit physical block removal"
            );
            helper.assertTrue(
                    player.getMainHandItem().is(Items.DIAMOND_PICKAXE)
                            && player.getMainHandItem()
                                .getDamageValue() == 1,
                    "Allowed mining did not preserve vanilla durability"
            );
            phase = SupportMiningPhase.DONE;
            helper.succeed();
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

    private enum SupportMiningPhase {
        ON_SUPPORT,
        BESIDE_SUPPORT,
        MINING,
        DONE
    }

    private static void placementObstructionRecovery(
            final GameTestHelper helper,
            final int minimumConfirmedBeforeObstruction,
            final boolean requirePartialBuild
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicReference<RecoveryScenario> scenario =
                new AtomicReference<>();
        final long startedAt = helper.getTick();

        helper.addCleanup(ignored -> {
            final RecoveryScenario current = scenario.get();
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
                "Placement-recovery gate requires an isolated body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Placement-recovery companion spawn was rejected"
        );

        helper.onEachTick(() -> {
            final RecoveryScenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Placement-recovery companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Placement-recovery companion did not become active"
                );
                return;
            }
            scenario.set(new RecoveryScenario(
                    helper,
                    AiPlayerManager.onlinePlayer(server).orElseThrow(),
                    helper.absolutePos(TEST_ORIGIN),
                    minimumConfirmedBeforeObstruction,
                    requirePartialBuild
            ));
        });
    }

    /**
     * Exercises a complete roof layer through the production builder. The
     * body starts on a flat floor with ordinary survival inventory and must
     * construct both wall layers before a real jump raises its eye above the
     * wall top. It must then continue after that first block changes the
     * available headroom and visible support faces. Success is based on every
     * physical roof block, confirmed skill progress, inventory consumption,
     * and observed body elevation.
     */
    public static void roofJumpPlacement(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicReference<RoofJumpScenario> scenario =
                new AtomicReference<>();
        final long startedAt = helper.getTick();

        helper.addCleanup(ignored -> {
            final RoofJumpScenario current = scenario.get();
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
                "Roof-jump gate requires an isolated body"
        );
        helper.assertTrue(
                GameTestCompanionSpawn.request(
                        helper,
                        TEST_ORIGIN
                ).accepted(),
                "Roof-jump companion spawn was rejected"
        );

        helper.onEachTick(() -> {
            final RoofJumpScenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Roof-jump companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - startedAt
                                <= BODY_START_TIMEOUT_TICKS,
                        "Roof-jump companion did not become active"
                );
                return;
            }
            scenario.set(new RoofJumpScenario(
                    helper,
                    AiPlayerManager.onlinePlayer(server).orElseThrow(),
                    helper.absolutePos(TEST_ORIGIN)
            ));
        });
    }

    private static final class RoofJumpScenario {
        private static final int EXECUTION_TIMEOUT_TICKS = 8_000;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final OccludedApronShelterFrameSource shelterFrames;
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerOwnedCoreSkillActuator core;
        private final ServerOwnedInteractionSkillActuator interactions;
        private final OwnedStructureBlockIndex protectedStructures =
                new OwnedStructureBlockIndex();
        private final BuildShelterStepSkill skill;
        private final long createdAt;
        private final double startingY;

        private BuildShelterStepParameters parameters;
        private boolean started;
        private boolean finished;
        private boolean cleaned;
        private boolean forcedLateInteriorStance;
        private boolean observedExteriorRoofStance;
        private double maximumBodyY;
        private long skillCompletedAtTick = -1;

        private RoofJumpScenario(
                final GameTestHelper helper,
                final ServerPlayer player,
                final BlockPos origin
        ) {
            this.helper = helper;
            this.player = player;
            this.origin = origin.immutable();
            createdAt = helper.getTick();
            prepareFixture();
            startingY = player.getY();
            maximumBodyY = startingY;
            final var server = helper.getLevel().getServer();
            shelterFrames = new OccludedApronShelterFrameSource(
                    new ServerShelterFrameSource(
                            server,
                            player.getUUID()
                    )
            );
            coreFrames = new ServerCoreSkillFrameSource(
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
                    ignored -> {
                    },
                    protectedStructures
            );
            final ServerInventorySkillActuator inventory =
                    new ServerInventorySkillActuator(
                            server,
                            player.getUUID()
                    );
            skill = new BuildShelterStepSkill(
                    player.getUUID(),
                    interactions,
                    shelterFrames,
                    inventory,
                    core,
                    coreFrames,
                    new SurveyResultBuffer(),
                    new DynamicShelterPlanner(),
                    (ignoredRevision, ignoredPlan) -> {
                    },
                    protectedStructures
            );
        }

        private void prepareFixture() {
            final var level = helper.getLevel();
            for (int x = -10; x <= 10; x++) {
                for (int z = -10; z <= 10; z++) {
                    level.setBlockAndUpdate(
                            origin.offset(x, -1, z),
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 0; y <= 5; y++) {
                        level.setBlockAndUpdate(
                                origin.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            player.setItemInHand(
                    InteractionHand.MAIN_HAND,
                    new ItemStack(Items.OAK_PLANKS, 64)
            );
            player.getInventory().setItem(
                    1,
                    new ItemStack(Items.OAK_DOOR)
            );
            player.getInventory().setItem(
                    2,
                    new ItemStack(Items.TORCH, 4)
            );
            player.inventoryMenu.broadcastChanges();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.setDeltaMovement(Vec3.ZERO);
            player.teleportTo(
                    origin.getX() + 0.5,
                    origin.getY(),
                    origin.getZ() + 0.5
            );
        }

        private void tick() {
            if (finished) {
                return;
            }
            helper.assertTrue(
                    helper.getTick() - createdAt
                            <= EXECUTION_TIMEOUT_TICKS,
                    "Physical roof jump-placement timed out: "
                            + checkpoint()
            );
            try {
                maximumBodyY = Math.max(
                        maximumBodyY,
                        player.getY()
                );
                forceLateInteriorStance();
                observeExteriorRoofStance();
                final SemanticObservation observation =
                        publishObservation();
                /*
                 * Some final placement paths become terminal at the end of
                 * a nested physical action boundary. Detect the terminal
                 * state before calling tick again so this test reports the
                 * independent world oracle, not invalid_state.
                 */
                if (started
                        && skill.result(
                                context(observation.sequence()),
                                parameters
                        ).status() == SkillResult.Status.COMPLETED
                        && skillCompletedAtTick < 0) {
                    if (structuralBatchComplete()) {
                        skillCompletedAtTick = helper.getTick();
                    } else {
                        started = false;
                    }
                }
                if (skillCompletedAtTick >= 0) {
                    verifyCompleteRoofPlacement();
                    helper.assertTrue(
                            finished
                                    || helper.getTick()
                                            - skillCompletedAtTick <= 20,
                            "Roof skill completed without a matching "
                                    + "physical roof: "
                                    + physicalRoofSummary()
                    );
                    return;
                }
                if (!started) {
                    parameters = new BuildShelterStepParameters(
                            dev.mcai.companion.waypoint.DimensionRef
                                    .parse(
                                            observation.body()
                                                    .dimensionId()
                                    ),
                            observation.sequence(),
                            ShelterScale.COMPACT
                    );
                    final Optional<dev.mcai.companion.skill.SkillFailure>
                            rejected = skill.preconditions(
                                    context(observation.sequence()),
                                    parameters
                            );
                    helper.assertTrue(
                            rejected.isEmpty(),
                            "Roof-jump skill start rejected: "
                                    + rejected
                    );
                    skill.start(
                            context(observation.sequence()),
                            parameters
                    );
                    started = true;
                }

                final SkillTickResult result = skill.tick(
                        context(observation.sequence()),
                        parameters
                );
                if (result.status()
                        == SkillTickResult.Status.FAILED) {
                    finished = true;
                    helper.assertTrue(
                            false,
                            "Roof-jump skill failed: "
                                    + result.failure()
                                    + ", checkpoint=" + checkpoint()
                    );
                    return;
                }
                verifyCompleteRoofPlacement();
                if (result.status()
                        == SkillTickResult.Status.COMPLETED
                        && !finished) {
                    if (structuralBatchComplete()) {
                        /*
                         * A completed final structural batch must not be
                         * ticked again. Keep publishing fair observations
                         * briefly so the independent physical oracle can see
                         * the last vanilla block update.
                         */
                        skillCompletedAtTick = helper.getTick();
                    } else {
                        /*
                         * No currently reachable step is a safe atomic batch
                         * boundary, not whole-shelter completion. The normal
                         * supervisor starts the same checkpointed skill again
                         * from a newer fair observation.
                         */
                        started = false;
                    }
                }
            } finally {
                if (!finished) {
                    core.postServerTick();
                }
            }
        }

        private void forceLateInteriorStance() {
            if (forcedLateInteriorStance
                    || skill.confirmedStepCount() < 36) {
                return;
            }
            final ShelterPlan plan = skill.activePlan()
                    .orElse(null);
            if (plan == null) {
                return;
            }
            final double x = plan.origin().x()
                    + plan.exteriorWidth() / 2.0;
            final double z = plan.origin().z()
                    + plan.exteriorDepth() / 2.0;
            player.setDeltaMovement(Vec3.ZERO);
            player.teleportTo(x, plan.origin().y(), z);
            shelterFrames.hideFarApronUntilCorner(plan);
            forcedLateInteriorStance = true;
        }

        private void observeExteriorRoofStance() {
            if (!forcedLateInteriorStance) {
                return;
            }
            final ShelterPlan plan = skill.activePlan()
                    .orElse(null);
            if (plan == null) {
                return;
            }
            final BlockPos feet = player.blockPosition();
            final int minimumX = plan.origin().x();
            final int maximumX = minimumX
                    + plan.exteriorWidth() - 1;
            final int minimumZ = plan.origin().z();
            final int maximumZ = minimumZ
                    + plan.exteriorDepth() - 1;
            if (feet.getY() == plan.origin().y()
                    && (feet.getX() < minimumX
                            || feet.getX() > maximumX
                            || feet.getZ() < minimumZ
                            || feet.getZ() > maximumZ)) {
                observedExteriorRoofStance = true;
            }
        }

        private SemanticObservation publishObservation() {
            final SemanticObservation observation =
                    sampler.sample(player);
            shelterFrames.publish(observation);
            coreFrames.publish(observation);
            return observation;
        }

        private void verifyCompleteRoofPlacement() {
            final Optional<ShelterPlan> plan = skill.activePlan();
            if (plan.isEmpty()) {
                return;
            }
            final ShelterPlan active = plan.orElseThrow();
            final long requiredRoofBlocks = active.steps().stream()
                    .filter(step ->
                            step.role() == ShelterStepRole.ROOF)
                    .count();
            final long physicalRoofBlocks = active.steps().stream()
                    .filter(step ->
                            step.role() == ShelterStepRole.ROOF)
                    .filter(step ->
                            helper.getLevel().getBlockState(
                                    new BlockPos(
                                            step.target().x(),
                                            step.target().y(),
                                            step.target().z()
                                    )
                            ).is(Blocks.OAK_PLANKS))
                    .count();
            if (physicalRoofBlocks != requiredRoofBlocks
                    || skill.confirmedStepCount()
                            < active.requiredStructuralBlocks()) {
                return;
            }
            helper.assertTrue(
                    maximumBodyY >= startingY + 0.5,
                    "Roof appeared without the body physically rising: "
                            + maximumBodyY + " from " + startingY
            );
            helper.assertTrue(
                    player.getInventory().countItem(
                            Items.OAK_PLANKS
                    ) <= 64 - active.requiredStructuralBlocks(),
                    "Complete roof did not consume ordinary inventory"
            );
            helper.assertTrue(
                    forcedLateInteriorStance,
                    "Roof recovery gate never forced the body inside"
            );
            helper.assertTrue(
                    observedExteriorRoofStance,
                    "Occluded roof recovery never used an ordinary "
                            + "exterior stance"
            );
            helper.assertTrue(
                    shelterFrames
                            .visitedDoorSideApronCornerApproach(),
                    "Occluded roof recovery never followed the fairly "
                            + "observed exterior apron into a corner "
                            + "observation zone"
            );
            final ShelterBuildStep protectedRoof = active.steps()
                    .stream()
                    .filter(step ->
                            step.role() == ShelterStepRole.ROOF
                                    && helper.getLevel()
                                        .getBlockState(new BlockPos(
                                                step.target().x(),
                                                step.target().y(),
                                                step.target().z()
                                        ))
                                        .is(Blocks.OAK_PLANKS)
                    )
                    .findFirst()
                    .orElseThrow();
            final GridPos protectedTarget = protectedRoof.target();
            helper.assertTrue(
                    interactions.beginMining(
                            new BlockInteractionTarget(
                                    protectedTarget.x(),
                                    protectedTarget.y(),
                                    protectedTarget.z(),
                                    BlockFace.UP,
                                    new ActionVec3(
                                            protectedTarget.x() + 0.5,
                                            protectedTarget.y() + 0.5,
                                            protectedTarget.z() + 0.5
                                    )
                            )
                    ) == ActionOutcome.WORLD_DENIED,
                    "Server mining boundary allowed a generated "
                            + "shelter block to be dismantled"
            );
            helper.assertTrue(
                    helper.getLevel().getBlockState(
                            new BlockPos(
                                    protectedTarget.x(),
                                    protectedTarget.y(),
                                    protectedTarget.z()
                            )
                    ).is(Blocks.OAK_PLANKS),
                    "Protected roof changed after denied mining"
            );
            finished = true;
            helper.succeed();
        }

        private boolean structuralBatchComplete() {
            return skill.activePlan()
                    .map(plan ->
                            skill.confirmedStepCount()
                                    >= plan.requiredStructuralBlocks()
                    )
                    .orElse(false);
        }

        private String physicalRoofSummary() {
            final Optional<ShelterPlan> plan = skill.activePlan();
            if (plan.isEmpty()) {
                return "plan_unavailable";
            }
            final ShelterPlan active = plan.orElseThrow();
            final var roofSteps = active.steps().stream()
                    .filter(step ->
                            step.role() == ShelterStepRole.ROOF)
                    .toList();
            final var missing = roofSteps.stream()
                    .filter(step ->
                            !helper.getLevel().getBlockState(
                                    new BlockPos(
                                            step.target().x(),
                                            step.target().y(),
                                            step.target().z()
                                    )
                            ).is(Blocks.OAK_PLANKS))
                    .map(step ->
                            step.index() + "@" + step.target())
                    .toList();
            return "required=" + roofSteps.size()
                    + ", missing=" + missing
                    + ", confirmed=" + skill.confirmedStepCount()
                    + ", cornerApproachVisited="
                    + shelterFrames
                            .visitedDoorSideApronCornerApproach()
                    + ", checkpoint=" + checkpoint();
        }

        private String checkpoint() {
            if (parameters == null) {
                return "not_started";
            }
            return skill.checkpoint(
                    context(0),
                    parameters
            ).payload();
        }

        private SkillContext context(
                final long worldRevision
        ) {
            return new SkillContext(
                    1,
                    worldRevision,
                    helper.getTick(),
                    true,
                    true,
                    0.0
            );
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            core.quiesceNow();
        }
    }

    /**
     * Test-only observation boundary that reproduces an opaque shelter corner.
     *
     * <p>The flat roof fixture is observed before its walls are complete, so
     * the production incremental map already knows the entire exterior apron.
     * A real shelter on uneven terrain can hide its far apron after the body
     * is moved inside. This wrapper removes only those previously observed
     * apron columns until ordinary movement reaches one of the still-observed
     * door-side corners. It never reads the level or manufactures navigation
     * evidence.</p>
     */
    private static final class OccludedApronShelterFrameSource
            implements ShelterFrameSource {
        private final ServerShelterFrameSource delegate;

        private ShelterPlan hiddenPlan;
        private GridPos exteriorDoor;
        private long revealRevision = -1;
        private boolean visitedDoorSideApronCornerApproach;

        private OccludedApronShelterFrameSource(
                final ServerShelterFrameSource delegate
        ) {
            this.delegate = java.util.Objects.requireNonNull(
                    delegate,
                    "delegate"
            );
        }

        private void hideFarApronUntilCorner(
                final ShelterPlan plan
        ) {
            hiddenPlan = java.util.Objects.requireNonNull(
                    plan,
                    "plan"
            );
            exteriorDoor =
                    BuildShelterStepSkill.exteriorDoorwayStand(plan);
            revealRevision = -1;
            visitedDoorSideApronCornerApproach = false;
        }

        private boolean visitedDoorSideApronCornerApproach() {
            return visitedDoorSideApronCornerApproach;
        }

        private ShelterFrame publish(
                final SemanticObservation observation
        ) {
            final ShelterFrame raw = delegate.publish(observation);
            maybeRevealAtObservedCorner(raw);
            return filtered(raw);
        }

        @Override
        public Optional<ShelterFrame> current() {
            return delegate.current().map(this::filtered);
        }

        @Override
        public Optional<ShelterFrame> atObservation(
                final long observationRevision
        ) {
            return delegate.atObservation(observationRevision)
                    .map(this::filtered);
        }

        @Override
        public Optional<VisibleBlockFace> currentCrosshairBlock() {
            return delegate.currentCrosshairBlock();
        }

        private void maybeRevealAtObservedCorner(
                final ShelterFrame frame
        ) {
            if (hiddenPlan == null
                    || revealRevision >= 0
                    || !isDoorSideApronCornerApproach(
                            frame.feet()
                    )) {
                return;
            }
            visitedDoorSideApronCornerApproach = true;
            revealRevision = frame.observationRevision();
        }

        private ShelterFrame filtered(
                final ShelterFrame frame
        ) {
            if (!mustFilter(frame)) {
                return frame;
            }
            final LocalNavSnapshot navigation =
                    new LocalNavSnapshot(
                            frame.navigation().dimension(),
                            frame.navigation().revision(),
                            frame.navigation()
                                    .observedVoxels()
                                    .values()
                                    .stream()
                                    .filter(voxel ->
                                            keepObservedColumn(
                                                    voxel.position()
                                            )
                                    )
                                    .toList()
                    );
            return new ShelterFrame(
                    frame.playerId(),
                    frame.dimension(),
                    frame.currentGameTime(),
                    frame.observedAtGameTime(),
                    frame.observationRevision(),
                    frame.sessionGeneration(),
                    frame.feet(),
                    frame.lookDirection(),
                    frame.mainHand(),
                    frame.inventory(),
                    navigation,
                    frame.visibleBlockFaces(),
                    frame.recentVisibleEntities()
            );
        }

        private boolean mustFilter(
                final ShelterFrame frame
        ) {
            return hiddenPlan != null
                    && (revealRevision < 0
                            || frame.observationRevision()
                                    < revealRevision);
        }

        private boolean keepObservedColumn(
                final GridPos position
        ) {
            if (!isApronRingColumn(position)) {
                return true;
            }
            return isDoorSideColumn(position);
        }

        private boolean isApronRingColumn(
                final GridPos position
        ) {
            final int minimumX = hiddenPlan.origin().x() - 1;
            final int maximumX = hiddenPlan.origin().x()
                    + hiddenPlan.exteriorWidth();
            final int minimumZ = hiddenPlan.origin().z() - 1;
            final int maximumZ = hiddenPlan.origin().z()
                    + hiddenPlan.exteriorDepth();
            if (position.x() < minimumX
                    || position.x() > maximumX
                    || position.z() < minimumZ
                    || position.z() > maximumZ) {
                return false;
            }
            return position.x() == minimumX
                    || position.x() == maximumX
                    || position.z() == minimumZ
                    || position.z() == maximumZ;
        }

        private boolean isDoorSideColumn(
                final GridPos position
        ) {
            final int minimumX = hiddenPlan.origin().x() - 1;
            final int maximumX = hiddenPlan.origin().x()
                    + hiddenPlan.exteriorWidth();
            final int minimumZ = hiddenPlan.origin().z() - 1;
            final int maximumZ = hiddenPlan.origin().z()
                    + hiddenPlan.exteriorDepth();
            if (exteriorDoor.x() == minimumX) {
                return position.x() == minimumX;
            }
            if (exteriorDoor.x() == maximumX) {
                return position.x() == maximumX;
            }
            if (exteriorDoor.z() == minimumZ) {
                return position.z() == minimumZ;
            }
            if (exteriorDoor.z() == maximumZ) {
                return position.z() == maximumZ;
            }
            throw new IllegalStateException(
                    "Exterior doorway is not on the shelter apron"
            );
        }

        private boolean isDoorSideApronCornerApproach(
                final GridPos position
        ) {
            if (position.y() != hiddenPlan.origin().y()
                    || !isDoorSideColumn(position)) {
                return false;
            }
            final int minimumX = hiddenPlan.origin().x() - 1;
            final int maximumX = hiddenPlan.origin().x()
                    + hiddenPlan.exteriorWidth();
            final int minimumZ = hiddenPlan.origin().z() - 1;
            final int maximumZ = hiddenPlan.origin().z()
                    + hiddenPlan.exteriorDepth();
            if (exteriorDoor.x() == minimumX
                    || exteriorDoor.x() == maximumX) {
                return position.z() <= minimumZ + 1
                        || position.z() >= maximumZ - 1;
            }
            return position.x() <= minimumX + 1
                    || position.x() >= maximumX - 1;
        }
    }

    private static final class RecoveryScenario {
        private static final int EXECUTION_TIMEOUT_TICKS = 2_400;

        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerShelterFrameSource shelterFrames;
        private final ServerCoreSkillFrameSource coreFrames;
        private final ServerOwnedCoreSkillActuator core;
        private final ServerOwnedInteractionSkillActuator interactions;
        private final ServerInventorySkillActuator inventory;
        private final BuildShelterStepSkill skill;
        private final long createdAt;
        private final int minimumConfirmedBeforeObstruction;
        private final boolean requirePartialBuild;

        private BuildShelterStepParameters parameters;
        private Cow cow;
        private BlockPos blockedTarget;
        private String originalPlanId;
        private String repairedPlanId;
        private int planksAtRepair = -1;
        private int confirmedAtRepair = -1;
        private int confirmedAtObstruction = -1;
        private boolean started;
        private boolean finished;
        private boolean cleaned;

        private RecoveryScenario(
                final GameTestHelper helper,
                final ServerPlayer player,
                final BlockPos origin,
                final int minimumConfirmedBeforeObstruction,
                final boolean requirePartialBuild
        ) {
            this.helper = helper;
            this.player = player;
            this.origin = origin.immutable();
            this.minimumConfirmedBeforeObstruction =
                    minimumConfirmedBeforeObstruction;
            this.requirePartialBuild = requirePartialBuild;
            createdAt = helper.getTick();
            prepareFixture();
            final var server = helper.getLevel().getServer();
            shelterFrames = new ServerShelterFrameSource(
                    server,
                    player.getUUID()
            );
            coreFrames = new ServerCoreSkillFrameSource(
                    server,
                    player.getUUID()
            );
            core = new ServerOwnedCoreSkillActuator(
                    server,
                    player.getUUID()
            );
            interactions =
                    new ServerOwnedInteractionSkillActuator(
                            server,
                            player.getUUID()
                    );
            inventory = new ServerInventorySkillActuator(
                    server,
                    player.getUUID()
            );
            skill = new BuildShelterStepSkill(
                    player.getUUID(),
                    interactions,
                    shelterFrames,
                    inventory,
                    core,
                    coreFrames,
                    new SurveyResultBuffer(),
                    new DynamicShelterPlanner(),
                    (ignoredRevision, ignoredPlan) -> {
                    }
            );
        }

        private void prepareFixture() {
            final var level = helper.getLevel();
            for (int x = -10; x <= 10; x++) {
                for (int z = -10; z <= 10; z++) {
                    level.setBlockAndUpdate(
                            origin.offset(x, -1, z),
                            Blocks.SMOOTH_STONE
                                    .defaultBlockState()
                    );
                    for (int y = 0; y <= 5; y++) {
                        level.setBlockAndUpdate(
                                origin.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            player.setItemInHand(
                    InteractionHand.MAIN_HAND,
                    new ItemStack(Items.OAK_PLANKS, 64)
            );
            player.getInventory().setItem(
                    1,
                    new ItemStack(Items.OAK_DOOR)
            );
            player.getInventory().setItem(
                    2,
                    new ItemStack(Items.TORCH, 4)
            );
            player.inventoryMenu.broadcastChanges();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.setDeltaMovement(Vec3.ZERO);
            player.teleportTo(
                    origin.getX() + 0.5,
                    origin.getY(),
                    origin.getZ() + 0.5
            );
        }

        private void tick() {
            if (finished) {
                return;
            }
            helper.assertTrue(
                    helper.getTick() - createdAt
                            <= EXECUTION_TIMEOUT_TICKS,
                    "Physical placement-obstruction recovery timed out: "
                            + skill.checkpoint(
                                    context(0),
                                    parameters == null
                                            ? new BuildShelterStepParameters(
                                                    dev.mcai.companion
                                                            .waypoint
                                                            .DimensionRef
                                                            .OVERWORLD,
                                                    0,
                                                    ShelterScale.COMPACT
                                            )
                                            : parameters
                            ).payload()
            );
            try {
                final SemanticObservation observation =
                        publishObservation();
                if (!started) {
                    parameters =
                            new BuildShelterStepParameters(
                                    dev.mcai.companion.waypoint
                                            .DimensionRef.parse(
                                                    observation.body()
                                                            .dimensionId()
                                            ),
                                    observation.sequence(),
                                    ShelterScale.COMPACT
                            );
                    final Optional<dev.mcai.companion.skill.SkillFailure>
                            rejected = skill.preconditions(
                                    context(
                                            observation.sequence()
                                    ),
                                    parameters
                            );
                    helper.assertTrue(
                            rejected.isEmpty(),
                            "Placement-recovery skill start rejected: "
                                    + rejected
                    );
                    skill.start(
                            context(observation.sequence()),
                            parameters
                    );
                    started = true;
                }

                final SkillTickResult result = skill.tick(
                        context(observation.sequence()),
                        parameters
                );
                helper.assertTrue(
                        result.status()
                                != SkillTickResult.Status.FAILED,
                        "Placement-recovery skill failed: "
                                + result.failure()
                                + ", checkpoint="
                                + skill.checkpoint(
                                        context(
                                                observation.sequence()
                                        ),
                                        parameters
                                ).payload()
                );
                if (result.status()
                        == SkillTickResult.Status.COMPLETED) {
                    /*
                     * One builder invocation is an atomic local batch. The
                     * production supervisor starts the same persisted plan
                     * again from a fresh observation when work remains.
                     */
                    started = false;
                }
                if (cow == null
                        && skill.activePlan().isPresent()
                        && skill.confirmedStepCount()
                                >= minimumConfirmedBeforeObstruction
                        && skill.executingStepForDiagnostics()
                                .isPresent()) {
                    spawnLateObstruction();
                }
                observeRepair();
                verifyRecoveredPlacement();
            } finally {
                if (!finished) {
                    core.postServerTick();
                }
            }
        }

        private SemanticObservation publishObservation() {
            final SemanticObservation observation =
                    sampler.sample(player);
            shelterFrames.publish(observation);
            coreFrames.publish(observation);
            return observation;
        }

        private void spawnLateObstruction() {
            final ShelterBuildStep step =
                    skill.executingStepForDiagnostics()
                            .orElseThrow();
            helper.assertTrue(
                    step.role() == ShelterStepRole.LOWER_WALL,
                    "First physical shelter step was not a lower wall: "
                            + step
            );
            blockedTarget = new BlockPos(
                    step.target().x(),
                    step.target().y(),
                    step.target().z()
            );
            confirmedAtObstruction =
                    skill.confirmedStepCount();
            originalPlanId = skill.activePlan()
                    .orElseThrow()
                    .planId();
            helper.assertTrue(
                    helper.getLevel()
                            .getBlockState(blockedTarget)
                            .isAir(),
                    "Late obstruction target was already occupied"
            );
            final Cow created = EntityTypes.COW.create(
                    helper.getLevel(),
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                    created != null,
                    "Placement-recovery fixture could not create cow"
            );
            created.setBaby(false);
            created.setNoAi(true);
            created.setPos(
                    blockedTarget.getX() + 0.5,
                    blockedTarget.getY(),
                    blockedTarget.getZ() + 0.5
            );
            helper.assertTrue(
                    helper.getLevel().addFreshEntity(created),
                    "Placement-recovery fixture could not add cow"
            );
            cow = created;
        }

        private void observeRepair() {
            if (originalPlanId == null
                    || repairedPlanId != null
                    || skill.activePlan().isEmpty()
                    || originalPlanId.equals(
                            skill.activePlan()
                                    .orElseThrow()
                                    .planId()
                    )) {
                return;
            }
            repairedPlanId = skill.activePlan()
                    .orElseThrow()
                    .planId();
            planksAtRepair =
                    player.getInventory().countItem(
                            Items.OAK_PLANKS
                    );
            confirmedAtRepair =
                    skill.confirmedStepCount();
        }

        private void verifyRecoveredPlacement() {
            if (cow == null
                    || blockedTarget == null
                    || repairedPlanId == null
                    || skill.confirmedStepCount()
                            <= confirmedAtRepair
                    || player.getInventory().countItem(
                            Items.OAK_PLANKS
                    ) >= planksAtRepair) {
                return;
            }
            final AABB targetBox = new AABB(blockedTarget);
            helper.assertTrue(
                    cow.getBoundingBox().intersects(targetBox),
                    "The fixture animal left before local replanning "
                            + "could be proved"
            );
            helper.assertTrue(
                    helper.getLevel()
                            .getBlockState(blockedTarget)
                            .isAir(),
                    "The repaired plan still tried to place through "
                            + "the obstructed cell"
            );
            helper.assertTrue(
                    skill.activePlan().orElseThrow().steps()
                            .stream()
                            .noneMatch(step ->
                                    step.target().x()
                                            == blockedTarget.getX()
                                    && step.target().y()
                                            == blockedTarget.getY()
                                    && step.target().z()
                                            == blockedTarget.getZ()),
                    "The repaired plan retained the forbidden shell target"
            );
            helper.assertTrue(
                    skill.activePlan().orElseThrow().steps()
                            .stream()
                            .filter(step ->
                                    step.role()
                                            .usesStructuralMaterial())
                            .anyMatch(step ->
                                    helper.getLevel().getBlockState(
                                            new BlockPos(
                                                    step.target().x(),
                                                    step.target().y(),
                                                    step.target().z()
                                            )
                                    ).is(Blocks.OAK_PLANKS)),
                    "Repaired plan did not produce a physical plank"
            );
            helper.assertTrue(
                    cow.isAlive() && !cow.isRemoved(),
                    "Gentle obstruction recovery harmed the animal"
            );
            if (requirePartialBuild) {
                helper.assertTrue(
                        confirmedAtObstruction
                                >= minimumConfirmedBeforeObstruction,
                        "The obstruction was not introduced after the "
                                + "required partial build"
                );
            }
            finished = true;
            helper.succeed();
        }

        private SkillContext context(
                final long worldRevision
        ) {
            return new SkillContext(
                    1,
                    worldRevision,
                    helper.getTick(),
                    true,
                    true,
                    0.0
            );
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            core.quiesceNow();
            if (cow != null && !cow.isRemoved()) {
                cow.discard();
            }
        }
    }

    private static final class Scenario {
        private final GameTestHelper helper;
        private final ServerPlayer player;
        private final BlockPos origin;
        private final BlockPos target;
        private final BlockPos support;
        private final FairPerceptionSampler sampler =
                new FairPerceptionSampler();
        private final ServerShelterFrameSource shelterFrames;
        private final ServerOwnedInteractionSkillActuator interactions;
        private final Cow cow;
        private final long createdAt;

        private Phase phase = Phase.BLOCKED_PLACEMENT;
        private long cowObservedAt = -1;
        private int awaySamples;
        private boolean cleaned;

        private Scenario(
                final GameTestHelper helper,
                final ServerPlayer player,
                final BlockPos origin
        ) {
            this.helper = helper;
            this.player = player;
            this.origin = origin.immutable();
            target = origin.east(2);
            support = target.below();
            createdAt = helper.getTick();
            prepareFixture();
            shelterFrames = new ServerShelterFrameSource(
                    helper.getLevel().getServer(),
                    player.getUUID()
            );
            interactions = new ServerOwnedInteractionSkillActuator(
                    helper.getLevel().getServer(),
                    player.getUUID()
            );
            cow = createCow();
        }

        private void prepareFixture() {
            final var level = helper.getLevel();
            for (int x = -5; x <= 5; x++) {
                for (int z = -5; z <= 5; z++) {
                    final BlockPos floor =
                            origin.offset(x, -1, z);
                    level.setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 0; y <= 4; y++) {
                        level.setBlockAndUpdate(
                                origin.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            player.getInventory().setSelectedSlot(0);
            player.setItemInHand(
                    InteractionHand.MAIN_HAND,
                    new ItemStack(Items.OAK_PLANKS, 2)
            );
            player.teleportTo(
                    origin.getX() + 0.5,
                    origin.getY(),
                    origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
        }

        private Cow createCow() {
            final Cow created = EntityTypes.COW.create(
                    helper.getLevel(),
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                    created != null,
                    "Building occupancy fixture could not create cow"
            );
            created.setBaby(false);
            created.setNoAi(true);
            created.setPos(
                    target.getX() + 0.5,
                    target.getY(),
                    target.getZ() + 0.5
            );
            helper.assertTrue(
                    helper.getLevel().addFreshEntity(created),
                    "Building occupancy fixture could not add cow"
            );
            return created;
        }

        private void tick() {
            helper.assertTrue(
                    helper.getTick() - createdAt
                            <= ShelterFrame
                                    .MAXIMUM_RECENT_ENTITY_AGE_TICKS
                                + 300,
                    "Building occupancy contract timed out in " + phase
            );
            switch (phase) {
                case BLOCKED_PLACEMENT -> tickBlockedPlacement();
                case LOOK_AWAY -> tickLookAway();
                case CLEAR_PLACEMENT -> tickClearPlacement();
                case EXPIRE_MEMORY -> tickExpireMemory();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void tickBlockedPlacement() {
            lookAtSupport();
            final SemanticObservation observation =
                    publishObservation();
            final boolean cowVisible =
                    observation.visibleEntities().stream()
                            .anyMatch(entity ->
                                    entity.entityId().equals(
                                            cow.getUUID()
                                    )
                            );
            final VisibleBlockFace crosshair =
                    FirstPersonCrosshairSampler.sample(player)
                            .orElse(null);
            if (!cowVisible
                    || crosshair == null
                    || !isSupportTop(crosshair)) {
                return;
            }
            cowObservedAt = player.level().getGameTime();
            helper.assertTrue(
                    shelterFrames.current().orElseThrow()
                            .recentVisibleEntities().stream()
                            .anyMatch(entity ->
                                    entity.entity().entityId().equals(
                                            cow.getUUID()
                                    )
                            ),
                    "Fairly visible cow was not published to shelter memory"
            );
            final int before =
                    player.getMainHandItem().getCount();
            final ActionOutcome blocked = interactions.useOnBlock(
                    ActionHand.MAIN_HAND,
                    target(crosshair)
            );
            helper.assertTrue(
                    blocked == ActionOutcome.DISPATCHED
                            && player.getMainHandItem().getCount()
                                    == before
                            && helper.getLevel().getBlockState(target)
                                    .isAir(),
                    "Vanilla placement was not physically rejected by "
                            + "the cow: outcome=" + blocked
                            + ", hand=" + player.getMainHandItem()
                            + ", target="
                            + helper.getLevel().getBlockState(target)
            );
            phase = Phase.LOOK_AWAY;
        }

        private void tickLookAway() {
            player.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(origin.west(8))
            );
            player.setYHeadRot(player.getYRot());
            final SemanticObservation observation =
                    publishObservation();
            if (observation.visibleEntities().stream()
                    .anyMatch(entity ->
                            entity.entityId().equals(cow.getUUID())
                    )) {
                helper.assertTrue(
                        ++awaySamples <= 30,
                        "Cow remained in the semantic view after turning away"
                );
                return;
            }
            helper.assertTrue(
                    shelterFrames.current().orElseThrow()
                            .recentVisibleEntities().stream()
                            .anyMatch(entity ->
                                    entity.entity().entityId().equals(
                                            cow.getUUID()
                                    )
                            ),
                    "Turning away erased the fair short-term cow memory"
            );
            cow.discard();
            phase = Phase.CLEAR_PLACEMENT;
        }

        private void tickClearPlacement() {
            lookAtSupport();
            publishObservation();
            final VisibleBlockFace crosshair =
                    FirstPersonCrosshairSampler.sample(player)
                            .orElse(null);
            if (crosshair == null || !isSupportTop(crosshair)) {
                return;
            }
            final int before =
                    player.getMainHandItem().getCount();
            final ActionOutcome placed = interactions.useOnBlock(
                    ActionHand.MAIN_HAND,
                    target(crosshair)
            );
            helper.assertTrue(
                    placed == ActionOutcome.COMPLETED
                            && player.getMainHandItem().getCount()
                                    == before - 1
                            && helper.getLevel().getBlockState(target)
                                    .is(Blocks.OAK_PLANKS),
                    "The same vanilla placement did not succeed after "
                            + "the cow left: outcome=" + placed
                            + ", hand=" + player.getMainHandItem()
                            + ", target="
                            + helper.getLevel().getBlockState(target)
            );
            phase = Phase.EXPIRE_MEMORY;
        }

        private void tickExpireMemory() {
            player.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(origin.west(8))
            );
            player.setYHeadRot(player.getYRot());
            publishObservation();
            if (player.level().getGameTime() - cowObservedAt
                    <= ShelterFrame
                        .MAXIMUM_RECENT_ENTITY_AGE_TICKS) {
                return;
            }
            helper.assertTrue(
                    shelterFrames.current().orElseThrow()
                            .recentVisibleEntities().stream()
                            .noneMatch(entity ->
                                    entity.entity().entityId().equals(
                                            cow.getUUID()
                                    )
                            ),
                    "Expired cow remained in shelter memory as hidden radar"
            );
            phase = Phase.DONE;
            helper.succeed();
        }

        private SemanticObservation publishObservation() {
            final SemanticObservation observation =
                    sampler.sample(player);
            shelterFrames.publish(observation);
            return observation;
        }

        private void lookAtSupport() {
            player.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    new Vec3(
                            support.getX() + 0.5,
                            support.getY() + 1.0,
                            support.getZ() + 0.5
                    )
            );
            player.setYHeadRot(player.getYRot());
        }

        private boolean isSupportTop(
                final VisibleBlockFace face
        ) {
            return face.block().x() == support.getX()
                    && face.block().y() == support.getY()
                    && face.block().z() == support.getZ()
                    && face.face().equals("up");
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

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            if (!cow.isRemoved()) {
                cow.discard();
            }
        }
    }

    private enum Phase {
        BLOCKED_PLACEMENT,
        LOOK_AWAY,
        CLEAR_PLACEMENT,
        EXPIRE_MEMORY,
        DONE
    }
}
