package dev.mcai.companion.skills.loot;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.progression.CompletionResourceReadiness;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.MoveToParameters;
import dev.mcai.companion.skills.core.MoveToSkill;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.exploration.ExploreForObservedTargetSkill;
import dev.mcai.companion.skills.exploration.ExploreForTargetParameters;
import dev.mcai.companion.skills.exploration.SearchTargetKind;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

/**
 * Secures the completion route's Ender-pearl reserve with one model decision.
 *
 * <p>The compound builds or re-verifies a local two-block-high roof using
 * ordinary player placement, lures only a currently visible Enderman, binds
 * each fight to the existing sheltered one-target skill, confirms vanilla
 * drops, and physically returns to the roof after pickup. When no target is
 * visible it performs bounded first-person search; arriving at a new target
 * causes a new locally generated shelter rather than unsafe open combat.</p>
 */
public final class SecureEnderPearlReserveSkill
        implements Skill<NoParameters> {
    public static final String NAME =
            "secure_ender_pearl_reserve";

    private static final String ENDERMAN = "minecraft:enderman";
    private static final String ENDER_PEARL =
            "minecraft:ender_pearl";
    private static final String ENDER_EYE =
            "minecraft:ender_eye";
    private static final String ITEM_ENTITY = "minecraft:item";
    private static final int MAXIMUM_TICKS = 180_000;
    private static final int CHILD_MAXIMUM_TICKS = 1_200;
    private static final int MAXIMUM_TARGET_ATTEMPTS = 96;
    private static final int MAXIMUM_SCAN_TURNS = 48;
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int MAXIMUM_SCAN_ALIGNMENT_TICKS = 40;
    private static final int MAXIMUM_LURE_TICKS = 500;
    private static final int MAXIMUM_TARGET_LOST_TICKS = 80;
    private static final int MAXIMUM_RETURN_RECOVERY_ATTEMPTS = 3;
    private static final int MAXIMUM_RETURN_REOBSERVATION_TICKS = 12;
    private static final float SCAN_ALIGNMENT_TOLERANCE_DEGREES =
            2.0F;
    private static final int EXPLORATION_RADIUS = 192;
    private static final int EXPLORATION_STEP = 16;
    private static final double MAXIMUM_UNSHELTERED_TARGET_RISK =
            0.35;
    private static final double MINIMUM_UNSHELTERED_TARGET_DISTANCE =
            4.0;
    private static final double MAXIMUM_INITIAL_MELEE_DISTANCE =
            3.0;
    private static final double SHELTER_RETURN_RADIUS = 0.25;
    private static final double RISK_EPSILON = 1.0E-9;
    private static final long MAXIMUM_RECENT_DEFEAT_REVISION_AGE = 2L;
    private static final long MAXIMUM_RECENT_DEFEAT_TICK_AGE = 20L;
    private static final double NORMAL_MINIMUM_HEALTH = 0.55;
    private static final double HARDCORE_MINIMUM_HEALTH = 0.80;
    private static final int MINIMUM_FOOD = 7;
    private static final float[] YAW_OFFSETS = {
            0.0F,
            45.0F,
            -45.0F,
            90.0F,
            -90.0F,
            135.0F,
            -135.0F,
            180.0F
    };
    private static final float[] PITCHES = {
            0.0F,
            24.0F,
            -62.0F
    };
    private static final List<String> WEAPON_PREFERENCE = List.of(
            "minecraft:netherite_sword",
            "minecraft:diamond_sword",
            "minecraft:iron_sword",
            "minecraft:stone_sword",
            "minecraft:netherite_axe",
            "minecraft:diamond_axe",
            "minecraft:iron_axe",
            "minecraft:stone_axe"
    );

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final BuildEndermanSafetyRoofSkill roofBuilder;
    private final AcquireShelteredEnderPearlSkill hunter;
    private final Set<UUID> exhaustedTargets = new HashSet<>();

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1L;
    private long phaseStartedAtTick = -1L;
    private long nextScanTick = -1L;
    private int scanTurns;
    private int scanAlignmentTicks;
    private long lastScanAlignmentObservationRevision = -1L;
    private float scanBaseYaw;
    private int attemptedTargets;
    private int noDropAttempts;
    private int sheltersBuilt;
    private int returnsCompleted;
    private int extraDropsCollected;
    private int targetLostTicks;
    private int returnRecoveryAttempts;
    private long returnRecoveryObservationRevision = -1L;
    private long returnRecoveryStartedAtTick = -1L;
    private UUID returnJustDefeatedTargetId;
    private UUID activeTargetId;
    private UUID recentlyDefeatedTargetId;
    private long recentlyDefeatedAtObservationRevision = -1L;
    private long recentlyDefeatedAtTick = -1L;
    private GridPos shelterAnchor;
    private String pendingWeapon;
    private AcquireShelteredEnderPearlParameters huntParameters;
    private ExploreForObservedTargetSkill explorer;
    private ExploreForTargetParameters explorationParameters;
    private MoveToSkill returnMovement;
    private MoveToParameters returnParameters;
    private CollectObservedItemSkill pearlCollector;
    private CollectObservedItemParameters pearlCollectorParameters;

    public SecureEnderPearlReserveSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames
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
        roofBuilder = new BuildEndermanSafetyRoofSkill(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames
        );
        hunter = new AcquireShelteredEnderPearlSkill(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames
        );
    }

    @Override
    public SkillParameterParser<NoParameters> parameters() {
        return arguments -> arguments.isEmpty()
                ? SkillParameterResult.valid(NoParameters.INSTANCE)
                : SkillParameterResult.invalid(
                        NAME + ".invalid_arguments"
                );
    }

    @Override
    public boolean managesVisibleHostileProximity() {
        return (phase == Phase.HUNTING
                && hunter.managesVisibleHostileProximity())
                || (phase == Phase.BUILDING
                    || phase == Phase.LURING
                    || phase == Phase.SELECTING
                    || phase == Phase.EQUIPPING_WEAPON)
                    && activeTargetId != null
                || selectingShelteredEnderman();
    }

    @Override
    public boolean managesPhysicalContactThreats() {
        return phase == Phase.HUNTING
                && hunter.managesPhysicalContactThreats();
    }

    @Override
    public OptionalDouble hardcoreRiskThresholdOverride(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final Optional<CoreSkillFrame> frame = ownedFrame();
        if (frame.isEmpty() || !context.hardcore()) {
            return OptionalDouble.empty();
        }
        final CoreSkillFrame current = frame.orElseThrow();
        if (phase == Phase.HUNTING && huntParameters != null) {
            return hunter.hardcoreRiskThresholdOverride(
                    context,
                    huntParameters
            );
        }
        if (unsafeTransitionRisk(current, context)) {
            return OptionalDouble.empty();
        }
        if (phase == Phase.BUILDING
                && roofBuilder.revalidatingExistingRoof()
                && atShelter(current)
                && activeVisibleTarget(current).isPresent()
                && onlyEndermenVisible(current)) {
            return OptionalDouble.of(1.0);
        }
        if (shelterVerified(current)
                && onlyEndermenVisible(current)) {
            return OptionalDouble.of(1.0);
        }
        final Optional<VisibleEntity> active =
                activeVisibleTarget(current);
        if (active.isPresent()
                && active.orElseThrow().distance()
                    >= MINIMUM_UNSHELTERED_TARGET_DISTANCE
                && onlyEndermenVisible(current)) {
            return OptionalDouble.of(
                    MAXIMUM_UNSHELTERED_TARGET_RISK
            );
        }
        return OptionalDouble.empty();
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<CoreSkillFrame> frame = ownedFrame();
        if (frame.isEmpty()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".body_unavailable"
            ));
        }
        final CoreSkillFrame current = frame.orElseThrow();
        if (!DimensionRef.OVERWORLD.equals(current.dimension())
                && !DimensionRef.NETHER.equals(
                        current.dimension()
                )) {
            return Optional.of(SkillFailure.of(
                    NAME + ".overworld_or_nether_required"
            ));
        }
        if (!current.onGround() || current.inWater()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stable_pose_required"
            ));
        }
        if (enderRouteUnits(current)
                >= CompletionResourceReadiness.ENDER_ROUTE_UNITS
                || shelterVerified(current)) {
            return Optional.empty();
        }
        return roofBuilder.preconditions(
                context,
                NoParameters.INSTANCE
        );
    }

    @Override
    public void start(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final CoreSkillFrame frame = ownedFrame().orElseThrow(
                () -> new IllegalStateException(
                        "Companion body changed before pearl reserve"
                )
        );
        failure = null;
        exhaustedTargets.clear();
        cancelChildren(context);
        activeTargetId = null;
        clearRecentDefeat();
        shelterAnchor = shelterVerified(frame)
                ? frame.feet()
                : null;
        pendingWeapon = null;
        huntParameters = null;
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        nextScanTick = context.gameTick();
        scanTurns = 0;
        scanAlignmentTicks = 0;
        lastScanAlignmentObservationRevision = -1L;
        scanBaseYaw = yaw(frame);
        attemptedTargets = 0;
        noDropAttempts = 0;
        sheltersBuilt = 0;
        returnsCompleted = 0;
        extraDropsCollected = 0;
        targetLostTicks = 0;
        returnRecoveryAttempts = 0;
        returnRecoveryObservationRevision = -1L;
        returnRecoveryStartedAtTick = -1L;
        returnJustDefeatedTargetId = null;
        if (enderRouteUnits(frame)
                >= CompletionResourceReadiness.ENDER_ROUTE_UNITS) {
            phase = Phase.SELECTING;
            return;
        }
        if (shelterAnchor == null) {
            final Optional<SkillFailure> rejected =
                    roofBuilder.preconditions(
                            context,
                            NoParameters.INSTANCE
                    );
            if (rejected.isPresent()) {
                throw new IllegalStateException(
                        rejected.orElseThrow().code()
                );
            }
            roofBuilder.start(context, NoParameters.INSTANCE);
            phase = Phase.BUILDING;
        } else {
            phase = Phase.SELECTING;
        }
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final NoParameters parameters
    ) {
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context);
        } catch (RuntimeException exception) {
            MinecraftAiCompanion.LOGGER.error(
                    "Ender reserve internal failure in phase {}",
                    phase,
                    exception
            );
            return fail(context, NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final String builderChild = phase == Phase.BUILDING
                ? roofBuilder.checkpoint(
                        context,
                        NoParameters.INSTANCE
                ).payload()
                : "null";
        final String returnChild = returnMovement == null
                    || returnParameters == null
                ? "null"
                : returnMovement
                    .checkpoint(context, returnParameters)
                    .payload();
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"enderRouteUnits\":%d,"
                            + "\"attemptedTargets\":%d,"
                            + "\"noDropAttempts\":%d,"
                            + "\"exhaustedTargets\":%d,"
                            + "\"sheltersBuilt\":%d,"
                            + "\"returnsCompleted\":%d,"
                            + "\"extraDropsCollected\":%d,"
                            + "\"scanTurns\":%d,"
                            + "\"builderChild\":%s,"
                            + "\"returnChild\":%s}",
                        phase.name(),
                        ownedFrame().map(
                                SecureEnderPearlReserveSkill
                                    ::enderRouteUnits
                        ).orElse(0),
                        attemptedTargets,
                        noDropAttempts,
                        exhaustedTargets.size(),
                        sheltersBuilt,
                        returnsCompleted,
                        extraDropsCollected,
                        scanTurns,
                        builderChild,
                        returnChild
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        cancelChildren(context);
        core.stop();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(SkillFailure.of(
                    NAME + ".invalid_state"
            ));
        };
    }

    private SkillTickResult tickSafely(
            final SkillContext context
    ) {
        if (context.gameTick() - startedAtTick >= MAXIMUM_TICKS) {
            return fail(context, NAME + ".timed_out");
        }
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        if (frame == null) {
            return fail(context, NAME + ".body_unavailable");
        }
        if (!DimensionRef.OVERWORLD.equals(frame.dimension())
                && !DimensionRef.NETHER.equals(frame.dimension())) {
            return fail(context, NAME + ".dimension_changed");
        }
        if (enderRouteUnits(frame)
                >= CompletionResourceReadiness.ENDER_ROUTE_UNITS) {
            cancelChildren(context);
            core.stop();
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (attemptedTargets >= MAXIMUM_TARGET_ATTEMPTS) {
            return fail(
                    context,
                    NAME + ".target_attempts_exhausted"
            );
        }
        final Optional<SkillFailure> ambient =
                ambientSafetyFailure(context, frame);
        if (ambient.isPresent()) {
            return fail(context, ambient.orElseThrow());
        }
        return switch (phase) {
            case BUILDING -> tickBuilder(context, frame);
            case SELECTING -> selectTarget(context, frame);
            case EQUIPPING_WEAPON ->
                    tickWeaponEquip(context, frame);
            case LURING -> tickLure(context, frame);
            case HUNTING -> tickHunter(context, frame);
            case COLLECTING_VISIBLE_PEARL ->
                    tickVisiblePearlCollection(context, frame);
            case RETURNING -> tickReturn(context, frame);
            case RETURN_REOBSERVING ->
                    tickReturnRecovery(context, frame);
            case EXPLORING -> tickExplorer(context, frame);
            default -> fail(context, NAME + ".invalid_state");
        };
    }

    private SkillTickResult tickBuilder(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<VisibleEntity> target =
                activeVisibleTarget(frame);
        if (target.isPresent()
                && target.orElseThrow().distance()
                    < MINIMUM_UNSHELTERED_TARGET_DISTANCE
                && !roofBuilder.revalidatingExistingRoof()) {
            return fail(
                    context,
                    NAME + ".enderman_too_close_to_build"
            );
        }
        final SkillTickResult result = roofBuilder.tick(
                context,
                NoParameters.INSTANCE
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            return fail(
                    context,
                    result.failure().orElseGet(() ->
                            SkillFailure.of(
                                    NAME + ".roof_build_failed"
                            )
                    )
            );
        }
        if (result.status()
                != SkillTickResult.Status.COMPLETED) {
            return SkillTickResult.running(
                    result.madeProgress(),
                    result.safeCheckpoint()
            );
        }
        shelterAnchor = roofBuilder.anchor();
        if (roofBuilder.placementsConfirmed() > 0) {
            sheltersBuilt++;
        }
        phase = Phase.SELECTING;
        phaseStartedAtTick = context.gameTick();
        resetScan(context, frame);
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult selectTarget(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (inShelterCell(frame) && !atShelter(frame)) {
            return beginReturn(
                    context,
                    frame,
                    recentDefeatForPrecisionRedock(
                            context,
                            frame
                    ).orElse(null)
            );
        }
        if (!shelterVerified(frame)) {
            return beginBuilder(context, frame);
        }
        shelterAnchor = frame.feet();
        final Optional<SkillTickResult> loosePearl =
                startVisiblePearlCollection(context, frame);
        if (loosePearl.isPresent()) {
            return loosePearl.orElseThrow();
        }
        final Optional<IndexedTarget> selected =
                selectVisibleTarget(frame);
        if (selected.isPresent()) {
            final IndexedTarget target = selected.orElseThrow();
            clearRecentDefeat();
            activeTargetId = target.entity().entityId();
            targetLostTicks = 0;
            if (target.entity().distance()
                        <= MAXIMUM_INITIAL_MELEE_DISTANCE
                    && interactionLineClear(target.entity())) {
                return beginWeaponEquip(context, frame);
            }
            phase = Phase.LURING;
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        return scan(context, frame);
    }

    private Optional<SkillTickResult> startVisiblePearlCollection(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<Integer> index =
                visiblePearlDropIndex(frame);
        if (index.isEmpty()) {
            return Optional.empty();
        }
        final Optional<InteractionSkillFrame> interaction =
                interactionFrames.current();
        if (interaction.isEmpty()
                || interaction.orElseThrow().observationRevision()
                    != frame.observationRevision()
                || index.orElseThrow()
                    >= interaction.orElseThrow()
                        .visibleEntities().size()
                || !frame.visibleEntities()
                    .get(index.orElseThrow())
                    .entityId().equals(
                        interaction.orElseThrow()
                            .visibleEntities()
                            .get(index.orElseThrow())
                            .entityId()
                    )) {
            core.stop();
            return Optional.of(
                    SkillTickResult.running(false, true)
            );
        }
        pearlCollectorParameters =
                new CollectObservedItemParameters(
                        frame.observationRevision(),
                        "visible-" + index.orElseThrow(),
                        600
                );
        pearlCollector = new CollectObservedItemSkill(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames
        );
        final Optional<SkillFailure> rejected =
                pearlCollector.preconditions(
                        context,
                        pearlCollectorParameters
                );
        if (rejected.isPresent()) {
            pearlCollector = null;
            pearlCollectorParameters = null;
            return Optional.of(fail(
                    context,
                    NAME + ".loose_pearl_collection_rejected."
                        + rejected.orElseThrow().code()
            ));
        }
        pearlCollector.start(
                context,
                pearlCollectorParameters
        );
        phase = Phase.COLLECTING_VISIBLE_PEARL;
        phaseStartedAtTick = context.gameTick();
        return Optional.of(SkillTickResult.running(true, true));
    }

    private SkillTickResult tickVisiblePearlCollection(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (pearlCollector == null
                || pearlCollectorParameters == null) {
            return fail(
                    context,
                    NAME + ".loose_pearl_collection_binding_missing"
            );
        }
        final SkillTickResult result = pearlCollector.tick(
                context,
                pearlCollectorParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            return fail(
                    context,
                    result.failure().orElseGet(() ->
                            SkillFailure.of(
                                    NAME
                                        + ".loose_pearl_collection_failed"
                            )
                    )
            );
        }
        if (result.status()
                != SkillTickResult.Status.COMPLETED) {
            return SkillTickResult.running(
                    result.madeProgress(),
                    result.safeCheckpoint()
            );
        }
        pearlCollector = null;
        pearlCollectorParameters = null;
        extraDropsCollected++;
        if (!atShelter(frame)) {
            return beginReturn(context, frame);
        }
        return resetSelection(context, frame);
    }

    private SkillTickResult beginWeaponEquip(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<String> weapon = selectWeapon(frame);
        if (weapon.isEmpty()) {
            return fail(context, NAME + ".melee_weapon_required");
        }
        pendingWeapon = weapon.orElseThrow();
        if (pendingWeapon.equals(frame.mainHand().itemId())) {
            return startHunter(context, frame);
        }
        final ActionOutcome equipped =
                interactions.equipMainHand(pendingWeapon);
        if (!equipped.accepted()) {
            return fail(context, NAME + ".weapon_equip_rejected");
        }
        phase = Phase.EQUIPPING_WEAPON;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickWeaponEquip(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (pendingWeapon != null
                && pendingWeapon.equals(
                        frame.mainHand().itemId()
                )) {
            return startHunter(context, frame);
        }
        if (context.gameTick() - phaseStartedAtTick >= 60) {
            return fail(context, NAME + ".weapon_equip_unconfirmed");
        }
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult startHunter(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!shelterVerified(frame) || activeTargetId == null) {
            return resetSelection(context, frame);
        }
        final Optional<IndexedTarget> selected =
                indexedTarget(frame, activeTargetId);
        if (selected.isEmpty()
                || selected.orElseThrow().entity().distance()
                    > MAXIMUM_INITIAL_MELEE_DISTANCE
                || !interactionLineClear(
                        selected.orElseThrow().entity()
                )) {
            phase = Phase.LURING;
            phaseStartedAtTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        final IndexedTarget target = selected.orElseThrow();
        final AcquireShelteredEnderPearlParameters parameters =
                new AcquireShelteredEnderPearlParameters(
                        frame.observationRevision(),
                        "visible-" + target.index(),
                        CHILD_MAXIMUM_TICKS
                );
        final Optional<SkillFailure> rejected =
                hunter.preconditions(context, parameters);
        if (rejected.isPresent()) {
            return fail(context, rejected.orElseThrow());
        }
        hunter.start(context, parameters);
        huntParameters = parameters;
        attemptedTargets++;
        phase = Phase.HUNTING;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickLure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!shelterVerified(frame)) {
            return beginBuilder(context, frame);
        }
        final Optional<VisibleEntity> target =
                activeVisibleTarget(frame);
        if (target.isEmpty()) {
            targetLostTicks++;
            if (targetLostTicks >= MAXIMUM_TARGET_LOST_TICKS) {
                activeTargetId = null;
                return resetSelection(context, frame);
            }
            return SkillTickResult.running(false, true);
        }
        targetLostTicks = 0;
        final VisibleEntity visible = target.orElseThrow();
        if (visible.distance() <= MAXIMUM_INITIAL_MELEE_DISTANCE
                && interactionLineClear(visible)) {
            return beginWeaponEquip(context, frame);
        }
        if (context.gameTick() - phaseStartedAtTick
                >= MAXIMUM_LURE_TICKS) {
            exhaustedTargets.add(visible.entityId());
            activeTargetId = null;
            return resetSelection(context, frame);
        }
        final PerceptionVec3 eye = new PerceptionVec3(
                visible.position().x(),
                visible.position().y() + 2.55,
                visible.position().z()
        );
        if (!core.stop().accepted()
                || !lookAt(frame, eye).accepted()) {
            return fail(context, NAME + ".lure_look_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickHunter(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (activeTargetId == null || huntParameters == null) {
            return fail(context, NAME + ".hunt_binding_missing");
        }
        final SkillTickResult result = hunter.tick(
                context,
                huntParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            final UUID defeatedTargetId = activeTargetId;
            exhaustedTargets.add(defeatedTargetId);
            rememberRecentDefeat(
                    context,
                    frame,
                    defeatedTargetId
            );
            clearHunterBinding();
            if (!atShelter(frame)) {
                return beginReturn(
                        context,
                        frame,
                        defeatedTargetId
                );
            }
            /*
             * Combat and the vanilla pickup transaction can legitimately
             * leave the camera pointed at the drop site for one semantic
             * sample.  That makes the previously cached roof proof
             * temporarily unavailable even though the body is still in the
             * same shelter cell.  Do not start the precision return mover in
             * that state: its residual-risk authorization deliberately
             * requires a fresh roof proof and would reject with
             * move_to.hardcore_danger.  Stay in the current cell and let the
             * normal selection path re-observe or revalidate the shelter
             * through the ordinary placement skill.
             */
            // A fresh combat/pickup sample may temporarily omit the roof
            // proof.  Since the body is already inside the shelter, reset
            // selection and re-observe instead of starting a precision
            // return mover that would be rejected by the hardcore gate.
            return resetSelection(context, frame);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            final SkillFailure childFailure = result.failure()
                    .orElseGet(() -> SkillFailure.of(
                            NAME + ".hunt_failed"
                    ));
            if (AcquireShelteredEnderPearlSkill.isNoDropFailure(
                    childFailure
            )) {
                exhaustedTargets.add(activeTargetId);
                noDropAttempts++;
                cancelHunter(context);
                return resetSelection(context, frame);
            }
            return fail(context, childFailure);
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult beginReturn(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        return beginReturn(context, frame, null);
    }

    private SkillTickResult beginReturn(
            final SkillContext context,
            final CoreSkillFrame frame,
            final UUID justDefeatedTargetId
    ) {
        if (shelterAnchor == null) {
            return fail(context, NAME + ".shelter_anchor_missing");
        }
        final GridPos authorizedAnchor = shelterAnchor;
        final long authorizedObservationRevision =
                frame.observationRevision();
        if (justDefeatedTargetId != null) {
            returnJustDefeatedTargetId = justDefeatedTargetId;
        }
        returnParameters = shelterReturnParameters(
                frame.dimension(),
                shelterAnchor
        );
        returnMovement = new MoveToSkill(
                expectedPlayerId,
                core,
                coreFrames,
                (childContext, childFrame, childParameters) ->
                        authorizesShelteredReturnResidualRisk(
                                childContext,
                                childFrame,
                                childParameters,
                                authorizedAnchor,
                                justDefeatedTargetId,
                                authorizedObservationRevision
                        )
        );
        final Optional<SkillFailure> rejected =
                returnMovement.preconditions(
                        context,
                        returnParameters
                );
        if (rejected.isPresent()) {
            if (justDefeatedTargetId != null) {
                final CoreSkillFrame rejectedFrame =
                        coreFrames.current().orElse(frame);
                MinecraftAiCompanion.LOGGER.warn(
                        "Sheltered return authorization rejected: "
                            + "boundTarget={} boundRevision={} "
                            + "currentRevision={} anchor={} feet={} "
                            + "goal={} hardcore={} contextRisk={} "
                            + "frameDanger={} roof={} health={}/{} "
                            + "food={} visibleHostiles={} signals={} "
                            + "directAuthorization={}",
                        justDefeatedTargetId,
                        authorizedObservationRevision,
                        rejectedFrame.observationRevision(),
                        authorizedAnchor,
                        rejectedFrame.feet(),
                        returnParameters.gridGoal(),
                        context.hardcore(),
                        context.riskScore(),
                        rejectedFrame.danger(),
                        shelterVerified(rejectedFrame),
                        rejectedFrame.health(),
                        rejectedFrame.maxHealth(),
                        rejectedFrame.foodLevel(),
                        rejectedFrame.visibleEntities().stream()
                            .filter(VisibleEntity::hostile)
                            .map(entity ->
                                    entity.entityId()
                                        + "/"
                                        + entity.entityTypeId()
                            )
                            .toList(),
                        rejectedFrame.dangerSignals(),
                        authorizesShelteredReturnResidualRisk(
                                context,
                                rejectedFrame,
                                returnParameters,
                                authorizedAnchor,
                                justDefeatedTargetId,
                                authorizedObservationRevision
                        )
                );
            }
            return fail(context, rejected.orElseThrow());
        }
        returnMovement.start(context, returnParameters);
        phase = Phase.RETURNING;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickReturn(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (returnMovement == null || returnParameters == null) {
            return fail(
                    context,
                    NAME + ".return_binding_missing"
            );
        }
        final SkillTickResult result = returnMovement.tick(
                context,
                returnParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            final SkillFailure reason = result.failure().orElseGet(() ->
                    SkillFailure.of(NAME + ".return_failed")
            );
            if (recoverableReturnMoveFailure(reason.code())
                    && returnRecoveryAttempts
                        < MAXIMUM_RETURN_RECOVERY_ATTEMPTS) {
                return beginReturnRecovery(context, frame, reason);
            }
            return fail(context, reason);
        }
        if (result.status()
                != SkillTickResult.Status.COMPLETED) {
            return SkillTickResult.running(
                    result.madeProgress(),
                    result.safeCheckpoint()
            );
        }
        returnMovement = null;
        returnParameters = null;
        returnsCompleted++;
        returnRecoveryAttempts = 0;
        returnRecoveryObservationRevision = -1L;
        returnRecoveryStartedAtTick = -1L;
        returnJustDefeatedTargetId = null;
        clearRecentDefeat();
        if (!atShelter(frame)) {
            return fail(context, NAME + ".return_not_confirmed");
        }
        if (!shelterVerified(frame)) {
            return beginBuilder(context, frame);
        }
        return resetSelection(context, frame);
    }

    private SkillTickResult beginReturnRecovery(
            final SkillContext context,
            final CoreSkillFrame frame,
            final SkillFailure reason
    ) {
        if (shelterAnchor == null) {
            return fail(context, NAME + ".shelter_anchor_missing");
        }
        cancelReturn(context);
        returnRecoveryAttempts++;
        returnRecoveryObservationRevision = frame.observationRevision();
        returnRecoveryStartedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        phase = Phase.RETURN_REOBSERVING;
        MinecraftAiCompanion.LOGGER.warn(
                "Recovering sheltered return attempt={}/{} reason={} "
                    + "feet={} anchor={} observationRevision={}",
                returnRecoveryAttempts,
                MAXIMUM_RETURN_RECOVERY_ATTEMPTS,
                reason.code(),
                frame.feet(),
                shelterAnchor,
                frame.observationRevision()
        );
        if (!core.stop().accepted()
                || !lookAt(
                        frame,
                        new PerceptionVec3(
                                shelterAnchor.x() + 0.5,
                                frame.eyePosition().y(),
                                shelterAnchor.z() + 0.5
                        )
                ).accepted()) {
            return fail(context, NAME + ".return_recovery_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickReturnRecovery(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (shelterAnchor == null
                || (returnParameters != null
                    && !frame.dimension().equals(
                        returnParameters.dimension()
                    ))) {
            return fail(
                    context,
                    NAME + ".return_recovery_binding_missing"
            );
        }
        final boolean hasFreshObservation =
                frame.observationRevision()
                    > returnRecoveryObservationRevision;
        final boolean waitExpired =
                context.gameTick() - returnRecoveryStartedAtTick
                    >= MAXIMUM_RETURN_REOBSERVATION_TICKS;
        if (!hasFreshObservation && !waitExpired) {
            if (!core.stop().accepted()
                    || !lookAt(
                            frame,
                            new PerceptionVec3(
                                    shelterAnchor.x() + 0.5,
                                    frame.eyePosition().y(),
                                    shelterAnchor.z() + 0.5
                            )
                    ).accepted()) {
                return fail(
                        context,
                        NAME + ".return_recovery_rejected"
                );
            }
            return SkillTickResult.running(false, true);
        }
        return beginReturn(
                context,
                frame,
                returnJustDefeatedTargetId
        );
    }

    static boolean recoverableReturnMoveFailure(final String code) {
        return "move_to.route_unknown".equals(code)
                || "move_to.stuck".equals(code)
                || "move_to.turn_stuck".equals(code);
    }

    private SkillTickResult scan(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (scanTurns >= MAXIMUM_SCAN_TURNS) {
            return beginExploration(context, frame);
        }
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(false, true);
        }
        final int yawIndex = scanTurns / PITCHES.length;
        final int pitchIndex = scanTurns % PITCHES.length;
        final float scanYaw = normalizeDegrees(scanBaseYaw
                + YAW_OFFSETS[
                        yawIndex % YAW_OFFSETS.length
                ]);
        final float scanPitch = PITCHES[pitchIndex];
        if (!core.stop().accepted()
                || !core.look(new LookIntent(
                        scanYaw,
                        scanPitch
                )).accepted()) {
            return fail(context, NAME + ".scan_rejected");
        }
        if (!aligned(frame, scanYaw, scanPitch)) {
            /*
             * The actuator runs at 20 TPS while semantic eye frames are
             * deliberately sampled at a lower cadence.  Count alignment
             * evidence only once per fresh observation revision; repeatedly
             * seeing the same frame must not consume the bounded wait while
             * the vanilla look input is still being applied.
             */
            if (frame.observationRevision()
                    != lastScanAlignmentObservationRevision) {
                scanAlignmentTicks++;
                lastScanAlignmentObservationRevision =
                        frame.observationRevision();
            }
            if (scanAlignmentTicks
                    > MAXIMUM_SCAN_ALIGNMENT_TICKS) {
                return fail(
                        context,
                        NAME + ".scan_alignment_timed_out"
                );
            }
            return SkillTickResult.running(false, false);
        }
        scanTurns++;
        scanAlignmentTicks = 0;
        lastScanAlignmentObservationRevision = -1L;
        nextScanTick =
                context.gameTick() + SCAN_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult beginExploration(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        clearRecentDefeat();
        cancelExplorer(context);
        explorationParameters = new ExploreForTargetParameters(
                frame.dimension(),
                SearchTargetKind.ENTITY,
                ENDERMAN,
                EXPLORATION_RADIUS,
                EXPLORATION_STEP
        );
        explorer = new ExploreForObservedTargetSkill(
                expectedPlayerId,
                core,
                coreFrames,
                () -> interactions.sessionGeneration()
                        .orElse(-1L),
                (candidate, ignored) ->
                        selectVisibleTarget(candidate).isPresent()
        );
        final Optional<SkillFailure> rejected =
                explorer.preconditions(
                        context,
                        explorationParameters
                );
        if (rejected.isPresent()) {
            cancelExplorer(context);
            return fail(context, rejected.orElseThrow());
        }
        shelterAnchor = null;
        explorer.start(context, explorationParameters);
        phase = Phase.EXPLORING;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickExplorer(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (explorer == null || explorationParameters == null) {
            return fail(
                    context,
                    NAME + ".exploration_binding_missing"
            );
        }
        final SkillTickResult result = explorer.tick(
                context,
                explorationParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            cancelExplorer(context);
            final Optional<IndexedTarget> target =
                    selectVisibleTarget(frame);
            if (target.isEmpty()) {
                return resetSelection(context, frame);
            }
            activeTargetId =
                    target.orElseThrow().entity().entityId();
            return beginBuilder(context, frame);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            return fail(
                    context,
                    result.failure().orElseGet(() ->
                            SkillFailure.of(
                                    NAME + ".enderman_not_found"
                            )
                    )
            );
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult beginBuilder(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        clearRecentDefeat();
        cancelExplorer(context);
        cancelReturn(context);
        final boolean revalidatingKnownShelter =
                shelterAnchor != null && atShelter(frame);
        if (!revalidatingKnownShelter) {
            shelterAnchor = null;
        }
        final Optional<SkillFailure> rejected =
                roofBuilder.preconditions(
                        context,
                        NoParameters.INSTANCE
                );
        if (rejected.isPresent()) {
            return fail(context, rejected.orElseThrow());
        }
        final Optional<VisibleEntity> target =
                activeVisibleTarget(frame);
        if (target.isPresent()
                && target.orElseThrow().distance()
                    < MINIMUM_UNSHELTERED_TARGET_DISTANCE
                && !revalidatingKnownShelter) {
            return fail(
                    context,
                    NAME + ".enderman_too_close_to_build"
            );
        }
        roofBuilder.start(context, NoParameters.INSTANCE);
        phase = Phase.BUILDING;
        phaseStartedAtTick = context.gameTick();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult resetSelection(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        cancelExplorer(context);
        cancelReturn(context);
        phase = Phase.SELECTING;
        phaseStartedAtTick = context.gameTick();
        activeTargetId = null;
        pendingWeapon = null;
        targetLostTicks = 0;
        resetScan(context, frame);
        return SkillTickResult.running(true, true);
    }

    private void resetScan(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        scanBaseYaw = yaw(frame);
        scanTurns = 0;
        scanAlignmentTicks = 0;
        lastScanAlignmentObservationRevision = -1L;
        nextScanTick = context.gameTick();
    }

    private Optional<IndexedTarget> selectVisibleTarget(
            final CoreSkillFrame frame
    ) {
        return java.util.stream.IntStream.range(
                        0,
                        frame.visibleEntities().size()
                )
                .mapToObj(index -> new IndexedTarget(
                        index,
                        frame.visibleEntities().get(index)
                ))
                .filter(target ->
                        isEnderman(target.entity())
                                && !exhaustedTargets.contains(
                                    target.entity().entityId()
                                ))
                .min(Comparator.comparingDouble(
                        target -> target.entity().distance()
                ));
    }

    private static Optional<Integer> visiblePearlDropIndex(
            final CoreSkillFrame frame
    ) {
        return java.util.stream.IntStream.range(
                        0,
                        frame.visibleEntities().size()
                )
                .filter(index -> {
                    final VisibleEntity entity =
                            frame.visibleEntities().get(index);
                    return ITEM_ENTITY.equals(entity.entityTypeId())
                            && ENDER_PEARL.equals(
                                entity.visibleProperties()
                                    .get("itemId")
                            );
                })
                .boxed()
                .min(Comparator.comparingDouble(index ->
                        frame.visibleEntities()
                            .get(index)
                            .distance()
                ));
    }

    private Optional<IndexedTarget> indexedTarget(
            final CoreSkillFrame frame,
            final UUID id
    ) {
        return java.util.stream.IntStream.range(
                        0,
                        frame.visibleEntities().size()
                )
                .mapToObj(index -> new IndexedTarget(
                        index,
                        frame.visibleEntities().get(index)
                ))
                .filter(target ->
                        id.equals(target.entity().entityId())
                                && isEnderman(target.entity()))
                .findFirst();
    }

    private Optional<VisibleEntity> activeVisibleTarget(
            final CoreSkillFrame frame
    ) {
        if (activeTargetId == null) {
            return Optional.empty();
        }
        return frame.visibleEntities().stream()
                .filter(entity ->
                        activeTargetId.equals(entity.entityId())
                                && isEnderman(entity))
                .findFirst();
    }

    private Optional<SkillFailure> ambientSafetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double minimumHealth = context.hardcore()
                ? HARDCORE_MINIMUM_HEALTH
                : NORMAL_MINIMUM_HEALTH;
        if (frame.health() / frame.maxHealth() < minimumHealth) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_reserve_required"
            ));
        }
        if (frame.foodLevel() < MINIMUM_FOOD) {
            return Optional.of(SkillFailure.of(
                    NAME + ".food_reserve_required"
            ));
        }
        if (frame.visibleEntities().stream()
                .anyMatch(VisibleEntity::projectile)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".projectile_danger"
            ));
        }
        if (frame.visibleEntities().stream()
                .filter(VisibleEntity::hostile)
                .anyMatch(entity -> !isEnderman(entity))) {
            return Optional.of(SkillFailure.of(
                    NAME + ".other_hostile_visible"
            ));
        }
        if (phase == Phase.BUILDING
                && frame.visibleEntities().stream()
                    .filter(SecureEnderPearlReserveSkill::isEnderman)
                    .anyMatch(entity ->
                            activeTargetId == null
                                    || !activeTargetId.equals(
                                        entity.entityId()
                                    ))) {
            return Optional.of(SkillFailure.of(
                    NAME + ".unexpected_enderman_while_building"
            ));
        }
        if (phase != Phase.HUNTING) {
            for (DangerSignal signal : frame.dangerSignals()) {
                final boolean controlledBuilderLanding =
                        phase == Phase.BUILDING
                            && roofBuilder
                                .managesControlledPlacementLanding(
                                    frame,
                                    signal
                                );
                if (signal.kind() == DangerKind.THREAT_CONTACT
                        || signal.kind()
                            == DangerKind.PROJECTILE_PROXIMITY
                        || signal.provenance()
                            == PerceptionProvenance.BODY_HAZARD
                            && !controlledBuilderLanding) {
                    return Optional.of(SkillFailure.of(
                            NAME + ".body_hazard"
                    ));
                }
            }
        }
        return Optional.empty();
    }

    private static boolean unsafeTransitionRisk(
            final CoreSkillFrame frame,
            final SkillContext context
    ) {
        return frame.health() / frame.maxHealth()
                    < HARDCORE_MINIMUM_HEALTH
                || frame.foodLevel() < MINIMUM_FOOD
                || context.riskScore() > 1.0
                || frame.visibleEntities().stream()
                    .anyMatch(VisibleEntity::projectile)
                || frame.dangerSignals().stream().anyMatch(signal ->
                        signal.kind()
                                == DangerKind.THREAT_CONTACT
                                || signal.kind()
                                    == DangerKind
                                        .PROJECTILE_PROXIMITY
                                || signal.provenance()
                                    == PerceptionProvenance
                                        .BODY_HAZARD
                );
    }

    private static boolean onlyEndermenVisible(
            final CoreSkillFrame frame
    ) {
        return frame.visibleEntities().stream()
                .filter(VisibleEntity::hostile)
                .allMatch(
                        SecureEnderPearlReserveSkill::isEnderman
                );
    }

    private static boolean isEnderman(
            final VisibleEntity entity
    ) {
        return ENDERMAN.equals(entity.entityTypeId())
                && entity.hostile()
                && !entity.projectile();
    }

    private static boolean interactionLineClear(
            final VisibleEntity entity
    ) {
        return Boolean.parseBoolean(
                entity.visibleProperties().getOrDefault(
                        "interactionLineClear",
                        "false"
                )
        );
    }

    private static Optional<String> selectWeapon(
            final CoreSkillFrame frame
    ) {
        for (String weapon : WEAPON_PREFERENCE) {
            if (inventoryCount(frame, weapon) > 0) {
                return Optional.of(weapon);
            }
        }
        return Optional.empty();
    }

    private static boolean shelterVerified(
            final CoreSkillFrame frame
    ) {
        return AcquireShelteredEnderPearlSkill
                .hasObservedTwoBlockShelter(frame);
    }

    /**
     * Exposes the exact fair-evidence predicate used by this compound for
     * integration gates and read-only diagnostics. It does not inspect the
     * level: callers receive only the body's current semantic/navigation
     * frame, with the same freshness and support requirements as production.
     */
    public static boolean hasObservedSafetyRoof(
            final CoreSkillFrame frame
    ) {
        return shelterVerified(
                Objects.requireNonNull(frame, "frame")
        );
    }

    private boolean atShelter(final CoreSkillFrame frame) {
        return shelterAnchor != null
                && shelterAnchor.equals(frame.feet())
                && Math.hypot(
                    frame.position().x()
                        - (shelterAnchor.x() + 0.5),
                    frame.position().z()
                        - (shelterAnchor.z() + 0.5)
                ) <= SHELTER_RETURN_RADIUS + 1.0E-6;
    }

    static MoveToParameters shelterReturnParameters(
            final DimensionRef dimension,
            final GridPos anchor
    ) {
        Objects.requireNonNull(anchor, "anchor");
        return new MoveToParameters(
                Objects.requireNonNull(dimension, "dimension"),
                anchor.x() + 0.5,
                anchor.y(),
                anchor.z() + 0.5,
                SHELTER_RETURN_RADIUS
        );
    }

    /**
     * Authorizes one cached proximity-only sample while precision-docking
     * inside the already verified Enderman roof after the bound target's
     * ordinary combat child completed.
     *
     * <p>The authorization is tied to the exact semantic revision, target
     * UUID, shelter cell, and return goal. It cannot authorize contact,
     * projectiles, body hazards, another hostile, an unattributed aggregate
     * score, or any route voxel. {@link MoveToSkill} continues to validate
     * every observed route step independently.</p>
     */
    static boolean authorizesShelteredReturnResidualRisk(
            final SkillContext context,
            final CoreSkillFrame frame,
            final MoveToParameters parameters,
            final GridPos authorizedAnchor,
            final UUID justDefeatedTargetId,
            final long authorizedObservationRevision
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(parameters, "parameters");
        if (!context.hardcore()
                || authorizedAnchor == null
                || justDefeatedTargetId == null
                || authorizedObservationRevision < 0L
                || frame.observationRevision()
                    != authorizedObservationRevision
                || !frame.dimension().equals(parameters.dimension())
                || !authorizedAnchor.equals(parameters.gridGoal())
                || !authorizedAnchor.equals(frame.feet())
                || !frame.onGround()
                || frame.inWater()
                || !shelterVerified(frame)
                || frame.health() / frame.maxHealth()
                    < HARDCORE_MINIMUM_HEALTH
                || frame.foodLevel() < MINIMUM_FOOD) {
            return false;
        }
        if (frame.visibleEntities().stream().anyMatch(entity ->
                entity.projectile()
                        || entity.hostile()
                            && (!isEnderman(entity)
                                || !justDefeatedTargetId.equals(
                                        entity.entityId()
                                )))) {
            return false;
        }
        if (frame.dangerSignals().isEmpty()) {
            return false;
        }
        double attributedMaximum = 0.0;
        for (DangerSignal signal : frame.dangerSignals()) {
            if (signal.kind() != DangerKind.HOSTILE_PROXIMITY
                    || signal.provenance()
                        != PerceptionProvenance.PROXIMITY_THREAT) {
                return false;
            }
            attributedMaximum = Math.max(
                    attributedMaximum,
                    signal.severity()
            );
        }
        return attributedMaximum > 0.0
                && Math.abs(frame.danger() - attributedMaximum)
                    <= RISK_EPSILON
                && context.riskScore()
                    <= attributedMaximum + RISK_EPSILON;
    }

    private void rememberRecentDefeat(
            final SkillContext context,
            final CoreSkillFrame frame,
            final UUID defeatedTargetId
    ) {
        recentlyDefeatedTargetId = Objects.requireNonNull(
                defeatedTargetId,
                "defeatedTargetId"
        );
        recentlyDefeatedAtObservationRevision =
                frame.observationRevision();
        recentlyDefeatedAtTick = context.gameTick();
    }

    private Optional<UUID> recentDefeatForPrecisionRedock(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        return recentDefeatFreshForPrecisionRedock(
                context,
                frame,
                shelterAnchor,
                recentlyDefeatedTargetId,
                recentlyDefeatedAtObservationRevision,
                recentlyDefeatedAtTick
        )
                ? Optional.of(recentlyDefeatedTargetId)
                : Optional.empty();
    }

    /**
     * Retains one combat binding just long enough to correct ordinary
     * post-combat drift across the 0.25-block shelter-centre boundary.
     *
     * <p>This does not itself authorize danger. It only decides whether the
     * exact defeated UUID may be offered to the stricter, one-revision
     * {@link #authorizesShelteredReturnResidualRisk} predicate.</p>
     */
    static boolean recentDefeatFreshForPrecisionRedock(
            final SkillContext context,
            final CoreSkillFrame frame,
            final GridPos authorizedAnchor,
            final UUID defeatedTargetId,
            final long defeatedAtObservationRevision,
            final long defeatedAtTick
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(frame, "frame");
        if (authorizedAnchor == null
                || defeatedTargetId == null
                || defeatedAtObservationRevision < 0L
                || defeatedAtTick < 0L
                || frame.observationRevision()
                    < defeatedAtObservationRevision
                || frame.observationRevision()
                    - defeatedAtObservationRevision
                        > MAXIMUM_RECENT_DEFEAT_REVISION_AGE
                || context.gameTick() < defeatedAtTick
                || context.gameTick() - defeatedAtTick
                    > MAXIMUM_RECENT_DEFEAT_TICK_AGE
                || !authorizedAnchor.equals(frame.feet())
                || !frame.onGround()
                || frame.inWater()
                || !shelterVerified(frame)) {
            return false;
        }
        return frame.visibleEntities().stream().noneMatch(entity ->
                entity.projectile()
                        || entity.hostile()
                            && (!isEnderman(entity)
                                || !defeatedTargetId.equals(
                                        entity.entityId()
                                )));
    }

    private void clearRecentDefeat() {
        recentlyDefeatedTargetId = null;
        recentlyDefeatedAtObservationRevision = -1L;
        recentlyDefeatedAtTick = -1L;
    }

    private boolean inShelterCell(final CoreSkillFrame frame) {
        return shelterAnchor != null
                && shelterAnchor.equals(frame.feet());
    }

    private static int enderRouteUnits(
            final CoreSkillFrame frame
    ) {
        return CompletionResourceReadiness.enderRouteUnits(
                inventoryCount(frame, ENDER_PEARL),
                inventoryCount(frame, ENDER_EYE)
        );
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

    private ActionOutcome lookAt(
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        final double dx = target.x() - frame.eyePosition().x();
        final double dy = target.y() - frame.eyePosition().y();
        final double dz = target.z() - frame.eyePosition().z();
        final float yaw = normalizeDegrees(
                (float) Math.toDegrees(Math.atan2(-dx, dz))
        );
        final float pitch = (float) -Math.toDegrees(
                Math.atan2(dy, Math.hypot(dx, dz))
        );
        return core.look(new LookIntent(yaw, pitch));
    }

    private static boolean aligned(
            final CoreSkillFrame frame,
            final float targetYaw,
            final float targetPitch
    ) {
        return Math.abs(normalizeDegrees(
                    yaw(frame) - targetYaw
                )) <= SCAN_ALIGNMENT_TOLERANCE_DEGREES
                && Math.abs(pitch(frame) - targetPitch)
                    <= SCAN_ALIGNMENT_TOLERANCE_DEGREES;
    }

    private static float yaw(final CoreSkillFrame frame) {
        return normalizeDegrees((float) Math.toDegrees(Math.atan2(
                -frame.lookDirection().x(),
                frame.lookDirection().z()
        )));
    }

    private static float pitch(final CoreSkillFrame frame) {
        return (float) -Math.toDegrees(Math.atan2(
                frame.lookDirection().y(),
                Math.hypot(
                        frame.lookDirection().x(),
                        frame.lookDirection().z()
                )
        ));
    }

    private static float normalizeDegrees(float value) {
        value %= 360.0F;
        if (value >= 180.0F) {
            value -= 360.0F;
        }
        if (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }

    private void cancelHunter(final SkillContext context) {
        if (huntParameters != null) {
            hunter.cancel(context, huntParameters);
        }
        clearHunterBinding();
    }

    private void clearHunterBinding() {
        activeTargetId = null;
        huntParameters = null;
        pendingWeapon = null;
    }

    private void cancelExplorer(final SkillContext context) {
        if (explorer != null && explorationParameters != null) {
            explorer.cancel(context, explorationParameters);
        }
        explorer = null;
        explorationParameters = null;
    }

    private void cancelReturn(final SkillContext context) {
        if (returnMovement != null && returnParameters != null) {
            returnMovement.cancel(context, returnParameters);
        }
        returnMovement = null;
        returnParameters = null;
    }

    private void cancelPearlCollector(
            final SkillContext context
    ) {
        if (pearlCollector != null
                && pearlCollectorParameters != null) {
            pearlCollector.cancel(
                    context,
                    pearlCollectorParameters
            );
        }
        pearlCollector = null;
        pearlCollectorParameters = null;
    }

    private void cancelChildren(final SkillContext context) {
        if (phase == Phase.BUILDING) {
            roofBuilder.cancel(context, NoParameters.INSTANCE);
        }
        cancelHunter(context);
        cancelPearlCollector(context);
        cancelExplorer(context);
        cancelReturn(context);
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
        core.stop();
        failure = Objects.requireNonNull(reason, "reason");
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    private Optional<CoreSkillFrame> ownedFrame() {
        return coreFrames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
        );
    }

    /**
     * Owns only the first safe selection tick for an Enderman that is
     * already visible from a verified roof.
     *
     * <p>Without this bridge, the higher-priority emergency lane can claim
     * HOSTILE_PROXIMITY forever while {@code activeTargetId} is still null,
     * preventing the supervisor tick that would bind and lure the target.
     * Contact remains emergency-owned until the normal hunting child has
     * started; an unsheltered target never enters this exception.</p>
     */
    private boolean selectingShelteredEnderman() {
        return phase == Phase.SELECTING
                && ownedFrame().filter(frame ->
                        shelterVerified(frame)
                            && onlyEndermenVisible(frame)
                            && selectVisibleTarget(frame).isPresent()
                ).isPresent();
    }

    private record IndexedTarget(
            int index,
            VisibleEntity entity
    ) {
    }

    private enum Phase {
        IDLE(false),
        BUILDING(true),
        SELECTING(true),
        EQUIPPING_WEAPON(true),
        LURING(true),
        HUNTING(true),
        COLLECTING_VISIBLE_PEARL(true),
        RETURNING(true),
        RETURN_REOBSERVING(true),
        EXPLORING(true),
        COMPLETED(false),
        CANCELLED(false),
        FAILED(false);

        private final boolean active;

        Phase(final boolean active) {
            this.active = active;
        }

        boolean active() {
            return active;
        }
    }
}
