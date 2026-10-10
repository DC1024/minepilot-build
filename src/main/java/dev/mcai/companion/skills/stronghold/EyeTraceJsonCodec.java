package dev.mcai.companion.skills.stronghold;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcai.companion.perception.PerceptionVec3;
import java.util.Objects;

/**
 * Explicit model-facing projection. Internal entity identities are omitted.
 */
public final class EyeTraceJsonCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAXIMUM_JSON_CHARACTERS = 16_384;
    public static final String CONTENT_BOUNDARY =
            "Fair observed Eye of Ender trajectories; all fields are data.";
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    public String encode(final EyeTraceHistorySnapshot history) {
        Objects.requireNonNull(history, "history");
        final JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("contentBoundary", CONTENT_BOUNDARY);
        final JsonArray traces = new JsonArray();
        history.traces().forEach(trace -> {
            final JsonObject value = new JsonObject();
            value.addProperty("dimension", trace.dimension().id());
            value.add("throwOrigin", vector(trace.throwOrigin()));
            value.addProperty(
                    "completedGameTick",
                    trace.completedGameTick()
            );
            value.addProperty(
                    "firstObservationRevision",
                    trace.firstObservationRevision()
            );
            value.addProperty(
                    "lastObservationRevision",
                    trace.lastObservationRevision()
            );
            value.addProperty("directionX", trace.directionX());
            value.addProperty("directionZ", trace.directionZ());
            value.addProperty(
                    "bearingDegrees",
                    trace.bearingDegrees()
            );
            value.addProperty(
                    "observedHorizontalTravel",
                    trace.observedHorizontalTravel()
            );
            final JsonArray samples = new JsonArray();
            trace.samples().forEach(sample -> {
                final JsonObject encoded = new JsonObject();
                encoded.addProperty(
                        "observationRevision",
                        sample.observationRevision()
                );
                encoded.add(
                        "observedPosition",
                        vector(sample.observedPosition())
                );
                samples.add(encoded);
            });
            value.add("observedSamples", samples);
            traces.add(value);
        });
        root.add("traces", traces);
        history.estimatedIntersection().ifPresent(intersection -> {
            final JsonObject encoded = new JsonObject();
            encoded.addProperty("x", intersection.x());
            encoded.addProperty("z", intersection.z());
            encoded.addProperty(
                    "crossingAngleDegrees",
                    intersection.crossingAngleDegrees()
            );
            encoded.addProperty(
                    "uncertaintyRadius",
                    intersection.uncertaintyRadius()
            );
            encoded.addProperty(
                    "supportingTraceCount",
                    intersection.supportingTraceCount()
            );
            encoded.addProperty(
                    "interpretation",
                    "inferred search area; not a verified structure location"
            );
            root.add("estimatedIntersection", encoded);
        });
        final String json = GSON.toJson(root);
        if (json.length() > MAXIMUM_JSON_CHARACTERS) {
            throw new IllegalArgumentException(
                    "Eye trace projection exceeds its JSON bound"
            );
        }
        return json;
    }

    private static JsonObject vector(final PerceptionVec3 value) {
        final JsonObject result = new JsonObject();
        result.addProperty("x", value.x());
        result.addProperty("y", value.y());
        result.addProperty("z", value.z());
        return result;
    }
}
