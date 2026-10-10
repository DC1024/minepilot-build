package dev.mcai.companion.skills.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class TravelSkillPolicyTest {
    @Test
    void explorationPolicyUsesShortBoundedObservationLegs() {
        final TravelSkillPolicy normal = TravelSkillPolicy.defaults();
        final TravelSkillPolicy exploration =
                TravelSkillPolicy.explorationDefaults();

        assertTrue(
                exploration.maximumTotalTicks()
                        < normal.maximumTotalTicks()
        );
        assertTrue(
                exploration.maximumNoProgressTicks()
                        < normal.maximumNoProgressTicks()
        );
        assertTrue(
                exploration.maximumStationarySegmentTicks()
                        < normal.maximumStationarySegmentTicks()
        );
        assertTrue(
                exploration.maximumScansWithoutGrowth()
                        < normal.maximumScansWithoutGrowth()
        );
    }
}
