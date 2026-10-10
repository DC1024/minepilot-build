package dev.mcai.companion.skills.portal;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class PortalSkillTestFixtures {
    static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000777");

    private PortalSkillTestFixtures() {
    }

    static PortalSkillFrame frame(
            DimensionRef currentDimension,
            DimensionRef observedDimension,
            long serverTick,
            long observationRevision,
            long sessionGeneration,
            PerceptionVec3 position,
            PortalKind kind
    ) {
        return new PortalSkillFrame(
                PLAYER_ID,
                currentDimension,
                observedDimension,
                serverTick,
                0,
                observationRevision,
                sessionGeneration,
                position,
                position.add(new PerceptionVec3(0.0, 1.62, 0.0)),
                new PerceptionVec3(1.0, 0.0, 0.0),
                true,
                false,
                false,
                0,
                Optional.empty(),
                0.0,
                List.of(new VisibleBlockFace(
                        new BlockCoordinate(1, 64, 0),
                        kind.blockTypeId(),
                        "north",
                        new PerceptionVec3(1.5, 64.5, 0.5),
                        0.5,
                        PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                        Map.of()
                ))
        );
    }

    static PortalSkillFrame withPortalProgress(
            PortalSkillFrame frame,
            long serverTick,
            int portalProgressTicks
    ) {
        return new PortalSkillFrame(
                frame.playerId(),
                frame.currentDimension(),
                frame.observedDimension(),
                serverTick,
                frame.observedAtServerTick(),
                frame.observationRevision(),
                frame.sessionGeneration(),
                frame.position(),
                frame.eyePosition(),
                frame.lookDirection(),
                frame.onGround(),
                frame.inWater(),
                true,
                portalProgressTicks,
                Optional.of(new BlockCoordinate(1, 64, 0)),
                frame.danger(),
                frame.visibleBlockFaces()
        );
    }

    static PortalSkillFrame withPortalHit(
            PortalSkillFrame frame,
            PerceptionVec3 hitPosition
    ) {
        VisibleBlockFace original =
                frame.visibleBlockFaces().getFirst();
        return new PortalSkillFrame(
                frame.playerId(),
                frame.currentDimension(),
                frame.observedDimension(),
                frame.serverTick(),
                frame.observedAtServerTick(),
                frame.observationRevision(),
                frame.sessionGeneration(),
                frame.position(),
                frame.eyePosition(),
                frame.lookDirection(),
                frame.onGround(),
                frame.inWater(),
                frame.portalProcessActive(),
                frame.portalProgressTicks(),
                frame.portalEntryBlock(),
                frame.danger(),
                List.of(new VisibleBlockFace(
                        original.block(),
                        original.blockTypeId(),
                        original.face(),
                        hitPosition,
                        original.distance(),
                        original.provenance(),
                        original.stateProperties()
                ))
        );
    }

    static PortalSkillFrame withVisiblePortalBlock(
            PortalSkillFrame frame,
            BlockCoordinate block
    ) {
        VisibleBlockFace original =
                frame.visibleBlockFaces().getFirst();
        return new PortalSkillFrame(
                frame.playerId(),
                frame.currentDimension(),
                frame.observedDimension(),
                frame.serverTick(),
                frame.observedAtServerTick(),
                frame.observationRevision(),
                frame.sessionGeneration(),
                frame.position(),
                frame.eyePosition(),
                frame.lookDirection(),
                frame.onGround(),
                frame.inWater(),
                frame.portalProcessActive(),
                frame.portalProgressTicks(),
                frame.portalEntryBlock(),
                frame.danger(),
                List.of(new VisibleBlockFace(
                        block,
                        original.blockTypeId(),
                        "south",
                        new PerceptionVec3(
                                block.x() + 0.5,
                                block.y() + 0.5,
                                block.z() + 0.5
                        ),
                        original.distance(),
                        original.provenance(),
                        original.stateProperties()
                ))
        );
    }

    static EnterObservedPortalParameters parameters(
            DimensionRef source,
            Optional<DimensionRef> expectedDestination
    ) {
        return new EnterObservedPortalParameters(
                source,
                new ObservedPortalTarget(
                        10,
                        1,
                        64,
                        0,
                        BlockFace.NORTH
                ),
                expectedDestination
        );
    }

    static final class MutableFrames
            implements PortalSkillFrameSource {
        PortalSkillFrame frame;

        MutableFrames(PortalSkillFrame frame) {
            this.frame = frame;
        }

        @Override
        public Optional<PortalSkillFrame> current() {
            return Optional.ofNullable(frame);
        }
    }

    static final class RecordingActuator
            implements CoreSkillActuator {
        final List<MovementIntent> movements = new ArrayList<>();
        final List<LookIntent> looks = new ArrayList<>();
        int jumps;
        int stops;
        ActionOutcome outcome = ActionOutcome.QUEUED;

        @Override
        public ActionOutcome move(MovementIntent intent) {
            movements.add(intent);
            return outcome;
        }

        @Override
        public ActionOutcome look(LookIntent intent) {
            looks.add(intent);
            return outcome;
        }

        @Override
        public ActionOutcome jump() {
            jumps++;
            return outcome;
        }

        @Override
        public ActionOutcome stop() {
            stops++;
            return outcome;
        }

        @Override
        public ActionOutcome useMainHandOn(
                BlockInteractionTarget target
        ) {
            return outcome;
        }

        @Override
        public ActionOutcome useItem(ActionHand hand) {
            return outcome;
        }

        @Override
        public ActionOutcome releaseUse() {
            return outcome;
        }
    }
}
