package dev.mcai.companion.action;

import net.minecraft.world.InteractionHand;

public enum ActionHand {
    MAIN_HAND(InteractionHand.MAIN_HAND),
    OFF_HAND(InteractionHand.OFF_HAND);

    private final InteractionHand minecraftHand;

    ActionHand(InteractionHand minecraftHand) {
        this.minecraftHand = minecraftHand;
    }

    InteractionHand minecraftHand() {
        return minecraftHand;
    }
}
