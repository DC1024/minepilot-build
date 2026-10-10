package dev.mcai.companion.skills.portal;

import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.frame;
import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.parameters;
import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.withPortalHit;
import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.withPortalProgress;
import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.withVisiblePortalBlock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class EnterObservedPortalSkillTest {
    @Test
    void netherPortalCompletesOnlyAfterActualDimensionChange() {
        var source = frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                1,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.NETHER_PORTAL
        );
        var frames = new PortalSkillTestFixtures.MutableFrames(source);
        var actuator = new PortalSkillTestFixtures.RecordingActuator();
        var traversal = new PortalTraversalBuffer();
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                actuator,
                frames,
                PortalSkillPolicy.defaults(),
                traversal
        );
        var parameters = parameters(
                DimensionRef.OVERWORLD,
                Optional.of(DimensionRef.NETHER)
        );

        assertTrue(skill.preconditions(context(0), parameters).isEmpty());
        skill.start(context(0), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(0), parameters).status()
        );
        assertFalse(traversal.latest().isPresent());

        frames.frame = frame(
                DimensionRef.NETHER,
                DimensionRef.OVERWORLD,
                1,
                10,
                1,
                new PerceptionVec3(12.5, 70.0, -4.5),
                PortalKind.NETHER_PORTAL
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(1), parameters).status()
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                skill.result(context(1), parameters).status()
        );
        PortalTraversalResult result = traversal.latest().orElseThrow();
        assertEquals(DimensionRef.OVERWORLD, result.sourceDimension());
        assertEquals(DimensionRef.NETHER, result.destinationDimension());
        assertEquals(
                new PerceptionVec3(12.5, 70.0, -4.5),
                result.destinationPosition()
        );
    }

    @Test
    void endPortalRecordsTheRealEndArrival() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                4,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.END_PORTAL
        ));
        var traversal = new PortalTraversalBuffer();
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames,
                PortalSkillPolicy.defaults(),
                traversal
        );
        var parameters = parameters(
                DimensionRef.OVERWORLD,
                Optional.of(DimensionRef.END)
        );

        skill.start(context(0), parameters);
        skill.tick(context(0), parameters);
        frames.frame = frame(
                DimensionRef.END,
                DimensionRef.OVERWORLD,
                1,
                10,
                4,
                new PerceptionVec3(100.5, 49.0, 0.5),
                PortalKind.END_PORTAL
        );

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(1), parameters).status()
        );
        assertEquals(
                DimensionRef.END,
                traversal.latest()
                        .orElseThrow()
                        .destinationDimension()
        );
    }

    @Test
    void endPortalCommitsBeforeVanillasInstantCollisionTransition() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.END,
                DimensionRef.END,
                0,
                10,
                4,
                new PerceptionVec3(0.62, 65.0, 0.5),
                PortalKind.END_PORTAL
        ));
        var traversal = new PortalTraversalBuffer();
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames,
                PortalSkillPolicy.defaults(),
                traversal
        );
        var parameters = parameters(
                DimensionRef.END,
                Optional.of(DimensionRef.OVERWORLD)
        );

        skill.start(context(0), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(0), parameters).status()
        );
        frames.frame = frame(
                DimensionRef.OVERWORLD,
                DimensionRef.END,
                1,
                10,
                4,
                new PerceptionVec3(20.5, 70.0, 20.5),
                PortalKind.END_PORTAL
        );

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(1), parameters).status()
        );
        assertEquals(
                DimensionRef.OVERWORLD,
                traversal.latest().orElseThrow().destinationDimension()
        );
    }

    @Test
    void endPortalPrecommitsOneNormalPulseBeforeInstantTransition() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.END,
                DimensionRef.END,
                0,
                10,
                4,
                new PerceptionVec3(0.35, 64.0, 0.5),
                PortalKind.END_PORTAL
        ));
        var traversal = new PortalTraversalBuffer();
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames,
                PortalSkillPolicy.defaults(),
                traversal
        );
        var parameters = parameters(
                DimensionRef.END,
                Optional.of(DimensionRef.OVERWORLD)
        );

        skill.start(context(0), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(0), parameters).status()
        );
        frames.frame = frame(
                DimensionRef.OVERWORLD,
                DimensionRef.END,
                1,
                10,
                4,
                new PerceptionVec3(20.5, 70.0, 20.5),
                PortalKind.END_PORTAL
        );

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(1), parameters).status()
        );
        assertEquals(
                DimensionRef.OVERWORLD,
                traversal.latest().orElseThrow().destinationDimension()
        );
    }

    @Test
    void committedEndPortalToleratesABoundedMissingFrameDuringTransfer() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                4,
                new PerceptionVec3(0.62, 64.0, 0.5),
                PortalKind.END_PORTAL
        ));
        var traversal = new PortalTraversalBuffer();
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames,
                PortalSkillPolicy.defaults(),
                traversal
        );
        var parameters = parameters(
                DimensionRef.OVERWORLD,
                Optional.of(DimensionRef.END)
        );

        skill.start(context(0), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(0), parameters).status()
        );
        frames.frame = null;
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(1), parameters).status()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2), parameters).status()
        );

        frames.frame = frame(
                DimensionRef.END,
                DimensionRef.OVERWORLD,
                3,
                10,
                4,
                new PerceptionVec3(100.5, 49.0, 0.5),
                PortalKind.END_PORTAL
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(3), parameters).status()
        );
        assertEquals(
                DimensionRef.END,
                traversal.latest().orElseThrow().destinationDimension()
        );
    }

    @Test
    void missingFrameBeforePortalCommitStillFailsClosed() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                4,
                new PerceptionVec3(-2.5, 64.0, 0.5),
                PortalKind.END_PORTAL
        ));
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames
        );
        var parameters = parameters(
                DimensionRef.OVERWORLD,
                Optional.of(DimensionRef.END)
        );

        skill.start(context(0), parameters);
        frames.frame = null;
        SkillTickResult missing = skill.tick(context(1), parameters);

        assertEquals(SkillTickResult.Status.FAILED, missing.status());
        assertEquals(
                "enter_observed_portal.observation_unavailable",
                missing.failure().orElseThrow().code()
        );
    }

    @Test
    void committedMissingFrameWindowRemainsBounded() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                4,
                new PerceptionVec3(0.62, 64.0, 0.5),
                PortalKind.END_PORTAL
        ));
        var policy = new PortalSkillPolicy(
                30,
                8.0,
                0.75,
                0.45,
                1,
                2,
                20,
                6,
                1,
                2,
                0,
                14.0,
                0.62,
                2.25,
                8.0
        );
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames,
                policy,
                PortalTraversalObserver.NOOP
        );
        var parameters = parameters(
                DimensionRef.OVERWORLD,
                Optional.of(DimensionRef.END)
        );

        skill.start(context(0), parameters);
        skill.tick(context(0), parameters);
        frames.frame = null;
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(1), parameters).status()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2), parameters).status()
        );
        SkillTickResult expired = skill.tick(context(3), parameters);
        assertEquals(SkillTickResult.Status.FAILED, expired.status());
        assertEquals(
                "enter_observed_portal.committed_observation_unavailable",
                expired.failure().orElseThrow().code()
        );
    }

    @Test
    void endPortalRejectsAnUnrelatedDimensionChangeBeforeCommit() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.END,
                DimensionRef.END,
                0,
                10,
                4,
                new PerceptionVec3(-2.5, 64.0, 0.5),
                PortalKind.END_PORTAL
        ));
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames
        );
        var parameters = parameters(
                DimensionRef.END,
                Optional.of(DimensionRef.OVERWORLD)
        );

        skill.start(context(0), parameters);
        skill.tick(context(0), parameters);
        frames.frame = frame(
                DimensionRef.OVERWORLD,
                DimensionRef.END,
                1,
                10,
                4,
                new PerceptionVec3(20.5, 70.0, 20.5),
                PortalKind.END_PORTAL
        );

        SkillTickResult changed = skill.tick(context(1), parameters);
        assertEquals(SkillTickResult.Status.FAILED, changed.status());
        assertEquals(
                "enter_observed_portal.dimension_changed_before_entry",
                changed.failure().orElseThrow().code()
        );
        assertTrue(skill.traversalResult().isEmpty());
    }

    @Test
    void endGatewayUsesRealSameDimensionDisplacement() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.END,
                DimensionRef.END,
                0,
                10,
                7,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.END_GATEWAY
        ));
        var traversal = new PortalTraversalBuffer();
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames,
                PortalSkillPolicy.defaults(),
                traversal
        );
        var parameters = parameters(
                DimensionRef.END,
                Optional.of(DimensionRef.END)
        );

        skill.start(context(0), parameters);
        skill.tick(context(0), parameters);
        frames.frame = frame(
                DimensionRef.END,
                DimensionRef.END,
                1,
                10,
                7,
                new PerceptionVec3(1_025.5, 70.0, 0.5),
                PortalKind.END_GATEWAY
        );

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(1), parameters).status()
        );
        PortalTraversalResult result = traversal.latest().orElseThrow();
        assertEquals(result.sourceDimension(), result.destinationDimension());
        assertTrue(
                result.destinationPosition()
                        .subtract(result.sourcePosition())
                        .length() > 1_000.0
        );
    }

    @Test
    void rejectsAParameterDimensionThatDoesNotMatchTheObservation() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                1,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.NETHER_PORTAL
        ));
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames
        );
        var mismatch = parameters(
                DimensionRef.NETHER,
                Optional.of(DimensionRef.OVERWORLD)
        );

        assertEquals(
                "enter_observed_portal.dimension_mismatch",
                skill.preconditions(context(0), mismatch)
                        .orElseThrow()
                        .code()
        );
    }

    @Test
    void sessionSwitchFailsWithoutReportingATraversal() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                1,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.NETHER_PORTAL
        ));
        var traversal = new PortalTraversalBuffer();
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames,
                PortalSkillPolicy.defaults(),
                traversal
        );
        var parameters = parameters(
                DimensionRef.OVERWORLD,
                Optional.empty()
        );

        skill.start(context(0), parameters);
        frames.frame = frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                1,
                10,
                2,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.NETHER_PORTAL
        );

        SkillTickResult tick = skill.tick(context(1), parameters);
        assertEquals(SkillTickResult.Status.FAILED, tick.status());
        assertEquals(
                "enter_observed_portal.session_mismatch",
                tick.failure().orElseThrow().code()
        );
        assertTrue(traversal.latest().isEmpty());
    }

    @Test
    void portalWaitHasABoundedTimeout() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                1,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.NETHER_PORTAL
        ));
        var policy = new PortalSkillPolicy(
                30,
                8.0,
                0.75,
                0.45,
                1,
                2,
                4,
                2,
                1,
                1,
                0,
                14.0,
                0.62,
                2.25,
                8.0
        );
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames,
                policy,
                PortalTraversalObserver.NOOP
        );
        var parameters = parameters(
                DimensionRef.OVERWORLD,
                Optional.empty()
        );

        skill.start(context(0), parameters);
        skill.tick(context(0), parameters);
        frames.frame = withPortalProgress(frames.frame, 1, 1);
        skill.tick(context(1), parameters);
        frames.frame = withPortalProgress(frames.frame, 3, 3);
        SkillTickResult timeout = skill.tick(context(3), parameters);

        assertEquals(SkillTickResult.Status.FAILED, timeout.status());
        assertEquals(
                "enter_observed_portal.portal_wait_timeout",
            timeout.failure().orElseThrow().code()
        );
    }

    @Test
    void vanillaPortalChargeIsReportedAsBoundedProgress() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                1,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.NETHER_PORTAL
        ));
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames
        );
        var parameters = parameters(
                DimensionRef.OVERWORLD,
                Optional.of(DimensionRef.NETHER)
        );

        skill.start(context(0), parameters);
        assertTrue(skill.tick(context(0), parameters).madeProgress());

        frames.frame = withPortalProgress(frames.frame, 1, 0);
        assertTrue(skill.tick(context(1), parameters).madeProgress());
        for (int tick = 2; tick <= 80; tick++) {
            frames.frame = withPortalProgress(
                    frames.frame,
                    tick,
                    tick - 1
            );
            SkillTickResult charging =
                    skill.tick(context(tick), parameters);
            assertEquals(
                    SkillTickResult.Status.RUNNING,
                    charging.status()
            );
            assertTrue(
                    charging.madeProgress(),
                    "Vanilla portal charge tick was hidden at " + tick
            );
        }

        frames.frame = frame(
                DimensionRef.NETHER,
                DimensionRef.OVERWORLD,
                81,
                10,
                1,
                new PerceptionVec3(12.5, 70.0, -4.5),
                PortalKind.NETHER_PORTAL
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(81), parameters).status()
        );
    }

    @Test
    void offCenterPortalRaySteersTowardTheObservedCellCenter() {
        PortalSkillFrame initial = frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                1,
                new PerceptionVec3(0.5, 64.0, -1.3),
                PortalKind.NETHER_PORTAL
        );
        /*
         * Portal semantic samples are produced by ray marching because a
         * portal has no ordinary selectable face. The fair hit can therefore
         * be materially off-center. The body's look direction below is
         * already aligned to that hit but intentionally not to block center.
         * Entry must steer toward the proven cell center so the player's
         * collision box cannot be guided into the adjacent frame.
         */
        PerceptionVec3 hit = new PerceptionVec3(1.95, 64.5, 0.38);
        PerceptionVec3 horizontal = hit.subtract(initial.position());
        double length = Math.hypot(horizontal.x(), horizontal.z());
        PerceptionVec3 look = new PerceptionVec3(
                horizontal.x() / length,
                0.0,
                horizontal.z() / length
        );
        initial = withPortalHit(
                new PortalSkillFrame(
                        initial.playerId(),
                        initial.currentDimension(),
                        initial.observedDimension(),
                        initial.serverTick(),
                        initial.observedAtServerTick(),
                        initial.observationRevision(),
                        initial.sessionGeneration(),
                        initial.position(),
                        initial.eyePosition(),
                        look,
                        initial.onGround(),
                        initial.inWater(),
                        false,
                        0,
                        Optional.empty(),
                        initial.danger(),
                        initial.visibleBlockFaces()
                ),
                hit
        );
        var frames = new PortalSkillTestFixtures.MutableFrames(initial);
        var actuator = new PortalSkillTestFixtures.RecordingActuator();
        var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                actuator,
                frames
        );
        var parameters = parameters(
                DimensionRef.OVERWORLD,
                Optional.empty()
        );

        skill.start(context(0), parameters);
        SkillTickResult tick = skill.tick(context(0), parameters);

        assertEquals(SkillTickResult.Status.RUNNING, tick.status());
        assertEquals(
                -29.054605F,
                actuator.looks.getLast().yawDegrees(),
                1.0E-4F
        );
        assertFalse(actuator.movements.isEmpty());
        assertEquals(0, actuator.stops);
    }

    @Test
    void upperPortalRayDoesNotTriggerAnImmediateRecoveryJump() {
        final PortalSkillFrame initial = frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                1,
                new PerceptionVec3(0.5, 63.0, 0.5),
                PortalKind.NETHER_PORTAL
        );
        final var frames =
                new PortalSkillTestFixtures.MutableFrames(initial);
        final var actuator =
                new PortalSkillTestFixtures.RecordingActuator();
        final var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                actuator,
                frames
        );
        final var parameters = parameters(
                DimensionRef.OVERWORLD,
                Optional.empty()
        );

        skill.start(context(0), parameters);
        final SkillTickResult tick =
                skill.tick(context(0), parameters);

        assertEquals(SkillTickResult.Status.RUNNING, tick.status());
        assertEquals(
                0,
                actuator.jumps,
                "An upper block of a vertical portal is an observation "
                    + "anchor, not a ledge"
        );
    }

    @Test
    void stuckNetherApproachCrouchesBeforeTryingAJump() {
        final PortalSkillFrame initial = frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                1,
                new PerceptionVec3(-2.5, 64.0, 0.5),
                PortalKind.NETHER_PORTAL
        );
        final var frames =
                new PortalSkillTestFixtures.MutableFrames(initial);
        final var actuator =
                new PortalSkillTestFixtures.RecordingActuator();
        final var policy = new PortalSkillPolicy(
                30,
                8.0,
                0.75,
                0.45,
                10,
                10,
                40,
                20,
                2,
                2,
                1,
                14.0,
                0.62,
                2.25,
                8.0
        );
        final var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                actuator,
                frames,
                policy,
                PortalTraversalObserver.NOOP
        );
        final var parameters = parameters(
                DimensionRef.OVERWORLD,
                Optional.empty()
        );
        skill.start(context(0), parameters);

        skill.tick(context(0), parameters);
        skill.tick(context(1), parameters);
        skill.tick(context(2), parameters);
        final SkillTickResult crouching =
                skill.tick(context(3), parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                crouching.status()
        );
        assertTrue(crouching.madeProgress());
        assertTrue(
                actuator.movements.getLast().sneak(),
                "A blocked Nether approach must lower the vanilla pose "
                    + "before spending a recovery jump"
        );
        assertEquals(0, actuator.jumps);

        skill.tick(context(4), parameters);
        skill.tick(context(5), parameters);
        skill.tick(context(6), parameters);
        assertEquals(
                1,
                actuator.jumps,
                "A crouched approach that still makes no positional "
                    + "progress must retain the bounded jump fallback"
        );
        assertFalse(actuator.movements.getLast().sneak());
    }

    @Test
    void approachingMayReacquireAnotherFaceOfTheSameNetherPortal() {
        final PortalSkillFrame initial = frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                1,
                new PerceptionVec3(0.5, 64.0, -1.3),
                PortalKind.NETHER_PORTAL
        );
        final var frames =
                new PortalSkillTestFixtures.MutableFrames(initial);
        final var skill = new EnterObservedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames
        );
        final var parameters = parameters(
                DimensionRef.OVERWORLD,
                Optional.empty()
        );
        skill.start(context(0), parameters);

        frames.frame = withVisiblePortalBlock(
                initial,
                new BlockCoordinate(2, 65, 0)
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(1), parameters).status()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(12), parameters).status(),
                "A face change inside the same bounded portal must not "
                    + "consume the target-lost grace period"
        );
    }

    private static SkillContext context(long tick) {
        return new SkillContext(1, 1, tick, true, true, 0.0);
    }
}
