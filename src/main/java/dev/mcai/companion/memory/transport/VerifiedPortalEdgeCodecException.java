package dev.mcai.companion.memory.transport;

public final class VerifiedPortalEdgeCodecException
        extends IllegalArgumentException {
    public VerifiedPortalEdgeCodecException(String message) {
        super(message);
    }

    public VerifiedPortalEdgeCodecException(
        String message,
        Throwable cause
    ) {
        super(message, cause);
    }
}
