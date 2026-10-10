package dev.mcai.companion.skills.portal;

import dev.mcai.companion.MinecraftAiCompanion;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.EquipmentTarget;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds one complete vanilla 4x5 obsidian frame from owned items, then
 * lights it through an ordinary use packet. The frame orientation may be
 * selected from the fair local observation.
 */
public final class BuildAndLightNetherPortalSkill
        implements Skill<BuildNetherPortalParameters> {
    public static final String NAME =
            "build_and_light_nether_portal";

    private static final String OBSIDIAN = "minecraft:obsidian";
    private static final String FLINT_AND_STEEL =
            "minecraft:flint_and_steel";
    private static final String NETHER_PORTAL =
            "minecraft:nether_portal";
    private static final int REQUIRED_OBSIDIAN = 14;
    private static final double NORMAL_MAXIMUM_DANGER = 0.12;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.04;
    private static final double NORMAL_MINIMUM_HEALTH_RATIO = 0.65;
    private static final double HARDCORE_MINIMUM_HEALTH_RATIO = 0.90;
    private static final double MAXIMUM_INTERACTION_DISTANCE = 4.75;
    private static final double ALIGNMENT_DEGREES = 2.75;
    private static final int MAXIMUM_FACE_SEARCH_TICKS = 80;
    private static final int MAXIMUM_VERIFY_TICKS = 60;
    private static final int MAXIMUM_LIGHT_VERIFY_TICKS = 80;
    private static final int MAXIMUM_TOTAL_TICKS = 2_400;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource frames;
    private final Optional<InteractionSkillFrameSource> interactionFrames;
    private final InventorySkillActuator inventory;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long phaseStartedAtTick = -1;
    private long phaseObservationRevision = -1;
    private long lastObservationRevision = -1;
    private PortalBuildAxis resolvedAxis;
    private GridPos retainedPlanAnchor;
    private PortalBuildAxis retainedPlanAxis;
    private boolean backingWallKnown;
    private List<GridPos> frameBlocks = List.of();
    private Set<GridPos> completedBlocks = Set.of();
    private int blockIndex;
    private int faceSearchTicks;
    private int itemCountBefore;
    private int flintDamageBefore;
    private int flintCountBefore;
    private BlockInteractionTarget interactionTarget;

    public BuildAndLightNetherPortalSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource frames,
            final InventorySkillActuator inventory
    ) {
        this(
                expectedPlayerId,
                core,
                frames,
                Optional.empty(),
                inventory
        );
    }

    public BuildAndLightNetherPortalSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource frames,
            final InteractionSkillFrameSource interactionFrames,
            final InventorySkillActuator inventory
    ) {
        this(
                expectedPlayerId,
                core,
                frames,
                Optional.of(Objects.requireNonNull(
                        interactionFrames,
                        "interactionFrames"
                )),
                inventory
        );
    }

    private BuildAndLightNetherPortalSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource frames,
            final Optional<InteractionSkillFrameSource> interactionFrames,
            final InventorySkillActuator inventory
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.core = Objects.requireNonNull(core, "core");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.interactionFrames = Objects.requireNonNull(
                interactionFrames,
                "interactionFrames"
        );
        this.inventory = Objects.requireNonNull(
                inventory,
                "inventory"
        );
    }

    @Override
    public SkillParameterParser<BuildNetherPortalParameters> parameters() {
        return PortalBuildSkillParameters::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final BuildNetherPortalParameters parameters
    ) {
        final FrameValidation validation = validateFrame(parameters);
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        final CoreSkillFrame frame = validation.frame().orElseThrow();
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return unsafe;
        }
        if (!frame.onGround() || frame.inWater()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stable_ground_required"
            ));
        }
        if (!portalDimension(parameters.dimension())) {
            return Optional.of(SkillFailure.of(
                    NAME + ".unsupported_dimension"
            ));
        }
        if (inventoryCount(frame, FLINT_AND_STEEL) < 1) {
            return Optional.of(SkillFailure.of(
                    NAME + ".flint_and_steel_required"
            ));
        }
        final Optional<PortalBuildAxis> axis =
                resolveAxis(parameters, frame);
        if (axis.isEmpty()) {
            MinecraftAiCompanion.LOGGER.warn(
                "Observed portal site rejected: {}",
                siteDiagnostic(
                    parameters.anchor(),
                    parameters.axis() == PortalBuildAxis.AUTO
                        ? PortalBuildAxis.X
                        : parameters.axis(),
                    frame
                )
            );
            return Optional.of(SkillFailure.of(
                    NAME + ".observed_site_unavailable"
            ));
        }
        final int remaining = remainingFrameBlocks(
                parameters.anchor(),
                axis.orElseThrow(),
                frame
        );
        if (inventoryCount(frame, OBSIDIAN) < remaining) {
            return Optional.of(SkillFailure.of(
                    NAME + ".obsidian_required"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final BuildNetherPortalParameters parameters
    ) {
        final CoreSkillFrame frame = validateFrame(parameters)
                .frame()
                .orElseThrow(() -> new IllegalStateException(
                        "Portal body changed before start"
                ));
        resolvedAxis = resolveAxis(parameters, frame)
                .orElseThrow(() -> new IllegalStateException(
                    "Portal site changed before start"
                ));
        final boolean resumingSamePlan =
                parameters.anchor().equals(retainedPlanAnchor)
                    && resolvedAxis == retainedPlanAxis;
        final Set<GridPos> retainedCompleted =
                resumingSamePlan
                        ? new HashSet<>(completedBlocks)
                        : new HashSet<>();
        frameBlocks = framePlan(
                parameters.anchor(),
                resolvedAxis
        );
        backingWallKnown = backingWallObserved(
                parameters.anchor(),
                resolvedAxis,
                frame
        );
        completedBlocks = retainedCompleted;
        for (GridPos block : frameBlocks) {
            if (visibleBlockType(frame, block, OBSIDIAN)) {
                completedBlocks.add(block);
            }
        }
        retainedPlanAnchor = parameters.anchor();
        retainedPlanAxis = resolvedAxis;
        blockIndex = 0;
        advancePastCompletedBlocks();
        phase = Phase.PLACING;
        failure = null;
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        phaseObservationRevision = frame.observationRevision();
        lastObservationRevision = -1;
        faceSearchTicks = 0;
        interactionTarget = null;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final BuildNetherPortalParameters parameters
    ) {
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context, parameters);
        } catch (RuntimeException exception) {
            return fail(NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final BuildNetherPortalParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                            + "\"x\":%d,\"y\":%d,\"z\":%d,"
                            + "\"axis\":\"%s\",\"placed\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.x(),
                        parameters.y(),
                        parameters.z(),
                        resolvedAxis == null
                                ? parameters.axis().name()
                                : resolvedAxis.name(),
                        blockIndex
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final BuildNetherPortalParameters parameters
    ) {
        quiesce();
        phase = Phase.CANCELLED;
        interactionTarget = null;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final BuildNetherPortalParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(
                    SkillFailure.of(NAME + ".invalid_state")
            );
        };
    }

    private SkillTickResult tickSafely(
            final SkillContext context,
            final BuildNetherPortalParameters parameters
    ) {
        if (context.gameTick() - startedAtTick > MAXIMUM_TOTAL_TICKS) {
            return fail(NAME + ".timed_out");
        }
        final FrameValidation validation = validateFrame(parameters);
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final CoreSkillFrame frame = validation.frame().orElseThrow();
        if (!backingWallKnown) {
            backingWallKnown = backingWallObserved(
                    parameters.anchor(),
                    resolvedAxis,
                    frame
            );
        }
        if (frame.observationRevision() < lastObservationRevision) {
            return fail(NAME + ".stale_observation");
        }
        final boolean freshObservation =
                frame.observationRevision() > lastObservationRevision;
        lastObservationRevision = Math.max(
                lastObservationRevision,
                frame.observationRevision()
        );
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return fail(unsafe.orElseThrow());
        }
        if (!frame.onGround() || frame.inWater()) {
            return fail(NAME + ".stable_ground_lost");
        }
        return switch (phase) {
            case PLACING -> placeNext(
                    context,
                    frame,
                    freshObservation
            );
            case VERIFYING_BLOCK -> verifyBlock(
                    context,
                    frame,
                    freshObservation
            );
            case LIGHTING -> lightPortal(
                    context,
                    parameters,
                    frame,
                    freshObservation
            );
            case VERIFYING_LIGHT -> verifyLight(
                    context,
                    parameters,
                    frame,
                    freshObservation
            );
            default -> SkillTickResult.failed(
                    NAME + ".invalid_state"
            );
        };
    }

    private SkillTickResult placeNext(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        advancePastCompletedBlocks();
        if (blockIndex >= frameBlocks.size()) {
            beginPhase(Phase.LIGHTING, context, frame);
            interactionTarget = null;
            faceSearchTicks = 0;
            return SkillTickResult.running(true, true);
        }
        final GridPos desired = frameBlocks.get(blockIndex);
        if (visibleBlockType(frame, desired, OBSIDIAN)) {
            completedBlocks.add(desired);
            blockIndex++;
            interactionTarget = null;
            faceSearchTicks = 0;
            return SkillTickResult.running(true, true);
        }
        if (!observedAir(frame, desired)) {
            return fail(NAME + ".site_changed");
        }
        if (!OBSIDIAN.equals(frame.mainHand().itemId())) {
            final InventoryOperationResult equipped =
                    inventory.equip(new EquipItemParameters(
                            OBSIDIAN,
                            EquipmentTarget.MAINHAND
                    ));
            if (!equipped.succeeded()) {
                return fail(equipped.failure().orElseThrow());
            }
            interactionTarget = null;
            return SkillTickResult.running(true, true);
        }
        /*
         * The fair sampler's hit point belongs to one exact first-person
         * ray.  Once the body turns, reusing that old point can fail the
         * vanilla crosshair epsilon check even though the same support face
         * is still visible.  Rebind on each fresh sample before dispatching.
         */
        if (freshObservation && interactionTarget != null) {
            interactionTarget = placementTarget(frame, desired)
                    .orElse(null);
        }
        if (interactionTarget == null) {
            interactionTarget = placementTarget(frame, desired)
                    .orElse(null);
        }
        final PerceptionVec3 target = interactionTarget == null
                ? preferredFaceCenter(desired)
                : hit(interactionTarget);
        /*
         * The backing wall is a legitimate observed support, but it can be
         * one block beyond the vanilla interaction range from the initial
         * stance.  A real player simply walks forward while looking at that
         * wall.  Queue the same bounded movement input until the support is
         * within range; no position assignment or hidden-world probe is used.
         */
        if (interactionTarget == null
                && isBackingSupportTarget(desired)
                && distance(frame.eyePosition(), target) > 4.20D) {
            final ActionOutcome lookOutcome = core.look(
                    lookAt(frame.eyePosition(), target)
            );
            final ActionOutcome moveOutcome = core.move(
                    new MovementIntent(
                            1.0,
                            0.0,
                            false,
                            false
                    )
            );
            if (!lookOutcome.accepted() || !moveOutcome.accepted()) {
                return fail(NAME + ".actuator_rejected");
            }
            return SkillTickResult.running(true, true);
        }
        if (!holdAndLook(frame, target)) {
            return fail(NAME + ".actuator_rejected");
        }
        if (interactionTarget == null) {
            faceSearchTicks++;
            if (faceSearchTicks > MAXIMUM_FACE_SEARCH_TICKS) {
                MinecraftAiCompanion.LOGGER.warn(
                    "Portal placement face unavailable: index={} "
                        + "desired={} completed={} visibleFaces={}",
                    blockIndex,
                    desired,
                    completedBlocks,
                    frame.visibleBlockFaces().stream()
                        .map(face -> face.blockTypeId()
                            + "@" + face.block()
                            + ":" + face.face())
                        .toList()
                );
                return fail(NAME + ".visible_face_unavailable");
            }
            return SkillTickResult.running(
                    freshObservation,
                    true
            );
        }
        if (angularError(
                frame.lookDirection(),
                target.subtract(frame.eyePosition())
        ) > ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, true);
        }
        itemCountBefore = inventoryCount(frame, OBSIDIAN);
        final ActionOutcome used =
                core.useMainHandOn(interactionTarget);
        if (!used.accepted()) {
            interactionTarget = null;
            return SkillTickResult.running(true, true);
        }
        beginPhase(Phase.VERIFYING_BLOCK, context, frame);
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyBlock(
            final SkillContext context,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        final GridPos desired = frameBlocks.get(blockIndex);
        if (!holdAndLook(
                frame,
                center(desired)
        )) {
            return fail(NAME + ".actuator_rejected");
        }
        final Optional<ObservedVoxel> observed =
                frame.navigation().voxelAt(desired);
        /*
         * A stationary player can receive a fresh first-person surface ray
         * without the coarser navigation revision changing.  Accept that
         * fair visual proof as an alternative to a fresh voxel entry, while
         * retaining the observation barrier and exact item-consumption check.
         */
        final boolean navigationPlaced = observed.isPresent()
                && observed.orElseThrow().kind().supportsWeight()
                && observed.orElseThrow().observationRevision()
                    > phaseObservationRevision;
        final boolean visiblePlaced =
                frame.observationRevision() > phaseObservationRevision
                    && visibleBlockType(frame, desired, OBSIDIAN);
        final boolean placed = navigationPlaced || visiblePlaced;
        if (placed) {
            final int after = inventoryCount(frame, OBSIDIAN);
            if (itemCountBefore - after != 1) {
                return fail(
                        NAME + ".obsidian_consumption_unverified"
                );
            }
            completedBlocks.add(desired);
            blockIndex++;
            interactionTarget = null;
            faceSearchTicks = 0;
            beginPhase(Phase.PLACING, context, frame);
            return SkillTickResult.running(true, true);
        }
        if (context.gameTick() - phaseStartedAtTick
                > MAXIMUM_VERIFY_TICKS) {
            return fail(NAME + ".placement_unverified");
        }
        return SkillTickResult.running(
                freshObservation,
                false
        );
    }

    private SkillTickResult lightPortal(
            final SkillContext context,
            final BuildNetherPortalParameters parameters,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        if (!FLINT_AND_STEEL.equals(frame.mainHand().itemId())) {
            final InventoryOperationResult equipped =
                    inventory.equip(new EquipItemParameters(
                            FLINT_AND_STEEL,
                            EquipmentTarget.MAINHAND
                    ));
            if (!equipped.succeeded()) {
                return fail(equipped.failure().orElseThrow());
            }
            interactionTarget = null;
            return SkillTickResult.running(true, true);
        }
        final GridPos base = at(
                parameters.anchor(),
                resolvedAxis,
                1,
                0
        );
        if (interactionTarget == null) {
            interactionTarget = visibleFace(
                    frame,
                    base,
                    BlockFace.UP
            ).orElse(null);
        }
        final PerceptionVec3 target = interactionTarget == null
                ? new PerceptionVec3(
                    base.x() + 0.5,
                    base.y() + 1.0,
                    base.z() + 0.5
                )
                : hit(interactionTarget);
        if (!holdAndLook(frame, target)) {
            return fail(NAME + ".actuator_rejected");
        }
        if (interactionTarget == null) {
            faceSearchTicks++;
            if (faceSearchTicks > MAXIMUM_FACE_SEARCH_TICKS) {
                return fail(NAME + ".lighting_face_unavailable");
            }
            return SkillTickResult.running(
                    freshObservation,
                    true
            );
        }
        if (angularError(
                frame.lookDirection(),
                target.subtract(frame.eyePosition())
        ) > ALIGNMENT_DEGREES) {
            return SkillTickResult.running(true, true);
        }
        flintDamageBefore = frame.mainHand().damage();
        flintCountBefore = inventoryCount(
                frame,
                FLINT_AND_STEEL
        );
        final ActionOutcome used =
                core.useMainHandOn(interactionTarget);
        if (!used.accepted()) {
            interactionTarget = null;
            return SkillTickResult.running(true, true);
        }
        beginPhase(Phase.VERIFYING_LIGHT, context, frame);
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyLight(
            final SkillContext context,
            final BuildNetherPortalParameters parameters,
            final CoreSkillFrame frame,
            final boolean freshObservation
    ) {
        final GridPos interior = at(
                parameters.anchor(),
                resolvedAxis,
                1,
                1
        );
        if (!holdAndLook(frame, center(interior))) {
            return fail(NAME + ".actuator_rejected");
        }
        final boolean portalVisible =
                frame.observationRevision()
                    > phaseObservationRevision
                    && visiblePortalInterior(
                        frame,
                        parameters.anchor(),
                        resolvedAxis
                    );
        if (portalVisible) {
            final boolean durabilityUsed =
                    FLINT_AND_STEEL.equals(
                        frame.mainHand().itemId()
                    )
                        && frame.mainHand().damage()
                            == flintDamageBefore + 1;
            final boolean toolBroke =
                    inventoryCount(frame, FLINT_AND_STEEL)
                        == flintCountBefore - 1;
            if (!durabilityUsed && !toolBroke) {
                return fail(
                        NAME + ".flint_durability_unverified"
                );
            }
            quiesce();
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (context.gameTick() - phaseStartedAtTick
                > MAXIMUM_LIGHT_VERIFY_TICKS) {
            return fail(NAME + ".portal_activation_unverified");
        }
        return SkillTickResult.running(
                freshObservation,
                false
        );
    }

    private Optional<PortalBuildAxis> resolveAxis(
            final BuildNetherPortalParameters parameters,
            final CoreSkillFrame frame
    ) {
        if (parameters.axis() != PortalBuildAxis.AUTO) {
            return siteUsable(
                    parameters.anchor(),
                    parameters.axis(),
                    frame
            )
                    ? Optional.of(parameters.axis())
                    : Optional.empty();
        }
        final boolean x = siteUsable(
                parameters.anchor(),
                PortalBuildAxis.X,
                frame
        );
        final boolean z = siteUsable(
                parameters.anchor(),
                PortalBuildAxis.Z,
                frame
        );
        if (x && !z) {
            return Optional.of(PortalBuildAxis.X);
        }
        if (z && !x) {
            return Optional.of(PortalBuildAxis.Z);
        }
        if (!x) {
            return Optional.empty();
        }
        final double xDistance = centerDistanceSquared(
                frame,
                parameters.anchor(),
                PortalBuildAxis.X
        );
        final double zDistance = centerDistanceSquared(
                frame,
                parameters.anchor(),
                PortalBuildAxis.Z
        );
        return Optional.of(
                xDistance <= zDistance
                        ? PortalBuildAxis.X
                        : PortalBuildAxis.Z
        );
    }

    private boolean siteUsable(
            final GridPos anchor,
            final PortalBuildAxis axis,
            final CoreSkillFrame frame
    ) {
        for (GridPos block : framePlan(anchor, axis)) {
            if (!(observedAir(frame, block)
                        || visibleBlockType(
                                frame,
                                block,
                                OBSIDIAN
                        )
                        || reusableVerifiedBlock(
                                anchor,
                                axis,
                                block,
                                frame
                        ))
                    || distance(frame.eyePosition(), center(block))
                        > MAXIMUM_INTERACTION_DISTANCE) {
                return false;
            }
        }
        for (int u = 0; u < 4; u++) {
            if (!observedSupport(
                    frame,
                    at(anchor, axis, u, -1)
            )) {
                return false;
            }
        }
        for (int u = 1; u <= 2; u++) {
            for (int v = 1; v <= 3; v++) {
                if (!observedAir(
                        frame,
                        at(anchor, axis, u, v)
                )) {
                    return false;
                }
            }
        }
        return true;
    }

    private int remainingFrameBlocks(
            final GridPos anchor,
            final PortalBuildAxis axis,
            final CoreSkillFrame frame
    ) {
        return (int) framePlan(anchor, axis).stream()
                .filter(block ->
                        !visibleBlockType(
                                frame,
                                block,
                                OBSIDIAN
                        )
                            && !reusableVerifiedBlock(
                                    anchor,
                                    axis,
                                    block,
                                    frame
                            )
                )
                .count();
    }

    /**
     * A retry may trust only blocks this exact skill previously placed and
     * verified through item consumption plus a later first-person voxel.
     * Current visible evidence still wins: a visible replacement block is
     * never treated as retained obsidian.
     */
    private boolean reusableVerifiedBlock(
            final GridPos anchor,
            final PortalBuildAxis axis,
            final GridPos block,
            final CoreSkillFrame frame
    ) {
        if (!anchor.equals(retainedPlanAnchor)
                || axis != retainedPlanAxis
                || !completedBlocks.contains(block)) {
            return false;
        }
        final List<VisibleBlockFace> currentFaces =
                frame.visibleBlockFaces().stream()
                        .filter(face ->
                                face.block().x() == block.x()
                                    && face.block().y() == block.y()
                                    && face.block().z() == block.z()
                        )
                        .toList();
        if (!currentFaces.isEmpty()) {
            return currentFaces.stream().allMatch(face ->
                    OBSIDIAN.equals(face.blockTypeId())
            );
        }
        return frame.navigation().voxelAt(block)
                .map(ObservedVoxel::kind)
                .map(VoxelKind::supportsWeight)
                .orElse(false);
    }

    private static boolean visibleBlockType(
            final CoreSkillFrame frame,
            final GridPos block,
            final String blockType
    ) {
        return frame.visibleBlockFaces().stream().anyMatch(face ->
                face.block().x() == block.x()
                    && face.block().y() == block.y()
                    && face.block().z() == block.z()
                    && blockType.equals(face.blockTypeId())
        );
    }

    private String siteDiagnostic(
            final GridPos anchor,
            final PortalBuildAxis axis,
            final CoreSkillFrame frame
    ) {
        final List<String> failures = new ArrayList<>();
        for (GridPos block : framePlan(anchor, axis)) {
            if (!(observedAir(frame, block)
                    || visibleBlockType(frame, block, OBSIDIAN)
                    || reusableVerifiedBlock(
                            anchor,
                            axis,
                            block,
                            frame
                    ))) {
                failures.add("air:" + block);
            }
            if (distance(frame.eyePosition(), center(block))
                    > MAXIMUM_INTERACTION_DISTANCE) {
                failures.add("reach:" + block);
            }
        }
        for (int u = 0; u < 4; u++) {
            final GridPos support = at(anchor, axis, u, -1);
            if (!observedSupport(frame, support)) {
                failures.add("support:" + support);
            }
        }
        for (int u = 1; u <= 2; u++) {
            for (int v = 1; v <= 3; v++) {
                final GridPos interior = at(anchor, axis, u, v);
                if (!observedAir(frame, interior)) {
                    failures.add("interior:" + interior);
                }
            }
        }
        return String.join(",", failures);
    }

    /**
     * Uses the body's current centre crosshair when available.  The
     * crosshair sampler performs the same finite OUTLINE ray and exact
     * hit-point check as the vanilla action actuator, so this avoids replaying
     * a peripheral semantic-ray hit after the body has turned.
     */
    private Optional<BlockInteractionTarget>
            currentCrosshairPlacementTarget(final GridPos desired) {
        return interactionFrames.flatMap(
                InteractionSkillFrameSource::currentCrosshairBlock
        ).flatMap(visible -> {
            final Optional<BlockFace> parsed = parseFace(visible.face());
            if (parsed.isEmpty()) {
                return Optional.empty();
            }
            final BlockFace face = parsed.orElseThrow();
            final GridPos clicked = new GridPos(
                    visible.block().x(),
                    visible.block().y(),
                    visible.block().z()
            );
            if (!offset(clicked, face).equals(desired)) {
                return Optional.empty();
            }
            try {
                return Optional.of(target(visible, face));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        });
    }

    private static Optional<BlockInteractionTarget> visibleFace(
            final CoreSkillFrame frame,
            final GridPos block,
            final BlockFace required
    ) {
        for (VisibleBlockFace visible : frame.visibleBlockFaces()) {
            if (visible.block().x() == block.x()
                    && visible.block().y() == block.y()
                    && visible.block().z() == block.z()
                    && parseFace(visible.face())
                        .filter(face -> face == required)
                        .isPresent()) {
                try {
                    return Optional.of(target(visible, required));
                } catch (IllegalArgumentException exception) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private PerceptionVec3 preferredFaceCenter(
            final GridPos desired
    ) {
        if (backingWallKnown && retainedPlanAnchor != null) {
            if (resolvedAxis == PortalBuildAxis.X) {
                return new PerceptionVec3(
                        desired.x() + 0.5,
                        desired.y() + 0.5,
                        desired.z() + 1.0
                );
            }
            return new PerceptionVec3(
                    desired.x() + 1.0,
                    desired.y() + 0.5,
                    desired.z() + 0.5
            );
        }
        for (BlockFace face : List.of(
                BlockFace.WEST,
                BlockFace.EAST,
                BlockFace.NORTH,
                BlockFace.SOUTH
        )) {
            final GridPos neighbour = offset(
                    desired,
                    opposite(face)
            );
            if (completedBlocks.contains(neighbour)) {
                return new PerceptionVec3(
                        desired.x() + 0.5
                            - deltaX(face) * 0.5,
                        desired.y() + 0.5,
                        desired.z() + 0.5
                            - deltaZ(face) * 0.5
                );
            }
        }
        final GridPos below = desired.below();
        if (completedBlocks.contains(below)
                || blockIndex < 4) {
            return new PerceptionVec3(
                    desired.x() + 0.5,
                    desired.y(),
                    desired.z() + 0.5
            );
        }
        /*
         * The upper corners are intentionally placed against the observed
         * backing wall.  Aiming at the empty destination cell is not a valid
         * vanilla placement ray: the server expects the crosshair to hit a
         * collision face of an existing block.  Turn toward the wall face
         * first, then currentCrosshairPlacementTarget() binds the exact hit
         * point on the following observation.
         */
        return center(desired);
    }

    private Optional<BlockInteractionTarget> placementTarget(
            final CoreSkillFrame frame,
            final GridPos desired
    ) {
        if (interactionFrames.isPresent()) {
            return currentCrosshairPlacementTarget(desired);
        }
        /* Compatibility fixtures without a server ray source retain their
         * semantic face adapter; production always supplies the exact
         * first-person sampler above. */
        for (VisibleBlockFace visible : frame.visibleBlockFaces()) {
            final Optional<BlockFace> parsed = parseFace(visible.face());
            if (parsed.isEmpty()) {
                continue;
            }
            final BlockFace face = parsed.orElseThrow();
            final GridPos clicked = new GridPos(
                    visible.block().x(),
                    visible.block().y(),
                    visible.block().z()
            );
            if (!offset(clicked, face).equals(desired)) {
                continue;
            }
            try {
                return Optional.of(target(visible, face));
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static boolean backingWallObserved(
            final GridPos anchor,
            final PortalBuildAxis axis,
            final CoreSkillFrame frame
    ) {
        for (int u = 0; u < 4; u++) {
            for (int v = 0; v <= 4; v++) {
                final GridPos backing = axis == PortalBuildAxis.X
                        ? anchor.offset(u, v, 1)
                        : anchor.offset(1, v, u);
                final Optional<ObservedVoxel> observed =
                        frame.navigation().voxelAt(backing);
                if (observed.isEmpty()
                        || !observed.orElseThrow().kind().supportsWeight()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isBackingSupportTarget(final GridPos desired) {
        if (retainedPlanAnchor == null
                || (desired.y() - retainedPlanAnchor.y()) < 4) {
            return false;
        }
        if (completedBlocks.contains(desired.below())) {
            return false;
        }
        for (BlockFace face : List.of(
                BlockFace.WEST,
                BlockFace.EAST,
                BlockFace.NORTH,
                BlockFace.SOUTH
        )) {
            if (completedBlocks.contains(offset(
                    desired,
                    opposite(face)
            ))) {
                return false;
            }
        }
        return true;
    }

    private Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        if (context.riskScore() > maximumDanger
                || frame.danger() > maximumDanger) {
            return Optional.of(SkillFailure.of(
                    NAME + ".danger_detected"
            ));
        }
        final double health = frame.health() / frame.maxHealth();
        final double minimumHealth = context.hardcore()
                ? HARDCORE_MINIMUM_HEALTH_RATIO
                : NORMAL_MINIMUM_HEALTH_RATIO;
        if (health < minimumHealth) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_reserve_required"
            ));
        }
        if (frame.foodLevel() < (context.hardcore() ? 10 : 6)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".food_reserve_required"
            ));
        }
        return Optional.empty();
    }

    private FrameValidation validateFrame(
            final BuildNetherPortalParameters parameters
    ) {
        final Optional<CoreSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return FrameValidation.failed(
                    NAME + ".body_unavailable"
            );
        }
        final CoreSkillFrame frame = current.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return FrameValidation.failed(
                    NAME + ".body_mismatch"
            );
        }
        if (!parameters.dimension().equals(frame.dimension())) {
            return FrameValidation.failed(
                    NAME + ".wrong_dimension"
            );
        }
        return FrameValidation.available(frame);
    }

    private boolean holdAndLook(
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        return core.move(MovementIntent.STOPPED).accepted()
                && core.look(
                    lookAt(frame.eyePosition(), target)
                ).accepted();
    }

    private void beginPhase(
            final Phase next,
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        phase = next;
        phaseStartedAtTick = context.gameTick();
        phaseObservationRevision = frame.observationRevision();
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        quiesce();
        failure = reason;
        phase = Phase.FAILED;
        interactionTarget = null;
        return SkillTickResult.failed(reason);
    }

    private void quiesce() {
        core.stop();
        core.releaseUse();
    }

    private static boolean portalDimension(
            final DimensionRef dimension
    ) {
        return dimension.equals(DimensionRef.OVERWORLD)
                || dimension.equals(DimensionRef.NETHER);
    }

    private static List<GridPos> framePlan(
            final GridPos anchor,
            final PortalBuildAxis axis
    ) {
        final List<GridPos> blocks =
                new ArrayList<>(REQUIRED_OBSIDIAN);
        /*
         * The body starts on the negative side of the fixture's frame. Build
         * the bottom beam from the far/right corner back toward the body so
         * already-placed blocks never occlude the next backing-wall ray.
         */
        for (int u = 3; u >= 0; u--) {
            blocks.add(at(anchor, axis, u, 0));
        }
        /*
         * A ground-level player cannot see the UP face of a third-level
         * column block.  Build the low sides first, use the observed backing
         * wall for the upper corners/top beam, then fill the middle height
         * from the DOWN face of those already-placed top blocks.  This is the
         * same finite, jump-free click geometry a player can use beside a
         * wall; no hidden block face or direct world write is introduced.
         */
        blocks.add(at(anchor, axis, 3, 1));
        blocks.add(at(anchor, axis, 3, 2));
        blocks.add(at(anchor, axis, 3, 4));
        blocks.add(at(anchor, axis, 2, 4));
        blocks.add(at(anchor, axis, 1, 4));
        blocks.add(at(anchor, axis, 3, 3));
        blocks.add(at(anchor, axis, 0, 1));
        blocks.add(at(anchor, axis, 0, 2));
        blocks.add(at(anchor, axis, 0, 4));
        blocks.add(at(anchor, axis, 0, 3));
        return List.copyOf(blocks);
    }

    private void advancePastCompletedBlocks() {
        while (blockIndex < frameBlocks.size()
                && completedBlocks.contains(
                        frameBlocks.get(blockIndex)
                )) {
            blockIndex++;
        }
    }

    private static GridPos at(
            final GridPos anchor,
            final PortalBuildAxis axis,
            final int u,
            final int v
    ) {
        return axis == PortalBuildAxis.X
                ? anchor.offset(u, v, 0)
                : anchor.offset(0, v, u);
    }

    private static boolean visiblePortalInterior(
            final CoreSkillFrame frame,
            final GridPos anchor,
            final PortalBuildAxis axis
    ) {
        for (VisibleBlockFace visible : frame.visibleBlockFaces()) {
            if (!NETHER_PORTAL.equals(visible.blockTypeId())) {
                continue;
            }
            final GridPos position = new GridPos(
                    visible.block().x(),
                    visible.block().y(),
                    visible.block().z()
            );
            for (int u = 1; u <= 2; u++) {
                for (int v = 1; v <= 3; v++) {
                    if (position.equals(at(anchor, axis, u, v))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean observedAir(
            final CoreSkillFrame frame,
            final GridPos position
    ) {
        return frame.navigation().voxelAt(position)
                .map(ObservedVoxel::kind)
                .map(kind -> kind == VoxelKind.AIR)
                .orElse(false);
    }

    private static boolean observedSupport(
            final CoreSkillFrame frame,
            final GridPos position
    ) {
        return frame.navigation().voxelAt(position)
                .map(ObservedVoxel::kind)
                .map(VoxelKind::supportsWeight)
                .orElse(false);
    }

    private static int inventoryCount(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        return frame.inventory().stream()
                .filter(item -> item.itemId().equals(itemId))
                .mapToInt(InventoryItemSummary::count)
                .sum();
    }

    private static BlockInteractionTarget target(
            final VisibleBlockFace visible,
            final BlockFace face
    ) {
        return new BlockInteractionTarget(
                visible.block().x(),
                visible.block().y(),
                visible.block().z(),
                face,
                new ActionVec3(
                        visible.hitPosition().x(),
                        visible.hitPosition().y(),
                        visible.hitPosition().z()
                )
        );
    }

    private static Optional<BlockFace> parseFace(
            final String value
    ) {
        final int separator = value.lastIndexOf(':');
        final String name = separator >= 0
                ? value.substring(separator + 1)
                : value;
        try {
            return Optional.of(BlockFace.valueOf(
                    name.toUpperCase(Locale.ROOT)
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static GridPos offset(
            final GridPos position,
            final BlockFace face
    ) {
        return position.offset(
                deltaX(face),
                deltaY(face),
                deltaZ(face)
        );
    }

    private static int deltaX(final BlockFace face) {
        return switch (face) {
            case EAST -> 1;
            case WEST -> -1;
            default -> 0;
        };
    }

    private static int deltaY(final BlockFace face) {
        return switch (face) {
            case UP -> 1;
            case DOWN -> -1;
            default -> 0;
        };
    }

    private static int deltaZ(final BlockFace face) {
        return switch (face) {
            case SOUTH -> 1;
            case NORTH -> -1;
            default -> 0;
        };
    }

    private static BlockFace opposite(final BlockFace face) {
        return switch (face) {
            case DOWN -> BlockFace.UP;
            case UP -> BlockFace.DOWN;
            case NORTH -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.NORTH;
            case WEST -> BlockFace.EAST;
            case EAST -> BlockFace.WEST;
        };
    }

    private static PerceptionVec3 center(final GridPos position) {
        return new PerceptionVec3(
                position.x() + 0.5,
                position.y() + 0.5,
                position.z() + 0.5
        );
    }

    private static PerceptionVec3 hit(
            final BlockInteractionTarget target
    ) {
        return new PerceptionVec3(
                target.hitPoint().x(),
                target.hitPoint().y(),
                target.hitPoint().z()
        );
    }

    private static LookIntent lookAt(
            final PerceptionVec3 eye,
            final PerceptionVec3 target
    ) {
        final PerceptionVec3 delta = target.subtract(eye);
        return new LookIntent(
                (float) Math.toDegrees(
                    Math.atan2(-delta.x(), delta.z())
                ),
                (float) Math.toDegrees(Math.atan2(
                    -delta.y(),
                    Math.hypot(delta.x(), delta.z())
                ))
        );
    }

    private static double angularError(
            final PerceptionVec3 current,
            final PerceptionVec3 target
    ) {
        if (target.lengthSquared() <= 1.0E-12) {
            return 180.0;
        }
        final double dot = current.normalized().dot(
                target.normalized()
        );
        return Math.toDegrees(Math.acos(
                Math.max(-1.0, Math.min(1.0, dot))
        ));
    }

    private static double distance(
            final PerceptionVec3 left,
            final PerceptionVec3 right
    ) {
        return left.subtract(right).length();
    }

    private static double centerDistanceSquared(
            final CoreSkillFrame frame,
            final GridPos anchor,
            final PortalBuildAxis axis
    ) {
        final PerceptionVec3 middle = center(
                at(anchor, axis, 1, 2)
        );
        return frame.position().subtract(middle).lengthSquared();
    }

    private enum Phase {
        IDLE,
        PLACING,
        VERIFYING_BLOCK,
        LIGHTING,
        VERIFYING_LIGHT,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return this == PLACING
                    || this == VERIFYING_BLOCK
                    || this == LIGHTING
                    || this == VERIFYING_LIGHT;
        }
    }

    private record FrameValidation(
            Optional<CoreSkillFrame> frame,
            Optional<SkillFailure> failure
    ) {
        private static FrameValidation available(
                final CoreSkillFrame frame
        ) {
            return new FrameValidation(
                    Optional.of(frame),
                    Optional.empty()
            );
        }

        private static FrameValidation failed(
                final String code
        ) {
            return new FrameValidation(
                    Optional.empty(),
                    Optional.of(SkillFailure.of(code))
            );
        }
    }
}
