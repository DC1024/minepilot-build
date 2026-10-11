package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockInteractionTarget;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Narrow legal-action boundary for local interaction skills.
 *
 * <p>Implementations must route every mutation through ordinary
 * {@code ServerPlayer} packet/game-mode handling. Observation validation in a
 * skill is only an allow-list; the implementation must independently
 * revalidate reach, line of sight, target identity, dimension, and world
 * permissions at dispatch time.</p>
 */
public interface InteractionSkillActuator {
    OptionalLong sessionGeneration();

    ActionOutcome beginMining(BlockInteractionTarget target);

    ActionOutcome continueMining();

    ActionOutcome abortMining();

    ActionOutcome useOnBlock(
            ActionHand hand,
            BlockInteractionTarget target
    );

    /**
     * Aims the bound body's crosshair at one world point taken from the
     * current observation.
     *
     * <p>Interactions are only legal against a target that is genuinely under
     * the crosshair, so a skill that never turns can reach only whatever the
     * body already happened to face. Implementations that cannot drive the
     * body's look return {@code INVALID_PLAYER_STATE}; a caller must then keep
     * its previous behaviour instead of assuming the aim was applied.</p>
     */
    default ActionOutcome lookAt(ActionVec3 point) {
        return ActionOutcome.INVALID_PLAYER_STATE;
    }

    ActionOutcome attack(UUID entityId);

    /**
     * Performs the ordinary non-attack interaction on the entity currently
     * under the companion's own crosshair.
     */
    default ActionOutcome interactEntity(
            UUID entityId,
            ActionHand hand
    ) {
        return ActionOutcome.ITEM_UNAVAILABLE;
    }

    /**
     * Returns only the bound player's own vanilla melee recharge fraction.
     *
     * <p>This self-state is safe to sample at 20 TPS and lets a local combat
     * controller respect attack cooldown without querying any target or
     * hidden world state. Implementations that cannot prove the active body
     * binding return empty rather than guessing.</p>
     */
    default OptionalDouble attackStrengthScale() {
        return OptionalDouble.empty();
    }

    ActionOutcome useItem(ActionHand hand);

    /**
     * Observes only the bound player's own active-use state.
     */
    ActionOutcome continueUsing(ActionHand hand);

    ActionOutcome releaseUse();

    /**
     * Selects an observed owned item into the main hand through the ordinary
     * inventory-menu swap path. Implementations must not create, clone, or
     * directly rewrite an item stack.
     */
    default ActionOutcome equipMainHand(String itemId) {
        return ActionOutcome.ITEM_UNAVAILABLE;
    }
}
