package dev.mcai.companion.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CompanionSkinDataTest {
    private static final String DIGEST =
        "0123456789abcdef0123456789abcdef"
            + "0123456789abcdef0123456789abcdef";

    @Test
    void persistsOnlyDigestArmAndFallbackPolicy() {
        final CompanionSkinData data = new CompanionSkinData();
        assertTrue(data.isDefault());
        assertTrue(data.selection().isEmpty());

        data.select(new SkinSpec(
            DIGEST,
            ArmType.SLIM,
            SkinFallback.UUID_DEFAULT
        ));

        final SkinSpec selection = data.selection().orElseThrow();
        assertEquals(DIGEST, selection.sha256());
        assertEquals(ArmType.SLIM, selection.armType());
        assertEquals(SkinFallback.UUID_DEFAULT, selection.fallback());
        assertFalse(data.isDefault());
    }

    @Test
    void explicitDisableSurvivesAsDifferentStateFromFirstRun() {
        final CompanionSkinData data = new CompanionSkinData();
        data.disable();

        assertTrue(data.isDisabled());
        assertTrue(data.selection().isEmpty());
    }
}
