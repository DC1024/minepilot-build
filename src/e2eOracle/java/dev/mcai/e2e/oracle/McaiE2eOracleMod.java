package dev.mcai.e2e.oracle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Independent, test-only server Oracle for the real-client vertical slice.
 *
 * <p>The Oracle builds a neutral platform and positions participants before
 * the nonce-bearing chat starts the timer. It also creates one ordinary
 * dropped stack before publishing the ready marker. After that point it is
 * read-only: it samples ordinary server state and scores both movement and
 * the later vanilla inventory transaction. It never calls a production AI
 * class or supplies observations to the model.</p>
 */
@Mod(McaiE2eOracleMod.MOD_ID)
public final class McaiE2eOracleMod {
    public static final String MOD_ID = "mcai_e2e_oracle";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();
    private static final Gson COMPACT_GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();
    private static final Pattern SAFE_TOKEN =
            Pattern.compile("[A-Za-z0-9_-]{4,64}");
    private static final Pattern SAFE_PLAYER_NAME =
            Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final BlockPos FIXTURE_ORIGIN =
            new BlockPos(0, 100, 0);
    private static final int PLATFORM_RADIUS = 18;
    private static final long TIMEOUT_TICKS = 3_600L;
    private static final long SECOND_CHAT_TIMEOUT_TICKS = 1_200L;
    private static final long INVENTORY_TIMEOUT_TICKS = 3_600L;
    private static final double MOVEMENT_THRESHOLD = 2.0;
    private static final double ARRIVAL_DISTANCE = 4.0;
    private static final double INVENTORY_MOVEMENT_THRESHOLD = 2.0;
    private static final double MAX_PLAUSIBLE_TICK_STEP = 2.0;
    private static final int FIXTURE_LOG_COUNT = 3;
    private static final String INVENTORY_MARKER_SUFFIX = "-ITEM";
    private static final Vec3 FIXTURE_DROP_POSITION =
            new Vec3(15.5, 101.0, 0.5);

    private final boolean enabled;
    private final Scenario scenario;
    private final String nonce;
    private final String actorName;
    private final String observerName;
    private final String aiName;
    private final Path resultsRoot;
    private final Path resultFile;
    private final EvidenceWriter evidence;
    private final AtomicLong sequence = new AtomicLong();

    private MinecraftServer server;
    private boolean platformBuilt;
    private boolean setupComplete;
    private boolean serverChatReceived;
    private boolean movementPassed;
    private boolean inventoryChatReceived;
    private boolean inventoryPassed;
    private boolean oraclePassed;
    private boolean timedOut;
    private long chatStartTick = -1L;
    private long movementPassedTick = -1L;
    private long inventoryChatStartTick = -1L;
    private Vec3 aiStart;
    private Vec3 previousAi;
    private Vec3 inventoryStart;
    private Vec3 previousInventoryAi;
    private double maxDisplacement;
    private double maxTickStep;
    private double maxInventoryDisplacement;
    private double maxInventoryTickStep;
    private int inventoryInitialLogCount;
    private int inventoryFinalLogCount;
    private boolean vanillaItemPickupObserved;
    private int vanillaItemPickupCount;
    private ItemEntity fixtureDrop;
    private boolean delayedAnchorPrepared;
    private boolean delayedAnchorPassed;
    private long delayedAnchorStartTick = -1L;
    private long delayedAnchorAiLoginTick = -1L;
    private long delayedAnchorHumanLoginTick = -1L;
    private Vec3 delayedAnchorAiBeforeHuman;
    private Vec3 delayedAnchorHumanLoginPosition;

    public McaiE2eOracleMod() {
        enabled = Boolean.getBoolean("mcai.e2e.enabled");
        if (!enabled) {
            nonce = "";
            scenario = Scenario.CHAT_FOLLOW_INVENTORY;
            actorName = "";
            observerName = "";
            aiName = "";
            resultsRoot = Path.of(".").toAbsolutePath();
            resultFile = resultsRoot.resolve(
                    "mcai-e2e-oracle-disabled.json"
            );
            evidence = EvidenceWriter.disabled();
            return;
        }
        nonce = checked(
                requiredProperty("mcai.e2e.nonce", 64),
                SAFE_TOKEN,
                "nonce"
        );
        scenario = Scenario.parse(
                System.getProperty(
                        "mcai.e2e.scenario",
                        Scenario.CHAT_FOLLOW_INVENTORY.id
                )
        );
        actorName = checked(
                requiredProperty("mcai.e2e.actorName", 16),
                SAFE_PLAYER_NAME,
                "Actor name"
        );
        observerName = checked(
                requiredProperty("mcai.e2e.observerName", 16),
                SAFE_PLAYER_NAME,
                "Observer name"
        );
        aiName = checked(
                requiredProperty("mcai.e2e.aiName", 16),
                SAFE_PLAYER_NAME,
                "AI name"
        );
        resultsRoot = validatedResultsRoot(
                requiredProperty("mcai.e2e.resultsRoot", 4_096)
        );
        resultFile = resultsRoot.resolve("oracle-result.json");
        evidence = new EvidenceWriter(
                resultsRoot.resolve("oracle-events.jsonl")
        );

        ServerStartedEvent.BUS.addListener(this::onServerStarted);
        ServerStoppingEvent.BUS.addListener(this::onServerStopping);
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(
                this::onPlayerLogin
        );
        PlayerEvent.ItemPickupEvent.BUS.addListener(
                this::onItemPickup
        );
        ServerChatEvent.BUS.addListener(this::onServerChat);
        TickEvent.ServerTickEvent.Post.BUS.addListener(
                this::onServerTick
        );
        record("oracle_mod_ready", payload -> {
            payload.addProperty("actorName", actorName);
            payload.addProperty("observerName", observerName);
            payload.addProperty("aiName", aiName);
            payload.addProperty("scenario", scenario.id);
        });
    }

    private void onServerStarted(final ServerStartedEvent event) {
        if (!enabled) {
            return;
        }
        server = event.getServer();
        server.setDifficulty(Difficulty.PEACEFUL, true);
        buildPlatform(server.overworld());
        if (scenario == Scenario.DELAYED_FIRST_HUMAN_ANCHOR) {
            /*
             * The production body is allowed to finish its ordinary
             * no-human startup first.  We move the vanilla respawn point
             * only after that body has logged in (see prepareDelayedAnchor),
             * so the first real client naturally joins at a different safe
             * pad and the production initial-anchor reconciliation is the
             * only code that brings the AI beside it.
             */
            record("delayed_anchor_scenario_started", payload -> {
                payload.addProperty("serverTick", server.getTickCount());
                payload.addProperty("teleportUsedAfterStart", false);
            });
        }
        writeResult("RUNNING", "waiting_for_participants");
        record("dedicated_server_started", payload -> {
            payload.addProperty(
                    "dedicated",
                    server.isDedicatedServer()
            );
            payload.addProperty(
                    "serverTick",
                    server.getTickCount()
            );
        });
    }

    private void onPlayerLogin(
            final PlayerEvent.PlayerLoggedInEvent event
    ) {
        if (!enabled
                || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        record("server_player_logged_in", payload -> {
            payload.addProperty(
                    "playerName",
                    player.getGameProfile().name()
            );
            payload.addProperty(
                    "playerUuid",
                    player.getUUID().toString()
            );
            payload.addProperty("serverTick", server.getTickCount());
        });
        if (scenario == Scenario.DELAYED_FIRST_HUMAN_ANCHOR
                && player.getGameProfile().name().equals(aiName)
                && delayedAnchorAiLoginTick < 0L) {
            delayedAnchorAiLoginTick = server.getTickCount();
        }
    }

    /**
     * Record the authoritative post-inventory Forge pickup event for the
     * exact fixture entity. A count increase alone could be produced by a
     * faulty direct-inventory-write implementation, so the external gate
     * requires this event as well.
     */
    private void onItemPickup(
            final PlayerEvent.ItemPickupEvent event
    ) {
        if (!enabled
                || server == null
                || !(event.getEntity() instanceof ServerPlayer player)
                || !player.getGameProfile().name().equals(aiName)
                || fixtureDrop == null
                || !fixtureDrop.getUUID().equals(
                        event.getOriginalEntity().getUUID()
                )
                || event.getStack().isEmpty()
                || !Items.OAK_LOG.equals(event.getStack().getItem())) {
            return;
        }
        vanillaItemPickupObserved = true;
        vanillaItemPickupCount += event.getStack().getCount();
        record("server_vanilla_item_pickup", payload -> {
            payload.addProperty(
                    "entityUuid",
                    event.getOriginalEntity().getUUID().toString()
            );
            payload.addProperty("itemId", "minecraft:oak_log");
            payload.addProperty(
                    "pickedCount",
                    event.getStack().getCount()
            );
            payload.addProperty(
                    "cumulativePickedCount",
                    vanillaItemPickupCount
            );
            payload.addProperty(
                    "path",
                    "forge_player_item_pickup_event"
            );
        });
    }

    private void onServerChat(final ServerChatEvent event) {
        if (!enabled
                || server == null
                || !event.getPlayer().getGameProfile().name()
                        .equals(actorName)
                || !event.getRawText().contains(nonce)) {
            return;
        }
        if (event.getRawText().contains(
                nonce + INVENTORY_MARKER_SUFFIX
        )) {
            acceptInventoryChat(event);
            return;
        }
        if (serverChatReceived) {
            return;
        }
        /*
         * This event is the timer boundary. All fixture mutation is complete
         * before it. From here on this class only samples and scores.
         */
        serverChatReceived = true;
        chatStartTick = Integer.toUnsignedLong(
                server.getTickCount()
        );
        final ServerPlayer ai = player(aiName);
        aiStart = ai == null ? null : ai.position();
        previousAi = aiStart;
        record("server_chat_received", payload -> {
            payload.addProperty(
                    "sender",
                    event.getPlayer().getGameProfile().name()
            );
            payload.addProperty(
                    "senderUuid",
                    event.getPlayer().getUUID().toString()
            );
            payload.addProperty("message", bounded(
                    event.getRawText(),
                    512
            ));
            payload.addProperty("serverTick", chatStartTick);
        });
        writeResult("RUNNING", "chat_received");
    }

    private void acceptInventoryChat(final ServerChatEvent event) {
        if (!serverChatReceived || inventoryChatReceived) {
            return;
        }
        inventoryChatReceived = true;
        inventoryChatStartTick = Integer.toUnsignedLong(
                server.getTickCount()
        );
        final ServerPlayer ai = player(aiName);
        inventoryStart = ai == null ? null : ai.position();
        previousInventoryAi = inventoryStart;
        inventoryInitialLogCount = ai == null
                ? -1
                : ai.getInventory().countItem(Items.OAK_LOG);
        record("inventory_chat_received", payload -> {
            payload.addProperty(
                    "sender",
                    event.getPlayer().getGameProfile().name()
            );
            payload.addProperty(
                    "senderUuid",
                    event.getPlayer().getUUID().toString()
            );
            payload.addProperty("message", bounded(
                    event.getRawText(),
                    512
            ));
            payload.addProperty(
                    "serverTick",
                    inventoryChatStartTick
            );
            payload.addProperty(
                    "initialOakLogCount",
                    inventoryInitialLogCount
            );
            payload.addProperty(
                    "fixtureDropAlive",
                    fixtureDrop != null && fixtureDrop.isAlive()
            );
        });
        writeResult("RUNNING", "inventory_chat_received");
    }

    private void onServerTick(
            final TickEvent.ServerTickEvent.Post event
    ) {
        if (!enabled || server != event.server()) {
            return;
        }
        if (!setupComplete) {
            if (scenario == Scenario.DELAYED_FIRST_HUMAN_ANCHOR) {
                attemptDelayedAnchorSetup();
                return;
            }
            attemptSetup();
            return;
        }
        if (!serverChatReceived || oraclePassed || timedOut) {
            return;
        }
        sampleAndScore();
    }

    /**
     * External lifecycle-only Oracle.  It deliberately starts with no
     * human clients, waits until the production headless ServerPlayer is
     * online, then changes only the vanilla respawn point and builds a safe
     * second pad.  The actor and observer are launched later by the Python
     * orchestrator.  No production player is teleported by this scenario.
     */
    private void attemptDelayedAnchorSetup() {
        final ServerPlayer ai = player(aiName);
        if (ai == null) {
            return;
        }
        if (!delayedAnchorPrepared) {
            delayedAnchorPrepared = true;
            delayedAnchorStartTick = server.getTickCount();
            delayedAnchorAiBeforeHuman = ai.position();
            final BlockPos humanSpawn = FIXTURE_ORIGIN.offset(10, 1, 0);
            buildPad(server.overworld(), humanSpawn.below());
            if (server.getWorldData() instanceof PrimaryLevelData primary) {
                primary.setSpawn(
                        RespawnData.of(
                                Level.OVERWORLD,
                                humanSpawn,
                                90.0F,
                                0.0F
                        )
                );
            } else {
                throw new IllegalStateException(
                        "Dedicated E2E world data cannot set a respawn point"
                );
            }
            record("delayed_anchor_zero_human_active", payload -> {
                payload.addProperty("serverTick", delayedAnchorStartTick);
                payload.addProperty(
                        "aiX",
                        delayedAnchorAiBeforeHuman.x()
                );
                payload.addProperty(
                        "aiY",
                        delayedAnchorAiBeforeHuman.y()
                );
                payload.addProperty(
                        "aiZ",
                        delayedAnchorAiBeforeHuman.z()
                );
                payload.addProperty("humanSpawnX", humanSpawn.getX());
                payload.addProperty("humanSpawnY", humanSpawn.getY());
                payload.addProperty("humanSpawnZ", humanSpawn.getZ());
                payload.addProperty(
                        "zeroHumanTicks",
                        Math.max(
                                0L,
                                server.getTickCount()
                                        - delayedAnchorAiLoginTick
                        )
                );
            });
            writeResult("RUNNING", "waiting_for_delayed_human");
            return;
        }

        final ServerPlayer actor = player(actorName);
        final ServerPlayer observer = player(observerName);
        if (actor == null || observer == null || ai == null) {
            return;
        }
        if (delayedAnchorHumanLoginTick < 0L) {
            delayedAnchorHumanLoginTick = server.getTickCount();
            delayedAnchorHumanLoginPosition = actor.position();
            record("delayed_anchor_human_login_observed", payload -> {
                payload.addProperty(
                        "serverTick",
                        delayedAnchorHumanLoginTick
                );
                payload.addProperty(
                        "x",
                        delayedAnchorHumanLoginPosition.x()
                );
                payload.addProperty(
                        "y",
                        delayedAnchorHumanLoginPosition.y()
                );
                payload.addProperty(
                        "z",
                        delayedAnchorHumanLoginPosition.z()
                );
            });
        }
        final double distance = ai.distanceTo(actor);
        final boolean sameDimension = ai.level() == actor.level();
        final boolean safeFeet = ai.onGround()
                && !ai.isInLava()
                && !ai.isOnFire();
        final boolean stable = delayedAnchorStartTick >= 0L
                && server.getTickCount() - delayedAnchorStartTick >= 10L;
        record("delayed_anchor_world_sample", payload -> {
            payload.addProperty("serverTick", server.getTickCount());
            payload.addProperty("distance", distance);
            payload.addProperty("sameDimension", sameDimension);
            payload.addProperty("safeFeet", safeFeet);
            payload.addProperty("aiX", ai.getX());
            payload.addProperty("aiY", ai.getY());
            payload.addProperty("aiZ", ai.getZ());
            payload.addProperty("actorX", actor.getX());
            payload.addProperty("actorY", actor.getY());
            payload.addProperty("actorZ", actor.getZ());
            payload.addProperty(
                    "teleportUsedAfterStart",
                    false
            );
        });
        if (sameDimension && safeFeet && distance <= 12.0D && stable) {
            delayedAnchorPassed = true;
            setupComplete = true;
            oraclePassed = true;
            record("delayed_anchor_objective_oracle_passed", payload -> {
                payload.addProperty("distance", distance);
                payload.addProperty("zeroHumanTicks", Math.max(
                        0L,
                        delayedAnchorHumanLoginTick
                                - delayedAnchorAiLoginTick
                ));
                payload.addProperty("criterion", "initial_anchor_near_first_human");
            });
            broadcastReady();
            writeResult("PASS", "delayed_initial_anchor_passed");
        }
    }

    private void broadcastReady() {
        server.getPlayerList().broadcastSystemMessage(
                Component.literal(
                        "[[MCAI_E2E_READY:" + nonce + "]]"
                ),
                false
        );
    }

    private void attemptSetup() {
        final ServerPlayer actor = player(actorName);
        final ServerPlayer observer = player(observerName);
        final ServerPlayer ai = player(aiName);
        if (actor == null || observer == null || ai == null) {
            return;
        }
        if (!platformBuilt) {
            buildPlatform(server.overworld());
        }

        /*
         * Test fixture setup. It deliberately finishes before the ready
         * marker and before the Actor sends the command.
         */
        ai.teleportTo(-10.5, 101.0, 0.5);
        ai.setYRot(-90.0F);
        ai.setXRot(0.0F);
        ai.setDeltaMovement(Vec3.ZERO);
        ai.getInventory().clearContent();
        ai.inventoryMenu.broadcastChanges();
        actor.teleportTo(10.5, 101.0, 0.5);
        actor.setYRot(90.0F);
        actor.setXRot(0.0F);
        actor.setDeltaMovement(Vec3.ZERO);
        observer.setGameMode(GameType.SPECTATOR);
        observer.teleportTo(0.5, 108.0, 0.5);
        observer.setYRot(0.0F);
        observer.setXRot(45.0F);

        fixtureDrop = new ItemEntity(
                server.overworld(),
                FIXTURE_DROP_POSITION.x(),
                FIXTURE_DROP_POSITION.y(),
                FIXTURE_DROP_POSITION.z(),
                new ItemStack(Items.OAK_LOG, FIXTURE_LOG_COUNT)
        );
        fixtureDrop.setDeltaMovement(Vec3.ZERO);
        if (!server.overworld().addFreshEntity(fixtureDrop)) {
            timedOut = true;
            writeResult("FAIL", "fixture_item_spawn_failed");
            return;
        }

        setupComplete = true;
        aiStart = ai.position();
        previousAi = aiStart;
        record("fixture_setup_complete", payload -> {
            payload.addProperty("serverTick", server.getTickCount());
            payload.addProperty(
                    "initialAiActorDistance",
                    ai.distanceTo(actor)
            );
            payload.addProperty(
                    "initialOakLogCount",
                    ai.getInventory().countItem(Items.OAK_LOG)
            );
        });
        record("fixture_item_spawned", payload -> {
            payload.addProperty(
                    "entityUuid",
                    fixtureDrop.getUUID().toString()
            );
            payload.addProperty("itemId", "minecraft:oak_log");
            payload.addProperty("count", FIXTURE_LOG_COUNT);
            payload.addProperty("x", fixtureDrop.getX());
            payload.addProperty("y", fixtureDrop.getY());
            payload.addProperty("z", fixtureDrop.getZ());
        });
        server.getPlayerList().broadcastSystemMessage(
                Component.literal(
                        "[[MCAI_E2E_READY:" + nonce + "]]"
                ),
                false
        );
        writeResult("RUNNING", "ready_for_actor_chat");
    }

    private void sampleAndScore() {
        final ServerPlayer actor = player(actorName);
        final ServerPlayer ai = player(aiName);
        if (actor == null || ai == null) {
            return;
        }
        if (!movementPassed) {
            sampleAndScoreMovement(actor, ai);
            return;
        }
        if (!inventoryChatReceived) {
            final long waitingTicks = Integer.toUnsignedLong(
                    server.getTickCount()
            ) - movementPassedTick;
            if (waitingTicks >= SECOND_CHAT_TIMEOUT_TICKS) {
                fail(
                        "inventory_chat_timeout",
                        waitingTicks,
                        payload -> payload.addProperty(
                                "firstAiFollowupRequired",
                                true
                        )
                );
            }
            return;
        }
        sampleAndScoreInventory(ai);
    }

    private void sampleAndScoreMovement(
            final ServerPlayer actor,
            final ServerPlayer ai
    ) {
        final Vec3 current = ai.position();
        if (aiStart == null) {
            aiStart = current;
        }
        if (previousAi != null) {
            maxTickStep = Math.max(
                    maxTickStep,
                    current.distanceTo(previousAi)
            );
        }
        previousAi = current;
        maxDisplacement = Math.max(
                maxDisplacement,
                current.distanceTo(aiStart)
        );
        final double actorDistance = ai.distanceTo(actor);
        final long elapsedTicks = Integer.toUnsignedLong(
                server.getTickCount()
        ) - chatStartTick;

        record("server_world_sample", payload -> {
            payload.addProperty("elapsedTicks", elapsedTicks);
            payload.addProperty("aiX", ai.getX());
            payload.addProperty("aiY", ai.getY());
            payload.addProperty("aiZ", ai.getZ());
            payload.addProperty("aiYaw", ai.getYRot());
            payload.addProperty("aiPitch", ai.getXRot());
            payload.addProperty("aiHealth", ai.getHealth());
            payload.addProperty("aiOnGround", ai.onGround());
            payload.addProperty(
                    "aiSprinting",
                    ai.isSprinting()
            );
            payload.addProperty(
                    "aiCrouching",
                    ai.isShiftKeyDown()
            );
            payload.addProperty("aiSwimming", ai.isSwimming());
            payload.addProperty(
                    "selectedSlot",
                    ai.getInventory().getSelectedSlot()
            );
            payload.addProperty(
                    "mainHand",
                    ai.getMainHandItem().getItem().toString()
            );
            payload.addProperty(
                    "offHand",
                    ai.getOffhandItem().getItem().toString()
            );
            payload.addProperty(
                    "menuType",
                    ai.containerMenu.getClass().getName()
            );
            payload.addProperty(
                    "actorDistance",
                    actorDistance
            );
            payload.addProperty(
                    "maxDisplacement",
                    maxDisplacement
            );
            payload.addProperty("maxTickStep", maxTickStep);
        });

        final boolean moved =
                maxDisplacement >= MOVEMENT_THRESHOLD;
        final boolean arrived =
                actorDistance <= ARRIVAL_DISTANCE;
        final boolean plausible =
                maxTickStep <= MAX_PLAUSIBLE_TICK_STEP;
        if (moved && arrived && plausible) {
            movementPassed = true;
            movementPassedTick = Integer.toUnsignedLong(
                    server.getTickCount()
            );
            record("server_observed_world_delta", payload -> {
                payload.addProperty(
                        "maxDisplacement",
                        maxDisplacement
                );
                payload.addProperty(
                        "finalActorDistance",
                        actorDistance
                );
                payload.addProperty("maxTickStep", maxTickStep);
            });
            record("movement_objective_oracle_passed", payload -> {
                payload.addProperty("elapsedTicks", elapsedTicks);
                payload.addProperty(
                        "criterion",
                        "follow_actor_without_teleport"
                );
            });
            writeResult(
                    "RUNNING",
                    "movement_passed_waiting_for_inventory_chat"
            );
            return;
        }
        if (elapsedTicks >= TIMEOUT_TICKS) {
            fail("movement_timeout", elapsedTicks, payload -> {
                payload.addProperty("moved", moved);
                payload.addProperty("arrived", arrived);
                payload.addProperty("plausible", plausible);
            });
        }
    }

    private void sampleAndScoreInventory(final ServerPlayer ai) {
        final Vec3 current = ai.position();
        if (inventoryStart == null) {
            inventoryStart = current;
        }
        if (previousInventoryAi != null) {
            maxInventoryTickStep = Math.max(
                    maxInventoryTickStep,
                    current.distanceTo(previousInventoryAi)
            );
        }
        previousInventoryAi = current;
        maxInventoryDisplacement = Math.max(
                maxInventoryDisplacement,
                current.distanceTo(inventoryStart)
        );
        inventoryFinalLogCount =
                ai.getInventory().countItem(Items.OAK_LOG);
        final boolean fixtureDropRemoved = fixtureDrop == null
                || fixtureDrop.isRemoved()
                || !fixtureDrop.isAlive();
        final boolean inventoryIncreased =
                inventoryInitialLogCount >= 0
                && inventoryFinalLogCount
                    >= inventoryInitialLogCount + FIXTURE_LOG_COUNT;
        final boolean physicallyApproached =
                maxInventoryDisplacement
                    >= INVENTORY_MOVEMENT_THRESHOLD;
        final boolean plausible =
                maxInventoryTickStep <= MAX_PLAUSIBLE_TICK_STEP;
        final boolean vanillaPickup = vanillaItemPickupObserved
                && vanillaItemPickupCount >= FIXTURE_LOG_COUNT;
        final long elapsedTicks = Integer.toUnsignedLong(
                server.getTickCount()
        ) - inventoryChatStartTick;

        record("server_inventory_sample", payload -> {
            payload.addProperty("elapsedTicks", elapsedTicks);
            payload.addProperty(
                    "initialOakLogCount",
                    inventoryInitialLogCount
            );
            payload.addProperty(
                    "currentOakLogCount",
                    inventoryFinalLogCount
            );
            payload.addProperty(
                    "fixtureDropRemoved",
                    fixtureDropRemoved
            );
            payload.addProperty(
                    "maxInventoryDisplacement",
                    maxInventoryDisplacement
            );
            payload.addProperty(
                    "maxInventoryTickStep",
                    maxInventoryTickStep
            );
            payload.addProperty(
                    "vanillaPickupObserved",
                    vanillaItemPickupObserved
            );
            payload.addProperty(
                    "vanillaPickupCount",
                    vanillaItemPickupCount
            );
        });

        if (fixtureDropRemoved
                && inventoryIncreased
                && physicallyApproached
                && plausible
                && vanillaPickup) {
            inventoryPassed = true;
            oraclePassed = true;
            record("server_observed_inventory_delta", payload -> {
                payload.addProperty(
                        "initialOakLogCount",
                        inventoryInitialLogCount
                );
                payload.addProperty(
                        "finalOakLogCount",
                        inventoryFinalLogCount
                );
                payload.addProperty(
                        "fixtureDropRemoved",
                        true
                );
                payload.addProperty(
                        "maxInventoryDisplacement",
                        maxInventoryDisplacement
                );
                payload.addProperty(
                        "maxInventoryTickStep",
                        maxInventoryTickStep
                );
                payload.addProperty(
                        "vanillaPickupObserved",
                        vanillaItemPickupObserved
                );
                payload.addProperty(
                        "vanillaPickupCount",
                        vanillaItemPickupCount
                );
            });
            record("inventory_transaction_oracle_passed", payload -> {
                payload.addProperty("elapsedTicks", elapsedTicks);
                payload.addProperty(
                        "criterion",
                        "vanilla_item_pickup_into_ai_inventory"
                );
            });
            record("objective_oracle_passed", payload -> {
                payload.addProperty(
                        "criterion",
                        "follow_then_collect_observed_item"
                );
                payload.addProperty("movementPassed", true);
                payload.addProperty("inventoryPassed", true);
            });
            writeResult("PASS", "movement_and_inventory_passed");
            return;
        }
        if (elapsedTicks >= INVENTORY_TIMEOUT_TICKS) {
            fail("inventory_timeout", elapsedTicks, payload -> {
                payload.addProperty(
                        "fixtureDropRemoved",
                        fixtureDropRemoved
                );
                payload.addProperty(
                        "inventoryIncreased",
                        inventoryIncreased
                );
                payload.addProperty(
                        "physicallyApproached",
                        physicallyApproached
                );
                payload.addProperty("plausible", plausible);
                payload.addProperty(
                        "vanillaPickupObserved",
                        vanillaItemPickupObserved
                );
                payload.addProperty(
                        "vanillaPickupCount",
                        vanillaItemPickupCount
                );
            });
        }
    }

    private void fail(
            final String reason,
            final long elapsedTicks,
            final java.util.function.Consumer<JsonObject> fields
    ) {
        timedOut = true;
        record("objective_oracle_failed", payload -> {
            payload.addProperty("reason", reason);
            payload.addProperty("elapsedTicks", elapsedTicks);
            fields.accept(payload);
        });
        writeResult("FAIL", reason);
    }

    private void buildPlatform(final ServerLevel level) {
        if (platformBuilt) {
            return;
        }
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                level.setBlock(
                        FIXTURE_ORIGIN.offset(x, 0, z),
                        Blocks.STONE.defaultBlockState(),
                        3
                );
                for (int y = 1; y <= 4; y++) {
                    level.setBlock(
                            FIXTURE_ORIGIN.offset(x, y, z),
                            Blocks.AIR.defaultBlockState(),
                            3
                    );
                }
            }
        }
        platformBuilt = true;
        record("fixture_platform_built", payload -> {
            payload.addProperty("x", FIXTURE_ORIGIN.getX());
            payload.addProperty("y", FIXTURE_ORIGIN.getY());
            payload.addProperty("z", FIXTURE_ORIGIN.getZ());
            payload.addProperty("radius", PLATFORM_RADIUS);
        });
    }

    private void buildPad(
            final ServerLevel level,
            final BlockPos support
    ) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                final BlockPos floor = support.offset(x, 0, z);
                level.setBlock(
                        floor,
                        Blocks.STONE.defaultBlockState(),
                        3
                );
                for (int y = 1; y <= 3; y++) {
                    level.setBlock(
                            floor.above(y),
                            Blocks.AIR.defaultBlockState(),
                            3
                    );
                }
            }
        }
        record("delayed_anchor_human_spawn_pad_built", payload -> {
            payload.addProperty("x", support.getX());
            payload.addProperty("y", support.getY());
            payload.addProperty("z", support.getZ());
            payload.addProperty("radius", 3);
        });
    }

    private void onServerStopping(
            final ServerStoppingEvent event
    ) {
        if (!enabled || server != event.getServer()) {
            return;
        }
        if (!oraclePassed && !timedOut) {
            writeResult("FAIL", "server_stopped_before_result");
        }
        record("dedicated_server_stopping", payload ->
                payload.addProperty(
                        "serverTick",
                        server.getTickCount()
                )
        );
        evidence.close();
    }

    private ServerPlayer player(final String name) {
        return server == null
                ? null
                : server.getPlayerList().getPlayerByName(name);
    }

    private void record(
            final String type,
            final java.util.function.Consumer<JsonObject> fields
    ) {
        if (!enabled) {
            return;
        }
        final JsonObject event = new JsonObject();
        event.addProperty("schemaVersion", 1);
        event.addProperty("sequence", sequence.incrementAndGet());
        event.addProperty("atUtc", Instant.now().toString());
        event.addProperty("nonce", nonce);
        event.addProperty("type", type);
        fields.accept(event);
        if (!evidence.offer(COMPACT_GSON.toJson(event))) {
            LOGGER.error(
                    "MCAI E2E Oracle evidence queue overflowed"
            );
        }
    }

    private void writeResult(
            final String status,
            final String reason
    ) {
        final JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1);
        result.addProperty("atUtc", Instant.now().toString());
        result.addProperty("nonce", nonce);
        result.addProperty("status", status);
        result.addProperty("reason", reason);
        result.addProperty("scenario", scenario.id);
        result.addProperty("platformBuilt", platformBuilt);
        result.addProperty("setupComplete", setupComplete);
        result.addProperty(
                "serverChatReceived",
                serverChatReceived
        );
        result.addProperty("movementPassed", movementPassed);
        result.addProperty(
                "inventoryChatReceived",
                inventoryChatReceived
        );
        result.addProperty("inventoryPassed", inventoryPassed);
        result.addProperty("maxDisplacement", maxDisplacement);
        result.addProperty("maxTickStep", maxTickStep);
        result.addProperty(
                "maxInventoryDisplacement",
                maxInventoryDisplacement
        );
        result.addProperty(
                "maxInventoryTickStep",
                maxInventoryTickStep
        );
        result.addProperty(
                "initialOakLogCount",
                inventoryInitialLogCount
        );
        result.addProperty(
                "finalOakLogCount",
                inventoryFinalLogCount
        );
        result.addProperty("oraclePassed", oraclePassed);
        result.addProperty("delayedAnchorPassed", delayedAnchorPassed);
        result.addProperty(
                "delayedAnchorDistance",
                delayedAnchorHumanLoginPosition == null || server == null
                        || player(actorName) == null
                        || player(aiName) == null
                        ? -1.0D
                        : player(aiName).distanceTo(player(actorName))
        );
        result.addProperty(
                "delayedAnchorZeroHumanTicks",
                delayedAnchorStartTick < 0L || delayedAnchorAiLoginTick < 0L
                ? -1L
                        : Math.max(
                                0L,
                                delayedAnchorHumanLoginTick
                                        - delayedAnchorAiLoginTick
                        )
        );
        result.addProperty(
                "evidenceDropped",
                evidence.dropped()
        );
        evidence.failure().ifPresent(value ->
                result.addProperty("evidenceFailure", value)
        );
        final Path temporary = resultFile.resolveSibling(
                resultFile.getFileName() + ".tmp"
        );
        try {
            Files.createDirectories(resultsRoot);
            Files.writeString(
                    temporary,
                    GSON.toJson(result) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            try {
                Files.move(
                        temporary,
                        resultFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary,
                        resultFile,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException exception) {
            LOGGER.error(
                    "MCAI E2E Oracle result write failed; "
                            + "path and message are withheld",
                    exception
            );
        }
    }

    private static String bounded(
            final String value,
            final int maximumCodePoints
    ) {
        final String nonNull = Objects.requireNonNull(value, "value");
        final int count = nonNull.codePointCount(0, nonNull.length());
        if (count <= maximumCodePoints) {
            return nonNull;
        }
        return nonNull.substring(
                0,
                nonNull.offsetByCodePoints(0, maximumCodePoints)
        );
    }

    private static Path validatedResultsRoot(final String raw) {
        final Path input = Path.of(raw);
        final Path path = input.toAbsolutePath().normalize();
        if (!input.isAbsolute()
                || path.getNameCount() < 3
                || path.getParent() == null
                || path.equals(path.getRoot())) {
            throw new IllegalArgumentException(
                    "E2E results root must be a narrow absolute directory"
            );
        }
        return path;
    }

    private static String checked(
            final String value,
            final Pattern pattern,
            final String label
    ) {
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid E2E " + label);
        }
        return value;
    }

    private static String requiredProperty(
            final String name,
            final int maximumCharacters
    ) {
        final String value = System.getProperty(name, "");
        if (value.isBlank() || value.length() > maximumCharacters) {
            throw new IllegalArgumentException(
                    "Missing or oversized E2E property " + name
            );
        }
        return value;
    }

    private enum Scenario {
        CHAT_FOLLOW_INVENTORY("chat_follow_inventory"),
        DELAYED_FIRST_HUMAN_ANCHOR("delayed_first_human_anchor");

        private final String id;

        Scenario(final String id) {
            this.id = id;
        }

        private static Scenario parse(final String value) {
            for (Scenario candidate : values()) {
                if (candidate.id.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException(
                    "Unsupported E2E scenario"
            );
        }
    }

    private static final class EvidenceWriter implements AutoCloseable {
        private static final String POISON =
                "__MCAI_E2E_EVIDENCE_STOP__";
        private final Path file;
        private final ArrayBlockingQueue<String> queue;
        private final AtomicLong dropped;
        private final AtomicReference<String> failure;
        private final Thread thread;

        private EvidenceWriter(final Path file) {
            this.file = Objects.requireNonNull(
                    file,
                    "file"
            ).toAbsolutePath().normalize();
            queue = new ArrayBlockingQueue<>(32_768);
            dropped = new AtomicLong();
            failure = new AtomicReference<>();
            thread = Thread.ofPlatform()
                    .daemon(true)
                    .name("mcai-e2e-oracle-evidence")
                    .start(this::run);
        }

        private EvidenceWriter() {
            file = Path.of(".").toAbsolutePath();
            queue = null;
            dropped = new AtomicLong();
            failure = new AtomicReference<>();
            thread = null;
        }

        static EvidenceWriter disabled() {
            return new EvidenceWriter();
        }

        boolean offer(final String json) {
            if (queue == null) {
                return true;
            }
            if (failure.get() != null || !queue.offer(json)) {
                dropped.incrementAndGet();
                return false;
            }
            return true;
        }

        long dropped() {
            return dropped.get();
        }

        java.util.Optional<String> failure() {
            return java.util.Optional.ofNullable(failure.get());
        }

        private void run() {
            try {
                Files.createDirectories(file.getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(
                        file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                )) {
                    int unflushed = 0;
                    while (true) {
                        final String line = queue.poll(
                                250L,
                                TimeUnit.MILLISECONDS
                        );
                        if (line == null) {
                            if (unflushed > 0) {
                                writer.flush();
                                unflushed = 0;
                            }
                            continue;
                        }
                        if (POISON.equals(line)) {
                            String remaining;
                            while ((remaining = queue.poll()) != null) {
                                if (!POISON.equals(remaining)) {
                                    writer.write(remaining);
                                    writer.newLine();
                                }
                            }
                            writer.flush();
                            return;
                        }
                        writer.write(line);
                        writer.newLine();
                        unflushed++;
                        if (unflushed >= 20) {
                            writer.flush();
                            unflushed = 0;
                        }
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure.compareAndSet(
                        null,
                        "InterruptedException"
                );
            } catch (IOException exception) {
                failure.compareAndSet(
                        null,
                        exception.getClass().getSimpleName()
                );
            }
        }

        @Override
        public void close() {
            if (queue == null) {
                return;
            }
            if (!queue.offer(POISON)) {
                dropped.incrementAndGet();
            }
        }
    }
}
