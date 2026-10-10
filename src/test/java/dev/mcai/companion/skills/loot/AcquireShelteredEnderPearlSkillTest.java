package dev.mcai.companion.skills.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillRegistry;
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

final class AcquireShelteredEnderPearlSkillTest {
    private static final UUID PLAYER =
            UUID.fromString("21000000-0000-0000-0000-000000000001");
    private static final UUID ENDERMAN =
            UUID.fromString("21000000-0000-0000-0000-000000000002");
    private static final UUID NEW_PEARL =
            UUID.fromString("21000000-0000-0000-0000-000000000003");
    private static final UUID OLD_PEARL =
            UUID.fromString("21000000-0000-0000-0000-000000000004");
    private static final long SESSION = 23;
    private static final long SEQUENCE = 70;

    @Test
    void registersTheBoundedPublicResourceContract() {
        final MutableCoreFrames coreFrames =
                new MutableCoreFrames();
        final MutableInteractionFrames interactionFrames =
                new MutableInteractionFrames();
        final RecordingCore core = new RecordingCore();
        final RecordingInteractions interactions =
                new RecordingInteractions();
        final SkillRegistry registry = LootSkills.registerAll(
                new SkillRegistry(),
                PLAYER,
                core,
                coreFrames,
                interactions,
                interactionFrames
        );

        assertTrue(registry.contains(
                LootSkills.ACQUIRE_SHELTERED_ENDER_PEARL
        ));
        assertTrue(registry.contains(
                LootSkills.HUNT_OBSERVED_FOOD_ANIMAL
        ));
        assertTrue(registry.contains(
                LootSkills.SECURE_VISIBLE_FOOD_RESERVE
        ));
        assertTrue(
                LootSkills.plannerGuide().contains(
                        "observed 3x3"
                )
        );
    }

    @Test
    void rejectsAnythingExceptAReachableVisibleHostileEnderman() {
        final Fixture wrongType = fixture(
                List.of(entity(
                        ENDERMAN,
                        "minecraft:zombie",
                        2.0,
                        true,
                        Map.of()
                )),
                true,
                List.of(),
                sword()
        );
        assertEquals(
                AcquireShelteredEnderPearlSkill.NAME
                    + ".visible_hostile_enderman_required",
                wrongType.skill.preconditions(
                        context(1),
                        parameters("visible-0")
                ).orElseThrow().code()
        );

        final Fixture distant = fixture(
                List.of(entity(
                        ENDERMAN,
                        "minecraft:enderman",
                        4.0,
                        true,
                        Map.of()
                )),
                true,
                List.of(),
                sword()
        );
        assertEquals(
                AcquireShelteredEnderPearlSkill.NAME
                    + ".enderman_out_of_shelter_reach",
                distant.skill.preconditions(
                        context(1),
                        parameters("visible-0")
                ).orElseThrow().code()
        );

        final Fixture stale = fixture(
                List.of(enderman()),
                true,
                List.of(),
                sword()
        );
        assertEquals(
                AcquireShelteredEnderPearlSkill.NAME
                    + ".stale_observation_id",
                stale.skill.preconditions(
                        context(1),
                        new AcquireShelteredEnderPearlParameters(
                                SEQUENCE + 1,
                                "visible-0",
                                600
                        )
                ).orElseThrow().code()
        );
    }

    @Test
    void requiresACompleteObservedTwoBlockRoofAndMeleeWeapon() {
        final Fixture noRoof = fixture(
                List.of(enderman()),
                false,
                List.of(),
                sword()
        );
        assertEquals(
                AcquireShelteredEnderPearlSkill.NAME
                    + ".observed_two_block_roof_required",
                noRoof.skill.preconditions(
                        context(1),
                        parameters("visible-0")
                ).orElseThrow().code()
        );

        final Fixture noWeapon = fixture(
                List.of(enderman()),
                true,
                List.of(),
                HeldItemSummary.empty()
        );
        assertEquals(
                AcquireShelteredEnderPearlSkill.NAME
                    + ".melee_weapon_required",
                noWeapon.skill.preconditions(
                        context(1),
                        parameters("visible-0")
                ).orElseThrow().code()
        );
    }

    @Test
    void allowsOnlyTheBoundEndermanProximityRiskUnderTheRoof() {
        final DangerSignal hostileProximity = danger(
                DangerKind.HOSTILE_PROXIMITY,
                0.75,
                PerceptionProvenance.PROXIMITY_THREAT
        );
        final Fixture allowed = fixture(
                List.of(enderman()),
                true,
                List.of(),
                sword()
        );
        allowed.publishDanger(
                List.of(enderman()),
                List.of(hostileProximity),
                0.75
        );
        assertTrue(allowed.skill.preconditions(
                context(1, 0.75),
                parameters("visible-0")
        ).isEmpty());
        assertEquals(
                1.0,
                allowed.skill.hardcoreRiskThresholdOverride(
                        context(1, 0.75),
                        parameters("visible-0")
                ).orElseThrow()
        );

        final Fixture contact = fixture(
                List.of(enderman()),
                true,
                List.of(),
                sword()
        );
        contact.publishDanger(
                List.of(enderman()),
                List.of(danger(
                        DangerKind.THREAT_CONTACT,
                        1.0,
                        PerceptionProvenance.PHYSICAL_CONTACT
                )),
                1.0
        );
        assertTrue(
                contact.skill.hardcoreRiskThresholdOverride(
                        context(1, 1.0),
                        parameters("visible-0")
                ).isEmpty()
        );
        assertEquals(
                AcquireShelteredEnderPearlSkill.NAME
                    + ".contact_danger",
                contact.skill.preconditions(
                        context(1, 1.0),
                        parameters("visible-0")
                ).orElseThrow().code()
        );

        final Fixture projectile = fixture(
                List.of(enderman()),
                true,
                List.of(),
                sword()
        );
        projectile.publishDanger(
                List.of(enderman()),
                List.of(danger(
                        DangerKind.PROJECTILE_PROXIMITY,
                        0.75,
                        PerceptionProvenance.PROXIMITY_THREAT
                )),
                0.75
        );
        assertEquals(
                AcquireShelteredEnderPearlSkill.NAME
                    + ".projectile_danger",
                projectile.skill.preconditions(
                        context(1, 0.75),
                        parameters("visible-0")
                ).orElseThrow().code()
        );

        final VisibleEntity zombie = entity(
                UUID.fromString(
                        "21000000-0000-0000-0000-000000000005"
                ),
                "minecraft:zombie",
                2.75,
                true,
                Map.of("interactionLineClear", "true")
        );
        final Fixture otherHostile = fixture(
                List.of(enderman(), zombie),
                true,
                List.of(),
                sword()
        );
        otherHostile.publishDanger(
                List.of(enderman(), zombie),
                List.of(hostileProximity),
                0.75
        );
        assertEquals(
                AcquireShelteredEnderPearlSkill.NAME
                    + ".other_hostile_visible",
                otherHostile.skill.preconditions(
                        context(1, 0.75),
                        parameters("visible-0")
                ).orElseThrow().code()
        );

        final Fixture onFire = fixture(
                List.of(enderman()),
                true,
                List.of(),
                sword()
        );
        onFire.publishDanger(
                List.of(enderman()),
                List.of(danger(
                        DangerKind.ON_FIRE,
                        1.0,
                        PerceptionProvenance.BODY_HAZARD
                )),
                1.0
        );
        assertEquals(
                AcquireShelteredEnderPearlSkill.NAME
                    + ".body_hazard",
                onFire.skill.preconditions(
                        context(1, 1.0),
                        parameters("visible-0")
                ).orElseThrow().code()
        );
    }

    @Test
    void keepsCombatStationaryThenCollectsOnlyANewVisiblePearl() {
        final Fixture fixture = fixture(
                List.of(enderman()),
                true,
                List.of(),
                sword()
        );
        final var parameters = parameters("visible-0");

        assertTrue(fixture.skill.preconditions(
                context(1),
                parameters
        ).isEmpty());
        fixture.skill.start(context(1), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                fixture.skill.tick(context(2), parameters).status()
        );
        assertEquals(List.of(ENDERMAN), fixture.interactions.attacks);
        assertEquals(0, fixture.core.nonZeroMoves);

        fixture.publish(
                SEQUENCE + 1,
                List.of(pearl(NEW_PEARL)),
                true,
                List.of(),
                sword()
        );
        fixture.skill.tick(context(3), parameters);
        fixture.skill.tick(context(33), parameters);
        assertEquals(
                0,
                fixture.core.nonZeroMoves,
                "A visible pearl must not unlock movement until the "
                    + "combat child has entered its drop phase"
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                fixture.skill.tick(context(34), parameters).status()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                fixture.skill.tick(context(35), parameters).status()
        );
        assertTrue(
                fixture.core.nonZeroMoves > 0,
                "Only a newly observed pearl may release pickup movement"
        );

        fixture.publish(
                SEQUENCE + 2,
                List.of(),
                true,
                List.of(new InventoryItemSummary(
                        "minecraft:ender_pearl",
                        1
                )),
                sword()
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                fixture.skill.tick(context(36), parameters).status()
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                fixture.skill.result(context(36), parameters).status()
        );
        assertEquals(
                1,
                fixture.interactions.attacks.size(),
                "The resource attempt must not switch hostile targets"
        );
    }

    @Test
    void staleProximityAfterTheKillDoesNotTerminateDropHandoff() {
        final DangerSignal staleProximity = danger(
                DangerKind.HOSTILE_PROXIMITY,
                0.75,
                PerceptionProvenance.PROXIMITY_THREAT
        );
        final Fixture fixture = fixture(
                List.of(enderman()),
                true,
                List.of(),
                sword()
        );
        final var parameters = parameters("visible-0");
        fixture.skill.start(context(1), parameters);
        fixture.skill.tick(context(2), parameters);
        fixture.publishDanger(
                List.of(),
                List.of(staleProximity),
                0.75
        );

        fixture.skill.tick(context(3, 0.75), parameters);
        fixture.skill.tick(context(33, 0.75), parameters);

        assertTrue(
                !fixture.skill.managesVisibleHostileProximity(),
                "The emergency lane must still own proximity while "
                    + "the combat child waits for its drop"
        );
        assertTrue(
                !fixture.skill.managesPhysicalContactThreats(),
                "Physical contact must remain emergency-owned"
        );
        assertEquals(
                1.0,
                fixture.skill.hardcoreRiskThresholdOverride(
                        context(34, 0.75),
                        parameters
                ).orElseThrow(),
                "A roof-safe distance-only residue must not terminate "
                    + "the causal drop handoff"
        );
    }

    @Test
    void excludesPearlsThatWereAlreadyVisibleBeforeCombat() {
        final Fixture fixture = fixture(
                List.of(pearl(OLD_PEARL), enderman()),
                true,
                List.of(),
                sword()
        );
        final var parameters = parameters("visible-1");
        fixture.skill.start(context(1), parameters);
        fixture.skill.tick(context(2), parameters);

        fixture.publish(
                SEQUENCE + 1,
                List.of(pearl(OLD_PEARL)),
                true,
                List.of(),
                sword()
        );
        fixture.skill.tick(context(3), parameters);
        fixture.skill.tick(context(33), parameters);
        final SkillTickResult timedOutScan =
                fixture.skill.tick(context(134), parameters);

        assertEquals(
                SkillTickResult.Status.FAILED,
                timedOutScan.status()
        );
        assertEquals(
                0,
                fixture.core.nonZeroMoves,
                "A pre-combat pearl must never release pickup movement"
        );
    }

    @Test
    void rejectsAnInventoryIncrementBeforeAVisibleDropIsAuthorized() {
        final Fixture fixture = fixture(
                List.of(enderman()),
                true,
                List.of(),
                sword()
        );
        final var parameters = parameters("visible-0");
        fixture.skill.start(context(1), parameters);
        fixture.skill.tick(context(2), parameters);

        fixture.publish(
                SEQUENCE + 1,
                List.of(),
                true,
                List.of(new InventoryItemSummary(
                        "minecraft:ender_pearl",
                        1
                )),
                sword()
        );
        final SkillTickResult result =
                fixture.skill.tick(context(3), parameters);

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                AcquireShelteredEnderPearlSkill.NAME
                    + ".unverified_pearl_increment",
                fixture.skill.result(
                        context(3),
                        parameters
                ).failure().orElseThrow().code()
        );
        assertEquals(0, fixture.core.nonZeroMoves);
    }

    @Test
    void acceptsOnlyTheKilledTargetsPostInventoryPickupReceipt() {
        final MutablePickupReceipts receipts =
                new MutablePickupReceipts();
        final Fixture fixture = fixture(
                List.of(enderman()),
                true,
                List.of(),
                sword(),
                receipts
        );
        final var parameters = parameters("visible-0");
        fixture.skill.start(context(1), parameters);
        fixture.skill.tick(context(2), parameters);

        receipts.publish(new LootPickupReceiptSource.Receipt(
                1L,
                PLAYER,
                ENDERMAN,
                NEW_PEARL,
                "minecraft:ender_pearl",
                DimensionRef.OVERWORLD,
                1
        ));
        fixture.publish(
                SEQUENCE + 1,
                List.of(),
                true,
                List.of(new InventoryItemSummary(
                        "minecraft:ender_pearl",
                        1
                )),
                sword()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                fixture.skill.tick(
                        context(3),
                        parameters
                ).status(),
                "A causal post-inventory receipt must survive the "
                    + "semantic drop-observation race"
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                fixture.skill.tick(
                        context(33),
                        parameters
                ).status()
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                fixture.skill.result(
                        context(33),
                        parameters
                ).status()
        );
    }

    @Test
    void rejectsAPickupReceiptFromADifferentVictim() {
        final MutablePickupReceipts receipts =
                new MutablePickupReceipts();
        final Fixture fixture = fixture(
                List.of(enderman()),
                true,
                List.of(),
                sword(),
                receipts
        );
        final var parameters = parameters("visible-0");
        fixture.skill.start(context(1), parameters);
        fixture.skill.tick(context(2), parameters);

        receipts.publish(new LootPickupReceiptSource.Receipt(
                1L,
                PLAYER,
                OLD_PEARL,
                NEW_PEARL,
                "minecraft:ender_pearl",
                DimensionRef.OVERWORLD,
                1
        ));
        fixture.publish(
                SEQUENCE + 1,
                List.of(),
                true,
                List.of(new InventoryItemSummary(
                        "minecraft:ender_pearl",
                        1
                )),
                sword()
        );
        final SkillTickResult result =
                fixture.skill.tick(context(3), parameters);

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                AcquireShelteredEnderPearlSkill.NAME
                    + ".unverified_pearl_increment",
                fixture.skill.result(
                        context(3),
                        parameters
                ).failure().orElseThrow().code()
        );
    }

    @Test
    void abortsImmediatelyIfTheObservedRoofStopsBeingSafe() {
        final Fixture fixture = fixture(
                List.of(enderman()),
                true,
                List.of(),
                sword()
        );
        final var parameters = parameters("visible-0");
        fixture.skill.start(context(1), parameters);

        fixture.publish(
                SEQUENCE + 1,
                List.of(enderman()),
                false,
                List.of(),
                sword()
        );
        final SkillTickResult result =
                fixture.skill.tick(context(2), parameters);

        assertEquals(SkillTickResult.Status.FAILED, result.status());
        assertEquals(
                AcquireShelteredEnderPearlSkill.NAME
                    + ".observed_two_block_roof_required",
                fixture.skill.result(
                        context(2),
                        parameters
                ).failure().orElseThrow().code()
        );
        assertEquals(0, fixture.core.nonZeroMoves);
    }

    private static Fixture fixture(
            final List<VisibleEntity> entities,
            final boolean completeRoof,
            final List<InventoryItemSummary> inventory,
            final HeldItemSummary mainHand
    ) {
        return fixture(
                entities,
                completeRoof,
                inventory,
                mainHand,
                LootPickupReceiptSource.none()
        );
    }

    private static Fixture fixture(
            final List<VisibleEntity> entities,
            final boolean completeRoof,
            final List<InventoryItemSummary> inventory,
            final HeldItemSummary mainHand,
            final LootPickupReceiptSource pickupReceipts
    ) {
        final MutableCoreFrames coreFrames =
                new MutableCoreFrames();
        final MutableInteractionFrames interactionFrames =
                new MutableInteractionFrames();
        final RecordingCore core = new RecordingCore();
        final RecordingInteractions interactions =
                new RecordingInteractions();
        final Fixture fixture = new Fixture(
                coreFrames,
                interactionFrames,
                core,
                interactions,
                new AcquireShelteredEnderPearlSkill(
                        PLAYER,
                        core,
                        coreFrames,
                        interactions,
                        interactionFrames,
                        pickupReceipts
                )
        );
        fixture.publish(
                SEQUENCE,
                entities,
                completeRoof,
                inventory,
                mainHand
        );
        return fixture;
    }

    private static CoreSkillFrame coreFrame(
            final long sequence,
            final List<VisibleEntity> entities,
            final boolean completeRoof,
            final List<InventoryItemSummary> inventory,
            final HeldItemSummary mainHand
    ) {
        return coreFrame(
                sequence,
                entities,
                completeRoof,
                inventory,
                mainHand,
                0.0,
                List.of()
        );
    }

    private static CoreSkillFrame coreFrame(
            final long sequence,
            final List<VisibleEntity> entities,
            final boolean completeRoof,
            final List<InventoryItemSummary> inventory,
            final HeldItemSummary mainHand,
            final double danger,
            final List<DangerSignal> dangerSignals
    ) {
        final PerceptionVec3 eye =
                new PerceptionVec3(0.5, 2.62, 0.5);
        final PerceptionVec3 look = entities.isEmpty()
                ? new PerceptionVec3(1.0, 0.0, 0.0)
                : entities.getFirst().position()
                    .add(new PerceptionVec3(0.0, 1.0, 0.0))
                    .subtract(eye)
                    .normalized();
        return new CoreSkillFrame(
                PLAYER,
                DimensionRef.OVERWORLD,
                100,
                sequence,
                new PerceptionVec3(0.5, 1.0, 0.5),
                eye,
                look,
                true,
                false,
                danger,
                shelter(sequence, completeRoof, danger),
                List.of(),
                20.0F,
                20.0F,
                20,
                inventory,
                mainHand,
                new HeldItemSummary(
                        "minecraft:shield",
                        1,
                        0,
                        336
                ),
                entities,
                dangerSignals
        );
    }

    private static InteractionSkillFrame interactionFrame(
            final long sequence,
            final List<VisibleEntity> entities,
            final List<InventoryItemSummary> inventory,
            final HeldItemSummary mainHand
    ) {
        return new InteractionSkillFrame(
                PLAYER,
                DimensionRef.OVERWORLD,
                100,
                100,
                sequence,
                SESSION,
                mainHand,
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

    private static LocalNavSnapshot shelter(
            final long revision,
            final boolean completeRoof,
            final double bodyDanger
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        for (int x = -3; x <= 4; x++) {
            for (int z = -3; z <= 3; z++) {
                voxels.add(voxel(x, 0, z, VoxelKind.SOLID, revision));
                final double danger =
                        x == 0 && z == 0 ? bodyDanger : 0.0;
                voxels.add(voxel(
                        x,
                        1,
                        z,
                        VoxelKind.AIR,
                        danger,
                        revision
                ));
                voxels.add(voxel(
                        x,
                        2,
                        z,
                        VoxelKind.AIR,
                        danger,
                        revision
                ));
            }
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (completeRoof || x != 1 || z != 1) {
                    voxels.add(voxel(
                            x,
                            3,
                            z,
                            VoxelKind.SOLID,
                            revision
                    ));
                }
            }
        }
        return new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                revision,
                voxels
        );
    }

    private static ObservedVoxel voxel(
            final int x,
            final int y,
            final int z,
            final VoxelKind kind,
            final long revision
    ) {
        return voxel(x, y, z, kind, 0.0, revision);
    }

    private static ObservedVoxel voxel(
            final int x,
            final int y,
            final int z,
            final VoxelKind kind,
            final double danger,
            final long revision
    ) {
        return new ObservedVoxel(
                new GridPos(x, y, z),
                kind,
                danger,
                revision,
                kind == VoxelKind.AIR
                        ? OccupancyEvidence.MULTI_RAY_CLEAR
                        : OccupancyEvidence.SURFACE_HIT,
                kind.supportsWeight()
                        ? TopSupportAffordance.STURDY_FULL_TOP
                        : TopSupportAffordance.UNKNOWN
        );
    }

    private static VisibleEntity enderman() {
        return entity(
                ENDERMAN,
                "minecraft:enderman",
                2.5,
                true,
                Map.of("interactionLineClear", "true")
        );
    }

    private static VisibleEntity pearl(final UUID id) {
        return entity(
                id,
                "minecraft:item",
                2.0,
                false,
                Map.of("itemId", "minecraft:ender_pearl")
        );
    }

    private static VisibleEntity entity(
            final UUID id,
            final String type,
            final double x,
            final boolean hostile,
            final Map<String, String> properties
    ) {
        final PerceptionVec3 position =
                new PerceptionVec3(x, 1.0, 0.5);
        final PerceptionVec3 relative = position.subtract(
                new PerceptionVec3(0.5, 1.0, 0.5)
        );
        return new VisibleEntity(
                id,
                type,
                position,
                relative,
                relative.length(),
                hostile,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                properties
        );
    }

    private static HeldItemSummary sword() {
        return new HeldItemSummary(
                "minecraft:iron_sword",
                1,
                0,
                250
        );
    }

    private static DangerSignal danger(
            final DangerKind kind,
            final double severity,
            final PerceptionProvenance provenance
    ) {
        return new DangerSignal(
                kind,
                severity,
                kind == DangerKind.THREAT_CONTACT ? 0.1 : 3.0,
                kind == DangerKind.THREAT_CONTACT
                        ? Optional.of(
                            new PerceptionVec3(1.0, 0.0, 0.0)
                        )
                        : Optional.empty(),
                provenance
        );
    }

    private static AcquireShelteredEnderPearlParameters parameters(
            final String observationId
    ) {
        return new AcquireShelteredEnderPearlParameters(
                SEQUENCE,
                observationId,
                600
        );
    }

    private static SkillContext context(final long tick) {
        return context(tick, 0.0);
    }

    private static SkillContext context(
            final long tick,
            final double risk
    ) {
        return new SkillContext(1, 1, tick, true, true, risk);
    }

    private static final class Fixture {
        private final MutableCoreFrames coreFrames;
        private final MutableInteractionFrames interactionFrames;
        private final RecordingCore core;
        private final RecordingInteractions interactions;
        private final AcquireShelteredEnderPearlSkill skill;

        private Fixture(
                final MutableCoreFrames coreFrames,
                final MutableInteractionFrames interactionFrames,
                final RecordingCore core,
                final RecordingInteractions interactions,
                final AcquireShelteredEnderPearlSkill skill
        ) {
            this.coreFrames = coreFrames;
            this.interactionFrames = interactionFrames;
            this.core = core;
            this.interactions = interactions;
            this.skill = skill;
        }

        private void publish(
                final long sequence,
                final List<VisibleEntity> entities,
                final boolean completeRoof,
                final List<InventoryItemSummary> inventory,
                final HeldItemSummary mainHand
        ) {
            coreFrames.frame = coreFrame(
                    sequence,
                    entities,
                    completeRoof,
                    inventory,
                    mainHand
            );
            interactionFrames.frame = interactionFrame(
                    sequence,
                    entities,
                    inventory,
                    mainHand
            );
        }

        private void publishDanger(
                final List<VisibleEntity> entities,
                final List<DangerSignal> dangerSignals,
                final double danger
        ) {
            coreFrames.frame = coreFrame(
                    SEQUENCE,
                    entities,
                    true,
                    List.of(),
                    sword(),
                    danger,
                    dangerSignals
            );
            interactionFrames.frame = interactionFrame(
                    SEQUENCE,
                    entities,
                    List.of(),
                    sword()
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

    private static final class MutablePickupReceipts
            implements LootPickupReceiptSource {
        private final List<Receipt> receipts =
                new ArrayList<>();
        private long sequence;

        @Override
        public long latestSequence() {
            return sequence;
        }

        @Override
        public List<Receipt> receiptsAfter(
                final UUID playerId,
                final long exclusiveSequence
        ) {
            return receipts.stream()
                    .filter(receipt ->
                            receipt.playerId().equals(playerId)
                                && receipt.sequence()
                                    > exclusiveSequence
                    )
                    .toList();
        }

        private void publish(final Receipt receipt) {
            receipts.add(receipt);
            sequence = Math.max(
                    sequence,
                    receipt.sequence()
            );
        }
    }

    private static final class RecordingCore
            implements CoreSkillActuator {
        private int nonZeroMoves;

        @Override
        public ActionOutcome move(final MovementIntent intent) {
            if (intent.forward() != 0.0
                    || intent.strafeLeft() != 0.0) {
                nonZeroMoves++;
            }
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
