package dev.mcai.companion.skill;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.model.SkillArgumentValidator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The explicit allow-list of local skills available to a model decision.
 */
public final class SkillRegistry {
    public static final int MAX_ARGUMENTS = 32;
    public static final int MAX_ARGUMENT_NAME_CHARACTERS = 64;
    public static final int MAX_ARGUMENT_VALUE_CHARACTERS = 512;

    private static final Pattern SKILL_NAME = Pattern.compile("[a-z0-9_.:-]{1,64}");
    private static final Pattern ARGUMENT_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final SkillFailure INVALID_ARGUMENTS =
            SkillFailure.of("invalid_skill_arguments");

    private final ConcurrentHashMap<String, Registration<?>> registrations =
            new ConcurrentHashMap<>();

    public <P> SkillRegistry register(String name, Skill<P> skill) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(skill, "skill");
        if (!SKILL_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Skill name is not canonical");
        }

        SkillParameterParser<P> parser =
                Objects.requireNonNull(skill.parameters(), "skill.parameters()");
        Registration<P> registration = new Registration<>(name, skill, parser);
        if (registrations.putIfAbsent(name, registration) != null) {
            throw new IllegalStateException("A skill with this name is already registered");
        }
        return this;
    }

    public boolean contains(String name) {
        return name != null && registrations.containsKey(name);
    }

    public Set<String> names() {
        return Set.copyOf(registrations.keySet());
    }

    /**
     * Adapts the same typed parsers for the model decision validator.
     *
     * <p>The supervisor parses again immediately before start. The second
     * validation is intentional defense in depth against stale or bypassed
     * model validation.</p>
     */
    public Map<String, SkillArgumentValidator> modelArgumentValidators() {
        Map<String, SkillArgumentValidator> validators = new HashMap<>();
        registrations.forEach((name, registration) ->
                validators.put(name, arguments ->
                        validateForModel(registration, arguments).map(SkillFailure::code)));
        return Map.copyOf(validators);
    }

    Optional<Registration<?>> find(String name) {
        return Optional.ofNullable(registrations.get(name));
    }

    static Optional<SkillFailure> validateWireArguments(List<SkillArgument> arguments) {
        if (arguments == null || arguments.size() > MAX_ARGUMENTS) {
            return Optional.of(INVALID_ARGUMENTS);
        }
        Set<String> names = new HashSet<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                    || !ARGUMENT_NAME.matcher(argument.name()).matches()
                    || !names.add(argument.name())
                    || argument.value().length() > MAX_ARGUMENT_VALUE_CHARACTERS
                    || containsDisallowedControl(argument.value())) {
                return Optional.of(INVALID_ARGUMENTS);
            }
        }
        return Optional.empty();
    }

    private static Optional<SkillFailure> validateForModel(
            Registration<?> registration,
            List<SkillArgument> arguments
    ) {
        Optional<SkillFailure> wireFailure = validateWireArguments(arguments);
        if (wireFailure.isPresent()) {
            return wireFailure;
        }
        try {
            SkillParameterResult<?> parsed = registration.parser().parse(List.copyOf(arguments));
            if (parsed instanceof SkillParameterResult.Valid<?>) {
                return Optional.empty();
            }
            if (parsed instanceof SkillParameterResult.Invalid<?> invalid) {
                return Optional.of(invalid.rejection());
            }
            return Optional.of(INVALID_ARGUMENTS);
        } catch (RuntimeException exception) {
            return Optional.of(INVALID_ARGUMENTS);
        }
    }

    private static boolean containsDisallowedControl(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (codePoint == 0
                    || (Character.isISOControl(codePoint)
                    && codePoint != '\n'
                    && codePoint != '\r'
                    && codePoint != '\t')) {
                return true;
            }
            offset += Character.charCount(codePoint);
        }
        return false;
    }

    record Registration<P>(
            String name,
            Skill<P> skill,
            SkillParameterParser<P> parser
    ) {
        Registration {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(skill, "skill");
            Objects.requireNonNull(parser, "parser");
        }
    }
}
