package dev.mcai.companion.skills.portal;

import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Registration slice for verified, ordinary vanilla portal entry.
 */
public final class PortalSkills {
    public static final String ENTER_OBSERVED_PORTAL =
            "enter_observed_portal";
    public static final String FIND_AND_ENTER_OBSERVED_PORTAL =
            FindAndEnterObservedPortalSkill.NAME;
    public static final String RETURN_VIA_VERIFIED_PORTAL =
            ReturnViaVerifiedPortalSkill.NAME;
    private static final int MAX_HORIZONTAL = 29_999_984;
    private static final int MAX_VERTICAL = 2_048;
    private static final Set<String> REQUIRED_ARGUMENTS = Set.of(
            "dimension",
            "sampleSequence",
            "x",
            "y",
            "z",
            "face"
    );

    private PortalSkills() {
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            CoreSkillActuator actuator,
            PortalSkillFrameSource frames
    ) {
        return registerAll(
                registry,
                playerId,
                actuator,
                frames,
                PortalSkillPolicy.defaults(),
                PortalTraversalObserver.NOOP
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            CoreSkillActuator actuator,
            PortalSkillFrameSource frames,
            PortalTraversalObserver traversalObserver
    ) {
        return registerAll(
                registry,
                playerId,
                actuator,
                frames,
                PortalSkillPolicy.defaults(),
                traversalObserver
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            CoreSkillActuator actuator,
            PortalSkillFrameSource frames,
            PortalSkillPolicy policy,
            PortalTraversalObserver traversalObserver
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(actuator, "actuator");
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(traversalObserver, "traversalObserver");
        return registry
                .register(
                        ENTER_OBSERVED_PORTAL,
                        new EnterObservedPortalSkill(
                                playerId,
                                actuator,
                                frames,
                                policy,
                                traversalObserver
                        )
                )
                .register(
                        FIND_AND_ENTER_OBSERVED_PORTAL,
                        new FindAndEnterObservedPortalSkill(
                                playerId,
                                actuator,
                                frames,
                                policy,
                                traversalObserver
                        )
                );
    }

    /**
     * Registers portal entry plus the durable arrival-endpoint return
     * compound used by autonomous cross-dimension routes.
     */
    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource coreFrames,
            final PortalSkillFrameSource portalFrames,
            final LongSupplier sessionGeneration,
            final VerifiedPortalArrivalLookup arrivals,
            final PortalSkillPolicy policy,
            final PortalTraversalObserver traversalObserver
    ) {
        registerAll(
                registry,
                playerId,
                actuator,
                portalFrames,
                policy,
                traversalObserver
        );
        return registry.register(
                RETURN_VIA_VERIFIED_PORTAL,
                new ReturnViaVerifiedPortalSkill(
                        playerId,
                        actuator,
                        coreFrames,
                        portalFrames,
                        sessionGeneration,
                        arrivals,
                        policy,
                        traversalObserver
                )
        );
    }

    static SkillParameterResult<EnterObservedPortalParameters>
    parseEnterObservedPortal(List<SkillArgument> arguments) {
        if (arguments == null
                || arguments.size() < REQUIRED_ARGUMENTS.size()
                || arguments.size() > REQUIRED_ARGUMENTS.size() + 1) {
            return invalid();
        }
        Set<String> allowed = new HashSet<>(REQUIRED_ARGUMENTS);
        allowed.add("expectedDestination");
        Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                    || !allowed.contains(argument.name())
                    || values.putIfAbsent(
                            argument.name(),
                            argument.value()
                    ) != null) {
                return invalid();
            }
        }
        if (!values.keySet().containsAll(REQUIRED_ARGUMENTS)
                || values.size() != arguments.size()) {
            return invalid();
        }
        try {
            Optional<DimensionRef> expected = Optional.ofNullable(
                    values.get("expectedDestination")
            ).map(DimensionRef::parse);
            return SkillParameterResult.valid(
                    new EnterObservedPortalParameters(
                            DimensionRef.parse(values.get("dimension")),
                            new ObservedPortalTarget(
                                    nonNegativeLong(
                                            values.get("sampleSequence")
                                    ),
                                    integer(
                                            values.get("x"),
                                            -MAX_HORIZONTAL,
                                            MAX_HORIZONTAL
                                    ),
                                    integer(
                                            values.get("y"),
                                            -MAX_VERTICAL,
                                            MAX_VERTICAL
                                    ),
                                    integer(
                                            values.get("z"),
                                            -MAX_HORIZONTAL,
                                            MAX_HORIZONTAL
                                    ),
                                    face(values.get("face"))
                            ),
                            expected
                    )
            );
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    public static String plannerGuide() {
        return """
            enter_observed_portal binds one complete current visibleBlockFaces
            portal entry. find_and_enter_observed_portal takes no arguments;
            it performs a bounded first-person scan and binds the nearest
            visible portal locally. return_via_verified_portal takes no
            arguments; it walks to a durable body-observed arrival endpoint,
            re-observes the portal, and crosses it normally. These skills
            succeed only after vanilla traversal; none scans the world or
            teleports, and an arrival endpoint is never treated as a verified
            reverse edge before the new crossing.
            """;
    }

    private static BlockFace face(String value) {
        if (value == null
                || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid face");
        }
        return BlockFace.valueOf(value.toUpperCase(Locale.ROOT));
    }

    private static int integer(
            String value,
            int minimum,
            int maximum
    ) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid integer");
        }
        int parsed = Integer.parseInt(value);
        if (!Integer.toString(parsed).equals(value)
                || parsed < minimum
                || parsed > maximum) {
            throw new IllegalArgumentException("Integer outside bounds");
        }
        return parsed;
    }

    private static long nonNegativeLong(String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())
                || value.startsWith("+")) {
            throw new IllegalArgumentException("Invalid long");
        }
        long parsed = Long.parseLong(value);
        if (!Long.toString(parsed).equals(value) || parsed < 0) {
            throw new IllegalArgumentException(
                    "Long must be canonical and non-negative"
            );
        }
        return parsed;
    }

    private static SkillParameterResult<EnterObservedPortalParameters>
    invalid() {
        return SkillParameterResult.invalid(
                "enter_observed_portal.invalid_arguments"
        );
    }
}
