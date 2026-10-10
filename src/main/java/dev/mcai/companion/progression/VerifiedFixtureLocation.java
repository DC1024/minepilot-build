package dev.mcai.companion.progression;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

/**
 * Exact location of a fixture the companion has opened through a normal
 * first-person block interaction.
 */
public record VerifiedFixtureLocation(
        String dimension,
        int x,
        int y,
        int z
) {
    public static final Codec<VerifiedFixtureLocation> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.STRING.fieldOf("dimension")
                            .forGetter(
                                    VerifiedFixtureLocation::dimension
                            ),
                    Codec.INT.fieldOf("x")
                            .forGetter(VerifiedFixtureLocation::x),
                    Codec.INT.fieldOf("y")
                            .forGetter(VerifiedFixtureLocation::y),
                    Codec.INT.fieldOf("z")
                            .forGetter(VerifiedFixtureLocation::z)
            ).apply(instance, VerifiedFixtureLocation::new));

    public VerifiedFixtureLocation {
        dimension = Objects.requireNonNull(dimension, "dimension");
        DimensionRef.parse(dimension);
        if (!bounded(x) || !bounded(y) || !bounded(z)) {
            throw new IllegalArgumentException(
                    "Foundation fixture coordinate is invalid"
            );
        }
    }

    public GridPos position() {
        return new GridPos(x, y, z);
    }

    private static boolean bounded(final int value) {
        return value >= -30_000_000 && value <= 30_000_000;
    }
}
