package dev.mcai.companion.skin.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mcai.companion.skin.ArmType;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

final class SkinNetworkCodecTest {
    @Test
    void codecRetainsBoundedChunkMetadata() {
        final UUID companionId = UUID.randomUUID();
        final byte[] bytes = new byte[SkinWireProtocol.CHUNK_BYTES];
        final ClientboundSkinChunk expected = new ClientboundSkinChunk(
            companionId,
            "a".repeat(64),
            ArmType.SLIM,
            SkinWireProtocol.CHUNK_BYTES + 5,
            0,
            2,
            bytes
        );
        final FriendlyByteBuf buffer = new FriendlyByteBuf(
            Unpooled.buffer()
        );
        try {
            SkinNetwork.encodeChunk(expected, buffer);
            final ClientboundSkinChunk decoded =
                SkinNetwork.decodeChunk(buffer);

            assertEquals(expected.companionId(), decoded.companionId());
            assertEquals(expected.sha256(), decoded.sha256());
            assertEquals(expected.armType(), decoded.armType());
            assertEquals(expected.totalLength(), decoded.totalLength());
            assertEquals(expected.chunkIndex(), decoded.chunkIndex());
            assertEquals(expected.chunkCount(), decoded.chunkCount());
            assertArrayEquals(expected.bytes(), decoded.bytes());
        } finally {
            buffer.release();
        }
    }
}
