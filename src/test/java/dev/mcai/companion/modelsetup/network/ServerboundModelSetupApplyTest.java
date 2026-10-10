package dev.mcai.companion.modelsetup.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.modelsetup.ModelSetupSessionRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class ServerboundModelSetupApplyTest {
    @Test
    void packetCopiesAndDestroysSecretArrays() {
        final byte[] token =
            new byte[ModelSetupSessionRegistry.TOKEN_BYTES];
        final byte[] key = "test-key-not-real".getBytes(
            StandardCharsets.UTF_8
        );
        final ServerboundModelSetupApply packet =
            new ServerboundModelSetupApply(
                1,
                token,
                key,
                "https://example.test/v1",
                "model",
                "Agent_1",
                "emerald",
                0.2,
                "",
                false,
                true
            );
        Arrays.fill(token, (byte) 7);
        Arrays.fill(key, (byte) 7);

        assertEquals(
            "test-key-not-real",
            new String(packet.apiKeyUtf8(), StandardCharsets.UTF_8)
        );
        packet.destroy();
        assertTrue(allZero(packet.apiKeyUtf8Unsafe()));
        assertTrue(allZero(packet.sessionTokenUnsafe()));
    }

    @Test
    void packetEnforcesEveryWireLengthBoundary() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ServerboundModelSetupApply(
                1,
                new byte[ModelSetupSessionRegistry.TOKEN_BYTES - 1],
                new byte[0],
                "https://example.test/v1",
                "model",
                "Agent_1",
                "emerald",
                0.2,
                "",
                false,
                true
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new ServerboundModelSetupApply(
                1,
                new byte[ModelSetupSessionRegistry.TOKEN_BYTES],
                new byte[
                    ModelSetupWireLimits.MAX_API_KEY_UTF8_BYTES + 1
                ],
                "https://example.test/v1",
                "model",
                "Agent_1",
                "emerald",
                0.2,
                "",
                false,
                true
            )
        );
    }

    private static boolean allZero(final byte[] value) {
        for (byte element : value) {
            if (element != 0) {
                return false;
            }
        }
        return true;
    }
}
