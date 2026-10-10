package dev.mcai.companion.skills.bridging;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import java.util.Objects;
import java.util.UUID;

public final class BridgeSkills {
    public static final String BRIDGE_TO = BridgeToSkill.NAME;
    public static final String TOWER_UP = TowerUpSkill.NAME;
    public static final String WATER_CLUTCH_DESCEND =
            WaterClutchDescendSkill.NAME;

    private BridgeSkills() {
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final BridgeMaterialActuator materials
    ) {
        return Objects.requireNonNull(registry, "registry")
                .register(
                        BRIDGE_TO,
                        new BridgeToSkill(
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
                                ),
                                Objects.requireNonNull(
                                        materials,
                                        "materials"
                                )
                        )
                )
                .register(
                        TOWER_UP,
                        new TowerUpSkill(
                                playerId,
                                actuator,
                                frames,
                                materials
                        )
                )
                .register(
                        WATER_CLUTCH_DESCEND,
                        new WaterClutchDescendSkill(
                                playerId,
                                actuator,
                                frames
                        )
                );
    }

    public static String plannerGuide() {
        return """
            bridge_to exact args: dimension,x,y,z,arrivalRadius,maxBlocks;
            copy one visible level landing; arrivalRadius decimal 0.5..2.0,
            maxBlocks integer 1..64. tower_up exact args:
            dimension,targetY,arrivalTolerance,maxBlocks; targetY is absolute
            feet Y, arrivalTolerance is decimal 0.1..0.75, maxBlocks is integer
            1..32; do not tower when another fair actor will place/drop the
            body. water_clutch_descend exact args:
            dimension,x,y,z,arrivalRadius,maximumDropBlocks; copy one visible
            safe adjacent landing; arrivalRadius is decimal 0.25..0.9,
            maximumDropBlocks integer 4..32; requires stable ledge, owned water,
            and non-Nether dimension. Numeric values are JSON strings without
            units, plus signs, or integer leading zeroes. Vanilla actions only;
            each skill verifies support and never teleports.
            """;
    }
}
