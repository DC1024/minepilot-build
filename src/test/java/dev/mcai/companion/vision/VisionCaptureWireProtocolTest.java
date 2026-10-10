package dev.mcai.companion.vision;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class VisionCaptureWireProtocolTest {
    @Test
    void reassemblesLargePngOutOfOrderBelowServerboundLimit() {
        final byte[] png = png(
                VisionCaptureWireProtocol.CHUNK_BYTES * 3 + 137
        );
        final ServerboundVisionCaptureResult source = result("ok", png);
        final List<ServerboundVisionCaptureChunk> chunks =
                new ArrayList<>(VisionCaptureWireProtocol.chunks(source));
        assertEquals(4, chunks.size());
        assertTrue(chunks.stream().allMatch(chunk ->
                chunk.bytes().length <= VisionCaptureWireProtocol.CHUNK_BYTES
        ));
        Collections.reverse(chunks);

        final VisionCaptureChunkAssembler assembler =
                new VisionCaptureChunkAssembler(chunks.getFirst());
        ServerboundVisionCaptureResult completed = null;
        for (ServerboundVisionCaptureChunk chunk : chunks) {
            final var candidate = assembler.accept(chunk);
            if (candidate.isPresent()) {
                completed = candidate.orElseThrow();
            }
        }

        assertArrayEquals(png, completed.png());
        assembler.destroy();
        completed.destroy();
        source.destroy();
        chunks.forEach(ServerboundVisionCaptureChunk::destroy);
    }

    @Test
    void ignoresDuplicateChunkUntilEveryIndexArrives() {
        final byte[] png = png(
                VisionCaptureWireProtocol.CHUNK_BYTES + 31
        );
        final ServerboundVisionCaptureResult source = result("ok", png);
        final List<ServerboundVisionCaptureChunk> chunks =
                VisionCaptureWireProtocol.chunks(source);
        final VisionCaptureChunkAssembler assembler =
                new VisionCaptureChunkAssembler(chunks.getFirst());

        assertFalse(assembler.accept(chunks.getFirst()).isPresent());
        assertFalse(assembler.accept(chunks.getFirst()).isPresent());
        assertTrue(assembler.accept(chunks.getLast()).isPresent());
        assembler.destroy();
        source.destroy();
        chunks.forEach(ServerboundVisionCaptureChunk::destroy);
    }

    @Test
    void rejectsChangedMetadataAndMalformedFailurePayload() {
        final byte[] png = png(
                VisionCaptureWireProtocol.CHUNK_BYTES + 31
        );
        final ServerboundVisionCaptureResult source = result("ok", png);
        final List<ServerboundVisionCaptureChunk> chunks =
                VisionCaptureWireProtocol.chunks(source);
        final VisionCaptureChunkAssembler assembler =
                new VisionCaptureChunkAssembler(chunks.getFirst());
        final ServerboundVisionCaptureChunk changed =
                new ServerboundVisionCaptureChunk(
                        chunks.getLast().requestId(),
                        chunks.getLast().companionId(),
                        chunks.getLast().code(),
                        chunks.getLast().totalLength(),
                        "0".repeat(64),
                        chunks.getLast().chunkIndex(),
                        chunks.getLast().chunkCount(),
                        chunks.getLast().bytes()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> assembler.accept(changed)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ServerboundVisionCaptureChunk(
                        1L,
                        UUID.randomUUID(),
                        "capture_failed",
                        1,
                        "",
                        0,
                        1,
                        new byte[] {1}
                )
        );
        assembler.destroy();
        changed.destroy();
        source.destroy();
        chunks.forEach(ServerboundVisionCaptureChunk::destroy);
    }

    @Test
    void sendsFailureAsOneEmptyChunk() {
        final ServerboundVisionCaptureResult source =
                result("capture_encode_failed", new byte[0]);
        final List<ServerboundVisionCaptureChunk> chunks =
                VisionCaptureWireProtocol.chunks(source);

        assertEquals(1, chunks.size());
        assertEquals(0, chunks.getFirst().totalLength());
        assertEquals(0, chunks.getFirst().bytes().length);
        source.destroy();
        chunks.forEach(ServerboundVisionCaptureChunk::destroy);
    }

    private static ServerboundVisionCaptureResult result(
            final String code,
            final byte[] png
    ) {
        return new ServerboundVisionCaptureResult(
                7L,
                UUID.fromString("d645c291-69e2-4bd5-931f-cd46eeaba488"),
                code,
                png
        );
    }

    private static byte[] png(final int length) {
        final byte[] bytes = new byte[length];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 31 + 17);
        }
        bytes[0] = (byte) 0x89;
        bytes[1] = 0x50;
        bytes[2] = 0x4e;
        bytes[3] = 0x47;
        bytes[4] = 0x0d;
        bytes[5] = 0x0a;
        bytes[6] = 0x1a;
        bytes[7] = 0x0a;
        return bytes;
    }
}
