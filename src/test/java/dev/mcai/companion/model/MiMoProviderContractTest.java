package dev.mcai.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Wire contract for the Xiaomi MiMo Token Plan profile used by the setup UI.
 * This is deliberately an offline test: it validates the exact request shape
 * without consuming a user's credential or making a provider call.
 */
final class MiMoProviderContractTest {
    private static final ModelEndpoint ENDPOINT = new ModelEndpoint(
            URI.create("https://token-plan-cn.xiaomimimo.com/v1"),
            "mimo-v2.5"
    );

    private static final PlannerInput INPUT = new PlannerInput(
            new DecisionContext(
                    "mimo-contract-request",
                    17L,
                    4L,
                    false,
                    Map.of()
            ),
            "You are a Minecraft teammate.",
            "{}",
            384,
            0.2
    );

    @Test
    void tokenPlanChatRequestUsesDocumentedTokenAndReasoningFields() {
        JsonObject request = JsonParser.parseString(
                new ModelRequestFactory(new DecisionEnvelopeCodec()).build(
                        ENDPOINT,
                        ProviderCapabilities.chatJsonSchema(false),
                        INPUT
                )
        ).getAsJsonObject();

        assertEquals("mimo-v2.5", request.get("model").getAsString());
        assertEquals(384, request.get("max_completion_tokens").getAsInt());
        assertFalse(request.has("max_tokens"));
        assertEquals(
                "disabled",
                request.getAsJsonObject("thinking")
                        .get("type")
                        .getAsString()
        );
        assertEquals(
                "json_schema",
                request.getAsJsonObject("response_format")
                        .get("type")
                        .getAsString()
        );
        assertTrue(request.getAsJsonArray("messages").size() >= 2);
    }

    @Test
    void tokenPlanResponsesRequestKeepsStoreOffAndStrictOutput() {
        JsonObject request = JsonParser.parseString(
                new ModelRequestFactory(new DecisionEnvelopeCodec()).build(
                        ENDPOINT,
                        ProviderCapabilities.responsesJsonSchema(false),
                        INPUT
                )
        ).getAsJsonObject();

        assertEquals("mimo-v2.5", request.get("model").getAsString());
        assertFalse(request.get("store").getAsBoolean());
        assertEquals(384, request.get("max_output_tokens").getAsInt());
        assertEquals(
                "none",
                request.getAsJsonObject("reasoning")
                        .get("effort")
                        .getAsString()
        );
        assertEquals(
                "json_schema",
                request.getAsJsonObject("text")
                        .getAsJsonObject("format")
                        .get("type")
                        .getAsString()
        );
        assertTrue(request.getAsJsonArray("input").size() == 1);
    }
}
