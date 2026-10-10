package dev.mcai.companion.credential;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class CredentialUtf8 {
    private CredentialUtf8() {
    }

    static byte[] encode(final char[] credential)
            throws CredentialException {
        final char[] validated =
            CredentialRules.validatedCopy(credential);
        ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(validated));
            final byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new CredentialException(
                "API credential is not valid UTF-8",
                exception
            );
        } finally {
            Arrays.fill(validated, '\0');
            if (encoded != null && encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
        }
    }

    static char[] decode(final byte[] encoded)
            throws CredentialException {
        CharBuffer decoded = null;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded));
            final char[] credential = new char[decoded.remaining()];
            decoded.get(credential);
            try {
                return CredentialRules.validatedCopy(credential);
            } finally {
                Arrays.fill(credential, '\0');
            }
        } catch (CharacterCodingException exception) {
            throw new CredentialException(
                "Stored API credential is not valid UTF-8",
                exception
            );
        } finally {
            if (decoded != null && decoded.hasArray()) {
                Arrays.fill(decoded.array(), '\0');
            }
        }
    }
}
