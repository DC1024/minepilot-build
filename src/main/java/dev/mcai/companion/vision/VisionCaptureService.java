package dev.mcai.companion.vision;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import dev.mcai.companion.model.ModelImageInput;
import dev.mcai.companion.model.ObservationKind;
import dev.mcai.companion.model.RequestedObservation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server-owned request/response coordinator for authenticated AI-view PNGs. */
public final class VisionCaptureService implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_DIMENSION = 2_048;

    private final MinecraftServer server;
    private final UUID companionId;
    private long nextRequestId;
    private final Set<UUID> registeredRenderers = new HashSet<>();
    private Pending pending;
    private VisionCaptureChunkAssembler assembler;
    private VisionCaptureSnapshot latest;
    private long lastModelDeliveryRequestId;
    private String lastFailureCode = "not_requested";
    private boolean closed;

    public VisionCaptureService(
            final MinecraftServer server,
            final UUID companionId
    ) {
        this.server = Objects.requireNonNull(server, "server");
        this.companionId = Objects.requireNonNull(
                companionId,
                "companionId"
        );
        VisionCaptureServerBridge.register(server, this);
    }

    public RequestState request() {
        return request(ObservationKind.SCREENSHOT_LOW);
    }

    public RequestState request(final RequestedObservation observation) {
        Objects.requireNonNull(observation, "observation");
        requireServerThread();
        if (observation.kind() != ObservationKind.SCREENSHOT_LOW) {
            lastFailureCode = observation.kind()
                    == ObservationKind.SCREENSHOT_HIGH_CROP
                            ? "high_crop_not_implemented"
                            : "not_a_screenshot_request";
            return new RequestState(
                    false,
                    false,
                    lastFailureCode,
                    0L
            );
        }
        return request(observation.kind());
    }

    private RequestState request(final ObservationKind observationKind) {
        requireServerThread();
        expirePending();
        if (closed) {
            return new RequestState(false, false, "service_closed", 0L);
        }
        if (pending != null) {
            return new RequestState(
                    true,
                    true,
                    "capture_pending",
                    pending.requestId()
            );
        }
        final ServerPlayer body = server.getPlayerList().getPlayer(
                companionId
        );
        if (body == null || !body.isAlive() || body.isRemoved()) {
            lastFailureCode = "companion_body_unavailable";
            return new RequestState(false, false, lastFailureCode, 0L);
        }
        final Optional<ServerPlayer> renderer = server.getPlayerList()
                .getPlayers()
                .stream()
                .filter(player -> !player.getUUID().equals(companionId))
                .filter(player -> player.level() == body.level())
                .filter(player -> registeredRenderers.contains(
                        player.getUUID()
                ))
                .filter(player -> VisionCaptureNetwork.canSendTo(player))
                .min(Comparator.comparingDouble(player ->
                        player.distanceToSqr(body)
                ));
        if (renderer.isEmpty()) {
            lastFailureCode = "authenticated_renderer_unavailable";
            return new RequestState(false, false, lastFailureCode, 0L);
        }
        final long requestId = ++nextRequestId;
        final ServerPlayer selected = renderer.orElseThrow();
        pending = new Pending(
                requestId,
                selected.getUUID(),
                Instant.now(),
                observationKind
        );
        VisionCaptureNetwork.request(
                selected,
                new ClientboundVisionCaptureRequest(
                        requestId,
                        companionId
                )
        );
        return new RequestState(true, true, "capture_pending", requestId);
    }

    public Optional<VisionCaptureSnapshot> latest() {
        requireServerThread();
        expirePending();
        return Optional.ofNullable(latest);
    }

    public Optional<ModelImageInput> takeLatestModelImage() {
        requireServerThread();
        expirePending();
        if (latest == null
                || latest.requestId() == lastModelDeliveryRequestId
                || Duration.between(
                        latest.capturedAt(),
                        Instant.now()
                ).compareTo(Duration.ofSeconds(5)) > 0) {
            return Optional.empty();
        }
        lastModelDeliveryRequestId = latest.requestId();
        return Optional.of(new ModelImageInput(
                latest.png(),
                latest.observationKind()
                        == ObservationKind.SCREENSHOT_HIGH_CROP
                                ? ModelImageInput.Detail.HIGH
                                : ModelImageInput.Detail.LOW
        ));
    }

    public String lastFailureCode() {
        requireServerThread();
        expirePending();
        return lastFailureCode;
    }

    void acceptChunk(
            final ServerPlayer sender,
            final ServerboundVisionCaptureChunk chunk
    ) {
        requireServerThread();
        expirePending();
        if (closed || pending == null
                || pending.requestId() != chunk.requestId()
                || !pending.rendererId().equals(sender.getUUID())
                || !companionId.equals(chunk.companionId())) {
            return;
        }
        try {
            if (assembler == null) {
                assembler = new VisionCaptureChunkAssembler(chunk);
            }
            final Optional<ServerboundVisionCaptureResult> completed =
                    assembler.accept(chunk);
            if (completed.isEmpty()) {
                return;
            }
            final ServerboundVisionCaptureResult result =
                    completed.orElseThrow();
            clearAssembler();
            try {
                acceptCompleted(sender, result);
            } finally {
                result.destroy();
            }
        } catch (IllegalArgumentException exception) {
            clearAssembler();
            pending = null;
            lastFailureCode = "invalid_capture_transfer";
        }
    }

    private void acceptCompleted(
            final ServerPlayer sender,
            final ServerboundVisionCaptureResult result
    ) {
        final Pending completed = pending;
        pending = null;
        if (!"ok".equals(result.code())) {
            lastFailureCode = safeCode(result.code());
            return;
        }
        final byte[] png = result.png();
        final Optional<PngHeader> header = PngHeader.parse(png);
        if (header.isEmpty()) {
            lastFailureCode = "invalid_png";
            return;
        }
        final PngHeader dimensions = header.orElseThrow();
        if (dimensions.width() > MAX_DIMENSION
                || dimensions.height() > MAX_DIMENSION) {
            lastFailureCode = "png_dimensions_exceeded";
            return;
        }
        if (latest != null) {
            latest.destroy();
        }
        latest = new VisionCaptureSnapshot(
                result.requestId(),
                companionId,
                sender.getUUID(),
                Instant.now(),
                dimensions.width(),
                dimensions.height(),
                completed.observationKind(),
                png
        );
        lastModelDeliveryRequestId = 0L;
        lastFailureCode = "ok";
    }

    void registerRenderer(
            final ServerPlayer sender,
            final boolean available
    ) {
        requireServerThread();
        if (closed
                || sender.level().getServer() != server
                || sender.getUUID().equals(companionId)) {
            return;
        }
        if (available) {
            registeredRenderers.add(sender.getUUID());
        } else {
            registeredRenderers.remove(sender.getUUID());
        }
    }

    private void expirePending() {
        if (pending != null
                && Duration.between(
                        pending.startedAt(),
                        Instant.now()
                ).compareTo(REQUEST_TIMEOUT) > 0) {
            pending = null;
            clearAssembler();
            lastFailureCode = "capture_timeout";
        }
    }

    private void clearAssembler() {
        if (assembler != null) {
            assembler.destroy();
            assembler = null;
        }
    }

    private static String safeCode(final String value) {
        if (value == null
                || !value.matches("[a-z0-9_]{1,64}")) {
            return "capture_failed";
        }
        return value;
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException(
                    "Vision capture requires the server thread"
            );
        }
    }

    @Override
    public void close() {
        requireServerThread();
        if (closed) {
            return;
        }
        closed = true;
        pending = null;
        clearAssembler();
        registeredRenderers.clear();
        if (latest != null) {
            latest.destroy();
            latest = null;
        }
        VisionCaptureServerBridge.unregister(server, this);
    }

    public record RequestState(
            boolean accepted,
            boolean pending,
            String code,
            long requestId
    ) {
    }

    private record Pending(
            long requestId,
            UUID rendererId,
            Instant startedAt,
            ObservationKind observationKind
    ) {
    }

    private record PngHeader(int width, int height) {
        private static Optional<PngHeader> parse(final byte[] png) {
            if (png == null
                    || png.length < 24
                    || png.length > VisionCaptureNetwork.MAX_PNG_BYTES
                    || (png[0] & 0xff) != 0x89
                    || png[1] != 0x50
                    || png[2] != 0x4e
                    || png[3] != 0x47
                    || png[4] != 0x0d
                    || png[5] != 0x0a
                    || png[6] != 0x1a
                    || png[7] != 0x0a) {
                return Optional.empty();
            }
            final int width = integer(png, 16);
            final int height = integer(png, 20);
            return width > 0 && height > 0
                    ? Optional.of(new PngHeader(width, height))
                    : Optional.empty();
        }

        private static int integer(final byte[] bytes, final int offset) {
            return (bytes[offset] & 0xff) << 24
                    | (bytes[offset + 1] & 0xff) << 16
                    | (bytes[offset + 2] & 0xff) << 8
                    | bytes[offset + 3] & 0xff;
        }
    }
}
