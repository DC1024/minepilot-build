package dev.mcai.companion.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ActionMathTest {
    @Test
    void approachesWithoutOvershoot() {
        assertEquals(0.25, ActionMath.approach(0.0, 1.0, 0.25));
        assertEquals(1.0, ActionMath.approach(0.9, 1.0, 0.25));
        assertEquals(-0.25, ActionMath.approach(0.0, -1.0, 0.25));
    }

    @Test
    void turnsAcrossWrapUsingShortestArc() {
        assertEquals(-175.0F, ActionMath.approachAngle(175.0F, -170.0F, 10.0F));
        assertEquals(175.0F, ActionMath.approachAngle(-175.0F, 170.0F, 10.0F));
        assertEquals(-180.0F, ActionMath.wrapDegrees(180.0F));
        assertEquals(-90.0F, ActionMath.wrapDegrees(630.0F));
    }

    @Test
    void normalizesDiagonalWithoutChangingDirection() {
        ActionMath.MovementAxes axes = ActionMath.normalizeMovement(1.0, 1.0);
        assertEquals(1.0, Math.hypot(axes.forward(), axes.strafeLeft()), 1.0E-12);
        assertEquals(axes.forward(), axes.strafeLeft(), 1.0E-12);
    }

    @Test
    void rejectsNonFiniteAndUnboundedInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionMath.approach(Double.NaN, 0.0, 0.1)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionMath.normalizeMovement(1.01, 0.0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ActionMath.approachAngle(0.0F, 10.0F, 181.0F)
        );
    }
}
