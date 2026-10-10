package dev.mcai.companion.skills.gathering;

import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
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
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.exploration.ExploreForObservedTargetSkill;
import dev.mcai.companion.skills.exploration.ExploreForTargetParameters;
import dev.mcai.companion.skills.exploration.SearchTargetKind;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillPolicy;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.skills.interaction.BreakBlockParameters;
import dev.mcai.companion.skills.interaction.BreakBlockSkill;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Finds, approaches, mines, and picks up nearby wood as one local action.
 *
 * <p>This is the latency boundary for ordinary teammate requests such as
 * "chop a tree". The model chooses the action once; first-person scanning,
 * bounded exploration, vanilla mining, and physical item pickup then continue
 * at server tick rate. It never scans a level or chunk directly.</p>
 */
public final class GatherNearbyWoodSkill implements Skill<NoParameters> {
    public static final String NAME = "gather_nearby_wood";

    private static final int MAXIMUM_TICKS = 6_000;
    private static final int MAXIMUM_SCAN_TURNS = 32;
    private static final int SCAN_INTERVAL_TICKS = 4;
    private static final int MAXIMUM_GATHER_FAILURES = 4;
    private static final int MAXIMUM_BLOCKS = 8;
    private static final int SEARCH_RADIUS = 48;
    private static final int SEARCH_STEP = 8;
    private static final int MAXIMUM_DESCENT_TICKS = 100;
    private static final int MAXIMUM_GATHER_NO_PROGRESS_TICKS = 400;
    private static final float[] SCAN_YAW_OFFSETS = {
            0.0F, -45.0F, 45.0F, -90.0F,
            90.0F, -135.0F, 135.0F, 180.0F
    };
    private static final float[] SCAN_PITCHES = {
            10.0F, 35.0F, 60.0F, 85.0F
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final GatherVisibleBlockClusterSkill gatherer;

    private final Set<GridPos> rejectedSeeds = new HashSet<>();
    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1L;
    private long nextScanTick = -1L;
    private int scanTurns;
    private float scanBaseYaw;
    private int initialWoodCount;
    private int lastProgressWoodCount;
    private long lastWoodProgressAtTick;
    private int gatherFailures;
    private GatherVisibleBlockClusterParameters gatherParameters;
    private BreakBlockSkill canopyBreaker;
    private BreakBlockParameters canopyBreakParameters;
    private double canopyStartY;
    private long canopyBreakCompletedAtTick;
    private ExploreForObservedTargetSkill explorer;
    private ExploreForTargetParameters exploreParameters;

    public GatherNearbyWoodSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final ResourceInventorySource inventory
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.core = Objects.requireNonNull(core, "core");
        this.coreFrames = Objects.requireNonNull(coreFrames, "coreFrames");
        this.interactions = Objects.requireNonNull(
                interactions,
                "interactions"
        );
        this.interactionFrames = Objects.requireNonNull(
                interactionFrames,
                "interactionFrames"
        );
        this.gatherer = new GatherVisibleBlockClusterSkill(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames,
                Objects.requireNonNull(inventory, "inventory"),
                GatheringSkillPolicy.defaults()
        );
    }

    @Override
    public SkillParameterParser<NoParameters> parameters() {
        return GatherNearbyWoodSkill::parseNone;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(parameters, "parameters");
        return ownedFrame()
                .map(frame -> safetyFailure(context, frame))
                .orElseGet(() -> Optional.of(SkillFailure.of(
                        NAME + ".body_unavailable"
                )));
    }

    @Override
    public void start(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final CoreSkillFrame frame = ownedFrame().orElseThrow(
                () -> new IllegalStateException(
                        "Companion body changed before wood gathering"
                )
        );
        phase = Phase.SCANNING;
        failure = null;
        startedAtTick = context.gameTick();
        nextScanTick = context.gameTick();
        scanTurns = 0;
        scanBaseYaw = yaw(frame.lookDirection());
        initialWoodCount = woodCount(frame);
        lastProgressWoodCount = initialWoodCount;
        lastWoodProgressAtTick = context.gameTick();
        gatherFailures = 0;
        gatherParameters = null;
        canopyBreaker = null;
        canopyBreakParameters = null;
        canopyStartY = Double.NaN;
        canopyBreakCompletedAtTick = -1L;
        explorer = null;
        exploreParameters = null;
        rejectedSeeds.clear();
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
        } catch (RuntimeException ignored) {
            return fail(context, NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        final String gatherCheckpoint = gatherParameters == null
                ? ""
                : escapeCheckpoint(gatherer.checkpoint(
                        context,
                        gatherParameters
                ).payload());
        final String explorationCheckpoint = explorer == null
                || exploreParameters == null
                ? ""
                : escapeCheckpoint(explorer.checkpoint(
                        context,
                        exploreParameters
                ).payload());
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"elapsedTicks\":%d,"
                                + "\"scanTurns\":%d,\"gatherFailures\":%d,"
                                + "\"initialWood\":%d,\"currentWood\":%d,"
                                + "\"gatherCheckpoint\":\"%s\","
                                + "\"explorationCheckpoint\":\"%s\"}",
                        phase.name(),
                        Math.max(0L, context.gameTick() - startedAtTick),
                        scanTurns,
                        gatherFailures,
                        initialWoodCount,
                        frame == null ? -1 : woodCount(frame),
                        gatherCheckpoint,
                        explorationCheckpoint
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
            case FAILED -> SkillResult.failed(Objects.requireNonNull(failure));
            default -> SkillResult.failed(SkillFailure.of(
                    NAME + ".invalid_state"
            ));
        };
    }

    private SkillTickResult tickSafely(final SkillContext context) {
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        if (frame == null) {
            return fail(context, NAME + ".body_unavailable");
        }
        final Optional<SkillFailure> unsafe = safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return fail(context, unsafe.orElseThrow().code());
        }
        if (context.gameTick() - startedAtTick >= MAXIMUM_TICKS) {
            return finishOrFail(context, frame, NAME + ".timed_out");
        }
        final int currentWoodCount = woodCount(frame);
        if (currentWoodCount > lastProgressWoodCount) {
            lastProgressWoodCount = currentWoodCount;
            lastWoodProgressAtTick = context.gameTick();
        }
        if (phase == Phase.GATHERING
                && context.gameTick() - lastWoodProgressAtTick
                    >= MAXIMUM_GATHER_NO_PROGRESS_TICKS) {
            return recoverStalledGather(context, frame);
        }
        if (phase == Phase.EXPLORING
                && context.gameTick() - lastWoodProgressAtTick
                    >= MAXIMUM_GATHER_NO_PROGRESS_TICKS) {
            return recoverStalledExploration(context, frame);
        }
        return switch (phase) {
            case SCANNING -> scanOrBind(context, frame);
            case BREAKING_CANOPY -> breakCanopy(context, frame);
            case DESCENDING_CANOPY -> descendCanopy(context, frame);
            case GATHERING -> gather(context, frame);
            case EXPLORING -> explore(context, frame);
            default -> fail(context, NAME + ".invalid_state");
        };
    }

    private SkillTickResult scanOrBind(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final Optional<InteractionSkillFrame> interaction =
                currentInteraction(frame);
        if (interaction.isPresent()) {
            final Optional<VisibleBlockFace> underfootLeaves =
                    interaction.orElseThrow()
                            .visibleBlockFaces()
                            .stream()
                            .filter(face -> isUnderfootLeaves(face, frame))
                            .min(Comparator.comparingDouble(
                                    VisibleBlockFace::distance
                            ));
            if (underfootLeaves.isPresent()) {
                return beginCanopyBreak(
                        context,
                        frame,
                        interaction.orElseThrow(),
                        underfootLeaves.orElseThrow()
                );
            }
            final Optional<VisibleBlockFace> wood = interaction.orElseThrow()
                    .visibleBlockFaces()
                    .stream()
                    .filter(GatherNearbyWoodSkill::isWood)
                    .filter(face -> !rejectedSeeds.contains(new GridPos(
                            face.block().x(),
                            face.block().y(),
                            face.block().z()
                    )))
                    .min(Comparator
                            .comparingDouble((VisibleBlockFace face) ->
                                    Math.abs(face.block().y()
                                            - frame.position().y()))
                            .thenComparingDouble(VisibleBlockFace::distance));
            if (wood.isPresent()) {
                final VisibleBlockFace seed = wood.orElseThrow();
                gatherParameters = new GatherVisibleBlockClusterParameters(
                        interaction.orElseThrow().dimension(),
                        observedTarget(interaction.orElseThrow(), seed),
                        seed.blockTypeId(),
                        MAXIMUM_BLOCKS,
                        12.0,
                        "minecraft:air"
                );
                final Optional<SkillFailure> rejected =
                        gatherer.preconditions(context, gatherParameters);
                if (rejected.isEmpty()) {
                    gatherer.start(context, gatherParameters);
                    phase = Phase.GATHERING;
                    return SkillTickResult.running(true, true);
                }
                rejectedSeeds.add(new GridPos(
                        seed.block().x(),
                        seed.block().y(),
                        seed.block().z()
                ));
                gatherParameters = null;
            }
        }
        if (scanTurns >= MAXIMUM_SCAN_TURNS) {
            return beginExploration(context, frame);
        }
        if (context.gameTick() < nextScanTick) {
            return SkillTickResult.running(false, true);
        }
        final int yawIndex = scanTurns % SCAN_YAW_OFFSETS.length;
        final int pitchIndex = scanTurns / SCAN_YAW_OFFSETS.length;
        final float targetYaw = normalizeYaw(
                scanBaseYaw + SCAN_YAW_OFFSETS[yawIndex]
        );
        final float targetPitch = SCAN_PITCHES[Math.min(
                pitchIndex,
                SCAN_PITCHES.length - 1
        )];
        scanTurns++;
        nextScanTick = context.gameTick() + SCAN_INTERVAL_TICKS;
        if (!core.stop().accepted()
                || !core.look(new LookIntent(
                        targetYaw,
                        targetPitch
                )).accepted()) {
            return fail(context, NAME + ".scan_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult beginCanopyBreak(
            final SkillContext context,
            final CoreSkillFrame frame,
            final InteractionSkillFrame interaction,
            final VisibleBlockFace leaves
    ) {
        canopyBreakParameters = new BreakBlockParameters(
                interaction.dimension(),
                observedTarget(interaction, leaves)
        );
        canopyBreaker = new BreakBlockSkill(
                expectedPlayerId,
                interactions,
                interactionFrames,
                InteractionSkillPolicy.defaults()
        );
        final Optional<SkillFailure> rejected = canopyBreaker.preconditions(
                context,
                canopyBreakParameters
        );
        if (rejected.isPresent()) {
            canopyBreaker = null;
            canopyBreakParameters = null;
            return SkillTickResult.running(false, true);
        }
        canopyStartY = frame.position().y();
        canopyBreaker.start(context, canopyBreakParameters);
        phase = Phase.BREAKING_CANOPY;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult breakCanopy(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (canopyBreaker == null || canopyBreakParameters == null) {
            return fail(context, NAME + ".canopy_binding_missing");
        }
        final SkillTickResult result = canopyBreaker.tick(
                context,
                canopyBreakParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            canopyBreaker = null;
            canopyBreakParameters = null;
            canopyBreakCompletedAtTick = context.gameTick();
            phase = Phase.DESCENDING_CANOPY;
            return SkillTickResult.running(true, false);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            canopyBreaker = null;
            canopyBreakParameters = null;
            scanTurns = Math.min(scanTurns + 1, MAXIMUM_SCAN_TURNS);
            phase = Phase.SCANNING;
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult descendCanopy(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        core.stop();
        final boolean descended = frame.position().y() <= canopyStartY - 0.5;
        if (descended && frame.onGround()) {
            scanTurns = 0;
            scanBaseYaw = yaw(frame.lookDirection());
            nextScanTick = context.gameTick();
            phase = Phase.SCANNING;
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - canopyBreakCompletedAtTick
                >= MAXIMUM_DESCENT_TICKS) {
            return fail(context, NAME + ".canopy_descent_timed_out");
        }
        return SkillTickResult.running(!frame.onGround(), false);
    }

    private SkillTickResult beginExploration(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        exploreParameters = new ExploreForTargetParameters(
                frame.dimension(),
                SearchTargetKind.BLOCK,
                "mcai_companion:convertible_wood",
                SEARCH_RADIUS,
                SEARCH_STEP
        );
        explorer = new ExploreForObservedTargetSkill(
                expectedPlayerId,
                core,
                coreFrames,
                () -> interactions.sessionGeneration().orElse(-1L),
                (candidate, ignored) -> candidate.visibleBlockFaces()
                        .stream()
                        .filter(GatherNearbyWoodSkill::isWood)
                        .map(face -> new GridPos(
                                face.block().x(),
                                face.block().y(),
                                face.block().z()
                        ))
                        .anyMatch(position ->
                                !rejectedSeeds.contains(position))
        );
        final Optional<SkillFailure> rejected = explorer.preconditions(
                context,
                exploreParameters
        );
        if (rejected.isPresent()) {
            return finishOrFail(
                    context,
                    frame,
                    NAME + ".exploration_rejected"
            );
        }
        explorer.start(context, exploreParameters);
        phase = Phase.EXPLORING;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult explore(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (explorer == null || exploreParameters == null) {
            return fail(context, NAME + ".exploration_binding_missing");
        }
        final SkillTickResult result = explorer.tick(
                context,
                exploreParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            explorer = null;
            exploreParameters = null;
            scanTurns = 0;
            scanBaseYaw = yaw(frame.lookDirection());
            nextScanTick = context.gameTick();
            phase = Phase.SCANNING;
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            if (!rejectedSeeds.isEmpty()
                    && gatherFailures < MAXIMUM_GATHER_FAILURES) {
                /*
                 * Exploration has changed the viewing position. A seed that
                 * was unreachable from the previous side may now be legal,
                 * so give those observed coordinates one bounded retry
                 * instead of oscillating between an immediate visibility
                 * success and a scan that permanently ignores them.
                 */
                explorer = null;
                exploreParameters = null;
                rejectedSeeds.clear();
                scanTurns = 0;
                scanBaseYaw = yaw(frame.lookDirection());
                nextScanTick = context.gameTick();
                phase = Phase.SCANNING;
                return SkillTickResult.running(true, true);
            }
            return finishOrFail(
                    context,
                    frame,
                    NAME + ".wood_not_found"
            );
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private SkillTickResult gather(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (gatherParameters == null) {
            return fail(context, NAME + ".gather_binding_missing");
        }
        final SkillTickResult result = gatherer.tick(
                context,
                gatherParameters
        );
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            gatherParameters = null;
            return finishOrFail(
                    context,
                    ownedFrame().orElse(frame),
                    NAME + ".no_wood_collected"
            );
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            rejectedSeeds.add(new GridPos(
                    gatherParameters.seed().x(),
                    gatherParameters.seed().y(),
                    gatherParameters.seed().z()
            ));
            gatherParameters = null;
            if (woodCount(ownedFrame().orElse(frame)) > initialWoodCount) {
                return complete(context);
            }
            if (++gatherFailures >= MAXIMUM_GATHER_FAILURES) {
                scanTurns = MAXIMUM_SCAN_TURNS;
            } else {
                scanTurns = 0;
            }
            scanBaseYaw = yaw(frame.lookDirection());
            nextScanTick = context.gameTick();
            phase = Phase.SCANNING;
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    /**
     * A connected-cluster child must not monopolize the body indefinitely
     * after its last physical pickup. Keep any item already acquired, end
     * this bounded parent action, and let the route issue a fresh fair scan.
     * If nothing was acquired, reject only that observed seed and continue
     * searching locally.
     */
    private SkillTickResult recoverStalledGather(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (gatherParameters != null) {
            final GridPos stalledSeed = new GridPos(
                    gatherParameters.seed().x(),
                    gatherParameters.seed().y(),
                    gatherParameters.seed().z()
            );
            gatherer.cancel(context, gatherParameters);
            gatherParameters = null;
            rejectedSeeds.add(stalledSeed);
        }
        core.stop();
        if (woodCount(frame) > initialWoodCount) {
            return complete(context);
        }
        if (++gatherFailures >= MAXIMUM_GATHER_FAILURES) {
            scanTurns = MAXIMUM_SCAN_TURNS;
        } else {
            scanTurns = 0;
        }
        scanBaseYaw = yaw(frame.lookDirection());
        nextScanTick = context.gameTick();
        lastWoodProgressAtTick = context.gameTick();
        phase = Phase.SCANNING;
        return SkillTickResult.running(true, true);
    }

    /**
     * Exploration is also a bounded part of gathering. If it makes no item
     * progress for the same watchdog interval as a connected tree cluster,
     * discard its rolling route and rescan from the body's current position.
     * This prevents one unreachable spiral segment from monopolizing the
     * body until the five-minute parent deadline.
     */
    private SkillTickResult recoverStalledExploration(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (explorer != null && exploreParameters != null) {
            explorer.cancel(context, exploreParameters);
        }
        explorer = null;
        exploreParameters = null;
        core.stop();
        rejectedSeeds.clear();
        scanTurns = 0;
        scanBaseYaw = yaw(frame.lookDirection());
        nextScanTick = context.gameTick();
        lastWoodProgressAtTick = context.gameTick();
        phase = Phase.SCANNING;
        return SkillTickResult.running(true, true);
    }

    private static String escapeCheckpoint(final String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private SkillTickResult finishOrFail(
            final SkillContext context,
            final CoreSkillFrame frame,
            final String failureCode
    ) {
        return woodCount(frame) > initialWoodCount
                ? complete(context)
                : fail(context, failureCode);
    }

    private SkillTickResult complete(final SkillContext context) {
        cancelChildren(context);
        core.stop();
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private SkillTickResult fail(
            final SkillContext context,
            final String code
    ) {
        cancelChildren(context);
        core.stop();
        failure = SkillFailure.of(code);
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    private void cancelChildren(final SkillContext context) {
        if (gatherParameters != null) {
            gatherer.cancel(context, gatherParameters);
        }
        gatherParameters = null;
        if (canopyBreaker != null && canopyBreakParameters != null) {
            canopyBreaker.cancel(context, canopyBreakParameters);
        }
        canopyBreaker = null;
        canopyBreakParameters = null;
        if (explorer != null && exploreParameters != null) {
            explorer.cancel(context, exploreParameters);
        }
        explorer = null;
        exploreParameters = null;
    }

    private Optional<CoreSkillFrame> ownedFrame() {
        return coreFrames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
        );
    }

    private Optional<InteractionSkillFrame> currentInteraction(
            final CoreSkillFrame frame
    ) {
        return interactionFrames.current()
                .filter(candidate -> expectedPlayerId.equals(
                        candidate.playerId()
                ))
                .filter(candidate -> candidate.dimension().equals(
                        frame.dimension()
                ))
                .filter(candidate -> candidate.observationAgeTicks()
                        <= GatheringSkillPolicy.defaults()
                                .maximumObservationAgeTicks());
    }

    private static Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (frame.inWater()) {
            return Optional.of(SkillFailure.of(NAME + ".unsafe_pose"));
        }
        final double maximumDanger = context.hardcore() ? 0.10 : 0.25;
        if (Math.max(context.riskScore(), frame.danger()) > maximumDanger) {
            return Optional.of(SkillFailure.of(NAME + ".danger_detected"));
        }
        final float minimumHealth = context.hardcore() ? 0.60F : 0.35F;
        if (frame.health() / frame.maxHealth() < minimumHealth) {
            return Optional.of(SkillFailure.of(NAME + ".health_too_low"));
        }
        return Optional.empty();
    }

    private static boolean isWood(final VisibleBlockFace face) {
        final String id = face.blockTypeId();
        return id.endsWith("_log")
                || id.endsWith("_wood")
                || id.endsWith("_stem")
                || id.endsWith("_hyphae")
                || id.equals("minecraft:bamboo_block");
    }

    private static boolean isUnderfootLeaves(
            final VisibleBlockFace face,
            final CoreSkillFrame frame
    ) {
        final GridPos support = frame.feet().below();
        return face.blockTypeId().endsWith("_leaves")
                && face.block().x() == support.x()
                && face.block().y() == support.y()
                && face.block().z() == support.z();
    }

    private static int woodCount(final CoreSkillFrame frame) {
        /*
         * CoreSkillFrame.inventory already contains the selected hotbar
         * slot. Adding mainHand again doubled every held log and made live
         * checkpoints claim 42 logs when the physical inventory contained
         * 21. Use the inventory total as the authoritative owned count and
         * only fall back to a hand stack for minimal test/frame adapters
         * that intentionally omit hotbar slots.
         */
        final int inventoryCount = frame.inventory().stream()
                .filter(item -> isWoodItem(item.itemId()))
                .mapToInt(InventoryItemSummary::count)
                .sum();
        int count = inventoryCount;
        if (isWoodItem(frame.mainHand().itemId())) {
            count = Math.max(count, frame.mainHand().count());
        }
        if (isWoodItem(frame.offHand().itemId())) {
            count = Math.max(count, frame.offHand().count());
        }
        return count;
    }

    private static boolean isWoodItem(final String id) {
        return id.endsWith("_log")
                || id.endsWith("_wood")
                || id.endsWith("_stem")
                || id.endsWith("_hyphae")
                || id.equals("minecraft:bamboo_block");
    }

    private static ObservedBlockTarget observedTarget(
            final InteractionSkillFrame frame,
            final VisibleBlockFace face
    ) {
        return new ObservedBlockTarget(
                frame.observationRevision(),
                face.block().x(),
                face.block().y(),
                face.block().z(),
                BlockFace.valueOf(face.face().toUpperCase(Locale.ROOT))
        );
    }

    private static float yaw(final PerceptionVec3 direction) {
        return (float) Math.toDegrees(
                Math.atan2(-direction.x(), direction.z())
        );
    }

    private static float normalizeYaw(final float value) {
        float normalized = value % 360.0F;
        if (normalized <= -180.0F) {
            normalized += 360.0F;
        } else if (normalized > 180.0F) {
            normalized -= 360.0F;
        }
        return normalized;
    }

    private static SkillParameterResult<NoParameters> parseNone(
            final List<SkillArgument> arguments
    ) {
        return arguments.isEmpty()
                ? SkillParameterResult.valid(NoParameters.INSTANCE)
                : SkillParameterResult.invalid(NAME + ".invalid_arguments");
    }

    private enum Phase {
        IDLE(false),
        SCANNING(true),
        BREAKING_CANOPY(true),
        DESCENDING_CANOPY(true),
        GATHERING(true),
        EXPLORING(true),
        COMPLETED(false),
        CANCELLED(false),
        FAILED(false);

        private final boolean active;

        Phase(final boolean active) {
            this.active = active;
        }

        private boolean active() {
            return active;
        }
    }
}
