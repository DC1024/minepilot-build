package dev.mcai.companion.skin;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.util.Objects;

/**
 * Explicit marker carried in the vanilla GameProfile property map.
 *
 * <p>The ADD_PLAYER packet already serializes profile properties, so this
 * marker reaches clients without name or UUID heuristics. It is a declaration
 * by the connected server, not a cross-server identity credential.</p>
 */
public final class AiProfileMarker {
    public static final String PROPERTY_NAME = "mcai_companion_ai";
    public static final String PROPERTY_VALUE = "headless_player:v1";
    public static final int MAX_VANILLA_PROFILE_PROPERTIES = 16;

    private AiProfileMarker() {
    }

    /**
     * Returns a marked immutable-profile copy and leaves the input untouched.
     */
    public static GameProfile markedCopy(final GameProfile profile) {
        Objects.requireNonNull(profile, "profile");
        final ImmutableMultimap.Builder<String, Property> properties =
            ImmutableMultimap.builder();
        int retained = 0;
        for (var entry : profile.properties().entries()) {
            if (!PROPERTY_NAME.equals(entry.getKey())) {
                properties.put(entry.getKey(), entry.getValue());
                retained++;
            }
        }
        if (retained >= MAX_VANILLA_PROFILE_PROPERTIES) {
            throw new IllegalArgumentException(
                "GameProfile has no property slot for the AI marker"
            );
        }
        properties.put(
            PROPERTY_NAME,
            new Property(PROPERTY_NAME, PROPERTY_VALUE)
        );
        return new GameProfile(
            profile.id(),
            profile.name(),
            new PropertyMap(properties.build())
        );
    }

    /**
     * Requires exactly one unsigned property with the versioned marker value.
     */
    public static boolean isMarked(final GameProfile profile) {
        Objects.requireNonNull(profile, "profile");
        final var markers = profile.properties().get(PROPERTY_NAME);
        if (markers.size() != 1) {
            return false;
        }
        final Property marker = markers.iterator().next();
        return PROPERTY_NAME.equals(marker.name())
            && PROPERTY_VALUE.equals(marker.value())
            && !marker.hasSignature();
    }
}
