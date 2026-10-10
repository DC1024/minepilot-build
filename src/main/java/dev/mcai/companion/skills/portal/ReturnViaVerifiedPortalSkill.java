package dev.mcai.companion.skills.portal;

import dev.mcai.companion.memory.transport.VerifiedPortalEdge;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.core.NoParameters;
import dev.mcai.companion.skills.core.TravelToParameters;
import dev.mcai.companion.skills.core.TravelToSkill;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Returns to a portal through one durable, body-observed arrival endpoint.
 *
 * <p>The stored endpoint is navigation memory, not proof that a portal still
 * exists and not a reverse-edge fabrication. The skill walks the body to the
 * remembered arrival area using rolling first-person navigation, scans for a
 * current portal surface, and delegates the actual crossing to the vanilla
 * portal-entry controller. Only that new physical traversal can create a
 * verified reverse edge.</p>
 */
public final class ReturnViaVerifiedPortalSkill
        implements Skill<NoParameters> {
    public static final String NAME = "return_via_verified_portal";

    static final double LOOKUP_RADIUS_BLOCKS = 4_096.0;
    static final int LOOKUP_RESULT_LIMIT = 4;
    private static final int LOOKUP_TIMEOUT_TICKS = 200;
    private static final int LOOKUP_RETRY_INTERVAL_TICKS = 20;
    private static final double ARRIVAL_RADIUS_BLOCKS = 3.0;
    private static final double MAXIMUM_SAFE_DANGER = 0.10;

    private final UUID expectedPlayerId;
    private final CoreSkillActuator actuator;
    private final CoreSkillFrameSource coreFrames;
    private final PortalSkillFrameSource portalFrames;
    private final LongSupplier sessionGeneration;
    private final VerifiedPortalArrivalLookup arrivals;
    private final PortalSkillPolicy portalPolicy;
    private final PortalTraversalObserver traversalObserver;
    private final AtomicLong lookupToken = new AtomicLong();
    private final AtomicReference<LookupCompletion> lookupCompletion =
            new AtomicReference<>();

    private Phase phase = Phase.IDLE;
    private SkillFailure failure;
    private long boundSessionGeneration = -1L;
    private long startedAtTick = -1L;
    private long nextLookupTick = -1L;
    private int lookupAttempts;
    private boolean sawLookupFailure;
    private DimensionRef boundDimension;
    private VerifiedPortalEdge selectedEdge;
    private TravelToSkill travel;
    private TravelToParameters travelParameters;
    private FindAndEnterObservedPortalSkill entry;

    public ReturnViaVerifiedPortalSkill(
            final UUID expectedPlayerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource coreFrames,
            final PortalSkillFrameSource portalFrames,
            final LongSupplier sessionGeneration,
            final VerifiedPortalArrivalLookup arrivals,
            final PortalSkillPolicy portalPolicy,
            final PortalTraversalObserver traversalObserver
    ) {
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.actuator = Objects.requireNonNull(actuator, "actuator");
        this.coreFrames = Objects.requireNonNull(
                coreFrames,
                "coreFrames"
        );
        this.portalFrames = Objects.requireNonNull(
                portalFrames,
                "portalFrames"
        );
        this.sessionGeneration = Objects.requireNonNull(
                sessionGeneration,
                "sessionGeneration"
        );
        this.arrivals = Objects.requireNonNull(arrivals, "arrivals");
        this.portalPolicy = Objects.requireNonNull(
                portalPolicy,
                "portalPolicy"
        );
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
    public Optional<SkillFailure> preconditions(
            final SkillContext context,
            final NoParameters parameters
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(parameters, "parameters");
        final Optional<CoreSkillFrame> current = coreFrames.current();
        if (current.isEmpty()) {
            return rejected("observation_unavailable");
        }
        final CoreSkillFrame frame = current.orElseThrow();
        if (!expectedPlayerId.equals(frame.playerId())) {
            return rejected("player_mismatch");
        }
        final long generation;
        try {
            generation = sessionGeneration.getAsLong();
        } catch (RuntimeException exception) {
            return rejected("session_unavailable");
        }
        if (generation < 0L) {
            return rejected("session_unavailable");
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

    @Override
    public void start(
            final SkillContext context,
            final NoParameters parameters
    ) {
        final CoreSkillFrame frame = coreFrames.current()
                .orElseThrow(() -> new IllegalStateException(
                        "Portal return frame disappeared before start"
                ));
        phase = Phase.LOOKUP;
        failure = null;
        boundSessionGeneration = sessionGeneration.getAsLong();
        startedAtTick = context.gameTick();
        boundDimension = frame.dimension();
        selectedEdge = null;
        travel = null;
        travelParameters = null;
        entry = null;
        lookupCompletion.set(null);
        nextLookupTick = context.gameTick();
        lookupAttempts = 0;
        sawLookupFailure = false;
        requestLookup(frame, context.gameTick());
    }

    private void requestLookup(
            final CoreSkillFrame frame,
            final long gameTick
    ) {
        final long token = lookupToken.incrementAndGet();
        lookupAttempts++;
        nextLookupTick = gameTick + LOOKUP_RETRY_INTERVAL_TICKS;
        try {
            arrivals.findNearby(
                    frame.dimension(),
                    frame.position(),
                    LOOKUP_RADIUS_BLOCKS,
                    LOOKUP_RESULT_LIMIT
            ).whenComplete((edges, error) -> {
                if (lookupToken.get() != token) {
                    return;
                }
                lookupCompletion.set(new LookupCompletion(
                        error == null ? edges : null,
                        error
                ));
            });
        } catch (RuntimeException exception) {
            lookupCompletion.set(new LookupCompletion(null, exception));
        }
    }

    @Override
    public SkillTickResult tick(
            final SkillContext context,
            final NoParameters parameters
    ) {
        if (phase == Phase.LOOKUP) {
            return tickLookup(context);
        }
        if (phase == Phase.TRAVELLING) {
            return tickTravel(context);
        }
        if (phase == Phase.ENTERING) {
            return tickEntry(context);
        }
        return SkillTickResult.failed(NAME + ".invalid_state");
    }

    @Override
    public SkillCheckpoint checkpoint(
            final SkillContext context,
            final NoParameters parameters
    ) {
        return new SkillCheckpoint(
                1,
                "{\"phase\":\"" + phase.name()
                    + "\",\"sessionGeneration\":"
                    + boundSessionGeneration
                    + ",\"sourceDimension\":\""
                    + (boundDimension == null
                        ? ""
                        : boundDimension.id())
                    + "\",\"selectedEdgeId\":\""
                    + (selectedEdge == null
                        ? ""
                        : selectedEdge.edgeId())
                    + "\",\"lookupAttempts\":"
                    + lookupAttempts
                    + "}"
        );
    }

    @Override
    public void cancel(
            final SkillContext context,
            final NoParameters parameters
    ) {
        lookupToken.incrementAndGet();
        if (phase == Phase.ENTERING && entry != null) {
            entry.cancel(context, NoParameters.INSTANCE);
        } else if (phase == Phase.TRAVELLING
                && travel != null
                && travelParameters != null) {
            travel.cancel(context, travelParameters);
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

    private SkillTickResult tickLookup(final SkillContext context) {
        final Optional<CoreSkillFrame> valid = currentFrame(context);
        if (valid.isEmpty()) {
            return fail(NAME + ".frame_invalid");
        }
        if (context.gameTick() < startedAtTick
                || context.gameTick() - startedAtTick
                    > LOOKUP_TIMEOUT_TICKS) {
            return fail(
                    sawLookupFailure
                            ? NAME + ".lookup_failed"
                            : NAME + ".arrival_not_remembered"
            );
        }
        if (!actuator.stop().accepted()) {
            return fail(NAME + ".actuator_rejected");
        }
        final LookupCompletion completion = lookupCompletion.get();
        if (completion == null) {
            return SkillTickResult.running(false, true);
        }
        if (completion.error() != null || completion.edges() == null) {
            sawLookupFailure = true;
            return retryLookup(context, valid.orElseThrow());
        }
        final CoreSkillFrame frame = valid.orElseThrow();
        final Optional<VerifiedPortalEdge> nearest = completion.edges()
                .stream()
                .filter(Objects::nonNull)
                .filter(edge ->
                        edge.portalKind() == PortalKind.NETHER_PORTAL
                )
                .filter(edge ->
                        edge.destinationDimension().equals(
                                boundDimension
                        )
                )
                .filter(edge ->
                        distance(
                                edge.destinationPosition(),
                                frame.position()
                        ) <= LOOKUP_RADIUS_BLOCKS
                )
                .min(Comparator
                        .comparingDouble((VerifiedPortalEdge edge) ->
                                distance(
                                        edge.destinationPosition(),
                                        frame.position()
                                )
                        )
                        .thenComparing(VerifiedPortalEdge::edgeId));
        if (nearest.isEmpty()) {
            return retryLookup(context, frame);
        }
        selectedEdge = nearest.orElseThrow();
        travelParameters = new TravelToParameters(
                boundDimension,
                selectedEdge.destinationPosition().x(),
                selectedEdge.destinationPosition().y(),
                selectedEdge.destinationPosition().z(),
                ARRIVAL_RADIUS_BLOCKS
        );
        travel = new TravelToSkill(
                expectedPlayerId,
                actuator,
                coreFrames,
                sessionGeneration
        );
        final Optional<SkillFailure> rejected =
                travel.preconditions(context, travelParameters);
        if (rejected.isPresent()) {
            return fail(rejected.orElseThrow().code());
        }
        travel.start(context, travelParameters);
        phase = Phase.TRAVELLING;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult retryLookup(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        if (context.gameTick() < nextLookupTick) {
            return SkillTickResult.running(false, true);
        }
        lookupCompletion.set(null);
        requestLookup(frame, context.gameTick());
        return SkillTickResult.running(false, true);
    }

    private SkillTickResult tickTravel(final SkillContext context) {
        final SkillTickResult result = travel.tick(
                context,
                travelParameters
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            return fail(result.failure().orElseThrow().code());
        }
        if (result.status() != SkillTickResult.Status.COMPLETED) {
            return result;
        }
        entry = new FindAndEnterObservedPortalSkill(
                expectedPlayerId,
                actuator,
                portalFrames,
                portalPolicy,
                traversalObserver
        );
        final Optional<SkillFailure> rejected = entry.preconditions(
                context,
                NoParameters.INSTANCE
        );
        if (rejected.isPresent()) {
            return fail(rejected.orElseThrow().code());
        }
        entry.start(context, NoParameters.INSTANCE);
        phase = Phase.ENTERING;
        return SkillTickResult.running(true, true);
    }

    private SkillTickResult tickEntry(final SkillContext context) {
        final SkillTickResult result = entry.tick(
                context,
                NoParameters.INSTANCE
        );
        if (result.status() == SkillTickResult.Status.FAILED) {
            return fail(result.failure().orElseThrow().code());
        }
        if (result.status() != SkillTickResult.Status.COMPLETED) {
            return result;
        }
        final Optional<PortalSkillFrame> current = portalFrames.current();
        if (current.isEmpty()
                || selectedEdge == null
                || !current.orElseThrow().currentDimension().equals(
                        selectedEdge.sourceDimension()
                )) {
            return fail(NAME + ".unexpected_destination");
        }
        phase = Phase.COMPLETED;
        return SkillTickResult.completed();
    }

    private Optional<CoreSkillFrame> currentFrame(
            final SkillContext context
    ) {
        final Optional<CoreSkillFrame> current = coreFrames.current();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        final CoreSkillFrame frame = current.orElseThrow();
        final long generation;
        try {
            generation = sessionGeneration.getAsLong();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if (!expectedPlayerId.equals(frame.playerId())
                || generation != boundSessionGeneration
                || !frame.dimension().equals(boundDimension)
                || unsafe(context, frame)) {
            return Optional.empty();
        }
        return Optional.of(frame);
    }

    private boolean unsafe(
            final SkillContext context,
            final CoreSkillFrame frame
    ) {
        return context.riskScore() > MAXIMUM_SAFE_DANGER
                || frame.danger() > MAXIMUM_SAFE_DANGER;
    }

    private SkillTickResult fail(final String code) {
        lookupToken.incrementAndGet();
        actuator.stop();
        failure = SkillFailure.of(code);
        phase = Phase.FAILED;
        return SkillTickResult.failed(failure);
    }

    private static Optional<SkillFailure> rejected(
            final String suffix
    ) {
        return Optional.of(SkillFailure.of(NAME + "." + suffix));
    }

    private static double distance(
            final PerceptionVec3 left,
            final PerceptionVec3 right
    ) {
        return left.subtract(right).length();
    }

    private record LookupCompletion(
            List<VerifiedPortalEdge> edges,
            Throwable error
    ) {
        private LookupCompletion {
            edges = edges == null ? null : List.copyOf(edges);
        }
    }

    private enum Phase {
        IDLE,
        LOOKUP,
        TRAVELLING,
        ENTERING,
        COMPLETED,
        CANCELLED,
        FAILED
    }
}
