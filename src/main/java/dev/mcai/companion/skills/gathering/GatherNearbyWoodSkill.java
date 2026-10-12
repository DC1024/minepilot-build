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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    /**
     * FIX23: cap on consecutive canopy breaks (without any wood progress) before
     * the scan phase gives up clearing leaves and forces {@code beginExploration}
     * instead. The old code broke underfoot leaves on every scan tick but never
     * advanced {@code scanTurns}, so a bot standing in a canopy looped on canopy
     * breaks forever (observed terminal: {@code no_wood_collected.w0.r0.c1200.d-1})
     * and never reached the exploration branch that would move it to a trunk.
     */
    private static final int CANOPY_BREAKS_BEFORE_EXPLORE = 8;
    /**
     * FIX23: ticks of danger (riskScore or frame.danger above the safety
     * threshold) that may be tolerated before the skill hard-fails with
     * {@code danger_detected}. A single-tick mob/risk spike used to abort a whole
     * multi-minute gather and let the supervisor restart it into the same danger;
     * a short grace lets transient spikes pass (brief pause) while still failing on
     * sustained danger.
     */
    private static final int DANGER_GRACE_TICKS = 30;
    /**
     * Ticks without a single new wood item before the skill stops trusting the
     * current phase. Applies in every phase (not just GATHERING/EXPLORING):
     * a canopy that will not yield used to burn the whole MAXIMUM_TICKS budget
     * with currentWood unchanged.
     */
    private static final int MAXIMUM_NO_PROGRESS_TICKS = 600;
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
    /*
     * P21 wood-gather diagnostics. {@code no_wood_collected} is a bare terminal
     * code that cannot say why the body walked away empty-handed: whether the
     * perception layer never delivered a wood face, every candidate seed was
     * rejected by the gatherer preconditions, no canopy was ever broken, or the
     * world model never offered a reachable convertible-wood POI. These counters
     * snapshot the last observation so the terminal code can disambiguate.
     */
    private int diagnosticsWoodFacesSeen = 0;
    private int diagnosticsCanopyBinds = 0;
    private double diagnosticsExplorationBestDist = -1.0;
    /*
     * P22 wood-gather reject-reason capture. {@code rejectedSeeds} only stores
     * the rejected seed positions, so a {@code no_wood_collected.w*.r*} terminal
     * could never say WHY the gatherer refused a seed. This map records, per
     * reject, the gatherer's precondition failure code (gatherer prefix
     * stripped), the gatherer's terminal failure code from a bound seed that
     * FAILED mid-gather, or the synthetic {@code gatherer_stalled} when a bound
     * seed was cancelled by the no-progress watchdog without ever mining. The
     * dominant reason is appended to the {@code no_wood_collected} code and
     * dumped in the live checkpoint JSON so the exact cause is observable
     * rather than guessed.
     */
    private final Map<String, Integer> diagnosticsRejectReasons = new HashMap<>();
    private static final String GATHERER_PREFIX = "gather_visible_block_cluster.";
    /*
     * FIX23 counters. {@code canopyBreaksSinceProgress} bounds the canopy-clear
     * loop; {@code dangerTicks} implements the danger grace window.
     */
    private int canopyBreaksSinceProgress = 0;
    private int dangerTicks = 0;

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
        diagnosticsWoodFacesSeen = 0;
        diagnosticsCanopyBinds = 0;
        diagnosticsExplorationBestDist = -1.0;
        rejectedSeeds.clear();
        diagnosticsRejectReasons.clear();
        canopyBreaksSinceProgress = 0;
        dangerTicks = 0;
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
        /*
         * Diagnostic only. BREAKING_CANOPY has no child checkpoint in the
         * original payload, so a canopy stall was indistinguishable from any
         * other stall in a persisted snapshot. Mirroring the breaker's own
         * checkpoint makes the next such capture self-explanatory (phase,
         * bound block, observation revision) without changing behaviour.
         */
        final String canopyCheckpoint = canopyBreaker == null
                || canopyBreakParameters == null
                ? ""
                : escapeCheckpoint(canopyBreaker.checkpoint(
                        context,
                        canopyBreakParameters
                ).payload());
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"elapsedTicks\":%d,"
                                + "\"scanTurns\":%d,\"gatherFailures\":%d,"
                                + "\"initialWood\":%d,\"currentWood\":%d,"
                                + "\"woodFacesSeen\":%d,\"rejectedSeeds\":%d,"
                                + "\"canopyBinds\":%d,\"explorationBestDist\":%s,"
                                + "\"rejectedReasons\":\"%s\","
                                + "\"gatherCheckpoint\":\"%s\","
                                + "\"explorationCheckpoint\":\"%s\","
                                + "\"canopyCheckpoint\":\"%s\"}",
                        phase.name(),
                        Math.max(0L, context.gameTick() - startedAtTick),
                        scanTurns,
                        gatherFailures,
                        initialWoodCount,
                        frame == null ? -1 : woodCount(frame),
                        diagnosticsWoodFacesSeen,
                        rejectedSeeds.size(),
                        diagnosticsCanopyBinds,
                        diagnosticsExplorationBestDist < 0.0
                                ? "\"-\""
                                : String.format(
                                        Locale.ROOT,
                                        "%.3f",
                                        diagnosticsExplorationBestDist),
                        rejectedReasonsJson(),
                        gatherCheckpoint,
                        explorationCheckpoint,
                        canopyCheckpoint
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
        final double maximumDanger = context.hardcore() ? 0.10 : 0.25;
        final double dangerLevel = Math.max(context.riskScore(), frame.danger());
        if (frame.inWater()) {
            return fail(context, NAME + ".unsafe_pose");
        }
        if (dangerLevel > maximumDanger) {
            /*
             * FIX23 danger grace: a transient single-tick risk/mob spike must
             * not abort a whole multi-minute gather and let the supervisor
             * restart it into the same danger (observed restart-loop after
             * {@code danger_detected}). Pause briefly and only hard-fail on
             * sustained danger.
             */
            if (++dangerTicks >= DANGER_GRACE_TICKS) {
                return fail(context, NAME + ".danger_detected");
            }
            core.stop();
            return SkillTickResult.running(false, true);
        }
        dangerTicks = 0;
        final float minimumHealth = context.hardcore() ? 0.60F : 0.35F;
        if (frame.health() / frame.maxHealth() < minimumHealth) {
            return fail(context, NAME + ".health_too_low");
        }
        if (context.gameTick() - startedAtTick >= MAXIMUM_TICKS) {
            return finishOrFail(context, frame, NAME + ".timed_out");
        }
        final int currentWoodCount = woodCount(frame);
        if (currentWoodCount > lastProgressWoodCount) {
            lastProgressWoodCount = currentWoodCount;
            lastWoodProgressAtTick = context.gameTick();
            canopyBreaksSinceProgress = 0;
        }
        /*
         * Phase-independent no-progress watchdog.
         *
         * The watchdog used to be gated on GATHERING or EXPLORING only, so a
         * canopy that refused to yield monopolised the entire parent budget:
         * a live capture showed phase=BREAKING_CANOPY with elapsedTicks=5999
         * of MAXIMUM_TICKS=6000 and currentWood unchanged, while
         * gatherFailures stayed 0 because BreakBlockSkill never reported
         * FAILED (so no recovery path was ever reached). The companion simply
         * stood on the leaves until the five-minute deadline, every time.
         *
         * lastWoodProgressAtTick is only reset by the recovery routines below
         * and on real wood progress, never on an ordinary phase change, so
         * budget burned across a phase cycle still counts as no progress.
         * That is what bounds a hopeless objective: each recovery costs
         * MAXIMUM_NO_PROGRESS_TICKS, and MAXIMUM_GATHER_FAILURES of them end
         * the skill so the brain can choose something else instead of spending
         * the full MAXIMUM_TICKS on a tree it cannot convert.
         */
        if (context.gameTick() - lastWoodProgressAtTick
                >= MAXIMUM_NO_PROGRESS_TICKS) {
            /*
             * Every watchdog round counts as one failure, whichever phase it
             * interrupted, so a companion that oscillates between exploring
             * and scanning cannot reset its way out of the failure budget.
             */
            if (++gatherFailures >= MAXIMUM_GATHER_FAILURES) {
                return failNoWoodCollected(context);
            }
            if (phase == Phase.EXPLORING) {
                return recoverStalledExploration(context, frame);
            }
            if (phase == Phase.GATHERING) {
                return recoverStalledGather(context, frame);
            }
            /*
             * Canopy and scan phases have no child cluster to abandon, but the
             * stale canopy breaker (if any) must still be released so the next
             * attempt binds to a fresh observation instead of a stale block.
             */
            cancelCanopyBreaker(context);
            scanBaseYaw = yaw(frame.lookDirection());
            nextScanTick = context.gameTick();
            lastWoodProgressAtTick = context.gameTick();
            phase = Phase.SCANNING;
            return SkillTickResult.running(true, true);
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
            final InteractionSkillFrame visible = interaction.orElseThrow();
            final long visibleWood = visible.visibleBlockFaces()
                    .stream()
                    .filter(GatherNearbyWoodSkill::isWood)
                    .filter(face -> !rejectedSeeds.contains(new GridPos(
                            face.block().x(),
                            face.block().y(),
                            face.block().z()
                    )))
                    .count();
            diagnosticsWoodFacesSeen += (int) visibleWood;
            /*
             * FIX23: bind a visible log BEFORE clearing the leaf we stand on.
             * The previous order short-circuited to canopy break whenever
             * underfoot leaves existed, so a bot in a canopy never reached the
             * trunk and looped on canopy breaks (w0.r0.c1200.d-1). Mining the
             * visible log is always the higher-value action.
             */
            final Optional<VisibleBlockFace> wood = visible.visibleBlockFaces()
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
                        visible.dimension(),
                        observedTarget(visible, seed),
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
                recordReject(rejected.get().code());
                rejectedSeeds.add(new GridPos(
                        seed.block().x(),
                        seed.block().y(),
                        seed.block().z()
                ));
                gatherParameters = null;
            }
            final Optional<VisibleBlockFace> underfootLeaves =
                    visible.visibleBlockFaces()
                            .stream()
                            .filter(face -> isUnderfootLeaves(face, frame))
                            .min(Comparator.comparingDouble(
                                    VisibleBlockFace::distance
                            ));
            if (underfootLeaves.isPresent()) {
                /*
                 * FIX23: bound canopy clearing. After enough breaks without
                 * wood progress, explore for a reachable trunk instead of
                 * looping on the same leaf tile (the old code never advanced
                 * scanTurns from this branch, so exploration was unreachable).
                 */
                if (canopyBreaksSinceProgress >= CANOPY_BREAKS_BEFORE_EXPLORE) {
                    return beginExploration(context, frame);
                }
                canopyBreaksSinceProgress++;
                return beginCanopyBreak(
                        context,
                        frame,
                        visible,
                        underfootLeaves.orElseThrow()
                );
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
        diagnosticsCanopyBinds++;
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
                    currentNoWoodCode(context)
            );
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            recordReject(result.failure().orElseThrow().code());
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
            recordReject("gatherer_stalled");
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

    /**
     * Release a bound canopy breaker without ending the parent skill, so the
     * next scan binds to a fresh observation rather than a stale leaf block.
     * Used by the phase-independent no-progress watchdog.
     */
    private void cancelCanopyBreaker(final SkillContext context) {
        if (canopyBreaker != null && canopyBreakParameters != null) {
            canopyBreaker.cancel(context, canopyBreakParameters);
        }
        canopyBreaker = null;
        canopyBreakParameters = null;
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

    /**
     * P21 diagnostic helper: builds the disambiguated {@code no_wood_collected}
     * code from the last observation's counters, best-effort capturing the
     * exploration travel's {@code bestDistance} (POI reachability) when the
     * explorer is still live. The bare {@code no_wood_collected} token is kept
     * so existing monitors keep matching.
     */
    private String currentNoWoodCode(final SkillContext context) {
        if (diagnosticsExplorationBestDist < 0.0
                && explorer != null
                && exploreParameters != null) {
            try {
                final String payload = explorer.checkpoint(
                        context, exploreParameters).payload();
                final java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("\"bestDistance\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
                        .matcher(payload);
                if (matcher.find()) {
                    diagnosticsExplorationBestDist = Double.parseDouble(
                            matcher.group(1));
                }
            } catch (RuntimeException ignored) {
                // best effort: leave -1
            }
        }
        final int d = diagnosticsExplorationBestDist < 0.0
                ? -1
                : (int) Math.round(diagnosticsExplorationBestDist * 10.0);
        final String reason = dominantRejectReason();
        return noWoodCollectedCode(
                diagnosticsWoodFacesSeen,
                rejectedSeeds.size(),
                diagnosticsCanopyBinds,
                d,
                reason
        );
    }

    /**
     * P22 helper: builds a compact {@code reason:count;reason:count} summary of
     * the gatherer reject reasons recorded since {@code start()}, for the live
     * checkpoint JSON. Reasons are ordered by frequency (highest first), then
     * alphabetically for a deterministic tie-break.
     */
    private String rejectedReasonsJson() {
        if (diagnosticsRejectReasons.isEmpty()) {
            return "";
        }
        return diagnosticsRejectReasons.entrySet().stream()
                .sorted(java.util.Comparator
                        .<Map.Entry<String, Integer>>comparingInt(
                                Map.Entry::getValue)
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(e -> e.getKey() + ":" + e.getValue())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    /**
     * P22 helper: the dominant reject reason, or {@code null} when none was
     * recorded. Multiple distinct reasons are joined with {@code +} so the
     * terminal code still exposes the full breakdown (e.g.
     * {@code seed_not_visible+gatherer_stalled}).
     */
    private String dominantRejectReason() {
        if (diagnosticsRejectReasons.isEmpty()) {
            return null;
        }
        return diagnosticsRejectReasons.entrySet().stream()
                .sorted(java.util.Comparator
                        .<Map.Entry<String, Integer>>comparingInt(
                                Map.Entry::getValue)
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.joining("+"));
    }

    /**
     * P22 helper: records a gatherer reject reason. The gatherer returns codes
     * prefixed with its own name; strip that so the terminal code stays short
     * and the reason reads as a cause (e.g. {@code seed_not_visible},
     * {@code inventory_full}, {@code tool_unavailable}, {@code durability},
     * {@code snapshot_failure}, or the synthetic {@code gatherer_stalled}).
     */
    private void recordReject(final String rawCode) {
        final String code = rawCode.startsWith(GATHERER_PREFIX)
                ? rawCode.substring(GATHERER_PREFIX.length())
                : rawCode;
        diagnosticsRejectReasons.merge(code, 1, Integer::sum);
    }

    private SkillTickResult failNoWoodCollected(
            final SkillContext context
    ) {
        return fail(context, currentNoWoodCode(context));
    }

    /**
     * P21 diagnostic helper: disambiguates an empty wood gather by appending
     * the last observation's counters to the still-monitorable
     * {@code no_wood_collected} token:
     * <ul>
     *   <li>{@code w} = raw wood faces seen during scanning (perception
     *       delivery);</li>
     *   <li>{@code r} = wood seeds bound but rejected by the gatherer's
     *       preconditions;</li>
     *   <li>{@code c} = canopy breaks started;</li>
     *   <li>{@code d} = exploration best distance x10 (POI reachability;
     *       -1 unknown).</li>
     * </ul>
     * Interpretation: {@code w0} = perception never delivered a wood face
     * (leaves occluding logs / sparse sampling); {@code w>0 r>0} = wood seen
     * but every seed precondition-rejected; {@code c0} = no canopy handling
     * attempted while standing under one; {@code d~80} = world model never
     * yielded a reachable convertible_wood POI.
     */
    static String noWoodCollectedCode(
            final int woodFacesSeen,
            final int rejectedSeeds,
            final int canopyBinds,
            final int bestDistX10
    ) {
        return NAME
                + ".no_wood_collected.w"
                + woodFacesSeen
                + ".r"
                + rejectedSeeds
                + ".c"
                + canopyBinds
                + ".d"
                + bestDistX10;
    }

    /**
     * P22 overload: appends the dominant gatherer reject reason (or multiple
     * reasons joined with {@code +}) to the still-monitorable
     * {@code no_wood_collected} token so the exact cause of an empty gather is
     * observable in the terminal failure code. A null/empty reason returns the
     * base code unchanged (keeps the bare-code monitors matching).
     */
    static String noWoodCollectedCode(
            final int woodFacesSeen,
            final int rejectedSeeds,
            final int canopyBinds,
            final int bestDistX10,
            final String rejectReason
    ) {
        final String base = noWoodCollectedCode(
                woodFacesSeen, rejectedSeeds, canopyBinds, bestDistX10);
        return rejectReason == null || rejectReason.isEmpty()
                ? base
                : base + "." + rejectReason;
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
