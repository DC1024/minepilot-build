package dev.mcai.companion.skills.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LootSkillParametersTest {
    @Test
    void parsesOnlyTheExactBoundedObservationContract() {
        final var parsed = LootSkillParameters.parse(List.of(
                argument("sampleSequence", "42"),
                argument("observationId", "visible-3"),
                argument("maximumTicks", "300")
        ));

        assertTrue(parsed.value().isPresent());
        assertEquals(42L, parsed.value().orElseThrow().sampleSequence());
        assertEquals(
                "visible-3",
                parsed.value().orElseThrow().observationId()
        );
        assertEquals(300, parsed.value().orElseThrow().maximumTicks());

        assertFalse(LootSkillParameters.parse(List.of(
                argument("sampleSequence", "042"),
                argument("observationId", "visible-3"),
                argument("maximumTicks", "300")
        )).value().isPresent());
        assertFalse(LootSkillParameters.parse(List.of(
                argument("sampleSequence", "42"),
                argument("observationId", "visible-0003"),
                argument("maximumTicks", "300")
        )).value().isPresent());
        assertFalse(LootSkillParameters.parse(List.of(
                argument("sampleSequence", "42"),
                argument("observationId", "visible-3"),
                argument("maximumTicks", "601")
        )).value().isPresent());
        assertFalse(LootSkillParameters.parse(List.of(
                argument("sampleSequence", "42"),
                argument("observationId", "visible-3"),
                argument("maximumTicks", "300"),
                argument("itemId", "minecraft:diamond")
        )).value().isPresent());
    }

    @Test
    void parsesBoundedEngageAndCollectContract() {
        final var parsed =
                LootSkillParameters.parseEngageAndCollect(
                        List.of(
                                argument("sampleSequence", "77"),
                                argument(
                                        "observationId",
                                        "visible-1"
                                ),
                                argument(
                                        "expectedItemId",
                                        "minecraft:blaze_rod"
                                ),
                                argument("maximumTicks", "600")
                        )
                );
        assertTrue(parsed.value().isPresent());
        assertEquals(
                "minecraft:blaze_rod",
                parsed.value().orElseThrow().expectedItemId()
        );

        assertFalse(
                LootSkillParameters.parseEngageAndCollect(
                        List.of(
                                argument("sampleSequence", "77"),
                                argument(
                                        "observationId",
                                        "visible-1"
                                ),
                                argument(
                                        "expectedItemId",
                                        "not an item"
                                ),
                                argument("maximumTicks", "600")
                        )
                ).value().isPresent()
        );
        assertFalse(
                LootSkillParameters.parseEngageAndCollect(
                        List.of(
                                argument("sampleSequence", "77"),
                                argument(
                                        "observationId",
                                        "visible-1"
                                ),
                                argument(
                                        "expectedItemId",
                                        "minecraft:blaze_rod"
                                ),
                                argument("maximumTicks", "79")
                        )
                ).value().isPresent()
        );
    }

    @Test
    void parsesBoundedFoodAnimalHuntContract() {
        final var parsed =
                LootSkillParameters.parseFoodAnimalHunt(
                        List.of(
                                argument("sampleSequence", "81"),
                                argument(
                                        "observationId",
                                        "visible-2"
                                ),
                                argument(
                                        "expectedItemId",
                                        "minecraft:beef"
                                ),
                                argument("maximumTicks", "500")
                        )
                );

        assertEquals(
                new HuntObservedFoodAnimalParameters(
                        81,
                        "visible-2",
                        "minecraft:beef",
                        500
                ),
                parsed.value().orElseThrow()
        );
        assertFalse(
                LootSkillParameters.parseFoodAnimalHunt(
                        List.of(
                                argument("sampleSequence", "081"),
                                argument(
                                        "observationId",
                                        "visible-2"
                                ),
                                argument(
                                        "expectedItemId",
                                        "minecraft:beef"
                                ),
                                argument("maximumTicks", "500")
                        )
                ).value().isPresent()
        );
    }

    @Test
    void parsesOnlyTheShelteredEndermanResourceContract() {
        final var parsed =
                LootSkillParameters.parseShelteredEnderPearl(
                        List.of(
                                argument("sampleSequence", "91"),
                                argument(
                                        "observationId",
                                        "visible-2"
                                ),
                                argument("maximumTicks", "600")
                        )
                );

        assertEquals(
                new AcquireShelteredEnderPearlParameters(
                        91,
                        "visible-2",
                        600
                ),
                parsed.value().orElseThrow()
        );
        assertFalse(
                LootSkillParameters.parseShelteredEnderPearl(
                        List.of(
                                argument("sampleSequence", "91"),
                                argument(
                                        "observationId",
                                        "visible-2"
                                ),
                                argument("maximumTicks", "600"),
                                argument(
                                        "expectedItemId",
                                        "minecraft:diamond"
                                )
                        )
                ).value().isPresent()
        );
        assertFalse(
                LootSkillParameters.parseShelteredEnderPearl(
                        List.of(
                                argument("sampleSequence", "091"),
                                argument(
                                        "observationId",
                                        "visible-2"
                                ),
                                argument("maximumTicks", "600")
                        )
                ).value().isPresent()
        );
    }

    private static SkillArgument argument(
            final String name,
            final String value
    ) {
        return new SkillArgument(name, value);
    }
}
