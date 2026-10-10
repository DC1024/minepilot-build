package dev.mcai.companion.skill;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One deterministic, local, atomic player skill.
 *
 * <p>{@link #start} initializes state but must not begin an irreversible
 * segment; the post-start boundary is considered safe. {@link #tick} is
 * called at most once per increasing game tick. Implementations must keep all
 * methods bounded and must use ordinary legal player actions.</p>
 */
public interface Skill<P> {
    SkillParameterParser<P> parameters();

    /**
     * Declares that this active skill owns the response to ordinary,
     * first-person-visible hostile proximity.
     *
     * <p>The emergency controller still retains final authority over physical
     * contact, projectiles, fire, falling, air, food and health. A skill may
     * return {@code true} only when it binds hostile targets from fair
     * observations and performs its own per-tick health, distance, retreat
     * and action-safety checks.</p>
     */
    default boolean managesVisibleHostileProximity() {
        return false;
    }

    /**
     * Declares that this skill can retain control when its fairly observed
     * melee target reaches physical contact.
     *
     * <p>This is deliberately separate from ordinary hostile proximity:
     * ranged and navigation skills must not suppress the emergency contact
     * response. Only a local melee controller that continuously checks
     * health, guards and retreats may opt in.</p>
     */
    default boolean managesPhysicalContactThreats() {
        return false;
    }

    /**
     * Declares ownership of ordinary, first-person-visible projectile
     * proximity for a narrowly scoped skill.  This is separate from hostile
     * proximity because arrows, fireballs, and dragon breath must not be
     * silently ignored by navigation or melee skills.  An implementation
     * must perform its own bounded dodge/cover checks and keep health, fire,
     * fall, air, and contact emergencies available to the local supervisor.
     */
    default boolean managesVisibleProjectileThreats() {
        return false;
    }

    /**
     * Declares that this skill owns a narrowly bounded, server-authoritative
     * world/route transition such as entering a vanilla portal or reaching a
     * server-verified completion milestone.
     *
     * <p>The observation epoch is normally frozen while a skill is active so
     * any unrelated world change invalidates the model-bound action. A
     * transition skill may keep that binding across the one dimension/route
     * revision change caused by its own legal action, after which the skill
     * must prove the transition or fail closed. This is deliberately opt-in;
     * ordinary navigation, combat, and building skills retain strict stale
     * world rejection.</p>
     */
    default boolean allowsWorldRevisionTransition() {
        return false;
    }

    /**
     * Allows a trusted local skill to opt into a higher Hardcore risk ceiling
     * for an intentional, internally supervised action such as combat.
     *
     * <p>The default keeps the runtime-wide ceiling. Implementations that
     * override it must derive the value from current server-authoritative
     * state, reject body hazards themselves, and continue checking safety on
     * every tick. The supervisor validates the returned value and falls back
     * to its global ceiling if this hook throws or returns an invalid
     * number.</p>
     */
    default OptionalDouble hardcoreRiskThresholdOverride(
            SkillContext context,
            P parameters
    ) {
        return OptionalDouble.empty();
    }

    Optional<SkillFailure> preconditions(SkillContext context, P parameters) throws Exception;

    void start(SkillContext context, P parameters) throws Exception;

    SkillTickResult tick(SkillContext context, P parameters) throws Exception;

    SkillCheckpoint checkpoint(SkillContext context, P parameters) throws Exception;

    void cancel(SkillContext context, P parameters) throws Exception;

    SkillResult result(SkillContext context, P parameters) throws Exception;
}
