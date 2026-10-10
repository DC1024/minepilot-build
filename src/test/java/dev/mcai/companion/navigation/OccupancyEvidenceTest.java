package dev.mcai.companion.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class OccupancyEvidenceTest {
    @Test
    void onlyPlayerBodyOccupancyIsAFullBodyClearanceFact() {
        assertFalse(OccupancyEvidence.SINGLE_RAY_CLEAR.isFullBodyFact());
        assertFalse(OccupancyEvidence.MULTI_RAY_CLEAR.isFullBodyFact());
        assertFalse(
                OccupancyEvidence.COLLISION_SHAPE_CLEAR.isFullBodyFact()
        );
        assertFalse(OccupancyEvidence.SURFACE_HIT.isFullBodyFact());
        assertFalse(OccupancyEvidence.BODY_CONTACT.isFullBodyFact());
        assertTrue(OccupancyEvidence.BODY_OCCUPIED.isFullBodyFact());
    }
}
