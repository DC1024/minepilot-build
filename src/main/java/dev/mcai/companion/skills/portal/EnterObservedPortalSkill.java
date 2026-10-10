package dev.mcai.companion.skills.portal;

import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.perception.BlockCoordinate;
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
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Walks the companion into a portal block that its own first-person semantic
 * sample actually saw.
 *
 * <p>Success is never inferred from elapsed time or a requested destination.
 * Nether/end portals complete only after the live body changes dimension.
 * Vanilla end gateways are the necessary special case: they remain in the End
 * and complete only after the live body undergoes the gateway's large
 * server-authoritative displacement.</p>
 */
public final class EnterObservedPortalSkill
        implements Skill<EnterObservedPortalParameters> {
    private static final MovementIntent APPROACH =
            new MovementIntent(0.72, 0.0, false, false);
    /*
     * A player can finish a portal frame while standing on a raised slab and
     * then meet the obsidian top beam before their feet leave that scaffold.
     * A bounded crouched approach lowers the vanilla player pose and lets the
     * 0.6-wide body overlap the portal while it still has edge support. This
     * is ordinary player input: it neither changes position nor bypasses
     * collision/ledge safety.
     */
    private static final MovementIntent CROUCHED_APPROACH =
            new MovementIntent(0.48, 0.0, false, true);
    private static final MovementIntent ENTER =
            new MovementIntent(0.65, 0.0, false, false);
    private static final double PROGRESS_EPSILON = 0.04;
    /*
     * A player's 0.6-wide body touches a one-block End portal when its
     * horizontal center is about 0.8 blocks from the portal-cell center. In
     * practice the server can apply one ordinary movement pulse (roughly
     * 0.7 blocks) and the End portal can change dimension before the next
     * semantic frame is published. Keep a small pre-commit envelope around
     * that contact boundary so the dispatched vanilla pulse has an explicit
     * physical provenance even when the next frame is already in the End.
     * This does not claim success: a blocked pulse still enters ENTERING and
     * must produce a real portal transition before the bounded timeout.
     */
    private static final double END_PORTAL_COMMIT_HORIZONTAL = 1.20;
    /*
     * EndPortalBlock's vanilla inside-collision shape ends at 12/16 of the
     * block. A player approaching across the surrounding frame is normally
     * standing one block above the portal block before the next movement and
     * gravity step intersects that shape. Measure vertical proximity from the
     * real portal surface, not from the block's integer Y origin.
     */
    private static final double END_PORTAL_SURFACE_Y = 12.0 / 16.0;
    private static final double END_PORTAL_COMMIT_VERTICAL = 0.75;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final PortalSkillFrameSource frames;
    private final PortalSkillPolicy policy;
    private final PortalTraversalObserver traversalObserver;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private PortalKind portalKind;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;
    private long committedAtTick = -1;
    private long lastProgressTick = -1;
    private long lastObservationRevision = -1;
    private long targetLostAtTick = -1;
    private long missingFrameSinceTick = -1;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private double bestAlignment = Double.POSITIVE_INFINITY;
    private boolean crouchedRecovery;
    private int recoveryJumps;
    private int lastPortalProgressTicks;
    private PerceptionVec3 targetAim;
    private PerceptionVec3 lastOriginPosition;
    private PerceptionVec3 committedPosition;
    private PortalTraversalResult traversalResult;

    public EnterObservedPortalSkill(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            PortalSkillFrameSource frames
    ) {
        this(
                expectedPlayerId,
                actuator,
                frames,
                PortalSkillPolicy.defaults(),
                PortalTraversalObserver.NOOP
        );
    }

    public EnterObservedPortalSkill(
            UUID expectedPlayerId,
            CoreSkillActuator actuator,
            PortalSkillFrameSource frames,
            PortalSkillPolicy policy,
            PortalTraversalObserver traversalObserver
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.traversalObserver = Objects.requireNonNull(
                traversalObserver,
                "traversalObserver"
        );
    }

    @Override
    public SkillParameterParser<EnterObservedPortalParameters> parameters() {
        return PortalSkills::parseEnterObservedPortal;
    }

    @Override
    public boolean allowsWorldRevisionTransition() {
        return true;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            SkillContext context,
            EnterObservedPortalParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        Optional<PortalSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return rejected("observation_unavailable");
        }
        PortalSkillFrame frame = current.orElseThrow();
        Optional<SkillFailure> frameFailure = validateInitialFrame(
                context,
                parameters,
                frame
        );
        if (frameFailure.isPresent()) {
            return frameFailure;
        }
        TargetResolution target = resolveTarget(frame, parameters.target());
        if (target.face().isEmpty()) {
            return target.failure();
        }
        VisibleBlockFace face = target.face().orElseThrow();
        if (face.distance() > policy.maximumApproachDistance()) {
            return rejected("target_too_far");
        }
        return Optional.empty();
    }

    @Override
    public void start(
            SkillContext context,
            EnterObservedPortalParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        PortalSkillFrame frame = frames.current().orElseThrow(
                () -> new IllegalStateException(
                        "Portal observation disappeared before start"
                )
        );
        TargetResolution resolution = resolveTarget(
                frame,
                parameters.target()
        );
        VisibleBlockFace target = resolution.face().orElseThrow(
                () -> new IllegalStateException(
                        "Portal target changed before start"
                )
        );
        portalKind = PortalKind.fromBlockTypeId(target.blockTypeId())
                .orElseThrow();
        boundSessionGeneration = frame.sessionGeneration();
        startedAtTick = context.gameTick();
        committedAtTick = -1;
        lastProgressTick = context.gameTick();
        lastObservationRevision = frame.observationRevision();
        targetLostAtTick = -1;
        missingFrameSinceTick = -1;
        bestDistance = distanceToPortal(frame, parameters.target());
        bestAlignment = Double.POSITIVE_INFINITY;
        crouchedRecovery = false;
        recoveryJumps = 0;
        lastPortalProgressTicks = 0;
        /*
         * Portal observations are ray-marched and their hit can lie close to
         * the obsidian frame. Walking toward that edge can wedge the
         * player's 0.6-block-wide body against the frame even though the
         * portal cell itself is open. The observed block coordinate safely
         * proves the center of that same portal cell without revealing any
         * additional world state.
         */
        targetAim = portalCenter(parameters.target());
        lastOriginPosition = frame.position();
        committedPosition = null;
        traversalResult = null;
        failure = null;
        phase = Phase.ALIGNING;
    }

    @Override
    public SkillTickResult tick(
            SkillContext context,
            EnterObservedPortalParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        if (phase != Phase.ALIGNING
                && phase != Phase.APPROACHING
                && phase != Phase.ENTERING
                && phase != Phase.WAITING) {
            return SkillTickResult.failed(
                    "enter_observed_portal.invalid_state"
            );
        }
        try {
            return tickSafely(context, parameters);
        } catch (RuntimeException exception) {
            return fail(
                    frames.current(),
                    "internal_failure"
            );
        }
    }

    @Override
    public SkillCheckpoint checkpoint(
            SkillContext context,
            EnterObservedPortalParameters parameters
    ) {
        String expected = parameters.expectedDestination()
                .map(DimensionRef::id)
                .orElse("");
        return new SkillCheckpoint(
                1,
                String.format(
                        Locale.ROOT,
                        "{\"phase\":\"%s\",\"dimension\":\"%s\","
                                + "\"sampleSequence\":%d,"
                                + "\"x\":%d,\"y\":%d,\"z\":%d,"
                                + "\"face\":\"%s\","
                                + "\"expectedDestination\":\"%s\","
                                + "\"sessionGeneration\":%d}",
                        phase.name(),
                        parameters.dimension().id(),
                        parameters.target().sampleSequence(),
                        parameters.target().x(),
                        parameters.target().y(),
                        parameters.target().z(),
                        parameters.target().face()
                                .name()
                                .toLowerCase(Locale.ROOT),
                        expected,
                        boundSessionGeneration
                )
        );
    }

    @Override
    public void cancel(
            SkillContext context,
            EnterObservedPortalParameters parameters
    ) {
        quiet(frames.current());
        phase = Phase.CANCELLED;
    }

    @Override
    public SkillResult result(
            SkillContext context,
            EnterObservedPortalParameters parameters
    ) {
        return switch (phase) {
            case COMPLETED -> SkillResult.completed();
            case CANCELLED -> SkillResult.cancelled();
            case FAILED -> SkillResult.failed(
                    Objects.requireNonNull(failure)
            );
            default -> SkillResult.failed(
                    SkillFailure.of(
                            "enter_observed_portal.invalid_state"
                    )
            );
        };
    }

    /**
     * Present only after the live body actually crossed or was displaced by
     * the selected gateway, including when an expected destination mismatch
     * makes the skill terminally fail.
     */
    public Optional<PortalTraversalResult> traversalResult() {
        return Optional.ofNullable(traversalResult);
    }

    private SkillTickResult tickSafely(
            SkillContext context,
            EnterObservedPortalParameters parameters
    ) {
        Optional<PortalSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return handleMissingFrame(context);
        }
        missingFrameSinceTick = -1;
        PortalSkillFrame frame = current.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return fail(current, "player_mismatch");
        }
        if (frame.sessionGeneration() != boundSessionGeneration) {
            return fail(current, "session_mismatch");
        }

        // This is the sole normal nether/end completion condition.
        if (!frame.currentDimension().equals(parameters.dimension())) {
            if (committedPosition == null) {
                return fail(
                        current,
                        "dimension_changed_before_entry"
                );
            }
            return completeTraversal(context, parameters, frame);
        }
        if (portalKind == PortalKind.END_GATEWAY
                && committedPosition != null
                && frame.position()
                .subtract(committedPosition)
                .length() >= policy.gatewayMinimumDisplacement()) {
            return completeTraversal(context, parameters, frame);
        }

        if (context.gameTick() < startedAtTick
                || context.gameTick() - startedAtTick
                > policy.maximumTotalTicks()) {
            return fail(current, "timeout");
        }
        if (unsafe(context, frame)) {
            return fail(
                    current,
                    context.hardcore()
                            ? "hardcore_danger"
                            : "current_danger"
            );
        }
        if (frame.observationRevision() < lastObservationRevision) {
            return fail(current, "stale_observation");
        }

        if ((phase == Phase.ALIGNING || phase == Phase.APPROACHING)
                && portalContactMatches(frame, parameters.target())) {
            phase = Phase.WAITING;
            if (committedAtTick < 0) {
                committedAtTick = context.gameTick();
            }
            if (committedPosition == null) {
                committedPosition = frame.position();
            }
            lastPortalProgressTicks = frame.portalProgressTicks();
            lastObservationRevision = Math.max(
                    lastObservationRevision,
                    frame.observationRevision()
            );
            lastOriginPosition = frame.position();
            if (!actuator.stop().accepted()) {
                return fail(current, "actuator_rejected");
            }
            return SkillTickResult.running(true, false);
        }

        if (phase == Phase.ALIGNING || phase == Phase.APPROACHING) {
            Optional<SkillTickResult> visibility = revalidateVisibleTarget(
                    context,
                    parameters,
                    frame
            );
            if (visibility.isPresent()) {
                return visibility.orElseThrow();
            }
            if (frame.observationAgeTicks()
                    > policy.maximumObservationAgeTicks()) {
                return fail(current, "stale_observation");
            }
        } else if (frame.observationRevision() > lastObservationRevision) {
            TargetResolution committedTarget = resolveTarget(
                    frame,
                    parameters.target()
            );
            if (committedTarget.failure()
                    .map(SkillFailure::code)
                    .filter(code -> code.endsWith("target_changed")
                            || code.endsWith("target_not_portal"))
                    .isPresent()) {
                return fail(current, "target_changed");
            }
            Optional<VisibleBlockFace> refreshed =
                    committedTarget.face().isPresent()
                        ? committedTarget.face()
                        : compatiblePortalFace(
                                frame,
                                parameters.target()
                        );
            if (refreshed.isPresent()) {
                VisibleBlockFace visible = refreshed.orElseThrow();
                PortalKind refreshedKind = PortalKind
                        .fromBlockTypeId(visible.blockTypeId())
                        .orElseThrow();
                if (refreshedKind != portalKind) {
                    return fail(current, "target_changed");
                }
            }
        }

        lastObservationRevision = Math.max(
                lastObservationRevision,
                frame.observationRevision()
        );
        lastOriginPosition = frame.position();
        return switch (phase) {
            case ALIGNING, APPROACHING ->
                    approach(context, parameters, frame);
            case ENTERING -> enter(context, parameters, frame);
            case WAITING -> waitForPortal(context, parameters, frame);
            default -> SkillTickResult.failed(
                    "enter_observed_portal.invalid_state"
            );
        };
    }

    /**
     * Vanilla may detach and reattach a {@code ServerPlayer} for a few ticks
     * while completing a dimension transfer. The fair frame source therefore
     * has a legitimate short empty window even though the same body has
     * already touched the observed portal.
     *
     * <p>Only a traversal that has physically entered its commit envelope may
     * wait. Before that point an absent observation still fails closed. The
     * wait is bounded by both the dedicated frame-gap budget and the skill's
     * total timeout, and emits no movement while the body cannot be verified.</p>
     */
    private SkillTickResult handleMissingFrame(
            SkillContext context
    ) {
        if (committedPosition == null) {
            return fail(Optional.empty(), "observation_unavailable");
        }
        if (context.gameTick() < startedAtTick
                || context.gameTick() - startedAtTick
                > policy.maximumTotalTicks()) {
            return fail(Optional.empty(), "timeout");
        }
        if (missingFrameSinceTick < 0) {
            missingFrameSinceTick = context.gameTick();
        }
        if (context.gameTick() < missingFrameSinceTick
                || context.gameTick() - missingFrameSinceTick
                >= policy.maximumCommittedFrameGapTicks()) {
            return fail(
                    Optional.empty(),
                    "committed_observation_unavailable"
            );
        }
        if (!actuator.stop().accepted()) {
            return fail(Optional.empty(), "actuator_rejected");
        }
        return SkillTickResult.running(false, false);
    }

    private Optional<SkillTickResult> revalidateVisibleTarget(
            SkillContext context,
            EnterObservedPortalParameters parameters,
            PortalSkillFrame frame
    ) {
        TargetResolution resolution = resolveTarget(
                frame,
                parameters.target()
        );
        if (resolution.failure()
                .map(SkillFailure::code)
                .filter(code -> code.endsWith("target_changed")
                        || code.endsWith("target_not_portal"))
                .isPresent()) {
            return Optional.of(fail(
                    Optional.of(frame),
                    "target_changed"
            ));
        }
        Optional<VisibleBlockFace> visibleTarget =
                resolution.face().isPresent()
                    ? resolution.face()
                    : compatiblePortalFace(
                            frame,
                            parameters.target()
                    );
        if (visibleTarget.isPresent()) {
            VisibleBlockFace target =
                    visibleTarget.orElseThrow();
            PortalKind currentKind = PortalKind
                    .fromBlockTypeId(target.blockTypeId())
                    .orElseThrow();
            if (currentKind != portalKind) {
                return Optional.of(fail(
                        Optional.of(frame),
                        "target_changed"
                ));
            }
            if (target.distance() > policy.maximumApproachDistance()) {
                return Optional.of(fail(
                        Optional.of(frame),
                        "target_too_far"
                ));
            }
            targetLostAtTick = -1;
            return Optional.empty();
        }
        if (targetLostAtTick < 0) {
            targetLostAtTick = context.gameTick();
        }
        if (context.gameTick() - targetLostAtTick
                > policy.targetLostGraceTicks()) {
            return Optional.of(fail(
                    Optional.of(frame),
                    "target_disappeared"
            ));
        }
        if (!lookAndStop(frame)) {
            return Optional.of(fail(
                    Optional.of(frame),
                    "actuator_rejected"
            ));
        }
        return Optional.of(SkillTickResult.running(false, true));
    }

    /**
     * Reacquires only another currently visible block inside the bounded
     * physical extent of the portal initially selected by the player/model.
     * This handles ray-face changes while approaching without enumerating the
     * world or switching to a distant portal.
     */
    private Optional<VisibleBlockFace> compatiblePortalFace(
            PortalSkillFrame frame,
            ObservedPortalTarget original
    ) {
        if (portalKind == null
                || portalKind == PortalKind.END_GATEWAY) {
            return Optional.empty();
        }
        VisibleBlockFace nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (VisibleBlockFace candidate
                : frame.visibleBlockFaces()) {
            if (!portalKind.blockTypeId().equals(
                    candidate.blockTypeId()
            )) {
                continue;
            }
            BlockCoordinate block = candidate.block();
            int deltaX = Math.abs(block.x() - original.x());
            int deltaY = Math.abs(block.y() - original.y());
            int deltaZ = Math.abs(block.z() - original.z());
            boolean sameBoundedPortal = switch (portalKind) {
                case NETHER_PORTAL ->
                        Math.max(deltaX, deltaZ) <= 2
                            && deltaY <= 3;
                case END_PORTAL ->
                        deltaX <= 2
                            && deltaZ <= 2
                            && deltaY <= 1;
                case END_GATEWAY -> false;
            };
            if (!sameBoundedPortal) {
                continue;
            }
            int distance = deltaX + deltaY + deltaZ;
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return Optional.ofNullable(nearest);
    }

    private SkillTickResult approach(
            SkillContext context,
            EnterObservedPortalParameters parameters,
            PortalSkillFrame frame
    ) {
        PerceptionVec3 center = portalCenter(parameters.target());
        LookIntent look = lookAt(frame.eyePosition(), targetAim);
        double alignment = horizontalAngularError(
                frame.lookDirection(),
                targetAim.subtract(frame.position())
        );
        boolean alignedProgress = alignment + 0.5 < bestAlignment;
        bestAlignment = Math.min(bestAlignment, alignment);
        if (!actuator.look(look).accepted()) {
            return fail(
                    Optional.of(frame),
                    "actuator_rejected"
            );
        }
        if (alignment > policy.alignmentToleranceDegrees()) {
            phase = Phase.ALIGNING;
            if (!actuator.stop().accepted()) {
                return fail(
                        Optional.of(frame),
                        "actuator_rejected"
                );
            }
            return SkillTickResult.running(alignedProgress, true);
        }

        double distance = distanceToPortal(frame, parameters.target());
        double horizontal = horizontalDistance(
                frame.position(),
                center
        );
        double vertical = Math.abs(
                frame.position().y() - parameters.target().y()
        );
        if (withinCommitEnvelope(
                frame,
                parameters.target(),
                horizontal,
                vertical
        )) {
            phase = Phase.ENTERING;
            committedAtTick = context.gameTick();
            committedPosition = frame.position();
            if (!actuator.move(ENTER).accepted()) {
                return fail(
                        Optional.of(frame),
                        "actuator_rejected"
                );
            }
            return SkillTickResult.running(true, false);
        }

        phase = Phase.APPROACHING;
        boolean distanceProgress = distance + PROGRESS_EPSILON < bestDistance;
        if (distanceProgress) {
            bestDistance = distance;
            lastProgressTick = context.gameTick();
        }
        if (!distanceProgress
                && !crouchedRecovery
                && portalKind == PortalKind.NETHER_PORTAL
                && frame.onGround()
                && context.gameTick() - lastProgressTick
                    > policy.stuckWindowTicks()) {
            /*
             * Try the normal player's low-clearance/edge technique before a
             * jump. Switching strategy is one bounded recovery transition,
             * not evidence that the body moved; the following ticks must
             * still produce measured distance or real portal contact.
             */
            crouchedRecovery = true;
            lastProgressTick = context.gameTick();
            if (!actuator.move(CROUCHED_APPROACH).accepted()) {
                return fail(
                        Optional.of(frame),
                        "actuator_rejected"
                );
            }
            return SkillTickResult.running(true, true);
        }
        if (needsRecoveryJump(context, frame)) {
            crouchedRecovery = false;
            if (!actuator.jump().accepted()) {
                return fail(
                        Optional.of(frame),
                        "actuator_rejected"
                );
            }
            recoveryJumps++;
            lastProgressTick = context.gameTick();
        }
        if (context.gameTick() - lastProgressTick
                > policy.stuckWindowTicks()
                && recoveryJumps >= policy.maximumRecoveryJumps()) {
            return fail(Optional.of(frame), "approach_stuck");
        }
        final MovementIntent approach = crouchedRecovery
                ? CROUCHED_APPROACH
                : APPROACH;
        if (!actuator.move(approach).accepted()) {
            return fail(
                    Optional.of(frame),
                    "actuator_rejected"
            );
        }
        return SkillTickResult.running(
                distanceProgress || alignedProgress,
                true
        );
    }

    private boolean withinCommitEnvelope(
            PortalSkillFrame frame,
            ObservedPortalTarget target,
            double horizontal,
            double vertical
    ) {
        if (portalKind != PortalKind.END_PORTAL) {
            return horizontal <= policy.committedHorizontalDistance()
                    && vertical <= policy.committedVerticalDistance();
        }
        PerceptionVec3 center = portalCenter(target);
        return Math.abs(frame.position().x() - center.x())
                    <= END_PORTAL_COMMIT_HORIZONTAL
                && Math.abs(frame.position().z() - center.z())
                    <= END_PORTAL_COMMIT_HORIZONTAL
                && Math.abs(
                        frame.position().y()
                                - (target.y() + END_PORTAL_SURFACE_Y)
                )
                    <= END_PORTAL_COMMIT_VERTICAL;
    }

    private SkillTickResult enter(
            SkillContext context,
            EnterObservedPortalParameters parameters,
            PortalSkillFrame frame
    ) {
        if (!actuator.look(
                lookAt(frame.eyePosition(), portalCenter(parameters.target()))
        ).accepted()) {
            return fail(Optional.of(frame), "actuator_rejected");
        }
        if (frame.portalProcessActive()) {
            phase = Phase.WAITING;
            lastPortalProgressTicks = frame.portalProgressTicks();
            if (!actuator.stop().accepted()) {
                return fail(Optional.of(frame), "actuator_rejected");
            }
            return SkillTickResult.running(true, false);
        }
        if (context.gameTick() - committedAtTick
                > policy.entryPulseTicks()
                    + policy.stuckWindowTicks()) {
            return fail(Optional.of(frame), "portal_contact_timeout");
        }
        if (!actuator.move(ENTER).accepted()) {
            return fail(
                    Optional.of(frame),
                    "actuator_rejected"
            );
        }
        /*
         * The pulse itself is not proof of progress. The supervisor should
         * only see forward progress once vanilla has created a PortalProcessor
         * for this body; otherwise a blocked approach terminates via the
         * bounded contact timeout above.
         */
        return SkillTickResult.running(false, false);
    }

    private SkillTickResult waitForPortal(
            SkillContext context,
            EnterObservedPortalParameters parameters,
            PortalSkillFrame frame
    ) {
        if (context.gameTick() - committedAtTick
                > policy.maximumPortalWaitTicks()) {
            return fail(Optional.of(frame), "portal_wait_timeout");
        }
        if (!frame.portalProcessActive()) {
            phase = Phase.ENTERING;
            lastPortalProgressTicks = 0;
            if (!actuator.move(ENTER).accepted()) {
                return fail(Optional.of(frame), "actuator_rejected");
            }
            return SkillTickResult.running(false, false);
        }
        if (!actuator.look(
                lookAt(frame.eyePosition(), portalCenter(parameters.target()))
        ).accepted()
                || !actuator.stop().accepted()) {
            return fail(Optional.of(frame), "actuator_rejected");
        }
        boolean charged = frame.portalProgressTicks()
                > lastPortalProgressTicks;
        lastPortalProgressTicks = frame.portalProgressTicks();
        // Waiting includes vanilla portal charge and an existing portal
        // cooldown. The read-only charge counter proves bounded liveness; no
        // timer, cooldown, position, or world state is modified.
        return SkillTickResult.running(charged, false);
    }

    private SkillTickResult completeTraversal(
            SkillContext context,
            EnterObservedPortalParameters parameters,
            PortalSkillFrame frame
    ) {
        PerceptionVec3 source = lastOriginPosition == null
                ? frame.position()
                : lastOriginPosition;
        traversalResult = new PortalTraversalResult(
                Objects.requireNonNull(portalKind),
                boundSessionGeneration,
                parameters.dimension(),
                source,
                new BlockCoordinate(
                        parameters.target().x(),
                        parameters.target().y(),
                        parameters.target().z()
                ),
                frame.currentDimension(),
                frame.position(),
                startedAtTick,
                context.gameTick(),
                parameters.expectedDestination()
        );
        try {
            traversalObserver.onTraversal(traversalResult);
        } catch (RuntimeException ignored) {
            // The actual traversal remains authoritative and inspectable via
            // traversalResult(), even if an optional graph consumer failed.
        }
        quiet(Optional.of(frame));
        if (!traversalResult.destinationMatchesExpectation()) {
            phase = Phase.FAILED;
            failure = SkillFailure.of(
                    "enter_observed_portal.unexpected_destination"
            );
            return SkillTickResult.failed(failure);
        }
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private Optional<SkillFailure> validateInitialFrame(
            SkillContext context,
            EnterObservedPortalParameters parameters,
            PortalSkillFrame frame
    ) {
        if (!expectedPlayerId.equals(frame.playerId())) {
            return rejected("player_mismatch");
        }
        if (!parameters.dimension().equals(frame.currentDimension())
                || !parameters.dimension().equals(
                        frame.observedDimension()
                )) {
            return rejected("dimension_mismatch");
        }
        if (frame.observationRevision()
                != parameters.target().sampleSequence()) {
            return rejected("observation_expired");
        }
        if (frame.observationAgeTicks()
                > policy.maximumObservationAgeTicks()) {
            return rejected("stale_observation");
        }
        if (unsafe(context, frame)) {
            return rejected(
                    context.hardcore()
                            ? "hardcore_danger"
                            : "current_danger"
            );
        }
        return Optional.empty();
    }

    private TargetResolution resolveTarget(
            PortalSkillFrame frame,
            ObservedPortalTarget target
    ) {
        if (frame.observationRevision() < target.sampleSequence()) {
            return TargetResolution.failed("observation_expired");
        }
        Optional<VisibleBlockFace> exact = frame.visibleBlockFaces().stream()
                .filter(face -> sameBlockAndFace(face, target))
                .findFirst();
        if (exact.isEmpty()) {
            boolean changed = frame.visibleBlockFaces().stream()
                    .anyMatch(face -> sameBlock(face, target)
                            && PortalKind.fromBlockTypeId(
                            face.blockTypeId()
                    ).isEmpty());
            return TargetResolution.failed(
                    changed ? "target_changed" : "target_not_visible"
            );
        }
        VisibleBlockFace visible = exact.orElseThrow();
        if (PortalKind.fromBlockTypeId(visible.blockTypeId()).isEmpty()) {
            return TargetResolution.failed("target_not_portal");
        }
        return TargetResolution.resolved(visible);
    }

    private boolean needsRecoveryJump(
            SkillContext context,
            PortalSkillFrame frame
    ) {
        if (!frame.onGround()
                || recoveryJumps >= policy.maximumRecoveryJumps()) {
            return false;
        }
        /*
         * The selected semantic ray may hit an upper block of the same
         * vertical portal interior. Its Y coordinate is an observation
         * anchor, not a ledge that must be jumped onto. Recovery jumping is
         * reserved for measured lack of positional progress.
         */
        return context.gameTick() - lastProgressTick
                > policy.stuckWindowTicks();
    }

    private boolean unsafe(
            SkillContext context,
            PortalSkillFrame frame
    ) {
        double maximum = context.hardcore()
                ? policy.hardcoreMaximumDanger()
                : policy.normalMaximumDanger();
        return context.riskScore() > maximum || frame.danger() > maximum;
    }

    private boolean lookAndStop(PortalSkillFrame frame) {
        return actuator.look(
                lookAt(frame.eyePosition(), targetAim)
        ).accepted() && actuator.stop().accepted();
    }

    private SkillTickResult fail(
            Optional<PortalSkillFrame> frame,
            String suffix
    ) {
        quiet(frame);
        phase = Phase.FAILED;
        failure = SkillFailure.of(
                "enter_observed_portal." + suffix
        );
        return SkillTickResult.failed(failure);
    }

    private void quiet(Optional<PortalSkillFrame> frame) {
        actuator.stop();
        frame.ifPresent(value ->
                actuator.look(holdLook(value.lookDirection())));
    }

    private static Optional<SkillFailure> rejected(String suffix) {
        return Optional.of(SkillFailure.of(
                "enter_observed_portal." + suffix
        ));
    }

    private static double distanceToPortal(
            PortalSkillFrame frame,
            ObservedPortalTarget target
    ) {
        return frame.position().subtract(portalCenter(target)).length();
    }

    private static double horizontalDistance(
            PerceptionVec3 left,
            PerceptionVec3 right
    ) {
        return Math.hypot(left.x() - right.x(), left.z() - right.z());
    }

    private static PerceptionVec3 portalCenter(
            ObservedPortalTarget target
    ) {
        return new PerceptionVec3(
                target.x() + 0.5,
                target.y() + 0.5,
                target.z() + 0.5
        );
    }

    private static LookIntent lookAt(
            PerceptionVec3 eye,
            PerceptionVec3 target
    ) {
        PerceptionVec3 delta = target.subtract(eye);
        if (delta.lengthSquared() <= 1.0E-12) {
            return new LookIntent(0.0F, 0.0F);
        }
        float yaw = (float) Math.toDegrees(
                Math.atan2(-delta.x(), delta.z())
        );
        float pitch = (float) Math.toDegrees(Math.atan2(
                -delta.y(),
                Math.hypot(delta.x(), delta.z())
        ));
        return new LookIntent(yaw, pitch);
    }

    private static LookIntent holdLook(PerceptionVec3 direction) {
        return lookAt(
                new PerceptionVec3(0.0, 0.0, 0.0),
                direction
        );
    }

    private static double horizontalAngularError(
            PerceptionVec3 current,
            PerceptionVec3 target
    ) {
        double currentLength = Math.hypot(current.x(), current.z());
        double targetLength = Math.hypot(target.x(), target.z());
        if (targetLength <= 1.0E-12) {
            return 0.0;
        }
        if (currentLength <= 1.0E-12) {
            return 180.0;
        }
        double dot = (
                current.x() * target.x()
                        + current.z() * target.z()
        ) / (currentLength * targetLength);
        return Math.toDegrees(
                Math.acos(Math.max(-1.0, Math.min(1.0, dot)))
        );
    }

    private static boolean sameBlockAndFace(
            VisibleBlockFace visible,
            ObservedPortalTarget target
    ) {
        return sameBlock(visible, target)
                && visible.face().equals(
                        target.face()
                                .name()
                                .toLowerCase(Locale.ROOT)
                );
    }

    private static boolean sameBlock(
            VisibleBlockFace visible,
            ObservedPortalTarget target
    ) {
        return visible.block().x() == target.x()
                && visible.block().y() == target.y()
                && visible.block().z() == target.z();
    }

    private static boolean portalContactMatches(
            PortalSkillFrame frame,
            ObservedPortalTarget target
    ) {
        if (!frame.portalProcessActive()
                || frame.portalEntryBlock().isEmpty()) {
            return false;
        }
        BlockCoordinate entry = frame.portalEntryBlock().orElseThrow();
        int vertical = Math.abs(entry.y() - target.y());
        if (vertical > 4) {
            return false;
        }
        return switch (target.face()) {
            case NORTH, SOUTH ->
                    entry.z() == target.z()
                        && Math.abs(entry.x() - target.x()) <= 3;
            case EAST, WEST ->
                    entry.x() == target.x()
                        && Math.abs(entry.z() - target.z()) <= 3;
            case UP, DOWN ->
                    Math.abs(entry.x() - target.x()) <= 3
                        && Math.abs(entry.z() - target.z()) <= 3;
        };
    }

    private enum Phase {
        IDLE,
        ALIGNING,
        APPROACHING,
        ENTERING,
        WAITING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    private record TargetResolution(
            Optional<VisibleBlockFace> face,
            Optional<SkillFailure> failure
    ) {
        private TargetResolution {
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(failure, "failure");
            if (face.isPresent() == failure.isPresent()) {
                throw new IllegalArgumentException(
                        "Portal target resolution requires one outcome"
                );
            }
        }

        static TargetResolution resolved(VisibleBlockFace face) {
            return new TargetResolution(
                    Optional.of(face),
                    Optional.empty()
            );
        }

        static TargetResolution failed(String suffix) {
            return new TargetResolution(
                    Optional.empty(),
                    rejected(suffix)
            );
        }
    }
}
