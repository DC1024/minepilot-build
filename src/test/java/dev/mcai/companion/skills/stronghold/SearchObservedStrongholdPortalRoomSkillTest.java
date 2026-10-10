package dev.mcai.companion.skills.stronghold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalAStarPlanner;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillPolicy;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class SearchObservedStrongholdPortalRoomSkillTest {
    private static final UUID PLAYER_ID = UUID.fromString(
            "63000000-0000-0000-0000-000000000001"
    );
    private static final long REVISION = 12;
    private static final GridPos START =
            new GridPos(0, 64, 0);

    @Test
    void selectsOnlyAOneStepFairlyObservedSafeFrontier() {
        final GridPos adjacent = START.offset(0, 0, 1);
        final GridPos hiddenAcrossGap = START.offset(0, 0, 2);
        final CoreSkillFrame frame = frame(
                List.of(adjacent, hiddenAcrossGap),
                List.of(strongholdFace())
        );

        assertEquals(
                Optional.of(adjacent),
                SearchObservedStrongholdPortalRoomSkill
                        .nextObservedAdjacentFrontier(
                                frame,
                                START,
                                Set.of(START),
                                Set.of(),
                                true,
                                new LocalAStarPlanner(),
                                CoreSkillPolicy.defaults()
                        )
        );
        assertTrue(
                SearchObservedStrongholdPortalRoomSkill
                    .hasObservedAdjacentFrontier(frame, true)
        );
        assertTrue(
                SearchObservedStrongholdPortalRoomSkill
                    .hasObservedAdjacentFrontier(
                        frame,
                        true,
                        0,
                        1
                    )
        );
        assertFalse(
                SearchObservedStrongholdPortalRoomSkill
                    .hasObservedAdjacentFrontier(
                        frame,
                        true,
                        1,
                        0
                    )
        );
        assertFalse(
                SearchObservedStrongholdPortalRoomSkill
                        .nextObservedAdjacentFrontier(
                                frame,
                                START,
                                Set.of(START, adjacent),
                                Set.of(),
                                true,
                                new LocalAStarPlanner(),
                                CoreSkillPolicy.defaults()
                        )
                        .isPresent()
        );
    }

    @Test
    void dangerousOrRejectedAdjacentCellCannotBecomeAFrontier() {
        final GridPos adjacent = START.offset(1, 0, 0);
        final CoreSkillFrame dangerous = frame(
                List.of(),
                List.of(strongholdFace()),
                List.of(
                        air(adjacent, 0.20),
                        air(adjacent.above(), 0.20),
                        support(adjacent.below(), 0.20)
                )
        );

        assertTrue(
                SearchObservedStrongholdPortalRoomSkill
                        .nextObservedAdjacentFrontier(
                                dangerous,
                                START,
                                Set.of(START),
                                Set.of(),
                                true,
                                new LocalAStarPlanner(),
                                CoreSkillPolicy.defaults()
                        )
                        .isEmpty()
        );
        assertTrue(
                SearchObservedStrongholdPortalRoomSkill
                        .nextObservedAdjacentFrontier(
                                frame(
                                        List.of(adjacent),
                                        List.of(strongholdFace())
                                ),
                                START,
                                Set.of(START),
                                Set.of(adjacent),
                                false,
                                new LocalAStarPlanner(),
                                CoreSkillPolicy.defaults()
                        )
                        .isEmpty()
        );
    }

    @Test
    void completesOnlyFromEnoughCurrentPortalRingEvidence() {
        final MutableFrames frames = new MutableFrames(frame(
                List.of(),
                List.of(strongholdFace())
        ));
        final SearchObservedStrongholdPortalRoomSkill skill =
                new SearchObservedStrongholdPortalRoomSkill(
                        PLAYER_ID,
                        new AcceptedActuator(),
                        frames,
                        () -> 7L
                );
        assertTrue(
                skill.preconditions(
                        context(20),
                        NoParameters.INSTANCE
                ).isEmpty()
        );
        skill.start(context(20), NoParameters.INSTANCE);

        frames.frame = frame(
                List.of(),
                List.of(portalFrame())
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(
                        context(21),
                        NoParameters.INSTANCE
                ).status()
        );

        frames.frame = frame(
                List.of(),
                portalRingEvidence()
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(
                        context(22),
                        NoParameters.INSTANCE
                ).status()
        );
        assertTrue(
                skill.checkpoint(
                        context(22),
                        NoParameters.INSTANCE
                ).payload().contains("\"phase\":\"COMPLETED\"")
        );
    }

    @Test
    void doesNotWalkFromAViewWithoutAStrongholdFloorUnderfoot() {
        final CountingActuator actuator = new CountingActuator();
        final MutableFrames frames = new MutableFrames(frame(
                List.of(),
                List.of(strongholdFace())
        ));
        final SearchObservedStrongholdPortalRoomSkill skill =
                new SearchObservedStrongholdPortalRoomSkill(
                        PLAYER_ID,
                        actuator,
                        frames,
                        () -> 7L
                );
        skill.start(context(20), NoParameters.INSTANCE);

        final CoreSkillFrame base = frames.frame;
        final double pitch = Math.toRadians(20.0);
        frames.frame = new CoreSkillFrame(
                base.playerId(),
                base.dimension(),
                REVISION + 1,
                REVISION + 1,
                base.position(),
                base.eyePosition(),
                new PerceptionVec3(
                        0.0,
                        -Math.sin(pitch),
                        Math.cos(pitch)
                ),
                base.onGround(),
                base.inWater(),
                base.danger(),
                base.navigation(),
                List.of(),
                base.health(),
                base.maxHealth(),
                base.foodLevel(),
                base.inventory(),
                base.mainHand(),
                base.offHand(),
                base.visibleEntities(),
                base.dangerSignals()
        );

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(21), NoParameters.INSTANCE).status()
        );
        assertEquals(
                0,
                actuator.moveCalls,
                "The search must not walk into an unverified open area"
        );
    }

    @Test
    void refusesToStartWithoutFirstPersonStrongholdEvidence() {
        final SearchObservedStrongholdPortalRoomSkill skill =
                new SearchObservedStrongholdPortalRoomSkill(
                        PLAYER_ID,
                        new AcceptedActuator(),
                        new MutableFrames(frame(List.of(), List.of())),
                        () -> 7L
                );

        assertEquals(
                SearchObservedStrongholdPortalRoomSkill.NAME
                        + ".stronghold_evidence_required",
                skill.preconditions(
                        context(1),
                        NoParameters.INSTANCE
                ).orElseThrow().code()
        );
    }

    private static CoreSkillFrame frame(
            final List<GridPos> safeDestinations,
            final List<VisibleBlockFace> faces
    ) {
        return frame(safeDestinations, faces, List.of());
    }

    private static CoreSkillFrame frame(
            final List<GridPos> safeDestinations,
            final List<VisibleBlockFace> faces,
            final List<ObservedVoxel> extras
    ) {
        final List<ObservedVoxel> voxels = new ArrayList<>();
        voxels.add(new ObservedVoxel(
                START,
                VoxelKind.AIR,
                0.0,
                REVISION,
                OccupancyEvidence.BODY_OCCUPIED,
                TopSupportAffordance.UNKNOWN
        ));
        voxels.add(new ObservedVoxel(
                START.above(),
                VoxelKind.AIR,
                0.0,
                REVISION,
                OccupancyEvidence.BODY_OCCUPIED,
                TopSupportAffordance.UNKNOWN
        ));
        voxels.add(new ObservedVoxel(
                START.below(),
                VoxelKind.SOLID,
                0.0,
                REVISION,
                OccupancyEvidence.BODY_CONTACT,
                TopSupportAffordance.STURDY_FULL_TOP
        ));
        for (GridPos destination : safeDestinations) {
            voxels.add(air(destination, 0.0));
            voxels.add(air(destination.above(), 0.0));
            voxels.add(support(destination.below(), 0.0));
        }
        voxels.addAll(extras);
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                REVISION,
                REVISION,
                new PerceptionVec3(0.5, 64.0, 0.5),
                new PerceptionVec3(0.5, 65.62, 0.5),
                new PerceptionVec3(0.0, 0.0, 1.0),
                true,
                false,
                0.0,
                new LocalNavSnapshot(
                        DimensionRef.OVERWORLD,
                        REVISION,
                        voxels
                ),
                faces
        );
    }

    private static ObservedVoxel air(
            final GridPos position,
            final double danger
    ) {
        return new ObservedVoxel(
                position,
                VoxelKind.AIR,
                danger,
                REVISION,
                OccupancyEvidence.MULTI_RAY_CLEAR,
                TopSupportAffordance.UNKNOWN
        );
    }

    private static ObservedVoxel support(
            final GridPos position,
            final double danger
    ) {
        return new ObservedVoxel(
                position,
                VoxelKind.SOLID,
                danger,
                REVISION,
                OccupancyEvidence.SURFACE_HIT,
                TopSupportAffordance.STURDY_FULL_TOP
        );
    }

    private static VisibleBlockFace strongholdFace() {
        return face("minecraft:stone_bricks");
    }

    private static VisibleBlockFace portalFrame() {
        return portalFrame(0, 2, "south");
    }

    private static List<VisibleBlockFace> portalRingEvidence() {
        return List.of(
                portalFrame(0, 2, "south"),
                portalFrame(-2, 4, "east")
        );
    }

    private static VisibleBlockFace portalFrame(
            final int x,
            final int z,
            final String facing
    ) {
        return new VisibleBlockFace(
                new BlockCoordinate(x, 63, z),
                "minecraft:end_portal_frame",
                "north",
                new PerceptionVec3(x + 0.5, 63.8, z),
                2.5,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of("eye", "false", "facing", facing),
                TopSupportAffordance.STURDY_FULL_TOP
        );
    }

    private static VisibleBlockFace face(final String blockId) {
        return new VisibleBlockFace(
                new BlockCoordinate(0, 63, 2),
                blockId,
                "north",
                new PerceptionVec3(0.5, 63.8, 2.0),
                2.5,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of(),
                TopSupportAffordance.STURDY_FULL_TOP
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(1, REVISION, tick, true, true, 0.0);
    }

    private static final class MutableFrames
            implements dev.mcai.companion.skills.core
                    .CoreSkillFrameSource {
        private CoreSkillFrame frame;

        private MutableFrames(final CoreSkillFrame frame) {
            this.frame = frame;
        }

        @Override
        public Optional<CoreSkillFrame> current() {
            return Optional.of(frame);
        }
    }

    private static class AcceptedActuator
            implements CoreSkillActuator {
        int moveCalls;

        @Override
        public ActionOutcome move(final MovementIntent intent) {
            moveCalls++;
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
            return ActionOutcome.DISPATCHED;
        }
    }

    private static final class CountingActuator extends AcceptedActuator {
    }
}
