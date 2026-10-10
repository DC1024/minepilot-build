package dev.mcai.companion.perception;

import java.util.Objects;

/**
 * Conservative collision geometry for a fairly visible actor and one
 * proposed block-placement cell.
 *
 * <p>This utility consumes only ordinary first-person entity evidence. It
 * never resolves an entity UUID through the level, so callers can avoid
 * visibly occupied placement cells without gaining hidden-entity radar.</p>
 */
public final class VisibleEntityPlacementEnvelope {
    private VisibleEntityPlacementEnvelope() {
    }

    public static boolean intersectsBlock(
            final VisibleEntity entity,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        Objects.requireNonNull(entity, "entity");
        if (!couldBlockPlacement(
                entity.entityTypeId(),
                entity.projectile()
        )) {
            return false;
        }
        final EntityEnvelope envelope =
                entityEnvelope(entity.entityTypeId());
        return intersectsBlock(
                entity.position().x() - envelope.halfWidth(),
                entity.position().x() + envelope.halfWidth(),
                entity.position().y(),
                entity.position().y() + envelope.height(),
                entity.position().z() - envelope.halfWidth(),
                entity.position().z() + envelope.halfWidth(),
                blockX,
                blockY,
                blockZ
        );
    }

    private static boolean intersectsBlock(
            final double minX,
            final double maxX,
            final double minY,
            final double maxY,
            final double minZ,
            final double maxZ,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        return maxX > blockX
                && minX < blockX + 1.0
                && maxY > blockY
                && minY < blockY + 1.0
                && maxZ > blockZ
                && minZ < blockZ + 1.0;
    }

    private static boolean couldBlockPlacement(
            final String entityTypeId,
            final boolean projectile
    ) {
        if (projectile) {
            return false;
        }
        return switch (entityTypeId) {
            case "minecraft:item",
                    "minecraft:experience_orb",
                    "minecraft:area_effect_cloud",
                    "minecraft:interaction",
                    "minecraft:marker",
                    "minecraft:lightning_bolt" -> false;
            default -> true;
        };
    }

    private static EntityEnvelope entityEnvelope(
            final String entityTypeId
    ) {
        return switch (entityTypeId) {
            case "minecraft:cow", "minecraft:mooshroom" ->
                    new EntityEnvelope(0.45, 1.40);
            case "minecraft:pig", "minecraft:sheep",
                    "minecraft:goat" ->
                    new EntityEnvelope(0.45, 1.30);
            case "minecraft:horse", "minecraft:donkey",
                    "minecraft:mule", "minecraft:camel" ->
                    new EntityEnvelope(0.70, 2.40);
            case "minecraft:iron_golem" ->
                    new EntityEnvelope(0.70, 2.70);
            case "minecraft:ravager" ->
                    new EntityEnvelope(0.98, 2.20);
            case "minecraft:boat", "minecraft:chest_boat",
                    "minecraft:bamboo_raft",
                    "minecraft:bamboo_chest_raft" ->
                    new EntityEnvelope(0.75, 0.65);
            case "minecraft:minecart",
                    "minecraft:chest_minecart",
                    "minecraft:furnace_minecart",
                    "minecraft:hopper_minecart",
                    "minecraft:tnt_minecart",
                    "minecraft:command_block_minecart" ->
                    new EntityEnvelope(0.50, 0.75);
            default -> new EntityEnvelope(0.50, 2.00);
        };
    }

    private record EntityEnvelope(
            double halfWidth,
            double height
    ) {
    }
}
