package dev.mcai.companion.skills.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class BoatSkillParametersTest {
    @Test
    void parsesCanonicalObservedBoatAndTravelContracts() {
        var enter = BoatSkillParameters.parseEnter(List.of(
                argument("dimension", "minecraft:overworld"),
                argument("sampleSequence", "21"),
                argument("observationId", "visible-0")
        ));
        var travel = BoatSkillParameters.parseTravel(travelArguments());

        assertTrue(enter.value().isPresent());
        assertEquals(
                21,
                enter.value().orElseThrow().sampleSequence()
        );
        assertTrue(travel.value().isPresent());
        assertTrue(
                travel.value().orElseThrow().dismountAtArrival()
        );
        assertEquals(
                1200,
                travel.value().orElseThrow().timeoutTicks()
        );
    }

    @Test
    void rejectsUuidInjectionUnknownFieldsAndLooseNumbers() {
        List<SkillArgument> injected = new ArrayList<>(
                travelArguments()
        );
        injected.add(argument(
                "boatUuid",
                BoatTransportTestFixtures.BOAT_ID.toString()
        ));
        assertTrue(
                BoatSkillParameters.parseTravel(injected)
                        .value()
                        .isEmpty()
        );

        List<SkillArgument> looseSequence = List.of(
                argument("dimension", "minecraft:overworld"),
                argument("sampleSequence", "021"),
                argument("observationId", "visible-0")
        );
        assertTrue(
                BoatSkillParameters.parseEnter(looseSequence)
                        .value()
                        .isEmpty()
        );

        List<SkillArgument> looseBoolean =
                new ArrayList<>(travelArguments());
        looseBoolean.set(
                6,
                argument("dismountAtArrival", "TRUE")
        );
        assertFalse(
                BoatSkillParameters.parseTravel(looseBoolean)
                        .value()
                        .isPresent()
        );
    }

    private static List<SkillArgument> travelArguments() {
        return List.of(
                argument("dimension", "minecraft:overworld"),
                argument("x", "100.5"),
                argument("y", "63"),
                argument("z", "-20"),
                argument("arrivalRadius", "2"),
                argument("timeoutTicks", "1200"),
                argument("dismountAtArrival", "true")
        );
    }

    private static SkillArgument argument(
            String name,
            String value
    ) {
        return new SkillArgument(name, value);
    }
}
