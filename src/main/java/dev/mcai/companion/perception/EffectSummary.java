package dev.mcai.companion.perception;

public record EffectSummary(
        String effectId,
        int amplifier,
        int remainingTicks,
        boolean ambient
) {
    public EffectSummary {
        effectId = PerceptionValidation.identifier(effectId, "effectId");
        if (amplifier < 0 || remainingTicks < -1) {
            throw new IllegalArgumentException("Invalid status effect values");
        }
    }
}
