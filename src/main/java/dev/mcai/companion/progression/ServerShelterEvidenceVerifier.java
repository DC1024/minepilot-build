package dev.mcai.companion.progression;

import dev.mcai.companion.navigation.GridPos;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Revalidates only the recorded, already-known shelter footprint. It never
 * locates structures, scans surrounding chunks, or forces an unloaded chunk.
 */
public final class ServerShelterEvidenceVerifier {
    private ServerShelterEvidenceVerifier() {
    }

    public static boolean verify(
            final MinecraftServer server,
            final VerifiedShelterEvidence evidence
    ) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(evidence, "evidence");
        final Identifier dimensionId =
                Identifier.tryParse(evidence.dimension());
        final Identifier structuralId =
                Identifier.tryParse(evidence.structuralItemId());
        final Identifier lightId =
                Identifier.tryParse(evidence.lightItemId());
        if (dimensionId == null
                || structuralId == null
                || lightId == null) {
            return false;
        }
        final ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION,
                dimensionId
        );
        final ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return false;
        }
        final GridPos origin = evidence.origin();
        final BlockPos minimum = blockPos(
                origin.offset(0, -1, 0)
        );
        final BlockPos maximum = blockPos(origin.offset(
                evidence.exteriorWidth() - 1,
                evidence.interiorHeight(),
                evidence.exteriorDepth() - 1
        ));
        if (!level.hasChunksAt(minimum, maximum)) {
            return false;
        }
        final Item structural =
                BuiltInRegistries.ITEM.getValue(structuralId);
        final Item light = BuiltInRegistries.ITEM.getValue(lightId);
        if (structural == null || light == null) {
            return false;
        }
        if (!verifyWallsAndRoof(level, evidence, structural)
                || !verifyDoor(level, evidence)
                || !verifyInterior(level, evidence, light)) {
            return false;
        }
        return true;
    }

    private static boolean verifyWallsAndRoof(
            final ServerLevel level,
            final VerifiedShelterEvidence evidence,
            final Item structural
    ) {
        final GridPos origin = evidence.origin();
        final GridPos doorLower = evidence.doorLower();
        final GridPos doorUpper = doorLower.above();
        for (int y = 0; y < evidence.interiorHeight(); y++) {
            for (int x = 0; x < evidence.exteriorWidth(); x++) {
                for (int z = 0;
                        z < evidence.exteriorDepth();
                        z++) {
                    if (x != 0
                            && x != evidence.exteriorWidth() - 1
                            && z != 0
                            && z != evidence.exteriorDepth() - 1) {
                        continue;
                    }
                    final GridPos target = origin.offset(x, y, z);
                    if (target.equals(doorLower)
                            || target.equals(doorUpper)) {
                        continue;
                    }
                    if (level.getBlockState(blockPos(target))
                            .getBlock()
                            .asItem() != structural) {
                        return false;
                    }
                }
            }
        }
        for (int x = 0; x < evidence.exteriorWidth(); x++) {
            for (int z = 0;
                    z < evidence.exteriorDepth();
                    z++) {
                final GridPos target = origin.offset(
                        x,
                        evidence.interiorHeight(),
                        z
                );
                if (level.getBlockState(blockPos(target))
                        .getBlock()
                        .asItem() != structural) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean verifyDoor(
            final ServerLevel level,
            final VerifiedShelterEvidence evidence
    ) {
        final BlockState lower = level.getBlockState(
                blockPos(evidence.doorLower())
        );
        final BlockState upper = level.getBlockState(
                blockPos(evidence.doorLower().above())
        );
        return lower.getBlock() instanceof DoorBlock lowerDoor
                && upper.getBlock() instanceof DoorBlock upperDoor
                && lower.getBlock() == upper.getBlock()
                && lower.getValue(DoorBlock.HALF)
                        == DoubleBlockHalf.LOWER
                && upper.getValue(DoorBlock.HALF)
                        == DoubleBlockHalf.UPPER
                && !lowerDoor.isOpen(lower)
                && !upperDoor.isOpen(upper);
    }

    private static boolean verifyInterior(
            final ServerLevel level,
            final VerifiedShelterEvidence evidence,
            final Item expectedLight
    ) {
        final GridPos origin = evidence.origin();
        final GridPos light = evidence.lightPosition();
        final BlockState lightState = level.getBlockState(
                blockPos(light)
        );
        if (lightState.getLightEmission() <= 0
                || lightState.getBlock().asItem() != expectedLight
                || !lightState.getCollisionShape(
                        level,
                        blockPos(light)
                ).isEmpty()) {
            return false;
        }
        for (int x = 1;
                x <= evidence.interiorWidth();
                x++) {
            for (int z = 1;
                    z <= evidence.interiorDepth();
                    z++) {
                final GridPos floor = origin.offset(x, -1, z);
                final BlockPos floorPos = blockPos(floor);
                if (!level.getBlockState(floorPos).isFaceSturdy(
                        level,
                        floorPos,
                        Direction.UP
                )) {
                    return false;
                }
                for (int y = 0;
                        y < evidence.interiorHeight();
                        y++) {
                    final GridPos interior = origin.offset(x, y, z);
                    final BlockPos interiorPos = blockPos(interior);
                    final BlockState state =
                            level.getBlockState(interiorPos);
                    if (!state.getCollisionShape(
                            level,
                            interiorPos
                    ).isEmpty()) {
                        return false;
                    }
                    if (y == 0
                            && level.getBrightness(
                                    LightLayer.BLOCK,
                                    interiorPos
                            ) <= 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static BlockPos blockPos(final GridPos position) {
        return new BlockPos(
                position.x(),
                position.y(),
                position.z()
        );
    }
}
