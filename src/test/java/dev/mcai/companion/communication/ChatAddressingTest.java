package dev.mcai.companion.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChatAddressingTest {
    @Test
    void ordinarySinglePlayerConversationIsPreserved() {
        final var parsed = ChatAddressing.parse(
                "Could you speak Chinese?",
                "MC_AI"
        );
        assertFalse(parsed.explicit());
        assertEquals("Could you speak Chinese?", parsed.message());
        assertTrue(ChatAddressing.addressedForServer(parsed, true));
        assertFalse(ChatAddressing.addressedForServer(parsed, false));
        assertTrue(ChatAddressing.addressedForServer(parsed, 1));
        assertFalse(ChatAddressing.addressedForServer(parsed, 2));
        assertTrue(
                ChatAddressing.addressedForServer(
                        ChatAddressing.parse("@MC_AI hello", "MC_AI"),
                        2
                )
        );
    }

    @Test
    void ordinaryChineseSinglePlayerTaskNeedsNoMention() {
        final var parsed = ChatAddressing.parse(
                "跟我来，保持两三格距离，正常走，不要传送。",
                "MC_AI"
        );

        assertFalse(parsed.explicit());
        assertEquals(
                "跟我来，保持两三格距离，正常走，不要传送。",
                parsed.message()
        );
        assertTrue(ChatAddressing.addressedForServer(parsed, 1));
        assertFalse(ChatAddressing.addressedForServer(parsed, 2));
    }

    @Test
    void acceptsAgentNameWithOrWithoutAt() {
        assertEquals(
                "去砍树",
                ChatAddressing.parse("MC_AI，去砍树", "MC_AI")
                    .message()
        );
        assertTrue(
                ChatAddressing.parse("@mc_ai: hello", "MC_AI")
                    .explicit()
        );
    }

    @Test
    void acceptsLegacySpacedMcAiAlias() {
        final var parsed = ChatAddressing.parse(
                "@MC AI Could you speak Chinese?",
                "Companion"
        );
        assertTrue(parsed.explicit());
        assertEquals(
                "Could you speak Chinese?",
                parsed.message()
        );
    }

    @Test
    void doesNotTreatLongerWordsAsAddresses() {
        assertFalse(
                ChatAddressing.parse(
                    "MCAICompanion hello",
                    "Other"
                ).explicit()
        );
    }

    @Test
    void rejectsNegativeHumanCountInsteadOfSilentlyAddressingChat() {
        final var parsed = ChatAddressing.parse("hello", "MC_AI");
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ChatAddressing.addressedForServer(parsed, -1)
        );
    }
}
