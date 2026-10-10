package dev.mcai.companion.skills.building;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.perception.HeldItemSummary;
import org.junit.jupiter.api.Test;

final class CausalPlacementEvidenceTest {
    private static final GridPos INTENDED =
            new GridPos(10, 21, 30);
    private static final BlockInteractionTarget CLICKED =
            new BlockInteractionTarget(
                    10,
                    20,
                    30,
                    BlockFace.UP,
                    new ActionVec3(10.5, 21.0, 30.5)
            );

    @Test
    void exactCompletedVanillaPlacementAndOneItemDeltaConfirm() {
        assertTrue(CausalPlacementEvidence.confirms(
                "minecraft:oak_planks",
                INTENDED,
                CLICKED,
                true,
                true,
                "minecraft:oak_planks",
                45,
                new HeldItemSummary(
                        "minecraft:oak_planks",
                        44,
                        0,
                        0
                )
        ));
    }

    @Test
    void inventoryDeltaWithoutCompletedActionNeverConfirms() {
        assertFalse(CausalPlacementEvidence.confirms(
                "minecraft:oak_planks",
                INTENDED,
                CLICKED,
                false,
                true,
                "minecraft:oak_planks",
                45,
                new HeldItemSummary(
                        "minecraft:oak_planks",
                        44,
                        0,
                        0
                )
        ));
    }

    @Test
    void wrongTargetOrUnknownSupportNeverConfirms() {
        assertFalse(CausalPlacementEvidence.confirms(
                "minecraft:oak_planks",
                INTENDED.offset(1, 0, 0),
                CLICKED,
                true,
                true,
                "minecraft:oak_planks",
                45,
                new HeldItemSummary(
                        "minecraft:oak_planks",
                        44,
                        0,
                        0
                )
        ));
        assertFalse(CausalPlacementEvidence.confirms(
                "minecraft:oak_planks",
                INTENDED,
                CLICKED,
                true,
                false,
                "minecraft:oak_planks",
                45,
                new HeldItemSummary(
                        "minecraft:oak_planks",
                        44,
                        0,
                        0
                )
        ));
    }
}
