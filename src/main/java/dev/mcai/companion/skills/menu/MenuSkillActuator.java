package dev.mcai.companion.skills.menu;

import java.util.OptionalLong;

public interface MenuSkillActuator {
    OptionalLong sessionGeneration();

    MenuOperationResult checkTransfer(
            TransferMenuItemParameters parameters
    );

    MenuOperationResult transfer(TransferMenuItemParameters parameters);

    MenuOperationResult checkQuickMove(
            ObservedMenuSlotParameters parameters,
            boolean outputOnly
    );

    MenuOperationResult quickMove(
            ObservedMenuSlotParameters parameters,
            boolean outputOnly
    );

    MenuOperationResult checkSelectOption(
            SelectMenuOptionParameters parameters
    );

    MenuOperationResult selectOption(
            SelectMenuOptionParameters parameters
    );

    MenuOperationResult checkClose(CloseMenuParameters parameters);

    MenuOperationResult close(CloseMenuParameters parameters);

    MenuOperationResult checkBinding(MenuBinding binding);

    MenuChangeState observeChange(
            MenuBinding binding,
            long expectedSessionGeneration
    );
}
