package dev.mcai.companion.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SkinStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void importsValidatedSkinByDigest() throws Exception {
        final Path source = temporaryDirectory.resolve("custom.png");
        writeImage(source, BufferedImage.TYPE_INT_ARGB, 64, 64);
        final SkinStore store = new SkinStore(temporaryDirectory.resolve("cache"));

        final SkinSpec first = store.importLocal(source, ArmType.SLIM);
        final SkinSpec second = store.importLocal(source, ArmType.CLASSIC);

        assertEquals(first.sha256(), second.sha256());
        assertEquals(ArmType.SLIM, first.armType());
        assertTrue(store.isAvailable(first));
        assertTrue(store.resolve(first).startsWith(temporaryDirectory.resolve("cache")));
    }

    @Test
    void rejectsWrongDimensionsAndMissingAlpha() throws Exception {
        final SkinStore store = new SkinStore(temporaryDirectory.resolve("cache"));
        final Path wrongSize = temporaryDirectory.resolve("wrong-size.png");
        final Path noAlpha = temporaryDirectory.resolve("no-alpha.png");
        writeImage(wrongSize, BufferedImage.TYPE_INT_ARGB, 32, 64);
        writeImage(noAlpha, BufferedImage.TYPE_INT_RGB, 64, 64);

        assertThrows(SkinImportException.class, () -> store.importLocal(wrongSize, ArmType.CLASSIC));
        assertThrows(SkinImportException.class, () -> store.importLocal(noAlpha, ArmType.CLASSIC));
    }

    @Test
    void rejectsNonPngAndOversizedInput() throws IOException {
        final SkinStore store = new SkinStore(temporaryDirectory.resolve("cache"));
        final Path text = temporaryDirectory.resolve("not-a-skin.png");
        final Path oversized = temporaryDirectory.resolve("oversized.png");
        Files.writeString(text, "not png");
        Files.write(oversized, new byte[(int) SkinStore.MAX_FILE_BYTES + 1]);

        assertThrows(SkinImportException.class, () -> store.importLocal(text, ArmType.CLASSIC));
        assertThrows(SkinImportException.class, () -> store.importLocal(oversized, ArmType.CLASSIC));
        assertFalse(Files.exists(temporaryDirectory.resolve("cache")));
    }

    @Test
    void defaultChoiceIsStableAndMatchesArmType() {
        final UUID evenHash = new UUID(0L, 0L);
        final UUID oddHash = new UUID(0L, 1L);

        assertEquals(
            new DefaultSkinChoice("steve", ArmType.CLASSIC),
            DefaultSkinChoice.forUuid(evenHash)
        );
        assertEquals(
            new DefaultSkinChoice("alex", ArmType.SLIM),
            DefaultSkinChoice.forUuid(oddHash)
        );
        assertEquals(
            DefaultSkinChoice.forUuid(oddHash),
            DefaultSkinChoice.forUuid(oddHash)
        );
    }

    @Test
    void importsDefensiveBytesAndRepairsAChangedCacheEntry() throws Exception {
        final Path source = temporaryDirectory.resolve("custom.png");
        writeImage(source, BufferedImage.TYPE_INT_ARGB, 64, 64);
        final byte[] bytes = Files.readAllBytes(source);
        final byte expectedFirstByte = bytes[0];
        final SkinStore store = new SkinStore(
            temporaryDirectory.resolve("cache")
        );

        final SkinSpec spec = store.importBytes(bytes, ArmType.CLASSIC);
        bytes[0] = 0;
        final SkinImageData stored = store.readValidated(spec).orElseThrow();
        assertEquals(expectedFirstByte, stored.pngBytes()[0]);

        Files.writeString(store.resolve(spec), "corrupt");
        assertFalse(store.isAvailable(spec));
        store.importLocal(source, ArmType.CLASSIC);
        assertTrue(store.isAvailable(spec));
    }

    @Test
    void parsesOnlyDocumentedArmGeometries() {
        assertEquals(ArmType.CLASSIC, ArmType.parse("classic"));
        assertEquals(ArmType.CLASSIC, ArmType.parse("wide"));
        assertEquals(ArmType.SLIM, ArmType.parse("SLIM"));
        assertThrows(
            IllegalArgumentException.class,
            () -> ArmType.parse("remote-url")
        );
    }

    private static void writeImage(
        final Path target,
        final int imageType,
        final int width,
        final int height
    ) throws IOException {
        final BufferedImage image = new BufferedImage(width, height, imageType);
        ImageIO.write(image, "png", target.toFile());
    }
}
