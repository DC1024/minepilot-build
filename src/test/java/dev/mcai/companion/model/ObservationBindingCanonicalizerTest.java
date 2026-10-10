package dev.mcai.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class ObservationBindingCanonicalizerTest {
    private static final String OPEN_MENU = """
            {
              "sampleSequence": 45,
              "openMenu": {
                "containerId": 7,
                "stateId": 3,
                "slots": []
              }
            }
            """;

    @Test
    void suppliesOnlyOpaqueBindingsForMenuTransaction() {
        final DecisionEnvelope canonical =
                ObservationBindingCanonicalizer.canonicalize(
                        decision(
                                "transfer_menu_item",
                                List.of(
                                        argument("sourceSlot", "0"),
                                        argument("destinationSlot", "27"),
                                        argument("count", "3")
                                )
                        ),
                        OPEN_MENU
                );

        assertEquals(
                Map.of(
                        "sourceSlot", "0",
                        "destinationSlot", "27",
                        "count", "3",
                        "sampleSequence", "45",
                        "containerId", "7",
                        "stateId", "3"
                ),
                arguments(canonical)
        );
    }

    @Test
    void replacesForgedOpaqueBindingsButNotSemanticArguments() {
        final DecisionEnvelope canonical =
                ObservationBindingCanonicalizer.canonicalize(
                        decision(
                                "transfer_menu_item",
                                List.of(
                                        argument("sampleSequence", "999"),
                                        argument("containerId", "999"),
                                        argument("stateId", "999"),
                                        argument("sourceSlot", "4"),
                                        argument("destinationSlot", "30"),
                                        argument("count", "2")
                                )
                        ),
                        OPEN_MENU
                );

        assertEquals("45", arguments(canonical).get("sampleSequence"));
        assertEquals("7", arguments(canonical).get("containerId"));
        assertEquals("3", arguments(canonical).get("stateId"));
        assertEquals("4", arguments(canonical).get("sourceSlot"));
        assertEquals("30", arguments(canonical).get("destinationSlot"));
        assertEquals("2", arguments(canonical).get("count"));
    }

    @Test
    void doesNotInventMissingSemanticArguments() {
        final DecisionEnvelope canonical =
                ObservationBindingCanonicalizer.canonicalize(
                        decision(
                                "transfer_menu_item",
                                List.of(argument("count", "3"))
                        ),
                        OPEN_MENU
                );

        assertEquals(
                Map.of(
                        "count", "3",
                        "sampleSequence", "45",
                        "containerId", "7",
                        "stateId", "3"
                ),
                arguments(canonical)
        );
    }

    @Test
    void smeltBatchReceivesOnlyItsSampleBinding() {
        final DecisionEnvelope canonical =
                ObservationBindingCanonicalizer.canonicalize(
                        decision(
                                "smelt_menu_batch",
                                List.of(
                                        argument(
                                                "inputItemId",
                                                "minecraft:raw_iron"
                                        )
                                )
                        ),
                        OPEN_MENU
                );

        assertEquals(
                Map.of(
                        "inputItemId", "minecraft:raw_iron",
                        "sampleSequence", "45"
                ),
                arguments(canonical)
        );
    }

    @Test
    void leavesNonMenuSkillAndClosedMenuUnchanged() {
        final DecisionEnvelope movement = decision(
                "move_to",
                List.of(argument("x", "4"))
        );
        assertEquals(
                movement,
                ObservationBindingCanonicalizer.canonicalize(
                        movement,
                        OPEN_MENU
                )
        );

        final DecisionEnvelope transfer = decision(
                "transfer_menu_item",
                List.of(argument("count", "3"))
        );
        assertEquals(
                transfer,
                ObservationBindingCanonicalizer.canonicalize(
                        transfer,
                        "{\"sampleSequence\":45}"
                )
        );
    }

    private static DecisionEnvelope decision(
            final String skill,
            final List<SkillArgument> arguments
    ) {
        return new DecisionEnvelope(
                "request-1",
                2,
                1,
                DecisionKind.START_SKILL,
                skill,
                arguments,
                RequestedObservation.none(),
                "",
                0.8
        );
    }

    private static SkillArgument argument(
            final String name,
            final String value
    ) {
        return new SkillArgument(name, value);
    }

    private static Map<String, String> arguments(
            final DecisionEnvelope decision
    ) {
        return decision.typedArguments().stream().collect(
                Collectors.toMap(
                        SkillArgument::name,
                        SkillArgument::value
                )
        );
    }
}
