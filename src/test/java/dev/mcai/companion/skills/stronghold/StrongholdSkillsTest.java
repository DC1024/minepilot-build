package dev.mcai.companion.skills.stronghold;

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
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class StrongholdSkillsTest {
    private static final UUID PLAYER_ID = UUID.fromString(
            "61000000-0000-0000-0000-000000000001"
    );
    private static final UUID EYE_ID = UUID.fromString(
            "61000000-0000-0000-0000-000000000002"
    );

    @Test
    void parserAndRegistrationExposeOnlyTheBoundedFairContract() {
        final var parsed = StrongholdSkillParameters.parseTrace(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("sampleSequence", "12"),
                argument("hand", "main_hand")
        ));

        assertEquals(
                12,
                parsed.value().orElseThrow().sampleSequence()
        );
        assertFalse(StrongholdSkillParameters.parseTrace(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("sampleSequence", "012"),
                argument("hand", "main_hand")
        )).value().isPresent());
        assertFalse(StrongholdSkillParameters.parseTrace(List.of(
                argument("dimension", "minecraft:the_nether"),
                argument("sampleSequence", "12"),
                argument("hand", "third_hand")
        )).value().isPresent());

        final SkillRegistry registry = StrongholdSkills.registerAll(
                new SkillRegistry(),
                PLAYER_ID,
                new RecordingActuator(),
                new MutableFrames(frame(12, 3, List.of())),
                new EyeTraceResultBuffer()
        );
        assertEquals(
                java.util.Set.of("trace_stronghold_eye"),
                registry.names()
        );
        assertTrue(
                StrongholdSkills.plannerGuide()
                        .contains("measured search area only")
        );
    }

    @Test
    void tracksOnlyFreshVisibleThrownEyeAndPublishesNoEntityIdentity() {
        final MutableFrames frames =
                new MutableFrames(frame(10, 3, List.of()));
        final RecordingActuator actuator = new RecordingActuator();
        final EyeTraceResultBuffer buffer =
                new EyeTraceResultBuffer();
        final TraceStrongholdEyeSkill skill =
                new TraceStrongholdEyeSkill(
                        PLAYER_ID,
                        actuator,
                        frames,
                        buffer
                );
        final TraceStrongholdEyeParameters parameters =
                new TraceStrongholdEyeParameters(
                        DimensionRef.OVERWORLD,
                        10,
                        ActionHand.MAIN_HAND
                );

        assertTrue(skill.preconditions(context(100), parameters).isEmpty());
        skill.start(context(100), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101), parameters).status()
        );
        assertEquals(List.of(ActionHand.MAIN_HAND), actuator.uses);

        frames.frame = frame(
                11,
                2,
                List.of(eye(1.0, 66.0, 2.0))
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(102), parameters).status()
        );
        frames.frame = frame(
                12,
                2,
                List.of(eye(3.0, 67.0, 5.0))
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(103), parameters).status()
        );
        frames.frame = frame(
                13,
                2,
                List.of(eye(5.0, 68.0, 8.0))
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(104), parameters).status()
        );

        final EyeTraceHistorySnapshot history =
                buffer.snapshot(7).orElseThrow();
        final EyeTraceSnapshot trace = history.traces().getFirst();
        assertEquals(3, trace.samples().size());
        assertTrue(trace.directionX() > 0.5);
        assertTrue(trace.directionZ() > 0.8);
        assertEquals(13, trace.lastObservationRevision());
        final String json = new EyeTraceJsonCodec().encode(history);
        assertTrue(
                JsonParser.parseString(json)
                        .getAsJsonObject()
                        .has("contentBoundary")
        );
        assertFalse(json.contains(EYE_ID.toString()));
        assertFalse(json.contains(PLAYER_ID.toString()));
        assertFalse(buffer.snapshot(8).isPresent());
    }

    @Test
    void startsContinuousBoundedCameraSweepAfterLaunchReactionWindow() {
        final MutableFrames frames =
                new MutableFrames(frame(20, 2, List.of()));
        final RecordingActuator actuator = new RecordingActuator();
        final TraceStrongholdEyeSkill skill =
                new TraceStrongholdEyeSkill(
                        PLAYER_ID,
                        actuator,
                        frames,
                        new EyeTraceResultBuffer()
                );
        final TraceStrongholdEyeParameters parameters =
                new TraceStrongholdEyeParameters(
                        DimensionRef.OVERWORLD,
                        20,
                        ActionHand.MAIN_HAND
                );

        skill.start(context(100), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101), parameters).status()
        );
        frames.frame = frame(21, 1, List.of());
        skill.tick(context(102), parameters);
        frames.frame = frame(22, 1, List.of());
        skill.tick(context(104), parameters);
        frames.frame = frame(23, 1, List.of());
        skill.tick(context(106), parameters);

        assertEquals(3, actuator.looks.size());
        assertEquals(
                0.0F,
                actuator.looks.get(0).yawDegrees(),
                1.0E-6F
        );
        assertEquals(
                0.0F,
                actuator.looks.get(1).yawDegrees(),
                1.0E-6F
        );
        assertEquals(
                0.0F,
                actuator.looks.get(2).yawDegrees(),
                1.0E-6F
        );

        frames.frame = frame(24, 1, List.of());
        skill.tick(context(109), parameters);
        frames.frame = frame(25, 1, List.of());
        skill.tick(context(113), parameters);
        assertEquals(
                -45.0F,
                actuator.looks.get(actuator.looks.size() - 2)
                        .yawDegrees(),
                1.0E-6F
        );
        assertEquals(
                -90.0F,
                actuator.looks.getLast().yawDegrees(),
                1.0E-6F
        );
    }

    @Test
    void derivesFairBearingWhenFirstSeenNearEyeHoverPoint() {
        final MutableFrames frames =
                new MutableFrames(frame(30, 2, List.of()));
        final RecordingActuator actuator = new RecordingActuator();
        final EyeTraceResultBuffer buffer =
                new EyeTraceResultBuffer();
        final TraceStrongholdEyeSkill skill =
                new TraceStrongholdEyeSkill(
                        PLAYER_ID,
                        actuator,
                        frames,
                        buffer
                );
        final TraceStrongholdEyeParameters parameters =
                new TraceStrongholdEyeParameters(
                        DimensionRef.OVERWORLD,
                        30,
                        ActionHand.MAIN_HAND
                );

        skill.start(context(200), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(201), parameters).status()
        );
        frames.frame = frame(
                31,
                1,
                List.of(eye(12.0, 73.0, 0.5))
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(202), parameters).status()
        );
        frames.frame = frame(
                32,
                1,
                List.of(eye(12.1, 73.1, 0.5))
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(203), parameters).status()
        );
        frames.frame = frame(
                33,
                1,
                List.of(eye(12.0, 73.0, 0.5))
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(204), parameters).status()
        );

        final EyeTraceSnapshot trace = buffer.snapshot(7)
                .orElseThrow()
                .traces()
                .getFirst();
        assertEquals(1.0, trace.directionX(), 1.0E-6);
        assertEquals(0.0, trace.directionZ(), 1.0E-6);
        assertTrue(trace.observedHorizontalTravel() > 11.0);
    }

    @Test
    void triangulatesOnlySeparatedForwardCrossingTraces() {
        final EyeTraceResultBuffer buffer =
                new EyeTraceResultBuffer();
        buffer.publish(trace(
                new PerceptionVec3(0.0, 64.0, 0.0),
                0.4472135954999579,
                0.8944271909999159,
                20
        ));
        assertTrue(
                buffer.snapshot(7)
                        .orElseThrow()
                        .estimatedIntersection()
                        .isEmpty()
        );
        buffer.publish(trace(
                new PerceptionVec3(100.0, 64.0, 0.0),
                -0.4472135954999579,
                0.8944271909999159,
                30
        ));

        final var intersection = buffer.snapshot(7)
                .orElseThrow()
                .estimatedIntersection()
                .orElseThrow();
        assertEquals(50.0, intersection.x(), 1.0E-6);
        assertEquals(100.0, intersection.z(), 1.0E-6);
        assertTrue(intersection.uncertaintyRadius() >= 8.0);
    }

    @Test
    void rejectsStaleWrongDimensionAndUnsafeUse() {
        final MutableFrames frames =
                new MutableFrames(frame(10, 3, List.of()));
        final TraceStrongholdEyeSkill skill =
                new TraceStrongholdEyeSkill(
                        PLAYER_ID,
                        new RecordingActuator(),
                        frames,
                        new EyeTraceResultBuffer()
                );
        assertEquals(
                "trace_stronghold_eye.stale_observation",
                skill.preconditions(
                        context(1),
                        new TraceStrongholdEyeParameters(
                                DimensionRef.OVERWORLD,
                                9,
                                ActionHand.MAIN_HAND
                        )
                ).orElseThrow().code()
        );
        assertEquals(
                "trace_stronghold_eye.overworld_required",
                skill.preconditions(
                        context(1),
                        new TraceStrongholdEyeParameters(
                                DimensionRef.NETHER,
                                10,
                                ActionHand.MAIN_HAND
                        )
                ).orElseThrow().code()
        );
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
                200 + revision,
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
                Math.toDegrees(Math.atan2(-directionX, directionZ)),
                5.0
        );
    }

    private static CoreSkillFrame frame(
            final long revision,
            final int eyeCount,
            final List<VisibleEntity> entities
    ) {
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                revision,
                revision,
                new PerceptionVec3(0.5, 64.0, 0.5),
                new PerceptionVec3(0.5, 65.62, 0.5),
                new PerceptionVec3(0.0, 0.0, 1.0),
                true,
                false,
                entities.isEmpty() ? 0.0 : 0.5,
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        List.of()
                ),
                List.of(),
                20.0F,
                20.0F,
                20,
                List.of(new InventoryItemSummary(
                        TraceStrongholdEyeSkill.EYE_ITEM_ID,
                        eyeCount
                )),
                new HeldItemSummary(
                        TraceStrongholdEyeSkill.EYE_ITEM_ID,
                        eyeCount,
                        0,
                        0
                ),
                HeldItemSummary.empty(),
                entities,
                entities.isEmpty()
                        ? List.of()
                        : List.of(new dev.mcai.companion.perception
                                .DangerSignal(
                                dev.mcai.companion.perception
                                        .DangerKind
                                        .PROJECTILE_PROXIMITY,
                                0.5,
                                3.0,
                                Optional.empty(),
                                PerceptionProvenance.PROXIMITY_THREAT
                        ))
        );
    }

    private static VisibleEntity eye(
            final double x,
            final double y,
            final double z
    ) {
        return new VisibleEntity(
                EYE_ID,
                TraceStrongholdEyeSkill.EYE_ENTITY_ID,
                new PerceptionVec3(x, y, z),
                new PerceptionVec3(x - 0.5, y - 64.0, z - 0.5),
                Math.sqrt(
                        Math.pow(x - 0.5, 2)
                                + Math.pow(y - 64.0, 2)
                                + Math.pow(z - 0.5, 2)
                ),
                false,
                true,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(7, 10, tick, false, true, 0.0);
    }

    private static SkillArgument argument(
            final String name,
            final String value
    ) {
        return new SkillArgument(name, value);
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
        private final List<ActionHand> uses = new ArrayList<>();
        private final List<LookIntent> looks = new ArrayList<>();

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
            uses.add(hand);
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome releaseUse() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
    }
}
