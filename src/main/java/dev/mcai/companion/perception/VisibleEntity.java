package dev.mcai.companion.perception;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Minimal semantics available from an entity that passed distance, loaded-chunk
 * and invisibility checks plus either the normal first-person view/clip gate
 * or a real physical-contact gate.  Physical contact is deliberately a
 * separate provenance: it lets the emergency lane identify the mob that is
 * already inside the player's collision volume without pretending that the
 * mob was visible through a wall or outside the player's eyes.
 */
public record VisibleEntity(
        UUID entityId,
        String entityTypeId,
        PerceptionVec3 position,
        PerceptionVec3 relativePosition,
        double distance,
        boolean hostile,
        boolean projectile,
        PerceptionProvenance provenance,
        Map<String, String> visibleProperties
) {
    public static final int MAX_VISIBLE_PROPERTIES = 8;
    private static final Pattern PROPERTY_KEY =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

    public VisibleEntity {
        Objects.requireNonNull(entityId, "entityId");
        entityTypeId = PerceptionValidation.identifier(entityTypeId, "entityTypeId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(relativePosition, "relativePosition");
        Objects.requireNonNull(provenance, "provenance");
        visibleProperties = Collections.unmodifiableMap(
                new TreeMap<>(
                        Objects.requireNonNull(
                                visibleProperties,
                                "visibleProperties"
                        )
                )
        );
        distance = PerceptionValidation.finite(distance, "distance");
        if (distance < 0.0
                || provenance != PerceptionProvenance
                    .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
                && provenance != PerceptionProvenance.PHYSICAL_CONTACT) {
            throw new IllegalArgumentException("Invalid visible entity");
        }
        if (visibleProperties.size() > MAX_VISIBLE_PROPERTIES
                || visibleProperties.entrySet().stream().anyMatch(entry ->
                        entry.getKey() == null
                                || entry.getValue() == null
                                || !PROPERTY_KEY.matcher(
                                    entry.getKey()
                                ).matches()
                                || !VisibleBlockFace.isSafeStateToken(
                                    entry.getValue()
                                )
                )) {
            throw new IllegalArgumentException(
                    "Invalid visible entity properties"
            );
        }
    }

    public VisibleEntity(
            final UUID entityId,
            final String entityTypeId,
            final PerceptionVec3 position,
            final PerceptionVec3 relativePosition,
            final double distance,
            final boolean hostile,
            final boolean projectile,
            final PerceptionProvenance provenance
    ) {
        this(
                entityId,
                entityTypeId,
                position,
                relativePosition,
                distance,
                hostile,
                projectile,
                provenance,
                Map.of()
        );
    }
}
