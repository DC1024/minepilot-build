package dev.mcai.companion.skills.transport;

import static dev.mcai.companion.skills.transport.BoatTransportTestFixtures.BOAT_ID;
import static dev.mcai.companion.skills.transport.BoatTransportTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.transport.BoatTransportTestFixtures.SEQUENCE;
import static dev.mcai.companion.skills.transport.BoatTransportTestFixtures.SESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class EnterObservedBoatSkillTest {
    @Test
    void bindsObservedIdInternallyAndMountsThroughActuator() {
        BoatTransportTestFixtures.MutableFrames frames =
                new BoatTransportTestFixtures.MutableFrames(
                        unmounted(SEQUENCE, List.of(
                                BoatTransportTestFixtures.visibleBoat()
                        ))
                );
        BoatTransportTestFixtures.RecordingActuator actuator =
                new BoatTransportTestFixtures.RecordingActuator();
        EnterObservedBoatSkill skill = new EnterObservedBoatSkill(
                PLAYER_ID,
                actuator,
                frames,
                BoatSkillPolicy.defaults()
        );
        EnterObservedBoatParameters parameters =
                new EnterObservedBoatParameters(
                        DimensionRef.OVERWORLD,
                        SEQUENCE,
                        "visible-0"
                );

        assertTrue(skill.preconditions(context(1), parameters).isEmpty());
        skill.start(context(1), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2), parameters).status()
        );
        assertEquals(List.of(BOAT_ID), actuator.entered);
        assertFalse(
                skill.checkpoint(context(2), parameters)
                        .payload()
                        .contains(BOAT_ID.toString())
        );

        frames.frame = BoatTransportTestFixtures.mounted(
                BoatTransportTestFixtures.boat(
                        0.0,
                        0.0,
                        0.0F,
                        0.0,
                        false,
                        false
                )
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(3), parameters).status()
        );
    }

    @Test
    void rejectsStaleForgedAndNonBoatReferences() {
        BoatTransportTestFixtures.MutableFrames frames =
                new BoatTransportTestFixtures.MutableFrames(
                        unmounted(SEQUENCE, List.of(
                                BoatTransportTestFixtures.visibleBoat()
                        ))
                );
        EnterObservedBoatSkill skill = new EnterObservedBoatSkill(
                PLAYER_ID,
                new BoatTransportTestFixtures.RecordingActuator(),
                frames,
                BoatSkillPolicy.defaults()
        );

        assertEquals(
                "enter_observed_boat.observation_expired",
                skill.preconditions(
                        context(1),
                        new EnterObservedBoatParameters(
                                DimensionRef.OVERWORLD,
                                SEQUENCE - 1,
                                "visible-0"
                        )
                ).orElseThrow().code()
        );
        assertEquals(
                "enter_observed_boat.target_not_visible",
                skill.preconditions(
                        context(1),
                        new EnterObservedBoatParameters(
                                DimensionRef.OVERWORLD,
                                SEQUENCE,
                                "visible-8"
                        )
                ).orElseThrow().code()
        );

        VisibleEntity zombie = new VisibleEntity(
                BOAT_ID,
                "minecraft:zombie",
                BoatTransportTestFixtures.visibleBoat().position(),
                BoatTransportTestFixtures.visibleBoat()
                        .relativePosition(),
                2.0,
                true,
                false,
                dev.mcai.companion.perception.PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        frames.frame = unmounted(SEQUENCE, List.of(zombie));
        assertEquals(
                "enter_observed_boat.target_not_boat",
                skill.preconditions(
                        context(1),
                        new EnterObservedBoatParameters(
                                DimensionRef.OVERWORLD,
                                SEQUENCE,
                                "visible-0"
                        )
                ).orElseThrow().code()
        );
    }

    @Test
    void sessionReplacementFailsClosed() {
        BoatTransportTestFixtures.MutableFrames frames =
                new BoatTransportTestFixtures.MutableFrames(
                        unmounted(SEQUENCE, List.of(
                                BoatTransportTestFixtures.visibleBoat()
                        ))
                );
        BoatTransportTestFixtures.RecordingActuator actuator =
                new BoatTransportTestFixtures.RecordingActuator();
        EnterObservedBoatSkill skill = new EnterObservedBoatSkill(
                PLAYER_ID,
                actuator,
                frames,
                BoatSkillPolicy.defaults()
        );
        EnterObservedBoatParameters parameters =
                new EnterObservedBoatParameters(
                        DimensionRef.OVERWORLD,
                        SEQUENCE,
                        "visible-0"
                );
        skill.start(context(1), parameters);
        actuator.session++;

        assertEquals(
                "enter_observed_boat.session_mismatch",
                skill.tick(context(2), parameters)
                        .failure()
                        .orElseThrow()
                        .code()
        );
    }

    private static BoatSkillFrame unmounted(
            long sequence,
            List<VisibleEntity> entities
    ) {
        return BoatTransportTestFixtures.frame(
                100,
                100,
                sequence,
                SESSION,
                0.0,
                entities,
                List.of(),
                Optional.empty()
        );
    }

    private static SkillContext context(long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }
}
