package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.skills.core.EmergencyMeleeActuator;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Adapts the ordinary interaction actuator to the emergency survival slice.
 */
public final class InteractionEmergencyMeleeActuator
        implements EmergencyMeleeActuator {
    private final InteractionSkillActuator delegate;

    public InteractionEmergencyMeleeActuator(
            final InteractionSkillActuator delegate
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public OptionalDouble attackStrengthScale() {
        return delegate.attackStrengthScale();
    }

    @Override
    public ActionOutcome attack(final UUID entityId) {
        return delegate.attack(Objects.requireNonNull(entityId, "entityId"));
    }
}
