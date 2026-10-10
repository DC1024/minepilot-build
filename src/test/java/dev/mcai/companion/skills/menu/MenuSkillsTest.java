package dev.mcai.companion.skills.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.model.SkillArgument;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

final class MenuSkillsTest {
    @Test
    void exactTransferMutatesOnlyOnTickAndPropagatesFailure()
            throws Exception {
        final RecordingActuator actuator = new RecordingActuator();
        final MenuOneShotSkill<TransferMenuItemParameters> skill =
                new MenuOneShotSkill<>(
                        MenuSkills.TRANSFER_MENU_ITEM,
                        MenuSkillParameters::parseTransfer,
                        actuator::checkTransfer,
                        actuator::transfer
                );
        final TransferMenuItemParameters parameters =
                new TransferMenuItemParameters(
                        new MenuBinding(1, 2, 3),
                        10,
                        0,
                        4
                );

        assertTrue(skill.preconditions(context(1), parameters).isEmpty());
        skill.start(context(1), parameters);
        assertTrue(actuator.operations.isEmpty());
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(2), parameters).status()
        );
        assertEquals(List.of("transfer"), actuator.operations);
        assertEquals(
                SkillResult.Status.COMPLETED,
                skill.result(context(2), parameters).status()
        );

        final RecordingActuator rejected = new RecordingActuator();
        rejected.transferCheck = MenuOperationResult.rejected(
                "menu.state_changed"
        );
        final MenuOneShotSkill<TransferMenuItemParameters> rejectedSkill =
                new MenuOneShotSkill<>(
                        MenuSkills.TRANSFER_MENU_ITEM,
                        MenuSkillParameters::parseTransfer,
                        rejected::checkTransfer,
                        rejected::transfer
                );
        assertEquals(
                "menu.state_changed",
                rejectedSkill.preconditions(context(1), parameters)
                        .orElseThrow()
                        .code()
        );
        assertTrue(rejected.operations.isEmpty());
    }

    @Test
    void waitCompletesOnChangeAndFailsOnTimeout() throws Exception {
        final RecordingActuator actuator = new RecordingActuator();
        final WaitForMenuChangeSkill skill =
                new WaitForMenuChangeSkill(actuator);
        final WaitForMenuChangeParameters parameters =
                new WaitForMenuChangeParameters(
                        new MenuBinding(5, 2, 8),
                        5
                );

        assertTrue(skill.preconditions(context(10), parameters).isEmpty());
        skill.start(context(10), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(11), parameters).status()
        );
        actuator.change = MenuChangeState.CHANGED;
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(12), parameters).status()
        );

        final RecordingActuator timeoutActuator =
                new RecordingActuator();
        final WaitForMenuChangeSkill timeout =
                new WaitForMenuChangeSkill(timeoutActuator);
        timeout.start(context(20), parameters);
        final SkillTickResult timedOut =
                timeout.tick(context(25), parameters);
        assertEquals(SkillTickResult.Status.FAILED, timedOut.status());
        assertEquals(
                "wait_for_menu_change.timeout",
                timedOut.failure().orElseThrow().code()
        );
    }

    @Test
    void waitRejectsReplacementBodySession() throws Exception {
        final RecordingActuator actuator = new RecordingActuator();
        final WaitForMenuChangeSkill skill =
                new WaitForMenuChangeSkill(actuator);
        final WaitForMenuChangeParameters parameters =
                new WaitForMenuChangeParameters(
                        new MenuBinding(5, 2, 8),
                        20
                );
        skill.start(context(1), parameters);
        actuator.change = MenuChangeState.SESSION_MISMATCH;

        final SkillTickResult result = skill.tick(context(2), parameters);
        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "wait_for_menu_change.session_mismatch",
                result.failure().orElseThrow().code()
        );
    }

    @Test
    void selectsOnlyAnExactlyBoundObservedOption() throws Exception {
        final RecordingActuator actuator = new RecordingActuator();
        final MenuOneShotSkill<SelectMenuOptionParameters> skill =
                new MenuOneShotSkill<>(
                        MenuSkills.SELECT_MENU_OPTION,
                        MenuSkillParameters::parseSelectOption,
                        actuator::checkSelectOption,
                        actuator::selectOption
                );
        final var parsed = MenuSkillParameters.parseSelectOption(
                List.of(
                        new SkillArgument("sampleSequence", "5"),
                        new SkillArgument("containerId", "2"),
                        new SkillArgument("stateId", "8"),
                        new SkillArgument("optionId", "1")
                )
        );
        assertTrue(parsed.value().isPresent());
        final SelectMenuOptionParameters parameters =
                parsed.value().orElseThrow();
        assertTrue(skill.preconditions(context(1), parameters).isEmpty());
        skill.start(context(1), parameters);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(2), parameters).status()
        );
        assertEquals(List.of("select:1"), actuator.operations);

        assertTrue(
                !MenuSkillParameters.parseSelectOption(
                        List.of(
                                new SkillArgument("sampleSequence", "5"),
                                new SkillArgument("containerId", "2"),
                                new SkillArgument("stateId", "8"),
                                new SkillArgument("optionId", "01")
                        )
                ).value().isPresent()
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }

    private static final class RecordingActuator
            implements MenuSkillActuator {
        private final List<String> operations = new ArrayList<>();
        private MenuOperationResult transferCheck =
                MenuOperationResult.success();
        private MenuChangeState change = MenuChangeState.UNCHANGED;

        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(9);
        }

        @Override
        public MenuOperationResult checkTransfer(
                final TransferMenuItemParameters parameters
        ) {
            return transferCheck;
        }

        @Override
        public MenuOperationResult transfer(
                final TransferMenuItemParameters parameters
        ) {
            operations.add("transfer");
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
            operations.add(outputOnly ? "take" : "quick_move");
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
            operations.add("select:" + parameters.optionId());
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
            operations.add("close");
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
            return change;
        }
    }
}
