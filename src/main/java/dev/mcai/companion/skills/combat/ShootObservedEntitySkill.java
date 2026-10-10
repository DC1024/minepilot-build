package dev.mcai.companion.skills.combat;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.perception.HeldItemSummary;
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
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * A bounded first-person bow/crossbow/trident controller.
 *
 * <p>The target UUID is resolved from one fair observation and remains
 * internal. Every shot uses normal item-use, active-use and release paths;
 * this skill cannot create projectiles, damage entities, or bypass
 * ammunition, durability, cooldown, line of sight, or vanilla ballistics.</p>
 */
public final class ShootObservedEntitySkill
        implements Skill<ShootObservedEntityParameters> {
    public static final String NAME = "shoot_observed_entity";
    private static final double MAXIMUM_OBSERVED_TARGET_SPEED = 2.0;
    private static final double MAXIMUM_LEAD_TICKS = 20.0;
    private static final double MAXIMUM_LEAD_DISTANCE = 24.0;
    private static final double VELOCITY_SMOOTHING = 0.65;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator coreActuator;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactionActuator;
    private final InteractionSkillFrameSource interactionFrames;
    private final RangedCombatSkillPolicy policy;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private UUID boundTargetId;
    private String boundTargetType;
    private dev.mcai.companion.waypoint.DimensionRef boundDimension;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;
    private long chargeStartedAtTick = -1;
    private long nextShotTick = -1;
    private int shotsDispatched;
    private WeaponKind weapon;
    private PerceptionVec3 lastTargetPosition;
    private long lastTargetGameTime = -1;
    private long lastTargetObservationRevision = -1;
    private PerceptionVec3 estimatedTargetVelocity =
            new PerceptionVec3(0.0, 0.0, 0.0);
    private boolean targetVelocityMeasured;

    public ShootObservedEntitySkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator coreActuator,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactionActuator,
            final InteractionSkillFrameSource interactionFrames,
            final RangedCombatSkillPolicy policy
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.coreActuator = Objects.requireNonNull(
                coreActuator,
                "coreActuator"
        );
        this.coreFrames = Objects.requireNonNull(
                coreFrames,
                "coreFrames"
        );
        this.interactionActuator = Objects.requireNonNull(
                interactionActuator,
                "interactionActuator"
        );
        this.interactionFrames = Objects.requireNonNull(
                interactionFrames,
                "interactionFrames"
        );
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public SkillParameterParser<ShootObservedEntityParameters>
            parameters() {
        return CombatSkillParameters::parseShoot;
    }

    @Override
    public boolean managesVisibleHostileProximity() {
        return phase != Phase.IDLE
                && phase != Phase.COMPLETED
                && phase != Phase.CANCELLED
                && phase != Phase.FAILED;
    }

    @Override
    public OptionalDouble hardcoreRiskThresholdOverride(
            final SkillContext context,
            final ShootObservedEntityParameters parameters
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
                        policy.maximumDanger(true)
                );
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final ShootObservedEntityParameters parameters
    ) {
        final Resolution resolution = initialResolution(parameters);
        if (resolution.failure().isPresent()) {
            return resolution.failure();
        }
        final Snapshot snapshot =
                resolution.snapshot().orElseThrow();
        final CoreSkillFrame core = snapshot.core();
        final WeaponKind resolved = WeaponKind.from(
                held(core, parameters.hand()).itemId()
        ).orElse(null);
        if (resolved == null) {
            return Optional.of(
                    SkillFailure.of(NAME + ".unsupported_weapon")
            );
        }
        if (!resolved.hasAmmunition(core.inventory())) {
            return Optional.of(
                    SkillFailure.of(NAME + ".ammunition_unavailable")
            );
        }
        final VisibleEntity target =
                resolution.target().orElseThrow();
        if (!interactionLineClear(target)) {
            return Optional.of(
                    SkillFailure.of(
                            NAME + ".interaction_line_blocked"
                    )
            );
        }
        if (target.entityTypeId().equals("minecraft:end_crystal")
                && target.distance()
                        < policy.minimumEndCrystalDistance()) {
            return Optional.of(
                    SkillFailure.of(NAME + ".crystal_too_close")
            );
        }
        return safetyFailure(context, core);
    }

    @Override
    public void start(
            final SkillContext context,
            final ShootObservedEntityParameters parameters
    ) {
        final Resolution resolution = initialResolution(parameters);
        if (resolution.failure().isPresent()) {
            throw new IllegalStateException(
                    "Ranged target changed before start"
            );
        }
        final Snapshot snapshot =
                resolution.snapshot().orElseThrow();
        final VisibleEntity target =
                resolution.target().orElseThrow();
        weapon = WeaponKind.from(
                held(snapshot.core(), parameters.hand()).itemId()
        ).orElseThrow();
        phase = Phase.AIMING;
        failure = null;
        boundTargetId = target.entityId();
        boundTargetType = target.entityTypeId();
        boundDimension = snapshot.core().dimension();
        boundSessionGeneration =
                snapshot.interaction().sessionGeneration();
        startedAtTick = context.gameTick();
        chargeStartedAtTick = -1;
        nextShotTick = context.gameTick();
        shotsDispatched = 0;
        lastTargetPosition = target.position();
        lastTargetGameTime = snapshot.core().gameTime();
        lastTargetObservationRevision =
                snapshot.core().observationRevision();
        estimatedTargetVelocity =
                new PerceptionVec3(0.0, 0.0, 0.0);
        targetVelocityMeasured = false;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final ShootObservedEntityParameters parameters
    ) {
        if (phase != Phase.AIMING
                && phase != Phase.CHARGING
                && phase != Phase.FIRING_CROSSBOW
                && phase != Phase.COOLDOWN) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context, parameters);
        } catch (RuntimeException exception) {
            return fail(NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final ShootObservedEntityParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"sampleSequence\":%d,"
                                + "\"observationId\":\"%s\","
                                + "\"hand\":\"%s\",\"shots\":%d,"
                        + "\"shotsDispatched\":%d,"
                                + "\"weapon\":\"%s\"}",
                        phase.name(),
                        parameters.sampleSequence(),
                        parameters.observationId(),
                        parameters.hand().name(),
                        parameters.shots(),
                        shotsDispatched,
                        weapon == null ? "" : weapon.name()
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final ShootObservedEntityParameters parameters
    ) {
        releaseUse();
        coreActuator.stop();
        phase = Phase.CANCELLED;
        clearBinding();
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final ShootObservedEntityParameters parameters
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
            final ShootObservedEntityParameters parameters
    ) {
        if (context.gameTick() - startedAtTick
                >= policy.maximumSkillTicks()) {
            return fail(NAME + ".timed_out");
        }
        final SnapshotResult current = currentSnapshot();
        if (current.failure().isPresent()) {
            return fail(current.failure().orElseThrow());
        }
        final Snapshot snapshot =
                current.snapshot().orElseThrow();
        final CoreSkillFrame core = snapshot.core();
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, core);
        if (unsafe.isPresent()) {
            return fail(unsafe.orElseThrow());
        }
        if (!held(core, parameters.hand())
                .itemId()
                .equals(weapon.itemId())) {
            return fail(NAME + ".weapon_changed");
        }
        final Optional<VisibleEntity> visible =
                core.visibleEntities().stream()
                        .filter(entity ->
                                entity.entityId().equals(boundTargetId)
                        )
                        .filter(entity ->
                                entity.entityTypeId()
                                        .equals(boundTargetType)
                        )
                        .findFirst();
        if (visible.isEmpty()) {
            return fail(NAME + ".target_lost");
        }
        final VisibleEntity target = visible.orElseThrow();
        if (!interactionLineClear(target)) {
            return fail(NAME + ".interaction_line_blocked");
        }
        if (target.entityTypeId().equals("minecraft:end_crystal")
                && target.distance()
                        < policy.minimumEndCrystalDistance()) {
            return fail(NAME + ".crystal_too_close");
        }
        updateTargetVelocity(core, target);
        /*
         * Keep a dragon shot mobile.  A bow charge can last a full second;
         * stopping for that entire interval makes the companion an
         * unusually easy target for breath and contact damage.  The strafe
         * is deliberately small, player-relative, and only applies to the
         * dragon target. Crystals and ordinary ranged interactions retain
         * the stationary first-person alignment they require.
         */
        final ActionOutcome position =
                "minecraft:ender_dragon".equals(boundTargetType)
                        ? coreActuator.move(new MovementIntent(
                                    0.0,
                                    ((context.gameTick() / 6L) & 1L) == 0L
                                            ? 0.30
                                            : -0.30,
                                    false,
                                    false
                                ))
                        : coreActuator.stop();
        if (!position.accepted()) {
            return fail(NAME + ".position_rejected");
        }
        final PerceptionVec3 aimPoint = aimPoint(
                core,
                target,
                weapon,
                targetVelocityMeasured
                    ? estimatedTargetVelocity
                    : new PerceptionVec3(0.0, 0.0, 0.0)
        );
        final LookIntent look = lookAt(
                core.eyePosition(),
                aimPoint
        );
        if (!coreActuator.look(look).accepted()) {
            return fail(NAME + ".look_rejected");
        }
        final double error = angularError(
                core.lookDirection(),
                aimPoint.subtract(core.eyePosition())
        );
        if (error > policy.aimAlignmentDegrees()) {
            return SkillTickResult.running(true, true);
        }

        if (phase == Phase.COOLDOWN) {
            if (context.gameTick() < nextShotTick) {
                return SkillTickResult.running(false, true);
            }
            phase = Phase.AIMING;
        }
        if (phase == Phase.AIMING) {
            final ActionOutcome use =
                    interactionActuator.useItem(parameters.hand());
            if (!use.accepted()) {
                return fail(actionFailure(use));
            }
            chargeStartedAtTick = context.gameTick();
            phase = Phase.CHARGING;
            return SkillTickResult.running(true, true);
        }
        if (phase == Phase.FIRING_CROSSBOW) {
            final ActionOutcome use =
                    interactionActuator.useItem(parameters.hand());
            if (!use.accepted()) {
                return fail(actionFailure(use));
            }
            interactionActuator.releaseUse();
            return shotCompleted(context, parameters);
        }

        final long chargedTicks =
                context.gameTick() - chargeStartedAtTick;
        final ActionOutcome active =
                interactionActuator.continueUsing(
                        parameters.hand()
                );
        if (weapon == WeaponKind.CROSSBOW
                && active == ActionOutcome.NO_ACTIVE_ACTION) {
            if (chargedTicks <= 2) {
                return shotCompleted(context, parameters);
            }
            phase = Phase.FIRING_CROSSBOW;
            return SkillTickResult.running(true, true);
        }
        if (active != ActionOutcome.IN_PROGRESS
                && !active.accepted()) {
            return fail(NAME + ".use_interrupted");
        }
        if (chargedTicks < weapon.chargeTicks()) {
            return SkillTickResult.running(false, true);
        }
        final ActionOutcome release =
                interactionActuator.releaseUse();
        if (!releaseSucceeded(release)) {
            return fail(actionFailure(release));
        }
        if (weapon == WeaponKind.CROSSBOW) {
            phase = Phase.FIRING_CROSSBOW;
            return SkillTickResult.running(true, true);
        }
        return shotCompleted(context, parameters);
    }

    private SkillTickResult shotCompleted(
            final SkillContext context,
            final ShootObservedEntityParameters parameters
    ) {
        shotsDispatched++;
        chargeStartedAtTick = -1;
        if (shotsDispatched >= parameters.shots()) {
            phase = Phase.COMPLETED;
            clearBinding();
            return SkillTickResult.completed();
        }
        phase = Phase.COOLDOWN;
        nextShotTick = context.gameTick()
                + policy.betweenShotTicks();
        return SkillTickResult.running(true, true);
    }

    private Resolution initialResolution(
            final ShootObservedEntityParameters parameters
    ) {
        final SnapshotResult result = currentSnapshot();
        if (result.failure().isPresent()) {
            return Resolution.failed(
                    result.failure().orElseThrow()
            );
        }
        final Snapshot snapshot =
                result.snapshot().orElseThrow();
        if (snapshot.core().observationRevision()
                != parameters.sampleSequence()) {
            return Resolution.failed(
                    NAME + ".stale_observation_id"
            );
        }
        final int index = parameters.observationIndex();
        if (index < 0
                || index >= snapshot.core()
                        .visibleEntities()
                        .size()
                || index >= snapshot.interaction()
                        .visibleEntities()
                        .size()) {
            return Resolution.failed(
                    NAME + ".invalid_observation_id"
            );
        }
        final VisibleEntity coreTarget = snapshot.core()
                .visibleEntities()
                .get(index);
        final VisibleEntity interactionTarget =
                snapshot.interaction()
                        .visibleEntities()
                        .get(index);
        if (!coreTarget.entityId().equals(
                interactionTarget.entityId()
        )) {
            return Resolution.failed(
                    NAME + ".observation_desynchronized"
            );
        }
        if (!legalTarget(coreTarget)) {
            return Resolution.failed(NAME + ".unsafe_target");
        }
        return Resolution.resolved(snapshot, coreTarget);
    }

    private SnapshotResult currentSnapshot() {
        final Optional<CoreSkillFrame> maybeCore =
                coreFrames.current();
        final Optional<InteractionSkillFrame> maybeInteraction =
                interactionFrames.current();
        if (maybeCore.isEmpty() || maybeInteraction.isEmpty()) {
            return SnapshotResult.failed(
                    NAME + ".observation_unavailable"
            );
        }
        final CoreSkillFrame core = maybeCore.orElseThrow();
        final InteractionSkillFrame interaction =
                maybeInteraction.orElseThrow();
        if (!expectedPlayerId.equals(core.playerId())
                || !expectedPlayerId.equals(
                        interaction.playerId()
                )) {
            return SnapshotResult.failed(NAME + ".player_mismatch");
        }
        if (!core.dimension().equals(interaction.dimension())
                || boundDimension != null
                && !boundDimension.equals(core.dimension())) {
            return SnapshotResult.failed(
                    NAME + ".dimension_mismatch"
            );
        }
        if (core.observationRevision()
                        != interaction.observationRevision()
                || interaction.observationAgeTicks()
                        > policy.maximumObservationAgeTicks()) {
            return SnapshotResult.failed(NAME + ".stale_observation");
        }
        final OptionalLong session =
                interactionActuator.sessionGeneration();
        if (session.isEmpty()
                || session.orElseThrow()
                        != interaction.sessionGeneration()
                || boundSessionGeneration >= 0
                && session.orElseThrow()
                        != boundSessionGeneration) {
            return SnapshotResult.failed(NAME + ".session_mismatch");
        }
        return SnapshotResult.valid(
                new Snapshot(core, interaction)
        );
    }

    private Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame core
    ) {
        final double health = core.health() / core.maxHealth();
        if (health < policy.minimumHealth(context.hardcore())) {
            return Optional.of(
                    SkillFailure.of(NAME + ".health_reserve_low")
            );
        }
        return Math.max(context.riskScore(), core.danger())
                        > policy.maximumDanger(context.hardcore())
                ? Optional.of(
                        SkillFailure.of(NAME + ".danger_too_high")
                )
                : Optional.empty();
    }

    private void releaseUse() {
        final OptionalLong session =
                interactionActuator.sessionGeneration();
        if (session.isPresent()
                && session.orElseThrow()
                        == boundSessionGeneration) {
            interactionActuator.releaseUse();
        }
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        releaseUse();
        coreActuator.stop();
        failure = reason;
        phase = Phase.FAILED;
        clearBinding();
        return SkillTickResult.failed(reason);
    }

    private void clearBinding() {
        boundTargetId = null;
        boundTargetType = null;
        boundDimension = null;
        boundSessionGeneration = -1;
        lastTargetPosition = null;
        lastTargetGameTime = -1;
        lastTargetObservationRevision = -1;
        estimatedTargetVelocity =
                new PerceptionVec3(0.0, 0.0, 0.0);
        targetVelocityMeasured = false;
    }

    private static HeldItemSummary held(
            final CoreSkillFrame frame,
            final ActionHand hand
    ) {
        return hand == ActionHand.MAIN_HAND
                ? frame.mainHand()
                : frame.offHand();
    }

    private static boolean legalTarget(final VisibleEntity entity) {
        return entity.hostile()
                || entity.entityTypeId().equals("minecraft:player")
                || entity.entityTypeId().equals(
                        "minecraft:end_crystal"
                )
                || entity.entityTypeId().equals(
                        "minecraft:ender_dragon"
                );
    }

    private static boolean interactionLineClear(
            final VisibleEntity target
    ) {
        return !"false".equals(
                target.visibleProperties().get(
                    "interactionLineClear"
                )
        );
    }

    private static PerceptionVec3 aimPoint(
            final CoreSkillFrame core,
            final VisibleEntity target,
            final WeaponKind weapon,
            final PerceptionVec3 observedVelocity
    ) {
        final double height = switch (target.entityTypeId()) {
            case "minecraft:ender_dragon" -> 2.0;
            case "minecraft:end_crystal" -> 1.0;
            case "minecraft:pillager" -> 1.5;
            case "minecraft:player",
                    "minecraft:enderman",
                    "minecraft:blaze",
                    "minecraft:skeleton",
                    "minecraft:zombie" -> 1.2;
            default -> 0.8;
        };
        /*
         * The perception sampler already performed the player's collider
         * line-of-sight check against one concrete point on the entity.  Use
         * that same point for the projectile aim whenever it is present.
         * Replacing it with a synthetic feet+height point can move the ray
         * through a one-block tower or iron-bar edge that the sampler did not
         * certify, producing a vanilla arrow embedded in an apparently empty
         * block boundary.  Test fixtures and older observations without the
         * authored coordinates retain the bounded type-height fallback.
         */
        final PerceptionVec3 base = interactionAimPoint(target)
                .orElseGet(() -> target.position()
                        .add(new PerceptionVec3(0.0, height, 0.0)));
        PerceptionVec3 predicted = base;
        double flightTicks = 0.0;
        for (int iteration = 0; iteration < 2; iteration++) {
            final double horizontal = Math.hypot(
                    predicted.x() - core.eyePosition().x(),
                    predicted.z() - core.eyePosition().z()
            );
            flightTicks = Math.min(
                    MAXIMUM_LEAD_TICKS,
                    horizontal / weapon.blocksPerTick()
            );
            PerceptionVec3 lead = observedVelocity.scale(
                    flightTicks
            );
            if (lead.length() > MAXIMUM_LEAD_DISTANCE) {
                lead = lead.normalized().scale(
                        MAXIMUM_LEAD_DISTANCE
                );
            }
            predicted = base.add(lead);
        }
        /*
         * The authored point is a fair collider point, but a bow still loses
         * height under vanilla gravity before it reaches that point. Keep the
         * bounded ballistic correction for multipart dragons as well; without
         * it arrows consistently pass below the small tail/wing colliders.
         */
        final double compensation = 0.5
                * weapon.gravityPerTick()
                * flightTicks
                * flightTicks;
        return predicted.add(new PerceptionVec3(
                0.0,
                Math.min(12.0, compensation),
                0.0
        ));
    }

    private static Optional<PerceptionVec3> interactionAimPoint(
            final VisibleEntity target
    ) {
        try {
            final MapValues values = new MapValues(target.visibleProperties());
            return Optional.of(new PerceptionVec3(
                    values.coordinate("interactionAimX"),
                    values.coordinate("interactionAimY"),
                    values.coordinate("interactionAimZ")
            ));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private record MapValues(java.util.Map<String, String> values) {
        private double coordinate(final String key) {
            final String raw = values.get(key);
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("missing " + key);
            }
            final double parsed = Double.parseDouble(raw);
            if (!Double.isFinite(parsed)) {
                throw new IllegalArgumentException("non-finite " + key);
            }
            return parsed;
        }
    }

    /**
     * Estimates motion only from successive fair semantic positions. It does
     * not read entity velocity or any hidden server-world state.
     */
    private void updateTargetVelocity(
            final CoreSkillFrame core,
            final VisibleEntity target
    ) {
        if (core.observationRevision()
                <= lastTargetObservationRevision) {
            return;
        }
        if (lastTargetPosition != null
                && core.gameTime() > lastTargetGameTime) {
            final double elapsed =
                    core.gameTime() - lastTargetGameTime;
            PerceptionVec3 measured = target.position()
                    .subtract(lastTargetPosition)
                    .scale(1.0 / elapsed);
            final double speed = measured.length();
            if (speed > MAXIMUM_OBSERVED_TARGET_SPEED) {
                measured = measured.normalized().scale(
                        MAXIMUM_OBSERVED_TARGET_SPEED
                );
            }
            estimatedTargetVelocity = targetVelocityMeasured
                    ? estimatedTargetVelocity
                        .scale(1.0 - VELOCITY_SMOOTHING)
                        .add(measured.scale(VELOCITY_SMOOTHING))
                    : measured;
            targetVelocityMeasured = true;
        }
        lastTargetPosition = target.position();
        lastTargetGameTime = core.gameTime();
        lastTargetObservationRevision =
                core.observationRevision();
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
        final double dot = current.normalized().dot(
                desired.normalized()
        );
        return Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
    }

    private static boolean releaseSucceeded(
            final ActionOutcome outcome
    ) {
        return outcome.accepted()
                || outcome == ActionOutcome.NO_ACTIVE_ACTION;
    }

    private static String actionFailure(
            final ActionOutcome outcome
    ) {
        return NAME + ".action_"
                + outcome.name().toLowerCase(Locale.ROOT);
    }

    private enum Phase {
        IDLE,
        AIMING,
        CHARGING,
        FIRING_CROSSBOW,
        COOLDOWN,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private enum WeaponKind {
        BOW("minecraft:bow", 20, 3.0, 0.05),
        CROSSBOW("minecraft:crossbow", 25, 3.15, 0.05),
        TRIDENT("minecraft:trident", 10, 2.5, 0.05);

        private final String itemId;
        private final int chargeTicks;
        private final double blocksPerTick;
        private final double gravityPerTick;

        WeaponKind(
                final String itemId,
                final int chargeTicks,
                final double blocksPerTick,
                final double gravityPerTick
        ) {
            this.itemId = itemId;
            this.chargeTicks = chargeTicks;
            this.blocksPerTick = blocksPerTick;
            this.gravityPerTick = gravityPerTick;
        }

        static Optional<WeaponKind> from(final String itemId) {
            for (WeaponKind value : values()) {
                if (value.itemId.equals(itemId)) {
                    return Optional.of(value);
                }
            }
            return Optional.empty();
        }

        boolean hasAmmunition(
                final java.util.List<InventoryItemSummary> inventory
        ) {
            if (this == TRIDENT) {
                return true;
            }
            return inventory.stream().anyMatch(item ->
                    item.itemId().equals("minecraft:arrow")
                            || item.itemId().equals(
                                    "minecraft:spectral_arrow"
                            )
                            || item.itemId().equals(
                                    "minecraft:tipped_arrow"
                            )
                            || this == CROSSBOW
                            && item.itemId().equals(
                                    "minecraft:firework_rocket"
                            )
            );
        }

        String itemId() {
            return itemId;
        }

        int chargeTicks() {
            return chargeTicks;
        }

        double blocksPerTick() {
            return blocksPerTick;
        }

        double gravityPerTick() {
            return gravityPerTick;
        }
    }

    private record Snapshot(
            CoreSkillFrame core,
            InteractionSkillFrame interaction
    ) {
    }

    private record SnapshotResult(
            Optional<Snapshot> snapshot,
            Optional<SkillFailure> failure
    ) {
        static SnapshotResult valid(final Snapshot snapshot) {
            return new SnapshotResult(
                    Optional.of(snapshot),
                    Optional.empty()
            );
        }

        static SnapshotResult failed(final String code) {
            return new SnapshotResult(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }

    private record Resolution(
            Optional<Snapshot> snapshot,
            Optional<VisibleEntity> target,
            Optional<SkillFailure> failure
    ) {
        static Resolution resolved(
                final Snapshot snapshot,
                final VisibleEntity target
        ) {
            return new Resolution(
                    Optional.of(snapshot),
                    Optional.of(target),
                    Optional.empty()
            );
        }

        static Resolution failed(final String code) {
            return failed(SkillFailure.of(code));
        }

        static Resolution failed(final SkillFailure failure) {
            return new Resolution(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(failure)
            );
        }
    }
}
