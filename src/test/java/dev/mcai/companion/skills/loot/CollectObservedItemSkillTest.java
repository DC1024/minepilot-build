package dev.mcai.companion.skills.loot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.PerceptionVec3;
import org.junit.jupiter.api.Test;

final class CollectObservedItemSkillTest {
    @Test
    void waitsForVanillaPickupInsideConservativeTouchColumn() {
        final PerceptionVec3 player =
                new PerceptionVec3(10.5, 64.0, 20.5);

        assertTrue(CollectObservedItemSkill.withinPickupWaitRange(
                player,
                new PerceptionVec3(11.4, 65.0, 20.5)
        ));
        assertTrue(CollectObservedItemSkill.withinPickupWaitRange(
                player,
                new PerceptionVec3(10.5, 66.2, 20.5)
        ));
        assertFalse(CollectObservedItemSkill.withinPickupWaitRange(
                player,
                new PerceptionVec3(11.6, 64.0, 20.5)
        ));
        assertFalse(CollectObservedItemSkill.withinPickupWaitRange(
                player,
                new PerceptionVec3(10.5, 66.3, 20.5)
        ));
    }

    @Test
    void acceptsOnlyANearbySameItemMergeSurvivor() {
        final PerceptionVec3 original =
                new PerceptionVec3(-296.15, -42.0, -293.17);

        assertTrue(
                CollectObservedItemSkill
                        .withinMergedReplacementRadius(
                                original,
                                new PerceptionVec3(
                                        -296.00,
                                        -42.0,
                                        -292.54
                                )
                        )
        );
        assertFalse(
                CollectObservedItemSkill
                        .withinMergedReplacementRadius(
                                original,
                                new PerceptionVec3(
                                        -296.00,
                                        -42.0,
                                        -291.0
                                )
                        )
        );
    }
}
