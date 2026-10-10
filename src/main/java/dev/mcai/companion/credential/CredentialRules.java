package dev.mcai.companion.credential;

import java.util.Arrays;

final class CredentialRules {
    static final int MAXIMUM_CHARACTERS = 8192;

    private CredentialRules() {
    }

    static char[] validatedCopy(final char[] credential) throws CredentialException {
        if (credential == null || credential.length == 0
            || credential.length > MAXIMUM_CHARACTERS) {
            throw new CredentialException("API credential length is invalid");
        }
        final char[] copy = credential.clone();
        for (char character : copy) {
            if (Character.isISOControl(character) || Character.isWhitespace(character)) {
                Arrays.fill(copy, '\0');
                throw new CredentialException("API credential contains an invalid character");
            }
        }
        return copy;
    }
}
