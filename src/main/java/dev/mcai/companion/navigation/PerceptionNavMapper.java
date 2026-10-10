package dev.mcai.companion.navigation;

import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.CollisionAffordance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Incrementally converts only first-person ray evidence into the voxel
 * boundary consumed by {@link LocalAStarPlanner}. Missing cells remain
 * unknown; this class never reads a Minecraft chunk or block directly.
 */
public final class PerceptionNavMapper {
    public static final int DEFAULT_MAXIMUM_VOXELS = 16_384;
    public static final double DEFAULT_RETENTION_RADIUS = 64.0;
    private static final double RAY_SAMPLE_SPACING = 0.20;
    private static final int MULTI_RAY_MINIMUM = 3;

    private final int maximumVoxels;
    private final double retentionRadius;
    private final Map<GridPos, ObservedVoxel> observed = new HashMap<>();
    private final Map<GridPos, Integer> currentClearRayCounts =
        new HashMap<>();

    private DimensionRef dimension;
    private long revision = -1;
    private GridPos currentFeet;
    /*
     * A semantic revision is immutable once ingest() returns.  Runtime
     * consumers ask for the same navigation boundary from several lanes in
     * one tick (skill frames, diagnostics, and route planning); rebuilding
     * the rolling HashMap for each read was pure allocation on the server
     * thread.  Keep one immutable snapshot until the next fair observation.
     */
    private LocalNavSnapshot cachedSnapshot;

    public PerceptionNavMapper() {
        this(DEFAULT_MAXIMUM_VOXELS, DEFAULT_RETENTION_RADIUS);
    }

    public PerceptionNavMapper(
        final int maximumVoxels,
        final double retentionRadius
    ) {
        if (maximumVoxels < 64 || maximumVoxels > 1_000_000) {
            throw new IllegalArgumentException("maximumVoxels is outside its bound");
        }
        if (!Double.isFinite(retentionRadius)
            || retentionRadius < 8.0
            || retentionRadius > 512.0) {
            throw new IllegalArgumentException("retentionRadius is outside its bound");
        }
        this.maximumVoxels = maximumVoxels;
        this.retentionRadius = retentionRadius;
    }

    /**
     * Ingests a strictly newer semantic sample. Rays mark only their observed
     * free segment and the actual hit surface.
     */
    public synchronized LocalNavSnapshot ingest(
        final SemanticObservation observation
    ) {
        Objects.requireNonNull(observation, "observation");
        if (observation.sequence() <= revision) {
            throw new IllegalArgumentException("Observation revision must increase");
        }
        final DimensionRef observedDimension = DimensionRef.parse(
            observation.body().dimensionId()
        );
        if (!observedDimension.equals(dimension)) {
            observed.clear();
            dimension = observedDimension;
            cachedSnapshot = null;
        }
        revision = observation.sequence();
        cachedSnapshot = null;
        currentClearRayCounts.clear();
        currentFeet = floor(observation.body().position());

        final VoxelKind bodyKind = observation.body().inWater()
            ? VoxelKind.WATER
            : VoxelKind.AIR;
        putCurrent(
            currentFeet,
            bodyKind,
            bodyDanger(observation.dangers())
        );
        putCurrent(
            currentFeet.above(),
            bodyKind,
            bodyDanger(observation.dangers())
        );
        if (observation.body().onGround() && !observation.body().inWater()) {
            putBodyContact(
                currentFeet.below(),
                0.0
            );
        }

        for (VisibleBlockFace face : observation.visibleBlockFaces()) {
            ingestRay(
                observation.body().eyePosition(),
                face.hitPosition(),
                new GridPos(face.block().x(), face.block().y(), face.block().z())
            );
            final VoxelKind kind = classify(face);
            put(
                new GridPos(face.block().x(), face.block().y(), face.block().z()),
                kind,
                0.0,
                face.collisionAffordance() == CollisionAffordance.EMPTY
                        && kind == VoxelKind.AIR
                    ? OccupancyEvidence.COLLISION_SHAPE_CLEAR
                    : OccupancyEvidence.SURFACE_HIT,
                face.topSupportAffordance()
            );
        }
        observation.clearSightRays().forEach(ray ->
            ingestClearRay(
                observation.body().eyePosition(),
                ray.endPosition()
            )
        );
        prune();
        return snapshot();
    }

    public synchronized LocalNavSnapshot snapshot() {
        if (dimension == null || revision < 0) {
            throw new IllegalStateException("No observation has been ingested");
        }
        if (cachedSnapshot == null) {
            cachedSnapshot = new LocalNavSnapshot(
                dimension,
                revision,
                observed.values()
            );
        }
        return cachedSnapshot;
    }

    public synchronized GridPos currentFeet() {
        if (currentFeet == null) {
            throw new IllegalStateException("No observation has been ingested");
        }
        return currentFeet;
    }

    public synchronized int observedVoxelCount() {
        return observed.size();
    }

    /**
     * Invalidates body-local navigation evidence when the authoritative
     * player session changes. A replacement body must establish its own
     * first-person map even when it appears in the same dimension and near
     * the previous position.
     */
    public synchronized void reset() {
        observed.clear();
        currentClearRayCounts.clear();
        dimension = null;
        revision = -1;
        currentFeet = null;
        cachedSnapshot = null;
    }

    private void ingestRay(
        final PerceptionVec3 origin,
        final PerceptionVec3 hit,
        final GridPos hitBlock
    ) {
        final double deltaX = hit.x() - origin.x();
        final double deltaY = hit.y() - origin.y();
        final double deltaZ = hit.z() - origin.z();
        final double distance = Math.sqrt(
            deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
        );
        final int samples = Math.max(
            1,
            (int) Math.ceil(distance / RAY_SAMPLE_SPACING)
        );
        /*
         * Samples along one ray are monotonic.  The old implementation
         * allocated a LinkedHashSet for every ray and hashed the same voxel
         * repeatedly when the 0.20-block samples fell inside one block.
         * Keep the exact per-ray de-duplication semantics with one previous
         * coordinate instead; currentClearRayCounts still aggregates evidence
         * across distinct rays below.
         */
        GridPos previous = null;
        for (int sample = 0; sample < samples; sample++) {
            final double interpolation = (double) sample / samples;
            final GridPos position = floor(
                origin.x() + deltaX * interpolation,
                origin.y() + deltaY * interpolation,
                origin.z() + deltaZ * interpolation
            );
            if (!position.equals(hitBlock)
                    && !position.equals(previous)) {
                putRayClear(position);
            }
            previous = position;
        }
    }

    private void ingestClearRay(
        final PerceptionVec3 origin,
        final PerceptionVec3 end
    ) {
        final double deltaX = end.x() - origin.x();
        final double deltaY = end.y() - origin.y();
        final double deltaZ = end.z() - origin.z();
        final double distance = Math.sqrt(
            deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
        );
        final int samples = Math.max(
            1,
            (int) Math.ceil(distance / RAY_SAMPLE_SPACING)
        );
        /* See ingestRay: preserve ray-local de-duplication without creating
         * a temporary hash set for every visible clear ray. */
        GridPos previous = null;
        for (int sample = 0; sample <= samples; sample++) {
            final double interpolation =
                (double) sample / samples;
            final GridPos position = floor(
                origin.x() + deltaX * interpolation,
                origin.y() + deltaY * interpolation,
                origin.z() + deltaZ * interpolation
            );
            if (!position.equals(previous)) {
                putRayClear(position);
            }
            previous = position;
        }
    }

    private void put(
        final GridPos position,
        final VoxelKind kind,
        final double danger,
        final OccupancyEvidence occupancyEvidence,
        final TopSupportAffordance topSupportAffordance
    ) {
        final ObservedVoxel existing = observed.get(position);
        final double combinedDanger = existing == null
            ? danger
            : Math.max(existing.danger(), danger);
        final TopSupportAffordance combinedSupport =
            existing != null
                && existing.observationRevision() == revision
                ? strongerSupport(
                    existing.topSupportAffordance(),
                    topSupportAffordance
                )
                : topSupportAffordance;
        final OccupancyEvidence combinedOccupancy =
            existing != null
                && existing.observationRevision() == revision
                ? strongerOccupancy(
                    existing.occupancyEvidence(),
                    occupancyEvidence
                )
                : occupancyEvidence;
        observed.put(position, new ObservedVoxel(
            position,
            kind,
            combinedDanger,
            revision,
            combinedOccupancy,
            combinedSupport
        ));
    }

    private void putCurrent(
        final GridPos position,
        final VoxelKind kind,
        final double danger
    ) {
        observed.put(position, new ObservedVoxel(
            position,
            kind,
            danger,
            revision,
            OccupancyEvidence.BODY_OCCUPIED,
            TopSupportAffordance.UNKNOWN
        ));
    }

    /**
     * Refreshes the support beneath the current body on every sample.  Keeping
     * an earlier BODY_CONTACT revision would make a stationary player's real
     * start support appear stale once destination support is fail-closed.
     */
    private void putBodyContact(
        final GridPos position,
        final double danger
    ) {
        final ObservedVoxel existing = observed.get(position);
        /*
         * onGround proves that some collision surface under the body is
         * carrying it; it does not prove that the block directly below the
         * floored body centre is that surface. While straddling an edge, an
         * adjacent block may provide the contact. Do not overwrite recent
         * multi-ray air at the centre-below coordinate with an invented
         * solid. A direct surface ray can still replace it, and the retained
         * evidence expires normally at its consumers.
         */
        if (existing != null
                && existing.kind() == VoxelKind.AIR
                && NavigationEvidence.hasTraversalClearance(existing)) {
            return;
        }
        /* An edge-straddling player can report onGround while the voxel
         * directly below its floored centre is an irrigation source.  Body
         * contact proves that some collision surface carries the player; it
         * must never reinterpret that observed liquid as SOLID, otherwise a
         * farming route may select the water cell and recover by jumping onto
         * a crop.  Preserve the stronger liquid observation and let the
         * consumer choose a verified side step instead. */
        if (existing != null && existing.kind().isLiquid()) {
            return;
        }
        if (existing != null
                && existing.observationRevision() == revision
                && existing.occupancyEvidence()
                    == OccupancyEvidence.BODY_CONTACT) {
            return;
        }
        final VoxelKind kind = existing != null
                && existing.kind().supportsWeight()
            ? existing.kind()
            : VoxelKind.SOLID;
        final TopSupportAffordance support = existing == null
                || existing.observationRevision() != revision
            ? TopSupportAffordance.UNKNOWN
            : existing.topSupportAffordance();
        observed.put(position, new ObservedVoxel(
            position,
            kind,
            existing == null
                ? danger
                : Math.max(existing.danger(), danger),
            revision,
            OccupancyEvidence.BODY_CONTACT,
            support
        ));
    }

    /**
     * Newer line-of-sight evidence may clear a stale solid, but it cannot
     * erase a surface observed in the same sample or reinterpret liquid as
     * air.
     */
    private void putRayClear(final GridPos position) {
        final Integer previousCount = currentClearRayCounts.get(position);
        final int rayCount = previousCount == null
            ? 1
            : Math.addExact(previousCount, 1);
        currentClearRayCounts.put(position, rayCount);
        final OccupancyEvidence evidence =
            rayCount >= MULTI_RAY_MINIMUM
                ? OccupancyEvidence.MULTI_RAY_CLEAR
                : OccupancyEvidence.SINGLE_RAY_CLEAR;
        final ObservedVoxel existing = observed.get(position);
        if (existing != null
                && existing.observationRevision() == revision
                && (existing.occupancyEvidence()
                        == OccupancyEvidence.SURFACE_HIT
                    || existing.occupancyEvidence()
                        == OccupancyEvidence.BODY_CONTACT
                    || existing.occupancyEvidence()
                        == OccupancyEvidence.BODY_OCCUPIED
                    || existing.kind().isLiquid())) {
            return;
        }
        if (existing != null
                && existing.observationRevision() == revision
                && existing.kind() == VoxelKind.AIR
                && (existing.occupancyEvidence()
                        == OccupancyEvidence.COLLISION_SHAPE_CLEAR
                    || rayCount < MULTI_RAY_MINIMUM
                    && existing.occupancyEvidence()
                        == OccupancyEvidence.SINGLE_RAY_CLEAR
                    || existing.occupancyEvidence()
                        == OccupancyEvidence.MULTI_RAY_CLEAR)) {
            return;
        }
        /*
         * One or two infinitesimal clear rays may have slipped through the
         * visual shape of a slab, fence, rail, or other partial block.  They
         * must not erase older surface evidence.  Three rays may update the
         * heuristic navigation map, but MULTI_RAY_CLEAR still is not a
         * full-player-volume safety fact.
         */
        if (existing != null
                && existing.observationRevision() < revision
                && existing.kind() != VoxelKind.AIR
                && rayCount < MULTI_RAY_MINIMUM) {
            return;
        }
        /*
         * A lone newer ray also cannot disprove previously established
         * multi-ray body clearance. Keep the stronger observation and let
         * its bounded consumer-side age expire normally. A direct surface
         * hit still replaces it above through put(), while three current
         * rays may refresh it below.
         */
        if (existing != null
                && existing.observationRevision() < revision
                && existing.kind() == VoxelKind.AIR
                && NavigationEvidence.hasTraversalClearance(existing)
                && rayCount < MULTI_RAY_MINIMUM) {
            return;
        }
        if (existing == null
                || existing.observationRevision() < revision
                && !existing.kind().isLiquid()
                || existing.observationRevision() == revision
                    && existing.kind() == VoxelKind.AIR) {
            observed.put(position, new ObservedVoxel(
                position,
                VoxelKind.AIR,
                0.0,
                revision,
                evidence,
                TopSupportAffordance.UNKNOWN
            ));
        }
    }

    private void prune() {
        final double maximumDistanceSquared = retentionRadius * retentionRadius;
        observed.entrySet().removeIf(entry ->
            squaredDistance(entry.getKey(), currentFeet) > maximumDistanceSquared
        );
        if (observed.size() <= maximumVoxels) {
            return;
        }
        final List<ObservedVoxel> evictionOrder = new ArrayList<>(observed.values());
        evictionOrder.sort(
            Comparator.comparingLong(ObservedVoxel::observationRevision)
                .thenComparing(
                    Comparator.comparingDouble(
                        (ObservedVoxel voxel) ->
                            squaredDistance(voxel.position(), currentFeet)
                    ).reversed()
                )
                .thenComparing(ObservedVoxel::position)
        );
        final int removeCount = observed.size() - maximumVoxels;
        for (int index = 0; index < removeCount; index++) {
            observed.remove(evictionOrder.get(index).position());
        }
    }

    private static double bodyDanger(final List<DangerSignal> dangers) {
        return dangers.stream()
            /*
             * Fire, low air and falling describe the player's body, not an
             * environmental property of the voxel currently occupied.  If
             * copied into the map, a legitimate intentional fall makes its
             * observed landing cell look permanently hazardous and defeats
             * dynamic safety revalidation.  Physical/proximity threats may
             * still mark the current cell.
             */
            .filter(signal ->
                signal.provenance()
                    != PerceptionProvenance.BODY_HAZARD
            )
            .mapToDouble(DangerSignal::severity)
            .max()
            .orElse(0.0);
    }

    private static TopSupportAffordance strongerSupport(
        final TopSupportAffordance first,
        final TopSupportAffordance second
    ) {
        if (first == TopSupportAffordance.STURDY_FULL_TOP
                || second == TopSupportAffordance.STURDY_FULL_TOP) {
            return TopSupportAffordance.STURDY_FULL_TOP;
        }
        if (first
                    == TopSupportAffordance.WALKABLE_FULL_FOOTPRINT_TOP
                || second
                    == TopSupportAffordance.WALKABLE_FULL_FOOTPRINT_TOP) {
            return TopSupportAffordance.WALKABLE_FULL_FOOTPRINT_TOP;
        }
        if (first == TopSupportAffordance.NON_STURDY_OR_PARTIAL
                || second
                    == TopSupportAffordance.NON_STURDY_OR_PARTIAL) {
            return TopSupportAffordance.NON_STURDY_OR_PARTIAL;
        }
        return TopSupportAffordance.UNKNOWN;
    }

    private static OccupancyEvidence strongerOccupancy(
        final OccupancyEvidence first,
        final OccupancyEvidence second
    ) {
        if (first == OccupancyEvidence.BODY_OCCUPIED
                || second == OccupancyEvidence.BODY_OCCUPIED) {
            return OccupancyEvidence.BODY_OCCUPIED;
        }
        if (first == OccupancyEvidence.BODY_CONTACT
                || second == OccupancyEvidence.BODY_CONTACT) {
            return OccupancyEvidence.BODY_CONTACT;
        }
        if (first == OccupancyEvidence.SURFACE_HIT
                || second == OccupancyEvidence.SURFACE_HIT) {
            return OccupancyEvidence.SURFACE_HIT;
        }
        if (first == OccupancyEvidence.COLLISION_SHAPE_CLEAR
                || second == OccupancyEvidence.COLLISION_SHAPE_CLEAR) {
            return OccupancyEvidence.COLLISION_SHAPE_CLEAR;
        }
        if (first == OccupancyEvidence.MULTI_RAY_CLEAR
                || second == OccupancyEvidence.MULTI_RAY_CLEAR) {
            return OccupancyEvidence.MULTI_RAY_CLEAR;
        }
        if (first == OccupancyEvidence.SINGLE_RAY_CLEAR
                || second == OccupancyEvidence.SINGLE_RAY_CLEAR) {
            return OccupancyEvidence.SINGLE_RAY_CLEAR;
        }
        return OccupancyEvidence.UNKNOWN;
    }

    static VoxelKind classify(final String blockTypeId) {
        Objects.requireNonNull(blockTypeId, "blockTypeId");
        final String id = blockTypeId.toLowerCase(Locale.ROOT);
        if (id.equals("minecraft:air")
            || id.equals("minecraft:cave_air")
            || id.equals("minecraft:void_air")
            || id.equals("minecraft:nether_portal")
            || id.equals("minecraft:end_portal")
            || id.equals("minecraft:end_gateway")) {
            return VoxelKind.AIR;
        }
        if (id.endsWith(":water")) {
            return VoxelKind.WATER;
        }
        if (id.endsWith(":lava")) {
            return VoxelKind.LAVA;
        }
        if (id.endsWith("_ladder")
            || id.endsWith(":ladder")
            || id.endsWith(":vine")
            || id.endsWith("_vines")
            || id.endsWith(":scaffolding")) {
            return VoxelKind.CLIMBABLE;
        }
        if (id.endsWith("_door")) {
            // The current perception contract exposes no OPEN state. Treat a
            // visible door as closed until an interaction confirms otherwise.
            return VoxelKind.CLOSED_DOOR;
        }
        return VoxelKind.SOLID;
    }

    static VoxelKind classify(final VisibleBlockFace face) {
        Objects.requireNonNull(face, "face");
        final VoxelKind semantic = classify(face.blockTypeId());
        if (face.collisionAffordance() != CollisionAffordance.EMPTY) {
            return semantic;
        }
        return switch (semantic) {
            case SOLID -> VoxelKind.AIR;
            case CLOSED_DOOR -> VoxelKind.OPEN_DOOR;
            default -> semantic;
        };
    }

    private static GridPos floor(final PerceptionVec3 vector) {
        return floor(vector.x(), vector.y(), vector.z());
    }

    /**
     * Ray rasterization hot path. Keeping the arithmetic here avoids a
     * temporary PerceptionVec3 for every 0.20-block sample while preserving
     * the exact floor semantics of the public vector overload.
     */
    private static GridPos floor(
        final double x,
        final double y,
        final double z
    ) {
        return new GridPos(
            (int) Math.floor(x),
            (int) Math.floor(y),
            (int) Math.floor(z)
        );
    }

    private static double squaredDistance(
        final GridPos first,
        final GridPos second
    ) {
        final double deltaX = (double) first.x() - second.x();
        final double deltaY = (double) first.y() - second.y();
        final double deltaZ = (double) first.z() - second.z();
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }
}
