package dev.mcai.companion.skills.core;

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
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class CoreSkillTestFixtures {
    static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000123");

    private CoreSkillTestFixtures() {
    }

    static CoreSkillFrame frame(
            long revision,
            double x,
            double y,
            double z,
            PerceptionVec3 look,
            LocalNavSnapshot navigation,
            double danger
    ) {
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                revision,
                revision,
                new PerceptionVec3(x, y, z),
                new PerceptionVec3(x, y + 1.62, z),
                look,
                true,
                false,
                danger,
                navigation,
                List.of()
        );
    }

    static LocalNavSnapshot corridor(long revision, int maximumX) {
        return corridor(revision, maximumX, null, VoxelKind.AIR, 0.0);
    }

    static LocalNavSnapshot corridor(
            long revision,
            int maximumX,
            GridPos overridePosition,
            VoxelKind overrideKind,
            double overrideDanger
    ) {
        List<ObservedVoxel> voxels = new ArrayList<>();
        for (int x = 0; x <= maximumX; x++) {
            add(voxels, new GridPos(x, 0, 0), VoxelKind.SOLID, 0.0, revision);
            add(voxels, new GridPos(x, 1, 0), VoxelKind.AIR, 0.0, revision);
            add(voxels, new GridPos(x, 2, 0), VoxelKind.AIR, 0.0, revision);
            add(voxels, new GridPos(x, 3, 0), VoxelKind.AIR, 0.0, revision);
        }
        if (overridePosition != null) {
            voxels.removeIf(voxel -> voxel.position().equals(overridePosition));
            add(
                    voxels,
                    overridePosition,
                    overrideKind,
                    overrideDanger,
                    revision
            );
        }
        return new LocalNavSnapshot(DimensionRef.OVERWORLD, revision, voxels);
    }

    static LocalNavSnapshot currentCellOnly(long revision) {
        return corridor(revision, 0);
    }

    private static void add(
            List<ObservedVoxel> voxels,
            GridPos position,
            VoxelKind kind,
            double danger,
            long revision
    ) {
        final OccupancyEvidence occupancy = switch (kind) {
            case AIR -> OccupancyEvidence.MULTI_RAY_CLEAR;
            case SOLID, WATER, LAVA, CLIMBABLE, OPEN_DOOR,
                    CLOSED_DOOR -> OccupancyEvidence.SURFACE_HIT;
        };
        final TopSupportAffordance support = kind.supportsWeight()
                ? TopSupportAffordance.STURDY_FULL_TOP
                : TopSupportAffordance.UNKNOWN;
        voxels.add(new ObservedVoxel(
                position,
                kind,
                danger,
                revision,
                occupancy,
                support
        ));
    }

    static final class MutableFrames implements CoreSkillFrameSource {
        CoreSkillFrame frame;

        MutableFrames(CoreSkillFrame frame) {
            this.frame = frame;
        }

        @Override
        public Optional<CoreSkillFrame> current() {
            return Optional.ofNullable(frame);
        }
    }

    static final class RecordingActuator implements CoreSkillActuator {
        final List<MovementIntent> movements = new ArrayList<>();
        final List<LookIntent> looks = new ArrayList<>();
        final List<BlockInteractionTarget> blockUses = new ArrayList<>();
        final List<ActionHand> itemUses = new ArrayList<>();
        int jumps;
        int stops;
        int releases;
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
        public ActionOutcome useMainHandOn(BlockInteractionTarget target) {
            blockUses.add(target);
            return outcome;
        }

        @Override
        public ActionOutcome useItem(ActionHand hand) {
            itemUses.add(hand);
            return outcome;
        }

        @Override
        public ActionOutcome releaseUse() {
            releases++;
            return outcome;
        }
    }
}
