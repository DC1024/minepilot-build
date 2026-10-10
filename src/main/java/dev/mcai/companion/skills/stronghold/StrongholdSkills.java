package dev.mcai.companion.skills.stronghold;

import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skills.core.CoreSkillActuator;
import dev.mcai.companion.skills.core.CoreSkillFrameSource;
import dev.mcai.companion.skills.gathering.ResourceInventorySource;
import dev.mcai.companion.skills.interaction.InteractionSkillActuator;
import dev.mcai.companion.skills.interaction.InteractionSkillFrameSource;
import dev.mcai.companion.skills.inventory.InventorySkillActuator;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

public final class StrongholdSkills {
    public static final String TRACE_STRONGHOLD_EYE =
            TraceStrongholdEyeSkill.NAME;
    public static final String TRIANGULATE_STRONGHOLD_SEARCH_AREA =
            TriangulateStrongholdSearchAreaSkill.NAME;
    public static final String REACH_OBSERVED_STRONGHOLD =
            ReachObservedStrongholdSkill.NAME;
    public static final String
            SEARCH_OBSERVED_STRONGHOLD_PORTAL_ROOM =
            SearchObservedStrongholdPortalRoomSkill.NAME;

    private StrongholdSkills() {
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final EyeTraceResultBuffer results
    ) {
        return Objects.requireNonNull(registry, "registry")
                .register(
                        TRACE_STRONGHOLD_EYE,
                        new TraceStrongholdEyeSkill(
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

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final InventorySkillActuator inventory,
            final EyeTraceResultBuffer results,
            final LongSupplier sessionGeneration
    ) {
        return registerAll(
                registry,
                playerId,
                actuator,
                frames,
                inventory,
                results,
                sessionGeneration,
                ignored -> {
                }
        );
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final InventorySkillActuator inventory,
            final EyeTraceResultBuffer results,
            final LongSupplier sessionGeneration,
            final LongConsumer triangulationCompletion
    ) {
        registerAll(
                registry,
                playerId,
                actuator,
                frames,
                results
        );
        return registry.register(
                TRIANGULATE_STRONGHOLD_SEARCH_AREA,
                new TriangulateStrongholdSearchAreaSkill(
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
                                inventory,
                                "inventory"
                        ),
                        Objects.requireNonNull(
                                results,
                                "results"
                        ),
                        Objects.requireNonNull(
                                sessionGeneration,
                                "sessionGeneration"
                        ),
                        Objects.requireNonNull(
                                triangulationCompletion,
                                "triangulationCompletion"
                        )
                )
        );
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final InventorySkillActuator inventory,
            final EyeTraceResultBuffer results,
            final LongSupplier sessionGeneration,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final ResourceInventorySource resourceInventory
    ) {
        return registerAll(
                registry,
                playerId,
                actuator,
                frames,
                inventory,
                results,
                sessionGeneration,
                ignored -> {
                },
                interactions,
                interactionFrames,
                resourceInventory
        );
    }

    public static SkillRegistry registerAll(
            final SkillRegistry registry,
            final UUID playerId,
            final CoreSkillActuator actuator,
            final CoreSkillFrameSource frames,
            final InventorySkillActuator inventory,
            final EyeTraceResultBuffer results,
            final LongSupplier sessionGeneration,
            final LongConsumer triangulationCompletion,
            final InteractionSkillActuator interactions,
            final InteractionSkillFrameSource interactionFrames,
            final ResourceInventorySource resourceInventory
    ) {
        registerAll(
                registry,
                playerId,
                actuator,
                frames,
                inventory,
                results,
                sessionGeneration,
                triangulationCompletion
        );
        registry.register(
                REACH_OBSERVED_STRONGHOLD,
                new ReachObservedStrongholdSkill(
                        playerId,
                        actuator,
                        frames,
                        Objects.requireNonNull(
                                interactions,
                                "interactions"
                        ),
                        Objects.requireNonNull(
                                interactionFrames,
                                "interactionFrames"
                        ),
                        Objects.requireNonNull(
                                resourceInventory,
                                "resourceInventory"
                        ),
                        results,
                        sessionGeneration
                )
        );
        return registry.register(
                SEARCH_OBSERVED_STRONGHOLD_PORTAL_ROOM,
                new SearchObservedStrongholdPortalRoomSkill(
                        playerId,
                        actuator,
                        frames,
                        sessionGeneration
                )
        );
    }

    public static String plannerGuide() {
        return """
            trace_stronghold_eye: dimension,sampleSequence,hand; visible Eye.
            triangulate_stronghold_search_area: none; two throws and walked
            baseline yield a measured search area only.
            reach_observed_stronghold: none; fair lit travel and bounded
            wall entry while preserving nearby evidence.
            search_stronghold_portal_room: none; fair DFS until visible ring
            evidence proves one center.
            """;
    }
}
