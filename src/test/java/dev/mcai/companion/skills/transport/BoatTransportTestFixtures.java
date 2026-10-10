package dev.mcai.companion.skills.transport;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

final class BoatTransportTestFixtures {
    static final UUID PLAYER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000123"
    );
    static final UUID BOAT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000456"
    );
    static final long SESSION = 9;
    static final long SEQUENCE = 21;

    private BoatTransportTestFixtures() {
    }

    static VisibleEntity visibleBoat() {
        return new VisibleEntity(
                BOAT_ID,
                "minecraft:oak_boat",
                new PerceptionVec3(2.0, 63.0, 0.0),
                new PerceptionVec3(2.0, -1.0, 0.0),
                2.25,
                false,
                false,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
    }

    static VisibleBlockFace safeBank() {
        return new VisibleBlockFace(
                new BlockCoordinate(1, 63, 0),
                "minecraft:grass_block",
                "up",
                new PerceptionVec3(1.5, 64.0, 0.5),
                2.0,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP
        );
    }

    static BoatState boat(
            double x,
            double z,
            float yaw,
            double speed,
            boolean collision,
            boolean underwater
    ) {
        return new BoatState(
                BOAT_ID,
                new PerceptionVec3(x, 63.0, z),
                yaw,
                new PerceptionVec3(speed, 0.0, 0.0),
                collision,
                underwater
        );
    }

    static BoatSkillFrame frame(
            long currentTime,
            long observedAt,
            long sequence,
            long session,
            double danger,
            List<VisibleEntity> entities,
            List<VisibleBlockFace> faces,
            Optional<BoatState> controlled
    ) {
        return new BoatSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                currentTime,
                observedAt,
                sequence,
                session,
                new PerceptionVec3(0.0, 64.0, 0.0),
                entities,
                faces,
                danger,
                controlled
        );
    }

    static BoatSkillFrame mounted(BoatState state) {
        return frame(
                100,
                100,
                SEQUENCE,
                SESSION,
                0.0,
                List.of(),
                List.of(),
                Optional.of(state)
        );
    }

    static final class MutableFrames
            implements BoatSkillFrameSource {
        BoatSkillFrame frame;

        MutableFrames(BoatSkillFrame frame) {
            this.frame = frame;
        }

        @Override
        public Optional<BoatSkillFrame> current() {
            return Optional.ofNullable(frame);
        }
    }

    static final class RecordingActuator
            implements BoatSkillActuator {
        final List<UUID> entered = new ArrayList<>();
        final List<BoatControlIntent> controls = new ArrayList<>();
        final List<UUID> stopped = new ArrayList<>();
        final List<UUID> dismounted = new ArrayList<>();
        long session = SESSION;
        boolean available = true;
        ActionOutcome enterOutcome = ActionOutcome.DISPATCHED;
        ActionOutcome driveOutcome = ActionOutcome.DISPATCHED;
        ActionOutcome stopOutcome = ActionOutcome.DISPATCHED;
        ActionOutcome dismountOutcome = ActionOutcome.DISPATCHED;

        @Override
        public OptionalLong sessionGeneration() {
            return available
                    ? OptionalLong.of(session)
                    : OptionalLong.empty();
        }

        @Override
        public ActionOutcome enterBoat(UUID observedBoatId) {
            entered.add(observedBoatId);
            return enterOutcome;
        }

        @Override
        public ActionOutcome driveBoat(
                UUID expectedBoatId,
                BoatControlIntent intent
        ) {
            controls.add(intent);
            return driveOutcome;
        }

        @Override
        public ActionOutcome stopBoat(UUID expectedBoatId) {
            stopped.add(expectedBoatId);
            return stopOutcome;
        }

        @Override
        public ActionOutcome dismountBoat(UUID expectedBoatId) {
            dismounted.add(expectedBoatId);
            return dismountOutcome;
        }
    }
}
