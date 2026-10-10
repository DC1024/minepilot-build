package dev.mcai.companion.skin;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.stream.ImageInputStream;

/**
 * Imports local modern-format skins into a content-addressed cache.
 *
 * <p>The store never downloads URLs and never trusts the file extension. The
 * renderer only receives a path below {@code cacheRoot} after decoding and
 * validating the PNG.</p>
 */
public final class SkinStore {
    public static final int SKIN_WIDTH = 64;
    public static final int SKIN_HEIGHT = 64;
    public static final long MAX_FILE_BYTES = 1024L * 1024L;

    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private final Path cacheRoot;

    public SkinStore(final Path cacheRoot) {
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot")
            .toAbsolutePath()
            .normalize();
    }

    public SkinSpec importLocal(final Path source, final ArmType armType) throws SkinImportException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(armType, "armType");

        final Path normalizedSource = source.toAbsolutePath().normalize();
        final byte[] bytes;
        try {
            if (!Files.isRegularFile(normalizedSource)) {
                throw new SkinImportException("Skin source is not a regular local file");
            }
            final long size = Files.size(normalizedSource);
            if (size <= 0 || size > MAX_FILE_BYTES) {
                throw new SkinImportException("Skin PNG must be between 1 byte and 1 MiB");
            }
            try (InputStream input = Files.newInputStream(
                normalizedSource,
                StandardOpenOption.READ
            )) {
                bytes = input.readNBytes(Math.toIntExact(MAX_FILE_BYTES) + 1);
            }
            if (bytes.length <= 0 || bytes.length > MAX_FILE_BYTES) {
                throw new SkinImportException(
                    "Skin PNG must be between 1 byte and 1 MiB"
                );
            }
        } catch (IOException exception) {
            throw new SkinImportException("Unable to read skin PNG", exception);
        }

        return importBytes(bytes, armType);
    }

    /**
     * Stores already-local or server-synchronized bytes after applying the
     * exact same validation as a direct file import.
     */
    public SkinSpec importBytes(
        final byte[] requestedBytes,
        final ArmType armType
    ) throws SkinImportException {
        Objects.requireNonNull(requestedBytes, "requestedBytes");
        Objects.requireNonNull(armType, "armType");
        if (requestedBytes.length <= 0
            || requestedBytes.length > MAX_FILE_BYTES) {
            throw new SkinImportException(
                "Skin PNG must be between 1 byte and 1 MiB"
            );
        }
        final byte[] bytes = requestedBytes.clone();
        validatePng(bytes);
        final String digest = sha256(bytes);
        final Path destination = resolve(digest);

        try {
            Files.createDirectories(cacheRoot);
            if (!cachedFileMatches(destination, digest)) {
                final Path temporary = Files.createTempFile(
                    cacheRoot,
                    digest + "-",
                    ".tmp"
                );
                try {
                    Files.write(
                        temporary,
                        bytes,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                    );
                    try {
                        Files.move(
                            temporary,
                            destination,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                        );
                    } catch (
                        java.nio.file.AtomicMoveNotSupportedException ignored
                    ) {
                        Files.move(
                            temporary,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING
                        );
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (IOException exception) {
            throw new SkinImportException("Unable to store validated skin PNG", exception);
        }

        return new SkinSpec(digest, armType, SkinFallback.UUID_DEFAULT);
    }

    public Path resolve(final SkinSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return resolve(spec.sha256());
    }

    public boolean isAvailable(final SkinSpec spec) {
        try {
            return readValidated(spec).isPresent();
        } catch (SkinImportException ignored) {
            return false;
        }
    }

    /**
     * Revalidates cached content before it is rendered or sent over the
     * network. A missing or tampered cache entry is treated as unavailable.
     */
    public Optional<SkinImageData> readValidated(
        final SkinSpec spec
    ) throws SkinImportException {
        Objects.requireNonNull(spec, "spec");
        final Path candidate = resolve(spec);
        if (!Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        final byte[] bytes;
        try (InputStream input = Files.newInputStream(
            candidate,
            StandardOpenOption.READ
        )) {
            bytes = input.readNBytes(Math.toIntExact(MAX_FILE_BYTES) + 1);
        } catch (IOException exception) {
            throw new SkinImportException(
                "Unable to read cached skin PNG",
                exception
            );
        }
        if (bytes.length <= 0 || bytes.length > MAX_FILE_BYTES) {
            return Optional.empty();
        }
        validatePng(bytes);
        if (!MessageDigest.isEqual(
            HexFormat.of().parseHex(spec.sha256()),
            MessageDigestHolder.digest(bytes)
        )) {
            return Optional.empty();
        }
        return Optional.of(new SkinImageData(spec, bytes));
    }

    private Path resolve(final String digest) {
        final Path candidate = cacheRoot.resolve(digest + ".png").normalize();
        if (!candidate.getParent().equals(cacheRoot)) {
            throw new IllegalArgumentException("Skin digest escaped the cache directory");
        }
        return candidate;
    }

    private static void validatePng(final byte[] bytes) throws SkinImportException {
        if (bytes.length < PNG_SIGNATURE.length) {
            throw new SkinImportException("Skin file is not a PNG");
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (bytes[index] != PNG_SIGNATURE[index]) {
                throw new SkinImportException("Skin file is not a PNG");
            }
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(
            new java.io.ByteArrayInputStream(bytes)
        )) {
            if (input == null) {
                throw new SkinImportException("Skin PNG could not be decoded");
            }
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new SkinImportException("Skin PNG could not be decoded");
            }
            final ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!"png".equalsIgnoreCase(reader.getFormatName())) {
                    throw new SkinImportException("Skin file is not a PNG");
                }
                if (reader.getWidth(0) != SKIN_WIDTH
                    || reader.getHeight(0) != SKIN_HEIGHT) {
                    throw new SkinImportException(
                        "Skin PNG must be exactly 64x64 pixels"
                    );
                }
                if (!hasAlphaChannel(reader)) {
                    throw new SkinImportException(
                        "Skin PNG must contain an alpha channel"
                    );
                }
                final BufferedImage image = reader.read(0);
                if (image == null
                    || image.getWidth() != SKIN_WIDTH
                    || image.getHeight() != SKIN_HEIGHT
                    || !image.getColorModel().hasAlpha()) {
                    throw new SkinImportException(
                        "Skin PNG failed decoded-image validation"
                    );
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new SkinImportException("Skin PNG could not be decoded", exception);
        }
    }

    private static String sha256(final byte[] bytes) throws SkinImportException {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new SkinImportException("JVM does not provide SHA-256", exception);
        }
    }

    private static boolean cachedFileMatches(
        final Path candidate,
        final String expectedDigest
    ) throws IOException {
        if (!Files.isRegularFile(candidate)
            || Files.size(candidate) <= 0
            || Files.size(candidate) > MAX_FILE_BYTES) {
            return false;
        }
        try (InputStream input = Files.newInputStream(candidate)) {
            final byte[] bytes = input.readNBytes(
                Math.toIntExact(MAX_FILE_BYTES) + 1
            );
            return bytes.length <= MAX_FILE_BYTES
                && expectedDigest.equals(
                    HexFormat.of().formatHex(
                        MessageDigestHolder.digest(bytes)
                    )
                );
        }
    }

    private static boolean hasAlphaChannel(
        final ImageReader reader
    ) throws IOException {
        final ImageTypeSpecifier raw = reader.getRawImageType(0);
        if (raw != null) {
            return raw.getColorModel().hasAlpha();
        }
        final Iterator<ImageTypeSpecifier> types = reader.getImageTypes(0);
        while (types.hasNext()) {
            if (types.next().getColorModel().hasAlpha()) {
                return true;
            }
        }
        return false;
    }

    private static final class MessageDigestHolder {
        private MessageDigestHolder() {
        }

        private static byte[] digest(final byte[] bytes) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(bytes);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(
                    "JVM does not provide SHA-256",
                    exception
                );
            }
        }
    }
}
