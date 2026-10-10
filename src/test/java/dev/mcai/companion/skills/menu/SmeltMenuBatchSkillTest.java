package dev.mcai.companion.skills.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.MenuSlotSummary;
import dev.mcai.companion.perception.OpenMenuSnapshot;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SmeltMenuBatchSkillTest {
    private static final UUID PLAYER = UUID.fromString(
            "00000000-0000-0000-0000-000000000123"
    );

    @Test
    void loadsWaitsAndTakesOnlyObservedVanillaOutput()
            throws Exception {
        final RecordingActuator actuator =
                new RecordingActuator();
        final MutableFrames frames = new MutableFrames(frame(
                10,
                4,
                empty(0, false),
                empty(1, false),
                empty(2, false),
                item(3, "minecraft:raw_iron", 1, true),
                item(4, "minecraft:coal", 1, true)
        ));
        final SmeltMenuBatchSkill skill =
                new SmeltMenuBatchSkill(PLAYER, actuator, frames);
        final SmeltMenuBatchParameters parameters =
                new SmeltMenuBatchParameters(
                        10,
                        "minecraft:raw_iron",
                        "minecraft:iron_ingot",
                        1,
                        "minecraft:coal",
                        1
                );

        assertTrue(skill.preconditions(
                context(1),
                parameters
        ).isEmpty());
        skill.start(context(1), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2), parameters).status()
        );
        assertEquals(List.of("transfer:3->0:1"), actuator.operations);

        frames.current = frame(
                11,
                5,
                item(0, "minecraft:raw_iron", 1, false),
                empty(1, false),
                empty(2, false),
                empty(3, true),
                item(4, "minecraft:coal", 1, true)
        );
        skill.tick(context(3), parameters);
        assertEquals(
                List.of("transfer:3->0:1", "transfer:4->1:1"),
                actuator.operations
        );

        frames.current = frame(
                12,
                6,
                item(0, "minecraft:raw_iron", 1, false),
                empty(1, false),
                empty(2, false),
                empty(3, true),
                empty(4, true)
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(4), parameters).status()
        );

        frames.current = frame(
                13,
                7,
                empty(0, false),
                empty(1, false),
                item(2, "minecraft:iron_ingot", 1, false),
                empty(3, true),
                empty(4, true)
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(5), parameters).status()
        );
        assertEquals(
                List.of(
                        "transfer:3->0:1",
                        "transfer:4->1:1",
                        "take:2"
                ),
                actuator.operations
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                skill.result(context(5), parameters).status()
        );
    }

    @Test
    void rejectsPreexistingOutputRatherThanTakingUnownedBatch()
            throws Exception {
        final RecordingActuator actuator =
                new RecordingActuator();
        final MutableFrames frames = new MutableFrames(frame(
                20,
                3,
                empty(0, false),
                empty(1, false),
                item(2, "minecraft:iron_ingot", 1, false),
                item(3, "minecraft:raw_iron", 1, true),
                item(4, "minecraft:coal", 1, true)
        ));
        final SmeltMenuBatchSkill skill =
                new SmeltMenuBatchSkill(PLAYER, actuator, frames);

        assertEquals(
                "smelt_menu_batch.clean_input_output_required",
                skill.preconditions(
                        context(1),
                        new SmeltMenuBatchParameters(
                                20,
                                "minecraft:raw_iron",
                                "minecraft:iron_ingot",
                                1,
                                "minecraft:coal",
                                1
                        )
                ).orElseThrow().code()
        );
        assertTrue(actuator.operations.isEmpty());
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(1, 1, tick, true, true, 0.0);
    }

    private static MenuSkillFrame frame(
            final long sequence,
            final int stateId,
            final MenuSlotSummary... slots
    ) {
        return new MenuSkillFrame(
                PLAYER,
                "minecraft:overworld",
                sequence,
                sequence,
                9,
                new OpenMenuSnapshot(
                        "minecraft:furnace",
                        "FurnaceMenu",
                        2,
                        stateId,
                        List.of(slots),
                        HeldItemSummary.empty()
                )
        );
    }

    private static MenuSlotSummary empty(
            final int slot,
            final boolean player
    ) {
        return new MenuSlotSummary(
                slot,
                "minecraft:air",
                0,
                0,
                0,
                player,
                false
        );
    }

    private static MenuSlotSummary item(
            final int slot,
            final String item,
            final int count,
            final boolean player
    ) {
        return new MenuSlotSummary(
                slot,
                item,
                count,
                0,
                0,
                player,
                true
        );
    }

    private static final class MutableFrames
            implements MenuSkillFrameSource {
        private MenuSkillFrame current;

        private MutableFrames(final MenuSkillFrame current) {
            this.current = current;
        }

        @Override
        public Optional<MenuSkillFrame> current() {
            return Optional.ofNullable(current);
        }
    }

    private static final class RecordingActuator
            implements MenuSkillActuator {
        private final List<String> operations = new ArrayList<>();

        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(9);
        }

        @Override
        public MenuOperationResult checkTransfer(
                final TransferMenuItemParameters parameters
        ) {
            return MenuOperationResult.success();
        }

        @Override
        public MenuOperationResult transfer(
                final TransferMenuItemParameters parameters
        ) {
            operations.add(
                    "transfer:" + parameters.sourceSlot()
                            + "->" + parameters.destinationSlot()
                            + ":" + parameters.count()
            );
            return MenuOperationResult.success(parameters.count());
        }

        @Override
        public MenuOperationResult checkQuickMove(
                final ObservedMenuSlotParameters parameters,
                final boolean outputOnly
        ) {
            return MenuOperationResult.success();
        }

        @Override
        public MenuOperationResult quickMove(
                final ObservedMenuSlotParameters parameters,
                final boolean outputOnly
        ) {
            operations.add(
                    (outputOnly ? "take:" : "quick:")
                            + parameters.slot()
            );
            return MenuOperationResult.success(1);
        }

        @Override
        public MenuOperationResult checkSelectOption(
                final SelectMenuOptionParameters parameters
        ) {
            return MenuOperationResult.success();
        }

        @Override
        public MenuOperationResult selectOption(
                final SelectMenuOptionParameters parameters
        ) {
            return MenuOperationResult.success();
        }

        @Override
        public MenuOperationResult checkClose(
                final CloseMenuParameters parameters
        ) {
            return MenuOperationResult.success();
        }

        @Override
        public MenuOperationResult close(
                final CloseMenuParameters parameters
        ) {
            return MenuOperationResult.success();
        }

        @Override
        public MenuOperationResult checkBinding(
                final MenuBinding binding
        ) {
            return MenuOperationResult.success();
        }

        @Override
        public MenuChangeState observeChange(
                final MenuBinding binding,
                final long expectedSessionGeneration
        ) {
            return MenuChangeState.CHANGED;
        }
    }
}
