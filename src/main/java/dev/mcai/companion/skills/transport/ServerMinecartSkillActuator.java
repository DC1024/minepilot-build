package dev.mcai.companion.skills.transport;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.action.FairPlayerActuator;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.action.MovementIntent;
import dev.mcai.companion.embodiment.AiPlayerManager;
import dev.mcai.companion.embodiment.SessionState;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

/**
 * Uses ordinary interaction plus the shared leased player-input path for
 * rideable minecarts. Rail physics, powered-rail acceleration, collision and
 * route choice remain entirely vanilla.
 */
public final class ServerMinecartSkillActuator
        implements MinecartSkillActuator {
    private final MinecraftServer server;
    private final UUID expectedPlayerId;
    private final CoreSkillActuator riderInput;

    private ServerPlayer boundPlayer;
    private ServerGamePacketListenerImpl boundConnection;
    private FairPlayerActuator fairActuator;
    private long boundSessionGeneration = -1;

    public ServerMinecartSkillActuator(
            final MinecraftServer server,
            final UUID expectedPlayerId,
            final CoreSkillActuator riderInput
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.expectedPlayerId = Objects.requireNonNull(
                expectedPlayerId,
                "expectedPlayerId"
        );
        this.riderInput = Objects.requireNonNull(
                riderInput,
                "riderInput"
        );
    }

    @Override
    public OptionalLong sessionGeneration() {
        if (!server.isSameThread()) {
            return OptionalLong.empty();
        }
        return resolve()
                .map(binding -> OptionalLong.of(
                        binding.sessionGeneration()
                ))
                .orElseGet(OptionalLong::empty);
    }

    @Override
    public ActionOutcome enterMinecart(
            final UUID observedMinecartId
    ) {
        Objects.requireNonNull(
                observedMinecartId,
                "observedMinecartId"
        );
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        final Optional<Binding> maybeBinding = resolve();
        if (maybeBinding.isEmpty()) {
            return ActionOutcome.PLAYER_UNAVAILABLE;
        }
        final Binding binding = maybeBinding.orElseThrow();
        final ServerPlayer player = binding.player();
        if (player.isPassenger() || !player.isAlive()) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }
        final Entity entity =
                player.level().getEntity(observedMinecartId);
        if (!(entity instanceof AbstractMinecart minecart)
                || minecart.isRemoved()
                || !minecart.isAlive()
                || minecart.level() != player.level()
                || !"minecraft:minecart".equals(
                        net.minecraft.core.registries.BuiltInRegistries
                                .ENTITY_TYPE
                                .getKey(minecart.getType())
                                .toString()
                )) {
            return ActionOutcome.TARGET_NOT_FOUND;
        }
        return binding.actuator().interactEntity(
                observedMinecartId,
                ActionHand.MAIN_HAND
        );
    }

    @Override
    public ActionOutcome driveMinecart(
            final UUID expectedMinecartId,
            final float targetYawDegrees,
            final boolean forward,
            final boolean backward
    ) {
        Objects.requireNonNull(
                expectedMinecartId,
                "expectedMinecartId"
        );
        if (!Float.isFinite(targetYawDegrees)
                || forward && backward) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        final Optional<ControlledMinecart> controlled =
                controlledMinecart(expectedMinecartId);
        if (controlled.isEmpty()) {
            return ActionOutcome.INVALID_PLAYER_STATE;
        }
        final ControlledMinecart control =
                controlled.orElseThrow();
        final ServerPlayer player = control.player();
        final ActionOutcome look = riderInput.look(
                new LookIntent(targetYawDegrees, player.getXRot())
        );
        if (!look.accepted()) {
            return look;
        }
        return riderInput.move(new MovementIntent(
                forward ? 1.0 : backward ? -1.0 : 0.0,
                0.0,
                false,
                false
        ));
    }

    @Override
    public ActionOutcome stopMinecartInput(
            final UUID expectedMinecartId
    ) {
        Objects.requireNonNull(
                expectedMinecartId,
                "expectedMinecartId"
        );
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        final Optional<ControlledMinecart> controlled =
                controlledMinecart(expectedMinecartId);
        if (controlled.isEmpty()) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
        return riderInput.stop();
    }

    @Override
    public ActionOutcome dismountMinecart(
            final UUID expectedMinecartId
    ) {
        final Optional<ControlledMinecart> controlled =
                controlledMinecart(expectedMinecartId);
        if (controlled.isEmpty()) {
            return server.isSameThread()
                    ? ActionOutcome.INVALID_PLAYER_STATE
                    : ActionOutcome.WRONG_THREAD;
        }
        stopMinecartInput(expectedMinecartId);
        controlled.orElseThrow().player().stopRiding();
        return ActionOutcome.DISPATCHED;
    }

    public ActionOutcome quiesceNow() {
        if (!server.isSameThread()) {
            return ActionOutcome.WRONG_THREAD;
        }
        final Optional<Binding> binding = resolve();
        if (binding.isEmpty()) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
        final ServerPlayer player =
                binding.orElseThrow().player();
        if (!(player.getVehicle() instanceof AbstractMinecart minecart)) {
            return ActionOutcome.NO_ACTIVE_ACTION;
        }
        return stopMinecartInput(minecart.getUUID());
    }

    private Optional<ControlledMinecart> controlledMinecart(
            final UUID expectedMinecartId
    ) {
        if (!server.isSameThread()) {
            return Optional.empty();
        }
        final Optional<Binding> maybeBinding = resolve();
        if (maybeBinding.isEmpty()) {
            return Optional.empty();
        }
        final Binding binding = maybeBinding.orElseThrow();
        final ServerPlayer player = binding.player();
        if (!(player.getVehicle() instanceof AbstractMinecart minecart)
                || minecart.isRemoved()
                || !minecart.isAlive()
                || minecart.getFirstPassenger() != player
                || !minecart.getUUID().equals(expectedMinecartId)) {
            return Optional.empty();
        }
        return Optional.of(new ControlledMinecart(
                player,
                binding.connection(),
                minecart
        ));
    }

    private Optional<Binding> resolve() {
        final AiPlayerManager.Status status =
                AiPlayerManager.status(server);
        final ServerPlayer player =
                AiPlayerManager.onlinePlayer(server).orElse(null);
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
    }

    private record Binding(
            ServerPlayer player,
            ServerGamePacketListenerImpl connection,
            FairPlayerActuator actuator,
            long sessionGeneration
    ) {
    }

    private record ControlledMinecart(
            ServerPlayer player,
            ServerGamePacketListenerImpl connection,
            AbstractMinecart minecart
    ) {
    }
}
