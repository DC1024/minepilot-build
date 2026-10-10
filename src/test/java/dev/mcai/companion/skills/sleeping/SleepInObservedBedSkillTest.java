package dev.mcai.companion.skills.sleeping;

import static dev.mcai.companion.skills.sleeping.SleepSkillTestFixtures.BED_HEAD;
import static dev.mcai.companion.skills.sleeping.SleepSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.sleeping.SleepSkillTestFixtures.SESSION;
import static dev.mcai.companion.skills.sleeping.SleepSkillTestFixtures.bed;
import static dev.mcai.companion.skills.sleeping.SleepSkillTestFixtures.parameters;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SleepInObservedBedSkillTest {
    @Test
    void usesVisibleBedThenRequiresRealSleepAndNaturalDawnWake() {
        var builder = new SleepSkillTestFixtures.FrameBuilder();
        var frames = new SleepSkillTestFixtures.MutableFrames(
                builder.build()
        );
        var actuator = new SleepSkillTestFixtures.RecordingActuator();
        var skill = skill(frames, actuator);

        assertTrue(
                skill.preconditions(context(100, false), parameters())
                        .isEmpty()
        );
        skill.start(context(100, false), parameters());
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101, false), parameters()).status()
        );
        assertEquals(1, actuator.uses.size());

        frames.frame = builder.sleeping().build();
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(102, false), parameters()).status()
        );

        frames.frame = builder.awakeAtDawn().build();
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(203, false), parameters()).status()
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                skill.result(context(203, false), parameters()).status()
        );
    }

    @Test
    void rejectsDayExplosiveDimensionOccupiedDistantAndInsufficientBed() {
        var daytime = new SleepSkillTestFixtures.FrameBuilder();
        daytime.darkOutside = false;
        assertFailure(daytime, false, "daytime");

        var nether = new SleepSkillTestFixtures.FrameBuilder();
        nether.currentDimension = DimensionRef.NETHER;
        nether.observedDimension = DimensionRef.NETHER;
        var netherParameters = new SleepInObservedBedParameters(
                DimensionRef.NETHER,
                parameters().target()
        );
        assertEquals(
                "sleep_in_observed_bed.explosive_or_unsupported_dimension",
                skill(
                        new SleepSkillTestFixtures.MutableFrames(
                                nether.build()
                        ),
                        new SleepSkillTestFixtures.RecordingActuator()
                ).preconditions(context(100, false), netherParameters)
                        .orElseThrow()
                        .code()
        );

        var occupied = new SleepSkillTestFixtures.FrameBuilder();
        occupied.blocks = List.of(bed(true, 2.5));
        assertFailure(occupied, false, "bed_occupied");

        var distant = new SleepSkillTestFixtures.FrameBuilder();
        distant.blocks = List.of(bed(false, 4.0));
        assertFailure(distant, false, "too_far");

        var multiplayer = new SleepSkillTestFixtures.FrameBuilder();
        multiplayer.activePlayers = 3;
        multiplayer.sleepersNeeded = 3;
        assertFailure(
                multiplayer,
                false,
                "insufficient_sleepers"
        );
    }

    @Test
    void rejectsNearbyHostileAndHardcoreUsesHigherHealthFloor() {
        var hostile = new SleepSkillTestFixtures.FrameBuilder();
        hostile.dangers = List.of(new DangerSignal(
                DangerKind.HOSTILE_PROXIMITY,
                0.75,
                6.0,
                Optional.empty(),
                PerceptionProvenance.PROXIMITY_THREAT
        ));
        assertFailure(hostile, false, "hostile_nearby");

        var injured = new SleepSkillTestFixtures.FrameBuilder();
        injured.health = 8.0F;
        assertTrue(skill(
                new SleepSkillTestFixtures.MutableFrames(
                        injured.build()
                ),
                new SleepSkillTestFixtures.RecordingActuator()
        ).preconditions(context(100, false), parameters()).isEmpty());
        assertFailure(injured, true, "health_too_low");
    }

    @Test
    void rejectsStaleObservationAndReplacementSession() {
        var stale = new SleepSkillTestFixtures.FrameBuilder();
        stale.currentGameTime = 111;
        assertFailure(stale, false, "stale_observation");

        var builder = new SleepSkillTestFixtures.FrameBuilder();
        var frames = new SleepSkillTestFixtures.MutableFrames(
                builder.build()
        );
        var actuator = new SleepSkillTestFixtures.RecordingActuator();
        var skill = skill(frames, actuator);
        skill.start(context(100, false), parameters());

        actuator.session = SESSION + 1;
        builder.session = SESSION + 1;
        builder.currentGameTime++;
        frames.frame = builder.build();
        SkillTickResult result = skill.tick(
                context(101, false),
                parameters()
        );
        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                "sleep_in_observed_bed.session_mismatch",
                result.failure().orElseThrow().code()
        );
        assertTrue(actuator.uses.isEmpty());
    }

    @Test
    void doesNotAcceptWrongSleepingPositionOrEarlyWake() {
        var builder = new SleepSkillTestFixtures.FrameBuilder();
        var frames = new SleepSkillTestFixtures.MutableFrames(
                builder.build()
        );
        var actuator = new SleepSkillTestFixtures.RecordingActuator();
        var skill = skill(frames, actuator);
        skill.start(context(100, false), parameters());
        skill.tick(context(101, false), parameters());

        builder.sleeping();
        builder.sleepingPosition = Optional.of(
                new dev.mcai.companion.perception.BlockCoordinate(
                        BED_HEAD.x() + 1,
                        BED_HEAD.y(),
                        BED_HEAD.z()
                )
        );
        frames.frame = builder.build();
        SkillTickResult wrong = skill.tick(
                context(102, false),
                parameters()
        );
        assertEquals(
                "sleep_in_observed_bed.wrong_sleeping_position",
                wrong.failure().orElseThrow().code()
        );

        builder = new SleepSkillTestFixtures.FrameBuilder();
        frames = new SleepSkillTestFixtures.MutableFrames(
                builder.build()
        );
        skill = skill(frames, actuator);
        skill.start(context(200, false), parameters());
        skill.tick(context(201, false), parameters());
        frames.frame = builder.sleeping().build();
        skill.tick(context(202, false), parameters());
        builder.sleeping = false;
        builder.sleepingPosition = Optional.empty();
        builder.darkOutside = true;
        builder.currentGameTime++;
        builder.clockTime++;
        frames.frame = builder.build();
        SkillTickResult early = skill.tick(
                context(203, false),
                parameters()
        );
        assertEquals(
                "sleep_in_observed_bed.woke_before_dawn",
                early.failure().orElseThrow().code()
        );
    }

    @Test
    void reportsRejectedInteractionAndSleepConfirmationTimeout() {
        var builder = new SleepSkillTestFixtures.FrameBuilder();
        var frames = new SleepSkillTestFixtures.MutableFrames(
                builder.build()
        );
        var actuator = new SleepSkillTestFixtures.RecordingActuator();
        actuator.useOutcome = ActionOutcome.TARGET_OUT_OF_REACH;
        var skill = skill(frames, actuator);
        skill.start(context(100, false), parameters());
        assertEquals(
                "sleep_in_observed_bed.use_target_out_of_reach",
                skill.tick(context(101, false), parameters())
                        .failure()
                        .orElseThrow()
                        .code()
        );

        actuator = new SleepSkillTestFixtures.RecordingActuator();
        skill = new SleepInObservedBedSkill(
                PLAYER_ID,
                actuator,
                frames,
                new SleepSkillPolicy(
                        10,
                        3.25,
                        2,
                        10,
                        0.25,
                        0.5,
                        0.49,
                        0.0
                )
        );
        skill.start(context(200, false), parameters());
        skill.tick(context(201, false), parameters());
        assertEquals(
                "sleep_in_observed_bed.sleep_not_started",
                skill.tick(context(203, false), parameters())
                        .failure()
                        .orElseThrow()
                        .code()
        );
    }

    private static SleepInObservedBedSkill skill(
            SleepSkillTestFixtures.MutableFrames frames,
            SleepSkillTestFixtures.RecordingActuator actuator
    ) {
        return new SleepInObservedBedSkill(
                PLAYER_ID,
                actuator,
                frames,
                SleepSkillPolicy.defaults()
        );
    }

    private static void assertFailure(
            SleepSkillTestFixtures.FrameBuilder builder,
            boolean hardcore,
            String suffix
    ) {
        var skill = skill(
                new SleepSkillTestFixtures.MutableFrames(
                        builder.build()
                ),
                new SleepSkillTestFixtures.RecordingActuator()
        );
        assertEquals(
                "sleep_in_observed_bed." + suffix,
                skill.preconditions(
                        context(100, hardcore),
                        parameters()
                ).orElseThrow().code()
        );
    }

    private static SkillContext context(
            long tick,
            boolean hardcore
    ) {
        return new SkillContext(1, 1, tick, hardcore, true, 0.0);
    }
}
