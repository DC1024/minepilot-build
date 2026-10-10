package dev.mcai.companion.skills.core;

import dev.mcai.companion.action.ActionOutcome;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Narrow vanilla-action boundary used only by the local survival fail-safe.
 *
 * <p>The emergency controller may counterattack only an entity already
 * present in its current fair first-person observation. Implementations must
 * independently revalidate identity, crosshair, reach, occlusion, cooldown
 * and player state before dispatching the normal player attack.</p>
 */
public interface EmergencyMeleeActuator {
    OptionalDouble attackStrengthScale();

    ActionOutcome attack(UUID entityId);

    static EmergencyMeleeActuator unavailable() {
        return new EmergencyMeleeActuator() {
            @Override
            public OptionalDouble attackStrengthScale() {
                return OptionalDouble.empty();
            }

            @Override
            public ActionOutcome attack(final UUID entityId) {
                return ActionOutcome.PLAYER_UNAVAILABLE;
            }
        };
    }
}
