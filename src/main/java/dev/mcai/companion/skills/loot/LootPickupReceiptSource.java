package dev.mcai.companion.skills.loot;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only evidence that a vanilla mob drop entered an owned inventory.
 *
 * <p>The source does not expose nearby entities or hidden container state.
 * Each receipt is created only after Forge reports both the victim's exact
 * drop entity and the later successful player pickup transaction.</p>
 */
public interface LootPickupReceiptSource {
    long latestSequence();

    List<Receipt> receiptsAfter(
            UUID playerId,
            long exclusiveSequence
    );

    static LootPickupReceiptSource none() {
        return EmptySource.INSTANCE;
    }

    record Receipt(
            long sequence,
            UUID playerId,
            UUID victimId,
            UUID dropEntityId,
            String itemId,
            DimensionRef dimension,
            int count
    ) {
        public Receipt {
            if (sequence < 1L || count < 1) {
                throw new IllegalArgumentException(
                        "Loot receipt values are outside bounds"
                );
            }
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(victimId, "victimId");
            Objects.requireNonNull(
                    dropEntityId,
                    "dropEntityId"
            );
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    enum EmptySource implements LootPickupReceiptSource {
        INSTANCE;

        @Override
        public long latestSequence() {
            return 0L;
        }

        @Override
        public List<Receipt> receiptsAfter(
                final UUID playerId,
                final long exclusiveSequence
        ) {
            Objects.requireNonNull(playerId, "playerId");
            return List.of();
        }
    }
}
