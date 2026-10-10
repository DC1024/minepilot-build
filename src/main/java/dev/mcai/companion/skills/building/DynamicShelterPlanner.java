package dev.mcai.companion.skills.building;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.VisibleEntityPlacementEnvelope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Constraint-based, fair shelter synthesis over observed voxels only.
 *
 * <p>The planner enumerates feasible sizes, positions, and entrances around
 * the companion. It never queries a level/chunk, assumes an unobserved cell
 * is empty, or loads a saved block template.</p>
 */
public final class DynamicShelterPlanner {
    public static final double MAXIMUM_SITE_DANGER = 0.35;
    private static final int INTERIOR_HEIGHT = 2;
    private static final int MAXIMUM_RELOCATION_ORIGIN_DISTANCE = 8;

    public ShelterPlanningResult plan(
            ShelterFrame frame,
            ShelterScale scale
    ) {
        return plan(
                frame,
                scale,
                PlanningConstraints.none()
        );
    }

    /**
     * Regenerates a site after a physical placement obstruction without
     * discarding already confirmed material.
     *
     * <p>Reusable cells are causal receipts owned by the current building
     * transaction, not level queries. A candidate may count one only when
     * that exact cell is part of its new structural shell. Forbidden cells
     * may remain interior but can never become wall, roof, door, or light
     * targets.</p>
     */
    ShelterPlanningResult repair(
            final ShelterFrame frame,
            final ShelterScale scale,
            final String reusableItemId,
            final Set<GridPos> reusableStructuralBlocks,
            final Set<GridPos> forbiddenShellBlocks
    ) {
        Objects.requireNonNull(
                reusableItemId,
                "reusableItemId"
        );
        if (!isStructuralItem(reusableItemId)) {
            throw new IllegalArgumentException(
                    "Reusable shelter material is not structural"
            );
        }
        return plan(
                frame,
                scale,
                new PlanningConstraints(
                        reusableItemId,
                        reusableStructuralBlocks,
                        forbiddenShellBlocks
                )
        );
    }

    private ShelterPlanningResult plan(
            ShelterFrame frame,
            ShelterScale scale,
            PlanningConstraints constraints
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(scale, "scale");
        Objects.requireNonNull(constraints, "constraints");
        Map<String, Integer> inventory = inventory(frame.inventory());

        Optional<String> door = inventory.keySet().stream()
                .filter(DynamicShelterPlanner::isSafeDoorItem)
                .filter(item -> inventory.get(item) >= 1)
                .sorted()
                .findFirst();
        if (door.isEmpty()) {
            return ShelterPlanningResult.failed("shelter.missing_door");
        }
        Optional<String> light = inventory.keySet().stream()
                .filter(DynamicShelterPlanner::isLightItem)
                .filter(item -> inventory.get(item) >= 1)
                .sorted(Comparator.comparingInt(
                        DynamicShelterPlanner::lightPreference
                ).thenComparing(Comparator.naturalOrder()))
                .findFirst();
        if (light.isEmpty()) {
            return ShelterPlanningResult.failed("shelter.missing_light");
        }

        List<MaterialChoice> materials = structuralMaterials(
                frame,
                inventory
        );
        if (materials.isEmpty()) {
            return ShelterPlanningResult.failed(
                    "shelter.missing_structural_material"
            );
        }

        SearchEvidence evidence = new SearchEvidence();
        Candidate best = null;
        List<InteriorSize> sizes = sizes(scale);
        for (int sizeRank = 0; sizeRank < sizes.size(); sizeRank++) {
            InteriorSize size = sizes.get(sizeRank);
            int required = structuralBlockCount(
                    size.width(),
                    size.depth()
            );
            for (int materialRank = 0;
                    materialRank < materials.size();
                    materialRank++) {
                MaterialChoice material = materials.get(materialRank);
                if (material.count()
                        + constraints.maximumReusableFor(material)
                        < required) {
                    continue;
                }
                Candidate candidate = searchSite(
                        frame,
                        scale,
                        size,
                        sizeRank,
                        material,
                        materialRank,
                        evidence,
                        constraints
                );
                if (candidate != null
                        && (best == null
                        || candidate.score() < best.score())) {
                    best = candidate;
                }
            }
        }
        if (best == null) {
            if (!materialsCanBuildMinimum(
                    materials,
                    constraints
            )) {
                return ShelterPlanningResult.failed(
                        "shelter.insufficient_structural_material"
                );
            }
            return ShelterPlanningResult.failed(
                    evidence.unknown()
                            ? "shelter.insufficient_observation"
                            : "shelter.no_safe_footprint"
            );
        }

        return ShelterPlanningResult.planned(generate(
                frame,
                scale,
                best,
                door.orElseThrow(),
                light.orElseThrow()
        ));
    }

    /**
     * Finds an already observed nearby standing cell from which normal local
     * planning can survey and build a shelter without enclosing the current
     * workstation cluster.
     *
     * <p>This is not a teleport destination and it does not inspect the
     * Minecraft level. The destination's support and player-sized clearance
     * come from the same incremental first-person navigation map used by
     * {@link #plan(ShelterFrame, ShelterScale)}. It deliberately does not
     * require the complete future building volume to be visible before the
     * player moves: that requirement creates a perception deadlock because a
     * first-person player normally walks to a promising patch of ground and
     * inspects it from there. The caller must still walk through an ordinary
     * movement skill, which refreshes every traversed cell, and re-survey the
     * complete footprint before construction.</p>
     */
    public Optional<GridPos> relocationTarget(
            final ShelterFrame frame,
            final ShelterScale scale
    ) {
        return relocationTarget(frame, scale, Set.of());
    }

    /**
     * Finds a relocation target outside every site already inspected for the
     * current shelter transaction.
     *
     * <p>A rejected site is an ordinary first-person survey receipt. Keeping
     * these receipts prevents deterministic tie-breaking from walking back
     * to the same unsuitable patch after the model retries the skill. The
     * exclusion radius is the same spacing used for a fresh candidate, so a
     * nominally different block inside the already rejected footprint does
     * not masquerade as a new site.</p>
     */
    public Optional<GridPos> relocationTarget(
            final ShelterFrame frame,
            final ShelterScale scale,
            final Set<GridPos> rejectedSurveyCenters
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(scale, "scale");
        Objects.requireNonNull(
                rejectedSurveyCenters,
                "rejectedSurveyCenters"
        );
        final GridPos feet = frame.feet();
        RelocationCandidate best = null;
        final double minimumDistance = switch (scale) {
            case COMPACT -> 4.0;
            case STANDARD -> 5.0;
            case SPACIOUS -> 6.0;
        };
        for (ObservedVoxel observed
                : frame.navigation().observedVoxels().values()) {
            final GridPos stand = observed.position();
            final int deltaY = Math.abs(stand.y() - feet.y());
            final double distance = feet.euclideanDistance(stand);
            if (deltaY > 1
                    || distance < minimumDistance
                    || distance
                        > MAXIMUM_RELOCATION_ORIGIN_DISTANCE) {
                continue;
            }
            final boolean alreadySurveyed =
                    rejectedSurveyCenters.stream().anyMatch(center ->
                            stand.euclideanDistance(center)
                                    < minimumDistance
                    );
            if (alreadySurveyed) {
                continue;
            }
            final Optional<ObservedVoxel> head =
                    frame.navigation().voxelAt(stand.above());
            final Optional<ObservedVoxel> support =
                    frame.navigation().voxelAt(stand.below());
            if (!NavigationEvidence.hasTraversalClearance(observed)
                    || head.isEmpty()
                    || !NavigationEvidence.hasTraversalClearance(
                            head.orElseThrow()
                    )
                    || support.isEmpty()
                    || !support.orElseThrow().kind().supportsWeight()
                    || support.orElseThrow().effectiveDanger()
                        > MAXIMUM_SITE_DANGER
                    || knownBodyHeightObstacleNearby(
                            frame.navigation(),
                            stand
                    )) {
                continue;
            }
            final double score = Math.abs(distance - minimumDistance)
                    * 100.0
                    + deltaY * 50.0
                    + observed.effectiveDanger() * 25.0
                    + stableStandTieBreak(stand, scale);
            final RelocationCandidate candidate =
                    new RelocationCandidate(stand, score);
            if (best == null || candidate.score() < best.score()) {
                best = candidate;
            }
        }
        return best == null
                ? Optional.empty()
                : Optional.of(best.stand());
    }

    /**
     * Reject a destination whose immediately surrounding body-height cells
     * are already known to contain a workstation, wall, tree, or other
     * obstacle. Unknown cells are not declared safe building volume here;
     * they are simply deferred to the mandatory post-move survey.
     */
    private static boolean knownBodyHeightObstacleNearby(
            final LocalNavSnapshot navigation,
            final GridPos stand
    ) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y <= 1; y++) {
                    final Optional<ObservedVoxel> voxel =
                            navigation.voxelAt(
                                    stand.offset(x, y, z)
                            );
                    if (voxel.isPresent()
                            && !voxel.orElseThrow()
                                .kind().isPassable()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static double stableStandTieBreak(
            final GridPos stand,
            final ShelterScale scale
    ) {
        final long mixed = 31L * stand.x()
                + 17L * stand.y()
                + 13L * stand.z()
                + scale.ordinal();
        return Math.floorMod(mixed, 997L) / 100_000.0;
    }

    public static boolean isStructuralItem(String itemId) {
        if (itemId == null || !itemId.startsWith("minecraft:")) {
            return false;
        }
        String path = itemId.substring("minecraft:".length());
        return path.endsWith("_planks")
                || path.endsWith("_log")
                || path.endsWith("_wood")
                || path.endsWith("_hyphae")
                || path.endsWith("_stem")
                || path.endsWith("_bricks")
                || path.endsWith("_terracotta")
                || path.equals("stone")
                || path.equals("cobblestone")
                || path.equals("cobbled_deepslate")
                || path.equals("deepslate")
                || path.equals("blackstone")
                || path.equals("netherrack")
                || path.equals("bricks")
                || path.equals("terracotta")
                || path.equals("dirt")
                || path.equals("coarse_dirt")
                || path.equals("packed_mud");
    }

    public static boolean isSafeDoorItem(String itemId) {
        return itemId != null
                && itemId.startsWith("minecraft:")
                && itemId.endsWith("_door")
                && !itemId.equals("minecraft:iron_door")
                && !itemId.contains("copper");
    }

    public static boolean isLightItem(String itemId) {
        return itemId != null
                && (itemId.equals("minecraft:torch")
                || itemId.equals("minecraft:soul_torch")
                || itemId.equals("minecraft:lantern")
                || itemId.equals("minecraft:soul_lantern"));
    }

    public static int structuralBlockCount(
            int interiorWidth,
            int interiorDepth
    ) {
        if (interiorWidth < 3 || interiorDepth < 3) {
            throw new IllegalArgumentException(
                    "Interior dimensions are below minimum"
            );
        }
        int exteriorWidth = Math.addExact(interiorWidth, 2);
        int exteriorDepth = Math.addExact(interiorDepth, 2);
        int perimeter = Math.subtractExact(
                Math.multiplyExact(
                        2,
                        Math.addExact(exteriorWidth, exteriorDepth)
                ),
                4
        );
        int walls = Math.subtractExact(
                Math.multiplyExact(perimeter, INTERIOR_HEIGHT),
                2
        );
        return Math.addExact(
                walls,
                Math.multiplyExact(exteriorWidth, exteriorDepth)
        );
    }

    private static List<MaterialChoice> structuralMaterials(
            ShelterFrame frame,
            Map<String, Integer> inventory
    ) {
        List<MaterialChoice> result = new ArrayList<>();
        String held = frame.mainHand().itemId();
        if (isStructuralItem(held) && inventory.containsKey(held)) {
            result.add(new MaterialChoice(
                    held,
                    inventory.get(held)
            ));
        }
        inventory.entrySet().stream()
                .filter(entry -> isStructuralItem(entry.getKey()))
                .filter(entry -> !entry.getKey().equals(held))
                .sorted(
                        Comparator.<Map.Entry<String, Integer>>
                                comparingInt(entry ->
                                        defensePreference(entry.getKey()))
                                .thenComparing(
                                        Comparator.comparingInt(
                                                Map.Entry<String, Integer>::getValue
                                        ).reversed()
                                )
                                .thenComparing(Map.Entry::getKey)
                )
                .forEach(entry -> result.add(new MaterialChoice(
                        entry.getKey(),
                        entry.getValue()
                )));
        return List.copyOf(result);
    }

    private static Candidate searchSite(
            ShelterFrame frame,
            ShelterScale scale,
            InteriorSize size,
            int sizeRank,
            MaterialChoice material,
            int materialRank,
            SearchEvidence evidence,
            PlanningConstraints constraints
    ) {
        int exteriorWidth = size.width() + 2;
        int exteriorDepth = size.depth() + 2;
        GridPos feet = frame.feet();
        Candidate best = null;

        // Keeping the body inside the prospective interior avoids placing a
        // wall into its own collision box and bounds the search to at most 25
        // positions for the largest supported size.
        for (int originX = feet.x() - size.width();
                originX <= feet.x() - 1;
                originX++) {
            for (int originZ = feet.z() - size.depth();
                    originZ <= feet.z() - 1;
                    originZ++) {
                GridPos origin = new GridPos(
                        originX,
                        feet.y(),
                        originZ
                );
                SiteCheck site = checkSite(
                        frame,
                        origin,
                        exteriorWidth,
                        exteriorDepth,
                        constraints.reusableFor(material),
                        constraints.forbiddenShellBlocks()
                );
                evidence.record(site);
                if (!site.valid()) {
                    continue;
                }
                for (ShelterFacing facing : ShelterFacing.values()) {
                    GridPos door = doorPosition(
                            origin,
                            exteriorWidth,
                            exteriorDepth,
                            facing
                    );
                    final Set<GridPos> reusable =
                            constraints.reusableFor(material);
                    if (reusable.contains(door)
                            || reusable.contains(door.above())
                            || constraints.forbiddenShellBlocks()
                                    .contains(door)
                            || constraints.forbiddenShellBlocks()
                                    .contains(door.above())) {
                        continue;
                    }
                    final GridPos light = origin.offset(
                            1 + size.width() / 2,
                            0,
                            1 + size.depth() / 2
                    );
                    if (constraints.forbiddenShellBlocks()
                            .contains(light)) {
                        continue;
                    }
                    SiteCheck entrance = checkEntrance(
                            frame.navigation(),
                            door,
                            facing
                    );
                    evidence.record(entrance);
                    if (!entrance.valid()) {
                        continue;
                    }
                    final int reusedBlocks =
                            reusableStructuralCount(
                                    reusable,
                                    origin,
                                    exteriorWidth,
                                    exteriorDepth,
                                    door
                            );
                    final int required =
                            structuralBlockCount(
                                    size.width(),
                                    size.depth()
                            );
                    if (material.count() + reusedBlocks
                            < required) {
                        continue;
                    }
                    double centerX = origin.x()
                            + (exteriorWidth - 1) / 2.0;
                    double centerZ = origin.z()
                            + (exteriorDepth - 1) / 2.0;
                    double centerDistance = Math.hypot(
                            frame.feet().x() + 0.5 - centerX,
                            frame.feet().z() + 0.5 - centerZ
                    );
                    double entranceAlignment = facing.alignment(
                            frame.lookDirection().x(),
                            frame.lookDirection().z()
                    );
                    double score = sizeRank * 10_000.0
                            + materialRank * 250.0
                            + centerDistance * 8.0
                            + site.danger() * 100.0
                            - entranceAlignment * 4.0
                            - reusedBlocks * 500.0
                            + stableTieBreak(origin, facing, scale);
                    Candidate candidate = new Candidate(
                            origin,
                            size,
                            facing,
                            door,
                            material,
                            score
                    );
                    if (best == null || candidate.score() < best.score()) {
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static SiteCheck checkSite(
            ShelterFrame frame,
            GridPos origin,
            int exteriorWidth,
            int exteriorDepth,
            Set<GridPos> reusableStructuralBlocks,
            Set<GridPos> forbiddenShellBlocks
    ) {
        if (forbiddenIntersectsShell(
                forbiddenShellBlocks,
                origin,
                exteriorWidth,
                exteriorDepth
        )) {
            return SiteCheck.blocked();
        }
        if (recentVisibleEntityIntersectsShell(
                frame,
                origin,
                exteriorWidth,
                exteriorDepth
        )) {
            return SiteCheck.blocked();
        }
        final LocalNavSnapshot navigation = frame.navigation();
        double danger = 0.0;
        for (int x = 0; x < exteriorWidth; x++) {
            for (int z = 0; z < exteriorDepth; z++) {
                GridPos ground = origin.offset(x, -1, z);
                Optional<ObservedVoxel> groundVoxel =
                        navigation.voxelAt(ground);
                if (groundVoxel.isEmpty()) {
                    return SiteCheck.unobserved();
                }
                ObservedVoxel support = groundVoxel.orElseThrow();
                if (!support.kind().supportsWeight()
                        || support.effectiveDanger()
                        > MAXIMUM_SITE_DANGER) {
                    return SiteCheck.blocked();
                }
                danger += support.effectiveDanger();
                for (int y = 0; y <= INTERIOR_HEIGHT; y++) {
                    final GridPos position =
                            origin.offset(x, y, z);
                    Optional<ObservedVoxel> volume = navigation.voxelAt(
                            position
                    );
                    if (volume.isEmpty()) {
                        return SiteCheck.unobserved();
                    }
                    ObservedVoxel voxel = volume.orElseThrow();
                    final boolean reusableShell =
                            reusableStructuralBlocks
                                    .contains(position)
                            && isShellCell(
                                    origin,
                                    exteriorWidth,
                                    exteriorDepth,
                                    position
                            );
                    if (voxel.kind() != VoxelKind.AIR
                            && !reusableShell
                            || voxel.effectiveDanger()
                            > MAXIMUM_SITE_DANGER) {
                        return SiteCheck.blocked();
                    }
                    danger += voxel.effectiveDanger();
                }
            }
        }
        return SiteCheck.valid(danger);
    }

    private static boolean forbiddenIntersectsShell(
            final Set<GridPos> forbidden,
            final GridPos origin,
            final int exteriorWidth,
            final int exteriorDepth
    ) {
        return forbidden.stream().anyMatch(position ->
                isShellCell(
                        origin,
                        exteriorWidth,
                        exteriorDepth,
                        position
                ));
    }

    private static int reusableStructuralCount(
            final Set<GridPos> reusable,
            final GridPos origin,
            final int exteriorWidth,
            final int exteriorDepth,
            final GridPos door
    ) {
        return (int) reusable.stream()
                .filter(position ->
                        isShellCell(
                                origin,
                                exteriorWidth,
                                exteriorDepth,
                                position
                        ))
                .filter(position ->
                        !position.equals(door)
                                && !position.equals(door.above()))
                .count();
    }

    private static boolean isShellCell(
            final GridPos origin,
            final int exteriorWidth,
            final int exteriorDepth,
            final GridPos position
    ) {
        final int relativeX = position.x() - origin.x();
        final int relativeY = position.y() - origin.y();
        final int relativeZ = position.z() - origin.z();
        if (relativeX < 0
                || relativeX >= exteriorWidth
                || relativeZ < 0
                || relativeZ >= exteriorDepth
                || relativeY < 0
                || relativeY > INTERIOR_HEIGHT) {
            return false;
        }
        if (relativeY == INTERIOR_HEIGHT) {
            return true;
        }
        return relativeX == 0
                || relativeX == exteriorWidth - 1
                || relativeZ == 0
                || relativeZ == exteriorDepth - 1;
    }

    /**
     * Rejects only the blocks that will form the generated shell, rather than
     * treating every animal in the future interior as a solid voxel. The
     * entity list is short-lived first-person memory supplied by
     * {@link ShelterFrame}; this method never queries the level.
     */
    private static boolean recentVisibleEntityIntersectsShell(
            final ShelterFrame frame,
            final GridPos origin,
            final int exteriorWidth,
            final int exteriorDepth
    ) {
        for (RecentVisibleEntity remembered :
                frame.recentVisibleEntities()) {
            for (int x = 0; x < exteriorWidth; x++) {
                for (int z = 0; z < exteriorDepth; z++) {
                    final boolean perimeter = x == 0
                            || z == 0
                            || x == exteriorWidth - 1
                            || z == exteriorDepth - 1;
                    if (perimeter
                            && (visibleEntityIntersectsBlock(
                                    remembered,
                                    origin.offset(x, 0, z)
                            ) || visibleEntityIntersectsBlock(
                                    remembered,
                                    origin.offset(x, 1, z)
                            ))) {
                        return true;
                    }
                    if (visibleEntityIntersectsBlock(
                            remembered,
                            origin.offset(x, INTERIOR_HEIGHT, z)
                    )) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns the freshest fairly observed actor whose conservative vanilla
     * collision envelope intersects one proposed placement cell.
     *
     * <p>The executor uses the same predicate as site selection after an
     * accepted use-on-block packet produces no world or inventory change.
     * Keeping this package-visible avoids a second, subtly different entity
     * geometry implementation. It never resolves the UUID through the level
     * and therefore cannot become hidden entity radar.</p>
     */
    static Optional<RecentVisibleEntity> visiblePlacementObstruction(
            final ShelterFrame frame,
            final GridPos target
    ) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(target, "target");
        return frame.recentVisibleEntities().stream()
                .filter(entity ->
                        visibleEntityIntersectsBlock(entity, target))
                .max(Comparator
                        .comparingLong(
                                RecentVisibleEntity::observedAtGameTime
                        )
                        .thenComparingLong(
                                RecentVisibleEntity::observationRevision
                        ));
    }

    static boolean visibleEntityIntersectsBlock(
            final RecentVisibleEntity remembered,
            final GridPos block
    ) {
        Objects.requireNonNull(remembered, "remembered");
        Objects.requireNonNull(block, "block");
        return VisibleEntityPlacementEnvelope.intersectsBlock(
                remembered.entity(),
                block.x(),
                block.y(),
                block.z()
        );
    }

    private static SiteCheck checkEntrance(
            LocalNavSnapshot navigation,
            GridPos door,
            ShelterFacing facing
    ) {
        GridPos outside = facing.outside(door);
        Optional<ObservedVoxel> ground = navigation.voxelAt(outside.below());
        Optional<ObservedVoxel> feet = navigation.voxelAt(outside);
        Optional<ObservedVoxel> head = navigation.voxelAt(outside.above());
        if (ground.isEmpty() || feet.isEmpty() || head.isEmpty()) {
            return SiteCheck.unobserved();
        }
        if (!ground.orElseThrow().kind().supportsWeight()
                || feet.orElseThrow().kind() != VoxelKind.AIR
                || head.orElseThrow().kind() != VoxelKind.AIR) {
            return SiteCheck.blocked();
        }
        double danger = Math.max(
                ground.orElseThrow().effectiveDanger(),
                Math.max(
                        feet.orElseThrow().effectiveDanger(),
                        head.orElseThrow().effectiveDanger()
                )
        );
        return danger <= MAXIMUM_SITE_DANGER
                ? SiteCheck.valid(danger)
                : SiteCheck.blocked();
    }

    private static ShelterPlan generate(
            ShelterFrame frame,
            ShelterScale scale,
            Candidate candidate,
            String doorItem,
            String lightItem
    ) {
        int exteriorWidth = candidate.size().width() + 2;
        int exteriorDepth = candidate.size().depth() + 2;
        List<ShelterBuildStep> steps = new ArrayList<>();
        GridPos origin = candidate.origin();
        GridPos door = candidate.door();

        addWallLayer(
                steps,
                origin,
                exteriorWidth,
                exteriorDepth,
                0,
                ShelterStepRole.LOWER_WALL,
                door
        );
        addWallLayer(
                steps,
                origin,
                exteriorWidth,
                exteriorDepth,
                1,
                ShelterStepRole.UPPER_WALL,
                door.above()
        );
        addRoof(
                steps,
                origin,
                exteriorWidth,
                exteriorDepth
        );
        steps.add(new ShelterBuildStep(
                steps.size(),
                ShelterStepRole.DOOR,
                door
        ));
        GridPos light = origin.offset(
                1 + candidate.size().width() / 2,
                0,
                1 + candidate.size().depth() / 2
        );
        steps.add(new ShelterBuildStep(
                steps.size(),
                ShelterStepRole.LIGHT,
                light
        ));

        int required = (int) steps.stream()
                .filter(step -> step.role().usesStructuralMaterial())
                .count();
        String id = planId(
                frame,
                scale,
                candidate,
                doorItem,
                lightItem
        );
        return new ShelterPlan(
                id,
                frame.dimension(),
                frame.observationRevision(),
                scale,
                origin,
                candidate.size().width(),
                candidate.size().depth(),
                INTERIOR_HEIGHT,
                candidate.facing(),
                door,
                light,
                candidate.material().itemId(),
                doorItem,
                lightItem,
                required,
                steps
        );
    }

    private static void addWallLayer(
            List<ShelterBuildStep> steps,
            GridPos origin,
            int width,
            int depth,
            int y,
            ShelterStepRole role,
            GridPos gap
    ) {
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (x != 0 && x != width - 1
                        && z != 0 && z != depth - 1) {
                    continue;
                }
                GridPos target = origin.offset(x, y, z);
                if (!target.equals(gap)) {
                    steps.add(new ShelterBuildStep(
                            steps.size(),
                            role,
                            target
                    ));
                }
            }
        }
    }

    private static void addRoof(
            List<ShelterBuildStep> steps,
            GridPos origin,
            int width,
            int depth
    ) {
        Set<GridPos> ordered = new LinkedHashSet<>();
        int rings = Math.max((width + 1) / 2, (depth + 1) / 2);
        for (int ring = 0; ring < rings; ring++) {
            int minimumX = ring;
            int maximumX = width - 1 - ring;
            int minimumZ = ring;
            int maximumZ = depth - 1 - ring;
            if (minimumX > maximumX || minimumZ > maximumZ) {
                continue;
            }
            for (int x = minimumX; x <= maximumX; x++) {
                ordered.add(origin.offset(x, INTERIOR_HEIGHT, minimumZ));
                ordered.add(origin.offset(x, INTERIOR_HEIGHT, maximumZ));
            }
            for (int z = minimumZ + 1; z < maximumZ; z++) {
                ordered.add(origin.offset(minimumX, INTERIOR_HEIGHT, z));
                ordered.add(origin.offset(maximumX, INTERIOR_HEIGHT, z));
            }
        }
        ordered.forEach(target -> steps.add(new ShelterBuildStep(
                steps.size(),
                ShelterStepRole.ROOF,
                target
        )));
    }

    private static GridPos doorPosition(
            GridPos origin,
            int width,
            int depth,
            ShelterFacing facing
    ) {
        return switch (facing) {
            case NORTH -> origin.offset(width / 2, 0, 0);
            case SOUTH -> origin.offset(width / 2, 0, depth - 1);
            case WEST -> origin.offset(0, 0, depth / 2);
            case EAST -> origin.offset(width - 1, 0, depth / 2);
        };
    }

    private static List<InteriorSize> sizes(ShelterScale scale) {
        return switch (scale) {
            case COMPACT -> List.of(new InteriorSize(3, 3));
            case STANDARD -> List.of(
                    new InteriorSize(4, 4),
                    new InteriorSize(4, 3),
                    new InteriorSize(3, 4),
                    new InteriorSize(3, 3)
            );
            case SPACIOUS -> List.of(
                    new InteriorSize(5, 5),
                    new InteriorSize(5, 4),
                    new InteriorSize(4, 5),
                    new InteriorSize(4, 4),
                    new InteriorSize(4, 3),
                    new InteriorSize(3, 4),
                    new InteriorSize(3, 3)
            );
        };
    }

    private static Map<String, Integer> inventory(
            List<InventoryItemSummary> items
    ) {
        Map<String, Integer> result = new HashMap<>();
        for (InventoryItemSummary item : items) {
            result.merge(item.itemId(), item.count(), Math::addExact);
        }
        return Map.copyOf(result);
    }

    private static boolean materialsCanBuildMinimum(
            List<MaterialChoice> materials,
            PlanningConstraints constraints
    ) {
        int minimum = structuralBlockCount(3, 3);
        return materials.stream().anyMatch(material ->
                material.count()
                        + constraints.maximumReusableFor(material)
                        >= minimum
        );
    }

    private static int defensePreference(String itemId) {
        String path = itemId.substring(itemId.indexOf(':') + 1)
                .toLowerCase(Locale.ROOT);
        if (path.contains("stone")
                || path.contains("deepslate")
                || path.contains("brick")
                || path.contains("terracotta")
                || path.equals("basalt")) {
            return 0;
        }
        if (path.contains("planks")
                || path.contains("log")
                || path.contains("wood")
                || path.contains("stem")
                || path.contains("hyphae")) {
            return 1;
        }
        return 2;
    }

    private static int lightPreference(String itemId) {
        return switch (itemId) {
            case "minecraft:torch" -> 0;
            case "minecraft:lantern" -> 1;
            case "minecraft:soul_torch" -> 2;
            case "minecraft:soul_lantern" -> 3;
            default -> 4;
        };
    }

    private static double stableTieBreak(
            GridPos origin,
            ShelterFacing facing,
            ShelterScale scale
    ) {
        long mixed = 31L * origin.x()
                + 17L * origin.z()
                + 7L * facing.ordinal()
                + scale.ordinal();
        return Math.floorMod(mixed, 997L) / 100_000.0;
    }

    private static String planId(
            ShelterFrame frame,
            ShelterScale scale,
            Candidate candidate,
            String doorItem,
            String lightItem
    ) {
        long hash = 0xcbf29ce484222325L;
        hash = hash(hash, frame.dimension().id());
        hash = hash(hash, scale.name());
        hash = hash(hash, candidate.origin().x());
        hash = hash(hash, candidate.origin().y());
        hash = hash(hash, candidate.origin().z());
        hash = hash(hash, candidate.size().width());
        hash = hash(hash, candidate.size().depth());
        hash = hash(hash, candidate.facing().name());
        hash = hash(hash, candidate.material().itemId());
        hash = hash(hash, doorItem);
        hash = hash(hash, lightItem);
        return String.format(Locale.ROOT, "%016x", hash);
    }

    private static long hash(long current, String value) {
        long result = current;
        for (int index = 0; index < value.length(); index++) {
            result ^= value.charAt(index);
            result *= 0x100000001b3L;
        }
        return result;
    }

    private static long hash(long current, int value) {
        long result = current;
        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
            result ^= value >>> shift & 0xff;
            result *= 0x100000001b3L;
        }
        return result;
    }

    private record InteriorSize(int width, int depth) {
    }

    private record PlanningConstraints(
            String reusableItemId,
            Set<GridPos> reusableStructuralBlocks,
            Set<GridPos> forbiddenShellBlocks
    ) {
        private PlanningConstraints {
            reusableItemId = Objects.requireNonNull(
                    reusableItemId,
                    "reusableItemId"
            );
            reusableStructuralBlocks = Set.copyOf(
                    Objects.requireNonNull(
                            reusableStructuralBlocks,
                            "reusableStructuralBlocks"
                    )
            );
            forbiddenShellBlocks = Set.copyOf(
                    Objects.requireNonNull(
                            forbiddenShellBlocks,
                            "forbiddenShellBlocks"
                    )
            );
        }

        private static PlanningConstraints none() {
            return new PlanningConstraints(
                    "",
                    Set.of(),
                    Set.of()
            );
        }

        private int maximumReusableFor(
                final MaterialChoice material
        ) {
            return reusableItemId.equals(material.itemId())
                    ? reusableStructuralBlocks.size()
                    : 0;
        }

        private Set<GridPos> reusableFor(
                final MaterialChoice material
        ) {
            return reusableItemId.equals(material.itemId())
                    ? reusableStructuralBlocks
                    : Set.of();
        }
    }

    private record MaterialChoice(
            String itemId,
            int count
    ) {
    }

    private record Candidate(
            GridPos origin,
            InteriorSize size,
            ShelterFacing facing,
            GridPos door,
            MaterialChoice material,
            double score
    ) {
    }

    private record RelocationCandidate(
            GridPos stand,
            double score
    ) {
    }

    private record SiteCheck(
            boolean valid,
            boolean unknown,
            double danger
    ) {
        private static SiteCheck valid(double danger) {
            return new SiteCheck(true, false, danger);
        }

        private static SiteCheck unobserved() {
            return new SiteCheck(false, true, 0.0);
        }

        private static SiteCheck blocked() {
            return new SiteCheck(false, false, 0.0);
        }
    }

    private static final class SearchEvidence {
        private boolean unknown;

        void record(SiteCheck check) {
            unknown |= check.unknown();
        }

        boolean unknown() {
            return unknown;
        }
    }
}
