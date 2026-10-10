package dev.mcai.companion.skills.sleeping;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import java.util.Objects;
import java.util.UUID;

/**
 * Registration slice for ordinary, server-verified bed sleep.
 */
public final class SleepSkills {
    public static final String SLEEP_IN_OBSERVED_BED =
            "sleep_in_observed_bed";

    private SleepSkills() {
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            InteractionSkillActuator actuator,
            SleepSkillFrameSource frames
    ) {
        return registerAll(
                registry,
                playerId,
                actuator,
                frames,
                SleepSkillPolicy.defaults()
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            InteractionSkillActuator actuator,
            SleepSkillFrameSource frames,
            SleepSkillPolicy policy
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(actuator, "actuator");
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(policy, "policy");
        return registry.register(
                SLEEP_IN_OBSERVED_BED,
                new SleepInObservedBedSkill(
                        playerId,
                        actuator,
                        frames,
                        policy
                )
        );
    }

    public static String plannerGuide() {
        return """
            sleep_in_observed_bed requires dimension, sampleSequence, integer
            x/y/z, and lowercase face copied from one latest
            visibleBlockFaces entry for a vanilla minecraft:*_bed. The skill
            accepts only a fresh, unoccupied, close bed in the overworld at
            night, with safe health/danger and enough projected multiplayer
            sleepers. It right-clicks that exact visible face through the
            ordinary player interaction path, then succeeds only after the
            bound ServerPlayer really sleeps at the matching bed, owns the
            matching respawn point, and naturally wakes in daylight. It never changes time,
            weather, position, respawn state, or wake state.
            """;
    }
}
