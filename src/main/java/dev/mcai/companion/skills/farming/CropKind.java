package dev.mcai.companion.skills.farming;

import dev.mcai.companion.perception.VisibleBlockFace;
import java.util.Arrays;
import java.util.Optional;

/**
 * First-party crop mechanics whose maturity is visibly represented by the
 * vanilla {@code age} block-state property.
 */
public enum CropKind {
    WHEAT(
            "minecraft:wheat",
            "minecraft:wheat_seeds",
            "minecraft:wheat",
            "minecraft:farmland",
            7
    ),
    CARROTS(
            "minecraft:carrots",
            "minecraft:carrot",
            "minecraft:carrot",
            "minecraft:farmland",
            7
    ),
    POTATOES(
            "minecraft:potatoes",
            "minecraft:potato",
            "minecraft:potato",
            "minecraft:farmland",
            7
    ),
    BEETROOTS(
            "minecraft:beetroots",
            "minecraft:beetroot_seeds",
            "minecraft:beetroot",
            "minecraft:farmland",
            3
    ),
    NETHER_WART(
            "minecraft:nether_wart",
            "minecraft:nether_wart",
            "minecraft:nether_wart",
            "minecraft:soul_sand",
            3
    );

    private final String blockId;
    private final String seedItemId;
    private final String harvestItemId;
    private final String substrateBlockId;
    private final int matureAge;

    CropKind(
            String blockId,
            String seedItemId,
            String harvestItemId,
            String substrateBlockId,
            int matureAge
    ) {
        this.blockId = blockId;
        this.seedItemId = seedItemId;
        this.harvestItemId = harvestItemId;
        this.substrateBlockId = substrateBlockId;
        this.matureAge = matureAge;
    }

    public String blockId() {
        return blockId;
    }

    public String seedItemId() {
        return seedItemId;
    }

    public String harvestItemId() {
        return harvestItemId;
    }

    public String substrateBlockId() {
        return substrateBlockId;
    }

    public int matureAge() {
        return matureAge;
    }

    public boolean isMature(VisibleBlockFace face) {
        return blockId.equals(face.blockTypeId())
                && Integer.toString(matureAge).equals(
                        face.stateProperties().get("age")
                );
    }

    public boolean isNewPlant(VisibleBlockFace face) {
        return age(face).filter(value -> value < matureAge).isPresent();
    }

    /** Returns true for any valid planted growth stage, including mature. */
    public boolean isPlant(VisibleBlockFace face) {
        return age(face).isPresent();
    }

    private Optional<Integer> age(VisibleBlockFace face) {
        if (!blockId.equals(face.blockTypeId())) {
            return Optional.empty();
        }
        String age = face.stateProperties().get("age");
        if (age == null) {
            return Optional.empty();
        }
        try {
            int parsed = Integer.parseInt(age);
            return parsed >= 0 && parsed <= matureAge
                    ? Optional.of(parsed)
                    : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    public static Optional<CropKind> fromBlockId(String blockId) {
        return Arrays.stream(values())
                .filter(crop -> crop.blockId.equals(blockId))
                .findFirst();
    }
}
