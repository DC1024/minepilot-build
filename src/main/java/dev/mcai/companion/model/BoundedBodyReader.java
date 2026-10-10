package dev.mcai.companion.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class BoundedBodyReader {
    private BoundedBodyReader() {}

    static String readUtf8(InputStream input, int maxBytes) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("HTTP response exceeds the configured size limit");
                }
                output.write(buffer, 0, read);
            }
            try {
                CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(output.toByteArray()));
                return decoded.toString();
            } catch (CharacterCodingException exception) {
                throw new IOException("HTTP response is not valid UTF-8", exception);
            }
        }
    }
}
