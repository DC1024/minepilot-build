package dev.mcai.companion.skills.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.navigation.GridPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;

final class PlayerSupportBlockGuardTest {
    private static final AABB STANDING_BODY = new AABB(
            0.2D,
            64.0D,
            0.2D,
            0.8D,
            65.8D,
            0.8D
    );

    @Test
    void protectsTheCollisionSurfaceCarryingThePlayer() {
        assertTrue(PlayerSupportBlockGuard.protects(
                STANDING_BODY,
                true,
                new GridPos(0, 63, 0),
                Shapes.block()
        ));
    }

    @Test
    void doesNotProtectAnAdjacentBlockOrWall() {
        assertFalse(PlayerSupportBlockGuard.protects(
                STANDING_BODY,
                true,
                new GridPos(1, 63, 0),
                Shapes.block()
        ));
        assertFalse(PlayerSupportBlockGuard.protects(
                STANDING_BODY,
                true,
                new GridPos(0, 64, 0),
                Shapes.block()
        ));
    }

    @Test
    void protectsAnAdjacentLowerBlockWhenTheBodyStraddlesItsEdge() {
        final AABB straddlingBody = new AABB(
                0.7D,
                64.0D,
                0.2D,
                1.3D,
                65.8D,
                0.8D
        );

        assertTrue(PlayerSupportBlockGuard.protects(
                straddlingBody,
                true,
                new GridPos(1, 63, 0),
                Shapes.block()
        ));
    }

    @Test
    void onlyClaimsAuthoritativeGroundContact() {
        assertFalse(PlayerSupportBlockGuard.protects(
                STANDING_BODY,
                false,
                new GridPos(0, 63, 0),
                Shapes.block()
        ));
    }
}
