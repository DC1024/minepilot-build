package dev.mcai.companion.skills.transport;

import dev.mcai.companion.action.ActionOutcome;
import java.util.OptionalLong;
import java.util.UUID;

public interface MinecartSkillActuator {
    OptionalLong sessionGeneration();

    ActionOutcome enterMinecart(UUID observedMinecartId);

    ActionOutcome driveMinecart(
            UUID expectedMinecartId,
            float targetYawDegrees,
            boolean forward,
            boolean backward
    );

    ActionOutcome stopMinecartInput(UUID expectedMinecartId);

    ActionOutcome dismountMinecart(UUID expectedMinecartId);
}
