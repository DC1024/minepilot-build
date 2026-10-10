package dev.mcai.companion.skin.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.skin.ArmType;
import dev.mcai.companion.skin.SkinFallback;
import dev.mcai.companion.skin.SkinSpec;
import dev.mcai.companion.skin.SkinWireSnapshot;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ClientSkinTransferAssemblerTest {
    @Test
    void roundTripsMaximumSizedContentInBoundedChunks() throws Exception {
        final byte[] bytes = new byte[1024 * 1024];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 31);
        }
        final UUID companionId = UUID.randomUUID();
        final SkinWireSnapshot expected = snapshot(companionId, bytes);
        final List<ClientboundSkinChunk> chunks =
            SkinWireProtocol.chunks(expected);
        final ClientSkinTransferAssembler assembler =
            new ClientSkinTransferAssembler();
        Optional<SkinWireSnapshot> completed = Optional.empty();

        for (ClientboundSkinChunk chunk : chunks) {
            completed = assembler.accept(chunk);
        }

        assertEquals(SkinWireProtocol.MAX_CHUNK_COUNT, chunks.size());
        assertEquals(companionId, completed.orElseThrow().companionId());
        assertArrayEquals(bytes, completed.orElseThrow().pngBytes());
        assertEquals(0, assembler.pendingCount());
    }

    @Test
    void rejectsOrphanAndOutOfOrderFragmentsWithoutRetainingMemory()
            throws Exception {
        final SkinWireSnapshot snapshot = snapshot(
            UUID.randomUUID(),
            new byte[SkinWireProtocol.CHUNK_BYTES + 1]
        );
        final List<ClientboundSkinChunk> chunks =
            SkinWireProtocol.chunks(snapshot);
        final ClientSkinTransferAssembler assembler =
            new ClientSkinTransferAssembler();

        assertTrue(assembler.accept(chunks.get(1)).isEmpty());
        assertEquals(0, assembler.pendingCount());
        assertTrue(assembler.accept(chunks.get(0)).isEmpty());
        assertEquals(1, assembler.pendingCount());
        assertTrue(assembler.accept(chunks.get(0)).isEmpty());
        assertEquals(1, assembler.pendingCount());
        assertTrue(assembler.accept(chunks.get(1)).isPresent());
        assertEquals(0, assembler.pendingCount());
    }

    @Test
    void refusesDigestMismatchAtCompletion() throws Exception {
        final SkinWireSnapshot snapshot = snapshot(
            UUID.randomUUID(),
            new byte[] {1, 2, 3}
        );
        final ClientboundSkinChunk original =
            SkinWireProtocol.chunks(snapshot).getFirst();
        final String wrongDigest = "0".repeat(64);
        final ClientboundSkinChunk forged = new ClientboundSkinChunk(
            original.companionId(),
            wrongDigest,
            ArmType.CLASSIC,
            original.totalLength(),
            original.chunkIndex(),
            original.chunkCount(),
            original.bytes()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> new ClientSkinTransferAssembler().accept(forged)
        );
    }

    private static SkinWireSnapshot snapshot(
        final UUID companionId,
        final byte[] bytes
    ) throws Exception {
        final String digest = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes)
        );
        return new SkinWireSnapshot(
            companionId,
            new SkinSpec(
                digest,
                ArmType.CLASSIC,
                SkinFallback.UUID_DEFAULT
            ),
            bytes
        );
    }
}
