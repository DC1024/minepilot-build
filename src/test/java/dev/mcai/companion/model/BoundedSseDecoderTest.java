package dev.mcai.companion.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundedSseDecoderTest {
    @Test
    void supportsCrLfCommentsAndMultiLineData() throws Exception {
        String stream = """
                : keepalive\r
                event: message\r
                data: {"hello":\r
                data: "世界"}\r
                \r
                data: [DONE]\r
                \r
                """;

        List<SseEvent> events = new BoundedSseDecoder().decode(stream);

        assertEquals(2, events.size());
        assertEquals("message", events.get(0).event());
        assertEquals("{\"hello\":\n\"世界\"}", events.get(0).data());
        assertEquals("[DONE]", events.get(1).data());
    }

    @Test
    void dispatchesFinalEventWithoutBlankTerminator() throws Exception {
        List<SseEvent> events = new BoundedSseDecoder().decode("data: final");
        assertEquals(List.of(new SseEvent("", "final")), events);
    }
}
