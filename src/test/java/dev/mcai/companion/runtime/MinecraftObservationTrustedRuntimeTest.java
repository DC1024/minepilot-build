package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.mcai.companion.brain.ObservationRequestStatus;
import dev.mcai.companion.memory.transport.VerifiedPortalEdge;
import dev.mcai.companion.memory.transport.VerifiedPortalEdgeRecallEntry;
import dev.mcai.companion.memory.transport.VerifiedPortalEdgeRecallSnapshot;
import dev.mcai.companion.model.ObservationKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.progression.SurvivalMilestone;
import dev.mcai.companion.progression.SurvivalRouteSnapshot;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillSupervisor;
import dev.mcai.companion.skills.memory.WaypointRecallSnapshot;
import dev.mcai.companion.skills.portal.PortalKind;
import dev.mcai.companion.skills.survey.SurveyResultSnapshot;
import dev.mcai.companion.skills.stronghold.EyeTraceHistorySnapshot;
import dev.mcai.companion.skills.stronghold.EyeTraceSnapshot;
import dev.mcai.companion.waypoint.DimensionRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class MinecraftObservationTrustedRuntimeTest {
    private static final UUID WORLD =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW =
        Instant.parse("2026-07-25T00:00:00Z");

    @Test
    void projectsVerifiedPortalRecallInsideTrustedRuntimeBoundary() {
        VerifiedPortalEdge edge = new VerifiedPortalEdge(
            "a".repeat(64),
            WORLD,
            PortalKind.NETHER_PORTAL,
            DimensionRef.OVERWORLD,
            new PerceptionVec3(10, 64, 10),
            new BlockCoordinate(10, 64, 10),
            DimensionRef.NETHER,
            new PerceptionVec3(80, 70, 80),
            new BlockCoordinate(80, 70, 80),
            NOW.minusSeconds(60),
            NOW,
            1,
            0
        );
        VerifiedPortalEdgeRecallSnapshot portalRecall =
            new VerifiedPortalEdgeRecallSnapshot(
                WORLD,
                DimensionRef.OVERWORLD,
                new PerceptionVec3(13, 64, 14),
                512,
                4,
                NOW.plusSeconds(1),
                List.of(VerifiedPortalEdgeRecallEntry.from(edge, 5))
            );

        String json = MinecraftObservationProvider.encodeTrustedRuntime(
            idleSkill(),
            List.of(),
            WaypointRecallSnapshot.empty(),
            Optional.of(portalRecall),
            ObservationKind.NONE,
            ObservationRequestStatus.REJECTED
        );
        var root = JsonParser.parseString(json).getAsJsonObject();
        var projected = root.getAsJsonObject(
            "recalledVerifiedPortalEdgeData"
        );

        assertEquals(
            "minecraft:overworld",
            projected.get("queryDimensionData").getAsString()
        );
        assertEquals(
            4,
            projected.get("maximumResultCountData").getAsInt()
        );
        assertEquals(
            "minecraft:the_nether",
            projected.getAsJsonArray("verifiedTransportEdgeData")
                .get(0)
                .getAsJsonObject()
                .get("destinationDimensionData")
                .getAsString()
        );
        assertTrue(projected.has("contentBoundary"));
        assertFalse(json.contains(WORLD.toString()));
    }

    @Test
    void omitsPortalFieldUntilAnAsyncQueryHasCompleted() {
        String json = MinecraftObservationProvider.encodeTrustedRuntime(
            idleSkill(),
            List.of(),
            WaypointRecallSnapshot.empty(),
            Optional.empty(),
            ObservationKind.NONE,
            ObservationRequestStatus.REJECTED
        );

        assertFalse(JsonParser.parseString(json).getAsJsonObject().has(
            "recalledVerifiedPortalEdgeData"
        ));
    }

    @Test
    void projectsGoalScopedFairSurveyInsideTrustedRuntimeBoundary() {
        final SurveyResultSnapshot survey = new SurveyResultSnapshot(
                3,
                DimensionRef.OVERWORLD,
                new PerceptionVec3(1.5, 64.0, 2.5),
                200,
                4,
                10,
                14,
                List.of(new SurveyResultSnapshot.BlockData(
                        "minecraft:oak_log",
                        2,
                        64,
                        4,
                        12,
                        "north",
                        2.0
                )),
                List.of(),
                List.of()
        );

        final String json =
                MinecraftObservationProvider.encodeTrustedRuntime(
                        idleSkill(),
                        List.of(),
                        WaypointRecallSnapshot.empty(),
                        Optional.empty(),
                        Optional.of(survey),
                        ObservationKind.NONE,
                        ObservationRequestStatus.REJECTED
                );
        final var projected = JsonParser.parseString(json)
                .getAsJsonObject()
                .getAsJsonObject("recentFairSurveyData");

        assertEquals(4, projected.get("sampledViews").getAsInt());
        assertEquals(
                "minecraft:oak_log",
                projected.getAsJsonArray("observedBlocks")
                        .get(0)
                        .getAsJsonObject()
                        .get("type")
                        .getAsString()
        );
        assertEquals(
                12,
                projected.getAsJsonArray("observedBlocks")
                        .get(0)
                        .getAsJsonObject()
                        .get("sampleSequence")
                        .getAsLong()
        );
        assertEquals(
                "north",
                projected.getAsJsonArray("observedBlocks")
                        .get(0)
                        .getAsJsonObject()
                        .get("face")
                        .getAsString()
        );
        assertTrue(projected.has("contentBoundary"));
        assertFalse(json.contains(WORLD.toString()));
    }

    @Test
    void omitsOversizedOptionalSurveyInsteadOfStoppingTheBrain() {
        final String longPath = "a".repeat(120);
        final List<SurveyResultSnapshot.BlockData> blocks =
                IntStream.range(
                                0,
                                SurveyResultSnapshot.MAXIMUM_BLOCKS
                        )
                        .mapToObj(index ->
                                new SurveyResultSnapshot.BlockData(
                                        "minecraft:" + longPath + index,
                                        index,
                                        64,
                                        index,
                                        index + 1L,
                                        "up",
                                        index + 0.5
                                )
                        )
                        .toList();
        final List<SurveyResultSnapshot.EntityData> entities =
                IntStream.range(
                                0,
                                SurveyResultSnapshot.MAXIMUM_ENTITIES
                        )
                        .mapToObj(index ->
                                new SurveyResultSnapshot.EntityData(
                                        "minecraft:" + longPath + index,
                                        1,
                                        new PerceptionVec3(
                                                index,
                                                64,
                                                index
                                        ),
                                        index + 0.5,
                                        false,
                                        false
                                )
                        )
                        .toList();
        final SurveyResultSnapshot survey =
                new SurveyResultSnapshot(
                        3,
                        DimensionRef.OVERWORLD,
                        new PerceptionVec3(0.5, 64, 0.5),
                        200,
                        24,
                        1,
                        64,
                        blocks,
                        entities,
                        List.of()
                );

        final String json =
                MinecraftObservationProvider.encodeTrustedRuntime(
                        idleSkill(),
                        List.of(),
                        WaypointRecallSnapshot.empty(),
                        Optional.empty(),
                        Optional.of(survey),
                        ObservationKind.NONE,
                        ObservationRequestStatus.REJECTED
                );
        final var root = JsonParser.parseString(json)
                .getAsJsonObject();

        assertTrue(
                json.length()
                        <= dev.mcai.companion.brain.BrainObservation
                            .MAX_TRUSTED_RUNTIME_JSON_CHARACTERS
        );
        assertFalse(root.has("recentFairSurveyData"));
        assertEquals(
                "recentFairSurveyData",
                root.getAsJsonArray("omittedTrustedRuntimeData")
                        .get(0)
                        .getAsString()
        );
        new dev.mcai.companion.brain.BrainObservation(
                8,
                new SkillContext(
                        3,
                        8,
                        200,
                        false,
                        true,
                        0.0
                ),
                "{}",
                json
        );
    }

    @Test
    void projectsFairEyeTraceInsideTrustedRuntimeBoundary() {
        final EyeTraceSnapshot trace = new EyeTraceSnapshot(
                3,
                DimensionRef.OVERWORLD,
                new PerceptionVec3(10.5, 64.0, 20.5),
                240,
                20,
                21,
                List.of(
                        new EyeTraceSnapshot.Sample(
                                20,
                                new PerceptionVec3(11.0, 66.0, 22.0)
                        ),
                        new EyeTraceSnapshot.Sample(
                                21,
                                new PerceptionVec3(13.0, 68.0, 26.0)
                        )
                ),
                0.4472135954999579,
                0.8944271909999159,
                -26.56505117707799,
                4.47213595499958
        );
        final EyeTraceHistorySnapshot history =
                new EyeTraceHistorySnapshot(
                        3,
                        List.of(trace),
                        Optional.empty()
                );

        final String json =
                MinecraftObservationProvider.encodeTrustedRuntime(
                        idleSkill(),
                        List.of(),
                        WaypointRecallSnapshot.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(history),
                        ObservationKind.NONE,
                        ObservationRequestStatus.REJECTED
                );
        final var projected = JsonParser.parseString(json)
                .getAsJsonObject()
                .getAsJsonObject("recentFairEyeTraceData");

        assertEquals(
                2,
                projected.getAsJsonArray("traces")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("observedSamples")
                        .size()
        );
        assertTrue(projected.has("contentBoundary"));
        assertFalse(json.contains(WORLD.toString()));
    }

    @Test
    void projectsServerVerifiedCompletionRouteSeparatelyFromNotes() {
        final SurvivalRouteSnapshot route =
                new SurvivalRouteSnapshot(
                        3,
                        dev.mcai.companion.progression
                                .SurvivalRouteProfile.COMPLETION,
                        DimensionRef.NETHER,
                        List.of(
                                SurvivalMilestone.BODY_ACTIVE,
                                SurvivalMilestone.WOOD_OBTAINED,
                                SurvivalMilestone.NETHER_ENTERED
                        ),
                        Optional.of(
                                SurvivalMilestone
                                        .BLAZE_MATERIAL_OBTAINED
                        ),
                        List.of(
                                dev.mcai.companion.progression
                                        .SurvivalSafetyDeficit
                                        .FOOD_RESERVE_LOW
                        ),
                        List.of(
                                dev.mcai.companion.progression
                                        .SurvivalRouteObjective
                                        .FIND_AND_ACQUIRE_BLAZE_MATERIAL
                        ),
                        Map.of(
                                "food",
                                12,
                                "building_blocks",
                                48
                        ),
                        Map.of(
                                "food",
                                8,
                                "building_blocks",
                                32
                        ),
                        20.0F,
                        18,
                        true,
                        4_000
                );

        final String json =
                MinecraftObservationProvider.encodeTrustedRuntime(
                        idleSkill(),
                        List.of("model claim only"),
                        WaypointRecallSnapshot.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(route),
                        ObservationKind.NONE,
                        ObservationRequestStatus.REJECTED
                );
        final var root = JsonParser.parseString(json)
                .getAsJsonObject();
        final var projected = root.getAsJsonObject(
                "verifiedCompletionRouteData"
        );

        assertEquals(
                "BLAZE_MATERIAL_OBTAINED",
                projected.get("nextUnverifiedMilestone").getAsString()
        );
        assertEquals(
                "COMPLETION",
                projected.get("profile").getAsString()
        );
        assertEquals(
                "FOOD_RESERVE_LOW",
                projected.getAsJsonArray("currentSafetyDeficits")
                        .get(0)
                        .getAsString()
        );
        assertEquals(
                48,
                projected.getAsJsonObject("criticalOwnedCounts")
                        .get("building_blocks")
                        .getAsInt()
        );
        assertEquals(
                32,
                projected.getAsJsonObject("currentMinimumTargets")
                        .get("building_blocks")
                        .getAsInt()
        );
        assertEquals(
                "model claim only",
                root.getAsJsonArray("modelAuthoredProgress")
                        .get(0)
                        .getAsString()
        );
    }

    @Test
    void projectsOnlyTheSafeLocalSkillStartRejectionCode() {
        final SkillSupervisor.Snapshot rejected =
                new SkillSupervisor.Snapshot(
                        SkillSupervisor.State.IDLE,
                        "",
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        false,
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(SkillFailure.of(
                                "gather.target_not_visible"
                        ))
                );

        final String json =
                MinecraftObservationProvider.encodeTrustedRuntime(
                        rejected,
                        List.of(),
                        WaypointRecallSnapshot.empty(),
                        Optional.empty(),
                        ObservationKind.NONE,
                        ObservationRequestStatus.REJECTED
                );
        final var root = JsonParser.parseString(json).getAsJsonObject();

        assertEquals(
                "gather.target_not_visible",
                root.get("lastSkillStartRejectionCode").getAsString()
        );
        assertFalse(json.contains("target text"));
    }

    private static SkillSupervisor.Snapshot idleSkill() {
        return new SkillSupervisor.Snapshot(
            SkillSupervisor.State.IDLE,
            "",
            0,
            0,
            0,
            0,
            0,
            0,
            false,
            false,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }
}
