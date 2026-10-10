package dev.mcai.companion.skills.bridging;

import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.EquipmentTarget;
import dev.mcai.companion.skills.inventory.ServerInventorySkillActuator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/**
 * Selects only a small allow-list of ordinary expendable blocks, then equips
 * it through the same vanilla inventory transaction as equip_item.
 */
public final class ServerBridgeMaterialActuator
        implements BridgeMaterialActuator {
    private static final List<String> PREFERENCE = List.of(
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate",
            "minecraft:netherrack",
            "minecraft:dirt",
            "minecraft:stone",
            "minecraft:blackstone",
            "minecraft:oak_planks",
            "minecraft:spruce_planks",
            "minecraft:birch_planks",
            "minecraft:jungle_planks",
            "minecraft:acacia_planks",
            "minecraft:dark_oak_planks",
            "minecraft:mangrove_planks",
            "minecraft:cherry_planks",
            "minecraft:bamboo_planks",
            "minecraft:crimson_planks",
            "minecraft:warped_planks"
    );

    private final MinecraftServer server;
    private final UUID playerId;
    private final ServerInventorySkillActuator inventory;

    public ServerBridgeMaterialActuator(
            final MinecraftServer server,
            final UUID playerId,
            final ServerInventorySkillActuator inventory
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override
    public BridgeMaterialResult ensureEquipped() {
        if (!server.isSameThread()) {
            return BridgeMaterialResult.failed(
                    "bridge_to.wrong_thread"
            );
        }
        final var player = AiPlayerManager.onlinePlayer(server)
                .filter(candidate ->
                        playerId.equals(candidate.getUUID())
                );
        if (player.isEmpty()) {
            return BridgeMaterialResult.failed(
                    "bridge_to.player_unavailable"
            );
        }
        final var body = player.orElseThrow();
        final String current = body.getMainHandItem().isEmpty()
                ? ""
                : BuiltInRegistries.ITEM.getKey(
                        body.getMainHandItem().getItem()
                ).toString();
        if (PREFERENCE.contains(current)) {
            return BridgeMaterialResult.ready(
                    current,
                    count(body, current)
            );
        }
        for (String itemId : PREFERENCE) {
            final int available = count(body, itemId);
            if (available < 1) {
                continue;
            }
            final var equipped = inventory.equip(
                    new EquipItemParameters(
                            itemId,
                            EquipmentTarget.MAINHAND
                    )
            );
            return equipped.succeeded()
                    ? BridgeMaterialResult.ready(itemId, available)
                    : BridgeMaterialResult.failed(
                            equipped.failure()
                                    .orElseThrow()
                                    .code()
                    );
        }
        return BridgeMaterialResult.failed(
                "bridge_to.material_unavailable"
        );
    }

    private static int count(
            final net.minecraft.server.level.ServerPlayer player,
            final String itemId
    ) {
        int count = 0;
        for (int slot = 0;
                slot < player.getInventory().getContainerSize();
                slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()
                    && BuiltInRegistries.ITEM.getKey(stack.getItem())
                        .toString()
                        .equals(itemId)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
