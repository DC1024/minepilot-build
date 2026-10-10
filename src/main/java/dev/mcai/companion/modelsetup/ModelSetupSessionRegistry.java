package dev.mcai.companion.modelsetup;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Short-lived, connection-bound, one-use authorization challenges for model
 * setup writes. A copied packet cannot be replayed on a later connection or
 * after its first validation attempt.
 */
public final class ModelSetupSessionRegistry {
    public static final int TOKEN_BYTES = 32;
    public static final Duration SESSION_LIFETIME = Duration.ofMinutes(2);

    private final SecureRandom random;
    private final LongSupplier nanoTime;
    private final long lifetimeNanos;
    private final Map<UUID, Session> sessions = new HashMap<>();

    public ModelSetupSessionRegistry() {
        this(
            new SecureRandom(),
            System::nanoTime,
            SESSION_LIFETIME.toNanos()
        );
    }

    ModelSetupSessionRegistry(
        final SecureRandom random,
        final LongSupplier nanoTime,
        final long lifetimeNanos
    ) {
        this.random = Objects.requireNonNull(random, "random");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        if (lifetimeNanos <= 0) {
            throw new IllegalArgumentException("lifetimeNanos must be positive");
        }
        this.lifetimeNanos = lifetimeNanos;
    }

    public synchronized byte[] issue(
        final UUID playerId,
        final Object connectionIdentity,
        final long requestId
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(connectionIdentity, "connectionIdentity");
        if (requestId <= 0) {
            throw new IllegalArgumentException("requestId must be positive");
        }
        final byte[] token = new byte[TOKEN_BYTES];
        random.nextBytes(token);
        final Session previous = sessions.put(
            playerId,
            new Session(
                connectionIdentity,
                token,
                saturatingAdd(nanoTime.getAsLong(), lifetimeNanos),
                requestId
            )
        );
        if (previous != null) {
            previous.destroy();
        }
        return token.clone();
    }

    /**
     * Consumes the challenge even for an invalid request. This limits every
     * issued token to one comparison and forces a fresh server state fetch for
     * retries.
     */
    public synchronized boolean consume(
        final UUID playerId,
        final Object connectionIdentity,
        final byte[] candidate,
        final long requestId
    ) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(connectionIdentity, "connectionIdentity");
        final Session session = sessions.remove(playerId);
        if (session == null) {
            return false;
        }
        try {
            return requestId > 0
                && session.connectionIdentity == connectionIdentity
                && nanoTime.getAsLong() <= session.expiresAtNanos
                && requestId == session.requestId
                && candidate != null
                && candidate.length == TOKEN_BYTES
                && MessageDigest.isEqual(session.token, candidate);
        } finally {
            session.destroy();
        }
    }

    public synchronized void remove(final UUID playerId) {
        final Session removed = sessions.remove(playerId);
        if (removed != null) {
            removed.destroy();
        }
    }

    public synchronized void clear() {
        sessions.values().forEach(Session::destroy);
        sessions.clear();
    }

    private static long saturatingAdd(final long left, final long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static final class Session {
        private final Object connectionIdentity;
        private final byte[] token;
        private final long expiresAtNanos;
        private final long requestId;

        private Session(
            final Object connectionIdentity,
            final byte[] token,
            final long expiresAtNanos,
            final long requestId
        ) {
            this.connectionIdentity = connectionIdentity;
            this.token = token;
            this.expiresAtNanos = expiresAtNanos;
            this.requestId = requestId;
        }

        private void destroy() {
            Arrays.fill(token, (byte) 0);
        }
    }
}
