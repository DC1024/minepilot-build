package dev.mcai.companion.skin;

import java.util.Objects;
import java.util.UUID;

public record DefaultSkinChoice(
    String name,
    ArmType armType
) {
    public DefaultSkinChoice {
        name = Objects.requireNonNull(name, "name");
        armType = Objects.requireNonNull(armType, "armType");
    }

    /**
     * Stable legacy two-skin rule for a companion UUID. Even UUID hashes use
     * wide Steve; odd UUID hashes use slim Alex.
     */
    public static DefaultSkinChoice forUuid(final UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return (uuid.hashCode() & 1) == 0
            ? new DefaultSkinChoice("steve", ArmType.CLASSIC)
            : new DefaultSkinChoice("alex", ArmType.SLIM);
    }
}
