package dev.mcai.companion.skills.portal;

import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import org.junit.jupiter.api.Test;

final class FindAndEnterObservedPortalSkillTest {
    @Test
    void scansThenBindsCurrentFaceAndCompletesOnlyAfterReturn() {
        final PortalSkillFrame hidden = withoutFaces(frame(
                DimensionRef.END,
                DimensionRef.END,
                0,
                10,
                7,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.END_PORTAL
        ));
        final var frames =
                new PortalSkillTestFixtures.MutableFrames(hidden);
        final var actuator =
                new PortalSkillTestFixtures.RecordingActuator();
        final var traversal = new PortalTraversalBuffer();
        final var skill = new FindAndEnterObservedPortalSkill(
                PLAYER_ID,
                actuator,
                frames,
                PortalSkillPolicy.defaults(),
                traversal
        );

        assertTrue(skill.preconditions(
                context(0),
                NoParameters.INSTANCE
        ).isEmpty());
        skill.start(context(0), NoParameters.INSTANCE);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(0),
                        NoParameters.INSTANCE
                ).status()
        );
        assertFalse(actuator.looks.isEmpty());

        frames.frame = frame(
                DimensionRef.END,
                DimensionRef.END,
                1,
                11,
                7,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.END_PORTAL
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(1),
                        NoParameters.INSTANCE
                ).status()
        );
        assertTrue(
                skill.checkpoint(
                        context(1),
                        NoParameters.INSTANCE
                ).payload().contains("\"phase\":\"ENTERING\"")
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(2),
                        NoParameters.INSTANCE
                ).status()
        );
        assertFalse(actuator.movements.isEmpty());

        frames.frame = frame(
                DimensionRef.OVERWORLD,
                DimensionRef.END,
                3,
                11,
                7,
                new PerceptionVec3(0.5, 70.0, 0.5),
                PortalKind.END_PORTAL
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(
                        context(3),
                        NoParameters.INSTANCE
                ).status()
        );
        assertEquals(
                DimensionRef.OVERWORLD,
                traversal.latest()
                        .orElseThrow()
                        .destinationDimension()
        );
    }

    @Test
    void modelContractIsStrictlyParameterless() {
        final var skill = new FindAndEnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                new PortalSkillTestFixtures.MutableFrames(null),
                PortalSkillPolicy.defaults(),
                PortalTraversalObserver.NOOP
        );

        assertTrue(skill.parameters().parse(List.of())
                .value().isPresent());
        assertTrue(skill.parameters().parse(List.of(
                new SkillArgument("x", "1")
        )).value().isEmpty());
    }

    @Test
    void preservesTheChildFailureCodeWithoutLengthFallback() {
        final PortalSkillFrame initial = frame(
                DimensionRef.END,
                DimensionRef.END,
                0,
                10,
                7,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.END_PORTAL
        );
        final var frames =
                new PortalSkillTestFixtures.MutableFrames(initial);
        final var skill = new FindAndEnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames,
                PortalSkillPolicy.defaults(),
                PortalTraversalObserver.NOOP
        );

        skill.start(context(0), NoParameters.INSTANCE);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(0), NoParameters.INSTANCE).status()
        );
        frames.frame = frame(
                DimensionRef.END,
                DimensionRef.END,
                1,
                10,
                8,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.END_PORTAL
        );

        final SkillTickResult failed =
                skill.tick(context(1), NoParameters.INSTANCE);
        assertEquals(SkillTickResult.Status.FAILED, failed.status());
        assertEquals(
                "enter_observed_portal.session_mismatch",
                failed.failure().orElseThrow().code()
        );
    }

    private static PortalSkillFrame withoutFaces(
            final PortalSkillFrame frame
    ) {
        return new PortalSkillFrame(
                frame.playerId(),
                frame.currentDimension(),
                frame.observedDimension(),
                frame.serverTick(),
                frame.observedAtServerTick(),
                frame.observationRevision(),
                frame.sessionGeneration(),
                frame.position(),
                frame.eyePosition(),
                frame.lookDirection(),
                frame.onGround(),
                frame.inWater(),
                frame.portalProcessActive(),
                frame.portalProgressTicks(),
                frame.portalEntryBlock(),
                frame.danger(),
                List.of()
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(
                1,
                10,
                tick,
                true,
                true,
                0.0
        );
    }
}
