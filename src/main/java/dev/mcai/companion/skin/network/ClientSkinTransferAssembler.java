package dev.mcai.companion.skin.network;

import dev.mcai.companion.skin.SkinFallback;
import dev.mcai.companion.skin.SkinSpec;
import dev.mcai.companion.skin.SkinWireSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Strict in-order assembly with bounded memory. Only an index-zero packet can
 * begin or replace a transfer, preventing orphan fragments from allocating a
 * one-MiB buffer.
 */
public final class ClientSkinTransferAssembler {
    private static final int MAX_SIMULTANEOUS_TRANSFERS = 4;

    private final Map<UUID, PendingTransfer> pending = new HashMap<>();

    public Optional<SkinWireSnapshot> accept(
        final ClientboundSkinChunk chunk
    ) {
        final UUID companionId = chunk.companionId();
        PendingTransfer transfer = pending.get(companionId);
        if (chunk.chunkIndex() == 0) {
            if (transfer == null
                && pending.size() >= MAX_SIMULTANEOUS_TRANSFERS) {
                pending.clear();
            }
            transfer = new PendingTransfer(chunk);
            pending.put(companionId, transfer);
        } else if (transfer == null || !transfer.matches(chunk)) {
            pending.remove(companionId);
            return Optional.empty();
        }

        if (!transfer.accept(chunk)) {
            pending.remove(companionId);
            return Optional.empty();
        }
        if (!transfer.complete()) {
            return Optional.empty();
        }

        pending.remove(companionId);
        return Optional.of(new SkinWireSnapshot(
            companionId,
            new SkinSpec(
                chunk.sha256(),
                chunk.armType(),
                SkinFallback.UUID_DEFAULT
            ),
            transfer.bytes
        ));
    }

    public void clear(final UUID companionId) {
        pending.remove(companionId);
    }

    public void clearAll() {
        pending.clear();
    }

    int pendingCount() {
        return pending.size();
    }

    private static final class PendingTransfer {
        private final UUID companionId;
        private final String sha256;
        private final dev.mcai.companion.skin.ArmType armType;
        private final int chunkCount;
        private final byte[] bytes;
        private int nextIndex;

        private PendingTransfer(final ClientboundSkinChunk first) {
            companionId = first.companionId();
            sha256 = first.sha256();
            armType = first.armType();
            chunkCount = first.chunkCount();
            bytes = new byte[first.totalLength()];
        }

        private boolean matches(final ClientboundSkinChunk chunk) {
            return companionId.equals(chunk.companionId())
                && sha256.equals(chunk.sha256())
                && armType == chunk.armType()
                && bytes.length == chunk.totalLength()
                && chunkCount == chunk.chunkCount();
        }

        private boolean accept(final ClientboundSkinChunk chunk) {
            if (!matches(chunk) || chunk.chunkIndex() != nextIndex) {
                return false;
            }
            final byte[] fragment = chunk.bytes();
            System.arraycopy(
                fragment,
                0,
                bytes,
                nextIndex * SkinWireProtocol.CHUNK_BYTES,
                fragment.length
            );
            nextIndex++;
            return true;
        }

        private boolean complete() {
            return nextIndex == chunkCount;
        }
    }
}
