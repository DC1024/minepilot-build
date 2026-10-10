package dev.mcai.companion.memory.transport;

import dev.mcai.companion.skills.portal.PortalTraversalObserver;
import dev.mcai.companion.skills.portal.PortalTraversalResult;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-blocking adapter from the portal skill's verified-result callback to
 * durable transport memory.
 */
public final class PersistentPortalTraversalObserver
        implements PortalTraversalObserver {
    private final UUID worldId;
    private final VerifiedPortalEdgeRepository repository;
    private final Clock clock;
    private final AtomicReference<CompletableFuture<VerifiedPortalEdge>>
        latestWrite = new AtomicReference<>(
            CompletableFuture.completedFuture(null)
        );
    private final AtomicReference<Throwable> latestFailure =
        new AtomicReference<>();
    private final AtomicLong successfulWrites = new AtomicLong();
    private final AtomicLong failedWrites = new AtomicLong();

    public PersistentPortalTraversalObserver(
        UUID worldId,
        VerifiedPortalEdgeRepository repository
    ) {
        this(worldId, repository, Clock.systemUTC());
    }

    public PersistentPortalTraversalObserver(
        UUID worldId,
        VerifiedPortalEdgeRepository repository,
        Clock clock
    ) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void onTraversal(PortalTraversalResult result) {
        Objects.requireNonNull(result, "result");
        CompletableFuture<VerifiedPortalEdge> write;
        try {
            write = repository.recordTraversal(
                worldId,
                result,
                clock.instant()
            );
        } catch (RuntimeException exception) {
            latestFailure.set(exception);
            failedWrites.incrementAndGet();
            return;
        }
        latestWrite.set(write);
        write.whenComplete((ignored, failure) -> {
            if (failure == null) {
                latestFailure.set(null);
                successfulWrites.incrementAndGet();
            } else {
                latestFailure.set(failure);
                failedWrites.incrementAndGet();
            }
        });
    }

    public CompletableFuture<VerifiedPortalEdge> latestWrite() {
        return latestWrite.get();
    }

    public Optional<Throwable> latestFailure() {
        return Optional.ofNullable(latestFailure.get());
    }

    public long successfulWriteCount() {
        return successfulWrites.get();
    }

    public long failedWriteCount() {
        return failedWrites.get();
    }
}
