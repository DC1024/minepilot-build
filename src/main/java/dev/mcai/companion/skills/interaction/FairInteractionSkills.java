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
            break_block: dimension, sampleSequence, x/y/z, face from one
            visibleBlockFaces entry.
            use_block: the same sampled face plus hand (main_hand or
            off_hand). It only right-clicks with what that hand holds, so
            equip_item a block into mainhand before placing. Aim at a
            reachable face whose air neighbour is empty, never the cell
            the companion stands in.
            attack_entity: dimension, sampleSequence, observationId; one
            in-reach hit. Use engage_observed_entity for a fight.
            interact_entity: dimension, sampleSequence, observationId,
            hand; non-attack crosshair interaction.
            look_at aligns the crosshair first.
            use_item: dimension, hand, holdTicks 0-1200. Zero taps;
            positive holds then releases.
            consume_owned_food: dimension, itemId; equips and eats.
            Every interaction is same-session, same-dimension, reach- and
            line-of-sight checked.
            """;
    }
}
