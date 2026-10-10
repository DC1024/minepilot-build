package dev.mcai.companion.model;

import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionEnvelopeCodecTest {
    private final DecisionEnvelopeCodec codec = new DecisionEnvelopeCodec();

    @Test
    void defaultsOnlyNonAuthoritativeOptionalMembers()
            throws Exception {
        final DecisionEnvelope decoded = codec.decode("""
                {
                  "requestId":"req-1",
                  "observedWorldRevision":7,
                  "goalRevision":9,
                  "decision":"REPLAN",
                  "confidence":0.75
                }
                """);

        assertEquals("", decoded.skillName());
        assertEquals(List.of(), decoded.typedArguments());
        assertEquals(RequestedObservation.none(),
                decoded.requestedObservation());
        assertEquals("", decoded.optionalSpeech());
    }
    private final DecisionEnvelopeValidator validator = new DecisionEnvelopeValidator();

    @Test
    void roundTripsAndValidatesSafeIdle() throws Exception {
        DecisionEnvelope expected = safeIdle("req-1", 7, 9);

        DecisionEnvelope decoded = codec.decode(codec.encode(expected));
        DecisionEnvelope validated = validator.validate(
                decoded,
                new DecisionContext("req-1", 7, 9, false, Map.of())
        );

        assertEquals(expected, validated);
    }

    @Test
    void schemaUsesCrossProviderStrictSubset() {
        String schema = codec.schemaJson();
        assertFalse(schema.contains("\"null\""));
        assertFalse(schema.contains("minLength"));
        assertFalse(schema.contains("maxLength"));
        assertFalse(schema.contains("minItems"));
        assertFalse(schema.contains("maxItems"));
        assertTrue(schema.contains("\"additionalProperties\":false"));
    }

    @Test
    void rejectsDuplicateButIgnoresNonAuthoritativeUnknownMembers()
            throws Exception {
        String valid = codec.encode(safeIdle("req-1", 1, 1));
        String duplicate = valid.replace(
                "\"requestId\":\"req-1\"",
                "\"requestId\":\"req-1\",\"requestId\":\"attacker\""
        );
        assertEquals(
                "invalid_json",
                assertThrows(
                        DecisionValidationException.class,
                        () -> codec.decode(duplicate)
                ).code()
        );

        String unknown = valid.substring(0, valid.length() - 1) + ",\"extra\":true}";
        assertEquals(safeIdle("req-1", 1, 1), codec.decode(unknown));
    }

    @Test
    void rejectsMarkdownAndTrailingText() {
        String valid = codec.encode(safeIdle("req-1", 1, 1));
        assertThrows(
                DecisionValidationException.class,
                () -> codec.decode("```json\n" + valid + "\n```")
        );
        assertThrows(
                DecisionValidationException.class,
                () -> codec.decode(valid + "\nExplanation")
        );
    }

    @Test
    void rejectsStaleRevisions() {
        DecisionValidationException exception = assertThrows(
                DecisionValidationException.class,
                () -> validator.validate(
                        safeIdle("req-1", 4, 5),
                        new DecisionContext("req-1", 5, 5, false, Map.of())
                )
        );
        assertEquals("stale_world", exception.code());
    }

    @Test
    void validatesRegisteredSkillArgumentsLocally() throws Exception {
        SkillArgumentValidator navigateSchema = arguments -> {
            if (arguments.equals(List.of(new SkillArgument("waypoint", "home")))) {
                return Optional.empty();
            }
            return Optional.of("navigate requires one known waypoint");
        };
        DecisionEnvelope start = new DecisionEnvelope(
                "req-2",
                10,
                11,
                DecisionKind.START_SKILL,
                "navigate",
                List.of(new SkillArgument("waypoint", "home")),
                RequestedObservation.none(),
                "",
                0.9
        );

        assertEquals(
                start,
                validator.validate(
                        start,
                        new DecisionContext(
                                "req-2",
                                10,
                                11,
                                false,
                                Map.of("navigate", navigateSchema)
                        )
                )
        );
    }

    @Test
    void canonicalizesKnownFoundationAliasOnlyForItsSoleCompound()
            throws Exception {
        DecisionEnvelope providerAlias = new DecisionEnvelope(
                "food-alias",
                10,
                11,
                DecisionKind.START_SKILL,
                "hunt_animal",
                List.of(
                        new SkillArgument("observationId", "visible-0"),
                        new SkillArgument("sampleSequence", "42"),
                        new SkillArgument("maximumTicks", "300")
                ),
                RequestedObservation.none(),
                "",
                0.8
        );
        DecisionEnvelope validated = validator.validate(
                providerAlias,
                new DecisionContext(
                        "food-alias",
                        10,
                        11,
                        false,
                        Map.of(
                                "secure_visible_food_reserve",
                                ignored -> Optional.empty()
                        )
                )
        );
        assertEquals("secure_visible_food_reserve", validated.skillName());
        assertTrue(validated.typedArguments().isEmpty());

        DecisionEnvelope stoneAlias = new DecisionEnvelope(
                "stone-alias",
                10,
                11,
                DecisionKind.START_SKILL,
                "gather_connected_cluster",
                List.of(
                        new SkillArgument("sampleSequence", "223"),
                        new SkillArgument("x", "-326"),
                        new SkillArgument("y", "-60"),
                        new SkillArgument("z", "-318")
                ),
                RequestedObservation.none(),
                "",
                0.8
        );
        DecisionEnvelope stoneValidated = validator.validate(
                stoneAlias,
                new DecisionContext(
                        "stone-alias",
                        10,
                        11,
                        false,
                        Map.of(
                                "prepare_stone_tools",
                                ignored -> Optional.empty()
                        )
                )
        );
        assertEquals("prepare_stone_tools", stoneValidated.skillName());
        assertTrue(stoneValidated.typedArguments().isEmpty());

        DecisionEnvelope rejected = new DecisionEnvelope(
                providerAlias.requestId(),
                providerAlias.observedWorldRevision(),
                providerAlias.goalRevision(),
                providerAlias.decision(),
                providerAlias.skillName(),
                providerAlias.typedArguments(),
                providerAlias.requestedObservation(),
                providerAlias.optionalSpeech(),
                providerAlias.confidence()
        );
        assertEquals(
                "unknown_skill",
                assertThrows(
                        DecisionValidationException.class,
                        () -> validator.validate(
                                rejected,
                                new DecisionContext(
                                        "food-alias",
                                        10,
                                        11,
                                        false,
                                        Map.of(
                                                "secure_visible_food_reserve",
                                                ignored -> Optional.empty(),
                                                "move_to",
                                                ignored -> Optional.empty()
                                        )
                                )
                        )
                ).code()
        );
    }

    @Test
    void rejectsDuplicateArgumentsAndReplansContinueWithoutActiveSkill()
            throws Exception {
        DecisionEnvelope duplicate = new DecisionEnvelope(
                "req-3",
                1,
                1,
                DecisionKind.START_SKILL,
                "navigate",
                List.of(
                        new SkillArgument("waypoint", "home"),
                        new SkillArgument("waypoint", "mine")
                ),
                RequestedObservation.none(),
                "",
                0.5
        );
        assertEquals(
                "duplicate_argument",
                assertThrows(
                        DecisionValidationException.class,
                        () -> validator.validate(
                                duplicate,
                                new DecisionContext(
                                        "req-3",
                                        1,
                                        1,
                                        false,
                                        Map.of("navigate", ignored -> Optional.empty())
                                )
                        )
                ).code()
        );

        DecisionEnvelope continueDecision = new DecisionEnvelope(
                "req-4",
                1,
                1,
                DecisionKind.CONTINUE,
                "",
                List.of(),
                RequestedObservation.none(),
                "",
                0.5
        );
        assertEquals(
                DecisionKind.REPLAN,
                validator.validate(
                        continueDecision,
                        new DecisionContext(
                                "req-4",
                                1,
                                1,
                                false,
                                Map.of()
                        )
                ).decision()
        );
    }

    @Test
    void boundedParserRejectsExcessiveDepth() {
        String deeplyNested = "[".repeat(20) + "0" + "]".repeat(20);
        assertThrows(
                DecisionValidationException.class,
                () -> codec.decode(deeplyNested)
        );
    }

    @Test
    void schemaIsReturnedAsDefensiveCopy() {
        JsonElement first = codec.schema();
        first.getAsJsonObject().addProperty("tampered", true);
        assertFalse(codec.schema().has("tampered"));
    }

    @Test
    void contextualSchemaEnumeratesOnlyCurrentlyAdmittedSkills() {
        final JsonElement schema = codec.schema(
                new DecisionContext(
                        "request-context-schema",
                        4,
                        8,
                        false,
                        Map.of(
                                "prepare_stone_tools",
                                ignored -> Optional.empty(),
                                "survey_surroundings",
                                ignored -> Optional.empty()
                        )
                )
        );
        final var admitted = schema.getAsJsonObject()
                .getAsJsonObject("properties")
                .getAsJsonObject("skillName")
                .getAsJsonArray("enum");

        assertEquals(3, admitted.size());
        assertEquals("", admitted.get(0).getAsString());
        assertEquals(
                "prepare_stone_tools",
                admitted.get(1).getAsString()
        );
        assertEquals(
                "survey_surroundings",
                admitted.get(2).getAsString()
        );
        assertFalse(
                codec.schema()
                        .getAsJsonObject("properties")
                        .getAsJsonObject("skillName")
                        .has("enum")
        );
    }

    @Test
    void canonicalizesHarmlessReasonForNoObservationRequest() throws Exception {
        String encoded = codec.encode(safeIdle("req-provider", 3, 4));
        String providerVariant = encoded.replace(
                "\"kind\":\"NONE\",\"reason\":\"\"",
                "\"kind\":\"NONE\",\"reason\":\"No additional observation is needed.\""
        );

        DecisionEnvelope decoded = codec.decode(providerVariant);

        assertEquals(ObservationKind.NONE, decoded.requestedObservation().kind());
        assertEquals("", decoded.requestedObservation().reason());
        assertEquals(
                decoded,
                validator.validate(
                        decoded,
                        new DecisionContext("req-provider", 3, 4, false, Map.of())
                )
        );
    }

    private static DecisionEnvelope safeIdle(String requestId, long worldRevision, long goalRevision) {
        return new DecisionEnvelope(
                requestId,
                worldRevision,
                goalRevision,
                DecisionKind.SAFE_IDLE,
                "",
                List.of(),
                RequestedObservation.none(),
                "",
                0.75
        );
    }
}
