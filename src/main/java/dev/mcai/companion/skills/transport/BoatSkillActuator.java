package dev.mcai.companion.skills.transport;

import dev.mcai.companion.action.ActionOutcome;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Narrow production port for vanilla boat interaction and controls.
 */
public interface BoatSkillActuator {
    OptionalLong sessionGeneration();

    ActionOutcome enterBoat(UUID observedBoatId);

    ActionOutcome driveBoat(
            UUID expectedBoatId,
            BoatControlIntent intent
    );

    ActionOutcome stopBoat(UUID expectedBoatId);

    ActionOutcome dismountBoat(UUID expectedBoatId);
}
