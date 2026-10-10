package dev.mcai.companion.skin.network;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Common-code handoff that keeps dedicated-server class loading independent
 * of every net.minecraft.client type.
 */
public final class SkinClientBridge {
    private static Consumer<ClientboundSkinChunk> chunkHandler =
        ignored -> {
        };
    private static Consumer<UUID> clearHandler = ignored -> {
    };

    private SkinClientBridge() {
    }

    public static void install(
        final Consumer<ClientboundSkinChunk> chunks,
        final Consumer<UUID> clears
    ) {
        chunkHandler = Objects.requireNonNull(chunks, "chunks");
        clearHandler = Objects.requireNonNull(clears, "clears");
    }

    static void accept(final ClientboundSkinChunk chunk) {
        chunkHandler.accept(chunk);
    }

    static void clear(final UUID companionId) {
        clearHandler.accept(companionId);
    }
}
