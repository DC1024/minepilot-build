package dev.mcai.companion.skills.core;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionMath;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Small fail-safe state machine that runs after the selected skill and before
 * the one-tick actuator lease is executed. It may override movement only from
 * player-owned state and the last fair semantic observation.
 *
 * <p>The controller never edits inventory, health/food, or the world
 * directly, queries a level, or follows an occluded threat. It may request an
 * atomic vanilla menu swap through {@link EmergencyEquipmentActuator}, then
 * eat, guard, deploy a water bucket or owned vanilla fall-arrest block, swim
 * upward, or retreat into an adjacent fully observed standable cell.</p>
 */
public final class EmergencySurvivalController {
    private static final double IMMEDIATE_DANGER = 0.50;
    private static final double SAFE_VOXEL_DANGER = 0.10;
    private static final int MAX_EATING_TICKS = 60;
    private static final int MAX_RETREAT_TICKS = 60;
    private static final double MAXIMUM_WATER_REACH = 4.75;
    private static final double MAXIMUM_CLUTCH_COLUMN_OFFSET = 1.45;
    private static final double WATER_ALIGNMENT_DEGREES = 5.0;
    private static final double MINIMUM_CLUTCH_DROP = 2.0;
    private static final double IMMINENT_FALL_SEVERITY = 0.15;
    private static final double EMERGENCY_MELEE_REACH = 3.25;
    private static final double HOSTILE_REACQUIRE_OBSERVATION_RADIUS = 12.0;
    private static final double EMERGENCY_ATTACK_COOLDOWN = 0.85;
    private static final double EMERGENCY_ATTACK_ALIGNMENT_DEGREES = 7.5;
    private static final int MAX_WARNING_REACTION_TICKS = 12;
    /**
     * A directionless physical hit is not enough evidence to choose a
     * particular adjacent voxel, but standing still forever is also not a
     * fair survival policy.  After one complete first-person cardinal scan,
     * issue a short sneaking backstep using only the current look direction.
     * Vanilla collision, gravity and support remain authoritative; this is a
     * bounded separation probe, not a teleport or an unseen-world query.
     */
    private static final int DIRECTIONLESS_DAMAGE_SCAN_TICKS = 4;
    private static final double DIRECTIONLESS_DAMAGE_PROBE_SPEED = 0.45;
    private static final int MAX_HOSTILE_REACQUIRE_TICKS = 120;
    private static final int MAX_ATTACK_FOOTWORK_TICKS = 40;
    private static final double WARNING_SCAN_ALIGNMENT_DEGREES = 20.0;
    /**
     * A recent vanilla damage event carries only the attacker's direction,
     * never its identity or hidden position.  Start the first-person scan at
     * that direction, then use a small bounded fan so a moving hostile can be
     * reacquired without turning the reflex lane into an omniscient radar.
     */
    private static final float[] DAMAGE_DIRECTION_SCAN_OFFSETS = {
        0.0F, -35.0F, 35.0F, -70.0F, 70.0F, 180.0F
    };
    /**
     * If vanilla did not expose a source direction (for example a generic
     * environmental contact), cover the four cardinal sectors relative to
     * the current first-person heading instead of oscillating in one 80°
     * window forever.
     */
    private static final float[] DAMAGE_UNKNOWN_SCAN_OFFSETS = {
        0.0F, 90.0F, 180.0F, -90.0F
    };
    private static final List<String> FALL_CLUTCH_ITEMS = List.of(
            "minecraft:slime_block",
            "minecraft:cobweb",
            "minecraft:hay_block"
    );
    private static final List<String> EMERGENCY_MELEE_WEAPONS =
            List.of(
                    "minecraft:netherite_sword",
                    "minecraft:diamond_sword",
                    "minecraft:iron_sword",
                    "minecraft:stone_sword",
                    "minecraft:golden_sword",
                    "minecraft:wooden_sword",
                    "minecraft:netherite_axe",
                    "minecraft:diamond_axe",
                    "minecraft:iron_axe",
                    "minecraft:stone_axe",
                    "minecraft:golden_axe",
                    "minecraft:wooden_axe"
            );
    private static final int[][] CARDINALS = {
            {-1, 0},
            {0, -1},
            {0, 1},
            {1, 0}
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource frames;
    private final EmergencyEquipmentActuator equipment;
    private final EmergencyMeleeActuator melee;
    private final Runnable preemptTaskControls;

    private State state = State.CLEAR;
    private long stateStartedTick;
    private int eatingBaseline;
    private String eatingItemId;
    private int eatingItemCount;
    private ActionHand activeUseHand;
    private PerceptionVec3 retreatTarget;
    private long retreatRevision = -1;
    private long lastVisibleHostileTick = -1;
    /**
     * Local attack timestamp used as a conservative vanilla cooldown guard.
     * The server-side attack-strength sample is authoritative for dispatch,
     * but retaining this timestamp prevents a clientless packet loop from
     * issuing another attack before the previous attack has had a chance to
     * recharge.  This is also what gives a real shield a continuous warm-up
     * window instead of repeatedly staring at a hostile.
     */
    private long lastEmergencyAttackTick = -1;
    /**
     * Bounded direction of the last fair close hostile observation. It is not
     * an entity id or position; it only permits one short separation step
     * after an Enderman teleports outside the current semantic sample.
     */
    private PerceptionVec3 lastEmergencyAway;

    public EmergencySurvivalController(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                EmergencyEquipmentActuator.unavailable(),
                EmergencyMeleeActuator.unavailable(),
                () -> {
                }
        );
    }

    public EmergencySurvivalController(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            EmergencyEquipmentActuator equipment
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                equipment,
                EmergencyMeleeActuator.unavailable(),
                () -> {
                }
        );
    }

    public EmergencySurvivalController(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            EmergencyEquipmentActuator equipment,
            EmergencyMeleeActuator melee
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                equipment,
                melee,
                () -> {
                }
        );
    }

    /**
     * Creates the production controller with a bounded callback that releases
     * task-owned mining, item-use and vehicle inputs before a new emergency
     * state writes its first body action.
     */
    public EmergencySurvivalController(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            EmergencyEquipmentActuator equipment,
            EmergencyMeleeActuator melee,
            Runnable preemptTaskControls
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.equipment = Objects.requireNonNull(
                equipment,
                "equipment"
        );
        this.melee = Objects.requireNonNull(melee, "melee");
        this.preemptTaskControls = Objects.requireNonNull(
                preemptTaskControls,
                "preemptTaskControls"
        );
    }

    /**
     * Executes at most one bounded intervention for the current server tick.
     */
    public TickReport tick() {
        return tick(false);
    }

    /**
     * Executes one bounded intervention while respecting an active combat
     * skill's ownership of ordinary visible-hostile proximity. Physical
     * contact and projectile threats are never delegated.
     */
    public TickReport tick(
            final boolean visibleHostileProximityManaged
    ) {
        return tick(visibleHostileProximityManaged, false);
    }

    /**
     * Executes one bounded intervention while allowing an active, locally
     * supervised melee skill to retain contact control for its target.
     * Projectile, fire, fall, air, food and health emergencies remain local.
     */
    public TickReport tick(
            final boolean visibleHostileProximityManaged,
            final boolean physicalContactManaged
    ) {
        return tick(
                visibleHostileProximityManaged,
                physicalContactManaged,
                false
        );
    }

    /**
     * Executes one bounded intervention while allowing a specialized active
     * skill to own visible projectile proximity.  Contact, fire, fall, air,
     * food, health and any projectile that is not covered by that explicit
     * declaration remain emergency-owned.
     */
    public TickReport tick(
            final boolean visibleHostileProximityManaged,
            final boolean physicalContactManaged,
            final boolean visibleProjectileThreatManaged
    ) {
        Optional<CoreSkillFrame> current = frames.current();
        if (current.isEmpty()
                || !expectedPlayerId.equals(
                        current.orElseThrow().playerId()
                )) {
            return TickReport.none(state);
        }
        CoreSkillFrame frame = current.orElseThrow();

        /*
         * Retain only the time of a recently visible nearby hostile. This is
         * not an entity handle or a hidden position; it permits a bounded
         * first-person sweep after an Enderman/hostile teleports or slips
         * outside one semantic sample. The twelve-block observation radius
         * keeps the timestamp fresh while the hostile remains in the fair
         * view, even though ordinary melee still requires 3.25 blocks.
         */
        if (frame.visibleEntities().stream().anyMatch(entity ->
                (entity.hostile()
                    || "minecraft:iron_golem".equals(entity.entityTypeId())
                        && neutralCombatLeaseActive(frame))
                    && !entity.projectile()
                    && entity.distance()
                        <= HOSTILE_REACQUIRE_OBSERVATION_RADIUS
        )) {
            lastVisibleHostileTick = frame.gameTime();
        }

        if (danger(frame, DangerKind.LOW_AIR).isPresent()
                && frame.inWater()) {
            return surface(frame);
        }
        if (danger(frame, DangerKind.FALLING).isPresent()) {
            /*
             * A high-impact melee hit (most visibly an iron-golem
             * knockback) can put a player into the vanilla falling state for
             * several ticks.  Falling used to win this priority branch and
             * the controller only held sneak while the attacker delivered
             * the next hit.  If a fair contact target is still visible, keep
             * the owned shield facing that target during the fall.  This is
             * ordinary player input, not fall immunity or a position write;
             * the normal clutch lane remains the fallback when no contact
             * target is known.
            */
            if (!physicalContactManaged) {
                final Optional<VisibleEntity> fallingContact =
                        nearestCombatFallTarget(frame);
                final Optional<ActionHand> fallingShield =
                        shieldHand(frame);
                if (fallingContact.isPresent() && fallingShield.isPresent()) {
                    final PerceptionVec3 delta = frame.position().subtract(
                            fallingContact.orElseThrow().position()
                    );
                    final PerceptionVec3 away = new PerceptionVec3(
                            delta.x(),
                            0.0,
                            delta.z()
                    );
                    final TickReport guarded = guard(
                            frame,
                            fallingShield.orElseThrow(),
                            away.lengthSquared() <= 1.0E-12
                                    ? Optional.empty()
                                    : Optional.of(away.normalized()),
                            true,
                            true
                    );
                    return new TickReport(
                            guarded.intervened(),
                            guarded.state(),
                            "combat_fall_" + guarded.reason()
                    );
                }
            }
            return respondToFall(frame);
        }

        Optional<DangerSignal> threat = mostSevereThreat(
                frame,
                visibleHostileProximityManaged,
                physicalContactManaged,
                visibleProjectileThreatManaged
        );
        boolean burning = danger(frame, DangerKind.ON_FIRE).isPresent();
        final double healthRatio = frame.health() / frame.maxHealth();
        final boolean criticalHealth = healthRatio <= 0.40;
        final boolean physicalContact =
                danger(frame, DangerKind.THREAT_CONTACT).isPresent();
        final boolean localAttackCooldown = lastEmergencyAttackTick >= 0L
                && frame.gameTime() - lastEmergencyAttackTick
                    < emergencyAttackIntervalTicks(frame);
        if (!burning
                && localAttackCooldown
                && !visibleHostileProximityManaged
                && !physicalContactManaged) {
            final Optional<ActionHand> cooldownShield = shieldHand(frame);
            if (cooldownShield.isPresent()) {
                /*
                 * Keep the shield up even when an Enderman teleports out of
                 * the current entity sample immediately after a legal hit.
                 * The bounded local attack timestamp and last fair direction
                 * are the only memory used here; neither carries entity
                 * identity or a hidden position.
                 */
                final TickReport guarded = guard(
                        frame,
                        cooldownShield.orElseThrow(),
                        threatDirection(frame, threat),
                        shouldScanForHostile(
                                frame,
                                visibleHostileProximityManaged
                        ),
                        true
                );
                nearestEmergencyMeleeTarget(frame).ifPresentOrElse(
                        target -> emergencyFootwork(frame, target),
                        () -> {
                            if (lastEmergencyAway != null) {
                                emergencyFootwork(frame, lastEmergencyAway);
                            }
                        }
                );
                return guarded;
            }
        }
        /*
         * A golden apple is an emergency health item, not ordinary hunger
         * food. At critical health a merely nearby hostile must not starve
         * this action forever: eat once physical contact has been broken.
         * Fire, falling, drowning and actual contact still take precedence.
         */
        if (criticalHealth && !burning && !physicalContact) {
            final Optional<ActionHand> heldGolden =
                    emergencyGoldenAppleHand(frame);
            if (state == State.EATING && heldGolden.isPresent()) {
                return continueEating(frame);
            }
            if (heldGolden.isPresent()) {
                return startEating(
                        frame,
                        heldGolden.orElseThrow()
                );
            }
            final Optional<String> ownedGolden =
                    preferredOwnedEmergencyGoldenApple(frame);
            if (ownedGolden.isPresent()) {
                return equipEmergencyItem(
                        frame,
                        ActionHand.MAIN_HAND,
                        ownedGolden.orElseThrow(),
                        State.EQUIPPING_FOOD,
                        "equipping_critical_golden_apple"
                );
            }
        }
        if (burning
                || threat.isPresent()
                || shouldScanForHostile(
                    frame,
                    visibleHostileProximityManaged
                )) {
            return avoidDanger(
                    frame,
                    threat,
                    burning,
                    visibleHostileProximityManaged
            );
        }

        if (state == State.EATING) {
            return continueEating(frame);
        }
        final boolean nutritionNeeded = frame.foodLevel() <= 8
                || healthRatio <= 0.60 && frame.foodLevel() < 20;
        final Optional<ActionHand> food = foodHand(frame);
        final boolean heldCriticalFood = food
                .map(hand -> isAlwaysEdibleEmergency(
                        held(frame, hand).itemId()
                ))
                .orElse(false);
        final boolean inventoryCriticalFood = frame.inventory().stream()
                .anyMatch(item ->
                        isAlwaysEdibleEmergency(item.itemId())
                );
        if (nutritionNeeded
                || criticalHealth
                && (heldCriticalFood || inventoryCriticalFood)) {
            if (food.isPresent()
                    && (frame.foodLevel() < 20
                    || heldCriticalFood)) {
                return startEating(frame, food.orElseThrow());
            }
            final Optional<String> selected =
                    VanillaFoodItems.preferredAvailable(
                            frame.inventory(),
                            criticalHealth
                    );
            if (selected.isPresent()
                    && (frame.foodLevel() < 20
                    || isAlwaysEdibleEmergency(
                            selected.orElseThrow()
                    ))) {
                return equipEmergencyItem(
                        frame,
                        ActionHand.MAIN_HAND,
                        selected.orElseThrow(),
                        State.EQUIPPING_FOOD,
                        "equipping_food"
                );
            }
        }

        clearActiveUse();
        state = State.CLEAR;
        retreatTarget = null;
        retreatRevision = -1;
        if (lastVisibleHostileTick >= 0L
                && frame.gameTime() - lastVisibleHostileTick
                    > MAX_HOSTILE_REACQUIRE_TICKS) {
            lastVisibleHostileTick = -1;
        }
        if (!recentAttackFootwork(frame.gameTime())) {
            lastEmergencyAway = null;
        }
        return TickReport.none(state);
    }

    /**
     * Bounded first-person target used only while a fall is already active.
     * Unlike the ordinary emergency melee selector this also admits a
     * visible iron golem before the contact signal has survived one complete
     * observation frame; the high-impact knockback itself is the reason the
     * shield must come up quickly.  It never exposes or queries an entity
     * outside the current semantic sample.
     */
    private Optional<VisibleEntity> nearestCombatFallTarget(
            final CoreSkillFrame frame
    ) {
        return frame.visibleEntities().stream()
                .filter(entity -> !entity.projectile())
                .filter(entity -> entity.hostile()
                        || "minecraft:iron_golem".equals(
                                entity.entityTypeId()
                        ))
                .filter(entity -> entity.distance() <= 8.0)
                .min(Comparator
                        .comparingDouble(VisibleEntity::distance)
                        .thenComparing(entity ->
                                entity.entityId().toString()
                        ));
    }

    /**
     * Releases any locally held use action after a body-session transition.
     */
    public void reset() {
        clearActiveUse();
        clearEatingSnapshot();
        actuator.stop();
        state = State.CLEAR;
        retreatTarget = null;
        retreatRevision = -1;
        lastVisibleHostileTick = -1;
        lastEmergencyAttackTick = -1;
        lastEmergencyAway = null;
    }

    public State state() {
        return state;
    }

    /**
     * Hands the body back from an atomic skill to the next skill without
     * carrying the previous skill's hostile reacquisition lease into an
     * unrelated action.  The timestamp is only a bounded first-person cue;
     * clearing it at a verified skill boundary cannot hide a fresh threat:
     * the next frame refreshes a visible hostile, recent damage, physical
     * contact, fire, fall, air, and food signals before any skill is allowed
     * to write movement.  Without this handoff a completed melee skill could
     * leave the emergency lane in GUARDING for 120 ticks and legitimately
     * stop a later travel/portal skill even though the arena was empty.
     */
    public void onActiveSkillEnded() {
        lastVisibleHostileTick = -1L;
    }

    private TickReport surface(CoreSkillFrame frame) {
        transition(State.SURFACING, frame.gameTime());
        /* A low-air reflex can run concurrently with a fair atomic farming
         * step.  Jumping out of a one-block irrigation cell is the vanilla
         * action that tramples farmland, and the headless player cannot use
         * the same jump input to rise while it is actually swimming.  Keep
         * this lane to ordinary look/forward input; a dedicated water-clutch
         * skill owns verified escape placement when a jump is truly needed. */
        if (!actuator.look(new LookIntent(
                CoreSkillGeometry.holdLook(frame.lookDirection()).yawDegrees(),
                -75.0F
        )).accepted()
                || !actuator.move(new MovementIntent(
                0.35,
                0.0,
                false,
                false
        )).accepted()) {
            return stopFailure("low_air_action_rejected");
        }
        return TickReport.intervened(state, "low_air");
    }

    private TickReport braceForFall(CoreSkillFrame frame) {
        transition(State.BRACING_FALL, frame.gameTime());
        ActionOutcome outcome = actuator.move(
                new MovementIntent(0.0, 0.0, false, true)
        );
        return outcome.accepted()
                ? TickReport.intervened(state, "falling")
                : stopFailure("fall_action_rejected");
    }

    private TickReport respondToFall(final CoreSkillFrame frame) {
        final Optional<VisibleBlockFace> reachableFallSurface =
                fallClutchSurface(frame);
        final boolean imminent = danger(frame, DangerKind.FALLING)
                .map(DangerSignal::severity)
                .orElse(0.0) >= IMMINENT_FALL_SEVERITY;
        if (waterAllowed(frame)) {
            if ("minecraft:water_bucket".equals(
                    frame.mainHand().itemId()
            )) {
                if (reachableFallSurface.isPresent()) {
                    return deployWater(
                            frame,
                            "fall",
                            reachableFallSurface
                    );
                }
                if (imminent) {
                    return deployWater(frame, "fall");
                }
                return TickReport.none(state);
            }
            if (inventoryContains(frame, "minecraft:water_bucket")) {
                if (reachableFallSurface.isPresent() || imminent) {
                    return equipEmergencyItem(
                            frame,
                            ActionHand.MAIN_HAND,
                            "minecraft:water_bucket",
                            State.EQUIPPING_WATER,
                            "equipping_water_for_fall"
                    );
                }
                return TickReport.none(state);
            }
        }
        final Optional<String> heldClutch =
                preferredHeldFallClutch(frame);
        if (heldClutch.isPresent()) {
            if (reachableFallSurface.isPresent() || imminent) {
                return deployFallClutch(
                        frame,
                        heldClutch.orElseThrow()
                );
            }
            return TickReport.none(state);
        }
        final Optional<String> ownedClutch =
                preferredOwnedFallClutch(frame);
        if (ownedClutch.isPresent()) {
            if (reachableFallSurface.isPresent() || imminent) {
                return equipEmergencyItem(
                        frame,
                        ActionHand.MAIN_HAND,
                        ownedClutch.orElseThrow(),
                        State.EQUIPPING_FALL_CLUTCH,
                        "equipping_fall_clutch"
                );
            }
            return TickReport.none(state);
        }
        return imminent
                ? braceForFall(frame)
                : TickReport.none(state);
    }

    private TickReport avoidDanger(
            CoreSkillFrame frame,
            Optional<DangerSignal> threat,
            boolean burning,
            boolean visibleHostileProximityManaged
    ) {
        /*
         * Do not release an already raised shield merely because the same
         * danger is still present. Vanilla needs several continuous use
         * ticks before a shield can block; releasing and reissuing use every
         * tick looks like guarding in telemetry but never becomes an actual
         * block. transition(...) and every equipment/item branch below
         * already release the old use action when ownership really changes.
         */
        Optional<PerceptionVec3> away = threatDirection(frame, threat);
        final Optional<VisibleEntity> contactTarget =
                nearestEmergencyMeleeTarget(frame);
        final boolean localAttackCooldown = lastEmergencyAttackTick >= 0L
                && frame.gameTime() - lastEmergencyAttackTick
                    < emergencyAttackIntervalTicks(frame);
        /*
         * Keep the shield lease even if the target's next movement puts it
         * just outside the 3.25-block attack radius. A real player does not
         * drop a shield during that short recharge window merely because a
         * jumping mob crossed one voxel; the recent fair hostile observation
         * still authorizes this bounded defensive action.
         */
        if (!burning && localAttackCooldown) {
            final Optional<ActionHand> cooldownShield = shieldHand(frame);
            if (cooldownShield.isPresent()) {
                final TickReport guarded = guard(
                        frame,
                        cooldownShield.orElseThrow(),
                        away,
                        shouldScanForHostile(
                                frame,
                                visibleHostileProximityManaged
                        ),
                        true
                );
                /*
                 * Guarding and short combat footwork are simultaneous vanilla
                 * inputs.  guard() deliberately stops stale movement for
                 * ordinary hazards, but stopping here made a shielded body
                 * stand on the attacker's cell during every recharge window.
                 * Re-issue one bounded, first-person-certified backstep after
                 * the shield lease so the normal player input path advances
                 * with vanilla's shield slowdown instead of freezing.
                 */
                contactTarget.ifPresent(target ->
                        emergencyFootwork(frame, target)
                );
                return guarded;
            }
            if (contactTarget.isPresent()) {
                emergencyFootwork(frame, contactTarget.orElseThrow());
                return TickReport.intervened(
                        state,
                        "counterattack_local_cooldown"
                );
            }
        }
        if (!burning && contactTarget.isPresent()) {
            /*
             * A shield is a cooldown bridge, not a terminal combat policy.
             * The previous ordering always selected guard before melee, so a
             * companion holding a shield could stare at one Enderman or
             * Zombie until it died. Equip an already-owned ordinary weapon,
             * then spend each ready vanilla attack.  During the vanilla
             * attack recharge window, keep an already-held shield up when
             * possible; that is the ordinary player response to a hostile
             * closing distance and prevents a stationary damage trade.
             */
            final Optional<String> weapon =
                    preferredEmergencyMeleeWeapon(frame);
            if (weapon.isPresent()) {
                return equipEmergencyItem(
                        frame,
                        ActionHand.MAIN_HAND,
                        weapon.orElseThrow(),
                        State.EQUIPPING_WEAPON,
                        "equipping_emergency_weapon"
                );
            }
            final OptionalDouble attackScale = melee.attackStrengthScale();
            if (attackScale.isPresent()
                    && attackScale.orElseThrow()
                        < EMERGENCY_ATTACK_COOLDOWN) {
                final Optional<ActionHand> cooldownShield = shieldHand(frame);
                if (cooldownShield.isPresent()) {
                    return guard(
                            frame,
                            cooldownShield.orElseThrow(),
                            away,
                            shouldScanForHostile(
                                    frame,
                                    visibleHostileProximityManaged
                            )
                    );
                }
            }
            if (attackScale.isPresent()
                    && attackScale.orElseThrow()
                        >= EMERGENCY_ATTACK_COOLDOWN) {
                return counterattack(
                        frame,
                        contactTarget.orElseThrow()
                );
            }
        }
        /*
         * Retreat is an immediate separation maneuver, not an unbounded
         * navigation policy. Once its short window is exhausted, hold or
         * guard until the threat clears (or an authorized combat skill takes
         * control). A fresh, directional vanilla damage cue is the narrow
         * exception: it may reopen one observed-cell escape so a body that
         * was already guarding does not stand in the attacker's reach.
         * Recomputing an adjacent cell on every fresh navigation revision
         * previously made a slow model response send the companion dozens of
         * blocks away from the task.
         */
        final boolean retreatWindowExhausted =
                state == State.RETREATING
                    && frame.gameTime() - stateStartedTick
                        >= MAX_RETREAT_TICKS
                || (state == State.GUARDING
                    || state == State.HOLDING)
                    && !directionalRecentDamage(frame);
        Optional<PerceptionVec3> safeTarget =
                retreatWindowExhausted
                    ? Optional.empty()
                    : safeRetreatTarget(
                            frame,
                            away,
                            burning,
                            directionlessRecentDamage(frame)
                    );
        if (safeTarget.isPresent()) {
            if (state != State.RETREATING
                    || retreatRevision != frame.navigation().revision()
                    || frame.gameTime() - stateStartedTick
                    >= MAX_RETREAT_TICKS) {
                transition(State.RETREATING, frame.gameTime());
                retreatTarget = safeTarget.orElseThrow();
                retreatRevision = frame.navigation().revision();
            }
            return retreat(frame);
        }

        if (burning && waterAllowed(frame)) {
            if ("minecraft:water_bucket".equals(
                    frame.mainHand().itemId()
            )) {
                return deployWater(frame, "fire");
            }
            if (inventoryContains(frame, "minecraft:water_bucket")) {
                return equipEmergencyItem(
                        frame,
                        ActionHand.MAIN_HAND,
                        "minecraft:water_bucket",
                        State.EQUIPPING_WATER,
                        "equipping_water_for_fire"
                );
            }
        }

        Optional<ActionHand> shield = shieldHand(frame);
        if (shield.isPresent()) {
            final boolean preserveCombatFootwork =
                    recentAttackFootwork(frame.gameTime())
                        || directionalRecentDamage(frame)
                            && away.isPresent()
                        || directionlessRecentDamage(frame);
            final TickReport guarded = guard(
                frame,
                shield.orElseThrow(),
                    away,
                    shouldScanForHostile(
                            frame,
                            visibleHostileProximityManaged
                    ),
                    preserveCombatFootwork
            );
            if (preserveCombatFootwork && lastEmergencyAway != null) {
                emergencyFootwork(frame, lastEmergencyAway);
            } else if (preserveCombatFootwork) {
                if (away.isPresent()) {
                    emergencyFootwork(frame, away.orElseThrow());
                } else {
                    /*
                     * Some vanilla magic damage sources intentionally carry
                     * no source position. A shield-only response can then
                     * leave the body on the same cell while repeated breath
                     * damage lands. Use one bounded side-step in the current
                     * first-person frame. This is a normal player input, not
                     * a guessed target position; collision, gravity, and
                     * loaded-world rules remain authoritative.
                     */
                    directionlessDamageFootwork(frame);
                }
            }
            return guarded;
        }
        if (inventoryContains(frame, "minecraft:shield")) {
            return equipEmergencyItem(
                    frame,
                    ActionHand.OFF_HAND,
                    "minecraft:shield",
                    State.EQUIPPING_SHIELD,
                    "equipping_shield"
            );
        }

        if (!burning && contactTarget.isPresent()) {
            return counterattack(
                    frame,
                    contactTarget.orElseThrow()
            );
        }
        if (!burning && shouldScanForHostile(
                frame,
                visibleHostileProximityManaged
        )) {
            return scanRecentDamage(frame);
        }
        if (!burning
                && state != State.HOLDING
                && authorizedDirectionalWarning(threat)) {
            return reactToDirectionalWarning(
                    frame,
                    away.orElseThrow()
            );
        }

        transition(State.HOLDING, frame.gameTime());
        actuator.stop();
        away.flatMap(vector -> lookTowardThreat(frame, vector))
                .ifPresent(actuator::look);
        return TickReport.intervened(
                state,
                burning ? "on_fire_no_safe_cell" : "threat_not_visible"
        );
    }

    /**
     * Treats a trusted teammate's broad "behind/left/right" warning like the
     * directional awareness a player receives over voice chat: turn to
     * verify it and take a short sneak-protected separation step. The cue
     * still supplies no entity identity or exact coordinates, and the
     * maneuver expires after twelve ticks instead of becoming navigation.
     */
    private TickReport reactToDirectionalWarning(
            final CoreSkillFrame frame,
            final PerceptionVec3 away
    ) {
        transition(State.WARNING_REACTING, frame.gameTime());
        if (frame.gameTime() - stateStartedTick
                >= MAX_WARNING_REACTION_TICKS) {
            transition(State.HOLDING, frame.gameTime());
            actuator.stop();
            return TickReport.intervened(
                    state,
                    "warning_scan_exhausted"
            );
        }
        final PerceptionVec3 toward = away.scale(-1.0);
        final LookIntent look = CoreSkillGeometry.lookAt(
                frame.eyePosition(),
                frame.eyePosition().add(toward)
        );
        if (!actuator.look(look).accepted()) {
            return stopFailure("warning_scan_look_rejected");
        }
        if (CoreSkillGeometry.angularErrorDegrees(
                frame.lookDirection(),
                toward
        ) > WARNING_SCAN_ALIGNMENT_DEGREES) {
            actuator.stop();
            return TickReport.intervened(
                    state,
                    "warning_scanning"
            );
        }
        if (!frame.onGround()) {
            actuator.stop();
            return TickReport.intervened(
                    state,
                    "warning_scan_airborne"
            );
        }
        final MovementIntent relative = relativeMovement(
                frame.lookDirection(),
                away
        );
        final double side = ((frame.gameTime() / 6L) & 1L) == 0L
                ? 0.20
                : -0.20;
        final ActionOutcome movement = actuator.move(
                new MovementIntent(
                        relative.forward(),
                        Math.max(
                                -1.0,
                                Math.min(
                                        1.0,
                                        relative.strafeLeft() + side
                                )
                        ),
                        false,
                        true
                )
        );
        return movement.accepted()
                ? TickReport.intervened(
                        state,
                        "warning_separating"
                )
                : stopFailure("warning_scan_move_rejected");
    }

    private static boolean authorizedDirectionalWarning(
            final Optional<DangerSignal> threat
    ) {
        return threat
                .filter(signal -> signal.provenance()
                        == PerceptionProvenance
                            .AUTHORIZED_PLAYER_WARNING)
                .flatMap(DangerSignal::contactDirection)
                .isPresent();
    }

    private TickReport counterattack(
            final CoreSkillFrame frame,
            final VisibleEntity target
    ) {
        transition(State.COUNTERATTACKING, frame.gameTime());
        if (!actuator.stop().accepted()) {
            return stopFailure("counterattack_stop_rejected");
        }

        final PerceptionVec3 aim = interactionAim(target);
        final PerceptionVec3 targetDirection =
                aim.subtract(frame.eyePosition());
        final LookIntent look = CoreSkillGeometry.lookAt(
                frame.eyePosition(),
                aim
        );
        if (!actuator.look(look).accepted()) {
            return stopFailure("counterattack_look_rejected");
        }
        if (CoreSkillGeometry.angularErrorDegrees(
                frame.lookDirection(),
                targetDirection
        ) > EMERGENCY_ATTACK_ALIGNMENT_DEGREES) {
            return TickReport.intervened(
                    state,
                    "counterattack_aiming"
            );
        }

        final OptionalDouble cooldown = melee.attackStrengthScale();
        if (cooldown.isEmpty()) {
            return TickReport.intervened(
                    state,
                    "counterattack_cooldown_unavailable"
            );
        }
        if (cooldown.orElseThrow() < EMERGENCY_ATTACK_COOLDOWN) {
            emergencyFootwork(frame, target);
            return TickReport.intervened(
                    state,
                    "counterattack_cooling_down"
            );
        }

        final ActionOutcome outcome = melee.attack(target.entityId());
        if (outcome.accepted()) {
            final PerceptionVec3 away = frame.position()
                    .subtract(target.position());
            lastEmergencyAway = away.lengthSquared() <= 1.0E-12
                    ? null
                    : away.normalized();
            lastEmergencyAttackTick = frame.gameTime();
            emergencyFootwork(frame, target);
        }
        return outcome.accepted()
                ? TickReport.intervened(state, "counterattack_dispatched")
                : TickReport.intervened(
                        state,
                        "counterattack_rejected_"
                                + outcome.name().toLowerCase(Locale.ROOT)
                );
    }

    /**
     * Keeps an unshielded emergency response from becoming a stationary
     * damage trade. Movement is allowed only into an adjacent cell whose
     * support and two-block clearance were freshly established by the
     * companion's own first-person rays.
     */
    private void emergencyFootwork(
            final CoreSkillFrame frame,
            final VisibleEntity target
    ) {
        final PerceptionVec3 away = frame.position()
                .subtract(target.position());
        emergencyFootwork(frame, away);
    }

    private void emergencyFootwork(
            final CoreSkillFrame frame,
            final PerceptionVec3 away
    ) {
        final Optional<PerceptionVec3> safe = safeRetreatTarget(
                frame,
                away.lengthSquared() <= 1.0E-12
                        ? Optional.empty()
                        : Optional.of(away.normalized()),
                false
        );
        if (safe.isEmpty()) {
            /*
             * A close body can occlude the floor rays needed to certify an
             * adjacent cell. Fall back to a bounded vanilla backstep when
             * the body is grounded or is not in a fall hazard. Collision,
             * gravity and the server's own movement rules remain
             * authoritative; the controller never assigns a position.
             */
            if (away.lengthSquared() > 1.0E-12
                    && (frame.onGround()
                    || danger(frame, DangerKind.FALLING).isEmpty())) {
                final MovementIntent cautious =
                        relativeMovement(
                                frame.lookDirection(),
                                away
                        );
                final double side = ((frame.gameTime() / 12L) & 1L)
                        == 0L ? 0.30 : -0.30;
                actuator.move(new MovementIntent(
                        cautious.forward() * 0.75,
                        Math.max(
                                -1.0,
                                Math.min(
                                        1.0,
                                        cautious.strafeLeft() * 0.75
                                                + side
                                )
                        ),
                        false,
                        true
                ));
            }
            return;
        }
        final PerceptionVec3 desired = safe.orElseThrow()
                .subtract(frame.position());
        final MovementIntent movement = relativeMovement(
                frame.lookDirection(),
                desired
        );
        if (Math.abs(movement.forward()) <= 1.0E-6
                && Math.abs(movement.strafeLeft()) <= 1.0E-6) {
            return;
        }
        actuator.move(movement);
    }

    /**
     * Separates from a recent damage cue when vanilla did not expose a fair
     * source direction (for example an indirect magic effect). The alternating
     * strafe is deliberately short and sneak-protected; it is only called
     * during the bounded recent-damage window and never creates an entity
     * target or an exact world route.
     */
    private void directionlessDamageFootwork(
            final CoreSkillFrame frame
    ) {
        if (frame.inWater()
                || danger(frame, DangerKind.FALLING).isPresent()) {
            return;
        }
        final double side = ((frame.gameTime() / 6L) & 1L) == 0L
                ? 0.70
                : -0.70;
        actuator.move(new MovementIntent(
                0.0,
                side,
                false,
                true
        ));
    }

    private Optional<VisibleEntity> nearestEmergencyMeleeTarget(
            final CoreSkillFrame frame
    ) {
        return frame.visibleEntities().stream()
                .filter(entity -> emergencyMeleeTarget(frame, entity))
                .filter(entity -> !entity.projectile())
                .filter(entity -> entity.distance()
                        <= EMERGENCY_MELEE_REACH)
                .min(Comparator
                        .comparingDouble(VisibleEntity::distance)
                        .thenComparing(entity ->
                        entity.entityId().toString()
                ));
    }

    /**
     * A neutral iron golem is not an ambient hostile. It becomes an
     * emergency self-defence target only after the body has received a
     * recent vanilla damage/contact cue and the golem is currently visible
     * inside ordinary melee reach. This keeps villages safe by default,
     * while avoiding the old failure mode where a golem could hit the body
     * and the companion would only raise a shield and stare. Player targets
     * remain explicit {@code engage_observed_entity} decisions rather than an
     * automatic retaliation policy.
     */
    private boolean emergencyMeleeTarget(
            final CoreSkillFrame frame,
            final VisibleEntity entity
    ) {
        if (entity.hostile()) {
            return true;
        }
        if (!"minecraft:iron_golem".equals(entity.entityTypeId())) {
            return false;
        }
        return neutralCombatLeaseActive(frame);
    }

    private boolean neutralCombatLeaseActive(
            final CoreSkillFrame frame
    ) {
        final boolean damageCue = frame.dangerSignals().stream().anyMatch(
                signal -> signal.kind() == DangerKind.THREAT_CONTACT
                        && (signal.provenance()
                                == PerceptionProvenance.RECENT_DAMAGE_EVENT
                            || signal.provenance()
                                == PerceptionProvenance.PHYSICAL_CONTACT)
        );
        if (damageCue) {
            return true;
        }
        return lastVisibleHostileTick >= 0L
                && frame.gameTime() - lastVisibleHostileTick
                    <= MAX_HOSTILE_REACQUIRE_TICKS;
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
            // Use only the already-visible public entity position below.
        }
        return new PerceptionVec3(
                target.position().x(),
                target.position().y() + 1.0,
                target.position().z()
        );
    }

    private TickReport equipEmergencyItem(
            final CoreSkillFrame frame,
            final ActionHand hand,
            final String itemId,
            final State preparingState,
            final String reason
    ) {
        clearActiveUse();
        transition(preparingState, frame.gameTime());
        actuator.stop();
        final ActionOutcome outcome = equipment.equip(hand, itemId);
        if (!outcome.accepted()) {
            return stopFailure(reason + "_rejected");
        }
        return TickReport.intervened(state, reason);
    }

    private TickReport deployWater(
            final CoreSkillFrame frame,
            final String purpose
    ) {
        return deployWater(frame, purpose, waterSurface(frame));
    }

    private TickReport deployWater(
            final CoreSkillFrame frame,
            final String purpose,
            final Optional<VisibleBlockFace> selectedSurface
    ) {
        transition(State.PREPARING_WATER, frame.gameTime());
        actuator.stop();
        final Optional<VisibleBlockFace> surface = Objects.requireNonNull(
                selectedSurface,
                "selectedSurface"
        );
        if (surface.isEmpty()) {
            final LookIntent downward = new LookIntent(
                    CoreSkillGeometry.holdLook(
                            frame.lookDirection()
                    ).yawDegrees(),
                    85.0F
            );
            if (!actuator.look(downward).accepted()) {
                return stopFailure(
                        purpose + "_water_scan_rejected"
                );
            }
            actuator.move(new MovementIntent(
                    0.0,
                    0.0,
                    false,
                    true
            ));
            return TickReport.intervened(
                    state,
                    purpose + "_water_scanning"
            );
        }
        final VisibleBlockFace face = surface.orElseThrow();
        final LookIntent targetLook = CoreSkillGeometry.lookAt(
                frame.eyePosition(),
                face.hitPosition()
        );
        final double lookError = angularError(
                frame.lookDirection(),
                face.hitPosition().subtract(frame.eyePosition())
        );
        if (!actuator.look(targetLook).accepted()) {
            return stopFailure(
                    purpose + "_water_look_rejected"
            );
        }
        if (lookError > WATER_ALIGNMENT_DEGREES) {
            return TickReport.intervened(
                    state,
                    purpose + "_water_aligning"
            );
        }
        /*
         * Vanilla BucketItem implements Item#use and performs its own
         * first-person block ray trace.  A ServerboundUseItemOnPacket only
         * invokes the block/item use-on path and therefore leaves a water
         * bucket unchanged even when its crosshair target is valid.  Send the
         * same ordinary use-item action as a real client after alignment.
         */
        final ActionOutcome use =
                actuator.useItem(ActionHand.MAIN_HAND);
        if (!use.accepted()) {
            return stopFailure(
                    purpose + "_water_use_rejected"
            );
        }
        transition(State.DEPLOYING_WATER, frame.gameTime());
        return TickReport.intervened(
                state,
                purpose + "_water_deployed"
        );
    }

    /**
     * Places an owned vanilla fall-arrest item onto a currently visible,
     * reachable landing surface. Unlike water this remains legal in the
     * Nether. The controller only issues an ordinary use-on-block action; the
     * game decides whether the item can be placed and applies normal
     * consumption, collision, bounce, slowdown, and fall damage.
     */
    private TickReport deployFallClutch(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        transition(State.PREPARING_FALL_CLUTCH, frame.gameTime());
        actuator.stop();
        final Optional<VisibleBlockFace> surface =
                waterSurface(frame);
        if (surface.isEmpty()) {
            final LookIntent downward = new LookIntent(
                    CoreSkillGeometry.holdLook(
                            frame.lookDirection()
                    ).yawDegrees(),
                    85.0F
            );
            if (!actuator.look(downward).accepted()) {
                return stopFailure(
                        "fall_clutch_scan_rejected"
                );
            }
            actuator.move(MovementIntent.STOPPED);
            return TickReport.intervened(
                    state,
                    "fall_clutch_scanning"
            );
        }
        final VisibleBlockFace face = surface.orElseThrow();
        final LookIntent targetLook = CoreSkillGeometry.lookAt(
                frame.eyePosition(),
                face.hitPosition()
        );
        if (!actuator.look(targetLook).accepted()) {
            return stopFailure("fall_clutch_look_rejected");
        }
        if (angularError(
                frame.lookDirection(),
                face.hitPosition().subtract(frame.eyePosition())
        ) > WATER_ALIGNMENT_DEGREES) {
            return TickReport.intervened(
                    state,
                    "fall_clutch_aligning"
            );
        }
        final Optional<BlockInteractionTarget> target =
                blockTarget(face);
        if (target.isEmpty()) {
            return stopFailure("fall_clutch_surface_invalid");
        }
        final ActionOutcome use = actuator.useMainHandOn(
                target.orElseThrow()
        );
        if (!use.accepted()) {
            return stopFailure("fall_clutch_use_rejected");
        }
        transition(State.DEPLOYING_FALL_CLUTCH, frame.gameTime());
        return TickReport.intervened(
                state,
                "fall_clutch_deployed_" + itemId.substring(
                        itemId.indexOf(':') + 1
                )
        );
    }

    private TickReport retreat(CoreSkillFrame frame) {
        if (retreatTarget == null) {
            actuator.stop();
            return TickReport.intervened(state, "retreat_target_unavailable");
        }
        PerceptionVec3 delta = retreatTarget.subtract(frame.eyePosition());
        if (Math.hypot(delta.x(), delta.z()) <= 0.20) {
            actuator.stop();
            retreatTarget = null;
            return TickReport.intervened(state, "retreat_cell_reached");
        }
        LookIntent look = CoreSkillGeometry.lookAt(
                frame.eyePosition(),
                retreatTarget
        );
        if (!actuator.look(look).accepted()) {
            return stopFailure("retreat_look_rejected");
        }
        double error = CoreSkillGeometry.horizontalAngularErrorDegrees(
                frame.lookDirection(),
                delta
        );
        if (error > 15.0) {
            actuator.stop();
            return TickReport.intervened(state, "retreat_aligning");
        }
        if (!actuator.move(new MovementIntent(
                1.0,
                0.0,
                true,
                false
        )).accepted()) {
            return stopFailure("retreat_move_rejected");
        }
        return TickReport.intervened(state, "retreating");
    }

    private TickReport guard(
            CoreSkillFrame frame,
            ActionHand shield,
            Optional<PerceptionVec3> away,
            boolean scanRecentDamage
    ) {
        return guard(frame, shield, away, scanRecentDamage, false);
    }

    private TickReport guard(
            CoreSkillFrame frame,
            ActionHand shield,
            Optional<PerceptionVec3> away,
            boolean scanRecentDamage,
            boolean preserveMovement
    ) {
        boolean entering = state != State.GUARDING
                || activeUseHand != shield;
        transition(State.GUARDING, frame.gameTime());
        if (!preserveMovement) {
            actuator.stop();
        }
        if (scanRecentDamage && away.isPresent()) {
            /*
             * A recent vanilla damage event carries a bounded direction from
             * the companion to the hit source.  Face that direction first so
             * the shield can actually intercept the next melee hit; a blind
             * fixed yaw sweep can leave the body side-on to an Enderman that
             * just teleported.  Keep the sweep only as the honest fallback
             * when the event did not provide a direction.
             */
            lookTowardThreat(frame, away.orElseThrow())
                    .ifPresent(actuator::look);
        } else if (scanRecentDamage) {
            actuator.look(recentDamageScanLook(frame));
        } else {
            away.flatMap(vector -> lookTowardThreat(frame, vector))
                    .ifPresent(actuator::look);
        }
        if (entering) {
            ActionOutcome use = actuator.useItem(shield);
            if (!use.accepted()) {
                activeUseHand = null;
                return stopFailure("shield_use_rejected");
            }
            activeUseHand = shield;
        }
        return TickReport.intervened(state, "guarding");
    }

    /**
     * A damage cue identifies only a direction, not an attacker.  If the
     * hostile leaves the current semantic sample (Endermen commonly teleport
     * after a hit), keep the shield up and sweep a small first-person arc for
     * the bounded cue lifetime.  This can reacquire a newly visible target
     * without tracking, querying or attacking an occluded entity.
     */
    private TickReport scanRecentDamage(final CoreSkillFrame frame) {
        transition(State.WARNING_REACTING, frame.gameTime());
        final long elapsed = frame.gameTime() - stateStartedTick;
        if (elapsed >= MAX_WARNING_REACTION_TICKS) {
            transition(State.HOLDING, frame.gameTime());
            actuator.stop();
            return TickReport.intervened(
                    state,
                    "recent_damage_scan_exhausted"
            );
        }
        if (!actuator.look(recentDamageScanLook(frame)).accepted()) {
            return stopFailure("recent_damage_scan_look_rejected");
        }
        /*
         * A melee hit with a fair source direction is already enough
         * information for one bounded separation step.  Waiting for the
         * whole look sweep before moving made a rear Zombie land several
         * vanilla hits while the body only turned its head; that is the
         * reported "staring until death" failure.  The source vector is
         * still the only direction used, and the movement below accepts
         * only a freshly observed adjacent cell (or the same cautious
         * grounded fallback used by ordinary emergency footwork).
         */
        final Optional<PerceptionVec3> directedAway = frame.dangerSignals()
                .stream()
                .filter(signal -> signal.kind() == DangerKind.THREAT_CONTACT)
                .filter(signal -> signal.provenance()
                        == PerceptionProvenance.RECENT_DAMAGE_EVENT
                    || signal.provenance()
                        == PerceptionProvenance.PHYSICAL_CONTACT)
                .flatMap(signal -> signal.contactDirection().stream())
                .filter(direction -> direction.lengthSquared() > 1.0E-12)
                .map(direction -> direction.normalized().scale(-1.0))
                .findFirst();
        if (directedAway.isPresent()
                && elapsed >= 1L
                && !frame.inWater()
                && danger(frame, DangerKind.FALLING).isEmpty()
                && directedDamageFootwork(
                        frame,
                        directedAway.orElseThrow()
                )) {
            return TickReport.intervened(
                    state,
                    "recent_damage_separating"
            );
        }
        /*
         * When the hit carried no direction and no adjacent cell was fully
         * observed, a pure look-only response can leave the body under a
         * repeating attack indefinitely (the failure seen in the real
         * End-portal lifecycle gate).  Once the four cardinal sectors have
         * been sampled, take one small sneak-protected probe in the current
         * first-person heading.  It is deliberately restricted
         * to recent physical damage, a grounded body, and this bounded
         * warning window.  It therefore cannot turn an ordinary unknown
         * projectile/proximity warning into blind movement.
         */
        /*
         * A melee hit or crystal blast can leave a vanilla player briefly
         * airborne without entering the separate FALLING hazard lane.  A
         * strict onGround gate made that body keep looking while repeated
         * directionless damage landed.  Horizontal air control is still an
         * ordinary player input and remains bounded to the recent-damage
         * window; water, fire and actual falling are handled by the higher
         * priority emergency branches before this method.
         */
        if (directionlessRecentDamage(frame)
                && !frame.inWater()
                && elapsed >= DIRECTIONLESS_DAMAGE_SCAN_TICKS) {
            final ActionOutcome movement = actuator.move(
                    new MovementIntent(
                            DIRECTIONLESS_DAMAGE_PROBE_SPEED,
                            0.0,
                            false,
                            true
                    )
            );
            return movement.accepted()
                    ? TickReport.intervened(
                            state,
                            "recent_damage_separating"
                    )
                    : stopFailure("recent_damage_separation_rejected");
        }
        actuator.stop();
        return TickReport.intervened(state, "recent_damage_scanning");
    }

    /**
     * Takes one small, server-authoritative step away from a recently
     * reported attacker.  This method deliberately has no entity identity
     * or hidden coordinate access: it uses only the fair damage direction
     * and the same observed-cell validation as the normal emergency
     * footwork path.
     */
    private boolean directedDamageFootwork(
            final CoreSkillFrame frame,
            final PerceptionVec3 away
    ) {
        final Optional<PerceptionVec3> safe = safeRetreatTarget(
                frame,
                Optional.of(away),
                false
        );
        if (safe.isPresent()) {
            final PerceptionVec3 desired = safe.orElseThrow()
                    .subtract(frame.position());
            return actuator.move(relativeMovement(
                    frame.lookDirection(),
                    desired
            )).accepted();
        }
        if (away.lengthSquared() <= 1.0E-12
                || !frame.onGround()) {
            return false;
        }
        final MovementIntent cautious = relativeMovement(
                frame.lookDirection(),
                away
        );
        return actuator.move(new MovementIntent(
                cautious.forward() * 0.75,
                cautious.strafeLeft() * 0.75,
                false,
                true
        )).accepted();
    }

    private boolean shouldScanForHostile(final CoreSkillFrame frame) {
        return shouldScanForHostile(frame, false);
    }

    private boolean shouldScanForHostile(
            final CoreSkillFrame frame,
            final boolean visibleHostileProximityManaged
    ) {
        /*
         * An active combat skill owns ordinary hostile proximity, including
         * its own target-loss/reacquisition policy. The emergency timestamp
         * must never steal that lease after the target leaves the current
         * view. A separately reported physical contact or projectile threat
         * still reaches mostSevereThreat and remains non-delegable.
         */
        if (visibleHostileProximityManaged) {
            return false;
        }
        final boolean visibleHostile = frame.visibleEntities().stream()
                .anyMatch(entity -> entity.hostile()
                        || entity.projectile()
                        || "minecraft:iron_golem".equals(
                                entity.entityTypeId()
                        ) && neutralCombatLeaseActive(frame));
        if (visibleHostile) {
            /*
             * A recently contacted hostile may still be visible after it
             * moves or teleports outside the 3.25 block melee radius. Do not
             * drop the emergency lease merely because the entity is now at
             * eight blocks: retain the bounded first-person scan/guard window
             * from the last fair close observation. Unrelated distant mobs
             * never set lastVisibleHostileTick and therefore do not wake this
             * lane.
             */
            return lastVisibleHostileTick >= 0L
                    && frame.gameTime() - lastVisibleHostileTick
                        <= MAX_HOSTILE_REACQUIRE_TICKS;
        }
        final boolean recentDamage = frame.dangerSignals().stream().anyMatch(
                signal ->
                        signal.kind() == DangerKind.THREAT_CONTACT
                            && signal.provenance()
                                == PerceptionProvenance.RECENT_DAMAGE_EVENT
                );
        if (recentDamage) {
            return true;
        }
        return lastVisibleHostileTick >= 0L
                && frame.gameTime() - lastVisibleHostileTick
                    <= MAX_HOSTILE_REACQUIRE_TICKS;
    }

    private static LookIntent recentDamageScanLook(
            final CoreSkillFrame frame
    ) {
        final Optional<PerceptionVec3> damageDirection = frame
                .dangerSignals()
                .stream()
                .filter(signal -> signal.kind() == DangerKind.THREAT_CONTACT)
                .filter(signal -> signal.provenance()
                        == PerceptionProvenance.RECENT_DAMAGE_EVENT
                    || signal.provenance()
                        == PerceptionProvenance.PHYSICAL_CONTACT)
                .flatMap(signal -> signal.contactDirection().stream())
                .filter(direction -> direction.lengthSquared() > 1.0E-12)
                .findFirst();
        final boolean directed = damageDirection.isPresent();
        final LookIntent base = directed
                ? CoreSkillGeometry.holdLook(
                        damageDirection.orElseThrow().normalized()
                )
                : CoreSkillGeometry.holdLook(frame.lookDirection());
        final float[] offsets = directed
                ? DAMAGE_DIRECTION_SCAN_OFFSETS
                : DAMAGE_UNKNOWN_SCAN_OFFSETS;
        final int phase = (int) ((frame.gameTime() / 4L) % offsets.length);
        return new LookIntent(
                ActionMath.wrapDegrees(
                        base.yawDegrees() + offsets[phase]
                ),
                Math.max(
                        -35.0F,
                        Math.min(35.0F, base.pitchDegrees())
                )
        );
    }

    private TickReport startEating(
            CoreSkillFrame frame,
            ActionHand hand
    ) {
        transition(State.EATING, frame.gameTime());
        eatingBaseline = frame.foodLevel();
        final HeldItemSummary heldFood = held(frame, hand);
        eatingItemId = heldFood.itemId();
        eatingItemCount = heldFood.count();
        actuator.stop();
        ActionOutcome use = actuator.useItem(hand);
        if (!use.accepted()) {
            state = State.CLEAR;
            activeUseHand = null;
            clearEatingSnapshot();
            return TickReport.none(state);
        }
        activeUseHand = hand;
        return TickReport.intervened(state, "eating_started");
    }

    private TickReport continueEating(CoreSkillFrame frame) {
        final HeldItemSummary current =
                activeUseHand == null
                        ? HeldItemSummary.empty()
                        : held(frame, activeUseHand);
        final boolean alwaysEdible =
                isAlwaysEdibleEmergency(eatingItemId);
        final boolean stackConsumed =
                eatingItemId != null
                        && (!eatingItemId.equals(current.itemId())
                        || current.count() < eatingItemCount);
        final boolean nutritionApplied =
                !alwaysEdible && frame.foodLevel() > eatingBaseline;
        final boolean timedOut =
                frame.gameTime() - stateStartedTick
                        >= MAX_EATING_TICKS;
        if (stackConsumed || nutritionApplied || timedOut) {
            clearActiveUse();
            state = State.CLEAR;
            clearEatingSnapshot();
            return TickReport.intervened(
                    state,
                    timedOut
                            ? "eating_timed_out"
                            : "eating_finished"
            );
        }
        if (activeUseHand == null
                || !isFood(current)) {
            clearActiveUse();
            state = State.CLEAR;
            clearEatingSnapshot();
            return TickReport.intervened(state, "food_no_longer_held");
        }
        if (!actuator.stop().accepted()) {
            return stopFailure("eating_stop_rejected");
        }
        return TickReport.intervened(state, "eating");
    }

    private void transition(State next, long gameTime) {
        if (state == next) {
            return;
        }
        if (next != State.CLEAR) {
            /*
             * This happens before the first action of a new emergency state.
             * It prevents a bow draw, mining operation, boat paddle or
             * minecart rider input from the previously active task surviving
             * into the reflex that is taking ownership now.
             */
            preemptTaskControls.run();
        }
        if (state == State.EATING || state == State.GUARDING) {
            clearActiveUse();
        }
        if (state == State.EATING && next != State.EATING) {
            clearEatingSnapshot();
        }
        state = next;
        stateStartedTick = gameTime;
    }

    private void clearActiveUse() {
        if (activeUseHand != null) {
            actuator.releaseUse();
            activeUseHand = null;
        }
    }

    private void clearEatingSnapshot() {
        eatingItemId = null;
        eatingItemCount = 0;
    }

    private TickReport stopFailure(String reason) {
        actuator.stop();
        state = State.HOLDING;
        return TickReport.intervened(state, reason);
    }

    private static Optional<DangerSignal> danger(
            CoreSkillFrame frame,
            DangerKind kind
    ) {
        return frame.dangerSignals().stream()
                .filter(signal -> signal.kind() == kind)
                .max(Comparator.comparingDouble(DangerSignal::severity));
    }

    private static Optional<DangerSignal> mostSevereThreat(
            CoreSkillFrame frame,
            boolean visibleHostileProximityManaged,
        boolean physicalContactManaged,
            boolean visibleProjectileThreatManaged
    ) {
        return frame.dangerSignals().stream()
                .filter(signal ->
                        (!physicalContactManaged
                                && signal.kind()
                                    == DangerKind.THREAT_CONTACT)
                        || (!visibleHostileProximityManaged
                                && signal.kind()
                                    == DangerKind.HOSTILE_PROXIMITY)
                        || (!visibleProjectileThreatManaged
                                && signal.kind()
                                    == DangerKind.PROJECTILE_PROXIMITY))
                .filter(signal -> signal.severity() >= IMMEDIATE_DANGER)
                .max(Comparator.comparingDouble(DangerSignal::severity));
    }

    private static Optional<PerceptionVec3> threatDirection(
            CoreSkillFrame frame,
            Optional<DangerSignal> signal
    ) {
        Optional<PerceptionVec3> visibleDirection =
                frame.visibleEntities().stream()
                .filter(entity -> entity.hostile() || entity.projectile())
                .min(Comparator.comparingDouble(VisibleEntity::distance))
                .map(VisibleEntity::relativePosition);
        Optional<PerceptionVec3> towardThreat = visibleDirection.isPresent()
                ? visibleDirection
                : signal.flatMap(DangerSignal::contactDirection);
        return towardThreat
                .filter(vector -> vector.lengthSquared() > 1.0E-12)
                .map(vector -> vector.normalized().scale(-1.0));
    }

    private static Optional<PerceptionVec3> safeRetreatTarget(
            CoreSkillFrame frame,
            Optional<PerceptionVec3> preferredAway,
            boolean preferWater
    ) {
        return safeRetreatTarget(
                frame,
                preferredAway,
                preferWater,
                false
        );
    }

    private static Optional<PerceptionVec3> safeRetreatTarget(
            CoreSkillFrame frame,
            Optional<PerceptionVec3> preferredAway,
            boolean preferWater,
            boolean allowUndirectedDamageEscape
    ) {
        LocalNavSnapshot navigation = frame.navigation();
        GridPos feet = frame.feet();
        Candidate best = null;
        for (int[] direction : CARDINALS) {
            GridPos destination = feet.offset(direction[0], 0, direction[1]);
            Optional<ObservedVoxel> body = navigation.voxelAt(destination);
            Optional<ObservedVoxel> head = navigation.voxelAt(
                    destination.above()
            );
            Optional<ObservedVoxel> support = navigation.voxelAt(
                    destination.below()
            );
            if (body.isEmpty()
                    || head.isEmpty()
                    || support.isEmpty()
                    || !NavigationEvidence.hasFreshTraversalClearance(
                            body.orElseThrow(),
                            navigation.revision()
                    )
                    || !NavigationEvidence.hasFreshTraversalClearance(
                            head.orElseThrow(),
                            navigation.revision()
                    )
                    || (!body.orElseThrow().kind().isLiquid()
                    && !NavigationEvidence.isFreshStandingSupport(
                            support.orElseThrow(),
                            navigation.revision()
                    ))
                    || body.orElseThrow().effectiveDanger()
                    > SAFE_VOXEL_DANGER
                    || head.orElseThrow().effectiveDanger()
                    > SAFE_VOXEL_DANGER
                    || support.orElseThrow().effectiveDanger()
                    > SAFE_VOXEL_DANGER) {
                continue;
            }
            if (preferredAway.isEmpty()
                    && (!allowUndirectedDamageEscape
                    || preferWater
                    && body.orElseThrow().kind() != VoxelKind.WATER)) {
                /*
                 * An environmental hit (for example an End-crystal blast)
                 * can be fair but directionless.  A fully observed adjacent
                 * standable cell is still a legal escape choice; refusing
                 * every such cell made the body scan in place while health
                 * continued to fall.  Water clutching retains its stricter
                 * requirement and only selects a visible water cell.
                 */
                continue;
            }
            PerceptionVec3 directionVector = new PerceptionVec3(
                    direction[0],
                    0.0,
                    direction[1]
            );
            double score = preferredAway
                    .map(value -> value.dot(directionVector))
                    .orElse(0.0);
            if (preferWater
                    && body.orElseThrow().kind() == VoxelKind.WATER) {
                score += 4.0;
            }
            Candidate candidate = new Candidate(
                    new PerceptionVec3(
                            destination.x() + 0.5,
                            frame.eyePosition().y(),
                            destination.z() + 0.5
                    ),
                    score,
                    destination
            );
            if (best == null
                    || candidate.score() > best.score()
                    || (candidate.score() == best.score()
                    && candidate.position().compareTo(best.position()) < 0)) {
                best = candidate;
            }
        }
        return best == null
                ? Optional.empty()
                : Optional.of(best.target());
    }

    private static boolean directionlessRecentDamage(
            final CoreSkillFrame frame
    ) {
        return frame.dangerSignals().stream()
                .filter(signal -> signal.kind() == DangerKind.THREAT_CONTACT)
                .filter(signal -> signal.provenance()
                        == PerceptionProvenance.RECENT_DAMAGE_EVENT
                    || signal.provenance()
                        == PerceptionProvenance.PHYSICAL_CONTACT)
                .anyMatch(signal -> signal.contactDirection().isEmpty());
    }

    /**
     * A recent physical hit with a fair source direction authorizes one
     * bounded defensive separation even when the previous tick was already
     * guarding/holding.  This is deliberately narrower than a generic
     * threat: it never invents a direction for an unknown projectile or
     * environmental hazard, and the normal observed-cell checks plus the
     * retreat window still cap movement.
     */
    private static boolean directionalRecentDamage(
            final CoreSkillFrame frame
    ) {
        return frame.dangerSignals().stream()
                .filter(signal -> signal.kind() == DangerKind.THREAT_CONTACT)
                .filter(signal -> signal.provenance()
                        == PerceptionProvenance.RECENT_DAMAGE_EVENT
                    || signal.provenance()
                        == PerceptionProvenance.PHYSICAL_CONTACT)
                .anyMatch(signal -> signal.contactDirection()
                        .map(direction -> direction.lengthSquared()
                                > 1.0E-12)
                        .orElse(false));
    }

    private static Optional<LookIntent> lookTowardThreat(
            CoreSkillFrame frame,
            PerceptionVec3 away
    ) {
        PerceptionVec3 toward = away.scale(-1.0);
        if (toward.lengthSquared() <= 1.0E-12) {
            return Optional.empty();
        }
        return Optional.of(CoreSkillGeometry.lookAt(
                frame.eyePosition(),
                frame.eyePosition().add(toward)
        ));
    }

    private static MovementIntent relativeMovement(
            final PerceptionVec3 look,
            final PerceptionVec3 desired
    ) {
        final PerceptionVec3 horizontalLook =
                new PerceptionVec3(look.x(), 0.0, look.z());
        final PerceptionVec3 horizontalDesired =
                new PerceptionVec3(desired.x(), 0.0, desired.z());
        if (horizontalLook.lengthSquared() <= 1.0E-12
                || horizontalDesired.lengthSquared() <= 1.0E-12) {
            return MovementIntent.STOPPED;
        }
        final PerceptionVec3 forward = horizontalLook.normalized();
        final PerceptionVec3 direction =
                horizontalDesired.normalized();
        final PerceptionVec3 left = new PerceptionVec3(
                forward.z(),
                0.0,
                -forward.x()
        );
        return new MovementIntent(
                direction.dot(forward),
                direction.dot(left),
                false,
                false
        );
    }

    private static Optional<ActionHand> foodHand(CoreSkillFrame frame) {
        if (isFood(frame.offHand())) {
            return Optional.of(ActionHand.OFF_HAND);
        }
        return isFood(frame.mainHand())
                ? Optional.of(ActionHand.MAIN_HAND)
                : Optional.empty();
    }

    private static Optional<ActionHand> shieldHand(CoreSkillFrame frame) {
        if ("minecraft:shield".equals(frame.offHand().itemId())) {
            return Optional.of(ActionHand.OFF_HAND);
        }
        return "minecraft:shield".equals(frame.mainHand().itemId())
                ? Optional.of(ActionHand.MAIN_HAND)
                : Optional.empty();
    }

    private static Optional<ActionHand> emergencyGoldenAppleHand(
            final CoreSkillFrame frame
    ) {
        if (isAlwaysEdibleEmergency(frame.offHand().itemId())
                && frame.offHand().count() > 0) {
            return Optional.of(ActionHand.OFF_HAND);
        }
        return isAlwaysEdibleEmergency(frame.mainHand().itemId())
                && frame.mainHand().count() > 0
                ? Optional.of(ActionHand.MAIN_HAND)
                : Optional.empty();
    }

    private static Optional<String> preferredOwnedEmergencyGoldenApple(
            final CoreSkillFrame frame
    ) {
        if (inventoryContains(frame, "minecraft:golden_apple")) {
            return Optional.of("minecraft:golden_apple");
        }
        return inventoryContains(
                frame,
                "minecraft:enchanted_golden_apple"
        )
                ? Optional.of("minecraft:enchanted_golden_apple")
                : Optional.empty();
    }

    private static HeldItemSummary held(
            CoreSkillFrame frame,
            ActionHand hand
    ) {
        return hand == ActionHand.MAIN_HAND
                ? frame.mainHand()
                : frame.offHand();
    }

    private static boolean isFood(HeldItemSummary item) {
        return item.count() > 0
                && VanillaFoodItems.isSafeFood(item.itemId());
    }

    private static boolean isAlwaysEdibleEmergency(
            final String itemId
    ) {
        return "minecraft:golden_apple".equals(itemId)
                || "minecraft:enchanted_golden_apple".equals(itemId);
    }

    private static boolean inventoryContains(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().equals(itemId))
                .mapToInt(InventoryItemSummary::count)
                .sum() > 0;
    }

    private static Optional<String> preferredHeldFallClutch(
            final CoreSkillFrame frame
    ) {
        final String mainHand = frame.mainHand().itemId();
        return frame.mainHand().count() > 0
                && FALL_CLUTCH_ITEMS.contains(mainHand)
                ? Optional.of(mainHand)
                : Optional.empty();
    }

    private static Optional<String> preferredOwnedFallClutch(
            final CoreSkillFrame frame
    ) {
        return FALL_CLUTCH_ITEMS.stream()
                .filter(itemId -> inventoryContains(frame, itemId))
                .findFirst();
    }

    private static Optional<String> preferredEmergencyMeleeWeapon(
            final CoreSkillFrame frame
    ) {
        if (EMERGENCY_MELEE_WEAPONS.contains(
                frame.mainHand().itemId()
        )) {
            return Optional.empty();
        }
        return EMERGENCY_MELEE_WEAPONS.stream()
                .filter(itemId -> inventoryContains(frame, itemId))
                .findFirst();
    }

    /**
     * Returns a conservative number of server ticks for a sword/axe attack
     * to recharge.  The exact value is still checked by the vanilla player
     * attack handler; this local floor only prevents repeated clientless
     * dispatch from stealing the interval in which a shield should be held.
     */
    private static int emergencyAttackIntervalTicks(
            final CoreSkillFrame frame
    ) {
        return 10;
    }

    private boolean recentAttackFootwork(final long gameTime) {
        return lastEmergencyAttackTick >= 0L
                && lastEmergencyAway != null
                && gameTime - lastEmergencyAttackTick
                    <= MAX_ATTACK_FOOTWORK_TICKS;
    }

    private static boolean waterAllowed(final CoreSkillFrame frame) {
        /* Vanilla water evaporates in the Nether, but remains placeable in
         * the End. The player-visible dimension is the only fact used; the
         * existing observed-surface, reach, alignment and occupancy gates
         * still decide whether a bucket action may be issued. */
        return !frame.dimension().equals(
                    dev.mcai.companion.waypoint.DimensionRef.NETHER
                );
    }

    private static Optional<VisibleBlockFace> waterSurface(
            final CoreSkillFrame frame
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> face.face().equals("up"))
                /*
                 * VisibleBlockFace.distance belongs to semantic sample time.
                 * During a fall the live eye can move several blocks before
                 * the next sample, so reach must use the current self pose.
                 */
                .filter(face ->
                        face.hitPosition()
                            .subtract(frame.eyePosition())
                            .length()
                                <= MAXIMUM_WATER_REACH
                )
                .filter(face ->
                        face.hitPosition().y()
                                <= frame.position().y() + 0.25
                )
                .filter(face ->
                        horizontalColumnDistance(frame, face)
                            <= MAXIMUM_CLUTCH_COLUMN_OFFSET
                )
                .filter(face -> {
                    final GridPos placement = new GridPos(
                            face.block().x(),
                            face.block().y() + 1,
                            face.block().z()
                    );
                    return frame.navigation().voxelAt(placement)
                            .map(ObservedVoxel::kind)
                            .map(kind -> kind == VoxelKind.AIR)
                            .orElse(false);
                })
                .min(Comparator
                        .comparingDouble(
                            (VisibleBlockFace face) ->
                                horizontalColumnDistance(
                                    frame,
                                    face
                                )
                        )
                        .thenComparingDouble(
                            VisibleBlockFace::distance
                        ));
    }

    private static Optional<VisibleBlockFace> fallClutchSurface(
            final CoreSkillFrame frame
    ) {
        return waterSurface(frame).filter(face ->
                frame.position().y()
                    - (face.block().y() + 1.0)
                        >= MINIMUM_CLUTCH_DROP
        );
    }

    private static double horizontalColumnDistance(
            final CoreSkillFrame frame,
            final VisibleBlockFace face
    ) {
        return Math.hypot(
                face.block().x() + 0.5
                    - frame.position().x(),
                face.block().z() + 0.5
                    - frame.position().z()
        );
    }

    private static Optional<BlockInteractionTarget> blockTarget(
            final VisibleBlockFace face
    ) {
        try {
            return Optional.of(new BlockInteractionTarget(
                    face.block().x(),
                    face.block().y(),
                    face.block().z(),
                    BlockFace.valueOf(
                            face.face().toUpperCase(Locale.ROOT)
                    ),
                    new ActionVec3(
                            face.hitPosition().x(),
                            face.hitPosition().y(),
                            face.hitPosition().z()
                    )
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static double angularError(
            final PerceptionVec3 current,
            final PerceptionVec3 target
    ) {
        if (target.lengthSquared() <= 1.0E-12) {
            return 180.0;
        }
        final double dot = current.normalized().dot(
                target.normalized()
        );
        return Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
    }

    public enum State {
        CLEAR,
        EATING,
        RETREATING,
        GUARDING,
        SURFACING,
        BRACING_FALL,
        EQUIPPING_FOOD,
        EQUIPPING_WEAPON,
        EQUIPPING_SHIELD,
        EQUIPPING_WATER,
        PREPARING_WATER,
        DEPLOYING_WATER,
        EQUIPPING_FALL_CLUTCH,
        PREPARING_FALL_CLUTCH,
        DEPLOYING_FALL_CLUTCH,
        COUNTERATTACKING,
        WARNING_REACTING,
        HOLDING
    }

    public record TickReport(
            boolean intervened,
            State state,
            String reason
    ) {
        public TickReport {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(reason, "reason");
        }

        private static TickReport none(State state) {
            return new TickReport(false, state, "");
        }

        private static TickReport intervened(
                State state,
                String reason
        ) {
            return new TickReport(true, state, reason);
        }
    }

    private record Candidate(
            PerceptionVec3 target,
            double score,
            GridPos position
    ) {
    }
}
