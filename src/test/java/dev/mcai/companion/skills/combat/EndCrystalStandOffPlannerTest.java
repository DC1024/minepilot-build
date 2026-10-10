package dev.mcai.companion.skills.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.MoveToParameters;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class EndCrystalStandOffPlannerTest {
    private static final UUID PLAYER_ID = UUID.fromString(
            "77100000-0000-0000-0000-000000000001"
    );
    private static final PerceptionVec3 CRYSTAL =
            new PerceptionVec3(8.0, 64.0, 0.5);

    @Test
    void selectsNearestObservedCellBeyondExplosionRadius() {
        final CoreSkillFrame frame = frame(false);

        final GridPos selected = EndCrystalStandOffPlanner
                .select(frame, CRYSTAL, true)
                .orElseThrow();

        assertEquals(new GridPos(-6, 64, 0), selected);
        assertTrue(
                EndCrystalStandOffPlanner.horizontalDistance(
                        center(selected),
                        CRYSTAL
                ) >= EndCrystalStandOffPlanner
                    .MINIMUM_DESTINATION_DISTANCE
        );
    }

    @Test
    void rejectsUnsupportedRetreatCell() {
        assertTrue(
                EndCrystalStandOffPlanner
                    .select(frame(true), CRYSTAL, true)
                    .isEmpty()
        );
    }

    @Test
    void selectsIncrementalObservedStepWhenFinalCellIsNotYetVisible() {
        final CoreSkillFrame frame = frame(-3, 2, false);

        final GridPos selected = EndCrystalStandOffPlanner
                .select(frame, CRYSTAL, true)
                .orElseThrow();

        assertEquals(new GridPos(-3, 64, 0), selected);
        assertTrue(
                EndCrystalStandOffPlanner.horizontalDistance(
                        center(selected),
                        CRYSTAL
                ) < EndCrystalStandOffPlanner
                    .MINIMUM_DESTINATION_DISTANCE
        );
        assertTrue(
                EndCrystalStandOffPlanner
                    .authorizesAggregateRisk(
                            frame,
                            new MoveToParameters(
                                    DimensionRef.END,
                                    selected.x() + 0.5,
                                    selected.y(),
                                    selected.z() + 0.5,
                                    0.30
                            ),
                            CRYSTAL
                    )
        );
    }

    @Test
    void aggregateRiskAuthorizationAllowsOnlyImprovingAwayMovement() {
        final CoreSkillFrame frame = frame(false);
        final MoveToParameters safe = new MoveToParameters(
                DimensionRef.END,
                -5.5,
                64.0,
                0.5,
                0.30
        );
        final MoveToParameters incremental =
                new MoveToParameters(
                        DimensionRef.END,
                        -2.5,
                        64.0,
                        0.5,
                        0.30
                );
        final MoveToParameters toward =
                new MoveToParameters(
                        DimensionRef.END,
                        1.5,
                        64.0,
                        0.5,
                        0.30
                );

        assertTrue(
                EndCrystalStandOffPlanner
                    .authorizesAggregateRisk(
                            frame,
                            safe,
                            CRYSTAL
                    )
        );
        assertTrue(
                EndCrystalStandOffPlanner
                    .authorizesAggregateRisk(
                            frame,
                            incremental,
                            CRYSTAL
                    )
        );
        assertFalse(
                EndCrystalStandOffPlanner
                    .authorizesAggregateRisk(
                            frame,
                            toward,
                            CRYSTAL
                    )
        );
    }

    private static CoreSkillFrame frame(
            final boolean unsupported
    ) {
        return frame(
                unsupported ? -6 : -8,
                unsupported ? -6 : 2,
                unsupported
        );
    }

    private static CoreSkillFrame frame(
            final int minimumX,
            final int maximumX,
            final boolean unsupported
    ) {
        final List<ObservedVoxel> voxels =
                new ArrayList<>();
        for (int x = minimumX;
                x <= maximumX;
                x++) {
            voxels.add(new ObservedVoxel(
                    new GridPos(x, 63, 0),
                    unsupported
                            ? VoxelKind.AIR
                            : VoxelKind.SOLID,
                    0.0,
                    1
            ));
            voxels.add(new ObservedVoxel(
                    new GridPos(x, 64, 0),
                    VoxelKind.AIR,
                    0.0,
                    1
            ));
            voxels.add(new ObservedVoxel(
                    new GridPos(x, 65, 0),
                    VoxelKind.AIR,
                    0.0,
                    1
            ));
        }
        final PerceptionVec3 position =
                new PerceptionVec3(0.5, 64.0, 0.5);
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.END,
                100,
                1,
                position,
                position.add(
                        new PerceptionVec3(0.0, 1.62, 0.0)
                ),
                new PerceptionVec3(1.0, 0.0, 0.0),
                true,
                false,
                0.75,
                new LocalNavSnapshot(
                        DimensionRef.END,
                        1,
                        voxels
                ),
                List.of(),
                20.0F,
                20.0F,
                20,
                List.of(),
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
    }

    private static PerceptionVec3 center(
            final GridPos position
    ) {
        return new PerceptionVec3(
                position.x() + 0.5,
                position.y(),
                position.z() + 0.5
        );
    }
}
