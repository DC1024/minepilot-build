package dev.mcai.companion.client;

import com.mojang.authlib.GameProfile;
import dev.mcai.companion.skin.AiProfileMarker;
import dev.mcai.companion.skin.ArmType;
import dev.mcai.companion.skin.DefaultSkinChoice;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-only mapping from a marked AI profile to two built-in vanilla skins.
 */
public final class ClientAiSkinResolver {
    private static final PlayerSkin WIDE_STEVE = builtIn(
        "entity/player/wide/steve",
        PlayerModelType.WIDE
    );
    private static final PlayerSkin SLIM_ALEX = builtIn(
        "entity/player/slim/alex",
        PlayerModelType.SLIM
    );

    private ClientAiSkinResolver() {
    }

    public static Optional<PlayerSkin> overrideFor(final GameProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (!AiProfileMarker.isMarked(profile)) {
            return Optional.empty();
        }
        return Optional.of(
            ClientSkinRuntime.customSkin(profile.id())
                .orElseGet(() -> fallbackFor(profile.id()))
        );
    }

    public static PlayerSkin fallbackFor(final UUID profileId) {
        final DefaultSkinChoice choice = DefaultSkinChoice.forUuid(profileId);
        return choice.armType() == ArmType.CLASSIC ? WIDE_STEVE : SLIM_ALEX;
    }

    private static PlayerSkin builtIn(
        final String assetPath,
        final PlayerModelType model
    ) {
        return new PlayerSkin(
            new ClientAsset.ResourceTexture(
                Identifier.withDefaultNamespace(assetPath)
            ),
            null,
            null,
            model,
            true
        );
    }
}
