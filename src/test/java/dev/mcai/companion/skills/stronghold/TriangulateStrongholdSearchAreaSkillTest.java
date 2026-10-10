package dev.mcai.companion.skills.stronghold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.inventory.CraftRecipeParameters;
import dev.mcai.companion.skills.inventory.DropItemParameters;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class TriangulateStrongholdSearchAreaSkillTest {
    private static final UUID PLAYER_ID = UUID.fromString(
            "62000000-0000-0000-0000-000000000001"
    );

    @Test
    void defaultTargetsArePerpendicularAndPreferTheNearerSide() {
        final EyeTraceSnapshot trace = trace(
                new PerceptionVec3(10.0, 64.0, 20.0),
                0.6,
                0.8,
                20
        );
        final PerceptionVec3 current = new PerceptionVec3(
                -180.0,
                70.0,
                165.0
        );

        final List<PerceptionVec3> targets =
                TriangulateStrongholdSearchAreaSkill
                        .baselineTravelTargets(
                                trace,
                                current,
                                TriangulateStrongholdSearchAreaSkill
                                        .DEFAULT_BASELINE_DISTANCE
                        );

        assertEquals(2, targets.size());
        assertTrue(
                targets.getFirst().subtract(current).lengthSquared()
                    <= targets.getLast().subtract(current).lengthSquared()
        );
        for (PerceptionVec3 target : targets) {
            final double dx = target.x() - trace.throwOrigin().x();
            final double dz = target.z() - trace.throwOrigin().z();
            assertEquals(
                    TriangulateStrongholdSearchAreaSkill
                            .DEFAULT_BASELINE_DISTANCE,
                    Math.hypot(dx, dz),
                    1.0E-6
            );
            assertEquals(
                    0.0,
                    dx * trace.directionX()
                        + dz * trace.directionZ(),
                    1.0E-6
            );
            assertEquals(current.y(), target.y(), 1.0E-6);
        }
    }

    @Test
    void fromScratchRequiresTwoOwnedEyes() {
        final EyeTraceResultBuffer results =
                new EyeTraceResultBuffer();
        final TriangulateStrongholdSearchAreaSkill skill =
                skill(frame(1, 1), results);

        assertEquals(
                "triangulate_stronghold_search_area"
                    + ".insufficient_ender_eyes",
                skill.preconditions(
                        context(1),
                        NoParameters.INSTANCE
                ).orElseThrow().code()
        );
        assertTrue(
                skill(frame(1, 2), results).preconditions(
                        context(1),
                        NoParameters.INSTANCE
                ).isEmpty()
        );
    }

    @Test
    void anExistingIntersectionCompletesWithoutAnotherEye() {
        final EyeTraceResultBuffer results =
                new EyeTraceResultBuffer();
        results.publish(trace(
                new PerceptionVec3(0.0, 64.0, 0.0),
                0.4472135954999579,
                0.8944271909999159,
                20
        ));
        results.publish(trace(
                new PerceptionVec3(100.0, 64.0, 0.0),
                -0.4472135954999579,
                0.8944271909999159,
                30
        ));
        final AtomicLong publishedRevision =
                new AtomicLong(-1L);
        final TriangulateStrongholdSearchAreaSkill skill =
                skill(
                        frame(40, 0),
                        results,
                        publishedRevision::set
                );

        assertTrue(
                skill.preconditions(
                        context(40),
                        NoParameters.INSTANCE
                ).isEmpty()
        );
        skill.start(context(40), NoParameters.INSTANCE);
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(
                        context(41),
                        NoParameters.INSTANCE
                ).status()
        );
        assertEquals(7L, publishedRevision.get());
    }

    @Test
    void fullRegistrationAddsTheNoArgumentCompound() {
        final SkillRegistry registry = StrongholdSkills.registerAll(
                new SkillRegistry(),
                PLAYER_ID,
                new AcceptedCoreActuator(),
                () -> Optional.of(frame(1, 2)),
                new AcceptedInventoryActuator(),
                new EyeTraceResultBuffer(),
                () -> 1L
        );

        assertEquals(
                java.util.Set.of(
                        "trace_stronghold_eye",
                        "triangulate_stronghold_search_area"
                ),
                registry.names()
        );
        assertTrue(
                registry.modelArgumentValidators()
                        .get("triangulate_stronghold_search_area")
                        .validate(List.of())
                        .isEmpty()
        );
    }

    private static TriangulateStrongholdSearchAreaSkill skill(
            final CoreSkillFrame frame,
            final EyeTraceResultBuffer results
    ) {
        return skill(frame, results, ignored -> {
        });
    }

    private static TriangulateStrongholdSearchAreaSkill skill(
            final CoreSkillFrame frame,
            final EyeTraceResultBuffer results,
            final java.util.function.LongConsumer completionSink
    ) {
        final CoreSkillFrameSource frames =
                () -> Optional.of(frame);
        return new TriangulateStrongholdSearchAreaSkill(
                PLAYER_ID,
                new AcceptedCoreActuator(),
                frames,
                new AcceptedInventoryActuator(),
                results,
                () -> 1L,
                completionSink
        );
    }

    private static CoreSkillFrame frame(
            final long revision,
            final int eyeCount
    ) {
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                revision,
                revision,
                new PerceptionVec3(0.5, 64.0, 0.5),
                new PerceptionVec3(0.5, 65.62, 0.5),
                new PerceptionVec3(0.0, 0.0, 1.0),
                true,
                false,
                0.0,
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        revision,
                        List.of()
                ),
                List.of(),
                20.0F,
                20.0F,
                20,
                eyeCount == 0
                        ? List.of()
                        : List.of(new InventoryItemSummary(
                                TraceStrongholdEyeSkill.EYE_ITEM_ID,
                                eyeCount
                        )),
                eyeCount == 0
                        ? HeldItemSummary.empty()
                        : new HeldItemSummary(
                                TraceStrongholdEyeSkill.EYE_ITEM_ID,
                                eyeCount,
                                0,
                                0
                        ),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
    }

    private static EyeTraceSnapshot trace(
            final PerceptionVec3 origin,
            final double directionX,
            final double directionZ,
            final long revision
    ) {
        return new EyeTraceSnapshot(
                7,
                DimensionRef.OVERWORLD,
                origin,
                200 + revision,
                revision,
                revision + 1,
                List.of(
                        new EyeTraceSnapshot.Sample(
                                revision,
                                origin.add(new PerceptionVec3(
                                        directionX,
                                        1.0,
                                        directionZ
                                ))
                        ),
                        new EyeTraceSnapshot.Sample(
                                revision + 1,
                                origin.add(new PerceptionVec3(
                                        directionX * 6.0,
                                        2.0,
                                        directionZ * 6.0
                                ))
                        )
                ),
                directionX,
                directionZ,
                Math.toDegrees(Math.atan2(-directionX, directionZ)),
                5.0
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(7, 10, tick, false, true, 0.0);
    }

    private static final class AcceptedCoreActuator
            implements CoreSkillActuator {
        @Override
        public ActionOutcome move(final MovementIntent intent) {
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
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome useMainHandOn(
                final BlockInteractionTarget target
        ) {
            return ActionOutcome.QUEUED;
        }

        @Override
        public ActionOutcome useItem(final ActionHand hand) {
            return ActionOutcome.QUEUED;
        }
    }

    private static final class AcceptedInventoryActuator
            implements InventorySkillActuator {
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
