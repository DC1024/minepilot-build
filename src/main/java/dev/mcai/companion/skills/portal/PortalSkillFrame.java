package dev.mcai.companion.skills.portal;

import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The latest fair portal candidates plus the companion's tick-local pose.
 *
 * <p>The observed dimension remains tied to the semantic rays while
 * {@code currentDimension} follows the real ServerPlayer. Keeping both is
 * what lets the skill recognize a genuine portal transition without exposing
 * a level, chunk, structure lookup, or writable position.</p>
 */
public record PortalSkillFrame(
        UUID playerId,
        DimensionRef currentDimension,
        DimensionRef observedDimension,
        long serverTick,
        long observedAtServerTick,
        long observationRevision,
        long sessionGeneration,
        PerceptionVec3 position,
        PerceptionVec3 eyePosition,
        PerceptionVec3 lookDirection,
        boolean onGround,
        boolean inWater,
        boolean portalProcessActive,
        int portalProgressTicks,
        Optional<BlockCoordinate> portalEntryBlock,
        double danger,
        List<VisibleBlockFace> visibleBlockFaces
) {
    public PortalSkillFrame {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(currentDimension, "currentDimension");
        Objects.requireNonNull(observedDimension, "observedDimension");
        if (serverTick < 0
                || observedAtServerTick < 0
                || observationRevision < 0
                || sessionGeneration < 0
                || serverTick < observedAtServerTick) {
            throw new IllegalArgumentException(
                    "Portal frame counters must be non-negative and monotonic"
            );
        }
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(eyePosition, "eyePosition");
        Objects.requireNonNull(lookDirection, "lookDirection");
        portalEntryBlock = Objects.requireNonNull(
                portalEntryBlock,
                "portalEntryBlock"
        );
        if (portalProgressTicks < 0) {
            throw new IllegalArgumentException(
                    "portalProgressTicks must be non-negative"
            );
        }
        if (!portalProcessActive && portalProgressTicks != 0) {
            throw new IllegalArgumentException(
                    "Inactive portal process cannot retain progress"
            );
        }
        if (portalProcessActive != portalEntryBlock.isPresent()) {
            throw new IllegalArgumentException(
                    "Portal process and entry block must be present together"
            );
        }
        if (Math.abs(lookDirection.length() - 1.0) > 1.0E-6) {
            throw new IllegalArgumentException(
                    "lookDirection must be normalized"
            );
        }
        if (!Double.isFinite(danger) || danger < 0.0 || danger > 1.0) {
            throw new IllegalArgumentException("danger must be in [0, 1]");
        }
        visibleBlockFaces = List.copyOf(
                Objects.requireNonNull(
                        visibleBlockFaces,
                        "visibleBlockFaces"
                )
        );
    }

    public static PortalSkillFrame from(
            SemanticObservation observation,
            long sessionGeneration,
            long serverTick
    ) {
        Objects.requireNonNull(observation, "observation");
        var body = observation.body();
        DimensionRef dimension = DimensionRef.parse(body.dimensionId());
        double danger = observation.dangers().stream()
                .mapToDouble(signal -> signal.severity())
                .max()
                .orElse(0.0);
        return new PortalSkillFrame(
                body.playerId(),
                dimension,
                dimension,
                serverTick,
                serverTick,
                observation.sequence(),
                sessionGeneration,
                body.position(),
                body.eyePosition(),
                body.lookDirection(),
                body.onGround(),
                body.inWater(),
                false,
                0,
                Optional.empty(),
                danger,
                observation.visibleBlockFaces()
        );
    }

    public long observationAgeTicks() {
        return serverTick - observedAtServerTick;
    }

    public PortalSkillFrame withLivePose(
            DimensionRef dimension,
            long currentServerTick,
            PerceptionVec3 currentPosition,
            PerceptionVec3 currentEyePosition,
            PerceptionVec3 currentLookDirection,
            boolean currentlyOnGround,
            boolean currentlyInWater,
            boolean currentPortalProcessActive,
            int currentPortalProgressTicks,
            Optional<BlockCoordinate> currentPortalEntryBlock
    ) {
        return new PortalSkillFrame(
                playerId,
                dimension,
                observedDimension,
                currentServerTick,
                observedAtServerTick,
                observationRevision,
                sessionGeneration,
                currentPosition,
                currentEyePosition,
                currentLookDirection,
                currentlyOnGround,
                currentlyInWater,
                currentPortalProcessActive,
                currentPortalProgressTicks,
                currentPortalEntryBlock,
                danger,
                visibleBlockFaces
        );
    }
}
