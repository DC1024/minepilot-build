package dev.mcai.companion.skills.transport;

import static dev.mcai.companion.skills.transport.BoatTransportTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.transport.BoatTransportTestFixtures.SEQUENCE;
import static dev.mcai.companion.skills.transport.BoatTransportTestFixtures.SESSION;
import static dev.mcai.companion.skills.transport.BoatTransportTestFixtures.BOAT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BoatTravelToSkillTest {
    @Test
    void steersTowardTargetAndNeverPublishesBoatUuid() {
        BoatState state = BoatTransportTestFixtures.boat(
                0.0,
                0.0,
                0.0F,
                0.0,
                false,
                false
        );
        BoatTransportTestFixtures.MutableFrames frames =
                new BoatTransportTestFixtures.MutableFrames(
                        BoatTransportTestFixtures.mounted(state)
                );
        BoatTransportTestFixtures.RecordingActuator actuator =
                new BoatTransportTestFixtures.RecordingActuator();
        BoatTravelToSkill skill = new BoatTravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                BoatSkillPolicy.defaults()
        );
        BoatTravelToParameters parameters = parameters(
                0.0,
                20.0,
                false
        );

        assertTrue(skill.preconditions(context(1), parameters).isEmpty());
        skill.start(context(1), parameters);
        SkillTickResult tick = skill.tick(context(2), parameters);

        assertEquals(SkillTickResult.Status.RUNNING, tick.status());
        assertEquals(
                List.of(BoatControlIntent.forwardIntent()),
                actuator.controls
        );
        assertFalse(
                skill.checkpoint(context(2), parameters)
                        .payload()
                        .contains(
                                BoatTransportTestFixtures.BOAT_ID.toString()
                        )
        );
    }

    @Test
    void turnsBeforePaddlingWhenTargetIsOutsideForwardArc() {
        BoatState state = BoatTransportTestFixtures.boat(
                0.0,
                0.0,
                0.0F,
                0.0,
                false,
                false
        );
        BoatControlIntent steering =
                BoatTravelToSkill.defaultSteering(
                        parameters(20.0, 0.0, false),
                        state
                );

        assertTrue(steering.left());
        assertFalse(steering.forward());
        assertFalse(steering.right());
    }

    @Test
    void brakesThenCompletesWithoutDismount() {
        BoatTransportTestFixtures.MutableFrames frames =
                new BoatTransportTestFixtures.MutableFrames(
                        BoatTransportTestFixtures.mounted(
                                BoatTransportTestFixtures.boat(
                                        0.0,
                                        0.0,
                                        0.0F,
                                        0.0,
                                        false,
                                        false
                                )
                        )
                );
        BoatTransportTestFixtures.RecordingActuator actuator =
                new BoatTransportTestFixtures.RecordingActuator();
        BoatTravelToSkill skill = new BoatTravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                BoatSkillPolicy.defaults()
        );
        BoatTravelToParameters parameters = parameters(
                0.0,
                0.0,
                false
        );
        skill.start(context(1), parameters);

        SkillTickResult result = skill.tick(context(2), parameters);

        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
        assertEquals(
                BoatControlIntent.NEUTRAL,
                actuator.controls.getFirst()
        );
        assertEquals(
                List.of(BoatTransportTestFixtures.BOAT_ID),
                actuator.stopped
        );
        assertTrue(actuator.dismounted.isEmpty());
    }

    @Test
    void predictivelyBrakesWithVanillaReverseInputBeforeRadius() {
        BoatState movingTowardTarget = new BoatState(
                BOAT_ID,
                new PerceptionVec3(0.0, 63.0, 3.3),
                0.0F,
                new PerceptionVec3(0.0, 0.0, 0.2),
                false,
                false
        );
        BoatTransportTestFixtures.MutableFrames frames =
                new BoatTransportTestFixtures.MutableFrames(
                        BoatTransportTestFixtures.mounted(
                                movingTowardTarget
                        )
                );
        BoatTransportTestFixtures.RecordingActuator actuator =
                new BoatTransportTestFixtures.RecordingActuator();
        BoatTravelToSkill skill = new BoatTravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                BoatSkillPolicy.defaults()
        );
        BoatTravelToParameters parameters = parameters(
                0.0,
                5.0,
                false
        );
        skill.start(context(1), parameters);

        SkillTickResult result = skill.tick(
                context(2),
                parameters
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                result.status()
        );
        assertEquals(
                BoatControlIntent.backwardIntent(),
                actuator.controls.getFirst()
        );
    }

    @Test
    void driftOutsideArrivalRadiusResumesCruising() {
        BoatState initiallyInside = new BoatState(
                BOAT_ID,
                new PerceptionVec3(0.0, 63.0, 0.0),
                0.0F,
                new PerceptionVec3(0.0, 0.0, 0.2),
                false,
                false
        );
        BoatTransportTestFixtures.MutableFrames frames =
                new BoatTransportTestFixtures.MutableFrames(
                        BoatTransportTestFixtures.mounted(
                                initiallyInside
                        )
                );
        BoatTransportTestFixtures.RecordingActuator actuator =
                new BoatTransportTestFixtures.RecordingActuator();
        BoatTravelToSkill skill = new BoatTravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                BoatSkillPolicy.defaults()
        );
        BoatTravelToParameters parameters = parameters(
                0.0,
                0.0,
                false
        );
        skill.start(context(1), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2), parameters).status()
        );

        frames.frame = BoatTransportTestFixtures.mounted(
                new BoatState(
                        BOAT_ID,
                        new PerceptionVec3(0.0, 63.0, 1.6),
                        0.0F,
                        new PerceptionVec3(0.0, 0.0, 0.0),
                        false,
                        false
                )
        );
        SkillTickResult result = skill.tick(
                context(3),
                parameters
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                result.status()
        );
        assertTrue(
                skill.checkpoint(context(3), parameters)
                        .payload()
                        .contains("\"phase\":\"CRUISING\"")
        );
        assertEquals(
                List.of(BOAT_ID),
                actuator.stopped
        );
    }

    @Test
    void dismountRequiresObservedSafeSurface() {
        BoatState state = BoatTransportTestFixtures.boat(
                0.0,
                0.0,
                0.0F,
                0.0,
                false,
                false
        );
        BoatTransportTestFixtures.MutableFrames frames =
                new BoatTransportTestFixtures.MutableFrames(
                        BoatTransportTestFixtures.frame(
                                100,
                                100,
                                SEQUENCE,
                                SESSION,
                                0.0,
                                List.of(),
                                List.of(
                                        BoatTransportTestFixtures.safeBank()
                                ),
                                Optional.of(state)
                        )
                );
        BoatTransportTestFixtures.RecordingActuator actuator =
                new BoatTransportTestFixtures.RecordingActuator();
        BoatTravelToSkill skill = new BoatTravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                BoatSkillPolicy.defaults()
        );
        BoatTravelToParameters parameters = parameters(
                0.0,
                0.0,
                true
        );
        skill.start(context(1), parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2), parameters).status()
        );
        assertEquals(
                List.of(BoatTransportTestFixtures.BOAT_ID),
                actuator.dismounted
        );
        frames.frame = BoatTransportTestFixtures.frame(
                101,
                101,
                SEQUENCE + 1,
                SESSION,
                0.0,
                List.of(),
                List.of(),
                Optional.empty()
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(3), parameters).status()
        );
    }

    @Test
    void refusesHazardousOrUnobservedDismount() {
        BoatState state = BoatTransportTestFixtures.boat(
                0.0,
                0.0,
                0.0F,
                0.0,
                false,
                false
        );
        BoatSkillFrame waterOnly = BoatTransportTestFixtures.frame(
                100,
                100,
                SEQUENCE,
                SESSION,
                0.0,
                List.of(),
                List.of(new dev.mcai.companion.perception.VisibleBlockFace(
                        new dev.mcai.companion.perception.BlockCoordinate(
                                0,
                                62,
                                0
                        ),
                        "minecraft:water",
                        "up",
                        new dev.mcai.companion.perception.PerceptionVec3(
                                0.5,
                                63.0,
                                0.5
                        ),
                        1.0,
                        dev.mcai.companion.perception.PerceptionProvenance
                                .BLOCK_SURFACE_RAY_CLIP
                )),
                Optional.of(state)
        );

        assertFalse(waterOnly.hasObservedSafeDismountSurface(5.0));
    }

    @Test
    void observedDangerFailsClosedAndReleasesPaddles() {
        BoatState state = BoatTransportTestFixtures.boat(
                0.0,
                0.0,
                0.0F,
                0.0,
                false,
                false
        );
        BoatTransportTestFixtures.MutableFrames frames =
                new BoatTransportTestFixtures.MutableFrames(
                        BoatTransportTestFixtures.mounted(state)
                );
        BoatTransportTestFixtures.RecordingActuator actuator =
                new BoatTransportTestFixtures.RecordingActuator();
        BoatTravelToSkill skill = new BoatTravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                BoatSkillPolicy.defaults()
        );
        BoatTravelToParameters parameters = parameters(
                0.0,
                20.0,
                false
        );
        skill.start(context(1), parameters);
        frames.frame = BoatTransportTestFixtures.frame(
                101,
                101,
                SEQUENCE + 1,
                SESSION,
                0.95,
                List.of(),
                List.of(),
                Optional.of(state)
        );

        SkillTickResult result = skill.tick(context(2), parameters);

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "boat_travel_to.danger_observed",
                result.failure().orElseThrow().code()
        );
        assertEquals(
                List.of(BoatTransportTestFixtures.BOAT_ID),
                actuator.stopped
        );
    }

    @Test
    void collisionTriggersBoundedReverseRecovery() {
        BoatState colliding = BoatTransportTestFixtures.boat(
                0.0,
                0.0,
                0.0F,
                0.0,
                true,
                false
        );
        BoatTransportTestFixtures.MutableFrames frames =
                new BoatTransportTestFixtures.MutableFrames(
                        BoatTransportTestFixtures.mounted(colliding)
                );
        BoatTransportTestFixtures.RecordingActuator actuator =
                new BoatTransportTestFixtures.RecordingActuator();
        BoatTravelToSkill skill = new BoatTravelToSkill(
                PLAYER_ID,
                actuator,
                frames,
                BoatSkillPolicy.defaults()
        );
        BoatTravelToParameters parameters = parameters(
                0.0,
                20.0,
                false
        );
        skill.start(context(1), parameters);

        skill.tick(context(2), parameters);
        skill.tick(context(3), parameters);
        skill.tick(context(4), parameters);

        BoatControlIntent recovery = actuator.controls.getLast();
        assertTrue(recovery.backward());
        assertTrue(recovery.left() || recovery.right());
    }

    private static BoatTravelToParameters parameters(
            double x,
            double z,
            boolean dismount
    ) {
        return new BoatTravelToParameters(
                DimensionRef.OVERWORLD,
                x,
                63.0,
                z,
                1.5,
                400,
                dismount
        );
    }

    private static SkillContext context(long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }
}
