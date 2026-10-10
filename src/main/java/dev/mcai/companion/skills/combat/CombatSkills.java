package dev.mcai.companion.skills.combat;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.bridging.BridgeMaterialActuator;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Registration slice for fair, local, one-target combat.
 */
public final class CombatSkills {
    public static final String ENGAGE_OBSERVED_ENTITY =
            "engage_observed_entity";
    public static final String FIGHT_ENDER_DRAGON =
            FightEnderDragonSkill.NAME;

    private CombatSkills() {
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            InteractionSkillActuator interactionActuator,
            InteractionSkillFrameSource interactionFrames
    ) {
        return registerAll(
                registry,
                playerId,
                coreActuator,
                coreFrames,
                interactionActuator,
                interactionFrames,
                CombatSkillPolicy.defaults()
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            InteractionSkillActuator interactionActuator,
            InteractionSkillFrameSource interactionFrames,
            CombatSkillPolicy policy
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(coreActuator, "coreActuator");
        Objects.requireNonNull(coreFrames, "coreFrames");
        Objects.requireNonNull(
                interactionActuator,
                "interactionActuator"
        );
        Objects.requireNonNull(interactionFrames, "interactionFrames");
        Objects.requireNonNull(policy, "policy");
        return registry
                .register(
                        ENGAGE_OBSERVED_ENTITY,
                        new EngageObservedEntitySkill(
                                playerId,
                                coreActuator,
                                coreFrames,
                                interactionActuator,
                                interactionFrames,
                                policy
                        )
                )
                .register(
                        ShootObservedEntitySkill.NAME,
                        new ShootObservedEntitySkill(
                                playerId,
                                coreActuator,
                                coreFrames,
                                interactionActuator,
                                interactionFrames,
                                RangedCombatSkillPolicy.defaults()
                        )
                );
    }

    public static SkillRegistry registerDragonFight(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator coreActuator,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactionActuator,
            final InteractionSkillFrameSource interactionFrames,
            final InventorySkillActuator inventory,
            final BridgeMaterialActuator bridgeMaterials,
            final DragonVictorySource victory,
            final LongSupplier sessionGeneration
    ) {
        return Objects.requireNonNull(registry, "registry")
                .register(
                        FIGHT_ENDER_DRAGON,
                        new FightEnderDragonSkill(
                                Objects.requireNonNull(
                                        playerId,
                                        "playerId"
                                ),
                                Objects.requireNonNull(
                                        coreActuator,
                                        "coreActuator"
                                ),
                                Objects.requireNonNull(
                                        coreFrames,
                                        "coreFrames"
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
                                        inventory,
                                        "inventory"
                                ),
                                Objects.requireNonNull(
                                        bridgeMaterials,
                                        "bridgeMaterials"
                                ),
                                Objects.requireNonNull(
                                        victory,
                                        "victory"
                                ),
                                Objects.requireNonNull(
                                        sessionGeneration,
                                        "sessionGeneration"
                                )
                        )
                );
    }

    public static String plannerGuide() {
        return """
            engage_observed_entity: sampleSequence,observationId. Binds one
            visible hostile/player locally, never exposes or accepts UUIDs;
            uses safe cells, vanilla attack cooldown, shield/retreat.
            shoot_observed_entity requires sampleSequence, observationId,
            hand (main_hand/off_hand),shots [1,16]; visible legal target plus
            owned bow/crossbow/trident and ammo. Nearby End crystals refused.
            After natural End entry, run parameterless reach_end_island, then
            parameterless fight_ender_dragon. Both bind the current body;
            combat rejects an outer spawn-platform rally, handles only fairly
            observed targets/cages, and wins only on attributed death.
            """;
    }
}
