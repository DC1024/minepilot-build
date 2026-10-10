package dev.mcai.companion.modelsetup.network;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Common-code handoff used to keep all client GUI classes out of dedicated
 * server class loading.
 */
public final class ModelSetupClientBridge {
    private static volatile Consumer<ClientboundModelSetupState> handler =
        ignored -> {
        };

    private ModelSetupClientBridge() {
    }

    public static void install(
        final Consumer<ClientboundModelSetupState> stateHandler
    ) {
        handler = Objects.requireNonNull(stateHandler, "stateHandler");
    }

    static void accept(final ClientboundModelSetupState state) {
        handler.accept(state);
    }
}
