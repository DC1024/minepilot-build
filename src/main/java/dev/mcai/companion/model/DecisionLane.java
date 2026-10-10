package dev.mcai.companion.model;

/**
 * Declares which local authority boundary a model request belongs to.
 *
 * <p>The conversation lane may classify a player's message and speak, but it
 * cannot invoke a gameplay skill. Some otherwise usable providers still fill
 * the globally-required skill fields while returning a conversational
 * decision. Those fields are harmless only in this explicitly marked lane and
 * are discarded before the decision reaches game code.</p>
 */
public enum DecisionLane {
    GAMEPLAY,
    CONVERSATION
}
