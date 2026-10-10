package dev.mcai.companion.skills.stronghold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.gathering.ResourceInventoryState;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.inventory.CraftRecipeParameters;
import dev.mcai.companion.skills.inventory.DropItemParameters;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.skills.mining.TunnelDirection;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ReachObservedStrongholdSkillTest {
    private static final UUID PLAYER_ID = UUID.fromString(
            "62000000-0000-0000-0000-000000000002"
    );

    @Test
    void requiresMeasuredIntersectionBeforeAnyMovement() {
        final AcceptedCoreActuator core = new AcceptedCoreActuator();
        final ReachObservedStrongholdSkill skill = skill(
                frame(DimensionRef.OVERWORLD, List.of()),
                new EyeTraceResultBuffer(),
                core
        );

        assertEquals(
                "reach_observed_stronghold"
                    + ".triangulated_search_area_required",
                skill.preconditions(
                        context(1),
                        NoParameters.INSTANCE
                ).orElseThrow().code()
        );
        assertEquals(0, core.moves);
    }

    @Test
    void completesWhenStandingOnCurrentlyVisibleStrongholdSupport() {
        final EyeTraceResultBuffer traces = intersection();
        final VisibleBlockFace stronghold = new VisibleBlockFace(
                new BlockCoordinate(0, 63, 0),
                "minecraft:stone_bricks",
                "up",
                new PerceptionVec3(0.5, 64.0, 0.5),
                1.62,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of(),
                TopSupportAffordance.STURDY_FULL_TOP
        );
        final AcceptedCoreActuator core = new AcceptedCoreActuator();
        final ReachObservedStrongholdSkill skill = skill(
                frame(DimensionRef.OVERWORLD, List.of(stronghold)),
                traces,
                core
        );

        assertTrue(
                skill.preconditions(
                        context(2),
                        NoParameters.INSTANCE
                ).isEmpty()
        );
        skill.start(context(2), NoParameters.INSTANCE);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(
                        context(3),
                        NoParameters.INSTANCE
                ).status()
        );
        assertTrue(
                skill.checkpoint(
                        context(3),
                        NoParameters.INSTANCE
                ).payload().contains(
                        "\"observedStrongholdBlock\":[0,63,0]"
                )
        );
        assertTrue(core.stops > 0);
    }

    @Test
    void visibleSideWallStartsBoundedEntryInsteadOfFalseCompletion() {
        final VisibleBlockFace wall = new VisibleBlockFace(
                new BlockCoordinate(2, 63, 0),
                "minecraft:stone_bricks",
                "west",
                new PerceptionVec3(2.0, 63.5, 0.5),
                2.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of(),
                TopSupportAffordance.NON_STURDY_OR_PARTIAL
        );
        final ReachObservedStrongholdSkill skill = skill(
                frame(DimensionRef.OVERWORLD, List.of(wall)),
                intersection(),
                new AcceptedCoreActuator()
        );

        skill.start(context(2), NoParameters.INSTANCE);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(3), NoParameters.INSTANCE).status()
        );
        assertTrue(
                skill.checkpoint(
                        context(3),
                        NoParameters.INSTANCE
                ).payload().contains(
                        "\"phase\":\"ALIGNING_ENTRY_FLOOR\""
                )
        );
        assertTrue(
                skill.checkpoint(
                        context(3),
                        NoParameters.INSTANCE
                ).payload().contains(
                        "\"entryMaximumSteps\":3"
                )
        );
        assertTrue(
                skill.checkpoint(
                        context(3),
                        NoParameters.INSTANCE
                ).payload().contains(
                        "\"entryMode\":\"HORIZONTAL\""
                )
        );
    }

    @Test
    void adjacentFloorLayerDoesNotCountAsSupportedStrongholdFeet() {
        final VisibleBlockFace adjacentFloor =
                new VisibleBlockFace(
                        new BlockCoordinate(1, 64, 0),
                        "minecraft:stone_bricks",
                        "up",
                        new PerceptionVec3(1.5, 65.0, 0.5),
                        1.0,
                        PerceptionProvenance
                            .BLOCK_SURFACE_RAY_CLIP,
                        Map.of(),
                        TopSupportAffordance.STURDY_FULL_TOP
                );
        final VisibleBlockFace support =
                new VisibleBlockFace(
                        new BlockCoordinate(0, 63, 0),
                        "minecraft:stone_bricks",
                        "up",
                        new PerceptionVec3(0.5, 64.0, 0.5),
                        1.62,
                        PerceptionProvenance
                            .BLOCK_SURFACE_RAY_CLIP,
                        Map.of(),
                        TopSupportAffordance.STURDY_FULL_TOP
                );

        assertFalse(
                ReachObservedStrongholdSkill
                    .hasAccessibleStrongholdSupport(
                        frame(
                            DimensionRef.OVERWORLD,
                            List.of(adjacentFloor)
                        )
                    )
        );
        assertTrue(
                ReachObservedStrongholdSkill
                    .hasAccessibleStrongholdSupport(
                        frame(
                            DimensionRef.OVERWORLD,
                            List.of(adjacentFloor, support)
                        )
                    )
        );
    }

    @Test
    void ordinarySturdyFloorAllowsEntryScanButNotStrongholdHandoff() {
        final VisibleBlockFace ordinaryFloor =
                new VisibleBlockFace(
                        new BlockCoordinate(0, 63, 0),
                        "minecraft:stone",
                        "up",
                        new PerceptionVec3(0.5, 64.0, 0.5),
                        1.62,
                        PerceptionProvenance
                            .BLOCK_SURFACE_RAY_CLIP,
                        Map.of(),
                        TopSupportAffordance.STURDY_FULL_TOP
                );
        final CoreSkillFrame ordinary = frame(
                DimensionRef.OVERWORLD,
                List.of(ordinaryFloor)
        );

        assertTrue(
                ReachObservedStrongholdSkill
                    .hasVisibleSafeFloorSupport(ordinary)
        );
        assertFalse(
                ReachObservedStrongholdSkill
                    .hasAccessibleStrongholdSupport(ordinary)
        );
    }

    @Test
    void descendingLegsFormAClosedSquare() {
        assertEquals(
                List.of(
                        TunnelDirection.EAST,
                        TunnelDirection.SOUTH,
                        TunnelDirection.WEST,
                        TunnelDirection.NORTH,
                        TunnelDirection.EAST
                ),
                java.util.stream.IntStream.range(0, 5)
                        .mapToObj(
                                ReachObservedStrongholdSkill
                                    ::descendingDirection
                        )
                        .toList()
        );
    }

    @Test
    void depthLimitProbeClimbsOutwardOneObservedStepAtATime() {
        assertEquals(
                List.of(
                        TunnelDirection.EAST,
                        TunnelDirection.SOUTH,
                        TunnelDirection.WEST,
                        TunnelDirection.NORTH,
                        TunnelDirection.EAST,
                        TunnelDirection.SOUTH,
                        TunnelDirection.WEST,
                        TunnelDirection.NORTH
                ),
                java.util.stream.IntStream.range(0, 8)
                        .mapToObj(
                                ReachObservedStrongholdSkill
                                    ::depthProbeDirection
                        )
                        .toList()
        );
        assertTrue(
                java.util.stream.IntStream.range(
                        0,
                        ReachObservedStrongholdSkill
                            .MAXIMUM_DEPTH_PROBE_LEGS
                ).allMatch(index ->
                        ReachObservedStrongholdSkill
                            .depthProbeMaximumSteps(index) == 1
                )
        );
        assertEquals(
                TunnelDirection.EAST,
                ReachObservedStrongholdSkill
                    .radialDepthProbeDirection(
                        -297.3,
                        -1725.5,
                        -304.0,
                        -1728.0,
                        0
                    )
        );
        assertEquals(
                TunnelDirection.NORTH,
                ReachObservedStrongholdSkill
                    .radialDepthProbeDirection(
                        -304.0,
                        -1734.0,
                        -304.0,
                        -1728.0,
                        0
                    )
        );
    }

    @Test
    void undergroundRestartResumesLocalSearchInsteadOfSurfaceTravel() {
        assertTrue(
                ReachObservedStrongholdSkill
                    .shouldResumeUndergroundSearch(
                        -297.3,
                        -50.0,
                        -1725.5,
                        -304.0,
                        -1728.0,
                        6.0
                    )
        );
        assertFalse(
                ReachObservedStrongholdSkill
                    .shouldResumeUndergroundSearch(
                        -297.3,
                        -38.0,
                        -1725.5,
                        -304.0,
                        -1728.0,
                        6.0
                    )
        );
        assertFalse(
                ReachObservedStrongholdSkill
                    .shouldResumeUndergroundSearch(
                        -250.0,
                        -50.0,
                        -1725.5,
                        -304.0,
                        -1728.0,
                        6.0
                    )
        );
    }

    @Test
    void strongholdEntryUsesDominantCardinalBearing() {
        assertEquals(
                TunnelDirection.EAST,
                ReachObservedStrongholdSkill.cardinalDirection(4, 2)
        );
        assertEquals(
                TunnelDirection.WEST,
                ReachObservedStrongholdSkill.cardinalDirection(-4, 2)
        );
        assertEquals(
                TunnelDirection.SOUTH,
                ReachObservedStrongholdSkill.cardinalDirection(1, 4)
        );
        assertEquals(
                TunnelDirection.NORTH,
                ReachObservedStrongholdSkill.cardinalDirection(1, -4)
        );
    }

    @Test
    void unsupportedEntryAlternatesBoundedDescentAlongTheWall() {
        assertEquals(
                TunnelDirection.SOUTH,
                ReachObservedStrongholdSkill
                    .entryDepthAdjustmentDirection(
                        TunnelDirection.EAST,
                        1
                    )
        );
        assertEquals(
                TunnelDirection.NORTH,
                ReachObservedStrongholdSkill
                    .entryDepthAdjustmentDirection(
                        TunnelDirection.EAST,
                        2
                    )
        );
        assertEquals(
                TunnelDirection.EAST,
                ReachObservedStrongholdSkill
                    .entryDepthAdjustmentDirection(
                        TunnelDirection.NORTH,
                        3
                    )
        );
        assertEquals(
                TunnelDirection.WEST,
                ReachObservedStrongholdSkill
                    .entryDepthAdjustmentDirection(
                        TunnelDirection.SOUTH,
                        4
                    )
        );
    }

    @Test
    void higherEntryProbeFormsBoundedSupportedSwitchbackOutsideWall() {
        assertEquals(
                List.of(
                        TunnelDirection.SOUTH,
                        TunnelDirection.WEST,
                        TunnelDirection.SOUTH,
                        TunnelDirection.EAST,
                        TunnelDirection.NORTH,
                        TunnelDirection.WEST,
                        TunnelDirection.NORTH,
                        TunnelDirection.EAST
                ),
                java.util.stream.IntStream.rangeClosed(1, 8)
                        .mapToObj(attempt ->
                                ReachObservedStrongholdSkill
                                    .entryHeightAdjustmentDirection(
                                        TunnelDirection.EAST,
                                        attempt
                                    )
                        )
                        .toList()
        );
        assertEquals(
                List.of(
                        TunnelDirection.EAST,
                        TunnelDirection.SOUTH,
                        TunnelDirection.EAST,
                        TunnelDirection.NORTH
                ),
                java.util.stream.IntStream.rangeClosed(1, 4)
                        .mapToObj(attempt ->
                                ReachObservedStrongholdSkill
                                    .entryHeightAdjustmentDirection(
                                        TunnelDirection.NORTH,
                                        attempt
                                    )
                        )
                        .toList()
        );
        assertEquals(
                List.of(true, false, false, true,
                        true, false, false, true),
                java.util.stream.IntStream.rangeClosed(1, 8)
                        .mapToObj(
                                ReachObservedStrongholdSkill
                                    ::shouldProbeWallAfterHeightAdjustment
                        )
                        .toList()
        );
    }

    @Test
    void entryRetreatCountsCrossedBlocksInsteadOfFloatingPointDistance() {
        assertEquals(
                3,
                ReachObservedStrongholdSkill.entryRetreatSteps(
                        new dev.mcai.companion.navigation.GridPos(
                                -194,
                                -50,
                                -293
                        ),
                        new dev.mcai.companion.navigation.GridPos(
                                -191,
                                -50,
                                -293
                        ),
                        TunnelDirection.EAST
                )
        );
        assertEquals(
                2,
                ReachObservedStrongholdSkill.entryRetreatSteps(
                        new dev.mcai.companion.navigation.GridPos(
                                8,
                                32,
                                11
                        ),
                        new dev.mcai.companion.navigation.GridPos(
                                8,
                                32,
                                9
                        ),
                        TunnelDirection.NORTH
                )
        );
    }

    @Test
    void fullRuntimeRegistrationAddsParameterlessReachCompound() {
        final CoreSkillFrame frame = frame(
                DimensionRef.OVERWORLD,
                List.of()
        );
        final SkillRegistry registry = StrongholdSkills.registerAll(
                new SkillRegistry(),
                PLAYER_ID,
                new AcceptedCoreActuator(),
                () -> Optional.of(frame),
                new AcceptedInventoryActuator(),
                intersection(),
                () -> 7L,
                new AcceptedInteractionActuator(),
                () -> Optional.of(interactionFrame(frame)),
                () -> Optional.of(
                        new ResourceInventoryState(7L, 30)
                )
        );

        assertEquals(
                java.util.Set.of(
                        "trace_stronghold_eye",
                        "triangulate_stronghold_search_area",
                        "reach_observed_stronghold",
                        "search_stronghold_portal_room"
                ),
                registry.names()
        );
        assertTrue(
                registry.modelArgumentValidators()
                        .get("reach_observed_stronghold")
                        .validate(List.of())
                        .isEmpty()
        );
        assertTrue(
                registry.modelArgumentValidators()
                        .get(
                            "search_stronghold_portal_room"
                        )
                        .validate(List.of())
                        .isEmpty()
        );
    }

    private static ReachObservedStrongholdSkill skill(
            final CoreSkillFrame frame,
            final EyeTraceResultBuffer traces,
            final AcceptedCoreActuator core
    ) {
        return new ReachObservedStrongholdSkill(
                PLAYER_ID,
                core,
                () -> Optional.of(frame),
                new AcceptedInteractionActuator(),
                () -> Optional.of(interactionFrame(frame)),
                () -> Optional.of(
                        new ResourceInventoryState(7L, 30)
                ),
                traces,
                () -> 7L
        );
    }

    private static InteractionSkillFrame interactionFrame(
            final CoreSkillFrame frame
    ) {
        return new InteractionSkillFrame(
                PLAYER_ID,
                frame.dimension(),
                frame.gameTime(),
                frame.gameTime(),
                frame.observationRevision(),
                7L,
                frame.mainHand(),
                frame.offHand(),
                List.of(),
                frame.visibleBlockFaces(),
                frame.inventory()
        );
    }

    private static CoreSkillFrame frame(
            final DimensionRef dimension,
            final List<VisibleBlockFace> faces
    ) {
        return new CoreSkillFrame(
                PLAYER_ID,
                dimension,
                20,
                20,
                new PerceptionVec3(0.5, 64.0, 0.5),
                new PerceptionVec3(0.5, 65.62, 0.5),
                new PerceptionVec3(0.0, 0.0, 1.0),
                true,
                false,
                0.0,
                new LocalNavSnapshot(
                        dimension,
                        20,
                        List.of()
                ),
                faces,
                20.0F,
                20.0F,
                20,
                List.of(
                        new InventoryItemSummary(
                                "minecraft:iron_pickaxe",
                                1
                        ),
                        new InventoryItemSummary(
                                "minecraft:torch",
                                32
                        )
                ),
                new HeldItemSummary(
                        "minecraft:iron_pickaxe",
                        1,
                        0,
                        250
                ),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
    }

    private static EyeTraceResultBuffer intersection() {
        final EyeTraceResultBuffer traces =
                new EyeTraceResultBuffer();
        traces.publish(trace(
                new PerceptionVec3(-100.0, 64.0, 0.0),
                1.0,
                0.0,
                10
        ));
        traces.publish(trace(
                new PerceptionVec3(0.0, 64.0, -100.0),
                0.0,
                1.0,
                20
        ));
        return traces;
    }

    private static EyeTraceSnapshot trace(
            final PerceptionVec3 origin,
            final double directionX,
            final double directionZ,
            final long revision
    ) {
        return new EyeTraceSnapshot(
                7,
                DimensionRef.OVERWORLD,
                origin,
                100 + revision,
                revision,
                revision + 1,
                List.of(
                        new EyeTraceSnapshot.Sample(
                                revision,
                                origin.add(new PerceptionVec3(
                                        directionX,
                                        1.0,
                                        directionZ
                                ))
                        ),
                        new EyeTraceSnapshot.Sample(
                                revision + 1,
                                origin.add(new PerceptionVec3(
                                        directionX * 6.0,
                                        2.0,
                                        directionZ * 6.0
                                ))
                        )
                ),
                directionX,
                directionZ,
                Math.toDegrees(Math.atan2(
                        -directionX,
                        directionZ
                )),
                5.0
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(7, 10, tick, false, true, 0.0);
    }

    private static final class AcceptedCoreActuator
            implements CoreSkillActuator {
        private int moves;
        private int stops;

        @Override
        public ActionOutcome move(final MovementIntent intent) {
            moves++;
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome look(final LookIntent intent) {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome jump() {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome stop() {
            stops++;
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome useMainHandOn(
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            return ActionOutcome.QUEUED;
        }
    }

    private static final class AcceptedInteractionActuator
            implements InteractionSkillActuator {
        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(7L);
        }

        @Override
        public ActionOutcome beginMining(
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome continueMining() {
            return ActionOutcome.COMPLETED;
        }

        @Override
        public ActionOutcome abortMining() {
            return ActionOutcome.COMPLETED;
        }

        @Override
        public ActionOutcome useOnBlock(
                final ActionHand hand,
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome attack(final UUID entityId) {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome continueUsing(
                final ActionHand hand
        ) {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome releaseUse() {
            return ActionOutcome.COMPLETED;
        }

        @Override
        public ActionOutcome equipMainHand(
                final String itemId
        ) {
            return ActionOutcome.QUEUED;
        }
    }

    private static final class AcceptedInventoryActuator
            implements InventorySkillActuator {
        @Override
        public InventoryOperationResult checkEquip(
                final EquipItemParameters parameters
        ) {
            return InventoryOperationResult.success();
        }

        @Override
        public InventoryOperationResult equip(
                final EquipItemParameters parameters
        ) {
            return InventoryOperationResult.success();
        }

        @Override
        public InventoryOperationResult checkDrop(
                final DropItemParameters parameters
        ) {
            return InventoryOperationResult.success();
        }

        @Override
        public InventoryOperationResult drop(
                final DropItemParameters parameters
        ) {
            return InventoryOperationResult.success();
        }

        @Override
        public InventoryOperationResult checkCraft(
                final CraftRecipeParameters parameters
        ) {
            return InventoryOperationResult.success();
        }

        @Override
        public InventoryOperationResult craftOnce(
                final CraftRecipeParameters parameters
        ) {
            return InventoryOperationResult.success();
        }
    }
}
