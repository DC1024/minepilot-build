package dev.mcai.companion.skills.end;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.bridging.BridgeMaterialActuator;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Registers the fair, parameterless natural-End ingress controller. */
public final class EndSkills {
    public static final String REACH_END_ISLAND =
            EndIslandIngressSkill.NAME;

    private EndSkills() {
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final BridgeMaterialActuator materials,
            final LongSupplier sessionGeneration,
            final InteractionSkillActuator interactionActuator,
            final InteractionSkillFrameSource interactionFrames,
            final LongConsumer completionSink
    ) {
        return Objects.requireNonNull(registry, "registry").register(
                REACH_END_ISLAND,
                new EndIslandIngressSkill(
                        Objects.requireNonNull(playerId, "playerId"),
                        Objects.requireNonNull(actuator, "actuator"),
                        Objects.requireNonNull(frames, "frames"),
                        Objects.requireNonNull(materials, "materials"),
                        Objects.requireNonNull(
                                sessionGeneration,
                                "sessionGeneration"
                        ),
                        Objects.requireNonNull(
                                interactionActuator,
                                "interactionActuator"
                        ),
                        Objects.requireNonNull(
                                interactionFrames,
                                "interactionFrames"
                        ),
                        Objects.requireNonNull(
                                completionSink,
                                "completionSink"
                        )
                )
        );
    }

    public static String plannerGuide() {
        return """
            reach_end_island has no arguments. Use it after natural End entry
            before fight_ender_dragon; it uses fresh terrain and bounded
            vanilla movement/mining/bridging.
            """;
    }
}
