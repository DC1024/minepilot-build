package dev.mcai.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class KnownSkillArgumentCanonicalizerTest {
    @Test
    void removesOnlyRedundantSurveySampleSequence() {
        final DecisionEnvelope decision = decision(
                "survey_surroundings",
                List.of(
                        new SkillArgument(
                                "dimension",
                                "minecraft:overworld"
                        ),
                        new SkillArgument("sampleSequence", "276"),
                        new SkillArgument("horizontalSteps", "8"),
                        new SkillArgument("includeVertical", "true")
                )
        );

        assertEquals(
                List.of(
                        new SkillArgument(
                                "dimension",
                                "minecraft:overworld"
                        ),
                        new SkillArgument("horizontalSteps", "8"),
                        new SkillArgument("includeVertical", "true")
                ),
                KnownSkillArgumentCanonicalizer
                        .canonicalize(decision)
                        .typedArguments()
        );
    }

    @Test
    void preservesUnknownFieldsForStrictValidation() {
        final DecisionEnvelope survey = decision(
                "survey_surroundings",
                List.of(new SkillArgument("invented", "value"))
        );
        final DecisionEnvelope movement = decision(
                "move_to",
                List.of(new SkillArgument("sampleSequence", "4"))
        );

        assertEquals(
                survey,
                KnownSkillArgumentCanonicalizer.canonicalize(survey)
        );
        assertEquals(
                movement,
                KnownSkillArgumentCanonicalizer.canonicalize(movement)
        );
    }

    @Test
    void removesAllProviderFillersFromExactParameterlessCompounds() {
        for (String skillName : List.of(
                "prepare_basic_crafting",
                "prepare_stone_tools",
                "prepare_iron_toolkit",
                "establish_foundation_workstations",
                "prepare_foundation_shelter_materials",
                "secure_visible_food_reserve",
                "secure_nether_blaze_material",
                "secure_ender_pearl_reserve",
                "triangulate_stronghold_search_area",
                "reach_observed_stronghold",
                "search_stronghold_portal_room",
                "activate_observed_end_portal",
                "fight_ender_dragon",
                "find_and_enter_observed_portal",
                "return_via_verified_portal"
        )) {
            final DecisionEnvelope decision = decision(
                    skillName,
                    List.of(
                            new SkillArgument("hand", "main_hand"),
                            new SkillArgument(
                                    "dimension",
                                    "minecraft:overworld"
                            ),
                            new SkillArgument(
                                    "sampleSequence",
                                    "956"
                            )
                    )
            );

            assertEquals(
                    List.of(),
                    KnownSkillArgumentCanonicalizer
                            .canonicalize(decision)
                            .typedArguments(),
                    skillName
            );
        }
    }

    @Test
    void removesOnlyRedundantDimensionFromExactObservationSkills() {
        final DecisionEnvelope hunt = decision(
                "hunt_observed_food_animal",
                List.of(
                        new SkillArgument(
                                "dimension",
                                "minecraft:overworld"
                        ),
                        new SkillArgument("sampleSequence", "379"),
                        new SkillArgument("observationId", "visible-0"),
                        new SkillArgument(
                                "expectedItemId",
                                "minecraft:beef"
                        ),
                        new SkillArgument("maximumTicks", "300")
                )
        );
        final DecisionEnvelope collect = decision(
                "collect_observed_item",
                List.of(
                        new SkillArgument(
                                "dimension",
                                "minecraft:overworld"
                        ),
                        new SkillArgument("sampleSequence", "427"),
                        new SkillArgument("observationId", "visible-1"),
                        new SkillArgument("maximumTicks", "300")
                )
        );

        assertEquals(
                hunt.typedArguments().subList(
                        1,
                        hunt.typedArguments().size()
                ),
                KnownSkillArgumentCanonicalizer
                        .canonicalize(hunt)
                        .typedArguments()
        );
        assertEquals(
                collect.typedArguments().subList(
                        1,
                        collect.typedArguments().size()
                ),
                KnownSkillArgumentCanonicalizer
                        .canonicalize(collect)
                        .typedArguments()
        );
    }

    @Test
    void preservesDimensionOnSkillsThatActuallyDeclareIt() {
        final DecisionEnvelope gather = decision(
                "gather_visible_block_cluster",
                List.of(new SkillArgument(
                        "dimension",
                        "minecraft:overworld"
                ))
        );

        assertEquals(
                gather,
                KnownSkillArgumentCanonicalizer.canonicalize(gather)
        );
    }

    @Test
    void splitsKnownCombinedConversationPlanEnums() {
        final DecisionEnvelope combined = conversationDecision(List.of(
                new SkillArgument(
                        "goalRouteProfile",
                        "FOUNDATION/LOG_STORAGE_DISTRIBUTED"
                ),
                new SkillArgument(
                        "goalTerminalMilestone",
                        "NONE"
                )
        ));

        assertEquals(
                List.of(
                        new SkillArgument(
                                "goalRouteProfile",
                                "FOUNDATION"
                        ),
                        new SkillArgument(
                                "goalTerminalMilestone",
                                "LOG_STORAGE_DISTRIBUTED"
                        )
                ),
                KnownSkillArgumentCanonicalizer
                        .canonicalize(combined)
                        .typedArguments()
        );
    }

    @Test
    void rejectsAmbiguousOrUnknownCombinedConversationPlanEnums() {
        final DecisionEnvelope conflicting = conversationDecision(List.of(
                new SkillArgument(
                        "goalRouteProfile",
                        "FOUNDATION/LOG_STORAGE_DISTRIBUTED"
                ),
                new SkillArgument(
                        "goalTerminalMilestone",
                        "IRON_OBTAINED"
                )
        ));
        final DecisionEnvelope unknown = conversationDecision(List.of(
                new SkillArgument(
                        "goalRouteProfile",
                        "FOUNDATION/INVENTED"
                ),
                new SkillArgument(
                        "goalTerminalMilestone",
                        "NONE"
                )
        ));

        assertEquals(
                conflicting,
                KnownSkillArgumentCanonicalizer.canonicalize(conflicting)
        );
        assertEquals(
                unknown,
                KnownSkillArgumentCanonicalizer.canonicalize(unknown)
        );
    }

    private static DecisionEnvelope decision(
            final String skillName,
            final List<SkillArgument> arguments
    ) {
        return new DecisionEnvelope(
                "request-1",
                1,
                1,
                DecisionKind.START_SKILL,
                skillName,
                arguments,
                RequestedObservation.none(),
                "",
                0.8
        );
    }

    private static DecisionEnvelope conversationDecision(
            final List<SkillArgument> arguments
    ) {
        return new DecisionEnvelope(
                "conversation-1",
                1,
                1,
                DecisionKind.ASK_PLAYER,
                "",
                arguments,
                RequestedObservation.none(),
                "",
                0.8
        );
    }
}
