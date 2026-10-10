package dev.mcai.companion.skills.building;

import dev.mcai.companion.model.SkillArgument;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BuildingSkillParameters {
    private static final Set<String> ARGUMENTS = Set.of(
            "dimension",
            "sampleSequence",
            "scale"
    );

    private BuildingSkillParameters() {
    }

    static SkillParameterResult<BuildShelterStepParameters> parse(
            List<SkillArgument> arguments
    ) {
        if (arguments == null || arguments.size() != ARGUMENTS.size()) {
            return invalid();
        }
        Map<String, String> values = new HashMap<>();
        for (SkillArgument argument : arguments) {
            if (argument == null
                    || !ARGUMENTS.contains(argument.name())
                    || values.putIfAbsent(
                            argument.name(),
                            argument.value()
                    ) != null) {
                return invalid();
            }
        }
        if (!values.keySet().equals(ARGUMENTS)) {
            return invalid();
        }
        try {
            String sequence = values.get("sampleSequence");
            if (sequence == null
                    || sequence.isEmpty()
                    || sequence.startsWith("+")
                    || !sequence.equals(sequence.strip())) {
                return invalid();
            }
            long parsedSequence = Long.parseLong(sequence);
            if (parsedSequence < 0
                    || !Long.toString(parsedSequence).equals(sequence)) {
                return invalid();
            }
            return SkillParameterResult.valid(
                    new BuildShelterStepParameters(
                            DimensionRef.parse(values.get("dimension")),
                            parsedSequence,
                            ShelterScale.parse(values.get("scale"))
                    )
            );
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    private static SkillParameterResult<BuildShelterStepParameters> invalid() {
        return SkillParameterResult.invalid(
                "build_shelter_step.invalid_arguments"
        );
    }
}
