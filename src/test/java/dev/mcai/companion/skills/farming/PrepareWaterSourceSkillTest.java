package dev.mcai.companion.skills.farming;

import static dev.mcai.companion.skills.farming.FarmingSkillTestFixtures.INITIAL_SEQUENCE;
import static dev.mcai.companion.skills.farming.FarmingSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.farming.FarmingSkillTestFixtures.SESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillPolicy;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PrepareWaterSourceSkillTest {
    @Test
    void reportsTheSpecificBreakFailureWithoutOverflowing() throws Exception {
        final var frames = new FarmingSkillTestFixtures.MutableFrames(
                frame(
                        INITIAL_SEQUENCE,
                        SESSION,
                        face(1, 63, 0, "minecraft:dirt"),
                        "minecraft:stone_shovel",
                        1,
                        0
                )
        );
        final var actuator =
                new FarmingSkillTestFixtures.RecordingActuator();
        actuator.beginMiningOutcome =
                dev.mcai.companion.action.ActionOutcome.TARGET_NOT_FOUND;
        final var skill = skill(
                frames,
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames),
                actuator,
                FarmingSkillPolicy.defaults()
        );
        skill.start(context(100), parameters());
        skill.tick(context(101), parameters());
        final SkillTickResult result = skill.tick(
                context(102),
                parameters()
        );

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "prepare_water_source.break_action_target_not_found",
                result.failure().orElseThrow().code()
        );
    }

    @Test
    void excavatesThenPlacesAndConfirmsOwnedWater() throws Exception {
        final var frames = new FarmingSkillTestFixtures.MutableFrames(
                frame(
                        INITIAL_SEQUENCE,
                        SESSION,
                        face(1, 63, 0, "minecraft:dirt"),
                        "minecraft:stone_shovel",
                        1,
                        0
                )
        );
        final var actuator =
                new FarmingSkillTestFixtures.RecordingActuator();
        final var skill = skill(
                frames,
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames),
                actuator,
                FarmingSkillPolicy.defaults()
        );

        assertTrue(skill.preconditions(context(100), parameters()).isEmpty());
        skill.start(context(100), parameters());
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101), parameters()).status()
        );
        assertTrue(actuator.mining.isEmpty());
        skill.tick(context(102), parameters());
        assertEquals(1, actuator.mining.size());

        frames.frame = frame(
                INITIAL_SEQUENCE + 1,
                SESSION,
                face(1, 62, 0, "minecraft:stone"),
                "minecraft:stone_shovel",
                1,
                0
        );
        skill.tick(context(103), parameters());
        assertEquals(
                List.of("minecraft:water_bucket"),
                actuator.equipped
        );
        frames.frame = frame(
                INITIAL_SEQUENCE + 2,
                SESSION,
                face(1, 62, 0, "minecraft:stone"),
                "minecraft:water_bucket",
                1,
                0
        );
        skill.tick(context(104), parameters());
        skill.tick(context(105), parameters());
        assertEquals(1, actuator.itemUses.size());

        frames.frame = frame(
                INITIAL_SEQUENCE + 3,
                SESSION,
                face(1, 63, 0, "minecraft:water"),
                "minecraft:bucket",
                0,
                1
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(106), parameters()).status()
        );
    }

    @Test
    void rejectsSideNetherSupportAndMissingBucket() throws Exception {
        final var frames = new FarmingSkillTestFixtures.MutableFrames(
                frame(
                        INITIAL_SEQUENCE,
                        SESSION,
                        face(1, 63, 0, "minecraft:dirt"),
                        "minecraft:stone_shovel",
                        1,
                        0
                )
        );
        final var skill = skill(
                frames,
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames),
                new FarmingSkillTestFixtures.RecordingActuator(),
                FarmingSkillPolicy.defaults()
        );

        assertEquals(
                "prepare_water_source.ground_face_not_up",
                skill.preconditions(
                        context(100),
                        new PrepareWaterSourceParameters(
                                DimensionRef.OVERWORLD,
                                new ObservedBlockTarget(
                                        INITIAL_SEQUENCE,
                                        1,
                                        63,
                                        0,
                                        BlockFace.NORTH
                                )
                        )
                ).orElseThrow().code()
        );
        assertEquals(
                "prepare_water_source.water_invalid_dimension",
                skill.preconditions(
                        context(100),
                        new PrepareWaterSourceParameters(
                                DimensionRef.NETHER,
                                parameters().ground()
                        )
                ).orElseThrow().code()
        );

        final var supportParameters = new PrepareWaterSourceParameters(
                DimensionRef.OVERWORLD,
                new ObservedBlockTarget(
                        INITIAL_SEQUENCE,
                        0,
                        63,
                        0,
                        BlockFace.UP
                )
        );
        frames.frame = frame(
                INITIAL_SEQUENCE,
                SESSION,
                face(0, 63, 0, "minecraft:dirt"),
                "minecraft:stone_shovel",
                1,
                0
        );
        assertEquals(
                "prepare_water_source.current_support_target",
                skill.preconditions(context(100), supportParameters)
                        .orElseThrow().code()
        );

        frames.frame = frame(
                INITIAL_SEQUENCE,
                SESSION,
                face(1, 63, 0, "minecraft:dirt"),
                "minecraft:stone_shovel",
                0,
                0
        );
        final var withoutBucket = skill(
                frames,
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames),
                new FarmingSkillTestFixtures.RecordingActuator(),
                FarmingSkillPolicy.defaults()
        );
        assertEquals(
                "prepare_water_source.water_bucket_unavailable",
                withoutBucket.preconditions(context(100), parameters())
                        .orElseThrow().code()
        );
    }

    @Test
    void doesNotAcceptBucketChangeWithoutVisibleWater() throws Exception {
        final var frames = new FarmingSkillTestFixtures.MutableFrames(
                frame(
                        INITIAL_SEQUENCE,
                        SESSION,
                        face(1, 63, 0, "minecraft:dirt"),
                        "minecraft:stone_shovel",
                        1,
                        0
                )
        );
        final var skill = skill(
                frames,
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames),
                new FarmingSkillTestFixtures.RecordingActuator(),
                new FarmingSkillPolicy(10, 20, 2, 6.0)
        );
        skill.start(context(100), parameters());
        skill.tick(context(101), parameters());
        skill.tick(context(102), parameters());
        frames.frame = frame(
                INITIAL_SEQUENCE + 1,
                SESSION,
                face(1, 62, 0, "minecraft:stone"),
                "minecraft:stone_shovel",
                1,
                0
        );
        skill.tick(context(103), parameters());
        frames.frame = frame(
                INITIAL_SEQUENCE + 2,
                SESSION,
                face(1, 62, 0, "minecraft:stone"),
                "minecraft:water_bucket",
                1,
                0
        );
        skill.tick(context(104), parameters());
        skill.tick(context(105), parameters());
        frames.frame = frame(
                INITIAL_SEQUENCE + 3,
                SESSION,
                face(1, 62, 0, "minecraft:stone"),
                "minecraft:bucket",
                0,
                1
        );

        final SkillTickResult result = skill.tick(
                context(107),
                parameters()
        );
        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "prepare_water_source.water_not_confirmed",
                result.failure().orElseThrow().code()
        );
        assertEquals(
                "prepare_water_source.water_not_confirmed",
                skill.tick(context(108), parameters())
                        .failure().orElseThrow().code()
        );
    }

    private static PrepareWaterSourceSkill skill(
            final FarmingSkillTestFixtures.MutableFrames frames,
            final FarmingSkillTestFixtures.CoupledCoreFrames coreFrames,
            final FarmingSkillTestFixtures.RecordingActuator actuator,
            final FarmingSkillPolicy policy
    ) {
        return new PrepareWaterSourceSkill(
                PLAYER_ID,
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                coreFrames,
                actuator,
                frames,
                policy,
                new InteractionSkillPolicy(10, 100, 20, 0, 6.0)
        );
    }

    private static PrepareWaterSourceParameters parameters() {
        return new PrepareWaterSourceParameters(
                DimensionRef.OVERWORLD,
                new ObservedBlockTarget(
                        INITIAL_SEQUENCE,
                        1,
                        63,
                        0,
                        BlockFace.UP
                )
        );
    }

    private static InteractionSkillFrame frame(
            final long sequence,
            final long session,
            final VisibleBlockFace visible,
            final String mainHand,
            final int waterBuckets,
            final int emptyBuckets
    ) {
        final List<InventoryItemSummary> inventory = new ArrayList<>();
        inventory.add(new InventoryItemSummary(
                "minecraft:stone_shovel",
                1
        ));
        if (waterBuckets > 0) {
            inventory.add(new InventoryItemSummary(
                    "minecraft:water_bucket",
                    waterBuckets
            ));
        }
        if (emptyBuckets > 0) {
            inventory.add(new InventoryItemSummary(
                    "minecraft:bucket",
                    emptyBuckets
            ));
        }
        return new InteractionSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                100 + sequence,
                100 + sequence,
                sequence,
                session,
                new HeldItemSummary(mainHand, 1, 0, 131),
                HeldItemSummary.empty(),
                List.of(),
                List.of(visible),
                inventory
        );
    }

    private static VisibleBlockFace face(
            final int x,
            final int y,
            final int z,
            final String blockId
    ) {
        return new VisibleBlockFace(
                new BlockCoordinate(x, y, z),
                blockId,
                "up",
                new PerceptionVec3(x + 0.5, y + 1.0, z + 0.5),
                3.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of(),
                "minecraft:water".equals(blockId)
                        ? TopSupportAffordance.NON_STURDY_OR_PARTIAL
                        : TopSupportAffordance.STURDY_FULL_TOP
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(1, 1, tick, true, true, 0.0);
    }
}
