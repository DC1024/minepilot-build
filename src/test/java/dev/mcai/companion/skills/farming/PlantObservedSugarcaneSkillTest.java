package dev.mcai.companion.skills.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PlantObservedSugarcaneSkillTest {
    private static final ObservedBlockTarget SUPPORT =
            new ObservedBlockTarget(21, 1, 63, 0, BlockFace.UP);

    @Test
    void plantsWithObservedWaterAndConfirmsFreshPlantObservation() {
        var frames = new FarmingSkillTestFixtures.MutableFrames(
                frame(21, HeldItemSummary.empty(), 2, false)
        );
        var coreFrames = new FarmingSkillTestFixtures.CoupledCoreFrames(frames);
        var core = new FarmingSkillTestFixtures.RecordingCoreActuator();
        var actuator = new FarmingSkillTestFixtures.RecordingActuator();
        var skill = new PlantObservedSugarcaneSkill(
                FarmingSkillTestFixtures.PLAYER_ID,
                core,
                coreFrames,
                actuator,
                frames,
                FarmingSkillPolicy.defaults()
        );
        var parameters = new PlantObservedSugarcaneParameters(
                DimensionRef.OVERWORLD,
                SUPPORT
        );
        var context = new SkillContext(1, 1, 100, false, false, 0.0);

        assertTrue(skill.preconditions(context, parameters).isEmpty());
        skill.start(context, parameters);

        SkillTickResult aim = skill.tick(
                new SkillContext(1, 1, 101, false, false, 0.0),
                parameters
        );
        assertEquals(SkillTickResult.Status.RUNNING, aim.status());
        assertTrue(core.looks.size() >= 1);

        // The production skill deliberately chooses an interior top-face
        // point.  Reflect the ordinary player's next-frame turn before the
        // inventory/use assertions below.
        coreFrames.lookOverride = new PerceptionVec3(
                0.75,
                -1.62,
                0.25
        ).normalized();
        SkillTickResult equip = skill.tick(
                new SkillContext(1, 1, 102, false, false, 0.0),
                parameters
        );
        assertEquals(SkillTickResult.Status.RUNNING, equip.status());
        assertEquals(List.of("minecraft:sugar_cane"), actuator.equipped);

        frames.frame = frame(
                22,
                new HeldItemSummary("minecraft:sugar_cane", 2, 0, 0),
                2,
                false
        );
        SkillTickResult dispatch = skill.tick(
                new SkillContext(1, 1, 103, false, false, 0.0),
                parameters
        );
        assertEquals(SkillTickResult.Status.RUNNING, dispatch.status());
        assertEquals(1, actuator.uses.size());
        assertEquals(ActionHand.MAIN_HAND, actuator.useHands.getFirst());

        frames.frame = frame(
                23,
                new HeldItemSummary("minecraft:sugar_cane", 1, 0, 0),
                1,
                true
        );
        SkillTickResult completed = skill.tick(
                new SkillContext(1, 1, 104, false, false, 0.0),
                parameters
        );
        assertEquals(SkillTickResult.Status.COMPLETED, completed.status());
    }

    @Test
    void refusesWithoutVisibleAdjacentWaterOrOwnedCane() {
        var noWater = new FarmingSkillTestFixtures.MutableFrames(
                frame(21, HeldItemSummary.empty(), 2, false, false)
        );
        var skill = new PlantObservedSugarcaneSkill(
                FarmingSkillTestFixtures.PLAYER_ID,
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                new FarmingSkillTestFixtures.CoupledCoreFrames(noWater),
                new FarmingSkillTestFixtures.RecordingActuator(),
                noWater,
                FarmingSkillPolicy.defaults()
        );
        var parameters = new PlantObservedSugarcaneParameters(
                DimensionRef.OVERWORLD,
                SUPPORT
        );
        var context = new SkillContext(1, 1, 100, false, false, 0.0);
        assertTrue(skill.preconditions(context, parameters).isPresent());

        var noCane = new FarmingSkillTestFixtures.MutableFrames(
                frame(21, HeldItemSummary.empty(), 0, false)
        );
        var noCaneSkill = new PlantObservedSugarcaneSkill(
                FarmingSkillTestFixtures.PLAYER_ID,
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                new FarmingSkillTestFixtures.CoupledCoreFrames(noCane),
                new FarmingSkillTestFixtures.RecordingActuator(),
                noCane,
                FarmingSkillPolicy.defaults()
        );
        assertTrue(noCaneSkill.preconditions(context, parameters).isPresent());
    }

    private static InteractionSkillFrame frame(
            long sequence,
            HeldItemSummary mainHand,
            int caneCount,
            boolean planted
    ) {
        return frame(sequence, mainHand, caneCount, planted, true);
    }

    private static InteractionSkillFrame frame(
            long sequence,
            HeldItemSummary mainHand,
            int caneCount,
            boolean planted,
            boolean water
    ) {
        List<VisibleBlockFace> blocks = new ArrayList<>();
        blocks.add(new VisibleBlockFace(
                new BlockCoordinate(1, 63, 0),
                "minecraft:sand",
                "up",
                new PerceptionVec3(1.5, 64.0, 0.5),
                3.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of()
        ));
        if (water) {
            blocks.add(new VisibleBlockFace(
                    new BlockCoordinate(2, 63, 0),
                    "minecraft:water",
                    "west",
                    new PerceptionVec3(2.0, 63.5, 0.5),
                    3.2,
                    PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                    Map.of()
            ));
        }
        if (planted) {
            blocks.add(new VisibleBlockFace(
                    new BlockCoordinate(1, 64, 0),
                    "minecraft:sugar_cane",
                    "west",
                    new PerceptionVec3(1.0, 64.5, 0.5),
                    3.1,
                    PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                    Map.of()
            ));
        }
        List<InventoryItemSummary> inventory = caneCount > 0
                ? List.of(new InventoryItemSummary(
                        "minecraft:sugar_cane",
                        caneCount
                ))
                : List.of();
        return new InteractionSkillFrame(
                FarmingSkillTestFixtures.PLAYER_ID,
                DimensionRef.OVERWORLD,
                100 + sequence,
                100 + sequence,
                sequence,
                FarmingSkillTestFixtures.SESSION,
                mainHand,
                HeldItemSummary.empty(),
                List.of(),
                blocks,
                inventory
        );
    }
}
