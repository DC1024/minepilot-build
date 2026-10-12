package dev.mcai.companion.skills.gathering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class GatherNearbyWoodSkillTest {
    /*
     * P21: an empty wood gather must encode the last observation's counters so
     * the real cause (no wood face seen vs. every seed rejected vs. no canopy
     * handled vs. POI unreachable) can be read straight off the failure code.
     * The bare "no_wood_collected" token is preserved for monitors.
     */
    @Test
    void noWoodCollectedEncodesObservationCounters() {
        assertEquals(
                "gather_nearby_wood.no_wood_collected.w0.r0.c0.d-1",
                GatherNearbyWoodSkill.noWoodCollectedCode(0, 0, 0, -1)
        );
        // leaves occluding logs: perception never delivered a wood face
        assertEquals(
                "gather_nearby_wood.no_wood_collected.w0.r0.c0.d80",
                GatherNearbyWoodSkill.noWoodCollectedCode(0, 0, 0, 80)
        );
        // wood seen but every seed precondition-rejected by the gatherer
        assertEquals(
                "gather_nearby_wood.no_wood_collected.w3.r3.c1.d-1",
                GatherNearbyWoodSkill.noWoodCollectedCode(3, 3, 1, -1)
        );
        assertTrue(
                GatherNearbyWoodSkill.noWoodCollectedCode(2, 1, 0, -1)
                        .contains("no_wood_collected")
        );
    }
}
