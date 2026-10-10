package dev.mcai.companion.client;

import com.mojang.authlib.GameProfile;
import dev.mcai.companion.skin.AiProfileMarker;
import net.minecraft.world.entity.player.PlayerModelType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClientAiSkinResolverTest {
    @Test
    void mapsEvenUuidHashToWideSteve() {
        final var skin = ClientAiSkinResolver.fallbackFor(new UUID(0L, 0L));

        assertEquals(PlayerModelType.WIDE, skin.model());
        assertEquals(
            "minecraft:textures/entity/player/wide/steve.png",
            skin.body().texturePath().toString()
        );
    }

    @Test
    void mapsOddUuidHashToSlimAlex() {
        final var skin = ClientAiSkinResolver.fallbackFor(new UUID(0L, 1L));

        assertEquals(PlayerModelType.SLIM, skin.model());
        assertEquals(
            "minecraft:textures/entity/player/slim/alex.png",
            skin.body().texturePath().toString()
        );
    }

    @Test
    void onlyMarkedProfilesReceiveAnOverride() {
        final GameProfile ordinary = new GameProfile(
            UUID.randomUUID(),
            "OrdinaryPlayer"
        );
        assertTrue(ClientAiSkinResolver.overrideFor(ordinary).isEmpty());
        assertTrue(
            ClientAiSkinResolver.overrideFor(
                AiProfileMarker.markedCopy(ordinary)
            ).isPresent()
        );
    }
}
