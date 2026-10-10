package dev.mcai.companion.progression;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.skills.building.ShelterPlan;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

/**
 * Goal-scoped geometry of a shelter generated from observed terrain. This is
 * enough to reverify enclosure, volume, door, light, and support after a
 * restart without storing or loading a fixed building blueprint.
 */
public record VerifiedShelterEvidence(
        long goalRevision,
        String dimension,
        int originX,
        int originY,
        int originZ,
        int interiorWidth,
        int interiorDepth,
        int interiorHeight,
        int doorX,
        int doorY,
        int doorZ,
        int lightX,
        int lightY,
        int lightZ,
        String structuralItemId,
        String lightItemId
) {
    public static final Codec<VerifiedShelterEvidence> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.LONG.fieldOf("goal_revision")
                            .forGetter(
                                    VerifiedShelterEvidence::goalRevision
                            ),
                    Codec.STRING.fieldOf("dimension")
                            .forGetter(
                                    VerifiedShelterEvidence::dimension
                            ),
                    Codec.INT.fieldOf("origin_x")
                            .forGetter(VerifiedShelterEvidence::originX),
                    Codec.INT.fieldOf("origin_y")
                            .forGetter(VerifiedShelterEvidence::originY),
                    Codec.INT.fieldOf("origin_z")
                            .forGetter(VerifiedShelterEvidence::originZ),
                    Codec.INT.fieldOf("interior_width")
                            .forGetter(
                                    VerifiedShelterEvidence::interiorWidth
                            ),
                    Codec.INT.fieldOf("interior_depth")
                            .forGetter(
                                    VerifiedShelterEvidence::interiorDepth
                            ),
                    Codec.INT.fieldOf("interior_height")
                            .forGetter(
                                    VerifiedShelterEvidence::interiorHeight
                            ),
                    Codec.INT.fieldOf("door_x")
                            .forGetter(VerifiedShelterEvidence::doorX),
                    Codec.INT.fieldOf("door_y")
                            .forGetter(VerifiedShelterEvidence::doorY),
                    Codec.INT.fieldOf("door_z")
                            .forGetter(VerifiedShelterEvidence::doorZ),
                    Codec.INT.fieldOf("light_x")
                            .forGetter(VerifiedShelterEvidence::lightX),
                    Codec.INT.fieldOf("light_y")
                            .forGetter(VerifiedShelterEvidence::lightY),
                    Codec.INT.fieldOf("light_z")
                            .forGetter(VerifiedShelterEvidence::lightZ),
                    Codec.STRING.fieldOf("structural_item")
                            .forGetter(
                                    VerifiedShelterEvidence
                                            ::structuralItemId
                            ),
                    Codec.STRING.fieldOf("light_item")
                            .forGetter(
                                    VerifiedShelterEvidence::lightItemId
                            )
            ).apply(instance, VerifiedShelterEvidence::new));

    public VerifiedShelterEvidence {
        if (goalRevision < 0
                || interiorWidth < 3
                || interiorWidth > 15
                || interiorDepth < 3
                || interiorDepth > 15
                || interiorHeight < 2
                || interiorHeight > 8
                || !boundedCoordinate(originX)
                || !boundedCoordinate(originY)
                || !boundedCoordinate(originZ)
                || !boundedCoordinate(doorX)
                || !boundedCoordinate(doorY)
                || !boundedCoordinate(doorZ)
                || !boundedCoordinate(lightX)
                || !boundedCoordinate(lightY)
                || !boundedCoordinate(lightZ)) {
            throw new IllegalArgumentException(
                    "Verified shelter geometry is invalid"
            );
        }
        dimension = Objects.requireNonNull(
                dimension,
                "dimension"
        );
        DimensionRef.parse(dimension);
        structuralItemId = Objects.requireNonNull(
                structuralItemId,
                "structuralItemId"
        );
        if (!structuralItemId.matches(
                "[a-z0-9_.-]+:[a-z0-9_./-]+"
        )) {
            throw new IllegalArgumentException(
                    "Verified shelter material is invalid"
            );
        }
        lightItemId = Objects.requireNonNull(
                lightItemId,
                "lightItemId"
        );
        if (!lightItemId.matches(
                "[a-z0-9_.-]+:[a-z0-9_./-]+"
        )) {
            throw new IllegalArgumentException(
                    "Verified shelter light is invalid"
            );
        }
        final GridPos door = new GridPos(doorX, doorY, doorZ);
        final GridPos light = new GridPos(lightX, lightY, lightZ);
        final int exteriorWidth = interiorWidth + 2;
        final int exteriorDepth = interiorDepth + 2;
        final boolean doorInsideExterior =
                door.x() >= originX
                    && door.x() < originX + exteriorWidth
                    && door.y() >= originY
                    && door.y() < originY + interiorHeight
                    && door.z() >= originZ
                    && door.z() < originZ + exteriorDepth;
        final boolean lightInsideInterior =
                light.x() > originX
                    && light.x() < originX + exteriorWidth - 1
                    && light.y() >= originY
                    && light.y() < originY + interiorHeight
                    && light.z() > originZ
                    && light.z() < originZ + exteriorDepth - 1;
        if (!doorInsideExterior
                || !lightInsideInterior
                || door.equals(light)
                || door.y() != originY
                || light.y() != originY
                || (door.x() != originX
                    && door.x()
                        != originX + exteriorWidth - 1
                    && door.z() != originZ
                    && door.z()
                        != originZ + exteriorDepth - 1)) {
            throw new IllegalArgumentException(
                    "Verified shelter fixtures are out of bounds"
            );
        }
    }

    public static VerifiedShelterEvidence from(
            final long goalRevision,
            final ShelterPlan plan
    ) {
        Objects.requireNonNull(plan, "plan");
        return new VerifiedShelterEvidence(
                goalRevision,
                plan.dimension().id(),
                plan.origin().x(),
                plan.origin().y(),
                plan.origin().z(),
                plan.interiorWidth(),
                plan.interiorDepth(),
                plan.interiorHeight(),
                plan.doorLower().x(),
                plan.doorLower().y(),
                plan.doorLower().z(),
                plan.lightPosition().x(),
                plan.lightPosition().y(),
                plan.lightPosition().z(),
                plan.structuralItemId(),
                plan.lightItemId()
        );
    }

    public GridPos origin() {
        return new GridPos(originX, originY, originZ);
    }

    public GridPos doorLower() {
        return new GridPos(doorX, doorY, doorZ);
    }

    public GridPos lightPosition() {
        return new GridPos(lightX, lightY, lightZ);
    }

    public int exteriorWidth() {
        return interiorWidth + 2;
    }

    public int exteriorDepth() {
        return interiorDepth + 2;
    }

    private static boolean boundedCoordinate(final int value) {
        return value >= -30_000_000 && value <= 30_000_000;
    }
}
