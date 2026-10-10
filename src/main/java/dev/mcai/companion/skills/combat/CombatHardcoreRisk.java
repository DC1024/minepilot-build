package dev.mcai.companion.skills.combat;

import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Narrow supervisor override for danger that is intrinsic to an intentional
 * combat skill.
 *
 * <p>Proximity and physical-contact signals may be handled by the combat
 * controller's own reach, shield, retreat, health and path policies. Body
 * hazards never receive an override, so fire, low air and an uncontrolled
 * fall still trigger the runtime-wide Hardcore stop before another combat
 * action. A half-health reserve is a defense-in-depth floor; individual
 * skills may impose a stricter reserve.</p>
 */
public final class CombatHardcoreRisk {
    private static final double MINIMUM_HEALTH_FRACTION = 0.50;

    private CombatHardcoreRisk() {
    }

    public static OptionalDouble threshold(
            final SkillContext context,
            final CoreSkillFrame frame,
            final double intentionalRiskCeiling
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(frame, "frame");
        if (!Double.isFinite(intentionalRiskCeiling)
                || intentionalRiskCeiling < 0.0
                || intentionalRiskCeiling > 1.0) {
            throw new IllegalArgumentException(
                    "Intentional combat risk ceiling is invalid"
            );
        }
        if (!context.hardcore()
                || frame.health() / frame.maxHealth()
                    < MINIMUM_HEALTH_FRACTION
                || frame.dangerSignals().stream().anyMatch(signal ->
                        signal.provenance()
                            == PerceptionProvenance.BODY_HAZARD
                )) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(intentionalRiskCeiling);
    }
}
