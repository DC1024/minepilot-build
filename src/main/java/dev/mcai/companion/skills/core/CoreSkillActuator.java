package dev.mcai.companion.skills.core;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;

/**
 * Narrow legal-action port used by core skills and faked by pure tests.
 */
public interface CoreSkillActuator {
    ActionOutcome move(MovementIntent intent);

    ActionOutcome look(LookIntent intent);

    ActionOutcome jump();

    ActionOutcome stop();

    ActionOutcome useMainHandOn(BlockInteractionTarget target);

    /**
     * Starts normal vanilla item use in the selected hand. Skills cannot
     * complete consumption directly; the player tick and item component own
     * that lifecycle.
     */
    default ActionOutcome useItem(ActionHand hand) {
        return ActionOutcome.ITEM_UNAVAILABLE;
    }

    /**
     * Releases a held use action such as eating, drawing a bow, or guarding.
     */
    default ActionOutcome releaseUse() {
        return ActionOutcome.NO_ACTIVE_ACTION;
    }
}
