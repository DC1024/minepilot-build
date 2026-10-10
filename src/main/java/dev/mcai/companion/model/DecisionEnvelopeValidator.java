package dev.mcai.companion.model;

import dev.mcai.companion.control.GoalExecutionPlan;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Contextual validation is intentionally independent from provider-side
 * structured output. Provider guarantees are never treated as authorization.
 */
public final class DecisionEnvelopeValidator {
    public static final int MAX_ARGUMENTS = 32;
    public static final int MAX_ARGUMENT_NAME_CHARS = 64;
    public static final int MAX_ARGUMENT_VALUE_CHARS = 512;
    public static final int MAX_SPEECH_CODE_POINTS = 512;
    public static final int MAX_OBSERVATION_REASON_CODE_POINTS = 256;

    /**
     * Gameplay speech is advisory and has no authority over the body.  Keep a
     * bounded, code-point-safe prefix available to the transport layer when a
     * provider puts an overlong narration beside an otherwise valid action.
     * Argument and observation fields are never repaired this way.
     */
    static String boundedSpeech(final String value) {
        if (value.codePointCount(0, value.length()) <= MAX_SPEECH_CODE_POINTS) {
            return value;
        }
        final int end = value.offsetByCodePoints(
                0,
                MAX_SPEECH_CODE_POINTS
        );
        return value.substring(0, end);
    }

    private static final Pattern SKILL_NAME = Pattern.compile("[a-z0-9_.:-]{1,64}");
    private static final Pattern ARGUMENT_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    /**
     * A few providers use a private tool vocabulary even when the server
     * exposes one phase-owned compound.  These aliases are deliberately
     * phase-specific: an alias is accepted only when its mapped, server
     * authored skill is currently admitted below.  The food compound remains
     * sole-capability only because it is the most dangerous place for a
     * provider to confuse a stale hunting verb with ordinary movement.
     */
    private static final Map<String, String> FOUNDATION_COMPOUND_ALIASES =
            Map.ofEntries(
                    Map.entry("craft_basic_tools", "prepare_basic_crafting"),
                    Map.entry("craft", "prepare_basic_crafting"),
                    Map.entry("craft_basic", "prepare_basic_crafting"),
                    Map.entry("make_crafting_table", "prepare_basic_crafting"),
                    Map.entry("gather_visible_block_cluster", "prepare_basic_crafting"),
                    Map.entry("collect_visible_item", "prepare_basic_crafting"),
                    Map.entry("gather_cluster", "prepare_basic_crafting"),
                    Map.entry("basic_crafting_setup", "prepare_basic_crafting"),
                    Map.entry("prepare_crafting", "prepare_basic_crafting"),
                    Map.entry("gather_connected_cluster", "prepare_stone_tools"),
                    Map.entry("gather_stone_cluster", "prepare_stone_tools"),
                    Map.entry("mine_stone", "prepare_stone_tools"),
                    Map.entry("craft_stone_tools", "prepare_stone_tools"),
                    Map.entry("make_stone_tools", "prepare_stone_tools"),
                    Map.entry("prepare_stone_tool", "prepare_stone_tools"),
                    Map.entry("mine_iron", "prepare_iron_toolkit"),
                    Map.entry("mine_tunnel", "prepare_iron_toolkit"),
                    Map.entry("mine_iron_ore", "prepare_iron_toolkit"),
                    Map.entry("gather_iron", "prepare_iron_toolkit"),
                    Map.entry("get_iron", "prepare_iron_toolkit"),
                    Map.entry("craft_iron_tools", "prepare_iron_toolkit"),
                    Map.entry("prepare_iron_tools", "prepare_iron_toolkit"),
                    Map.entry("iron_toolkit", "prepare_iron_toolkit"),
                    Map.entry("build_workstations", "establish_foundation_workstations"),
                    Map.entry("setup_workstations", "establish_foundation_workstations"),
                    Map.entry("place_workstations", "establish_foundation_workstations"),
                    Map.entry("gather_shelter_materials", "prepare_foundation_shelter_materials"),
                    Map.entry("collect_building_materials", "prepare_foundation_shelter_materials"),
                    Map.entry("hunt_animal", "secure_visible_food_reserve"),
                    Map.entry("hunt_melee", "secure_visible_food_reserve"),
                    Map.entry("hunt_melee_animal", "secure_visible_food_reserve"),
                    Map.entry("hunt_meat", "secure_visible_food_reserve"),
                    Map.entry("hunting_kill", "secure_visible_food_reserve"),
                    Map.entry("shoot_ranged", "secure_visible_food_reserve"),
                    Map.entry("shoot_ranged_animal", "secure_visible_food_reserve")
            );

    public DecisionEnvelope validate(DecisionEnvelope envelope, DecisionContext context)
            throws DecisionValidationException {
        if (!envelope.requestId().equals(context.requestId())) {
            fail("stale_request", "The response belongs to a different request");
        }
        if (envelope.observedWorldRevision() != context.observedWorldRevision()) {
            fail("stale_world", "The observed world revision changed");
        }
        if (envelope.goalRevision() != context.goalRevision()) {
            fail("stale_goal", "The goal revision changed");
        }
        if (envelope.observedWorldRevision() < 0 || envelope.goalRevision() < 0) {
            fail("negative_revision", "Revisions must be non-negative");
        }
        if (!Double.isFinite(envelope.confidence())
                || envelope.confidence() < 0.0
                || envelope.confidence() > 1.0) {
            fail("invalid_confidence", "Confidence must be a finite number from zero to one");
        }
        validateText("optionalSpeech", envelope.optionalSpeech(), MAX_SPEECH_CODE_POINTS);
        envelope = canonicalizeConversationPayload(envelope, context);
        envelope = preferRequestedObservationOverConflictingAction(
                envelope,
                context
        );
        envelope = discardInactiveSkillInvocation(envelope, context);
        envelope = canonicalizeFoundationCompoundAlias(envelope, context);
        envelope = replanContinueWithoutActiveSkill(envelope, context);
        validateText(
                "requestedObservation.reason",
                envelope.requestedObservation().reason(),
                MAX_OBSERVATION_REASON_CODE_POINTS
        );
        validateObservation(envelope.requestedObservation());
        validateSkillInvocation(envelope, context);

        if (envelope.requestedObservation().kind() != ObservationKind.NONE
                && envelope.decision() != DecisionKind.REPLAN) {
            fail(
                    "observation_requires_replan",
                    "An extra observation may only accompany REPLAN"
            );
        }

        if (envelope.decision() == DecisionKind.ASK_PLAYER && envelope.optionalSpeech().isBlank()) {
            fail("missing_question", "ASK_PLAYER requires non-empty speech");
        }
        return envelope;
    }

    /**
     * Some providers reuse a natural-language action name from their own
     * tool vocabulary even when the server-authored foundation phase exposes
     * only the durable, parameterless foundation compounds. Accept only the
     * small observed alias set when its mapped compound is admitted by the
     * current gameplay capability set (the food compound still requires sole
     * admission), and discard the alias arguments. This remains a
     * server-selected fair skill; it never turns an arbitrary name or target
     * into a world action.
     */
    private static DecisionEnvelope canonicalizeFoundationCompoundAlias(
            final DecisionEnvelope envelope,
            final DecisionContext context
    ) {
        final String expectedSkill = foundationAliasTarget(
                envelope.skillName(),
                context.availableSkills()
        );
        if (context.lane() != DecisionLane.GAMEPLAY
                || envelope.decision() != DecisionKind.START_SKILL
                || expectedSkill == null
                || !context.availableSkills().containsKey(expectedSkill)
                || ("secure_visible_food_reserve".equals(expectedSkill)
                    && context.availableSkills().size() != 1)) {
            return envelope;
        }
        return new DecisionEnvelope(
                envelope.requestId(),
                envelope.observedWorldRevision(),
                envelope.goalRevision(),
                envelope.decision(),
                expectedSkill,
                java.util.List.of(),
                envelope.requestedObservation(),
                envelope.optionalSpeech(),
                envelope.confidence()
        );
    }

    private static String foundationAliasTarget(
            final String skillName,
            final Map<String, SkillArgumentValidator> availableSkills
    ) {
        if (availableSkills.containsKey("gather_nearby_wood")
                && Set.of(
                        "gather_visible_block_cluster",
                        "gather_visible_cluster",
                        "gather_connected_cluster",
                        "gather_cluster",
                        "gather_tree",
                        "chop_tree",
                        "collect_wood"
                ).contains(skillName)) {
            return "gather_nearby_wood";
        }
        if ("gather_visible_cluster".equals(skillName)
                || "collect_visible_item".equals(skillName)
                || "gather_cluster".equals(skillName)) {
            /*
             * MiMo uses compact gathering verbs for multiple foundation
             * substeps. Resolve only to a server-admitted compound; never
             * manufacture a capability merely because the alias was
             * recognized. The phase order is intentionally narrow so an
             * iron-phase collection request cannot fall back to the earlier
             * basic-crafting compound and stall the route.
             */
            if (availableSkills.containsKey("prepare_iron_toolkit")) {
                return "prepare_iron_toolkit";
            }
            if (availableSkills.containsKey("prepare_stone_tools")) {
                return "prepare_stone_tools";
            }
            if (availableSkills.containsKey("prepare_basic_crafting")) {
                return "prepare_basic_crafting";
            }
        }
        return FOUNDATION_COMPOUND_ALIASES.get(skillName);
    }

    /**
     * A model response can race the final tick of a local skill. Continuing a
     * skill that has already completed has no possible action, so safely
     * reinterpret it as a replan against the fresh authoritative state.
     */
    private static DecisionEnvelope replanContinueWithoutActiveSkill(
            final DecisionEnvelope envelope,
            final DecisionContext context
    ) {
        if (envelope.decision() != DecisionKind.CONTINUE
                || context.activeSkill()) {
            return envelope;
        }
        return new DecisionEnvelope(
                envelope.requestId(),
                envelope.observedWorldRevision(),
                envelope.goalRevision(),
                DecisionKind.REPLAN,
                "",
                java.util.List.of(),
                envelope.requestedObservation(),
                envelope.optionalSpeech(),
                envelope.confidence()
        );
    }

    /**
     * Some providers repeat the previously considered skill payload on a
     * REPLAN, CONTINUE, or terminal response despite the schema. Those fields
     * have no authority unless the decision is START_SKILL, so discard them
     * instead of turning a safe replan into a task-ending protocol failure.
     */
    private static DecisionEnvelope discardInactiveSkillInvocation(
            final DecisionEnvelope envelope,
            final DecisionContext context
    ) {
        if (context.lane() == DecisionLane.CONVERSATION
                && envelope.decision() == DecisionKind.ASK_PLAYER) {
            return envelope;
        }
        if (envelope.decision() == DecisionKind.START_SKILL
                || envelope.skillName().isEmpty()
                && envelope.typedArguments().isEmpty()) {
            return envelope;
        }
        return new DecisionEnvelope(
                envelope.requestId(),
                envelope.observedWorldRevision(),
                envelope.goalRevision(),
                envelope.decision(),
                "",
                java.util.List.of(),
                envelope.requestedObservation(),
                envelope.optionalSpeech(),
                envelope.confidence()
        );
    }

    /**
     * A few schema-capable providers fill both an action and a non-NONE
     * observation request. Executing the action would ignore the model's own
     * statement that its evidence is insufficient. Treat the lower-authority
     * observation request as the winner: discard the proposed action and
     * replan from the fresh fair observation. This can only reduce authority;
     * it never turns text into a game action.
     */
    private static DecisionEnvelope preferRequestedObservationOverConflictingAction(
            final DecisionEnvelope envelope,
            final DecisionContext context
    ) {
        if (context.lane() != DecisionLane.GAMEPLAY
                || envelope.decision() == DecisionKind.REPLAN
                || envelope.requestedObservation().kind()
                    == ObservationKind.NONE) {
            return envelope;
        }
        return new DecisionEnvelope(
                envelope.requestId(),
                envelope.observedWorldRevision(),
                envelope.goalRevision(),
                DecisionKind.REPLAN,
                "",
                java.util.List.of(),
                envelope.requestedObservation(),
                "",
                envelope.confidence()
        );
    }

    private static DecisionEnvelope canonicalizeConversationPayload(
            final DecisionEnvelope envelope,
            final DecisionContext context
    ) {
        if (context.lane() != DecisionLane.CONVERSATION) {
            return envelope;
        }
        /*
         * Conversation output can acknowledge/promote a player goal, but it
         * never owns a body action.  MiMo occasionally reuses its gameplay
         * tool vocabulary here and emits START_SKILL; treating that as a
         * malformed request strands the actual gameplay planner behind a
         * retry loop.  Convert it to a harmless replan while retaining only
         * speech, so the local intent classifier can still accept the goal.
         */
        final DecisionKind decision =
                envelope.decision() == DecisionKind.START_SKILL
                        ? DecisionKind.REPLAN
                        : envelope.decision();
        final java.util.List<SkillArgument> taskPlanArguments =
                decision == DecisionKind.ASK_PLAYER
                        ? envelope.typedArguments()
                        : java.util.List.of();
        return new DecisionEnvelope(
                envelope.requestId(),
                envelope.observedWorldRevision(),
                envelope.goalRevision(),
                decision,
                "",
                taskPlanArguments,
                RequestedObservation.none(),
                envelope.optionalSpeech(),
                envelope.confidence()
        );
    }

    private static void validateObservation(RequestedObservation observation)
            throws DecisionValidationException {
        if (observation.kind() == ObservationKind.NONE && !observation.reason().isEmpty()) {
            fail("unexpected_observation_reason", "NONE must have an empty observation reason");
        }
        if (observation.kind() != ObservationKind.NONE && observation.reason().isBlank()) {
            fail("missing_observation_reason", "An extra observation requires a reason");
        }
    }

    private static void validateSkillInvocation(DecisionEnvelope envelope, DecisionContext context)
            throws DecisionValidationException {
        if (envelope.typedArguments().size() > MAX_ARGUMENTS) {
            fail("too_many_arguments", "The skill argument count exceeds the local limit");
        }

        Set<String> argumentNames = new HashSet<>();
        for (SkillArgument argument : envelope.typedArguments()) {
            if (!ARGUMENT_NAME.matcher(argument.name()).matches()) {
                fail("invalid_argument_name", "A skill argument has an invalid name");
            }
            if (!argumentNames.add(argument.name())) {
                fail("duplicate_argument", "A skill argument name was repeated");
            }
            validateText("argument." + argument.name(), argument.value(), MAX_ARGUMENT_VALUE_CHARS);
        }

        if (context.lane() == DecisionLane.CONVERSATION) {
            validateConversationTaskPlan(envelope);
            return;
        }

        if (envelope.decision() != DecisionKind.START_SKILL) {
            if (!envelope.skillName().isEmpty() || !envelope.typedArguments().isEmpty()) {
                fail("unexpected_skill", "Only START_SKILL may include a skill invocation");
            }
            return;
        }

        if (!SKILL_NAME.matcher(envelope.skillName()).matches()) {
            fail("invalid_skill_name", "START_SKILL requires a canonical skill name");
        }
        SkillArgumentValidator validator = context.availableSkills().get(envelope.skillName());
        if (validator == null) {
            fail("unknown_skill", "The requested skill is not available");
        }
        Optional<String> validationError = validator.validate(envelope.typedArguments());
        if (validationError.isPresent()) {
            fail("invalid_skill_arguments", validationError.get());
        }
    }

    private static void validateConversationTaskPlan(
            final DecisionEnvelope envelope
    ) throws DecisionValidationException {
        if (!envelope.skillName().isEmpty()) {
            fail(
                    "conversation_skill_forbidden",
                    "Conversation output cannot invoke a skill"
            );
        }
        if (envelope.decision() != DecisionKind.ASK_PLAYER) {
            if (!envelope.typedArguments().isEmpty()) {
                fail(
                        "unexpected_task_plan",
                        "Only an accepted conversation task may include a plan"
                );
            }
            return;
        }
        if (envelope.typedArguments().size() != 2) {
            fail(
                    "missing_task_plan",
                    "An accepted conversation task requires two plan fields"
            );
        }
        final Map<String, String> fields = envelope.typedArguments()
                .stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        SkillArgument::name,
                        SkillArgument::value
                ));
        if (!fields.keySet().equals(Set.of(
                "goalRouteProfile",
                "goalTerminalMilestone"
        ))) {
            fail(
                    "invalid_task_plan_fields",
                    "Conversation task plan fields are not canonical"
            );
        }
        try {
            GoalExecutionPlan.fromModelValues(
                    fields.get("goalRouteProfile"),
                    fields.get("goalTerminalMilestone")
            );
        } catch (IllegalArgumentException invalidPlan) {
            fail("invalid_task_plan", invalidPlan.getMessage());
        }
    }

    private static void validateText(String field, String value, int maxCodePoints)
            throws DecisionValidationException {
        if (value.codePointCount(0, value.length()) > maxCodePoints) {
            fail("text_too_long", field + " exceeds the local length limit");
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (codePoint == 0
                    || (Character.isISOControl(codePoint)
                    && codePoint != '\n'
                    && codePoint != '\r'
                    && codePoint != '\t')) {
                fail("invalid_control_character", field + " contains a disallowed control character");
            }
            offset += Character.charCount(codePoint);
        }
    }

    private static void fail(String code, String message) throws DecisionValidationException {
        throw new DecisionValidationException(code, message);
    }
}
