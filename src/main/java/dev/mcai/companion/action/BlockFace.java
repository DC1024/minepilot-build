package dev.mcai.companion.action;

import net.minecraft.core.Direction;

public enum BlockFace {
    DOWN(Direction.DOWN),
    UP(Direction.UP),
    NORTH(Direction.NORTH),
    SOUTH(Direction.SOUTH),
    WEST(Direction.WEST),
    EAST(Direction.EAST);

    private final Direction direction;

    BlockFace(Direction direction) {
        this.direction = direction;
    }

    Direction direction() {
        return direction;
    }
}
