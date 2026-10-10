package dev.mcai.companion.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.CompanionConfig;
import dev.mcai.companion.brain.BrainOrchestrator;
import dev.mcai.companion.brain.BrainPolicy;
import dev.mcai.companion.brain.ObservationRequestStatus;
import dev.mcai.companion.communication.CompanionConversationCoordinator;
import dev.mcai.companion.control.BehaviorArbiter;
import dev.mcai.companion.control.GoalCoordinator;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.control.WorldGoalRevisionStore;
import dev.mcai.companion.control.WorldGoalStateStore;
import dev.mcai.companion.credential.ApiKeyManager;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.memory.MemoryDatabase;
import dev.mcai.companion.memory.MemoryEvent;
import dev.mcai.companion.memory.transport.AsyncVerifiedPortalEdgeRecall;
import dev.mcai.companion.memory.transport.PersistentPortalTraversalObserver;
import dev.mcai.companion.mechanism.AsyncHydratedCropFieldPlanService;
import dev.mcai.companion.mcp.LoopbackMcpServer;
import dev.mcai.companion.mcp.McpCommands;
import dev.mcai.companion.mcp.MinecraftMcpBackend;
import dev.mcai.companion.model.EndpointValidationException;
import dev.mcai.companion.model.EndpointValidator;
import dev.mcai.companion.model.ObservationKind;
import dev.mcai.companion.model.RequestedObservation;
import dev.mcai.companion.modelsetup.ModelSetupModule;
import dev.mcai.companion.modelsetup.ModelProfileStore;
import dev.mcai.companion.progression.FoundationActionAudit;
import dev.mcai.companion.progression.SurvivalMilestone;
import dev.mcai.companion.progression.SurvivalRouteTracker;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skill.SkillRuntimePolicy;
import dev.mcai.companion.skill.SkillSupervisor;
import dev.mcai.companion.skills.building.DynamicShelterSkills;
import dev.mcai.companion.skills.building.OwnedStructureBlockIndex;
import dev.mcai.companion.skills.building.ServerShelterFrameSource;
import dev.mcai.companion.skills.bridging.BridgeSkills;
import dev.mcai.companion.skills.bridging.ServerBridgeMaterialActuator;
import dev.mcai.companion.skills.combat.CombatSkills;
import dev.mcai.companion.skills.core.CoreSkills;
import dev.mcai.companion.skills.core.EmergencySurvivalController;
import dev.mcai.companion.skills.core.InventoryEmergencyEquipmentActuator;
import dev.mcai.companion.skills.core.IdleEquipmentController;
import dev.mcai.companion.skills.core.ServerCoreSkillFrameSource;
import dev.mcai.companion.skills.core.ServerOwnedCoreSkillActuator;
import dev.mcai.companion.skills.core.TravelSkills;
import dev.mcai.companion.skills.end.EndSkills;
import dev.mcai.companion.skills.exploration.ExplorationSkills;
import dev.mcai.companion.skills.farming.FarmingSkills;
import dev.mcai.companion.skills.foundation.FoundationCraftingSkills;
import dev.mcai.companion.skills.gathering.ResourceGatheringSkills;
import dev.mcai.companion.skills.gathering.ServerResourceInventorySource;
import dev.mcai.companion.skills.inventory.InventorySkills;
import dev.mcai.companion.skills.inventory.ServerInventorySkillActuator;
import dev.mcai.companion.skills.interaction.FairInteractionSkills;
import dev.mcai.companion.skills.interaction.InteractionEmergencyMeleeActuator;
import dev.mcai.companion.skills.interaction.ServerInteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.ServerOwnedInteractionSkillActuator;
import dev.mcai.companion.skills.loot.LootSkills;
import dev.mcai.companion.skills.menu.MenuSkills;
import dev.mcai.companion.skills.menu.ServerMenuSkillActuator;
import dev.mcai.companion.skills.menu.ServerMenuSkillFrameSource;
import dev.mcai.companion.skills.memory.MemorySkills;
import dev.mcai.companion.skills.memory.ObservedCurrentPosition;
import dev.mcai.companion.skills.memory.WaypointRecallBuffer;
import dev.mcai.companion.skills.mining.MiningSkills;
import dev.mcai.companion.skills.parkour.ParkourSkills;
import dev.mcai.companion.skills.portal.PortalBuildSkills;
import dev.mcai.companion.skills.portal.PortalSkills;
import dev.mcai.companion.skills.portal.PortalTraversalBuffer;
import dev.mcai.companion.skills.portal.ServerPortalSkillFrameSource;
import dev.mcai.companion.skills.progress.ProgressSkills;
import dev.mcai.companion.skills.sleeping.ServerSleepSkillFrameSource;
import dev.mcai.companion.skills.sleeping.SleepSkills;
import dev.mcai.companion.skills.survey.SurveyResultBuffer;
import dev.mcai.companion.skills.survey.SurveySkills;
import dev.mcai.companion.skills.stronghold.EyeTraceResultBuffer;
import dev.mcai.companion.skills.stronghold.StrongholdSkills;
import dev.mcai.companion.skills.transport.BoatTransportSkills;
import dev.mcai.companion.skills.transport.MinecartTransportSkills;
import dev.mcai.companion.skills.transport.ServerBoatSkillActuator;
import dev.mcai.companion.skills.transport.ServerBoatSkillFrameSource;
import dev.mcai.companion.skills.transport.ServerMinecartSkillActuator;
import dev.mcai.companion.skills.transport.ServerMinecartSkillFrameSource;
import dev.mcai.companion.world.CompanionWorldData;
import dev.mcai.companion.vision.VisionCaptureService;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.PlayLevelSoundEvent;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Owns resources that must have exactly one lifecycle per running server.
 */
public final class CompanionRuntime {
    private static final Gson AUDIT_GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();
    private static final long TRANSPORT_AUDIT_INTERVAL_TICKS = 100L;
    private static final String RUNTIME_LIFECYCLE_AUDIT_TYPE =
        "runtime_lifecycle_audit";
    private static final AtomicReference<String>
            LIVE_MODEL_SURVIVAL_LOG_STATE = new AtomicReference<>("");
    private static long runtimeRetryAfterTick;
    /**
     * A transient chunk/placement failure must not make an ordinary world
     * permanently lose its visible companion.  This is deliberately separate
     * from the runtime tick backoff: a failed body admission is a lifecycle
     * problem, not evidence that model planning should be retried faster.
     */
    private static long bodySpawnRetryAfterTick;
    private static long lastRuntimeFailureLogTick = Long.MIN_VALUE;
    private static long suppressedRuntimeFailureLogs;
    private static long runtimeTickFailures;
    private static final String CORE_SKILL_GUIDE = """
        move_to requires dimension, x/y/z and arrivalRadius, and uses only the
        currently observed same-dimension corridor. look_at requires
        dimension and x/y/z.
        follow_entity requires current observationId, sampleSequence,
        followDistance [1.5,16], and lostGraceTicks [20,600]. It follows only
        repeated line-of-sight samples; UUIDs and hidden positions stay local.
        equip_item requires itemId and slot (mainhand/offhand/head/chest/legs/
        feet). drop_item requires itemId and count [1,64].
        craft_recipe requires recipeId and crafts [1,64], meaning recipe
        executions. It uses unlocked auto-placeable recipes; 3x3 requires an
        already-open reachable crafting-table menu.
        """
            + FairInteractionSkills.plannerGuide()
            + CombatSkills.plannerGuide()
            + LootSkills.plannerGuide()
            + TravelSkills.plannerGuide()
            + ExplorationSkills.plannerGuide()
            + MenuSkills.plannerGuide()
            + DynamicShelterSkills.plannerGuide()
            + BridgeSkills.plannerGuide()
            + EndSkills.plannerGuide()
            + ParkourSkills.plannerGuide()
            + FarmingSkills.plannerGuide()
            + FoundationCraftingSkills.plannerGuide()
            + ResourceGatheringSkills.plannerGuide()
            + MiningSkills.plannerGuide()
            + SurveySkills.plannerGuide()
            + StrongholdSkills.plannerGuide()
            + MemorySkills.plannerGuide()
            + PortalBuildSkills.plannerGuide()
            + PortalSkills.plannerGuide()
            + BoatTransportSkills.plannerGuide()
            + MinecartTransportSkills.plannerGuide()
            + SleepSkills.plannerGuide()
            + ProgressSkills.plannerGuide();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    static String coreSkillGuideForTests() {
        return CORE_SKILL_GUIDE;
    }
    private static final AtomicReference<ServerRuntime> ACTIVE = new AtomicReference<>();

    private CompanionRuntime() {
    }

    static String coreSkillGuide() {
        return CORE_SKILL_GUIDE;
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        ServerStartedEvent.BUS.addListener(CompanionRuntime::onServerStarted);
        ServerStoppingEvent.BUS.addListener(CompanionRuntime::onServerStopping);
        ServerStoppedEvent.BUS.addListener(CompanionRuntime::onServerStopped);
        TickEvent.ServerTickEvent.Post.BUS.addListener(
            event -> onServerTick(event.server())
        );
        LivingDamageEvent.BUS.addListener(
                CompanionRuntime::onLivingDamage
        );
        PlayLevelSoundEvent.AtEntity.BUS.addListener(
                CompanionRuntime::onHostileSound
        );
        RegisterCommandsEvent.BUS.addListener(McpCommands::register);
        RegisterCommandsEvent.BUS.addListener(ModelCommands::register);
    }

    public static Optional<ServerRuntime> active() {
        return Optional.ofNullable(ACTIVE.get());
    }

    public static Optional<McpConnectionInfo> mcpConnectionInfo(
        final net.minecraft.server.MinecraftServer server
    ) {
        return active()
            .filter(runtime -> runtime.server() == server)
            .flatMap(ServerRuntime::mcp)
            .map(endpoint -> new McpConnectionInfo(endpoint.port()));
    }

    /**
     * Non-sensitive server health signal for diagnostics and the dedicated
     * GameTest server. Exception text and model content are never included.
     */
    public static RuntimeFailureAudit runtimeFailureAudit(
            final net.minecraft.server.MinecraftServer server
    ) {
        final ServerRuntime runtime = ACTIVE.get();
        if (runtime == null || runtime.server() != server) {
            return new RuntimeFailureAudit(0L, 0L);
        }
        return new RuntimeFailureAudit(
                runtimeTickFailures,
                suppressedRuntimeFailureLogs
        );
    }

    private static void onServerStarted(final ServerStartedEvent event) {
        final var server = event.getServer();
        runtimeRetryAfterTick = 0L;
        bodySpawnRetryAfterTick = 0L;
        lastRuntimeFailureLogTick = Long.MIN_VALUE;
        suppressedRuntimeFailureLogs = 0L;
        runtimeTickFailures = 0L;
        final CompanionWorldData worldData = CompanionWorldData.get(server);
        final Path databasePath = server.getWorldPath(LevelResource.ROOT)
            .resolve("data")
            .resolve(MinecraftAiCompanion.MOD_ID)
            .resolve("memory.db");
        final MemoryDatabase database = MemoryDatabase.open(databasePath);
        final GoalCoordinator goals = new GoalCoordinator(
            new WorldGoalRevisionStore(worldData),
            new WorldGoalStateStore(worldData)
        );
        final ApiKeyManager apiKeys = new ApiKeyManager(
            FMLPaths.CONFIGDIR.get()
        );
        final ModelProfileStore profileStore =
            new ModelProfileStore(FMLPaths.CONFIGDIR.get());
        final ModelProfileStore.Profile startupProfile =
                worldData.evaluationLocked()
                ? withCachedCapabilities(
                    new ModelProfileStore.Profile(
                        worldData.evaluationModelBaseUrl(),
                        worldData.evaluationModelName()
                    ),
                    profileStore.load()
                )
                        : startupModelProfile(profileStore);
        final int configuredHardTimeoutSeconds =
            CompanionConfig.MODEL_HARD_TIMEOUT_SECONDS.get();
        final int configuredSoftTimeoutSeconds =
            CompanionConfig.MODEL_SOFT_TIMEOUT_SECONDS.get();
        final int effectiveSoftTimeoutSeconds =
            CompanionConfig.effectiveModelSoftTimeoutSeconds(
                configuredSoftTimeoutSeconds,
                configuredHardTimeoutSeconds
            );
        if (effectiveSoftTimeoutSeconds != configuredSoftTimeoutSeconds) {
            MinecraftAiCompanion.LOGGER.warn(
                "model.softTimeoutSeconds={} is not shorter than "
                    + "model.hardTimeoutSeconds={}; using {} seconds",
                configuredSoftTimeoutSeconds,
                configuredHardTimeoutSeconds,
                effectiveSoftTimeoutSeconds
            );
        }
        final java.time.Duration modelSoftTimeout =
            java.time.Duration.ofSeconds(effectiveSoftTimeoutSeconds);
        final java.time.Duration modelHardTimeout =
            java.time.Duration.ofSeconds(configuredHardTimeoutSeconds);
        final String configuredModelBaseUrl =
                startupProfile.baseUrl();
        final String configuredModelName =
                startupProfile.modelName();
        final ModelRuntime model = new ModelRuntime(
            apiKeys,
            configuredModelBaseUrl,
            configuredModelName,
            java.time.Duration.ofSeconds(5),
            modelHardTimeout,
            startupProfile.capabilities(),
            new ModelRuntime.VerifiedProfileSink() {
                @Override
                public void persist(
                    final dev.mcai.companion.model.ModelEndpoint endpoint,
                    final dev.mcai.companion.model.ProviderCapabilities capabilities
                ) {
                    profileStore.save(
                        endpoint.baseUri().toASCIIString(),
                        endpoint.modelName(),
                        capabilities
                    );
                }

                @Override
                public void invalidateCapabilities() {
                    profileStore.invalidateCapabilities();
                }
            }
        );
        final SkillRegistry skills = new SkillRegistry();
        final RuntimeActionTrace actionTrace =
            new RuntimeActionTrace(database, goals::snapshot);
        final ServerOwnedCoreSkillActuator coreActions =
            new ServerOwnedCoreSkillActuator(
                server,
                worldData.companionUuid(),
                actionTrace::record
            );
        final ServerCoreSkillFrameSource coreFrames =
            new ServerCoreSkillFrameSource(
                server,
                worldData.companionUuid()
            );
        final ServerInventorySkillActuator inventoryActions =
            new ServerInventorySkillActuator(
                server,
                worldData.companionUuid()
            );
        final ServerResourceInventorySource resourceInventory =
            new ServerResourceInventorySource(
                server,
                worldData.companionUuid()
            );
        final FoundationActionAudit foundationAudit =
            new FoundationActionAudit(worldData);
        final OwnedStructureBlockIndex protectedStructures =
            new OwnedStructureBlockIndex();
        worldData.verifiedShelterEvidence(
                worldData.goalRevision()
        ).ifPresent(
                protectedStructures::restoreVerifiedShelter
        );
        final ServerOwnedInteractionSkillActuator interactionActions =
            new ServerOwnedInteractionSkillActuator(
                server,
                worldData.companionUuid(),
                foundationAudit,
                actionTrace::record,
                protectedStructures
            );
        final ServerBoatSkillActuator boatActions =
            new ServerBoatSkillActuator(
                server,
                worldData.companionUuid()
            );
        final ServerMinecartSkillActuator minecartActions =
            new ServerMinecartSkillActuator(
                server,
                worldData.companionUuid(),
                coreActions
            );
        final EmergencySurvivalController survival =
            new EmergencySurvivalController(
                worldData.companionUuid(),
                coreActions,
                coreFrames,
                new InventoryEmergencyEquipmentActuator(
                    inventoryActions
                ),
                new InteractionEmergencyMeleeActuator(
                    interactionActions
                ),
                () -> {
                    /*
                     * These are releases only. The emergency state writes its
                     * own core input immediately after this callback.
                     */
                    interactionActions.quiesceNow();
                    boatActions.quiesceNow();
                    minecartActions.quiesceNow();
                }
            );
        final IdleEquipmentController idleEquipment =
            new IdleEquipmentController(
                server,
                worldData.companionUuid(),
                inventoryActions
            );
        final ServerInteractionSkillFrameSource interactionFrames =
            new ServerInteractionSkillFrameSource(
                server,
                worldData.companionUuid()
            );
        final ServerMenuSkillFrameSource menuFrames =
            new ServerMenuSkillFrameSource(
                server,
                worldData.companionUuid()
            );
        final ServerMenuSkillActuator menuActions =
            new ServerMenuSkillActuator(
                server,
                worldData.companionUuid(),
                menuFrames,
                foundationAudit,
                actionTrace::record
            );
        final ServerShelterFrameSource shelterFrames =
            new ServerShelterFrameSource(
                server,
                worldData.companionUuid()
            );
        final AsyncHydratedCropFieldPlanService mechanismPlans =
            new AsyncHydratedCropFieldPlanService();
        final ServerBoatSkillFrameSource boatFrames =
            new ServerBoatSkillFrameSource(
                server,
                worldData.companionUuid()
            );
        final ServerMinecartSkillFrameSource minecartFrames =
            new ServerMinecartSkillFrameSource(
                server,
                worldData.companionUuid()
            );
        final ServerPortalSkillFrameSource portalFrames =
            new ServerPortalSkillFrameSource(
                server,
                worldData.companionUuid()
            );
        final ServerSleepSkillFrameSource sleepFrames =
            new ServerSleepSkillFrameSource(
                server,
                worldData.companionUuid()
            );
        final PortalTraversalBuffer portalTraversals =
            new PortalTraversalBuffer();
        final PersistentPortalTraversalObserver persistentPortalTraversals =
            new PersistentPortalTraversalObserver(
                worldData.companionUuid(),
                database.portalEdges()
            );
        final WaypointRecallBuffer waypointRecall =
            new WaypointRecallBuffer();
        final AsyncVerifiedPortalEdgeRecall portalEdgeRecall =
            new AsyncVerifiedPortalEdgeRecall(
                worldData.companionUuid(),
                database.portalEdges()
            );
        final SurveyResultBuffer surveyResults =
            new SurveyResultBuffer();
        final EyeTraceResultBuffer eyeTraceResults =
            new EyeTraceResultBuffer();
        final SurvivalRouteTracker routeTracker =
            new SurvivalRouteTracker(worldData);
        final ServerBridgeMaterialActuator bridgeMaterials =
            new ServerBridgeMaterialActuator(
                server,
                worldData.companionUuid(),
                inventoryActions
            );
        CoreSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames
        );
        TravelSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            () -> AiPlayerManager.status(server).sessionGeneration()
        );
        ExplorationSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            () -> AiPlayerManager.status(server).sessionGeneration()
        );
        FairInteractionSkills.registerAll(
            skills,
            worldData.companionUuid(),
            interactionActions,
            interactionFrames
        );
        LootSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            interactionActions,
            interactionFrames
        );
        CombatSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            interactionActions,
            interactionFrames
        );
        CombatSkills.registerDragonFight(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            interactionActions,
            interactionFrames,
            inventoryActions,
            bridgeMaterials,
            goalRevision -> worldData
                .verifiedRouteProgress(goalRevision)
                .milestones()
                .contains(
                    dev.mcai.companion.progression
                        .SurvivalMilestone.DRAGON_KILLED
                ),
            () -> AiPlayerManager.status(server).sessionGeneration()
        );
        InventorySkills.registerAll(
            skills,
            inventoryActions
        );
        FoundationCraftingSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            interactionActions,
            interactionFrames,
            inventoryActions,
            resourceInventory,
            menuActions,
            menuFrames,
            goalRevision -> worldData
                .verifiedFoundationEvidence(goalRevision)
                .flatMap(evidence -> evidence.craftingTable()),
            goalRevision -> worldData
                .verifiedFoundationEvidence(goalRevision)
                .flatMap(evidence -> evidence.furnace()),
            goalRevision -> worldData
                .verifiedFoundationEvidence(goalRevision)
                .flatMap(evidence -> evidence.storage()),
            revision -> worldData.markVerifiedRouteMilestones(
                    revision,
                    java.util.EnumSet.of(
                            SurvivalMilestone.LOG_STORAGE_DISTRIBUTED
                    )
            ),
            revision -> worldData.markVerifiedRouteMilestones(
                    revision,
                    java.util.EnumSet.of(
                            SurvivalMilestone.CONTAINER_WOOD_DOOR_PLACED
                    )
            )
        );
        BridgeSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            bridgeMaterials
        );
        EndSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            bridgeMaterials,
            () -> AiPlayerManager.status(server).sessionGeneration(),
            interactionActions,
            interactionFrames,
            revision -> worldData.markVerifiedRouteMilestones(
                    revision,
                    java.util.EnumSet.of(
                            SurvivalMilestone.END_ISLAND_REACHED
                    )
            )
        );
        ParkourSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames
        );
        MenuSkills.registerAll(
            skills,
            menuActions,
            worldData.companionUuid(),
            menuFrames
        );
        DynamicShelterSkills.registerAll(
            skills,
            worldData.companionUuid(),
            interactionActions,
            shelterFrames,
            inventoryActions,
            coreActions,
            coreFrames,
            surveyResults,
            new dev.mcai.companion.skills.building
                    .DynamicShelterPlanner(),
            worldData::recordVerifiedShelter,
            protectedStructures
        );
        FarmingSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            interactionActions,
            interactionFrames,
            shelterFrames,
            mechanismPlans
        );
        ResourceGatheringSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            interactionActions,
            interactionFrames,
            resourceInventory
        );
        MiningSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            interactionActions,
            interactionFrames,
            resourceInventory
        );
        SurveySkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            surveyResults
        );
        StrongholdSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            inventoryActions,
            eyeTraceResults,
            () -> AiPlayerManager.status(server).sessionGeneration(),
            revision -> worldData.markVerifiedRouteMilestones(
                    revision,
                    java.util.EnumSet.of(
                            SurvivalMilestone
                                    .STRONGHOLD_BEARING_MEASURED,
                            SurvivalMilestone
                                    .STRONGHOLD_SEARCH_AREA_TRIANGULATED
                    )
            ),
            interactionActions,
            interactionFrames,
            resourceInventory
        );
        PortalSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            portalFrames,
            () -> AiPlayerManager.status(server).sessionGeneration(),
            (dimension, position, radius, limit) ->
                    database.portalEdges().findNearbyArrivals(
                            worldData.companionUuid(),
                            dimension,
                            position,
                            radius,
                            limit
                    ),
            dev.mcai.companion.skills.portal.PortalSkillPolicy.defaults(),
            result -> {
                portalTraversals.onTraversal(result);
                persistentPortalTraversals.onTraversal(result);
            }
        );
        PortalBuildSkills.registerAll(
            skills,
            worldData.companionUuid(),
            coreActions,
            coreFrames,
            interactionActions,
            interactionFrames,
            inventoryActions,
            () -> AiPlayerManager.status(server).sessionGeneration()
        );
        BoatTransportSkills.registerAll(
            skills,
            worldData.companionUuid(),
            boatActions,
            boatFrames
        );
        MinecartTransportSkills.registerAll(
            skills,
            worldData.companionUuid(),
            minecartActions,
            minecartFrames
        );
        SleepSkills.registerAll(
            skills,
            worldData.companionUuid(),
            interactionActions,
            sleepFrames
        );
        MemorySkills.registerAll(
            skills,
            (dimension, query, limit) -> database.waypoints()
                .searchByName(
                    worldData.companionUuid(),
                    dimension,
                    query,
                    java.time.Instant.now(),
                    limit
                ),
            waypointRecall,
            worldData.companionUuid(),
            worldData.companionUuid(),
            () -> {
                final var status = AiPlayerManager.status(server);
                if (status.state() != SessionState.ACTIVE
                    || !status.online()) {
                    return Optional.empty();
                }
                return AiPlayerManager.onlinePlayer(server).map(player ->
                    new ObservedCurrentPosition(
                        dev.mcai.companion.waypoint.DimensionRef.parse(
                            player.level()
                                .dimension()
                                .identifier()
                                .toString()
                        ),
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        status.sessionGeneration()
                    )
                );
            },
            database.waypoints()::upsert,
            () -> !goals.snapshot().externalWritesLocked()
        );
        ProgressSkills.registerAll(
            skills,
            worldData::appendGoalProgress
        );
        final SkillSupervisor skillSupervisor = new SkillSupervisor(
            skills,
            new MemorySkillCheckpointSink(database, worldData.companionUuid()),
            new SkillRuntimePolicy(
                // Two milliseconds is the measured p95 target, not a safe
                // one-sample kill switch.  A cold class load, packet-driven
                // mount, or rare GC pause can legitimately cross it once.
                // RuntimeTickMetrics still audits the 2 ms p95 release gate;
                // the supervisor's 10 ms ceiling stops genuinely runaway
                // local skill work without cancelling valid vanilla actions.
                java.time.Duration.ofMillis(10),
                java.time.Duration.ofMinutes(125),
                200,
                0.35,
                0.15,
                /*
                 * A first skill tick can include class loading and the
                 * server's initial chunk ticket work. Keep the local kill
                 * switch bounded, but do not terminate a legal action after
                 * three cold-start samples; RuntimeTickMetrics remains the
                 * authoritative 2 ms p95 performance gate.
                 */
                12
            )
        );
        final VisionCaptureService visionCapture =
            new VisionCaptureService(
                server,
                worldData.companionUuid()
            );
        final MinecraftObservationProvider observations =
            new MinecraftObservationProvider(
                server,
                skillSupervisor,
                () -> model.gateway().configured(),
                observation -> {
                    coreFrames.publish(observation);
                    interactionFrames.publish(observation);
                    menuFrames.publish(observation);
                    shelterFrames.publish(observation);
                    portalFrames.publish(observation);
                    boatFrames.publish(observation);
                    minecartFrames.publish(observation);
                    sleepFrames.publish(observation);
                },
                worldData::goalProgress,
                waypointRecall::snapshot,
                portalEdgeRecall,
                surveyResults::snapshot,
                eyeTraceResults::snapshot,
                revision -> {
                    final var goal = goals.snapshot();
                    if (goal.revision() != revision) {
                        return Optional.empty();
                    }
                    return AiPlayerManager.onlinePlayer(server).flatMap(
                        player -> routeTracker.snapshot(
                            goal,
                            player,
                            eyeTraceResults.snapshot(revision)
                        )
                    );
                }
            );
        observations.attachActiveVisionRequester(request -> {
            if (!CompanionConfig.ACTIVE_VISION_ENABLED.get()) {
                return ObservationRequestStatus.UNSUPPORTED;
            }
            final VisionCaptureService.RequestState capture =
                    visionCapture.request(request);
            if (capture.accepted() || capture.pending()) {
                return ObservationRequestStatus.ACCEPTED;
            }
            return capture.code().equals(
                    "authenticated_renderer_unavailable"
            ) || capture.code().equals("high_crop_not_implemented")
                    ? ObservationRequestStatus.UNSUPPORTED
                    : ObservationRequestStatus.REJECTED;
        });
        final MinecraftBrainEventSink brainEvents =
            new MinecraftBrainEventSink(
                server,
                worldData,
                database,
                observations::latestDecisionEpoch,
                actionTrace
            );
        final BrainOrchestrator brain = new BrainOrchestrator(
            goals,
            model.gateway(),
            skillSupervisor,
            observations,
            new MinecraftPlannerInputFactory(
                skills,
                CORE_SKILL_GUIDE,
                MinecraftPlannerInputFactory.DEFAULT_MAX_OUTPUT_TOKENS,
                () -> new MinecraftPlannerInputFactory.AgentPromptSettings(
                    worldData.displayName(),
                    worldData.temperature(),
                    worldData.agentSystemPrompt()
                ),
                visionCapture::takeLatestModelImage
            ),
            brainEvents,
            new BrainPolicy(
                java.time.Duration.ofMillis(250),
                modelSoftTimeout,
                modelHardTimeout,
                8
            ),
            new dev.mcai.companion.progression
                    .ServerGoalCompletionVerifier(worldData)
        );
        final CompanionConversationCoordinator conversation =
            new CompanionConversationCoordinator(
                server,
                worldData,
                goals,
                model.gateway(),
                brain,
                brainEvents,
                () -> model.snapshot().gatewayReady(),
                () -> model.snapshot().probeInFlight(),
                observations::latestDecisionEpoch,
                observations::latestSemanticJson,
                coreFrames::hasRecentThreatSignal,
                coreFrames::recordPlayerThreatWarning,
                modelSoftTimeout,
                database
            );
        final RuntimeTickMetrics tickMetrics = new RuntimeTickMetrics();
        final BehaviorArbiter behaviorArbiter =
            new BehaviorArbiter();
        final Optional<LoopbackMcpServer> mcp = startMcp(
            server,
            worldData,
            database,
            goals,
            observations,
            tickMetrics,
            visionCapture
        );
        final ModelSetupModule.RuntimeAttachment modelSetup =
            ModelSetupModule.attach(server, model);
        final ModelBootstrapCoordinator modelBootstrap =
            new ModelBootstrapCoordinator(
                server,
                worldData,
                goals,
                model
            );
        final ServerRuntime runtime = new ServerRuntime(
            server,
            worldData,
            database,
            goals,
            apiKeys,
            model,
            modelSetup,
            modelBootstrap,
            skills,
            skillSupervisor,
            observations,
            brain,
            conversation,
            coreActions,
            coreFrames,
            eyeTraceResults,
            survival,
            idleEquipment,
            interactionActions,
            foundationAudit,
            boatActions,
            minecartActions,
            mechanismPlans,
            behaviorArbiter,
            tickMetrics,
            new java.util.concurrent.atomic.AtomicLong(
                AiPlayerManager.status(server).sessionGeneration()
            ),
            new java.util.concurrent.atomic.AtomicLong(-1L),
            visionCapture,
            mcp
        );
        if (!ACTIVE.compareAndSet(null, runtime)) {
            mcp.ifPresent(LoopbackMcpServer::close);
            visionCapture.close();
            mechanismPlans.close();
            modelSetup.close();
            brain.close();
            skillSupervisor.close();
            model.close();
            apiKeys.close();
            database.close();
            throw new IllegalStateException("Minecraft AI Companion runtime already active");
        }
        appendRuntimeLifecycleAudit(runtime, "started");
        MinecraftAiCompanion.LOGGER.info(
            "Opened companion runtime for UUID {} at {}",
            worldData.companionUuid(),
            databasePath
        );
        mcp.ifPresent(endpoint -> MinecraftAiCompanion.LOGGER.info(
            "Codex MCP is listening on loopback port {}; bearer token is withheld from logs",
            endpoint.port()
        ));
        /*
         * A saved Keychain credential must make the next world immediately
         * usable. ModelBootstrapCoordinator owns the sole probe entry so it
         * can inspect the persistent Hardcore model lock before any provider
         * request. Ordinary accelerated GameTests deliberately stay offline;
         * the opt-in live suite exercises this exact startup path.
         */
        if (!isGameTestServer(server)
                || Boolean.getBoolean("mcai.liveModelTest")) {
            modelBootstrap.requestOrdinaryStartupRestore();
        }
        /*
         * The companion is a server-side player, not a feature that exists
         * only while a human is online. Production worlds restore its body
         * at server start even with zero human players and even when no API
         * credential is available; without a verified model it simply
         * remains inert. Accelerated GameTests keep explicit ownership of
         * their fixture body unless a running persisted goal needs recovery.
         */
        if (AiPlayerManager.status(server).state()
                    == SessionState.ABSENT
                && (!isGameTestServer(server)
                    || Boolean.getBoolean(
                        "mcai.zeroHumanAutoSpawnTest"
                    )
                    || goals.snapshot().status()
                        == GoalStatus.RUNNING)) {
            AiPlayerManager.requestSpawn(server);
        }
    }

    private static boolean isGameTestServer(
            final net.minecraft.server.MinecraftServer server
    ) {
        return server.getClass().getName().equals(
                "net.minecraft.gametest.framework.GameTestServer"
        );
    }

    private static Optional<LoopbackMcpServer> startMcp(
        final net.minecraft.server.MinecraftServer server,
        final CompanionWorldData worldData,
        final MemoryDatabase database,
        final GoalCoordinator goals,
        final MinecraftObservationProvider observations,
        final RuntimeTickMetrics tickMetrics,
        final VisionCaptureService visionCapture
    ) {
        if (!CompanionConfig.MCP_ENABLED.get()) {
            return Optional.empty();
        }
        try {
            final MinecraftMcpBackend backend =
                new MinecraftMcpBackend(
                    server,
                    worldData,
                    database,
                    goals,
                    observations::latestDecisionEpoch,
                    tickMetrics,
                    visionCapture
                );
            final String configuredToken = System.getenv("MCAI_MCP_TOKEN");
            if (configuredToken == null || configuredToken.isBlank()) {
                throw new IllegalArgumentException(
                    "MCP requires MCAI_MCP_TOKEN"
                );
            }
            final LoopbackMcpServer endpoint =
                LoopbackMcpServer.start(
                    CompanionConfig.MCP_PORT.get(),
                    backend,
                    configuredToken
                );
            return Optional.of(endpoint);
        } catch (IOException | IllegalArgumentException exception) {
            MinecraftAiCompanion.LOGGER.error(
                "Unable to start loopback MCP server; companion gameplay remains available"
            );
            return Optional.empty();
        }
    }

    public record McpConnectionInfo(int port) {
    }

    public record RuntimeFailureAudit(
            long failureCount,
            long suppressedLogCount
    ) {
    }

    private static void onLivingDamage(
            final LivingDamageEvent event
    ) {
        if (!(event.getEntity()
                instanceof net.minecraft.server.level.ServerPlayer
                    player)) {
            return;
        }
        final ServerRuntime runtime = ACTIVE.get();
        if (runtime == null
                || runtime.server() != player.level().getServer()
                || !runtime.worldData()
                        .companionUuid()
                        .equals(player.getUUID())) {
            return;
        }
        runtime.coreFrames().recordDamage(
            player,
            event.getSource(),
            event.getAmount()
        );
        /*
         * Damage is a real first-person sensory interrupt.  The 20 TPS
         * emergency lane already owns the directional hit cue, but the
         * semantic entity list is normally sampled at 4 Hz.  Request the
         * next fair sample immediately so a hostile that entered the view
         * between semantic intervals becomes actionable on the following
         * server tick instead of leaving the body in a stale stare/guard
         * state.  This only invalidates the cadence; the sampler still
         * applies its normal distance, FOV and block-clip checks.
         */
        try {
            runtime.observations().requestObservation(
                new RequestedObservation(
                    ObservationKind.SEMANTIC_REFRESH,
                    "recent_damage"
                )
            );
        } catch (RuntimeException ignored) {
            /* A damage cue must never change vanilla damage semantics. */
        }
    }

    /**
     * Feeds the body a short-lived, fair auditory threat cue.  The event is
     * filtered to vanilla/Forge hostile entities here; the frame source then
     * strips identity and exact position before the cue can reach either the
     * model or the semantic observation.  It is an interrupt, not a model
     * action request: the local 20 TPS survival lane owns the first response.
     */
    private static void onHostileSound(
            final PlayLevelSoundEvent.AtEntity event
    ) {
        if (!(event.getEntity()
                    instanceof net.minecraft.world.entity.monster.Enemy)
                || !event.getEntity().isAlive()) {
            return;
        }
        final var level = event.getLevel();
        final var server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return;
        }
        final ServerRuntime runtime = ACTIVE.get();
        if (runtime == null || runtime.server() != server) {
            return;
        }
        final var body = AiPlayerManager.onlinePlayer(server);
        if (body.isEmpty()
                || body.orElseThrow().level() != level
                || body.orElseThrow().isRemoved()) {
            return;
        }
        try {
            runtime.coreFrames().recordAudibleHostileSound(
                    body.orElseThrow(),
                    event.getEntity(),
                    event.getNewVolume()
            );
            runtime.observations().requestObservation(
                    new RequestedObservation(
                            ObservationKind.SEMANTIC_REFRESH,
                            "audible_hostile_sound"
                    )
            );
        } catch (RuntimeException ignored) {
            /* Sound cues must never alter vanilla sound or gameplay flow. */
        }
    }

    private static void onServerTick(
        final net.minecraft.server.MinecraftServer server
    ) {
        final ServerRuntime runtime = ACTIVE.get();
        if (runtime == null || runtime.server() != server) {
            return;
        }
        final long tickStartedNanos = System.nanoTime();
        var goal = runtime.goals().snapshot();
        var embodiment = AiPlayerManager.status(server);
        boolean emergencyQuiesce = false;
        boolean bodyReadyForControl = false;
        boolean survivalIntervened = false;
        EmergencySurvivalController.TickReport survivalReport =
            new EmergencySurvivalController.TickReport(
                false,
                EmergencySurvivalController.State.CLEAR,
                ""
            );
        try {
            final long currentTick =
                    Integer.toUnsignedLong(server.getTickCount());
            if (currentTick < runtimeRetryAfterTick) {
                emergencyQuiesce = true;
                return;
            }
            runtime.modelBootstrap().tick();
            goal = runtime.goals().snapshot();
            embodiment = AiPlayerManager.status(server);
            final long previousGeneration =
                runtime.observedBodySessionGeneration().getAndSet(
                    embodiment.sessionGeneration()
                );
            if (previousGeneration != embodiment.sessionGeneration()) {
                emergencyQuiesce = true;
                runtime.coreActions().quiesceNow();
                runtime.interactionActions().quiesceNow();
                runtime.boatActions().quiesceNow();
                runtime.minecartActions().quiesceNow();
                runtime.skillSupervisor().abandonForSessionEnd();
                runtime.brain().onBodySessionChanged();
                runtime.survival().reset();
                runtime.idleEquipment().resetForBodySession();
                runtime.observations().invalidateBodySession();
                runtime.coreFrames().invalidateBodySession();
            }

            if ((goal.status() == GoalStatus.RUNNING
                || goal.status() == GoalStatus.CANCEL_PENDING)
                && embodiment.state() == SessionState.FAILED) {
                runtime.goals().markTerminal(
                    GoalStatus.FAILED,
                    "embodiment_failed"
                );
                emergencyQuiesce = true;
                return;
            }
            if (goal.status() == GoalStatus.RUNNING) {
                if (embodiment.state() == SessionState.ABSENT) {
                    AiPlayerManager.requestSpawn(server);
                    emergencyQuiesce = true;
                    return;
                }
            } else if (goal.status() == GoalStatus.CANCEL_PENDING
                && embodiment.state() == SessionState.ABSENT) {
                runtime.goals().markTerminal(
                    GoalStatus.SAFE_IDLE,
                    "body_absent"
                );
                emergencyQuiesce = true;
                return;
            }

            /*
             * Ordinary worlds also need a self-healing admission path.  The
             * initial ServerStartedEvent request can fail transiently while
             * the spawn chunk is still loading or while a saved position is
             * temporarily unsafe.  Previously only an already-running goal
             * retried, so an otherwise idle world could remain permanently
             * body-less after one such failure.  Retry FAILED (not ABSENT):
             * ABSENT is a deliberate remove/stop state and must not be
             * silently undone.  The bounded backoff prevents a broken world
             * border or permanently invalid save from creating a per-tick
             * spawn loop, and no model or world mutation is involved.
             */
            if (embodiment.state() == SessionState.FAILED
                    && !runtime.worldData().hardcoreDead()
                    && currentTick >= bodySpawnRetryAfterTick) {
                final AiPlayerManager.OperationResult respawn =
                        AiPlayerManager.requestSpawn(server);
                bodySpawnRetryAfterTick = currentTick
                        + (respawn.accepted() ? 20L : 200L);
                emergencyQuiesce = true;
                return;
            }

            if (embodiment.state() != SessionState.ACTIVE
                || !embodiment.online()) {
                emergencyQuiesce = true;
                return;
            }
            final var body = AiPlayerManager.onlinePlayer(server);
            if (body.isEmpty()
                    || body.orElseThrow().isRemoved()
                    || body.orElseThrow().isDeadOrDying()
                    || !body.orElseThrow().isAlive()
                    || body.orElseThrow().getHealth() <= 0.0F) {
                /*
                 * A normal survival death keeps the transport online while
                 * vanilla displays the death state and later replaces the
                 * ServerPlayer on respawn. Sampling menus, issuing skills or
                 * refreshing body-bound maps during that interval caused one
                 * exception every server tick in real worlds. AiPlayerSession
                 * owns the ordinary respawn; this runtime waits without
                 * inventing movement or speech.
                 */
                emergencyQuiesce = true;
                return;
            }
            bodyReadyForControl = true;
            recordTransportAudit(runtime, currentTick, goal.revision());
            /*
             * Refresh authoritative body state before the conversation lane
             * on every tick, including while a gameplay goal is running.
             * Previously the running-goal branch sampled only after
             * conversation dispatch. A /tp, portal transition, respawn, or
             * other large movement could therefore send the model the old
             * location and old terrain for one request, producing exactly
             * the field failure where a companion standing in a village
             * claimed it was still in a distant forest. BrainOrchestrator's
             * later call reuses this bounded/cached sample.
             */
            runtime.observations().refreshSemantic(goal);
            runtime.foundationAudit().tick(body.orElseThrow());
            runtime.conversation().tick();
            final boolean modelControlEnabled =
                    runtime.model().snapshot().gatewayReady();
            /*
             * A missing/rejected credential disables the high-level model
             * lane, but it must not freeze the local survival reflex or
             * ordinary equipment upkeep. The emergency controller is a fair,
             * server-owned 20 TPS safety loop: it may only use the current
             * first-person frame and the body's owned items. It never creates
             * a goal or speaks. Active gameplay skills still require a
             * verified gateway; the low-risk idle equipment lane may continue
             * through the normal inventory-menu path so a body can wear an
             * armor upgrade or shield that it already owns while setup is
             * incomplete.
             */

            final BehaviorCycle behavior = arbitrateBehavior(
                runtime,
                currentTick,
                modelControlEnabled
            );
            survivalReport = behavior.survivalReport();
            survivalIntervened =
                behavior.resolution().claimedBy(
                    BehaviorArbiter.Lane.EMERGENCY_SURVIVAL
                );
            if (behavior.resolution().failedClosed()) {
                emergencyQuiesce = true;
                return;
            }
            if (!modelControlEnabled && !survivalIntervened) {
                emergencyQuiesce = true;
            }
            if (behavior.skillEnded()) {
                emergencyQuiesce = true;
            }
            final GoalStatus after = runtime.goals().snapshot().status();
            if (after != GoalStatus.RUNNING
                && after != GoalStatus.CANCEL_PENDING
                && !survivalIntervened) {
                emergencyQuiesce = true;
            }
        } catch (RuntimeException exception) {
            emergencyQuiesce = true;
            runtimeTickFailures++;
            runtimeRetryAfterTick = Integer.toUnsignedLong(
                    server.getTickCount()
            ) + 20L;
            final GoalStatus current = runtime.goals().snapshot().status();
            if (current == GoalStatus.RUNNING
                || current == GoalStatus.CANCEL_PENDING) {
                runtime.goals().markTerminal(
                    GoalStatus.SAFE_IDLE,
                    "runtime_tick_failure"
                );
            }
            logRuntimeFailureSafely(server, exception);
        } finally {
            try {
                final boolean localControlEnabled =
                        runtime.model().snapshot().gatewayReady();
                if (embodiment.state() == SessionState.ACTIVE
                    && embodiment.online()
                    && bodyReadyForControl
                    && localControlEnabled) {
                    final String signature = survivalIntervened
                            ? survivalReport.state() + ":"
                                + survivalReport.reason()
                            : "";
                    final String previous =
                            LIVE_MODEL_SURVIVAL_LOG_STATE
                                    .getAndSet(signature);
                    if (survivalIntervened
                            && !signature.equals(previous)) {
                        MinecraftAiCompanion.LOGGER.info(
                                "Companion emergency intervention: "
                                    + "state={}, reason={}",
                                survivalReport.state(),
                                survivalReport.reason()
                        );
                    }
                } else if (!localControlEnabled && !survivalIntervened) {
                    /*
                     * No verified model means no high-level skill or idle
                     * equipment writes.  Keep the emergency reflex state
                     * alive when it claimed this tick; otherwise reset it and
                     * release every body input so a stale lease cannot move
                     * the player while setup is incomplete.
                     */
                    runtime.survival().reset();
                    emergencyQuiesce = true;
                }
                if (!localControlEnabled) {
                    /*
                     * Vehicle lanes are never part of offline safety. The
                     * interaction actuator is different: it shares the
                     * player's use-item state with the core emergency lane.
                     * Releasing it after a shield claim sends a real
                     * RELEASE_USE_ITEM packet and cancels the shield every
                     * tick. The emergency preemption callback already
                     * releases any stale interaction action when ownership
                     * changes, so leave it untouched while survival owns the
                     * body and only quiesce it on an unclaimed offline tick.
                     */
                    if (!survivalIntervened) {
                        runtime.interactionActions().quiesceNow();
                    }
                    runtime.boatActions().quiesceNow();
                    runtime.minecartActions().quiesceNow();
                }
                if (emergencyQuiesce && !survivalIntervened) {
                    runtime.coreActions().quiesceNow();
                }
                if (emergencyQuiesce) {
                    if (!survivalIntervened) {
                        runtime.interactionActions().quiesceNow();
                    }
                    runtime.boatActions().quiesceNow();
                    runtime.minecartActions().quiesceNow();
                }
            } catch (RuntimeException exception) {
                runtime.coreActions().quiesceNow();
                runtime.interactionActions().quiesceNow();
                runtime.boatActions().quiesceNow();
                runtime.minecartActions().quiesceNow();
            } finally {
                try {
                    runtime.coreActions().postServerTick();
                } finally {
                    runtime.tickMetrics().record(
                        System.nanoTime() - tickStartedNanos
                    );
                }
            }
        }
    }

    /**
     * Persists a low-frequency transport health sample from the real
     * headless connection.  The sample is intentionally independent of the
     * model lane: a configured API cannot make an un-drained packet queue or
     * a missing disconnect callback look healthy.
     */
    private static void recordTransportAudit(
            final ServerRuntime runtime,
            final long currentTick,
            final long goalRevision
    ) {
        final long previous = runtime.lastTransportAuditTick().get();
        if (previous >= 0L
                && currentTick < previous + TRANSPORT_AUDIT_INTERVAL_TICKS) {
            return;
        }
        if (!runtime.lastTransportAuditTick().compareAndSet(
                previous,
                currentTick
        )) {
            return;
        }
        AiPlayerManager.transportAudit(runtime.server()).ifPresent(audit -> {
            appendTransportAudit(runtime, audit, goalRevision);
        });
    }

    /**
     * Receives the final snapshot after the embodiment module closes the
     * vanilla connection.  This keeps the final disconnect callback evidence
     * in the same SQLite audit stream as live transport samples.
     */
    public static void recordFinalTransportAudit(
            final net.minecraft.server.MinecraftServer server,
            final Optional<AiPlayerManager.TransportAudit> finalAudit
    ) {
        final ServerRuntime runtime = ACTIVE.get();
        if (runtime == null || runtime.server() != server) {
            return;
        }
        finalAudit.ifPresent(audit -> appendTransportAudit(
                runtime,
                audit,
                runtime.goals().snapshot().revision()
        ));
    }

    private static void appendTransportAudit(
            final ServerRuntime runtime,
            final AiPlayerManager.TransportAudit audit,
            final long goalRevision
    ) {
        final JsonObject payload = new JsonObject();
        payload.addProperty("discardedPackets", audit.discardedPackets());
        payload.addProperty(
                "keepAliveAcknowledgements",
                audit.keepAliveAcknowledgements()
        );
        payload.addProperty(
                "teleportAcknowledgements",
                audit.teleportAcknowledgements()
        );
        payload.addProperty(
                "chunkBatchAcknowledgements",
                audit.chunkBatchAcknowledgements()
        );
        payload.addProperty(
                "endCreditsRespawnRequests",
                audit.endCreditsRespawnRequests()
        );
        payload.addProperty("largestDrain", audit.largestDrain());
        payload.addProperty(
                "outboundQueueHighWatermark",
                audit.outboundQueueHighWatermark()
        );
        payload.addProperty(
                "unreleasedOutboundPackets",
                audit.unreleasedOutboundPackets()
        );
        payload.addProperty(
                "disconnectHandled",
                audit.disconnectionHandled()
        );
        runtime.memory().appendEvent(new MemoryEvent(
                Instant.now(),
                "connection_transport_audit",
                "embodiment",
                AUDIT_GSON.toJson(payload),
                Math.max(0L, runtime.observations().latestDecisionEpoch()),
                Math.max(0L, goalRevision)
        ));
    }

    /**
     * Records only authoritative restart identity/state, never goal prose,
     * prompts, provider responses, or credentials.  Two started rows with
     * the same companion UUID and monotonic SavedData revision are the
     * durable evidence used by the dedicated restart gate.
     */
    private static void appendRuntimeLifecycleAudit(
            final ServerRuntime runtime,
            final String phase
    ) {
        final var goal = runtime.goals().snapshot();
        final JsonObject payload = new JsonObject();
        payload.addProperty(
                "phase",
                phase == null || phase.isBlank() ? "unknown" : phase
        );
        payload.addProperty(
                "companionUuid",
                runtime.worldData().companionUuid().toString()
        );
        payload.addProperty("goalRevision", goal.revision());
        payload.addProperty("goalStatus", goal.status().name());
        payload.addProperty("goalSource", goal.source().name());
        payload.addProperty(
                "bodyEverSpawned",
                runtime.worldData().bodyEverSpawned()
        );
        payload.addProperty(
                "hardcoreDead",
                runtime.worldData().hardcoreDead()
        );
        payload.addProperty(
                "evaluationLocked",
                runtime.worldData().evaluationLocked()
        );
        payload.addProperty(
                "evaluationContaminated",
                runtime.worldData().evaluationContaminated()
        );
        payload.addProperty(
                "memorySchemaVersion",
                runtime.worldData().schemaVersion()
        );
        payload.addProperty("serverTick", runtime.server().getTickCount());
        runtime.memory().appendEvent(new MemoryEvent(
                Instant.now(),
                RUNTIME_LIFECYCLE_AUDIT_TYPE,
                "runtime",
                AUDIT_GSON.toJson(payload),
                Math.max(0L, runtime.worldData().goalRevision()),
                Math.max(0L, goal.revision())
        ));
    }

    /**
     * Runs the only three body-authoring lanes in strict priority order.
     *
     * <p>Emergency survival is evaluated before an atomic skill gets its
     * Tick. If it claims, the brain/supervisor is not advanced and therefore
     * cannot enqueue a contradictory movement, view or use action. Idle
     * equipment runs only when neither lane owns the body.</p>
     */
    private static BehaviorCycle arbitrateBehavior(
            final ServerRuntime runtime,
            final long currentTick,
            final boolean modelControlEnabled
    ) {
        final AtomicReference<EmergencySurvivalController.TickReport>
            survival = new AtomicReference<>(
                new EmergencySurvivalController.TickReport(
                    false,
                    runtime.survival().state(),
                    ""
                )
            );
        final AtomicBoolean skillEnded = new AtomicBoolean();
        final List<BehaviorArbiter.Candidate> candidates =
            new ArrayList<>(3);
        if (runtime.skillSupervisor().consumeActiveSkillEndedHandoff()) {
            runtime.survival().onActiveSkillEnded();
        }
        candidates.add(
                    new BehaviorArbiter.Candidate(
                        BehaviorArbiter.Lane.EMERGENCY_SURVIVAL,
                        () -> {
                            final boolean activeBefore =
                                isActive(
                                    runtime.skillSupervisor()
                                        .snapshot()
                                );
                            if (!modelControlEnabled && activeBefore) {
                                /*
                                 * Emergency survival has first ownership of
                                 * this tick.  Do not leave a model-authored
                                 * skill attached behind it when the gateway
                                 * has disappeared.  Release every task-owned
                                 * input before detaching it, then let the
                                 * supervisor record a bounded
                                 * model-disconnected safe-idle result.
                                 */
                                runtime.coreActions().quiesceNow();
                                runtime.interactionActions().quiesceNow();
                                runtime.boatActions().quiesceNow();
                                runtime.minecartActions().quiesceNow();
                                runtime.skillSupervisor()
                                        .abandonForModelDisconnect();
                            }
                            final var report =
                                runtime.survival().tick(
                                    modelControlEnabled
                                        && runtime.skillSupervisor()
                                            .activeSkillManagesVisibleHostileProximity(),
                                    modelControlEnabled
                                        && runtime.skillSupervisor()
                                            .activeSkillManagesPhysicalContactThreats(),
                                    modelControlEnabled
                                        && runtime.skillSupervisor()
                                            .activeSkillManagesVisibleProjectileThreats()
                                );
                            survival.set(report);
                            if (modelControlEnabled
                                    && report.intervened()
                                    && !activeBefore) {
                                /*
                                 * Emergency reflexes own all body writes this
                                 * tick, but they must not starve the model
                                 * control plane that can authorize the local
                                 * combat skill which resolves the danger.
                                 */
                                runtime.brain().tickPlanningOnly();
                            }
                            return report.intervened()
                                ? BehaviorArbiter.Attempt.claim(
                                    "survival_"
                                        + report.state().name()
                                        + "_" + report.reason()
                                )
                                : BehaviorArbiter.Attempt.pass();
                        }
                    )
        );
        /*
         * A skill that was already authorized while the gateway was healthy
         * still needs one ordinary server tick after a disconnect.  The
         * supervisor will see modelConnected=false, finish only its current
         * low-risk atomic segment, and stop at a safe checkpoint.  Skipping
         * this lane whenever the gateway is offline left the skill marked
         * RUNNING forever while the finally block released every body input;
         * from the player's view that was exactly "it said it would go, then
         * stood still".  This branch never starts a new skill or planner
         * request: it is only an offline teardown path for an already-active
         * skill.  If an emergency lane claims the tick first, it preempts the
         * stale skill below before taking body ownership.
         */
        if (modelControlEnabled
                || isActive(runtime.skillSupervisor().snapshot())) {
            candidates.add(
                    new BehaviorArbiter.Candidate(
                        BehaviorArbiter.Lane.ACTIVE_SKILL,
                        () -> {
                            final boolean before = isActive(
                                runtime.skillSupervisor().snapshot()
                            );
                            runtime.brain().tick();
                            final var afterSnapshot =
                                runtime.skillSupervisor().snapshot();
                            final boolean after = isActive(
                                afterSnapshot
                            );
                            skillEnded.set(before && !after);
                            if (!before && !after) {
                                return BehaviorArbiter.Attempt.pass();
                            }
                            final String name =
                                afterSnapshot.skillName().isBlank()
                                    ? "transition"
                                    : afterSnapshot.skillName();
                            return BehaviorArbiter.Attempt.claim(
                                "skill_" + name
                            );
                        }
                    )
            );
        }
        /*
         * Wearing an already-owned armor upgrade, shield, or better ordinary
         * weapon is a bounded vanilla inventory transaction, not a high-level
         * gameplay decision. Keep it available when the provider is offline;
         * this lets a freshly received item become useful without pretending
         * that the model can move or speak.
         */
        candidates.add(
                new BehaviorArbiter.Candidate(
                    BehaviorArbiter.Lane.IDLE_EQUIPMENT,
                    () -> {
                        final var report =
                            runtime.idleEquipment().tick();
                        return report.intervened()
                            ? BehaviorArbiter.Attempt.claim(
                                "idle_" + report.reason()
                            )
                            : BehaviorArbiter.Attempt.pass();
                    }
                )
        );
        final BehaviorArbiter.Resolution resolution =
            runtime.behaviorArbiter().arbitrate(currentTick, candidates);
        return new BehaviorCycle(
            resolution,
            survival.get(),
            skillEnded.get()
        );
    }

    private record BehaviorCycle(
        BehaviorArbiter.Resolution resolution,
        EmergencySurvivalController.TickReport survivalReport,
        boolean skillEnded
    ) {
        private BehaviorCycle {
            java.util.Objects.requireNonNull(
                resolution,
                "resolution"
            );
            java.util.Objects.requireNonNull(
                survivalReport,
                "survivalReport"
            );
        }
    }

    private static void logRuntimeFailureSafely(
            final net.minecraft.server.MinecraftServer server,
            final RuntimeException exception
    ) {
        final long now = Integer.toUnsignedLong(server.getTickCount());
        if (lastRuntimeFailureLogTick != Long.MIN_VALUE
                && now >= lastRuntimeFailureLogTick
                && now - lastRuntimeFailureLogTick < 200L) {
            suppressedRuntimeFailureLogs++;
            return;
        }
        final long suppressed = suppressedRuntimeFailureLogs;
        suppressedRuntimeFailureLogs = 0L;
        lastRuntimeFailureLogTick = now;
        MinecraftAiCompanion.LOGGER.error(
                "Companion runtime tick failed safely: type={}, site={}, "
                    + "suppressedSinceLast={}; exception messages, model "
                    + "content and credentials are withheld",
                exception.getClass().getSimpleName(),
                safeFailureSite(exception),
                suppressed
        );
    }

    private static ModelProfileStore.Profile startupModelProfile(
            final ModelProfileStore profileStore
    ) {
        final Optional<ModelProfileStore.Profile> stored =
            Objects.requireNonNull(
                profileStore,
                "profileStore"
            ).load();
        final Optional<ModelProfileStore.Profile> injected =
                injectedModelProfile(System.getenv());
        if (injected.isPresent()) {
            return withCachedCapabilities(
                    injected.orElseThrow(),
                    stored
            );
        }
        final String configuredBaseUrl =
                CompanionConfig.MODEL_BASE_URL.get();
        final String configuredModelName =
                CompanionConfig.MODEL_NAME.get();
        try {
            final var validated = new EndpointValidator().validate(
                    configuredBaseUrl,
                    configuredModelName
            );
            return withCachedCapabilities(
                new ModelProfileStore.Profile(
                    validated.baseUri().toASCIIString(),
                    validated.modelName()
                ),
                stored
            );
        } catch (EndpointValidationException ignored) {
            return stored.orElseGet(() ->
                    new ModelProfileStore.Profile(
                            configuredBaseUrl,
                            configuredModelName
                    )
            );
        }
    }

    /**
     * Parses the non-secret endpoint coordinates used by dedicated-server
     * automation and secret managers.
     *
     * <p>Both values must be present and pass the same production endpoint
     * validator as the setup UI. A partial or invalid override is ignored,
     * allowing the persisted profile/config to remain authoritative. The API
     * key is handled separately by {@link ApiKeyManager}; it is never
     * returned or logged here.</p>
     */
    static Optional<ModelProfileStore.Profile> injectedModelProfile(
            final Map<String, String> environment
    ) {
        Objects.requireNonNull(environment, "environment");
        final String baseUrl = environment.getOrDefault(
                "MCAI_BASE_URL",
                ""
        ).strip();
        final String modelName = environment.getOrDefault(
                "MCAI_MODEL",
                ""
        ).strip();
        if (baseUrl.isEmpty() && modelName.isEmpty()) {
            return Optional.empty();
        }
        if (baseUrl.isEmpty() || modelName.isEmpty()) {
            MinecraftAiCompanion.LOGGER.warn(
                    "Ignoring partial model environment override; "
                            + "MCAI_BASE_URL and MCAI_MODEL must both be set"
            );
            return Optional.empty();
        }
        try {
            final var validated = new EndpointValidator().validate(
                    baseUrl,
                    modelName
            );
            return Optional.of(new ModelProfileStore.Profile(
                    validated.baseUri().toASCIIString(),
                    validated.modelName()
            ));
        } catch (EndpointValidationException exception) {
            MinecraftAiCompanion.LOGGER.warn(
                    "Ignoring invalid model environment override; "
                            + "endpoint values are withheld"
            );
            return Optional.empty();
        }
    }

    private static ModelProfileStore.Profile withCachedCapabilities(
            final ModelProfileStore.Profile selected,
            final Optional<ModelProfileStore.Profile> stored
    ) {
        return stored
                .filter(candidate ->
                    candidate.baseUrl().equals(selected.baseUrl())
                        && candidate.modelName().equals(
                            selected.modelName()
                        )
                )
                .map(candidate -> new ModelProfileStore.Profile(
                    selected.baseUrl(),
                    selected.modelName(),
                    candidate.capabilities()
                ))
                .orElse(selected);
    }

    private static String safeFailureSite(
            final RuntimeException exception
    ) {
        for (StackTraceElement element : exception.getStackTrace()) {
            if (element.getClassName().startsWith(
                    "dev.mcai.companion."
            )) {
                return element.getClassName()
                        + "#" + element.getMethodName()
                        + ":" + Math.max(0, element.getLineNumber());
            }
        }
        return "external";
    }

    private static boolean isActive(
        final SkillSupervisor.Snapshot snapshot
    ) {
        return snapshot.state() == SkillSupervisor.State.RUNNING
            || snapshot.state() == SkillSupervisor.State.CANCEL_PENDING;
    }

    private static void onServerStopping(final ServerStoppingEvent event) {
        closeRuntime(event.getServer());
    }

    /**
     * Crash-path fallback: Forge can skip ServerStoppingEvent when the server
     * loop exits exceptionally, but still emits ServerStoppedEvent in its
     * finally path.
     */
    private static void onServerStopped(final ServerStoppedEvent event) {
        closeRuntime(event.getServer());
    }

    private static void closeRuntime(
        final net.minecraft.server.MinecraftServer server
    ) {
        final ServerRuntime candidate = ACTIVE.get();
        if (candidate == null || candidate.server() != server
            || !ACTIVE.compareAndSet(candidate, null)) {
            return;
        }
        final ServerRuntime runtime = candidate;
        if (runtime != null) {
            appendRuntimeLifecycleAudit(runtime, "stopping");
            runtime.close();
            MinecraftAiCompanion.LOGGER.info("Closed companion runtime");
        }
    }
}
