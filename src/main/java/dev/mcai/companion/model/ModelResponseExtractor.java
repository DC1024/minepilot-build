package dev.mcai.companion.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

final class ModelResponseExtractor {
    private static final int MAX_RESPONSE_CHARS = 4 * 1_048_576;
    private static final int MAX_RESPONSE_DEPTH = 64;
    private static final int MAX_RESPONSE_NODES = 100_000;

    private final BoundedSseDecoder sseDecoder = new BoundedSseDecoder();

    ExtractedModelResponse extract(
            Protocol protocol,
            OutputContract outputContract,
            boolean streaming,
            String body
    ) throws ModelResponseException {
        return switch (protocol) {
            case RESPONSES -> streaming
                    ? extractResponsesStream(outputContract, body)
                    : extractResponsesObject(outputContract, parseObject(body));
            case CHAT_COMPLETIONS -> streaming
                    ? extractChatStream(outputContract, body)
                    : extractChatObject(outputContract, parseObject(body));
        };
    }

    private ExtractedModelResponse extractResponsesStream(
            OutputContract outputContract,
            String body
    ) throws ModelResponseException {
        JsonObject completedResponse = null;
        try {
            for (SseEvent event : sseDecoder.decode(body)) {
                if (event.data().equals("[DONE]")) {
                    continue;
                }
                JsonObject payload = parseObject(event.data());
                String type = string(payload.get("type")).orElse(event.event());
                switch (type) {
                    case "response.completed" -> {
                        JsonElement response = payload.get("response");
                        if (response == null || !response.isJsonObject()) {
                            malformed("response.completed omitted the response object");
                        }
                        completedResponse = response.getAsJsonObject();
                    }
                    case "response.incomplete" -> incompleteFrom(payload);
                    case "response.failed", "error" ->
                            throw new ModelResponseException(
                                    ModelFailureKind.SERVER_TRANSIENT,
                                    "The Responses stream reported a failure"
                            );
                    default -> {
                        // Delta and future extension events are not authoritative.
                    }
                }
            }
        } catch (IOException exception) {
            throw new ModelResponseException(
                    ModelFailureKind.MALFORMED_RESPONSE,
                    "The Responses SSE stream is malformed"
            );
        }
        if (completedResponse == null) {
            return malformed("The Responses stream ended without response.completed");
        }
        return extractResponsesObject(outputContract, completedResponse);
    }

    private ExtractedModelResponse extractResponsesObject(
            OutputContract outputContract,
            JsonObject root
    ) throws ModelResponseException {
        String status = string(root.get("status")).orElse("");
        if (status.equals("incomplete")) {
            return incompleteFrom(root);
        }
        if (status.equals("failed") || status.equals("cancelled")) {
            throw new ModelResponseException(
                    ModelFailureKind.SERVER_TRANSIENT,
                    "The Responses API did not complete the request"
            );
        }
        if (!status.isEmpty() && !status.equals("completed")) {
            return malformed("The Responses API returned a non-terminal status");
        }

        JsonArray output = array(root.get("output"))
                .orElseThrow(() -> malformedException("Responses output is missing"));
        String decisionJson = outputContract == OutputContract.FORCED_FUNCTION
                ? extractResponsesFunction(output)
                : extractResponsesText(output);
        return new ExtractedModelResponse(decisionJson, parseResponsesUsage(root.get("usage")));
    }

    private String extractResponsesFunction(JsonArray output) throws ModelResponseException {
        String arguments = null;
        for (JsonElement element : output) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            if (!string(item.get("type")).orElse("").equals("function_call")) {
                continue;
            }
            if (arguments != null) {
                return malformed("Responses returned more than one function call");
            }
            if (!string(item.get("name")).orElse("")
                    .equals(ModelRequestFactory.DECISION_FUNCTION_NAME)) {
                return malformed("Responses returned an unexpected function name");
            }
            arguments = string(item.get("arguments"))
                    .orElseThrow(() -> malformedException("Function arguments are missing"));
        }
        if (arguments == null) {
            return malformed("Responses returned no decision function call");
        }
        return arguments;
    }

    private String extractResponsesText(JsonArray output) throws ModelResponseException {
        String text = null;
        for (JsonElement element : output) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            if (!string(item.get("type")).orElse("").equals("message")) {
                continue;
            }
            JsonArray content = array(item.get("content")).orElse(new JsonArray());
            for (JsonElement contentElement : content) {
                if (!contentElement.isJsonObject()) {
                    continue;
                }
                JsonObject part = contentElement.getAsJsonObject();
                String type = string(part.get("type")).orElse("");
                if (type.equals("refusal")) {
                    throw new ModelResponseException(
                            ModelFailureKind.CONTENT_FILTERED,
                            "The model refused the decision request"
                    );
                }
                if (!type.equals("output_text")) {
                    continue;
                }
                if (text != null) {
                    return malformed("Responses returned multiple output_text parts");
                }
                text = string(part.get("text"))
                        .orElseThrow(() -> malformedException("output_text is missing text"));
            }
        }
        if (text == null) {
            return malformed("Responses returned no decision text");
        }
        return text;
    }

    private ExtractedModelResponse extractChatObject(
            OutputContract outputContract,
            JsonObject root
    ) throws ModelResponseException {
        JsonArray choices = array(root.get("choices"))
                .orElseThrow(() -> malformedException("Chat choices are missing"));
        if (choices.size() != 1 || !choices.get(0).isJsonObject()) {
            return malformed("Chat must return exactly one choice");
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        checkFinishReason(string(choice.get("finish_reason")).orElse(""));
        JsonObject message = object(choice.get("message"))
                .orElseThrow(() -> malformedException("Chat message is missing"));
        if (string(message.get("refusal")).filter(value -> !value.isEmpty()).isPresent()) {
            throw new ModelResponseException(
                    ModelFailureKind.CONTENT_FILTERED,
                    "The model refused the decision request"
            );
        }

        String decisionJson = outputContract == OutputContract.FORCED_FUNCTION
                ? extractChatFunction(message)
                : string(message.get("content"))
                        .orElseThrow(() -> malformedException("Chat content is missing"));
        return new ExtractedModelResponse(decisionJson, parseChatUsage(root.get("usage")));
    }

    private String extractChatFunction(JsonObject message) throws ModelResponseException {
        JsonArray calls = array(message.get("tool_calls"))
                .orElseThrow(() -> malformedException("Chat tool_calls are missing"));
        if (calls.size() != 1 || !calls.get(0).isJsonObject()) {
            return malformed("Chat must return exactly one tool call");
        }
        JsonObject call = calls.get(0).getAsJsonObject();
        JsonObject function = object(call.get("function"))
                .orElseThrow(() -> malformedException("Chat tool function is missing"));
        if (!string(function.get("name")).orElse("")
                .equals(ModelRequestFactory.DECISION_FUNCTION_NAME)) {
            return malformed("Chat returned an unexpected function name");
        }
        return string(function.get("arguments"))
                .orElseThrow(() -> malformedException("Chat function arguments are missing"));
    }

    private ExtractedModelResponse extractChatStream(
            OutputContract outputContract,
            String body
    ) throws ModelResponseException {
        StringBuilder content = new StringBuilder();
        Map<Integer, ToolAccumulator> tools = new TreeMap<>();
        TokenUsage usage = TokenUsage.UNKNOWN;
        String finishReason = "";
        boolean terminalMarker = false;

        try {
            for (SseEvent event : sseDecoder.decode(body)) {
                if (event.data().equals("[DONE]")) {
                    terminalMarker = true;
                    continue;
                }
                JsonObject chunk = parseObject(event.data());
                usage = mergeUsage(usage, parseChatUsage(chunk.get("usage")));
                JsonArray choices = array(chunk.get("choices")).orElse(new JsonArray());
                if (choices.isEmpty()) {
                    continue;
                }
                if (choices.size() != 1 || !choices.get(0).isJsonObject()) {
                    return malformed("A Chat stream chunk contained multiple choices");
                }
                JsonObject choice = choices.get(0).getAsJsonObject();
                int choiceIndex = integer(choice.get("index")).orElse(0);
                if (choiceIndex != 0) {
                    return malformed("A Chat stream returned a non-zero choice index");
                }
                String chunkFinishReason = string(choice.get("finish_reason")).orElse("");
                if (!chunkFinishReason.isEmpty()) {
                    finishReason = chunkFinishReason;
                    terminalMarker = true;
                }
                JsonObject delta = object(choice.get("delta")).orElse(new JsonObject());
                string(delta.get("refusal"))
                        .filter(value -> !value.isEmpty())
                        .ifPresent(value -> {
                            throw new StreamRefusal();
                        });
                string(delta.get("content")).ifPresent(content::append);

                JsonArray calls = array(delta.get("tool_calls")).orElse(new JsonArray());
                for (JsonElement callElement : calls) {
                    if (!callElement.isJsonObject()) {
                        return malformed("A Chat stream tool call is not an object");
                    }
                    JsonObject call = callElement.getAsJsonObject();
                    int index = integer(call.get("index")).orElse(0);
                    ToolAccumulator accumulator = tools.computeIfAbsent(
                            index,
                            ignored -> new ToolAccumulator()
                    );
                    JsonObject function = object(call.get("function")).orElse(new JsonObject());
                    string(function.get("name")).ifPresent(accumulator.name::append);
                    string(function.get("arguments")).ifPresent(accumulator.arguments::append);
                }
            }
        } catch (StreamRefusal refusal) {
            throw new ModelResponseException(
                    ModelFailureKind.CONTENT_FILTERED,
                    "The model refused the decision request"
            );
        } catch (IOException exception) {
            throw new ModelResponseException(
                    ModelFailureKind.MALFORMED_RESPONSE,
                    "The Chat SSE stream is malformed"
            );
        }

        if (!terminalMarker) {
            return malformed("The Chat stream ended without a terminal marker");
        }
        checkFinishReason(finishReason);

        String decisionJson;
        if (outputContract == OutputContract.FORCED_FUNCTION) {
            if (tools.size() != 1 || !tools.containsKey(0)) {
                return malformed("Chat must stream exactly one decision tool call");
            }
            ToolAccumulator tool = tools.get(0);
            if (!tool.name.toString().equals(ModelRequestFactory.DECISION_FUNCTION_NAME)) {
                return malformed("Chat streamed an unexpected function name");
            }
            decisionJson = tool.arguments.toString();
        } else {
            if (!tools.isEmpty()) {
                return malformed("Chat unexpectedly streamed a tool call");
            }
            decisionJson = content.toString();
        }
        return new ExtractedModelResponse(decisionJson, usage);
    }

    private static void checkFinishReason(String finishReason) throws ModelResponseException {
        if (finishReason.equals("length")) {
            throw new ModelResponseException(
                    ModelFailureKind.CONTEXT_LIMIT,
                    "The model output was truncated"
            );
        }
        if (finishReason.equals("content_filter")) {
            throw new ModelResponseException(
                    ModelFailureKind.CONTENT_FILTERED,
                    "The model output was filtered"
            );
        }
    }

    private static <T> T incompleteFrom(JsonObject root) throws ModelResponseException {
        String reason = object(root.get("incomplete_details"))
                .flatMap(details -> string(details.get("reason")))
                .orElse("");
        if (reason.equals("content_filter")) {
            throw new ModelResponseException(
                    ModelFailureKind.CONTENT_FILTERED,
                    "The model output was filtered"
            );
        }
        throw new ModelResponseException(
                ModelFailureKind.CONTEXT_LIMIT,
                "The model response was incomplete"
        );
    }

    private static TokenUsage parseResponsesUsage(JsonElement element) {
        Optional<JsonObject> usage = object(element);
        if (usage.isEmpty()) {
            return TokenUsage.UNKNOWN;
        }
        return new TokenUsage(
                longValue(usage.get().get("input_tokens")).orElse(-1L),
                longValue(usage.get().get("output_tokens")).orElse(-1L),
                longValue(usage.get().get("total_tokens")).orElse(-1L)
        );
    }

    private static TokenUsage parseChatUsage(JsonElement element) {
        Optional<JsonObject> usage = object(element);
        if (usage.isEmpty()) {
            return TokenUsage.UNKNOWN;
        }
        return new TokenUsage(
                longValue(usage.get().get("prompt_tokens")).orElse(-1L),
                longValue(usage.get().get("completion_tokens")).orElse(-1L),
                longValue(usage.get().get("total_tokens")).orElse(-1L)
        );
    }

    private static TokenUsage mergeUsage(TokenUsage previous, TokenUsage candidate) {
        return candidate.equals(TokenUsage.UNKNOWN) ? previous : candidate;
    }

    private static JsonObject parseObject(String json) throws ModelResponseException {
        try {
            JsonElement parsed = BoundedJsonParser.parse(
                    json,
                    MAX_RESPONSE_CHARS,
                    MAX_RESPONSE_DEPTH,
                    MAX_RESPONSE_NODES
            );
            if (!parsed.isJsonObject()) {
                return malformed("Provider response is not a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (IOException | RuntimeException exception) {
            throw new ModelResponseException(
                    ModelFailureKind.MALFORMED_RESPONSE,
                    "Provider response is not valid bounded JSON"
            );
        }
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

    private static Optional<Integer> integer(JsonElement element) {
        return longValue(element).flatMap(value ->
                value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                        ? Optional.of(value.intValue())
                        : Optional.empty()
        );
    }

    private static Optional<Long> longValue(JsonElement element) {
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(element.getAsString()).longValueExact());
        } catch (ArithmeticException | NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static <T> T malformed(String message) throws ModelResponseException {
        throw malformedException(message);
    }

    private static ModelResponseException malformedException(String message) {
        return new ModelResponseException(ModelFailureKind.MALFORMED_RESPONSE, message);
    }

    private static final class ToolAccumulator {
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }

    private static final class StreamRefusal extends RuntimeException {}
}
