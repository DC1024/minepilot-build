package dev.mcai.companion.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.mcai.companion.agent.AgentAccentColor;
import java.util.List;
import java.util.Objects;

/**
 * Flat map codec grouped as one parent record field. Keeping the individual
 * serialized keys flat preserves old {@code display_name} worlds while
 * avoiding DataFixerUpper's sixteen-field applicative group limit.
 */
public record AgentPresentationState(
    String displayName,
    String accentColor,
    double temperature,
    String systemPrompt,
    boolean onboardingCompleted,
    List<String> knownPlayerNames
) {
    public static final AgentPresentationState DEFAULT =
        new AgentPresentationState(
            "MCAI",
            "emerald",
            0.2,
            "",
            false,
            List.of()
        );

    public static final MapCodec<AgentPresentationState> MAP_CODEC =
        RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.optionalFieldOf(
                "display_name",
                DEFAULT.displayName()
            ).forGetter(AgentPresentationState::displayName),
            Codec.STRING.optionalFieldOf(
                "accent_color",
                DEFAULT.accentColor()
            ).forGetter(AgentPresentationState::accentColor),
            /*
             * Keep the original persisted key for dev-world migration. Its
             * old 0..100 value maps exactly to the real 0.0..1.0 sampling
             * temperature and is never exposed as a second setting.
             */
            Codec.INT.xmap(
                value -> value / 100.0,
                value -> (int) Math.round(value * 100.0)
            ).optionalFieldOf(
                "temperament",
                DEFAULT.temperature()
            ).forGetter(AgentPresentationState::temperature),
            Codec.STRING.optionalFieldOf(
                "agent_system_prompt",
                DEFAULT.systemPrompt()
            ).forGetter(AgentPresentationState::systemPrompt),
            Codec.BOOL.optionalFieldOf(
                "onboarding_completed",
                DEFAULT.onboardingCompleted()
            ).forGetter(AgentPresentationState::onboardingCompleted),
            Codec.STRING.listOf().optionalFieldOf(
                "known_player_names",
                DEFAULT.knownPlayerNames()
            ).forGetter(AgentPresentationState::knownPlayerNames)
        ).apply(instance, AgentPresentationState::new));

    public AgentPresentationState {
        displayName = Objects.requireNonNullElse(
            displayName,
            "MCAI"
        ).strip();
        if (displayName.isEmpty() || displayName.length() > 16) {
            throw new IllegalArgumentException(
                "Persisted Agent name exceeds its bound"
            );
        }
        accentColor = AgentAccentColor.parse(accentColor).serializedName();
        if (!Double.isFinite(temperature)
            || temperature < 0.0
            || temperature > 1.0) {
            throw new IllegalArgumentException(
                "Persisted Agent temperature is outside [0.0,1.0]"
            );
        }
        systemPrompt = Objects.requireNonNullElse(systemPrompt, "").strip();
        if (systemPrompt.length() > 4_096
            || systemPrompt.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                "Persisted Agent system prompt exceeds its bound"
            );
        }
        knownPlayerNames = List.copyOf(
            Objects.requireNonNullElse(knownPlayerNames, List.of())
        );
        if (knownPlayerNames.size() > 4_096) {
            throw new IllegalArgumentException(
                "Persisted known-player name set exceeds its bound"
            );
        }
    }
}
