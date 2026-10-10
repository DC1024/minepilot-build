package dev.mcai.companion.skills.gathering;

import static dev.mcai.companion.skills.gathering.GatheringSkillTestFixtures.PLAYER_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ResourceGatheringSkillsTest {
    @Test
    void registersBoundedPublicGatherersAndDocumentsFairness() {
        final var snapshots = GatheringSkillTestFixtures.frames(
                40,
                List.of(GatheringSkillTestFixtures.log(1, 40))
        );
        final SkillRegistry registry =
                ResourceGatheringSkills.registerAll(
                        new SkillRegistry(),
                        PLAYER_ID,
                        new GatheringSkillTestFixtures
                                .RecordingCoreActuator(),
                        snapshots,
                        new GatheringSkillTestFixtures
                                .RecordingInteractionActuator(),
                        new GatheringSkillTestFixtures.InteractionFrames(
                                snapshots
                        ),
                        new GatheringSkillTestFixtures.MutableInventory()
                );

        assertEquals(
                Set.of(
                        "gather_visible_block_cluster",
                        "gather_nearby_wood"
                ),
                registry.names()
        );
        assertTrue(
                ResourceGatheringSkills.plannerGuide()
                        .contains("never scans chunks")
        );
        assertTrue(
                ResourceGatheringSkills.plannerGuide()
                        .contains("takes no arguments")
        );
    }

    @Test
    void parserAcceptsOnlyExactCanonicalVisibleSeedFields() {
        final List<SkillArgument> valid = List.of(
                argument("dimension", "minecraft:overworld"),
                argument("sampleSequence", "40"),
                argument("x", "1"),
                argument("y", "64"),
                argument("z", "0"),
                argument("face", "west"),
                argument("blockId", "minecraft:oak_log"),
                argument("maxBlocks", "16"),
                argument("clusterRadius", "8"),
                argument("toolItemId", "minecraft:iron_axe")
        );
        assertTrue(
                GatheringSkillParameters.parse(valid)
                        .value()
                        .isPresent()
        );
        final var forged = new java.util.ArrayList<>(valid);
        forged.add(argument("scanHiddenBlocks", "true"));
        assertTrue(
                GatheringSkillParameters.parse(forged)
                        .failure()
                        .isPresent()
        );
        final var nonCanonical = new java.util.ArrayList<>(valid);
        nonCanonical.set(
                1,
                argument("sampleSequence", "040")
        );
        assertTrue(
                GatheringSkillParameters.parse(nonCanonical)
                        .failure()
                        .isPresent()
        );
    }

    private static SkillArgument argument(
            final String name,
            final String value
    ) {
        return new SkillArgument(name, value);
    }
}
