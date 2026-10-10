package dev.mcai.companion.progression;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import java.util.Optional;

/**
 * Goal-scoped foundation evidence. Locations are learned only by the
 * companion opening the corresponding block. Deposited item identity and
 * count are set only after a successful vanilla menu transaction from its
 * own inventory.
 */
public record VerifiedFoundationEvidence(
        long goalRevision,
        Optional<VerifiedFixtureLocation> craftingTable,
        Optional<VerifiedFixtureLocation> furnace,
        Optional<VerifiedFixtureLocation> storage,
        String depositedItemId,
        int depositedItemCount
) {
    public static final Codec<VerifiedFoundationEvidence> CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.LONG.fieldOf("goal_revision")
                            .forGetter(
                                    VerifiedFoundationEvidence::goalRevision
                            ),
                    VerifiedFixtureLocation.CODEC.optionalFieldOf(
                            "crafting_table"
                    ).forGetter(
                            VerifiedFoundationEvidence::craftingTable
                    ),
                    VerifiedFixtureLocation.CODEC.optionalFieldOf(
                            "furnace"
                    ).forGetter(VerifiedFoundationEvidence::furnace),
                    VerifiedFixtureLocation.CODEC.optionalFieldOf(
                            "storage"
                    ).forGetter(VerifiedFoundationEvidence::storage),
                    Codec.STRING.optionalFieldOf(
                            "deposited_item",
                            ""
                    ).forGetter(
                            VerifiedFoundationEvidence::depositedItemId
                    ),
                    Codec.INT.optionalFieldOf(
                            "deposited_count",
                            0
                    ).forGetter(
                            VerifiedFoundationEvidence::depositedItemCount
                    )
            ).apply(instance, VerifiedFoundationEvidence::new));

    public VerifiedFoundationEvidence {
        if (goalRevision < 0) {
            throw new IllegalArgumentException(
                    "Foundation evidence revision is invalid"
            );
        }
        craftingTable = Objects.requireNonNull(
                craftingTable,
                "craftingTable"
        );
        furnace = Objects.requireNonNull(furnace, "furnace");
        storage = Objects.requireNonNull(storage, "storage");
        depositedItemId = Objects.requireNonNull(
                depositedItemId,
                "depositedItemId"
        );
        final boolean hasDeposit = !depositedItemId.isEmpty();
        if (hasDeposit != (depositedItemCount > 0)
                || depositedItemCount > 64
                || hasDeposit && !depositedItemId.matches(
                        "[a-z0-9_.-]+:[a-z0-9_./-]+"
                )
                || hasDeposit && storage.isEmpty()) {
            throw new IllegalArgumentException(
                    "Foundation storage deposit evidence is invalid"
            );
        }
    }

    public static VerifiedFoundationEvidence empty(
            final long goalRevision
    ) {
        return new VerifiedFoundationEvidence(
                goalRevision,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                "",
                0
        );
    }

    public VerifiedFoundationEvidence withFixture(
            final FoundationFixtureKind kind,
            final VerifiedFixtureLocation location
    ) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(location, "location");
        return switch (kind) {
            case CRAFTING_TABLE -> new VerifiedFoundationEvidence(
                    goalRevision,
                    Optional.of(location),
                    furnace,
                    storage,
                    depositedItemId,
                    depositedItemCount
            );
            case FURNACE -> new VerifiedFoundationEvidence(
                    goalRevision,
                    craftingTable,
                    Optional.of(location),
                    storage,
                    depositedItemId,
                    depositedItemCount
            );
            case STORAGE -> new VerifiedFoundationEvidence(
                    goalRevision,
                    craftingTable,
                    furnace,
                    Optional.of(location),
                    storage.filter(location::equals).isPresent()
                            ? depositedItemId
                            : "",
                    storage.filter(location::equals).isPresent()
                            ? depositedItemCount
                            : 0
            );
        };
    }

    public VerifiedFoundationEvidence withDepositedSupply(
            final String itemId,
            final int count
    ) {
        if (storage.isEmpty()) {
            return this;
        }
        final String normalized = Objects.requireNonNull(
                itemId,
                "itemId"
        );
        return new VerifiedFoundationEvidence(
                goalRevision,
                craftingTable,
                furnace,
                storage,
                normalized,
                count
        );
    }

    public boolean suppliesDeposited() {
        return depositedItemCount > 0;
    }
}
