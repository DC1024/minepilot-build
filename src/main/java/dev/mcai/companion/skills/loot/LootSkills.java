package dev.mcai.companion.skills.loot;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import java.util.Objects;
import java.util.UUID;

public final class LootSkills {
    public static final String COLLECT_OBSERVED_ITEM =
            CollectObservedItemSkill.NAME;
    public static final String ENGAGE_AND_COLLECT_OBSERVED_DROP =
            EngageAndCollectObservedDropSkill.NAME;
    public static final String HUNT_OBSERVED_FOOD_ANIMAL =
            HuntObservedFoodAnimalSkill.NAME;
    public static final String SECURE_VISIBLE_FOOD_RESERVE =
            SecureVisibleFoodReserveSkill.NAME;
    public static final String ACQUIRE_NETHER_BLAZE_ROD =
            AcquireNetherBlazeRodSkill.NAME;
    public static final String SECURE_NETHER_BLAZE_MATERIAL =
            SecureNetherBlazeMaterialSkill.NAME;
    public static final String ACQUIRE_SHELTERED_ENDER_PEARL =
            AcquireShelteredEnderPearlSkill.NAME;
    public static final String SECURE_ENDER_PEARL_RESERVE =
            SecureEnderPearlReserveSkill.NAME;

    private LootSkills() {
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames
    ) {
        return Objects.requireNonNull(registry, "registry")
                .register(
                        COLLECT_OBSERVED_ITEM,
                        new CollectObservedItemSkill(
                                Objects.requireNonNull(
                                        playerId,
                                        "playerId"
                                ),
                                Objects.requireNonNull(core, "core"),
                                Objects.requireNonNull(
                                        coreFrames,
                                        "coreFrames"
                                ),
                                Objects.requireNonNull(
                                        interactions,
                                        "interactions"
                                ),
                                Objects.requireNonNull(
                                        interactionFrames,
                                        "interactionFrames"
                                )
                        )
                )
                .register(
                        ENGAGE_AND_COLLECT_OBSERVED_DROP,
                        new EngageAndCollectObservedDropSkill(
                                playerId,
                                core,
                                coreFrames,
                                interactions,
                                interactionFrames
                        )
                )
                .register(
                        HUNT_OBSERVED_FOOD_ANIMAL,
                        new HuntObservedFoodAnimalSkill(
                                playerId,
                                core,
                                coreFrames,
                                interactions,
                                interactionFrames
                        )
                )
                .register(
                        SECURE_VISIBLE_FOOD_RESERVE,
                        new SecureVisibleFoodReserveSkill(
                                playerId,
                                core,
                                coreFrames,
                                interactions,
                                interactionFrames
                        )
                )
                .register(
                        ACQUIRE_NETHER_BLAZE_ROD,
                        new AcquireNetherBlazeRodSkill(
                                playerId,
                                core,
                                coreFrames,
                                interactions,
                                interactionFrames
                        )
                )
                .register(
                        SECURE_NETHER_BLAZE_MATERIAL,
                        new SecureNetherBlazeMaterialSkill(
                                playerId,
                                core,
                                coreFrames,
                                interactions,
                                interactionFrames
                        )
                )
                .register(
                        ACQUIRE_SHELTERED_ENDER_PEARL,
                        new AcquireShelteredEnderPearlSkill(
                                playerId,
                                core,
                                coreFrames,
                                interactions,
                                interactionFrames
                        )
                )
                .register(
                        SECURE_ENDER_PEARL_RESERVE,
                        new SecureEnderPearlReserveSkill(
                                playerId,
                                core,
                                coreFrames,
                                interactions,
                                interactionFrames
                        )
                );
    }

    public static String plannerGuide() {
        return """
            collect_observed_item: sampleSequence,observationId,maximumTicks.
            engage_and_collect_observed_drop: same plus expectedItemId; melee
            then pickup.
            hunt_observed_food_animal: same; one legal visible adult.
            secure_visible_food_reserve: no args; legal visible adults to
            eight safe foods.
            acquire_nether_blaze_rod: observation-bound visible Nether Blaze.
            secure_nether_blaze_material: no args; fair search and repeated
            visible-Blaze combat/pickup until fourteen route units.
            acquire_sheltered_ender_pearl: observation-bound Enderman under
            an observed 3x3 safe roof.
            secure_ender_pearl_reserve: no args; roof/search/safe kills
            to fourteen pearl route units.
            """;
    }
}
