package dev.mcai.companion.skills.portal;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.VoxelKind;
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
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrame;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.EquipmentTarget;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Casts one observed block of a minimal Nether-portal frame, or lights a
 * completely observed frame, exclusively through ordinary player actions.
 *
 * <p>A cast invocation is intentionally transactional at one lava source per
 * call. The high-level planner can move, expose another source, and call the
 * next generated frame index without holding a model request open. Before
 * lava is moved, this skill constructs a non-flammable two-level containment
 * cage around the selected frame cell. It then verifies every bucket
 * transition and the real obsidian result from a newer first-person semantic
 * observation. No level, chunk, fluid, structure, or writable world accessor
 * is available to this class.</p>
 */
public final class CastObservedNetherPortalSkill
        implements Skill<CastObservedNetherPortalParameters> {
    public static final String NAME = "cast_observed_nether_portal";

    private static final String AIR = "minecraft:air";
    private static final String BUCKET = "minecraft:bucket";
    private static final String WATER_BUCKET = "minecraft:water_bucket";
    private static final String LAVA_BUCKET = "minecraft:lava_bucket";
    private static final String LAVA = "minecraft:lava";
    private static final String WATER = "minecraft:water";
    private static final String OBSIDIAN = "minecraft:obsidian";
    private static final String NETHER_PORTAL = "minecraft:nether_portal";
    private static final String FLINT_AND_STEEL =
            "minecraft:flint_and_steel";

    private static final int MAXIMUM_OBSERVATION_AGE_TICKS = 20;
    private static final int MAXIMUM_TOTAL_TICKS = 3_600;
    private static final int MAXIMUM_PHASE_TICKS = 120;
    private static final int MAXIMUM_DRAIN_TICKS = 200;
    private static final int MAXIMUM_LIGHT_SURVEY_TICKS = 320;
    private static final int CONSERVATIVE_DRAIN_TICKS = 40;
    private static final int CONSERVATIVE_CLEANUP_TICKS = 20;
    private static final int MAXIMUM_SITE_MEMORY_TICKS = 400;
    private static final double MAXIMUM_REACH = 4.5;
    private static final double ALIGNMENT_DEGREES = 0.75;
    private static final double NORMAL_MAXIMUM_DANGER = 0.10;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.035;
    private static final double NORMAL_MINIMUM_HEALTH = 0.70;
    private static final double HARDCORE_MINIMUM_HEALTH = 0.90;

    private static final List<String> TEMPORARY_MATERIALS = List.of(
            "minecraft:dirt",
            "minecraft:netherrack",
            "minecraft:cobblestone",
            "minecraft:cobbled_deepslate"
    );
    private static final Set<String> SAFE_EXISTING_FORMWORK = Set.of(
            "minecraft:dirt",
            "minecraft:grass_block",
            "minecraft:stone",
            "minecraft:smooth_stone",
            "minecraft:cobblestone",
            "minecraft:deepslate",
            "minecraft:cobbled_deepslate",
            "minecraft:tuff",
            "minecraft:calcite",
            "minecraft:netherrack",
            "minecraft:blackstone",
            "minecraft:basalt",
            "minecraft:smooth_basalt",
            "minecraft:obsidian",
            "minecraft:crying_obsidian",
            "minecraft:bedrock"
    );

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource coreFrames;
    private final InteractionSkillActuator interactions;
    private final InteractionSkillFrameSource interactionFrames;
    private final InventorySkillActuator inventory;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long phaseStartedAtTick = -1;
    private long boundSessionGeneration = -1;
    private long dispatchedObservationRevision = -1;
    private String temporaryMaterial;
    private GridPos targetBlock;
    private GridPos waterBlock;
    private List<GridPos> formworkPlan = List.of();
    private List<GridPos> lightFramePlan = List.of();
    private final Set<GridPos> placedFormwork = new LinkedHashSet<>();
    private final Map<GridPos, SiteEvidence> siteMemory = new HashMap<>();
    private GridPos activeBlock;
    private GridPos surveyBlock;
    private BlockInteractionTarget activeTarget;
    private int countBefore;
    private int secondaryCountBefore;
    private int toolDamageBefore;

    public CastObservedNetherPortalSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource coreFrames,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final InventorySkillActuator inventory
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.core = Objects.requireNonNull(core, "core");
        this.coreFrames = Objects.requireNonNull(
                coreFrames,
                "coreFrames"
        );
        this.interactions = Objects.requireNonNull(
                interactions,
                "interactions"
        );
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
    public SkillParameterParser<CastObservedNetherPortalParameters>
    parameters() {
        return PortalCastSkillParameters::parse;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final CastObservedNetherPortalParameters parameters
    ) {
        final Validation validation = validate(
                context,
                parameters,
                true
        );
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        return prepare(
                context,
                parameters,
                validation.frames().orElseThrow(),
                false
        ).failure();
    }

    @Override
    public void start(
            final SkillContext context,
            final CastObservedNetherPortalParameters parameters
    ) {
        final Validation validation = validate(
                context,
                parameters,
                true
        );
        if (validation.failure().isPresent()) {
            throw new IllegalStateException(
                    "Portal casting binding changed before start"
            );
        }
        final Preparation preparation = prepare(
                context,
                parameters,
                validation.frames().orElseThrow(),
                true
        );
        if (preparation.failure().isPresent()) {
            throw new IllegalStateException(
                    "Portal casting site changed before start"
            );
        }
        phase = preparation.initialPhase();
        failure = null;
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        boundSessionGeneration = validation.frames()
                .orElseThrow()
                .interaction()
                .sessionGeneration();
        dispatchedObservationRevision = -1;
        siteMemory.clear();
        rememberSiteEvidence(validation.frames().orElseThrow());
        activeBlock = null;
        surveyBlock = null;
        activeTarget = null;
        countBefore = 0;
        secondaryCountBefore = 0;
        toolDamageBefore = 0;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final CastObservedNetherPortalParameters parameters
    ) {
        if (phase == Phase.FAILED && failure != null) {
            return SkillTickResult.failed(failure);
        }
        if (phase == Phase.COMPLETED) {
            return SkillTickResult.completed();
        }
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        if (context.gameTick() - startedAtTick > MAXIMUM_TOTAL_TICKS) {
            return fail(NAME + ".timed_out");
        }
        if (context.gameTick() - phaseStartedAtTick
                > phaseTimeout()) {
            return fail(NAME + ".phase_timed_out");
        }
        final Validation validation = validate(
                context,
                parameters,
                false
        );
        if (validation.failure().isPresent()) {
            return fail(validation.failure().orElseThrow());
        }
        final Frames frames = validation.frames().orElseThrow();
        try {
            return switch (phase) {
                case READY_COMPLETE -> complete();
                case SURVEY_LIGHT -> surveyLight(context, frames);
                case PREPARE_FORMWORK -> prepareFormwork(
                        context,
                        frames
                );
                case VERIFY_FORMWORK -> verifyFormwork(
                        context,
                        frames
                );
                case COLLECT_LAVA -> collectLava(
                        context,
                        parameters,
                        frames
                );
                case VERIFY_LAVA_COLLECTION -> verifyLavaCollection(
                        context,
                        parameters,
                        frames
                );
                case PLACE_LAVA -> placeLava(context, frames);
                case VERIFY_LAVA -> verifyLava(context, frames);
                case PLACE_WATER -> placeWater(context, frames);
                case VERIFY_CAST -> verifyCast(context, frames);
                case COLLECT_WATER -> collectWater(context, frames);
                case VERIFY_WATER_COLLECTION -> verifyWaterCollection(
                        context,
                        frames
                );
                case WAIT_FOR_DRAIN -> waitForDrain(context, frames);
                case CLEAN_FORMWORK -> cleanFormwork(
                        context,
                        frames
                );
                case MINING_FORMWORK -> mineFormwork(
                        context,
                        frames
                );
                case VERIFY_CLEANUP -> verifyCleanup(
                        context,
                        frames
                );
                case LIGHT -> light(context, parameters, frames);
                case VERIFY_LIGHT -> verifyLight(
                        context,
                        parameters,
                        frames
                );
                default -> SkillTickResult.failed(
                        NAME + ".invalid_state"
                );
            };
        } catch (RuntimeException exception) {
            return fail(NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final CastObservedNetherPortalParameters parameters
    ) {
        final GridPos target = targetBlock;
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"operation\":\"%s\","
                            + "\"dimension\":\"%s\",\"anchor\":[%d,%d,%d],"
                        + "\"axis\":\"%s\",\"frameIndex\":%d,"
                        + "\"target\":%s,\"survey\":%s,"
                        + "\"interactionTarget\":%s,"
                        + "\"temporaryPlaced\":%d,"
                        + "\"session\":%d}",
                        phase.name(),
                        parameters.operation().wireName(),
                        parameters.dimension().id(),
                        parameters.anchor().x(),
                        parameters.anchor().y(),
                        parameters.anchor().z(),
                        parameters.axis().name().toLowerCase(Locale.ROOT),
                        parameters.frameIndex().orElse(-1),
                        target == null
                                ? "null"
                                : "[%d,%d,%d]".formatted(
                                    target.x(),
                                    target.y(),
                                    target.z()
                                ),
                        surveyBlock == null
                                ? "null"
                                : "[%d,%d,%d]".formatted(
                                    surveyBlock.x(),
                                    surveyBlock.y(),
                                    surveyBlock.z()
                                ),
                        activeTarget == null
                                ? "null"
                                : "\"%d,%d,%d:%s@%.3f,%.3f,%.3f\""
                                    .formatted(
                                        activeTarget.x(),
                                        activeTarget.y(),
                                        activeTarget.z(),
                                        activeTarget.face().name(),
                                        activeTarget.hitPoint().x(),
                                        activeTarget.hitPoint().y(),
                                        activeTarget.hitPoint().z()
                                    ),
                        placedFormwork.size(),
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final CastObservedNetherPortalParameters parameters
    ) {
        quiesce();
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final CastObservedNetherPortalParameters parameters
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

    private Preparation prepare(
            final SkillContext context,
            final CastObservedNetherPortalParameters parameters,
            final Frames frames,
            final boolean commit
    ) {
        if (parameters.operation() == PortalCastOperation.LIGHT) {
            final Optional<SkillFailure> frameFailure =
                    validateCompleteFrame(parameters, frames);
            if (frameFailure.isPresent()) {
                return Preparation.failed(frameFailure.orElseThrow());
            }
            if (commit) {
                lightFramePlan = minimumFramePlan(
                        parameters.anchor(),
                        parameters.axis()
                );
                /*
                 * A positively observed interior obstruction is rejected by
                 * validateCompleteFrame. Unknown air is left to vanilla's
                 * portal-shape validation at ignition: failure cannot create
                 * a portal and is verified as such, while demanding three
                 * same-frame clearance rays for every empty cell deadlocks
                 * after a recently collected water source.
                 */
            }
            return Preparation.ready(Phase.SURVEY_LIGHT);
        }
        if (!parameters.dimension().equals(DimensionRef.OVERWORLD)) {
            return Preparation.failed(
                    NAME + ".casting_requires_overworld"
            );
        }
        final GridPos target = minimumFramePlan(
                parameters.anchor(),
                parameters.axis()
        ).get(parameters.frameIndex().orElseThrow());
        if (observedBlockId(frames.interaction(), target)
                .filter(OBSIDIAN::equals)
                .isPresent()) {
            if (commit) {
                targetBlock = target;
                waterBlock = target.above();
                temporaryMaterial = null;
                formworkPlan = List.of();
                placedFormwork.clear();
            }
            return Preparation.ready(Phase.READY_COMPLETE);
        }
        if (!freshVoxel(frames.core(), target, VoxelKind.AIR)
                && observedBlockId(
                    frames.interaction(),
                    target
                ).isPresent()) {
            return Preparation.failed(NAME + ".target_air_not_observed");
        }
        final GridPos water = target.above();
        if (!freshVoxel(frames.core(), water, VoxelKind.AIR)
                && observedBlockId(
                    frames.interaction(),
                    water
                ).isPresent()) {
            return Preparation.failed(NAME + ".water_cell_not_observed");
        }
        if (frames.core().feet().equals(target)
                || frames.core().feet().equals(water)) {
            return Preparation.failed(NAME + ".body_inside_cast");
        }
        if (distance(frames.core().eyePosition(), center(target))
                > MAXIMUM_REACH
                || distance(frames.core().eyePosition(), center(water))
                    > MAXIMUM_REACH) {
            return Preparation.failed(NAME + ".cast_target_out_of_reach");
        }
        if (flammableNearby(frames.interaction(), target)) {
            return Preparation.failed(NAME + ".flammable_site");
        }
        if (inventoryCount(frames.core(), WATER_BUCKET) < 1) {
            return Preparation.failed(NAME + ".water_bucket_required");
        }
        if (inventoryCount(frames.core(), BUCKET) < 1) {
            return Preparation.failed(NAME + ".second_bucket_required");
        }
        final GridPos lavaSource = parameters.lavaSource().orElseThrow();
        final Optional<VisibleBlockFace> lavaFace = visibleFluidSource(
                frames.interaction(),
                lavaSource,
                LAVA
        );
        if (lavaFace.isEmpty()) {
            return Preparation.failed(NAME + ".visible_lava_source_required");
        }
        if (lavaFace.orElseThrow().distance() > MAXIMUM_REACH
                || lavaSource.equals(target)
                || lavaSource.equals(water)) {
            return Preparation.failed(NAME + ".lava_source_out_of_reach");
        }
        if (flammableNearby(frames.interaction(), lavaSource)) {
            return Preparation.failed(NAME + ".flammable_lava_area");
        }

        final List<GridPos> formwork = formworkPlan(
                target,
                frames
        );
        int needed = 0;
        for (GridPos position : formwork) {
            final SiteCell cell = siteCell(frames, position);
            if (cell == SiteCell.AIR) {
                needed++;
            } else if (cell == SiteCell.UNSAFE_SOLID) {
                return Preparation.failed(
                        NAME + ".unsafe_formwork_obstruction"
                );
            } else if (cell == SiteCell.UNKNOWN) {
                // Reserve material for the fail-closed worst case. The
                // active phase will turn and fairly inspect this exact cell
                // before deciding whether to place anything.
                needed++;
            }
            if (frames.core().feet().equals(position)) {
                return Preparation.failed(NAME + ".body_inside_formwork");
            }
            if (distance(
                    frames.core().eyePosition(),
                    center(position)
            ) > MAXIMUM_REACH) {
                return Preparation.failed(
                        NAME + ".formwork_out_of_reach"
                );
            }
        }
        final Optional<String> material = chooseTemporaryMaterial(
                frames.core(),
                needed
        );
        if (material.isEmpty()) {
            return Preparation.failed(
                    NAME + ".nonflammable_formwork_required"
            );
        }
        if (commit) {
            targetBlock = target;
            waterBlock = water;
            temporaryMaterial = material.orElseThrow();
            formworkPlan = formwork;
            placedFormwork.clear();
        }
        return Preparation.ready(Phase.PREPARE_FORMWORK);
    }

    private SkillTickResult prepareFormwork(
            final SkillContext context,
            final Frames frames
    ) {
        rememberSiteEvidence(frames);
        final Optional<SkillTickResult> targetSurvey =
                ensureRecentAir(
                    frames,
                    Objects.requireNonNull(targetBlock),
                    "target_site_changed"
                );
        if (targetSurvey.isPresent()) {
            return targetSurvey.orElseThrow();
        }
        final Optional<SkillTickResult> waterSurvey =
                ensureRecentAir(
                    frames,
                    Objects.requireNonNull(waterBlock),
                    "water_site_changed"
                );
        if (waterSurvey.isPresent()) {
            return waterSurvey.orElseThrow();
        }
        for (GridPos position : formworkPlan) {
            if (placedFormwork.contains(position)) {
                final Optional<SiteEvidence> evidence =
                        recentSiteEvidence(frames, position);
                if (evidence.isEmpty()) {
                    surveyBlock = position;
                    return lookAndWait(
                            frames.core(),
                            topCenter(position)
                    );
                }
                surveyBlock = null;
                if (!temporaryMaterial.equals(
                        evidence.orElseThrow().blockId()
                )) {
                    return fail(NAME + ".placed_formwork_changed");
                }
                continue;
            }
            final SiteCell cell = rememberedSiteCell(
                    frames,
                    position
            );
            if (cell == SiteCell.SAFE_SOLID) {
                surveyBlock = null;
                continue;
            }
            if (cell == SiteCell.UNSAFE_SOLID) {
                return fail(NAME + ".unsafe_formwork_obstruction");
            }
            if (cell == SiteCell.UNKNOWN) {
                surveyBlock = position;
                return lookAndWait(
                        frames.core(),
                        topCenter(position)
                );
            }
            surveyBlock = position;
            /*
             * Cached air is enough to finish a bounded survey, but a block
             * write still requires this exact cell to be proven clear by the
             * latest first-person semantic sample.
             */
            if (!freshVoxel(
                    frames.core(),
                    position,
                    VoxelKind.AIR
            )) {
                surveyBlock = position;
                return lookAndWait(
                        frames.core(),
                        topCenter(position)
                );
            }
            final Optional<SkillTickResult> equipped = ensureEquipped(
                    frames.core(),
                    temporaryMaterial
            );
            if (equipped.isPresent()) {
                return equipped.orElseThrow();
            }
            final Optional<BlockInteractionTarget> target =
                    visiblePlacementTarget(
                            frames.interaction(),
                            position
                    );
            if (target.isEmpty()) {
                return lookAndWait(
                        frames.core(),
                        placementSupportAim(
                                frames.core().eyePosition(),
                                position
                        )
                );
            }
            if (!aligned(frames.core(), hit(target.orElseThrow()))) {
                return lookAndWait(
                        frames.core(),
                        hit(target.orElseThrow())
                );
            }
            countBefore = inventoryCount(
                    frames.core(),
                    temporaryMaterial
            );
            final ActionOutcome outcome = interactions.useOnBlock(
                    ActionHand.MAIN_HAND,
                    target.orElseThrow()
            );
            activeTarget = target.orElseThrow();
            if (outcome == ActionOutcome.TARGET_OCCLUDED) {
                return lookAndWait(
                        frames.core(),
                        hit(target.orElseThrow())
                );
            }
            if (!outcome.accepted()) {
                return fail(actionFailure(
                        "formwork_place",
                        outcome
                ));
            }
            activeBlock = position;
            surveyBlock = null;
            dispatchedObservationRevision =
                    frames.core().observationRevision();
            beginPhase(Phase.VERIFY_FORMWORK, context);
            return SkillTickResult.running(true, false);
        }
        beginPhase(Phase.COLLECT_LAVA, context);
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult surveyLight(
            final SkillContext context,
            final Frames frames
    ) {
        rememberSiteEvidence(frames);
        for (GridPos position : lightFramePlan) {
            final Optional<SiteEvidence> evidence =
                    recentSiteEvidence(frames, position);
            if (evidence.isEmpty()) {
                surveyBlock = position;
                return lookAndWait(
                        frames.core(),
                        center(position)
                );
            }
            if (!OBSIDIAN.equals(
                    evidence.orElseThrow().blockId()
            )) {
                return fail(NAME + ".complete_frame_not_observed");
            }
        }
        surveyBlock = null;
        beginPhase(Phase.LIGHT, context);
        return SkillTickResult.running(true, true);
    }

    private Optional<SkillTickResult> ensureRecentAir(
            final Frames frames,
            final GridPos position,
            final String changedFailure
    ) {
        final Optional<SiteEvidence> evidence =
                recentSiteEvidence(frames, position);
        if (evidence.filter(value ->
                value.cell() == SiteCell.AIR
        ).isPresent()) {
            surveyBlock = null;
            return Optional.empty();
        }
        if (evidence.isPresent()) {
            return Optional.of(fail(NAME + "." + changedFailure));
        }
        surveyBlock = position;
        return Optional.of(lookAndWait(
                frames.core(),
                topCenter(position)
        ));
    }

    private Optional<SkillTickResult> ensureCurrentAirForWrite(
            final Frames frames,
            final GridPos position,
            final String changedFailure
    ) {
        if (freshVoxel(frames.core(), position, VoxelKind.AIR)) {
            return Optional.empty();
        }
        if (observedBlockId(
                frames.interaction(),
                position
        ).isPresent()) {
            return Optional.of(fail(NAME + "." + changedFailure));
        }
        return Optional.of(lookAndWait(
                frames.core(),
                topCenter(position)
        ));
    }

    private SkillTickResult verifyFormwork(
            final SkillContext context,
            final Frames frames
    ) {
        if (!newObservation(frames)) {
            return SkillTickResult.running(false, false);
        }
        if (!observedBlockId(
                frames.interaction(),
                Objects.requireNonNull(activeBlock)
        ).filter(temporaryMaterial::equals).isPresent()) {
            return fail(NAME + ".formwork_unverified");
        }
        if (countBefore - inventoryCount(
                frames.core(),
                temporaryMaterial
        ) != 1) {
            return fail(NAME + ".formwork_consumption_unverified");
        }
        placedFormwork.add(activeBlock);
        activeBlock = null;
        activeTarget = null;
        beginPhase(Phase.PREPARE_FORMWORK, context);
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult collectLava(
            final SkillContext context,
            final CastObservedNetherPortalParameters parameters,
            final Frames frames
    ) {
        final Optional<SkillTickResult> equipped =
                ensureEquipped(frames.core(), BUCKET);
        if (equipped.isPresent()) {
            return equipped.orElseThrow();
        }
        final Optional<VisibleBlockFace> visible = visibleFluidSource(
                frames.interaction(),
                parameters.lavaSource().orElseThrow(),
                LAVA
        );
        if (visible.isEmpty()) {
            final GridPos source =
                    parameters.lavaSource().orElseThrow();
            if (observedBlockId(
                    frames.interaction(),
                    source
            ).isPresent()) {
                return fail(NAME + ".lava_source_changed");
            }
            return lookAndWait(
                    frames.core(),
                    topCenter(source)
            );
        }
        final PerceptionVec3 sourceHit =
                visible.orElseThrow().hitPosition();
        if (!aligned(frames.core(), sourceHit)) {
            return lookAndWait(frames.core(), sourceHit);
        }
        countBefore = inventoryCount(frames.core(), BUCKET);
        secondaryCountBefore = inventoryCount(
                frames.core(),
                LAVA_BUCKET
        );
        /*
         * A real client sends a generic use-item packet when a bucket is
         * aimed at a fluid source. BucketItem then performs vanilla's
         * SOURCE_ONLY point-of-view ray. Sending use-on-block here would
         * incorrectly require a solid OUTLINE hit for a fluid voxel.
         */
        final ActionOutcome outcome = interactions.useItem(
                ActionHand.MAIN_HAND
        );
        if (!outcome.accepted()) {
            return fail(actionFailure("lava_collect", outcome));
        }
        dispatchedObservationRevision =
                frames.core().observationRevision();
        beginPhase(Phase.VERIFY_LAVA_COLLECTION, context);
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyLavaCollection(
            final SkillContext context,
            final CastObservedNetherPortalParameters parameters,
            final Frames frames
    ) {
        if (!newObservation(frames)) {
            return SkillTickResult.running(false, false);
        }
        if (countBefore - inventoryCount(frames.core(), BUCKET) != 1
                || inventoryCount(frames.core(), LAVA_BUCKET)
                    - secondaryCountBefore != 1) {
            return fail(NAME + ".lava_collection_unverified");
        }
        if (visibleFluidSource(
                frames.interaction(),
                parameters.lavaSource().orElseThrow(),
                LAVA
        ).isPresent()) {
            return fail(NAME + ".lava_source_still_present");
        }
        beginPhase(Phase.PLACE_LAVA, context);
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult placeLava(
            final SkillContext context,
            final Frames frames
    ) {
        final Optional<SkillTickResult> targetSurvey =
                ensureCurrentAirForWrite(
                        frames,
                        Objects.requireNonNull(targetBlock),
                        "target_site_changed"
                );
        if (targetSurvey.isPresent()) {
            return targetSurvey.orElseThrow();
        }
        final Optional<SkillTickResult> waterSurvey =
                ensureCurrentAirForWrite(
                        frames,
                        Objects.requireNonNull(waterBlock),
                        "water_site_changed"
                );
        if (waterSurvey.isPresent()) {
            return waterSurvey.orElseThrow();
        }
        final Optional<SkillTickResult> equipped =
                ensureEquipped(frames.core(), LAVA_BUCKET);
        if (equipped.isPresent()) {
            return equipped.orElseThrow();
        }
        final Optional<BlockInteractionTarget> placement =
                visiblePlacementTarget(
                        frames.interaction(),
                        targetBlock
                );
        if (placement.isEmpty()) {
            return fail(NAME + ".lava_placement_face_unavailable");
        }
        if (!aligned(frames.core(), hit(placement.orElseThrow()))) {
            return lookAndWait(
                    frames.core(),
                    hit(placement.orElseThrow())
            );
        }
        countBefore = inventoryCount(frames.core(), LAVA_BUCKET);
        secondaryCountBefore = inventoryCount(frames.core(), BUCKET);
        final ActionOutcome outcome = interactions.useItem(
                ActionHand.MAIN_HAND
        );
        if (!outcome.accepted()) {
            return fail(actionFailure("lava_place", outcome));
        }
        dispatchedObservationRevision =
                frames.core().observationRevision();
        beginPhase(Phase.VERIFY_LAVA, context);
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyLava(
            final SkillContext context,
            final Frames frames
    ) {
        if (!newObservation(frames)) {
            return SkillTickResult.running(false, false);
        }
        if (countBefore - inventoryCount(frames.core(), LAVA_BUCKET) != 1
                || inventoryCount(frames.core(), BUCKET)
                    - secondaryCountBefore != 1
                || visibleFluidSource(
                    frames.interaction(),
                    targetBlock,
                    LAVA
                ).isEmpty()) {
            return fail(NAME + ".lava_placement_unverified");
        }
        beginPhase(Phase.PLACE_WATER, context);
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult placeWater(
            final SkillContext context,
            final Frames frames
    ) {
        final Optional<SkillTickResult> waterSurvey =
                ensureCurrentAirForWrite(
                        frames,
                        Objects.requireNonNull(waterBlock),
                        "water_site_changed"
                );
        if (waterSurvey.isPresent()) {
            return waterSurvey.orElseThrow();
        }
        final Optional<SkillTickResult> equipped =
                ensureEquipped(frames.core(), WATER_BUCKET);
        if (equipped.isPresent()) {
            return equipped.orElseThrow();
        }
        final Optional<BlockInteractionTarget> placement =
                visiblePlacementTarget(
                        frames.interaction(),
                        waterBlock
                );
        if (placement.isEmpty()) {
            return fail(NAME + ".water_placement_face_unavailable");
        }
        if (!aligned(
                frames.core(),
                hit(placement.orElseThrow())
        )) {
            return lookAndWait(
                    frames.core(),
                    hit(placement.orElseThrow())
            );
        }
        countBefore = inventoryCount(frames.core(), WATER_BUCKET);
        secondaryCountBefore = inventoryCount(frames.core(), BUCKET);
        final ActionOutcome outcome = interactions.useItem(
                ActionHand.MAIN_HAND
        );
        if (!outcome.accepted()) {
            return fail(actionFailure("water_place", outcome));
        }
        dispatchedObservationRevision =
                frames.core().observationRevision();
        beginPhase(Phase.VERIFY_CAST, context);
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyCast(
            final SkillContext context,
            final Frames frames
    ) {
        if (!newObservation(frames)) {
            return SkillTickResult.running(false, false);
        }
        if (countBefore - inventoryCount(frames.core(), WATER_BUCKET) != 1
                || inventoryCount(frames.core(), BUCKET)
                    - secondaryCountBefore != 1
                || !observedBlockId(frames.interaction(), targetBlock)
                    .filter(OBSIDIAN::equals)
                    .isPresent()
                || visibleFluidSource(
                    frames.interaction(),
                    waterBlock,
                    WATER
                ).isEmpty()) {
            return fail(NAME + ".obsidian_cast_unverified");
        }
        beginPhase(Phase.COLLECT_WATER, context);
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult collectWater(
            final SkillContext context,
            final Frames frames
    ) {
        final Optional<SkillTickResult> equipped =
                ensureEquipped(frames.core(), BUCKET);
        if (equipped.isPresent()) {
            return equipped.orElseThrow();
        }
        final Optional<VisibleBlockFace> water = visibleFluidSource(
                frames.interaction(),
                waterBlock,
                WATER
        );
        if (water.isEmpty()) {
            return fail(NAME + ".water_source_unavailable");
        }
        final PerceptionVec3 sourceHit =
                water.orElseThrow().hitPosition();
        if (!aligned(frames.core(), sourceHit)) {
            return lookAndWait(frames.core(), sourceHit);
        }
        countBefore = inventoryCount(frames.core(), BUCKET);
        secondaryCountBefore = inventoryCount(
                frames.core(),
                WATER_BUCKET
        );
        final ActionOutcome outcome = interactions.useItem(
                ActionHand.MAIN_HAND
        );
        if (!outcome.accepted()) {
            return fail(actionFailure("water_collect", outcome));
        }
        dispatchedObservationRevision =
                frames.core().observationRevision();
        beginPhase(Phase.VERIFY_WATER_COLLECTION, context);
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyWaterCollection(
            final SkillContext context,
            final Frames frames
    ) {
        if (!newObservation(frames)) {
            return SkillTickResult.running(false, false);
        }
        if (countBefore - inventoryCount(frames.core(), BUCKET) != 1
                || inventoryCount(frames.core(), WATER_BUCKET)
                    - secondaryCountBefore != 1
                || visibleFluidSource(
                    frames.interaction(),
                    waterBlock,
                    WATER
                ).isPresent()) {
            return fail(NAME + ".water_collection_unverified");
        }
        beginPhase(Phase.WAIT_FOR_DRAIN, context);
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult waitForDrain(
            final SkillContext context,
            final Frames frames
    ) {
        if (freshVoxel(frames.core(), waterBlock, VoxelKind.AIR)) {
            beginPhase(Phase.CLEAN_FORMWORK, context);
            return SkillTickResult.running(true, true);
        }
        final Optional<String> visible =
                observedBlockId(frames.interaction(), waterBlock);
        if (visible.filter(WATER::equals).isEmpty()
                && context.gameTick() - phaseStartedAtTick
                    >= CONSERVATIVE_DRAIN_TICKS) {
            /*
             * The owned source was already recovered by a verified vanilla
             * bucket transaction. If the now-empty cell is occluded from the
             * semantic ray map, wait longer than vanilla water's local flow
             * cadence before cleanup instead of oscillating forever for an
             * impossible same-frame air proof.
             */
            beginPhase(Phase.CLEAN_FORMWORK, context);
            return SkillTickResult.running(true, true);
        }
        return lookAndWait(frames.core(), center(waterBlock));
    }

    private SkillTickResult cleanFormwork(
            final SkillContext context,
            final Frames frames
    ) {
        final List<GridPos> reverse =
                new ArrayList<>(placedFormwork);
        Collections.reverse(reverse);
        for (GridPos position : reverse) {
            if (observedBlockId(frames.interaction(), position)
                    .filter(temporaryMaterial::equals)
                    .isEmpty()) {
                if (freshVoxel(
                        frames.core(),
                        position,
                        VoxelKind.AIR
                )) {
                    placedFormwork.remove(position);
                    continue;
                }
                return fail(NAME + ".cleanup_target_changed");
            }
            final Optional<VisibleBlockFace> face =
                    visibleFace(frames.interaction(), position);
            if (face.isEmpty()) {
                return lookAndWait(
                        frames.core(),
                        center(position)
                );
            }
            final BlockInteractionTarget target = target(
                    face.orElseThrow()
            );
            if (!aligned(frames.core(), hit(target))) {
                return lookAndWait(frames.core(), hit(target));
            }
            final ActionOutcome outcome =
                    interactions.beginMining(target);
            if (!outcome.accepted()) {
                return fail(actionFailure(
                        "formwork_mine",
                        outcome
                ));
            }
            activeBlock = position;
            activeTarget = target;
            dispatchedObservationRevision =
                    frames.core().observationRevision();
            beginPhase(
                    outcome == ActionOutcome.COMPLETED
                            ? Phase.VERIFY_CLEANUP
                            : Phase.MINING_FORMWORK,
                    context
            );
            return SkillTickResult.running(true, false);
        }
        if (!observedBlockId(frames.interaction(), targetBlock)
                .filter(OBSIDIAN::equals)
                .isPresent()) {
            return fail(NAME + ".cast_result_changed");
        }
        return complete();
    }

    private SkillTickResult mineFormwork(
            final SkillContext context,
            final Frames frames
    ) {
        if (!observedBlockId(frames.interaction(), activeBlock)
                .filter(temporaryMaterial::equals)
                .isPresent()) {
            return fail(NAME + ".cleanup_target_changed");
        }
        final ActionOutcome outcome = interactions.continueMining();
        if (outcome == ActionOutcome.COMPLETED) {
            dispatchedObservationRevision =
                    frames.core().observationRevision();
            beginPhase(Phase.VERIFY_CLEANUP, context);
            return SkillTickResult.running(true, false);
        }
        if (!outcome.accepted()) {
            return fail(actionFailure("formwork_mine", outcome));
        }
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyCleanup(
            final SkillContext context,
            final Frames frames
    ) {
        if (!newObservation(frames)) {
            return SkillTickResult.running(false, false);
        }
        if (!freshVoxel(
                frames.core(),
                Objects.requireNonNull(activeBlock),
                VoxelKind.AIR
        )) {
            final Optional<String> visible = observedBlockId(
                    frames.interaction(),
                    activeBlock
            );
            if (visible.isPresent()) {
                return fail(NAME + ".cleanup_unverified");
            }
            if (context.gameTick() - phaseStartedAtTick
                    < CONSERVATIVE_CLEANUP_TICKS) {
                return lookAndWait(
                        frames.core(),
                        center(activeBlock)
                );
            }
        }
        placedFormwork.remove(activeBlock);
        activeBlock = null;
        activeTarget = null;
        beginPhase(Phase.CLEAN_FORMWORK, context);
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult light(
            final SkillContext context,
            final CastObservedNetherPortalParameters parameters,
            final Frames frames
    ) {
        final Optional<SkillFailure> invalidFrame =
                validateCompleteFrame(parameters, frames);
        if (invalidFrame.isPresent()) {
            return fail(invalidFrame.orElseThrow());
        }
        final Optional<SkillTickResult> equipped =
                ensureEquipped(frames.core(), FLINT_AND_STEEL);
        if (equipped.isPresent()) {
            return equipped.orElseThrow();
        }
        final GridPos base = at(
                parameters.anchor(),
                parameters.axis(),
                1,
                0
        );
        final Optional<VisibleBlockFace> face = visibleFace(
                frames.interaction(),
                base,
                BlockFace.UP
        );
        if (face.isEmpty()) {
            return fail(NAME + ".lighting_face_unavailable");
        }
        final BlockInteractionTarget target = target(
                face.orElseThrow()
        );
        if (!aligned(frames.core(), hit(target))) {
            return lookAndWait(frames.core(), hit(target));
        }
        countBefore = inventoryCount(
                frames.core(),
                FLINT_AND_STEEL
        );
        toolDamageBefore = frames.core().mainHand().damage();
        final ActionOutcome outcome = interactions.useOnBlock(
                ActionHand.MAIN_HAND,
                target
        );
        if (!outcome.accepted()) {
            return fail(actionFailure("portal_light", outcome));
        }
        dispatchedObservationRevision =
                frames.core().observationRevision();
        beginPhase(Phase.VERIFY_LIGHT, context);
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyLight(
            final SkillContext context,
            final CastObservedNetherPortalParameters parameters,
            final Frames frames
    ) {
        if (!newObservation(frames)) {
            return SkillTickResult.running(false, false);
        }
        boolean portalVisible = false;
        for (VisibleBlockFace face
                : frames.interaction().visibleBlockFaces()) {
            if (!NETHER_PORTAL.equals(face.blockTypeId())) {
                continue;
            }
            final GridPos position = position(face);
            for (int u = 1; u <= 2; u++) {
                for (int v = 1; v <= 3; v++) {
                    if (position.equals(at(
                            parameters.anchor(),
                            parameters.axis(),
                            u,
                            v
                    ))) {
                        portalVisible = true;
                    }
                }
            }
        }
        final boolean damageUsed =
                FLINT_AND_STEEL.equals(
                    frames.core().mainHand().itemId()
                )
                    && frames.core().mainHand().damage()
                        == toolDamageBefore + 1;
        final boolean broke = inventoryCount(
                frames.core(),
                FLINT_AND_STEEL
        ) == countBefore - 1;
        if (!portalVisible || !damageUsed && !broke) {
            return fail(NAME + ".portal_activation_unverified");
        }
        return complete();
    }

    private Validation validate(
            final SkillContext context,
            final CastObservedNetherPortalParameters parameters,
            final boolean requireParameterSample
    ) {
        final Optional<CoreSkillFrame> coreFrame =
                coreFrames.current();
        final Optional<InteractionSkillFrame> interactionFrame =
                interactionFrames.current();
        final OptionalLong session = interactions.sessionGeneration();
        if (coreFrame.isEmpty()
                || interactionFrame.isEmpty()
                || session.isEmpty()) {
            return Validation.failed(NAME + ".body_unavailable");
        }
        final CoreSkillFrame coreFrameValue = coreFrame.orElseThrow();
        final InteractionSkillFrame interaction =
                interactionFrame.orElseThrow();
        if (!expectedPlayerId.equals(coreFrameValue.playerId())
                || !expectedPlayerId.equals(interaction.playerId())) {
            return Validation.failed(NAME + ".body_mismatch");
        }
        if (!parameters.dimension().equals(coreFrameValue.dimension())
                || !parameters.dimension().equals(
                    interaction.dimension()
                )) {
            return Validation.failed(NAME + ".wrong_dimension");
        }
        if (coreFrameValue.observationRevision()
                != interaction.observationRevision()) {
            return Validation.failed(
                    NAME + ".observation_sources_mismatch"
            );
        }
        if (requireParameterSample
                && parameters.sampleSequence()
                    != coreFrameValue.observationRevision()) {
            return Validation.failed(NAME + ".sample_mismatch");
        }
        if (interaction.observationAgeTicks()
                > MAXIMUM_OBSERVATION_AGE_TICKS) {
            return Validation.failed(NAME + ".stale_observation");
        }
        if (!coreFrameValue.onGround()
                || coreFrameValue.inWater()) {
            return Validation.failed(NAME + ".stable_ground_required");
        }
        if (phase.active()
                && boundSessionGeneration >= 0
                && (interaction.sessionGeneration()
                    != boundSessionGeneration
                    || session.orElseThrow()
                        != boundSessionGeneration)) {
            return Validation.failed(NAME + ".session_mismatch");
        }
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, coreFrameValue);
        if (unsafe.isPresent()) {
            return Validation.failed(unsafe.orElseThrow());
        }
        return Validation.available(new Frames(
                coreFrameValue,
                interaction
        ));
    }

    private Optional<SkillFailure> validateCompleteFrame(
            final CastObservedNetherPortalParameters parameters,
            final Frames frames
    ) {
        if (!parameters.dimension().equals(DimensionRef.OVERWORLD)
                && !parameters.dimension().equals(DimensionRef.NETHER)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".unsupported_dimension"
            ));
        }
        for (GridPos position : minimumFramePlan(
                parameters.anchor(),
                parameters.axis()
        )) {
            final Optional<String> observed =
                    observedBlockId(frames.interaction(), position);
            if (observed.isPresent()
                    && !OBSIDIAN.equals(observed.orElseThrow())) {
                return Optional.of(SkillFailure.of(
                        NAME + ".complete_frame_not_observed"
                ));
            }
        }
        for (int u = 1; u <= 2; u++) {
            for (int v = 1; v <= 3; v++) {
                final GridPos interior = at(
                        parameters.anchor(),
                        parameters.axis(),
                        u,
                        v
                );
                if (!freshVoxel(
                        frames.core(),
                        interior,
                        VoxelKind.AIR
                ) && observedBlockId(
                        frames.interaction(),
                        interior
                ).isPresent()) {
                    return Optional.of(SkillFailure.of(
                        NAME + ".portal_interior_not_clear"
                    ));
                }
            }
        }
        if (flammableNearby(
                frames.interaction(),
                at(
                    parameters.anchor(),
                    parameters.axis(),
                    1,
                    2
                )
        )) {
            return Optional.of(SkillFailure.of(
                    NAME + ".flammable_site"
            ));
        }
        if (inventoryCount(frames.core(), FLINT_AND_STEEL) < 1) {
            return Optional.of(SkillFailure.of(
                    NAME + ".flint_and_steel_required"
            ));
        }
        return Optional.empty();
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
        final double healthRatio = frame.health() / frame.maxHealth();
        final double minimumHealth = context.hardcore()
                ? HARDCORE_MINIMUM_HEALTH
                : NORMAL_MINIMUM_HEALTH;
        if (healthRatio < minimumHealth) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_reserve_required"
            ));
        }
        if (frame.foodLevel() < (context.hardcore() ? 12 : 8)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".food_reserve_required"
            ));
        }
        return Optional.empty();
    }

    private Optional<SkillTickResult> ensureEquipped(
            final CoreSkillFrame frame,
            final String itemId
    ) {
        if (itemId.equals(frame.mainHand().itemId())) {
            return Optional.empty();
        }
        final InventoryOperationResult result = inventory.equip(
                new EquipItemParameters(
                        itemId,
                        EquipmentTarget.MAINHAND
                )
        );
        if (!result.succeeded()) {
            return Optional.of(fail(
                    NAME + ".required_item_unavailable"
            ));
        }
        return Optional.of(SkillTickResult.running(true, true));
    }

    private SkillTickResult lookAndWait(
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        if (!core.move(MovementIntent.STOPPED).accepted()
                || !core.look(
                    lookAt(frame.eyePosition(), target)
                ).accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        return SkillTickResult.running(true, true);
    }

    private boolean aligned(
            final CoreSkillFrame frame,
            final PerceptionVec3 target
    ) {
        if (!core.move(MovementIntent.STOPPED).accepted()
                || !core.look(
                    lookAt(frame.eyePosition(), target)
                ).accepted()) {
            return false;
        }
        return angularError(
                frame.lookDirection(),
                target.subtract(frame.eyePosition())
        ) <= ALIGNMENT_DEGREES;
    }

    private boolean newObservation(final Frames frames) {
        return frames.core().observationRevision()
                > dispatchedObservationRevision;
    }

    private int phaseTimeout() {
        return switch (phase) {
            case WAIT_FOR_DRAIN -> MAXIMUM_DRAIN_TICKS;
            case SURVEY_LIGHT -> MAXIMUM_LIGHT_SURVEY_TICKS;
            default -> MAXIMUM_PHASE_TICKS;
        };
    }

    private void beginPhase(
            final Phase next,
            final SkillContext context
    ) {
        phase = next;
        phaseStartedAtTick = context.gameTick();
    }

    private SkillTickResult complete() {
        quiesce();
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private SkillTickResult fail(final String code) {
        return fail(SkillFailure.of(code));
    }

    private SkillTickResult fail(final SkillFailure reason) {
        quiesce();
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private void quiesce() {
        core.stop();
        core.releaseUse();
        interactions.releaseUse();
        interactions.abortMining();
        activeTarget = null;
    }

    private static Optional<String> chooseTemporaryMaterial(
            final CoreSkillFrame frame,
            final int count
    ) {
        if (count == 0) {
            return Optional.of(AIR);
        }
        return TEMPORARY_MATERIALS.stream()
                .filter(item -> inventoryCount(frame, item) >= count)
                .findFirst();
    }

    private static List<GridPos> formworkPlan(
            final GridPos target,
            final Frames frames
    ) {
        final LinkedHashSet<GridPos> result = new LinkedHashSet<>();
        result.add(target.below());
        addHorizontalRing(result, target);
        addHorizontalRing(result, target.above());
        /*
         * Leave the closest fully observed two-block air column open as the
         * player's legal interaction window. A closed two-high ring hides
         * every inward support face, so no vanilla client could place either
         * fluid into the center. Lava and water are dispatched back-to-back
         * well inside their vanilla flow delays; every other side and the
         * floor remain contained.
         */
        final List<GridPos> candidates = List.of(
                target.offset(1, 0, 0),
                target.offset(-1, 0, 0),
                target.offset(0, 0, 1),
                target.offset(0, 0, -1)
        );
        candidates.stream()
                .filter(position ->
                        siteCell(frames, position) == SiteCell.AIR
                )
                .filter(position ->
                        siteCell(
                                frames,
                                position.above()
                        ) == SiteCell.AIR
                )
                .min((left, right) -> Double.compare(
                        distance(
                                frames.core().eyePosition(),
                                center(left)
                        ),
                        distance(
                                frames.core().eyePosition(),
                                center(right)
                        )
                ))
                .ifPresent(access -> {
                    result.remove(access);
                    result.remove(access.above());
                });
        return List.copyOf(result);
    }

    private static void addHorizontalRing(
            final Set<GridPos> output,
            final GridPos center
    ) {
        output.add(center.offset(1, 0, 0));
        output.add(center.offset(-1, 0, 0));
        output.add(center.offset(0, 0, 1));
        output.add(center.offset(0, 0, -1));
    }

    static List<GridPos> minimumFramePlan(
            final GridPos anchor,
            final PortalBuildAxis axis
    ) {
        if (axis == PortalBuildAxis.AUTO) {
            throw new IllegalArgumentException(
                    "Minimum frame needs an explicit axis"
            );
        }
        return List.of(
                at(anchor, axis, 1, 0),
                at(anchor, axis, 2, 0),
                at(anchor, axis, 0, 1),
                at(anchor, axis, 0, 2),
                at(anchor, axis, 0, 3),
                at(anchor, axis, 3, 1),
                at(anchor, axis, 3, 2),
                at(anchor, axis, 3, 3),
                at(anchor, axis, 1, 4),
                at(anchor, axis, 2, 4)
        );
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

    private static SiteCell siteCell(
            final Frames frames,
            final GridPos position
    ) {
        if (freshVoxel(frames.core(), position, VoxelKind.AIR)) {
            return SiteCell.AIR;
        }
        final Optional<String> id = observedBlockId(
                frames.interaction(),
                position
        );
        if (id.isPresent()) {
            return SAFE_EXISTING_FORMWORK.contains(id.orElseThrow())
                    ? SiteCell.SAFE_SOLID
                    : SiteCell.UNSAFE_SOLID;
        }
        return SiteCell.UNKNOWN;
    }

    private void rememberSiteEvidence(final Frames frames) {
        final LinkedHashSet<GridPos> relevant = new LinkedHashSet<>();
        if (targetBlock != null) {
            relevant.add(targetBlock);
        }
        if (waterBlock != null) {
            relevant.add(waterBlock);
        }
        relevant.addAll(formworkPlan);
        relevant.addAll(lightFramePlan);
        for (GridPos position : relevant) {
            currentSiteEvidence(frames, position)
                    .ifPresent(evidence ->
                            siteMemory.put(position, evidence)
                    );
        }
    }

    private SiteCell rememberedSiteCell(
            final Frames frames,
            final GridPos position
    ) {
        return recentSiteEvidence(frames, position)
                .map(SiteEvidence::cell)
                .orElse(SiteCell.UNKNOWN);
    }

    private Optional<SiteEvidence> recentSiteEvidence(
            final Frames frames,
            final GridPos position
    ) {
        final SiteEvidence evidence = siteMemory.get(position);
        if (evidence == null
                || frames.core().gameTime()
                    < evidence.observedAtGameTime()
                || frames.core().gameTime()
                    - evidence.observedAtGameTime()
                    > MAXIMUM_SITE_MEMORY_TICKS) {
            return Optional.empty();
        }
        return Optional.of(evidence);
    }

    private static Optional<SiteEvidence> currentSiteEvidence(
            final Frames frames,
            final GridPos position
    ) {
        if (freshVoxel(frames.core(), position, VoxelKind.AIR)) {
            return Optional.of(new SiteEvidence(
                    SiteCell.AIR,
                    AIR,
                    frames.core().gameTime()
            ));
        }
        return observedBlockId(frames.interaction(), position)
                .map(blockId -> new SiteEvidence(
                        SAFE_EXISTING_FORMWORK.contains(blockId)
                                ? SiteCell.SAFE_SOLID
                                : SiteCell.UNSAFE_SOLID,
                        blockId,
                        frames.core().gameTime()
                ));
    }

    private static boolean freshVoxel(
            final CoreSkillFrame frame,
            final GridPos position,
            final VoxelKind kind
    ) {
        return frame.navigation().voxelAt(position)
                .filter(voxel ->
                        voxel.observationRevision()
                            == frame.observationRevision()
                )
                .map(ObservedVoxel::kind)
                .filter(observed -> observed == kind)
                .isPresent();
    }

    private static Optional<String> observedBlockId(
            final InteractionSkillFrame frame,
            final GridPos position
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> position(face).equals(position))
                .map(VisibleBlockFace::blockTypeId)
                .distinct()
                .findFirst();
    }

    private static Optional<VisibleBlockFace> visibleFace(
            final InteractionSkillFrame frame,
            final GridPos position
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> position(face).equals(position))
                .filter(face -> face.distance() <= MAXIMUM_REACH)
                .findFirst();
    }

    private static Optional<VisibleBlockFace> visibleFace(
            final InteractionSkillFrame frame,
            final GridPos position,
            final BlockFace required
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> position(face).equals(position))
                .filter(face -> face.distance() <= MAXIMUM_REACH)
                .filter(face -> parseFace(face.face())
                        .filter(parsed -> parsed == required)
                        .isPresent())
                .findFirst();
    }

    private static Optional<VisibleBlockFace> visibleFluidSource(
            final InteractionSkillFrame frame,
            final GridPos position,
            final String fluidId
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> position(face).equals(position))
                .filter(face -> fluidId.equals(face.blockTypeId()))
                .filter(face -> "0".equals(
                        face.stateProperties().get("level")
                ))
                .filter(face -> face.distance() <= MAXIMUM_REACH)
                .findFirst();
    }

    private static Optional<BlockInteractionTarget> visiblePlacementTarget(
            final InteractionSkillFrame frame,
            final GridPos desired
    ) {
        for (VisibleBlockFace visible : frame.visibleBlockFaces()) {
            if (visible.distance() > MAXIMUM_REACH) {
                continue;
            }
            /*
             * Filled buckets need a solid clicked support. A semantic fluid
             * surface can geometrically border the desired cell, but
             * BucketItem's vanilla Fluid.NONE ray passes through it and
             * would place against a different block.
             */
            if (LAVA.equals(visible.blockTypeId())
                    || WATER.equals(visible.blockTypeId())
                    || AIR.equals(visible.blockTypeId())) {
                continue;
            }
            final Optional<BlockFace> parsed =
                    parseFace(visible.face());
            if (parsed.isEmpty()) {
                continue;
            }
            if (offset(position(visible), parsed.orElseThrow())
                    .equals(desired)) {
                return Optional.of(insetObservedTarget(
                        visible,
                        parsed.orElseThrow()
                ));
            }
        }
        return Optional.empty();
    }

    private static BlockInteractionTarget insetObservedTarget(
            final VisibleBlockFace visible,
            final BlockFace face
    ) {
        final GridPos position = position(visible);
        final PerceptionVec3 observed = visible.hitPosition();
        final double x = switch (face) {
            case EAST -> position.x() + 1.0;
            case WEST -> position.x();
            default -> inset(observed.x(), position.x());
        };
        final double y = switch (face) {
            case UP -> position.y() + 1.0;
            case DOWN -> position.y();
            default -> inset(observed.y(), position.y());
        };
        final double z = switch (face) {
            case SOUTH -> position.z() + 1.0;
            case NORTH -> position.z();
            default -> inset(observed.z(), position.z());
        };
        return new BlockInteractionTarget(
                position.x(),
                position.y(),
                position.z(),
                face,
                new ActionVec3(x, y, z)
        );
    }

    private static double inset(
            final double coordinate,
            final int blockCoordinate
    ) {
        return Math.max(
                blockCoordinate + 0.125,
                Math.min(blockCoordinate + 0.875, coordinate)
        );
    }

    private static boolean flammableNearby(
            final InteractionSkillFrame frame,
            final GridPos center
    ) {
        return frame.visibleBlockFaces().stream()
                .filter(face -> chebyshev(
                        position(face),
                        center
                ) <= 3)
                .map(VisibleBlockFace::blockTypeId)
                .anyMatch(CastObservedNetherPortalSkill::flammable);
    }

    private static boolean flammable(final String itemId) {
        return itemId.endsWith("_planks")
                || itemId.endsWith("_log")
                || itemId.endsWith("_wood")
                || itemId.endsWith("_leaves")
                || itemId.endsWith("_wool")
                || itemId.endsWith("_carpet")
                || itemId.endsWith("_bed")
                || itemId.endsWith("_fence")
                || itemId.endsWith("_fence_gate")
                || itemId.endsWith("_door")
                || itemId.endsWith("_trapdoor")
                || itemId.contains("bamboo")
                || itemId.endsWith(":hay_block")
                || itemId.endsWith(":bookshelf")
                || itemId.endsWith(":chiseled_bookshelf")
                || itemId.endsWith(":chest")
                || itemId.endsWith(":barrel")
                || itemId.endsWith(":crafting_table")
                || itemId.endsWith(":scaffolding");
    }

    private static int chebyshev(
            final GridPos left,
            final GridPos right
    ) {
        return Math.max(
                Math.max(
                    Math.abs(left.x() - right.x()),
                    Math.abs(left.y() - right.y())
                ),
                Math.abs(left.z() - right.z())
        );
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

    private static GridPos position(final VisibleBlockFace face) {
        return new GridPos(
                face.block().x(),
                face.block().y(),
                face.block().z()
        );
    }

    private static BlockInteractionTarget target(
            final VisibleBlockFace face
    ) {
        final BlockFace parsed = parseFace(face.face())
                .orElseThrow();
        return new BlockInteractionTarget(
                face.block().x(),
                face.block().y(),
                face.block().z(),
                parsed,
                new ActionVec3(
                        face.hitPosition().x(),
                        face.hitPosition().y(),
                        face.hitPosition().z()
                )
        );
    }

    private static Optional<BlockFace> parseFace(
            final String value
    ) {
        final int separator = value.lastIndexOf(':');
        final String name = separator < 0
                ? value
                : value.substring(separator + 1);
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
        return switch (face) {
            case DOWN -> position.offset(0, -1, 0);
            case UP -> position.offset(0, 1, 0);
            case NORTH -> position.offset(0, 0, -1);
            case SOUTH -> position.offset(0, 0, 1);
            case WEST -> position.offset(-1, 0, 0);
            case EAST -> position.offset(1, 0, 0);
        };
    }

    private static PerceptionVec3 center(final GridPos position) {
        return new PerceptionVec3(
                position.x() + 0.5,
                position.y() + 0.5,
                position.z() + 0.5
        );
    }

    private static PerceptionVec3 topCenter(final GridPos position) {
        return new PerceptionVec3(
                position.x() + 0.5,
                position.y() + 1.0,
                position.z() + 0.5
        );
    }

    private static PerceptionVec3 placementSupportAim(
            final PerceptionVec3 eye,
            final GridPos position
    ) {
        return new PerceptionVec3(
                inset(eye.x(), position.x()),
                position.y(),
                inset(eye.z(), position.z())
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

    private static String actionFailure(
            final String operation,
            final ActionOutcome outcome
    ) {
        return NAME + "." + operation + "_"
                + outcome.name().toLowerCase(Locale.ROOT);
    }

    private enum SiteCell {
        AIR,
        SAFE_SOLID,
        UNSAFE_SOLID,
        UNKNOWN
    }

    private record SiteEvidence(
            SiteCell cell,
            String blockId,
            long observedAtGameTime
    ) {
        private SiteEvidence {
            Objects.requireNonNull(cell, "cell");
            Objects.requireNonNull(blockId, "blockId");
            if (observedAtGameTime < 0) {
                throw new IllegalArgumentException(
                        "Site evidence time must be non-negative"
                );
            }
        }
    }

    private enum Phase {
        IDLE,
        READY_COMPLETE,
        SURVEY_LIGHT,
        PREPARE_FORMWORK,
        VERIFY_FORMWORK,
        COLLECT_LAVA,
        VERIFY_LAVA_COLLECTION,
        PLACE_LAVA,
        VERIFY_LAVA,
        PLACE_WATER,
        VERIFY_CAST,
        COLLECT_WATER,
        VERIFY_WATER_COLLECTION,
        WAIT_FOR_DRAIN,
        CLEAN_FORMWORK,
        MINING_FORMWORK,
        VERIFY_CLEANUP,
        LIGHT,
        VERIFY_LIGHT,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return switch (this) {
                case READY_COMPLETE,
                        SURVEY_LIGHT,
                        PREPARE_FORMWORK,
                        VERIFY_FORMWORK,
                        COLLECT_LAVA,
                        VERIFY_LAVA_COLLECTION,
                        PLACE_LAVA,
                        VERIFY_LAVA,
                        PLACE_WATER,
                        VERIFY_CAST,
                        COLLECT_WATER,
                        VERIFY_WATER_COLLECTION,
                        WAIT_FOR_DRAIN,
                        CLEAN_FORMWORK,
                        MINING_FORMWORK,
                        VERIFY_CLEANUP,
                        LIGHT,
                        VERIFY_LIGHT -> true;
                default -> false;
            };
        }
    }

    private record Frames(
            CoreSkillFrame core,
            InteractionSkillFrame interaction
    ) {
        private Frames {
            Objects.requireNonNull(core, "core");
            Objects.requireNonNull(interaction, "interaction");
        }
    }

    private record Validation(
            Optional<Frames> frames,
            Optional<SkillFailure> failure
    ) {
        private static Validation available(final Frames frames) {
            return new Validation(
                    Optional.of(frames),
                    Optional.empty()
            );
        }

        private static Validation failed(final String code) {
            return failed(SkillFailure.of(code));
        }

        private static Validation failed(
                final SkillFailure failure
        ) {
            return new Validation(
                    Optional.empty(),
                    Optional.of(failure)
            );
        }
    }

    private record Preparation(
            Phase initialPhase,
            Optional<SkillFailure> failure
    ) {
        private static Preparation ready(final Phase phase) {
            return new Preparation(
                    phase,
                    Optional.empty()
            );
        }

        private static Preparation failed(final String code) {
            return failed(SkillFailure.of(code));
        }

        private static Preparation failed(
                final SkillFailure failure
        ) {
            return new Preparation(
                    Phase.FAILED,
                    Optional.of(failure)
            );
        }
    }
}
