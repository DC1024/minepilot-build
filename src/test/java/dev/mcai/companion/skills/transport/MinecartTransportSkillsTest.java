package dev.mcai.companion.skills.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MinecartTransportSkillsTest {
    private static final UUID PLAYER_ID = UUID.fromString(
            "70000000-0000-0000-0000-000000000001"
    );
    private static final UUID MINECART_ID = UUID.fromString(
            "70000000-0000-0000-0000-000000000002"
    );
    private static final long SESSION = 19;

    @Test
    void registersExactMinecartSkillsAndTypedContracts() {
        final MutableFrames frames = new MutableFrames(
                frame(40, 40, 0.0, false, "minecraft:minecart", false)
        );
        final SkillRegistry registry =
                MinecartTransportSkills.registerAll(
                        new SkillRegistry(),
                        PLAYER_ID,
                        new RecordingActuator(),
                        frames
                );

        assertEquals(
                Set.of(
                        "enter_observed_minecart",
                        "minecart_travel_to"
                ),
                registry.names()
        );
        assertTrue(MinecartSkillParameters.parseEnter(List.of(
                new SkillArgument(
                        "dimension",
                        "minecraft:overworld"
                ),
                new SkillArgument("sampleSequence", "40"),
                new SkillArgument("observationId", "visible-0")
        )).value().isPresent());
        assertFalse(MinecartSkillParameters.parseTravel(List.of(
                new SkillArgument(
                        "dimension",
                        "minecraft:overworld"
                ),
                new SkillArgument("x", "5"),
                new SkillArgument("y", "64"),
                new SkillArgument("z", "0"),
                new SkillArgument("arrivalRadius", "1"),
                new SkillArgument("timeoutTicks", "0100"),
                new SkillArgument("dismountAtArrival", "true")
        )).value().isPresent());
    }

    @Test
    void mountsOnlyTheFreshObservedRideableMinecart() {
        final MutableFrames frames = new MutableFrames(
                frame(40, 40, 0.0, false, "minecraft:minecart", false)
        );
        final RecordingActuator actuator =
                new RecordingActuator();
        final EnterObservedMinecartSkill skill =
                new EnterObservedMinecartSkill(
                        PLAYER_ID,
                        actuator,
                        frames,
                        MinecartSkillPolicy.defaults()
                );
        final EnterObservedMinecartParameters parameters =
                new EnterObservedMinecartParameters(
                        DimensionRef.OVERWORLD,
                        40,
                        "visible-0"
                );

        assertTrue(
                skill.preconditions(context(100), parameters).isEmpty()
        );
        skill.start(context(100), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101), parameters).status()
        );
        assertEquals(List.of(MINECART_ID), actuator.entries);

        frames.frame = frame(
                40,
                41,
                0.0,
                true,
                "minecraft:minecart",
                false
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(102), parameters).status()
        );
        assertFalse(
                skill.checkpoint(context(102), parameters)
                        .payload()
                        .contains(MINECART_ID.toString()),
                "Internal entity UUID must not enter planner checkpoint data"
        );
    }

    @Test
    void refusesCargoMinecartAsASeat() {
        final MutableFrames frames = new MutableFrames(
                frame(
                        40,
                        40,
                        0.0,
                        false,
                        "minecraft:chest_minecart",
                        false
                )
        );
        final EnterObservedMinecartSkill skill =
                new EnterObservedMinecartSkill(
                        PLAYER_ID,
                        new RecordingActuator(),
                        frames,
                        MinecartSkillPolicy.defaults()
                );

        assertEquals(
                "enter_observed_minecart.target_not_rideable",
                skill.preconditions(
                                context(100),
                                new EnterObservedMinecartParameters(
                                        DimensionRef.OVERWORLD,
                                        40,
                                        "visible-0"
                                )
                        )
                        .orElseThrow()
                        .code()
        );
    }

    @Test
    void ridesToTargetAndDismountsOnlyBesideObservedSurface() {
        final MutableFrames frames = new MutableFrames(
                frame(50, 50, 0.0, true, "minecraft:minecart", false)
        );
        final RecordingActuator actuator =
                new RecordingActuator();
        final MinecartTravelToSkill skill =
                new MinecartTravelToSkill(
                        PLAYER_ID,
                        actuator,
                        frames,
                        MinecartSkillPolicy.defaults()
                );
        final MinecartTravelToParameters parameters =
                new MinecartTravelToParameters(
                        DimensionRef.OVERWORLD,
                        5.0,
                        64.0,
                        0.0,
                        1.0,
                        400,
                        true
                );

        assertTrue(
                skill.preconditions(context(100), parameters).isEmpty()
        );
        skill.start(context(100), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101), parameters).status()
        );
        assertEquals(1, actuator.drives.size());

        frames.frame = frame(
                51,
                51,
                4.5,
                true,
                "minecraft:minecart",
                true
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(102), parameters).status()
        );
        assertEquals(List.of(MINECART_ID), actuator.dismounts);

        frames.frame = frame(
                52,
                52,
                4.5,
                false,
                "minecraft:minecart",
                true
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(103), parameters).status()
        );
    }

    private static MinecartSkillFrame frame(
            final long observationRevision,
            final long currentGameTime,
            final double cartX,
            final boolean riding,
            final String visibleType,
            final boolean safeSurface
    ) {
        final VisibleEntity visible = new VisibleEntity(
                MINECART_ID,
                visibleType,
                new PerceptionVec3(cartX, 64.0, 0.0),
                new PerceptionVec3(cartX, 0.0, 0.0),
                Math.abs(cartX),
                false,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final List<VisibleBlockFace> faces = safeSurface
                ? List.of(new VisibleBlockFace(
                        new BlockCoordinate(4, 63, 0),
                        "minecraft:stone",
                        "up",
                        new PerceptionVec3(4.5, 64.0, 0.5),
                        2.0,
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                        Map.of()
                ))
                : List.of();
        return new MinecartSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                currentGameTime,
                observationRevision,
                observationRevision,
                SESSION,
                new PerceptionVec3(cartX, 64.0, 0.0),
                List.of(visible),
                faces,
                0.0,
                riding
                        ? Optional.of(new MinecartState(
                                MINECART_ID,
                                new PerceptionVec3(
                                        cartX,
                                        64.0,
                                        0.0
                                ),
                                new PerceptionVec3(
                                        0.2,
                                        0.0,
                                        0.0
                                ),
                                false
                        ))
                        : Optional.empty()
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(
                1,
                2,
                tick,
                false,
                true,
                0.0
        );
    }

    private static final class MutableFrames
            implements MinecartSkillFrameSource {
        private MinecartSkillFrame frame;

        private MutableFrames(final MinecartSkillFrame frame) {
            this.frame = frame;
        }

        @Override
        public Optional<MinecartSkillFrame> current() {
            return Optional.of(frame);
        }
    }

    private static final class RecordingActuator
            implements MinecartSkillActuator {
        private final List<UUID> entries = new ArrayList<>();
        private final List<String> drives = new ArrayList<>();
        private final List<UUID> stops = new ArrayList<>();
        private final List<UUID> dismounts = new ArrayList<>();

        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(SESSION);
        }

        @Override
        public ActionOutcome enterMinecart(
                final UUID observedMinecartId
        ) {
            entries.add(observedMinecartId);
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome driveMinecart(
                final UUID expectedMinecartId,
                final float targetYawDegrees,
                final boolean forward,
                final boolean backward
        ) {
            drives.add(
                    expectedMinecartId
                            + ":"
                            + forward
                            + ":"
                            + backward
            );
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome stopMinecartInput(
                final UUID expectedMinecartId
        ) {
            stops.add(expectedMinecartId);
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome dismountMinecart(
                final UUID expectedMinecartId
        ) {
            dismounts.add(expectedMinecartId);
            return ActionOutcome.DISPATCHED;
        }
    }
}
