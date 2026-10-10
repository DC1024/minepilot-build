package dev.mcai.companion.skills.core;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.navigation.LocalAStarPlanner;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Independently registered long-range travel slice.
 */
public final class TravelSkills {
    public static final String TRAVEL_TO = "travel_to";
    private static final Set<String> ARGUMENTS = Set.of(
            "dimension",
            "x",
            "y",
            "z",
            "arrivalRadius"
    );

    private TravelSkills() {
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            LongSupplier sessionGeneration
    ) {
        return registerAll(
                registry,
                playerId,
                actuator,
                frames,
                sessionGeneration,
                new LocalAStarPlanner(),
                CoreSkillPolicy.defaults(),
                TravelSkillPolicy.defaults()
        );
    }

    public static SkillRegistry registerAll(
            SkillRegistry registry,
            UUID playerId,
            CoreSkillActuator actuator,
            CoreSkillFrameSource frames,
            LongSupplier sessionGeneration,
            LocalAStarPlanner planner,
            CoreSkillPolicy corePolicy,
            TravelSkillPolicy travelPolicy
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(actuator, "actuator");
        Objects.requireNonNull(frames, "frames");
        Objects.requireNonNull(sessionGeneration, "sessionGeneration");
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(corePolicy, "corePolicy");
        Objects.requireNonNull(travelPolicy, "travelPolicy");
        return registry.register(
                TRAVEL_TO,
                new TravelToSkill(
                        playerId,
                        actuator,
                        frames,
                        sessionGeneration,
                        planner,
                        corePolicy,
                        travelPolicy
                )
        );
    }

    static SkillParameterResult<TravelToParameters> parseTravelTo(
            List<SkillArgument> arguments
    ) {
        if (arguments == null || arguments.size() != ARGUMENTS.size()) {
            return SkillParameterResult.invalid(
                    "travel_to.invalid_arguments"
            );
        }
        Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                    || !ARGUMENTS.contains(argument.name())
                    || values.putIfAbsent(
                            argument.name(),
                            argument.value()
                    ) != null) {
                return SkillParameterResult.invalid(
                        "travel_to.invalid_arguments"
                );
            }
        }
        if (!values.keySet().equals(ARGUMENTS)) {
            return SkillParameterResult.invalid(
                    "travel_to.invalid_arguments"
            );
        }
        try {
            return SkillParameterResult.valid(new TravelToParameters(
                    DimensionRef.parse(values.get("dimension")),
                    decimal(values.get("x")),
                    decimal(values.get("y")),
                    decimal(values.get("z")),
                    decimal(values.get("arrivalRadius"))
            ));
        } catch (RuntimeException exception) {
            return SkillParameterResult.invalid(
                    "travel_to.invalid_arguments"
            );
        }
    }

    public static String plannerGuide() {
        return """
            travel_to: dimension,x,y,z,arrivalRadius 0.5..3. Same-dimension
            walking uses only first-person semantic observations, turns before
            unknown terrain, never teleports/reads hidden chunks, and may stop
            at an observed safe point within three blocks. Use separate skills
            for boats, rails, portals, and cross-dimension routes.
            """;
    }

    private static double decimal(String value) {
        if (value == null
                || value.isEmpty()
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Invalid decimal");
        }
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException("Decimal must be finite");
        }
        return parsed == 0.0 ? 0.0 : parsed;
    }
}
