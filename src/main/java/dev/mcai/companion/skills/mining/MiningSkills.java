package dev.mcai.companion.skills.mining;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.gathering.ResourceInventorySource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import java.util.Objects;
import java.util.UUID;

/**
 * Registration slice for observation-driven, non-cheating excavation.
 */
public final class MiningSkills {
    public static final String EXCAVATE_SAFE_TUNNEL =
            ExcavateSafeTunnelSkill.NAME;

    private MiningSkills() {
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator coreActuator,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactionActuator,
            final InteractionSkillFrameSource interactionFrames,
            final ResourceInventorySource inventory
    ) {
        return registerAll(
                registry,
                playerId,
                coreActuator,
                coreFrames,
                interactionActuator,
                interactionFrames,
                inventory,
                MiningSkillPolicy.defaults()
        );
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator coreActuator,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactionActuator,
            final InteractionSkillFrameSource interactionFrames,
            final ResourceInventorySource inventory,
            final MiningSkillPolicy policy
    ) {
        Objects.requireNonNull(registry, "registry");
        return registry.register(
                EXCAVATE_SAFE_TUNNEL,
                new ExcavateSafeTunnelSkill(
                        Objects.requireNonNull(playerId, "playerId"),
                        Objects.requireNonNull(
                                coreActuator,
                                "coreActuator"
                        ),
                        Objects.requireNonNull(coreFrames, "coreFrames"),
                        Objects.requireNonNull(
                                interactionActuator,
                                "interactionActuator"
                        ),
                        Objects.requireNonNull(
                                interactionFrames,
                                "interactionFrames"
                        ),
                        Objects.requireNonNull(inventory, "inventory"),
                        Objects.requireNonNull(policy, "policy")
                )
        );
    }

    public static String plannerGuide() {
        return """
            excavate_safe_tunnel requires dimension, current sampleSequence,
            north/south/east/west direction,
            horizontal/descending/ascending mode,
            maximumSteps 1..48, torchInterval 4..8, owned pickaxeItemId,
            and 1..8 comma-separated targetBlockIds.
            Observe the floor UP face first. It uses ordinary mining/movement,
            fresh visible faces, verified air/support and owned torches;
            descending drops and ascending climbs one block per step.
            It never reads hidden blocks
            and stops when a requested target first becomes visible.
            Unknown/stale geometry, fluids, falling blocks, danger, low
            reserves, full inventory, weak tools, missing light, binding
            changes or timeouts fail closed.
            """;
    }
}
