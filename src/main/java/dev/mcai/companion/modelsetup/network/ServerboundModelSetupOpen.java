package dev.mcai.companion.modelsetup.network;

public record ServerboundModelSetupOpen(long requestId) {
    public ServerboundModelSetupOpen {
        if (requestId <= 0) {
            throw new IllegalArgumentException("requestId must be positive");
        }
    }
}
