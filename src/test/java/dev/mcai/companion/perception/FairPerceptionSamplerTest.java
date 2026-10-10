package dev.mcai.companion.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class FairPerceptionSamplerTest {
    @Test
    void hitClearSegmentStopsOnTheEyeSideOfTheHitSurface() {
        final PerceptionVec3 eye =
            new PerceptionVec3(0.5, 65.5, 0.5);
        final PerceptionVec3 hit =
            new PerceptionVec3(4.0, 65.5, 0.5);

        final ClearSightRay ray = FairPerceptionSampler
            .clearSegmentBeforeHit(eye, hit)
            .orElseThrow();

        assertEquals(
            PerceptionProvenance.BLOCK_RAY_CLEAR_BEFORE_HIT,
            ray.provenance()
        );
        assertEquals(3.4999, ray.distance(), 1.0E-12);
        assertEquals(3.9999, ray.endPosition().x(), 1.0E-12);
        assertTrue(ray.endPosition().x() < hit.x());
        assertEquals(hit.y(), ray.endPosition().y(), 0.0);
        assertEquals(hit.z(), ray.endPosition().z(), 0.0);
    }

    @Test
    void hitClearSegmentAlsoRetreatsCorrectlyTowardANegativeEyeSide() {
        final PerceptionVec3 eye =
            new PerceptionVec3(0.5, 65.5, 0.5);
        final PerceptionVec3 hit =
            new PerceptionVec3(-2.0, 65.5, 0.5);

        final ClearSightRay ray = FairPerceptionSampler
            .clearSegmentBeforeHit(eye, hit)
            .orElseThrow();

        assertTrue(ray.endPosition().x() > hit.x());
        assertFalse(
            Math.floor(ray.endPosition().x())
                == Math.floor(hit.x() - 0.5)
        );
    }

    @Test
    void degenerateHitDoesNotInventAFreeSegment() {
        final PerceptionVec3 eye =
            new PerceptionVec3(0.5, 65.5, 0.5);

        assertTrue(
            FairPerceptionSampler.clearSegmentBeforeHit(
                eye,
                new PerceptionVec3(0.50001, 65.5, 0.5)
            ).isEmpty()
        );
    }

    @Test
    void crosshairRayIsProcessedBeforePeripheralActionEvidence() {
        final PerceptionVec3 forward =
                new PerceptionVec3(0.0, 0.0, 1.0);
        final List<PerceptionVec3> ordered =
                FairPerceptionSampler.centerFirst(
                    List.of(
                        new PerceptionVec3(-0.2, 0.0, 0.98)
                            .normalized(),
                        new PerceptionVec3(0.0, 0.0, 1.0),
                        new PerceptionVec3(0.2, 0.0, 0.98)
                            .normalized()
                    ),
                    forward
                );

        assertEquals(forward, ordered.getFirst());
        assertEquals(3, ordered.size());
    }
}
