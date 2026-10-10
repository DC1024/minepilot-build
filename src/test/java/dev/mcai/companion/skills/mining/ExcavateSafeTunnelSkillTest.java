package dev.mcai.companion.skills.mining;

import static dev.mcai.companion.skills.mining.MiningSkillTestFixtures.ORIGIN;
import static dev.mcai.companion.skills.mining.MiningSkillTestFixtures.PICKAXE;
import static dev.mcai.companion.skills.mining.MiningSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.mining.MiningSkillTestFixtures.TARGET;
import static dev.mcai.companion.skills.mining.MiningSkillTestFixtures.destination;
import static dev.mcai.companion.skills.mining.MiningSkillTestFixtures.fastPolicy;
import static dev.mcai.companion.skills.mining.MiningSkillTestFixtures.initial;
import static dev.mcai.companion.skills.mining.MiningSkillTestFixtures.parameters;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ExcavateSafeTunnelSkillTest {
    @Test
    void excavatesTwoHighHorizontalTunnelAndWalksOnlyAfterSupport()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.HORIZONTAL);

        final SkillTickResult result = drive(
                scenario,
                true,
                false
        );

        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
        assertEquals(
                List.of(
                        destination(TunnelMode.HORIZONTAL).above(),
                        destination(TunnelMode.HORIZONTAL)
                ),
                scenario.minedBlocks()
        );
        assertEquals(1, scenario.interaction.placements.size());
        assertFalse(scenario.core.moves.isEmpty());
        assertEquals(
                destination(TunnelMode.HORIZONTAL),
                scenario.frames.coreCurrent().orElseThrow().feet()
        );
        assertTrue(scenario.core.stops > 0);
        assertTrue(scenario.interaction.aborts > 0);
        final String terminalCheckpoint = scenario.skill.checkpoint(
                context(260, false, 0.0),
                scenario.parameters
        ).payload();
        assertTrue(terminalCheckpoint.contains("\"session\":37"));
        assertTrue(terminalCheckpoint.contains("\"completedSteps\":1"));
        assertTrue(terminalCheckpoint.contains("\"stepsSinceTorch\":1"));
        assertTrue(terminalCheckpoint.contains("\"resumable\":false"));
    }

    @Test
    void excavatesDescendingStepOnlyAfterLowerSupportIsObserved()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.DESCENDING);

        final SkillTickResult result = drive(
                scenario,
                true,
                false
        );

        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
        assertEquals(
                new GridPos(1, 63, 0),
                scenario.frames.coreCurrent().orElseThrow().feet()
        );
        assertEquals(
                List.of(
                        new GridPos(1, 64, 0),
                        new GridPos(1, 63, 0)
                ),
                scenario.minedBlocks()
        );
    }

    @Test
    void excavatesAscendingStepAndJumpsOnlyAfterSupportIsObserved()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.ASCENDING);

        final SkillTickResult result = drive(
                scenario,
                true,
                false
        );

        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
        assertEquals(
                destination(TunnelMode.ASCENDING),
                scenario.frames.coreCurrent().orElseThrow().feet()
        );
        assertEquals(
                List.of(
                        destination(TunnelMode.ASCENDING).above(),
                        destination(TunnelMode.ASCENDING)
                ),
                scenario.minedBlocks()
        );
        assertTrue(
                scenario.core.jumps > 0,
                "Ascending traversal never issued a normal player jump"
        );
        assertFalse(scenario.core.moves.isEmpty());
    }

    @Test
    void ascendingStepClearsCeilingOverCurrentFootholdFirst()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.ASCENDING);
        final GridPos transitionHead = ORIGIN.above().above();
        scenario.frames.putVoxel(
                transitionHead,
                VoxelKind.SOLID,
                0.0
        );
        scenario.frames.addFace(
                transitionHead,
                "minecraft:stone",
                BlockFace.DOWN
        );

        final SkillTickResult result = drive(
                scenario,
                true,
                false
        );

        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
        assertEquals(
                List.of(
                        transitionHead,
                        destination(TunnelMode.ASCENDING).above(),
                        destination(TunnelMode.ASCENDING)
                ),
                scenario.minedBlocks()
        );
    }

    @Test
    void descendingStepClearsTheHighSideHeadTransition()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.DESCENDING);
        final GridPos destination =
                destination(TunnelMode.DESCENDING);
        final GridPos transitionHead =
                destination.above().above();
        scenario.frames.putVoxel(
                transitionHead,
                VoxelKind.SOLID,
                0.0
        );
        scenario.frames.addFace(
                transitionHead,
                "minecraft:stone",
                BlockFace.WEST
        );

        final SkillTickResult result = drive(
                scenario,
                true,
                false
        );

        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
        assertEquals(
                List.of(
                        transitionHead,
                        destination.above(),
                        destination
                ),
                scenario.minedBlocks()
        );
    }

    @Test
    void recentersAEdgeStraddlingBodyBeforeDescendingExcavation()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.DESCENDING);
        scenario.frames.position = new PerceptionVec3(
                0.92,
                64.0,
                0.5
        );
        assertTrue(
                scenario.skill.preconditions(
                        context(100, false, 0.0),
                        scenario.parameters
                ).isEmpty()
        );
        scenario.skill.start(
                context(100, false, 0.0),
                scenario.parameters
        );

        int processedMines = 0;
        int processedMoves = 0;
        boolean recenterMoveObserved = false;
        boolean minedOnlyAfterCentering = true;
        SkillTickResult result = null;
        for (long tick = 101; tick < 260; tick++) {
            result = scenario.skill.tick(
                    context(tick, false, 0.0),
                    scenario.parameters
            );
            while (scenario.core.moves.size() > processedMoves) {
                processedMoves++;
                if (scenario.interaction.mines.isEmpty()) {
                    recenterMoveObserved = true;
                    scenario.frames.moveTo(ORIGIN);
                } else {
                    scenario.frames.moveTo(
                            destination(TunnelMode.DESCENDING)
                    );
                }
            }
            while (scenario.interaction.mines.size()
                    > processedMines) {
                minedOnlyAfterCentering &=
                        Math.abs(
                            scenario.frames.position.x() - 0.5
                        ) <= 1.0E-9;
                final GridPos mined = block(
                        scenario.interaction.mines.get(
                                processedMines++
                        )
                );
                scenario.frames.clearBlock(mined);
                final GridPos destination =
                        destination(TunnelMode.DESCENDING);
                if (scenario.frames.voxels.get(destination).kind()
                            == VoxelKind.AIR
                        && scenario.frames.voxels
                            .get(destination.above()).kind()
                            == VoxelKind.AIR) {
                    scenario.frames.exposeSupport(destination);
                }
            }
            scenario.frames.advance();
            if (result.status()
                    != SkillTickResult.Status.RUNNING) {
                break;
            }
        }

        assertTrue(recenterMoveObserved);
        assertTrue(minedOnlyAfterCentering);
        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
        assertEquals(
                List.of(
                        destination(TunnelMode.DESCENDING).above(),
                        destination(TunnelMode.DESCENDING)
                ),
                scenario.minedBlocks()
        );
    }

    @Test
    void stopsAsSoonAsTargetOreBecomesActuallyVisible()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.HORIZONTAL);
        assertTrue(
                scenario.skill.preconditions(
                        context(100, false, 0.0),
                        scenario.parameters
                ).isEmpty()
        );
        scenario.skill.start(
                context(100, false, 0.0),
                scenario.parameters
        );
        int processedMines = 0;
        SkillTickResult result = null;
        for (long tick = 101; tick < 180; tick++) {
            result = scenario.skill.tick(
                    context(tick, false, 0.0),
                    scenario.parameters
            );
            if (scenario.interaction.mines.size()
                    > processedMines) {
                final GridPos mined = block(
                        scenario.interaction.mines
                                .get(processedMines++)
                );
                scenario.frames.clearBlock(mined);
                scenario.frames.addFace(
                        new GridPos(1, 65, 1),
                        TARGET,
                        BlockFace.WEST
                );
            }
            scenario.frames.advance();
            if (result.status()
                    != SkillTickResult.Status.RUNNING) {
                break;
            }
        }

        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
        assertEquals(1, scenario.interaction.mines.size());
        assertTrue(scenario.core.moves.isEmpty());
        assertTrue(
                scenario.skill.checkpoint(
                        context(180, false, 0.0),
                        scenario.parameters
                ).payload().contains("\"exposedTarget\":[1,65,1]")
        );
    }

    @Test
    void descendingObservationEnvelopeIncludesLateLegCells()
            throws Exception {
        final Scenario scenario = scenario(
                TunnelMode.DESCENDING,
                12
        );
        final GridPos lateLegSupport = ORIGIN.offset(10, -11, 0);
        scenario.frames.addFace(
                lateLegSupport,
                TARGET,
                BlockFace.UP
        );
        assertTrue(
                scenario.skill.preconditions(
                        context(100, false, 0.0),
                        scenario.parameters
                ).isEmpty()
        );
        scenario.skill.start(
                context(100, false, 0.0),
                scenario.parameters
        );

        final SkillTickResult result = scenario.skill.tick(
                context(101, false, 0.0),
                scenario.parameters
        );

        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
        assertTrue(scenario.interaction.mines.isEmpty());
    }

    @Test
    void rejectsStaleAndNeverVisibleFacesWithoutDispatchingMining()
            throws Exception {
        final Scenario stale = scenario(TunnelMode.HORIZONTAL);
        stale.frames.observationAge = 11;
        assertEquals(
                "excavate_safe_tunnel.stale_observation",
                stale.skill.preconditions(
                        context(100, false, 0.0),
                        stale.parameters
                ).orElseThrow().code()
        );
        assertTrue(stale.interaction.mines.isEmpty());

        final Scenario invisible = scenario(TunnelMode.HORIZONTAL);
        invisible.frames.removeFace(
                destination(TunnelMode.HORIZONTAL)
        );
        invisible.frames.removeFace(
                destination(TunnelMode.HORIZONTAL).above()
        );
        final SkillTickResult result = drive(
                invisible,
                false,
                false
        );
        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "excavate_safe_tunnel.block_face_not_visible",
                result.failure().orElseThrow().code()
        );
        assertTrue(invisible.interaction.mines.isEmpty());
    }

    @Test
    void refusesUnknownOrDangerousSupportBeforeMovement()
            throws Exception {
        final Scenario unknown = scenario(TunnelMode.HORIZONTAL);
        final SkillTickResult unknownResult = drive(
                unknown,
                false,
                false
        );
        assertEquals(SkillTickResult.Status.FAILED, unknownResult.status());
        assertEquals(
                "excavate_safe_tunnel.support_not_observed",
                unknownResult.failure().orElseThrow().code()
        );
        assertTrue(unknown.core.moves.isEmpty());

        final Scenario dangerous = scenario(TunnelMode.HORIZONTAL);
        dangerous.supportDanger = 0.9;
        final SkillTickResult dangerousResult = drive(
                dangerous,
                true,
                false
        );
        assertEquals(
                SkillTickResult.Status.FAILED,
                dangerousResult.status()
        );
        assertEquals(
                "excavate_safe_tunnel.unsafe_support",
                dangerousResult.failure().orElseThrow().code()
        );
        assertTrue(dangerous.core.moves.isEmpty());
    }

    @Test
    void rejectsPartialTopSupportEvenWhenItsSurfaceIsVisible()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.HORIZONTAL);
        final GridPos destination =
                destination(TunnelMode.HORIZONTAL);
        scenario.frames.putVoxel(
                destination.below(),
                VoxelKind.SOLID,
                0.0
        );
        scenario.frames.addFace(
                destination.below(),
                "minecraft:oak_slab",
                BlockFace.UP,
                TopSupportAffordance.NON_STURDY_OR_PARTIAL
        );

        final SkillTickResult result = drive(
                scenario,
                false,
                false
        );

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "excavate_safe_tunnel.unsafe_support",
                result.failure().orElseThrow().code()
        );
        assertTrue(scenario.core.moves.isEmpty());
    }

    @Test
    void unboundSingleRayAirFailsClosedButExactMinedCellsProgress()
            throws Exception {
        final Scenario exactMined = scenario(TunnelMode.HORIZONTAL);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                drive(exactMined, true, false).status()
        );
        assertEquals(2, exactMined.interaction.mines.size());

        final Scenario unbound = scenario(TunnelMode.HORIZONTAL);
        final GridPos destination =
                destination(TunnelMode.HORIZONTAL);
        unbound.frames.removeFace(destination);
        unbound.frames.removeFace(destination.above());
        unbound.frames.putVoxel(
                destination,
                VoxelKind.AIR,
                0.0,
                OccupancyEvidence.SINGLE_RAY_CLEAR
        );
        unbound.frames.putVoxel(
                destination.above(),
                VoxelKind.AIR,
                0.0,
                OccupancyEvidence.SINGLE_RAY_CLEAR
        );

        final SkillTickResult result = drive(
                unbound,
                false,
                false
        );

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "excavate_safe_tunnel.block_face_not_visible",
                result.failure().orElseThrow().code()
        );
        assertTrue(unbound.interaction.mines.isEmpty());
        assertTrue(unbound.core.moves.isEmpty());
    }

    @Test
    void currentMultiRayAirIsObservedClearInsteadOfMined()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.DESCENDING);
        final GridPos destination =
                destination(TunnelMode.DESCENDING);
        scenario.frames.removeFace(destination.above());
        scenario.frames.putVoxel(
                destination.above(),
                VoxelKind.AIR,
                0.0,
                OccupancyEvidence.MULTI_RAY_CLEAR
        );

        final SkillTickResult result = drive(
                scenario,
                true,
                false
        );

        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
        assertEquals(List.of(destination), scenario.minedBlocks());
        assertFalse(scenario.core.moves.isEmpty());
    }

    @Test
    void minedAndReobservedCellsBridgeAOneFrameMovementFanGap()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.DESCENDING);
        assertTrue(
                scenario.skill.preconditions(
                        context(100, false, 0.0),
                        scenario.parameters
                ).isEmpty()
        );
        scenario.skill.start(
                context(100, false, 0.0),
                scenario.parameters
        );

        final GridPos destination =
                destination(TunnelMode.DESCENDING);
        int processedMines = 0;
        int processedMoves = 0;
        boolean gapInjected = false;
        SkillTickResult result = null;
        for (long tick = 101; tick < 260; tick++) {
            result = scenario.skill.tick(
                    context(tick, false, 0.0),
                    scenario.parameters
            );
            while (scenario.interaction.mines.size()
                    > processedMines) {
                final GridPos mined = block(
                        scenario.interaction.mines.get(
                                processedMines++
                        )
                );
                scenario.frames.clearBlock(mined);
                if (scenario.frames.voxels.get(destination).kind()
                            == VoxelKind.AIR
                        && scenario.frames.voxels
                            .get(destination.above()).kind()
                            == VoxelKind.AIR) {
                    scenario.frames.exposeSupport(destination);
                }
            }
            while (scenario.core.moves.size() > processedMoves) {
                processedMoves++;
                if (!gapInjected) {
                    scenario.frames.removeVoxel(destination);
                    scenario.frames.removeVoxel(destination.above());
                    gapInjected = true;
                }
                scenario.frames.moveTo(destination);
            }
            scenario.frames.advance();
            if (result.status()
                    != SkillTickResult.Status.RUNNING) {
                break;
            }
        }

        assertTrue(gapInjected);
        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
        assertEquals(destination, scenario.frames.coreCurrent()
                .orElseThrow().feet());
    }

    @Test
    void verifiedMinedCellMayLagOneRevisionDuringCommittedMove()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.DESCENDING);
        assertTrue(
                scenario.skill.preconditions(
                        context(100, false, 0.0),
                        scenario.parameters
                ).isEmpty()
        );
        scenario.skill.start(
                context(100, false, 0.0),
                scenario.parameters
        );

        final GridPos destination =
                destination(TunnelMode.DESCENDING);
        int processedMines = 0;
        int processedMoves = 0;
        boolean lagInjected = false;
        SkillTickResult result = null;
        for (long tick = 101; tick < 260; tick++) {
            result = scenario.skill.tick(
                    context(tick, false, 0.0),
                    scenario.parameters
            );
            while (scenario.interaction.mines.size()
                    > processedMines) {
                final GridPos mined = block(
                        scenario.interaction.mines.get(
                                processedMines++
                        )
                );
                scenario.frames.clearBlock(mined);
                if (scenario.frames.voxels.get(destination).kind()
                            == VoxelKind.AIR
                        && scenario.frames.voxels
                            .get(destination.above()).kind()
                            == VoxelKind.AIR) {
                    scenario.frames.exposeSupport(destination);
                }
            }
            while (scenario.core.moves.size() > processedMoves) {
                processedMoves++;
                if (!lagInjected) {
                    scenario.frames.setVoxelRevisionLag(
                            destination,
                            1
                    );
                    lagInjected = true;
                }
                scenario.frames.moveTo(destination);
            }
            scenario.frames.advance();
            if (result.status()
                    != SkillTickResult.Status.RUNNING) {
                break;
            }
        }

        assertTrue(lagInjected);
        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
    }

    @Test
    void committedStepKeepsItsVerifiedSupportAcrossForwardViewTurn()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.DESCENDING);
        assertTrue(
                scenario.skill.preconditions(
                        context(100, false, 0.0),
                        scenario.parameters
                ).isEmpty()
        );
        scenario.skill.start(
                context(100, false, 0.0),
                scenario.parameters
        );

        final GridPos destination =
                destination(TunnelMode.DESCENDING);
        final GridPos support = destination.below();
        int processedMines = 0;
        int processedMoves = 0;
        boolean supportViewDropped = false;
        SkillTickResult result = null;
        for (long tick = 101; tick < 260; tick++) {
            result = scenario.skill.tick(
                    context(tick, false, 0.0),
                    scenario.parameters
            );
            while (scenario.interaction.mines.size()
                    > processedMines) {
                final GridPos mined = block(
                        scenario.interaction.mines.get(
                                processedMines++
                        )
                );
                scenario.frames.clearBlock(mined);
                if (scenario.frames.voxels.get(destination).kind()
                            == VoxelKind.AIR
                        && scenario.frames.voxels
                            .get(destination.above()).kind()
                            == VoxelKind.AIR) {
                    scenario.frames.exposeSupport(destination);
                }
            }
            while (scenario.core.moves.size() > processedMoves) {
                processedMoves++;
                if (!supportViewDropped) {
                    scenario.frames.removeFace(support);
                    scenario.frames.removeVoxel(support);
                    supportViewDropped = true;
                }
                scenario.frames.moveTo(destination);
            }
            scenario.frames.advance();
            if (result.status()
                    != SkillTickResult.Status.RUNNING) {
                break;
            }
        }

        assertTrue(supportViewDropped);
        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
    }

    @Test
    void torchConsumptionWithoutFreshExpectedBlockObservationFails()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.HORIZONTAL);
        scenario.frames.publishPlacedTorch = false;

        final SkillTickResult result = drive(
                scenario,
                false,
                false
        );

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "excavate_safe_tunnel.torch_placement_not_observed",
                result.failure().orElseThrow().code()
        );
        assertEquals(1, scenario.interaction.placements.size());
        assertTrue(scenario.interaction.mines.isEmpty());
    }

    @Test
    void torchUseWaitsForTheLiveCentreCrosshair()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.HORIZONTAL);
        scenario.frames.publishCrosshair = false;
        assertTrue(
                scenario.skill.preconditions(
                        context(100, false, 0.0),
                        scenario.parameters
                ).isEmpty()
        );
        scenario.skill.start(
                context(100, false, 0.0),
                scenario.parameters
        );
        SkillTickResult result = null;
        boolean gated = true;
        for (long tick = 101; tick < 120; tick++) {
            result = scenario.skill.tick(
                    context(tick, false, 0.0),
                    scenario.parameters
            );
            if (gated) {
                assertTrue(
                        scenario.interaction.placements.isEmpty(),
                        "A semantic fan hit must not authorize a stale torch use"
                );
            }
            if (gated && !scenario.core.looks.isEmpty()) {
                scenario.frames.publishCrosshair = true;
                gated = false;
            }
            scenario.frames.advance();
            if (scenario.interaction.placements.size() == 1) {
                break;
            }
        }
        assertEquals(SkillTickResult.Status.RUNNING, result.status());
        assertEquals(1, scenario.interaction.placements.size());
    }

    @Test
    void nearbyCurrentlyVisibleTorchIsReusedAcrossBoundedLegs() {
        final Scenario scenario = scenario(TunnelMode.HORIZONTAL);
        scenario.frames.addFace(
                ORIGIN.offset(0, 0, 1),
                "minecraft:torch",
                BlockFace.WEST
        );
        assertTrue(
                scenario.skill.preconditions(
                        context(100, false, 0.0),
                        scenario.parameters
                ).isEmpty()
        );
        scenario.skill.start(
                context(100, false, 0.0),
                scenario.parameters
        );

        final SkillTickResult result = scenario.skill.tick(
                context(101, false, 0.0),
                scenario.parameters
        );

        assertEquals(SkillTickResult.Status.RUNNING, result.status());
        assertTrue(scenario.interaction.placements.isEmpty());
        assertEquals(8, scenario.frames.torchCount);
        assertTrue(
                scenario.skill.checkpoint(
                        context(101, false, 0.0),
                        scenario.parameters
                ).payload().contains("\"stepsSinceTorch\":0")
        );
    }

    @Test
    void temporarilyOccludedFloorIsReobservedBeforeTorchPlacement()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.HORIZONTAL);
        assertTrue(
                scenario.skill.preconditions(
                        context(100, false, 0.0),
                        scenario.parameters
                ).isEmpty()
        );
        scenario.skill.start(
                context(100, false, 0.0),
                scenario.parameters
        );
        scenario.frames.removeFace(ORIGIN.below());

        final SkillTickResult occluded = scenario.skill.tick(
                context(101, false, 0.0),
                scenario.parameters
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                occluded.status()
        );
        assertFalse(scenario.core.looks.isEmpty());
        assertTrue(scenario.interaction.placements.isEmpty());

        scenario.frames.addFace(
                ORIGIN.below(),
                "minecraft:stone",
                BlockFace.UP
        );
        scenario.frames.advance();
        final SkillTickResult reobserved = scenario.skill.tick(
                context(102, false, 0.0),
                scenario.parameters
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                reobserved.status()
        );
        assertEquals(
                List.of("minecraft:torch"),
                scenario.interaction.equips
        );
    }

    @Test
    void failsClosedForToolTorchInventoryAndHardcoreReserves() {
        final Scenario noPickaxe = scenario(TunnelMode.HORIZONTAL);
        noPickaxe.frames.pickaxeCount = 0;
        noPickaxe.frames.main = HeldItemSummary.empty();
        assertPrecondition(
                noPickaxe,
                false,
                0.0,
                "excavate_safe_tunnel.pickaxe_unavailable"
        );

        final Scenario noTorch = scenario(TunnelMode.HORIZONTAL);
        noTorch.frames.torchCount = 0;
        assertPrecondition(
                noTorch,
                false,
                0.0,
                "excavate_safe_tunnel.torch_unavailable"
        );

        final Scenario worn = scenario(TunnelMode.HORIZONTAL);
        worn.frames.main = new HeldItemSummary(
                PICKAXE,
                1,
                245,
                250
        );
        assertPrecondition(
                worn,
                false,
                0.0,
                "excavate_safe_tunnel.pickaxe_durability_reserve"
        );

        final Scenario full = scenario(TunnelMode.HORIZONTAL);
        full.frames.emptySlots = 0;
        assertPrecondition(
                full,
                false,
                0.0,
                "excavate_safe_tunnel.inventory_full"
        );

        final Scenario hungry = scenario(TunnelMode.HORIZONTAL);
        hungry.frames.food = 13;
        assertPrecondition(
                hungry,
                true,
                0.0,
                "excavate_safe_tunnel.food_reserve_low"
        );

        final Scenario risky = scenario(TunnelMode.HORIZONTAL);
        risky.frames.danger = 0.07;
        assertPrecondition(
                risky,
                true,
                0.07,
                "excavate_safe_tunnel.danger_detected"
        );
        assertTrue(risky.interaction.mines.isEmpty());
        assertTrue(risky.interaction.placements.isEmpty());
    }

    @Test
    void refusesFluidAndFallingBlocksBeforeMining() throws Exception {
        final Scenario fluid = scenario(TunnelMode.HORIZONTAL);
        fluid.frames.addFace(
                destination(TunnelMode.HORIZONTAL).above(),
                "minecraft:water",
                BlockFace.WEST
        );
        final SkillTickResult fluidResult = drive(
                fluid,
                false,
                false
        );
        assertEquals(
                "excavate_safe_tunnel.fluid_exposed",
                fluidResult.failure().orElseThrow().code()
        );
        assertTrue(fluid.interaction.mines.isEmpty());

        final Scenario gravel = scenario(TunnelMode.HORIZONTAL);
        gravel.frames.addFace(
                destination(TunnelMode.HORIZONTAL).above(),
                "minecraft:gravel",
                BlockFace.WEST
        );
        final SkillTickResult gravelResult = drive(
                gravel,
                false,
                false
        );
        assertEquals(
                "excavate_safe_tunnel.unstable_block_exposed",
                gravelResult.failure().orElseThrow().code()
        );
        assertTrue(gravel.interaction.mines.isEmpty());
    }

    @Test
    void environmentalHazardWinsOverTargetInTheSameFrame()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.HORIZONTAL);
        scenario.frames.addFace(
                new GridPos(1, 65, 1),
                TARGET,
                BlockFace.WEST
        );
        scenario.frames.addFace(
                new GridPos(0, 64, 1),
                "minecraft:lava",
                BlockFace.NORTH
        );

        final SkillTickResult result = drive(
                scenario,
                false,
                false
        );

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "excavate_safe_tunnel.fluid_exposed",
                result.failure().orElseThrow().code()
        );
        assertTrue(scenario.interaction.mines.isEmpty());
        assertTrue(scenario.core.moves.isEmpty());
    }

    @Test
    void visibleFallingBlockEntityFailsBeforeMining()
            throws Exception {
        final Scenario scenario = scenario(TunnelMode.HORIZONTAL);
        scenario.frames.entities.add(new VisibleEntity(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000982"
                ),
                "minecraft:falling_block",
                new PerceptionVec3(1.5, 65.0, 0.5),
                new PerceptionVec3(1.0, -0.62, 0.0),
                1.2,
                false,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        ));

        final SkillTickResult result = drive(
                scenario,
                false,
                false
        );

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "excavate_safe_tunnel.falling_block_entity_exposed",
                result.failure().orElseThrow().code()
        );
        assertTrue(scenario.interaction.mines.isEmpty());
    }

    @Test
    void retreatsOneVerifiedStepBeforeHandingHazardToSurvival()
            throws Exception {
        final Scenario scenario = scenario(
                TunnelMode.HORIZONTAL,
                2
        );
        assertTrue(
                scenario.skill.preconditions(
                        context(100, false, 0.0),
                        scenario.parameters
                ).isEmpty()
        );
        scenario.skill.start(
                context(100, false, 0.0),
                scenario.parameters
        );
        final GridPos first = new GridPos(1, 64, 0);
        int processedMines = 0;
        int processedMoves = 0;
        boolean hazardInjected = false;
        SkillTickResult result = null;
        for (long tick = 101; tick < 300; tick++) {
            result = scenario.skill.tick(
                    context(tick, false, 0.0),
                    scenario.parameters
            );
            while (scenario.interaction.mines.size()
                    > processedMines) {
                final GridPos mined = block(
                        scenario.interaction.mines.get(
                                processedMines++
                        )
                );
                scenario.frames.clearBlock(mined);
                if (scenario.frames.voxels.get(first).kind()
                            == VoxelKind.AIR
                        && scenario.frames.voxels
                            .get(first.above()).kind()
                            == VoxelKind.AIR) {
                    scenario.frames.exposeSupport(first);
                }
            }
            while (scenario.core.moves.size() > processedMoves) {
                processedMoves++;
                scenario.frames.moveTo(
                        hazardInjected ? ORIGIN : first
                );
            }
            if (!hazardInjected
                    && result.safeCheckpoint()
                    && scenario.frames.coreCurrent()
                        .orElseThrow()
                        .feet()
                        .equals(first)) {
                hazardInjected = true;
                scenario.frames.addFace(
                        new GridPos(2, 64, 0),
                        "minecraft:lava",
                        BlockFace.WEST
                );
                scenario.frames.addFace(
                        new GridPos(2, 65, 1),
                        TARGET,
                        BlockFace.WEST
                );
            }
            scenario.frames.advance();
            if (result.status()
                    != SkillTickResult.Status.RUNNING) {
                break;
            }
        }

        assertTrue(hazardInjected);
        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "excavate_safe_tunnel.fluid_exposed",
                result.failure().orElseThrow().code()
        );
        assertEquals(
                ORIGIN,
                scenario.frames.coreCurrent().orElseThrow().feet()
        );
        assertTrue(
                processedMoves >= 2,
                "Expected normal forward movement and verified-corridor retreat"
        );
        assertEquals(2, scenario.interaction.mines.size());
    }

    @Test
    void cancellationQuiescesOrdinaryActuatorsAtCheckpoint() {
        final Scenario scenario = scenario(TunnelMode.HORIZONTAL);
        scenario.skill.start(
                context(100, false, 0.0),
                scenario.parameters
        );
        scenario.skill.cancel(
                context(101, false, 0.0),
                scenario.parameters
        );

        assertEquals(
                dev.mcai.companion.skill.SkillResult.Status.CANCELLED,
                scenario.skill.result(
                        context(101, false, 0.0),
                        scenario.parameters
                ).status()
        );
        assertEquals(1, scenario.interaction.aborts);
        assertEquals(1, scenario.core.stops);
    }

    @Test
    void checkpointPersistsBindingsAndTicksRejectTheirReplacement() {
        final Scenario session = scenario(TunnelMode.HORIZONTAL);
        session.skill.start(
                context(100, false, 0.0),
                session.parameters
        );
        final String checkpoint = session.skill.checkpoint(
                context(100, false, 0.0),
                session.parameters
        ).payload();
        assertTrue(checkpoint.contains("\"session\":37"));
        assertTrue(checkpoint.contains(
                "\"purpose\":\"audit_and_high_level_replan\""
        ));
        assertTrue(checkpoint.contains("\"resumable\":false"));
        assertTrue(checkpoint.contains("\"completedSteps\":0"));
        assertTrue(checkpoint.contains("\"stepsSinceTorch\":4"));
        assertTrue(checkpoint.contains("\"goalRevision\":3"));
        assertTrue(checkpoint.contains("\"worldRevision\":10"));
        session.frames.session++;
        final SkillTickResult sessionResult = session.skill.tick(
                context(101, false, 0.0),
                session.parameters
        );
        assertEquals(
                "excavate_safe_tunnel.session_mismatch",
                sessionResult.failure().orElseThrow().code()
        );
        assertTrue(session.interaction.mines.isEmpty());

        final Scenario revision = scenario(TunnelMode.HORIZONTAL);
        revision.skill.start(
                context(100, false, 0.0),
                revision.parameters
        );
        final SkillTickResult revisionResult = revision.skill.tick(
                new SkillContext(
                        4,
                        10,
                        101,
                        false,
                        true,
                        0.0
                ),
                revision.parameters
        );
        assertEquals(
                "excavate_safe_tunnel.revision_changed",
                revisionResult.failure().orElseThrow().code()
        );
        assertTrue(revision.interaction.mines.isEmpty());
    }

    @Test
    void nonCanonicalMiningPhaseNeverPublishesCheckpoint()
            throws Exception {
        final Scenario equipment = scenario(TunnelMode.HORIZONTAL);
        equipment.skill.start(
                context(100, false, 0.0),
                equipment.parameters
        );
        final SkillTickResult equipping = equipment.skill.tick(
                context(101, false, 0.0),
                equipment.parameters
        );
        assertFalse(equipping.safeCheckpoint());
        assertThrows(
                IllegalStateException.class,
                () -> equipment.skill.checkpoint(
                        context(101, false, 0.0),
                        equipment.parameters
                )
        );

        final Scenario mining = scenario(TunnelMode.HORIZONTAL);
        mining.interaction.beginMiningOutcome =
                ActionOutcome.IN_PROGRESS;
        mining.skill.start(
                context(100, false, 0.0),
                mining.parameters
        );
        SkillTickResult beganMining = null;
        for (long tick = 101;
                tick < 180 && mining.interaction.mines.isEmpty();
                tick++) {
            beganMining = mining.skill.tick(
                    context(tick, false, 0.0),
                    mining.parameters
            );
            mining.frames.advance();
        }
        assertFalse(mining.interaction.mines.isEmpty());
        assertFalse(beganMining.safeCheckpoint());
        final SkillTickResult periodicTick = mining.skill.tick(
                context(200, false, 0.0),
                mining.parameters
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                periodicTick.status()
        );
        assertFalse(periodicTick.safeCheckpoint());
        assertThrows(
                IllegalStateException.class,
                () -> mining.skill.checkpoint(
                        context(200, false, 0.0),
                        mining.parameters
                )
        );
    }

    private static Scenario scenario(final TunnelMode mode) {
        return scenario(mode, 1);
    }

    private static Scenario scenario(
            final TunnelMode mode,
            final int maximumSteps
    ) {
        final var frames = initial(mode);
        final var core =
                new MiningSkillTestFixtures.RecordingCoreActuator(
                        frames
                );
        final var interaction =
                new MiningSkillTestFixtures
                        .RecordingInteractionActuator(frames);
        final var skill = new ExcavateSafeTunnelSkill(
                PLAYER_ID,
                core,
                frames::coreCurrent,
                interaction,
                frames.interactionFrameSource(),
                frames::inventoryCurrent,
                fastPolicy()
        );
        final ExcavateSafeTunnelParameters parameters =
                new ExcavateSafeTunnelParameters(
                        DimensionRef.OVERWORLD,
                        frames.revision,
                        TunnelDirection.EAST,
                        mode,
                        maximumSteps,
                        4,
                        PICKAXE,
                        List.of(TARGET)
                );
        return new Scenario(
                frames,
                core,
                interaction,
                skill,
                parameters
        );
    }

    private static SkillTickResult drive(
            final Scenario scenario,
            final boolean exposeSupport,
            final boolean hardcore
    ) throws Exception {
        assertTrue(
                scenario.skill.preconditions(
                        context(100, hardcore, 0.0),
                        scenario.parameters
                ).isEmpty()
        );
        scenario.skill.start(
                context(100, hardcore, 0.0),
                scenario.parameters
        );
        int processedMines = 0;
        int processedMoves = 0;
        SkillTickResult result = null;
        for (long tick = 101; tick < 260; tick++) {
            result = scenario.skill.tick(
                    context(tick, hardcore, 0.0),
                    scenario.parameters
            );
            while (scenario.interaction.mines.size()
                    > processedMines) {
                final GridPos mined = block(
                        scenario.interaction.mines
                                .get(processedMines++)
                );
                scenario.frames.clearBlock(mined);
                final GridPos destination = destination(
                        scenario.parameters.mode()
                );
                if (exposeSupport
                        && scenario.frames.voxels
                            .get(destination) != null
                        && scenario.frames.voxels
                            .get(destination).kind()
                                == VoxelKind.AIR
                        && scenario.frames.voxels
                            .get(destination.above()) != null
                        && scenario.frames.voxels
                            .get(destination.above()).kind()
                                == VoxelKind.AIR) {
                    scenario.frames.exposeSupport(destination);
                    if (scenario.supportDanger > 0.0) {
                        scenario.frames.putVoxel(
                                destination.below(),
                                VoxelKind.SOLID,
                                scenario.supportDanger
                        );
                    }
                }
            }
            while (scenario.core.moves.size() > processedMoves) {
                processedMoves++;
                scenario.frames.moveTo(
                        destination(scenario.parameters.mode())
                );
            }
            scenario.frames.advance();
            if (result.status()
                    != SkillTickResult.Status.RUNNING) {
                return result;
            }
        }
        throw new AssertionError("Mining scenario did not terminate");
    }

    private static GridPos block(
            final dev.mcai.companion.action.BlockInteractionTarget target
    ) {
        return new GridPos(target.x(), target.y(), target.z());
    }

    private static void assertPrecondition(
            final Scenario scenario,
            final boolean hardcore,
            final double risk,
            final String expected
    ) {
        assertEquals(
                expected,
                scenario.skill.preconditions(
                        context(100, hardcore, risk),
                        scenario.parameters
                ).orElseThrow().code()
        );
    }

    private static SkillContext context(
            final long tick,
            final boolean hardcore,
            final double risk
    ) {
        return new SkillContext(
                3,
                10,
                tick,
                hardcore,
                true,
                risk
        );
    }

    private static final class Scenario {
        final MiningSkillTestFixtures.MutableFrames frames;
        final MiningSkillTestFixtures.RecordingCoreActuator core;
        final MiningSkillTestFixtures.RecordingInteractionActuator
                interaction;
        final ExcavateSafeTunnelSkill skill;
        final ExcavateSafeTunnelParameters parameters;
        double supportDanger;

        Scenario(
                final MiningSkillTestFixtures.MutableFrames frames,
                final MiningSkillTestFixtures.RecordingCoreActuator core,
                final MiningSkillTestFixtures
                        .RecordingInteractionActuator interaction,
                final ExcavateSafeTunnelSkill skill,
                final ExcavateSafeTunnelParameters parameters
        ) {
            this.frames = frames;
            this.core = core;
            this.interaction = interaction;
            this.skill = skill;
            this.parameters = parameters;
        }

        List<GridPos> minedBlocks() {
            return interaction.mines.stream()
                    .map(ExcavateSafeTunnelSkillTest::block)
                    .toList();
        }
    }
}
