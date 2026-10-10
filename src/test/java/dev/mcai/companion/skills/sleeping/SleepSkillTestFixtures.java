package dev.mcai.companion.skills.sleeping;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;

final class SleepSkillTestFixtures {
    static final UUID PLAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000751");
    static final long SEQUENCE = 31;
    static final long SESSION = 8;
    static final BlockCoordinate BED_HEAD =
            new BlockCoordinate(1, 64, 0);

    private SleepSkillTestFixtures() {
    }

    static SleepInObservedBedParameters parameters() {
        return new SleepInObservedBedParameters(
                DimensionRef.OVERWORLD,
                new ObservedBlockTarget(
                        SEQUENCE,
                        1,
                        64,
                        0,
                        BlockFace.WEST
                )
        );
    }

    static VisibleBlockFace bed() {
        return bed(false, 2.5);
    }

    static VisibleBlockFace bed(
            boolean occupied,
            double distance
    ) {
        return new VisibleBlockFace(
                BED_HEAD,
                "minecraft:red_bed",
                "west",
                new PerceptionVec3(1.0, 64.4, 0.5),
                distance,
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                Map.of(
                        "occupied", Boolean.toString(occupied),
                        "part", "head",
                        "facing", "north"
                )
        );
    }

    static final class FrameBuilder {
        DimensionRef currentDimension = DimensionRef.OVERWORLD;
        DimensionRef observedDimension = DimensionRef.OVERWORLD;
        long currentGameTime = 100;
        long observedAtGameTime = 100;
        long revision = SEQUENCE;
        long session = SESSION;
        boolean alive = true;
        boolean spectator;
        float health = 20.0F;
        float maximumHealth = 20.0F;
        boolean sleeping;
        Optional<BlockCoordinate> sleepingPosition = Optional.empty();
        Optional<SleepRespawnPoint> respawnPoint = Optional.empty();
        boolean darkOutside = true;
        long clockTime = 13_000;
        int sleepTimer;
        int activePlayers = 1;
        int sleepingPlayers;
        int sleepersNeeded = 1;
        List<VisibleBlockFace> blocks = List.of(bed());
        List<DangerSignal> dangers = List.of();

        SleepSkillFrame build() {
            return new SleepSkillFrame(
                    PLAYER_ID,
                    currentDimension,
                    observedDimension,
                    currentGameTime,
                    observedAtGameTime,
                    revision,
                    session,
                    alive,
                    spectator,
                    health,
                    maximumHealth,
                    sleeping,
                    sleepingPosition,
                    respawnPoint,
                    darkOutside,
                    clockTime,
                    sleepTimer,
                    activePlayers,
                    sleepingPlayers,
                    sleepersNeeded,
                    blocks,
                    dangers
            );
        }

        FrameBuilder sleeping() {
            sleeping = true;
            sleepingPosition = Optional.of(BED_HEAD);
            respawnPoint = Optional.of(new SleepRespawnPoint(
                    currentDimension,
                    BED_HEAD
            ));
            sleepingPlayers = 1;
            sleepTimer = 1;
            currentGameTime++;
            clockTime++;
            return this;
        }

        FrameBuilder awakeAtDawn() {
            sleeping = false;
            sleepingPosition = Optional.empty();
            respawnPoint = Optional.of(new SleepRespawnPoint(
                    currentDimension,
                    BED_HEAD
            ));
            sleepingPlayers = 0;
            darkOutside = false;
            sleepTimer = 100;
            currentGameTime += 100;
            clockTime = 24_000;
            return this;
        }
    }

    static final class MutableFrames implements SleepSkillFrameSource {
        SleepSkillFrame frame;

        MutableFrames(SleepSkillFrame frame) {
            this.frame = frame;
        }

        @Override
        public Optional<SleepSkillFrame> current() {
            return Optional.ofNullable(frame);
        }
    }

    static final class RecordingActuator
            implements InteractionSkillActuator {
        long session = SESSION;
        ActionOutcome useOutcome = ActionOutcome.DISPATCHED;
        final List<BlockInteractionTarget> uses = new ArrayList<>();

        @Override
        public OptionalLong sessionGeneration() {
            return OptionalLong.of(session);
        }

        @Override
        public ActionOutcome beginMining(
                BlockInteractionTarget target
        ) {
            return ActionOutcome.WORLD_DENIED;
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
            uses.add(target);
            return useOutcome;
        }

        @Override
        public ActionOutcome attack(UUID entityId) {
            return ActionOutcome.TARGET_NOT_FOUND;
        }

        @Override
        public OptionalDouble attackStrengthScale() {
            return OptionalDouble.empty();
        }

        @Override
        public ActionOutcome useItem(ActionHand hand) {
            return ActionOutcome.ITEM_UNAVAILABLE;
        }

        @Override
        public ActionOutcome continueUsing(ActionHand hand) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }

        @Override
        public ActionOutcome releaseUse() {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
    }
}
