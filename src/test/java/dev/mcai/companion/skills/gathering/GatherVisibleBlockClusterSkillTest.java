package dev.mcai.companion.skills.gathering;

import static dev.mcai.companion.skills.gathering.GatheringSkillTestFixtures.BLOCK_ID;
import static dev.mcai.companion.skills.gathering.GatheringSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.gathering.GatheringSkillTestFixtures.TOOL_ID;
import static dev.mcai.companion.skills.gathering.GatheringSkillTestFixtures.collectionPolicy;
import static dev.mcai.companion.skills.gathering.GatheringSkillTestFixtures.droppedLog;
import static dev.mcai.companion.skills.gathering.GatheringSkillTestFixtures.frames;
import static dev.mcai.companion.skills.gathering.GatheringSkillTestFixtures.immediateCollectionPolicy;
import static dev.mcai.companion.skills.gathering.GatheringSkillTestFixtures.log;
import static dev.mcai.companion.skills.gathering.GatheringSkillTestFixtures.parameters;
import static dev.mcai.companion.skills.gathering.GatheringSkillTestFixtures.withInventory;
import static dev.mcai.companion.skills.gathering.GatheringSkillTestFixtures.withVisibleEntities;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class GatherVisibleBlockClusterSkillTest {
    @Test
    void mapsMinedFoundationBlocksToTheirRequiredInventoryDrops() {
        assertEquals(
                "minecraft:cobblestone",
                GatherVisibleBlockClusterSkill.requiredPickupItemId(
                        "minecraft:stone"
                ).orElseThrow()
        );
        assertEquals(
                "minecraft:coal",
                GatherVisibleBlockClusterSkill.requiredPickupItemId(
                        "minecraft:deepslate_coal_ore"
                ).orElseThrow()
        );
        assertEquals(
                "minecraft:raw_iron",
                GatherVisibleBlockClusterSkill.requiredPickupItemId(
                        "minecraft:iron_ore"
                ).orElseThrow()
        );
        assertTrue(
                GatherVisibleBlockClusterSkill.requiredPickupItemId(
                        "minecraft:glass"
                ).isEmpty(),
                "Unknown or non-dropping blocks must not invent pickup "
                        + "evidence"
        );
    }

    @Test
    void connectedClusterEntryPrefersPlayerHeightOverCanopySeed() {
        assertTrue(
                GatherVisibleBlockClusterSkill.targetHeightDistance(
                        64,
                        65
                )
                    < GatherVisibleBlockClusterSkill
                        .targetHeightDistance(64, 68),
                "A visible lower trunk must outrank a high model-selected "
                        + "seed when both belong to the same component"
        );
    }

    @Test
    void currentSupportIsNotASelectableMiningCandidate() {
        final VisibleBlockFace support = new VisibleBlockFace(
                new BlockCoordinate(0, 63, 0),
                BLOCK_ID,
                "up",
                new PerceptionVec3(0.5, 64.0, 0.5),
                1.62,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
        final var snapshots = frames(40, List.of(support));
        final var interaction =
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator();
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                new GatheringSkillTestFixtures.RecordingCoreActuator(),
                snapshots,
                interaction,
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                immediateCollectionPolicy()
        );
        final var requested =
                new GatherVisibleBlockClusterParameters(
                        DimensionRef.OVERWORLD,
                        new ObservedBlockTarget(
                                40,
                                0,
                                63,
                                0,
                                BlockFace.UP
                        ),
                        BLOCK_ID,
                        1,
                        4.0,
                        TOOL_ID
                );

        assertTrue(skill.preconditions(
                context(100, true, 0.0),
                requested
        ).isEmpty());
        skill.start(context(100, true, 0.0), requested);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(101, true, 0.0),
                        requested
                ).status()
        );
        assertTrue(
                interaction.mining.isEmpty(),
                "The gatherer must step away or replan instead of mining "
                        + "the block carrying its body"
        );
    }

    @Test
    void visibleConnectedTrunkBeatsHighModelSelectedSeed() {
        final List<VisibleBlockFace> trunk = List.of(
                visibleLog(1, 64),
                visibleLog(1, 65),
                visibleLog(1, 66),
                visibleLog(1, 67)
        );
        final var snapshots = frames(40, trunk);
        final var core =
                new GatheringSkillTestFixtures.RecordingCoreActuator();
        final var interaction =
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator();
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                core,
                snapshots,
                interaction,
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                immediateCollectionPolicy()
        );
        final var requested =
                new GatherVisibleBlockClusterParameters(
                        DimensionRef.OVERWORLD,
                        new ObservedBlockTarget(
                                40,
                                1,
                                67,
                                0,
                                BlockFace.WEST
                        ),
                        BLOCK_ID,
                        4,
                        8.0,
                        TOOL_ID
                );

        assertTrue(
                skill.preconditions(
                        context(100, false, 0.0),
                        requested
                ).isEmpty()
        );
        skill.start(context(100, false, 0.0), requested);
        skill.tick(context(101, false, 0.0), requested);
        skill.tick(context(102, false, 0.0), requested);

        assertEquals(1, interaction.mining.size());
        assertEquals(
                64,
                interaction.mining.getFirst().y(),
                "The body must open the visible trunk at player height "
                        + "instead of obeying a high canopy seed literally"
        );
    }

    @Test
    void currentCrosshairStartsMiningDespiteStaleSemanticLook() {
        final VisibleBlockFace target = log(1, 40);
        final var snapshots = GatheringSkillTestFixtures.withLook(
                frames(40, List.of(target)),
                new PerceptionVec3(-1.0, 0.0, 0.0)
        );
        final var core =
                new GatheringSkillTestFixtures.RecordingCoreActuator();
        final var interaction =
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator();
        final var interactionFrames =
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                );
        interactionFrames.setCrosshair(target);
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                core,
                snapshots,
                interaction,
                interactionFrames,
                new GatheringSkillTestFixtures.MutableInventory(),
                immediateCollectionPolicy()
        );
        final var requested = parameters(1);

        assertTrue(skill.preconditions(
                context(100, false, 0.0),
                requested
        ).isEmpty());
        skill.start(context(100, false, 0.0), requested);
        skill.tick(context(101, false, 0.0), requested);
        skill.tick(context(102, false, 0.0), requested);

        assertEquals(1, interaction.mining.size());
        assertEquals(
                target.block().x(),
                interaction.mining.getFirst().x()
        );
    }

    @Test
    void aimLeaseExpiresInsteadOfRemainingBusyForever() {
        final VisibleBlockFace target = log(1, 40);
        final var snapshots = GatheringSkillTestFixtures.withLook(
                frames(40, List.of(target)),
                new PerceptionVec3(-1.0, 0.0, 0.0)
        );
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                new GatheringSkillTestFixtures.RecordingCoreActuator(),
                snapshots,
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator(),
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                immediateCollectionPolicy()
        );
        final var requested = parameters(1);

        assertTrue(skill.preconditions(
                context(100, false, 0.0),
                requested
        ).isEmpty());
        skill.start(context(100, false, 0.0), requested);
        for (long tick = 101; tick <= 182; tick++) {
            skill.tick(context(tick, false, 0.0), requested);
        }

        assertTrue(
                skill.checkpoint(
                        context(182, false, 0.0),
                        requested
                ).payload().contains("\"unavailable\":1"),
                "An unreachable aim must release its target after a bounded "
                    + "lease"
        );
    }

    @Test
    void retainsFairAimPointWhileSemanticFanRefreshesAway() {
        final VisibleBlockFace target = log(1, 40);
        final var snapshots = GatheringSkillTestFixtures.withLook(
                frames(40, List.of(target)),
                new PerceptionVec3(-1.0, 0.0, 0.0)
        );
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                new GatheringSkillTestFixtures.RecordingCoreActuator(),
                snapshots,
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator(),
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                immediateCollectionPolicy()
        );
        final var requested = parameters(1);

        assertTrue(skill.preconditions(
                context(100, false, 0.0),
                requested
        ).isEmpty());
        skill.start(context(100, false, 0.0), requested);
        skill.tick(context(101, false, 0.0), requested);
        GatheringSkillTestFixtures.setInteractionBlocks(
                snapshots,
                List.of()
        );
        for (long tick = 102; tick <= 110; tick++) {
            skill.tick(context(tick, false, 0.0), requested);
        }

        final String checkpoint = skill.checkpoint(
                context(110, false, 0.0),
                requested
        ).payload();
        assertTrue(
                checkpoint.contains("\"phase\":\"AIMING\""),
                "A short semantic refresh gap must not restart the scan"
        );
        assertTrue(
                checkpoint.contains("\"target\":[1,64,0]"),
                "The retained aim must remain bound to the exact ray-proven "
                    + "block"
        );
    }

    @Test
    void distantVisibleResourceLooksDownForSupportThenWalksForward() {
        final VisibleBlockFace distant = log(6, 40);
        final var snapshots =
                GatheringSkillTestFixtures.withLook(
                        GatheringSkillTestFixtures.withNavigation(
                                frames(40, List.of(distant)),
                                new LocalNavSnapshot(
                                        DimensionRef.OVERWORLD,
                                        40,
                                        List.of()
                                )
                        ),
                        new PerceptionVec3(0.0, 0.0, 1.0)
                );
        final var core =
                new GatheringSkillTestFixtures.RecordingCoreActuator();
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                core,
                snapshots,
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator(),
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                immediateCollectionPolicy()
        );
        final var requested =
                new GatherVisibleBlockClusterParameters(
                        DimensionRef.OVERWORLD,
                        new ObservedBlockTarget(
                                40,
                                6,
                                64,
                                0,
                                BlockFace.WEST
                        ),
                        BLOCK_ID,
                        1,
                        16.0,
                        TOOL_ID
                );

        assertTrue(
                skill.preconditions(
                        context(100, false, 0.0),
                        requested
                ).isEmpty()
        );
        skill.start(context(100, false, 0.0), requested);
        skill.tick(context(101, false, 0.0), requested);
        skill.tick(context(102, false, 0.0), requested);
        for (long tick = 103; tick <= 122; tick++) {
            skill.tick(context(tick, false, 0.0), requested);
        }

        assertTrue(
                core.moves.isEmpty(),
                "Unknown floor must be observed before movement"
        );
        assertTrue(
                !core.looks.isEmpty(),
                "The body must look down to acquire fair support evidence"
        );
        assertTrue(
                skill.checkpoint(
                        context(122, false, 0.0),
                        requested
                ).payload().contains("\"approachSupportProbes\":1"),
                "Twenty body ticks over one semantic frame must consume only "
                        + "one support-observation probe"
        );
        assertTrue(
                skill.checkpoint(
                        context(122, false, 0.0),
                        requested
                ).payload().contains("\"unavailable\":0"),
                "A visible resource must remain bound while a fresh floor "
                        + "observation is pending"
        );

        GatheringSkillTestFixtures.withNavigation(
                snapshots,
                GatheringSkillTestFixtures.observedFloor(40)
        );
        skill.tick(context(123, false, 0.0), requested);

        assertEquals(1, core.moves.size());
        assertEquals(1.0, core.moves.getFirst().forward(), 1.0E-9);
        assertEquals(0.0, core.moves.getFirst().strafeLeft(), 1.0E-9);
    }

    @Test
    void minesAVisibleConnectedClusterAcrossFreshObservations()
            throws Exception {
        final var snapshots = frames(40, List.of(log(1, 40)));
        final var core =
                new GatheringSkillTestFixtures.RecordingCoreActuator();
        final var interaction =
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator();
        final var inventory =
                new GatheringSkillTestFixtures.MutableInventory();
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                core,
                snapshots,
                interaction,
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                inventory,
                immediateCollectionPolicy()
        );
        final var requested = parameters(2);

        assertTrue(
                skill.preconditions(context(100, false, 0.0), requested)
                        .isEmpty()
        );
        skill.start(context(100, false, 0.0), requested);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(101, false, 0.0), requested)
                        .status()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(102, false, 0.0), requested)
                        .status()
        );
        assertEquals(1, interaction.mining.size());

        final var next = frames(41, List.of(log(2, 41)));
        snapshots.core = next.core;
        snapshots.interaction = next.interaction;
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(103, false, 0.0), requested)
                        .status()
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(104, false, 0.0), requested)
                        .status()
        );
        assertEquals(2, interaction.mining.size());
        assertEquals(
                SkillResult.Status.COMPLETED,
                skill.result(context(104, false, 0.0), requested)
                        .status()
        );
        assertTrue(core.stops > 0);
        assertTrue(interaction.aborts > 0);
    }

    private static VisibleBlockFace visibleLog(
            final int x,
            final int y
    ) {
        return new VisibleBlockFace(
                new BlockCoordinate(x, y, 0),
                BLOCK_ID,
                "west",
                new PerceptionVec3(x, y + 0.5, 0.5),
                Math.hypot(x - 0.5, y + 0.5 - 65.62),
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
    }

    @Test
    void rejectsDangerFullInventoryAndExhaustedToolBeforeMutation() {
        final var dangerous = GatheringSkillTestFixtures.withDanger(
                frames(40, List.of(log(1, 40))),
                0.5
        );
        final var dangerSkill = skill(
                dangerous,
                new GatheringSkillTestFixtures.MutableInventory()
        );
        assertEquals(
                "gather_visible_block_cluster.danger_detected",
                dangerSkill.preconditions(
                                context(100, true, 0.5),
                                parameters(1)
                        )
                        .orElseThrow()
                        .code()
        );

        final var fullInventory =
                new GatheringSkillTestFixtures.MutableInventory();
        fullInventory.emptySlots = 0;
        final var fullSkill = skill(
                frames(40, List.of(log(1, 40))),
                fullInventory
        );
        assertEquals(
                "gather_visible_block_cluster.inventory_full",
                fullSkill.preconditions(
                                context(100, false, 0.0),
                                parameters(1)
                        )
                        .orElseThrow()
                        .code()
        );

        final var worn = GatheringSkillTestFixtures.withToolDamage(
                frames(40, List.of(log(1, 40))),
                248
        );
        final var wornSkill = skill(
                worn,
                new GatheringSkillTestFixtures.MutableInventory()
        );
        assertEquals(
                "gather_visible_block_cluster.tool_durability_reserve",
                wornSkill.preconditions(
                                context(100, false, 0.0),
                                parameters(1)
                        )
                        .orElseThrow()
                        .code()
        );
    }

    @Test
    void seedMustMatchTheExactFairObservationAndBlockType() {
        final var snapshots = frames(41, List.of(log(1, 41)));
        final var actuator =
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator();
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                new GatheringSkillTestFixtures.RecordingCoreActuator(),
                snapshots,
                actuator,
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                immediateCollectionPolicy()
        );

        assertEquals(
                "gather_visible_block_cluster.seed_not_visible",
                skill.preconditions(
                                context(100, false, 0.0),
                                parameters(1)
                        )
                        .orElseThrow()
                        .code()
        );
        assertTrue(
                actuator.mining.isEmpty(),
                "A rejected observation must never dispatch mining"
        );
    }

    @Test
    void networkDelayedDecisionRebindsTheSameCurrentlyVisibleSeed() {
        final var historical = frames(40, List.of(log(1, 40)));
        final var current = frames(41, List.of(log(1, 41)));
        final InteractionSkillFrame retained =
                historical.interaction;
        final InteractionSkillFrameSource history =
                new InteractionSkillFrameSource() {
                    @Override
                    public Optional<InteractionSkillFrame> current() {
                        return current.interactionCurrent();
                    }

                    @Override
                    public Optional<InteractionSkillFrame> atObservation(
                            final long revision
                    ) {
                        return revision == retained.observationRevision()
                                ? Optional.of(retained)
                                : Optional.empty();
                    }
                };
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                new GatheringSkillTestFixtures.RecordingCoreActuator(),
                current,
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator(),
                history,
                new GatheringSkillTestFixtures.MutableInventory(),
                immediateCollectionPolicy()
        );

        assertTrue(
                skill.preconditions(
                        context(100, false, 0.0),
                        parameters(1)
                ).isEmpty(),
                "A retained handle must rebind only to the same currently "
                    + "visible coordinate and block type"
        );
    }

    @Test
    void retainedSurveySeedStartsBoundedReacquisitionWithoutMining() {
        final var historical = frames(40, List.of(log(1, 40)));
        final var current = frames(41, List.of());
        final InteractionSkillFrame retained =
                historical.interaction;
        final InteractionSkillFrameSource history =
                new InteractionSkillFrameSource() {
                    @Override
                    public Optional<InteractionSkillFrame> current() {
                        return current.interactionCurrent();
                    }

                    @Override
                    public Optional<InteractionSkillFrame> atObservation(
                            final long revision
                    ) {
                        return revision == retained.observationRevision()
                                ? Optional.of(retained)
                                : Optional.empty();
                    }
                };
        final var interaction =
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator();
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                new GatheringSkillTestFixtures.RecordingCoreActuator(),
                current,
                interaction,
                history,
                new GatheringSkillTestFixtures.MutableInventory(),
                immediateCollectionPolicy()
        );

        assertTrue(
                skill.preconditions(
                        context(100, false, 0.0),
                        parameters(1)
                ).isEmpty(),
                "An exact retained survey ray may start reacquisition"
        );
        skill.start(
                context(100, false, 0.0),
                parameters(1)
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(101, false, 0.0),
                        parameters(1)
                ).status()
        );
        assertTrue(
                interaction.mining.isEmpty(),
                "A seed that is no longer visible must not be mined"
        );
    }

    @Test
    void woodGatheringWaitsForFairInventoryPickupEvidence() {
        final var snapshots = frames(40, List.of(log(1, 40)));
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                new GatheringSkillTestFixtures.RecordingCoreActuator(),
                snapshots,
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator(),
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                collectionPolicy(10)
        );
        final var requested = parameters(1);

        assertTrue(
            skill.preconditions(
                context(100, false, 0.0),
                requested
            ).isEmpty()
        );
        skill.start(context(100, false, 0.0), requested);
        assertEquals(
            SkillTickResult.Status.RUNNING,
            skill.tick(context(101, false, 0.0), requested)
                .status()
        );
        assertEquals(
            SkillTickResult.Status.RUNNING,
            skill.tick(context(102, false, 0.0), requested)
                .status(),
            "Breaking a log is not yet a successful gather"
        );

        final var pickedUp = withInventory(
            frames(41, List.of()),
            List.of(
                new InventoryItemSummary(TOOL_ID, 1),
                new InventoryItemSummary(BLOCK_ID, 1)
            )
        );
        snapshots.core = pickedUp.core;
        snapshots.interaction = pickedUp.interaction;
        assertEquals(
            SkillTickResult.Status.COMPLETED,
            skill.tick(context(103, false, 0.0), requested)
                .status()
        );
    }

    @Test
    void liveOwnedInventoryConfirmsPickupBeforeNextSemanticFrame() {
        final var snapshots = frames(40, List.of(log(1, 40)));
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                new GatheringSkillTestFixtures.RecordingCoreActuator(),
                snapshots,
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator(),
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                collectionPolicy(10)
        );
        final var requested = parameters(1);
        skill.start(context(100, false, 0.0), requested);
        skill.tick(context(101, false, 0.0), requested);
        skill.tick(context(102, false, 0.0), requested);

        GatheringSkillTestFixtures.withLiveCoreInventory(
                snapshots,
                List.of(
                        new InventoryItemSummary(TOOL_ID, 1),
                        new InventoryItemSummary(BLOCK_ID, 1)
                )
        );

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(103, false, 0.0), requested)
                        .status(),
                "The player's 20 TPS owned inventory must confirm pickup "
                        + "without waiting for a semantic ray refresh"
        );
    }

    @Test
    void aRegressedSnapshotCannotReuseAnEarlierLogAsNewPickupEvidence() {
        final var snapshots = frames(40, List.of(log(1, 40)));
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                new GatheringSkillTestFixtures.RecordingCoreActuator(),
                snapshots,
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator(),
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                collectionPolicy(10)
        );
        final var requested = parameters(2);
        skill.start(context(100, false, 0.0), requested);
        skill.tick(context(101, false, 0.0), requested);
        skill.tick(context(102, false, 0.0), requested);

        final List<InventoryItemSummary> firstPickup = List.of(
                new InventoryItemSummary(TOOL_ID, 1),
                new InventoryItemSummary(BLOCK_ID, 1)
        );
        final var firstCollected = withInventory(
                frames(41, List.of(log(2, 41))),
                firstPickup
        );
        GatheringSkillTestFixtures.withLiveCoreInventory(
                firstCollected,
                firstPickup
        );
        snapshots.core = firstCollected.core;
        snapshots.interaction = firstCollected.interaction;
        skill.tick(context(103, false, 0.0), requested);

        /*
         * Simulate a transient publication that is older than the latest
         * owned-inventory refresh while the next visible target is selected.
         * A per-block "greater than before" comparison could then count the
         * already-owned first log as the second block's pickup.
         */
        final var regressed = frames(42, List.of(log(2, 42)));
        snapshots.core = regressed.core;
        snapshots.interaction = regressed.interaction;
        skill.tick(context(104, false, 0.0), requested);
        skill.tick(context(105, false, 0.0), requested);

        final var stillOnlyFirstPickup = withInventory(
                frames(43, List.of()),
                firstPickup
        );
        GatheringSkillTestFixtures.withLiveCoreInventory(
                stillOnlyFirstPickup,
                firstPickup
        );
        snapshots.core = stillOnlyFirstPickup.core;
        snapshots.interaction = stillOnlyFirstPickup.interaction;
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(106, false, 0.0), requested)
                        .status(),
                "Every mined log must have cumulative owned-inventory "
                        + "evidence; an earlier pickup cannot be reused"
        );
        final SkillTickResult timedOut =
                skill.tick(context(115, false, 0.0), requested);
        assertEquals(SkillTickResult.Status.FAILED, timedOut.status());
        assertEquals(
                "gather_visible_block_cluster.drop_not_collected",
                timedOut.failure().orElseThrow().code()
        );
    }

    @Test
    void missingWoodDropFailsInsteadOfClaimingCompletion() {
        final var snapshots = frames(40, List.of(log(1, 40)));
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                new GatheringSkillTestFixtures.RecordingCoreActuator(),
                snapshots,
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator(),
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                collectionPolicy(10)
        );
        final var requested = parameters(1);
        skill.start(context(100, false, 0.0), requested);
        skill.tick(context(101, false, 0.0), requested);
        skill.tick(context(102, false, 0.0), requested);

        final var noPickup = frames(41, List.of());
        snapshots.core = noPickup.core;
        snapshots.interaction = noPickup.interaction;
        final SkillTickResult timedOut =
            skill.tick(context(112, false, 0.0), requested);
        assertEquals(SkillTickResult.Status.FAILED, timedOut.status());
        assertEquals(
            "gather_visible_block_cluster.drop_not_collected",
            timedOut.failure().orElseThrow().code()
        );
        final var debt = skill.uncollectedDropDebt().orElseThrow();
        assertEquals(
                new dev.mcai.companion.navigation.GridPos(1, 64, 0),
                debt.origin(),
                "The parent compound needs the last causally mined origin "
                        + "to recover its vanilla drop before exploring"
        );
        assertEquals(BLOCK_ID, debt.itemId());
        assertEquals(1, debt.requiredOwnedCount());
        assertEquals(0, debt.observedOwnedCount());
    }

    @Test
    void inaccessibleUpperDropIsDeferredWhileConnectedTrunkRemains() {
        final var snapshots = frames(40, List.of(log(1, 40)));
        final var interaction =
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator();
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                new GatheringSkillTestFixtures.RecordingCoreActuator(),
                snapshots,
                interaction,
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                collectionPolicy(10)
        );
        final var requested = parameters(2);
        skill.start(context(100, false, 0.0), requested);
        skill.tick(context(101, false, 0.0), requested);
        skill.tick(context(102, false, 0.0), requested);

        final var lowerConnectedLog =
                frames(41, List.of(log(2, 41)));
        snapshots.core = lowerConnectedLog.core;
        snapshots.interaction = lowerConnectedLog.interaction;
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(112, false, 0.0), requested)
                        .status(),
                "A temporarily unreachable drop must not abandon a "
                        + "connected trunk that can make it reachable"
        );
        skill.tick(context(113, false, 0.0), requested);
        skill.tick(context(114, false, 0.0), requested);
        assertEquals(
                2,
                interaction.mining.size(),
                "Gathering should continue through the connected component"
        );
    }

    @Test
    void verticallyOffsetDropInsideBodyDoesNotMakePlayerWalkAway() {
        final var snapshots = frames(40, List.of(log(1, 40)));
        final var core =
                new GatheringSkillTestFixtures.RecordingCoreActuator();
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                core,
                snapshots,
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator(),
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                collectionPolicy(10)
        );
        final var requested = parameters(1);
        skill.start(context(100, false, 0.0), requested);
        skill.tick(context(101, false, 0.0), requested);
        skill.tick(context(102, false, 0.0), requested);

        final var dropAtSameHorizontalPosition =
                withVisibleEntities(
                        frames(41, List.of()),
                        List.of(droppedLog(0.5, 64.8, 0.5))
                );
        snapshots.core = dropAtSameHorizontalPosition.core;
        snapshots.interaction =
                dropAtSameHorizontalPosition.interaction;

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(103, false, 0.0), requested)
                        .status()
        );
        assertTrue(
                core.moves.isEmpty(),
                "A vertically offset drop already inside the body must "
                        + "not make the gatherer walk away"
        );
    }

    @Test
    void vanillaPickupColumnDoesNotCauseAnUnnecessaryMicroStep() {
        final var snapshots = frames(40, List.of(log(1, 40)));
        final var core =
                new GatheringSkillTestFixtures.RecordingCoreActuator();
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                core,
                snapshots,
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator(),
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                collectionPolicy(10)
        );
        final var requested = parameters(1);
        skill.start(context(100, false, 0.0), requested);
        skill.tick(context(101, false, 0.0), requested);
        skill.tick(context(102, false, 0.0), requested);

        final var dropInsideOrdinaryArrivalRadius =
                withVisibleEntities(
                        frames(41, List.of()),
                        List.of(droppedLog(0.9, 64.8, 0.5))
                );
        snapshots.core = dropInsideOrdinaryArrivalRadius.core;
        snapshots.interaction =
                dropInsideOrdinaryArrivalRadius.interaction;

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(103, false, 0.0), requested)
                        .status()
        );
        assertEquals(
                0,
                core.moves.size(),
                "Player.tick inflates the pickup AABB by one horizontal "
                        + "block, so a drop 0.4 blocks away must be allowed "
                        + "to complete through vanilla collision"
        );
    }

    @Test
    void droppedItemHeightDoesNotBecomeAnUnsupportedWalkingGoal() {
        final var snapshots = frames(40, List.of(log(1, 40)));
        final var core =
                new GatheringSkillTestFixtures.RecordingCoreActuator();
        final var skill = new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                core,
                snapshots,
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator(),
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                new GatheringSkillTestFixtures.MutableInventory(),
                collectionPolicy(10)
        );
        final var requested = parameters(1);
        skill.start(context(100, false, 0.0), requested);
        skill.tick(context(101, false, 0.0), requested);
        skill.tick(context(102, false, 0.0), requested);

        final var elevatedDrop =
                withVisibleEntities(
                        frames(41, List.of()),
                        List.of(droppedLog(3.5, 65.0, 0.5))
                );
        snapshots.core = elevatedDrop.core;
        snapshots.interaction = elevatedDrop.interaction;

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(103, false, 0.0), requested)
                        .status()
        );
        for (long tick = 104; tick <= 106
                && core.moves.isEmpty(); tick++) {
            skill.tick(context(tick, false, 0.0), requested);
        }
        assertTrue(
                !core.moves.isEmpty(),
                "The route must target the player's standable layer, not "
                        + "the dropped entity's floating Y coordinate"
        );
    }

    private static GatherVisibleBlockClusterSkill skill(
            final GatheringSkillTestFixtures.SnapshotFrames snapshots,
            final GatheringSkillTestFixtures.MutableInventory inventory
    ) {
        return new GatherVisibleBlockClusterSkill(
                PLAYER_ID,
                new GatheringSkillTestFixtures.RecordingCoreActuator(),
                snapshots,
                new GatheringSkillTestFixtures
                        .RecordingInteractionActuator(),
                new GatheringSkillTestFixtures.InteractionFrames(
                        snapshots
                ),
                inventory,
                immediateCollectionPolicy()
        );
    }

    private static SkillContext context(
            final long tick,
            final boolean hardcore,
            final double risk
    ) {
        return new SkillContext(1, 40, tick, hardcore, true, risk);
    }
}
