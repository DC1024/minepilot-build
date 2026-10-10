package dev.mcai.companion.skin.network;

import dev.mcai.companion.skin.ArmType;
import dev.mcai.companion.skin.SkinSpec;
import java.util.Objects;
import java.util.UUID;

/**
 * One bounded clientbound fragment. Chunking keeps every custom payload well
 * below Minecraft's one-MiB packet ceiling even for the largest accepted PNG.
 */
public record ClientboundSkinChunk(
    UUID companionId,
    String sha256,
    ArmType armType,
    int totalLength,
    int chunkIndex,
    int chunkCount,
    byte[] bytes
) {
    public ClientboundSkinChunk {
        companionId = Objects.requireNonNull(companionId, "companionId");
        sha256 = new SkinSpec(
            sha256,
            Objects.requireNonNull(armType, "armType"),
            dev.mcai.companion.skin.SkinFallback.UUID_DEFAULT
        ).sha256();
        bytes = Objects.requireNonNull(bytes, "bytes").clone();
        SkinWireProtocol.validateChunkShape(
            totalLength,
            chunkIndex,
            chunkCount,
            bytes.length
        );
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
