package dev.mcai.companion.skills.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.LocalPlanningBudget;
import dev.mcai.companion.skill.SkillParameterResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CoreSkillParametersTest {
    @Test
    void moveToRequiresExactTypedContract() {
        SkillParameterResult<MoveToParameters> parsed =
                CoreSkillParameters.parseMoveTo(List.of(
                        new SkillArgument("dimension", "minecraft:overworld"),
                        new SkillArgument("x", "12.5"),
                        new SkillArgument("y", "64"),
                        new SkillArgument("z", "-3.25"),
                        new SkillArgument("arrivalRadius", "1.5")
                ));

        MoveToParameters value = parsed.value().orElseThrow();
        assertEquals("minecraft:overworld", value.dimension().id());
        assertEquals(12.5, value.x());
        assertEquals(1.5, value.arrivalRadius());

        assertFalse(CoreSkillParameters.parseMoveTo(List.of(
                new SkillArgument("dimension", "minecraft:overworld"),
                new SkillArgument("x", "NaN"),
                new SkillArgument("y", "64"),
                new SkillArgument("z", "0"),
                new SkillArgument("arrivalRadius", "1")
        )).value().isPresent());
        assertFalse(CoreSkillParameters.parseMoveTo(List.of(
                new SkillArgument("dimension", "minecraft:overworld"),
                new SkillArgument("x", "0"),
                new SkillArgument("y", "64"),
                new SkillArgument("z", "0"),
                new SkillArgument("arrivalRadius", "1"),
                new SkillArgument("extra", "ignored")
        )).value().isPresent());
    }

    @Test
    void moveToAllowsPreciseConstructionDockingRadius() {
        assertTrue(CoreSkillParameters.parseMoveTo(List.of(
                new SkillArgument("dimension", "minecraft:overworld"),
                new SkillArgument("x", "12.5"),
                new SkillArgument("y", "64"),
                new SkillArgument("z", "-3.5"),
                new SkillArgument("arrivalRadius", "0.15")
        )).value().isPresent());
        assertFalse(CoreSkillParameters.parseMoveTo(List.of(
                new SkillArgument("dimension", "minecraft:overworld"),
                new SkillArgument("x", "12.5"),
                new SkillArgument("y", "64"),
                new SkillArgument("z", "-3.5"),
                new SkillArgument("arrivalRadius", "0.09")
        )).value().isPresent());
    }

    @Test
    void lookAndIdleRejectMalformedArguments() {
        assertTrue(CoreSkillParameters.parseLookAt(List.of(
                new SkillArgument("dimension", "minecraft:overworld"),
                new SkillArgument("x", "1"),
                new SkillArgument("y", "65"),
                new SkillArgument("z", "2")
        )).value().isPresent());
        assertFalse(CoreSkillParameters.parseLookAt(List.of(
                new SkillArgument("dimension", "overworld"),
                new SkillArgument("x", "1"),
                new SkillArgument("y", "65"),
                new SkillArgument("z", "2")
        )).value().isPresent());
        assertTrue(CoreSkillParameters.parseNone(List.of()).value().isPresent());
        assertFalse(CoreSkillParameters.parseNone(List.of(
                new SkillArgument("duration", "20")
        )).value().isPresent());
    }

    @Test
    void followEntityAcceptsOnlyOpaqueObservationReferences() {
        SkillParameterResult<FollowEntityParameters> parsed =
                CoreSkillParameters.parseFollowEntity(List.of(
                        new SkillArgument("observationId", "visible-0"),
                        new SkillArgument("sampleSequence", "42"),
                        new SkillArgument("followDistance", "2.5"),
                        new SkillArgument("lostGraceTicks", "100")
                ));

        FollowEntityParameters value = parsed.value().orElseThrow();
        assertEquals("visible-0", value.observationId());
        assertEquals(42, value.sampleSequence());
        assertEquals(0, value.observationIndex());

        assertFalse(CoreSkillParameters.parseFollowEntity(List.of(
                new SkillArgument("observationId", "visible-01"),
                new SkillArgument("sampleSequence", "42"),
                new SkillArgument("followDistance", "2.5"),
                new SkillArgument("lostGraceTicks", "100")
        )).value().isPresent());
        assertFalse(CoreSkillParameters.parseFollowEntity(List.of(
                new SkillArgument(
                        "entityId",
                        "00000000-0000-0000-0000-000000000456"
                ),
                new SkillArgument("sampleSequence", "42"),
                new SkillArgument("followDistance", "2.5"),
                new SkillArgument("lostGraceTicks", "100")
        )).value().isPresent());
    }

    @Test
    void policyKeepsPlannerInsideSkillTickBudget() {
        assertTrue(
                CoreSkillPolicy.defaults()
                        .planningBudget()
                        .maximumWallTime()
                        .compareTo(Duration.ofMillis(2)) <= 0
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CoreSkillPolicy(
                        new LocalPlanningBudget(100, Duration.ofMillis(3)),
                        4,
                        30.0F,
                        24,
                        12.0,
                        3.0,
                        0.1
                )
        );
    }
}
