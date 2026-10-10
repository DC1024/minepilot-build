package dev.mcai.companion.skills.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MenuSkillParametersTest {
    @Test
    void parsesExactObservedTransferBinding() {
        final TransferMenuItemParameters parsed =
                MenuSkillParameters.parseTransfer(List.of(
                        argument("sampleSequence", "42"),
                        argument("containerId", "7"),
                        argument("stateId", "12"),
                        argument("sourceSlot", "31"),
                        argument("destinationSlot", "1"),
                        argument("count", "16")
                )).value().orElseThrow();

        assertEquals(new MenuBinding(42, 7, 12), parsed.binding());
        assertEquals(31, parsed.sourceSlot());
        assertEquals(1, parsed.destinationSlot());
        assertEquals(16, parsed.count());
    }

    @Test
    void rejectsInventedDuplicateAndNonCanonicalValues() {
        assertFalse(MenuSkillParameters.parseTransfer(List.of(
                argument("sampleSequence", "42"),
                argument("containerId", "7"),
                argument("stateId", "12"),
                argument("sourceSlot", "1"),
                argument("destinationSlot", "1"),
                argument("count", "1")
        )).value().isPresent());
        assertFalse(MenuSkillParameters.parseTransfer(List.of(
                argument("sampleSequence", "42"),
                argument("containerId", "7"),
                argument("stateId", "12"),
                argument("sourceSlot", "1"),
                argument("destinationSlot", "2"),
                argument("count", "+1")
        )).value().isPresent());
        assertFalse(MenuSkillParameters.parseQuickMove(List.of(
                argument("sampleSequence", "42"),
                argument("containerId", "7"),
                argument("stateId", "12"),
                argument("slot", "1"),
                argument("slot", "2")
        )).value().isPresent());
    }

    @Test
    void parsesBoundedWaitAndAllOneSlotOperations() {
        assertTrue(MenuSkillParameters.parseQuickMove(slotArguments())
                .value().isPresent());
        assertTrue(MenuSkillParameters.parseTakeOutput(slotArguments())
                .value().isPresent());
        assertTrue(MenuSkillParameters.parseClose(List.of(
                argument("sampleSequence", "42"),
                argument("containerId", "7"),
                argument("stateId", "12")
        )).value().isPresent());
        assertEquals(
                1_200,
                MenuSkillParameters.parseWait(List.of(
                        argument("sampleSequence", "42"),
                        argument("containerId", "7"),
                        argument("stateId", "12"),
                        argument("timeoutTicks", "1200")
                )).value().orElseThrow().timeoutTicks()
        );
        assertFalse(MenuSkillParameters.parseWait(List.of(
                argument("sampleSequence", "42"),
                argument("containerId", "7"),
                argument("stateId", "12"),
                argument("timeoutTicks", "1201")
        )).value().isPresent());
    }

    @Test
    void parsesBoundedObservedSmeltingBatch() {
        final SmeltMenuBatchParameters parsed =
                MenuSkillParameters.parseSmeltBatch(List.of(
                        argument("sampleSequence", "42"),
                        argument("inputItemId", "minecraft:raw_iron"),
                        argument("outputItemId", "minecraft:iron_ingot"),
                        argument("count", "7"),
                        argument("fuelItemId", "minecraft:coal"),
                        argument("fuelCount", "1")
                )).value().orElseThrow();

        assertEquals(42, parsed.sampleSequence());
        assertEquals("minecraft:raw_iron", parsed.inputItemId());
        assertEquals("minecraft:iron_ingot", parsed.outputItemId());
        assertEquals(7, parsed.count());
        assertEquals("minecraft:coal", parsed.fuelItemId());
        assertEquals(1, parsed.fuelCount());
        assertFalse(MenuSkillParameters.parseSmeltBatch(List.of(
                argument("sampleSequence", "42"),
                argument("inputItemId", "minecraft:raw_iron"),
                argument("outputItemId", "minecraft:air"),
                argument("count", "7"),
                argument("fuelItemId", "minecraft:coal"),
                argument("fuelCount", "1")
        )).value().isPresent());
    }

    private static List<SkillArgument> slotArguments() {
        return List.of(
                argument("sampleSequence", "42"),
                argument("containerId", "7"),
                argument("stateId", "12"),
                argument("slot", "1")
        );
    }

    private static SkillArgument argument(
            final String name,
            final String value
    ) {
        return new SkillArgument(name, value);
    }
}
