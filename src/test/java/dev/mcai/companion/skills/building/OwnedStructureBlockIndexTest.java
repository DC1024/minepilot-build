package dev.mcai.companion.skills.building;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.progression.VerifiedShelterEvidence;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import org.junit.jupiter.api.Test;

final class OwnedStructureBlockIndexTest {
    @Test
    void activeAndCompletedSheltersProtectOnlyOwnedPositions() {
        final OwnedStructureBlockIndex index =
                new OwnedStructureBlockIndex();
        final ShelterPlan first = plan(
                "0123456789abcdef",
                new GridPos(0, 64, 0)
        );

        index.activateShelter(4L, first);

        assertTrue(index.protects(
                DimensionRef.OVERWORLD,
                first.origin()
        ));
        assertTrue(index.protects(
                DimensionRef.OVERWORLD,
                first.doorUpper()
        ));
        assertFalse(index.protects(
                DimensionRef.OVERWORLD,
                new GridPos(20, 64, 20)
        ));
        assertFalse(index.protects(
                DimensionRef.NETHER,
                first.origin()
        ));

        index.completeShelter(4L, first);
        final ShelterPlan replacement = plan(
                "fedcba9876543210",
                new GridPos(30, 64, 30)
        );
        index.activateShelter(5L, replacement);

        assertTrue(index.protects(
                DimensionRef.OVERWORLD,
                first.origin()
        ));
        assertTrue(index.protects(
                DimensionRef.OVERWORLD,
                replacement.origin()
        ));
    }

    @Test
    void persistedVerifiedGeometryRestoresWallsRoofDoorAndLight() {
        final ShelterPlan plan = plan(
                "0011223344556677",
                new GridPos(100, 70, 200)
        );
        final VerifiedShelterEvidence evidence =
                VerifiedShelterEvidence.from(9L, plan);
        final OwnedStructureBlockIndex index =
                new OwnedStructureBlockIndex();

        index.restoreVerifiedShelter(evidence);

        assertTrue(index.protects(
                DimensionRef.OVERWORLD,
                plan.origin()
        ));
        assertTrue(index.protects(
                DimensionRef.OVERWORLD,
                new GridPos(
                        plan.origin().x() + 2,
                        plan.origin().y() + plan.interiorHeight(),
                        plan.origin().z() + 2
                )
        ));
        assertTrue(index.protects(
                DimensionRef.OVERWORLD,
                plan.doorLower()
        ));
        assertTrue(index.protects(
                DimensionRef.OVERWORLD,
                plan.doorUpper()
        ));
        assertTrue(index.protects(
                DimensionRef.OVERWORLD,
                plan.lightPosition()
        ));
        assertFalse(index.protects(
                DimensionRef.OVERWORLD,
                new GridPos(
                        plan.origin().x() + 2,
                        plan.origin().y(),
                        plan.origin().z() + 1
                )
        ));
        assertTrue(index.protectedPositionCount() > 3);
    }

    private static ShelterPlan plan(
            final String id,
            final GridPos origin
    ) {
        final GridPos door = origin.offset(0, 0, 2);
        final GridPos light = origin.offset(2, 0, 2);
        return new ShelterPlan(
                id,
                DimensionRef.OVERWORLD,
                1L,
                ShelterScale.COMPACT,
                origin,
                3,
                3,
                2,
                ShelterFacing.WEST,
                door,
                light,
                "minecraft:oak_planks",
                "minecraft:oak_door",
                "minecraft:torch",
                1,
                List.of(
                        new ShelterBuildStep(
                                0,
                                ShelterStepRole.LOWER_WALL,
                                origin
                        ),
                        new ShelterBuildStep(
                                1,
                                ShelterStepRole.DOOR,
                                door
                        ),
                        new ShelterBuildStep(
                                2,
                                ShelterStepRole.LIGHT,
                                light
                        )
                )
        );
    }
}
