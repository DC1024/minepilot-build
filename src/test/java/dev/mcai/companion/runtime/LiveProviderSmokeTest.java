package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.brain.BrainObservation;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.credential.ApiKeyManager;
import dev.mcai.companion.model.CapabilityProbeOutcome;
import dev.mcai.companion.model.DecisionKind;
import dev.mcai.companion.model.ModelOutcome;
import dev.mcai.companion.model.PlannerInput;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Explicit, opt-in provider smoke test.
 *
 * <p>The credential is read from the same macOS Keychain entry as the mod (or
 * the process-only MCAI_API_KEY fallback). Provider coordinates are supplied
 * by the test environment, so neither credentials nor user-specific endpoints
 * are committed to the repository. This test is disabled in ordinary builds.</p>
 */
@EnabledIfEnvironmentVariable(
    named = "MCAI_LIVE_PROVIDER_TEST",
    matches = "true"
)
final class LiveProviderSmokeTest {
    @Test
    void explicitlyNegotiatesOneRealProviderWithoutPersistingItsSecret()
        throws Exception {
        final String baseUrl = requiredEnvironment("MCAI_LIVE_BASE_URL");
        final String modelName = requiredEnvironment("MCAI_LIVE_MODEL");

        try (ApiKeyManager keys = new ApiKeyManager()) {
            try (ModelRuntime runtime = new ModelRuntime(
                keys,
                baseUrl,
                modelName,
                Duration.ofSeconds(5),
                Duration.ofSeconds(90)
            )) {
                final CapabilityProbeOutcome outcome = runtime
                    .probeExplicitly()
                    .toCompletableFuture()
                    .get(30, TimeUnit.SECONDS);
                assertInstanceOf(
                    CapabilityProbeOutcome.Supported.class,
                    outcome,
                    () -> safeFailure(outcome)
                );
                assertTrue(runtime.snapshot().gatewayReady());

                final String requestId =
                        "live-provider-foundation-complete";
                final String currentGuide =
                        MinecraftPlannerInputFactory
                                .guideForAvailableSkills(
                                        CompanionRuntime
                                                .coreSkillGuideForTests(),
                                        Set.of(
                                                "prepare_basic_crafting",
                                                "prepare_stone_tools",
                                                "prepare_iron_toolkit",
                                                "establish_foundation_workstations",
                                                "prepare_foundation_shelter_materials",
                                                "build_shelter_step",
                                                "hunt_observed_food_animal",
                                                "secure_visible_food_reserve"
                                        ),
                                        Set.of()
                                );
                final PlannerInput plannerInput =
                        new MinecraftPlannerInputFactory(
                                new SkillRegistry(),
                                currentGuide
                        ).create(
                                requestId,
                                new GoalSnapshot(
                                        Optional.empty(),
                                        3L,
                                        GoalStatus.RUNNING,
                                        GoalSource.MCP,
                                        "建立安全据点并生存到第二天",
                                        "",
                                        Instant.EPOCH,
                                        false
                                ),
                                new BrainObservation(
                                        7L,
                                        new SkillContext(
                                                3L,
                                                7L,
                                                12_100L,
                                                true,
                                                true,
                                                0.0
                                        ),
                                        "{}",
                                        """
                                        {
                                          "verifiedCompletionRouteData": {
                                            "profile": "FOUNDATION",
                                            "verifiedMilestones": [
                                              "SHELTER_COMPLETED",
                                              "FIRST_NIGHT_SURVIVED"
                                            ],
                                            "nextObjectives": []
                                          }
                                        }
                                        """
                                )
                        );
                final ModelOutcome decisionOutcome = runtime.gateway()
                    .decide(plannerInput)
                    .toCompletableFuture()
                    .get(90, TimeUnit.SECONDS);
                final ModelOutcome.Success success = assertInstanceOf(
                    ModelOutcome.Success.class,
                    decisionOutcome,
                    () -> safeDecisionFailure(decisionOutcome)
                );
                assertEquals(requestId, success.decision().requestId());
                assertEquals(7L, success.decision().observedWorldRevision());
                assertEquals(3L, success.decision().goalRevision());
                assertEquals(
                        DecisionKind.COMPLETE_GOAL,
                        success.decision().decision()
                );

                final Skill<Unit> noArguments = noArgumentSkill();
                final SkillRegistry stonePhaseSkills =
                        new SkillRegistry()
                                .register(
                                        "prepare_stone_tools",
                                        noArguments
                                )
                                .register("craft_recipe", noArguments)
                                .register(
                                        "collect_observed_item",
                                        noArguments
                                );
                final String stoneRequestId =
                        "live-provider-stone-phase";
                final PlannerInput stoneInput =
                        new MinecraftPlannerInputFactory(
                                stonePhaseSkills,
                                CompanionRuntime
                                        .coreSkillGuideForTests()
                        ).create(
                                stoneRequestId,
                                new GoalSnapshot(
                                        Optional.empty(),
                                        4L,
                                        GoalStatus.RUNNING,
                                        GoalSource.MCP,
                                        "建立安全据点并生存到第二天",
                                        "",
                                        Instant.EPOCH,
                                        false
                                ),
                                new BrainObservation(
                                        8L,
                                        new SkillContext(
                                                4L,
                                                8L,
                                                12_200L,
                                                true,
                                                true,
                                                0.0
                                        ),
                                        """
                                        {
                                          "sampleSequence": 52,
                                          "craftingAffordances": [{
                                            "recipeId": "minecraft:oak_planks"
                                          }],
                                          "visibleEntities": [{
                                            "observationId": "visible-drop",
                                            "type": "minecraft:item",
                                            "properties": {
                                              "itemId": "minecraft:beef"
                                            }
                                          }]
                                        }
                                        """,
                                        """
                                        {
                                          "verifiedCompletionRouteData": {
                                            "profile": "FOUNDATION",
                                            "nextObjectives": [
                                              "CRAFT_AND_MINE_STONE"
                                            ]
                                          }
                                        }
                                        """
                                )
                        );
                assertEquals(
                        Set.of("prepare_stone_tools"),
                        stoneInput.decisionContext()
                                .availableSkills()
                                .keySet()
                );
                final ModelOutcome stoneOutcome = runtime.gateway()
                        .decide(stoneInput)
                        .toCompletableFuture()
                        .get(90, TimeUnit.SECONDS);
                final ModelOutcome.Success stoneSuccess = assertInstanceOf(
                        ModelOutcome.Success.class,
                        stoneOutcome,
                        () -> safeDecisionFailure(stoneOutcome)
                );
                assertEquals(
                        DecisionKind.START_SKILL,
                        stoneSuccess.decision().decision()
                );
                assertEquals(
                        "prepare_stone_tools",
                        stoneSuccess.decision().skillName()
                );
                assertTrue(
                        stoneSuccess.decision().typedArguments().isEmpty()
                );

                final SkillRegistry blazePhaseSkills =
                        new SkillRegistry()
                                .register(
                                        "secure_nether_blaze_material",
                                        noArguments
                                )
                                .register(
                                        "fight_ender_dragon",
                                        noArguments
                                );
                final String blazeRequestId =
                        "live-provider-completion-blaze-phase";
                final PlannerInput blazeInput =
                        new MinecraftPlannerInputFactory(
                                blazePhaseSkills,
                                CompanionRuntime
                                        .coreSkillGuideForTests()
                        ).create(
                                blazeRequestId,
                                new GoalSnapshot(
                                        Optional.empty(),
                                        5L,
                                        GoalStatus.RUNNING,
                                        GoalSource.HARDCORE_EVALUATION,
                                        "通关 Minecraft",
                                        "",
                                        Instant.EPOCH,
                                        true
                                ),
                                new BrainObservation(
                                        9L,
                                        new SkillContext(
                                                5L,
                                                9L,
                                                12_300L,
                                                true,
                                                true,
                                                0.0
                                        ),
                                        """
                                        {
                                          "sampleSequence": 73,
                                          "self": {
                                            "dimension":
                                              "minecraft:the_nether"
                                          },
                                          "visibleEntities": [{
                                            "observationId": "visible-0",
                                            "type": "minecraft:blaze",
                                            "distance": 8.0,
                                            "lineOfSight": true,
                                            "properties": {
                                              "hostile": true
                                            }
                                          }]
                                        }
                                        """,
                                        """
                                        {
                                          "verifiedCompletionRouteData": {
                                            "profile": "COMPLETION",
                                            "verifiedMilestones": [
                                              "BODY_ACTIVE",
                                              "WOOD_OBTAINED",
                                              "BASIC_CRAFTING_READY",
                                              "STONE_TOOL_OBTAINED",
                                              "FOOD_SECURED",
                                              "IRON_TOOLKIT_OBTAINED",
                                              "NETHER_ENTERED"
                                            ],
                                            "nextObjectives": [
                                              "FIND_AND_ACQUIRE_BLAZE_MATERIAL"
                                            ],
                                            "criticalOwnedCounts": {
                                              "blaze_route_units": 0
                                            },
                                            "currentMinimumTargets": {
                                              "blaze_route_units": 14
                                            }
                                          }
                                        }
                                        """
                                )
                        );
                assertEquals(
                        Set.of("secure_nether_blaze_material"),
                        blazeInput.decisionContext()
                                .availableSkills()
                                .keySet()
                );
                final ModelOutcome blazeOutcome = runtime.gateway()
                        .decide(blazeInput)
                        .toCompletableFuture()
                        .get(90, TimeUnit.SECONDS);
                final ModelOutcome.Success blazeSuccess =
                        assertInstanceOf(
                                ModelOutcome.Success.class,
                                blazeOutcome,
                                () -> safeDecisionFailure(blazeOutcome)
                        );
                assertEquals(
                        DecisionKind.START_SKILL,
                        blazeSuccess.decision().decision()
                );
                assertEquals(
                        "secure_nether_blaze_material",
                        blazeSuccess.decision().skillName()
                );
                assertTrue(
                        blazeSuccess.decision()
                                .typedArguments()
                                .isEmpty()
                );

                final SkillRegistry enderPhaseSkills =
                        new SkillRegistry()
                                .register(
                                        "secure_ender_pearl_reserve",
                                        noArguments
                                )
                                .register(
                                        "fight_ender_dragon",
                                        noArguments
                                );
                final String enderRequestId =
                        "live-provider-completion-ender-phase";
                final PlannerInput enderInput =
                        new MinecraftPlannerInputFactory(
                                enderPhaseSkills,
                                CompanionRuntime
                                        .coreSkillGuideForTests()
                        ).create(
                                enderRequestId,
                                new GoalSnapshot(
                                        Optional.empty(),
                                        6L,
                                        GoalStatus.RUNNING,
                                        GoalSource.HARDCORE_EVALUATION,
                                        "通关 Minecraft",
                                        "",
                                        Instant.EPOCH,
                                        true
                                ),
                                new BrainObservation(
                                        10L,
                                        new SkillContext(
                                                6L,
                                                10L,
                                                12_400L,
                                                true,
                                                true,
                                                0.0
                                        ),
                                        """
                                        {
                                          "sampleSequence": 74,
                                          "self": {
                                            "dimension":
                                              "minecraft:overworld"
                                          },
                                          "visibleEntities": []
                                        }
                                        """,
                                        """
                                        {
                                          "verifiedCompletionRouteData": {
                                            "profile": "COMPLETION",
                                            "verifiedMilestones": [
                                              "BODY_ACTIVE",
                                              "WOOD_OBTAINED",
                                              "BASIC_CRAFTING_READY",
                                              "STONE_TOOL_OBTAINED",
                                              "FOOD_SECURED",
                                              "IRON_TOOLKIT_OBTAINED",
                                              "NETHER_ENTERED",
                                              "BLAZE_MATERIAL_OBTAINED"
                                            ],
                                            "nextObjectives": [
                                              "ACQUIRE_ENDER_PEARLS"
                                            ],
                                            "criticalOwnedCounts": {
                                              "ender_route_units": 0
                                            },
                                            "currentMinimumTargets": {
                                              "ender_route_units": 14
                                            }
                                          }
                                        }
                                        """
                                )
                        );
                assertEquals(
                        Set.of("secure_ender_pearl_reserve"),
                        enderInput.decisionContext()
                                .availableSkills()
                                .keySet()
                );
                final ModelOutcome enderOutcome = runtime.gateway()
                        .decide(enderInput)
                        .toCompletableFuture()
                        .get(90, TimeUnit.SECONDS);
                final ModelOutcome.Success enderSuccess =
                        assertInstanceOf(
                                ModelOutcome.Success.class,
                                enderOutcome,
                                () -> safeDecisionFailure(enderOutcome)
                        );
                assertEquals(
                        enderRequestId,
                        enderSuccess.decision().requestId()
                );
                assertEquals(
                        10L,
                        enderSuccess.decision()
                                .observedWorldRevision()
                );
                assertEquals(
                        6L,
                        enderSuccess.decision().goalRevision()
                );
                assertEquals(
                        DecisionKind.START_SKILL,
                        enderSuccess.decision().decision()
                );
                assertEquals(
                        "secure_ender_pearl_reserve",
                        enderSuccess.decision().skillName()
                );
                assertTrue(
                        enderSuccess.decision()
                                .typedArguments()
                                .isEmpty()
                );

                final SkillRegistry strongholdPhaseSkills =
                        new SkillRegistry()
                                .register(
                                        "triangulate_stronghold_search_area",
                                        noArguments
                                )
                                .register(
                                        "fight_ender_dragon",
                                        noArguments
                                );
                final String strongholdRequestId =
                        "live-provider-completion-stronghold-phase";
                final PlannerInput strongholdInput =
                        new MinecraftPlannerInputFactory(
                                strongholdPhaseSkills,
                                CompanionRuntime
                                        .coreSkillGuideForTests()
                        ).create(
                                strongholdRequestId,
                                new GoalSnapshot(
                                        Optional.empty(),
                                        7L,
                                        GoalStatus.RUNNING,
                                        GoalSource.HARDCORE_EVALUATION,
                                        "通关 Minecraft",
                                        "",
                                        Instant.EPOCH,
                                        true
                                ),
                                new BrainObservation(
                                        11L,
                                        new SkillContext(
                                                7L,
                                                11L,
                                                12_500L,
                                                true,
                                                true,
                                                0.0
                                        ),
                                        """
                                        {
                                          "sampleSequence": 75,
                                          "self": {
                                            "dimension":
                                              "minecraft:overworld",
                                            "health": 20.0,
                                            "foodLevel": 20,
                                            "inventory": [{
                                              "itemId":
                                                "minecraft:ender_eye",
                                              "count": 14
                                            }]
                                          },
                                          "visibleEntities": []
                                        }
                                        """,
                                        """
                                        {
                                          "verifiedCompletionRouteData": {
                                            "profile": "COMPLETION",
                                            "verifiedMilestones": [
                                              "BODY_ACTIVE",
                                              "WOOD_OBTAINED",
                                              "BASIC_CRAFTING_READY",
                                              "STONE_TOOL_OBTAINED",
                                              "FOOD_SECURED",
                                              "IRON_TOOLKIT_OBTAINED",
                                              "NETHER_ENTERED",
                                              "BLAZE_MATERIAL_OBTAINED",
                                              "ENDER_PEARL_OBTAINED",
                                              "EYE_OF_ENDER_CRAFTED"
                                            ],
                                            "nextObjectives": [
                                              "TRACE_STRONGHOLD_BEARING"
                                            ],
                                            "criticalOwnedCounts": {
                                              "eyes_of_ender": 14
                                            }
                                          }
                                        }
                                        """
                                )
                        );
                assertEquals(
                        Set.of(
                                "triangulate_stronghold_search_area"
                        ),
                        strongholdInput.decisionContext()
                                .availableSkills()
                                .keySet()
                );
                final ModelOutcome strongholdOutcome =
                        runtime.gateway()
                                .decide(strongholdInput)
                                .toCompletableFuture()
                                .get(90, TimeUnit.SECONDS);
                final ModelOutcome.Success strongholdSuccess =
                        assertInstanceOf(
                                ModelOutcome.Success.class,
                                strongholdOutcome,
                                () -> safeDecisionFailure(
                                        strongholdOutcome
                                )
                        );
                assertEquals(
                        strongholdRequestId,
                        strongholdSuccess.decision().requestId()
                );
                assertEquals(
                        11L,
                        strongholdSuccess.decision()
                                .observedWorldRevision()
                );
                assertEquals(
                        7L,
                        strongholdSuccess.decision().goalRevision()
                );
                assertEquals(
                        DecisionKind.START_SKILL,
                        strongholdSuccess.decision().decision()
                );
                assertEquals(
                        "triangulate_stronghold_search_area",
                        strongholdSuccess.decision().skillName()
                );
                assertTrue(
                        strongholdSuccess.decision()
                                .typedArguments()
                                .isEmpty()
                );

                final SkillRegistry strongholdReachSkills =
                        new SkillRegistry()
                                .register(
                                        "reach_observed_stronghold",
                                        noArguments
                                )
                                .register(
                                        "fight_ender_dragon",
                                        noArguments
                                );
                final String reachRequestId =
                        "live-provider-completion-stronghold-reach";
                final PlannerInput reachInput =
                        new MinecraftPlannerInputFactory(
                                strongholdReachSkills,
                                CompanionRuntime
                                        .coreSkillGuideForTests()
                        ).create(
                                reachRequestId,
                                new GoalSnapshot(
                                        Optional.empty(),
                                        8L,
                                        GoalStatus.RUNNING,
                                        GoalSource.HARDCORE_EVALUATION,
                                        "通关 Minecraft",
                                        "",
                                        Instant.EPOCH,
                                        true
                                ),
                                new BrainObservation(
                                        12L,
                                        new SkillContext(
                                                8L,
                                                12L,
                                                12_600L,
                                                true,
                                                true,
                                                0.0
                                        ),
                                        """
                                        {
                                          "sampleSequence": 76,
                                          "self": {
                                            "dimension":
                                              "minecraft:overworld",
                                            "position": {
                                              "x": 512.5,
                                              "y": 70.0,
                                              "z": -320.5
                                            },
                                            "health": 20.0,
                                            "foodLevel": 20,
                                            "inventory": [{
                                              "itemId":
                                                "minecraft:iron_pickaxe",
                                              "count": 1
                                            }, {
                                              "itemId":
                                                "minecraft:torch",
                                              "count": 32
                                            }, {
                                              "itemId":
                                                "minecraft:ender_eye",
                                              "count": 12
                                            }]
                                          },
                                          "visibleBlockFaces": [],
                                          "visibleEntities": []
                                        }
                                        """,
                                        """
                                        {
                                          "verifiedCompletionRouteData": {
                                            "profile": "COMPLETION",
                                            "verifiedMilestones": [
                                              "BODY_ACTIVE",
                                              "WOOD_OBTAINED",
                                              "BASIC_CRAFTING_READY",
                                              "STONE_TOOL_OBTAINED",
                                              "FOOD_SECURED",
                                              "IRON_TOOLKIT_OBTAINED",
                                              "NETHER_ENTERED",
                                              "BLAZE_MATERIAL_OBTAINED",
                                              "ENDER_PEARL_OBTAINED",
                                              "EYE_OF_ENDER_CRAFTED",
                                              "STRONGHOLD_BEARING_MEASURED",
                                              "STRONGHOLD_SEARCH_AREA_TRIANGULATED"
                                            ],
                                            "nextObjectives": [
                                              "ACTIVATE_AND_ENTER_END_PORTAL"
                                            ]
                                          },
                                          "recentFairEyeTraceData": {
                                            "estimatedIntersection": {
                                              "x": 820.0,
                                              "z": -96.0,
                                              "crossingAngleDegrees": 32.0,
                                              "uncertaintyRadius": 18.0,
                                              "supportingTraceCount": 2
                                            }
                                          }
                                        }
                                        """
                                )
                        );
                assertEquals(
                        Set.of("reach_observed_stronghold"),
                        reachInput.decisionContext()
                                .availableSkills()
                                .keySet()
                );
                final ModelOutcome reachOutcome = runtime.gateway()
                        .decide(reachInput)
                        .toCompletableFuture()
                        .get(90, TimeUnit.SECONDS);
                final ModelOutcome.Success reachSuccess =
                        assertInstanceOf(
                                ModelOutcome.Success.class,
                                reachOutcome,
                                () -> safeDecisionFailure(
                                        reachOutcome
                                )
                        );
                assertEquals(
                        reachRequestId,
                        reachSuccess.decision().requestId()
                );
                assertEquals(
                        12L,
                        reachSuccess.decision()
                                .observedWorldRevision()
                );
                assertEquals(
                        8L,
                        reachSuccess.decision().goalRevision()
                );
                assertEquals(
                        DecisionKind.START_SKILL,
                        reachSuccess.decision().decision()
                );
                assertEquals(
                        "reach_observed_stronghold",
                        reachSuccess.decision().skillName()
                );
                assertTrue(
                        reachSuccess.decision()
                                .typedArguments()
                                .isEmpty()
                );
            }
        }
    }

    private static Skill<Unit> observedBlazeSkill() {
        return skillWithParser(arguments -> {
            if (arguments == null || arguments.size() != 3) {
                return SkillParameterResult.invalid(
                        "invalid_blaze_arguments"
                );
            }
            final Map<String, String> values;
            try {
                values = arguments.stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                SkillArgument::name,
                                SkillArgument::value
                        )
                );
                final long sampleSequence = Long.parseLong(
                        values.get("sampleSequence")
                );
                final int maximumTicks = Integer.parseInt(
                        values.get("maximumTicks")
                );
                if (sampleSequence < 0
                        || !Set.of(
                                "sampleSequence",
                                "observationId",
                                "maximumTicks"
                        ).equals(values.keySet())
                        || !values.get("observationId")
                                .matches(
                                        "visible-(?:0|[1-9][0-9]{0,5})"
                                )
                        || maximumTicks < 80
                        || maximumTicks > 1_200) {
                    return SkillParameterResult.invalid(
                            "invalid_blaze_arguments"
                    );
                }
            } catch (RuntimeException invalid) {
                return SkillParameterResult.invalid(
                        "invalid_blaze_arguments"
                );
            }
            return SkillParameterResult.valid(Unit.INSTANCE);
        });
    }

    private static Skill<Unit> noArgumentSkill() {
        return skillWithParser(arguments ->
                arguments.isEmpty()
                        ? SkillParameterResult.valid(Unit.INSTANCE)
                        : SkillParameterResult.invalid(
                                "unexpected_arguments"
                        )
        );
    }

    private static Skill<Unit> skillWithParser(
            final SkillParameterParser<Unit> parser
    ) {
        return new Skill<>() {
            @Override
            public SkillParameterParser<Unit> parameters() {
                return parser;
            }

            @Override
            public Optional<SkillFailure> preconditions(
                    final SkillContext context,
                    final Unit parameters
            ) {
                return Optional.empty();
            }

            @Override
            public void start(
                    final SkillContext context,
                    final Unit parameters
            ) {
            }

            @Override
            public SkillTickResult tick(
                    final SkillContext context,
                    final Unit parameters
            ) {
                return SkillTickResult.completed();
            }

            @Override
            public SkillCheckpoint checkpoint(
                    final SkillContext context,
                    final Unit parameters
            ) {
                return SkillCheckpoint.empty();
            }

            @Override
            public void cancel(
                    final SkillContext context,
                    final Unit parameters
            ) {
            }

            @Override
            public SkillResult result(
                    final SkillContext context,
                    final Unit parameters
            ) {
                return SkillResult.completed();
            }
        };
    }

    private static String requiredEnvironment(final String name) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String safeFailure(final CapabilityProbeOutcome outcome) {
        if (outcome instanceof CapabilityProbeOutcome.Failure failure) {
            return "Provider probe stopped with "
                + failure.error().kind()
                + " after "
                + failure.requestsMade()
                + " request(s)";
        }
        return "Provider probe did not return a supported profile";
    }

    private static String safeDecisionFailure(final ModelOutcome outcome) {
        if (outcome instanceof ModelOutcome.Failure failure) {
            return "Provider decision stopped with "
                + failure.error().kind()
                + " (HTTP "
                + failure.error().httpStatus()
                + "): "
                + failure.error().safeMessage();
        }
        return "Provider did not return a validated decision";
    }

    private enum Unit {
        INSTANCE
    }
}
