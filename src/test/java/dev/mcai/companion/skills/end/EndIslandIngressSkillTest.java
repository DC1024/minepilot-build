package dev.mcai.companion.skills.end;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.CollisionAffordance;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.bridging.BridgeMaterialResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class EndIslandIngressSkillTest {
    private static final UUID PLAYER_ID = UUID.fromString(
            "e0000000-0000-0000-0000-000000000001"
    );
    private static final UUID OTHER_PLAYER_ID = UUID.fromString(
            "e0000000-0000-0000-0000-000000000002"
    );
    private static final PerceptionVec3 WEST =
            new PerceptionVec3(-1.0, 0.0, 0.0);

    @Test
    void preconditionsRequireTheLiveGroundedEndBodyInsideSpawnEnvelope() {
        final AtomicLong generation = new AtomicLong(7L);
        final MutableFrames frames = new MutableFrames(
                frame(
                        PLAYER_ID,
                        DimensionRef.END,
                        1,
                        100.5,
                        50.0,
                        0.5,
                        true,
                        false,
                        List.of(),
                        clearance(1, 100, 50, 0, true)
                )
        );
        final EndIslandIngressSkill skill = skill(
                new RecordingActuator(),
                frames,
                generation
        );
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();

        assertTrue(
                skill.preconditions(context(1), parameters).isEmpty()
        );

        frames.frame = withDimension(frames.frame, DimensionRef.OVERWORLD);
        assertTrue(skill.preconditions(context(1), parameters).isPresent());
        frames.frame = withDimension(frames.frame, DimensionRef.END);
        frames.frame = withPose(frames.frame, 1, 113.5, 50.0, 0.5, true, false);
        assertTrue(skill.preconditions(context(1), parameters).isPresent());
        frames.frame = withPose(frames.frame, 1, 100.5, 50.0, 0.5, false, false);
        assertTrue(skill.preconditions(context(1), parameters).isPresent());
        frames.frame = withPose(frames.frame, 1, 100.5, 50.0, 0.5, true, true);
        assertTrue(skill.preconditions(context(1), parameters).isPresent());
        frames.frame = withPlayer(frames.frame, OTHER_PLAYER_ID);
        assertTrue(skill.preconditions(context(1), parameters).isPresent());
        frames.frame = withPlayer(frames.frame, PLAYER_ID);
        generation.set(-1L);
        assertTrue(skill.preconditions(context(1), parameters).isPresent());
    }

    @Test
    void startsOneSneakingCardinalBridgeStepTowardTheEndOrigin() {
        final MutableFrames frames = new MutableFrames(
                frame(
                        PLAYER_ID,
                        DimensionRef.END,
                        1,
                        100.5,
                        50.0,
                        4.5,
                        true,
                        false,
                        List.of(currentTop(100, 49, 4, "minecraft:obsidian")),
                        bridgeStepClearance(1, 100, 50, 4, 99, 4)
                )
        );
        final RecordingActuator actuator = new RecordingActuator();
        final EndIslandIngressSkill skill = skill(
                actuator,
                frames,
                new AtomicLong(3L)
        );
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();
        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);
        frames.frame = withRevision(frames.frame, 2);
        skill.tick(context(3), parameters);

        final SkillTickResult tick = skill.tick(context(4), parameters);

        assertEquals(SkillTickResult.Status.RUNNING, tick.status());
        assertFalse(
                tick.safeCheckpoint(),
                "The parent must preserve the unsafe edge phase of BridgeTo"
        );
        assertFalse(actuator.movements.isEmpty());
        assertTrue(actuator.movements.getLast().sneak());
        assertEquals(
                90.0F,
                actuator.looks.getLast().yawDegrees(),
                0.01F,
                "The dominant cardinal step from +X must face west"
        );
        assertTrue(
                skill.checkpoint(context(2), parameters)
                        .payload()
                        .contains("BRIDGING_ONE_STEP")
        );
    }

    @Test
    void landfallRejectsStaleWrongBlockWrongFaceAndUnknownClearance() {
        assertLandfallRejected(CandidateKind.STALE);
        assertLandfallRejected(CandidateKind.WRONG_BLOCK);
        assertLandfallRejected(CandidateKind.WRONG_FACE);
        assertLandfallRejected(CandidateKind.UNKNOWN_CLEARANCE);
    }

    @Test
    void freshEndStoneLandfallStartsOrdinaryObservedTravel() {
        final MutableFrames frames = landfallFrames();
        final RecordingActuator actuator = new RecordingActuator();
        final EndIslandIngressSkill skill = skill(
                actuator,
                frames,
                new AtomicLong(9L)
        );
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();
        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);

        frames.frame = candidateFrame(
                2,
                "minecraft:end_stone",
                "up",
                true
        );
        skill.tick(context(3), parameters);
        final SkillTickResult tick = skill.tick(context(4), parameters);

        assertEquals(SkillTickResult.Status.RUNNING, tick.status());
        assertTrue(
                skill.checkpoint(context(3), parameters)
                        .payload()
                        .contains("TRAVELLING_TO_OBSERVED_END_STONE")
        );
    }

    @Test
    void visibleButDisconnectedEndStoneContinuesOneBlockBridging() {
        final MutableFrames frames = landfallFrames();
        final RecordingActuator actuator = new RecordingActuator();
        final EndIslandIngressSkill skill = skill(
                actuator,
                frames,
                new AtomicLong(10L)
        );
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();
        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);
        frames.frame = candidateAcrossGapFrame(2);
        skill.tick(context(3), parameters);

        final SkillTickResult tick = skill.tick(context(4), parameters);

        assertEquals(SkillTickResult.Status.RUNNING, tick.status());
        assertFalse(tick.safeCheckpoint());
        assertTrue(actuator.movements.getLast().sneak());
        assertTrue(
                skill.checkpoint(context(4), parameters)
                        .payload()
                        .contains("BRIDGING_ONE_STEP")
        );
        assertFalse(
                skill.checkpoint(context(4), parameters)
                        .payload()
                        .contains("TRAVELLING_TO_OBSERVED_END_STONE")
        );
    }

    @Test
    void visibleCenterwardEndStoneWallStartsBoundedTowering() {
        final MutableFrames frames = new MutableFrames(
                wallFrame(1)
        );
        final RecordingActuator actuator = new RecordingActuator();
        final EndIslandIngressSkill skill = skill(
                actuator,
                frames,
                new AtomicLong(12L)
        );
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();
        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);
        frames.frame = wallFrame(2);
        skill.tick(context(3), parameters);

        final SkillTickResult tick = skill.tick(context(4), parameters);

        assertEquals(SkillTickResult.Status.RUNNING, tick.status());
        assertTrue(
                skill.checkpoint(context(4), parameters)
                        .payload()
                        .contains("TOWERING_FOR_LANDFALL")
        );
        assertFalse(
                skill.checkpoint(context(4), parameters)
                        .payload()
                        .contains("BRIDGING_ONE_STEP")
        );
    }

    @Test
    void overheadObstructionBeforeAFrontWallStartsMiningNotAnotherTower() {
        final CoreSkillFrame first = wallAndOverheadFrame(1);
        final MutableFrames frames = new MutableFrames(first);
        final RecordingActuator actuator = new RecordingActuator();
        final RecordingInteractionActuator interactionActuator =
                new RecordingInteractionActuator(14L);
        final MutableInteractionFrames interactionFrames =
                new MutableInteractionFrames(
                        interactionFrame(first, 14L)
                );
        final EndIslandIngressSkill skill = new EndIslandIngressSkill(
                PLAYER_ID,
                actuator,
                frames,
                () -> BridgeMaterialResult.ready(
                        "minecraft:cobblestone",
                        64
                ),
                () -> 14L,
                interactionActuator,
                interactionFrames
        );
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();
        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);

        final CoreSkillFrame alignedRequest = wallAndOverheadFrame(2);
        frames.frame = alignedRequest;
        interactionFrames.frame = interactionFrame(
                alignedRequest,
                14L
        );
        skill.tick(context(3), parameters);
        final SkillTickResult aligning = skill.tick(
                context(4),
                parameters
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                aligning.status()
        );
        assertTrue(
                skill.checkpoint(context(4), parameters)
                        .payload()
                        .contains("ALIGNING_VISIBLE_BLOCK_BREAK")
        );
        assertTrue(interactionActuator.equippedItems.isEmpty());
        assertTrue(interactionActuator.miningTargets.isEmpty());
        assertEquals(MovementIntent.STOPPED, actuator.movements.getLast());
        assertFalse(actuator.looks.isEmpty());
        assertEquals(0, actuator.jumps);
        assertTrue(actuator.blockUses.isEmpty());

        final CoreSkillFrame alignedFresh = wallAndOverheadFrame(3);
        frames.frame = alignedFresh;
        interactionFrames.frame = interactionFrame(alignedFresh, 14L);
        interactionFrames.crosshair = alignedFresh.visibleBlockFaces()
                .stream()
                .filter(face -> face.block().equals(
                        new BlockCoordinate(98, 51, 0)
                ))
                .findFirst()
                .orElseThrow();
        final SkillTickResult waitingForAppliedLook = skill.tick(
                context(5),
                parameters
        );

        assertEquals(SkillTickResult.Status.RUNNING, waitingForAppliedLook.status());
        assertTrue(
                skill.checkpoint(context(5), parameters)
                        .payload()
                        .contains("ALIGNING_VISIBLE_BLOCK_BREAK")
        );
        assertTrue(interactionActuator.equippedItems.isEmpty());
        assertTrue(interactionActuator.miningTargets.isEmpty());

        final CoreSkillFrame executable = wallAndOverheadFrame(4);
        frames.frame = executable;
        interactionFrames.frame = interactionFrame(executable, 14L);
        interactionFrames.crosshair = executable.visibleBlockFaces()
                .stream()
                .filter(face -> face.block().equals(
                        new BlockCoordinate(98, 51, 0)
                ))
                .findFirst()
                .orElseThrow();
        final SkillTickResult mining = skill.tick(
                context(6),
                parameters
        );

        assertEquals(SkillTickResult.Status.RUNNING, mining.status());
        assertTrue(
                skill.checkpoint(context(5), parameters)
                        .payload()
                        .contains("MINING_VISIBLE_END_STONE")
        );
        assertFalse(
                skill.checkpoint(context(5), parameters)
                        .payload()
                        .contains("TOWERING_FOR_LANDFALL")
        );
        assertEquals(
                List.of("minecraft:iron_pickaxe"),
                interactionActuator.equippedItems
        );
        assertEquals(1, interactionActuator.miningTargets.size());
        final BlockInteractionTarget target =
                interactionActuator.miningTargets.getFirst();
        assertEquals(98, target.x());
        assertEquals(51, target.y());
        assertEquals(0, target.z());
        assertEquals(0, actuator.jumps);
        assertTrue(actuator.blockUses.isEmpty());
    }

    @Test
    void reachableLandfallIsUsedWhenAMoreCentralCandidateIsDisconnected() {
        final MutableFrames frames = landfallFrames();
        final EndIslandIngressSkill skill = skill(
                new RecordingActuator(),
                frames,
                new AtomicLong(13L)
        );
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();
        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);
        frames.frame = twoCandidateFrame(2);
        skill.tick(context(3), parameters);

        final SkillTickResult tick = skill.tick(context(4), parameters);

        assertEquals(SkillTickResult.Status.RUNNING, tick.status());
        assertTrue(
                skill.checkpoint(context(4), parameters)
                        .payload()
                        .contains("TRAVELLING_TO_OBSERVED_END_STONE")
        );
        assertTrue(
                skill.checkpoint(context(4), parameters)
                        .payload()
                        .contains("GridPos[x=55, y=49, z=0]")
        );
    }

    @Test
    void childFailuresExhaustABoundedRetryBudget() {
        final MutableFrames frames = new MutableFrames(
                frame(
                        PLAYER_ID,
                        DimensionRef.END,
                        1,
                        100.5,
                        50.0,
                        0.5,
                        true,
                        false,
                        List.of(currentTop(100, 49, 0, "minecraft:obsidian")),
                        bridgeStepClearance(1, 100, 50, 0, 99, 0)
                )
        );
        final RecordingActuator actuator = new RecordingActuator();
        actuator.rejectSneakingMovement = true;
        final EndIslandIngressSkill skill = skill(
                actuator,
                frames,
                new AtomicLong(5L)
        );
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();
        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);
        SkillTickResult tick = SkillTickResult.running(false, true);
        int gameTick = 2;
        for (int revision = 2;
                revision <= 12
                    && tick.status() != SkillTickResult.Status.FAILED;
                revision++) {
            frames.frame = withRevision(frames.frame, revision);
            skill.tick(context(++gameTick), parameters);
            tick = skill.tick(context(++gameTick), parameters);
        }

        assertEquals(
                SkillTickResult.Status.FAILED,
                tick.status(),
                "A permanently rejected child may not retry forever"
        );
        assertTrue(
                tick.failure().orElseThrow().code()
                        .startsWith(EndIslandIngressSkill.NAME + ".")
        );
    }

    @Test
    void completionNeedsANewerExactCurrentEndStoneSupportInsideRadius() {
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();

        final MutableFrames validFrames = new MutableFrames(
                readyFrame(1, 55.5, "minecraft:end_stone", "up", true)
        );
        final EndIslandIngressSkill valid = skill(
                new RecordingActuator(),
                validFrames,
                new AtomicLong(1L)
        );
        valid.start(context(1), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                valid.tick(context(2), parameters).status(),
                "The start observation cannot prove post-start arrival"
        );
        validFrames.frame = readyFrame(
                2,
                55.5,
                "minecraft:end_stone",
                "up",
                true
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                valid.tick(context(3), parameters).status()
        );

        assertDoesNotComplete(
                readyFrame(2, 55.5, "minecraft:obsidian", "up", true),
                "non-End-stone support"
        );
        assertDoesNotComplete(
                readyFrame(2, 55.5, "minecraft:end_stone", "north", true),
                "a non-UP face"
        );
        assertDoesNotComplete(
                readyFrame(2, 55.5, "minecraft:end_stone", "up", false),
                "unknown body clearance"
        );
        assertDoesNotComplete(
                readyFrame(2, 56.5, "minecraft:end_stone", "up", true),
                "support outside the ready radius"
        );
    }

    @Test
    void strictCompletionPublishesItsMilestoneExactlyOnce() {
        final MutableFrames frames = new MutableFrames(
                readyFrame(1, 55.5, "minecraft:end_stone", "up", true)
        );
        final AtomicInteger completionCalls = new AtomicInteger();
        final AtomicLong publishedGoalRevision = new AtomicLong(-1L);
        final EndIslandIngressSkill skill = new EndIslandIngressSkill(
                PLAYER_ID,
                new RecordingActuator(),
                frames,
                () -> BridgeMaterialResult.ready(
                        "minecraft:cobblestone",
                        64
                ),
                () -> 15L,
                null,
                null,
                goalRevision -> {
                    completionCalls.incrementAndGet();
                    publishedGoalRevision.set(goalRevision);
                }
        );
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();
        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);
        frames.frame = readyFrame(
                2,
                55.5,
                "minecraft:end_stone",
                "up",
                true
        );

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(3), parameters).status()
        );
        skill.tick(context(4), parameters);
        skill.result(context(4), parameters);
        skill.result(context(5), parameters);

        assertEquals(1, completionCalls.get());
        assertEquals(1L, publishedGoalRevision.get());
    }

    @Test
    void completionRadiusOverrideIsStricterThanTheNormalIngressRadius() {
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();
        final MutableFrames frames = new MutableFrames(
                readyFrame(1, 40.5, "minecraft:end_stone", "up", true)
        );
        final EndIslandIngressSkill skill = new EndIslandIngressSkill(
                PLAYER_ID,
                new RecordingActuator(),
                frames,
                () -> BridgeMaterialResult.ready(
                        "minecraft:cobblestone",
                        64
                ),
                () -> 15L,
                null,
                null,
                ignored -> { },
                32.0
        );

        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(3), parameters).status(),
                "A fight rally must not be certified merely because it is inside the wider ingress radius"
        );

        frames.frame = readyFrame(
                4,
                31.5,
                "minecraft:end_stone",
                "up",
                true
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(4), parameters).status()
        );
    }

    @Test
    void rejectedPostTravelSupportReturnsToScanningInsteadOfFreezing() {
        final MutableFrames frames = landfallFrames();
        final RecordingActuator actuator = new RecordingActuator();
        final EndIslandIngressSkill skill = skill(
                actuator,
                frames,
                new AtomicLong(11L)
        );
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();
        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);
        frames.frame = candidateFrame(
                2,
                "minecraft:end_stone",
                "up",
                true
        );
        skill.tick(context(3), parameters);
        skill.tick(context(4), parameters);
        frames.frame = readyFrame(
                3,
                51.5,
                "minecraft:end_stone",
                "up",
                true
        );
        skill.tick(context(5), parameters);

        /* TravelTo can complete immediately in this fixture once its
         * observed target is bound. The next fresh frame deliberately proves
         * that the body did not land on natural End stone. */
        frames.frame = readyFrame(
                4,
                51.5,
                "minecraft:obsidian",
                "up",
                true
        );
        final SkillTickResult rejected = skill.tick(
                context(6),
                parameters
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                rejected.status(),
                rejected.failure().map(failure -> failure.code())
                        .orElse("no failure")
        );
        assertTrue(rejected.safeCheckpoint());
        assertTrue(
                skill.checkpoint(context(6), parameters)
                        .payload()
                        .contains("SCANNING")
        );
        assertFalse(
                skill.checkpoint(context(6), parameters)
                        .payload()
                        .contains("VERIFYING_CURRENT_SUPPORT")
        );
    }

    @Test
    void exactEndOriginNeverInventsAnOutwardCardinalStep() {
        final PerceptionVec3 origin = new PerceptionVec3(0.0, 50.0, 0.0);

        assertEquals(
                origin,
                EndArenaTopology.oneCardinalStepTowardCenter(origin)
        );
        assertNotEquals(
                origin,
                EndArenaTopology.oneCardinalStepTowardCenter(
                        new PerceptionVec3(0.5, 50.0, 0.5)
                )
        );
        assertEquals(
                new PerceptionVec3(99.5, 50.0, 0.5),
                EndArenaTopology.oneCardinalStepTowardCenter(
                        new PerceptionVec3(100.99, 50.0, 0.99)
                ),
                "A knockback offset must still target the adjacent cell "
                        + "center"
        );
    }

    @Test
    void sideStepTargetRequiresARealGridCrossingFromAnEdgePose() {
        final PerceptionVec3 body = new PerceptionVec3(
                47.63,
                51.0,
                -0.12
        );
        final PerceptionVec3 target = EndIslandIngressSkill.sideStepTarget(
                body,
                new GridPos(47, 51, 0)
        );

        assertTrue(target.x() > 47.0 && target.x() < 48.0);
        assertTrue(target.z() > 0.5 && target.z() < 1.0);
        assertTrue(
                Math.hypot(target.x() - body.x(), target.z() - body.z())
                        > 0.65,
                "the adjacent-cell target must not be inside BridgeTo's "
                        + "minimum arrival radius before the feet grid changes"
        );
    }

    @Test
    void sideStepTargetDoesNotInventAnOrthogonalCornerWhenAlreadyAligned() {
        final PerceptionVec3 body = new PerceptionVec3(
                53.50,
                49.0,
                0.36
        );
        final PerceptionVec3 target = EndIslandIngressSkill.sideStepTarget(
                body,
                new GridPos(52, 49, 0)
        );

        assertEquals(0.36, target.z(), 1.0E-9);
        assertTrue(target.x() > 52.0 && target.x() < 52.5);
    }

    @Test
    void cancelQuiescesAnActiveSneakingBridgeAndUseAction() {
        final MutableFrames frames = new MutableFrames(
                frame(
                        PLAYER_ID,
                        DimensionRef.END,
                        1,
                        100.5,
                        50.0,
                        0.5,
                        true,
                        false,
                        List.of(currentTop(100, 49, 0, "minecraft:obsidian")),
                        bridgeStepClearance(1, 100, 50, 0, 99, 0)
                )
        );
        final RecordingActuator actuator = new RecordingActuator();
        final EndIslandIngressSkill skill = skill(
                actuator,
                frames,
                new AtomicLong(4L)
        );
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();
        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);
        frames.frame = withRevision(frames.frame, 2);
        skill.tick(context(3), parameters);
        skill.tick(context(4), parameters);
        assertTrue(actuator.movements.getLast().sneak());

        final int movementsBeforeCancel = actuator.movements.size();
        skill.cancel(context(5), parameters);

        assertTrue(actuator.stops > 0);
        assertTrue(actuator.releases > 0);
        assertEquals(movementsBeforeCancel, actuator.movements.size());
        assertEquals(
                SkillResult.Status.CANCELLED,
                skill.result(context(5), parameters).status()
        );
    }

    private static void assertLandfallRejected(final CandidateKind kind) {
        final MutableFrames frames = landfallFrames();
        final EndIslandIngressSkill skill = skill(
                new RecordingActuator(),
                frames,
                new AtomicLong(8L)
        );
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();
        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);

        frames.frame = switch (kind) {
            case STALE -> candidateFrame(
                    1,
                    "minecraft:end_stone",
                    "up",
                    true
            );
            case WRONG_BLOCK -> candidateFrame(
                    2,
                    "minecraft:obsidian",
                    "up",
                    true
            );
            case WRONG_FACE -> candidateFrame(
                    2,
                    "minecraft:end_stone",
                    "north",
                    true
            );
            case UNKNOWN_CLEARANCE -> candidateFrame(
                    2,
                    "minecraft:end_stone",
                    "up",
                    false
            );
        };
        skill.tick(context(3), parameters);
        final SkillTickResult tick = skill.tick(context(4), parameters);

        assertEquals(SkillTickResult.Status.RUNNING, tick.status());
        assertFalse(
                skill.checkpoint(context(3), parameters)
                        .payload()
                        .contains("TRAVELLING_TO_OBSERVED_END_STONE"),
                kind + " must not authorize a landfall travel child"
        );
    }

    private static void assertDoesNotComplete(
            final CoreSkillFrame secondFrame,
            final String reason
    ) {
        final EndIslandIngressParameters parameters =
                EndIslandIngressParameters.defaults();
        final MutableFrames frames = new MutableFrames(
                readyFrame(
                        1,
                        secondFrame.position().x(),
                        "minecraft:end_stone",
                        "up",
                        true
                )
        );
        final EndIslandIngressSkill skill = skill(
                new RecordingActuator(),
                frames,
                new AtomicLong(2L)
        );
        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);
        frames.frame = secondFrame;

        assertFalse(
                skill.tick(context(3), parameters).status()
                        == SkillTickResult.Status.COMPLETED,
                "Ingress completed with " + reason
        );
    }

    private static MutableFrames landfallFrames() {
        return new MutableFrames(
                frame(
                        PLAYER_ID,
                        DimensionRef.END,
                        1,
                        60.5,
                        50.0,
                        0.5,
                        true,
                        false,
                        List.of(currentTop(60, 49, 0, "minecraft:obsidian")),
                        clearance(1, 60, 50, 0, true)
                )
        );
    }

    private static CoreSkillFrame candidateFrame(
            final long revision,
            final String block,
            final String face,
            final boolean safeClearance
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>(
                clearance(revision, 60, 50, 0, true)
        );
        for (int x = 59; x >= 51; x--) {
            addSupport(
                    voxels,
                    revision,
                    x,
                    49,
                    0,
                    safeClearance
            );
        }
        return frame(
                PLAYER_ID,
                DimensionRef.END,
                revision,
                60.5,
                50.0,
                0.5,
                true,
                false,
                List.of(
                        currentTop(60, 49, 0, "minecraft:obsidian"),
                        top(51, 49, 0, block, face)
                ),
                voxels
        );
    }

    private static CoreSkillFrame candidateAcrossGapFrame(
            final long revision
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>(
                bridgeStepClearance(revision, 60, 50, 0, 59, 0)
        );
        addSupport(voxels, revision, 51, 49, 0, true);
        return frame(
                PLAYER_ID,
                DimensionRef.END,
                revision,
                60.5,
                50.0,
                0.5,
                true,
                false,
                List.of(
                        currentTop(60, 49, 0, "minecraft:obsidian"),
                        top(51, 49, 0, "minecraft:end_stone", "up")
                ),
                voxels
        );
    }

    private static CoreSkillFrame wallFrame(final long revision) {
        return frame(
                PLAYER_ID,
                DimensionRef.END,
                revision,
                98.637,
                49.0,
                0.5,
                true,
                false,
                List.of(
                        currentTop(98, 48, 0, "minecraft:obsidian"),
                        top(97, 49, 0, "minecraft:end_stone", "east"),
                        top(97, 50, 0, "minecraft:end_stone", "east")
                ),
                clearance(revision, 98, 49, 0, true)
        );
    }

    private static CoreSkillFrame wallAndOverheadFrame(
            final long revision
    ) {
        final CoreSkillFrame geometry = frame(
                PLAYER_ID,
                DimensionRef.END,
                revision,
                98.637,
                49.0,
                0.5,
                true,
                false,
                List.of(
                        currentTop(98, 48, 0, "minecraft:obsidian"),
                        top(97, 49, 0, "minecraft:end_stone", "east"),
                        top(97, 50, 0, "minecraft:end_stone", "east"),
                        face(
                                98,
                                51,
                                0,
                                "minecraft:end_stone",
                                "down",
                                new PerceptionVec3(98.5, 51.0, 0.5)
                        )
                ),
                clearance(revision, 98, 49, 0, true)
        );
        return new CoreSkillFrame(
                geometry.playerId(),
                geometry.dimension(),
                geometry.gameTime(),
                geometry.observationRevision(),
                geometry.position(),
                geometry.eyePosition(),
                geometry.lookDirection(),
                geometry.onGround(),
                geometry.inWater(),
                geometry.danger(),
                geometry.navigation(),
                geometry.visibleBlockFaces(),
                20.0F,
                20.0F,
                20,
                List.of(new InventoryItemSummary(
                        "minecraft:iron_pickaxe",
                        1
                )),
                new HeldItemSummary(
                        "minecraft:iron_pickaxe",
                        1,
                        0,
                        250
                ),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
    }

    private static InteractionSkillFrame interactionFrame(
            final CoreSkillFrame core,
            final long session
    ) {
        return new InteractionSkillFrame(
                core.playerId(),
                core.dimension(),
                core.gameTime(),
                core.gameTime(),
                core.observationRevision(),
                session,
                core.mainHand(),
                core.offHand(),
                List.of(),
                core.visibleBlockFaces(),
                core.inventory()
        );
    }

    private static CoreSkillFrame twoCandidateFrame(
            final long revision
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>(
                clearance(revision, 60, 50, 0, true)
        );
        for (int x = 59; x >= 55; x--) {
            addSupport(voxels, revision, x, 49, 0, true);
        }
        addSupport(voxels, revision, 50, 49, 0, true);
        return frame(
                PLAYER_ID,
                DimensionRef.END,
                revision,
                60.5,
                50.0,
                0.5,
                true,
                false,
                List.of(
                        currentTop(60, 49, 0, "minecraft:obsidian"),
                        top(50, 49, 0, "minecraft:end_stone", "up"),
                        top(55, 49, 0, "minecraft:end_stone", "up")
                ),
                voxels
        );
    }

    private static CoreSkillFrame readyFrame(
            final long revision,
            final double x,
            final String block,
            final String face,
            final boolean safeClearance
    ) {
        final int footX = (int) Math.floor(x);
        final CoreSkillFrame base = frame(
                PLAYER_ID,
                DimensionRef.END,
                revision,
                x,
                50.0,
                0.5,
                true,
                false,
                List.of(top(footX, 49, 0, block, face)),
                clearance(revision, footX, 50, 0, safeClearance)
        );
        return new CoreSkillFrame(
                base.playerId(),
                base.dimension(),
                base.gameTime(),
                base.observationRevision(),
                base.position(),
                base.eyePosition(),
                new PerceptionVec3(0.0, 1.0, 0.0),
                base.onGround(),
                base.inWater(),
                base.danger(),
                base.navigation(),
                base.visibleBlockFaces()
        );
    }

    private static EndIslandIngressSkill skill(
            final RecordingActuator actuator,
            final MutableFrames frames,
            final AtomicLong generation
    ) {
        return new EndIslandIngressSkill(
                PLAYER_ID,
                actuator,
                frames,
                () -> BridgeMaterialResult.ready(
                        "minecraft:cobblestone",
                        64
                ),
                generation::get
        );
    }

    private static CoreSkillFrame frame(
            final UUID playerId,
            final DimensionRef dimension,
            final long revision,
            final double x,
            final double y,
            final double z,
            final boolean onGround,
            final boolean inWater,
            final List<VisibleBlockFace> faces,
            final List<ObservedVoxel> voxels
    ) {
        return new CoreSkillFrame(
                playerId,
                dimension,
                revision,
                revision,
                new PerceptionVec3(x, y, z),
                new PerceptionVec3(x, y + 1.62, z),
                WEST,
                onGround,
                inWater,
                0.0,
                new LocalNavSnapshot(dimension, revision, voxels),
                faces
        );
    }

    private static List<ObservedVoxel> clearance(
            final long revision,
            final int footX,
            final int footY,
            final int footZ,
            final boolean safe
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        voxels.add(voxel(
                revision,
                new GridPos(footX, footY - 1, footZ),
                VoxelKind.SOLID,
                OccupancyEvidence.SURFACE_HIT,
                safe
                        ? TopSupportAffordance.STURDY_FULL_TOP
                        : TopSupportAffordance.UNKNOWN
        ));
        voxels.add(voxel(
                revision,
                new GridPos(footX, footY, footZ),
                VoxelKind.AIR,
                safe
                        ? OccupancyEvidence.BODY_OCCUPIED
                        : OccupancyEvidence.UNKNOWN,
                TopSupportAffordance.UNKNOWN
        ));
        voxels.add(voxel(
                revision,
                new GridPos(footX, footY + 1, footZ),
                VoxelKind.AIR,
                safe
                        ? OccupancyEvidence.MULTI_RAY_CLEAR
                        : OccupancyEvidence.UNKNOWN,
                TopSupportAffordance.UNKNOWN
        ));
        return voxels;
    }

    private static List<ObservedVoxel> bridgeStepClearance(
            final long revision,
            final int currentX,
            final int footY,
            final int currentZ,
            final int destinationX,
            final int destinationZ
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>(clearance(
                revision,
                currentX,
                footY,
                currentZ,
                true
        ));
        voxels.add(voxel(
                revision,
                new GridPos(destinationX, footY, destinationZ),
                VoxelKind.AIR,
                OccupancyEvidence.MULTI_RAY_CLEAR,
                TopSupportAffordance.UNKNOWN
        ));
        voxels.add(voxel(
                revision,
                new GridPos(destinationX, footY + 1, destinationZ),
                VoxelKind.AIR,
                OccupancyEvidence.MULTI_RAY_CLEAR,
                TopSupportAffordance.UNKNOWN
        ));
        return voxels;
    }

    private static void addSupport(
            final List<ObservedVoxel> voxels,
            final long revision,
            final int x,
            final int y,
            final int z,
            final boolean safe
    ) {
        voxels.add(voxel(
                revision,
                new GridPos(x, y, z),
                VoxelKind.SOLID,
                OccupancyEvidence.SURFACE_HIT,
                safe
                        ? TopSupportAffordance.STURDY_FULL_TOP
                        : TopSupportAffordance.UNKNOWN
        ));
        voxels.add(voxel(
                revision,
                new GridPos(x, y + 1, z),
                VoxelKind.AIR,
                safe
                        ? OccupancyEvidence.MULTI_RAY_CLEAR
                        : OccupancyEvidence.UNKNOWN,
                TopSupportAffordance.UNKNOWN
        ));
        voxels.add(voxel(
                revision,
                new GridPos(x, y + 2, z),
                VoxelKind.AIR,
                safe
                        ? OccupancyEvidence.MULTI_RAY_CLEAR
                        : OccupancyEvidence.UNKNOWN,
                TopSupportAffordance.UNKNOWN
        ));
    }

    private static ObservedVoxel voxel(
            final long revision,
            final GridPos position,
            final VoxelKind kind,
            final OccupancyEvidence occupancy,
            final TopSupportAffordance support
    ) {
        return new ObservedVoxel(
                position,
                kind,
                0.0,
                revision,
                occupancy,
                support
        );
    }

    private static VisibleBlockFace currentTop(
            final int x,
            final int y,
            final int z,
            final String block
    ) {
        return top(x, y, z, block, "up");
    }

    private static VisibleBlockFace top(
            final int x,
            final int y,
            final int z,
            final String block,
            final String face
    ) {
        return face(
                x,
                y,
                z,
                block,
                face,
                new PerceptionVec3(x + 0.5, y + 1.0, z + 0.5)
        );
    }

    private static VisibleBlockFace face(
            final int x,
            final int y,
            final int z,
            final String block,
            final String face,
            final PerceptionVec3 hitPosition
    ) {
        return new VisibleBlockFace(
                new BlockCoordinate(x, y, z),
                block,
                face,
                hitPosition,
                2.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of(),
                TopSupportAffordance.STURDY_FULL_TOP,
                CollisionAffordance.OBSTRUCTED_OR_PARTIAL,
                0
        );
    }

    private static CoreSkillFrame withRevision(
            final CoreSkillFrame source,
            final long revision
    ) {
        final List<ObservedVoxel> voxels = source.navigation()
                .observedVoxels()
                .values()
                .stream()
                .map(voxel -> new ObservedVoxel(
                        voxel.position(),
                        voxel.kind(),
                        voxel.danger(),
                        revision,
                        voxel.occupancyEvidence(),
                        voxel.topSupportAffordance()
                ))
                .toList();
        return frame(
                source.playerId(),
                source.dimension(),
                revision,
                source.position().x(),
                source.position().y(),
                source.position().z(),
                source.onGround(),
                source.inWater(),
                source.visibleBlockFaces(),
                voxels
        );
    }

    private static CoreSkillFrame withDimension(
            final CoreSkillFrame source,
            final DimensionRef dimension
    ) {
        return frame(
                source.playerId(),
                dimension,
                source.observationRevision(),
                source.position().x(),
                source.position().y(),
                source.position().z(),
                source.onGround(),
                source.inWater(),
                source.visibleBlockFaces(),
                source.navigation().observedVoxels().values().stream()
                        .toList()
        );
    }

    private static CoreSkillFrame withPose(
            final CoreSkillFrame source,
            final long revision,
            final double x,
            final double y,
            final double z,
            final boolean onGround,
            final boolean inWater
    ) {
        return frame(
                source.playerId(),
                source.dimension(),
                revision,
                x,
                y,
                z,
                onGround,
                inWater,
                source.visibleBlockFaces(),
                source.navigation().observedVoxels().values().stream()
                        .toList()
        );
    }

    private static CoreSkillFrame withPlayer(
            final CoreSkillFrame source,
            final UUID playerId
    ) {
        return frame(
                playerId,
                source.dimension(),
                source.observationRevision(),
                source.position().x(),
                source.position().y(),
                source.position().z(),
                source.onGround(),
                source.inWater(),
                source.visibleBlockFaces(),
                source.navigation().observedVoxels().values().stream()
                        .toList()
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(1, tick, tick, true, true, 0.0);
    }

    private enum CandidateKind {
        STALE,
        WRONG_BLOCK,
        WRONG_FACE,
        UNKNOWN_CLEARANCE
    }

    private static final class MutableFrames
            implements CoreSkillFrameSource {
        private CoreSkillFrame frame;

        private MutableFrames(final CoreSkillFrame frame) {
            this.frame = frame;
        }

        @Override
        public Optional<CoreSkillFrame> current() {
            return Optional.ofNullable(frame);
        }
    }

    private static final class MutableInteractionFrames
            implements InteractionSkillFrameSource {
        private InteractionSkillFrame frame;
        private VisibleBlockFace crosshair;

        private MutableInteractionFrames(
                final InteractionSkillFrame frame
        ) {
            this.frame = frame;
        }

        @Override
        public Optional<InteractionSkillFrame> current() {
            return Optional.ofNullable(frame);
        }

        @Override
        public Optional<VisibleBlockFace> currentCrosshairBlock() {
            return Optional.ofNullable(crosshair);
        }
    }

    private static final class RecordingInteractionActuator
            implements InteractionSkillActuator {
        private final long session;
        private final List<BlockInteractionTarget> miningTargets =
                new ArrayList<>();
        private final List<String> equippedItems = new ArrayList<>();

        private RecordingInteractionActuator(final long session) {
            this.session = session;
        }

        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(session);
        }

        @Override
        public ActionOutcome beginMining(
                final BlockInteractionTarget target
        ) {
            miningTargets.add(target);
            return ActionOutcome.IN_PROGRESS;
        }

        @Override
        public ActionOutcome continueMining() {
            return ActionOutcome.IN_PROGRESS;
        }

        @Override
        public ActionOutcome abortMining() {
            return ActionOutcome.COMPLETED;
        }

        @Override
        public ActionOutcome useOnBlock(
                final ActionHand hand,
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }

        @Override
        public ActionOutcome attack(final UUID entityId) {
            return ActionOutcome.TARGET_NOT_FOUND;
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }

        @Override
        public ActionOutcome continueUsing(final ActionHand hand) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome releaseUse() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome equipMainHand(final String itemId) {
            equippedItems.add(itemId);
            return ActionOutcome.COMPLETED;
        }
    }

    private static final class RecordingActuator
            implements CoreSkillActuator {
        private final List<MovementIntent> movements = new ArrayList<>();
        private final List<LookIntent> looks = new ArrayList<>();
        private final List<BlockInteractionTarget> blockUses =
                new ArrayList<>();
        private final List<ActionHand> itemUses = new ArrayList<>();
        private int jumps;
        private int stops;
        private int releases;
        private ActionOutcome outcome = ActionOutcome.QUEUED;
        private boolean rejectSneakingMovement;

        @Override
        public ActionOutcome move(final MovementIntent intent) {
            movements.add(intent);
            if (rejectSneakingMovement && intent.sneak()) {
                return ActionOutcome.PLAYER_UNAVAILABLE;
            }
            return outcome;
        }

        @Override
        public ActionOutcome look(final LookIntent intent) {
            looks.add(intent);
            return outcome;
        }

        @Override
        public ActionOutcome jump() {
            jumps++;
            return outcome;
        }

        @Override
        public ActionOutcome stop() {
            stops++;
            return outcome;
        }

        @Override
        public ActionOutcome useMainHandOn(
                final BlockInteractionTarget target
        ) {
            blockUses.add(target);
            return outcome;
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            itemUses.add(hand);
            return outcome;
        }

        @Override
        public ActionOutcome releaseUse() {
            releases++;
            return outcome;
        }
    }
}
