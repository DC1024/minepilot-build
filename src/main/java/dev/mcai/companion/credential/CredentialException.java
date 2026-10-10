package dev.mcai.companion.credential;

public final class CredentialException extends Exception {
    public CredentialException(final String message) {
        super(message);
    }

    public CredentialException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
