package dev.mcai.companion.model;

import dev.mcai.companion.control.GoalExecutionPlan;
import java.util.Objects;

/**
 * Removes narrowly identified provider-added arguments that carry no action
 * authority for a particular skill.
 *
 * <p>This is not a permissive unknown-field filter. Every field not listed
 * here continues through exact per-skill validation and fails closed.</p>
 */
final class KnownSkillArgumentCanonicalizer {
    private static final String SURVEY = "survey_surroundings";
    private static final java.util.Set<String>
            EXACT_PARAMETERLESS_COMPOUNDS = java.util.Set.of(
                    "prepare_basic_crafting",
                    "prepare_stone_tools",
                    "prepare_iron_toolkit",
                    "prepare_container_wood_door",
                    "establish_foundation_workstations",
                    "prepare_foundation_shelter_materials",
                    "secure_visible_food_reserve",
                    "secure_nether_blaze_material",
                    "secure_ender_pearl_reserve",
                    "triangulate_stronghold_search_area",
                    "reach_observed_stronghold",
                    "search_stronghold_portal_room",
                    "activate_observed_end_portal",
                    "fight_ender_dragon",
                    "find_and_enter_observed_portal",
                    "return_via_verified_portal"
            );
    private static final java.util.Set<String>
            OBSERVATION_BOUND_WITHOUT_DIMENSION = java.util.Set.of(
                    "collect_observed_item",
                    "hunt_observed_food_animal"
            );

    private KnownSkillArgumentCanonicalizer() {
    }

    static DecisionEnvelope canonicalize(
            final DecisionEnvelope decision
    ) {
        Objects.requireNonNull(decision, "decision");
        if (decision.decision() == DecisionKind.ASK_PLAYER) {
            return canonicalizeConversationPlan(decision);
        }
        if (decision.decision() != DecisionKind.START_SKILL) {
            return decision;
        }
        if (EXACT_PARAMETERLESS_COMPOUNDS.contains(
                decision.skillName()
        )) {
            return decision.typedArguments().isEmpty()
                    ? decision
                    : withArguments(decision, java.util.List.of());
        }
        final boolean removeSurveySample =
                SURVEY.equals(decision.skillName());
        final boolean removeObservationDimension =
                OBSERVATION_BOUND_WITHOUT_DIMENSION.contains(
                        decision.skillName()
                );
        if (!removeSurveySample && !removeObservationDimension) {
            return decision;
        }
        final java.util.List<SkillArgument> canonicalArguments =
                decision.typedArguments().stream()
                        .filter(argument ->
                                !(removeSurveySample
                                        && "sampleSequence".equals(
                                            argument.name()
                                        )))
                        .filter(argument ->
                                !(removeObservationDimension
                                        && "dimension".equals(
                                            argument.name()
                                        )))
                        .toList();
        if (canonicalArguments.equals(decision.typedArguments())) {
            return decision;
        }
        return withArguments(decision, canonicalArguments);
    }

    private static DecisionEnvelope canonicalizeConversationPlan(
            final DecisionEnvelope decision
    ) {
        if (decision.typedArguments().size() != 2) {
            return decision;
        }
        final SkillArgument route = decision.typedArguments().stream()
                .filter(argument -> argument.name().equals(
                        "goalRouteProfile"
                ))
                .findFirst()
                .orElse(null);
        final SkillArgument target = decision.typedArguments().stream()
                .filter(argument -> argument.name().equals(
                        "goalTerminalMilestone"
                ))
                .findFirst()
                .orElse(null);
        if (route == null || target == null
                || !target.value().strip().equalsIgnoreCase("NONE")) {
            return decision;
        }
        final String[] combined = route.value().split("/", -1);
        if (combined.length != 2) {
            return decision;
        }
        final GoalExecutionPlan plan;
        try {
            plan = GoalExecutionPlan.fromModelValues(
                    combined[0],
                    combined[1]
            );
        } catch (IllegalArgumentException malformed) {
            return decision;
        }
        return withArguments(
                decision,
                java.util.List.of(
                        new SkillArgument(
                                "goalRouteProfile",
                                plan.route().name()
                        ),
                        new SkillArgument(
                                "goalTerminalMilestone",
                                plan.terminalTarget().name()
                        )
                )
        );
    }

    private static DecisionEnvelope withArguments(
            final DecisionEnvelope decision,
            final java.util.List<SkillArgument> arguments
    ) {
        return new DecisionEnvelope(
                decision.requestId(),
                decision.observedWorldRevision(),
                decision.goalRevision(),
                decision.decision(),
                decision.skillName(),
                arguments,
                decision.requestedObservation(),
                decision.optionalSpeech(),
                decision.confidence()
        );
    }
}
