package dev.mcai.companion.skills.loot;

import dev.mcai.companion.skin.AiProfileMarker;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

/**
 * Bounded causal audit for ordinary mob-drop pickups by companion bodies.
 *
 * <p>A pending entry starts at {@link LivingDropsEvent}, where Forge exposes
 * the victim, damage source, and exact item-entity UUIDs. A receipt is issued
 * only by the post-inventory {@link PlayerEvent.ItemPickupEvent} for the same
 * player and entity. The ledger never changes an event or the world.</p>
 */
public final class VanillaLootReceiptLedger
        implements LootPickupReceiptSource {
    private static final int MAXIMUM_TRACKED_DROPS = 512;
    private static final int MAXIMUM_RECEIPTS_PER_PLAYER = 64;
    private static final long MAXIMUM_DROP_AGE_TICKS = 1_200L;
    private static final AtomicBoolean REGISTERED =
            new AtomicBoolean();
    private static final VanillaLootReceiptLedger INSTANCE =
            new VanillaLootReceiptLedger();

    private final Object lock = new Object();
    private final Map<UUID, TrackedDrop> trackedDrops =
            new HashMap<>();
    private final Map<UUID, ArrayDeque<Receipt>> receipts =
            new HashMap<>();
    private long sequence;

    private VanillaLootReceiptLedger() {
    }

    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        LivingDropsEvent.BUS.addListener(INSTANCE::onDrops);
        PlayerEvent.ItemPickupEvent.BUS.addListener(
                INSTANCE::onPickup
        );
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(
                event -> INSTANCE.clearPlayer(
                        event.getEntity().getUUID()
                )
        );
        ServerStoppedEvent.BUS.addListener(
                event -> INSTANCE.clear()
        );
    }

    public static LootPickupReceiptSource source() {
        return INSTANCE;
    }

    @Override
    public long latestSequence() {
        synchronized (lock) {
            return sequence;
        }
    }

    @Override
    public List<Receipt> receiptsAfter(
            final UUID playerId,
            final long exclusiveSequence
    ) {
        Objects.requireNonNull(playerId, "playerId");
        synchronized (lock) {
            final ArrayDeque<Receipt> owned =
                    receipts.get(playerId);
            if (owned == null || owned.isEmpty()) {
                return List.of();
            }
            final List<Receipt> result = new ArrayList<>();
            for (Receipt receipt : owned) {
                if (receipt.sequence() > exclusiveSequence) {
                    result.add(receipt);
                }
            }
            return List.copyOf(result);
        }
    }

    private void onDrops(final LivingDropsEvent event) {
        if (!(event.getSource().getEntity()
                instanceof ServerPlayer killer)
                || !AiProfileMarker.isMarked(
                        killer.getGameProfile()
                )) {
            return;
        }
        final long gameTime =
                event.getEntity().level().getGameTime();
        final DimensionRef dimension = DimensionRef.parse(
                event.getEntity().level().dimension()
                        .identifier().toString()
        );
        synchronized (lock) {
            pruneTrackedDrops(gameTime);
            for (var drop : event.getDrops()) {
                if (drop.getItem().isEmpty()) {
                    continue;
                }
                trackedDrops.put(
                        drop.getUUID(),
                        new TrackedDrop(
                                killer.getUUID(),
                                event.getEntity().getUUID(),
                                BuiltInRegistries.ITEM.getKey(
                                        drop.getItem().getItem()
                                ).toString(),
                                dimension,
                                gameTime
                        )
                );
            }
            trimTrackedDrops();
        }
    }

    private void onPickup(final PlayerEvent.ItemPickupEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !AiProfileMarker.isMarked(
                        player.getGameProfile()
                )
                || event.getStack().isEmpty()) {
            return;
        }
        final UUID dropEntityId =
                event.getOriginalEntity().getUUID();
        final String itemId = BuiltInRegistries.ITEM.getKey(
                event.getStack().getItem()
        ).toString();
        final DimensionRef dimension = DimensionRef.parse(
                player.level().dimension().identifier().toString()
        );
        synchronized (lock) {
            final TrackedDrop tracked =
                    trackedDrops.get(dropEntityId);
            if (tracked == null
                    || !tracked.playerId()
                        .equals(player.getUUID())
                    || !tracked.itemId().equals(itemId)
                    || !tracked.dimension().equals(dimension)) {
                return;
            }
            final Receipt receipt = new Receipt(
                    ++sequence,
                    player.getUUID(),
                    tracked.victimId(),
                    dropEntityId,
                    itemId,
                    dimension,
                    event.getStack().getCount()
            );
            final ArrayDeque<Receipt> owned =
                    receipts.computeIfAbsent(
                            player.getUUID(),
                            ignored -> new ArrayDeque<>()
                    );
            owned.addLast(receipt);
            while (owned.size()
                    > MAXIMUM_RECEIPTS_PER_PLAYER) {
                owned.removeFirst();
            }
        }
    }

    private void clearPlayer(final UUID playerId) {
        synchronized (lock) {
            receipts.remove(playerId);
            trackedDrops.values().removeIf(drop ->
                    drop.playerId().equals(playerId)
            );
        }
    }

    private void clear() {
        synchronized (lock) {
            trackedDrops.clear();
            receipts.clear();
            sequence = 0L;
        }
    }

    private void pruneTrackedDrops(final long gameTime) {
        trackedDrops.values().removeIf(drop ->
                gameTime >= drop.createdAtGameTime()
                    && gameTime - drop.createdAtGameTime()
                        > MAXIMUM_DROP_AGE_TICKS
        );
    }

    private void trimTrackedDrops() {
        if (trackedDrops.size() <= MAXIMUM_TRACKED_DROPS) {
            return;
        }
        final Iterator<UUID> iterator =
                trackedDrops.keySet().iterator();
        while (trackedDrops.size() > MAXIMUM_TRACKED_DROPS
                && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private record TrackedDrop(
            UUID playerId,
            UUID victimId,
            String itemId,
            DimensionRef dimension,
            long createdAtGameTime
    ) {
    }
}
