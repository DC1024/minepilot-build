package dev.mcai.companion.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.net.URI;
import org.junit.jupiter.api.Test;

final class ModelRequestFactoryVisionTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAA"
                    + "HUlEQVR4nGNoaGhgGAWjYBSMglEwCkbBKBgFo2AUjAIAG6AA"
                    + "AX0V09YAAAAASUVORK5CYII="
    );

    @Test
    void serializesVerifiedResponsesImageBeforeTrustedText() {
        final JsonObject request = request(
                ProviderCapabilities.responsesJsonSchema(false)
                        .withImageInput(true)
        );
        final var content = request.getAsJsonArray("input")
                .get(0).getAsJsonObject()
                .getAsJsonArray("content");

        assertEquals("input_image", content.get(0).getAsJsonObject()
                .get("type").getAsString());
        assertTrue(content.get(0).getAsJsonObject()
                .get("image_url").getAsString()
                .startsWith("data:image/png;base64,"));
        assertEquals("low", content.get(0).getAsJsonObject()
                .get("detail").getAsString());
        assertEquals("input_text", content.get(1).getAsJsonObject()
                .get("type").getAsString());
    }

    @Test
    void serializesVerifiedLegacyChatImageShape() {
        final JsonObject request = request(
                ProviderCapabilities.chatJsonSchema(false)
                        .withImageInput(true)
        );
        final var content = request.getAsJsonArray("messages")
                .get(1).getAsJsonObject()
                .getAsJsonArray("content");

        assertEquals("image_url", content.get(0).getAsJsonObject()
                .get("type").getAsString());
        assertEquals("low", content.get(0).getAsJsonObject()
                .getAsJsonObject("image_url")
                .get("detail").getAsString());
        assertEquals("text", content.get(1).getAsJsonObject()
                .get("type").getAsString());
    }

    @Test
    void neverSendsPixelsWithoutVerifiedImageCapability() {
        final JsonObject request = request(
                ProviderCapabilities.responsesJsonSchema(false)
        );
        final String encoded = request.toString();

        assertFalse(encoded.contains("input_image"));
        assertFalse(encoded.contains("data:image/png;base64,"));
    }

    private static JsonObject request(
            final ProviderCapabilities capabilities
    ) {
        final PlannerInput input = new PlannerInput(
                new DecisionContext(
                        "vision-request",
                        3L,
                        2L,
                        false,
                        Map.of()
                ),
                "Use only trusted local skills.",
                "{\"sampleSequence\":4}",
                128,
                0.2,
                Optional.of(new ModelImageInput(
                        PNG,
                        ModelImageInput.Detail.LOW
                ))
        );
        final String body = new ModelRequestFactory(
                new DecisionEnvelopeCodec()
        ).build(
                new ModelEndpoint(
                        URI.create("https://provider.example/v1"),
                        "vision-model"
                ),
                capabilities,
                input
        );
        return JsonParser.parseString(body).getAsJsonObject();
    }
}
