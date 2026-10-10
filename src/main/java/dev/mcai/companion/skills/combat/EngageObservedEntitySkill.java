package dev.mcai.companion.skills.combat;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionMath;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.ObservedVoxel;
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
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A fair 20 TPS melee controller for exactly one observed target.
 *
 * <p>The model supplies only an observation sequence and public observation
 * ID. The UUID resolved from that immutable sample never appears in a
 * checkpoint, parameter, result, or planner guide. Later ticks may reacquire
 * only that UUID from new ordinary semantic observations; they never enumerate
 * the level, expand a target radius, or switch to another entity. The final
 * attack still goes through the crosshair/reach/occlusion checks of the vanilla
 * player action path.</p>
 */
public final class EngageObservedEntitySkill
        implements Skill<EngageObservedEntityParameters> {
    private static final String NAME = "engage_observed_entity";
    private static final int[][] CARDINALS = {
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
    };
    private static final float[] CHASE_SCAN_YAW_OFFSETS = {
            0.0F, -25.0F, 25.0F, -45.0F, 45.0F
    };
    private static final double HORIZONTAL_EPSILON = 1.0E-9;
    /*
     * Give a golem/player duel a real shielded disengage window before the
     * first damage trade.  Four ticks only raised the shield for one packet
     * round; a vanilla knockback swing could still land before the body had
     * created any separation.  Twenty ticks is one ordinary second at 20
     * TPS, still bounded and fully driven by local fair movement.
     */
    private static final int HIGH_IMPACT_SHIELD_WARMUP_TICKS = 20;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator coreActuator;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactionActuator;
    private final InteractionSkillFrameSource interactionFrames;
    private final CombatSkillPolicy policy;
    private final Predicate<VisibleEntity> targetAuthorization;
    private final boolean managesNearbyHostiles;
    private final boolean overridesHardcoreRisk;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private UUID boundTargetId;
    private String boundTargetType;
    private DimensionRef boundDimension;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;
    private long lostSinceTick = -1;
    private long nextScanTick = -1;
    private long lastObservationRevision = -1;
    private PerceptionVec3 lastSeenTargetPosition;
    private long lastSeenTargetTick = -1;
    private int scanTurns;
    private LookIntent searchBaseLook;
    private int chaseScanTurns;
    private long nextChaseScanTick = -1;
    private int attacksDispatched;
    private ActionHand activeShieldHand;
    private long shieldWarmupUntilTick = -1L;

    public EngageObservedEntitySkill(
            UUID expectedPlayerId,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            InteractionSkillActuator interactionActuator,
            InteractionSkillFrameSource interactionFrames,
            CombatSkillPolicy policy
    ) {
        this(
                expectedPlayerId,
                coreActuator,
                coreFrames,
                interactionActuator,
                interactionFrames,
                policy,
                EngageObservedEntitySkill::standardCombatTarget,
                true,
                true
        );
    }

    /**
     * Composition boundary for a more restrictive, resource-specific target
     * policy. Callers cannot broaden attacks silently: the supplied policy is
     * checked both when the immutable observation is bound and on every later
     * visible sample of that same UUID.
     */
    public EngageObservedEntitySkill(
            UUID expectedPlayerId,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            InteractionSkillActuator interactionActuator,
            InteractionSkillFrameSource interactionFrames,
            CombatSkillPolicy policy,
            Predicate<VisibleEntity> targetAuthorization,
            boolean managesNearbyHostiles,
            boolean overridesHardcoreRisk
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.coreActuator = Objects.requireNonNull(
                coreActuator,
                "coreActuator"
        );
        this.coreFrames = Objects.requireNonNull(coreFrames, "coreFrames");
        this.interactionActuator = Objects.requireNonNull(
                interactionActuator,
                "interactionActuator"
        );
        this.interactionFrames = Objects.requireNonNull(
                interactionFrames,
                "interactionFrames"
        );
        this.policy = Objects.requireNonNull(policy, "policy");
        this.targetAuthorization = Objects.requireNonNull(
                targetAuthorization,
                "targetAuthorization"
        );
        this.managesNearbyHostiles = managesNearbyHostiles;
        this.overridesHardcoreRisk = overridesHardcoreRisk;
    }

    @Override
    public SkillParameterParser<EngageObservedEntityParameters> parameters() {
        return CombatSkillParameters::parseEngage;
    }

    @Override
    public boolean managesVisibleHostileProximity() {
        /*
         * High-impact encounters (iron golems and explicitly authorized
         * player duels) must not suppress the local survival arbiter's
         * contact lane.  The model-authored skill still owns fair targeting
         * and attacks, but once vanilla damage/contact is observed the
         * emergency controller may raise the shield, separate, or issue a
         * bounded counterattack.  Keeping that preemption path is what a
         * real teammate does when a planned exchange turns into an
         * unexpected knockback/death spiral.
         */
        return managesNearbyHostiles
                && !highImpactTarget()
                && (phase == Phase.ENGAGING
                || phase == Phase.SEARCHING
                || phase == Phase.RETREATING
                || phase == Phase.GUARDING);
    }

    @Override
    public boolean managesPhysicalContactThreats() {
        return managesVisibleHostileProximity();
    }

    @Override
    public OptionalDouble hardcoreRiskThresholdOverride(
            final SkillContext context,
            final EngageObservedEntityParameters parameters
    ) {
        if (!overridesHardcoreRisk) {
            return OptionalDouble.empty();
        }
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
            SkillContext context,
            EngageObservedEntityParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        Resolution resolution = initialResolution(parameters);
        if (resolution.failure().isPresent()) {
            return resolution.failure();
        }
        CoreSkillFrame core = resolution.snapshot()
                .orElseThrow()
                .core();
        if (healthFraction(core)
                <= policy.retreatHealthFraction(context.hardcore())) {
            return Optional.of(SkillFailure.of(NAME + ".low_health"));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            SkillContext context,
            EngageObservedEntityParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        Resolution resolution = initialResolution(parameters);
        if (resolution.failure().isPresent()) {
            throw new IllegalStateException(
                    "Combat observation changed before start"
            );
        }
        Snapshot snapshot = resolution.snapshot().orElseThrow();
        VisibleEntity target = resolution.target().orElseThrow();

        phase = Phase.ENGAGING;
        failure = null;
        boundTargetId = target.entityId();
        boundTargetType = target.entityTypeId();
        boundDimension = snapshot.core().dimension();
        boundSessionGeneration =
                snapshot.interaction().sessionGeneration();
        startedAtTick = context.gameTick();
        lostSinceTick = -1;
        nextScanTick = context.gameTick();
        lastObservationRevision =
                snapshot.core().observationRevision();
        lastSeenTargetPosition = interactionAim(target);
        lastSeenTargetTick = context.gameTick();
        scanTurns = 0;
        searchBaseLook = null;
        chaseScanTurns = 0;
        nextChaseScanTick = context.gameTick();
        attacksDispatched = 0;
        activeShieldHand = null;
        shieldWarmupUntilTick = highImpactTarget()
                && shieldHand(snapshot.core()).isPresent()
                ? context.gameTick() + HIGH_IMPACT_SHIELD_WARMUP_TICKS
                : -1L;
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            EngageObservedEntityParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase != Phase.ENGAGING
                && phase != Phase.SEARCHING
                && phase != Phase.RETREATING
                && phase != Phase.GUARDING) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context);
        } catch (RuntimeException exception) {
            return fail(NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            SkillContext context,
            EngageObservedEntityParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"sampleSequence\":%d,"
                                + "\"observationId\":\"%s\","
                                + "\"attacks\":%d,\"lostSinceTick\":%d}",
                        phase.name(),
                        parameters.sampleSequence(),
                        parameters.observationId(),
                        attacksDispatched,
                        lostSinceTick
                )
        );
    }

    @Override
    public void cancel(
            SkillContext context,
            EngageObservedEntityParameters parameters
    ) {
        quiesce();
        phase = Phase.CANCELLED;
        clearBinding();
    }

    @Override
    public SkillResult result(
            SkillContext context,
            EngageObservedEntityParameters parameters
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

    private SkillTickResult tickSafely(SkillContext context) {
        if (context.gameTick() - startedAtTick
                >= policy.maximumEngagementTicks()) {
            return fail(NAME + ".timed_out");
        }

        SnapshotResult current = currentSnapshot();
        if (current.failure().isPresent()) {
            return fail(current.failure().orElseThrow());
        }
        Snapshot snapshot = current.snapshot().orElseThrow();
        CoreSkillFrame core = snapshot.core();
        if (core.health() <= 0.0F) {
            return fail(NAME + ".player_incapacitated");
        }
        if (core.observationRevision() < lastObservationRevision) {
            return fail(NAME + ".stale_observation");
        }
        lastObservationRevision = core.observationRevision();

        Optional<VisibleEntity> visible = visibleBoundTarget(snapshot);
        if (visible.isEmpty()) {
            if (lastSeenTargetPosition != null
                    && context.gameTick() - lastSeenTargetTick
                        <= Math.max(
                            8,
                            policy.scanIntervalTicks() * 3L
                        )) {
                return reacquireLastSeen(context, core);
            }
            return search(context, core);
        }
        VisibleEntity target = visible.orElseThrow();
        if (!authorizedTarget(target)
                || !target.entityTypeId().equals(boundTargetType)) {
            return fail(NAME + ".target_changed");
        }

        if (context.gameTick() <= shieldWarmupUntilTick) {
            return warmHighImpactShield(context, core, target);
        }

        lostSinceTick = -1;
        lastSeenTargetPosition = interactionAim(target);
        lastSeenTargetTick = context.gameTick();
        scanTurns = 0;
        searchBaseLook = null;
        nextScanTick = context.gameTick();

        double healthFraction = healthFraction(core);
        if (healthFraction
                <= policy.retreatHealthFraction(context.hardcore())) {
            phase = Phase.RETREATING;
            return retreat(context, core, target);
        }

        double distance = target.position()
                .subtract(core.position())
                .length();
        if (distance > policy.preferredMaximumDistance()) {
            phase = Phase.ENGAGING;
            return chase(context, core, target);
        }
        AimResult aim = aimAtPoint(core, interactionAim(target));
        if (!aim.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        if (aim.errorDegrees() > policy.attackAlignmentDegrees()) {
            coreActuator.stop();
            phase = Phase.ENGAGING;
            return SkillTickResult.running(true, true);
        }

        OptionalDouble strength =
                interactionActuator.attackStrengthScale();
        if (strength.isEmpty()) {
            return fail(NAME + ".cooldown_unavailable");
        }
        if (strength.orElseThrow()
                < policy.attackCooldownThreshold()) {
            if ((distance <= policy.guardDistance()
                    || highImpactTarget()
                        && distance <= policy.guardDistance() + 2.0)
                    && shieldHand(core).isPresent()) {
                phase = Phase.GUARDING;
                if (!guard(shieldHand(core).orElseThrow())) {
                    return fail(NAME + ".shield_rejected");
                }
                if (highImpactTarget()) {
                    final SkillTickResult footwork = combatFootwork(
                            context,
                            core,
                            target,
                            distance
                    );
                    if (footwork.status()
                            == SkillTickResult.Status.FAILED) {
                        return footwork;
                    }
                    return SkillTickResult.running(
                            footwork.madeProgress(),
                            true
                    );
                }
                coreActuator.stop();
                return SkillTickResult.running(false, true);
            }
            if (!releaseGuard()) {
                return fail(NAME + ".release_rejected");
            }
            return combatFootwork(context, core, target, distance);
        }

        if (!releaseGuard()) {
            return fail(NAME + ".release_rejected");
        }
        ActionOutcome attack = interactionActuator.attack(
                Objects.requireNonNull(boundTargetId)
        );
        if (attack.accepted()) {
            attacksDispatched++;
            phase = Phase.ENGAGING;
            /*
             * A real melee player does not freeze between fully charged
             * swings. Queue one collision-checked lateral/backward step after
             * the synchronous vanilla attack transaction. This both avoids
             * stationary damage trading and prevents knockback from turning
             * the next several ticks into an inert aim loop.
             */
            final SkillTickResult footwork =
                    combatFootwork(context, core, target, distance);
            if (footwork.status()
                    == SkillTickResult.Status.FAILED) {
                return footwork;
            }
            return SkillTickResult.running(true, true);
        }
        if (transientTargetFailure(attack)) {
            phase = Phase.SEARCHING;
            return SkillTickResult.running(false, true);
        }
        return fail(NAME + ".attack_" + outcomeCode(attack));
    }

    private SkillTickResult combatFootwork(
            final SkillContext context,
            final CoreSkillFrame core,
            final VisibleEntity target,
            final double distance
    ) {
        final PerceptionVec3 toward = horizontal(
                target.position().subtract(core.position())
        );
        if (toward.lengthSquared() <= HORIZONTAL_EPSILON) {
            coreActuator.stop();
            return SkillTickResult.running(false, true);
        }
        final PerceptionVec3 forward = toward.normalized();
        final double sideSign =
                ((context.gameTick() / 12L) & 1L) == 0L
                        ? 1.0
                        : -1.0;
        final PerceptionVec3 lateral = new PerceptionVec3(
                -forward.z() * sideSign,
                0.0,
                forward.x() * sideSign
        );
        final PerceptionVec3 desired;
        if (highImpactTarget()
                && distance < policy.guardDistance() + 2.0) {
            desired = forward.scale(-1.0)
                    .add(lateral.scale(0.35));
        } else if (distance < policy.tooCloseDistance()) {
            desired = forward.scale(-1.0)
                    .add(lateral.scale(0.35));
        } else {
            desired = lateral.add(
                    forward.scale(
                            context.hardcore() ? -0.20 : 0.08
                    )
            );
        }
        Optional<PerceptionVec3> step = safeStep(
                core,
                desired,
                policy.maximumStepDanger(context.hardcore())
        );
        if (step.isEmpty()) {
            coreActuator.stop();
            return SkillTickResult.running(false, true);
        }
        final MovementIntent movement = relativeMovement(
                core.lookDirection(),
                step.orElseThrow().subtract(core.position())
        );
        if (!coreActuator.move(movement).accepted()) {
            return fail(NAME + ".footwork_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult chase(
            SkillContext context,
            CoreSkillFrame core,
            VisibleEntity target
    ) {
        PerceptionVec3 toward = horizontal(
                target.position().subtract(core.position())
        );
        final double distance = target.position()
                .subtract(core.position())
                .length();
        /*
         * Iron golems and an explicitly authorized player duel have a much
         * larger punishment window than an ordinary zombie.  A vanilla
         * player approaches those targets with the shield already raised,
         * then releases it only once the sword can reach.  Without this
         * short shielded approach the body can walk into a golem's swing
         * during the same tick that the target becomes active, which is a
         * genuine Hardcore death rather than a planning failure.  The shield
         * remains an ordinary use-item action; it does not grant immunity or
         * change the target's attack rules.
         */
        final Optional<ActionHand> approachShield =
                shieldHand(core).filter(ignored ->
                        highImpactTarget()
                                && distance <= policy.guardDistance() + 2.0
                );
        if (approachShield.isPresent()) {
            final AimResult targetAim = aimAtPoint(
                    core,
                    interactionAim(target)
            );
            if (!targetAim.accepted()) {
                return fail(NAME + ".look_rejected");
            }
            if (targetAim.errorDegrees()
                    > policy.movementAlignmentDegrees()) {
                coreActuator.stop();
                return SkillTickResult.running(true, true);
            }
            if (!guard(approachShield.orElseThrow())) {
                return fail(NAME + ".shield_rejected");
            }
        } else if (!releaseGuard()) {
            return fail(NAME + ".release_rejected");
        }
        Optional<PerceptionVec3> step = safeStep(
                core,
                toward,
                policy.maximumStepDanger(context.hardcore())
        );
        if (step.isEmpty()) {
            return scanForChasePath(
                    context,
                    core,
                    target,
                    toward
            );
        }

        chaseScanTurns = 0;
        nextChaseScanTick = context.gameTick();
        AimResult aim = aimAtFeet(core, step.orElseThrow());
        if (!aim.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        if (aim.errorDegrees() > policy.movementAlignmentDegrees()) {
            coreActuator.stop();
            return SkillTickResult.running(true, true);
        }
        boolean sprint = !context.hardcore()
                && target.distance() > policy.guardDistance()
                && core.foodLevel() > 6;
        ActionOutcome movement = coreActuator.move(
                new MovementIntent(1.0, 0.0, sprint, false)
        );
        if (!movement.accepted()) {
            return fail(NAME + ".move_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private boolean highImpactTarget() {
        return "minecraft:iron_golem".equals(boundTargetType)
                || "minecraft:player".equals(boundTargetType);
    }

    private SkillTickResult warmHighImpactShield(
            final SkillContext context,
            final CoreSkillFrame core,
            final VisibleEntity target
    ) {
        final Optional<ActionHand> shield = shieldHand(core);
        if (shield.isEmpty()) {
            shieldWarmupUntilTick = -1L;
            return SkillTickResult.running(false, true);
        }
        final AimResult aim = aimAtPoint(core, interactionAim(target));
        if (!aim.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        if (aim.errorDegrees() > policy.attackAlignmentDegrees()) {
            coreActuator.stop();
            return SkillTickResult.running(true, true);
        }
        if (!guard(shield.orElseThrow())) {
            return fail(NAME + ".shield_rejected");
        }
        if (highImpactTarget()) {
            /* Keep the crosshair on the target while a bounded backward /
             * lateral step creates room. This is the same collision-checked
             * footwork used between vanilla swings; it is not a teleport or
             * an invulnerability shortcut. */
            final SkillTickResult footwork = combatFootwork(
                    context,
                    core,
                    target,
                    target.position().subtract(core.position()).length()
            );
            if (footwork.status() == SkillTickResult.Status.FAILED) {
                return footwork;
            }
            return SkillTickResult.running(
                    footwork.madeProgress(),
                    true
            );
        }
        return SkillTickResult.running(true, true);
    }

    /**
     * A visible distant target does not prove that the intervening floor can
     * support the player. Deliberately lower and fan the player's own view so
     * the semantic sampler can establish nearby support plus feet/head
     * clearance. This is a bounded first-person observation action, not a
     * level or chunk query.
     */
    private SkillTickResult scanForChasePath(
            SkillContext context,
            CoreSkillFrame core,
            VisibleEntity target,
            PerceptionVec3 toward
    ) {
        if (!coreActuator.stop().accepted()) {
            return fail(NAME + ".stop_rejected");
        }
        if (context.gameTick() < nextChaseScanTick) {
            return SkillTickResult.running(false, true);
        }
        PerceptionVec3 direction = toward.normalized();
        PerceptionVec3 floorAim = new PerceptionVec3(
                core.position().x() + direction.x() * 2.5,
                core.position().y(),
                core.position().z() + direction.z() * 2.5
        );
        LookIntent direct = lookAt(core.eyePosition(), floorAim);
        float yawOffset = CHASE_SCAN_YAW_OFFSETS[
                chaseScanTurns % CHASE_SCAN_YAW_OFFSETS.length
        ];
        LookIntent scan = new LookIntent(
                ActionMath.wrapDegrees(
                        direct.yawDegrees() + yawOffset
                ),
                Math.max(
                        25.0F,
                        Math.min(55.0F, direct.pitchDegrees())
                )
        );
        if (!coreActuator.look(scan).accepted()) {
            return fail(NAME + ".look_rejected");
        }
        chaseScanTurns++;
        nextChaseScanTick = context.gameTick()
                + policy.scanIntervalTicks();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult retreat(
            SkillContext context,
            CoreSkillFrame core,
            VisibleEntity target
    ) {
        PerceptionVec3 away = horizontal(
                core.position().subtract(target.position())
        );
        Optional<PerceptionVec3> step = safeStep(
                core,
                away,
                policy.maximumStepDanger(context.hardcore())
        );
        AimResult aim = aimAtFeet(core, target.position());
        if (!aim.accepted()) {
            return fail(NAME + ".look_rejected");
        }

        Optional<ActionHand> shield = shieldHand(core);
        if (shield.isPresent()
                && !guard(shield.orElseThrow())) {
            return fail(NAME + ".shield_rejected");
        }
        if (step.isEmpty()
                || aim.errorDegrees()
                > policy.movementAlignmentDegrees()) {
            coreActuator.stop();
            return SkillTickResult.running(
                    shield.isPresent(),
                    true
            );
        }

        MovementIntent movement = relativeMovement(
                core.lookDirection(),
                step.orElseThrow().subtract(core.position())
        );
        if (!coreActuator.move(movement).accepted()) {
            return fail(NAME + ".move_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private static PerceptionVec3 interactionAim(
            final VisibleEntity target
    ) {
        final var properties = target.visibleProperties();
        try {
            final double x = Double.parseDouble(
                    properties.getOrDefault("interactionAimX", "NaN")
            );
            final double y = Double.parseDouble(
                    properties.getOrDefault("interactionAimY", "NaN")
            );
            final double z = Double.parseDouble(
                    properties.getOrDefault("interactionAimZ", "NaN")
            );
            if (Double.isFinite(x)
                    && Double.isFinite(y)
                    && Double.isFinite(z)) {
                return new PerceptionVec3(x, y, z);
            }
        } catch (NumberFormatException ignored) {
            // Fall back to the public entity position below.
        }
        return new PerceptionVec3(
                target.position().x(),
                target.position().y() + 1.0,
                target.position().z()
        );
    }

    private SkillTickResult search(
            SkillContext context,
            CoreSkillFrame core
    ) {
        phase = Phase.SEARCHING;
        if (!releaseGuard()) {
            return fail(NAME + ".release_rejected");
        }
        if (!coreActuator.stop().accepted()) {
            return fail(NAME + ".stop_rejected");
        }
        if (lostSinceTick < 0) {
            lostSinceTick = context.gameTick();
            nextScanTick = context.gameTick();
            scanTurns = 0;
            searchBaseLook = lookFromDirection(core.lookDirection());
        }
        long lostTicks = context.gameTick() - lostSinceTick;
        if (lostTicks >= policy.lostGraceTicks(context.hardcore())) {
            if (attacksDispatched > 0) {
                quiesce();
                phase = Phase.COMPLETED;
                clearBinding();
                return SkillTickResult.completed();
            }
            return fail(NAME + ".target_lost");
        }
        if (context.gameTick() >= nextScanTick
                && scanTurns < policy.maximumScanTurns()) {
            float direction = (scanTurns & 1) == 0 ? 1.0F : -1.0F;
            float magnitude = policy.scanYawDegrees()
                    * (1.0F + scanTurns / 2.0F);
            final LookIntent base = searchBaseLook == null
                    ? lookFromDirection(core.lookDirection())
                    : searchBaseLook;
            ActionOutcome look = coreActuator.look(new LookIntent(
                    ActionMath.wrapDegrees(
                            base.yawDegrees()
                                    + direction * magnitude
                    ),
                    Math.max(
                            -35.0F,
                            Math.min(35.0F, base.pitchDegrees())
                    )
            ));
            if (!look.accepted()) {
                return fail(NAME + ".look_rejected");
            }
            scanTurns++;
            nextScanTick = context.gameTick()
                    + policy.scanIntervalTicks();
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(false, true);
    }

    /**
     * A combat controller deliberately looks down while establishing safe
     * chase footing. That self-authored view change can temporarily move the
     * target outside the semantic frustum. Re-aim briefly at the last fairly
     * seen position instead of misclassifying our own scan as target loss.
     * Movement and attacks remain disabled until a fresh visible sample
     * reacquires the bound UUID.
     */
    private SkillTickResult reacquireLastSeen(
            final SkillContext context,
            final CoreSkillFrame core
    ) {
        phase = Phase.SEARCHING;
        /*
         * Re-aiming at the last fairly observed position is part of the same
         * bounded target-loss window as the wider search fan. Record the first
         * missing tick here so a sparse server tick/test sequence cannot
         * restart the grace period only after last-seen reacquisition expires.
         */
        if (lostSinceTick < 0) {
            lostSinceTick = context.gameTick();
            nextScanTick = context.gameTick();
            scanTurns = 0;
            searchBaseLook = lookFromDirection(core.lookDirection());
        }
        if (!releaseGuard()) {
            return fail(NAME + ".release_rejected");
        }
        if (!coreActuator.stop().accepted()) {
            return fail(NAME + ".stop_rejected");
        }
        final AimResult aim = aimAtPoint(
                core,
                Objects.requireNonNull(lastSeenTargetPosition)
        );
        if (!aim.accepted()) {
            return fail(NAME + ".look_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private Resolution initialResolution(
            EngageObservedEntityParameters parameters
    ) {
        final Optional<InteractionSkillFrame> historicalInteraction =
                interactionFrames.atObservation(
                        parameters.sampleSequence()
                );
        if (historicalInteraction.isEmpty()) {
            return Resolution.failed(
                    NAME + ".stale_observation_id"
            );
        }
        final InteractionSkillFrame observedInteraction =
                historicalInteraction.orElseThrow();
        if (!expectedPlayerId.equals(
                        observedInteraction.playerId()
                )) {
            return Resolution.failed(NAME + ".player_mismatch");
        }
        if (observedInteraction.observationRevision()
                    != parameters.sampleSequence()) {
            return Resolution.failed(
                    NAME + ".observation_desynchronized"
            );
        }
        int index = parameters.observationIndex();
        if (index < 0
                || index >= observedInteraction
                    .visibleEntities().size()) {
            return Resolution.failed(
                    NAME + ".invalid_observation_id"
            );
        }
        final VisibleEntity originallyObserved =
                observedInteraction.visibleEntities().get(index);
        if (!authorizedTarget(originallyObserved)) {
            dev.mcai.companion.MinecraftAiCompanion.LOGGER.info(
                    "Fair combat target rejected: type={}, hostile={}, "
                            + "projectile={}, provenance={}",
                    originallyObserved.entityTypeId(),
                    originallyObserved.hostile(),
                    originallyObserved.projectile(),
                    originallyObserved.provenance()
            );
            return Resolution.failed(NAME + ".unsafe_target");
        }

        /*
         * A network model normally answers several semantic samples after the
         * sample it saw. Resolve the public ID against that exact retained
         * sample, then require the same private UUID to remain visible in the
         * latest ordinary first-person sample before authorizing combat.
         * This avoids both an impossible sub-second response requirement and
         * stale-index retargeting.
         */
        final SnapshotResult current = currentSnapshot();
        if (current.failure().isPresent()) {
            return Resolution.failed(current.failure().orElseThrow());
        }
        final Snapshot snapshot = current.snapshot().orElseThrow();
        final Optional<VisibleEntity> latestCoreTarget =
                snapshot.core().visibleEntities().stream()
                        .filter(entity -> entity.entityId().equals(
                                originallyObserved.entityId()
                        ))
                        .findFirst();
        final Optional<VisibleEntity> latestInteractionTarget =
                snapshot.interaction().visibleEntities().stream()
                        .filter(entity -> entity.entityId().equals(
                                originallyObserved.entityId()
                        ))
                        .findFirst();
        if (latestCoreTarget.isEmpty()
                || latestInteractionTarget.isEmpty()
                || !latestCoreTarget.orElseThrow().entityId().equals(
                    latestInteractionTarget.orElseThrow().entityId()
                )) {
            return Resolution.failed(
                    NAME + ".target_not_currently_visible"
            );
        }
        final VisibleEntity coreTarget =
                latestCoreTarget.orElseThrow();
        if (!coreTarget.entityTypeId().equals(
                    originallyObserved.entityTypeId()
                )
                || !authorizedTarget(coreTarget)) {
            return Resolution.failed(NAME + ".target_changed");
        }
        if (interactionActuator.attackStrengthScale().isEmpty()) {
            return Resolution.failed(
                    NAME + ".cooldown_unavailable"
            );
        }
        return Resolution.resolved(snapshot, coreTarget);
    }

    private SnapshotResult currentSnapshot() {
        Optional<CoreSkillFrame> currentCore = coreFrames.current();
        Optional<InteractionSkillFrame> currentInteraction =
                interactionFrames.current();
        if (currentCore.isEmpty() || currentInteraction.isEmpty()) {
            return SnapshotResult.failed(
                    NAME + ".observation_unavailable"
            );
        }
        CoreSkillFrame core = currentCore.orElseThrow();
        InteractionSkillFrame interaction =
                currentInteraction.orElseThrow();
        if (!expectedPlayerId.equals(core.playerId())
                || !expectedPlayerId.equals(interaction.playerId())) {
            return SnapshotResult.failed(NAME + ".player_mismatch");
        }
        if (!core.dimension().equals(interaction.dimension())
                || boundDimension != null
                && !boundDimension.equals(core.dimension())) {
            return SnapshotResult.failed(NAME + ".dimension_mismatch");
        }
        if (core.observationRevision()
                != interaction.observationRevision()) {
            return SnapshotResult.failed(
                    NAME + ".observation_desynchronized"
            );
        }
        if (interaction.observationAgeTicks()
                > policy.maximumObservationAgeTicks()) {
            return SnapshotResult.failed(NAME + ".stale_observation");
        }
        OptionalLong currentSession =
                interactionActuator.sessionGeneration();
        if (currentSession.isEmpty()) {
            return SnapshotResult.failed(NAME + ".player_unavailable");
        }
        long session = currentSession.orElseThrow();
        if (interaction.sessionGeneration() != session
                || boundSessionGeneration >= 0
                && boundSessionGeneration != session) {
            return SnapshotResult.failed(NAME + ".session_mismatch");
        }
        return SnapshotResult.valid(new Snapshot(core, interaction));
    }

    private Optional<VisibleEntity> visibleBoundTarget(
            Snapshot snapshot
    ) {
        if (boundTargetId == null) {
            return Optional.empty();
        }
        Optional<VisibleEntity> coreTarget =
                snapshot.core().visibleEntities().stream()
                        .filter(entity -> entity.entityId().equals(
                                boundTargetId
                        ))
                        .findFirst();
        Optional<VisibleEntity> interactionTarget =
                snapshot.interaction().visibleEntities().stream()
                        .filter(entity -> entity.entityId().equals(
                                boundTargetId
                        ))
                        .findFirst();
        return coreTarget.isPresent() && interactionTarget.isPresent()
                ? coreTarget
                : Optional.empty();
    }

    private AimResult aimAtFeet(
            CoreSkillFrame frame,
            PerceptionVec3 targetFeet
    ) {
        return aimAtPoint(frame, new PerceptionVec3(
                targetFeet.x(),
                targetFeet.y() + 1.0,
                targetFeet.z()
        ));
    }

    private AimResult aimAtPoint(
            final CoreSkillFrame frame,
            final PerceptionVec3 targetPoint
    ) {
        PerceptionVec3 delta = targetPoint.subtract(frame.eyePosition());
        if (delta.lengthSquared() <= HORIZONTAL_EPSILON) {
            return new AimResult(true, 0.0);
        }
        ActionOutcome outcome = coreActuator.look(
                lookAt(frame.eyePosition(), targetPoint)
        );
        return new AimResult(
                outcome.accepted(),
                angularErrorDegrees(frame.lookDirection(), delta)
        );
    }

    private boolean guard(ActionHand shield) {
        if (activeShieldHand == shield) {
            ActionOutcome continued =
                    interactionActuator.continueUsing(shield);
            if (continued.accepted()) {
                return true;
            }
            if (continued != ActionOutcome.NO_ACTIVE_ACTION) {
                return false;
            }
            activeShieldHand = null;
        } else if (!releaseGuard()) {
            return false;
        }
        ActionOutcome use = coreActuator.useItem(shield);
        if (!use.accepted()) {
            return false;
        }
        activeShieldHand = shield;
        return true;
    }

    private boolean releaseGuard() {
        if (activeShieldHand == null) {
            return true;
        }
        ActionOutcome released = coreActuator.releaseUse();
        activeShieldHand = null;
        return released.accepted()
                || released == ActionOutcome.NO_ACTIVE_ACTION;
    }

    private void quiesce() {
        releaseGuard();
        coreActuator.stop();
    }

    private SkillTickResult fail(String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(SkillFailure reason) {
        quiesce();
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
        activeShieldHand = null;
        shieldWarmupUntilTick = -1L;
        lastSeenTargetPosition = null;
        lastSeenTargetTick = -1;
        searchBaseLook = null;
    }

    private static Optional<ActionHand> shieldHand(CoreSkillFrame frame) {
        if ("minecraft:shield".equals(frame.offHand().itemId())) {
            return Optional.of(ActionHand.OFF_HAND);
        }
        return "minecraft:shield".equals(frame.mainHand().itemId())
                ? Optional.of(ActionHand.MAIN_HAND)
                : Optional.empty();
    }

    public static boolean standardCombatTarget(
            final VisibleEntity target
    ) {
        Objects.requireNonNull(target, "target");
        return !target.projectile()
                && (target.hostile()
                || knownVanillaHostileType(target.entityTypeId())
                || "minecraft:player".equals(target.entityTypeId())
                /*
                 * Iron golems are neutral until provoked, so they do not
                 * implement Enemy and are not marked hostile by the fair
                 * sampler.  They are nevertheless a legitimate explicit
                 * combat target when the model/player asks for a duel.  Keep
                 * this opt-in at the skill boundary; proximity alone must not
                 * turn a village guardian into an automatic target.
                 */
                || "minecraft:iron_golem".equals(target.entityTypeId()));
    }

    /**
     * Keep the fair target gate resilient to a sampler that omits the derived
     * hostile bit for a canonical vanilla monster. The type is already part
     * of the first-person sample; this bounded allow-list does not enumerate
     * entities or broaden the target to arbitrary neutral/passive mobs.
     */
    private static boolean knownVanillaHostileType(
            final String entityTypeId
    ) {
        return switch (entityTypeId) {
            case "minecraft:blaze",
                    "minecraft:cave_spider",
                    "minecraft:creeper",
                    "minecraft:drowned",
                    "minecraft:elder_guardian",
                    "minecraft:ender_dragon",
                    "minecraft:enderman",
                    "minecraft:endermite",
                    "minecraft:evoker",
                    "minecraft:ghast",
                    "minecraft:guardian",
                    "minecraft:hoglin",
                    "minecraft:husk",
                    "minecraft:magma_cube",
                    "minecraft:phantom",
                    "minecraft:piglin_brute",
                    "minecraft:pillager",
                    "minecraft:ravager",
                    "minecraft:shulker",
                    "minecraft:silverfish",
                    "minecraft:skeleton",
                    "minecraft:slime",
                    "minecraft:spider",
                    "minecraft:stray",
                    "minecraft:vex",
                    "minecraft:vindicator",
                    "minecraft:warden",
                    "minecraft:witch",
                    "minecraft:wither",
                    "minecraft:wither_skeleton",
                    "minecraft:zoglin",
                    "minecraft:zombie",
                    "minecraft:zombie_villager",
                    "minecraft:zombified_piglin" -> true;
            default -> false;
        };
    }

    private boolean authorizedTarget(final VisibleEntity target) {
        try {
            return targetAuthorization.test(target);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static double healthFraction(CoreSkillFrame frame) {
        return frame.health() / frame.maxHealth();
    }

    private static Optional<PerceptionVec3> safeStep(
            CoreSkillFrame frame,
            PerceptionVec3 desiredDirection,
            double maximumDanger
    ) {
        PerceptionVec3 horizontal = horizontal(desiredDirection);
        if (horizontal.lengthSquared() <= HORIZONTAL_EPSILON) {
            return Optional.empty();
        }
        PerceptionVec3 normalized = horizontal.normalized();
        GridPos feet = frame.feet();
        return java.util.Arrays.stream(CARDINALS)
                .map(direction -> candidate(
                        frame,
                        feet,
                        direction,
                        normalized,
                        maximumDanger
                ))
                .flatMap(Optional::stream)
                .filter(candidate -> candidate.score() > 0.0)
                .max(Comparator
                        .comparingDouble(StepCandidate::score)
                        .thenComparing(
                                StepCandidate::danger,
                                Comparator.reverseOrder()
                        ))
                .map(StepCandidate::target);
    }

    private static Optional<StepCandidate> candidate(
            CoreSkillFrame frame,
            GridPos feet,
            int[] direction,
            PerceptionVec3 desired,
            double maximumDanger
    ) {
        GridPos destination = feet.offset(
                direction[0],
                0,
                direction[1]
        );
        Optional<ObservedVoxel> body = frame.navigation()
                .voxelAt(destination);
        Optional<ObservedVoxel> head = frame.navigation()
                .voxelAt(destination.above());
        Optional<ObservedVoxel> support = frame.navigation()
                .voxelAt(destination.below());
        if (body.isEmpty()
                || head.isEmpty()
                || support.isEmpty()
                || !body.orElseThrow().kind().isPassable()
                || !head.orElseThrow().kind().isPassable()
                || (!body.orElseThrow().kind().isLiquid()
                && !support.orElseThrow().kind().supportsWeight())) {
            return Optional.empty();
        }
        double danger = Math.max(
                body.orElseThrow().effectiveDanger(),
                head.orElseThrow().effectiveDanger()
        );
        if (danger > maximumDanger) {
            return Optional.empty();
        }
        PerceptionVec3 directionVector = new PerceptionVec3(
                direction[0],
                0.0,
                direction[1]
        );
        double score = desired.dot(directionVector);
        return Optional.of(new StepCandidate(
                new PerceptionVec3(
                        destination.x() + 0.5,
                        frame.position().y(),
                        destination.z() + 0.5
                ),
                score,
                danger
        ));
    }

    private static MovementIntent relativeMovement(
            PerceptionVec3 look,
            PerceptionVec3 desired
    ) {
        PerceptionVec3 horizontalLook = horizontal(look);
        PerceptionVec3 horizontalDesired = horizontal(desired);
        if (horizontalLook.lengthSquared() <= HORIZONTAL_EPSILON
                || horizontalDesired.lengthSquared()
                <= HORIZONTAL_EPSILON) {
            return MovementIntent.STOPPED;
        }
        PerceptionVec3 forward = horizontalLook.normalized();
        PerceptionVec3 direction = horizontalDesired.normalized();
        PerceptionVec3 left = new PerceptionVec3(
                forward.z(),
                0.0,
                -forward.x()
        );
        double localForward = direction.dot(forward);
        double localLeft = direction.dot(left);
        double magnitude = Math.hypot(localForward, localLeft);
        if (magnitude > 1.0) {
            localForward /= magnitude;
            localLeft /= magnitude;
        }
        return new MovementIntent(
                localForward,
                localLeft,
                false,
                false
        );
    }

    private static PerceptionVec3 horizontal(PerceptionVec3 vector) {
        return new PerceptionVec3(vector.x(), 0.0, vector.z());
    }

    private static LookIntent lookAt(
            PerceptionVec3 eye,
            PerceptionVec3 target
    ) {
        PerceptionVec3 delta = target.subtract(eye);
        float yaw = (float) Math.toDegrees(
                Math.atan2(-delta.x(), delta.z())
        );
        float pitch = (float) Math.toDegrees(Math.atan2(
                -delta.y(),
                Math.hypot(delta.x(), delta.z())
        ));
        return new LookIntent(yaw, pitch);
    }

    private static LookIntent lookFromDirection(
            PerceptionVec3 direction
    ) {
        return lookAt(
                new PerceptionVec3(0.0, 0.0, 0.0),
                direction
        );
    }

    private static double angularErrorDegrees(
            PerceptionVec3 current,
            PerceptionVec3 target
    ) {
        double dot = current.normalized().dot(target.normalized());
        return Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
    }

    private static boolean transientTargetFailure(ActionOutcome outcome) {
        return outcome == ActionOutcome.TARGET_NOT_FOUND
                || outcome == ActionOutcome.TARGET_UNLOADED
                || outcome == ActionOutcome.TARGET_OUT_OF_REACH
                || outcome == ActionOutcome.TARGET_OCCLUDED
                || outcome == ActionOutcome.TARGET_CHANGED;
    }

    private static String outcomeCode(ActionOutcome outcome) {
        return outcome.name().toLowerCase(Locale.ROOT);
    }

    private enum Phase {
        IDLE,
        ENGAGING,
        GUARDING,
        RETREATING,
        SEARCHING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private record Snapshot(
            CoreSkillFrame core,
            InteractionSkillFrame interaction
    ) {
        private Snapshot {
            Objects.requireNonNull(core, "core");
            Objects.requireNonNull(interaction, "interaction");
        }
    }

    private record SnapshotResult(
            Optional<Snapshot> snapshot,
            Optional<SkillFailure> failure
    ) {
        private SnapshotResult {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(failure, "failure");
            if (snapshot.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "Snapshot result requires exactly one outcome"
                );
            }
        }

        private static SnapshotResult valid(Snapshot snapshot) {
            return new SnapshotResult(
                    Optional.of(snapshot),
                    Optional.empty()
            );
        }

        private static SnapshotResult failed(String code) {
            return failed(SkillFailure.of(code));
        }

        private static SnapshotResult failed(SkillFailure failure) {
            return new SnapshotResult(
                    Optional.empty(),
                    Optional.of(failure)
            );
        }
    }

    private record Resolution(
            Optional<Snapshot> snapshot,
            Optional<VisibleEntity> target,
            Optional<SkillFailure> failure
    ) {
        private Resolution {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(failure, "failure");
            boolean resolved = snapshot.isPresent() && target.isPresent();
            if (resolved == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "Resolution requires exactly one outcome"
                );
            }
        }

        private static Resolution resolved(
                Snapshot snapshot,
                VisibleEntity target
        ) {
            return new Resolution(
                    Optional.of(snapshot),
                    Optional.of(target),
                    Optional.empty()
            );
        }

        private static Resolution failed(String code) {
            return failed(SkillFailure.of(code));
        }

        private static Resolution failed(SkillFailure failure) {
            return new Resolution(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(failure)
            );
        }
    }

    private record AimResult(boolean accepted, double errorDegrees) {
    }

    private record StepCandidate(
            PerceptionVec3 target,
            double score,
            double danger
    ) {
    }
}
