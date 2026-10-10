package dev.mcai.companion.skills.portal;

import dev.mcai.companion.action.ActionMath;
import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Performs a bounded first-person scan and enters the nearest portal surface
 * that becomes current fair evidence.
 *
 * <p>The model authorizes only the high-level intent. This compound turns the
 * normal player camera, binds one exact semantic face from the resulting
 * observation, and delegates every movement and traversal proof to
 * {@link EnterObservedPortalSkill}. It never searches blocks or dimensions
 * through a world accessor.</p>
 */
public final class FindAndEnterObservedPortalSkill
        implements Skill<NoParameters> {
    public static final String NAME =
            "find_and_enter_observed_portal";

    private static final int MAXIMUM_SCAN_STEPS = 36;
    private static final int MAXIMUM_TOTAL_TICKS = 1_800;
    private static final float[] SCAN_PITCHES = {
        18.0F,
        42.0F,
        0.0F
    };

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final PortalSkillFrameSource frames;
    private final PortalSkillPolicy policy;
    private final PortalTraversalObserver traversalObserver;

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1;
    private long startedAtTick = -1;
    private long lastObservationRevision = -1;
    private float scanBaseYaw;
    private int scanSteps;
    /* Last fair portal target used for bounded re-acquisition after a
     * transient child visibility loss. */
    private ObservedPortalTarget reacquisitionTarget;
    private EnterObservedPortalSkill entry;
    private EnterObservedPortalParameters entryParameters;

    public FindAndEnterObservedPortalSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final PortalSkillFrameSource frames,
            final PortalSkillPolicy policy,
            final PortalTraversalObserver traversalObserver
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
    public SkillParameterParser<NoParameters> parameters() {
        return arguments -> arguments != null && arguments.isEmpty()
                ? SkillParameterResult.valid(NoParameters.INSTANCE)
                : SkillParameterResult.invalid(
                        NAME + ".invalid_arguments"
                );
    }

    @Override
    public boolean allowsWorldRevisionTransition() {
        return true;
    }

    @Override
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<PortalSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return rejected("observation_unavailable");
        }
        final PortalSkillFrame frame = current.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return rejected("player_mismatch");
        }
        if (!frame.currentDimension().equals(
                frame.observedDimension()
        )) {
            return rejected("dimension_desynchronized");
        }
        if (frame.observationAgeTicks()
                > policy.maximumObservationAgeTicks()) {
            return rejected("stale_observation");
        }
        return Optional.empty();
    }

    @Override
    public void start(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final PortalSkillFrame frame = frames.current()
                .orElseThrow(() -> new IllegalStateException(
                        "Portal frame disappeared before start"
                ));
        phase = Phase.SCANNING;
        failure = null;
        boundSessionGeneration = frame.sessionGeneration();
        startedAtTick = context.gameTick();
        lastObservationRevision = -1;
        scanBaseYaw = yaw(frame);
        scanSteps = 0;
        reacquisitionTarget = null;
        entry = null;
        entryParameters = null;
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final NoParameters parameters
    ) {
        if (phase == Phase.ENTERING) {
            return tickEntry(context);
        }
        if (phase != Phase.SCANNING) {
            return SkillTickResult.failed(NAME + ".invalid_state");
        }
        final Optional<PortalSkillFrame> current = frames.current();
        if (current.isEmpty()) {
            return fail("observation_unavailable");
        }
        final PortalSkillFrame frame = current.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return fail("player_mismatch");
        }
        if (frame.sessionGeneration() != boundSessionGeneration) {
            return fail("session_mismatch");
        }
        if (!frame.currentDimension().equals(
                frame.observedDimension()
        )) {
            return fail("dimension_desynchronized");
        }
        if (context.gameTick() < startedAtTick
                || context.gameTick() - startedAtTick
                    > MAXIMUM_TOTAL_TICKS) {
            return fail("timeout");
        }

        final Optional<VisibleBlockFace> target =
                nearestPortal(frame, reacquisitionTarget);
        if (target.isPresent()) {
            reacquisitionTarget = null;
            return startEntry(
                    context,
                    frame,
                    target.orElseThrow()
            );
        }
        if (frame.observationRevision()
                <= lastObservationRevision) {
            actuator.stop();
            return SkillTickResult.running(false, true);
        }
        lastObservationRevision = frame.observationRevision();
        if (scanSteps >= MAXIMUM_SCAN_STEPS) {
            return fail("portal_not_observed");
        }
        final int pitchIndex =
                scanSteps % SCAN_PITCHES.length;
        final int yawStep =
                scanSteps / SCAN_PITCHES.length;
        final LookIntent look = new LookIntent(
                ActionMath.wrapDegrees(
                        scanBaseYaw + yawStep * 30.0F
                ),
                SCAN_PITCHES[pitchIndex]
        );
        if (!actuator.stop().accepted()
                || !actuator.look(look).accepted()) {
            return fail("actuator_rejected");
        }
        scanSteps++;
        return SkillTickResult.running(true, true);
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                "{\"phase\":\"" + phase.name()
                    + "\",\"scanSteps\":" + scanSteps
                    + ",\"sessionGeneration\":"
                    + boundSessionGeneration + "}"
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        if (entry != null && entryParameters != null) {
            entry.cancel(context, entryParameters);
        } else {
            actuator.stop();
        }
        phase = Phase.CANCELLED;
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

    private SkillTickResult startEntry(
            final SkillContext context,
            final PortalSkillFrame frame,
            final VisibleBlockFace visible
    ) {
        final Optional<BlockFace> face = blockFace(visible.face());
        if (face.isEmpty()) {
            return fail("invalid_visible_face");
        }
        final var block = visible.block();
        entryParameters = new EnterObservedPortalParameters(
                frame.currentDimension(),
                new ObservedPortalTarget(
                        frame.observationRevision(),
                        block.x(),
                        block.y(),
                        block.z(),
                        face.orElseThrow()
                ),
                expectedDestination(
                        frame.currentDimension(),
                        PortalKind.fromBlockTypeId(
                                visible.blockTypeId()
                        ).orElseThrow()
                )
        );
        entry = new EnterObservedPortalSkill(
                expectedPlayerId,
                actuator,
                frames,
                policy,
                traversalObserver
        );
        final Optional<SkillFailure> precondition =
                entry.preconditions(context, entryParameters);
        if (precondition.isPresent()) {
            entry = null;
            entryParameters = null;
            return fail("entry_precondition_failed");
        }
        entry.start(context, entryParameters);
        phase = Phase.ENTERING;
        return SkillTickResult.running(true, false);
    }

    private SkillTickResult tickEntry(final SkillContext context) {
        final SkillTickResult result =
                entry.tick(context, entryParameters);
        if (result.status()
                == SkillTickResult.Status.COMPLETED) {
            phase = Phase.COMPLETED;
            return SkillTickResult.completed();
        }
        if (result.status() == SkillTickResult.Status.FAILED) {
            final SkillFailure childFailure = result.failure().orElseGet(() ->
                    SkillFailure.of(NAME + ".entry_failed"));
            /*
             * A portal is a two-dimensional translucent surface. While a
             * player closes the last approach steps, the normal first-person
             * ray fan can briefly miss the exact face that started the child
             * skill (camera interpolation, a neighbouring portal block, or a
             * one-tick body straddle are all sufficient). Treating that
             * bounded miss as terminal made a verified return fail even
             * though the same portal was still immediately in front of the
             * body. Drop only the child binding and resume the ordinary
             * finite scan; no portal block or position is queried outside the
             * next fair frame.
            */
            if (recoverableTargetLoss(childFailure)) {
                final ObservedPortalTarget lostTarget =
                        entryParameters == null
                                ? null
                                : entryParameters.target();
                entry = null;
                entryParameters = null;
                lastObservationRevision = -1;
                reacquisitionTarget = lostTarget;
                final Optional<PortalSkillFrame> current = frames.current();
                if (current.isPresent()) {
                    final PortalSkillFrame frame = current.orElseThrow();
                    /*
                     * Reacquire from the last fair target first. A broad
                     * 30-degree sweep can miss a nearby portal after the
                     * child has turned the body; looking at the already
                     * observed cell is ordinary camera input, not a world
                     * query or hidden interaction.
                     */
                    scanBaseYaw = yaw(frame);
                    scanSteps = 0;
                    if (lostTarget != null
                            && !actuator.look(new LookIntent(
                                    yawTo(frame, lostTarget),
                                    pitchTo(frame, lostTarget)
                            )).accepted()) {
                        failure = SkillFailure.of(
                                NAME + ".actuator_rejected"
                        );
                        phase = Phase.FAILED;
                        actuator.stop();
                        return SkillTickResult.failed(failure);
                    }
                }
                if (scanSteps >= MAXIMUM_SCAN_STEPS) {
                    failure = SkillFailure.of(NAME + ".portal_not_observed");
                    phase = Phase.FAILED;
                    actuator.stop();
                    return SkillTickResult.failed(failure);
                }
                scanSteps++;
                phase = Phase.SCANNING;
                actuator.stop();
                return SkillTickResult.running(true, true);
            }
            actuator.stop();
            failure = childFailure;
            phase = Phase.FAILED;
            return SkillTickResult.failed(failure);
        }
        return result;
    }

    private static boolean recoverableTargetLoss(
            final SkillFailure failure
    ) {
        final String code = failure.code();
        return code.endsWith("enter_observed_portal.target_disappeared")
                || code.endsWith("enter_observed_portal.target_too_far");
    }

    private Optional<VisibleBlockFace> nearestPortal(
            final PortalSkillFrame frame,
            final ObservedPortalTarget anchor
    ) {
        if (frame.observationAgeTicks()
                > policy.maximumObservationAgeTicks()) {
            return Optional.empty();
        }
        return frame.visibleBlockFaces().stream()
                .filter(face ->
                        PortalKind.fromBlockTypeId(
                                face.blockTypeId()
                        ).isPresent()
                )
                .filter(face -> face.distance()
                        <= policy.maximumApproachDistance())
                .filter(face -> blockFace(face.face()).isPresent())
                .filter(face -> anchor == null
                        || sameBoundedPortal(face, anchor))
                .min(Comparator.comparingDouble(
                        VisibleBlockFace::distance
                ));
    }

    private static boolean sameBoundedPortal(
            final VisibleBlockFace face,
            final ObservedPortalTarget anchor
    ) {
        final int dx = Math.abs(face.block().x() - anchor.x());
        final int dy = Math.abs(face.block().y() - anchor.y());
        final int dz = Math.abs(face.block().z() - anchor.z());
        return dy <= 3 && Math.max(dx, dz) <= 2;
    }

    private static float yawTo(
            final PortalSkillFrame frame,
            final ObservedPortalTarget target
    ) {
        final PerceptionVec3 center = new PerceptionVec3(
                target.x() + 0.5,
                target.y() + 0.5,
                target.z() + 0.5
        );
        final PerceptionVec3 delta = center.subtract(frame.eyePosition());
        return (float) Math.toDegrees(Math.atan2(-delta.x(), delta.z()));
    }

    private static float pitchTo(
            final PortalSkillFrame frame,
            final ObservedPortalTarget target
    ) {
        final PerceptionVec3 center = new PerceptionVec3(
                target.x() + 0.5,
                target.y() + 0.5,
                target.z() + 0.5
        );
        final PerceptionVec3 delta = center.subtract(frame.eyePosition());
        return (float) Math.toDegrees(Math.atan2(
                -delta.y(),
                Math.hypot(delta.x(), delta.z())
        ));
    }

    private static Optional<DimensionRef> expectedDestination(
            final DimensionRef source,
            final PortalKind kind
    ) {
        return switch (kind) {
            case END_PORTAL -> Optional.of(
                    source.equals(DimensionRef.END)
                            ? DimensionRef.OVERWORLD
                            : DimensionRef.END
            );
            case NETHER_PORTAL -> source.equals(DimensionRef.NETHER)
                    ? Optional.of(DimensionRef.OVERWORLD)
                    : source.equals(DimensionRef.OVERWORLD)
                            ? Optional.of(DimensionRef.NETHER)
                            : Optional.empty();
            case END_GATEWAY -> Optional.empty();
        };
    }

    private static Optional<BlockFace> blockFace(
            final String value
    ) {
        try {
            return Optional.of(BlockFace.valueOf(
                    value.toUpperCase(Locale.ROOT)
            ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static float yaw(final PortalSkillFrame frame) {
        return (float) Math.toDegrees(Math.atan2(
                -frame.lookDirection().x(),
                frame.lookDirection().z()
        ));
    }

    private SkillTickResult fail(final String suffix) {
        actuator.stop();
        failure = SkillFailure.of(NAME + "." + suffix);
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    private static Optional<SkillFailure> rejected(
            final String suffix
    ) {
        return Optional.of(SkillFailure.of(
                NAME + "." + suffix
        ));
    }

    private enum Phase {
        IDLE,
        SCANNING,
        ENTERING,
        COMPLETED,
        CANCELLED,
        FAILED
    }
}
