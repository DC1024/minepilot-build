package dev.mcai.companion.modelsetup.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.modelsetup.ModelSetupSessionRegistry;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

final class ModelSetupNetworkCodecTest {
    @Test
    void stateRoundTripContainsNoCredentialMaterial() {
        final byte[] token =
            new byte[ModelSetupSessionRegistry.TOKEN_BYTES];
        token[0] = 7;
        final ClientboundModelSetupState expected =
            new ClientboundModelSetupState(
                42,
                token,
                "https://example.test/v1",
                "model-v1",
                "Agent_1",
                "amethyst",
                0.6,
                "Prefer compact stone buildings.",
                true,
                false,
                true,
                true,
                false,
                false,
                true,
                false,
                "ready"
            );
        final FriendlyByteBuf buffer = new FriendlyByteBuf(
            Unpooled.buffer()
        );
        try {
            ModelSetupNetwork.encodeState(expected, buffer);
            final ClientboundModelSetupState decoded =
                ModelSetupNetwork.decodeState(buffer);

            assertArrayEquals(token, decoded.sessionToken());
            assertEquals("Agent_1", decoded.agentName());
            assertEquals("amethyst", decoded.accentColor());
            assertEquals(0.6, decoded.temperature());
            assertTrue(decoded.onboardingCompleted());
            assertTrue(decoded.gatewayReady());
            assertFalse(decoded.bodyActive());
            assertEquals(
                "Prefer compact stone buildings.",
                decoded.systemPrompt()
            );
        } finally {
            buffer.release();
        }
    }

    @Test
    void applyRoundTripRetainsPresentationAndDestroysWireSecret() {
        final byte[] token =
            new byte[ModelSetupSessionRegistry.TOKEN_BYTES];
        final byte[] key = "not-a-real-key".getBytes(
            StandardCharsets.UTF_8
        );
        final ServerboundModelSetupApply message =
            new ServerboundModelSetupApply(
                43,
                token,
                key,
                "https://example.test/v1",
                "model-v2",
                "Builder_2",
                "gold",
                0.8,
                "Prefer defensive construction.",
                false,
                true
            );
        final FriendlyByteBuf buffer = new FriendlyByteBuf(
            Unpooled.buffer()
        );
        try {
            ModelSetupNetwork.encodeApply(message, buffer);
            final ServerboundModelSetupApply decoded =
                ModelSetupNetwork.decodeApply(buffer);
            try {
                assertEquals("Builder_2", decoded.agentName());
                assertEquals("gold", decoded.accentColor());
                assertEquals(0.8, decoded.temperature());
                assertEquals(
                    "Prefer defensive construction.",
                    decoded.systemPrompt()
                );
                assertEquals(
                    "not-a-real-key",
                    new String(
                        decoded.apiKeyUtf8(),
                        StandardCharsets.UTF_8
                    )
                );
            } finally {
                decoded.destroy();
            }
        } finally {
            buffer.release();
        }
    }
}
