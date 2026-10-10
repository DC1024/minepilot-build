package dev.mcai.companion.embodiment;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.FairPlayerActuator;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.brain.BrainObservation;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.model.DecisionEnvelope;
import dev.mcai.companion.model.DecisionKind;
import dev.mcai.companion.model.GatewayStatus;
import dev.mcai.companion.model.ModelGateway;
import dev.mcai.companion.model.ModelOutcome;
import dev.mcai.companion.model.ObservationKind;
import dev.mcai.companion.model.PlannerInput;
import dev.mcai.companion.model.RequestedObservation;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.mixin.WorldGenSettingsAccessor;
import dev.mcai.companion.runtime.CompanionRuntime;
import dev.mcai.companion.runtime.RuntimeTickMetrics;
import dev.mcai.companion.runtime.ServerRuntime;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillSupervisor;
import dev.mcai.companion.skills.building.DynamicShelterSkills;
import dev.mcai.companion.skills.building.BuildingGameTests;
import dev.mcai.companion.skills.bridging.BridgeSkills;
import dev.mcai.companion.skills.combat.CombatSkills;
import dev.mcai.companion.skills.core.CoreSkills;
import dev.mcai.companion.skills.core.EmergencySurvivalController;
import dev.mcai.companion.skills.core.TravelSkills;
import dev.mcai.companion.skills.exploration.ExplorationSkills;
import dev.mcai.companion.skills.farming.FarmingGameTests;
import dev.mcai.companion.skills.farming.FarmingSkills;
import dev.mcai.companion.skills.foundation.FoundationGameTests;
import dev.mcai.companion.skills.foundation
        .ShelterMaterialExplorationGameTests;
import dev.mcai.companion.skills.foundation
        .WorkstationPrerequisiteGameTests;
import dev.mcai.companion.skills.gathering.ResourceGatheringSkills;
import dev.mcai.companion.skills.inventory.InventoryGameTests;
import dev.mcai.companion.skills.inventory.InventorySkills;
import dev.mcai.companion.skills.interaction.FairInteractionSkills;
import dev.mcai.companion.skills.loot.LootSkills;
import dev.mcai.companion.skills.loot.LootGameTests;
import dev.mcai.companion.skills.loot.SecureEnderPearlReserveSkill;
import dev.mcai.companion.communication.LiveModelChatGameTests;
import dev.mcai.companion.skills.menu.MenuGameTests;
import dev.mcai.companion.skills.redstone.RedstoneGameTests;
import dev.mcai.companion.skills.menu.MenuSkills;
import dev.mcai.companion.skills.memory.MemorySkills;
import dev.mcai.companion.skills.parkour.ParkourSkills;
import dev.mcai.companion.skills.portal.PortalBuildSkills;
import dev.mcai.companion.skills.portal.PortalSkills;
import dev.mcai.companion.skills.progress.ProgressSkills;
import dev.mcai.companion.skills.sleeping.SleepSkills;
import dev.mcai.companion.skills.survey.SurveySkills;
import dev.mcai.companion.skills.stronghold.EyeTraceSnapshot;
import dev.mcai.companion.skills.stronghold
        .SearchObservedStrongholdPortalRoomSkill;
import dev.mcai.companion.skills.stronghold.StrongholdSkills;
import dev.mcai.companion.waypoint.DimensionRef;
import dev.mcai.companion.skills.transport.BoatTransportSkills;
import dev.mcai.companion.skills.transport.MinecartTransportSkills;
import dev.mcai.companion.skin.AiProfileMarker;
import dev.mcai.companion.progression.SurvivalMilestone;
import dev.mcai.companion.progression.ServerShelterEvidenceVerifier;
import dev.mcai.companion.progression.ServerFoundationEvidenceVerifier;
import dev.mcai.companion.progression.VerifiedFixtureLocation;
import dev.mcai.companion.progression.VerifiedFoundationEvidence;
import dev.mcai.companion.progression.VerifiedShelterEvidence;
import dev.mcai.companion.world.CompanionWorldData;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.stats.Stats;
import net.minecraftforge.gametest.GameTest;
import net.minecraftforge.gametest.GameTestDontPrefix;
import net.minecraftforge.gametest.GameTestNamespace;

/**
 * Runs inside the real dedicated GameTest server, not a mocked JVM.
 */
@GameTestNamespace(MinecraftAiCompanion.MOD_ID)
@GameTestDontPrefix
public final class EmbodimentGameTests {
    /**
     * Keeps every overworld fixture, including the separated stronghold-eye
     * triangulation baseline, inside the GameTest barrier.  The former
     * 3x3x3 default put the companion against the south wall, so a valid
     * vanilla movement input could advance only 0.2 blocks.
     */
    private static final String TEST_STRUCTURE = "forge:empty160x24x192";
    private static final BlockPos TEST_ORIGIN = new BlockPos(80, 8, 80);
    private static final long TEST_MAX_TICKS = 12_000L;
    private static final String FOCUSED_TEST_STRUCTURE =
        "forge:empty48x32x48";
    private static final BlockPos FOCUSED_TEST_ORIGIN =
        new BlockPos(16, 8, 16);
    private static final int FOCUSED_BODY_START_TIMEOUT_TICKS = 3_000;
    private static final int FOCUSED_SIMULATION_TICKET_TIMEOUT_TICKS =
        3_000;
    private static final int CROSS_DIMENSION_TICKET_TEST_MAX_TICKS =
        20_000;
    private static final long FOCUSED_SIMULATION_TICKET_TIMEOUT_NANOS =
        java.time.Duration.ofSeconds(15).toNanos();
    private static final long FOCUSED_ASYNC_CHUNK_YIELD_NANOS =
        java.time.Duration.ofMillis(1).toNanos();
    private static final int WATER_TEST_MAX_TICKS = 4_000;
    private static final int PARKOUR_TEST_MAX_TICKS = 5_000;
    private static final int TRAVEL_DETOUR_TEST_MAX_TICKS = 5_000;
    private static final int PORTAL_RETURN_TEST_MAX_TICKS = 6_000;
    private static final int BLAZE_TEST_MAX_TICKS = 20_000;
    private static final int BLAZE_RESERVE_TEST_MAX_TICKS = 20_000;
    private static final int ENDER_SINGLE_TEST_MAX_TICKS = 4_000;
    private static final int ENDER_RESERVE_TEST_MAX_TICKS = 30_000;
    private static final int STRONGHOLD_TEST_MAX_TICKS = 8_000;
    private static final int STRONGHOLD_REACH_TEST_MAX_TICKS = 10_000;
    private static final int END_PORTAL_TEST_MAX_TICKS = 5_000;
    private static final int END_VICTORY_TEST_MAX_TICKS = 8_000;
    private static final int OFFLINE_DRAGON_FIGHT_WINDOW_TICKS = 4_000;
    /* The production cue is valid through tick 40 inclusive; allow a few
     * semantic publication ticks for the fixture to observe its expiry. */
    private static final int RECENT_DAMAGE_SETTLE_MAX_TICKS = 100;
    private static final double PHYSICAL_CAGE_BREAK_REACH = 4.5;
    private static final double PHYSICAL_END_CRYSTAL_STANDOFF = 12.25;
    private static final int EMERGENCY_ENDERMAN_TEST_MAX_TICKS = 1_200;
    private static final int EMERGENCY_SLIME_TEST_MAX_TICKS = 1_600;
    private static final int EMERGENCY_GOLEM_TEST_MAX_TICKS = 3_000;
    private static final int EMERGENCY_HORDE_TEST_MAX_TICKS = 2_000;
    private static final double EMERGENCY_SAFE_STANDOFF_DISTANCE = 8.0D;

    private EmbodimentGameTests() {
    }

    @GameTest(
        name = "realtime_clock_contract",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 320,
        padding = 8
    )
    public static void realtimeClockContract(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.realtimeGameTest")) {
            helper.succeed();
            return;
        }
        final long started = System.nanoTime();
        final AtomicLong maximumScheduleLagNanos = new AtomicLong();
        /*
         * Real servers spend time doing work inside each tick. The throttle
         * must count that work toward the 50 ms budget instead of adding a
         * fresh 50 ms sleep after it. A small deterministic load exposes the
         * old cumulative-drift bug without depending on model/network speed.
         */
        for (long tick = 1L; tick <= 260L; tick++) {
            final long scheduledTick = tick;
            helper.runAtTickTime(scheduledTick, () -> {
                LockSupport.parkNanos(
                        java.time.Duration.ofMillis(8).toNanos()
                );
                if (scheduledTick >= 20L) {
                    final long scheduleLag = System.nanoTime()
                        - helper.getLevel().getServer()
                            .getNextTickTime();
                    maximumScheduleLagNanos.accumulateAndGet(
                            Math.max(0L, scheduleLag),
                            Math::max
                    );
                }
            });
        }
        helper.runAtTickTime(261, () -> {
            final long elapsed = System.nanoTime() - started;
            helper.assertTrue(
                    elapsed >= java.time.Duration.ofSeconds(11)
                        .toNanos(),
                    "Real-time GameTest clock advanced 261 ticks in only "
                        + java.time.Duration.ofNanos(elapsed).toMillis()
                        + " ms"
            );
            helper.assertTrue(
                    elapsed <= java.time.Duration.ofMillis(13_750)
                        .toNanos(),
                    "Tick work was added after the 50 ms budget; 261 ticks "
                        + "took "
                        + java.time.Duration.ofNanos(elapsed).toMillis()
                        + " ms"
            );
            helper.assertTrue(
                    maximumScheduleLagNanos.get()
                        <= java.time.Duration.ofMillis(750).toNanos(),
                    "Real-time GameTest schedule accumulated "
                        + java.time.Duration.ofNanos(
                                maximumScheduleLagNanos.get()
                            ).toMillis()
                        + " ms of lag"
            );
            helper.succeed();
        });
    }

    /**
     * Physical regression for the field failure where a shielded companion
     * guarded forever and let one Enderman kill it. The production arbiter,
     * emergency controller, menu equipment transaction and melee actuator all
     * run on the real headless survival player; this test never damages the
     * target or edits either combatant after the encounter starts. Endermen
     * may naturally teleport, so a clean, damage-free separation is a valid
     * defensive outcome alongside a defeated target.
     */
    @GameTest(
        name = "real_emergency_enderman_defense",
        environment = "exclusive_real_emergency_enderman_defense",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = EMERGENCY_ENDERMAN_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realEmergencyEndermanDefense(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicInteger phase = new AtomicInteger();
        final AtomicLong phaseStarted = new AtomicLong(helper.getTick());
        final AtomicReference<Mob> enderman = new AtomicReference<>();
        final AtomicReference<Vec3> bodyStart = new AtomicReference<>();
        final AtomicInteger evidence = new AtomicInteger();
        final AtomicLong lastEmergencyTick =
                new AtomicLong(helper.getTick());
        final AtomicReference<Float> lastObservedHealth =
                new AtomicReference<>();
        final AtomicLong lastHealthChangeTick =
                new AtomicLong(helper.getTick());
        final AtomicInteger guardingTicks = new AtomicInteger();
        final AtomicInteger maximumShieldTicks = new AtomicInteger();
        final AtomicInteger consecutiveShieldTicks = new AtomicInteger();
        final AtomicReference<Double> maximumDistanceFromStart =
                new AtomicReference<>(0.0D);

        helper.addCleanup(ignored -> {
            final Mob target = enderman.get();
            if (target != null && !target.isRemoved()) {
                target.discard();
            }
            CompanionRuntime.active()
                    .filter(runtime -> runtime.server() == server)
                    .ifPresent(runtime -> {
                        final GoalStatus goalStatus =
                                runtime.goals().snapshot().status();
                        if (goalStatus == GoalStatus.RUNNING
                                || goalStatus
                                    == GoalStatus.CANCEL_PENDING) {
                            runtime.goals().markTerminal(
                                    GoalStatus.SAFE_IDLE,
                                    "enderman_gate_cleanup"
                            );
                        }
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor()
                                .abandonForSessionEnd();
                    });
            if (AiPlayerManager.status(server).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(server);
            }
        });

        final var spawn = GameTestCompanionSpawn.request(
                helper,
                FOCUSED_TEST_ORIGIN
        );
        helper.assertTrue(
                spawn.accepted(),
                "Enderman-defense body spawn was rejected: "
                    + spawn.code()
        );
        scheduleEveryTick(
                helper,
                EMERGENCY_ENDERMAN_TEST_MAX_TICKS,
                () -> {
                    final long now = helper.getTick();
                    if (phase.get() == 0) {
                        final var status =
                                AiPlayerManager.status(server);
                        helper.assertTrue(
                                status.state() != SessionState.FAILED,
                                "Enderman-defense body failed: "
                                    + status
                        );
                        if (status.state() != SessionState.ACTIVE
                                || !status.online()) {
                            helper.assertTrue(
                                    now - phaseStarted.get()
                                        <= FOCUSED_BODY_START_TIMEOUT_TICKS,
                                    "Enderman-defense body timed out"
                            );
                            return;
                        }
                        final var runtime = CompanionRuntime.active()
                                .filter(candidate ->
                                        candidate.server() == server)
                                .orElseThrow(() ->
                                        helper.assertionException(
                                            "Enderman-defense runtime "
                                                + "is unavailable"
                                        ));
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor()
                                .abandonForSessionEnd();
                        /*
                         * Deliberately do not install a test gateway here.
                         * This is the regression for the real field failure
                         * where an invalid/missing model credential used to
                         * skip even local survival. The runtime must keep
                         * the fair emergency lane alive while the high-level
                         * model lane remains unavailable.
                         */
                        runtime.brain().close();
                        final var goal = runtime.goals().setGoal(
                                "Survive the physical Enderman encounter",
                                GoalSource.RECOVERY
                        );
                        helper.assertTrue(
                                goal.accepted(),
                                "Enderman-defense goal was rejected: "
                                    + goal.code()
                        );

                        final var body = AiPlayerManager
                                .onlinePlayer(server)
                                .orElseThrow();
                        final BlockPos center =
                                helper.absolutePos(
                                    FOCUSED_TEST_ORIGIN
                                );
                        for (int x = -8; x <= 8; x++) {
                            for (int z = -8; z <= 8; z++) {
                                helper.getLevel()
                                        .setBlockAndUpdate(
                                            center.offset(x, -1, z),
                                            Blocks.SMOOTH_STONE
                                                .defaultBlockState()
                                        );
                                for (int y = 0; y <= 5; y++) {
                                    helper.getLevel()
                                            .setBlockAndUpdate(
                                                center.offset(x, y, z),
                                                Blocks.AIR
                                                    .defaultBlockState()
                                            );
                                }
                            }
                        }
                        body.teleportTo(
                                center.getX() + 0.5D,
                                center.getY(),
                                center.getZ() + 0.5D
                        );
                        body.setGameMode(GameType.SURVIVAL);
                        body.getInventory().clearContent();
                        body.getInventory().setItem(
                                0,
                                new ItemStack(
                                        Items.COOKED_BEEF,
                                        3
                                )
                        );
                        body.getInventory().setItem(
                                3,
                                new ItemStack(Items.DIAMOND_SWORD)
                        );
                        body.getInventory().setSelectedSlot(0);
                        body.setItemSlot(
                                EquipmentSlot.OFFHAND,
                                new ItemStack(Items.SHIELD)
                        );
                        body.setItemSlot(
                                EquipmentSlot.HEAD,
                                new ItemStack(Items.DIAMOND_HELMET)
                        );
                        body.setItemSlot(
                                EquipmentSlot.CHEST,
                                new ItemStack(
                                        Items.DIAMOND_CHESTPLATE
                                )
                        );
                        body.setItemSlot(
                                EquipmentSlot.LEGS,
                                new ItemStack(
                                        Items.DIAMOND_LEGGINGS
                                )
                        );
                        body.setItemSlot(
                                EquipmentSlot.FEET,
                                new ItemStack(Items.DIAMOND_BOOTS)
                        );
                        body.setHealth(12.0F);
                        body.getFoodData().setFoodLevel(20);
                        body.getFoodData().setSaturation(5.0F);
                        body.setDeltaMovement(Vec3.ZERO);
                        body.fallDistance = 0.0F;
                        body.inventoryMenu.broadcastChanges();
                        lastObservedHealth.set(body.getHealth());
                        lastHealthChangeTick.set(now);

                        final net.minecraft.world.entity.monster.EnderMan
                                target = EntityTypes.ENDERMAN.create(
                                    helper.getLevel(),
                                    EntitySpawnReason.COMMAND
                                );
                        helper.assertTrue(
                                target != null,
                                "Could not create physical Enderman"
                        );
                        target.setPos(
                                center.getX() + 2.25D,
                                center.getY(),
                                center.getZ() + 0.5D
                        );
                        target.setPersistenceRequired();
                        helper.assertTrue(
                                helper.getLevel()
                                    .addFreshEntity(target),
                                "Could not add physical Enderman"
                        );
                        target.setPersistentAngerTarget(
                                net.minecraft.world.entity
                                    .EntityReference.of(body)
                        );
                        target.setTimeToRemainAngry(1_200L);
                        target.setTarget(body);
                        enderman.set(target);
                        body.lookAt(
                                EntityAnchorArgument.Anchor.EYES,
                                target.position().add(
                                        0.0D,
                                        1.0D,
                                        0.0D
                                )
                        );
                        body.setYHeadRot(body.getYRot());
                        bodyStart.set(body.position());
                        phase.set(1);
                        phaseStarted.set(now);
                        lastEmergencyTick.set(now);
                        return;
                    }

                    final var runtime = CompanionRuntime.active()
                            .orElseThrow();
                    final var body = AiPlayerManager
                            .onlinePlayer(server)
                            .orElseThrow();
                    final Mob target = enderman.get();
                    if (runtime.survival().state()
                            == EmergencySurvivalController.State.GUARDING) {
                        guardingTicks.incrementAndGet();
                    }
                    final float health = body.getHealth();
                    final Float previousHealth =
                            lastObservedHealth.getAndSet(health);
                    if (previousHealth != null
                            && Math.abs(previousHealth - health) > 1.0E-4F) {
                        lastHealthChangeTick.set(now);
                    }
                    helper.assertTrue(
                            body.isAlive()
                                && !body.isDeadOrDying(),
                            "Shielded companion was killed by one "
                                + "Enderman: health="
                                + body.getHealth()
                                + ", position=" + body.position()
                                + ", velocity=" + body.getDeltaMovement()
                                + ", onGround=" + body.onGround()
                                + ", targetPosition="
                                + (target == null ? "null" : target.position())
                                + ", survival="
                                + runtime.survival().state()
                    );
                    if (body.getMainHandItem()
                            .is(Items.DIAMOND_SWORD)) {
                        evidence.getAndUpdate(bits -> bits | 1);
                        if (body.getMainHandItem()
                                .getDamageValue() > 0) {
                            evidence.getAndUpdate(bits -> bits | 2);
                        }
                    }
                    if (body.isUsingItem()
                            && body.getUseItem().is(Items.SHIELD)) {
                        evidence.getAndUpdate(bits -> bits | 4);
                        final int continuous =
                                consecutiveShieldTicks.incrementAndGet();
                        maximumShieldTicks.accumulateAndGet(
                                continuous,
                                Math::max
                        );
                    } else {
                        consecutiveShieldTicks.set(0);
                    }
                    runtime.behaviorArbiter().latest()
                            .filter(resolution ->
                                    resolution.claimedBy(
                                        dev.mcai.companion.control
                                            .BehaviorArbiter.Lane
                                            .EMERGENCY_SURVIVAL
                                    ))
                            .ifPresent(resolution ->
                                    {
                                        evidence.getAndUpdate(
                                            bits -> bits | 8
                                        );
                                        lastEmergencyTick.set(now);
                                    });
                    final double distanceFromStart = body.position()
                            .distanceTo(bodyStart.get());
                    maximumDistanceFromStart.accumulateAndGet(
                            distanceFromStart,
                            Math::max
                    );
                    /*
                     * Net displacement is not a reliable footwork oracle:
                     * vanilla knockback and an Enderman teleport can return
                     * the body to its starting cell after a legal backstep.
                     * Require observed excursion from the original pose while
                     * retaining the ordinary movement/physics path.
                     */
                    if (maximumDistanceFromStart.get() >= 0.25D) {
                        evidence.getAndUpdate(bits -> bits | 16);
                    }
                    final boolean defeated = target == null
                            || target.isRemoved()
                            || !target.isAlive();
                    final boolean repelled = !defeated
                            && target.getHealth()
                                <= target.getMaxHealth() * 0.50F
                            && now - lastEmergencyTick.get() >= 40L;
                    final boolean safelySeparated = !defeated
                            && target.distanceTo(body)
                                >= EMERGENCY_SAFE_STANDOFF_DISTANCE
                            && now - lastHealthChangeTick.get() >= 40L
                            && now - lastEmergencyTick.get() >= 40L;
                    if (defeated || repelled || safelySeparated) {
                        helper.assertTrue(
                                (evidence.get() & 31) == 31,
                                "Enderman defense lacked normal "
                                    + "weapon equip/durability, emergency "
                                    + "ownership and footwork; evidence="
                                    + evidence.get()
                                    + ", maximumShieldTicks="
                                    + maximumShieldTicks.get()
                                    + ", guardingTicks=" + guardingTicks.get()
                                    + ", maximumDistanceFromStart="
                                    + maximumDistanceFromStart.get()
                                    + ", position=" + body.position()
                                    + ", survival=" + runtime.survival().state()
                                    + ", using=" + body.isUsingItem()
                                    + ", useItem=" + body.getUseItem().getHoverName().getString()
                                    + ", clientLoaded=" + body.connection.hasClientLoaded()
                        );
                        helper.succeed();
                        return;
                    }
                    helper.assertTrue(
                            now - phaseStarted.get() <= 500L,
                            "Physical Enderman defense timed out: "
                                + "targetHealth=" + target.getHealth()
                                + ", bodyHealth=" + body.getHealth()
                                + ", targetDistance="
                                + target.distanceTo(body)
                                + ", stableHealthTicks="
                                + (now - lastHealthChangeTick.get())
                                + ", evidence=" + evidence.get()
                                + ", survival="
                                + runtime.survival().state()
                                + ", arbiter="
                                + runtime.behaviorArbiter().latest()
                    );
                }
        );
    }

    /**
     * Physical regression for a hostile that begins behind the companion.
     * This catches a deceptive shield state where telemetry said
     * {@code GUARDING}, but the controller released and restarted use every
     * tick so vanilla never completed the shield warmup.
     */
    @GameTest(
        name = "real_emergency_slime_defense",
        environment = "exclusive_real_emergency_slime_defense",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = EMERGENCY_SLIME_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realEmergencySlimeDefense(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicInteger phase = new AtomicInteger();
        final AtomicLong phaseStarted = new AtomicLong(helper.getTick());
        final AtomicReference<Mob> slime = new AtomicReference<>();
        final AtomicReference<Vec3> bodyStart = new AtomicReference<>();
        final AtomicInteger evidence = new AtomicInteger();
        final AtomicInteger consecutiveShieldTicks = new AtomicInteger();
        final AtomicInteger maximumShieldTicks = new AtomicInteger();
        final AtomicInteger guardingTicks = new AtomicInteger();

        helper.addCleanup(ignored -> {
            final Mob target = slime.get();
            if (target != null && !target.isRemoved()) {
                target.discard();
            }
            CompanionRuntime.active()
                    .filter(runtime -> runtime.server() == server)
                    .ifPresent(runtime -> {
                        final GoalStatus goalStatus =
                                runtime.goals().snapshot().status();
                        if (goalStatus == GoalStatus.RUNNING
                                || goalStatus
                                    == GoalStatus.CANCEL_PENDING) {
                            runtime.goals().markTerminal(
                                    GoalStatus.SAFE_IDLE,
                                    "slime_gate_cleanup"
                            );
                        }
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor()
                                .abandonForSessionEnd();
                    });
            if (AiPlayerManager.status(server).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(server);
            }
        });

        final var spawn = GameTestCompanionSpawn.request(
                helper,
                FOCUSED_TEST_ORIGIN
        );
        helper.assertTrue(
                spawn.accepted(),
                "Slime-defense body spawn was rejected: "
                    + spawn.code()
        );
        scheduleEveryTick(
                helper,
                EMERGENCY_SLIME_TEST_MAX_TICKS,
                () -> {
                    final long now = helper.getTick();
                    if (phase.get() == 0) {
                        final var status =
                                AiPlayerManager.status(server);
                        helper.assertTrue(
                                status.state() != SessionState.FAILED,
                                "Slime-defense body failed: " + status
                        );
                        if (status.state() != SessionState.ACTIVE
                                || !status.online()) {
                            helper.assertTrue(
                                    now - phaseStarted.get()
                                        <= FOCUSED_BODY_START_TIMEOUT_TICKS,
                                    "Slime-defense body timed out"
                            );
                            return;
                        }
                        final var runtime = CompanionRuntime.active()
                                .filter(candidate ->
                                        candidate.server() == server)
                                .orElseThrow(() ->
                                        helper.assertionException(
                                            "Slime-defense runtime "
                                                + "is unavailable"
                                        ));
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor()
                                .abandonForSessionEnd();
                        /*
                         * Deliberately do not install a test gateway here.
                         * This regression must exercise the production
                         * no-credential path: high-level model control is
                         * unavailable, but the fair local survival lane
                         * must still respond to the real hostile.
                         */
                        runtime.brain().close();
                        final var goal = runtime.goals().setGoal(
                                "Survive the physical Slime encounter",
                                GoalSource.RECOVERY
                        );
                        helper.assertTrue(
                                goal.accepted(),
                                "Slime-defense goal was rejected: "
                                    + goal.code()
                        );

                        final var body = AiPlayerManager
                                .onlinePlayer(server)
                                .orElseThrow();
                        final BlockPos center = helper.absolutePos(
                                FOCUSED_TEST_ORIGIN
                        );
                        for (int x = -8; x <= 8; x++) {
                            for (int z = -8; z <= 8; z++) {
                                helper.getLevel().setBlockAndUpdate(
                                        center.offset(x, -1, z),
                                        Blocks.SMOOTH_STONE
                                            .defaultBlockState()
                                );
                                for (int y = 0; y <= 5; y++) {
                                    helper.getLevel().setBlockAndUpdate(
                                            center.offset(x, y, z),
                                            Blocks.AIR
                                                .defaultBlockState()
                                    );
                                }
                            }
                        }
                        body.teleportTo(
                                center.getX() + 0.5D,
                                center.getY(),
                                center.getZ() + 0.5D
                        );
                        body.setGameMode(GameType.SURVIVAL);
                        body.getInventory().clearContent();
                        body.getInventory().setItem(
                                0,
                                new ItemStack(Items.COOKED_BEEF, 3)
                        );
                        body.getInventory().setItem(
                                3,
                                new ItemStack(Items.DIAMOND_SWORD)
                        );
                        body.getInventory().setSelectedSlot(0);
                        body.setItemSlot(
                                EquipmentSlot.OFFHAND,
                                new ItemStack(Items.SHIELD)
                        );
                        body.setItemSlot(
                                EquipmentSlot.HEAD,
                                new ItemStack(Items.DIAMOND_HELMET)
                        );
                        body.setItemSlot(
                                EquipmentSlot.CHEST,
                                new ItemStack(Items.DIAMOND_CHESTPLATE)
                        );
                        body.setItemSlot(
                                EquipmentSlot.LEGS,
                                new ItemStack(Items.DIAMOND_LEGGINGS)
                        );
                        body.setItemSlot(
                                EquipmentSlot.FEET,
                                new ItemStack(Items.DIAMOND_BOOTS)
                        );
                        body.setHealth(body.getMaxHealth());
                        body.getFoodData().setFoodLevel(20);
                        body.getFoodData().setSaturation(5.0F);
                        body.setDeltaMovement(Vec3.ZERO);
                        body.fallDistance = 0.0F;
                        body.inventoryMenu.broadcastChanges();

                        final var target = EntityTypes.SLIME.create(
                                helper.getLevel(),
                                EntitySpawnReason.COMMAND
                        );
                        helper.assertTrue(
                                target != null,
                                "Could not create physical Slime"
                        );
                        target.setSize(3, true);
                        target.setPos(
                                center.getX() + 0.5D,
                                center.getY(),
                                center.getZ() + 2.25D
                        );
                        target.setPersistenceRequired();
                        helper.assertTrue(
                                helper.getLevel().addFreshEntity(target),
                                "Could not add physical Slime"
                        );
                        target.setTarget(body);
                        slime.set(target);
                        body.lookAt(
                                EntityAnchorArgument.Anchor.EYES,
                                body.position().add(0.0D, 0.0D, -5.0D)
                        );
                        body.setYHeadRot(body.getYRot());
                        bodyStart.set(body.position());
                        phase.set(1);
                        phaseStarted.set(now);
                        return;
                    }

                    final var runtime = CompanionRuntime.active()
                            .orElseThrow();
                    final var body = AiPlayerManager
                            .onlinePlayer(server)
                            .orElseThrow();
                    final Mob target = slime.get();
                    if (runtime.survival().state()
                            == EmergencySurvivalController.State.GUARDING) {
                        guardingTicks.incrementAndGet();
                    }
                    helper.assertTrue(
                            body.isAlive() && !body.isDeadOrDying(),
                            "Shielded companion was killed by one Slime: "
                                + "health=" + body.getHealth()
                                + ", survival="
                                + runtime.survival().state()
                    );
                    if (body.isUsingItem()
                            && body.getUseItem().is(Items.SHIELD)) {
                        final int continuous =
                                consecutiveShieldTicks.incrementAndGet();
                        maximumShieldTicks.accumulateAndGet(
                                continuous,
                                Math::max
                        );
                        evidence.getAndUpdate(bits -> bits | 4);
                    } else {
                        consecutiveShieldTicks.set(0);
                    }
                    if (body.getMainHandItem()
                            .is(Items.DIAMOND_SWORD)) {
                        evidence.getAndUpdate(bits -> bits | 1);
                        if (body.getMainHandItem()
                                .getDamageValue() > 0) {
                            evidence.getAndUpdate(bits -> bits | 2);
                        }
                    }
                    runtime.behaviorArbiter().latest()
                            .filter(resolution ->
                                    resolution.claimedBy(
                                        dev.mcai.companion.control
                                            .BehaviorArbiter.Lane
                                            .EMERGENCY_SURVIVAL
                                    ))
                            .ifPresent(resolution ->
                                    evidence.getAndUpdate(
                                        bits -> bits | 8
                                    ));
                    if (body.position().distanceTo(
                            bodyStart.get()
                    ) >= 0.25D) {
                        evidence.getAndUpdate(bits -> bits | 16);
                    }

                    final boolean defeated = target == null
                            || target.isRemoved()
                            || !target.isAlive();
                    if (defeated) {
                        helper.assertTrue(
                                (evidence.get() & 31) == 31,
                                "Slime defense lacked normal shield, "
                                    + "weapon durability, emergency "
                                    + "ownership or footwork; evidence="
                                    + evidence.get()
                                    + ", maximumShieldTicks="
                                    + maximumShieldTicks.get()
                                    + ", guardingTicks=" + guardingTicks.get()
                                    + ", survival=" + runtime.survival().state()
                                    + ", using=" + body.isUsingItem()
                                    + ", useItem=" + body.getUseItem().getHoverName().getString()
                                    + ", clientLoaded=" + body.connection.hasClientLoaded()
                        );
                        helper.assertTrue(
                                maximumShieldTicks.get() >= 5,
                                "Slime defense never held the shield "
                                    + "through vanilla warmup; max="
                                    + maximumShieldTicks.get()
                        );
                        helper.succeed();
                        return;
                    }
                    helper.assertTrue(
                            now - phaseStarted.get() <= 700L,
                            "Physical Slime defense timed out: "
                                + "targetHealth=" + target.getHealth()
                                + ", bodyHealth=" + body.getHealth()
                                + ", evidence=" + evidence.get()
                                + ", maximumShieldTicks="
                                + maximumShieldTicks.get()
                                + ", guardingTicks=" + guardingTicks.get()
                                + ", survival="
                                + runtime.survival().state()
                    );
                }
        );
    }

    /**
     * Regression for a particularly confusing field symptom: a hostile is
     * already touching the body while the body is looking away, so a
     * first-person view-cone sample contains no entity.  The emergency lane
     * must use the real collision cue to reacquire the target, turn through
     * the ordinary input path, and dispatch a normal attack instead of only
     * speaking/guarding until death.  No model gateway is installed here.
     */
    @GameTest(
        name = "real_emergency_contact_reacquisition",
        environment = "exclusive_real_emergency_contact_reacquisition",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 1_200,
        skyAccess = true,
        padding = 8
    )
    public static void realEmergencyContactReacquisition(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicInteger phase = new AtomicInteger();
        final AtomicLong phaseStarted = new AtomicLong(helper.getTick());
        final AtomicReference<Mob> target = new AtomicReference<>();
        final AtomicReference<Vec3> bodyStart = new AtomicReference<>();
        final AtomicInteger evidence = new AtomicInteger();

        helper.addCleanup(ignored -> {
            final Mob current = target.get();
            if (current != null && !current.isRemoved()) {
                current.discard();
            }
            CompanionRuntime.active()
                    .filter(runtime -> runtime.server() == server)
                    .ifPresent(runtime -> {
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor().abandonForSessionEnd();
                    });
            if (AiPlayerManager.status(server).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(server);
            }
        });

        final var spawn = GameTestCompanionSpawn.request(
                helper,
                FOCUSED_TEST_ORIGIN
        );
        helper.assertTrue(
                spawn.accepted(),
                "Contact-reacquisition body spawn was rejected: "
                    + spawn.code()
        );
        scheduleEveryTick(
                helper,
                1_200,
                () -> {
                    final long now = helper.getTick();
                    if (phase.get() == 0) {
                        final var status = AiPlayerManager.status(server);
                        helper.assertTrue(
                                status.state() != SessionState.FAILED,
                                "Contact-reacquisition body failed: "
                                    + status
                        );
                        if (status.state() != SessionState.ACTIVE
                                || !status.online()) {
                            helper.assertTrue(
                                    now - phaseStarted.get()
                                        <= FOCUSED_BODY_START_TIMEOUT_TICKS,
                                    "Contact-reacquisition body timed out"
                            );
                            return;
                        }
                        final var runtime = CompanionRuntime.active()
                                .filter(candidate ->
                                        candidate.server() == server)
                                .orElseThrow(() -> helper.assertionException(
                                        "Contact-reacquisition runtime unavailable"
                                ));
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor().abandonForSessionEnd();
                        runtime.brain().close();
                        final var goal = runtime.goals().setGoal(
                                "Survive a hostile contact",
                                GoalSource.RECOVERY
                        );
                        helper.assertTrue(
                                goal.accepted(),
                                "Contact-reacquisition goal was rejected: "
                                    + goal.code()
                        );

                        final BlockPos center = helper.absolutePos(
                                FOCUSED_TEST_ORIGIN
                        );
                        final var level = helper.getLevel();
                        for (int x = -6; x <= 6; x++) {
                            for (int z = -6; z <= 6; z++) {
                                level.setBlockAndUpdate(
                                        center.offset(x, -1, z),
                                        Blocks.SMOOTH_STONE.defaultBlockState()
                                );
                                for (int y = 0; y <= 4; y++) {
                                    level.setBlockAndUpdate(
                                            center.offset(x, y, z),
                                            Blocks.AIR.defaultBlockState()
                                    );
                                }
                            }
                        }
                        final var body = AiPlayerManager.onlinePlayer(server)
                                .orElseThrow();
                        body.teleportTo(
                                center.getX() + 0.5D,
                                center.getY(),
                                center.getZ() + 0.5D
                        );
                        body.setGameMode(GameType.SURVIVAL);
                        body.getInventory().clearContent();
                        body.getInventory().setItem(
                                0,
                                new ItemStack(Items.DIAMOND_SWORD)
                        );
                        body.getInventory().setSelectedSlot(0);
                        body.setItemSlot(
                                EquipmentSlot.OFFHAND,
                                new ItemStack(Items.SHIELD)
                        );
                        body.setItemSlot(
                                EquipmentSlot.HEAD,
                                new ItemStack(Items.DIAMOND_HELMET)
                        );
                        body.setItemSlot(
                                EquipmentSlot.CHEST,
                                new ItemStack(Items.DIAMOND_CHESTPLATE)
                        );
                        body.setItemSlot(
                                EquipmentSlot.LEGS,
                                new ItemStack(Items.DIAMOND_LEGGINGS)
                        );
                        body.setItemSlot(
                                EquipmentSlot.FEET,
                                new ItemStack(Items.DIAMOND_BOOTS)
                        );
                        body.setHealth(body.getMaxHealth());
                        body.getFoodData().setFoodLevel(20);
                        body.setDeltaMovement(Vec3.ZERO);
                        body.fallDistance = 0.0F;
                        body.inventoryMenu.broadcastChanges();

                        final Mob zombie = EntityTypes.ZOMBIE.create(
                                level,
                                EntitySpawnReason.COMMAND
                        );
                        helper.assertTrue(
                                zombie != null,
                                "Could not create contact zombie"
                        );
                        zombie.setPos(
                                center.getX() + 0.5D,
                                center.getY(),
                                center.getZ() + 0.45D
                        );
                        zombie.setPersistenceRequired();
                        helper.assertTrue(
                                level.addFreshEntity(zombie),
                                "Could not add contact zombie"
                        );
                        zombie.setTarget(body);
                        target.set(zombie);
                        /* Face the empty side of the arena, not the mob. */
                        body.lookAt(
                                EntityAnchorArgument.Anchor.EYES,
                                body.position().add(0.0D, 0.0D, -6.0D)
                        );
                        body.setYHeadRot(body.getYRot());
                        bodyStart.set(body.position());
                        phase.set(1);
                        phaseStarted.set(now);
                        return;
                    }

                    final var runtime = CompanionRuntime.active()
                            .orElseThrow();
                    final var body = AiPlayerManager.onlinePlayer(server)
                            .orElseThrow();
                    final Mob zombie = target.get();
                    helper.assertTrue(
                            body.isAlive() && !body.isDeadOrDying(),
                            "Contact hostile killed the companion: health="
                                + body.getHealth()
                                + ", survival=" + runtime.survival().state()
                    );
                    if (zombie != null
                            && zombie.getHealth() < zombie.getMaxHealth()) {
                        evidence.getAndUpdate(bits -> bits | 1);
                    }
                    if (body.getMainHandItem().is(Items.DIAMOND_SWORD)
                            && body.getMainHandItem().getDamageValue() > 0) {
                        evidence.getAndUpdate(bits -> bits | 2);
                    }
                    if (body.position().distanceTo(bodyStart.get()) >= 0.10D) {
                        evidence.getAndUpdate(bits -> bits | 4);
                    }
                    runtime.behaviorArbiter().latest()
                            .filter(resolution -> resolution.claimedBy(
                                    dev.mcai.companion.control.BehaviorArbiter
                                            .Lane.EMERGENCY_SURVIVAL
                            ))
                            .ifPresent(ignored ->
                                    evidence.getAndUpdate(bits -> bits | 8)
                            );
                    if ((evidence.get() & 15) == 15
                            && now - phaseStarted.get() >= 60L) {
                        helper.succeed();
                        return;
                    }
                    helper.assertTrue(
                            now - phaseStarted.get() <= 900L,
                            "Contact hostile produced no complete response: "
                                + "evidence=" + evidence.get()
                                + ", health=" + body.getHealth()
                                + ", zombieHealth="
                                + (zombie == null ? "null" : zombie.getHealth())
                                + ", position=" + body.position()
                                + ", survival=" + runtime.survival().state()
                                + ", frame=" + runtime.coreFrames().current()
                    );
                }
        );
    }

    /**
     * Physical regression for a neutral mob that has already chosen the
     * companion as its target.  Iron golems are intentionally not marked
     * hostile by the fair sampler, so the only legal automatic retaliation
     * trigger is a real vanilla damage/contact cue.  The body must use its
     * owned sword through the ordinary attack actuator and must not remain a
     * stationary shield spectator.
     */
    @GameTest(
        name = "real_emergency_iron_golem_duel",
        environment = "exclusive_real_emergency_iron_golem_duel",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = EMERGENCY_GOLEM_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realEmergencyIronGolemDuel(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicInteger phase = new AtomicInteger();
        final AtomicLong phaseStarted = new AtomicLong(helper.getTick());
        final AtomicReference<Mob> target = new AtomicReference<>();
        final AtomicReference<Vec3> bodyStart = new AtomicReference<>();
        final AtomicReference<Float> targetInitialHealth =
                new AtomicReference<>();
        final AtomicInteger evidence = new AtomicInteger();
        final AtomicBoolean targetActivated = new AtomicBoolean();

        helper.addCleanup(ignored -> {
            final Mob current = target.get();
            if (current != null && !current.isRemoved()) {
                current.discard();
            }
            CompanionRuntime.active()
                    .filter(runtime -> runtime.server() == server)
                    .ifPresent(runtime -> {
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor().abandonForSessionEnd();
                    });
            if (AiPlayerManager.status(server).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(server);
            }
        });

        final var spawn = GameTestCompanionSpawn.request(
                helper,
                FOCUSED_TEST_ORIGIN
        );
        helper.assertTrue(
                spawn.accepted(),
                "Iron-golem duel body spawn was rejected: " + spawn.code()
        );
        scheduleEveryTick(
                helper,
                EMERGENCY_GOLEM_TEST_MAX_TICKS,
                () -> {
                    final long now = helper.getTick();
                    if (phase.get() == 0) {
                        final var status = AiPlayerManager.status(server);
                        helper.assertTrue(
                                status.state() != SessionState.FAILED,
                                "Iron-golem duel body failed: " + status
                        );
                        if (status.state() != SessionState.ACTIVE
                                || !status.online()) {
                            helper.assertTrue(
                                    now - phaseStarted.get()
                                        <= FOCUSED_BODY_START_TIMEOUT_TICKS,
                                    "Iron-golem duel body timed out"
                            );
                            return;
                        }
                        final var runtime = CompanionRuntime.active()
                                .filter(candidate ->
                                        candidate.server() == server)
                                .orElseThrow(() -> helper.assertionException(
                                        "Iron-golem duel runtime unavailable"
                                ));
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor().abandonForSessionEnd();
                        runtime.brain().close();
                        final var goal = runtime.goals().setGoal(
                                "Survive the iron golem duel",
                                GoalSource.RECOVERY
                        );
                        helper.assertTrue(
                                goal.accepted(),
                                "Iron-golem duel goal was rejected: "
                                    + goal.code()
                        );

                        final var level = helper.getLevel();
                        final BlockPos center = helper.absolutePos(
                                FOCUSED_TEST_ORIGIN
                        );
                        for (int x = -8; x <= 8; x++) {
                            for (int z = -8; z <= 8; z++) {
                                level.setBlockAndUpdate(
                                        center.offset(x, -1, z),
                                        Blocks.SMOOTH_STONE.defaultBlockState()
                                );
                                for (int y = 0; y <= 5; y++) {
                                    level.setBlockAndUpdate(
                                            center.offset(x, y, z),
                                            Blocks.AIR.defaultBlockState()
                                    );
                                }
                            }
                        }
                        final var body = AiPlayerManager
                                .onlinePlayer(server)
                                .orElseThrow();
                        body.teleportTo(
                                center.getX() + 0.5D,
                                center.getY(),
                                center.getZ() + 0.5D
                        );
                        body.setGameMode(GameType.SURVIVAL);
                        body.getInventory().clearContent();
                        body.getInventory().setItem(
                                0,
                                new ItemStack(Items.DIAMOND_SWORD)
                        );
                        body.getInventory().setItem(
                                8,
                                new ItemStack(Items.COOKED_BEEF, 8)
                        );
                        body.getInventory().setSelectedSlot(0);
                        body.setItemSlot(
                                EquipmentSlot.OFFHAND,
                                new ItemStack(Items.SHIELD)
                        );
                        body.setItemSlot(
                                EquipmentSlot.HEAD,
                                new ItemStack(Items.DIAMOND_HELMET)
                        );
                        body.setItemSlot(
                                EquipmentSlot.CHEST,
                                new ItemStack(Items.DIAMOND_CHESTPLATE)
                        );
                        body.setItemSlot(
                                EquipmentSlot.LEGS,
                                new ItemStack(Items.DIAMOND_LEGGINGS)
                        );
                        body.setItemSlot(
                                EquipmentSlot.FEET,
                                new ItemStack(Items.DIAMOND_BOOTS)
                        );
                        body.setHealth(body.getMaxHealth());
                        body.getFoodData().setFoodLevel(20);
                        body.getFoodData().setSaturation(5.0F);
                        body.setDeltaMovement(Vec3.ZERO);
                        body.fallDistance = 0.0F;
                        body.inventoryMenu.broadcastChanges();

                        final Mob golem = EntityTypes.IRON_GOLEM.create(
                                level,
                                EntitySpawnReason.COMMAND
                        );
                        helper.assertTrue(
                                golem != null,
                                "Could not create physical iron golem"
                        );
                        golem.setPos(
                                center.getX() + 2.5D,
                                center.getY(),
                                center.getZ() + 0.5D
                        );
                        golem.setPersistenceRequired();
                        // Match the live-model fixture lifecycle: the mob is
                        // staged without AI, then receives an ordinary
                        // vanilla target on the following server tick.
                        golem.setNoAi(true);
                        helper.assertTrue(
                                level.addFreshEntity(golem),
                                "Could not add physical iron golem"
                        );
                        golem.setTarget(null);
                        target.set(golem);
                        targetInitialHealth.set(golem.getHealth());
                        body.lookAt(
                                EntityAnchorArgument.Anchor.EYES,
                                golem.position().add(0.0D, 1.0D, 0.0D)
                        );
                        body.setYHeadRot(body.getYRot());
                        bodyStart.set(body.position());
                        phase.set(1);
                        phaseStarted.set(now);
                        return;
                    }

                    final var runtime = CompanionRuntime.active()
                            .orElseThrow();
                    final var body = AiPlayerManager
                            .onlinePlayer(server)
                            .orElseThrow();
                    final Mob golem = target.get();
                    if (!targetActivated.get()) {
                        golem.setNoAi(false);
                        golem.setTarget(body);
                        golem.setLastHurtByMob(body);
                        golem.setAggressive(true);
                        if (golem instanceof net.minecraft.world.entity.NeutralMob neutral) {
                            neutral.setPersistentAngerTarget(
                                    net.minecraft.world.entity.EntityReference.of(body)
                            );
                            neutral.startPersistentAngerTimer();
                        }
                        targetActivated.set(true);
                    }
                    helper.assertTrue(
                            body.isAlive() && !body.isDeadOrDying(),
                            "Iron-golem duel killed the companion: health="
                                + body.getHealth()
                                + ", survival=" + runtime.survival().state()
                    );
                    if (golem != null
                            && targetInitialHealth.get() != null
                            && golem.getHealth()
                                < targetInitialHealth.get() - 0.1F) {
                        evidence.getAndUpdate(bits -> bits | 1);
                    }
                    if (body.getMainHandItem().is(Items.DIAMOND_SWORD)
                            && body.getMainHandItem().getDamageValue() > 0) {
                        evidence.getAndUpdate(bits -> bits | 2);
                    }
                    if (body.position().distanceTo(bodyStart.get()) >= 0.25D) {
                        evidence.getAndUpdate(bits -> bits | 4);
                    }
                    if (runtime.survival().state()
                            == EmergencySurvivalController.State.COUNTERATTACKING
                            || runtime.survival().state()
                                == EmergencySurvivalController.State.GUARDING) {
                        evidence.getAndUpdate(bits -> bits | 8);
                    }
                    /*
                     * This is a pressure/behavior gate, not a synthetic
                     * instant-kill assertion. A real duel has already been
                     * demonstrated once the golem lost meaningful health,
                     * the sword took vanilla durability, the body moved and
                     * the emergency arbiter owned the encounter. Requiring
                     * the full 100-health golem to die here would make the
                     * gate depend on the golem's pathing after a legitimate
                     * separation, rather than catching the reported
                     * stand-still failure.
                     */
                    if (golem != null
                            && targetInitialHealth.get() != null
                            && targetInitialHealth.get()
                                - golem.getHealth() >= 5.0F
                            && (evidence.get() & 15) == 15
                            && now - phaseStarted.get() >= 120L) {
                        helper.succeed();
                        return;
                    }
                    if (golem == null || golem.isRemoved() || !golem.isAlive()) {
                        helper.assertTrue(
                                (evidence.get() & 15) == 15,
                                "Iron-golem duel ended without real combat "
                                    + "evidence=" + evidence.get()
                                    + ", bodyHealth=" + body.getHealth()
                                    + ", survival="
                                    + runtime.survival().state()
                        );
                        helper.succeed();
                        return;
                    }
                    helper.assertTrue(
                            now - phaseStarted.get()
                                <= EMERGENCY_GOLEM_TEST_MAX_TICKS - 120L,
                            "Iron-golem duel timed out: targetHealth="
                                + golem.getHealth()
                                + ", bodyHealth=" + body.getHealth()
                                + ", evidence=" + evidence.get()
                                + ", survival=" + runtime.survival().state()
                    );
                }
        );
    }

    /**
     * Bounded horde-pressure gate requested for combat review: ten zombies
     * and ten skeletons are allowed to use their ordinary AI and attacks
     * against one survival body.  The assertion is intentionally about the
     * reported failure mode (standing still and never counterattacking), not
     * an artificial claim that one unenchanted player must clear twenty mobs
     * in every world.
     */
    @GameTest(
        name = "real_emergency_zombie_skeleton_horde",
        environment = "exclusive_real_emergency_zombie_skeleton_horde",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = EMERGENCY_HORDE_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realEmergencyZombieSkeletonHorde(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicInteger phase = new AtomicInteger();
        final AtomicLong phaseStarted = new AtomicLong(helper.getTick());
        final AtomicReference<List<Mob>> targets = new AtomicReference<>(
                List.of()
        );
        final AtomicReference<Vec3> bodyStart = new AtomicReference<>();

        helper.addCleanup(ignored -> {
            targets.get().forEach(target -> {
                if (target != null && !target.isRemoved()) {
                    target.discard();
                }
            });
            CompanionRuntime.active()
                    .filter(runtime -> runtime.server() == server)
                    .ifPresent(runtime -> {
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor().abandonForSessionEnd();
                    });
            if (AiPlayerManager.status(server).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(server);
            }
        });

        final var spawn = GameTestCompanionSpawn.request(
                helper,
                FOCUSED_TEST_ORIGIN
        );
        helper.assertTrue(
                spawn.accepted(),
                "Horde-defense body spawn was rejected: " + spawn.code()
        );
        scheduleEveryTick(
                helper,
                EMERGENCY_HORDE_TEST_MAX_TICKS,
                () -> {
                    final long now = helper.getTick();
                    if (phase.get() == 0) {
                        final var status = AiPlayerManager.status(server);
                        helper.assertTrue(
                                status.state() != SessionState.FAILED,
                                "Horde-defense body failed: " + status
                        );
                        if (status.state() != SessionState.ACTIVE
                                || !status.online()) {
                            helper.assertTrue(
                                    now - phaseStarted.get()
                                        <= FOCUSED_BODY_START_TIMEOUT_TICKS,
                                    "Horde-defense body timed out"
                            );
                            return;
                        }
                        final var runtime = CompanionRuntime.active()
                                .filter(candidate ->
                                        candidate.server() == server)
                                .orElseThrow(() -> helper.assertionException(
                                        "Horde-defense runtime unavailable"
                                ));
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor().abandonForSessionEnd();
                        runtime.brain().close();
                        final var goal = runtime.goals().setGoal(
                                "Survive ten zombies and ten skeletons",
                                GoalSource.RECOVERY
                        );
                        helper.assertTrue(
                                goal.accepted(),
                                "Horde-defense goal was rejected: "
                                    + goal.code()
                        );

                        final var level = helper.getLevel();
                        final BlockPos center = helper.absolutePos(
                                FOCUSED_TEST_ORIGIN
                        );
                        for (int x = -10; x <= 10; x++) {
                            for (int z = -10; z <= 10; z++) {
                                level.setBlockAndUpdate(
                                        center.offset(x, -1, z),
                                        Blocks.SMOOTH_STONE.defaultBlockState()
                                );
                                for (int y = 0; y <= 5; y++) {
                                    level.setBlockAndUpdate(
                                            center.offset(x, y, z),
                                            Blocks.AIR.defaultBlockState()
                                    );
                                }
                            }
                        }
                        final var body = AiPlayerManager
                                .onlinePlayer(server)
                                .orElseThrow();
                        body.teleportTo(
                                center.getX() + 0.5D,
                                center.getY(),
                                center.getZ() + 0.5D
                        );
                        body.setGameMode(GameType.SURVIVAL);
                        body.getInventory().clearContent();
                        body.getInventory().setItem(
                                0,
                                new ItemStack(Items.DIAMOND_SWORD)
                        );
                        body.getInventory().setItem(
                                8,
                                new ItemStack(Items.COOKED_BEEF, 16)
                        );
                        body.getInventory().setItem(
                                7,
                                new ItemStack(Items.GOLDEN_APPLE, 2)
                        );
                        body.getInventory().setSelectedSlot(0);
                        body.setItemSlot(
                                EquipmentSlot.OFFHAND,
                                new ItemStack(Items.SHIELD)
                        );
                        body.setItemSlot(
                                EquipmentSlot.HEAD,
                                new ItemStack(Items.DIAMOND_HELMET)
                        );
                        body.setItemSlot(
                                EquipmentSlot.CHEST,
                                new ItemStack(Items.DIAMOND_CHESTPLATE)
                        );
                        body.setItemSlot(
                                EquipmentSlot.LEGS,
                                new ItemStack(Items.DIAMOND_LEGGINGS)
                        );
                        body.setItemSlot(
                                EquipmentSlot.FEET,
                                new ItemStack(Items.DIAMOND_BOOTS)
                        );
                        body.setHealth(body.getMaxHealth());
                        body.getFoodData().setFoodLevel(20);
                        body.getFoodData().setSaturation(5.0F);
                        body.setDeltaMovement(Vec3.ZERO);
                        body.fallDistance = 0.0F;
                        body.inventoryMenu.broadcastChanges();

                        final List<Mob> spawned =
                                new java.util.ArrayList<>();
                        for (int index = 0; index < 20; index++) {
                            final boolean skeleton = index >= 10;
                            final Mob hostile = (skeleton
                                    ? EntityTypes.SKELETON
                                    : EntityTypes.ZOMBIE).create(
                                        level,
                                        EntitySpawnReason.COMMAND
                                    );
                            helper.assertTrue(
                                    hostile != null,
                                    "Could not create horde member " + index
                            );
                            final double angle =
                                    (Math.PI * 2.0 * index) / 20.0;
                            hostile.setPos(
                                    center.getX() + 5.5D
                                        * Math.cos(angle),
                                    center.getY(),
                                    center.getZ() + 5.5D
                                        * Math.sin(angle)
                            );
                            hostile.setPersistenceRequired();
                            helper.assertTrue(
                                    level.addFreshEntity(hostile),
                                    "Could not add horde member " + index
                            );
                            hostile.setTarget(body);
                            spawned.add(hostile);
                        }
                        helper.assertTrue(
                                spawned.size() == 20,
                                "Horde fixture did not create 20 mobs"
                        );
                        targets.set(List.copyOf(spawned));
                        body.lookAt(
                                EntityAnchorArgument.Anchor.EYES,
                                spawned.get(0).position()
                                    .add(0.0D, 1.0D, 0.0D)
                        );
                        body.setYHeadRot(body.getYRot());
                        bodyStart.set(body.position());
                        phase.set(1);
                        phaseStarted.set(now);
                        return;
                    }

                    final var runtime = CompanionRuntime.active()
                            .orElseThrow();
                    final var body = AiPlayerManager
                            .onlinePlayer(server)
                            .orElseThrow();
                    helper.assertTrue(
                            body.isAlive() && !body.isDeadOrDying(),
                            "Twenty-mob pressure killed the companion: "
                                + "health=" + body.getHealth()
                                + ", survival=" + runtime.survival().state()
                    );
                    final long damagedTargets = targets.get().stream()
                            .filter(target -> target.isRemoved()
                                    || !target.isAlive()
                                    || target.getHealth()
                                        < target.getMaxHealth() - 0.1F)
                            .count();
                    final boolean moved = body.position().distanceTo(
                            bodyStart.get()
                    ) >= 0.25D;
                    final boolean swordUsed = body.getMainHandItem()
                            .is(Items.DIAMOND_SWORD)
                            && body.getMainHandItem().getDamageValue() > 0;
                    if (damagedTargets >= 3
                            && moved
                            && swordUsed
                            && now - phaseStarted.get() >= 120L) {
                        helper.succeed();
                        return;
                    }
                    helper.assertTrue(
                            now - phaseStarted.get()
                                <= EMERGENCY_HORDE_TEST_MAX_TICKS - 120L,
                            "Twenty-mob pressure produced insufficient combat "
                                + "evidence: damagedTargets=" + damagedTargets
                                + ", moved=" + moved
                                + ", swordUsed=" + swordUsed
                                + ", bodyHealth=" + body.getHealth()
                                + ", survival=" + runtime.survival().state()
                    );
                }
        );
    }

    /**
     * Offline-provider gate for the smallest useful "someone handed me
     * equipment" interaction.  The fixture deliberately does not configure
     * a model gateway.  The body must still use the normal inventory-menu
     * transaction to put an owned helmet on its head and a shield in its
     * off-hand; this is local upkeep, not a fabricated item/world write.
     */
    @GameTest(
        name = "offline_idle_equipment",
        environment = "exclusive_offline_idle_equipment",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 1_000,
        skyAccess = true,
        padding = 8
    )
    public static void offlineIdleEquipment(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicInteger phase = new AtomicInteger();
        final AtomicLong phaseStarted = new AtomicLong(helper.getTick());

        helper.addCleanup(ignored -> {
            CompanionRuntime.active()
                    .filter(runtime -> runtime.server() == server)
                    .ifPresent(runtime -> {
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor().abandonForSessionEnd();
                    });
            if (AiPlayerManager.status(server).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(server);
            }
        });

        final var spawn = GameTestCompanionSpawn.request(
                helper,
                FOCUSED_TEST_ORIGIN
        );
        helper.assertTrue(
                spawn.accepted(),
                "Offline equipment body spawn was rejected: " + spawn.code()
        );
        scheduleEveryTick(
                helper,
                1_000,
                () -> {
                    final long now = helper.getTick();
                    if (phase.get() == 0) {
                        final var status = AiPlayerManager.status(server);
                        helper.assertTrue(
                                status.state() != SessionState.FAILED,
                                "Offline equipment body failed: " + status
                        );
                        if (status.state() != SessionState.ACTIVE
                                || !status.online()) {
                            helper.assertTrue(
                                    now - phaseStarted.get()
                                        <= FOCUSED_BODY_START_TIMEOUT_TICKS,
                                    "Offline equipment body timed out"
                            );
                            return;
                        }
                        final var runtime = CompanionRuntime.active()
                                .filter(candidate ->
                                        candidate.server() == server)
                                .orElseThrow(() -> helper.assertionException(
                                        "Offline equipment runtime unavailable"
                                ));
                        /*
                         * GameTestServer may reuse the production runtime
                         * between exclusive fixtures. A previous focused
                         * movement fixture can have installed its inert
                         * HoldingGameTestGateway; remove only that verified
                         * delegate before asserting this test's deliberate
                         * offline boundary. This is fixture isolation, not a
                         * production fallback or a model result.
                         */
                        runtime.model().gateway().clearVerifiedDelegate();
                        helper.assertTrue(
                                !runtime.model().snapshot().gatewayReady(),
                                "Offline equipment gate unexpectedly has a "
                                    + "live model gateway"
                        );
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor().abandonForSessionEnd();
                        runtime.brain().close();

                        final var body = AiPlayerManager
                                .onlinePlayer(server)
                                .orElseThrow();
                        body.setGameMode(GameType.SURVIVAL);
                        body.getInventory().clearContent();
                        body.setItemSlot(
                                EquipmentSlot.HEAD,
                                ItemStack.EMPTY
                        );
                        body.setItemSlot(
                                EquipmentSlot.OFFHAND,
                                ItemStack.EMPTY
                        );
                        body.getInventory().setItem(
                                0,
                                new ItemStack(Items.IRON_HELMET)
                        );
                        body.getInventory().setItem(
                                1,
                                new ItemStack(Items.SHIELD)
                        );
                        body.getInventory().setSelectedSlot(0);
                        body.inventoryMenu.broadcastChanges();
                        phase.set(1);
                        phaseStarted.set(now);
                        return;
                    }

                    final var body = AiPlayerManager
                            .onlinePlayer(server)
                            .orElseThrow();
                    helper.assertTrue(
                            body.isAlive() && !body.isDeadOrDying(),
                            "Offline equipment body died while idle"
                    );
                    final boolean helmet = body.getItemBySlot(
                            EquipmentSlot.HEAD
                    ).is(Items.IRON_HELMET);
                    final boolean shield = body.getItemBySlot(
                            EquipmentSlot.OFFHAND
                    ).is(Items.SHIELD);
                    if (helmet && shield) {
                        helper.succeed();
                        return;
                    }
                    helper.assertTrue(
                            now - phaseStarted.get() < 800L,
                            "Offline idle equipment did not commit through "
                                + "vanilla menus: helmet=" + helmet
                                + ", shield=" + shield
                                + ", inventoryHelmet="
                                + body.getInventory().countItem(
                                    Items.IRON_HELMET
                                )
                                + ", inventoryShield="
                                + body.getInventory().countItem(
                                    Items.SHIELD
                                )
                );
            }
        );
    }

    /**
     * Release-excluded, no-provider safety gate for the failure mode where a
     * companion is handed an always-edible golden apple at critical health.
     * The fixture gives the body the item through setup only; production code
     * must move it through the ordinary inventory transaction and then issue
     * a normal held-item use.  No health, inventory or effect is written by
     * the assertion itself after setup, and no model decision is installed.
     */
    @GameTest(
        name = "real_offline_critical_golden_apple",
        environment = "exclusive_real_offline_critical_golden_apple",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 2_000,
        skyAccess = true,
        padding = 8
    )
    public static void realOfflineCriticalGoldenApple(
            final GameTestHelper helper
    ) {
        final var server = helper.getLevel().getServer();
        final AtomicInteger phase = new AtomicInteger();
        final AtomicLong phaseStarted = new AtomicLong(helper.getTick());
        final AtomicReference<Integer> initialCount =
                new AtomicReference<>();

        helper.addCleanup(ignored -> {
            CompanionRuntime.active()
                    .filter(runtime -> runtime.server() == server)
                    .ifPresent(runtime -> {
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor().abandonForSessionEnd();
                    });
            if (AiPlayerManager.status(server).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(server);
            }
        });

        final var spawn = GameTestCompanionSpawn.request(
                helper,
                FOCUSED_TEST_ORIGIN
        );
        helper.assertTrue(
                spawn.accepted(),
                "Critical-food body spawn was rejected: " + spawn.code()
        );
        scheduleEveryTick(
                helper,
                2_000,
                () -> {
                    final long now = helper.getTick();
                    final var status = AiPlayerManager.status(server);
                    helper.assertTrue(
                            status.state() != SessionState.FAILED,
                            "Critical-food body failed: " + status
                    );
                    if (phase.get() == 0) {
                        if (status.state() != SessionState.ACTIVE
                                || !status.online()) {
                            helper.assertTrue(
                                    now <= FOCUSED_BODY_START_TIMEOUT_TICKS,
                                    "Critical-food body did not become active"
                            );
                            return;
                        }
                        final var runtime = CompanionRuntime.active()
                                .filter(candidate ->
                                        candidate.server() == server)
                                .orElseThrow(() -> helper.assertionException(
                                        "Critical-food runtime unavailable"
                                ));
                        /* This gate is intentionally offline/no-model. */
                        runtime.model().gateway().clearVerifiedDelegate();
                        runtime.brain().close();
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.skillSupervisor().abandonForSessionEnd();

                        final var body = AiPlayerManager
                                .onlinePlayer(server)
                                .orElseThrow();
                        body.setGameMode(GameType.SURVIVAL);
                        body.getInventory().clearContent();
                        body.setItemSlot(
                                EquipmentSlot.MAINHAND,
                                ItemStack.EMPTY
                        );
                        body.setItemSlot(
                                EquipmentSlot.OFFHAND,
                                ItemStack.EMPTY
                        );
                        body.removeAllEffects();
                        body.clearFire();
                        body.setHealth(4.0F);
                        body.getFoodData().setFoodLevel(20);
                        body.getFoodData().setSaturation(5.0F);
                        body.getInventory().setItem(
                                8,
                                new ItemStack(Items.GOLDEN_APPLE)
                        );
                        body.getInventory().setSelectedSlot(0);
                        body.inventoryMenu.broadcastChanges();
                        initialCount.set(
                                body.getInventory().countItem(
                                        Items.GOLDEN_APPLE
                                )
                        );
                        helper.assertTrue(
                                initialCount.get() == 1,
                                "Critical-food fixture did not install one "
                                    + "owned golden apple"
                        );
                        phase.set(1);
                        phaseStarted.set(now);
                        return;
                    }

                    final var body = AiPlayerManager.onlinePlayer(server)
                            .orElseThrow(() -> helper.assertionException(
                                    "Critical-food body disappeared"
                            ));
                    helper.assertTrue(
                            body.isAlive() && !body.isDeadOrDying(),
                            "Critical-food body died before eating: health="
                                + body.getHealth()
                    );
                    final int remaining = body.getInventory().countItem(
                            Items.GOLDEN_APPLE
                    );
                    final boolean held = body.getMainHandItem().is(
                            Items.GOLDEN_APPLE
                    );
                    final boolean consumed = remaining
                            < initialCount.get();
                    final boolean protectedByAbsorption =
                            body.getAbsorptionAmount() > 0.0F;
                    if (consumed && protectedByAbsorption) {
                        helper.succeed();
                        return;
                    }
                    helper.assertTrue(
                            now - phaseStarted.get() < 1_700L,
                            "Critical golden apple was not consumed through "
                                + "vanilla use: held=" + held
                                + ", remaining=" + remaining
                                + ", absorption="
                                + body.getAbsorptionAmount()
                                + ", health=" + body.getHealth()
                                + ", survival="
                                + CompanionRuntime.active()
                                    .map(runtime -> runtime.survival().state())
                                    .orElse(null)
                    );
                }
        );
    }

    /**
     * Dedicated-server gate for the case the production companion actually
     * needs most: every human is offline, yet the headless AI remains the only
     * vanilla player ticket and its remote work area keeps simulating.
     *
     * <p>The probe is deliberately 640 blocks from the GameTest structure.
     * Both a non-player entity and a scheduled water update must advance
     * there. The test also rejects force-loaded chunks, then kills the body
     * and verifies that ordinary respawn changes the body generation without
     * producing a runtime tick failure.</p>
     */
    @GameTest(
        name = "zero_human_dedicated_server_chunk_and_respawn",
        environment = "exclusive_zero_human_dedicated_server",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 4_000,
        skyAccess = true,
        padding = 8
    )
    public static void zeroHumanDedicatedServerChunkAndRespawn(
            final GameTestHelper helper
    ) {
        final var level = helper.getLevel();
        final var server = level.getServer();
        final BlockPos remoteAnchor = helper.absolutePos(
                FOCUSED_TEST_ORIGIN
        ).offset(640, 0, 0);
        final ChunkPos remoteChunk = new ChunkPos(
                SectionPos.blockToSectionCoord(remoteAnchor.getX()),
                SectionPos.blockToSectionCoord(remoteAnchor.getZ())
        );
        final BlockPos remoteStand = new BlockPos(
                SectionPos.sectionToBlockCoord(remoteChunk.x(), 8),
                remoteAnchor.getY(),
                SectionPos.sectionToBlockCoord(remoteChunk.z(), 8)
        );
        /*
         * Keep the simulation probes outside the body's current chunk. A
         * same-chunk probe cannot distinguish a real player simulation
         * window from GameTestServer's otherwise-zero simulation radius.
         */
        final ChunkPos simulationProbeChunk = new ChunkPos(
                remoteChunk.x() + 2,
                remoteChunk.z() + 1
        );
        final BlockPos simulationProbeStand = new BlockPos(
                SectionPos.sectionToBlockCoord(
                        simulationProbeChunk.x(),
                        8
                ),
                remoteStand.getY(),
                SectionPos.sectionToBlockCoord(
                        simulationProbeChunk.z(),
                        8
                )
        );
        final BlockPos blockTickProbe =
                simulationProbeStand.offset(3, 0, 0);
        final AtomicInteger phase = new AtomicInteger();
        final AtomicLong phaseStartedAt = new AtomicLong(helper.getTick());
        final AtomicLong probeStartAge = new AtomicLong(-1L);
        final AtomicLong bodyGenerationBeforeDeath =
                new AtomicLong(-1L);
        final AtomicLong failureCountBeforeDeath =
                new AtomicLong(-1L);
        final AtomicReference<ItemEntity> probe = new AtomicReference<>();
        final AtomicReference<Mob> damageAttacker =
                new AtomicReference<>();
        final AtomicReference<
                net.minecraft.server.level.ServerPlayer
        > killedBody = new AtomicReference<>();

        helper.addCleanup(ignored -> {
            final ItemEntity currentProbe = probe.get();
            if (currentProbe != null && !currentProbe.isRemoved()) {
                currentProbe.discard();
            }
            final Mob currentAttacker = damageAttacker.get();
            if (currentAttacker != null
                    && !currentAttacker.isRemoved()) {
                currentAttacker.discard();
            }
            if (AiPlayerManager.status(server).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(server);
            }
        });

        /*
         * The ordinary selector runs beside other GameTests on one server.
         * Clear a previous fixture's runtime before requesting this test's
         * body, while preserving the dedicated zero-human startup assertion
         * (that path must observe the production-created body untouched).
         */
        if (!Boolean.getBoolean("mcai.zeroHumanAutoSpawnTest")) {
            GameTestCompanionSpawn.resetForIsolatedFixture(server);
        }
        assertNoHumanPlayers(helper);
        if (Boolean.getBoolean("mcai.zeroHumanAutoSpawnTest")) {
            final var startupStatus = AiPlayerManager.status(server);
            helper.assertTrue(
                    startupStatus.state() != SessionState.ABSENT,
                    "Production ServerStartedEvent did not start the "
                        + "companion with zero humans online"
            );
            helper.assertTrue(
                    startupStatus.state() != SessionState.FAILED,
                    "Production zero-human startup failed: "
                        + startupStatus
            );
        } else {
            final var spawn = AiPlayerManager.requestSpawn(server);
            helper.assertTrue(
                    spawn.accepted(),
                    "Zero-human companion spawn was rejected: "
                        + spawn.code()
            );
        }

        scheduleEveryTick(helper, 4_000L, () -> {
            assertNoHumanPlayers(helper);
            final int currentPhase = phase.get();
            final long now = helper.getTick();

            if (currentPhase == 0) {
                final var status = AiPlayerManager.status(server);
                helper.assertTrue(
                        status.state() != SessionState.FAILED,
                        "Zero-human body failed to become active: "
                            + status
                );
                if (status.state() != SessionState.ACTIVE
                        || !status.online()) {
                    helper.assertTrue(
                            now - phaseStartedAt.get()
                                <= FOCUSED_BODY_START_TIMEOUT_TICKS,
                            "Zero-human body did not become active"
                    );
                    return;
                }

                final var player = AiPlayerManager.onlinePlayer(server)
                        .orElseThrow(() -> helper.assertionException(
                                "Active zero-human body was missing"
                        ));
                helper.assertTrue(
                        server.getPlayerList().getPlayers().size() == 1,
                        "Expected exactly one AI and zero humans, found "
                            + server.getPlayerList()
                                .getPlayers().size()
                            + " player-list entries"
                );
                player.setInvulnerable(true);
                player.setGameMode(GameType.SURVIVAL);
                player.setDeltaMovement(Vec3.ZERO);

                for (int x = -5; x <= 5; x++) {
                    for (int z = -5; z <= 5; z++) {
                        level.setBlockAndUpdate(
                                remoteStand.offset(x, -1, z),
                                Blocks.SMOOTH_STONE
                                        .defaultBlockState()
                        );
                        level.setBlockAndUpdate(
                                remoteStand.offset(x, 0, z),
                                Blocks.AIR.defaultBlockState()
                        );
                        level.setBlockAndUpdate(
                                remoteStand.offset(x, 1, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
                player.teleportTo(
                        remoteStand.getX() + 0.5,
                        remoteStand.getY(),
                        remoteStand.getZ() + 0.5
                );

                phase.set(1);
                phaseStartedAt.set(now);
                return;
            }

            if (currentPhase == 1) {
                final var player = AiPlayerManager.onlinePlayer(server)
                        .orElseThrow(() -> helper.assertionException(
                                "Zero-human body disconnected at its "
                                    + "remote work area"
                        ));
                helper.assertTrue(
                        player.chunkPosition().equals(remoteChunk),
                        "AI did not remain in the remote work chunk"
                );
                if (!level.shouldTickBlocksAt(remoteChunk.pack())
                        || !level.areEntitiesActuallyLoadedAndTicking(
                            remoteChunk
                        )
                        || !level.shouldTickBlocksAt(
                            simulationProbeChunk.pack()
                        )
                        || !level.areEntitiesActuallyLoadedAndTicking(
                            simulationProbeChunk
                        )) {
                    helper.assertTrue(
                            now - phaseStartedAt.get() <= 600L,
                            "The only AI player never established a "
                                + "multi-chunk vanilla entity/block "
                                + "simulation window"
                    );
                    return;
                }

                /*
                 * Do not insert either probe until vanilla has processed the
                 * teleported ServerPlayer and established its own ticket.
                 * Adding an entity to an unloaded far chunk would measure a
                 * fixture race, not player-ticket behavior.
                 */
                level.setBlockAndUpdate(
                        blockTickProbe,
                        Blocks.REDSTONE_LAMP
                                .defaultBlockState()
                                .setValue(
                                    RedstoneLampBlock.LIT,
                                    true
                                )
                );
                level.scheduleTick(
                        blockTickProbe,
                        Blocks.REDSTONE_LAMP,
                        4
                );
                final ItemEntity tickingProbe = new ItemEntity(
                        level,
                        simulationProbeStand.getX() + 0.5,
                        simulationProbeStand.getY() + 0.25,
                        simulationProbeStand.getZ() + 0.5,
                        new ItemStack(Items.COBBLESTONE)
                );
                tickingProbe.setNoGravity(true);
                tickingProbe.setPickUpDelay(32_767);
                helper.assertTrue(
                        level.addFreshEntity(tickingProbe),
                        "Could not add the remote ticking probe"
                );
                probe.set(tickingProbe);
                phase.set(2);
                phaseStartedAt.set(now);
                return;
            }

            if (currentPhase == 2) {
                final ItemEntity currentProbe = probe.get();
                helper.assertTrue(
                        currentProbe != null
                            && !currentProbe.isRemoved(),
                        "Remote entity probe vanished after the AI ticket "
                            + "was established"
                );
                if (!level.isPositionEntityTicking(
                            currentProbe.blockPosition()
                        )) {
                    helper.assertTrue(
                            now - phaseStartedAt.get() <= 100L,
                            "Remote probe did not enter entity-ticking "
                                + "state under the AI player ticket"
                    );
                    return;
                }
                probeStartAge.set(currentProbe.tickCount);
                phase.set(3);
                phaseStartedAt.set(now);
                return;
            }

            if (currentPhase == 3) {
                if (now - phaseStartedAt.get() < 120L) {
                    return;
                }
                final ItemEntity currentProbe = probe.get();
                helper.assertTrue(
                        currentProbe != null
                            && !currentProbe.isRemoved(),
                        "Remote entity probe disappeared during "
                            + "zero-human simulation"
                );
                helper.assertTrue(
                        currentProbe.tickCount
                            - probeStartAge.get() >= 100L,
                        "Remote non-player entity received only "
                            + (currentProbe.tickCount
                                - probeStartAge.get())
                            + " ticks while the AI was the sole player"
                );
                helper.assertTrue(
                        !level.getBlockState(blockTickProbe)
                                .getValue(RedstoneLampBlock.LIT),
                        "Scheduled block simulation did not run in the "
                            + "AI-only remote chunk"
                );
                helper.assertTrue(
                        !level.getChunkSource()
                                .getForceLoadedChunks()
                                .contains(remoteChunk.pack()),
                        "The test remote chunk was force-loaded instead "
                            + "of maintained by the AI's vanilla player "
                            + "ticket"
                );
                helper.assertTrue(
                        !level.getChunkSource()
                                .getForceLoadedChunks()
                                .contains(
                                    simulationProbeChunk.pack()
                                ),
                        "The outlying simulation probe was force-loaded "
                            + "instead of maintained by the AI's vanilla "
                            + "player ticket"
                );

                final var player = AiPlayerManager.onlinePlayer(server)
                        .orElseThrow();
                final var runtime = CompanionRuntime.active()
                        .filter(candidate ->
                                candidate.server() == server
                        )
                        .orElseThrow(() -> helper.assertionException(
                                "Zero-human test lost its production "
                                    + "runtime"
                        ));
                if (runtime.coreFrames().current().isEmpty()) {
                    helper.assertTrue(
                            now - phaseStartedAt.get() <= 320L,
                            "No fair core frame became available for the "
                                + "damage-reaction gate"
                    );
                    return;
                }

                final Mob attacker = EntityTypes.ZOMBIE.create(
                        level,
                        EntitySpawnReason.COMMAND
                );
                helper.assertTrue(
                        attacker != null,
                        "Could not create the damage-cue attacker"
                );
                attacker.setPos(
                        remoteStand.getX() + 2.5,
                        remoteStand.getY(),
                        remoteStand.getZ() + 0.5
                );
                attacker.setNoAi(true);
                helper.assertTrue(
                        level.addFreshEntity(attacker),
                        "Could not add the damage-cue attacker"
                );
                damageAttacker.set(attacker);
                player.setInvulnerable(false);
                player.invulnerableTime = 0;
                helper.assertTrue(
                        player.hurtServer(
                                level,
                                player.damageSources()
                                        .mobAttack(attacker),
                                2.0F
                        ),
                        "Controlled hostile damage did not reach the "
                            + "headless AI"
                );
                player.setInvulnerable(true);
                final var damageFrame = runtime.coreFrames()
                        .current()
                        .orElseThrow(() -> helper.assertionException(
                                "Damage invalidated the current core frame"
                        ));
                helper.assertTrue(
                        damageFrame.dangerSignals().stream()
                                .anyMatch(signal ->
                                        signal.provenance()
                                            == PerceptionProvenance
                                                .RECENT_DAMAGE_EVENT
                                            && signal.contactDirection()
                                                .isPresent()
                                ),
                        "A hostile hit outside the current view did not "
                            + "produce a directional recent-damage cue"
                );
                final var reaction = runtime.survival().tick(
                        false,
                        false
                );
                helper.assertTrue(
                        reaction.intervened()
                            && reaction.state()
                                != dev.mcai.companion.skills.core
                                    .EmergencySurvivalController
                                    .State.CLEAR,
                        "Local survival controller ignored a confirmed "
                            + "hostile hit: " + reaction
                );
                runtime.survival().reset();
                attacker.discard();
                player.setHealth(player.getMaxHealth());
                killedBody.set(player);
                bodyGenerationBeforeDeath.set(
                        AiPlayerManager.status(server)
                                .sessionGeneration()
                );
                failureCountBeforeDeath.set(
                        CompanionRuntime.runtimeFailureAudit(server)
                                .failureCount()
                );
                player.setInvulnerable(false);
                player.kill(level);
                phase.set(4);
                phaseStartedAt.set(now);
                return;
            }

            helper.assertTrue(
                    CompanionRuntime.runtimeFailureAudit(server)
                            .failureCount()
                        == failureCountBeforeDeath.get(),
                    "Companion runtime threw while the headless player "
                        + "was dead or respawning"
            );
            if (currentPhase == 4) {
                final var replacement =
                        AiPlayerManager.onlinePlayer(server);
                if (replacement.isEmpty()
                        || replacement.orElseThrow()
                            == killedBody.get()
                        || !replacement.orElseThrow().isAlive()
                        || replacement.orElseThrow().getHealth()
                            <= 0.0F) {
                    helper.assertTrue(
                            now - phaseStartedAt.get() <= 200L,
                            "Ordinary zero-human respawn did not produce "
                                + "a fresh living ServerPlayer"
                    );
                    return;
                }
                helper.assertTrue(
                        AiPlayerManager.status(server)
                                .sessionGeneration()
                            > bodyGenerationBeforeDeath.get(),
                        "Respawn replaced the body without advancing the "
                            + "body-session generation"
                );
                replacement.orElseThrow().setInvulnerable(true);
                phase.set(5);
                phaseStartedAt.set(now);
                return;
            }

            if (now - phaseStartedAt.get() < 60L) {
                return;
            }
            final var stableBody = AiPlayerManager.onlinePlayer(server)
                    .orElseThrow(() -> helper.assertionException(
                            "Respawned zero-human body disconnected"
                    ));
            helper.assertTrue(
                    stableBody.isAlive()
                        && stableBody.getHealth() > 0.0F,
                    "Respawned zero-human body did not remain stable"
            );
            helper.assertTrue(
                    AiPlayerManager.status(server).state()
                        == SessionState.ACTIVE,
                    "Respawned zero-human session is not ACTIVE"
            );
            helper.succeed();
        });
    }

    /**
     * Regression for a clientless player crossing into a different level at
     * exactly the same section coordinate. A real client follows a dimension
     * transition with movement packets, but the server-driven companion has
     * no packet stream to refresh the destination level's player ticket.
     * Comparing only {@link SectionPos} therefore left the ticket behind.
     *
     * <p>The fixture neither prepares nor force-loads the Nether destination.
     * Only after the companion's ordinary player ticket makes both the body
     * chunk and an outlying chunk entity-ticking does the test add its
     * scheduled-block and entity probes. This keeps the oracle focused on
     * vanilla player tracking rather than a fixture-owned chunk load.</p>
     */
    @GameTest(
        name = "zero_human_cross_dimension_chunk_tracking",
        environment = "exclusive_zero_human_cross_dimension_tracking",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = CROSS_DIMENSION_TICKET_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void zeroHumanCrossDimensionChunkTracking(
            final GameTestHelper helper
    ) {
        final var overworld = helper.getLevel();
        final var server = overworld.getServer();
        final var nether = server.getLevel(Level.NETHER);
        helper.assertTrue(
                nether != null,
                "Cross-dimension ticket gate could not access the Nether"
        );

        final BlockPos fixtureAnchor = helper.absolutePos(
                FOCUSED_TEST_ORIGIN
        );
        /*
         * Stay well outside every test structure and the Nether's ordinary
         * origin region. The first teleport establishes an Overworld player
         * ticket; the second uses the identical XYZ section in the Nether so
         * the former section-only session check cannot pass accidentally.
         */
        final BlockPos sharedFeet = new BlockPos(
                fixtureAnchor.getX() + 3_072,
                192,
                fixtureAnchor.getZ() + 1_536
        );
        final ChunkPos bodyChunk = new ChunkPos(
                SectionPos.blockToSectionCoord(sharedFeet.getX()),
                SectionPos.blockToSectionCoord(sharedFeet.getZ())
        );
        final ChunkPos simulationProbeChunk = new ChunkPos(
                bodyChunk.x() + 2,
                bodyChunk.z() + 1
        );
        final BlockPos simulationProbeStand = new BlockPos(
                SectionPos.sectionToBlockCoord(
                        simulationProbeChunk.x(),
                        8
                ),
                sharedFeet.getY(),
                SectionPos.sectionToBlockCoord(
                        simulationProbeChunk.z(),
                        8
                )
        );
        final BlockPos blockTickProbe =
                simulationProbeStand.offset(3, 0, 0);
        final AtomicInteger phase = new AtomicInteger();
        final AtomicLong phaseStartedAt = new AtomicLong(helper.getTick());
        final AtomicLong phaseStartedNanos = new AtomicLong(
                System.nanoTime()
        );
        final AtomicLong probeStartAge = new AtomicLong(-1L);
        final AtomicReference<SectionPos> sourceSection =
                new AtomicReference<>();
        final AtomicReference<ItemEntity> probe = new AtomicReference<>();

        helper.addCleanup(ignored -> {
            final ItemEntity currentProbe = probe.get();
            if (currentProbe != null && !currentProbe.isRemoved()) {
                currentProbe.discard();
            }
            if (AiPlayerManager.status(server).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(server);
            }
        });

        assertNoHumanPlayers(helper);
        final var spawn = GameTestCompanionSpawn.request(
                helper,
                FOCUSED_TEST_ORIGIN
        );
        helper.assertTrue(
                spawn.accepted(),
                "Cross-dimension companion spawn was rejected: "
                    + spawn.code()
        );

        scheduleEveryTick(
                helper,
                CROSS_DIMENSION_TICKET_TEST_MAX_TICKS,
                () -> {
                    assertNoHumanPlayers(helper);
                    final long now = helper.getTick();

                    if (phase.get() == 0) {
                        final var status = AiPlayerManager.status(server);
                        helper.assertTrue(
                                status.state() != SessionState.FAILED,
                                "Cross-dimension body failed: " + status
                        );
                        if (status.state() != SessionState.ACTIVE
                                || !status.online()) {
                            helper.assertTrue(
                                    now - phaseStartedAt.get()
                                        <= FOCUSED_BODY_START_TIMEOUT_TICKS,
                                    "Cross-dimension body did not become "
                                        + "active"
                            );
                            return;
                        }

                        final var runtime = CompanionRuntime.active()
                                .filter(candidate ->
                                        candidate.server() == server
                                )
                                .orElseThrow(() ->
                                        helper.assertionException(
                                            "Cross-dimension runtime is "
                                                + "unavailable"
                                        ));
                        runtime.brain().close();
                        runtime.survival().reset();
                        runtime.coreActions().quiesceNow();
                        runtime.interactionActions().quiesceNow();
                        runtime.skillSupervisor()
                                .abandonForSessionEnd();

                        final var player = AiPlayerManager
                                .onlinePlayer(server)
                                .orElseThrow();
                        player.setInvulnerable(true);
                        player.setNoGravity(true);
                        player.noPhysics = true;
                        player.setDeltaMovement(Vec3.ZERO);
                        player.teleportTo(
                                sharedFeet.getX() + 0.5D,
                                sharedFeet.getY(),
                                sharedFeet.getZ() + 0.5D
                        );
                        phase.set(1);
                        phaseStartedAt.set(now);
                        phaseStartedNanos.set(System.nanoTime());
                        return;
                    }

                    if (phase.get() == 1) {
                        final var player = AiPlayerManager
                                .onlinePlayer(server)
                                .orElseThrow(() ->
                                        helper.assertionException(
                                            "Cross-dimension body "
                                                + "disconnected in the "
                                                + "Overworld"
                                        ));
                        helper.assertTrue(
                                player.level() == overworld,
                                "Body changed dimension before the gate"
                        );
                        if (!overworld.isPositionEntityTicking(
                                    player.blockPosition()
                                )) {
                            awaitVanillaPlayerTicket(
                                    helper,
                                    phaseStartedAt.get(),
                                    phaseStartedNanos.get(),
                                    "Overworld staging chunk"
                            );
                            return;
                        }

                        helper.assertTrue(
                                !nether.getChunkSource()
                                    .getForceLoadedChunks()
                                    .contains(bodyChunk.pack())
                                    && !nether.getChunkSource()
                                        .getForceLoadedChunks()
                                        .contains(
                                            simulationProbeChunk.pack()
                                        ),
                                "Nether destination was force-loaded before "
                                    + "the companion entered it"
                        );
                        helper.assertTrue(
                                !nether.shouldTickBlocksAt(
                                    bodyChunk.pack()
                                )
                                    && !nether
                                        .areEntitiesActuallyLoadedAndTicking(
                                            bodyChunk
                                        ),
                                "Nether destination already had an active "
                                    + "simulation ticket before traversal"
                        );

                        sourceSection.set(SectionPos.of(player));
                        final boolean teleported = player.teleportTo(
                                nether,
                                sharedFeet.getX() + 0.5D,
                                sharedFeet.getY(),
                                sharedFeet.getZ() + 0.5D,
                                Set.of(),
                                player.getYRot(),
                                player.getXRot(),
                                false
                        );
                        helper.assertTrue(
                                teleported,
                                "Vanilla cross-dimension teleport was rejected"
                        );
                        player.setInvulnerable(true);
                        player.setNoGravity(true);
                        player.noPhysics = true;
                        player.setDeltaMovement(Vec3.ZERO);
                        helper.assertTrue(
                                player.level() == nether,
                                "Body did not enter the Nether"
                        );
                        helper.assertTrue(
                                SectionPos.of(player).equals(
                                    sourceSection.get()
                                ),
                                "Fixture did not preserve the same section "
                                    + "coordinate across dimensions"
                        );
                        phase.set(2);
                        phaseStartedAt.set(now);
                        phaseStartedNanos.set(System.nanoTime());
                        return;
                    }

                    if (phase.get() == 2) {
                        final var player = AiPlayerManager
                                .onlinePlayer(server)
                                .orElseThrow(() ->
                                        helper.assertionException(
                                            "Cross-dimension body "
                                                + "disconnected in the "
                                                + "Nether"
                                        ));
                        helper.assertTrue(
                                player.level() == nether,
                                "Body left the Nether before its ticket "
                                    + "settled"
                        );
                        final boolean destinationSimulating =
                                nether.shouldTickBlocksAt(bodyChunk.pack())
                                    && nether
                                        .areEntitiesActuallyLoadedAndTicking(
                                            bodyChunk
                                        )
                                    && nether.shouldTickBlocksAt(
                                        simulationProbeChunk.pack()
                                    )
                                    && nether
                                        .areEntitiesActuallyLoadedAndTicking(
                                            simulationProbeChunk
                                        );
                        if (!destinationSimulating) {
                            awaitVanillaPlayerTicket(
                                    helper,
                                    phaseStartedAt.get(),
                                    phaseStartedNanos.get(),
                                    "same-section Nether destination window"
                            );
                            return;
                        }

                        helper.assertTrue(
                                server.getPlayerList().getPlayers().size()
                                    == 1,
                                "Expected one AI and zero humans after "
                                    + "dimension travel"
                        );
                        helper.assertTrue(
                                !nether.getChunkSource()
                                    .getForceLoadedChunks()
                                    .contains(bodyChunk.pack())
                                    && !nether.getChunkSource()
                                        .getForceLoadedChunks()
                                        .contains(
                                            simulationProbeChunk.pack()
                                        ),
                                "Destination simulation used a force-loaded "
                                    + "chunk instead of the AI player ticket"
                        );

                        nether.setBlockAndUpdate(
                                blockTickProbe,
                                Blocks.REDSTONE_LAMP
                                    .defaultBlockState()
                                    .setValue(
                                        RedstoneLampBlock.LIT,
                                        true
                                    )
                        );
                        nether.scheduleTick(
                                blockTickProbe,
                                Blocks.REDSTONE_LAMP,
                                4
                        );
                        final ItemEntity tickingProbe = new ItemEntity(
                                nether,
                                simulationProbeStand.getX() + 0.5D,
                                simulationProbeStand.getY() + 0.25D,
                                simulationProbeStand.getZ() + 0.5D,
                                new ItemStack(Items.COBBLESTONE)
                        );
                        tickingProbe.setNoGravity(true);
                        tickingProbe.setPickUpDelay(32_767);
                        helper.assertTrue(
                                nether.addFreshEntity(tickingProbe),
                                "Could not add the Nether ticking probe"
                        );
                        probe.set(tickingProbe);
                        phase.set(3);
                        phaseStartedAt.set(now);
                        return;
                    }

                    if (phase.get() == 3) {
                        final ItemEntity currentProbe = probe.get();
                        helper.assertTrue(
                                currentProbe != null
                                    && !currentProbe.isRemoved(),
                                "Nether entity probe vanished after ticket "
                                    + "settlement"
                        );
                        if (!nether.isPositionEntityTicking(
                                    currentProbe.blockPosition()
                                )) {
                            helper.assertTrue(
                                    now - phaseStartedAt.get() <= 100L,
                                    "Nether probe never entered the "
                                        + "entity-ticking state"
                            );
                            return;
                        }
                        probeStartAge.set(currentProbe.tickCount);
                        phase.set(4);
                        phaseStartedAt.set(now);
                        return;
                    }

                    if (now - phaseStartedAt.get() < 120L) {
                        return;
                    }
                    final ItemEntity currentProbe = probe.get();
                    helper.assertTrue(
                            currentProbe != null
                                && !currentProbe.isRemoved(),
                            "Nether entity probe disappeared during the "
                                + "cross-dimension simulation window"
                    );
                    helper.assertTrue(
                            currentProbe.tickCount
                                - probeStartAge.get() >= 100L,
                            "Nether entity probe received only "
                                + (currentProbe.tickCount
                                    - probeStartAge.get())
                                + " ticks from the AI player window"
                    );
                    helper.assertTrue(
                            !nether.getBlockState(blockTickProbe)
                                .getValue(RedstoneLampBlock.LIT),
                            "Scheduled Nether block tick did not run under "
                                + "the AI player ticket"
                    );
                    helper.assertTrue(
                            !overworld.shouldTickBlocksAt(bodyChunk.pack())
                                && !overworld
                                    .areEntitiesActuallyLoadedAndTicking(
                                        bodyChunk
                                    ),
                            "The old Overworld simulation window remained "
                                + "active after the sole player left"
                    );
                    helper.succeed();
                }
        );
    }

    private static void awaitVanillaPlayerTicket(
            final GameTestHelper helper,
            final long startedAtTick,
            final long startedAtNanos,
            final String windowDescription
    ) {
        final long elapsedTicks = helper.getTick() - startedAtTick;
        final long elapsedNanos = System.nanoTime() - startedAtNanos;
        /*
         * Chunk promotion uses async workers while GameTest ticks are
         * normally unthrottled. Yield one bounded millisecond without adding
         * any ticket so the test observes the vanilla player's own window.
         */
        LockSupport.parkNanos(FOCUSED_ASYNC_CHUNK_YIELD_NANOS);
        helper.assertTrue(
                elapsedTicks <= FOCUSED_SIMULATION_TICKET_TIMEOUT_TICKS
                    || elapsedNanos
                        <= FOCUSED_SIMULATION_TICKET_TIMEOUT_NANOS,
                "Headless player's vanilla ticket did not establish the "
                    + windowDescription + ": ticks=" + elapsedTicks
                    + ", wallMillis="
                    + java.time.Duration.ofNanos(elapsedNanos)
                        .toMillis()
        );
    }

    private static void assertNoHumanPlayers(
            final GameTestHelper helper
    ) {
        final var players = helper.getLevel()
                .getServer()
                .getPlayerList()
                .getPlayers();
        final long humanPlayers = players.stream()
                .filter(player -> !AiProfileMarker.isMarked(
                        player.getGameProfile()
                ))
                .count();
        helper.assertTrue(
                humanPlayers == 0L,
                "Dedicated zero-human gate observed "
                    + humanPlayers + " human player(s)"
        );
    }

    @GameTest(
        name = "real_water_clutch",
        environment = "exclusive_real_water_clutch",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = WATER_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realWaterClutch(final GameTestHelper helper) {
        runFocusedMovementScenario(
            helper,
            ScenarioScope.WATER_ONLY,
            WATER_TEST_MAX_TICKS
        );
    }

    @GameTest(
        name = "real_parkour_course",
        environment = "exclusive_real_parkour",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = PARKOUR_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realParkourCourse(final GameTestHelper helper) {
        runFocusedMovementScenario(
            helper,
            ScenarioScope.PARKOUR_ONLY,
            PARKOUR_TEST_MAX_TICKS
        );
    }

    @GameTest(
        name = "real_travel_diagonal_detour",
        environment = "exclusive_real_travel_detour",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = TRAVEL_DETOUR_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realTravelDiagonalDetour(
            final GameTestHelper helper
    ) {
        runFocusedMovementScenario(
            helper,
            ScenarioScope.TRAVEL_DETOUR_ONLY,
            TRAVEL_DETOUR_TEST_MAX_TICKS
        );
    }

    @GameTest(
        name = "real_verified_portal_return",
        environment = "exclusive_real_verified_portal_return",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = PORTAL_RETURN_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realVerifiedPortalReturn(
            final GameTestHelper helper
    ) {
        runFocusedMovementScenario(
            helper,
            ScenarioScope.PORTAL_RETURN_ONLY,
            PORTAL_RETURN_TEST_MAX_TICKS
        );
    }

    @GameTest(
        name = "real_nether_blaze_rod_acquisition",
        environment = "exclusive_real_nether_blaze_rod",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = BLAZE_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realNetherBlazeRodAcquisition(
            final GameTestHelper helper
    ) {
        runFocusedMovementScenario(
            helper,
            ScenarioScope.BLAZE_ONLY,
            BLAZE_TEST_MAX_TICKS
        );
    }

    @GameTest(
        name = "real_nether_blaze_material_reserve",
        environment = "exclusive_real_nether_blaze_material_reserve",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = BLAZE_RESERVE_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realNetherBlazeMaterialReserve(
            final GameTestHelper helper
    ) {
        runFocusedMovementScenario(
            helper,
            ScenarioScope.BLAZE_RESERVE_ONLY,
            BLAZE_RESERVE_TEST_MAX_TICKS
        );
    }

    @GameTest(
        name = "real_ender_pearl_reserve",
        environment = "exclusive_real_ender_pearl_reserve",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = ENDER_RESERVE_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realEnderPearlReserve(
            final GameTestHelper helper
    ) {
        runFocusedMovementScenario(
            helper,
            ScenarioScope.ENDER_RESERVE_ONLY,
            ENDER_RESERVE_TEST_MAX_TICKS
        );
    }

    @GameTest(
        name = "real_sheltered_ender_pearl_acquisition",
        environment = "exclusive_real_sheltered_ender_pearl_acquisition",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = ENDER_SINGLE_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realShelteredEnderPearlAcquisition(
            final GameTestHelper helper
    ) {
        runFocusedMovementScenario(
            helper,
            ScenarioScope.ENDER_SINGLE_ONLY,
            ENDER_SINGLE_TEST_MAX_TICKS
        );
    }

    @GameTest(
        name = "real_stronghold_triangulation",
        environment = "exclusive_real_stronghold_triangulation",
        structure = "forge:empty600x24x600",
        maxTicks = STRONGHOLD_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realStrongholdTriangulation(
            final GameTestHelper helper
    ) {
        runFocusedMovementScenario(
            helper,
            ScenarioScope.STRONGHOLD_ONLY,
            STRONGHOLD_TEST_MAX_TICKS
        );
    }

    @GameTest(
        name = "real_stronghold_reach",
        environment = "exclusive_real_stronghold_reach",
        structure = "forge:empty600x24x600",
        maxTicks = STRONGHOLD_REACH_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realStrongholdReach(
            final GameTestHelper helper
    ) {
        runFocusedMovementScenario(
            helper,
            ScenarioScope.STRONGHOLD_REACH_ONLY,
            STRONGHOLD_REACH_TEST_MAX_TICKS
        );
    }

    @GameTest(
        name = "real_end_portal_activation",
        environment = "exclusive_real_end_portal_activation",
        structure = TEST_STRUCTURE,
        maxTicks = END_PORTAL_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realEndPortalActivation(
            final GameTestHelper helper
    ) {
        runFocusedMovementScenario(
            helper,
            ScenarioScope.END_PORTAL_ONLY,
            END_PORTAL_TEST_MAX_TICKS
        );
    }

    @GameTest(
        name = "real_end_victory_and_return",
        environment = "exclusive_real_end_victory",
        structure = TEST_STRUCTURE,
        maxTicks = END_VICTORY_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void realEndVictoryAndReturn(
            final GameTestHelper helper
    ) {
        runFocusedMovementScenario(
                helper,
                ScenarioScope.END_VICTORY_ONLY,
                END_VICTORY_TEST_MAX_TICKS
        );
    }

    /**
     * Release-excluded physics/perception baseline for the dragon skill.
     * This deliberately bypasses the model lane: it proves that a real
     * ServerPlayer can perceive the multipart dragon and finish the bounded
     * fight before the live-model scenario is allowed to claim a regression
     * fix.  It has a distinct name so the live-model selector cannot mask it.
     */
    @GameTest(
        name = "offline_end_victory_skill_baseline",
        environment = "exclusive_offline_end_victory_baseline",
        structure = TEST_STRUCTURE,
        maxTicks = END_VICTORY_TEST_MAX_TICKS,
        skyAccess = true,
        padding = 8
    )
    public static void offlineEndVictorySkillBaseline(
            final GameTestHelper helper
    ) {
        runFocusedMovementScenario(
                helper,
                ScenarioScope.END_VICTORY_ONLY,
                END_VICTORY_TEST_MAX_TICKS
        );
    }

    @GameTest(
        name = "verified_shelter_evidence",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 200,
        skyAccess = true,
        padding = 8
    )
    public static void verifiedShelterEvidence(
            final GameTestHelper helper
    ) {
        final var level = helper.getLevel();
        final BlockPos origin = helper.absolutePos(
                new BlockPos(12, 4, 12)
        );
        final int exterior = 5;
        for (int x = 0; x < exterior; x++) {
            for (int z = 0; z < exterior; z++) {
                level.setBlock(
                        origin.offset(x, -1, z),
                        Blocks.COBBLESTONE.defaultBlockState(),
                        2
                );
                level.setBlock(
                        origin.offset(x, 2, z),
                        Blocks.COBBLESTONE.defaultBlockState(),
                        2
                );
                if (x != 0
                        && x != exterior - 1
                        && z != 0
                        && z != exterior - 1) {
                    continue;
                }
                for (int y = 0; y < 2; y++) {
                    level.setBlock(
                            origin.offset(x, y, z),
                            Blocks.COBBLESTONE.defaultBlockState(),
                            2
                    );
                }
            }
        }
        final BlockPos doorLower = origin.offset(0, 0, 2);
        level.setBlock(
                doorLower,
                Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(
                                DoorBlock.HALF,
                                DoubleBlockHalf.LOWER
                        )
                        .setValue(DoorBlock.FACING, Direction.WEST)
                        .setValue(DoorBlock.OPEN, false),
                2
        );
        level.setBlock(
                doorLower.above(),
                Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(
                                DoorBlock.HALF,
                                DoubleBlockHalf.UPPER
                        )
                        .setValue(DoorBlock.FACING, Direction.WEST)
                        .setValue(DoorBlock.OPEN, false),
                2
        );
        final BlockPos light = origin.offset(2, 0, 2);
        level.setBlock(
                light,
                Blocks.TORCH.defaultBlockState(),
                3
        );
        final VerifiedShelterEvidence evidence =
                new VerifiedShelterEvidence(
                        1L,
                        level.dimension().identifier().toString(),
                        origin.getX(),
                        origin.getY(),
                        origin.getZ(),
                        3,
                        3,
                        2,
                        doorLower.getX(),
                        doorLower.getY(),
                        doorLower.getZ(),
                        light.getX(),
                        light.getY(),
                        light.getZ(),
                        "minecraft:cobblestone",
                        "minecraft:torch"
                );

        helper.runAtTickTime(20, () -> {
            helper.assertTrue(
                    ServerShelterEvidenceVerifier.verify(
                            level.getServer(),
                            evidence
                    ),
                    "Completed shelter evidence did not reverify"
            );
            final BlockPos breached = origin.offset(4, 1, 2);
            level.setBlock(
                    breached,
                    Blocks.AIR.defaultBlockState(),
                    3
            );
            helper.assertTrue(
                    !ServerShelterEvidenceVerifier.verify(
                            level.getServer(),
                            evidence
                    ),
                    "A breached wall remained verified"
            );
            level.setBlock(
                    breached,
                    Blocks.COBBLESTONE.defaultBlockState(),
                    3
            );
            level.setBlock(
                    doorLower,
                    level.getBlockState(doorLower)
                            .setValue(DoorBlock.OPEN, true),
                    3
            );
            helper.assertTrue(
                    !ServerShelterEvidenceVerifier.verify(
                            level.getServer(),
                            evidence
                    ),
                    "An open entrance remained isolated"
            );
            helper.succeed();
        });
    }

    @GameTest(
        name = "verified_foundation_evidence",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 200,
        skyAccess = true,
        padding = 8
    )
    public static void verifiedFoundationEvidence(
            final GameTestHelper helper
    ) {
        final var level = helper.getLevel();
        final BlockPos crafting = helper.absolutePos(
                new BlockPos(8, 4, 8)
        );
        final BlockPos furnace = crafting.east();
        final BlockPos chest = furnace.east();
        level.setBlock(
                crafting,
                Blocks.CRAFTING_TABLE.defaultBlockState(),
                3
        );
        level.setBlock(
                furnace,
                Blocks.FURNACE.defaultBlockState(),
                3
        );
        level.setBlock(
                chest,
                Blocks.CHEST.defaultBlockState(),
                3
        );
        final Container storage = (Container) level.getBlockEntity(chest);
        storage.setItem(0, new ItemStack(Items.COBBLESTONE, 16));
        storage.setChanged();
        final String dimension =
                level.dimension().identifier().toString();
        final VerifiedFoundationEvidence evidence =
                new VerifiedFoundationEvidence(
                        1L,
                        java.util.Optional.of(location(
                                dimension,
                                crafting
                        )),
                        java.util.Optional.of(location(
                                dimension,
                                furnace
                        )),
                        java.util.Optional.of(location(
                                dimension,
                                chest
                        )),
                        "minecraft:cobblestone",
                        16
                );

        helper.runAtTickTime(20, () -> {
            final var valid =
                    ServerFoundationEvidenceVerifier.verify(
                            level.getServer(),
                            evidence
                    );
            helper.assertTrue(
                    valid.workstationsEstablished()
                            && valid.suppliesStored(),
                    "Completed foundation evidence did not reverify"
            );
            storage.clearContent();
            storage.setItem(0, new ItemStack(Items.DIRT, 16));
            final var emptied =
                    ServerFoundationEvidenceVerifier.verify(
                            level.getServer(),
                            evidence
                    );
            helper.assertTrue(
                    emptied.workstationsEstablished()
                            && !emptied.suppliesStored(),
                    "Unrelated chest contents replaced deposited evidence"
            );
            level.setBlock(
                    furnace,
                    Blocks.AIR.defaultBlockState(),
                    3
            );
            final var damaged =
                    ServerFoundationEvidenceVerifier.verify(
                            level.getServer(),
                            evidence
                    );
            helper.assertTrue(
                    !damaged.workstationsEstablished()
                            && !damaged.suppliesStored(),
                    "A missing furnace remained verified"
            );
            helper.succeed();
        });
    }

    @GameTest(
        name = "visible_entity_placement_occupancy",
        environment = "exclusive_visible_entity_placement_occupancy",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 4_000,
        skyAccess = true,
        padding = 8
    )
    public static void visibleEntityPlacementOccupancy(
            final GameTestHelper helper
    ) {
        BuildingGameTests.visibleEntityPlacementOccupancy(helper);
    }

    @GameTest(
        name = "placement_obstruction_recovery",
        environment = "exclusive_placement_obstruction_recovery",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 4_000,
        skyAccess = true,
        padding = 8
    )
    public static void placementObstructionRecovery(
            final GameTestHelper helper
    ) {
        BuildingGameTests.placementObstructionRecovery(helper);
    }

    @GameTest(
        name = "partial_shelter_obstruction_recovery",
        environment = "exclusive_partial_shelter_obstruction_recovery",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 6_000,
        skyAccess = true,
        padding = 8
    )
    public static void partialShelterObstructionRecovery(
            final GameTestHelper helper
    ) {
        BuildingGameTests.partialShelterObstructionRecovery(helper);
    }

    @GameTest(
        name = "roof_jump_placement",
        environment = "exclusive_roof_jump_placement",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 10_000,
        skyAccess = true,
        padding = 8
    )
    public static void roofJumpPlacement(
            final GameTestHelper helper
    ) {
        BuildingGameTests.roofJumpPlacement(helper);
    }

    @GameTest(
        name = "current_support_mining_guard",
        environment = "exclusive_current_support_mining_guard",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 2_000,
        skyAccess = true,
        padding = 8
    )
    public static void currentSupportMiningGuard(
            final GameTestHelper helper
    ) {
        BuildingGameTests.currentSupportMiningGuard(helper);
    }

    @GameTest(
        name = "reachable_basic_crafting",
        environment = "exclusive_reachable_basic_crafting",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 4_000,
        skyAccess = true,
        padding = 8
    )
    public static void reachableBasicCrafting(
            final GameTestHelper helper
    ) {
        FoundationGameTests.reachableBasicCrafting(helper);
    }

    @GameTest(
        name = "occluded_iron_toolkit_table",
        environment = "exclusive_occluded_iron_toolkit_table",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 4_000,
        skyAccess = true,
        padding = 8
    )
    public static void occludedIronToolkitTable(
            final GameTestHelper helper
    ) {
        FoundationGameTests.occludedIronToolkitTable(helper);
    }

    @GameTest(
        name = "container_wood_door_spruce",
        environment = "exclusive_container_wood_door_spruce",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 6_000,
        skyAccess = true,
        padding = 8
    )
    public static void containerWoodDoorSpruce(
            final GameTestHelper helper
    ) {
        FoundationGameTests.containerWoodDoorSpruce(helper);
    }

    @GameTest(
        name = "container_wood_door_warped",
        environment = "exclusive_container_wood_door_warped",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 6_000,
        skyAccess = true,
        padding = 8
    )
    public static void containerWoodDoorWarped(
            final GameTestHelper helper
    ) {
        FoundationGameTests.containerWoodDoorWarped(helper);
    }

    @GameTest(
        name = "container_wood_door_oak",
        environment = "exclusive_container_wood_door_oak",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 6_000,
        skyAccess = true,
        padding = 8
    )
    public static void containerWoodDoorOak(final GameTestHelper helper) {
        FoundationGameTests.containerWoodDoorOak(helper);
    }

    @GameTest(
        name = "container_wood_door_birch",
        environment = "exclusive_container_wood_door_birch",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 6_000,
        skyAccess = true,
        padding = 8
    )
    public static void containerWoodDoorBirch(final GameTestHelper helper) {
        FoundationGameTests.containerWoodDoorBirch(helper);
    }

    @GameTest(
        name = "container_wood_door_jungle",
        environment = "exclusive_container_wood_door_jungle",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 6_000,
        skyAccess = true,
        padding = 8
    )
    public static void containerWoodDoorJungle(final GameTestHelper helper) {
        FoundationGameTests.containerWoodDoorJungle(helper);
    }

    @GameTest(
        name = "container_wood_door_acacia",
        environment = "exclusive_container_wood_door_acacia",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 6_000,
        skyAccess = true,
        padding = 8
    )
    public static void containerWoodDoorAcacia(final GameTestHelper helper) {
        FoundationGameTests.containerWoodDoorAcacia(helper);
    }

    @GameTest(
        name = "container_wood_door_dark_oak",
        environment = "exclusive_container_wood_door_dark_oak",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 6_000,
        skyAccess = true,
        padding = 8
    )
    public static void containerWoodDoorDarkOak(
            final GameTestHelper helper
    ) {
        FoundationGameTests.containerWoodDoorDarkOak(helper);
    }

    @GameTest(
        name = "container_wood_door_mangrove",
        environment = "exclusive_container_wood_door_mangrove",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 6_000,
        skyAccess = true,
        padding = 8
    )
    public static void containerWoodDoorMangrove(
            final GameTestHelper helper
    ) {
        FoundationGameTests.containerWoodDoorMangrove(helper);
    }

    @GameTest(
        name = "container_wood_door_cherry",
        environment = "exclusive_container_wood_door_cherry",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 6_000,
        skyAccess = true,
        padding = 8
    )
    public static void containerWoodDoorCherry(final GameTestHelper helper) {
        FoundationGameTests.containerWoodDoorCherry(helper);
    }

    @GameTest(
        name = "container_wood_door_crimson",
        environment = "exclusive_container_wood_door_crimson",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 6_000,
        skyAccess = true,
        padding = 8
    )
    public static void containerWoodDoorCrimson(
            final GameTestHelper helper
    ) {
        FoundationGameTests.containerWoodDoorCrimson(helper);
    }

    @GameTest(
        name = "shelter_material_wood_exploration",
        environment = "exclusive_shelter_material_wood_exploration",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 8_000,
        skyAccess = true,
        padding = 8
    )
    public static void shelterMaterialWoodExploration(
            final GameTestHelper helper
    ) {
        ShelterMaterialExplorationGameTests
                .shelterMaterialWoodExploration(helper);
    }

    @GameTest(
        name = "workstation_wood_prerequisite_composition",
        environment =
            "exclusive_workstation_wood_prerequisite_composition",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 12_000,
        skyAccess = true,
        padding = 8
    )
    public static void workstationWoodPrerequisiteComposition(
            final GameTestHelper helper
    ) {
        WorkstationPrerequisiteGameTests
                .workstationWoodPrerequisiteComposition(helper);
    }

    @GameTest(
        name = "real_furnace_batch",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 800,
        skyAccess = true,
        padding = 8
    )
    public static void realFurnaceBatch(
            final GameTestHelper helper
    ) {
        MenuGameTests.naturalSmeltingBatch(helper);
    }

    @GameTest(
        name = "real_charcoal_furnace_batch",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 800,
        skyAccess = true,
        padding = 8
    )
    public static void realCharcoalFurnaceBatch(
            final GameTestHelper helper
    ) {
        MenuGameTests.naturalCharcoalBatch(helper);
    }

    @GameTest(
        name = "real_blast_furnace_batch",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 800,
        skyAccess = true,
        padding = 8
    )
    public static void realBlastFurnaceBatch(
            final GameTestHelper helper
    ) {
        MenuGameTests.naturalBlastFurnaceBatch(helper);
    }

    @GameTest(
        name = "real_smoker_batch",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 800,
        skyAccess = true,
        padding = 8
    )
    public static void realSmokerBatch(
            final GameTestHelper helper
    ) {
        MenuGameTests.naturalSmokerBatch(helper);
    }

    @GameTest(
        name = "real_cartography_table_transaction",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 100,
        skyAccess = true,
        padding = 8
    )
    public static void realCartographyTableTransaction(
            final GameTestHelper helper
    ) {
        MenuGameTests.cartographyTableTransaction(helper);
    }

    @GameTest(
        name = "real_stonecutter_transaction",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 100,
        skyAccess = true,
        padding = 8
    )
    public static void realStonecutterTransaction(
            final GameTestHelper helper
    ) {
        MenuGameTests.stonecutterTransaction(helper);
    }

    @GameTest(
        name = "real_barrel_transaction",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 100,
        skyAccess = true,
        padding = 8
    )
    public static void realBarrelTransaction(
            final GameTestHelper helper
    ) {
        MenuGameTests.barrelTransaction(helper);
    }

    @GameTest(
        name = "real_shulker_box_transaction",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 100,
        skyAccess = true,
        padding = 8
    )
    public static void realShulkerBoxTransaction(
            final GameTestHelper helper
    ) {
        MenuGameTests.shulkerBoxTransaction(helper);
    }

    @GameTest(
        name = "real_hopper_transaction",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 100,
        skyAccess = true,
        padding = 8
    )
    public static void realHopperTransaction(
            final GameTestHelper helper
    ) {
        MenuGameTests.hopperTransaction(helper);
    }

    @GameTest(
        name = "real_dispenser_transaction",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 100,
        skyAccess = true,
        padding = 8
    )
    public static void realDispenserTransaction(
            final GameTestHelper helper
    ) {
        MenuGameTests.dispenserTransaction(helper);
    }

    @GameTest(
        name = "real_dispenser_button_activation",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 100,
        skyAccess = true,
        padding = 8
    )
    public static void realDispenserButtonActivation(
            final GameTestHelper helper
    ) {
        RedstoneGameTests.dispenserButtonActivation(helper);
    }

    @GameTest(
        name = "real_door_open_close",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 100,
        skyAccess = true,
        padding = 8
    )
    public static void realDoorOpenClose(
            final GameTestHelper helper
    ) {
        RedstoneGameTests.doorOpenClose(helper);
    }

    @GameTest(
        name = "real_ender_chest_transaction",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 100,
        skyAccess = true,
        padding = 8
    )
    public static void realEnderChestTransaction(
            final GameTestHelper helper
    ) {
        MenuGameTests.enderChestTransaction(helper);
    }

    @GameTest(
        name = "real_brewing_stand_batch",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 1_200,
        skyAccess = true,
        padding = 8
    )
    public static void realBrewingStandBatch(
            final GameTestHelper helper
    ) {
        MenuGameTests.naturalBrewingStandBatch(helper);
    }

    @GameTest(
        name = "real_food_animal_hunt",
        environment = "exclusive_real_food_animal_hunt",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 4_000,
        skyAccess = true,
        padding = 8
    )
    public static void realFoodAnimalHunt(
            final GameTestHelper helper
    ) {
        LootGameTests.realFoodAnimalHunt(helper);
    }

    @GameTest(
        name = "real_prepare_and_plant_plot",
        environment = "exclusive_real_prepare_and_plant_plot",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 4_000,
        skyAccess = true,
        padding = 8
    )
    public static void realPrepareAndPlantPlot(
            final GameTestHelper helper
    ) {
        FarmingGameTests.realPrepareAndPlantPlot(helper);
    }

    @GameTest(
        name = "real_prepare_water_source",
        environment = "exclusive_real_prepare_water_source",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 4_000,
        skyAccess = true,
        padding = 8
    )
    public static void realPrepareWaterSource(
            final GameTestHelper helper
    ) {
        FarmingGameTests.realPrepareWaterSource(helper);
    }

    @GameTest(
        name = "real_plant_observed_sugarcane",
        environment = "exclusive_real_plant_observed_sugarcane",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 4_000,
        skyAccess = true,
        padding = 8
    )
    public static void realPlantObservedSugarcane(
            final GameTestHelper helper
    ) {
        FarmingGameTests.realPlantObservedSugarcane(helper);
    }

    @GameTest(
        name = "real_build_hydrated_crop_field",
        environment = "exclusive_real_build_hydrated_crop_field",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 12_000,
        skyAccess = true,
        padding = 8
    )
    public static void realBuildHydratedCropField(
            final GameTestHelper helper
    ) {
        FarmingGameTests.realBuildHydratedCropField(helper);
    }

    @GameTest(
        name = "real_maintain_observed_crop_field",
        environment = "exclusive_real_maintain_observed_crop_field",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 12_000,
        skyAccess = true,
        padding = 8
    )
    public static void realMaintainObservedCropField(
            final GameTestHelper helper
    ) {
        FarmingGameTests.realMaintainObservedCropField(helper);
    }

    @GameTest(
        name = "real_maintain_observed_carrot_field",
        environment = "exclusive_real_maintain_observed_crop_field",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 12_000,
        skyAccess = true,
        padding = 8
    )
    public static void realMaintainObservedCarrotField(
            final GameTestHelper helper
    ) {
        FarmingGameTests.realMaintainObservedCarrotField(helper);
    }

    @GameTest(
        name = "real_maintain_observed_potato_field",
        environment = "exclusive_real_maintain_observed_crop_field",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 12_000,
        skyAccess = true,
        padding = 8
    )
    public static void realMaintainObservedPotatoField(
            final GameTestHelper helper
    ) {
        FarmingGameTests.realMaintainObservedPotatoField(helper);
    }

    @GameTest(
        name = "real_maintain_observed_beetroot_field",
        environment = "exclusive_real_maintain_observed_crop_field",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 12_000,
        skyAccess = true,
        padding = 8
    )
    public static void realMaintainObservedBeetrootField(
            final GameTestHelper helper
    ) {
        FarmingGameTests.realMaintainObservedBeetrootField(helper);
    }

    @GameTest(
        name = "real_maintain_observed_expanded_field",
        environment = "exclusive_real_maintain_observed_crop_field",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 24_000,
        skyAccess = true,
        padding = 8
    )
    public static void realMaintainObservedExpandedField(
            final GameTestHelper helper
    ) {
        FarmingGameTests.realMaintainObservedExpandedField(helper);
    }

    @GameTest(
        name = "auto_presence_on_human_login",
        environment = "exclusive_auto_presence",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 800,
        skyAccess = true,
        padding = 8
    )
    public static void autoPresenceOnHumanLogin(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests.autoPresenceOnHumanLogin(helper);
    }

    @GameTest(
        name = "delayed_human_login_after_zero_human_active",
        environment = "exclusive_auto_presence",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 1_600,
        skyAccess = true,
        padding = 16
    )
    public static void delayedHumanLoginAfterZeroHumanActive(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests.delayedHumanLoginAfterZeroHumanActive(helper);
    }

    @GameTest(
        name = "delayed_human_login_while_emergency_active",
        environment = "exclusive_auto_presence",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 1_600,
        skyAccess = true,
        padding = 16
    )
    public static void delayedHumanLoginWhileEmergencyActive(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests.delayedHumanLoginWhileEmergencyActive(helper);
    }

    @GameTest(
        name = "real_player_chat_to_live_model",
        environment = "exclusive_live_model_chat",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 8
    )
    public static void realPlayerChatToLiveModel(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests.realPlayerChatToLiveModel(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_movement",
        environment = "exclusive_live_model_task",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 8
    )
    public static void realPlayerTaskToLiveModelMovement(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelMovement(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_movement_stop_resume",
        environment = "exclusive_live_model_movement_stop_resume",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 900_000,
        skyAccess = true,
        padding = 8
    )
    public static void realPlayerTaskToLiveModelMovementStopResume(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelMovementStopResume(helper);
    }

    @GameTest(
        name = "real_player_chat_to_immediate_bound_follow",
        environment = "exclusive_immediate_bound_follow",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 5_000,
        skyAccess = true,
        padding = 16
    )
    public static void realPlayerChatToImmediateBoundFollow(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerChatToImmediateBoundFollow(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_follow",
        environment = "exclusive_live_model_follow",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 16
    )
    public static void realPlayerTaskToLiveModelFollow(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelFollow(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_follow_stop_resume",
        environment = "exclusive_live_model_follow_stop_resume",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 900_000,
        skyAccess = true,
        padding = 16
    )
    public static void realPlayerTaskToLiveModelFollowStopResume(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelFollowStopResume(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_item_collection",
        environment = "exclusive_live_model_item_collection",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerTaskToLiveModelItemCollection(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelItemCollection(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_player_gift_round_trip",
        environment = "exclusive_live_model_item_gift_round_trip",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerTaskToLiveModelPlayerGiftRoundTrip(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelPlayerGiftRoundTrip(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_container_withdrawal",
        environment = "exclusive_live_model_container_withdrawal",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerTaskToLiveModelContainerWithdrawal(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelContainerWithdrawal(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_zombie_defense",
        environment = "exclusive_live_model_combat",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerTaskToLiveModelZombieDefense(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelZombieDefense(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_zombie_defense_stop_resume",
        environment = "exclusive_live_model_combat",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerTaskToLiveModelZombieDefenseStopResume(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelZombieDefenseStopResume(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_horde_defense",
        environment = "exclusive_live_model_horde_combat",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 16
    )
    public static void realPlayerTaskToLiveModelHordeDefense(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelHordeDefense(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_ten_plus_ten_horde",
        environment = "exclusive_live_model_ten_plus_ten_horde",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 900_000,
        skyAccess = true,
        padding = 20
    )
    public static void realPlayerTaskToLiveModelTenPlusTenHorde(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelTenPlusTenHorde(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_iron_golem_duel",
        environment = "exclusive_live_model_iron_golem_duel",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 16
    )
    public static void realPlayerTaskToLiveModelIronGolemDuel(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelIronGolemDuel(helper);
    }

    @GameTest(
        name = "real_player_chat_to_surprise_zombie_defense",
        environment = "exclusive_live_model_surprise_combat",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerChatToSurpriseZombieDefense(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerChatToSurpriseZombieDefense(helper);
    }

    @GameTest(
        name = "real_player_chat_to_critical_golden_apple",
        environment = "exclusive_live_model_golden_apple",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerChatToCriticalGoldenApple(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerChatToCriticalGoldenApple(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_parkour",
        environment = "exclusive_live_model_parkour",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerTaskToLiveModelParkour(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelParkour(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_water_clutch",
        environment = "exclusive_live_model_water_clutch",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerTaskToLiveModelWaterClutch(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelWaterClutch(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_farm_work",
        environment = "exclusive_live_model_farm_work",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerTaskToLiveModelFarmWork(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelFarmWork(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_foundation_bootstrap",
        environment = "exclusive_live_model_foundation_bootstrap",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerTaskToLiveModelFoundationBootstrap(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelFoundationBootstrap(helper);
    }

    @GameTest(
        name = "real_player_chat_to_live_model_wood_and_stone_tools",
        environment = "exclusive_live_model_foundation_bootstrap",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 240_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerChatToLiveModelWoodAndStoneTools(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerChatToLiveModelWoodAndStoneTools(helper);
    }

    @GameTest(
        name = "real_player_chat_to_live_model_iron_acquisition",
        environment = "exclusive_live_model_foundation_bootstrap",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerChatToLiveModelIronAcquisition(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerChatToLiveModelIronAcquisition(helper);
    }

    @GameTest(
        name = "real_player_chat_to_live_model_distributed_log_storage",
        environment = "exclusive_live_model_foundation_bootstrap",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerChatToLiveModelDistributedLogStorage(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerChatToLiveModelDistributedLogStorage(helper);
    }

    @GameTest(
        name =
            "real_player_chat_to_live_model_distributed_storage_and_door",
        environment = "exclusive_live_model_foundation_bootstrap",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 900_000,
        skyAccess = true,
        padding = 12
    )
    public static void
            realPlayerChatToLiveModelDistributedStorageAndDoor(
                    final GameTestHelper helper
            ) {
        LiveModelChatGameTests
                .realPlayerChatToLiveModelDistributedStorageAndDoor(
                        helper
                );
    }

    @GameTest(
        name =
            "real_player_task_to_live_model_nether_portal_build_and_entry",
        environment =
            "exclusive_live_model_nether_portal_build_and_entry",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void
            realPlayerTaskToLiveModelNetherPortalBuildAndEntry(
                    final GameTestHelper helper
            ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelNetherPortalBuildAndEntry(
                        helper
                );
    }

    @GameTest(
        name = "real_player_task_to_live_model_nether_blaze_material",
        environment = "exclusive_live_model_nether_blaze_material",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void
            realPlayerTaskToLiveModelNetherBlazeMaterial(
                    final GameTestHelper helper
            ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelNetherBlazeMaterial(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_ender_pearl_reserve",
        environment = "exclusive_live_model_ender_pearl_reserve",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void
            realPlayerTaskToLiveModelEnderPearlReserve(
                    final GameTestHelper helper
            ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelEnderPearlReserve(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_end_portal_activation",
        environment = "exclusive_live_model_end_portal_activation",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerTaskToLiveModelEndPortalActivation(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelEndPortalActivation(helper);
    }

    @GameTest(
        name =
            "real_player_task_to_live_model_end_portal_activation_and_entry",
        environment =
            "exclusive_live_model_end_portal_activation_and_entry",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void
            realPlayerTaskToLiveModelEndPortalActivationAndEntry(
                    final GameTestHelper helper
            ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelEndPortalActivationAndEntry(
                        helper
                );
    }

    @GameTest(
        name =
            "real_player_task_to_live_model_eye_craft_return_and_stronghold",
        environment =
            "exclusive_live_model_eye_craft_return_and_stronghold",
        structure = "forge:empty600x24x600",
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void
            realPlayerTaskToLiveModelEyeCraftReturnAndStronghold(
                    final GameTestHelper helper
            ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelEyeCraftReturnAndStronghold(
                        helper
                );
    }

    @GameTest(
        name =
            "real_player_task_to_live_model_nether_materials_to_victory",
        environment =
            "exclusive_live_model_nether_materials_to_victory",
        structure = "forge:empty600x24x600",
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void
            realPlayerTaskToLiveModelNetherMaterialsToVictory(
                    final GameTestHelper helper
            ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelNetherMaterialsToVictory(
                        helper
                );
    }

    @GameTest(
        name =
            "real_player_task_to_live_model_stronghold_portal_room_and_entry",
        environment =
            "exclusive_live_model_stronghold_portal_room_and_entry",
        structure = "forge:empty96x24x96",
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void
            realPlayerTaskToLiveModelStrongholdPortalRoomAndEntry(
                    final GameTestHelper helper
            ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelStrongholdPortalRoomAndEntry(
                        helper
                );
    }

    @GameTest(
        name =
            "real_player_task_to_live_model_stronghold_portal_room_to_victory",
        environment =
            "exclusive_live_model_stronghold_portal_room_to_victory",
        structure = "forge:empty96x24x96",
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void
            realPlayerTaskToLiveModelStrongholdPortalRoomToVictory(
                    final GameTestHelper helper
            ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelStrongholdPortalRoomToVictory(
                        helper
                );
    }

    @GameTest(
        name = "real_player_task_to_live_model_late_end_completion_chain",
        environment = "exclusive_live_model_late_end_completion_chain",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void
            realPlayerTaskToLiveModelLateEndCompletionChain(
                    final GameTestHelper helper
            ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelLateEndCompletionChain(
                        helper
                );
    }

    @GameTest(
        name = "real_player_task_to_live_model_end_victory_and_return",
        environment = "exclusive_live_model_end_victory",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realPlayerTaskToLiveModelEndVictoryAndReturn(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelEndVictoryAndReturn(helper);
    }

    @GameTest(
        name = "real_player_task_to_live_model_shelter_relocation",
        environment = "exclusive_live_model_shelter_relocation",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 16
    )
    public static void realPlayerTaskToLiveModelShelterRelocation(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .realPlayerTaskToLiveModelShelterRelocation(helper);
    }

    @GameTest(
        name = "real_zero_human_dedicated_server_foundation",
        environment = "exclusive_zero_human_live_foundation",
        structure = FOCUSED_TEST_STRUCTURE,
        maxTicks = 600_000,
        skyAccess = true,
        padding = 12
    )
    public static void realZeroHumanDedicatedServerFoundation(
            final GameTestHelper helper
    ) {
        LiveModelChatGameTests
                .zeroHumanDedicatedServerToLiveModelFoundation(helper);
    }

    private static VerifiedFixtureLocation location(
            final String dimension,
            final BlockPos position
    ) {
        return new VerifiedFixtureLocation(
                dimension,
                position.getX(),
                position.getY(),
                position.getZ()
        );
    }

    private static void runFocusedMovementScenario(
            final GameTestHelper helper,
            final ScenarioScope scope,
            final int maximumTicks
    ) {
        MinecraftAiCompanion.LOGGER.info(
            "Starting focused real {} GameTest",
            scope.logName()
        );
        final var server = helper.getLevel().getServer();
        final AtomicReference<IntegratedSkillScenario> scenario =
            new AtomicReference<>();
        helper.addCleanup(ignored -> {
            final IntegratedSkillScenario current = scenario.get();
            if (current != null) {
                current.cleanup();
            }
            CompanionRuntime.active()
                .filter(runtime -> runtime.server() == server)
                .ifPresent(runtime -> {
                    final GoalStatus goalStatus =
                        runtime.goals().snapshot().status();
                    if (goalStatus == GoalStatus.RUNNING
                            || goalStatus
                                == GoalStatus.CANCEL_PENDING) {
                        runtime.goals().markTerminal(
                            GoalStatus.SAFE_IDLE,
                            "focused_test_cleanup"
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
        });

        final var spawn = GameTestCompanionSpawn.request(
            helper,
            FOCUSED_TEST_ORIGIN
        );
        helper.assertTrue(
            spawn.accepted(),
            "Focused movement body spawn was rejected: " + spawn.code()
        );
        scheduleEveryTick(helper, maximumTicks, () -> {
            final IntegratedSkillScenario current = scenario.get();
            if (current != null) {
                current.tick();
                return;
            }

            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                status.state() != SessionState.FAILED,
                "Focused movement body failed to spawn: " + status
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                    helper.getTick() <= FOCUSED_BODY_START_TIMEOUT_TICKS,
                    "Focused movement body did not become active"
                );
                return;
            }

            final var runtime = CompanionRuntime.active()
                .filter(candidate -> candidate.server() == server)
                .orElseThrow(() -> helper.assertionException(
                    "Focused movement test has no production runtime"
                ));
            runtime.survival().reset();
            runtime.coreActions().quiesceNow();
            runtime.interactionActions().quiesceNow();
            runtime.boatActions().quiesceNow();
            runtime.minecartActions().quiesceNow();
            runtime.skillSupervisor().abandonForSessionEnd();
            /*
             * The integrated fixture drives production skills directly and
             * deliberately performs no provider I/O. A RUNNING goal with an
             * unconfigured gateway correctly makes CompanionRuntime quiesce
             * every persistent actuator at ServerTickEvent.END. That safety
             * path would clear a boat input after the GameTest callback but
             * before the next vanilla entity tick, making this test measure
             * callback order rather than boat physics. Install an inert,
             * ready delegate in this release-excluded GameTest class so the
             * runtime follows the same gateway-ready path as live play.
             * Stop the brain first so the manually supervised fixture remains
             * the only caller of SkillSupervisor.tick; otherwise both the
             * server runtime and this fixture would advance every skill once
             * per tick. The holding delegate never emits a decision.
             */
            runtime.brain().close();
            runtime.model().gateway().install(
                new HoldingGameTestGateway()
            );
            final var goalStart = runtime.goals().setGoal(
                    "Run the focused " + scope.logName()
                        + " verification",
                    GoalSource.RECOVERY
            );
            helper.assertTrue(
                    goalStart.accepted(),
                    "Focused movement test could not install its "
                        + "production goal: " + goalStart.code()
            );

            final var player = AiPlayerManager.onlinePlayer(server)
                .orElseThrow();
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

            scenario.set(new IntegratedSkillScenario(
                helper,
                runtime,
                helper.absolutePos(FOCUSED_TEST_ORIGIN),
                scope
            ));
        });
    }

    @GameTest(
        name = "headless_player_lifecycle_state_and_fair_action",
        environment = "exclusive_headless_lifecycle",
        structure = TEST_STRUCTURE,
        maxTicks = (int) TEST_MAX_TICKS
    )
    public static void headlessPlayerLoginReloginAndState(final GameTestHelper helper) {
        MinecraftAiCompanion.LOGGER.info(
            "Starting real headless_player_lifecycle_state_and_fair_action GameTest"
        );
        InventoryGameTests.vanillaInventoryTransactions(helper);
        MenuGameTests.vanillaMenuTransactions(helper);
        final var server = helper.getLevel().getServer();
        final var expectedIdentity = CompanionWorldData.get(server).companionUuid();
        final AtomicReference<FairPlayerActuator> actuator =
            new AtomicReference<>();
        final AtomicReference<Vec3> actionStart = new AtomicReference<>();
        final AtomicReference<Vec3> actionStopped = new AtomicReference<>();
        final AtomicReference<BlockPos> miningBlock = new AtomicReference<>();
        final AtomicReference<Vec3> leasedActionStart =
            new AtomicReference<>();
        final AtomicReference<Long> leasedActionTick =
            new AtomicReference<>();
        final AtomicReference<IntegratedSkillScenario> integratedScenario =
            new AtomicReference<>();
        final AtomicReference<LifecycleGateStage> lifecycleGate =
            new AtomicReference<>(
                LifecycleGateStage.WAITING_FIRST_LOGIN
            );
        helper.addCleanup(ignored -> {
            final IntegratedSkillScenario scenario =
                integratedScenario.get();
            if (scenario != null) {
                scenario.cleanup();
            }
            CompanionRuntime.active()
                .filter(runtime -> runtime.server() == server)
                .ifPresent(runtime -> {
                    final GoalStatus goalStatus =
                        runtime.goals().snapshot().status();
                    if (goalStatus == GoalStatus.RUNNING
                            || goalStatus
                                == GoalStatus.CANCEL_PENDING) {
                        runtime.goals().markTerminal(
                            GoalStatus.SAFE_IDLE,
                            "integrated_test_cleanup"
                        );
                    }
                    runtime.survival().reset();
                    runtime.coreActions().quiesceNow();
                    runtime.interactionActions().quiesceNow();
                    runtime.boatActions().quiesceNow();
                    runtime.minecartActions().quiesceNow();
                    runtime.skillSupervisor().abandonForSessionEnd();
                });
            if (AiPlayerManager.status(server).state() != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(server);
            }
        });
        final var spawn = GameTestCompanionSpawn.request(
            helper,
            TEST_ORIGIN
        );
        helper.assertTrue(spawn.accepted(), "Headless spawn request was rejected: " + spawn.code());

        helper.runAtTickTime(220, () -> {
            final var runtime = CompanionRuntime.active()
                .orElseThrow(() -> helper.assertionException(
                    "ServerStartedEvent did not create the companion runtime"
                ));
            helper.assertTrue(
                runtime.server() == server,
                "Active companion runtime belongs to a different server"
            );
            helper.assertTrue(
                runtime.skills().contains(CoreSkills.MOVE_TO),
                "Production runtime did not register move_to"
            );
            helper.assertTrue(
                runtime.skills().contains(CoreSkills.LOOK_AT),
                "Production runtime did not register look_at"
            );
            helper.assertTrue(
                runtime.skills().contains(CoreSkills.SAFE_IDLE),
                "Production runtime did not register safe_idle"
            );
            helper.assertTrue(
                runtime.skills().contains(CoreSkills.FOLLOW_ENTITY),
                "Production runtime did not register follow_entity"
            );
            helper.assertTrue(
                runtime.skills().contains(TravelSkills.TRAVEL_TO),
                "Production runtime did not register travel_to"
            );
            helper.assertTrue(
                runtime.skills().contains(
                    ExplorationSkills.EXPLORE_FOR_OBSERVED_TARGET
                ),
                "Production runtime did not register fair target exploration"
            );
            helper.assertTrue(
                runtime.skills().contains(FairInteractionSkills.BREAK_BLOCK)
                    && runtime.skills().contains(
                        FairInteractionSkills.ATTACK_ENTITY
                    )
                    && runtime.skills().contains(
                        FairInteractionSkills.INTERACT_ENTITY
                    )
                    && runtime.skills().contains(
                        FairInteractionSkills.USE_BLOCK
                    ),
                "Production runtime did not register fair interaction skills"
            );
            helper.assertTrue(
                runtime.skills().contains(
                    CombatSkills.ENGAGE_OBSERVED_ENTITY
                )
                    && runtime.skills().contains(
                        "shoot_observed_entity"
                    )
                    && runtime.skills().contains(
                        CombatSkills.FIGHT_ENDER_DRAGON
                    ),
                "Production runtime did not register local combat skills"
            );
            helper.assertTrue(
                runtime.skills().contains(InventorySkills.CRAFT_RECIPE)
                    && runtime.skills().contains(
                        InventorySkills.EQUIP_ITEM
                    )
                    && runtime.skills().contains(
                        InventorySkills.DROP_ITEM
                    ),
                "Production runtime did not register inventory skills"
            );
            helper.assertTrue(
                runtime.skills().contains(
                    LootSkills.COLLECT_OBSERVED_ITEM
                )
                    && runtime.skills().contains(
                        LootSkills
                            .ENGAGE_AND_COLLECT_OBSERVED_DROP
                    ),
                "Production runtime did not register fair drop collection"
            );
            helper.assertTrue(
                runtime.skills().contains(MenuSkills.TRANSFER_MENU_ITEM)
                    && runtime.skills().contains(
                        MenuSkills.TAKE_MENU_OUTPUT
                    )
                    && runtime.skills().contains(
                        MenuSkills.SELECT_MENU_OPTION
                    )
                    && runtime.skills().contains(MenuSkills.CLOSE_MENU),
                "Production runtime did not register menu skills"
            );
            helper.assertTrue(
                runtime.skills().contains(
                    DynamicShelterSkills.BUILD_SHELTER_STEP
                ),
                "Production runtime did not register dynamic shelter building"
            );
            helper.assertTrue(
                runtime.skills().contains(BridgeSkills.BRIDGE_TO)
                    && runtime.skills().contains(
                        BridgeSkills.TOWER_UP
                    )
                    && runtime.skills().contains(
                        BridgeSkills.WATER_CLUTCH_DESCEND
                    ),
                "Production runtime did not register fair building movement"
            );
            helper.assertTrue(
                runtime.skills().contains(ParkourSkills.PARKOUR_TO),
                "Production runtime did not register fair parkour movement"
            );
            helper.assertTrue(
                runtime.skills().contains(
                    FarmingSkills.HARVEST_AND_REPLANT_STEP
                ) && runtime.skills().contains(
                    FarmingSkills.PREPARE_AND_PLANT_PLOT
                ) && runtime.skills().contains(
                    FarmingSkills.PREPARE_WATER_SOURCE
                ),
                "Production runtime did not register fair crop work"
            );
            helper.assertTrue(
                runtime.skills().contains(
                    ResourceGatheringSkills.GATHER_VISIBLE_BLOCK_CLUSTER
                ),
                "Production runtime did not register fair resource gathering"
            );
            helper.assertTrue(
                runtime.skills().contains(
                    SurveySkills.SURVEY_SURROUNDINGS
                ),
                "Production runtime did not register fair environment survey"
            );
            helper.assertTrue(
                runtime.skills().contains(
                    StrongholdSkills.TRACE_STRONGHOLD_EYE
                )
                    && runtime.skills().contains(
                        StrongholdSkills
                            .TRIANGULATE_STRONGHOLD_SEARCH_AREA
                    )
                    && runtime.skills().contains(
                        StrongholdSkills.REACH_OBSERVED_STRONGHOLD
                    )
                    && runtime.skills().contains(
                        StrongholdSkills
                            .SEARCH_OBSERVED_STRONGHOLD_PORTAL_ROOM
                    ),
                "Production runtime did not register fair Eye tracing "
                    + "triangulation, physical reach, and portal-room search"
            );
            helper.assertTrue(
                runtime.skills().contains(
                    PortalSkills.ENTER_OBSERVED_PORTAL
                )
                    && runtime.skills().contains(
                        PortalSkills.RETURN_VIA_VERIFIED_PORTAL
                    ),
                "Production runtime did not register fair portal "
                    + "traversal and durable return"
            );
            helper.assertTrue(
                runtime.skills().contains(
                    PortalBuildSkills.BUILD_AND_LIGHT_NETHER_PORTAL
                )
                    && runtime.skills().contains(
                        PortalBuildSkills.ACTIVATE_OBSERVED_END_PORTAL
                    ),
                "Production runtime did not register fair portal construction"
            );
            helper.assertTrue(
                runtime.skills().contains(
                    BoatTransportSkills.ENTER_OBSERVED_BOAT
                )
                    && runtime.skills().contains(
                        BoatTransportSkills.BOAT_TRAVEL_TO
                    ),
                "Production runtime did not register fair boat transport"
            );
            helper.assertTrue(
                runtime.skills().contains(
                    MinecartTransportSkills.ENTER_OBSERVED_MINECART
                )
                    && runtime.skills().contains(
                        MinecartTransportSkills.MINECART_TRAVEL_TO
                    ),
                "Production runtime did not register fair minecart transport"
            );
            helper.assertTrue(
                runtime.skills().contains(
                    SleepSkills.SLEEP_IN_OBSERVED_BED
                ),
                "Production runtime did not register fair bed sleeping"
            );
            helper.assertTrue(
                runtime.skills().contains(ProgressSkills.RECORD_PROGRESS),
                "Production runtime did not register progress journaling"
            );
            helper.assertTrue(
                runtime.skills().contains(MemorySkills.RECALL_WAYPOINT)
                    && runtime.skills().contains(
                        MemorySkills.REMEMBER_WAYPOINT
                    ),
                "Production runtime did not register waypoint memory skills"
            );
            helper.assertTrue(
                runtime.tickMetrics().snapshot().lifetimeSamples() > 0,
                "Production runtime did not record tick-cost telemetry"
            );

            final var status = AiPlayerManager.status(server);
            helper.assertTrue(
                lifecycleGate.get()
                    == LifecycleGateStage.RESTORED,
                "Companion login/relogin gate did not complete: "
                    + lifecycleGate.get()
                    + ", status="
                    + status
            );
            helper.assertTrue(status.state() == SessionState.ACTIVE, "Companion session did not become active");
            helper.assertTrue(status.online(), "Companion was not online after relogin");

            final var player = AiPlayerManager.onlinePlayer(server)
                .orElseThrow(() -> helper.assertionException("Companion ServerPlayer was missing"));
            helper.assertTrue(
                expectedIdentity.equals(player.getUUID()),
                "Companion did not use the persistent world UUID"
            );
            helper.assertTrue(player.connection != null, "Vanilla play listener was missing");
            helper.assertTrue(
                server.getPlayerList().getPlayer(expectedIdentity) == player,
                "PlayerList did not own the authoritative companion body"
            );
            helper.assertTrue(
                player.getInventory().getItem(0).is(Items.IRON_PICKAXE),
                "Inventory was not restored through vanilla player data"
            );
            helper.assertTrue(
                player.getEnderChestInventory().getItem(0).is(Items.EMERALD)
                    && player.getEnderChestInventory().getItem(0).getCount() == 17,
                "Ender chest was not restored through vanilla player data"
            );
            helper.assertTrue(player.getHealth() == 13.0F, "Health was not restored");
            helper.assertTrue(player.getFoodData().getFoodLevel() == 11, "Food level was not restored");
            helper.assertTrue(player.experienceLevel == 6, "Experience level was not restored");
            player.setGameMode(GameType.SURVIVAL);
            helper.assertTrue(
                player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                    && !player.getAbilities().instabuild,
                "Fair-action fixture could not enter survival mode"
            );

            final BlockPos origin = helper.absolutePos(TEST_ORIGIN);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 18; z++) {
                    helper.getLevel().setBlockAndUpdate(
                        origin.offset(x, -1, z),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                }
            }
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            player.setYRot(0.0F);
            player.setYHeadRot(0.0F);
            player.setXRot(0.0F);

            leasedActionStart.set(player.position());
            leasedActionTick.set(Integer.toUnsignedLong(server.getTickCount()));
            helper.assertTrue(
                runtime.coreActions().move(
                    new MovementIntent(1.0, 0.0, false, false)
                ) == ActionOutcome.QUEUED,
                "Production core action lease rejected movement"
            );

            final FairPlayerActuator controller =
                new FairPlayerActuator(player);
            helper.assertTrue(
                controller.setMovement(
                    new MovementIntent(1.0, 0.0, false, false)
                ) == ActionOutcome.QUEUED,
                "Forward movement was not accepted"
            );
            actuator.set(controller);
            actionStart.set(player.position());
        });

        helper.runAtTickTime(224, () -> {
            final var runtime = CompanionRuntime.active()
                .orElseThrow(() -> helper.assertionException(
                    "Companion runtime disappeared during the test"
                ));
            final var player = AiPlayerManager.onlinePlayer(server)
                .orElseThrow();
            final Vec3 start = leasedActionStart.get();
            final Long issuedAt = leasedActionTick.get();
            final var lease = runtime.coreActions().snapshot();
            helper.assertTrue(
                start != null && issuedAt != null,
                "Production lease fixture did not run"
            );
            helper.assertTrue(
                lease.bound() && lease.lastExecutedTick() >= issuedAt,
                "Runtime did not close the production core-action tick loop"
            );
            helper.assertTrue(
                player.position().distanceToSqr(start) < 0.01,
                "Idle runtime left a movement intent latched across ticks"
            );
        });

        scheduleEveryTick(helper, TEST_MAX_TICKS, () -> {
            final long tick = helper.getTick();
            if (tick <= 200) {
                switch (lifecycleGate.get()) {
                    case WAITING_FIRST_LOGIN -> {
                        final var status =
                            AiPlayerManager.status(server);
                        if (status.state()
                                == SessionState.FAILED) {
                            throw helper.assertionException(
                                "Initial companion spawn failed: "
                                    + status
                            );
                        }
                        final var online =
                            AiPlayerManager.onlinePlayer(server);
                        if (online.isPresent()) {
                            final var player =
                                online.orElseThrow();
                            player.getInventory().setItem(
                                0,
                                new ItemStack(
                                    Items.IRON_PICKAXE
                                )
                            );
                            player.getEnderChestInventory()
                                .setItem(
                                    0,
                                    new ItemStack(
                                        Items.EMERALD,
                                        17
                                    )
                                );
                            player.setHealth(13.0F);
                            player.getFoodData()
                                .setFoodLevel(11);
                            player.experienceLevel = 6;
                            final var removed =
                                AiPlayerManager.requestRemove(
                                    server
                                );
                            helper.assertTrue(
                                removed.accepted(),
                                "Initial companion removal "
                                    + "was rejected"
                            );
                            lifecycleGate.set(
                                LifecycleGateStage
                                    .WAITING_RELOGIN
                            );
                        }
                    }
                    case WAITING_RELOGIN -> {
                        final var status =
                            AiPlayerManager.status(server);
                        if (status.state()
                                == SessionState.ABSENT) {
                            final var respawn =
                                GameTestCompanionSpawn.request(
                                    helper,
                                    TEST_ORIGIN
                                );
                            helper.assertTrue(
                                respawn.accepted(),
                                "Companion relogin was "
                                    + "rejected: "
                                    + respawn.code()
                            );
                            lifecycleGate.set(
                                LifecycleGateStage
                                    .WAITING_RESTORE
                            );
                        }
                    }
                    case WAITING_RESTORE -> {
                        final var status =
                            AiPlayerManager.status(server);
                        if (status.state()
                                == SessionState.FAILED) {
                            throw helper.assertionException(
                                "Companion relogin failed: "
                                    + status
                            );
                        }
                        if (status.state()
                                == SessionState.ACTIVE
                                && status.online()) {
                            lifecycleGate.set(
                                LifecycleGateStage.RESTORED
                            );
                        }
                    }
                    case RESTORED -> {
                    }
                }
            }
            final FairPlayerActuator controller = actuator.get();
            if (controller != null && tick >= 225 && tick < 250) {
                final ActionOutcome outcome = controller.tick();
                helper.assertTrue(
                    outcome == ActionOutcome.DISPATCHED,
                    "Movement tick failed: " + outcome
                );
            }
            if (controller != null && tick >= 271 && tick < 310) {
                final ActionOutcome outcome = controller.tick();
                helper.assertTrue(
                    outcome == ActionOutcome.DISPATCHED
                        || outcome == ActionOutcome.IN_PROGRESS
                        || outcome == ActionOutcome.COMPLETED,
                    "Mining tick failed: " + outcome
                );
            }
            final IntegratedSkillScenario scenario =
                integratedScenario.get();
            if (scenario != null && tick > 310) {
                scenario.tick();
            }
        });

        helper.runAtTickTime(250, () -> {
            final FairPlayerActuator controller = actuator.get();
            helper.assertTrue(controller != null, "Actuator was not created");
            helper.assertTrue(
                controller.stop() == ActionOutcome.DISPATCHED,
                "Stop input was not dispatched"
            );
            actionStopped.set(
                AiPlayerManager.onlinePlayer(server)
                    .orElseThrow()
                    .position()
            );
        });

        helper.runAtTickTime(260, () -> {
            final var player = AiPlayerManager.onlinePlayer(server)
                .orElseThrow(() -> helper.assertionException(
                    "Companion disappeared during movement"
                ));
            final Vec3 initial = actionStart.get();
            final Vec3 stopped = actionStopped.get();
            helper.assertTrue(
                initial != null && stopped != null,
                "Action test state was incomplete"
            );
            final double travelled = stopped.z() - initial.z();
            helper.assertTrue(
                travelled > 0.5 && travelled < 15.0,
                "Forward travel was outside a plausible vanilla range: "
                    + travelled
            );
            helper.assertTrue(
                Math.abs(stopped.x() - initial.x()) < 0.5,
                "Forward input drifted sideways"
            );
            helper.assertTrue(
                player.position().distanceToSqr(stopped) < 0.25,
                "Released movement input continued driving the player"
            );

            final BlockPos origin = helper.absolutePos(TEST_ORIGIN);
            final BlockPos target = origin.offset(0, 1, 2);
            helper.getLevel().setBlockAndUpdate(
                target,
                Blocks.STONE.defaultBlockState()
            );
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            player.setItemInHand(
                InteractionHand.MAIN_HAND,
                new ItemStack(Items.DIAMOND_PICKAXE)
            );
            player.lookAt(
                EntityAnchorArgument.Anchor.EYES,
                Vec3.atCenterOf(target)
            );
            miningBlock.set(target);
        });

        helper.runAtTickTime(270, () -> {
            final var player = AiPlayerManager.onlinePlayer(server)
                .orElseThrow();
            final Vec3 eye = player.getEyePosition();
            final BlockHitResult hit = helper.getLevel().clip(new ClipContext(
                eye,
                eye.add(
                    player.getViewVector(1.0F)
                        .scale(player.blockInteractionRange())
                ),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
            ));
            helper.assertTrue(
                hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(miningBlock.get()),
                "Fixture did not put the stone under the companion crosshair"
            );
            final Vec3 location = hit.getLocation();
            final BlockPos target = hit.getBlockPos();
            final BlockInteractionTarget actionTarget =
                new BlockInteractionTarget(
                    target.getX(),
                    target.getY(),
                    target.getZ(),
                    BlockFace.valueOf(hit.getDirection().name()),
                    new ActionVec3(
                        location.x(),
                        location.y(),
                        location.z()
                    )
                );
            final ActionOutcome miningStart =
                actuator.get().beginMining(actionTarget);
            helper.assertTrue(
                miningStart == ActionOutcome.IN_PROGRESS
                    || miningStart == ActionOutcome.COMPLETED,
                "Vanilla START_DESTROY_BLOCK was not accepted: "
                    + miningStart
            );
        });

        helper.runAtTickTime(310, () -> {
            final var player = AiPlayerManager.onlinePlayer(server)
                .orElseThrow();
            helper.assertTrue(
                helper.getLevel().getBlockState(miningBlock.get()).isAir(),
                "Fair mining did not remove the observed stone"
            );
            helper.assertTrue(
                player.getMainHandItem().is(Items.DIAMOND_PICKAXE)
                    && player.getMainHandItem().getDamageValue() == 1,
                "Vanilla mining did not apply normal tool durability"
            );
            final var runtime = CompanionRuntime.active()
                .orElseThrow(() -> helper.assertionException(
                    "Companion runtime disappeared before integrated skill tests"
                ));
            runtime.survival().reset();
            runtime.coreActions().quiesceNow();
            runtime.interactionActions().quiesceNow();
            runtime.boatActions().quiesceNow();
            runtime.minecartActions().quiesceNow();
            runtime.skillSupervisor().abandonForSessionEnd();
            runtime.brain().close();
            runtime.model().gateway().install(
                new HoldingGameTestGateway()
            );
            final var goalStart = runtime.goals().setGoal(
                "Run the full integrated embodiment verification",
                GoalSource.RECOVERY
            );
            helper.assertTrue(
                goalStart.accepted(),
                "Integrated embodiment test could not install its "
                    + "production goal: " + goalStart.code()
            );
            helper.assertTrue(
                runtime.goals().snapshot().status()
                    == GoalStatus.RUNNING,
                "Integrated embodiment goal was not running before "
                    + "the production skill chain started"
            );
            integratedScenario.set(new IntegratedSkillScenario(
                helper,
                runtime,
                helper.absolutePos(TEST_ORIGIN)
            ));
        });
    }

    private enum LifecycleGateStage {
        WAITING_FIRST_LOGIN,
        WAITING_RELOGIN,
        WAITING_RESTORE,
        RESTORED
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
            // A direct GameTest skill owns the active goal until cleanup.
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

    private enum ScenarioScope {
        FULL(false, "full embodiment chain"),
        WATER_ONLY(true, "water-clutch physics"),
        PARKOUR_ONLY(true, "parkour physics"),
        TRAVEL_DETOUR_ONLY(
                true,
                "diagonal travel detour and course recovery"
        ),
        PORTAL_RETURN_ONLY(
                true,
                "verified portal arrival return"
        ),
        BLAZE_ONLY(true, "Nether Blaze-rod acquisition"),
        BLAZE_RESERVE_ONLY(
                true,
                "Nether Blaze material reserve acquisition"
        ),
        ENDER_SINGLE_ONLY(
                true,
                "single sheltered Ender pearl acquisition"
        ),
        ENDER_RESERVE_ONLY(
                true,
                "Ender pearl reserve acquisition"
        ),
        STRONGHOLD_ONLY(
                true,
                "stronghold triangulation"
        ),
        STRONGHOLD_REACH_ONLY(
                true,
                "stronghold physical approach and excavation"
        ),
        END_PORTAL_ONLY(false, "End portal activation"),
        END_VICTORY_ONLY(
                true,
                "End entry, dragon victory, and return"
        );

        private final boolean forceHardcorePolicy;
        private final String logName;

        ScenarioScope(
                final boolean forceHardcorePolicy,
                final String logName
        ) {
            this.forceHardcorePolicy = forceHardcorePolicy;
            this.logName = logName;
        }

        private boolean forceHardcorePolicy() {
            return forceHardcorePolicy;
        }

        private String logName() {
            return logName;
        }
    }

    /**
     * Minecraft 26.2's {@link GameTestHelper#onEachTick(Runnable)} stores
     * method references as map keys; the JVM may reuse that method-reference
     * object and collapse the schedule to one entry. Capturing the distinct
     * target tick makes every callback independently observable.
     */
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

    /**
     * Sequentially exercises production-registered skills against one body.
     * Fixture setup may place blocks/entities and establish night, but every
     * asserted gameplay transition is caused by the ordinary skill actuator.
     */
    private static final class IntegratedSkillScenario {
        private static final int TARGET_DISCOVERY_TIMEOUT_TICKS = 40;
        private static final int FOCUSED_WATER_STABLE_TICKS = 3;
        private static final int FOCUSED_WATER_SETTLE_TIMEOUT_TICKS = 40;
        private static final int PARKOUR_STABLE_TICKS = 3;
        private static final int PARKOUR_SETTLE_TIMEOUT_TICKS = 40;
        private static final double CONTROLLED_ENDERMAN_OFFSET = 2.5;
        private static final double MAXIMUM_FIXTURE_MELEE_DISTANCE = 2.81;
        private static final String OVERWORLD = "minecraft:overworld";

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final BlockPos origin;
        private final int originalSleepPercentage;
        private final boolean originalSpawnMobs;
        private final boolean originalSpawnMonsters;
        private final boolean originalMobDrops;
        private final boolean originalGenerateStructures;
        private final ScenarioScope scope;
        private final RuntimeTickMetrics.Cursor performanceStart;
        private Stage stage;
        private long stageStartedAt;
        private long stageStartedNanos;
        private Boat boat;
        private Vec3 boatTravelStart;
        private Vec3 boatDestination;
        private AbstractMinecart minecart;
        private Vec3 minecartTravelStart;
        private Vec3 minecartDestination;
        private boolean minecartTravelVerified;
        private Mob rangedTarget;
        private AbstractMinecart rangedMinecart;
        private float rangedTargetInitialHealth;
        private Vec3 rangedTargetStart;
        private EndCrystal endCrystal;
        private EndCrystal fightCrystal;
        private EnderDragon fightDragon;
        private double maximumFightCrystalDistance;
        private UUID dragonFightPlayerId;
        private BlockPos fightCageBar;
        private ItemEntity lootDrop;
        private Mob occludedThreat;
        private Mob shelteredEnderman;
        private Mob resourceTarget;
        private Vec3 lootCollectionStart;
        private BlockPos endermanShelterCenter;
        private int endermanShelterScanIndex;
        private BlockPos endArena;
        private BlockPos endRouteStart;
        private Vec3 endRouteTravelStart;
        private BlockPos returnPortal;
        private BlockPos bedHead;
        private List<BlockPos> crystalCageBars = List.of();
        private int crystalCageBarIndex;
        private BlockPos crystalTowerBase;
        private BlockPos crystalLanding;
        private BlockPos crystalShotPosition;
        private BlockPos builtPortalAnchor;
        private int portalSiteScanIndex;
        private int portalBuildStableTicks;
        private BlockPos endPortalCenter;
        private BlockPos explorationTarget;
        private BlockPos netherExplorationTarget;
        private BlockPos netherPortalBlock;
        private BlockPos netherClutchLanding;
        private BlockPos netherBlazeArenaOrigin;
        private BlockPos strongholdTraceTarget;
        private BlockPos strongholdReachSearchFeet;
        private BlockPos strongholdReachEvidence;
        private BlockPos endPortalMazeDeadEnd;
        private BlockPos endPortalMazeSecondTurn;
        private Vec3 strongholdReachStart;
        private int strongholdReachPickaxeDamage;
        private int strongholdReachTorchCount;
        private Vec3 endPortalSearchStart;
        private Vec3 explorationStart;
        private Vec3 netherExplorationStart;
        private Vec3 netherPortalReturnTarget;
        private Vec3 firstEyeThrowPosition;
        private Vec3 secondEyeThrowTarget;
        private Vec3 travelDetourStart;
        private Vec3 travelDetourTarget;
        private double travelDetourMaximumX;
        private int parkourInitialJumpStat;
        private int parkourStableTicks;
        private int waterBucketUseStart;
        private int focusedWaterStableTicks;
        private double maximumWaterFallDistance;
        private boolean observedWaterDescent;
        private boolean heldSleepOpen;
        private boolean observedRealSleep;
        private boolean endPortalMazeDeadEndVisited;
        private boolean endPortalMazeSecondTurnVisited;
        private int blazeTargetsSpawned;
        private int blazeWeaponDamageBefore;
        private int enderTargetsSpawned;
        private int enderWeaponDamageBefore;
        private int enderStableTicks;
        private long enderRoofStableSince = -1L;
        private long lastEnderTargetRemovedAt = -1L;
        private boolean cleaned;

        private IntegratedSkillScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final BlockPos origin
        ) {
            this(helper, runtime, origin, ScenarioScope.FULL);
        }

        private IntegratedSkillScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final BlockPos origin,
                final ScenarioScope scope
        ) {
            this.helper = helper;
            this.runtime = runtime;
            this.origin = origin;
            this.scope = scope;
            performanceStart = runtime.tickMetrics().cursor();
            helper.assertTrue(
                runtime.goals().snapshot().status()
                    == GoalStatus.RUNNING,
                "Integrated production skill scenario requires a "
                    + "running goal"
            );
            originalSleepPercentage = helper.getLevel()
                .getGameRules()
                .get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
            originalSpawnMobs = helper.getLevel()
                .getGameRules()
                .get(GameRules.SPAWN_MOBS);
            originalSpawnMonsters = helper.getLevel()
                .getGameRules()
                .get(GameRules.SPAWN_MONSTERS);
            originalMobDrops = helper.getLevel()
                .getGameRules()
                .get(GameRules.MOB_DROPS);
            originalGenerateStructures = runtime.server()
                .getWorldGenSettings()
                .options()
                .generateStructures();
            if (scope == ScenarioScope.FULL
                    || scope == ScenarioScope.BLAZE_ONLY
                    || scope == ScenarioScope.BLAZE_RESERVE_ONLY
                    || scope == ScenarioScope.PORTAL_RETURN_ONLY
                    || scope == ScenarioScope.ENDER_SINGLE_ONLY
                    || scope == ScenarioScope.ENDER_RESERVE_ONLY
                    || scope == ScenarioScope.TRAVEL_DETOUR_ONLY
                    || scope == ScenarioScope.STRONGHOLD_ONLY
                    || scope == ScenarioScope.STRONGHOLD_REACH_ONLY
                    || scope == ScenarioScope.END_PORTAL_ONLY
                    || scope == ScenarioScope.END_VICTORY_ONLY) {
                /*
                 * Keep this release-excluded capability chain deterministic.
                 * Explicit fixture mobs still spawn through addFreshEntity;
                 * only unrelated natural spawns are suppressed. Production
                 * worlds and natural Hardcore evaluation never use this.
                 */
                setNaturalSpawning(false, false);
                setMobDrops(true);
            }
            switch (scope) {
                case FULL -> prepareBoat();
                case WATER_ONLY -> prepareFocusedWaterStart();
                case PARKOUR_ONLY -> prepareParkour();
                case TRAVEL_DETOUR_ONLY ->
                    prepareTravelDiagonalDetour();
                case PORTAL_RETURN_ONLY ->
                    prepareNetherPortalBuild();
                case BLAZE_ONLY ->
                    prepareFocusedNetherBlazeCombat();
                case BLAZE_RESERVE_ONLY ->
                    prepareFocusedNetherBlazeCombat();
                case ENDER_SINGLE_ONLY ->
                    prepareShelteredEndermanCombat();
                case ENDER_RESERVE_ONLY ->
                    prepareFocusedEnderPearlReserve();
                case STRONGHOLD_ONLY ->
                    prepareFocusedStrongholdTriangulation();
                case STRONGHOLD_REACH_ONLY ->
                    prepareFocusedStrongholdReach();
                case END_PORTAL_ONLY -> prepareEndPortalActivation();
                case END_VICTORY_ONLY ->
                    prepareFocusedEndVictory();
            }
        }

        private void tick() {
            if (scope.forceHardcorePolicy()
                    && stage != Stage.FINISHED) {
                final var livingBody = player();
                helper.assertTrue(
                        livingBody.isAlive()
                            && !livingBody.isDeadOrDying()
                            && livingBody.getHealth() > 0.0F,
                        "Forced-Hardcore physical gate body died; "
                            + "death is terminal and must not respawn"
                );
            }
            switch (stage) {
                case FINDING_BOAT -> tryStartBoatEntry();
                case ENTERING_BOAT -> tickBoatEntry();
                case TRAVELLING_BY_BOAT -> tickBoatTravel();
                case FINDING_MINECART -> tryStartMinecartEntry();
                case ENTERING_MINECART -> tickMinecartEntry();
                case TRAVELLING_BY_MINECART -> tickMinecartTravel();
                case SETTLING_FOR_BRIDGE -> tickBridgePreparation();
                case BRIDGING_GAP -> tickBridge();
                case TOWERING_UP -> tickTower();
                case SETTLING_FOR_FOCUSED_WATER ->
                    tickFocusedWaterLedgeSettlement();
                case WATER_CLUTCH_DESCENDING ->
                    tickDeliberateWaterClutchDescent();
                case WATER_CLUTCHING -> tickWaterClutch();
                case SETTLING_FOR_PARKOUR ->
                    tickParkourSettlement();
                case PARKOUR_RUNNING -> tickParkour();
                case SETTLING_FOR_PARKOUR_LONG_GAP ->
                    tickLongGapParkourSettlement();
                case PARKOUR_LONG_GAP -> tickLongGapParkour();
                case SETTLING_FOR_PARKOUR_TURNING_UP ->
                    tickTurningElevatedParkourSettlement();
                case PARKOUR_TURNING_UP ->
                    tickTurningElevatedParkour();
                case TRAVELLING_DIAGONAL_DETOUR ->
                    tickTravelDiagonalDetour();
                case SCANNING_NETHER_PORTAL_SITE ->
                    tickScanNetherPortalSite();
                case SETTLING_FOR_NETHER_PORTAL_BUILD ->
                    tickNetherPortalBuildSettlement();
                case BUILDING_NETHER_PORTAL -> tickBuildNetherPortal();
                case FINDING_BUILT_NETHER_PORTAL ->
                    tryStartBuiltNetherPortalEntry();
                case ENTERING_BUILT_NETHER_PORTAL ->
                    tickBuiltNetherPortalEntry();
                case EXPLORING_NETHER_FOR_TARGET ->
                    tickNetherExploration();
                case NETHER_FALL_CLUTCHING ->
                    tickNetherFallClutch();
                case WAITING_FOR_FOCUSED_NETHER_SIMULATION ->
                    tickFocusedNetherSimulationReadiness();
                case FINDING_NETHER_BLAZE ->
                    tryStartNetherBlazeCombat();
                case ACQUIRING_NETHER_BLAZE_ROD ->
                    tickNetherBlazeCombat();
                case RETURNING_TO_NETHER_PORTAL ->
                    tickReturnToNetherPortal();
                case FINDING_NETHER_RETURN_PORTAL ->
                    tryStartNetherReturnPortalEntry();
                case ENTERING_NETHER_RETURN_PORTAL ->
                    tickNetherReturnPortalEntry();
                case EXPLORING_FOR_TARGET ->
                    tickExploreForObservedTarget();
                case COLLECTING_DROP -> tickDropCollection();
                case VERIFYING_OCCLUDED_THREAT ->
                    tickOccludedThreatAudit();
                case SETTLING_FOR_ENDER_RESERVE ->
                    tickEnderReserveSettlement();
                case FINDING_SHELTERED_ENDERMAN ->
                    tryStartShelteredEndermanCombat();
                case ACQUIRING_ENDER_PEARL ->
                    tickShelteredEndermanCombat();
                case FINDING_RESOURCE_TARGET ->
                    tryStartResourceCombat();
                case ENGAGING_AND_COLLECTING ->
                    tickResourceCombat();
                case FINDING_RANGED_TARGET -> tryStartRangedAttack();
                case SHOOTING_RANGED_TARGET -> tickRangedAttack();
                case VERIFYING_RANGED_HIT -> verifyRangedHit();
                case TOWERING_TO_CRYSTAL_CAGE ->
                    tickCrystalCageTower();
                case EQUIPPING_CRYSTAL_PICKAXE ->
                    tickEquipCrystalPickaxe();
                case FINDING_CRYSTAL_CAGE_BAR ->
                    tryStartCrystalCageBreak();
                case BREAKING_CRYSTAL_CAGE_BAR ->
                    tickCrystalCageBreak();
                case DESCENDING_FROM_CRYSTAL_CAGE ->
                    tickCrystalCageDescent();
                case MOVING_TO_CRYSTAL_SHOT ->
                    tickMoveToCrystalShot();
                case EQUIPPING_CRYSTAL_BOW ->
                    tickEquipCrystalBow();
                case FINDING_END_CRYSTAL -> tryStartEndCrystalAttack();
                case SHOOTING_END_CRYSTAL -> tickEndCrystalAttack();
                case VERIFYING_END_CRYSTAL -> verifyEndCrystalDestroyed();
                case WAITING_FOR_STRONGHOLD_TRACE_SAFETY ->
                    tryStartStrongholdEyeTrace();
                case TRACING_STRONGHOLD_EYE ->
                    tickStrongholdEyeTrace();
                case TRAVELLING_FOR_SECOND_EYE ->
                    tickTravelForSecondEye();
                case TRACING_SECOND_STRONGHOLD_EYE ->
                    tickSecondStrongholdEyeTrace();
                case WAITING_FOR_STRONGHOLD_COMPOUND ->
                    tryStartStrongholdTriangulation();
                case TRIANGULATING_STRONGHOLD ->
                    tickStrongholdTriangulation();
                case WAITING_FOR_STRONGHOLD_REACH ->
                    tryStartStrongholdReach();
                case REACHING_STRONGHOLD ->
                    tickStrongholdReach();
                case SETTLING_FOR_END_PORTAL_SEARCH ->
                    tickEndPortalSearchSettlement();
                case EXPLORING_FOR_END_PORTAL ->
                    tickExploreForEndPortal();
                case ACTIVATING_END_PORTAL -> tickEndPortalActivation();
                case FINDING_BED -> tryStartSleeping();
                case SLEEPING -> tickSleeping();
                case FINDING_PORTAL -> tryStartPortalEntry();
                case ENTERING_PORTAL -> tickPortalEntry();
                case TRAVELLING_TO_END_GAP ->
                    tickTravelToEndGap();
                case BRIDGING_END_GAP -> tickBridgeEndGap();
                case TRAVELLING_TO_END_ARENA ->
                    tickTravelToEndArena();
                case FIGHTING_DRAGON -> tickDragonFight();
                case WAITING_FOR_DRAGON_DEATH ->
                    tickDragonDeathAnimation();
                case FINDING_RETURN_PORTAL ->
                    tryStartReturnPortalEntry();
                case ENTERING_RETURN_PORTAL ->
                    tickReturnPortalEntry();
                case FINISHED -> {
                }
            }
        }

        private void prepareBoat() {
            final var level = helper.getLevel();
            for (int x = -1; x <= 1; x++) {
                for (int z = 2; z <= 12; z++) {
                    level.setBlockAndUpdate(
                        origin.offset(x, -1, z),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    level.setBlockAndUpdate(
                        origin.offset(x, 0, z),
                        Blocks.WATER.defaultBlockState()
                    );
                }
            }
            final var player = player();
            player.stopRiding();
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            boat = helper.spawn(
                EntityTypes.OAK_BOAT,
                fixtureRelative(0.5, 1.0, 2.5)
            );
            boat.setYRot(0.0F);
            boatTravelStart = boat.position();
            face(player, boat.getBoundingBox().getCenter());
            enter(Stage.FINDING_BOAT);
        }

        private void tryStartBoatEntry() {
            face(player(), boat.getBoundingBox().getCenter());
            final BrainObservation observation = freshObservation();
            final JsonObject entity = findEntity(
                observation,
                "minecraft:oak_boat"
            );
            if (entity == null) {
                awaitTarget("oak boat");
                return;
            }
            final long sample = sampleSequence(observation);
            startSkill(
                BoatTransportSkills.ENTER_OBSERVED_BOAT,
                List.of(
                    argument("dimension", OVERWORLD),
                    argument("sampleSequence", Long.toString(sample)),
                    argument(
                        "observationId",
                        entity.get("observationId").getAsString()
                    )
                ),
                observation
            );
            enter(Stage.ENTERING_BOAT);
        }

        private void tickBoatEntry() {
            face(player(), boat.getBoundingBox().getCenter());
            freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(false);
            if (!completed(snapshot)) {
                return;
            }
            final var player = player();
            helper.assertTrue(
                player.getControlledVehicle() == boat
                    && boat.getControllingPassenger() == player,
                "enter_observed_boat completed without a real vanilla mount"
            );
            boatTravelStart = boat.position();
            boatDestination = boatTravelStart.add(0.0, 0.0, 4.0);
            final BrainObservation observation = freshObservation();
            startSkill(
                BoatTransportSkills.BOAT_TRAVEL_TO,
                List.of(
                    argument("dimension", OVERWORLD),
                    argument("x", decimal(boatDestination.x())),
                    argument("y", decimal(boatDestination.y())),
                    argument("z", decimal(boatDestination.z())),
                    argument("arrivalRadius", "0.9"),
                    argument("timeoutTicks", "240"),
                    argument("dismountAtArrival", "false")
                ),
                observation
            );
            enter(Stage.TRAVELLING_BY_BOAT);
        }

        private void tickBoatTravel() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(false);
            if (!completed(snapshot)) {
                return;
            }
            final double travelled = horizontalDistance(
                boatTravelStart,
                boat.position()
            );
            final double remaining = horizontalDistance(
                boatDestination,
                boat.position()
            );
            helper.assertTrue(
                travelled >= 2.0,
                "boat_travel_to did not produce material vanilla travel: "
                    + travelled
            );
            helper.assertTrue(
                remaining <= 1.5,
                "boat_travel_to completed outside its destination: "
                    + remaining
            );
            helper.assertTrue(
                player().getControlledVehicle() == boat,
                "boat_travel_to lost the controlled vanilla boat"
            );
            prepareMinecart();
        }

        private void prepareMinecart() {
            final var level = helper.getLevel();
            player().stopRiding();
            boat.discard();
            for (int z = 2; z <= 12; z++) {
                level.setBlockAndUpdate(
                    origin.offset(0, -1, z),
                    Blocks.REDSTONE_BLOCK.defaultBlockState()
                );
                level.setBlockAndUpdate(
                    origin.offset(0, 0, z),
                    Blocks.POWERED_RAIL.defaultBlockState()
                );
            }
            level.setBlockAndUpdate(
                origin.offset(0, 0, 1),
                Blocks.AIR.defaultBlockState()
            );
            minecart = helper.spawn(
                EntityTypes.MINECART,
                fixtureRelative(0.5, 0.1, 2.5)
            );
            final var player = player();
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(player, minecart.getBoundingBox().getCenter());
            enter(Stage.FINDING_MINECART);
        }

        private void tryStartMinecartEntry() {
            face(player(), minecart.getBoundingBox().getCenter());
            final BrainObservation observation = freshObservation();
            final JsonObject entity = findEntity(
                observation,
                "minecraft:minecart"
            );
            if (entity == null) {
                awaitTarget("rideable minecart");
                return;
            }
            final long sample = sampleSequence(observation);
            startSkill(
                MinecartTransportSkills.ENTER_OBSERVED_MINECART,
                List.of(
                    argument("dimension", OVERWORLD),
                    argument("sampleSequence", Long.toString(sample)),
                    argument(
                        "observationId",
                        entity.get("observationId").getAsString()
                    )
                ),
                observation
            );
            enter(Stage.ENTERING_MINECART);
        }

        private void tickMinecartEntry() {
            face(player(), minecart.getBoundingBox().getCenter());
            freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(false);
            if (!completed(snapshot)) {
                return;
            }
            helper.assertTrue(
                player().getVehicle() == minecart
                    && minecart.getFirstPassenger() == player(),
                "enter_observed_minecart completed without a vanilla mount"
            );
            // Keep the approach unobstructed while the first-person entry
            // action resolves, then complete an ordinary powered-rail launch
            // station. Vanilla uses the conductor behind a stationary cart
            // to choose the initial rail direction.
            helper.getLevel().setBlockAndUpdate(
                origin.offset(0, 0, 1),
                Blocks.SMOOTH_STONE.defaultBlockState()
            );
            minecartTravelStart = minecart.position();
            minecartDestination =
                minecartTravelStart.add(0.0, 0.0, 4.0);
            final BrainObservation observation = freshObservation();
            startSkill(
                MinecartTransportSkills.MINECART_TRAVEL_TO,
                List.of(
                    argument("dimension", OVERWORLD),
                    argument("x", decimal(minecartDestination.x())),
                    argument("y", decimal(minecartDestination.y())),
                    argument("z", decimal(minecartDestination.z())),
                    argument("arrivalRadius", "1.0"),
                    argument("timeoutTicks", "300"),
                    argument("dismountAtArrival", "false")
                ),
                observation
            );
            enter(Stage.TRAVELLING_BY_MINECART);
        }

        private void tickMinecartTravel() {
            if (minecartTravelVerified) {
                return;
            }
            // Minecart rider input shares the core one-tick lease so the
            // runtime fail-safe cannot erase it before vanilla rail physics.
            // GameTest drives the supervisor after the normal server post
            // event, therefore it must execute that lease explicitly here.
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                return;
            }
            final double travelled = horizontalDistance(
                minecartTravelStart,
                minecart.position()
            );
            final double remaining = horizontalDistance(
                minecartDestination,
                minecart.position()
            );
            helper.assertTrue(
                travelled >= 2.0,
                "minecart_travel_to did not produce material rail travel: "
                    + travelled
            );
            helper.assertTrue(
                remaining <= 1.75,
                "minecart_travel_to completed outside its destination: "
                    + remaining
            );
            helper.assertTrue(
                player().getVehicle() == minecart,
                "minecart_travel_to lost the ridden vanilla minecart"
            );
            // Preserve the first failure if preparation of the next fixture
            // is rejected. GameTest records that assertion but can still run
            // a later scheduled callback; the removed cart must not overwrite
            // the real bridge-fixture failure with a misleading transport
            // error.
            minecartTravelVerified = true;
            prepareBridge();
        }

        private void prepareBridge() {
            final var level = helper.getLevel();
            player().stopRiding();
            minecart.discard();
            /*
             * The preceding boat lane leaves source water at z=2..12.
             * Minecart rails replace only its centre line, so simply clearing
             * the one-block bridge gap lets water flow back from the sides
             * while the skill aligns. Build a bounded dry fixture around the
             * new scenario so its first-person placement face remains a real
             * solid face instead of being replaced by an old water column.
             */
            for (int x = -2; x <= 2; x++) {
                for (int z = -1; z <= 4; z++) {
                    final boolean boundary =
                        x == -2 || x == 2 || z == -1 || z == 4;
                    for (int y = -2; y <= 1; y++) {
                        level.setBlockAndUpdate(
                            origin.offset(x, y, z),
                            boundary
                                ? Blocks.SMOOTH_STONE.defaultBlockState()
                                : Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            level.setBlockAndUpdate(
                origin.below(),
                Blocks.OBSIDIAN.defaultBlockState()
            );
            level.setBlockAndUpdate(
                origin.offset(0, -1, 2),
                Blocks.OBSIDIAN.defaultBlockState()
            );
            final var player = player();
            player.getInventory().clearContent();
            player.getInventory().setItem(
                0,
                new ItemStack(Items.COBBLESTONE, 4)
            );
            player.getInventory().setSelectedSlot(0);
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(
                player,
                Vec3.atCenterOf(
                    origin.offset(0, -1, 2)
                ).add(0.0, -0.15, 0.0)
            );
            enter(Stage.SETTLING_FOR_BRIDGE);
        }

        private void tickBridgePreparation() {
            final var player = player();
            if (!player.onGround()) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 20,
                    "Bridge fixture did not settle on its vanilla support"
                );
                return;
            }
            helper.assertTrue(
                helper.getLevel()
                    .getFluidState(origin.offset(0, -1, 1))
                    .isEmpty(),
                "Bridge fixture gap was contaminated by an older water lane"
            );
            face(
                player,
                Vec3.atCenterOf(
                    origin.offset(0, -1, 2)
                ).add(0.0, -0.15, 0.0)
            );
            final BrainObservation observation = freshObservation();
            helper.assertTrue(
                semantic(observation)
                    .getAsJsonObject("self")
                    .get("onGround")
                    .getAsBoolean(),
                "Bridge semantic frame was published before vanilla landing"
            );
            startSkill(
                BridgeSkills.BRIDGE_TO,
                List.of(
                    argument("dimension", OVERWORLD),
                    argument(
                        "x",
                        decimal(origin.getX() + 0.5)
                    ),
                    argument(
                        "y",
                        decimal(origin.getY())
                    ),
                    argument(
                        "z",
                        decimal(origin.getZ() + 2.5)
                    ),
                    argument("arrivalRadius", "0.6"),
                    argument("maxBlocks", "1")
                ),
                observation
            );
            enter(Stage.BRIDGING_GAP);
        }

        private void tickBridge() {
            final SkillSupervisor.Snapshot snapshot =
                tickSkill(true);
            if (!completed(snapshot)) {
                return;
            }
            final BlockPos placed = origin.offset(0, -1, 1);
            helper.assertTrue(
                helper.getLevel().getBlockState(placed)
                    .is(Blocks.COBBLESTONE),
                "bridge_to completed without one real cobblestone placement"
            );
            helper.assertTrue(
                player().getInventory().countItem(
                    Items.COBBLESTONE
                ) == 3,
                "bridge_to did not consume exactly one owned block"
            );
            helper.assertTrue(
                player().getZ() >= origin.getZ() + 2.0,
                "bridge_to completed without walking across the new support"
            );
            prepareTower();
        }

        private void prepareTower() {
            final var level = helper.getLevel();
            for (int x = -1; x <= 1; x++) {
                for (int y = 0; y <= 5; y++) {
                    for (int z = -1; z <= 1; z++) {
                        level.setBlockAndUpdate(
                            origin.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            level.setBlockAndUpdate(
                origin.below(),
                Blocks.OBSIDIAN.defaultBlockState()
            );
            final var player = player();
            player.getInventory().clearContent();
            player.getInventory().setItem(
                0,
                new ItemStack(Items.COBBLESTONE, 7)
            );
            player.getInventory().setSelectedSlot(0);
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(
                player,
                player.getEyePosition().add(0.0, 8.0, 0.0)
            );
            final BrainObservation observation = freshObservation();
            startSkill(
                BridgeSkills.TOWER_UP,
                List.of(
                    argument("dimension", OVERWORLD),
                    argument(
                        "targetY",
                        decimal(origin.getY() + 5.0)
                    ),
                    argument("arrivalTolerance", "0.15"),
                    argument("maxBlocks", "5")
                ),
                observation
            );
            enter(Stage.TOWERING_UP);
        }

        private void tickTower() {
            final SkillSupervisor.Snapshot snapshot =
                tickSkill(true);
            if (!completed(snapshot)) {
                return;
            }
            for (int y = 0; y < 5; y++) {
                helper.assertTrue(
                    helper.getLevel()
                        .getBlockState(origin.above(y))
                        .is(Blocks.COBBLESTONE),
                    "tower_up completed without all five real pillar blocks"
                );
            }
            helper.assertTrue(
                player().getInventory().countItem(
                    Items.COBBLESTONE
                ) == 2,
                "tower_up did not consume exactly five owned blocks"
            );
            helper.assertTrue(
                player().getY() >= origin.getY() + 4.9,
                "tower_up completed without landing on its pillar"
            );
            prepareDeliberateWaterClutchDescent();
        }

        private void prepareFocusedWaterStart() {
            final var level = helper.getLevel();
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    for (int y = -1; y <= 8; y++) {
                        level.setBlockAndUpdate(
                            origin.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            for (int y = 0; y < 5; y++) {
                level.setBlockAndUpdate(
                    origin.above(y),
                    Blocks.SMOOTH_STONE.defaultBlockState()
                );
            }
            final var player = player();
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY() + 5.0,
                origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            prepareDeliberateWaterClutchFixture();
            focusedWaterStableTicks = 0;
            enter(Stage.SETTLING_FOR_FOCUSED_WATER);
        }

        private void tickFocusedWaterLedgeSettlement() {
            final var player = player();
            final var expectedSupport = scope == ScenarioScope.WATER_ONLY
                ? Blocks.SMOOTH_STONE
                : Blocks.COBBLESTONE;
            final boolean stableDryLedge =
                player.onGround()
                    && !player.isInWater()
                    && !player.isPassenger()
                    && Math.abs(
                        player.getY() - (origin.getY() + 5.0)
                    ) <= 0.05
                    && helper.getLevel()
                        .getBlockState(player.blockPosition().below())
                        .is(expectedSupport);
            if (!stableDryLedge) {
                focusedWaterStableTicks = 0;
                helper.assertTrue(
                    helper.getTick() - stageStartedAt
                        <= FOCUSED_WATER_SETTLE_TIMEOUT_TICKS,
                    "Water descent fixture did not settle on its "
                        + "vanilla dry ledge"
                );
                return;
            }
            focusedWaterStableTicks++;
            if (focusedWaterStableTicks
                    < FOCUSED_WATER_STABLE_TICKS) {
                return;
            }
            face(
                player,
                Vec3.atLowerCornerOf(deliberateWaterLanding())
                    .add(0.5, 0.0, 0.5)
            );
            final BrainObservation observation = freshObservation();
            helper.assertTrue(
                semantic(observation)
                    .getAsJsonObject("self")
                    .get("onGround")
                    .getAsBoolean(),
                "Water descent semantic frame preceded vanilla landing"
            );
            startDeliberateWaterClutchDescent(observation);
        }

        private void prepareDeliberateWaterClutchDescent() {
            prepareDeliberateWaterClutchFixture();
            /*
             * Tower completion and the edge reposition happen in one server
             * callback.  Give vanilla collision/ground state several real
             * ticks to settle before satisfying the production skill's
             * stable-dry-ledge precondition, exactly as the focused fixture
             * does.  Starting from the same callback can leave the body with
             * a stale grounded frame and makes the first physics step walk
             * off the ledge before the clutch controller owns its lease.
             */
            focusedWaterStableTicks = 0;
            enter(Stage.SETTLING_FOR_FOCUSED_WATER);
        }

        private void prepareDeliberateWaterClutchFixture() {
            final var level = helper.getLevel();
            final BlockPos landing = deliberateWaterLanding();
            /*
             * The full chain's earlier dry bridge fixture has a retaining
             * wall at origin x+2. That wall is intentionally useful during
             * bridge validation, but it lies directly beyond this later
             * eastbound landing and the production preflight correctly
             * rejects it as an overshoot collision. Isolate only the later
             * fixture's one-block-wide exit corridor; keep the tower ledge
             * and exact landing support intact. Production rejection of an
             * actually observed high block remains covered by JVM tests.
             */
            for (int y = 0; y <= 1; y++) {
                level.setBlockAndUpdate(
                    landing.offset(1, y, 0),
                    Blocks.AIR.defaultBlockState()
                );
            }
            level.setBlockAndUpdate(
                    landing.below(),
                    Blocks.SMOOTH_STONE.defaultBlockState()
            );
            for (int y = 0; y <= 7; y++) {
                level.setBlockAndUpdate(
                        landing.above(y),
                        Blocks.AIR.defaultBlockState()
                );
            }
            final var player = player();
            /*
             * Put the body at the legitimate edge of its one-block ledge.
             * From the block centre an adjacent landing five blocks below is
             * physically occluded by the ledge itself, so the fair
             * first-person ray gate correctly refuses to pretend it was
             * visible.  A centre at +0.90 still leaves 0.40 blocks of the
             * 0.60-wide vanilla player box supported, while its sight line
             * clears the ledge and reaches the adjacent lower surface.
             */
            player.teleportTo(
                origin.getX() + 0.90,
                player.getY(),
                origin.getZ() + 0.5
            );
            player.getInventory().clearContent();
            player.getInventory().setItem(
                    0,
                    new ItemStack(Items.WATER_BUCKET)
            );
            player.getInventory().setSelectedSlot(0);
            player.setHealth(player.getMaxHealth());
            player.fallDistance = 0.0F;
            player.setDeltaMovement(Vec3.ZERO);
            waterBucketUseStart = player.getStats().getValue(
                Stats.ITEM_USED.get(Items.WATER_BUCKET)
            );
            maximumWaterFallDistance = 0.0F;
            observedWaterDescent = false;
            face(
                    player,
                    Vec3.atLowerCornerOf(landing)
                        .add(0.5, 0.0, 0.5)
            );
        }

        private void startDeliberateWaterClutchDescent(
                final BrainObservation observation
        ) {
            final BlockPos landing = deliberateWaterLanding();
            startSkill(
                    BridgeSkills.WATER_CLUTCH_DESCEND,
                    List.of(
                            argument("dimension", OVERWORLD),
                            argument(
                                    "x",
                                    decimal(landing.getX() + 0.5)
                            ),
                            argument(
                                    "y",
                                    decimal(landing.getY())
                            ),
                            argument(
                                    "z",
                                    decimal(landing.getZ() + 0.5)
                            ),
                            argument("arrivalRadius", "0.65"),
                            argument("maximumDropBlocks", "6")
                    ),
                    observation
            );
            enter(Stage.WATER_CLUTCH_DESCENDING);
        }

        private void tickDeliberateWaterClutchDescent() {
            final BlockPos landing = deliberateWaterLanding();
            final var player = player();
            maximumWaterFallDistance = Math.max(
                maximumWaterFallDistance,
                player.fallDistance
            );
            observedWaterDescent |=
                player.getDeltaMovement().y() < -0.08;
            movementObservation();
            final SkillSupervisor.Snapshot snapshot =
                    tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt <= 260,
                        "water_clutch_descend exceeded its bounded window"
                );
                return;
            }
            helper.assertTrue(
                    helper.getLevel().getBlockState(landing)
                        .is(Blocks.WATER),
                    "Deliberate descent completed without real water placement"
            );
            helper.assertTrue(
                    player.getInventory().countItem(
                        Items.WATER_BUCKET
                    ) == 0
                        && player.getInventory().countItem(
                            Items.BUCKET
                        ) == 1,
                    "Deliberate descent did not consume the owned water bucket"
            );
            helper.assertTrue(
                    player.getHealth() == player.getMaxHealth(),
                    "Deliberate water clutch took vanilla fall damage"
            );
            helper.assertTrue(
                player.getStats().getValue(
                    Stats.ITEM_USED.get(Items.WATER_BUCKET)
                ) - waterBucketUseStart == 1,
                "Deliberate water clutch did not record one vanilla "
                    + "water-bucket use"
            );
            helper.assertTrue(
                observedWaterDescent
                    && maximumWaterFallDistance >= 2.5F,
                "Deliberate water clutch completed without a material "
                    + "vanilla fall"
            );
            helper.assertTrue(
                    player.getY() <= landing.getY() + 1.25,
                    "Deliberate water clutch did not reach its visible landing"
            );
            prepareWaterClutch();
        }

        private BlockPos deliberateWaterLanding() {
            return origin.east();
        }

        private void prepareWaterClutch() {
            final var level = helper.getLevel();
            final BlockPos landing = origin.offset(0, -1, 8);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    level.setBlockAndUpdate(
                        landing.offset(x, 0, z),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 1; y <= 14; y++) {
                        level.setBlockAndUpdate(
                            landing.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            final var player = player();
            player.getInventory().clearContent();
            player.getInventory().setItem(
                0,
                new ItemStack(Items.WATER_BUCKET)
            );
            player.getInventory().setSelectedSlot(0);
            player.setHealth(player.getMaxHealth());
            player.fallDistance = 0.0F;
            waterBucketUseStart = player.getStats().getValue(
                Stats.ITEM_USED.get(Items.WATER_BUCKET)
            );
            maximumWaterFallDistance = 0.0F;
            observedWaterDescent = false;
            player.teleportTo(
                landing.getX() + 0.5,
                landing.getY() + 12.0,
                landing.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(
                player,
                Vec3.atCenterOf(landing).add(0.0, 0.5, 0.0)
            );
            freshObservation();
            enter(Stage.WATER_CLUTCHING);
        }

        private void tickWaterClutch() {
            final BlockPos landing = origin.offset(0, -1, 8);
            movementObservation();
            final var player = player();
            maximumWaterFallDistance = Math.max(
                maximumWaterFallDistance,
                player.fallDistance
            );
            observedWaterDescent |=
                player.getDeltaMovement().y() < -0.08;
            final boolean waterPlaced = helper.getLevel()
                .getBlockState(landing.above())
                .is(Blocks.WATER);
            final boolean bucketEmptied =
                player.getInventory().countItem(Items.WATER_BUCKET) == 0
                    && player.getInventory().countItem(Items.BUCKET) == 1;
            if (waterPlaced
                    && bucketEmptied
                    && player.getY() <= landing.getY() + 2.25) {
                helper.assertTrue(
                    player.isAlive(),
                    "fall rescue placed water after the companion died"
                );
                helper.assertTrue(
                    player.getHealth() == player.getMaxHealth(),
                    "water clutch did not prevent all vanilla fall damage: "
                        + player.getHealth()
                );
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 120,
                    "water clutch exceeded its bounded response window"
                );
                helper.assertTrue(
                    player.getStats().getValue(
                        Stats.ITEM_USED.get(Items.WATER_BUCKET)
                    ) - waterBucketUseStart == 1,
                    "Emergency water clutch did not record one vanilla "
                        + "water-bucket use"
                );
                helper.assertTrue(
                    observedWaterDescent
                        && maximumWaterFallDistance >= 7.0F,
                    "Emergency water clutch completed without a real "
                        + "twelve-block fall"
                );
                if (scope == ScenarioScope.WATER_ONLY) {
                    completeFocused();
                    return;
                }
                prepareParkour();
                return;
            }
            helper.assertTrue(
                helper.getTick() - stageStartedAt <= 160,
                "production emergency controller failed a real water clutch"
            );
        }

        /**
         * Reproduces the real completion-chain geometry at a smaller physical
         * scale. The body starts just inside a perpendicular survey baseline.
         * The only route to the target first moves away from it into a diagonal
         * branch. A monotonic Euclidean frontier walker follows the baseline
         * west and becomes stranded; a bounded course-recovery walker must
         * backtrack and enter the branch using ordinary first-person evidence.
         */
        private void prepareTravelDiagonalDetour() {
            final var level = helper.getLevel();
            /*
             * Keep the branch physically adjacent to the baseline, but do
             * not let its rasterized width include the body start. The
             * companion must first travel east along observed support before
             * it can enter the southwest-to-northeast diagonal.
             */
            final BlockPos diagonalStart = origin.offset(15, 0, 0);
            final BlockPos target = origin.offset(-8, 0, 24);
            final double branchRadiusSquared = 4.0;
            for (int offsetX = -12; offsetX <= 15; offsetX++) {
                for (int offsetZ = -3; offsetZ <= 27; offsetZ++) {
                    final boolean baseline =
                        offsetX >= -12
                            && offsetX <= 12
                            && Math.abs(offsetZ) <= 3;
                    final double sampleX = origin.getX()
                            + offsetX + 0.5;
                    final double sampleZ = origin.getZ()
                            + offsetZ + 0.5;
                    final boolean diagonal =
                        distanceSquaredToHorizontalSegment(
                                sampleX,
                                sampleZ,
                                diagonalStart.getX() + 0.5,
                                diagonalStart.getZ() + 0.5,
                                target.getX() + 0.5,
                                target.getZ() + 0.5
                        ) <= branchRadiusSquared;
                    if (!baseline && !diagonal) {
                        continue;
                    }
                    final BlockPos floor =
                        origin.offset(offsetX, -1, offsetZ);
                    level.setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 1; y <= 6; y++) {
                        level.setBlockAndUpdate(
                                floor.above(y),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }

            final var body = player();
            body.getInventory().clearContent();
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.getFoodData().setSaturation(5.0F);
            body.fallDistance = 0.0F;
            body.teleportTo(
                    origin.getX() + 8.5,
                    origin.getY(),
                    origin.getZ() + 0.5
            );
            body.setDeltaMovement(Vec3.ZERO);
            travelDetourStart = body.position();
            travelDetourTarget = new Vec3(
                    target.getX() + 0.5,
                    origin.getY(),
                    target.getZ() + 0.5
            );
            travelDetourMaximumX = body.getX();
            face(body, travelDetourTarget.add(0.0, 1.0, 0.0));
            final BrainObservation observation = freshObservation();
            startSkill(
                    TravelSkills.TRAVEL_TO,
                    List.of(
                            argument("dimension", OVERWORLD),
                            argument(
                                    "x",
                                    decimal(travelDetourTarget.x())
                            ),
                            argument(
                                    "y",
                                    decimal(travelDetourTarget.y())
                            ),
                            argument(
                                    "z",
                                    decimal(travelDetourTarget.z())
                            ),
                            argument("arrivalRadius", "0.8")
                    ),
                    observation
            );
            enter(Stage.TRAVELLING_DIAGONAL_DETOUR);
        }

        private void tickTravelDiagonalDetour() {
            movementObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            final var body = player();
            travelDetourMaximumX = Math.max(
                    travelDetourMaximumX,
                    body.getX()
            );
            if (!completed(snapshot)) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt <= 2_400,
                        "travel_to did not recover into the diagonal branch"
                            + " position=" + body.position()
                            + " target=" + travelDetourTarget
                            + " maximumX=" + travelDetourMaximumX
                            + " supervisor=" + snapshot
                );
                return;
            }
            final double remaining = horizontalDistance(
                    body.position(),
                    travelDetourTarget
            );
            helper.assertTrue(
                    remaining <= 1.1,
                    "travel_to completed outside the diagonal destination: "
                        + remaining
            );
            helper.assertTrue(
                    horizontalDistance(
                            body.position(),
                            travelDetourStart
                    ) >= 20.0,
                    "travel_to completed without material vanilla movement"
            );
            helper.assertTrue(
                    travelDetourMaximumX
                        >= travelDetourStart.x() + 0.70,
                    "travel_to never made the mandatory short away-from-goal "
                        + "detour into the diagonal branch: start="
                        + travelDetourStart
                        + " maximumX=" + travelDetourMaximumX
                        + " final=" + body.position()
            );
            helper.assertTrue(
                    body.onGround()
                        && body.getHealth() == body.getMaxHealth()
                        && helper.getLevel()
                            .getBlockState(body.blockPosition().below())
                            .is(Blocks.SMOOTH_STONE),
                    "travel_to left the observed supported course: position="
                        + body.position() + " health=" + body.getHealth()
            );
            completeFocused();
        }

        private static double distanceSquaredToHorizontalSegment(
                final double pointX,
                final double pointZ,
                final double startX,
                final double startZ,
                final double endX,
                final double endZ
        ) {
            final double deltaX = endX - startX;
            final double deltaZ = endZ - startZ;
            final double lengthSquared =
                    deltaX * deltaX + deltaZ * deltaZ;
            if (lengthSquared <= 1.0E-9) {
                final double x = pointX - startX;
                final double z = pointZ - startZ;
                return x * x + z * z;
            }
            final double projection = Math.max(
                    0.0,
                    Math.min(
                            1.0,
                            ((pointX - startX) * deltaX
                                + (pointZ - startZ) * deltaZ)
                                / lengthSquared
                    )
            );
            final double nearestX = startX + projection * deltaX;
            final double nearestZ = startZ + projection * deltaZ;
            final double x = pointX - nearestX;
            final double z = pointZ - nearestZ;
            return x * x + z * z;
        }

        private void prepareParkour() {
            final var level = helper.getLevel();
            final BlockPos course = origin.offset(8, -1, 0);
            final int recoveryFloorDepth =
                scope.forceHardcorePolicy() ? -3 : -5;
            for (int x = -1; x <= 1; x++) {
                for (int z = -3; z <= 8; z++) {
                    final boolean platform = z <= 0
                        || z == 2
                        || z == 4
                        || z >= 6;
                    level.setBlockAndUpdate(
                        course.offset(x, 0, z),
                        platform
                            ? Blocks.SMOOTH_STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState()
                    );
                    for (int y = 1; y <= 5; y++) {
                        level.setBlockAndUpdate(
                            course.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                    level.setBlockAndUpdate(
                        course.offset(x, recoveryFloorDepth, z),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                }
            }
            final var player = player();
            player.getInventory().clearContent();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.fallDistance = 0.0F;
            player.teleportTo(
                course.getX() + 0.5,
                course.getY() + 1.0,
                course.getZ() - 1.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            parkourStableTicks = 0;
            enter(Stage.SETTLING_FOR_PARKOUR);
        }

        private void tickParkourSettlement() {
            final BlockPos course = origin.offset(8, -1, 0);
            if (!settledOnParkourSupport(
                    course.getY() + 1.0,
                    "initial course"
            )) {
                return;
            }
            final var player = player();
            face(
                player,
                Vec3.atCenterOf(
                    course.offset(0, 0, 2)
                ).add(0.0, 0.5, 0.0)
            );
            parkourInitialJumpStat = player.getStats().getValue(
                Stats.CUSTOM.get(Stats.JUMP)
            );
            final BrainObservation observation = freshObservation();
            assertParkourSemanticGrounded(observation);
            startSkill(
                ParkourSkills.PARKOUR_TO,
                List.of(
                    argument("dimension", OVERWORLD),
                    argument(
                        "x",
                        decimal(course.getX() + 0.5)
                    ),
                    argument(
                        "y",
                        decimal(course.getY() + 1.0)
                    ),
                    argument(
                        "z",
                        decimal(course.getZ() + 7.5)
                    ),
                    argument("arrivalRadius", "0.65"),
                    argument("maxJumps", "3"),
                    argument("maxGap", "1")
                ),
                observation
            );
            enter(Stage.PARKOUR_RUNNING);
        }

        private void tickParkour() {
            movementObservation();
            final SkillSupervisor.Snapshot snapshot =
                tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 240,
                    "parkour_to exceeded its continuous course window"
                        + " position=" + player().position()
                        + " velocity=" + player().getDeltaMovement()
                        + " onGround=" + player().onGround()
                        + " supervisor=" + snapshot
                );
                return;
            }
            final BlockPos course = origin.offset(8, -1, 0);
            final var player = player();
            final int jumps = player.getStats().getValue(
                Stats.CUSTOM.get(Stats.JUMP)
            ) - parkourInitialJumpStat;
            helper.assertTrue(
                jumps >= 3,
                "parkour_to completed without three vanilla jumps: "
                    + jumps
            );
            helper.assertTrue(
                player.getZ() >= course.getZ() + 6.85,
                "parkour_to completed before reaching the final platform"
            );
            helper.assertTrue(
                player.getHealth() == player.getMaxHealth()
                    && player.fallDistance == 0.0F,
                "parkour_to took fall damage on the verified course"
            );
            for (int gap : new int[]{1, 3, 5}) {
                helper.assertTrue(
                    helper.getLevel()
                        .getBlockState(course.offset(0, 0, gap))
                        .isAir(),
                    "parkour_to modified a course gap instead of jumping"
                );
            }
            prepareLongGapParkour();
        }

        private void prepareLongGapParkour() {
            final var level = helper.getLevel();
            final BlockPos course = origin.offset(12, -1, 0);
            final int recoveryFloorDepth =
                scope.forceHardcorePolicy() ? -3 : -5;
            for (int x = -1; x <= 1; x++) {
                for (int z = -4; z <= 6; z++) {
                    final boolean platform = z <= 0 || z >= 3;
                    level.setBlockAndUpdate(
                        course.offset(x, 0, z),
                        platform
                            ? Blocks.SMOOTH_STONE.defaultBlockState()
                            : Blocks.AIR.defaultBlockState()
                    );
                    for (int y = 1; y <= 5; y++) {
                        level.setBlockAndUpdate(
                            course.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                    level.setBlockAndUpdate(
                        course.offset(x, recoveryFloorDepth, z),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                }
            }
            final var player = player();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.fallDistance = 0.0F;
            player.teleportTo(
                course.getX() + 0.5,
                course.getY() + 1.0,
                course.getZ() - 2.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            parkourStableTicks = 0;
            enter(Stage.SETTLING_FOR_PARKOUR_LONG_GAP);
        }

        private void tickLongGapParkourSettlement() {
            final BlockPos course = origin.offset(12, -1, 0);
            if (!settledOnParkourSupport(
                    course.getY() + 1.0,
                    "two-block-gap course"
            )) {
                return;
            }
            final var player = player();
            face(
                player,
                Vec3.atCenterOf(
                    course.offset(0, 0, 3)
                ).add(0.0, 0.5, 0.0)
            );
            parkourInitialJumpStat = player.getStats().getValue(
                Stats.CUSTOM.get(Stats.JUMP)
            );
            final BrainObservation observation = freshObservation();
            assertParkourSemanticGrounded(observation);
            startSkill(
                ParkourSkills.PARKOUR_TO,
                List.of(
                    argument("dimension", OVERWORLD),
                    argument(
                        "x",
                        decimal(course.getX() + 0.5)
                    ),
                    argument(
                        "y",
                        decimal(course.getY() + 1.0)
                    ),
                    argument(
                        "z",
                        decimal(course.getZ() + 4.5)
                    ),
                    argument("arrivalRadius", "0.65"),
                    argument("maxJumps", "1"),
                    argument("maxGap", "2")
                ),
                observation
            );
            enter(Stage.PARKOUR_LONG_GAP);
        }

        private void tickLongGapParkour() {
            movementObservation();
            final SkillSupervisor.Snapshot snapshot =
                tickSkill(true);
            if (!completed(snapshot)) {
                final BlockPos course = origin.offset(12, -1, 0);
                final var coreFrame = runtime.coreFrames().current();
                final String navigationEvidence = coreFrame
                    .map(frame -> {
                        final GridPos landingFeet = new GridPos(
                            course.getX(),
                            course.getY() + 1,
                            course.getZ() + 3
                        );
                        final GridPos nextFeet = landingFeet.offset(
                            0,
                            0,
                            1
                        );
                        return " navRevision="
                            + frame.navigation().revision()
                            + " feet=" + frame.feet()
                            + " look=" + frame.lookDirection()
                            + " landingSupport="
                            + frame.navigation()
                                .voxelAt(landingFeet.below())
                            + " nextSupport="
                            + frame.navigation()
                                .voxelAt(nextFeet.below())
                            + " nextFeet="
                            + frame.navigation().voxelAt(nextFeet)
                            + " nextHead="
                            + frame.navigation()
                                .voxelAt(nextFeet.above());
                    })
                    .orElse(" coreFrame=unavailable");
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 140,
                    "parkour_to exceeded its two-block-gap window"
                        + " position="
                        + player().position()
                        + " supervisor="
                        + snapshot
                        + " yaw=" + player().getYRot()
                        + " pitch=" + player().getXRot()
                        + " input=" + player().getLastClientInput()
                        + " survival="
                        + runtime.survival().state()
                        + " lease="
                        + runtime.coreActions().snapshot()
                        + navigationEvidence
                );
                return;
            }
            final BlockPos course = origin.offset(12, -1, 0);
            final var player = player();
            final int jumps = player.getStats().getValue(
                Stats.CUSTOM.get(Stats.JUMP)
            ) - parkourInitialJumpStat;
            helper.assertTrue(
                jumps >= 1,
                "parkour_to crossed a two-block gap without a vanilla jump"
            );
            helper.assertTrue(
                player.getZ() >= course.getZ() + 3.85
                    && player.getHealth() == player.getMaxHealth(),
                "parkour_to did not land safely beyond the two-block gap"
            );
            helper.assertTrue(
                helper.getLevel()
                    .getBlockState(course.offset(0, 0, 1))
                    .isAir()
                    && helper.getLevel()
                        .getBlockState(course.offset(0, 0, 2))
                        .isAir(),
                "parkour_to filled its two-block gap"
            );
            prepareTurningElevatedParkour();
        }

        private void prepareTurningElevatedParkour() {
            final var level = helper.getLevel();
            final BlockPos course = origin.offset(18, -1, 0);
            /*
             * The traversed platforms are one block above course Y. Keep the
             * focused Hardcore recovery surface at a three-block feet drop,
             * matching ParkourToSkill's non-lethal recovery policy.
             */
            final int recoveryFloorDepth =
                scope.forceHardcorePolicy() ? -2 : -5;
            for (int x = -2; x <= 5; x++) {
                for (int z = -2; z <= 5; z++) {
                    for (int y = -1; y <= 5; y++) {
                        level.setBlockAndUpdate(
                            course.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                    level.setBlockAndUpdate(
                        course.offset(x, recoveryFloorDepth, z),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                }
            }
            level.setBlockAndUpdate(
                course,
                Blocks.SMOOTH_STONE.defaultBlockState()
            );
            for (BlockPos raised : List.of(
                    course.offset(2, 1, 0),
                    course.offset(2, 1, 1),
                    course.offset(2, 1, 3),
                    course.offset(3, 1, 3)
            )) {
                level.setBlockAndUpdate(
                    raised,
                    Blocks.SMOOTH_STONE.defaultBlockState()
                );
            }
            final var player = player();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.fallDistance = 0.0F;
            player.teleportTo(
                course.getX() + 0.5,
                course.getY() + 1.0,
                course.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            parkourStableTicks = 0;
            enter(Stage.SETTLING_FOR_PARKOUR_TURNING_UP);
        }

        private void tickTurningElevatedParkourSettlement() {
            final BlockPos course = origin.offset(18, -1, 0);
            if (!settledOnParkourSupport(
                    course.getY() + 1.0,
                    "turning/elevated course"
            )) {
                return;
            }
            final var player = player();
            face(
                player,
                Vec3.atCenterOf(
                    course.offset(2, 1, 0)
                ).add(0.0, 0.5, 0.0)
            );
            parkourInitialJumpStat = player.getStats().getValue(
                Stats.CUSTOM.get(Stats.JUMP)
            );
            final BrainObservation observation = freshObservation();
            assertParkourSemanticGrounded(observation);
            startSkill(
                ParkourSkills.PARKOUR_TO,
                List.of(
                    argument("dimension", OVERWORLD),
                    argument(
                        "x",
                        decimal(course.getX() + 3.5)
                    ),
                    argument(
                        "y",
                        decimal(course.getY() + 2.0)
                    ),
                    argument(
                        "z",
                        decimal(course.getZ() + 3.5)
                    ),
                    argument("arrivalRadius", "0.65"),
                    argument("maxJumps", "2"),
                    argument("maxGap", "1")
                ),
                observation
            );
            enter(Stage.PARKOUR_TURNING_UP);
        }

        private boolean settledOnParkourSupport(
                final double expectedY,
                final String courseName
        ) {
            final var player = player();
            final boolean stableDrySupport =
                player.onGround()
                    && !player.isInWater()
                    && !player.isPassenger()
                    && Math.abs(player.getY() - expectedY) <= 0.05
                    && helper.getLevel()
                        .getBlockState(player.blockPosition().below())
                        .is(Blocks.SMOOTH_STONE);
            if (!stableDrySupport) {
                parkourStableTicks = 0;
                helper.assertTrue(
                    helper.getTick() - stageStartedAt
                        <= PARKOUR_SETTLE_TIMEOUT_TICKS,
                    "Parkour fixture did not settle on vanilla support: "
                        + courseName
                );
                return false;
            }
            parkourStableTicks++;
            return parkourStableTicks >= PARKOUR_STABLE_TICKS;
        }

        private void assertParkourSemanticGrounded(
                final BrainObservation observation
        ) {
            helper.assertTrue(
                semantic(observation)
                    .getAsJsonObject("self")
                    .get("onGround")
                    .getAsBoolean(),
                "Parkour semantic frame preceded vanilla landing"
            );
        }

        private void tickTurningElevatedParkour() {
            movementObservation();
            final SkillSupervisor.Snapshot snapshot =
                tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 260,
                    "parkour_to exceeded its turning/elevation window"
                        + " position="
                        + player().position()
                        + " velocity="
                        + player().getDeltaMovement()
                        + " onGround="
                        + player().onGround()
                        + " supervisor="
                        + snapshot
                );
                return;
            }
            final BlockPos course = origin.offset(18, -1, 0);
            final var player = player();
            final int jumps = player.getStats().getValue(
                Stats.CUSTOM.get(Stats.JUMP)
            ) - parkourInitialJumpStat;
            helper.assertTrue(
                jumps >= 2,
                "parkour_to completed the turning course without two "
                    + "vanilla jumps: " + jumps
            );
            helper.assertTrue(
                player.getX() >= course.getX() + 2.85
                    && player.getZ() >= course.getZ() + 2.85
                    && player.getY() >= course.getY() + 1.85,
                "parkour_to did not finish the turn on the raised platform"
                    + " position=" + player.position()
                    + " courseRelative=("
                    + (player.getX() - course.getX()) + ","
                    + (player.getY() - course.getY()) + ","
                    + (player.getZ() - course.getZ()) + ")"
                    + " velocity=" + player.getDeltaMovement()
                    + " jumps=" + jumps
            );
            helper.assertTrue(
                player.getHealth() == player.getMaxHealth(),
                "turning/elevated parkour caused fall damage"
            );
            helper.assertTrue(
                levelBlockIsAir(course.offset(1, 0, 0))
                    && levelBlockIsAir(course.offset(2, 1, 2)),
                "parkour_to filled a turning-course gap"
            );
            if (scope == ScenarioScope.PARKOUR_ONLY) {
                completeFocused();
                return;
            }
            prepareNetherPortalBuild();
        }

        private boolean levelBlockIsAir(final BlockPos position) {
            return helper.getLevel().getBlockState(position).isAir();
        }

        private void prepareNetherPortalBuild() {
            final var level = helper.getLevel();
            builtPortalAnchor = origin.offset(-2, -1, 10);
            for (int x = -2; x <= 5; x++) {
                for (int z = -4; z <= 2; z++) {
                    level.setBlockAndUpdate(
                        builtPortalAnchor.offset(x, 0, z),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 1; y <= 6; y++) {
                        level.setBlockAndUpdate(
                            builtPortalAnchor.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            for (int u = 0; u < 4; u++) {
                level.setBlockAndUpdate(
                    builtPortalAnchor.offset(u, 0, 0),
                    Blocks.AIR.defaultBlockState()
                );
                level.setBlockAndUpdate(
                    builtPortalAnchor.offset(u, -1, 0),
                    Blocks.SMOOTH_STONE.defaultBlockState()
                );
            }
            /*
             * Give the ordinary first-person ray sampler an opaque surface
             * behind the proposed X-axis frame. Looking through each future
             * frame/interior cell at this wall proves that the site is empty;
             * the production skill still receives only the accumulated fair
             * semantic map and never reads fixture blocks directly.
             */
            for (int u = -1; u <= 4; u++) {
                for (int v = 0; v <= 5; v++) {
                    level.setBlockAndUpdate(
                        builtPortalAnchor.offset(u, v, 2),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                }
            }
            final var player = player();
            player.getInventory().clearContent();
            player.getInventory().setItem(
                0,
                new ItemStack(Items.OBSIDIAN, 14)
            );
            player.getInventory().setItem(
                1,
                new ItemStack(Items.FLINT_AND_STEEL)
            );
            player.getInventory().setSelectedSlot(0);
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            level.setBlockAndUpdate(
                builtPortalAnchor.offset(1, 1, -1),
                Blocks.SMOOTH_STONE.defaultBlockState()
            );
            level.setBlockAndUpdate(
                builtPortalAnchor.offset(2, 1, -1),
                Blocks.SMOOTH_STONE.defaultBlockState()
            );
            player.teleportTo(
                builtPortalAnchor.getX() + 2.0,
                builtPortalAnchor.getY() + 2.0,
                builtPortalAnchor.getZ() - 0.31
            );
            player.setDeltaMovement(Vec3.ZERO);
            portalSiteScanIndex = 0;
            enter(Stage.SCANNING_NETHER_PORTAL_SITE);
        }

        private void tickScanNetherPortalSite() {
            final int supportSamples = 4;
            final int airSamples = 20;
            if (portalSiteScanIndex < supportSamples) {
                final BlockPos support = builtPortalAnchor.offset(
                    portalSiteScanIndex,
                    -1,
                    0
                );
                face(
                    player(),
                    new Vec3(
                        support.getX() + 0.5,
                        support.getY() + 1.0,
                        support.getZ() + 0.5
                    )
                );
            } else if (portalSiteScanIndex
                    < supportSamples + airSamples) {
                final int cell =
                    portalSiteScanIndex - supportSamples;
                final int u = cell % 4;
                final int v = cell / 4;
                face(
                        player(),
                        Vec3.atCenterOf(
                        builtPortalAnchor.offset(u, v, 2)
                    )
                );
            } else {
                helper.getLevel().setBlockAndUpdate(
                    builtPortalAnchor.offset(1, 2, -1),
                    Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                );
                helper.getLevel().setBlockAndUpdate(
                    builtPortalAnchor.offset(2, 2, -1),
                    Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                );
                player().teleportTo(
                    builtPortalAnchor.getX() + 2.0,
                    builtPortalAnchor.getY() + 2.5,
                    builtPortalAnchor.getZ() - 0.31
                );
                player().setDeltaMovement(Vec3.ZERO);
                portalBuildStableTicks = 0;
                enter(Stage.SETTLING_FOR_NETHER_PORTAL_BUILD);
                return;
            }
            freshObservation();
            portalSiteScanIndex++;
        }

        private void tickNetherPortalBuildSettlement() {
            final var player = player();
            if (!player.onGround()
                    || Math.abs(
                        player.getY()
                            - (builtPortalAnchor.getY() + 2.5)
                    ) > 0.05) {
                portalBuildStableTicks = 0;
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 40,
                    "Portal builder did not settle on its observed "
                        + "construction scaffold"
                );
                return;
            }
            portalBuildStableTicks++;
            final BrainObservation observation =
                freshObservation();
            if (portalBuildStableTicks < 3) {
                return;
            }
            startSkill(
                PortalBuildSkills.BUILD_AND_LIGHT_NETHER_PORTAL,
                List.of(
                    argument("dimension", OVERWORLD),
                    argument(
                        "x",
                        Integer.toString(
                            builtPortalAnchor.getX()
                        )
                    ),
                    argument(
                        "y",
                        Integer.toString(
                            builtPortalAnchor.getY()
                        )
                    ),
                    argument(
                        "z",
                        Integer.toString(
                            builtPortalAnchor.getZ()
                        )
                    ),
                    argument("axis", "x")
                ),
                observation
            );
            enter(Stage.BUILDING_NETHER_PORTAL);
        }

        private void tickBuildNetherPortal() {
            freshObservation();
            final SkillSupervisor.Snapshot snapshot =
                tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 900,
                    "build_and_light_nether_portal exceeded its window"
                );
                return;
            }
            final var level = helper.getLevel();
            int frameBlocks = 0;
            for (int u = 0; u < 4; u++) {
                for (int v = 0; v < 5; v++) {
                    final boolean frame = v == 0
                        || v == 4
                        || u == 0
                        || u == 3;
                    final BlockPos position =
                        builtPortalAnchor.offset(u, v, 0);
                    if (frame) {
                        helper.assertTrue(
                            level.getBlockState(position)
                                .is(Blocks.OBSIDIAN),
                            "portal builder missed frame block " + position
                        );
                        frameBlocks++;
                    } else {
                        helper.assertTrue(
                            level.getBlockState(position)
                                .is(Blocks.NETHER_PORTAL),
                            "portal builder did not activate interior "
                                + position
                        );
                    }
                }
            }
            helper.assertTrue(
                frameBlocks == 14
                    && player().getInventory().countItem(
                        Items.OBSIDIAN
                    ) == 0,
                "portal builder did not consume exactly 14 obsidian"
            );
            final ItemStack flint = player().getMainHandItem();
            helper.assertTrue(
                flint.is(Items.FLINT_AND_STEEL)
                    && flint.getDamageValue() == 1,
                "portal builder did not use normal flint durability"
            );
            /*
             * The raised half-block scaffold exists only so the stationary
             * builder can fairly reach every frame face. Remove it after the
             * build and begin the distinct entry capability from ordinary
             * flat footing, as a player would after stepping off a temporary
             * build scaffold. The portal remains wholly vanilla and the entry
             * skill must still walk into it and trigger a real dimension
             * change.
             */
            level.setBlockAndUpdate(
                builtPortalAnchor.offset(1, 2, -1),
                Blocks.AIR.defaultBlockState()
            );
            level.setBlockAndUpdate(
                builtPortalAnchor.offset(2, 2, -1),
                Blocks.AIR.defaultBlockState()
            );
            player().teleportTo(
                builtPortalAnchor.getX() + 1.5,
                builtPortalAnchor.getY() + 1.0,
                builtPortalAnchor.getZ() - 1.3
            );
            player().setDeltaMovement(Vec3.ZERO);
            enter(Stage.FINDING_BUILT_NETHER_PORTAL);
        }

        private void tryStartBuiltNetherPortalEntry() {
            final var player = player();
            face(
                player,
                Vec3.atCenterOf(
                    builtPortalAnchor.offset(1, 1, 0)
                )
            );
            final BrainObservation observation = freshObservation();
            final JsonObject target = findBlock(
                observation,
                "minecraft:nether_portal"
            );
            if (target == null) {
                awaitTarget("newly built nether portal");
                return;
            }
            final JsonObject block =
                target.getAsJsonObject("block");
            MinecraftAiCompanion.LOGGER.warn(
                "Portal entry target diagnostic anchor={} player={} "
                    + "target={} face={} hit={}",
                builtPortalAnchor,
                player.position(),
                block,
                target.get("face"),
                target.get("hit")
            );
            startSkill(
                PortalSkills.ENTER_OBSERVED_PORTAL,
                List.of(
                    argument("dimension", OVERWORLD),
                    argument(
                        "sampleSequence",
                        Long.toString(sampleSequence(observation))
                    ),
                    argument("x", block.get("x").getAsString()),
                    argument("y", block.get("y").getAsString()),
                    argument("z", block.get("z").getAsString()),
                    argument(
                        "face",
                        target.get("face").getAsString()
                    ),
                    argument(
                        "expectedDestination",
                        "minecraft:the_nether"
                    )
                ),
                observation
            );
            enter(Stage.ENTERING_BUILT_NETHER_PORTAL);
        }

        private void tickBuiltNetherPortalEntry() {
            final SkillSupervisor.Snapshot snapshot =
                tickSkill(true);
            if ((helper.getTick() - stageStartedAt) % 20 == 0) {
                final var body = player();
                final var portal = body.portalProcess;
                MinecraftAiCompanion.LOGGER.warn(
                    "Portal entry diagnostic tick={} position={} "
                        + "velocity={} yaw={} pitch={} block={} "
                        + "portalActive={} portalTime={} portalInside={} "
                        + "cooldown={} coreLease={}",
                    helper.getTick(),
                    body.position(),
                    body.getDeltaMovement(),
                    body.getYRot(),
                    body.getXRot(),
                    body.blockPosition(),
                    portal != null,
                    portal == null ? -1 : portal.getPortalTime(),
                    portal != null && portal.isInsidePortalThisTick(),
                    body.getPortalCooldown(),
                    runtime.coreActions().snapshot()
                );
                final BlockPos feet = body.blockPosition();
                MinecraftAiCompanion.LOGGER.warn(
                    "Portal entry collision diagnostic horizontal={} "
                        + "vertical={} minor={} input={} feetState={} "
                        + "below={} north={} south={} west={} east={} "
                        + "anchor={}",
                    body.horizontalCollision,
                    body.verticalCollision,
                    body.minorHorizontalCollision,
                    body.getLastClientInput(),
                    body.level().getBlockState(feet),
                    body.level().getBlockState(feet.below()),
                    body.level().getBlockState(feet.north()),
                    body.level().getBlockState(feet.south()),
                    body.level().getBlockState(feet.west()),
                    body.level().getBlockState(feet.east()),
                    builtPortalAnchor
                );
            }
            if (!completed(snapshot)) {
                return;
            }
            final var player = player();
            helper.assertTrue(
                player.level().dimension().equals(Level.NETHER),
                "newly built portal did not cause real Nether traversal"
            );
            prepareNetherExploration();
        }

        private void prepareNetherExploration() {
            final var player = player();
            final var level = player.level();
            netherPortalBlock = nearestPortalBlock(
                level,
                player.blockPosition()
            );
            final boolean spansX =
                level.getBlockState(netherPortalBlock.east())
                    .is(Blocks.NETHER_PORTAL)
                    || level.getBlockState(netherPortalBlock.west())
                        .is(Blocks.NETHER_PORTAL);
            final int forwardX = spansX ? 0 : 1;
            final int forwardZ = spansX ? 1 : 0;
            final int sideX = -forwardZ;
            final int sideZ = forwardX;
            final BlockPos portalFeet = player.blockPosition();
            /*
             * A generated destination portal may leave the body centered over
             * its lower frame with no same-height departure floor. Portal
             * traversal was already proven above; begin the independent
             * exploration capability three blocks beyond that frame on a
             * normal, observed, flat corridor.
             */
            final BlockPos feet = portalFeet.offset(
                forwardX * 3,
                0,
                forwardZ * 3
            );
            /*
             * Include the two cells between the safe departure point and the
             * generated portal frame. A vanilla destination portal may stand
             * above irregular netherrack; leaving those cells natural makes
             * this controlled capability test depend on unrelated terrain
             * and correctly causes the fail-closed entry skill to stop after
             * it loses sight of the selected portal face.
             */
            for (int step = -2; step <= 36; step++) {
                for (int side = -3; side <= 3; side++) {
                    final BlockPos column = feet.offset(
                        forwardX * step + sideX * side,
                        0,
                        forwardZ * step + sideZ * side
                    );
                    level.setBlockAndUpdate(
                        column.below(),
                        Blocks.NETHERRACK.defaultBlockState()
                    );
                    if (Math.abs(side) == 3) {
                        for (int y = 0; y <= 4; y++) {
                            level.setBlockAndUpdate(
                                column.above(y),
                                Blocks.NETHERRACK
                                    .defaultBlockState()
                            );
                        }
                    } else {
                        for (int y = 0; y <= 3; y++) {
                            level.setBlockAndUpdate(
                                column.above(y),
                                Blocks.AIR.defaultBlockState()
                            );
                        }
                        level.setBlockAndUpdate(
                            column.above(4),
                            Blocks.NETHERRACK.defaultBlockState()
                        );
                    }
                }
            }
            player.teleportTo(
                feet.getX() + 0.5,
                feet.getY(),
                feet.getZ() + 0.5
            );
            /*
             * A normal coordinate route must end at a verified standable
             * portal approach, never inside the non-standable portal volume.
             * The dedicated observed-portal skill owns the final entry.
             */
            netherPortalReturnTarget = player.position();
            final int landmarkDistance = 30;
            netherExplorationTarget = feet.offset(
                forwardX * landmarkDistance,
                1,
                forwardZ * landmarkDistance
            );
            for (int side = -1; side <= 1; side++) {
                for (int y = 0; y <= 2; y++) {
                    level.setBlockAndUpdate(
                        feet.offset(
                            forwardX * landmarkDistance
                                + sideX * side,
                            y,
                            forwardZ * landmarkDistance
                                + sideZ * side
                        ),
                        Blocks.NETHER_BRICKS.defaultBlockState()
                    );
                }
            }
            player.getInventory().clearContent();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.setDeltaMovement(Vec3.ZERO);
            face(player, Vec3.atCenterOf(netherExplorationTarget));
            netherExplorationStart = player.position();
            final BrainObservation observation = freshObservation();
            MinecraftAiCompanion.LOGGER.warn(
                "Nether exploration setup start={} target={} "
                    + "forward=({},{}) look={} yaw={} pitch={}",
                netherExplorationStart,
                netherExplorationTarget,
                forwardX,
                forwardZ,
                player.getLookAngle(),
                player.getYRot(),
                player.getXRot()
            );
            helper.assertTrue(
                findBlock(observation, "minecraft:nether_bricks")
                    == null,
                "Nether landmark was already first-person visible"
            );
            startSkill(
                ExplorationSkills.EXPLORE_FOR_OBSERVED_TARGET,
                List.of(
                    argument(
                        "dimension",
                        "minecraft:the_nether"
                    ),
                    argument("targetKind", "block"),
                    argument(
                        "targetId",
                        "minecraft:nether_bricks"
                    ),
                    argument("maximumDistance", "64"),
                    argument("stepDistance", "16")
                ),
                observation
            );
            enter(Stage.EXPLORING_NETHER_FOR_TARGET);
        }

        private void tickNetherExploration() {
            final BrainObservation observation = freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if ((helper.getTick() - stageStartedAt) % 50 == 0) {
                final var body = player();
                MinecraftAiCompanion.LOGGER.warn(
                    "Nether exploration diagnostic tick={} position={} "
                        + "target={} distance={} velocity={} input={} "
                        + "look={} yaw={} pitch={} collision=({},{}) "
                        + "visibleFaces={} "
                        + "supervisor={}",
                    helper.getTick(),
                    body.position(),
                    netherExplorationTarget,
                    horizontalDistance(
                        body.position(),
                        Vec3.atCenterOf(netherExplorationTarget)
                    ),
                    body.getDeltaMovement(),
                    body.getLastClientInput(),
                    body.getLookAngle(),
                    body.getYRot(),
                    body.getXRot(),
                    body.horizontalCollision,
                    body.verticalCollision,
                    semantic(observation)
                        .getAsJsonArray("visibleBlockFaces")
                        .size(),
                    snapshot
                );
            }
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 700,
                    "Nether exploration exceeded its window"
                );
                return;
            }
            helper.assertTrue(
                player().level().dimension().equals(Level.NETHER),
                "Nether exploration changed dimensions"
            );
            helper.assertTrue(
                horizontalDistance(
                    player().position(),
                    netherExplorationStart
                ) >= 4.0,
                "Nether exploration completed without walking"
            );
            helper.assertTrue(
                player().level()
                    .getBlockState(netherExplorationTarget)
                    .is(Blocks.NETHER_BRICKS),
                "Nether exploration changed its landmark"
            );
            if (scope == ScenarioScope.PORTAL_RETURN_ONLY) {
                startReturnToNetherPortal();
                return;
            }
            prepareNetherFallClutch();
        }

        private void prepareNetherFallClutch() {
            final var player = player();
            final var level = player.level();
            netherClutchLanding = player.blockPosition().below();
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    final BlockPos support =
                            netherClutchLanding.offset(x, 0, z);
                    level.setBlockAndUpdate(
                            support,
                            Blocks.NETHERRACK.defaultBlockState()
                    );
                    for (int y = 1; y <= 14; y++) {
                        level.setBlockAndUpdate(
                                support.above(y),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            player.getInventory().clearContent();
            player.getInventory().setItem(
                    0,
                    new ItemStack(Items.WATER_BUCKET)
            );
            player.getInventory().setItem(
                    1,
                    new ItemStack(Items.HAY_BLOCK)
            );
            player.getInventory().setSelectedSlot(0);
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.fallDistance = 0.0F;
            player.teleportTo(
                    netherClutchLanding.getX() + 0.5,
                    netherClutchLanding.getY() + 12.0,
                    netherClutchLanding.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(
                    player,
                    Vec3.atCenterOf(netherClutchLanding)
                            .add(0.0, 0.5, 0.0)
            );
            freshObservation();
            enter(Stage.NETHER_FALL_CLUTCHING);
        }

        private void tickNetherFallClutch() {
            freshObservation();
            final var player = player();
            final boolean hayPlaced = player.level()
                    .getBlockState(netherClutchLanding.above())
                    .is(Blocks.HAY_BLOCK);
            final boolean hayConsumed = player.getInventory()
                    .countItem(Items.HAY_BLOCK) == 0;
            final boolean waterPreserved = player.getInventory()
                    .countItem(Items.WATER_BUCKET) == 1;
            if (hayPlaced
                    && hayConsumed
                    && waterPreserved
                    && player.getY()
                        <= netherClutchLanding.getY() + 2.25) {
                helper.assertTrue(
                        player.isAlive()
                            && player.getHealth() >= 17.0F,
                        "Nether hay-bale clutch did not preserve a "
                            + "safe health reserve: "
                            + player.getHealth()
                );
                helper.assertTrue(
                        helper.getTick() - stageStartedAt <= 160,
                        "Nether fall clutch exceeded its bounded "
                            + "response window"
                );
                prepareNetherBlazeCombat();
                return;
            }
            helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 200,
                    "production emergency controller failed a real "
                        + "Nether fall clutch"
            );
        }

        private void prepareNetherBlazeCombat() {
            final var player = player();
            final var level = player.level();
            final BlockPos start = player.blockPosition();
            netherBlazeArenaOrigin = start.immutable();
            for (int x = -12; x <= 12; x++) {
                for (int z = -6; z <= 12; z++) {
                    final BlockPos column = start.offset(x, 0, z);
                    level.setBlockAndUpdate(
                            column.below(),
                            Blocks.NETHER_BRICKS.defaultBlockState()
                    );
                    for (int y = 0; y <= 4; y++) {
                        level.setBlockAndUpdate(
                                column.above(y),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            blazeTargetsSpawned = 0;
            spawnControlledBlaze();
            player.getInventory().clearContent();
            player.setItemInHand(
                    InteractionHand.MAIN_HAND,
                    new ItemStack(Items.DIAMOND_SWORD)
            );
            player.setItemInHand(
                    InteractionHand.OFF_HAND,
                    new ItemStack(Items.SHIELD)
            );
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.setDeltaMovement(Vec3.ZERO);
            blazeWeaponDamageBefore =
                    player.getMainHandItem().getDamageValue();
            face(player, resourceTarget.getEyePosition());
            enter(Stage.FINDING_NETHER_BLAZE);
        }

        private void spawnControlledBlaze() {
            final var level = player().level();
            resourceTarget = EntityTypes.BLAZE.create(
                    level,
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                    resourceTarget != null,
                    "GameTest could not create a Nether Blaze"
            );
            final double zOffset =
                    blazeTargetsSpawned % 2 == 0 ? 6.5 : 0.5;
            resourceTarget.setPos(
                    netherBlazeArenaOrigin.getX() + 0.5,
                    netherBlazeArenaOrigin.getY(),
                    netherBlazeArenaOrigin.getZ() + zOffset
            );
            resourceTarget.setNoAi(true);
            resourceTarget.setHealth(5.0F);
            resourceTarget.setItemSlot(
                    EquipmentSlot.MAINHAND,
                    new ItemStack(Items.BLAZE_ROD)
            );
            resourceTarget.setGuaranteedDrop(
                    EquipmentSlot.MAINHAND
            );
            helper.assertTrue(
                    resourceTarget.getMainHandItem()
                        .is(Items.BLAZE_ROD),
                    "Controlled Blaze did not retain its guaranteed rod"
            );
            helper.assertTrue(
                    level.addFreshEntity(resourceTarget),
                    "GameTest could not add the Nether Blaze"
            );
            blazeTargetsSpawned++;
            face(player(), resourceTarget.getEyePosition());
        }

        private void prepareFocusedNetherBlazeCombat() {
            final var nether = runtime.server().getLevel(Level.NETHER);
            helper.assertTrue(
                    nether != null,
                    "Focused Blaze gate could not access the Nether"
            );
            final BlockPos feet = new BlockPos(
                    origin.getX(),
                    64,
                    origin.getZ()
            );
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 10; z++) {
                    final BlockPos column = feet.offset(x, 0, z);
                    nether.setBlockAndUpdate(
                            column.below(),
                            Blocks.NETHER_BRICKS.defaultBlockState()
                    );
                    for (int y = 0; y <= 5; y++) {
                        nether.setBlockAndUpdate(
                                column.above(y),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            final var player = player();
            player.teleportTo(
                    nether,
                    feet.getX() + 0.5,
                    feet.getY(),
                    feet.getZ() + 0.5,
                    Set.of(),
                    0.0F,
                    0.0F,
                    false
            );
            player.setDeltaMovement(Vec3.ZERO);
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            /*
             * Cross-dimension ServerPlayer placement installs the ordinary
             * vanilla player ticket asynchronously. A production server's
             * 20 TPS cadence naturally gives chunk promotion time to finish,
             * while the unthrottled GameTest server can advance dozens of
             * game ticks in the same wall-clock instant. Do not create the
             * controlled Blaze or its ordinary delayed drop until this gate
             * has proved that the headless player's own ticket made the
             * destination entity-ticking. This keeps the oracle physical:
             * no forced chunk, direct pickup, or shortened pickup delay.
             */
            enter(Stage.WAITING_FOR_FOCUSED_NETHER_SIMULATION);
        }

        private void tickFocusedNetherSimulationReadiness() {
            final var player = player();
            helper.assertTrue(
                    player.level().dimension().equals(Level.NETHER),
                    "Focused Blaze body left the Nether while awaiting "
                        + "its player simulation ticket"
            );
            if (!player.level().isPositionEntityTicking(
                    player.blockPosition()
            )) {
                final long elapsedTicks =
                    helper.getTick() - stageStartedAt;
                final long elapsedNanos =
                    System.nanoTime() - stageStartedNanos;
                /*
                 * The dedicated GameTest server is deliberately
                 * unthrottled. In a full batch it can advance more than
                 * 3,000 server ticks in half a second, starving the vanilla
                 * async chunk workers whose result this physical gate is
                 * waiting to observe. Yield a bounded millisecond to those
                 * workers; do not add or extend any chunk ticket.
                 */
                LockSupport.parkNanos(
                    FOCUSED_ASYNC_CHUNK_YIELD_NANOS
                );
                helper.assertTrue(
                        elapsedTicks
                            <= FOCUSED_SIMULATION_TICKET_TIMEOUT_TICKS
                            || elapsedNanos
                                <= FOCUSED_SIMULATION_TICKET_TIMEOUT_NANOS,
                        "Headless player's vanilla ticket did not make "
                            + "the focused Nether fixture entity-ticking "
                            + "within the bounded async window: ticks="
                            + elapsedTicks + ", wallMillis="
                            + java.time.Duration.ofNanos(elapsedNanos)
                                .toMillis()
                );
                return;
            }
            freshObservation();
            prepareNetherBlazeCombat();
        }

        private void tryStartNetherBlazeCombat() {
            face(player(), resourceTarget.getEyePosition());
            final BrainObservation observation = freshObservation();
            final JsonObject target = findEntity(
                    observation,
                    "minecraft:blaze"
            );
            if (target == null) {
                awaitTarget("Nether Blaze resource target");
                return;
            }
            if (scope == ScenarioScope.BLAZE_RESERVE_ONLY) {
                startSkill(
                        LootSkills.SECURE_NETHER_BLAZE_MATERIAL,
                        List.of(),
                        observation
                );
                enter(Stage.ACQUIRING_NETHER_BLAZE_ROD);
                return;
            }
            startSkill(
                    LootSkills.ACQUIRE_NETHER_BLAZE_ROD,
                    List.of(
                            argument(
                                    "sampleSequence",
                                    Long.toString(
                                            sampleSequence(observation)
                                    )
                            ),
                            argument(
                                    "observationId",
                                    target.get("observationId")
                                            .getAsString()
                            ),
                            argument("maximumTicks", "600")
                    ),
                    observation
            );
            enter(Stage.ACQUIRING_NETHER_BLAZE_ROD);
        }

        private void tickNetherBlazeCombat() {
            if (scope == ScenarioScope.BLAZE_RESERVE_ONLY
                    && (resourceTarget.isRemoved()
                        || !resourceTarget.isAlive())
                    && player().getInventory()
                        .countItem(Items.BLAZE_ROD)
                        < 7
                    && player().getInventory()
                        .countItem(Items.BLAZE_ROD)
                        >= blazeTargetsSpawned) {
                spawnControlledBlaze();
            }
            final BrainObservation observation = freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if ((helper.getTick() - stageStartedAt)
                    % (scope == ScenarioScope.BLAZE_ONLY
                        || scope == ScenarioScope.BLAZE_RESERVE_ONLY
                            ? 5
                            : 50)
                    == 0) {
                final var body = player();
                final List<String> nearbyDrops = body.level()
                    .getEntitiesOfClass(
                        ItemEntity.class,
                        body.getBoundingBox().inflate(16.0)
                    )
                    .stream()
                    .map(drop ->
                        drop.getItem().getItem()
                            + "x" + drop.getItem().getCount()
                            + "@" + drop.position()
                            + ",age=" + drop.getAge()
                            + ",pickupDelay="
                            + drop.hasPickUpDelay()
                            + ",entityTicking="
                            + body.level().isPositionEntityTicking(
                                drop.blockPosition()
                            )
                            + ",pickupIntersection="
                            + body.getBoundingBox()
                                .inflate(1.0, 0.5, 1.0)
                                .intersects(drop.getBoundingBox())
                            + ",alive=" + drop.isAlive()
                            + ",removed=" + drop.isRemoved()
                            + ",distance="
                            + body.distanceTo(drop)
                    )
                    .toList();
                MinecraftAiCompanion.LOGGER.warn(
                    "Nether Blaze diagnostic tick={} player={} target={} "
                        + "distance={} targetAlive={} targetHealth={} "
                        + "weaponDamage={} rods={} input={} yaw={} pitch={} "
                        + "visibleEntities={} nearbyDrops={} supervisor={}",
                    helper.getTick(),
                    body.position(),
                    resourceTarget.position(),
                    body.distanceTo(resourceTarget),
                    resourceTarget.isAlive(),
                    resourceTarget.getHealth(),
                    body.getMainHandItem().getDamageValue(),
                    body.getInventory().countItem(Items.BLAZE_ROD),
                    body.getLastClientInput(),
                    body.getYRot(),
                    body.getXRot(),
                    semantic(observation)
                        .getAsJsonArray("visibleEntities"),
                    nearbyDrops,
                    snapshot
                );
            }
            if (!completed(snapshot)) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt
                            <= (scope
                                    == ScenarioScope.BLAZE_RESERVE_ONLY
                                        ? 4_000
                                        : 620),
                        (scope == ScenarioScope.BLAZE_RESERVE_ONLY
                            ? "secure_nether_blaze_material"
                            : "acquire_nether_blaze_rod")
                            + " exceeded its window"
                );
                return;
            }
            helper.assertTrue(
                    player().level().dimension().equals(Level.NETHER),
                    "Nether Blaze acquisition changed dimensions"
            );
            if (scope != ScenarioScope.BLAZE_RESERVE_ONLY) {
                helper.assertTrue(
                        resourceTarget.isRemoved()
                            || !resourceTarget.isAlive(),
                        "Nether resource skill completed before "
                            + "defeating its bound Blaze"
                );
            }
            helper.assertTrue(
                    player().getInventory().countItem(Items.BLAZE_ROD)
                        >= 1,
                    "Nether resource skill did not confirm the ordinary "
                        + "drop in owned inventory"
            );
            helper.assertTrue(
                    player().getMainHandItem().is(Items.DIAMOND_SWORD)
                        && player().getMainHandItem()
                            .getDamageValue()
                                > blazeWeaponDamageBefore,
                    "Nether Blaze combat did not consume vanilla weapon "
                        + "durability"
            );
            if (scope == ScenarioScope.BLAZE_RESERVE_ONLY) {
                helper.assertTrue(
                        player().getInventory()
                            .countItem(Items.BLAZE_ROD) >= 7,
                        "Blaze reserve skill completed below the "
                            + "fourteen-unit route target"
                );
                helper.assertTrue(
                        blazeTargetsSpawned >= 4,
                        "Blaze reserve did not prove repeated independent "
                            + "combat and pickup cycles: "
                            + blazeTargetsSpawned
                );
                completeFocused();
                return;
            }
            if (scope == ScenarioScope.BLAZE_ONLY) {
                completeFocused();
                return;
            }
            startReturnToNetherPortal();
        }

        private void startReturnToNetherPortal() {
            clearExternalLavaFromControlledReturnCorridor();
            final BrainObservation observation = freshObservation();
            startSkill(
                PortalSkills.RETURN_VIA_VERIFIED_PORTAL,
                List.of(),
                observation
            );
            enter(Stage.RETURNING_TO_NETHER_PORTAL);
        }

        private void clearExternalLavaFromControlledReturnCorridor() {
            /*
             * The destination portal is generated in the real Nether. A
             * source outside this deliberately constructed tunnel can
             * receive a delayed vanilla fluid tick and flow through its open
             * portal end several seconds after setup. That makes this
             * capability fixture depend on unrelated world generation:
             * travel correctly refuses the new lava, but the test was meant
             * to verify ordinary return navigation after the independent
             * clutch scenario. Remove only lava in a bounded buffer around
             * the already controlled portal approach, then seal the bounded
             * return lane so another delayed outside fluid tick cannot enter.
             * Never move the player or alter production
             * observations/actions.
             */
            final var level = player().level();
            final BlockPos target = BlockPos.containing(
                    netherPortalReturnTarget
            );
            final BlockPos current = player().blockPosition();
            final int minimumX =
                    Math.min(target.getX(), current.getX()) - 4;
            final int maximumX =
                    Math.max(target.getX(), current.getX()) + 4;
            final int minimumZ =
                    Math.min(target.getZ(), current.getZ()) - 4;
            final int maximumZ =
                    Math.max(target.getZ(), current.getZ()) + 4;
            final int floorY = target.getY() - 1;
            for (int x = minimumX; x <= maximumX; x++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    setUnlessPortalFrame(
                            level,
                            new BlockPos(x, floorY, z),
                            Blocks.NETHERRACK.defaultBlockState()
                    );
                    for (int y = target.getY();
                            y <= target.getY() + 3; y++) {
                        final boolean perimeter =
                                x == minimumX
                                || x == maximumX
                                || z == minimumZ
                                || z == maximumZ;
                        setUnlessPortalFrame(
                                level,
                                new BlockPos(x, y, z),
                                perimeter
                                        ? Blocks.NETHERRACK
                                            .defaultBlockState()
                                        : Blocks.AIR.defaultBlockState()
                        );
                    }
                    setUnlessPortalFrame(
                            level,
                            new BlockPos(
                                    x,
                                    target.getY() + 4,
                                    z
                            ),
                            Blocks.NETHERRACK.defaultBlockState()
                    );
                }
            }
            if (current.getY() > target.getY()) {
                level.setBlockAndUpdate(
                        current.below(),
                        Blocks.NETHER_BRICKS.defaultBlockState()
                );
            }
            player().clearFire();
        }

        private static void setUnlessPortalFrame(
                final net.minecraft.server.level.ServerLevel level,
                final BlockPos position,
                final net.minecraft.world.level.block.state.BlockState state
        ) {
            final var existing = level.getBlockState(position);
            if (!existing.is(Blocks.NETHER_PORTAL)
                    && !existing.is(Blocks.OBSIDIAN)) {
                level.setBlockAndUpdate(position, state);
            }
        }

        private void tickReturnToNetherPortal() {
            final BrainObservation observation = freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if ((helper.getTick() - stageStartedAt) % 50 == 0) {
                final var body = player();
                MinecraftAiCompanion.LOGGER.warn(
                    "Nether portal return diagnostic tick={} "
                        + "position={} target={} distance={} "
                        + "velocity={} input={} yaw={} pitch={} "
                        + "visibleFaces={} supervisor={} survival={} "
                        + "survivalOwnsHostiles={} survivalOwnsContact={} "
                        + "arbiter={}",
                    helper.getTick(),
                    body.position(),
                    netherPortalReturnTarget,
                    body.position().distanceTo(
                        netherPortalReturnTarget
                    ),
                    body.getDeltaMovement(),
                    body.getLastClientInput(),
                    body.getYRot(),
                    body.getXRot(),
                    semantic(observation)
                        .getAsJsonArray("visibleBlockFaces")
                        .size(),
                    snapshot,
                    runtime.survival().state(),
                    runtime.skillSupervisor()
                        .activeSkillManagesVisibleHostileProximity(),
                    runtime.skillSupervisor()
                        .activeSkillManagesPhysicalContactThreats(),
                    runtime.behaviorArbiter().latest().orElse(null)
                );
            }
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 2_200,
                    "return_via_verified_portal did not complete: "
                        + snapshot
                );
                return;
            }
            helper.assertTrue(
                PortalSkills.RETURN_VIA_VERIFIED_PORTAL.equals(
                    snapshot.skillName()
                ),
                "Portal return completed under another skill: "
                    + snapshot
            );
            helper.assertTrue(
                player().level().dimension().equals(Level.OVERWORLD),
                "remembered arrival did not produce a real reverse "
                    + "Nether traversal"
            );
            if (scope == ScenarioScope.PORTAL_RETURN_ONLY) {
                completeFocused();
                return;
            }
            prepareExploration();
        }

        private void tryStartNetherReturnPortalEntry() {
            face(player(), Vec3.atCenterOf(netherPortalBlock));
            final BrainObservation observation = freshObservation();
            final JsonObject target = findBlock(
                observation,
                "minecraft:nether_portal"
            );
            if (target == null) {
                awaitTarget("Nether return portal");
                return;
            }
            final JsonObject block =
                target.getAsJsonObject("block");
            startSkill(
                PortalSkills.ENTER_OBSERVED_PORTAL,
                List.of(
                    argument(
                        "dimension",
                        "minecraft:the_nether"
                    ),
                    argument(
                        "sampleSequence",
                        Long.toString(sampleSequence(observation))
                    ),
                    argument("x", block.get("x").getAsString()),
                    argument("y", block.get("y").getAsString()),
                    argument("z", block.get("z").getAsString()),
                    argument(
                        "face",
                        target.get("face").getAsString()
                    ),
                    argument(
                        "expectedDestination",
                        OVERWORLD
                    )
                ),
                observation
            );
            enter(Stage.ENTERING_NETHER_RETURN_PORTAL);
        }

        private void tickNetherReturnPortalEntry() {
            final BrainObservation observation = freshObservation();
            if ((helper.getTick() - stageStartedAt) % 5 == 0) {
                final var body = player();
                final BlockPos feet = body.blockPosition();
                MinecraftAiCompanion.LOGGER.warn(
                    "Nether return entry diagnostic tick={} position={} "
                        + "health={} onFire={} fallDistance={} onGround={} "
                        + "feet={} below={} dangers={}",
                    helper.getTick(),
                    body.position(),
                    body.getHealth(),
                    body.isOnFire(),
                    body.fallDistance,
                    body.onGround(),
                    body.level().getBlockState(feet),
                    body.level().getBlockState(feet.below()),
                    semantic(observation).getAsJsonArray("dangers")
                );
            }
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 500,
                    "Nether return portal traversal exceeded its window"
                );
                return;
            }
            helper.assertTrue(
                player().level().dimension().equals(Level.OVERWORLD),
                "AI did not return through the real Nether portal"
            );
            prepareExploration();
        }

        private void prepareExploration() {
            final var level = helper.getLevel();
            final var player = player();
            player.stopRiding();
            for (int x = -3; x <= 3; x++) {
                for (int z = 0; z <= 36; z++) {
                    level.setBlockAndUpdate(
                        origin.offset(x, -1, z),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 0; y <= 3; y++) {
                        level.setBlockAndUpdate(
                            origin.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            explorationTarget = origin.offset(0, 1, 30);
            for (int x = -1; x <= 1; x++) {
                for (int y = 0; y <= 2; y++) {
                    level.setBlockAndUpdate(
                        origin.offset(x, y, 30),
                        Blocks.NETHER_BRICKS.defaultBlockState()
                    );
                }
            }
            player.getInventory().clearContent();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(player, Vec3.atCenterOf(explorationTarget));
            explorationStart = player.position();
            final BrainObservation observation = freshObservation();
            helper.assertTrue(
                findBlock(observation, "minecraft:nether_bricks") == null,
                "Exploration landmark was already first-person visible"
            );
            startSkill(
                ExplorationSkills.EXPLORE_FOR_OBSERVED_TARGET,
                List.of(
                    argument("dimension", OVERWORLD),
                    argument("targetKind", "block"),
                    argument("targetId", "minecraft:nether_bricks"),
                    argument("maximumDistance", "64"),
                    argument("stepDistance", "16")
                ),
                observation
            );
            enter(Stage.EXPLORING_FOR_TARGET);
        }

        private void tickExploreForObservedTarget() {
            freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 500,
                    "explore_for_observed_target exceeded its window"
                );
                return;
            }
            helper.assertTrue(
                horizontalDistance(player().position(), explorationStart)
                    >= 4.0,
                "Exploration completed without physically searching"
            );
            helper.assertTrue(
                helper.getLevel().getBlockState(explorationTarget)
                    .is(Blocks.NETHER_BRICKS),
                "Exploration modified its target landmark"
            );
            final BrainObservation observation = freshObservation();
            helper.assertTrue(
                findBlock(observation, "minecraft:nether_bricks") != null,
                "Exploration completed before the target was visible"
            );
            prepareDropCollection();
        }

        private void prepareDropCollection() {
            final var level = helper.getLevel();
            for (int x = -2; x <= 2; x++) {
                for (int z = 0; z <= 12; z++) {
                    level.setBlockAndUpdate(
                        origin.offset(x, -1, z),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 0; y <= 2; y++) {
                        level.setBlockAndUpdate(
                            origin.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            final var player = player();
            player.getInventory().clearContent();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            lootDrop = new ItemEntity(
                level,
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 9.5,
                new ItemStack(Items.BLAZE_ROD)
            );
            helper.assertTrue(
                level.addFreshEntity(lootDrop),
                "GameTest could not add a dropped blaze rod"
            );
            face(player, lootDrop.getEyePosition());
            lootCollectionStart = player.position();
            final BrainObservation observation = freshObservation();
            final JsonObject target = findEntity(
                observation,
                "minecraft:item"
            );
            helper.assertTrue(
                target != null
                    && target.getAsJsonObject("properties")
                        .get("itemId")
                        .getAsString()
                        .equals("minecraft:blaze_rod"),
                "Dropped item type was not fairly identified"
            );
            startSkill(
                LootSkills.COLLECT_OBSERVED_ITEM,
                List.of(
                    argument(
                        "sampleSequence",
                        Long.toString(sampleSequence(observation))
                    ),
                    argument(
                        "observationId",
                        target.get("observationId").getAsString()
                    ),
                    argument("maximumTicks", "300")
                ),
                observation
            );
            enter(Stage.COLLECTING_DROP);
        }

        private void tickDropCollection() {
            final BrainObservation observation = freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if ((helper.getTick() - stageStartedAt) % 25 == 0) {
                final var body = player();
                MinecraftAiCompanion.LOGGER.warn(
                    "Drop collection diagnostic tick={} position={} "
                        + "drop={} distance={} alive={} inventory={} "
                        + "yaw={} pitch={} input={} visibleEntities={} "
                        + "visibleFaces={} supervisor={}",
                    helper.getTick(),
                    body.position(),
                    lootDrop.position(),
                    body.distanceTo(lootDrop),
                    lootDrop.isAlive(),
                    body.getInventory().countItem(Items.BLAZE_ROD),
                    body.getYRot(),
                    body.getXRot(),
                    body.getLastClientInput(),
                    semantic(observation)
                        .getAsJsonArray("visibleEntities"),
                    semantic(observation)
                        .getAsJsonArray("visibleBlockFaces")
                        .size(),
                    snapshot
                );
            }
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 320,
                    "collect_observed_item exceeded its window"
                );
                return;
            }
            helper.assertTrue(
                player().getInventory().countItem(Items.BLAZE_ROD)
                    == 1,
                "collect_observed_item did not verify owned loot"
            );
            helper.assertTrue(
                lootDrop.isRemoved() || !lootDrop.isAlive(),
                "collect_observed_item completed before pickup"
            );
            helper.assertTrue(
                horizontalDistance(
                    player().position(),
                    lootCollectionStart
                ) >= 4.0,
                "collect_observed_item did not physically approach the drop"
            );
            prepareOccludedThreatAudit();
        }

        private void prepareOccludedThreatAudit() {
            final var level = helper.getLevel();
            for (int x = -3; x <= 3; x++) {
                for (int z = -2; z <= 8; z++) {
                    level.setBlockAndUpdate(
                        origin.offset(x, -1, z),
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
            for (int x = -1; x <= 1; x++) {
                for (int y = 0; y <= 3; y++) {
                    level.setBlockAndUpdate(
                        origin.offset(x, y, 2),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                }
            }
            occludedThreat = helper.spawn(
                EntityTypes.ZOMBIE,
                fixtureRelative(0.5, 0.0, 5.0)
            );
            occludedThreat.setNoAi(true);
            final var player = player();
            player.getInventory().clearContent();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(player, occludedThreat.getEyePosition());
            enter(Stage.VERIFYING_OCCLUDED_THREAT);
        }

        private void tickOccludedThreatAudit() {
            final BrainObservation observation = freshObservation();
            helper.assertTrue(
                findEntity(observation, "minecraft:zombie") == null,
                "A hostile behind an opaque wall leaked into fair vision"
            );
            final var dangers = semantic(observation)
                .getAsJsonArray("dangers");
            for (final var element : dangers) {
                helper.assertTrue(
                    !"HOSTILE_PROXIMITY".equals(
                        element.getAsJsonObject()
                            .get("kind")
                            .getAsString()
                    ),
                    "A hostile behind an opaque wall leaked through the "
                        + "proximity danger channel"
                );
            }
            helper.assertTrue(
                occludedThreat.isAlive()
                    && !occludedThreat.isRemoved(),
                "Occluded-threat audit passed only because its target "
                    + "disappeared"
            );
            occludedThreat.discard();
            prepareShelteredEndermanCombat();
        }

        private void prepareFocusedEnderPearlReserve() {
            final var level = helper.getLevel();
            endermanShelterCenter = origin;
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 10; z++) {
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
            final var player = player();
            player.getInventory().clearContent();
            player.setItemInHand(
                InteractionHand.MAIN_HAND,
                new ItemStack(Items.DIAMOND_SWORD)
            );
            player.setItemInHand(
                InteractionHand.OFF_HAND,
                new ItemStack(Items.SHIELD)
            );
            helper.assertTrue(
                player.getInventory().add(
                    new ItemStack(Items.COBBLESTONE, 64)
                ),
                "Focused pearl gate could not give the body its "
                    + "ordinary starting building stack"
            );
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.teleportTo(
                endermanShelterCenter.getX() + 0.5,
                endermanShelterCenter.getY(),
                endermanShelterCenter.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            enderTargetsSpawned = 0;
            enderStableTicks = 0;
            enderRoofStableSince = -1L;
            lastEnderTargetRemovedAt = -1L;
            enderWeaponDamageBefore =
                player.getMainHandItem().getDamageValue();
            shelteredEnderman = null;
            face(
                player,
                Vec3.atCenterOf(
                    endermanShelterCenter.offset(0, 0, 5)
                )
            );
            enter(Stage.SETTLING_FOR_ENDER_RESERVE);
        }

        private void tickEnderReserveSettlement() {
            final var body = player();
            if (body.onGround()
                    && Math.abs(
                        body.getY()
                            - endermanShelterCenter.getY()
                    ) <= 0.05
                    && body.getDeltaMovement().lengthSqr() <= 0.01) {
                enderStableTicks++;
            } else {
                enderStableTicks = 0;
            }
            helper.assertTrue(
                helper.getTick() - stageStartedAt <= 120,
                "Focused pearl body did not settle on its ordinary floor"
            );
            if (enderStableTicks < 3) {
                return;
            }
            final BrainObservation observation = freshObservation();
            startSkill(
                LootSkills.SECURE_ENDER_PEARL_RESERVE,
                List.of(),
                observation
            );
            enter(Stage.ACQUIRING_ENDER_PEARL);
        }

        private void spawnControlledEnderman() {
            final var level = helper.getLevel();
            shelteredEnderman = EntityTypes.ENDERMAN.create(
                    level,
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                shelteredEnderman != null,
                "GameTest could not create an Enderman"
            );
            shelteredEnderman.setPos(
                endermanShelterCenter.getX() + 0.5,
                endermanShelterCenter.getY(),
                endermanShelterCenter.getZ()
                    + CONTROLLED_ENDERMAN_OFFSET
            );
            /*
             * The no-AI fixture target cannot answer an ordinary lure by
             * walking closer. Keep it beyond the 3x3 roof footprint while
             * guaranteeing vanilla melee reach from every accepted docking
             * point under that roof.
             */
            helper.assertTrue(
                CONTROLLED_ENDERMAN_OFFSET > 1.5
                    && horizontalDistance(
                        player().position(),
                        shelteredEnderman.position()
                    ) <= MAXIMUM_FIXTURE_MELEE_DISTANCE,
                "Controlled Enderman fixture is outside reachable "
                    + "sheltered melee geometry"
            );
            shelteredEnderman.setNoAi(true);
            shelteredEnderman.setHealth(5.0F);
            shelteredEnderman.setItemSlot(
                EquipmentSlot.MAINHAND,
                new ItemStack(Items.ENDER_PEARL)
            );
            shelteredEnderman.setGuaranteedDrop(
                EquipmentSlot.MAINHAND
            );
            helper.assertTrue(
                level.addFreshEntity(shelteredEnderman),
                "GameTest could not add the controlled Enderman"
            );
            runtime.observations().requestObservation(
                new RequestedObservation(
                    ObservationKind.SEMANTIC_REFRESH,
                    "GameTest fixture spawned a controlled Enderman"
                )
            );
            enderTargetsSpawned++;
            lastEnderTargetRemovedAt = -1L;
        }

        private void tickFocusedEnderPearlReserve() {
            final var body = player();
            final boolean targetGone = shelteredEnderman == null
                || shelteredEnderman.isRemoved()
                || !shelteredEnderman.isAlive();
            if (targetGone && lastEnderTargetRemovedAt < 0L) {
                lastEnderTargetRemovedAt = helper.getTick();
            }
            final boolean roofComplete =
                focusedEndermanRoofComplete();
            final boolean temporaryPillarRemoved =
                focusedEndermanTemporaryPillarRemoved();
            final boolean bodyCenteredUnderRoof =
                body.onGround()
                    && horizontalDistance(
                        body.position(),
                        Vec3.atBottomCenterOf(
                            endermanShelterCenter
                        )
                    ) <= 0.30;
            final boolean fairlyVerifiedRoof =
                runtime.coreFrames().current()
                    .map(
                        SecureEnderPearlReserveSkill
                            ::hasObservedSafetyRoof
                    )
                    .orElse(false);
            if (roofComplete
                    && temporaryPillarRemoved
                    && bodyCenteredUnderRoof
                    && fairlyVerifiedRoof) {
                if (enderRoofStableSince < 0L) {
                    enderRoofStableSince = helper.getTick();
                }
            } else {
                enderRoofStableSince = -1L;
            }
            final boolean pearlDropPresent = body.level()
                .getEntitiesOfClass(
                    ItemEntity.class,
                    body.getBoundingBox().inflate(16.0)
                )
                .stream()
                .anyMatch(drop ->
                    drop.getItem().is(Items.ENDER_PEARL)
                        && drop.isAlive()
                        && !drop.isRemoved()
                );
            if (roofComplete
                    && temporaryPillarRemoved
                    && targetGone
                    && !pearlDropPresent
                    && body.getInventory()
                        .countItem(Items.ENDER_PEARL) < 14
                    && bodyCenteredUnderRoof
                    && enderRoofStableSince >= 0L
                    && helper.getTick()
                        - enderRoofStableSince >= 20L
                    && helper.getTick()
                        - lastEnderTargetRemovedAt >= 2L) {
                spawnControlledEnderman();
            }

            /*
             * Keep the production 4 Hz semantic boundary between explicit
             * fixture mutations. A 20 Hz forced refresh concealed navigation
             * loops that occur only when body pose is live but the fair local
             * map is correctly sampled at its normal cadence.
             */
            final BrainObservation observation =
                runtime.observations().observe(
                    runtime.goals().snapshot()
                );
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if ((helper.getTick() - stageStartedAt) % 25L == 0L) {
                final var liveDangers = runtime.coreFrames().current()
                    .map(frame -> frame.dangerSignals())
                    .orElse(List.of());
                MinecraftAiCompanion.LOGGER.info(
                    "Ender reserve diagnostic tick={} body={} "
                        + "roof={} targets={} pearls={} targetAlive={} "
                        + "weapon={} dangers={} supervisor={}",
                    helper.getTick(),
                    body.position(),
                    roofComplete,
                    enderTargetsSpawned,
                    body.getInventory().countItem(
                        Items.ENDER_PEARL
                    ),
                    shelteredEnderman != null
                        && shelteredEnderman.isAlive()
                        && !shelteredEnderman.isRemoved(),
                    body.getMainHandItem(),
                    liveDangers,
                    snapshot
                );
            }
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 8_000,
                    "secure_ender_pearl_reserve exceeded its window"
                );
                return;
            }
            helper.assertTrue(
                body.getInventory().countItem(Items.ENDER_PEARL)
                    >= 14,
                "Pearl reserve completed below fourteen "
                    + "pearl-derived route units"
            );
            helper.assertTrue(
                enderTargetsSpawned >= 7,
                "Pearl reserve did not prove repeated independent "
                    + "sheltered combat cycles: "
                    + enderTargetsSpawned
            );
            helper.assertTrue(
                focusedEndermanRoofComplete(),
                "Pearl reserve did not retain its physically built roof"
            );
            helper.assertTrue(
                focusedEndermanTemporaryPillarRemoved(),
                "Pearl reserve left its temporary construction pillar"
            );
            helper.assertTrue(
                body.getMainHandItem().is(Items.DIAMOND_SWORD)
                    && body.getMainHandItem().getDamageValue()
                        > enderWeaponDamageBefore,
                "Pearl reserve did not consume vanilla weapon durability"
            );
            completeFocused();
        }

        private boolean focusedEndermanRoofComplete() {
            if (endermanShelterCenter == null) {
                return false;
            }
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (!helper.getLevel().getBlockState(
                            endermanShelterCenter.offset(x, 2, z)
                        ).is(Blocks.COBBLESTONE)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean focusedEndermanTemporaryPillarRemoved() {
            if (endermanShelterCenter == null) {
                return false;
            }
            final int[][] offsets = {
                {1, 0},
                {-1, 0},
                {0, 1},
                {0, -1}
            };
            for (int[] offset : offsets) {
                for (int y = 0; y <= 1; y++) {
                    if (helper.getLevel().getBlockState(
                            endermanShelterCenter.offset(
                                offset[0],
                                y,
                                offset[1]
                            )
                        ).is(Blocks.COBBLESTONE)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private void prepareShelteredEndermanCombat() {
            final var level = helper.getLevel();
            endermanShelterCenter = origin;
            endermanShelterScanIndex = 0;
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 8; z++) {
                    level.setBlockAndUpdate(
                        origin.offset(x, -1, z),
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
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    level.setBlockAndUpdate(
                        endermanShelterCenter.offset(x, 2, z),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                }
            }
            shelteredEnderman = helper.spawn(
                EntityTypes.ENDERMAN,
                fixtureRelative(0.5, 0.0, 3.0)
            );
            shelteredEnderman.setNoAi(true);
            shelteredEnderman.setHealth(5.0F);
            shelteredEnderman.setItemSlot(
                EquipmentSlot.MAINHAND,
                new ItemStack(Items.ENDER_PEARL)
            );
            shelteredEnderman.setGuaranteedDrop(
                EquipmentSlot.MAINHAND
            );
            final var player = player();
            player.getInventory().clearContent();
            player.setItemInHand(
                InteractionHand.MAIN_HAND,
                new ItemStack(Items.DIAMOND_SWORD)
            );
            player.setItemInHand(
                InteractionHand.OFF_HAND,
                new ItemStack(Items.SHIELD)
            );
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.teleportTo(
                endermanShelterCenter.getX() + 0.5,
                endermanShelterCenter.getY(),
                endermanShelterCenter.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(player, shelteredEnderman.getEyePosition());
            enter(Stage.FINDING_SHELTERED_ENDERMAN);
        }

        private void tryStartShelteredEndermanCombat() {
            if (endermanShelterScanIndex < 9) {
                final int xOffset =
                    endermanShelterScanIndex % 3 - 1;
                final int zOffset =
                    endermanShelterScanIndex / 3 - 1;
                face(
                    player(),
                    Vec3.atCenterOf(
                        endermanShelterCenter.offset(
                            xOffset,
                            2,
                            zOffset
                        )
                    )
                );
                freshObservation();
                endermanShelterScanIndex++;
                return;
            }
            face(player(), shelteredEnderman.getEyePosition());
            final BrainObservation observation = freshObservation();
            final JsonObject target = findEntity(
                observation,
                "minecraft:enderman"
            );
            if (target == null) {
                awaitTarget("sheltered Enderman");
                return;
            }
            startSkill(
                LootSkills.ACQUIRE_SHELTERED_ENDER_PEARL,
                List.of(
                    argument(
                        "sampleSequence",
                        Long.toString(sampleSequence(observation))
                    ),
                    argument(
                        "observationId",
                        target.get("observationId").getAsString()
                    ),
                    argument("maximumTicks", "600")
                ),
                observation
            );
            enter(Stage.ACQUIRING_ENDER_PEARL);
        }

        private void tickShelteredEndermanCombat() {
            if (scope == ScenarioScope.ENDER_RESERVE_ONLY) {
                tickFocusedEnderPearlReserve();
                return;
            }
            freshObservation();
            final boolean targetAlive = shelteredEnderman.isAlive()
                && !shelteredEnderman.isRemoved();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (targetAlive) {
                helper.assertTrue(
                    horizontalDistance(
                        player().position(),
                        Vec3.atBottomCenterOf(endermanShelterCenter)
                    ) <= 0.40,
                    "Sheltered Enderman combat left the verified roof "
                        + "before the target was defeated"
                );
            }
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 620,
                    "acquire_sheltered_ender_pearl exceeded its window"
                );
                return;
            }
            helper.assertTrue(
                shelteredEnderman.isRemoved()
                    || !shelteredEnderman.isAlive(),
                "Sheltered pearl acquisition completed before defeating "
                    + "its bound Enderman"
            );
            helper.assertTrue(
                player().getInventory().countItem(Items.ENDER_PEARL)
                    >= 1,
                "Sheltered pearl acquisition did not confirm the ordinary "
                    + "drop in owned inventory"
            );
            helper.assertTrue(
                player().getMainHandItem().is(Items.DIAMOND_SWORD)
                    && player().getMainHandItem().getDamageValue() > 0,
                "Sheltered Enderman combat did not apply vanilla weapon "
                    + "durability"
            );
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    helper.assertTrue(
                        helper.getLevel().getBlockState(
                            endermanShelterCenter.offset(x, 2, z)
                        ).is(Blocks.SMOOTH_STONE),
                        "Sheltered Enderman combat modified its protective "
                            + "roof"
                    );
                }
            }
            if (scope == ScenarioScope.ENDER_SINGLE_ONLY) {
                completeFocused();
                return;
            }
            prepareResourceCombat();
        }

        private void prepareResourceCombat() {
            final var level = helper.getLevel();
            for (int x = -2; x <= 2; x++) {
                for (int z = 0; z <= 9; z++) {
                    level.setBlockAndUpdate(
                        origin.offset(x, -1, z),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 0; y <= 3; y++) {
                        level.setBlockAndUpdate(
                            origin.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            resourceTarget = helper.spawn(
                EntityTypes.BLAZE,
                fixtureRelative(0.5, 0.0, 5.0)
            );
            resourceTarget.setNoAi(true);
            resourceTarget.setHealth(5.0F);
            resourceTarget.setItemSlot(
                EquipmentSlot.MAINHAND,
                new ItemStack(Items.BLAZE_ROD)
            );
            resourceTarget.setGuaranteedDrop(
                EquipmentSlot.MAINHAND
            );
            final var player = player();
            player.getInventory().clearContent();
            player.setItemInHand(
                InteractionHand.MAIN_HAND,
                new ItemStack(Items.DIAMOND_SWORD)
            );
            player.setItemInHand(
                InteractionHand.OFF_HAND,
                new ItemStack(Items.SHIELD)
            );
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(player, resourceTarget.getEyePosition());
            enter(Stage.FINDING_RESOURCE_TARGET);
        }

        private void tryStartResourceCombat() {
            face(player(), resourceTarget.getEyePosition());
            final BrainObservation observation = freshObservation();
            final JsonObject target = findEntity(
                observation,
                "minecraft:blaze"
            );
            if (target == null) {
                awaitTarget("blaze resource target");
                return;
            }
            startSkill(
                LootSkills.ENGAGE_AND_COLLECT_OBSERVED_DROP,
                List.of(
                    argument(
                        "sampleSequence",
                        Long.toString(sampleSequence(observation))
                    ),
                    argument(
                        "observationId",
                        target.get("observationId").getAsString()
                    ),
                    argument(
                        "expectedItemId",
                        "minecraft:blaze_rod"
                    ),
                    argument("maximumTicks", "600")
                ),
                observation
            );
            enter(Stage.ENGAGING_AND_COLLECTING);
        }

        private void tickResourceCombat() {
            freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 620,
                    "engage-and-collect exceeded its window"
                );
                return;
            }
            helper.assertTrue(
                resourceTarget.isRemoved()
                    || !resourceTarget.isAlive(),
                "engage-and-collect completed before defeating its "
                    + "bound hostile"
            );
            helper.assertTrue(
                player().getInventory().countItem(Items.BLAZE_ROD)
                    >= 1,
                "engage-and-collect did not confirm the vanilla drop "
                    + "in owned inventory"
            );
            helper.assertTrue(
                player().getMainHandItem().is(Items.DIAMOND_SWORD)
                    && player().getMainHandItem().getDamageValue() > 0,
                "resource combat did not apply vanilla weapon durability"
            );
            prepareRangedCombat();
        }

        private void prepareRangedCombat() {
            final var level = helper.getLevel();
            player().stopRiding();
            minecart.discard();
            for (int x = -16; x <= 16; x++) {
                for (int z = 0; z <= 12; z++) {
                    level.setBlockAndUpdate(
                            origin.offset(x, 0, z),
                            Blocks.AIR.defaultBlockState()
                    );
                    level.setBlockAndUpdate(
                            origin.offset(x, -1, z),
                            z == 10
                                ? Blocks.REDSTONE_BLOCK
                                    .defaultBlockState()
                                : Blocks.SMOOTH_STONE
                                    .defaultBlockState()
                    );
                }
                level.setBlockAndUpdate(
                        origin.offset(x, 0, 10),
                        Blocks.RAIL.defaultBlockState()
                            .setValue(
                                RailBlock.SHAPE,
                                RailShape.EAST_WEST
                            )
                );
            }
            rangedMinecart = EntityTypes.MINECART.create(
                    level,
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                    rangedMinecart != null,
                    "GameTest could not create the moving target cart"
            );
            rangedMinecart.setPos(
                    origin.getX() - 10.5,
                    origin.getY() + 0.1,
                    origin.getZ() + 10.5
            );
            rangedMinecart.setDeltaMovement(0.4, 0.0, 0.0);
            helper.assertTrue(
                    level.addFreshEntity(rangedMinecart),
                    "GameTest could not add the moving target cart"
            );
            rangedTarget = EntityTypes.PILLAGER.create(
                    level,
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                    rangedTarget != null,
                    "GameTest could not create the moving ranged target"
            );
            rangedTarget.setNoAi(true);
            rangedTarget.setItemSlot(
                    EquipmentSlot.MAINHAND,
                    ItemStack.EMPTY
            );
            rangedTarget.setPos(rangedMinecart.position());
            helper.assertTrue(
                    level.addFreshEntity(rangedTarget)
                        && rangedTarget.startRiding(
                                rangedMinecart
                        ),
                    "GameTest could not mount the target in its vanilla cart"
            );
            rangedTargetInitialHealth = rangedTarget.getHealth();
            rangedTargetStart = rangedTarget.position();
            final var player = player();
            player.getInventory().clearContent();
            player.getInventory().setItem(
                0,
                new ItemStack(Items.BOW)
            );
            player.getInventory().setItem(
                1,
                new ItemStack(Items.ARROW, 8)
            );
            player.getInventory().setSelectedSlot(0);
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(player, rangedTarget.getEyePosition());
            enter(Stage.FINDING_RANGED_TARGET);
        }

        private void tryStartRangedAttack() {
            face(player(), rangedTarget.getEyePosition());
            final BrainObservation observation = freshObservation();
            final JsonObject entity = findEntity(
                observation,
                "minecraft:pillager"
            );
            if (entity == null) {
                awaitTarget("pillager ranged target");
                return;
            }
            startSkill(
                "shoot_observed_entity",
                List.of(
                    argument(
                        "sampleSequence",
                        Long.toString(sampleSequence(observation))
                    ),
                    argument(
                        "observationId",
                        entity.get("observationId").getAsString()
                    ),
                    argument("hand", "main_hand"),
                    argument("shots", "1")
                ),
                observation
            );
            enter(Stage.SHOOTING_RANGED_TARGET);
        }

        private void tickRangedAttack() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                return;
            }
            helper.assertTrue(
                player().getMainHandItem().is(Items.BOW)
                    && player().getInventory().countItem(Items.ARROW)
                        == 7,
                "shoot_observed_entity did not consume one vanilla arrow"
            );
            enter(Stage.VERIFYING_RANGED_HIT);
        }

        private void verifyRangedHit() {
            if (!rangedTarget.isAlive()
                    || rangedTarget.getHealth()
                        < rangedTargetInitialHealth) {
                helper.assertTrue(
                    horizontalDistance(
                        rangedTarget.position(),
                        rangedTargetStart
                    ) >= 1.0,
                    "Ranged combat passed against a target that never "
                        + "moved"
                );
                prepareEndCrystalCombat();
                return;
            }
            if ((helper.getTick() - stageStartedAt) % 5 == 0) {
                final var body = player();
                final List<String> arrows = body.level()
                    .getEntitiesOfClass(
                        AbstractArrow.class,
                        body.getBoundingBox().inflate(64.0)
                    )
                    .stream()
                    .map(arrow ->
                        "position=" + arrow.position()
                            + ",velocity=" + arrow.getDeltaMovement()
                    )
                    .toList();
                MinecraftAiCompanion.LOGGER.warn(
                    "Ranged hit diagnostic tick={} player={} yaw={} "
                        + "pitch={} target={} targetVelocity={} cart={} "
                        + "cartVelocity={} targetHealth={} arrows={}",
                    helper.getTick(),
                    body.position(),
                    body.getYRot(),
                    body.getXRot(),
                    rangedTarget.position(),
                    rangedTarget.getDeltaMovement(),
                    rangedMinecart.position(),
                    rangedMinecart.getDeltaMovement(),
                    rangedTarget.getHealth(),
                    arrows
                );
            }
            helper.assertTrue(
                helper.getTick() - stageStartedAt <= 40,
                "Vanilla ranged projectile did not hit the observed target"
            );
        }

        private void prepareEndCrystalCombat() {
            rangedTarget.discard();
            final var level = helper.getLevel();
            for (int x = -3; x <= 3; x++) {
                /*
                 * Keep the bow station outside the production crystal
                 * stand-off radius.  The former z=-2 edge put the station
                 * roughly eight blocks from the crystal and correctly caused
                 * ShootObservedEntitySkill to reject it as unsafe.
                 */
                for (int z = -5; z <= 10; z++) {
                    level.setBlockAndUpdate(
                            origin.offset(x, -1, z),
                            Blocks.OBSIDIAN.defaultBlockState()
                    );
                    for (int y = 0; y <= 8; y++) {
                        level.setBlockAndUpdate(
                                origin.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            for (int x = -1; x <= 1; x++) {
                for (int y = 3; y <= 6; y++) {
                    level.setBlockAndUpdate(
                            origin.offset(x, y, 5),
                            Blocks.IRON_BARS.defaultBlockState()
                    );
                }
            }
            crystalCageBars = List.of(
                    origin.offset(0, 6, 5),
                    origin.offset(0, 5, 5),
                    origin.offset(0, 4, 5),
                    origin.offset(0, 3, 5)
            );
            crystalCageBarIndex = 0;
            crystalTowerBase = origin.offset(0, 0, 2);
            crystalLanding = crystalTowerBase.north(2);
            crystalShotPosition = origin.offset(1, 0, -5);
            endCrystal = EntityTypes.END_CRYSTAL.create(
                    level,
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                    endCrystal != null,
                    "GameTest could not create the elevated End crystal"
            );
            endCrystal.setPos(
                    origin.getX() + 0.5,
                    origin.getY() + 5.0,
                    origin.getZ() + 8.5
            );
            helper.assertTrue(
                    level.addFreshEntity(endCrystal),
                    "GameTest could not add the elevated End crystal"
            );
            final var player = player();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getInventory().clearContent();
            player.getInventory().setItem(
                0,
                new ItemStack(Items.COBBLESTONE, 7)
            );
            player.getInventory().setItem(
                1,
                new ItemStack(Items.DIAMOND_PICKAXE)
            );
            player.getInventory().setItem(
                2,
                new ItemStack(Items.WATER_BUCKET)
            );
            player.getInventory().setItem(
                3,
                new ItemStack(Items.BOW)
            );
            player.getInventory().setItem(
                4,
                new ItemStack(Items.ARROW, 8)
            );
            player.getInventory().setSelectedSlot(0);
            player.teleportTo(
                crystalTowerBase.getX() + 0.5,
                crystalTowerBase.getY(),
                crystalTowerBase.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(player, endCrystal.getEyePosition());
            final BrainObservation observation = freshObservation();
            final JsonObject crystal = findEntity(
                    observation,
                    "minecraft:end_crystal"
            );
            helper.assertTrue(
                    crystal != null
                        && crystal.getAsJsonObject("properties")
                            .get("interactionLineClear")
                            .getAsString()
                            .equals("false"),
                    "Elevated caged crystal was not fairly visible and blocked"
            );
            startSkill(
                    BridgeSkills.TOWER_UP,
                    List.of(
                            argument("dimension", OVERWORLD),
                            argument(
                                    "targetY",
                                    decimal(origin.getY() + 5.0)
                            ),
                            argument("arrivalTolerance", "0.15"),
                            argument("maxBlocks", "5")
                    ),
                    observation
            );
            enter(Stage.TOWERING_TO_CRYSTAL_CAGE);
        }

        private void tickCrystalCageTower() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt <= 720,
                        "AI did not pillar-jump to the elevated crystal cage"
                );
                return;
            }
            for (int y = 0; y < 5; y++) {
                helper.assertTrue(
                        helper.getLevel()
                            .getBlockState(
                                crystalTowerBase.above(y)
                            )
                            .is(Blocks.COBBLESTONE),
                        "Elevated cage route missed a real pillar block"
                );
            }
            final BrainObservation observation = freshObservation();
            startSkill(
                    InventorySkills.EQUIP_ITEM,
                    List.of(
                            argument(
                                    "itemId",
                                    "minecraft:diamond_pickaxe"
                            ),
                            argument("slot", "mainhand")
                    ),
                    observation
            );
            enter(Stage.EQUIPPING_CRYSTAL_PICKAXE);
        }

        private void tickEquipCrystalPickaxe() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                return;
            }
            helper.assertTrue(
                    player().getMainHandItem()
                        .is(Items.DIAMOND_PICKAXE),
                    "AI did not equip a pickaxe at the elevated cage"
            );
            face(
                    player(),
                    Vec3.atCenterOf(
                            crystalCageBars.get(
                                    crystalCageBarIndex
                            )
                    )
            );
            enter(Stage.FINDING_CRYSTAL_CAGE_BAR);
        }

        private void tryStartCrystalCageBreak() {
            final BlockPos cageBar =
                    crystalCageBars.get(crystalCageBarIndex);
            face(player(), Vec3.atCenterOf(cageBar));
            final BrainObservation observation = freshObservation();
            final JsonObject bar = findBlockAt(
                observation,
                "minecraft:iron_bars",
                cageBar
            );
            if (bar == null) {
                awaitTarget("visible crystal-cage bar");
                return;
            }
            startSkill(
                FairInteractionSkills.BREAK_BLOCK,
                blockArguments(observation, bar, false),
                observation
            );
            enter(Stage.BREAKING_CRYSTAL_CAGE_BAR);
        }

        private void tickCrystalCageBreak() {
            freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 140,
                    "break_block did not open the crystal cage"
                );
                return;
            }
            final BlockPos cageBar =
                    crystalCageBars.get(crystalCageBarIndex);
            helper.assertTrue(
                helper.getLevel().getBlockState(cageBar)
                    .isAir(),
                "crystal cage bar remained after verified mining"
            );
            helper.assertTrue(
                player().getMainHandItem()
                    .is(Items.DIAMOND_PICKAXE)
                    && player().getMainHandItem()
                        .getDamageValue()
                        == crystalCageBarIndex + 1,
                "cage mining did not apply vanilla tool durability"
            );
            crystalCageBarIndex++;
            if (crystalCageBarIndex
                    < crystalCageBars.size()) {
                face(
                        player(),
                        Vec3.atCenterOf(
                                crystalCageBars.get(
                                        crystalCageBarIndex
                                )
                        )
                );
                enter(Stage.FINDING_CRYSTAL_CAGE_BAR);
                return;
            }
            prepareCrystalCageDescent();
        }

        private void prepareCrystalCageDescent() {
            face(
                    player(),
                    Vec3.atLowerCornerOf(crystalLanding)
                        .add(0.5, 0.0, 0.001)
            );
            final BrainObservation observation = freshObservation();
            startSkill(
                    BridgeSkills.WATER_CLUTCH_DESCEND,
                    List.of(
                            argument("dimension", OVERWORLD),
                            argument(
                                    "x",
                                    decimal(
                                        crystalLanding.getX()
                                                + 0.5
                                    )
                            ),
                            argument(
                                    "y",
                                    decimal(crystalLanding.getY())
                            ),
                            argument(
                                    "z",
                                    decimal(
                                        crystalLanding.getZ()
                                                + 0.71
                                    )
                            ),
                            argument("arrivalRadius", "0.65"),
                            argument("maximumDropBlocks", "6")
                    ),
                    observation
            );
            enter(Stage.DESCENDING_FROM_CRYSTAL_CAGE);
        }

        private void tickCrystalCageDescent() {
            freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt <= 280,
                        "AI did not water-clutch down from the crystal cage"
                );
                return;
            }
            helper.assertTrue(
                    helper.getLevel()
                        .getBlockState(crystalLanding)
                        .is(Blocks.WATER)
                        && player().getHealth()
                            == player().getMaxHealth(),
                    "Elevated crystal descent was not damage-free"
            );
            final BrainObservation observation = freshObservation();
            startSkill(
                    TravelSkills.TRAVEL_TO,
                    List.of(
                            argument("dimension", OVERWORLD),
                            argument(
                                    "x",
                                    decimal(
                                            crystalShotPosition.getX()
                                                + 0.5
                                    )
                            ),
                            argument(
                                    "y",
                                    decimal(
                                            crystalShotPosition.getY()
                                    )
                            ),
                            argument(
                                    "z",
                                    decimal(
                                            crystalShotPosition.getZ()
                                                + 0.5
                                    )
                            ),
                            argument("arrivalRadius", "0.65")
                    ),
                    observation
            );
            enter(Stage.MOVING_TO_CRYSTAL_SHOT);
        }

        private void tickMoveToCrystalShot() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt <= 240,
                        "AI did not walk to its elevated crystal shot"
                );
                return;
            }
            final BrainObservation observation = freshObservation();
            startSkill(
                    InventorySkills.EQUIP_ITEM,
                    List.of(
                            argument("itemId", "minecraft:bow"),
                            argument("slot", "mainhand")
                    ),
                    observation
            );
            enter(Stage.EQUIPPING_CRYSTAL_BOW);
        }

        private void tickEquipCrystalBow() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                return;
            }
            helper.assertTrue(
                player().getMainHandItem().is(Items.BOW),
                "equip_item did not put the bow in the main hand"
            );
            face(player(), endCrystal.getEyePosition());
            enter(Stage.FINDING_END_CRYSTAL);
        }

        private void tryStartEndCrystalAttack() {
            face(player(), endCrystal.getEyePosition());
            final BrainObservation observation = freshObservation();
            final JsonObject entity = findEntity(
                observation,
                "minecraft:end_crystal"
            );
            if (entity == null) {
                awaitTarget("End crystal ranged target");
                return;
            }
            helper.assertTrue(
                entity.getAsJsonObject("properties")
                    .get("interactionLineClear")
                    .getAsString()
                    .equals("true"),
                "Opened crystal cage still blocked the shot line"
            );
            startSkill(
                "shoot_observed_entity",
                List.of(
                    argument(
                        "sampleSequence",
                        Long.toString(sampleSequence(observation))
                    ),
                    argument(
                        "observationId",
                        entity.get("observationId").getAsString()
                    ),
                    argument("hand", "main_hand"),
                    argument("shots", "1")
                ),
                observation
            );
            enter(Stage.SHOOTING_END_CRYSTAL);
        }

        private void tickEndCrystalAttack() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                return;
            }
            helper.assertTrue(
                player().getInventory().countItem(Items.ARROW) == 7,
                "End crystal shot did not consume one vanilla arrow"
            );
            enter(Stage.VERIFYING_END_CRYSTAL);
        }

        private void verifyEndCrystalDestroyed() {
            if (!endCrystal.isAlive() || endCrystal.isRemoved()) {
                prepareStrongholdEyeTrace();
                return;
            }
            helper.assertTrue(
                helper.getTick() - stageStartedAt <= 60,
                "Vanilla ranged projectile did not destroy the End crystal"
            );
        }

        private void prepareStrongholdEyeTrace() {
            final var level = helper.getLevel();
            /*
             * Mojang's dedicated GameTestServer deliberately creates a flat
             * world with WorldOptions.generateStructures=false. The flat
             * generator still owns the normal compatible structure sets, so
             * temporarily enable the vanilla locate path before invoking the
             * production item-use skill. This fixture toggle is excluded
             * from release code with EmbodimentGameTests; neither the skill
             * nor the production perception boundary receives structure
             * lookup access.
             */
            setGenerateStructures(true);
            final BlockPos generatedStronghold =
                    level.findNearestMapStructure(
                        StructureTags.EYE_OF_ENDER_LOCATED,
                        BlockPos.ZERO,
                        256,
                        false
                    );
            helper.assertTrue(
                generatedStronghold != null,
                "GameTest world has no generated stronghold available "
                    + "for a vanilla Eye of Ender trace"
            );
            strongholdTraceTarget = generatedStronghold.immutable();
            final BlockPos traceOrigin = new BlockPos(
                generatedStronghold.getX() - 96,
                origin.getY(),
                generatedStronghold.getZ()
            );
            for (int x = -6; x <= 6; x++) {
                for (int z = -6; z <= 6; z++) {
                    level.setBlockAndUpdate(
                        traceOrigin.offset(x, -1, z),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 0; y <= 8; y++) {
                        level.setBlockAndUpdate(
                            traceOrigin.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            final var player = player();
            player.getInventory().clearContent();
            player.setItemInHand(
                InteractionHand.MAIN_HAND,
                new ItemStack(Items.ENDER_EYE, 2)
            );
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.teleportTo(
                traceOrigin.getX() + 0.5,
                traceOrigin.getY(),
                traceOrigin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(
                player,
                new Vec3(
                    generatedStronghold.getX() + 0.5,
                    player.getEyeY() + 8.0,
                    generatedStronghold.getZ() + 0.5
                )
            );
            enter(Stage.WAITING_FOR_STRONGHOLD_TRACE_SAFETY);
        }

        private void prepareFocusedStrongholdTriangulation() {
            final var level = helper.getLevel();
            setGenerateStructures(true);
            final BlockPos courseCenter = helper.absolutePos(
                new BlockPos(300, 8, 300)
            );
            final BlockPos generatedStronghold =
                    level.findNearestMapStructure(
                        StructureTags.EYE_OF_ENDER_LOCATED,
                        courseCenter,
                        256,
                        false
                    );
            helper.assertTrue(
                generatedStronghold != null,
                "GameTest world has no generated stronghold for the "
                    + "compound triangulation gate"
            );
            strongholdTraceTarget = generatedStronghold.immutable();
            firstEyeThrowPosition = new Vec3(
                courseCenter.getX() + 0.5,
                courseCenter.getY(),
                courseCenter.getZ() + 0.5
            );
            final double towardX =
                strongholdTraceTarget.getX() + 0.5
                    - firstEyeThrowPosition.x();
            final double towardZ =
                strongholdTraceTarget.getZ() + 0.5
                    - firstEyeThrowPosition.z();
            final double length = Math.hypot(towardX, towardZ);
            helper.assertTrue(
                length > 32.0,
                "Generated stronghold is too close for a two-ray gate"
            );
            final double directionX = towardX / length;
            final double directionZ = towardZ / length;
            final double baselineX = -directionZ;
            final double baselineZ = directionX;
            secondEyeThrowTarget = firstEyeThrowPosition.add(
                baselineX
                    * dev.mcai.companion.skills.stronghold
                        .TriangulateStrongholdSearchAreaSkill
                        .DEFAULT_BASELINE_DISTANCE,
                0.0,
                baselineZ
                    * dev.mcai.companion.skills.stronghold
                        .TriangulateStrongholdSearchAreaSkill
                        .DEFAULT_BASELINE_DISTANCE
            );

            /*
             * Fixture-only terrain follows the expected perpendicular
             * corridor. The production skill receives none of these
             * coordinates: its destination is derived again from the Eye
             * trajectory published through fair first-person perception.
             */
            /*
             * Rasterize by block centre instead of rotating integer sample
             * points and flooring them. The latter can map two samples onto
             * one block and leave a one-block support hole in an otherwise
             * straight corridor; fail-closed A* correctly refuses that hole.
             */
            final int corridorRadius = 280;
            final int minimumX = (int) Math.floor(
                firstEyeThrowPosition.x()
            ) - corridorRadius;
            final int maximumX = (int) Math.ceil(
                firstEyeThrowPosition.x()
            ) + corridorRadius;
            final int minimumZ = (int) Math.floor(
                firstEyeThrowPosition.z()
            ) - corridorRadius;
            final int maximumZ = (int) Math.ceil(
                firstEyeThrowPosition.z()
            ) + corridorRadius;
            for (int x = minimumX; x <= maximumX; x++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    final double deltaX =
                            x + 0.5 - firstEyeThrowPosition.x();
                    final double deltaZ =
                            z + 0.5 - firstEyeThrowPosition.z();
                    final double forward =
                            deltaX * baselineX + deltaZ * baselineZ;
                    final double lateral =
                            deltaX * directionX + deltaZ * directionZ;
                    if (forward < -6.5
                            || forward > 264.5
                            || Math.abs(lateral) > 9.5) {
                        continue;
                    }
                    final BlockPos floor = new BlockPos(
                        x,
                        courseCenter.getY() - 1,
                        z
                    );
                    level.setBlockAndUpdate(
                        floor,
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 0; y <= 6; y++) {
                        level.setBlockAndUpdate(
                            floor.above(y + 1),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }

            final var player = player();
            player.getInventory().clearContent();
            player.setItemInHand(
                InteractionHand.MAIN_HAND,
                new ItemStack(Items.ENDER_EYE, 14)
            );
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.teleportTo(
                firstEyeThrowPosition.x(),
                firstEyeThrowPosition.y(),
                firstEyeThrowPosition.z()
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(
                player,
                new Vec3(
                    strongholdTraceTarget.getX() + 0.5,
                    player.getEyeY() + 8.0,
                    strongholdTraceTarget.getZ() + 0.5
                )
            );
            enter(Stage.WAITING_FOR_STRONGHOLD_COMPOUND);
        }

        /**
         * Supplies the measured-ray handoff that the preceding physical
         * triangulation gate already proves, then verifies this production
         * compound without exposing fixture coordinates to it. The only
         * stronghold evidence available to the skill remains a block that
         * eventually enters the companion's own first-person semantic frame.
         */
        private void prepareFocusedStrongholdReach() {
            final var level = helper.getLevel();
            strongholdReachSearchFeet = origin.offset(96, 0, 0);

            /*
             * A flat, ordinary walking approach followed by a solid search
             * volume. Fixture construction is test-only; the production
             * skill receives neither these blocks nor their coordinates.
             */
            for (int x = -14; x <= 14; x++) {
                for (int z = -14; z <= 14; z++) {
                    for (int y = -18; y <= -1; y++) {
                        level.setBlockAndUpdate(
                            strongholdReachSearchFeet.offset(x, y, z),
                            Blocks.STONE.defaultBlockState()
                        );
                    }
                }
            }
            for (int x = -6; x <= 102; x++) {
                for (int z = -6; z <= 6; z++) {
                    final BlockPos floor = origin.offset(x, -1, z);
                    level.setBlockAndUpdate(
                        floor,
                        Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 0; y <= 6; y++) {
                        level.setBlockAndUpdate(
                            origin.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }

            /*
             * Model a buried fragment of an actual stronghold wall rather
             * than one isolated marker block. The Eye intersection has an
             * intentional uncertainty radius, so the production skill stops
             * several blocks short of its centre before searching. A wall
             * through that search volume is both representative and
             * independent of the exact legal stopping coordinate. It remains
             * fully occluded by ordinary stone from the surface and the
             * production skill receives none of these fixture coordinates.
             */
            final int strongholdRingRadius = 8;
            for (int offset = -strongholdRingRadius;
                    offset <= strongholdRingRadius; offset++) {
                for (int y = -10; y <= -3; y++) {
                    level.setBlockAndUpdate(
                        strongholdReachSearchFeet.offset(
                            strongholdRingRadius,
                            y,
                            offset
                        ),
                        Blocks.STONE_BRICKS.defaultBlockState()
                    );
                    level.setBlockAndUpdate(
                        strongholdReachSearchFeet.offset(
                            -strongholdRingRadius,
                            y,
                            offset
                        ),
                        Blocks.STONE_BRICKS.defaultBlockState()
                    );
                    level.setBlockAndUpdate(
                        strongholdReachSearchFeet.offset(
                            offset,
                            y,
                            strongholdRingRadius
                        ),
                        Blocks.STONE_BRICKS.defaultBlockState()
                    );
                    level.setBlockAndUpdate(
                        strongholdReachSearchFeet.offset(
                            offset,
                            y,
                            -strongholdRingRadius
                        ),
                        Blocks.STONE_BRICKS.defaultBlockState()
                    );
                }
            }
            /*
             * The approach terminates inside this buried ring. Its first
             * descending leg passes two blocks beneath the side wall, so the
             * next leg is rejected by the global safe-Y bound. The room is
             * at the same vertical offsets as the continuous portal maze:
             * the companion must stop its outward ascending probe on the
             * preserved wall, trial an entry, retreat, climb along the wall,
             * and retry until it discovers the supported room layer.
             */
            for (int x = strongholdRingRadius + 1;
                    x <= strongholdRingRadius + 6; x++) {
                for (int z = -7; z <= 7; z++) {
                    level.setBlockAndUpdate(
                        strongholdReachSearchFeet.offset(x, -5, z),
                        Blocks.STONE_BRICKS.defaultBlockState()
                    );
                    level.setBlockAndUpdate(
                        strongholdReachSearchFeet.offset(x, -4, z),
                        Blocks.AIR.defaultBlockState()
                    );
                    level.setBlockAndUpdate(
                        strongholdReachSearchFeet.offset(x, -3, z),
                        Blocks.AIR.defaultBlockState()
                    );
                }
            }
            strongholdReachEvidence =
                strongholdReachSearchFeet.offset(
                    strongholdRingRadius,
                    -4,
                    3
                );

            final long goalRevision =
                runtime.goals().snapshot().revision();
            final double targetX =
                strongholdReachSearchFeet.getX() + 0.5;
            final double targetZ =
                strongholdReachSearchFeet.getZ() + 0.5;
            final double traceY = strongholdReachSearchFeet.getY() + 1.5;
            runtime.eyeTraceResults().clear();
            runtime.eyeTraceResults().publish(strongholdFixtureTrace(
                goalRevision,
                new PerceptionVec3(targetX - 80.0, traceY, targetZ),
                1.0,
                0.0,
                10L
            ));
            runtime.eyeTraceResults().publish(strongholdFixtureTrace(
                goalRevision,
                new PerceptionVec3(targetX, traceY, targetZ - 80.0),
                0.0,
                1.0,
                20L
            ));

            final var player = player();
            player.getInventory().clearContent();
            player.getInventory().setItem(
                0,
                new ItemStack(Items.IRON_PICKAXE)
            );
            player.getInventory().setItem(
                1,
                new ItemStack(Items.TORCH, 32)
            );
            player.getInventory().setSelectedSlot(0);
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 0.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            face(
                player,
                new Vec3(
                    targetX,
                    player.getEyeY(),
                    targetZ
                )
            );
            enter(Stage.WAITING_FOR_STRONGHOLD_REACH);
        }

        private EyeTraceSnapshot strongholdFixtureTrace(
                final long goalRevision,
                final PerceptionVec3 throwOrigin,
                final double directionX,
                final double directionZ,
                final long observationRevision
        ) {
            return new EyeTraceSnapshot(
                goalRevision,
                DimensionRef.OVERWORLD,
                throwOrigin,
                Integer.toUnsignedLong(runtime.server().getTickCount()),
                observationRevision,
                observationRevision + 1L,
                List.of(
                    new EyeTraceSnapshot.Sample(
                        observationRevision,
                        throwOrigin.add(new PerceptionVec3(
                            directionX,
                            1.0,
                            directionZ
                        ))
                    ),
                    new EyeTraceSnapshot.Sample(
                        observationRevision + 1L,
                        throwOrigin.add(new PerceptionVec3(
                            directionX * 6.0,
                            2.0,
                            directionZ * 6.0
                        ))
                    )
                ),
                directionX,
                directionZ,
                Math.toDegrees(Math.atan2(-directionX, directionZ)),
                5.0
            );
        }

        private void tryStartStrongholdReach() {
            final var body = player();
            if (!body.onGround()) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 40,
                    "Stronghold reach body did not settle on its course"
                );
                return;
            }
            final BrainObservation observation = freshObservation();
            helper.assertTrue(
                findBlock(observation, "minecraft:stone_bricks") == null,
                "Buried stronghold evidence was visible before excavation"
            );
            final JsonObject trusted = JsonParser.parseString(
                observation.trustedRuntimeJson()
            ).getAsJsonObject();
            final JsonObject traceData = trusted.getAsJsonObject(
                "recentFairEyeTraceData"
            );
            helper.assertTrue(
                traceData != null
                    && traceData.getAsJsonArray("traces").size() == 2
                    && traceData.has("estimatedIntersection"),
                "Stronghold reach did not receive a goal-scoped measured "
                    + "Eye intersection"
            );
            strongholdReachStart = body.position();
            strongholdReachPickaxeDamage =
                inventoryDamage(Items.IRON_PICKAXE);
            strongholdReachTorchCount =
                body.getInventory().countItem(Items.TORCH);
            startSkill(
                StrongholdSkills.REACH_OBSERVED_STRONGHOLD,
                List.of(),
                observation
            );
            enter(Stage.REACHING_STRONGHOLD);
        }

        private void tickStrongholdReach() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                if ((helper.getTick() - stageStartedAt) % 100 == 0) {
                    MinecraftAiCompanion.LOGGER.info(
                        "Stronghold reach diagnostic tick={} position={} "
                            + "search={} evidence={} checkpoint={} "
                            + "pickaxeDamage={} torches={} supervisor={}",
                        helper.getTick(),
                        player().position(),
                        strongholdReachSearchFeet,
                        strongholdReachEvidence,
                        snapshot.checkpointSequence(),
                        inventoryDamage(Items.IRON_PICKAXE),
                        player().getInventory().countItem(Items.TORCH),
                        snapshot
                    );
                }
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 9_000,
                    "Stronghold reach exceeded its physical window"
                );
                return;
            }

            final BrainObservation observation = freshObservation();
            helper.assertTrue(
                horizontalDistance(
                    player().position(),
                    strongholdReachStart
                ) >= 80.0,
                "Stronghold reach did not physically traverse the "
                    + "approach corridor"
            );
            helper.assertTrue(
                player().getY() <= strongholdReachStart.y() - 2.0,
                "Stronghold reach completed without descending underground"
            );
            helper.assertTrue(
                player().getX()
                    > strongholdReachSearchFeet.getX() + 0.5,
                "Stronghold reach stopped at masonry instead of opening "
                    + "and crossing a legal wall entry"
            );
            helper.assertTrue(
                Math.abs(
                    player().getY()
                        - (strongholdReachSearchFeet.getY() - 4.0)
                ) <= 0.25,
                "Stronghold reach crossed into the room's floor layer "
                    + "instead of its supported feet layer: body="
                    + player().position()
            );
            helper.assertTrue(
                helper.getLevel().getBlockState(
                    strongholdReachEvidence
                ).is(Blocks.STONE_BRICKS),
                "Stronghold evidence was mined instead of preserved on sight"
            );
            helper.assertTrue(
                inventoryDamage(Items.IRON_PICKAXE)
                    > strongholdReachPickaxeDamage,
                "Stronghold reach did not consume ordinary pickaxe durability"
            );
            helper.assertTrue(
                player().getInventory().countItem(Items.TORCH)
                    < strongholdReachTorchCount,
                "Stronghold reach did not place an owned torch"
            );
            helper.assertTrue(
                findBlock(observation, "minecraft:stone_bricks") != null,
                "Stronghold reach completed without current first-person "
                    + "stone-brick evidence"
            );
            helper.assertTrue(
                SearchObservedStrongholdPortalRoomSkill
                    .hasObservedAdjacentFrontier(
                        runtime.coreFrames().current().orElseThrow(),
                        true,
                        1,
                        0
                    ),
                "Stronghold reach completed without an immediately "
                    + "traversable first-person portal-search frontier"
            );
            helper.assertTrue(
                runtime.eyeTraceResults()
                    .snapshot(runtime.goals().snapshot().revision())
                    .flatMap(history -> history.estimatedIntersection())
                    .isPresent(),
                "Measured Eye intersection disappeared during the handoff"
            );
            completeFocused();
        }

        private int inventoryDamage(
                final net.minecraft.world.item.Item expected
        ) {
            for (int slot = 0;
                    slot < player().getInventory().getContainerSize();
                    slot++) {
                final ItemStack stack =
                    player().getInventory().getItem(slot);
                if (stack.is(expected)) {
                    return stack.getDamageValue();
                }
            }
            return -1;
        }

        private void tryStartStrongholdTriangulation() {
            final var player = player();
            if (!player.onGround()) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 40,
                    "Stronghold compound body did not settle on its course"
                );
                return;
            }
            helper.assertTrue(
                player.requestedViewDistance()
                    == runtime.server().getPlayerList()
                        .getViewDistance(),
                "Headless player retained bootstrap view distance "
                    + player.requestedViewDistance()
                    + " instead of server player distance "
                    + runtime.server().getPlayerList()
                        .getViewDistance()
            );
            face(
                player,
                new Vec3(
                    strongholdTraceTarget.getX() + 0.5,
                    player.getEyeY() + 8.0,
                    strongholdTraceTarget.getZ() + 0.5
                )
            );
            final BrainObservation observation = freshObservation();
            startSkill(
                StrongholdSkills
                    .TRIANGULATE_STRONGHOLD_SEARCH_AREA,
                List.of(),
                observation
            );
            enter(Stage.TRIANGULATING_STRONGHOLD);
        }

        private void tickStrongholdTriangulation() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                if ((helper.getTick() - stageStartedAt) % 100 == 0) {
                    final var body = player();
                    final var navigation = runtime.coreFrames().current()
                            .map(frame -> frame.navigation())
                            .orElse(null);
                    final int observedVoxels = navigation == null
                            ? 0
                            : navigation.observedVoxels().size();
                    final int targetChunkX =
                            SectionPos.blockToSectionCoord(
                                (int) Math.floor(
                                    secondEyeThrowTarget.x()
                                )
                            );
                    final int targetChunkZ =
                            SectionPos.blockToSectionCoord(
                                (int) Math.floor(
                                    secondEyeThrowTarget.z()
                                )
                            );
                    MinecraftAiCompanion.LOGGER.info(
                        "Stronghold compound diagnostic tick={} "
                            + "position={} target={} checkpoint={} "
                            + "requestedView={} bodyChunk={} "
                            + "lastSection={} trackingView={} "
                            + "targetChunkLoaded={} observedVoxels={} "
                            + "supervisor={}",
                        helper.getTick(),
                        body.position(),
                        secondEyeThrowTarget,
                        snapshot.checkpointSequence(),
                        body.requestedViewDistance(),
                        body.chunkPosition(),
                        body.getLastSectionPos(),
                        body.getChunkTrackingView(),
                        body.level().getChunkSource().hasChunk(
                            targetChunkX,
                            targetChunkZ
                        ),
                        observedVoxels,
                        snapshot
                    );
                }
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 7_000,
                    "Stronghold compound exceeded its physical window"
                );
                return;
            }
            final JsonObject trusted = JsonParser.parseString(
                freshObservation().trustedRuntimeJson()
            ).getAsJsonObject();
            final JsonObject traceData = trusted.getAsJsonObject(
                "recentFairEyeTraceData"
            );
            helper.assertTrue(
                traceData != null
                    && traceData.getAsJsonArray("traces").size() >= 2
                    && traceData.has("estimatedIntersection"),
                "Compound completed without two fair traces and an "
                    + "estimated search area"
            );
            final JsonObject first = traceData
                .getAsJsonArray("traces")
                .get(0)
                .getAsJsonObject()
                .getAsJsonObject("throwOrigin");
            final JsonObject second = traceData
                .getAsJsonArray("traces")
                .get(1)
                .getAsJsonObject()
                .getAsJsonObject("throwOrigin");
            helper.assertTrue(
                Math.hypot(
                    second.get("x").getAsDouble()
                        - first.get("x").getAsDouble(),
                    second.get("z").getAsDouble()
                        - first.get("z").getAsDouble()
                ) >= 250.0,
                "Compound did not create its baseline through physical travel"
            );
            helper.assertTrue(
                player().getInventory().countItem(Items.ENDER_EYE) == 12,
                "Two normal Eye throws did not leave the twelve-Eye "
                    + "portal reserve"
            );
            helper.assertTrue(
                horizontalDistance(
                    player().position(),
                    firstEyeThrowPosition
                ) >= 250.0,
                "Body did not physically travel the measured baseline"
            );
            completeFocused();
        }

        /**
         * The End crystal's ordinary explosion can leave the player's
         * two-second recent-damage cue active even after the fixture moves the
         * body to the eye-throw site. The production stronghold skill is
         * correct to refuse a nonessential throw while that sensory danger is
         * active. Wait for the same fair cue to expire instead of clearing it
         * through a test backdoor or weakening the safety precondition.
         */
        private void tryStartStrongholdEyeTrace() {
            final BrainObservation observation = freshObservation();
            final var liveDangers = runtime.coreFrames().current()
                    .map(frame -> frame.dangerSignals())
                    .orElse(List.of());
            if (!liveDangers.isEmpty()
                    || observation.skillContext().riskScore() > 0.10) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt
                        <= RECENT_DAMAGE_SETTLE_MAX_TICKS,
                    "Stronghold trace remained unsafe after the End crystal "
                        + "encounter: " + liveDangers
                );
                return;
            }
            firstEyeThrowPosition = player().position();
            startSkill(
                StrongholdSkills.TRACE_STRONGHOLD_EYE,
                List.of(
                    argument("dimension", OVERWORLD),
                    argument(
                        "sampleSequence",
                        Long.toString(sampleSequence(observation))
                    ),
                    argument("hand", "main_hand")
                ),
                observation
            );
            enter(Stage.TRACING_STRONGHOLD_EYE);
        }

        private void tickStrongholdEyeTrace() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 140,
                    "trace_stronghold_eye exceeded its window"
                );
                return;
            }
            helper.assertTrue(
                player().getInventory().countItem(Items.ENDER_EYE)
                    == 1,
                "stronghold trace did not consume exactly one normally "
                    + "owned Eye of Ender"
            );
            final JsonObject trusted = JsonParser.parseString(
                freshObservation().trustedRuntimeJson()
            ).getAsJsonObject();
            helper.assertTrue(
                trusted.has("recentFairEyeTraceData")
                    && trusted
                        .getAsJsonObject("recentFairEyeTraceData")
                        .getAsJsonArray("traces")
                        .size() >= 1
                    && trusted
                        .getAsJsonObject("recentFairEyeTraceData")
                        .getAsJsonArray("traces")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("observedSamples")
                        .size() >= 2,
                "stronghold trace did not publish a visible trajectory"
            );
            prepareSecondEyeTravel(
                    trusted.getAsJsonObject(
                            "recentFairEyeTraceData"
                    )
            );
        }

        private void prepareSecondEyeTravel(
                final JsonObject traceData
        ) {
            final JsonObject first = traceData
                    .getAsJsonArray("traces")
                    .get(0)
                    .getAsJsonObject();
            final double directionX =
                    first.get("directionX").getAsDouble();
            final double directionZ =
                    first.get("directionZ").getAsDouble();
            final double perpendicularX = -directionZ;
            final double perpendicularZ = directionX;
            /*
             * Keep the required baseline above 60 blocks while avoiding an
             * endpoint within a few hundredths of a negative-coordinate
             * chunk boundary. A projectile crossing that synthetic far-world
             * GameTest boundary on its first tick can be parked by the test
             * server's asynchronous entity-section transition, obscuring the
             * first-person trace behavior this scenario is meant to verify.
             */
            secondEyeThrowTarget = firstEyeThrowPosition.add(
                    perpendicularX * 68.0,
                    0.0,
                    perpendicularZ * 68.0
            );
            final int minimumX = (int) Math.floor(Math.min(
                    firstEyeThrowPosition.x(),
                    secondEyeThrowTarget.x()
            )) - 3;
            final int maximumX = (int) Math.ceil(Math.max(
                    firstEyeThrowPosition.x(),
                    secondEyeThrowTarget.x()
            )) + 3;
            final int minimumZ = (int) Math.floor(Math.min(
                    firstEyeThrowPosition.z(),
                    secondEyeThrowTarget.z()
            )) - 3;
            final int maximumZ = (int) Math.ceil(Math.max(
                    firstEyeThrowPosition.z(),
                    secondEyeThrowTarget.z()
            )) + 3;
            final var level = helper.getLevel();
            for (int x = minimumX; x <= maximumX; x++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    level.setBlockAndUpdate(
                            new BlockPos(x, origin.getY() - 1, z),
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 0; y <= 4; y++) {
                        level.setBlockAndUpdate(
                                new BlockPos(
                                        x,
                                        origin.getY() + y,
                                        z
                                ),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            final BrainObservation observation = freshObservation();
            startSkill(
                    TravelSkills.TRAVEL_TO,
                    List.of(
                            argument("dimension", OVERWORLD),
                            argument(
                                    "x",
                                    decimal(secondEyeThrowTarget.x())
                            ),
                            argument(
                                    "y",
                                    decimal(secondEyeThrowTarget.y())
                            ),
                            argument(
                                    "z",
                                    decimal(secondEyeThrowTarget.z())
                            ),
                            argument("arrivalRadius", "0.8")
                    ),
                    observation
            );
            enter(Stage.TRAVELLING_FOR_SECOND_EYE);
        }

        private void tickTravelForSecondEye() {
            freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt <= 1_200,
                        "AI did not physically reposition for a second Eye"
                );
                return;
            }
            helper.assertTrue(
                    horizontalDistance(
                            player().position(),
                            firstEyeThrowPosition
                    ) >= 60.0,
                    "Second Eye baseline was not created by real travel"
            );
            helper.assertTrue(
                    player().getInventory().countItem(
                            Items.ENDER_EYE
                    ) == 1,
                    "Physical Eye reposition changed the remaining Eye count"
            );
            player().setDeltaMovement(Vec3.ZERO);
            /*
             * Travel follows the perpendicular baseline, so its final yaw
             * intentionally points away from the stronghold. A player doing
             * triangulation turns back toward the expected Eye flight before
             * throwing; make that ordinary camera action explicit so the
             * first-person entity sampler has a fair chance to bind the fast
             * vanilla projectile.
             */
            face(
                player(),
                new Vec3(
                    strongholdTraceTarget.getX() + 0.5,
                    player().getEyeY() + 8.0,
                    strongholdTraceTarget.getZ() + 0.5
                )
            );
            final BrainObservation observation = freshObservation();
            startSkill(
                    StrongholdSkills.TRACE_STRONGHOLD_EYE,
                    List.of(
                            argument("dimension", OVERWORLD),
                            argument(
                                    "sampleSequence",
                                    Long.toString(
                                            sampleSequence(observation)
                                    )
                            ),
                            argument("hand", "main_hand")
                    ),
                    observation
            );
            enter(Stage.TRACING_SECOND_STRONGHOLD_EYE);
        }

        private void tickSecondStrongholdEyeTrace() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt <= 140,
                        "second trace_stronghold_eye exceeded its window"
                );
                return;
            }
            helper.assertTrue(
                    player().getInventory().countItem(
                            Items.ENDER_EYE
                    ) == 0,
                    "Second trace did not consume exactly one owned Eye"
            );
            final JsonObject trusted = JsonParser.parseString(
                    freshObservation().trustedRuntimeJson()
            ).getAsJsonObject();
            final JsonObject traceData = trusted.getAsJsonObject(
                    "recentFairEyeTraceData"
            );
            helper.assertTrue(
                    traceData != null
                        && traceData.getAsJsonArray("traces").size()
                            >= 2,
                    "Two physically separated Eye traces were not retained"
            );
            final JsonObject first = traceData
                    .getAsJsonArray("traces")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("throwOrigin");
            final JsonObject second = traceData
                    .getAsJsonArray("traces")
                    .get(1)
                    .getAsJsonObject()
                    .getAsJsonObject("throwOrigin");
            helper.assertTrue(
                    Math.hypot(
                            second.get("x").getAsDouble()
                                - first.get("x").getAsDouble(),
                            second.get("z").getAsDouble()
                                - first.get("z").getAsDouble()
                    ) >= 60.0,
                    "Retained Eye traces did not preserve the real baseline"
            );
            prepareEndPortalActivation();
        }

        private void prepareEndPortalActivation() {
            final var level = helper.getLevel();
            final BlockPos searchStart = player().blockPosition();
            endPortalCenter = searchStart.offset(9, 0, 25);
            endPortalMazeDeadEnd = searchStart.offset(0, 0, 12);
            endPortalMazeSecondTurn = searchStart.offset(9, 0, 8);
            endPortalMazeDeadEndVisited = false;
            endPortalMazeSecondTurnVisited = false;

            /*
             * Start with an opaque stone-brick volume and carve only the
             * interior cells a player could actually traverse. The route
             * first continues into a dead branch, then returns to a junction,
             * turns east, and turns south into the portal room. A ceiling and
             * full-height walls prevent the first-person ray fan from seeing
             * the frame across the untravelled world-space diagonal.
             */
            for (int x = -4; x <= 14; x++) {
                for (int z = -3; z <= 31; z++) {
                    level.setBlockAndUpdate(
                        searchStart.offset(x, -1, z),
                        Blocks.STONE_BRICKS.defaultBlockState()
                    );
                    for (int y = 0; y <= 4; y++) {
                        level.setBlockAndUpdate(
                            searchStart.offset(x, y, z),
                            Blocks.STONE_BRICKS.defaultBlockState()
                        );
                    }
                }
            }
            for (int z = 0; z <= 12; z++) {
                carveStrongholdInterior(searchStart, 0, z);
            }
            for (int x = 0; x <= 9; x++) {
                carveStrongholdInterior(searchStart, x, 8);
            }
            for (int z = 8; z <= 22; z++) {
                carveStrongholdInterior(searchStart, 9, z);
            }
            for (int x = 6; x <= 12; x++) {
                for (int z = 21; z <= 29; z++) {
                    carveStrongholdInterior(searchStart, x, z);
                }
            }
            for (int offset = -1; offset <= 1; offset++) {
                setEmptyEndPortalFrame(
                    endPortalCenter.offset(offset, 0, -2),
                    Direction.SOUTH
                );
                setEmptyEndPortalFrame(
                    endPortalCenter.offset(offset, 0, 2),
                    Direction.NORTH
                );
                setEmptyEndPortalFrame(
                    endPortalCenter.offset(-2, 0, offset),
                    Direction.EAST
                );
                setEmptyEndPortalFrame(
                    endPortalCenter.offset(2, 0, offset),
                    Direction.WEST
                );
            }
            final var player = player();
            player.getInventory().clearContent();
            player.getInventory().setItem(
                0,
                new ItemStack(Items.ENDER_EYE, 12)
            );
            player.getInventory().setSelectedSlot(0);
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.setDeltaMovement(Vec3.ZERO);
            face(
                player,
                Vec3.atCenterOf(endPortalCenter)
                    .add(0.0, 0.2, 0.0)
            );
            final BrainObservation observation = freshObservation();
            helper.assertTrue(
                findBlock(
                    observation,
                    "minecraft:end_portal_frame"
                ) == null,
                "End portal room was visible before physical exploration"
            );
            endPortalSearchStart = player.position();
            enter(Stage.SETTLING_FOR_END_PORTAL_SEARCH);
        }

        private void carveStrongholdInterior(
                final BlockPos origin,
                final int offsetX,
                final int offsetZ
        ) {
            for (int y = 0; y <= 3; y++) {
                helper.getLevel().setBlockAndUpdate(
                        origin.offset(offsetX, y, offsetZ),
                        Blocks.AIR.defaultBlockState()
                );
            }
        }

        private void tickEndPortalSearchSettlement() {
            final var body = player();
            if (!body.onGround()) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt <= 80,
                        "End portal search body did not settle: position="
                            + body.position()
                            + " velocity="
                            + body.getDeltaMovement()
                            + " onGround="
                            + body.onGround()
                            + " noGravity="
                            + body.isNoGravity()
                            + " feet="
                            + body.level().getBlockState(
                                body.blockPosition()
                            )
                            + " below="
                            + body.level().getBlockState(
                                body.blockPosition().below()
                            )
                );
                return;
            }
            face(
                body,
                Vec3.atCenterOf(endPortalCenter)
                    .add(0.0, 0.2, 0.0)
            );
            final BrainObservation observation = freshObservation();
            helper.assertTrue(
                    findBlock(
                            observation,
                            "minecraft:end_portal_frame"
                    ) == null,
                    "End portal room became visible before search start"
            );
            /*
             * The preceding controlled End-crystal encounter leaves the
             * same short-lived recent-damage signal that production uses to
             * pause nonessential exploration. Let that fair first-person
             * cue expire naturally instead of weakening the stronghold
             * skill's danger precondition or clearing it through a fixture
             * backdoor.
             */
            final var liveDangers = runtime.coreFrames().current()
                    .map(frame -> frame.dangerSignals())
                    .orElse(List.of());
            if (!liveDangers.isEmpty()
                    || observation.skillContext().riskScore() > 0.10) {
                final long dangerWaitTicks =
                        helper.getTick() - stageStartedAt;
                if (dangerWaitTicks == 0L || dangerWaitTicks % 10L == 0L) {
                    final var currentFrame = runtime.coreFrames().current();
                    MinecraftAiCompanion.LOGGER.warn(
                            "End portal danger diagnostic tick={} wait={} "
                                + "worldGameTime={} frameGameTime={} "
                                + "health={} hurtTime={} invulnerableTime={} "
                                + "invulnerable={} position={} "
                                + "dangers={} risk={}",
                            helper.getTick(),
                            dangerWaitTicks,
                            body.level().getGameTime(),
                            currentFrame.map(frame -> frame.gameTime())
                                    .orElse(-1L),
                            body.getHealth(),
                            body.hurtTime,
                            body.invulnerableTime,
                            body.isInvulnerable(),
                            body.position(),
                            liveDangers,
                            observation.skillContext().riskScore()
                    );
                }
                helper.assertTrue(
                        dangerWaitTicks
                                <= RECENT_DAMAGE_SETTLE_MAX_TICKS,
                        "End portal search remained unsafe after the prior "
                                + "crystal encounter: " + liveDangers
                );
                return;
            }
            startSkill(
                StrongholdSkills
                        .SEARCH_OBSERVED_STRONGHOLD_PORTAL_ROOM,
                    List.of(),
                    observation
            );
            endPortalSearchStart = body.position();
            enter(Stage.EXPLORING_FOR_END_PORTAL);
        }

        private void tickExploreForEndPortal() {
            final BrainObservation observation = freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (horizontalDistance(
                    player().position(),
                    Vec3.atCenterOf(endPortalMazeDeadEnd)
            ) <= 1.35) {
                endPortalMazeDeadEndVisited = true;
            }
            if (endPortalMazeDeadEndVisited
                    && horizontalDistance(
                        player().position(),
                        Vec3.atCenterOf(endPortalMazeSecondTurn)
                    ) <= 1.35) {
                endPortalMazeSecondTurnVisited = true;
            }
            if (!completed(snapshot)) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt <= 700,
                        "AI did not physically explore for the portal room"
                );
                return;
            }
            helper.assertTrue(
                    horizontalDistance(
                            player().position(),
                            endPortalSearchStart
                    ) >= 8.0,
                    "Portal-room search completed without physical movement"
            );
            helper.assertTrue(
                    endPortalMazeDeadEndVisited,
                    "Portal-room search never entered the occluded dead branch"
            );
            helper.assertTrue(
                    endPortalMazeSecondTurnVisited,
                    "Portal-room search did not backtrack and take the "
                        + "second corridor"
            );
            /*
             * The observation sampled at the start of this tick is the exact
             * fair frame that allowed the production search skill to finish.
             * Re-sampling after the supervisor stops its child movement can
             * legitimately place a peripheral frame ray outside the next
             * finite fan. Hand the causal frame directly to the next atomic
             * skill instead of pretending the completion evidence vanished.
             */
            final BrainObservation activationObservation = observation;
            helper.assertTrue(
                    findBlock(
                            activationObservation,
                            "minecraft:end_portal_frame"
                    ) != null,
                    "Portal-room search completed before a frame was visible"
            );
            startSkill(
                    PortalBuildSkills.ACTIVATE_OBSERVED_END_PORTAL,
                    List.of(),
                    activationObservation
            );
            enter(Stage.ACTIVATING_END_PORTAL);
        }

        private void tickEndPortalActivation() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 900,
                    "activate_observed_end_portal exceeded its window"
                );
                return;
            }
            int eyedFrames = 0;
            for (int offset = -1; offset <= 1; offset++) {
                for (BlockPos position : List.of(
                        endPortalCenter.offset(offset, 0, -2),
                        endPortalCenter.offset(offset, 0, 2),
                        endPortalCenter.offset(-2, 0, offset),
                        endPortalCenter.offset(2, 0, offset)
                )) {
                    final var state =
                            helper.getLevel().getBlockState(position);
                    helper.assertTrue(
                        state.is(Blocks.END_PORTAL_FRAME)
                            && state.getValue(
                                net.minecraft.world.level.block
                                    .EndPortalFrameBlock.HAS_EYE
                            ),
                        "End portal skill left an empty frame at "
                            + position
                    );
                    eyedFrames++;
                }
            }
            int portalBlocks = 0;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    helper.assertTrue(
                        helper.getLevel().getBlockState(
                            endPortalCenter.offset(x, 0, z)
                        ).is(Blocks.END_PORTAL),
                        "End portal did not activate its 3x3 interior"
                    );
                    portalBlocks++;
                }
            }
            helper.assertTrue(
                eyedFrames == 12
                    && portalBlocks == 9
                    && player().getInventory().countItem(
                        Items.ENDER_EYE
                    ) == 0,
                "End portal activation did not consume exactly twelve eyes"
            );
            if (scope == ScenarioScope.END_PORTAL_ONLY) {
                completeFocused();
                return;
            }
            prepareBed();
        }

        private void setEmptyEndPortalFrame(
                final BlockPos position,
                final Direction facing
        ) {
            setEndPortalFrame(position, facing, false);
        }

        private void setEndPortalFrame(
                final BlockPos position,
                final Direction facing,
                final boolean hasEye
        ) {
            helper.getLevel().setBlockAndUpdate(
                position,
                Blocks.END_PORTAL_FRAME.defaultBlockState()
                    .setValue(
                        net.minecraft.world.level.block
                            .EndPortalFrameBlock.FACING,
                        facing
                    )
                    .setValue(
                        net.minecraft.world.level.block
                            .EndPortalFrameBlock.HAS_EYE,
                        hasEye
                    )
            );
        }

        private void prepareFocusedEndVictory() {
            final var level = helper.getLevel();
            endPortalCenter = origin.offset(0, 0, 8);
            for (int x = -6; x <= 6; x++) {
                for (int z = -5; z <= 13; z++) {
                    level.setBlockAndUpdate(
                            origin.offset(x, -1, z),
                            Blocks.STONE_BRICKS.defaultBlockState()
                    );
                    for (int y = 0; y <= 4; y++) {
                        level.setBlockAndUpdate(
                                origin.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            for (int offset = -1; offset <= 1; offset++) {
                setEndPortalFrame(
                        endPortalCenter.offset(offset, 0, -2),
                        Direction.SOUTH,
                        true
                );
                setEndPortalFrame(
                        endPortalCenter.offset(offset, 0, 2),
                        Direction.NORTH,
                        true
                );
                setEndPortalFrame(
                        endPortalCenter.offset(-2, 0, offset),
                        Direction.EAST,
                        true
                );
                setEndPortalFrame(
                        endPortalCenter.offset(2, 0, offset),
                        Direction.WEST,
                        true
                );
            }
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    level.setBlockAndUpdate(
                            endPortalCenter.offset(x, 0, z),
                            Blocks.END_PORTAL.defaultBlockState()
                    );
                }
            }
            final var player = player();
            player.teleportTo(
                    endPortalCenter.getX() + 0.5,
                    endPortalCenter.getY(),
                    endPortalCenter.getZ() - 3.0
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(
                    player,
                    Vec3.atCenterOf(endPortalCenter)
                        .add(0.0, -0.15, 0.0)
            );
            enter(Stage.FINDING_PORTAL);
        }

        private void prepareBed() {
            final var level = helper.getLevel();
            player().stopRiding();
            minecart.discard();
            for (int x = -1; x <= 1; x++) {
                for (int z = 2; z <= 12; z++) {
                    level.setBlockAndUpdate(
                        origin.offset(x, 0, z),
                        Blocks.AIR.defaultBlockState()
                    );
                }
            }

            final BlockPos foot = origin.offset(0, 0, 14);
            bedHead = foot.relative(Direction.SOUTH);
            final var base = Blocks.BED.red().defaultBlockState()
                .setValue(
                    HorizontalDirectionalBlock.FACING,
                    Direction.SOUTH
                );
            level.setBlockAndUpdate(
                bedHead,
                base.setValue(BedBlock.PART, BedPart.HEAD)
            );
            level.setBlockAndUpdate(
                foot,
                base.setValue(BedBlock.PART, BedPart.FOOT)
            );
            level.setBlockAndUpdate(
                foot.below(),
                Blocks.SMOOTH_STONE.defaultBlockState()
            );
            level.setBlockAndUpdate(
                bedHead.below(),
                Blocks.SMOOTH_STONE.defaultBlockState()
            );
            final var player = player();
            player.teleportTo(
                origin.getX() + 0.5,
                origin.getY(),
                origin.getZ() + 12.5
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(player, Vec3.atCenterOf(foot).add(0.0, -0.2, 0.0));
            helper.setTime(13_000L);
            setSleepPercentage(100);
            heldSleepOpen = false;
            observedRealSleep = false;
            enter(Stage.FINDING_BED);
        }

        private void tryStartSleeping() {
            face(
                player(),
                Vec3.atCenterOf(bedHead).add(0.0, -0.2, 0.0)
            );
            final BrainObservation observation = freshObservation();
            final JsonObject target = findBlock(
                observation,
                "minecraft:red_bed"
            );
            if (target == null) {
                awaitTarget("red bed");
                return;
            }
            startSkill(
                SleepSkills.SLEEP_IN_OBSERVED_BED,
                blockArguments(observation, target, false),
                observation
            );
            enter(Stage.SLEEPING);
        }

        private void tickSleeping() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(false);
            if (snapshot.state() == SkillSupervisor.State.RUNNING) {
                /*
                 * Keep the first real sleeping tick observable before
                 * restoring the normal threshold. Time is still advanced by
                 * vanilla's ordinary all-players-sleeping path.
                 */
                if (snapshot.executedTicks() == 1 && !heldSleepOpen) {
                    setSleepPercentage(101);
                    heldSleepOpen = true;
                } else if (snapshot.executedTicks() >= 2
                        && player().isSleeping()) {
                    observedRealSleep = true;
                    setSleepPercentage(100);
                }
            }
            if (!completed(snapshot)) {
                return;
            }
            helper.assertTrue(
                observedRealSleep,
                "sleep_in_observed_bed never entered real ServerPlayer sleep"
            );
            helper.assertTrue(
                !player().isSleeping()
                    && !helper.getLevel().isDarkOutside(),
                "sleep_in_observed_bed completed before a natural dawn wake"
            );
            preparePortal();
        }

        private void preparePortal() {
            setSleepPercentage(originalSleepPercentage);
            final var player = player();
            player.teleportTo(
                endPortalCenter.getX() + 0.5,
                endPortalCenter.getY(),
                endPortalCenter.getZ() - 3.0
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(
                player,
                Vec3.atCenterOf(endPortalCenter)
                    .add(0.0, -0.15, 0.0)
            );
            enter(Stage.FINDING_PORTAL);
        }

        private void tryStartPortalEntry() {
            face(
                player(),
                Vec3.atCenterOf(endPortalCenter)
                    .add(0.0, -0.15, 0.0)
            );
            final BrainObservation observation = freshObservation();
            final JsonObject target = findBlock(
                observation,
                "minecraft:end_portal"
            );
            if (target == null) {
                awaitTarget("end portal");
                return;
            }
            startSkill(
                PortalSkills.ENTER_OBSERVED_PORTAL,
                blockArguments(observation, target, true),
                observation
            );
            enter(Stage.ENTERING_PORTAL);
        }

        private void tickPortalEntry() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                return;
            }
            final var player = player();
            helper.assertTrue(
                player.level().dimension().equals(Level.END),
                "enter_observed_portal completed without real End traversal"
            );
            prepareEndApproach();
        }

        private void prepareEndApproach() {
            final var end = runtime.server().getLevel(Level.END);
            helper.assertTrue(end != null, "End level is unavailable");
            final var player = player();
            endRouteStart = player.blockPosition();
            endRouteTravelStart = player.position();
            endArena = endRouteStart.offset(0, 0, 30);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 38; z++) {
                    final boolean gap = z >= 11 && z <= 13;
                    end.setBlockAndUpdate(
                            endRouteStart.offset(x, -1, z),
                            gap
                                ? Blocks.AIR.defaultBlockState()
                                : Blocks.OBSIDIAN.defaultBlockState()
                    );
                    for (int y = 0; y <= 5; y++) {
                        end.setBlockAndUpdate(
                                endRouteStart.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                    if (gap) {
                        for (int y = -2; y >= -20; y--) {
                            end.setBlockAndUpdate(
                                    endRouteStart.offset(x, y, z),
                                    Blocks.AIR.defaultBlockState()
                            );
                        }
                    }
                }
            }
            player.getInventory().clearContent();
            player.getInventory().setItem(
                    0,
                    new ItemStack(Items.COBBLESTONE, 16)
            );
            player.getInventory().setSelectedSlot(0);
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.setDeltaMovement(Vec3.ZERO);
            face(
                    player,
                    Vec3.atCenterOf(
                            endRouteStart.offset(0, -1, 14)
                    )
            );
            final BrainObservation observation = freshObservation();
            startSkill(
                    TravelSkills.TRAVEL_TO,
                    List.of(
                            argument(
                                    "dimension",
                                    "minecraft:the_end"
                            ),
                            argument(
                                    "x",
                                    decimal(
                                            endRouteStart.getX() + 0.5
                                    )
                            ),
                            argument(
                                    "y",
                                    decimal(endRouteStart.getY())
                            ),
                            argument(
                                    "z",
                                    decimal(
                                            endRouteStart.getZ() + 10.5
                                    )
                            ),
                            argument("arrivalRadius", "0.65")
                    ),
                    observation
            );
            enter(Stage.TRAVELLING_TO_END_GAP);
        }

        private void tickTravelToEndGap() {
            freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt <= 360,
                        "AI did not walk from the End spawn to the gap"
                );
                return;
            }
            final BlockPos farSupport =
                    endRouteStart.offset(0, -1, 14);
            face(player(), Vec3.atCenterOf(farSupport));
            final BrainObservation observation = freshObservation();
            startSkill(
                    BridgeSkills.BRIDGE_TO,
                    List.of(
                            argument(
                                    "dimension",
                                    "minecraft:the_end"
                            ),
                            argument(
                                    "x",
                                    decimal(
                                            endRouteStart.getX() + 0.5
                                    )
                            ),
                            argument(
                                    "y",
                                    decimal(endRouteStart.getY())
                            ),
                            argument(
                                    "z",
                                    decimal(
                                            endRouteStart.getZ() + 14.5
                                    )
                            ),
                            argument("arrivalRadius", "0.65"),
                            argument("maxBlocks", "3")
                    ),
                    observation
            );
            enter(Stage.BRIDGING_END_GAP);
        }

        private void tickBridgeEndGap() {
            freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt <= 520,
                        "AI did not bridge the three-block End gap"
                );
                return;
            }
            for (int z = 11; z <= 13; z++) {
                helper.assertTrue(
                        player().level()
                            .getBlockState(
                                endRouteStart.offset(0, -1, z)
                            )
                            .is(Blocks.COBBLESTONE),
                        "End bridge omitted an owned cobblestone"
                );
            }
            helper.assertTrue(
                    player().getInventory().countItem(
                            Items.COBBLESTONE
                    ) == 13,
                    "End bridge did not consume exactly three blocks"
            );
            final BrainObservation observation = freshObservation();
            startSkill(
                    TravelSkills.TRAVEL_TO,
                    List.of(
                            argument(
                                    "dimension",
                                    "minecraft:the_end"
                            ),
                            argument(
                                    "x",
                                    decimal(endArena.getX() + 0.5)
                            ),
                            argument(
                                    "y",
                                    decimal(endArena.getY())
                            ),
                            argument(
                                    "z",
                                    decimal(endArena.getZ() + 0.5)
                            ),
                            argument("arrivalRadius", "0.75")
                    ),
                    observation
            );
            enter(Stage.TRAVELLING_TO_END_ARENA);
        }

        private void tickTravelToEndArena() {
            freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                        helper.getTick() - stageStartedAt <= 600,
                        "AI did not walk from its bridge to the End arena"
                );
                return;
            }
            helper.assertTrue(
                    horizontalDistance(
                            player().position(),
                            endRouteTravelStart
                    ) >= 25.0,
                    "End approach completed without material travel"
            );
            prepareDragonFight();
        }

        private void prepareDragonFight() {
            final var end = runtime.server().getLevel(Level.END);
            helper.assertTrue(end != null, "End level is unavailable");
            for (int x = -20; x <= 20; x++) {
                for (int z = -20; z <= 28; z++) {
                    end.setBlockAndUpdate(
                        endArena.offset(x, -1, z),
                        Blocks.OBSIDIAN.defaultBlockState()
                    );
                    for (int y = 0; y <= 10; y++) {
                        end.setBlockAndUpdate(
                            endArena.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            final var player = player();
            dragonFightPlayerId = player.getUUID();
            helper.assertTrue(
                    horizontalDistance(
                            player.position(),
                            Vec3.atBottomCenterOf(endArena)
                    ) <= 1.25,
                    "AI reached no valid End arena approach position"
            );
            player.setDeltaMovement(Vec3.ZERO);
            player.getInventory().clearContent();
            player.getInventory().setItem(
                0,
                new ItemStack(Items.BOW)
            );
            player.getInventory().setItem(
                1,
                new ItemStack(Items.ARROW, 99)
            );
            player.getInventory().setItem(
                2,
                new ItemStack(Items.ARROW, 29)
            );
            player.getInventory().setItem(
                3,
                new ItemStack(Items.COOKED_BEEF, 16)
            );
            player.getInventory().setItem(
                4,
                new ItemStack(Items.DIAMOND_SWORD)
            );
            player.getInventory().setItem(
                5,
                new ItemStack(Items.DIAMOND_PICKAXE)
            );
            player.getInventory().setSelectedSlot(0);
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.setItemSlot(
                EquipmentSlot.HEAD,
                new ItemStack(Items.IRON_HELMET)
            );
            player.setItemSlot(
                EquipmentSlot.CHEST,
                new ItemStack(Items.IRON_CHESTPLATE)
            );
            player.setItemSlot(
                EquipmentSlot.LEGS,
                new ItemStack(Items.IRON_LEGGINGS)
            );
            player.setItemSlot(
                EquipmentSlot.FEET,
                new ItemStack(Items.IRON_BOOTS)
            );

            fightCrystal = EntityTypes.END_CRYSTAL.create(
                end,
                EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                fightCrystal != null,
                "GameTest could not create an End crystal"
            );
            fightCrystal.setPos(
                endArena.getX() + 0.5,
                endArena.getY(),
                endArena.getZ() + 7.5
            );
            helper.assertTrue(
                end.addFreshEntity(fightCrystal),
                "GameTest could not add an End crystal"
            );
            maximumFightCrystalDistance =
                    player.position().distanceTo(
                            fightCrystal.position()
                    );
            /*
             * Use a real two-block collider between the body and the crystal.
             * The former one-block rail sat above the ray to the crystal
             * base, so the crystal could truthfully be clear and its
             * explosion could remove the rail without any mining. That
             * geometry did not prove the cage-opening contract.
             */
            fightCageBar = endArena.offset(0, 0, 3);
            end.setBlockAndUpdate(
                fightCageBar,
                Blocks.IRON_BARS.defaultBlockState()
            );
            end.setBlockAndUpdate(
                fightCageBar.above(),
                Blocks.IRON_BARS.defaultBlockState()
            );
            helper.assertTrue(
                    player.getEyePosition().distanceTo(
                        Vec3.atCenterOf(fightCageBar)
                    ) <= PHYSICAL_CAGE_BREAK_REACH,
                    "Controlled crystal cage is outside ordinary player "
                        + "block reach"
            );

            /*
             * Reuse the dragon already owned by the real End fight manager.
             * Replacing it with an unrelated entity leaves the manager free
             * to respawn or credit its saved UUID, producing two independent
             * dragons. Only create one when this fresh End genuinely has none.
             */
            final List<? extends EnderDragon> managedDragons =
                end.getEntities(
                    EntityTypes.ENDER_DRAGON,
                    existing -> true
                );
            if (managedDragons.isEmpty()) {
                fightDragon = EntityTypes.ENDER_DRAGON.create(
                    end,
                    EntitySpawnReason.COMMAND
                );
                helper.assertTrue(
                    fightDragon != null,
                    "GameTest could not create an Ender Dragon"
                );
                helper.assertTrue(
                    end.addFreshEntity(fightDragon),
                    "GameTest could not add an Ender Dragon"
                );
            } else {
                fightDragon = managedDragons.getFirst();
                managedDragons.stream()
                    .skip(1)
                    .forEach(Entity::discard);
            }
            fightDragon.setPos(
                endArena.getX() + 0.5,
                endArena.getY() + 2.0,
                /* Keep the first cage interaction observable before the
                 * semantic dragon root becomes an immediate melee threat. */
                endArena.getZ() + 12.5
            );
            fightDragon.setYRot(180.0F);
            fightDragon.setNoAi(true);
            fightDragon.setHealth(fightDragon.getMaxHealth());
            positionStaticDragonParts(fightDragon);
            helper.assertTrue(
                    end.getEntities(
                            EntityTypes.ENDER_DRAGON,
                            dragon -> dragon.isAlive()
                        ).size() == 1,
                    "Controlled dragon fixture contains multiple live dragons"
            );
            face(player, fightCrystal.getEyePosition());
            final BrainObservation observation = freshObservation();
            final JsonObject observedCrystal = findEntity(
                    observation,
                    "minecraft:end_crystal"
            );
            helper.assertTrue(
                    observedCrystal != null,
                    "Controlled End crystal was not fairly visible behind "
                        + "the physical cage; visible entities="
                        + semantic(observation)
                            .getAsJsonArray("visibleEntities")
            );
            final JsonObject observedDragon = findEntity(
                    observation,
                    "minecraft:ender_dragon"
            );
            helper.assertTrue(
                    observedDragon != null,
                    "Controlled Ender Dragon was not fairly visible from "
                        + "the initial first-person pose; visible entities="
                        + semantic(observation)
                            .getAsJsonArray("visibleEntities")
            );
            startSkill(
                    CombatSkills.FIGHT_ENDER_DRAGON,
                List.of(),
                observation
            );
            enter(Stage.FIGHTING_DRAGON);
        }

        /**
         * {@code EnderDragon#setNoAi(true)} intentionally skips vanilla's
         * multipart-position update, leaving newly constructed parts at the
         * world origin. This controlled full-health target is stationary so
         * the coordinator can deterministically prove sustained melee, but
         * its selectable parts must still occupy the same relative geometry
         * a yaw-180 dragon would produce.
         */
        private void positionStaticDragonParts(
                final EnderDragon dragon
        ) {
            final EnderDragonPart[] parts =
                    dragon.getSubEntities();
            helper.assertTrue(
                    parts.length == 8,
                    "Unexpected Ender Dragon part count "
                        + parts.length
            );
            final double x = dragon.getX();
            final double y = dragon.getY();
            final double z = dragon.getZ();
            parts[0].setPos(x, y - 1.0, z + 6.5);
            parts[1].setPos(x, y - 1.0, z + 5.5);
            parts[2].setPos(x, y, z + 0.5);
            parts[3].setPos(x, y + 1.5, z - 3.5);
            parts[4].setPos(x, y + 1.5, z - 5.5);
            parts[5].setPos(x, y + 1.5, z - 7.5);
            parts[6].setPos(x - 4.5, y + 2.0, z);
            parts[7].setPos(x + 4.5, y + 2.0, z);
        }

        private void tickDragonFight() {
            if (fightCrystal.isAlive()
                    && !fightCrystal.isRemoved()) {
                maximumFightCrystalDistance = Math.max(
                        maximumFightCrystalDistance,
                        player().position().distanceTo(
                                fightCrystal.position()
                        )
                );
            }
            freshObservation();
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                helper.assertTrue(
                    helper.getTick() - stageStartedAt
                        <= OFFLINE_DRAGON_FIGHT_WINDOW_TICKS,
                    "fight_ender_dragon exceeded its window; "
                        + dragonFightDiagnostics()
                );
                return;
            }
            helper.assertTrue(
                !fightCrystal.isAlive() || fightCrystal.isRemoved(),
                "Dragon coordinator did not clear the visible crystal"
            );
            helper.assertTrue(
                    maximumFightCrystalDistance
                        >= PHYSICAL_END_CRYSTAL_STANDOFF,
                    "Dragon coordinator fired inside the End-crystal "
                        + "explosion radius: maximum stand-off was "
                        + maximumFightCrystalDistance
            );
            helper.assertTrue(
                    player().isAlive()
                        && !player().isDeadOrDying()
                        && player().getHealth() > 0.0F,
                    "Dragon coordinator did not survive an ordinary "
                        + "iron-armour "
                        + "End-crystal explosion"
            );
            helper.assertTrue(
                player().level().getBlockState(fightCageBar).isAir(),
                "Dragon coordinator did not normally mine the reachable "
                    + "iron-bar crystal cage"
            );
            helper.assertTrue(
                fightDragon.isDeadOrDying()
                    || fightDragon.getPhaseManager()
                        .getCurrentPhase()
                        .getPhase()
                        == EnderDragonPhase.DYING
                    || fightDragon.isRemoved(),
                "Dragon coordinator completed before killing the dragon"
            );
            helper.assertTrue(
                runtime.worldData()
                    .verifiedRouteProgress(
                        runtime.goals().snapshot().revision()
                    )
                    .milestones()
                    .contains(SurvivalMilestone.DRAGON_KILLED),
                "Server death event did not credit the companion"
            );
            helper.assertTrue(
                player().getInventory().countItem(Items.ARROW) < 128,
                "Dragon coordinator did not use normal ammunition"
            );
            int swordDurabilityConsumed = 0;
            int pickaxeDurabilityConsumed = 0;
            final int arrowsConsumed = 128
                    - player().getInventory().countItem(
                            Items.ARROW
                    );
            for (int slot = 0;
                    slot < player().getInventory()
                        .getContainerSize();
                    slot++) {
                final ItemStack stack =
                        player().getInventory().getItem(slot);
                if (stack.is(Items.DIAMOND_SWORD)) {
                    swordDurabilityConsumed = Math.max(
                            swordDurabilityConsumed,
                            stack.getDamageValue()
                    );
                }
                if (stack.is(Items.DIAMOND_PICKAXE)) {
                    pickaxeDurabilityConsumed = Math.max(
                            pickaxeDurabilityConsumed,
                            stack.getDamageValue()
                    );
                }
            }
            helper.assertTrue(
                    swordDurabilityConsumed >= 20
                        || arrowsConsumed >= 4,
                    "Full-health dragon fight used neither sustained "
                        + "reachable melee nor sustained ordinary bow "
                        + "damage: sword durability="
                        + swordDurabilityConsumed
                        + ", arrows="
                        + arrowsConsumed
            );
            helper.assertTrue(
                    pickaxeDurabilityConsumed >= 1,
                    "Crystal-cage opening did not consume normal pickaxe "
                        + "durability"
            );
            /*
             * The controlled target is frozen while the production skill
             * proves a sustained full-health kill. Vanilla's DYING phase is
             * itself AI-driven: leaving no-AI enabled strands the dragon at
             * one health forever, and the emergency controller correctly
             * treats that still-live Enemy as a contact threat. Resume the
             * ordinary death phase and wait for its real 200-tick removal
             * before testing return-portal entry.
             */
            if (!fightDragon.isRemoved()) {
                fightDragon.setNoAi(false);
                runtime.coreActions().quiesceNow();
                enter(Stage.WAITING_FOR_DRAGON_DEATH);
                return;
            }
            prepareReturnPortal();
        }

        /**
         * Keep the offline dragon gate honest when it times out.  The
         * diagnostic is deliberately made from the same semantic observation
         * supplied to the skill; it is not a fallback entity scan or a world
         * mutation.  Multipart positions are included only to distinguish a
         * missing fair target from a failed actuator.
         */
        private String dragonFightDiagnostics() {
            final var body = player();
            final BrainObservation observation =
                runtime.observations().observe(runtime.goals().snapshot());
            final List<String> parts = fightDragon == null
                ? List.of()
                : java.util.Arrays.stream(fightDragon.getSubEntities())
                    .map(part -> part.name + "@" + part.position())
                    .toList();
            return "body=" + body.position()
                + ", yaw=" + body.getYRot()
                + ", pitch=" + body.getXRot()
                + ", dragonHealth="
                + (fightDragon == null
                    ? "unknown"
                    : fightDragon.getHealth())
                + ", root=" + (fightDragon == null
                    ? "none" : fightDragon.position())
                + ", parts=" + parts
                + ", visibleEntities="
                + semantic(observation).getAsJsonArray("visibleEntities")
                + ", dangerSignals="
                + semantic(observation).getAsJsonArray("dangers")
                + ", supervisor=" + runtime.skillSupervisor().snapshot()
                + ", checkpoint=" + runtime.skillSupervisor()
                    .lastCheckpointPayload().orElse("none");
        }

        private void tickDragonDeathAnimation() {
            freshObservation();
            runtime.survival().tick(false);
                runtime.coreActions().postServerTick();
            helper.assertTrue(
                    helper.getTick() - stageStartedAt <= 420,
                    "Vanilla Ender Dragon death animation did not finish"
            );
            if (!fightDragon.isRemoved()) {
                return;
            }
            helper.assertTrue(
                    !fightDragon.isAlive(),
                    "Removed Ender Dragon remained alive"
            );
            runtime.survival().reset();
            runtime.coreActions().quiesceNow();
            prepareReturnPortal();
        }

        private void prepareReturnPortal() {
            final var end = runtime.server().getLevel(Level.END);
            final var player = player();
            returnPortal = player.blockPosition().offset(0, 0, 5);
            end.setBlockAndUpdate(
                    returnPortal.below(),
                    Blocks.OBSIDIAN.defaultBlockState()
            );
            end.setBlockAndUpdate(
                returnPortal,
                Blocks.END_PORTAL.defaultBlockState()
            );
            player.setDeltaMovement(Vec3.ZERO);
            face(
                player,
                player.position().add(0.0, 0.0, -5.0)
            );
            enter(Stage.FINDING_RETURN_PORTAL);
        }

        private void tryStartReturnPortalEntry() {
            final BrainObservation observation = freshObservation();
            startSkill(
                PortalSkills.FIND_AND_ENTER_OBSERVED_PORTAL,
                List.of(),
                observation
            );
            enter(Stage.ENTERING_RETURN_PORTAL);
        }

        private void tickReturnPortalEntry() {
            final SkillSupervisor.Snapshot snapshot = tickSkill(true);
            if (!completed(snapshot)) {
                return;
            }
            final var returnedPlayer = player();
            helper.assertTrue(
                returnedPlayer.level().dimension().equals(Level.OVERWORLD),
                "Return portal completed without reaching the overworld"
            );
            helper.assertTrue(
                dragonFightPlayerId != null
                    && dragonFightPlayerId.equals(
                        returnedPlayer.getUUID()
                    )
                    && dragonFightPlayerId.equals(
                        runtime.worldData().companionUuid()
                    ),
                "End return did not preserve the credited AI identity"
            );
            helper.assertTrue(
                runtime.worldData()
                    .verifiedRouteProgress(
                        runtime.goals().snapshot().revision()
                    )
                    .milestones()
                    .contains(SurvivalMilestone.RETURNED_FROM_END),
                "Server did not verify the companion's End return"
            );
            final var connectionAudit =
                AiPlayerManager.connectionAudit(runtime.server())
                    .orElseThrow(() -> helper.assertionException(
                        "Headless connection audit disappeared before "
                            + "End return verification"
                    ));
            helper.assertTrue(
                connectionAudit.endCreditsRespawnRequests() == 1L,
                "End return required exactly one WIN_GAME response, got "
                    + connectionAudit.endCreditsRespawnRequests()
            );
            if (scope == ScenarioScope.END_VICTORY_ONLY) {
                completeFocused();
                return;
            }
            final var tickMetrics =
                runtime.tickMetrics().snapshotSince(performanceStart);
            /*
             * Measure this scenario rather than the runtime lifetime. Forge
             * may register GameTests in a different order between builds,
             * and a lifetime average made this gate depend on unrelated
             * fixtures that happened to run first. The production thresholds
             * and the audited skill chain are unchanged.
             */
            MinecraftAiCompanion.LOGGER.info(
                    "Integrated GameTest scenario runtime metrics: "
                        + "samples={}, "
                        + "averageNanos={}, rollingP95Nanos={}, "
                        + "windowMaximumNanos={}, overP95={}",
                    tickMetrics.samples(),
                    Math.round(tickMetrics.averageNanos()),
                    tickMetrics.windowP95Nanos(),
                    tickMetrics.windowMaximumNanos(),
                    tickMetrics.overP95Target()
            );
            helper.assertTrue(
                    tickMetrics.samples() >= 1_000,
                    "Integrated scenario did not collect enough runtime "
                        + "tick samples: "
                        + tickMetrics.samples()
            );
            helper.assertTrue(
                    tickMetrics.averageTargetMet(),
                    "Companion runtime average tick cost exceeded 1 ms: "
                        + tickMetrics.averageNanos()
                        + " ns"
            );
            helper.assertTrue(
                    tickMetrics.p95TargetMet(),
                    "Companion runtime rolling p95 exceeded 2 ms: "
                        + tickMetrics.windowP95Nanos()
                        + " ns"
            );
            helper.assertTrue(
                AiPlayerManager.requestRemove(runtime.server()).accepted(),
                "Companion cleanup was rejected"
            );
            stage = Stage.FINISHED;
            MinecraftAiCompanion.LOGGER.info(
                "Completed real headless_player_lifecycle_state_and_fair_action GameTest"
            );
            helper.succeed();
        }

        private void completeFocused() {
            helper.assertTrue(
                scope != ScenarioScope.FULL,
                "Full scenario attempted focused completion"
            );
            runtime.survival().reset();
            runtime.coreActions().quiesceNow();
            runtime.interactionActions().quiesceNow();
            runtime.boatActions().quiesceNow();
            runtime.minecartActions().quiesceNow();
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() != SessionState.ABSENT) {
                helper.assertTrue(
                    AiPlayerManager.requestRemove(runtime.server())
                        .accepted(),
                    "Focused movement companion cleanup was rejected"
                );
            }
            stage = Stage.FINISHED;
            MinecraftAiCompanion.LOGGER.info(
                "Completed focused real {} GameTest",
                scope.logName()
            );
            helper.succeed();
        }

        private SkillSupervisor.Snapshot tickSkill(
                final boolean executeCoreLease
        ) {
            final BrainObservation observation =
                runtime.observations().observe(runtime.goals().snapshot());
            final SkillSupervisor.Snapshot snapshot;
            if (executeCoreLease) {
                /*
                 * Match the production arbiter exactly: the 20 TPS
                 * emergency controller runs before the active skill.  A
                 * claimed emergency owns the complete body for this tick;
                 * advancing the lower skill first would let it rotate or
                 * move, after which the emergency overwrites the view and
                 * creates a false alignment timeout.
                */
                if (runtime.skillSupervisor()
                        .consumeActiveSkillEndedHandoff()) {
                    runtime.survival().onActiveSkillEnded();
                }
                final var survival = runtime.survival().tick(
                    runtime.skillSupervisor()
                        .activeSkillManagesVisibleHostileProximity(),
                    runtime.skillSupervisor()
                        .activeSkillManagesPhysicalContactThreats()
                );
                snapshot = survival.intervened()
                    ? runtime.skillSupervisor().snapshot()
                    : runtime.skillSupervisor().tick(
                        connectedContext(observation)
                    );
                final var leaseBefore = runtime.coreActions().snapshot();
                if (scope == ScenarioScope.WATER_ONLY
                        && survival.intervened()) {
                    final var body = player();
                    MinecraftAiCompanion.LOGGER.debug(
                        "Focused water survival state={} reason={} "
                            + "position={} velocity={} fallDistance={} "
                            + "mainHand={}",
                        survival.state(),
                        survival.reason(),
                        body.position(),
                        body.getDeltaMovement(),
                        body.fallDistance,
                        body.getMainHandItem()
                    );
                }
                final var postTick =
                    runtime.coreActions().postServerTick();
                if (stage == Stage.ACQUIRING_NETHER_BLAZE_ROD
                        && (helper.getTick() - stageStartedAt) % 50
                            == 0) {
                    MinecraftAiCompanion.LOGGER.warn(
                        "Nether Blaze lease diagnostic tick={} "
                            + "survival=({},{},{}) before={} "
                            + "post=({},{},{},{}) after={}",
                        helper.getTick(),
                        survival.state(),
                        survival.intervened(),
                        survival.reason(),
                        leaseBefore,
                        postTick.status(),
                        postTick.serverTick(),
                        postTick.tickOutcome(),
                        postTick.failsafeQuiesced(),
                        runtime.coreActions().snapshot()
                    );
                }
            } else {
                snapshot = runtime.skillSupervisor().tick(
                    connectedContext(observation)
                );
            }
            if (snapshot.state() != SkillSupervisor.State.RUNNING
                    && snapshot.state()
                        != SkillSupervisor.State.CANCEL_PENDING
                    && snapshot.state()
                        != SkillSupervisor.State.COMPLETED
                    && stage == Stage.ACQUIRING_ENDER_PEARL) {
                final var fairFrame =
                    runtime.coreFrames().current();
                final List<String> fairVoxels =
                    new java.util.ArrayList<>();
                fairFrame.ifPresent(frame -> {
                    final var feet = frame.feet();
                    frame.navigation().observedVoxels()
                        .values()
                        .stream()
                        .filter(voxel ->
                            Math.abs(
                                voxel.position().x() - feet.x()
                            ) <= 1
                                && voxel.position().y()
                                    >= feet.y() - 1
                                && voxel.position().y()
                                    <= feet.y() + 2
                                && Math.abs(
                                    voxel.position().z() - feet.z()
                                ) <= 1
                        )
                        .sorted(java.util.Comparator.comparing(
                            voxel -> voxel.position().toString()
                        ))
                        .forEach(voxel -> fairVoxels.add(
                            voxel.position() + "="
                                + voxel.kind() + "/"
                                + voxel.occupancyEvidence()
                                + "/support:"
                                + voxel.topSupportAffordance()
                                + "/danger:"
                                + voxel.effectiveDanger()
                                + "/rev:"
                                + voxel.observationRevision()
                        ));
                });
                MinecraftAiCompanion.LOGGER.warn(
                    "Ender reserve failure evidence position={} "
                        + "velocity={} onGround={} fallDistance={} "
                        + "risk={} dangers={} roof={} pillarRemoved={} "
                        + "fairVoxels={} visibleFaces={} terminal={}",
                    player().position(),
                    player().getDeltaMovement(),
                    player().onGround(),
                    player().fallDistance,
                    observation.skillContext().riskScore(),
                    runtime.coreFrames().current()
                        .map(frame -> frame.dangerSignals())
                        .orElse(List.of()),
                    focusedEndermanRoofComplete(),
                    focusedEndermanTemporaryPillarRemoved(),
                    fairVoxels,
                    fairFrame.map(
                        frame -> frame.visibleBlockFaces()
                    ).orElse(List.of()),
                    snapshot.terminalResult()
                );
            }
            if (snapshot.state() == SkillSupervisor.State.FAILED
                    && stage == Stage.BUILDING_NETHER_PORTAL
                    && builtPortalAnchor != null) {
                final List<String> interior = new java.util.ArrayList<>();
                for (int u = 1; u <= 2; u++) {
                    for (int v = 1; v <= 3; v++) {
                        final BlockPos position =
                            builtPortalAnchor.offset(u, v, 0);
                        interior.add(
                            position + "="
                                + helper.getLevel()
                                    .getBlockState(position)
                        );
                    }
                }
                MinecraftAiCompanion.LOGGER.warn(
                    "Portal activation failure world state: "
                        + "interior={} mainHand={}",
                    interior,
                    player().getMainHandItem()
                );
            }
            if (snapshot.state() == SkillSupervisor.State.FAILED
                    && stage == Stage.RETURNING_TO_NETHER_PORTAL) {
                final var body = player();
                final BlockPos feet = body.blockPosition();
                final BlockPos target = BlockPos.containing(
                    netherPortalReturnTarget
                );
                final List<String> corridorVoxels =
                    new java.util.ArrayList<>();
                runtime.observations().navigationSnapshot()
                    .ifPresent(navigation -> {
                        final int minimumX = Math.max(
                            Math.min(feet.getX(), target.getX()) - 1,
                            feet.getX() - 10
                        );
                        final int maximumX = Math.min(
                            Math.max(feet.getX(), target.getX()) + 1,
                            feet.getX() + 10
                        );
                        final int minimumY = Math.max(
                            Math.min(feet.getY(), target.getY()) - 1,
                            feet.getY() - 4
                        );
                        final int maximumY = Math.min(
                            Math.max(feet.getY(), target.getY()) + 2,
                            feet.getY() + 4
                        );
                        final int minimumZ = Math.max(
                            Math.min(feet.getZ(), target.getZ()) - 1,
                            feet.getZ() - 10
                        );
                        final int maximumZ = Math.min(
                            Math.max(feet.getZ(), target.getZ()) + 1,
                            feet.getZ() + 10
                        );
                        for (int x = minimumX; x <= maximumX; x++) {
                            for (int y = minimumY;
                                    y <= maximumY; y++) {
                                for (int z = minimumZ;
                                        z <= maximumZ; z++) {
                                    final var position =
                                        new dev.mcai.companion.navigation.GridPos(
                                            x,
                                            y,
                                            z
                                        );
                                    navigation.voxelAt(position)
                                        .ifPresent(voxel ->
                                            corridorVoxels.add(
                                                position + "="
                                                    + voxel.kind() + "/"
                                                    + voxel.occupancyEvidence()
                                                    + "/support:"
                                                    + voxel.topSupportAffordance()
                                                    + "/danger:"
                                                    + voxel.effectiveDanger()
                                                    + "/rev:"
                                                    + voxel.observationRevision()
                                            )
                                        );
                                }
                            }
                        }
                    });
                MinecraftAiCompanion.LOGGER.warn(
                    "Nether return rejection evidence position={} "
                        + "target={} semanticDangers={} corridorVoxels={}",
                    body.position(),
                    netherPortalReturnTarget,
                    semantic(observation).getAsJsonArray("dangers"),
                    corridorVoxels
                );
            }
            if (snapshot.state() == SkillSupervisor.State.FAILED
                    && stage == Stage.FIGHTING_DRAGON
                    && fightCageBar != null) {
                final var body = player();
                final Vec3 eye = body.getEyePosition();
                final BlockHitResult crosshair = body.level().clip(
                    new ClipContext(
                        eye,
                        eye.add(
                            body.getLookAngle().scale(
                                body.blockInteractionRange()
                            )
                        ),
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        body
                    )
                );
                final Vec3 entityRayEnd = eye.add(
                        body.getLookAngle().scale(16.0)
                );
                final EntityHitResult entityCrosshair =
                        ProjectileUtil.getEntityHitResult(
                                body,
                                eye,
                                entityRayEnd,
                                body.getBoundingBox()
                                    .expandTowards(
                                        entityRayEnd.subtract(eye)
                                    )
                                    .inflate(1.0),
                                EntitySelector.CAN_BE_PICKED.and(
                                    entity -> entity != body
                                ),
                                16.0 * 16.0
                        );
                final List<String> dragonParts =
                        fightDragon == null
                            ? List.of()
                            : java.util.Arrays.stream(
                                    fightDragon.getSubEntities()
                                )
                                .map(part ->
                                    part.name
                                        + "@"
                                        + part.position()
                                        + " bounds="
                                        + part.getBoundingBox()
                                        + " inRange="
                                        + body.isWithinAttackRange(
                                            body.getMainHandItem(),
                                            part.getBoundingBox(),
                                            0.0
                                        )
                                )
                                .toList();
                MinecraftAiCompanion.LOGGER.warn(
                    "Dragon cage mining failure evidence player={} "
                        + "yaw={} pitch={} mainHand={} bar={} "
                        + "barState={} crosshair={} coreActions={} "
                        + "interactionSession={} entityCrosshair={} "
                        + "dragonRoot={} dragonParts={} visibleEntities={}",
                    body.position(),
                    body.getYRot(),
                    body.getXRot(),
                    body.getMainHandItem(),
                    fightCageBar,
                    body.level().getBlockState(fightCageBar),
                    crosshair,
                    runtime.coreActions().snapshot(),
                    runtime.interactionActions()
                        .sessionGeneration(),
                    entityCrosshair == null
                        ? null
                        : entityCrosshair.getEntity()
                            .getClass().getSimpleName()
                            + "@"
                            + entityCrosshair.getLocation(),
                    fightDragon == null
                        ? null
                        : fightDragon.position(),
                    dragonParts,
                    semantic(observation)
                        .getAsJsonArray("visibleEntities")
                );
            }
            assertNotFailed(snapshot);
            return snapshot;
        }

        private void startSkill(
                final String name,
                final List<SkillArgument> arguments,
                final BrainObservation observation
        ) {
            final SkillContext context = connectedContext(observation);
            final DecisionEnvelope decision = new DecisionEnvelope(
                "gametest-" + name + "-" + helper.getTick(),
                context.worldRevision(),
                context.goalRevision(),
                DecisionKind.START_SKILL,
                name,
                arguments,
                RequestedObservation.none(),
                "",
                1.0
            );
            final SkillSupervisor.StartOutcome outcome =
                runtime.skillSupervisor().start(decision, context);
            if (!outcome.accepted()) {
                MinecraftAiCompanion.LOGGER.warn(
                    "GameTest skill start rejected at tick {}: "
                        + "skill={} failure={} supervisor={}",
                    helper.getTick(),
                    name,
                    outcome.failure()
                        .map(failure -> failure.code())
                        .orElse("unknown"),
                    outcome.snapshot()
                );
            }
            helper.assertTrue(
                outcome.accepted(),
                "Production skill " + name + " rejected GameTest start: "
                    + outcome.failure()
                        .map(failure -> failure.code())
                        .orElse("unknown")
            );
        }

        private BrainObservation freshObservation() {
            runtime.observations().requestObservation(
                new RequestedObservation(
                    ObservationKind.SEMANTIC_REFRESH,
                    "GameTest fixture changed"
                )
            );
            return runtime.observations().observe(
                runtime.goals().snapshot()
            );
        }

        /**
         * The full integration chain forces a refresh after every fixture
         * mutation. Focused movement gates force once during setup, then use
         * the production sampler's ordinary cadence so water and parkour are
         * not accidentally validated with a 20 Hz semantic oracle.
         */
        private BrainObservation movementObservation() {
            if (scope == ScenarioScope.FULL) {
                return freshObservation();
            }
            return runtime.observations().observe(
                runtime.goals().snapshot()
            );
        }

        private SkillContext connectedContext(
                final BrainObservation observation
        ) {
            final SkillContext sampled = observation.skillContext();
            return new SkillContext(
                sampled.goalRevision(),
                sampled.worldRevision(),
                Integer.toUnsignedLong(runtime.server().getTickCount()),
                sampled.hardcore() || scope.forceHardcorePolicy(),
                true,
                sampled.riskScore()
            );
        }

        private List<SkillArgument> blockArguments(
                final BrainObservation observation,
                final JsonObject target,
                final boolean expectedEnd
        ) {
            final JsonObject block = target.getAsJsonObject("block");
            final var arguments = new java.util.ArrayList<SkillArgument>(
                expectedEnd ? 7 : 6
            );
            arguments.add(argument("dimension", OVERWORLD));
            arguments.add(argument(
                "sampleSequence",
                Long.toString(sampleSequence(observation))
            ));
            arguments.add(argument("x", block.get("x").getAsString()));
            arguments.add(argument("y", block.get("y").getAsString()));
            arguments.add(argument("z", block.get("z").getAsString()));
            arguments.add(argument(
                "face",
                target.get("face").getAsString()
            ));
            if (expectedEnd) {
                arguments.add(argument(
                    "expectedDestination",
                    "minecraft:the_end"
                ));
            }
            return List.copyOf(arguments);
        }

        private JsonObject findEntity(
                final BrainObservation observation,
                final String type
        ) {
            final var entities = semantic(observation)
                .getAsJsonArray("visibleEntities");
            for (final var element : entities) {
                final JsonObject entity = element.getAsJsonObject();
                if (type.equals(entity.get("type").getAsString())) {
                    return entity;
                }
            }
            return null;
        }

        private BlockPos nearestPortalBlock(
                final Level level,
                final BlockPos center
        ) {
            BlockPos best = null;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int x = -4; x <= 4; x++) {
                for (int y = -4; y <= 4; y++) {
                    for (int z = -4; z <= 4; z++) {
                        final BlockPos candidate =
                            center.offset(x, y, z);
                        if (!level.getBlockState(candidate)
                                .is(Blocks.NETHER_PORTAL)) {
                            continue;
                        }
                        final double distance =
                            candidate.distSqr(center);
                        if (distance < bestDistance) {
                            best = candidate.immutable();
                            bestDistance = distance;
                        }
                    }
                }
            }
            if (best == null) {
                throw helper.assertionException(
                    "Verified Nether traversal had no nearby portal"
                );
            }
            return best;
        }

        private JsonObject findBlock(
                final BrainObservation observation,
                final String type
        ) {
            final var blocks = semantic(observation)
                .getAsJsonArray("visibleBlockFaces");
            for (final var element : blocks) {
                final JsonObject block = element.getAsJsonObject();
                if (type.equals(block.get("type").getAsString())) {
                    return block;
                }
            }
            return null;
        }

        private JsonObject findBlockAt(
                final BrainObservation observation,
                final String type,
                final BlockPos position
        ) {
            final var blocks = semantic(observation)
                .getAsJsonArray("visibleBlockFaces");
            for (final var element : blocks) {
                final JsonObject face = element.getAsJsonObject();
                final JsonObject block =
                    face.getAsJsonObject("block");
                if (type.equals(face.get("type").getAsString())
                        && block.get("x").getAsInt()
                            == position.getX()
                        && block.get("y").getAsInt()
                            == position.getY()
                        && block.get("z").getAsInt()
                            == position.getZ()) {
                    return face;
                }
            }
            return null;
        }

        private JsonObject semantic(
                final BrainObservation observation
        ) {
            return JsonParser.parseString(observation.semanticJson())
                .getAsJsonObject();
        }

        private long sampleSequence(
                final BrainObservation observation
        ) {
            return semantic(observation)
                .get("sampleSequence")
                .getAsLong();
        }

        private void awaitTarget(final String description) {
            helper.assertTrue(
                helper.getTick() - stageStartedAt
                    <= TARGET_DISCOVERY_TIMEOUT_TICKS,
                "Fair first-person sampler did not observe " + description
            );
        }

        private boolean completed(
                final SkillSupervisor.Snapshot snapshot
        ) {
            assertNotFailed(snapshot);
            return snapshot.state() == SkillSupervisor.State.COMPLETED;
        }

        private void assertNotFailed(
                final SkillSupervisor.Snapshot snapshot
        ) {
            if (snapshot.state() == SkillSupervisor.State.RUNNING
                    || snapshot.state()
                        == SkillSupervisor.State.CANCEL_PENDING
                    || snapshot.state()
                        == SkillSupervisor.State.COMPLETED) {
                return;
            }
            final String failure = snapshot.terminalResult()
                .flatMap(result -> result.failure())
                .map(value -> value.code())
                .orElse(snapshot.state().name());
            final String bodyState =
                " position=" + player().position()
                    + " velocity=" + player().getDeltaMovement()
                    + " onGround=" + player().onGround();
            throw helper.assertionException(
                "Production skill " + snapshot.skillName()
                    + " terminated unexpectedly: " + failure
                    + " stage=" + stage
                    + bodyState
            );
        }

        private void enter(final Stage next) {
            stage = next;
            stageStartedAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void setSleepPercentage(final int percentage) {
            helper.getLevel().getGameRules().set(
                GameRules.PLAYERS_SLEEPING_PERCENTAGE,
                percentage,
                runtime.server()
            );
        }

        private void setNaturalSpawning(
                final boolean mobs,
                final boolean monsters
        ) {
            helper.getLevel().getGameRules().set(
                GameRules.SPAWN_MOBS,
                mobs,
                runtime.server()
            );
            helper.getLevel().getGameRules().set(
                GameRules.SPAWN_MONSTERS,
                monsters,
                runtime.server()
            );
        }

        private void setMobDrops(final boolean enabled) {
            helper.getLevel().getGameRules().set(
                GameRules.MOB_DROPS,
                enabled,
                runtime.server()
            );
        }

        private void setGenerateStructures(final boolean enabled) {
            final var settings = runtime.server()
                .getWorldGenSettings();
            ((WorldGenSettingsAccessor) (Object) settings)
                .mcai$setOptions(
                    settings.options().withStructures(enabled)
                );
        }

        private net.minecraft.server.level.ServerPlayer player() {
            return AiPlayerManager.onlinePlayer(runtime.server())
                .orElseThrow(() -> helper.assertionException(
                    "Companion body disappeared during integrated skill test"
                ));
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            setSleepPercentage(originalSleepPercentage);
            setNaturalSpawning(
                originalSpawnMobs,
                originalSpawnMonsters
            );
            setMobDrops(originalMobDrops);
            setGenerateStructures(originalGenerateStructures);
            if (boat != null && !boat.isRemoved()) {
                boat.discard();
            }
            if (minecart != null && !minecart.isRemoved()) {
                minecart.discard();
            }
            if (rangedTarget != null && !rangedTarget.isRemoved()) {
                rangedTarget.discard();
            }
            if (rangedMinecart != null
                    && !rangedMinecart.isRemoved()) {
                rangedMinecart.discard();
            }
            if (endCrystal != null && !endCrystal.isRemoved()) {
                endCrystal.discard();
            }
            if (fightCrystal != null && !fightCrystal.isRemoved()) {
                fightCrystal.discard();
            }
            if (fightDragon != null && !fightDragon.isRemoved()) {
                fightDragon.discard();
            }
            if (lootDrop != null && !lootDrop.isRemoved()) {
                lootDrop.discard();
            }
            if (occludedThreat != null
                    && !occludedThreat.isRemoved()) {
                occludedThreat.discard();
            }
            if (shelteredEnderman != null
                    && !shelteredEnderman.isRemoved()) {
                shelteredEnderman.discard();
            }
            if (resourceTarget != null
                    && !resourceTarget.isRemoved()) {
                resourceTarget.discard();
            }
            AiPlayerManager.onlinePlayer(runtime.server()).ifPresent(
                player -> {
                    if (player.isSleeping()) {
                        player.stopSleepInBed(true, false);
                    }
                    if (player.level() != runtime.server().overworld()) {
                        player.teleportTo(
                            runtime.server().overworld(),
                            origin.getX() + 0.5,
                            origin.getY(),
                            origin.getZ() + 0.5,
                            Set.of(),
                            0.0F,
                            0.0F,
                            false
                        );
                    }
                }
            );
        }

        private static SkillArgument argument(
                final String name,
                final String value
        ) {
            return new SkillArgument(name, value);
        }

        private static String decimal(final double value) {
            return Double.toString(value == 0.0 ? 0.0 : value);
        }

        /**
         * Entity-spawn helpers consume GameTest-relative coordinates, while
         * block fixtures use the absolute {@link #origin}.  Keep both rooted
         * at the active scenario origin so a focused fixture cannot silently
         * spawn its entity at the full-chain origin.
         */
        private Vec3 fixtureRelative(
                final double x,
                final double y,
                final double z
        ) {
            final BlockPos relativeOrigin =
                    scope == ScenarioScope.FULL
                        ? TEST_ORIGIN
                        : FOCUSED_TEST_ORIGIN;
            return new Vec3(
                relativeOrigin.getX() + x,
                relativeOrigin.getY() + y,
                relativeOrigin.getZ() + z
            );
        }

        private static double horizontalDistance(
                final Vec3 left,
                final Vec3 right
        ) {
            return Math.hypot(left.x() - right.x(), left.z() - right.z());
        }

        private static void face(
                final net.minecraft.server.level.ServerPlayer player,
                final Vec3 target
        ) {
            player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
            player.setYHeadRot(player.getYRot());
        }

        private enum Stage {
            FINDING_BOAT,
            ENTERING_BOAT,
            TRAVELLING_BY_BOAT,
            FINDING_MINECART,
            ENTERING_MINECART,
            TRAVELLING_BY_MINECART,
            SETTLING_FOR_BRIDGE,
            BRIDGING_GAP,
            TOWERING_UP,
            SETTLING_FOR_FOCUSED_WATER,
            WATER_CLUTCH_DESCENDING,
            WATER_CLUTCHING,
            SETTLING_FOR_PARKOUR,
            PARKOUR_RUNNING,
            SETTLING_FOR_PARKOUR_LONG_GAP,
            PARKOUR_LONG_GAP,
            SETTLING_FOR_PARKOUR_TURNING_UP,
            PARKOUR_TURNING_UP,
            TRAVELLING_DIAGONAL_DETOUR,
            SCANNING_NETHER_PORTAL_SITE,
            SETTLING_FOR_NETHER_PORTAL_BUILD,
            BUILDING_NETHER_PORTAL,
            FINDING_BUILT_NETHER_PORTAL,
            ENTERING_BUILT_NETHER_PORTAL,
            EXPLORING_NETHER_FOR_TARGET,
            NETHER_FALL_CLUTCHING,
            WAITING_FOR_FOCUSED_NETHER_SIMULATION,
            FINDING_NETHER_BLAZE,
            ACQUIRING_NETHER_BLAZE_ROD,
            RETURNING_TO_NETHER_PORTAL,
            FINDING_NETHER_RETURN_PORTAL,
            ENTERING_NETHER_RETURN_PORTAL,
            EXPLORING_FOR_TARGET,
            COLLECTING_DROP,
            VERIFYING_OCCLUDED_THREAT,
            SETTLING_FOR_ENDER_RESERVE,
            FINDING_SHELTERED_ENDERMAN,
            ACQUIRING_ENDER_PEARL,
            FINDING_RESOURCE_TARGET,
            ENGAGING_AND_COLLECTING,
            FINDING_RANGED_TARGET,
            SHOOTING_RANGED_TARGET,
            VERIFYING_RANGED_HIT,
            TOWERING_TO_CRYSTAL_CAGE,
            EQUIPPING_CRYSTAL_PICKAXE,
            FINDING_CRYSTAL_CAGE_BAR,
            BREAKING_CRYSTAL_CAGE_BAR,
            DESCENDING_FROM_CRYSTAL_CAGE,
            MOVING_TO_CRYSTAL_SHOT,
            EQUIPPING_CRYSTAL_BOW,
            FINDING_END_CRYSTAL,
            SHOOTING_END_CRYSTAL,
            VERIFYING_END_CRYSTAL,
            WAITING_FOR_STRONGHOLD_TRACE_SAFETY,
            TRACING_STRONGHOLD_EYE,
            TRAVELLING_FOR_SECOND_EYE,
            TRACING_SECOND_STRONGHOLD_EYE,
            WAITING_FOR_STRONGHOLD_COMPOUND,
            TRIANGULATING_STRONGHOLD,
            WAITING_FOR_STRONGHOLD_REACH,
            REACHING_STRONGHOLD,
            SETTLING_FOR_END_PORTAL_SEARCH,
            EXPLORING_FOR_END_PORTAL,
            ACTIVATING_END_PORTAL,
            FINDING_BED,
            SLEEPING,
            FINDING_PORTAL,
            ENTERING_PORTAL,
            TRAVELLING_TO_END_GAP,
            BRIDGING_END_GAP,
            TRAVELLING_TO_END_ARENA,
            FIGHTING_DRAGON,
            WAITING_FOR_DRAGON_DEATH,
            FINDING_RETURN_PORTAL,
            ENTERING_RETURN_PORTAL,
            FINISHED
        }
    }

}
