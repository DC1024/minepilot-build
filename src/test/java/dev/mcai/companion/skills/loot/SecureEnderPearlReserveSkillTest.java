package dev.mcai.companion.skills.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.MoveToParameters;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SecureEnderPearlReserveSkillTest {
    private static final UUID PLAYER =
            UUID.fromString("51000000-0000-0000-0000-000000000001");
    private static final UUID ENDERMAN =
            UUID.fromString("51000000-0000-0000-0000-000000000002");
    private static final HeldItemSummary SWORD =
            new HeldItemSummary(
                    "minecraft:diamond_sword",
                    1,
                    0,
                    1_561
            );

    @Test
    void acceptsExactlyNoArguments() {
        final Fixture fixture = fixture(
                roofFrame(List.of(), SWORD)
        );

        assertTrue(
                fixture.reserve.parameters()
                        .parse(List.of())
                        .value()
                        .isPresent()
        );
        assertTrue(
                fixture.reserve.parameters()
                        .parse(List.of(new SkillArgument(
                                "maximumTicks",
                                "1200"
                        )))
                        .value()
                        .isEmpty()
        );
    }

    @Test
    void fullPearlDerivedReserveCompletesWithoutAnotherKill() {
        final Fixture fixture = fixture(
                openFrame(List.of(
                        new InventoryItemSummary(
                                "minecraft:ender_eye",
                                14
                        )
                ))
        );

        assertTrue(fixture.reserve.preconditions(
                context(1),
                NoParameters.INSTANCE
        ).isEmpty());
        fixture.reserve.start(context(1), NoParameters.INSTANCE);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                fixture.reserve.tick(
                        context(2),
                        NoParameters.INSTANCE
                ).status()
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                fixture.reserve.result(
                        context(2),
                        NoParameters.INSTANCE
                ).status()
        );
    }

    @Test
    void onePearlCannotShortcutAndStartsFairScanning() {
        final Fixture fixture = fixture(
                roofFrame(
                        List.of(new InventoryItemSummary(
                                "minecraft:ender_pearl",
                                1
                        )),
                        SWORD
                )
        );
        fixture.reserve.start(context(1), NoParameters.INSTANCE);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                fixture.reserve.tick(
                        context(2),
                        NoParameters.INSTANCE
                ).status()
        );
        assertEquals(1, fixture.core.looks);
        assertTrue(
                fixture.reserve.checkpoint(
                        context(2),
                        NoParameters.INSTANCE
                ).payload().contains("\"enderRouteUnits\":1")
        );
    }

    @Test
    void aNewRoofRequiresElevenOwnedFullBlocks() {
        final Fixture insufficient = fixture(
                openFrame(List.of(new InventoryItemSummary(
                        "minecraft:cobblestone",
                        10
                )))
        );
        assertEquals(
                BuildEndermanSafetyRoofSkill.NAME
                    + ".building_blocks_required",
                insufficient.reserve.preconditions(
                        context(1),
                        NoParameters.INSTANCE
                ).orElseThrow().code()
        );

        final Fixture enough = fixture(
                openFrame(List.of(new InventoryItemSummary(
                        "minecraft:cobblestone",
                        11
                )))
        );
        assertTrue(enough.reserve.preconditions(
                context(1),
                NoParameters.INSTANCE
        ).isEmpty());
    }

    @Test
    void anAlreadyObservedRoofNeedsNoDisposableBlocks() {
        final Fixture fixture = fixture(
                roofFrame(List.of(), SWORD)
        );

        assertTrue(fixture.builder.preconditions(
                context(1),
                NoParameters.INSTANCE
        ).isEmpty());
        fixture.builder.start(context(1), NoParameters.INSTANCE);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                fixture.builder.tick(
                        context(2),
                        NoParameters.INSTANCE
                ).status()
        );
        assertEquals(0, fixture.builder.placementsConfirmed());
    }

    @Test
    void aNewRoofCannotCompleteBeforeItsTemporaryPillarIsRemoved() {
        assertTrue(
                BuildEndermanSafetyRoofSkill.mayCompleteObservedRoof(
                        0,
                        false
                )
        );
        assertTrue(
                !BuildEndermanSafetyRoofSkill.mayCompleteObservedRoof(
                        11,
                        false
                )
        );
        assertTrue(
                BuildEndermanSafetyRoofSkill.mayCompleteObservedRoof(
                        11,
                        true
                )
        );
    }

    @Test
    void onlyTheExactOrdinaryNoDropFailureCanBeRetried() {
        assertTrue(AcquireShelteredEnderPearlSkill.isNoDropFailure(
                SkillFailure.of(
                        "engage_and_collect_observed_drop"
                            + ".expected_drop_not_observed"
                )
        ));
        assertTrue(!AcquireShelteredEnderPearlSkill.isNoDropFailure(
                SkillFailure.of(
                        "engage_and_collect_observed_drop"
                            + ".health_too_low"
                )
        ));
    }

    @Test
    void shelterReturnUsesTheTightestLegalMoveRadius() {
        final var parameters =
                SecureEnderPearlReserveSkill
                    .shelterReturnParameters(
                            DimensionRef.OVERWORLD,
                            new GridPos(3, 7, -2)
                    );

        assertEquals(0.25, parameters.arrivalRadius());
        assertEquals(new GridPos(3, 7, -2), parameters.gridGoal());
    }

    @Test
    void onlyBoundedLocalReturnFailuresAreRecoverable() {
        assertTrue(
                SecureEnderPearlReserveSkill
                    .recoverableReturnMoveFailure(
                            "move_to.route_unknown"
                    )
        );
        assertTrue(
                SecureEnderPearlReserveSkill
                    .recoverableReturnMoveFailure(
                            "move_to.stuck"
                    )
        );
        assertTrue(
                SecureEnderPearlReserveSkill
                    .recoverableReturnMoveFailure(
                            "move_to.turn_stuck"
                    )
        );
        assertFalse(
                SecureEnderPearlReserveSkill
                    .recoverableReturnMoveFailure(
                            "move_to.hardcore_danger"
                    )
        );
    }

    @Test
    void onlyTheBuildersOwnShortSupportedLandingIsManaged() {
        final DangerSignal ordinaryJumpLanding = new DangerSignal(
                DangerKind.FALLING,
                0.06,
                0.0,
                Optional.empty(),
                PerceptionProvenance.BODY_HAZARD
        );
        final CoreSkillFrame frame = airborneFrame(
                0.5,
                0.20,
                ordinaryJumpLanding
        );
        final GridPos anchor = new GridPos(0, 0, 0);

        assertTrue(
                BuildEndermanSafetyRoofSkill
                    .isControlledPlacementLanding(
                        frame,
                        ordinaryJumpLanding,
                        anchor,
                        true
                    )
        );
        assertTrue(
                !BuildEndermanSafetyRoofSkill
                    .isControlledPlacementLanding(
                        frame,
                        ordinaryJumpLanding,
                        anchor,
                        false
                    )
        );
        assertTrue(
                !BuildEndermanSafetyRoofSkill
                    .isControlledPlacementLanding(
                        airborneFrame(
                            1.5,
                            0.20,
                            ordinaryJumpLanding
                        ),
                        ordinaryJumpLanding,
                        anchor,
                        true
                    )
        );
        final DangerSignal realFall = new DangerSignal(
                DangerKind.FALLING,
                0.20,
                0.0,
                Optional.empty(),
                PerceptionProvenance.BODY_HAZARD
        );
        assertTrue(
                !BuildEndermanSafetyRoofSkill
                    .isControlledPlacementLanding(
                        airborneFrame(0.5, 0.20, realFall),
                        realFall,
                        anchor,
                        true
                    )
        );
    }

    @Test
    void shelteredVisibleEndermanIsOwnedBeforeFirstSupervisorTick() {
        final Fixture fixture = fixture(
                roofFrame(
                        List.of(),
                        SWORD,
                        List.of(enderman())
                )
        );

        assertTrue(
                !fixture.reserve.managesVisibleHostileProximity()
        );
        fixture.reserve.start(context(1), NoParameters.INSTANCE);

        assertTrue(
                fixture.reserve.managesVisibleHostileProximity(),
                "The emergency lane must not starve the sheltered "
                    + "SELECTING handoff"
        );
        assertTrue(
                !fixture.reserve.managesPhysicalContactThreats(),
                "Actual pre-combat contact remains emergency-owned"
        );
    }

    @Test
    void boundedRoofMemorySurvivesTargetHandoffButStillExpires() {
        final CoreSkillFrame withinLease = agedRoofFrame(
                AcquireShelteredEnderPearlSkill
                    .MAXIMUM_ROOF_OBSERVATION_AGE
            );
        assertTrue(
                SecureEnderPearlReserveSkill.hasObservedSafetyRoof(
                    withinLease
                )
        );
        assertFalse(
                SecureEnderPearlReserveSkill.hasObservedSafetyRoof(
                    agedRoofFrame(
                        AcquireShelteredEnderPearlSkill
                            .MAXIMUM_ROOF_OBSERVATION_AGE + 1
                    )
                )
        );

        final Fixture fixture = fixture(agedRoofFrame(150));
        final SkillContext hardcore = new SkillContext(
                1,
                1,
                1,
                true,
                true,
                0.75
        );
        assertTrue(fixture.reserve.preconditions(
                hardcore,
                NoParameters.INSTANCE
        ).isEmpty());
        fixture.reserve.start(hardcore, NoParameters.INSTANCE);
        assertEquals(
                1.0,
                fixture.reserve.hardcoreRiskThresholdOverride(
                    hardcore,
                    NoParameters.INSTANCE
                ).orElseThrow()
        );
    }

    @Test
    void returnRiskAuthorizationIsBoundToOneShelteredKillSample() {
        final CoreSkillFrame frame = agedRoofFrame(150);
        final GridPos anchor = frame.feet();
        final MoveToParameters returnParameters =
                SecureEnderPearlReserveSkill
                    .shelterReturnParameters(
                            frame.dimension(),
                            anchor
                    );
        final SkillContext attributedRisk = new SkillContext(
                1,
                1,
                1,
                true,
                true,
                0.75
        );

        assertTrue(
                SecureEnderPearlReserveSkill
                    .authorizesShelteredReturnResidualRisk(
                            attributedRisk,
                            frame,
                            returnParameters,
                            anchor,
                            ENDERMAN,
                            frame.observationRevision()
                    )
        );

        final UUID differentEnderman = UUID.fromString(
                "51000000-0000-0000-0000-000000000003"
        );
        final CoreSkillFrame differentTarget = copyFrame(
                frame,
                frame.danger(),
                List.of(enderman(differentEnderman)),
                frame.dangerSignals()
        );
        assertFalse(
                SecureEnderPearlReserveSkill
                    .authorizesShelteredReturnResidualRisk(
                            attributedRisk,
                            differentTarget,
                            returnParameters,
                            anchor,
                            ENDERMAN,
                            frame.observationRevision()
                    )
        );

        final DangerSignal contact = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                0.75,
                0.0,
                Optional.of(new PerceptionVec3(
                        1.0,
                        0.0,
                        0.0
                )),
                PerceptionProvenance.PHYSICAL_CONTACT
        );
        assertFalse(
                SecureEnderPearlReserveSkill
                    .authorizesShelteredReturnResidualRisk(
                            attributedRisk,
                            copyFrame(
                                    frame,
                                    contact.severity(),
                                    frame.visibleEntities(),
                                    List.of(contact)
                            ),
                            returnParameters,
                            anchor,
                            ENDERMAN,
                            frame.observationRevision()
                    )
        );

        assertFalse(
                SecureEnderPearlReserveSkill
                    .authorizesShelteredReturnResidualRisk(
                            attributedRisk,
                            frame,
                            SecureEnderPearlReserveSkill
                                .shelterReturnParameters(
                                        frame.dimension(),
                                        anchor.offset(1, 0, 0)
                                ),
                            anchor,
                            ENDERMAN,
                            frame.observationRevision()
                    )
        );

        final SkillContext unattributedRisk = new SkillContext(
                1,
                1,
                1,
                true,
                true,
                0.90
        );
        assertFalse(
                SecureEnderPearlReserveSkill
                    .authorizesShelteredReturnResidualRisk(
                            unattributedRisk,
                            frame,
                            returnParameters,
                            anchor,
                            ENDERMAN,
                            frame.observationRevision()
                    )
        );
        assertFalse(
                SecureEnderPearlReserveSkill
                    .authorizesShelteredReturnResidualRisk(
                            attributedRisk,
                            frame,
                            returnParameters,
                            anchor,
                            ENDERMAN,
                            frame.observationRevision() - 1
                    )
        );
    }

    @Test
    void recentDefeatBindingSurvivesOnlyTheImmediatePrecisionRedock() {
        final CoreSkillFrame observedTarget = agedRoofFrame(150);
        final CoreSkillFrame residualOnly = copyFrame(
                observedTarget,
                observedTarget.danger(),
                List.of(),
                observedTarget.dangerSignals()
        );
        final GridPos anchor = residualOnly.feet();
        final SkillContext withinTickWindow = new SkillContext(
                1,
                1,
                120,
                true,
                true,
                0.75
        );

        assertTrue(
                SecureEnderPearlReserveSkill
                    .recentDefeatFreshForPrecisionRedock(
                            withinTickWindow,
                            residualOnly,
                            anchor,
                            ENDERMAN,
                            residualOnly.observationRevision() - 2,
                            100
                    )
        );
        assertFalse(
                SecureEnderPearlReserveSkill
                    .recentDefeatFreshForPrecisionRedock(
                            withinTickWindow,
                            residualOnly,
                            anchor,
                            ENDERMAN,
                            residualOnly.observationRevision() - 3,
                            100
                    )
        );
        assertFalse(
                SecureEnderPearlReserveSkill
                    .recentDefeatFreshForPrecisionRedock(
                            new SkillContext(
                                    1,
                                    1,
                                    121,
                                    true,
                                    true,
                                    0.75
                            ),
                            residualOnly,
                            anchor,
                            ENDERMAN,
                            residualOnly.observationRevision(),
                            100
                    )
        );
        assertFalse(
                SecureEnderPearlReserveSkill
                    .recentDefeatFreshForPrecisionRedock(
                            withinTickWindow,
                            observedTarget,
                            anchor,
                            UUID.fromString(
                                "51000000-0000-0000-0000-000000000003"
                            ),
                            observedTarget.observationRevision(),
                            120
                    )
        );
        assertFalse(
                SecureEnderPearlReserveSkill
                    .recentDefeatFreshForPrecisionRedock(
                            withinTickWindow,
                            residualOnly,
                            anchor.offset(1, 0, 0),
                            ENDERMAN,
                            residualOnly.observationRevision(),
                            120
                    )
        );
    }

    private static Fixture fixture(final CoreSkillFrame frame) {
        final RecordingCore core = new RecordingCore();
        final CoreSkillFrameSource frames =
                () -> Optional.of(frame);
        final NoopInteractions interactions =
                new NoopInteractions();
        final InteractionSkillFrameSource interactionFrames =
                Optional::empty;
        return new Fixture(
                core,
                new BuildEndermanSafetyRoofSkill(
                        PLAYER,
                        core,
                        frames,
                        interactions,
                        interactionFrames
                ),
                new SecureEnderPearlReserveSkill(
                        PLAYER,
                        core,
                        frames,
                        interactions,
                        interactionFrames
                )
        );
    }

    private static CoreSkillFrame openFrame(
            final List<InventoryItemSummary> inventory
    ) {
        return frame(inventory, HeldItemSummary.empty(), false);
    }

    private static CoreSkillFrame roofFrame(
            final List<InventoryItemSummary> inventory,
            final HeldItemSummary mainHand
    ) {
        return roofFrame(inventory, mainHand, List.of());
    }

    private static CoreSkillFrame roofFrame(
            final List<InventoryItemSummary> inventory,
            final HeldItemSummary mainHand,
            final List<VisibleEntity> visibleEntities
    ) {
        return frame(
                inventory,
                mainHand,
                true,
                visibleEntities
        );
    }

    private static CoreSkillFrame frame(
            final List<InventoryItemSummary> inventory,
            final HeldItemSummary mainHand,
            final boolean roof
    ) {
        return frame(inventory, mainHand, roof, List.of());
    }

    private static CoreSkillFrame frame(
            final List<InventoryItemSummary> inventory,
            final HeldItemSummary mainHand,
            final boolean roof,
            final List<VisibleEntity> visibleEntities
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        if (roof) {
            voxels.add(voxel(new GridPos(0, -1, 0), VoxelKind.SOLID));
            voxels.add(voxel(new GridPos(0, 0, 0), VoxelKind.AIR));
            voxels.add(voxel(new GridPos(0, 1, 0), VoxelKind.AIR));
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    voxels.add(voxel(
                            new GridPos(x, 2, z),
                            VoxelKind.SOLID
                    ));
                }
            }
        }
        final List<InventoryItemSummary> completeInventory =
                new ArrayList<>(inventory);
        if (!mainHand.emptyHand()
                && completeInventory.stream().noneMatch(item ->
                        item.itemId().equals(mainHand.itemId())
                )) {
            completeInventory.add(new InventoryItemSummary(
                    mainHand.itemId(),
                    mainHand.count()
            ));
        }
        return new CoreSkillFrame(
                PLAYER,
                DimensionRef.OVERWORLD,
                100,
                10,
                new PerceptionVec3(0.5, 0.0, 0.5),
                new PerceptionVec3(0.5, 1.62, 0.5),
                new PerceptionVec3(0.0, 0.0, 1.0),
                true,
                false,
                0.0,
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        10,
                        voxels
                ),
                List.of(),
                20.0F,
                20.0F,
                20,
                completeInventory,
                mainHand,
                HeldItemSummary.empty(),
                visibleEntities,
                List.of()
        );
    }

    private static VisibleEntity enderman() {
        return enderman(ENDERMAN);
    }

    private static VisibleEntity enderman(final UUID entityId) {
        return new VisibleEntity(
                entityId,
                "minecraft:enderman",
                new PerceptionVec3(0.5, 0.0, 3.5),
                new PerceptionVec3(0.0, 0.0, 3.0),
                3.0,
                true,
                false,
                PerceptionProvenance
                    .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                Map.of("interactionLineClear", "true")
        );
    }

    private static CoreSkillFrame copyFrame(
            final CoreSkillFrame base,
            final double danger,
            final List<VisibleEntity> visibleEntities,
            final List<DangerSignal> dangerSignals
    ) {
        return new CoreSkillFrame(
                base.playerId(),
                base.dimension(),
                base.gameTime(),
                base.observationRevision(),
                base.position(),
                base.eyePosition(),
                base.lookDirection(),
                base.onGround(),
                base.inWater(),
                danger,
                base.navigation(),
                base.visibleBlockFaces(),
                base.health(),
                base.maxHealth(),
                base.foodLevel(),
                base.inventory(),
                base.mainHand(),
                base.offHand(),
                visibleEntities,
                dangerSignals
        );
    }

    private static ObservedVoxel voxel(
            final GridPos position,
            final VoxelKind kind
    ) {
        return new ObservedVoxel(position, kind, 0.0, 10);
    }

    private static CoreSkillFrame airborneFrame(
            final double x,
            final double y,
            final DangerSignal danger
    ) {
        return new CoreSkillFrame(
                PLAYER,
                DimensionRef.OVERWORLD,
                100,
                10,
                new PerceptionVec3(x, y, 0.5),
                new PerceptionVec3(x, y + 1.62, 0.5),
                new PerceptionVec3(0.0, 0.0, 1.0),
                false,
                false,
                danger.severity(),
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        10,
                        List.of(voxel(
                                new GridPos(0, -1, 0),
                                VoxelKind.SOLID
                        ))
                ),
                List.of(),
                20.0F,
                20.0F,
                20,
                List.of(),
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(),
                List.of(danger)
        );
    }

    private static CoreSkillFrame agedRoofFrame(
            final long roofAge
    ) {
        final CoreSkillFrame base = roofFrame(
                List.of(),
                SWORD,
                List.of(enderman())
        );
        final long revision = 1_000;
        final LocalNavSnapshot navigation = new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                revision,
                base.navigation().observedVoxels().values().stream()
                    .map(voxel -> new ObservedVoxel(
                            voxel.position(),
                            voxel.kind(),
                            voxel.danger(),
                            voxel.position().y() == 2
                                ? revision - roofAge
                                : revision,
                            voxel.occupancyEvidence(),
                            voxel.topSupportAffordance()
                    ))
                    .toList()
        );
        final DangerSignal proximity = new DangerSignal(
                DangerKind.HOSTILE_PROXIMITY,
                0.75,
                3.0,
                Optional.empty(),
                PerceptionProvenance.PROXIMITY_THREAT
        );
        return new CoreSkillFrame(
                base.playerId(),
                base.dimension(),
                base.gameTime(),
                revision,
                base.position(),
                base.eyePosition(),
                base.lookDirection(),
                base.onGround(),
                base.inWater(),
                proximity.severity(),
                navigation,
                base.visibleBlockFaces(),
                base.health(),
                base.maxHealth(),
                base.foodLevel(),
                base.inventory(),
                base.mainHand(),
                base.offHand(),
                base.visibleEntities(),
                List.of(proximity)
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }

    private record Fixture(
            RecordingCore core,
            BuildEndermanSafetyRoofSkill builder,
            SecureEnderPearlReserveSkill reserve
    ) {
    }

    private static final class RecordingCore
            implements CoreSkillActuator {
        private int looks;

        @Override
        public ActionOutcome move(final MovementIntent intent) {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome look(final LookIntent intent) {
            looks++;
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome jump() {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome stop() {
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome useMainHandOn(
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.DISPATCHED;
        }
    }

    private static final class NoopInteractions
            implements InteractionSkillActuator {
        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(1);
        }

        @Override
        public ActionOutcome beginMining(
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }

        @Override
        public ActionOutcome continueMining() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome abortMining() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome useOnBlock(
                final ActionHand hand,
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }

        @Override
        public ActionOutcome attack(final UUID entityId) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }

        @Override
        public OptionalDouble attackStrengthScale() {
            return OptionalDouble.of(1.0);
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }

        @Override
        public ActionOutcome continueUsing(
                final ActionHand hand
        ) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome releaseUse() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
    }
}
