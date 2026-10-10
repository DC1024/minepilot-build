package dev.mcai.companion.skills.foundation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skills.core.NoParameters;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PrepareFoundationShelterMaterialsSkillTest {
    @Test
    void acceptsOnlyTheNoArgumentContract() {
        final var accepted =
                PrepareFoundationShelterMaterialsSkill.parseNone(
                        List.of()
                );
        assertEquals(
                NoParameters.INSTANCE,
                accepted.value().orElseThrow()
        );

        final var rejected =
                PrepareFoundationShelterMaterialsSkill.parseNone(
                        List.of(new SkillArgument("coal", "true"))
                );
        assertEquals(
                "prepare_foundation_shelter_materials"
                        + ".invalid_arguments",
                rejected.failure().orElseThrow().code()
        );
    }

    @Test
    void fairCoalRediscoveryMissesAreRecoverableLocally() {
        assertTrue(
                PrepareFoundationShelterMaterialsSkill
                        .recoverableCoalGatherFailure(
                                "gather_visible_block_cluster"
                                        + ".cluster_not_rediscovered"
                        )
        );
        assertTrue(
                PrepareFoundationShelterMaterialsSkill
                        .recoverableCoalGatherFailure(
                                "gather_visible_block_cluster"
                                        + ".target_binding_lost"
                        )
        );
        assertFalse(
                PrepareFoundationShelterMaterialsSkill
                        .recoverableCoalGatherFailure(
                                "gather_visible_block_cluster"
                                        + ".danger_detected"
                        )
        );
        assertFalse(
                PrepareFoundationShelterMaterialsSkill
                        .recoverableCoalGatherFailure(
                                "gather_visible_block_cluster"
                                        + ".tool_durability_reserve"
                        )
        );
    }

    @Test
    void fairWoodRediscoveryMissesAreRecoverableLocally() {
        assertTrue(
                PrepareFoundationShelterMaterialsSkill
                        .recoverableWoodGatherFailure(
                                "gather_visible_block_cluster"
                                        + ".cluster_not_rediscovered"
                        )
        );
        assertTrue(
                PrepareFoundationShelterMaterialsSkill
                        .recoverableWoodGatherFailure(
                                "gather_visible_block_cluster"
                                        + ".target_binding_lost"
                        )
        );
        assertFalse(
                PrepareFoundationShelterMaterialsSkill
                        .recoverableWoodGatherFailure(
                                "gather_visible_block_cluster"
                                        + ".danger_detected"
                        )
        );
        assertFalse(
                PrepareFoundationShelterMaterialsSkill
                        .recoverableWoodGatherFailure(
                                "gather_visible_block_cluster"
                                        + ".inventory_full"
                        )
        );
    }

    @Test
    void woodProgressWatchdogUsesActualInventoryProgressDeadline() {
        assertFalse(
                PrepareFoundationShelterMaterialsSkill
                        .woodGatheringProgressExpired(399, 100)
        );
        assertTrue(
                PrepareFoundationShelterMaterialsSkill
                        .woodGatheringProgressExpired(400, 100)
        );
        assertFalse(
                PrepareFoundationShelterMaterialsSkill
                        .woodGatheringProgressExpired(20_000, -1)
        );
    }

    @Test
    void repeatedVisibleWoodMissesSwitchToBoundedExploration() {
        assertFalse(
                PrepareFoundationShelterMaterialsSkill
                        .shouldExploreAfterRepeatedWoodRejection(2)
        );
        assertTrue(
                PrepareFoundationShelterMaterialsSkill
                        .shouldExploreAfterRepeatedWoodRejection(3)
        );
    }

    @Test
    void charcoalFallbackAcceptsOnlyKnownBurnableVanillaWood() {
        assertTrue(
                PrepareFoundationShelterMaterialsSkill
                        .isBurnableCharcoalInput(
                                "minecraft:oak_log"
                        )
        );
        assertTrue(
                PrepareFoundationShelterMaterialsSkill
                        .isBurnableCharcoalInput(
                                "minecraft:stripped_mangrove_wood"
                        )
        );
        assertFalse(
                PrepareFoundationShelterMaterialsSkill
                        .isBurnableCharcoalInput(
                                "minecraft:crimson_stem"
                        )
        );
        assertFalse(
                PrepareFoundationShelterMaterialsSkill
                        .isBurnableCharcoalInput(
                                "minecraft:bamboo_block"
                        )
        );
        assertFalse(
                PrepareFoundationShelterMaterialsSkill
                        .isBurnableCharcoalInput(
                                "example:oak_log"
                        )
        );
    }

    @Test
    void explorationUsesARepresentativeBlockFromTheChosenPlankFamily() {
        assertEquals(
                "minecraft:oak_log",
                PrepareFoundationShelterMaterialsSkill
                        .representativeWoodBlock(
                                "minecraft:oak_planks"
                        )
        );
        assertEquals(
                "minecraft:crimson_stem",
                PrepareFoundationShelterMaterialsSkill
                        .representativeWoodBlock(
                                "minecraft:crimson_planks"
                        )
        );
        assertEquals(
                "minecraft:bamboo_block",
                PrepareFoundationShelterMaterialsSkill
                        .representativeWoodBlock(
                                "minecraft:bamboo_planks"
                        )
        );
        assertEquals(
                "minecraft:oak_log",
                PrepareFoundationShelterMaterialsSkill
                        .representativeWoodBlock(null)
        );
    }
}
