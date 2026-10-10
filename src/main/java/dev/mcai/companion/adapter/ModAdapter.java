package dev.mcai.companion.adapter;

import java.util.List;
import java.util.Set;

/**
 * Version-gated extension point. Loading a mod never automatically grants an
 * adapter authority: compatibility contracts must pass first.
 */
public interface ModAdapter {
    String adapterId();

    Set<String> targetModIds();

    AdapterCompatibility detect(AdapterEnvironment environment);

    List<BlockAffordance> describeAffordances();

    List<String> exposeRecipeTypes();

    List<MenuOperation> menuContract();

    Set<String> contributedSkillNames();
}
