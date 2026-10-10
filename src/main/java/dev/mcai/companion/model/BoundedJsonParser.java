package dev.mcai.companion.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * Strict JSON parser with duplicate-key, depth, node-count, and input limits.
 *
 * <p>Gson's tree parser keeps the last duplicate object member. That behavior
 * is unsuitable at a model trust boundary, so this parser builds the tree
 * through {@link JsonReader} and rejects duplicates before they can be lost.</p>
 */
final class BoundedJsonParser {
    private static final int MAX_NUMBER_CHARS = 128;

    private BoundedJsonParser() {}

    static JsonElement parse(String json, int maxChars, int maxDepth, int maxNodes)
            throws IOException {
        if (json == null) {
            throw new IOException("JSON input is null");
        }
        if (json.length() > maxChars) {
            throw new IOException("JSON input exceeds the configured size limit");
        }

        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setStrictness(Strictness.STRICT);
        ParseBudget budget = new ParseBudget(maxNodes);
        JsonElement value = readValue(reader, 0, maxDepth, budget);
        if (reader.peek() != JsonToken.END_DOCUMENT) {
            throw new IOException("Trailing data after the JSON value");
        }
        return value;
    }

    private static JsonElement readValue(
            JsonReader reader,
            int depth,
            int maxDepth,
            ParseBudget budget
    ) throws IOException {
        if (depth > maxDepth) {
            throw new IOException("JSON nesting exceeds the configured depth limit");
        }
        budget.consume();

        return switch (reader.peek()) {
            case BEGIN_OBJECT -> readObject(reader, depth, maxDepth, budget);
            case BEGIN_ARRAY -> readArray(reader, depth, maxDepth, budget);
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> readNumber(reader);
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IOException("Unexpected JSON token: " + reader.peek());
        };
    }

    private static JsonObject readObject(
            JsonReader reader,
            int depth,
            int maxDepth,
            ParseBudget budget
    ) throws IOException {
        JsonObject object = new JsonObject();
        Set<String> names = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!names.add(name)) {
                throw new IOException("Duplicate JSON object member: " + name);
            }
            object.add(name, readValue(reader, depth + 1, maxDepth, budget));
        }
        reader.endObject();
        return object;
    }

    private static JsonArray readArray(
            JsonReader reader,
            int depth,
            int maxDepth,
            ParseBudget budget
    ) throws IOException {
        JsonArray array = new JsonArray();
        reader.beginArray();
        while (reader.hasNext()) {
            array.add(readValue(reader, depth + 1, maxDepth, budget));
        }
        reader.endArray();
        return array;
    }

    private static JsonPrimitive readNumber(JsonReader reader) throws IOException {
        String raw = reader.nextString();
        if (raw.length() > MAX_NUMBER_CHARS) {
            throw new IOException("JSON number exceeds the configured size limit");
        }
        try {
            return new JsonPrimitive(new BigDecimal(raw));
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid JSON number", exception);
        }
    }

    private static final class ParseBudget {
        private int remaining;

        private ParseBudget(int remaining) {
            if (remaining <= 0) {
                throw new IllegalArgumentException("maxNodes must be positive");
            }
            this.remaining = remaining;
        }

        private void consume() throws IOException {
            if (--remaining < 0) {
                throw new IOException("JSON node count exceeds the configured limit");
            }
        }
    }
}
