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
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PrepareAndPlantPlotSkillTest {
    private static final String STONE_HOE = "minecraft:stone_hoe";

    @Test
    void tillsThenPlantsOnlyAfterFreshFarmlandAndCropEvidence()
            throws Exception {
        final var frames = new FarmingSkillTestFixtures.MutableFrames(
                plotFrame(
                        INITIAL_SEQUENCE,
                        SESSION,
                        groundFace("minecraft:grass_block"),
                        STONE_HOE,
                        5,
                        true
                )
        );
        final var coreFrames =
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames);
        final var coreActuator =
                new FarmingSkillTestFixtures.RecordingCoreActuator();
        final var actuator =
                new FarmingSkillTestFixtures.RecordingActuator();
        final var skill = skill(
                coreActuator,
                coreFrames,
                actuator,
                frames,
                FarmingSkillPolicy.defaults()
        );

        assertTrue(skill.preconditions(context(100), parameters()).isEmpty());
        skill.start(context(100), parameters());
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101), parameters()).status()
        );
        assertEquals(1, actuator.uses.size());
        assertEquals(63, actuator.uses.getFirst().y());

        frames.frame = plotFrame(
                INITIAL_SEQUENCE + 1,
                SESSION,
                groundFace("minecraft:farmland"),
                STONE_HOE,
                5,
                true
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(102), parameters()).status()
        );
        assertEquals(2, actuator.uses.size());
        assertEquals(
                "minecraft:wheat_seeds",
                actuator.equipped.getLast()
        );

        frames.frame = plotFrame(
                INITIAL_SEQUENCE + 2,
                SESSION,
                FarmingSkillTestFixtures.cropFace("0"),
                "minecraft:wheat_seeds",
                4,
                true
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(103), parameters()).status()
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                skill.result(context(103), parameters()).status()
        );
    }

    @Test
    void alreadyTilledPlotSkipsHoeUseAndPlantsOnce() throws Exception {
        final var frames = new FarmingSkillTestFixtures.MutableFrames(
                plotFrame(
                        INITIAL_SEQUENCE,
                        SESSION,
                        groundFace("minecraft:farmland"),
                        STONE_HOE,
                        3,
                        true
                )
        );
        final var actuator =
                new FarmingSkillTestFixtures.RecordingActuator();
        final var skill = skill(
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames),
                actuator,
                frames,
                FarmingSkillPolicy.defaults()
        );

        skill.start(context(100), parameters());
        skill.tick(context(101), parameters());

        assertEquals(1, actuator.uses.size());
        assertEquals(
                List.of("minecraft:wheat_seeds"),
                actuator.equipped
        );
    }

    @Test
    void rejectsMissingMaterialsOrChangedGroundBeforeMutation()
            throws Exception {
        final var noHoeFrames = new FarmingSkillTestFixtures.MutableFrames(
                plotFrame(
                        INITIAL_SEQUENCE,
                        SESSION,
                        groundFace("minecraft:dirt"),
                        "minecraft:stick",
                        4,
                        false
                )
        );
        var actuator = new FarmingSkillTestFixtures.RecordingActuator();
        var skill = skill(
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                new FarmingSkillTestFixtures.CoupledCoreFrames(noHoeFrames),
                actuator,
                noHoeFrames,
                FarmingSkillPolicy.defaults()
        );
        assertEquals(
                "prepare_and_plant_plot.hoe_unavailable",
                skill.preconditions(context(100), parameters())
                        .orElseThrow().code()
        );

        final var noSeedFrames = new FarmingSkillTestFixtures.MutableFrames(
                plotFrame(
                        INITIAL_SEQUENCE,
                        SESSION,
                        groundFace("minecraft:dirt"),
                        STONE_HOE,
                        0,
                        true
                )
        );
        skill = skill(
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                new FarmingSkillTestFixtures.CoupledCoreFrames(noSeedFrames),
                actuator,
                noSeedFrames,
                FarmingSkillPolicy.defaults()
        );
        assertEquals(
                "prepare_and_plant_plot.seed_unavailable",
                skill.preconditions(context(100), parameters())
                        .orElseThrow().code()
        );

        final var changedFrames = new FarmingSkillTestFixtures.MutableFrames(
                plotFrame(
                        INITIAL_SEQUENCE,
                        SESSION,
                        groundFace("minecraft:dirt"),
                        STONE_HOE,
                        4,
                        true
                )
        );
        changedFrames.frame = plotFrame(
                INITIAL_SEQUENCE + 1,
                SESSION,
                groundFace("minecraft:stone"),
                STONE_HOE,
                4,
                true
        );
        actuator = new FarmingSkillTestFixtures.RecordingActuator();
        skill = skill(
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                new FarmingSkillTestFixtures.CoupledCoreFrames(changedFrames),
                actuator,
                changedFrames,
                FarmingSkillPolicy.defaults()
        );
        assertEquals(
                "prepare_and_plant_plot.ground_not_currently_visible",
                skill.preconditions(context(100), parameters())
                        .orElseThrow().code()
        );
        assertTrue(actuator.uses.isEmpty());
    }

    @Test
    void doesNotUseTheHoeUntilTheBodyHasActuallyTurned()
            throws Exception {
        final var frames = new FarmingSkillTestFixtures.MutableFrames(
                plotFrame(
                        INITIAL_SEQUENCE,
                        SESSION,
                        groundFace("minecraft:dirt"),
                        STONE_HOE,
                        4,
                        true
                )
        );
        final var coreFrames =
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames);
        coreFrames.lookOverride = new PerceptionVec3(-1, 0, 0);
        final var coreActuator =
                new FarmingSkillTestFixtures.RecordingCoreActuator();
        final var actuator =
                new FarmingSkillTestFixtures.RecordingActuator();
        final var skill = skill(
                coreActuator,
                coreFrames,
                actuator,
                frames,
                FarmingSkillPolicy.defaults()
        );

        skill.start(context(100), parameters());
        skill.tick(context(101), parameters());

        assertEquals(1, coreActuator.looks.size());
        assertTrue(actuator.uses.isEmpty());
        coreFrames.lookOverride = null;
        skill.tick(context(102), parameters());
        assertEquals(1, actuator.uses.size());
    }

    @Test
    void farmlandAndCropMustBeConfirmedWithinTheBoundedWindow()
            throws Exception {
        final var frames = new FarmingSkillTestFixtures.MutableFrames(
                plotFrame(
                        INITIAL_SEQUENCE,
                        SESSION,
                        groundFace("minecraft:dirt"),
                        STONE_HOE,
                        4,
                        true
                )
        );
        final var skill = skill(
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames),
                new FarmingSkillTestFixtures.RecordingActuator(),
                frames,
                new FarmingSkillPolicy(10, 100, 2, 6.0)
        );

        skill.start(context(100), parameters());
        skill.tick(context(101), parameters());
        final SkillTickResult result = skill.tick(
                context(103),
                parameters()
        );

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "prepare_and_plant_plot.farmland_not_confirmed",
                result.failure().orElseThrow().code()
        );
    }

    @Test
    void replacementBodySessionCannotContinueTheTransaction()
            throws Exception {
        final var frames = new FarmingSkillTestFixtures.MutableFrames(
                plotFrame(
                        INITIAL_SEQUENCE,
                        SESSION,
                        groundFace("minecraft:dirt"),
                        STONE_HOE,
                        4,
                        true
                )
        );
        final var actuator =
                new FarmingSkillTestFixtures.RecordingActuator();
        final var skill = skill(
                new FarmingSkillTestFixtures.RecordingCoreActuator(),
                new FarmingSkillTestFixtures.CoupledCoreFrames(frames),
                actuator,
                frames,
                FarmingSkillPolicy.defaults()
        );
        skill.start(context(100), parameters());
        skill.tick(context(101), parameters());

        actuator.session = SESSION + 1;
        frames.frame = plotFrame(
                INITIAL_SEQUENCE + 1,
                SESSION + 1,
                groundFace("minecraft:farmland"),
                STONE_HOE,
                4,
                true
        );
        final SkillTickResult result = skill.tick(
                context(102),
                parameters()
        );

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "prepare_and_plant_plot.session_mismatch",
                result.failure().orElseThrow().code()
        );
        assertEquals(1, actuator.uses.size());
    }

    private static PrepareAndPlantPlotSkill skill(
            final FarmingSkillTestFixtures.RecordingCoreActuator coreActuator,
            final FarmingSkillTestFixtures.CoupledCoreFrames coreFrames,
            final FarmingSkillTestFixtures.RecordingActuator actuator,
            final FarmingSkillTestFixtures.MutableFrames frames,
            final FarmingSkillPolicy policy
    ) {
        return new PrepareAndPlantPlotSkill(
                PLAYER_ID,
                coreActuator,
                coreFrames,
                actuator,
                frames,
                policy
        );
    }

    private static PrepareAndPlantPlotParameters parameters() {
        return new PrepareAndPlantPlotParameters(
                DimensionRef.OVERWORLD,
                CropKind.WHEAT,
                new ObservedBlockTarget(
                        INITIAL_SEQUENCE,
                        1,
                        63,
                        0,
                        BlockFace.UP
                )
        );
    }

    private static VisibleBlockFace groundFace(final String blockId) {
        return new VisibleBlockFace(
                new BlockCoordinate(1, 63, 0),
                blockId,
                "up",
                new PerceptionVec3(1.5, 64.0, 0.5),
                3.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                "minecraft:farmland".equals(blockId)
                        ? Map.of("moisture", "7")
                        : Map.of()
        );
    }

    private static InteractionSkillFrame plotFrame(
            final long sequence,
            final long session,
            final VisibleBlockFace face,
            final String mainHandItem,
            final int seedCount,
            final boolean ownHoe
    ) {
        final List<InventoryItemSummary> inventory = new ArrayList<>();
        if (seedCount > 0) {
            inventory.add(new InventoryItemSummary(
                    "minecraft:wheat_seeds",
                    seedCount
            ));
        }
        if (ownHoe) {
            inventory.add(new InventoryItemSummary(STONE_HOE, 1));
        }
        return new InteractionSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                100 + sequence,
                100 + sequence,
                sequence,
                session,
                new HeldItemSummary(mainHandItem, 1, 0, 131),
                HeldItemSummary.empty(),
                List.of(),
                List.of(face),
                inventory
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }
}
