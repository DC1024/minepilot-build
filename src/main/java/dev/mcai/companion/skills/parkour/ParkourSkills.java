package dev.mcai.companion.skills.parkour;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import java.util.Objects;
import java.util.UUID;

public final class ParkourSkills {
    public static final String PARKOUR_TO = ParkourToSkill.NAME;

    private ParkourSkills() {
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames
    ) {
        return Objects.requireNonNull(registry, "registry")
                .register(
                        PARKOUR_TO,
                        new ParkourToSkill(
                                Objects.requireNonNull(
                                        playerId,
                                        "playerId"
                                ),
                                Objects.requireNonNull(
                                        actuator,
                                        "actuator"
                                ),
                                Objects.requireNonNull(
                                        frames,
                                        "frames"
                                )
                        )
                );
    }

    public static String plannerGuide() {
        return """
            parkour_to requires all seven strings dimension,x,y,z,
            arrivalRadius,maxJumps,maxGap. Copy the current dimension. Bounds:
            arrivalRadius 0.45..1.5; maxJumps integer 1..16; maxGap integer
            1..2 and means the widest single gap, not route length. Three
            one-block gaps therefore use maxJumps=3,maxGap=1. Uses vanilla
            sprint-jumps over visible clear platforms.
            """;
    }
}
