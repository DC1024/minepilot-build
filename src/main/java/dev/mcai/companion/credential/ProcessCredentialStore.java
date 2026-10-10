package dev.mcai.companion.credential;

import java.util.Arrays;
import java.util.Optional;

/**
 * Process-lifetime fallback. No String representation is retained.
 */
public final class ProcessCredentialStore implements ApiCredentialStore {
    private char[] credential;

    @Override
    public synchronized Optional<char[]> load() {
        return credential == null
            ? Optional.empty()
            : Optional.of(credential.clone());
    }

    @Override
    public synchronized void save(final char[] newCredential) throws CredentialException {
        final char[] validated = CredentialRules.validatedCopy(newCredential);
        wipe();
        credential = validated;
    }

    @Override
    public synchronized void clear() {
        wipe();
    }

    @Override
    public boolean persistent() {
        return false;
    }

    @Override
    public synchronized void close() {
        wipe();
    }

    private void wipe() {
        if (credential != null) {
            Arrays.fill(credential, '\0');
            credential = null;
        }
    }
}
