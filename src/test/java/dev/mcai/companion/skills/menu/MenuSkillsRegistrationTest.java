package dev.mcai.companion.skills.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.skill.SkillRegistry;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MenuSkillsRegistrationTest {
    @Test
    void registersAllPublicMenuSkillsAndDocumentsBindings() {
        final SkillRegistry registry = MenuSkills.registerAll(
                new SkillRegistry(),
                new SuccessfulActuator(),
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000001"
                ),
                java.util.Optional::empty
        );

        assertEquals(
                Set.of(
                        "transfer_menu_item",
                        "quick_move_observed_slot",
                        "take_menu_output",
                        "select_menu_option",
                        "close_menu",
                        "wait_for_menu_change",
                        "smelt_menu_batch"
                ),
                registry.names()
        );
        assertTrue(
                MenuSkills.plannerGuide().contains("sampleSequence")
        );
        assertTrue(MenuSkills.plannerGuide().contains("stateId"));
        assertTrue(MenuSkills.plannerGuide().contains("exactly"));
        assertTrue(MenuSkills.plannerGuide().contains(
                "smelt_menu_batch"
        ));
        assertTrue(MenuSkills.plannerGuide().contains(
                "SMITHING_TEMPLATE"
        ));
        assertTrue(MenuSkills.plannerGuide().contains(
                "PLAYER_INVENTORY"
        ));
    }

    private static final class SuccessfulActuator
            implements MenuSkillActuator {
        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(1);
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
            return MenuOperationResult.success(1);
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
