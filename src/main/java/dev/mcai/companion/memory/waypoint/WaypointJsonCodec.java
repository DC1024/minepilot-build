package dev.mcai.companion.memory.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import dev.mcai.companion.waypoint.DimensionRef;
import dev.mcai.companion.waypoint.Waypoint;
import dev.mcai.companion.waypoint.WaypointAabb;
import dev.mcai.companion.waypoint.WaypointGeometry;
import dev.mcai.companion.waypoint.WaypointGeometryType;
import dev.mcai.companion.waypoint.WaypointMovingTarget;
import dev.mcai.companion.waypoint.WaypointPoint;
import dev.mcai.companion.waypoint.WaypointPolygon;
import dev.mcai.companion.waypoint.WaypointPolyline;
import dev.mcai.companion.waypoint.WaypointProvenance;
import dev.mcai.companion.waypoint.WaypointStatus;

/**
 * Versioned JSON representation for durable waypoint records.
 */
public final class WaypointJsonCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAXIMUM_JSON_CHARS = 1_048_576;

    private static final Gson GSON = new GsonBuilder()
        .disableHtmlEscaping()
        .serializeNulls()
        .create();
    private static final Set<String> ROOT_FIELDS = Set.of(
        "schemaVersion",
        "id",
        "worldId",
        "dimension",
        "geometry",
        "name",
        "aliases",
        "category",
        "creatorId",
        "source",
        "provenance",
        "confidence",
        "revision",
        "status",
        "createdAt",
        "updatedAt",
        "lastVerifiedAt",
        "ttl"
    );
    private static final Set<String> POINT_FIELDS = Set.of("x", "y", "z");
    private static final Set<String> TTL_FIELDS = Set.of("seconds", "nanos");

    public String encode(Waypoint waypoint) {
        if (waypoint == null) {
            throw new WaypointCodecException("Waypoint must not be null");
        }
        final JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("id", waypoint.id().toString());
        root.addProperty("worldId", waypoint.worldId().toString());
        root.addProperty("dimension", waypoint.dimension().id());
        root.add("geometry", encodeGeometryObject(waypoint.geometry()));
        root.addProperty("name", waypoint.name());

        final JsonArray aliases = new JsonArray();
        waypoint.aliases().forEach(aliases::add);
        root.add("aliases", aliases);

        root.addProperty("category", waypoint.category());
        root.addProperty("creatorId", waypoint.creatorId().toString());
        root.addProperty("source", waypoint.source());
        root.addProperty("provenance", waypoint.provenance().name());
        root.addProperty("confidence", waypoint.confidence());
        root.addProperty("revision", waypoint.revision());
        root.addProperty("status", waypoint.status().name());
        root.addProperty("createdAt", waypoint.createdAt().toString());
        root.addProperty("updatedAt", waypoint.updatedAt().toString());
        root.add(
            "lastVerifiedAt",
            waypoint.lastVerifiedAt()
                .<JsonElement>map(value -> new JsonPrimitive(value.toString()))
                .orElse(JsonNull.INSTANCE)
        );
        root.add(
            "ttl",
            waypoint.ttl().<JsonElement>map(WaypointJsonCodec::encodeDuration)
                .orElse(JsonNull.INSTANCE)
        );
        return boundedJson(root);
    }

    public Waypoint decode(String json) {
        final JsonObject root = parseObject(json, "waypoint");
        requireExactFields(root, ROOT_FIELDS, "waypoint");
        if (requireInt(root, "schemaVersion") != SCHEMA_VERSION) {
            throw new WaypointCodecException("Unsupported waypoint schema version");
        }

        try {
            final Set<String> aliases = decodeAliases(requireArray(root, "aliases"));
            return new Waypoint(
                UUID.fromString(requireString(root, "id")),
                UUID.fromString(requireString(root, "worldId")),
                DimensionRef.parse(requireString(root, "dimension")),
                decodeGeometryObject(requireObject(root, "geometry")),
                requireString(root, "name"),
                aliases,
                requireString(root, "category"),
                UUID.fromString(requireString(root, "creatorId")),
                requireString(root, "source"),
                requireEnum(root, "provenance", WaypointProvenance.class),
                requireDouble(root, "confidence"),
                requireLong(root, "revision"),
                requireEnum(root, "status", WaypointStatus.class),
                Instant.parse(requireString(root, "createdAt")),
                Instant.parse(requireString(root, "updatedAt")),
                optionalInstant(root.get("lastVerifiedAt"), "lastVerifiedAt"),
                optionalDuration(root.get("ttl"))
            );
        } catch (WaypointCodecException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WaypointCodecException("Invalid waypoint JSON value", exception);
        }
    }

    public String encodeGeometry(WaypointGeometry geometry) {
        if (geometry == null) {
            throw new WaypointCodecException("Geometry must not be null");
        }
        return boundedJson(encodeGeometryObject(geometry));
    }

    public WaypointGeometry decodeGeometry(String json) {
        try {
            return decodeGeometryObject(parseObject(json, "geometry"));
        } catch (WaypointCodecException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WaypointCodecException("Invalid geometry JSON value", exception);
        }
    }

    private static JsonObject encodeGeometryObject(WaypointGeometry geometry) {
        final JsonObject object = new JsonObject();
        object.addProperty("type", geometry.type().name());
        switch (geometry) {
            case WaypointPoint point -> addPointCoordinates(object, point);
            case WaypointAabb bounds -> {
                object.add("minimum", encodePoint(bounds.minimum()));
                object.add("maximum", encodePoint(bounds.maximum()));
            }
            case WaypointPolygon polygon ->
                object.add("vertices", encodePoints(polygon.vertices()));
            case WaypointPolyline polyline ->
                object.add("points", encodePoints(polyline.points()));
            case WaypointMovingTarget target -> {
                object.addProperty("targetId", target.targetId().toString());
                object.add("lastKnownPosition", encodePoint(target.lastKnownPosition()));
                object.addProperty("observedAt", target.observedAt().toString());
            }
        }
        return object;
    }

    private static WaypointGeometry decodeGeometryObject(JsonObject object) {
        final WaypointGeometryType type = requireEnum(
            object,
            "type",
            WaypointGeometryType.class
        );
        return switch (type) {
            case POINT -> {
                requireExactFields(object, Set.of("type", "x", "y", "z"), "point geometry");
                yield decodePointCoordinates(object);
            }
            case AABB -> {
                requireExactFields(
                    object,
                    Set.of("type", "minimum", "maximum"),
                    "AABB geometry"
                );
                yield new WaypointAabb(
                    decodePoint(requireObject(object, "minimum")),
                    decodePoint(requireObject(object, "maximum"))
                );
            }
            case POLYGON -> {
                requireExactFields(object, Set.of("type", "vertices"), "polygon geometry");
                yield new WaypointPolygon(decodePoints(requireArray(object, "vertices")));
            }
            case POLYLINE -> {
                requireExactFields(object, Set.of("type", "points"), "polyline geometry");
                yield new WaypointPolyline(decodePoints(requireArray(object, "points")));
            }
            case MOVING_TARGET -> {
                requireExactFields(
                    object,
                    Set.of("type", "targetId", "lastKnownPosition", "observedAt"),
                    "moving target geometry"
                );
                yield new WaypointMovingTarget(
                    UUID.fromString(requireString(object, "targetId")),
                    decodePoint(requireObject(object, "lastKnownPosition")),
                    Instant.parse(requireString(object, "observedAt"))
                );
            }
        };
    }

    private static JsonObject encodePoint(WaypointPoint point) {
        final JsonObject object = new JsonObject();
        addPointCoordinates(object, point);
        return object;
    }

    private static void addPointCoordinates(JsonObject object, WaypointPoint point) {
        object.addProperty("x", point.x());
        object.addProperty("y", point.y());
        object.addProperty("z", point.z());
    }

    private static WaypointPoint decodePoint(JsonObject object) {
        requireExactFields(object, POINT_FIELDS, "point");
        return decodePointCoordinates(object);
    }

    private static WaypointPoint decodePointCoordinates(JsonObject object) {
        return new WaypointPoint(
            requireDouble(object, "x"),
            requireDouble(object, "y"),
            requireDouble(object, "z")
        );
    }

    private static JsonArray encodePoints(List<WaypointPoint> points) {
        final JsonArray array = new JsonArray();
        points.forEach(point -> array.add(encodePoint(point)));
        return array;
    }

    private static List<WaypointPoint> decodePoints(JsonArray array) {
        final List<WaypointPoint> points = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new WaypointCodecException("Geometry point must be an object");
            }
            points.add(decodePoint(element.getAsJsonObject()));
        }
        return List.copyOf(points);
    }

    private static JsonObject encodeDuration(Duration duration) {
        final JsonObject object = new JsonObject();
        object.addProperty("seconds", duration.getSeconds());
        object.addProperty("nanos", duration.getNano());
        return object;
    }

    private static Optional<Duration> optionalDuration(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!element.isJsonObject()) {
            throw new WaypointCodecException("ttl must be an object or null");
        }
        final JsonObject object = element.getAsJsonObject();
        requireExactFields(object, TTL_FIELDS, "ttl");
        final long seconds = requireLong(object, "seconds");
        final int nanos = requireInt(object, "nanos");
        if (nanos < 0 || nanos > 999_999_999) {
            throw new WaypointCodecException("ttl nanos are out of range");
        }
        return Optional.of(Duration.ofSeconds(seconds, nanos));
    }

    private static Optional<Instant> optionalInstant(JsonElement element, String field) {
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new WaypointCodecException(field + " must be a string or null");
        }
        return Optional.of(Instant.parse(element.getAsString()));
    }

    private static Set<String> decodeAliases(JsonArray array) {
        final Set<String> aliases = new LinkedHashSet<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new WaypointCodecException("Waypoint alias must be a string");
            }
            if (!aliases.add(element.getAsString())) {
                throw new WaypointCodecException("Waypoint aliases must be unique");
            }
        }
        return aliases;
    }

    private static JsonObject parseObject(String json, String label) {
        if (json == null || json.length() > MAXIMUM_JSON_CHARS) {
            throw new WaypointCodecException(label + " JSON exceeds bounds");
        }
        try {
            final JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new WaypointCodecException(label + " JSON must be an object");
            }
            return parsed.getAsJsonObject();
        } catch (WaypointCodecException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WaypointCodecException("Invalid " + label + " JSON", exception);
        }
    }

    private static String boundedJson(JsonObject object) {
        final String json = GSON.toJson(object);
        if (json.length() > MAXIMUM_JSON_CHARS) {
            throw new WaypointCodecException("Encoded waypoint JSON exceeds bounds");
        }
        return json;
    }

    private static JsonObject requireObject(JsonObject parent, String field) {
        final JsonElement element = parent.get(field);
        if (element == null || !element.isJsonObject()) {
            throw new WaypointCodecException(field + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject parent, String field) {
        final JsonElement element = parent.get(field);
        if (element == null || !element.isJsonArray()) {
            throw new WaypointCodecException(field + " must be an array");
        }
        return element.getAsJsonArray();
    }

    private static String requireString(JsonObject object, String field) {
        final JsonElement element = object.get(field);
        if (element == null
            || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isString()) {
            throw new WaypointCodecException(field + " must be a string");
        }
        return element.getAsString();
    }

    private static long requireLong(JsonObject object, String field) {
        final JsonPrimitive primitive = requireNumber(object, field);
        try {
            return primitive.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException exception) {
            throw new WaypointCodecException(field + " must be an integer", exception);
        }
    }

    private static int requireInt(JsonObject object, String field) {
        final long value = requireLong(object, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new WaypointCodecException(field + " exceeds integer bounds");
        }
        return (int) value;
    }

    private static double requireDouble(JsonObject object, String field) {
        final double value = requireNumber(object, field).getAsDouble();
        if (!Double.isFinite(value)) {
            throw new WaypointCodecException(field + " must be finite");
        }
        return value;
    }

    private static JsonPrimitive requireNumber(JsonObject object, String field) {
        final JsonElement element = object.get(field);
        if (element == null
            || !element.isJsonPrimitive()
            || !element.getAsJsonPrimitive().isNumber()) {
            throw new WaypointCodecException(field + " must be a number");
        }
        return element.getAsJsonPrimitive();
    }

    private static <E extends Enum<E>> E requireEnum(
        JsonObject object,
        String field,
        Class<E> type
    ) {
        final String value = requireString(object, field);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new WaypointCodecException(field + " contains an unknown value", exception);
        }
    }

    private static void requireExactFields(
        JsonObject object,
        Set<String> fields,
        String label
    ) {
        if (!object.keySet().equals(fields)) {
            throw new WaypointCodecException(
                label + " contains unexpected or missing fields: " + object.keySet()
            );
        }
    }
}
