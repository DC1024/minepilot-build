package dev.mcai.companion.model;

import java.util.Map;
import java.util.Objects;

/**
 * Local facts that a response must still match before it can affect the game.
 */
public record DecisionContext(
        String requestId,
        long observedWorldRevision,
        long goalRevision,
        boolean activeSkill,
        Map<String, SkillArgumentValidator> availableSkills,
        DecisionLane lane
) {
    public DecisionContext {
        Objects.requireNonNull(requestId, "requestId");
        availableSkills = Map.copyOf(Objects.requireNonNull(availableSkills, "availableSkills"));
        Objects.requireNonNull(lane, "lane");
    }

    public DecisionContext(
            final String requestId,
            final long observedWorldRevision,
            final long goalRevision,
            final boolean activeSkill,
            final Map<String, SkillArgumentValidator> availableSkills
    ) {
        this(
                requestId,
                observedWorldRevision,
                goalRevision,
                activeSkill,
                availableSkills,
                DecisionLane.GAMEPLAY
        );
    }
}
