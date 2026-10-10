package dev.mcai.companion.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcai.companion.brain.BrainObservation;
import dev.mcai.companion.brain.ObservationProvider;
import dev.mcai.companion.brain.ObservationRequestStatus;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.memory.transport.AsyncVerifiedPortalEdgeRecall;
import dev.mcai.companion.memory.transport.VerifiedPortalEdgeRecallJsonCodec;
import dev.mcai.companion.memory.transport.VerifiedPortalEdgeRecallSnapshot;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.PerceptionNavMapper;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.FairPerceptionSampler;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.perception.SemanticObservationJsonCodec;
import dev.mcai.companion.progression.SurvivalRouteJsonCodec;
import dev.mcai.companion.progression.SurvivalRouteSnapshot;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillSupervisor;
import dev.mcai.companion.skills.memory.WaypointRecallSnapshot;
import dev.mcai.companion.skills.memory.WaypointRecallJsonCodec;
import dev.mcai.companion.skills.survey.SurveyResultJsonCodec;
import dev.mcai.companion.skills.survey.SurveyResultSnapshot;
import dev.mcai.companion.skills.stronghold.EyeTraceHistorySnapshot;
import dev.mcai.companion.skills.stronghold.EyeTraceJsonCodec;
import dev.mcai.companion.model.ObservationKind;
import dev.mcai.companion.model.RequestedObservation;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-thread observation bridge: body safety is refreshed at 20 TPS while
 * the more expensive first-person semantic ray sample is capped at 4 Hz.
 */
public final class MinecraftObservationProvider implements ObservationProvider {
    public static final int DEFAULT_SEMANTIC_INTERVAL_TICKS = 5;
    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();
    private static final VerifiedPortalEdgeRecallJsonCodec
        PORTAL_RECALL_CODEC = new VerifiedPortalEdgeRecallJsonCodec();
    private static final WaypointRecallJsonCodec WAYPOINT_RECALL_CODEC =
        new WaypointRecallJsonCodec();
    private static final SurveyResultJsonCodec SURVEY_RESULT_CODEC =
        new SurveyResultJsonCodec();
    private static final EyeTraceJsonCodec EYE_TRACE_CODEC =
        new EyeTraceJsonCodec();
    private static final SurvivalRouteJsonCodec SURVIVAL_ROUTE_CODEC =
        new SurvivalRouteJsonCodec();

    private final MinecraftServer server;
    private final SkillSupervisor skills;
    private final BooleanSupplier modelConnected;
    private final FairPerceptionSampler sampler;
    private final SemanticObservationJsonCodec jsonCodec;
    private final PerceptionNavMapper navigationMapper;
    private final Consumer<SemanticObservation> semanticObserver;
    private final LongFunction<List<String>> progressJournal;
    private final LongFunction<WaypointRecallSnapshot> waypointRecall;
    private final Optional<AsyncVerifiedPortalEdgeRecall> portalEdgeRecall;
    private final LongFunction<Optional<SurveyResultSnapshot>>
        surveyResults;
    private final LongFunction<Optional<EyeTraceHistorySnapshot>>
        eyeTraceResults;
    private final LongFunction<Optional<SurvivalRouteSnapshot>>
        routeResults;
    private final DecisionEpochTracker<Fingerprint> epochs;
    private final int semanticIntervalTicks;

    private SemanticObservation latest;
    private String latestJson = "";
    private Fingerprint latestFingerprint;
    private long lastSemanticGameTick = Long.MIN_VALUE;
    private long lastGoalRevision = -1;
    private boolean semanticRefreshRequested;
    private ObservationKind lastRequestedObservation =
            ObservationKind.NONE;
    private ObservationRequestStatus lastObservationRequestStatus =
            ObservationRequestStatus.REJECTED;
    private long lastBodySessionGeneration = -1;
    private Function<RequestedObservation, ObservationRequestStatus>
            activeVisionRequester = ignored ->
                    ObservationRequestStatus.UNSUPPORTED;

    public void attachActiveVisionRequester(
            final Function<RequestedObservation, ObservationRequestStatus>
                    requester
    ) {
        requireServerThread();
        activeVisionRequester = Objects.requireNonNull(
                requester,
                "requester"
        );
    }
    public MinecraftObservationProvider(
        final MinecraftServer server,
        final SkillSupervisor skills,
        final BooleanSupplier modelConnected
    ) {
        this(
            server,
            skills,
            modelConnected,
            new FairPerceptionSampler(),
            new SemanticObservationJsonCodec(),
            new PerceptionNavMapper(),
            ignored -> {
            },
            ignored -> List.of(),
            ignored -> WaypointRecallSnapshot.empty(),
            Optional.empty(),
            new DecisionEpochTracker<>(),
            DEFAULT_SEMANTIC_INTERVAL_TICKS
        );
    }

    public MinecraftObservationProvider(
        final MinecraftServer server,
        final SkillSupervisor skills,
        final BooleanSupplier modelConnected,
        final Consumer<SemanticObservation> semanticObserver
    ) {
        this(
            server,
            skills,
            modelConnected,
            new FairPerceptionSampler(),
            new SemanticObservationJsonCodec(),
            new PerceptionNavMapper(),
            semanticObserver,
            ignored -> List.of(),
            ignored -> WaypointRecallSnapshot.empty(),
            Optional.empty(),
            new DecisionEpochTracker<>(),
            DEFAULT_SEMANTIC_INTERVAL_TICKS
        );
    }

    public MinecraftObservationProvider(
        final MinecraftServer server,
        final SkillSupervisor skills,
        final BooleanSupplier modelConnected,
        final Consumer<SemanticObservation> semanticObserver,
        final LongFunction<List<String>> progressJournal
    ) {
        this(
            server,
            skills,
            modelConnected,
            new FairPerceptionSampler(),
            new SemanticObservationJsonCodec(),
            new PerceptionNavMapper(),
            semanticObserver,
            progressJournal,
            ignored -> WaypointRecallSnapshot.empty(),
            Optional.empty(),
            new DecisionEpochTracker<>(),
            DEFAULT_SEMANTIC_INTERVAL_TICKS
        );
    }

    public MinecraftObservationProvider(
        final MinecraftServer server,
        final SkillSupervisor skills,
        final BooleanSupplier modelConnected,
        final Consumer<SemanticObservation> semanticObserver,
        final LongFunction<List<String>> progressJournal,
        final LongFunction<WaypointRecallSnapshot> waypointRecall
    ) {
        this(
            server,
            skills,
            modelConnected,
            new FairPerceptionSampler(),
            new SemanticObservationJsonCodec(),
            new PerceptionNavMapper(),
            semanticObserver,
            progressJournal,
            waypointRecall,
            Optional.empty(),
            new DecisionEpochTracker<>(),
            DEFAULT_SEMANTIC_INTERVAL_TICKS
        );
    }

    public MinecraftObservationProvider(
        final MinecraftServer server,
        final SkillSupervisor skills,
        final BooleanSupplier modelConnected,
        final Consumer<SemanticObservation> semanticObserver,
        final LongFunction<List<String>> progressJournal,
        final LongFunction<WaypointRecallSnapshot> waypointRecall,
        final AsyncVerifiedPortalEdgeRecall portalEdgeRecall
    ) {
        this(
            server,
            skills,
            modelConnected,
            semanticObserver,
            progressJournal,
            waypointRecall,
            portalEdgeRecall,
            ignored -> Optional.empty()
        );
    }

    public MinecraftObservationProvider(
        final MinecraftServer server,
        final SkillSupervisor skills,
        final BooleanSupplier modelConnected,
        final Consumer<SemanticObservation> semanticObserver,
        final LongFunction<List<String>> progressJournal,
        final LongFunction<WaypointRecallSnapshot> waypointRecall,
        final AsyncVerifiedPortalEdgeRecall portalEdgeRecall,
        final LongFunction<Optional<SurveyResultSnapshot>> surveyResults
    ) {
        this(
            server,
            skills,
            modelConnected,
            semanticObserver,
            progressJournal,
            waypointRecall,
            portalEdgeRecall,
            surveyResults,
            ignored -> Optional.empty()
        );
    }

    public MinecraftObservationProvider(
        final MinecraftServer server,
        final SkillSupervisor skills,
        final BooleanSupplier modelConnected,
        final Consumer<SemanticObservation> semanticObserver,
        final LongFunction<List<String>> progressJournal,
        final LongFunction<WaypointRecallSnapshot> waypointRecall,
        final AsyncVerifiedPortalEdgeRecall portalEdgeRecall,
        final LongFunction<Optional<SurveyResultSnapshot>> surveyResults,
        final LongFunction<Optional<EyeTraceHistorySnapshot>>
            eyeTraceResults
    ) {
        this(
            server,
            skills,
            modelConnected,
            semanticObserver,
            progressJournal,
            waypointRecall,
            portalEdgeRecall,
            surveyResults,
            eyeTraceResults,
            ignored -> Optional.empty()
        );
    }

    public MinecraftObservationProvider(
        final MinecraftServer server,
        final SkillSupervisor skills,
        final BooleanSupplier modelConnected,
        final Consumer<SemanticObservation> semanticObserver,
        final LongFunction<List<String>> progressJournal,
        final LongFunction<WaypointRecallSnapshot> waypointRecall,
        final AsyncVerifiedPortalEdgeRecall portalEdgeRecall,
        final LongFunction<Optional<SurveyResultSnapshot>> surveyResults,
        final LongFunction<Optional<EyeTraceHistorySnapshot>>
            eyeTraceResults,
        final LongFunction<Optional<SurvivalRouteSnapshot>> routeResults
    ) {
        this(
            server,
            skills,
            modelConnected,
            new FairPerceptionSampler(),
            new SemanticObservationJsonCodec(),
            new PerceptionNavMapper(),
            semanticObserver,
            progressJournal,
            waypointRecall,
            Optional.of(Objects.requireNonNull(
                portalEdgeRecall,
                "portalEdgeRecall"
            )),
            surveyResults,
            eyeTraceResults,
            routeResults,
            new DecisionEpochTracker<>(),
            DEFAULT_SEMANTIC_INTERVAL_TICKS
        );
    }

    MinecraftObservationProvider(
        final MinecraftServer server,
        final SkillSupervisor skills,
        final BooleanSupplier modelConnected,
        final FairPerceptionSampler sampler,
        final SemanticObservationJsonCodec jsonCodec,
        final PerceptionNavMapper navigationMapper,
        final Consumer<SemanticObservation> semanticObserver,
        final LongFunction<List<String>> progressJournal,
        final LongFunction<WaypointRecallSnapshot> waypointRecall,
        final Optional<AsyncVerifiedPortalEdgeRecall> portalEdgeRecall,
        final DecisionEpochTracker<Fingerprint> epochs,
        final int semanticIntervalTicks
    ) {
        this(
            server,
            skills,
            modelConnected,
            sampler,
            jsonCodec,
            navigationMapper,
            semanticObserver,
            progressJournal,
            waypointRecall,
            portalEdgeRecall,
            ignored -> Optional.empty(),
            ignored -> Optional.empty(),
            ignored -> Optional.empty(),
            epochs,
            semanticIntervalTicks
        );
    }

    MinecraftObservationProvider(
        final MinecraftServer server,
        final SkillSupervisor skills,
        final BooleanSupplier modelConnected,
        final FairPerceptionSampler sampler,
        final SemanticObservationJsonCodec jsonCodec,
        final PerceptionNavMapper navigationMapper,
        final Consumer<SemanticObservation> semanticObserver,
        final LongFunction<List<String>> progressJournal,
        final LongFunction<WaypointRecallSnapshot> waypointRecall,
        final Optional<AsyncVerifiedPortalEdgeRecall> portalEdgeRecall,
        final LongFunction<Optional<SurveyResultSnapshot>> surveyResults,
        final LongFunction<Optional<EyeTraceHistorySnapshot>>
            eyeTraceResults,
        final LongFunction<Optional<SurvivalRouteSnapshot>> routeResults,
        final DecisionEpochTracker<Fingerprint> epochs,
        final int semanticIntervalTicks
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.modelConnected = Objects.requireNonNull(modelConnected, "modelConnected");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.navigationMapper = Objects.requireNonNull(
            navigationMapper,
            "navigationMapper"
        );
        this.semanticObserver = Objects.requireNonNull(
            semanticObserver,
            "semanticObserver"
        );
        this.progressJournal = Objects.requireNonNull(
            progressJournal,
            "progressJournal"
        );
        this.waypointRecall = Objects.requireNonNull(
            waypointRecall,
            "waypointRecall"
        );
        this.portalEdgeRecall = Objects.requireNonNull(
            portalEdgeRecall,
            "portalEdgeRecall"
        );
        this.surveyResults = Objects.requireNonNull(
            surveyResults,
            "surveyResults"
        );
        this.eyeTraceResults = Objects.requireNonNull(
            eyeTraceResults,
            "eyeTraceResults"
        );
        this.routeResults = Objects.requireNonNull(
            routeResults,
            "routeResults"
        );
        this.epochs = Objects.requireNonNull(epochs, "epochs");
        if (semanticIntervalTicks < 1 || semanticIntervalTicks > 20) {
            throw new IllegalArgumentException(
                "semanticIntervalTicks must be between 1 and 20"
            );
        }
        this.semanticIntervalTicks = semanticIntervalTicks;
    }

    @Override
    public BrainObservation observe(final GoalSnapshot goal) {
        final ServerPlayer player = refreshSemantic(goal);
        final long gameTick = player.level().getGameTime();

        final SkillSupervisor.Snapshot skill = skills.snapshot();
        final DimensionRef currentDimension = DimensionRef.parse(
            player.level().dimension().identifier().toString()
        );
        final PerceptionVec3 currentPosition = new PerceptionVec3(
            player.getX(),
            player.getY(),
            player.getZ()
        );
        portalEdgeRecall.ifPresent(recall ->
            recall.refresh(currentDimension, currentPosition, gameTick)
        );
        final Optional<VerifiedPortalEdgeRecallSnapshot> portalRecall =
            portalEdgeRecall.flatMap(recall ->
                recall.snapshot(currentDimension, currentPosition)
            );
        final Optional<SurveyResultSnapshot> surveyResult =
            Objects.requireNonNull(
                surveyResults.apply(goal.revision()),
                "surveyResults returned null"
            );
        final Optional<EyeTraceHistorySnapshot> eyeTraceResult =
            Objects.requireNonNull(
                eyeTraceResults.apply(goal.revision()),
                "eyeTraceResults returned null"
            );
        final Optional<SurvivalRouteSnapshot> routeResult =
                Objects.requireNonNull(
                    routeResults.apply(goal.revision()),
                    "routeResults returned null"
                );
        final String routePhase = routePhaseSignature(routeResult);
        /*
         * An active atomic skill owns its server-authoritative action window.
         * Route milestones may change while that skill is doing exactly what
         * it was asked to do (for example, collecting the first pearl or
         * recording a newly verified resource).  Releasing the frozen epoch
         * for those ordinary progress changes rebinds the SkillContext to a
         * newer fingerprint and makes the supervisor report
         * stale_world_revision on the very tick the skill completes.  Keep
         * the decision epoch bound for every active skill; the supervisor's
         * skill-specific capability remains the explicit documentation for
         * bounded dimension/route transitions, while completion itself
         * releases the epoch on the following observation.
         */
        final OptionalLong frozenEpoch = isActive(skill)
            ? OptionalLong.of(skill.boundWorldRevision())
            : OptionalLong.empty();
        final Fingerprint decisionFingerprint =
            latestFingerprint.withCurrentBody(player);
        final long epoch = epochs.update(
            goal.revision(),
            decisionFingerprint.withRoutePhase(routePhase),
            frozenEpoch
        );
        return new BrainObservation(
            epoch,
            new SkillContext(
                goal.revision(),
                epoch,
                gameTick,
                server.isHardcore(),
                modelConnected.getAsBoolean(),
                currentRisk(player, latest.dangers())
            ),
            latestJson,
            encodeTrustedRuntime(
                skill,
                progressJournal.apply(goal.revision()),
                waypointRecall.apply(goal.revision()),
                portalRecall,
                surveyResult,
                eyeTraceResult,
                routeResult,
                lastRequestedObservation,
                lastObservationRequestStatus
            )
        );
    }

    /**
     * Refreshes the 4 Hz first-person semantic/body-frame boundary without
     * serializing planner-only trusted runtime data.
     *
     * <p>The conversation lane needs current position and terrain before it
     * consumes player chat, while the brain later calls {@link #observe} in
     * the same server tick. Keeping this operation separate prevents route,
     * waypoint, skill and progress JSON from being rebuilt twice per tick.</p>
     *
     * @return the authoritative online companion body used for the refresh
     */
    public ServerPlayer refreshSemantic(final GoalSnapshot goal) {
        Objects.requireNonNull(goal, "goal");
        requireServerThread();
        final ServerPlayer player = AiPlayerManager.onlinePlayer(server)
            .orElseThrow(() -> new IllegalStateException("Companion body is offline"));
        final long bodySessionGeneration =
                AiPlayerManager.status(server).sessionGeneration();
        if (bodySessionGeneration != lastBodySessionGeneration) {
            resetBodyLocalState();
            lastBodySessionGeneration = bodySessionGeneration;
        }
        final long gameTick = player.level().getGameTime();
        final boolean goalChanged = goal.revision() != lastGoalRevision;
        if (mustRefreshSemantic(player, gameTick, goalChanged)) {
            latest = sampler.sample(player);
            latestJson = jsonCodec.encode(latest);
            latestFingerprint = Fingerprint.from(latest);
            navigationMapper.ingest(latest);
            semanticObserver.accept(latest);
            lastSemanticGameTick = gameTick;
            lastGoalRevision = goal.revision();
        }
        return player;
    }

    /**
     * Returns the navigation boundary derived from the same latest fair
     * semantic observation exposed by {@link #observe(GoalSnapshot)}.
     *
     * <p>This is intentionally read-only and does not sample chunks or
     * blocks. It exists for in-process audits and GameTest diagnostics that
     * need to explain why a fail-closed route was rejected.</p>
     */
    public Optional<LocalNavSnapshot> navigationSnapshot() {
        requireServerThread();
        if (latest == null) {
            return Optional.empty();
        }
        return Optional.of(navigationMapper.snapshot());
    }

    @Override
    public ObservationRequestStatus requestObservation(
            final RequestedObservation request
    ) {
        Objects.requireNonNull(request, "request");
        requireServerThread();
        if (request.kind() == ObservationKind.SEMANTIC_REFRESH) {
            semanticRefreshRequested = true;
            lastRequestedObservation = request.kind();
            lastObservationRequestStatus =
                    ObservationRequestStatus.ACCEPTED;
            return ObservationRequestStatus.ACCEPTED;
        }
        final ObservationRequestStatus status;
        if (request.kind() == ObservationKind.SCREENSHOT_LOW) {
            status = Objects.requireNonNull(
                    activeVisionRequester.apply(request),
                    "activeVisionRequester returned null"
            );
        } else {
            status = request.kind() == ObservationKind.NONE
                    ? ObservationRequestStatus.REJECTED
                    : ObservationRequestStatus.UNSUPPORTED;
        }
        lastRequestedObservation = request.kind();
        lastObservationRequestStatus = status;
        return status;
    }

    public Optional<LocalNavSnapshot> latestNavigationSnapshot() {
        requireServerThread();
        if (latest == null) {
            return Optional.empty();
        }
        return Optional.of(navigationMapper.snapshot());
    }

    /**
     * Invalidates immediate perception when the authoritative body session
     * changes. Long-term waypoints remain intact; only evidence that belonged
     * to the old player's eyes is discarded.
     */
    public void invalidateBodySession() {
        requireServerThread();
        lastBodySessionGeneration = -1;
        resetBodyLocalState();
    }

    private void resetBodyLocalState() {
        latest = null;
        latestJson = "";
        latestFingerprint = null;
        navigationMapper.reset();
        lastSemanticGameTick = Long.MIN_VALUE;
        lastGoalRevision = -1;
        semanticRefreshRequested = true;
        lastRequestedObservation = ObservationKind.NONE;
        lastObservationRequestStatus =
                ObservationRequestStatus.REJECTED;
    }

    /**
     * Returns the decision-validity epoch most recently exposed to the brain.
     * This is intentionally distinct from both the game tick and goal
     * revision, and is suitable for audit-event correlation.
     */
    public long latestDecisionEpoch() {
        requireServerThread();
        // Chat, MCP and audit events can arrive in the spawn tick before the
        // first semantic refresh. Epoch zero is also the value assigned by
        // that first refresh, so this avoids a startup race without
        // pretending that a newer observation exists.
        return epochs.currentOrInitial();
    }

    /**
     * Latest ordinary first-person semantic JSON for the low-latency
     * conversation lane. Reading it never triggers a new sample.
     */
    public Optional<String> latestSemanticJson() {
        requireServerThread();
        return latestJson.isEmpty()
                ? Optional.empty()
                : Optional.of(latestJson);
    }

    private boolean mustRefreshSemantic(
        final ServerPlayer player,
        final long gameTick,
        final boolean goalChanged
    ) {
        if (latest == null
                || goalChanged
                || semanticRefreshRequested
                || gameTick < lastSemanticGameTick) {
            semanticRefreshRequested = false;
            return true;
        }
        if (latestFingerprint.criticalBodyChanged(player)) {
            return true;
        }
        return gameTick - lastSemanticGameTick >= semanticIntervalTicks;
    }

    private static boolean isActive(final SkillSupervisor.Snapshot snapshot) {
        return snapshot.state() == SkillSupervisor.State.RUNNING
            || snapshot.state() == SkillSupervisor.State.CANCEL_PENDING;
    }

    /**
     * Gives the planner bounded, locally-authored feedback about the previous
     * atomic action. This is kept outside semantic world data so a sign,
     * waypoint label, item name, or chat message cannot impersonate a skill
     * result.
     */
    static String encodeTrustedRuntime(
        final SkillSupervisor.Snapshot skill,
        final List<String> modelAuthoredProgress,
        final WaypointRecallSnapshot waypointRecall,
        final Optional<VerifiedPortalEdgeRecallSnapshot> portalRecall,
        final ObservationKind lastRequestedObservation,
        final ObservationRequestStatus observationRequestStatus
    ) {
        return encodeTrustedRuntime(
            skill,
            modelAuthoredProgress,
            waypointRecall,
            portalRecall,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            lastRequestedObservation,
            observationRequestStatus
        );
    }

    static String encodeTrustedRuntime(
        final SkillSupervisor.Snapshot skill,
        final List<String> modelAuthoredProgress,
        final WaypointRecallSnapshot waypointRecall,
        final Optional<VerifiedPortalEdgeRecallSnapshot> portalRecall,
        final Optional<SurveyResultSnapshot> surveyResult,
        final ObservationKind lastRequestedObservation,
        final ObservationRequestStatus observationRequestStatus
    ) {
        return encodeTrustedRuntime(
            skill,
            modelAuthoredProgress,
            waypointRecall,
            portalRecall,
            surveyResult,
            Optional.empty(),
            Optional.empty(),
            lastRequestedObservation,
            observationRequestStatus
        );
    }

    static String encodeTrustedRuntime(
        final SkillSupervisor.Snapshot skill,
        final List<String> modelAuthoredProgress,
        final WaypointRecallSnapshot waypointRecall,
        final Optional<VerifiedPortalEdgeRecallSnapshot> portalRecall,
        final Optional<SurveyResultSnapshot> surveyResult,
        final Optional<EyeTraceHistorySnapshot> eyeTraceResult,
        final ObservationKind lastRequestedObservation,
        final ObservationRequestStatus observationRequestStatus
    ) {
        return encodeTrustedRuntime(
            skill,
            modelAuthoredProgress,
            waypointRecall,
            portalRecall,
            surveyResult,
            eyeTraceResult,
            Optional.empty(),
            lastRequestedObservation,
            observationRequestStatus
        );
    }

    static String encodeTrustedRuntime(
        final SkillSupervisor.Snapshot skill,
        final List<String> modelAuthoredProgress,
        final WaypointRecallSnapshot waypointRecall,
        final Optional<VerifiedPortalEdgeRecallSnapshot> portalRecall,
        final Optional<SurveyResultSnapshot> surveyResult,
        final Optional<EyeTraceHistorySnapshot> eyeTraceResult,
        final Optional<SurvivalRouteSnapshot> routeResult,
        final ObservationKind lastRequestedObservation,
        final ObservationRequestStatus observationRequestStatus
    ) {
        final JsonObject root = new JsonObject();
        root.addProperty("skillState", skill.state().name());
        if (!skill.skillName().isEmpty()) {
            root.addProperty("skillName", skill.skillName());
        }
        root.addProperty("executedTicks", skill.executedTicks());
        root.addProperty("safeCheckpointSequence", skill.checkpointSequence());
        root.addProperty("cancelPending", skill.cancelPending());
        root.addProperty("modelDisconnectPending", skill.disconnectedPending());
        skill.terminalResult().ifPresent(result -> {
            root.addProperty("terminalStatus", result.status().name());
            result.failure().ifPresent(failure ->
                root.addProperty("failureCode", failure.code())
            );
        });
        skill.checkpointPersistenceFailure().ifPresent(failure ->
            root.addProperty("checkpointFailureCode", failure.code())
        );
        skill.lastStartRejection().ifPresent(failure ->
            root.addProperty(
                "lastSkillStartRejectionCode",
                failure.code()
            )
        );
        root.add(
            "modelAuthoredProgress",
            GSON.toJsonTree(List.copyOf(modelAuthoredProgress))
        );
        final List<String> omitted = new ArrayList<>();
        addTrustedFieldWithinBudget(
            root,
            "recalledWaypointData",
            JsonParser.parseString(
                WAYPOINT_RECALL_CODEC.encode(
                    Objects.requireNonNull(
                        waypointRecall,
                        "waypointRecall"
                    )
                )
            ),
            omitted
        );
        Objects.requireNonNull(portalRecall, "portalRecall")
            .ifPresent(snapshot -> addTrustedFieldWithinBudget(
                root,
                "recalledVerifiedPortalEdgeData",
                JsonParser.parseString(
                    PORTAL_RECALL_CODEC.encode(snapshot)
                ),
                omitted
            ));
        Objects.requireNonNull(surveyResult, "surveyResult")
            .ifPresent(snapshot -> addTrustedFieldWithinBudget(
                root,
                "recentFairSurveyData",
                JsonParser.parseString(
                    SURVEY_RESULT_CODEC.encode(snapshot)
                ),
                omitted
            ));
        Objects.requireNonNull(eyeTraceResult, "eyeTraceResult")
            .ifPresent(snapshot -> addTrustedFieldWithinBudget(
                root,
                "recentFairEyeTraceData",
                JsonParser.parseString(
                    EYE_TRACE_CODEC.encode(snapshot)
                ),
                omitted
            ));
        Objects.requireNonNull(routeResult, "routeResult")
            .ifPresent(snapshot -> addTrustedFieldWithinBudget(
                root,
                "verifiedCompletionRouteData",
                JsonParser.parseString(
                    SURVIVAL_ROUTE_CODEC.encode(snapshot)
                ),
                omitted
            ));
        if (lastRequestedObservation != ObservationKind.NONE) {
            final JsonObject request = new JsonObject();
            request.addProperty(
                    "kind",
                    lastRequestedObservation.name()
            );
            request.addProperty(
                    "status",
                    observationRequestStatus.name()
            );
            root.add("lastObservationRequest", request);
        }
        if (!omitted.isEmpty()) {
            final JsonArray names = new JsonArray();
            omitted.forEach(names::add);
            root.add("omittedTrustedRuntimeData", names);
        }
        final String encoded = GSON.toJson(root);
        if (encoded.length()
                > BrainObservation.MAX_TRUSTED_RUNTIME_JSON_CHARACTERS) {
            throw new IllegalStateException(
                "Mandatory trusted runtime exceeds its total budget"
            );
        }
        return encoded;
    }

    /**
     * Adds one independently bounded projection only when the aggregate
     * trusted-runtime envelope still fits. Individual codec bounds cannot be
     * summed: waypoint, portal, survey, eye-trace and route data may all be
     * present in the same tick.
     */
    private static void addTrustedFieldWithinBudget(
            final JsonObject root,
            final String name,
            final JsonElement value,
            final List<String> omitted
    ) {
        /*
         * Reserve room for the explicit omission marker and the small
         * last-observation status that is appended after optional fields.
         */
        final int reserve = 512;
        root.add(name, value);
        if (GSON.toJson(root).length()
                > BrainObservation.MAX_TRUSTED_RUNTIME_JSON_CHARACTERS
                    - reserve) {
            root.remove(name);
            omitted.add(name);
        }
    }

    private static double currentRisk(
        final ServerPlayer player,
        final List<DangerSignal> sampledDangers
    ) {
        double risk = sampledDangers.stream()
            .mapToDouble(DangerSignal::severity)
            .max()
            .orElse(0.0);
        if (player.isOnFire()) {
            risk = 1.0;
        }
        if (!player.onGround() && !player.isInWater() && player.fallDistance > 3.0F) {
            risk = Math.max(risk, Math.min(1.0, player.fallDistance / 20.0));
        }
        if (player.getAirSupply() < player.getMaxAirSupply() / 4) {
            risk = Math.max(risk, 0.75);
        }
        final double healthRatio = player.getHealth() / player.getMaxHealth();
        if (healthRatio < 0.25) {
            risk = Math.max(risk, 0.9);
        } else if (healthRatio < 0.5) {
            risk = Math.max(risk, 0.5);
        }
        return Math.min(1.0, Math.max(0.0, risk));
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                "Minecraft observations must run on the server thread"
            );
        }
    }

    /**
     * Route milestones are server-verified capability boundaries.  They are
     * deliberately part of the decision epoch, so an in-flight response
     * produced for "gather wood" cannot be applied after the route has
     * advanced to "prepare crafting".  Volatile counts, elapsed time and
     * prose are excluded to avoid invalidating useful high-level decisions
     * every tick.
     */
    private static String routePhaseSignature(
        final Optional<SurvivalRouteSnapshot> route
    ) {
        return route.map(snapshot ->
                snapshot.profile()
                    + "|verified="
                    + snapshot.verifiedMilestones()
                    + "|next="
                    + snapshot.nextUnverifiedMilestone()
        ).orElse("");
    }

    /**
     * Coarse invalidation facts for an outstanding high-level decision.
     *
     * <p>Ordinary visible entities and sampled block faces deliberately do
     * not participate. They change continuously while the model is thinking
     * and caused a live companion to discard every useful response. Each
     * local skill rechecks its current first-person target, collision and
     * preconditions before and during execution. Body, inventory, menu and
     * danger changes remain strict invalidators. Position is quantized to
     * four-block cells so a slow provider response is not discarded because
     * the body settled one or two blocks while thinking.</p>
     */
    record Fingerprint(
        String dimension,
        int blockX,
        int blockY,
        int blockZ,
        int halfHealth,
        int food,
        int airBand,
        boolean onFire,
        boolean inWater,
        List<String> inventory,
        List<String> openMenu,
        List<String> dangers,
        String routePhase
    ) {
        Fingerprint {
            Objects.requireNonNull(dimension, "dimension");
            inventory = List.copyOf(inventory);
            openMenu = List.copyOf(openMenu);
            dangers = List.copyOf(dangers);
            Objects.requireNonNull(routePhase, "routePhase");
        }

        static Fingerprint from(final SemanticObservation observation) {
            final var body = observation.body();
            return new Fingerprint(
                body.dimensionId(),
                coarseCoordinate(body.position().x()),
                coarseCoordinate(body.position().y()),
                coarseCoordinate(body.position().z()),
                Math.round(body.health() * 2.0F),
                body.foodLevel(),
                Math.max(0, Math.min(4, 4 * body.airSupply() / body.maxAirSupply())),
                body.onFire(),
                body.inWater(),
                body.inventory().stream()
                    .map(item -> item.itemId() + "=" + item.count())
                    .toList(),
                observation.openMenu()
                    .map(menu -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(
                            menu.menuType()
                                + "#"
                                + menu.containerId()
                                + "@"
                                + menu.stateId()
                                + ":carried="
                                + menu.carried().itemId()
                                + "="
                                + menu.carried().count()
                        ),
                        menu.slots().stream().map(slot ->
                            slot.slot()
                                + ":"
                                + slot.itemId()
                                + "="
                                + slot.count()
                                + ":"
                                + slot.damage()
                                + ":"
                                + slot.playerInventory()
                        )
                    ).toList())
                    .orElseGet(List::of),
                observation.dangers().stream()
                    /*
                     * Severity is consumed by the 20 TPS local supervisor.
                     * A moving mob continuously changes that scalar while
                     * the model thinks; only the appearance/disappearance of
                     * a danger kind invalidates the high-level response.
                     */
                    .map(danger -> danger.kind().name())
                    .sorted()
                    .toList(),
                ""
            );
        }

        Fingerprint withRoutePhase(final String phase) {
            return new Fingerprint(
                dimension,
                blockX,
                blockY,
                blockZ,
                halfHealth,
                food,
                airBand,
                onFire,
                inWater,
                inventory,
                openMenu,
                dangers,
                Objects.requireNonNull(phase, "phase")
            );
        }

        Fingerprint withCurrentBody(final ServerPlayer player) {
            return new Fingerprint(
                player.level().dimension().identifier().toString(),
                coarseCoordinate(player.getX()),
                coarseCoordinate(player.getY()),
                coarseCoordinate(player.getZ()),
                Math.round(player.getHealth() * 2.0F),
                player.getFoodData().getFoodLevel(),
                airBand(player),
                player.isOnFire(),
                player.isInWater(),
                inventory,
                openMenu,
                dangers,
                routePhase
            );
        }

        private static int coarseCoordinate(final double coordinate) {
            return Math.floorDiv((int) Math.floor(coordinate), 4);
        }

        boolean criticalBodyChanged(final ServerPlayer player) {
            /*
             * This method runs on every server tick.  Calling
             * withCurrentBody() here used to rebuild the complete immutable
             * Fingerprint, including copied inventory/menu/danger lists,
             * even though none of those lists participate in this coarse
             * invalidation check.  During a long lifecycle that allocation
             * pressure showed up as p95 tick spikes.  Compare only the
             * primitive body fields that are intentionally invalidators;
             * the next scheduled semantic sample still captures inventory,
             * menu and danger-list changes for the model.
             */
            return !dimension.equals(
                    player.level().dimension().identifier().toString()
                )
                || blockX != coarseCoordinate(player.getX())
                || blockY != coarseCoordinate(player.getY())
                || blockZ != coarseCoordinate(player.getZ())
                || halfHealth != Math.round(player.getHealth() * 2.0F)
                || food != player.getFoodData().getFoodLevel()
                || airBand != airBand(player)
                || onFire != player.isOnFire()
                || inWater != player.isInWater();
        }

        private static int airBand(final ServerPlayer player) {
            return Math.max(
                0,
                Math.min(
                    4,
                    4 * player.getAirSupply() / player.getMaxAirSupply()
                )
            );
        }
    }
}
