package dev.mcai.companion.skills.bridging;

/**
 * Ordinary player-inventory transaction used only to equip a conservative
 * expendable full block.
 */
@FunctionalInterface
public interface BridgeMaterialActuator {
    BridgeMaterialResult ensureEquipped();
}
