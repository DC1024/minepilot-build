package dev.mcai.companion.skills.combat;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;

final class CombatSkillTestFixtures {
    static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000123");
    static final UUID TARGET_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000456");
    static final UUID DECOY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000789");
    static final long SESSION = 7;
    static final long SEQUENCE = 12;

    private CombatSkillTestFixtures() {
    }

    static VisibleEntity hostile(UUID id, String type, double x) {
        return entity(id, type, x, true, false);
    }

    static VisibleEntity entity(
            UUID id,
            String type,
            double x,
            boolean hostile,
            boolean projectile
    ) {
        return new VisibleEntity(
                id,
                type,
                new PerceptionVec3(x, 1.0, 0.5),
                new PerceptionVec3(x - 0.5, 0.0, 0.0),
                Math.abs(x - 0.5),
                hostile,
                projectile,
                PerceptionProvenance
                        .ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP
        );
    }

    static CoreSkillFrame coreFrame(
            long sequence,
            float health,
            PerceptionVec3 look,
            List<VisibleEntity> entities,
            boolean shield
    ) {
        return new CoreSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                100,
                sequence,
                new PerceptionVec3(0.5, 1.0, 0.5),
                new PerceptionVec3(0.5, 2.62, 0.5),
                look,
                true,
                false,
                0.0,
                observedFloor(sequence),
                List.of(),
                health,
                20.0F,
                20,
                new HeldItemSummary(
                        "minecraft:iron_sword",
                        1,
                        0,
                        250
                ),
                shield
                        ? new HeldItemSummary(
                                "minecraft:shield",
                                1,
                                0,
                                336
                        )
                        : HeldItemSummary.empty(),
                entities,
                List.of()
        );
    }

    static InteractionSkillFrame interactionFrame(
            long sequence,
            List<VisibleEntity> entities,
            boolean shield
    ) {
        return new InteractionSkillFrame(
                PLAYER_ID,
                DimensionRef.OVERWORLD,
                100,
                100,
                sequence,
                SESSION,
                new HeldItemSummary(
                        "minecraft:iron_sword",
                        1,
                        0,
                        250
                ),
                shield
                        ? new HeldItemSummary(
                                "minecraft:shield",
                                1,
                                0,
                                336
                        )
                        : HeldItemSummary.empty(),
                entities,
                List.of()
        );
    }

    static PerceptionVec3 lookToward(VisibleEntity entity) {
        return new PerceptionVec3(
                entity.position().x() - 0.5,
                entity.position().y() + 1.0 - 2.62,
                entity.position().z() - 0.5
        ).normalized();
    }

    private static LocalNavSnapshot observedFloor(long revision) {
        List<ObservedVoxel> voxels = new ArrayList<>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                voxels.add(new ObservedVoxel(
                        new GridPos(x, 0, z),
                        VoxelKind.SOLID,
                        0.0,
                        revision
                ));
                voxels.add(new ObservedVoxel(
                        new GridPos(x, 1, z),
                        VoxelKind.AIR,
                        0.0,
                        revision
                ));
                voxels.add(new ObservedVoxel(
                        new GridPos(x, 2, z),
                        VoxelKind.AIR,
                        0.0,
                        revision
                ));
            }
        }
        return new LocalNavSnapshot(
                DimensionRef.OVERWORLD,
                revision,
                voxels
        );
    }

    static final class MutableCoreFrames
            implements CoreSkillFrameSource {
        CoreSkillFrame frame;

        MutableCoreFrames(CoreSkillFrame frame) {
            this.frame = frame;
        }

        @Override
        public Optional<CoreSkillFrame> current() {
            return Optional.ofNullable(frame);
        }
    }

    static final class MutableInteractionFrames
            implements InteractionSkillFrameSource {
        InteractionSkillFrame frame;

        MutableInteractionFrames(InteractionSkillFrame frame) {
            this.frame = frame;
        }

        @Override
        public Optional<InteractionSkillFrame> current() {
            return Optional.ofNullable(frame);
        }
    }

    static final class RecordingCoreActuator
            implements CoreSkillActuator {
        final List<MovementIntent> movements = new ArrayList<>();
        final List<LookIntent> looks = new ArrayList<>();
        final List<ActionHand> uses = new ArrayList<>();
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
            uses.add(hand);
            return outcome;
        }

        @Override
        public ActionOutcome releaseUse() {
            releases++;
            return outcome;
        }
    }

    static final class RecordingInteractionActuator
            implements InteractionSkillActuator {
        final List<UUID> attacks = new ArrayList<>();
        long session = SESSION;
        double attackStrength = 1.0;
        ActionOutcome attackOutcome = ActionOutcome.DISPATCHED;
        boolean activelyUsing;
        int useCalls;
        int releaseCalls;

        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(session);
        }

        @Override
        public ActionOutcome beginMining(
                BlockInteractionTarget target
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
                ActionHand hand,
                BlockInteractionTarget target
        ) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }

        @Override
        public ActionOutcome attack(UUID entityId) {
            attacks.add(entityId);
            attackStrength = 0.0;
            return attackOutcome;
        }

        @Override
        public OptionalDouble attackStrengthScale() {
            return OptionalDouble.of(attackStrength);
        }

        @Override
        public ActionOutcome useItem(ActionHand hand) {
            useCalls++;
            activelyUsing = true;
            return ActionOutcome.DISPATCHED;
        }

        @Override
        public ActionOutcome continueUsing(ActionHand hand) {
            return activelyUsing
                    ? ActionOutcome.IN_PROGRESS
                    : ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome releaseUse() {
            releaseCalls++;
            activelyUsing = false;
            return ActionOutcome.DISPATCHED;
        }
    }
}
