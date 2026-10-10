package dev.mcai.companion.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.control.PersistedGoalState;
import dev.mcai.companion.progression.SurvivalMilestone;
import dev.mcai.companion.progression.FoundationFixtureKind;
import dev.mcai.companion.progression.VerifiedFixtureLocation;
import dev.mcai.companion.agent.AgentAccentColor;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.skills.building.ShelterBuildStep;
import dev.mcai.companion.skills.building.ShelterFacing;
import dev.mcai.companion.skills.building.ShelterPlan;
import dev.mcai.companion.skills.building.ShelterScale;
import dev.mcai.companion.skills.building.ShelterStepRole;
import dev.mcai.companion.waypoint.DimensionRef;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CompanionWorldDataTest {
    @Test
    void agentPresentationRoundTripsWithProviderTemperature() {
        final CompanionWorldData data = new CompanionWorldData();
        data.updateAgentPresentation(
            "Builder_1",
            AgentAccentColor.AMETHYST,
            0.7,
            "Prefer warm materials and concise status updates.",
            true
        );
        data.rememberPlayerName("Human_1");

        final var encoded = CompanionWorldData.CODEC
            .encodeStart(JsonOps.INSTANCE, data)
            .getOrThrow();
        final CompanionWorldData restored = CompanionWorldData.CODEC
            .parse(JsonOps.INSTANCE, encoded)
            .getOrThrow();

        assertEquals("Builder_1", restored.displayName());
        assertEquals(AgentAccentColor.AMETHYST, restored.accentColor());
        assertEquals(0.7, restored.temperature());
        assertEquals(
            "Prefer warm materials and concise status updates.",
            restored.agentSystemPrompt()
        );
        assertTrue(restored.onboardingCompleted());
        assertTrue(restored.knownPlayerNames().contains("Human_1"));
        assertThrows(
            IllegalArgumentException.class,
            () -> data.setDisplayName("中文名称")
        );
    }

    @Test
    void bodyAnchorProvenanceRoundTripsAndCanBeClaimedOnce() {
        final CompanionWorldData data = new CompanionWorldData();
        assertFalse(data.bodyNeedsInitialAnchor());

        data.markBodySpawned(false);
        assertTrue(data.bodyEverSpawned());
        assertFalse(data.bodySpawnAnchored());
        assertTrue(data.bodyNeedsInitialAnchor());

        final var encoded = CompanionWorldData.CODEC
            .encodeStart(JsonOps.INSTANCE, data)
            .getOrThrow();
        final CompanionWorldData restored = CompanionWorldData.CODEC
            .parse(JsonOps.INSTANCE, encoded)
            .getOrThrow();
        assertTrue(restored.bodyNeedsInitialAnchor());

        restored.markBodyAnchored();
        assertTrue(restored.bodySpawnAnchored());
        assertFalse(restored.bodyNeedsInitialAnchor());
    }

    @Test
    void evaluationVictoryRequiresOrderedServerEvidence() {
        final CompanionWorldData data = new CompanionWorldData();

        data.markEvaluationDragonKilled();
        data.markEvaluationReturnedFromEnd();
        assertFalse(data.evaluationDragonKilled());
        assertFalse(data.evaluationReturnedFromEnd());

        data.updateGoalState(new PersistedGoalState(
            0,
            Optional.of(UUID.randomUUID()),
            GoalStatus.RUNNING,
            GoalSource.HARDCORE_EVALUATION,
            "通关 Minecraft",
            "",
            Instant.EPOCH,
            true
        ));
        data.beginEvaluation(
            200L,
            "https://example.test/v1",
            "test-model"
        );
        assertThrows(
            IllegalStateException.class,
            () -> data.beginEvaluation(
                201L,
                "https://example.test/v1",
                "test-model"
            )
        );
        assertTrue(data.evaluationElapsedTicks(250L) == 50L);
        assertEquals(-1L, data.evaluationElapsedTicks(199L));
        data.appendGoalProgress(0L, "已完成第一阶段");
        assertTrue(
            data.goalProgress(0L).contains("已完成第一阶段")
        );
        data.markVerifiedRouteMilestones(
            0L,
            java.util.Set.of(
                SurvivalMilestone.BODY_ACTIVE,
                SurvivalMilestone.WOOD_OBTAINED
            )
        );
        assertEquals(
            java.util.Set.of(
                SurvivalMilestone.BODY_ACTIVE,
                SurvivalMilestone.WOOD_OBTAINED
            ),
            data.verifiedRouteProgress(0L).milestones()
        );
        assertEquals(42L, data.initializeRouteStartDay(0L, 42L));
        assertEquals(
            42L,
            data.initializeRouteStartDay(0L, 43L),
            "the first observed day must stay sticky"
        );
        final var encoded = CompanionWorldData.CODEC
            .encodeStart(JsonOps.INSTANCE, data)
            .getOrThrow();
        final CompanionWorldData restored =
            CompanionWorldData.CODEC
                .parse(JsonOps.INSTANCE, encoded)
                .getOrThrow();
        assertEquals(
            data.verifiedRouteProgress(0L),
            restored.verifiedRouteProgress(0L)
        );
        assertEquals(
            42L,
            restored.initializeRouteStartDay(0L, 99L)
        );
        data.markEvaluationReturnedFromEnd();
        assertFalse(data.evaluationReturnedFromEnd());
        assertFalse(data.evaluationVictoryVerified());

        data.markEvaluationDragonKilled();
        assertTrue(data.evaluationDragonKilled());
        assertFalse(data.evaluationVictoryVerified());

        data.markEvaluationReturnedFromEnd();
        assertTrue(data.evaluationReturnedFromEnd());
        assertTrue(data.evaluationVictoryVerified());
        data.markEvaluationContaminated();
        assertFalse(data.evaluationVictoryVerified());
        data.finishEvaluation(260L);
        assertTrue(data.evaluationElapsedTicks(999L) == 60L);

        assertThrows(
            IllegalArgumentException.class,
            () -> data.updateGoalState(new PersistedGoalState(
                0,
                Optional.of(UUID.randomUUID()),
                GoalStatus.RUNNING,
                GoalSource.RECOVERY,
                "后续任务",
                "",
                Instant.EPOCH.plusSeconds(1),
                true
            ))
        );
    }

    @Test
    void verifiedShelterGeometrySurvivesWorldRestart() {
        final CompanionWorldData data = new CompanionWorldData();
        final ShelterPlan plan = compactPlan();
        data.recordVerifiedShelter(0L, plan);

        assertTrue(data.verifiedRouteProgress(0L).milestones()
                .contains(SurvivalMilestone.SHELTER_COMPLETED));
        final var encoded = CompanionWorldData.CODEC
                .encodeStart(JsonOps.INSTANCE, data)
                .getOrThrow();
        final CompanionWorldData restored = CompanionWorldData.CODEC
                .parse(JsonOps.INSTANCE, encoded)
                .getOrThrow();

        final var evidence = restored
                .verifiedShelterEvidence(0L)
                .orElseThrow();
        assertEquals(DimensionRef.OVERWORLD.id(), evidence.dimension());
        assertEquals(plan.origin(), evidence.origin());
        assertEquals(plan.doorLower(), evidence.doorLower());
        assertEquals(plan.lightPosition(), evidence.lightPosition());

        restored.updateShelterVerification(0L, false);
        assertFalse(restored.verifiedRouteProgress(0L).milestones()
                .contains(SurvivalMilestone.SHELTER_COMPLETED));
        restored.updateShelterVerification(0L, true);
        assertTrue(restored.verifiedRouteProgress(0L).milestones()
                .contains(SurvivalMilestone.SHELTER_COMPLETED));
    }

    @Test
    void terminalTransitionPreservesEvidenceForTheSameGoal() {
        final CompanionWorldData data = new CompanionWorldData();
        final UUID goalId = UUID.randomUUID();
        data.updateGoalState(new PersistedGoalState(
                0L,
                Optional.of(goalId),
                GoalStatus.RUNNING,
                GoalSource.MCP,
                "建立安全据点并生存到第二天",
                "",
                Instant.EPOCH,
                false
        ));
        data.appendGoalProgress(0L, "庇护所已经完成");
        data.recordVerifiedShelter(0L, compactPlan());

        data.advanceGoalRevision();
        data.updateGoalState(new PersistedGoalState(
                1L,
                Optional.of(goalId),
                GoalStatus.SAFE_IDLE,
                GoalSource.MCP,
                "建立安全据点并生存到第二天",
                "model_failures_exhausted",
                Instant.EPOCH.plusSeconds(1L),
                false
        ));

        assertEquals(
                java.util.List.of("庇护所已经完成"),
                data.goalProgress(0L)
        );
        assertTrue(data.verifiedShelterEvidence(0L).isPresent());
        assertTrue(data.verifiedRouteProgress(0L).milestones()
                .contains(SurvivalMilestone.SHELTER_COMPLETED));
    }

    @Test
    void verifiedFoundationActionsSurviveWorldRestart() {
        final CompanionWorldData data = new CompanionWorldData();
        data.recordFoundationFixture(
                0L,
                FoundationFixtureKind.CRAFTING_TABLE,
                fixture(1, 64, 1)
        );
        data.recordFoundationFixture(
                0L,
                FoundationFixtureKind.FURNACE,
                fixture(2, 64, 1)
        );
        data.recordFoundationFixture(
                0L,
                FoundationFixtureKind.STORAGE,
                fixture(3, 64, 1)
        );
        data.recordFoundationStorageDeposit(
                0L,
                "minecraft:cobblestone",
                16
        );
        data.updateFoundationVerification(0L, true, true);

        final var encoded = CompanionWorldData.CODEC
                .encodeStart(JsonOps.INSTANCE, data)
                .getOrThrow();
        final CompanionWorldData restored = CompanionWorldData.CODEC
                .parse(JsonOps.INSTANCE, encoded)
                .getOrThrow();
        final var evidence = restored
                .verifiedFoundationEvidence(0L)
                .orElseThrow();

        assertEquals(fixture(1, 64, 1), evidence.craftingTable()
                .orElseThrow());
        assertEquals(fixture(2, 64, 1), evidence.furnace()
                .orElseThrow());
        assertEquals(fixture(3, 64, 1), evidence.storage()
                .orElseThrow());
        assertTrue(evidence.suppliesDeposited());
        assertEquals("minecraft:cobblestone", evidence.depositedItemId());
        assertEquals(16, evidence.depositedItemCount());
        assertTrue(restored.verifiedRouteProgress(0L).milestones()
                .contains(SurvivalMilestone.WORKSTATIONS_ESTABLISHED));
        assertTrue(restored.verifiedRouteProgress(0L).milestones()
                .contains(SurvivalMilestone.SUPPLIES_STORED));

        restored.updateFoundationVerification(0L, false, false);
        assertFalse(restored.verifiedRouteProgress(0L).milestones()
                .contains(SurvivalMilestone.WORKSTATIONS_ESTABLISHED));
        assertFalse(restored.verifiedRouteProgress(0L).milestones()
                .contains(SurvivalMilestone.SUPPLIES_STORED));
    }

    @Test
    void foundationInventoryReadinessIsRevokedWhenResourcesAreLost() {
        final CompanionWorldData data = new CompanionWorldData();
        data.markVerifiedRouteMilestones(
                0L,
                java.util.Set.of(
                        SurvivalMilestone.BASIC_CRAFTING_READY,
                        SurvivalMilestone.FOOD_SECURED,
                        SurvivalMilestone.STONE_TOOL_OBTAINED,
                        SurvivalMilestone.IRON_TOOLKIT_OBTAINED
                )
        );

        data.updateFoundationInventoryVerification(
                0L,
                false,
                false,
                true,
                false
        );

        assertFalse(data.verifiedRouteProgress(0L).milestones()
                .contains(SurvivalMilestone.BASIC_CRAFTING_READY));
        assertFalse(data.verifiedRouteProgress(0L).milestones()
                .contains(SurvivalMilestone.FOOD_SECURED));
        assertTrue(data.verifiedRouteProgress(0L).milestones()
                .contains(SurvivalMilestone.STONE_TOOL_OBTAINED));
        assertFalse(data.verifiedRouteProgress(0L).milestones()
                .contains(SurvivalMilestone.IRON_TOOLKIT_OBTAINED));

        data.updateFoundationInventoryVerification(
                0L,
                true,
                true,
                true,
                true
        );
        assertTrue(data.verifiedRouteProgress(0L).milestones()
                .containsAll(java.util.Set.of(
                        SurvivalMilestone.BASIC_CRAFTING_READY,
                        SurvivalMilestone.FOOD_SECURED,
                        SurvivalMilestone.STONE_TOOL_OBTAINED,
                        SurvivalMilestone.IRON_TOOLKIT_OBTAINED
                )));
    }

    @Test
    void inactiveContaminatedAndDeadRunsCannotAcquireVictoryEvidence() {
        final CompanionWorldData contaminated = lockedEvaluation();
        contaminated.markEvaluationContaminated();
        contaminated.markEvaluationDragonKilled();
        contaminated.markEvaluationReturnedFromEnd();
        assertFalse(contaminated.evaluationDragonKilled());
        assertFalse(contaminated.evaluationVictoryVerified());

        final CompanionWorldData dead = lockedEvaluation();
        dead.markHardcoreDead();
        dead.markEvaluationDragonKilled();
        dead.markEvaluationReturnedFromEnd();
        assertFalse(dead.evaluationDragonKilled());
        assertFalse(dead.evaluationVictoryVerified());

        final CompanionWorldData diedAfterEvidence = lockedEvaluation();
        diedAfterEvidence.markEvaluationDragonKilled();
        diedAfterEvidence.markEvaluationReturnedFromEnd();
        assertTrue(diedAfterEvidence.evaluationVictoryVerified());
        diedAfterEvidence.markHardcoreDead();
        assertFalse(diedAfterEvidence.evaluationVictoryVerified());
    }

    @Test
    void evaluationAuditRoundTripPreservesTimerAndTerminalEvidence() {
        final CompanionWorldData data = lockedEvaluation();
        data.markEvaluationDragonKilled();
        data.markEvaluationReturnedFromEnd();
        data.updateGoalState(new PersistedGoalState(
            0,
            Optional.of(UUID.randomUUID()),
            GoalStatus.COMPLETED,
            GoalSource.HARDCORE_EVALUATION,
            "通关 Minecraft",
            "dragon_killed_and_returned",
            Instant.EPOCH.plusSeconds(1),
            true
        ));
        data.finishEvaluation(1_400L);

        final var encoded = CompanionWorldData.CODEC
            .encodeStart(JsonOps.INSTANCE, data)
            .getOrThrow();
        final CompanionWorldData restored = CompanionWorldData.CODEC
            .parse(JsonOps.INSTANCE, encoded)
            .getOrThrow();

        assertTrue(restored.evaluationDragonKilled());
        assertTrue(restored.evaluationReturnedFromEnd());
        assertEquals(1_000L, restored.evaluationStartedGameTick());
        assertEquals(1_400L, restored.evaluationFinishedGameTick());
        assertEquals(400L, restored.evaluationElapsedTicks(1_500L));
        assertEquals(
            "https://example.test/v1",
            restored.evaluationModelBaseUrl()
        );
        assertEquals("test-model", restored.evaluationModelName());
        assertTrue(
            restored.persistedGoalState()
                .orElseThrow()
                .externalWritesLocked()
        );
        assertEquals(
            GoalStatus.COMPLETED,
            restored.persistedGoalState()
                .orElseThrow()
                .status()
        );
        assertFalse(
            restored.evaluationVictoryVerified(),
            "a terminal run must not be completed again after restart"
        );
    }

    @Test
    void codecRejectsForgedOrContradictoryEvaluationEvidence() {
        final JsonObject unlockedWithVictory = CompanionWorldData.CODEC
            .encodeStart(JsonOps.INSTANCE, new CompanionWorldData())
            .getOrThrow()
            .getAsJsonObject();
        final JsonObject forgedAudit = new JsonObject();
        forgedAudit.addProperty("dragon_killed", true);
        forgedAudit.addProperty("returned_from_end", true);
        forgedAudit.addProperty("started_game_tick", 100L);
        forgedAudit.addProperty("finished_game_tick", 200L);
        forgedAudit.addProperty(
            "model_base_url",
            "https://example.test/v1"
        );
        forgedAudit.addProperty("model_name", "test-model");
        unlockedWithVictory.add("evaluation_audit", forgedAudit);

        assertThrows(
            IllegalArgumentException.class,
            () -> CompanionWorldData.CODEC
                .parse(JsonOps.INSTANCE, unlockedWithVictory)
        );

        final JsonObject returnedWithoutKill = new JsonObject();
        returnedWithoutKill.addProperty("dragon_killed", false);
        returnedWithoutKill.addProperty("returned_from_end", true);
        returnedWithoutKill.addProperty(
            "started_game_tick",
            100L
        );
        returnedWithoutKill.addProperty(
            "finished_game_tick",
            -1L
        );
        returnedWithoutKill.addProperty(
            "model_base_url",
            "https://example.test/v1"
        );
        returnedWithoutKill.addProperty(
            "model_name",
            "test-model"
        );
        unlockedWithVictory.addProperty("evaluation_locked", true);
        unlockedWithVictory.addProperty(
            "goal_source",
            GoalSource.HARDCORE_EVALUATION.name()
        );
        unlockedWithVictory.add("evaluation_audit", returnedWithoutKill);

        assertThrows(
            IllegalArgumentException.class,
            () -> CompanionWorldData.CODEC
                .parse(JsonOps.INSTANCE, unlockedWithVictory)
        );
    }

    private static ShelterPlan compactPlan() {
        final GridPos origin = new GridPos(0, 64, 0);
        final GridPos door = new GridPos(0, 64, 2);
        final GridPos light = new GridPos(2, 64, 2);
        return new ShelterPlan(
                "0123456789abcdef",
                DimensionRef.OVERWORLD,
                1L,
                ShelterScale.COMPACT,
                origin,
                3,
                3,
                2,
                ShelterFacing.WEST,
                door,
                light,
                "minecraft:cobblestone",
                "minecraft:oak_door",
                "minecraft:torch",
                1,
                java.util.List.of(
                        new ShelterBuildStep(
                                0,
                                ShelterStepRole.LOWER_WALL,
                                origin
                        ),
                        new ShelterBuildStep(
                                1,
                                ShelterStepRole.DOOR,
                                door
                        ),
                        new ShelterBuildStep(
                                2,
                                ShelterStepRole.LIGHT,
                                light
                        )
                )
        );
    }

    private static VerifiedFixtureLocation fixture(
            final int x,
            final int y,
            final int z
    ) {
        return new VerifiedFixtureLocation(
                DimensionRef.OVERWORLD.id(),
                x,
                y,
                z
        );
    }

    private static CompanionWorldData lockedEvaluation() {
        final CompanionWorldData data = new CompanionWorldData();
        data.updateGoalState(new PersistedGoalState(
            0,
            Optional.of(UUID.randomUUID()),
            GoalStatus.RUNNING,
            GoalSource.HARDCORE_EVALUATION,
            "通关 Minecraft",
            "",
            Instant.EPOCH,
            true
        ));
        data.beginEvaluation(
            1_000L,
            "https://example.test/v1",
            "test-model"
        );
        return data;
    }
}
