package dev.mcai.companion.skills.core;

import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.EquipmentTarget;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Low-frequency, player-like equipment upkeep for items already owned by the
 * companion. Every change uses the normal inventory-menu actuator.
 */
public final class IdleEquipmentController {
    private static final int CHECK_INTERVAL_TICKS = 10;
    private static final Map<String, ArmorChoice> ARMOR = Map.ofEntries(
            armor("minecraft:leather_helmet", EquipmentTarget.HEAD, 1),
            armor("minecraft:golden_helmet", EquipmentTarget.HEAD, 2),
            armor("minecraft:chainmail_helmet", EquipmentTarget.HEAD, 3),
            armor("minecraft:copper_helmet", EquipmentTarget.HEAD, 3),
            armor("minecraft:turtle_helmet", EquipmentTarget.HEAD, 3),
            armor("minecraft:iron_helmet", EquipmentTarget.HEAD, 4),
            armor("minecraft:diamond_helmet", EquipmentTarget.HEAD, 5),
            armor("minecraft:netherite_helmet", EquipmentTarget.HEAD, 6),
            armor("minecraft:leather_chestplate", EquipmentTarget.CHEST, 1),
            armor("minecraft:golden_chestplate", EquipmentTarget.CHEST, 2),
            armor("minecraft:chainmail_chestplate", EquipmentTarget.CHEST, 3),
            armor("minecraft:copper_chestplate", EquipmentTarget.CHEST, 3),
            armor("minecraft:iron_chestplate", EquipmentTarget.CHEST, 4),
            armor("minecraft:diamond_chestplate", EquipmentTarget.CHEST, 5),
            armor("minecraft:netherite_chestplate", EquipmentTarget.CHEST, 6),
            armor("minecraft:leather_leggings", EquipmentTarget.LEGS, 1),
            armor("minecraft:golden_leggings", EquipmentTarget.LEGS, 2),
            armor("minecraft:chainmail_leggings", EquipmentTarget.LEGS, 3),
            armor("minecraft:copper_leggings", EquipmentTarget.LEGS, 3),
            armor("minecraft:iron_leggings", EquipmentTarget.LEGS, 4),
            armor("minecraft:diamond_leggings", EquipmentTarget.LEGS, 5),
            armor("minecraft:netherite_leggings", EquipmentTarget.LEGS, 6),
            armor("minecraft:leather_boots", EquipmentTarget.FEET, 1),
            armor("minecraft:golden_boots", EquipmentTarget.FEET, 2),
            armor("minecraft:chainmail_boots", EquipmentTarget.FEET, 3),
            armor("minecraft:copper_boots", EquipmentTarget.FEET, 3),
            armor("minecraft:iron_boots", EquipmentTarget.FEET, 4),
            armor("minecraft:diamond_boots", EquipmentTarget.FEET, 5),
            armor("minecraft:netherite_boots", EquipmentTarget.FEET, 6)
    );
    private static final List<String> WEAPONS = List.of(
            "minecraft:netherite_sword",
            "minecraft:diamond_sword",
            "minecraft:iron_sword",
            "minecraft:stone_sword",
            "minecraft:wooden_sword"
    );

    private final MinecraftServer server;
    private final UUID expectedPlayerId;
    private final InventorySkillActuator inventory;
    private long nextCheckTick;

    public IdleEquipmentController(
            final MinecraftServer server,
            final UUID expectedPlayerId,
            final InventorySkillActuator inventory
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public TickReport tick() {
        if (!server.isSameThread()) {
            return TickReport.none();
        }
        final Optional<ServerPlayer> current =
                AiPlayerManager.onlinePlayer(server);
        if (current.isEmpty()
                || !expectedPlayerId.equals(
                        current.orElseThrow().getUUID()
                )) {
            return TickReport.none();
        }
        final ServerPlayer player = current.orElseThrow();
        final long now = player.level().getGameTime();
        if (now < nextCheckTick
                || !player.isAlive()
                || player.isSpectator()
                || player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.getCarried().isEmpty()) {
            return TickReport.none();
        }
        nextCheckTick = now + CHECK_INTERVAL_TICKS;

        final Optional<OwnedArmor> upgrade =
                bestArmorUpgrade(player);
        if (upgrade.isPresent()) {
            final OwnedArmor selected = upgrade.orElseThrow();
            return apply(
                    selected.itemId(),
                    selected.choice().target(),
                    "armor"
            );
        }
        if (player.getOffhandItem().isEmpty()
                && owns(player, "minecraft:shield")) {
            return apply(
                    "minecraft:shield",
                    EquipmentTarget.OFFHAND,
                    "shield"
            );
        }
        final Optional<String> weapon = bestWeaponUpgrade(player);
        if (weapon.isPresent()) {
            return apply(
                    weapon.orElseThrow(),
                    EquipmentTarget.MAINHAND,
                    "weapon"
            );
        }
        return TickReport.none();
    }

    /**
     * Removes scheduling state that belongs to a replaced ServerPlayer body.
     * The inventory actuator resolves the new body on every operation, while
     * this controller must also allow an immediate first equipment pass.
     */
    public void resetForBodySession() {
        nextCheckTick = 0L;
    }

    private TickReport apply(
            final String itemId,
            final EquipmentTarget target,
            final String reason
    ) {
        final var outcome = inventory.equip(
                new EquipItemParameters(itemId, target)
        );
        return outcome.succeeded()
                ? new TickReport(true, "equipped_" + reason)
                : TickReport.none();
    }

    private static Optional<OwnedArmor> bestArmorUpgrade(
            final ServerPlayer player
    ) {
        return java.util.stream.IntStream.range(
                    0,
                    player.getInventory().getContainerSize()
                )
                .mapToObj(player.getInventory()::getItem)
                .filter(stack -> !stack.isEmpty())
                .map(stack -> {
                    final String itemId = itemId(stack);
                    return new OwnedArmor(itemId, ARMOR.get(itemId));
                })
                .filter(owned -> owned.choice() != null)
                .filter(owned -> owned.choice().rank()
                        > equippedRank(
                                player,
                                owned.choice().target()
                        ))
                .max(Comparator
                        .comparingInt(
                                (OwnedArmor owned) ->
                                        owned.choice().rank()
                        )
                        .thenComparing(OwnedArmor::itemId));
    }

    private static int equippedRank(
            final ServerPlayer player,
            final EquipmentTarget target
    ) {
        final EquipmentSlot slot = switch (target) {
            case HEAD -> EquipmentSlot.HEAD;
            case CHEST -> EquipmentSlot.CHEST;
            case LEGS -> EquipmentSlot.LEGS;
            case FEET -> EquipmentSlot.FEET;
            default -> throw new IllegalArgumentException(
                    "Armor target required"
            );
        };
        final ArmorChoice equipped = ARMOR.get(
                itemId(player.getItemBySlot(slot))
        );
        return equipped == null ? 0 : equipped.rank();
    }

    private static Optional<String> bestWeaponUpgrade(
            final ServerPlayer player
    ) {
        final int currentRank = weaponRank(
                itemId(player.getMainHandItem())
        );
        for (int rank = 0; rank < WEAPONS.size(); rank++) {
            final String itemId = WEAPONS.get(rank);
            if (owns(player, itemId)
                    && weaponRank(itemId) > currentRank) {
                return Optional.of(itemId);
            }
        }
        return Optional.empty();
    }

    private static int weaponRank(final String itemId) {
        final int index = WEAPONS.indexOf(itemId);
        return index < 0 ? 0 : WEAPONS.size() - index;
    }

    private static boolean owns(
            final ServerPlayer player,
            final String itemId
    ) {
        for (int slot = 0;
                slot < player.getInventory().getContainerSize();
                slot++) {
            if (itemId.equals(itemId(
                    player.getInventory().getItem(slot)
            ))) {
                return true;
            }
        }
        return false;
    }

    private static String itemId(final ItemStack stack) {
        return stack.isEmpty()
                ? ""
                : BuiltInRegistries.ITEM.getKey(
                        stack.getItem()
                ).toString();
    }

    private static Map.Entry<String, ArmorChoice> armor(
            final String itemId,
            final EquipmentTarget target,
            final int rank
    ) {
        return Map.entry(itemId, new ArmorChoice(target, rank));
    }

    private record ArmorChoice(
            EquipmentTarget target,
            int rank
    ) {
    }

    private record OwnedArmor(
            String itemId,
            ArmorChoice choice
    ) {
    }

    public record TickReport(
            boolean intervened,
            String reason
    ) {
        private static TickReport none() {
            return new TickReport(false, "");
        }
    }
}
