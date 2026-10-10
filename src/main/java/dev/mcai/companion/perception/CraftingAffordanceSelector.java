package dev.mcai.companion.perception;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure bounded selection policy shared by the live sampler and JVM tests.
 */
final class CraftingAffordanceSelector {
    private CraftingAffordanceSelector() {
    }

    static CraftingAffordanceSnapshot select(
            final int currentGridWidth,
            final int currentGridHeight,
            final List<Candidate> candidates
    ) {
        Objects.requireNonNull(candidates, "candidates");
        final Map<String, CraftingAffordance> eligible =
                new LinkedHashMap<>();
        candidates.stream()
                .filter(Candidate::unlocked)
                .filter(Candidate::materialsAvailable)
                .map(Candidate::affordance)
                .filter(recipe ->
                        recipe.gridWidth() <= currentGridWidth
                        && recipe.gridHeight() <= currentGridHeight)
                .sorted(Comparator.comparing(
                        CraftingAffordance::recipeId
                ))
                .forEach(recipe -> eligible.putIfAbsent(
                        recipe.recipeId(),
                        recipe
                ));
        final List<CraftingAffordance> bounded = eligible.values()
                .stream()
                .limit(CraftingAffordanceSnapshot.MAX_RECIPES)
                .toList();
        return new CraftingAffordanceSnapshot(
                currentGridWidth,
                currentGridHeight,
                bounded,
                eligible.size() > bounded.size()
        );
    }

    record Candidate(
            CraftingAffordance affordance,
            boolean unlocked,
            boolean materialsAvailable
    ) {
        Candidate {
            Objects.requireNonNull(affordance, "affordance");
        }
    }
}
