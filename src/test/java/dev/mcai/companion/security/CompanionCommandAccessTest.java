package dev.mcai.companion.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CompanionCommandAccessTest {
    private static final UUID PLAYER_ID = UUID.fromString(
            "123e4567-e89b-12d3-a456-426614174000"
    );

    @Test
    void explicitChatAllowListIsUuidOnlyAndFailsClosed() {
        assertTrue(CompanionCommandAccess.isExplicitlyAllowed(
                PLAYER_ID,
                List.of(" 123e4567-e89b-12d3-a456-426614174000 ")
        ));
        assertFalse(CompanionCommandAccess.isExplicitlyAllowed(
                PLAYER_ID,
                List.of("TestHuman", "not-a-uuid")
        ));
        assertFalse(CompanionCommandAccess.isExplicitlyAllowed(
                PLAYER_ID,
                List.of("123e4567-e89b-12d3-a456-426614174001")
        ));
    }
}
