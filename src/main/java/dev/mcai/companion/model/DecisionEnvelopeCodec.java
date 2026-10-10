package dev.mcai.companion.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded codec for the sole model-to-game decision type.
 *
 * <p>Every authoritative field is mandatory and strongly typed. Unknown
 * members are ignored because several nominally schema-capable providers add
 * harmless explanation metadata. Unknown data never reaches the decision
 * object, while duplicate members, invalid JSON, missing required members,
 * and wrong types remain fail-closed.</p>
 */
public final class DecisionEnvelopeCodec {
    public static final int MAX_DECISION_JSON_CHARS = 32_768;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_NODES = 2_048;

    private static final Set<String> REQUIRED_ROOT_FIELDS = Set.of(
            "requestId",
            "observedWorldRevision",
            "goalRevision",
            "decision",
            "confidence"
    );
    private static final Set<String> ARGUMENT_FIELDS = Set.of("name", "value");
    private static final Set<String> OBSERVATION_FIELDS = Set.of("kind", "reason");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final JsonObject SCHEMA = JsonParser.parseString("""
            {
              "type": "object",
              "properties": {
                "requestId": { "type": "string" },
                "observedWorldRevision": { "type": "integer", "minimum": 0 },
                "goalRevision": { "type": "integer", "minimum": 0 },
                "decision": {
                  "type": "string",
                  "enum": [
                    "CONTINUE",
                    "START_SKILL",
                    "REPLAN",
                    "ASK_PLAYER",
                    "COMPLETE_GOAL",
                    "SAFE_IDLE"
                  ]
                },
                "skillName": { "type": "string" },
                "typedArguments": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": { "type": "string" },
                      "value": { "type": "string" }
                    },
                    "required": ["name", "value"],
                    "additionalProperties": false
                  }
                },
                "requestedObservation": {
                  "type": "object",
                  "properties": {
                    "kind": {
                      "type": "string",
                      "enum": [
                        "NONE",
                        "SEMANTIC_REFRESH",
                        "SCREENSHOT_LOW",
                        "SCREENSHOT_HIGH_CROP"
                      ]
                    },
                    "reason": { "type": "string" }
                  },
                  "required": ["kind", "reason"],
                  "additionalProperties": false
                },
                "optionalSpeech": { "type": "string" },
                "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
              },
              "required": [
                "requestId",
                "observedWorldRevision",
                "goalRevision",
                "decision",
                "skillName",
                "typedArguments",
                "requestedObservation",
                "optionalSpeech",
                "confidence"
              ],
              "additionalProperties": false
            }
            """).getAsJsonObject();

    public DecisionEnvelope decode(String json) throws DecisionValidationException {
        final JsonElement parsed;
        try {
            parsed = BoundedJsonParser.parse(
                    json,
                    MAX_DECISION_JSON_CHARS,
                    MAX_DEPTH,
                    MAX_NODES
            );
        } catch (IOException | RuntimeException exception) {
            throw new DecisionValidationException("invalid_json", "The model returned invalid JSON");
        }

        JsonObject root = requireObject(parsed, "decision envelope");
        requireFields(
                root,
                REQUIRED_ROOT_FIELDS,
                "decision envelope"
        );
        List<SkillArgument> arguments = root.has("typedArguments")
                ? decodeArguments(
                    requireArray(
                        root.get("typedArguments"),
                        "typedArguments"
                    )
                )
                : List.of();
        RequestedObservation observation =
                root.has("requestedObservation")
                ? decodeObservation(
                    requireObject(
                        root.get("requestedObservation"),
                        "requestedObservation"
                    )
                )
                : RequestedObservation.none();

        return new DecisionEnvelope(
                requireString(root, "requestId"),
                requireLong(root, "observedWorldRevision"),
                requireLong(root, "goalRevision"),
                requireEnum(root, "decision", DecisionKind.class),
                optionalString(root, "skillName"),
                arguments,
                observation,
                optionalString(root, "optionalSpeech"),
                requireDouble(root, "confidence")
        );
    }

    public String encode(DecisionEnvelope envelope) {
        JsonObject root = new JsonObject();
        root.addProperty("requestId", envelope.requestId());
        root.addProperty("observedWorldRevision", envelope.observedWorldRevision());
        root.addProperty("goalRevision", envelope.goalRevision());
        root.addProperty("decision", envelope.decision().name());
        root.addProperty("skillName", envelope.skillName());

        JsonArray arguments = new JsonArray();
        for (SkillArgument argument : envelope.typedArguments()) {
            JsonObject encodedArgument = new JsonObject();
            encodedArgument.addProperty("name", argument.name());
            encodedArgument.addProperty("value", argument.value());
            arguments.add(encodedArgument);
        }
        root.add("typedArguments", arguments);

        JsonObject observation = new JsonObject();
        observation.addProperty("kind", envelope.requestedObservation().kind().name());
        observation.addProperty("reason", envelope.requestedObservation().reason());
        root.add("requestedObservation", observation);
        root.addProperty("optionalSpeech", envelope.optionalSpeech());
        root.addProperty("confidence", envelope.confidence());
        return GSON.toJson(root);
    }

    public JsonObject schema() {
        return SCHEMA.deepCopy();
    }

    /**
     * Narrows the provider-side schema to the same per-request skill
     * allow-list enforced again by {@link DecisionEnvelopeValidator}.
     *
     * <p>The empty string remains legal because every non-START_SKILL
     * decision must carry an empty skill payload. Provider enforcement is
     * only an early error-prevention layer; the local validator remains the
     * authority.</p>
     */
    public JsonObject schema(final DecisionContext context) {
        final DecisionContext current = Objects.requireNonNull(
                context,
                "context"
        );
        final JsonObject contextual = schema();
        final JsonObject skillName = contextual
                .getAsJsonObject("properties")
                .getAsJsonObject("skillName");
        final JsonArray admitted = new JsonArray();
        admitted.add("");
        current.availableSkills().keySet().stream()
                .sorted()
                .forEach(admitted::add);
        skillName.add("enum", admitted);
        return contextual;
    }

    public String schemaJson() {
        return GSON.toJson(SCHEMA);
    }

    private static List<SkillArgument> decodeArguments(JsonArray array)
            throws DecisionValidationException {
        List<SkillArgument> arguments = new ArrayList<>(array.size());
        for (JsonElement value : array) {
            JsonObject object = requireObject(value, "skill argument");
            requireFields(object, ARGUMENT_FIELDS, "skill argument");
            arguments.add(new SkillArgument(
                    requireString(object, "name"),
                    requireString(object, "value")
            ));
        }
        return List.copyOf(arguments);
    }

    private static RequestedObservation decodeObservation(JsonObject object)
            throws DecisionValidationException {
        requireFields(object, OBSERVATION_FIELDS, "requestedObservation");
        final ObservationKind kind = requireEnum(
                object,
                "kind",
                ObservationKind.class
        );
        final String reason = requireString(object, "reason");
        /*
         * Several otherwise schema-capable providers populate a harmless
         * explanatory string even when the enum is NONE. The reason has no
         * authority and is never consumed in that state, so canonicalize it
         * at the wire boundary instead of failing an otherwise valid action.
         * Non-NONE requests remain subject to the full local validator.
         */
        return new RequestedObservation(
                kind,
                kind == ObservationKind.NONE ? "" : reason
        );
    }

    private static JsonObject requireObject(JsonElement element, String name)
            throws DecisionValidationException {
        if (element == null || !element.isJsonObject()) {
            throw invalidType(name, "object");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonElement element, String name)
            throws DecisionValidationException {
        if (element == null || !element.isJsonArray()) {
            throw invalidType(name, "array");
        }
        return element.getAsJsonArray();
    }

    private static String requireString(JsonObject object, String field)
            throws DecisionValidationException {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()) {
            throw invalidType(field, "string");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) {
            throw invalidType(field, "string");
        }
        return primitive.getAsString();
    }

    private static long requireLong(JsonObject object, String field)
            throws DecisionValidationException {
        BigDecimal value = requireNumber(object, field);
        try {
            return value.longValueExact();
        } catch (ArithmeticException exception) {
            throw invalidType(field, "integer");
        }
    }

    private static double requireDouble(JsonObject object, String field)
            throws DecisionValidationException {
        double value = requireNumber(object, field).doubleValue();
        if (!Double.isFinite(value)) {
            throw invalidType(field, "finite number");
        }
        return value;
    }

    private static BigDecimal requireNumber(JsonObject object, String field)
            throws DecisionValidationException {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()) {
            throw invalidType(field, "number");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            throw invalidType(field, "number");
        }
        try {
            return primitive.getAsBigDecimal();
        } catch (NumberFormatException exception) {
            throw invalidType(field, "number");
        }
    }

    private static <E extends Enum<E>> E requireEnum(
            JsonObject object,
            String field,
            Class<E> enumType
    ) throws DecisionValidationException {
        String value = requireString(object, field);
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw new DecisionValidationException(
                    "unknown_enum_value",
                    field + " contains an unsupported value"
            );
        }
    }

    private static void requireFields(
            JsonObject object,
            Set<String> fields,
            String name
    )
            throws DecisionValidationException {
        if (!object.keySet().containsAll(fields)) {
            throw new DecisionValidationException(
                    "missing_fields",
                    name + " must contain every documented field"
            );
        }
    }

    private static String optionalString(
            final JsonObject object,
            final String field
    ) throws DecisionValidationException {
        return object.has(field)
                ? requireString(object, field)
                : "";
    }

    private static DecisionValidationException invalidType(String field, String expected) {
        return new DecisionValidationException(
                "invalid_field_type",
                field + " must be a JSON " + expected
        );
    }
}
