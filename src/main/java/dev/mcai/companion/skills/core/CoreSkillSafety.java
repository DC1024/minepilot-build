package dev.mcai.companion.skills.core;

import dev.mcai.companion.action.ActionOutcome;

final class CoreSkillSafety {
    private CoreSkillSafety() {
    }

    static boolean quiesce(
            CoreSkillActuator actuator,
            CoreSkillFrame frame
    ) {
        ActionOutcome stopped = actuator.stop();
        ActionOutcome held = actuator.look(
                CoreSkillGeometry.holdLook(frame.lookDirection())
        );
        return stopped.accepted() && held.accepted();
    }
}
