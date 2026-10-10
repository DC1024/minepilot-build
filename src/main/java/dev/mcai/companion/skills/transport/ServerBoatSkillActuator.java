package dev.mcai.companion.skills.transport;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.FairPlayerActuator;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.network.protocol.game.ServerboundPaddleBoatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

/**
 * Server-owned boat controller for the clientless companion.
 *
 * <p>Vanilla makes a player-controlled boat client-authoritative: the client
 * calls the private boat control step and sends vehicle positions, while the
 * dedicated server only validates those positions. The headless player has no
 * such client. A narrowly scoped common Mixin makes only the marked
 * companion's controlled boat locally authoritative on the server, allowing
 * {@link AbstractBoat#tick()} to run its own exact float, control, collision
 * and movement path. This actuator therefore supplies input and paddle state
 * only; it never integrates motion, writes a position, teleports, scans
 * blocks, or sends a fabricated vehicle-position packet.</p>
 */
public final class ServerBoatSkillActuator
        implements BoatSkillActuator {
    private final MinecraftServer server;
    private final UUID expectedPlayerId;

    private ServerPlayer boundPlayer;
    private ServerGamePacketListenerImpl boundConnection;
    private FairPlayerActuator fairActuator;
    private long boundSessionGeneration = -1;
    private AbstractBoat inputBoat;
    private long lastDriveTick = Long.MIN_VALUE;

    public ServerBoatSkillActuator(
            MinecraftServer server,
            UUID expectedPlayerId
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
    }

    @Override
    public OptionalLong sessionGeneration() {
        if (!server.isSameThread()) {
            return OptionalLong.empty();
        }
        Optional<Binding> binding = resolve();
        return binding.isEmpty()
                ? OptionalLong.empty()
                : OptionalLong.of(
                        binding.orElseThrow().sessionGeneration()
                );
    }

    @Override
    public ActionOutcome enterBoat(UUID observedBoatId) {
        Objects.requireNonNull(observedBoatId, "observedBoatId");
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        Optional<Binding> resolved = resolve();
        if (resolved.isEmpty()) {
            return ActionOutcome.PLAYER_UNAVAILABLE;
        }
        Binding binding = resolved.orElseThrow();
        ServerPlayer player = binding.player();
        if (player.isPassenger() || !player.isAlive()) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }
        Entity entity = player.level().getEntity(observedBoatId);
        if (!(entity instanceof AbstractBoat boat)
                || boat.isRemoved()
                || !boat.isAlive()
                || boat.level() != player.level()) {
            return ActionOutcome.TARGET_NOT_FOUND;
        }
        return binding.actuator().interactEntity(
                observedBoatId,
                ActionHand.MAIN_HAND
        );
    }

    @Override
    public ActionOutcome driveBoat(
            UUID expectedBoatId,
            BoatControlIntent intent
    ) {
        Objects.requireNonNull(expectedBoatId, "expectedBoatId");
        Objects.requireNonNull(intent, "intent");
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        Optional<ControlledBoat> controlled = controlledBoat(
                expectedBoatId
        );
        if (controlled.isEmpty()) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }
        long tick = Integer.toUnsignedLong(server.getTickCount());
        if (tick == lastDriveTick) {
            return ActionOutcome.IN_PROGRESS;
        }
        lastDriveTick = tick;

        ControlledBoat control = controlled.orElseThrow();
        AbstractBoat boat = control.boat();
        /*
         * The marked headless controller must activate the scoped authority
         * Mixin. Refuse input if it is absent rather than falling back to a
         * second, approximate movement implementation.
         */
        if (!HeadlessBoatAuthority.usesServerLocalControl(boat)
                || !boat.isLocalInstanceAuthoritative()) {
            return ActionOutcome.WORLD_DENIED;
        }
        inputBoat = boat;

        boat.setInput(
                intent.left(),
                intent.right(),
                intent.forward(),
                intent.backward()
        );
        boolean paddleLeft = intent.right() && !intent.left()
                || intent.forward();
        boolean paddleRight = intent.left() && !intent.right()
                || intent.forward();
        control.connection().handlePaddleBoat(
                new ServerboundPaddleBoatPacket(
                        paddleLeft,
                        paddleRight
                )
        );
        return ActionOutcome.DISPATCHED;
    }

    @Override
    public ActionOutcome stopBoat(UUID expectedBoatId) {
        Objects.requireNonNull(expectedBoatId, "expectedBoatId");
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        Optional<ControlledBoat> controlled = controlledBoat(
                expectedBoatId
        );
        if (controlled.isEmpty()) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
        ControlledBoat control = controlled.orElseThrow();
        control.boat().setInput(false, false, false, false);
        control.connection().handlePaddleBoat(
                new ServerboundPaddleBoatPacket(false, false)
        );
        return ActionOutcome.DISPATCHED;
    }

    @Override
    public ActionOutcome dismountBoat(UUID expectedBoatId) {
        Objects.requireNonNull(expectedBoatId, "expectedBoatId");
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        Optional<ControlledBoat> controlled = controlledBoat(
                expectedBoatId
        );
        if (controlled.isEmpty()) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }
        ControlledBoat control = controlled.orElseThrow();
        stopBoat(expectedBoatId);
        // LivingEntity.stopRiding invokes the boat's normal vanilla safe
        // dismount-position selection. The skill calls this only after a
        // recent first-person surface observation.
        control.player().stopRiding();
        clearControl();
        return ActionOutcome.DISPATCHED;
    }

    /**
     * Releases paddles during runtime close/body replacement.
     */
    public ActionOutcome quiesceNow() {
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        if (inputBoat == null) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
        UUID boatId = inputBoat.getUUID();
        ActionOutcome outcome = stopBoat(boatId);
        clearControl();
        return outcome;
    }

    private Optional<ControlledBoat> controlledBoat(UUID expectedBoatId) {
        Optional<Binding> resolved = resolve();
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Binding binding = resolved.orElseThrow();
        ServerPlayer player = binding.player();
        if (!(player.getControlledVehicle() instanceof AbstractBoat boat)
                || boat.isRemoved()
                || !boat.isAlive()
                || boat.getControllingPassenger() != player
                || !boat.getUUID().equals(expectedBoatId)) {
            return Optional.empty();
        }
        return Optional.of(new ControlledBoat(
                player,
                binding.connection(),
                boat
        ));
    }

    private Optional<Binding> resolve() {
        AiPlayerManager.Status status = AiPlayerManager.status(server);
        ServerPlayer player = AiPlayerManager.onlinePlayer(server)
                .orElse(null);
        if (status.state() != SessionState.ACTIVE
                || !status.online()
                || player == null
                || player.connection == null
                || player.isRemoved()
                || !player.isAlive()
                || !expectedPlayerId.equals(player.getUUID())) {
            clearBinding();
            return Optional.empty();
        }
        if (boundPlayer != player
                || boundConnection != player.connection
                || boundSessionGeneration
                != status.sessionGeneration()) {
            boundPlayer = player;
            boundConnection = player.connection;
            fairActuator = new FairPlayerActuator(player);
            boundSessionGeneration = status.sessionGeneration();
            clearControl();
        }
        return Optional.of(new Binding(
                boundPlayer,
                boundConnection,
                fairActuator,
                boundSessionGeneration
        ));
    }

    private void clearBinding() {
        boundPlayer = null;
        boundConnection = null;
        fairActuator = null;
        boundSessionGeneration = -1;
        clearControl();
    }

    private void clearControl() {
        inputBoat = null;
        lastDriveTick = Long.MIN_VALUE;
    }

    private record Binding(
            ServerPlayer player,
            ServerGamePacketListenerImpl connection,
            FairPlayerActuator actuator,
            long sessionGeneration
    ) {
    }

    private record ControlledBoat(
            ServerPlayer player,
            ServerGamePacketListenerImpl connection,
            AbstractBoat boat
    ) {
    }
}
