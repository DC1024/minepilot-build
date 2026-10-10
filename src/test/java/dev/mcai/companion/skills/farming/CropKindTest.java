package dev.mcai.companion.skills.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CropKindTest {
    @Test
    void supportsVanillaFieldCropsAndNetherWartAtVisibleMaturity() {
        assertCrop(
                CropKind.WHEAT,
                "minecraft:wheat_seeds",
                "minecraft:farmland",
                7
        );
        assertCrop(
                CropKind.CARROTS,
                "minecraft:carrot",
                "minecraft:farmland",
                7
        );
        assertCrop(
                CropKind.POTATOES,
                "minecraft:potato",
                "minecraft:farmland",
                7
        );
        assertCrop(
                CropKind.BEETROOTS,
                "minecraft:beetroot_seeds",
                "minecraft:farmland",
                3
        );
        assertCrop(
                CropKind.NETHER_WART,
                "minecraft:nether_wart",
                "minecraft:soul_sand",
                3
        );
    }

    private static void assertCrop(
            CropKind crop,
            String seed,
            String substrate,
            int age
    ) {
        assertEquals(seed, crop.seedItemId());
        assertEquals(substrate, crop.substrateBlockId());
        assertTrue(crop.isMature(face(crop, age)));
        assertTrue(crop.isNewPlant(face(crop, 0)));
        assertTrue(CropKind.fromBlockId(crop.blockId()).isPresent());
    }

    private static VisibleBlockFace face(CropKind crop, int age) {
        return new VisibleBlockFace(
                new BlockCoordinate(0, 64, 0),
                crop.blockId(),
                "up",
                new PerceptionVec3(0.5, 64.5, 0.5),
                2.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of("age", Integer.toString(age))
        );
    }
}
