package dev.mcai.companion.communication;

import com.mojang.authlib.GameProfile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcai.companion.control.BehaviorArbiter;
import dev.mcai.companion.control.GoalExecutionPlan;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.GameTestCompanionSpawn;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.memory.MemoryEvent;
import dev.mcai.companion.memory.transport.VerifiedPortalEdge;
import dev.mcai.companion.mcp.MinecraftMcpBackend;
import dev.mcai.companion.mixin.WorldGenSettingsAccessor;
import dev.mcai.companion.model.CapabilityProbeOutcome;
import dev.mcai.companion.model.GatewayStatus;
import dev.mcai.companion.model.ModelGateway;
import dev.mcai.companion.model.ModelOutcome;
import dev.mcai.companion.model.ObservationKind;
import dev.mcai.companion.model.PlannerInput;
import dev.mcai.companion.model.RequestedObservation;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.progression.ServerFoundationEvidenceVerifier;
import dev.mcai.companion.progression.ServerShelterEvidenceVerifier;
import dev.mcai.companion.progression.SurvivalMilestone;
import dev.mcai.companion.runtime.CompanionRuntime;
import dev.mcai.companion.runtime.ServerRuntime;
import dev.mcai.companion.security.CompanionCommandAccess;
import dev.mcai.companion.skill.SkillSupervisor;
import dev.mcai.companion.skills.core.EmergencySurvivalController;
import dev.mcai.companion.skills.building.DynamicShelterPlanner;
import dev.mcai.companion.skills.loot.SecureEnderPearlReserveSkill;
import dev.mcai.companion.skills.portal.ObservedEndPortalGeometry;
import dev.mcai.companion.skills.portal.PortalKind;
import dev.mcai.companion.skills.portal.PortalSkills;
import dev.mcai.companion.skills.portal.PortalTraversalResult;
import dev.mcai.companion.skills.stronghold.EyeTraceHistorySnapshot;
import dev.mcai.companion.skills.stronghold.EyeTraceSnapshot;
import dev.mcai.companion.skills.stronghold.StrongholdSkills;
import dev.mcai.companion.skills.stronghold
        .TriangulateStrongholdSearchAreaSkill;
import dev.mcai.companion.waypoint.DimensionRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.stats.Stats;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraft.tags.StructureTags;
import net.minecraftforge.common.ForgeHooks;

/**
 * Opt-in, development-only test that spends real model tokens. It enters
 * through Forge's official post-packet chat hook and runs without a client
 * renderer or launcher.
 */
public final class LiveModelChatGameTests {
    private static final int BODY_TIMEOUT_TICKS = 3_000;
    private static final long MODEL_TIMEOUT_NANOS =
            java.time.Duration.ofSeconds(120).toNanos();
    private static final long HORDE_MODEL_TIMEOUT_NANOS =
            java.time.Duration.ofSeconds(45).toNanos();
    private static final long FOUNDATION_TOOLKIT_TIMEOUT_NANOS =
            java.time.Duration.ofMinutes(6).toNanos();

    private LiveModelChatGameTests() {
    }

    /**
     * One GameTest server owns one verified model runtime. The first live
     * scenario performs a real capability handshake; later scenarios reuse
     * that exact installed capability profile instead of repeatedly probing
     * the same provider and manufacturing a rate-limit burst.
     */
    private static CompletableFuture<CapabilityProbeOutcome>
            probeOrReuseVerifiedModel(final ServerRuntime runtime) {
        final var setup = runtime.model().snapshot();
        if (setup.gatewayReady()
                && setup.capabilities().isPresent()) {
            return CompletableFuture.completedFuture(
                    new CapabilityProbeOutcome.Supported(
                            setup.capabilities().orElseThrow(),
                            1
                    )
            );
        }
        return runtime.model()
                .prepareConfiguredProfile()
                .toCompletableFuture();
    }

    /**
     * A physical GameTest can reach its asserted outcome one server tick
     * before the model emits COMPLETE_GOAL. End only that test's active goal
     * during cleanup so the next exclusive live scenario cannot inherit a
     * planner skill or suppress its idle equipment controller.
     */
    private static void finishScenarioGoal(
            final ServerRuntime runtime
    ) {
        final GoalStatus status = runtime.goals()
                .snapshot()
                .status();
        if (status == GoalStatus.RUNNING
                || status == GoalStatus.CANCEL_PENDING) {
            runtime.goals().markTerminal(
                    GoalStatus.SAFE_IDLE,
                    "live_test_cleanup"
            );
        }
    }

    /**
     * Test-only boundary for the shared GameTest server. A failed live
     * scenario may leave an active headless body, a leased action, or an
     * emergency lane behind even after its normal cleanup callback runs.
     * Clear that state before the next scenario asks for a body; production
     * startup and respawn never call this helper.
     */
    private static void resetIsolatedScenario(
            final ServerRuntime runtime
    ) {
        GameTestCompanionSpawn.resetForIsolatedFixture(runtime.server());
    }

    /**
     * Distinguishes a gameplay-planner response from the separate
     * conversation request. Both deliberately share the same single model
     * and usage event type, but only {@code brain-*} requests prove that the
     * high-level gameplay loop actually observed the installed task.
     */
    private static boolean isGameplayPlannerUsage(
            final MemoryEvent event,
            final Instant commandAt,
            final long goalRevision
    ) {
        if (event.occurredAt().isBefore(commandAt)
                || event.goalRevision() != goalRevision) {
            return false;
        }
        try {
            final JsonObject payload = JsonParser.parseString(
                    event.payloadJson()
            ).getAsJsonObject();
            final String requestId =
                    payload.get("requestId").getAsString();
            return requestId.startsWith(
                    "brain-" + goalRevision + "-"
            );
        } catch (RuntimeException malformedAuditEvent) {
            return false;
        }
    }

    /**
     * Verifies the production login lifecycle without a renderer or model:
     * a real PlayerList login must create the companion beside that player,
     * publish an explicit AI TAB name, and leave the body motionless while
     * no verified gateway exists.
     */
    public static void autoPresenceOnHumanLogin(
            final GameTestHelper helper
    ) {
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final AutoPresenceScenario scenario =
                new AutoPresenceScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Covers the production-only ordering that ordinary auto-presence tests
     * miss: the dedicated server has no human for longer than the short
     * unanchored admission grace, the AI becomes ACTIVE at the saved/world
     * spawn, and only then does the first human log in.  The companion must
     * reconcile that initial placement through a normal remove/relogin (not a
     * gameplay teleport) while preserving its UUID, idle goal and inventory.
     */
    public static void delayedHumanLoginAfterZeroHumanActive(
            final GameTestHelper helper
    ) {
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate -> candidate.server()
                        == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final DelayedHumanLoginScenario scenario =
                new DelayedHumanLoginScenario(helper, runtime, false);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Regression for the dangerous ordering where the first human joins while
     * the unanchored body is already in its local emergency lane.  The login
     * must record a deferred anchor and leave that exact ServerPlayer alive;
     * the normal remove/relogin is allowed only after the emergency clears.
     */
    public static void delayedHumanLoginWhileEmergencyActive(
            final GameTestHelper helper
    ) {
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate -> candidate.server()
                        == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final DelayedHumanLoginScenario scenario =
                new DelayedHumanLoginScenario(helper, runtime, true);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    public static void realPlayerChatToLiveModel(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveScenario scenario =
                new LiveScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Exercises the complete task path: a Forge chat submission from an
     * authorized mock player, live-model task classification, a second
     * live-model gameplay decision, and ordinary ServerPlayer movement.
     */
    public static void realPlayerTaskToLiveModelMovement(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveMovementScenario scenario =
                new LiveMovementScenario(
                        helper,
                        runtime,
                        false
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Exercises a server-owned navigation stop/resume rather than treating a
     * spoken acknowledgement as completion.  A real chat task starts
     * {@code travel_to}, the player interrupts it at a running-skill
     * checkpoint, and a second coordinate task must start a fresh skill and
     * reach its destination through ordinary player movement.
     */
    public static void realPlayerTaskToLiveModelMovementStopResume(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate -> candidate.server()
                        == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveMovementScenario scenario = new LiveMovementScenario(
                helper,
                runtime,
                true
        );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Exercises the field-facing follow contract rather than a fixed
     * coordinate walk. One ordinary, unaddressed chat message must make the
     * live model bind {@code follow_entity}; the human then walks farther
     * along a collision-checked course while the companion continuously
     * follows through ordinary player movement.
     */
    public static void realPlayerTaskToLiveModelFollow(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveFollowScenario scenario =
                new LiveFollowScenario(helper, runtime, true);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Exercises the teammate stop/resume contract with the configured live
     * model. The player keeps a real chat session open, cancels an active
     * follow at a safe checkpoint, verifies the idle state, and submits a
     * fresh follow request without any teleport or direct world mutation.
     */
    public static void realPlayerTaskToLiveModelFollowStopResume(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server() == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveFollowScenario scenario = new LiveFollowScenario(
                helper, runtime, true, true
        );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Inner-loop physical integration for the trusted immediate-follow lane.
     * A real PlayerList-backed test player submits ordinary Forge chat and
     * the production body must acquire {@code follow_entity} and walk the
     * moving course without a model round trip.  This is deliberately not a
     * formal real-client/model gate; the external Actor/Observer gate remains
     * authoritative for that claim.
     */
    public static void realPlayerChatToImmediateBoundFollow(
            final GameTestHelper helper
    ) {
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveFollowScenario scenario =
                new LiveFollowScenario(helper, runtime, false);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Starts from one fairly visible ordinary dropped stack. A logged-in
     * player asks through normal chat, the configured live model must bind
     * {@code collect_observed_item}, and the body must walk to the exact
     * observed entity and acquire it through vanilla pickup.
     */
    public static void realPlayerTaskToLiveModelItemCollection(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveItemCollectionScenario scenario =
                new LiveItemCollectionScenario(helper, runtime, true);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Uses a real logged-in player's vanilla drop action as the gift source,
     * then exercises the same live-model pickup and immediate inventory-drop
     * round trip. This is separate from the authored-item collection test so
     * a pass proves the player-to-companion handoff path as well.
     */
    public static void realPlayerTaskToLiveModelPlayerGiftRoundTrip(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate -> candidate.server()
                        == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveItemCollectionScenario scenario =
                new LiveItemCollectionScenario(helper, runtime, true, true);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Starts with an unopened visible chest containing ordinary fixture
     * materials. A logged-in player asks through normal chat, then leaves.
     * The configured model must first open that chest through
     * {@code use_block}, bind the resulting semantic menu, and transfer the
     * requested exact count through vanilla menu clicks.
     */
    public static void realPlayerTaskToLiveModelContainerWithdrawal(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveContainerWithdrawalScenario scenario =
                new LiveContainerWithdrawalScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Exercises inventory upkeep and combat through the same real-chat/live
     * model entry used by a player. Test setup only supplies owned equipment
     * and a vanilla Zombie; all equipping, task selection and attacks remain
     * ordinary companion actions.
     */
    public static void realPlayerTaskToLiveModelZombieDefense(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveCombatScenario scenario =
                new LiveCombatScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Exercises the professional-companion interruption contract on the
     * same fair, real-model combat path. The player stops the companion after
     * the melee skill is physically active, verifies that the skill reaches a
     * safe idle checkpoint, then sends a fresh ordinary chat request and
     * requires the same ServerPlayer to reacquire and defeat the visible
     * Zombie. No entity, inventory, or position is reset between phases.
     */
    public static void realPlayerTaskToLiveModelZombieDefenseStopResume(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate -> candidate.server()
                        == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveCombatScenario scenario = new LiveCombatScenario(
                helper,
                runtime,
                true
        );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Sends one ordinary team request through the live model while several
     * visible hostile entities are present.  The fixture is deliberately
     * bounded (six mobs, not a survival-statistic claim): it verifies that a
     * model-selected combat skill can coexist with the local 20 TPS survival
     * lane instead of leaving the body stationary after the first target.
     */
    public static void realPlayerTaskToLiveModelHordeDefense(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                                == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveHordeCombatScenario scenario =
                new LiveHordeCombatScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Extends the same fair live-model combat path to the requested ten
     * Zombies plus ten Skeletons.  The assertion is intentionally bounded to
     * target damage, movement and survival; it is not a claim that one
     * unenchanted body clears every twenty-mob encounter in a natural world.
     */
    public static void realPlayerTaskToLiveModelTenPlusTenHorde(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                                == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveHordeCombatScenario scenario =
                new LiveHordeCombatScenario(
                        helper,
                        runtime,
                        20,
                        10,
                        7.0D,
                        "请马上保护我，击退面前的十个僵尸和十个骷髅，"
                                + "不要只回复，要移动、格挡并攻击。"
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Sends an ordinary Chinese duel request through the live model while a
     * real vanilla iron golem is visible. The golem is held still only until
     * the model-selected combat skill starts; it then receives normal AI and
     * targets the companion. The bounded assertion requires movement, damage
     * to the golem, and survival, rather than a synthetic instant kill.
     */
    public static void realPlayerTaskToLiveModelIronGolemDuel(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate -> candidate.server()
                        == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveIronGolemDuelScenario scenario =
                new LiveIronGolemDuelScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Reproduces the field failure where a hostile approaches from outside
     * the current view and the companion merely stares until death.  The
     * command still enters through ordinary player chat and the live model,
     * but first contact must trigger a physical reaction before a provider
     * round trip can finish.
     */
    public static void realPlayerChatToSurpriseZombieDefense(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final SurpriseZombieScenario scenario =
                new SurpriseZombieScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Reproduces the real low-health gift conversation. A logged-in player
     * drops a normal golden apple, the companion acquires that item through
     * vanilla pickup, and the player says only "给你了，快吃吧". Success
     * requires an actual item-use transaction and a live-model response for
     * the installed goal; a spoken promise is not an outcome.
     */
    public static void realPlayerChatToCriticalGoldenApple(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final CriticalGoldenAppleScenario scenario =
                new CriticalGoldenAppleScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Sends a natural player request to the live model, then requires the
     * selected production parkour skill to clear three real one-block gaps
     * using ordinary sprint-jumps.
     */
    public static void realPlayerTaskToLiveModelParkour(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveParkourScenario scenario =
                new LiveParkourScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Enters through ordinary player chat and a live model, then creates a
     * real twelve-block fall. The online model owns the high-level task while
     * the production 20 TPS emergency controller must equip the owned bucket
     * and perform the time-critical vanilla water placement without waiting
     * for another network response.
     */
    public static void realPlayerTaskToLiveModelWaterClutch(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveWaterClutchScenario scenario =
                new LiveWaterClutchScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Sends the player's ordinary multi-step farm request through the live
     * model and requires three independent production farming skills to
     * harvest mature wheat and restore every plot through vanilla actions.
     */
    public static void realPlayerTaskToLiveModelFarmWork(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveFarmScenario scenario =
                new LiveFarmScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Starts the actual M1 route from an empty inventory through ordinary
     * player chat. The fixture supplies only visible vanilla terrain and an
     * exposed connected oak-log cluster; the model must select the production
     * gatherer, while the body performs normal mining and pickup.
     */
    public static void realPlayerTaskToLiveModelFoundationBootstrap(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveFoundationBootstrapScenario scenario =
                new LiveFoundationBootstrapScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Sends one ordinary player-chat request and stops only after the body
     * has physically mined/picked up wood and stone, then crafted and retained
     * a stone pickaxe through the production foundation route.
     */
    public static void realPlayerChatToLiveModelWoodAndStoneTools(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        resetIsolatedScenario(runtime);
        final LiveFoundationBootstrapScenario scenario =
                new LiveFoundationBootstrapScenario(
                        helper,
                        runtime,
                        false,
                        true
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Sends one abstract player-chat request from a physically empty start
     * and accepts only a real ore break plus raw-iron pickup within five real
     * minutes. The language model must encode the exact iron-material
     * terminal; model speech is never completion evidence.
     */
    public static void realPlayerChatToLiveModelIronAcquisition(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        resetIsolatedScenario(runtime);
        final LiveFoundationBootstrapScenario scenario =
                new LiveFoundationBootstrapScenario(
                        helper,
                        runtime,
                        false,
                        false,
                        true
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Sends one natural player-chat request from a clean relocated start.
     * Completion requires thirty vanilla log breaks and pickups, ordinary
     * tool recipes, four independent chest placements, and an exact balanced
     * distribution of the remaining logs across those four block entities.
     */
    public static void realPlayerChatToLiveModelDistributedLogStorage(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate -> candidate.server()
                        == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        resetIsolatedScenario(runtime);
        final LiveFoundationBootstrapScenario scenario =
                new LiveFoundationBootstrapScenario(
                        helper,
                        runtime,
                        false,
                        false,
                        false,
                        true
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Continues the physical distributed-storage task with a second ordinary
     * player-chat request. The body must remove one convertible wood item
     * from each of the four real chests, craft the resulting plank family and
     * matching door through vanilla recipes, and place the door three blocks
     * in front of the observed chest group.
     */
    public static void
            realPlayerChatToLiveModelDistributedStorageAndDoor(
                    final GameTestHelper helper
            ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate -> candidate.server()
                        == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        resetIsolatedScenario(runtime);
        final LiveFoundationBootstrapScenario scenario =
                new LiveFoundationBootstrapScenario(
                        helper,
                        runtime,
                        false,
                        false,
                        false,
                        false,
                        true
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Covers the first unverified M2 handoff after the iron toolkit. A real
     * player submits one completion-route chat request and leaves. The live
     * model must bind the ordinary portal builder, that builder must consume
     * fourteen owned obsidian and flint-and-steel durability, and the same
     * survival body must then enter the resulting vanilla portal without a
     * second command or teleport.
     */
    public static void
            realPlayerTaskToLiveModelNetherPortalBuildAndEntry(
                    final GameTestHelper helper
            ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveNetherPortalScenario scenario =
                new LiveNetherPortalScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Closes the next completion-route handoff after verified Nether entry.
     * A real player submits one ordinary completion message and leaves. The
     * configured model must select the parameterless durable Blaze-reserve
     * controller, which then performs repeated vanilla combat and pickups
     * until the server-authoritative fourteen-unit route threshold is met.
     */
    public static void
            realPlayerTaskToLiveModelNetherBlazeMaterial(
                    final GameTestHelper helper
            ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveNetherBlazeScenario scenario =
                new LiveNetherBlazeScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Closes the completion-route handoff after Blaze material. A real
     * player submits one ordinary completion message and leaves. The live
     * model must select the durable Ender-pearl reserve controller, which
     * physically builds its safety roof before repeated vanilla combat and
     * pickup cycles reach the fourteen-unit route threshold.
     */
    public static void
            realPlayerTaskToLiveModelEnderPearlReserve(
                    final GameTestHelper helper
            ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveEnderPearlScenario scenario =
                new LiveEnderPearlScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Proves the live high-level handoff for the End-portal activation
     * compound. A real player submits one natural chat request, then leaves.
     * The configured model must select the parameterless production skill;
     * the local controller may resolve the ring center only from the
     * headless player's current first-person frame evidence.
     */
    public static void realPlayerTaskToLiveModelEndPortalActivation(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveEndPortalActivationScenario scenario =
                new LiveEndPortalActivationScenario(
                        helper,
                        runtime,
                        false,
                        false
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Proves the missing handoff after activation. One ordinary player chat
     * request asks for both operations and the player leaves. The configured
     * model must first select the parameterless activation compound, then
     * make a second gameplay decision that starts the parameterless local
     * portal finder. The same survival body must consume the Eyes and cross
     * the resulting portal without a teleport or a second human command.
     */
    public static void
            realPlayerTaskToLiveModelEndPortalActivationAndEntry(
                    final GameTestHelper helper
            ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveEndPortalActivationScenario scenario =
                new LiveEndPortalActivationScenario(
                        helper,
                        runtime,
                        true,
                        false
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Proves the previously missing continuous completion handoff under one
     * ordinary chat goal. Before that command, the fixture establishes an
     * already-completed Nether resource stage by physically traversing a
     * valid portal and naturally picking up the owned crafting ingredients.
     * After the human leaves, no fixture mutation is permitted: the
     * configured model must select ordinary recipe crafting, the
     * parameterless verified-portal return, stronghold triangulation, and
     * fair stronghold approach/excavation compounds with the same survival
     * body.
     */
    public static void
            realPlayerTaskToLiveModelEyeCraftReturnAndStronghold(
                    final GameTestHelper helper
            ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveEyeCraftReturnStrongholdScenario scenario =
                new LiveEyeCraftReturnStrongholdScenario(
                        helper,
                        runtime,
                        false
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Extends the controlled Nether-material route through every remaining
     * irreversible completion handoff under one ordinary chat goal. The
     * configured model must compose Eyes, return through its verified portal,
     * triangulate and reach a stronghold, search an occluded portal room,
     * activate and enter the End portal, defeat the dragon, and physically
     * return with the same survival body.
     */
    public static void
            realPlayerTaskToLiveModelNetherMaterialsToVictory(
                    final GameTestHelper helper
            ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveEyeCraftReturnStrongholdScenario scenario =
                new LiveEyeCraftReturnStrongholdScenario(
                        helper,
                        runtime,
                        true
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Closes the next completion-route handoff after fair stronghold
     * discovery. Before the command, the fixture creates an opaque
     * stronghold corridor with one dead branch and a hidden portal room.
     * After one ordinary player chat and disconnect, the configured model
     * must select portal-room search, activation, and entry in order. The
     * same survival body must physically explore and backtrack, consume its
     * Eyes through vanilla interactions, activate all nine portal cells, and
     * enter the End. No fixture mutation occurs after the route checkpoint
     * is installed.
     */
    public static void
            realPlayerTaskToLiveModelStrongholdPortalRoomAndEntry(
                    final GameTestHelper helper
            ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveStrongholdPortalRoomScenario scenario =
                new LiveStrongholdPortalRoomScenario(
                        helper,
                        runtime,
                        false
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Extends the stronghold-interior gate across the two remaining
     * irreversible completion phases under the same ordinary chat goal. The
     * companion must preserve its UUID and goal revision through portal-room
     * search, activation, End entry, credited dragon combat, and physical
     * return to the Overworld.
     */
    public static void
            realPlayerTaskToLiveModelStrongholdPortalRoomToVictory(
                    final GameTestHelper helper
            ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveStrongholdPortalRoomScenario scenario =
                new LiveStrongholdPortalRoomScenario(
                        helper,
                        runtime,
                        true
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Starts from a previously verified late-game route checkpoint and then
     * proves the four irreversible handoffs under one ordinary player chat
     * goal: activate the observed portal, enter the End, defeat the dragon,
     * and physically return. The checkpoint represents work completed before
     * this test; no model skill is selected or world outcome manufactured
     * after the command.
     */
    public static void
            realPlayerTaskToLiveModelLateEndCompletionChain(
                    final GameTestHelper helper
            ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveEndPortalActivationScenario scenario =
                new LiveEndPortalActivationScenario(
                        helper,
                        runtime,
                        true,
                        true
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Proves the two late irreversible completion phases with the configured
     * model. Test setup first moves the body through a real End portal and
     * prepares a deterministic full-health dragon arena. Only then does a
     * real player submit one chat goal and leave. The model must select the
     * parameterless dragon compound, then bind and enter the currently
     * visible return portal with the same survival body.
     */
    public static void realPlayerTaskToLiveModelEndVictoryAndReturn(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveEndVictoryScenario scenario =
                new LiveEndVictoryScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Isolates the field failure where previously placed workstations occupy
     * every shelter footprint around the companion. A real player supplies
     * one natural chat request to the configured live model. Test setup owns
     * only the flat terrain, ordinary owned materials, and the already
     * existing workstation cluster; success still requires normal walking,
     * first-person surveying, vanilla block placement, and server-verified
     * shelter evidence.
     *
     * <p>This is a seeded fault-reproduction gate, not proof of the complete
     * empty-inventory M1 route.</p>
     */
    public static void realPlayerTaskToLiveModelShelterRelocation(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveShelterRelocationScenario scenario =
                new LiveShelterRelocationScenario(helper, runtime);
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    /**
     * Runs the same live-model foundation transaction on a dedicated server
     * that has no human player at any point in the test. The single initial
     * goal enters through the production MCP backend, which is the supported
     * unattended-server control path. All autonomous work then happens 640
     * blocks from the GameTest origin under the headless player's ordinary
     * PLAYER_SIMULATION ticket.
     */
    public static void zeroHumanDedicatedServerToLiveModelFoundation(
            final GameTestHelper helper
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")) {
            helper.succeed();
            return;
        }
        final ServerRuntime runtime = CompanionRuntime.active()
                .filter(candidate ->
                        candidate.server()
                            == helper.getLevel().getServer())
                .orElseThrow(() -> new IllegalStateException(
                        "Companion runtime is unavailable"
                ));
        final LiveFoundationBootstrapScenario scenario =
                new LiveFoundationBootstrapScenario(
                        helper,
                        runtime,
                        true
                );
        helper.addCleanup(ignored -> scenario.cleanup());
        scenario.start();
        helper.onEachTick(scenario::tick);
    }

    private static final class LiveScenario {
        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;
        private final Instant auditNotBefore;

        private PlacedHuman humanSession;
        private Stage stage = Stage.BODY;
        private CompletableFuture<Optional<MemoryEvent>> speechRead;
        private GoalSnapshot goalBefore;
        private long stageStartedNanos;

        private LiveScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
            auditNotBefore = Instant.now();
        }

        private void start() {
            finishScenarioGoal(runtime);
            resetIsolatedScenario(runtime);
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
            helper.assertTrue(
                    AiPlayerManager.status(runtime.server()).state()
                            == SessionState.ABSENT,
                    "Live-chat fixture could not begin absent"
            );
            final BlockPos loginFeet =
                    helper.absolutePos(new BlockPos(12, 2, 12));
            for (int x = -9; x <= 9; x++) {
                for (int z = -9; z <= 9; z++) {
                    helper.getLevel().setBlockAndUpdate(
                            loginFeet.offset(x, -1, z),
                            Blocks.STONE.defaultBlockState()
                    );
                }
            }
            humanSession = PlacedHuman.create(
                    helper,
                    runtime,
                    Vec3.atBottomCenterOf(loginFeet)
            );
            goalBefore = runtime.goals().snapshot();
            /*
             * Submit through Forge chat immediately after the real login.
             * The startup Keychain probe is intentionally not awaited here:
             * production must retain this utterance until the saved model is
             * restored instead of dropping it or demanding the key again.
             */
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            humanSession.player(),
                            Component.literal(
                                "Could you speak Chinese?"
                            )
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled ordinary player chat"
            );
        }

        private void tick() {
            humanSession.tick();
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForStartupRestore();
                case SPEECH -> waitForSpeech();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status =
                    AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Live-chat companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Live-chat companion body timed out"
                );
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            helper.assertTrue(
                    body.level() == humanSession.player().level()
                        && body.distanceToSqr(humanSession.player())
                            <= 12.0D * 12.0D,
                    "Startup-restored companion did not auto-spawn "
                        + "beside the logged-in player"
            );
            helper.assertTrue(
                    body.getTabListDisplayName() != null
                        && body.getTabListDisplayName()
                            .getString()
                            .startsWith("[AI] "),
                    "Startup-restored companion is absent from TAB"
            );
            stage = Stage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForStartupRestore() {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    "Saved model did not restore automatically: "
                        + startupModelDiagnostic()
            );
            final var setup = runtime.model().snapshot();
            if (!setup.gatewayReady()) {
                return;
            }
            stage = Stage.SPEECH;
            stageStartedNanos = System.nanoTime();
        }

        private String startupModelDiagnostic() {
            final var setup = runtime.model().snapshot();
            return "endpointConfigured=" + setup.endpointConfigured()
                    + ", credentialAvailable="
                    + setup.credentialAvailable()
                    + ", probeInFlight=" + setup.probeInFlight()
                    + ", gatewayReady=" + setup.gatewayReady()
                    + ", configurationErrorCode="
                    + setup.configurationErrorCode();
        }

        private void waitForSpeech() {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    "Live model conversation timed out"
            );
            if (speechRead == null) {
                speechRead = runtime.memory().latestEvent(
                        "brain_speech"
                );
                return;
            }
            if (!speechRead.isDone()) {
                return;
            }
            final Optional<MemoryEvent> found = speechRead.join();
            speechRead = null;
            if (found.isEmpty()
                    || found.orElseThrow().occurredAt()
                        .isBefore(auditNotBefore)) {
                return;
            }
            final String reply = JsonParser.parseString(
                    found.orElseThrow().payloadJson()
                ).getAsJsonObject()
                .get("message")
                .getAsString();
            helper.assertTrue(
                    containsHanCharacter(reply),
                    "Model did not answer the language request in "
                        + "Chinese: " + reply
            );
            final GoalSnapshot after = runtime.goals().snapshot();
            helper.assertTrue(
                    after.revision() == goalBefore.revision()
                        && after.goal().equals(goalBefore.goal()),
                    "Casual conversation was incorrectly promoted "
                        + "to a gameplay goal"
            );
            stage = Stage.DONE;
            helper.succeed();
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }

        private static boolean containsHanCharacter(
                final String text
        ) {
            return text.codePoints().anyMatch(codePoint ->
                    Character.UnicodeScript.of(codePoint)
                        == Character.UnicodeScript.HAN
            );
        }
    }

    private enum Stage {
        BODY,
        PROBE,
        SPEECH,
        DONE
    }

    private static final class AutoPresenceScenario {
        private static final double MAXIMUM_LOGIN_DISTANCE = 12.0D;
        private static final double MAXIMUM_IDLE_DRIFT_SQUARED =
                0.01D;
        private static final int STABLE_TICKS_REQUIRED = 10;
        private static final int IDLE_AUDIT_TICKS = 40;

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final boolean offlineAudit;
        private PlacedHuman humanSession;
        private PresenceStage stage = PresenceStage.LOGIN;
        private Vec3 settlingPosition;
        private Vec3 stablePosition;
        private int stableTicks;
        private int auditTicks;

        private AutoPresenceScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            this.offlineAudit =
                    !Boolean.getBoolean("mcai.liveModelTest");
        }

        private void start() {
            finishScenarioGoal(runtime);
            resetIsolatedScenario(runtime);
            if (offlineAudit) {
                /*
                 * Other release-excluded integrated fixtures may install a
                 * holding gateway into the shared GameTest runtime. Remove
                 * that test delegate so this scenario represents the real
                 * no-credential/no-verified-model state. The live-model
                 * suite deliberately keeps its startup-restored gateway.
                 */
                runtime.model().gateway().clearVerifiedDelegate();
            }
            final var status =
                    AiPlayerManager.status(runtime.server());
            if (status.state() != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
            helper.assertTrue(
                    AiPlayerManager.status(runtime.server()).state()
                            == SessionState.ABSENT,
                    "Auto-presence fixture could not begin absent"
            );
            final BlockPos loginFeet =
                    helper.absolutePos(new BlockPos(12, 2, 12));
            for (int x = -9; x <= 9; x++) {
                for (int z = -9; z <= 9; z++) {
                    helper.getLevel().setBlockAndUpdate(
                            loginFeet.offset(x, -1, z),
                            Blocks.STONE.defaultBlockState()
                    );
                }
            }
            humanSession = PlacedHuman.create(
                    helper,
                    runtime,
                    Vec3.atBottomCenterOf(loginFeet)
            );
        }

        private void tick() {
            humanSession.tick();
            switch (stage) {
                case LOGIN -> waitForAutomaticBody();
                case SETTLE -> waitForStableBody();
                case AUDIT -> auditIdleBody();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForAutomaticBody() {
            final var status =
                    AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Automatic companion login failed: " + status
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            helper.assertTrue(
                    runtime.server().getPlayerList().getPlayer(
                            runtime.worldData().companionUuid()
                    ) == body,
                    "Automatic body is absent from the authoritative "
                        + "PlayerList"
            );
            helper.assertTrue(
                    body.getTabListDisplayName() != null
                        && body.getTabListDisplayName()
                            .getString()
                            .startsWith("[AI] "),
                    "TAB entry does not disclose the online AI identity"
            );
            helper.assertTrue(
                    body.level() == humanSession.player().level(),
                    "Automatic body spawned in another dimension"
            );
            helper.assertTrue(
                    body.distanceToSqr(humanSession.player())
                        <= MAXIMUM_LOGIN_DISTANCE
                            * MAXIMUM_LOGIN_DISTANCE,
                    "Automatic body did not spawn beside the player: "
                        + "distance="
                        + Math.sqrt(body.distanceToSqr(
                                humanSession.player()
                        ))
            );
            if (offlineAudit) {
                helper.assertTrue(
                        !runtime.model().snapshot().gatewayReady(),
                        "Offline presence test unexpectedly has a model "
                            + "gateway"
                );
            } else {
                stage = PresenceStage.DONE;
                helper.succeed();
                return;
            }
            stage = PresenceStage.SETTLE;
        }

        private void waitForStableBody() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            final Vec3 current = body.position();
            if (settlingPosition == null
                    || current.distanceToSqr(settlingPosition)
                        > MAXIMUM_IDLE_DRIFT_SQUARED) {
                settlingPosition = current;
                stableTicks = 0;
                return;
            }
            stableTicks++;
            if (stableTicks < STABLE_TICKS_REQUIRED) {
                return;
            }
            stablePosition = current;
            auditTicks = 0;
            stage = PresenceStage.AUDIT;
        }

        private void auditIdleBody() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            auditTicks++;
            helper.assertTrue(
                    body.position().distanceToSqr(stablePosition)
                        <= MAXIMUM_IDLE_DRIFT_SQUARED,
                    "Companion authored movement without a verified "
                        + "model gateway"
            );
            if (auditTicks < IDLE_AUDIT_TICKS) {
                return;
            }
            stage = PresenceStage.DONE;
            helper.succeed();
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum PresenceStage {
        LOGIN,
        SETTLE,
        AUDIT,
        DONE
    }

    private enum DelayedHumanLoginStage {
        WAITING_FOR_UNANCHORED_BODY,
        WAITING_FOR_EMERGENCY,
        WAITING_FOR_REANCHOR,
        DONE
    }

    private static final class DelayedHumanLoginScenario {
        private static final int MAX_WAIT_TICKS = 1_200;
        private static final double MAXIMUM_LOGIN_DISTANCE = 12.0D;

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final boolean emergencyAtLogin;
        private DelayedHumanLoginStage stage =
                DelayedHumanLoginStage.WAITING_FOR_UNANCHORED_BODY;
        private PlacedHuman humanSession;
        private UUID originalBodyUuid;
        private ServerPlayer originalBody;
        private Mob emergencyAttacker;
        private Vec3 loginPosition;
        private boolean loginDeferredObserved;
        private long originalGoalRevision;
        private int age;

        private DelayedHumanLoginScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final boolean emergencyAtLogin
        ) {
            this.helper = helper;
            this.runtime = runtime;
            this.emergencyAtLogin = emergencyAtLogin;
        }

        private void start() {
            helper.assertTrue(
                    Boolean.getBoolean("mcai.zeroHumanAutoSpawnTest"),
                    "Delayed first-login gate requires the production "
                        + "zero-human auto-spawn property"
            );
            assertOnlyAiIsOnline();
        }

        private void tick() {
            age++;
            if (humanSession != null) {
                humanSession.tick();
            }
            helper.assertTrue(
                    age <= MAX_WAIT_TICKS,
                    "Delayed first-login anchor scenario timed out at stage "
                        + stage
            );
            switch (stage) {
                case WAITING_FOR_UNANCHORED_BODY -> waitForUnanchoredBody();
                case WAITING_FOR_EMERGENCY -> waitForEmergency();
                case WAITING_FOR_REANCHOR -> waitForReanchoredBody();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForUnanchoredBody() {
            assertOnlyAiIsOnline();
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Zero-human body failed before first login: " + status
            );
            if (status.state() != SessionState.ACTIVE || !status.online()) {
                return;
            }
            final ServerPlayer body = AiPlayerManager.onlinePlayer(
                    runtime.server()
            ).orElseThrow();
            helper.assertTrue(
                    runtime.worldData().bodyNeedsInitialAnchor(),
                    "Zero-human body did not retain its unanchored startup "
                        + "provenance"
            );
            helper.assertTrue(
                    runtime.server().getPlayerList().getPlayers().size() == 1,
                    "Active zero-human stage did not contain exactly one AI"
            );
            originalBody = body;
            originalBodyUuid = body.getUUID();
            originalGoalRevision = runtime.goals().snapshot().revision();
            final BlockPos loginFeet = helper.absolutePos(
                    new BlockPos(32, 2, 32)
            );
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    helper.getLevel().setBlockAndUpdate(
                            loginFeet.offset(x, -1, z),
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 0; y <= 2; y++) {
                        helper.getLevel().setBlockAndUpdate(
                                loginFeet.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            loginPosition = Vec3.atBottomCenterOf(loginFeet);
            if (!emergencyAtLogin) {
                createHumanAtLoginPosition();
                stage = DelayedHumanLoginStage.WAITING_FOR_REANCHOR;
                return;
            }

            emergencyAttacker = EntityTypes.ZOMBIE.create(
                    helper.getLevel(),
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                    emergencyAttacker != null,
                    "Could not create the hostile for the deferred-anchor gate"
            );
            emergencyAttacker.setPos(
                    body.getX() + 2.0D,
                    body.getY(),
                    body.getZ() + 0.5D
            );
            emergencyAttacker.setNoAi(true);
            emergencyAttacker.setPersistenceRequired();
            emergencyAttacker.setTarget(body);
            helper.assertTrue(
                    helper.getLevel().addFreshEntity(emergencyAttacker),
                    "Could not add the hostile for the deferred-anchor gate"
            );
            body.setGameMode(GameType.SURVIVAL);
            body.getAbilities().invulnerable = false;
            body.setInvulnerable(false);
            body.invulnerableTime = 0;
            body.setHealth(body.getMaxHealth());
            helper.assertTrue(
                    body.hurtServer(
                            helper.getLevel(),
                            body.damageSources().mobAttack(
                                    emergencyAttacker
                            ),
                            2.0F
                    ),
                    "Controlled hostile damage did not enter the emergency "
                        + "anchor gate"
            );
            body.setInvulnerable(true);
            final var reaction = runtime.survival().tick(false, false);
            helper.assertTrue(
                    reaction.intervened()
                        && reaction.state()
                            != EmergencySurvivalController.State.CLEAR,
                    "Emergency lane did not claim the hostile before login: "
                        + reaction
            );
            stage = DelayedHumanLoginStage.WAITING_FOR_EMERGENCY;
        }

        private void waitForEmergency() {
            final ServerPlayer body = AiPlayerManager.onlinePlayer(
                    runtime.server()
            ).orElseThrow();
            helper.assertTrue(
                    originalBody == body,
                    "Emergency anchor gate replaced the body before human "
                        + "login"
            );
            if (runtime.survival().state()
                    == EmergencySurvivalController.State.CLEAR) {
                helper.assertTrue(
                        age <= MAX_WAIT_TICKS - 120,
                        "Emergency lane cleared before the deferred login "
                            + "could be exercised"
                );
                final var reaction = runtime.survival().tick(false, false);
                if (reaction.state()
                        == EmergencySurvivalController.State.CLEAR) {
                    return;
                }
            }
            createHumanAtLoginPosition();
            helper.assertTrue(
                    AiPlayerManager.onlinePlayer(runtime.server())
                            .orElseThrow() == originalBody,
                    "First human login removed an emergency body instead of "
                        + "deferring the initial anchor"
            );
            loginDeferredObserved = true;
            /*
             * The danger has now been observed at the exact login boundary.
             * Resolve this controlled threat so the production retry can be
             * observed without making the test depend on combat eventually
             * defeating an invulnerable fixture mob.
             */
            if (emergencyAttacker != null
                    && !emergencyAttacker.isRemoved()) {
                emergencyAttacker.discard();
            }
            stage = DelayedHumanLoginStage.WAITING_FOR_REANCHOR;
        }

        private void createHumanAtLoginPosition() {
            humanSession = PlacedHuman.create(
                    helper,
                    runtime,
                    loginPosition
            );
        }

        private void waitForReanchoredBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "First-login body re-anchor failed: " + status
            );
            if (status.state() != SessionState.ACTIVE || !status.online()) {
                return;
            }
            final ServerPlayer body = AiPlayerManager.onlinePlayer(
                    runtime.server()
            ).orElseThrow();
            if (emergencyAtLogin) {
                helper.assertTrue(
                        loginDeferredObserved,
                        "Emergency login never observed a deferred initial "
                            + "anchor"
                );
                if (!runtime.worldData().bodySpawnAnchored()) {
                    helper.assertTrue(
                            body == originalBody,
                            "Emergency retry removed the body before the "
                                + "danger cleared"
                    );
                    return;
                }
            }
            helper.assertTrue(
                    runtime.worldData().bodySpawnAnchored(),
                    "First human login did not claim the initial anchor"
            );
            helper.assertTrue(
                    originalBodyUuid.equals(body.getUUID()),
                    "Re-login changed the stable companion UUID"
            );
            helper.assertTrue(
                    originalGoalRevision
                            == runtime.goals().snapshot().revision(),
                    "Initial anchor reconciliation changed the idle goal"
            );
            helper.assertTrue(
                    runtime.goals().snapshot().status() == GoalStatus.IDLE,
                    "Initial anchor reconciliation created a gameplay goal"
            );
            helper.assertTrue(
                    body.getInventory().isEmpty(),
                    "Initial anchor reconciliation changed the body inventory"
            );
            helper.assertTrue(
                    body.distanceToSqr(humanSession.player())
                            <= MAXIMUM_LOGIN_DISTANCE
                                * MAXIMUM_LOGIN_DISTANCE,
                    "Re-anchored body is not beside the first human: "
                        + Math.sqrt(body.distanceToSqr(
                                humanSession.player()
                        ))
            );
            helper.assertTrue(
                    body.getTabListDisplayName() != null
                        && body.getTabListDisplayName().getString()
                            .startsWith("[AI] "),
                    "Re-anchored body lost its disclosed AI TAB identity"
            );
            helper.assertTrue(
                    runtime.server().getPlayerList().getPlayers().size() == 2,
                    "First-login gate did not retain exactly one AI and one human"
            );
            stage = DelayedHumanLoginStage.DONE;
            helper.succeed();
        }

        private void assertOnlyAiIsOnline() {
            helper.assertTrue(
                    runtime.server().getPlayerList().getPlayers().stream()
                            .allMatch(player ->
                                    dev.mcai.companion.skin.AiProfileMarker
                                            .isMarked(player.getGameProfile())),
                    "Zero-human stage contains a non-AI player"
            );
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (emergencyAttacker != null
                    && !emergencyAttacker.isRemoved()) {
                emergencyAttacker.discard();
            }
            runtime.survival().reset();
            if (humanSession != null) {
                humanSession.close();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private static final class LiveMovementScenario {
        private static final double MINIMUM_REAL_MOVEMENT = 2.0D;
        private static final double ARRIVAL_RADIUS = 2.25D;

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;
        private final boolean exerciseStopResume;

        private PlacedHuman humanSession;
        private ServerPlayer human;
        private MovementStage stage = MovementStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private long stageStartedNanos;
        private long goalRevisionBefore;
        private double startX;
        private double startZ;
        private double targetX;
        private double targetY;
        private double targetZ;
        private double resumeTargetX;
        private double resumeTargetZ;
        private Vec3 positionAtStop;
        private Vec3 resumeStart;
        private int stopStableTicks;
        private boolean stopSubmitted;
        private boolean resumeSkillStarted;

        private LiveMovementScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this(helper, runtime, false);
        }

        private LiveMovementScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final boolean exerciseStopResume
        ) {
            this.helper = helper;
            this.runtime = runtime;
            this.exerciseStopResume = exerciseStopResume;
            this.createdAt = helper.getTick();
            this.stageStartedNanos = System.nanoTime();
        }

        private void start() {
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Live-task companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case GOAL -> waitForGoal();
                case MOVEMENT -> waitForMovement();
                case STOP -> waitForStop();
                case RESUME_GOAL -> waitForResumeGoal();
                case RESUME_MOVEMENT -> waitForResumeMovement();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status =
                    AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Live-task companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Live-task companion body timed out"
                );
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            prepareStraightSafeCourse(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = MovementStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "Live task model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            humanSession = PlacedHuman.create(helper, runtime);
            human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in test player did not gain task-write "
                        + "permission: isOp="
                        + runtime.server().getPlayerList().isOp(
                                new NameAndId(human.getGameProfile())
                        )
                        + ", playerPermission="
                        + human.permissions().hasPermission(
                                Permissions.COMMANDS_GAMEMASTER
                        )
                        + ", sourcePermission="
                        + human.createCommandSourceStack()
                            .permissions()
                            .hasPermission(
                                Permissions.COMMANDS_GAMEMASTER
                            )
            );
            goalRevisionBefore = runtime.goals()
                    .snapshot()
                    .revision();
            final String command = """
                    %s，请走到坐标 %.1f %.1f %.1f，正常步行，不要传送。
                    """.formatted(
                            runtime.worldData().displayName(),
                            targetX,
                            targetY,
                            targetZ
                    ).strip();
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            humanSession.player(),
                            Component.literal(command)
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the movement chat command"
            );
            if (!exerciseStopResume) {
                humanSession.close();
                humanSession = null;
            }
            stage = MovementStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            if (!exerciseStopResume) {
                assertNoHumanPlayersDuringAutonomy();
            }
            assertWithinModelDeadline(
                    "Live model did not classify the movement task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore,
                    "Movement task did not advance goal revision"
            );
            helper.assertTrue(
                    goal.goal().contains("坐标"),
                    "Authorized chat task was not preserved as the goal: "
                        + goal.goal()
            );
            stage = MovementStage.MOVEMENT;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForMovement() {
            if (!exerciseStopResume) {
                assertNoHumanPlayersDuringAutonomy();
            }
            /*
             * The first human login may still be completing the ordinary
             * initial-anchor remove/relogin transaction.  During that
             * bounded server-thread window the authoritative PlayerList has
             * no AI entry; treating it as a movement failure both hides the
             * real model result and makes the live gate flaky.  Wait for the
             * same UUID to be online again instead of teleporting or creating
             * a substitute entity.
             */
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                assertWithinModelDeadline(
                        "Companion body did not return after initial-anchor "
                            + "relogin"
                );
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            if (exerciseStopResume && !stopSubmitted) {
                final var skill = runtime.skillSupervisor().snapshot();
                if (skill.state()
                        == dev.mcai.companion.skill.SkillSupervisor.State.RUNNING
                        && skill.skillName().equals("travel_to")) {
                    final Component stop =
                            ForgeHooks.onServerChatSubmittedEvent(
                                    human,
                                    Component.literal("停下")
                            );
                    helper.assertTrue(
                            stop != null,
                            "Companion cancelled the navigation stop request"
                    );
                    stopSubmitted = true;
                    stage = MovementStage.STOP;
                    stageStartedNanos = System.nanoTime();
                    return;
                }
            }
            final double moved = Math.hypot(
                    body.getX() - startX,
                    body.getZ() - startZ
            );
            final double remaining = Math.sqrt(
                    Math.pow(body.getX() - targetX, 2.0D)
                        + Math.pow(body.getY() - targetY, 2.0D)
                        + Math.pow(body.getZ() - targetZ, 2.0D)
            );
            if (remaining <= ARRIVAL_RADIUS) {
                helper.assertTrue(
                        moved >= MINIMUM_REAL_MOVEMENT,
                        "Body reached the target without material vanilla "
                            + "movement: " + moved
                );
                stage = MovementStage.DONE;
                helper.succeed();
                return;
            }
            if (System.nanoTime() - stageStartedNanos
                    <= MODEL_TIMEOUT_NANOS) {
                return;
            }
            helper.assertTrue(
                    false,
                    moved < MINIMUM_REAL_MOVEMENT
                        ? "Live model task produced no material player "
                            + "movement: " + moved
                        : "Body moved but did not reach the commanded point "
                            + "before the wall-clock deadline: remaining="
                            + remaining + ", moved=" + moved
            );
        }

        private void waitForStop() {
            assertWithinModelDeadline(
                    "Companion did not reach a safe navigation stop"
            );
            helper.assertTrue(
                    stopSubmitted,
                    "Navigation stop stage started without a request"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            final var skill = runtime.skillSupervisor().snapshot();
            if (goal.status() == GoalStatus.RUNNING
                    || goal.status() == GoalStatus.CANCEL_PENDING
                    || skill.state()
                        == dev.mcai.companion.skill.SkillSupervisor.State.RUNNING) {
                return;
            }
            helper.assertTrue(
                    goal.status() == GoalStatus.SAFE_IDLE
                            && goal.detailCode().equals("goal_cancelled"),
                    "Navigation stop ended in an untruthful state: " + goal
            );
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            if (positionAtStop == null) {
                positionAtStop = body.position();
                return;
            }
            helper.assertTrue(
                    body.position().distanceTo(positionAtStop) <= 0.25D,
                    "Body moved after navigation stop: before="
                            + positionAtStop + ", after=" + body.position()
            );
            stopStableTicks++;
            if (stopStableTicks < 2) {
                return;
            }
            resumeTargetX = startX + 15.5D;
            resumeTargetZ = startZ + 0.5D;
            human.setPos(
                    body.getX() + 2.0D,
                    body.getY(),
                    body.getZ()
            );
            human.setDeltaMovement(Vec3.ZERO);
            goalRevisionBefore = goal.revision();
            final Component resume = ForgeHooks.onServerChatSubmittedEvent(
                    human,
                    Component.literal("继续走到坐标 %.1f %.1f %.1f，正常步行，不要传送。"
                            .formatted(resumeTargetX, targetY, resumeTargetZ))
            );
            helper.assertTrue(
                    resume != null,
                    "Companion cancelled the navigation resume request"
            );
            resumeStart = body.position();
            stage = MovementStage.RESUME_GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForResumeGoal() {
            assertWithinModelDeadline(
                    "Live model did not accept navigation resume"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                            && goal.status() == GoalStatus.RUNNING
                            && goal.goal().contains("继续走到坐标"),
                    "Navigation resume did not become a running goal: " + goal
            );
            stage = MovementStage.RESUME_MOVEMENT;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForResumeMovement() {
            assertWithinModelDeadline(
                    "Live model did not start navigation after stop"
            );
            final Optional<ServerPlayer> bodyCandidate =
                    AiPlayerManager.onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            final var skill = runtime.skillSupervisor().snapshot();
            if (skill.state()
                    == dev.mcai.companion.skill.SkillSupervisor.State.RUNNING
                    && skill.skillName().equals("travel_to")) {
                resumeSkillStarted = true;
            }
            if (!resumeSkillStarted) {
                return;
            }
            final double remaining = Math.sqrt(
                    Math.pow(body.getX() - resumeTargetX, 2.0D)
                            + Math.pow(body.getY() - targetY, 2.0D)
                            + Math.pow(body.getZ() - resumeTargetZ, 2.0D)
            );
            if (remaining <= ARRIVAL_RADIUS) {
                final double moved = body.position().distanceTo(resumeStart);
                helper.assertTrue(
                        moved >= MINIMUM_REAL_MOVEMENT,
                        "Resumed navigation reached target without material movement: "
                                + moved
                );
                stage = MovementStage.DONE;
                helper.succeed();
                return;
            }
        }

        private void assertNoHumanPlayersDuringAutonomy() {
            final long humanPlayers = runtime.server()
                    .getPlayerList()
                    .getPlayers()
                    .stream()
                    .filter(player -> !player.getUUID().equals(
                            runtime.worldData().companionUuid()
                    ))
                    .count();
            helper.assertTrue(
                    humanPlayers == 0L,
                    "Live autonomous movement observed "
                        + humanPlayers + " human player(s) after the "
                        + "initial chat command"
            );
        }

        private void prepareStraightSafeCourse(
                final ServerPlayer body
        ) {
            startX = body.getX();
            startZ = body.getZ();
            final BlockPos start = body.blockPosition();
            final int floorY = start.getY() - 1;
            for (int dx = -2; dx <= 20; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    final BlockPos floor = new BlockPos(
                            start.getX() + dx,
                            floorY,
                            start.getZ() + dz
                    );
                    helper.getLevel().setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int dy = 1; dy <= 3; dy++) {
                        helper.getLevel().setBlockAndUpdate(
                                floor.above(dy),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            targetX = start.getX() + 7.5D;
            targetY = start.getY();
            targetZ = start.getZ() + 0.5D;
        }

        private void assertWithinModelDeadline(
                final String message
        ) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum MovementStage {
        BODY,
        PROBE,
        GOAL,
        MOVEMENT,
        STOP,
        RESUME_GOAL,
        RESUME_MOVEMENT,
        DONE
    }

    private static final class LiveFollowScenario {
        private static final double INITIAL_LEAD = 6.0D;
        private static final double SECOND_LEG = 6.0D;
        private static final double HUMAN_STEP = 0.075D;
        private static final double FIRST_ARRIVAL = 3.35D;
        private static final double FINAL_ARRIVAL = 3.75D;
        private static final double MINIMUM_BODY_PATH = 7.0D;
        private static final double MAXIMUM_BODY_TICK_STEP = 0.9D;
        private static final long PHYSICAL_FOLLOW_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(20).toNanos();

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;
        private final boolean requireModelProbe;
        private final boolean exerciseStopResume;

        private FollowStage stage = FollowStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private ServerPlayer human;
        private long stageStartedNanos;
        private long goalRevisionBefore;
        private long followGoalRevision;
        private Vec3 bodyStart;
        private Vec3 previousBodyPosition;
        private double bodyPath;
        private double maximumBodyTickStep;
        private double humanSecondLegStartX;
        private boolean secondLegStarted;
        private boolean sawActiveSkillArbiter;
        private boolean continuationNudgeSent;
        private boolean stopSubmitted;
        private Vec3 positionAtStop;
        private int stopStableTicks;
        private HoldingModelGateway holdingGateway;
        private boolean followCourseRepositioned;

        private LiveFollowScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final boolean requireModelProbe
        ) {
            this(helper, runtime, requireModelProbe, false);
        }

        private LiveFollowScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final boolean requireModelProbe,
                final boolean exerciseStopResume
        ) {
            this.helper = helper;
            this.runtime = runtime;
            this.requireModelProbe = requireModelProbe;
            this.exerciseStopResume = exerciseStopResume;
            this.createdAt = helper.getTick();
            this.stageStartedNanos = System.nanoTime();
        }

        private void start() {
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Live-follow companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (holdingGateway != null) {
                helper.assertTrue(
                        holdingGateway.requestCount() == 0,
                        "Immediate follow unexpectedly used the holding "
                            + "model gateway at stage " + stage
                );
            }
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case VISIBLE -> waitForVisibleHuman();
                case GOAL -> waitForGoal();
                case SKILL -> waitForFollowSkill();
                case STOP -> waitForStop();
                case RESUME_GOAL -> waitForResumeGoal();
                case RESUME_SKILL -> waitForResumeSkill();
                case FOLLOW -> waitForPhysicalFollow();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Live-follow companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Live-follow companion body timed out"
                );
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            prepareSafeFollowCourse(body);
            if (!requireModelProbe) {
                /*
                 * Ordinary chat is intentionally rejected when no verified
                 * provider is installed.  This release-excluded physical
                 * fixture supplies only the readiness precondition, then
                 * proves the immediate path never invokes the provider.
                 */
                holdingGateway = new HoldingModelGateway();
                runtime.model().gateway().install(holdingGateway);
                placeHumanInFairView(body);
                return;
            }
            probe = probeOrReuseVerifiedModel(runtime);
            stage = FollowStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "Live follow model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            placeHumanInFairView(body);
        }

        private void placeHumanInFairView(final ServerPlayer body) {
            humanSession = PlacedHuman.create(
                    helper,
                    runtime,
                    body.position().add(INITIAL_LEAD, 0.0, 0.0)
            );
            human = humanSession.player();
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    human.getEyePosition()
            );
            body.setYHeadRot(body.getYRot());
            stage = FollowStage.VISIBLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForVisibleHuman() {
            assertWithinModelDeadline(
                    "Human never entered the companion's fair semantic view"
            );
            /*
             * A first human login can legitimately trigger the one-time
             * initial-anchor remove/relogin while the body is still
             * unanchored.  PlayerLoggedInEvent is synchronous, but the
             * replacement ServerPlayer completes on subsequent server
             * ticks.  Do not turn that bounded lifecycle gap into the
             * misleading "No value present" failure or submit chat to a
             * body that is between vanilla login sessions.
             */
            final Optional<ServerPlayer> bodyCandidate =
                    AiPlayerManager.onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            if (!followCourseRepositioned) {
                /*
                 * The production first-login anchor intentionally places the
                 * body at a safe 1--2 block offset from the human.  The
                 * follow course needs a visible lead before it can measure a
                 * walk, otherwise the correct "already close" controller
                 * state would deadlock this fixture's second-leg trigger.
                 */
                human.setPos(
                        body.getX() + INITIAL_LEAD,
                        body.getY(),
                        body.getZ()
                );
                human.setDeltaMovement(Vec3.ZERO);
                followCourseRepositioned = true;
            }
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    human.getEyePosition()
            );
            body.setYHeadRot(body.getYRot());
            if (!latestObservationContainsVisiblePlayer()) {
                return;
            }
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in follow test player lacked task permission"
            );
            final var priorGoal = runtime.goals().setGoal(
                    "帮我砍树",
                    GoalSource.PLAYER_CHAT
            );
            helper.assertTrue(
                    priorGoal.accepted()
                            && priorGoal.snapshot().status()
                                == GoalStatus.RUNNING,
                    "Could not install the pre-existing wood goal"
            );
            goalRevisionBefore = priorGoal.snapshot().revision();
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            human,
                            Component.literal(
                                    "跟我来，保持两三格距离，正常走，不要传送。"
                            )
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the ordinary follow chat"
            );
            bodyStart = body.position();
            previousBodyPosition = bodyStart;
            stage = FollowStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            assertWithinModelDeadline(
                    requireModelProbe
                            ? "Live model did not classify the follow task"
                            : "Immediate player follow chat did not install "
                                + "its bound goal"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.status() == GoalStatus.RUNNING
                        && goal.goal().contains("跟我来"),
                    "Ordinary follow chat did not become a running goal: "
                        + goal
            );
            followGoalRevision = goal.revision();
            stage = FollowStage.SKILL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForFollowSkill() {
            assertWithinModelDeadline(
                    (requireModelProbe
                            ? "Live model did not start physical "
                                + "follow_entity; "
                            : "Immediate bound follow did not start physical "
                                + "follow_entity; ")
                        + followDiagnostics()
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            helper.assertTrue(
                    goal.revision() == followGoalRevision
                        && goal.status() == GoalStatus.RUNNING,
                    "Follow goal terminated before movement: " + goal
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if (!skill.skillName().equals("follow_entity")
                    || skill.state()
                        != dev.mcai.companion.skill.SkillSupervisor
                            .State.RUNNING) {
                return;
            }
            helper.assertTrue(
                    skill.boundGoalRevision() == followGoalRevision,
                    "follow_entity bound the wrong goal revision"
            );
            if (exerciseStopResume) {
                final Component stop = ForgeHooks.onServerChatSubmittedEvent(
                        human,
                        Component.literal("停下")
                );
                helper.assertTrue(
                        stop != null,
                        "Companion cancelled the local stop request"
                );
                stopSubmitted = true;
                stage = FollowStage.STOP;
                stageStartedNanos = System.nanoTime();
                return;
            }
            if (!continuationNudgeSent) {
                final Component nudge =
                        ForgeHooks.onServerChatSubmittedEvent(
                                human,
                                Component.literal("走啊")
                        );
                helper.assertTrue(
                        nudge != null,
                        "Companion cancelled the ordinary follow nudge"
                );
                helper.assertTrue(
                        runtime.goals().snapshot().revision()
                                == followGoalRevision
                            && runtime.goals().snapshot().goal()
                                .contains("跟我来"),
                        "A short follow nudge replaced the bound follow goal: "
                                + runtime.goals().snapshot()
                );
                final var afterNudge =
                        runtime.skillSupervisor().snapshot();
                helper.assertTrue(
                        afterNudge.state()
                            == dev.mcai.companion.skill.SkillSupervisor
                                .State.RUNNING
                            && afterNudge.skillName().equals(
                                    "follow_entity"
                            )
                            && afterNudge.boundGoalRevision()
                                == followGoalRevision,
                        "A short follow nudge cancelled physical follow: "
                                + afterNudge
                );
                continuationNudgeSent = true;
            }
            previousBodyPosition = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow()
                    .position();
            stage = FollowStage.FOLLOW;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForStop() {
            assertWithinModelDeadline(
                    "Companion did not reach a safe stop checkpoint"
            );
            helper.assertTrue(
                    stopSubmitted,
                    "Stop stage started without a submitted player request"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            final var skill = runtime.skillSupervisor().snapshot();
            if (goal.status() == GoalStatus.RUNNING
                    || goal.status() == GoalStatus.CANCEL_PENDING
                    || skill.state()
                        == dev.mcai.companion.skill.SkillSupervisor.State.RUNNING) {
                return;
            }
            helper.assertTrue(
                    goal.status() == GoalStatus.SAFE_IDLE
                            && goal.detailCode().equals("goal_cancelled"),
                    "Stop request ended in an untruthful state: " + goal
            );
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            if (positionAtStop == null) {
                positionAtStop = body.position();
                return;
            }
            helper.assertTrue(
                    body.position().distanceTo(positionAtStop) <= 0.25D,
                    "Body moved after the stop checkpoint: before="
                            + positionAtStop + ", after=" + body.position()
            );
            stopStableTicks++;
            if (stopStableTicks < 2) {
                return;
            }
            body.setDeltaMovement(Vec3.ZERO);
            human.setPos(
                    body.getX() + INITIAL_LEAD,
                    body.getY(),
                    body.getZ()
            );
            human.setDeltaMovement(Vec3.ZERO);
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    human.getEyePosition()
            );
            body.setYHeadRot(body.getYRot());
            goalRevisionBefore = goal.revision();
            final Component resume = ForgeHooks.onServerChatSubmittedEvent(
                    human,
                    Component.literal(
                            "跟我来，继续保持两三格距离，正常走，不要传送。"
                    )
            );
            helper.assertTrue(
                    resume != null,
                    "Companion cancelled the follow-up request after stop"
            );
            bodyStart = body.position();
            previousBodyPosition = bodyStart;
            bodyPath = 0.0D;
            secondLegStarted = false;
            continuationNudgeSent = true;
            stage = FollowStage.RESUME_GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForResumeGoal() {
            assertWithinModelDeadline(
                    "Live model did not accept the follow-up after stop"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                            && goal.status() == GoalStatus.RUNNING
                            && goal.goal().contains("跟我来"),
                    "Follow-up chat did not become a running goal: " + goal
            );
            followGoalRevision = goal.revision();
            stage = FollowStage.RESUME_SKILL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForResumeSkill() {
            assertWithinModelDeadline(
                    "Live model did not resume follow_entity after stop"
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if (!skill.skillName().equals("follow_entity")
                    || skill.state()
                        != dev.mcai.companion.skill.SkillSupervisor.State.RUNNING) {
                return;
            }
            helper.assertTrue(
                    skill.boundGoalRevision() == followGoalRevision,
                    "Resumed follow bound the wrong goal revision: " + skill
            );
            stage = FollowStage.FOLLOW;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForPhysicalFollow() {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= PHYSICAL_FOLLOW_TIMEOUT_NANOS,
                    "Companion did not continuously follow the moving "
                        + "player; " + followDiagnostics()
            );
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            helper.assertTrue(
                    body.isAlive()
                        && human.isAlive()
                        && human.connection != null
                        && human.connection.isAcceptingMessages(),
                    "A follow participant left the safe course: "
                        + "bodyAlive=" + body.isAlive()
                        + ", humanAlive=" + human.isAlive()
                        + ", humanRemoved=" + human.isRemoved()
                        + ", body=" + body.position()
                        + ", human=" + human.position()
                        + ", bodyPath=" + bodyPath
                        + ", distance=" + body.distanceTo(human)
                        + ", keepAliveSeen="
                        + humanSession.keepAlivePackets
                        + ", keepAliveAccepted="
                        + humanSession.keepAliveAccepted
            );
            final var continuousSkill =
                    runtime.skillSupervisor().snapshot();
            helper.assertTrue(
                    continuousSkill.state()
                        == dev.mcai.companion.skill.SkillSupervisor
                            .State.RUNNING
                        && continuousSkill.skillName().equals(
                                "follow_entity"
                        )
                        && continuousSkill.boundGoalRevision()
                            == followGoalRevision,
                    "Continuous follow ownership was lost: "
                        + continuousSkill + "; " + followDiagnostics()
            );
            final Vec3 current = body.position();
            final double tickStep =
                    current.distanceTo(previousBodyPosition);
            bodyPath += tickStep;
            maximumBodyTickStep = Math.max(
                    maximumBodyTickStep,
                    tickStep
            );
            previousBodyPosition = current;
            helper.assertTrue(
                    maximumBodyTickStep <= MAXIMUM_BODY_TICK_STEP,
                    "Companion followed by a non-vanilla position jump: "
                        + maximumBodyTickStep
            );

            runtime.behaviorArbiter().latest()
                    .filter(resolution ->
                            resolution.claimedBy(
                                    BehaviorArbiter.Lane.ACTIVE_SKILL
                            ))
                    .ifPresent(ignored ->
                            sawActiveSkillArbiter = true);

            final double distance = body.distanceTo(human);
            if (!secondLegStarted
                    && distance <= FIRST_ARRIVAL
                    && body.position().distanceTo(bodyStart) >= 2.0D) {
                secondLegStarted = true;
                humanSecondLegStartX = human.getX();
            }
            if (secondLegStarted
                    && human.getX() - humanSecondLegStartX
                        < SECOND_LEG) {
                human.move(
                        MoverType.PLAYER,
                        new Vec3(HUMAN_STEP, 0.0, 0.0)
                );
            }

            final double humanSecondLegDistance = secondLegStarted
                    ? human.getX() - humanSecondLegStartX
                    : 0.0D;
            if (humanSecondLegDistance >= SECOND_LEG
                    && distance <= FINAL_ARRIVAL
                    && bodyPath >= MINIMUM_BODY_PATH) {
                helper.assertTrue(
                        continuousSkill.state()
                            == dev.mcai.companion.skill.SkillSupervisor
                                .State.RUNNING
                            && continuousSkill.skillName().equals(
                                    "follow_entity"
                            )
                            && sawActiveSkillArbiter,
                        "Follow reached the player without continuous "
                            + "production skill ownership: "
                            + continuousSkill
                );
                stage = FollowStage.DONE;
                helper.succeed();
            }
        }

        private String followDiagnostics() {
            final var supervisor =
                    runtime.skillSupervisor().snapshot();
            final String frame = runtime.coreFrames().current()
                    .map(value -> "observation="
                            + value.observationRevision()
                            + ", navigation="
                            + value.navigation().revision()
                            + ", voxels="
                            + value.navigation()
                                    .observedVoxels().size()
                            + ", feet=" + value.feet()
                            + ", visibleEntities="
                            + value.visibleEntities().size()
                            + ", corridor="
                            + corridorEvidence(
                                value.navigation(),
                                value.feet(),
                                human
                            ))
                    .orElse("frame=unavailable");
            return "supervisor=" + supervisor + ", " + frame
                    + ", bodyPath=" + bodyPath
                    + ", body="
                    + AiPlayerManager.onlinePlayer(runtime.server())
                        .map(ServerPlayer::position)
                        .orElse(Vec3.ZERO)
                    + ", human="
                    + (human == null ? "not-created" : human.position())
                    + ", distance="
                    + (human == null
                        ? "unknown"
                        : AiPlayerManager.onlinePlayer(runtime.server())
                            .map(body -> body.distanceTo(human))
                            .map(value -> String.format(
                                Locale.ROOT,
                                "%.3f",
                                value
                            ))
                            .orElse("unknown"))
                    + ", coreLease="
                    + runtime.coreActions().snapshot()
                    + ", humanKeepAlive="
                    + (humanSession == null
                            ? "not-created"
                            : humanSession.keepAlivePackets + "/"
                                + humanSession.keepAliveAccepted);
        }

        /**
         * Live-test-only, bounded evidence trace for the straight corridor
         * between the two test players. It reports only the semantic
         * navigation snapshot already available to the production skill; it
         * never reads world blocks and therefore cannot hide a fair-
         * perception defect behind privileged GameTest state.
         */
        private static String corridorEvidence(
                final LocalNavSnapshot snapshot,
                final GridPos start,
                final ServerPlayer target
        ) {
            if (target == null) {
                return "target-not-created";
            }
            final GridPos destination = new GridPos(
                    (int) Math.floor(target.getX()),
                    (int) Math.floor(target.getY()),
                    (int) Math.floor(target.getZ())
            );
            final int deltaX = Integer.compare(
                    destination.x(),
                    start.x()
            );
            final int deltaZ = Integer.compare(
                    destination.z(),
                    start.z()
            );
            final int length = Math.min(
                    12,
                    Math.max(
                        Math.abs(destination.x() - start.x()),
                        Math.abs(destination.z() - start.z())
                    )
            );
            final StringBuilder trace = new StringBuilder();
            for (int offset = 0; offset <= length; offset++) {
                if (offset > 0) {
                    trace.append(';');
                }
                final GridPos feet = start.offset(
                        deltaX * offset,
                        0,
                        deltaZ * offset
                );
                trace.append(offset)
                        .append('@')
                        .append(feet.x())
                        .append(',')
                        .append(feet.z())
                        .append("[f=")
                        .append(voxelEvidence(snapshot, feet))
                        .append(",h=")
                        .append(voxelEvidence(
                            snapshot,
                            feet.above()
                        ))
                        .append(",s=")
                        .append(voxelEvidence(
                            snapshot,
                            feet.below()
                        ))
                        .append(']');
            }
            return trace.toString();
        }

        private static String voxelEvidence(
                final LocalNavSnapshot snapshot,
                final GridPos position
        ) {
            return snapshot.voxelAt(position)
                    .map(voxel -> voxel.kind()
                        + "/"
                        + voxel.occupancyEvidence()
                        + "/"
                        + voxel.topSupportAffordance()
                        + "/age"
                        + (snapshot.revision()
                            - voxel.observationRevision()))
                    .orElse("?");
        }

        private void prepareSafeFollowCourse(
                final ServerPlayer body
        ) {
            final BlockPos start = body.blockPosition();
            final int floorY = start.getY() - 1;
            for (int dx = -3; dx <= 22; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    final BlockPos floor = new BlockPos(
                            start.getX() + dx,
                            floorY,
                            start.getZ() + dz
                    );
                    helper.getLevel().setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int dy = 1; dy <= 3; dy++) {
                        helper.getLevel().setBlockAndUpdate(
                                floor.above(dy),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            helper.getLevel().getEntitiesOfClass(
                    Mob.class,
                    body.getBoundingBox().inflate(48.0)
            ).forEach(Mob::discard);
        }

        private boolean latestObservationContainsVisiblePlayer() {
            final Optional<String> semantic =
                    runtime.observations().latestSemanticJson();
            if (semantic.isEmpty()) {
                return false;
            }
            try {
                final var entities = JsonParser
                        .parseString(semantic.orElseThrow())
                        .getAsJsonObject()
                        .getAsJsonArray("visibleEntities");
                if (entities == null) {
                    return false;
                }
                for (var element : entities) {
                    if ("minecraft:player".equals(
                            element.getAsJsonObject()
                                .get("type").getAsString()
                    )) {
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException malformedObservation) {
                return false;
            }
        }

        private void assertWithinModelDeadline(
                final String message
        ) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (holdingGateway != null) {
                runtime.model().gateway().clearVerifiedDelegate();
            }
            if (humanSession != null) {
                humanSession.close();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    /**
     * Test-only readiness sentinel. A direct player-bound skill must never
     * call it; the surrounding scenario asserts that invariant every tick.
     */
    private static final class HoldingModelGateway implements ModelGateway {
        private final CompletableFuture<ModelOutcome> holding =
                new CompletableFuture<>();
        private int requestCount;
        private boolean closed;

        @Override
        public synchronized java.util.concurrent.CompletionStage<ModelOutcome>
                decide(final PlannerInput input) {
            requestCount++;
            return holding;
        }

        @Override
        public void cancelForGoalRevision(final long currentGoalRevision) {
            // No request is expected; cancellation remains harmless.
        }

        @Override
        public synchronized GatewayStatus status() {
            return closed ? GatewayStatus.CLOSED : GatewayStatus.IDLE;
        }

        @Override
        public synchronized boolean configured() {
            // The fixture must admit chat/goal installation while proving
            // that the offline direct-follow fallback never calls a model.
            return !closed;
        }

        @Override
        public boolean highLevelDecisionReady() {
            return false;
        }

        @Override
        public synchronized void close() {
            closed = true;
            holding.cancel(false);
        }

        private synchronized int requestCount() {
            return requestCount;
        }
    }

    private enum FollowStage {
        BODY,
        PROBE,
        VISIBLE,
        GOAL,
        SKILL,
        STOP,
        RESUME_GOAL,
        RESUME_SKILL,
        FOLLOW,
        DONE
    }

    private static final class LiveItemCollectionScenario {
        private static final int DROP_COUNT = 3;
        private static final double DROP_DISTANCE = 8.0D;
        private static final double MINIMUM_REAL_MOVEMENT = 4.0D;
        private static final double MAXIMUM_TICK_STEP = 1.25D;
        private static final long PICKUP_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(30).toNanos();

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final boolean roundTrip;
        private final boolean playerGift;
        private final long createdAt;

        private ItemCollectionStage stage = ItemCollectionStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private ItemEntity drop;
        private ServerPlayer bodyBeforeHumanLogin;
        private long stageStartedNanos;
        private long goalRevisionBefore;
        private long collectionGoalRevision;
        private long dropGoalRevisionBefore;
        private long dropGoalRevision;
        private boolean dropGoalSubmitted;
        private Vec3 bodyStart;
        private Vec3 previousBody;
        private ServerPlayer previousBodyInstance;
        private double bodyPath;
        private double maximumTickStep;

        private LiveItemCollectionScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this(helper, runtime, false);
        }

        private LiveItemCollectionScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final boolean roundTrip
        ) {
            this(helper, runtime, roundTrip, false);
        }

        private LiveItemCollectionScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final boolean roundTrip,
                final boolean playerGift
        ) {
            this.helper = helper;
            this.runtime = runtime;
            this.roundTrip = roundTrip;
            this.playerGift = playerGift;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            finishScenarioGoal(runtime);
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Item-collection companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case GIFT -> waitForPlayerGift();
                case VISIBLE -> waitForVisibleDrop();
                case GOAL -> waitForGoal();
                case SKILL -> waitForCollectionSkill();
                case COLLECT -> waitForVanillaPickup();
                case DROP_GOAL -> waitForDropGoal();
                case DROP_SKILL -> waitForDropSkill();
                case DROP_ENTITY -> waitForDropEntity();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Item-collection companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Item-collection companion body timed out"
                );
                return;
            }
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                helper.assertTrue(
                        helper.getTick() - createdAt <= BODY_TIMEOUT_TICKS,
                        "Item-collection companion body timed out while "
                            + "waiting for its authoritative login"
                );
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            prepareCourse(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = ItemCollectionStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "Item-collection model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                assertWithinModelDeadline(
                        "Item-collection body disappeared while the model "
                            + "probe was completing"
                );
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            if (playerGift) {
                bodyBeforeHumanLogin = body;
                humanSession = PlacedHuman.create(
                        helper,
                        runtime,
                        body.position().add(0.0D, 0.0D, -2.0D)
                );
                stage = ItemCollectionStage.GIFT;
                stageStartedNanos = System.nanoTime();
                return;
            }
            drop = new ItemEntity(
                    helper.getLevel(),
                    body.getX() + DROP_DISTANCE,
                    body.getY(),
                    body.getZ(),
                    new ItemStack(Items.OAK_LOG, DROP_COUNT)
            );
            drop.setDeltaMovement(Vec3.ZERO);
            helper.assertTrue(
                    helper.getLevel().addFreshEntity(drop),
                    "Could not create the ordinary dropped log stack"
            );
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    drop.getEyePosition()
            );
            body.setYHeadRot(body.getYRot());
            stage = ItemCollectionStage.VISIBLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForPlayerGift() {
            assertWithinModelDeadline(
                    "Player gift was not delivered through the vanilla drop path"
            );
            if (humanSession == null) {
                helper.assertTrue(false, "Player gift session disappeared");
                return;
            }
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            if (runtime.worldData().bodyNeedsInitialAnchor()
                    && body == bodyBeforeHumanLogin) {
                return;
            }
            physicallyGiveLogs(body, humanSession.player());
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    drop.getEyePosition()
            );
            body.setYHeadRot(body.getYRot());
            stage = ItemCollectionStage.VISIBLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForVisibleDrop() {
            assertWithinModelDeadline(
                    "Dropped logs never entered fair semantic view"
            );
            if (!latestObservationContainsOakLog()) {
                return;
            }
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                assertWithinModelDeadline(
                        "Item-collection body disappeared while the "
                            + "visible-drop task was being submitted"
                );
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            if (humanSession == null) {
                humanSession = PlacedHuman.create(
                        helper,
                        runtime,
                        body.position().add(0.0D, 0.0D, -2.0D)
                );
            }
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in item test player lacked task permission"
            );
            goalRevisionBefore = runtime.goals().snapshot().revision();
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            human,
                            Component.literal(
                                    runtime.worldData().displayName()
                                        + "，请把你面前掉落的橡木原木"
                                        + "捡进背包。"
                            )
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the item pickup chat"
            );
            bodyStart = body.position();
            previousBody = bodyStart;
            humanSession.close();
            humanSession = null;
            stage = ItemCollectionStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void physicallyGiveLogs(
                final ServerPlayer body,
                final ServerPlayer human
        ) {
            human.setItemSlot(
                    EquipmentSlot.MAINHAND,
                    new ItemStack(Items.OAK_LOG, DROP_COUNT)
            );
            final ItemStack transferred = human.getMainHandItem().copy();
            human.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            drop = human.drop(transferred, true);
            helper.assertTrue(
                    drop != null,
                    "Logged-in player could not drop the oak-log gift"
            );
            drop.setNoPickUpDelay();
            drop.setPos(
                    body.getX() + DROP_DISTANCE,
                    body.getY(),
                    body.getZ()
            );
            drop.setDeltaMovement(Vec3.ZERO);
            body.inventoryMenu.broadcastChanges();
            helper.assertTrue(
                    human.getInventory().countItem(Items.OAK_LOG) == 0,
                    "The player retained a duplicate oak-log gift"
            );
        }

        private void waitForGoal() {
            assertNoHumanPlayers();
            assertWithinModelDeadline(
                    "Live model did not classify the item pickup task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.status() == GoalStatus.RUNNING
                        && goal.goal().contains("捡"),
                    "Item chat did not become a running pickup goal: "
                        + goal
            );
            collectionGoalRevision = goal.revision();
            stage = ItemCollectionStage.SKILL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForCollectionSkill() {
            assertNoHumanPlayers();
            assertWithinModelDeadline(
                    "Live model did not start collect_observed_item; "
                        + diagnostics()
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if (!skill.skillName().equals("collect_observed_item")
                    || skill.state()
                        != dev.mcai.companion.skill.SkillSupervisor
                            .State.RUNNING) {
                return;
            }
            helper.assertTrue(
                    skill.boundGoalRevision() == collectionGoalRevision,
                    "collect_observed_item bound the wrong goal revision"
            );
            stage = ItemCollectionStage.COLLECT;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForVanillaPickup() {
            assertNoHumanPlayers();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= PICKUP_TIMEOUT_NANOS,
                        "Item collection body was offline during pickup"
                            + ": " + diagnostics()
                );
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            final Vec3 current = body.position();
            if (previousBody == null || previousBodyInstance != body) {
                /*
                 * The first human login may replace the headless
                 * ServerPlayer through the ordinary remove/relogin anchor
                 * transaction.  Do not count that lifecycle relocation as a
                 * movement-frame delta; all subsequent deltas remain bounded
                 * by the vanilla actuator check below.
                 */
                previousBody = current;
                previousBodyInstance = body;
                return;
            }
            final double tickStep = current.distanceTo(previousBody);
            bodyPath += tickStep;
            maximumTickStep = Math.max(maximumTickStep, tickStep);
            previousBody = current;
            previousBodyInstance = body;
            helper.assertTrue(
                    maximumTickStep <= MAXIMUM_TICK_STEP,
                    "Item pickup used a non-vanilla position jump: "
                        + maximumTickStep
            );
            final int owned =
                    body.getInventory().countItem(Items.OAK_LOG);
            final boolean dropRemoved =
                    drop.isRemoved() || !drop.isAlive();
            if (owned >= DROP_COUNT && dropRemoved) {
                helper.assertTrue(
                        body.position().distanceTo(bodyStart)
                            >= MINIMUM_REAL_MOVEMENT
                            && bodyPath >= MINIMUM_REAL_MOVEMENT,
                        "Logs entered inventory without material approach: "
                            + diagnostics()
                );
                if (!roundTrip) {
                    stage = ItemCollectionStage.DONE;
                    helper.succeed();
                    return;
                }
                if (!dropGoalSubmitted) {
                    submitDropGoal(body);
                }
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= PICKUP_TIMEOUT_NANOS,
                    "Vanilla dropped-item pickup timed out: "
                        + diagnostics()
            );
        }

        private void submitDropGoal(final ServerPlayer body) {
            dropGoalSubmitted = true;
            final PlacedHuman human = PlacedHuman.create(
                    helper,
                    runtime,
                    body.position().add(0.0D, 0.0D, -2.0D)
            );
            humanSession = human;
            final ServerPlayer player = human.player();
            dropGoalRevisionBefore = runtime.goals().snapshot().revision();
            final Component submitted = ForgeHooks.onServerChatSubmittedEvent(
                    player,
                    Component.literal(
                            runtime.worldData().displayName()
                                    + "，请立即把背包里的3个橡木原木丢到地上，"
                                    + "不要只回复。"
                    )
            );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the inventory drop chat"
            );
            stage = ItemCollectionStage.DROP_GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForDropGoal() {
            assertWithinModelDeadline(
                    "Live model did not classify the inventory drop task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == dropGoalRevisionBefore) {
                return;
            }
            if (humanSession != null) {
                humanSession.close();
                humanSession = null;
            }
            assertNoHumanPlayers();
            helper.assertTrue(
                    goal.revision() > dropGoalRevisionBefore
                            && goal.status() == GoalStatus.RUNNING
                            && goal.goal().contains("丢"),
                    "Inventory drop chat did not become a running goal: "
                            + goal
            );
            dropGoalRevision = goal.revision();
            stage = ItemCollectionStage.DROP_SKILL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForDropSkill() {
            assertNoHumanPlayers();
            assertWithinModelDeadline(
                    "Live model did not start drop_item; " + diagnostics()
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if (!skill.skillName().equals("drop_item")
                    || skill.state() != SkillSupervisor.State.RUNNING) {
                return;
            }
            helper.assertTrue(
                    skill.boundGoalRevision() == dropGoalRevision,
                    "drop_item bound the wrong goal revision"
            );
            stage = ItemCollectionStage.DROP_ENTITY;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForDropEntity() {
            assertNoHumanPlayers();
            final Optional<ServerPlayer> bodyCandidate =
                    AiPlayerManager.onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                assertWithinModelDeadline(
                        "Companion body disappeared during inventory drop"
                );
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            final int owned = body.getInventory().countItem(Items.OAK_LOG);
            final int dropped = helper.getLevel().getEntitiesOfClass(
                    ItemEntity.class,
                    body.getBoundingBox().inflate(3.0D),
                    entity -> entity.isAlive()
                            && entity.getItem().is(Items.OAK_LOG)
                            && entity.getItem().getCount() == DROP_COUNT
            ).stream().mapToInt(entity -> entity.getItem().getCount()).sum();
            if (owned == 0 && dropped >= DROP_COUNT) {
                stage = ItemCollectionStage.DONE;
                helper.succeed();
                return;
            }
            assertWithinModelDeadline(
                    "Vanilla inventory drop did not produce the owned item "
                            + diagnostics()
            );
        }

        private void prepareCourse(final ServerPlayer body) {
            final BlockPos start = body.blockPosition();
            final int floorY = start.getY() - 1;
            for (int dx = -2; dx <= 12; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    final BlockPos floor = new BlockPos(
                            start.getX() + dx,
                            floorY,
                            start.getZ() + dz
                    );
                    helper.getLevel().setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int dy = 1; dy <= 3; dy++) {
                        helper.getLevel().setBlockAndUpdate(
                                floor.above(dy),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            helper.getLevel().getEntitiesOfClass(
                    Mob.class,
                    body.getBoundingBox().inflate(32.0D)
            ).forEach(Mob::discard);
            body.getInventory().clearContent();
            body.inventoryMenu.broadcastChanges();
            body.setDeltaMovement(Vec3.ZERO);
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
        }

        private boolean latestObservationContainsOakLog() {
            final Optional<String> semantic =
                    runtime.observations().latestSemanticJson();
            if (semantic.isEmpty()) {
                return false;
            }
            try {
                final var visible = JsonParser
                        .parseString(semantic.orElseThrow())
                        .getAsJsonObject()
                        .getAsJsonArray("visibleEntities");
                if (visible == null) {
                    return false;
                }
                for (var element : visible) {
                    final var entity = element.getAsJsonObject();
                    final var properties =
                            entity.getAsJsonObject("properties");
                    if ("minecraft:item".equals(
                            entity.get("type").getAsString()
                    )
                            && properties != null
                            && properties.has("itemId")
                            && "minecraft:oak_log".equals(
                                properties.get("itemId").getAsString()
                            )) {
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException malformedObservation) {
                return false;
            }
        }

        private void assertNoHumanPlayers() {
            final long humans = runtime.server()
                    .getPlayerList()
                    .getPlayers()
                    .stream()
                    .filter(player -> !player.getUUID().equals(
                            runtime.worldData().companionUuid()
                    ))
                    .count();
            helper.assertTrue(
                    humans == 0L,
                    "Item collection retained " + humans
                        + " human player(s) after the command"
            );
        }

        private void assertWithinModelDeadline(final String message) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private String diagnostics() {
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            final String bodySummary = bodyCandidate
                    .map(body -> "position=" + body.position()
                            + ", ownedLogs="
                            + body.getInventory().countItem(Items.OAK_LOG))
                    .orElse("offline");
            return "supervisor=" + runtime.skillSupervisor().snapshot()
                    + ", goal=" + runtime.goals().snapshot()
                    + ", body=" + bodySummary
                    + ", bodyPath=" + bodyPath
                    + ", maxTickStep=" + maximumTickStep
                    + ", dropAlive="
                    + (drop != null && drop.isAlive())
                    + ", drop="
                    + (drop == null ? "not-created" : drop.position());
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (drop != null && !drop.isRemoved()) {
                drop.discard();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum ItemCollectionStage {
        BODY,
        PROBE,
        GIFT,
        VISIBLE,
        GOAL,
        SKILL,
        COLLECT,
        DROP_GOAL,
        DROP_SKILL,
        DROP_ENTITY,
        DONE
    }

    private static final class LiveContainerWithdrawalScenario {
        private static final int FIXTURE_PLANK_COUNT = 5;
        private static final int REQUESTED_PLANK_COUNT = 3;
        private static final long TRANSACTION_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(45).toNanos();

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;

        private ContainerWithdrawalStage stage =
                ContainerWithdrawalStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private BlockPos chestPosition;
        private long stageStartedNanos;
        private long goalRevisionBefore;
        private long withdrawalGoalRevision;
        private boolean sawUseBlockSkill;
        private boolean sawTransferSkill;

        private LiveContainerWithdrawalScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            finishScenarioGoal(runtime);
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Container companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            observeActiveSkill();
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case VISIBLE -> waitForVisibleChest();
                case GOAL -> waitForGoal();
                case OPEN -> waitForVanillaMenuOpen();
                case MENU_VISIBLE -> waitForObservedMenu();
                case TRANSFER -> waitForVanillaTransfer();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Container companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Container companion body timed out"
                );
                return;
            }
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                helper.assertTrue(
                        helper.getTick() - createdAt <= BODY_TIMEOUT_TICKS,
                        "Container companion body timed out while waiting "
                            + "for its authoritative login"
                );
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            prepareFixture(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = ContainerWithdrawalStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "Container model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                assertWithinModelDeadline(
                        "Container body disappeared while the model probe "
                            + "was completing"
                );
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(chestPosition)
            );
            body.setYHeadRot(body.getYRot());
            stage = ContainerWithdrawalStage.VISIBLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForVisibleChest() {
            assertWithinModelDeadline(
                    "Chest never entered fair semantic view"
            );
            if (humanSession == null) {
                if (!latestObservationContainsClosedChest()) {
                    return;
                }
                final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                        .onlinePlayer(runtime.server());
                if (bodyCandidate.isEmpty()) {
                    return;
                }
                final ServerPlayer body = bodyCandidate.orElseThrow();
                /*
                 * Keep the human online while the production initial-anchor
                 * remove/relogin finishes.  The task is submitted only after
                 * the authoritative replacement body has a fresh first-person
                 * observation of this exact chest.
                 */
                humanSession = PlacedHuman.create(
                        helper,
                        runtime,
                        body.position().add(0.0D, 0.0D, -2.0D)
                );
                return;
            }
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(chestPosition)
            );
            body.setYHeadRot(body.getYRot());
            if (!latestObservationContainsClosedChest()) {
                return;
            }
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in container test player lacked task permission"
            );
            goalRevisionBefore = runtime.goals().snapshot().revision();
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            human,
                            Component.literal(
                                    runtime.worldData().displayName()
                                        + "，请从你面前的箱子里取出3块"
                                        + "橡木木板，放进自己的背包。"
                            )
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the container-withdrawal chat"
            );
            humanSession.close();
            humanSession = null;
            stage = ContainerWithdrawalStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            assertNoHumanPlayers();
            assertWithinModelDeadline(
                    "Live model did not classify the container task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.status() == GoalStatus.RUNNING
                        && goal.goal().contains("箱子")
                        && goal.goal().contains("取出"),
                    "Container chat did not become a running goal: "
                        + goal
            );
            withdrawalGoalRevision = goal.revision();
            stage = ContainerWithdrawalStage.OPEN;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForVanillaMenuOpen() {
            assertNoHumanPlayers();
            assertWithinModelDeadline(
                    "Model did not open the visible chest; "
                        + diagnostics()
            );
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            if (body.containerMenu == body.inventoryMenu) {
                return;
            }
            helper.assertTrue(
                    sawUseBlockSkill
                        && body.containerMenu instanceof ChestMenu,
                    "Chest menu opened without an observed model-selected "
                        + "use_block skill: " + diagnostics()
            );
            stage = ContainerWithdrawalStage.MENU_VISIBLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForObservedMenu() {
            assertNoHumanPlayers();
            assertWithinModelDeadline(
                    "Opened chest menu never entered fair semantic view"
            );
            if (!latestObservationContainsTransferablePlanks()) {
                return;
            }
            stage = ContainerWithdrawalStage.TRANSFER;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForVanillaTransfer() {
            assertNoHumanPlayers();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= TRANSACTION_TIMEOUT_NANOS,
                        "Container body was offline during transfer: "
                            + diagnostics()
                );
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            final ChestBlockEntity chest = chest();
            final int owned =
                    body.getInventory().countItem(Items.OAK_PLANKS);
            final int remaining =
                    chest.countItem(Items.OAK_PLANKS);
            if (owned == REQUESTED_PLANK_COUNT
                    && remaining
                        == FIXTURE_PLANK_COUNT
                            - REQUESTED_PLANK_COUNT) {
                helper.assertTrue(
                        sawTransferSkill
                            && body.containerMenu instanceof ChestMenu
                            && body.containerMenu.getCarried().isEmpty(),
                        "Container inventory changed without the bound "
                            + "transfer_menu_item transaction: "
                            + diagnostics()
                );
                stage = ContainerWithdrawalStage.DONE;
                helper.succeed();
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= TRANSACTION_TIMEOUT_NANOS,
                    "Vanilla container transfer timed out: "
                        + diagnostics()
            );
        }

        private void prepareFixture(final ServerPlayer body) {
            final BlockPos start = body.blockPosition();
            final int floorY = start.getY() - 1;
            for (int dx = -2; dx <= 7; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    final BlockPos floor = new BlockPos(
                            start.getX() + dx,
                            floorY,
                            start.getZ() + dz
                    );
                    helper.getLevel().setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int dy = 1; dy <= 4; dy++) {
                        helper.getLevel().setBlockAndUpdate(
                                floor.above(dy),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            helper.getLevel().getEntitiesOfClass(
                    Mob.class,
                    body.getBoundingBox().inflate(32.0D)
            ).forEach(Mob::discard);
            body.getInventory().clearContent();
            body.inventoryMenu.broadcastChanges();
            body.setDeltaMovement(Vec3.ZERO);
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);

            chestPosition = start.offset(3, 0, 0);
            helper.getLevel().setBlockAndUpdate(
                    chestPosition,
                    Blocks.CHEST.defaultBlockState()
            );
            final ChestBlockEntity chest = chest();
            chest.clearContent();
            chest.setItem(
                    0,
                    new ItemStack(
                            Items.OAK_PLANKS,
                            FIXTURE_PLANK_COUNT
                    )
            );
            chest.setChanged();
        }

        private boolean latestObservationContainsClosedChest() {
            final Optional<String> semantic =
                    runtime.observations().latestSemanticJson();
            if (semantic.isEmpty()) {
                return false;
            }
            try {
                final JsonObject root = JsonParser
                        .parseString(semantic.orElseThrow())
                        .getAsJsonObject();
                if (root.has("openMenu")) {
                    return false;
                }
                final var faces =
                        root.getAsJsonArray("visibleBlockFaces");
                if (faces == null) {
                    return false;
                }
                for (var element : faces) {
                    final JsonObject face =
                            element.getAsJsonObject();
                    final JsonObject block =
                            face.getAsJsonObject("block");
                    if ("minecraft:chest".equals(
                            face.get("type").getAsString()
                    )
                            && block != null
                            && block.get("x").getAsInt()
                                == chestPosition.getX()
                            && block.get("y").getAsInt()
                                == chestPosition.getY()
                            && block.get("z").getAsInt()
                                == chestPosition.getZ()) {
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException malformedObservation) {
                return false;
            }
        }

        private boolean latestObservationContainsTransferablePlanks() {
            final Optional<String> semantic =
                    runtime.observations().latestSemanticJson();
            if (semantic.isEmpty()) {
                return false;
            }
            try {
                final JsonObject root = JsonParser
                        .parseString(semantic.orElseThrow())
                        .getAsJsonObject();
                final JsonObject menu =
                        root.getAsJsonObject("openMenu");
                if (menu == null
                        || !"minecraft:generic_9x3".equals(
                            menu.get("type").getAsString()
                        )) {
                    return false;
                }
                boolean source = false;
                boolean destination = false;
                for (var element : menu.getAsJsonArray("slots")) {
                    final JsonObject slot =
                            element.getAsJsonObject();
                    final String location =
                            slot.get("location").getAsString();
                    final String item =
                            slot.get("item").getAsString();
                    final int count =
                            slot.get("count").getAsInt();
                    source |= location.equals("MENU")
                            && item.equals("minecraft:oak_planks")
                            && count == FIXTURE_PLANK_COUNT;
                    destination |= location.equals("PLAYER")
                            && item.equals("minecraft:air")
                            && count == 0;
                }
                return source && destination;
            } catch (RuntimeException malformedObservation) {
                return false;
            }
        }

        private void observeActiveSkill() {
            final var skill = runtime.skillSupervisor().snapshot();
            if (skill.boundGoalRevision() != withdrawalGoalRevision
                    || skill.state()
                        != dev.mcai.companion.skill.SkillSupervisor
                            .State.RUNNING) {
                return;
            }
            sawUseBlockSkill |= skill.skillName().equals("use_block");
            sawTransferSkill |= skill.skillName().equals(
                    "transfer_menu_item"
            );
        }

        private ChestBlockEntity chest() {
            return (ChestBlockEntity) Optional.ofNullable(
                    helper.getLevel().getBlockEntity(chestPosition)
            ).filter(ChestBlockEntity.class::isInstance)
                    .orElseThrow(() ->
                            helper.assertionException(
                                    "Fixture chest block entity is missing"
                            )
                    );
        }

        private void assertNoHumanPlayers() {
            final long humans = runtime.server()
                    .getPlayerList()
                    .getPlayers()
                    .stream()
                    .filter(player -> !player.getUUID().equals(
                            runtime.worldData().companionUuid()
                    ))
                    .count();
            helper.assertTrue(
                    humans == 0L,
                    "Container task retained " + humans
                        + " human player(s) after the command"
            );
        }

        private void assertWithinModelDeadline(final String message) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private String diagnostics() {
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            final String bodySummary = bodyCandidate
                    .map(body -> "menu="
                            + body.containerMenu.getClass().getSimpleName()
                            + ", ownedPlanks="
                            + body.getInventory().countItem(Items.OAK_PLANKS))
                    .orElse("offline");
            return "supervisor=" + runtime.skillSupervisor().snapshot()
                    + ", goal=" + runtime.goals().snapshot()
                    + ", body=" + bodySummary
                    + ", sawUseBlock=" + sawUseBlockSkill
                    + ", sawTransfer=" + sawTransferSkill
                    + ", chestPlanks="
                    + (chestPosition == null
                        ? "not-created"
                        : chest().countItem(Items.OAK_PLANKS));
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            AiPlayerManager.onlinePlayer(runtime.server()).ifPresent(
                    player -> {
                        if (player.containerMenu != player.inventoryMenu) {
                            player.closeContainer();
                        }
                    }
            );
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum ContainerWithdrawalStage {
        BODY,
        PROBE,
        VISIBLE,
        GOAL,
        OPEN,
        MENU_VISIBLE,
        TRANSFER,
        DONE
    }

    private static final class LiveCombatScenario {
        private static final float ZOMBIE_START_HEALTH = 8.0F;

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;
        private final boolean stopResume;

        private CombatStage stage = CombatStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private Mob zombie;
        private long stageStartedNanos;
        private long goalRevisionBefore;
        private long stopGoalRevisionBefore;
        private long resumeGoalRevisionBefore;
        private long resumeGoalRevision;
        private boolean sawCombatSkill;
        private boolean threatAiActivated;
        private boolean stopSubmitted;
        private boolean resumeSubmitted;
        private boolean sameBodyAfterStop;
        private boolean resumeCombatSkillStarted;

        private LiveCombatScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this(helper, runtime, false);
        }

        private LiveCombatScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final boolean stopResume
        ) {
            this.helper = helper;
            this.runtime = runtime;
            this.stopResume = stopResume;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Live-combat companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case EQUIPMENT -> waitForEquipment();
                case THREAT_VISIBLE -> waitForThreatVisibility();
                case GOAL -> waitForGoal();
                case COMBAT -> waitForCombat();
                case STOP -> waitForStop();
                case RESUME_GOAL -> waitForResumeGoal();
                case RESUME_COMBAT -> waitForResumeCombat();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status =
                    AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Live-combat companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Live-combat companion body timed out"
                );
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            prepareArenaAndOwnedEquipment(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = CombatStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "Live combat model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            stage = CombatStage.EQUIPMENT;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForEquipment() {
            final Optional<ServerPlayer> bodySnapshot = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodySnapshot.isEmpty()) {
                helper.assertTrue(
                        helper.getTick() - createdAt <= BODY_TIMEOUT_TICKS,
                        "Companion body disappeared during initial anchor"
                );
                return;
            }
            final ServerPlayer body = bodySnapshot.orElseThrow();
            if (!fullyEquipped(body)) {
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= java.time.Duration.ofSeconds(15)
                                .toNanos(),
                        "Owned armor, shield and sword were not equipped "
                            + "through the inventory controller: "
                            + equipmentDiagnostic(body, runtime)
                );
                return;
            }
            if (stopResume && humanSession == null) {
                /*
                 * Complete the one-time no-human startup anchor before a
                 * hostile exists.  Otherwise the emergency lane correctly
                 * owns the body and the fair lifecycle defers the re-login,
                 * which would make the later stop/resume request look stale.
                 */
                humanSession = PlacedHuman.create(
                        helper,
                        runtime,
                        body.position().add(2.0D, 0.0D, 0.0D)
                );
                helper.assertTrue(
                        CompanionCommandAccess.mayAdmin(
                                humanSession.player()
                                        .createCommandSourceStack()
                        ),
                        "Logged-in combat test player lacked task-write "
                                + "permission"
                );
            }
            if (stopResume && runtime.worldData().bodyNeedsInitialAnchor()) {
                return;
            }
            spawnThreat(body);
            stage = CombatStage.THREAT_VISIBLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForThreatVisibility() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    zombie.getEyePosition()
            );
            body.setYHeadRot(body.getYRot());
            if (!latestObservationContainsZombie()) {
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= java.time.Duration.ofSeconds(15)
                                .toNanos(),
                        "The companion's fair first-person semantic "
                            + "observation never exposed the visible Zombie"
                );
                return;
            }
            /*
             * Use the same safe, nearby login path as a real client. The
             * no-human startup lifecycle intentionally anchors the first
             * human beside the body; placing this fixture at the vanilla
             * origin could trigger a legitimate initial-anchor replacement
             * into an unloaded or unsafe area before the chat packet was
             * delivered, making the test report a missing model response
             * instead of exercising combat. Stop/resume waits for that
             * one-time re-login to finish before measuring a second model
             * request, so a body-session revision cannot masquerade as a
             * player interruption.
             */
            if (humanSession == null) {
                humanSession = PlacedHuman.create(
                        helper,
                        runtime,
                        body.position().add(2.0D, 0.0D, 0.0D)
                );
                final ServerPlayer human = humanSession.player();
                helper.assertTrue(
                        CompanionCommandAccess.mayAdmin(
                                human.createCommandSourceStack()
                        ),
                        "Logged-in combat test player lacked task-write "
                            + "permission"
                );
            }
            goalRevisionBefore = runtime.goals()
                    .snapshot()
                    .revision();
            final String command = """
                    %s，保护好自己，使用现有装备击败你面前的僵尸。
                    """.formatted(
                            runtime.worldData().displayName()
                    ).strip();
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            humanSession.player(),
                            Component.literal(command)
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the combat chat command"
            );
            if (!stopResume) {
                humanSession.close();
            }
            stage = CombatStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            assertWithinModelDeadline(
                    "Live model did not classify the combat task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.goal().contains("僵尸"),
                    "Authorized combat chat was not preserved as the goal"
            );
            stage = CombatStage.COMBAT;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForCombat() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died during the controlled Zombie defense"
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if ("engage_observed_entity".equals(
                    skill.skillName()
            )) {
                sawCombatSkill = true;
                if (!threatAiActivated) {
                    zombie.setNoAi(false);
                    threatAiActivated = true;
                }
            }
            final boolean defeated = zombie == null
                    || zombie.isRemoved()
                    || !zombie.isAlive();
            if (defeated) {
                helper.assertTrue(
                        sawCombatSkill,
                        "Zombie disappeared without the verified combat "
                            + "controller becoming active"
                );
                helper.assertTrue(
                        fullyEquipped(body),
                        "Companion discarded equipped protection during "
                            + "combat"
                );
                stage = CombatStage.DONE;
                helper.succeed();
                return;
            }
            if (stopResume && sawCombatSkill && !stopSubmitted) {
                /*
                 * Stop while the ordinary melee skill owns the body. Freeze
                 * only the authored threat for this interruption window so
                 * the assertion measures cancellation, not a race between
                 * stop chat and ambient mob movement.
                 */
                zombie.setNoAi(true);
                stopGoalRevisionBefore = runtime.goals()
                        .snapshot()
                        .revision();
                submitPlayerChat("stop");
                stopSubmitted = true;
                stage = CombatStage.STOP;
                stageStartedNanos = System.nanoTime();
                return;
            }
            if (System.nanoTime() - stageStartedNanos
                    <= MODEL_TIMEOUT_NANOS) {
                final GoalSnapshot goal = runtime.goals().snapshot();
                if (goal.status() != GoalStatus.RUNNING
                        && goal.status() != GoalStatus.CANCEL_PENDING
                        && !defeated) {
                    helper.assertTrue(
                            false,
                            "Live combat goal became terminal before the "
                                + "Zombie was defeated: goal=" + goal
                                + ", skill=" + skill.skillName()
                                + ", lastStartRejection="
                                + skill.lastStartRejection()
                    );
                }
                return;
            }
            helper.assertTrue(
                    false,
                    "Live combat did not defeat the Zombie before the "
                        + "wall-clock deadline: zombieHealth="
                        + zombie.getHealth()
                        + ", bodyHealth=" + body.getHealth()
                        + ", skill=" + skill.skillName()
                        + ", lastStartRejection="
                        + skill.lastStartRejection()
            );
        }

        private void waitForStop() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died while processing the combat stop"
            );
            final SkillSupervisor.Snapshot skill = runtime
                    .skillSupervisor()
                    .snapshot();
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.status() == GoalStatus.SAFE_IDLE
                    && runtime.skillSupervisor().snapshot().state()
                        != SkillSupervisor.State.RUNNING
                    && runtime.skillSupervisor().snapshot().state()
                        != SkillSupervisor.State.CANCEL_PENDING) {
                sameBodyAfterStop = body.getUUID().equals(
                        runtime.worldData().companionUuid()
                );
                helper.assertTrue(
                        sameBodyAfterStop,
                        "Combat stop replaced the ServerPlayer body"
                );
                resumeGoalRevisionBefore = goal.revision();
                submitPlayerChat(
                        "继续保护自己，重新击败面前的僵尸。"
                );
                resumeSubmitted = true;
                stage = CombatStage.RESUME_GOAL;
                stageStartedNanos = System.nanoTime();
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                            <= MODEL_TIMEOUT_NANOS,
                    "Combat stop did not reach a safe idle checkpoint: goal="
                            + goal + ", skill=" + skill
            );
        }

        private void waitForResumeGoal() {
            assertWithinModelDeadline(
                    "Live model did not classify the resumed combat task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == resumeGoalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > resumeGoalRevisionBefore
                            && goal.goal().contains("僵尸"),
                    "Resumed combat chat was not installed as a fresh goal: "
                            + goal
            );
            resumeGoalRevision = goal.revision();
            stage = CombatStage.RESUME_COMBAT;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForResumeCombat() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died after resuming combat"
            );
            final var skill = runtime.skillSupervisor().snapshot();
            final boolean activeResumeSkill =
                    "engage_observed_entity".equals(skill.skillName())
                    && (skill.state() == SkillSupervisor.State.RUNNING
                        || skill.state()
                            == SkillSupervisor.State.CANCEL_PENDING)
                    && skill.boundGoalRevision() == resumeGoalRevision;
            if (activeResumeSkill) {
                resumeCombatSkillStarted = true;
                zombie.setNoAi(false);
            }
            final boolean defeated = zombie == null
                    || zombie.isRemoved()
                    || !zombie.isAlive();
            if (defeated) {
                helper.assertTrue(
                        sameBodyAfterStop,
                        "Resumed combat did not retain the original body"
                );
                helper.assertTrue(
                        resumeCombatSkillStarted,
                        "Resumed combat never reacquired the melee skill; "
                                + "skill=" + skill
                );
                stage = CombatStage.DONE;
                helper.succeed();
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                            <= MODEL_TIMEOUT_NANOS,
                    "Resumed combat did not defeat the Zombie: health="
                            + zombie.getHealth() + ", skill=" + skill
            );
        }

        private void submitPlayerChat(final String message) {
            if (humanSession == null
                    || humanSession.player().isRemoved()) {
                final ServerPlayer body = AiPlayerManager
                        .onlinePlayer(runtime.server())
                        .orElseThrow();
                humanSession = PlacedHuman.create(
                        helper,
                        runtime,
                        body.position().add(2.0D, 0.0D, 0.0D)
                );
            }
            final Component submitted = ForgeHooks
                    .onServerChatSubmittedEvent(
                            humanSession.player(),
                            Component.literal(message)
                    );
            helper.assertTrue(
                    submitted != null,
                    "Combat interruption chat was cancelled: " + message
            );
        }

        private void prepareArenaAndOwnedEquipment(
                final ServerPlayer body
        ) {
            final BlockPos start = body.blockPosition();
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 10; dz++) {
                    final BlockPos floor =
                            start.offset(dx, -1, dz);
                    helper.getLevel().setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int dy = 1; dy <= 4; dy++) {
                        helper.getLevel().setBlockAndUpdate(
                                floor.above(dy),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            body.getInventory().clearContent();
            body.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            body.getInventory().setItem(
                    9,
                    new ItemStack(Items.IRON_HELMET)
            );
            body.getInventory().setItem(
                    10,
                    new ItemStack(Items.IRON_CHESTPLATE)
            );
            body.getInventory().setItem(
                    11,
                    new ItemStack(Items.IRON_LEGGINGS)
            );
            body.getInventory().setItem(
                    12,
                    new ItemStack(Items.IRON_BOOTS)
            );
            body.getInventory().setItem(
                    13,
                    new ItemStack(Items.SHIELD)
            );
            body.getInventory().setItem(
                    14,
                    new ItemStack(Items.IRON_SWORD)
            );
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.getFoodData().setSaturation(5.0F);
            body.setDeltaMovement(Vec3.ZERO);
            body.inventoryMenu.broadcastChanges();
        }

        private void spawnThreat(final ServerPlayer body) {
            zombie = EntityTypes.ZOMBIE.create(
                            helper.getLevel(),
                            EntitySpawnReason.COMMAND
                    );
            helper.assertTrue(
                    zombie != null,
                    "GameTest could not create a Zombie"
            );
            zombie.setPos(body.position().add(0.0, 0.0, 10.0));
            zombie.setHealth(ZOMBIE_START_HEALTH);
            // Freeze the threat only while waiting for a model decision.
            // This keeps it in the NPC's real view without feeding hidden
            // entity data to the planner. The normal Zombie AI is restored
            // as soon as the combat controller actually starts.
            zombie.setNoAi(true);
            zombie.setItemSlot(
                    EquipmentSlot.HEAD,
                    new ItemStack(Items.IRON_HELMET)
            );
            zombie.setPersistenceRequired();
            helper.assertTrue(
                    helper.getLevel().addFreshEntity(zombie),
                    "GameTest could not add the Zombie"
            );
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    zombie.getEyePosition()
            );
            body.setYHeadRot(body.getYRot());
        }

        private boolean latestObservationContainsZombie() {
            final Optional<String> semanticJson =
                    runtime.observations().latestSemanticJson();
            if (semanticJson.isEmpty()) {
                return false;
            }
            try {
                final var root = JsonParser
                        .parseString(semanticJson.orElseThrow())
                        .getAsJsonObject();
                final var visible =
                        root.getAsJsonArray("visibleEntities");
                if (visible == null) {
                    return false;
                }
                for (var element : visible) {
                    final var entity = element.getAsJsonObject();
                    if (entity.has("type")
                            && "minecraft:zombie".equals(
                                entity.get("type").getAsString()
                            )) {
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException malformedObservation) {
                return false;
            }
        }

        private static boolean fullyEquipped(
                final ServerPlayer body
        ) {
            return body.getItemBySlot(EquipmentSlot.HEAD)
                        .is(Items.IRON_HELMET)
                    && body.getItemBySlot(EquipmentSlot.CHEST)
                        .is(Items.IRON_CHESTPLATE)
                    && body.getItemBySlot(EquipmentSlot.LEGS)
                        .is(Items.IRON_LEGGINGS)
                    && body.getItemBySlot(EquipmentSlot.FEET)
                        .is(Items.IRON_BOOTS)
                    && body.getOffhandItem().is(Items.SHIELD)
                    && body.getMainHandItem().is(Items.IRON_SWORD);
        }

        private static String equipmentDiagnostic(
                final ServerPlayer body,
                final ServerRuntime runtime
        ) {
            return "head=" + body.getItemBySlot(EquipmentSlot.HEAD)
                    + ", chest="
                    + body.getItemBySlot(EquipmentSlot.CHEST)
                    + ", legs="
                    + body.getItemBySlot(EquipmentSlot.LEGS)
                    + ", feet="
                    + body.getItemBySlot(EquipmentSlot.FEET)
                    + ", offhand=" + body.getOffhandItem()
                    + ", mainhand=" + body.getMainHandItem()
                    + ", carried="
                    + body.inventoryMenu.getCarried()
                    + ", onGround=" + body.onGround()
                    + ", position=" + body.position()
                    + ", survival="
                    + runtime.survival().state()
                    + ", skill="
                    + runtime.skillSupervisor().snapshot();
        }

        private void assertWithinModelDeadline(
                final String message
        ) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (zombie != null && zombie.isAlive()) {
                zombie.discard();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private static final class LiveIronGolemDuelScenario {
        private static final float GOLEM_START_HEALTH = 40.0F;
        private static final long EQUIPMENT_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(15).toNanos();
        private static final long OBSERVATION_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(15).toNanos();

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;
        private GolemStage stage = GolemStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private Mob golem;
        private ServerPlayer bodyBeforeHumanLogin;
        private long stageStartedTick;
        private long stageStartedNanos;
        private long goalRevisionBefore;
        private boolean golemActivated;
        private boolean sawCombatSkill;
        private boolean bodyWasHit;
        private Vec3 bodyStart;

        private LiveIronGolemDuelScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            createdAt = helper.getTick();
            stageStartedTick = createdAt;
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            finishScenarioGoal(runtime);
            if (AiPlayerManager.status(runtime.server()).state()
                    == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(runtime.server())
                                .accepted(),
                        "Live-golem companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case EQUIPMENT -> waitForEquipment();
                case THREAT_VISIBLE -> waitForThreatVisibility();
                case CHAT -> waitForChatAnchor();
                case COMBAT -> waitForCombat();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Live-golem companion body failed: " + status
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt <= BODY_TIMEOUT_TICKS,
                        "Live-golem companion body timed out"
                );
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            prepareArenaAndEquipment(body);
            probe = probeOrReuseVerifiedModel(runtime);
            changeStage(GolemStage.PROBE);
        }

        private void waitForProbe() {
            assertWithin(
                    "Live-golem model capability probe timed out",
                    MODEL_TIMEOUT_NANOS
            );
            if (!probe.isDone()) {
                return;
            }
            helper.assertTrue(
                    probe.join() instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + probe.join()
            );
            changeStage(GolemStage.EQUIPMENT);
        }

        private void waitForEquipment() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            if (!fullyEquipped(body)) {
                assertWithin(
                        "Owned golem-duel equipment was not equipped: "
                                + equipmentDiagnostic(body),
                        EQUIPMENT_TIMEOUT_NANOS
                );
                return;
            }
            spawnGolem(body);
            changeStage(GolemStage.THREAT_VISIBLE);
        }

        private void waitForThreatVisibility() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    golem.getEyePosition()
            );
            body.setYHeadRot(body.getYRot());
            if (!latestObservationContainsGolem()) {
                assertWithin(
                        "The live first-person frame did not expose the iron golem",
                        OBSERVATION_TIMEOUT_NANOS
                );
                return;
            }
            bodyBeforeHumanLogin = body;
            humanSession = PlacedHuman.create(
                    helper,
                    runtime,
                    body.position().add(2.0D, 0.0D, 0.0D)
            );
            changeStage(GolemStage.CHAT);
        }

        private void waitForChatAnchor() {
            final Optional<ServerPlayer> current = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (current.isEmpty()) {
                assertWithin(
                        "Live-golem body disappeared during human login",
                        MODEL_TIMEOUT_NANOS
                );
                return;
            }
            if (runtime.worldData().bodyNeedsInitialAnchor()
                    && current.orElseThrow() == bodyBeforeHumanLogin) {
                return;
            }
            reanchorGolem(current.orElseThrow());
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Live-golem chat player lacked task-write permission"
            );
            goalRevisionBefore = runtime.goals().snapshot().revision();
            final Component submitted = ForgeHooks
                    .onServerChatSubmittedEvent(
                            human,
                            Component.literal(
                                    "请和你面前的铁傀儡单挑，不要只回复，"
                                            + "要移动、格挡并攻击它。"
                            )
                    );
            helper.assertTrue(
                    submitted != null,
                    "Live-golem chat command was cancelled"
            );
            humanSession.close();
            humanSession = null;
            changeStage(GolemStage.COMBAT);
        }

        private void waitForCombat() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            helper.assertTrue(
                    body.isAlive() && !body.isDeadOrDying(),
                    "Iron-golem duel killed the companion: health="
                            + body.getHealth()
                            + ", survival=" + runtime.survival().state()
            );
            if (body.getHealth() < body.getMaxHealth() - 0.1F) {
                bodyWasHit = true;
            }
            final var skill = runtime.skillSupervisor().snapshot();
            if ("engage_observed_entity".equals(skill.skillName())
                    && skill.state() == SkillSupervisor.State.RUNNING) {
                sawCombatSkill = true;
                if (!golemActivated) {
                    golemActivated = true;
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
                }
            }
            if (body.getHealth() < body.getMaxHealth() - 0.1F
                    || (golem instanceof net.minecraft.world.entity.animal.golem.IronGolem iron
                            && iron.getAttackAnimationTick() > 0)) {
                bodyWasHit = true;
            }
            if (golemActivated && golem.isAlive()) {
                // Keep the authored vanilla duel target stable while the
                // body reacquires the target through ordinary perception.
                golem.setTarget(body);
                golem.setLastHurtByMob(body);
                golem.setAggressive(true);
            }
            final boolean damaged = golem != null
                    && (golem.isRemoved()
                            || !golem.isAlive()
                            || golem.getHealth() < GOLEM_START_HEALTH - 8.0F);
            final boolean moved = bodyStart != null
                    && body.position().distanceTo(bodyStart) >= 0.25D;
            if (sawCombatSkill && golemActivated && damaged && moved
                    && bodyWasHit
                    && helper.getTick() - stageStartedTick >= 120L) {
                dev.mcai.companion.MinecraftAiCompanion.LOGGER.info(
                        "Live iron-golem duel evidence: modelSkill={}, "
                                + "bodyHealth={}, golemHealth={}, "
                                + "golemAttackAnimationTick={}, moved={}, "
                                + "bodyWasHit={}, damaged={}",
                        skill.skillName(),
                        body.getHealth(),
                        golem.getHealth(),
                        golem instanceof net.minecraft.world.entity.animal.golem.IronGolem iron
                                ? iron.getAttackAnimationTick()
                                : -1,
                        moved,
                        bodyWasHit,
                        damaged
                );
                stage = GolemStage.DONE;
                helper.succeed();
                return;
            }
            assertWithin(
                    "Iron-golem duel produced insufficient live evidence: "
                            + "damaged=" + damaged
                            + ", moved=" + moved
                            + ", bodyWasHit=" + bodyWasHit
                            + ", modelSkill=" + skill.skillName()
                            + ", lastStartRejection="
                            + skill.lastStartRejection(),
                    MODEL_TIMEOUT_NANOS
            );
        }

        private void prepareArenaAndEquipment(final ServerPlayer body) {
            final BlockPos start = body.blockPosition();
            /*
             * The live provider is intentionally allowed to report every
             * fair visible mob.  Clear ambient GameTest mobs before the
             * authored golem is spawned so a model response to “duel the
             * iron golem” cannot legally bind an unrelated slime that happens
             * to be in the same first-person sample.  This is fixture hygiene,
             * not a production target filter or hidden-world query.
             */
            helper.getLevel().getEntitiesOfClass(
                    Mob.class,
                    body.getBoundingBox().inflate(40.0D),
                    mob -> true
            ).forEach(Entity::discard);
            // Keep the authored duel deterministic while preserving normal
            // entity AI for the iron golem itself. Natural spawning would
            // otherwise introduce a second fair target during the model's
            // capability probe and chat-to-action latency window.
            helper.getLevel().getGameRules().set(
                    GameRules.SPAWN_MOBS,
                    false,
                    runtime.server()
            );
            helper.getLevel().getGameRules().set(
                    GameRules.SPAWN_MONSTERS,
                    false,
                    runtime.server()
            );
            for (int dx = -8; dx <= 8; dx++) {
                for (int dz = -4; dz <= 12; dz++) {
                    final BlockPos floor = start.offset(dx, -1, dz);
                    helper.getLevel().setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int dy = 0; dy <= 4; dy++) {
                        helper.getLevel().setBlockAndUpdate(
                                floor.above(dy + 1),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            // GameTest servers may default newly logged-in players to
            // creative, and persisted player abilities can outlive an older
            // fixture.  A live combat claim is invalid unless the body is a
            // genuine vanilla survival player that can take and deal damage.
            body.setGameMode(GameType.SURVIVAL);
            body.getAbilities().invulnerable = false;
            body.setInvulnerable(false);
            body.invulnerableTime = 0;
            helper.assertTrue(
                    body.gameMode.getGameModeForPlayer()
                            == GameType.SURVIVAL
                        && !body.getAbilities().instabuild
                        && !body.getAbilities().flying
                        && !body.getAbilities().mayfly
                        && !body.getAbilities().invulnerable,
                    "Live-golem fixture did not enter genuine survival mode"
            );
            body.getInventory().clearContent();
            body.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            body.getInventory().setItem(9, new ItemStack(Items.IRON_HELMET));
            body.getInventory().setItem(
                    10,
                    new ItemStack(Items.IRON_CHESTPLATE)
            );
            body.getInventory().setItem(
                    11,
                    new ItemStack(Items.IRON_LEGGINGS)
            );
            body.getInventory().setItem(12, new ItemStack(Items.IRON_BOOTS));
            body.getInventory().setItem(13, new ItemStack(Items.SHIELD));
            body.getInventory().setItem(14, new ItemStack(Items.IRON_SWORD));
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.getFoodData().setSaturation(5.0F);
            body.setDeltaMovement(Vec3.ZERO);
            body.setYRot(0.0F);
            body.setYHeadRot(0.0F);
            body.setXRot(0.0F);
            body.inventoryMenu.broadcastChanges();
        }

        private void spawnGolem(final ServerPlayer body) {
            golem = EntityTypes.IRON_GOLEM.create(
                    helper.getLevel(),
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(golem != null, "Could not create iron golem");
            golem.setPos(body.getX(), body.getY(), body.getZ() + 2.5D);
            golem.setHealth(GOLEM_START_HEALTH);
            golem.setPersistenceRequired();
            golem.setNoAi(true);
            golem.setTarget(null);
            helper.assertTrue(
                    helper.getLevel().addFreshEntity(golem),
                    "Could not add iron golem"
            );
        }

        private void reanchorGolem(final ServerPlayer body) {
            golem.setPos(body.getX(), body.getY(), body.getZ() + 2.5D);
            golem.setNoAi(true);
            golem.setTarget(null);
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    golem.getEyePosition()
            );
            body.setYHeadRot(body.getYRot());
            bodyStart = body.position();
        }

        private boolean latestObservationContainsGolem() {
            final Optional<String> semantic = runtime.observations()
                    .latestSemanticJson();
            if (semantic.isEmpty()) {
                return false;
            }
            try {
                final var visible = JsonParser.parseString(
                        semantic.orElseThrow()
                ).getAsJsonObject().getAsJsonArray("visibleEntities");
                if (visible == null) {
                    return false;
                }
                for (var element : visible) {
                    if ("minecraft:iron_golem".equals(
                            element.getAsJsonObject().get("type")
                                    .getAsString()
                    )) {
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException malformedObservation) {
                return false;
            }
        }

        private static boolean fullyEquipped(final ServerPlayer body) {
            return body.getItemBySlot(EquipmentSlot.HEAD)
                        .is(Items.IRON_HELMET)
                    && body.getItemBySlot(EquipmentSlot.CHEST)
                        .is(Items.IRON_CHESTPLATE)
                    && body.getItemBySlot(EquipmentSlot.LEGS)
                        .is(Items.IRON_LEGGINGS)
                    && body.getItemBySlot(EquipmentSlot.FEET)
                        .is(Items.IRON_BOOTS)
                    && body.getOffhandItem().is(Items.SHIELD)
                    && body.getMainHandItem().is(Items.IRON_SWORD);
        }

        private String equipmentDiagnostic(final ServerPlayer body) {
            return "head=" + body.getItemBySlot(EquipmentSlot.HEAD)
                    + ", chest=" + body.getItemBySlot(EquipmentSlot.CHEST)
                    + ", legs=" + body.getItemBySlot(EquipmentSlot.LEGS)
                    + ", feet=" + body.getItemBySlot(EquipmentSlot.FEET)
                    + ", offhand=" + body.getOffhandItem()
                    + ", mainhand=" + body.getMainHandItem();
        }

        private void changeStage(final GolemStage next) {
            stage = next;
            stageStartedTick = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void assertWithin(final String message, final long timeout) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos <= timeout,
                    message
            );
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (golem != null && !golem.isRemoved()) {
                golem.discard();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private static final class LiveHordeCombatScenario {
        private static final long EQUIPMENT_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(15).toNanos();
        private static final long OBSERVATION_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(15).toNanos();

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final int targetCount;
        private final int minimumDamagedTargets;
        private final double targetRadius;
        private final String requestMessage;
        private final long createdAt;
        private final List<Mob> targets = new ArrayList<>();
        private final Map<UUID, Float> targetBaselineHealth = new HashMap<>();
        private final Set<UUID> damagedTargetIds = new HashSet<>();
        private HordeStage stage = HordeStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private ServerPlayer bodyBeforeHumanLogin;
        private long goalRevisionBefore;
        private long stageStartedTick;
        private long stageStartedNanos;
        private boolean hostilesActivated;
        private boolean sawCombatSkill;
        private long modelCombatStartTick = -1L;
        private String lastModelCombatSkill = "";
        private final Map<UUID, Float> combatStartHealth = new HashMap<>();
        private boolean combatObservationLogged;
        private boolean targetsReanchored;
        private Vec3 bodyStart;

        private LiveHordeCombatScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this(
                    helper,
                    runtime,
                    6,
                    3,
                    4.5D,
                    "请马上保护我，击退你面前所有的僵尸和骷髅，"
                            + "不要只回复，要移动、格挡并攻击。"
            );
        }

        private LiveHordeCombatScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final int targetCount,
                final int minimumDamagedTargets,
                final double targetRadius,
                final String requestMessage
        ) {
            this.helper = helper;
            this.runtime = runtime;
            if (targetCount < 2 || targetCount % 2 != 0) {
                throw new IllegalArgumentException(
                        "targetCount must be a positive even number"
                );
            }
            if (minimumDamagedTargets < 1
                    || minimumDamagedTargets > targetCount) {
                throw new IllegalArgumentException(
                        "minimumDamagedTargets must be in target range"
                );
            }
            if (!(targetRadius > 0.0D)
                    || !Double.isFinite(targetRadius)) {
                throw new IllegalArgumentException(
                        "targetRadius must be finite and positive"
                );
            }
            this.targetCount = targetCount;
            this.minimumDamagedTargets = minimumDamagedTargets;
            this.targetRadius = targetRadius;
            this.requestMessage = Objects.requireNonNull(
                    requestMessage,
                    "requestMessage"
            );
            createdAt = helper.getTick();
            stageStartedTick = createdAt;
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            finishScenarioGoal(runtime);
            if (AiPlayerManager.status(runtime.server()).state()
                    == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(runtime.server())
                                .accepted(),
                        "Live-horde companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case EQUIPMENT -> waitForEquipment();
                case THREAT_VISIBLE -> waitForThreatVisibility();
                case CHAT -> waitForChatAnchor();
                case COMBAT -> waitForCombat();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Live-horde companion body failed: " + status
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                                <= BODY_TIMEOUT_TICKS,
                        "Live-horde companion body timed out"
                );
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            prepareArenaAndEquipment(body);
            probe = probeOrReuseVerifiedModel(runtime);
            changeStage(HordeStage.PROBE);
        }

        private void waitForProbe() {
            assertWithin(
                    "Live-horde model capability probe timed out",
                    MODEL_TIMEOUT_NANOS
            );
            if (!probe.isDone()) {
                return;
            }
            helper.assertTrue(
                    probe.join() instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + probe.join()
            );
            changeStage(HordeStage.EQUIPMENT);
        }

        private void waitForEquipment() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            if (!fullyEquipped(body)) {
                assertWithin(
                        "Owned combat equipment was not equipped: "
                                + equipmentDiagnostic(body),
                        EQUIPMENT_TIMEOUT_NANOS
                );
                return;
            }
            spawnTargets(body);
            bodyStart = body.position();
            changeStage(HordeStage.THREAT_VISIBLE);
        }

        private void waitForThreatVisibility() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    body.position().add(0.0D, 1.0D, 4.0D)
            );
            body.setYHeadRot(body.getYRot());
            if (visibleHostileCount() < 3) {
                assertWithin(
                        "The live first-person frame did not expose the "
                                + "bounded hostile group",
                        OBSERVATION_TIMEOUT_NANOS
                );
                return;
            }
            captureTargetBaseline();
            dev.mcai.companion.MinecraftAiCompanion.LOGGER.info(
                    "Live horde authored target baseline: targetCount={}, "
                            + "health={}, visibleEntities={}",
                    targets.size(),
                    targetBaselineHealth,
                    visibleEntityTypes()
            );
            bodyBeforeHumanLogin = body;
            humanSession = PlacedHuman.create(
                    helper,
                    runtime,
                    body.position().add(2.0D, 0.0D, 0.0D)
            );
            changeStage(HordeStage.CHAT);
        }

        private void waitForChatAnchor() {
            final Optional<ServerPlayer> current = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (current.isEmpty()) {
                assertWithin(
                        "Live-horde body disappeared during human login",
                        MODEL_TIMEOUT_NANOS
                );
                return;
            }
            if (runtime.worldData().bodyNeedsInitialAnchor()
                    && current.orElseThrow() == bodyBeforeHumanLogin) {
                return;
            }
            if (!targetsReanchored) {
                /*
                 * A real PlayerList login can force the headless body through
                 * its remove/relogin lifecycle.  On a GameTest server that
                 * transition may unload the authored mob instances together
                 * with the old player ticket.  Recreate the bounded authored
                 * group only after the replacement body is stable, still
                 * before the chat command is submitted.  This keeps the
                 * causal test honest without relying on a hidden target or a
                 * post-command world mutation.
                 */
                targets.forEach(target -> {
                    if (target != null && !target.isRemoved()) {
                        target.discard();
                    }
                });
                targets.clear();
                spawnTargets(current.orElseThrow());
                reanchorTargets(current.orElseThrow());
                captureTargetBaseline();
                runtime.observations().requestObservation(
                        new RequestedObservation(
                                ObservationKind.SEMANTIC_REFRESH,
                                "horde_reanchor"
                        )
                );
                targetsReanchored = true;
            }
            bodyStart = current.orElseThrow().position();
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Live-horde chat player lacked task-write permission"
            );
            goalRevisionBefore = runtime.goals().snapshot().revision();
            final Component submitted = ForgeHooks
                    .onServerChatSubmittedEvent(
                            human,
                            Component.literal(
                                    requestMessage
                            )
                    );
            helper.assertTrue(
                    submitted != null,
                    "Live-horde chat command was cancelled"
            );
            humanSession.close();
            humanSession = null;
            changeStage(HordeStage.COMBAT);
        }

        private void waitForCombat() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            helper.assertTrue(
                    body.isAlive() && !body.isDeadOrDying(),
                    "Live-horde pressure killed the companion: health="
                            + body.getHealth()
                            + ", survival=" + runtime.survival().state()
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if (!combatObservationLogged) {
                combatObservationLogged = true;
                dev.mcai.companion.MinecraftAiCompanion.LOGGER.info(
                        "Live horde combat observation after chat: "
                                + "visibleEntities={}, targets={}, supervisor={}",
                        visibleEntityTypes(),
                        targetDiagnostic(body),
                        skill
                );
            }
            /*
             * A requested skill name is not evidence that the local skill
             * actually started: a stale/invalid observation can be rejected
             * before the supervisor enters RUNNING.  Keep the authored
             * hostile AI frozen until the accepted RUNNING edge, otherwise
             * vanilla target damage can make a speech-only model response
             * look like combat.
             */
            if ("engage_observed_entity".equals(skill.skillName())
                    && skill.state() == SkillSupervisor.State.RUNNING) {
                sawCombatSkill = true;
                lastModelCombatSkill = skill.skillName();
                if (!hostilesActivated) {
                    hostilesActivated = true;
                    modelCombatStartTick = helper.getTick();
                    combatStartHealth.clear();
                    for (Mob target : targets) {
                        if (!target.isRemoved() && target.isAlive()) {
                            combatStartHealth.put(
                                    target.getUUID(),
                                    target.getHealth()
                            );
                        }
                    }
                    for (Mob target : targets) {
                        if (!target.isRemoved() && target.isAlive()) {
                            target.setNoAi(false);
                            target.setTarget(body);
                        }
                    }
                }
            }
            updateDamageEvidence();
            final long damagedTargets = damagedTargetIds.size();
            final boolean moved = bodyStart != null
                    && body.position().distanceTo(bodyStart) >= 0.25D;
            if (sawCombatSkill
                    && hostilesActivated
                    && damagedTargets >= minimumDamagedTargets
                    && moved
                    && helper.getTick() - stageStartedTick >= 120L) {
                dev.mcai.companion.MinecraftAiCompanion.LOGGER.info(
                        "Live horde evidence: modelSkill={}, "
                                + "damagedTargets={}, targetCount={}, "
                                + "moved={}, bodyHealth={}",
                        lastModelCombatSkill,
                        damagedTargets,
                        targets.size(),
                        moved,
                        body.getHealth()
                );
                stage = HordeStage.DONE;
                helper.succeed();
                return;
            }
            assertWithin(
                    "Live-horde combat produced insufficient evidence: "
                            + "damagedTargets=" + damagedTargets
                            + ", moved=" + moved
                            + ", modelSkill=" + lastModelCombatSkill
                            + ", modelCombatStartTick="
                            + modelCombatStartTick
                            + ", lastStartRejection="
                            + skill.lastStartRejection()
                            + ", targetBaseline=" + targetBaselineHealth
                            + ", damagedTargetIds=" + damagedTargetIds,
                    HORDE_MODEL_TIMEOUT_NANOS
            );
        }

        private void captureTargetBaseline() {
            targetBaselineHealth.clear();
            damagedTargetIds.clear();
            for (Mob target : targets) {
                if (!target.isRemoved() && target.isAlive()) {
                    target.setHealth(target.getMaxHealth());
                    targetBaselineHealth.put(
                            target.getUUID(),
                            target.getMaxHealth()
                    );
                }
            }
            helper.assertTrue(
                    targetBaselineHealth.size() == targets.size(),
                    "Live-horde target baseline was not fully initialized: "
                            + targetBaselineHealth
            );
        }

        private void updateDamageEvidence() {
            if (!hostilesActivated) {
                return;
            }
            for (Mob target : targets) {
                Float baseline = combatStartHealth.get(target.getUUID());
                if (baseline == null) {
                    continue;
                }
                if (target.getHealth() < baseline - 0.1F) {
                    damagedTargetIds.add(target.getUUID());
                }
            }
        }

        private String visibleEntityTypes() {
            Optional<String> semantic = runtime.observations()
                    .latestSemanticJson();
            if (semantic.isEmpty()) {
                return "unavailable";
            }
            try {
                var visible = JsonParser.parseString(semantic.orElseThrow())
                        .getAsJsonObject()
                        .getAsJsonArray("visibleEntities");
                if (visible == null) {
                    return "none";
                }
                List<String> types = new ArrayList<>();
                visible.forEach(element -> {
                    JsonObject entity = element.getAsJsonObject();
                    types.add(
                            entity.get("observationId").getAsString()
                                    + "="
                                    + entity.get("type").getAsString()
                    );
                });
                return types.toString();
            } catch (RuntimeException malformed) {
                return "malformed";
            }
        }

        private String targetDiagnostic(final ServerPlayer body) {
            return targets.stream()
                    .map(target -> target.getType().toString()
                            + ":alive=" + target.isAlive()
                            + ":removed=" + target.isRemoved()
                            + ":distance=" + String.format(
                                    Locale.ROOT,
                                    "%.2f",
                                    target.distanceTo(body)
                            )
                            + ":pos=" + target.blockPosition())
                    .toList()
                    .toString();
        }

        private void prepareArenaAndEquipment(final ServerPlayer body) {
            final BlockPos start = body.blockPosition();
            for (int dx = -8; dx <= 8; dx++) {
                for (int dz = -4; dz <= 12; dz++) {
                    final BlockPos floor = start.offset(dx, -1, dz);
                    helper.getLevel().setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int dy = 0; dy <= 4; dy++) {
                        helper.getLevel().setBlockAndUpdate(
                                floor.above(dy + 1),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            // A persistent GameTest world may contain an ambient mob outside
            // this fixture's authored target list. Remove those entities so
            // the model cannot legally select an unrelated hostile and still
            // satisfy a target-damage assertion for this scenario.
            helper.getLevel().getEntitiesOfClass(
                    Entity.class,
                    body.getBoundingBox().inflate(48.0D)
            ).forEach(entity -> {
                if (entity != body) {
                    entity.discard();
                }
            });
            // Freeze natural spawning for the bounded pressure arena. The
            // authored Zombies/Skeletons still use their ordinary AI after
            // activation; an ambient spawn must not become a hidden third
            // target class during the model observation window.
            helper.getLevel().getGameRules().set(
                    GameRules.SPAWN_MOBS,
                    false,
                    runtime.server()
            );
            helper.getLevel().getGameRules().set(
                    GameRules.SPAWN_MONSTERS,
                    false,
                    runtime.server()
            );
            // Do not let a creative default or stale fixture ability turn a
            // hostile-pressure run into a damage-free movement demo.
            body.setGameMode(GameType.SURVIVAL);
            body.getAbilities().invulnerable = false;
            body.setInvulnerable(false);
            body.invulnerableTime = 0;
            helper.assertTrue(
                    body.gameMode.getGameModeForPlayer()
                            == GameType.SURVIVAL
                        && !body.getAbilities().instabuild
                        && !body.getAbilities().flying
                        && !body.getAbilities().mayfly
                        && !body.getAbilities().invulnerable,
                    "Live-horde fixture did not enter genuine survival mode"
            );
            body.getInventory().clearContent();
            body.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            body.getInventory().setItem(9, new ItemStack(Items.IRON_HELMET));
            body.getInventory().setItem(
                    10,
                    new ItemStack(Items.IRON_CHESTPLATE)
            );
            body.getInventory().setItem(
                    11,
                    new ItemStack(Items.IRON_LEGGINGS)
            );
            body.getInventory().setItem(12, new ItemStack(Items.IRON_BOOTS));
            body.getInventory().setItem(13, new ItemStack(Items.SHIELD));
            body.getInventory().setItem(14, new ItemStack(Items.IRON_SWORD));
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.getFoodData().setSaturation(5.0F);
            body.setDeltaMovement(Vec3.ZERO);
            body.setYRot(0.0F);
            body.setYHeadRot(0.0F);
            body.setXRot(0.0F);
            body.inventoryMenu.broadcastChanges();
        }

        private void spawnTargets(final ServerPlayer body) {
            final Level level = helper.getLevel();
            final double radius = targetRadius;
            for (int index = 0; index < targetCount; index++) {
                final boolean skeleton = index >= targetCount / 2;
                final Mob target = (skeleton
                        ? EntityTypes.SKELETON
                        : EntityTypes.ZOMBIE).create(
                                level,
                                EntitySpawnReason.COMMAND
                        );
                helper.assertTrue(
                        target != null,
                        "Could not create live-horde target " + index
                );
                final double angle = Math.toRadians(-50.0D + index * 20.0D);
                target.setPos(
                        body.getX() + radius * Math.sin(angle),
                        body.getY(),
                        body.getZ() + radius * Math.cos(angle)
                );
                target.setPersistenceRequired();
                target.setNoAi(true);
                helper.assertTrue(
                        level.addFreshEntity(target),
                        "Could not add live-horde target " + index
                );
                targets.add(target);
            }
        }

        private void reanchorTargets(final ServerPlayer body) {
            final double radius = targetRadius;
            for (int index = 0; index < targets.size(); index++) {
                final double angle = Math.toRadians(-50.0D + index * 20.0D);
                final Mob target = targets.get(index);
                target.setPos(
                        body.getX() + radius * Math.sin(angle),
                        body.getY(),
                        body.getZ() + radius * Math.cos(angle)
                );
                target.setNoAi(true);
                target.setTarget(null);
            }
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    body.position().add(0.0D, 1.0D, 4.0D)
            );
            body.setYHeadRot(body.getYRot());
        }

        private int visibleHostileCount() {
            final Optional<String> semantic = runtime.observations()
                    .latestSemanticJson();
            if (semantic.isEmpty()) {
                return 0;
            }
            try {
                final JsonObject root = JsonParser
                        .parseString(semantic.orElseThrow())
                        .getAsJsonObject();
                final var visible = root.getAsJsonArray("visibleEntities");
                if (visible == null) {
                    return 0;
                }
                int count = 0;
                for (var element : visible) {
                    final String type = element.getAsJsonObject()
                            .get("type").getAsString();
                    if ("minecraft:zombie".equals(type)
                            || "minecraft:skeleton".equals(type)) {
                        count++;
                    }
                }
                return count;
            } catch (RuntimeException malformed) {
                return 0;
            }
        }

        private static boolean fullyEquipped(final ServerPlayer body) {
            return body.getItemBySlot(EquipmentSlot.HEAD)
                        .is(Items.IRON_HELMET)
                    && body.getItemBySlot(EquipmentSlot.CHEST)
                        .is(Items.IRON_CHESTPLATE)
                    && body.getItemBySlot(EquipmentSlot.LEGS)
                        .is(Items.IRON_LEGGINGS)
                    && body.getItemBySlot(EquipmentSlot.FEET)
                        .is(Items.IRON_BOOTS)
                    && body.getOffhandItem().is(Items.SHIELD)
                    && body.getMainHandItem().is(Items.IRON_SWORD);
        }

        private String equipmentDiagnostic(final ServerPlayer body) {
            return "head=" + body.getItemBySlot(EquipmentSlot.HEAD)
                    + ", chest=" + body.getItemBySlot(EquipmentSlot.CHEST)
                    + ", legs=" + body.getItemBySlot(EquipmentSlot.LEGS)
                    + ", feet=" + body.getItemBySlot(EquipmentSlot.FEET)
                    + ", offhand=" + body.getOffhandItem()
                    + ", mainhand=" + body.getMainHandItem();
        }

        private void changeStage(final HordeStage next) {
            stage = next;
            stageStartedTick = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void assertWithin(final String message, final long timeout) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos <= timeout,
                    message
            );
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            targets.forEach(target -> {
                if (target != null && !target.isRemoved()) {
                    target.discard();
                }
            });
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum GolemStage {
        BODY,
        PROBE,
        EQUIPMENT,
        THREAT_VISIBLE,
        CHAT,
        COMBAT,
        DONE
    }

    private enum HordeStage {
        BODY,
        PROBE,
        EQUIPMENT,
        THREAT_VISIBLE,
        CHAT,
        COMBAT,
        DONE
    }

    private enum CombatStage {
        BODY,
        PROBE,
        EQUIPMENT,
        THREAT_VISIBLE,
        GOAL,
        COMBAT,
        STOP,
        RESUME_GOAL,
        RESUME_COMBAT,
        DONE
    }

    private static final class SurpriseZombieScenario {
        private static final int REACTION_DEADLINE_TICKS = 12;
        private static final double REQUIRED_DISPLACEMENT = 0.35D;
        private static final float MAXIMUM_YAW_STEP = 30.0F;
        private static final float MAXIMUM_PITCH_STEP = 22.5F;

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;
        private long stageStartedNanos;

        private SurpriseStage stage = SurpriseStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private CompletableFuture<Optional<MemoryEvent>> usageRead;
        private PlacedHuman humanSession;
        private ServerPlayer bodyBeforeHumanLogin;
        private Mob zombie;
        private Instant commandAt;
        private long commandGoalRevision;
        private long contactTick;
        private Vec3 startPosition;
        private float priorYaw;
        private float priorPitch;
        private float maximumYawStep;
        private float maximumPitchStep;
        private double maximumDisplacement;
        private boolean reacted;
        private boolean modelResponded;
        private long arbiterBaselineTick = -1L;
        private boolean emergencyLaneClaimed;

        private SurpriseZombieScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            finishScenarioGoal(runtime);
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Surprise-Zombie companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case COMBAT -> waitForCombat();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Surprise-Zombie companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                                <= BODY_TIMEOUT_TICKS,
                        "Surprise-Zombie companion body timed out"
                );
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            prepareArena(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = SurpriseStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                            instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            if (humanSession == null) {
                final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                        .onlinePlayer(runtime.server());
                if (bodyCandidate.isEmpty()) {
                    assertWithinModelDeadline(
                            "Surprise-Zombie body disappeared before human "
                                + "warning"
                    );
                    return;
                }
                bodyBeforeHumanLogin = bodyCandidate.orElseThrow();
                humanSession = PlacedHuman.create(
                        helper,
                        runtime,
                        bodyBeforeHumanLogin.position()
                                .add(2.0D, 0.0D, 0.0D)
                );
                return;
            }
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                assertWithinModelDeadline(
                        "Surprise-Zombie body disappeared during initial "
                            + "anchor reconciliation"
                );
                return;
            }
            /*
             * The first real human login may replace the zero-human body
             * through the normal PlayerList remove/relogin anchor
             * transaction. Never spawn the hostile or retain observation
             * state against that stale ServerPlayer reference.
             */
            if (bodyBeforeHumanLogin != null
                    && runtime.worldData().bodyNeedsInitialAnchor()
                    && bodyCandidate.orElseThrow() == bodyBeforeHumanLogin) {
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            final long before = runtime.goals().snapshot().revision();
            commandAt = Instant.now();
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            humanSession.player(),
                            Component.literal(
                                    "小心，你后面有僵尸，保护好自己！"
                            )
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the surprise warning"
            );
            final GoalSnapshot installed = runtime.goals().snapshot();
            helper.assertTrue(
                    installed.revision() > before
                            && installed.goal().contains("僵尸"),
                    "Obvious threat warning was not installed in the "
                            + "same chat tick"
            );
            commandGoalRevision = installed.revision();
            humanSession.close();
            humanSession = null;
            arbiterBaselineTick = runtime.behaviorArbiter()
                    .latest()
                    .map(BehaviorArbiter.Resolution::serverTick)
                    .orElse(-1L);
            spawnThreatBehind(body);
            startPosition = body.position();
            priorYaw = body.getYRot();
            priorPitch = body.getXRot();
            contactTick = helper.getTick();
            stage = SurpriseStage.COMBAT;
            stageStartedNanos = System.nanoTime();
        }

        private void assertWithinModelDeadline(final String message) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private void waitForCombat() {
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                assertWithinModelDeadline(
                        "Surprise-Zombie body disappeared during defense"
                );
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            helper.assertTrue(
                    body.isAlive() && !body.isDeadOrDying(),
                    "Companion stared at the surprise Zombie until death"
            );
            final float yawStep = wrappedDegrees(
                    body.getYRot() - priorYaw
            );
            final float pitchStep = Math.abs(
                    body.getXRot() - priorPitch
            );
            maximumYawStep = Math.max(maximumYawStep, yawStep);
            maximumPitchStep = Math.max(
                    maximumPitchStep,
                    pitchStep
            );
            priorYaw = body.getYRot();
            priorPitch = body.getXRot();

            final double displacement = Math.sqrt(
                    body.position().distanceToSqr(startPosition)
            );
            maximumDisplacement = Math.max(
                    maximumDisplacement,
                    displacement
            );
            final boolean attacked = zombie == null
                    || zombie.isRemoved()
                    || zombie.getHealth() < zombie.getMaxHealth();
            final boolean turned = wrappedDegrees(
                    body.getYRot()
            ) >= 8.0F;
            reacted |= displacement >= 0.10D
                    || attacked
                    || turned
                    || body.isUsingItem();
            runtime.behaviorArbiter().latest()
                    .filter(resolution ->
                            resolution.serverTick()
                                    > arbiterBaselineTick)
                    .filter(resolution ->
                            resolution.claimedBy(
                                BehaviorArbiter.Lane
                                    .EMERGENCY_SURVIVAL
                            ))
                    .ifPresent(resolution -> {
                        helper.assertTrue(
                                resolution.attempted().equals(
                                    List.of(
                                        BehaviorArbiter.Lane
                                            .EMERGENCY_SURVIVAL
                                    )
                                ),
                                "A lower body-authoring lane ran after "
                                    + "emergency ownership: "
                                    + resolution
                        );
                        emergencyLaneClaimed = true;
                    });
            if (!reacted
                    && helper.getTick() - contactTick
                            > REACTION_DEADLINE_TICKS) {
                helper.assertTrue(
                        false,
                        "No physical defense, retreat, turn or attack "
                                + "occurred within "
                                + REACTION_DEADLINE_TICKS
                                + " ticks of a rear hostile; survival="
                                + runtime.survival().state()
                                + ", modelReady="
                                + runtime.model()
                                    .snapshot().gatewayReady()
                                + ", skill="
                                + runtime.skillSupervisor().snapshot()
                                + ", coreFrame="
                                + runtime.coreFrames().current()
                                    .map(frame -> "dangers="
                                        + frame.dangerSignals()
                                        + ", position="
                                        + frame.position()
                                        + ", look="
                                        + frame.lookDirection())
                                    .orElse("unavailable")
                                + ", coreLease="
                                + runtime.coreActions().snapshot()
                                + ", arbiter="
                                + runtime.behaviorArbiter().latest()
                );
            }
            pollModelUsage();

            final boolean defeated = zombie == null
                    || zombie.isRemoved()
                    || !zombie.isAlive();
            if (defeated && modelResponded) {
                helper.assertTrue(
                        emergencyLaneClaimed,
                        "The hostile was defeated without proving that "
                                + "the production emergency lane owned "
                                + "the body after contact; latest="
                                + runtime.behaviorArbiter().latest()
                );
                helper.assertTrue(
                        maximumDisplacement >= REQUIRED_DISPLACEMENT,
                        "The open-arena defense never used meaningful "
                                + "movement: displacement="
                                + maximumDisplacement
                );
                helper.assertTrue(
                        maximumYawStep <= MAXIMUM_YAW_STEP,
                        "Head yaw snapped unnaturally by "
                                + maximumYawStep + " degrees in one tick"
                );
                helper.assertTrue(
                        maximumPitchStep <= MAXIMUM_PITCH_STEP,
                        "Head pitch snapped/nodded unnaturally by "
                                + maximumPitchStep
                                + " degrees in one tick"
                );
                stage = SurpriseStage.DONE;
                helper.succeed();
                return;
            }
            helper.assertTrue(
                    java.time.Duration.between(
                            commandAt,
                            Instant.now()
                    ).compareTo(
                            java.time.Duration.ofSeconds(45)
                    ) <= 0,
                    "Surprise-Zombie defense timed out: zombieHealth="
                            + (zombie == null
                                ? "removed"
                                : zombie.getHealth())
                            + ", zombieTickCount="
                            + (zombie == null
                                ? "removed"
                                : zombie.tickCount)
                            + ", zombieInvulnerableTicks="
                            + (zombie == null
                                ? "removed"
                                : zombie.invulnerableTime)
                            + ", bodyHealth=" + body.getHealth()
                            + ", bodyTickCount=" + body.tickCount
                            + ", displacement="
                            + maximumDisplacement
                            + ", modelResponded=" + modelResponded
                            + ", emergencyLaneClaimed="
                            + emergencyLaneClaimed
                            + ", arbiter="
                            + runtime.behaviorArbiter().latest()
                            + ", survival="
                            + runtime.survival().state()
                            + ", skill="
                            + runtime.skillSupervisor().snapshot()
            );
        }

        private void pollModelUsage() {
            if (modelResponded) {
                return;
            }
            if (usageRead == null) {
                usageRead = runtime.memory().latestEvent(
                        "brain_model_usage"
                );
                return;
            }
            if (!usageRead.isDone()) {
                return;
            }
            final Optional<MemoryEvent> found = usageRead.join();
            usageRead = null;
            modelResponded = found
                    .filter(event -> isGameplayPlannerUsage(
                            event,
                            commandAt,
                            commandGoalRevision
                    ))
                    .isPresent();
        }

        private void prepareArena(final ServerPlayer body) {
            final BlockPos start = body.blockPosition();
            for (int dx = -10; dx <= 10; dx++) {
                for (int dz = -10; dz <= 10; dz++) {
                    final BlockPos floor = start.offset(dx, -1, dz);
                    helper.getLevel().setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int dy = 0; dy <= 4; dy++) {
                        helper.getLevel().setBlockAndUpdate(
                                floor.above(dy + 1),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            body.getInventory().clearContent();
            body.setGameMode(GameType.SURVIVAL);
            body.setItemSlot(
                    EquipmentSlot.MAINHAND,
                    new ItemStack(Items.IRON_SWORD)
            );
            body.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.getFoodData().setSaturation(5.0F);
            body.setDeltaMovement(Vec3.ZERO);
            body.setYRot(0.0F);
            body.setYHeadRot(0.0F);
            body.setXRot(0.0F);
            body.inventoryMenu.broadcastChanges();
        }

        private void spawnThreatBehind(final ServerPlayer body) {
            zombie = EntityTypes.ZOMBIE.create(
                    helper.getLevel(),
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                    zombie != null,
                    "GameTest could not create the surprise Zombie"
            );
            zombie.setPos(body.position().add(0.0D, 0.0D, -1.55D));
            zombie.setItemSlot(
                    EquipmentSlot.HEAD,
                    new ItemStack(Items.IRON_HELMET)
            );
            zombie.setPersistenceRequired();
            zombie.setTarget(body);
            helper.assertTrue(
                    helper.getLevel().addFreshEntity(zombie),
                    "GameTest could not add the surprise Zombie"
            );
        }

        private static float wrappedDegrees(final float value) {
            return Math.abs((float) Math.IEEEremainder(value, 360.0D));
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (zombie != null && !zombie.isRemoved()) {
                zombie.discard();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum SurpriseStage {
        BODY,
        PROBE,
        COMBAT,
        DONE
    }

    private static final class CriticalGoldenAppleScenario {
        private static final int USE_START_DEADLINE_TICKS = 12;

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;

        private GoldenAppleStage stage = GoldenAppleStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private CompletableFuture<Optional<MemoryEvent>> usageRead;
        private CompletableFuture<Optional<MemoryEvent>> speechRead;
        private PlacedHuman humanSession;
        private ServerPlayer bodyBeforeHumanLogin;
        private ItemEntity gift;
        private Instant commandAt;
        private long commandGoalRevision;
        private long commandTick;
        private int baselineUseStat;
        private boolean sawUseStart;
        private boolean consumed;
        private boolean modelResponded;
        private boolean speechChecked;
        private long arbiterBaselineTick = -1L;
        private boolean emergencyLaneClaimed;

        private CriticalGoldenAppleScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            createdAt = helper.getTick();
        }

        private void start() {
            finishScenarioGoal(runtime);
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Golden-apple companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case CONSUME -> waitForConsumption();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Golden-apple companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                                <= BODY_TIMEOUT_TICKS,
                        "Golden-apple companion body timed out"
                );
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            prepareBody(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = GoldenAppleStage.PROBE;
        }

        private void waitForProbe() {
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                            instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            if (humanSession == null) {
                final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                        .onlinePlayer(runtime.server());
                if (bodyCandidate.isEmpty()) {
                    return;
                }
                bodyBeforeHumanLogin = bodyCandidate.orElseThrow();
                humanSession = PlacedHuman.create(
                        helper,
                        runtime,
                        bodyBeforeHumanLogin.position().add(1.5D, 0.0D, 0.0D)
                );
                return;
            }
            final Optional<ServerPlayer> currentBody = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (currentBody.isEmpty()) {
                return;
            }
            /*
             * A zero-human startup body is reconciled through a normal
             * remove/relogin when this first human appears.  Do not drop the
             * gift onto the stale object that is still referenced by the
             * previous tick; wait for the authoritative replacement before
             * touching inventory or issuing chat.
             */
            if (runtime.worldData().bodyNeedsInitialAnchor()
                    && currentBody.orElseThrow() == bodyBeforeHumanLogin) {
                return;
            }
            final ServerPlayer body = currentBody.orElseThrow();
            physicallyGiveApple(body, humanSession.player());
            helper.assertTrue(
                    body.getInventory().countItem(
                            Items.GOLDEN_APPLE
                    ) == 1,
                    "The companion did not acquire the player's dropped "
                            + "golden apple through vanilla pickup"
            );
            arbiterBaselineTick = runtime.behaviorArbiter()
                    .latest()
                    .map(BehaviorArbiter.Resolution::serverTick)
                    .orElse(-1L);
            body.setHealth(4.0F);
            baselineUseStat = body.getStats().getValue(
                    Stats.ITEM_USED.get(Items.GOLDEN_APPLE)
            );
            final long before = runtime.goals().snapshot().revision();
            commandAt = Instant.now();
            commandTick = helper.getTick();
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            humanSession.player(),
                            Component.literal("给你了，快吃吧！")
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the golden-apple chat command"
            );
            final GoalSnapshot installed = runtime.goals().snapshot();
            helper.assertTrue(
                    installed.revision() > before
                            && installed.goal().contains("吃"),
                    "The direct eat command was not installed in the "
                            + "same chat tick"
            );
            commandGoalRevision = installed.revision();
            humanSession.close();
            humanSession = null;
            stage = GoldenAppleStage.CONSUME;
        }

        private void waitForConsumption() {
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                helper.assertTrue(
                        java.time.Duration.between(
                                commandAt,
                                Instant.now()
                        ).compareTo(
                                java.time.Duration.ofSeconds(30)
                        ) <= 0,
                        "Companion body disappeared during the golden-apple "
                            + "transaction"
                );
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            helper.assertTrue(
                    body.isAlive() && !body.isDeadOrDying(),
                    "Companion died instead of using its critical "
                            + "golden apple"
            );
            sawUseStart |= body.isUsingItem()
                    && body.getUseItem().is(Items.GOLDEN_APPLE);
            runtime.behaviorArbiter().latest()
                    .filter(resolution ->
                            resolution.serverTick()
                                    > arbiterBaselineTick)
                    .filter(resolution ->
                            resolution.claimedBy(
                                BehaviorArbiter.Lane
                                    .EMERGENCY_SURVIVAL
                            ))
                    .ifPresent(resolution -> {
                        helper.assertTrue(
                                resolution.attempted().equals(
                                    List.of(
                                        BehaviorArbiter.Lane
                                            .EMERGENCY_SURVIVAL
                                    )
                                ),
                                "A lower body-authoring lane ran while "
                                    + "the critical-health lane owned the "
                                    + "body: " + resolution
                        );
                        emergencyLaneClaimed = true;
                    });
            if (!sawUseStart
                    && helper.getTick() - commandTick
                            > USE_START_DEADLINE_TICKS) {
                helper.assertTrue(
                        false,
                        "Companion did not begin the real golden-apple "
                                + "use action within "
                                + USE_START_DEADLINE_TICKS + " ticks"
                );
            }
            consumed |= body.getStats().getValue(
                    Stats.ITEM_USED.get(Items.GOLDEN_APPLE)
            ) > baselineUseStat
                    && body.getInventory().countItem(
                            Items.GOLDEN_APPLE
                    ) == 0
                    && body.getAbsorptionAmount() > 0.0F;
            pollModelUsage();
            pollSpeech();
            if (consumed && modelResponded && speechChecked) {
                helper.assertTrue(
                        emergencyLaneClaimed,
                        "The golden apple was consumed without proving "
                            + "production emergency-lane ownership; "
                            + "latest="
                            + runtime.behaviorArbiter().latest()
                );
                stage = GoldenAppleStage.DONE;
                helper.succeed();
                return;
            }
            helper.assertTrue(
                    java.time.Duration.between(
                            commandAt,
                            Instant.now()
                    ).compareTo(
                            java.time.Duration.ofSeconds(30)
                    ) <= 0,
                    "Critical golden-apple transaction timed out: "
                            + "using=" + body.isUsingItem()
                            + ", appleCount="
                            + body.getInventory().countItem(
                                Items.GOLDEN_APPLE
                            )
                            + ", useStat="
                            + body.getStats().getValue(
                                Stats.ITEM_USED.get(
                                    Items.GOLDEN_APPLE
                                )
                            )
                            + ", absorption="
                            + body.getAbsorptionAmount()
                            + ", modelResponded=" + modelResponded
                            + ", emergencyLaneClaimed="
                            + emergencyLaneClaimed
                            + ", arbiter="
                            + runtime.behaviorArbiter().latest()
            );
        }

        private void physicallyGiveApple(
                final ServerPlayer body,
                final ServerPlayer human
        ) {
            human.setItemSlot(
                    EquipmentSlot.MAINHAND,
                    new ItemStack(Items.GOLDEN_APPLE)
            );
            final ItemStack transferred =
                    human.getMainHandItem().copy();
            human.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            gift = human.drop(transferred, true);
            helper.assertTrue(
                    gift != null,
                    "Logged-in player could not drop the golden apple"
            );
            gift.setNoPickUpDelay();
            gift.setPos(body.position());
            gift.playerTouch(body);
            body.inventoryMenu.broadcastChanges();
            helper.assertTrue(
                    human.getInventory().countItem(
                            Items.GOLDEN_APPLE
                    ) == 0,
                    "The player retained a duplicate golden apple"
            );
        }

        private void pollModelUsage() {
            if (modelResponded) {
                return;
            }
            if (usageRead == null) {
                usageRead = runtime.memory().latestEvent(
                        "brain_model_usage"
                );
                return;
            }
            if (!usageRead.isDone()) {
                return;
            }
            final Optional<MemoryEvent> found = usageRead.join();
            usageRead = null;
            modelResponded = found
                    .filter(event -> isGameplayPlannerUsage(
                            event,
                            commandAt,
                            commandGoalRevision
                    ))
                    .isPresent();
        }

        private void pollSpeech() {
            if (speechChecked) {
                return;
            }
            if (speechRead == null) {
                speechRead = runtime.memory().latestEvent(
                        "brain_speech"
                );
                return;
            }
            if (!speechRead.isDone()) {
                return;
            }
            final Optional<MemoryEvent> found = speechRead.join();
            speechRead = null;
            if (found.isEmpty()
                    || found.orElseThrow().occurredAt()
                        .isBefore(commandAt)) {
                return;
            }
            final JsonObject payload = JsonParser.parseString(
                    found.orElseThrow().payloadJson()
            ).getAsJsonObject();
            final String speech = payload.get("message").getAsString();
            helper.assertTrue(
                    !speech.contains("浪费")
                            && !speech.contains("再低")
                            && !speech.contains("留着")
                            && !speech.toLowerCase(Locale.ROOT)
                                .contains("wait until"),
                    "Companion repeated the unsafe golden-apple advice: "
                            + speech
            );
            speechChecked = true;
        }

        private void prepareBody(final ServerPlayer body) {
            final BlockPos start = body.blockPosition();
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    final BlockPos floor = start.offset(dx, -1, dz);
                    helper.getLevel().setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    helper.getLevel().setBlockAndUpdate(
                            floor.above(),
                            Blocks.AIR.defaultBlockState()
                    );
                    helper.getLevel().setBlockAndUpdate(
                            floor.above(2),
                            Blocks.AIR.defaultBlockState()
                    );
                }
            }
            /*
             * GameTest players default to creative. A creative player gains
             * the golden-apple effects and ITEM_USED statistic but retains
             * the stack, which made this supposedly survival transaction
             * wait forever for a count decrease after the real use had
             * already completed.
             */
            body.setGameMode(GameType.SURVIVAL);
            helper.assertTrue(
                    body.gameMode.getGameModeForPlayer()
                            == GameType.SURVIVAL
                        && !body.getAbilities().instabuild
                        && !body.getAbilities().invulnerable,
                    "Golden-apple fixture did not enter genuine "
                        + "survival mode"
            );
            body.getInventory().clearContent();
            body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.getFoodData().setSaturation(5.0F);
            body.setDeltaMovement(Vec3.ZERO);
            body.inventoryMenu.broadcastChanges();
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (gift != null && !gift.isRemoved()) {
                gift.discard();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum GoldenAppleStage {
        BODY,
        PROBE,
        CONSUME,
        DONE
    }

    private static final class LiveParkourScenario {
        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;

        private ParkourStage stage = ParkourStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private BlockPos course;
        private long stageStartedNanos;
        private long goalRevisionBefore;
        private int stableTicks;
        private int initialJumpStat;
        private boolean sawParkourSkill;

        private LiveParkourScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Live-parkour companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case SETTLE -> waitForSettlement();
                case GOAL -> waitForGoal();
                case RUN -> waitForRun();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status =
                    AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Live-parkour companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Live-parkour companion body timed out"
                );
                return;
            }
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                // The first human login can legitimately create a short
                // remove/relogin window while the initial anchor is moved.
                // Do not turn that lifecycle gap into a model failure.
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            prepareCourse(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = ParkourStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "Live parkour model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            stage = ParkourStage.SETTLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForSettlement() {
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            final double expectedY = course.getY() + 1.0;
            if (!body.onGround()
                    || Math.abs(body.getY() - expectedY) > 0.08) {
                stableTicks = 0;
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= java.time.Duration.ofSeconds(15)
                                .toNanos(),
                        "Live parkour body did not settle on its real "
                            + "starting platform"
                );
                return;
            }
            final Vec3 destination = new Vec3(
                    course.getX() + 0.5,
                    expectedY,
                    course.getZ() + 7.5
            );
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    destination
            );
            body.setYHeadRot(body.getYRot());
            if (++stableTicks < 8
                    || !latestObservationMatchesBody(body)) {
                return;
            }
            initialJumpStat = body.getStats().getValue(
                    Stats.CUSTOM.get(Stats.JUMP)
            );
            humanSession = PlacedHuman.create(helper, runtime);
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in parkour test player lacked task-write "
                        + "permission"
            );
            goalRevisionBefore = runtime.goals()
                    .snapshot()
                    .revision();
            final String command = """
                    %s，跑过你正前方连续三个一格宽的缺口，到最远端平台。
                    目标平台中心是 %.1f %.1f %.1f；不要搭方块，直接跑酷过去。
                    """.formatted(
                            runtime.worldData().displayName(),
                            course.getX() + 0.5,
                            course.getY() + 1.0,
                            course.getZ() + 7.5
                    ).strip();
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            human,
                            Component.literal(command)
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the parkour chat command"
            );
            humanSession.close();
            stage = ParkourStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            assertWithinModelDeadline(
                    "Live model did not classify the parkour task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.goal().contains("跑酷"),
                    "Authorized parkour chat was not preserved as the goal"
            );
            stage = ParkourStage.RUN;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForRun() {
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                // A pending initial-anchor relogin is server-authoritative;
                // wait for the replacement entity instead of failing before
                // the live model has received a usable observation.
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died on the controlled parkour course"
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if ("parkour_to".equals(skill.skillName())) {
                sawParkourSkill = true;
            }
            final boolean arrived = body.getZ()
                    >= course.getZ() + 6.15
                    && body.onGround();
            if (arrived) {
                final int jumps = body.getStats().getValue(
                        Stats.CUSTOM.get(Stats.JUMP)
                    ) - initialJumpStat;
                helper.assertTrue(
                        sawParkourSkill,
                        "Course was crossed without the live-model-selected "
                            + "parkour controller"
                );
                helper.assertTrue(
                        jumps >= 3,
                        "Live parkour completed without three vanilla "
                            + "jumps: " + jumps
                );
                helper.assertTrue(
                        body.getHealth() == body.getMaxHealth(),
                        "Live parkour took avoidable fall damage"
                );
                for (int gap : new int[]{1, 3, 5}) {
                    helper.assertTrue(
                            helper.getLevel().getBlockState(
                                course.offset(0, 0, gap)
                            ).isAir(),
                            "Live parkour modified a gap instead of jumping"
                    );
                }
                stage = ParkourStage.DONE;
                helper.succeed();
                return;
            }
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.status() != GoalStatus.RUNNING
                    && goal.status() != GoalStatus.CANCEL_PENDING) {
                helper.assertTrue(
                        false,
                        "Live parkour goal became terminal before arrival: "
                            + goal + ", skill=" + skill.skillName()
                            + ", rejection="
                            + skill.lastStartRejection()
                );
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    "Live parkour did not reach the final platform: "
                        + "position=" + body.position()
                        + ", skill=" + skill.skillName()
                        + ", rejection="
                        + skill.lastStartRejection()
            );
        }

        private void prepareCourse(final ServerPlayer body) {
            course = body.blockPosition().below();
            final var level = helper.getLevel();
            /*
             * The GameTest superflat can be a slime chunk. Natural mobs are
             * not part of this controller contract and can physically push a
             * body across a gap before the test begins. The environment also
             * disables subsequent natural spawning; remove only pre-existing
             * nearby mobs as deterministic test isolation.
             */
            level.getEntitiesOfClass(
                    Mob.class,
                    body.getBoundingBox().inflate(48.0)
            ).forEach(Mob::discard);
            for (int x = -2; x <= 2; x++) {
                for (int z = -3; z <= 9; z++) {
                    final boolean platform = z <= 0
                            || z == 2
                            || z == 4
                            || z >= 6;
                    level.setBlockAndUpdate(
                            course.offset(x, 0, z),
                            platform
                                    ? Blocks.SMOOTH_STONE
                                        .defaultBlockState()
                                    : Blocks.AIR.defaultBlockState()
                    );
                    for (int y = 1; y <= 5; y++) {
                        level.setBlockAndUpdate(
                                course.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                    level.setBlockAndUpdate(
                            course.offset(x, -4, z),
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                }
            }
            body.teleportTo(
                    course.getX() + 0.5,
                    course.getY() + 1.0,
                    course.getZ() - 1.5
            );
            body.setDeltaMovement(Vec3.ZERO);
            body.fallDistance = 0.0F;
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
        }

        private boolean latestObservationMatchesBody(
                final ServerPlayer body
        ) {
            final Optional<String> semantic =
                    runtime.observations().latestSemanticJson();
            if (semantic.isEmpty()) {
                return false;
            }
            try {
                final var self = JsonParser
                        .parseString(semantic.orElseThrow())
                        .getAsJsonObject()
                        .getAsJsonObject("self");
                if (self == null
                        || !self.get("onGround").getAsBoolean()) {
                    return false;
                }
                final var position =
                        self.getAsJsonObject("position");
                return Math.abs(
                            position.get("x").getAsDouble()
                                - body.getX()
                        ) <= 0.35
                        && Math.abs(
                            position.get("y").getAsDouble()
                                - body.getY()
                        ) <= 0.20
                        && Math.abs(
                            position.get("z").getAsDouble()
                                - body.getZ()
                        ) <= 0.35;
            } catch (RuntimeException malformedObservation) {
                return false;
            }
        }

        private void assertWithinModelDeadline(
                final String message
        ) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum ParkourStage {
        BODY,
        PROBE,
        SETTLE,
        GOAL,
        RUN,
        DONE
    }

    private static final class LiveWaterClutchScenario {
        private static final int MAXIMUM_FALL_TICKS = 180;

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;

        private WaterClutchStage stage = WaterClutchStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private CompletableFuture<Optional<MemoryEvent>> usageRead;
        private PlacedHuman humanSession;
        private BlockPos landing;
        private long stageStartedNanos;
        private long goalRevisionBefore;
        private long waterClutchGoalRevision;
        private long fallStartedAt;
        private long arbiterBaselineTick = -1L;
        private int waterBucketUseStart;
        private double maximumFallDistance;
        private boolean observedDescent;
        private boolean modelResponded;
        private boolean sawEmergencyController;
        private boolean sawEmergencyArbiter;
        private boolean sawWaterBucketInMainHand;
        private int stableTicks;
        private Instant commandAt;

        private LiveWaterClutchScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Live-water-clutch companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case SETTLE -> waitForSettlement();
                case GOAL -> waitForGoal();
                case MODEL -> waitForModelDecision();
                case FALL -> waitForFall();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status =
                    AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Live-water-clutch companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Live-water-clutch companion body timed out"
                );
                return;
            }
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                // Initial-anchor relogin is a normal server lifecycle window.
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            prepareFixture(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = WaterClutchStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "Live water-clutch model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            stage = WaterClutchStage.SETTLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForSettlement() {
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            final double expectedY = landing.getY() + 1.0;
            if (!body.onGround()
                    || Math.abs(body.getY() - expectedY) > 0.08) {
                stableTicks = 0;
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= java.time.Duration.ofSeconds(15)
                                .toNanos(),
                        "Live water-clutch body did not settle"
                );
                return;
            }
            if (++stableTicks < 8
                    || !latestObservationMatchesBody(body)) {
                return;
            }
            // Keep the sender on the prepared solid landing platform. The
            // default (0,0,0) login position can fall in the flat test world
            // while the async model conversation is in flight, which would
            // remove the anchor and strand the AI relogin.
            humanSession = PlacedHuman.create(
                    helper,
                    runtime,
                    new Vec3(
                            landing.getX() + 0.5,
                            landing.getY() + 1.0,
                            landing.getZ() + 0.5
                    )
            );
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in water-clutch test player lacked "
                        + "task-write permission"
            );
            goalRevisionBefore = runtime.goals()
                    .snapshot()
                    .revision();
            final String command = """
                    %s，做一次落地水自救训练。我把你放到高处后，
                    测试装置会自动让你下落；请用背包里的水桶在落地前
                    放水保命。无需我再次确认，不要使用命令。
                    """.formatted(
                            runtime.worldData().displayName()
                    ).strip();
            commandAt = Instant.now();
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            human,
                            Component.literal(command)
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the water-clutch chat command"
            );
            stage = WaterClutchStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            assertWithinModelDeadline(
                    "Live model did not classify the water-clutch task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.goal().contains("落地水"),
                    "Authorized water-clutch chat was not preserved "
                        + "as the goal"
            );
            // Keep the physical sender online until the asynchronous
            // conversation coordinator has committed the goal. Closing the
            // embedded connection in the same tick as chat can otherwise
            // discard the model request before it is accepted.
            if (humanSession != null) {
                humanSession.close();
                humanSession = null;
            }
            waterClutchGoalRevision = goal.revision();
            stage = WaterClutchStage.MODEL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForModelDecision() {
            assertWithinModelDeadline(
                    "Live model did not answer the water-clutch goal"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            helper.assertTrue(
                    goal.revision() == waterClutchGoalRevision,
                    "Water-clutch goal revision changed before the live "
                        + "model response: " + goal
            );
            if (usageRead == null) {
                usageRead = runtime.memory().latestEvent(
                        "brain_model_usage"
                );
                return;
            }
            if (!usageRead.isDone()) {
                return;
            }
            final Optional<MemoryEvent> found = usageRead.join();
            usageRead = null;
            modelResponded = found
                    .filter(event -> isGameplayPlannerUsage(
                            event,
                            commandAt,
                            waterClutchGoalRevision
                    ))
                    .isPresent();
            if (!modelResponded) {
                return;
            }
            beginFall();
        }

        private void beginFall() {
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.getInventory().countItem(
                            Items.WATER_BUCKET
                    ) == 1,
                    "Water bucket disappeared before the fall began"
            );
            helper.assertTrue(
                    body.getMainHandItem().isEmpty(),
                    "Water bucket was already equipped before the "
                        + "emergency test"
            );
            waterBucketUseStart = body.getStats().getValue(
                    Stats.ITEM_USED.get(Items.WATER_BUCKET)
            );
            maximumFallDistance = 0.0;
            observedDescent = false;
            sawEmergencyController = false;
            sawEmergencyArbiter = false;
            sawWaterBucketInMainHand = false;
            arbiterBaselineTick = runtime.behaviorArbiter()
                    .latest()
                    .map(BehaviorArbiter.Resolution::serverTick)
                    .orElse(-1L);
            body.teleportTo(
                    landing.getX() + 0.5,
                    landing.getY() + 12.0,
                    landing.getZ() + 0.5
            );
            body.setDeltaMovement(Vec3.ZERO);
            body.fallDistance = 0.0F;
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(landing).add(0.0, 0.5, 0.0)
            );
            body.setYHeadRot(body.getYRot());
            fallStartedAt = helper.getTick();
            stage = WaterClutchStage.FALL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForFall() {
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died before completing the water clutch"
            );
            maximumFallDistance = Math.max(
                    maximumFallDistance,
                    body.fallDistance
            );
            observedDescent |=
                    body.getDeltaMovement().y() < -0.08;
            sawEmergencyController |=
                    runtime.survival().state()
                        != EmergencySurvivalController.State.CLEAR;
            runtime.behaviorArbiter().latest()
                    .filter(resolution ->
                            resolution.serverTick()
                                    > arbiterBaselineTick)
                    .filter(resolution ->
                            resolution.claimedBy(
                                BehaviorArbiter.Lane
                                    .EMERGENCY_SURVIVAL
                            ))
                    .ifPresent(resolution -> {
                        helper.assertTrue(
                                resolution.attempted().equals(
                                    List.of(
                                        BehaviorArbiter.Lane
                                            .EMERGENCY_SURVIVAL
                                    )
                                ),
                                "A lower body-authoring lane ran during "
                                    + "the water-clutch emergency: "
                                    + resolution
                        );
                        sawEmergencyArbiter = true;
                    });
            sawWaterBucketInMainHand |=
                    body.getMainHandItem().is(Items.WATER_BUCKET);

            final boolean waterPlaced = helper.getLevel()
                    .getBlockState(landing.above())
                    .is(Blocks.WATER);
            final boolean bucketEmptied =
                    body.getInventory().countItem(
                            Items.WATER_BUCKET
                    ) == 0
                    && body.getInventory().countItem(Items.BUCKET) == 1;
            if (waterPlaced
                    && bucketEmptied
                    && body.getY() <= landing.getY() + 2.25) {
                helper.assertTrue(
                        body.getHealth() == body.getMaxHealth(),
                        "Live-model water clutch took vanilla fall "
                            + "damage: " + body.getHealth()
                );
                helper.assertTrue(
                        observedDescent
                            && maximumFallDistance >= 7.0F,
                        "Water clutch completed without a material "
                            + "twelve-block fall"
                );
                helper.assertTrue(
                        sawEmergencyController,
                        "Water placement did not pass through the "
                            + "production emergency controller"
                );
                helper.assertTrue(
                        modelResponded && sawEmergencyArbiter,
                        "Water clutch lacked a verified live-model "
                            + "response or exclusive emergency body "
                            + "ownership: modelResponded="
                            + modelResponded + ", arbiter="
                            + runtime.behaviorArbiter().latest()
                );
                helper.assertTrue(
                        sawWaterBucketInMainHand,
                        "Emergency controller did not visibly equip the "
                            + "owned water bucket"
                );
                helper.assertTrue(
                        body.getStats().getValue(
                                Stats.ITEM_USED.get(
                                        Items.WATER_BUCKET
                                )
                        ) - waterBucketUseStart == 1,
                        "Water clutch did not record exactly one "
                            + "vanilla bucket use"
                );
                stage = WaterClutchStage.DONE;
                helper.succeed();
                return;
            }
            helper.assertTrue(
                    helper.getTick() - fallStartedAt
                        <= MAXIMUM_FALL_TICKS,
                    "Production emergency controller failed the "
                        + "live-model water clutch: position="
                        + body.position()
                        + ", velocity=" + body.getDeltaMovement()
                        + ", fallDistance=" + body.fallDistance
                        + ", maximumFallDistance="
                        + maximumFallDistance
                        + ", health=" + body.getHealth()
                        + ", sawEmergency="
                        + sawEmergencyController
                        + ", sawEmergencyArbiter="
                        + sawEmergencyArbiter
                        + ", modelResponded="
                        + modelResponded
                        + ", sawWaterMainHand="
                        + sawWaterBucketInMainHand
                        + ", waterBuckets="
                        + body.getInventory().countItem(
                                Items.WATER_BUCKET
                        )
                        + ", emptyBuckets="
                        + body.getInventory().countItem(Items.BUCKET)
                        + ", survival="
                        + runtime.survival().state()
            );
        }

        private void prepareFixture(final ServerPlayer body) {
            final var level = helper.getLevel();
            landing = body.blockPosition().below()
                    .offset(0, 0, 8);
            /*
             * The dedicated GameTest server normally spawns test players in
             * creative mode. A creative water bucket is not transformed into
             * an empty bucket and creative players do not take ordinary fall
             * damage, so leaving that default in place would make this a
             * false survival test. Exercise the same physics and inventory
             * semantics as the promised survival companion.
             */
            body.setGameMode(GameType.SURVIVAL);
            helper.assertTrue(
                    body.gameMode.getGameModeForPlayer()
                            == GameType.SURVIVAL
                        && !body.getAbilities().instabuild
                        && !body.getAbilities().flying
                        && !body.getAbilities().mayfly
                        && !body.getAbilities().invulnerable,
                    "Live water-clutch fixture did not enter genuine "
                        + "survival mode"
            );
            level.getEntitiesOfClass(
                    Mob.class,
                    body.getBoundingBox().inflate(48.0)
            ).forEach(Mob::discard);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    level.setBlockAndUpdate(
                            landing.offset(x, 0, z),
                            Blocks.SMOOTH_STONE
                                .defaultBlockState()
                    );
                    for (int y = 1; y <= 14; y++) {
                        level.setBlockAndUpdate(
                                landing.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            body.getInventory().clearContent();
            body.getInventory().setItem(
                    8,
                    new ItemStack(Items.WATER_BUCKET)
            );
            body.getInventory().setSelectedSlot(0);
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.fallDistance = 0.0F;
            body.teleportTo(
                    landing.getX() + 0.5,
                    landing.getY() + 1.0,
                    landing.getZ() + 0.5
            );
            body.setDeltaMovement(Vec3.ZERO);
        }

        private boolean latestObservationMatchesBody(
                final ServerPlayer body
        ) {
            final Optional<String> semantic =
                    runtime.observations().latestSemanticJson();
            if (semantic.isEmpty()) {
                return false;
            }
            try {
                final var self = JsonParser
                        .parseString(semantic.orElseThrow())
                        .getAsJsonObject()
                        .getAsJsonObject("self");
                return self != null
                        && self.get("onGround").getAsBoolean()
                        && Math.abs(
                            self.getAsJsonObject("position")
                                .get("z").getAsDouble()
                                - body.getZ()
                        ) <= 0.2;
            } catch (RuntimeException malformedObservation) {
                return false;
            }
        }

        private void assertWithinModelDeadline(
                final String message
        ) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum WaterClutchStage {
        BODY,
        PROBE,
        SETTLE,
        GOAL,
        MODEL,
        FALL,
        DONE
    }

    private static final class LiveFoundationBootstrapScenario {
        private static final int REQUIRED_LOGS = 5;
        private static final int REQUIRED_COBBLESTONE = 3;
        private static final double AUTONOMOUS_WORK_RADIUS = 24.0D;
        private static final long FAST_STONE_TOOLS_DEADLINE_NANOS =
                java.time.Duration.ofSeconds(60).toNanos();
        private static final long FAST_IRON_DEADLINE_NANOS =
                java.time.Duration.ofMinutes(5).toNanos();
        private static final long DISTRIBUTED_STORAGE_DEADLINE_NANOS =
                java.time.Duration.ofMinutes(8).toNanos();
        private static final long STORAGE_AND_DOOR_DEADLINE_NANOS =
                java.time.Duration.ofMinutes(11).toNanos();
        private static final long CONTAINER_DOOR_DEADLINE_NANOS =
                java.time.Duration.ofMinutes(3).toNanos();

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;
        private final boolean zeroHumanFromStart;
        private final boolean completeAfterStoneTools;
        private final boolean completeAfterIron;
        private final boolean completeAfterDistributedStorage;
        private final boolean completeAfterContainerWoodDoor;

        private FoundationBootstrapStage stage =
                FoundationBootstrapStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private List<BlockPos> logs = List.of();
        private List<BlockPos> reserveLogs = List.of();
        private List<BlockPos> stoneBlocks = List.of();
        private List<BlockPos> coalBlocks = List.of();
        private List<BlockPos> ironBlocks = List.of();
        private List<Cow> foodAnimals = List.of();
        private NonOceanFixtureVariant fixtureVariant =
                NonOceanFixtureVariant.OAK_FLAT;
        private Block fixtureWoodBlock = Blocks.OAK_LOG;
        private Item fixtureWoodItem = Items.OAK_LOG;
        private Item fixturePlanksItem = Items.OAK_PLANKS;
        private Block fixtureDoorBlock = Blocks.OAK_DOOR;
        private Item fixtureDoorItem = Items.OAK_DOOR;
        private long stageStartedNanos;
        private long taskSubmittedNanos = -1L;
        private long goalRevisionBefore;
        private long foundationGoalRevision;
        private int stableTicks;
        private int initialMinedLogs;
        private int initialPickedUpLogs;
        private int initialCraftedTables;
        private int initialCraftedWoodenPickaxes;
        private int initialMinedStone;
        private int initialPickedUpCobblestone;
        private int initialCraftedStonePickaxes;
        private int initialMinedCoal;
        private int initialPickedUpCoal;
        private int initialCraftedCharcoal;
        private int initialMinedIron;
        private int initialPickedUpRawIron;
        private int initialCraftedFurnaces;
        private int initialCraftedIronPickaxes;
        private int initialCraftedBuckets;
        private int initialCraftedShields;
        private int initialCraftedChests;
        private int initialCraftedDoors;
        private int initialCraftedTorches;
        private boolean sawClusterGatherer;
        private boolean sawCraftRecipe;
        private boolean sawStoneGatherer;
        private boolean sawStoneCraftRecipe;
        private boolean sawFoodHunt;
        private boolean sawIronToolkit;
        private boolean sawContainerWoodDoor;
        private BlockPos autonomousWorkCenter;
        private ChunkPos autonomousAnchorChunk;
        private List<BlockPos> containerDoorChests = List.of();
        private Map<BlockPos, Integer> containerDoorCounts = Map.of();
        private BlockPos expectedContainerDoor;
        private int containerDoorInitialPlanks;
        private int containerDoorInitialDoors;
        private long containerDoorTaskSubmittedNanos = -1L;

        private LiveFoundationBootstrapScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this(helper, runtime, false, false, false, false);
        }

        private LiveFoundationBootstrapScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final boolean zeroHumanFromStart
        ) {
            this(
                    helper,
                    runtime,
                    zeroHumanFromStart,
                    false,
                    false,
                    false
            );
        }

        private LiveFoundationBootstrapScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final boolean zeroHumanFromStart,
                final boolean completeAfterStoneTools
        ) {
            this(
                    helper,
                    runtime,
                    zeroHumanFromStart,
                    completeAfterStoneTools,
                    false,
                    false
            );
        }

        private LiveFoundationBootstrapScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
            final boolean zeroHumanFromStart,
            final boolean completeAfterStoneTools,
            final boolean completeAfterIron
    ) {
        this(
                helper,
                runtime,
                zeroHumanFromStart,
                completeAfterStoneTools,
                completeAfterIron,
                false
        );
    }

    private LiveFoundationBootstrapScenario(
            final GameTestHelper helper,
            final ServerRuntime runtime,
            final boolean zeroHumanFromStart,
            final boolean completeAfterStoneTools,
            final boolean completeAfterIron,
            final boolean completeAfterDistributedStorage
    ) {
        this(
                helper,
                runtime,
                zeroHumanFromStart,
                completeAfterStoneTools,
                completeAfterIron,
                completeAfterDistributedStorage,
                false
        );
    }

    private LiveFoundationBootstrapScenario(
            final GameTestHelper helper,
            final ServerRuntime runtime,
            final boolean zeroHumanFromStart,
            final boolean completeAfterStoneTools,
            final boolean completeAfterIron,
            final boolean completeAfterDistributedStorage,
            final boolean completeAfterContainerWoodDoor
    ) {
            this.helper = helper;
            this.runtime = runtime;
            this.zeroHumanFromStart = zeroHumanFromStart;
            this.completeAfterStoneTools = completeAfterStoneTools;
            this.completeAfterIron = completeAfterIron;
            this.completeAfterDistributedStorage =
                    completeAfterDistributedStorage;
            this.completeAfterContainerWoodDoor =
                    completeAfterContainerWoodDoor;
            if ((completeAfterStoneTools ? 1 : 0)
                    + (completeAfterIron ? 1 : 0)
                    + (storageTask() ? 1 : 0) > 1) {
                throw new IllegalArgumentException(
                        "Only one bounded terminal may be selected"
                );
            }
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            if (zeroHumanFromStart) {
                assertNoHumanPlayers(
                        "before unattended companion startup"
                );
            }
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(runtime.server())
                                .accepted(),
                        "Live-foundation companion spawn was rejected"
                );
            }
        }

        private boolean storageTask() {
            return completeAfterDistributedStorage
                    || completeAfterContainerWoodDoor;
        }

        private void tick() {
            if (zeroHumanFromStart) {
                assertNoHumanPlayers(
                        "during unattended live-model execution"
                );
            }
            if (humanSession != null) {
                humanSession.tick();
            }
            assertFastTerminalDeadline();
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case SETTLE -> waitForSettlement();
                case GOAL -> waitForGoal();
                case GATHER -> waitForGathering();
                case BASIC_CRAFTING -> waitForBasicCrafting();
                case STONE_GATHERING -> waitForStoneGathering();
                case STONE_CRAFTING -> waitForStoneCrafting();
                case DISTRIBUTED_STORAGE ->
                        waitForDistributedStorage();
                case CONTAINER_DOOR_GOAL ->
                        waitForContainerDoorGoal();
                case CONTAINER_DOOR -> waitForContainerDoor();
                case FOOD -> waitForFood();
                case IRON_TOOLKIT -> waitForIronToolkit();
                case WORKSTATIONS -> waitForWorkstations();
                case STORAGE -> waitForStorage();
                case SHELTER_MATERIALS ->
                        waitForShelterMaterials();
                case SHELTER -> waitForShelter();
                case FIRST_NIGHT -> waitForFirstNight();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Live-foundation companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Live-foundation companion body timed out"
                );
                return;
            }
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            prepareFixture(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = FoundationBootstrapStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "Live foundation model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            stage = FoundationBootstrapStage.SETTLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForSettlement() {
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(logs.get(1))
            );
            body.setYHeadRot(body.getYRot());
            if (!body.onGround()) {
                stableTicks = 0;
                return;
            }
            if (++stableTicks < 8
                    || !latestObservationSeesFixtureWood()) {
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= java.time.Duration.ofSeconds(15)
                                .toNanos(),
                        "Live-foundation semantic view never exposed "
                            + "the configured wood cluster"
                );
                return;
            }
            initialMinedLogs = body.getStats().getValue(
                    Stats.BLOCK_MINED.get(fixtureWoodBlock)
            );
            initialPickedUpLogs = body.getStats().getValue(
                    Stats.ITEM_PICKED_UP.get(fixtureWoodItem)
            );
            initialCraftedTables = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.CRAFTING_TABLE)
            );
            initialCraftedWoodenPickaxes = body.getStats().getValue(
                Stats.ITEM_CRAFTED.get(Items.WOODEN_PICKAXE)
            );
            initialMinedStone = body.getStats().getValue(
                    Stats.BLOCK_MINED.get(Blocks.STONE)
            );
            initialPickedUpCobblestone = body.getStats().getValue(
                    Stats.ITEM_PICKED_UP.get(Items.COBBLESTONE)
            );
            initialCraftedStonePickaxes = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.STONE_PICKAXE)
            );
            initialMinedCoal = body.getStats().getValue(
                    Stats.BLOCK_MINED.get(Blocks.COAL_ORE)
            );
            initialPickedUpCoal = body.getStats().getValue(
                    Stats.ITEM_PICKED_UP.get(Items.COAL)
            );
            initialCraftedCharcoal = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.CHARCOAL)
            );
            initialMinedIron = body.getStats().getValue(
                    Stats.BLOCK_MINED.get(Blocks.IRON_ORE)
            );
            initialPickedUpRawIron = body.getStats().getValue(
                    Stats.ITEM_PICKED_UP.get(Items.RAW_IRON)
            );
            initialCraftedFurnaces = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.FURNACE)
            );
            initialCraftedIronPickaxes = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.IRON_PICKAXE)
            );
            initialCraftedBuckets = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.BUCKET)
            );
            initialCraftedShields = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.SHIELD)
            );
            initialCraftedChests = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.CHEST)
            );
            initialCraftedDoors = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(fixtureDoorItem)
            );
            initialCraftedTorches = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.TORCH)
            );
            goalRevisionBefore = runtime.goals()
                    .snapshot()
                    .revision();
            if (zeroHumanFromStart) {
                final JsonObject arguments = new JsonObject();
                arguments.addProperty(
                        "goal",
                        "从空背包开始建立安全据点并生存到第二天。"
                            + "先把眼前相连的橡木原木全部砍下并捡进"
                            + "背包，然后继续基础生存；不要使用命令。"
                );
                final JsonObject response = new MinecraftMcpBackend(
                        runtime.server(),
                        runtime.worldData(),
                        runtime.memory(),
                        runtime.goals(),
                        runtime.observations()::latestDecisionEpoch,
                        runtime.tickMetrics()
                ).call("set_goal", arguments)
                        .join()
                        .getAsJsonObject();
                helper.assertTrue(
                        response.get("accepted").getAsBoolean(),
                        "Production MCP rejected the unattended foundation "
                            + "goal: " + response
                );
                stage = FoundationBootstrapStage.GOAL;
                stageStartedNanos = System.nanoTime();
                return;
            }
            humanSession = PlacedHuman.create(
                    helper,
                    runtime,
                    new Vec3(
                            autonomousWorkCenter.getX() + 0.5,
                            autonomousWorkCenter.getY()
                                + fixtureVariant.surfaceHeight(0, -4)
                                + 1.0,
                            autonomousWorkCenter.getZ() - 3.5
                    )
            );
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in foundation test player lacked task-write "
                        + "permission"
            );
            if (completeAfterStoneTools
                    || completeAfterIron
                    || storageTask()) {
                taskSubmittedNanos = System.nanoTime();
            }
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            human,
                            Component.literal(
                                completeAfterStoneTools
                                    ? runtime.worldData().displayName()
                                        + "，我把你带到了一个陌生地方，"
                                        + "身上的东西也都清空了。请你自己"
                                        + "从零发展到石器阶段，真正拥有一"
                                        + "把石镐后停下；不要使用命令。"
                                    : completeAfterIron
                                        ? runtime.worldData().displayName()
                                            + "，你现在什么都没有。请从空手"
                                            + "开始，把生存推进到拿到第一份"
                                            + "真正用于铁器时代的矿物原料，"
                                            + "东西实际进入背包后就停下；"
                                            + "不要使用命令。"
                                    : storageTask()
                                        ? runtime.worldData().displayName()
                                            + "，请从空背包自己制作需要的"
                                            + "工具，亲手砍下并捡起30个眼前"
                                            + "能制成木板的树干或菌柄；随后"
                                            + "制作并放置4个彼此"
                                            + "独立的箱子，把剩余原木尽量"
                                            + "平均地放进这4个箱子里。完成"
                                            + "实体操作后停下，不要使用命令。"
                                    : runtime.worldData().displayName()
                                        + "，从空背包开始建立安全据点并"
                                        + "生存到第二天。先把你眼前这组"
                                        + "相连的橡木原木全部砍下并捡进"
                                        + "背包，然后继续基础生存；不要"
                                        + "使用命令。"
                            )
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the foundation chat command"
            );
            stage = FoundationBootstrapStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            assertWithinModelDeadline(
                    "Live model did not classify the foundation task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && (completeAfterStoneTools
                            ? goal.goal().contains("石镐")
                            : completeAfterIron
                                ? goal.goal().contains("铁器时代")
                            : storageTask()
                                ? goal.goal().contains("30个眼前")
                                    && goal.goal().contains("4个")
                            : goal.goal().contains("安全据点")
                                && goal.goal().contains("第二天")),
                    (zeroHumanFromStart
                            ? "Unattended MCP goal was not preserved "
                            : "Authorized foundation chat was not preserved ")
                        + "as the M1 goal"
            );
            if (completeAfterStoneTools) {
                final GoalExecutionPlan plan = GoalExecutionPlan
                        .fromDetailCode(goal.detailCode())
                        .orElseThrow(() -> new AssertionError(
                                "The live model did not encode a route plan"
                        ));
                helper.assertTrue(
                        plan.equals(GoalExecutionPlan.foundation(
                                GoalExecutionPlan.Target
                                        .STONE_TOOL_OBTAINED
                        )),
                        "The live model encoded the natural-language task "
                            + "as the wrong route: " + plan
                );
            } else if (completeAfterIron) {
                final GoalExecutionPlan plan = GoalExecutionPlan
                        .fromDetailCode(goal.detailCode())
                        .orElseThrow(() -> new AssertionError(
                                "The live model did not encode an iron route"
                        ));
                helper.assertTrue(
                        plan.equals(GoalExecutionPlan.foundation(
                                GoalExecutionPlan.Target.IRON_OBTAINED
                        )),
                        "The live model encoded the abstract iron task "
                            + "as the wrong route: " + plan
                );
            } else if (storageTask()) {
                final GoalExecutionPlan plan = GoalExecutionPlan
                        .fromDetailCode(goal.detailCode())
                        .orElseThrow(() -> new AssertionError(
                                "The live model did not encode a route plan"
                        ));
                helper.assertTrue(
                        plan.equals(GoalExecutionPlan.foundation(
                                GoalExecutionPlan.Target
                                        .LOG_STORAGE_DISTRIBUTED
                        )),
                        "The live model encoded the quantified storage "
                            + "task as the wrong route: " + plan
                );
            }
            if (zeroHumanFromStart) {
                helper.assertTrue(
                        goal.source() == GoalSource.MCP,
                        "Unattended goal bypassed the production MCP source"
                );
            }
            if (humanSession != null) {
                humanSession.close();
                humanSession = null;
            }
            foundationGoalRevision = goal.revision();
            stage = FoundationBootstrapStage.GATHER;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGathering() {
            assertNoHumanPlayersDuringAutonomy();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died during foundation wood gathering"
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if ("gather_visible_block_cluster".equals(
                    skill.skillName()
            ) || "gather_nearby_wood".equals(
                    skill.skillName()
            )) {
                sawClusterGatherer = true;
            }
            final int ownedLogs = body.getInventory()
                    .countItem(fixtureWoodItem);
            final int pickedUpLogs = body.getStats().getValue(
                    Stats.ITEM_PICKED_UP.get(fixtureWoodItem)
            ) - initialPickedUpLogs;
            final int minedLogs = body.getStats().getValue(
                    Stats.BLOCK_MINED.get(fixtureWoodBlock)
            ) - initialMinedLogs;
            final int requiredLogs = completeAfterStoneTools
                    ? 3
                    : storageTask()
                        ? 30
                    : REQUIRED_LOGS;
            final boolean milestone = runtime.worldData()
                    .verifiedRouteProgress(foundationGoalRevision)
                    .milestones()
                    .contains(SurvivalMilestone.WOOD_OBTAINED);
            if (pickedUpLogs >= requiredLogs
                    && minedLogs >= requiredLogs
                    && milestone) {
                helper.assertTrue(
                        sawClusterGatherer,
                        "M1 bootstrap used repeated model micro-actions "
                            + "instead of the bounded production gatherer"
                );
                helper.assertTrue(
                        minedLogs >= requiredLogs,
                        "Foundation bootstrap did not record the required "
                            + "vanilla log mining actions: required="
                            + requiredLogs + ", actual=" + minedLogs
                );
                stage = FoundationBootstrapStage.BASIC_CRAFTING;
                stageStartedNanos = System.nanoTime();
                return;
            }
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.status() != GoalStatus.RUNNING
                    && goal.status() != GoalStatus.CANCEL_PENDING) {
                helper.assertTrue(
                        false,
                        "Foundation goal became terminal before the "
                            + "wood milestone: " + goal + ", logs="
                            + ownedLogs + ", pickedUp=" + pickedUpLogs
                            + ", blocks=" + logSummary()
                            + ", skill=" + skill.skillName()
                            + ", rejection="
                            + skill.lastStartRejection()
                            + ", "
                            + basicCraftingDiagnostic(body)
                );
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= (storageTask()
                                ? DISTRIBUTED_STORAGE_DEADLINE_NANOS
                                : MODEL_TIMEOUT_NANOS),
                    "Live foundation bootstrap did not gather the "
                        + "visible logs: owned=" + ownedLogs
                        + ", pickedUp=" + pickedUpLogs
                        + ", mined=" + minedLogs
                        + ", blocks=" + logSummary()
                        + ", milestone=" + milestone
                        + ", skill=" + skill.skillName()
                        + ", rejection="
                        + skill.lastStartRejection()
            );
        }

        private void waitForBasicCrafting() {
            assertNoHumanPlayersDuringAutonomy();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died during foundation basic crafting"
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if ("craft_recipe".equals(skill.skillName())) {
                sawCraftRecipe = true;
            }
            if ("prepare_basic_crafting".equals(
                    skill.skillName()
            )) {
                sawCraftRecipe = true;
            }
            final boolean tableCrafted = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.CRAFTING_TABLE)
            ) - initialCraftedTables >= 1;
            final boolean pickaxeCrafted = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.WOODEN_PICKAXE)
            ) - initialCraftedWoodenPickaxes >= 1;
            final boolean ownsBasicPickaxe =
                    body.getInventory().countItem(
                            Items.WOODEN_PICKAXE
                    ) > 0
                    || body.getInventory().countItem(
                            Items.STONE_PICKAXE
                    ) > 0
                    || body.getInventory().countItem(
                            Items.IRON_PICKAXE
                    ) > 0
                    || body.getInventory().countItem(
                            Items.GOLDEN_PICKAXE
                    ) > 0
                    || body.getInventory().countItem(
                            Items.DIAMOND_PICKAXE
                    ) > 0
                    || body.getInventory().countItem(
                            Items.NETHERITE_PICKAXE
                    ) > 0;
            final boolean tableAvailable =
                    body.getInventory().countItem(
                            Items.CRAFTING_TABLE
                    ) > 0
                    || runtime.worldData()
                        .verifiedFoundationEvidence(
                                foundationGoalRevision
                        )
                        .flatMap(evidence ->
                                evidence.craftingTable()
                        )
                        .isPresent();
            final boolean milestone = runtime.worldData()
                    .verifiedRouteProgress(foundationGoalRevision)
                    .milestones()
                    .contains(
                            SurvivalMilestone.BASIC_CRAFTING_READY
                    );
            if (tableCrafted
                    && pickaxeCrafted
                    && ownsBasicPickaxe
                    && tableAvailable
                    && milestone) {
                helper.assertTrue(
                        sawCraftRecipe,
                        "M1 basic crafting did not use the production "
                            + "recipe skill"
                );
                stage = FoundationBootstrapStage.STONE_GATHERING;
                stageStartedNanos = System.nanoTime();
                return;
            }
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.status() != GoalStatus.RUNNING
                    && goal.status() != GoalStatus.CANCEL_PENDING) {
                helper.assertTrue(
                        false,
                        "Foundation goal became terminal before basic "
                            + "crafting: " + goal
                            + ", tableCrafted=" + tableCrafted
                            + ", pickaxeCrafted=" + pickaxeCrafted
                            + ", ownsPickaxe=" + ownsBasicPickaxe
                            + ", tableAvailable=" + tableAvailable
                            + ", milestone=" + milestone
                            + ", inventory=" + body.getInventory()
                            + ", skill=" + skill.skillName()
                            + ", rejection="
                            + skill.lastStartRejection()
                            + ", "
                            + basicCraftingDiagnostic(body)
                );
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    "Live foundation bootstrap did not prepare basic "
                        + "crafting: tableCrafted=" + tableCrafted
                        + ", pickaxeCrafted=" + pickaxeCrafted
                        + ", ownsPickaxe=" + ownsBasicPickaxe
                        + ", tableAvailable=" + tableAvailable
                        + ", milestone=" + milestone
                        + ", inventory=" + body.getInventory()
                        + ", skill=" + skill.skillName()
                        + ", rejection="
                        + skill.lastStartRejection()
                        + ", "
                        + basicCraftingDiagnostic(body)
            );
        }

        private String basicCraftingDiagnostic(
                final ServerPlayer body
        ) {
            final BlockPos center = body.blockPosition();
            final List<BlockPos> nearbyTables =
                    BlockPos.betweenClosedStream(
                            center.offset(-10, -3, -10),
                            center.offset(10, 3, 10)
                    ).filter(pos ->
                            helper.getLevel().getBlockState(pos)
                                    .is(Blocks.CRAFTING_TABLE)
                    ).map(BlockPos::immutable).toList();
            return "bodyPosition=" + body.position()
                    + ", bodyBlock=" + center
                    + ", onGround=" + body.onGround()
                    + ", rotation=[" + body.getYRot()
                    + "," + body.getXRot() + "]"
                    + ", mainHand=" + body.getMainHandItem()
                    + ", nearbyTables=" + nearbyTables;
        }

        private void waitForStoneGathering() {
            assertNoHumanPlayersDuringAutonomy();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died during foundation stone gathering"
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if ("gather_visible_block_cluster".equals(
                    skill.skillName()
            ) || "prepare_stone_tools".equals(
                    skill.skillName()
            )) {
                sawStoneGatherer = true;
            }
            final int ownedCobblestone = body.getInventory()
                    .countItem(Items.COBBLESTONE);
            final int pickedUpCobblestone = body.getStats().getValue(
                    Stats.ITEM_PICKED_UP.get(Items.COBBLESTONE)
            ) - initialPickedUpCobblestone;
            final int minedStone = body.getStats().getValue(
                    Stats.BLOCK_MINED.get(Blocks.STONE)
            ) - initialMinedStone;
            if (ownedCobblestone >= REQUIRED_COBBLESTONE
                    && pickedUpCobblestone >= REQUIRED_COBBLESTONE
                    && minedStone >= REQUIRED_COBBLESTONE) {
                helper.assertTrue(
                        sawStoneGatherer,
                        "M1 stone acquisition bypassed the bounded "
                            + "production gatherer"
                );
                stage = FoundationBootstrapStage.STONE_CRAFTING;
                stageStartedNanos = System.nanoTime();
                return;
            }
            final GoalSnapshot goal = runtime.goals().snapshot();
            helper.assertTrue(
                    goal.status() == GoalStatus.RUNNING
                        || goal.status() == GoalStatus.CANCEL_PENDING,
                    "Foundation goal became terminal before stone "
                        + "gathering: " + goal
                        + ", ownedCobblestone=" + ownedCobblestone
                        + ", pickedUp=" + pickedUpCobblestone
                        + ", minedStone=" + minedStone
                        + ", blocks=" + stoneSummary()
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
            );
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    "Live foundation route did not gather stone: "
                        + "ownedCobblestone=" + ownedCobblestone
                        + ", pickedUp=" + pickedUpCobblestone
                        + ", minedStone=" + minedStone
                        + ", blocks=" + stoneSummary()
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
            );
        }

        private void waitForStoneCrafting() {
            assertNoHumanPlayersDuringAutonomy();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died during foundation stone crafting"
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if ("craft_recipe".equals(skill.skillName())) {
                sawStoneCraftRecipe = true;
            }
            if ("prepare_stone_tools".equals(skill.skillName())) {
                sawStoneCraftRecipe = true;
            }
            final boolean crafted = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.STONE_PICKAXE)
            ) - initialCraftedStonePickaxes >= 1;
            final boolean ownsStonePickaxe =
                    body.getInventory().countItem(
                            Items.STONE_PICKAXE
                    ) > 0;
            final boolean milestone = runtime.worldData()
                    .verifiedRouteProgress(foundationGoalRevision)
                    .milestones()
                    .contains(
                            SurvivalMilestone.STONE_TOOL_OBTAINED
                    );
            if (crafted && ownsStonePickaxe && milestone) {
                helper.assertTrue(
                        sawStoneCraftRecipe,
                        "M1 stone pickaxe did not use the production "
                            + "recipe transaction"
                );
                if (completeAfterStoneTools) {
                    helper.assertTrue(
                            System.nanoTime() - taskSubmittedNanos
                                <= FAST_STONE_TOOLS_DEADLINE_NANOS,
                            "The encoded zero-inventory stone-tool route "
                                + "exceeded 60 seconds"
                    );
                    finishScenarioGoal(runtime);
                    stage = FoundationBootstrapStage.DONE;
                    helper.succeed();
                    return;
                }
                if (storageTask()) {
                    stage = FoundationBootstrapStage.DISTRIBUTED_STORAGE;
                    stageStartedNanos = System.nanoTime();
                    return;
                }
                stage = completeAfterIron
                        ? FoundationBootstrapStage.IRON_TOOLKIT
                        : FoundationBootstrapStage.FOOD;
                stageStartedNanos = System.nanoTime();
                return;
            }
            final GoalSnapshot goal = runtime.goals().snapshot();
            helper.assertTrue(
                    goal.status() == GoalStatus.RUNNING
                        || goal.status() == GoalStatus.CANCEL_PENDING,
                    "Foundation goal became terminal before stone "
                        + "crafting: " + goal
                        + ", crafted=" + crafted
                        + ", ownsStonePickaxe=" + ownsStonePickaxe
                        + ", milestone=" + milestone
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
            );
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    "Live foundation route did not craft a stone pickaxe: "
                        + "crafted=" + crafted
                        + ", ownsStonePickaxe=" + ownsStonePickaxe
                        + ", milestone=" + milestone
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
            );
        }

        private void waitForFood() {
            assertNoHumanPlayersDuringAutonomy();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died while securing M1 food"
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if ("hunt_observed_food_animal".equals(
                    skill.skillName()
            ) || "secure_visible_food_reserve".equals(
                    skill.skillName()
            )) {
                sawFoodHunt = true;
            }
            final int safeFood = body.getInventory()
                    .countItem(Items.BEEF);
            final boolean milestone = runtime.worldData()
                    .verifiedRouteProgress(foundationGoalRevision)
                    .milestones()
                    .contains(SurvivalMilestone.FOOD_SECURED);
            if (safeFood >= 8 && milestone) {
                helper.assertTrue(
                        sawFoodHunt,
                        "M1 food reserve bypassed the production "
                            + "observed-animal hunt"
                );
                helper.assertTrue(
                        foodAnimals.stream()
                            .filter(Cow::isAlive)
                            .count() <= foodAnimals.size() - 3L,
                        "M1 food stage did not physically hunt enough "
                            + "of the observed animals"
                );
                stage = FoundationBootstrapStage.IRON_TOOLKIT;
                stageStartedNanos = System.nanoTime();
                return;
            }
            final GoalSnapshot goal = runtime.goals().snapshot();
            helper.assertTrue(
                    goal.status() == GoalStatus.RUNNING
                        || goal.status() == GoalStatus.CANCEL_PENDING,
                    "Foundation goal became terminal before food "
                        + "readiness: " + goal + ", beef="
                        + safeFood + ", milestone=" + milestone
                        + ", skill=" + skill.skillName()
                        + ", rejection="
                        + skill.lastStartRejection()
            );
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= FOUNDATION_TOOLKIT_TIMEOUT_NANOS,
                    "Live foundation route did not secure eight food: "
                        + "beef=" + safeFood
                        + ", livingAnimals="
                        + foodAnimals.stream()
                            .filter(Cow::isAlive)
                            .count()
                        + ", milestone=" + milestone
                        + ", skill=" + skill.skillName()
                        + ", rejection="
                        + skill.lastStartRejection()
            );
        }

        private void waitForDistributedStorage() {
            assertNoHumanPlayersDuringAutonomy();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died during quantified log storage"
            );
            final int minedLogs = body.getStats().getValue(
                    Stats.BLOCK_MINED.get(fixtureWoodBlock)
            ) - initialMinedLogs;
            final int pickedLogs = body.getStats().getValue(
                    Stats.ITEM_PICKED_UP.get(fixtureWoodItem)
            ) - initialPickedUpLogs;
            final int craftedChests = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.CHEST)
            ) - initialCraftedChests;
            final boolean milestone = runtime.worldData()
                    .verifiedRouteProgress(foundationGoalRevision)
                    .milestones()
                    .contains(
                            SurvivalMilestone.LOG_STORAGE_DISTRIBUTED
                    );
            final List<BlockPos> chestPositions =
                    BlockPos.betweenClosedStream(
                            autonomousWorkCenter.offset(-10, 0, -10),
                            autonomousWorkCenter.offset(10, 5, 10)
                    ).filter(pos -> helper.getLevel()
                            .getBlockState(pos)
                            .is(Blocks.CHEST)
                    ).map(BlockPos::immutable).toList();
            if (milestone && chestPositions.size() == 4) {
                helper.assertTrue(
                        minedLogs == 30,
                        "Quantified task did not mine exactly thirty logs: "
                            + minedLogs
                );
                helper.assertTrue(
                        pickedLogs == 30,
                        "Quantified task did not pick up exactly thirty "
                            + "logs: " + pickedLogs
                );
                helper.assertTrue(
                        craftedChests >= 4,
                        "Four placed chests were not backed by four "
                            + "vanilla recipe outputs: " + craftedChests
                );
                for (int first = 0;
                        first < chestPositions.size(); first++) {
                    for (int second = first + 1;
                            second < chestPositions.size(); second++) {
                        final BlockPos a = chestPositions.get(first);
                        final BlockPos b = chestPositions.get(second);
                        helper.assertTrue(
                                a.getY() != b.getY()
                                    || Math.abs(a.getX() - b.getX())
                                        + Math.abs(a.getZ() - b.getZ()) > 1,
                                "Storage placements merged into a double "
                                    + "chest: " + chestPositions
                        );
                    }
                }
                final List<Integer> stored = chestPositions.stream()
                        .map(position -> helper.getLevel()
                                .getBlockEntity(position))
                        .map(entity -> {
                            helper.assertTrue(
                                    entity instanceof ChestBlockEntity,
                                    "Placed chest lacked a chest block entity"
                            );
                            final ChestBlockEntity chest =
                                    (ChestBlockEntity) entity;
                            int count = 0;
                            for (int slot = 0;
                                    slot < chest.getContainerSize(); slot++) {
                                final ItemStack stack = chest.getItem(slot);
                                if (stack.is(fixtureWoodItem)) {
                                    count += stack.getCount();
                                }
                            }
                            return count;
                        })
                        .sorted()
                        .toList();
                helper.assertTrue(
                        stored.getLast() - stored.getFirst() <= 1,
                        "Remaining logs were not evenly distributed: "
                            + stored
                );
                helper.assertTrue(
                        stored.getFirst() > 0,
                        "At least one chest received no remaining logs: "
                            + stored
                );
                helper.assertTrue(
                        body.getInventory().countItem(fixtureWoodItem) == 0
                            && !body.getMainHandItem().is(fixtureWoodItem)
                            && !body.getOffhandItem().is(fixtureWoodItem),
                        "Raw logs remained on the companion after the "
                            + "four-way transfer"
                );
                if (completeAfterContainerWoodDoor) {
                    beginContainerDoorFollowup(body, chestPositions);
                } else {
                    finishScenarioGoal(runtime);
                    stage = FoundationBootstrapStage.DONE;
                    helper.succeed();
                }
                return;
            }
            final GoalSnapshot goal = runtime.goals().snapshot();
            final var skill = runtime.skillSupervisor().snapshot();
            helper.assertTrue(
                    goal.status() == GoalStatus.RUNNING
                        || goal.status() == GoalStatus.CANCEL_PENDING
                        || milestone
                            && goal.status() == GoalStatus.COMPLETED,
                    "Quantified storage goal became terminal before its "
                        + "physical evidence: " + goal
                        + ", mined=" + minedLogs
                        + ", picked=" + pickedLogs
                        + ", craftedChests=" + craftedChests
                        + ", chestPositions=" + chestPositions
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
            );
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= DISTRIBUTED_STORAGE_DEADLINE_NANOS,
                    "Quantified log storage timed out: mined=" + minedLogs
                        + ", picked=" + pickedLogs
                        + ", craftedChests=" + craftedChests
                        + ", chestPositions=" + chestPositions
                        + ", inventory=" + body.getInventory()
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
                        + ", checkpoint=" + runtime.skillSupervisor()
                            .lastCheckpointPayload().orElse("<none>")
            );
        }

        private void beginContainerDoorFollowup(
                final ServerPlayer body,
                final List<BlockPos> chestPositions
        ) {
            finishScenarioGoal(runtime);
            containerDoorChests = chestPositions.stream()
                    .sorted(java.util.Comparator
                            .<BlockPos>comparingInt(BlockPos::getY)
                            .thenComparingInt(BlockPos::getX)
                            .thenComparingInt(BlockPos::getZ))
                    .toList();
            final Map<BlockPos, Integer> counts = new HashMap<>();
            for (BlockPos position : containerDoorChests) {
                counts.put(position, fixtureWoodInChest(position));
            }
            containerDoorCounts = Map.copyOf(counts);
            containerDoorInitialPlanks = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(fixturePlanksItem)
            );
            containerDoorInitialDoors = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(fixtureDoorItem)
            );
            expectedContainerDoor = expectedDoorFor(
                    body,
                    containerDoorChests
            );
            goalRevisionBefore = runtime.goals().snapshot().revision();
            humanSession = PlacedHuman.create(
                    helper,
                    runtime,
                    new Vec3(
                            autonomousWorkCenter.getX() + 0.5,
                            autonomousWorkCenter.getY()
                                + fixtureVariant.surfaceHeight(0, -8)
                                + 1.0,
                            autonomousWorkCenter.getZ() - 7.5
                    )
            );
            final Component submitted = ForgeHooks
                    .onServerChatSubmittedEvent(
                            humanSession.player(),
                            Component.literal(
                                    runtime.worldData().displayName()
                                        + "，现在从刚才那4个独立箱子里"
                                        + "每个各取出1个能制成木板的木材，"
                                        + "用实际取出的木材合成木板和匹配"
                                        + "的木门，再把木门放在这组箱子"
                                        + "正前方3格处。做完实体操作后"
                                        + "停下，不要使用命令。"
                            )
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the container-door chat command"
            );
            containerDoorTaskSubmittedNanos = System.nanoTime();
            stage = FoundationBootstrapStage.CONTAINER_DOOR_GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForContainerDoorGoal() {
            assertWithinModelDeadline(
                    "Live model did not classify the container-door task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            final GoalExecutionPlan plan = GoalExecutionPlan
                    .fromDetailCode(goal.detailCode())
                    .orElseThrow(() -> new AssertionError(
                            "The live model did not encode the door route"
                    ));
            helper.assertTrue(
                    plan.equals(GoalExecutionPlan.foundation(
                            GoalExecutionPlan.Target
                                    .CONTAINER_WOOD_DOOR_PLACED
                    )),
                    "The live model encoded the container-door request as "
                        + "the wrong route: " + plan
            );
            helper.assertTrue(
                    goal.goal().contains("每个各取出1个")
                        && goal.goal().contains("正前方3格"),
                    "The natural container-door request was not preserved"
            );
            if (humanSession != null) {
                humanSession.close();
                humanSession = null;
            }
            foundationGoalRevision = goal.revision();
            stage = FoundationBootstrapStage.CONTAINER_DOOR;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForContainerDoor() {
            assertNoHumanPlayersDuringAutonomy();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died during the container-door follow-up"
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if ("prepare_container_wood_door".equals(
                    skill.skillName()
            )) {
                sawContainerWoodDoor = true;
            }
            final boolean eachChestLostOne = containerDoorChests.stream()
                    .allMatch(position -> fixtureWoodInChest(position)
                            == containerDoorCounts.get(position) - 1);
            final boolean planksCrafted = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(fixturePlanksItem)
            ) >= containerDoorInitialPlanks + 4;
            final boolean doorCrafted = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(fixtureDoorItem)
            ) >= containerDoorInitialDoors + 1;
            final boolean doorPlaced = expectedContainerDoor != null
                    && helper.getLevel()
                        .getBlockState(expectedContainerDoor)
                        .is(fixtureDoorBlock);
            final boolean milestone = runtime.worldData()
                    .verifiedRouteProgress(foundationGoalRevision)
                    .milestones()
                    .contains(
                            SurvivalMilestone
                                    .CONTAINER_WOOD_DOOR_PLACED
                    );
            if (eachChestLostOne
                    && planksCrafted
                    && doorCrafted
                    && doorPlaced
                    && milestone) {
                helper.assertTrue(
                        sawContainerWoodDoor,
                        "The follow-up bypassed the production container "
                            + "wood-door compound"
                );
                helper.assertTrue(
                        body.getInventory().countItem(fixtureWoodItem) == 0
                            && !body.getMainHandItem().is(fixtureWoodItem)
                            && !body.getOffhandItem().is(fixtureWoodItem),
                        "Withdrawn raw logs remained after door crafting"
                );
                finishScenarioGoal(runtime);
                stage = FoundationBootstrapStage.DONE;
                helper.succeed();
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - containerDoorTaskSubmittedNanos
                        <= CONTAINER_DOOR_DEADLINE_NANOS,
                    "Container-door follow-up timed out: chestCounts="
                        + containerDoorChests.stream()
                            .map(position -> position + "="
                                    + fixtureWoodInChest(position))
                            .toList()
                        + ", expectedDoor=" + expectedContainerDoor
                        + ", doorState="
                        + (expectedContainerDoor == null
                            ? "missing"
                            : helper.getLevel().getBlockState(
                                    expectedContainerDoor
                            ))
                        + ", planksCrafted=" + planksCrafted
                        + ", doorCrafted=" + doorCrafted
                        + ", milestone=" + milestone
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
                        + ", checkpoint=" + runtime.skillSupervisor()
                            .lastCheckpointPayload().orElse("<none>")
            );
        }

        private int fixtureWoodInChest(final BlockPos position) {
            final var entity = helper.getLevel().getBlockEntity(position);
            helper.assertTrue(
                    entity instanceof ChestBlockEntity,
                    "Expected chest block entity is missing at " + position
            );
            final ChestBlockEntity chest = (ChestBlockEntity) entity;
            int count = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                final ItemStack stack = chest.getItem(slot);
                if (stack.is(fixtureWoodItem)) {
                    count += stack.getCount();
                }
            }
            return count;
        }

        private static BlockPos expectedDoorFor(
                final ServerPlayer body,
                final List<BlockPos> chests
        ) {
            final double averageX = chests.stream()
                    .mapToInt(BlockPos::getX).average().orElseThrow();
            final double averageY = chests.stream()
                    .mapToInt(BlockPos::getY).average().orElseThrow();
            final double averageZ = chests.stream()
                    .mapToInt(BlockPos::getZ).average().orElseThrow();
            final double towardBodyX = body.getX() - (averageX + 0.5);
            final double towardBodyZ = body.getZ() - (averageZ + 0.5);
            final int outwardX;
            final int outwardZ;
            if (Math.abs(towardBodyX) >= Math.abs(towardBodyZ)
                    && Math.abs(towardBodyX) > 0.25) {
                outwardX = towardBodyX > 0.0 ? 1 : -1;
                outwardZ = 0;
            } else if (Math.abs(towardBodyZ) > 0.25) {
                outwardX = 0;
                outwardZ = towardBodyZ > 0.0 ? 1 : -1;
            } else {
                final Vec3 look = body.getLookAngle();
                if (Math.abs(look.x()) >= Math.abs(look.z())) {
                    outwardX = look.x() > 0.0 ? -1 : 1;
                    outwardZ = 0;
                } else {
                    outwardX = 0;
                    outwardZ = look.z() > 0.0 ? -1 : 1;
                }
            }
            return new BlockPos(
                    (int) Math.round(averageX) + outwardX * 3,
                    (int) Math.round(averageY),
                    (int) Math.round(averageZ) + outwardZ * 3
            );
        }

        private void waitForIronToolkit() {
            assertNoHumanPlayersDuringAutonomy();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died while preparing the M1 iron toolkit"
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if ("prepare_iron_toolkit".equals(
                    skill.skillName()
            )) {
                sawIronToolkit = true;
            }
            final int minedIron = body.getStats().getValue(
                    Stats.BLOCK_MINED.get(Blocks.IRON_ORE)
            ) - initialMinedIron;
            final int pickedUpRawIron = body.getStats().getValue(
                    Stats.ITEM_PICKED_UP.get(Items.RAW_IRON)
            ) - initialPickedUpRawIron;
            final int ownedIronMaterial = body.getInventory().countItem(
                    Items.RAW_IRON
            ) + body.getInventory().countItem(Items.IRON_INGOT);
            final boolean ironMaterialMilestone = runtime.worldData()
                    .verifiedRouteProgress(foundationGoalRevision)
                    .milestones()
                    .contains(SurvivalMilestone.IRON_OBTAINED);
            if (completeAfterIron
                    && minedIron >= 1
                    && pickedUpRawIron >= 1
                    && ownedIronMaterial >= 1
                    && ironMaterialMilestone) {
                helper.assertTrue(
                        sawIronToolkit,
                        "Iron acquisition bypassed the bounded production "
                            + "iron controller"
                );
                helper.assertTrue(
                        System.nanoTime() - taskSubmittedNanos
                            <= FAST_IRON_DEADLINE_NANOS,
                        "The encoded zero-inventory iron route exceeded "
                            + "five minutes"
                );
                finishScenarioGoal(runtime);
                stage = FoundationBootstrapStage.DONE;
                helper.succeed();
                return;
            }
            final boolean ownsIronPickaxe =
                    body.getInventory().countItem(
                            Items.IRON_PICKAXE
                    ) > 0;
            final boolean ownsBucket =
                    body.getInventory().countItem(Items.BUCKET) > 0
                    || body.getInventory().countItem(
                            Items.WATER_BUCKET
                    ) > 0
                    || body.getInventory().countItem(
                            Items.LAVA_BUCKET
                    ) > 0;
            final boolean ownsShield =
                    body.getInventory().countItem(Items.SHIELD) > 0;
            final boolean milestone = runtime.worldData()
                    .verifiedRouteProgress(foundationGoalRevision)
                    .milestones()
                    .contains(
                            SurvivalMilestone.IRON_TOOLKIT_OBTAINED
                    );
            final boolean furnaceVerified = runtime.worldData()
                    .verifiedFoundationEvidence(
                            foundationGoalRevision
                    )
                    .flatMap(evidence -> evidence.furnace())
                    .isPresent();
            if (ownsIronPickaxe
                    && ownsBucket
                    && ownsShield
                    && milestone
                    && furnaceVerified) {
                helper.assertTrue(
                        sawIronToolkit,
                        "M1 iron readiness bypassed the bounded "
                            + "production iron-toolkit skill"
                );
                final boolean minedCoalFuel =
                        body.getStats().getValue(
                                Stats.BLOCK_MINED.get(
                                        Blocks.COAL_ORE
                                )
                        ) - initialMinedCoal >= 1
                        && body.getStats().getValue(
                                Stats.ITEM_PICKED_UP.get(Items.COAL)
                        ) - initialPickedUpCoal >= 1;
                final boolean smeltedCharcoalFuel =
                        body.getStats().getValue(
                                Stats.ITEM_CRAFTED.get(
                                        Items.CHARCOAL
                                )
                        ) - initialCraftedCharcoal >= 1;
                helper.assertTrue(
                        minedCoalFuel || smeltedCharcoalFuel,
                        "M1 iron toolkit produced no physically audited "
                            + "coal or charcoal fuel"
                );
                helper.assertTrue(
                        body.getStats().getValue(
                            Stats.BLOCK_MINED.get(Blocks.IRON_ORE)
                        ) - initialMinedIron >= 7,
                        "M1 iron toolkit did not mine seven iron ore"
                );
                helper.assertTrue(
                        body.getStats().getValue(
                            Stats.ITEM_PICKED_UP.get(Items.RAW_IRON)
                        ) - initialPickedUpRawIron >= 7,
                        "M1 iron toolkit did not physically collect "
                            + "seven raw iron"
                );
                helper.assertTrue(
                        body.getStats().getValue(
                            Stats.ITEM_CRAFTED.get(Items.FURNACE)
                        ) - initialCraftedFurnaces >= 1,
                        "M1 iron toolkit did not craft its furnace"
                );
                helper.assertTrue(
                        body.getStats().getValue(
                            Stats.ITEM_CRAFTED.get(Items.IRON_PICKAXE)
                        ) - initialCraftedIronPickaxes >= 1
                        && body.getStats().getValue(
                            Stats.ITEM_CRAFTED.get(Items.BUCKET)
                        ) - initialCraftedBuckets >= 1
                        && body.getStats().getValue(
                            Stats.ITEM_CRAFTED.get(Items.SHIELD)
                        ) - initialCraftedShields >= 1,
                        "M1 iron toolkit did not craft all three "
                            + "retained iron items through recipes"
                );
                if (zeroHumanFromStart) {
                    assertAutonomousChunkSimulation(body);
                }
                stage = FoundationBootstrapStage.WORKSTATIONS;
                stageStartedNanos = System.nanoTime();
                return;
            }
            final GoalSnapshot goal = runtime.goals().snapshot();
            helper.assertTrue(
                    goal.status() == GoalStatus.RUNNING
                        || goal.status() == GoalStatus.CANCEL_PENDING
                        || completeAfterIron
                            && goal.status() == GoalStatus.COMPLETED,
                    "Foundation goal became terminal before iron "
                        + "toolkit readiness: " + goal
                        + ", minedIron=" + minedIron
                        + ", pickedUpRawIron=" + pickedUpRawIron
                        + ", ownedIronMaterial=" + ownedIronMaterial
                        + ", ironMaterialMilestone="
                        + ironMaterialMilestone
                        + ", pickaxe=" + ownsIronPickaxe
                        + ", bucket=" + ownsBucket
                        + ", shield=" + ownsShield
                        + ", furnace=" + furnaceVerified
                        + ", milestone=" + milestone
                        + ", skill=" + skill.skillName()
                        + ", rejection="
                        + skill.lastStartRejection()
            );
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= FOUNDATION_TOOLKIT_TIMEOUT_NANOS,
                    "Live foundation route did not complete its iron "
                        + "toolkit: pickaxe=" + ownsIronPickaxe
                        + ", bucket=" + ownsBucket
                        + ", shield=" + ownsShield
                        + ", furnace=" + furnaceVerified
                        + ", milestone=" + milestone
                        + ", coal=" + coalSummary()
                        + ", iron=" + ironSummary()
                        + ", skill=" + skill.skillName()
                        + ", rejection="
                        + skill.lastStartRejection()
            );
        }

        private void waitForWorkstations() {
            assertNoHumanPlayersDuringAutonomy();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died while establishing M1 workstations"
            );
            final var evidence = runtime.worldData()
                    .verifiedFoundationEvidence(
                            foundationGoalRevision
                    );
            final var verification = evidence
                    .map(found ->
                            ServerFoundationEvidenceVerifier.verify(
                                    runtime.server(),
                                    found
                            )
                    )
                    .orElseGet(() ->
                            new ServerFoundationEvidenceVerifier.Result(
                                    false,
                                    false
                            )
                    );
            final boolean milestone = runtime.worldData()
                    .verifiedRouteProgress(foundationGoalRevision)
                    .milestones()
                    .contains(
                            SurvivalMilestone.WORKSTATIONS_ESTABLISHED
                    );
            final boolean chestCrafted = body.getStats().getValue(
                    Stats.ITEM_CRAFTED.get(Items.CHEST)
            ) - initialCraftedChests >= 1;
            if (verification.workstationsEstablished()
                    && milestone
                    && chestCrafted) {
                helper.assertTrue(
                        evidence.orElseThrow().craftingTable().isPresent()
                            && evidence.orElseThrow().furnace().isPresent()
                            && evidence.orElseThrow().storage().isPresent(),
                        "M1 workstation evidence omitted an opened fixture"
                );
                stage = FoundationBootstrapStage.STORAGE;
                stageStartedNanos = System.nanoTime();
                return;
            }
            final var skill = runtime.skillSupervisor().snapshot();
            final GoalSnapshot goal = runtime.goals().snapshot();
            helper.assertTrue(
                    goal.status() == GoalStatus.RUNNING
                        || goal.status() == GoalStatus.CANCEL_PENDING,
                    "Foundation goal became terminal before workstation "
                        + "verification: " + goal
                        + ", verification=" + verification
                        + ", chestCrafted=" + chestCrafted
                        + ", evidence=" + evidence
                        + ", inventory=" + body.getInventory()
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
            );
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= FOUNDATION_TOOLKIT_TIMEOUT_NANOS,
                    "Live foundation route did not establish and open its "
                        + "crafting table, furnace, and chest: verification="
                        + verification + ", chestCrafted=" + chestCrafted
                        + ", evidence=" + evidence
                        + ", inventory=" + body.getInventory()
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
            );
        }

        private void waitForStorage() {
            assertNoHumanPlayersDuringAutonomy();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died while storing M1 supplies"
            );
            final var evidence = runtime.worldData()
                    .verifiedFoundationEvidence(
                            foundationGoalRevision
                    );
            final var verification = evidence
                    .map(found ->
                            ServerFoundationEvidenceVerifier.verify(
                                    runtime.server(),
                                    found
                            )
                    )
                    .orElseGet(() ->
                            new ServerFoundationEvidenceVerifier.Result(
                                    false,
                                    false
                            )
                    );
            final boolean milestone = runtime.worldData()
                    .verifiedRouteProgress(foundationGoalRevision)
                    .milestones()
                    .contains(SurvivalMilestone.SUPPLIES_STORED);
            if (verification.suppliesStored() && milestone) {
                final var recorded = evidence.orElseThrow();
                helper.assertTrue(
                        recorded.suppliesDeposited()
                            && recorded.depositedItemCount() > 0
                            && !recorded.depositedItemId().isBlank(),
                        "M1 storage milestone lacked a genuine recorded "
                            + "menu deposit"
                );
                if (zeroHumanFromStart) {
                    assertAutonomousChunkSimulation(body);
                }
                stage = FoundationBootstrapStage.SHELTER_MATERIALS;
                stageStartedNanos = System.nanoTime();
                return;
            }
            final var skill = runtime.skillSupervisor().snapshot();
            final GoalSnapshot goal = runtime.goals().snapshot();
            helper.assertTrue(
                    goal.status() == GoalStatus.RUNNING
                        || goal.status() == GoalStatus.CANCEL_PENDING,
                    "Foundation goal became terminal before storage "
                        + "verification: " + goal
                        + ", verification=" + verification
                        + ", evidence=" + evidence
                        + ", inventory=" + body.getInventory()
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
            );
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= FOUNDATION_TOOLKIT_TIMEOUT_NANOS,
                    "Live foundation route did not deposit surplus through "
                        + "the opened chest menu: verification="
                        + verification + ", evidence=" + evidence
                        + ", inventory=" + body.getInventory()
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
            );
        }

        private void waitForShelterMaterials() {
            assertNoHumanPlayersDuringAutonomy();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died while preparing shelter materials"
            );
            final int structural = body.getInventory()
                    .countItem(fixturePlanksItem);
            final int doors = body.getInventory()
                    .countItem(fixtureDoorItem);
            final int lights = body.getInventory()
                    .countItem(Items.TORCH);
            final var skill = runtime.skillSupervisor().snapshot();
            if (structural >= DynamicShelterPlanner
                        .structuralBlockCount(3, 3)
                    && doors >= 1
                    && lights >= 1) {
                final long minedReserveLogs = reserveLogs.stream()
                        .filter(pos -> helper.getLevel()
                                .getBlockState(pos)
                                .isAir())
                        .count();
                helper.assertTrue(
                        body.getStats().getValue(
                            Stats.BLOCK_MINED.get(fixtureWoodBlock)
                        ) - initialMinedLogs
                            >= REQUIRED_LOGS + minedReserveLogs,
                        "Shelter material preparation did not physically "
                            + "mine the reserve wood"
                );
                helper.assertTrue(
                        minedReserveLogs > 0,
                        "Shelter material preparation did not consume any "
                            + "physical reserve wood"
                );
                helper.assertTrue(
                        body.getStats().getValue(
                            Stats.ITEM_CRAFTED.get(fixtureDoorItem)
                        ) - initialCraftedDoors >= 3,
                        "Shelter door did not come from its vanilla recipe"
                );
                helper.assertTrue(
                        body.getStats().getValue(
                            Stats.ITEM_CRAFTED.get(Items.TORCH)
                        ) - initialCraftedTorches >= 4,
                        "Shelter light did not come from its vanilla recipe"
                );
                stage = FoundationBootstrapStage.SHELTER;
                stageStartedNanos = System.nanoTime();
                return;
            }
            final GoalSnapshot goal = runtime.goals().snapshot();
            helper.assertTrue(
                    goal.status() == GoalStatus.RUNNING
                        || goal.status() == GoalStatus.CANCEL_PENDING,
                    "Foundation goal became terminal before shelter "
                        + "materials: " + goal
                        + ", planks=" + structural
                        + ", doors=" + doors
                        + ", lights=" + lights
                        + ", reserve=" + reserveLogSummary()
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
                        + ", checkpoint=" + runtime.skillSupervisor()
                            .lastCheckpointPayload().orElse("<none>")
            );
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= FOUNDATION_TOOLKIT_TIMEOUT_NANOS,
                    "Live foundation route did not prepare shelter "
                        + "materials: planks=" + structural
                        + ", doors=" + doors
                        + ", lights=" + lights
                        + ", reserve=" + reserveLogSummary()
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
            );
        }

        private void waitForShelter() {
            assertNoHumanPlayersDuringAutonomy();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died while constructing the M1 shelter"
            );
            final var evidence = runtime.worldData()
                    .verifiedShelterEvidence(foundationGoalRevision);
            final boolean verified = evidence
                    .filter(found ->
                            ServerShelterEvidenceVerifier.verify(
                                    runtime.server(),
                                    found
                            )
                    )
                    .isPresent();
            final boolean milestone = runtime.worldData()
                    .verifiedRouteProgress(foundationGoalRevision)
                    .milestones()
                    .contains(SurvivalMilestone.SHELTER_COMPLETED);
            if (verified && milestone) {
                final var shelter = evidence.orElseThrow();
                helper.assertTrue(
                        shelter.interiorWidth() >= 3
                            && shelter.interiorDepth() >= 3
                            && shelter.interiorHeight() >= 2,
                        "Verified M1 shelter was below the required "
                            + "3x3x2 interior"
                );
                if (zeroHumanFromStart) {
                    assertAutonomousChunkSimulation(body);
                }
                stage = FoundationBootstrapStage.FIRST_NIGHT;
                stageStartedNanos = System.nanoTime();
                return;
            }
            final var skill = runtime.skillSupervisor().snapshot();
            final GoalSnapshot goal = runtime.goals().snapshot();
            helper.assertTrue(
                    goal.status() == GoalStatus.RUNNING
                        || goal.status() == GoalStatus.CANCEL_PENDING,
                    "Foundation goal became terminal before shelter "
                        + "verification: " + goal
                        + ", evidence=" + evidence
                        + ", verified=" + verified
                        + ", milestone=" + milestone
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
            );
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= FOUNDATION_TOOLKIT_TIMEOUT_NANOS,
                    "Live foundation route did not complete a verified "
                        + "dynamic shelter: evidence=" + evidence
                        + ", verified=" + verified
                        + ", milestone=" + milestone
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
            );
        }

        private void waitForFirstNight() {
            assertNoHumanPlayersDuringAutonomy();
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died before reaching the second day"
            );
            final var milestones = runtime.worldData()
                    .verifiedRouteProgress(foundationGoalRevision)
                    .milestones();
            final boolean survived = milestones.contains(
                    SurvivalMilestone.FIRST_NIGHT_SURVIVED
            );
            final boolean shelterStillValid = runtime.worldData()
                    .verifiedShelterEvidence(foundationGoalRevision)
                    .filter(evidence ->
                            ServerShelterEvidenceVerifier.verify(
                                    runtime.server(),
                                    evidence
                            )
                    )
                    .isPresent();
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (survived
                    && shelterStillValid
                    && goal.status() == GoalStatus.COMPLETED) {
                helper.assertTrue(
                        milestones.contains(
                            SurvivalMilestone.SHELTER_COMPLETED
                        ),
                        "First-night completion lost shelter evidence"
                );
                stage = FoundationBootstrapStage.DONE;
                helper.succeed();
                return;
            }
            helper.assertTrue(
                    goal.status() == GoalStatus.RUNNING
                        || goal.status() == GoalStatus.CANCEL_PENDING
                        || survived
                            && goal.status() == GoalStatus.COMPLETED,
                    "Foundation goal failed before verified second day: "
                        + goal + ", survived=" + survived
                        + ", shelterValid=" + shelterStillValid
            );
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= FOUNDATION_TOOLKIT_TIMEOUT_NANOS,
                    "Live foundation route did not survive the actual "
                        + "night and complete its goal: clock="
                        + runtime.server().overworld()
                            .getOverworldClockTime()
                        + ", milestones=" + milestones
                        + ", goal=" + goal
                        + ", shelterValid=" + shelterStillValid
            );
        }

        private void prepareFixture(final ServerPlayer body) {
            final var level = helper.getLevel();
            helper.setTime(18_000L);
            level.getEntitiesOfClass(
                    Mob.class,
                    body.getBoundingBox().inflate(48.0)
            ).forEach(Mob::discard);
            final Vec3 positionBeforeRelocation = body.position();
            final boolean boundedRelocation = completeAfterStoneTools
                    || completeAfterIron
                    || storageTask();
            final int iteration = Integer.getInteger(
                    "mcai.testIteration",
                    1
            );
            if (boundedRelocation) {
                helper.assertTrue(
                        iteration >= 1 && iteration <= 100,
                        "Test iteration must be between 1 and 100"
                );
            }
            fixtureVariant = boundedRelocation
                    ? NonOceanFixtureVariant.forIteration(iteration)
                    : NonOceanFixtureVariant.OAK_FLAT;
            fixtureWoodBlock = fixtureVariant.woodBlock();
            fixtureWoodItem = fixtureVariant.woodItem();
            fixturePlanksItem = fixtureVariant.planksItem();
            fixtureDoorBlock = fixtureVariant.doorBlock();
            fixtureDoorItem = fixtureVariant.doorItem();
            final BlockPos center;
            if (zeroHumanFromStart) {
                center = helper.absolutePos(
                        new BlockPos(8, 1, 8)
                ).offset(640, 0, 0);
            } else if (boundedRelocation) {
                center = helper.absolutePos(
                        new BlockPos(8, 1, 8)
                ).offset(256 + (iteration - 1) * 128, 0, 192);
            } else {
                center = body.blockPosition()
                        .below()
                        .offset(0, 0, 4);
            }
            for (int x = -10; x <= 10; x++) {
                for (int z = -10; z <= 10; z++) {
                    final int height = fixtureVariant.surfaceHeight(x, z);
                    for (int y = -2; y <= height; y++) {
                        level.setBlockAndUpdate(
                                center.offset(x, y, z),
                                Blocks.DIRT.defaultBlockState()
                        );
                    }
                    for (int y = height + 1; y <= 8; y++) {
                        level.setBlockAndUpdate(
                                center.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            fixtureVariant.placeObstacles(level, center);
            final BlockPos base = fixtureVariant.surfaceAbove(center, 0, 0);
            logs = List.of(
                    base,
                    base.above(),
                    base.above(2),
                    base.above(3),
                    base.above(2).east(),
                    base.above(2).west(),
                    base.above(3).north(),
                    base.above(3).south()
            );
            logs.forEach(pos -> level.setBlockAndUpdate(
                    pos,
                    fixtureWoodBlock.defaultBlockState()
            ));
            final List<BlockPos> preparedReserveLogs =
                    new ArrayList<>();
            /*
             * The empty-inventory route can consume the original eight logs
             * on its table, three pickaxes, shield and chest before shelter
             * preparation starts. The shelter then needs 55 planks, a door,
             * sticks and, when the iron furnace used the last coal, one
             * retained log plus a plank for legal charcoal smelting. Four
             * rows left the controlled underground fixture two logs short
             * after every staged log had been physically mined. Five rows
             * provide that bounded recipe budget plus a small pickup margin;
             * they remain ordinary world blocks and the oracle still
             * requires the production body to discover, mine and collect
             * them after the test starts.
             */
            for (int x = -9; x <= -5; x++) {
                for (int z = -2; z <= 1; z++) {
                    preparedReserveLogs.add(
                            fixtureVariant.surfaceAbove(center, x, z)
                    );
                }
            }
            if (storageTask()) {
                preparedReserveLogs.add(
                        fixtureVariant.surfaceAbove(center, -4, -2)
                );
                preparedReserveLogs.add(
                        fixtureVariant.surfaceAbove(center, -4, -1)
                );
            }
            reserveLogs = List.copyOf(preparedReserveLogs);
            reserveLogs.forEach(pos -> level.setBlockAndUpdate(
                    pos,
                    fixtureWoodBlock.defaultBlockState()
            ));
            final List<BlockPos> preparedStone =
                    new ArrayList<>();
            for (int x = -3; x <= 0; x++) {
                for (int y = 0; y <= 2; y++) {
                    preparedStone.add(
                            fixtureVariant.surfaceAbove(center, x, 4)
                                    .above(y)
                    );
                }
            }
            stoneBlocks = List.copyOf(preparedStone);
            stoneBlocks.forEach(pos -> level.setBlockAndUpdate(
                    pos,
                    Blocks.STONE.defaultBlockState()
            ));
            coalBlocks = List.of(
                    fixtureVariant.surfaceAbove(center, 1, 4),
                    fixtureVariant.surfaceAbove(center, 1, 4).above()
            );
            coalBlocks.forEach(pos -> level.setBlockAndUpdate(
                    pos,
                    Blocks.COAL_ORE.defaultBlockState()
            ));
            ironBlocks = List.of(
                    fixtureVariant.surfaceAbove(center, 2, 4),
                    fixtureVariant.surfaceAbove(center, 2, 4).above(),
                    fixtureVariant.surfaceAbove(center, 2, 4).above(2),
                    fixtureVariant.surfaceAbove(center, 3, 4),
                    fixtureVariant.surfaceAbove(center, 3, 4).above(),
                    fixtureVariant.surfaceAbove(center, 3, 4).above(2),
                    fixtureVariant.surfaceAbove(center, 4, 4)
            );
            ironBlocks.forEach(pos -> level.setBlockAndUpdate(
                    pos,
                    Blocks.IRON_ORE.defaultBlockState()
            ));
            final List<Cow> preparedAnimals = new ArrayList<>();
            final int animalCount = completeAfterStoneTools
                    || completeAfterIron
                    || storageTask()
                            ? 0 : 8;
            for (int index = 0; index < animalCount; index++) {
                final Cow cow = EntityTypes.COW.create(
                        level,
                        EntitySpawnReason.COMMAND
                );
                helper.assertTrue(
                        cow != null,
                        "Live foundation fixture could not create cow"
                );
                cow.setBaby(false);
                cow.setNoAi(true);
                cow.setHealth(1.0F);
                final int column = index % 4;
                final int row = index / 4;
                cow.setPos(
                        center.getX() - 3.0 + column * 1.75,
                        center.getY() + 1.0,
                        center.getZ() + 1.0 + row * 1.75
                );
                helper.assertTrue(
                        level.addFreshEntity(cow),
                        "Live foundation fixture could not add cow"
                );
                preparedAnimals.add(cow);
            }
            foodAnimals = List.copyOf(preparedAnimals);
            body.setGameMode(GameType.SURVIVAL);
            body.getInventory().clearContent();
            body.getEnderChestInventory().clearContent();
            body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
            body.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            body.getInventory().setSelectedSlot(0);
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.teleportTo(
                    center.getX() + 0.5,
                    center.getY()
                        + fixtureVariant.surfaceHeight(0, -4)
                        + 1.0,
                    center.getZ() - 3.5
            );
            autonomousWorkCenter = center.immutable();
            autonomousAnchorChunk = body.chunkPosition();
            body.setDeltaMovement(Vec3.ZERO);
            body.fallDistance = 0.0F;
            if (completeAfterStoneTools
                    || completeAfterIron
                    || storageTask()) {
                helper.assertTrue(
                        positionBeforeRelocation.distanceToSqr(
                                body.position()
                        ) >= 64.0D * 64.0D,
                        "Fast stone-tool fixture did not relocate the "
                            + "companion to a new area"
                );
                helper.assertTrue(
                        body.getInventory().isEmpty()
                            && body.getMainHandItem().isEmpty()
                            && body.getOffhandItem().isEmpty()
                            && body.getItemBySlot(
                                EquipmentSlot.HEAD
                            ).isEmpty()
                            && body.getItemBySlot(
                                EquipmentSlot.CHEST
                            ).isEmpty()
                            && body.getItemBySlot(
                                EquipmentSlot.LEGS
                            ).isEmpty()
                            && body.getItemBySlot(
                                EquipmentSlot.FEET
                            ).isEmpty(),
                        "Fast stone-tool fixture did not start with an "
                            + "empty inventory and equipment set"
                );
            }
        }

        private void assertFastTerminalDeadline() {
            if (!(completeAfterStoneTools
                    || completeAfterIron
                    || storageTask())
                    || taskSubmittedNanos < 0L
                    || stage == FoundationBootstrapStage.DONE) {
                return;
            }
            final long deadline = completeAfterStoneTools
                    ? FAST_STONE_TOOLS_DEADLINE_NANOS
                    : completeAfterIron
                        ? FAST_IRON_DEADLINE_NANOS
                        : completeAfterContainerWoodDoor
                            ? STORAGE_AND_DOOR_DEADLINE_NANOS
                            : DISTRIBUTED_STORAGE_DEADLINE_NANOS;
            helper.assertTrue(
                    System.nanoTime() - taskSubmittedNanos
                        <= deadline,
                    "The encoded zero-inventory route did not finish "
                        + "within its real-time deadline: stage=" + stage
                        + ", skill="
                        + runtime.skillSupervisor().snapshot().skillName()
            );
        }

        private void assertAutonomousChunkSimulation(
                final ServerPlayer body
        ) {
            helper.assertTrue(
                    autonomousWorkCenter != null
                        && autonomousAnchorChunk != null,
                    "Unattended work-area ticket evidence was not "
                        + "initialized"
            );
            final double deltaX = body.getX()
                    - (autonomousWorkCenter.getX() + 0.5D);
            final double deltaZ = body.getZ()
                    - (autonomousWorkCenter.getZ() + 0.5D);
            final double horizontalDistanceSquared =
                    deltaX * deltaX + deltaZ * deltaZ;
            helper.assertTrue(
                    horizontalDistanceSquared
                        <= AUTONOMOUS_WORK_RADIUS
                            * AUTONOMOUS_WORK_RADIUS,
                    "Unattended companion left its bounded work area: "
                        + "center=" + autonomousWorkCenter
                        + ", body=" + body.position()
                        + ", radius=" + AUTONOMOUS_WORK_RADIUS
            );

            /*
             * The 21x21 fixture necessarily straddles chunk boundaries for
             * many random GameTest origins. A real player gathering ore is
             * expected to cross those boundaries, so equality with the
             * initial chunk is not a valid ticket invariant. Assert both the
             * original work anchor and the body's current chunk instead: the
             * former proves the nearby work area remains simulated and the
             * latter proves the vanilla player ticket followed the moving
             * headless body.
             */
            assertVanillaPlayerTicketSimulation(
                    autonomousAnchorChunk,
                    "initial work anchor"
            );
            assertVanillaPlayerTicketSimulation(
                    body.chunkPosition(),
                    "current companion chunk"
            );
        }

        private void assertVanillaPlayerTicketSimulation(
                final ChunkPos chunk,
                final String role
        ) {
            helper.assertTrue(
                    helper.getLevel().shouldTickBlocksAt(
                            chunk.pack()
                    ),
                    "Unattended companion did not maintain block "
                        + "simulation in " + role
                        + " through its player ticket: " + chunk
            );
            helper.assertTrue(
                    helper.getLevel()
                        .areEntitiesActuallyLoadedAndTicking(
                                chunk
                        ),
                    "Unattended companion did not maintain entity "
                        + "simulation in " + role
                        + " through its player ticket: " + chunk
            );
            helper.assertTrue(
                    !helper.getLevel()
                        .getChunkSource()
                        .getForceLoadedChunks()
                        .contains(chunk.pack()),
                    "Unattended test force-loaded " + role
                        + " instead of relying on the companion's vanilla "
                        + "player ticket: " + chunk
            );
        }

        private boolean latestObservationSeesFixtureWood() {
            final Optional<String> semantic =
                    runtime.observations().latestSemanticJson();
            if (semantic.isEmpty()) {
                return false;
            }
            try {
                final var root = JsonParser
                        .parseString(semantic.orElseThrow())
                        .getAsJsonObject();
                final String expectedId = BuiltInRegistries.BLOCK
                        .getKey(fixtureWoodBlock)
                        .toString();
                for (var element
                        : root.getAsJsonArray("visibleBlockFaces")) {
                    if (expectedId.equals(
                            element.getAsJsonObject()
                                .get("type").getAsString()
                    )) {
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException malformedObservation) {
                return false;
            }
        }

        private String logSummary() {
            return logs.stream()
                    .map(pos -> pos + "="
                            + helper.getLevel().getBlockState(pos))
                    .toList()
                    .toString();
        }

        private String stoneSummary() {
            return stoneBlocks.stream()
                    .map(pos -> pos + "="
                            + helper.getLevel().getBlockState(pos))
                    .toList()
                    .toString();
        }

        private String reserveLogSummary() {
            return reserveLogs.stream()
                    .map(pos -> pos + "="
                            + helper.getLevel().getBlockState(pos))
                    .toList()
                    .toString();
        }

        private String coalSummary() {
            return coalBlocks.stream()
                    .map(pos -> pos + "="
                            + helper.getLevel().getBlockState(pos))
                    .toList()
                    .toString();
        }

        private String ironSummary() {
            return ironBlocks.stream()
                    .map(pos -> pos + "="
                            + helper.getLevel().getBlockState(pos))
                    .toList()
                    .toString();
        }

        private void assertWithinModelDeadline(
                final String message
        ) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private void assertNoHumanPlayersDuringAutonomy() {
            assertNoHumanPlayers(
                    "after the initial chat command"
            );
        }

        private void assertNoHumanPlayers(final String phaseDescription) {
            final long humanPlayers = runtime.server()
                    .getPlayerList()
                    .getPlayers()
                    .stream()
                    .filter(player -> !player.getUUID().equals(
                            runtime.worldData().companionUuid()
                    ))
                    .count();
            helper.assertTrue(
                    humanPlayers == 0L,
                    "Live autonomous foundation bootstrap observed "
                        + humanPlayers + " human player(s) "
                        + phaseDescription
            );
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            foodAnimals.stream()
                    .filter(Cow::isAlive)
                    .forEach(Cow::discard);
            if (humanSession != null) {
                humanSession.close();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    /**
     * Ten deterministic land fixtures used by the repeated black-box gates.
     * Only the test world builder knows these variants. Production skills see
     * the same first-person semantic frames they receive in an ordinary world
     * and must discover the wood family, terrain, and route at runtime.
     */
    private enum NonOceanFixtureVariant {
        OAK_FLAT(
                Blocks.OAK_LOG,
                Items.OAK_LOG,
                Items.OAK_PLANKS,
                Blocks.OAK_DOOR,
                Items.OAK_DOOR,
                Blocks.OAK_LEAVES,
                TerrainShape.FLAT
        ),
        SPRUCE_CROSS_SLOPE(
                Blocks.SPRUCE_LOG,
                Items.SPRUCE_LOG,
                Items.SPRUCE_PLANKS,
                Blocks.SPRUCE_DOOR,
                Items.SPRUCE_DOOR,
                Blocks.SPRUCE_LEAVES,
                TerrainShape.CROSS_SLOPE
        ),
        BIRCH_SHALLOW_BASIN(
                Blocks.BIRCH_LOG,
                Items.BIRCH_LOG,
                Items.BIRCH_PLANKS,
                Blocks.BIRCH_DOOR,
                Items.BIRCH_DOOR,
                Blocks.BIRCH_LEAVES,
                TerrainShape.SHALLOW_BASIN
        ),
        JUNGLE_LONG_SLOPE(
                Blocks.JUNGLE_LOG,
                Items.JUNGLE_LOG,
                Items.JUNGLE_PLANKS,
                Blocks.JUNGLE_DOOR,
                Items.JUNGLE_DOOR,
                Blocks.JUNGLE_LEAVES,
                TerrainShape.LONG_SLOPE
        ),
        ACACIA_LOW_RIDGE(
                Blocks.ACACIA_LOG,
                Items.ACACIA_LOG,
                Items.ACACIA_PLANKS,
                Blocks.ACACIA_DOOR,
                Items.ACACIA_DOOR,
                Blocks.ACACIA_LEAVES,
                TerrainShape.LOW_RIDGE
        ),
        DARK_OAK_TERRACES(
                Blocks.DARK_OAK_LOG,
                Items.DARK_OAK_LOG,
                Items.DARK_OAK_PLANKS,
                Blocks.DARK_OAK_DOOR,
                Items.DARK_OAK_DOOR,
                Blocks.DARK_OAK_LEAVES,
                TerrainShape.TERRACES
        ),
        MANGROVE_BROKEN_GROUND(
                Blocks.MANGROVE_LOG,
                Items.MANGROVE_LOG,
                Items.MANGROVE_PLANKS,
                Blocks.MANGROVE_DOOR,
                Items.MANGROVE_DOOR,
                Blocks.MANGROVE_LEAVES,
                TerrainShape.BROKEN_GROUND
        ),
        CHERRY_ROLLING_GROUND(
                Blocks.CHERRY_LOG,
                Items.CHERRY_LOG,
                Items.CHERRY_PLANKS,
                Blocks.CHERRY_DOOR,
                Items.CHERRY_DOOR,
                Blocks.CHERRY_LEAVES,
                TerrainShape.ROLLING_GROUND
        ),
        CRIMSON_STEPPED_GROUND(
                Blocks.CRIMSON_STEM,
                Items.CRIMSON_STEM,
                Items.CRIMSON_PLANKS,
                Blocks.CRIMSON_DOOR,
                Items.CRIMSON_DOOR,
                Blocks.NETHER_WART_BLOCK,
                TerrainShape.STEPPED_GROUND
        ),
        WARPED_OFFSET_RIDGE(
                Blocks.WARPED_STEM,
                Items.WARPED_STEM,
                Items.WARPED_PLANKS,
                Blocks.WARPED_DOOR,
                Items.WARPED_DOOR,
                Blocks.WARPED_WART_BLOCK,
                TerrainShape.OFFSET_RIDGE
        );

        private final Block woodBlock;
        private final Item woodItem;
        private final Item planksItem;
        private final Block doorBlock;
        private final Item doorItem;
        private final Block canopyBlock;
        private final TerrainShape terrainShape;

        NonOceanFixtureVariant(
                final Block woodBlock,
                final Item woodItem,
                final Item planksItem,
                final Block doorBlock,
                final Item doorItem,
                final Block canopyBlock,
                final TerrainShape terrainShape
        ) {
            this.woodBlock = woodBlock;
            this.woodItem = woodItem;
            this.planksItem = planksItem;
            this.doorBlock = doorBlock;
            this.doorItem = doorItem;
            this.canopyBlock = canopyBlock;
            this.terrainShape = terrainShape;
        }

        static NonOceanFixtureVariant forIteration(final int iteration) {
            final NonOceanFixtureVariant[] variants = values();
            return variants[Math.floorMod(iteration - 1, variants.length)];
        }

        Block woodBlock() {
            return woodBlock;
        }

        Item woodItem() {
            return woodItem;
        }

        Item planksItem() {
            return planksItem;
        }

        Block doorBlock() {
            return doorBlock;
        }

        Item doorItem() {
            return doorItem;
        }

        int surfaceHeight(final int x, final int z) {
            return terrainShape.surfaceHeight(x, z);
        }

        BlockPos surfaceAbove(
                final BlockPos center,
                final int x,
                final int z
        ) {
            return center.offset(x, surfaceHeight(x, z) + 1, z);
        }

        void placeObstacles(
                final net.minecraft.server.level.ServerLevel level,
                final BlockPos center
        ) {
            final BlockPos trunkTop = surfaceAbove(center, 0, 0).above(3);
            level.setBlockAndUpdate(
                    trunkTop.east(2),
                    canopyBlock.defaultBlockState()
            );
            level.setBlockAndUpdate(
                    trunkTop.west(2),
                    canopyBlock.defaultBlockState()
            );
            level.setBlockAndUpdate(
                    trunkTop.north(2),
                    canopyBlock.defaultBlockState()
            );
            if (this != OAK_FLAT) {
                final BlockPos boulder = surfaceAbove(center, 6, -1);
                level.setBlockAndUpdate(
                        boulder,
                        Blocks.COBBLESTONE.defaultBlockState()
                );
                if (ordinal() % 2 == 0) {
                    level.setBlockAndUpdate(
                            boulder.above(),
                            Blocks.COBBLESTONE.defaultBlockState()
                    );
                }
            }
        }
    }

    private enum TerrainShape {
        FLAT,
        CROSS_SLOPE,
        SHALLOW_BASIN,
        LONG_SLOPE,
        LOW_RIDGE,
        TERRACES,
        BROKEN_GROUND,
        ROLLING_GROUND,
        STEPPED_GROUND,
        OFFSET_RIDGE;

        int surfaceHeight(final int x, final int z) {
            return switch (this) {
                case FLAT -> 0;
                case CROSS_SLOPE -> x >= 5 ? 1 : x <= -5 ? -1 : 0;
                case SHALLOW_BASIN ->
                        Math.abs(x) <= 2 && Math.abs(z) <= 2 ? -1 : 0;
                case LONG_SLOPE -> z >= 5 ? 1 : z <= -6 ? -1 : 0;
                case LOW_RIDGE ->
                        Math.abs(z - 1) <= 1 && Math.abs(x) >= 3 ? 1 : 0;
                case TERRACES -> x >= 6 ? 2 : x >= 2 ? 1
                        : x <= -7 ? -1 : 0;
                case BROKEN_GROUND ->
                        Math.abs(x) >= 7 && (x + z) % 3 == 0 ? -1 : 0;
                case ROLLING_GROUND ->
                        Math.abs(x - 5) + Math.abs(z + 3) <= 3 ? 1
                                : Math.abs(x + 5) + Math.abs(z - 3) <= 2
                                    ? -1 : 0;
                case STEPPED_GROUND -> z >= 6 ? 2 : z >= 2 ? 1
                        : z <= -7 ? -1 : 0;
                case OFFSET_RIDGE ->
                        Math.abs(x + z - 3) <= 1 && Math.abs(x) >= 3
                                ? 1 : 0;
            };
        }
    }

    private enum FoundationBootstrapStage {
        BODY,
        PROBE,
        SETTLE,
        GOAL,
        GATHER,
        BASIC_CRAFTING,
        STONE_GATHERING,
        STONE_CRAFTING,
        DISTRIBUTED_STORAGE,
        CONTAINER_DOOR_GOAL,
        CONTAINER_DOOR,
        FOOD,
        IRON_TOOLKIT,
        WORKSTATIONS,
        STORAGE,
        SHELTER_MATERIALS,
        SHELTER,
        FIRST_NIGHT,
        DONE
    }

    private static final class LiveShelterRelocationScenario {
        private static final long BUILD_TIMEOUT_NANOS =
                java.time.Duration.ofMinutes(6).toNanos();
        private static final double MINIMUM_RELOCATION_DISTANCE = 1.5D;

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;

        private ShelterRelocationStage stage =
                ShelterRelocationStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private BlockPos initialFeet;
        private BlockPos craftingTable;
        private BlockPos furnace;
        private BlockPos chest;
        private long goalRevisionBefore;
        private long goalRevision;
        private long stageStartedNanos;
        private int stableTicks;
        private int initialPlankCount;
        private boolean sawBuildSkill;

        private LiveShelterRelocationScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            this.createdAt = helper.getTick();
            this.stageStartedNanos = System.nanoTime();
        }

        private void start() {
            finishScenarioGoal(runtime);
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(runtime.server())
                                .accepted(),
                        "Live-shelter companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case SETTLE -> waitForSettlement();
                case GOAL -> waitForGoal();
                case BUILD -> waitForShelter();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Live-shelter companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Live-shelter companion body timed out"
                );
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            prepareFixture(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = ShelterRelocationStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "Live shelter model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            stage = ShelterRelocationStage.SETTLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForSettlement() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(furnace)
            );
            body.setYHeadRot(body.getYRot());
            if (!body.onGround()) {
                stableTicks = 0;
                return;
            }
            if (++stableTicks < 12
                    || !latestObservationMatchesFixture(body)) {
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= java.time.Duration.ofSeconds(20)
                                .toNanos(),
                        "Live-shelter semantic view did not settle on "
                            + "the workstation fixture"
                );
                return;
            }
            initialPlankCount = body.getInventory()
                    .countItem(Items.OAK_PLANKS);
            goalRevisionBefore = runtime.goals()
                    .snapshot()
                    .revision();
            humanSession = PlacedHuman.create(helper, runtime);
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            humanSession.player(),
                            Component.literal(
                                runtime.worldData().displayName()
                                    + "，去旁边安全的空地给我们建一个"
                                    + "紧凑的、有门和照明的房子。材料"
                                    + "已经在你背包里，别只说，直接动手。"
                            )
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the shelter chat command"
            );
            humanSession.close();
            humanSession = null;
            stage = ShelterRelocationStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            assertNoHumanPlayersDuringAutonomy();
            assertWithinModelDeadline(
                    "Live model did not classify the shelter task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.goal().contains("房子"),
                    "Authorized shelter chat was not preserved as a task: "
                        + goal
            );
            goalRevision = goal.revision();
            stage = ShelterRelocationStage.BUILD;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForShelter() {
            assertNoHumanPlayersDuringAutonomy();
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died during isolated shelter construction"
            );
            final var skill = runtime.skillSupervisor().snapshot();
            if ("build_shelter_step".equals(skill.skillName())) {
                sawBuildSkill = true;
            }
            final var evidence = runtime.worldData()
                    .verifiedShelterEvidence(goalRevision);
            final boolean verified = evidence
                    .filter(found ->
                            ServerShelterEvidenceVerifier.verify(
                                    runtime.server(),
                                    found
                            )
                    )
                    .isPresent();
            if (verified) {
                final double moved = Math.sqrt(
                        body.distanceToSqr(
                                Vec3.atBottomCenterOf(initialFeet)
                        )
                );
                helper.assertTrue(
                        sawBuildSkill,
                        "Shelter appeared without the production "
                            + "build_shelter_step skill"
                );
                helper.assertTrue(
                        moved >= MINIMUM_RELOCATION_DISTANCE,
                        "Crowded shelter fixture completed without ordinary "
                            + "relocation: moved=" + moved
                );
                helper.assertTrue(
                        body.getInventory().countItem(Items.OAK_PLANKS)
                            < initialPlankCount,
                        "Verified shelter did not consume owned planks"
                );
                assertWorkstationsRemain();
                stage = ShelterRelocationStage.DONE;
                helper.succeed();
                return;
            }
            final GoalSnapshot currentGoal =
                    runtime.goals().snapshot();
            helper.assertTrue(
                    currentGoal.status() != GoalStatus.SAFE_IDLE
                            && currentGoal.status()
                                    != GoalStatus.FAILED,
                    "Live shelter entered a terminal failure before "
                            + "physical completion: position="
                            + body.position()
                            + ", initialFeet=" + initialFeet
                            + ", skill=" + skill.skillName()
                            + ", terminalResult="
                            + skill.terminalResult()
                            + ", rejection="
                            + skill.lastStartRejection()
                            + ", goal=" + currentGoal
                            + ", evidence=" + evidence
            );
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= BUILD_TIMEOUT_NANOS,
                    "Live model did not physically relocate and complete "
                        + "the seeded shelter: position="
                        + body.position()
                        + ", initialFeet=" + initialFeet
                        + ", inventory=" + body.getInventory()
                        + ", skill=" + skill.skillName()
                        + ", rejection=" + skill.lastStartRejection()
                        + ", goal=" + runtime.goals().snapshot()
                        + ", evidence=" + evidence
            );
        }

        private void prepareFixture(final ServerPlayer body) {
            final var level = helper.getLevel();
            level.getEntitiesOfClass(
                    Mob.class,
                    body.getBoundingBox().inflate(48.0)
            ).forEach(Mob::discard);
            final BlockPos floorCenter =
                    helper.absolutePos(new BlockPos(12, 1, 12));
            for (int x = -12; x <= 12; x++) {
                for (int z = -12; z <= 12; z++) {
                    final BlockPos floor = floorCenter.offset(x, 0, z);
                    level.setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 1; y <= 4; y++) {
                        level.setBlockAndUpdate(
                                floor.above(y),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            initialFeet = floorCenter.above();
            craftingTable = initialFeet.offset(-2, 0, -2);
            furnace = initialFeet.offset(-1, 0, -1);
            chest = initialFeet.offset(0, 0, -2);
            level.setBlockAndUpdate(
                    craftingTable,
                    Blocks.CRAFTING_TABLE.defaultBlockState()
            );
            level.setBlockAndUpdate(
                    furnace,
                    Blocks.FURNACE.defaultBlockState()
            );
            level.setBlockAndUpdate(
                    chest,
                    Blocks.CHEST.defaultBlockState()
            );
            body.setGameMode(GameType.SURVIVAL);
            body.getInventory().clearContent();
            body.getInventory().setSelectedSlot(0);
            body.getInventory().setItem(
                    0,
                    new ItemStack(Items.OAK_PLANKS, 64)
            );
            body.getInventory().setItem(
                    1,
                    new ItemStack(Items.OAK_DOOR, 3)
            );
            body.getInventory().setItem(
                    2,
                    new ItemStack(Items.TORCH, 4)
            );
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.getFoodData().setSaturation(5.0F);
            body.teleportTo(
                    initialFeet.getX() + 0.5,
                    initialFeet.getY(),
                    initialFeet.getZ() + 0.5
            );
            body.setDeltaMovement(Vec3.ZERO);
            body.fallDistance = 0.0F;
            body.inventoryMenu.broadcastChanges();
        }

        private boolean latestObservationMatchesFixture(
                final ServerPlayer body
        ) {
            final Optional<String> semantic =
                    runtime.observations().latestSemanticJson();
            if (semantic.isEmpty()) {
                return false;
            }
            try {
                final var root = JsonParser
                        .parseString(semantic.orElseThrow())
                        .getAsJsonObject();
                final var self = root.getAsJsonObject("self");
                if (self == null
                        || !self.get("onGround").getAsBoolean()) {
                    return false;
                }
                final var position =
                        self.getAsJsonObject("position");
                final double dx = position.get("x").getAsDouble()
                        - body.getX();
                final double dy = position.get("y").getAsDouble()
                        - body.getY();
                final double dz = position.get("z").getAsDouble()
                        - body.getZ();
                return dx * dx + dy * dy + dz * dz <= 0.25D;
            } catch (RuntimeException malformedObservation) {
                return false;
            }
        }

        private void assertWorkstationsRemain() {
            helper.assertTrue(
                    helper.getLevel().getBlockState(craftingTable)
                            .is(Blocks.CRAFTING_TABLE)
                        && helper.getLevel().getBlockState(furnace)
                            .is(Blocks.FURNACE)
                        && helper.getLevel().getBlockState(chest)
                            .is(Blocks.CHEST),
                    "Shelter relocation overwrote the existing "
                        + "workstation cluster"
            );
        }

        private void assertNoHumanPlayersDuringAutonomy() {
            final long humanPlayers = runtime.server()
                    .getPlayerList()
                    .getPlayers()
                    .stream()
                    .filter(player -> !player.getUUID().equals(
                            runtime.worldData().companionUuid()
                    ))
                    .count();
            helper.assertTrue(
                    humanPlayers == 0L,
                    "Live shelter autonomy retained "
                        + humanPlayers + " human player(s)"
            );
        }

        private void assertWithinModelDeadline(
                final String message
        ) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum ShelterRelocationStage {
        BODY,
        PROBE,
        SETTLE,
        GOAL,
        BUILD,
        DONE
    }

    private static final class LiveFarmScenario {
        private static final int REQUIRED_PLOTS = 3;

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;

        private FarmStage stage = FarmStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private List<BlockPos> crops = List.of();
        private long stageStartedNanos;
        private long goalRevisionBefore;
        private int stableTicks;
        private int farmingSkillStarts;
        private boolean farmingSkillActive;
        private int initialMinedWheat;

        private LiveFarmScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Live-farm companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case SETTLE -> waitForSettlement();
                case GOAL -> waitForGoal();
                case FARM -> waitForFarmWork();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status =
                    AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Live-farm companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Live-farm companion body timed out"
                );
                return;
            }
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            prepareFixture(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = FarmStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "Live farm model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            stage = FarmStage.SETTLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForSettlement() {
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(crops.get(1))
            );
            body.setYHeadRot(body.getYRot());
            if (!body.onGround()) {
                stableTicks = 0;
                return;
            }
            if (++stableTicks < 8
                    || !latestObservationSeesMatureWheat(body)) {
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= java.time.Duration.ofSeconds(15)
                                .toNanos(),
                        "Live-farm semantic view never exposed the "
                            + "mature field"
                );
                return;
            }
            initialMinedWheat = body.getStats().getValue(
                    Stats.BLOCK_MINED.get(Blocks.WHEAT)
            );
            humanSession = PlacedHuman.create(
                    helper,
                    runtime,
                    new Vec3(
                            crops.get(1).getX() + 0.5,
                            crops.get(1).getY() + 1.0,
                            crops.get(1).getZ() + 0.5
                    )
            );
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in farm test player lacked task-write "
                        + "permission"
            );
            goalRevisionBefore = runtime.goals()
                    .snapshot()
                    .revision();
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            human,
                            Component.literal(
                                runtime.worldData().displayName()
                                    + "，把你面前成熟的三格小麦全部"
                                    + "收割，收完每一格都要重新种上；"
                                    + "把收获物捡进背包，不要漏掉，"
                                    + "也不要用命令。"
                            )
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the farm chat command"
            );
            stage = FarmStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            assertWithinModelDeadline(
                    "Live model did not classify the farm task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.goal().contains("小麦"),
                    "Authorized farm chat was not preserved as the goal"
            );
            if (humanSession != null) {
                humanSession.close();
                humanSession = null;
            }
            stage = FarmStage.FARM;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForFarmWork() {
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            final ServerPlayer body = bodyCandidate.get();
            helper.assertTrue(
                    body.isAlive(),
                    "Companion died during controlled farm work"
            );
            final var skill = runtime.skillSupervisor().snapshot();
            final boolean activeNow =
                    ("harvest_and_replant_step".equals(skill.skillName())
                            || "maintain_observed_crop_field".equals(
                                    skill.skillName()
                            ))
                    && (skill.state()
                        == dev.mcai.companion.skill.SkillSupervisor
                            .State.RUNNING
                        || skill.state()
                        == dev.mcai.companion.skill.SkillSupervisor
                            .State.CANCEL_PENDING);
            if (activeNow && !farmingSkillActive) {
                farmingSkillStarts++;
            }
            farmingSkillActive = activeNow;

            final boolean replanted = crops.stream().allMatch(pos -> {
                final var state = helper.getLevel()
                        .getBlockState(pos);
                return state.is(Blocks.WHEAT)
                        /*
                         * A successfully replanted age-zero crop may grow
                         * while the external model is answering. Requiring
                         * age zero would turn normal random ticks into a
                         * false failure, especially on Mojang's deliberately
                         * accelerated GameTest server. The isolated fixture
                         * began with age-seven crops, so wheat below maturity
                         * proves that every exact plot was replanted.
                         */
                        && state.getValue(CropBlock.AGE)
                            < CropBlock.MAX_AGE;
            });
            final int collectedWheat =
                    body.getInventory().countItem(Items.WHEAT);
            if (replanted && collectedWheat >= REQUIRED_PLOTS) {
                helper.assertTrue(
                        farmingSkillStarts >= 1,
                        "All plots changed without a model-selected "
                            + "production farming skill: "
                            + farmingSkillStarts
                );
                helper.assertTrue(
                        body.getStats().getValue(
                            Stats.BLOCK_MINED.get(Blocks.WHEAT)
                        ) - initialMinedWheat >= REQUIRED_PLOTS,
                        "Farm work did not record three vanilla wheat "
                            + "harvests"
                );
                stage = FarmStage.DONE;
                helper.succeed();
                return;
            }
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.status() != GoalStatus.RUNNING
                    && goal.status() != GoalStatus.CANCEL_PENDING) {
                helper.assertTrue(
                        false,
                        "Live farm goal became terminal before every "
                            + "farm requirement was satisfied: " + goal
                            + ", crops=" + cropSummary()
                            + ", collectedWheat=" + collectedWheat
                            + ", nearbyDrops="
                            + nearbyDropSummary(body)
                            + ", body=" + body.position()
                            + ", starts=" + farmingSkillStarts
                            + ", skill=" + skill.skillName()
                            + ", rejection="
                            + skill.lastStartRejection()
                );
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    "Live model did not harvest and replant all plots: "
                        + cropSummary() + ", starts="
                        + farmingSkillStarts + ", collectedWheat="
                        + collectedWheat + ", nearbyDrops="
                        + nearbyDropSummary(body) + ", skill="
                        + skill.skillName() + ", body="
                        + body.position() + ", rejection="
                        + skill.lastStartRejection()
            );
        }

        private void prepareFixture(final ServerPlayer body) {
            final var level = helper.getLevel();
            level.getEntitiesOfClass(
                    Mob.class,
                    body.getBoundingBox().inflate(48.0)
            ).forEach(Mob::discard);
            final BlockPos center = body.blockPosition()
                    .below()
                    .offset(0, 0, 3);
            crops = List.of(
                    center.above().west(),
                    center.above(),
                    center.above().east()
            );
            for (int x = -3; x <= 3; x++) {
                for (int z = -2; z <= 5; z++) {
                    final BlockPos floor = center.offset(x, 0, z - 3);
                    level.setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE
                                .defaultBlockState()
                    );
                    for (int y = 1; y <= 4; y++) {
                        level.setBlockAndUpdate(
                                floor.above(y),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            for (BlockPos crop : crops) {
                level.setBlockAndUpdate(
                        crop.below(),
                        Blocks.FARMLAND.defaultBlockState()
                );
                level.setBlockAndUpdate(
                        crop,
                        Blocks.WHEAT.defaultBlockState()
                            .setValue(
                                CropBlock.AGE,
                                CropBlock.MAX_AGE
                            )
                );
            }
            body.setGameMode(GameType.SURVIVAL);
            body.getInventory().clearContent();
            body.getInventory().setItem(
                    0,
                    new ItemStack(Items.WHEAT_SEEDS, 8)
            );
            body.getInventory().setSelectedSlot(0);
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.teleportTo(
                    center.getX() + 0.5,
                    center.getY() + 1.0,
                    center.getZ() - 1.5
            );
            body.setDeltaMovement(Vec3.ZERO);
        }

        private boolean latestObservationSeesMatureWheat(
                final ServerPlayer body
        ) {
            final Optional<String> semantic =
                    runtime.observations().latestSemanticJson();
            if (semantic.isEmpty()) {
                return false;
            }
            try {
                final var root = JsonParser
                        .parseString(semantic.orElseThrow())
                        .getAsJsonObject();
                final var self = root.getAsJsonObject("self");
                if (self == null
                        || !self.get("onGround").getAsBoolean()) {
                    return false;
                }
                for (var element
                        : root.getAsJsonArray("visibleBlockFaces")) {
                    final var face = element.getAsJsonObject();
                    if ("minecraft:wheat".equals(
                            face.get("type").getAsString()
                    )
                            && Integer.toString(CropBlock.MAX_AGE)
                                .equals(
                                    face.getAsJsonObject("state")
                                        .get("age").getAsString()
                                )) {
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException malformedObservation) {
                return false;
            }
        }

        private String cropSummary() {
            return crops.stream()
                    .map(pos -> pos + "="
                            + helper.getLevel()
                                .getBlockState(pos))
                    .toList()
                    .toString();
        }

        private List<String> nearbyDropSummary(
                final ServerPlayer body
        ) {
            return helper.getLevel().getEntitiesOfClass(
                            ItemEntity.class,
                            body.getBoundingBox().inflate(12.0)
                    ).stream()
                    .map(drop -> drop.getItem().getItem()
                            + "x" + drop.getItem().getCount()
                            + "@" + drop.position())
                    .toList();
        }

        private void assertWithinModelDeadline(
                final String message
        ) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum FarmStage {
        BODY,
        PROBE,
        SETTLE,
        GOAL,
        FARM,
        DONE
    }

    private static final class LiveEndVictoryScenario {
        private static final long END_ENTRY_TIMEOUT_TICKS = 300;
        private static final long END_SETTLE_TICKS = 80;
        private static final long FIGHT_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(150).toNanos();
        private static final long RETURN_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(150).toNanos();

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;

        private EndVictoryStage stage = EndVictoryStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private EndCrystal crystal;
        private EnderDragon dragon;
        private BlockPos cageBar;
        private BlockPos returnPortalCenter;
        private UUID bodyId;
        private long enteredEndAt;
        private long goalRevisionBefore;
        private long victoryGoalRevision;
        private long stageStartedNanos;
        private boolean fightSkillObserved;
        private boolean returnSkillObserved;

        private LiveEndVictoryScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            finishScenarioGoal(runtime);
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "End-victory companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case ENTERING_END -> waitForEndEntry();
                case SETTLING_END -> waitForEndSettle();
                case PROBE -> waitForProbe();
                case VISIBLE -> waitForVisibleFight();
                case GOAL -> waitForGoal();
                case FIGHT_SKILL -> waitForFightSkill();
                case FIGHTING -> waitForDragonKill();
                case RETURNING -> waitForReturn();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "End-victory companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "End-victory companion body timed out"
                );
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            bodyId = body.getUUID();
            prepareRealEndEntry(body);
            stage = EndVictoryStage.ENTERING_END;
        }

        private void prepareRealEndEntry(final ServerPlayer body) {
            final BlockPos entry = body.blockPosition();
            body.setGameMode(GameType.SURVIVAL);
            body.getInventory().clearContent();
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    helper.getLevel().setBlockAndUpdate(
                            entry.offset(x, -1, z),
                            Blocks.OBSIDIAN.defaultBlockState()
                    );
                    helper.getLevel().setBlockAndUpdate(
                            entry.offset(x, 0, z),
                            Blocks.AIR.defaultBlockState()
                    );
                    helper.getLevel().setBlockAndUpdate(
                            entry.offset(x, 1, z),
                            Blocks.AIR.defaultBlockState()
                    );
                }
            }
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
            body.fallDistance = 0.0F;
        }

        private void waitForEndEntry() {
            final ServerPlayer body = body();
            if (body.level().dimension().equals(Level.END)) {
                enteredEndAt = helper.getTick();
                stage = EndVictoryStage.SETTLING_END;
                return;
            }
            helper.assertTrue(
                    helper.getTick() - createdAt
                        <= END_ENTRY_TIMEOUT_TICKS,
                    "Fixture body did not traverse the real End portal"
            );
        }

        private void waitForEndSettle() {
            final ServerPlayer body = body();
            helper.assertTrue(
                    body.level().dimension().equals(Level.END),
                    "Companion left the End before arena setup"
            );
            if (helper.getTick() - enteredEndAt < END_SETTLE_TICKS) {
                return;
            }
            prepareFightArena(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = EndVictoryStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void prepareFightArena(final ServerPlayer body) {
            final EndVictoryArena prepared =
                    prepareEndVictoryArena(
                            helper,
                            runtime,
                            body,
                            true,
                            true
                    );
            crystal = prepared.crystal();
            dragon = prepared.dragon();
            cageBar = prepared.cageBar();
            returnPortalCenter = prepared.returnPortalCenter();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "End-victory model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            stage = EndVictoryStage.VISIBLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForVisibleFight() {
            assertWithinModelDeadline(
                    "Dragon arena never became fair first-person evidence"
            );
            final var frame = runtime.coreFrames().current();
            if (frame.isEmpty()
                    || !frame.orElseThrow().dimension()
                            .equals(
                                    dev.mcai.companion.waypoint
                                            .DimensionRef.END
                            )
                    || !frame.orElseThrow().onGround()
                    || frame.orElseThrow().visibleEntities()
                            .stream()
                            .noneMatch(entity ->
                                    entity.entityTypeId().equals(
                                            "minecraft:end_crystal"
                                    )
                            )) {
                return;
            }
            humanSession = PlacedHuman.create(helper, runtime);
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in End-victory test player lacked permission"
            );
            goalRevisionBefore = runtime.goals().snapshot().revision();
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            human,
                            Component.literal(
                                    runtime.worldData().displayName()
                                        + "，请击败末影龙，然后进入中央"
                                        + "返回传送门回到主世界。"
                            )
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the End-victory chat command"
            );
            humanSession.close();
            humanSession = null;
            stage = EndVictoryStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            assertNoHumanPlayers();
            assertWithinModelDeadline(
                    "Live model did not classify the End-victory task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.status() == GoalStatus.RUNNING
                        && goal.goal().contains("末影龙"),
                    "End-victory chat did not become a running goal: "
                        + goal
            );
            victoryGoalRevision = goal.revision();
            stage = EndVictoryStage.FIGHT_SKILL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForFightSkill() {
            assertNoHumanPlayers();
            assertWithinModelDeadline(
                    "Live model did not select parameterless "
                        + "fight_ender_dragon; " + diagnostics()
            );
            final var snapshot =
                    runtime.skillSupervisor().snapshot();
            if (!"fight_ender_dragon".equals(
                    snapshot.skillName()
            )) {
                return;
            }
            helper.assertTrue(
                    snapshot.boundGoalRevision()
                        == victoryGoalRevision,
                    "Dragon skill bound the wrong goal revision"
            );
            fightSkillObserved = true;
            stage = EndVictoryStage.FIGHTING;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForDragonKill() {
            assertNoHumanPlayers();
            final var milestones = runtime.worldData()
                    .verifiedRouteProgress(victoryGoalRevision)
                    .milestones();
            if (milestones.contains(
                    SurvivalMilestone.DRAGON_KILLED
            )) {
                final ServerPlayer body = body();
                helper.assertTrue(
                        fightSkillObserved
                            && (!crystal.isAlive()
                                || crystal.isRemoved())
                            && body.getInventory()
                                .countItem(Items.ARROW) < 128,
                        "Dragon milestone lacked physical fight evidence: "
                            + diagnostics()
                );
                /*
                 * This release-excluded fixture freezes the full-health
                 * dragon so the production coordinator has a deterministic
                 * ranged/cage/melee target. Vanilla fires the credited death
                 * event before its 200-tick DYING animation, and a no-AI
                 * dragon otherwise remains at one health forever. Resume the
                 * ordinary death lifecycle here. Production dragons never
                 * receive no-AI, and the emergency lane may legitimately
                 * retain body ownership until this hostile corpse disappears.
                 */
                if (!dragon.isRemoved()) {
                    dragon.setNoAi(false);
                }
                activateEndReturnPortal(
                        runtime.server().getLevel(Level.END),
                        returnPortalCenter
                );
                stage = EndVictoryStage.RETURNING;
                stageStartedNanos = System.nanoTime();
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= FIGHT_TIMEOUT_NANOS,
                    "Live-model dragon fight timed out: "
                        + diagnostics()
            );
        }

        private void waitForReturn() {
            assertNoHumanPlayers();
            final var supervisor =
                    runtime.skillSupervisor().snapshot();
            if ("find_and_enter_observed_portal".equals(
                    supervisor.skillName()
            )) {
                helper.assertTrue(
                        supervisor.boundGoalRevision()
                            == victoryGoalRevision,
                        "Return portal skill bound the wrong goal revision"
                );
                returnSkillObserved = true;
            }
            final ServerPlayer body = body();
            final var milestones = runtime.worldData()
                    .verifiedRouteProgress(victoryGoalRevision)
                    .milestones();
            if (body.level().dimension().equals(Level.OVERWORLD)
                    && milestones.contains(
                            SurvivalMilestone.RETURNED_FROM_END
                    )) {
                helper.assertTrue(
                        fightSkillObserved
                            && returnSkillObserved
                            && bodyId.equals(body.getUUID()),
                        "End return lacked the same live-model body/skills: "
                            + diagnostics()
                );
                /*
                 * Returning through the vanilla portal is the physical
                 * milestone. Give the normal brain tick a bounded chance to
                 * retire the portal skill and apply the server-owned
                 * completion verifier; ending the fixture on the first
                 * return tick would hide a real RUNNING/SAFE_IDLE mismatch.
                 */
                if (runtime.goals().snapshot().status()
                        != GoalStatus.COMPLETED) {
                    helper.assertTrue(
                            System.nanoTime() - stageStartedNanos
                                    <= RETURN_TIMEOUT_NANOS,
                            "End return reached but goal was not completed: "
                                    + diagnostics()
                    );
                    return;
                }
                stage = EndVictoryStage.DONE;
                helper.succeed();
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= RETURN_TIMEOUT_NANOS,
                    "Live-model End return timed out: "
                        + diagnostics()
            );
        }

        private ServerPlayer body() {
            return AiPlayerManager.onlinePlayer(runtime.server())
                    .orElseThrow();
        }

        private void assertNoHumanPlayers() {
            final long humans = runtime.server()
                    .getPlayerList()
                    .getPlayers()
                    .stream()
                    .filter(player -> !player.getUUID().equals(
                            runtime.worldData().companionUuid()
                    ))
                    .count();
            helper.assertTrue(
                    humans == 0L,
                    "End-victory autonomy retained " + humans
                        + " human player(s)"
            );
        }

        private void assertWithinModelDeadline(final String message) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private String diagnostics() {
            final Optional<ServerPlayer> body = AiPlayerManager
                    .onlinePlayer(runtime.server());
            final String perception = runtime.coreFrames().current()
                    .map(frame -> "visibleEntities="
                        + frame.visibleEntities()
                        + ", dangers=" + frame.dangerSignals()
                        + ", observationRevision="
                        + frame.observationRevision())
                    .orElse("visibleEntities=unavailable");
            final String dragonParts = dragon == null
                    ? "none"
                    : java.util.Arrays.stream(dragon.getSubEntities())
                        .map(part -> part.name + "@" + part.position())
                        .toList()
                        .toString();
            return "supervisor="
                    + runtime.skillSupervisor().snapshot()
                    + ", goal=" + runtime.goals().snapshot()
                    + ", dimension="
                    + body.map(player -> player.level().dimension()
                            .identifier().toString())
                        .orElse("offline")
                    + ", body=" + body.map(ServerPlayer::position)
                        .orElse(null)
                    + ", milestones="
                    + runtime.worldData()
                        .verifiedRouteProgress(
                                Math.max(
                                        0L,
                                        victoryGoalRevision
                                )
                        )
                        .milestones()
                    + ", dragonHealth="
                    + (dragon == null ? "none" : dragon.getHealth())
                    + ", dragonParts=" + dragonParts
                    + ", crystalAlive="
                    + (crystal != null && crystal.isAlive())
                    + ", returnPortal=" + returnPortalCenter
                    + ", arrows=" + body.map(player -> player.getInventory()
                            .countItem(Items.ARROW))
                        .orElse(-1)
                    + ", checkpoint=" + runtime.skillSupervisor()
                        .lastCheckpointPayload().orElse("none")
                    + ", " + perception;
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum EndVictoryStage {
        BODY,
        ENTERING_END,
        SETTLING_END,
        PROBE,
        VISIBLE,
        GOAL,
        FIGHT_SKILL,
        FIGHTING,
        RETURNING,
        DONE
    }

    private static EndVictoryArena prepareEndVictoryArena(
            final GameTestHelper helper,
            final ServerRuntime runtime,
            final ServerPlayer body,
            final boolean equipBody,
            final boolean recenterBody
    ) {
        final var end = runtime.server().getLevel(Level.END);
        helper.assertTrue(end != null, "End level is unavailable");
        final BlockPos entry = body.blockPosition();
        /* Fight-focused callers may explicitly request a central, already
         * landed rally.  This is a test fixture boundary: production never
         * teleports the body or assumes a central island coordinate. */
        final BlockPos arena = recenterBody
                ? new BlockPos(0, entry.getY(), 0)
                : entry;
        /*
         * Keep a broad, explicitly constructed obsidian course around the
         * observed fight.  The body may need to retreat from a newly spawned
         * Enderman while returning to the portal; a narrow 41x41 pad made a
         * legitimate vanilla emergency move fall into the void and obscured
         * the dragon/return behavior being measured.
         */
        for (int x = -60; x <= 60; x++) {
            for (int z = -60; z <= 60; z++) {
                end.setBlockAndUpdate(
                        arena.offset(x, -1, z),
                        Blocks.OBSIDIAN.defaultBlockState()
                );
                for (int y = 0; y <= 10; y++) {
                    end.setBlockAndUpdate(
                            arena.offset(x, y, z),
                            Blocks.AIR.defaultBlockState()
                    );
                }
            }
        }
        /* The production sampler refuses to ray-test through an unloaded
         * horizontal chunk.  GameTest block writes do not guarantee that
         * every intermediate chunk remains resident after the player has
         * walked around the arena, so load only this bounded fixture corridor
         * before the real model is allowed to fight. */
        final int arenaChunkX = Math.floorDiv(arena.getX(), 16);
        final int arenaChunkZ = Math.floorDiv(arena.getZ(), 16);
        for (int chunkX = arenaChunkX - 4;
                chunkX <= arenaChunkX + 4;
                chunkX++) {
            for (int chunkZ = arenaChunkZ - 4;
                    chunkZ <= arenaChunkZ + 4;
                    chunkZ++) {
                end.getChunkAt(new BlockPos(chunkX << 4, 0, chunkZ << 4));
            }
        }
        end.getEntities(
                EntityTypes.END_CRYSTAL,
                existing -> true
        ).forEach(Entity::discard);
        /*
         * This arena is a focused dragon-control fixture. Remove ambient
         * Endermen so the emergency survival lane cannot legitimately leave
         * the bounded dragon course to pursue an unrelated hostile while the
         * live-model causal chain is being measured.
         */
        end.getEntities(
                EntityTypes.ENDERMAN,
                existing -> true
        ).forEach(Entity::discard);
        /*
         * This is a bounded dragon-control fixture, not a mob-spawn
         * benchmark.  Freeze ambient mob spawning after the dragon and
         * crystal have been installed so a newly spawned Enderman cannot
         * pull the emergency controller out of the observed arena and make
         * the crystal/dragon causal chain nondeterministic.  The production
         * runtime never changes gamerules for combat.
         */
        end.getGameRules().set(
                GameRules.SPAWN_MOBS,
                false,
                end.getServer()
        );
        end.getGameRules().set(
                GameRules.SPAWN_MONSTERS,
                false,
                end.getServer()
        );

        if (recenterBody) {
            body.teleportTo(
                    arena.getX() + 0.5D,
                    arena.getY(),
                    arena.getZ() + 0.5D
            );
        }
        body.setGameMode(GameType.SURVIVAL);
        if (equipBody) {
            equipEndVictoryBody(body, false);
        }
        body.setHealth(body.getMaxHealth());
        body.getFoodData().setFoodLevel(20);
        body.setDeltaMovement(Vec3.ZERO);
        body.fallDistance = 0.0F;
        body.inventoryMenu.broadcastChanges();
        if (recenterBody) {
            end.setBlockAndUpdate(
                    arena.offset(0, -1, 0),
                    Blocks.END_STONE.defaultBlockState()
            );
        }

        final EndCrystal crystal = EntityTypes.END_CRYSTAL.create(
                end,
                EntitySpawnReason.COMMAND
        );
        helper.assertTrue(
                crystal != null,
                "End-victory fixture could not create crystal"
        );
        crystal.setPos(
                arena.getX() + 10.5D,
                arena.getY(),
                arena.getZ() + 7.5D
        );
        helper.assertTrue(
                end.addFreshEntity(crystal),
                "End-victory fixture could not add crystal"
        );
        final BlockPos cageBar = arena.offset(0, 1, 4);
        end.setBlockAndUpdate(
                cageBar,
                Blocks.IRON_BARS.defaultBlockState()
        );

        final List<? extends EnderDragon> dragons =
                end.getEntities(
                        EntityTypes.ENDER_DRAGON,
                        existing -> true
                );
        final EnderDragon dragon;
        if (dragons.isEmpty()) {
            dragon = EntityTypes.ENDER_DRAGON.create(
                    end,
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                    dragon != null,
                    "End-victory fixture could not create dragon"
            );
            helper.assertTrue(
                    end.addFreshEntity(dragon),
                    "End-victory fixture could not add dragon"
            );
        } else {
            dragon = dragons.getFirst();
            dragons.stream()
                    .skip(1)
                    .forEach(Entity::discard);
        }
        /* Keep the root inside the companion's normal tracking window. The
         * multipart hitboxes extend toward the player from this root, so the
         * dragon remains a genuine fair target without widening the view
         * distance or relying on an always-loaded test entity. The observed
         * bar remains a real obstruction that the production cage-opening
         * path must clear before an occluded shot can be accepted. */
        dragon.setPos(
                arena.getX() + 0.5D,
                arena.getY() + 2.0D,
                arena.getZ() + 4.5D
        );
        dragon.setYRot(180.0F);
        dragon.setNoAi(true);
        dragon.setHealth(dragon.getMaxHealth());
        positionStaticDragonParts(helper, dragon);

        final BlockPos returnPortalCenter =
                arena.offset(0, 0, -10);
        /* The vanilla return portal activates only after dragon death. */
        clearEndReturnPortal(end, returnPortalCenter);
        body.lookAt(
                EntityAnchorArgument.Anchor.EYES,
                crystal.getEyePosition()
        );
        body.setYHeadRot(body.getYRot());
        return new EndVictoryArena(
                crystal,
                dragon,
                cageBar,
                returnPortalCenter
        );
    }

    private static void clearEndReturnPortal(
            final Level end,
            final BlockPos center
    ) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                end.setBlockAndUpdate(
                        center.offset(x, -1, z),
                        Blocks.OBSIDIAN.defaultBlockState()
                );
                end.setBlockAndUpdate(
                        center.offset(x, 0, z),
                        Blocks.AIR.defaultBlockState()
                );
            }
        }
    }

    private static void activateEndReturnPortal(
            final Level end,
            final BlockPos center
    ) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                end.setBlockAndUpdate(
                        center.offset(x, -1, z),
                        Blocks.OBSIDIAN.defaultBlockState()
                );
                end.setBlockAndUpdate(
                        center.offset(x, 0, z),
                        Blocks.END_PORTAL.defaultBlockState()
                );
            }
        }
    }

    private static void equipEndVictoryBody(
            final ServerPlayer body,
            final boolean includeEnderEyes
    ) {
        body.getInventory().clearContent();
        int slot = 0;
        if (includeEnderEyes) {
            body.getInventory().setItem(
                    slot++,
                    new ItemStack(Items.ENDER_EYE, 12)
            );
        }
        body.getInventory().setItem(
                slot++,
                new ItemStack(Items.BOW)
        );
        body.getInventory().setItem(
                slot++,
                new ItemStack(Items.ARROW, 99)
        );
        body.getInventory().setItem(
                slot++,
                new ItemStack(Items.ARROW, 29)
        );
        body.getInventory().setItem(
                slot++,
                new ItemStack(Items.COOKED_BEEF, 16)
        );
        body.getInventory().setItem(
                slot++,
                new ItemStack(Items.DIAMOND_SWORD)
        );
        body.getInventory().setItem(
                slot++,
                new ItemStack(Items.DIAMOND_PICKAXE)
        );
        body.getInventory().setItem(
                slot++,
                new ItemStack(Items.COBBLESTONE, 64)
        );
        body.getInventory().setItem(
                slot,
                new ItemStack(Items.WATER_BUCKET)
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
    }

    private static void positionStaticDragonParts(
            final GameTestHelper helper,
            final EnderDragon target
    ) {
        final EnderDragonPart[] parts = target.getSubEntities();
        helper.assertTrue(
                parts.length == 8,
                "Unexpected Ender Dragon part count "
                    + parts.length
        );
        final double x = target.getX();
        final double y = target.getY();
        final double z = target.getZ();
        parts[0].setPos(x, y - 1.0D, z + 6.5D);
        parts[1].setPos(x, y - 1.0D, z + 5.5D);
        parts[2].setPos(x, y, z + 0.5D);
        parts[3].setPos(x, y + 1.5D, z - 3.5D);
        parts[4].setPos(x, y + 1.5D, z - 5.5D);
        parts[5].setPos(x, y + 1.5D, z - 7.5D);
        parts[6].setPos(x - 4.5D, y + 2.0D, z);
        parts[7].setPos(x + 4.5D, y + 2.0D, z);
    }

    private static boolean hasPhysicalDragonDamageEvidence(
            final ServerPlayer body,
            final EndVictoryArena arena
    ) {
        /*
         * The enclosing DRAGON_KILLED milestone is emitted only by
         * EvaluationVictoryTracker after vanilla attributes an actual dragon
         * death to this ServerPlayer (including legitimate arrow kill
         * credit).  Keep the continuous route gate focused on that physical
         * fight: the dragon must have lost health and the body must have
         * consumed ammunition.  Destroying every optional healing crystal or
         * a particular fixture obstruction is a valid tactic, but not a
         * prerequisite for a credited win; the dedicated End-victory fixture
         * above retains its stricter cage/crystal assertions.
         */
        return arena.dragon().getHealth()
                    < arena.dragon().getMaxHealth()
                && body.getInventory().countItem(Items.ARROW) < 128;
    }

    private record EndVictoryArena(
            EndCrystal crystal,
            EnderDragon dragon,
            BlockPos cageBar,
            BlockPos returnPortalCenter
    ) {
    }

    private static final class LiveEnderPearlScenario {
        private static final int REQUIRED_ENDER_PEARLS = 14;
        private static final double CONTROLLED_ENDERMAN_OFFSET = 2.5D;
        private static final double MAXIMUM_FIXTURE_MELEE_DISTANCE = 2.81D;
        private static final long SIMULATION_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(30).toNanos();
        private static final long ACQUISITION_TIMEOUT_NANOS =
                java.time.Duration.ofMinutes(10).toNanos();
        private static final long ASYNC_CHUNK_YIELD_NANOS = 1_000_000L;

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;

        private EnderPearlStage stage = EnderPearlStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private BlockPos arenaOrigin;
        private Mob enderman;
        private UUID bodyId;
        private long goalRevisionBefore;
        private long goalRevision;
        private long stageStartedNanos;
        private long roofStableSince = -1L;
        private long targetRemovedAt = -1L;
        private int enderTargetsSpawned;
        private int swordDamageBefore;
        private boolean reserveSkillObserved;

        private LiveEnderPearlScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            finishScenarioGoal(runtime);
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Ender-route companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case SIMULATION -> waitForNetherSimulation();
                case PROBE -> waitForProbe();
                case GOAL -> waitForGoal();
                case SKILL -> waitForReserveSkill();
                case ACQUIRE -> waitForReserve();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Ender-route companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Ender-route companion body timed out"
                );
                return;
            }
            final ServerPlayer body = body();
            bodyId = body.getUUID();
            prepareNetherArena(body);
            stage = EnderPearlStage.SIMULATION;
            stageStartedNanos = System.nanoTime();
        }

        /**
         * Pre-command fixture setup represents the already-completed Blaze
         * route. After ordinary player chat, this test never moves the body,
         * edits its inventory, places the safety roof, or awards a pearl.
         */
        private void prepareNetherArena(final ServerPlayer body) {
            final var nether = runtime.server().getLevel(Level.NETHER);
            helper.assertTrue(
                    nether != null,
                    "Live Ender gate could not access the Nether"
            );
            final BlockPos reference =
                    helper.absolutePos(new BlockPos(12, 4, 12));
            arenaOrigin = new BlockPos(
                    reference.getX(),
                    64,
                    reference.getZ()
            );
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 10; z++) {
                    final BlockPos column =
                            arenaOrigin.offset(x, 0, z);
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
            body.setGameMode(GameType.SURVIVAL);
            body.getInventory().clearContent();
            body.getEnderChestInventory().clearContent();
            body.removeAllEffects();
            body.clearFire();
            body.getInventory().setItem(
                    0,
                    new ItemStack(Items.DIAMOND_SWORD)
            );
            body.getInventory().setItem(
                    1,
                    new ItemStack(Items.DIAMOND_PICKAXE)
            );
            body.getInventory().setItem(
                    2,
                    new ItemStack(Items.CRAFTING_TABLE)
            );
            body.getInventory().setItem(
                    3,
                    new ItemStack(Items.OAK_LOG, 8)
            );
            body.getInventory().setItem(
                    4,
                    new ItemStack(Items.COOKED_BEEF, 16)
            );
            body.getInventory().setItem(
                    5,
                    new ItemStack(Items.COBBLESTONE, 64)
            );
            body.getInventory().setItem(
                    6,
                    new ItemStack(Items.WATER_BUCKET)
            );
            body.getInventory().setItem(
                    7,
                    new ItemStack(Items.BLAZE_ROD, 7)
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
            body.teleportTo(
                    nether,
                    arenaOrigin.getX() + 0.5D,
                    arenaOrigin.getY(),
                    arenaOrigin.getZ() + 0.5D,
                    java.util.Set.of(),
                    0.0F,
                    0.0F,
                    false
            );
            body.setDeltaMovement(Vec3.ZERO);
            body.fallDistance = 0.0F;
            body.inventoryMenu.broadcastChanges();
            swordDamageBefore =
                    body.getMainHandItem().getDamageValue();
            enderTargetsSpawned = 0;
            reserveSkillObserved = false;
            roofStableSince = -1L;
            targetRemovedAt = -1L;
        }

        private void waitForNetherSimulation() {
            final ServerPlayer body = body();
            helper.assertTrue(
                    body.level().dimension().equals(Level.NETHER),
                    "Ender-route body left the Nether before the command"
            );
            if (!body.level().isPositionEntityTicking(
                    body.blockPosition()
            ) || !body.onGround()) {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        ASYNC_CHUNK_YIELD_NANOS
                );
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= SIMULATION_TIMEOUT_NANOS,
                        "Headless player's ordinary Nether chunk ticket "
                            + "did not become entity-ticking"
                );
                return;
            }
            probe = probeOrReuseVerifiedModel(runtime);
            stage = EnderPearlStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "Ender-route model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            humanSession = PlacedHuman.create(helper, runtime);
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in Ender-route player lacked task permission"
            );
            goalRevisionBefore = runtime.goals().snapshot().revision();
            final String request = runtime.worldData().displayName()
                    + "，请继续通关Minecraft。烈焰棒已经准备好了，"
                    + "请正常搭建末影人安全屋顶、战斗和拾取，"
                    + "收集至少14颗末影珍珠。不要使用指令，"
                    + "也不要等我再次提醒。";
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            human,
                            Component.literal(request)
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the Ender-route chat command"
            );
            humanSession.close();
            humanSession = null;
            stage = EnderPearlStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            assertNoHumanPlayers();
            assertWithinModelDeadline(
                    "Live model did not classify the Ender-route task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.status() == GoalStatus.RUNNING
                        && goal.goal().contains("通关Minecraft"),
                    "Ender-route chat did not become a running "
                        + "completion goal: " + goal
            );
            goalRevision = goal.revision();
            stage = EnderPearlStage.SKILL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForReserveSkill() {
            assertNoHumanPlayers();
            final var snapshot =
                    runtime.skillSupervisor().snapshot();
            if ("secure_ender_pearl_reserve".equals(
                    snapshot.skillName()
            ) && snapshot.boundGoalRevision() == goalRevision) {
                reserveSkillObserved = true;
                stage = EnderPearlStage.ACQUIRE;
                stageStartedNanos = System.nanoTime();
                return;
            }
            if (snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.RUNNING) {
                helper.fail(
                        "Live model selected the wrong Ender-route skill: "
                            + diagnostics()
                );
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    "Live model did not select the durable pearl reserve: "
                        + diagnostics()
            );
        }

        private void waitForReserve() {
            assertNoHumanPlayers();
            final ServerPlayer body = body();
            helper.assertTrue(
                    bodyId.equals(body.getUUID())
                        && body.level().dimension().equals(Level.NETHER)
                        && body.isAlive()
                        && !body.isDeadOrDying(),
                    "Pearl reserve lost the original living Nether body: "
                        + diagnostics()
            );
            final int pearls =
                    body.getInventory().countItem(Items.ENDER_PEARL);
            final boolean targetGone = enderman == null
                    || enderman.isRemoved()
                    || !enderman.isAlive();
            if (enderman != null
                    && targetGone
                    && targetRemovedAt < 0L) {
                targetRemovedAt = helper.getTick();
            }
            final boolean roofComplete = roofComplete();
            final boolean pillarRemoved = temporaryPillarRemoved();
            final boolean bodyCentered = bodyCenteredUnderRoof(body);
            final boolean observedRoof = runtime.coreFrames()
                    .current()
                    .map(SecureEnderPearlReserveSkill
                            ::hasObservedSafetyRoof)
                    .orElse(false);
            if (roofComplete
                    && pillarRemoved
                    && bodyCentered
                    && observedRoof) {
                if (roofStableSince < 0L) {
                    roofStableSince = helper.getTick();
                }
            } else {
                roofStableSince = -1L;
            }
            final boolean pearlDropPresent = body.level()
                    .getEntitiesOfClass(
                            ItemEntity.class,
                            body.getBoundingBox().inflate(16.0D)
                    )
                    .stream()
                    .anyMatch(drop ->
                            drop.getItem().is(Items.ENDER_PEARL)
                                && drop.isAlive()
                                && !drop.isRemoved()
                    );
            final boolean targetCooldownComplete =
                    enderman == null
                        || targetRemovedAt >= 0L
                            && helper.getTick() - targetRemovedAt >= 2L;
            if (pearls < REQUIRED_ENDER_PEARLS
                    && targetGone
                    && !pearlDropPresent
                    && bodyCentered
                    && roofStableSince >= 0L
                    && helper.getTick() - roofStableSince >= 20L
                    && targetCooldownComplete) {
                spawnControlledEnderman();
            }

            final var snapshot =
                    runtime.skillSupervisor().snapshot();
            if (pearls > 0
                    && !bodyCentered
                    && helper.getTick() % 20L == 0L) {
                dev.mcai.companion.MinecraftAiCompanion.LOGGER.info(
                        "Live Ender return diagnostic: {}",
                        diagnostics()
                );
            }
            if (snapshot.executedTicks() > 0L
                    && snapshot.executedTicks() % 400L == 0L) {
                dev.mcai.companion.MinecraftAiCompanion.LOGGER.info(
                        "Live Ender reserve progress: {}",
                        diagnostics()
                );
            }
            if ("secure_ender_pearl_reserve".equals(
                    snapshot.skillName()
            ) && snapshot.state()
                    == SkillSupervisor.State.FAILED) {
                helper.fail(
                        "Live-model pearl reserve failed: "
                            + diagnostics()
                );
                return;
            }
            final boolean milestone = runtime.worldData()
                    .verifiedRouteProgress(goalRevision)
                    .milestones()
                    .contains(
                            SurvivalMilestone.ENDER_PEARL_OBTAINED
                    );
            if (pearls >= REQUIRED_ENDER_PEARLS
                    && milestone
                    && "secure_ender_pearl_reserve".equals(
                        snapshot.skillName()
                    )
                    && snapshot.state()
                        == SkillSupervisor.State.COMPLETED) {
                helper.assertTrue(
                        reserveSkillObserved
                            && enderTargetsSpawned >= 7
                            && roofComplete
                            && pillarRemoved
                            && body.getMainHandItem()
                                .is(Items.DIAMOND_SWORD)
                            && body.getMainHandItem()
                                .getDamageValue()
                                    > swordDamageBefore,
                        "Pearl reserve appeared without roof construction, "
                            + "repeated combat, and durability: "
                            + diagnostics()
                );
                stage = EnderPearlStage.DONE;
                helper.succeed();
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= ACQUISITION_TIMEOUT_NANOS,
                    "Live-model pearl reserve timed out: "
                        + diagnostics()
            );
        }

        private void spawnControlledEnderman() {
            final ServerPlayer body = body();
            final Mob next = EntityTypes.ENDERMAN.create(
                    body.level(),
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                    next != null,
                    "Could not create the controlled live-model Enderman"
            );
            next.setPos(
                    arenaOrigin.getX() + 0.5D,
                    arenaOrigin.getY(),
                    arenaOrigin.getZ()
                        + CONTROLLED_ENDERMAN_OFFSET
            );
            /*
             * This controlled target has no AI, so it cannot close the last
             * fraction of a block in response to the production lure. It
             * remains outside the roof while reachable from any accepted
             * centre-docking position.
             */
            final double targetDistance = Math.hypot(
                    next.getX() - body.getX(),
                    next.getZ() - body.getZ()
            );
            helper.assertTrue(
                    CONTROLLED_ENDERMAN_OFFSET > 1.5D
                        && targetDistance
                            <= MAXIMUM_FIXTURE_MELEE_DISTANCE,
                    "Controlled live-model Enderman fixture is outside "
                        + "reachable sheltered melee geometry"
            );
            next.setNoAi(true);
            next.setHealth(5.0F);
            next.setPersistenceRequired();
            next.setItemSlot(
                    EquipmentSlot.MAINHAND,
                    new ItemStack(Items.ENDER_PEARL)
            );
            next.setGuaranteedDrop(EquipmentSlot.MAINHAND);
            helper.assertTrue(
                    body.level().addFreshEntity(next),
                    "Could not add the controlled live-model Enderman"
            );
            enderman = next;
            enderTargetsSpawned++;
            targetRemovedAt = -1L;
        }

        private boolean roofComplete() {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (!body().level().getBlockState(
                            arenaOrigin.offset(x, 2, z)
                    ).is(Blocks.COBBLESTONE)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean temporaryPillarRemoved() {
            final int[][] offsets = {
                    {1, 0},
                    {-1, 0},
                    {0, 1},
                    {0, -1}
            };
            for (int[] offset : offsets) {
                for (int y = 0; y <= 1; y++) {
                    if (body().level().getBlockState(
                            arenaOrigin.offset(
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

        private boolean bodyCenteredUnderRoof(
                final ServerPlayer body
        ) {
            return body.onGround()
                    && Math.abs(
                            body.getY() - arenaOrigin.getY()
                    ) <= 0.05D
                    && Math.hypot(
                            body.getX()
                                - (arenaOrigin.getX() + 0.5D),
                            body.getZ()
                                - (arenaOrigin.getZ() + 0.5D)
                    ) <= 0.30D;
        }

        private ServerPlayer body() {
            return AiPlayerManager.onlinePlayer(runtime.server())
                    .orElseThrow(() -> helper.assertionException(
                            "Ender-route companion body disappeared"
                    ));
        }

        private void assertNoHumanPlayers() {
            final long humans = runtime.server()
                    .getPlayerList()
                    .getPlayers()
                    .stream()
                    .filter(player -> !player.getUUID().equals(
                            runtime.worldData().companionUuid()
                    ))
                    .count();
            helper.assertTrue(
                    humans == 0L,
                    "Ender-route autonomy retained " + humans
                        + " human player(s)"
            );
        }

        private void assertWithinModelDeadline(
                final String message
        ) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private String diagnostics() {
            final ServerPlayer body = body();
            return "supervisor=" + runtime.skillSupervisor().snapshot()
                    + ", goal=" + runtime.goals().snapshot()
                    + ", dimension="
                    + body.level().dimension().identifier()
                    + ", body=" + body.position()
                    + ", velocity=" + body.getDeltaMovement()
                    + ", input=" + body.getLastClientInput()
                    + ", usingItem=" + body.isUsingItem()
                    + ", useItem=" + body.getUseItem()
                    + ", yaw=" + body.getYRot()
                    + ", pitch=" + body.getXRot()
                    + ", coreLease=" + runtime.coreActions().snapshot()
                    + ", arbiter=" + runtime.behaviorArbiter().latest()
                    + ", survival=" + runtime.survival().state()
                    + ", health=" + body.getHealth()
                    + ", pearls="
                    + body.getInventory().countItem(Items.ENDER_PEARL)
                    + ", spawned=" + enderTargetsSpawned
                    + ", roof=" + roofComplete()
                    + ", pillarRemoved="
                    + temporaryPillarRemoved()
                    + ", target="
                    + (enderman == null
                        ? "none"
                        : enderman.getUUID() + "/alive="
                            + enderman.isAlive() + "/health="
                            + enderman.getHealth())
                    + ", skillObserved=" + reserveSkillObserved
                    + ", milestones=" + runtime.worldData()
                        .verifiedRouteProgress(goalRevision)
                        .milestones();
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (enderman != null && !enderman.isRemoved()) {
                enderman.discard();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum EnderPearlStage {
        BODY,
        SIMULATION,
        PROBE,
        GOAL,
        SKILL,
        ACQUIRE,
        DONE
    }

    private static final class LiveNetherBlazeScenario {
        private static final int REQUIRED_BLAZE_RODS = 7;
        private static final long SIMULATION_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(30).toNanos();
        private static final long ACQUISITION_TIMEOUT_NANOS =
                java.time.Duration.ofMinutes(3).toNanos();
        private static final long ASYNC_CHUNK_YIELD_NANOS = 1_000_000L;

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;

        private NetherBlazeStage stage = NetherBlazeStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private BlockPos arenaOrigin;
        private Mob blaze;
        private UUID bodyId;
        private long goalRevisionBefore;
        private long goalRevision;
        private long stageStartedNanos;
        private int blazeTargetsSpawned;
        private int swordDamageBefore;
        private boolean reserveSkillObserved;

        private LiveNetherBlazeScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            finishScenarioGoal(runtime);
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Blaze-route companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case SIMULATION -> waitForNetherSimulation();
                case VISIBLE -> waitForVisibleBlaze();
                case PROBE -> waitForProbe();
                case GOAL -> waitForGoal();
                case SKILL -> waitForReserveSkill();
                case ACQUIRE -> waitForReserve();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Blaze-route companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Blaze-route companion body timed out"
                );
                return;
            }
            final ServerPlayer body = body();
            bodyId = body.getUUID();
            prepareNetherArena(body);
            stage = NetherBlazeStage.SIMULATION;
            stageStartedNanos = System.nanoTime();
        }

        /**
         * This is pre-command fixture setup. It establishes only a controlled
         * already-reached Nether checkpoint; after the player message, the
         * test never moves the body, edits inventory, or awards a route item.
         */
        private void prepareNetherArena(final ServerPlayer body) {
            final var nether = runtime.server().getLevel(Level.NETHER);
            helper.assertTrue(
                    nether != null,
                    "Live Blaze gate could not access the Nether"
            );
            final BlockPos reference =
                    helper.absolutePos(new BlockPos(12, 4, 12));
            arenaOrigin = new BlockPos(
                    reference.getX(),
                    64,
                    reference.getZ()
            );
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 10; z++) {
                    final BlockPos column =
                            arenaOrigin.offset(x, 0, z);
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
            body.setGameMode(GameType.SURVIVAL);
            body.getInventory().clearContent();
            body.getEnderChestInventory().clearContent();
            body.removeAllEffects();
            body.clearFire();
            body.getInventory().setItem(
                    0,
                    new ItemStack(Items.DIAMOND_SWORD)
            );
            body.getInventory().setItem(
                    1,
                    new ItemStack(Items.DIAMOND_PICKAXE)
            );
            body.getInventory().setItem(
                    2,
                    new ItemStack(Items.CRAFTING_TABLE)
            );
            body.getInventory().setItem(
                    3,
                    new ItemStack(Items.OAK_LOG, 8)
            );
            body.getInventory().setItem(
                    4,
                    new ItemStack(Items.COOKED_BEEF, 16)
            );
            body.getInventory().setItem(
                    5,
                    new ItemStack(Items.COBBLESTONE, 64)
            );
            body.getInventory().setItem(
                    6,
                    new ItemStack(Items.WATER_BUCKET)
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
            body.teleportTo(
                    nether,
                    arenaOrigin.getX() + 0.5D,
                    arenaOrigin.getY(),
                    arenaOrigin.getZ() + 0.5D,
                    java.util.Set.of(),
                    0.0F,
                    0.0F,
                    false
            );
            body.setDeltaMovement(Vec3.ZERO);
            body.fallDistance = 0.0F;
            body.inventoryMenu.broadcastChanges();
            swordDamageBefore =
                    body.getMainHandItem().getDamageValue();
            blazeTargetsSpawned = 0;
            reserveSkillObserved = false;
        }

        private void waitForNetherSimulation() {
            final ServerPlayer body = body();
            helper.assertTrue(
                    body.level().dimension().equals(Level.NETHER),
                    "Blaze-route body left the Nether before the command"
            );
            if (!body.level().isPositionEntityTicking(
                    body.blockPosition()
            ) || !body.onGround()) {
                java.util.concurrent.locks.LockSupport.parkNanos(
                        ASYNC_CHUNK_YIELD_NANOS
                );
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= SIMULATION_TIMEOUT_NANOS,
                        "Headless player's ordinary Nether chunk ticket "
                            + "did not become entity-ticking"
                );
                return;
            }
            spawnControlledBlaze();
            stage = NetherBlazeStage.VISIBLE;
            stageStartedNanos = System.nanoTime();
        }

        private void spawnControlledBlaze() {
            final ServerPlayer body = body();
            final Mob next = EntityTypes.BLAZE.create(
                    body.level(),
                    EntitySpawnReason.COMMAND
            );
            helper.assertTrue(
                    next != null,
                    "Could not create the controlled live-model Blaze"
            );
            final double zOffset =
                    blazeTargetsSpawned % 2 == 0 ? 6.5D : 0.5D;
            next.setPos(
                    arenaOrigin.getX() + 0.5D,
                    arenaOrigin.getY(),
                    arenaOrigin.getZ() + zOffset
            );
            next.setNoAi(true);
            next.setHealth(5.0F);
            next.setPersistenceRequired();
            next.setItemSlot(
                    EquipmentSlot.MAINHAND,
                    new ItemStack(Items.BLAZE_ROD)
            );
            next.setGuaranteedDrop(EquipmentSlot.MAINHAND);
            helper.assertTrue(
                    body.level().addFreshEntity(next),
                    "Could not add the controlled live-model Blaze"
            );
            blaze = next;
            blazeTargetsSpawned++;
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    next.getEyePosition()
            );
            body.setYHeadRot(body.getYRot());
        }

        private void waitForVisibleBlaze() {
            final ServerPlayer body = body();
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    blaze.getEyePosition()
            );
            body.setYHeadRot(body.getYRot());
            final var frame = runtime.coreFrames().current();
            if (frame.isEmpty()
                    || !frame.orElseThrow().dimension()
                        .equals(dev.mcai.companion.waypoint.DimensionRef.NETHER)
                    || frame.orElseThrow().visibleEntities().stream()
                        .noneMatch(entity ->
                                entity.entityId().equals(blaze.getUUID())
                                    && "minecraft:blaze".equals(
                                        entity.entityTypeId()
                                    ))) {
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= SIMULATION_TIMEOUT_NANOS,
                        "Controlled Blaze never became current "
                            + "first-person evidence"
                );
                return;
            }
            probe = probeOrReuseVerifiedModel(runtime);
            stage = NetherBlazeStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "Blaze-route model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            humanSession = PlacedHuman.create(helper, runtime);
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in Blaze-route player lacked task permission"
            );
            goalRevisionBefore = runtime.goals().snapshot().revision();
            final String request = runtime.worldData().displayName()
                    + "，请继续通关Minecraft。你已经在下界了，"
                    + "请从眼前的烈焰人开始，正常战斗和拾取，"
                    + "收集至少7根烈焰棒作为通关储备。"
                    + "不要使用指令，也不要等我再次提醒。";
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            human,
                            Component.literal(request)
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the Blaze-route chat command"
            );
            humanSession.close();
            humanSession = null;
            stage = NetherBlazeStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            assertNoHumanPlayers();
            assertWithinModelDeadline(
                    "Live model did not classify the Blaze-route task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.status() == GoalStatus.RUNNING
                        && goal.goal().contains("通关Minecraft"),
                    "Blaze-route chat did not become a running "
                        + "completion goal: " + goal
            );
            goalRevision = goal.revision();
            stage = NetherBlazeStage.SKILL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForReserveSkill() {
            assertNoHumanPlayers();
            final var snapshot =
                    runtime.skillSupervisor().snapshot();
            if ("secure_nether_blaze_material".equals(
                    snapshot.skillName()
            ) && snapshot.boundGoalRevision() == goalRevision) {
                reserveSkillObserved = true;
                stage = NetherBlazeStage.ACQUIRE;
                stageStartedNanos = System.nanoTime();
                return;
            }
            if (snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.RUNNING) {
                helper.fail(
                        "Live model selected the wrong Blaze-route skill: "
                            + diagnostics()
                );
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    "Live model did not select the durable Blaze reserve: "
                        + diagnostics()
            );
        }

        private void waitForReserve() {
            assertNoHumanPlayers();
            final ServerPlayer body = body();
            helper.assertTrue(
                    bodyId.equals(body.getUUID())
                        && body.level().dimension().equals(Level.NETHER)
                        && body.isAlive()
                        && !body.isDeadOrDying(),
                    "Blaze reserve lost the original living Nether body: "
                        + diagnostics()
            );
            final int rods =
                    body.getInventory().countItem(Items.BLAZE_ROD);
            if ((blaze.isRemoved() || !blaze.isAlive())
                    && rods < REQUIRED_BLAZE_RODS
                    && rods >= blazeTargetsSpawned) {
                spawnControlledBlaze();
            }

            final var snapshot =
                    runtime.skillSupervisor().snapshot();
            if ("secure_nether_blaze_material".equals(
                    snapshot.skillName()
            ) && snapshot.state()
                    == SkillSupervisor.State.FAILED) {
                helper.fail(
                        "Live-model Blaze reserve failed: "
                            + diagnostics()
                );
                return;
            }
            final boolean milestone = runtime.worldData()
                    .verifiedRouteProgress(goalRevision)
                    .milestones()
                    .contains(
                            SurvivalMilestone.BLAZE_MATERIAL_OBTAINED
                    );
            if (rods >= REQUIRED_BLAZE_RODS
                    && milestone
                    && "secure_nether_blaze_material".equals(
                        snapshot.skillName()
                    )
                    && snapshot.state()
                        == SkillSupervisor.State.COMPLETED) {
                helper.assertTrue(
                        reserveSkillObserved
                            && blazeTargetsSpawned >= 4
                            && body.getMainHandItem()
                                .is(Items.DIAMOND_SWORD)
                            && body.getMainHandItem()
                                .getDamageValue()
                                    > swordDamageBefore,
                        "Blaze material appeared without repeated ordinary "
                            + "combat and durability: " + diagnostics()
                );
                stage = NetherBlazeStage.DONE;
                helper.succeed();
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= ACQUISITION_TIMEOUT_NANOS,
                    "Live-model Blaze reserve timed out: "
                        + diagnostics()
            );
        }

        private ServerPlayer body() {
            return AiPlayerManager.onlinePlayer(runtime.server())
                    .orElseThrow(() -> helper.assertionException(
                            "Blaze-route companion body disappeared"
                    ));
        }

        private void assertNoHumanPlayers() {
            final long humans = runtime.server()
                    .getPlayerList()
                    .getPlayers()
                    .stream()
                    .filter(player -> !player.getUUID().equals(
                            runtime.worldData().companionUuid()
                    ))
                    .count();
            helper.assertTrue(
                    humans == 0L,
                    "Blaze-route autonomy retained " + humans
                        + " human player(s)"
            );
        }

        private void assertWithinModelDeadline(final String message) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private String diagnostics() {
            final ServerPlayer body = body();
            return "supervisor=" + runtime.skillSupervisor().snapshot()
                    + ", goal=" + runtime.goals().snapshot()
                    + ", dimension="
                    + body.level().dimension().identifier()
                    + ", body=" + body.position()
                    + ", health=" + body.getHealth()
                    + ", rods="
                    + body.getInventory().countItem(Items.BLAZE_ROD)
                    + ", spawned=" + blazeTargetsSpawned
                    + ", target="
                    + (blaze == null
                        ? "none"
                        : blaze.getUUID() + "/alive="
                            + blaze.isAlive() + "/health="
                            + blaze.getHealth())
                    + ", skillObserved=" + reserveSkillObserved
                    + ", milestones="
                    + runtime.worldData()
                        .verifiedRouteProgress(
                                Math.max(0L, goalRevision)
                        ).milestones();
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (blaze != null && !blaze.isRemoved()) {
                blaze.discard();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum NetherBlazeStage {
        BODY,
        SIMULATION,
        VISIBLE,
        PROBE,
        GOAL,
        SKILL,
        ACQUIRE,
        DONE
    }

    private static final class LiveNetherPortalScenario {
        private static final long BUILD_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(90).toNanos();
        private static final long ENTRY_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(150).toNanos();
        private static final int SITE_SUPPORT_SAMPLES = 4;
        private static final int SITE_AIR_SAMPLES = 20;
        /*
         * A single infinitesimal ray is intentionally not enough to prove
         * portal clearance.  Revisit every support/backing target from three
         * nearby first-person aim points so the post-login body session can
         * establish the same bounded evidence a player would build by
         * looking around, without reading blocks directly.
         */
        private static final int PORTAL_SITE_SCAN_PASSES = 3;
        private static final int SAMPLE_SETTLE_TICKS = 6;

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;

        private NetherPortalStage stage = NetherPortalStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private BlockPos portalAnchor;
        private UUID bodyId;
        private ServerPlayer bodyBeforeHumanLogin;
        private long humanAnchorReadyTick = -1L;
        private long nextSiteSampleTick;
        private int siteSampleIndex;
        private long goalRevisionBefore;
        private long goalRevision;
        private long stageStartedNanos;
        private boolean buildSkillObserved;
        private boolean entrySkillObserved;

        private LiveNetherPortalScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            this.helper = helper;
            this.runtime = runtime;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            finishScenarioGoal(runtime);
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Nether-route companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case SITE_SCAN -> scanFairPortalSite();
                case POST_ANCHOR_SCAN -> scanPostAnchorPortalSite();
                case PROBE -> waitForProbe();
                case GOAL -> waitForGoal();
                case BUILD_SKILL -> waitForBuildSkill();
                case BUILD -> waitForBuild();
                case ENTRY_SKILL -> waitForEntrySkill();
                case ENTER -> waitForEntry();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Nether-route companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Nether-route companion body timed out"
                );
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            bodyId = body.getUUID();
            preparePortalSite(body);
            stage = NetherPortalStage.SITE_SCAN;
            nextSiteSampleTick = helper.getTick();
        }

        /**
         * Builds only first-person evidence. The fixture turns the actual
         * body toward opaque surfaces behind each proposed air cell, then
         * waits for the normal 4 Hz semantic sampler. It never inserts a
         * portal block or edits navigation memory.
         */
        private void scanFairPortalSite() {
            final ServerPlayer body = body();
            if (helper.getTick() < nextSiteSampleTick) {
                return;
            }
            if (siteSampleIndex
                    >= SITE_SUPPORT_SAMPLES + SITE_AIR_SAMPLES) {
                prepareConstructionScaffold(body);
                probe = probeOrReuseVerifiedModel(runtime);
                stage = NetherPortalStage.PROBE;
                stageStartedNanos = System.nanoTime();
                return;
            }
            final BlockPos target;
            if (siteSampleIndex < SITE_SUPPORT_SAMPLES) {
                target = portalAnchor.offset(
                        siteSampleIndex,
                        -1,
                        0
                );
            } else {
                final int cell =
                        siteSampleIndex - SITE_SUPPORT_SAMPLES;
                target = portalAnchor.offset(
                        cell % 4,
                        cell / 4,
                        1
                );
            }
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(target)
            );
            body.setYHeadRot(body.getYRot());
            siteSampleIndex++;
            nextSiteSampleTick =
                    helper.getTick() + SAMPLE_SETTLE_TICKS;
        }

        /**
         * Rebuilds the same bounded first-person site evidence after the
         * production initial-anchor remove/relogin. Perception is tied to the
         * authoritative body session, so retaining the pre-login navigation
         * snapshot would be an unfair hidden-world shortcut and would make a
         * legitimate model decision fail with {@code observed_site_unavailable}.
         */
        private void scanPostAnchorPortalSite() {
            assertWithinModelDeadline(
                    "Nether-route post-anchor site observation timed out"
            );
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            if (helper.getTick() < nextSiteSampleTick) {
                return;
            }
            final int logicalSampleCount =
                    SITE_SUPPORT_SAMPLES + SITE_AIR_SAMPLES;
            if (siteSampleIndex
                    >= logicalSampleCount * PORTAL_SITE_SCAN_PASSES) {
                stage = NetherPortalStage.PROBE;
                stageStartedNanos = System.nanoTime();
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            final int logicalSample = siteSampleIndex
                    / PORTAL_SITE_SCAN_PASSES;
            final int pass = siteSampleIndex
                    % PORTAL_SITE_SCAN_PASSES;
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    portalSampleAimPoint(logicalSample, pass)
            );
            body.setYHeadRot(body.getYRot());
            runtime.observations().requestObservation(
                    new RequestedObservation(
                            ObservationKind.SEMANTIC_REFRESH,
                            "post_anchor_portal_site"
                    )
            );
            siteSampleIndex++;
            nextSiteSampleTick =
                    helper.getTick() + SAMPLE_SETTLE_TICKS;
        }

        private BlockPos portalSampleTarget(final int sampleIndex) {
            if (sampleIndex < SITE_SUPPORT_SAMPLES) {
                return portalAnchor.offset(sampleIndex, -1, 0);
            }
            final int cell = sampleIndex - SITE_SUPPORT_SAMPLES;
            return portalAnchor.offset(
                    cell % 4,
                    cell / 4,
                    1
            );
        }

        private Vec3 portalSampleAimPoint(
                final int logicalSample,
                final int pass
        ) {
            final BlockPos target = portalSampleTarget(logicalSample);
            final double offsetX;
            final double offsetY;
            final double offsetZ;
            switch (pass) {
                case 1 -> {
                    offsetX = 0.22D;
                    offsetY = -0.18D;
                    offsetZ = 0.16D;
                }
                case 2 -> {
                    offsetX = -0.22D;
                    offsetY = 0.18D;
                    offsetZ = -0.16D;
                }
                default -> {
                    offsetX = 0.0D;
                    offsetY = 0.0D;
                    offsetZ = 0.0D;
                }
            }
            return new Vec3(
                    target.getX() + 0.5D + offsetX,
                    target.getY() + 0.5D + offsetY,
                    target.getZ() + 0.5D + offsetZ
            );
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "Nether-route model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            /*
             * A first human joining a dedicated world can legitimately
             * trigger the production initial-anchor remove/relogin. Keep the
             * human online and wait for the replacement ServerPlayer instead
             * of submitting the task against the stale body or treating the
             * one-tick absence as a model failure.
             */
            if (humanSession == null) {
                final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                        .onlinePlayer(runtime.server());
                if (bodyCandidate.isEmpty()) {
                    assertWithinModelDeadline(
                            "Nether-route body disappeared while the model "
                                + "probe was completing"
                    );
                    return;
                }
                bodyBeforeHumanLogin = bodyCandidate.orElseThrow();
                humanSession = PlacedHuman.create(
                        helper,
                        runtime,
                        /* Keep the login anchor on the prepared floor beside
                         * the portal. Using a real supported feet position
                         * avoids a vanilla spawn correction several blocks
                         * upward while keeping every frame block inside the
                         * ordinary 4.75-block interaction reach.
                         */
                        new Vec3(
                                portalAnchor.getX() + 2.5D,
                                portalAnchor.getY(),
                                portalAnchor.getZ() - 2.0D
                        )
                );
                humanAnchorReadyTick = helper.getTick()
                        + SAMPLE_SETTLE_TICKS;
                return;
            }
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                assertWithinModelDeadline(
                        "Nether-route body disappeared during initial "
                            + "anchor reconciliation"
                );
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            if (bodyBeforeHumanLogin != null
                    && runtime.worldData().bodyNeedsInitialAnchor()
                    && body == bodyBeforeHumanLogin) {
                return;
            }
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(portalAnchor.offset(1, 2, 0))
            );
            body.setYHeadRot(body.getYRot());
            if (helper.getTick() < humanAnchorReadyTick) {
                return;
            }
            if (bodyBeforeHumanLogin != null) {
                bodyBeforeHumanLogin = null;
                /* The pre-login vantage scaffold is directly in front of the
                 * replacement body's first-person rays. It was only a setup
                 * aid for the old body, so remove those test-created blocks
                 * before the replacement body observes the site. */
                clearConstructionScaffold();
                siteSampleIndex = 0;
                nextSiteSampleTick = helper.getTick();
                stage = NetherPortalStage.POST_ANCHOR_SCAN;
                stageStartedNanos = System.nanoTime();
                return;
            }
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in Nether-route player lacked task permission"
            );
            goalRevisionBefore = runtime.goals().snapshot().revision();
            final String request = """
                    %s，请继续通关Minecraft。先用背包里的14个黑曜石和打火石，
                    在你已经观察过的安全位置以X轴搭建并点燃下界传送门；
                    门框左下角坐标是 %d %d %d。完成后立刻正常走进传送门前往下界，
                    不要传送，也不要等我再次提醒。
                    """.formatted(
                            runtime.worldData().displayName(),
                            portalAnchor.getX(),
                            portalAnchor.getY(),
                            portalAnchor.getZ()
                    ).replace('\n', ' ').strip();
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            human,
                            Component.literal(request)
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the Nether-route chat command"
            );
            humanSession.close();
            humanSession = null;
            stage = NetherPortalStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void clearConstructionScaffold() {
            for (int u = 1; u <= 2; u++) {
                helper.getLevel().setBlockAndUpdate(
                        portalAnchor.offset(u, 1, -1),
                        Blocks.AIR.defaultBlockState()
                );
                helper.getLevel().setBlockAndUpdate(
                        portalAnchor.offset(u, 2, -1),
                        Blocks.AIR.defaultBlockState()
                );
            }
        }

        private void waitForGoal() {
            assertNoHumanPlayers();
            assertWithinModelDeadline(
                    "Live model did not classify the Nether-route task"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.status() == GoalStatus.RUNNING
                        && goal.goal().contains("通关Minecraft"),
                    "Nether-route chat did not become a running "
                        + "completion goal: " + goal
            );
            goalRevision = goal.revision();
            stage = NetherPortalStage.BUILD_SKILL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForBuildSkill() {
            assertNoHumanPlayers();
            final var snapshot =
                    runtime.skillSupervisor().snapshot();
            if ("build_and_light_nether_portal".equals(
                    snapshot.skillName()
            )) {
                helper.assertTrue(
                        snapshot.boundGoalRevision() == goalRevision,
                        "Nether portal builder bound the wrong goal"
                );
                buildSkillObserved = true;
                stage = NetherPortalStage.BUILD;
                stageStartedNanos = System.nanoTime();
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    "Live model did not select the ordinary Nether "
                        + "portal builder: " + diagnostics()
            );
        }

        private void waitForBuild() {
            assertNoHumanPlayers();
            if (activePortalBlocks() == 6) {
                final ServerPlayer body = body();
                helper.assertTrue(
                        buildSkillObserved
                            && body.getInventory().countItem(
                                Items.OBSIDIAN
                            ) == 0
                            && flintDamage(body) == 1,
                        "Nether portal appeared without exact item "
                            + "consumption and durability: "
                            + diagnostics()
                );
                stage = NetherPortalStage.ENTRY_SKILL;
                stageStartedNanos = System.nanoTime();
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= BUILD_TIMEOUT_NANOS,
                    "Live-model Nether portal build timed out: "
                        + diagnostics()
            );
        }

        private void waitForEntrySkill() {
            assertNoHumanPlayers();
            final var snapshot =
                    runtime.skillSupervisor().snapshot();
            if (("enter_observed_portal".equals(
                        snapshot.skillName()
                    )
                    || "find_and_enter_observed_portal".equals(
                        snapshot.skillName()
                    ))
                    && snapshot.boundGoalRevision()
                        == goalRevision) {
                entrySkillObserved = true;
                stage = NetherPortalStage.ENTER;
                stageStartedNanos = System.nanoTime();
                return;
            }
            helper.assertTrue(
                    body().level().dimension().equals(Level.OVERWORLD),
                    "Body entered the Nether before an entry skill "
                        + "was observed: " + diagnostics()
            );
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    "Live model did not continue from portal build "
                        + "to physical entry: " + diagnostics()
            );
        }

        private void waitForEntry() {
            assertNoHumanPlayers();
            final ServerPlayer body = body();
            helper.assertTrue(
                    bodyId.equals(body.getUUID()),
                    "Nether-route handoff replaced the companion body"
            );
            if (body.level().dimension().equals(Level.NETHER)) {
                final var milestones = runtime.worldData()
                        .verifiedRouteProgress(goalRevision)
                        .milestones();
                helper.assertTrue(
                        buildSkillObserved
                            && entrySkillObserved
                            && activePortalBlocks() == 6
                            && milestones.contains(
                                SurvivalMilestone.NETHER_ENTERED
                            ),
                        "Nether entry lacked the complete causal route "
                            + "evidence: " + diagnostics()
                );
                stage = NetherPortalStage.DONE;
                helper.succeed();
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= ENTRY_TIMEOUT_NANOS,
                    "Live-model Nether portal entry timed out: "
                        + diagnostics()
            );
        }

        private void preparePortalSite(final ServerPlayer body) {
            final BlockPos feet = body.blockPosition();
            portalAnchor = feet.offset(-1, 0, 3);
            body.setGameMode(GameType.SURVIVAL);
            body.getInventory().clearContent();
            body.getEnderChestInventory().clearContent();
            body.removeAllEffects();
            body.clearFire();
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.getFoodData().setSaturation(5.0F);
            body.setDeltaMovement(Vec3.ZERO);
            for (int x = -6; x <= 6; x++) {
                for (int z = -5; z <= 9; z++) {
                    final BlockPos floor =
                            feet.offset(x, -1, z);
                    helper.getLevel().setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 1; y <= 6; y++) {
                        helper.getLevel().setBlockAndUpdate(
                                floor.above(y),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            for (int u = -1; u <= 4; u++) {
                for (int v = 0; v <= 5; v++) {
                    helper.getLevel().setBlockAndUpdate(
                            portalAnchor.offset(u, v, 1),
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                }
            }
            body.getInventory().setItem(
                    0,
                    new ItemStack(Items.OBSIDIAN, 14)
            );
            body.getInventory().setItem(
                    1,
                    new ItemStack(Items.FLINT_AND_STEEL)
            );
            body.getInventory().setItem(
                    2,
                    new ItemStack(Items.IRON_PICKAXE)
            );
            body.getInventory().setItem(
                    3,
                    new ItemStack(Items.CRAFTING_TABLE)
            );
            body.getInventory().setItem(
                    4,
                    new ItemStack(Items.OAK_LOG, 8)
            );
            body.getInventory().setItem(
                    5,
                    new ItemStack(Items.COOKED_BEEF, 16)
            );
            body.getInventory().setItem(
                    6,
                    new ItemStack(Items.WATER_BUCKET)
            );
            body.getInventory().setItem(
                    7,
                    new ItemStack(Items.COBBLESTONE, 64)
            );
            body.setItemSlot(
                    EquipmentSlot.OFFHAND,
                    new ItemStack(Items.SHIELD)
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
            body.getInventory().setSelectedSlot(0);
            body.inventoryMenu.broadcastChanges();
        }

        private void prepareConstructionScaffold(
                final ServerPlayer body
        ) {
            for (int u = 1; u <= 2; u++) {
                helper.getLevel().setBlockAndUpdate(
                        portalAnchor.offset(u, 1, -1),
                        Blocks.SMOOTH_STONE.defaultBlockState()
                );
                helper.getLevel().setBlockAndUpdate(
                        portalAnchor.offset(u, 2, -1),
                        Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                );
            }
            body.teleportTo(
                    portalAnchor.getX() + 2.0D,
                    portalAnchor.getY() + 2.5D,
                    portalAnchor.getZ() - 0.31D
            );
            body.setDeltaMovement(Vec3.ZERO);
            body.fallDistance = 0.0F;
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(
                            portalAnchor.offset(1, 2, 0)
                    )
            );
            body.setYHeadRot(body.getYRot());
        }

        private int activePortalBlocks() {
            int blocks = 0;
            for (int u = 1; u <= 2; u++) {
                for (int v = 1; v <= 3; v++) {
                    if (helper.getLevel().getBlockState(
                            portalAnchor.offset(u, v, 0)
                    ).is(Blocks.NETHER_PORTAL)) {
                        blocks++;
                    }
                }
            }
            return blocks;
        }

        private static int flintDamage(final ServerPlayer body) {
            for (int slot = 0;
                    slot < body.getInventory().getContainerSize();
                    slot++) {
                final ItemStack stack =
                        body.getInventory().getItem(slot);
                if (stack.is(Items.FLINT_AND_STEEL)) {
                    return stack.getDamageValue();
                }
            }
            return -1;
        }

        private ServerPlayer body() {
            return AiPlayerManager.onlinePlayer(runtime.server())
                    .orElseThrow(() -> helper.assertionException(
                            "Nether-route companion body disappeared"
                    ));
        }

        private void assertNoHumanPlayers() {
            final long humans = runtime.server()
                    .getPlayerList()
                    .getPlayers()
                    .stream()
                    .filter(player -> !player.getUUID().equals(
                            runtime.worldData().companionUuid()
                    ))
                    .count();
            helper.assertTrue(
                    humans == 0L,
                    "Nether-route autonomy retained " + humans
                        + " human player(s) after the command"
            );
        }

        private void assertWithinModelDeadline(final String message) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private String diagnostics() {
            final ServerPlayer body = body();
            return "supervisor=" + runtime.skillSupervisor().snapshot()
                    + ", goal=" + runtime.goals().snapshot()
                    + ", dimension="
                    + body.level().dimension().identifier()
                    + ", body=" + body.position()
                    + ", obsidian="
                    + body.getInventory().countItem(Items.OBSIDIAN)
                    + ", flintDamage=" + flintDamage(body)
                    + ", portalBlocks=" + activePortalBlocks()
                    + ", buildSkillObserved="
                    + buildSkillObserved
                    + ", entrySkillObserved="
                    + entrySkillObserved
                    + ", milestones="
                    + runtime.worldData()
                        .verifiedRouteProgress(
                                Math.max(0L, goalRevision)
                        ).milestones();
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum NetherPortalStage {
        BODY,
        SITE_SCAN,
        POST_ANCHOR_SCAN,
        PROBE,
        GOAL,
        BUILD_SKILL,
        BUILD,
        ENTRY_SKILL,
        ENTER,
        DONE
    }

    /**
     * Release-excluded causal gate for the completion phase boundary between
     * Nether resources and the stronghold search. All Overworld/Nether
     * fixture work happens before {@link #submitGoal()}. The victory variant
     * creates its deterministic release-excluded End combat arena only after
     * the body genuinely crosses the portal; it never selects a model skill,
     * credits damage, kills the dragon, or moves the body through a portal.
     */
    private static final class LiveEyeCraftReturnStrongholdScenario {
        private static final long SETUP_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(150).toNanos();
        private static final long CHAIN_TIMEOUT_NANOS =
                java.time.Duration.ofMinutes(22).toNanos();
        private static final long END_SETTLE_TICKS = 80L;
        private static final int PORTAL_WAIT_TICKS = 400;
        private static final int COURSE_HALF_LENGTH = 270;
        private static final double COURSE_HALF_WIDTH = 11.5;
        private static final int STRONGHOLD_APPROACH_OFFSET = 192;
        private static final int STRONGHOLD_SEARCH_HALF_WIDTH = 28;
        private static final int STRONGHOLD_EVIDENCE_RADIUS = 8;
        private static final int NETHER_LANE_LENGTH = 28;
        private static final int NETHER_REMOTE_DISTANCE = 22;
        private static final int REQUIRED_EYES = 14;
        private static final int END_PORTAL_FRAME_COUNT = 12;

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;
        private final boolean originalSpawnMobs;
        private final boolean originalSpawnMonsters;
        private final boolean originalGenerateStructures;
        private final boolean requireVictory;

        private EyeReturnStage stage = EyeReturnStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private UUID bodyId;
        private BlockPos courseCenter;
        private BlockPos portalInterior;
        private BlockPos netherPortalInterior;
        private BlockPos strongholdTarget;
        private BlockPos strongholdEvidence;
        private BlockPos endPortalCenter;
        private Vec3 netherArrival;
        private Vec3 strongholdReachStart;
        private EndVictoryArena victoryArena;
        private long enteredNetherAt = -1L;
        private long enteredEndAt = -1L;
        private long portalEntryStartedAt = -1L;
        private long goalRevisionBefore = -1L;
        private long goalRevision = -1L;
        private long stageStartedNanos;
        private CompletableFuture<VerifiedPortalEdge>
                verifiedPortalWrite;
        private boolean priorCheckpointInstalled;
        private boolean netherPrepared;
        private boolean craftSkillObserved;
        private boolean eyeCraftObserved;
        private boolean returnSkillObserved;
        private boolean overworldReturnObserved;
        private boolean triangulationSkillObserved;
        private boolean triangulationHandoffValidated;
        private boolean reachSkillObserved;
        private boolean strongholdHandoffValidated;
        private boolean portalRoomSearchObserved;
        private boolean activationSkillObserved;
        private int activationEyeCountBefore = -1;
        private int activationFilledFramesBefore = -1;
        private boolean endEntrySkillObserved;
        private boolean fightSkillObserved;
        private boolean endReturnSkillObserved;
        private boolean endArenaPrepared;
        private int strongholdReachPickaxeDamage;
        private int strongholdReachTorchCount;
        private boolean cleaned;

        private LiveEyeCraftReturnStrongholdScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final boolean requireVictory
        ) {
            this.helper = helper;
            this.runtime = runtime;
            this.requireVictory = requireVictory;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
            originalSpawnMobs = helper.getLevel()
                    .getGameRules()
                    .get(GameRules.SPAWN_MOBS);
            originalSpawnMonsters = helper.getLevel()
                    .getGameRules()
                    .get(GameRules.SPAWN_MONSTERS);
            originalGenerateStructures = runtime.server()
                    .getWorldGenSettings()
                    .options()
                    .generateStructures();
        }

        private void start() {
            finishScenarioGoal(runtime);
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Eye-return companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case ENTERING_NETHER -> waitForNetherEntry();
                case NETHER_READY -> waitForNetherReadiness();
                case GOAL -> waitForGoal();
                case AUTONOMOUS_CHAIN -> observeAutonomousChain();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Eye-return companion body failed: " + status
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Eye-return companion body timed out"
                );
                return;
            }
            final ServerPlayer body = body();
            bodyId = body.getUUID();
            prepareOverworldCourseAndPortal(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = EyeReturnStage.ENTERING_NETHER;
            stageStartedNanos = System.nanoTime();
        }

        private void prepareOverworldCourseAndPortal(
                final ServerPlayer body
        ) {
            setNaturalSpawning(false, false);
            setGenerateStructures(true);
            final BlockPos structureSearchOrigin = helper.absolutePos(
                    new BlockPos(300, 8, 300)
            );
            strongholdTarget = helper.getLevel()
                    .findNearestMapStructure(
                            StructureTags.EYE_OF_ENDER_LOCATED,
                            structureSearchOrigin,
                            256,
                            false
                    );
            helper.assertTrue(
                    strongholdTarget != null,
                    "GameTest world has no generated stronghold for the "
                        + "live Eye-return chain"
            );
            /*
             * Keep this controlled continuous gate short enough to run
             * repeatedly. The generated-structure lookup is fixture-only,
             * before ordinary chat, and is never passed to production. The
             * body and model still receive only normal Eye trajectories.
             */
            courseCenter = new BlockPos(
                    strongholdTarget.getX(),
                    structureSearchOrigin.getY(),
                    strongholdTarget.getZ()
                        + STRONGHOLD_APPROACH_OFFSET
            );
            final BlockPos nearestFromCourse = helper.getLevel()
                    .findNearestMapStructure(
                            StructureTags.EYE_OF_ENDER_LOCATED,
                            courseCenter,
                            256,
                            false
                    );
            helper.assertTrue(
                    strongholdTarget.equals(nearestFromCourse),
                    "Controlled course did not retain the same vanilla "
                        + "Eye target"
            );

            final double towardX =
                    strongholdTarget.getX() + 0.5
                        - (courseCenter.getX() + 0.5);
            final double towardZ =
                    strongholdTarget.getZ() + 0.5
                        - (courseCenter.getZ() + 0.5);
            final double length = Math.hypot(towardX, towardZ);
            helper.assertTrue(
                    length > 32.0,
                    "Generated stronghold is too close for a fair "
                        + "two-ray live gate"
            );
            final double directionX = towardX / length;
            final double directionZ = towardZ / length;
            final double baselineX = -directionZ;
            final double baselineZ = directionX;

            /*
             * Build both legal perpendicular directions. The production
             * compound receives no fixture coordinate and chooses a
             * candidate only after measuring its own first Eye trajectory.
             */
            final int radius = COURSE_HALF_LENGTH + 12;
            for (int x = courseCenter.getX() - radius;
                    x <= courseCenter.getX() + radius; x++) {
                for (int z = courseCenter.getZ() - radius;
                        z <= courseCenter.getZ() + radius; z++) {
                    final double deltaX =
                            x + 0.5 - (courseCenter.getX() + 0.5);
                    final double deltaZ =
                            z + 0.5 - (courseCenter.getZ() + 0.5);
                    final double forward =
                            deltaX * baselineX + deltaZ * baselineZ;
                    final double lateral =
                            deltaX * directionX + deltaZ * directionZ;
                    if (Math.abs(forward) > COURSE_HALF_LENGTH + 0.5
                            || Math.abs(lateral)
                                > COURSE_HALF_WIDTH) {
                        continue;
                    }
                    final BlockPos floor = new BlockPos(
                            x,
                            courseCenter.getY() - 1,
                            z
                    );
                    helper.getLevel().setBlockAndUpdate(
                            floor,
                            Blocks.SMOOTH_STONE.defaultBlockState()
                    );
                    for (int y = 1; y <= 7; y++) {
                        helper.getLevel().setBlockAndUpdate(
                                floor.above(y),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            prepareStrongholdApproachCourse(
                    directionX,
                    directionZ,
                    baselineX,
                    baselineZ
            );

            portalInterior = courseCenter;
            buildActivePortal(helper.getLevel(), portalInterior);
            helper.assertTrue(
                    helper.getLevel()
                        .getBlockState(portalInterior)
                        .is(Blocks.NETHER_PORTAL),
                    "Pre-command source frame did not ignite into a "
                        + "vanilla Nether portal"
            );
            prepareNetherDestinationPortal();
            body.setGameMode(GameType.SURVIVAL);
            body.stopRiding();
            body.getInventory().clearContent();
            body.getEnderChestInventory().clearContent();
            body.removeAllEffects();
            body.clearFire();
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.getFoodData().setSaturation(5.0F);
            body.fallDistance = 0.0F;
            portalEntryStartedAt = helper.getTick();
            body.teleportTo(
                    portalInterior.getX() + 0.5,
                    portalInterior.getY(),
                    portalInterior.getZ() + 0.5
            );
            body.setDeltaMovement(Vec3.ZERO);
            dev.mcai.companion.MinecraftAiCompanion.LOGGER.info(
                    "Live Eye-return source portal body={} scale={} "
                        + "course={} expectedNether={}",
                    body.position(),
                    net.minecraft.world.level.dimension.DimensionType
                        .getTeleportationScale(
                            body.level().dimensionType(),
                            runtime.server()
                                .getLevel(Level.NETHER)
                                .dimensionType()
                        ),
                    courseCenter,
                    netherPortalInterior
            );
        }

        /**
         * Builds two pre-command diagonal walking corridors from either
         * legal triangulation endpoint to a buried search volume. The
         * fixture does not expose the target or wall to production; after
         * chat submission it never changes another block.
         */
        private void prepareStrongholdApproachCourse(
                final double directionX,
                final double directionZ,
                final double baselineX,
                final double baselineZ
        ) {
            final var level = helper.getLevel();
            final double targetX = strongholdTarget.getX() + 0.5;
            final double targetZ = strongholdTarget.getZ() + 0.5;
            final double positiveX = courseCenter.getX() + 0.5
                    + baselineX * COURSE_HALF_LENGTH;
            final double positiveZ = courseCenter.getZ() + 0.5
                    + baselineZ * COURSE_HALF_LENGTH;
            final double negativeX = courseCenter.getX() + 0.5
                    - baselineX * COURSE_HALF_LENGTH;
            final double negativeZ = courseCenter.getZ() + 0.5
                    - baselineZ * COURSE_HALF_LENGTH;
            final int minimumX = (int) Math.floor(Math.min(
                    targetX,
                    Math.min(positiveX, negativeX)
            )) - STRONGHOLD_SEARCH_HALF_WIDTH;
            final int maximumX = (int) Math.ceil(Math.max(
                    targetX,
                    Math.max(positiveX, negativeX)
            )) + STRONGHOLD_SEARCH_HALF_WIDTH;
            final int minimumZ = (int) Math.floor(Math.min(
                    targetZ,
                    Math.min(positiveZ, negativeZ)
            )) - STRONGHOLD_SEARCH_HALF_WIDTH;
            final int maximumZ = (int) Math.ceil(Math.max(
                    targetZ,
                    Math.max(positiveZ, negativeZ)
            )) + STRONGHOLD_SEARCH_HALF_WIDTH;
            final double corridorRadiusSquared =
                    COURSE_HALF_WIDTH * COURSE_HALF_WIDTH;

            for (int x = minimumX; x <= maximumX; x++) {
                for (int z = minimumZ; z <= maximumZ; z++) {
                    final double sampleX = x + 0.5;
                    final double sampleZ = z + 0.5;
                    if (distanceSquaredToSegment(
                            sampleX,
                            sampleZ,
                            positiveX,
                            positiveZ,
                            targetX,
                            targetZ
                    ) > corridorRadiusSquared
                            && distanceSquaredToSegment(
                                sampleX,
                                sampleZ,
                                negativeX,
                                negativeZ,
                                targetX,
                                targetZ
                            ) > corridorRadiusSquared) {
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
                    for (int y = 1; y <= 7; y++) {
                        level.setBlockAndUpdate(
                                floor.above(y),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }

            for (int x = -STRONGHOLD_SEARCH_HALF_WIDTH;
                    x <= STRONGHOLD_SEARCH_HALF_WIDTH; x++) {
                for (int z = -STRONGHOLD_SEARCH_HALF_WIDTH;
                        z <= STRONGHOLD_SEARCH_HALF_WIDTH; z++) {
                    for (int y = -14; y <= -2; y++) {
                        level.setBlockAndUpdate(
                                new BlockPos(
                                        strongholdTarget.getX() + x,
                                        courseCenter.getY() + y,
                                        strongholdTarget.getZ() + z
                                ),
                                Blocks.STONE.defaultBlockState()
                        );
                    }
                }
            }
            for (int offset = -STRONGHOLD_EVIDENCE_RADIUS;
                    offset <= STRONGHOLD_EVIDENCE_RADIUS;
                    offset++) {
                for (int y = -10; y <= -3; y++) {
                    level.setBlockAndUpdate(
                            new BlockPos(
                                    strongholdTarget.getX()
                                        + STRONGHOLD_EVIDENCE_RADIUS,
                                    courseCenter.getY() + y,
                                    strongholdTarget.getZ() + offset
                            ),
                            Blocks.STONE_BRICKS.defaultBlockState()
                    );
                    level.setBlockAndUpdate(
                            new BlockPos(
                                    strongholdTarget.getX()
                                        - STRONGHOLD_EVIDENCE_RADIUS,
                                    courseCenter.getY() + y,
                                    strongholdTarget.getZ() + offset
                            ),
                            Blocks.STONE_BRICKS.defaultBlockState()
                    );
                    level.setBlockAndUpdate(
                            new BlockPos(
                                    strongholdTarget.getX() + offset,
                                    courseCenter.getY() + y,
                                    strongholdTarget.getZ()
                                        + STRONGHOLD_EVIDENCE_RADIUS
                            ),
                            Blocks.STONE_BRICKS.defaultBlockState()
                    );
                    level.setBlockAndUpdate(
                            new BlockPos(
                                    strongholdTarget.getX() + offset,
                                    courseCenter.getY() + y,
                                    strongholdTarget.getZ()
                                        - STRONGHOLD_EVIDENCE_RADIUS
                            ),
                            Blocks.STONE_BRICKS.defaultBlockState()
                    );
                }
            }
            strongholdEvidence = new BlockPos(
                    strongholdTarget.getX()
                        + STRONGHOLD_EVIDENCE_RADIUS,
                    courseCenter.getY() - 5,
                    strongholdTarget.getZ()
            );
            if (requireVictory) {
                prepareBuriedStrongholdPortalMaze();
            }
            helper.assertTrue(
                    Math.abs(directionX) + Math.abs(directionZ) > 0.99,
                    "Stronghold approach lacked a normalized Eye bearing"
            );
        }

        /**
         * Gives the continuous completion variant a traversable,
         * first-person-only stronghold interior at the measured search area.
         * The measured centre remains solid so the descending search never
         * walks over a fixture-created void. A buried receiving room begins
         * one block beyond the east stronghold wall, where the production
         * wall-entry gate physically finishes. A roofed two-turn corridor
         * then hides the portal ring from that handoff frame.
         */
        private void prepareBuriedStrongholdPortalMaze() {
            final var level = helper.getLevel();
            final BlockPos chamber = new BlockPos(
                    strongholdTarget.getX()
                        + STRONGHOLD_EVIDENCE_RADIUS + 1,
                    courseCenter.getY() - 4,
                    strongholdTarget.getZ()
            );

            for (int x = 0; x <= 6; x++) {
                for (int z = -8; z <= 8; z++) {
                    level.setBlockAndUpdate(
                            chamber.offset(x, -1, z),
                            Blocks.STONE_BRICKS.defaultBlockState()
                    );
                    for (int y = 0; y <= 2; y++) {
                        level.setBlockAndUpdate(
                                chamber.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                    level.setBlockAndUpdate(
                            chamber.offset(x, 3, z),
                            Blocks.STONE.defaultBlockState()
                    );
                }
            }

            /*
             * Build an opaque corridor shell before carving its actual
             * interior. The ring sits beyond a right-angle turn, so loaded
             * world geometry does not become fair evidence until the body
             * physically explores there.
             */
            for (int x = 7; x <= 23; x++) {
                for (int z = -2; z <= 21; z++) {
                    level.setBlockAndUpdate(
                            chamber.offset(x, -1, z),
                            Blocks.STONE_BRICKS.defaultBlockState()
                    );
                    for (int y = 0; y <= 3; y++) {
                        level.setBlockAndUpdate(
                                chamber.offset(x, y, z),
                                Blocks.STONE.defaultBlockState()
                        );
                    }
                }
            }
            for (int x = 7; x <= 20; x++) {
                carveBuriedStrongholdInterior(chamber, x, 0);
            }
            for (int z = 0; z <= 12; z++) {
                carveBuriedStrongholdInterior(chamber, 16, z);
            }
            for (int x = 13; x <= 19; x++) {
                for (int z = 11; z <= 19; z++) {
                    carveBuriedStrongholdInterior(chamber, x, z);
                }
            }

            endPortalCenter = chamber.offset(16, 0, 15);
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
            strongholdEvidence = chamber.offset(0, -1, 6);
            helper.assertTrue(
                    level.getBlockState(
                        new BlockPos(
                            strongholdTarget.getX(),
                            courseCenter.getY() - 1,
                            strongholdTarget.getZ()
                        )
                    ).is(Blocks.SMOOTH_STONE)
                        && level.getBlockState(
                            chamber.above(3)
                        ).is(Blocks.STONE),
                    "Buried portal maze exposed its chamber at the "
                        + "ordinary approach surface"
            );
        }

        private void carveBuriedStrongholdInterior(
                final BlockPos chamber,
                final int offsetX,
                final int offsetZ
        ) {
            helper.getLevel().setBlockAndUpdate(
                    chamber.offset(offsetX, -1, offsetZ),
                    Blocks.STONE_BRICKS.defaultBlockState()
            );
            for (int y = 0; y <= 2; y++) {
                helper.getLevel().setBlockAndUpdate(
                        chamber.offset(offsetX, y, offsetZ),
                        Blocks.AIR.defaultBlockState()
                );
            }
        }

        private void setEmptyEndPortalFrame(
                final BlockPos position,
                final Direction facing
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
                            false
                        )
            );
        }

        private static double distanceSquaredToSegment(
                final double sampleX,
                final double sampleZ,
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
                return Math.pow(sampleX - startX, 2.0)
                        + Math.pow(sampleZ - startZ, 2.0);
            }
            final double projection = Math.max(
                    0.0,
                    Math.min(
                            1.0,
                            ((sampleX - startX) * deltaX
                                + (sampleZ - startZ) * deltaZ)
                                / lengthSquared
                    )
            );
            final double closestX = startX + projection * deltaX;
            final double closestZ = startZ + projection * deltaZ;
            return Math.pow(sampleX - closestX, 2.0)
                    + Math.pow(sampleZ - closestZ, 2.0);
        }

        private void prepareNetherDestinationPortal() {
            final var nether = runtime.server().getLevel(Level.NETHER);
            helper.assertTrue(
                    nether != null,
                    "GameTest server has no Nether level"
            );
            netherPortalInterior = new BlockPos(
                    Math.floorDiv(courseCenter.getX(), 8),
                    64,
                    Math.floorDiv(courseCenter.getZ(), 8)
            );
            for (int x = -6; x <= 7; x++) {
                for (int z = -6; z <= 6; z++) {
                    final BlockPos floor =
                            netherPortalInterior.offset(x, -1, z);
                    nether.setBlockAndUpdate(
                            floor,
                            Blocks.NETHERRACK.defaultBlockState()
                    );
                    for (int y = 1; y <= 5; y++) {
                        nether.setBlockAndUpdate(
                                floor.above(y),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            buildActivePortal(nether, netherPortalInterior);
            helper.assertTrue(
                    nether.getBlockState(netherPortalInterior)
                            .is(Blocks.NETHER_PORTAL)
                        && nether.getBlockState(
                            netherPortalInterior.above(2)
                        ).is(Blocks.NETHER_PORTAL),
                    "Pre-command Nether destination portal did not "
                        + "remain active"
            );
            final Optional<BlockPos> indexedPortal =
                    nether.getPortalForcer()
                        .findClosestPortalPosition(
                                netherPortalInterior,
                                true,
                                nether.getWorldBorder()
                        );
            helper.assertTrue(
                    indexedPortal.filter(position ->
                            position.distSqr(netherPortalInterior)
                                <= 16.0
                    ).isPresent(),
                    "Pre-command Nether destination portal was not "
                        + "indexed by vanilla PortalForcer: "
                        + indexedPortal
            );
        }

        private void waitForNetherEntry() {
            assertSetupDeadline(
                    "Live Eye-return setup timed out before Nether entry"
            );
            assertProbeHealthy();
            final ServerPlayer body = body();
            if (!body.level().dimension().equals(Level.NETHER)) {
                if ((helper.getTick() - createdAt) % 20L == 0L) {
                    dev.mcai.companion.MinecraftAiCompanion.LOGGER.info(
                            "Live Eye-return source wait tick={} body={} "
                                + "feetState={} insidePortal={}",
                            helper.getTick(),
                            body.position(),
                            body.level().getBlockState(
                                body.blockPosition()
                            ),
                            body.portalProcess != null
                                && body.portalProcess
                                    .isInsidePortalThisTick()
                    );
                }
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= PORTAL_WAIT_TICKS,
                        "Companion did not physically enter the prepared "
                            + "Nether portal"
                );
                return;
            }
            if (enteredNetherAt < 0L) {
                enteredNetherAt = helper.getTick();
                recordObservedInitialTraversal(body);
                return;
            }
            /*
             * Let vanilla finish installing the destination player ticket
             * and let the traversal observer enqueue its durable edge.
             */
            if (helper.getTick() - enteredNetherAt < 3L) {
                return;
            }
            helper.assertTrue(
                    verifiedPortalWrite != null,
                    "Physical Nether entry did not start its verified "
                        + "portal-memory write"
            );
            if (!verifiedPortalWrite.isDone()) {
                return;
            }
            helper.assertTrue(
                    !verifiedPortalWrite.isCompletedExceptionally(),
                    "Physical Nether traversal could not be persisted"
            );
            if (netherPrepared) {
                stage = EyeReturnStage.NETHER_READY;
                return;
            }
            /*
             * Commit the phase before fixture mutations. Vanilla inventory
             * and advancement callbacks can cause another scheduled
             * GameTest callback to observe this scenario before the current
             * callback unwinds; the setup must remain exactly-once.
             */
            netherPrepared = true;
            stage = EyeReturnStage.NETHER_READY;
            stageStartedNanos = System.nanoTime();
            prepareNetherLaneAndOwnedMaterials(body);
        }

        private void recordObservedInitialTraversal(
                final ServerPlayer body
        ) {
            helper.assertTrue(
                    portalEntryStartedAt >= 0L
                        && helper.getLevel()
                            .getBlockState(portalInterior)
                            .is(Blocks.NETHER_PORTAL)
                        && body.level().dimension().equals(Level.NETHER)
                        && body.level()
                            .getBlockState(nearestPortalBlock(body))
                            .is(Blocks.NETHER_PORTAL),
                    "Initial portal traversal lacked physical endpoint "
                        + "evidence"
            );
            final PortalTraversalResult observed =
                    new PortalTraversalResult(
                            PortalKind.NETHER_PORTAL,
                            AiPlayerManager.status(runtime.server())
                                .sessionGeneration(),
                            DimensionRef.OVERWORLD,
                            new PerceptionVec3(
                                    portalInterior.getX() + 0.5,
                                    portalInterior.getY(),
                                    portalInterior.getZ() + 0.5
                            ),
                            new BlockCoordinate(
                                    portalInterior.getX(),
                                    portalInterior.getY(),
                                    portalInterior.getZ()
                            ),
                            DimensionRef.NETHER,
                            new PerceptionVec3(
                                    body.getX(),
                                    body.getY(),
                                    body.getZ()
                            ),
                            portalEntryStartedAt,
                            helper.getTick(),
                            Optional.of(DimensionRef.NETHER)
                    );
            verifiedPortalWrite = runtime.memory()
                    .portalEdges()
                    .recordTraversal(
                            runtime.worldData().companionUuid(),
                            observed,
                            Instant.now()
                    );
        }

        private void prepareNetherLaneAndOwnedMaterials(
                final ServerPlayer body
        ) {
            dev.mcai.companion.MinecraftAiCompanion.LOGGER.info(
                    "Live Eye-return Nether preparation arrival={} "
                        + "expectedPortal={} expectedState={}",
                    body.position(),
                    netherPortalInterior,
                    body.level().getBlockState(netherPortalInterior)
            );
            final BlockPos portal = nearestPortalBlock(body);
            dev.mcai.companion.MinecraftAiCompanion.LOGGER.info(
                    "Live Eye-return selected Nether portal={} "
                        + "arrival={}",
                    portal,
                    body.position()
            );
            final Direction.Axis axis = body.level()
                    .getBlockState(portal)
                    .getValue(NetherPortalBlock.AXIS);
            final Direction forward = axis == Direction.Axis.X
                    ? Direction.SOUTH
                    : Direction.EAST;
            final Direction side = forward.getClockWise();
            final BlockPos arrivalFeet = body.blockPosition();
            netherArrival = body.position();

            for (int step = -4; step <= NETHER_LANE_LENGTH; step++) {
                for (int lateral = -3; lateral <= 3; lateral++) {
                    final BlockPos column = arrivalFeet
                            .relative(forward, step)
                            .relative(side, lateral);
                    setUnlessPortal(
                            body,
                            column.below(),
                            Blocks.NETHERRACK.defaultBlockState()
                    );
                    for (int y = 0; y <= 3; y++) {
                        setUnlessPortal(
                                body,
                                column.above(y),
                                Math.abs(lateral) == 3
                                        ? Blocks.NETHERRACK
                                            .defaultBlockState()
                                        : Blocks.AIR.defaultBlockState()
                        );
                    }
                    setUnlessPortal(
                            body,
                            column.above(4),
                            Blocks.NETHERRACK.defaultBlockState()
                    );
                }
            }

            final BlockPos remote = arrivalFeet.relative(
                    forward,
                    NETHER_REMOTE_DISTANCE
            );
            body.teleportTo(
                    remote.getX() + 0.5,
                    remote.getY(),
                    remote.getZ() + 0.5
            );
            dev.mcai.companion.MinecraftAiCompanion.LOGGER.info(
                    "Live Eye-return moved pre-command body to remote={} "
                        + "from arrival={}",
                    body.position(),
                    netherArrival
            );
            body.setDeltaMovement(Vec3.ZERO);
            body.fallDistance = 0.0F;
            body.getInventory().clearContent();
            pickUpOwned(
                    body,
                    new ItemStack(Items.BLAZE_POWDER, REQUIRED_EYES)
            );
            pickUpOwned(
                    body,
                    new ItemStack(Items.ENDER_PEARL, REQUIRED_EYES)
            );
            body.getInventory().setItem(
                    4,
                    new ItemStack(Items.COOKED_BEEF, 16)
            );
            body.getInventory().setItem(
                    5,
                    new ItemStack(Items.IRON_PICKAXE)
            );
            body.getInventory().setItem(
                    6,
                    new ItemStack(Items.WATER_BUCKET)
            );
            body.getInventory().setItem(
                    7,
                    new ItemStack(Items.COBBLESTONE, 64)
            );
            body.getInventory().setItem(
                    8,
                    new ItemStack(Items.TORCH, 32)
            );
            if (requireVictory) {
                body.getInventory().setItem(
                        9,
                        new ItemStack(Items.BOW)
                );
                body.getInventory().setItem(
                        10,
                        new ItemStack(Items.ARROW, 99)
                );
                body.getInventory().setItem(
                        11,
                        new ItemStack(Items.ARROW, 29)
                );
                body.getInventory().setItem(
                        12,
                        new ItemStack(Items.DIAMOND_SWORD)
                );
            }
            body.setItemSlot(
                    EquipmentSlot.OFFHAND,
                    new ItemStack(Items.SHIELD)
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
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.getFoodData().setSaturation(5.0F);
            body.getInventory().setSelectedSlot(5);
            body.inventoryMenu.broadcastChanges();
            strongholdReachPickaxeDamage =
                    itemDamage(body, Items.IRON_PICKAXE);
            strongholdReachTorchCount =
                    body.getInventory().countItem(Items.TORCH);
            helper.assertTrue(
                    body.getInventory().countItem(Items.BLAZE_POWDER)
                            == REQUIRED_EYES
                        && body.getInventory().countItem(
                            Items.ENDER_PEARL
                        ) == REQUIRED_EYES
                        && body.position().distanceTo(netherArrival)
                            >= 16.0,
                    "Nether resource checkpoint was not established "
                        + "through owned pickups away from the portal"
            );
        }

        private void waitForNetherReadiness() {
            assertSetupDeadline(
                    "Live Eye-return setup timed out before ordinary chat"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            final var observation = runtime.observations()
                    .observe(runtime.goals().snapshot());
            if (!observation.semanticJson().contains(
                    "\"outputItemId\":\"minecraft:ender_eye\""
            )) {
                return;
            }
            submitGoal();
        }

        private void submitGoal() {
            final ServerPlayer body = body();
            humanSession = PlacedHuman.create(
                    helper,
                    runtime,
                    Vec3.atCenterOf(courseCenter)
            );
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in Eye-return test player lacked task "
                        + "permission"
            );
            goalRevisionBefore = runtime.goals().snapshot().revision();
            final String request = requireVictory
                    ? runtime.worldData().displayName()
                        + "，请继续通关Minecraft：用你背包里的材料"
                        + "合成足够的末影之眼，从下界正常返回主世界，"
                        + "定位并进入要塞，找到末地传送门房间并激活，"
                        + "进入末地击败末影龙，再通过中央返回传送门"
                        + "回到主世界。"
                    : runtime.worldData().displayName()
                        + "，请继续通关Minecraft："
                        + "用你背包里的材料合成足够的"
                        + "末影之眼，从下界正常返回主世界，"
                        + "然后开始定位要塞。";
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            human,
                            Component.literal(request)
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the Eye-return chat command"
            );
            final GoalSnapshot installed = runtime.goals().snapshot();
            if (installed.revision() > goalRevisionBefore
                    && installed.status() == GoalStatus.RUNNING) {
                installPriorRouteCheckpoint(installed);
            }
            humanSession.close();
            humanSession = null;
            helper.assertTrue(
                    body.getInventory().countItem(Items.ENDER_EYE) == 0,
                    "Fixture crafted Eyes after the command boundary"
            );
            stage = EyeReturnStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            assertNoHumanPlayers();
            assertSetupDeadline(
                    "Live model did not install the Eye-return goal"
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.status() == GoalStatus.RUNNING
                        && goal.goal().contains("通关"),
                    "Eye-return chat did not become a running "
                        + "completion goal: " + goal
            );
            installPriorRouteCheckpoint(goal);
            goalRevision = goal.revision();
            stage = EyeReturnStage.AUTONOMOUS_CHAIN;
            stageStartedNanos = System.nanoTime();
        }

        private void installPriorRouteCheckpoint(
                final GoalSnapshot goal
        ) {
            if (priorCheckpointInstalled
                    && goalRevision == goal.revision()) {
                return;
            }
            runtime.worldData().markVerifiedRouteMilestones(
                    goal.revision(),
                    EnumSet.of(
                            SurvivalMilestone.BODY_ACTIVE,
                            SurvivalMilestone.WOOD_OBTAINED,
                            SurvivalMilestone.BASIC_CRAFTING_READY,
                            SurvivalMilestone.STONE_TOOL_OBTAINED,
                            SurvivalMilestone.FOOD_SECURED,
                            SurvivalMilestone.IRON_TOOLKIT_OBTAINED,
                            SurvivalMilestone.NETHER_ENTERED,
                            SurvivalMilestone.BLAZE_MATERIAL_OBTAINED,
                            SurvivalMilestone.ENDER_PEARL_OBTAINED
                    )
            );
            goalRevision = goal.revision();
            priorCheckpointInstalled = true;
        }

        private void observeAutonomousChain() {
            assertNoHumanPlayers();
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= CHAIN_TIMEOUT_NANOS,
                    "Live Eye craft/return/stronghold chain timed out: "
                        + diagnostics()
            );
            /*
             * The first real human login can deliberately exercise the
             * production initial-anchor lifecycle: the authoritative
             * ServerPlayer is removed, then re-created with the same UUID.
             * There is a small server-thread window in which PlayerList has
             * no online companion.  Treat that window as lifecycle
             * reconciliation, not as a gameplay death or a model failure;
             * the next tick must reacquire the authoritative body before any
             * observation, skill, or world assertion is evaluated.
             */
            final Optional<ServerPlayer> bodyCandidate = AiPlayerManager
                    .onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                final var status = AiPlayerManager.status(runtime.server());
                helper.assertTrue(
                        status.state() != SessionState.FAILED,
                        "Eye-return companion body failed during lifecycle "
                            + "reconciliation: " + status
                );
                return;
            }
            final ServerPlayer body = bodyCandidate.orElseThrow();
            helper.assertTrue(
                    body.isAlive()
                        && !body.isDeadOrDying()
                        && body.getHealth() > 0.0F,
                    "Continuous Hardcore-policy completion body died; "
                        + "the run is terminal and must not respawn or "
                        + "request another model decision: "
                        + diagnostics()
            );
            final SkillSupervisor.Snapshot supervisor =
                    runtime.skillSupervisor().snapshot();
            final var milestones = runtime.worldData()
                    .verifiedRouteProgress(goalRevision)
                    .milestones();
            if ("craft_recipe".equals(supervisor.skillName())
                    && supervisor.boundGoalRevision() == goalRevision) {
                craftSkillObserved = true;
            }
            if (PortalSkills.RETURN_VIA_VERIFIED_PORTAL.equals(
                    supervisor.skillName()
            ) && supervisor.boundGoalRevision() == goalRevision) {
                returnSkillObserved = true;
            }
            if (StrongholdSkills
                    .TRIANGULATE_STRONGHOLD_SEARCH_AREA
                    .equals(supervisor.skillName())
                    && supervisor.boundGoalRevision()
                        == goalRevision) {
                triangulationSkillObserved = true;
            }
            if (StrongholdSkills.REACH_OBSERVED_STRONGHOLD.equals(
                    supervisor.skillName()
            ) && supervisor.boundGoalRevision() == goalRevision) {
                reachSkillObserved = true;
            }
            if (StrongholdSkills
                    .SEARCH_OBSERVED_STRONGHOLD_PORTAL_ROOM
                    .equals(supervisor.skillName())
                    && supervisor.boundGoalRevision()
                        == goalRevision) {
                portalRoomSearchObserved = true;
            }
            if ("activate_observed_end_portal".equals(
                    supervisor.skillName()
            ) && supervisor.boundGoalRevision() == goalRevision) {
                if (!activationSkillObserved) {
                    activationEyeCountBefore =
                            body.getInventory().countItem(
                                Items.ENDER_EYE
                            );
                    activationFilledFramesBefore =
                            filledEndPortalFrames();
                }
                activationSkillObserved = true;
            }
            if ("find_and_enter_observed_portal".equals(
                    supervisor.skillName()
            ) && supervisor.boundGoalRevision() == goalRevision) {
                if (milestones.contains(
                        SurvivalMilestone.DRAGON_KILLED
                )) {
                    endReturnSkillObserved = true;
                } else {
                    endEntrySkillObserved = true;
                }
            }
            if ("fight_ender_dragon".equals(
                    supervisor.skillName()
            ) && supervisor.boundGoalRevision() == goalRevision) {
                fightSkillObserved = true;
            }

            final int eyes =
                    body.getInventory().countItem(Items.ENDER_EYE);
            if (eyes >= REQUIRED_EYES) {
                helper.assertTrue(
                        body.getInventory().countItem(
                            Items.BLAZE_POWDER
                        ) == 0
                            && body.getInventory().countItem(
                                Items.ENDER_PEARL
                            ) == 0,
                        "Eyes appeared without consuming the normally "
                            + "owned powder and pearls"
                );
                eyeCraftObserved = true;
            }
            if (body.level().dimension().equals(Level.OVERWORLD)
                    && !endArenaPrepared) {
                helper.assertTrue(
                        eyeCraftObserved && returnSkillObserved,
                        "Body returned before the model-composed Eye and "
                            + "verified-portal phases: " + diagnostics()
                );
                overworldReturnObserved = true;
            }

            if (endArenaPrepared
                    && body.level().dimension().equals(Level.OVERWORLD)) {
                if (milestones.contains(
                        SurvivalMilestone.RETURNED_FROM_END
                )) {
                    helper.assertTrue(
                            bodyId.equals(body.getUUID())
                                && portalRoomSearchObserved
                                && activationSkillObserved
                                && endEntrySkillObserved
                                && fightSkillObserved
                                && endReturnSkillObserved,
                            "Continuous Nether-material completion lost "
                                + "one model/body handoff: "
                                + diagnostics()
                    );
                    stage = EyeReturnStage.DONE;
                    helper.succeed();
                }
                return;
            }

            if (body.level().dimension().equals(Level.END)) {
                helper.assertTrue(
                        requireVictory
                            && strongholdHandoffValidated
                            && portalRoomSearchObserved
                            && activationSkillObserved
                            && endEntrySkillObserved
                            && activeEndPortalBlocks() == 9
                            && exactActivationEyeConsumption(body),
                        "End entry skipped a stronghold, activation, or "
                            + "inventory handoff: " + diagnostics()
                );
                if (!endArenaPrepared) {
                    if (enteredEndAt < 0L) {
                        enteredEndAt = helper.getTick();
                        return;
                    }
                    if (helper.getTick() - enteredEndAt
                            < END_SETTLE_TICKS) {
                        return;
                    }
                    victoryArena = prepareEndVictoryArena(
                            helper,
                            runtime,
                            body,
                            false,
                            false
                    );
                    endArenaPrepared = true;
                    return;
                }
                if (milestones.contains(
                        SurvivalMilestone.DRAGON_KILLED
                )) {
                    helper.assertTrue(
                            fightSkillObserved
                                && victoryArena != null
                                && hasPhysicalDragonDamageEvidence(
                                    body,
                                    victoryArena
                                ),
                            "Continuous dragon milestone lacked physical "
                                + "combat evidence: " + diagnostics()
                    );
                    if (!victoryArena.dragon().isRemoved()) {
                        victoryArena.dragon().setNoAi(false);
                    }
                    activateEndReturnPortal(
                            runtime.server().getLevel(Level.END),
                            victoryArena.returnPortalCenter()
                    );
                }
                return;
            }

            final Optional<EyeTraceHistorySnapshot> history =
                    runtime.eyeTraceResults().snapshot(goalRevision);
            if (history.flatMap(
                    EyeTraceHistorySnapshot::estimatedIntersection
            ).isEmpty()) {
                return;
            }
            final List<EyeTraceSnapshot> traces =
                    history.orElseThrow().traces();
            helper.assertTrue(
                    traces.size() >= 2,
                    "Stronghold intersection lacked two fair Eye traces"
            );
            final EyeTraceSnapshot first = traces.get(0);
            final EyeTraceSnapshot second = traces.get(1);
            final double baseline = Math.hypot(
                    second.throwOrigin().x()
                        - first.throwOrigin().x(),
                    second.throwOrigin().z()
                        - first.throwOrigin().z()
            );
            if (!triangulationHandoffValidated) {
                helper.assertTrue(
                        priorCheckpointInstalled
                            && craftSkillObserved
                            && eyeCraftObserved
                            && returnSkillObserved
                            && overworldReturnObserved
                            && triangulationSkillObserved
                            && bodyId.equals(body.getUUID())
                            && body.level().dimension()
                                .equals(Level.OVERWORLD)
                            && baseline >= 250.0
                            && body.getInventory().countItem(
                                Items.ENDER_EYE
                            ) == 12
                            && milestones.contains(
                                SurvivalMilestone
                                    .STRONGHOLD_SEARCH_AREA_TRIANGULATED
                            ),
                        "Live Eye-return chain lost a causal or physical "
                            + "handoff: " + diagnostics()
                );
                triangulationHandoffValidated = true;
                strongholdReachStart = body.position();
                return;
            }

            if (strongholdHandoffValidated) {
                if (activeEndPortalBlocks() == 9) {
                    helper.assertTrue(
                            activationSkillObserved
                                && exactActivationEyeConsumption(body),
                            "End portal activated without the observed "
                                + "skill and exact Eye consumption: "
                                + diagnostics()
                    );
                }
                return;
            }

            final boolean strongholdVisible = runtime.coreFrames()
                    .current()
                    .filter(frame ->
                            frame.dimension().equals(
                                DimensionRef.OVERWORLD
                            )
                    )
                    .stream()
                    .flatMap(frame ->
                            frame.visibleBlockFaces().stream()
                    )
                    .anyMatch(face ->
                            face.blockTypeId().equals(
                                "minecraft:stone_bricks"
                            )
                                || face.blockTypeId().equals(
                                    "minecraft:cracked_stone_bricks"
                                )
                                || face.blockTypeId().equals(
                                    "minecraft:mossy_stone_bricks"
                                )
                    );
            if (!strongholdVisible) {
                return;
            }
            helper.assertTrue(
                    reachSkillObserved
                        && strongholdReachStart != null
                        && Math.hypot(
                            body.getX() - strongholdReachStart.x(),
                            body.getZ() - strongholdReachStart.z()
                        ) >= 180.0
                        && body.getY()
                            <= courseCenter.getY() - 2.0
                        && helper.getLevel().getBlockState(
                            strongholdEvidence
                        ).is(Blocks.STONE_BRICKS)
                        && itemDamage(body, Items.IRON_PICKAXE)
                            > strongholdReachPickaxeDamage
                        && body.getInventory().countItem(Items.TORCH)
                            < strongholdReachTorchCount,
                    "Live stronghold reach lacked physical travel, "
                        + "excavation, lighting, or preserved visible "
                        + "evidence: " + diagnostics()
            );
            strongholdHandoffValidated = true;
            if (!requireVictory) {
                stage = EyeReturnStage.DONE;
                helper.succeed();
            }
        }

        private void assertProbeHealthy() {
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
        }

        private void assertSetupDeadline(final String message) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= SETUP_TIMEOUT_NANOS,
                    message + ": " + diagnostics()
            );
        }

        private void assertNoHumanPlayers() {
            final long humans = runtime.server()
                    .getPlayerList()
                    .getPlayers()
                    .stream()
                    .filter(player -> !player.getUUID().equals(
                            runtime.worldData().companionUuid()
                    ))
                    .count();
            helper.assertTrue(
                    humans == 0L,
                    "Eye-return autonomy retained " + humans
                        + " human player(s) after the command"
            );
        }

        private ServerPlayer body() {
            return AiPlayerManager.onlinePlayer(runtime.server())
                    .orElseThrow(() -> helper.assertionException(
                            "Eye-return companion body disappeared"
                    ));
        }

        private BlockPos nearestPortalBlock(
                final ServerPlayer body
        ) {
            final BlockPos feet = body.blockPosition();
            BlockPos nearest = null;
            double nearestDistance = Double.POSITIVE_INFINITY;
            for (int x = -8; x <= 8; x++) {
                for (int y = -8; y <= 8; y++) {
                    for (int z = -8; z <= 8; z++) {
                        final BlockPos candidate =
                                feet.offset(x, y, z);
                        if (!body.level().getBlockState(candidate)
                                .is(Blocks.NETHER_PORTAL)) {
                            continue;
                        }
                        final double distance =
                                candidate.distToCenterSqr(
                                        body.position()
                                );
                        if (distance < nearestDistance) {
                            nearest = candidate.immutable();
                            nearestDistance = distance;
                        }
                    }
                }
            }
            helper.assertTrue(
                    nearest != null,
                    "Nether arrival exposed no nearby physical portal: "
                        + "body=" + body.position()
                        + ", sourceCourse=" + courseCenter
                        + ", sourcePortal=" + portalInterior
                        + ", expected=" + netherPortalInterior
                        + ", expectedState="
                        + body.level().getBlockState(
                            netherPortalInterior
                        )
                        + ", expectedTopState="
                        + body.level().getBlockState(
                            netherPortalInterior.above(2)
                        )
            );
            return nearest;
        }

        private void pickUpOwned(
                final ServerPlayer body,
                final ItemStack stack
        ) {
            final ItemEntity dropped = new ItemEntity(
                    body.level(),
                    body.getX(),
                    body.getY(),
                    body.getZ(),
                    stack
            );
            helper.assertTrue(
                    body.level().addFreshEntity(dropped),
                    "Could not spawn an ordinary pre-command owned drop"
            );
            dropped.playerTouch(body);
            helper.assertTrue(
                    dropped.isRemoved() || dropped.getItem().isEmpty(),
                    "Companion did not normally pick up its pre-command "
                        + "resource stack"
            );
        }

        private static int itemDamage(
                final ServerPlayer body,
                final net.minecraft.world.item.Item expected
        ) {
            for (int slot = 0;
                    slot < body.getInventory().getContainerSize();
                    slot++) {
                final ItemStack stack =
                        body.getInventory().getItem(slot);
                if (stack.is(expected)) {
                    return stack.getDamageValue();
                }
            }
            return -1;
        }

        private int activeEndPortalBlocks() {
            if (endPortalCenter == null) {
                return 0;
            }
            int blocks = 0;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (helper.getLevel().getBlockState(
                            endPortalCenter.offset(x, 0, z)
                    ).is(Blocks.END_PORTAL)) {
                        blocks++;
                    }
                }
            }
            return blocks;
        }

        private int filledEndPortalFrames() {
            if (endPortalCenter == null) {
                return 0;
            }
            int filled = 0;
            for (int offset = -1; offset <= 1; offset++) {
                filled += hasEndPortalEye(
                        endPortalCenter.offset(offset, 0, -2)
                );
                filled += hasEndPortalEye(
                        endPortalCenter.offset(offset, 0, 2)
                );
                filled += hasEndPortalEye(
                        endPortalCenter.offset(-2, 0, offset)
                );
                filled += hasEndPortalEye(
                        endPortalCenter.offset(2, 0, offset)
                );
            }
            return filled;
        }

        private int hasEndPortalEye(final BlockPos position) {
            final var state =
                    helper.getLevel().getBlockState(position);
            return state.is(Blocks.END_PORTAL_FRAME)
                    && state.getValue(
                        net.minecraft.world.level.block
                            .EndPortalFrameBlock.HAS_EYE
                    )
                    ? 1
                    : 0;
        }

        private boolean exactActivationEyeConsumption(
                final ServerPlayer body
        ) {
            if (activationEyeCountBefore < 0
                    || activationFilledFramesBefore < 0
                    || activationFilledFramesBefore
                        > END_PORTAL_FRAME_COUNT) {
                return false;
            }
            final int consumed =
                    activationEyeCountBefore
                        - body.getInventory().countItem(
                            Items.ENDER_EYE
                        );
            return consumed
                    == END_PORTAL_FRAME_COUNT
                        - activationFilledFramesBefore;
        }

        private static void buildActivePortal(
                final net.minecraft.server.level.ServerLevel level,
                final BlockPos interior
        ) {
            for (int x = -1; x <= 2; x++) {
                level.setBlockAndUpdate(
                        interior.offset(x, -1, 0),
                        Blocks.OBSIDIAN.defaultBlockState()
                );
                level.setBlockAndUpdate(
                        interior.offset(x, 3, 0),
                        Blocks.OBSIDIAN.defaultBlockState()
                );
            }
            for (int y = 0; y <= 2; y++) {
                level.setBlockAndUpdate(
                        interior.offset(-1, y, 0),
                        Blocks.OBSIDIAN.defaultBlockState()
                );
                level.setBlockAndUpdate(
                        interior.offset(2, y, 0),
                        Blocks.OBSIDIAN.defaultBlockState()
                );
                for (int x = 0; x <= 1; x++) {
                    level.setBlockAndUpdate(
                            interior.offset(x, y, 0),
                            Blocks.AIR.defaultBlockState()
                    );
                }
            }
            /*
             * Let vanilla's fire placement validate the frame and create
             * the portal surface. Directly writing NETHER_PORTAL blocks looks
             * identical but does not exercise the complete portal creation
             * lifecycle used by normal flint-and-steel ignition.
             */
            level.setBlockAndUpdate(
                    interior,
                    Blocks.FIRE.defaultBlockState()
            );
        }

        private static void setUnlessPortal(
                final ServerPlayer body,
                final BlockPos position,
                final net.minecraft.world.level.block.state.BlockState state
        ) {
            final var existing = body.level().getBlockState(position);
            if (!existing.is(Blocks.NETHER_PORTAL)
                    && !existing.is(Blocks.OBSIDIAN)) {
                body.level().setBlockAndUpdate(position, state);
            }
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

        private void setGenerateStructures(final boolean enabled) {
            final var settings = runtime.server()
                    .getWorldGenSettings();
            ((WorldGenSettingsAccessor) (Object) settings)
                    .mcai$setOptions(
                            settings.options()
                                .withStructures(enabled)
                    );
        }

        private String diagnostics() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElse(null);
            return "stage=" + stage
                    + ", supervisor="
                    + runtime.skillSupervisor().snapshot()
                    + ", goal=" + runtime.goals().snapshot()
                    + ", body="
                    + (body == null
                        ? "absent"
                        : body.level().dimension().identifier()
                            + "@" + body.position())
                    + ", powder="
                    + (body == null
                        ? -1
                        : body.getInventory().countItem(
                            Items.BLAZE_POWDER
                        ))
                    + ", pearls="
                    + (body == null
                        ? -1
                        : body.getInventory().countItem(
                            Items.ENDER_PEARL
                        ))
                    + ", eyes="
                    + (body == null
                        ? -1
                        : body.getInventory().countItem(
                            Items.ENDER_EYE
                        ))
                    + ", netherArrival=" + netherArrival
                    + ", expectedNetherPortal="
                    + netherPortalInterior
                    + ", flags=[craftSkill=" + craftSkillObserved
                    + ",eyeCraft=" + eyeCraftObserved
                    + ",returnSkill=" + returnSkillObserved
                    + ",overworld=" + overworldReturnObserved
                    + ",triangulation="
                    + triangulationSkillObserved
                    + ",triangulationValidated="
                    + triangulationHandoffValidated
                    + ",reach=" + reachSkillObserved
                    + ",strongholdValidated="
                    + strongholdHandoffValidated
                    + ",portalSearch="
                    + portalRoomSearchObserved
                    + ",activation=" + activationSkillObserved
                    + ",endEntry=" + endEntrySkillObserved
                    + ",fight=" + fightSkillObserved
                    + ",endReturn=" + endReturnSkillObserved
                    + ",arena=" + endArenaPrepared + "]"
                    + ", strongholdTarget=" + strongholdTarget
                    + ", strongholdEvidence=" + strongholdEvidence
                    + ", endPortalCenter=" + endPortalCenter
                    + ", endPortalBlocks="
                    + activeEndPortalBlocks()
                    + ", activationEyesBefore="
                    + activationEyeCountBefore
                    + ", activationFilledBefore="
                    + activationFilledFramesBefore
                    + ", activationEyesConsumed="
                    + (body == null
                        || activationEyeCountBefore < 0
                        ? -1
                        : activationEyeCountBefore
                            - body.getInventory().countItem(
                                Items.ENDER_EYE
                            ))
                    + ", dragonHealth="
                    + (victoryArena == null
                        ? "none"
                        : victoryArena.dragon().getHealth())
                    + ", dragonParts="
                    + (victoryArena == null
                        ? "none"
                        : java.util.Arrays.stream(
                                victoryArena.dragon().getSubEntities()
                            )
                            .map(part -> part.name + "@" + part.position())
                            .toList())
                    + ", perception="
                    + runtime.coreFrames().current()
                        .map(frame -> "visibleEntities="
                            + frame.visibleEntities()
                            + ", dangers=" + frame.dangerSignals()
                            + ", revision=" + frame.observationRevision())
                        .orElse("unavailable")
                    + ", reachStart=" + strongholdReachStart
                    + ", pickaxeDamage="
                    + (body == null
                        ? -1
                        : itemDamage(body, Items.IRON_PICKAXE))
                    + ", torches="
                    + (body == null
                        ? -1
                        : body.getInventory().countItem(Items.TORCH))
                    + ", milestones="
                    + (goalRevision < 0L
                        ? List.of()
                        : runtime.worldData()
                            .verifiedRouteProgress(goalRevision)
                            .milestones())
                    + ", eyeHistory="
                    + (goalRevision < 0L
                        ? Optional.empty()
                        : runtime.eyeTraceResults()
                            .snapshot(goalRevision));
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
                humanSession = null;
            }
            setNaturalSpawning(
                    originalSpawnMobs,
                    originalSpawnMonsters
            );
            setGenerateStructures(originalGenerateStructures);
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum EyeReturnStage {
        BODY,
        ENTERING_NETHER,
        NETHER_READY,
        GOAL,
        AUTONOMOUS_CHAIN,
        DONE
    }

    /**
     * Release-excluded causal gate for the stronghold-interior completion
     * phase. Test setup owns only the already-discovered stronghold maze,
     * late-route inventory, and prior route checkpoint. The entry-only
     * variant performs no fixture mutation after the checkpoint. The victory
     * variant creates its deterministic release-excluded End combat arena
     * only after the body has genuinely crossed the activated portal; that
     * setup supplies targets but never selects a model skill, credits damage,
     * kills the dragon, or moves the body through a portal.
     */
    private static final class LiveStrongholdPortalRoomScenario {
        private static final long SEARCH_TIMEOUT_NANOS =
                java.time.Duration.ofMinutes(6).toNanos();
        private static final long ACTIVATION_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(60).toNanos();
        private static final long ENTRY_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(150).toNanos();
        private static final long END_SETTLE_TICKS = 80L;
        private static final long FIGHT_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(150).toNanos();
        private static final long RETURN_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(150).toNanos();

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;
        private final boolean originalSpawnMobs;
        private final boolean originalSpawnMonsters;
        private final boolean requireVictory;

        private StrongholdPortalRoomStage stage =
                StrongholdPortalRoomStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private EndVictoryArena victoryArena;
        private UUID bodyId;
        private BlockPos searchStart;
        private BlockPos portalCenter;
        private BlockPos mazeDeadEnd;
        private BlockPos mazeSecondTurn;
        private Vec3 physicalSearchStart;
        private long minimumFixtureObservationRevision;
        private long goalRevisionBefore = -1L;
        private long goalRevision = -1L;
        private long enteredEndAt = -1L;
        private long controlledRallyMarkedAt = -1L;
        private long stageStartedNanos;
        private boolean priorRouteCheckpointInstalled;
        private boolean searchSkillObserved;
        private boolean deadEndVisited;
        private boolean secondTurnVisited;
        private boolean activationSkillObserved;
        private boolean entrySkillObserved;
        private boolean fightSkillObserved;
        private boolean returnSkillObserved;
        private boolean cleaned;

        private LiveStrongholdPortalRoomScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final boolean requireVictory
        ) {
            this.helper = helper;
            this.runtime = runtime;
            this.requireVictory = requireVictory;
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
            originalSpawnMobs = helper.getLevel()
                    .getGameRules()
                    .get(GameRules.SPAWN_MOBS);
            originalSpawnMonsters = helper.getLevel()
                    .getGameRules()
                    .get(GameRules.SPAWN_MONSTERS);
        }

        private void start() {
            finishScenarioGoal(runtime);
            setNaturalSpawning(false, false);
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "Stronghold portal-room companion spawn was "
                            + "rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case INITIAL_FRAME -> waitForInitialFrame();
                case GOAL -> waitForGoal();
                case SEARCH_SKILL -> waitForSearchSkill();
                case SEARCH_AND_ACTIVATION ->
                        waitForSearchAndActivationHandoff();
                case ACTIVATE -> waitForActivation();
                case ENTRY_SKILL -> waitForEntrySkill();
                case ENTER -> waitForEntry();
                case SETTLING_END -> waitForEndSettle();
                case VICTORY_VISIBLE -> waitForVictoryVisible();
                case FIGHT_SKILL -> waitForFightSkill();
                case FIGHT -> waitForDragonKill();
                case RETURN -> waitForReturn();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "Stronghold portal-room companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "Stronghold portal-room companion body timed out"
                );
                return;
            }
            final ServerPlayer body = body();
            bodyId = body.getUUID();
            prepareStrongholdMaze(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = StrongholdPortalRoomStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithin(
                    MODEL_TIMEOUT_NANOS,
                    "Stronghold portal-room model probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            stage = StrongholdPortalRoomStage.INITIAL_FRAME;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForInitialFrame() {
            assertWithin(
                    MODEL_TIMEOUT_NANOS,
                    "Stronghold maze never became fair first-person "
                        + "evidence: " + diagnostics()
            );
            final ServerPlayer body = body();
            if (!body.onGround()) {
                return;
            }
            final var frame = runtime.coreFrames().current();
            if (frame.isEmpty()
                    || frame.orElseThrow().observationRevision()
                        < minimumFixtureObservationRevision
                    || !frame.orElseThrow().dimension()
                        .equals(DimensionRef.OVERWORLD)
                    || !bodyId.equals(
                        frame.orElseThrow().playerId()
                    )
                    || !hasVisibleBlock(
                        frame.orElseThrow(),
                        "minecraft:stone_bricks"
                    )) {
                return;
            }
            helper.assertTrue(
                    !hasVisibleBlock(
                            frame.orElseThrow(),
                            "minecraft:end_portal_frame"
                    ),
                    "Hidden portal frame leaked into the initial fair "
                        + "first-person frame"
            );
            humanSession = PlacedHuman.create(
                    helper,
                    runtime,
                    /*
                     * SafeCompanionSpawnLocator examines the anchor ring in
                     * a deterministic (-1,-1) first position.  The fixture
                     * maze is authored around the body's pre-login block;
                     * placing the human one block forward therefore keeps
                     * the ordinary remove/relogin anchor inside that same
                     * observed corridor instead of moving the body outside
                     * the release-excluded structure.
                     */
                    body.position().add(1.0D, 0.0D, 1.0D)
            );
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in stronghold test player lacked task "
                        + "permission"
            );
            goalRevisionBefore =
                    runtime.goals().snapshot().revision();
            final String request = requireVictory
                    ? runtime.worldData().displayName()
                        + "，请继续通关Minecraft：在这个要塞中找到"
                        + "末地传送门房间，放入末影之眼激活并进入"
                        + "末地，击败末影龙，然后进入中央返回"
                        + "传送门回到主世界。"
                    : runtime.worldData().displayName()
                        + "，请继续通关Minecraft：在这个要塞中找到"
                        + "末地传送门房间，放入末影之眼激活传送门，"
                        + "然后进入末地。";
            final Component submitted =
                    ForgeHooks.onServerChatSubmittedEvent(
                            human,
                            Component.literal(request)
                    );
            helper.assertTrue(
                    submitted != null,
                    "Companion cancelled the stronghold portal-room "
                        + "chat command"
            );
            stage = StrongholdPortalRoomStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            /*
             * The production login path may need one remove-and-relogin
             * cycle when the server spawned the body before any human was
             * online.  Keep the real chat sender connected until that
             * bounded initial anchor is committed; closing it in the same
             * tick would cancel the pending anchor and make the body
             * disappear for a reason unrelated to the model or the route.
             */
            if (humanSession != null) {
                if (runtime.worldData().bodyNeedsInitialAnchor()) {
                    assertWithin(
                            MODEL_TIMEOUT_NANOS,
                            "Initial human anchor did not settle: "
                                + diagnostics()
                    );
                    return;
                }
                humanSession.close();
                humanSession = null;
            }
            assertNoHumanPlayers();
            assertWithin(
                    MODEL_TIMEOUT_NANOS,
                    "Live model did not classify the stronghold "
                        + "portal-room task: " + diagnostics()
            );
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.status() == GoalStatus.RUNNING
                        && goal.goal().contains("通关Minecraft")
                        && goal.goal().contains("末地传送门"),
                    "Stronghold chat did not become the requested "
                        + "completion goal: " + goal
            );
            installPriorRouteCheckpoint(goal);
            goalRevision = goal.revision();
            physicalSearchStart = body().position();
            stage = StrongholdPortalRoomStage.SEARCH_SKILL;
            stageStartedNanos = System.nanoTime();
        }

        private void installPriorRouteCheckpoint(
                final GoalSnapshot goal
        ) {
            final EnumSet<SurvivalMilestone> checkpoint = EnumSet.of(
                    SurvivalMilestone.BODY_ACTIVE,
                    SurvivalMilestone.WOOD_OBTAINED,
                    SurvivalMilestone.BASIC_CRAFTING_READY,
                    SurvivalMilestone.STONE_TOOL_OBTAINED,
                    SurvivalMilestone.FOOD_SECURED,
                    SurvivalMilestone.IRON_TOOLKIT_OBTAINED,
                    SurvivalMilestone.NETHER_ENTERED,
                    SurvivalMilestone.BLAZE_MATERIAL_OBTAINED,
                    SurvivalMilestone.ENDER_PEARL_OBTAINED,
                    SurvivalMilestone.EYE_OF_ENDER_CRAFTED,
                    SurvivalMilestone.STRONGHOLD_BEARING_MEASURED,
                    SurvivalMilestone.STRONGHOLD_SEARCH_AREA_TRIANGULATED
            );
            if (requireVictory) {
                /*
                 * This variant is deliberately a dragon-control slice.  Its
                 * natural-ingress proof is a separate gate, so bind the
                 * explicit rally precondition before portal entry; otherwise
                 * the production planner correctly starts reach_end_island
                 * during the first End tick, before this fixture can install
                 * its bounded target arena.
                 */
                checkpoint.add(SurvivalMilestone.END_ISLAND_REACHED);
            }
            runtime.worldData().markVerifiedRouteMilestones(
                    goal.revision(),
                    checkpoint
            );
            priorRouteCheckpointInstalled = true;
        }

        private void waitForSearchSkill() {
            assertNoHumanPlayers();
            observeMazeVisits();
            final SkillSupervisor.Snapshot snapshot =
                    runtime.skillSupervisor().snapshot();
            if ("search_stronghold_portal_room".equals(
                    snapshot.skillName()
            ) && snapshot.boundGoalRevision() == goalRevision) {
                helper.assertTrue(
                        snapshot.state()
                            == SkillSupervisor.State.RUNNING,
                        "Portal-room search became terminal before it "
                            + "was observed running: " + diagnostics()
                );
                searchSkillObserved = true;
                stage =
                        StrongholdPortalRoomStage.SEARCH_AND_ACTIVATION;
                stageStartedNanos = System.nanoTime();
                return;
            }
            if (snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.RUNNING) {
                helper.fail(
                        "Live model selected the wrong stronghold "
                            + "interior skill: " + diagnostics()
                );
                return;
            }
            assertWithin(
                    MODEL_TIMEOUT_NANOS,
                    "Live model did not select "
                        + "search_stronghold_portal_room: "
                        + diagnostics()
            );
        }

        private void waitForSearchAndActivationHandoff() {
            assertNoHumanPlayers();
            final ServerPlayer body = body();
            helper.assertTrue(
                    bodyId.equals(body.getUUID())
                        && body.level().dimension()
                            .equals(Level.OVERWORLD)
                        && body.isAlive()
                        && !body.isDeadOrDying(),
                    "Stronghold search lost the original living "
                        + "Overworld body: " + diagnostics()
            );
            observeMazeVisits();
            final SkillSupervisor.Snapshot snapshot =
                    runtime.skillSupervisor().snapshot();
            if ("search_stronghold_portal_room".equals(
                    snapshot.skillName()
            ) && snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.FAILED) {
                helper.fail(
                        "Physical stronghold portal-room search failed: "
                            + diagnostics()
                );
                return;
            }
            if ("activate_observed_end_portal".equals(
                    snapshot.skillName()
            ) && snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.RUNNING) {
                helper.assertTrue(
                        searchSkillObserved
                            && priorRouteCheckpointInstalled
                            && deadEndVisited
                            && secondTurnVisited
                            && horizontalDistance(
                                body.position(),
                                physicalSearchStart
                            ) >= 8.0D,
                        "Activation handoff lacked physical DFS, "
                            + "dead-end backtracking, or route evidence: "
                            + diagnostics()
                );
                activationSkillObserved = true;
                stage = StrongholdPortalRoomStage.ACTIVATE;
                stageStartedNanos = System.nanoTime();
                return;
            }
            if (snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.RUNNING
                    && !"search_stronghold_portal_room".equals(
                        snapshot.skillName()
                    )) {
                helper.fail(
                        "Live model skipped or replaced the portal-room "
                            + "activation handoff: " + diagnostics()
                );
                return;
            }
            assertWithin(
                    SEARCH_TIMEOUT_NANOS,
                    "Live-model stronghold search/activation handoff "
                        + "timed out: " + diagnostics()
            );
        }

        private void waitForActivation() {
            assertNoHumanPlayers();
            observeMazeVisits();
            final SkillSupervisor.Snapshot snapshot =
                    runtime.skillSupervisor().snapshot();
            if ("activate_observed_end_portal".equals(
                    snapshot.skillName()
            ) && snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.FAILED) {
                helper.fail(
                        "Live-model End portal activation failed: "
                            + diagnostics()
                );
                return;
            }
            if (activePortalBlocks() == 9) {
                final ServerPlayer body = body();
                helper.assertTrue(
                        activationSkillObserved
                            && body.getInventory().countItem(
                                Items.ENDER_EYE
                            ) == 0,
                        "Portal activated without the observed model "
                            + "skill or exact Eye consumption: "
                            + diagnostics()
                );
                stage = StrongholdPortalRoomStage.ENTRY_SKILL;
                stageStartedNanos = System.nanoTime();
                return;
            }
            assertWithin(
                    ACTIVATION_TIMEOUT_NANOS,
                    "Live-model End portal activation timed out: "
                        + diagnostics()
            );
        }

        private void waitForEntrySkill() {
            assertNoHumanPlayers();
            final ServerPlayer body = body();
            helper.assertTrue(
                    bodyId.equals(body.getUUID())
                        && body.level().dimension()
                            .equals(Level.OVERWORLD),
                    "Companion entered the End before the entry skill "
                        + "was observed: " + diagnostics()
            );
            final SkillSupervisor.Snapshot snapshot =
                    runtime.skillSupervisor().snapshot();
            if ("find_and_enter_observed_portal".equals(
                    snapshot.skillName()
            ) && snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.RUNNING) {
                entrySkillObserved = true;
                stage = StrongholdPortalRoomStage.ENTER;
                stageStartedNanos = System.nanoTime();
                return;
            }
            if (snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.RUNNING
                    && !"activate_observed_end_portal".equals(
                        snapshot.skillName()
                    )) {
                helper.fail(
                        "Live model selected the wrong post-activation "
                            + "skill: " + diagnostics()
                );
                return;
            }
            assertWithin(
                    MODEL_TIMEOUT_NANOS,
                    "Live model did not select "
                        + "find_and_enter_observed_portal: "
                        + diagnostics()
            );
        }

        private void waitForEntry() {
            assertNoHumanPlayers();
            final ServerPlayer body = body();
            helper.assertTrue(
                    bodyId.equals(body.getUUID()),
                    "End entry replaced the companion body"
            );
            if (body.level().dimension().equals(Level.END)) {
                helper.assertTrue(
                        priorRouteCheckpointInstalled
                            && searchSkillObserved
                            && deadEndVisited
                            && secondTurnVisited
                            && activationSkillObserved
                            && entrySkillObserved
                            && activePortalBlocks() == 9
                            && body.getInventory().countItem(
                                Items.ENDER_EYE
                            ) == 0,
                        "End entry lacked the complete model/physical "
                            + "causal chain: " + diagnostics()
                );
                if (requireVictory) {
                    /* Move only the focused dragon-control body onto its
                     * explicit central rally before the first post-entry
                     * planner tick.  Otherwise production correctly starts
                     * natural ingress while this release-excluded fixture is
                     * still waiting for vanilla's dragon-fight scan. */
                    prepareControlledCentralRally(
                            runtime,
                            body
                    );
                    enteredEndAt = helper.getTick();
                    /* Let the vanilla EnderDragonFight finish its one-time
                     * legacy-state scan before installing the release-
                     * excluded combat target.  Installing a custom dragon
                     * in the same tick as portal travel makes that scan see
                     * a live dragon without an exit portal and discard the
                     * fixture entity, which is exactly what vanilla does in
                     * a real world. */
                    stage = StrongholdPortalRoomStage.SETTLING_END;
                    stageStartedNanos = System.nanoTime();
                } else {
                    stage = StrongholdPortalRoomStage.DONE;
                    helper.succeed();
                }
                return;
            }
            final SkillSupervisor.Snapshot snapshot =
                    runtime.skillSupervisor().snapshot();
            if ("find_and_enter_observed_portal".equals(
                    snapshot.skillName()
            ) && snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.FAILED) {
                helper.fail(
                        "Live-model End portal entry failed: "
                            + diagnostics()
                );
                return;
            }
            assertWithin(
                    ENTRY_TIMEOUT_NANOS,
                    "Live-model End portal entry timed out: "
                        + diagnostics()
            );
        }

        private static void prepareControlledCentralRally(
                final ServerRuntime runtime,
                final ServerPlayer body
        ) {
            final var end = runtime.server().getLevel(Level.END);
            if (end == null) {
                return;
            }
            final BlockPos entry = body.blockPosition();
            final BlockPos rally = new BlockPos(0, entry.getY(), 0);
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    end.setBlockAndUpdate(
                            rally.offset(x, -1, z),
                            Blocks.OBSIDIAN.defaultBlockState()
                    );
                    for (int y = 0; y <= 2; y++) {
                        end.setBlockAndUpdate(
                                rally.offset(x, y, z),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            end.setBlockAndUpdate(
                    rally.offset(0, -1, 0),
                    Blocks.END_STONE.defaultBlockState()
            );
            end.getChunkAt(rally);
            body.teleportTo(
                    rally.getX() + 0.5D,
                    rally.getY(),
                    rally.getZ() + 0.5D
            );
            body.setDeltaMovement(Vec3.ZERO);
            body.fallDistance = 0.0F;
        }

        private void waitForEndSettle() {
            assertNoHumanPlayers();
            final ServerPlayer body = body();
            helper.assertTrue(
                    bodyId.equals(body.getUUID())
                        && body.level().dimension().equals(Level.END),
                    "Companion left the End during vanilla fight-state "
                        + "settling: " + diagnostics()
            );
            if (helper.getTick() - enteredEndAt < END_SETTLE_TICKS) {
                return;
            }
            victoryArena = prepareEndVictoryArena(
                    helper,
                    runtime,
                    body,
                    requireVictory,
                    requireVictory
            );
            /*
             * This release-excluded method is a focused dragon-control
             * slice, not the natural End ingress gate.  The body was placed
             * on the explicit central rally immediately after real portal
             * entry, and the bounded dragon arena is installed only after
             * the vanilla fight-state scan.  The separate ingress GameTest
             * owns bridge/tower/landfall proof.  Bind this test-only
             * precondition to the goal so production current-pose ingress
             * checks remain unchanged.
             */
            runtime.worldData().markVerifiedRouteMilestones(
                    goalRevision,
                    EnumSet.of(SurvivalMilestone.END_ISLAND_REACHED)
            );
            controlledRallyMarkedAt = helper.getTick();
            stage = StrongholdPortalRoomStage.VICTORY_VISIBLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForVictoryVisible() {
            assertNoHumanPlayers();
            final ServerPlayer body = body();
            helper.assertTrue(
                    bodyId.equals(body.getUUID())
                        && body.level().dimension().equals(Level.END),
                    "Continuous victory body left the End before combat: "
                        + diagnostics()
            );
            /* The test-only rally attestation is persisted on the server
             * thread, while the next model observation is assembled from a
             * later route snapshot.  Do not issue a fight/ingress request
             * against the one-tick stale snapshot: wait until the attestation
             * is visible in the same goal-bound progress record that the
             * planner receives. */
            if (!runtime.worldData()
                    .verifiedRouteProgress(goalRevision)
                    .milestones()
                    .contains(SurvivalMilestone.END_ISLAND_REACHED)) {
                assertWithin(
                        MODEL_TIMEOUT_NANOS,
                        "Controlled End rally attestation was not visible: "
                            + diagnostics()
                );
                return;
            }
            /* Route JSON is assembled on the runtime observation cadence,
             * not synchronously with SavedData writes.  Hold the fixture for
             * two observation windows so the next model request cannot race
             * the attestation and choose ingress on a stale snapshot. */
            if (controlledRallyMarkedAt < 0L
                    || helper.getTick() - controlledRallyMarkedAt < 40L) {
                assertWithin(
                        MODEL_TIMEOUT_NANOS,
                        "Controlled End rally observation window has not "
                            + "settled: " + diagnostics()
                );
                return;
            }
            final var frame = runtime.coreFrames().current();
            if (frame.isEmpty()
                    || !frame.orElseThrow().dimension()
                        .equals(DimensionRef.END)
                    || !frame.orElseThrow().onGround()
                    || frame.orElseThrow().visibleEntities()
                        .stream()
                        .noneMatch(entity ->
                                entity.entityTypeId().equals(
                                    "minecraft:end_crystal"
                                )
                        )) {
                assertWithin(
                        MODEL_TIMEOUT_NANOS,
                        "Continuous victory arena never became fair "
                            + "first-person evidence: " + diagnostics()
                );
                return;
            }
            stage = StrongholdPortalRoomStage.FIGHT_SKILL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForFightSkill() {
            assertNoHumanPlayers();
            final SkillSupervisor.Snapshot snapshot =
                    runtime.skillSupervisor().snapshot();
            if ("fight_ender_dragon".equals(
                    snapshot.skillName()
            ) && snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.RUNNING) {
                fightSkillObserved = true;
                stage = StrongholdPortalRoomStage.FIGHT;
                stageStartedNanos = System.nanoTime();
                return;
            }
            if (snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.RUNNING) {
                helper.fail(
                        "Live model selected the wrong post-entry "
                            + "completion skill: " + diagnostics()
                );
                return;
            }
            assertWithin(
                    MODEL_TIMEOUT_NANOS,
                    "Live model did not select fight_ender_dragon: "
                        + diagnostics()
            );
        }

        private void waitForDragonKill() {
            assertNoHumanPlayers();
            final SkillSupervisor.Snapshot snapshot =
                    runtime.skillSupervisor().snapshot();
            if ("fight_ender_dragon".equals(
                    snapshot.skillName()
            ) && snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.FAILED) {
                helper.fail(
                        "Continuous live-model dragon fight failed: "
                            + diagnostics()
                            + ", dragonDiagnostics="
                            + dragonDiagnostics()
                );
                return;
            }
            final var milestones = runtime.worldData()
                    .verifiedRouteProgress(goalRevision)
                    .milestones();
            if (milestones.contains(
                    SurvivalMilestone.DRAGON_KILLED
            )) {
                final ServerPlayer body = body();
                helper.assertTrue(
                        fightSkillObserved
                            && victoryArena != null
                            && hasPhysicalDragonDamageEvidence(
                                body,
                                victoryArena
                            ),
                        "Dragon milestone lacked physical combat "
                            + "evidence: " + diagnostics()
                );
                if (!victoryArena.dragon().isRemoved()) {
                    victoryArena.dragon().setNoAi(false);
                }
                activateEndReturnPortal(
                        runtime.server().getLevel(Level.END),
                        victoryArena.returnPortalCenter()
                );
                stage = StrongholdPortalRoomStage.RETURN;
                stageStartedNanos = System.nanoTime();
                return;
            }
            assertWithin(
                    FIGHT_TIMEOUT_NANOS,
                    "Continuous live-model dragon fight timed out: "
                        + diagnostics()
                        + ", dragonDiagnostics="
                        + dragonDiagnostics()
            );
        }

        private void waitForReturn() {
            assertNoHumanPlayers();
            final SkillSupervisor.Snapshot snapshot =
                    runtime.skillSupervisor().snapshot();
            if ("find_and_enter_observed_portal".equals(
                    snapshot.skillName()
            ) && snapshot.boundGoalRevision() == goalRevision) {
                returnSkillObserved = true;
            }
            final ServerPlayer body = body();
            final var milestones = runtime.worldData()
                    .verifiedRouteProgress(goalRevision)
                    .milestones();
            if (body.level().dimension().equals(Level.OVERWORLD)
                    && milestones.contains(
                        SurvivalMilestone.RETURNED_FROM_END
                    )) {
                helper.assertTrue(
                        bodyId.equals(body.getUUID())
                            && searchSkillObserved
                            && activationSkillObserved
                            && entrySkillObserved
                            && fightSkillObserved
                            && returnSkillObserved,
                        "Continuous completion lost one body/model "
                            + "handoff: " + diagnostics()
                );
                stage = StrongholdPortalRoomStage.DONE;
                helper.succeed();
                return;
            }
            if ("find_and_enter_observed_portal".equals(
                    snapshot.skillName()
            ) && snapshot.boundGoalRevision() == goalRevision
                    && snapshot.state()
                        == SkillSupervisor.State.FAILED) {
                helper.fail(
                        "Continuous End return skill failed: "
                            + diagnostics()
                );
                return;
            }
            assertWithin(
                    RETURN_TIMEOUT_NANOS,
                    "Continuous live-model End return timed out: "
                        + diagnostics()
            );
        }

        private void prepareStrongholdMaze(
                final ServerPlayer body
        ) {
            searchStart = body.blockPosition();
            portalCenter = searchStart.offset(9, 0, 25);
            mazeDeadEnd = searchStart.offset(0, 0, 12);
            mazeSecondTurn = searchStart.offset(9, 0, 8);
            final var level = helper.getLevel();

            body.setGameMode(GameType.SURVIVAL);
            helper.assertTrue(
                    body.gameMode.getGameModeForPlayer()
                            == GameType.SURVIVAL
                        && !body.getAbilities().instabuild,
                    "Stronghold portal-room fixture did not enter "
                        + "survival mode"
            );

            for (int x = -4; x <= 14; x++) {
                for (int z = -3; z <= 31; z++) {
                    level.setBlockAndUpdate(
                            searchStart.offset(x, -1, z),
                            Blocks.STONE_BRICKS
                                .defaultBlockState()
                    );
                    for (int y = 0; y <= 4; y++) {
                        level.setBlockAndUpdate(
                                searchStart.offset(x, y, z),
                                Blocks.STONE_BRICKS
                                    .defaultBlockState()
                        );
                    }
                }
            }
            for (int z = 0; z <= 12; z++) {
                carveStrongholdInterior(0, z);
            }
            for (int x = 0; x <= 9; x++) {
                carveStrongholdInterior(x, 8);
            }
            for (int z = 8; z <= 22; z++) {
                carveStrongholdInterior(9, z);
            }
            for (int x = 6; x <= 12; x++) {
                for (int z = 21; z <= 29; z++) {
                    carveStrongholdInterior(x, z);
                }
            }
            for (int offset = -1; offset <= 1; offset++) {
                setFrame(
                        portalCenter.offset(offset, 0, -2),
                        Direction.SOUTH
                );
                setFrame(
                        portalCenter.offset(offset, 0, 2),
                        Direction.NORTH
                );
                setFrame(
                        portalCenter.offset(-2, 0, offset),
                        Direction.EAST
                );
                setFrame(
                        portalCenter.offset(2, 0, offset),
                        Direction.WEST
                );
            }

            if (requireVictory) {
                equipEndVictoryBody(body, true);
            } else {
                body.getInventory().clearContent();
                body.getInventory().setItem(
                        0,
                        new ItemStack(Items.ENDER_EYE, 12)
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
                        new ItemStack(Items.COBBLESTONE, 64)
                );
                body.getInventory().setSelectedSlot(0);
                body.setItemSlot(
                        EquipmentSlot.OFFHAND,
                        new ItemStack(Items.SHIELD)
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
            }
            body.inventoryMenu.broadcastChanges();
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.getFoodData().setSaturation(5.0F);
            body.setDeltaMovement(Vec3.ZERO);
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(mazeDeadEnd)
                        .add(0.0D, 0.2D, 0.0D)
            );
            body.setYHeadRot(body.getYRot());
            minimumFixtureObservationRevision =
                    runtime.coreFrames()
                        .current()
                        .map(frame ->
                            frame.observationRevision() + 1L
                        )
                        .orElse(0L);
        }

        private void carveStrongholdInterior(
                final int offsetX,
                final int offsetZ
        ) {
            for (int y = 0; y <= 3; y++) {
                helper.getLevel().setBlockAndUpdate(
                        searchStart.offset(offsetX, y, offsetZ),
                        Blocks.AIR.defaultBlockState()
                );
            }
        }

        private void setFrame(
                final BlockPos position,
                final Direction facing
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
                            false
                        )
            );
        }

        private void observeMazeVisits() {
            final Vec3 position = body().position();
            if (horizontalDistance(
                    position,
                    Vec3.atCenterOf(mazeDeadEnd)
            ) <= 1.35D) {
                deadEndVisited = true;
            }
            if (deadEndVisited
                    && horizontalDistance(
                        position,
                        Vec3.atCenterOf(mazeSecondTurn)
                    ) <= 1.35D) {
                secondTurnVisited = true;
            }
        }

        private int activePortalBlocks() {
            int blocks = 0;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (helper.getLevel().getBlockState(
                            portalCenter.offset(x, 0, z)
                    ).is(Blocks.END_PORTAL)) {
                        blocks++;
                    }
                }
            }
            return blocks;
        }

        private static double horizontalDistance(
                final Vec3 first,
                final Vec3 second
        ) {
            return Math.hypot(
                    first.x() - second.x(),
                    first.z() - second.z()
            );
        }

        private static boolean hasVisibleBlock(
                final dev.mcai.companion.skills.core.CoreSkillFrame
                        frame,
                final String blockTypeId
        ) {
            return frame.visibleBlockFaces()
                    .stream()
                    .anyMatch(face ->
                            face.blockTypeId().equals(blockTypeId)
                    );
        }

        private ServerPlayer body() {
            return AiPlayerManager.onlinePlayer(runtime.server())
                    .orElseThrow(() -> helper.assertionException(
                            "Stronghold portal-room companion body "
                                + "disappeared"
                    ));
        }

        private void assertNoHumanPlayers() {
            final long humans = runtime.server()
                    .getPlayerList()
                    .getPlayers()
                    .stream()
                    .filter(player -> !player.getUUID().equals(
                            runtime.worldData().companionUuid()
                    ))
                    .count();
            helper.assertTrue(
                    humans == 0L,
                    "Stronghold portal-room autonomy retained "
                        + humans + " human player(s)"
            );
        }

        private void assertWithin(
                final long timeoutNanos,
                final String message
        ) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= timeoutNanos,
                    message
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

        private String diagnostics() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElse(null);
            return "stage=" + stage
                    + ", supervisor="
                    + runtime.skillSupervisor().snapshot()
                    + ", goal=" + runtime.goals().snapshot()
                    + ", body="
                    + (body == null
                        ? "absent"
                        : body.level().dimension().identifier()
                            + "@" + body.position())
                    + ", eyes="
                    + (body == null
                        ? -1
                        : body.getInventory().countItem(
                            Items.ENDER_EYE
                        ))
                    + ", portalBlocks=" + activePortalBlocks()
                    + ", flags=[checkpoint="
                    + priorRouteCheckpointInstalled
                    + ",search=" + searchSkillObserved
                    + ",deadEnd=" + deadEndVisited
                    + ",secondTurn=" + secondTurnVisited
                    + ",activation=" + activationSkillObserved
                    + ",entry=" + entrySkillObserved
                    + ",fight=" + fightSkillObserved
                    + ",return=" + returnSkillObserved + "]"
                    + ", start=" + searchStart
                    + ", portalCenter=" + portalCenter
                    + ", enteredEndAt=" + enteredEndAt
                    + ", dragonHealth="
                    + (victoryArena == null
                        ? "none"
                        : victoryArena.dragon().getHealth())
                    + ", milestones="
                    + (goalRevision < 0L
                        ? List.of()
                        : runtime.worldData()
                            .verifiedRouteProgress(goalRevision)
                            .milestones());
        }

        /**
         * Keep the release-excluded dragon gate diagnosable when a real model
         * run loses the target. This reads only the bounded loaded entity
         * window around the actual body and the current fair frame; it is not
         * used by production decisions or by the skill under test.
         */
        private String dragonDiagnostics() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElse(null);
            if (body == null) {
                return "body=absent";
            }
            final var end = runtime.server().getLevel(Level.END);
            if (end == null || body.level() != end) {
                return "bodyLevel=" + body.level().dimension().identifier();
            }
            final List<? extends EnderDragon> loadedDragons = end.getDragons()
                    .stream()
                    .filter(Entity::isAlive)
                    .filter(dragon -> body.distanceToSqr(dragon) <= 64.0D * 64.0D)
                    .toList();
            final String dragonPositions = loadedDragons.stream()
                    .map(dragon -> String.format(
                            Locale.ROOT,
                            "%s@%.1f,%.1f,%.1f/alive=%s/noAi=%s",
                            dragon.getUUID(),
                            dragon.getX(),
                            dragon.getY(),
                            dragon.getZ(),
                            dragon.isAlive(),
                            dragon.isNoAi()
                    ))
                    .toList()
                    .toString();
            final String fixtureDragon = victoryArena == null
                    ? "none"
                    : "uuid=" + victoryArena.dragon().getUUID()
                        + ",removed=" + victoryArena.dragon().isRemoved()
                        + ",level=" + (victoryArena.dragon().level() == null
                            ? "null"
                            : victoryArena.dragon().level().dimension()
                                .identifier());
            final var frame = runtime.coreFrames().current();
            final String visible = frame.map(current -> current
                    .visibleEntities()
                    .stream()
                    .map(entity -> entity.entityTypeId()
                            + "@" + String.format(
                                Locale.ROOT,
                                "%.1f",
                                entity.distance()
                            ))
                    .toList()
                    .toString())
                    .orElse("<no-frame>");
            return "body=" + body.position()
                    + ",bodyChunk=" + body.chunkPosition()
                    + ",requestedView=" + body.requestedViewDistance()
                    + ",trackingView=" + body.getChunkTrackingView()
                    + ",fixtureDragon=" + fixtureDragon
                    + ",loadedDragons=" + dragonPositions
                    + ",visible=" + visible;
        }

        private void cleanup() {
            if (cleaned) {
                return;
            }
            cleaned = true;
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
                humanSession = null;
            }
            setNaturalSpawning(
                    originalSpawnMobs,
                    originalSpawnMonsters
            );
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum StrongholdPortalRoomStage {
        BODY,
        PROBE,
        INITIAL_FRAME,
        GOAL,
        SEARCH_SKILL,
        SEARCH_AND_ACTIVATION,
        ACTIVATE,
        ENTRY_SKILL,
        ENTER,
        SETTLING_END,
        VICTORY_VISIBLE,
        FIGHT_SKILL,
        FIGHT,
        RETURN,
        DONE
    }

    private static final class LiveEndPortalActivationScenario {
        private static final long ACTIVATION_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(45).toNanos();
        private static final long ENTRY_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(120).toNanos();
        private static final long CHAIN_FIGHT_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(150).toNanos();
        private static final long END_SETTLE_TICKS = 80L;
        private static final long CHAIN_RETURN_TIMEOUT_NANOS =
                java.time.Duration.ofSeconds(150).toNanos();

        private final GameTestHelper helper;
        private final ServerRuntime runtime;
        private final long createdAt;
        private final boolean requireEntry;
        private final boolean requireVictory;

        private EndPortalStage stage = EndPortalStage.BODY;
        private CompletableFuture<CapabilityProbeOutcome> probe;
        private PlacedHuman humanSession;
        private BlockPos portalCenter;
        private EndVictoryArena victoryArena;
        private UUID bodyId;
        private long enteredEndAt;
        private long goalRevisionBefore;
        private long activationGoalRevision;
        private long stageStartedNanos;
        private boolean activationSkillObserved;
        private boolean entrySkillObserved;
        private boolean fightSkillObserved;
        private boolean returnSkillObserved;
        private boolean priorRouteCheckpointInstalled;
        private boolean chatSubmitted;

        private LiveEndPortalActivationScenario(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final boolean requireEntry,
                final boolean requireVictory
        ) {
            this.helper = helper;
            this.runtime = runtime;
            this.requireEntry = requireEntry;
            this.requireVictory = requireVictory;
            if (requireVictory && !requireEntry) {
                throw new IllegalArgumentException(
                        "Victory chain requires portal entry"
                );
            }
            createdAt = helper.getTick();
            stageStartedNanos = System.nanoTime();
        }

        private void start() {
            finishScenarioGoal(runtime);
            final var status = AiPlayerManager.status(runtime.server());
            if (status.state() == SessionState.ABSENT) {
                helper.assertTrue(
                        AiPlayerManager.requestSpawn(
                                runtime.server()
                        ).accepted(),
                        "End-portal companion spawn was rejected"
                );
            }
        }

        private void tick() {
            if (humanSession != null) {
                humanSession.tick();
            }
            switch (stage) {
                case BODY -> waitForBody();
                case PROBE -> waitForProbe();
                case VISIBLE -> waitForVisibleRing();
                case GOAL -> waitForGoal();
                case SKILL -> waitForActivationSkill();
                case ACTIVATE -> waitForActivation();
                case ENTRY_SKILL -> waitForEntrySkill();
                case ENTER -> waitForEntry();
                case SETTLING_END -> waitForEndSettle();
                case VICTORY_VISIBLE -> waitForVictoryVisible();
                case FIGHT_SKILL -> waitForChainedFightSkill();
                case FIGHT -> waitForChainedDragonKill();
                case RETURN -> waitForChainedReturn();
                case DONE -> {
                    // GameTest is already terminal.
                }
            }
        }

        private void waitForBody() {
            final var status = AiPlayerManager.status(runtime.server());
            helper.assertTrue(
                    status.state() != SessionState.FAILED,
                    "End-portal companion body failed"
            );
            if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                helper.assertTrue(
                        helper.getTick() - createdAt
                            <= BODY_TIMEOUT_TICKS,
                        "End-portal companion body timed out"
                );
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            bodyId = body.getUUID();
            preparePortalFixture(body);
            probe = probeOrReuseVerifiedModel(runtime);
            stage = EndPortalStage.PROBE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForProbe() {
            assertWithinModelDeadline(
                    "End-portal model capability probe timed out"
            );
            if (!probe.isDone()) {
                return;
            }
            final CapabilityProbeOutcome outcome = probe.join();
            helper.assertTrue(
                    outcome
                        instanceof CapabilityProbeOutcome.Supported,
                    "Configured live model probe failed: " + outcome
            );
            stage = EndPortalStage.VISIBLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForVisibleRing() {
            assertWithinModelDeadline(
                    "End portal ring never became fair first-person evidence"
            );
            final var frame = runtime.coreFrames().current();
            if (frame.isEmpty()
                    || ObservedEndPortalGeometry.uniqueCenter(
                            frame.orElseThrow().visibleBlockFaces()
                    ).filter(center ->
                            center.x() == portalCenter.getX()
                                && center.y()
                                    == portalCenter.getY()
                                && center.z()
                                    == portalCenter.getZ()
                    ).isEmpty()) {
                return;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            humanSession = PlacedHuman.create(
                    helper,
                    runtime,
                    body.position().add(-2.0D, 0.0D, -2.0D)
            );
            final ServerPlayer human = humanSession.player();
            helper.assertTrue(
                    CompanionCommandAccess.mayAdmin(
                            human.createCommandSourceStack()
                    ),
                    "Logged-in End-portal test player lacked task permission"
            );
            goalRevisionBefore = runtime.goals().snapshot().revision();
            /*
             * The first human login may synchronously remove and relogin the
             * unanchored companion. Keep this real chat connection alive and
             * defer submission until the replacement ServerPlayer is visible
             * again. Closing it in the same tick as placeNewPlayer races the
             * authoritative body transaction and produced a misleading
             * Optional.get/"No value present" fixture failure before the
             * model was ever called.
             */
            stage = EndPortalStage.GOAL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForGoal() {
            assertWithinModelDeadline(
                    "Live model did not classify the End-portal task"
            );
            final Optional<ServerPlayer> bodyCandidate =
                    AiPlayerManager.onlinePlayer(runtime.server());
            if (bodyCandidate.isEmpty()) {
                return;
            }
            helper.assertTrue(
                    bodyId.equals(bodyCandidate.orElseThrow().getUUID()),
                    "Initial-anchor relogin changed the companion UUID"
            );
            if (!chatSubmitted) {
                final ServerPlayer human = humanSession.player();
                final String request = requireVictory
                        ? runtime.worldData().displayName()
                            + "，请从眼前的末地传送门继续通关Minecraft："
                            + "放入末影之眼激活并进入末地，"
                            + "击败末影龙，然后进入中央返回传送门"
                            + "回到主世界。"
                        : requireEntry
                        ? runtime.worldData().displayName()
                            + "，请激活你眼前的末地传送门，"
                            + "把背包里的末影之眼放进框架，"
                            + "然后进入传送门前往末地。"
                        : runtime.worldData().displayName()
                            + "，请激活你眼前的末地传送门，"
                            + "把背包里的末影之眼放进框架。";
                final Component submitted =
                        ForgeHooks.onServerChatSubmittedEvent(
                                human,
                                Component.literal(request)
                        );
                helper.assertTrue(
                        submitted != null,
                        "Companion cancelled the End-portal chat command"
                );
                chatSubmitted = true;
                return;
            }
            final GoalSnapshot goal = runtime.goals().snapshot();
            if (goal.revision() == goalRevisionBefore) {
                return;
            }
            helper.assertTrue(
                    goal.revision() > goalRevisionBefore
                        && goal.status() == GoalStatus.RUNNING
                        && goal.goal().contains("末地传送门"),
                    "End-portal chat did not become a running goal: "
                        + goal
            );
            if (requireVictory
                    && !priorRouteCheckpointInstalled) {
                installPriorRouteCheckpoint(goal);
            }
            humanSession.close();
            humanSession = null;
            activationGoalRevision = goal.revision();
            stage = EndPortalStage.SKILL;
            stageStartedNanos = System.nanoTime();
        }

        private void installPriorRouteCheckpoint(
                final GoalSnapshot goal
        ) {
            runtime.worldData().markVerifiedRouteMilestones(
                    goal.revision(),
                    java.util.EnumSet.of(
                            SurvivalMilestone.BODY_ACTIVE,
                            SurvivalMilestone.WOOD_OBTAINED,
                            SurvivalMilestone.BASIC_CRAFTING_READY,
                            SurvivalMilestone.STONE_TOOL_OBTAINED,
                            SurvivalMilestone.FOOD_SECURED,
                            SurvivalMilestone.IRON_TOOLKIT_OBTAINED,
                            SurvivalMilestone.NETHER_ENTERED,
                            SurvivalMilestone.BLAZE_MATERIAL_OBTAINED,
                            SurvivalMilestone.ENDER_PEARL_OBTAINED,
                            SurvivalMilestone.EYE_OF_ENDER_CRAFTED,
                            SurvivalMilestone
                                .STRONGHOLD_BEARING_MEASURED,
                            SurvivalMilestone
                                .STRONGHOLD_SEARCH_AREA_TRIANGULATED
                    )
            );
            priorRouteCheckpointInstalled = true;
        }

        private void waitForActivationSkill() {
            assertNoHumanPlayers();
            assertWithinModelDeadline(
                    "Live model did not select the parameterless "
                        + "activate_observed_end_portal skill; "
                        + diagnostics()
            );
            final var snapshot =
                    runtime.skillSupervisor().snapshot();
            if (!"activate_observed_end_portal".equals(
                    snapshot.skillName()
            )) {
                return;
            }
            helper.assertTrue(
                    snapshot.boundGoalRevision()
                        == activationGoalRevision,
                    "End-portal skill bound the wrong goal revision"
            );
            activationSkillObserved = true;
            stage = EndPortalStage.ACTIVATE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForActivation() {
            assertNoHumanPlayers();
            if (activePortalBlocks() == 9) {
                final ServerPlayer body = AiPlayerManager
                        .onlinePlayer(runtime.server())
                        .orElseThrow();
                helper.assertTrue(
                        activationSkillObserved
                            && body.getInventory().countItem(
                                Items.ENDER_EYE
                            ) == 0,
                        "Portal activated without the observed live-model "
                            + "skill or exact Eye consumption: "
                            + diagnostics()
                );
                if (requireEntry) {
                    stage = EndPortalStage.ENTRY_SKILL;
                    stageStartedNanos = System.nanoTime();
                } else {
                    stage = EndPortalStage.DONE;
                    helper.succeed();
                }
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= ACTIVATION_TIMEOUT_NANOS,
                    "Live-model End portal activation timed out: "
                        + diagnostics()
            );
        }

        private void waitForEntrySkill() {
            assertNoHumanPlayers();
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            helper.assertTrue(
                    bodyId.equals(body.getUUID()),
                    "End portal handoff replaced the companion body"
            );
            helper.assertTrue(
                    body.level().dimension().equals(Level.OVERWORLD),
                    "Companion entered the End before the entry skill "
                        + "was observed: " + diagnostics()
            );
            final var snapshot =
                    runtime.skillSupervisor().snapshot();
            if (!"find_and_enter_observed_portal".equals(
                    snapshot.skillName()
            )) {
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= MODEL_TIMEOUT_NANOS,
                        "Live model did not select the parameterless "
                            + "portal finder after activation: "
                            + diagnostics()
                );
                return;
            }
            helper.assertTrue(
                    snapshot.boundGoalRevision()
                        == activationGoalRevision,
                    "Portal entry skill bound the wrong goal revision"
            );
            entrySkillObserved = true;
            stage = EndPortalStage.ENTER;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForEntry() {
            assertNoHumanPlayers();
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            helper.assertTrue(
                    bodyId.equals(body.getUUID()),
                    "End entry did not preserve the companion UUID"
            );
            if (body.level().dimension().equals(Level.END)) {
                helper.assertTrue(
                        entrySkillObserved
                            && activePortalBlocks() == 9
                            && body.getInventory().countItem(
                                Items.ENDER_EYE
                            ) == 0,
                        "End entry lacked activation/entry causal evidence: "
                            + diagnostics()
                );
                if (requireVictory) {
                    enteredEndAt = helper.getTick();
                    stage = EndPortalStage.SETTLING_END;
                    stageStartedNanos = System.nanoTime();
                } else {
                    stage = EndPortalStage.DONE;
                    helper.succeed();
                }
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= ENTRY_TIMEOUT_NANOS,
                    "Live-model End portal entry timed out: "
                        + diagnostics()
            );
        }

        private void waitForEndSettle() {
            assertNoHumanPlayers();
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            helper.assertTrue(
                    bodyId.equals(body.getUUID())
                        && body.level().dimension().equals(Level.END),
                    "Late completion body left the End during vanilla "
                        + "fight-state settling: " + diagnostics()
            );
            if (helper.getTick() - enteredEndAt < END_SETTLE_TICKS) {
                return;
            }
            victoryArena = prepareEndVictoryArena(
                    helper,
                    runtime,
                    body,
                    false,
                    false
            );
            stage = EndPortalStage.VICTORY_VISIBLE;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForVictoryVisible() {
            assertNoHumanPlayers();
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            helper.assertTrue(
                    body.level().dimension().equals(Level.END),
                    "Late completion body left the End before combat"
            );
            final var frame = runtime.coreFrames().current();
            if (frame.isEmpty()
                    || !frame.orElseThrow().dimension().equals(
                            dev.mcai.companion.waypoint
                                .DimensionRef.END
                    )
                    || !frame.orElseThrow().onGround()
                    || frame.orElseThrow().visibleEntities()
                            .stream()
                            .noneMatch(entity ->
                                    entity.entityTypeId().equals(
                                            "minecraft:end_crystal"
                                    )
                            )) {
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= MODEL_TIMEOUT_NANOS,
                        "Late completion dragon arena never became fair "
                            + "first-person evidence: " + diagnostics()
                );
                return;
            }
            stage = EndPortalStage.FIGHT_SKILL;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForChainedFightSkill() {
            assertNoHumanPlayers();
            final var snapshot =
                    runtime.skillSupervisor().snapshot();
            if (!"fight_ender_dragon".equals(
                    snapshot.skillName()
            )) {
                helper.assertTrue(
                        System.nanoTime() - stageStartedNanos
                            <= MODEL_TIMEOUT_NANOS,
                        "Late completion model did not select "
                            + "fight_ender_dragon: " + diagnostics()
                );
                return;
            }
            helper.assertTrue(
                    snapshot.boundGoalRevision()
                        == activationGoalRevision,
                    "Chained dragon skill bound the wrong goal revision"
            );
            fightSkillObserved = true;
            stage = EndPortalStage.FIGHT;
            stageStartedNanos = System.nanoTime();
        }

        private void waitForChainedDragonKill() {
            assertNoHumanPlayers();
            final var milestones = runtime.worldData()
                    .verifiedRouteProgress(
                            activationGoalRevision
                    )
                    .milestones();
            if (milestones.contains(
                    SurvivalMilestone.DRAGON_KILLED
            )) {
                final ServerPlayer body = AiPlayerManager
                        .onlinePlayer(runtime.server())
                        .orElseThrow();
                helper.assertTrue(
                        fightSkillObserved
                            && victoryArena != null
                            && hasPhysicalDragonDamageEvidence(
                                body,
                                victoryArena
                            ),
                        "Chained dragon milestone lacked physical combat "
                            + "evidence: " + diagnostics()
                );
                if (!victoryArena.dragon().isRemoved()) {
                    victoryArena.dragon().setNoAi(false);
                }
                activateEndReturnPortal(
                        runtime.server().getLevel(Level.END),
                        victoryArena.returnPortalCenter()
                );
                stage = EndPortalStage.RETURN;
                stageStartedNanos = System.nanoTime();
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= CHAIN_FIGHT_TIMEOUT_NANOS,
                    "Late completion dragon fight timed out: "
                        + diagnostics()
            );
        }

        private void waitForChainedReturn() {
            assertNoHumanPlayers();
            final var snapshot =
                    runtime.skillSupervisor().snapshot();
            if ("find_and_enter_observed_portal".equals(
                    snapshot.skillName()
            ) && snapshot.boundGoalRevision()
                    == activationGoalRevision) {
                returnSkillObserved = true;
            }
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            final var milestones = runtime.worldData()
                    .verifiedRouteProgress(
                            activationGoalRevision
                    )
                    .milestones();
            if (body.level().dimension().equals(Level.OVERWORLD)
                    && milestones.contains(
                        SurvivalMilestone.RETURNED_FROM_END
                    )) {
                helper.assertTrue(
                        priorRouteCheckpointInstalled
                            && activationSkillObserved
                            && entrySkillObserved
                            && fightSkillObserved
                            && returnSkillObserved
                            && bodyId.equals(body.getUUID()),
                        "Late completion chain lost one causal handoff: "
                            + diagnostics()
                );
                stage = EndPortalStage.DONE;
                helper.succeed();
                return;
            }
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= CHAIN_RETURN_TIMEOUT_NANOS,
                    "Late completion End return timed out: "
                        + diagnostics()
            );
        }

        private void preparePortalFixture(final ServerPlayer body) {
            final BlockPos feet = body.blockPosition();
            portalCenter = feet.offset(0, 0, 3);
            body.setGameMode(GameType.SURVIVAL);
            helper.assertTrue(
                    body.gameMode.getGameModeForPlayer()
                            == GameType.SURVIVAL
                        && !body.getAbilities().instabuild,
                    "Live End-portal fixture did not enter survival mode"
            );
            for (int x = -6; x <= 6; x++) {
                for (int z = -5; z <= 9; z++) {
                    final BlockPos floor =
                            feet.offset(x, -1, z);
                    helper.getLevel().setBlockAndUpdate(
                            floor,
                            Blocks.STONE_BRICKS.defaultBlockState()
                    );
                    for (int y = 1; y <= 4; y++) {
                        helper.getLevel().setBlockAndUpdate(
                                floor.above(y),
                                Blocks.AIR.defaultBlockState()
                        );
                    }
                }
            }
            for (int offset = -1; offset <= 1; offset++) {
                setFrame(
                        portalCenter.offset(offset, 0, -2),
                        Direction.SOUTH
                );
                setFrame(
                        portalCenter.offset(offset, 0, 2),
                        Direction.NORTH
                );
                setFrame(
                        portalCenter.offset(-2, 0, offset),
                        Direction.EAST
                );
                setFrame(
                        portalCenter.offset(2, 0, offset),
                        Direction.WEST
                );
            }
            if (requireVictory) {
                equipEndVictoryBody(body, true);
            } else {
                body.getInventory().clearContent();
                body.getInventory().setItem(
                        0,
                        new ItemStack(Items.ENDER_EYE, 12)
                );
                /*
                 * This handoff represents a late completion-route body, not
                 * a naked portal laboratory. Natural slime-chunk spawns stay
                 * enabled while the provider makes the second decision.
                 */
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
                        new ItemStack(Items.COBBLESTONE, 64)
                );
                body.getInventory().setSelectedSlot(0);
                body.setItemSlot(
                        EquipmentSlot.OFFHAND,
                        new ItemStack(Items.SHIELD)
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
            }
            body.inventoryMenu.broadcastChanges();
            body.setHealth(body.getMaxHealth());
            body.getFoodData().setFoodLevel(20);
            body.setDeltaMovement(Vec3.ZERO);
            body.lookAt(
                    EntityAnchorArgument.Anchor.EYES,
                    Vec3.atCenterOf(portalCenter)
                        .add(0.0D, -0.15D, 0.0D)
            );
            body.setYHeadRot(body.getYRot());
        }

        private void setFrame(
                final BlockPos position,
                final Direction facing
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
                            false
                        )
            );
        }

        private int activePortalBlocks() {
            int blocks = 0;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (helper.getLevel().getBlockState(
                            portalCenter.offset(x, 0, z)
                    ).is(Blocks.END_PORTAL)) {
                        blocks++;
                    }
                }
            }
            return blocks;
        }

        private void assertNoHumanPlayers() {
            final long humans = runtime.server()
                    .getPlayerList()
                    .getPlayers()
                    .stream()
                    .filter(player -> !player.getUUID().equals(
                            runtime.worldData().companionUuid()
                    ))
                    .count();
            helper.assertTrue(
                    humans == 0L,
                    "End-portal autonomy retained " + humans
                        + " human player(s) after the command"
            );
        }

        private void assertWithinModelDeadline(final String message) {
            helper.assertTrue(
                    System.nanoTime() - stageStartedNanos
                        <= MODEL_TIMEOUT_NANOS,
                    message
            );
        }

        private String diagnostics() {
            final ServerPlayer body = AiPlayerManager
                    .onlinePlayer(runtime.server())
                    .orElseThrow();
            return "supervisor=" + runtime.skillSupervisor().snapshot()
                    + ", goal=" + runtime.goals().snapshot()
                    + ", dimension="
                    + body.level().dimension().identifier()
                    + ", body=" + body.position()
                    + ", eyes="
                    + body.getInventory().countItem(Items.ENDER_EYE)
                    + ", portalBlocks=" + activePortalBlocks()
                    + ", activationSkillObserved="
                    + activationSkillObserved
                    + ", entrySkillObserved="
                    + entrySkillObserved
                    + ", fightSkillObserved="
                    + fightSkillObserved
                    + ", returnSkillObserved="
                    + returnSkillObserved
                    + ", enteredEndAt=" + enteredEndAt
                    + ", milestones="
                    + runtime.worldData()
                        .verifiedRouteProgress(
                                Math.max(
                                        0L,
                                        activationGoalRevision
                                )
                        ).milestones()
                    + ", dragonHealth="
                    + (victoryArena == null
                        ? "none"
                        : victoryArena.dragon().getHealth());
        }

        private void cleanup() {
            finishScenarioGoal(runtime);
            if (humanSession != null) {
                humanSession.close();
            }
            if (AiPlayerManager.status(runtime.server()).state()
                    != SessionState.ABSENT) {
                AiPlayerManager.requestRemove(runtime.server());
            }
        }
    }

    private enum EndPortalStage {
        BODY,
        PROBE,
        VISIBLE,
        GOAL,
        SKILL,
        ACTIVATE,
        ENTRY_SKILL,
        ENTER,
        SETTLING_END,
        VICTORY_VISIBLE,
        FIGHT_SKILL,
        FIGHT,
        RETURN,
        DONE
    }

    /**
     * A real, logged-in ServerPlayer used only by the opt-in live-model
     * GameTest. Forge's convenient mock uses the intentionally invalid name
     * {@code test-mock-player} and is not present in PlayerList, so it cannot
     * exercise the same permission path as a real chat sender.
     */
    private static final class PlacedHuman implements AutoCloseable {
        private final ServerRuntime runtime;
        private final NameAndId identity;
        private final Connection connection;
        private final EmbeddedChannel channel;
        private final ServerGamePacketListenerImpl listener;
        private final ServerPlayer player;
        private long keepAlivePackets;
        private long keepAliveAccepted;
        private boolean closed;

        private PlacedHuman(
                final ServerRuntime runtime,
                final NameAndId identity,
                final Connection connection,
                final EmbeddedChannel channel,
                final ServerGamePacketListenerImpl listener,
                final ServerPlayer player
        ) {
            this.runtime = runtime;
            this.identity = identity;
            this.connection = connection;
            this.channel = channel;
            this.listener = listener;
            this.player = player;
        }

        static PlacedHuman create(
                final GameTestHelper helper,
                final ServerRuntime runtime
        ) {
            return create(helper, runtime, null);
        }

        /**
         * Creates the physical player at the supplied login position before
         * {@link net.minecraft.server.players.PlayerList#placeNewPlayer}.
         * This preserves the production ordering used by the Forge
         * PlayerLoggedInEvent and lets login-triggered systems observe the
         * same position that vanilla publishes to other players.
         */
        static PlacedHuman create(
                final GameTestHelper helper,
                final ServerRuntime runtime,
                final Vec3 loginPosition
        ) {
            final GameProfile profile = new GameProfile(
                    UUID.randomUUID(),
                    "TestHuman"
            );
            final NameAndId identity = new NameAndId(profile);
            final CommonListenerCookie cookie =
                    CommonListenerCookie.createInitial(profile, false);
            final ServerPlayer player = new ServerPlayer(
                    runtime.server(),
                    helper.getLevel(),
                    profile,
                    cookie.clientInformation()
            );
            final Connection connection =
                    new Connection(PacketFlow.SERVERBOUND);
            if (loginPosition != null) {
                player.setPos(
                        loginPosition.x(),
                        loginPosition.y(),
                        loginPosition.z()
                );
            }
            final EmbeddedChannel channel =
                    new EmbeddedChannel(connection);
            runtime.server().getPlayerList().placeNewPlayer(
                    connection,
                    player,
                    cookie
            );
            /*
             * PlayerList.placeNewPlayer is the authoritative vanilla login
             * path and installs a fresh ServerGamePacketListenerImpl. Do not
             * construct a second listener before it: acknowledgements sent
             * to that detached instance cannot clear the installed
             * listener's keepalive challenge and cause a deterministic
             * 15-second timeout.
             */
            final ServerGamePacketListenerImpl listener =
                    player.connection;
            if (listener == null) {
                throw new IllegalStateException(
                        "Vanilla login did not install a game listener"
                );
            }
            player.setGameMode(GameType.SURVIVAL);
            runtime.server().getPlayerList().op(
                    identity,
                    Optional.of(LevelBasedPermissionSet.OWNER),
                    Optional.empty()
            );
            listener.handleAcceptPlayerLoad(
                    new ServerboundPlayerLoadedPacket()
            );
            return new PlacedHuman(
                    runtime,
                    identity,
                    connection,
                    channel,
                    listener,
                    player
            );
        }

        ServerPlayer player() {
            return player;
        }

        void tick() {
            if (closed || !connection.isConnected()) {
                return;
            }
            connection.tick();
            if (!connection.isConnected()) {
                return;
            }
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            Object packet;
            while ((packet = channel.readOutbound()) != null) {
                try {
                    if (packet
                            instanceof ClientboundKeepAlivePacket keepAlive) {
                        keepAlivePackets++;
                        listener.handleKeepAlive(
                                new ServerboundKeepAlivePacket(
                                        keepAlive.getId()
                                )
                        );
                        if (connection.isConnected()) {
                            keepAliveAccepted++;
                        }
                    } else if (packet
                            instanceof ClientboundPlayerPositionPacket position) {
                        listener.handleAcceptTeleportPacket(
                                new ServerboundAcceptTeleportationPacket(
                                        position.id()
                                )
                        );
                    } else if (packet
                            instanceof ClientboundChunkBatchFinishedPacket) {
                        listener.handleChunkBatchReceived(
                                new ServerboundChunkBatchReceivedPacket(
                                        3.5F
                                )
                        );
                    }
                } finally {
                    ReferenceCountUtil.release(packet);
                }
            }
            channel.runPendingTasks();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            runtime.server().getPlayerList().deop(identity);
            if (connection.isConnected()) {
                connection.disconnect(Component.literal(
                        "Live model GameTest complete"
                ));
            }
            connection.handleDisconnection();
            channel.finishAndReleaseAll();
        }
    }
}
