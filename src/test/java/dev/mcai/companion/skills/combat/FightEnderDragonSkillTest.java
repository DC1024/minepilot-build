package dev.mcai.companion.skills.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.CollisionAffordance;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.bridging.BridgeMaterialResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.inventory.CraftRecipeParameters;
import dev.mcai.companion.skills.inventory.DropItemParameters;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class FightEnderDragonSkillTest {
    private static final UUID PLAYER_ID = UUID.fromString(
            "77000000-0000-0000-0000-000000000001"
    );
    private static final UUID CRYSTAL_ID = UUID.fromString(
            "77000000-0000-0000-0000-000000000002"
    );
    private static final long SESSION = 23;
    private static final String BOW = "minecraft:bow";
    private static final String PICKAXE =
            "minecraft:diamond_pickaxe";
    private static final String DIAMOND_SWORD =
            "minecraft:diamond_sword";

    @Test
    void dragonFightClaimsProjectileLaneOnlyWhileActive() {
        final MutableFrames frames = new MutableFrames(
                frame(12, dragon(8.0), List.of(), BOW)
        );
        final FightEnderDragonSkill skill = skill(
                frames,
                new RecordingCore(),
                new RecordingInteractions(),
                new RecordingInventory()
        );
        final FightEnderDragonParameters parameters = parameters();

        assertFalse(skill.managesVisibleProjectileThreats());
        skill.start(context(1), parameters);
        assertTrue(skill.managesVisibleProjectileThreats());
        skill.cancel(context(2), parameters);
        assertFalse(skill.managesVisibleProjectileThreats());
    }

    @Test
    void dragonFightClaimsPhysicalContactLaneOnlyWhileActive() {
        final MutableFrames frames = new MutableFrames(
                frame(12, dragon(8.0), List.of(), BOW)
        );
        final FightEnderDragonSkill skill = skill(
                frames,
                new RecordingCore(),
                new RecordingInteractions(),
                new RecordingInventory()
        );
        final FightEnderDragonParameters parameters = parameters();

        assertFalse(skill.managesPhysicalContactThreats());
        skill.start(context(1), parameters);
        assertTrue(skill.managesPhysicalContactThreats());
        skill.cancel(context(2), parameters);
        assertFalse(skill.managesPhysicalContactThreats());
    }

    @Test
    void outerEndSpawnPlatformRequiresPhysicalIslandIngressFirst() {
        final Snapshot outerSpawn = withPosition(
                frame(12, dragon(8.0), List.of(), BOW),
                new PerceptionVec3(100.5, 64.0, 0.5)
        );
        final MutableFrames frames = new MutableFrames(outerSpawn);
        final FightEnderDragonSkill skill = skill(
                frames,
                new RecordingCore(),
                new RecordingInteractions(),
                new RecordingInventory()
        );

        assertEquals(
                FightEnderDragonSkill.NAME
                        + ".end_island_ingress_required",
                skill.preconditions(context(1), parameters())
                        .orElseThrow()
                        .code()
        );
    }

    @Test
    void recentDragonDamageProducesBoundedFirstPersonDodge() {
        final RecordingCore core = new RecordingCore();
        final MutableFrames frames = new MutableFrames(
                frameWithEntities(
                        12,
                        List.of(dragon(4.0)),
                        List.of(),
                        BOW,
                        List.of(new DangerSignal(
                                DangerKind.THREAT_CONTACT,
                                0.90,
                                0.0,
                                Optional.of(new PerceptionVec3(
                                        0.0,
                                        0.0,
                                        1.0
                                )),
                                PerceptionProvenance.RECENT_DAMAGE_EVENT
                        ))
                )
        );
        final FightEnderDragonSkill skill = skill(
                frames,
                core,
                new RecordingInteractions(),
                new RecordingInventory()
        );

        skill.start(context(1), parameters());
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2), parameters()).status()
        );
        assertTrue(core.moves > 0);
    }

    @Test
    void rangedTransitionRetreatIsBoundedToEightFreshInputs() {
        final RecordingCore core = new RecordingCore();
        final MutableFrames frames = new MutableFrames(
                frameWithEntities(
                        12,
                        List.of(dragon(4.0)),
                        List.of(),
                        DIAMOND_SWORD,
                        List.of()
                )
        );
        final FightEnderDragonSkill skill = skill(
                frames,
                core,
                new RecordingInteractions(),
                new RecordingInventory()
        );
        final FightEnderDragonParameters parameters = parameters();

        skill.start(context(1), parameters);
        for (int attack = 0; attack < 24; attack++) {
            final long tick = 2L + attack * 2L;
            frames.set(frameWithEntities(
                    13L + attack,
                    List.of(dragon(4.0)),
                    List.of(),
                    DIAMOND_SWORD,
                    List.of()
            ));
            skill.tick(context(tick), parameters);
        }

        /* The next search tick enters the first-person ranged retreat. */
        frames.set(frameWithEntities(
                40,
                List.of(dragon(4.0)),
                List.of(),
                DIAMOND_SWORD,
                List.of()
        ));
        skill.tick(context(50), parameters);
        final int movesBeforeRetreat = core.moves;
        for (int retreat = 0; retreat < 8; retreat++) {
            frames.set(frameWithEntities(
                    41L + retreat,
                    List.of(dragon(4.0)),
                    List.of(),
                    DIAMOND_SWORD,
                    List.of()
            ));
            skill.tick(context(51L + retreat), parameters);
        }

        assertEquals(
                8,
                core.movementIntents.stream()
                        .skip(movesBeforeRetreat)
                        .filter(intent -> intent.sprint()
                                && (Math.abs(intent.forward()) > 1.0E-9
                                || Math.abs(intent.strafeLeft()) > 1.0E-9))
                        .count(),
                () -> "movesBefore=" + movesBeforeRetreat
                        + " intents=" + core.movementIntents
        );
        frames.set(frameWithEntities(
                50,
                List.of(dragon(4.0)),
                List.of(),
                DIAMOND_SWORD,
                List.of()
        ));
        skill.tick(context(59), parameters);
        assertEquals(
                8,
                core.movementIntents.stream()
                        .skip(movesBeforeRetreat)
                        .filter(intent -> intent.sprint()
                                && (Math.abs(intent.forward()) > 1.0E-9
                                || Math.abs(intent.strafeLeft()) > 1.0E-9))
                        .count()
        );
    }

    @Test
    void fourDragonShotsReopenTheBoundedPerchedMeleeWindow()
            throws Exception {
        final MutableFrames frames = new MutableFrames(
                frameWithEntities(
                        12,
                        List.of(dragon(4.0)),
                        List.of(),
                        DIAMOND_SWORD,
                        List.of()
                )
        );
        final RecordingInteractions interactions =
                new RecordingInteractions();
        final FightEnderDragonSkill skill = skill(
                frames,
                new RecordingCore(),
                interactions,
                new RecordingInventory()
        );
        final FightEnderDragonParameters parameters = parameters();
        skill.start(context(1), parameters);

        setField(skill, "meleeAttacks", 24);
        setField(skill, "meleeReachMisses", 3);
        setField(skill, "rangedDragonShotsSinceMeleeRetry", 3);
        setField(skill, "dragonRangedMode", true);
        final var completion = FightEnderDragonSkill.class
                .getDeclaredMethod("recordCompletedDragonShot");
        completion.setAccessible(true);
        completion.invoke(skill);

        final String checkpoint = skill
                .checkpoint(context(2), parameters)
                .payload();
        assertTrue(checkpoint.contains("\"melee\":0"));
        assertTrue(checkpoint.contains("\"meleeReachMisses\":0"));
        assertTrue(checkpoint.contains(
                "\"rangedDragonShotsSinceMeleeRetry\":0"
        ));
        assertTrue(checkpoint.contains("\"dragonRangedMode\":false"));
        assertTrue(checkpoint.contains("\"skyBlocksMined\":0"));
        assertTrue(checkpoint.contains("\"skyBreakAttempts\":0"));

        frames.set(frameWithEntities(
                13,
                List.of(dragon(4.0)),
                List.of(),
                DIAMOND_SWORD,
                List.of()
        ));
        skill.tick(context(2), parameters);
        assertEquals(1, interactions.attackCalls);
    }

    @Test
    void nearbyDragonTakesPriorityOverCageBarWhenUnderThreat() {
        final VisibleEntity blocked = crystal(14.0, false);
        final VisibleBlockFace bar = ironBar(
                4,
                65,
                0,
                new PerceptionVec3(4.0, 65.0, 0.5),
                3.7
        );
        final RecordingInventory inventory = new RecordingInventory();
        final MutableFrames frames = new MutableFrames(
                frameWithEntities(
                        12,
                        List.of(blocked, dragon(4.0)),
                        List.of(bar),
                        BOW,
                        List.of()
                )
        );
        final FightEnderDragonSkill skill = skill(
                frames,
                new RecordingCore(),
                new RecordingInteractions(),
                inventory
        );

        skill.start(context(1), parameters());
        skill.tick(context(2), parameters());
        assertEquals(List.of(DIAMOND_SWORD), inventory.equipped);
    }

    @Test
    void clearCrystalTakesPriorityOverNearbyDragonWhenNoThreat() {
        final VisibleEntity clear = crystal(14.0, true);
        final RecordingCore core = new RecordingCore();
        final RecordingInteractions interactions =
                new RecordingInteractions();
        final RecordingInventory inventory = new RecordingInventory();
        final MutableFrames frames = new MutableFrames(
                frameWithEntities(
                        12,
                        List.of(dragon(4.0), clear),
                        List.of(),
                        BOW,
                        List.of()
                )
        );
        final FightEnderDragonSkill skill = skill(
                frames,
                core,
                interactions,
                inventory
        );

        skill.start(context(1), parameters());
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2), parameters()).status()
        );
        assertEquals(0, interactions.attackCalls);
        assertFalse(inventory.equipped.contains(DIAMOND_SWORD));
    }

    @Test
    void minesOnlyAnObservedReachableCageBarThenSelectsCrystal() {
        /*
         * Thin iron bars do not always block the entity-center clip ray.
         * The aligned visible bar must still take precedence over a semantic
         * "line clear" flag so the body does not retreat away from a cage it
         * can currently open.
         */
        final VisibleEntity blocked = crystal(14.0, true);
        final VisibleBlockFace bar = ironBar(
                4,
                65,
                0,
                new PerceptionVec3(4.0, 65.0, 0.5),
                3.7
        );
        final MutableFrames frames = new MutableFrames(
                upwardSky(frame(
                        12,
                        blocked,
                        List.of(bar, landingTop(0, 63, 0)),
                        BOW
                ))
        );
        final RecordingCore core = new RecordingCore();
        final RecordingInteractions interactions =
                new RecordingInteractions();
        final RecordingInventory inventory =
                new RecordingInventory();
        final FightEnderDragonSkill skill = skill(
                frames,
                core,
                interactions,
                inventory
        );
        final FightEnderDragonParameters parameters = parameters();

        assertTrue(
                skill.preconditions(context(1), parameters).isEmpty(),
                () -> skill.preconditions(context(1), parameters)
                        .map(failure -> failure.code())
                        .orElse("unexpected")
        );
        skill.start(context(1), parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2), parameters).status()
        );
        assertEquals(List.of(PICKAXE), inventory.equipped);
        assertEquals(0, interactions.beginMiningCalls);

        frames.set(frame(
                13,
                blocked,
                List.of(bar),
                PICKAXE
        ));
        skill.tick(context(3), parameters);
        assertEquals(0, interactions.beginMiningCalls);
        assertTrue(
                skill.checkpoint(context(3), parameters)
                        .payload()
                        .contains(
                            "\"cageStatus\":\"ALIGNING_VISIBLE_BAR\""
                        )
        );
        assertFalse(core.looks.isEmpty());

        frames.set(frame(
                14,
                blocked,
                List.of(bar),
                PICKAXE,
                bar.hitPosition()
        ));
        skill.tick(context(4), parameters);
        assertTrue(
                skill.checkpoint(context(4), parameters)
                        .payload()
                        .contains("\"phase\":\"OPENING_CAGE\"")
        );
        skill.tick(context(5), parameters);
        assertEquals(1, interactions.beginMiningCalls);
        assertEquals(
                new BlockCoordinate(4, 65, 0),
                interactions.minedBlock
        );
        skill.tick(context(6), parameters);
        assertEquals(1, interactions.continueMiningCalls);
        assertTrue(
                skill.checkpoint(context(6), parameters)
                        .payload()
                        .contains("\"cageBarsMined\":1")
        );

        frames.set(frame(
                15,
                crystal(14.0, true),
                List.of(),
                PICKAXE
        ));
        skill.tick(context(9), parameters);
        assertEquals(
                List.of(PICKAXE, BOW),
                inventory.equipped
        );
        frames.set(frame(
                16,
                crystal(14.0, true),
                List.of(),
                BOW
        ));
        skill.tick(context(10), parameters);
        assertTrue(
                skill.checkpoint(context(10), parameters)
                        .payload()
                        .contains("\"phase\":\"SHOOTING\"")
        );
    }

    @Test
    void closeClearCrystalStartsObservedRetreatBeforeShooting() {
        final VisibleEntity close = crystal(7.5, true);
        final MutableFrames frames = new MutableFrames(
                frame(17, close, List.of(), BOW)
        );
        final RecordingInteractions interactions =
                new RecordingInteractions();
        final FightEnderDragonSkill skill = skill(
                frames,
                new RecordingCore(),
                interactions,
                new RecordingInventory()
        );
        final FightEnderDragonParameters parameters = parameters();

        skill.start(context(1), parameters);
        SkillTickResult result = null;
        long tick = 2;
        for (int scan = 0; scan < 12; scan++) {
            frames.set(frame(
                    17 + scan,
                    close,
                    List.of(),
                    BOW
            ));
            result = skill.tick(
                    context(tick),
                    parameters
            );
            assertEquals(
                    SkillTickResult.Status.RUNNING,
                    result.status()
            );
            tick += 3;
        }
        frames.set(frame(
                29,
                close,
                List.of(),
                BOW
        ));
        result = skill.tick(context(tick), parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                result.status()
        );
        final String checkpoint =
                skill.checkpoint(context(2), parameters)
                        .payload();
        assertTrue(
                checkpoint.contains(
                        "\"phase\":\"REPOSITIONING_CRYSTAL\""
                ),
                checkpoint
        );
        assertTrue(
                checkpoint.contains(
                        "\"crystalStandOffAttempts\":1"
                ),
                checkpoint
        );
        assertEquals(0, interactions.attackCalls);
    }

    @Test
    void distantAlignedBarTriggersRecoverableCageAssessment() {
        final VisibleEntity blocked = crystal(14.0, false);
        final VisibleBlockFace distantBar = ironBar(
                10,
                65,
                0,
                new PerceptionVec3(10.0, 65.0, 0.5),
                9.7
        );
        final MutableFrames frames = new MutableFrames(
                frame(
                        20,
                        blocked,
                        List.of(distantBar),
                        BOW
                )
        );
        final RecordingCore core = new RecordingCore();
        final RecordingInteractions interactions =
                new RecordingInteractions();
        final FightEnderDragonSkill skill = skill(
                frames,
                core,
                interactions,
                new RecordingInventory()
        );
        final FightEnderDragonParameters parameters = parameters();

        skill.start(context(1), parameters);
        SkillTickResult result =
                skill.tick(context(2), parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                result.status()
        );
        assertFalse(result.failure().isPresent());
        assertEquals(0, interactions.beginMiningCalls);
        assertTrue(core.looks.size() >= 1);
        assertTrue(
                skill.checkpoint(context(2), parameters)
                        .payload()
                        .contains(
                                "SAFE_TRAVERSAL_UNAVAILABLE"
                        )
        );

        /*
         * This deliberately frozen frame cannot simulate the child travel
         * skill's movement. It must nevertheless stay non-destructive and
         * fail closed once the strict fair traversal evidence is unavailable.
         */
        for (long tick = 3;
                tick <= 1_300
                        && result.status()
                        == SkillTickResult.Status.RUNNING;
                tick++) {
            result = skill.tick(context(tick), parameters);
        }
        assertEquals(
                SkillTickResult.Status.FAILED,
                result.status(),
                skill.checkpoint(context(1_300), parameters).payload()
        );
        assertEquals(
                "fight_ender_dragon.cage_safe_traversal_unavailable",
                result.failure().orElseThrow().code(),
                skill.checkpoint(context(1_300), parameters).payload()
        );
        assertEquals(0, interactions.beginMiningCalls);
    }

    @Test
    void refusesToMineAnIronBarOutsideTheCrystalRay() {
        final VisibleEntity blocked = crystal(8.0, false);
        final VisibleBlockFace unrelatedBar = ironBar(
                4,
                65,
                4,
                new PerceptionVec3(4.0, 65.0, 4.0),
                5.2
        );
        final MutableFrames frames = new MutableFrames(
                frame(
                        30,
                        blocked,
                        List.of(unrelatedBar),
                        PICKAXE
                )
        );
        final RecordingInteractions interactions =
                new RecordingInteractions();
        final FightEnderDragonSkill skill = skill(
                frames,
                new RecordingCore(),
                interactions,
                new RecordingInventory()
        );
        final FightEnderDragonParameters parameters = parameters();

        skill.start(context(1), parameters);
        final SkillTickResult result =
                skill.tick(context(2), parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                result.status()
        );
        assertEquals(0, interactions.beginMiningCalls);
        assertTrue(
                skill.checkpoint(context(2), parameters)
                        .payload()
                        .contains("SEEKING_VISIBLE_BAR")
        );
    }

    @Test
    void startsBoundedTowerForFairlyPlannedHighCage() {
        final VisibleEntity blocked =
                crystalAt(4.0, 73.0, false);
        final VisibleBlockFace highBar = ironBar(
                4,
                73,
                0,
                new PerceptionVec3(4.0, 73.0, 0.5),
                8.2
        );
        final List<InventoryItemSummary> inventory = List.of(
                new InventoryItemSummary(BOW, 1),
                new InventoryItemSummary("minecraft:arrow", 64),
                new InventoryItemSummary(PICKAXE, 1),
                new InventoryItemSummary(
                        "minecraft:water_bucket",
                        1
                ),
                new InventoryItemSummary(
                        "minecraft:cobblestone",
                        16
                )
        );
        final MutableFrames frames = new MutableFrames(
                frame(
                        40,
                        blocked,
                        List.of(
                                highBar,
                                landingTop(-1, 63, 0)
                        ),
                        BOW,
                        inventory
                )
        );
        final RecordingInteractions interactions =
                new RecordingInteractions();
        final FightEnderDragonSkill skill = skill(
                frames,
                new RecordingCore(),
                interactions,
                new RecordingInventory()
        );
        final FightEnderDragonParameters parameters = parameters();

        skill.start(context(1), parameters);
        final SkillTickResult result =
                skill.tick(context(2), parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                result.status()
        );
        assertEquals(0, interactions.beginMiningCalls);
        final String checkpoint =
                skill.checkpoint(context(2), parameters)
                        .payload();
        assertTrue(
                checkpoint.contains(
                        "\"phase\":\"TOWERING_CAGE\""
                )
        );
        assertTrue(
                checkpoint.contains(
                        "\"cageStatus\":\"TOWERING\""
                )
        );
    }

    @Test
    void unsafeHighCageTraversalReacquiresVisibleDragon() {
        final VisibleEntity blocked =
                crystalAt(4.0, 73.0, false);
        final VisibleEntity dragon = dragon(12.0);
        final VisibleBlockFace highBar = ironBar(
                4,
                73,
                0,
                new PerceptionVec3(4.0, 73.0, 0.5),
                8.2
        );
        final List<InventoryItemSummary> inventory = List.of(
                new InventoryItemSummary(BOW, 1),
                new InventoryItemSummary("minecraft:arrow", 64),
                new InventoryItemSummary(PICKAXE, 1),
                new InventoryItemSummary(
                        "minecraft:water_bucket",
                        1
                ),
                new InventoryItemSummary(
                        "minecraft:cobblestone",
                        16
                )
        );
        final MutableFrames frames = new MutableFrames(frame(
                43,
                blocked,
                List.of(
                        highBar,
                        landingTop(-1, 63, 0)
                ),
                BOW,
                inventory,
                blocked.position(),
                List.of(blocked, dragon),
                List.of()
        ));
        final RecordingCore core = new RecordingCore();
        final FightEnderDragonSkill skill = skill(
                frames,
                core,
                new RecordingInteractions(),
                new RecordingInventory()
        );
        final FightEnderDragonParameters parameters = parameters();
        final SkillContext unsafe = new SkillContext(
                4,
                10,
                1,
                true,
                true,
                0.10
        );

        skill.start(unsafe, parameters);
        final SkillTickResult deferred = skill.tick(
                new SkillContext(4, 10, 2, true, true, 0.10),
                parameters
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                deferred.status()
        );
        assertFalse(deferred.failure().isPresent());
        assertTrue(
                skill.checkpoint(unsafe, parameters)
                        .payload()
                        .contains("SAFETY_RESERVE_REQUIRED")
        );
        assertFalse(core.looks.isEmpty());

        frames.set(frameWithEntities(
                44,
                List.of(dragon),
                List.of(),
                BOW,
                List.of()
        ));
        final SkillTickResult fallback = skill.tick(
                new SkillContext(4, 10, 5, true, true, 0.10),
                parameters
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                fallback.status()
        );
        assertTrue(
                skill.checkpoint(unsafe, parameters)
                        .payload()
                        .contains("\"phase\":\"SHOOTING\"")
        );
    }

    @Test
    void refusesHighCageTowerWithoutOwnedWaterBucket() {
        final VisibleEntity blocked =
                crystalAt(4.0, 73.0, false);
        final VisibleBlockFace highBar = ironBar(
                4,
                73,
                0,
                new PerceptionVec3(4.0, 73.0, 0.5),
                8.2
        );
        final MutableFrames frames = new MutableFrames(
                frame(
                        41,
                        blocked,
                        List.of(highBar),
                        BOW
                )
        );
        final FightEnderDragonSkill skill = skill(
                frames,
                new RecordingCore(),
                new RecordingInteractions(),
                new RecordingInventory()
        );
        final FightEnderDragonParameters parameters = parameters();

        skill.start(context(1), parameters);
        final SkillTickResult result =
                skill.tick(context(2), parameters);

        assertEquals(
                SkillTickResult.Status.FAILED,
                result.status()
        );
        assertEquals(
                "fight_ender_dragon.cage_water_bucket_required",
                result.failure().orElseThrow().code()
        );
    }

    @Test
    void verifiesAdjacentLandingBeforeStartingHighCageTower() {
        final VisibleEntity blocked =
                crystalAt(4.0, 73.0, false);
        final VisibleBlockFace highBar = ironBar(
                4,
                73,
                0,
                new PerceptionVec3(4.0, 73.0, 0.5),
                8.2
        );
        final List<InventoryItemSummary> inventory = List.of(
                new InventoryItemSummary(BOW, 1),
                new InventoryItemSummary("minecraft:arrow", 64),
                new InventoryItemSummary(PICKAXE, 1),
                new InventoryItemSummary(
                        "minecraft:water_bucket",
                        1
                ),
                new InventoryItemSummary(
                        "minecraft:cobblestone",
                        16
                )
        );
        final MutableFrames frames = new MutableFrames(
                frame(
                        42,
                        blocked,
                        List.of(highBar),
                        BOW,
                        inventory
                )
        );
        final RecordingCore core = new RecordingCore();
        final FightEnderDragonSkill skill = skill(
                frames,
                core,
                new RecordingInteractions(),
                new RecordingInventory()
        );
        final FightEnderDragonParameters parameters = parameters();

        skill.start(context(1), parameters);
        final SkillTickResult result =
                skill.tick(context(2), parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                result.status()
        );
        assertTrue(
                skill.checkpoint(context(2), parameters)
                        .payload()
                        .contains(
                                "\"phase\":"
                                    + "\"PREPARING_CAGE_TOWER\""
                        )
        );
        assertTrue(
                skill.checkpoint(context(2), parameters)
                        .payload()
                        .contains("VERIFYING_LANDING")
        );
        assertFalse(core.looks.isEmpty());
    }

    @Test
    void transientDragonPartMissReobservesBeforeMeleeRetry() {
        final VisibleEntity dragon = dragon(4.0);
        final List<InventoryItemSummary> inventory = List.of(
                new InventoryItemSummary(BOW, 1),
                new InventoryItemSummary("minecraft:arrow", 64),
                new InventoryItemSummary(PICKAXE, 1),
                new InventoryItemSummary(DIAMOND_SWORD, 1)
        );
        final PerceptionVec3 aim = dragon.position().add(
                new PerceptionVec3(0.0, 2.0, 0.0)
        );
        final MutableFrames frames = new MutableFrames(frame(
                50,
                dragon,
                List.of(),
                DIAMOND_SWORD,
                inventory,
                aim
        ));
        final RecordingInteractions interactions =
                new RecordingInteractions();
        interactions.attackOutcome =
                ActionOutcome.TARGET_OUT_OF_REACH;
        final FightEnderDragonSkill skill = skill(
                frames,
                new RecordingCore(),
                interactions,
                new RecordingInventory()
        );
        final FightEnderDragonParameters parameters = parameters();

        skill.start(context(1), parameters);
        final SkillTickResult first =
                skill.tick(context(2), parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                first.status()
        );
        assertFalse(first.failure().isPresent());
        assertEquals(1, interactions.attackCalls);

        interactions.attackOutcome = ActionOutcome.DISPATCHED;
        frames.set(frame(
                51,
                dragon,
                List.of(),
                DIAMOND_SWORD,
                inventory,
                aim
        ));
        final SkillTickResult retried =
                skill.tick(context(3), parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                retried.status()
        );
        assertEquals(2, interactions.attackCalls);
        assertTrue(
                skill.checkpoint(context(3), parameters)
                        .payload()
                        .contains("\"melee\":1")
        );
    }

    @Test
    void repeatedDragonReachMissesFallBackToOrdinaryBow() {
        final VisibleEntity dragon = highDragonPart(4.0);
        final List<InventoryItemSummary> inventory = List.of(
                new InventoryItemSummary(BOW, 1),
                new InventoryItemSummary("minecraft:arrow", 64),
                new InventoryItemSummary(PICKAXE, 1),
                new InventoryItemSummary(DIAMOND_SWORD, 1)
        );
        final PerceptionVec3 aim = dragon.position().add(
                new PerceptionVec3(0.0, 3.0, 0.0)
        );
        final MutableFrames frames = new MutableFrames(frame(
                60,
                dragon,
                List.of(),
                DIAMOND_SWORD,
                inventory,
                aim
        ));
        final RecordingInteractions interactions =
                new RecordingInteractions();
        interactions.attackOutcome =
                ActionOutcome.TARGET_OUT_OF_REACH;
        final RecordingCore core = new RecordingCore();
        final RecordingInventory equipped =
                new RecordingInventory();
        final FightEnderDragonSkill skill = skill(
                frames,
                core,
                interactions,
                equipped
        );
        final FightEnderDragonParameters parameters = parameters();

        skill.start(context(1), parameters);
        for (long tick = 2; tick <= 4; tick++) {
            final SkillTickResult miss =
                    skill.tick(context(tick), parameters);
            assertEquals(
                    SkillTickResult.Status.RUNNING,
                    miss.status()
            );
        }
        final SkillTickResult fallback =
                skill.tick(context(5), parameters);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                fallback.status()
        );
        assertEquals(3, interactions.attackCalls);
        assertEquals(3, core.jumps);
        assertEquals(List.of(BOW), equipped.equipped);
        assertTrue(
                skill.checkpoint(context(5), parameters)
                    .payload()
                    .contains("\"meleeReachMisses\":3")
        );
    }

    @Test
    void survivalEquipmentPreemptionIsARecoverableShotFailure()
            throws Exception {
        final var method = FightEnderDragonSkill.class
                .getDeclaredMethod(
                        "transientShotFailure",
                        String.class
                );
        method.setAccessible(true);

        assertTrue((boolean) method.invoke(
                null,
                "shoot_observed_entity.weapon_changed"
        ));
        assertTrue((boolean) method.invoke(
                null,
                "shoot_observed_entity.interaction_line_blocked"
        ));
        assertTrue((boolean) method.invoke(
                null,
                "shoot_observed_entity.use_start_rejected"
        ));
        assertTrue((boolean) method.invoke(
                null,
                "shoot_observed_entity.danger_too_high"
        ));
        assertFalse((boolean) method.invoke(
                null,
                "shoot_observed_entity.item_unavailable"
        ));
    }

    @Test
    void ownVisibleArrowDoesNotTriggerEmergencyDodge() throws Exception {
        final var method = FightEnderDragonSkill.class
                .getDeclaredMethod(
                        "projectileThreatensBody",
                        VisibleEntity.class
                );
        method.setAccessible(true);
        final VisibleEntity ownArrow = new VisibleEntity(
                UUID.fromString(
                        "77000000-0000-0000-0000-000000000004"
                ),
                "minecraft:arrow",
                new PerceptionVec3(0.5, 64.0, 1.0),
                new PerceptionVec3(0.0, 0.0, 0.5),
                0.5,
                false,
                true,
                PerceptionProvenance.ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                Map.of(
                        "interactionLineClear", "true",
                        "projectileThreat", "false"
                )
        );
        final VisibleEntity legacyProjectile = new VisibleEntity(
                UUID.fromString(
                        "77000000-0000-0000-0000-000000000005"
                ),
                "minecraft:arrow",
                new PerceptionVec3(0.5, 64.0, 1.0),
                new PerceptionVec3(0.0, 0.0, 0.5),
                0.5,
                false,
                true,
                PerceptionProvenance.ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                Map.of("interactionLineClear", "true")
        );

        assertFalse((boolean) method.invoke(null, ownArrow));
        assertTrue((boolean) method.invoke(null, legacyProjectile));
    }

    private static FightEnderDragonSkill skill(
            final MutableFrames frames,
            final RecordingCore core,
            final RecordingInteractions interactions,
            final RecordingInventory inventory
    ) {
        return new FightEnderDragonSkill(
                PLAYER_ID,
                core,
                frames::core,
                interactions,
                frames::interaction,
                inventory,
                () -> BridgeMaterialResult.ready(
                        "minecraft:cobblestone",
                        64
                ),
                ignored -> false,
                () -> SESSION
        );
    }

    private static FightEnderDragonParameters parameters() {
        return new FightEnderDragonParameters(
                DimensionRef.END,
                0.5,
                64.0,
                0.5,
                16,
                1_200
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(
                4,
                10,
                tick,
                true,
                true,
                0.0
        );
    }

    private static VisibleEntity crystal(
            final double x,
            final boolean clear
    ) {
        return crystalAt(x, 65.0, clear);
    }

    private static VisibleEntity crystalAt(
            final double x,
            final double y,
            final boolean clear
    ) {
        final PerceptionVec3 position =
                new PerceptionVec3(x + 0.5, y, 0.5);
        return new VisibleEntity(
                CRYSTAL_ID,
                "minecraft:end_crystal",
                position,
                position.subtract(
                        new PerceptionVec3(0.5, 64.0, 0.5)
                ),
                x,
                false,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                Map.of(
                        "interactionLineClear",
                        Boolean.toString(clear)
                )
        );
    }

    private static VisibleEntity dragon(final double z) {
        final PerceptionVec3 position =
                new PerceptionVec3(0.5, 64.0, z + 0.5);
        return new VisibleEntity(
                UUID.fromString(
                        "77000000-0000-0000-0000-000000000003"
                ),
                "minecraft:ender_dragon",
                position,
                position.subtract(
                        new PerceptionVec3(0.5, 64.0, 0.5)
                ),
                z,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                Map.of("interactionLineClear", "true")
        );
    }

    private static VisibleEntity highDragonPart(final double z) {
        final PerceptionVec3 position =
                new PerceptionVec3(0.5, 64.0, z + 0.5);
        return new VisibleEntity(
                UUID.fromString(
                        "77000000-0000-0000-0000-000000000003"
                ),
                "minecraft:ender_dragon",
                position,
                position.subtract(
                        new PerceptionVec3(0.5, 64.0, 0.5)
                ),
                z,
                true,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                Map.of(
                        "interactionLineClear",
                        "true",
                        "multipartParent",
                        "true",
                        "interactionAimX",
                        Double.toString(position.x()),
                        "interactionAimY",
                        Double.toString(position.y() + 3.0),
                        "interactionAimZ",
                        Double.toString(position.z())
                )
        );
    }

    private static VisibleBlockFace ironBar(
            final int x,
            final int y,
            final int z,
            final PerceptionVec3 hit,
            final double distance
    ) {
        return new VisibleBlockFace(
                new BlockCoordinate(x, y, z),
                "minecraft:iron_bars",
                "west",
                hit,
                distance,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of()
        );
    }

    private static VisibleBlockFace landingTop(
            final int x,
            final int y,
            final int z
    ) {
        return new VisibleBlockFace(
                new BlockCoordinate(x, y, z),
                "minecraft:end_stone",
                "up",
                new PerceptionVec3(
                        x + 0.5,
                        y + 1.0,
                        z + 0.5
                ),
                2.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of(),
                TopSupportAffordance.STURDY_FULL_TOP,
                CollisionAffordance.OBSTRUCTED_OR_PARTIAL,
                0
        );
    }

    private static Snapshot frame(
            final long revision,
            final VisibleEntity crystal,
            final List<VisibleBlockFace> faces,
            final String mainHandItem
    ) {
        final List<InventoryItemSummary> inventory = List.of(
                new InventoryItemSummary(BOW, 1),
                new InventoryItemSummary("minecraft:arrow", 64),
                new InventoryItemSummary(PICKAXE, 1)
        );
        return frame(
                revision,
                crystal,
                faces,
                mainHandItem,
                inventory,
                crystal.position(),
                List.of(crystal),
                List.of()
        );
    }

    private static Snapshot frame(
            final long revision,
            final VisibleEntity crystal,
            final List<VisibleBlockFace> faces,
            final String mainHandItem,
            final List<InventoryItemSummary> inventory
    ) {
        return frame(
                revision,
                crystal,
                faces,
                mainHandItem,
                inventory,
                crystal.position()
        );
    }

    private static Snapshot frame(
            final long revision,
            final VisibleEntity crystal,
            final List<VisibleBlockFace> faces,
            final String mainHandItem,
            final List<InventoryItemSummary> inventory,
            final PerceptionVec3 lookTarget
    ) {
        return frame(
                revision,
                crystal,
                faces,
                mainHandItem,
                inventory,
                lookTarget,
                List.of(crystal),
                List.of()
        );
    }

    private static Snapshot frame(
            final long revision,
            final VisibleEntity crystal,
            final List<VisibleBlockFace> faces,
            final String mainHandItem,
            final PerceptionVec3 lookTarget
    ) {
        return frame(
                revision,
                crystal,
                faces,
                mainHandItem,
                List.of(
                    new InventoryItemSummary(BOW, 1),
                    new InventoryItemSummary("minecraft:arrow", 64),
                    new InventoryItemSummary(PICKAXE, 1)
                ),
                lookTarget,
                List.of(crystal),
                List.of()
        );
    }

    private static Snapshot frameWithEntities(
            final long revision,
            final List<VisibleEntity> visibleEntities,
            final List<VisibleBlockFace> faces,
            final String mainHandItem,
            final List<DangerSignal> dangerSignals
    ) {
        final List<InventoryItemSummary> inventory = List.of(
                new InventoryItemSummary(BOW, 1),
                new InventoryItemSummary("minecraft:arrow", 64),
                new InventoryItemSummary(PICKAXE, 1),
                new InventoryItemSummary(DIAMOND_SWORD, 1)
        );
        final VisibleEntity first = visibleEntities.getFirst();
        final PerceptionVec3 lookTarget =
                "minecraft:ender_dragon".equals(first.entityTypeId())
                        ? first.position().add(
                                new PerceptionVec3(0.0, 2.0, 0.0)
                        )
                        : first.position();
        return frame(
                revision,
                first,
                faces,
                mainHandItem,
                inventory,
                lookTarget,
                visibleEntities,
                dangerSignals
        );
    }

    private static Snapshot frame(
            final long revision,
            final VisibleEntity crystal,
            final List<VisibleBlockFace> faces,
            final String mainHandItem,
            final List<InventoryItemSummary> inventory,
            final PerceptionVec3 lookTarget,
            final List<VisibleEntity> visibleEntities,
            final List<DangerSignal> dangerSignals
    ) {
        final HeldItemSummary mainHand = held(mainHandItem);
        final PerceptionVec3 position =
                new PerceptionVec3(0.5, 64.0, 0.5);
        final PerceptionVec3 eye =
                new PerceptionVec3(0.5, 65.62, 0.5);
        final PerceptionVec3 look =
                lookTarget.subtract(eye).normalized();
        final CoreSkillFrame core = new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.END,
                100,
                revision,
                position,
                eye,
                look,
                true,
                false,
                0.0,
                navigation(revision),
                faces,
                20.0F,
                20.0F,
                20,
                inventory,
                mainHand,
                HeldItemSummary.empty(),
                visibleEntities,
                dangerSignals
        );
        final InteractionSkillFrame interaction =
                new InteractionSkillFrame(
                        PLAYER_ID,
                        DimensionRef.END,
                        100,
                        100,
                        revision,
                        SESSION,
                        mainHand,
                        HeldItemSummary.empty(),
                        visibleEntities,
                        faces,
                        inventory
                );
        return new Snapshot(core, interaction);
    }

    private static HeldItemSummary held(final String itemId) {
        return switch (itemId) {
            case BOW -> new HeldItemSummary(
                    BOW,
                    1,
                    0,
                    384
            );
            case PICKAXE -> new HeldItemSummary(
                    PICKAXE,
                    1,
                    0,
                    1_561
            );
            case DIAMOND_SWORD -> new HeldItemSummary(
                    DIAMOND_SWORD,
                    1,
                    0,
                    1_561
            );
            default -> throw new IllegalArgumentException(itemId);
        };
    }

    private static LocalNavSnapshot navigation(
            final long revision
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        for (int x = -8; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                voxels.add(new ObservedVoxel(
                        new GridPos(x, 63, z),
                        VoxelKind.SOLID,
                        0.0,
                        revision,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.STURDY_FULL_TOP
                ));
                voxels.add(new ObservedVoxel(
                        new GridPos(x, 64, z),
                        VoxelKind.AIR,
                        0.0,
                        revision,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ));
                voxels.add(new ObservedVoxel(
                        new GridPos(x, 65, z),
                        VoxelKind.AIR,
                        0.0,
                        revision,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ));
                voxels.add(new ObservedVoxel(
                        new GridPos(x, 66, z),
                        VoxelKind.AIR,
                        0.0,
                        revision,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ));
            }
        }
        return new LocalNavSnapshot(
                DimensionRef.END,
                revision,
                voxels
        );
    }

    private record Snapshot(
            CoreSkillFrame core,
            InteractionSkillFrame interaction
    ) {
    }

    private static Snapshot upwardSky(final Snapshot source) {
        final CoreSkillFrame core = source.core();
        return new Snapshot(
                new CoreSkillFrame(
                        core.playerId(),
                        core.dimension(),
                        core.gameTime(),
                        core.observationRevision(),
                        core.position(),
                        core.eyePosition(),
                        new PerceptionVec3(0.0, 1.0, 0.0),
                        core.onGround(),
                        core.inWater(),
                        core.danger(),
                        core.navigation(),
                        core.visibleBlockFaces(),
                        core.health(),
                        core.maxHealth(),
                        core.foodLevel(),
                        core.inventory(),
                        core.mainHand(),
                        core.offHand(),
                        core.visibleEntities(),
                        core.dangerSignals()
                ),
                source.interaction()
        );
    }

    private static Snapshot withPosition(
            final Snapshot source,
            final PerceptionVec3 position
    ) {
        final CoreSkillFrame core = source.core();
        final PerceptionVec3 eye = new PerceptionVec3(
                position.x(),
                position.y() + 1.62,
                position.z()
        );
        return new Snapshot(
                new CoreSkillFrame(
                        core.playerId(),
                        core.dimension(),
                        core.gameTime(),
                        core.observationRevision(),
                        position,
                        eye,
                        core.lookDirection(),
                        core.onGround(),
                        core.inWater(),
                        core.danger(),
                        core.navigation(),
                        core.visibleBlockFaces(),
                        core.health(),
                        core.maxHealth(),
                        core.foodLevel(),
                        core.inventory(),
                        core.mainHand(),
                        core.offHand(),
                        core.visibleEntities(),
                        core.dangerSignals()
                ),
                source.interaction()
        );
    }

    private static void setField(
            final Object target,
            final String name,
            final Object value
    ) throws ReflectiveOperationException {
        final var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class MutableFrames {
        private Snapshot snapshot;

        private MutableFrames(final Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        private void set(final Snapshot replacement) {
            snapshot = replacement;
        }

        private Optional<CoreSkillFrame> core() {
            return Optional.of(snapshot.core());
        }

        private Optional<InteractionSkillFrame> interaction() {
            return Optional.of(snapshot.interaction());
        }
    }

    private static final class RecordingCore
            implements CoreSkillActuator {
        private final List<LookIntent> looks =
                new ArrayList<>();
        private final List<MovementIntent> movementIntents =
                new ArrayList<>();
        private int moves;
        private int jumps;

        @Override
        public ActionOutcome move(
                final MovementIntent intent
        ) {
            moves++;
            movementIntents.add(intent);
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome look(final LookIntent intent) {
            looks.add(intent);
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome jump() {
            jumps++;
            return ActionOutcome.DISPATCHED;
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
            return ActionOutcome.DISPATCHED;
        }
    }

    private static final class RecordingInteractions
            implements InteractionSkillActuator {
        private int beginMiningCalls;
        private int continueMiningCalls;
        private int attackCalls;
        private BlockCoordinate minedBlock;
        private ActionOutcome attackOutcome =
                ActionOutcome.DISPATCHED;

        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(SESSION);
        }

        @Override
        public ActionOutcome beginMining(
                final BlockInteractionTarget target
        ) {
            beginMiningCalls++;
            minedBlock = new BlockCoordinate(
                    target.x(),
                    target.y(),
                    target.z()
            );
            return ActionOutcome.IN_PROGRESS;
        }

        @Override
        public ActionOutcome continueMining() {
            continueMiningCalls++;
            return ActionOutcome.COMPLETED;
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
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome attack(final UUID entityId) {
            attackCalls++;
            return attackOutcome;
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
            return ActionOutcome.DISPATCHED;
        }
    }

    private static final class RecordingInventory
            implements InventorySkillActuator {
        private final List<String> equipped =
                new ArrayList<>();

        @Override
        public InventoryOperationResult checkEquip(
                final EquipItemParameters parameters
        ) {
            return InventoryOperationResult.success();
        }

        @Override
        public InventoryOperationResult equip(
                final EquipItemParameters parameters
        ) {
            equipped.add(parameters.itemId());
            return InventoryOperationResult.success();
        }

        @Override
        public InventoryOperationResult checkDrop(
                final DropItemParameters parameters
        ) {
            return InventoryOperationResult.success();
        }

        @Override
        public InventoryOperationResult drop(
                final DropItemParameters parameters
        ) {
            return InventoryOperationResult.success();
        }

        @Override
        public InventoryOperationResult checkCraft(
                final CraftRecipeParameters parameters
        ) {
            return InventoryOperationResult.success();
        }

        @Override
        public InventoryOperationResult craftOnce(
                final CraftRecipeParameters parameters
        ) {
            return InventoryOperationResult.success();
        }
    }
}
