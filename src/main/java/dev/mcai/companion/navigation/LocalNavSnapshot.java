package dev.mcai.companion.navigation;

import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.mcai.companion.waypoint.DimensionRef;

/**
 * Immutable input boundary for local navigation. It has no Minecraft level,
 * chunk, block, or entity access.
 */
public final class LocalNavSnapshot {
    private final DimensionRef dimension;
    private final long revision;
    private final Map<GridPos, ObservedVoxel> observedVoxels;
    private final List<ObservedVoxel> latestObservedVoxels;

    public LocalNavSnapshot(
        DimensionRef dimension,
        long revision,
        Collection<ObservedVoxel> observedVoxels
    ) {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        if (revision < 0) {
            throw new IllegalArgumentException("Snapshot revision must be non-negative");
        }
        this.revision = revision;
        Objects.requireNonNull(observedVoxels, "observedVoxels");
        final Map<GridPos, ObservedVoxel> indexed = new HashMap<>();
        final List<ObservedVoxel> latest = new ArrayList<>();
        for (ObservedVoxel voxel : observedVoxels) {
            Objects.requireNonNull(voxel, "observed voxel");
            if (voxel.observationRevision() > revision) {
                throw new IllegalArgumentException(
                    "Voxel revision cannot exceed the snapshot revision"
                );
            }
            if (indexed.putIfAbsent(voxel.position(), voxel) != null) {
                throw new IllegalArgumentException("Duplicate observed voxel position");
            }
            if (voxel.observationRevision() == revision) {
                latest.add(voxel);
            }
        }
        this.observedVoxels = Collections.unmodifiableMap(indexed);
        this.latestObservedVoxels = List.copyOf(latest);
    }

    public DimensionRef dimension() {
        return dimension;
    }

    public long revision() {
        return revision;
    }

    public Optional<ObservedVoxel> voxelAt(GridPos position) {
        Objects.requireNonNull(position, "position");
        return Optional.ofNullable(observedVoxels.get(position));
    }

    public boolean isObserved(GridPos position) {
        return voxelAt(position).isPresent();
    }

    public Map<GridPos, ObservedVoxel> observedVoxels() {
        return observedVoxels;
    }

    /**
     * Returns only evidence authored by this snapshot's newest fair
     * observation. Consumers that already retain older map state can merge
     * this bounded delta instead of rescanning the complete rolling map.
     */
    public List<ObservedVoxel> latestObservedVoxels() {
        return latestObservedVoxels;
    }
}
