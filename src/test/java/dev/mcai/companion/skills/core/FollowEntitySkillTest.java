package dev.mcai.companion.skills.core;

import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.core.CoreSkillTestFixtures.corridor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class FollowEntitySkillTest {
    private static final UUID TARGET = UUID.fromString(
            "00000000-0000-0000-0000-000000000456"
    );
    private static final PerceptionVec3 EAST =
            new PerceptionVec3(1.0, 0.0, 0.0);

    @Test
    void followsOnlyAVisibleNonHostileTargetThroughLocalPlanner()
            throws Exception {
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        List.of(targetAt(2.5))
                ));
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        FollowEntitySkill skill = new FollowEntitySkill(
                PLAYER_ID,
                actuator,
                frames,
                new dev.mcai.companion.navigation.LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );
        FollowEntityParameters parameters = new FollowEntityParameters(
                "visible-0",
                1,
                1.5,
                40
        );

        assertTrue(skill.preconditions(
                context(1),
                parameters
        ).isEmpty());
        skill.start(context(1), parameters);
        assertFalse(skill.checkpoint(context(1), parameters)
                .payload()
                .contains(TARGET.toString()));
        SkillTickResult result = skill.tick(context(2), parameters);

        assertEquals(SkillTickResult.Status.RUNNING, result.status());
        assertFalse(actuator.movements.isEmpty());
    }

    @Test
    void visiblyNearbyPlayerGetsContinuousInputsBeforeNavMapIsComplete()
            throws Exception {
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frameWithNavigation(
                        1,
                        List.of(targetAt(10.5)),
                        new LocalNavSnapshot(
                                DimensionRef.OVERWORLD,
                                1,
                                List.of()
                        )
                ));
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final FollowEntitySkill skill = new FollowEntitySkill(
                PLAYER_ID,
                actuator,
                frames,
                new dev.mcai.companion.navigation.LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );
        final FollowEntityParameters parameters = new FollowEntityParameters(
                "visible-0",
                1,
                1.5,
                40
        );

        skill.start(context(1), parameters);
        for (long tick = 2; tick <= 6; tick++) {
            frames.frame = frameWithNavigation(
                    tick,
                    List.of(targetAt(10.5)),
                    new LocalNavSnapshot(
                            DimensionRef.OVERWORLD,
                            tick,
                            List.of()
                    )
            );
            assertEquals(
                    SkillTickResult.Status.RUNNING,
                    skill.tick(context(tick), parameters).status()
            );
        }

        assertEquals(
                5,
                actuator.movements.size(),
                "a visible same-level player must not wait for a full route scan"
        );
        assertTrue(
                actuator.movements.stream().allMatch(
                        movement -> movement.forward() > 0.0
                )
        );
    }

    @Test
    void losingLineOfSightStopsAndPerformsBoundedScan() throws Exception {
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        List.of(targetAt(2.5))
                ));
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        FollowEntitySkill skill = new FollowEntitySkill(
                PLAYER_ID,
                actuator,
                frames,
                new dev.mcai.companion.navigation.LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );
        FollowEntityParameters parameters = new FollowEntityParameters(
                "visible-0",
                1,
                1.5,
                20
        );
        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);
        int movementsBeforeLoss = actuator.movements.size();
        frames.frame = frame(2, List.of());

        SkillTickResult searching = skill.tick(context(3), parameters);

        assertEquals(SkillTickResult.Status.RUNNING, searching.status());
        assertEquals(movementsBeforeLoss, actuator.movements.size());
        assertTrue(actuator.stops > 0);
        assertFalse(actuator.looks.isEmpty());
    }

    @Test
    void nonSneakingPlayerCoordinateTurnsOnceThenContinuesVisibleFollow()
            throws Exception {
        final CoreSkillFrame authored = frame(
                1,
                List.of(targetAt(6.5))
        );
        final CoreSkillFrame[] current = {frameWithNavigation(
                2,
                List.of(),
                corridor(2, 8)
        )};
        final CoreSkillFrameSource frames = new CoreSkillFrameSource() {
            @Override
            public Optional<CoreSkillFrame> current() {
                return Optional.of(current[0]);
            }

            @Override
            public Optional<CoreSkillFrame> atObservation(
                    final long observationRevision
            ) {
                return observationRevision == 1
                        ? Optional.of(authored)
                        : Optional.of(current[0]);
            }

            @Override
            public Optional<TrackablePlayer> trackablePlayer(
                    final UUID playerId
            ) {
                return Optional.of(new TrackablePlayer(
                        TARGET,
                        DimensionRef.OVERWORLD,
                        new PerceptionVec3(6.5, 1.0, 0.5),
                        2
                ));
            }
        };
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final FollowEntitySkill skill = new FollowEntitySkill(
                PLAYER_ID,
                actuator,
                frames,
                new dev.mcai.companion.navigation.LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );
        final FollowEntityParameters parameters = new FollowEntityParameters(
                "visible-0",
                1,
                1.5,
                40
        );

        skill.start(context(1), parameters);
        final SkillTickResult reacquiring = skill.tick(
                context(2),
                parameters
        );

        assertEquals(SkillTickResult.Status.RUNNING, reacquiring.status());
        assertTrue(actuator.movements.isEmpty());
        assertEquals(1, actuator.stops);
        assertEquals(
                1,
                actuator.looks.size(),
                "the authorized coordinate should produce one directed look, "
                        + "not a spin search"
        );

        current[0] = frameWithNavigation(
                3,
                List.of(targetAt(6.5)),
                corridor(3, 8)
        );
        final SkillTickResult result = skill.tick(context(3), parameters);

        assertEquals(SkillTickResult.Status.RUNNING, result.status());
        assertFalse(actuator.movements.isEmpty());
    }

    @Test
    void rejectsForgedAndStaleObservationIds() {
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        2,
                        List.of(targetAt(2.5))
                ));
        FollowEntitySkill skill = new FollowEntitySkill(
                PLAYER_ID,
                new CoreSkillTestFixtures.RecordingActuator(),
                frames,
                new dev.mcai.companion.navigation.LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );

        assertEquals(
                "follow_entity.invalid_observation_id",
                skill.preconditions(
                        context(2),
                        new FollowEntityParameters(
                                "visible-9",
                                2,
                                1.5,
                                20
                        )
                ).orElseThrow().code()
        );
        assertEquals(
                "follow_entity.stale_observation_id",
                skill.preconditions(
                        context(2),
                        new FollowEntityParameters(
                                "visible-0",
                                1,
                                1.5,
                                20
                        )
                ).orElseThrow().code()
        );
    }

    @Test
    void bindsInternallyAcrossVisibleListReordering() throws Exception {
        UUID decoyId = UUID.fromString(
                "00000000-0000-0000-0000-000000000457"
        );
        VisibleEntity decoy = new VisibleEntity(
                decoyId,
                "minecraft:cow",
                new PerceptionVec3(0.75, 1.0, 0.5),
                new PerceptionVec3(0.25, 0.0, 0.0),
                0.25,
                false,
                false,
                PerceptionProvenance.ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        List.of(targetAt(2.5), decoy)
                ));
        CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        FollowEntitySkill skill = new FollowEntitySkill(
                PLAYER_ID,
                actuator,
                frames,
                new dev.mcai.companion.navigation.LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );
        FollowEntityParameters parameters = new FollowEntityParameters(
                "visible-0",
                1,
                1.5,
                20
        );
        assertTrue(skill.preconditions(context(1), parameters).isEmpty());
        skill.start(context(1), parameters);
        frames.frame = frame(
                2,
                List.of(decoy, targetAt(2.5))
        );

        SkillTickResult result = skill.tick(context(2), parameters);

        assertEquals(SkillTickResult.Status.RUNNING, result.status());
        assertFalse(
                actuator.movements.isEmpty(),
                "the originally bound target remains far after reordering"
        );
    }

    @Test
    void bindsRecentAuthoredSampleToSameCurrentlyVisibleEntity()
            throws Exception {
        final CoreSkillFrame authored = frame(
                4,
                List.of(targetAt(2.5))
        );
        final CoreSkillFrame current = frame(
                14,
                List.of(targetAt(3.5))
        );
        final CoreSkillFrameSource frames =
                new HistoricalFrames(
                        current,
                        Map.of(4L, authored, 14L, current)
                );
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final FollowEntitySkill skill = new FollowEntitySkill(
                PLAYER_ID,
                actuator,
                frames,
                new dev.mcai.companion.navigation.LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );
        final FollowEntityParameters parameters =
                new FollowEntityParameters(
                        "visible-0",
                        4,
                        1.5,
                        100
                );

        assertTrue(
                skill.preconditions(
                        context(14),
                        parameters
                ).isEmpty()
        );
        skill.start(context(14), parameters);
        final SkillTickResult result =
                skill.tick(context(15), parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                result.status()
        );
        assertFalse(actuator.movements.isEmpty());
        assertFalse(
                skill.checkpoint(context(15), parameters)
                        .payload()
                        .contains(TARGET.toString())
        );
    }

    @Test
    void bindsAuthoredSampleAcrossModelHardDeadlineWindow()
            throws Exception {
        final CoreSkillFrame authored = frame(
                4,
                List.of(targetAt(2.5))
        );
        final CoreSkillFrame current = frame(
                454,
                List.of(targetAt(3.5))
        );
        final FollowEntitySkill skill = new FollowEntitySkill(
                PLAYER_ID,
                new CoreSkillTestFixtures.RecordingActuator(),
                new HistoricalFrames(
                        current,
                        Map.of(4L, authored, 454L, current)
                ),
                new dev.mcai.companion.navigation.LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );
        final FollowEntityParameters parameters =
                new FollowEntityParameters(
                        "visible-0",
                        4,
                        1.5,
                        100
                );

        assertTrue(
                skill.preconditions(context(454), parameters).isEmpty()
        );
    }

    @Test
    void recentAuthoredSampleStartsBoundedSearchWhenTargetLeavesCurrentView()
            throws Exception {
        final CoreSkillFrame authored = frame(
                4,
                List.of(targetAt(2.5))
        );
        final CoreSkillFrame current = frame(14, List.of());
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final HistoricalFrames frames = new HistoricalFrames(
                current,
                Map.of(4L, authored, 14L, current)
        );
        final FollowEntitySkill skill = new FollowEntitySkill(
                PLAYER_ID,
                actuator,
                frames,
                new dev.mcai.companion.navigation.LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );

        final FollowEntityParameters parameters =
                new FollowEntityParameters(
                        "visible-0",
                        4,
                        1.5,
                        100
                );
        assertTrue(
                skill.preconditions(context(14), parameters).isEmpty(),
                "a recent fair target may enter bounded search"
        );
        skill.start(context(14), parameters);
        final SkillTickResult searching = skill.tick(context(14), parameters);
        assertEquals(SkillTickResult.Status.RUNNING, searching.status());
        assertTrue(
                actuator.stops > 0 && !actuator.looks.isEmpty(),
                "lost target must trigger bounded stop-and-scan, not a no-op"
        );
    }

    @Test
    void stalledFollowScansForRoutesButStillFailsWithinABound()
            throws Exception {
        final CoreSkillTestFixtures.MutableFrames frames =
                new CoreSkillTestFixtures.MutableFrames(frame(
                        1,
                        List.of(targetAt(2.5))
                ));
        final CoreSkillTestFixtures.RecordingActuator actuator =
                new CoreSkillTestFixtures.RecordingActuator();
        final FollowEntitySkill skill = new FollowEntitySkill(
                PLAYER_ID,
                actuator,
                frames,
                new dev.mcai.companion.navigation.LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );
        final FollowEntityParameters parameters =
                new FollowEntityParameters("visible-0", 1, 1.5, 100);

        skill.start(context(1), parameters);
        SkillTickResult result = null;
        for (long tick = 2; tick <= 360; tick++) {
            result = skill.tick(context(tick), parameters);
            if (result.status() == SkillTickResult.Status.FAILED) {
                break;
            }
        }

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "follow_entity.no_walkable_route",
                result.failure().orElseThrow().code()
        );
        assertTrue(actuator.stops > 0);
        assertTrue(
                actuator.looks.size() >= 24,
                "two bounded 360-degree floor/side scans must happen before "
                        + "the route is declared unavailable"
        );
    }

    @Test
    void authoredSampleOutsideBindingWindowRemainsRejected() {
        final CoreSkillFrame authored = frame(
                1,
                List.of(targetAt(2.5))
        );
        final CoreSkillFrame current = frame(
                514,
                List.of(targetAt(3.5))
        );
        final FollowEntitySkill skill = new FollowEntitySkill(
                PLAYER_ID,
                new CoreSkillTestFixtures.RecordingActuator(),
                new HistoricalFrames(
                        current,
                        Map.of(1L, authored, 514L, current)
                ),
                new dev.mcai.companion.navigation.LocalAStarPlanner(),
                CoreSkillPolicy.defaults()
        );

        assertEquals(
                "follow_entity.stale_observation_id",
                skill.preconditions(
                        context(514),
                        new FollowEntityParameters(
                                "visible-0",
                                1,
                                1.5,
                                100
                        )
                ).orElseThrow().code()
        );
    }

    private static VisibleEntity targetAt(double x) {
        return new VisibleEntity(
                TARGET,
                "minecraft:player",
                new PerceptionVec3(x, 1.0, 0.5),
                new PerceptionVec3(x - 0.5, 0.0, 0.0),
                x - 0.5,
                false,
                false,
                PerceptionProvenance.ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
    }

    private static CoreSkillFrame frame(
            long revision,
            List<VisibleEntity> entities
    ) {
        return frameWithNavigation(revision, entities, corridor(revision, 3));
    }

    private static CoreSkillFrame frameWithNavigation(
            long revision,
            List<VisibleEntity> entities,
            LocalNavSnapshot navigation
    ) {
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                revision,
                revision,
                new PerceptionVec3(0.5, 1.0, 0.5),
                new PerceptionVec3(0.5, 2.62, 0.5),
                EAST,
                true,
                false,
                0.0,
                navigation,
                List.of(),
                20.0F,
                20.0F,
                20,
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                entities,
                List.of()
        );
    }

    private static SkillContext context(long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }

    private record HistoricalFrames(
            CoreSkillFrame currentFrame,
            Map<Long, CoreSkillFrame> history
    ) implements CoreSkillFrameSource {
        private HistoricalFrames {
            history = Map.copyOf(history);
        }

        @Override
        public Optional<CoreSkillFrame> current() {
            return Optional.ofNullable(currentFrame);
        }

        @Override
        public Optional<CoreSkillFrame> atObservation(
                final long observationRevision
        ) {
            return Optional.ofNullable(
                    history.get(observationRevision)
            );
        }
    }
}
