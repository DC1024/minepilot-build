package dev.mcai.companion.skills.portal;

import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.PLAYER_ID;
import static dev.mcai.companion.skills.portal.PortalSkillTestFixtures.frame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.memory.transport.VerifiedPortalEdge;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.waypoint.DimensionRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class ReturnViaVerifiedPortalSkillTest {
    private static final long SESSION = 7L;
    private static final UUID WORLD = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final PerceptionVec3 ARRIVAL =
            new PerceptionVec3(1.5, 64.0, 0.5);

    @Test
    void arrivalMemoryRequiresWalkingReobservationAndANewTraversal() {
        final MutableCoreFrames coreFrames =
                new MutableCoreFrames(coreFrame(10L));
        final var portalFrames =
                new PortalSkillTestFixtures.MutableFrames(frame(
                        DimensionRef.NETHER,
                        DimensionRef.NETHER,
                        0L,
                        10L,
                        SESSION,
                        ARRIVAL,
                        PortalKind.NETHER_PORTAL
                ));
        final var actuator =
                new PortalSkillTestFixtures.RecordingActuator();
        final var traversals = new PortalTraversalBuffer();
        final var skill = new ReturnViaVerifiedPortalSkill(
                PLAYER_ID,
                actuator,
                coreFrames,
                portalFrames,
                () -> SESSION,
                (dimension, position, radius, limit) ->
                        CompletableFuture.completedFuture(
                                List.of(arrivalEdge())
                        ),
                PortalSkillPolicy.defaults(),
                traversals
        );

        assertTrue(skill.preconditions(
                context(0L),
                NoParameters.INSTANCE
        ).isEmpty());
        skill.start(context(0L), NoParameters.INSTANCE);
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(0L), NoParameters.INSTANCE)
                        .status()
        );
        assertTrue(skill.checkpoint(
                context(0L),
                NoParameters.INSTANCE
        ).payload().contains("\"phase\":\"TRAVELLING\""));

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(1L), NoParameters.INSTANCE)
                        .status()
        );
        assertTrue(skill.checkpoint(
                context(1L),
                NoParameters.INSTANCE
        ).payload().contains("\"phase\":\"ENTERING\""));

        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(2L), NoParameters.INSTANCE)
                        .status()
        );
        assertEquals(
                SkillTickResult.Status.RUNNING,
                skill.tick(context(3L), NoParameters.INSTANCE)
                        .status()
        );
        assertTrue(
                !actuator.movements.isEmpty(),
                "A remembered arrival must still use ordinary movement"
        );

        portalFrames.frame = frame(
                DimensionRef.OVERWORLD,
                DimensionRef.NETHER,
                4L,
                10L,
                SESSION,
                new PerceptionVec3(8.5, 70.0, 8.5),
                PortalKind.NETHER_PORTAL
        );
        assertEquals(
                SkillTickResult.Status.COMPLETED,
                skill.tick(context(4L), NoParameters.INSTANCE)
                        .status()
        );
        assertEquals(
                DimensionRef.OVERWORLD,
                traversals.latest().orElseThrow()
                        .destinationDimension()
        );
    }

    @Test
    void absentArrivalMemoryFailsWithoutInventingACoordinate() {
        final var skill = new ReturnViaVerifiedPortalSkill(
                PLAYER_ID,
                new PortalSkillTestFixtures.RecordingActuator(),
                new MutableCoreFrames(coreFrame(10L)),
                new PortalSkillTestFixtures.MutableFrames(frame(
                        DimensionRef.NETHER,
                        DimensionRef.NETHER,
                        0L,
                        10L,
                        SESSION,
                        ARRIVAL,
                        PortalKind.NETHER_PORTAL
                )),
                () -> SESSION,
                (dimension, position, radius, limit) ->
                        CompletableFuture.completedFuture(List.of()),
                PortalSkillPolicy.defaults(),
                PortalTraversalObserver.NOOP
        );

        skill.start(context(0L), NoParameters.INSTANCE);
        SkillTickResult failed = null;
        for (long tick = 0L; tick <= 201L; tick++) {
            failed = skill.tick(
                    context(tick),
                    NoParameters.INSTANCE
            );
            if (failed.status() == SkillTickResult.Status.FAILED) {
                break;
            }
        }
        assertEquals(SkillTickResult.Status.FAILED, failed.status());
        assertEquals(
                "return_via_verified_portal.arrival_not_remembered",
                failed.failure().orElseThrow().code()
        );
    }

    private static CoreSkillFrame coreFrame(final long revision) {
        final List<ObservedVoxel> voxels = List.of(
                voxel(
                        new GridPos(1, 63, 0),
                        VoxelKind.SOLID,
                        revision,
                        OccupancyEvidence.SURFACE_HIT,
                        TopSupportAffordance.STURDY_FULL_TOP
                ),
                voxel(
                        new GridPos(1, 64, 0),
                        VoxelKind.AIR,
                        revision,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                ),
                voxel(
                        new GridPos(1, 65, 0),
                        VoxelKind.AIR,
                        revision,
                        OccupancyEvidence.MULTI_RAY_CLEAR,
                        TopSupportAffordance.UNKNOWN
                )
        );
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.NETHER,
                revision,
                revision,
                ARRIVAL,
                ARRIVAL.add(new PerceptionVec3(0.0, 1.62, 0.0)),
                new PerceptionVec3(1.0, 0.0, 0.0),
                true,
                false,
                0.0,
                new LocalNavSnapshot(
                        DimensionRef.NETHER,
                        revision,
                        voxels
                ),
                List.of()
        );
    }

    private static ObservedVoxel voxel(
            final GridPos position,
            final VoxelKind kind,
            final long revision,
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

    private static VerifiedPortalEdge arrivalEdge() {
        return new VerifiedPortalEdge(
                "1".repeat(64),
                WORLD,
                PortalKind.NETHER_PORTAL,
                DimensionRef.OVERWORLD,
                new PerceptionVec3(8.5, 64.0, 8.5),
                new BlockCoordinate(8, 64, 8),
                DimensionRef.NETHER,
                ARRIVAL,
                new BlockCoordinate(1, 64, 0),
                Instant.parse("2026-08-04T00:00:00Z"),
                Instant.parse("2026-08-04T00:00:00Z"),
                1L,
                0L
        );
    }

    private static SkillContext context(final long tick) {
        return new SkillContext(
                1L,
                10L,
                tick,
                true,
                true,
                0.0
        );
    }

    private static final class MutableCoreFrames
            implements CoreSkillFrameSource {
        private CoreSkillFrame frame;

        private MutableCoreFrames(final CoreSkillFrame frame) {
            this.frame = frame;
        }

        @Override
        public Optional<CoreSkillFrame> current() {
            return Optional.ofNullable(frame);
        }
    }
}
