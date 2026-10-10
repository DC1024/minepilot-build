package dev.mcai.companion.skills.combat;

import static dev.mcai.companion.skills.combat.CombatSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.combat.CombatSkillTestFixtures.SEQUENCE;
import static dev.mcai.companion.skills.combat.CombatSkillTestFixtures.TARGET_ID;
import static dev.mcai.companion.skills.combat.CombatSkillTestFixtures.hostile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ShootObservedEntitySkillTest {
    @Test
    void chargesAndReleasesOneVanillaBowShotAtBoundTarget() {
        final VisibleEntity target = hostile(
                TARGET_ID,
                "minecraft:zombie",
                2.5
        );
        final PerceptionVec3 aim = bowAim(target);
        final var coreFrames =
                new CombatSkillTestFixtures.MutableCoreFrames(
                        rangedCoreFrame(target, aim)
                );
        final var interactionFrames =
                new CombatSkillTestFixtures.MutableInteractionFrames(
                        rangedInteractionFrame(target)
                );
        final var core =
                new CombatSkillTestFixtures.RecordingCoreActuator();
        final var interaction =
                new CombatSkillTestFixtures
                        .RecordingInteractionActuator();
        final ShootObservedEntitySkill skill =
                new ShootObservedEntitySkill(
                        PLAYER_ID,
                        core,
                        coreFrames,
                        interaction,
                        interactionFrames,
                        RangedCombatSkillPolicy.defaults()
                );
        final ShootObservedEntityParameters parameters =
                new ShootObservedEntityParameters(
                        SEQUENCE,
                        "visible-0",
                        ActionHand.MAIN_HAND,
                        1
                );

        assertTrue(
                skill.preconditions(context(1), parameters).isEmpty()
        );
        skill.start(context(1), parameters);
        SkillTickResult result = skill.tick(context(2), parameters);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                result.status()
        );
        assertEquals(1, interaction.useCalls);

        for (long tick = 3;
                tick <= 22
                        && result.status()
                        == SkillTickResult.Status.RUNNING;
                tick++) {
            result = skill.tick(context(tick), parameters);
        }

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                result.status()
        );
        assertEquals(1, interaction.releaseCalls);
        assertTrue(core.stops > 0);
        assertFalse(
                skill.checkpoint(context(22), parameters)
                        .payload()
                        .contains(TARGET_ID.toString())
        );
    }

    @Test
    void rejectsMissingAmmunitionAndUnsafeCrystalBlastRange() {
        final VisibleEntity target = hostile(
                TARGET_ID,
                "minecraft:zombie",
                2.5
        );
        final CoreSkillFrame withNoArrow =
                rangedCoreFrame(target, bowAim(target));
        final CoreSkillFrame noArrow = new CoreSkillFrame(
                withNoArrow.playerId(),
                withNoArrow.dimension(),
                withNoArrow.gameTime(),
                withNoArrow.observationRevision(),
                withNoArrow.position(),
                withNoArrow.eyePosition(),
                withNoArrow.lookDirection(),
                withNoArrow.onGround(),
                withNoArrow.inWater(),
                withNoArrow.danger(),
                withNoArrow.navigation(),
                withNoArrow.visibleBlockFaces(),
                withNoArrow.health(),
                withNoArrow.maxHealth(),
                withNoArrow.foodLevel(),
                List.of(),
                withNoArrow.mainHand(),
                withNoArrow.offHand(),
                withNoArrow.visibleEntities(),
                withNoArrow.dangerSignals()
        );
        final var noArrowSkill = skill(
                noArrow,
                rangedInteractionFrame(target)
        );
        assertEquals(
                "shoot_observed_entity.ammunition_unavailable",
                noArrowSkill.preconditions(
                                context(1),
                                parameters()
                        )
                        .orElseThrow()
                        .code()
        );

        final VisibleEntity crystal =
                CombatSkillTestFixtures.entity(
                        TARGET_ID,
                        "minecraft:end_crystal",
                        3.5,
                        false,
                        false
                );
        final var closeCrystalSkill = skill(
                rangedCoreFrame(crystal, bowAim(crystal)),
                rangedInteractionFrame(crystal)
        );
        assertEquals(
                "shoot_observed_entity.crystal_too_close",
                closeCrystalSkill.preconditions(
                                context(1),
                                parameters()
                        )
                        .orElseThrow()
                        .code()
        );
    }

    @Test
    void refusesAVisibleTargetWhoseStraightInteractionLineIsBlocked() {
        final VisibleEntity target = new VisibleEntity(
                TARGET_ID,
                "minecraft:end_crystal",
                new PerceptionVec3(10.5, 1.0, 0.5),
                new PerceptionVec3(10.0, 0.0, 0.0),
                10.0,
                false,
                false,
                dev.mcai.companion.perception
                        .PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                Map.of("interactionLineClear", "false")
        );
        final ShootObservedEntitySkill skill = skill(
                rangedCoreFrame(target, bowAim(target)),
                rangedInteractionFrame(target)
        );

        assertEquals(
                "shoot_observed_entity.interaction_line_blocked",
                skill.preconditions(
                        context(1),
                        parameters()
                ).orElseThrow().code()
        );
    }

    @Test
    void leadsAMovingTargetOnlyFromSuccessiveVisiblePositions() {
        final VisibleEntity initial = movingTarget(0.5);
        final PerceptionVec3 initialAim = bowAim(initial);
        final CoreSkillFrame initialCore =
                rangedCoreFrame(initial, initialAim);
        final var coreFrames =
                new CombatSkillTestFixtures.MutableCoreFrames(
                        initialCore
                );
        final var interactionFrames =
                new CombatSkillTestFixtures
                        .MutableInteractionFrames(
                                rangedInteractionFrame(initial)
                        );
        final var core =
                new CombatSkillTestFixtures.RecordingCoreActuator();
        final ShootObservedEntitySkill skill =
                new ShootObservedEntitySkill(
                        PLAYER_ID,
                        core,
                        coreFrames,
                        new CombatSkillTestFixtures
                                .RecordingInteractionActuator(),
                        interactionFrames,
                        RangedCombatSkillPolicy.defaults()
                );
        final ShootObservedEntityParameters parameters = parameters();

        skill.start(context(1), parameters);
        skill.tick(context(2), parameters);

        final VisibleEntity moved = movingTarget(4.5);
        coreFrames.frame = rangedCoreFrameAt(
                initialCore,
                moved,
                SEQUENCE + 1,
                104,
                initialAim
        );
        interactionFrames.frame = rangedInteractionFrameAt(
                moved,
                SEQUENCE + 1,
                104
        );
        skill.tick(context(3), parameters);

        final float currentPositionYaw = (float) Math.toDegrees(
                Math.atan2(-20.0, 4.0)
        );
        assertTrue(
                core.looks.getLast().yawDegrees()
                    > currentPositionYaw + 5.0F,
                "Successive visible motion should lead ahead of the "
                    + "target's current position"
        );
    }

    @Test
    void pillagerUsesHumanoidTorsoAimInsteadOfMinecartHeight() {
        final VisibleEntity target = hostile(
                TARGET_ID,
                "minecraft:pillager",
                10.0
        );
        final PerceptionVec3 expectedAim = bowAim(target);
        final var core =
                new CombatSkillTestFixtures.RecordingCoreActuator();
        final ShootObservedEntitySkill skill =
                new ShootObservedEntitySkill(
                        PLAYER_ID,
                        core,
                        new CombatSkillTestFixtures.MutableCoreFrames(
                                rangedCoreFrame(target, expectedAim)
                        ),
                        new CombatSkillTestFixtures
                                .RecordingInteractionActuator(),
                        new CombatSkillTestFixtures
                                .MutableInteractionFrames(
                                    rangedInteractionFrame(target)
                                ),
                        RangedCombatSkillPolicy.defaults()
                );

        skill.start(context(1), parameters());
        skill.tick(context(2), parameters());

        final float expectedPitch = (float) Math.toDegrees(
                Math.atan2(
                        -expectedAim.y(),
                        Math.hypot(
                                expectedAim.x(),
                                expectedAim.z()
                        )
                )
        );
        assertEquals(
                expectedPitch,
                core.looks.getLast().pitchDegrees(),
                1.0E-4F
        );
    }

    private static ShootObservedEntitySkill skill(
            final CoreSkillFrame core,
            final InteractionSkillFrame interaction
    ) {
        return new ShootObservedEntitySkill(
                PLAYER_ID,
                new CombatSkillTestFixtures.RecordingCoreActuator(),
                new CombatSkillTestFixtures.MutableCoreFrames(core),
                new CombatSkillTestFixtures
                        .RecordingInteractionActuator(),
                new CombatSkillTestFixtures.MutableInteractionFrames(
                        interaction
                ),
                RangedCombatSkillPolicy.defaults()
        );
    }

    private static ShootObservedEntityParameters parameters() {
        return new ShootObservedEntityParameters(
                SEQUENCE,
                "visible-0",
                ActionHand.MAIN_HAND,
                1
        );
    }

    private static CoreSkillFrame rangedCoreFrame(
            final VisibleEntity target,
            final PerceptionVec3 look
    ) {
        final CoreSkillFrame base =
                CombatSkillTestFixtures.coreFrame(
                        SEQUENCE,
                        20.0F,
                        look,
                        List.of(target),
                        false
                );
        return new CoreSkillFrame(
                base.playerId(),
                base.dimension(),
                base.gameTime(),
                base.observationRevision(),
                base.position(),
                base.eyePosition(),
                base.lookDirection(),
                base.onGround(),
                base.inWater(),
                base.danger(),
                base.navigation(),
                base.visibleBlockFaces(),
                base.health(),
                base.maxHealth(),
                base.foodLevel(),
                List.of(
                        new InventoryItemSummary(
                                "minecraft:arrow",
                                16
                        ),
                        new InventoryItemSummary(
                                "minecraft:bow",
                                1
                        )
                ),
                new HeldItemSummary(
                        "minecraft:bow",
                        1,
                        0,
                        384
                ),
                HeldItemSummary.empty(),
                base.visibleEntities(),
                base.dangerSignals()
        );
    }

    private static InteractionSkillFrame rangedInteractionFrame(
            final VisibleEntity target
    ) {
        return new InteractionSkillFrame(
                PLAYER_ID,
                dev.mcai.companion.waypoint.DimensionRef.OVERWORLD,
                100,
                100,
                SEQUENCE,
                CombatSkillTestFixtures.SESSION,
                new HeldItemSummary(
                        "minecraft:bow",
                        1,
                        0,
                        384
                ),
                HeldItemSummary.empty(),
                List.of(target),
                List.of(),
                List.of(
                        new InventoryItemSummary(
                                "minecraft:arrow",
                                16
                        ),
                        new InventoryItemSummary(
                                "minecraft:bow",
                                1
                        )
                )
        );
    }

    private static VisibleEntity movingTarget(final double z) {
        final PerceptionVec3 position =
                new PerceptionVec3(20.5, 1.0, z);
        final PerceptionVec3 relative =
                position.subtract(
                        new PerceptionVec3(0.5, 1.0, 0.5)
                );
        return new VisibleEntity(
                TARGET_ID,
                "minecraft:zombie",
                position,
                relative,
                relative.length(),
                true,
                false,
                dev.mcai.companion.perception
                        .PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
                Map.of("interactionLineClear", "true")
        );
    }

    private static CoreSkillFrame rangedCoreFrameAt(
            final CoreSkillFrame base,
            final VisibleEntity target,
            final long sequence,
            final long gameTime,
            final PerceptionVec3 look
    ) {
        return new CoreSkillFrame(
                base.playerId(),
                base.dimension(),
                gameTime,
                sequence,
                base.position(),
                base.eyePosition(),
                look,
                base.onGround(),
                base.inWater(),
                base.danger(),
                base.navigation(),
                base.visibleBlockFaces(),
                base.health(),
                base.maxHealth(),
                base.foodLevel(),
                base.inventory(),
                base.mainHand(),
                base.offHand(),
                List.of(target),
                base.dangerSignals()
        );
    }

    private static InteractionSkillFrame rangedInteractionFrameAt(
            final VisibleEntity target,
            final long sequence,
            final long gameTime
    ) {
        return new InteractionSkillFrame(
                PLAYER_ID,
                dev.mcai.companion.waypoint.DimensionRef.OVERWORLD,
                gameTime,
                gameTime,
                sequence,
                CombatSkillTestFixtures.SESSION,
                new HeldItemSummary(
                        "minecraft:bow",
                        1,
                        0,
                        384
                ),
                HeldItemSummary.empty(),
                List.of(target),
                List.of(),
                List.of(
                        new InventoryItemSummary(
                                "minecraft:arrow",
                                16
                        ),
                        new InventoryItemSummary(
                                "minecraft:bow",
                                1
                        )
                )
        );
    }

    private static PerceptionVec3 bowAim(
            final VisibleEntity target
    ) {
        final PerceptionVec3 eye =
                new PerceptionVec3(0.5, 2.62, 0.5);
        final double height =
                "minecraft:pillager".equals(target.entityTypeId())
                    ? 1.5
                    : 1.2;
        final PerceptionVec3 base = target.position().add(
                new PerceptionVec3(0.0, height, 0.0)
        );
        final double horizontal = Math.hypot(
                base.x() - eye.x(),
                base.z() - eye.z()
        );
        final double ticks = horizontal / 3.0;
        final double compensation = 0.5
                * 0.05
                * ticks
                * ticks;
        return base.add(new PerceptionVec3(
                0.0,
                compensation,
                0.0
        )).subtract(eye).normalized();
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(
                1,
                SEQUENCE,
                tick,
                false,
                true,
                0.0
        );
    }
}
