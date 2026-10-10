package dev.mcai.companion.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderResponseShapeTest {
    @Test
    void chatSummaryExposesStructureWithoutContent() {
        String canary = "sensitive-chain-of-thought-canary";
        String shape = ProviderResponseShape.summarize(
                Protocol.CHAT_COMPLETIONS,
                """
                        {
                          "choices": [{
                            "finish_reason": "length",
                            "message": {
                              "content": null,
                              "reasoning_content": "%s",
                              "tool_calls": []
                            }
                          }]
                        }
                        """.formatted(canary)
        );

        assertEquals(
                "json=object,choices=array(1),finish=length,message=object,"
                        + "content=null,reasoning=nonempty,tool_calls=array(0)",
                shape
        );
        assertFalse(shape.contains(canary));
    }

    @Test
    void responsesSummaryCountsOnlyKnownOutputKinds() {
        String canary = "sensitive-output-canary";
        String shape = ProviderResponseShape.summarize(
                Protocol.RESPONSES,
                """
                        {
                          "status": "incomplete",
                          "output": [
                            {"type":"reasoning","summary":"%s"},
                            {"type":"message","content":[
                              {"type":"output_text","text":"%s"}
                            ]},
                            {"type":"function_call","arguments":"%s"}
                          ]
                        }
                        """.formatted(canary, canary, canary)
        );

        assertEquals(
                "json=object,status=incomplete,output=array(3),messages=1,"
                        + "output_text_parts=1,function_calls=1,reasoning_items=1",
                shape
        );
        assertFalse(shape.contains(canary));
    }

    @Test
    void malformedJsonSummaryNeverEchoesBody() {
        String shape = ProviderResponseShape.summarize(
                Protocol.CHAT_COMPLETIONS,
                "{sensitive-invalid-json"
        );

        assertEquals("json=invalid", shape);
        assertTrue(shape.length() < 32);
    }
}
