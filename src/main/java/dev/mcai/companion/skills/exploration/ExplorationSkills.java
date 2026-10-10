package dev.mcai.companion.skills.exploration;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class ExplorationSkills {
    public static final String EXPLORE_FOR_OBSERVED_TARGET =
            ExploreForObservedTargetSkill.NAME;

    private ExplorationSkills() {
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final LongSupplier sessionGeneration
    ) {
        return Objects.requireNonNull(registry, "registry")
                .register(
                        EXPLORE_FOR_OBSERVED_TARGET,
                        new ExploreForObservedTargetSkill(
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
                                        sessionGeneration,
                                        "sessionGeneration"
                                )
                        )
                );
    }

    public static String plannerGuide() {
        return """
            explore_for_observed_target: dimension,targetKind block/entity,
            targetId,maximumDistance,stepDistance. Fair spiral; success
            requires exact target first-person visibility.
            """;
    }
}
