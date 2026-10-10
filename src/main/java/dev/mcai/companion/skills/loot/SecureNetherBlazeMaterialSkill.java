package dev.mcai.companion.skills.loot;

import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.perception.InventoryItemSummary;
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
import dev.mcai.companion.skills.combat.CombatHardcoreRisk;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.exploration.ExploreForObservedTargetSkill;
import dev.mcai.companion.skills.exploration.ExploreForTargetParameters;
import dev.mcai.companion.skills.exploration.SearchTargetKind;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

/**
 * Acquires the completion route's Blaze reserve with one high-level model
 * decision.
 *
 * <p>Discovery remains a bounded square spiral made from ordinary observed
 * travel. Every combat child binds a current first-person-visible Blaze and
 * every successful attempt confirms a vanilla item pickup in this player's
 * inventory. A legitimate no-drop roll is retried locally against another
 * visible Blaze; combat, collection, route, health, or dimension failures are
 * never hidden. No structure, spawner, entity registry, or chunk query is
 * available to this controller.</p>
 */
public final class SecureNetherBlazeMaterialSkill
        implements Skill<NoParameters> {
    public static final String NAME =
            "secure_nether_blaze_material";

    private static final String BLAZE = "minecraft:blaze";
    private static final String BLAZE_ROD = "minecraft:blaze_rod";
    private static final String BLAZE_POWDER =
            "minecraft:blaze_powder";
    private static final String ENDER_EYE = "minecraft:ender_eye";
    private static final String VISIBLE_THREAT_INTERRUPTED_PICKUP =
            CollectObservedItemSkill.NAME + ".danger_detected";
    private static final int MAXIMUM_TICKS = 120_000;
    private static final int CHILD_MAXIMUM_TICKS = 1_200;
    private static final int MAXIMUM_SCAN_TURNS = 48;
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int MAXIMUM_SCAN_ALIGNMENT_TICKS = 40;
    private static final float SCAN_ALIGNMENT_TOLERANCE_DEGREES =
            2.0F;
    private static final int EXPLORATION_RADIUS = 256;
    private static final int EXPLORATION_STEP = 16;
    private static final int MAXIMUM_TARGET_ATTEMPTS = 64;
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
            -20.0F
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final AcquireNetherBlazeRodSkill hunter;
    private final Set<UUID> exhaustedTargets = new HashSet<>();

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1L;
    private long nextScanTick = -1L;
    private int scanTurns;
    private int scanAlignmentTicks;
    private float scanBaseYaw;
    private int attemptedTargets;
    private int noDropAttempts;
    private int hostilePickupInterruptions;
    private UUID activeTargetId;
    private AcquireNetherBlazeRodParameters huntParameters;
    private ExploreForObservedTargetSkill explorer;
    private ExploreForTargetParameters explorationParameters;

    public SecureNetherBlazeMaterialSkill(
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
        hunter = new AcquireNetherBlazeRodSkill(
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
        if (!phase.active()) {
            return false;
        }
        if (phase == Phase.HUNTING
                && hunter.managesVisibleHostileProximity()) {
            return true;
        }
        if (huntingOwnsDropTransition()) {
            return true;
        }
        if (selectionOwnsTransition()) {
            return true;
        }
        /*
         * Selection and bounded exploration deliberately own their
         * first-person scan.  When no hostile is in the current sample, the
         * emergency lane's remembered last-hostile sweep must not rotate the
         * camera forever and starve the next scan alignment tick.  A newly
         * visible hostile still returns false here and remains emergency
         * owned unless the selector can bind it on this exact frame.
         */
        if (phase == Phase.SELECTING || phase == Phase.EXPLORING) {
            return ownedFrame()
                    .map(frame -> frame.visibleEntities().stream()
                            .noneMatch(entity -> entity.hostile()
                                    || entity.projectile()))
                    .orElse(false);
        }
        return false;
    }

    @Override
    public boolean managesPhysicalContactThreats() {
        return phase == Phase.HUNTING
                && (hunter.managesPhysicalContactThreats()
                    || huntingOwnsDropTransition())
                || selectionOwnsTransition();
    }

    @Override
    public OptionalDouble hardcoreRiskThresholdOverride(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final Optional<CoreSkillFrame> frame = ownedFrame();
        if (frame.isEmpty()) {
            return OptionalDouble.empty();
        }
        if (phase.active()) {
            return CombatHardcoreRisk.threshold(
                    context,
                    frame.orElseThrow(),
                    1.0
            );
        }
        final Optional<IndexedTarget> visible =
                selectVisibleTarget(frame.orElseThrow());
        if (visible.isEmpty()) {
            return OptionalDouble.empty();
        }
        final IndexedTarget target = visible.orElseThrow();
        return hunter.hardcoreRiskThresholdOverride(
                context,
                new AcquireNetherBlazeRodParameters(
                        frame.orElseThrow().observationRevision(),
                        "visible-" + target.index(),
                        CHILD_MAXIMUM_TICKS
                )
        );
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
        if (!DimensionRef.NETHER.equals(current.dimension())) {
            return Optional.of(SkillFailure.of(
                    NAME + ".nether_required"
            ));
        }
        if (!current.onGround() || current.inWater()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stable_pose_required"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final CoreSkillFrame frame = ownedFrame().orElseThrow(
                () -> new IllegalStateException(
                        "Companion body changed before Blaze reserve"
                )
        );
        failure = null;
        exhaustedTargets.clear();
        activeTargetId = null;
        huntParameters = null;
        explorer = null;
        explorationParameters = null;
        startedAtTick = context.gameTick();
        nextScanTick = context.gameTick();
        scanTurns = 0;
        scanAlignmentTicks = 0;
        scanBaseYaw = yaw(frame);
        attemptedTargets = 0;
        noDropAttempts = 0;
        hostilePickupInterruptions = 0;
        phase = Phase.SELECTING;
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
            return fail(context, NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"blazeRouteUnits\":%d,"
                            + "\"attemptedTargets\":%d,"
                            + "\"noDropAttempts\":%d,"
                            + "\"hostilePickupInterruptions\":%d,"
                            + "\"exhaustedTargets\":%d,"
                            + "\"scanTurns\":%d}",
                        phase.name(),
                        ownedFrame().map(
                                SecureNetherBlazeMaterialSkill
                                    ::blazeRouteUnits
                        ).orElse(0),
                        attemptedTargets,
                        noDropAttempts,
                        hostilePickupInterruptions,
                        exhaustedTargets.size(),
                        scanTurns
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        cancelHunter(context);
        cancelExplorer(context);
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
        if (!DimensionRef.NETHER.equals(frame.dimension())) {
            return fail(context, NAME + ".dimension_changed");
        }
        if (blazeRouteUnits(frame)
                >= CompletionResourceReadiness.BLAZE_ROUTE_UNITS) {
            cancelHunter(context);
            cancelExplorer(context);
            core.stop();
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (attemptedTargets >= MAXIMUM_TARGET_ATTEMPTS) {
            return fail(context, NAME + ".target_attempts_exhausted");
        }
        return switch (phase) {
            case SELECTING -> selectTarget(context, frame);
            case HUNTING -> tickHunter(context, frame);
            case EXPLORING -> tickExplorer(context, frame);
            default -> fail(context, NAME + ".invalid_state");
        };
    }

    private SkillTickResult selectTarget(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<IndexedTarget> selected =
                selectVisibleTarget(frame);
        if (selected.isPresent()) {
            final IndexedTarget target = selected.orElseThrow();
            final AcquireNetherBlazeRodParameters parameters =
                    new AcquireNetherBlazeRodParameters(
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
            activeTargetId = target.entity().entityId();
            huntParameters = parameters;
            attemptedTargets++;
            phase = Phase.HUNTING;
            scanTurns = 0;
            return SkillTickResult.running(true, true);
        }
        return scan(context, frame);
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
            exhaustedTargets.add(activeTargetId);
            clearHunterBinding();
            resetSelection(context, frame);
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            final SkillFailure childFailure = result.failure()
                    .orElseGet(() -> SkillFailure.of(
                            NAME + ".hunt_failed"
                    ));
            if (AcquireNetherBlazeRodSkill.isNoDropFailure(
                    childFailure
            )) {
                exhaustedTargets.add(activeTargetId);
                noDropAttempts++;
                cancelHunter(context);
                resetSelection(context, frame);
                return SkillTickResult.running(true, true);
            }
            if (VISIBLE_THREAT_INTERRUPTED_PICKUP.equals(
                    childFailure.code()
            )) {
                exhaustedTargets.add(activeTargetId);
                /*
                 * Collection deliberately fails closed as soon as a nearby
                 * threat re-enters the first-person safety frame.  Requiring
                 * the Blaze to remain in the very same camera sample here
                 * used to turn a correct emergency abort into a terminal
                 * failure: the combat target can be just outside the next
                 * semantic cone.  Cancel the stale child and resume normal
                 * selection/scan; no hidden entity position is inferred.
                 */
                hostilePickupInterruptions++;
                cancelHunter(context);
                resetSelection(context, frame);
                return SkillTickResult.running(true, true);
            }
            return fail(context, childFailure);
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
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
                        !exhaustedTargets.contains(
                                target.entity().entityId()
                        ))
                .filter(target ->
                        BLAZE.equals(
                                target.entity().entityTypeId()
                        )
                                && target.entity().hostile()
                                && !target.entity().projectile())
                .min(Comparator.comparingDouble(
                        target -> target.entity().distance()
                ));
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
        final float yawError = Math.abs(normalizeDegrees(
                yaw(frame) - scanYaw
        ));
        final float pitchError = Math.abs(
                pitch(frame) - scanPitch
        );
        if (yawError > SCAN_ALIGNMENT_TOLERANCE_DEGREES
                || pitchError
                    > SCAN_ALIGNMENT_TOLERANCE_DEGREES) {
            scanAlignmentTicks++;
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
        nextScanTick =
                context.gameTick() + SCAN_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult beginExploration(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        cancelExplorer(context);
        explorationParameters = new ExploreForTargetParameters(
                DimensionRef.NETHER,
                SearchTargetKind.ENTITY,
                BLAZE,
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
        explorer.start(context, explorationParameters);
        phase = Phase.EXPLORING;
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
            resetSelection(context, frame);
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            return fail(
                    context,
                    result.failure().orElseGet(() ->
                            SkillFailure.of(
                                    NAME + ".blaze_not_found"
                            )
                    )
            );
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private void resetSelection(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        cancelExplorer(context);
        phase = Phase.SELECTING;
        scanBaseYaw = yaw(frame);
        scanTurns = 0;
        scanAlignmentTicks = 0;
        nextScanTick = context.gameTick();
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
    }

    private void cancelExplorer(final SkillContext context) {
        if (explorer != null && explorationParameters != null) {
            explorer.cancel(context, explorationParameters);
        }
        explorer = null;
        explorationParameters = null;
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
        cancelHunter(context);
        cancelExplorer(context);
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
     * Owns the single transition tick between the model-authorized reserve
     * macro and its observation-bound combat child.
     *
     * <p>Emergency survival has higher arbitration priority than skills. If
     * an already-visible Blaze is allowed to claim that lane while this
     * skill is in {@link Phase#SELECTING}, the supervisor can never execute
     * the first tick that binds the Blaze and enters {@link Phase#HUNTING}.
     * Claim when the current fair frame contains an eligible Blaze, or when
     * it is clear of hostile/projectile observations. The latter is the
     * bounded hand-off window after a controlled target dies and a new
     * target has not yet reached the next 20-TPS fair frame; it lets this
     * already-authorized selector bind the next visible target instead of
     * leaving the body in a stale emergency guard state.</p>
     */
    private boolean selectionOwnsTransition() {
        if (phase != Phase.SELECTING) {
            return false;
        }
        return ownedFrame()
                .map(frame -> {
                    final boolean projectileVisible = frame.visibleEntities()
                            .stream()
                            .anyMatch(entity -> entity.projectile());
                    if (projectileVisible) {
                        return false;
                    }
                    final boolean hostileVisible = frame.visibleEntities()
                            .stream()
                            .anyMatch(entity -> entity.hostile()
                                    && !exhaustedTargets.contains(
                                            entity.entityId()
                                    ));
                    return !hostileVisible
                            || selectVisibleTarget(frame).isPresent();
                })
                .orElse(false);
    }

    /**
     * A resource child may be between combat and pickup while the next
     * authorized Blaze has already entered the current fair frame. Let the
     * reserve macro finish its ordinary pickup transaction in that bounded
     * window; an in-range or projectile threat remains emergency-owned.
     */
    private boolean huntingOwnsDropTransition() {
        if (phase != Phase.HUNTING
                || !hunter.awaitingObservedDrop()) {
            return false;
        }
        return ownedFrame()
                .map(frame -> {
                    if (frame.visibleEntities().stream()
                            .anyMatch(entity -> entity.projectile())) {
                        return false;
                    }
                    final boolean unrelatedHostile = frame.visibleEntities()
                            .stream()
                            .anyMatch(entity -> entity.hostile()
                                    && !BLAZE.equals(
                                            entity.entityTypeId()
                                    ));
                    if (unrelatedHostile) {
                        return false;
                    }
                    return frame.visibleEntities().stream()
                            .anyMatch(entity -> BLAZE.equals(
                                    entity.entityTypeId()
                            ) && entity.hostile()
                                    && entity.distance() > 3.25D);
                })
                .orElse(false);
    }

    private static int blazeRouteUnits(
            final CoreSkillFrame frame
    ) {
        return CompletionResourceReadiness.blazeRouteUnits(
                inventoryCount(frame, BLAZE_ROD),
                inventoryCount(frame, BLAZE_POWDER),
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

    private record IndexedTarget(
            int index,
            VisibleEntity entity
    ) {
    }

    private enum Phase {
        IDLE(false),
        SELECTING(true),
        HUNTING(true),
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
