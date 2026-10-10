package dev.mcai.companion.skills.building;

import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.navigation.PerceptionNavMapper;
import dev.mcai.companion.perception.FirstPersonCrosshairSampler;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Production observation adapter for dynamic building skills.
 *
 * <p>Call {@link #publish(SemanticObservation)} from the existing fair
 * semantic-observation callback. The source never reads level blocks or
 * chunks; only the bound player's current clock/session is refreshed at
 * skill-tick time.</p>
 */
public final class ServerShelterFrameSource implements ShelterFrameSource {
    /*
     * Every shelter frame owns a rolling navigation map, so retaining 2,048
     * frames multiplied the 16,384-voxel bound into tens of millions of map
     * entries and eventually stalled the server. Final block mutations use
     * fresh tick-local evidence; 64 semantic bindings preserve bounded exact
     * revision lookup while preventing history from dominating the heap.
     */
    private static final int MAX_RETAINED_OBSERVATIONS = 64;
    private static final int MAX_RETAINED_VISIBLE_ENTITIES = 64;

    private final MinecraftServer server;
    private final UUID expectedPlayerId;
    private final PerceptionNavMapper mapper;
    private final LinkedHashMap<Long, ShelterFrame> history =
            new LinkedHashMap<>();
    private final LinkedHashMap<UUID, RecentVisibleEntity>
            recentVisibleEntities = new LinkedHashMap<>();
    private ShelterFrame latest;
    private long publishedSessionGeneration = -1;
    private DimensionRef publishedDimension;
    private long latestPublishedGameTime = -1;

    public ServerShelterFrameSource(
            MinecraftServer server,
            UUID expectedPlayerId
    ) {
        this(server, expectedPlayerId, new PerceptionNavMapper());
    }

    public ServerShelterFrameSource(
            MinecraftServer server,
            UUID expectedPlayerId,
            PerceptionNavMapper mapper
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public synchronized ShelterFrame publish(
            SemanticObservation observation
    ) {
        Objects.requireNonNull(observation, "observation");
        requireServerThread();
        if (!expectedPlayerId.equals(observation.body().playerId())) {
            throw new IllegalArgumentException(
                    "Observation player does not match shelter source"
            );
        }
        AiPlayerManager.Status status = AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE || !status.online()) {
            throw new IllegalStateException(
                    "Cannot publish without an active companion body"
            );
        }
        final var body = observation.body();
        final DimensionRef dimension =
                DimensionRef.parse(body.dimensionId());
        if (publishedSessionGeneration
                    != status.sessionGeneration()
                || publishedDimension == null
                || !publishedDimension.equals(dimension)
                || body.gameTime() < latestPublishedGameTime) {
            history.clear();
            recentVisibleEntities.clear();
        }
        publishedSessionGeneration = status.sessionGeneration();
        publishedDimension = dimension;
        latestPublishedGameTime = body.gameTime();
        recentVisibleEntities.entrySet().removeIf(entry ->
                !entry.getValue().isFreshAt(
                        body.gameTime(),
                        ShelterFrame.MAXIMUM_RECENT_ENTITY_AGE_TICKS
                )
        );
        for (VisibleEntity entity : observation.visibleEntities()) {
            recentVisibleEntities.put(
                    entity.entityId(),
                    new RecentVisibleEntity(
                            entity,
                            body.gameTime(),
                            observation.sequence()
                    )
            );
        }
        trimRecentVisibleEntities();
        latest = ShelterFrame.from(
                observation,
                mapper.ingest(observation),
                status.sessionGeneration(),
                recentEntitySnapshot()
        );
        history.put(latest.observationRevision(), latest);
        while (history.size() > MAX_RETAINED_OBSERVATIONS) {
            history.remove(history.keySet().iterator().next());
        }
        return latest;
    }

    private List<RecentVisibleEntity> recentEntitySnapshot() {
        return recentVisibleEntities.values().stream()
                .sorted(Comparator
                        .comparingLong(
                                RecentVisibleEntity::observedAtGameTime
                        )
                        .reversed()
                        .thenComparing(entity ->
                                entity.entity().entityId()
                        ))
                .toList();
    }

    private void trimRecentVisibleEntities() {
        while (recentVisibleEntities.size()
                > MAX_RETAINED_VISIBLE_ENTITIES) {
            final UUID oldest = recentVisibleEntities.entrySet()
                    .stream()
                    .min(Comparator
                            .<Map.Entry<UUID, RecentVisibleEntity>>
                            comparingLong(entry ->
                                    entry.getValue()
                                            .observedAtGameTime()
                            )
                            .thenComparing(Map.Entry::getKey))
                    .map(java.util.Map.Entry::getKey)
                    .orElseThrow();
            recentVisibleEntities.remove(oldest);
        }
    }

    @Override
    public synchronized Optional<ShelterFrame> current() {
        return currentFrame(latest);
    }

    @Override
    public synchronized Optional<ShelterFrame> atObservation(
            final long observationRevision
    ) {
        return currentFrame(history.get(observationRevision));
    }

    @Override
    public synchronized Optional<VisibleBlockFace>
            currentCrosshairBlock() {
        if (current().isEmpty()) {
            return Optional.empty();
        }
        return AiPlayerManager.onlinePlayer(server)
                .filter(player ->
                        expectedPlayerId.equals(player.getUUID())
                                && player.connection != null
                                && !player.isRemoved()
                )
                .flatMap(FirstPersonCrosshairSampler::sample);
    }

    private Optional<ShelterFrame> currentFrame(
            final ShelterFrame frame
    ) {
        if (!server.isSameThread() || frame == null) {
            return Optional.empty();
        }
        AiPlayerManager.Status status = AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE
                || !status.online()
                || status.sessionGeneration() != frame.sessionGeneration()) {
            return Optional.empty();
        }
        ServerPlayer player = AiPlayerManager.onlinePlayer(server)
                .orElse(null);
        if (player == null
                || player.connection == null
                || player.isRemoved()
                || !expectedPlayerId.equals(player.getUUID())
                || !frame.dimension().equals(DimensionRef.parse(
                        player.level().dimension().identifier().toString()
                ))) {
            return Optional.empty();
        }
        long gameTime = player.level().getGameTime();
        if (gameTime < frame.observedAtGameTime()) {
            return Optional.empty();
        }
        return Optional.of(frame.atGameTime(gameTime));
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Shelter observations require the server thread"
            );
        }
    }
}
