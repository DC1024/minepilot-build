package dev.mcai.companion.skills.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SurveySkillsTest {
    private static final UUID PLAYER_ID = UUID.fromString(
            "60000000-0000-0000-0000-000000000001"
    );
    private static final UUID ENTITY_ID = UUID.fromString(
            "60000000-0000-0000-0000-000000000002"
    );

    @Test
    void parsesOnlyTheExactBoundedTypedContract() {
        final var parsed = SurveySkillParameters.parse(List.of(
                new SkillArgument(
                        "dimension",
                        "minecraft:overworld"
                ),
                new SkillArgument("horizontalSteps", "8"),
                new SkillArgument("includeVertical", "true")
        ));

        final SurveySurroundingsParameters parameters =
                parsed.value().orElseThrow();
        assertEquals(24, parameters.totalViews());
        assertEquals(
                SurveySurroundingsParameters.DEFAULT_OBSERVATION_WAIT_TICKS,
                parameters.observationWaitTicks()
        );
        final SurveySurroundingsParameters shortWait =
                SurveySkillParameters.parse(List.of(
                        new SkillArgument(
                                "dimension",
                                "minecraft:overworld"
                        ),
                        new SkillArgument("horizontalSteps", "4"),
                        new SkillArgument("includeVertical", "false"),
                        new SkillArgument("observationWaitTicks", "12")
                )).value().orElseThrow();
        assertEquals(12, shortWait.observationWaitTicks());
        assertFalse(SurveySkillParameters.parse(List.of(
                new SkillArgument(
                        "dimension",
                        "minecraft:overworld"
                ),
                new SkillArgument("horizontalSteps", "03"),
                new SkillArgument("includeVertical", "true")
        )).value().isPresent());
        assertFalse(SurveySkillParameters.parse(List.of(
                new SkillArgument(
                        "dimension",
                        "minecraft:overworld"
                ),
                new SkillArgument("horizontalSteps", "4"),
                new SkillArgument("includeVertical", "false"),
                new SkillArgument("observationWaitTicks", "03")
        )).value().isPresent());
        assertFalse(SurveySkillParameters.parse(List.of(
                new SkillArgument(
                        "dimension",
                        "minecraft:overworld"
                ),
                new SkillArgument("horizontalSteps", "17"),
                new SkillArgument("includeVertical", "false")
        )).value().isPresent());
    }

    @Test
    void registersTheSingleFairSurveySkill() {
        final MutableFrames frames = new MutableFrames(frame(
                1,
                new PerceptionVec3(0.0, 0.0, 1.0)
        ));
        final SkillRegistry registry = SurveySkills.registerAll(
                new SkillRegistry(),
                PLAYER_ID,
                new RecordingActuator(),
                frames,
                new SurveyResultBuffer()
        );

        assertEquals(
                java.util.Set.of("survey_surroundings"),
                registry.names()
        );
    }

    @Test
    void aggregatesOnlyFreshFirstPersonSamplesAndHidesEntityIds() {
        long observationRevision = 10;
        final MutableFrames frames = new MutableFrames(frame(
                observationRevision,
                new PerceptionVec3(0.0, 0.0, 1.0)
        ));
        final RecordingActuator actuator = new RecordingActuator();
        final SurveyResultBuffer buffer = new SurveyResultBuffer();
        final SurveySurroundingsSkill skill =
                new SurveySurroundingsSkill(
                        PLAYER_ID,
                        actuator,
                        frames,
                        buffer
                );
        final SurveySurroundingsParameters parameters =
                new SurveySurroundingsParameters(
                        DimensionRef.OVERWORLD,
                        4,
                        false
                );
        long tick = 100;
        final SkillContext initial = context(tick);

        assertTrue(skill.preconditions(initial, parameters).isEmpty());
        skill.start(initial, parameters);
        SkillTickResult result = SkillTickResult.running(false, false);
        while (result.status() == SkillTickResult.Status.RUNNING) {
            result = skill.tick(context(++tick), parameters);
            if (result.status() != SkillTickResult.Status.RUNNING) {
                break;
            }
            observationRevision++;
            final PerceptionVec3 direction = actuator.looks.isEmpty()
                    ? frames.frame.lookDirection()
                    : direction(actuator.looks.getLast());
            frames.frame = frame(observationRevision, direction);
            assertTrue(
                    tick < 140,
                    "Survey did not finish its bounded view sequence"
            );
        }

        assertEquals(SkillTickResult.Status.COMPLETED, result.status());
        final SurveyResultSnapshot snapshot =
                buffer.snapshot(initial.goalRevision()).orElseThrow();
        assertEquals(4, snapshot.sampledViews());
        assertEquals(1, snapshot.blocks().size());
        assertEquals("minecraft:oak_log", snapshot.blocks().getFirst().blockId());
        assertEquals("north", snapshot.blocks().getFirst().face());
        assertTrue(
                snapshot.blocks().getFirst().sampleSequence()
                    >= snapshot.firstObservationRevision()
        );
        assertTrue(
                snapshot.blocks().getFirst().sampleSequence()
                    <= snapshot.lastObservationRevision()
        );
        assertEquals(1, snapshot.entities().size());
        assertEquals(
                "minecraft:cow",
                snapshot.entities().getFirst().entityTypeId()
        );
        assertEquals(1, snapshot.entities().getFirst().uniqueCount());
        assertEquals(1, snapshot.dangers().size());
        assertTrue(actuator.stops >= 4);

        final String json = new SurveyResultJsonCodec().encode(snapshot);
        final var root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(
                4,
                root.get("sampledViews").getAsInt()
        );
        assertTrue(root.has("contentBoundary"));
        assertFalse(json.contains(ENTITY_ID.toString()));
        assertFalse(json.contains(PLAYER_ID.toString()));
        assertFalse(
                buffer.snapshot(initial.goalRevision() + 1).isPresent(),
                "A survey must never leak into another goal revision"
        );
    }

    @Test
    void rejectsDangerBeforeTurningTheBody() {
        final MutableFrames frames = new MutableFrames(
                dangerousFrame(1)
        );
        final RecordingActuator actuator = new RecordingActuator();
        final SurveySurroundingsSkill skill =
                new SurveySurroundingsSkill(
                        PLAYER_ID,
                        actuator,
                        frames,
                        new SurveyResultBuffer()
                );
        final SurveySurroundingsParameters parameters =
                new SurveySurroundingsParameters(
                        DimensionRef.OVERWORLD,
                        4,
                        false
                );

        assertEquals(
                "survey_surroundings.danger_detected",
                skill.preconditions(
                                new SkillContext(
                                        7,
                                        8,
                                        100,
                                        true,
                                        true,
                                        0.5
                                ),
                                parameters
                        )
                        .orElseThrow()
                        .code()
        );
        assertTrue(actuator.looks.isEmpty());
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(7, 8, tick, false, true, 0.0);
    }

    private static CoreSkillFrame frame(
            final long revision,
            final PerceptionVec3 lookDirection
    ) {
        final List<DangerSignal> dangers = List.of(new DangerSignal(
                DangerKind.HOSTILE_PROXIMITY,
                0.05,
                8.0,
                Optional.empty(),
                PerceptionProvenance.PROXIMITY_THREAT
        ));
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                revision,
                revision,
                new PerceptionVec3(0.5, 64.0, 0.5),
                new PerceptionVec3(0.5, 65.62, 0.5),
                lookDirection.normalized(),
                true,
                false,
                0.05,
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        List.of()
                ),
                List.of(new VisibleBlockFace(
                        new BlockCoordinate(0, 64, 3),
                        "minecraft:oak_log",
                        "north",
                        new PerceptionVec3(0.5, 64.5, 3.0),
                        2.5,
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                        Map.of("axis", "y")
                )),
                20.0F,
                20.0F,
                20,
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(new VisibleEntity(
                        ENTITY_ID,
                        "minecraft:cow",
                        new PerceptionVec3(1.5, 64.0, 3.5),
                        new PerceptionVec3(1.0, 0.0, 3.0),
                        Math.sqrt(10.0),
                        false,
                        false,
                        PerceptionProvenance
                                .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
                )),
                dangers
        );
    }

    private static CoreSkillFrame dangerousFrame(final long revision) {
        final CoreSkillFrame safe = frame(
                revision,
                new PerceptionVec3(0.0, 0.0, 1.0)
        );
        return new CoreSkillFrame(
                safe.playerId(),
                safe.dimension(),
                safe.gameTime(),
                safe.observationRevision(),
                safe.position(),
                safe.eyePosition(),
                safe.lookDirection(),
                safe.onGround(),
                safe.inWater(),
                0.5,
                safe.navigation(),
                safe.visibleBlockFaces(),
                safe.health(),
                safe.maxHealth(),
                safe.foodLevel(),
                safe.mainHand(),
                safe.offHand(),
                safe.visibleEntities(),
                safe.dangerSignals()
        );
    }

    private static PerceptionVec3 direction(final LookIntent look) {
        final double yaw = Math.toRadians(look.yawDegrees());
        final double pitch = Math.toRadians(look.pitchDegrees());
        final double horizontal = Math.cos(pitch);
        return new PerceptionVec3(
                -Math.sin(yaw) * horizontal,
                -Math.sin(pitch),
                Math.cos(yaw) * horizontal
        ).normalized();
    }

    private static final class MutableFrames
            implements CoreSkillFrameSource {
        private CoreSkillFrame frame;

        private MutableFrames(final CoreSkillFrame frame) {
            this.frame = frame;
        }

        @Override
        public Optional<CoreSkillFrame> current() {
            return Optional.of(frame);
        }
    }

    private static final class RecordingActuator
            implements CoreSkillActuator {
        private final List<LookIntent> looks = new ArrayList<>();
        private int stops;

        @Override
        public ActionOutcome move(final MovementIntent intent) {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome look(final LookIntent intent) {
            looks.add(intent);
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
}
