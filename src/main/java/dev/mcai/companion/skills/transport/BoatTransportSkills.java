package dev.mcai.companion.skills.transport;

import dev.mcai.companion.skill.SkillRegistry;
import java.util.Objects;
import java.util.UUID;

/**
 * Independent registration slice for fair vanilla boat transport.
 */
public final class BoatTransportSkills {
    public static final String ENTER_OBSERVED_BOAT =
            "enter_observed_boat";
    public static final String BOAT_TRAVEL_TO = "boat_travel_to";

    private BoatTransportSkills() {
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            BoatSkillActuator actuator,
            BoatSkillFrameSource frames
    ) {
        return registerAll(
                registry,
                playerId,
                actuator,
                frames,
                BoatSkillPolicy.defaults()
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            BoatSkillActuator actuator,
            BoatSkillFrameSource frames,
            BoatSkillPolicy policy
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(actuator, "actuator");
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(policy, "policy");
        return registry
                .register(
                        ENTER_OBSERVED_BOAT,
                        new EnterObservedBoatSkill(
                                playerId,
                                actuator,
                                frames,
                                policy
                        )
                )
                .register(
                        BOAT_TRAVEL_TO,
                        new BoatTravelToSkill(
                                playerId,
                                actuator,
                                frames,
                                policy
                        )
                );
    }

    public static String plannerGuide() {
        return """
            enter_observed_boat requires dimension, sampleSequence, and a
            current observationId such as visible-0. It accepts only a
            first-person-visible vanilla boat/raft and binds its UUID
            internally; look directly at the boat and move into ordinary
            interaction reach first.

            boat_travel_to requires dimension, x, y, z, arrivalRadius,
            timeoutTicks, and dismountAtArrival. It only works while the
            companion controls a boat in the same dimension. A local 20 TPS
            controller turns, paddles, brakes, and performs bounded collision
            recovery without teleporting or inspecting hidden blocks. Set
            dismountAtArrival=true only when the current semantic view shows a
            safe bank; the skill refuses an unobserved or hazardous dismount.
            """;
    }
}
