package dev.mcai.companion.model;

/**
 * The only high-level decisions a model may return.
 */
public enum DecisionKind {
    CONTINUE,
    START_SKILL,
    REPLAN,
    ASK_PLAYER,
    COMPLETE_GOAL,
    SAFE_IDLE
}
