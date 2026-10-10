package dev.mcai.companion.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Canonicalizes opaque, server-authored menu binding tokens.
 *
 * <p>A model still has to select the skill and all semantic action arguments
 * such as source slot, destination slot, count, or option. The three values
 * handled here merely bind that choice to the exact fair menu observation
 * included in the same request. Physical execution independently revalidates
 * the retained frame against the live vanilla menu.</p>
 */
final class ObservationBindingCanonicalizer {
    private static final Set<String> MENU_BOUND_SKILLS = Set.of(
            "transfer_menu_item",
            "quick_move_observed_slot",
            "take_menu_output",
            "select_menu_option",
            "wait_for_menu_change",
            "close_menu"
    );
    private static final String SMELT_MENU_BATCH = "smelt_menu_batch";

    private ObservationBindingCanonicalizer() {
    }

    static DecisionEnvelope canonicalize(
            final DecisionEnvelope decision,
            final String observationJson
    ) {
        if (decision.decision() != DecisionKind.START_SKILL
                || !MENU_BOUND_SKILLS.contains(decision.skillName())
                && !SMELT_MENU_BATCH.equals(decision.skillName())) {
            return decision;
        }
        final Map<String, String> bindings = observedBindings(
                observationJson
        );
        if (bindings.isEmpty()) {
            return decision;
        }
        if (SMELT_MENU_BATCH.equals(decision.skillName())) {
            bindings.keySet().retainAll(Set.of("sampleSequence"));
        }

        final var canonical = new ArrayList<SkillArgument>(
                decision.typedArguments().size() + bindings.size()
        );
        final var observedNames = new java.util.HashSet<String>();
        for (SkillArgument argument : decision.typedArguments()) {
            final String binding = bindings.get(argument.name());
            canonical.add(binding == null
                    ? argument
                    : new SkillArgument(argument.name(), binding));
            observedNames.add(argument.name());
        }
        for (Map.Entry<String, String> binding : bindings.entrySet()) {
            if (!observedNames.contains(binding.getKey())) {
                canonical.add(new SkillArgument(
                        binding.getKey(),
                        binding.getValue()
                ));
            }
        }
        return new DecisionEnvelope(
                decision.requestId(),
                decision.observedWorldRevision(),
                decision.goalRevision(),
                decision.decision(),
                decision.skillName(),
                canonical,
                decision.requestedObservation(),
                decision.optionalSpeech(),
                decision.confidence()
        );
    }

    private static Map<String, String> observedBindings(
            final String observationJson
    ) {
        try {
            final JsonObject root = JsonParser
                    .parseString(observationJson)
                    .getAsJsonObject();
            if (!root.has("sampleSequence")
                    || !root.has("openMenu")
                    || !root.get("openMenu").isJsonObject()) {
                return Map.of();
            }
            final JsonObject menu = root.getAsJsonObject("openMenu");
            final long sampleSequence = root
                    .get("sampleSequence")
                    .getAsLong();
            final int containerId = menu.get("containerId").getAsInt();
            final int stateId = menu.get("stateId").getAsInt();
            if (sampleSequence < 0 || containerId < 0 || stateId < 0) {
                return Map.of();
            }
            final Map<String, String> result = new LinkedHashMap<>();
            result.put(
                    "sampleSequence",
                    Long.toString(sampleSequence)
            );
            result.put("containerId", Integer.toString(containerId));
            result.put("stateId", Integer.toString(stateId));
            return result;
        } catch (RuntimeException malformedObservation) {
            return Map.of();
        }
    }
}
