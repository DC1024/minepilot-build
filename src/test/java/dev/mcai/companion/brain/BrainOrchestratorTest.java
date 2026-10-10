package dev.mcai.companion.brain;

import com.google.gson.JsonParser;
import dev.mcai.companion.control.GoalCoordinator;
import dev.mcai.companion.control.GoalExecutionPlan;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.control.InMemoryGoalRevisionStore;
import dev.mcai.companion.model.DecisionContext;
import dev.mcai.companion.model.DecisionEnvelope;
import dev.mcai.companion.model.DecisionKind;
import dev.mcai.companion.model.GatewayStatus;
import dev.mcai.companion.model.ModelFailure;
import dev.mcai.companion.model.ModelFailureKind;
import dev.mcai.companion.model.ModelGateway;
import dev.mcai.companion.model.ModelOutcome;
import dev.mcai.companion.model.ObservationKind;
import dev.mcai.companion.model.PlannerInput;
import dev.mcai.companion.model.Protocol;
import dev.mcai.companion.model.RequestTrace;
import dev.mcai.companion.model.RequestedObservation;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.model.TokenUsage;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillCheckpointSink;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillRuntimePolicy;
import dev.mcai.companion.skill.SkillSupervisor;
import dev.mcai.companion.skill.SkillTickResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrainOrchestratorTest {
    @Test
    void encodedSurvivalRouteStartsItsForcedCompoundWithoutAnotherRequest() {
        try (Fixture fixture = new Fixture()) {
            fixture.observations.trustedRuntimeJson = """
                    {
                      "verifiedCompletionRouteData": {
                        "profile": "FOUNDATION",
                        "nextObjectives": ["GATHER_VISIBLE_WOOD"]
                      }
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "Please handle the first useful preparation stage.",
                    GoalSource.PLAYER_CHAT,
                    GoalExecutionPlan.foundation(
                            GoalExecutionPlan.Target
                                    .STONE_TOOL_OBTAINED
                    )
            ).accepted());

            fixture.brain.tick();

            assertEquals(0, fixture.gateway.requestCount());
            assertEquals(1, fixture.gatherWoodSkill.startCalls);
            assertEquals(
                    "gather_nearby_wood",
                    fixture.skills.snapshot().skillName()
            );
            assertTrue(fixture.hasNotice(
                    "encoded_route_skill_started"
            ));
        }
    }

    @Test
    void verifiedWoodMilestoneRetiresTheContinuousRouteGatherer() {
        try (Fixture fixture = new Fixture()) {
            fixture.observations.trustedRuntimeJson = """
                    {
                      "verifiedCompletionRouteData": {
                        "profile": "FOUNDATION",
                        "nextObjectives": ["GATHER_VISIBLE_WOOD"],
                        "verifiedMilestones": ["BODY_ACTIVE"]
                      }
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "Reach the requested early survival milestone.",
                    GoalSource.PLAYER_CHAT,
                    GoalExecutionPlan.foundation(
                            GoalExecutionPlan.Target
                                    .STONE_TOOL_OBTAINED
                    )
            ).accepted());
            fixture.brain.tick();
            assertEquals(
                    "gather_nearby_wood",
                    fixture.skills.snapshot().skillName()
            );

            fixture.observations.trustedRuntimeJson = """
                    {
                      "verifiedCompletionRouteData": {
                        "profile": "FOUNDATION",
                        "nextObjectives": ["PREPARE_BASIC_CRAFTING"],
                        "verifiedMilestones": [
                          "BODY_ACTIVE",
                          "WOOD_OBTAINED"
                        ]
                      }
                    }
                    """;
            fixture.brain.tick();

            assertEquals(1, fixture.gatherWoodSkill.cancelCalls);
            assertFalse(fixture.skills.snapshot().state()
                    == SkillSupervisor.State.RUNNING);
            assertTrue(fixture.hasNotice(
                    "route_skill_retired_after_verified_milestone"
            ));
        }
    }

    @Test
    void ironMaterialTerminalRetiresToolkitCompoundAfterRealPickup() {
        try (Fixture fixture = new Fixture()) {
            fixture.observations.trustedRuntimeJson = """
                    {
                      "verifiedCompletionRouteData": {
                        "profile": "FOUNDATION",
                        "nextObjectives": ["ACQUIRE_IRON_TOOLKIT"],
                        "verifiedMilestones": [
                          "BODY_ACTIVE",
                          "WOOD_OBTAINED",
                          "BASIC_CRAFTING_READY",
                          "STONE_TOOL_OBTAINED",
                          "FOOD_SECURED"
                        ]
                      }
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "Reach the first physically owned iron material.",
                    GoalSource.PLAYER_CHAT,
                    GoalExecutionPlan.foundation(
                            GoalExecutionPlan.Target.IRON_OBTAINED
                    )
            ).accepted());
            fixture.brain.tick();
            assertEquals(
                    "prepare_iron_toolkit",
                    fixture.skills.snapshot().skillName()
            );

            fixture.observations.trustedRuntimeJson = """
                    {
                      "verifiedCompletionRouteData": {
                        "profile": "FOUNDATION",
                        "nextObjectives": [],
                        "verifiedMilestones": [
                          "BODY_ACTIVE",
                          "WOOD_OBTAINED",
                          "BASIC_CRAFTING_READY",
                          "STONE_TOOL_OBTAINED",
                          "FOOD_SECURED",
                          "IRON_OBTAINED"
                        ]
                      }
                    }
                    """;
            fixture.brain.tick();

            assertEquals(1, fixture.ironToolkitSkill.cancelCalls);
            assertTrue(fixture.hasNotice(
                    "route_skill_retired_after_verified_milestone"
            ));
        }
    }

    @Test
    void unencodedGoalCannotUseTheLocalRouteDispatcher() {
        try (Fixture fixture = new Fixture()) {
            fixture.observations.trustedRuntimeJson = """
                    {
                      "verifiedCompletionRouteData": {
                        "profile": "FOUNDATION",
                        "nextObjectives": ["GATHER_VISIBLE_WOOD"]
                      }
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "Please handle something useful.",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            fixture.brain.tick();

            assertEquals(1, fixture.gateway.requestCount());
            assertEquals(0, fixture.gatherWoodSkill.startCalls);
        }
    }

    @Test
    void addsBoundedModelCorrectionToTrustedPlannerState() {
        final BrainObservation observation = new BrainObservation(
                8,
                new SkillContext(3, 8, 20, true, true, 0.0),
                "{}",
                "{}"
        );

        final BrainObservation corrected =
                BrainOrchestrator.withPlannerCorrection(
                        observation,
                        "invalid_skill_arguments"
                );

        assertEquals(
                "invalid_skill_arguments",
                JsonParser.parseString(
                        corrected.trustedRuntimeJson()
                ).getAsJsonObject().get(
                        "lastModelDecisionFailureCode"
                ).getAsString()
        );
        assertEquals(
                observation,
                BrainOrchestrator.withPlannerCorrection(
                        observation,
                        ""
                )
        );
        assertEquals(
                "unknown_skill",
                BrainOrchestrator.plannerCorrectionCode(
                        new ModelFailure(
                                ModelFailureKind.MALFORMED_RESPONSE,
                                200,
                                "",
                                "",
                                "",
                                "",
                                Optional.empty(),
                                "",
                                "The model decision failed local "
                                        + "validation: unknown_skill"
                        )
                )
        );
    }

    @Test
    void preservesSurveyBlockSequenceButCanonicalizesCurrentHandles() {
        final DecisionEnvelope surveyGather =
                new DecisionEnvelope(
                        "request",
                        8,
                        3,
                        DecisionKind.START_SKILL,
                        "gather_visible_block_cluster",
                        List.of(new SkillArgument(
                                "sampleSequence",
                                "12"
                        )),
                        RequestedObservation.none(),
                        "",
                        0.9
                );
        final DecisionEnvelope currentHandle =
                new DecisionEnvelope(
                        "request",
                        8,
                        3,
                        DecisionKind.START_SKILL,
                        "follow_entity",
                        List.of(new SkillArgument(
                                "sampleSequence",
                                "12"
                        )),
                        RequestedObservation.none(),
                        "",
                        0.9
                );

        assertEquals(
                "12",
                BrainOrchestrator.bindAuthoritativeSampleSequence(
                        surveyGather,
                        99
                ).typedArguments().getFirst().value()
        );
        assertEquals(
                "99",
                BrainOrchestrator.bindAuthoritativeSampleSequence(
                        currentHandle,
                        99
                ).typedArguments().getFirst().value()
        );
    }

    @Test
    void requestsOnlyForARunningIdleGoalAndAppliesCompletionOnServerTick() {
        try (Fixture fixture = new Fixture()) {
            fixture.brain.tick();
            assertEquals(0, fixture.gateway.requestCount());

            fixture.startGoal();
            fixture.brain.tick();
            assertEquals(1, fixture.gateway.requestCount());
            fixture.brain.tick();
            assertEquals(1, fixture.gateway.requestCount());

            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.START_SKILL,
                    "test",
                    "",
                    List.of()
            ));

            assertEquals(SkillSupervisor.State.IDLE, fixture.skills.snapshot().state());
            assertTrue(fixture.brain.snapshot().mailboxOccupied());
            assertEquals(0, fixture.skill.startCalls);

            fixture.brain.tick();
            assertEquals(SkillSupervisor.State.RUNNING, fixture.skills.snapshot().state());
            assertEquals(1, fixture.skill.startCalls);
            assertEquals(1, fixture.gateway.requestCount());
            final BrainEvent.Usage usage = assertInstanceOf(
                    BrainEvent.Usage.class,
                    fixture.events.stream()
                            .filter(BrainEvent.Usage.class::isInstance)
                            .findFirst()
                            .orElseThrow()
            );
            assertEquals(
                    fixture.gateway.lastInput()
                            .decisionContext()
                            .requestId(),
                    usage.requestId()
            );
            assertEquals(-1L, usage.totalTokens());
            assertEquals(
                List.of(
                    BrainEvent.ModelAuditStage.AI_PERCEPTION_RECEIVED,
                    BrainEvent.ModelAuditStage.MODEL_REQUEST_STARTED,
                    BrainEvent.ModelAuditStage.MODEL_RESPONSE_RECEIVED,
                    BrainEvent.ModelAuditStage.DECISION_SCHEMA_VALIDATED,
                    BrainEvent.ModelAuditStage.DECISION_REVISION_ACCEPTED,
                    BrainEvent.ModelAuditStage.SKILL_STARTED
                ),
                fixture.events.stream()
                    .filter(BrainEvent.ModelAudit.class::isInstance)
                    .map(BrainEvent.ModelAudit.class::cast)
                    .map(BrainEvent.ModelAudit::stage)
                    .toList()
            );

            fixture.brain.tick();
            assertEquals(1, fixture.skill.tickCalls);
            assertEquals(1, fixture.gateway.requestCount());
        }
    }

    @Test
    void planningOnlyCanStartButNeverTickABodySkill() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tickPlanningOnly();
            assertEquals(1, fixture.gateway.requestCount());

            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.START_SKILL,
                    "test",
                    "",
                    List.of()
            ));
            fixture.brain.tickPlanningOnly();

            assertEquals(1, fixture.skill.startCalls);
            assertEquals(0, fixture.skill.tickCalls);
            assertEquals(
                    SkillSupervisor.State.RUNNING,
                    fixture.skills.snapshot().state()
            );

            fixture.brain.tickPlanningOnly();
            assertEquals(0, fixture.skill.tickCalls);

            fixture.brain.tick();
            assertEquals(1, fixture.skill.tickCalls);
        }
    }

    @Test
    void discardsACompletionWhenTheObservationEpochChanged() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            PlannerInput firstInput = fixture.gateway.lastInput();
            fixture.observations.epoch++;
            fixture.gateway.completeCurrent(success(
                    firstInput,
                    DecisionKind.START_SKILL,
                    "test",
                    "",
                    List.of()
            ));

            fixture.brain.tick();
            assertEquals(0, fixture.skill.startCalls);
            assertEquals(1, fixture.gateway.requestCount());
            assertTrue(fixture.hasNotice("stale_or_duplicate_completion"));

            fixture.clock.set(99);
            fixture.brain.tick();
            assertEquals(1, fixture.gateway.requestCount());
            fixture.clock.set(100);
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
        }
    }

    @Test
    void rebasesAStalePurePlanningDecisionAfterSmallWorldDrift() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            PlannerInput firstInput = fixture.gateway.lastInput();
            fixture.observations.epoch++;
            fixture.gateway.completeCurrent(success(
                    firstInput,
                    DecisionKind.REPLAN,
                    "",
                    "我重新评估当前情况。",
                    List.of()
            ));

            fixture.brain.tick();

            assertFalse(fixture.hasNotice("stale_or_duplicate_completion"));
            assertTrue(fixture.hasNotice(
                    "non_action_decision_world_drift_rebased"
            ));
            assertEquals(0, fixture.skill.startCalls);

            fixture.clock.set(100);
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
        }
    }

    @Test
    void boundedMailboxDropsADuplicateCompletionAndAppliesOnlyOnce() {
        try (Fixture fixture = new Fixture()) {
            fixture.gateway.duplicateNextCompletion = true;
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.START_SKILL,
                    "test",
                    "",
                    List.of()
            ));

            assertTrue(fixture.brain.snapshot().mailboxOccupied());
            assertEquals(1, fixture.brain.snapshot().droppedMailboxCompletions());
            assertEquals(0, fixture.skill.startCalls);

            fixture.brain.tick();
            assertEquals(1, fixture.skill.startCalls);
            assertTrue(fixture.hasNotice("mailbox_completion_dropped"));
        }
    }

    @Test
    void cancelsAndDiscardsAnOldGoalRevisionRequest() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            long oldRevision = fixture.gateway.lastInput()
                    .decisionContext()
                    .goalRevision();

            fixture.goals.setGoal("replacement goal", GoalSource.MCP);
            long newRevision = fixture.goals.snapshot().revision();
            fixture.brain.tick();

            assertTrue(newRevision > oldRevision);
            assertTrue(fixture.gateway.cancelRevisions.contains(newRevision));
            assertEquals(0, fixture.skill.startCalls);
            assertEquals(1, fixture.gateway.requestCount());
            fixture.clock.set(100);
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
        }
    }

    @Test
    void bodySessionChangeAbandonsOldSkillAndAllowsFreshPlanning() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.START_SKILL,
                    "test",
                    "",
                    List.of()
            ));
            fixture.brain.tick();
            assertEquals(
                    SkillSupervisor.State.RUNNING,
                    fixture.skills.snapshot().state()
            );

            fixture.brain.onBodySessionChanged();

            assertEquals(
                    SkillSupervisor.State.SAFE_IDLE,
                    fixture.skills.snapshot().state()
            );
            assertEquals(
                    "body_session_ended",
                    fixture.skills.snapshot()
                        .terminalResult()
                        .orElseThrow()
                        .failure()
                        .orElseThrow()
                        .code()
            );
            assertEquals(0, fixture.skill.cancelCalls);

            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
            assertEquals(GoalStatus.RUNNING, fixture.goals.snapshot().status());
        }
    }

    @Test
    void retiresAnActiveOldGoalSkillBeforePlanningAReplacement() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.START_SKILL,
                    "test",
                    "",
                    List.of()
            ));
            fixture.brain.tick();
            assertEquals(1, fixture.skill.startCalls);
            assertEquals(
                    BrainOrchestrator.State.EXECUTING_SKILL,
                    fixture.brain.snapshot().state()
            );

            assertTrue(
                fixture.goals.setGoal("replacement goal", GoalSource.MCP)
                    .accepted()
            );
            fixture.brain.tick();

            assertEquals(1, fixture.skill.cancelCalls);
            assertEquals(1, fixture.gateway.requestCount());
            assertTrue(fixture.hasNotice("replacement_skill_retired"));

            fixture.clock.set(100);
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
            assertEquals(
                    fixture.goals.snapshot().revision(),
                    fixture.gateway.lastInput()
                        .decisionContext()
                        .goalRevision()
            );
        }
    }

    @Test
    void continueAndReplanRespectMinimumBackoff() {
        for (DecisionKind kind : List.of(DecisionKind.CONTINUE, DecisionKind.REPLAN)) {
            try (Fixture fixture = new Fixture()) {
                fixture.startGoal();
                fixture.brain.tick();
                fixture.gateway.completeCurrent(success(
                        fixture.gateway.lastInput(),
                        kind,
                        "",
                        "好的，我正在跟着你。",
                        List.of()
                ));

                fixture.brain.tick();
                assertEquals(1, fixture.gateway.requestCount());
                assertEquals(BrainOrchestrator.State.BACKOFF,
                        fixture.brain.snapshot().state());
                assertFalse(fixture.events.stream().anyMatch(
                        BrainEvent.Speech.class::isInstance
                ));
                assertTrue(fixture.hasNotice(
                        "inactive_planner_speech_suppressed"
                ));

                fixture.clock.set(99);
                fixture.brain.tick();
                assertEquals(1, fixture.gateway.requestCount());
                fixture.clock.set(100);
                fixture.brain.tick();
                assertEquals(2, fixture.gateway.requestCount());
            }
        }
    }

    @Test
    void repeatedNoActionReplansExponentiallyBackOff() {
        try (Fixture fixture = new Fixture()) {
            fixture.inputFactory = (
                    requestId,
                    goal,
                    observation
            ) -> new PlannerInput(
                    new DecisionContext(
                            requestId,
                            observation.epoch(),
                            goal.revision(),
                            false,
                            fixture.registry
                                    .modelArgumentValidators()
                    ),
                    "system",
                    observation.trustedRuntimeJson(),
                    128
            );
            fixture.rebuildBrain();
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.REPLAN,
                    "",
                    "",
                    List.of()
            ));
            fixture.brain.tick();
            assertTrue(fixture.hasNotice(
                    "planner_no_action_backoff"
            ));

            fixture.clock.set(100);
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
            assertEquals(
                    "planner_no_action",
                    JsonParser.parseString(
                            fixture.gateway.lastInput()
                                    .observationJson()
                    ).getAsJsonObject().get(
                            "lastModelDecisionFailureCode"
                    ).getAsString()
            );
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.REPLAN,
                    "",
                    "",
                    List.of()
            ));
            fixture.brain.tick();

            fixture.clock.set(299);
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
            fixture.clock.set(300);
            fixture.brain.tick();
            assertEquals(3, fixture.gateway.requestCount());
        }
    }

    @Test
    void repeatedNoActionWaitsForPlayerInsteadOfRetryingForever() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.goals.setGoal(
                    "collect wood",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            for (int attempt = 0; attempt < 4; attempt++) {
                fixture.brain.tick();
                fixture.gateway.completeCurrent(success(
                        fixture.gateway.lastInput(),
                        DecisionKind.REPLAN,
                        "",
                        "I will do it",
                        List.of()
                ));
                fixture.brain.tick();
                fixture.clock.addAndGet(10_000L);
            }

            assertEquals(GoalStatus.RUNNING, fixture.goals.snapshot().status());
            assertTrue(fixture.brain.snapshot().waitingForPlayer());
            assertEquals(
                    BrainOrchestrator.State.WAITING_FOR_PLAYER,
                    fixture.brain.snapshot().state()
            );
            assertEquals(4, fixture.gateway.requestCount());
            assertTrue(fixture.hasNotice(
                    "planner_no_action_waiting_for_player"
            ));

            fixture.brain.tick();
            assertEquals(
                    4,
                    fixture.gateway.requestCount(),
                    "waiting state must not keep spending model requests"
            );

            fixture.brain.prioritizePlayerConversation();
            assertFalse(fixture.brain.snapshot().waitingForPlayer());
            fixture.brain.tick();
            assertEquals(
                    5,
                    fixture.gateway.requestCount(),
                    "a new real player message must wake the bounded planner"
            );
        }
    }

    @Test
    void repeatedNoActionSafelyEndsLockedEvaluationInsteadOfWaiting() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.goals.startHardcoreEvaluation(
                    GoalCoordinator.HARDCORE_INITIAL_GOAL
            ).accepted());

            for (int attempt = 0; attempt < 4; attempt++) {
                fixture.brain.tick();
                fixture.gateway.completeCurrent(success(
                        fixture.gateway.lastInput(),
                        DecisionKind.CONTINUE,
                        "",
                        "",
                        List.of()
                ));
                fixture.brain.tick();
                fixture.clock.addAndGet(10_000L);
            }

            assertEquals(
                    GoalStatus.SAFE_IDLE,
                    fixture.goals.snapshot().status()
            );
            assertEquals(
                    "planner_no_action_exhausted",
                    fixture.goals.snapshot().detailCode()
            );
            assertFalse(fixture.brain.snapshot().waitingForPlayer());
            assertTrue(fixture.hasNotice("planner_no_action_exhausted"));
        }
    }

    @Test
    void recoversVisibleBoundFollowWhenProviderReturnsNoAction() {
        try (Fixture fixture = new Fixture()) {
            fixture.observations.semanticJson = """
                    {
                      "sampleSequence": 50,
                      "self": {"dimension": "minecraft:overworld"},
                      "visibleEntities": [
                        {
                          "type": "minecraft:player",
                          "hostile": false,
                          "properties": {"playerName": "alex"}
                        }
                      ]
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "跟我走;serverBoundPlayerName=alex;"
                        + "serverBoundPlayerUuid=00000000-0000-0000-0000-000000000001",
                    GoalSource.MCP
            ).accepted());

            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.REPLAN,
                    "",
                    "我马上过来。",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(1, fixture.followSkill.startCalls);
            assertEquals(
                    BrainOrchestrator.State.EXECUTING_SKILL,
                    fixture.brain.snapshot().state()
            );
            assertTrue(fixture.hasNotice(
                    "follow_action_recovered_from_no_action"
            ));
            assertTrue(fixture.events.stream()
                    .filter(BrainEvent.ModelAudit.class::isInstance)
                    .map(BrainEvent.ModelAudit.class::cast)
                    .anyMatch(audit ->
                            audit.stage()
                                    == BrainEvent.ModelAuditStage.SKILL_STARTED
                                    && audit.skillName().equals(
                                        "follow_entity"
                                    )));
            assertFalse(fixture.events.stream().anyMatch(
                    event -> event instanceof BrainEvent.Speech
                            && ((BrainEvent.Speech) event).message()
                                .contains("马上过来")
            ));
        }
    }

    @Test
    void immediatelyStartsVisiblePlayerBoundFollowWithoutWaitingForModel() {
        try (Fixture fixture = new Fixture()) {
            fixture.observations.semanticJson = """
                    {
                      "sampleSequence": 52,
                      "self": {"dimension": "minecraft:overworld"},
                      "visibleEntities": [
                        {
                          "type": "minecraft:player",
                          "hostile": false,
                          "properties": {"playerName": "alex"}
                        }
                      ]
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "跟随发出请求的玩家；serverBoundPlayerName=alex;"
                        + "serverBoundPlayerUuid="
                        + "00000000-0000-0000-0000-000000000001",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            fixture.brain.tick();

            assertEquals(
                    1,
                    fixture.followSkill.startCalls,
                    "an explicit visible follow command must not wait for "
                        + "a model round trip"
            );
            assertEquals(0, fixture.gateway.requestCount());
            assertEquals(
                    BrainOrchestrator.State.EXECUTING_SKILL,
                    fixture.brain.snapshot().state()
            );
            assertTrue(fixture.hasNotice("immediate_player_follow_started"));
            assertTrue(fixture.hasNotice("direct_player_skill_started"));
            assertFalse(fixture.events.stream().anyMatch(
                    BrainEvent.ModelAudit.class::isInstance
            ), "a direct player command must not fabricate model audit data");
        }
    }

    @Test
    void verifiedModelOwnsBoundFollowDecisionAndProducesPlannerRequest() {
        try (Fixture fixture = new Fixture()) {
            fixture.gateway.configured = true;
            fixture.observations.semanticJson = """
                    {
                      "sampleSequence": 54,
                      "self": {"dimension": "minecraft:overworld"},
                      "visibleEntities": [
                        {
                          "type": "minecraft:player",
                          "hostile": false,
                          "properties": {"playerName": "alex"}
                        }
                      ]
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "跟随发出请求的玩家；serverBoundPlayerName=alex;"
                        + "serverBoundPlayerUuid="
                        + "00000000-0000-0000-0000-000000000001",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            fixture.brain.tick();

            assertEquals(
                    1,
                    fixture.gateway.requestCount(),
                    "a verified provider must receive the follow decision"
            );
            assertEquals(
                    0,
                    fixture.followSkill.startCalls,
                    "a model response is required before the body moves"
            );
            assertFalse(fixture.hasNotice("immediate_player_follow_started"));

            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.START_SKILL,
                    "follow_entity",
                    "我跟上了。",
                    List.of(
                            new SkillArgument(
                                    "observationId",
                                    "sample-54"
                            ),
                            new SkillArgument(
                                    "sampleSequence",
                                    "54"
                            ),
                            new SkillArgument(
                                    "followDistance",
                                    "3.0"
                            ),
                            new SkillArgument(
                                    "lostGraceTicks",
                                    "80"
                            )
                    )
            ));
            fixture.brain.tick();

            assertEquals(1, fixture.followSkill.startCalls);
            assertTrue(fixture.hasNotice("skill_started.follow_entity"));
        }
    }

    @Test
    void directlySearchesForAnOutOfViewPlayerOnlyOnceThenUsesPlanner() {
        try (Fixture fixture = new Fixture()) {
            fixture.observations.semanticJson = """
                    {
                      "sampleSequence": 53,
                      "self": {"dimension": "minecraft:overworld"},
                      "visibleEntities": []
                    }
                    """;
            fixture.surveySkill.steps.add(SkillTickResult.completed());
            assertTrue(fixture.goals.setGoal(
                    "跟随发出请求的玩家；serverBoundPlayerName=alex;"
                        + "serverBoundPlayerUuid="
                        + "00000000-0000-0000-0000-000000000001",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            fixture.brain.tick();
            assertEquals(1, fixture.surveySkill.startCalls);
            assertEquals(0, fixture.gateway.requestCount());
            assertTrue(fixture.hasNotice(
                    "immediate_player_follow_search_started"
            ));

            fixture.brain.tick();
            fixture.clock.addAndGet(1_000L);
            fixture.brain.tick();

            assertEquals(
                    1,
                    fixture.surveySkill.startCalls,
                    "a hidden target must not create an unbounded direct "
                        + "scan loop"
            );
            assertEquals(
                    1,
                    fixture.gateway.requestCount(),
                    "after one fair first-person search the regular planner "
                        + "owns the next step"
            );
        }
    }

    @Test
    void recoversBoundFollowWithAFirstPersonSearchWhenTargetIsOutOfView() {
        BrainObservation observation = new BrainObservation(
                50,
                new SkillContext(2, 50, 1, false, true, 0.0),
                """
                        {
                          "sampleSequence": 50,
                          "self": {"dimension": "minecraft:overworld"},
                          "visibleEntities": []
                        }
                        """
        );
        DecisionEnvelope noAction = new DecisionEnvelope(
                "request-search",
                50,
                2,
                DecisionKind.CONTINUE,
                "",
                List.of(),
                RequestedObservation.none(),
                "好的，我这就来。",
                0.5
        );

        Optional<DecisionEnvelope> recovered =
                BrainOrchestrator.recoverBoundFollowDecision(
                        new GoalSnapshot(
                                Optional.empty(),
                                2,
                                GoalStatus.RUNNING,
                                GoalSource.PLAYER_CHAT,
                                "跟随发出请求的玩家；"
                                        + "serverBoundPlayerName=alex;"
                                        + "serverBoundPlayerUuid=x",
                                "",
                                Instant.EPOCH,
                                false
                        ),
                        observation,
                        noAction
                );

        assertTrue(recovered.isPresent());
        assertEquals(
                "survey_surroundings",
                recovered.orElseThrow().skillName()
        );
        assertEquals(
                "minecraft:overworld",
                recovered.orElseThrow().typedArguments().stream()
                        .filter(argument -> argument.name().equals("dimension"))
                        .findFirst()
                        .orElseThrow()
                        .value()
        );
        assertEquals(
                "4",
                recovered.orElseThrow().typedArguments().stream()
                        .filter(argument -> argument.name().equals(
                                "horizontalSteps"
                        ))
                        .findFirst()
                        .orElseThrow()
                        .value(),
                "bound follow reacquire uses the bounded low-latency sweep"
        );
        assertEquals(
                "12",
                recovered.orElseThrow().typedArguments().stream()
                        .filter(argument -> argument.name().equals(
                                "observationWaitTicks"
                        ))
                        .findFirst()
                        .orElseThrow()
                        .value(),
                "bound follow reacquire must not wait like a terrain survey"
        );
    }

    @Test
    void followsPlayerNamesWithoutChangingTheirCase() {
        BrainObservation observation = new BrainObservation(
                50,
                new SkillContext(2, 50, 1, false, true, 0.0),
                """
                        {
                          "sampleSequence": 50,
                          "self": {"dimension": "minecraft:overworld"},
                          "visibleEntities": [
                            {
                              "type": "minecraft:player",
                              "hostile": false,
                              "properties": {"playerName": "Alex"}
                            }
                          ]
                        }
                        """
        );
        DecisionEnvelope noAction = new DecisionEnvelope(
                "request-case",
                50,
                2,
                DecisionKind.REPLAN,
                "",
                List.of(),
                RequestedObservation.none(),
                "",
                0.5
        );

        Optional<DecisionEnvelope> recovered =
                BrainOrchestrator.recoverBoundFollowDecision(
                        new GoalSnapshot(
                                Optional.empty(),
                                2,
                                GoalStatus.RUNNING,
                                GoalSource.PLAYER_CHAT,
                                "跟随发出请求的玩家；"
                                        + "serverBoundPlayerName=alex;"
                                        + "serverBoundPlayerUuid=x",
                                "",
                                Instant.EPOCH,
                                false
                        ),
                        observation,
                        noAction
                );

        assertEquals(
                "follow_entity",
                recovered.orElseThrow().skillName()
        );
    }

    @Test
    void recoversVisibleBoundFollowWhenProviderAsksForClarification() {
        try (Fixture fixture = new Fixture()) {
            fixture.observations.semanticJson = """
                    {
                      "sampleSequence": 51,
                      "self": {"dimension": "minecraft:overworld"},
                      "visibleEntities": [
                        {
                          "type": "minecraft:player",
                          "hostile": false,
                          "properties": {"playerName": "alex"}
                        }
                      ]
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "跟我走;serverBoundPlayerName=alex;"
                        + "serverBoundPlayerUuid=00000000-0000-0000-0000-000000000001",
                    GoalSource.MCP
            ).accepted());

            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.ASK_PLAYER,
                    "",
                    "你要我跟谁走？",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(1, fixture.followSkill.startCalls);
            assertEquals(
                    BrainOrchestrator.State.EXECUTING_SKILL,
                    fixture.brain.snapshot().state()
            );
            assertFalse(fixture.brain.snapshot().waitingForPlayer());
            assertTrue(fixture.hasNotice(
                    "follow_action_recovered_from_ask_player"
            ));
            assertFalse(fixture.events.stream().anyMatch(
                    event -> event instanceof BrainEvent.Speech
                            && ((BrainEvent.Speech) event).message()
                                .contains("跟谁")
            ));
        }
    }

    @Test
    void followRecoveryRequiresTheAuthoritativeBoundPlayer() {
        BrainObservation observation = new BrainObservation(
                50,
                new SkillContext(2, 50, 1, false, true, 0.0),
                """
                        {
                          "sampleSequence": 50,
                          "visibleEntities": [
                            {
                              "type": "minecraft:player",
                              "hostile": false,
                              "properties": {"playerName": "steve"}
                            }
                          ]
                        }
                        """
        );
        DecisionEnvelope noAction = new DecisionEnvelope(
                "request-1",
                50,
                2,
                DecisionKind.CONTINUE,
                "",
                List.of(),
                RequestedObservation.none(),
                "",
                0.5
        );

        assertTrue(BrainOrchestrator.recoverBoundFollowDecision(
                new GoalSnapshot(
                        Optional.empty(),
                        2,
                        GoalStatus.RUNNING,
                        GoalSource.PLAYER_CHAT,
                        "跟我走;serverBoundPlayerName=alex;serverBoundPlayerUuid=x",
                        "",
                        Instant.EPOCH,
                        false
                ),
                observation,
                noAction
        ).isEmpty());
    }

    @Test
    void completedAtomicSkillTriggersTheNextModelDecisionAfterBackoff() {
        try (Fixture fixture = new Fixture()) {
            fixture.skill.defaultStep = SkillTickResult.completed();
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.START_SKILL,
                    "test",
                    "",
                    List.of()
            ));
            fixture.brain.tick();

            fixture.brain.tick();
            assertEquals(1, fixture.gateway.requestCount());
            assertTrue(fixture.hasNotice("skill_completed"));

            fixture.clock.set(99);
            fixture.brain.tick();
            assertEquals(1, fixture.gateway.requestCount());
            fixture.clock.set(100);
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
            assertEquals(GoalStatus.RUNNING, fixture.goals.snapshot().status());
        }
    }

    @Test
    void failedAtomicSkillTriggersReplanningWithoutChangingGoalRevision() {
        try (Fixture fixture = new Fixture()) {
            fixture.skill.defaultStep =
                    SkillTickResult.failed("locally_blocked");
            fixture.startGoal();
            final long revision = fixture.goals.snapshot().revision();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.START_SKILL,
                    "test",
                    "",
                    List.of()
            ));
            fixture.brain.tick();

            fixture.brain.tick();
            assertTrue(fixture.hasNotice("skill_failed"));
            assertEquals(revision, fixture.goals.snapshot().revision());

            fixture.clock.set(100);
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
            assertEquals(revision, fixture.goals.snapshot().revision());
        }
    }

    @Test
    void repeatedIdenticalTerminalSkillFailureStopsProviderSpam() {
        try (Fixture fixture = new Fixture()) {
            fixture.skill.defaultStep =
                    SkillTickResult.failed("physically_blocked");
            fixture.startGoal();
            fixture.brain.tick();

            for (int attempt = 1; attempt <= 3; attempt++) {
                fixture.gateway.completeCurrent(success(
                        fixture.gateway.lastInput(),
                        DecisionKind.START_SKILL,
                        "test",
                        "",
                        List.of()
                ));
                fixture.brain.tick();
                fixture.brain.tick();
                if (attempt < 3) {
                    assertEquals(
                            GoalStatus.RUNNING,
                            fixture.goals.snapshot().status()
                    );
                    fixture.clock.addAndGet(100);
                    fixture.brain.tick();
                }
            }

            assertEquals(
                    GoalStatus.SAFE_IDLE,
                    fixture.goals.snapshot().status()
            );
            assertEquals(
                    "repeated_skill_failure_without_progress",
                    fixture.goals.snapshot().detailCode()
            );
            assertEquals(3, fixture.gateway.requestCount());
            assertTrue(fixture.hasNotice(
                    "repeated_identical_skill_failure"
            ));
        }
    }

    @Test
    void routesASemanticRefreshRequestWithoutInventingAnObservation() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.REPLAN,
                    "",
                    "",
                    List.of(),
                    new RequestedObservation(
                            ObservationKind.SEMANTIC_REFRESH,
                            "Need a fresh first-person ray sample"
                    )
            ));

            fixture.brain.tick();

            assertEquals(1, fixture.observations.requestCount);
            assertEquals(
                    ObservationKind.SEMANTIC_REFRESH,
                    fixture.observations.lastRequest.kind()
            );
            assertTrue(
                    fixture.hasNotice("observation_request_accepted")
            );
            assertEquals(GoalStatus.RUNNING, fixture.goals.snapshot().status());
        }
    }

    @Test
    void safeIdleTerminatesTheGoalAndPublishesSpeechAsData() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(
                    fixture.goals.setGoal(
                            "survive",
                            GoalSource.RECOVERY
                    ).accepted()
            );
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.SAFE_IDLE,
                    "",
                    "基地目前安全，我先停在这里。",
                    List.of()
            ));

            assertEquals(GoalStatus.RUNNING, fixture.goals.snapshot().status());
            assertTrue(fixture.events.stream().noneMatch(
                BrainEvent.Speech.class::isInstance
            ));
            fixture.brain.tick();

            assertEquals(GoalStatus.SAFE_IDLE, fixture.goals.snapshot().status());
            BrainEvent.Speech speech = assertInstanceOf(
                    BrainEvent.Speech.class,
                    fixture.events.stream()
                            .filter(BrainEvent.Speech.class::isInstance)
                            .findFirst()
                            .orElseThrow()
            );
            assertEquals("基地目前安全，我先停在这里。", speech.message());
        }
    }

    @Test
    void safeIdleCannotSilentlyEndAnActivePlayerChatGoal() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(
                    fixture.goals.setGoal(
                            "follow me",
                            GoalSource.PLAYER_CHAT
                    ).accepted()
            );
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.SAFE_IDLE,
                    "",
                    "好的，我先在这里。",
                    List.of()
            ));

            fixture.brain.tick();

            assertEquals(
                    GoalStatus.RUNNING,
                    fixture.goals.snapshot().status()
            );
            assertTrue(fixture.hasNotice(
                    "model_safe_idle_rejected_for_active_goal"
            ));
            assertTrue(fixture.events.stream().noneMatch(
                    BrainEvent.Speech.class::isInstance
            ));
        }
    }

    @Test
    void safeIdleFromVerifiedModelStillStartsBoundFollow() {
        try (Fixture fixture = new Fixture()) {
            fixture.gateway.configured = true;
            fixture.observations.semanticJson = """
                    {
                      "sampleSequence": 55,
                      "self": {"dimension": "minecraft:overworld"},
                      "visibleEntities": [
                        {
                          "type": "minecraft:player",
                          "hostile": false,
                          "properties": {"playerName": "alex"}
                        }
                      ]
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "跟随发出请求的玩家；serverBoundPlayerName=alex;"
                        + "serverBoundPlayerUuid="
                        + "00000000-0000-0000-0000-000000000001",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.SAFE_IDLE,
                    "",
                    "好的，我先停在这里。",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(1, fixture.followSkill.startCalls);
            assertEquals(
                    BrainOrchestrator.State.EXECUTING_SKILL,
                    fixture.brain.snapshot().state()
            );
            assertTrue(fixture.hasNotice(
                    "follow_action_recovered_from_safe_idle"
            ));
            assertTrue(fixture.events.stream()
                    .filter(BrainEvent.ModelAudit.class::isInstance)
                    .map(BrainEvent.ModelAudit.class::cast)
                    .anyMatch(audit ->
                            audit.stage()
                                    == BrainEvent.ModelAuditStage.SKILL_STARTED
                                    && audit.skillName().equals(
                                        "follow_entity"
                                    )));
            assertTrue(fixture.events.stream().noneMatch(
                    event -> event instanceof BrainEvent.Speech
                            && ((BrainEvent.Speech) event).message()
                                .contains("停在这里")
            ));
        }
    }

    @Test
    void completeGoalTerminatesOrdinaryGoalButCannotSelfCertifyEvaluation() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.COMPLETE_GOAL,
                    "",
                    "任务已经按要求完成。",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(GoalStatus.COMPLETED, fixture.goals.snapshot().status());
            assertEquals(
                    "server_verified_complete",
                    fixture.goals.snapshot().detailCode()
            );
        }

        try (Fixture fixture = new Fixture()) {
            fixture.goals.startHardcoreEvaluation("通关 Minecraft");
            fixture.observations.hardcore = true;
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.COMPLETE_GOAL,
                    "",
                    "我已经通关。",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(GoalStatus.RUNNING, fixture.goals.snapshot().status());
            assertTrue(fixture.hasNotice("evaluation_completion_unverified"));
        }
    }

    @Test
    void playerTaskCannotCompleteBeforeAnySkillStarts() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.goals.setGoal(
                    "去砍树",
                    GoalSource.PLAYER_CHAT
            ).accepted());
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.COMPLETE_GOAL,
                    "",
                    "任务完成了。",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(
                    GoalStatus.RUNNING,
                    fixture.goals.snapshot().status()
            );
            assertTrue(fixture.hasNotice(
                    "model_completion_without_action"
            ));
            assertTrue(fixture.events.stream().noneMatch(
                    BrainEvent.Speech.class::isInstance
            ));
        }
    }

    @Test
    void prematureCompletionOfVisibleBoundFollowStartsTheFairFollowSkill() {
        try (Fixture fixture = new Fixture()) {
            /* Exercise the configured-model path, not the offline direct
             * follow fallback used when no provider is configured. */
            fixture.gateway.configured = true;
            fixture.observations.semanticJson = """
                    {
                      "sampleSequence": 51,
                      "self": {"dimension": "minecraft:overworld"},
                      "visibleEntities": [
                        {
                          "type": "minecraft:player",
                          "hostile": false,
                          "properties": {"playerName": "alex"}
                        }
                      ]
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "跟我走;serverBoundPlayerName=alex;"
                        + "serverBoundPlayerUuid="
                        + "00000000-0000-0000-0000-000000000001",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.COMPLETE_GOAL,
                    "",
                    "已经跟上你了。",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(1, fixture.followSkill.startCalls);
            assertEquals(
                    BrainOrchestrator.State.EXECUTING_SKILL,
                    fixture.brain.snapshot().state()
            );
            assertEquals(GoalStatus.RUNNING, fixture.goals.snapshot().status());
            assertTrue(fixture.hasNotice(
                    "follow_action_recovered_from_premature_completion"
            ));
            assertTrue(fixture.events.stream()
                    .filter(BrainEvent.ModelAudit.class::isInstance)
                    .map(BrainEvent.ModelAudit.class::cast)
                    .anyMatch(audit -> audit.stage()
                            == BrainEvent.ModelAuditStage.SKILL_STARTED
                            && audit.skillName().equals("follow_entity")));
            assertTrue(fixture.events.stream().noneMatch(
                    event -> event instanceof BrainEvent.Speech
                            && ((BrainEvent.Speech) event).message()
                                .contains("已经跟上")
            ));
        }
    }

    @Test
    void explicitGoldenAppleTaskCannotCompleteAfterPickupBeforeConsumption() {
        try (Fixture fixture = new Fixture()) {
            fixture.collectSkill.defaultStep = SkillTickResult.completed();
            assertTrue(fixture.goals.setGoal(
                    "我把金苹果给你了，快吃吧",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.START_SKILL,
                    "collect_observed_item",
                    "",
                    List.of()
            ));
            fixture.brain.tick();
            fixture.brain.tick();

            fixture.clock.set(100);
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.COMPLETE_GOAL,
                    "",
                    "已经完成。",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(
                    GoalStatus.RUNNING,
                    fixture.goals.snapshot().status()
            );
            assertTrue(fixture.hasNotice(
                    "model_completion_before_food_consumption"
            ));

            fixture.clock.set(300);
            fixture.brain.tick();
            fixture.consumeSkill.defaultStep = SkillTickResult.completed();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.START_SKILL,
                    "consume_owned_food",
                    "",
                    List.of()
            ));
            fixture.brain.tick();
            fixture.brain.tick();

            fixture.clock.set(400);
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.COMPLETE_GOAL,
                    "",
                    "现在已经实际吃完。",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(
                    GoalStatus.COMPLETED,
                    fixture.goals.snapshot().status()
            );
        }
    }

    @Test
    void recoversOwnedGoldenAppleConsumptionAfterSpeechOnlyDecision() {
        try (Fixture fixture = new Fixture()) {
            fixture.observations.semanticJson = """
                    {
                      "sampleSequence": 77,
                      "self": {
                        "dimension": "minecraft:overworld",
                        "inventory": [
                          {
                            "itemId": "minecraft:golden_apple",
                            "count": 1
                          }
                        ]
                      }
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "我把金苹果给你了，快吃吧",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.REPLAN,
                    "",
                    "我先留着，之后再说。",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(1, fixture.consumeSkill.startCalls);
            assertEquals(
                    BrainOrchestrator.State.EXECUTING_SKILL,
                    fixture.brain.snapshot().state()
            );
            assertTrue(fixture.hasNotice(
                    "food_consumption_recovered_from_no_action"
            ));
            assertTrue(fixture.events.stream()
                    .filter(BrainEvent.ModelAudit.class::isInstance)
                    .map(BrainEvent.ModelAudit.class::cast)
                    .anyMatch(audit -> audit.stage()
                            == BrainEvent.ModelAuditStage.SKILL_STARTED
                            && audit.skillName().equals(
                                    "consume_owned_food"
                            )));
        }
    }

    @Test
    void recoversVisibleGoldenApplePickupAfterAskPlayerDecision() {
        try (Fixture fixture = new Fixture()) {
            fixture.observations.semanticJson = """
                    {
                      "sampleSequence": 78,
                      "self": {
                        "dimension": "minecraft:overworld",
                        "inventory": []
                      },
                      "visibleEntities": [
                        {
                          "observationId": "visible-2",
                          "type": "minecraft:item",
                          "properties": {
                            "itemId": "minecraft:golden_apple"
                          }
                        }
                      ]
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "我把金苹果丢给你了，快吃吧",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.ASK_PLAYER,
                    "",
                    "你要我现在吃吗？",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(1, fixture.collectSkill.startCalls);
            assertEquals(
                    BrainOrchestrator.State.EXECUTING_SKILL,
                    fixture.brain.snapshot().state()
            );
            assertTrue(fixture.hasNotice(
                    "food_pickup_recovered_from_ask_player"
            ));
        }
    }

    @Test
    void recoversOrdinaryVisibleItemPickupAfterReplanDecision() {
        try (Fixture fixture = new Fixture()) {
            fixture.observations.semanticJson = """
                    {
                      "sampleSequence": 81,
                      "visibleEntities": [{
                        "observationId": "visible-log",
                        "type": "minecraft:item",
                        "properties": {
                          "itemId": "minecraft:oak_log"
                        }
                      }]
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "把你面前掉落的橡木原木捡进背包",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.REPLAN,
                    "",
                    "我正在重新规划。",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(1, fixture.collectSkill.startCalls);
            assertEquals(
                    BrainOrchestrator.State.EXECUTING_SKILL,
                    fixture.brain.snapshot().state()
            );
            assertTrue(fixture.hasNotice(
                    "item_pickup_recovered_from_no_action"
            ));
        }
    }

    @Test
    void recoversOrdinaryVisibleItemPickupAfterMalformedModelArguments() {
        try (Fixture fixture = new Fixture()) {
            fixture.observations.semanticJson = """
                    {
                      "sampleSequence": 82,
                      "visibleEntities": [{
                        "observationId": "visible-log",
                        "type": "minecraft:item",
                        "properties": {
                          "itemId": "minecraft:oak_log"
                        }
                      }]
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "把你面前掉落的橡木原木捡进背包",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            fixture.brain.tick();
            fixture.gateway.completeCurrent(failure(
                    ModelFailureKind.MALFORMED_RESPONSE,
                    Optional.empty()
            ));
            fixture.brain.tick();

            assertEquals(1, fixture.collectSkill.startCalls);
            assertEquals(
                    BrainOrchestrator.State.EXECUTING_SKILL,
                    fixture.brain.snapshot().state()
            );
            assertTrue(fixture.hasNotice(
                    "item_pickup_recovered_from_malformed_response"
            ));
        }
    }

    @Test
    void performsOneBoundedSurveyWhenPickupTargetIsTemporarilyOutOfView() {
        try (Fixture fixture = new Fixture()) {
            fixture.observations.semanticJson = """
                    {
                      "sampleSequence": 83,
                      "self": {
                        "dimension": "minecraft:overworld"
                      },
                      "visibleEntities": []
                    }
                    """;
            assertTrue(fixture.goals.setGoal(
                    "把你面前掉落的橡木原木捡进背包",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.REPLAN,
                    "",
                    "我再看看周围。",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(1, fixture.surveySkill.startCalls);
            assertTrue(fixture.hasNotice(
                    "item_search_recovered_from_no_action"
            ));
        }
    }

    @Test
    void serverCompletionGuardRejectsAnUnverifiedModelClaim() {
        try (Fixture fixture = new Fixture()) {
            fixture.rebuildBrain(goal ->
                    GoalCompletionVerification.rejected(
                            "foundation_route_unverified"
                    )
            );
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.COMPLETE_GOAL,
                    "",
                    "我已经完成。",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(
                    GoalStatus.RUNNING,
                    fixture.goals.snapshot().status()
            );
            assertTrue(fixture.hasNotice(
                    "foundation_route_unverified"
            ));
            assertFalse(fixture.events.stream().anyMatch(
                    BrainEvent.Speech.class::isInstance
            ));
        }
    }

    @Test
    void fullyVerifiedRouteCompletesWithoutAnotherModelRequest() {
        try (Fixture fixture = new Fixture()) {
            fixture.rebuildBrain(new GoalCompletionVerifier() {
                @Override
                public GoalCompletionVerification verify(
                        final GoalSnapshot goal
                ) {
                    return GoalCompletionVerification.approved();
                }

                @Override
                public Optional<GoalCompletionVerification>
                        verifyAutonomousCompletion(
                                final GoalSnapshot goal
                        ) {
                    return Optional.of(
                            GoalCompletionVerification.approved()
                    );
                }
            });
            fixture.startGoal();

            fixture.brain.tick();
            fixture.brain.tick();

            assertEquals(
                    GoalStatus.COMPLETED,
                    fixture.goals.snapshot().status()
            );
            assertEquals(
                    "server_verified_complete",
                    fixture.goals.snapshot().detailCode()
            );
            assertEquals(0, fixture.gateway.requestCount());
            assertTrue(fixture.hasNotice(
                    "server_verified_auto_complete"
            ));
        }
    }

    @Test
    void incompleteOrOrdinaryGoalStillRequiresAPlannerDecision() {
        try (Fixture fixture = new Fixture()) {
            fixture.rebuildBrain(new GoalCompletionVerifier() {
                @Override
                public GoalCompletionVerification verify(
                        final GoalSnapshot goal
                ) {
                    return GoalCompletionVerification.rejected(
                            "foundation_route_unverified"
                    );
                }

                @Override
                public Optional<GoalCompletionVerification>
                        verifyAutonomousCompletion(
                                final GoalSnapshot goal
                        ) {
                    return Optional.of(verify(goal));
                }
            });
            fixture.startGoal();

            fixture.brain.tick();

            assertEquals(
                    GoalStatus.RUNNING,
                    fixture.goals.snapshot().status()
            );
            assertEquals(1, fixture.gateway.requestCount());
        }

        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();

            fixture.brain.tick();

            assertEquals(
                    GoalStatus.RUNNING,
                    fixture.goals.snapshot().status()
            );
            assertEquals(1, fixture.gateway.requestCount());
        }
    }

    @Test
    void lockedEvaluationCannotUseAutonomousRouteCompletion() {
        try (Fixture fixture = new Fixture()) {
            fixture.rebuildBrain(new GoalCompletionVerifier() {
                @Override
                public GoalCompletionVerification verify(
                        final GoalSnapshot goal
                ) {
                    return GoalCompletionVerification.approved();
                }

                @Override
                public Optional<GoalCompletionVerification>
                        verifyAutonomousCompletion(
                                final GoalSnapshot goal
                        ) {
                    return Optional.of(
                            GoalCompletionVerification.approved()
                    );
                }
            });
            fixture.goals.startHardcoreEvaluation(
                    "通关 Minecraft"
            );

            fixture.brain.tick();

            assertEquals(
                    GoalStatus.RUNNING,
                    fixture.goals.snapshot().status()
            );
            assertEquals(1, fixture.gateway.requestCount());
            assertFalse(fixture.hasNotice(
                    "server_verified_auto_complete"
            ));
        }
    }

    @Test
    void lockedHardcoreAskPlayerCannotWaitForIntervention() {
        try (Fixture fixture = new Fixture()) {
            fixture.goals.startHardcoreEvaluation("通关 Minecraft");
            fixture.observations.hardcore = true;
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.ASK_PLAYER,
                    "",
                    "请告诉我坐标。",
                    List.of()
            ));

            fixture.brain.tick();

            assertEquals(GoalStatus.SAFE_IDLE, fixture.goals.snapshot().status());
            assertEquals(
                    "evaluation_requires_input",
                    fixture.goals.snapshot().detailCode()
            );
            assertFalse(fixture.brain.snapshot().waitingForPlayer());
            assertTrue(fixture.events.stream().noneMatch(BrainEvent.Speech.class::isInstance));
        }
    }

    @Test
    void ordinaryAskPlayerEmitsSpeechAndStopsFurtherRequests() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.ASK_PLAYER,
                    "",
                    "农田是东边还是西边的那块？",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(BrainOrchestrator.State.WAITING_FOR_PLAYER,
                    fixture.brain.snapshot().state());
            assertEquals(GoalStatus.RUNNING, fixture.goals.snapshot().status());
            fixture.clock.set(10_000);
            fixture.brain.tick();
            assertEquals(1, fixture.gateway.requestCount());
            assertTrue(fixture.events.stream().anyMatch(BrainEvent.Speech.class::isInstance));
        }
    }

    @Test
    void playerTaskActionPromiseDoesNotLatchPlannerWaitingState() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.goals.setGoal(
                    "跟我走",
                    GoalSource.PLAYER_CHAT
            ).accepted());
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.ASK_PLAYER,
                    "",
                    "我这就来。",
                    List.of()
            ));

            fixture.brain.tick();

            assertEquals(
                    BrainOrchestrator.State.BACKOFF,
                    fixture.brain.snapshot().state()
            );
            assertFalse(fixture.brain.snapshot().waitingForPlayer());
            assertTrue(fixture.hasNotice(
                    "ask_player_action_commitment_replanned"
            ));
            assertTrue(fixture.events.stream().noneMatch(
                    BrainEvent.Speech.class::isInstance
            ));
        }
    }

    @Test
    void bareActionAcknowledgementDoesNotLatchPlannerWaitingState() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.goals.setGoal(
                    "帮我砍树",
                    GoalSource.PLAYER_CHAT
            ).accepted());
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.ASK_PLAYER,
                    "",
                    "好的",
                    List.of()
            ));

            fixture.brain.tick();

            assertEquals(
                    BrainOrchestrator.State.BACKOFF,
                    fixture.brain.snapshot().state()
            );
            assertFalse(fixture.brain.snapshot().waitingForPlayer());
            assertTrue(fixture.hasNotice(
                    "ask_player_action_commitment_replanned"
            ));
            assertTrue(fixture.events.stream().noneMatch(
                    BrainEvent.Speech.class::isInstance
            ));
        }
    }

    @Test
    void taskAcceptedStatusDoesNotLatchPlannerWaitingState() {
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.goals.setGoal(
                    "帮我砍树",
                    GoalSource.PLAYER_CHAT
            ).accepted());
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.ASK_PLAYER,
                    "",
                    "目标已接受。revision=2",
                    List.of()
            ));

            fixture.brain.tick();

            assertEquals(
                    BrainOrchestrator.State.BACKOFF,
                    fixture.brain.snapshot().state()
            );
            assertFalse(fixture.brain.snapshot().waitingForPlayer());
            assertTrue(fixture.hasNotice(
                    "ask_player_action_commitment_replanned"
            ));
            assertTrue(fixture.events.stream().noneMatch(
                    BrainEvent.Speech.class::isInstance
            ));
        }
    }

    @Test
    void playerConversationWakesPlannerAfterAskPlayer() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.ASK_PLAYER,
                    "",
                    "你要我继续吗？",
                    List.of()
            ));
            fixture.brain.tick();

            assertEquals(
                    BrainOrchestrator.State.WAITING_FOR_PLAYER,
                    fixture.brain.snapshot().state()
            );

            fixture.brain.prioritizePlayerConversation();

            assertFalse(fixture.brain.snapshot().waitingForPlayer());
            assertEquals(
                    BrainOrchestrator.State.READY,
                    fixture.brain.snapshot().state()
            );
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
        }
    }

    @Test
    void transientFailuresRetryButFatalFailuresStopSafely() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(failure(
                    ModelFailureKind.NETWORK_TRANSIENT,
                    Optional.empty()
            ));
            fixture.brain.tick();
            assertEquals(GoalStatus.RUNNING, fixture.goals.snapshot().status());
            assertEquals(1, fixture.brain.snapshot().consecutiveModelFailures());

            fixture.clock.set(800);
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
        }

        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(failure(
                    ModelFailureKind.AUTHENTICATION,
                    Optional.empty()
            ));
            fixture.brain.tick();

            assertEquals(GoalStatus.SAFE_IDLE, fixture.goals.snapshot().status());
            assertEquals("model_unavailable", fixture.goals.snapshot().detailCode());
            assertTrue(fixture.events.stream()
                    .filter(BrainEvent.Speech.class::isInstance)
                    .map(BrainEvent.Speech.class::cast)
                    .anyMatch(speech -> speech.message().contains("API Key")));
        }
    }

    @Test
    void networkOutageBackoffExpandsInsteadOfBurningTheBudgetPerTick() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(failure(
                    ModelFailureKind.NETWORK_TRANSIENT,
                    Optional.empty()
            ));
            fixture.brain.tick();

            fixture.clock.set(799);
            fixture.brain.tick();
            assertEquals(1, fixture.gateway.requestCount());
            assertEquals(
                    BrainOrchestrator.State.BACKOFF,
                    fixture.brain.snapshot().state()
            );

            fixture.clock.set(800);
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
            fixture.gateway.completeCurrent(failure(
                    ModelFailureKind.NETWORK_TRANSIENT,
                    Optional.empty()
            ));
            fixture.brain.tick();

            fixture.clock.set(2_399);
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
            assertEquals(
                    BrainOrchestrator.State.BACKOFF,
                    fixture.brain.snapshot().state()
            );

            fixture.clock.set(2_400);
            fixture.brain.tick();
            assertEquals(3, fixture.gateway.requestCount());
            assertEquals(
                    GoalStatus.RUNNING,
                    fixture.goals.snapshot().status()
            );
        }
    }

    @Test
    void oneContextLimitKeepsTheGoalAndRequestsAConciseRetry() {
        try (Fixture fixture = new Fixture()) {
            fixture.inputFactory = (
                    requestId,
                    goal,
                    observation
            ) -> new PlannerInput(
                    new DecisionContext(
                            requestId,
                            observation.epoch(),
                            goal.revision(),
                            false,
                            fixture.registry
                                    .modelArgumentValidators()
                    ),
                    "system",
                    observation.trustedRuntimeJson(),
                    128
            );
            fixture.rebuildBrain();
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(failure(
                    ModelFailureKind.CONTEXT_LIMIT,
                    Optional.empty()
            ));
            fixture.brain.tick();

            assertEquals(
                    GoalStatus.RUNNING,
                    fixture.goals.snapshot().status()
            );
            assertEquals(
                    1,
                    fixture.brain.snapshot()
                            .consecutiveModelFailures()
            );

            fixture.clock.set(100);
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
            assertEquals(
                    "context_limit",
                    JsonParser.parseString(
                            fixture.gateway.lastInput()
                                    .observationJson()
                    ).getAsJsonObject().get(
                            "lastModelDecisionFailureCode"
                    ).getAsString()
            );
        }
    }

    @Test
    void malformedResponsesEventuallyEnterSafeIdle() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();

            for (int attempt = 1; attempt <= 3; attempt++) {
                fixture.gateway.completeCurrent(failure(
                        ModelFailureKind.MALFORMED_RESPONSE,
                        Optional.empty()
                ));
                fixture.brain.tick();
                if (attempt < 3) {
                    assertEquals(GoalStatus.RUNNING, fixture.goals.snapshot().status());
                    fixture.clock.addAndGet(100);
                    fixture.brain.tick();
                }
            }

            assertEquals(GoalStatus.SAFE_IDLE, fixture.goals.snapshot().status());
            assertEquals(
                    "model_failures_exhausted",
                    fixture.goals.snapshot().detailCode()
            );
            assertEquals(3, fixture.gateway.requestCount());
        }
    }

    @Test
    void repeatedProviderOutagesPreserveTheInstalledGoal() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            final long[] retryAt = {800L, 2_400L};

            for (int attempt = 0; attempt < 3; attempt++) {
                fixture.gateway.completeCurrent(failure(
                        ModelFailureKind.SERVER_TRANSIENT,
                        Optional.empty()
                ));
                fixture.brain.tick();
                assertEquals(
                        GoalStatus.RUNNING,
                        fixture.goals.snapshot().status()
                );
                assertTrue(fixture.hasNotice(
                        "model_provider_outage_backoff"
                ));
                if (attempt < retryAt.length) {
                    fixture.clock.set(retryAt[attempt]);
                    fixture.brain.tick();
                }
            }

            assertEquals(3, fixture.gateway.requestCount());
            assertEquals(
                    BrainOrchestrator.State.BACKOFF,
                    fixture.brain.snapshot().state()
            );
        }
    }

    @Test
    void repeatedIdenticalRejectedSkillStopsWithoutProviderSpam() {
        try (Fixture fixture = new Fixture()) {
            fixture.skill.preconditionFailure =
                    SkillFailure.of("test.insufficient_materials");
            fixture.startGoal();
            fixture.brain.tick();

            for (int attempt = 1; attempt <= 3; attempt++) {
                fixture.gateway.completeCurrent(success(
                        fixture.gateway.lastInput(),
                        DecisionKind.START_SKILL,
                        "test",
                        "",
                        List.of()
                ));
                fixture.brain.tick();
                if (attempt < 3) {
                    assertEquals(
                            GoalStatus.RUNNING,
                            fixture.goals.snapshot().status()
                    );
                    fixture.clock.addAndGet(100);
                    fixture.brain.tick();
                }
            }

            assertEquals(
                    GoalStatus.SAFE_IDLE,
                    fixture.goals.snapshot().status()
            );
            assertEquals(
                    "repeated_skill_rejection_without_world_change",
                    fixture.goals.snapshot().detailCode()
            );
            assertEquals(3, fixture.gateway.requestCount());
            assertTrue(fixture.hasNotice(
                    "repeated_skill_start_rejection"
            ));
            assertEquals(
                    0,
                    fixture.skill.startCalls,
                    "Rejected preconditions must never start the skill"
            );
        }
    }

    @Test
    void repeatedRateLimitsBackOffWithoutDiscardingThePlayerGoal() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();

            for (int attempt = 1; attempt <= 4; attempt++) {
                fixture.gateway.completeCurrent(failure(
                        ModelFailureKind.RATE_LIMITED,
                        Optional.empty()
                ));
                fixture.brain.tick();
                assertEquals(
                        GoalStatus.RUNNING,
                        fixture.goals.snapshot().status()
                );
                assertTrue(fixture.hasNotice(
                        "model_rate_limit_backoff"
                ));

                final long expectedDelay = switch (attempt) {
                    case 1 -> 4_000L;
                    case 2 -> 8_000L;
                    default -> 16_000L;
                };
                fixture.clock.addAndGet(expectedDelay - 1L);
                fixture.brain.tick();
                assertEquals(
                        attempt,
                        fixture.gateway.requestCount()
                );
                fixture.clock.incrementAndGet();
                fixture.brain.tick();
                assertEquals(
                        attempt + 1,
                        fixture.gateway.requestCount()
                );
            }
        }
    }

    @Test
    void reportsSoftDeadlineOnceWithoutCancellingOrRetryingTheRequest() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();

            fixture.clock.set(500);
            fixture.brain.tick();

            assertEquals(1, fixture.gateway.requestCount());
            assertTrue(fixture.hasNotice("model_request_soft_deadline"));
            assertEquals(0, fixture.gateway.cancelRevisions.size());
            assertEquals(
                    1,
                    fixture.events.stream()
                            .filter(BrainEvent.Notice.class::isInstance)
                            .map(BrainEvent.Notice.class::cast)
                            .filter(notice -> notice.code().equals(
                                    "model_request_soft_deadline"
                            ))
                            .count()
            );

            fixture.clock.set(750);
            fixture.brain.tick();
            assertEquals(1, fixture.gateway.requestCount());
            assertEquals(0, fixture.gateway.cancelRevisions.size());
        }
    }

    @Test
    void requestTimeoutCancelsOnlyTheRequestAndRetriesTheGoal() {
        try (Fixture fixture = new Fixture()) {
            fixture.startGoal();
            fixture.brain.tick();
            long requestGoalRevision = fixture.goals.snapshot().revision();
            fixture.clock.set(1_000);

            fixture.brain.tick();

            assertEquals(GoalStatus.RUNNING, fixture.goals.snapshot().status());
            assertEquals(
                    requestGoalRevision,
                    fixture.goals.snapshot().revision()
            );
            assertEquals(
                    BrainOrchestrator.State.BACKOFF,
                    fixture.brain.snapshot().state()
            );
            assertEquals(1, fixture.gateway.cancelRevisions.size());
            assertFalse(fixture.gateway.cancelRevisions.contains(
                    requestGoalRevision
            ));
            assertTrue(fixture.hasNotice("model_request_timeout"));

            fixture.clock.set(1_799);
            fixture.brain.tick();
            assertEquals(1, fixture.gateway.requestCount());
            fixture.clock.set(1_800);
            fixture.brain.tick();
            assertEquals(2, fixture.gateway.requestCount());
        }
    }

    @Test
    void explicitWoodTaskUsesOnePhysicalHandoffAtSoftDeadlineAndCompletes() {
        try (Fixture fixture = new Fixture()) {
            fixture.gatherWoodSkill.defaultStep =
                    SkillTickResult.completed();
            assertTrue(fixture.goals.setGoal(
                    "请在附近砍一棵树，捡起木头",
                    GoalSource.PLAYER_CHAT
            ).accepted());

            fixture.brain.tick();
            assertEquals(1, fixture.gateway.requestCount());

            fixture.clock.set(500L);
            fixture.brain.tick();

            assertEquals(1, fixture.gateway.cancelRevisions.size());
            assertEquals(
                    "gather_nearby_wood",
                    fixture.skills.snapshot().skillName()
            );
            assertEquals(1, fixture.gatherWoodSkill.startCalls);
            assertTrue(fixture.hasNotice(
                    "soft_deadline_nearby_wood_handoff"
            ));

            fixture.brain.tick();

            assertEquals(
                    GoalStatus.COMPLETED,
                    fixture.goals.snapshot().status()
            );
            assertEquals(
                    "server_verified_nearby_wood_complete",
                    fixture.goals.snapshot().detailCode()
            );
            assertEquals(
                    1,
                    fixture.gateway.requestCount(),
                    "A verified one-action task must not request or start "
                            + "the same work again"
            );
        }
    }

    @Test
    void routedQuantifiedWoodTaskDoesNotEndAfterOneGatherCluster() {
        try (Fixture fixture = new Fixture()) {
            fixture.gatherWoodSkill.defaultStep =
                    SkillTickResult.completed();
            assertTrue(fixture.goals.setGoal(
                    "砍30个原木制作4个箱子并平均存放剩余原木",
                    GoalSource.PLAYER_CHAT,
                    GoalExecutionPlan.foundation(
                            GoalExecutionPlan.Target
                                    .LOG_STORAGE_DISTRIBUTED
                    )
            ).accepted());

            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.START_SKILL,
                    "gather_nearby_wood",
                    "",
                    List.of()
            ));
            fixture.brain.tick();
            assertEquals(
                    "gather_nearby_wood",
                    fixture.skills.snapshot().skillName()
            );
            fixture.brain.tick();

            assertEquals(
                    GoalStatus.RUNNING,
                    fixture.goals.snapshot().status(),
                    "One bounded tree cluster cannot complete a quantified "
                            + "multi-stage route"
            );
            assertEquals(
                    GoalExecutionPlan.foundation(
                            GoalExecutionPlan.Target
                                    .LOG_STORAGE_DISTRIBUTED
                    ).detailCode(),
                    fixture.goals.snapshot().detailCode()
            );
        }
    }

    @Test
    void goalCancellationDrivesTheSkillToACheckpointBeforeStopping() {
        try (Fixture fixture = new Fixture()) {
            fixture.skill.steps.add(SkillTickResult.running(true, false));
            fixture.skill.steps.add(SkillTickResult.running(true, true));
            fixture.startGoal();
            fixture.brain.tick();
            fixture.gateway.completeCurrent(success(
                    fixture.gateway.lastInput(),
                    DecisionKind.START_SKILL,
                    "test",
                    "",
                    List.of()
            ));
            fixture.brain.tick();
            fixture.brain.tick();
            assertEquals(1, fixture.skill.tickCalls);
            assertEquals(0, fixture.skill.cancelCalls);

            fixture.goals.requestCancel(GoalSource.MCP);
            fixture.brain.tick();

            assertEquals(2, fixture.skill.tickCalls);
            assertEquals(1, fixture.skill.cancelCalls);
            assertEquals(GoalStatus.SAFE_IDLE, fixture.goals.snapshot().status());
            assertEquals("goal_cancelled", fixture.goals.snapshot().detailCode());
            assertTrue(fixture.hasNotice("skill_cancelled"));
            assertTrue(fixture.hasNotice("skill_cancelled.test"));
        }
    }

    @Test
    void invalidPlannerBindingNeverReachesTheGateway() {
        try (Fixture fixture = new Fixture()) {
            fixture.inputFactory = (requestId, goal, observation) ->
                    new PlannerInput(
                            new DecisionContext(
                                    requestId,
                                    observation.epoch() + 1,
                                    goal.revision(),
                                    false,
                                    fixture.registry.modelArgumentValidators()
                            ),
                            "system",
                            observation.semanticJson(),
                            128
                    );
            fixture.rebuildBrain();
            fixture.startGoal();

            fixture.brain.tick();

            assertEquals(0, fixture.gateway.requestCount());
            assertEquals(GoalStatus.SAFE_IDLE, fixture.goals.snapshot().status());
            assertEquals(
                    "planner_input_invalid",
                    fixture.goals.snapshot().detailCode()
            );
        }
    }

    private static ModelOutcome success(
            PlannerInput input,
            DecisionKind kind,
            String skillName,
            String speech,
            List<SkillArgument> arguments
    ) {
        return success(
                input,
                kind,
                skillName,
                speech,
                arguments,
                RequestedObservation.none()
        );
    }

    private static ModelOutcome success(
            PlannerInput input,
            DecisionKind kind,
            String skillName,
            String speech,
            List<SkillArgument> arguments,
            RequestedObservation requestedObservation
    ) {
        DecisionContext context = input.decisionContext();
        DecisionEnvelope decision = new DecisionEnvelope(
                context.requestId(),
                context.observedWorldRevision(),
                context.goalRevision(),
                kind,
                skillName,
                arguments,
                requestedObservation,
                speech,
                0.9
        );
        return new ModelOutcome.Success(
                decision,
                TokenUsage.UNKNOWN,
                new RequestTrace(
                        context.requestId(),
                        "provider-request",
                        Protocol.RESPONSES,
                        200,
                        1
                )
        );
    }

    private static ModelOutcome failure(
            ModelFailureKind kind,
            Optional<Duration> retryAfter
    ) {
        return new ModelOutcome.Failure(new ModelFailure(
                kind,
                0,
                "",
                "",
                "",
                "",
                retryAfter,
                "",
                "safe"
        ));
    }

    private static final class Fixture implements AutoCloseable {
        private final GoalCoordinator goals =
                new GoalCoordinator(new InMemoryGoalRevisionStore());
        private final FakeGateway gateway = new FakeGateway();
        private final TestSkill skill = new TestSkill();
        private final TestSkill followSkill = new TestSkill(true);
        private final TestSkill surveySkill = new TestSkill(true);
        private final TestSkill collectSkill = new TestSkill(true);
        private final TestSkill consumeSkill = new TestSkill(true);
        private final TestSkill gatherWoodSkill = new TestSkill();
        private final TestSkill ironToolkitSkill = new TestSkill();
        private final SkillRegistry registry = new SkillRegistry()
                .register("test", skill)
                .register("follow_entity", followSkill)
                .register("survey_surroundings", surveySkill)
                .register("collect_observed_item", collectSkill)
                .register("consume_owned_food", consumeSkill)
                .register("gather_nearby_wood", gatherWoodSkill)
                .register("prepare_iron_toolkit", ironToolkitSkill);
        private final SkillSupervisor skills = new SkillSupervisor(
                registry,
                SkillCheckpointSink.discard(),
                new SkillRuntimePolicy(
                        Duration.ofSeconds(1),
                        Duration.ofHours(1),
                        100,
                        0.8,
                        0.4
                )
        );
        private final MutableObservationProvider observations =
                new MutableObservationProvider();
        private final List<BrainEvent> events = new ArrayList<>();
        private final AtomicLong clock = new AtomicLong();
        private PlannerInputFactory inputFactory = this::validInput;
        private BrainOrchestrator brain;

        private Fixture() {
            rebuildBrain();
        }

        private void rebuildBrain() {
            rebuildBrain(
                    GoalCompletionVerifier.ALLOW_ORDINARY_GOALS
            );
        }

        private void rebuildBrain(
                final GoalCompletionVerifier completionVerifier
        ) {
            if (brain != null) {
                brain.close();
            }
            brain = new BrainOrchestrator(
                    goals,
                    gateway,
                    skills,
                    observations,
                    inputFactory,
                    events::add,
                    new BrainPolicy(
                            Duration.ofNanos(100),
                            Duration.ofNanos(500),
                            Duration.ofNanos(1_000),
                            3
                    ),
                    clock::get,
                    completionVerifier
            );
        }

        private PlannerInput validInput(
                String requestId,
                dev.mcai.companion.control.GoalSnapshot goal,
                BrainObservation observation
        ) {
            return new PlannerInput(
                    new DecisionContext(
                            requestId,
                            observation.epoch(),
                            goal.revision(),
                            false,
                            registry.modelArgumentValidators()
                    ),
                    "system",
                    observation.semanticJson(),
                    128
            );
        }

        private void startGoal() {
            assertTrue(goals.setGoal("survive", GoalSource.MCP).accepted());
        }

        private boolean hasNotice(String code) {
            return events.stream()
                    .filter(BrainEvent.Notice.class::isInstance)
                    .map(BrainEvent.Notice.class::cast)
                    .anyMatch(notice -> notice.code().equals(code));
        }

        @Override
        public void close() {
            brain.close();
            skills.close();
            gateway.close();
        }
    }

    private static final class MutableObservationProvider implements ObservationProvider {
        private long epoch = 50;
        private long gameTick;
        private boolean hardcore;
        private boolean connected = true;
        private double risk;
        private int requestCount;
        private RequestedObservation lastRequest;
        private String semanticJson = "{\"health\":20}";
        private String trustedRuntimeJson = "{}";

        @Override
        public BrainObservation observe(
                dev.mcai.companion.control.GoalSnapshot goal
        ) {
            return new BrainObservation(
                    epoch,
                    new SkillContext(
                            goal.revision(),
                            epoch,
                            ++gameTick,
                            hardcore,
                            connected,
                            risk
                    ),
                    semanticJson,
                    trustedRuntimeJson
            );
        }

        @Override
        public ObservationRequestStatus requestObservation(
                final RequestedObservation request
        ) {
            requestCount++;
            lastRequest = request;
            return request.kind() == ObservationKind.SEMANTIC_REFRESH
                    ? ObservationRequestStatus.ACCEPTED
                    : ObservationRequestStatus.UNSUPPORTED;
        }
    }

    private static final class FakeGateway implements ModelGateway {
        private final List<PlannerInput> inputs = new ArrayList<>();
        private final List<Long> cancelRevisions = new ArrayList<>();
        private CompletableFuture<ModelOutcome> current;
        private GatewayStatus status = GatewayStatus.IDLE;
        private boolean configured;
        private boolean duplicateNextCompletion;

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public CompletionStage<ModelOutcome> decide(PlannerInput input) {
            inputs.add(input);
            current = duplicateNextCompletion
                    ? new DuplicateCompletionFuture()
                    : new CompletableFuture<>();
            duplicateNextCompletion = false;
            status = GatewayStatus.REQUESTING;
            current.whenComplete((result, throwable) -> status = GatewayStatus.IDLE);
            return current;
        }

        @Override
        public void cancelForGoalRevision(long currentGoalRevision) {
            cancelRevisions.add(currentGoalRevision);
            if (current != null && !current.isDone()) {
                current.complete(failure(
                        ModelFailureKind.CANCELLED,
                        Optional.empty()
                ));
            }
        }

        @Override
        public GatewayStatus status() {
            return status;
        }

        @Override
        public void close() {
            status = GatewayStatus.CLOSED;
            if (current != null) {
                current.cancel(true);
            }
        }

        private void completeCurrent(ModelOutcome outcome) {
            assertTrue(current.complete(outcome));
        }

        private int requestCount() {
            return inputs.size();
        }

        private PlannerInput lastInput() {
            return inputs.getLast();
        }
    }

    private static final class DuplicateCompletionFuture
            extends CompletableFuture<ModelOutcome> {
        @Override
        public CompletableFuture<ModelOutcome> whenComplete(
                BiConsumer<? super ModelOutcome, ? super Throwable> action
        ) {
            return super.whenComplete((outcome, throwable) -> {
                action.accept(outcome, throwable);
                action.accept(outcome, throwable);
            });
        }
    }

    private static final class TestSkill implements Skill<Unit> {
        private final boolean acceptArguments;
        private final ArrayDeque<SkillTickResult> steps = new ArrayDeque<>();
        private SkillTickResult defaultStep = SkillTickResult.running(true, false);
        private int startCalls;
        private int tickCalls;
        private int cancelCalls;
        private SkillFailure preconditionFailure;

        private TestSkill() {
            this(false);
        }

        private TestSkill(final boolean acceptArguments) {
            this.acceptArguments = acceptArguments;
        }

        @Override
        public SkillParameterParser<Unit> parameters() {
            return arguments -> (acceptArguments || arguments.isEmpty())
                    ? SkillParameterResult.valid(Unit.INSTANCE)
                    : SkillParameterResult.invalid("unexpected_arguments");
        }

        @Override
        public Optional<SkillFailure> preconditions(
                SkillContext context,
                Unit parameters
        ) {
            return Optional.ofNullable(preconditionFailure);
        }

        @Override
        public void start(SkillContext context, Unit parameters) {
            startCalls++;
        }

        @Override
        public SkillTickResult tick(SkillContext context, Unit parameters) {
            tickCalls++;
            return steps.isEmpty() ? defaultStep : steps.removeFirst();
        }

        @Override
        public SkillCheckpoint checkpoint(
                SkillContext context,
                Unit parameters
        ) {
            return SkillCheckpoint.empty();
        }

        @Override
        public void cancel(SkillContext context, Unit parameters) {
            cancelCalls++;
        }

        @Override
        public SkillResult result(SkillContext context, Unit parameters) {
            return SkillResult.completed();
        }
    }

    private enum Unit {
        INSTANCE
    }
}
