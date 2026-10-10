package dev.mcai.companion.control;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A bounded, model-classified execution route attached to one running goal.
 *
 * <p>The player's original words remain the goal text. This plan only selects
 * an existing server-owned route and its last required milestone; it cannot
 * add a skill, target a block, or certify progress.</p>
 */
public record GoalExecutionPlan(Route route, Target terminalTarget) {
    private static final String DETAIL_PREFIX = "plan:";

    private static final EnumSet<Target> FOUNDATION_TARGETS = EnumSet.of(
            Target.BODY_ACTIVE,
            Target.WOOD_OBTAINED,
            Target.BASIC_CRAFTING_READY,
            Target.STONE_TOOL_OBTAINED,
            Target.LOG_STORAGE_DISTRIBUTED,
            Target.CONTAINER_WOOD_DOOR_PLACED,
            Target.FOOD_SECURED,
            Target.IRON_OBTAINED,
            Target.IRON_TOOLKIT_OBTAINED,
            Target.WORKSTATIONS_ESTABLISHED,
            Target.SUPPLIES_STORED,
            Target.SHELTER_MATERIALS_PREPARED,
            Target.SHELTER_COMPLETED,
            Target.FIRST_NIGHT_SURVIVED
    );

    private static final EnumSet<Target> COMPLETION_TARGETS = EnumSet.of(
            Target.BODY_ACTIVE,
            Target.WOOD_OBTAINED,
            Target.BASIC_CRAFTING_READY,
            Target.STONE_TOOL_OBTAINED,
            Target.FOOD_SECURED,
            Target.IRON_OBTAINED,
            Target.IRON_TOOLKIT_OBTAINED,
            Target.NETHER_ENTERED,
            Target.BLAZE_MATERIAL_OBTAINED,
            Target.ENDER_PEARL_OBTAINED,
            Target.EYE_OF_ENDER_CRAFTED,
            Target.STRONGHOLD_BEARING_MEASURED,
            Target.STRONGHOLD_SEARCH_AREA_TRIANGULATED,
            Target.END_LOADOUT_PREPARED,
            Target.END_ENTERED,
            Target.END_ISLAND_REACHED,
            Target.DRAGON_KILLED,
            Target.RETURNED_FROM_END
    );

    public GoalExecutionPlan {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(terminalTarget, "terminalTarget");
        if (route == Route.NONE && terminalTarget != Target.NONE) {
            throw new IllegalArgumentException(
                    "An unplanned goal cannot have a route target"
            );
        }
        if (route == Route.FOUNDATION
                && !FOUNDATION_TARGETS.contains(terminalTarget)) {
            throw new IllegalArgumentException(
                    "Target is not part of the foundation route"
            );
        }
        if (route == Route.COMPLETION
                && !COMPLETION_TARGETS.contains(terminalTarget)) {
            throw new IllegalArgumentException(
                    "Target is not part of the completion route"
            );
        }
    }

    public static GoalExecutionPlan none() {
        return new GoalExecutionPlan(Route.NONE, Target.NONE);
    }

    public static GoalExecutionPlan foundation(final Target target) {
        return new GoalExecutionPlan(Route.FOUNDATION, target);
    }

    public static GoalExecutionPlan completion(final Target target) {
        return new GoalExecutionPlan(Route.COMPLETION, target);
    }

    public static GoalExecutionPlan fromModelValues(
            final String routeValue,
            final String targetValue
    ) {
        Route route = parseEnum(
                Route.class,
                routeValue,
                "goalRouteProfile"
        );
        final Target target = parseEnum(
                Target.class,
                targetValue,
                "goalTerminalMilestone"
        );
        /*
         * Some compatible providers correctly select the typed terminal but
         * leave the route at NONE. The terminal remains the model-authored
         * semantic decision; this normalization only repairs the impossible
         * type pair. Foundation-exclusive and shared early-survival targets
         * use FOUNDATION. Completion-only targets use COMPLETION. NONE/NONE
         * remains the only standalone-action encoding.
         */
        if (route == Route.NONE && target != Target.NONE) {
            route = FOUNDATION_TARGETS.contains(target)
                    ? Route.FOUNDATION
                    : COMPLETION_TARGETS.contains(target)
                            ? Route.COMPLETION
                            : route;
        }
        return new GoalExecutionPlan(route, target);
    }

    public static Optional<GoalExecutionPlan> fromDetailCode(
            final String detailCode
    ) {
        final String value = Objects.requireNonNullElse(detailCode, "");
        if (!value.startsWith(DETAIL_PREFIX)) {
            return Optional.empty();
        }
        final String[] fields = value.substring(DETAIL_PREFIX.length())
                .split(":", -1);
        if (fields.length != 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(fromModelValues(fields[0], fields[1]));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    public String detailCode() {
        if (route == Route.NONE) {
            return "";
        }
        return DETAIL_PREFIX + route.name() + ":" + terminalTarget.name();
    }

    private static <E extends Enum<E>> E parseEnum(
            final Class<E> type,
            final String value,
            final String field
    ) {
        try {
            return Enum.valueOf(
                    type,
                    Objects.requireNonNullElse(value, "")
                            .strip()
                            .toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    field + " is not a supported value",
                    exception
            );
        }
    }

    public enum Route {
        NONE,
        FOUNDATION,
        COMPLETION
    }

    public enum Target {
        NONE,
        BODY_ACTIVE,
        WOOD_OBTAINED,
        BASIC_CRAFTING_READY,
        STONE_TOOL_OBTAINED,
        LOG_STORAGE_DISTRIBUTED,
        CONTAINER_WOOD_DOOR_PLACED,
        FOOD_SECURED,
        IRON_OBTAINED,
        IRON_TOOLKIT_OBTAINED,
        WORKSTATIONS_ESTABLISHED,
        SUPPLIES_STORED,
        SHELTER_MATERIALS_PREPARED,
        SHELTER_COMPLETED,
        FIRST_NIGHT_SURVIVED,
        NETHER_ENTERED,
        BLAZE_MATERIAL_OBTAINED,
        ENDER_PEARL_OBTAINED,
        EYE_OF_ENDER_CRAFTED,
        STRONGHOLD_BEARING_MEASURED,
        STRONGHOLD_SEARCH_AREA_TRIANGULATED,
        END_LOADOUT_PREPARED,
        END_ENTERED,
        END_ISLAND_REACHED,
        DRAGON_KILLED,
        RETURNED_FROM_END
    }
}
