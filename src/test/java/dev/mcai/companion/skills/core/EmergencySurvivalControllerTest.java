package dev.mcai.companion.skills.core;

import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.corridor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class EmergencySurvivalControllerTest {
    private static final PerceptionVec3 EAST =
            new PerceptionVec3(1.0, 0.0, 0.0);

    @Test
    void preemptsTaskControlsBeforeEmergencyEquipmentMutation() {
        final CoreSkillFrame critical = withHealth(
                frameWithInventory(
                        1,
                        20,
                        HeldItemSummary.empty(),
                        HeldItemSummary.empty(),
                        List.of(new InventoryItemSummary(
                                "minecraft:golden_apple",
                                1
                        )),
                        List.of(),
                        List.of(),
                        List.of(),
                        true,
                        DimensionRef.OVERWORLD,
                        EAST
                ),
                4.0F
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(critical);
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final AtomicBoolean preempted = new AtomicBoolean();
        final AtomicInteger preemptions = new AtomicInteger();
        final EmergencyEquipmentActuator equipment =
                (hand, itemId) -> {
                    assertTrue(
                            preempted.get(),
                            "task controls must be released before inventory"
                    );
                    return ActionOutcome.COMPLETED;
                };
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames,
                        equipment,
                        EmergencyMeleeActuator.unavailable(),
                        () -> {
                            preempted.set(true);
                            preemptions.incrementAndGet();
                        }
                );

        final var report = controller.tick();

        assertTrue(report.intervened());
        assertEquals(
                EmergencySurvivalController.State.EQUIPPING_FOOD,
                report.state()
        );
        assertEquals(1, preemptions.get());
    }

    @Test
    void consumesHeldFoodAndReleasesAfterFoodRises() {
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        0.5,
                        5,
                        new HeldItemSummary(
                                "minecraft:bread",
                                2,
                                0,
                                0
                        ),
                        HeldItemSummary.empty(),
                        List.of(),
                        List.of()
                ));
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames
                );

        EmergencySurvivalController.TickReport first = controller.tick();
        assertTrue(first.intervened());
        assertEquals(
                EmergencySurvivalController.State.EATING,
                first.state()
        );
        assertEquals(List.of(ActionHand.MAIN_HAND), actuator.itemUses);

        frames.frame = frame(
                2,
                0.5,
                11,
                new HeldItemSummary("minecraft:bread", 1, 0, 0),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
        EmergencySurvivalController.TickReport finished =
                controller.tick();

        assertTrue(finished.intervened());
        assertEquals(
                EmergencySurvivalController.State.CLEAR,
                finished.state()
        );
        assertEquals(1, actuator.releases);
    }

    @Test
    void holdsGoldenAppleUseAtFullHungerUntilStackIsConsumed() {
        final HeldItemSummary goldenApple =
                new HeldItemSummary(
                        "minecraft:golden_apple",
                        1,
                        0,
                        0
                );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        withHealth(
                                frame(
                                        1,
                                        0.5,
                                        20,
                                        goldenApple,
                                        HeldItemSummary.empty(),
                                        List.of(),
                                        List.of()
                                ),
                                4.0F
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames
                );

        final var started = controller.tick();
        assertEquals(
                EmergencySurvivalController.State.EATING,
                started.state()
        );

        frames.frame = withHealth(
                frame(
                        2,
                        0.5,
                        20,
                        goldenApple,
                        HeldItemSummary.empty(),
                        List.of(),
                        List.of()
                ),
                4.0F
        );
        final var holding = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.EATING,
                holding.state()
        );
        assertEquals("eating", holding.reason());
        assertEquals(0, actuator.releases);

        frames.frame = withHealth(
                frame(
                        3,
                        0.5,
                        20,
                        HeldItemSummary.empty(),
                        HeldItemSummary.empty(),
                        List.of(),
                        List.of()
                ),
                4.0F
        );
        final var consumed = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.CLEAR,
                consumed.state()
        );
        assertEquals("eating_finished", consumed.reason());
        assertEquals(1, actuator.releases);
    }

    @Test
    void retreatsOnlyIntoObservedStandableCellAwayFromVisibleThreat() {
        UUID hostileId = UUID.fromString(
                "00000000-0000-0000-0000-000000000999"
        );
        VisibleEntity hostile = new VisibleEntity(
                hostileId,
                "minecraft:zombie",
                new PerceptionVec3(0.5, 1.0, 0.5),
                new PerceptionVec3(-1.0, 0.0, 0.0),
                1.0,
                true,
                false,
                PerceptionProvenance.ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        DangerSignal danger = new DangerSignal(
                DangerKind.HOSTILE_PROXIMITY,
                0.75,
                2.0,
                Optional.empty(),
                PerceptionProvenance.PROXIMITY_THREAT
        );
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        1.5,
                        20,
                        HeldItemSummary.empty(),
                        HeldItemSummary.empty(),
                        List.of(hostile),
                        List.of(danger)
                ));
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames
                );

        EmergencySurvivalController.TickReport report = controller.tick();

        assertTrue(report.intervened());
        assertEquals(
                EmergencySurvivalController.State.RETREATING,
                report.state()
        );
        assertFalse(actuator.looks.isEmpty());
        assertFalse(actuator.movements.isEmpty());
        assertTrue(actuator.movements.getLast().sprint());
    }

    @Test
    void activeCombatOwnsOrdinaryHostileProximityWithoutLosingInputs() {
        final VisibleEntity hostile = new VisibleEntity(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000997"
                ),
                "minecraft:blaze",
                new PerceptionVec3(0.5, 1.0, 6.5),
                new PerceptionVec3(0.0, 0.0, 6.0),
                6.0,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final DangerSignal danger = new DangerSignal(
                DangerKind.HOSTILE_PROXIMITY,
                0.5,
                6.0,
                Optional.empty(),
                PerceptionProvenance.PROXIMITY_THREAT
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        0.5,
                        20,
                        new HeldItemSummary(
                                "minecraft:diamond_sword",
                                1,
                                0,
                                1561
                        ),
                        new HeldItemSummary(
                                "minecraft:shield",
                                1,
                                0,
                                336
                        ),
                        List.of(hostile),
                        List.of(danger)
                ));
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames
                );

        final var report = controller.tick(true);

        assertFalse(report.intervened());
        assertEquals(
                EmergencySurvivalController.State.CLEAR,
                report.state()
        );
        assertEquals(0, actuator.stops);
        assertTrue(actuator.looks.isEmpty());
        assertTrue(actuator.itemUses.isEmpty());
    }

    @Test
    void activeCombatKeepsTargetLossReacquisitionOwnership() {
        final VisibleEntity hostile = new VisibleEntity(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000995"
                ),
                "minecraft:zombie",
                new PerceptionVec3(0.5, 1.0, 6.5),
                new PerceptionVec3(0.0, 0.0, 6.0),
                6.0,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        0.5,
                        20,
                        HeldItemSummary.empty(),
                        HeldItemSummary.empty(),
                        List.of(hostile),
                        List.of()
                ));
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames
                );

        assertFalse(controller.tick(true).intervened());

        frames.frame = frame(
                2,
                0.5,
                20,
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
        final var targetLost = controller.tick(true);

        assertFalse(targetLost.intervened());
        assertEquals(EmergencySurvivalController.State.CLEAR,
                targetLost.state());
        assertEquals(0, actuator.stops);
        assertTrue(actuator.looks.isEmpty());
        assertTrue(actuator.movements.isEmpty());
    }

    @Test
    void continuousThreatRetreatIsBoundedThenFallsBackToShield() {
        final VisibleEntity hostile = new VisibleEntity(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000996"
                ),
                "minecraft:zombie",
                new PerceptionVec3(0.5, 1.0, 0.5),
                new PerceptionVec3(-1.0, 0.0, 0.0),
                1.0,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final DangerSignal danger = new DangerSignal(
                DangerKind.HOSTILE_PROXIMITY,
                0.75,
                2.0,
                Optional.empty(),
                PerceptionProvenance.PROXIMITY_THREAT
        );
        final HeldItemSummary shield = new HeldItemSummary(
                "minecraft:shield",
                1,
                0,
                336
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        1.5,
                        20,
                        HeldItemSummary.empty(),
                        shield,
                        List.of(hostile),
                        List.of(danger)
                ));
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames
                );

        assertEquals(
                EmergencySurvivalController.State.RETREATING,
                controller.tick().state()
        );
        frames.frame = frame(
                62,
                1.5,
                20,
                HeldItemSummary.empty(),
                shield,
                List.of(hostile),
                List.of(danger)
        );
        final var bounded = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.GUARDING,
                bounded.state()
        );
        assertEquals(List.of(ActionHand.OFF_HAND), actuator.itemUses);
    }

    @Test
    void meleeCombatMayOwnPhysicalContactWithoutEmergencyGuardOverride() {
        final DangerSignal contact = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                1.0,
                0.5,
                Optional.of(new PerceptionVec3(0.0, 0.0, 1.0)),
                PerceptionProvenance.PHYSICAL_CONTACT
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        0.5,
                        20,
                        new HeldItemSummary(
                                "minecraft:iron_sword",
                                1,
                                0,
                                250
                        ),
                        new HeldItemSummary(
                                "minecraft:shield",
                                1,
                                0,
                                336
                        ),
                        List.of(),
                        List.of(contact)
                ));
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames
                );

        final var report = controller.tick(true, true);

        assertFalse(report.intervened());
        assertEquals(
                EmergencySurvivalController.State.CLEAR,
                report.state()
        );
        assertTrue(actuator.itemUses.isEmpty());
    }

    @Test
    void emergencyRetreatRejectsSingleRayAirAndGenericSupport() {
        final VisibleEntity hostile = new VisibleEntity(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000998"
                ),
                "minecraft:zombie",
                new PerceptionVec3(0.5, 1.0, 0.5),
                new PerceptionVec3(-1.0, 0.0, 0.0),
                1.0,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final DangerSignal danger = new DangerSignal(
                DangerKind.HOSTILE_PROXIMITY,
                0.75,
                2.0,
                Optional.empty(),
                PerceptionProvenance.PROXIMITY_THREAT
        );
        final CoreSkillFrame base = frame(
                1,
                0.5,
                20,
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(hostile),
                List.of(danger)
        );
        final List<LocalNavSnapshot> unsafe = List.of(
                replaceEvidence(
                        base.navigation(),
                        new GridPos(1, 1, 0),
                        OccupancyEvidence.SINGLE_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ),
                replaceEvidence(
                        base.navigation(),
                        new GridPos(1, 0, 0),
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.UNKNOWN
                )
        );

        for (LocalNavSnapshot navigation : unsafe) {
            final CoreSkillTestFixtures.MutableFrames frames =
                    new CoreSkillTestFixtures.MutableFrames(
                            withNavigation(base, navigation)
                    );
            final CoreSkillTestFixtures.RecordingActuator actuator =
                    new CoreSkillTestFixtures.RecordingActuator();
            final EmergencySurvivalController controller =
                    new EmergencySurvivalController(
                            PLAYER_ID,
                            actuator,
                            frames
                    );

            final var report = controller.tick();

            assertTrue(report.intervened());
            assertTrue(actuator.movements.isEmpty());
            assertTrue(actuator.stops > 0);
        }
    }

    @Test
    void unknownThreatDirectionNeverCausesBlindMovement() {
        DangerSignal danger = new DangerSignal(
                DangerKind.PROJECTILE_PROXIMITY,
                0.75,
                4.0,
                Optional.empty(),
                PerceptionProvenance.PROXIMITY_THREAT
        );
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        0.5,
                        20,
                        HeldItemSummary.empty(),
                        HeldItemSummary.empty(),
                        List.of(),
                        List.of(danger)
                ));
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames
                );

        EmergencySurvivalController.TickReport report = controller.tick();

        assertTrue(report.intervened());
        assertTrue(actuator.stops > 0);
        // The local map does contain an east cell, but without an observed
        // threat direction no retreat direction may be invented.
        assertTrue(actuator.movements.isEmpty());
    }

    @Test
    void unknownThreatUsesAnAlreadyHeldShieldWithoutMoving() {
        DangerSignal danger = new DangerSignal(
                DangerKind.PROJECTILE_PROXIMITY,
                0.75,
                4.0,
                Optional.empty(),
                PerceptionProvenance.PROXIMITY_THREAT
        );
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        0.5,
                        20,
                        HeldItemSummary.empty(),
                        new HeldItemSummary(
                                "minecraft:shield",
                                1,
                                0,
                                336
                        ),
                        List.of(),
                        List.of(danger)
                ));
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames
                );

        EmergencySurvivalController.TickReport report = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.GUARDING,
                report.state()
        );
        assertEquals(List.of(ActionHand.OFF_HAND), actuator.itemUses);
        assertTrue(actuator.movements.isEmpty());
    }

    @Test
    void directionlessRecentDamageDoesNotRemainShieldOnly() {
        final DangerSignal damage = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                1.0,
                0.0,
                Optional.empty(),
                PerceptionProvenance.RECENT_DAMAGE_EVENT
        );
        final HeldItemSummary shield = new HeldItemSummary(
                "minecraft:shield",
                1,
                0,
                336
        );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        new CoreSkillTestFixtures.MutableFrames(
                                frameWithInventory(
                                        1,
                                        20,
                                        HeldItemSummary.empty(),
                                        shield,
                                        List.of(),
                                        List.of(),
                                        List.of(damage),
                                        List.of(),
                                        true,
                                        DimensionRef.END,
                                        EAST
                                )
                        )
                );

        final var report = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.GUARDING,
                report.state()
        );
        assertEquals(List.of(ActionHand.OFF_HAND), actuator.itemUses);
        assertFalse(
                actuator.movements.isEmpty(),
                "A directionless magic hit must trigger bounded side-step "
                    + "even while the shield is held"
        );
        assertTrue(actuator.movements.getLast().sneak());
        assertEquals(0.0, actuator.movements.getLast().forward(), 0.001);
    }

    @Test
    void continuousGuardKeepsOneUninterruptedShieldUse() {
        final DangerSignal danger = new DangerSignal(
                DangerKind.PROJECTILE_PROXIMITY,
                0.75,
                4.0,
                Optional.empty(),
                PerceptionProvenance.PROXIMITY_THREAT
        );
        final HeldItemSummary shield = new HeldItemSummary(
                "minecraft:shield",
                1,
                0,
                336
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        0.5,
                        20,
                        HeldItemSummary.empty(),
                        shield,
                        List.of(),
                        List.of(danger)
                ));
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames
                );

        assertEquals("guarding", controller.tick().reason());
        frames.frame = frame(
                2,
                0.5,
                20,
                HeldItemSummary.empty(),
                shield,
                List.of(),
                List.of(danger)
        );
        assertEquals("guarding", controller.tick().reason());

        assertEquals(
                List.of(ActionHand.OFF_HAND),
                actuator.itemUses,
                "A continuing guard must not restart the shield every tick"
        );
        assertEquals(
                0,
                actuator.releases,
                "Vanilla shield warmup requires uninterrupted use"
        );
    }

    @Test
    void directionalDamageDoesNotLeaveShieldedBodyStationary() {
        final VisibleEntity zombie = new VisibleEntity(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000993"
                ),
                "minecraft:zombie",
                new PerceptionVec3(0.5, 5.0, 2.0),
                new PerceptionVec3(0.0, 0.0, 1.5),
                1.5,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final DangerSignal damage = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                1.0,
                1.5,
                Optional.of(new PerceptionVec3(0.0, 0.0, 1.0)),
                PerceptionProvenance.RECENT_DAMAGE_EVENT
        );
        final HeldItemSummary shield = new HeldItemSummary(
                "minecraft:shield",
                1,
                0,
                336
        );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        new CoreSkillTestFixtures.MutableFrames(
                                frameWithInventory(
                                        1,
                                        20,
                                        HeldItemSummary.empty(),
                                        shield,
                                        List.of(),
                                        List.of(zombie),
                                        List.of(damage),
                                        List.of(),
                                        true,
                                        DimensionRef.OVERWORLD,
                                        EAST
                                )
                        )
                );

        final var report = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.GUARDING,
                report.state()
        );
        assertEquals(List.of(ActionHand.OFF_HAND), actuator.itemUses);
        assertFalse(
                actuator.movements.isEmpty(),
                "A fair directional hit must trigger a bounded backstep "
                    + "instead of a look-only shield response"
        );
        assertTrue(actuator.movements.getLast().sneak());
    }

    @Test
    void visibleContactThreatIsAimedAtThenCounterattacked() {
        final UUID zombieId = UUID.fromString(
                "00000000-0000-0000-0000-000000000991"
        );
        final VisibleEntity zombie = new VisibleEntity(
                zombieId,
                "minecraft:zombie",
                new PerceptionVec3(0.5, 5.0, 2.5),
                new PerceptionVec3(0.0, 0.0, 2.0),
                2.0,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final DangerSignal contact = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                1.0,
                2.0,
                Optional.of(new PerceptionVec3(0.0, 0.0, -1.0)),
                PerceptionProvenance.PHYSICAL_CONTACT
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frameWithInventory(
                                1,
                                20,
                                HeldItemSummary.empty(),
                                HeldItemSummary.empty(),
                                List.of(),
                                List.of(zombie),
                                List.of(contact),
                                List.of(),
                                true,
                                DimensionRef.OVERWORLD,
                                EAST
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final RecordingMelee melee = new RecordingMelee();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames,
                        EmergencyEquipmentActuator.unavailable(),
                        melee
                );

        final var aiming = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.COUNTERATTACKING,
                aiming.state()
        );
        assertEquals("counterattack_aiming", aiming.reason());
        assertTrue(melee.targets.isEmpty());

        frames.frame = frameWithInventory(
                2,
                20,
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(),
                List.of(zombie),
                List.of(contact),
                List.of(),
                true,
                DimensionRef.OVERWORLD,
                new PerceptionVec3(0.0, -0.62, 2.0)
        );
        final var attacked = controller.tick();

        assertEquals("counterattack_dispatched", attacked.reason());
        assertEquals(List.of(zombieId), melee.targets);
    }

    @Test
    void emergencyCounterattackRespectsVanillaRecharge() {
        final UUID zombieId = UUID.fromString(
                "00000000-0000-0000-0000-000000000990"
        );
        final VisibleEntity zombie = new VisibleEntity(
                zombieId,
                "minecraft:zombie",
                new PerceptionVec3(2.5, 5.0, 0.5),
                new PerceptionVec3(2.0, 0.0, 0.0),
                2.0,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final DangerSignal contact = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                1.0,
                2.0,
                Optional.of(new PerceptionVec3(-1.0, 0.0, 0.0)),
                PerceptionProvenance.PHYSICAL_CONTACT
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frameWithInventory(
                                1,
                                20,
                                HeldItemSummary.empty(),
                                HeldItemSummary.empty(),
                                List.of(),
                                List.of(zombie),
                                List.of(contact),
                                List.of(),
                                true,
                                DimensionRef.OVERWORLD,
                                new PerceptionVec3(2.0, -0.62, 0.0)
                        )
                );
        final RecordingMelee melee = new RecordingMelee();
        melee.cooldown = 0.50;
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        new CoreSkillTestFixtures.RecordingActuator(),
                        frames,
                        EmergencyEquipmentActuator.unavailable(),
                        melee
                );

        final var report = controller.tick();

        assertEquals("counterattack_cooling_down", report.reason());
        assertTrue(melee.targets.isEmpty());
    }

    @Test
    void shieldBridgesCooldownButReadyMeleeAttackIsStillSpent() {
        final UUID endermanId = UUID.fromString(
                "00000000-0000-0000-0000-000000000988"
        );
        final VisibleEntity enderman = new VisibleEntity(
                endermanId,
                "minecraft:enderman",
                new PerceptionVec3(2.5, 5.0, 0.5),
                new PerceptionVec3(2.0, 0.0, 0.0),
                2.0,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final DangerSignal contact = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                1.0,
                2.0,
                Optional.of(new PerceptionVec3(-1.0, 0.0, 0.0)),
                PerceptionProvenance.PHYSICAL_CONTACT
        );
        final HeldItemSummary sword = new HeldItemSummary(
                "minecraft:diamond_sword",
                1,
                0,
                1561
        );
        final HeldItemSummary shield = new HeldItemSummary(
                "minecraft:shield",
                1,
                0,
                336
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frameWithInventory(
                                1,
                                20,
                                sword,
                                shield,
                                List.of(),
                                List.of(enderman),
                                List.of(contact),
                                List.of(),
                                true,
                                DimensionRef.END,
                                new PerceptionVec3(2.0, -0.62, 0.0)
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final RecordingMelee melee = new RecordingMelee();
        melee.cooldown = 0.50;
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames,
                        EmergencyEquipmentActuator.unavailable(),
                        melee
                );

        final var guarded = controller.tick();

        assertEquals("guarding", guarded.reason());
        assertEquals(
                EmergencySurvivalController.State.GUARDING,
                guarded.state()
        );
        assertEquals(List.of(ActionHand.OFF_HAND), actuator.itemUses);
        assertTrue(melee.targets.isEmpty());

        melee.cooldown = 1.0;
        frames.frame = frameWithInventory(
                2,
                20,
                sword,
                shield,
                List.of(),
                List.of(enderman),
                List.of(contact),
                List.of(),
                true,
                DimensionRef.END,
                new PerceptionVec3(2.0, -0.62, 0.0)
        );
        final var attacked = controller.tick();

        assertEquals("counterattack_dispatched", attacked.reason());
        assertEquals(List.of(endermanId), melee.targets);
    }

    @Test
    void contactEquipsOwnedSwordBeforeAttackingWithFood() {
        final UUID endermanId = UUID.fromString(
                "00000000-0000-0000-0000-000000000987"
        );
        final VisibleEntity enderman = new VisibleEntity(
                endermanId,
                "minecraft:enderman",
                new PerceptionVec3(2.5, 5.0, 0.5),
                new PerceptionVec3(2.0, 0.0, 0.0),
                2.0,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final DangerSignal contact = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                1.0,
                2.0,
                Optional.of(new PerceptionVec3(-1.0, 0.0, 0.0)),
                PerceptionProvenance.PHYSICAL_CONTACT
        );
        final RecordingEquipment equipment =
                new RecordingEquipment();
        final RecordingMelee melee = new RecordingMelee();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        new CoreSkillTestFixtures.RecordingActuator(),
                        new CoreSkillTestFixtures.MutableFrames(
                                frameWithInventory(
                                        1,
                                        20,
                                        new HeldItemSummary(
                                                "minecraft:cooked_beef",
                                                3,
                                                0,
                                                0
                                        ),
                                        new HeldItemSummary(
                                                "minecraft:shield",
                                                1,
                                                0,
                                                336
                                        ),
                                        List.of(
                                                new InventoryItemSummary(
                                                        "minecraft:"
                                                            + "diamond_sword",
                                                        1
                                                ),
                                                new InventoryItemSummary(
                                                        "minecraft:"
                                                            + "cooked_beef",
                                                        3
                                                )
                                        ),
                                        List.of(enderman),
                                        List.of(contact),
                                        List.of(),
                                        true,
                                        DimensionRef.END,
                                        new PerceptionVec3(
                                                2.0,
                                                -0.62,
                                                0.0
                                        )
                                )
                        ),
                        equipment,
                        melee
                );

        final var report = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.EQUIPPING_WEAPON,
                report.state()
        );
        assertEquals(
                List.of("MAIN_HAND=minecraft:diamond_sword"),
                equipment.requests
        );
        assertTrue(melee.targets.isEmpty());
    }

    @Test
    void emergencyCounterattackBackstepsIntoObservedSafeCell() {
        final UUID zombieId = UUID.fromString(
                "00000000-0000-0000-0000-000000000991"
        );
        final VisibleEntity zombie = new VisibleEntity(
                zombieId,
                "minecraft:zombie",
                new PerceptionVec3(-1.5, 1.0, 0.5),
                new PerceptionVec3(-2.0, 0.0, 0.0),
                2.0,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final DangerSignal contact = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                1.0,
                2.0,
                Optional.of(new PerceptionVec3(-1.0, 0.0, 0.0)),
                PerceptionProvenance.PHYSICAL_CONTACT
        );
        final CoreSkillFrame base = frame(
                1,
                0.5,
                20,
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(zombie),
                List.of(contact)
        );
        final CoreSkillFrame aligned = new CoreSkillFrame(
                base.playerId(),
                base.dimension(),
                base.gameTime(),
                base.observationRevision(),
                base.position(),
                base.eyePosition(),
                new PerceptionVec3(-2.0, -0.62, 0.0).normalized(),
                base.onGround(),
                base.inWater(),
                base.danger(),
                base.navigation(),
                base.visibleBlockFaces(),
                base.health(),
                base.maxHealth(),
                base.foodLevel(),
                base.inventory(),
                base.mainHand(),
                base.offHand(),
                base.visibleEntities(),
                base.dangerSignals()
        );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final RecordingMelee melee = new RecordingMelee();
        final DangerSignal unseenWarning = new DangerSignal(
                DangerKind.HOSTILE_PROXIMITY,
                0.85,
                4.0,
                Optional.empty(),
                PerceptionProvenance.AUTHORIZED_PLAYER_WARNING
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frameWithInventory(
                                0,
                                20,
                                HeldItemSummary.empty(),
                                HeldItemSummary.empty(),
                                List.of(),
                                List.of(),
                                List.of(unseenWarning),
                                List.of(),
                                true,
                                DimensionRef.OVERWORLD,
                                new PerceptionVec3(1.0, 0.0, 0.0)
                        )
                );
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames,
                        EmergencyEquipmentActuator.unavailable(),
                        melee
                );

        assertEquals("threat_not_visible", controller.tick().reason());
        frames.frame = aligned;
        final var report = controller.tick();

        assertEquals("counterattack_dispatched", report.reason());
        assertEquals(List.of(zombieId), melee.targets);
        assertFalse(actuator.movements.isEmpty());
        assertTrue(
                actuator.movements.getLast().forward() < 0.0,
                "The safe eastward cell should be a backstep while "
                    + "looking west at the threat"
        );
        assertFalse(actuator.movements.getLast().sneak());
    }

    @Test
    void directionalPlayerWarningTurnsIntoBoundedSneakSeparation() {
        final DangerSignal warning = new DangerSignal(
                DangerKind.HOSTILE_PROXIMITY,
                0.85,
                4.0,
                Optional.of(new PerceptionVec3(-1.0, 0.0, 0.0)),
                PerceptionProvenance.AUTHORIZED_PLAYER_WARNING
        );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frameWithInventory(
                                1,
                                20,
                                HeldItemSummary.empty(),
                                HeldItemSummary.empty(),
                                List.of(),
                                List.of(),
                                List.of(warning),
                                List.of(),
                                true,
                                DimensionRef.OVERWORLD,
                                new PerceptionVec3(-1.0, 0.0, 0.0)
                        )
                );
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames,
                        EmergencyEquipmentActuator.unavailable(),
                        EmergencyMeleeActuator.unavailable()
                );

        final var report = controller.tick();

        assertEquals("warning_separating", report.reason());
        assertEquals(
                EmergencySurvivalController.State.WARNING_REACTING,
                report.state()
        );
        assertFalse(actuator.movements.isEmpty());
        assertTrue(actuator.movements.getLast().sneak());
        assertTrue(
                actuator.movements.getLast().forward() < 0.0,
                "Once facing the warned-about rear direction, the body "
                    + "must separate with a backstep"
        );
    }

    @Test
    void recentDamageScanStartsAtFairAttackerDirection() {
        final DangerSignal damage = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                1.0,
                0.0,
                Optional.of(new PerceptionVec3(-1.0, 0.0, 0.0)),
                PerceptionProvenance.RECENT_DAMAGE_EVENT
        );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        new CoreSkillTestFixtures.MutableFrames(
                                frameWithInventory(
                                        0,
                                        20,
                                        HeldItemSummary.empty(),
                                        HeldItemSummary.empty(),
                                        List.of(),
                                        List.of(),
                                        List.of(damage),
                                        List.of(),
                                        true,
                                        DimensionRef.OVERWORLD,
                                        EAST
                                )
                        )
                );

        final var report = controller.tick();

        assertEquals("recent_damage_scanning", report.reason());
        assertFalse(actuator.looks.isEmpty());
        assertEquals(
                90.0F,
                actuator.looks.getLast().yawDegrees(),
                0.01F,
                "The first scan must face the direction supplied by the "
                    + "fair damage cue, not the old heading"
        );
    }

    @Test
    void directedRecentDamageSeparatesBeforeTheNextModelRoundTrip() {
        final DangerSignal damage = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                1.0,
                0.0,
                Optional.of(new PerceptionVec3(-1.0, 0.0, 0.0)),
                PerceptionProvenance.RECENT_DAMAGE_EVENT
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frameWithInventory(
                                0,
                                20,
                                HeldItemSummary.empty(),
                                HeldItemSummary.empty(),
                                List.of(),
                                List.of(),
                                List.of(damage),
                                List.of(),
                                true,
                                DimensionRef.OVERWORLD,
                                EAST
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames
                );

        assertEquals(
                "recent_damage_scanning",
                controller.tick().reason()
        );
        frames.frame = withGameTime(frames.frame, 1L);

        final var separated = controller.tick();

        assertEquals("recent_damage_separating", separated.reason());
        assertFalse(
                actuator.movements.isEmpty(),
                "A directed recent hit must issue a bounded retreat before "
                    + "the model can answer"
        );
        assertTrue(
                actuator.movements.getLast().sneak(),
                "The emergency separation must remain a cautious vanilla "
                    + "movement input"
        );
    }

    @Test
    void directionlessRecentDamageEventuallyTakesBoundedSneakSeparation() {
        final DangerSignal damage = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                1.0,
                0.0,
                Optional.empty(),
                PerceptionProvenance.RECENT_DAMAGE_EVENT
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frameWithInventory(
                                0,
                                20,
                                HeldItemSummary.empty(),
                                HeldItemSummary.empty(),
                                List.of(),
                                List.of(),
                                List.of(damage),
                                List.of(),
                                true,
                                DimensionRef.OVERWORLD,
                                EAST
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames
                );

        for (int tick = 0; tick < 4; tick++) {
            final var report = controller.tick();
            assertEquals("recent_damage_scanning", report.reason());
            assertTrue(actuator.movements.isEmpty());
            frames.frame = withGameTime(frames.frame, tick + 1L);
        }

        final var separation = controller.tick();

        assertEquals("recent_damage_separating", separation.reason());
        assertFalse(actuator.movements.isEmpty());
        assertTrue(actuator.movements.getLast().sneak());
        assertTrue(
                actuator.movements.getLast().forward() > 0.0,
                "A repeated directionless hit must not leave the body "
                    + "staring in place after its fair scan"
        );
    }

    @Test
    void directionlessDamageStillSeparatesDuringShortKnockbackAirTime() {
        final DangerSignal damage = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                1.0,
                0.0,
                Optional.empty(),
                PerceptionProvenance.RECENT_DAMAGE_EVENT
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frameWithInventory(
                                0,
                                20,
                                HeldItemSummary.empty(),
                                HeldItemSummary.empty(),
                                List.of(),
                                List.of(),
                                List.of(damage),
                                List.of(),
                                false,
                                DimensionRef.OVERWORLD,
                                EAST
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames
                );

        for (int tick = 0; tick < 4; tick++) {
            final var report = controller.tick();
            assertEquals("recent_damage_scanning", report.reason());
            assertTrue(actuator.movements.isEmpty());
            frames.frame = withGameTime(frames.frame, tick + 1L);
        }

        final var separation = controller.tick();

        assertEquals("recent_damage_separating", separation.reason());
        assertFalse(actuator.movements.isEmpty());
        assertTrue(actuator.movements.getLast().sneak());
    }

    @Test
    void emergencyCounterattackUsesSneakBackstepWhenFloorIsOccluded() {
        final UUID zombieId = UUID.fromString(
                "00000000-0000-0000-0000-000000000992"
        );
        final VisibleEntity zombie = new VisibleEntity(
                zombieId,
                "minecraft:zombie",
                new PerceptionVec3(0.5, 5.0, 2.5),
                new PerceptionVec3(0.0, 0.0, 2.0),
                2.0,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final DangerSignal contact = new DangerSignal(
                DangerKind.THREAT_CONTACT,
                1.0,
                2.0,
                Optional.of(new PerceptionVec3(0.0, 0.0, 1.0)),
                PerceptionProvenance.PHYSICAL_CONTACT
        );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final RecordingMelee melee = new RecordingMelee();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        new CoreSkillTestFixtures.MutableFrames(
                                frameWithInventory(
                                        1,
                                        20,
                                        HeldItemSummary.empty(),
                                        HeldItemSummary.empty(),
                                        List.of(),
                                        List.of(zombie),
                                        List.of(contact),
                                        List.of(),
                                        true,
                                        DimensionRef.OVERWORLD,
                                        new PerceptionVec3(
                                                0.0,
                                                -0.62,
                                                2.0
                                        )
                                )
                        ),
                        EmergencyEquipmentActuator.unavailable(),
                        melee
                );

        final var report = controller.tick();

        assertEquals("counterattack_dispatched", report.reason());
        assertFalse(actuator.movements.isEmpty());
        assertTrue(actuator.movements.getLast().sneak());
        assertTrue(actuator.movements.getLast().forward() < 0.0);
    }

    @Test
    void criticalGoldenApplePreemptsNonContactHostileProximity() {
        final VisibleEntity zombie = new VisibleEntity(
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000989"
                ),
                "minecraft:zombie",
                new PerceptionVec3(0.5, 5.0, 4.5),
                new PerceptionVec3(0.0, 0.0, 4.0),
                4.0,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final DangerSignal proximity = new DangerSignal(
                DangerKind.HOSTILE_PROXIMITY,
                0.65,
                4.0,
                Optional.empty(),
                PerceptionProvenance.PROXIMITY_THREAT
        );
        final List<InventoryItemSummary> inventory = List.of(
                new InventoryItemSummary(
                        "minecraft:golden_apple",
                        1
                )
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        withHealth(
                                frameWithInventory(
                                        1,
                                        20,
                                        HeldItemSummary.empty(),
                                        HeldItemSummary.empty(),
                                        inventory,
                                        List.of(zombie),
                                        List.of(proximity),
                                        List.of(),
                                        true,
                                        DimensionRef.OVERWORLD,
                                        EAST
                                ),
                                3.0F
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final RecordingEquipment equipment =
                new RecordingEquipment();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames,
                        equipment
                );

        final var equipped = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.EQUIPPING_FOOD,
                equipped.state()
        );
        assertEquals(
                "equipping_critical_golden_apple",
                equipped.reason()
        );
        assertEquals(
                List.of("MAIN_HAND=minecraft:golden_apple"),
                equipment.requests
        );

        frames.frame = withHealth(
                frameWithInventory(
                        2,
                        20,
                        new HeldItemSummary(
                                "minecraft:golden_apple",
                                1,
                                0,
                                0
                        ),
                        HeldItemSummary.empty(),
                        inventory,
                        List.of(zombie),
                        List.of(proximity),
                        List.of(),
                        true,
                        DimensionRef.OVERWORLD,
                        EAST
                ),
                3.0F
        );
        final var eating = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.EATING,
                eating.state()
        );
        assertEquals(List.of(ActionHand.MAIN_HAND), actuator.itemUses);
    }

    @Test
    void atomicallyEquipsOwnedFoodBeforeEating() {
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frameWithInventory(
                                1,
                                5,
                                HeldItemSummary.empty(),
                                HeldItemSummary.empty(),
                                List.of(new InventoryItemSummary(
                                        "minecraft:bread",
                                        3
                                )),
                                List.of(),
                                List.of(),
                                List.of(),
                                true,
                                DimensionRef.OVERWORLD,
                                EAST
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final RecordingEquipment equipment =
                new RecordingEquipment();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames,
                        equipment
                );

        final var prepared = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.EQUIPPING_FOOD,
                prepared.state()
        );
        assertEquals(
                List.of("MAIN_HAND=minecraft:bread"),
                equipment.requests
        );

        frames.frame = frame(
                2,
                0.5,
                5,
                new HeldItemSummary(
                        "minecraft:bread",
                        3,
                        0,
                        0
                ),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
        final var eating = controller.tick();
        assertEquals(
                EmergencySurvivalController.State.EATING,
                eating.state()
        );
        assertEquals(List.of(ActionHand.MAIN_HAND), actuator.itemUses);
    }

    @Test
    void atomicallyEquipsOwnedShieldWhenRetreatDirectionIsUnknown() {
        final DangerSignal danger = new DangerSignal(
                DangerKind.PROJECTILE_PROXIMITY,
                0.75,
                4.0,
                Optional.empty(),
                PerceptionProvenance.PROXIMITY_THREAT
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frameWithInventory(
                                1,
                                20,
                                HeldItemSummary.empty(),
                                HeldItemSummary.empty(),
                                List.of(new InventoryItemSummary(
                                        "minecraft:shield",
                                        1
                                )),
                                List.of(),
                                List.of(danger),
                                List.of(),
                                true,
                                DimensionRef.OVERWORLD,
                                EAST
                        )
                );
        final RecordingEquipment equipment =
                new RecordingEquipment();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        new CoreSkillTestFixtures.RecordingActuator(),
                        frames,
                        equipment
                );

        final var report = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.EQUIPPING_SHIELD,
                report.state()
        );
        assertEquals(
                List.of("OFF_HAND=minecraft:shield"),
                equipment.requests
        );
    }

    @Test
    void equipsAndDeploysWaterOnAFreshVisibleFallSurface() {
        final DangerSignal falling = new DangerSignal(
                DangerKind.FALLING,
                0.8,
                0.0,
                Optional.empty(),
                PerceptionProvenance.BODY_HAZARD
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frameWithInventory(
                                1,
                                20,
                                HeldItemSummary.empty(),
                                HeldItemSummary.empty(),
                                List.of(new InventoryItemSummary(
                                        "minecraft:water_bucket",
                                        1
                                )),
                                List.of(),
                                List.of(falling),
                                List.of(),
                                false,
                                DimensionRef.OVERWORLD,
                                EAST
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final RecordingEquipment equipment =
                new RecordingEquipment();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames,
                        equipment
                );

        final var equipped = controller.tick();
        assertEquals(
                EmergencySurvivalController.State.EQUIPPING_WATER,
                equipped.state()
        );
        assertEquals(
                List.of("MAIN_HAND=minecraft:water_bucket"),
                equipment.requests
        );

        final PerceptionVec3 downward =
                new PerceptionVec3(0.0, -1.0, 0.0);
        final VisibleBlockFace ground = new VisibleBlockFace(
                new BlockCoordinate(0, 3, 0),
                "minecraft:stone",
                "up",
                new PerceptionVec3(0.5, 4.0, 0.5),
                2.62,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of()
        );
        frames.frame = frameWithInventory(
                2,
                20,
                new HeldItemSummary(
                        "minecraft:water_bucket",
                        1,
                        0,
                        0
                ),
                HeldItemSummary.empty(),
                List.of(new InventoryItemSummary(
                        "minecraft:water_bucket",
                        1
                )),
                List.of(),
                List.of(falling),
                List.of(ground),
                false,
                DimensionRef.OVERWORLD,
                downward
        );
        final var deployed = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.DEPLOYING_WATER,
                deployed.state()
        );
        assertEquals(
                List.of(ActionHand.MAIN_HAND),
                actuator.itemUses
        );
        assertTrue(actuator.blockUses.isEmpty());
    }

    @Test
    void usesAnOwnedHayBaleInsteadOfIllegalWaterInTheNether() {
        final DangerSignal falling = new DangerSignal(
                DangerKind.FALLING,
                0.8,
                0.0,
                Optional.empty(),
                PerceptionProvenance.BODY_HAZARD
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frameWithInventory(
                                1,
                                20,
                                HeldItemSummary.empty(),
                                HeldItemSummary.empty(),
                                List.of(
                                        new InventoryItemSummary(
                                                "minecraft:water_bucket",
                                                1
                                        ),
                                        new InventoryItemSummary(
                                                "minecraft:hay_block",
                                                1
                                        )
                                ),
                                List.of(),
                                List.of(falling),
                                List.of(),
                                false,
                                DimensionRef.NETHER,
                                EAST
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final RecordingEquipment equipment =
                new RecordingEquipment();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames,
                        equipment
                );

        final var equipped = controller.tick();
        assertEquals(
                EmergencySurvivalController.State
                        .EQUIPPING_FALL_CLUTCH,
                equipped.state()
        );
        assertEquals(
                List.of("MAIN_HAND=minecraft:hay_block"),
                equipment.requests
        );

        final PerceptionVec3 downward =
                new PerceptionVec3(0.0, -1.0, 0.0);
        final VisibleBlockFace ground = new VisibleBlockFace(
                new BlockCoordinate(0, 3, 0),
                "minecraft:netherrack",
                "up",
                new PerceptionVec3(0.5, 4.0, 0.5),
                2.62,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of()
        );
        frames.frame = frameWithInventory(
                2,
                20,
                new HeldItemSummary(
                        "minecraft:hay_block",
                        1,
                        0,
                        0
                ),
                HeldItemSummary.empty(),
                List.of(new InventoryItemSummary(
                        "minecraft:hay_block",
                        1
                )),
                List.of(),
                List.of(falling),
                List.of(ground),
                false,
                DimensionRef.NETHER,
                downward
        );
        final var deployed = controller.tick();

        assertEquals(
                EmergencySurvivalController.State
                        .DEPLOYING_FALL_CLUTCH,
                deployed.state()
        );
        assertEquals(1, actuator.blockUses.size());
    }

    @Test
    void equipsAndDeploysWaterOnAFreshVisibleFallSurfaceInTheEnd() {
        final DangerSignal falling = new DangerSignal(
                DangerKind.FALLING,
                0.8,
                0.0,
                Optional.empty(),
                PerceptionProvenance.BODY_HAZARD
        );
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(
                        frameWithInventory(
                                1,
                                20,
                                HeldItemSummary.empty(),
                                HeldItemSummary.empty(),
                                List.of(
                                        new InventoryItemSummary(
                                                "minecraft:water_bucket",
                                                1
                                        ),
                                        new InventoryItemSummary(
                                                "minecraft:hay_block",
                                                1
                                        )
                                ),
                                List.of(),
                                List.of(falling),
                                List.of(),
                                false,
                                DimensionRef.END,
                                EAST
                        )
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final RecordingEquipment equipment = new RecordingEquipment();
        final EmergencySurvivalController controller =
                new EmergencySurvivalController(
                        PLAYER_ID,
                        actuator,
                        frames,
                        equipment
                );

        final var equipped = controller.tick();
        assertEquals(
                EmergencySurvivalController.State.EQUIPPING_WATER,
                equipped.state()
        );
        assertEquals(
                List.of("MAIN_HAND=minecraft:water_bucket"),
                equipment.requests
        );

        final PerceptionVec3 downward =
                new PerceptionVec3(0.0, -1.0, 0.0);
        final VisibleBlockFace ground = new VisibleBlockFace(
                new BlockCoordinate(0, 3, 0),
                "minecraft:end_stone",
                "up",
                new PerceptionVec3(0.5, 4.0, 0.5),
                2.62,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of()
        );
        frames.frame = frameWithInventory(
                2,
                20,
                new HeldItemSummary(
                        "minecraft:water_bucket",
                        1,
                        0,
                        0
                ),
                HeldItemSummary.empty(),
                List.of(
                        new InventoryItemSummary(
                                "minecraft:water_bucket",
                                1
                        ),
                        new InventoryItemSummary(
                                "minecraft:hay_block",
                                1
                        )
                ),
                List.of(),
                List.of(falling),
                List.of(ground),
                false,
                DimensionRef.END,
                downward
        );
        final var deployed = controller.tick();

        assertEquals(
                EmergencySurvivalController.State.DEPLOYING_WATER,
                deployed.state()
        );
        assertEquals(List.of(ActionHand.MAIN_HAND), actuator.itemUses);
        assertTrue(actuator.blockUses.isEmpty());
    }

    private static CoreSkillFrame frame(
            long revision,
            double x,
            int food,
            HeldItemSummary mainHand,
            HeldItemSummary offHand,
            List<VisibleEntity> entities,
            List<DangerSignal> dangers
    ) {
        double maximumX = Math.max(1.0, x + 1.0);
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                revision,
                revision,
                new PerceptionVec3(x, 1.0, 0.5),
                new PerceptionVec3(x, 2.62, 0.5),
                EAST,
                true,
                false,
                dangers.stream()
                        .mapToDouble(DangerSignal::severity)
                        .max()
                        .orElse(0.0),
                corridor(revision, (int) maximumX),
                List.of(),
                20.0F,
                20.0F,
                food,
                mainHand,
                offHand,
                entities,
                dangers
        );
    }

    private static CoreSkillFrame frameWithInventory(
            final long revision,
            final int food,
            final HeldItemSummary mainHand,
            final HeldItemSummary offHand,
            final List<InventoryItemSummary> inventory,
            final List<VisibleEntity> entities,
            final List<DangerSignal> dangers,
            final List<VisibleBlockFace> faces,
            final boolean onGround,
            final DimensionRef dimension,
            final PerceptionVec3 look
    ) {
        return new CoreSkillFrame(
                PLAYER_ID,
                dimension,
                revision,
                revision,
                new PerceptionVec3(0.5, 5.0, 0.5),
                new PerceptionVec3(0.5, 6.62, 0.5),
                look.normalized(),
                onGround,
                false,
                dangers.stream()
                        .mapToDouble(DangerSignal::severity)
                        .max()
                        .orElse(0.0),
                new LocalNavSnapshot(
                        dimension,
                        revision,
                        faces.isEmpty()
                            ? List.of()
                            : List.of(new ObservedVoxel(
                                new GridPos(0, 4, 0),
                                VoxelKind.AIR,
                                0.0,
                                revision
                            ))
                ),
                faces,
                20.0F,
                20.0F,
                food,
                inventory,
                mainHand,
                offHand,
                entities,
                dangers
        );
    }

    private static LocalNavSnapshot replaceEvidence(
            LocalNavSnapshot source,
            GridPos replaced,
            OccupancyEvidence occupancy,
            TopSupportAffordance support
    ) {
        return new LocalNavSnapshot(
                source.dimension(),
                source.revision(),
                source.observedVoxels().values().stream()
                        .map(voxel -> voxel.position().equals(replaced)
                                ? new ObservedVoxel(
                                    voxel.position(),
                                    voxel.kind(),
                                    voxel.danger(),
                                    voxel.observationRevision(),
                                    occupancy,
                                    support
                                )
                                : voxel)
                        .toList()
        );
    }

    private static CoreSkillFrame withNavigation(
            CoreSkillFrame source,
            LocalNavSnapshot navigation
    ) {
        return new CoreSkillFrame(
                source.playerId(),
                source.dimension(),
                source.gameTime(),
                source.observationRevision(),
                source.position(),
                source.eyePosition(),
                source.lookDirection(),
                source.onGround(),
                source.inWater(),
                source.danger(),
                navigation,
                source.visibleBlockFaces(),
                source.health(),
                source.maxHealth(),
                source.foodLevel(),
                source.inventory(),
                source.mainHand(),
                source.offHand(),
                source.visibleEntities(),
                source.dangerSignals()
        );
    }

    private static CoreSkillFrame withGameTime(
            final CoreSkillFrame source,
            final long gameTime
    ) {
        return new CoreSkillFrame(
                source.playerId(),
                source.dimension(),
                gameTime,
                source.observationRevision(),
                source.position(),
                source.eyePosition(),
                source.lookDirection(),
                source.onGround(),
                source.inWater(),
                source.danger(),
                source.navigation(),
                source.visibleBlockFaces(),
                source.health(),
                source.maxHealth(),
                source.foodLevel(),
                source.inventory(),
                source.mainHand(),
                source.offHand(),
                source.visibleEntities(),
                source.dangerSignals()
        );
    }

    private static CoreSkillFrame withHealth(
            final CoreSkillFrame source,
            final float health
    ) {
        return new CoreSkillFrame(
                source.playerId(),
                source.dimension(),
                source.gameTime(),
                source.observationRevision(),
                source.position(),
                source.eyePosition(),
                source.lookDirection(),
                source.onGround(),
                source.inWater(),
                source.danger(),
                source.navigation(),
                source.visibleBlockFaces(),
                health,
                source.maxHealth(),
                source.foodLevel(),
                source.inventory(),
                source.mainHand(),
                source.offHand(),
                source.visibleEntities(),
                source.dangerSignals()
        );
    }

    private static final class RecordingEquipment
            implements EmergencyEquipmentActuator {
        private final List<String> requests = new ArrayList<>();
        private ActionOutcome outcome = ActionOutcome.COMPLETED;

        @Override
        public ActionOutcome equip(
                final ActionHand hand,
                final String itemId
        ) {
            requests.add(hand.name() + "=" + itemId);
            return outcome;
        }
    }

    private static final class RecordingMelee
            implements EmergencyMeleeActuator {
        private final List<UUID> targets = new ArrayList<>();
        private double cooldown = 1.0;
        private ActionOutcome outcome = ActionOutcome.DISPATCHED;

        @Override
        public OptionalDouble attackStrengthScale() {
            return OptionalDouble.of(cooldown);
        }

        @Override
        public ActionOutcome attack(final UUID entityId) {
            targets.add(entityId);
            return outcome;
        }
    }
}
