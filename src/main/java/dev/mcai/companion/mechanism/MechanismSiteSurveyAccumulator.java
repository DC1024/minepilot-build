package dev.mcai.companion.mechanism;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skills.building.ShelterFrame;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded accumulator for a deliberate multi-view first-person site survey.
 * It never reads a level, chunk, block entity, or hidden registry state.
 */
public final class MechanismSiteSurveyAccumulator {
    private final MechanismSurveyPolicy policy;
    private final Map<SurfaceKey, MechanismSiteSurvey.SurfaceObservation>
            surfaces = new HashMap<>();
    private final Map<GridPos, MechanismSiteSurvey.VoxelObservation> voxels =
            new HashMap<>();
    private final Map<Long, Long> revisionGameTimes = new HashMap<>();

    private UUID playerId;
    private long sessionGeneration = -1;
    private DimensionRef dimension;
    private long currentGameTime = -1;
    private long sourceRevision = -1;
    private GridPos feet;
    private dev.mcai.companion.perception.PerceptionVec3 lookDirection;
    private List<dev.mcai.companion.perception.InventoryItemSummary>
            inventory = List.of();
    private MechanismSiteSurvey.SkyObservation skyObservation;

    public MechanismSiteSurveyAccumulator() {
        this(MechanismSurveyPolicy.defaults());
    }

    public MechanismSiteSurveyAccumulator(
            final MechanismSurveyPolicy policy
    ) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Adds exactly the evidence already present in {@code frame}. The sky flag
     * means that this same first-person observation positively identified open
     * sky; false is absence of proof, not proof of a roof.
     */
    public synchronized MechanismSiteSurvey ingest(
            final ShelterFrame frame,
            final boolean skyVisibleInObservation
    ) {
        observe(frame, skyVisibleInObservation);
        return snapshot();
    }

    /**
     * Merges one fair frame without materializing and sorting a full survey
     * snapshot. Long-running skills should use this while observations roll
     * and call {@link #current()} only when a planner needs an immutable
     * point-in-time view.
     */
    public synchronized void observe(
            final ShelterFrame frame,
            final boolean skyVisibleInObservation
    ) {
        Objects.requireNonNull(frame, "frame");
        if (!sameScope(frame)
                || sourceRevision >= 0
                        && (frame.observationRevision() < sourceRevision
                                || frame.currentGameTime()
                                        < currentGameTime)) {
            clear();
        }
        bind(frame);
        if (frame.observationRevision() > sourceRevision) {
            ingestNewObservation(frame, skyVisibleInObservation);
        } else if (frame.observationRevision() == sourceRevision
                && skyVisibleInObservation
                && skyObservation == null) {
            skyObservation = new MechanismSiteSurvey.SkyObservation(
                    frame.feet(),
                    frame.observedAtGameTime(),
                    frame.observationRevision()
            );
        }
        currentGameTime = frame.currentGameTime();
        sourceRevision = Math.max(
                sourceRevision,
                frame.observationRevision()
        );
        feet = frame.feet();
        lookDirection = frame.lookDirection();
        inventory = frame.inventory();
        prune();
    }

    public synchronized Optional<MechanismSiteSurvey> current() {
        return sourceRevision < 0
                ? Optional.empty()
                : Optional.of(snapshot());
    }

    public synchronized void reset() {
        clear();
    }

    private boolean sameScope(final ShelterFrame frame) {
        return playerId == null
                || playerId.equals(frame.playerId())
                        && sessionGeneration == frame.sessionGeneration()
                        && dimension.equals(frame.dimension());
    }

    private void bind(final ShelterFrame frame) {
        if (playerId == null) {
            playerId = frame.playerId();
            sessionGeneration = frame.sessionGeneration();
            dimension = frame.dimension();
        }
    }

    private void ingestNewObservation(
            final ShelterFrame frame,
            final boolean skyVisibleInObservation
    ) {
        revisionGameTimes.put(
                frame.observationRevision(),
                frame.observedAtGameTime()
        );
        final List<BlockCoordinate> observedBlocks = frame
                .visibleBlockFaces().stream()
                .map(VisibleBlockFace::block)
                .distinct()
                .toList();
        surfaces.entrySet().removeIf(entry ->
                observedBlocks.contains(entry.getKey().block())
        );
        for (VisibleBlockFace face : frame.visibleBlockFaces()) {
            surfaces.put(
                    SurfaceKey.from(face),
                    new MechanismSiteSurvey.SurfaceObservation(
                            face,
                            frame.observedAtGameTime(),
                            frame.observationRevision()
                    )
            );
        }
        for (ObservedVoxel voxel
                : frame.navigation().latestObservedVoxels()) {
            final MechanismSiteSurvey.VoxelObservation candidate =
                    new MechanismSiteSurvey.VoxelObservation(
                            voxel,
                            frame.observedAtGameTime()
                    );
            voxels.merge(
                    voxel.position(),
                    candidate,
                    (existing, replacement) ->
                            replacement.observationRevision()
                                    >= existing.observationRevision()
                                    ? replacement
                                    : existing
            );
        }
        if (skyVisibleInObservation) {
            skyObservation = new MechanismSiteSurvey.SkyObservation(
                    frame.feet(),
                    frame.observedAtGameTime(),
                    frame.observationRevision()
            );
        }
    }

    private void prune() {
        final long oldestGameTime = Math.max(
                0,
                currentGameTime - policy.maximumEvidenceAgeTicks()
        );
        surfaces.entrySet().removeIf(entry ->
                entry.getValue().observedAtGameTime() < oldestGameTime
                        || outsideRadius(entry.getKey().blockPosition())
        );
        voxels.entrySet().removeIf(entry ->
                entry.getValue().observedAtGameTime() < oldestGameTime
                        || outsideRadius(entry.getKey())
        );
        revisionGameTimes.entrySet().removeIf(entry ->
                entry.getValue() < oldestGameTime
        );
        if (skyObservation != null
                && (skyObservation.observedAtGameTime() < oldestGameTime
                        || outsideRadius(
                                skyObservation.observerFeet()
                        ))) {
            skyObservation = null;
        }
        evictSurfaces();
        evictVoxels();
    }

    private boolean outsideRadius(final GridPos position) {
        return feet.euclideanDistance(position)
                > policy.retentionRadius();
    }

    private void evictSurfaces() {
        if (surfaces.size() <= policy.maximumSurfaceObservations()) {
            return;
        }
        final List<Map.Entry<SurfaceKey,
                MechanismSiteSurvey.SurfaceObservation>> order =
                new ArrayList<>(surfaces.entrySet());
        order.sort(Comparator
                .comparingLong((Map.Entry<SurfaceKey,
                        MechanismSiteSurvey.SurfaceObservation> entry) ->
                        entry.getValue().observedAtGameTime())
                .thenComparing(Comparator.comparingDouble(
                        (Map.Entry<SurfaceKey,
                                MechanismSiteSurvey.SurfaceObservation>
                                entry) -> feet.euclideanDistance(
                                        entry.getKey().blockPosition()
                                )
                ).reversed())
                .thenComparing(Map.Entry::getKey));
        final int count = surfaces.size()
                - policy.maximumSurfaceObservations();
        for (int index = 0; index < count; index++) {
            surfaces.remove(order.get(index).getKey());
        }
    }

    private void evictVoxels() {
        if (voxels.size() <= policy.maximumVoxelObservations()) {
            return;
        }
        final List<Map.Entry<GridPos,
                MechanismSiteSurvey.VoxelObservation>> order =
                new ArrayList<>(voxels.entrySet());
        order.sort(Comparator
                .comparingLong((Map.Entry<GridPos,
                        MechanismSiteSurvey.VoxelObservation> entry) ->
                        entry.getValue().observedAtGameTime())
                .thenComparing(Comparator.comparingDouble(
                        (Map.Entry<GridPos,
                                MechanismSiteSurvey.VoxelObservation>
                                entry) -> feet.euclideanDistance(
                                        entry.getKey()
                                )
                ).reversed())
                .thenComparing(Map.Entry::getKey));
        final int count = voxels.size()
                - policy.maximumVoxelObservations();
        for (int index = 0; index < count; index++) {
            voxels.remove(order.get(index).getKey());
        }
    }

    private MechanismSiteSurvey snapshot() {
        final List<MechanismSiteSurvey.SurfaceObservation> orderedSurfaces =
                surfaces.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(Map.Entry::getValue)
                        .toList();
        final Map<GridPos, MechanismSiteSurvey.VoxelObservation>
                orderedVoxels = new LinkedHashMap<>();
        voxels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> orderedVoxels.put(
                        entry.getKey(),
                        entry.getValue()
                ));
        return new MechanismSiteSurvey(
                playerId,
                sessionGeneration,
                dimension,
                currentGameTime,
                sourceRevision,
                feet,
                lookDirection,
                inventory,
                orderedSurfaces,
                orderedVoxels,
                Optional.ofNullable(skyObservation)
        );
    }

    private void clear() {
        surfaces.clear();
        voxels.clear();
        revisionGameTimes.clear();
        playerId = null;
        sessionGeneration = -1;
        dimension = null;
        currentGameTime = -1;
        sourceRevision = -1;
        feet = null;
        lookDirection = null;
        inventory = List.of();
        skyObservation = null;
    }

    private record SurfaceKey(
            BlockCoordinate block,
            String face
    ) implements Comparable<SurfaceKey> {
        private SurfaceKey {
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(face, "face");
        }

        private static SurfaceKey from(final VisibleBlockFace face) {
            return new SurfaceKey(face.block(), face.face());
        }

        private GridPos blockPosition() {
            return new GridPos(block.x(), block.y(), block.z());
        }

        @Override
        public int compareTo(final SurfaceKey other) {
            int result = Integer.compare(block.x(), other.block.x());
            if (result == 0) {
                result = Integer.compare(block.y(), other.block.y());
            }
            if (result == 0) {
                result = Integer.compare(block.z(), other.block.z());
            }
            if (result == 0) {
                result = face.compareTo(other.face);
            }
            return result;
        }
    }
}
