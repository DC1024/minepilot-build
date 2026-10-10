package dev.mcai.companion.skills.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SecureNetherBlazeMaterialSkillTest {
    private static final UUID PLAYER =
            UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final UUID BLAZE =
            UUID.fromString("50000000-0000-0000-0000-000000000002");

    @Test
    void acceptsNoArgumentsOnly() {
        final Fixture fixture = fixture(
                DimensionRef.NETHER,
                true,
                List.of()
        );

        assertTrue(
                fixture.skill.parameters()
                        .parse(List.of())
                        .value()
                        .isPresent()
        );
        assertTrue(
                fixture.skill.parameters()
                        .parse(List.of(
                                new SkillArgument(
                                        "maximumTicks",
                                        "1200"
                                )
                        ))
                        .value()
                        .isEmpty()
        );
    }

    @Test
    void requiresTheNetherAndAStablePose() {
        final Fixture overworld = fixture(
                DimensionRef.OVERWORLD,
                true,
                List.of()
        );
        assertEquals(
                SecureNetherBlazeMaterialSkill.NAME
                    + ".nether_required",
                overworld.skill.preconditions(
                        context(1),
                        NoParameters.INSTANCE
                ).orElseThrow().code()
        );

        final Fixture airborne = fixture(
                DimensionRef.NETHER,
                false,
                List.of()
        );
        assertEquals(
                SecureNetherBlazeMaterialSkill.NAME
                    + ".stable_pose_required",
                airborne.skill.preconditions(
                        context(1),
                        NoParameters.INSTANCE
                ).orElseThrow().code()
        );
    }

    @Test
    void completesOnlyWhenTheFullVerifiedReserveAlreadyExists() {
        final Fixture complete = fixture(
                DimensionRef.NETHER,
                true,
                List.of(new InventoryItemSummary(
                        "minecraft:blaze_rod",
                        7
                ))
        );
        complete.skill.start(context(1), NoParameters.INSTANCE);

        assertEquals(
                SkillTickResult.Status.COMPLETED,
                complete.skill.tick(
                        context(2),
                        NoParameters.INSTANCE
                ).status()
        );
        assertEquals(
                SkillResult.Status.COMPLETED,
                complete.skill.result(
                        context(2),
                        NoParameters.INSTANCE
                ).status()
        );
    }

    @Test
    void oneRodCannotShortcutTheRouteAndStartsFairScanning() {
        final Fixture incomplete = fixture(
                DimensionRef.NETHER,
                true,
                List.of(new InventoryItemSummary(
                        "minecraft:blaze_rod",
                        1
                ))
        );
        incomplete.skill.start(context(1), NoParameters.INSTANCE);

        assertEquals(
                SkillTickResult.Status.RUNNING,
                incomplete.skill.tick(
                        context(2),
                        NoParameters.INSTANCE
                ).status()
        );
        assertEquals(1, incomplete.core.looks);
        assertTrue(
                incomplete.skill.checkpoint(
                        context(2),
                        NoParameters.INSTANCE
                ).payload().contains("\"blazeRouteUnits\":2")
        );
    }

    @Test
    void scanViewDoesNotAdvanceBeforeTheBodyActuallyAligns() {
        final Fixture fixture = fixture(
                DimensionRef.NETHER,
                true,
                List.of()
        );
        fixture.skill.start(context(1), NoParameters.INSTANCE);
        fixture.skill.tick(context(2), NoParameters.INSTANCE);
        assertTrue(fixture.skill.checkpoint(
                context(2),
                NoParameters.INSTANCE
        ).payload().contains("\"scanTurns\":1"));

        fixture.skill.tick(context(7), NoParameters.INSTANCE);
        fixture.skill.tick(context(8), NoParameters.INSTANCE);
        assertTrue(
                fixture.skill.checkpoint(
                        context(8),
                        NoParameters.INSTANCE
                ).payload().contains("\"scanTurns\":1"),
                "A smooth-turn actuator must reach the requested pitch "
                    + "before the scan advances"
        );
    }

    @Test
    void activeReserveKeepsTheBoundedCombatRiskContractBetweenTargets() {
        final Fixture fixture = fixture(
                DimensionRef.NETHER,
                true,
                List.of(),
                0.9
        );
        final SkillContext hardcore =
                new SkillContext(1, 1, 1, true, true, 0.9);

        assertTrue(fixture.skill.hardcoreRiskThresholdOverride(
                hardcore,
                NoParameters.INSTANCE
        ).isEmpty());
        fixture.skill.start(hardcore, NoParameters.INSTANCE);
        assertEquals(
                1.0,
                fixture.skill.hardcoreRiskThresholdOverride(
                        hardcore,
                        NoParameters.INSTANCE
                ).orElseThrow()
        );
    }

    @Test
    void visibleBlazeIsOwnedBeforeTheFirstSupervisorTick() {
        final Fixture fixture = fixture(
                DimensionRef.NETHER,
                true,
                List.of(),
                0.9,
                List.of(blaze())
        );

        assertTrue(
                !fixture.skill.managesVisibleHostileProximity()
        );
        assertTrue(
                !fixture.skill.managesPhysicalContactThreats()
        );

        fixture.skill.start(context(1), NoParameters.INSTANCE);

        assertTrue(
                fixture.skill.managesVisibleHostileProximity(),
                "The emergency lane must not starve SELECTING before "
                    + "the first combat-child tick"
        );
        assertTrue(
                fixture.skill.managesPhysicalContactThreats(),
                "A contacting selected Blaze needs the same one-tick "
                    + "handoff ownership"
        );
    }

    @Test
    void onlyTheOrdinaryNoDropFailureIsRecoverable() {
        assertTrue(AcquireNetherBlazeRodSkill.isNoDropFailure(
                SkillFailure.of(
                        "engage_and_collect_observed_drop"
                            + ".expected_drop_not_observed"
                )
        ));
        assertTrue(!AcquireNetherBlazeRodSkill.isNoDropFailure(
                SkillFailure.of(
                        "engage_and_collect_observed_drop"
                            + ".health_too_low"
                )
        ));
    }

    private static Fixture fixture(
            final DimensionRef dimension,
            final boolean onGround,
            final List<InventoryItemSummary> inventory
    ) {
        return fixture(dimension, onGround, inventory, 0.0);
    }

    private static Fixture fixture(
            final DimensionRef dimension,
            final boolean onGround,
            final List<InventoryItemSummary> inventory,
            final double danger
    ) {
        return fixture(
                dimension,
                onGround,
                inventory,
                danger,
                List.of()
        );
    }

    private static Fixture fixture(
            final DimensionRef dimension,
            final boolean onGround,
            final List<InventoryItemSummary> inventory,
            final double danger,
            final List<VisibleEntity> visibleEntities
    ) {
        final CoreSkillFrame frame = new CoreSkillFrame(
                PLAYER,
                dimension,
                100,
                10,
                new PerceptionVec3(0.5, 1.0, 0.5),
                new PerceptionVec3(0.5, 2.62, 0.5),
                new PerceptionVec3(1.0, 0.0, 0.0),
                onGround,
                false,
                danger,
                new LocalNavSnapshot(
                        dimension,
                        10,
                        List.of()
                ),
                List.of(),
                20.0F,
                20.0F,
                20,
                inventory,
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                visibleEntities,
                List.of()
        );
        final RecordingCore core = new RecordingCore();
        final CoreSkillFrameSource coreFrames =
                () -> Optional.of(frame);
        final InteractionSkillFrameSource interactionFrames =
                Optional::empty;
        final NoopInteractions interactions =
                new NoopInteractions();
        return new Fixture(
                core,
                new SecureNetherBlazeMaterialSkill(
                        PLAYER,
                        core,
                        coreFrames,
                        interactions,
                        interactionFrames
                )
        );
    }

    private static VisibleEntity blaze() {
        return new VisibleEntity(
                BLAZE,
                "minecraft:blaze",
                new PerceptionVec3(0.5, 1.0, 6.5),
                new PerceptionVec3(0.0, 0.0, 6.0),
                6.0,
                true,
                false,
                PerceptionProvenance
                    .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(1, 1, tick, false, true, 0.0);
    }

    private record Fixture(
            RecordingCore core,
            SecureNetherBlazeMaterialSkill skill
    ) {
    }

    private static final class RecordingCore
            implements CoreSkillActuator {
        private int looks;

        @Override
        public ActionOutcome move(final MovementIntent intent) {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome look(final LookIntent intent) {
            looks++;
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
    }

    private static final class NoopInteractions
            implements InteractionSkillActuator {
        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(1);
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
            return ActionOutcome.INVALID_PLAYER_STATE;
        }

        @Override
        public OptionalDouble attackStrengthScale() {
            return OptionalDouble.of(1.0);
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }

        @Override
        public ActionOutcome continueUsing(
                final ActionHand hand
        ) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome releaseUse() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
    }
}
