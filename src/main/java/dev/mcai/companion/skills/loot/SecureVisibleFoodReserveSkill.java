package dev.mcai.companion.skills.loot;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.VisibleEntity;
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
import dev.mcai.companion.skills.core.VanillaFoodItems;
import dev.mcai.companion.skills.exploration.ExploreForObservedTargetSkill;
import dev.mcai.companion.skills.exploration.ExploreForTargetParameters;
import dev.mcai.companion.skills.exploration.SearchTargetKind;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Secures the foundation food reserve with one high-level model decision.
 *
 * <p>Every animal must still pass the ordinary first-person semantic filter.
 * Each attack and drop pickup is delegated to the same normal-player hunt
 * transaction used by the one-target public skill. The wrapper merely keeps
 * selection, retries and a bounded visual scan local so network/model latency
 * is not paid once per cow, pig or sheep.</p>
 */
public final class SecureVisibleFoodReserveSkill
        implements Skill<NoParameters> {
    public static final String NAME = "secure_visible_food_reserve";
    public static final int REQUIRED_FOOD = 8;

    private static final int MAXIMUM_TICKS = 8_000;
    private static final int MAXIMUM_SCAN_TURNS = 48;
    private static final int SCAN_INTERVAL_TICKS = 5;
    private static final int CHILD_MAXIMUM_TICKS = 500;
    private static final int EXPLORATION_RADIUS = 32;
    private static final int EXPLORATION_STEP = 8;
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
            22.0F,
            -18.0F
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final HuntObservedFoodAnimalSkill hunter;

    private final Set<UUID> exhaustedTargets = new HashSet<>();
    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long nextScanTick = -1;
    private long lastDiagnosticTick = -1;
    private int scanTurns;
    private float scanBaseYaw;
    private UUID activeTargetId;
    private HuntObservedFoodAnimalParameters huntParameters;
    private ExploreForObservedTargetSkill explorer;
    private ExploreForTargetParameters explorationParameters;

    public SecureVisibleFoodReserveSkill(
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
        this.interactionFrames = Objects.requireNonNull(
                interactionFrames,
                "interactionFrames"
        );
        this.interactions = Objects.requireNonNull(
                interactions,
                "interactions"
        );
        hunter = new HuntObservedFoodAnimalSkill(
                expectedPlayerId,
                core,
                coreFrames,
                this.interactions,
                this.interactionFrames
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
        if (frame.orElseThrow().inWater()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".unsafe_pose"
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
                        "Companion body changed before food reserve"
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
        scanBaseYaw = yaw(frame);
        lastDiagnosticTick = -1;
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
                        "{\"phase\":\"%s\",\"food\":%d,"
                            + "\"exhaustedTargets\":%d,"
                            + "\"scanTurns\":%d}",
                        phase.name(),
                        ownedFrame().map(
                                SecureVisibleFoodReserveSkill::foodCount
                        ).orElse(0),
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
        emitLiveDiagnostic(context, frame);
        if (foodCount(frame) >= REQUIRED_FOOD) {
            cancelHunter(context);
            cancelExplorer(context);
            core.stop();
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
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
            final String expected = HuntObservedFoodAnimalSkill
                    .defaultFoodDrop(
                            target.entity().entityTypeId()
                    )
                    .orElseThrow();
            final HuntObservedFoodAnimalParameters parameters =
                    new HuntObservedFoodAnimalParameters(
                            frame.observationRevision(),
                            "visible-" + target.index(),
                            expected,
                            CHILD_MAXIMUM_TICKS
                    );
            final Optional<SkillFailure> rejected =
                    hunter.preconditions(context, parameters);
            if (rejected.isPresent()) {
                return SkillTickResult.running(false, true);
            }
            hunter.start(context, parameters);
            activeTargetId = target.entity().entityId();
            huntParameters = parameters;
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
            activeTargetId = null;
            huntParameters = null;
            phase = Phase.SELECTING;
            nextScanTick = context.gameTick();
            scanBaseYaw = yaw(frame);
            scanTurns = 0;
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            exhaustedTargets.add(activeTargetId);
            cancelHunter(context);
            phase = Phase.SELECTING;
            nextScanTick = context.gameTick();
            scanBaseYaw = yaw(frame);
            scanTurns = 0;
            return SkillTickResult.running(true, true);
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
                        HuntObservedFoodAnimalSkill
                            .legalFoodAnimalTarget(
                                    target.entity()
                            ))
                .filter(target ->
                        HuntObservedFoodAnimalSkill
                            .defaultFoodDrop(
                                    target.entity()
                                        .entityTypeId()
                            )
                            .isPresent())
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
        final float scanYaw = scanBaseYaw
                + YAW_OFFSETS[
                        yawIndex % YAW_OFFSETS.length
                ];
        if (!core.stop().accepted()
                || !core.look(
                        new LookIntent(
                                scanYaw,
                                PITCHES[pitchIndex]
                        )
                ).accepted()) {
            return fail(context, NAME + ".scan_rejected");
        }
        scanTurns++;
        nextScanTick =
                context.gameTick() + SCAN_INTERVAL_TICKS;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult beginExploration(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        cancelExplorer(context);
        /*
         * The registry id is a valid typed placeholder for the reusable
         * exploration contract. Detection is replaced below with the legal
         * food-animal predicate, so cows, pigs, sheep, chickens and other
         * explicitly supported vanilla food animals all terminate the same
         * bounded first-person search.
         */
        explorationParameters = new ExploreForTargetParameters(
                frame.dimension(),
                SearchTargetKind.ENTITY,
                "minecraft:cow",
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
            return fail(
                    context,
                    NAME + ".food_exploration_rejected"
            );
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
            phase = Phase.SELECTING;
            scanBaseYaw = yaw(frame);
            scanTurns = 0;
            nextScanTick = context.gameTick();
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            cancelExplorer(context);
            return fail(
                    context,
                    NAME + ".food_animal_not_found"
            );
        }
        return SkillTickResult.running(
                result.madeProgress(),
                result.safeCheckpoint()
        );
    }

    private void cancelHunter(final SkillContext context) {
        if (huntParameters != null) {
            hunter.cancel(context, huntParameters);
        }
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
        cancelHunter(context);
        cancelExplorer(context);
        core.stop();
        failure = SkillFailure.of(code);
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    /**
     * The foundation fixture is intentionally model-driven and can spend
     * several minutes in the fair first-person scan.  Keep one bounded,
     * opt-in trace so a live run distinguishes “no legal animal was seen”
     * from a child combat or pickup stall; this never changes decisions.
     */
    private void emitLiveDiagnostic(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!Boolean.getBoolean("mcai.liveModelTest")
                || lastDiagnosticTick >= 0
                    && context.gameTick() - lastDiagnosticTick < 100) {
            return;
        }
        lastDiagnosticTick = context.gameTick();
        final long legalAnimals = frame.visibleEntities().stream()
                .filter(HuntObservedFoodAnimalSkill::legalFoodAnimalTarget)
                .count();
        MinecraftAiCompanion.LOGGER.info(
                "Live food reserve trace: phase={}, tick={}, food={}, "
                    + "visibleEntities={}, legalAnimals={}, "
                    + "exhaustedTargets={}, scanTurns={}, position={}, "
                    + "look={}, activeTarget={}",
                phase,
                context.gameTick(),
                foodCount(frame),
                frame.visibleEntities().size(),
                legalAnimals,
                exhaustedTargets.size(),
                scanTurns,
                frame.position(),
                frame.lookDirection(),
                activeTargetId
        );
    }

    private Optional<CoreSkillFrame> ownedFrame() {
        return coreFrames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
        );
    }

    private static int foodCount(
            final CoreSkillFrame frame
    ) {
        return frame.inventory().stream()
                .filter(item -> VanillaFoodItems.isSafeFood(
                        item.itemId()
                ))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static float yaw(final CoreSkillFrame frame) {
        return (float) Math.toDegrees(Math.atan2(
                -frame.lookDirection().x(),
                frame.lookDirection().z()
        ));
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
