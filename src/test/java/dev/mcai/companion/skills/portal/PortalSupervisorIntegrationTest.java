package dev.mcai.companion.skills.portal;

import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.frame;
import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.withPortalProgress;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.DecisionEnvelope;
import dev.mcai.companion.model.DecisionKind;
import dev.mcai.companion.model.RequestedObservation;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skill.SkillCheckpointSink;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillRuntimePolicy;
import dev.mcai.companion.skill.SkillSupervisor;
import dev.mcai.companion.waypoint.DimensionRef;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class PortalSupervisorIntegrationTest {
    @Test
    void normalVanillaWarmupOutlivesAggressiveStallWindow() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                1,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.NETHER_PORTAL
        ));
        var registry = PortalSkills.registerAll(
                new SkillRegistry(),
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames
        );
        try (var supervisor = supervisor(registry, 3)) {
            assertTrue(
                    supervisor.start(decision(), context(0)).accepted()
            );
            assertEquals(
                    SkillSupervisor.State.RUNNING,
                    supervisor.tick(context(1)).state()
            );
            for (int tick = 2; tick <= 81; tick++) {
                frames.frame = withPortalProgress(
                        frames.frame,
                        tick,
                        Math.max(0, tick - 2)
                );
                assertEquals(
                        SkillSupervisor.State.RUNNING,
                        supervisor.tick(context(tick)).state(),
                    "Supervisor stalled during vanilla warmup tick "
                            + tick
                );
            }
            frames.frame = frame(
                    DimensionRef.NETHER,
                    DimensionRef.OVERWORLD,
                    82,
                    10,
                    1,
                    new PerceptionVec3(12.5, 70.0, -4.5),
                    PortalKind.NETHER_PORTAL
            );
            SkillSupervisor.Snapshot completed =
                    supervisor.tick(context(82));
            assertEquals(
                    SkillSupervisor.State.COMPLETED,
                    completed.state()
            );
            assertEquals(
                    SkillResult.Status.COMPLETED,
                    completed.terminalResult().orElseThrow().status()
            );
        }
    }

    @Test
    void chargeCannotRunForeverWithoutRealDimensionChange() {
        var frames = new PortalSkillTestFixtures.MutableFrames(frame(
                DimensionRef.OVERWORLD,
                DimensionRef.OVERWORLD,
                0,
                10,
                1,
                new PerceptionVec3(1.5, 64.0, 0.5),
                PortalKind.NETHER_PORTAL
        ));
        var portalPolicy = new PortalSkillPolicy(
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
        var registry = PortalSkills.registerAll(
                new SkillRegistry(),
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                frames,
                portalPolicy,
                PortalTraversalObserver.NOOP
        );
        try (var supervisor = supervisor(registry, 2)) {
            assertTrue(
                    supervisor.start(decision(), context(0)).accepted()
            );
            supervisor.tick(context(1));
            for (int tick = 2; tick <= 7; tick++) {
                frames.frame = withPortalProgress(
                        frames.frame,
                        tick,
                        tick - 1
                );
                assertEquals(
                        SkillSupervisor.State.RUNNING,
                    supervisor.tick(context(tick)).state()
                );
            }
            frames.frame = withPortalProgress(frames.frame, 8, 7);
            SkillSupervisor.Snapshot failed =
                    supervisor.tick(context(8));
            assertEquals(SkillSupervisor.State.FAILED, failed.state());
            assertEquals(
                    "enter_observed_portal.portal_wait_timeout",
                    failed.terminalResult()
                        .orElseThrow()
                        .failure()
                        .orElseThrow()
                        .code()
            );
        }
    }

    private static SkillSupervisor supervisor(
            SkillRegistry registry,
            int stallTicks
    ) {
        return new SkillSupervisor(
                registry,
                SkillCheckpointSink.discard(),
                new SkillRuntimePolicy(
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(1),
                        stallTicks,
                        0.5,
                        0.2
                )
        );
    }

    private static DecisionEnvelope decision() {
        return new DecisionEnvelope(
                "portal-supervisor-test",
                1,
                1,
                DecisionKind.START_SKILL,
                PortalSkills.ENTER_OBSERVED_PORTAL,
                List.of(
                        new SkillArgument(
                                "dimension",
                                DimensionRef.OVERWORLD.id()
                        ),
                        new SkillArgument("sampleSequence", "10"),
                        new SkillArgument("x", "1"),
                        new SkillArgument("y", "64"),
                        new SkillArgument("z", "0"),
                        new SkillArgument("face", "north"),
                        new SkillArgument(
                                "expectedDestination",
                                DimensionRef.NETHER.id()
                        )
                ),
                RequestedObservation.none(),
                "",
                1.0
        );
    }

    private static SkillContext context(long tick) {
        return new SkillContext(1, 1, tick, true, true, 0.0);
    }
}
