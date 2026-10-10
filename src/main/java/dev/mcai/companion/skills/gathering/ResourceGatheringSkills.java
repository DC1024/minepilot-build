package dev.mcai.companion.skills.gathering;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import java.util.Objects;
import java.util.UUID;

/**
 * Registration slice for bounded, fair, multi-block resource work.
 */
public final class ResourceGatheringSkills {
    public static final String GATHER_VISIBLE_BLOCK_CLUSTER =
            "gather_visible_block_cluster";
    public static final String GATHER_NEARBY_WOOD =
            GatherNearbyWoodSkill.NAME;

    private ResourceGatheringSkills() {
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            InteractionSkillActuator interactionActuator,
            InteractionSkillFrameSource interactionFrames,
            ResourceInventorySource inventory
    ) {
        return registerAll(
                registry,
                playerId,
                coreActuator,
                coreFrames,
                interactionActuator,
                interactionFrames,
                inventory,
                GatheringSkillPolicy.defaults()
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            CoreSkillActuator coreActuator,
            CoreSkillFrameSource coreFrames,
            InteractionSkillActuator interactionActuator,
            InteractionSkillFrameSource interactionFrames,
            ResourceInventorySource inventory,
            GatheringSkillPolicy policy
    ) {
        Objects.requireNonNull(registry, "registry");
        registry.register(
                GATHER_VISIBLE_BLOCK_CLUSTER,
                new GatherVisibleBlockClusterSkill(
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
        return registry.register(
                GATHER_NEARBY_WOOD,
                new GatherNearbyWoodSkill(
                        playerId,
                        coreActuator,
                        coreFrames,
                        interactionActuator,
                        interactionFrames,
                        inventory
                )
        );
    }

    public static String plannerGuide() {
        return """
            gather_nearby_wood takes no arguments; it fairly scans, walks,
            mines nearby tree wood, and physically picks up the drops without
            another model decision.
            gather_visible_block_cluster is a bounded local long task for one
            six-direction-connected cluster of exactly one block type. Pass
            dimension, sampleSequence, x/y/z, face, and blockId from one
            visibleBlockFaces entry or one recentFairSurveyData.observedBlocks
            entry; never mix fields between entries. Also pass maxBlocks
            (1..64), clusterRadius (1..16), and toolItemId. toolItemId must be
            an owned tool to equip, or minecraft:air to keep the current hand.
            A retained survey seed is used only to turn back toward that
            location and must become freshly visible again before mining.
            Every additional block must become visible in a later fair
            first-person semantic observation; the skill never scans chunks,
            hidden ores, loot, structures, or unseen blocks. It walks through
            observed safe space, mines through vanilla player actions,
            physically approaches visible nearby drops, preserves a durability
            reserve, stops for danger or a full inventory, and checkpoints
            throughout. This generic gatherer does not replant saplings or
            crops: follow tree work with an explicit visible
            placement/planting skill, and use harvest_and_replant_step for
            supported mature crops.
            """;
    }
}
