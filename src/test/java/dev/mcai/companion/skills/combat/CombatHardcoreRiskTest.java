package dev.mcai.companion.skills.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CombatHardcoreRiskTest {
    private static final UUID PLAYER =
            UUID.fromString("22000000-0000-0000-0000-000000000001");

    @Test
    void permitsIntentionalThreatButNeverBodyHazardsOrLowHealth() {
        final SkillContext hardcore =
                new SkillContext(1, 2, 3, true, true, 0.75);
        assertEquals(
                0.75,
                CombatHardcoreRisk.threshold(
                        hardcore,
                        frame(
                                20.0F,
                                danger(
                                        DangerKind.HOSTILE_PROXIMITY,
                                        PerceptionProvenance
                                            .PROXIMITY_THREAT,
                                        0.75
                                )
                        ),
                        0.75
                ).orElseThrow()
        );
        assertTrue(CombatHardcoreRisk.threshold(
                hardcore,
                frame(
                        20.0F,
                        danger(
                                DangerKind.FALLING,
                                PerceptionProvenance.BODY_HAZARD,
                                0.75
                        )
                ),
                1.0
        ).isEmpty());
        assertTrue(CombatHardcoreRisk.threshold(
                hardcore,
                frame(
                        9.0F,
                        danger(
                                DangerKind.HOSTILE_PROXIMITY,
                                PerceptionProvenance
                                    .PROXIMITY_THREAT,
                                0.75
                        )
                ),
                1.0
        ).isEmpty());
        assertTrue(CombatHardcoreRisk.threshold(
                new SkillContext(1, 2, 3, false, true, 0.75),
                frame(
                        20.0F,
                        danger(
                                DangerKind.HOSTILE_PROXIMITY,
                                PerceptionProvenance
                                    .PROXIMITY_THREAT,
                                0.75
                        )
                ),
                1.0
        ).isEmpty());
    }

    private static CoreSkillFrame frame(
            final float health,
            final DangerSignal danger
    ) {
        return new CoreSkillFrame(
                PLAYER,
                DimensionRef.OVERWORLD,
                10,
                7,
                new PerceptionVec3(0.5, 64.0, 0.5),
                new PerceptionVec3(0.5, 65.62, 0.5),
                new PerceptionVec3(0.0, 0.0, 1.0),
                true,
                false,
                danger.severity(),
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        7,
                        List.of()
                ),
                List.of(),
                health,
                20.0F,
                20,
                List.of(),
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(),
                List.of(danger)
        );
    }

    private static DangerSignal danger(
            final DangerKind kind,
            final PerceptionProvenance provenance,
            final double severity
    ) {
        return new DangerSignal(
                kind,
                severity,
                4.0,
                Optional.empty(),
                provenance
        );
    }
}
