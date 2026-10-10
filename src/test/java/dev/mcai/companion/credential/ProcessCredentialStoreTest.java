package dev.mcai.companion.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

final class ProcessCredentialStoreTest {
    @Test
    void storesDefensiveCopiesAndClearsSession() throws Exception {
        final ProcessCredentialStore store = new ProcessCredentialStore();
        final char[] input = "sk-test-not-a-real-secret".toCharArray();
        final char[] expected = input.clone();

        store.save(input);
        Arrays.fill(input, 'x');
        final char[] first = store.load().orElseThrow();
        assertArrayEquals(expected, first);

        Arrays.fill(first, 'y');
        assertArrayEquals(expected, store.load().orElseThrow());
        assertFalse(store.persistent());

        store.clear();
        assertTrue(store.load().isEmpty());
    }

    @Test
    void rejectsWhitespaceControlsAndOversizedValues() {
        final ProcessCredentialStore store = new ProcessCredentialStore();

        assertThrows(CredentialException.class, () -> store.save(new char[0]));
        assertThrows(CredentialException.class, () -> store.save("bad key".toCharArray()));
        assertThrows(CredentialException.class, () -> store.save("bad\nkey".toCharArray()));
        assertThrows(
            CredentialException.class,
            () -> store.save(new char[CredentialRules.MAXIMUM_CHARACTERS + 1])
        );
        assertEquals(0, store.load().stream().count());
    }
}
