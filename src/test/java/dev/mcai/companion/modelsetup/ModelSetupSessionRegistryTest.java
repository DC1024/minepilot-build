package dev.mcai.companion.modelsetup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class ModelSetupSessionRegistryTest {
    @Test
    void tokenIsConnectionAndRequestBoundAndOneUse() {
        final AtomicLong clock = new AtomicLong(100);
        final ModelSetupSessionRegistry sessions =
            new ModelSetupSessionRegistry(
                new FixedSecureRandom(),
                clock::get,
                50
            );
        final UUID player = UUID.randomUUID();
        final Object connection = new Object();
        final byte[] token = sessions.issue(player, connection, 7);

        assertFalse(sessions.consume(player, connection, token, 8));
        assertFalse(sessions.consume(player, connection, token, 7));

        final byte[] replacement = sessions.issue(
            player,
            connection,
            9
        );
        assertFalse(
            sessions.consume(player, new Object(), replacement, 9)
        );

        final byte[] accepted = sessions.issue(player, connection, 10);
        assertTrue(sessions.consume(player, connection, accepted, 10));
        assertFalse(sessions.consume(player, connection, accepted, 10));
    }

    @Test
    void expiredAndMutatedTokensAreRejected() {
        final AtomicLong clock = new AtomicLong(100);
        final ModelSetupSessionRegistry sessions =
            new ModelSetupSessionRegistry(
                new FixedSecureRandom(),
                clock::get,
                50
            );
        final UUID player = UUID.randomUUID();
        final Object connection = new Object();
        final byte[] token = sessions.issue(player, connection, 1);
        final byte[] mutated = token.clone();
        mutated[0] ^= 1;
        assertFalse(sessions.consume(player, connection, mutated, 1));

        final byte[] expired = sessions.issue(player, connection, 2);
        clock.set(151);
        assertFalse(sessions.consume(player, connection, expired, 2));
    }

    private static final class FixedSecureRandom extends SecureRandom {
        @Override
        public void nextBytes(final byte[] bytes) {
            Arrays.fill(bytes, (byte) 0x5a);
        }
    }
}
