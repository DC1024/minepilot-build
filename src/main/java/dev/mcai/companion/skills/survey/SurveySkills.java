package dev.mcai.companion.skills.survey;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import java.util.Objects;
import java.util.UUID;

public final class SurveySkills {
    public static final String SURVEY_SURROUNDINGS =
            SurveySurroundingsSkill.NAME;

    private SurveySkills() {
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final SurveyResultBuffer results
    ) {
        return Objects.requireNonNull(registry, "registry")
                .register(
                        SURVEY_SURROUNDINGS,
                        new SurveySurroundingsSkill(
                                Objects.requireNonNull(
                                        playerId,
                                        "playerId"
                                ),
                                Objects.requireNonNull(
                                        actuator,
                                        "actuator"
                                ),
                                Objects.requireNonNull(
                                        frames,
                                        "frames"
                                ),
                                Objects.requireNonNull(
                                        results,
                                        "results"
                                )
                        )
                );
    }

    public static String plannerGuide() {
        return """
            survey_surroundings requires dimension, horizontalSteps (4..16),
            includeVertical; optional observationWaitTicks (4..40), default
            40. It stays still for fresh first-person samples. Data contains
            observed blocks/entity types/danger only—never UUIDs or unseen
            blocks. Use 8+vertical for terrain, 4 horizon for follow
            reacquire, then re-observe before interaction.
            """;
    }
}
