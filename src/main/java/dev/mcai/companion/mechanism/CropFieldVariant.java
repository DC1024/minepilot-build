package dev.mcai.companion.mechanism;

/** Supported vanilla hydrated-field material substitutions. */
public enum CropFieldVariant {
    WHEAT(
            "minecraft:wheat_seeds",
            "minecraft:wheat",
            "minecraft:wheat",
            7
    ),
    CARROT(
            "minecraft:carrot",
            "minecraft:carrots",
            "minecraft:carrot",
            7
    ),
    POTATO(
            "minecraft:potato",
            "minecraft:potatoes",
            "minecraft:potato",
            7
    ),
    BEETROOT(
            "minecraft:beetroot_seeds",
            "minecraft:beetroots",
            "minecraft:beetroot",
            3
    );

    private final String plantingItemId;
    private final String plantedBlockId;
    private final String outputItemId;
    private final int matureAge;

    CropFieldVariant(
            final String plantingItemId,
            final String plantedBlockId,
            final String outputItemId,
            final int matureAge
    ) {
        this.plantingItemId = plantingItemId;
        this.plantedBlockId = plantedBlockId;
        this.outputItemId = outputItemId;
        this.matureAge = matureAge;
    }

    public String plantingItemId() {
        return plantingItemId;
    }

    public String plantedBlockId() {
        return plantedBlockId;
    }

    public String outputItemId() {
        return outputItemId;
    }

    public int matureAge() {
        return matureAge;
    }
}
