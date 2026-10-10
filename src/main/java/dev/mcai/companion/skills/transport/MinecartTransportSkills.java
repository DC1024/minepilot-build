package dev.mcai.companion.skills.transport;

import dev.mcai.companion.skill.SkillRegistry;
import java.util.Objects;
import java.util.UUID;

public final class MinecartTransportSkills {
    public static final String ENTER_OBSERVED_MINECART =
            EnterObservedMinecartSkill.NAME;
    public static final String MINECART_TRAVEL_TO =
            MinecartTravelToSkill.NAME;

    private MinecartTransportSkills() {
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final MinecartSkillActuator actuator,
            final MinecartSkillFrameSource frames
    ) {
        return registerAll(
                registry,
                playerId,
                actuator,
                frames,
                MinecartSkillPolicy.defaults()
        );
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final MinecartSkillActuator actuator,
            final MinecartSkillFrameSource frames,
            final MinecartSkillPolicy policy
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(actuator, "actuator");
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(policy, "policy");
        return registry
                .register(
                        ENTER_OBSERVED_MINECART,
                        new EnterObservedMinecartSkill(
                                playerId,
                                actuator,
                                frames,
                                policy
                        )
                )
                .register(
                        MINECART_TRAVEL_TO,
                        new MinecartTravelToSkill(
                                playerId,
                                actuator,
                                frames,
                                policy
                        )
                );
    }

    public static String plannerGuide() {
        return """
            enter_observed_minecart requires dimension, sampleSequence, and a
            current observationId. It accepts one visible rideable minecart,
            never a hidden or cargo minecart.

            minecart_travel_to requires dimension, x, y, z, arrivalRadius,
            timeoutTicks, and dismountAtArrival. It supplies rider input while
            vanilla rails/switches/collisions own motion; it never assigns
            position or reads hidden rails. Use verified stops and dismount
            only onto a freshly visible safe surface.
            """;
    }
}
