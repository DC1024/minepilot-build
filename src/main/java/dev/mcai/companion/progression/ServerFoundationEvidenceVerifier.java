package dev.mcai.companion.progression;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;

/**
 * Revalidates only exact fixtures previously opened by the companion. It
 * never locates structures, scans surrounding chunks, or forces a chunk.
 */
public final class ServerFoundationEvidenceVerifier {
    private ServerFoundationEvidenceVerifier() {
    }

    public static Result verify(
            final MinecraftServer server,
            final VerifiedFoundationEvidence evidence
    ) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(evidence, "evidence");
        final boolean crafting = fixtureIs(
                server,
                evidence.craftingTable(),
                Blocks.CRAFTING_TABLE
        );
        final boolean furnace = fixtureIs(
                server,
                evidence.furnace(),
                Blocks.FURNACE
        );
        final boolean storage = evidence.storage()
                .filter(location -> storageBlockIsPresent(
                        server,
                        location
                ))
                .isPresent();
        final boolean workstationsEstablished =
                crafting && furnace && storage;
        final boolean suppliesStored = workstationsEstablished
                && evidence.suppliesDeposited()
                && evidence.storage()
                        .filter(location -> knownStorageHasItems(
                                server,
                                location,
                                evidence.depositedItemId(),
                                evidence.depositedItemCount()
                        ))
                        .isPresent();
        return new Result(
                workstationsEstablished,
                suppliesStored
        );
    }

    private static boolean fixtureIs(
            final MinecraftServer server,
            final Optional<VerifiedFixtureLocation> location,
            final Block expected
    ) {
        return location.filter(fixture ->
                loadedLevel(server, fixture)
                        .filter(level ->
                                level.getBlockState(pos(fixture))
                                        .is(expected)
                        )
                        .isPresent()
        ).isPresent();
    }

    private static boolean storageBlockIsPresent(
            final MinecraftServer server,
            final VerifiedFixtureLocation location
    ) {
        return loadedLevel(server, location)
                .filter(level -> {
                    final var state = level.getBlockState(pos(location));
                    return state.is(Blocks.CHEST)
                            || state.is(Blocks.TRAPPED_CHEST);
                })
                .isPresent();
    }

    private static boolean knownStorageHasItems(
            final MinecraftServer server,
            final VerifiedFixtureLocation location,
            final String expectedItemId,
            final int minimumCount
    ) {
        final Optional<ServerLevel> resolved =
                loadedLevel(server, location);
        if (resolved.isEmpty()) {
            return false;
        }
        final ServerLevel level = resolved.orElseThrow();
        final BlockPos origin = pos(location);
        final var originState = level.getBlockState(origin);
        if (!(originState.getBlock() instanceof ChestBlock chest)) {
            return false;
        }
        /*
         * Vanilla resolves only the actual paired half and refuses a blocked
         * chest. This does not scan beyond the exact container the companion
         * opened.
         */
        return containerItemCount(
                ChestBlock.getContainer(
                        chest,
                        originState,
                        level,
                        origin,
                        false
                ),
                expectedItemId
        ) >= minimumCount;
    }

    private static int containerItemCount(
            final Container container,
            final String expectedItemId
    ) {
        if (container == null) {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            final ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()
                    && stack.getCount() > 0
                    && net.minecraft.core.registries.BuiltInRegistries
                            .ITEM
                            .getKey(stack.getItem())
                            .toString()
                            .equals(expectedItemId)) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
    }

    private static Optional<ServerLevel> loadedLevel(
            final MinecraftServer server,
            final VerifiedFixtureLocation location
    ) {
        final Identifier id = Identifier.tryParse(location.dimension());
        if (id == null) {
            return Optional.empty();
        }
        final ServerLevel level = server.getLevel(ResourceKey.create(
                Registries.DIMENSION,
                id
        ));
        if (level == null || !level.hasChunkAt(pos(location))) {
            return Optional.empty();
        }
        return Optional.of(level);
    }

    private static BlockPos pos(
            final VerifiedFixtureLocation location
    ) {
        return new BlockPos(location.x(), location.y(), location.z());
    }

    public record Result(
            boolean workstationsEstablished,
            boolean suppliesStored
    ) {
        public Result {
            if (suppliesStored && !workstationsEstablished) {
                throw new IllegalArgumentException(
                        "Stored supplies require all foundation fixtures"
                );
            }
        }
    }
}
