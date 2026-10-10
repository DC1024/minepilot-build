package dev.mcai.companion.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

final class AdapterRegistryTest {
    @Test
    void activatesOnlyAdaptersWhoseExactContractPasses() {
        final AdapterRegistry registry = new AdapterRegistry();
        registry.register(adapter("create_2612", "create", "1.0.0"));
        registry.register(adapter("farmersdelight_2612", "farmersdelight", "2.0.0"));

        final var active = registry.activate(new AdapterEnvironment(
            "26.2",
            "65.0.8",
            Map.of("create", "1.0.0", "farmersdelight", "wrong")
        ));

        assertEquals(1, active.size());
        assertEquals("create_2612", active.getFirst().adapter().adapterId());
    }

    @Test
    void rejectsDuplicateOrUnscopedAdapters() {
        final AdapterRegistry registry = new AdapterRegistry();
        final ModAdapter adapter = adapter("same", "create", "1");
        registry.register(adapter);

        assertThrows(IllegalArgumentException.class, () -> registry.register(adapter));
        assertThrows(IllegalArgumentException.class, () -> registry.register(new ModAdapter() {
            @Override public String adapterId() { return "empty"; }
            @Override public Set<String> targetModIds() { return Set.of(); }
            @Override public AdapterCompatibility detect(AdapterEnvironment environment) {
                return AdapterCompatibility.incompatible("missing");
            }
            @Override public List<BlockAffordance> describeAffordances() { return List.of(); }
            @Override public List<String> exposeRecipeTypes() { return List.of(); }
            @Override public List<MenuOperation> menuContract() { return List.of(); }
            @Override public Set<String> contributedSkillNames() { return Set.of(); }
        }));
    }

    private static ModAdapter adapter(
        final String id,
        final String target,
        final String expectedVersion
    ) {
        return new ModAdapter() {
            @Override public String adapterId() { return id; }
            @Override public Set<String> targetModIds() { return Set.of(target); }
            @Override
            public AdapterCompatibility detect(final AdapterEnvironment environment) {
                return environment.modVersion(target).filter(expectedVersion::equals).isPresent()
                    ? new AdapterCompatibility(true, "compatible", List.of("menu", "recipe"))
                    : AdapterCompatibility.incompatible("version_mismatch");
            }
            @Override public List<BlockAffordance> describeAffordances() { return List.of(); }
            @Override public List<String> exposeRecipeTypes() { return List.of(); }
            @Override public List<MenuOperation> menuContract() { return List.of(); }
            @Override public Set<String> contributedSkillNames() { return Set.of(); }
        };
    }
}
