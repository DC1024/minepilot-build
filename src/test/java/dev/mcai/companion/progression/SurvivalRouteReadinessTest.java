package dev.mcai.companion.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SurvivalRouteReadinessTest {
    @Test
    void foundationReadinessUsesOneMaterialRatherThanSummingMaterials() {
        final Map<String, Integer> counts =
                SurvivalRouteTracker.criticalCounts(Map.ofEntries(
                        Map.entry("minecraft:cobblestone", 40),
                        Map.entry("minecraft:oak_planks", 20),
                        Map.entry("minecraft:oak_log", 2),
                        Map.entry(
                                "minecraft:stripped_spruce_log",
                                1
                        ),
                        Map.entry("minecraft:bamboo_block", 1),
                        Map.entry("minecraft:oak_door", 3),
                        Map.entry("minecraft:torch", 4),
                        Map.entry("minecraft:stone_pickaxe", 1),
                        Map.entry("minecraft:crafting_table", 1),
                        Map.entry("minecraft:iron_pickaxe", 1),
                        Map.entry("minecraft:bucket", 1),
                        Map.entry("minecraft:shield", 1),
                        Map.entry("minecraft:bow", 1),
                        Map.entry("minecraft:arrow", 16),
                        Map.entry("minecraft:bread", 8)
                ));

        assertEquals(60, counts.get("building_blocks"));
        assertEquals(40, counts.get("same_structural_item"));
        assertEquals(3, counts.get("safe_doors"));
        assertEquals(4, counts.get("shelter_lights"));
        assertEquals(2, counts.get("stone_or_better_pickaxes"));
        assertEquals(2, counts.get("wood_or_better_pickaxes"));
        assertEquals(1, counts.get("crafting_tables"));
        assertEquals(36, counts.get("chest_plank_potential"));
        assertEquals(1, counts.get("iron_or_better_pickaxes"));
        assertEquals(1, counts.get("buckets"));
        assertEquals(1, counts.get("shields"));
        assertEquals(1, counts.get("bows"));
        assertEquals(16, counts.get("arrows"));
        assertEquals(8, counts.get("food"));
    }

    @Test
    void foundationTargetsMatchTheMinimumDynamicShelterContract() {
        final Map<String, Integer> targets =
                SurvivalRouteTracker.minimumTargets(
                        SurvivalRouteProfile.FOUNDATION
                );

        assertEquals(8, targets.get("food"));
        assertEquals(1, targets.get("crafting_tables"));
        assertEquals(1, targets.get("wood_or_better_pickaxes"));
        assertEquals(55, targets.get("same_structural_item"));
        assertEquals(1, targets.get("safe_doors"));
        assertEquals(1, targets.get("shelter_lights"));
        assertEquals(1, targets.get("iron_or_better_pickaxes"));
        assertEquals(1, targets.get("buckets"));
        assertEquals(1, targets.get("shields"));
        assertEquals(8, targets.get("chest_plank_potential"));
    }

    @Test
    void verifiedBasicCraftingStopsRequestingConsumedTableInput() {
        final Map<String, Integer> targets =
                SurvivalRouteTracker.minimumTargets(
                        SurvivalRouteProfile.FOUNDATION,
                        Set.of(
                                SurvivalMilestone.BASIC_CRAFTING_READY
                        )
                );

        assertFalse(targets.containsKey("crafting_tables"));
        assertFalse(targets.containsKey("wood_or_better_pickaxes"));
    }

    @Test
    void verifiedWorkstationsStopRequestingNewChestWood() {
        final Map<String, Integer> targets =
                SurvivalRouteTracker.minimumTargets(
                        SurvivalRouteProfile.FOUNDATION,
                        Set.of(
                                SurvivalMilestone
                                        .WORKSTATIONS_ESTABLISHED
                        )
                );

        assertFalse(
                targets.containsKey("chest_plank_potential")
        );
    }

    @Test
    void foodReadinessMatchesTheEmergencySafeFoodCatalog() {
        final Map<String, Integer> counts =
                SurvivalRouteTracker.criticalCounts(Map.of(
                        "minecraft:cooked_salmon",
                        3,
                        "minecraft:pumpkin_pie",
                        2,
                        "minecraft:honey_bottle",
                        3,
                        "minecraft:rotten_flesh",
                        64,
                        "minecraft:pufferfish",
                        64,
                        "minecraft:poisonous_potato",
                        64
                ));

        assertEquals(8, counts.get("food"));
    }

    @Test
    void verifiedShelterStopsRequestingConsumedConstructionInputs() {
        final Map<String, Integer> targets =
                SurvivalRouteTracker.minimumTargets(
                        SurvivalRouteProfile.FOUNDATION,
                        Set.of(SurvivalMilestone.SHELTER_COMPLETED)
                );

        assertFalse(targets.containsKey("same_structural_item"));
        assertFalse(targets.containsKey("safe_doors"));
        assertFalse(targets.containsKey("shelter_lights"));
        assertEquals(8, targets.get("food"));
        assertEquals(1, targets.get("iron_or_better_pickaxes"));
        assertEquals(1, targets.get("buckets"));
        assertEquals(1, targets.get("shields"));
    }

    @Test
    void preparedShelterMaterialsRemainSatisfiedWhileBuildingConsumesThem() {
        final Map<String, Integer> targets =
                SurvivalRouteTracker.minimumTargets(
                        SurvivalRouteProfile.FOUNDATION,
                        Set.of(
                                SurvivalMilestone
                                        .SHELTER_MATERIALS_PREPARED
                        )
                );

        assertFalse(targets.containsKey("same_structural_item"));
        assertFalse(targets.containsKey("safe_doors"));
        assertFalse(targets.containsKey("shelter_lights"));
        assertEquals(8, targets.get("food"));
        assertEquals(1, targets.get("iron_or_better_pickaxes"));
        assertEquals(1, targets.get("buckets"));
        assertEquals(1, targets.get("shields"));
    }
}
