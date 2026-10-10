package dev.mcai.companion.waypoint;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record Waypoint(
    UUID id,
    UUID worldId,
    DimensionRef dimension,
    WaypointGeometry geometry,
    String name,
    Set<String> aliases,
    String category,
    UUID creatorId,
    String source,
    WaypointProvenance provenance,
    double confidence,
    long revision,
    WaypointStatus status,
    Instant createdAt,
    Instant updatedAt,
    Optional<Instant> lastVerifiedAt,
    Optional<Duration> ttl
) {
    public static final int MAXIMUM_ALIASES = 64;
    public static final Duration MAXIMUM_TTL = Duration.ofDays(36_500);

    public Waypoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(creatorId, "creatorId");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(lastVerifiedAt, "lastVerifiedAt");
        Objects.requireNonNull(ttl, "ttl");

        name = WaypointNames.validateDisplayField(
            name,
            "name",
            WaypointNames.MAXIMUM_NAME_LENGTH
        );
        aliases = immutableAliases(aliases, name);
        category = WaypointNames.validateDisplayField(
            category,
            "category",
            WaypointNames.MAXIMUM_CATEGORY_LENGTH
        );
        source = WaypointNames.validateDisplayField(
            source,
            "source",
            WaypointNames.MAXIMUM_SOURCE_LENGTH
        );

        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Confidence must be finite and in [0, 1]");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must be non-negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
        lastVerifiedAt.ifPresent(verifiedAt -> {
            if (verifiedAt.isBefore(createdAt) || verifiedAt.isAfter(updatedAt)) {
                throw new IllegalArgumentException("lastVerifiedAt must be within the waypoint lifetime");
            }
        });
        ttl.ifPresent(duration -> {
            if (duration.isZero() || duration.isNegative() || duration.compareTo(MAXIMUM_TTL) > 0) {
                throw new IllegalArgumentException("TTL must be positive and bounded");
            }
        });
    }

    public boolean isDangerous() {
        return status.isDangerous();
    }

    public boolean isExpired(Instant at) {
        Objects.requireNonNull(at, "at");
        return expiresAt().map(expiry -> !at.isBefore(expiry)).orElse(false);
    }

    public Optional<Instant> expiresAt() {
        if (ttl.isEmpty()) {
            return Optional.empty();
        }
        final Instant baseline = lastVerifiedAt.orElse(updatedAt);
        try {
            return Optional.of(baseline.plus(ttl.orElseThrow()));
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalStateException("Waypoint TTL overflows the timestamp range", exception);
        }
    }

    public boolean isSearchableAt(Instant at) {
        return status.isSearchable() && !isExpired(at);
    }

    private static Set<String> immutableAliases(Set<String> aliases, String name) {
        Objects.requireNonNull(aliases, "aliases");
        if (aliases.size() > MAXIMUM_ALIASES) {
            throw new IllegalArgumentException("Too many waypoint aliases");
        }
        final String normalizedName = WaypointNames.normalize(name);
        final Map<String, String> byNormalizedAlias = new LinkedHashMap<>();
        for (String alias : aliases) {
            final String displayAlias = WaypointNames.validateDisplayField(
                alias,
                "alias",
                WaypointNames.MAXIMUM_NAME_LENGTH
            );
            final String normalizedAlias = WaypointNames.normalize(displayAlias);
            if (!normalizedAlias.equals(normalizedName)) {
                byNormalizedAlias.putIfAbsent(normalizedAlias, displayAlias);
            }
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(byNormalizedAlias.values()));
    }
}
