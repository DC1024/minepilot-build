package dev.mcai.companion.control;

public enum GoalSource {
    PLAYER_CHAT,
    MCP,
    RECOVERY,
    HARDCORE_EVALUATION,
    /**
     * A self-proposed objective. Only ever set while no player/MCP goal is
     * active and external writes are unlocked; the text originates from the
     * model's spoken objective and is validated by the same
     * {@link GoalCoordinator} path as any other request.
     */
    AUTONOMY
}
