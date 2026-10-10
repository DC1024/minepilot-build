package dev.mcai.companion.skills.core;

import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.navigation.PerceptionNavMapper;
import dev.mcai.companion.perception.DangerKind;
import dev.mcai.companion.perception.DangerSignal;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionProvenance;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * Server-owned frame source with a 20 TPS body pose over the most recent fair
 * semantic navigation snapshot.
 *
 * <p>{@link #publish(SemanticObservation)} is called by the observation stage
 * (normally 2-5 Hz). Every {@link #current()} call resolves the current
 * {@link ServerPlayer} by UUID, so respawn is handled without retaining a
 * stale player object. Only player-owned pose fields are read at 20 TPS; no
 * level, chunk, structure, or hidden block query is performed.</p>
 */
public final class ServerCoreSkillFrameSource
        implements CoreSkillFrameSource {
    private static final int MAX_RETAINED_OBSERVATIONS = 64;
    /*
     * The model hard deadline is 90 seconds and semantic publication may run
     * at 5 Hz. Keep enough compact bindings for that entire interval plus
     * scheduling margin, without retaining 512 complete navigation maps.
     */
    private static final int MAX_RETAINED_ENTITY_BINDINGS = 512;
    private static final long RECENT_DAMAGE_TICKS = 40L;
    private static final long RECENT_AUDIBLE_SOUND_TICKS = 20L;
    private static final double MAX_AUDIBLE_HOSTILE_RANGE = 16.0;
    private static final long PLAYER_WARNING_TICKS = 60L;

    private final MinecraftServer server;
    private final UUID expectedPlayerId;
    private final MappedCoreSkillFrameSource semanticFrames;
    private final LinkedHashMap<Long, CoreSkillFrame> history =
            new LinkedHashMap<>();
    private final LinkedHashMap<Long, EntityBindingFrame>
            entityBindingHistory = new LinkedHashMap<>();
    private long publishedSessionGeneration = -1;
    private RecentDamage recentDamage;
    private RecentAudibleHostileSound recentAudibleHostileSound;
    private RecentPlayerWarning recentPlayerWarning;

    public ServerCoreSkillFrameSource(
            MinecraftServer server,
            UUID expectedPlayerId
    ) {
        this(server, expectedPlayerId, new PerceptionNavMapper());
    }

    public ServerCoreSkillFrameSource(
            MinecraftServer server,
            UUID expectedPlayerId,
            PerceptionNavMapper mapper
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        semanticFrames = new MappedCoreSkillFrameSource(
                Objects.requireNonNull(mapper, "mapper")
        );
    }

    /**
     * Ingests only a fair first-person observation. Mapping is intentionally
     * done outside the skill tick's two-millisecond budget.
     */
    public synchronized CoreSkillFrame publish(
            SemanticObservation observation
    ) {
        Objects.requireNonNull(observation, "observation");
        if (!expectedPlayerId.equals(observation.body().playerId())) {
            throw new IllegalArgumentException(
                    "Observation player does not match frame source"
            );
        }
        final AiPlayerManager.Status status =
                AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE
                || !status.online()) {
            throw new IllegalStateException(
                    "Cannot publish an observation without an active body"
            );
        }
        if (publishedSessionGeneration
                != status.sessionGeneration()) {
            history.clear();
            entityBindingHistory.clear();
            semanticFrames.reset();
            publishedSessionGeneration =
                    status.sessionGeneration();
        }
        final CoreSkillFrame published =
                semanticFrames.publish(observation);
        history.put(published.observationRevision(), published);
        while (history.size() > MAX_RETAINED_OBSERVATIONS) {
            history.remove(history.keySet().iterator().next());
        }
        entityBindingHistory.put(
                published.observationRevision(),
                new EntityBindingFrame(
                        published.dimension(),
                        published.visibleEntities()
                )
        );
        while (entityBindingHistory.size()
                > MAX_RETAINED_ENTITY_BINDINGS) {
            entityBindingHistory.remove(
                    entityBindingHistory.keySet().iterator().next()
            );
        }
        return published;
    }

    @Override
    public synchronized Optional<CoreSkillFrame> current() {
        if (!server.isSameThread()) {
            return Optional.empty();
        }
        if (!currentSessionMatchesPublication()) {
            return Optional.empty();
        }
        Optional<CoreSkillFrame> semantic = semanticFrames.current();
        if (semantic.isEmpty()) {
            return Optional.empty();
        }
        return withLivePlayerState(semantic.orElseThrow());
    }

    @Override
    public synchronized Optional<CoreSkillFrame> atObservation(
            final long observationRevision
    ) {
        if (!server.isSameThread()) {
            return Optional.empty();
        }
        if (!currentSessionMatchesPublication()) {
            return Optional.empty();
        }
        final CoreSkillFrame semantic = history.get(
                observationRevision
        );
        return semantic == null
                ? Optional.empty()
                : withLivePlayerState(semantic);
    }

    @Override
    public synchronized Optional<VisibleEntityBinding>
            visibleEntityAtObservation(
                    final long observationRevision,
                    final int observationIndex
            ) {
        if (!server.isSameThread()
                || observationIndex < 0
                || !currentSessionMatchesPublication()) {
            return Optional.empty();
        }
        final EntityBindingFrame frame =
                entityBindingHistory.get(observationRevision);
        if (frame == null
                || observationIndex >= frame.entities().size()) {
            return Optional.empty();
        }
        return Optional.of(new VisibleEntityBinding(
                frame.dimension(),
                frame.entities().get(observationIndex)
        ));
    }

    @Override
    public synchronized Optional<TrackablePlayer> trackablePlayer(
            final UUID playerId
    ) {
        Objects.requireNonNull(playerId, "playerId");
        if (!server.isSameThread()
                || !currentSessionMatchesPublication()) {
            return Optional.empty();
        }
        final ServerPlayer target = server.getPlayerList().getPlayer(
                playerId
        );
        if (target == null
                || target.isRemoved()
                || !target.isAlive()
                || target.isSpectator()
                || target.isCrouching()) {
            return Optional.empty();
        }
        return Optional.of(new TrackablePlayer(
                target.getUUID(),
                DimensionRef.parse(
                        target.level().dimension().identifier().toString()
                ),
                new PerceptionVec3(
                        target.getX(),
                        target.getY(),
                        target.getZ()
                ),
                target.level().getGameTime()
        ));
    }

    /**
     * Drops all body-local semantic geometry after remove, respawn, or
     * replacement. Keeping the old session's nearby voxels can make a new
     * body plan against terrain it never observed.
     */
    public synchronized void invalidateBodySession() {
        history.clear();
        entityBindingHistory.clear();
        semanticFrames.reset();
        publishedSessionGeneration = -1;
        recentDamage = null;
        recentAudibleHostileSound = null;
        recentPlayerWarning = null;
    }

    /**
     * Records an authorized teammate's warning as a bounded directional
     * cue. The text may cause a defensive scan or separation maneuver, but
     * never creates an unseen entity or supplies its exact coordinates.
     */
    public synchronized void recordPlayerThreatWarning(
            final String message
    ) {
        if (!server.isSameThread()) {
            return;
        }
        final AiPlayerManager.Status status =
                AiPlayerManager.status(server);
        final ServerPlayer player =
                server.getPlayerList().getPlayer(expectedPlayerId);
        if (status.state() != SessionState.ACTIVE
                || !status.online()
                || player == null
                || player.isRemoved()) {
            return;
        }
        final PerceptionVec3 look = vector(
                player.getLookAngle()
        );
        PlayerThreatWarningCue.parse(message, look).ifPresent(cue ->
                recentPlayerWarning = new RecentPlayerWarning(
                        status.sessionGeneration(),
                        DimensionRef.parse(
                                player.level()
                                        .dimension()
                                        .identifier()
                                        .toString()
                        ),
                        player.level().getGameTime(),
                        cue
                )
        );
    }

    /**
     * Records only the sensory consequence of damage received by this body.
     * It does not expose an occluded attacker's identity or exact position.
     * The optional normalized direction mirrors the directional hit cue a
     * normal player receives and expires after two seconds.
     */
    public synchronized void recordDamage(
            final ServerPlayer player,
            final DamageSource source,
            final float amount
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(source, "source");
        if (!server.isSameThread()
                || !expectedPlayerId.equals(player.getUUID())
                || player.level().getServer() != server
                || !Float.isFinite(amount)
                || amount <= 0.0F) {
            return;
        }
        final AiPlayerManager.Status status =
                AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE
                || !status.online()) {
            return;
        }
        final Optional<PerceptionVec3> direction =
                damageDirection(player, source);
        final double healthFraction = amount
                / Math.max(1.0F, player.getMaxHealth());
        recentDamage = new RecentDamage(
                status.sessionGeneration(),
                DimensionRef.parse(
                        player.level()
                                .dimension()
                                .identifier()
                                .toString()
                ),
                player.level().getGameTime(),
                Math.min(
                        1.0,
                        Math.max(0.75, healthFraction * 2.0)
                ),
                direction
        );
    }

    /**
     * Records the bounded sensory consequence of a nearby hostile sound.
     *
     * <p>This intentionally retains neither the sound id nor the source
     * position/entity.  Only an approximate direction, an upper distance
     * bound derived from the event volume, and a short-lived threat level are
     * allowed into the fair perception frame.  The local emergency lane can
     * therefore react before the next semantic sample without granting the
     * model an entity radar.</p>
     */
    public synchronized void recordAudibleHostileSound(
            final ServerPlayer player,
            final Entity source,
            final float volume
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(source, "source");
        if (!server.isSameThread()
                || !expectedPlayerId.equals(player.getUUID())
                || player.level().getServer() != server
                || source == player
                || source.isRemoved()
                || source.level() != player.level()
                || !Float.isFinite(volume)
                || volume <= 0.0F) {
            return;
        }
        final AiPlayerManager.Status status = AiPlayerManager.status(server);
        if (status.state() != SessionState.ACTIVE || !status.online()) {
            return;
        }
        final double normalizedVolume = Math.min(1.0, Math.max(0.0, volume));
        final double range = Math.min(
                MAX_AUDIBLE_HOSTILE_RANGE,
                Math.max(4.0, 4.0 + normalizedVolume * 12.0)
        );
        final Vec3 delta = source.position().subtract(player.position());
        /*
         * PlayLevelSoundEvent is a server-side event, not a client listener;
         * a server may emit it even when no real client would hear the sound.
         * Apply the bounded audible radius before retaining any cue so a far
         * away hostile can never become an entity-radar substitute.
         */
        if (delta.lengthSqr() > range * range) {
            return;
        }
        final Optional<PerceptionVec3> direction = delta.lengthSqr() <= 1.0E-12
                ? Optional.empty()
                : Optional.of(vector(delta).normalized());
        recentAudibleHostileSound = new RecentAudibleHostileSound(
                status.sessionGeneration(),
                DimensionRef.parse(
                        player.level().dimension().identifier().toString()
                ),
                player.level().getGameTime(),
                Math.min(0.90, Math.max(0.50, 0.50 + normalizedVolume * 0.40)),
                range,
                direction
        );
    }

    /**
     * Returns only whether the current fair frame still contains a recent
     * contact/hostile warning. Conversation may use this as a correction
     * signal, but it never receives an attacker identity or an occluded
     * position. This bridges the gap between the 20 TPS damage interrupt and
     * the ordinary 2--5 Hz semantic JSON sample.
     */
    public synchronized boolean hasRecentThreatSignal() {
        if (!server.isSameThread()) {
            return false;
        }
        return current().map(frame -> frame.dangerSignals().stream()
                .anyMatch(signal ->
                        signal.kind() == DangerKind.THREAT_CONTACT
                                || signal.kind()
                                    == DangerKind.HOSTILE_PROXIMITY
                                || signal.kind()
                                    == DangerKind.PROJECTILE_PROXIMITY
                )).orElse(false);
    }

    private boolean currentSessionMatchesPublication() {
        final AiPlayerManager.Status status =
                AiPlayerManager.status(server);
        return status.state() == SessionState.ACTIVE
                && status.online()
                && status.sessionGeneration()
                    == publishedSessionGeneration;
    }

    private Optional<CoreSkillFrame> withLivePlayerState(
            final CoreSkillFrame frame
    ) {
        ServerPlayer player = server.getPlayerList().getPlayer(
                expectedPlayerId
        );
        if (player == null
                || player.connection == null
                || player.isRemoved()
                || !expectedPlayerId.equals(player.getUUID())) {
            return Optional.empty();
        }
        CoreSkillPose pose = poseOf(player);
        if (!frame.dimension().equals(pose.dimension())) {
            // A portal transition invalidates the old local map. Wait for the
            // next semantic publication instead of reusing cross-dimension
            // observations.
            return Optional.empty();
        }
        return Optional.of(frame.withLivePlayerState(
                pose,
                player.getHealth(),
                player.getMaxHealth(),
                player.getFoodData().getFoodLevel(),
                summarizeInventory(player),
                heldItem(player.getMainHandItem()),
                heldItem(player.getOffhandItem()),
                liveDangers(player, frame)
        ));
    }

    private static CoreSkillPose poseOf(ServerPlayer player) {
        Vec3 position = player.position();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        return new CoreSkillPose(
                player.getUUID(),
                DimensionRef.parse(
                        player.level().dimension().identifier().toString()
                ),
                player.level().getGameTime(),
                vector(position),
                vector(eye),
                vector(look),
                player.onGround(),
                player.isInWater()
        );
    }

    private static PerceptionVec3 vector(Vec3 vector) {
        return new PerceptionVec3(vector.x, vector.y, vector.z);
    }

    private record EntityBindingFrame(
            DimensionRef dimension,
            List<dev.mcai.companion.perception.VisibleEntity> entities
    ) {
        private EntityBindingFrame {
            Objects.requireNonNull(dimension, "dimension");
            entities = List.copyOf(entities);
        }
    }

    private List<DangerSignal> liveDangers(
            final ServerPlayer player,
            final CoreSkillFrame semantic
    ) {
        final List<DangerSignal> result = new ArrayList<>();
        semantic.dangerSignals().stream()
                .filter(signal ->
                        signal.provenance()
                                != PerceptionProvenance.BODY_HAZARD
                )
                .forEach(result::add);
        if (player.isOnFire()) {
            result.add(new DangerSignal(
                    DangerKind.ON_FIRE,
                    1.0,
                    0.0,
                    Optional.empty(),
                    PerceptionProvenance.BODY_HAZARD
            ));
        }
        final double airRatio = (double) player.getAirSupply()
                / player.getMaxAirSupply();
        if (airRatio < 0.25) {
            result.add(new DangerSignal(
                    DangerKind.LOW_AIR,
                    Math.min(
                            1.0,
                            Math.max(0.25, 1.0 - airRatio)
                    ),
                    0.0,
                    Optional.empty(),
                    PerceptionProvenance.BODY_HAZARD
            ));
        }
        /*
         * A five-block fall is over before the old three-block threshold
         * leaves enough ticks to aim and use a bucket.  Body fall distance
         * and velocity are self-state, so publish the descending condition
         * early at 20 TPS.  The emergency controller still requires a
         * currently observed, genuinely dangerous drop before spending a
         * clutch item; ordinary hops therefore remain untouched.
         */
        if (!player.onGround()
                && !player.isInWater()
                && player.fallDistance > 0.5F
                && player.getDeltaMovement().y() < -0.08) {
            result.add(new DangerSignal(
                    DangerKind.FALLING,
                    Math.min(1.0, player.fallDistance / 20.0),
                    0.0,
                    Optional.empty(),
                    PerceptionProvenance.BODY_HAZARD
            ));
        }
        final RecentDamage damage = recentDamage;
        final AiPlayerManager.Status status =
                AiPlayerManager.status(server);
        if (damage != null
                && damage.sessionGeneration()
                    == status.sessionGeneration()
                && damage.dimension().equals(
                    semantic.dimension()
                )) {
            final long age = player.level().getGameTime()
                    - damage.gameTime();
            if (age >= 0L && age <= RECENT_DAMAGE_TICKS) {
                result.add(new DangerSignal(
                        DangerKind.THREAT_CONTACT,
                        damage.severity(),
                        0.0,
                        damage.direction(),
                        PerceptionProvenance.RECENT_DAMAGE_EVENT
                ));
            } else if (age > RECENT_DAMAGE_TICKS) {
                recentDamage = null;
            }
        }
        final RecentAudibleHostileSound audible = recentAudibleHostileSound;
        if (audible != null
                && audible.sessionGeneration()
                    == status.sessionGeneration()
                && audible.dimension().equals(semantic.dimension())) {
            final long age = player.level().getGameTime()
                    - audible.gameTime();
            if (age >= 0L && age <= RECENT_AUDIBLE_SOUND_TICKS) {
                result.add(new DangerSignal(
                        DangerKind.HOSTILE_PROXIMITY,
                        audible.severity(),
                        audible.distanceUpperBound(),
                        audible.direction(),
                        PerceptionProvenance.AUDIBLE_HOSTILE_SOUND
                ));
            } else if (age > RECENT_AUDIBLE_SOUND_TICKS) {
                recentAudibleHostileSound = null;
            }
        }
        final RecentPlayerWarning warning = recentPlayerWarning;
        if (warning != null
                && warning.sessionGeneration()
                    == status.sessionGeneration()
                && warning.dimension().equals(
                    semantic.dimension()
                )) {
            final long age = player.level().getGameTime()
                    - warning.gameTime();
            if (age >= 0L && age <= PLAYER_WARNING_TICKS) {
                result.add(new DangerSignal(
                        DangerKind.HOSTILE_PROXIMITY,
                        warning.cue().severity(),
                        4.0,
                        warning.cue().threatDirection(),
                        PerceptionProvenance
                                .AUTHORIZED_PLAYER_WARNING
                ));
            } else if (age > PLAYER_WARNING_TICKS) {
                recentPlayerWarning = null;
            }
        }
        return List.copyOf(result);
    }

    private static Optional<PerceptionVec3> damageDirection(
            final ServerPlayer player,
            final DamageSource source
    ) {
        final Entity causing = source.getEntity();
        final Entity direct = source.getDirectEntity();
        final Vec3 sourcePosition;
        if (causing != null && causing != player) {
            sourcePosition = causing.position();
        } else if (direct != null && direct != player) {
            sourcePosition = direct.position();
        } else {
            sourcePosition = source.getSourcePosition();
        }
        if (sourcePosition == null) {
            return Optional.empty();
        }
        final PerceptionVec3 delta = vector(
                sourcePosition.subtract(player.position())
        );
        return delta.lengthSquared() <= 1.0E-12
                ? Optional.empty()
                : Optional.of(delta.normalized());
    }

    private static HeldItemSummary heldItem(final ItemStack stack) {
        if (stack.isEmpty()) {
            return HeldItemSummary.empty();
        }
        return new HeldItemSummary(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.getCount(),
                stack.isDamageableItem()
                        ? stack.getDamageValue()
                        : 0,
                stack.isDamageableItem()
                        ? stack.getMaxDamage()
                        : 0
        );
    }

    private static List<InventoryItemSummary> summarizeInventory(
            final ServerPlayer player
    ) {
        final Map<String, Integer> counts = new TreeMap<>();
        for (int slot = 0;
                slot < player.getInventory().getContainerSize();
                slot++) {
            final ItemStack stack =
                    player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                final String id = BuiltInRegistries.ITEM
                        .getKey(stack.getItem())
                        .toString();
                counts.merge(id, stack.getCount(), Math::addExact);
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new InventoryItemSummary(
                        entry.getKey(),
                        entry.getValue()
                ))
                .toList();
    }

    private record RecentDamage(
            long sessionGeneration,
            DimensionRef dimension,
            long gameTime,
            double severity,
            Optional<PerceptionVec3> direction
    ) {
        private RecentDamage {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(direction, "direction");
        }
    }

    private record RecentAudibleHostileSound(
            long sessionGeneration,
            DimensionRef dimension,
            long gameTime,
            double severity,
            double distanceUpperBound,
            Optional<PerceptionVec3> direction
    ) {
        private RecentAudibleHostileSound {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(direction, "direction");
        }
    }

    private record RecentPlayerWarning(
            long sessionGeneration,
            DimensionRef dimension,
            long gameTime,
            PlayerThreatWarningCue cue
    ) {
        private RecentPlayerWarning {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(cue, "cue");
        }
    }
}
