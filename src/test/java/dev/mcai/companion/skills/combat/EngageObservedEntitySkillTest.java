package dev.mcai.companion.skills.combat;

import static dev.mcai.companion.skills.combat.CombatSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.combat.CombatSkillTestFixtures.SEQUENCE;
import static dev.mcai.companion.skills.combat.CombatSkillTestFixtures.TARGET_ID;
import static dev.mcai.companion.skills.combat.CombatSkillTestFixtures.coreFrame;
import static dev.mcai.companion.skills.combat.CombatSkillTestFixtures.hostile;
import static dev.mcai.companion.skills.combat.CombatSkillTestFixtures.interactionFrame;
import static dev.mcai.companion.skills.combat.CombatSkillTestFixtures.lookToward;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionMath;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import java.util.List;
import org.junit.jupiter.api.Test;

final class EngageObservedEntitySkillTest {
    private static final PerceptionVec3 EAST =
            new PerceptionVec3(1.0, 0.0, 0.0);

    @Test
    void respectsCooldownThenAttacksOnlyBoundObservedTarget() {
        VisibleEntity target = hostile(
                TARGET_ID,
                "minecraft:zombie",
                2.5
        );
        var coreFrames =
                new CombatSkillTestFixtures.MutableCoreFrames(
                        coreFrame(
                                SEQUENCE,
                                20.0F,
                                lookToward(target),
                                List.of(target),
                                true
                        )
                );
        var interactionFrames =
                new CombatSkillTestFixtures.MutableInteractionFrames(
                        interactionFrame(
                                SEQUENCE,
                                List.of(target),
                                true
                        )
                );
        var core =
                new CombatSkillTestFixtures.RecordingCoreActuator();
        var interaction =
                new CombatSkillTestFixtures
                        .RecordingInteractionActuator();
        interaction.attackStrength = 0.5;
        EngageObservedEntitySkill skill = skill(
                core,
                coreFrames,
                interaction,
                interactionFrames,
                CombatSkillPolicy.defaults()
        );
        var parameters = parameters();

        assertTrue(skill.preconditions(
                context(1, false),
                parameters
        ).isEmpty());
        skill.start(context(1, false), parameters);
        SkillTickResult cooling = skill.tick(
                context(2, false),
                parameters
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                cooling.status()
        );
        assertTrue(interaction.attacks.isEmpty());
        assertEquals(List.of(ActionHand.OFF_HAND), core.uses);

        interaction.attackStrength = 1.0;
        SkillTickResult attacked = skill.tick(
                context(3, false),
                parameters
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                attacked.status()
        );
        assertEquals(List.of(TARGET_ID), interaction.attacks);
        assertFalse(
                skill.checkpoint(context(3, false), parameters)
                        .payload()
                        .contains(TARGET_ID.toString())
        );
    }

    @Test
    void closeHostileIsGuardedDuringCooldownThenAttacked() {
        VisibleEntity target = hostile(
                TARGET_ID,
                "minecraft:zombie",
                1.2
        );
        var coreFrames =
                new CombatSkillTestFixtures.MutableCoreFrames(
                        coreFrame(
                                SEQUENCE,
                                20.0F,
                                lookToward(target),
                                List.of(target),
                                true
                        )
                );
        var interactionFrames =
                new CombatSkillTestFixtures.MutableInteractionFrames(
                        interactionFrame(
                                SEQUENCE,
                                List.of(target),
                                true
                        )
                );
        var core =
                new CombatSkillTestFixtures.RecordingCoreActuator();
        var interaction =
                new CombatSkillTestFixtures
                        .RecordingInteractionActuator();
        interaction.attackStrength = 0.5;
        EngageObservedEntitySkill skill = skill(
                core,
                coreFrames,
                interaction,
                interactionFrames,
                CombatSkillPolicy.defaults()
        );
        var parameters = parameters();
        skill.start(context(1, false), parameters);

        SkillTickResult cooling = skill.tick(
                context(2, false),
                parameters
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                cooling.status()
        );
        assertTrue(interaction.attacks.isEmpty());
        assertEquals(List.of(ActionHand.OFF_HAND), core.uses);

        interaction.attackStrength = 1.0;
        SkillTickResult attacked = skill.tick(
                context(3, false),
                parameters
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                attacked.status()
        );
        assertEquals(List.of(TARGET_ID), interaction.attacks);
    }

    @Test
    void unshieldedMeleeUsesSafeFootworkDuringCooldownAndAfterAttack() {
        VisibleEntity target = hostile(
                TARGET_ID,
                "minecraft:zombie",
                2.2
        );
        var coreFrames =
                new CombatSkillTestFixtures.MutableCoreFrames(
                        coreFrame(
                                SEQUENCE,
                                20.0F,
                                lookToward(target),
                                List.of(target),
                                false
                        )
                );
        var interactionFrames =
                new CombatSkillTestFixtures.MutableInteractionFrames(
                        interactionFrame(
                                SEQUENCE,
                                List.of(target),
                                false
                        )
                );
        var core =
                new CombatSkillTestFixtures.RecordingCoreActuator();
        var interaction =
                new CombatSkillTestFixtures
                        .RecordingInteractionActuator();
        interaction.attackStrength = 0.5;
        EngageObservedEntitySkill skill = skill(
                core,
                coreFrames,
                interaction,
                interactionFrames,
                CombatSkillPolicy.defaults()
        );
        var parameters = parameters();
        skill.start(context(1, false), parameters);

        SkillTickResult cooling = skill.tick(
                context(2, false),
                parameters
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                cooling.status()
        );
        assertFalse(core.movements.isEmpty());
        assertTrue(core.uses.isEmpty());

        final int movementCount = core.movements.size();
        interaction.attackStrength = 1.0;
        SkillTickResult attacked = skill.tick(
                context(13, false),
                parameters
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                attacked.status()
        );
        assertEquals(List.of(TARGET_ID), interaction.attacks);
        assertTrue(core.movements.size() > movementCount);
    }

    @Test
    void internalBindingSurvivesVisibleListReordering() {
        VisibleEntity target = hostile(
                TARGET_ID,
                "minecraft:zombie",
                2.5
        );
        VisibleEntity decoy = hostile(
                CombatSkillTestFixtures.DECOY_ID,
                "minecraft:skeleton",
                2.0
        );
        var coreFrames =
                new CombatSkillTestFixtures.MutableCoreFrames(
                        coreFrame(
                                SEQUENCE,
                                20.0F,
                                lookToward(target),
                                List.of(target, decoy),
                                false
                        )
                );
        var interactionFrames =
                new CombatSkillTestFixtures.MutableInteractionFrames(
                        interactionFrame(
                                SEQUENCE,
                                List.of(target, decoy),
                                false
                        )
                );
        var interaction =
                new CombatSkillTestFixtures
                        .RecordingInteractionActuator();
        EngageObservedEntitySkill skill = skill(
                new CombatSkillTestFixtures.RecordingCoreActuator(),
                coreFrames,
                interaction,
                interactionFrames,
                CombatSkillPolicy.defaults()
        );
        var parameters = parameters();
        skill.start(context(1, false), parameters);

        coreFrames.frame = coreFrame(
                SEQUENCE + 1,
                20.0F,
                lookToward(target),
                List.of(decoy, target),
                false
        );
        interactionFrames.frame = interactionFrame(
                SEQUENCE + 1,
                List.of(decoy, target),
                false
        );
        skill.tick(context(2, false), parameters);

        assertEquals(List.of(TARGET_ID), interaction.attacks);
    }

    @Test
    void lossOfSightUsesBoundedSearchAndCompletesAfterAnAttack() {
        VisibleEntity target = hostile(
                TARGET_ID,
                "minecraft:zombie",
                2.5
        );
        var coreFrames =
                new CombatSkillTestFixtures.MutableCoreFrames(
                        coreFrame(
                                SEQUENCE,
                                20.0F,
                                lookToward(target),
                                List.of(target),
                                false
                        )
                );
        var interactionFrames =
                new CombatSkillTestFixtures.MutableInteractionFrames(
                        interactionFrame(
                                SEQUENCE,
                                List.of(target),
                                false
                        )
                );
        var core =
                new CombatSkillTestFixtures.RecordingCoreActuator();
        var interaction =
                new CombatSkillTestFixtures
                        .RecordingInteractionActuator();
        EngageObservedEntitySkill skill = skill(
                core,
                coreFrames,
                interaction,
                interactionFrames,
                shortSearchPolicy()
        );
        var parameters = parameters();
        skill.start(context(1, false), parameters);
        skill.tick(context(2, false), parameters);
        assertEquals(List.of(TARGET_ID), interaction.attacks);

        coreFrames.frame = coreFrame(
                SEQUENCE + 1,
                20.0F,
                EAST,
                List.of(),
                false
        );
        interactionFrames.frame = interactionFrame(
                SEQUENCE + 1,
                List.of(),
                false
        );
        SkillTickResult reacquiring = skill.tick(
                context(3, false),
                parameters
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                reacquiring.status()
        );
        assertFalse(core.looks.isEmpty());

        final int looksBeforeSearch = core.looks.size();
        skill.tick(context(16, false), parameters);
        skill.tick(context(17, false), parameters);
        assertEquals(looksBeforeSearch + 2, core.looks.size());
        assertTrue(
                Math.abs(ActionMath.wrapDegrees(
                    core.looks.getLast().yawDegrees()
                            - core.looks.get(
                                core.looks.size() - 2
                            ).yawDegrees()
                )) >= 50.0F,
                "Alternating search turns must fan to opposite sides "
                    + "instead of cancelling back to the current yaw"
        );

        SkillTickResult completed = skill.tick(
                context(20, false),
                parameters
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                completed.status()
        );
        assertEquals(1, interaction.attacks.size());
    }

    @Test
    void lowHealthRetreatsThroughObservedSafeCellAndGuards() {
        VisibleEntity target = hostile(
                TARGET_ID,
                "minecraft:zombie",
                2.2
        );
        var coreFrames =
                new CombatSkillTestFixtures.MutableCoreFrames(
                        coreFrame(
                                SEQUENCE,
                                20.0F,
                                lookToward(target),
                                List.of(target),
                                true
                        )
                );
        var interactionFrames =
                new CombatSkillTestFixtures.MutableInteractionFrames(
                        interactionFrame(
                                SEQUENCE,
                                List.of(target),
                                true
                        )
                );
        var core =
                new CombatSkillTestFixtures.RecordingCoreActuator();
        var interaction =
                new CombatSkillTestFixtures
                        .RecordingInteractionActuator();
        EngageObservedEntitySkill skill = skill(
                core,
                coreFrames,
                interaction,
                interactionFrames,
                CombatSkillPolicy.defaults()
        );
        var parameters = parameters();
        skill.start(context(1, false), parameters);
        coreFrames.frame = coreFrame(
                SEQUENCE + 1,
                5.0F,
                lookToward(target),
                List.of(target),
                true
        );
        interactionFrames.frame = interactionFrame(
                SEQUENCE + 1,
                List.of(target),
                true
        );

        SkillTickResult result = skill.tick(
                context(2, false),
                parameters
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                result.status()
        );
        assertTrue(interaction.attacks.isEmpty());
        assertFalse(core.movements.isEmpty());
        assertEquals(List.of(ActionHand.OFF_HAND), core.uses);
    }

    @Test
    void distantTargetWithUnknownFootingTriggersDownwardFairScan() {
        VisibleEntity target = hostile(
                TARGET_ID,
                "minecraft:blaze",
                6.5
        );
        CoreSkillFrame initial = coreFrame(
                SEQUENCE,
                20.0F,
                lookToward(target),
                List.of(target),
                true
        );
        var coreFrames =
                new CombatSkillTestFixtures.MutableCoreFrames(
                        withCurrentCellOnly(initial)
                );
        var interactionFrames =
                new CombatSkillTestFixtures.MutableInteractionFrames(
                        interactionFrame(
                                SEQUENCE,
                                List.of(target),
                                true
                        )
                );
        var core =
                new CombatSkillTestFixtures.RecordingCoreActuator();
        EngageObservedEntitySkill skill = skill(
                core,
                coreFrames,
                new CombatSkillTestFixtures
                        .RecordingInteractionActuator(),
                interactionFrames,
                CombatSkillPolicy.defaults()
        );
        var parameters = parameters();
        skill.start(context(1, false), parameters);

        SkillTickResult result = skill.tick(
                context(2, false),
                parameters
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                result.status()
        );
        assertTrue(result.madeProgress());
        assertTrue(core.movements.isEmpty());
        assertTrue(core.looks.getLast().pitchDegrees() >= 25.0F);
    }

    @Test
    void highImpactTargetRaisesShieldBeforeClosingIntoReach() {
        VisibleEntity target = CombatSkillTestFixtures.entity(
                TARGET_ID,
                "minecraft:iron_golem",
                2.5,
                true,
                false
        );
        var coreFrames =
                new CombatSkillTestFixtures.MutableCoreFrames(
                        coreFrame(
                                SEQUENCE,
                                20.0F,
                                lookToward(target),
                                List.of(target),
                                true
                        )
                );
        var interactionFrames =
                new CombatSkillTestFixtures.MutableInteractionFrames(
                        interactionFrame(
                                SEQUENCE,
                                List.of(target),
                                true
                        )
                );
        var core = new CombatSkillTestFixtures.RecordingCoreActuator();
        var interaction =
                new CombatSkillTestFixtures.RecordingInteractionActuator();
        EngageObservedEntitySkill skill = skill(
                core,
                coreFrames,
                interaction,
                interactionFrames,
                CombatSkillPolicy.defaults()
        );

        skill.start(context(1, false), parameters());
        assertFalse(
                skill.managesPhysicalContactThreats(),
                "High-impact encounters must leave contact preemption to survival"
        );
        SkillTickResult result = skill.tick(
                context(2, false),
                parameters()
        );

        assertEquals(SkillTickResult.Status.RUNNING, result.status());
        assertEquals(List.of(ActionHand.OFF_HAND), core.uses);
        assertTrue(interaction.attacks.isEmpty());

        interaction.attackStrength = 0.5;
        SkillTickResult guardedFootwork = skill.tick(
                context(6, false),
                parameters()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                guardedFootwork.status()
        );
        assertTrue(
                !core.movements.isEmpty(),
                "A high-impact cooldown must separate under shield"
        );
    }

    @Test
    void hardcoreRetreatThresholdIsMoreConservative() {
        VisibleEntity target = hostile(
                TARGET_ID,
                "minecraft:zombie",
                2.5
        );
        var coreFrames =
                new CombatSkillTestFixtures.MutableCoreFrames(
                        coreFrame(
                                SEQUENCE,
                                20.0F,
                                lookToward(target),
                                List.of(target),
                                false
                        )
                );
        var interactionFrames =
                new CombatSkillTestFixtures.MutableInteractionFrames(
                        interactionFrame(
                                SEQUENCE,
                                List.of(target),
                                false
                        )
                );
        var core =
                new CombatSkillTestFixtures.RecordingCoreActuator();
        var interaction =
                new CombatSkillTestFixtures
                        .RecordingInteractionActuator();
        EngageObservedEntitySkill skill = skill(
                core,
                coreFrames,
                interaction,
                interactionFrames,
                CombatSkillPolicy.defaults()
        );
        var parameters = parameters();
        skill.start(context(1, true), parameters);
        coreFrames.frame = coreFrame(
                SEQUENCE + 1,
                8.0F,
                lookToward(target),
                List.of(target),
                false
        );
        interactionFrames.frame = interactionFrame(
                SEQUENCE + 1,
                List.of(target),
                false
        );

        skill.tick(context(2, true), parameters);

        assertTrue(interaction.attacks.isEmpty());
        assertFalse(core.movements.isEmpty());
    }

    @Test
    void rejectsPassiveProjectileStaleAndForgedTargets() {
        VisibleEntity cow = CombatSkillTestFixtures.entity(
                TARGET_ID,
                "minecraft:cow",
                2.5,
                false,
                false
        );
        var coreFrames =
                new CombatSkillTestFixtures.MutableCoreFrames(
                        coreFrame(
                                SEQUENCE,
                                20.0F,
                                lookToward(cow),
                                List.of(cow),
                                false
                        )
                );
        var interactionFrames =
                new CombatSkillTestFixtures.MutableInteractionFrames(
                        interactionFrame(
                                SEQUENCE,
                                List.of(cow),
                                false
                        )
                );
        EngageObservedEntitySkill skill = skill(
                new CombatSkillTestFixtures.RecordingCoreActuator(),
                coreFrames,
                new CombatSkillTestFixtures
                        .RecordingInteractionActuator(),
                interactionFrames,
                CombatSkillPolicy.defaults()
        );

        assertEquals(
                "engage_observed_entity.unsafe_target",
                skill.preconditions(
                        context(1, false),
                        parameters()
                ).orElseThrow().code()
        );
        assertEquals(
                "engage_observed_entity.stale_observation_id",
                skill.preconditions(
                        context(1, false),
                        new EngageObservedEntityParameters(
                                SEQUENCE - 1,
                                "visible-0"
                        )
                ).orElseThrow().code()
        );
        assertEquals(
                "engage_observed_entity.invalid_observation_id",
                skill.preconditions(
                        context(1, false),
                        new EngageObservedEntityParameters(
                                SEQUENCE,
                                "visible-9"
                        )
                ).orElseThrow().code()
        );

        VisibleEntity canonicalZombie = CombatSkillTestFixtures.entity(
                TARGET_ID,
                "minecraft:zombie",
                2.5,
                false,
                false
        );
        var zombieCoreFrames =
                new CombatSkillTestFixtures.MutableCoreFrames(
                        coreFrame(
                                SEQUENCE,
                                20.0F,
                                lookToward(canonicalZombie),
                                List.of(canonicalZombie),
                                false
                        )
                );
        var zombieInteractionFrames =
                new CombatSkillTestFixtures.MutableInteractionFrames(
                        interactionFrame(
                                SEQUENCE,
                                List.of(canonicalZombie),
                                false
                        )
                );
        EngageObservedEntitySkill canonicalZombieSkill = skill(
                new CombatSkillTestFixtures.RecordingCoreActuator(),
                zombieCoreFrames,
                new CombatSkillTestFixtures.RecordingInteractionActuator(),
                zombieInteractionFrames,
                CombatSkillPolicy.defaults()
        );
        assertTrue(canonicalZombieSkill.preconditions(
                context(1, false),
                parameters()
        ).isEmpty());
    }

    private static EngageObservedEntitySkill skill(
            CombatSkillTestFixtures.RecordingCoreActuator core,
            CombatSkillTestFixtures.MutableCoreFrames coreFrames,
            CombatSkillTestFixtures.RecordingInteractionActuator interaction,
            CombatSkillTestFixtures.MutableInteractionFrames interactionFrames,
            CombatSkillPolicy policy
    ) {
        return new EngageObservedEntitySkill(
                PLAYER_ID,
                core,
                coreFrames,
                interaction,
                interactionFrames,
                policy
        );
    }

    private static EngageObservedEntityParameters parameters() {
        return new EngageObservedEntityParameters(
                SEQUENCE,
                "visible-0"
        );
    }

    private static SkillContext context(
            long tick,
            boolean hardcore
    ) {
        return new SkillContext(
                1,
                SEQUENCE,
                tick,
                hardcore,
                true,
                0.0
        );
    }

    private static CombatSkillPolicy shortSearchPolicy() {
        CombatSkillPolicy defaults = CombatSkillPolicy.defaults();
        return new CombatSkillPolicy(
                defaults.maximumObservationAgeTicks(),
                defaults.maximumEngagementTicks(),
                16,
                16,
                1,
                2,
                defaults.scanYawDegrees(),
                defaults.attackCooldownThreshold(),
                defaults.attackReach(),
                defaults.preferredMaximumDistance(),
                defaults.tooCloseDistance(),
                defaults.guardDistance(),
                defaults.normalRetreatHealthFraction(),
                defaults.hardcoreRetreatHealthFraction(),
                defaults.normalMaximumStepDanger(),
                defaults.hardcoreMaximumStepDanger(),
                defaults.attackAlignmentDegrees(),
                defaults.movementAlignmentDegrees()
        );
    }

    private static CoreSkillFrame withCurrentCellOnly(
            CoreSkillFrame source
    ) {
        LocalNavSnapshot navigation = new LocalNavSnapshot(
                source.dimension(),
                source.observationRevision(),
                List.of(
                        new ObservedVoxel(
                                new GridPos(0, 0, 0),
                                VoxelKind.SOLID,
                                0.0,
                                source.observationRevision()
                        ),
                        new ObservedVoxel(
                                new GridPos(0, 1, 0),
                                VoxelKind.AIR,
                                0.0,
                                source.observationRevision()
                        ),
                        new ObservedVoxel(
                                new GridPos(0, 2, 0),
                                VoxelKind.AIR,
                                0.0,
                                source.observationRevision()
                        )
                )
        );
        return new CoreSkillFrame(
                source.playerId(),
                source.dimension(),
                source.gameTime(),
                source.observationRevision(),
                source.position(),
                source.eyePosition(),
                source.lookDirection(),
                source.onGround(),
                source.inWater(),
                source.danger(),
                navigation,
                source.visibleBlockFaces(),
                source.health(),
                source.maxHealth(),
                source.foodLevel(),
                source.inventory(),
                source.mainHand(),
                source.offHand(),
                source.visibleEntities(),
                source.dangerSignals()
        );
    }
}
