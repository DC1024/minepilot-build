package dev.mcai.companion.skills.combat;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionMath;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.progression.CompletionResourceReadiness;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.bridging.BridgeMaterialActuator;
import dev.mcai.companion.skills.bridging.BridgeMaterialResult;
import dev.mcai.companion.skills.bridging.TowerUpParameters;
import dev.mcai.companion.skills.bridging.TowerUpSkill;
import dev.mcai.companion.skills.bridging.WaterClutchDescendParameters;
import dev.mcai.companion.skills.bridging.WaterClutchDescendSkill;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.MoveToParameters;
import dev.mcai.companion.skills.core.MoveToSkill;
import dev.mcai.companion.skills.core.TravelToParameters;
import dev.mcai.companion.skills.core.TravelToSkill;
import dev.mcai.companion.skills.end.EndArenaTopology;
import dev.mcai.companion.skills.end.EndIslandIngressParameters;
import dev.mcai.companion.skills.end.EndIslandRallyEvidence;
import dev.mcai.companion.skills.end.EndIslandIngressSkill;
import dev.mcai.companion.skills.interaction.BreakBlockParameters;
import dev.mcai.companion.skills.interaction.BreakBlockSkill;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillPolicy;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.EquipmentTarget;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Bounded End-fight coordination driven only by the player's current semantic
 * view. Every projectile is delegated to the normal ranged combat skill and
 * victory is accepted only from the companion-attributed dragon-death event.
 */
public final class FightEnderDragonSkill
        implements Skill<FightEnderDragonParameters> {
    public static final String NAME = "fight_ender_dragon";

    private static final String BOW = "minecraft:bow";
    private static final String ARROW = "minecraft:arrow";
    private static final String END_CRYSTAL =
            "minecraft:end_crystal";
    private static final String ENDER_DRAGON =
            "minecraft:ender_dragon";
    private static final String IRON_BARS =
            "minecraft:iron_bars";
    private static final int SCAN_INTERVAL_TICKS = 3;
    private static final int SCANS_BEFORE_RALLY = 48;
    private static final int MAXIMUM_RALLY_ATTEMPTS = 4;
    /** A natural island edge can require several bounded two-block openings. */
    private static final int MAXIMUM_SKY_BLOCKS_MINED = 96;
    private static final int MAXIMUM_SKY_BREAK_ATTEMPTS = 240;
    private static final int SKY_BLOCKS_BEFORE_CENTERWARD_TRAVEL = 8;
    private static final int SKY_BREAK_ALIGNMENT_TICKS = 60;
    /* A vanilla ray can change between the semantic frame and the action
     * tick when the body is standing under a low natural island lip.  Rebind
     * the same observed block a few times before abandoning it; this is a
     * bounded first-person retry, not a relaxed target check. */
    private static final int MAXIMUM_SKY_OCCLUSION_RETRIES = 4;
    private static final double SKY_BREAK_REACH = 6.0;
    private static final double SKY_BREAK_STANDING_REACH = 4.5;
    private static final int MAXIMUM_REENTRY_ATTEMPTS = 1;
    private static final int OBSERVED_RALLY_STEP_TICKS = 32;
    private static final double OBSERVED_RALLY_ALIGNMENT_DEGREES = 18.0;
    private static final long OBSERVED_RALLY_SUPPORT_MAX_AGE = 4L;
    private static final long OBSERVED_RALLY_MEMORY_MAX_AGE = 512L;
    /* The End ingress contract already proves a fair standing cell inside
     * the vanilla central-island envelope.  Re-entering the bridge/mining
     * controller from any point inside that envelope is counterproductive:
     * dragon knockback can push a valid fighter onto an observed edge and the
     * old 24-block threshold made it rebuild the route while it could still
     * legally fight.  Only a body outside the same 56-block evidence radius
     * needs island re-entry; combat remains responsible for reacquiring the
     * target from the current standing cell. */
    private static final double FIGHT_REENTRY_RADIUS =
            EndArenaTopology.ARENA_READY_RADIUS;
    /* Re-entry must actually leave the outer lip before it can hand control
     * back to combat.  Using the admission radius as the child completion
     * radius let a valid current support cell complete immediately, even
     * while the player was still behind the first wall. */
    private static final double FIGHT_REENTRY_TARGET_RADIUS = 54.0;
    /** Entity perception is 32 blocks; escape the spawn-platform corridor
     * before returning to ordinary dragon search. */
    private static final double FIGHT_OBSTACLE_ESCAPE_RADIUS = 30.0;
    private static final double SKY_BREAK_ALIGNMENT_DEGREES = 2.0;
    /* A full bow flight at the maximum observed dragon distance is shorter
     * than this; leave enough time for the vanilla projectile to advance
     * before reacquiring a target without idling a whole second. */
    private static final int PROJECTILE_SETTLE_TICKS = 8;
    private static final int CAGE_SCANS_BEFORE_RALLY = 12;
    private static final int CAGE_DESCENT_SCAN_LIMIT = 12;
    private static final int MAXIMUM_CAGE_APPROACH_ATTEMPTS = 2;
    private static final int MAXIMUM_CAGE_BARS_MINED = 12;
    private static final int CRYSTAL_CAGE_INSPECTION_SCANS = 12;
    private static final int MAXIMUM_CRYSTAL_STANDOFF_SCANS = 12;
    private static final int MAXIMUM_CRYSTAL_STANDOFF_ATTEMPTS = 8;
    private static final int MAXIMUM_CRYSTAL_LANE_ATTEMPTS = 3;
    private static final int MAXIMUM_CRYSTAL_LANE_SCANS = 8;
    private static final double CAGE_LINE_RADIUS = 1.15;
    private static final double CAGE_BREAK_REACH = 4.5;
    private static final double CAGE_MINING_ALIGNMENT_DEGREES = 1.5;
    private static final double MELEE_DRAGON_DISTANCE = 5.75;
    private static final double MELEE_ALIGNMENT_DEGREES = 5.0;
    private static final double MELEE_ATTACK_STRENGTH = 0.90;
    /*
     * A vanilla player can land many swings on a dragon part while the
     * attack cooldown and the dragon's damage immunity are out of phase.
     * Staying pressed against one tail/wing indefinitely is nevertheless a
     * poor recovery strategy: it leaves no room for the ordinary bow path
     * and makes a multipart target look like a frozen conversation.  After
     * this bounded local melee burst, keep using the currently visible
     * dragon and normal arrows until it is dead.  This is a combat policy,
     * not a health/world shortcut; every shot still goes through the
     * first-person crosshair and vanilla projectile interaction checks.
     */
    private static final int MELEE_ATTACKS_BEFORE_RANGED = 24;
    private static final int DEFENSIVE_DODGE_COOLDOWN_TICKS = 6;
    /*
     * Keep the first-person retreat inside the observed dragon arena.  The
     * movement actuator advances roughly 1.2 blocks per tick; twenty-four
     * ticks therefore sent the body almost thirty blocks away, where the
     * dragon could occlude every crystal lane and heal indefinitely.  Eight
     * ticks gives a normal melee-to-bow separation of about ten blocks while
     * retaining vanilla collision and hazard handling.
     */
    private static final int DRAGON_RANGED_RETREAT_TICKS = 8;
    private static final double IMMEDIATE_DRAGON_DISTANCE = 8.75;
    private static final int MELEE_REACH_MISSES_BEFORE_RANGED = 3;
    private static final int RANGED_SHOTS_BEFORE_MELEE_RETRY = 4;
    private static final List<String> MELEE_WEAPONS = List.of(
            "minecraft:netherite_sword",
            "minecraft:diamond_sword",
            "minecraft:iron_sword",
            "minecraft:stone_sword",
            "minecraft:netherite_axe",
            "minecraft:diamond_axe",
            "minecraft:iron_axe",
            "minecraft:stone_axe"
    );
    private static final List<String> CAGE_MINING_TOOLS = List.of(
            "minecraft:netherite_pickaxe",
            "minecraft:diamond_pickaxe",
            "minecraft:iron_pickaxe",
            "minecraft:stone_pickaxe",
            "minecraft:golden_pickaxe",
            "minecraft:wooden_pickaxe"
    );
    private static final float[] SCAN_PITCHES = {
        -30.0F,
        -55.0F,
        -75.0F,
        8.0F
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final InventorySkillActuator inventory;
    private final BridgeMaterialActuator bridgeMaterials;
    private final DragonVictorySource victory;
    private final LongSupplier sessionGeneration;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long nextActionTick = -1;
    private long lastDefensiveDodgeTick = -1;
    private long lastObservationRevision = -1;
    private long boundSessionGeneration = -1;
    private int shotsDispatched;
    private int meleeAttacks;
    private int meleeReachMisses;
    private int rangedDragonShotsSinceMeleeRetry;
    private int dragonRetreatTicksRemaining;
    private int scanTurns;
    private int rallyAttempts;
    private int ingressAttempts;
    private String lastIngressResult = "";
    /**
     * Retains the final fair ingress checkpoint after the child is retired.
     * The parent used to clear the child before its next checkpoint, which
     * erased the only bounded explanation for a natural terrain failure.
     */
    private String lastIslandIngressCheckpoint = "";
    private int skyBlocksMined;
    private int skyBreakAttempts;
    private int skyAlignmentTicks;
    private long skyAlignmentReadyTick = -1L;
    private int skyOcclusionRetries;
    private String lastSkyFailure = "";
    private GridPos lastSkyOccludedBlock;
    private boolean skyJumpPending;
    private int skyBlocksSinceRally;
    private int cageScanTurns;
    private int cageBarsMined;
    private int cageApproachAttempts;
    private int cageDescentScans;
    private int cageLandingScans;
    private float scanBaseYaw;
    /**
     * Last fair direction from a recent damage/contact cue.  A dodge turns
     * the camera away from the attacker; preserving this direction lets the
     * next bounded scan reacquire that same visible threat instead of
     * repeatedly sweeping the opposite hemisphere.
     */
    private PerceptionVec3 lastThreatDirection;
    private PerceptionVec3 localRallyPoint;
    private boolean recoveringSafetyReserve;
    private boolean dragonRangedMode;
    private PerceptionVec3 dragonRetreatDirection;
    private boolean sawCageBarBeyondReach;
    private boolean cageTowered;
    private boolean cageLandingVerified;
    private long cageLandingVerifiedRevision = -1;
    private int crystalStandOffScans;
    private int crystalStandOffAttempts;
    private int crystalCageInspectionTurns;
    private int crystalLaneAttempts;
    private int crystalLaneScanTurns;
    private CageStatus cageStatus = CageStatus.NONE;
    private TravelPurpose travelPurpose = TravelPurpose.NONE;
    private UUID cageCrystalId;
    private PerceptionVec3 cageLastSeenPosition;
    private PerceptionVec3 crystalStandOffPosition;
    private GridPos rallyStepTarget;
    private int rallyStepTicks;
    private int rallyStepStarts;
    private int rallyStepTimeouts;
    private boolean rallyStepAwaitingFreshObservation;
    private long rallyStepObservationRevision = -1L;
    private GridPos previousRallyStepTarget;
    private String lastRallyFailure = "";
    private String lastInternalFailure = "";
    private EndIslandIngressSkill islandIngress;
    private EndIslandIngressParameters islandIngressParameters;
    private CagedCrystalTraversalPlanner.Plan cagePlan;
    private ShootObservedEntitySkill shot;
    private ShootObservedEntityParameters shotParameters;
    private boolean shotTargetDragon;
    private TravelToSkill travel;
    private TravelToParameters travelParameters;
    private MoveToSkill crystalStandOff;
    private MoveToParameters crystalStandOffParameters;
    private MoveToSkill crystalLane;
    private MoveToParameters crystalLaneParameters;
    private BreakBlockSkill cageBreak;
    private BreakBlockParameters cageBreakParameters;
    private BreakBlockSkill skyBreak;
    private BreakBlockParameters skyBreakParameters;
    private ObservedBlockTarget skyBreakTarget;
    private TowerUpSkill cageTower;
    private TowerUpParameters cageTowerParameters;
    private WaterClutchDescendSkill cageDescent;
    private WaterClutchDescendParameters cageDescentParameters;

    public FightEnderDragonSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final InventorySkillActuator inventory,
            final BridgeMaterialActuator bridgeMaterials,
            final DragonVictorySource victory,
            final LongSupplier sessionGeneration
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.core = Objects.requireNonNull(core, "core");
        this.coreFrames = Objects.requireNonNull(
                coreFrames,
                "coreFrames"
        );
        this.interactions = Objects.requireNonNull(
                interactions,
                "interactions"
        );
        this.interactionFrames = Objects.requireNonNull(
                interactionFrames,
                "interactionFrames"
        );
        this.inventory = Objects.requireNonNull(
                inventory,
                "inventory"
        );
        this.bridgeMaterials = Objects.requireNonNull(
                bridgeMaterials,
                "bridgeMaterials"
        );
        this.victory = Objects.requireNonNull(victory, "victory");
        this.sessionGeneration = Objects.requireNonNull(
                sessionGeneration,
                "sessionGeneration"
        );
    }

    @Override
    public SkillParameterParser<FightEnderDragonParameters> parameters() {
        return DragonCombatSkillParameters::parse;
    }

    @Override
    public boolean allowsWorldRevisionTransition() {
        /*
         * Dragon death is a server-verified completion boundary.  The skill
         * must receive one more ordinary tick to observe victory and finish;
         * invalidating its bound epoch first would turn a real kill into a
         * stale-world failure.
         */
        return true;
    }

    @Override
    public boolean managesVisibleHostileProximity() {
        return phase != Phase.IDLE
                && phase != Phase.COMPLETED
                && phase != Phase.CANCELLED
                && phase != Phase.FAILED
                && !recoveringSafetyReserve;
    }

    @Override
    public boolean managesVisibleProjectileThreats() {
        return phase != Phase.IDLE
                && phase != Phase.COMPLETED
                && phase != Phase.CANCELLED
                && phase != Phase.FAILED
                && !recoveringSafetyReserve;
    }

    @Override
    public boolean managesPhysicalContactThreats() {
        return phase != Phase.IDLE
                && phase != Phase.COMPLETED
                && phase != Phase.CANCELLED
                && phase != Phase.FAILED
                && !recoveringSafetyReserve;
    }

    @Override
    public OptionalDouble hardcoreRiskThresholdOverride(
            final SkillContext context,
            final FightEnderDragonParameters parameters
    ) {
        final Optional<CoreSkillFrame> frame = coreFrames.current()
                .filter(current ->
                        expectedPlayerId.equals(current.playerId())
                );
        return frame.isEmpty()
                ? OptionalDouble.empty()
                : CombatHardcoreRisk.threshold(
                        context,
                        frame.orElseThrow(),
                        1.0
                );
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final FightEnderDragonParameters parameters
    ) {
        final SnapshotValidation validation =
                validateSnapshot(parameters);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        final CoreSkillFrame frame = validation.snapshot()
                .orElseThrow()
                .core();
        if (!DimensionRef.END.equals(parameters.dimension())) {
            return Optional.of(SkillFailure.of(
                    NAME + ".end_dimension_required"
            ));
        }
        if (healthTooLow(context, frame)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_reserve_required"
            ));
        }
        if (frame.foodLevel() < (context.hardcore() ? 10 : 4)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".food_reserve_required"
            ));
        }
        if (inventoryCount(frame, BOW)
                < CompletionResourceReadiness.END_BOWS) {
            return Optional.of(SkillFailure.of(
                    NAME + ".bow_required"
            ));
        }
        if (inventoryCount(frame, ARROW)
                < CompletionResourceReadiness.END_ARROWS) {
            return Optional.of(SkillFailure.of(
                    NAME + ".arrows_required"
            ));
        }
        if (!EndIslandRallyEvidence.supportsCurrentStandingCell(
                frame,
                EndArenaTopology.ARENA_READY_RADIUS
        )) {
            return Optional.of(SkillFailure.of(
                    NAME + ".end_island_ingress_required"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final FightEnderDragonParameters parameters
    ) {
        final Snapshot snapshot = validateSnapshot(parameters)
                .snapshot()
                .orElseThrow(() -> new IllegalStateException(
                        "Dragon-fight body changed before start"
                ));
        phase = Phase.SEARCHING;
        failure = null;
        startedAtTick = context.gameTick();
        nextActionTick = context.gameTick();
        lastDefensiveDodgeTick = -1;
        lastObservationRevision = -1;
        boundSessionGeneration =
                snapshot.interaction().sessionGeneration();
        shotsDispatched = 0;
        meleeAttacks = 0;
        meleeReachMisses = 0;
        rangedDragonShotsSinceMeleeRetry = 0;
        dragonRetreatTicksRemaining = 0;
        scanTurns = 0;
        rallyAttempts = 0;
        ingressAttempts = 0;
        lastIngressResult = "";
        lastIslandIngressCheckpoint = "";
        skyBlocksMined = 0;
        skyBreakAttempts = 0;
        skyAlignmentTicks = 0;
        skyAlignmentReadyTick = -1L;
        skyOcclusionRetries = 0;
        lastSkyFailure = "";
        lastSkyOccludedBlock = null;
        skyJumpPending = false;
        skyBlocksSinceRally = 0;
        cageScanTurns = 0;
        cageBarsMined = 0;
        cageApproachAttempts = 0;
        cageDescentScans = 0;
        cageLandingScans = 0;
        scanBaseYaw = lookYaw(snapshot.core());
        lastThreatDirection = null;
        /*
         * This exact pose belongs to the live authoritative body and proves a
         * reachable standing point. It replaces the old model-supplied rally
         * coordinates, which could be invented or stale.
         */
        localRallyPoint = snapshot.core().position();
        recoveringSafetyReserve = false;
        dragonRangedMode = false;
        dragonRetreatDirection = null;
        sawCageBarBeyondReach = false;
        cageTowered = false;
        cageLandingVerified = false;
        cageLandingVerifiedRevision = -1;
        crystalStandOffScans = 0;
        crystalStandOffAttempts = 0;
        crystalCageInspectionTurns = 0;
        crystalLaneAttempts = 0;
        crystalLaneScanTurns = 0;
        cageStatus = CageStatus.NONE;
        travelPurpose = TravelPurpose.NONE;
        cageCrystalId = null;
        cageLastSeenPosition = null;
        crystalStandOffPosition = null;
        cagePlan = null;
        shot = null;
        shotParameters = null;
        shotTargetDragon = false;
        travel = null;
        travelParameters = null;
        islandIngress = null;
        islandIngressParameters = null;
        rallyStepTarget = null;
        rallyStepTicks = 0;
        rallyStepStarts = 0;
        rallyStepTimeouts = 0;
        rallyStepAwaitingFreshObservation = false;
        rallyStepObservationRevision = -1L;
        previousRallyStepTarget = null;
        lastRallyFailure = "";
        lastInternalFailure = "";
        crystalStandOff = null;
        crystalStandOffParameters = null;
        crystalLane = null;
        crystalLaneParameters = null;
        cageBreak = null;
        cageBreakParameters = null;
        if (skyBreak != null && skyBreakParameters != null) {
            try {
                skyBreak.cancel(context, skyBreakParameters);
            } catch (RuntimeException ignored) {
                interactions.abortMining();
            }
        }
        skyBreak = null;
        skyBreakParameters = null;
        skyBreakTarget = null;
        cageTower = null;
        cageTowerParameters = null;
        cageDescent = null;
        cageDescentParameters = null;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final FightEnderDragonParameters parameters
    ) {
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context, parameters);
        } catch (RuntimeException exception) {
            lastInternalFailure = exception.getClass().getSimpleName()
                    + ":"
                    + (exception.getMessage() == null
                        ? ""
                        : exception.getMessage().replace('"', '\''));
            return fail(context, NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final FightEnderDragonParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"shots\":%d,\"melee\":%d,"
                            + "\"meleeReachMisses\":%d,"
                            + "\"rangedDragonShotsSinceMeleeRetry\":%d,"
                            + "\"dragonRangedMode\":%s,"
                            + "\"dragonRetreatTicksRemaining\":%d,"
                            + "\"scanTurns\":%d,\"rallyAttempts\":%d,"
                            + "\"ingressAttempts\":%d,"
                            + "\"islandIngressActive\":%s,"
                            + "\"lastIngressResult\":\"%s\","
                            + "\"islandIngressCheckpoint\":\"%s\","
                            + "\"skyBlocksMined\":%d,"
                            + "\"skyBlocksSinceRally\":%d,"
                            + "\"skyBreakAttempts\":%d,"
                            + "\"lastSkyFailure\":\"%s\","
                            + "\"lastSkyOccludedBlock\":\"%s\","
                            + "\"skyBreakTarget\":\"%s\","
                            + "\"cageBarsMined\":%d,"
                            + "\"cageApproaches\":%d,"
                            + "\"cageRecoveryTurns\":%d,"
                            + "\"cageTowerBlocks\":%d,"
                            + "\"cageLandingVerified\":%s,"
                            + "\"crystalStandOffScans\":%d,"
                            + "\"crystalStandOffAttempts\":%d,"
                            + "\"crystalCageInspectionTurns\":%d,"
                            + "\"crystalLaneAttempts\":%d,"
                            + "\"crystalLaneScanTurns\":%d,"
                            + "\"recoveringSafetyReserve\":%s,"
                            + "\"cageStatus\":\"%s\","
                            + "\"visibleEntityTypes\":\"%s\","
                            + "\"bodyPosition\":\"%s\","
                            + "\"rallyStepTarget\":\"%s\","
                            + "\"rallyStepTicks\":%d,"
                            + "\"rallyStepStarts\":%d,"
                            + "\"rallyStepTimeouts\":%d,"
                            + "\"rallyStepAwaitingFreshObservation\":%s,"
                            + "\"rallyStepObservationRevision\":%d,"
                            + "\"lastRallyFailure\":\"%s\","
                            + "\"lastInternalFailure\":\"%s\"}",
                        phase.name(),
                        shotsDispatched,
                        meleeAttacks,
                        meleeReachMisses,
                        rangedDragonShotsSinceMeleeRetry,
                        dragonRangedMode,
                        dragonRetreatTicksRemaining,
                        scanTurns,
                        rallyAttempts,
                        ingressAttempts,
                        islandIngress != null,
                        lastIngressResult.replace("\"", "'"),
                        islandIngress == null
                            ? lastIslandIngressCheckpoint
                            : islandIngress.checkpoint(
                                    context,
                                    islandIngressParameters
                              ).payload().replace("\"", "'")
                                .replace("\\", "/"),
                        skyBlocksMined,
                        skyBlocksSinceRally,
                        skyBreakAttempts,
                        lastSkyFailure.replace("\"", "'"),
                        lastSkyOccludedBlock == null
                                ? ""
                                : lastSkyOccludedBlock.toString(),
                        skyBreakTarget == null
                                ? ""
                                : skyBreakTarget.toString(),
                        cageBarsMined,
                        cageApproachAttempts,
                        cageScanTurns,
                        cagePlan == null
                            ? 0
                            : cagePlan.towerBlocks(),
                        cageLandingVerified,
                        crystalStandOffScans,
                        crystalStandOffAttempts,
                        crystalCageInspectionTurns,
                        crystalLaneAttempts,
                        crystalLaneScanTurns,
                        recoveringSafetyReserve,
                        cageStatus.name(),
                        visibleEntityTypes(),
                        currentBodyPosition(),
                        rallyStepTarget == null
                            ? ""
                            : rallyStepTarget.x() + ","
                                + rallyStepTarget.y() + ","
                                + rallyStepTarget.z(),
                        rallyStepTicks,
                        rallyStepStarts,
                        rallyStepTimeouts,
                        rallyStepAwaitingFreshObservation,
                        rallyStepObservationRevision,
                        lastRallyFailure.replace("\"", "'"),
                        lastInternalFailure.replace("\"", "'")
                )
        );
    }

    private String visibleEntityTypes() {
        return coreFrames.current()
                .map(frame -> frame.visibleEntities().stream()
                        .limit(12)
                        .map(entity -> entity.entityTypeId())
                        .distinct()
                        .sorted()
                        .reduce((left, right) -> left + "|" + right)
                        .orElse(""))
                .orElse("");
    }

    private String currentBodyPosition() {
        return coreFrames.current()
                .map(frame -> String.format(
                        Locale.ROOT,
                        "%.1f,%.1f,%.1f",
                        frame.position().x(),
                        frame.position().y(),
                        frame.position().z()
                ))
                .orElse("");
    }

    @Override
    public void cancel(
            final SkillContext context,
            final FightEnderDragonParameters parameters
    ) {
        cancelChildren(context);
        quiesce();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final FightEnderDragonParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(
                    SkillFailure.of(NAME + ".invalid_state")
            );
        };
    }

    private SkillTickResult tickSafely(
            final SkillContext context,
            final FightEnderDragonParameters parameters
    ) {
        if (victory.dragonKilled(context.goalRevision())) {
            cancelChildren(context);
            quiesce();
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (context.gameTick() - startedAtTick
                >= parameters.timeoutTicks()) {
            return fail(context, NAME + ".timed_out");
        }
        final SnapshotValidation validation =
                validateSnapshot(parameters);
        if (validation.failure().isPresent()) {
            return fail(
                    context,
                    validation.failure().orElseThrow()
            );
        }
        final Snapshot snapshot =
                validation.snapshot().orElseThrow();
        final CoreSkillFrame frame = snapshot.core();
        if (frame.observationRevision() < lastObservationRevision) {
            return fail(context, NAME + ".stale_observation");
        }
        final boolean fresh = frame.observationRevision()
                > lastObservationRevision;
        lastObservationRevision = Math.max(
                lastObservationRevision,
                frame.observationRevision()
        );
        final Optional<SkillTickResult> projectileResponse =
                evadeVisibleProjectile(context, frame, fresh);
        if (projectileResponse.isPresent()) {
            return projectileResponse.orElseThrow();
        }
        final Optional<SkillTickResult> damageResponse =
                evadeRecentDamage(context, frame);
        if (damageResponse.isPresent()) {
            return damageResponse.orElseThrow();
        }
        if (phase == Phase.OPENING_CAGE
                && immediateDragonIndex(frame).isPresent()) {
            abortCageBreak(context);
            phase = Phase.SEARCHING;
            cageStatus = CageStatus.SEEKING_VISIBLE_BAR;
            nextActionTick = context.gameTick();
        }
        if (healthTooLow(context, frame)
                || frame.foodLevel()
                    < (context.hardcore() ? 6 : 2)) {
            recoveringSafetyReserve = true;
            if (!core.stop().accepted()) {
                return fail(
                        context,
                        NAME + ".recovery_stop_rejected"
                );
            }
            /*
             * Survival control executes after the skill lease. Relinquishing
             * hostile-proximity ownership here lets it eat, guard, or evade
             * at 20 TPS; the dragon coordinator keeps its bounded deadline
             * and resumes only when both reserves are safe again.
             */
            return SkillTickResult.running(fresh, true);
        }
        recoveringSafetyReserve = false;
        return switch (phase) {
            case SEARCHING -> search(
                    context,
                    parameters,
                    snapshot,
                    fresh
            );
            case ALIGNING_SKY -> tickSkyAlignment(
                    context,
                    frame,
                    fresh
            );
            case OPENING_SKY -> tickSkyBreak(
                    context,
                    fresh
            );
            case SHOOTING -> tickShot(context, fresh);
            case RETREATING_DRAGON -> tickDragonRangedRetreat(
                    context,
                    frame,
                    fresh
            );
            case REPOSITIONING_CRYSTAL ->
                    tickCrystalStandOff(
                            context,
                            parameters,
                            fresh
                    );
            case REPOSITIONING_CRYSTAL_LANE ->
                    tickCrystalLane(context, fresh);
            case TRAVELLING -> tickTravel(context, fresh);
            case RALLY_STEPPING -> tickRallyStep(context, fresh);
            case REENTERING_ISLAND -> tickIslandReentry(context, fresh);
            case OPENING_CAGE -> tickCageBreak(
                    context,
                    fresh
            );
            case TOWERING_CAGE -> tickCageTower(
                    context,
                    parameters,
                    fresh
            );
            case PREPARING_CAGE_TOWER ->
                    prepareCageTower(
                            context,
                            parameters,
                            frame,
                            fresh
                    );
            case PREPARING_CAGE_DESCENT ->
                    prepareCageDescent(
                            context,
                            frame,
                            fresh
                    );
            case DESCENDING_CAGE -> tickCageDescent(
                    context,
                    fresh
            );
            default -> SkillTickResult.failed(
                    NAME + ".invalid_state"
            );
        };
    }

    /**
     * Dragon fireballs are fair first-person projectile observations, not a
     * reason for the emergency lane to freeze the entire fight.  On a fresh
     * visible projectile signal, turn away and issue one bounded sprint/strafe
     * input.  If the projectile is not in the current semantic list, the
     * directionless danger signal still triggers a short alternating strafe;
     * no hidden projectile position or world scan is used.
     */
    private Optional<SkillTickResult> evadeVisibleProjectile(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        final Optional<VisibleEntity> projectile = frame.visibleEntities()
                .stream()
                .filter(VisibleEntity::projectile)
                .filter(FightEnderDragonSkill::projectileThreatensBody)
                .min(Comparator.comparingDouble(VisibleEntity::distance));
        final boolean danger = frame.dangerSignals().stream().anyMatch(
                signal -> signal.kind()
                        == dev.mcai.companion.perception.DangerKind
                            .PROJECTILE_PROXIMITY
                    && signal.severity() >= 0.35
        );
        if (projectile.isEmpty() && !danger) {
            return Optional.empty();
        }
        if (lastDefensiveDodgeTick >= 0
                && context.gameTick() - lastDefensiveDodgeTick
                    < DEFENSIVE_DODGE_COOLDOWN_TICKS) {
            return Optional.empty();
        }
        final PerceptionVec3 away = projectile
                .map(entity -> frame.position().subtract(entity.position()))
                .filter(vector -> vector.lengthSquared() > 1.0E-12)
                .orElseGet(() -> new PerceptionVec3(
                        ((frame.gameTime() / 6L) & 1L) == 0L ? 1.0 : -1.0,
                        0.0,
                        0.35
                ));
        final LookIntent look = lookAt(
                frame.eyePosition(),
                frame.eyePosition().add(away)
        );
        if (!core.look(look).accepted()) {
            return Optional.of(fail(
                    context,
                    NAME + ".projectile_dodge_look_rejected"
            ));
        }
        final PerceptionVec3 horizontal = new PerceptionVec3(
                away.x(),
                0.0,
                away.z()
        );
        final PerceptionVec3 desired = horizontal.lengthSquared() > 1.0E-12
                ? horizontal.normalized()
                : new PerceptionVec3(1.0, 0.0, 0.0);
        final PerceptionVec3 horizontalForward = new PerceptionVec3(
                frame.lookDirection().x(),
                0.0,
                frame.lookDirection().z()
        );
        final PerceptionVec3 forward = horizontalForward.lengthSquared()
                > 1.0E-12
                ? horizontalForward.normalized()
                : new PerceptionVec3(0.0, 0.0, 1.0);
        final PerceptionVec3 left = new PerceptionVec3(
                forward.z(),
                0.0,
                -forward.x()
        );
        final ActionOutcome moved = core.move(new MovementIntent(
                desired.dot(forward),
                desired.dot(left),
                true,
                false
        ));
        if (!moved.accepted()) {
            return Optional.of(fail(
                    context,
                    NAME + ".projectile_dodge_move_rejected"
            ));
        }
        lastDefensiveDodgeTick = context.gameTick();
        nextActionTick = context.gameTick() + 1;
        return Optional.of(SkillTickResult.running(true, true));
    }

    /**
     * Dragon breath and multipart contact can arrive as a fair recent-damage
     * cue without a projectile entity in the current semantic sample.  Keep
     * the fight alive with one bounded first-person side/back step, then let
     * the ordinary combat phase resume; this never selects an unseen target
     * or edits the world.
     */
    private Optional<SkillTickResult> evadeRecentDamage(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<dev.mcai.companion.perception.DangerSignal> damage =
                frame.dangerSignals().stream()
                        .filter(signal ->
                                signal.kind() == DangerKind.THREAT_CONTACT)
                        .filter(signal ->
                                signal.provenance()
                                        == PerceptionProvenance
                                                .RECENT_DAMAGE_EVENT
                                || signal.provenance()
                                        == PerceptionProvenance
                                                .PHYSICAL_CONTACT)
                        .filter(signal -> signal.severity() >= 0.35)
                        .max(Comparator.comparingDouble(
                                dev.mcai.companion.perception.DangerSignal
                                        ::severity
                        ));
        if (damage.isEmpty()) {
            return Optional.empty();
        }
        if (lastDefensiveDodgeTick >= 0
                && context.gameTick() - lastDefensiveDodgeTick
                    < DEFENSIVE_DODGE_COOLDOWN_TICKS) {
            return Optional.empty();
        }
        final PerceptionVec3 toward = damage.orElseThrow()
                .contactDirection()
                .filter(vector -> vector.lengthSquared() > 1.0E-12)
                .orElseGet(() -> new PerceptionVec3(
                        ((context.gameTick() / DEFENSIVE_DODGE_COOLDOWN_TICKS)
                                & 1L) == 0L ? 1.0 : -1.0,
                        0.0,
                        0.35
                ));
        final PerceptionVec3 horizontalToward = new PerceptionVec3(
                toward.x(),
                0.0,
                toward.z()
        );
        if (horizontalToward.lengthSquared() > 1.0E-12) {
            lastThreatDirection = horizontalToward.normalized();
            scanBaseYaw = yawFromDirection(lastThreatDirection);
        }
        final PerceptionVec3 horizontalAway = new PerceptionVec3(
                -toward.x(),
                0.0,
                -toward.z()
        );
        final PerceptionVec3 away = horizontalAway.lengthSquared() > 1.0E-12
                ? horizontalAway.normalized()
                : new PerceptionVec3(
                        ((context.gameTick() / DEFENSIVE_DODGE_COOLDOWN_TICKS)
                                & 1L) == 0L ? 1.0 : -1.0,
                        0.0,
                        0.0
                );
        final PerceptionVec3 horizontalForward = new PerceptionVec3(
                frame.lookDirection().x(),
                0.0,
                frame.lookDirection().z()
        );
        final PerceptionVec3 forward = horizontalForward.lengthSquared()
                > 1.0E-12
                ? horizontalForward.normalized()
                : new PerceptionVec3(0.0, 0.0, 1.0);
        final PerceptionVec3 left = new PerceptionVec3(
                forward.z(),
                0.0,
                -forward.x()
        );
        if (!core.look(lookAt(
                frame.eyePosition(),
                frame.eyePosition().add(away)
        )).accepted()) {
            return Optional.of(fail(
                    context,
                    NAME + ".damage_dodge_look_rejected"
            ));
        }
        final ActionOutcome moved = core.move(new MovementIntent(
                away.dot(forward),
                away.dot(left),
                true,
                false
        ));
        if (!moved.accepted()) {
            return Optional.of(fail(
                    context,
                    NAME + ".damage_dodge_move_rejected"
            ));
        }
        lastDefensiveDodgeTick = context.gameTick();
        nextActionTick = context.gameTick() + 1;
        return Optional.of(SkillTickResult.running(true, true));
    }

    private void abortCageBreak(final SkillContext context) {
        if (cageBreak != null && cageBreakParameters != null) {
            try {
                cageBreak.cancel(context, cageBreakParameters);
            } catch (RuntimeException ignored) {
                interactions.abortMining();
            }
        }
        cageBreak = null;
        cageBreakParameters = null;
    }

    /**
     * Opens only a currently observed natural End-stone block in the bounded
     * overhead or lateral clearance envelope. The block identity, face, hit
     * point and revision all come from the first-person semantic frame;
     * BreakBlockSkill performs the final retained/current crosshair validation
     * before any vanilla mining packet is sent.
     */
    private SkillTickResult prepareSkyClearance(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final CoreSkillFrame frame,
            final VisibleBlockFace overhead,
            final boolean overheadTarget,
            final boolean fresh
    ) {
        if (skyBlocksMined >= MAXIMUM_SKY_BLOCKS_MINED
                || skyBreakAttempts >= MAXIMUM_SKY_BREAK_ATTEMPTS) {
            return fail(
                    context,
                    NAME + ".sky_clearance_budget_exhausted"
            );
        }
        if (!frame.onGround() || frame.inWater()) {
            return SkillTickResult.running(fresh, true);
        }
        final Optional<String> tool = preferredCageMiningTool(frame);
        if (tool.isEmpty()) {
            return fail(context, NAME + ".sky_pickaxe_required");
        }
        if (!tool.orElseThrow().equals(frame.mainHand().itemId())) {
            final InventoryOperationResult equipped = inventory.equip(
                    new EquipItemParameters(
                            tool.orElseThrow(),
                            EquipmentTarget.MAINHAND
                    )
            );
            if (!equipped.succeeded()) {
                return fail(
                        context,
                        equipped.failure().orElseThrow()
                );
            }
            return SkillTickResult.running(true, true);
        }
        final PerceptionVec3 toBlock = overhead.hitPosition()
                .subtract(frame.eyePosition());
        skyBreakTarget = observedTarget(frame, overhead);
        if (overheadTarget
                && overhead.distance() > SKY_BREAK_STANDING_REACH) {
            if (!core.jump().accepted()) {
                return fail(context, NAME + ".sky_jump_rejected");
            }
            skyJumpPending = true;
            skyAlignmentTicks = 0;
            skyAlignmentReadyTick = -1L;
            phase = Phase.ALIGNING_SKY;
            nextActionTick = context.gameTick() + 1;
            return SkillTickResult.running(true, true);
        }
        if (overhead.distance() > SKY_BREAK_REACH
                || angularError(frame.lookDirection(), toBlock)
                    > SKY_BREAK_ALIGNMENT_DEGREES) {
            if (!core.stop().accepted()
                    || !core.look(lookAt(
                            frame.eyePosition(),
                            overhead.hitPosition()
                    )).accepted()) {
                return fail(context, NAME + ".sky_alignment_rejected");
            }
            skyAlignmentTicks = 0;
            skyAlignmentReadyTick = -1L;
            phase = Phase.ALIGNING_SKY;
            nextActionTick = context.gameTick() + 1;
            return SkillTickResult.running(true, true);
        }
        return startAlignedSkyBreak(
                context,
                parameters,
                frame,
                overhead,
                fresh
        );
    }

    private SkillTickResult tickSkyAlignment(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (skyBreakTarget == null
                || frame.inWater()
                || !frame.onGround() && !skyJumpPending) {
            cancelSkyBreak(context);
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick() + 1;
            return SkillTickResult.running(true, true);
        }
        if (!core.stop().accepted()) {
            return fail(context, NAME + ".sky_alignment_stop_rejected");
        }
        final Optional<VisibleBlockFace> visible =
                frame.visibleBlockFaces().stream()
                        .filter(face -> sameObservedBlock(
                                face,
                                skyBreakTarget
                        ))
                        .filter(face -> "minecraft:end_stone".equals(
                                face.blockTypeId()
                        ))
                        .findFirst();
        /* The body can turn or move while the server applies the queued look.
         * If the authored surface is no longer in the newest fair fan, keep
         * no stale mining target alive: a real player would reacquire the
         * wall from the new eye position.  Retrying an absent target is what
         * turns an ordinary camera update into action_target_occluded. */
        if (visible.isEmpty()) {
            /* Turning toward a peripheral wall can briefly remove the
             * retained block from the finite fan even though it remains the
             * same observed, reachable block.  Reacquire it with a bounded
             * first-person look before abandoning the action; no world read
             * or hidden target refresh is performed. */
            if (skyAlignmentTicks < 4) {
                final PerceptionVec3 rememberedCenter = new PerceptionVec3(
                        skyBreakTarget.x() + 0.5,
                        skyBreakTarget.y() + 0.5,
                        skyBreakTarget.z() + 0.5
                );
                if (!core.stop().accepted()
                        || !core.look(lookAt(
                                frame.eyePosition(),
                                rememberedCenter
                        )).accepted()) {
                    return fail(
                            context,
                            NAME + ".sky_alignment_look_rejected"
                    );
                }
                skyAlignmentTicks++;
                return SkillTickResult.running(true, true);
            }
            lastSkyFailure = "sky_target_not_current";
            cancelSkyBreak(context);
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick() + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        final Optional<VisibleBlockFace> current =
                interactionFrames.currentCrosshairBlock()
                        .filter(face -> sameObservedBlock(
                                face,
                                skyBreakTarget
                        ))
                        .filter(face ->
                                "minecraft:end_stone".equals(
                                        face.blockTypeId()
                                ));
        if ((!fresh && skyAlignmentReadyTick < 0L) || current.isEmpty()) {
            if (!core.look(lookAt(
                            frame.eyePosition(),
                            visible.orElseThrow().hitPosition()
                    )).accepted()) {
                return fail(context, NAME + ".sky_alignment_look_rejected");
            }
            skyAlignmentTicks++;
            if (skyAlignmentTicks > SKY_BREAK_ALIGNMENT_TICKS) {
                cancelSkyBreak(context);
                phase = Phase.SEARCHING;
                skyBreakAttempts++;
                nextActionTick = context.gameTick() + SCAN_INTERVAL_TICKS;
            }
            return SkillTickResult.running(true, true);
        }
        /* The look command is applied by the vanilla server action pump at
         * the end of the preceding tick.  Defer the mining start by one
         * server tick after a matching crosshair sample so the actuator and
         * the interaction frame cannot observe different rays. */
        if (skyAlignmentReadyTick < 0L) {
            skyAlignmentReadyTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() <= skyAlignmentReadyTick) {
            return SkillTickResult.running(true, true);
        }
        /* The alignment frame is deliberately rebound to the exact current
         * crosshair sample.  Reusing the initial peripheral fan revision
         * would make BreakBlockSkill reject a fair target even though the
         * player's crosshair now selects the same block. */
        skyBreakTarget = observedTarget(frame, current.orElseThrow());
        return startAlignedSkyBreak(
                context,
                current.orElseThrow(),
                true
        );
    }

    private SkillTickResult startAlignedSkyBreak(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final CoreSkillFrame frame,
            final VisibleBlockFace face,
            final boolean fresh
    ) {
        if (skyBreakTarget == null) {
            skyBreakTarget = observedTarget(frame, face);
        }
        return startAlignedSkyBreak(context, face, fresh);
    }

    private SkillTickResult startAlignedSkyBreak(
            final SkillContext context,
            final VisibleBlockFace face,
            final boolean fresh
    ) {
        if (skyBreakTarget == null) {
            return SkillTickResult.running(fresh, true);
        }
        skyBreakParameters = new BreakBlockParameters(
                DimensionRef.END,
                skyBreakTarget
        );
        skyBreak = new BreakBlockSkill(
                expectedPlayerId,
                interactions,
                interactionFrames,
                InteractionSkillPolicy.defaults()
        );
        final Optional<SkillFailure> precondition =
                skyBreak.preconditions(context, skyBreakParameters);
        if (precondition.isPresent()) {
            lastSkyFailure = precondition.orElseThrow().code();
            cancelSkyBreak(context);
            phase = Phase.SEARCHING;
            skyBreakAttempts++;
            nextActionTick = context.gameTick() + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(fresh, true);
        }
        skyBreak.start(context, skyBreakParameters);
        skyJumpPending = false;
        skyBreakAttempts++;
        skyAlignmentTicks = 0;
        phase = Phase.OPENING_SKY;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickSkyBreak(
            final SkillContext context,
            final boolean fresh
    ) {
        if (skyBreak == null || skyBreakParameters == null) {
            return fail(context, NAME + ".sky_break_state_missing");
        }
        final SkillTickResult result = skyBreak.tick(
                context,
                skyBreakParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            skyBlocksMined++;
            skyBlocksSinceRally++;
            lastSkyOccludedBlock = null;
            skyOcclusionRetries = 0;
            cancelSkyBreak(context);
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick() + 2;
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            lastSkyFailure = result.failure()
                    .map(SkillFailure::code)
                    .orElse("break_block.unknown_failure");
            final ObservedBlockTarget failedTarget = skyBreakTarget;
            cancelSkyBreak(context);
            if (lastSkyFailure.endsWith(".action_target_occluded")
                    && failedTarget != null
                    && skyOcclusionRetries
                        < MAXIMUM_SKY_OCCLUSION_RETRIES) {
                skyBreakTarget = failedTarget;
                skyOcclusionRetries++;
                skyAlignmentTicks = 0;
                skyAlignmentReadyTick = -1L;
                phase = Phase.ALIGNING_SKY;
                nextActionTick = context.gameTick() + 1;
                core.stop();
                coreFrames.current().ifPresent(currentFrame ->
                                currentFrame.visibleBlockFaces().stream()
                                .filter(face -> sameObservedBlock(
                                        face,
                                        failedTarget
                                ))
                                .findFirst()
                                .ifPresent(face -> core.look(lookAt(
                                        currentFrame.eyePosition(),
                                        face.hitPosition()
                                )))
                );
                return SkillTickResult.running(true, true);
            }
            if (lastSkyFailure.endsWith(".action_target_occluded")
                    && failedTarget != null) {
                lastSkyOccludedBlock = new GridPos(
                        failedTarget.x(),
                        failedTarget.y(),
                        failedTarget.z()
                );
                skyOcclusionRetries = 0;
            }
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick() + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                result.madeProgress() || fresh,
                result.safeCheckpoint()
        );
    }

    private void cancelSkyBreak(final SkillContext context) {
        if (skyBreak != null && skyBreakParameters != null) {
            try {
                skyBreak.cancel(context, skyBreakParameters);
            } catch (RuntimeException ignored) {
                interactions.abortMining();
            }
        }
        skyBreak = null;
        skyBreakParameters = null;
        skyBreakTarget = null;
        skyAlignmentTicks = 0;
        skyAlignmentReadyTick = -1L;
        skyJumpPending = false;
    }

    private SkillTickResult search(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final Snapshot snapshot,
            final boolean fresh
    ) {
        final CoreSkillFrame frame = snapshot.core();
        if (context.gameTick() < nextActionTick) {
            core.stop();
            return SkillTickResult.running(fresh, true);
        }
        final Optional<VisibleBlockFace> overhead =
                visibleOverheadEndStone(frame, lastSkyOccludedBlock);
        final Optional<VisibleBlockFace> reachableOverhead = overhead
                .filter(face -> face.distance() <= SKY_BREAK_STANDING_REACH);
        final Optional<VisibleBlockFace> lateral =
                visibleLateralEndStone(frame, lastSkyOccludedBlock);
        final Optional<VisibleBlockFace> clearanceTarget =
                selectSkyClearanceTarget(
                        frame,
                        reachableOverhead,
                        lateral
                );
        final Optional<VisibleBlockFace> centerwardWall =
                visibleCenterwardEndStoneFace(frame);
        if (immediateDragonIndex(frame).isEmpty()) {
            /* Obsidian crystal pillars are not survival-mining targets.  If
             * the newest first-person fan proves that the centerward cell is
             * one, prefer a freshly observed lateral standing cell so the
             * body can walk around the pillar instead of repeatedly pushing
             * into its opaque face. */
            if (visibleCenterwardObsidianWall(frame)) {
                if (skyBlocksMined >= SKY_BLOCKS_BEFORE_CENTERWARD_TRAVEL
                        && EndArenaTopology.horizontalRadius(
                                frame.position()
                        ) > FIGHT_OBSTACLE_ESCAPE_RADIUS) {
                    /* Once the bounded overhead reserve has established the
                     * obstruction, let the observation-bound ingress child
                     * own the escape.  Trying an exposed lateral cell first
                     * can oscillate inside the one-cell spawn-platform
                     * corridor: the semantic frame proves that cell clear,
                     * but vanilla collision still leaves the body in the
                     * same feet grid.  Re-entry is fair and bounded; it can
                     * tower, mine observed End stone, or bridge only after
                     * the next fresh support proof. */
                    return startIslandReentry(
                            context,
                            fresh,
                            FIGHT_REENTRY_TARGET_RADIUS
                    );
                }
                final Optional<GridPos> sideStep =
                        selectObservedSideStep(frame);
                if (sideStep.isPresent()) {
                    return startObservedRallyStep(
                            context,
                            frame,
                            sideStep.orElseThrow(),
                            fresh
                    );
                }
                /* The semantic fan is intentionally sparse: an opaque
                 * pillar can prove the forward wall while the adjacent
                 * standing cell is still unknown.  Do not infer that cell
                 * or mine the pillar.  Turn to one of the two observed
                 * lateral frontiers and wait for the next first-person
                 * frame; that frame can then authorize a normal one-cell
                 * detour. */
                return requestObservedSideDetourObservation(
                        context,
                        frame,
                        fresh
                );
            }
            /* Clear a directly observed centerward wall before choosing a
             * lateral one-cell step.  The lateral candidate can be legal yet
             * lead under the same lip forever, while the wall face already
             * provides the exact fair BreakBlock target needed to expose the
             * next navigation/entity frame. */
            if (centerwardWall.isPresent()
                    && frame.dangerSignals().stream().noneMatch(
                            signal -> signal.severity() >= 0.65
                    )
                    && skyBlocksMined < MAXIMUM_SKY_BLOCKS_MINED
                    && skyBreakAttempts < MAXIMUM_SKY_BREAK_ATTEMPTS) {
                return prepareSkyClearance(
                        context,
                        parameters,
                        frame,
                        centerwardWall.orElseThrow(),
                        false,
                        fresh
                );
            }
            final Optional<GridPos> observedStep =
                    selectObservedCenterwardStep(frame);
            final Optional<GridPos> detour = observedStep.isPresent()
                    || skyBlocksMined <= 0
                    ? observedStep
                    : selectObservedSideStep(frame);
            if (detour.isPresent()) {
                return startObservedRallyStep(
                        context,
                        frame,
                        detour.orElseThrow(),
                    fresh
                );
            }
        }
        /* A natural End entry can be inside the nominal combat radius while
         * a one- or two-block End-stone wall still occupies the centerward
         * feet/head column.  Do not keep sweeping the sky or hand this frame
         * to a blind travel fallback: if the current first-person fan proves
         * an ordinary side face, open exactly that observed block through the
         * same BreakBlock/retained-crosshair path used for sky clearance.
         * The helper rejects UP/DOWN faces, support cells and non-centerward
         * terrain, so this remains a bounded player-like excavation. */
        if (centerwardWall.isPresent()
                && immediateDragonIndex(frame).isEmpty()
                && frame.dangerSignals().stream().noneMatch(
                        signal -> signal.severity() >= 0.65
                )
                && skyBlocksMined < MAXIMUM_SKY_BLOCKS_MINED
                && skyBreakAttempts < MAXIMUM_SKY_BREAK_ATTEMPTS) {
            return prepareSkyClearance(
                    context,
                    parameters,
                    frame,
                    centerwardWall.orElseThrow(),
                    false,
                    fresh
            );
        }
        /* The vanilla End entry can leave the body inside the 56-block
         * combat envelope but behind a natural End-stone lip. That is still
         * an ingress problem: mining only the overhead column never exposes
         * the central island. Once the bounded overhead reserve is spent,
         * reuse the fair ingress child to open the observed wall and return
         * on a fresh standing proof. */
        if (skyBlocksSinceRally
                    >= SKY_BLOCKS_BEFORE_CENTERWARD_TRAVEL
                && EndArenaTopology.horizontalRadius(frame.position())
                    > FIGHT_REENTRY_TARGET_RADIUS
                && visibleCenterwardEndStoneWall(frame)
                && immediateDragonIndex(frame).isEmpty()) {
            return startIslandReentry(context, fresh);
        }
        if (clearanceTarget.isPresent()
                && immediateDragonIndex(frame).isEmpty()
                && frame.dangerSignals().stream().noneMatch(
                        signal -> signal.severity() >= 0.65
                )
                && clearanceTarget.isPresent()
                && skyBlocksMined < MAXIMUM_SKY_BLOCKS_MINED
                && skyBreakAttempts < MAXIMUM_SKY_BREAK_ATTEMPTS
                && (lateral.isPresent() || !freshHeadClearance(frame))
                && (skyBlocksSinceRally
                    < SKY_BLOCKS_BEFORE_CENTERWARD_TRAVEL
                    || lateral.isPresent())) {
            return prepareSkyClearance(
                    context,
                    parameters,
                    frame,
                    clearanceTarget.orElseThrow(),
                    clearanceTarget.orElseThrow().equals(
                            reachableOverhead.orElse(null)
                    ),
                    fresh
            );
        }
        if (shouldDescendFromCage(frame)) {
            return startOrPrepareCageDescent(
                    context,
                    parameters,
                    frame,
                    fresh
            );
        }
        final Optional<Integer> immediateDragon =
                immediateDragonIndex(frame);
        if (immediateDragon.isEmpty()
                && (closestCrystalWithAlignedCageBar(frame).isPresent()
                || boundBlockedCrystal(frame).isPresent()
                || clearCrystalIndex(frame).isEmpty()
                    && hasBlockedCrystal(frame))) {
            return handleBlockedCrystal(
                    context,
                    parameters,
                    snapshot,
                    fresh
            );
        }
        rememberUnsafeClearCrystal(frame);
        if (crystalStandOffPosition != null) {
            return prepareCrystalStandOff(
                    context,
                    parameters,
                    frame,
                    fresh
            );
        }
        if (!cageTowered && cagePlan != null) {
            clearCageTraversalPlan();
        }
        cageScanTurns = 0;
        cageStatus = CageStatus.NONE;
        sawCageBarBeyondReach = false;
        final Optional<Integer> clearCrystal = clearCrystalIndex(frame);
        final boolean activeDragonThreat = frame.dangerSignals().stream()
                .anyMatch(signal ->
                        (signal.kind() == DangerKind.THREAT_CONTACT
                                || signal.kind()
                                        == DangerKind.PROJECTILE_PROXIMITY)
                                && signal.severity() >= 0.35
                );
        boolean crystalLaneBlocked = false;
        if (!activeDragonThreat && clearCrystal.isPresent()) {
            final VisibleEntity crystal = frame.visibleEntities()
                    .get(clearCrystal.orElseThrow());
            crystalLaneBlocked = EndCrystalStandOffPlanner.dragonBlocksFiringLane(
                    frame,
                    crystal
            );
            if (!crystalLaneBlocked) {
                crystalLaneAttempts = 0;
                crystalLaneScanTurns = 0;
            } else if (crystalLaneAttempts
                    < MAXIMUM_CRYSTAL_LANE_ATTEMPTS) {
                final Optional<GridPos> lane =
                        EndCrystalStandOffPlanner.selectFiringLane(
                                frame,
                                crystal.position(),
                                context.hardcore()
                        ).filter(candidate ->
                                !candidate.equals(frame.feet())
                        );
                if (lane.isPresent()) {
                    return startCrystalLane(
                            context,
                            parameters,
                            lane.orElseThrow(),
                            fresh
                    );
                }
                if (crystalLaneScanTurns
                        < MAXIMUM_CRYSTAL_LANE_SCANS) {
                    final PerceptionVec3 toCrystal = crystal.position()
                            .subtract(frame.position());
                    final PerceptionVec3 horizontal = new PerceptionVec3(
                            toCrystal.x(),
                            0.0,
                            toCrystal.z()
                    );
                    final PerceptionVec3 forward = horizontal.lengthSquared()
                            > 1.0E-12
                            ? horizontal.normalized()
                            : new PerceptionVec3(0.0, 0.0, 1.0);
                    final PerceptionVec3 side = new PerceptionVec3(
                            forward.z(),
                            0.0,
                            -forward.x()
                    );
                    final double sign =
                            (crystalLaneScanTurns & 1) == 0 ? 0.85 : -0.85;
                    if (!core.move(MovementIntent.STOPPED).accepted()
                            || !core.look(lookAt(
                                    frame.eyePosition(),
                                    frame.eyePosition().add(
                                            forward.add(side.scale(sign))
                                    )
                            )).accepted()) {
                        return fail(
                                context,
                                NAME + ".crystal_lane_scan_rejected"
                        );
                    }
                    if (fresh) {
                        crystalLaneScanTurns++;
                    }
                    nextActionTick = context.gameTick()
                            + SCAN_INTERVAL_TICKS;
                    return SkillTickResult.running(true, true);
                }
            }
        }
        /*
         * A nearby dragon is not sufficient reason to ignore an exposed
         * crystal. Vanilla crystals continuously heal the dragon, so a
         * clear crystal must be removed first whenever the body is not
         * currently under contact or projectile pressure. During an active
         * threat, keep the dragon as the immediate target so emergency
         * melee/ranged handling can take over without pausing for a crystal.
         */
        /*
         * A dragon can hover directly between the body and an exposed
         * crystal. After the bounded lane observations above, there may be no
         * fair standing cell from which the crystal ray is clear at all. Do
         * not stare at that crystal forever: choose the nearest observed
         * dragon part and use the ordinary ranged path until the dragon moves
         * or its health changes. This is still a normal first-person target;
         * it never reads hidden entity state or fabricates a projectile hit.
         */
        final boolean useDragonFallback = crystalLaneBlocked
                && crystalLaneScanTurns >= MAXIMUM_CRYSTAL_LANE_SCANS;
        final Optional<Integer> targetIndex =
                !activeDragonThreat
                        && clearCrystal.isPresent()
                        && !useDragonFallback
                        ? clearCrystal
                        : immediateDragon.or(() ->
                                useDragonFallback
                                        ? nearestDragonIndex(frame)
                                        : selectTargetIndex(frame)
                        );
        if (targetIndex.isEmpty()) {
            return scan(
                    context,
                    parameters,
                    frame,
                    fresh
            );
        }
        final int index = targetIndex.orElseThrow();
        final VisibleEntity target =
                frame.visibleEntities().get(index);
        final PerceptionVec3 meleeAim =
                meleeAimPoint(target);
        if (ENDER_DRAGON.equals(target.entityTypeId())
                && meleeAttacks >= MELEE_ATTACKS_BEFORE_RANGED
                && !dragonRangedMode) {
            dragonRangedMode = true;
            dragonRetreatDirection = horizontalAwayDirection(
                    meleeAim,
                    frame.position()
            );
            dragonRetreatTicksRemaining =
                    DRAGON_RANGED_RETREAT_TICKS;
            phase = Phase.RETREATING_DRAGON;
            nextActionTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (ENDER_DRAGON.equals(target.entityTypeId())
                && meleeReachMisses
                    < MELEE_REACH_MISSES_BEFORE_RANGED
                && !dragonRangedMode
                && meleeAttacks < MELEE_ATTACKS_BEFORE_RANGED
                && meleeAim.subtract(frame.eyePosition()).length()
                    <= MELEE_DRAGON_DISTANCE
                && interactionLineClear(target)) {
            final Optional<String> meleeWeapon =
                    preferredMeleeWeapon(frame);
            if (meleeWeapon.isPresent()) {
                return meleeDragon(
                        context,
                        frame,
                        target,
                        meleeWeapon.orElseThrow(),
                        fresh
                );
            }
        }
        if (shotsDispatched >= parameters.maximumShots()) {
            return fail(
                    context,
                    NAME + ".shot_budget_exhausted"
            );
        }
        if (!BOW.equals(frame.mainHand().itemId())) {
            final InventoryOperationResult equipped =
                    inventory.equip(new EquipItemParameters(
                            BOW,
                            EquipmentTarget.MAINHAND
                    ));
            if (!equipped.succeeded()) {
                return fail(
                        context,
                        equipped.failure().orElseThrow()
                );
            }
            return SkillTickResult.running(true, true);
        }
        if (inventoryCount(frame, ARROW) < 1) {
            return fail(context, NAME + ".arrows_exhausted");
        }
        shotParameters = new ShootObservedEntityParameters(
                frame.observationRevision(),
                "visible-" + index,
                ActionHand.MAIN_HAND,
                1
        );
        shotTargetDragon = ENDER_DRAGON.equals(
                target.entityTypeId()
        );
        shot = new ShootObservedEntitySkill(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames,
                RangedCombatSkillPolicy.defaults()
        );
        final Optional<SkillFailure> precondition =
                shot.preconditions(context, shotParameters);
        if (precondition.isPresent()) {
            shot = null;
            shotParameters = null;
            shotTargetDragon = false;
            nextActionTick = context.gameTick()
                    + SCAN_INTERVAL_TICKS;
            return scan(
                    context,
                    parameters,
                    frame,
                    fresh
            );
        }
        shot.start(context, shotParameters);
        phase = Phase.SHOOTING;
        scanTurns = 0;
        return SkillTickResult.running(true, true);
    }

    private static boolean visibleCenterwardEndStoneWall(
            final CoreSkillFrame frame
    ) {
        return visibleCenterwardEndStoneFace(frame).isPresent();
    }

    private static boolean visibleCenterwardObsidianWall(
            final CoreSkillFrame frame
    ) {
        final GridPos feet = frame.feet();
        final double centerwardX = EndArenaTopology.CENTER_X
                - frame.position().x();
        final double centerwardZ = EndArenaTopology.CENTER_Z
                - frame.position().z();
        final boolean alongX = Math.abs(centerwardX)
                >= Math.abs(centerwardZ);
        final int direction = alongX
                ? (centerwardX < 0.0 ? -1 : 1)
                : (centerwardZ < 0.0 ? -1 : 1);
        return frame.visibleBlockFaces().stream()
                .filter(face -> "minecraft:obsidian".equals(
                        face.blockTypeId()))
                .map(face -> new GridPos(
                        face.block().x(),
                        face.block().y(),
                        face.block().z()
                ))
                .anyMatch(block -> {
                    final int dx = block.x() - feet.x();
                    final int dz = block.z() - feet.z();
                    final int distance = Math.abs(dx) + Math.abs(dz);
                    final int forward = alongX ? dx * direction : dz * direction;
                    return distance >= 1
                            && distance <= 2
                            && forward == distance
                            && block.y() >= feet.y()
                            && block.y() <= feet.y() + 3;
                });
    }

    /**
     * Returns one ordinary side face of a centerward End-stone obstruction
     * from the newest first-person frame.  A boolean-only probe is not enough
     * for a legal mining action: the BreakBlock child needs the exact block,
     * face and hit point so its retained/current crosshair guard can verify
     * the action on the server thread.
     */
    private static Optional<VisibleBlockFace>
            visibleCenterwardEndStoneFace(final CoreSkillFrame frame) {
        final GridPos feet = frame.feet();
        final double centerwardX = EndArenaTopology.CENTER_X
                - frame.position().x();
        final double centerwardZ = EndArenaTopology.CENTER_Z
                - frame.position().z();
        final boolean alongX = Math.abs(centerwardX)
                >= Math.abs(centerwardZ);
        final int direction = alongX
                ? (centerwardX < 0.0 ? -1 : 1)
                : (centerwardZ < 0.0 ? -1 : 1);
        return frame.visibleBlockFaces().stream()
                .filter(face ->
                        "minecraft:end_stone".equals(face.blockTypeId()))
                .filter(face -> {
            final GridPos block = new GridPos(
                    face.block().x(),
                    face.block().y(),
                    face.block().z()
            );
            final int dx = block.x() - feet.x();
            final int dz = block.z() - feet.z();
            final int distance = Math.abs(dx) + Math.abs(dz);
            final int forward = alongX ? dx * direction : dz * direction;
            final String faceName = face.face().toLowerCase(
                    java.util.Locale.ROOT
            );
            return distance >= 1
                    && distance <= 2
                    && forward == distance
                    && block.y() >= feet.y()
                    && block.y() <= feet.y() + 3
                    && !faceName.endsWith(":up")
                    && !faceName.equals("up")
                    && !faceName.endsWith(":down")
                    && !faceName.equals("down");
                })
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private static boolean freshHeadClearance(
            final CoreSkillFrame frame
    ) {
        final long revision = frame.observationRevision();
        return frame.navigation()
                .voxelAt(frame.feet().above(2))
                .filter(voxel -> NavigationEvidence
                        .hasFreshTraversalClearance(voxel, revision))
                .isPresent();
    }

    private SkillTickResult handleBlockedCrystal(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final Snapshot snapshot,
            final boolean fresh
    ) {
        final CoreSkillFrame frame = snapshot.core();
        final VisibleEntity crystal =
                closestCrystalWithAlignedCageBar(frame)
                .or(() -> boundBlockedCrystal(frame))
                .or(() -> closestBlockedCrystal(frame))
                .orElseThrow();
        final Optional<VisibleBlockFace> maybeBar =
                alignedCageBar(frame, crystal);
        if (maybeBar.isPresent()) {
            final VisibleBlockFace bar = maybeBar.orElseThrow();
            if (bar.distance() <= CAGE_BREAK_REACH) {
                return startCageBreak(
                        context,
                        parameters,
                        frame,
                        bar,
                        fresh
                );
            }
            sawCageBarBeyondReach = true;
            cageStatus =
                    CageStatus.APPROACH_OR_ELEVATION_REQUIRED;
            if (cageTowered) {
                return startOrPrepareCageDescent(
                        context,
                        parameters,
                        frame,
                        fresh
                );
            }
            final Optional<CagedCrystalTraversalPlanner.Plan>
                    planned =
                    CagedCrystalTraversalPlanner.plan(
                            context,
                            frame,
                            bar
                    );
            if (planned.isPresent()) {
                return startCageTraversal(
                        context,
                        parameters,
                        frame,
                        crystal,
                        planned.orElseThrow(),
                        fresh
                );
            }
            cageStatus =
                    CageStatus.SAFE_TRAVERSAL_UNAVAILABLE;
        } else {
            cageStatus = CageStatus.SEEKING_VISIBLE_BAR;
            if (cageTowered) {
                return startOrPrepareCageDescent(
                        context,
                        parameters,
                        frame,
                        fresh
                );
            }
        }
        return recoverCageView(
                context,
                parameters,
                frame,
                crystal,
                fresh
        );
    }

    private SkillTickResult startCageTraversal(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final CoreSkillFrame frame,
            final VisibleEntity crystal,
            final CagedCrystalTraversalPlanner.Plan plan,
            final boolean fresh
    ) {
        if (!cageTraversalSafety(context, frame)) {
            /* A distant cage is optional while the player is under pressure.
             * Refusing a risky tower/descent must not terminate the entire
             * dragon fight when an ordinary visible dragon target can still
             * be reacquired.  Clear the tentative traversal authority and
             * resume the bounded first-person sweep; a later safe frame may
             * plan the cage again. */
            clearCageTraversalPlan();
            cageStatus = CageStatus.SAFETY_RESERVE_REQUIRED;
            return scan(
                    context,
                    parameters,
                    frame,
                    fresh
            );
        }
        if (inventoryCount(
                frame,
                "minecraft:water_bucket"
        ) < 1) {
            cageStatus = CageStatus.WATER_BUCKET_REQUIRED;
            return fail(
                    context,
                    NAME + ".cage_water_bucket_required"
            );
        }
        if (preferredCageMiningTool(frame).isEmpty()) {
            cageStatus = CageStatus.PICKAXE_REQUIRED;
            return fail(
                    context,
                    NAME + ".cage_pickaxe_required"
            );
        }
        if (cageCrystalId != null
                && !cageCrystalId.equals(crystal.entityId())) {
            clearCageTraversalPlan();
        }
        cageCrystalId = crystal.entityId();
        cageLastSeenPosition = crystal.position();
        cagePlan = plan;
        if (!frame.feet().equals(plan.approach())) {
            if (cageApproachAttempts
                    >= MAXIMUM_CAGE_APPROACH_ATTEMPTS) {
                return fail(
                        context,
                        NAME + ".cage_approach_failed"
                );
            }
            return startCageApproach(
                    context,
                    parameters,
                    plan,
                    fresh
            );
        }
        rememberVisibleLanding(frame, plan.landing());
        if (!recentLandingVerification(frame)) {
            phase = Phase.PREPARING_CAGE_TOWER;
            cageLandingScans = 0;
            return prepareCageTower(
                    context,
                    parameters,
                    frame,
                    fresh
            );
        }
        cageLandingScans = 0;
        final BridgeMaterialResult material =
                bridgeMaterials.ensureEquipped();
        if (!material.ready()) {
            return fail(
                    context,
                    NAME + ".cage_tower_material_unavailable"
            );
        }
        if (material.availableCount() < plan.towerBlocks()) {
            return fail(
                    context,
                    NAME + ".cage_tower_material_insufficient"
            );
        }
        cageTowerParameters = new TowerUpParameters(
                parameters.dimension(),
                plan.targetY(),
                0.2,
                plan.towerBlocks()
        );
        cageTower = new TowerUpSkill(
                expectedPlayerId,
                core,
                coreFrames,
                bridgeMaterials
        );
        final Optional<SkillFailure> precondition =
                cageTower.preconditions(
                        context,
                        cageTowerParameters
                );
        if (precondition.isPresent()) {
            cageTower = null;
            cageTowerParameters = null;
            return fail(
                    context,
                    NAME + ".cage_tower_precondition_failed"
            );
        }
        cageTower.start(context, cageTowerParameters);
        cageStatus = CageStatus.TOWERING;
        phase = Phase.TOWERING_CAGE;
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult startCageApproach(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final CagedCrystalTraversalPlanner.Plan plan,
            final boolean fresh
    ) {
        travelParameters = new TravelToParameters(
                parameters.dimension(),
                plan.approach().x() + 0.5,
                plan.approach().y(),
                plan.approach().z() + 0.5,
                0.50
        );
        travel = new TravelToSkill(
                expectedPlayerId,
                core,
                coreFrames,
                sessionGeneration
        );
        final Optional<SkillFailure> precondition =
                travel.preconditions(context, travelParameters);
        if (precondition.isPresent()) {
            travel = null;
            travelParameters = null;
            cageApproachAttempts++;
            cagePlan = null;
            cageStatus = CageStatus.APPROACH_CELL_UNAVAILABLE;
            nextActionTick = context.gameTick()
                    + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(fresh, true);
        }
        travel.start(context, travelParameters);
        cageApproachAttempts++;
        travelPurpose = TravelPurpose.CAGE_APPROACH;
        cageStatus = CageStatus.APPROACHING;
        phase = Phase.TRAVELLING;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult startCageBreak(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final CoreSkillFrame frame,
            final VisibleBlockFace bar,
            final boolean fresh
    ) {
        if (cageBarsMined >= MAXIMUM_CAGE_BARS_MINED) {
            return fail(
                    context,
                    NAME + ".cage_block_budget_exhausted"
            );
        }
        final Optional<String> tool =
                preferredCageMiningTool(frame);
        if (tool.isEmpty()) {
            cageStatus = CageStatus.PICKAXE_REQUIRED;
            return fail(
                    context,
                    NAME + ".cage_pickaxe_required"
            );
        }
        if (!tool.orElseThrow().equals(
                frame.mainHand().itemId()
        )) {
            final InventoryOperationResult equipped =
                    inventory.equip(new EquipItemParameters(
                            tool.orElseThrow(),
                            EquipmentTarget.MAINHAND
                    ));
            if (!equipped.succeeded()) {
                return fail(
                        context,
                        equipped.failure().orElseThrow()
                );
            }
            cageStatus = CageStatus.EQUIPPING_PICKAXE;
            return SkillTickResult.running(true, true);
        }
        final PerceptionVec3 barDirection =
                bar.hitPosition().subtract(frame.eyePosition());
        if (angularError(
                frame.lookDirection(),
                barDirection
        ) > CAGE_MINING_ALIGNMENT_DEGREES) {
            final ActionOutcome stopped = core.stop();
            final ActionOutcome looking = core.look(lookAt(
                    frame.eyePosition(),
                    bar.hitPosition()
            ));
            if (!stopped.accepted() || !looking.accepted()) {
                return fail(
                        context,
                        NAME + ".cage_mining_alignment_rejected"
                );
            }
            cageStatus = CageStatus.ALIGNING_VISIBLE_BAR;
            nextActionTick = context.gameTick() + 1;
            return SkillTickResult.running(true, true);
        }
        final Optional<BlockFace> face = blockFace(bar.face());
        if (face.isEmpty()) {
            cageStatus = CageStatus.SEEKING_VISIBLE_BAR;
            return recoverCageView(
                    context,
                    parameters,
                    frame,
                    closestBlockedCrystal(frame).orElseThrow(),
                    fresh
            );
        }
        cageBreakParameters = new BreakBlockParameters(
                parameters.dimension(),
                new ObservedBlockTarget(
                        frame.observationRevision(),
                        bar.block().x(),
                        bar.block().y(),
                        bar.block().z(),
                        face.orElseThrow()
                )
        );
        cageBreak = new BreakBlockSkill(
                expectedPlayerId,
                interactions,
                interactionFrames,
                InteractionSkillPolicy.defaults()
        );
        final Optional<SkillFailure> precondition =
                cageBreak.preconditions(
                        context,
                        cageBreakParameters
                );
        if (precondition.isPresent()) {
            final String code =
                    precondition.orElseThrow().code();
            cageBreak = null;
            cageBreakParameters = null;
            if (transientCageBreakFailure(code)) {
                cageStatus =
                        CageStatus.SEEKING_VISIBLE_BAR;
                nextActionTick = context.gameTick()
                        + SCAN_INTERVAL_TICKS;
                return SkillTickResult.running(
                        fresh,
                        true
                );
            }
            return fail(
                    context,
                    NAME + ".cage_mining_unavailable"
            );
        }
        cageBreak.start(context, cageBreakParameters);
        cageStatus = CageStatus.MINING_VISIBLE_BAR;
        phase = Phase.OPENING_CAGE;
        cageScanTurns = 0;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult recoverCageView(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final CoreSkillFrame frame,
            final VisibleEntity crystal,
            final boolean fresh
    ) {
        if (!core.move(MovementIntent.STOPPED).accepted()) {
            return fail(context, NAME + ".stop_rejected");
        }
        if (cageScanTurns >= CAGE_SCANS_BEFORE_RALLY) {
            cageScanTurns = 0;
            if (rallyAttempts >= MAXIMUM_RALLY_ATTEMPTS) {
                return fail(
                        context,
                        cageStatus
                            == CageStatus
                                .SAFE_TRAVERSAL_UNAVAILABLE
                            ? NAME
                                + ".cage_safe_traversal_unavailable"
                            : sawCageBarBeyondReach
                            ? NAME
                                + ".cage_requires_approach_or_tower"
                            : NAME
                                + ".cage_obstruction_unresolved"
                );
            }
            return startRallyTravel(
                    context,
                    parameters,
                    frame,
                    fresh
            );
        }
        final PerceptionVec3 offset = cageLookOffset(
                cageScanTurns
        );
        if (!core.look(lookAt(
                frame.eyePosition(),
                crystal.position().add(offset)
        )).accepted()) {
            return fail(context, NAME + ".look_rejected");
        }
        cageScanTurns++;
        nextActionTick = context.gameTick()
                + SCAN_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickCageBreak(
            final SkillContext context,
            final boolean fresh
    ) {
        final SkillTickResult result = cageBreak.tick(
                context,
                cageBreakParameters
        );
        if (result.status()
                == SkillTickResult.Status.COMPLETED) {
            cageBarsMined++;
            cageBreak = null;
            cageBreakParameters = null;
            cageStatus = CageStatus.VERIFYING_OPENING;
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick()
                    + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        if (result.status()
                == SkillTickResult.Status.FAILED) {
            final String code = result.failure()
                    .orElseThrow()
                    .code();
            cageBreak = null;
            cageBreakParameters = null;
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick()
                    + SCAN_INTERVAL_TICKS;
            if (transientCageBreakFailure(code)) {
                cageStatus =
                        CageStatus.SEEKING_VISIBLE_BAR;
                return SkillTickResult.running(true, true);
            }
            return fail(
                    context,
                    NAME + ".cage_mining_failed"
            );
        }
        return SkillTickResult.running(
                result.madeProgress() || fresh,
                result.safeCheckpoint()
        );
    }

    private SkillTickResult tickCageTower(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final boolean fresh
    ) {
        final SkillTickResult result = cageTower.tick(
                context,
                cageTowerParameters
        );
        if (result.status()
                == SkillTickResult.Status.COMPLETED) {
            cageTower = null;
            cageTowerParameters = null;
            cageTowered = true;
            cageStatus = CageStatus.ELEVATED;
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick()
                    + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, false);
        }
        if (result.status()
                == SkillTickResult.Status.FAILED) {
            cageTower = null;
            cageTowerParameters = null;
            final Optional<CoreSkillFrame> current =
                    coreFrames.current();
            if (current.isPresent()
                    && cageDrop(current.orElseThrow())
                        >= 3.5) {
                cageTowered = true;
                return startOrPrepareCageDescent(
                        context,
                        parameters,
                        current.orElseThrow(),
                        fresh
                );
            }
            return fail(
                    context,
                    NAME + ".cage_tower_failed"
            );
        }
        return SkillTickResult.running(
                result.madeProgress() || fresh,
                result.safeCheckpoint()
        );
    }

    private SkillTickResult prepareCageTower(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (cagePlan == null
                || cageCrystalId == null
                || cageLastSeenPosition == null) {
            return fail(
                    context,
                    NAME + ".cage_tower_plan_unavailable"
            );
        }
        rememberVisibleLanding(
                frame,
                cagePlan.landing()
        );
        final Optional<VisibleEntity> currentCrystal =
                frame.visibleEntities().stream()
                        .filter(entity ->
                                cageCrystalId.equals(
                                        entity.entityId()
                                )
                        )
                        .filter(entity ->
                                END_CRYSTAL.equals(
                                        entity.entityTypeId()
                                )
                        )
                        .findFirst();
        if (currentCrystal.isPresent()) {
            final VisibleEntity crystal =
                    currentCrystal.orElseThrow();
            cageLastSeenPosition = crystal.position();
            if (interactionLineClear(crystal)) {
                cageTowered = false;
                clearCageTraversalPlan();
                phase = Phase.SEARCHING;
                nextActionTick = context.gameTick();
                return SkillTickResult.running(true, true);
            }
            final Optional<VisibleBlockFace> bar =
                    alignedCageBar(frame, crystal);
            if (recentLandingVerification(frame)
                    && bar.isPresent()) {
                if (bar.orElseThrow().distance()
                        <= CAGE_BREAK_REACH) {
                    phase = Phase.SEARCHING;
                    return startCageBreak(
                            context,
                            parameters,
                            frame,
                            bar.orElseThrow(),
                            fresh
                    );
                }
                return startCageTraversal(
                        context,
                        parameters,
                        frame,
                        crystal,
                        cagePlan,
                        fresh
                );
            }
        }
        if (cageLandingScans
                >= CAGE_DESCENT_SCAN_LIMIT) {
            return fail(
                    context,
                    NAME + ".cage_landing_not_visible"
            );
        }
        final PerceptionVec3 target =
                recentLandingVerification(frame)
                    ? cageLastSeenPosition
                    : landingTop(cagePlan.landing());
        if (!core.move(MovementIntent.STOPPED).accepted()
                || !core.look(
                        lookAt(frame.eyePosition(), target)
                ).accepted()) {
            return fail(context, NAME + ".look_rejected");
        }
        if (fresh) {
            cageLandingScans++;
        }
        cageStatus = recentLandingVerification(frame)
                ? CageStatus.REACQUIRING_CAGE
                : CageStatus.VERIFYING_LANDING;
        return SkillTickResult.running(fresh, true);
    }

    private SkillTickResult requestObservedSideDetourObservation(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        final GridPos feet = frame.feet();
        final double centerwardX = EndArenaTopology.CENTER_X
                - frame.position().x();
        final double centerwardZ = EndArenaTopology.CENTER_Z
                - frame.position().z();
        final boolean alongX = Math.abs(centerwardX)
                >= Math.abs(centerwardZ);
        /* One adjacent cell can itself be the pillar's end-stone skirt.  A
         * two-cell lateral look exposes the next legal standing cell without
         * authorizing a two-cell movement; the subsequent selector still
         * accepts only an adjacent cell with observed support and clearance. */
        final int side = (scanTurns & 1) == 0 ? 2 : -2;
        final GridPos target = alongX
                ? new GridPos(feet.x(), feet.y(), feet.z() + side)
                : new GridPos(feet.x() + side, feet.y(), feet.z());
        final PerceptionVec3 targetCenter = new PerceptionVec3(
                target.x() + 0.5,
                target.y() + 0.5,
                target.z() + 0.5
        );
        if (!core.stop().accepted()
                || !core.look(lookAt(
                        frame.eyePosition(),
                        targetCenter
                )).accepted()) {
            return fail(
                    context,
                    NAME + ".side_observation_rejected"
            );
        }
        scanTurns = (scanTurns + 1) & 3;
        lastRallyFailure = "side_observation";
        nextActionTick = context.gameTick() + SCAN_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult startOrPrepareCageDescent(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (cagePlan == null) {
            return fail(
                    context,
                    NAME + ".cage_descent_plan_unavailable"
            );
        }
        final double drop = cageDrop(frame);
        if (drop < 3.5) {
            cageTowered = false;
            clearCageTraversalPlan();
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        final int maximumDrop = Math.max(
                4,
                Math.min(32, (int) Math.ceil(drop) + 1)
        );
        cageDescentParameters =
                new WaterClutchDescendParameters(
                        parameters.dimension(),
                        cagePlan.landing().x() + 0.5,
                        cagePlan.landing().y(),
                        cagePlan.landing().z() + 0.5,
                        0.6,
                        maximumDrop
                );
        cageDescent = new WaterClutchDescendSkill(
                expectedPlayerId,
                core,
                coreFrames
        );
        cageDescentScans = 0;
        cageLandingScans = 0;
        phase = Phase.PREPARING_CAGE_DESCENT;
        return prepareCageDescent(
                context,
                frame,
                fresh
        );
    }

    private SkillTickResult prepareCageDescent(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (cageDescent == null
                || cageDescentParameters == null
                || cagePlan == null) {
            return fail(
                    context,
                    NAME + ".cage_descent_plan_unavailable"
            );
        }
        final Optional<SkillFailure> precondition =
                cageDescent.preconditions(
                        context,
                        cageDescentParameters
                );
        if (precondition.isEmpty()) {
            cageDescent.start(
                    context,
                    cageDescentParameters
            );
            cageStatus = CageStatus.DESCENDING;
            phase = Phase.DESCENDING_CAGE;
            return SkillTickResult.running(true, false);
        }
        final String code =
                precondition.orElseThrow().code();
        if (!code.endsWith(
                ".visible_safe_landing_required"
        )) {
            return fail(
                    context,
                    NAME + ".cage_safe_descent_unavailable"
            );
        }
        if (fresh) {
            cageDescentScans++;
        }
        if (cageDescentScans
                >= CAGE_DESCENT_SCAN_LIMIT) {
            return fail(
                    context,
                    NAME + ".cage_landing_not_visible"
            );
        }
        final PerceptionVec3 landing =
                new PerceptionVec3(
                        cagePlan.landing().x() + 0.5,
                        cagePlan.landing().y(),
                        cagePlan.landing().z() + 0.5
                );
        if (!core.move(MovementIntent.STOPPED).accepted()
                || !core.look(
                        lookAt(frame.eyePosition(), landing)
                ).accepted()) {
            return fail(context, NAME + ".look_rejected");
        }
        cageStatus = CageStatus.SCANNING_LANDING;
        return SkillTickResult.running(fresh, true);
    }

    private SkillTickResult tickCageDescent(
            final SkillContext context,
            final boolean fresh
    ) {
        final SkillTickResult result = cageDescent.tick(
                context,
                cageDescentParameters
        );
        if (result.status()
                == SkillTickResult.Status.COMPLETED) {
            cageDescent = null;
            cageDescentParameters = null;
            cageTowered = false;
            clearCageTraversalPlan();
            cageStatus = CageStatus.DESCENT_COMPLETED;
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick()
                    + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        if (result.status()
                == SkillTickResult.Status.FAILED) {
            cageDescent = null;
            cageDescentParameters = null;
            return fail(
                    context,
                    NAME + ".cage_descent_failed"
            );
        }
        return SkillTickResult.running(
                result.madeProgress() || fresh,
                result.safeCheckpoint()
        );
    }

    private SkillTickResult meleeDragon(
            final SkillContext context,
            final CoreSkillFrame frame,
            final VisibleEntity target,
            final String meleeWeapon,
            final boolean fresh
    ) {
        if (!meleeWeapon.equals(frame.mainHand().itemId())) {
            final InventoryOperationResult equipped =
                    inventory.equip(new EquipItemParameters(
                            meleeWeapon,
                            EquipmentTarget.MAINHAND
                    ));
            if (!equipped.succeeded()) {
                return fail(
                        context,
                        equipped.failure().orElseThrow()
                );
            }
            return SkillTickResult.running(true, true);
        }
        final PerceptionVec3 interactionAim =
                meleeAimPoint(target);
        if (!core.move(MovementIntent.STOPPED).accepted()
                || !core.look(
                        lookAt(frame.eyePosition(), interactionAim)
                ).accepted()) {
            return fail(context, NAME + ".melee_aim_rejected");
        }
        if (angularError(
                frame.lookDirection(),
                interactionAim.subtract(frame.eyePosition())
        ) > MELEE_ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, false);
        }
        final OptionalDouble strength =
                interactions.attackStrengthScale();
        if (strength.isEmpty()) {
            return fail(
                    context,
                    NAME + ".attack_strength_unavailable"
            );
        }
        if (strength.orElseThrow() < MELEE_ATTACK_STRENGTH) {
            return SkillTickResult.running(fresh, false);
        }
        final ActionOutcome attacked =
                interactions.attack(target.entityId());
        if (!attacked.accepted()) {
            if (transientMeleeTargetFailure(attacked)) {
                if (attacked
                        == ActionOutcome.TARGET_OUT_OF_REACH) {
                    meleeReachMisses++;
                    if (frame.onGround()
                            && interactionAim.y()
                                - frame.eyePosition().y()
                                >= 0.75
                            && !core.jump().accepted()) {
                        return fail(
                                context,
                                NAME + ".melee_jump_rejected"
                        );
                    }
                }
                nextActionTick = context.gameTick() + 1;
                scanTurns = 0;
                return SkillTickResult.running(true, true);
            }
            return fail(context, NAME + ".melee_attack_rejected");
        }
        meleeReachMisses = 0;
        rangedDragonShotsSinceMeleeRetry = 0;
        meleeAttacks++;
        nextActionTick = context.gameTick() + 2;
        scanTurns = 0;
        return SkillTickResult.running(true, false);
    }

    private static PerceptionVec3 meleeAimPoint(
            final VisibleEntity target
    ) {
        final Map<String, String> properties =
                target.visibleProperties();
        if ("true".equals(properties.get("multipartParent"))) {
            try {
                final double x = Double.parseDouble(
                        properties.get("interactionAimX")
                );
                final double y = Double.parseDouble(
                        properties.get("interactionAimY")
                );
                final double z = Double.parseDouble(
                        properties.get("interactionAimZ")
                );
                if (Double.isFinite(x)
                        && Double.isFinite(y)
                        && Double.isFinite(z)) {
                    return new PerceptionVec3(x, y, z);
                }
            } catch (NullPointerException
                    | NumberFormatException ignored) {
                // Fail closed to the ordinary semantic entity position.
            }
        }
        return target.position().add(
                new PerceptionVec3(0.0, 2.0, 0.0)
        );
    }

    private static boolean transientMeleeTargetFailure(
            final ActionOutcome outcome
    ) {
        return outcome == ActionOutcome.TARGET_NOT_FOUND
                || outcome == ActionOutcome.TARGET_UNLOADED
                || outcome == ActionOutcome.TARGET_OUT_OF_REACH
                || outcome == ActionOutcome.TARGET_OCCLUDED
                || outcome == ActionOutcome.TARGET_CHANGED;
    }

    private SkillTickResult scan(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        /*
         * A real first-person player does not plant their feet while
         * searching the sky.  The old STOPPED input left the body exposed to
         * dragon breath during every 48-view sweep, and also produced the
         * exact "looks around but does nothing" failure seen in live runs.
         * Use a small alternating strafe only while no legal target is
         * visible.  It remains a normal player-relative input; collision,
         * fall, and emergency-survival checks still own the final movement.
         */
        final double searchStrafe =
                ((scanTurns / SCAN_PITCHES.length) & 1) == 0
                        ? 0.35
                        : -0.35;
        if (!core.move(new MovementIntent(
                0.0,
                searchStrafe,
                false,
                false
        )).accepted()) {
            return fail(context, NAME + ".search_move_rejected");
        }
        if (scanTurns >= SCANS_BEFORE_RALLY) {
            if (rallyAttempts >= MAXIMUM_RALLY_ATTEMPTS) {
                return fail(
                        context,
                        NAME + ".no_visible_combat_target"
                );
            }
            return startRallyTravel(
                    context,
                    parameters,
                    frame,
                    fresh
            );
        }
        final int pitchIndex =
                scanTurns % SCAN_PITCHES.length;
        final int yawStep =
                scanTurns / SCAN_PITCHES.length;
        final LookIntent look = new LookIntent(
                ActionMath.wrapDegrees(
                        scanBaseYaw + yawStep * 30.0F
                ),
                SCAN_PITCHES[pitchIndex]
        );
        if (!core.look(look).accepted()) {
            return fail(context, NAME + ".look_rejected");
        }
        scanTurns++;
        nextActionTick = context.gameTick()
                + SCAN_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    /**
     * Move out of a close multipart hitbox before switching to the bow.  A
     * normal player backs away from a dragon tail instead of continuing to
     * swing at the same small part from inside its collider.  The destination
     * is deliberately not teleported or path-injected: this is only a
     * bounded forward input after a first-person look, so vanilla collision,
     * void and emergency-survival rules still decide the result.
     */
    private SkillTickResult tickDragonRangedRetreat(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (dragonRetreatTicksRemaining <= 0
                || dragonRetreatDirection == null) {
            dragonRetreatDirection = null;
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        final PerceptionVec3 away = dragonRetreatDirection.lengthSquared()
                > 1.0E-12
                ? dragonRetreatDirection.normalized()
                : new PerceptionVec3(
                        ((context.gameTick() / 6L) & 1L) == 0L
                                ? 1.0
                                : -1.0,
                        0.0,
                        0.0
                );
        final PerceptionVec3 horizontalForward = new PerceptionVec3(
                frame.lookDirection().x(),
                0.0,
                frame.lookDirection().z()
        );
        final PerceptionVec3 forward = horizontalForward.lengthSquared()
                > 1.0E-12
                ? horizontalForward.normalized()
                : new PerceptionVec3(0.0, 0.0, 1.0);
        final PerceptionVec3 left = new PerceptionVec3(
                forward.z(),
                0.0,
                -forward.x()
        );
        if (!core.look(lookAt(
                frame.eyePosition(),
                frame.eyePosition().add(away)
        )).accepted()) {
            return fail(context, NAME + ".ranged_retreat_look_rejected");
        }
        if (!core.move(new MovementIntent(
                away.dot(forward),
                away.dot(left),
                true,
                false
        )).accepted()) {
            return fail(context, NAME + ".ranged_retreat_move_rejected");
        }
        if (fresh) {
            dragonRetreatTicksRemaining--;
        }
        nextActionTick = context.gameTick() + 1;
        return SkillTickResult.running(true, true);
    }

    private void rememberUnsafeClearCrystal(
            final CoreSkillFrame frame
    ) {
        closestClearCrystal(frame)
                .filter(crystal ->
                        EndCrystalStandOffPlanner
                            .horizontalDistance(
                                frame.position(),
                                crystal.position()
                            )
                            < EndCrystalStandOffPlanner
                                .MINIMUM_FIRE_DISTANCE
                )
                .ifPresent(crystal -> {
                    crystalStandOffPosition =
                            crystal.position();
                });
    }

    private SkillTickResult prepareCrystalStandOff(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        final PerceptionVec3 crystal =
                Objects.requireNonNull(
                        crystalStandOffPosition
                );
        if (EndCrystalStandOffPlanner.horizontalDistance(
                frame.position(),
                crystal
        ) >= EndCrystalStandOffPlanner.MINIMUM_FIRE_DISTANCE) {
            clearCrystalStandOffMemory();
            scanTurns = 0;
            scanBaseYaw = lookYaw(frame);
            nextActionTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (crystalCageInspectionTurns
                < CRYSTAL_CAGE_INSPECTION_SCANS) {
            if (!core.move(MovementIntent.STOPPED).accepted()) {
                return fail(
                        context,
                        NAME + ".crystal_cage_inspection_stop_rejected"
                );
            }
            final PerceptionVec3 inspectionTarget =
                    crystal.add(
                            cageLookOffset(
                                    crystalCageInspectionTurns
                            )
                    );
            if (!core.look(
                    lookAt(
                            frame.eyePosition(),
                            inspectionTarget
                    )
            ).accepted()) {
                return fail(
                        context,
                        NAME + ".crystal_cage_inspection_look_rejected"
                );
            }
            if (fresh) {
                crystalCageInspectionTurns++;
            }
            nextActionTick = context.gameTick()
                    + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        final Optional<GridPos> retreat =
                EndCrystalStandOffPlanner.select(
                        frame,
                        crystal,
                        context.hardcore()
                );
        if (retreat.isEmpty()) {
            if (crystalStandOffScans
                    >= MAXIMUM_CRYSTAL_STANDOFF_SCANS) {
                return fail(
                        context,
                        NAME + ".crystal_standoff_unobserved"
                );
            }
            if (!core.stop().accepted()) {
                return fail(
                        context,
                        NAME + ".crystal_standoff_stop_rejected"
                );
            }
            final PerceptionVec3 away =
                    horizontalAwayDirection(
                            crystal,
                            frame.position()
                    );
            final PerceptionVec3 inspectionTarget =
                    frame.eyePosition().add(
                            away.lengthSquared() <= 1.0E-12
                                ? new PerceptionVec3(
                                        0.0,
                                        -2.0,
                                        -8.0
                                )
                                : new PerceptionVec3(
                                        away.x() * 8.0,
                                        -2.0,
                                        away.z() * 8.0
                                )
                    );
            if (!core.look(
                    lookAt(
                            frame.eyePosition(),
                            inspectionTarget
                    )
            ).accepted()) {
                return fail(
                        context,
                        NAME + ".crystal_standoff_look_rejected"
                );
            }
            crystalStandOffScans++;
            nextActionTick = context.gameTick()
                    + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        final GridPos destination = retreat.orElseThrow();
        crystalStandOffParameters = new MoveToParameters(
                parameters.dimension(),
                destination.x() + 0.5,
                destination.y(),
                destination.z() + 0.5,
                0.30
        );
        crystalStandOff = new MoveToSkill(
                expectedPlayerId,
                core,
                coreFrames,
                (authorizedContext, authorizedFrame, target) ->
                    EndCrystalStandOffPlanner
                        .authorizesAggregateRisk(
                            authorizedFrame,
                            target,
                            crystal
                        )
        );
        final Optional<SkillFailure> precondition =
                crystalStandOff.preconditions(
                        context,
                        crystalStandOffParameters
                );
        if (precondition.isPresent()) {
            crystalStandOff = null;
            crystalStandOffParameters = null;
            return fail(
                    context,
                    NAME + ".crystal_standoff_precondition"
            );
        }
        crystalStandOff.start(
                context,
                crystalStandOffParameters
        );
        crystalStandOffAttempts++;
        phase = Phase.REPOSITIONING_CRYSTAL;
        return SkillTickResult.running(fresh, true);
    }

    private SkillTickResult tickCrystalStandOff(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final boolean fresh
    ) {
        final SkillTickResult result =
                crystalStandOff.tick(
                        context,
                        crystalStandOffParameters
                );
        if (result.status()
                == SkillTickResult.Status.COMPLETED) {
            clearCrystalStandOffChild();
            phase = Phase.SEARCHING;
            scanTurns = 0;
            nextActionTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (result.status()
                == SkillTickResult.Status.FAILED) {
            clearCrystalStandOffChild();
            if (crystalStandOffAttempts
                    >= MAXIMUM_CRYSTAL_STANDOFF_ATTEMPTS) {
                return fail(
                        context,
                        NAME + ".crystal_standoff_failed"
                );
            }
            phase = Phase.SEARCHING;
            crystalStandOffScans = 0;
            nextActionTick = context.gameTick()
                    + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                result.madeProgress() || fresh,
                result.safeCheckpoint()
        );
    }

    private SkillTickResult startCrystalLane(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final GridPos destination,
            final boolean fresh
    ) {
        crystalLaneParameters = new MoveToParameters(
                parameters.dimension(),
                destination.x() + 0.5,
                destination.y(),
                destination.z() + 0.5,
                0.35
        );
        crystalLane = new MoveToSkill(
                expectedPlayerId,
                core,
                coreFrames
        );
        final Optional<SkillFailure> precondition =
                crystalLane.preconditions(
                        context,
                        crystalLaneParameters
                );
        if (precondition.isPresent()) {
            crystalLane = null;
            crystalLaneParameters = null;
            crystalLaneAttempts++;
            return SkillTickResult.running(fresh, true);
        }
        crystalLane.start(context, crystalLaneParameters);
        crystalLaneAttempts++;
        crystalLaneScanTurns = 0;
        phase = Phase.REPOSITIONING_CRYSTAL_LANE;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickCrystalLane(
            final SkillContext context,
            final boolean fresh
    ) {
        if (crystalLane == null || crystalLaneParameters == null) {
            phase = Phase.SEARCHING;
            return SkillTickResult.running(true, true);
        }
        final SkillTickResult result = crystalLane.tick(
                context,
                crystalLaneParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            crystalLane = null;
            crystalLaneParameters = null;
            phase = Phase.SEARCHING;
            scanTurns = 0;
            crystalLaneScanTurns = 0;
            nextActionTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            crystalLane = null;
            crystalLaneParameters = null;
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick()
                    + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                result.madeProgress() || fresh,
                result.safeCheckpoint()
        );
    }

    private static Optional<VisibleEntity> closestClearCrystal(
            final CoreSkillFrame frame
    ) {
        return frame.visibleEntities()
                .stream()
                .filter(entity ->
                        END_CRYSTAL.equals(
                                entity.entityTypeId()
                        )
                )
                .filter(
                        FightEnderDragonSkill
                            ::interactionLineClear
                )
                .min(Comparator.comparingDouble(
                        VisibleEntity::distance
                ));
    }

    private static PerceptionVec3 horizontalAwayDirection(
            final PerceptionVec3 crystal,
            final PerceptionVec3 body
    ) {
        final PerceptionVec3 away = new PerceptionVec3(
                body.x() - crystal.x(),
                0.0,
                body.z() - crystal.z()
        );
        return away.lengthSquared() <= 1.0E-12
                ? away
                : away.normalized();
    }

    private void clearCrystalStandOffChild() {
        crystalStandOff = null;
        crystalStandOffParameters = null;
    }

    private void clearCrystalStandOffMemory() {
        clearCrystalStandOffChild();
        crystalStandOffPosition = null;
        crystalStandOffScans = 0;
        crystalStandOffAttempts = 0;
        crystalCageInspectionTurns = 0;
    }

    private SkillTickResult tickShot(
            final SkillContext context,
            final boolean fresh
    ) {
        final SkillTickResult result =
                shot.tick(context, shotParameters);
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            shotsDispatched++;
            if (shotTargetDragon) {
                recordCompletedDragonShot();
            }
            shot = null;
            shotParameters = null;
            shotTargetDragon = false;
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick()
                    + PROJECTILE_SETTLE_TICKS;
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            final String code = result.failure()
                    .orElseThrow()
                    .code();
            shot = null;
            shotParameters = null;
            shotTargetDragon = false;
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick()
                    + SCAN_INTERVAL_TICKS;
            if (transientShotFailure(code)) {
                return SkillTickResult.running(true, true);
            }
            return fail(context, NAME + ".shot_failed");
        }
        return SkillTickResult.running(
                result.madeProgress() || fresh,
                result.safeCheckpoint()
        );
    }

    private void recordCompletedDragonShot() {
        rangedDragonShotsSinceMeleeRetry++;
        if (rangedDragonShotsSinceMeleeRetry
                < RANGED_SHOTS_BEFORE_MELEE_RETRY) {
            return;
        }
        /*
         * A flying dragon may become safely reachable after it perches. The
         * old accounting cleared only reach misses, leaving meleeAttacks at
         * its burst limit and dragonRangedMode permanently true. Every later
         * close observation therefore skipped the sword forever. Reopen one
         * bounded melee burst after four ordinary arrows; distance, line of
         * sight, aim and attack cooldown still gate every swing.
         */
        meleeReachMisses = 0;
        meleeAttacks = 0;
        rangedDragonShotsSinceMeleeRetry = 0;
        dragonRangedMode = false;
        dragonRetreatDirection = null;
        dragonRetreatTicksRemaining = 0;
    }

    private SkillTickResult startRallyTravel(
            final SkillContext context,
            final FightEnderDragonParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        /*
         * A body can enter the central island on a legal cell whose current
         * frame contains no other complete standing cell (for example while
         * under a low End-stone lip). Returning to the same rally point in
         * that case is a liveness trap: four successful zero-distance travel
         * attempts only repeat the same blocked camera sweep. Use a bounded
         * centerward waypoint derived from the authoritative pose instead.
         * TravelTo still has to prove every intermediate voxel from fresh
         * semantic navigation evidence, so this is not a teleport or hidden
         * terrain lookup.
         */
        /* A point can be perfectly observable and still be outside the
         * combat entity-perception radius.  In that annulus, ordinary
         * TravelTo merely revisits another blind rally point and burns the
         * bounded scan budget.  Re-enter the observed island route first;
         * the child still requires fresh support/clearance and owns every
         * movement, mining, and placement action. */
        final Optional<GridPos> observedStep =
                selectObservedCenterwardStep(frame);
        /* The dragon can be outside the entity perception radius even after
         * the body has reached the verified island envelope.  Previously the
         * centerward observed step was gated on the outer re-entry radius,
         * so an otherwise legal standing cell inside that envelope could
         * only repeat blind camera scans until no_visible_combat_target.
         * Continue through one freshly observed centerward cell whenever one
         * exists.  The selector still requires support, two-block clearance,
         * arena bounds and a strictly smaller radius; movement remains a
         * normal vanilla input rather than a waypoint or teleport. */
        if (observedStep.isPresent()) {
            lastRallyFailure = "observed_step_selected:"
                    + observedStep.orElseThrow();
            return startObservedRallyStep(
                    context,
                    frame,
                    observedStep.orElseThrow(),
                    fresh
            );
        }
        if (ingressAttempts < MAXIMUM_REENTRY_ATTEMPTS
                && EndArenaTopology.horizontalRadius(frame.position())
                    > FIGHT_REENTRY_RADIUS) {
            lastRallyFailure = "observed_step_unavailable_reentry";
            return startIslandReentry(context, fresh);
        }
        lastRallyFailure = "observed_step_unavailable_travel";
        final Optional<PerceptionVec3> observedRallyPoint =
                selectObservedRallyPoint(frame);
        if (observedRallyPoint.isEmpty()) {
            /* Do not hand an inferred one-cell fallback to TravelTo.  Its
             * rolling planner is deliberately observation-bound, and the
             * old three-block arrival radius reported that fallback as
             * complete without moving.  First turn toward the centerward
             * frontier and wait for a fresh semantic frame; that frame can
             * either expose a legal cell for TravelTo or expose the wall/gap
             * for the normal ingress controller. */
            final PerceptionVec3 baseCenterward = centerwardSearchPoint(frame);
            /* Alternate the normal floor ray with a slightly elevated
             * centerward ray.  At the natural island lip the floor ray can
             * terminate on the near wall while the candidate's two-block
             * clearance remains unknown; a player naturally raises their
             * view before deciding whether to step around that wall.  Both
             * rays remain first-person and bounded, and no destination is
             * admitted until a newer navigation frame proves it. */
            final PerceptionVec3 centerward = (rallyAttempts & 1) == 0
                    ? baseCenterward
                    : new PerceptionVec3(
                            baseCenterward.x(),
                            frame.position().y() + 1.0,
                            baseCenterward.z()
                    );
            final LookIntent look = lookAt(
                    frame.eyePosition(),
                    centerward
            );
            if (!core.stop().accepted() || !core.look(look).accepted()) {
                return fail(context, NAME + ".rally_observation_rejected");
            }
            scanBaseYaw = look.yawDegrees();
            scanTurns = 0;
            rallyAttempts++;
            lastRallyFailure = "centerward_observation";
            nextActionTick = context.gameTick() + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        final PerceptionVec3 rallyPoint = observedRallyPoint.orElseThrow();
        travelParameters = new TravelToParameters(
                parameters.dimension(),
                rallyPoint.x(),
                rallyPoint.y(),
                rallyPoint.z(),
                /* The centerward fallback is one observed-cell scale away.
                 * A three-block arrival radius made TravelTo report
                 * COMPLETED while the body had not moved at all, so every
                 * scan revisited the same blind camera pose.  Keep arrival
                 * precise; rolling travel must now cross the observed cell
                 * or fail with an honest route result. */
                0.50
        );
        travel = new TravelToSkill(
                expectedPlayerId,
                core,
                coreFrames,
                sessionGeneration
        );
        final Optional<SkillFailure> precondition =
                travel.preconditions(context, travelParameters);
        if (precondition.isPresent()) {
            travel = null;
            travelParameters = null;
            rallyAttempts++;
            lastRallyFailure = "travel_precondition:" + precondition.orElseThrow().code();
            scanTurns = 0;
            scanBaseYaw = ActionMath.wrapDegrees(
                    scanBaseYaw + 15.0F
            );
            return SkillTickResult.running(true, true);
        }
        travel.start(context, travelParameters);
        lastRallyFailure = "travel_started";
        rallyAttempts++;
        travelPurpose = TravelPurpose.RALLY;
        phase = Phase.TRAVELLING;
        return SkillTickResult.running(fresh, true);
    }

    /**
     * Re-enters the bounded, observation-only End island ingress controller
     * when a fight scan reaches an observed void frontier.  The fight skill
     * does not synthesize a bridge target or teleport; the child owns the
     * normal crouch/place/break/landfall transaction and returns only after a
     * fresh natural support proof.
     */
    private SkillTickResult startIslandReentry(
            final SkillContext context,
            final boolean fresh
    ) {
        return startIslandReentry(
                context,
                fresh,
                FIGHT_REENTRY_TARGET_RADIUS
        );
    }

    private SkillTickResult startIslandReentry(
            final SkillContext context,
            final boolean fresh,
            final double completionRadius
    ) {
        if (ingressAttempts >= MAXIMUM_REENTRY_ATTEMPTS) {
            lastIngressResult = "rejected:reentry_attempt_budget_exhausted";
            nextActionTick = context.gameTick() + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(fresh, true);
        }
        islandIngressParameters = new EndIslandIngressParameters(
                128.0,
                EndArenaTopology.ARENA_READY_RADIUS,
                56,
                8,
                /* Natural pillar skirts can expose several vertical layers
                 * before the first-person frame reveals a walkable island
                 * cell.  The previous 32-block cap stopped exactly while
                 * making fair progress around the first pillar.  Keep the
                 * action bounded, but use the ingress policy's normal 96
         * observed-break reserve rather than terminating at an
         * arbitrary halfway point. Re-entry also has the full local bridge
         * and scan budgets because a knockback can land on the far edge of a
         * pillar skirt rather than the original rally cell. */
                96,
                10.0,
                128,
                6,
                6_000
        );
        islandIngress = new EndIslandIngressSkill(
                expectedPlayerId,
                core,
                coreFrames,
                bridgeMaterials,
                sessionGeneration,
                interactions,
                interactionFrames,
                ignored -> {
                },
                completionRadius
        );
        final Optional<SkillFailure> rejected = islandIngress.preconditions(
                new SkillContext(
                        context.goalRevision(),
                        context.worldRevision(),
                        context.gameTick(),
                        context.hardcore(),
                        true,
                        context.riskScore()
                ),
                islandIngressParameters
        );
        if (rejected.isPresent()) {
            lastSkyFailure = rejected.orElseThrow().code();
            lastIngressResult = "rejected:" + rejected.orElseThrow().code();
            islandIngress = null;
            islandIngressParameters = null;
            ingressAttempts++;
            rallyAttempts++;
            return SkillTickResult.running(fresh, true);
        }
        islandIngress.start(context, islandIngressParameters);
        ingressAttempts++;
        phase = Phase.REENTERING_ISLAND;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickIslandReentry(
            final SkillContext context,
            final boolean fresh
    ) {
        if (islandIngress == null || islandIngressParameters == null) {
            return fail(context, NAME + ".island_reentry_state_missing");
        }
        final SkillTickResult result = islandIngress.tick(
                context,
                islandIngressParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            lastIngressResult = "completed";
            lastIslandIngressCheckpoint = islandIngress.checkpoint(
                    context,
                    islandIngressParameters
            ).payload().replace("\"", "'")
                    .replace("\\", "/");
            islandIngress = null;
            islandIngressParameters = null;
            phase = Phase.SEARCHING;
            rallyAttempts = 0;
            scanTurns = 0;
            skyBlocksSinceRally = 0;
            nextActionTick = context.gameTick() + 1;
            coreFrames.current().ifPresent(frame ->
                    localRallyPoint = frame.position()
            );
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            final String code = result.failure()
                    .map(SkillFailure::code)
                    .orElse(NAME + ".island_reentry_failed");
            final String diagnostic = islandIngress.diagnosticFailureCode()
                    .orElse(code);
            lastIslandIngressCheckpoint = islandIngress.checkpoint(
                    context,
                    islandIngressParameters
            ).payload().replace("\"", "'")
                    .replace("\\", "/");
            lastIngressResult = "failed:" + diagnostic;
            islandIngress = null;
            islandIngressParameters = null;
            lastSkyFailure = code;
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick() + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                result.madeProgress() || fresh,
                result.safeCheckpoint()
        );
    }

    /**
     * Executes one ordinary player movement input into a cell whose two
     * clearance voxels and support were all observed in the same semantic
     * revision.  TravelTo is intentionally retained for longer routes; this
     * one-cell fallback handles an entry wall where its rolling planner has no
     * complete corridor yet.  It never invents terrain or changes position
     * directly.
     */
    private SkillTickResult startObservedRallyStep(
            final SkillContext context,
            final CoreSkillFrame frame,
            final GridPos target,
            final boolean fresh
    ) {
        if (!frame.onGround() || frame.inWater()) {
            rallyAttempts++;
            lastRallyFailure = "start_not_grounded";
            return SkillTickResult.running(fresh, true);
        }
        final PerceptionVec3 targetCenter = new PerceptionVec3(
                target.x() + 0.5,
                target.y(),
                target.z() + 0.5
        );
        if (!core.look(lookAt(frame.eyePosition(), targetCenter)).accepted()
                || !core.stop().accepted()) {
            rallyAttempts++;
            lastRallyFailure = "start_actuator_rejected";
            return SkillTickResult.running(fresh, true);
        }
        rallyStepTarget = target;
        rallyStepTicks = 0;
        rallyStepStarts++;
        rallyStepAwaitingFreshObservation = true;
        rallyStepObservationRevision = frame.observationRevision();
        phase = Phase.RALLY_STEPPING;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickRallyStep(
            final SkillContext context,
            final boolean fresh
    ) {
        final CoreSkillFrame frame = coreFrames.current().orElse(null);
        if (frame == null || rallyStepTarget == null) {
            lastRallyFailure = "step_state_missing";
            rallyStepTarget = null;
            rallyStepTicks = 0;
            rallyStepAwaitingFreshObservation = false;
            rallyStepObservationRevision = -1L;
            previousRallyStepTarget = null;
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick() + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(fresh, true);
        }
        if (!frame.onGround() || frame.inWater()) {
            core.stop();
            rallyAttempts++;
            lastRallyFailure = "step_not_grounded";
            rallyStepTarget = null;
            rallyStepTicks = 0;
            rallyStepAwaitingFreshObservation = false;
            rallyStepObservationRevision = -1L;
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick() + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        final PerceptionVec3 targetCenter = new PerceptionVec3(
                rallyStepTarget.x() + 0.5,
                rallyStepTarget.y(),
                rallyStepTarget.z() + 0.5
        );
        if (rallyStepAwaitingFreshObservation) {
            /* A queued look can fail to reveal the requested cell when the
             * body is under a natural lip or the target becomes occluded by
             * a moving multipart entity.  The old path kept waiting here
             * forever because the normal step timeout lived below this
             * branch.  Bound the observation wait just like the movement
             * phase, then return to the ordinary scan/mining recovery path. */
            if (rallyStepTicks++ >= OBSERVED_RALLY_STEP_TICKS) {
                core.stop();
                previousRallyStepTarget = rallyStepTarget;
                rallyAttempts++;
                rallyStepTimeouts++;
                lastRallyFailure = "step_fresh_observation_timeout";
                rallyStepTarget = null;
                rallyStepTicks = 0;
                rallyStepAwaitingFreshObservation = false;
                rallyStepObservationRevision = -1L;
                phase = Phase.SEARCHING;
                nextActionTick = context.gameTick()
                        + SCAN_INTERVAL_TICKS;
                return SkillTickResult.running(true, true);
            }
            final PerceptionVec3 clearanceCenter = new PerceptionVec3(
                    rallyStepTarget.x() + 0.5,
                    rallyStepTarget.y() + 1.5,
                    rallyStepTarget.z() + 0.5
            );
            final boolean freshTarget = rallyStepTicks > 0
                    && frame.observationRevision()
                    >= rallyStepObservationRevision
                    && frame.navigation().voxelAt(rallyStepTarget)
                        .filter(voxel -> hasObservedRallyPlanningClearance(
                                voxel,
                                frame.observationRevision(),
                                true
                        ))
                        .isPresent()
                    && frame.navigation().voxelAt(rallyStepTarget.above())
                        .filter(voxel -> NavigationEvidence
                                .hasFreshTraversalClearance(
                                        voxel,
                                        frame.observationRevision()
                                ))
                        .isPresent()
                    && frame.navigation().voxelAt(rallyStepTarget.below())
                        .filter(voxel -> hasObservedRallySupport(
                                voxel,
                                frame.observationRevision(),
                                true
                        ))
                        .isPresent();
            if (!freshTarget) {
                if (!core.look(lookAt(
                        frame.eyePosition(),
                        clearanceCenter
                )).accepted()) {
                    lastRallyFailure = "step_fresh_observation_look_rejected";
                    return fail(
                            context,
                            NAME + ".rally_step_alignment_rejected"
                    );
                }
                return SkillTickResult.running(true, true);
            }
            rallyStepAwaitingFreshObservation = false;
            rallyStepObservationRevision = frame.observationRevision();
            rallyStepTicks = 0;
        }
        /* A one-cell target can be only half a block from the current body
         * centre while the player's feet are still in the old cell.  Using a
         * radius here falsely completed the step before any vanilla travel
         * occurred.  Require the authoritative feet grid to cross into the
         * observed destination. */
        if (frame.feet().equals(rallyStepTarget)) {
            previousRallyStepTarget = rallyStepTarget;
            rallyStepTarget = null;
            rallyStepTicks = 0;
            rallyStepAwaitingFreshObservation = false;
            rallyStepObservationRevision = -1L;
            lastRallyFailure = "";
            rallyAttempts = 0;
            skyBlocksSinceRally = 0;
            localRallyPoint = frame.position();
            phase = Phase.SEARCHING;
            scanTurns = 0;
            nextActionTick = context.gameTick() + 1;
            return SkillTickResult.running(true, true);
        }
        if (rallyStepTicks++ >= OBSERVED_RALLY_STEP_TICKS) {
            core.stop();
            rallyAttempts++;
            rallyStepTimeouts++;
            lastRallyFailure = "step_timeout";
            rallyStepTarget = null;
            rallyStepTicks = 0;
            rallyStepAwaitingFreshObservation = false;
            rallyStepObservationRevision = -1L;
            phase = Phase.SEARCHING;
            nextActionTick = context.gameTick() + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        final PerceptionVec3 targetDirection = targetCenter.subtract(
                frame.eyePosition()
        );
        if (angularError(
                    frame.lookDirection(),
                    targetDirection
                ) > OBSERVED_RALLY_ALIGNMENT_DEGREES) {
            if (!core.stop().accepted()
                    || !core.look(lookAt(
                            frame.eyePosition(),
                            targetCenter
                    )).accepted()) {
                lastRallyFailure = "step_alignment_rejected";
                return fail(
                        context,
                        NAME + ".rally_step_alignment_rejected"
                );
            }
            return SkillTickResult.running(true, true);
        }
        if (!core.move(new MovementIntent(0.78, 0.0, false, false))
                .accepted()) {
            lastRallyFailure = "step_move_rejected";
            return fail(context, NAME + ".rally_step_actuator_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private Optional<GridPos> selectObservedCenterwardStep(
            final CoreSkillFrame frame
    ) {
        final long revision = frame.observationRevision();
        final GridPos current = frame.feet();
        final boolean allowHistorical = EndArenaTopology.horizontalRadius(
                frame.position()
        ) > FIGHT_REENTRY_RADIUS;
        final double currentRadius = EndArenaTopology.horizontalRadius(
                frame.position()
        );
        final double towardX = EndArenaTopology.CENTER_X - frame.position().x();
        final double towardZ = EndArenaTopology.CENTER_Z - frame.position().z();
        return List.of(
                new GridPos(current.x() - 1, current.y(), current.z()),
                new GridPos(current.x() + 1, current.y(), current.z()),
                new GridPos(current.x(), current.y(), current.z() - 1),
                new GridPos(current.x(), current.y(), current.z() + 1)
        ).stream()
                .filter(candidate -> EndArenaTopology.insideArenaReadyRadius(
                        new PerceptionVec3(
                                candidate.x() + 0.5,
                                candidate.y(),
                                candidate.z() + 0.5
                        )
                ))
                .filter(candidate -> EndArenaTopology.horizontalRadius(
                        new PerceptionVec3(
                                candidate.x() + 0.5,
                                candidate.y(),
                                candidate.z() + 0.5
                        )
                ) <= currentRadius - 0.05)
                .filter(candidate -> !candidate.equals(
                        previousRallyStepTarget
                ))
                .filter(candidate -> frame.navigation().voxelAt(candidate)
                        .filter(voxel -> hasObservedRallyPlanningClearance(
                                voxel,
                                revision,
                                allowHistorical
                        ))
                        .isPresent())
                .filter(candidate -> frame.navigation().voxelAt(candidate.above())
                        .filter(voxel -> hasObservedRallyPlanningClearance(
                                voxel,
                                revision,
                                allowHistorical
                        ))
                        .isPresent())
                .filter(candidate -> frame.navigation().voxelAt(candidate.below())
                        .filter(voxel -> hasObservedRallySupport(
                                voxel,
                                revision,
                                allowHistorical
                        ))
                        .isPresent())
                .max(Comparator.comparingDouble(candidate -> {
                    final double dx = candidate.x() + 0.5 - frame.position().x();
                    final double dz = candidate.z() + 0.5 - frame.position().z();
                    return dx * towardX + dz * towardZ;
                }));
    }

    /**
     * Selects a strictly observed lateral detour when the four cardinal
     * centerward candidates are blocked by a natural pillar or wall.  A
     * player may need to spend one or two blocks of radial distance to get
     * around an obstacle; requiring every step to reduce the radius makes
     * the End entry controller deadlock at exactly that geometry.  The
     * candidate is still bounded by the fair navigation snapshot, current
     * support/clearance evidence, the ready arena radius, and the previous
     * step guard.  No block state or inferred waypoint is used.
     */
    private Optional<GridPos> selectObservedSideStep(
            final CoreSkillFrame frame
    ) {
        final long revision = frame.observationRevision();
        final GridPos current = frame.feet();
        final double currentRadius = EndArenaTopology.horizontalRadius(
                frame.position()
        );
        final double towardX = EndArenaTopology.CENTER_X - frame.position().x();
        final double towardZ = EndArenaTopology.CENTER_Z - frame.position().z();
        return List.of(
                new GridPos(current.x() - 1, current.y(), current.z()),
                new GridPos(current.x() + 1, current.y(), current.z()),
                new GridPos(current.x(), current.y(), current.z() - 1),
                new GridPos(current.x(), current.y(), current.z() + 1)
        ).stream()
                .filter(candidate -> EndArenaTopology.insideArenaReadyRadius(
                        new PerceptionVec3(
                                candidate.x() + 0.5,
                                candidate.y(),
                                candidate.z() + 0.5
                        )
                ))
                .filter(candidate -> !candidate.equals(
                        previousRallyStepTarget
                ))
                .filter(candidate -> frame.navigation().voxelAt(candidate)
                        .filter(voxel -> hasObservedRallyPlanningClearance(
                                voxel,
                                revision,
                                false
                        ))
                        .isPresent())
                .filter(candidate -> frame.navigation().voxelAt(candidate.above())
                        .filter(voxel -> NavigationEvidence
                                .hasFreshTraversalClearance(
                                        voxel,
                                        revision
                                ))
                        .isPresent())
                .filter(candidate -> frame.navigation().voxelAt(candidate.below())
                        .filter(voxel -> hasObservedRallySupport(
                                voxel,
                                revision,
                                false
                        ))
                        .isPresent())
                .filter(candidate -> {
                    final double radius = EndArenaTopology.horizontalRadius(
                            new PerceptionVec3(
                                    candidate.x() + 0.5,
                                    candidate.y(),
                                    candidate.z() + 0.5
                            )
                    );
                    return radius <= FIGHT_REENTRY_RADIUS
                            && radius <= currentRadius + 2.5;
                })
                .min(Comparator
                        .comparingDouble((GridPos candidate) -> {
                            final double x = candidate.x() + 0.5;
                            final double z = candidate.z() + 0.5;
                            return EndArenaTopology.horizontalRadius(
                                    new PerceptionVec3(
                                            x,
                                            candidate.y(),
                                            z
                                    )
                            );
                        })
                        .thenComparingDouble(candidate -> {
                            final double dx = candidate.x() + 0.5
                                    - frame.position().x();
                            final double dz = candidate.z() + 0.5
                                    - frame.position().z();
                            return -(dx * towardX + dz * towardZ);
                        })
                        .thenComparingInt(GridPos::x)
                        .thenComparingInt(GridPos::z));
    }

    private static PerceptionVec3 centerwardSearchPoint(
            final CoreSkillFrame frame
    ) {
        /* Keep the fallback to one observed-cell scale. A longer inferred
         * waypoint lets rolling travel explore an unobserved frontier and
         * can drift away from the central island before the next fair scan. */
        return EndArenaTopology.oneCardinalStepTowardCenter(
                frame.position()
        );
    }

    private static boolean hasObservedRallySupport(
            final ObservedVoxel voxel,
            final long revision,
            final boolean allowHistorical
    ) {
        return NavigationEvidence.isFreshStandingSupport(voxel, revision)
                || allowHistorical
                && voxel.kind().supportsWeight()
                && (voxel.occupancyEvidence()
                        == OccupancyEvidence.SURFACE_HIT
                    || voxel.occupancyEvidence()
                        == OccupancyEvidence.BODY_CONTACT)
                && voxel.observationRevision() <= revision
                && revision - voxel.observationRevision()
                        <= OBSERVED_RALLY_MEMORY_MAX_AGE
                && voxel.topSupportAffordance().safelySupportsStanding()
                || NavigationEvidence.isRecentBodyContactSupport(
                        voxel,
                        revision,
                        OBSERVED_RALLY_SUPPORT_MAX_AGE
                );
    }

    private static boolean hasObservedRallyPlanningClearance(
            final ObservedVoxel voxel,
            final long revision,
            final boolean allowHistorical
    ) {
        if (!allowHistorical) {
            return hasObservedRallyClearance(voxel, revision);
        }
        return NavigationEvidence.hasFreshTraversalClearance(
                voxel,
                revision
        ) || voxel.kind().isPassable()
                && voxel.observationRevision() <= revision
                && revision - voxel.observationRevision()
                        <= OBSERVED_RALLY_MEMORY_MAX_AGE
                && NavigationEvidence.hasTraversalClearance(voxel);
    }

    /**
     * A body-occupied adjacent cell can legitimately straddle two semantic
     * revisions while vanilla travel is settling. Treat that very narrow,
     * recent self-contact as clearance; the head cell remains required to be
     * fresh in the caller, so this does not authorize an unseen corridor.
     */
    private static boolean hasObservedRallyClearance(
            final ObservedVoxel voxel,
            final long revision
    ) {
        return NavigationEvidence.hasFreshTraversalClearance(
                voxel,
                revision
        ) || voxel.kind() == VoxelKind.AIR
                && voxel.occupancyEvidence()
                        == OccupancyEvidence.BODY_OCCUPIED
                && voxel.observationRevision() <= revision
                && revision - voxel.observationRevision()
                        <= OBSERVED_RALLY_SUPPORT_MAX_AGE;
    }

    /**
     * Select a bounded, freshly observed standing cell for the next search
     * leg. Returning to the exact start pose after every empty scan is safe
     * but cannot discover a dragon that has flown to another side of the
     * natural island. This helper consumes only current first-person
     * navigation evidence; unknown cells and inferred terrain are ineligible.
     * TravelTo still has to prove a normal local route before movement.
     */
    private Optional<PerceptionVec3> selectObservedRallyPoint(
            final CoreSkillFrame frame
    ) {
        final long revision = frame.observationRevision();
        final GridPos current = frame.feet();
        final double heading = Math.toRadians(
                scanBaseYaw + rallyAttempts * 90.0
        );
        final double headingX = Math.cos(heading);
        final double headingZ = Math.sin(heading);
        final double centerwardX = EndArenaTopology.CENTER_X
                - frame.position().x();
        final double centerwardZ = EndArenaTopology.CENTER_Z
                - frame.position().z();
        final double centerwardLength = Math.hypot(
                centerwardX,
                centerwardZ
        );
        if (!Double.isFinite(centerwardLength)
                || centerwardLength < 1.0E-6) {
            return Optional.empty();
        }
        final double centerwardUnitX = centerwardX / centerwardLength;
        final double centerwardUnitZ = centerwardZ / centerwardLength;
        return frame.navigation().observedVoxels().values().stream()
                .filter(voxel -> voxel.observationRevision() == revision)
                .filter(voxel -> voxel.position().y() == current.y())
                .filter(voxel -> !voxel.position().equals(current))
                .filter(voxel -> NavigationEvidence.hasFreshTraversalClearance(
                        voxel,
                        revision
                ))
                .filter(voxel -> frame.navigation()
                        .voxelAt(voxel.position().above())
                        .filter(head ->
                                NavigationEvidence
                                        .hasFreshTraversalClearance(
                                                head,
                                                revision
                                        ))
                        .isPresent())
                .filter(voxel -> frame.navigation()
                        .voxelAt(voxel.position().below())
                        .filter(support ->
                                NavigationEvidence.isFreshStandingSupport(
                                        support,
                                        revision
                                ))
                        .isPresent())
                .filter(voxel -> voxel.effectiveDanger() <= 0.20)
                .map(ObservedVoxel::position)
                .filter(position -> position.euclideanDistance(current) >= 2)
                .filter(position -> position.euclideanDistance(current) <= 10)
                .map(position -> new PerceptionVec3(
                        position.x() + 0.5,
                        position.y(),
                        position.z() + 0.5
                ))
                .filter(position -> {
                    final double dx = position.x() - frame.position().x();
                    final double dz = position.z() - frame.position().z();
                    return dx * centerwardUnitX
                            + dz * centerwardUnitZ > 0.5;
                })
                .filter(EndArenaTopology::insideArenaReadyRadius)
                .max(Comparator.comparingDouble(candidate -> {
                    final double dx = candidate.x() - frame.position().x();
                    final double dz = candidate.z() - frame.position().z();
                    final double distance = Math.hypot(dx, dz);
                    final double directional = dx * headingX + dz * headingZ;
                    final double centerward = dx * centerwardUnitX
                            + dz * centerwardUnitZ;
                    return centerward * 2.0 + directional * 0.1
                            + distance * 0.01;
                }));
    }

    private SkillTickResult tickTravel(
            final SkillContext context,
            final boolean fresh
    ) {
        final SkillTickResult result =
                travel.tick(context, travelParameters);
        if (result.status() != SkillTickResult.Status.RUNNING) {
            final TravelPurpose completedPurpose =
                    travelPurpose;
            lastRallyFailure = "travel_result:" + result.status()
                    + result.failure().map(failure -> "/" + failure.code())
                            .orElse("");
            travel = null;
            travelParameters = null;
            travelPurpose = TravelPurpose.NONE;
            phase = Phase.SEARCHING;
            scanTurns = 0;
            nextActionTick = context.gameTick();
            if (completedPurpose == TravelPurpose.RALLY
                    && result.status() == SkillTickResult.Status.COMPLETED) {
                skyBlocksSinceRally = 0;
                coreFrames.current().ifPresent(current ->
                        localRallyPoint = current.position()
                );
            }
            if (completedPurpose
                    == TravelPurpose.CAGE_APPROACH) {
                if (result.status()
                        == SkillTickResult.Status.COMPLETED) {
                    cageStatus =
                            CageStatus.APPROACH_REACHED;
                } else {
                    cagePlan = null;
                    cageStatus =
                            CageStatus.APPROACH_CELL_UNAVAILABLE;
                }
            }
            coreFrames.current().ifPresent(frame ->
                    scanBaseYaw = lookYaw(frame)
            );
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                result.madeProgress() || fresh,
                result.safeCheckpoint()
        );
    }

    private static Optional<Integer> selectTargetIndex(
            final CoreSkillFrame frame
    ) {
        final Optional<Integer> crystal =
                clearCrystalIndex(frame);
        if (crystal.isPresent()) {
            return crystal;
        }
        return java.util.stream.IntStream.range(
                0,
                frame.visibleEntities().size()
        )
                .boxed()
                .filter(index ->
                        ENDER_DRAGON.equals(
                            frame.visibleEntities()
                                .get(index)
                                .entityTypeId()
                        )
                )
                .min(Comparator.comparingDouble(index ->
                        frame.visibleEntities()
                            .get(index)
                            .distance()
                ));
    }

    private static Optional<Integer> immediateDragonIndex(
            final CoreSkillFrame frame
    ) {
        final boolean damageNearby = frame.dangerSignals().stream()
                .anyMatch(signal ->
                        (signal.kind() == DangerKind.THREAT_CONTACT
                                || signal.kind()
                                        == DangerKind.PROJECTILE_PROXIMITY)
                                && signal.severity() >= 0.35);
        return java.util.stream.IntStream.range(
                        0,
                        frame.visibleEntities().size()
                )
                .boxed()
                .filter(index -> ENDER_DRAGON.equals(
                        frame.visibleEntities().get(index).entityTypeId()
                ))
                .filter(index ->
                        damageNearby
                                || frame.visibleEntities().get(index)
                                        .distance()
                                    <= IMMEDIATE_DRAGON_DISTANCE
                )
                .min(Comparator.comparingDouble(index ->
                        frame.visibleEntities().get(index).distance()
                ));
    }

    private static Optional<Integer> nearestDragonIndex(
            final CoreSkillFrame frame
    ) {
        return java.util.stream.IntStream.range(
                        0,
                        frame.visibleEntities().size()
                )
                .boxed()
                .filter(index -> ENDER_DRAGON.equals(
                        frame.visibleEntities().get(index).entityTypeId()
                ))
                .min(Comparator.comparingDouble(index ->
                        frame.visibleEntities().get(index).distance()
                ));
    }

    private static Optional<Integer> clearCrystalIndex(
            final CoreSkillFrame frame
    ) {
        return java.util.stream.IntStream.range(
                        0,
                        frame.visibleEntities().size()
                )
                    .boxed()
                    .filter(index ->
                            END_CRYSTAL.equals(
                                frame.visibleEntities()
                                    .get(index)
                                    .entityTypeId()
                            )
                    )
                    .filter(index ->
                            frame.visibleEntities()
                                .get(index)
                                .distance()
                                    >= EndCrystalStandOffPlanner
                                        .MINIMUM_FIRE_DISTANCE
                    )
                    .filter(index ->
                            interactionLineClear(
                                frame.visibleEntities().get(index)
                            )
                    )
                    .min(Comparator.comparingDouble(index ->
                            frame.visibleEntities()
                                .get(index)
                                .distance()
                    ));
    }

    private static boolean hasBlockedCrystal(
            final CoreSkillFrame frame
    ) {
        return frame.visibleEntities().stream().anyMatch(entity ->
                END_CRYSTAL.equals(entity.entityTypeId())
                    && !interactionLineClear(entity)
        );
    }

    private boolean shouldDescendFromCage(
            final CoreSkillFrame frame
    ) {
        if (!cageTowered || cagePlan == null) {
            return false;
        }
        if (cageCrystalId == null) {
            return true;
        }
        return frame.visibleEntities().stream()
                .filter(entity ->
                        cageCrystalId.equals(entity.entityId())
                )
                .findFirst()
                .map(FightEnderDragonSkill::interactionLineClear)
                .orElse(true);
    }

    private Optional<VisibleEntity> boundBlockedCrystal(
            final CoreSkillFrame frame
    ) {
        if (cageCrystalId == null) {
            return Optional.empty();
        }
        return frame.visibleEntities().stream()
                .filter(entity ->
                        cageCrystalId.equals(entity.entityId())
                )
                .filter(entity ->
                        END_CRYSTAL.equals(
                                entity.entityTypeId()
                        )
                )
                .filter(entity ->
                        !interactionLineClear(entity)
                )
                .findFirst();
    }

    private static Optional<VisibleEntity> closestBlockedCrystal(
            final CoreSkillFrame frame
    ) {
        return frame.visibleEntities().stream()
                .filter(entity ->
                        END_CRYSTAL.equals(
                                entity.entityTypeId()
                        )
                )
                .filter(entity ->
                        !interactionLineClear(entity)
                )
                .min(Comparator.comparingDouble(
                        VisibleEntity::distance
                ));
    }

    private static Optional<VisibleEntity>
            closestCrystalWithAlignedCageBar(
                    final CoreSkillFrame frame
            ) {
        return frame.visibleEntities().stream()
                .filter(entity ->
                        END_CRYSTAL.equals(
                                entity.entityTypeId()
                        )
                )
                .filter(entity ->
                        alignedCageBar(
                                frame,
                                entity
                        ).isPresent()
                )
                .min(Comparator.comparingDouble(
                        VisibleEntity::distance
                ));
    }

    private double cageDrop(final CoreSkillFrame frame) {
        return cagePlan == null
                ? 0.0
                : frame.position().y()
                    - cagePlan.landing().y();
    }

    private static boolean visibleLandingSupport(
            final CoreSkillFrame frame,
            final GridPos landing
    ) {
        final GridPos support = landing.below();
        return frame.visibleBlockFaces().stream().anyMatch(face ->
                face.block().x() == support.x()
                    && face.block().y() == support.y()
                    && face.block().z() == support.z()
                    && blockFace(face.face())
                        .filter(value -> value == BlockFace.UP)
                        .isPresent()
        );
    }

    private void rememberVisibleLanding(
            final CoreSkillFrame frame,
            final GridPos landing
    ) {
        if (visibleLandingSupport(frame, landing)) {
            cageLandingVerified = true;
            cageLandingVerifiedRevision =
                    frame.observationRevision();
        }
    }

    private boolean recentLandingVerification(
            final CoreSkillFrame frame
    ) {
        return cageLandingVerified
                && cageLandingVerifiedRevision >= 0
                && frame.observationRevision()
                    >= cageLandingVerifiedRevision
                && frame.observationRevision()
                    - cageLandingVerifiedRevision <= 4;
    }

    private static PerceptionVec3 landingTop(
            final GridPos landing
    ) {
        return new PerceptionVec3(
                landing.x() + 0.5,
                landing.y(),
                landing.z() + 0.5
        );
    }

    private void clearCageTraversalPlan() {
        cageCrystalId = null;
        cageLastSeenPosition = null;
        cagePlan = null;
        cageApproachAttempts = 0;
        cageDescentScans = 0;
        cageLandingScans = 0;
        cageLandingVerified = false;
        cageLandingVerifiedRevision = -1;
        sawCageBarBeyondReach = false;
    }

    private static Optional<VisibleBlockFace> alignedCageBar(
            final CoreSkillFrame frame,
            final VisibleEntity crystal
    ) {
        final PerceptionVec3 origin = frame.eyePosition();
        final PerceptionVec3 ray =
                crystal.position().subtract(origin);
        final double rayLengthSquared = ray.lengthSquared();
        if (rayLengthSquared <= 1.0E-12) {
            return Optional.empty();
        }
        return frame.visibleBlockFaces().stream()
                .filter(face ->
                        IRON_BARS.equals(face.blockTypeId())
                )
                .filter(face -> {
                    final PerceptionVec3 fromOrigin =
                            face.hitPosition().subtract(origin);
                    final double fraction = fromOrigin.dot(ray)
                            / rayLengthSquared;
                    if (fraction <= 0.0 || fraction >= 1.05) {
                        return false;
                    }
                    final PerceptionVec3 closest =
                            origin.add(ray.scale(fraction));
                    return face.hitPosition()
                            .subtract(closest)
                            .length()
                            <= CAGE_LINE_RADIUS;
                })
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private static Optional<VisibleBlockFace> visibleOverheadEndStone(
            final CoreSkillFrame frame,
            final GridPos excluded
    ) {
        final GridPos feet = frame.feet();
        return frame.visibleBlockFaces().stream()
                .filter(face ->
                        "minecraft:end_stone".equals(
                                face.blockTypeId()
                        ))
                .filter(face -> face.block().x() == feet.x()
                        && face.block().z() == feet.z()
                        && face.block().y() >= feet.y() + 2
                        && face.block().y() <= feet.y() + 8)
                .filter(face -> excluded == null
                        || !new GridPos(
                                face.block().x(),
                                face.block().y(),
                                face.block().z()
                        ).equals(excluded))
                .filter(face -> face.distance() <= SKY_BREAK_REACH)
                .min(Comparator.comparingInt(
                        face -> face.block().y()
                ));
    }

    private static Optional<VisibleBlockFace> visibleLateralEndStone(
            final CoreSkillFrame frame,
            final GridPos excluded
    ) {
        final GridPos feet = frame.feet();
        return frame.visibleBlockFaces().stream()
                .filter(face ->
                        "minecraft:end_stone".equals(
                                face.blockTypeId()
                        ))
                .filter(face -> face.distance() <= SKY_BREAK_STANDING_REACH)
                .filter(face -> face.block().y() >= feet.y()
                        && face.block().y() <= feet.y() + 3)
                .filter(face -> face.block().x() >= feet.x() - 1
                        && face.block().x() <= feet.x() + 1
                        && face.block().z() >= feet.z() - 1
                        && face.block().z() <= feet.z() + 1)
                .filter(face -> face.block().x() != feet.x()
                        || face.block().z() != feet.z())
                .filter(face -> excluded == null
                        || !new GridPos(
                                face.block().x(),
                                face.block().y(),
                                face.block().z()
                        ).equals(excluded))
                .filter(face -> blockFace(face.face())
                        .filter(value -> value != BlockFace.UP)
                        .isPresent())
                .min(Comparator
                        .comparingDouble(
                                (VisibleBlockFace face) ->
                                        lateralCenterwardScore(
                                                frame,
                                                face
                                        )
                        )
                .thenComparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private static Optional<VisibleBlockFace> selectSkyClearanceTarget(
            final CoreSkillFrame frame,
            final Optional<VisibleBlockFace> overhead,
            final Optional<VisibleBlockFace> lateral
    ) {
        if (overhead.isEmpty()) {
            return lateral;
        }
        if (lateral.isEmpty()) {
            return overhead;
        }
        /* If the current head cell is already freshly clear, a lateral wall
         * is the only observed obstruction still worth opening. If it is not
         * clear, remove the direct current-column overhead block first. This
         * prevents an unnecessary ceiling mine from starving a visible side
         * exit, while avoiding the old lateral-first churn when the head is
         * genuinely blocked. Both branches remain bounded by the first-person
         * fan, reach and BreakBlock's current-crosshair validation; no unseen
         * terrain is inferred here. */
        return freshHeadClearance(frame) ? lateral : overhead;
    }

    private static double lateralCenterwardScore(
            final CoreSkillFrame frame,
            final VisibleBlockFace face
    ) {
        final double dx = EndArenaTopology.CENTER_X
                - frame.position().x();
        final double dz = EndArenaTopology.CENTER_Z
                - frame.position().z();
        final double length = Math.max(1.0E-6, Math.hypot(dx, dz));
        final double candidateX = face.block().x() + 0.5
                - frame.position().x();
        final double candidateZ = face.block().z() + 0.5
                - frame.position().z();
        return -(candidateX * dx + candidateZ * dz) / length;
    }

    private static ObservedBlockTarget observedTarget(
            final CoreSkillFrame frame,
            final VisibleBlockFace face
    ) {
        return new ObservedBlockTarget(
                frame.observationRevision(),
                face.block().x(),
                face.block().y(),
                face.block().z(),
                blockFace(face.face()).orElse(BlockFace.DOWN)
        );
    }

    private static boolean sameObservedTarget(
            final VisibleBlockFace face,
            final ObservedBlockTarget target
    ) {
        return face.block().x() == target.x()
                && face.block().y() == target.y()
                && face.block().z() == target.z()
                && blockFace(face.face()).orElse(null)
                    == target.face();
    }

    /**
     * A retained sky target may expose a different face after the player
     * turns a few degrees around the same solid block.  The block identity is
     * still fair, current first-person evidence; the interaction actuator
     * supplies the exact current crosshair face immediately before mining.
     */
    private static boolean sameObservedBlock(
            final VisibleBlockFace face,
            final ObservedBlockTarget target
    ) {
        return face.block().x() == target.x()
                && face.block().y() == target.y()
                && face.block().z() == target.z();
    }

    private static boolean interactionLineClear(
            final VisibleEntity entity
    ) {
        return !"false".equals(
                entity.visibleProperties().get(
                    "interactionLineClear"
                )
        );
    }

    private static boolean projectileThreatensBody(
            final VisibleEntity projectile
    ) {
        /*
         * The fair sampler marks the companion's own arrows as visible but
         * non-threatening. Older fixtures without this property remain
         * conservative and are treated as threats.
         */
        return !"false".equals(
                projectile.visibleProperties().get("projectileThreat")
        );
    }

    private static Optional<String> preferredMeleeWeapon(
            final CoreSkillFrame frame
    ) {
        return MELEE_WEAPONS.stream().filter(itemId ->
                inventoryCount(frame, itemId) > 0
        ).findFirst();
    }

    private static Optional<String> preferredCageMiningTool(
            final CoreSkillFrame frame
    ) {
        return CAGE_MINING_TOOLS.stream().filter(itemId ->
                inventoryCount(frame, itemId) > 0
        ).findFirst();
    }

    private static Optional<BlockFace> blockFace(
            final String serialized
    ) {
        final int separator = serialized.lastIndexOf(':');
        final String token = separator >= 0
                ? serialized.substring(separator + 1)
                : serialized;
        try {
            return Optional.of(BlockFace.valueOf(
                    token.toUpperCase(Locale.ROOT)
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static PerceptionVec3 cageLookOffset(
            final int scanTurn
    ) {
        return switch (scanTurn % 6) {
            case 0 -> new PerceptionVec3(0.0, 0.0, 0.0);
            case 1 -> new PerceptionVec3(0.0, 1.25, 0.0);
            case 2 -> new PerceptionVec3(0.0, -0.75, 0.0);
            case 3 -> new PerceptionVec3(0.75, 0.25, 0.0);
            case 4 -> new PerceptionVec3(-0.75, 0.25, 0.0);
            default -> new PerceptionVec3(0.0, 0.25, 0.75);
        };
    }

    private static LookIntent lookAt(
            final PerceptionVec3 eye,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta = target.subtract(eye);
        return new LookIntent(
                (float) Math.toDegrees(
                        Math.atan2(-delta.x(), delta.z())
                ),
                (float) Math.toDegrees(Math.atan2(
                        -delta.y(),
                        Math.hypot(delta.x(), delta.z())
                ))
        );
    }

    private static double angularError(
            final PerceptionVec3 current,
            final PerceptionVec3 desired
    ) {
        if (desired.lengthSquared() <= 1.0E-12) {
            return 180.0;
        }
        final double dot = current.normalized()
                .dot(desired.normalized());
        return Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
    }

    private SnapshotValidation validateSnapshot(
            final FightEnderDragonParameters parameters
    ) {
        final Optional<CoreSkillFrame> maybeCore =
                coreFrames.current();
        final Optional<InteractionSkillFrame> maybeInteraction =
                interactionFrames.current();
        if (maybeCore.isEmpty() || maybeInteraction.isEmpty()) {
            return SnapshotValidation.failed(
                    NAME + ".observation_unavailable"
            );
        }
        final CoreSkillFrame coreFrame = maybeCore.orElseThrow();
        final InteractionSkillFrame interaction =
                maybeInteraction.orElseThrow();
        if (!expectedPlayerId.equals(coreFrame.playerId())
                || !expectedPlayerId.equals(
                    interaction.playerId()
                )) {
            return SnapshotValidation.failed(
                    NAME + ".body_mismatch"
            );
        }
        if (!parameters.dimension().equals(coreFrame.dimension())
                || !coreFrame.dimension().equals(
                    interaction.dimension()
                )) {
            return SnapshotValidation.failed(
                    NAME + ".wrong_dimension"
            );
        }
        if (coreFrame.observationRevision()
                != interaction.observationRevision()) {
            return SnapshotValidation.failed(
                    NAME + ".observation_desynchronized"
            );
        }
        final OptionalLong session =
                interactions.sessionGeneration();
        if (session.isEmpty()
                || session.orElseThrow()
                    != interaction.sessionGeneration()
                || boundSessionGeneration >= 0
                    && session.orElseThrow()
                        != boundSessionGeneration) {
            return SnapshotValidation.failed(
                    NAME + ".session_mismatch"
            );
        }
        return SnapshotValidation.available(
                new Snapshot(coreFrame, interaction)
        );
    }

    private static boolean healthTooLow(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double ratio = frame.health() / frame.maxHealth();
        return ratio < (context.hardcore() ? 0.50 : 0.25);
    }

    private static boolean cageTraversalSafety(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double maximumDanger =
                context.hardcore() ? 0.04 : 0.12;
        final double minimumHealth =
                context.hardcore() ? 0.95 : 0.80;
        return context.riskScore() <= maximumDanger
                && frame.danger() <= maximumDanger
                && frame.health() / frame.maxHealth()
                    >= minimumHealth
                && frame.foodLevel() >= 8
                && frame.onGround()
                && !frame.inWater();
    }

    private static int inventoryCount(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().equals(itemId))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static float lookYaw(final CoreSkillFrame frame) {
        return (float) Math.toDegrees(Math.atan2(
                -frame.lookDirection().x(),
                frame.lookDirection().z()
        ));
    }

    private static float yawFromDirection(
            final PerceptionVec3 direction
    ) {
        return (float) Math.toDegrees(Math.atan2(
                -direction.x(),
                direction.z()
        ));
    }

    private static boolean transientShotFailure(
            final String code
    ) {
        return code.endsWith(".target_lost")
                || code.endsWith(".stale_observation")
                || code.endsWith(".stale_observation_id")
                /*
                 * The 20 TPS survival supervisor may fairly pre-empt the
                 * bow with food after a crystal explosion. The child has
                 * already released its use action on failure; reobserve and
                 * equip again after the safety action instead of converting
                 * that expected pre-emption into a terminal dragon failure.
                 */
                || code.endsWith(".weapon_changed")
                || code.endsWith(".crystal_too_close")
                || code.endsWith(".target_out_of_reach")
                /*
                 * A dragon part can move between the sampled observation
                 * and the vanilla use packet.  Alignment, line-of-sight,
                 * and use-start failures are therefore ordinary retryable
                 * first-person races, not reasons to hand the whole fight
                 * back to the language model.  Releasing the child below
                 * always returns the skill to SEARCHING, where a fresh
                 * observation must prove the next shot.
                 */
                || code.endsWith(".interaction_line_blocked")
                || code.endsWith(".look_rejected")
                || code.endsWith(".stop_rejected")
                || code.endsWith(".use_start_rejected")
                || code.endsWith(".use_interrupted")
                || code.endsWith(".release_rejected")
                /*
                 * A dragon wing/contact pulse can raise the sampled danger
                 * after the child has already begun aiming.  The emergency
                 * supervisor owns the immediate retreat/guard response; the
                 * dragon skill must reobserve and retry instead of ending
                 * the entire victory goal on that one fair safety rejection.
                 */
                || code.endsWith(".danger_too_high");
    }

    private static boolean transientCageBreakFailure(
            final String code
    ) {
        return code.endsWith(".observation_expired")
                || code.endsWith(".target_not_visible")
                || code.endsWith(".target_out_of_range")
                || code.endsWith(".stale_observation")
                || code.endsWith(".target_changed")
                || code.endsWith(".action_target_occluded")
                || code.endsWith(".action_target_out_of_reach");
    }

    private void cancelChildren(final SkillContext context) {
        if (shot != null && shotParameters != null) {
            try {
                shot.cancel(context, shotParameters);
            } catch (RuntimeException ignored) {
                interactions.releaseUse();
            }
        }
        shot = null;
        shotParameters = null;
        shotTargetDragon = false;
        if (travel != null && travelParameters != null) {
            try {
                travel.cancel(context, travelParameters);
            } catch (RuntimeException ignored) {
                core.stop();
            }
        }
        travel = null;
        travelParameters = null;
        travelPurpose = TravelPurpose.NONE;
        if (islandIngress != null && islandIngressParameters != null) {
            try {
                islandIngress.cancel(context, islandIngressParameters);
            } catch (RuntimeException ignored) {
                core.stop();
                interactions.abortMining();
            }
        }
        islandIngress = null;
        islandIngressParameters = null;
        if (crystalStandOff != null
                && crystalStandOffParameters != null) {
            try {
                crystalStandOff.cancel(
                        context,
                        crystalStandOffParameters
                );
            } catch (RuntimeException ignored) {
                core.stop();
            }
        }
        clearCrystalStandOffChild();
        if (crystalLane != null && crystalLaneParameters != null) {
            try {
                crystalLane.cancel(context, crystalLaneParameters);
            } catch (RuntimeException ignored) {
                core.stop();
            }
        }
        crystalLane = null;
        crystalLaneParameters = null;
        if (cageBreak != null
                && cageBreakParameters != null) {
            try {
                cageBreak.cancel(
                        context,
                        cageBreakParameters
                );
            } catch (RuntimeException ignored) {
                interactions.abortMining();
            }
        }
        cageBreak = null;
        cageBreakParameters = null;
        if (cageTower != null
                && cageTowerParameters != null) {
            try {
                cageTower.cancel(
                        context,
                        cageTowerParameters
                );
            } catch (RuntimeException ignored) {
                core.stop();
            }
        }
        cageTower = null;
        cageTowerParameters = null;
        if (cageDescent != null
                && cageDescentParameters != null) {
            try {
                cageDescent.cancel(
                        context,
                        cageDescentParameters
                );
            } catch (RuntimeException ignored) {
                core.stop();
            }
        }
        cageDescent = null;
        cageDescentParameters = null;
    }

    private void quiesce() {
        interactions.releaseUse();
        core.releaseUse();
        core.stop();
    }

    private SkillTickResult fail(
            final SkillContext context,
            final String code
    ) {
        return fail(context, SkillFailure.of(code));
    }

    private SkillTickResult fail(
            final SkillContext context,
            final SkillFailure reason
    ) {
        cancelChildren(context);
        quiesce();
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private enum Phase {
        IDLE,
        SEARCHING,
        ALIGNING_SKY,
        OPENING_SKY,
        SHOOTING,
        RETREATING_DRAGON,
        REPOSITIONING_CRYSTAL,
        REPOSITIONING_CRYSTAL_LANE,
        TRAVELLING,
        RALLY_STEPPING,
        REENTERING_ISLAND,
        OPENING_CAGE,
        PREPARING_CAGE_TOWER,
        TOWERING_CAGE,
        PREPARING_CAGE_DESCENT,
        DESCENDING_CAGE,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return this == SEARCHING
                    || this == ALIGNING_SKY
                    || this == OPENING_SKY
                    || this == SHOOTING
                    || this == RETREATING_DRAGON
                    || this == REPOSITIONING_CRYSTAL
                    || this == REPOSITIONING_CRYSTAL_LANE
                    || this == TRAVELLING
                    || this == RALLY_STEPPING
                    || this == REENTERING_ISLAND
                    || this == OPENING_CAGE
                    || this == PREPARING_CAGE_TOWER
                    || this == TOWERING_CAGE
                    || this == PREPARING_CAGE_DESCENT
                    || this == DESCENDING_CAGE;
        }
    }

    private enum CageStatus {
        NONE,
        SEEKING_VISIBLE_BAR,
        APPROACH_OR_ELEVATION_REQUIRED,
        SAFE_TRAVERSAL_UNAVAILABLE,
        SAFETY_RESERVE_REQUIRED,
        PICKAXE_REQUIRED,
        WATER_BUCKET_REQUIRED,
        EQUIPPING_PICKAXE,
        APPROACH_CELL_UNAVAILABLE,
        APPROACHING,
        APPROACH_REACHED,
        VERIFYING_LANDING,
        REACQUIRING_CAGE,
        TOWERING,
        ELEVATED,
        ALIGNING_VISIBLE_BAR,
        MINING_VISIBLE_BAR,
        VERIFYING_OPENING,
        SCANNING_LANDING,
        DESCENDING,
        DESCENT_COMPLETED
    }

    private enum TravelPurpose {
        NONE,
        RALLY,
        CAGE_APPROACH
    }

    private record Snapshot(
            CoreSkillFrame core,
            InteractionSkillFrame interaction
    ) {
    }

    private record SnapshotValidation(
            Optional<Snapshot> snapshot,
            Optional<SkillFailure> failure
    ) {
        private static SnapshotValidation available(
                final Snapshot snapshot
        ) {
            return new SnapshotValidation(
                    Optional.of(snapshot),
                    Optional.empty()
            );
        }

        private static SnapshotValidation failed(
                final String code
        ) {
            return new SnapshotValidation(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }
}
