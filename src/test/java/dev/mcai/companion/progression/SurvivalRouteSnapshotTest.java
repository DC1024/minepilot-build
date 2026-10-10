package dev.mcai.companion.progression;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SurvivalRouteSnapshotTest {
    @Test
    void acceptsThePublishedTwentyFiveFieldCriticalInventoryLedger() {
        final Map<String, Integer> criticalCounts =
                SurvivalRouteTracker.criticalCounts(
                        Map.of("minecraft:bow", 1)
                );

        assertDoesNotThrow(() -> new SurvivalRouteSnapshot(
                1L,
                SurvivalRouteProfile.COMPLETION,
                DimensionRef.NETHER,
                List.of(SurvivalMilestone.BODY_ACTIVE),
                Optional.empty(),
                List.of(),
                List.of(),
                criticalCounts,
                Map.of("food", 8),
                20.0F,
                20,
                false,
                0L
        ));
    }
}
