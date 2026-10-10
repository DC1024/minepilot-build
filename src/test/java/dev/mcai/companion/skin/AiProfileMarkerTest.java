package dev.mcai.companion.skin;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.codec.ByteBufCodecs;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AiProfileMarkerTest {
    @Test
    void createsAnUnambiguousCopyWithoutMutatingTheInput() {
        final GameProfile original = profileWith(
            new Property("textures", "existing-texture-data")
        );

        final GameProfile marked = AiProfileMarker.markedCopy(original);

        assertNotSame(original, marked);
        assertFalse(AiProfileMarker.isMarked(original));
        assertTrue(AiProfileMarker.isMarked(marked));
        assertEquals(
            original.properties().get("textures"),
            marked.properties().get("textures")
        );
        assertEquals(
            1,
            marked.properties().get(AiProfileMarker.PROPERTY_NAME).size()
        );
    }

    @Test
    void markerSurvivesTheVanillaGameProfileWireCodec() {
        final GameProfile expected = AiProfileMarker.markedCopy(profileWith());
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ByteBufCodecs.GAME_PROFILE.encode(buffer, expected);
            final GameProfile decoded = ByteBufCodecs.GAME_PROFILE.decode(buffer);

            assertEquals(expected.id(), decoded.id());
            assertEquals(expected.name(), decoded.name());
            assertTrue(AiProfileMarker.isMarked(decoded));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsDuplicateSignedOrWrongValueMarkers() {
        final Property correct = new Property(
            AiProfileMarker.PROPERTY_NAME,
            AiProfileMarker.PROPERTY_VALUE
        );
        assertFalse(AiProfileMarker.isMarked(profileWith(
            correct,
            correct
        )));
        assertFalse(AiProfileMarker.isMarked(profileWith(new Property(
            AiProfileMarker.PROPERTY_NAME,
            "different-version"
        ))));
        assertFalse(AiProfileMarker.isMarked(profileWith(new Property(
            AiProfileMarker.PROPERTY_NAME,
            AiProfileMarker.PROPERTY_VALUE,
            "not-a-valid-marker-signature"
        ))));
    }

    @Test
    void refusesToOverflowTheVanillaSixteenPropertyLimit() {
        final ImmutableMultimap.Builder<String, Property> properties =
            ImmutableMultimap.builder();
        for (int index = 0; index < AiProfileMarker.MAX_VANILLA_PROFILE_PROPERTIES; index++) {
            final String name = "property_" + index;
            properties.put(name, new Property(name, "value"));
        }
        final GameProfile full = new GameProfile(
            UUID.randomUUID(),
            "Companion",
            new PropertyMap(properties.build())
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> AiProfileMarker.markedCopy(full)
        );
    }

    private static GameProfile profileWith(final Property... properties) {
        final ImmutableMultimap.Builder<String, Property> values =
            ImmutableMultimap.builder();
        for (Property property : properties) {
            values.put(property.name(), property);
        }
        return new GameProfile(
            UUID.fromString("8c1b26ea-d900-4c2f-8e56-6aa6a53f419d"),
            "Companion",
            new PropertyMap(values.build())
        );
    }
}
