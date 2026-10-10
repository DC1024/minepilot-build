package dev.mcai.companion.memory.transport;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skills.portal.PortalKind;
import dev.mcai.companion.waypoint.DimensionRef;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Strict, versioned durable representation for verified portal edges.
 */
public final class VerifiedPortalEdgeJsonCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAXIMUM_JSON_CHARS = 32_768;

    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .create();
    private static final Set<String> ROOT_FIELDS = Set.of(
        "schemaVersion",
        "edgeId",
        "worldId",
        "portalKind",
        "sourceDimension",
        "sourcePosition",
        "sourcePortalBlock",
        "destinationDimension",
        "destinationPosition",
        "destinationLandingBlock",
        "firstVerifiedAt",
        "lastVerifiedAt",
        "successCount",
        "revision"
    );
    private static final Set<String> VECTOR_FIELDS = Set.of("x", "y", "z");

    public String encode(VerifiedPortalEdge edge) {
        Objects.requireNonNull(edge, "edge");
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("edgeId", edge.edgeId());
        root.addProperty("worldId", edge.worldId().toString());
        root.addProperty("portalKind", edge.portalKind().name());
        root.addProperty("sourceDimension", edge.sourceDimension().id());
        root.add("sourcePosition", vector(edge.sourcePosition()));
        root.add("sourcePortalBlock", block(edge.sourcePortalBlock()));
        root.addProperty(
            "destinationDimension",
            edge.destinationDimension().id()
        );
        root.add("destinationPosition", vector(edge.destinationPosition()));
        root.add(
            "destinationLandingBlock",
            block(edge.destinationLandingBlock())
        );
        root.addProperty(
            "firstVerifiedAt",
            edge.firstVerifiedAt().toString()
        );
        root.addProperty(
            "lastVerifiedAt",
            edge.lastVerifiedAt().toString()
        );
        root.addProperty("successCount", edge.successCount());
        root.addProperty("revision", edge.revision());
        String json = GSON.toJson(root);
        if (json.length() > MAXIMUM_JSON_CHARS) {
            throw new VerifiedPortalEdgeCodecException(
                "Encoded portal edge exceeds bounds"
            );
        }
        return json;
    }

    public VerifiedPortalEdge decode(String json) {
        JsonObject root = parse(json);
        exactFields(root, ROOT_FIELDS, "portal edge");
        if (integer(root, "schemaVersion") != SCHEMA_VERSION) {
            throw new VerifiedPortalEdgeCodecException(
                "Unsupported portal edge schema version"
            );
        }
        try {
            return new VerifiedPortalEdge(
                string(root, "edgeId"),
                UUID.fromString(string(root, "worldId")),
                enumValue(root, "portalKind", PortalKind.class),
                DimensionRef.parse(string(root, "sourceDimension")),
                decodeVector(object(root, "sourcePosition")),
                decodeBlock(object(root, "sourcePortalBlock")),
                DimensionRef.parse(string(root, "destinationDimension")),
                decodeVector(object(root, "destinationPosition")),
                decodeBlock(object(root, "destinationLandingBlock")),
                Instant.parse(string(root, "firstVerifiedAt")),
                Instant.parse(string(root, "lastVerifiedAt")),
                longInteger(root, "successCount"),
                longInteger(root, "revision")
            );
        } catch (VerifiedPortalEdgeCodecException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VerifiedPortalEdgeCodecException(
                "Invalid portal edge JSON value",
                exception
            );
        }
    }

    private static JsonObject vector(PerceptionVec3 value) {
        JsonObject object = new JsonObject();
        object.addProperty("x", value.x());
        object.addProperty("y", value.y());
        object.addProperty("z", value.z());
        return object;
    }

    private static JsonObject block(BlockCoordinate value) {
        JsonObject object = new JsonObject();
        object.addProperty("x", value.x());
        object.addProperty("y", value.y());
        object.addProperty("z", value.z());
        return object;
    }

    private static PerceptionVec3 decodeVector(JsonObject object) {
        exactFields(object, VECTOR_FIELDS, "position");
        return new PerceptionVec3(
            number(object, "x"),
            number(object, "y"),
            number(object, "z")
        );
    }

    private static BlockCoordinate decodeBlock(JsonObject object) {
        exactFields(object, VECTOR_FIELDS, "block coordinate");
        return new BlockCoordinate(
            integer(object, "x"),
            integer(object, "y"),
            integer(object, "z")
        );
    }

    private static JsonObject parse(String json) {
        if (json == null || json.length() > MAXIMUM_JSON_CHARS) {
            throw new VerifiedPortalEdgeCodecException(
                "Portal edge JSON exceeds bounds"
            );
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new VerifiedPortalEdgeCodecException(
                    "Portal edge JSON must be an object"
                );
            }
            return parsed.getAsJsonObject();
        } catch (VerifiedPortalEdgeCodecException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VerifiedPortalEdgeCodecException(
                "Invalid portal edge JSON",
                exception
            );
        }
    }

    private static JsonObject object(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonObject()) {
            throw new VerifiedPortalEdgeCodecException(
                field + " must be an object"
            );
        }
        return value.getAsJsonObject();
    }

    private static String string(JsonObject root, String field) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive().isString()) {
            throw new VerifiedPortalEdgeCodecException(
                field + " must be a string"
            );
        }
        return value.getAsString();
    }

    private static double number(JsonObject root, String field) {
        JsonPrimitive value = numberPrimitive(root, field);
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) {
            throw new VerifiedPortalEdgeCodecException(
                field + " must be finite"
            );
        }
        return result;
    }

    private static long longInteger(JsonObject root, String field) {
        try {
            return numberPrimitive(root, field)
                .getAsBigDecimal()
                .longValueExact();
        } catch (ArithmeticException exception) {
            throw new VerifiedPortalEdgeCodecException(
                field + " must be an integer",
                exception
            );
        }
    }

    private static int integer(JsonObject root, String field) {
        long value = longInteger(root, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new VerifiedPortalEdgeCodecException(
                field + " exceeds integer bounds"
            );
        }
        return (int) value;
    }

    private static JsonPrimitive numberPrimitive(
        JsonObject root,
        String field
    ) {
        JsonElement value = root.get(field);
        if (value == null || !value.isJsonPrimitive()
            || !value.getAsJsonPrimitive().isNumber()) {
            throw new VerifiedPortalEdgeCodecException(
                field + " must be a number"
            );
        }
        return value.getAsJsonPrimitive();
    }

    private static <E extends Enum<E>> E enumValue(
        JsonObject root,
        String field,
        Class<E> type
    ) {
        try {
            return Enum.valueOf(type, string(root, field));
        } catch (IllegalArgumentException exception) {
            throw new VerifiedPortalEdgeCodecException(
                field + " contains an unknown value",
                exception
            );
        }
    }

    private static void exactFields(
        JsonObject object,
        Set<String> expected,
        String label
    ) {
        if (!object.keySet().equals(expected)) {
            throw new VerifiedPortalEdgeCodecException(
                label + " contains unexpected or missing fields: "
                    + object.keySet()
            );
        }
    }
}
