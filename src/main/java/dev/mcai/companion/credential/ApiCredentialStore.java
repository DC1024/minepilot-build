package dev.mcai.companion.credential;

import java.util.Optional;

public interface ApiCredentialStore extends AutoCloseable {
    Optional<char[]> load() throws CredentialException;

    void save(char[] credential) throws CredentialException;

    void clear() throws CredentialException;

    boolean persistent();

    @Override
    default void close() {
    }
}
