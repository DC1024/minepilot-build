package dev.mcai.companion.skills.core;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Loader-independent fair pose plus the latest map derived from first-person
 * semantic rays. No level/chunk accessor is exposed to a skill.
 */
public record CoreSkillFrame(
        UUID playerId,
        DimensionRef dimension,
        long gameTime,
        long observationRevision,
        PerceptionVec3 position,
        PerceptionVec3 eyePosition,
        PerceptionVec3 lookDirection,
        boolean onGround,
        boolean inWater,
        double danger,
        LocalNavSnapshot navigation,
        List<VisibleBlockFace> visibleBlockFaces,
        float health,
        float maxHealth,
        int foodLevel,
        List<InventoryItemSummary> inventory,
        HeldItemSummary mainHand,
        HeldItemSummary offHand,
        List<VisibleEntity> visibleEntities,
        List<DangerSignal> dangerSignals
) {
    public CoreSkillFrame {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(dimension, "dimension");
        if (gameTime < 0 || observationRevision < 0) {
            throw new IllegalArgumentException("Frame counters must be non-negative");
        }
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(eyePosition, "eyePosition");
        Objects.requireNonNull(lookDirection, "lookDirection");
        if (Math.abs(lookDirection.length() - 1.0) > 1.0E-6) {
            throw new IllegalArgumentException("lookDirection must be normalized");
        }
        if (!Double.isFinite(danger) || danger < 0.0 || danger > 1.0) {
            throw new IllegalArgumentException("danger must be in [0, 1]");
        }
        Objects.requireNonNull(navigation, "navigation");
        if (!navigation.dimension().equals(dimension)
                || navigation.revision() > observationRevision) {
            throw new IllegalArgumentException(
                    "Navigation snapshot is inconsistent with the fair frame"
            );
        }
        visibleBlockFaces = List.copyOf(
                Objects.requireNonNull(visibleBlockFaces, "visibleBlockFaces")
        );
        if (!Float.isFinite(health)
                || !Float.isFinite(maxHealth)
                || health < 0.0F
                || maxHealth <= 0.0F
                || health > maxHealth
                || foodLevel < 0
                || foodLevel > 20) {
            throw new IllegalArgumentException("Invalid player survival state");
        }
        inventory = List.copyOf(
                Objects.requireNonNull(inventory, "inventory")
        );
        Objects.requireNonNull(mainHand, "mainHand");
        Objects.requireNonNull(offHand, "offHand");
        visibleEntities = List.copyOf(
                Objects.requireNonNull(visibleEntities, "visibleEntities")
        );
        dangerSignals = List.copyOf(
                Objects.requireNonNull(dangerSignals, "dangerSignals")
        );
    }

    /**
     * Compatibility constructor for loader-independent movement tests and
     * adapters that do not yet publish survival semantics.
     */
    public CoreSkillFrame(
            UUID playerId,
            DimensionRef dimension,
            long gameTime,
            long observationRevision,
            PerceptionVec3 position,
            PerceptionVec3 eyePosition,
            PerceptionVec3 lookDirection,
            boolean onGround,
            boolean inWater,
            double danger,
            LocalNavSnapshot navigation,
            List<VisibleBlockFace> visibleBlockFaces
    ) {
        this(
                playerId,
                dimension,
                gameTime,
                observationRevision,
                position,
                eyePosition,
                lookDirection,
                onGround,
                inWater,
                danger,
                navigation,
                visibleBlockFaces,
                20.0F,
                20.0F,
                20,
                List.of(),
                HeldItemSummary.empty(),
                HeldItemSummary.empty(),
                List.of(),
                List.of()
        );
    }

    public static CoreSkillFrame from(
            SemanticObservation observation,
            LocalNavSnapshot navigation
    ) {
        Objects.requireNonNull(observation, "observation");
        double danger = observation.dangers().stream()
                .mapToDouble(signal -> signal.severity())
                .max()
                .orElse(0.0);
        return new CoreSkillFrame(
                observation.body().playerId(),
                DimensionRef.parse(observation.body().dimensionId()),
                observation.body().gameTime(),
                observation.sequence(),
                observation.body().position(),
                observation.body().eyePosition(),
                observation.body().lookDirection(),
                observation.body().onGround(),
                observation.body().inWater(),
                danger,
                navigation,
                observation.visibleBlockFaces(),
                observation.body().health(),
                observation.body().maxHealth(),
                observation.body().foodLevel(),
                observation.body().inventory(),
                observation.body().mainHand(),
                observation.body().offHand(),
                observation.visibleEntities(),
                observation.dangers()
        );
    }

    public GridPos feet() {
        return new GridPos(
                floorToInt(position.x()),
                floorToInt(position.y()),
                floorToInt(position.z())
        );
    }

    /**
     * Replaces only live body pose fields. Navigation, danger, visible faces,
     * and their observation revision remain tied to the last fair semantic
     * sample.
     */
    public CoreSkillFrame withPose(CoreSkillPose pose) {
        Objects.requireNonNull(pose, "pose");
        if (!playerId.equals(pose.playerId())) {
            throw new IllegalArgumentException("Pose player does not match frame");
        }
        if (!dimension.equals(pose.dimension())) {
            throw new IllegalArgumentException(
                    "Pose dimension does not match navigation frame"
            );
        }
        return new CoreSkillFrame(
                playerId,
                dimension,
                pose.gameTime(),
                observationRevision,
                pose.position(),
                pose.eyePosition(),
                pose.lookDirection(),
                pose.onGround(),
                pose.inWater(),
                danger,
                navigation,
                visibleBlockFaces,
                health,
                maxHealth,
                foodLevel,
                inventory,
                mainHand,
                offHand,
                visibleEntities,
                dangerSignals
        );
    }

    /**
     * Compatibility constructor for test fixtures and adapters that publish
     * survival semantics but not the complete owned inventory summary.
     */
    public CoreSkillFrame(
            UUID playerId,
            DimensionRef dimension,
            long gameTime,
            long observationRevision,
            PerceptionVec3 position,
            PerceptionVec3 eyePosition,
            PerceptionVec3 lookDirection,
            boolean onGround,
            boolean inWater,
            double danger,
            LocalNavSnapshot navigation,
            List<VisibleBlockFace> visibleBlockFaces,
            float health,
            float maxHealth,
            int foodLevel,
            HeldItemSummary mainHand,
            HeldItemSummary offHand,
            List<VisibleEntity> visibleEntities,
            List<DangerSignal> dangerSignals
    ) {
        this(
                playerId,
                dimension,
                gameTime,
                observationRevision,
                position,
                eyePosition,
                lookDirection,
                onGround,
                inWater,
                danger,
                navigation,
                visibleBlockFaces,
                health,
                maxHealth,
                foodLevel,
                List.of(),
                mainHand,
                offHand,
                visibleEntities,
                dangerSignals
        );
    }

    /**
     * Refreshes only player-owned state at 20 TPS. Visible geometry,
     * entities, navigation, and proximity threats remain tied to the latest
     * fair semantic observation.
     */
    public CoreSkillFrame withLivePlayerState(
            CoreSkillPose pose,
            float currentHealth,
            float currentMaxHealth,
            int currentFoodLevel,
            List<InventoryItemSummary> currentInventory,
            HeldItemSummary currentMainHand,
            HeldItemSummary currentOffHand,
            List<DangerSignal> currentDangerSignals
    ) {
        Objects.requireNonNull(currentDangerSignals, "currentDangerSignals");
        final double currentDanger = currentDangerSignals.stream()
                .mapToDouble(DangerSignal::severity)
                .max()
                .orElse(0.0);
        return new CoreSkillFrame(
                playerId,
                dimension,
                pose.gameTime(),
                observationRevision,
                pose.position(),
                pose.eyePosition(),
                pose.lookDirection(),
                pose.onGround(),
                pose.inWater(),
                currentDanger,
                navigation,
                visibleBlockFaces,
                currentHealth,
                currentMaxHealth,
                currentFoodLevel,
                currentInventory,
                currentMainHand,
                currentOffHand,
                visibleEntities,
                currentDangerSignals
        );
    }

    private static int floorToInt(double value) {
        double floor = Math.floor(value);
        if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
            throw new IllegalStateException("Player position is outside grid bounds");
        }
        return (int) floor;
    }
}
