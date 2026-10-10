package dev.mcai.companion.perception;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * State legitimately owned by the observed {@code ServerPlayer}.
 */
public record BodySnapshot(
        UUID playerId,
        String dimensionId,
        long gameTime,
        PerceptionVec3 position,
        PerceptionVec3 eyePosition,
        PerceptionVec3 lookDirection,
        float health,
        float maxHealth,
        float absorption,
        int foodLevel,
        float saturation,
        int airSupply,
        int maxAirSupply,
        boolean onGround,
        boolean inWater,
        boolean onFire,
        double fallDistance,
        HeldItemSummary mainHand,
        HeldItemSummary offHand,
        List<InventoryItemSummary> inventory,
        List<EffectSummary> effects,
        Set<PerceptionProvenance> provenance
) {
    public BodySnapshot {
        Objects.requireNonNull(playerId, "playerId");
        dimensionId = PerceptionValidation.identifier(dimensionId, "dimensionId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(eyePosition, "eyePosition");
        Objects.requireNonNull(lookDirection, "lookDirection");
        Objects.requireNonNull(mainHand, "mainHand");
        Objects.requireNonNull(offHand, "offHand");
        inventory = List.copyOf(Objects.requireNonNull(inventory, "inventory"));
        effects = List.copyOf(Objects.requireNonNull(effects, "effects"));
        provenance = Set.copyOf(Objects.requireNonNull(provenance, "provenance"));

        health = PerceptionValidation.finite(health, "health");
        maxHealth = PerceptionValidation.finite(maxHealth, "maxHealth");
        absorption = PerceptionValidation.finite(absorption, "absorption");
        saturation = PerceptionValidation.finite(saturation, "saturation");
        fallDistance = PerceptionValidation.finite(fallDistance, "fallDistance");
        if (health < 0.0F || maxHealth <= 0.0F || health > maxHealth
                || absorption < 0.0F
                || foodLevel < 0 || foodLevel > 20
                || saturation < 0.0F || saturation > 20.0F
                || maxAirSupply <= 0 || airSupply > maxAirSupply || airSupply < -1000
                || fallDistance < 0.0) {
            throw new IllegalArgumentException("Invalid body snapshot values");
        }
        if (Math.abs(lookDirection.length() - 1.0) > 1.0E-6) {
            throw new IllegalArgumentException("lookDirection must be normalized");
        }
    }
}
