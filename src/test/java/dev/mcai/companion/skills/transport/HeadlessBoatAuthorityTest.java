package dev.mcai.companion.skills.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import dev.mcai.companion.skin.AiProfileMarker;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class HeadlessBoatAuthorityTest {
    @Test
    void onlyMarkedProfilesOnTheLogicalServerAreEligible() {
        final GameProfile ordinary = new GameProfile(
            UUID.fromString(
                "36e524b4-9336-4d2d-84a2-3c0b044e4602"
            ),
            "Companion"
        );
        final GameProfile marked =
            AiProfileMarker.markedCopy(ordinary);

        assertFalse(HeadlessBoatAuthority.eligibleProfile(
            false,
            ordinary
        ));
        assertTrue(HeadlessBoatAuthority.eligibleProfile(
            false,
            marked
        ));
        assertFalse(HeadlessBoatAuthority.eligibleProfile(
            true,
            marked
        ));
        assertFalse(HeadlessBoatAuthority.eligibleProfile(
            false,
            null
        ));
    }
}
