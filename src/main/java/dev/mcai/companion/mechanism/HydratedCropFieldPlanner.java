package dev.mcai.companion.mechanism;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.TopSupportAffordance;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Constraint solver for a daylight, water-hydrated vanilla crop field.
 *
 * <p>The causal rule is central water hydrating farmland no more than four
 * horizontal blocks away. The solver chooses size, translation and service
 * side from current fair evidence and emits a construction DAG. It contains
 * no test-world coordinates or saved block template.</p>
 */
public final class HydratedCropFieldPlanner {
    private static final int MAXIMUM_WIDTH = 9;
    private static final int MAXIMUM_DEPTH = 9;
    private static final int MINIMUM_SIDE = 3;
    private static final double MAXIMUM_SITE_DANGER = 0.20;
    private static final double MAXIMUM_ANCHOR_DISTANCE = 10.0;
    private static final Set<String> TILLABLE = Set.of(
            "minecraft:dirt",
            "minecraft:grass_block",
            "minecraft:farmland"
    );
    private static final List<String> HOE_PREFERENCE = List.of(
            "minecraft:netherite_hoe",
            "minecraft:diamond_hoe",
            "minecraft:iron_hoe",
            "minecraft:stone_hoe",
            "minecraft:wooden_hoe",
            "minecraft:golden_hoe"
    );
    private static final MechanismSpec SPEC = createSpec();

    public MechanismSpec spec() {
        return SPEC;
    }

    public MechanismPlanningResult plan(
            final MechanismSiteFrame frame,
            final HydratedCropFieldRequest request
    ) {
        Objects.requireNonNull(frame, "frame");
        return plan(SiteEvidence.from(frame), request);
    }

    /**
     * Plans from a bounded multi-view survey without pretending that its
     * older observations came from the newest semantic frame.
     */
    public MechanismPlanningResult plan(
            final MechanismSiteSurvey survey,
            final HydratedCropFieldRequest request
    ) {
        Objects.requireNonNull(survey, "survey");
        return plan(SiteEvidence.from(survey), request);
    }

    private MechanismPlanningResult plan(
            final SiteEvidence frame,
            final HydratedCropFieldRequest request
    ) {
        Objects.requireNonNull(request, "request");
        if (frame.dimension().equals(DimensionRef.NETHER)) {
            return MechanismPlanningResult.failed(
                    "crop_field.water_invalid_dimension"
            );
        }
        final Map<String, Integer> inventory = inventory(frame.inventory());
        if (inventory.getOrDefault("minecraft:water_bucket", 0) < 1) {
            return MechanismPlanningResult.failed(
                    "crop_field.missing_water_bucket"
            );
        }
        final Optional<String> hoe = HOE_PREFERENCE.stream()
                .filter(item -> inventory.getOrDefault(item, 0) > 0)
                .findFirst();
        if (hoe.isEmpty()) {
            return MechanismPlanningResult.failed(
                    "crop_field.missing_hoe"
            );
        }
        final int plantingItems = inventory.getOrDefault(
                request.crop().plantingItemId(),
                0
        );
        if (plantingItems < request.minimumPlots()) {
            return MechanismPlanningResult.failed(
                    "crop_field.insufficient_planting_items"
            );
        }

        final SurfaceEvidence surfaces = surfaces(
                frame.visibleBlockFaces()
        );
        if (surfaces.blocks().isEmpty()) {
            return MechanismPlanningResult.failed(
                    "crop_field.insufficient_observation"
            );
        }
        Candidate best = null;
        boolean unknownEvidence = false;
        for (Dimensions dimensions : dimensions(
                request.minimumPlots(),
                plantingItems
        )) {
            for (GridPos anchor : candidateAnchors(frame, surfaces)) {
                for (MechanismPlan.Orientation facing
                        : MechanismPlan.Orientation.values()) {
                    final Candidate candidate;
                    try {
                        candidate = candidate(
                                frame,
                                request,
                                dimensions,
                                anchor,
                                facing,
                                surfaces
                        );
                    } catch (ArithmeticException coordinateOverflow) {
                        continue;
                    }
                    if (candidate.state() == SiteState.UNKNOWN) {
                        unknownEvidence = true;
                        continue;
                    }
                    if (candidate.state() != SiteState.VALID) {
                        continue;
                    }
                    if (best == null || candidate.score() < best.score()) {
                        best = candidate;
                    }
                }
            }
        }
        if (best == null) {
            return MechanismPlanningResult.failed(
                    unknownEvidence
                            ? "crop_field.insufficient_observation"
                            : "crop_field.no_safe_site"
            );
        }
        return MechanismPlanningResult.planned(generate(
                frame,
                request,
                best,
                hoe.orElseThrow()
        ));
    }

    private static Candidate candidate(
            final SiteEvidence frame,
            final HydratedCropFieldRequest request,
            final Dimensions dimensions,
            final GridPos anchor,
            final MechanismPlan.Orientation facing,
            final SurfaceEvidence surfaces
    ) {
        final GridPos origin = anchor.offset(
                -dimensions.width() / 2,
                0,
                -dimensions.depth() / 2
        );
        final LinkedHashSet<GridPos> footprint = new LinkedHashSet<>();
        SiteState state = SiteState.VALID;
        double danger = 0.0;
        for (int x = 0; x < dimensions.width(); x++) {
            for (int z = 0; z < dimensions.depth(); z++) {
                final GridPos ground = origin.offset(x, 0, z);
                footprint.add(ground);
                final CellCheck check = cropCell(frame, surfaces, ground);
                state = state.merge(check.state());
                danger = Math.max(danger, check.danger());
            }
        }
        final List<GridPos> aisle = serviceAisle(
                origin,
                dimensions,
                facing
        );
        for (GridPos ground : aisle) {
            final CellCheck check = serviceCell(frame, ground);
            state = state.merge(check.state());
            danger = Math.max(danger, check.danger());
        }
        if (request.requireSingleChunk()
                && !singleChunk(footprint, aisle)) {
            state = state.merge(SiteState.BLOCKED);
        }
        final double score = frame.feet().euclideanDistance(anchor.above())
                * 100.0
                + (dimensions.productionCells()
                    - request.minimumPlots()) * 8.0
                + Math.abs(dimensions.width() - dimensions.depth()) * 3.0
                + danger * 50.0
                + facingPenalty(frame, facing)
                + stableTieBreak(facing, dimensions);
        return new Candidate(
                state,
                anchor,
                origin,
                facing,
                dimensions,
                List.copyOf(footprint),
                aisle,
                score
        );
    }

    private static CellCheck cropCell(
            final SiteEvidence frame,
            final SurfaceEvidence surfaces,
            final GridPos ground
    ) {
        if (surfaces.ambiguous().contains(ground)
                || !surfaces.blocks().containsKey(ground)) {
            return CellCheck.unknown();
        }
        final SurfaceCell surface = surfaces.blocks().get(ground);
        if (!TILLABLE.contains(surface.blockTypeId())) {
            return CellCheck.blocked();
        }
        if (!frame.skyVisible()) {
            if (surface.adjacentLightLevel() < 0) {
                return CellCheck.unknown();
            }
            if (surface.adjacentLightLevel() < 9) {
                return CellCheck.blocked();
            }
        }
        final Optional<ObservedVoxel> support = frame.voxelAt(ground);
        final Optional<ObservedVoxel> cropSpace =
                frame.voxelAt(ground.above());
        final Optional<ObservedVoxel> headSpace =
                frame.voxelAt(ground.above(2));
        if (support.isEmpty()
                || cropSpace.isEmpty()
                || headSpace.isEmpty()) {
            return CellCheck.unknown();
        }
        final double danger = Math.max(
                support.orElseThrow().effectiveDanger(),
                Math.max(
                        cropSpace.orElseThrow().effectiveDanger(),
                        headSpace.orElseThrow().effectiveDanger()
                )
        );
        if (!support.orElseThrow().kind().supportsWeight()
                || !frame.isFresh(support.orElseThrow())
                || !frame.hasTraversalClearance(
                        cropSpace.orElseThrow()
                )
                || !frame.hasTraversalClearance(
                        headSpace.orElseThrow()
                )
                || danger > MAXIMUM_SITE_DANGER) {
            return CellCheck.blocked();
        }
        return CellCheck.valid(danger);
    }

    private static CellCheck serviceCell(
            final SiteEvidence frame,
            final GridPos ground
    ) {
        final Optional<ObservedVoxel> support = frame.voxelAt(ground);
        final Optional<ObservedVoxel> feet =
                frame.voxelAt(ground.above());
        final Optional<ObservedVoxel> head =
                frame.voxelAt(ground.above(2));
        if (support.isEmpty() || feet.isEmpty() || head.isEmpty()) {
            return CellCheck.unknown();
        }
        final double danger = Math.max(
                support.orElseThrow().effectiveDanger(),
                Math.max(
                        feet.orElseThrow().effectiveDanger(),
                        head.orElseThrow().effectiveDanger()
                )
        );
        if (!frame.isStandingSupport(support.orElseThrow())
                || !frame.hasTraversalClearance(feet.orElseThrow())
                || !frame.hasTraversalClearance(head.orElseThrow())
                || danger > MAXIMUM_SITE_DANGER) {
            return CellCheck.blocked();
        }
        return CellCheck.valid(danger);
    }

    private static List<GridPos> candidateAnchors(
            final SiteEvidence frame,
            final SurfaceEvidence surfaces
    ) {
        return surfaces.blocks().entrySet().stream()
                .filter(entry -> TILLABLE.contains(
                        entry.getValue().blockTypeId()
                ))
                .map(Map.Entry::getKey)
                .filter(position ->
                        position.y() == frame.feet().y() - 1
                                && frame.feet().euclideanDistance(
                                    position.above()
                                ) <= MAXIMUM_ANCHOR_DISTANCE
                )
                .sorted(Comparator
                        .comparingDouble((GridPos position) ->
                            frame.feet().euclideanDistance(position.above())
                        )
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private static List<Dimensions> dimensions(
            final int minimumPlots,
            final int plantingItems
    ) {
        final List<Dimensions> result = new ArrayList<>();
        for (int width = MINIMUM_SIDE; width <= MAXIMUM_WIDTH; width++) {
            for (int depth = MINIMUM_SIDE; depth <= MAXIMUM_DEPTH; depth++) {
                final Dimensions candidate = new Dimensions(width, depth);
                if (candidate.productionCells() >= minimumPlots
                        && candidate.productionCells() <= plantingItems) {
                    result.add(candidate);
                }
            }
        }
        result.sort(Comparator
                .comparingInt(Dimensions::productionCells)
                .thenComparingInt(value ->
                        Math.abs(value.width() - value.depth())
                )
                .thenComparingInt(Dimensions::width)
                .thenComparingInt(Dimensions::depth));
        return result;
    }

    private static List<GridPos> serviceAisle(
            final GridPos origin,
            final Dimensions dimensions,
            final MechanismPlan.Orientation facing
    ) {
        final List<GridPos> result = new ArrayList<>();
        switch (facing) {
            case NORTH -> {
                for (int x = 0; x < dimensions.width(); x++) {
                    result.add(origin.offset(x, 0, -1));
                }
            }
            case SOUTH -> {
                for (int x = 0; x < dimensions.width(); x++) {
                    result.add(origin.offset(x, 0, dimensions.depth()));
                }
            }
            case WEST -> {
                for (int z = 0; z < dimensions.depth(); z++) {
                    result.add(origin.offset(-1, 0, z));
                }
            }
            case EAST -> {
                for (int z = 0; z < dimensions.depth(); z++) {
                    result.add(origin.offset(dimensions.width(), 0, z));
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean singleChunk(
            final Set<GridPos> footprint,
            final List<GridPos> aisle
    ) {
        final Set<Long> chunks = new HashSet<>();
        footprint.forEach(position -> chunks.add(chunkKey(position)));
        aisle.forEach(position -> chunks.add(chunkKey(position)));
        return chunks.size() == 1;
    }

    private static long chunkKey(final GridPos position) {
        final long chunkX = Math.floorDiv(position.x(), 16);
        final long chunkZ = Math.floorDiv(position.z(), 16);
        return chunkX << 32 ^ chunkZ & 0xffffffffL;
    }

    private static double facingPenalty(
            final SiteEvidence frame,
            final MechanismPlan.Orientation facing
    ) {
        final double x = switch (facing) {
            case EAST -> 1.0;
            case WEST -> -1.0;
            default -> 0.0;
        };
        final double z = switch (facing) {
            case SOUTH -> 1.0;
            case NORTH -> -1.0;
            default -> 0.0;
        };
        final double dot = x * frame.lookDirection().x()
                + z * frame.lookDirection().z();
        return (1.0 - dot) * 2.0;
    }

    private static double stableTieBreak(
            final MechanismPlan.Orientation facing,
            final Dimensions dimensions
    ) {
        final long mixed = 7L * facing.ordinal()
                + dimensions.width() * 5L
                + dimensions.depth();
        return Math.floorMod(mixed, 997L) / 100_000.0;
    }

    private static MechanismPlan generate(
            final SiteEvidence frame,
            final HydratedCropFieldRequest request,
            final Candidate candidate,
            final String hoe
    ) {
        final List<MechanismConstructionStep> steps = new ArrayList<>();
        steps.add(new MechanismConstructionStep(
                0,
                MechanismConstructionStep.Phase.PREPARE,
                MechanismConstructionStep.Action.EXCAVATE,
                "water_source",
                candidate.anchor(),
                "",
                Set.of(),
                "water_cell_excavated"
        ));
        steps.add(new MechanismConstructionStep(
                1,
                MechanismConstructionStep.Phase.INSTALL,
                MechanismConstructionStep.Action.PLACE_FLUID,
                "water_source",
                candidate.anchor(),
                "minecraft:water_bucket",
                Set.of(0),
                "water_source_visible"
        ));
        final List<Integer> tillSteps = new ArrayList<>();
        final List<Integer> plantSteps = new ArrayList<>();
        for (GridPos ground : candidate.footprint()) {
            if (ground.equals(candidate.anchor())) {
                continue;
            }
            final int tillIndex = steps.size();
            steps.add(new MechanismConstructionStep(
                    tillIndex,
                    MechanismConstructionStep.Phase.INSTALL,
                    MechanismConstructionStep.Action.TILL,
                    "hydrated_soil",
                    ground,
                    hoe,
                    Set.of(1),
                    "farmland_visible"
            ));
            tillSteps.add(tillIndex);
            final int plantIndex = steps.size();
            steps.add(new MechanismConstructionStep(
                    plantIndex,
                    MechanismConstructionStep.Phase.INSTALL,
                    MechanismConstructionStep.Action.PLANT,
                    "crop",
                    ground.above(),
                    request.crop().plantingItemId(),
                    Set.of(tillIndex, 1),
                    "crop_block_visible"
            ));
            plantSteps.add(plantIndex);
        }
        final int waterProbe = steps.size();
        steps.add(new MechanismConstructionStep(
                waterProbe,
                MechanismConstructionStep.Phase.COMMISSION,
                MechanismConstructionStep.Action.PROBE,
                "water_source",
                candidate.anchor(),
                "",
                Set.of(1),
                "water_source_retained"
        ));
        final Set<Integer> hydrationDependencies = new LinkedHashSet<>();
        hydrationDependencies.add(waterProbe);
        hydrationDependencies.addAll(tillSteps);
        final int hydrationProbe = steps.size();
        steps.add(new MechanismConstructionStep(
                hydrationProbe,
                MechanismConstructionStep.Phase.COMMISSION,
                MechanismConstructionStep.Action.PROBE,
                "hydrated_soil",
                candidate.anchor(),
                "",
                hydrationDependencies,
                "farmland_hydrated"
        ));
        final int cropProbe = steps.size();
        steps.add(new MechanismConstructionStep(
                cropProbe,
                MechanismConstructionStep.Phase.COMMISSION,
                MechanismConstructionStep.Action.PROBE,
                "crop",
                candidate.anchor().above(),
                "",
                new LinkedHashSet<>(plantSteps),
                "crop_count_matches"
        ));
        steps.add(new MechanismConstructionStep(
                steps.size(),
                MechanismConstructionStep.Phase.COMMISSION,
                MechanismConstructionStep.Action.PROBE,
                "service_aisle",
                candidate.serviceAisle().get(
                        candidate.serviceAisle().size() / 2
                ).above(),
                "",
                Set.of(hydrationProbe, cropProbe),
                "production_rate_measured"
        ));

        final int productionCells = candidate.dimensions()
                .productionCells();
        final MechanismSpec.ExpectedRate rate =
                new MechanismSpec.ExpectedRate(
                        productionCells,
                        "harvest_items_per_mature_cycle",
                        24_000,
                        "At least one primary crop item per mature planted "
                            + "cell; random bonus drops are excluded."
                );
        return new MechanismPlan(
                1,
                planId(frame, request, candidate, hoe),
                SPEC.id(),
                SPEC.purpose(),
                frame.dimension(),
                frame.sourceRevision(),
                candidate.anchor(),
                candidate.facing(),
                candidate.dimensions().width(),
                candidate.dimensions().depth(),
                productionCells,
                Map.of(
                        "water", "minecraft:water_bucket",
                        "tool", hoe,
                        "crop", request.crop().plantingItemId()
                ),
                rate,
                steps
        );
    }

    private static String planId(
            final SiteEvidence frame,
            final HydratedCropFieldRequest request,
            final Candidate candidate,
            final String hoe
    ) {
        long hash = 0xcbf29ce484222325L;
        hash = hash(hash, SPEC.id());
        hash = hash(hash, frame.dimension().id());
        hash = hash(hash, request.crop().name());
        hash = hash(hash, candidate.anchor().x());
        hash = hash(hash, candidate.anchor().y());
        hash = hash(hash, candidate.anchor().z());
        hash = hash(hash, candidate.dimensions().width());
        hash = hash(hash, candidate.dimensions().depth());
        hash = hash(hash, candidate.facing().name());
        hash = hash(hash, hoe);
        return String.format(Locale.ROOT, "%016x", hash);
    }

    private static long hash(long current, final String value) {
        long result = current;
        for (int index = 0; index < value.length(); index++) {
            result ^= value.charAt(index);
            result *= 0x100000001b3L;
        }
        return result;
    }

    private static long hash(long current, final int value) {
        long result = current;
        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
            result ^= value >>> shift & 0xff;
            result *= 0x100000001b3L;
        }
        return result;
    }

    private static Map<String, Integer> inventory(
            final List<InventoryItemSummary> items
    ) {
        final Map<String, Integer> result = new HashMap<>();
        items.forEach(item -> result.merge(
                item.itemId(),
                item.count(),
                Math::addExact
        ));
        return Map.copyOf(result);
    }

    private static SurfaceEvidence surfaces(
            final List<VisibleBlockFace> faces
    ) {
        final Map<GridPos, SurfaceCell> blocks = new HashMap<>();
        final Set<GridPos> ambiguous = new HashSet<>();
        for (VisibleBlockFace face : faces) {
            if (!isUpFace(face.face())) {
                continue;
            }
            final BlockCoordinate block = face.block();
            final GridPos position = new GridPos(
                    block.x(),
                    block.y(),
                    block.z()
            );
            final SurfaceCell cell = new SurfaceCell(
                    face.blockTypeId(),
                    face.adjacentLightLevel()
            );
            final SurfaceCell previous = blocks.putIfAbsent(
                    position,
                    cell
            );
            if (previous != null && !previous.equals(cell)) {
                ambiguous.add(position);
            }
        }
        return new SurfaceEvidence(Map.copyOf(blocks), Set.copyOf(ambiguous));
    }

    private static boolean isUpFace(final String face) {
        return "up".equals(face) || "minecraft:up".equals(face);
    }

    private static MechanismSpec createSpec() {
        final List<MechanismSpec.RepairStrategy> repairs = List.of(
                new MechanismSpec.RepairStrategy(
                        "restore_water",
                        List.of("stop_work", "replace_water", "reprobe_hydration")
                ),
                new MechanismSpec.RepairStrategy(
                        "repair_soil",
                        List.of("clear_obstruction", "retill_soil", "replant_crop")
                ),
                new MechanismSpec.RepairStrategy(
                        "restore_light",
                        List.of("pause_rate_test", "add_light", "repeat_rate_test")
                )
        );
        return new MechanismSpec(
                1,
                "mcai_companion:hydrated_crop_field",
                "food_crop_production",
                List.of(
                        new MechanismSpec.Rule(
                                "hydration_radius",
                                "Every farmland cell is within four horizontal blocks of the retained water source."
                        ),
                        new MechanismSpec.Rule(
                                "one_crop_per_soil",
                                "Every production cell has exactly one tillable support and one selected crop."
                        ),
                        new MechanismSpec.Rule(
                                "observable_commissioning",
                                "Water, hydration, crop count, and output rate require post-construction observations."
                        )
                ),
                new MechanismSpec.ComponentGraph(
                        List.of(
                                new MechanismSpec.Component(
                                        "water", "water_source", 1, 1
                                ),
                                new MechanismSpec.Component(
                                        "soil", "hydrated_soil", 8, 80
                                ),
                                new MechanismSpec.Component(
                                        "crop", "crop", 8, 80
                                ),
                                new MechanismSpec.Component(
                                        "aisle", "service_aisle", 3, 9
                                )
                        ),
                        List.of(
                                new MechanismSpec.ComponentEdge(
                                        "water", "soil", "hydrates"
                                ),
                                new MechanismSpec.ComponentEdge(
                                        "soil", "crop", "supports"
                                ),
                                new MechanismSpec.ComponentEdge(
                                        "aisle", "crop", "services"
                                )
                        )
                ),
                List.of(
                        new MechanismSpec.ResourceFlow(
                                "minecraft:water_bucket",
                                1,
                                false,
                                "water"
                        ),
                        new MechanismSpec.ResourceFlow(
                                "#minecraft:hoes",
                                1,
                                false,
                                "tool"
                        ),
                        new MechanismSpec.ResourceFlow(
                                "#mcai_companion:crop_planting_items",
                                8,
                                true,
                                "crop"
                        )
                ),
                List.of(new MechanismSpec.ResourceFlow(
                        "#mcai_companion:food_crops",
                        8,
                        false,
                        "harvest"
                )),
                List.of(
                        new MechanismSpec.MaterialSubstitution(
                                "crop",
                                List.of(
                                        "minecraft:wheat_seeds",
                                        "minecraft:carrot",
                                        "minecraft:potato",
                                        "minecraft:beetroot_seeds"
                                )
                        ),
                        new MechanismSpec.MaterialSubstitution(
                                "tool",
                                HOE_PREFERENCE
                        )
                ),
                List.of(
                        new MechanismSpec.Rule(
                                "observed_tillable_surface",
                                "Every selected soil block and two-block work volume is present in fair first-person evidence."
                        ),
                        new MechanismSpec.Rule(
                                "daylight",
                                "The site has a fairly observed unobstructed sky ray for crop light."
                        )
                ),
                List.of(
                        new MechanismSpec.Rule(
                                "water_dimension",
                                "The mechanism is never selected in the Nether dimension."
                        ),
                        new MechanismSpec.Rule(
                                "single_chunk_option",
                                "When requested, production cells and the service aisle occupy one chunk."
                        )
                ),
                List.of(
                        new MechanismSpec.SafetyClearance(
                                "service_side", 1, 2,
                                "A player-sized observed aisle remains clear for commissioning and repairs."
                        ),
                        new MechanismSpec.SafetyClearance(
                                "work_volume", 0, 2,
                                "No known obstruction occupies the crop or player head volume."
                        )
                ),
                new MechanismSpec.ExpectedRate(
                        1.0,
                        "harvest_item_per_mature_cell",
                        24_000,
                        "Primary output only; random bonus drops and growth acceleration are excluded."
                ),
                List.of(
                        new MechanismSpec.CommissioningProbe(
                                "water_retained", "water_source_retained", 20
                        ),
                        new MechanismSpec.CommissioningProbe(
                                "soil_hydrated", "farmland_hydrated", 160
                        ),
                        new MechanismSpec.CommissioningProbe(
                                "crop_count", "crop_count_matches", 20
                        ),
                        new MechanismSpec.CommissioningProbe(
                                "production_rate", "production_rate_measured", 24_000
                        )
                ),
                List.of(
                        new MechanismSpec.KnownFailureMode(
                                "water_removed", "water_source_missing", "restore_water"
                        ),
                        new MechanismSpec.KnownFailureMode(
                                "soil_trampled", "farmland_reverted", "repair_soil"
                        ),
                        new MechanismSpec.KnownFailureMode(
                                "growth_stalled", "production_rate_low", "restore_light"
                        )
                ),
                repairs
        );
    }

    private record SiteEvidence(
            DimensionRef dimension,
            long sourceRevision,
            GridPos feet,
            PerceptionVec3 lookDirection,
            List<InventoryItemSummary> inventory,
            List<VisibleBlockFace> visibleBlockFaces,
            boolean skyVisible,
            Function<GridPos, Optional<ObservedVoxel>> voxelLookup,
            Predicate<ObservedVoxel> freshness
    ) {
        private SiteEvidence {
            Objects.requireNonNull(dimension, "dimension");
            if (sourceRevision < 0) {
                throw new IllegalArgumentException(
                        "Site evidence revision is invalid"
                );
            }
            Objects.requireNonNull(feet, "feet");
            Objects.requireNonNull(lookDirection, "lookDirection");
            inventory = List.copyOf(Objects.requireNonNull(
                    inventory,
                    "inventory"
            ));
            visibleBlockFaces = List.copyOf(Objects.requireNonNull(
                    visibleBlockFaces,
                    "visibleBlockFaces"
            ));
            Objects.requireNonNull(voxelLookup, "voxelLookup");
            Objects.requireNonNull(freshness, "freshness");
        }

        private static SiteEvidence from(
                final MechanismSiteFrame frame
        ) {
            final long revision = frame.navigation().revision();
            return new SiteEvidence(
                    frame.dimension(),
                    frame.sourceRevision(),
                    frame.feet(),
                    frame.lookDirection(),
                    frame.inventory(),
                    frame.visibleBlockFaces(),
                    frame.skyVisible(),
                    frame.navigation()::voxelAt,
                    voxel -> voxel.observationRevision() == revision
            );
        }

        private static SiteEvidence from(
                final MechanismSiteSurvey survey
        ) {
            return new SiteEvidence(
                    survey.dimension(),
                    survey.sourceRevision(),
                    survey.feet(),
                    survey.lookDirection(),
                    survey.inventory(),
                    survey.visibleBlockFaces(),
                    survey.skyVisible(),
                    survey::voxelAt,
                    voxel -> survey.voxels().containsKey(
                            voxel.position()
                    ) && survey.voxels().get(voxel.position())
                            .voxel().equals(voxel)
            );
        }

        private Optional<ObservedVoxel> voxelAt(
                final GridPos position
        ) {
            return voxelLookup.apply(position);
        }

        private boolean isFresh(final ObservedVoxel voxel) {
            return freshness.test(voxel);
        }

        private boolean hasTraversalClearance(
                final ObservedVoxel voxel
        ) {
            return isFresh(voxel)
                    && NavigationEvidence.hasTraversalClearance(voxel);
        }

        private boolean isStandingSupport(final ObservedVoxel voxel) {
            return isFresh(voxel)
                    && voxel.kind().supportsWeight()
                    && voxel.topSupportAffordance()
                            == TopSupportAffordance.STURDY_FULL_TOP
                    && (voxel.occupancyEvidence()
                            == OccupancyEvidence.SURFACE_HIT
                        || voxel.occupancyEvidence()
                            == OccupancyEvidence.BODY_CONTACT);
        }
    }

    private record Dimensions(int width, int depth) {
        private int productionCells() {
            return width * depth - 1;
        }
    }

    private record Candidate(
            SiteState state,
            GridPos anchor,
            GridPos origin,
            MechanismPlan.Orientation facing,
            Dimensions dimensions,
            List<GridPos> footprint,
            List<GridPos> serviceAisle,
            double score
    ) {
    }

    private record SurfaceEvidence(
            Map<GridPos, SurfaceCell> blocks,
            Set<GridPos> ambiguous
    ) {
    }

    private record SurfaceCell(
            String blockTypeId,
            int adjacentLightLevel
    ) {
        private SurfaceCell {
            Objects.requireNonNull(blockTypeId, "blockTypeId");
            if (adjacentLightLevel < -1 || adjacentLightLevel > 15) {
                throw new IllegalArgumentException(
                        "Invalid adjacent light level"
                );
            }
        }
    }

    private record CellCheck(SiteState state, double danger) {
        private static CellCheck valid(final double danger) {
            return new CellCheck(SiteState.VALID, danger);
        }

        private static CellCheck unknown() {
            return new CellCheck(SiteState.UNKNOWN, 0.0);
        }

        private static CellCheck blocked() {
            return new CellCheck(SiteState.BLOCKED, 1.0);
        }
    }

    private enum SiteState {
        VALID,
        UNKNOWN,
        BLOCKED;

        private SiteState merge(final SiteState other) {
            if (this == BLOCKED || other == BLOCKED) {
                return BLOCKED;
            }
            if (this == UNKNOWN || other == UNKNOWN) {
                return UNKNOWN;
            }
            return VALID;
        }
    }
}
