package dev.mcai.companion.world;

import java.util.UUID;
import java.util.Optional;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.mcai.companion.BuildInfo;
import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.agent.AgentAccentColor;
import dev.mcai.companion.agent.AgentNameRules;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.control.PersistedGoalState;
import dev.mcai.companion.progression.SurvivalMilestone;
import dev.mcai.companion.progression.FoundationFixtureKind;
import dev.mcai.companion.progression.VerifiedRouteProgress;
import dev.mcai.companion.progression.VerifiedFixtureLocation;
import dev.mcai.companion.progression.VerifiedFoundationEvidence;
import dev.mcai.companion.progression.VerifiedShelterEvidence;
import dev.mcai.companion.skills.building.ShelterPlan;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Small, world-authoritative state. Large spatial and episodic memories live
 * in SQLite and are referenced by this stable companion UUID.
 */
public final class CompanionWorldData extends SavedData {
    public static final Codec<CompanionWorldData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        UUIDUtil.CODEC.fieldOf("companion_uuid").forGetter(CompanionWorldData::companionUuid),
        AgentPresentationState.MAP_CODEC.forGetter(
            CompanionWorldData::agentPresentationState
        ),
        Codec.LONG.optionalFieldOf("goal_revision", 0L).forGetter(CompanionWorldData::goalRevision),
        Codec.BOOL.optionalFieldOf("hardcore_dead", false).forGetter(CompanionWorldData::hardcoreDead),
        Codec.BOOL.optionalFieldOf("body_ever_spawned", false).forGetter(CompanionWorldData::bodyEverSpawned),
        Codec.STRING.optionalFieldOf("active_goal_id", "").forGetter(CompanionWorldData::activeGoalId),
        Codec.STRING.optionalFieldOf("goal_status", "IDLE").forGetter(CompanionWorldData::goalStatus),
        Codec.STRING.optionalFieldOf("goal_source", "RECOVERY").forGetter(CompanionWorldData::goalSource),
        Codec.STRING.optionalFieldOf("active_goal", "").forGetter(CompanionWorldData::activeGoal),
        Codec.STRING.optionalFieldOf("goal_detail", "").forGetter(CompanionWorldData::goalDetail),
        Codec.LONG.optionalFieldOf("goal_updated_epoch_millis", 0L).forGetter(CompanionWorldData::goalUpdatedEpochMillis),
        Codec.BOOL.optionalFieldOf("evaluation_locked", false).forGetter(CompanionWorldData::evaluationLocked),
        Codec.BOOL.optionalFieldOf("evaluation_contaminated", false).forGetter(CompanionWorldData::evaluationContaminated),
        EvaluationAuditState.CODEC.optionalFieldOf(
            "evaluation_audit",
            EvaluationAuditState.EMPTY
        ).forGetter(CompanionWorldData::evaluationAuditState),
        ProgressState.CODEC.optionalFieldOf(
            "goal_progress",
            ProgressState.EMPTY
        ).forGetter(CompanionWorldData::progressState),
        Codec.INT.optionalFieldOf("schema_version", BuildInfo.MEMORY_SCHEMA_VERSION).forGetter(CompanionWorldData::schemaVersion)
    ).apply(instance, CompanionWorldData::new));

    public static final SavedDataType<CompanionWorldData> TYPE = new SavedDataType<>(
        Identifier.fromNamespaceAndPath(MinecraftAiCompanion.MOD_ID, "companion"),
        CompanionWorldData::new,
        CODEC,
        DataFixTypes.SAVED_DATA_COMMAND_STORAGE
    );

    private UUID companionUuid;
    private String displayName;
    private long goalRevision;
    private boolean hardcoreDead;
    private boolean bodyEverSpawned;
    private boolean bodySpawnAnchored;
    private AgentAccentColor accentColor;
    private double temperature;
    private String agentSystemPrompt;
    private boolean onboardingCompleted;
    private java.util.Set<String> knownPlayerNames;
    private String activeGoalId;
    private String goalStatus;
    private String goalSource;
    private String activeGoal;
    private String goalDetail;
    private long goalUpdatedEpochMillis;
    private boolean evaluationLocked;
    private boolean evaluationContaminated;
    private boolean evaluationDragonKilled;
    private boolean evaluationReturnedFromEnd;
    private long evaluationStartedGameTick;
    private long evaluationFinishedGameTick;
    private String evaluationModelBaseUrl;
    private String evaluationModelName;
    private long progressGoalRevision;
    private List<String> goalProgress;
    private long routeProgressGoalRevision;
    private java.util.Set<SurvivalMilestone> verifiedRouteMilestones;
    private long routeStartedDay;
    private Optional<VerifiedShelterEvidence> verifiedShelterEvidence;
    private Optional<VerifiedFoundationEvidence>
        verifiedFoundationEvidence;
    private int schemaVersion;

    public CompanionWorldData() {
        this(
            UUID.randomUUID(),
            AgentPresentationState.DEFAULT,
            0L,
            false,
            false,
            "",
            "IDLE",
            "RECOVERY",
            "",
            "",
            0L,
            false,
            false,
            EvaluationAuditState.EMPTY,
            ProgressState.EMPTY,
            BuildInfo.MEMORY_SCHEMA_VERSION
        );
        setDirty();
    }

    private CompanionWorldData(
        final UUID companionUuid,
        final AgentPresentationState presentation,
        final long goalRevision,
        final boolean hardcoreDead,
        final boolean bodyEverSpawned,
        final String activeGoalId,
        final String goalStatus,
        final String goalSource,
        final String activeGoal,
        final String goalDetail,
        final long goalUpdatedEpochMillis,
        final boolean evaluationLocked,
        final boolean evaluationContaminated,
        final EvaluationAuditState evaluationAudit,
        final ProgressState progress,
        final int schemaVersion
    ) {
        this.companionUuid = companionUuid;
        this.displayName = presentation.displayName();
        this.goalRevision = goalRevision;
        this.hardcoreDead = hardcoreDead;
        this.bodyEverSpawned = bodyEverSpawned;
        this.bodySpawnAnchored = progress.bodySpawnAnchored();
        this.accentColor = AgentAccentColor.parse(
            presentation.accentColor()
        );
        this.temperature = requireTemperature(
            presentation.temperature()
        );
        this.agentSystemPrompt = requireAgentSystemPrompt(
            presentation.systemPrompt()
        );
        this.onboardingCompleted = presentation.onboardingCompleted();
        this.knownPlayerNames = new java.util.LinkedHashSet<>(
            presentation.knownPlayerNames()
        );
        this.activeGoalId = activeGoalId;
        this.goalStatus = goalStatus;
        this.goalSource = goalSource;
        this.activeGoal = activeGoal;
        this.goalDetail = goalDetail;
        this.goalUpdatedEpochMillis = goalUpdatedEpochMillis;
        this.evaluationLocked = evaluationLocked;
        this.evaluationContaminated = evaluationContaminated;
        this.evaluationDragonKilled = evaluationAudit.dragonKilled();
        this.evaluationReturnedFromEnd = evaluationAudit.returnedFromEnd();
        this.evaluationStartedGameTick = evaluationAudit.startedGameTick();
        this.evaluationFinishedGameTick = evaluationAudit.finishedGameTick();
        this.evaluationModelBaseUrl = evaluationAudit.modelBaseUrl();
        this.evaluationModelName = evaluationAudit.modelName();
        this.progressGoalRevision = progress.goalRevision();
        this.goalProgress = List.copyOf(progress.notes());
        this.routeProgressGoalRevision = progress.routeGoalRevision();
        this.verifiedRouteMilestones = parseRouteMilestones(
            progress.verifiedRouteMilestones()
        );
        this.routeStartedDay = progress.routeStartedDay();
        this.verifiedShelterEvidence = progress.verifiedShelterEvidence();
        this.verifiedFoundationEvidence =
            progress.verifiedFoundationEvidence();
        this.schemaVersion = schemaVersion;
        validatePersistedEvaluationState();
    }

    public static CompanionWorldData get(final MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public UUID companionUuid() {
        return companionUuid;
    }

    public String displayName() {
        return displayName;
    }

    private AgentPresentationState agentPresentationState() {
        return new AgentPresentationState(
            displayName,
            accentColorName(),
            temperature,
            agentSystemPrompt,
            onboardingCompleted,
            java.util.List.copyOf(knownPlayerNames)
        );
    }

    public long goalRevision() {
        return goalRevision;
    }

    public boolean hardcoreDead() {
        return hardcoreDead;
    }

    public boolean bodyEverSpawned() {
        return bodyEverSpawned;
    }

    /**
     * Whether the persisted body was placed against a real player's safe
     * anchor (or has already been claimed by one).  A false value is only
     * meaningful after the body has spawned and represents the one-time
     * no-human startup placement.
     */
    public boolean bodySpawnAnchored() {
        return bodySpawnAnchored;
    }

    public boolean bodyNeedsInitialAnchor() {
        return bodyEverSpawned && !bodySpawnAnchored;
    }

    public AgentAccentColor accentColor() {
        return accentColor;
    }

    public String accentColorName() {
        return accentColor.serializedName();
    }

    /**
     * Provider sampling temperature, constrained to the UI/API range 0.0–1.0.
     */
    public double temperature() {
        return temperature;
    }

    public String agentSystemPrompt() {
        return agentSystemPrompt;
    }

    public boolean onboardingCompleted() {
        return onboardingCompleted;
    }

    public java.util.Set<String> knownPlayerNames() {
        return java.util.Set.copyOf(knownPlayerNames);
    }

    public void rememberPlayerName(final String playerName) {
        final String normalized = AgentNameRules.requireValid(playerName);
        final boolean alreadyKnown = knownPlayerNames.stream().anyMatch(
            existing -> existing.equalsIgnoreCase(normalized)
        );
        if (alreadyKnown || knownPlayerNames.size() >= 4_096) {
            return;
        }
        knownPlayerNames.add(normalized);
        setDirty();
    }

    public String activeGoalId() {
        return activeGoalId;
    }

    public String goalStatus() {
        return goalStatus;
    }

    public String goalSource() {
        return goalSource;
    }

    public String activeGoal() {
        return activeGoal;
    }

    public String goalDetail() {
        return goalDetail;
    }

    public long goalUpdatedEpochMillis() {
        return goalUpdatedEpochMillis;
    }

    public boolean evaluationLocked() {
        return evaluationLocked;
    }

    public boolean evaluationContaminated() {
        return evaluationContaminated;
    }

    public boolean evaluationDragonKilled() {
        return evaluationDragonKilled;
    }

    public boolean evaluationReturnedFromEnd() {
        return evaluationReturnedFromEnd;
    }

    public boolean evaluationVictoryVerified() {
        return evaluationLocked
            && !evaluationContaminated
            && !hardcoreDead
            && evaluationStartedGameTick >= 0
            && evaluationFinishedGameTick < 0
            && evaluationDragonKilled
            && evaluationReturnedFromEnd;
    }

    public long evaluationStartedGameTick() {
        return evaluationStartedGameTick;
    }

    public long evaluationFinishedGameTick() {
        return evaluationFinishedGameTick;
    }

    public long evaluationElapsedTicks(final long currentGameTick) {
        if (!evaluationClockValid(currentGameTick)) {
            return -1L;
        }
        final long end = evaluationFinishedGameTick >= 0
            ? evaluationFinishedGameTick
            : currentGameTick;
        return end - evaluationStartedGameTick;
    }

    public boolean evaluationClockValid(final long currentGameTick) {
        return currentGameTick >= 0
            && evaluationStartedGameTick >= 0
            && currentGameTick >= evaluationStartedGameTick
            && (evaluationFinishedGameTick < 0
                || evaluationFinishedGameTick
                    >= evaluationStartedGameTick
                && currentGameTick >= evaluationFinishedGameTick);
    }

    public boolean evaluationAuditFresh() {
        return !evaluationLocked
            && !evaluationContaminated
            && !evaluationDragonKilled
            && !evaluationReturnedFromEnd
            && evaluationStartedGameTick < 0
            && evaluationFinishedGameTick < 0
            && evaluationModelBaseUrl.isEmpty()
            && evaluationModelName.isEmpty();
    }

    public String evaluationModelBaseUrl() {
        return evaluationModelBaseUrl;
    }

    public String evaluationModelName() {
        return evaluationModelName;
    }

    private EvaluationAuditState evaluationAuditState() {
        return new EvaluationAuditState(
            evaluationDragonKilled,
            evaluationReturnedFromEnd,
            evaluationStartedGameTick,
            evaluationFinishedGameTick,
            evaluationModelBaseUrl,
            evaluationModelName
        );
    }

    private ProgressState progressState() {
        return new ProgressState(
            progressGoalRevision,
            goalProgress,
            routeProgressGoalRevision,
            verifiedRouteMilestones.stream()
                .map(Enum::name)
                .sorted()
                .toList(),
            routeStartedDay,
            verifiedShelterEvidence,
            verifiedFoundationEvidence,
            bodySpawnAnchored
        );
    }

    public List<String> goalProgress(final long revision) {
        return revision == progressGoalRevision
            ? List.copyOf(goalProgress)
            : List.of();
    }

    public void appendGoalProgress(
        final long revision,
        final String requestedNote
    ) {
        if (revision != goalRevision) {
            throw new IllegalArgumentException(
                "Progress revision does not match the active goal"
            );
        }
        final String note = normalizeProgressNote(requestedNote);
        final java.util.ArrayList<String> updated =
            revision == progressGoalRevision
                ? new java.util.ArrayList<>(goalProgress)
                : new java.util.ArrayList<>();
        updated.add(note);
        while (updated.size() > 16
                || updated.stream().mapToInt(String::length).sum() > 4_096) {
            updated.removeFirst();
        }
        progressGoalRevision = revision;
        goalProgress = List.copyOf(updated);
        setDirty();
    }

    public VerifiedRouteProgress verifiedRouteProgress(
            final long revision
    ) {
        return revision == routeProgressGoalRevision
            ? new VerifiedRouteProgress(
                    revision,
                    verifiedRouteMilestones
            )
            : new VerifiedRouteProgress(revision, java.util.Set.of());
    }

    public void markVerifiedRouteMilestones(
            final long revision,
            final java.util.Set<SurvivalMilestone> milestones
    ) {
        if (revision != goalRevision) {
            throw new IllegalArgumentException(
                    "Route revision does not match the active goal"
            );
        }
        final java.util.Set<SurvivalMilestone> additions =
            java.util.Set.copyOf(
                java.util.Objects.requireNonNull(
                    milestones,
                    "milestones"
                )
            );
        boolean changed = false;
        if (routeProgressGoalRevision != revision) {
            routeProgressGoalRevision = revision;
            verifiedRouteMilestones = java.util.EnumSet.noneOf(
                SurvivalMilestone.class
            );
            routeStartedDay = -1L;
            verifiedShelterEvidence = Optional.empty();
            verifiedFoundationEvidence = Optional.empty();
            changed = true;
        }
        changed |= verifiedRouteMilestones.addAll(additions);
        if (changed) {
            setDirty();
        }
    }

    public void recordVerifiedShelter(
            final long revision,
            final ShelterPlan plan
    ) {
        if (revision != goalRevision) {
            throw new IllegalArgumentException(
                    "Shelter revision does not match the active goal"
            );
        }
        ensureRouteProgressRevision(revision);
        verifiedShelterEvidence = Optional.of(
                VerifiedShelterEvidence.from(revision, plan)
        );
        verifiedRouteMilestones.add(
                SurvivalMilestone.SHELTER_COMPLETED
        );
        setDirty();
    }

    public void recordFoundationFixture(
            final long revision,
            final FoundationFixtureKind kind,
            final VerifiedFixtureLocation location
    ) {
        if (revision != goalRevision) {
            throw new IllegalArgumentException(
                    "Foundation fixture revision does not match the active goal"
            );
        }
        java.util.Objects.requireNonNull(kind, "kind");
        java.util.Objects.requireNonNull(location, "location");
        ensureRouteProgressRevision(revision);
        final VerifiedFoundationEvidence current =
            verifiedFoundationEvidence.orElseGet(
                    () -> VerifiedFoundationEvidence.empty(revision)
            );
        final VerifiedFoundationEvidence updated =
            current.withFixture(kind, location);
        if (!updated.equals(current)) {
            verifiedFoundationEvidence = Optional.of(updated);
            setDirty();
        }
    }

    public void recordFoundationStorageDeposit(
            final long revision,
            final String itemId,
            final int count
    ) {
        if (revision != goalRevision
                || routeProgressGoalRevision != revision) {
            throw new IllegalArgumentException(
                    "Foundation deposit revision does not match the active goal"
            );
        }
        final Optional<VerifiedFoundationEvidence> updated =
            verifiedFoundationEvidence.map(
                    evidence -> evidence.withDepositedSupply(
                            itemId,
                            count
                    )
            );
        if (!updated.equals(verifiedFoundationEvidence)) {
            verifiedFoundationEvidence = updated;
            setDirty();
        }
    }

    public Optional<VerifiedFoundationEvidence>
            verifiedFoundationEvidence(final long revision) {
        return routeProgressGoalRevision == revision
                ? verifiedFoundationEvidence
                : Optional.empty();
    }

    public void updateFoundationVerification(
            final long revision,
            final boolean workstationsEstablished,
            final boolean suppliesStored
    ) {
        if (revision != goalRevision
                || routeProgressGoalRevision != revision
                || suppliesStored && !workstationsEstablished) {
            throw new IllegalArgumentException(
                    "Foundation verification does not match the active goal"
            );
        }
        boolean changed = updateRouteMilestone(
                SurvivalMilestone.WORKSTATIONS_ESTABLISHED,
                workstationsEstablished
        );
        changed |= updateRouteMilestone(
                SurvivalMilestone.SUPPLIES_STORED,
                suppliesStored
        );
        if (changed) {
            setDirty();
        }
    }

    /**
     * Keeps foundation acceptance resources tied to the companion's current
     * owned inventory. These are readiness facts rather than historical
     * achievements, so consuming food or losing the required tools must
     * revoke completion until they are replenished.
     */
    public void updateFoundationInventoryVerification(
            final long revision,
            final boolean basicCraftingReady,
            final boolean foodSecured,
            final boolean stoneToolOwned,
            final boolean ironToolkitOwned
    ) {
        if (revision != goalRevision
                || routeProgressGoalRevision != revision) {
            throw new IllegalArgumentException(
                    "Foundation inventory verification does not match "
                            + "the active goal"
            );
        }
        boolean changed = updateRouteMilestone(
                SurvivalMilestone.BASIC_CRAFTING_READY,
                basicCraftingReady
        );
        changed |= updateRouteMilestone(
                SurvivalMilestone.FOOD_SECURED,
                foodSecured
        );
        changed |= updateRouteMilestone(
                SurvivalMilestone.STONE_TOOL_OBTAINED,
                stoneToolOwned
        );
        changed |= updateRouteMilestone(
                SurvivalMilestone.IRON_TOOLKIT_OBTAINED,
                ironToolkitOwned
        );
        if (changed) {
            setDirty();
        }
    }

    public Optional<VerifiedShelterEvidence> verifiedShelterEvidence(
            final long revision
    ) {
        return routeProgressGoalRevision == revision
                ? verifiedShelterEvidence
                : Optional.empty();
    }

    public void updateShelterVerification(
            final long revision,
            final boolean valid
    ) {
        if (revision != goalRevision
                || routeProgressGoalRevision != revision) {
            throw new IllegalArgumentException(
                    "Shelter verification does not match the active goal"
            );
        }
        final boolean changed = valid
                ? verifiedShelterEvidence.isPresent()
                    && verifiedRouteMilestones.add(
                            SurvivalMilestone.SHELTER_COMPLETED
                    )
                : verifiedRouteMilestones.remove(
                        SurvivalMilestone.SHELTER_COMPLETED
                );
        if (changed) {
            setDirty();
        }
    }

    /**
     * Binds the active route to the first observed Overworld day. Sleeping is
     * intentionally allowed to advance the day; changing clocks or reloading
     * cannot move the persisted starting boundary.
     */
    public long initializeRouteStartDay(
            final long revision,
            final long currentDay
    ) {
        if (revision != goalRevision || currentDay < 0) {
            throw new IllegalArgumentException(
                    "Route start day does not match the active goal"
            );
        }
        if (routeProgressGoalRevision != revision) {
            routeProgressGoalRevision = revision;
            verifiedRouteMilestones = java.util.EnumSet.noneOf(
                SurvivalMilestone.class
            );
            routeStartedDay = currentDay;
            verifiedShelterEvidence = Optional.empty();
            verifiedFoundationEvidence = Optional.empty();
            setDirty();
            return routeStartedDay;
        }
        if (routeStartedDay < 0) {
            routeStartedDay = currentDay;
            setDirty();
        }
        return routeStartedDay;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public void setDisplayName(final String name) {
        final String normalized = AgentNameRules.requireValid(name);
        if (!displayName.equals(normalized)) {
            displayName = normalized;
            setDirty();
        }
    }

    public void updateAgentPresentation(
        final String displayName,
        final AgentAccentColor accentColor,
        final double temperature,
        final String systemPrompt,
        final boolean onboardingCompleted
    ) {
        final String normalizedName = AgentNameRules.requireValid(displayName);
        final AgentAccentColor normalizedColor = java.util.Objects.requireNonNull(
            accentColor,
            "accentColor"
        );
        final double normalizedTemperature = requireTemperature(temperature);
        final String normalizedPrompt = requireAgentSystemPrompt(systemPrompt);
        if (!this.displayName.equals(normalizedName)
            || this.accentColor != normalizedColor
            || Double.compare(this.temperature, normalizedTemperature) != 0
            || !this.agentSystemPrompt.equals(normalizedPrompt)
            || this.onboardingCompleted != onboardingCompleted) {
            this.displayName = normalizedName;
            this.accentColor = normalizedColor;
            this.temperature = normalizedTemperature;
            this.agentSystemPrompt = normalizedPrompt;
            this.onboardingCompleted = onboardingCompleted;
            setDirty();
        }
    }

    private static double requireTemperature(final double value) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                "Agent temperature must be in [0.0,1.0]"
            );
        }
        return value;
    }

    private static String requireAgentSystemPrompt(final String value) {
        final String normalized = java.util.Objects.requireNonNullElse(
            value,
            ""
        ).strip();
        if (normalized.length() > 4_096 || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                "Agent system prompt exceeds its safe bound"
            );
        }
        return normalized;
    }

    public long advanceGoalRevision() {
        goalRevision = Math.addExact(goalRevision, 1L);
        setDirty();
        return goalRevision;
    }

    public void markHardcoreDead() {
        if (!hardcoreDead) {
            hardcoreDead = true;
            setDirty();
        }
    }

    public void markBodySpawned() {
        markBodySpawned(true);
    }

    /**
     * Records the placement provenance of the live body.  This is metadata
     * only; it never edits a block or grants the body a gameplay shortcut.
     */
    public void markBodySpawned(final boolean anchored) {
        if (!bodyEverSpawned || bodySpawnAnchored != anchored) {
            bodyEverSpawned = true;
            bodySpawnAnchored = anchored;
            setDirty();
        }
    }

    public void markBodyAnchored() {
        if (!bodySpawnAnchored) {
            bodySpawnAnchored = true;
            setDirty();
        }
    }

    public void markEvaluationContaminated() {
        if (!evaluationContaminated) {
            evaluationContaminated = true;
            setDirty();
        }
    }

    public void markEvaluationDragonKilled() {
        if (evaluationActive() && !evaluationDragonKilled) {
            evaluationDragonKilled = true;
            setDirty();
        }
    }

    public void markEvaluationReturnedFromEnd() {
        if (evaluationActive()
                && evaluationDragonKilled
                && !evaluationReturnedFromEnd) {
            evaluationReturnedFromEnd = true;
            setDirty();
        }
    }

    public void beginEvaluation(
            final long gameTick,
            final String modelBaseUrl,
            final String modelName
    ) {
        final String normalizedBaseUrl = normalizeEvaluationProfileField(
            modelBaseUrl,
            2_048,
            "model base URL"
        );
        final String normalizedModelName = normalizeEvaluationProfileField(
            modelName,
            256,
            "model name"
        );
        if (!evaluationLocked
                || evaluationContaminated
                || hardcoreDead
                || gameTick < 0
                || evaluationStartedGameTick >= 0
                || evaluationFinishedGameTick >= 0
                || evaluationDragonKilled
                || evaluationReturnedFromEnd
                || !evaluationModelBaseUrl.isEmpty()
                || !evaluationModelName.isEmpty()) {
            throw new IllegalStateException(
                "Evaluation timer requires a fresh locked evaluation"
            );
        }
        evaluationStartedGameTick = gameTick;
        evaluationFinishedGameTick = -1L;
        evaluationModelBaseUrl = normalizedBaseUrl;
        evaluationModelName = normalizedModelName;
        setDirty();
    }

    public void finishEvaluation(final long gameTick) {
        if (evaluationStartedGameTick < 0 || gameTick < evaluationStartedGameTick) {
            throw new IllegalStateException(
                "Evaluation finish tick precedes its start"
            );
        }
        if (evaluationFinishedGameTick < 0) {
            evaluationFinishedGameTick = gameTick;
            setDirty();
        }
    }

    private boolean evaluationActive() {
        return evaluationLocked
            && !evaluationContaminated
            && !hardcoreDead
            && evaluationStartedGameTick >= 0
            && evaluationFinishedGameTick < 0
            && !evaluationModelBaseUrl.isEmpty()
            && !evaluationModelName.isEmpty();
    }

    public Optional<PersistedGoalState> persistedGoalState() {
        if (goalStatus.equals("IDLE")
            && activeGoalId.isEmpty()
            && activeGoal.isEmpty()
            && !evaluationLocked) {
            return Optional.empty();
        }
        final GoalStatus parsedStatus = parseStatus(goalStatus);
        final GoalSource parsedSource = parseSource(goalSource);
        final Optional<UUID> parsedId = parseGoalId(
            activeGoalId,
            parsedStatus
        );
        final String recoveredGoal = activeGoal.isBlank()
            && parsedStatus != GoalStatus.IDLE
            ? "通关 Minecraft"
            : activeGoal;
        final java.time.Instant updatedAt;
        try {
            updatedAt = java.time.Instant.ofEpochMilli(
                Math.max(0L, goalUpdatedEpochMillis)
            );
        } catch (java.time.DateTimeException exception) {
            throw new IllegalStateException(
                "Persisted goal timestamp is invalid",
                exception
            );
        }
        return Optional.of(new PersistedGoalState(
            goalRevision,
            parsedId,
            parsedStatus,
            parsedSource,
            recoveredGoal,
            goalDetail,
            updatedAt,
            evaluationLocked
        ));
    }

    public void updateGoalState(final PersistedGoalState state) {
        if (state.revision() != goalRevision) {
            throw new IllegalArgumentException(
                "Goal state revision does not match world data"
            );
        }
        if ((evaluationLocked || state.externalWritesLocked())
                && state.source()
                    != GoalSource.HARDCORE_EVALUATION) {
            throw new IllegalArgumentException(
                "A locked evaluation cannot change goal source"
            );
        }
        if (evaluationLocked && !state.externalWritesLocked()) {
            throw new IllegalArgumentException(
                "A locked evaluation cannot be unlocked"
            );
        }
        final String nextGoalId = state.goalId()
                .map(UUID::toString)
                .orElse("");
        /*
         * Status transitions advance the mutation revision so in-flight model
         * responses become stale, but they do not create a different goal.
         * Keep the prior revision's audit and route evidence addressable for
         * that same goal. A genuinely new goal has a new UUID and still
         * clears every goal-scoped progress record.
         */
        final boolean sameGoal = !activeGoalId.isEmpty()
                && activeGoalId.equals(nextGoalId);
        activeGoalId = nextGoalId;
        goalStatus = state.status().name();
        goalSource = state.source().name();
        activeGoal = state.goal();
        goalDetail = state.detailCode();
        goalUpdatedEpochMillis = state.updatedAt().toEpochMilli();
        evaluationLocked = evaluationLocked || state.externalWritesLocked();
        if (!sameGoal
                && progressGoalRevision != state.revision()) {
            progressGoalRevision = state.revision();
            goalProgress = List.of();
        }
        if (!sameGoal
                && routeProgressGoalRevision != state.revision()) {
            routeProgressGoalRevision = state.revision();
            verifiedRouteMilestones = java.util.EnumSet.noneOf(
                SurvivalMilestone.class
            );
            routeStartedDay = -1L;
            verifiedShelterEvidence = Optional.empty();
            verifiedFoundationEvidence = Optional.empty();
        }
        setDirty();
    }

    private void ensureRouteProgressRevision(final long revision) {
        if (routeProgressGoalRevision == revision) {
            return;
        }
        routeProgressGoalRevision = revision;
        verifiedRouteMilestones = java.util.EnumSet.noneOf(
                SurvivalMilestone.class
        );
        routeStartedDay = -1L;
        verifiedShelterEvidence = Optional.empty();
        verifiedFoundationEvidence = Optional.empty();
    }

    private boolean updateRouteMilestone(
            final SurvivalMilestone milestone,
            final boolean valid
    ) {
        return valid
                ? verifiedRouteMilestones.add(milestone)
                : verifiedRouteMilestones.remove(milestone);
    }

    private void validatePersistedEvaluationState() {
        final boolean hasAudit =
            evaluationDragonKilled
                || evaluationReturnedFromEnd
                || evaluationStartedGameTick >= 0
                || evaluationFinishedGameTick >= 0
                || !evaluationModelBaseUrl.isEmpty()
                || !evaluationModelName.isEmpty();
        if (!evaluationLocked && hasAudit) {
            throw new IllegalArgumentException(
                "Evaluation evidence requires a persistent lock"
            );
        }
        if (evaluationLocked
                && !goalSource.equals(
                    GoalSource.HARDCORE_EVALUATION.name()
                )) {
            throw new IllegalArgumentException(
                "A locked evaluation has an invalid goal source"
            );
        }
        if (evaluationFinishedGameTick >= 0
                && (goalStatus.equals(GoalStatus.RUNNING.name())
                    || goalStatus.equals(
                        GoalStatus.CANCEL_PENDING.name()
                    ))) {
            throw new IllegalArgumentException(
                "A finished evaluation cannot have an active goal"
            );
        }
        if (evaluationLocked
                && (evaluationModelBaseUrl.isEmpty()
                    || evaluationModelName.isEmpty())) {
            throw new IllegalArgumentException(
                "A locked evaluation is missing its frozen model profile"
            );
        }
    }

    private static String normalizeEvaluationProfileField(
            final String raw,
            final int maximumLength,
            final String fieldName
    ) {
        if (raw == null
                || raw.isBlank()
                || !raw.equals(raw.strip())
                || raw.length() > maximumLength) {
            throw new IllegalArgumentException(
                "Evaluation " + fieldName + " is invalid"
            );
        }
        for (int offset = 0; offset < raw.length();) {
            final int codePoint = raw.codePointAt(offset);
            if (Character.isISOControl(codePoint)
                    || Character.isWhitespace(codePoint)) {
                throw new IllegalArgumentException(
                    "Evaluation " + fieldName
                        + " contains an invalid character"
                );
            }
            offset += Character.charCount(codePoint);
        }
        return raw;
    }

    private static String normalizeProgressNote(final String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Progress note is required");
        }
        final String note = raw.strip();
        if (note.isEmpty()
                || note.codePointCount(0, note.length()) > 256) {
            throw new IllegalArgumentException("Progress note length is invalid");
        }
        for (int offset = 0; offset < note.length();) {
            final int codePoint = note.codePointAt(offset);
            if (codePoint == 0
                    || (Character.isISOControl(codePoint)
                    && codePoint != '\n'
                    && codePoint != '\t')) {
                throw new IllegalArgumentException(
                    "Progress note contains a control character"
                );
            }
            offset += Character.charCount(codePoint);
        }
        return note;
    }

    private GoalStatus parseStatus(final String value) {
        try {
            return GoalStatus.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return evaluationLocked ? GoalStatus.SAFE_IDLE : GoalStatus.IDLE;
        }
    }

    private GoalSource parseSource(final String value) {
        try {
            return GoalSource.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return evaluationLocked
                ? GoalSource.HARDCORE_EVALUATION
                : GoalSource.RECOVERY;
        }
    }

    private Optional<UUID> parseGoalId(
        final String value,
        final GoalStatus status
    ) {
        try {
            if (!value.isBlank()) {
                return Optional.of(UUID.fromString(value));
            }
        } catch (IllegalArgumentException ignored) {
            // Recover below without clearing a persistent evaluation lock.
        }
        if (status == GoalStatus.IDLE) {
            return Optional.empty();
        }
        return Optional.of(UUID.nameUUIDFromBytes(
            (companionUuid + ":recovered-goal")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ));
    }

    private record EvaluationAuditState(
        boolean dragonKilled,
        boolean returnedFromEnd,
        long startedGameTick,
        long finishedGameTick,
        String modelBaseUrl,
        String modelName
    ) {
        private static final EvaluationAuditState EMPTY =
            new EvaluationAuditState(
                false,
                false,
                -1L,
                -1L,
                "",
                ""
            );
        private static final Codec<EvaluationAuditState> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("dragon_killed", false)
                    .forGetter(EvaluationAuditState::dragonKilled),
                Codec.BOOL.optionalFieldOf("returned_from_end", false)
                    .forGetter(EvaluationAuditState::returnedFromEnd),
                Codec.LONG.optionalFieldOf("started_game_tick", -1L)
                    .forGetter(EvaluationAuditState::startedGameTick),
                Codec.LONG.optionalFieldOf("finished_game_tick", -1L)
                    .forGetter(EvaluationAuditState::finishedGameTick),
                Codec.STRING.optionalFieldOf("model_base_url", "")
                    .forGetter(EvaluationAuditState::modelBaseUrl),
                Codec.STRING.optionalFieldOf("model_name", "")
                    .forGetter(EvaluationAuditState::modelName)
            ).apply(instance, EvaluationAuditState::new));

        private EvaluationAuditState {
            if (startedGameTick < -1L
                    || finishedGameTick < -1L
                    || startedGameTick < 0L
                        && finishedGameTick >= 0L
                    || finishedGameTick >= 0L
                        && finishedGameTick < startedGameTick
                    || returnedFromEnd && !dragonKilled
                    || modelBaseUrl.isEmpty() != modelName.isEmpty()) {
                throw new IllegalArgumentException(
                    "Persisted evaluation audit state is invalid"
                );
            }
            if (!modelBaseUrl.isEmpty()) {
                normalizeEvaluationProfileField(
                    modelBaseUrl,
                    2_048,
                    "model base URL"
                );
                normalizeEvaluationProfileField(
                    modelName,
                    256,
                    "model name"
                );
            }
        }
    }

    private record ProgressState(
        long goalRevision,
        List<String> notes,
        long routeGoalRevision,
        List<String> verifiedRouteMilestones,
        long routeStartedDay,
        Optional<VerifiedShelterEvidence> verifiedShelterEvidence,
        Optional<VerifiedFoundationEvidence> verifiedFoundationEvidence,
        boolean bodySpawnAnchored
    ) {
        private static final ProgressState EMPTY =
            new ProgressState(
                    -1L,
                    List.of(),
                    -1L,
                    List.of(),
                    -1L,
                    Optional.empty(),
                    Optional.empty(),
                    true
            );
        private static final Codec<ProgressState> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.optionalFieldOf("goal_revision", -1L)
                    .forGetter(ProgressState::goalRevision),
                Codec.STRING.listOf().optionalFieldOf("notes", List.of())
                    .forGetter(ProgressState::notes),
                Codec.LONG.optionalFieldOf(
                    "route_goal_revision",
                    -1L
                ).forGetter(ProgressState::routeGoalRevision),
                Codec.STRING.listOf().optionalFieldOf(
                    "verified_route_milestones",
                    List.of()
                ).forGetter(ProgressState::verifiedRouteMilestones),
                Codec.LONG.optionalFieldOf(
                    "route_started_day",
                    -1L
                ).forGetter(ProgressState::routeStartedDay),
                VerifiedShelterEvidence.CODEC.optionalFieldOf(
                    "verified_shelter"
                ).forGetter(ProgressState::verifiedShelterEvidence),
                VerifiedFoundationEvidence.CODEC.optionalFieldOf(
                    "verified_foundation"
                ).forGetter(ProgressState::verifiedFoundationEvidence),
                Codec.BOOL.optionalFieldOf(
                    "body_spawn_anchored",
                    true
                ).forGetter(ProgressState::bodySpawnAnchored)
            ).apply(instance, ProgressState::new));

        private ProgressState {
            notes = List.copyOf(notes);
            verifiedRouteMilestones = List.copyOf(
                verifiedRouteMilestones
            );
            verifiedShelterEvidence = java.util.Objects.requireNonNull(
                    verifiedShelterEvidence,
                    "verifiedShelterEvidence"
            );
            verifiedFoundationEvidence = java.util.Objects.requireNonNull(
                    verifiedFoundationEvidence,
                    "verifiedFoundationEvidence"
            );
            if (notes.size() > 16
                    || notes.stream().mapToInt(String::length).sum() > 4_096
                    || routeGoalRevision < -1
                    || routeGoalRevision < 0
                        && !verifiedRouteMilestones.isEmpty()
                    || routeStartedDay < -1
                    || routeGoalRevision < 0
                        && routeStartedDay >= 0
                    || routeGoalRevision < 0
                        && verifiedShelterEvidence.isPresent()
                    || routeGoalRevision < 0
                        && verifiedFoundationEvidence.isPresent()
                    || verifiedShelterEvidence
                        .filter(evidence ->
                                evidence.goalRevision()
                                    != routeGoalRevision
                        )
                        .isPresent()
                    || verifiedFoundationEvidence
                        .filter(evidence ->
                                evidence.goalRevision()
                                    != routeGoalRevision
                        )
                        .isPresent()
                    || verifiedRouteMilestones.size()
                        > SurvivalMilestone.values().length) {
                throw new IllegalArgumentException(
                    "Persisted progress exceeds its bound"
                );
            }
            notes.forEach(CompanionWorldData::normalizeProgressNote);
            parseRouteMilestones(verifiedRouteMilestones);
        }
    }

    private static java.util.Set<SurvivalMilestone>
            parseRouteMilestones(final List<String> names) {
        final java.util.EnumSet<SurvivalMilestone> result =
            java.util.EnumSet.noneOf(SurvivalMilestone.class);
        for (String name : names) {
            try {
                if (!result.add(SurvivalMilestone.valueOf(name))) {
                    throw new IllegalArgumentException(
                        "Duplicate route milestone"
                    );
                }
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                    "Persisted route milestone is invalid",
                    exception
                );
            }
        }
        return result;
    }
}
