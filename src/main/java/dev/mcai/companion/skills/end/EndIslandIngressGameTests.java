package dev.mcai.companion.skills.end;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.brain.BrainObservation;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.GameTestCompanionSpawn;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.model.DecisionEnvelope;
import dev.mcai.companion.model.DecisionKind;
import dev.mcai.companion.model.GatewayStatus;
import dev.mcai.companion.model.ModelGateway;
import dev.mcai.companion.model.ModelOutcome;
import dev.mcai.companion.model.PlannerInput;
import dev.mcai.companion.model.RequestedObservation;
import dev.mcai.companion.perception.FairPerceptionSampler;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.runtime.CompanionRuntime;
import dev.mcai.companion.runtime.ServerRuntime;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillSupervisor;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTest;
import net.minecraftforge.gametest.GameTestDontPrefix;
import net.minecraftforge.gametest.GameTestNamespace;

/**
 * Release-excluded physical gate for the natural End-island ingress skill.
 *
 * <p>The controller and structure remain in the Overworld. The same
 * headless survival player crosses a vanilla End-portal block, after which
 * this fixture performs no teleport, block write, entity write, chunk
 * preload, heightmap lookup, or hidden-terrain query. The production runtime
 * owns every movement and placement until a fresh fair frame proves that the
 * body is standing on natural End stone inside the arena-ready radius.</p>
 */
@GameTestNamespace(MinecraftAiCompanion.MOD_ID)
@GameTestDontPrefix
public final class EndIslandIngressGameTests {
    private static final String TEST_STRUCTURE = "forge:empty48x32x48";
    private static final BlockPos TEST_ORIGIN = new BlockPos(16, 8, 16);
    private static final int MAX_TICKS = 12_000;
    private static final int BODY_TIMEOUT_TICKS = 3_000;
    private static final int END_ENTRY_TIMEOUT_TICKS = 400;
    private static final int END_SETTLE_TIMEOUT_TICKS = 400;
    private static final int DYNAMIC_DRAGON_TIMEOUT_TICKS = 1_200;
    private static final int DYNAMIC_DRAGON_OBSERVATION_TICKS = 240;
    private static final int DYNAMIC_DRAGON_FIGHT_TIMEOUT_TICKS = 8_000;
    private static final int BRIDGE_BLOCKS = 64;

    private EndIslandIngressGameTests() {
    }

    @GameTest(
        name = "natural_end_island_ingress",
        environment = "exclusive_natural_end_island_ingress",
        structure = TEST_STRUCTURE,
        maxTicks = MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void naturalEndIslandIngress(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicReference<Scenario> scenario = new AtomicReference<>();
        helper.addCleanup(ignored -> {
            final Scenario current = scenario.get();
            if (current != null) {
                current.cleanup();
            } else {
                cleanupRuntime(server);
            }
        });

        final var spawn = GameTestCompanionSpawn.request(
                helper,
                TEST_ORIGIN
        );
        helper.assertTrue(
                spawn.accepted(),
                "Natural End ingress body spawn was rejected: "
                    + spawn.code()
        );
        scheduleEveryTick(helper, MAX_TICKS, () -> {
            final Scenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Natural End ingress body failed: " + status
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() <= BODY_TIMEOUT_TICKS,
                        "Natural End ingress body did not become active"
                );
                return;
            }
            final ServerRuntime runtime = CompanionRuntime.active()
                    .filter(candidate -> candidate.server() == server)
                    .orElseThrow(() -> helper.assertionException(
                            "Natural End ingress has no production runtime"
                    ));
            scenario.set(new Scenario(helper, runtime));
        });
    }

    @GameTest(
        name = "natural_end_dynamic_dragon_combat",
        environment = "exclusive_natural_end_dynamic_dragon_combat",
        structure = TEST_STRUCTURE,
        maxTicks = MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void naturalEndDynamicDragonCombat(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicReference<Scenario> scenario = new AtomicReference<>();
        helper.addCleanup(ignored -> {
            final Scenario current = scenario.get();
            if (current != null) {
                current.cleanup();
            } else {
                cleanupRuntime(server);
            }
        });
        final var spawn = GameTestCompanionSpawn.request(
                helper,
                TEST_ORIGIN
        );
        helper.assertTrue(
                spawn.accepted(),
                "Natural dynamic-dragon body spawn was rejected: "
                    + spawn.code()
        );
        scheduleEveryTick(helper, MAX_TICKS, () -> {
            final Scenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }
            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Natural dynamic-dragon body failed: " + status
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() <= BODY_TIMEOUT_TICKS,
                        "Natural dynamic-dragon body did not become active"
                );
                return;
            }
            final ServerRuntime runtime = CompanionRuntime.active()
                    .filter(candidate -> candidate.server() == server)
                    .orElseThrow(() -> helper.assertionException(
                            "Natural dynamic-dragon test has no runtime"
                    ));
            scenario.set(new Scenario(helper, runtime, true));
        });
    }

    private static void scheduleEveryTick(
            final GameTestHelper helper,
            final long finalTickInclusive,
            final Runnable action
    ) {
        for (long tick = helper.getTick();
                tick <= finalTickInclusive;
                tick++) {
            final long scheduledTick = tick;
            helper.runAtTickTime(scheduledTick, () -> {
                if (helper.getTick() < scheduledTick) {
                    throw helper.assertionException(
                            "GameTest callback ran before its scheduled tick"
                    );
                }
                action.run();
            });
        }
    }

    private static void cleanupRuntime(
            final net.minecraft.server.MinecraftServer server
    ) {
        CompanionRuntime.active()
                .filter(runtime -> runtime.server() == server)
                .ifPresent(runtime -> {
                    final GoalStatus status = runtime.goals()
                            .snapshot()
                            .status();
                    if (status == GoalStatus.RUNNING
                            || status == GoalStatus.CANCEL_PENDING) {
                        runtime.goals().markTerminal(
                                GoalStatus.SAFE_IDLE,
                                "natural_end_ingress_test_cleanup"
                        );
                    }
                    runtime.survival().reset();
                    runtime.coreActions().quiesceNow();
                    runtime.interactionActions().quiesceNow();
                    runtime.boatActions().quiesceNow();
                    runtime.minecartActions().quiesceNow();
                    runtime.skillSupervisor().abandonForSessionEnd();
                });
        if (AiPlayerManager.status(server).state()
                != SessionState.ABSENT) {
            AiPlayerManager.requestRemove(server);
        }
    }

    private static final class Scenario {
        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final UUID bodyId;
        private final boolean requireDynamicCombat;
        private Stage stage = Stage.ENTERING_END;
        private long stageStartedAt;
        private long ingressStartedAt = -1L;
        private long ingressStartObservationRevision = -1L;
        private long dynamicDragonStartedAt = -1L;
        private long dynamicCombatStartedAt = -1L;
        private Vec3 dynamicDragonStartPosition;
        private double dynamicDragonMaximumDisplacement;
        private Vec3 lastDynamicDragonPosition;
        private float lastDynamicDragonHealth;
        private boolean lastDynamicDragonLoaded;
        private double lastDynamicDragonDistance;
        private long firstFairDragonVisibleTick = -1L;
        private PerceptionVec3 firstFairDragonVisiblePosition;
        private Vec3 firstFairDragonVisibleLook;
        private double firstFairDragonVisibleDistance = Double.NaN;
        private final java.util.Set<String> dynamicDragonPhases =
                new java.util.LinkedHashSet<>();
        private int initialBlocks;
        private int initialBlockUseStat;
        private boolean cleaned;

        private Scenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this(helper, runtime, false);
        }

        private Scenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final boolean requireDynamicCombat
        ) {
            this.helper = helper;
            this.runtime = runtime;
            this.requireDynamicCombat = requireDynamicCombat;
            helper.assertTrue(
                    helper.getLevel().dimension().equals(Level.OVERWORLD),
                    "GameTest controller did not remain in the Overworld"
            );
            resetRuntime();
            final var goal = runtime.goals().setGoal(
                    "Reach the natural central End island",
                    GoalSource.RECOVERY
            );
            helper.assertTrue(
                    goal.accepted(),
                    "Natural End ingress goal was rejected: " + goal.code()
            );
            final ServerPlayer body = body();
            bodyId = body.getUUID();
            preparePreEntryBody(body);
            stageStartedAt = helper.getTick();
        }

        private void resetRuntime() {
            runtime.survival().reset();
            runtime.coreActions().quiesceNow();
            runtime.interactionActions().quiesceNow();
            runtime.boatActions().quiesceNow();
            runtime.minecartActions().quiesceNow();
            runtime.skillSupervisor().abandonForSessionEnd();
            /*
             * GameTest callbacks run before CompanionRuntime's server-tick
             * listener. Close only the high-level planner so a direct start
             * is not advanced twice on its admission tick; the fixture calls
             * the same supervisor, survival arbiter and player actuator from
             * the next tick onward. This is the established release-excluded
             * physical-gate boundary used by the embodiment suite.
             */
            runtime.brain().close();
            runtime.model().gateway().install(
                    new HoldingGameTestGateway()
            );
        }

        private void preparePreEntryBody(final ServerPlayer body) {
            body.stopRiding();
            if (body.isSleeping()) {
                body.stopSleepInBed(true, false);
            }
            body.setGameMode(GameType.SURVIVAL);
            body.getInventory().clearContent();
            body.getEnderChestInventory().clearContent();
            body.removeAllEffects();
            body.clearFire();
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.getFoodData().setSaturation(5.0F);
            body.setDeltaMovement(Vec3.ZERO);
            body.fallDistance = 0.0F;
            body.getInventory().setItem(
                    0,
                    /* The dynamic gate spends one bounded reserve opening
                     * natural pillar skirts and sky occlusions before the
                     * dragon is visible.  Use a durable, ordinary survival
                     * diamond pickaxe so the gate measures terrain/combat
                     * behavior rather than an exhausted single iron tool. */
                    new ItemStack(Items.DIAMOND_PICKAXE)
            );
            body.getInventory().setItem(
                    1,
                    new ItemStack(Items.IRON_SWORD)
            );
            body.getInventory().setItem(
                    2,
                    new ItemStack(Items.COOKED_BEEF, 16)
            );
            body.getInventory().setItem(
                    3,
                    new ItemStack(Items.WATER_BUCKET)
            );
            body.getInventory().setItem(
                    4,
                    new ItemStack(Items.BOW)
            );
            body.getInventory().setItem(
                    5,
                    new ItemStack(Items.ARROW, 64)
            );
            body.setItemSlot(
                    EquipmentSlot.HEAD,
                    new ItemStack(Items.IRON_HELMET)
            );
            body.setItemSlot(
                    EquipmentSlot.CHEST,
                    new ItemStack(Items.IRON_CHESTPLATE)
            );
            body.setItemSlot(
                    EquipmentSlot.LEGS,
                    new ItemStack(Items.IRON_LEGGINGS)
            );
            body.setItemSlot(
                    EquipmentSlot.FEET,
                    new ItemStack(Items.IRON_BOOTS)
            );
            body.setItemSlot(
                    EquipmentSlot.OFFHAND,
                    new ItemStack(Items.SHIELD)
            );
            body.getInventory().setSelectedSlot(0);
            helper.assertTrue(
                    body.getInventory().add(
                            new ItemStack(Items.COBBLESTONE, BRIDGE_BLOCKS)
                    ),
                    "Natural End ingress could not provision owned blocks"
            );
            initialBlocks = body.getInventory().countItem(
                    Items.COBBLESTONE
            );
            initialBlockUseStat = body.getStats().getValue(
                    Stats.ITEM_USED.get(Items.COBBLESTONE)
            );

            /*
             * Fixture setup ends at this point. Placing a vanilla portal
             * under the body and resynchronizing that same pre-entry pose is
             * the established GameTest entry boundary. Once Level.END is
             * observed below, this class never writes either world or moves
             * the player.
             */
            final BlockPos entry = body.blockPosition();
            helper.getLevel().setBlockAndUpdate(
                    entry,
                    Blocks.END_PORTAL.defaultBlockState()
            );
            body.teleportTo(
                    entry.getX() + 0.5D,
                    entry.getY(),
                    entry.getZ() + 0.5D
            );
            body.setDeltaMovement(Vec3.ZERO);
        }

        private void tick() {
            helper.assertTrue(
                    helper.getLevel().dimension().equals(Level.OVERWORLD),
                    "GameTest controller left the Overworld"
            );
            assertNoHumanPlayers();
            final ServerPlayer body = body();
            helper.assertTrue(
                    bodyId.equals(body.getUUID()),
                    "Natural End ingress replaced the companion body"
            );
            helper.assertTrue(
                    body.isAlive()
                        && !body.isDeadOrDying()
                        && body.getHealth() > 0.0F,
                    "Natural End ingress body died"
            );
            switch (stage) {
                case ENTERING_END -> waitForEndEntry(body);
                case SETTLING_END -> waitForStableEndFrame(body);
                case RUNNING -> waitForIngress(body);
                case VERIFYING_DYNAMIC_DRAGON -> verifyDynamicDragon(body);
                case FIGHTING_DYNAMIC_DRAGON -> fightDynamicDragon(body);
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForEndEntry(final ServerPlayer body) {
            if (body.level().dimension().equals(Level.END)) {
                stage = Stage.SETTLING_END;
                stageStartedAt = helper.getTick();
                return;
            }
            helper.assertTrue(
                    helper.getTick() - stageStartedAt
                        <= END_ENTRY_TIMEOUT_TICKS,
                    "Companion did not cross the vanilla End portal"
            );
        }

        private void waitForStableEndFrame(final ServerPlayer body) {
            helper.assertTrue(
                    body.level().dimension().equals(Level.END),
                    "Companion left the End before ingress started"
            );
            final var frame = runtime.coreFrames().current();
            if (!body.onGround()
                    || frame.isEmpty()
                    || !DimensionRef.END.equals(
                            frame.orElseThrow().dimension()
                    )
                    || !frame.orElseThrow().onGround()) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt
                            <= END_SETTLE_TIMEOUT_TICKS,
                        "Natural End spawn never produced a grounded fair "
                            + "frame: " + diagnostics()
                );
                return;
            }
            final BrainObservation observation = runtime.observations()
                    .observe(runtime.goals().snapshot());
            final SkillContext sampled = observation.skillContext();
            final SkillContext connected = new SkillContext(
                    sampled.goalRevision(),
                    sampled.worldRevision(),
                    Integer.toUnsignedLong(
                            runtime.server().getTickCount()
                    ),
                    sampled.hardcore(),
                    true,
                    sampled.riskScore()
            );
            final DecisionEnvelope decision = new DecisionEnvelope(
                    "gametest-reach-end-island-" + helper.getTick(),
                    connected.worldRevision(),
                    connected.goalRevision(),
                    DecisionKind.START_SKILL,
                    EndSkills.REACH_END_ISLAND,
                    List.of(),
                    RequestedObservation.none(),
                    "",
                    1.0
            );
            final SkillSupervisor.StartOutcome outcome =
                    runtime.skillSupervisor().start(
                            decision,
                            connected
                    );
            helper.assertTrue(
                    outcome.accepted(),
                    "Production reach_end_island rejected natural spawn: "
                        + outcome.failure()
                            .map(failure -> failure.code())
                            .orElse("unknown")
                        + "; " + diagnostics()
            );
            ingressStartedAt = helper.getTick();
            ingressStartObservationRevision = frame.orElseThrow()
                    .observationRevision();
            stage = Stage.RUNNING;
        }

        private void waitForIngress(final ServerPlayer body) {
            helper.assertTrue(
                    body.level().dimension().equals(Level.END),
                    "Companion left the End during island ingress"
            );
            final SkillSupervisor.Snapshot snapshot = advanceSkill();
            if (snapshot.state() == SkillSupervisor.State.RUNNING
                    || snapshot.state()
                        == SkillSupervisor.State.CANCEL_PENDING) {
                return;
            }
            helper.assertTrue(
                    snapshot.state() == SkillSupervisor.State.COMPLETED,
                    "Production reach_end_island did not complete: "
                        + diagnostics()
            );
            final var frame = runtime.coreFrames().current()
                    .orElseThrow(() -> helper.assertionException(
                            "Completed ingress has no fair body frame"
                    ));
            final BlockPos support = body.blockPosition().below();
            final boolean fairCurrentEndStone = frame.visibleBlockFaces()
                    .stream()
                    .anyMatch(face ->
                            face.block().x() == support.getX()
                                && face.block().y() == support.getY()
                                && face.block().z() == support.getZ()
                                && face.blockTypeId().equals(
                                    "minecraft:end_stone"
                                )
                                && face.face().equals("up")
                    );
            final int remainingBlocks = body.getInventory().countItem(
                    Items.COBBLESTONE
            );
            final int usedBlocks = body.getStats().getValue(
                    Stats.ITEM_USED.get(Items.COBBLESTONE)
            ) - initialBlockUseStat;
            helper.assertTrue(
                    frame.observationRevision()
                        > ingressStartObservationRevision,
                    "Ingress completed without a post-start fair frame"
            );
            helper.assertTrue(
                    EndArenaTopology.insideArenaReadyRadius(
                            frame.position()
                    ),
                    "Ingress completed outside the arena-ready radius: "
                        + frame.position()
            );
            helper.assertTrue(
                    fairCurrentEndStone
                        && body.level().getBlockState(support)
                            .is(Blocks.END_STONE),
                    "Ingress completion lacked exact natural End-stone "
                        + "support: " + diagnostics()
            );
            /* A natural seed may expose a continuous End-stone route from
             * the entry platform, so no bridge placement is required. If
             * the route encounters a gap, the production bridge child must
             * still consume an ordinary owned block through the vanilla
             * item-use path; never accept a placement-less gap crossing. */
            helper.assertTrue(
                    remainingBlocks <= initialBlocks
                        && (remainingBlocks == initialBlocks
                            || usedBlocks >= 1),
                    "Ingress changed owned-block accounting without a "
                        + "vanilla placement: initial=" + initialBlocks
                        + ", remaining=" + remainingBlocks
                        + ", itemUseStat=" + usedBlocks
            );
            helper.assertTrue(
                    ingressStartedAt >= 0L
                        && helper.getTick() > ingressStartedAt,
                    "Ingress completed without a physical runtime interval"
            );
            dynamicDragonStartedAt = helper.getTick();
            dynamicDragonStartPosition = null;
            dynamicDragonMaximumDisplacement = 0.0D;
            lastDynamicDragonPosition = null;
            lastDynamicDragonHealth = -1.0F;
            lastDynamicDragonLoaded = false;
            lastDynamicDragonDistance = Double.NaN;
            firstFairDragonVisibleTick = -1L;
            firstFairDragonVisiblePosition = null;
            firstFairDragonVisibleLook = null;
            firstFairDragonVisibleDistance = Double.NaN;
            dynamicDragonPhases.clear();
            stage = Stage.VERIFYING_DYNAMIC_DRAGON;
        }

        /**
         * Verify that the vanilla End fight owns a live, AI-enabled dragon
         * after ingress. This is intentionally a presence/motion gate only;
         * it does not spawn, reposition, freeze, damage, or otherwise mutate
         * the manager dragon. Static/no-AI dragon fixtures are not accepted
         * as evidence for this check.
         */
        private void verifyDynamicDragon(final ServerPlayer body) {
            helper.assertTrue(
                    body.level().dimension().equals(Level.END),
                    "Dynamic End dragon gate lost the End dimension"
            );
            final ServerLevel end = (ServerLevel) body.level();
            final var fight = end.getDragonFight();
            final List<? extends EnderDragon> dragons = end.getDragons();
            if (fight != null && !dragons.isEmpty()) {
                for (final EnderDragon dragon : dragons) {
                    lastDynamicDragonPosition = dragon.position();
                    lastDynamicDragonHealth = dragon.getHealth();
                    lastDynamicDragonLoaded = end.isLoaded(
                            dragon.blockPosition()
                    );
                    lastDynamicDragonDistance = body.distanceTo(dragon);
                    helper.assertTrue(
                            !dragon.isNoAi(),
                            "Natural End fight exposed a frozen/no-AI dragon"
                    );
                    dynamicDragonPhases.add(
                            dragon.getPhaseManager()
                                    .getCurrentPhase()
                                    .getPhase()
                                    .toString()
                    );
                    if (dynamicDragonStartPosition == null) {
                        dynamicDragonStartPosition = dragon.position();
                    }
                    dynamicDragonMaximumDisplacement = Math.max(
                            dynamicDragonMaximumDisplacement,
                            dynamicDragonStartPosition.distanceTo(
                                    dragon.position()
                            )
                    );
                }
                if (helper.getTick() - dynamicDragonStartedAt
                        >= DYNAMIC_DRAGON_OBSERVATION_TICKS
                        && dynamicDragonMaximumDisplacement >= 2.0D
                        && !dynamicDragonPhases.isEmpty()) {
                    if (requireDynamicCombat) {
                        startDynamicDragonCombat(body);
                    } else {
                        helper.succeed();
                        stage = Stage.DONE;
                    }
                    return;
                }
            }
            helper.assertTrue(
                    helper.getTick() - dynamicDragonStartedAt
                            <= DYNAMIC_DRAGON_TIMEOUT_TICKS,
                    "Natural End fight did not expose a moving AI-enabled "
                        + "dragon: fight=" + fight
                        + ", dragons=" + dragons.size()
                        + ", displacement="
                        + dynamicDragonMaximumDisplacement
                        + ", phases=" + dynamicDragonPhases
            );
        }

        private void startDynamicDragonCombat(final ServerPlayer body) {
            final BrainObservation observation = runtime.observations()
                    .observe(runtime.goals().snapshot());
            final SkillContext sampled = observation.skillContext();
            final SkillContext connected = new SkillContext(
                    sampled.goalRevision(),
                    sampled.worldRevision(),
                    Integer.toUnsignedLong(runtime.server().getTickCount()),
                    sampled.hardcore(),
                    true,
                    sampled.riskScore()
            );
            final DecisionEnvelope decision = new DecisionEnvelope(
                    "gametest-natural-dynamic-dragon-" + helper.getTick(),
                    connected.worldRevision(),
                    connected.goalRevision(),
                    DecisionKind.START_SKILL,
                    "fight_ender_dragon",
                    List.of(),
                    RequestedObservation.none(),
                    "",
                    1.0
            );
            final SkillSupervisor.StartOutcome outcome =
                    runtime.skillSupervisor().start(
                            decision,
                            connected
                    );
            helper.assertTrue(
                    outcome.accepted(),
                    "Natural dynamic-dragon fight was rejected: "
                        + outcome.failure().map(failure -> failure.code())
                            .orElse("unknown")
                        + "; " + diagnostics()
            );
            dynamicCombatStartedAt = helper.getTick();
            stage = Stage.FIGHTING_DYNAMIC_DRAGON;
        }

        private void fightDynamicDragon(final ServerPlayer body) {
            helper.assertTrue(
                    body.level().dimension().equals(Level.END),
                    "Natural dynamic-dragon fight left the End"
            );
            recordFairDragonVisibility(body);
            final SkillSupervisor.Snapshot snapshot = advanceSkill();
            if (snapshot.state() == SkillSupervisor.State.RUNNING
                    || snapshot.state() == SkillSupervisor.State.CANCEL_PENDING) {
                helper.assertTrue(
                        helper.getTick() - dynamicCombatStartedAt
                            <= DYNAMIC_DRAGON_FIGHT_TIMEOUT_TICKS,
                        "Natural dynamic-dragon fight exceeded its bound: "
                            + diagnostics()
                );
                return;
            }
            helper.assertTrue(
                    snapshot.state() == SkillSupervisor.State.COMPLETED,
                    "Natural dynamic-dragon fight did not complete: "
                        + diagnostics()
            );
            helper.succeed();
            stage = Stage.DONE;
        }

        private void recordFairDragonVisibility(final ServerPlayer body) {
            if (firstFairDragonVisibleTick >= 0L) {
                return;
            }
            final var visible = new FairPerceptionSampler().sample(body)
                    .visibleEntities()
                    .stream()
                    .filter(entity ->
                            entity.entityTypeId().equals(
                                    "minecraft:ender_dragon"
                            )
                    )
                    .findFirst();
            if (visible.isEmpty()) {
                return;
            }
            firstFairDragonVisibleTick = helper.getTick();
            firstFairDragonVisiblePosition = visible.orElseThrow()
                    .position();
            firstFairDragonVisibleLook = body.getLookAngle();
            firstFairDragonVisibleDistance = visible.orElseThrow()
                    .distance();
        }

        private SkillSupervisor.Snapshot advanceSkill() {
            final BrainObservation observation = runtime.observations()
                    .observe(runtime.goals().snapshot());
            if (runtime.skillSupervisor()
                    .consumeActiveSkillEndedHandoff()) {
                runtime.survival().onActiveSkillEnded();
            }
            final var survival = runtime.survival().tick(
                    runtime.skillSupervisor()
                        .activeSkillManagesVisibleHostileProximity(),
                    runtime.skillSupervisor()
                        .activeSkillManagesPhysicalContactThreats(),
                    runtime.skillSupervisor()
                        .activeSkillManagesVisibleProjectileThreats()
            );
            final SkillSupervisor.Snapshot snapshot;
            if (survival.intervened()) {
                snapshot = runtime.skillSupervisor().snapshot();
            } else {
                final SkillContext sampled = observation.skillContext();
                snapshot = runtime.skillSupervisor().tick(
                        new SkillContext(
                                sampled.goalRevision(),
                                sampled.worldRevision(),
                                Integer.toUnsignedLong(
                                    runtime.server().getTickCount()
                                ),
                                sampled.hardcore(),
                                true,
                                sampled.riskScore()
                        )
                );
            }
            runtime.coreActions().postServerTick();
            return snapshot;
        }

        private ServerPlayer body() {
            return AiPlayerManager.onlinePlayer(runtime.server())
                    .orElseThrow(() -> helper.assertionException(
                            "Natural End ingress body disappeared"
                    ));
        }

        private void assertNoHumanPlayers() {
            final long humans = runtime.server().getPlayerList()
                    .getPlayers()
                    .stream()
                    .filter(player -> !player.getUUID().equals(bodyId))
                    .count();
            helper.assertTrue(
                    humans == 0L,
                    "Natural End ingress retained " + humans
                        + " human player(s)"
            );
        }

        private String diagnostics() {
            final var snapshot = runtime.skillSupervisor().snapshot();
            final var body = AiPlayerManager.onlinePlayer(runtime.server());
            return "stage=" + stage
                    + ", supervisor=" + snapshot
                    + ", checkpoint=" + runtime.skillSupervisor()
                        .lastCheckpointPayload().orElse("none")
                    + ", dimension=" + body.map(player ->
                            player.level().dimension().identifier()
                                .toString()
                        ).orElse("offline")
                    + ", body=" + body.map(ServerPlayer::position)
                        .orElse(null)
                    + ", onGround=" + body.map(ServerPlayer::onGround)
                        .orElse(false)
                    + ", blocks=" + body.map(player ->
                            player.getInventory().countItem(
                                Items.COBBLESTONE
                            )
                        ).orElse(-1)
                    + ", frame=" + runtime.coreFrames().current()
                        .map(frame -> "revision="
                            + frame.observationRevision()
                            + ", navRevision="
                            + frame.navigation().revision()
                            + ", feet=" + frame.feet()
                            + ", supportVoxel=" + frame.navigation()
                                .voxelAt(frame.feet().below())
                            + ", feetVoxel=" + frame.navigation()
                                .voxelAt(frame.feet())
                            + ", headVoxel=" + frame.navigation()
                                .voxelAt(frame.feet().above(2))
                            + ", look=" + frame.lookDirection()
                            + ", position=" + frame.position()
                            + ", faces=" + frame.visibleBlockFaces())
                        .orElse("unavailable")
                    + ", terrain=" + endTerrainProfile(body)
                    + ", localTerrain=" + endLocalTerrainSlice(body)
                    + ", dragon=" + lastDynamicDragonPosition
                    + ", dragonHealth=" + lastDynamicDragonHealth
                    + ", dragonLoaded=" + lastDynamicDragonLoaded
                    + ", dragonDistance=" + lastDynamicDragonDistance
                    + ", firstFairDragonVisibleTick="
                        + firstFairDragonVisibleTick
                    + ", firstFairDragonVisiblePosition="
                        + firstFairDragonVisiblePosition
                    + ", firstFairDragonVisibleLook="
                        + firstFairDragonVisibleLook
                    + ", firstFairDragonVisibleDistance="
                        + firstFairDragonVisibleDistance
                    + ", freshSampler=" + body.map(player ->
                            new FairPerceptionSampler().sample(player)
                                    .visibleEntities()
                                    .stream()
                                    .map(entity -> entity.entityTypeId()
                                            + "@" + entity.position())
                                    .toList()
                        ).orElse(List.of());
        }

        private String endTerrainProfile(
                final Optional<ServerPlayer> body
        ) {
            if (body.isEmpty()
                    || !body.orElseThrow().level().dimension()
                        .equals(Level.END)) {
                return "unavailable";
            }
            final ServerLevel end = (ServerLevel) body.orElseThrow().level();
            final StringBuilder profile = new StringBuilder();
            for (int x = 112; x >= 0; x--) {
                final BlockPos position = new BlockPos(x, 49, 0);
                if (!end.getBlockState(position).isAir()) {
                    if (!profile.isEmpty()) {
                        profile.append(';');
                    }
                    profile.append(x).append('=')
                            .append(end.getBlockState(position)
                                    .getBlock().toString());
                }
            }
            return profile.toString();
        }

        /** Diagnostic-only local terrain slice; never used by production AI. */
        private String endLocalTerrainSlice(
                final Optional<ServerPlayer> body
        ) {
            if (body.isEmpty()
                    || !body.orElseThrow().level().dimension()
                        .equals(Level.END)) {
                return "unavailable";
            }
            final ServerLevel end = (ServerLevel) body.orElseThrow().level();
            final BlockPos feet = body.orElseThrow().blockPosition();
            final StringBuilder slice = new StringBuilder();
            for (int y = feet.getY() - 1; y <= feet.getY() + 2; y++) {
                if (!slice.isEmpty()) {
                    slice.append('|');
                }
                slice.append('y').append(y).append(':');
                for (int z = feet.getZ() - 4; z <= feet.getZ() + 4; z++) {
                    if (z != feet.getZ() - 4) {
                        slice.append('/');
                    }
                    for (int x = feet.getX() - 8;
                            x <= feet.getX() + 8;
                            x++) {
                        final var block = end.getBlockState(
                                new BlockPos(x, y, z)
                        ).getBlock();
                        slice.append(block == Blocks.OBSIDIAN ? 'O'
                                : block == Blocks.END_STONE ? 'E' : '.');
                    }
                }
            }
            return slice.toString();
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            cleanupRuntime(runtime.server());
        }
    }

    private enum Stage {
        ENTERING_END,
        SETTLING_END,
        RUNNING,
        VERIFYING_DYNAMIC_DRAGON,
        FIGHTING_DYNAMIC_DRAGON,
        DONE
    }

    private static final class HoldingGameTestGateway
            implements ModelGateway {
        private final CompletableFuture<ModelOutcome> pending =
                new CompletableFuture<>();
        private boolean requesting;
        private boolean closed;

        @Override
        public CompletionStage<ModelOutcome> decide(
                final PlannerInput input
        ) {
            if (closed) {
                throw new IllegalStateException(
                        "GameTest gateway is closed"
                );
            }
            requesting = true;
            return pending;
        }

        @Override
        public void cancelForGoalRevision(
                final long currentGoalRevision
        ) {
            // A directly authorized production skill owns this fixture.
        }

        @Override
        public GatewayStatus status() {
            if (closed) {
                return GatewayStatus.CLOSED;
            }
            return requesting
                    ? GatewayStatus.REQUESTING
                    : GatewayStatus.IDLE;
        }

        @Override
        public void close() {
            closed = true;
            pending.cancel(false);
        }
    }
}
