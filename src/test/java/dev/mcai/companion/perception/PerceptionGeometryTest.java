package dev.mcai.companion.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class PerceptionGeometryTest {
    private static final PerceptionVec3 FORWARD = new PerceptionVec3(0.0, 0.0, 1.0);

    @Test
    void filtersFrontEdgeAndBehindUsingFullFov() {
        assertTrue(PerceptionGeometry.isInsideViewCone(
                FORWARD,
                new PerceptionVec3(0.0, 0.0, 5.0),
                90.0
        ));
        assertTrue(PerceptionGeometry.isInsideViewCone(
                FORWARD,
                new PerceptionVec3(1.0, 0.0, 1.0),
                90.0
        ));
        assertFalse(PerceptionGeometry.isInsideViewCone(
                FORWARD,
                new PerceptionVec3(1.01, 0.0, 1.0),
                90.0
        ));
        assertFalse(PerceptionGeometry.isInsideViewCone(
                FORWARD,
                new PerceptionVec3(0.0, 0.0, -1.0),
                90.0
        ));
    }

    @Test
    void rayFanIsFiniteBoundedNormalizedAndContainsCenter() {
        List<PerceptionVec3> rays = PerceptionGeometry.rayFan(
                0.0,
                0.0,
                90.0,
                60.0,
                3,
                3
        );

        assertEquals(9, rays.size());
        rays.forEach(ray -> assertEquals(1.0, ray.length(), 1.0E-9));
        assertEquals(FORWARD.x(), rays.get(4).x(), 1.0E-9);
        assertEquals(FORWARD.y(), rays.get(4).y(), 1.0E-9);
        assertEquals(FORWARD.z(), rays.get(4).z(), 1.0E-9);
    }

    @Test
    void foveatedFanIsSymmetricDenseAtCenterAndKeepsItsBoundary() {
        final double[] expected = {
            -50.0,
            -200.0 / 9.0,
            -50.0 / 9.0,
            0.0,
            50.0 / 9.0,
            200.0 / 9.0,
            50.0
        };

        for (int index = 0; index < expected.length; index++) {
            assertEquals(
                expected[index],
                PerceptionGeometry.foveatedGridOffset(
                    index,
                    expected.length,
                    100.0
                ),
                1.0E-12
            );
            assertEquals(
                -PerceptionGeometry.foveatedGridOffset(
                    expected.length - index - 1,
                    expected.length,
                    100.0
                ),
                PerceptionGeometry.foveatedGridOffset(
                    index,
                    expected.length,
                    100.0
                ),
                1.0E-12
            );
        }
    }

    @Test
    void verticalFanKeepsUniformSupportFeetAndHeadLayers() {
        final double[] expected = {
            -35.0,
            -17.5,
            0.0,
            17.5,
            35.0
        };

        for (int index = 0; index < expected.length; index++) {
            assertEquals(
                expected[index],
                PerceptionGeometry.uniformGridOffset(
                    index,
                    expected.length,
                    70.0
                ),
                1.0E-12
            );
        }
    }

    @Test
    void defaultFanGivesAThreeRayFoveaToANearPlayerWidthColumn() {
        final double targetDistance = 3.77;
        final double eyeAboveSurface = 1.62;
        final double targetCenterDepth = Math.sqrt(
            targetDistance * targetDistance
                - eyeAboveSurface * eyeAboveSurface
        );
        final double targetNearDepth = targetCenterDepth - 0.5;
        final double targetHalfWidth = 0.3;
        final double pitch = Math.toDegrees(Math.atan2(
            eyeAboveSurface,
            targetCenterDepth
        ));
        final List<PerceptionVec3> rays =
            PerceptionGeometry.rayFan(
                0.0,
                pitch,
                100.0,
                70.0,
                7,
                5
            );
        final int centerRow = 2;
        int crossingCentralRays = 0;
        for (int column = 2; column <= 4; column++) {
            final PerceptionVec3 ray =
                rays.get(centerRow * 7 + column);
            final double xAtNearFace =
                ray.x() / ray.z() * targetNearDepth;
            if (Math.abs(xAtNearFace) <= targetHalfWidth) {
                crossingCentralRays++;
            }
        }

        assertEquals(35, rays.size());
        assertEquals(3, crossingCentralRays);
    }

    @Test
    void defaultFanGivesThreeRaysToBothFeetAndHeadLandingLayers() {
        final double targetDistance = 3.77;
        final double eyeAboveSurface = 1.62;
        final double targetCenterDepth = Math.sqrt(
            targetDistance * targetDistance
                - eyeAboveSurface * eyeAboveSurface
        );
        final double targetNearDepth = targetCenterDepth - 0.5;
        final double targetHalfWidth = 0.3;
        final double pitch = Math.toDegrees(Math.atan2(
            eyeAboveSurface,
            targetCenterDepth
        ));
        final List<PerceptionVec3> rays =
            PerceptionGeometry.rayFan(
                0.0,
                pitch,
                100.0,
                70.0,
                7,
                5
            );
        int feetLayerRays = 0;
        int headLayerRays = 0;
        for (int row = 1; row <= 2; row++) {
            for (int column = 2; column <= 4; column++) {
                final PerceptionVec3 ray =
                    rays.get(row * 7 + column);
                final double xAtNearFace =
                    ray.x() / ray.z() * targetNearDepth;
                final double heightAboveSurface =
                    eyeAboveSurface
                        + ray.y() / ray.z() * targetNearDepth;
                if (Math.abs(xAtNearFace) > targetHalfWidth) {
                    continue;
                }
                if (heightAboveSurface >= 0.0
                        && heightAboveSurface < 1.0) {
                    feetLayerRays++;
                }
                if (heightAboveSurface >= 1.0
                        && heightAboveSurface < 2.0) {
                    headLayerRays++;
                }
            }
        }

        assertEquals(3, feetLayerRays);
        assertEquals(3, headLayerRays);
    }

    @Test
    void rejectsZeroDirectionAndUnboundedRayFan() {
        assertThrows(
                IllegalArgumentException.class,
                () -> PerceptionGeometry.isInsideViewCone(
                        new PerceptionVec3(0.0, 0.0, 0.0),
                        FORWARD,
                        90.0
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> PerceptionGeometry.rayFan(
                        0.0,
                        0.0,
                        90.0,
                        60.0,
                        20,
                        20
                )
        );
    }

    @Test
    void followsMinecraftYawAndPitchConvention() {
        PerceptionVec3 eastFacing = PerceptionGeometry.directionFromRotation(0.0, -90.0);
        assertEquals(1.0, eastFacing.x(), 1.0E-9);
        assertEquals(0.0, eastFacing.y(), 1.0E-9);
        assertEquals(0.0, eastFacing.z(), 1.0E-9);

        PerceptionVec3 upward = PerceptionGeometry.directionFromRotation(-90.0, 0.0);
        assertEquals(0.0, upward.x(), 1.0E-9);
        assertEquals(1.0, upward.y(), 1.0E-9);
        assertEquals(0.0, upward.z(), 1.0E-9);
    }
}
