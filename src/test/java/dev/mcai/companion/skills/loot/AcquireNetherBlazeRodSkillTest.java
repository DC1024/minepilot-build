package dev.mcai.companion.skills.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
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
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AcquireNetherBlazeRodSkillTest {
    private static final UUID PLAYER =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID BLAZE =
            UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID DROP =
            UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final long SESSION = 17;
    private static final long SEQUENCE = 40;

    @Test
    void parsesOnlyTheFixedNetherResourceContract() {
        final var parsed = LootSkillParameters.parseNetherBlazeRod(
                List.of(
                        argument("sampleSequence", "40"),
                        argument("observationId", "visible-0"),
                        argument("maximumTicks", "600")
                )
        );

        assertTrue(parsed.value().isPresent());
        assertEquals(
                new AcquireNetherBlazeRodParameters(
                        40,
                        "visible-0",
                        600
                ),
                parsed.value().orElseThrow()
        );
        assertTrue(LootSkillParameters.parseNetherBlazeRod(
                List.of(
                        argument("sampleSequence", "40"),
                        argument("observationId", "visible-0"),
                        argument("maximumTicks", "600"),
                        argument("expectedItemId", "minecraft:diamond")
                )
        ).value().isEmpty());
        assertTrue(LootSkillParameters.parseNetherBlazeRod(
                List.of(
                        argument("sampleSequence", "040"),
                        argument("observationId", "visible-0"),
                        argument("maximumTicks", "600")
                )
        ).value().isEmpty());
    }

    @Test
    void rejectsOtherDimensionsAndOtherVisibleTargets() {
        final VisibleEntity blaze = blaze();
        final Fixture overworld = fixture(
                DimensionRef.OVERWORLD,
                SEQUENCE,
                List.of(blaze),
                List.of()
        );
        final var parameters = parameters();

        assertEquals(
                AcquireNetherBlazeRodSkill.NAME + ".nether_required",
                overworld.skill.preconditions(
                        context(1),
                        parameters
                ).orElseThrow().code()
        );

        final VisibleEntity skeleton = new VisibleEntity(
                BLAZE,
                "minecraft:skeleton",
                blaze.position(),
                blaze.relativePosition(),
                blaze.distance(),
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        final Fixture wrongTarget = fixture(
                DimensionRef.NETHER,
                SEQUENCE,
                List.of(skeleton),
                List.of()
        );
        assertEquals(
                AcquireNetherBlazeRodSkill.NAME
                    + ".visible_blaze_required",
                wrongTarget.skill.preconditions(
                        context(1),
                        parameters
                ).orElseThrow().code()
        );

        final Fixture stale = fixture(
                DimensionRef.NETHER,
                SEQUENCE + 1,
                List.of(blaze),
                List.of()
        );
        assertEquals(
                AcquireNetherBlazeRodSkill.NAME
                    + ".stale_observation_id",
                stale.skill.preconditions(
                        context(1),
                        parameters
                ).orElseThrow().code()
        );
    }

    @Test
    void bindsOneObservedBlazeThenRequiresVisibleVanillaPickup() {
        final VisibleEntity blaze = blaze();
        final Fixture fixture = fixture(
                DimensionRef.NETHER,
                SEQUENCE,
                List.of(blaze),
                List.of()
        );
        final var parameters = parameters();

        assertTrue(fixture.skill.preconditions(
                context(1),
                parameters
        ).isEmpty());
        fixture.skill.start(context(1), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                fixture.skill.tick(context(2), parameters).status()
        );
        assertEquals(List.of(BLAZE), fixture.interactions.attacks);

        fixture.publish(
                SEQUENCE + 1,
                List.of(),
                List.of()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                fixture.skill.tick(context(3), parameters).status()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                fixture.skill.tick(context(64), parameters).status()
        );

        fixture.publish(
                SEQUENCE + 2,
                List.of(blazeRodDrop()),
                List.of()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                fixture.skill.tick(context(65), parameters).status()
        );

        fixture.publish(
                SEQUENCE + 3,
                List.of(),
                List.of(new InventoryItemSummary(
                        "minecraft:blaze_rod",
                        1
                ))
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                fixture.skill.tick(context(66), parameters).status()
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                fixture.skill.result(context(66), parameters).status()
        );
        assertEquals(
                1,
                fixture.interactions.attacks.size(),
                "The local resource macro must never switch targets"
        );
    }

    @Test
    void collectionSuppressesOnlyStaleHostileReacquisition() {
        final Fixture fixture = fixture(
                DimensionRef.NETHER,
                SEQUENCE,
                List.of(blaze()),
                List.of()
        );
        final var parameters = parameters();
        fixture.skill.start(context(1), parameters);
        fixture.skill.tick(context(2), parameters);

        fixture.publish(
                SEQUENCE + 1,
                List.of(blazeRodDrop()),
                List.of()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                fixture.skill.tick(context(3), parameters).status()
        );
        assertTrue(
                fixture.skill.managesVisibleHostileProximity(),
                "A clean pickup frame must not be preempted by a stale "
                    + "recently-killed hostile timestamp"
        );

        final VisibleEntity zombie = new VisibleEntity(
                UUID.fromString("10000000-0000-0000-0000-000000000004"),
                "minecraft:zombie",
                new PerceptionVec3(1.0, 1.0, 0.5),
                new PerceptionVec3(0.5, 0.0, 0.0),
                1.0,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
        fixture.publish(
                SEQUENCE + 2,
                List.of(blazeRodDrop(), zombie),
                List.of()
        );
        assertTrue(
                !fixture.skill.managesVisibleHostileProximity(),
                "A newly visible unrelated hostile must remain emergency "
                    + "owned during pickup"
        );
    }

    @Test
    void visibleRodStartsCollectionBeforeCombatLossGraceExpires() {
        final Fixture fixture = fixture(
                DimensionRef.NETHER,
                SEQUENCE,
                List.of(blaze()),
                List.of()
        );
        final var parameters = parameters();
        fixture.skill.start(context(1), parameters);
        fixture.skill.tick(context(2), parameters);

        /*
         * The attacked Blaze has disappeared and its ordinary item entity is
         * already in the same synchronized first-person observation. Do not
         * spend the combat child's target-loss grace turning away from the
         * death site; bind the visible drop immediately.
         */
        fixture.publish(
                SEQUENCE + 1,
                List.of(blazeRodDrop()),
                List.of()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                fixture.skill.tick(context(3), parameters).status()
        );
        assertTrue(
                fixture.skill.checkpoint(context(3), parameters)
                    .payload()
                    .contains("\"phase\":\"COLLECTING\""),
                "A visible expected drop must preempt combat-loss scanning"
        );

        fixture.publish(
                SEQUENCE + 2,
                List.of(),
                List.of(new InventoryItemSummary(
                        "minecraft:blaze_rod",
                        1
                ))
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                fixture.skill.tick(context(4), parameters).status()
        );
    }

    @Test
    void temporarilyInvisibleDropContinuesTowardItsLastObservedPosition() {
        final Fixture fixture = fixture(
                DimensionRef.NETHER,
                SEQUENCE,
                List.of(blaze()),
                List.of()
        );
        final var parameters = parameters();
        fixture.skill.start(context(1), parameters);
        fixture.skill.tick(context(2), parameters);

        fixture.publish(SEQUENCE + 1, List.of(), List.of());
        fixture.skill.tick(context(3), parameters);
        fixture.skill.tick(context(64), parameters);

        fixture.publish(
                SEQUENCE + 2,
                List.of(blazeRodDrop()),
                List.of()
        );
        fixture.skill.tick(context(65), parameters);
        fixture.skill.tick(context(66), parameters);
        fixture.skill.tick(context(67), parameters);
        final int movesBeforeOcclusion = fixture.core.moves;

        for (int tick = 68; tick <= 82; tick++) {
            fixture.publish(
                    SEQUENCE + tick - 65,
                    List.of(),
                    List.of()
            );
            assertEquals(
                    SkillTickResult.Status.RUNNING,
                    fixture.skill.tick(context(tick), parameters)
                        .status()
            );
        }
        assertTrue(
                fixture.core.moves > movesBeforeOcclusion,
                "A temporary FOV loss must not cancel the fair "
                    + "last-known-position approach"
        );

        fixture.publish(
                SEQUENCE + 18,
                List.of(),
                List.of(new InventoryItemSummary(
                        "minecraft:blaze_rod",
                        1
                ))
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                fixture.skill.tick(context(83), parameters).status()
        );
    }

    @Test
    void cancelsIfTheBodyLeavesTheNetherMidAttempt() {
        final VisibleEntity blaze = blaze();
        final Fixture fixture = fixture(
                DimensionRef.NETHER,
                SEQUENCE,
                List.of(blaze),
                List.of()
        );
        final var parameters = parameters();
        fixture.skill.start(context(1), parameters);

        fixture.dimension = DimensionRef.OVERWORLD;
        fixture.publish(
                SEQUENCE + 1,
                List.of(blaze),
                List.of()
        );
        final SkillTickResult result =
                fixture.skill.tick(context(2), parameters);

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                AcquireNetherBlazeRodSkill.NAME
                    + ".dimension_changed",
                fixture.skill.result(
                        context(2),
                        parameters
                ).failure().orElseThrow().code()
        );
    }

    private static AcquireNetherBlazeRodParameters parameters() {
        return new AcquireNetherBlazeRodParameters(
                SEQUENCE,
                "visible-0",
                600
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }

    private static SkillArgument argument(
            final String name,
            final String value
    ) {
        return new SkillArgument(name, value);
    }

    private static VisibleEntity blaze() {
        return new VisibleEntity(
                BLAZE,
                "minecraft:blaze",
                new PerceptionVec3(2.5, 1.0, 0.5),
                new PerceptionVec3(2.0, 0.0, 0.0),
                2.0,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
    }

    private static VisibleEntity blazeRodDrop() {
        return new VisibleEntity(
                DROP,
                "minecraft:item",
                new PerceptionVec3(2.0, 1.0, 0.5),
                new PerceptionVec3(1.5, 0.0, 0.0),
                1.5,
                false,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                Map.of("itemId", "minecraft:blaze_rod")
        );
    }

    private static Fixture fixture(
            final DimensionRef dimension,
            final long sequence,
            final List<VisibleEntity> entities,
            final List<InventoryItemSummary> inventory
    ) {
        final MutableCoreFrames coreFrames =
                new MutableCoreFrames();
        final MutableInteractionFrames interactionFrames =
                new MutableInteractionFrames();
        final RecordingInteractions interactions =
                new RecordingInteractions();
        final AcceptedCoreActuator core =
                new AcceptedCoreActuator();
        final Fixture fixture = new Fixture(
                dimension,
                coreFrames,
                interactionFrames,
                interactions,
                core,
                new AcquireNetherBlazeRodSkill(
                        PLAYER,
                        core,
                        coreFrames,
                        interactions,
                        interactionFrames
                )
        );
        fixture.publish(sequence, entities, inventory);
        return fixture;
    }

    private static CoreSkillFrame coreFrame(
            final DimensionRef dimension,
            final long sequence,
            final List<VisibleEntity> entities,
            final List<InventoryItemSummary> inventory
    ) {
        final PerceptionVec3 look = entities.isEmpty()
                ? new PerceptionVec3(1.0, 0.0, 0.0)
                : entities.getFirst().position()
                    .add(new PerceptionVec3(0.0, 1.0, 0.0))
                    .subtract(new PerceptionVec3(0.5, 2.62, 0.5))
                    .normalized();
        return new CoreSkillFrame(
                PLAYER,
                dimension,
                100,
                sequence,
                new PerceptionVec3(0.5, 1.0, 0.5),
                new PerceptionVec3(0.5, 2.62, 0.5),
                look,
                true,
                false,
                0.0,
                floor(dimension, sequence),
                List.of(),
                20.0F,
                20.0F,
                20,
                inventory,
                new HeldItemSummary(
                        "minecraft:diamond_sword",
                        1,
                        0,
                        1_561
                ),
                new HeldItemSummary(
                        "minecraft:shield",
                        1,
                        0,
                        336
                ),
                entities,
                List.of()
        );
    }

    private static InteractionSkillFrame interactionFrame(
            final DimensionRef dimension,
            final long sequence,
            final List<VisibleEntity> entities,
            final List<InventoryItemSummary> inventory
    ) {
        return new InteractionSkillFrame(
                PLAYER,
                dimension,
                100,
                100,
                sequence,
                SESSION,
                new HeldItemSummary(
                        "minecraft:diamond_sword",
                        1,
                        0,
                        1_561
                ),
                new HeldItemSummary(
                        "minecraft:shield",
                        1,
                        0,
                        336
                ),
                entities,
                List.of(),
                inventory
        );
    }

    private static LocalNavSnapshot floor(
            final DimensionRef dimension,
            final long revision
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        for (int x = -2; x <= 3; x++) {
            for (int z = -2; z <= 2; z++) {
                voxels.add(new ObservedVoxel(
                        new GridPos(x, 0, z),
                        VoxelKind.SOLID,
                        0.0,
                        revision,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.STURDY_FULL_TOP
                ));
                voxels.add(new ObservedVoxel(
                        new GridPos(x, 1, z),
                        VoxelKind.AIR,
                        0.0,
                        revision,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ));
                voxels.add(new ObservedVoxel(
                        new GridPos(x, 2, z),
                        VoxelKind.AIR,
                        0.0,
                        revision,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ));
            }
        }
        return new LocalNavSnapshot(dimension, revision, voxels);
    }

    private static final class Fixture {
        private DimensionRef dimension;
        private final MutableCoreFrames coreFrames;
        private final MutableInteractionFrames interactionFrames;
        private final RecordingInteractions interactions;
        private final AcceptedCoreActuator core;
        private final AcquireNetherBlazeRodSkill skill;

        private Fixture(
                final DimensionRef dimension,
                final MutableCoreFrames coreFrames,
                final MutableInteractionFrames interactionFrames,
                final RecordingInteractions interactions,
                final AcceptedCoreActuator core,
                final AcquireNetherBlazeRodSkill skill
        ) {
            this.dimension = dimension;
            this.coreFrames = coreFrames;
            this.interactionFrames = interactionFrames;
            this.interactions = interactions;
            this.core = core;
            this.skill = skill;
        }

        private void publish(
                final long sequence,
                final List<VisibleEntity> entities,
                final List<InventoryItemSummary> inventory
        ) {
            coreFrames.frame = coreFrame(
                    dimension,
                    sequence,
                    entities,
                    inventory
            );
            interactionFrames.frame = interactionFrame(
                    dimension,
                    sequence,
                    entities,
                    inventory
            );
        }
    }

    private static final class MutableCoreFrames
            implements CoreSkillFrameSource {
        private CoreSkillFrame frame;

        @Override
        public Optional<CoreSkillFrame> current() {
            return Optional.ofNullable(frame);
        }
    }

    private static final class MutableInteractionFrames
            implements InteractionSkillFrameSource {
        private InteractionSkillFrame frame;

        @Override
        public Optional<InteractionSkillFrame> current() {
            return Optional.ofNullable(frame);
        }
    }

    private static final class AcceptedCoreActuator
            implements CoreSkillActuator {
        private int moves;

        @Override
        public ActionOutcome move(final MovementIntent intent) {
            moves++;
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome look(final LookIntent intent) {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome jump() {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome stop() {
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome useMainHandOn(
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome releaseUse() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
    }

    private static final class RecordingInteractions
            implements InteractionSkillActuator {
        private final List<UUID> attacks = new ArrayList<>();

        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(SESSION);
        }

        @Override
        public ActionOutcome beginMining(
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }

        @Override
        public ActionOutcome continueMining() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome abortMining() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome useOnBlock(
                final ActionHand hand,
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }

        @Override
        public ActionOutcome attack(final UUID entityId) {
            attacks.add(entityId);
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public OptionalDouble attackStrengthScale() {
            return OptionalDouble.of(1.0);
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome continueUsing(
                final ActionHand hand
        ) {
            return ActionOutcome.IN_PROGRESS;
        }

        @Override
        public ActionOutcome releaseUse() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
    }
}
