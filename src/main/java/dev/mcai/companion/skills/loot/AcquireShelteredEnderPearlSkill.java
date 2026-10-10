package dev.mcai.companion.skills.loot;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.combat.EngageObservedEntitySkill;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

/**
 * Fair Ender-pearl acquisition from one visible hostile Enderman.
 *
 * <p>The player must already be centered under a completely observed 3x3
 * ceiling whose underside is exactly two blocks above the feet. Combat is
 * delegated to the normal cooldown/reach/occlusion attack controller, but a
 * movement gate keeps the body under that ceiling. The gate opens only after
 * the bound Enderman is no longer visible and a newly observed pearl item
 * entity appears. Collection then uses the ordinary safe-route pickup skill,
 * and success is confirmed only by an increase in the player's own
 * {@code minecraft:ender_pearl} inventory count.</p>
 *
 * <p>The skill never scans the level for Endermen or drops. A pearl that was
 * already visible when combat began is excluded from the delegated
 * observation stream, so it cannot satisfy this attempt.</p>
 */
public final class AcquireShelteredEnderPearlSkill
        implements Skill<AcquireShelteredEnderPearlParameters> {
    public static final String NAME =
            "acquire_sheltered_ender_pearl";

    private static final String ENDERMAN = "minecraft:enderman";
    private static final String ITEM_ENTITY = "minecraft:item";
    private static final String ENDER_PEARL =
            "minecraft:ender_pearl";
    private static final double MAXIMUM_INITIAL_DISTANCE = 3.0;
    private static final double MAXIMUM_DROP_OFFSET_SQUARED = 16.0;
    private static final double MAXIMUM_MERGED_DROP_OFFSET_SQUARED = 4.0;
    private static final double MAXIMUM_SHELTER_VOXEL_DANGER = 0.08;
    private static final double RISK_EPSILON = 1.0E-6;
    /*
     * This is bounded semantic memory, not a hidden block read. A newly
     * observed contradiction replaces the voxel immediately. Six hundred
     * revisions gives a player enough time to look from the nine roof cells
     * to the Enderman and finish one ordinary melee exchange; the previous
     * 128-revision window could expire between the final roof scan and the
     * very first supervised combat tick.
     */
    static final long MAXIMUM_ROOF_OBSERVATION_AGE = 600;
    private static final double NORMAL_MINIMUM_HEALTH = 0.55;
    private static final double HARDCORE_MINIMUM_HEALTH = 0.80;
    private static final int MINIMUM_FOOD = 7;
    private static final Set<String> ACCEPTED_MELEE_WEAPONS = Set.of(
            "minecraft:stone_sword",
            "minecraft:iron_sword",
            "minecraft:diamond_sword",
            "minecraft:netherite_sword",
            "minecraft:stone_axe",
            "minecraft:iron_axe",
            "minecraft:diamond_axe",
            "minecraft:netherite_axe"
    );

    private final UUID expectedPlayerId;
    private final CoreSkillFrameSource rawCoreFrames;
    private final InteractionSkillFrameSource rawInteractionFrames;
    private final FilteredCoreFrames filteredCoreFrames;
    private final FilteredInteractionFrames filteredInteractionFrames;
    private final MovementGateCoreActuator movementGate;
    private final EngageAndCollectObservedDropSkill delegate;
    private final LootPickupReceiptSource pickupReceipts;

    private EngageAndCollectParameters delegateParameters;
    private UUID boundEndermanId;
    private DimensionRef boundDimension;
    private PerceptionVec3 lastEndermanPosition;
    private Set<UUID> preexistingPearlIds = Set.of();
    private UUID authorizedPearlId;
    private long collectionReleasedAtObservationRevision = -1L;
    private int initialPearlCount;
    private long pickupReceiptWatermark;
    private boolean collectionReleased;
    private boolean pickupReceiptAuthorized;
    private boolean cancelled;
    private SkillFailure wrapperFailure;

    public AcquireShelteredEnderPearlSkill(
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
                VanillaLootReceiptLedger.source()
        );
    }

    AcquireShelteredEnderPearlSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final LootPickupReceiptSource pickupReceipts
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        rawCoreFrames = Objects.requireNonNull(
                coreFrames,
                "coreFrames"
        );
        rawInteractionFrames = Objects.requireNonNull(
                interactionFrames,
                "interactionFrames"
        );
        filteredCoreFrames = new FilteredCoreFrames(
                this,
                rawCoreFrames
        );
        filteredInteractionFrames =
                new FilteredInteractionFrames(
                        this,
                        rawInteractionFrames
                );
        movementGate = new MovementGateCoreActuator(
                Objects.requireNonNull(core, "core")
        );
        this.pickupReceipts = Objects.requireNonNull(
                pickupReceipts,
                "pickupReceipts"
        );
        delegate = new EngageAndCollectObservedDropSkill(
                expectedPlayerId,
                movementGate,
                filteredCoreFrames,
                Objects.requireNonNull(
                        interactions,
                        "interactions"
                ),
                filteredInteractionFrames,
                EngageObservedEntitySkill::standardCombatTarget,
                true,
                true,
                this::allowsShelteredCollectionRisk
        );
    }

    @Override
    public SkillParameterParser<AcquireShelteredEnderPearlParameters>
            parameters() {
        return LootSkillParameters::parseShelteredEnderPearl;
    }

    @Override
    public boolean managesVisibleHostileProximity() {
        if (delegateParameters == null) {
            return false;
        }
        if (!delegate.awaitingObservedDrop()) {
            return true;
        }
        /*
         * Once a newly observed, causally authorized pearl has released the
         * collection lease, the body is still inside the proven shelter and
         * the active skill must be allowed to renew its vanilla movement
         * input.  Emergency hostile reacquisition is still retained whenever
         * an unrelated hostile/projectile is actually visible; the bound
         * Enderman is the already-authorized target of this compound skill.
         */
        if (!collectionReleased || boundEndermanId == null) {
            return false;
        }
        return ownedRawFrame()
                .map(frame -> frame.visibleEntities().stream()
                        .filter(entity -> !boundEndermanId.equals(
                                entity.entityId()
                        ))
                        .noneMatch(entity -> entity.hostile()
                                || entity.projectile()))
                .orElse(false);
    }

    @Override
    public boolean managesPhysicalContactThreats() {
        return delegateParameters != null
                && !delegate.awaitingObservedDrop();
    }

    @Override
    public OptionalDouble hardcoreRiskThresholdOverride(
            final SkillContext context,
            final AcquireShelteredEnderPearlParameters parameters
    ) {
        if (!context.hardcore()) {
            return OptionalDouble.empty();
        }
        final Optional<CoreSkillFrame> frame = ownedRawFrame();
        if (frame.isEmpty()) {
            return OptionalDouble.empty();
        }
        UUID allowed = boundEndermanId;
        if (allowed == null) {
            final Resolution resolution = resolve(parameters);
            if (resolution.failure().isPresent()) {
                return OptionalDouble.empty();
            }
            allowed = resolution.target()
                    .orElseThrow()
                    .entityId();
        }
        return shelterSafetyFailure(
                context,
                frame.orElseThrow(),
                allowed
        ).isEmpty()
                ? OptionalDouble.of(1.0)
                : OptionalDouble.empty();
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final AcquireShelteredEnderPearlParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        filteredCoreFrames.configure(Set.of(), null, false);
        filteredInteractionFrames.configure(
                Set.of(),
                null,
                false
        );
        final Resolution resolution = resolve(parameters);
        if (resolution.failure().isPresent()) {
            return resolution.failure();
        }
        final CoreSkillFrame frame =
                resolution.frame().orElseThrow();
        final Optional<SkillFailure> safety =
                shelterSafetyFailure(
                        context,
                        frame,
                        resolution.target()
                            .orElseThrow()
                            .entityId()
                );
        if (safety.isPresent()) {
            return safety;
        }
        return delegate.preconditions(
                context,
                delegateParameters(parameters)
        );
    }

    @Override
    public void start(
            final SkillContext context,
            final AcquireShelteredEnderPearlParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Resolution resolution = resolve(parameters);
        if (resolution.failure().isPresent()
                || shelterSafetyFailure(
                    context,
                    resolution.frame().orElseThrow(),
                    resolution.target()
                        .orElseThrow()
                        .entityId()
                ).isPresent()) {
            throw new IllegalStateException(
                    "Sheltered Enderman observation changed before start"
            );
        }
        final CoreSkillFrame frame =
                resolution.frame().orElseThrow();
        final VisibleEntity target =
                resolution.target().orElseThrow();
        final Set<UUID> preexistingPearls =
                visiblePearlIds(frame);

        filteredCoreFrames.configure(
                Set.of(),
                null,
                false
        );
        filteredInteractionFrames.configure(
                Set.of(),
                null,
                false
        );
        movementGate.lock();
        final EngageAndCollectParameters nextDelegateParameters =
                delegateParameters(parameters);
        delegate.start(context, nextDelegateParameters);

        delegateParameters = nextDelegateParameters;
        boundEndermanId = target.entityId();
        boundDimension = frame.dimension();
        lastEndermanPosition = target.position();
        preexistingPearlIds = preexistingPearls;
        authorizedPearlId = null;
        collectionReleasedAtObservationRevision = -1L;
        initialPearlCount = inventoryCount(frame);
        pickupReceiptWatermark =
                pickupReceipts.latestSequence();
        collectionReleased = false;
        pickupReceiptAuthorized = false;
        cancelled = false;
        wrapperFailure = null;

        filteredCoreFrames.configure(
                preexistingPearls,
                null,
                true
        );
        filteredInteractionFrames.configure(
                preexistingPearls,
                null,
                true
        );
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final AcquireShelteredEnderPearlParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (wrapperFailure != null) {
            return SkillTickResult.failed(wrapperFailure);
        }
        if (delegateParameters == null
                || boundEndermanId == null
                || boundDimension == null) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        final CoreSkillFrame frame = ownedRawFrame().orElse(null);
        if (frame == null) {
            return failAndCancel(
                    context,
                    NAME + ".body_unavailable"
            );
        }
        if (!boundDimension.equals(frame.dimension())) {
            return failAndCancel(
                    context,
                    NAME + ".dimension_changed"
            );
        }
        final Optional<VisibleEntity> boundTarget =
                frame.visibleEntities().stream()
                    .filter(entity ->
                            boundEndermanId.equals(entity.entityId())
                    )
                    .findFirst();
        final boolean boundTargetVisible =
                boundTarget.isPresent();
        boundTarget.ifPresent(entity ->
                lastEndermanPosition = entity.position()
        );
        final int currentPearlCount = inventoryCount(frame);
        if (currentPearlCount > initialPearlCount
                && !collectionReleased) {
            final Optional<UUID> correlatedDrop =
                    correlatedPickup(
                            frame,
                            currentPearlCount
                                - initialPearlCount
                    );
            if (correlatedDrop.isEmpty()) {
                return failAndCancel(
                        context,
                        NAME + ".unverified_pearl_increment"
                );
            }
            authorizedPearlId =
                    correlatedDrop.orElseThrow();
            filteredCoreFrames.configure(
                    preexistingPearlIds,
                    authorizedPearlId,
                    true
            );
            filteredInteractionFrames.configure(
                    preexistingPearlIds,
                    authorizedPearlId,
                    true
            );
            collectionReleased = true;
            collectionReleasedAtObservationRevision =
                    frame.observationRevision();
            pickupReceiptAuthorized = true;
            movementGate.release();
        }
        if (collectionReleased
                && boundTargetVisible
                && !pickupReceiptAuthorized
                && frame.observationRevision()
                    > collectionReleasedAtObservationRevision) {
            return failAndCancel(
                    context,
                    NAME + ".target_reappeared"
            );
        }
        if (currentPearlCount > initialPearlCount) {
            final SkillTickResult result =
                    delegate.tick(context, delegateParameters);
            return mapDelegateResult(result);
        }
        final Optional<VisibleEntity> newPearl =
                frame.visibleEntities().stream()
                    .filter(AcquireShelteredEnderPearlSkill
                            ::isPearlDrop)
                    .filter(entity ->
                            !preexistingPearlIds.contains(
                                    entity.entityId()
                            )
                    )
                    .filter(entity ->
                            lastEndermanPosition != null
                                    && entity.position()
                                        .subtract(lastEndermanPosition)
                                        .lengthSquared()
                                        <= MAXIMUM_DROP_OFFSET_SQUARED
                    )
                    .min(java.util.Comparator
                            .comparingDouble(entity ->
                                    entity.position()
                                        .subtract(lastEndermanPosition)
                                        .lengthSquared()
                            ));

        if (!collectionReleased) {
            final Optional<SkillFailure> safety =
                    shelterSafetyFailure(
                            context,
                            frame,
                            boundEndermanId
                    );
            if (safety.isPresent()) {
                return failAndCancel(
                        context,
                        safety.orElseThrow().code()
                );
            }
            if (delegate.awaitingObservedDrop()
                    && newPearl.isPresent()) {
                /*
                 * The combat child has already completed its authorized
                 * target.  The target UUID can remain in one semantic frame
                 * while the death/drop transaction is settling; refusing to
                 * release here strands CollectObservedItemSkill behind its
                 * movement gate and loses the still-visible stack.  The
                 * pearl is new, causally position-correlated, first-person
                 * visible, and shelterSafetyFailure() above has already
                 * rejected every unrelated hostile, projectile, contact,
                 * body hazard, and unsafe roof state.
                 */
                authorizedPearlId =
                        newPearl.orElseThrow().entityId();
                filteredCoreFrames.configure(
                        preexistingPearlIds,
                        authorizedPearlId,
                        true
                );
                filteredInteractionFrames.configure(
                        preexistingPearlIds,
                        authorizedPearlId,
                        true
                );
                collectionReleased = true;
                collectionReleasedAtObservationRevision =
                        frame.observationRevision();
                movementGate.release();
            }
        }

        final SkillTickResult result =
                delegate.tick(context, delegateParameters);
        return mapDelegateResult(result);
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final AcquireShelteredEnderPearlParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"resource\":"
                            + "\"ender_pearl\",\"collectionReleased\":%s,"
                            + "\"dropAuthorized\":%s,"
                            + "\"pickupReceiptAuthorized\":%s}",
                        delegateParameters == null
                                ? "IDLE"
                                : "ACTIVE",
                        collectionReleased,
                        authorizedPearlId != null,
                        pickupReceiptAuthorized
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final AcquireShelteredEnderPearlParameters parameters
    ) {
        if (delegateParameters != null) {
            delegate.cancel(context, delegateParameters);
        }
        clearState();
        cancelled = true;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final AcquireShelteredEnderPearlParameters parameters
    ) {
        if (wrapperFailure != null) {
            return SkillResult.failed(wrapperFailure);
        }
        if (cancelled) {
            return SkillResult.cancelled();
        }
        if (delegateParameters == null) {
            return SkillResult.failed(
                    SkillFailure.of(NAME + ".invalid_state")
            );
        }
        return delegate.result(context, delegateParameters);
    }

    private Resolution resolve(
            final AcquireShelteredEnderPearlParameters parameters
    ) {
        final Optional<CoreSkillFrame> maybeFrame =
                ownedRawFrame();
        if (maybeFrame.isEmpty()) {
            return Resolution.failed(NAME + ".body_unavailable");
        }
        final CoreSkillFrame frame = maybeFrame.orElseThrow();
        if (frame.observationRevision()
                != parameters.sampleSequence()) {
            return Resolution.failed(
                    NAME + ".stale_observation_id"
            );
        }
        final int index = parameters.observationIndex();
        if (index < 0
                || index >= frame.visibleEntities().size()) {
            return Resolution.failed(
                    NAME + ".invalid_observation_id"
            );
        }
        final VisibleEntity target =
                frame.visibleEntities().get(index);
        if (!ENDERMAN.equals(target.entityTypeId())
                || !target.hostile()
                || target.projectile()) {
            return Resolution.failed(
                    NAME + ".visible_hostile_enderman_required"
            );
        }
        if (target.distance() > MAXIMUM_INITIAL_DISTANCE) {
            return Resolution.failed(
                    NAME + ".enderman_out_of_shelter_reach"
            );
        }
        if (!Boolean.parseBoolean(
                target.visibleProperties().getOrDefault(
                        "interactionLineClear",
                        "false"
                )
        )) {
            return Resolution.failed(
                    NAME + ".clear_melee_line_required"
            );
        }
        return Resolution.resolved(frame, target);
    }

    private Optional<SkillFailure> shelterSafetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame,
            final UUID allowedEndermanId
    ) {
        final Optional<SkillFailure> danger =
                dangerFailure(
                        context,
                        frame,
                        allowedEndermanId
                );
        if (danger.isPresent()) {
            return danger;
        }
        final double healthFraction =
                frame.health() / frame.maxHealth();
        final double minimumHealth = context.hardcore()
                ? HARDCORE_MINIMUM_HEALTH
                : NORMAL_MINIMUM_HEALTH;
        if (healthFraction < minimumHealth) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_reserve_required"
            ));
        }
        if (frame.foodLevel() < MINIMUM_FOOD) {
            return Optional.of(SkillFailure.of(
                    NAME + ".food_reserve_required"
            ));
        }
        if (!ACCEPTED_MELEE_WEAPONS.contains(
                frame.mainHand().itemId()
        )) {
            return Optional.of(SkillFailure.of(
                    NAME + ".melee_weapon_required"
            ));
        }
        if (!hasObservedTwoBlockShelter(frame)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".observed_two_block_roof_required"
            ));
        }
        return Optional.empty();
    }

    private Optional<SkillFailure> dangerFailure(
            final SkillContext context,
            final CoreSkillFrame frame,
            final UUID allowedEndermanId
    ) {
        Objects.requireNonNull(
                allowedEndermanId,
                "allowedEndermanId"
        );
        final boolean projectileVisible =
                frame.visibleEntities().stream()
                    .anyMatch(VisibleEntity::projectile);
        if (projectileVisible) {
            return Optional.of(SkillFailure.of(
                    NAME + ".projectile_danger"
            ));
        }
        final boolean otherHostileVisible =
                frame.visibleEntities().stream()
                    .filter(VisibleEntity::hostile)
                    .anyMatch(entity ->
                            !allowedEndermanId.equals(
                                    entity.entityId()
                            )
                                    && !ENDERMAN.equals(
                                        entity.entityTypeId()
                                    )
                    );
        if (otherHostileVisible) {
            return Optional.of(SkillFailure.of(
                    NAME + ".other_hostile_visible"
            ));
        }

        double allowedProximityRisk = 0.0;
        for (DangerSignal signal : frame.dangerSignals()) {
            if (signal.kind() == DangerKind.HOSTILE_PROXIMITY) {
                allowedProximityRisk = Math.max(
                        allowedProximityRisk,
                        signal.severity()
                );
                continue;
            }
            if (signal.kind()
                    == DangerKind.PROJECTILE_PROXIMITY) {
                return Optional.of(SkillFailure.of(
                        NAME + ".projectile_danger"
                ));
            }
            if (signal.kind() == DangerKind.THREAT_CONTACT) {
                return Optional.of(SkillFailure.of(
                        NAME + ".contact_danger"
                ));
            }
            return Optional.of(SkillFailure.of(
                    NAME + ".body_hazard"
            ));
        }
        if (frame.danger()
                    > allowedProximityRisk + RISK_EPSILON
                || context.riskScore()
                    > allowedProximityRisk + RISK_EPSILON) {
            return Optional.of(SkillFailure.of(
                    NAME + ".unattributed_danger"
            ));
        }
        return Optional.empty();
    }

    /**
     * The Enderman wrapper has already proven the only exceptional risk:
     * hostile proximity from the bound Enderman while the body remains under
     * the observed two-block roof.  Collection may reuse that proof, but it
     * must not generalize it to another hostile, projectile, contact, or an
     * unobserved route.
     */
    private boolean allowsShelteredCollectionRisk(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (!context.hardcore() || boundEndermanId == null) {
            return false;
        }
        final boolean otherHostileVisible = frame.visibleEntities().stream()
                .filter(VisibleEntity::hostile)
                .anyMatch(entity ->
                        !boundEndermanId.equals(entity.entityId())
                );
        if (otherHostileVisible
                || frame.visibleEntities().stream()
                    .anyMatch(VisibleEntity::projectile)) {
            return false;
        }
        if (dangerFailure(
                context,
                frame,
                boundEndermanId
        ).isPresent()
                || !hasObservedTwoBlockShelter(frame)) {
            return false;
        }
        final double healthFraction =
                frame.health() / frame.maxHealth();
        return healthFraction >= HARDCORE_MINIMUM_HEALTH
                && frame.foodLevel() >= MINIMUM_FOOD;
    }

    static boolean hasObservedTwoBlockShelter(
            final CoreSkillFrame frame
    ) {
        final GridPos feet = frame.feet();
        if (!frame.onGround()
                || !isCurrentPassable(frame, feet)
                || !isCurrentPassable(frame, feet.above())
                || !isKnownSafeSupport(
                    frame,
                    feet.below(),
                    Long.MAX_VALUE
                )) {
            return false;
        }
        final int roofY = feet.y() + 2;
        for (int xOffset = -1; xOffset <= 1; xOffset++) {
            for (int zOffset = -1; zOffset <= 1; zOffset++) {
                if (!isKnownSafeSupport(
                        frame,
                        new GridPos(
                                feet.x() + xOffset,
                                roofY,
                                feet.z() + zOffset
                        ),
                        MAXIMUM_ROOF_OBSERVATION_AGE
                )) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The nav mapper refreshes the occupied body cells on every semantic
     * sample. Their voxel danger includes the allowed Enderman proximity, so
     * danger is classified from {@link DangerSignal} instead of rejecting the
     * body cells by their aggregate score.
     */
    private static boolean isCurrentPassable(
            final CoreSkillFrame frame,
            final GridPos position
    ) {
        return frame.navigation().voxelAt(position)
                .filter(voxel -> voxel.kind().isPassable())
                .filter(voxel ->
                        voxel.observationRevision()
                            == frame.navigation().revision()
                )
                .isPresent();
    }

    private static boolean isKnownSafeSupport(
            final CoreSkillFrame frame,
            final GridPos position,
            final long maximumAge
    ) {
        return frame.navigation().voxelAt(position)
                .filter(voxel ->
                        voxel.kind().supportsWeight()
                )
                .filter(voxel ->
                        isSafeSupportVoxel(
                                frame,
                                voxel,
                                maximumAge
                        )
                )
                .isPresent();
    }

    private static boolean isSafeSupportVoxel(
            final CoreSkillFrame frame,
            final ObservedVoxel voxel,
            final long maximumAge
    ) {
        final long age = frame.navigation().revision()
                - voxel.observationRevision();
        return age >= 0
                && age <= maximumAge
                && voxel.effectiveDanger()
                    <= MAXIMUM_SHELTER_VOXEL_DANGER;
    }

    private Optional<CoreSkillFrame> ownedRawFrame() {
        return rawCoreFrames.current().filter(frame ->
                expectedPlayerId.equals(frame.playerId())
        );
    }

    private SkillTickResult mapDelegateResult(
            final SkillTickResult result
    ) {
        if (result.status() == SkillTickResult.Status.FAILED) {
            wrapperFailure = result.failure().orElseGet(() ->
                    SkillFailure.of(NAME + ".attempt_failed")
            );
            deactivateObservationFilter();
            return SkillTickResult.failed(wrapperFailure);
        }
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            deactivateObservationFilter();
        }
        return result;
    }

    private SkillTickResult failAndCancel(
            final SkillContext context,
            final String code
    ) {
        if (delegateParameters != null) {
            delegate.cancel(context, delegateParameters);
        }
        wrapperFailure = SkillFailure.of(code);
        deactivateObservationFilter();
        return SkillTickResult.failed(wrapperFailure);
    }

    private void deactivateObservationFilter() {
        movementGate.lock();
        filteredCoreFrames.configure(Set.of(), null, false);
        filteredInteractionFrames.configure(
                Set.of(),
                null,
                false
        );
    }

    private void clearState() {
        delegateParameters = null;
        boundEndermanId = null;
        boundDimension = null;
        lastEndermanPosition = null;
        preexistingPearlIds = Set.of();
        authorizedPearlId = null;
        collectionReleasedAtObservationRevision = -1L;
        initialPearlCount = 0;
        pickupReceiptWatermark = 0L;
        collectionReleased = false;
        pickupReceiptAuthorized = false;
        wrapperFailure = null;
        movementGate.lock();
        filteredCoreFrames.configure(Set.of(), null, false);
        filteredInteractionFrames.configure(
                Set.of(),
                null,
                false
        );
    }

    private static EngageAndCollectParameters delegateParameters(
            final AcquireShelteredEnderPearlParameters parameters
    ) {
        return new EngageAndCollectParameters(
                parameters.sampleSequence(),
                parameters.observationId(),
                ENDER_PEARL,
                parameters.maximumTicks()
        );
    }

    static int inventoryCount(final CoreSkillFrame frame) {
        return frame.inventory().stream()
                .filter(item ->
                        ENDER_PEARL.equals(item.itemId())
                )
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    static boolean isNoDropFailure(final SkillFailure failure) {
        Objects.requireNonNull(failure, "failure");
        return (EngageAndCollectObservedDropSkill.NAME
                + ".expected_drop_not_observed").equals(
                        failure.code()
                );
    }

    private static Set<UUID> visiblePearlIds(
            final CoreSkillFrame frame
    ) {
        final Set<UUID> ids = new HashSet<>();
        frame.visibleEntities().stream()
                .filter(AcquireShelteredEnderPearlSkill
                        ::isPearlDrop)
                .map(VisibleEntity::entityId)
                .forEach(ids::add);
        return Set.copyOf(ids);
    }

    private Optional<UUID> correlatedPickup(
            final CoreSkillFrame frame,
            final int inventoryIncrease
    ) {
        int correlatedCount = 0;
        UUID newestDropId = null;
        long newestSequence = -1L;
        for (LootPickupReceiptSource.Receipt receipt
                : pickupReceipts.receiptsAfter(
                        expectedPlayerId,
                        pickupReceiptWatermark
                )) {
            if (!boundEndermanId.equals(receipt.victimId())
                    || !ENDER_PEARL.equals(receipt.itemId())
                    || !boundDimension.equals(
                            receipt.dimension()
                    )
                    || preexistingPearlIds.contains(
                            receipt.dropEntityId()
                    )) {
                continue;
            }
            correlatedCount += receipt.count();
            if (receipt.sequence() > newestSequence) {
                newestSequence = receipt.sequence();
                newestDropId = receipt.dropEntityId();
            }
        }
        return correlatedCount >= inventoryIncrease
                ? Optional.ofNullable(newestDropId)
                : Optional.empty();
    }

    private static boolean isPearlDrop(
            final VisibleEntity entity
    ) {
        return ITEM_ENTITY.equals(entity.entityTypeId())
                && ENDER_PEARL.equals(
                    entity.visibleProperties().get("itemId")
                );
    }

    private List<VisibleEntity> filterEntities(
            final List<VisibleEntity> entities,
            final Set<UUID> excludedIds,
            final UUID allowedPearlId,
            final boolean enabled
    ) {
        if (!enabled) {
            return entities;
        }
        return entities.stream()
                .filter(entity -> {
                    if (!isPearlDrop(entity)) {
                        return true;
                    }
                    if (allowedPearlId == null) {
                        return false;
                    }
                    if (allowedPearlId.equals(entity.entityId())) {
                        return true;
                    }
                    /*
                     * Vanilla can merge the newly observed drop into an
                     * older nearby ItemEntity and remove the UUID that was
                     * bound at combat completion. A visible survivor near
                     * the proven death site remains fair evidence; allowing
                     * it here lets CollectObservedItemSkill rebind to that
                     * survivor instead of treating a normal merge as a lost
                     * item. The pre-combat exclusion is intentionally
                     * bypassed only inside this bounded merge radius.
                     */
                    return lastEndermanPosition != null
                            && entity.position()
                                .subtract(lastEndermanPosition)
                                .lengthSquared()
                                    <= MAXIMUM_MERGED_DROP_OFFSET_SQUARED;
                }
                )
                .toList();
    }

    private static final class FilteredCoreFrames
            implements CoreSkillFrameSource {
        private final AcquireShelteredEnderPearlSkill owner;
        private final CoreSkillFrameSource delegate;
        private Set<UUID> excludedIds = Set.of();
        private UUID allowedPearlId;
        private boolean enabled;

        private FilteredCoreFrames(
                final AcquireShelteredEnderPearlSkill owner,
                final CoreSkillFrameSource delegate
        ) {
            this.owner = owner;
            this.delegate = delegate;
        }

        private void configure(
                final Set<UUID> ids,
                final UUID allowedId,
                final boolean shouldFilter
        ) {
            excludedIds = Set.copyOf(ids);
            allowedPearlId = allowedId;
            enabled = shouldFilter;
        }

        @Override
        public Optional<CoreSkillFrame> current() {
            return delegate.current().map(frame ->
                    new CoreSkillFrame(
                            frame.playerId(),
                            frame.dimension(),
                            frame.gameTime(),
                            frame.observationRevision(),
                            frame.position(),
                            frame.eyePosition(),
                            frame.lookDirection(),
                            frame.onGround(),
                            frame.inWater(),
                            frame.danger(),
                            frame.navigation(),
                            frame.visibleBlockFaces(),
                            frame.health(),
                            frame.maxHealth(),
                            frame.foodLevel(),
                            frame.inventory(),
                            frame.mainHand(),
                            frame.offHand(),
                            owner.filterEntities(
                                    frame.visibleEntities(),
                                    excludedIds,
                                    allowedPearlId,
                                    enabled
                            ),
                            frame.dangerSignals()
                    )
            );
        }
    }

    private static final class FilteredInteractionFrames
            implements InteractionSkillFrameSource {
        private final AcquireShelteredEnderPearlSkill owner;
        private final InteractionSkillFrameSource delegate;
        private Set<UUID> excludedIds = Set.of();
        private UUID allowedPearlId;
        private boolean enabled;

        private FilteredInteractionFrames(
                final AcquireShelteredEnderPearlSkill owner,
                final InteractionSkillFrameSource delegate
        ) {
            this.owner = owner;
            this.delegate = delegate;
        }

        private void configure(
                final Set<UUID> ids,
                final UUID allowedId,
                final boolean shouldFilter
        ) {
            excludedIds = Set.copyOf(ids);
            allowedPearlId = allowedId;
            enabled = shouldFilter;
        }

        @Override
        public Optional<InteractionSkillFrame> current() {
            return delegate.current().map(frame ->
                    new InteractionSkillFrame(
                            frame.playerId(),
                            frame.dimension(),
                            frame.currentGameTime(),
                            frame.observedAtGameTime(),
                            frame.observationRevision(),
                            frame.sessionGeneration(),
                            frame.mainHand(),
                            frame.offHand(),
                            owner.filterEntities(
                                    frame.visibleEntities(),
                                    excludedIds,
                                    allowedPearlId,
                                    enabled
                            ),
                            frame.visibleBlockFaces(),
                            frame.inventory()
                    )
            );
        }
    }

    /**
     * Keeps combat stationary under the roof while preserving every other
     * vanilla action path. Once released, collection movement is passed
     * through unchanged.
     */
    private static final class MovementGateCoreActuator
            implements CoreSkillActuator {
        private final CoreSkillActuator delegate;
        private boolean movementReleased;

        private MovementGateCoreActuator(
                final CoreSkillActuator delegate
        ) {
            this.delegate = delegate;
        }

        private void lock() {
            movementReleased = false;
            delegate.stop();
        }

        private void release() {
            movementReleased = true;
        }

        @Override
        public ActionOutcome move(final MovementIntent intent) {
            if (!movementReleased
                    && (intent.forward() != 0.0
                    || intent.strafeLeft() != 0.0)) {
                return delegate.stop();
            }
            return delegate.move(intent);
        }

        @Override
        public ActionOutcome look(final LookIntent intent) {
            return delegate.look(intent);
        }

        @Override
        public ActionOutcome jump() {
            return movementReleased
                    ? delegate.jump()
                    : delegate.stop();
        }

        @Override
        public ActionOutcome stop() {
            return delegate.stop();
        }

        @Override
        public ActionOutcome useMainHandOn(
                final BlockInteractionTarget target
        ) {
            return delegate.useMainHandOn(target);
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            return delegate.useItem(hand);
        }

        @Override
        public ActionOutcome releaseUse() {
            return delegate.releaseUse();
        }
    }

    private record Resolution(
            Optional<CoreSkillFrame> frame,
            Optional<VisibleEntity> target,
            Optional<SkillFailure> failure
    ) {
        private static Resolution resolved(
                final CoreSkillFrame frame,
                final VisibleEntity target
        ) {
            return new Resolution(
                    Optional.of(frame),
                    Optional.of(target),
                    Optional.empty()
            );
        }

        private static Resolution failed(final String code) {
            return new Resolution(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }
}
