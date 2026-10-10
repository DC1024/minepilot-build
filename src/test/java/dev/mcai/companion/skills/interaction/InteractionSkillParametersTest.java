package dev.mcai.companion.skills.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.model.SkillArgument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class InteractionSkillParametersTest {
    @Test
    void blockTargetsUseOnlyFieldsActuallyExposedToTheModel() {
        var parsed = InteractionSkillParameters.parseBreakBlock(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("sampleSequence", "12"),
                argument("x", "1"),
                argument("y", "64"),
                argument("z", "0"),
                argument("face", "west")
        ));

        assertTrue(parsed.value().isPresent());
        BreakBlockParameters parameters = parsed.value().orElseThrow();
        assertEquals(12, parameters.target().sampleSequence());
        assertEquals(BlockFace.WEST, parameters.target().face());
    }

    @Test
    void rejectsExtraRayHitAndNonCanonicalNumbers() {
        List<SkillArgument> withHiddenHit = new ArrayList<>(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("sampleSequence", "12"),
                argument("x", "1"),
                argument("y", "64"),
                argument("z", "0"),
                argument("face", "west")
        ));
        withHiddenHit.add(argument("hitX", "1.0"));

        assertTrue(
                InteractionSkillParameters.parseBreakBlock(withHiddenHit)
                        .value()
                        .isEmpty()
        );
        withHiddenHit.removeLast();
        withHiddenHit.set(
                1,
                argument("sampleSequence", "012")
        );
        assertTrue(
                InteractionSkillParameters.parseBreakBlock(withHiddenHit)
                        .value()
                        .isEmpty()
        );
    }

    @Test
    void entityReferencesAreOpaqueBoundedObservationIds() {
        var valid = InteractionSkillParameters.parseAttackEntity(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("sampleSequence", "12"),
                argument("observationId", "visible-0")
        ));
        assertTrue(valid.value().isPresent());
        assertEquals(
                0,
                valid.value().orElseThrow().observationIndex()
        );

        var forged = InteractionSkillParameters.parseAttackEntity(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("sampleSequence", "12"),
                argument(
                        "observationId",
                        "00000000-0000-0000-0000-000000000456"
                )
        ));
        assertTrue(forged.value().isEmpty());

        final var interaction =
                InteractionSkillParameters.parseInteractEntity(List.of(
                        argument("dimension", "minecraft:overworld"),
                        argument("sampleSequence", "12"),
                        argument("observationId", "visible-0"),
                        argument("hand", "main_hand")
                ));
        assertEquals(
                ActionHand.MAIN_HAND,
                interaction.value().orElseThrow().hand()
        );
    }

    @Test
    void useItemHasStrictHandAndBoundedDuration() {
        var valid = InteractionSkillParameters.parseUseItem(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("hand", "off_hand"),
                argument("holdTicks", "20")
        ));
        assertEquals(
                ActionHand.OFF_HAND,
                valid.value().orElseThrow().hand()
        );

        var invalid = InteractionSkillParameters.parseUseItem(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("hand", "OFF_HAND"),
                argument("holdTicks", "20")
        ));
        assertTrue(invalid.value().isEmpty());
    }

    private static SkillArgument argument(String name, String value) {
        return new SkillArgument(name, value);
    }
}
