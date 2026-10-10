package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.skill.SkillRegistry;
import java.util.Objects;
import java.util.UUID;

/**
 * Registration slice for ordinary vanilla world interactions.
 */
public final class FairInteractionSkills {
    public static final String BREAK_BLOCK = "break_block";
    public static final String USE_BLOCK = "use_block";
    public static final String ATTACK_ENTITY = "attack_entity";
    public static final String INTERACT_ENTITY = "interact_entity";
    public static final String USE_ITEM = "use_item";
    public static final String CONSUME_OWNED_FOOD =
            ConsumeOwnedFoodSkill.NAME;

    private FairInteractionSkills() {
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            InteractionSkillActuator actuator,
            InteractionSkillFrameSource frames
    ) {
        return registerAll(
                registry,
                playerId,
                actuator,
                frames,
                InteractionSkillPolicy.defaults()
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            InteractionSkillActuator actuator,
            InteractionSkillFrameSource frames,
            InteractionSkillPolicy policy
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(actuator, "actuator");
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(policy, "policy");
        return registry
                .register(BREAK_BLOCK, new BreakBlockSkill(
                        playerId,
                        actuator,
                        frames,
                        policy
                ))
                .register(USE_BLOCK, new UseBlockSkill(
                        playerId,
                        actuator,
                        frames,
                        policy
                ))
                .register(ATTACK_ENTITY, new AttackEntitySkill(
                        playerId,
                        actuator,
                        frames,
                        policy
                ))
                .register(INTERACT_ENTITY, new InteractEntitySkill(
                        playerId,
                        actuator,
                        frames,
                        policy
                ))
                .register(USE_ITEM, new UseItemSkill(
                        playerId,
                        actuator,
                        frames,
                        policy
                ))
                .register(
                    CONSUME_OWNED_FOOD,
                    new ConsumeOwnedFoodSkill(
                        playerId,
                        actuator,
                        frames
                    )
                );
    }

    public static String plannerGuide() {
        return """
            break_block requires dimension, sampleSequence, integer x/y/z,
            and face from one visibleBlockFaces entry.
            use_block requires the same sampled visible block face plus hand
            (main_hand or off_hand). Precise ray hits stay local.
            attack_entity: dimension,sampleSequence,current observationId;
            one in-reach hit only. Use engage_observed_entity for a fight.
            interact_entity: dimension,sampleSequence,observationId,hand;
            ordinary non-attack crosshair interaction with a visible entity.
            look_at should align the crosshair first.
            use_item requires dimension, hand, and holdTicks from 0 through
            1200. Zero is a tap; positive values hold and then release.
            consume_owned_food: dimension,itemId; equips, eats and verifies.
            Every interaction is same-session, same-dimension, reach- and
            line-of-sight checked through vanilla player actions.
            """;
    }
}
