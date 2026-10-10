package dev.mcai.companion.skills.portal;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.ActionVec3;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.BlockInteractionTarget;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.navigation.GridPos;
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
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.core.TravelToParameters;
import dev.mcai.companion.skills.core.TravelToSkill;
import dev.mcai.companion.skills.inventory.EquipItemParameters;
import dev.mcai.companion.skills.inventory.EquipmentTarget;
import dev.mcai.companion.skills.inventory.InventoryOperationResult;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Fills the canonical twelve-block End portal ring through ordinary
 * first-person interactions. It walks between observed approach stations and
 * never reads the stronghold, portal frame, or portal interior from the level.
 */
public final class ActivateObservedEndPortalSkill
        implements Skill<NoParameters> {
    public static final String NAME =
            "activate_observed_end_portal";

    private static final String END_PORTAL_FRAME =
            "minecraft:end_portal_frame";
    private static final String END_PORTAL =
            "minecraft:end_portal";
    private static final String ENDER_EYE =
            "minecraft:ender_eye";
    /*
     * Player.blockInteractionRange is 4.5 blocks in the survival fixture.
     * VisibleBlockFace.distance is measured to the sampled hit point, while
     * vanilla validates the closest point of the block AABB.  A small
     * fail-closed margin prevents a 4.5..4.7 semantic hit from being selected
     * and then rejected forever by the authoritative player range check.
     */
    private static final double MAXIMUM_INTERACTION_DISTANCE = 4.45;
    private static final double ALIGNMENT_DEGREES = 3.0;
    private static final double NORMAL_MAXIMUM_DANGER = 0.12;
    private static final double HARDCORE_MAXIMUM_DANGER = 0.04;
    private static final int MAXIMUM_VERIFY_TICKS = 80;
    private static final int MINIMUM_STATION_ORIENTATION_TICKS = 4;
    private static final int MAXIMUM_STATION_ORIENTATION_TICKS = 40;
    private static final int MAXIMUM_TARGET_ALIGNMENT_TICKS = 120;
    private static final int MAXIMUM_INTERACTION_REJECTIONS = 8;
    private static final int MAXIMUM_STATION_VISITS = 12;
    private static final int MAXIMUM_TOTAL_TICKS = 2_400;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator core;
    private final CoreSkillFrameSource frames;
    private final InventorySkillActuator inventory;
    private final LongSupplier sessionGeneration;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long startedAtTick = -1;
    private long phaseStartedAtTick = -1;
    private long phaseObservationRevision = -1;
    private long lastObservationRevision = -1;
    private int eyesInserted;
    private int eyeCountBefore;
    private int stationIndex;
    private int stationVisits;
    private int consecutiveInteractionRejections;
    private ActionOutcome lastInteractionOutcome =
            ActionOutcome.NO_ACTIVE_ACTION;
    private long targetAlignmentStartedAtTick;
    private GridPos alignmentTarget;
    private PerceptionVec3 alignmentAimPoint;
    private BlockInteractionTarget alignmentInteraction;
    private GridPos pendingFrame;
    private TravelToSkill travel;
    private TravelToParameters travelParameters;
    private ActivateEndPortalParameters resolvedParameters;

    public ActivateObservedEndPortalSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator core,
            final CoreSkillFrameSource frames,
            final InventorySkillActuator inventory,
            final LongSupplier sessionGeneration
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.core = Objects.requireNonNull(core, "core");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.inventory = Objects.requireNonNull(
                inventory,
                "inventory"
        );
        this.sessionGeneration = Objects.requireNonNull(
                sessionGeneration,
                "sessionGeneration"
        );
    }

    @Override
    public SkillParameterParser<NoParameters> parameters() {
        return arguments -> arguments != null && arguments.isEmpty()
                ? dev.mcai.companion.skill.SkillParameterResult.valid(
                        NoParameters.INSTANCE
                )
                : dev.mcai.companion.skill.SkillParameterResult.invalid(
                        NAME + ".invalid_arguments"
                );
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final FrameValidation validation = validateFrame();
        if (validation.failure().isPresent()) {
            return validation.failure();
        }
        final CoreSkillFrame frame = validation.frame().orElseThrow();
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return unsafe;
        }
        if (!DimensionRef.OVERWORLD.equals(frame.dimension())) {
            return Optional.of(SkillFailure.of(
                    NAME + ".overworld_required"
            ));
        }
        if (!frame.onGround() || frame.inWater()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".stable_ground_required"
            ));
        }
        if (inventoryCount(frame, ENDER_EYE) < 1) {
            return Optional.of(SkillFailure.of(
                    NAME + ".ender_eye_required"
            ));
        }
        final Optional<GridPos> center =
                ObservedEndPortalGeometry.uniqueCenter(
                        frame.visibleBlockFaces()
                );
        if (center.isEmpty()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".unique_visible_portal_center_required"
            ));
        }
        final ActivateEndPortalParameters resolved =
                resolvedParameters(frame, center.orElseThrow());
        if (visibleRingFrames(frame, resolved).isEmpty()) {
            return Optional.of(SkillFailure.of(
                    NAME + ".visible_portal_frame_required"
            ));
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final CoreSkillFrame frame = validateFrame()
                .frame()
                .orElseThrow(() -> new IllegalStateException(
                        "End portal body changed before start"
                ));
        final GridPos center = ObservedEndPortalGeometry.uniqueCenter(
                frame.visibleBlockFaces()
        ).orElseThrow(() -> new IllegalStateException(
                "End portal center changed before start"
        ));
        resolvedParameters = resolvedParameters(frame, center);
        final ActivateEndPortalParameters resolved =
                resolvedParameters;
        phase = Phase.SEARCHING;
        failure = null;
        startedAtTick = context.gameTick();
        phaseStartedAtTick = context.gameTick();
        phaseObservationRevision = frame.observationRevision();
        lastObservationRevision = -1;
        eyesInserted = 0;
        eyeCountBefore = -1;
        stationIndex = nearestStation(frame, resolved);
        /*
         * startNextStation advances before travelling. If the initially
         * observed frames are outside interaction range, seed it one station
         * behind so the first trip is to the nearest approach rather than
         * needlessly skipping to the next side of the ring.
         */
        if (visibleEmptyReachableFrame(frame, resolved).isEmpty()) {
            stationIndex = Math.floorMod(stationIndex - 1, 4);
        }
        stationVisits = 0;
        consecutiveInteractionRejections = 0;
        lastInteractionOutcome = ActionOutcome.NO_ACTIVE_ACTION;
        targetAlignmentStartedAtTick = context.gameTick();
        alignmentTarget = null;
        alignmentAimPoint = null;
        alignmentInteraction = null;
        pendingFrame = null;
        travel = null;
        travelParameters = null;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final NoParameters parameters
    ) {
        if (!phase.active()) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        try {
            return tickSafely(context, activeParameters());
        } catch (RuntimeException exception) {
            return fail(context, NAME + ".internal_failure");
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final ActivateEndPortalParameters resolved =
                activeParameters();
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                            + "\"centerX\":%d,\"centerY\":%d,"
                            + "\"centerZ\":%d,\"eyesInserted\":%d,"
                            + "\"stationVisits\":%d,"
                            + "\"interactionRejections\":%d,"
                            + "\"lastInteractionOutcome\":\"%s\","
                            + "\"alignmentTarget\":\"%s\"}",
                        phase.name(),
                        resolved.dimension().id(),
                        resolved.centerX(),
                        resolved.centerY(),
                        resolved.centerZ(),
                        eyesInserted,
                        stationVisits,
                        consecutiveInteractionRejections,
                        lastInteractionOutcome.name(),
                        alignmentTarget == null
                                ? ""
                                : alignmentTarget.toString()
                )
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        cancelTravel(context);
        quiesce();
        phase = Phase.CANCELLED;
        pendingFrame = null;
    }

    @Override
    public SkillResult result(
            final SkillContext context,
            final NoParameters parameters
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
            final ActivateEndPortalParameters parameters
    ) {
        if (context.gameTick() - startedAtTick > MAXIMUM_TOTAL_TICKS) {
            return fail(context, NAME + ".timed_out");
        }
        final FrameValidation validation = validateFrame();
        if (validation.failure().isPresent()) {
            return fail(
                    context,
                    validation.failure().orElseThrow()
            );
        }
        final CoreSkillFrame frame = validation.frame().orElseThrow();
        if (!parameters.dimension().equals(frame.dimension())) {
            return fail(context, NAME + ".wrong_dimension");
        }
        if (frame.observationRevision() < lastObservationRevision) {
            return fail(context, NAME + ".stale_observation");
        }
        final boolean fresh = frame.observationRevision()
                > lastObservationRevision;
        lastObservationRevision = Math.max(
                lastObservationRevision,
                frame.observationRevision()
        );
        final Optional<SkillFailure> unsafe =
                safetyFailure(context, frame);
        if (unsafe.isPresent()) {
            return fail(context, unsafe.orElseThrow());
        }
        /*
         * The final Eye creates the portal in the same vanilla interaction
         * that consumes the item. Do not let the newly visible portal skip
         * VERIFYING, otherwise the last inventory delta is never checked and
         * the checkpoint under-counts a successful twelve-frame activation.
         */
        if (portalVisible(frame, parameters)
                && phase != Phase.VERIFYING) {
            return complete(context);
        }
        return switch (phase) {
            case SEARCHING -> search(
                    context,
                    parameters,
                    frame,
                    fresh
            );
            case VERIFYING -> verifyEye(
                    context,
                    parameters,
                    frame,
                    fresh
            );
            case ORIENTING -> orientAtStation(
                    context,
                    parameters,
                    frame,
                    fresh
            );
            case TRAVELLING -> tickTravel(
                    context,
                    parameters,
                    frame,
                    fresh
            );
            default -> SkillTickResult.failed(
                    NAME + ".invalid_state"
            );
        };
    }

    private SkillTickResult search(
            final SkillContext context,
            final ActivateEndPortalParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (alignmentTarget != null
                && visibleRingFrames(frame, parameters).stream()
                    .filter(face -> sameBlock(
                            face,
                            alignmentTarget
                    ))
                    .anyMatch(
                            ActivateObservedEndPortalSkill::hasEye
                    )) {
            /*
             * A post-interaction observation may briefly retain the old
             * alignment target. Once that exact frame is visibly filled,
             * release it before selecting another empty frame.
             */
            resetAlignment(context);
        }
        final Optional<VisibleBlockFace> visible =
                visibleEmptyReachableFrame(frame, parameters);
        if (visible.isEmpty()) {
            if (alignmentTarget != null
                    && context.gameTick()
                        - targetAlignmentStartedAtTick
                        <= MAXIMUM_TARGET_ALIGNMENT_TICKS) {
                /*
                 * Turning toward a frame can temporarily remove its sampled
                 * surface from the semantic observation for one or more
                 * revisions. Keep looking at the last fair ray-hit instead
                 * of immediately abandoning the target and walking around
                 * the ring. The block is never interacted with while hidden;
                 * a fresh visible face is still required below.
                 */
                final PerceptionVec3 aim =
                        alignmentAimPoint == null
                            ? center(alignmentTarget)
                            : alignmentAimPoint;
                if (!holdAndLook(frame, aim)) {
                    return fail(
                            context,
                            NAME + ".actuator_rejected"
                    );
                }
                /*
                 * The target was already a fresh first-person hit. The
                 * server actuator still performs the vanilla crosshair and
                 * reach check, so it is safe to retry this exact packet when
                 * the semantic ray fan is temporarily one face away. This
                 * removes a camera-sampling gap without granting a hidden
                 * block interaction.
                 */
                if (alignmentInteraction != null
                        && ENDER_EYE.equals(frame.mainHand().itemId())
                        && angularError(
                                frame.lookDirection(),
                                aim.subtract(frame.eyePosition())
                        ) <= ALIGNMENT_DEGREES) {
                    eyeCountBefore = inventoryCount(frame, ENDER_EYE);
                    pendingFrame = alignmentTarget;
                    final ActionOutcome used = core.useMainHandOn(
                            alignmentInteraction
                    );
                    lastInteractionOutcome = used;
                    if (used.accepted()) {
                        consecutiveInteractionRejections = 0;
                        resetAlignment(context);
                        phase = Phase.VERIFYING;
                        phaseStartedAtTick = context.gameTick();
                        phaseObservationRevision =
                                frame.observationRevision();
                        return SkillTickResult.running(true, false);
                    }
                    if (used == ActionOutcome.TARGET_OUT_OF_REACH
                            || used == ActionOutcome.TARGET_CHANGED
                            || used == ActionOutcome.TARGET_OCCLUDED) {
                        /*
                         * The remembered ray hit is no longer a legal
                         * crosshair target after the body moved. Do not
                         * replay it eight times; discard it and obtain a
                         * newer visible frame from the current pose.
                         */
                        pendingFrame = null;
                        resetAlignment(context);
                        phase = Phase.SEARCHING;
                        phaseStartedAtTick = context.gameTick();
                        phaseObservationRevision =
                                frame.observationRevision();
                        return SkillTickResult.running(true, true);
                    }
                    pendingFrame = null;
                    consecutiveInteractionRejections++;
                    if (consecutiveInteractionRejections
                            >= MAXIMUM_INTERACTION_REJECTIONS) {
                        resetAlignment(context);
                        return startNextStation(
                                context,
                                parameters,
                                frame,
                                fresh
                        );
                    }
                }
                return SkillTickResult.running(fresh, true);
            }
            resetAlignment(context);
            if (inventoryCount(frame, ENDER_EYE) < 1) {
                return fail(
                        context,
                        NAME + ".ender_eyes_exhausted"
                );
            }
            return startNextStation(
                    context,
                    parameters,
                    frame,
                    fresh
            );
        }
        if (!ENDER_EYE.equals(frame.mainHand().itemId())) {
            final InventoryOperationResult equipped =
                    inventory.equip(new EquipItemParameters(
                            ENDER_EYE,
                            EquipmentTarget.MAINHAND
                    ));
            if (!equipped.succeeded()) {
                return fail(
                        context,
                        equipped.failure().orElseThrow()
                );
            }
            return SkillTickResult.running(true, true);
        }
        final VisibleBlockFace target = visible.orElseThrow();
        final GridPos targetPosition = new GridPos(
                target.block().x(),
                target.block().y(),
                target.block().z()
        );
        final boolean newAlignmentTarget =
                !targetPosition.equals(alignmentTarget);
        if (newAlignmentTarget) {
            alignmentTarget = targetPosition;
            targetAlignmentStartedAtTick = context.gameTick();
            /*
             * A semantic ray fan may report several faces of one frame as
             * the camera turns.  Keep the first fair hit for this alignment
             * instead of chasing a moving north/up/east hit point every
             * tick.  The actuator will still replay the ordinary vanilla
             * crosshair, face, range and world-permission checks.
             */
            alignmentAimPoint = target.hitPosition();
            alignmentInteraction = blockTarget(target);
        } else if (alignmentInteraction == null
                || alignmentAimPoint == null) {
            alignmentAimPoint = target.hitPosition();
            alignmentInteraction = blockTarget(target);
        } else if (alignmentInteraction.face() != BlockFace.UP
                && isTopFace(target)) {
            /* A side ray can be a fair first observation but may be hidden
             * by the neighbouring frame once the body settles. Upgrade only
             * to a later fair top hit of this same block; never synthesize a
             * new point or bypass the actuator's vanilla ray replay. */
            alignmentAimPoint = target.hitPosition();
            alignmentInteraction = blockTarget(target);
        }
        final PerceptionVec3 hit = alignmentAimPoint;
        if (!holdAndLook(frame, hit)) {
            return fail(context, NAME + ".actuator_rejected");
        }
        if (angularError(
                frame.lookDirection(),
                hit.subtract(frame.eyePosition())
        ) > ALIGNMENT_DEGREES) {
            if (context.gameTick() - targetAlignmentStartedAtTick
                    > MAXIMUM_TARGET_ALIGNMENT_TICKS) {
                resetAlignment(context);
                return startNextStation(
                        context,
                        parameters,
                        frame,
                        fresh
                );
            }
            return SkillTickResult.running(true, true);
        }
        eyeCountBefore = inventoryCount(frame, ENDER_EYE);
        pendingFrame = new GridPos(
                target.block().x(),
                target.block().y(),
                target.block().z()
        );
        /*
         * Reuse the exact fair hit selected when this alignment began.  The
         * semantic ray fan can expose another face of the same frame while
         * the body is turning; sending that newer face would make the
         * vanilla server validate a different crosshair target than the one
         * we actually aligned to.  A top-face upgrade above intentionally
         * replaces this stored interaction when it is freshly observed.
         */
        final BlockInteractionTarget interaction =
                alignmentInteraction == null
                    ? blockTarget(target)
                    : alignmentInteraction;
        final ActionOutcome used = core.useMainHandOn(
                interaction
        );
        lastInteractionOutcome = used;
        if (!used.accepted()) {
            pendingFrame = null;
            if (used == ActionOutcome.TARGET_OUT_OF_REACH
                    || used == ActionOutcome.TARGET_CHANGED
                    || used == ActionOutcome.TARGET_OCCLUDED) {
                resetAlignment(context);
                phase = Phase.SEARCHING;
                phaseStartedAtTick = context.gameTick();
                phaseObservationRevision = frame.observationRevision();
                return SkillTickResult.running(true, true);
            }
            consecutiveInteractionRejections++;
            if (consecutiveInteractionRejections
                    >= MAXIMUM_INTERACTION_REJECTIONS) {
                resetAlignment(context);
                return startNextStation(
                        context,
                        parameters,
                        frame,
                        fresh
                );
            }
            return SkillTickResult.running(fresh, true);
        }
        consecutiveInteractionRejections = 0;
        resetAlignment(context);
        phase = Phase.VERIFYING;
        phaseStartedAtTick = context.gameTick();
        phaseObservationRevision = frame.observationRevision();
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult verifyEye(
            final SkillContext context,
            final ActivateEndPortalParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (!holdAndLook(frame, center(pendingFrame))) {
            return fail(context, NAME + ".actuator_rejected");
        }
        if (frame.observationRevision() > phaseObservationRevision) {
            final boolean eyePresent =
                    visibleRingFrames(frame, parameters).stream()
                        .filter(face -> sameBlock(face, pendingFrame))
                        .anyMatch(ActivateObservedEndPortalSkill::hasEye);
            if (eyePresent || portalVisible(frame, parameters)) {
                final int after = inventoryCount(frame, ENDER_EYE);
                if (eyeCountBefore - after != 1) {
                    return fail(
                            context,
                            NAME + ".eye_consumption_unverified"
                    );
                }
                return commitEyeConsumption(
                        context,
                        parameters,
                        frame
                );
            }
        }
        if (context.gameTick() - phaseStartedAtTick
                > MAXIMUM_VERIFY_TICKS) {
            /*
             * A real vanilla use packet can consume the eye before the
             * finite semantic ray fan includes that frame again.  The
             * actuator has already proved the exact first-person target and
             * the current fair inventory is authoritative for the owned
             * item transaction.  Treat exactly one observed eye decrement as
             * the transaction proof, then continue from a fresh station
             * search; never read the world or invent a frame state here.
             */
            final int after = inventoryCount(frame, ENDER_EYE);
            if (eyeCountBefore >= 0
                    && eyeCountBefore - after == 1) {
                return commitEyeConsumption(
                        context,
                        parameters,
                        frame
                );
            }
            /* A dispatched packet that did not consume an eye is not a
             * completed placement.  Abandon only this stale target and let
             * the normal bounded station search obtain a new fair ray hit. */
            if (eyeCountBefore >= 0 && eyeCountBefore == after) {
                pendingFrame = null;
                resetAlignment(context);
                phase = Phase.SEARCHING;
                phaseStartedAtTick = context.gameTick();
                phaseObservationRevision = frame.observationRevision();
                return startNextStation(
                        context,
                        parameters,
                        frame,
                        fresh
                );
            }
            return fail(
                    context,
                    NAME + ".frame_activation_unverified"
            );
        }
        return SkillTickResult.running(fresh, false);
    }

    private SkillTickResult commitEyeConsumption(
            final SkillContext context,
            final ActivateEndPortalParameters parameters,
            final CoreSkillFrame frame
    ) {
        eyesInserted++;
        pendingFrame = null;
        if (portalVisible(frame, parameters)) {
            return complete(context);
        }
        phase = Phase.SEARCHING;
        phaseStartedAtTick = context.gameTick();
        phaseObservationRevision = frame.observationRevision();
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult startNextStation(
            final SkillContext context,
            final ActivateEndPortalParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        if (stationVisits >= MAXIMUM_STATION_VISITS) {
            return fail(
                    context,
                    NAME + ".portal_not_observed"
            );
        }
        stationIndex = (stationIndex + 1) % 4;
        resetAlignment(context);
        consecutiveInteractionRejections = 0;
        final PerceptionVec3 station =
                stations(parameters).get(stationIndex);
        travelParameters = new TravelToParameters(
                parameters.dimension(),
                station.x(),
                station.y(),
                station.z(),
                0.85
        );
        travel = new TravelToSkill(
                expectedPlayerId,
                core,
                frames,
                sessionGeneration
        );
        final Optional<SkillFailure> precondition =
                travel.preconditions(context, travelParameters);
        if (precondition.isPresent()) {
            travel = null;
            travelParameters = null;
            stationVisits++;
            return SkillTickResult.running(true, true);
        }
        travel.start(context, travelParameters);
        stationVisits++;
        phase = Phase.TRAVELLING;
        return SkillTickResult.running(fresh, true);
    }

    private SkillTickResult tickTravel(
            final SkillContext context,
            final ActivateEndPortalParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        final SkillTickResult result =
                travel.tick(context, travelParameters);
        if (result.status() == SkillTickResult.Status.COMPLETED) {
            travel = null;
            travelParameters = null;
            /*
             * Travel leaves the body looking along the route tangent. An
             * immediate semantic search from that pose often sees no portal
             * frames and skips straight to another station. Face the portal
             * and wait for a post-turn observation before deciding that this
             * side contains no reachable empty frame.
             */
            phase = Phase.ORIENTING;
            phaseStartedAtTick = context.gameTick();
            phaseObservationRevision = frame.observationRevision();
            return SkillTickResult.running(true, true);
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            travel = null;
            travelParameters = null;
            phase = Phase.SEARCHING;
            return SkillTickResult.running(true, true);
        }
        return SkillTickResult.running(
                result.madeProgress() || fresh,
                result.safeCheckpoint()
        );
    }

    private SkillTickResult orientAtStation(
            final SkillContext context,
            final ActivateEndPortalParameters parameters,
            final CoreSkillFrame frame,
            final boolean fresh
    ) {
        final PerceptionVec3 focus = center(parameters.center());
        if (!holdAndLook(frame, focus)) {
            return fail(context, NAME + ".actuator_rejected");
        }
        final long elapsed =
                context.gameTick() - phaseStartedAtTick;
        final boolean aligned = angularError(
                frame.lookDirection(),
                focus.subtract(frame.eyePosition())
        ) <= ALIGNMENT_DEGREES;
        final boolean observedAfterTurn =
                frame.observationRevision() > phaseObservationRevision;
        if (aligned
                && observedAfterTurn
                && elapsed >= MINIMUM_STATION_ORIENTATION_TICKS) {
            phase = Phase.SEARCHING;
            phaseStartedAtTick = context.gameTick();
            phaseObservationRevision = frame.observationRevision();
            return SkillTickResult.running(true, true);
        }
        if (elapsed > MAXIMUM_STATION_ORIENTATION_TICKS) {
            return fail(
                    context,
                    NAME + ".station_orientation_timed_out"
            );
        }
        return SkillTickResult.running(fresh, true);
    }

    private Optional<VisibleBlockFace> visibleEmptyReachableFrame(
            final CoreSkillFrame frame,
            final ActivateEndPortalParameters parameters
    ) {
        final List<VisibleBlockFace> candidates =
                visibleRingFrames(frame, parameters).stream()
                .filter(face -> !hasEye(face))
                .filter(face ->
                        face.distance()
                            <= MAXIMUM_INTERACTION_DISTANCE
                )
                .toList();
        if (alignmentTarget != null) {
            final Optional<VisibleBlockFace> stable =
                candidates.stream()
                        .filter(face -> sameBlock(
                                face,
                                alignmentTarget
                        ))
                        .min(Comparator
                                .comparing((VisibleBlockFace face) ->
                                        isTopFace(face) ? 0 : 1)
                                .thenComparingDouble(
                                        VisibleBlockFace::distance
                                ));
            if (stable.isPresent()) {
                return stable;
            }
            return Optional.empty();
        }
        return candidates.stream()
                /*
                 * EndPortalFrameBlock accepts the eye through its top
                 * outline. Prefer a freshly observed top hit when available;
                 * side hits remain a legal fallback and are still replayed
                 * through the exact fair actuator target.
                 */
                .min(Comparator
                        .comparing((VisibleBlockFace face) ->
                                isTopFace(face) ? 0 : 1)
                        .thenComparingDouble(
                                VisibleBlockFace::distance
                        ));
    }

    private static boolean isTopFace(final VisibleBlockFace face) {
        return "up".equalsIgnoreCase(face.face())
                || "minecraft:up".equalsIgnoreCase(face.face());
    }

    private static List<VisibleBlockFace> visibleRingFrames(
            final CoreSkillFrame frame,
            final ActivateEndPortalParameters parameters
    ) {
        final Set<GridPos> ring = ring(parameters.center());
        final List<VisibleBlockFace> result = new ArrayList<>();
        for (VisibleBlockFace face : frame.visibleBlockFaces()) {
            if (!END_PORTAL_FRAME.equals(face.blockTypeId())) {
                continue;
            }
            final GridPos position = new GridPos(
                    face.block().x(),
                    face.block().y(),
                    face.block().z()
            );
            if (ring.contains(position)) {
                result.add(face);
            }
        }
        return List.copyOf(result);
    }

    private static boolean portalVisible(
            final CoreSkillFrame frame,
            final ActivateEndPortalParameters parameters
    ) {
        final GridPos center = parameters.center();
        return frame.visibleBlockFaces().stream()
                .filter(face -> END_PORTAL.equals(face.blockTypeId()))
                .anyMatch(face ->
                        face.block().y() == center.y()
                            && Math.abs(
                                face.block().x() - center.x()
                            ) <= 1
                            && Math.abs(
                                face.block().z() - center.z()
                            ) <= 1
                );
    }

    private static boolean hasEye(final VisibleBlockFace face) {
        return "true".equals(face.stateProperties().get("eye"));
    }

    private static boolean sameBlock(
            final VisibleBlockFace face,
            final GridPos position
    ) {
        return position != null
                && face.block().x() == position.x()
                && face.block().y() == position.y()
                && face.block().z() == position.z();
    }

    private Optional<SkillFailure> safetyFailure(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        final double maximumDanger = context.hardcore()
                ? HARDCORE_MAXIMUM_DANGER
                : NORMAL_MAXIMUM_DANGER;
        if (Math.max(context.riskScore(), frame.danger())
                > maximumDanger) {
            return Optional.of(SkillFailure.of(
                    NAME + ".danger_detected"
            ));
        }
        final double minimumHealth = context.hardcore()
                ? 0.90
                : 0.60;
        if (frame.health() / frame.maxHealth() < minimumHealth) {
            return Optional.of(SkillFailure.of(
                    NAME + ".health_reserve_required"
            ));
        }
        if (frame.foodLevel() < (context.hardcore() ? 10 : 5)) {
            return Optional.of(SkillFailure.of(
                    NAME + ".food_reserve_required"
            ));
        }
        return Optional.empty();
    }

    private FrameValidation validateFrame() {
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
        return FrameValidation.available(frame);
    }

    private static ActivateEndPortalParameters resolvedParameters(
            final CoreSkillFrame frame,
            final GridPos center
    ) {
        return new ActivateEndPortalParameters(
                frame.dimension(),
                center.x(),
                center.y(),
                center.z()
        );
    }

    private ActivateEndPortalParameters activeParameters() {
        if (resolvedParameters == null) {
            throw new IllegalStateException(
                    "End portal center has not been resolved"
            );
        }
        return resolvedParameters;
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

    private SkillTickResult complete(final SkillContext context) {
        cancelTravel(context);
        quiesce();
        pendingFrame = null;
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private SkillTickResult fail(
            final SkillContext context,
            final String code
    ) {
        return fail(context, SkillFailure.of(code));
    }

    private SkillTickResult fail(
            final SkillContext context,
            final SkillFailure reason
    ) {
        cancelTravel(context);
        quiesce();
        pendingFrame = null;
        failure = reason;
        phase = Phase.FAILED;
        return SkillTickResult.failed(reason);
    }

    private void cancelTravel(final SkillContext context) {
        if (travel == null || travelParameters == null) {
            return;
        }
        try {
            travel.cancel(context, travelParameters);
        } catch (RuntimeException ignored) {
            core.stop();
        }
        travel = null;
        travelParameters = null;
    }

    private void quiesce() {
        core.stop();
        core.releaseUse();
    }

    private void resetAlignment(final SkillContext context) {
        alignmentTarget = null;
        alignmentAimPoint = null;
        alignmentInteraction = null;
        targetAlignmentStartedAtTick = context.gameTick();
    }

    private static int nearestStation(
            final CoreSkillFrame frame,
            final ActivateEndPortalParameters parameters
    ) {
        final List<PerceptionVec3> stations = stations(parameters);
        int nearest = 0;
        double distance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < stations.size(); index++) {
            final double candidate = frame.position()
                    .subtract(stations.get(index))
                    .lengthSquared();
            if (candidate < distance) {
                nearest = index;
                distance = candidate;
            }
        }
        return nearest;
    }

    private static List<PerceptionVec3> stations(
            final ActivateEndPortalParameters parameters
    ) {
        final GridPos center = parameters.center();
        return List.of(
                new PerceptionVec3(
                        center.x() + 0.5,
                        center.y(),
                        center.z() - 3.0
                ),
                new PerceptionVec3(
                        /* The portal-room wall begins at +4 in the
                         * controlled stronghold fixture; +3 is the last
                         * normal standable approach cell. */
                        center.x() + 3.0,
                        center.y(),
                        center.z() + 0.5
                ),
                new PerceptionVec3(
                        center.x() + 0.5,
                        center.y(),
                        center.z() + 4.0
                ),
                new PerceptionVec3(
                        center.x() - 3.0,
                        center.y(),
                        center.z() + 0.5
                )
        );
    }

    private static Set<GridPos> ring(final GridPos center) {
        final java.util.HashSet<GridPos> result =
                new java.util.HashSet<>(12);
        for (int offset = -1; offset <= 1; offset++) {
            result.add(center.offset(offset, 0, -2));
            result.add(center.offset(offset, 0, 2));
            result.add(center.offset(-2, 0, offset));
            result.add(center.offset(2, 0, offset));
        }
        return Set.copyOf(result);
    }

    private static BlockInteractionTarget blockTarget(
            final VisibleBlockFace visible
    ) {
        return new BlockInteractionTarget(
                visible.block().x(),
                visible.block().y(),
                visible.block().z(),
                parseFace(visible.face()),
                new ActionVec3(
                        visible.hitPosition().x(),
                        visible.hitPosition().y(),
                        visible.hitPosition().z()
                )
        );
    }

    private static BlockFace parseFace(final String value) {
        final int separator = value.lastIndexOf(':');
        final String name = separator >= 0
                ? value.substring(separator + 1)
                : value;
        return BlockFace.valueOf(name.toUpperCase(Locale.ROOT));
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

    private static PerceptionVec3 center(final GridPos position) {
        return new PerceptionVec3(
                position.x() + 0.5,
                position.y() + 0.65,
                position.z() + 0.5
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
                (float) Math.toDegrees(
                    Math.atan2(
                        -delta.y(),
                        Math.hypot(delta.x(), delta.z())
                    )
                )
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

    private enum Phase {
        IDLE,
        SEARCHING,
        VERIFYING,
        ORIENTING,
        TRAVELLING,
        COMPLETED,
        FAILED,
        CANCELLED;

        private boolean active() {
            return this == SEARCHING
                    || this == VERIFYING
                    || this == ORIENTING
                    || this == TRAVELLING;
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
