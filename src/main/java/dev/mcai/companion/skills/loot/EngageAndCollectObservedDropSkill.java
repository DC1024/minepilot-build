package dev.mcai.companion.skills.loot;

import dev.mcai.companion.action.ActionMath;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.combat.CombatHardcoreRisk;
import dev.mcai.companion.skills.combat.CombatSkillPolicy;
import dev.mcai.companion.skills.combat.EngageObservedEntityParameters;
import dev.mcai.companion.skills.combat.EngageObservedEntitySkill;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Keeps the latency-sensitive melee-to-pickup transition local. The combat
 * child binds exactly one visible hostile; after combat the pickup child may
 * bind only an ordinary first-person-visible item entity of the requested
 * type. Success requires that type to enter the companion's own inventory.
 */
public final class EngageAndCollectObservedDropSkill
        implements Skill<EngageAndCollectParameters> {
    public static final String NAME =
            "engage_and_collect_observed_drop";

    private static final String ITEM_ENTITY = "minecraft:item";
    private static final int DROP_SCAN_TICKS = 100;
    private static final int SCAN_INTERVAL_TICKS = 4;
    private static final float[] DROP_SCAN_YAW_OFFSETS = {
            0.0F,
            35.0F,
            -35.0F,
            70.0F,
            -70.0F,
            110.0F,
            -110.0F,
            180.0F
    };
    private static final float[] DROP_SCAN_PITCHES = {
            28.0F,
            50.0F,
            10.0F
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final Predicate<VisibleEntity> targetAuthorization;
    private final boolean managesNearbyHostiles;
    private final boolean overridesHardcoreRisk;
    private final BiPredicate<SkillContext, CoreSkillFrame>
            collectionRiskAuthorization;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long dropScanStartedAtTick = -1;
    private long nextScanTick = -1;
    private int scanTurns;
    private float dropScanBaseYaw;
    private int initialItemCount;
    private EngageObservedEntitySkill combat;
    private EngageObservedEntityParameters combatParameters;
    private CollectObservedItemSkill collection;
    private CollectObservedItemParameters collectionParameters;

    public EngageAndCollectObservedDropSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames
    ) {
        this(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames,
                EngageObservedEntitySkill::standardCombatTarget,
                true,
                true,
                (context, frame) -> false
        );
    }

    EngageAndCollectObservedDropSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final Predicate<VisibleEntity> targetAuthorization,
            final boolean managesNearbyHostiles,
            final boolean overridesHardcoreRisk
    ) {
        this(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames,
                targetAuthorization,
                managesNearbyHostiles,
                overridesHardcoreRisk,
                (context, frame) -> false
        );
    }

    EngageAndCollectObservedDropSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final Predicate<VisibleEntity> targetAuthorization,
            final boolean managesNearbyHostiles,
            final boolean overridesHardcoreRisk,
            final BiPredicate<SkillContext, CoreSkillFrame>
                    collectionRiskAuthorization
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
        this.targetAuthorization = Objects.requireNonNull(
                targetAuthorization,
                "targetAuthorization"
        );
        this.managesNearbyHostiles = managesNearbyHostiles;
        this.overridesHardcoreRisk = overridesHardcoreRisk;
        this.collectionRiskAuthorization = Objects.requireNonNull(
                collectionRiskAuthorization,
                "collectionRiskAuthorization"
        );
    }

    @Override
    public SkillParameterParser<EngageAndCollectParameters>
            parameters() {
        return LootSkillParameters::parseEngageAndCollect;
    }

    @Override
    public boolean managesVisibleHostileProximity() {
        return managesNearbyHostiles && phase == Phase.COMBAT;
    }

    @Override
    public boolean managesPhysicalContactThreats() {
        return managesVisibleHostileProximity();
    }

    @Override
    public OptionalDouble hardcoreRiskThresholdOverride(
            final SkillContext context,
            final EngageAndCollectParameters parameters
    ) {
        if (!overridesHardcoreRisk) {
            return OptionalDouble.empty();
        }
        final Optional<CoreSkillFrame> frame = ownedFrame();
        if (frame.isEmpty()
                || phase != Phase.IDLE
                    && phase != Phase.COMBAT) {
            return OptionalDouble.empty();
        }
        return CombatHardcoreRisk.threshold(
                context,
                frame.orElseThrow(),
                1.0
        );
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final EngageAndCollectParameters parameters
    ) {
        final EngageObservedEntitySkill candidate = newCombat();
        return candidate.preconditions(
                context,
                combatParameters(parameters)
        );
    }

    @Override
    public void start(
            final SkillContext context,
            final EngageAndCollectParameters parameters
    ) {
        final CoreSkillFrame frame = ownedFrame().orElseThrow(
                () -> new IllegalStateException(
                        "Companion body changed before combat"
                )
        );
        phase = Phase.COMBAT;
        failure = null;
        startedAtTick = context.gameTick();
        dropScanStartedAtTick = -1;
        nextScanTick = -1;
        scanTurns = 0;
        /*
         * Preserve the direction in which the bound hostile was first
         * observed. The combat child deliberately fans its camera while
         * confirming target loss; using that final scan yaw as the later
         * drop-search origin can turn the player away from the actual death
         * site.
         */
        dropScanBaseYaw = yaw(frame);
        initialItemCount = inventoryCount(
                frame,
                parameters.expectedItemId()
        );
        combat = newCombat();
        combatParameters = combatParameters(parameters);
        combat.start(context, combatParameters);
        collection = null;
        collectionParameters = null;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final EngageAndCollectParameters parameters
    ) {
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context, parameters);
        } catch (RuntimeException exception) {
            return fail(context, NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final EngageAndCollectParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"expectedItemId\":\"%s\","
                            + "\"scanTurns\":%d}",
                        phase.name(),
                        parameters.expectedItemId(),
                        scanTurns
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final EngageAndCollectParameters parameters
    ) {
        cancelChildren(context);
        core.stop();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final EngageAndCollectParameters parameters
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
            final EngageAndCollectParameters parameters
    ) {
        if (context.gameTick() - startedAtTick
                >= parameters.maximumTicks()) {
            return fail(context, NAME + ".timed_out");
        }
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        if (frame == null) {
            return fail(context, NAME + ".body_unavailable");
        }
        if (phase != Phase.COMBAT
                && inventoryCount(
                    frame,
                    parameters.expectedItemId()
                ) > initialItemCount) {
            cancelChildren(context);
            core.stop();
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (phase == Phase.COMBAT
                && frame.visibleEntities().stream()
                    .noneMatch(entity ->
                            entity.hostile()
                                && targetAuthorization.test(entity)
                    )) {
            final Optional<SkillTickResult> collectionStart =
                    startVisibleCollection(
                            context,
                            parameters,
                            frame
                    );
            if (collectionStart.isPresent()) {
                return collectionStart.orElseThrow();
            }
        }
        return switch (phase) {
            case COMBAT -> tickCombat(context, parameters);
            case FINDING_DROP ->
                    findDrop(context, parameters, frame);
            case COLLECTING ->
                    tickCollection(context, parameters);
            default -> fail(context, NAME + ".invalid_state");
        };
    }

    private SkillTickResult tickCombat(
            final SkillContext context,
            final EngageAndCollectParameters parameters
    ) {
        final SkillTickResult result = combat.tick(
                context,
                combatParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            return fail(
                    context,
                    result.failure().orElseGet(() ->
                            SkillFailure.of(NAME + ".combat_failed")
                    )
            );
        }
        if (result.status() != SkillTickResult.Status.COMPLETED) {
            return SkillTickResult.running(
                    result.madeProgress(),
                    result.safeCheckpoint()
            );
        }
        combat = null;
        combatParameters = null;
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        if (frame != null
                && inventoryCount(
                    frame,
                    parameters.expectedItemId()
                ) > initialItemCount) {
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        phase = Phase.FINDING_DROP;
        dropScanStartedAtTick = context.gameTick();
        nextScanTick = context.gameTick();
        scanTurns = 0;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult findDrop(
            final SkillContext context,
            final EngageAndCollectParameters parameters,
            final CoreSkillFrame frame
    ) {
        final Optional<Integer> index = visibleDropIndex(
                frame,
                parameters.expectedItemId()
        );
        if (index.isPresent()) {
            return startVisibleCollection(
                    context,
                    parameters,
                    frame
            ).orElseThrow();
        }
        if (context.gameTick() - dropScanStartedAtTick
                >= DROP_SCAN_TICKS) {
            return fail(
                    context,
                    NAME + ".expected_drop_not_observed"
            );
        }
        if (context.gameTick() >= nextScanTick) {
            final int directionIndex = scanTurns
                    % DROP_SCAN_YAW_OFFSETS.length;
            final int pitchPass = scanTurns
                    / DROP_SCAN_YAW_OFFSETS.length;
            final float yawOffset =
                    DROP_SCAN_YAW_OFFSETS[directionIndex];
            final float pitch = DROP_SCAN_PITCHES[
                    pitchPass % DROP_SCAN_PITCHES.length
            ];
            if (!core.look(new LookIntent(
                    ActionMath.wrapDegrees(
                            dropScanBaseYaw + yawOffset
                    ),
                    pitch
            )).accepted()) {
                return fail(context, NAME + ".scan_rejected");
            }
            scanTurns++;
            nextScanTick = context.gameTick()
                    + SCAN_INTERVAL_TICKS;
            return SkillTickResult.running(true, true);
        }
        core.stop();
        return SkillTickResult.running(false, true);
    }

    /**
     * Starts pickup from one current, synchronized first-person observation.
     *
     * <p>This path is also allowed while the combat child is confirming that
     * its attacked target disappeared, but only after no authorized hostile
     * remains visible. A real player who sees the required drop at the death
     * site does not keep turning away for a full target-loss scan before
     * walking over it.</p>
     */
    private Optional<SkillTickResult> startVisibleCollection(
            final SkillContext context,
            final EngageAndCollectParameters parameters,
            final CoreSkillFrame frame
    ) {
        final Optional<Integer> index = visibleDropIndex(
                frame,
                parameters.expectedItemId()
        );
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
                || !frame.visibleEntities().get(index.orElseThrow())
                    .entityId().equals(
                        interaction.orElseThrow()
                            .visibleEntities()
                            .get(index.orElseThrow())
                            .entityId()
                    )) {
            return Optional.of(
                    SkillTickResult.running(false, true)
            );
        }
        if (combat != null && combatParameters != null) {
            combat.cancel(context, combatParameters);
            combat = null;
            combatParameters = null;
        }
        collection = new CollectObservedItemSkill(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames,
                collectionRiskAuthorization
        );
        collectionParameters =
                new CollectObservedItemParameters(
                        frame.observationRevision(),
                        "visible-" + index.orElseThrow(),
                        remainingCollectionTicks(
                                context,
                                parameters
                        )
                );
        final Optional<SkillFailure> blocked =
                collection.preconditions(
                        context,
                        collectionParameters
                );
        if (blocked.isPresent()) {
            return Optional.of(fail(
                    context,
                    NAME + ".collection_blocked"
            ));
        }
        collection.start(context, collectionParameters);
        phase = Phase.COLLECTING;
        return Optional.of(SkillTickResult.running(true, true));
    }

    private SkillTickResult tickCollection(
            final SkillContext context,
            final EngageAndCollectParameters parameters
    ) {
        final SkillTickResult result = collection.tick(
                context,
                collectionParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            return fail(
                    context,
                    result.failure().orElseGet(() ->
                            SkillFailure.of(NAME + ".collection_failed")
                    )
            );
        }
        if (result.status() != SkillTickResult.Status.COMPLETED) {
            return SkillTickResult.running(
                    result.madeProgress(),
                    result.safeCheckpoint()
            );
        }
        final CoreSkillFrame frame = ownedFrame().orElse(null);
        if (frame == null
                || inventoryCount(
                    frame,
                    parameters.expectedItemId()
                ) <= initialItemCount) {
            return fail(
                    context,
                    NAME + ".inventory_confirmation_missing"
            );
        }
        collection = null;
        collectionParameters = null;
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private EngageObservedEntitySkill newCombat() {
        return new EngageObservedEntitySkill(
                expectedPlayerId,
                core,
                coreFrames,
                interactions,
                interactionFrames,
                CombatSkillPolicy.defaults(),
                targetAuthorization,
                managesNearbyHostiles,
                overridesHardcoreRisk
        );
    }

    /**
     * Package-private composition boundary for resource-specific safety
     * wrappers. It exposes no target identity or world data; it only confirms
     * that the one-target combat child has ended and drop collection may now
     * begin.
     */
    boolean awaitingObservedDrop() {
        return phase == Phase.FINDING_DROP
                || phase == Phase.COLLECTING;
    }

    private static EngageObservedEntityParameters combatParameters(
            final EngageAndCollectParameters parameters
    ) {
        return new EngageObservedEntityParameters(
                parameters.sampleSequence(),
                parameters.observationId()
        );
    }

    private Optional<CoreSkillFrame> ownedFrame() {
        return coreFrames.current()
                .filter(frame ->
                        expectedPlayerId.equals(frame.playerId())
                );
    }

    private static Optional<Integer> visibleDropIndex(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        for (int index = 0;
                index < frame.visibleEntities().size();
                index++) {
            final VisibleEntity entity =
                    frame.visibleEntities().get(index);
            if (ITEM_ENTITY.equals(entity.entityTypeId())
                    && itemId.equals(
                        entity.visibleProperties().get("itemId")
                    )) {
                return Optional.of(index);
            }
        }
        return Optional.empty();
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

    private int remainingCollectionTicks(
            final SkillContext context,
            final EngageAndCollectParameters parameters
    ) {
        final long elapsed = Math.max(
                0L,
                context.gameTick() - startedAtTick
        );
        final long remaining = Math.max(
                20L,
                parameters.maximumTicks() - elapsed
        );
        return (int) Math.min(600L, remaining);
    }

    private static float yaw(final CoreSkillFrame frame) {
        return (float) Math.toDegrees(Math.atan2(
                -frame.lookDirection().x(),
                frame.lookDirection().z()
        ));
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

    private void cancelChildren(final SkillContext context) {
        if (combat != null && combatParameters != null) {
            combat.cancel(context, combatParameters);
        }
        if (collection != null && collectionParameters != null) {
            collection.cancel(context, collectionParameters);
        }
        combat = null;
        combatParameters = null;
        collection = null;
        collectionParameters = null;
    }

    private enum Phase {
        IDLE,
        COMBAT,
        FINDING_DROP,
        COLLECTING,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return this == COMBAT
                    || this == FINDING_DROP
                    || this == COLLECTING;
        }
    }
}
