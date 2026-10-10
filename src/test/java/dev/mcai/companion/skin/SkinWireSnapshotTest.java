package dev.mcai.companion.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SkinWireSnapshotTest {
    @Test
    void copiesBytesAndRequiresMatchingDigest() throws Exception {
        final byte[] bytes = {1, 2, 3, 4};
        final String digest = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes)
        );
        final SkinSpec spec = new SkinSpec(
            digest,
            ArmType.CLASSIC,
            SkinFallback.UUID_DEFAULT
        );
        final SkinWireSnapshot snapshot = new SkinWireSnapshot(
            UUID.randomUUID(),
            spec,
            bytes
        );

        bytes[0] = 9;
        assertEquals(1, snapshot.pngBytes()[0]);
        final byte[] returned = snapshot.pngBytes();
        returned[0] = 8;
        assertEquals(1, snapshot.pngBytes()[0]);

        assertThrows(
            IllegalArgumentException.class,
            () -> new SkinWireSnapshot(
                UUID.randomUUID(),
                spec,
                new byte[] {4, 3, 2, 1}
            )
        );
    }
}
