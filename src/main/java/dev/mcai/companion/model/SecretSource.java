package dev.mcai.companion.model;

/**
 * Supplies a fresh mutable copy of a credential for one request. The gateway
 * clears the returned array after constructing the Authorization header.
 */
@FunctionalInterface
public interface SecretSource {
    char[] acquire();
}
