package dev.mcai.companion.skills.portal;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class PortalBuildSkills {
    public static final String BUILD_AND_LIGHT_NETHER_PORTAL =
            BuildAndLightNetherPortalSkill.NAME;
    public static final String ACTIVATE_OBSERVED_END_PORTAL =
            ActivateObservedEndPortalSkill.NAME;
    public static final String CAST_OBSERVED_NETHER_PORTAL =
            CastObservedNetherPortalSkill.NAME;

    private PortalBuildSkills() {
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource frames,
            final InventorySkillActuator inventory,
            final LongSupplier sessionGeneration
    ) {
        Objects.requireNonNull(sessionGeneration, "sessionGeneration");
        registerBuildSkill(
                registry,
                playerId,
                core,
                frames,
                null,
                inventory
        );
        return registry.register(
                ACTIVATE_OBSERVED_END_PORTAL,
                new ActivateObservedEndPortalSkill(
                        playerId,
                        core,
                        frames,
                        inventory,
                        sessionGeneration
                )
        );
    }

    private static SkillRegistry registerBuildSkill(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource frames,
            final InteractionSkillFrameSource interactionFrames,
            final InventorySkillActuator inventory
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(core, "core");
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(inventory, "inventory");
        registry.register(
                BUILD_AND_LIGHT_NETHER_PORTAL,
                interactionFrames == null
                        ? new BuildAndLightNetherPortalSkill(
                                playerId,
                                core,
                                frames,
                                inventory
                        )
                        : new BuildAndLightNetherPortalSkill(
                                playerId,
                                core,
                                frames,
                                interactionFrames,
                                inventory
                        )
        );
        return registry;
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource frames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final InventorySkillActuator inventory,
            final LongSupplier sessionGeneration
    ) {
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(interactionFrames, "interactionFrames");
        Objects.requireNonNull(sessionGeneration, "sessionGeneration");
        registerBuildSkill(
                registry,
                playerId,
                core,
                frames,
                interactionFrames,
                inventory
        );
        registry.register(
                ACTIVATE_OBSERVED_END_PORTAL,
                new ActivateObservedEndPortalSkill(
                        playerId,
                        core,
                        frames,
                        inventory,
                        sessionGeneration
                )
        );
        return registry.register(
                CAST_OBSERVED_NETHER_PORTAL,
                new CastObservedNetherPortalSkill(
                        playerId,
                        core,
                        frames,
                        interactions,
                        interactionFrames,
                        inventory
                )
        );
    }

    public static String plannerGuide() {
        return """
            build_and_light_nether_portal: dimension,x/y/z,axis; needs visible
            4x5 site,14 obsidian,flint. x/y/z is the lower-left base block,
            normally in the ground plane one block below the player's feet;
            a current observed backing wall or safe elevated work position
            makes every placement face reachable. A partially observed
            obsidian frame resumes and needs only its missing blocks.
            cast_observed_nether_portal: dimension,sampleSequence,anchorX/Y/Z,
            axis,operation; cast_next adds frameIndex0..9,lavaX/Y/Z and casts
            one source; repeat. light verifies the frame.
            activate_observed_end_portal: none; visible frames prove center;
            inserts eyes.
            """;
    }
}
