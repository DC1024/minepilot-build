package dev.mcai.companion.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ActionContractTest {
    @Test
    void blockHitMustStayInsideDeclaredVoxelAndSupportsInsetOutlines() {
        BlockInteractionTarget valid = new BlockInteractionTarget(
                2,
                64,
                -3,
                BlockFace.UP,
                new ActionVec3(2.5, 65.0, -2.5)
        );
        assertEquals(BlockFace.UP, valid.face());

        BlockInteractionTarget insetOutline =
                new BlockInteractionTarget(
                    2,
                    64,
                    -3,
                    BlockFace.NORTH,
                    new ActionVec3(2.5, 64.5, -2.5625)
                );
        assertEquals(
                new ActionVec3(2.5, 64.5, -2.5625),
                insetOutline.hitPoint()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new BlockInteractionTarget(
                        2,
                        64,
                        -3,
                        BlockFace.UP,
                        new ActionVec3(50.0, 65.0, -2.5)
                )
        );
    }

    @Test
    void pitchAndLimitsHaveHardBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new LookIntent(0.0F, 90.01F)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActionLimits(0.25, 91.0F, 15.0F, 100)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ActionLimits(0.25, 20.0F, 15.0F, 10)
        );
    }

    @Test
    void outcomesDeclareWhetherWorkWasAccepted() {
        assertTrue(ActionOutcome.DISPATCHED.accepted());
        assertTrue(ActionOutcome.IN_PROGRESS.accepted());
        assertEquals(false, ActionOutcome.TARGET_OUT_OF_REACH.accepted());
    }
}
