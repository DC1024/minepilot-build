package dev.mcai.companion.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * Produces a bounded, content-free description of a provider response.
 *
 * <p>The description contains only protocol field presence, JSON types,
 * bounded collection sizes, and allow-listed terminal states. It deliberately
 * excludes text, reasoning, tool arguments, identifiers, headers, and all
 * other provider-controlled values, so it is safe to surface in diagnostics.</p>
 */
final class ProviderResponseShape {
    private static final int MAX_RESPONSE_CHARS = 4 * 1_048_576;
    private static final int MAX_RESPONSE_DEPTH = 64;
    private static final int MAX_RESPONSE_NODES = 100_000;

    private ProviderResponseShape() {}

    static String summarize(Protocol protocol, String body) {
        final JsonElement parsed;
        try {
            parsed = BoundedJsonParser.parse(
                    body,
                    MAX_RESPONSE_CHARS,
                    MAX_RESPONSE_DEPTH,
                    MAX_RESPONSE_NODES
            );
        } catch (IOException | RuntimeException exception) {
            return "json=invalid";
        }
        if (!parsed.isJsonObject()) {
            return "json=" + typeOf(parsed);
        }
        JsonObject root = parsed.getAsJsonObject();
        return protocol == Protocol.RESPONSES
                ? summarizeResponses(root)
                : summarizeChat(root);
    }

    private static String summarizeResponses(JsonObject root) {
        Optional<JsonArray> output = array(root.get("output"));
        StringBuilder shape = new StringBuilder("json=object");
        shape.append(",status=").append(terminalState(root.get("status")));
        shape.append(",output=").append(arrayShape(output));
        if (output.isPresent()) {
            int messages = 0;
            int functionCalls = 0;
            int outputTextParts = 0;
            int reasoningItems = 0;
            for (JsonElement element : output.orElseThrow()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String type = string(item.get("type")).orElse("");
                switch (type) {
                    case "message" -> {
                        messages++;
                        JsonArray content = array(item.get("content"))
                                .orElse(new JsonArray());
                        for (JsonElement partElement : content) {
                            if (partElement.isJsonObject()
                                    && string(partElement.getAsJsonObject().get("type"))
                                            .orElse("")
                                            .equals("output_text")) {
                                outputTextParts++;
                            }
                        }
                    }
                    case "function_call" -> functionCalls++;
                    case "reasoning" -> reasoningItems++;
                    default -> {
                        // Unknown output kinds are counted only through output size.
                    }
                }
            }
            shape.append(",messages=").append(messages);
            shape.append(",output_text_parts=").append(outputTextParts);
            shape.append(",function_calls=").append(functionCalls);
            shape.append(",reasoning_items=").append(reasoningItems);
        }
        return shape.toString();
    }

    private static String summarizeChat(JsonObject root) {
        Optional<JsonArray> choices = array(root.get("choices"));
        StringBuilder shape = new StringBuilder("json=object");
        shape.append(",choices=").append(arrayShape(choices));
        if (choices.isEmpty()
                || choices.orElseThrow().isEmpty()
                || !choices.orElseThrow().get(0).isJsonObject()) {
            return shape.toString();
        }

        JsonObject choice = choices.orElseThrow().get(0).getAsJsonObject();
        shape.append(",finish=").append(finishReason(choice.get("finish_reason")));
        Optional<JsonObject> message = object(choice.get("message"));
        shape.append(",message=").append(message.isPresent() ? "object" : typeOf(
                choice.get("message")
        ));
        if (message.isEmpty()) {
            return shape.toString();
        }
        JsonObject value = message.orElseThrow();
        shape.append(",content=").append(textShape(value, "content"));
        shape.append(",reasoning=").append(textShape(value, "reasoning_content"));
        shape.append(",tool_calls=").append(arrayShape(array(value.get("tool_calls"))));
        return shape.toString();
    }

    private static String terminalState(JsonElement element) {
        return string(element)
                .map(value -> switch (value.toLowerCase(Locale.ROOT)) {
                    case "completed" -> "completed";
                    case "incomplete" -> "incomplete";
                    case "failed" -> "failed";
                    case "cancelled" -> "cancelled";
                    default -> "other";
                })
                .orElseGet(() -> typeOf(element));
    }

    private static String finishReason(JsonElement element) {
        return string(element)
                .map(value -> switch (value.toLowerCase(Locale.ROOT)) {
                    case "stop" -> "stop";
                    case "length" -> "length";
                    case "tool_calls" -> "tool_calls";
                    case "content_filter" -> "content_filter";
                    default -> "other";
                })
                .orElseGet(() -> typeOf(element));
    }

    private static String textShape(JsonObject object, String member) {
        if (!object.has(member)) {
            return "missing";
        }
        JsonElement element = object.get(member);
        if (element == null || element.isJsonNull()) {
            return "null";
        }
        Optional<String> value = string(element);
        if (value.isPresent()) {
            return value.orElseThrow().isEmpty() ? "empty" : "nonempty";
        }
        return typeOf(element);
    }

    private static String arrayShape(Optional<JsonArray> array) {
        if (array.isEmpty()) {
            return "missing";
        }
        return "array(" + Math.min(array.orElseThrow().size(), 1_000) + ")";
    }

    private static String typeOf(JsonElement element) {
        if (element == null) {
            return "missing";
        }
        if (element.isJsonNull()) {
            return "null";
        }
        if (element.isJsonObject()) {
            return "object";
        }
        if (element.isJsonArray()) {
            return "array";
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                return "string";
            }
            if (element.getAsJsonPrimitive().isBoolean()) {
                return "boolean";
            }
            return "number";
        }
        return "unknown";
    }

    private static Optional<JsonObject> object(JsonElement element) {
        return element != null && element.isJsonObject()
                ? Optional.of(element.getAsJsonObject())
                : Optional.empty();
    }

    private static Optional<JsonArray> array(JsonElement element) {
        return element != null && element.isJsonArray()
                ? Optional.of(element.getAsJsonArray())
                : Optional.empty();
    }

    private static Optional<String> string(JsonElement element) {
        return element != null
                && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()
                ? Optional.of(element.getAsString())
                : Optional.empty();
    }
}
