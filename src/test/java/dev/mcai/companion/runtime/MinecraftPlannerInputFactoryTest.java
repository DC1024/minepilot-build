package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.brain.BrainObservation;
import dev.mcai.companion.control.GoalCoordinator;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.model.SkillArgumentValidator;
import dev.mcai.companion.control.InMemoryGoalRevisionStore;
import dev.mcai.companion.skill.Skill;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillContext;
import dev.mcai.companion.skill.SkillFailure;
import dev.mcai.companion.skill.SkillParameterParser;
import dev.mcai.companion.skill.SkillParameterResult;
import dev.mcai.companion.skill.SkillRegistry;
import dev.mcai.companion.skill.SkillResult;
import dev.mcai.companion.skill.SkillTickResult;
import dev.mcai.companion.skills.mining.MiningSkills;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class MinecraftPlannerInputFactoryTest {
    @Test
    void completeProductionSkillGuideFitsTheRuntimeBound() {
        assertDoesNotThrow(() -> new MinecraftPlannerInputFactory(
                new SkillRegistry(),
                CompanionRuntime.coreSkillGuideForTests()
        ));
    }

    @Test
    void internalSafeIdlePrimitiveIsNeverExposedToTheModel() {
        final SkillArgumentValidator accepts =
                arguments -> Optional.empty();
        final Map<String, SkillArgumentValidator> visible =
                MinecraftPlannerInputFactory.modelVisibleSkills(
                        Map.of(
                                "safe_idle",
                                accepts,
                                "survey_surroundings",
                                accepts,
                                "move_to",
                                accepts
                        )
                );

        assertEquals(
                java.util.Set.of(
                        "survey_surroundings",
                        "move_to"
                ),
                visible.keySet()
        );
    }

    @Test
    void noActionCorrectionReachesTheNextTrustedPlannerInput() {
        final var input = new MinecraftPlannerInputFactory(
                new SkillRegistry().register(
                        "survey_surroundings",
                        noArgumentSkill()
                ),
                CompanionRuntime.coreSkillGuideForTests()
        ).create(
                "request-no-action-repair",
                new GoalSnapshot(
                        Optional.empty(),
                        4L,
                        GoalStatus.RUNNING,
                        GoalSource.PLAYER_CHAT,
                        "follow the player",
                        "",
                        java.time.Instant.EPOCH,
                        false
                ),
                new BrainObservation(
                        9L,
                        new SkillContext(4L, 9L, 20L, false, true, 0.0),
                        "{}",
                        "{\"lastModelDecisionFailureCode\":\"planner_no_action\"}"
                )
        );

        assertTrue(input.systemPrompt().contains(
                "\"lastModelDecisionFailureCode\":\"planner_no_action\""
        ));
        assertTrue(input.systemPrompt().contains(
                "previous valid planner response did not"
        ));
    }

    @Test
    void foundationSchemaAdmitsOnlyTheCurrentCompoundPhase() {
        final SkillArgumentValidator accepts =
                arguments -> Optional.empty();
        final Map<String, SkillArgumentValidator> all = Map.ofEntries(
                Map.entry("prepare_basic_crafting", accepts),
                Map.entry("prepare_stone_tools", accepts),
                Map.entry("prepare_distributed_log_storage", accepts),
                Map.entry("prepare_iron_toolkit", accepts),
                Map.entry("gather_visible_block_cluster", accepts),
                Map.entry("survey_surroundings", accepts),
                Map.entry("excavate_safe_tunnel", accepts),
                Map.entry(
                        "establish_foundation_workstations",
                        accepts
                ),
                Map.entry(
                        "prepare_foundation_shelter_materials",
                        accepts
                ),
                Map.entry("hunt_observed_food_animal", accepts),
                Map.entry("secure_visible_food_reserve", accepts),
                Map.entry("build_shelter_step", accepts)
        );
        final String foodRuntime = """
            {
              "verifiedCompletionRouteData": {
                "profile": "FOUNDATION",
                "nextObjectives": ["SECURE_FOOD_RESERVE"]
              }
            }
            """;
        final var food = MinecraftPlannerInputFactory
                .foundationPhaseSkills(all, foodRuntime);

        assertEquals(
                java.util.Set.of("secure_visible_food_reserve"),
                food.keySet()
        );

        final String ironRuntime = """
            {
              "verifiedCompletionRouteData": {
                "profile": "FOUNDATION",
                "nextObjectives": ["ACQUIRE_IRON_TOOLKIT"]
              }
            }
            """;
        final var iron = MinecraftPlannerInputFactory
                .foundationPhaseSkills(all, ironRuntime);
        assertTrue(iron.containsKey("prepare_iron_toolkit"));
        assertEquals(
                java.util.Set.of("prepare_iron_toolkit"),
                iron.keySet()
        );
        assertFalse(iron.containsKey("gather_visible_block_cluster"));
        assertFalse(iron.containsKey("survey_surroundings"));
        assertFalse(iron.containsKey("excavate_safe_tunnel"));
        assertFalse(iron.containsKey("hunt_observed_food_animal"));
        assertFalse(iron.containsKey("secure_visible_food_reserve"));
        assertFalse(iron.containsKey("prepare_basic_crafting"));
        assertFalse(iron.containsKey("prepare_stone_tools"));
        assertFalse(iron.containsKey(
                "prepare_distributed_log_storage"
        ));

        final String distributedStorageRuntime = """
            {
              "verifiedCompletionRouteData": {
                "profile": "FOUNDATION",
                "nextObjectives": ["DISTRIBUTE_LOG_STORAGE"]
              }
            }
            """;
        assertEquals(
                java.util.Set.of("prepare_distributed_log_storage"),
                MinecraftPlannerInputFactory.foundationPhaseSkills(
                        all,
                        distributedStorageRuntime
                ).keySet()
        );

        final String workstationRuntime = """
            {
              "verifiedCompletionRouteData": {
                "profile": "FOUNDATION",
                "nextObjectives": [
                  "ESTABLISH_FOUNDATION_WORKSTATIONS"
                ],
                "criticalOwnedCounts": {
                  "chest_plank_potential": 8
                },
                "currentMinimumTargets": {
                  "chest_plank_potential": 8
                }
              }
            }
            """;
        final var workstation = MinecraftPlannerInputFactory
                .foundationPhaseSkills(all, workstationRuntime);
        assertEquals(
                java.util.Set.of(
                        "establish_foundation_workstations"
                ),
                workstation.keySet()
        );

        final String workstationWoodDeficientRuntime = """
            {
              "verifiedCompletionRouteData": {
                "profile": "FOUNDATION",
                "nextObjectives": [
                  "ESTABLISH_FOUNDATION_WORKSTATIONS"
                ],
                "criticalOwnedCounts": {
                  "chest_plank_potential": 4
                },
                "currentMinimumTargets": {
                  "chest_plank_potential": 8
                }
              }
            }
            """;
        final var workstationWoodDeficient =
                MinecraftPlannerInputFactory.foundationPhaseSkills(
                        all,
                        workstationWoodDeficientRuntime
                );
        assertEquals(
                java.util.Set.of(
                        "establish_foundation_workstations"
                ),
                workstationWoodDeficient.keySet()
        );

        final String storageRuntime = """
            {
              "verifiedCompletionRouteData": {
                "profile": "FOUNDATION",
                "nextObjectives": ["STORE_SURPLUS_SUPPLIES"]
              }
            }
            """;
        final var storage = MinecraftPlannerInputFactory
                .foundationPhaseSkills(all, storageRuntime);
        assertEquals(workstation.keySet(), storage.keySet());

        final String shelterRuntime = """
            {
              "verifiedCompletionRouteData": {
                "profile": "FOUNDATION",
                "nextObjectives": ["BUILD_DYNAMIC_SHELTER"]
              }
            }
            """;
        final var shelter = MinecraftPlannerInputFactory
                .foundationPhaseSkills(all, shelterRuntime);
        assertTrue(
                shelter.containsKey(
                        "prepare_foundation_shelter_materials"
                )
        );
        assertFalse(shelter.containsKey("build_shelter_step"));
        assertFalse(
                shelter.containsKey(
                        "establish_foundation_workstations"
                )
        );

        final String shelterReadyRuntime = """
            {
              "verifiedCompletionRouteData": {
                "profile": "FOUNDATION",
                "nextObjectives": ["BUILD_DYNAMIC_SHELTER"],
                "criticalOwnedCounts": {
                  "same_structural_item": 55,
                  "safe_doors": 3,
                  "shelter_lights": 4
                },
                "currentMinimumTargets": {
                  "same_structural_item": 55,
                  "safe_doors": 1,
                  "shelter_lights": 1
                }
              }
            }
            """;
        final var shelterReady = MinecraftPlannerInputFactory
                .foundationPhaseSkills(all, shelterReadyRuntime);
        assertTrue(shelterReady.containsKey("build_shelter_step"));
        assertFalse(
                shelterReady.containsKey(
                        "prepare_foundation_shelter_materials"
                )
        );

        final String shelterInProgressRuntime = """
            {
              "verifiedCompletionRouteData": {
                "profile": "FOUNDATION",
                "nextObjectives": ["BUILD_DYNAMIC_SHELTER"],
                "verifiedMilestones": [
                  "SHELTER_MATERIALS_PREPARED"
                ],
                "criticalOwnedCounts": {
                  "same_structural_item": 53,
                  "safe_doors": 1,
                  "shelter_lights": 1
                },
                "currentMinimumTargets": {
                  "same_structural_item": 55,
                  "safe_doors": 1,
                  "shelter_lights": 1
                }
              }
            }
            """;
        final var shelterInProgress = MinecraftPlannerInputFactory
                .foundationPhaseSkills(
                        all,
                        shelterInProgressRuntime
                );
        assertTrue(
                shelterInProgress.containsKey(
                        "build_shelter_step"
                )
        );
        assertFalse(
                shelterInProgress.containsKey(
                        "prepare_foundation_shelter_materials"
                )
        );

        final String shelterShortageRuntime = """
            {
              "lastSkillStartRejectionCode":
                "shelter.missing_structural_material",
              "verifiedCompletionRouteData": {
                "profile": "FOUNDATION",
                "nextObjectives": ["BUILD_DYNAMIC_SHELTER"],
                "verifiedMilestones": [
                  "SHELTER_MATERIALS_PREPARED"
                ],
                "criticalOwnedCounts": {
                  "same_structural_item": 0,
                  "safe_doors": 1,
                  "shelter_lights": 1
                },
                "currentMinimumTargets": {
                  "same_structural_item": 55,
                  "safe_doors": 1,
                  "shelter_lights": 1
                }
              }
            }
            """;
        final var shelterShortage = MinecraftPlannerInputFactory
                .foundationPhaseSkills(all, shelterShortageRuntime);
        assertFalse(
                shelterShortage.containsKey("build_shelter_step")
        );
        assertTrue(
                shelterShortage.containsKey(
                        "prepare_foundation_shelter_materials"
                )
        );
    }

    @Test
    void advancedFoundationSchemaHidesIrrelevantCombatAndLootSkills() {
        final SkillArgumentValidator accepts =
                arguments -> Optional.empty();
        final Map<String, SkillArgumentValidator> all = Map.of(
                "establish_foundation_workstations",
                accepts,
                "survey_surroundings",
                accepts,
                "gather_visible_block_cluster",
                accepts,
                "collect_observed_item",
                accepts,
                "engage_and_collect_observed_drop",
                accepts,
                "attack_entity",
                accepts,
                "activate_observed_end_portal",
                accepts
        );
        final String runtime = """
            {
              "verifiedCompletionRouteData": {
                "profile": "FOUNDATION",
                "nextObjectives": [
                  "ESTABLISH_FOUNDATION_WORKSTATIONS"
                ]
              }
            }
            """;

        final Map<String, SkillArgumentValidator> admitted =
                MinecraftPlannerInputFactory.foundationPhaseSkills(
                        all,
                        runtime
                );

        assertEquals(
                java.util.Set.of(
                        "establish_foundation_workstations"
                ),
                admitted.keySet()
        );
    }

    @Test
    void nightAndCompletedFoundationSchemasCannotDismantleTheShelter() {
        final SkillArgumentValidator accepts =
                arguments -> Optional.empty();
        final Map<String, SkillArgumentValidator> all = Map.of(
                "gather_visible_block_cluster",
                accepts,
                "break_block",
                accepts,
                "build_shelter_step",
                accepts,
                "move_to",
                accepts,
                "consume_owned_food",
                accepts,
                "engage_observed_entity",
                accepts,
                "sleep_in_observed_bed",
                accepts
        );
        final String nightRuntime = """
            {
              "verifiedCompletionRouteData": {
                "profile": "FOUNDATION",
                "nextObjectives": [
                  "SURVIVE_OR_SLEEP_THROUGH_NIGHT"
                ]
              }
            }
            """;

        final Map<String, SkillArgumentValidator> night =
                MinecraftPlannerInputFactory.foundationPhaseSkills(
                        all,
                        nightRuntime
                );

        assertEquals(
                java.util.Set.of(
                        "move_to",
                        "consume_owned_food",
                        "engage_observed_entity",
                        "sleep_in_observed_bed"
                ),
                night.keySet()
        );
        assertFalse(night.containsKey("gather_visible_block_cluster"));
        assertFalse(night.containsKey("break_block"));
        assertFalse(night.containsKey("build_shelter_step"));

        final String completeRuntime = """
            {
              "verifiedCompletionRouteData": {
                "profile": "FOUNDATION",
                "nextObjectives": []
              }
            }
            """;
        assertTrue(
                MinecraftPlannerInputFactory.foundationPhaseSkills(
                        all,
                        completeRuntime
                ).isEmpty()
        );
    }

    @Test
    void completionSchemaEnforcesTheCurrentServerVerifiedPhase() {
        final SkillArgumentValidator accepts =
                arguments -> Optional.empty();
        final Map<String, SkillArgumentValidator> all = Map.ofEntries(
                Map.entry("prepare_basic_crafting", accepts),
                Map.entry("prepare_stone_tools", accepts),
                Map.entry("secure_visible_food_reserve", accepts),
                Map.entry("prepare_iron_toolkit", accepts),
                Map.entry("survey_surroundings", accepts),
                Map.entry("explore_for_observed_target", accepts),
                Map.entry("travel_to", accepts),
                Map.entry("gather_visible_block_cluster", accepts),
                Map.entry("engage_observed_entity", accepts),
                Map.entry("equip_item", accepts),
                Map.entry("craft_recipe", accepts),
                Map.entry("consume_owned_food", accepts),
                Map.entry(
                        "build_and_light_nether_portal",
                        accepts
                ),
                Map.entry(
                        "cast_observed_nether_portal",
                        accepts
                ),
                Map.entry("acquire_nether_blaze_rod", accepts),
                Map.entry(
                        "secure_nether_blaze_material",
                        accepts
                ),
                Map.entry(
                        "acquire_sheltered_ender_pearl",
                        accepts
                ),
                Map.entry(
                        "secure_ender_pearl_reserve",
                        accepts
                ),
                Map.entry("trace_stronghold_eye", accepts),
                Map.entry(
                        "triangulate_stronghold_search_area",
                        accepts
                ),
                Map.entry(
                        "reach_observed_stronghold",
                        accepts
                ),
                Map.entry(
                        "search_stronghold_portal_room",
                        accepts
                ),
                Map.entry(
                        "activate_observed_end_portal",
                        accepts
                ),
                Map.entry("enter_observed_portal", accepts),
                Map.entry(
                        "find_and_enter_observed_portal",
                        accepts
                ),
                Map.entry(
                        "return_via_verified_portal",
                        accepts
                ),
                Map.entry("reach_end_island", accepts),
                Map.entry("fight_ender_dragon", accepts)
        );

        assertEquals(
                java.util.Set.of("prepare_basic_crafting"),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        completionRuntime("PREPARE_BASIC_CRAFTING")
                ).keySet()
        );
        assertEquals(
                java.util.Set.of("prepare_stone_tools"),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        completionRuntime("CRAFT_AND_MINE_STONE")
                ).keySet()
        );
        assertEquals(
                java.util.Set.of("secure_visible_food_reserve"),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        completionRuntime("SECURE_FOOD_RESERVE")
                ).keySet()
        );
        assertEquals(
                java.util.Set.of("prepare_iron_toolkit"),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        completionRuntime("ACQUIRE_IRON_TOOLKIT")
                ).keySet()
        );

        final var netherRoute = MinecraftPlannerInputFactory
                .completionPhaseSkills(
                        all,
                        completionRuntime(
                                "BUILD_AND_VERIFY_NETHER_ROUTE"
                        )
                );
        assertTrue(netherRoute.containsKey(
                "build_and_light_nether_portal"
        ));
        assertTrue(netherRoute.containsKey(
                "cast_observed_nether_portal"
        ));
        assertTrue(netherRoute.containsKey(
                "find_and_enter_observed_portal"
        ));
        final var builtNetherPortal = MinecraftPlannerInputFactory
                .completionPhaseSkills(
                        all,
                        """
                        {
                          "skillName":
                            "build_and_light_nether_portal",
                          "terminalStatus": "COMPLETED",
                          "verifiedCompletionRouteData": {
                            "profile": "COMPLETION",
                            "nextObjectives": [
                              "BUILD_AND_VERIFY_NETHER_ROUTE"
                            ]
                          }
                        }
                        """
                );
        assertEquals(
                java.util.Set.of(
                        "find_and_enter_observed_portal"
                ),
                builtNetherPortal.keySet()
        );

        final var blaze = MinecraftPlannerInputFactory
                .completionPhaseSkills(
                        all,
                        completionRuntime(
                                "FIND_AND_ACQUIRE_BLAZE_MATERIAL"
                        )
                );
        assertTrue(
                blaze.containsKey(
                        "secure_nether_blaze_material"
                )
        );
        assertFalse(blaze.containsKey("acquire_nether_blaze_rod"));
        assertTrue(blaze.containsKey("explore_for_observed_target"));
        assertTrue(blaze.containsKey("enter_observed_portal"));
        assertFalse(
                blaze.containsKey("acquire_sheltered_ender_pearl")
        );
        assertFalse(blaze.containsKey("trace_stronghold_eye"));
        assertFalse(blaze.containsKey("fight_ender_dragon"));

        final var pearls = MinecraftPlannerInputFactory
                .completionPhaseSkills(
                        all,
                        completionRuntime("ACQUIRE_ENDER_PEARLS")
                );
        assertEquals(
                java.util.Set.of(
                        "secure_ender_pearl_reserve",
                        "consume_owned_food",
                        "enter_observed_portal"
                ),
                pearls.keySet()
        );
        assertFalse(
                pearls.containsKey("acquire_sheltered_ender_pearl")
        );
        assertFalse(pearls.containsKey("survey_surroundings"));
        assertFalse(pearls.containsKey("explore_for_observed_target"));
        assertFalse(pearls.containsKey("travel_to"));
        assertFalse(pearls.containsKey("acquire_nether_blaze_rod"));
        assertFalse(pearls.containsKey("trace_stronghold_eye"));
        assertFalse(pearls.containsKey("fight_ender_dragon"));

        assertEquals(
                java.util.Set.of("craft_recipe"),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        completionRuntime("CRAFT_EYES_OF_ENDER")
                ).keySet()
        );
        assertEquals(
                java.util.Set.of(
                        "triangulate_stronghold_search_area",
                        "return_via_verified_portal",
                        "consume_owned_food"
                ),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        completionRuntime(
                                "TRACE_STRONGHOLD_BEARING"
                        )
                ).keySet()
        );
        assertEquals(
                java.util.Set.of(
                        "triangulate_stronghold_search_area",
                        "return_via_verified_portal",
                        "consume_owned_food"
                ),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        completionRuntime(
                                "TRIANGULATE_STRONGHOLD_SEARCH_AREA"
                        )
                ).keySet()
        );

        final var endLoadout = MinecraftPlannerInputFactory
                .completionPhaseSkills(
                        all,
                        completionRuntime("PREPARE_END_LOADOUT")
                );
        assertTrue(endLoadout.containsKey("craft_recipe"));
        assertTrue(endLoadout.containsKey("gather_visible_block_cluster"));
        assertTrue(endLoadout.containsKey("engage_observed_entity"));
        assertFalse(endLoadout.containsKey("activate_observed_end_portal"));
        assertFalse(endLoadout.containsKey("find_and_enter_observed_portal"));
        assertFalse(endLoadout.containsKey("reach_end_island"));
        assertFalse(endLoadout.containsKey("fight_ender_dragon"));

        final var endPortal = MinecraftPlannerInputFactory
                .completionPhaseSkills(
                        all,
                        completionRuntime(
                                "ACTIVATE_AND_ENTER_END_PORTAL"
                        )
                );
        assertTrue(
                endPortal.containsKey(
                        "activate_observed_end_portal"
                )
        );
        assertTrue(
                endPortal.containsKey(
                        "reach_observed_stronghold"
                )
        );
        assertTrue(endPortal.containsKey("travel_to"));
        assertTrue(endPortal.containsKey("survey_surroundings"));
        assertTrue(
                endPortal.containsKey(
                        "explore_for_observed_target"
                )
        );
        assertTrue(
                endPortal.containsKey(
                        "find_and_enter_observed_portal"
                )
        );
        assertFalse(endPortal.containsKey("fight_ender_dragon"));
        final var measuredSearchArea = MinecraftPlannerInputFactory
                .completionPhaseSkills(
                        all,
                        """
                        {
                          "verifiedCompletionRouteData": {
                            "profile": "COMPLETION",
                            "nextObjectives": [
                              "ACTIVATE_AND_ENTER_END_PORTAL"
                            ],
                            "verifiedMilestones": [
                              "STRONGHOLD_SEARCH_AREA_TRIANGULATED"
                            ]
                          }
                        }
                        """
                );
        assertFalse(
                measuredSearchArea.containsKey("reach_observed_stronghold"),
                "A completed measured search area must not re-admit reach"
        );
        assertTrue(
                measuredSearchArea.containsKey(
                        "search_stronghold_portal_room"
                )
        );
        final var evidenceRecovery = MinecraftPlannerInputFactory
                .completionPhaseSkills(
                        all,
                        """
                        {
                          "lastSkillStartRejectionCode":
                            "search_stronghold_portal_room.stronghold_evidence_required",
                          "verifiedCompletionRouteData": {
                            "profile": "COMPLETION",
                            "nextObjectives": [
                              "ACTIVATE_AND_ENTER_END_PORTAL"
                            ],
                            "verifiedMilestones": [
                              "STRONGHOLD_SEARCH_AREA_TRIANGULATED"
                            ]
                          }
                        }
                        """
                );
        assertTrue(
                evidenceRecovery.containsKey(
                        "reach_observed_stronghold"
                ),
                "Evidence rejection must reopen fair stronghold ingress"
        );
        assertFalse(
                evidenceRecovery.containsKey(
                        "search_stronghold_portal_room"
                ),
                "The just-rejected portal search must not be retried"
        );
        final var activatedEndPortal = MinecraftPlannerInputFactory
                .completionPhaseSkills(
                        all,
                        """
                        {
                          "skillName":
                            "activate_observed_end_portal",
                          "terminalStatus": "COMPLETED",
                          "verifiedCompletionRouteData": {
                            "profile": "COMPLETION",
                            "nextObjectives": [
                              "ACTIVATE_AND_ENTER_END_PORTAL"
                            ]
                          }
                        }
                        """
                );
        assertEquals(
                java.util.Set.of(
                        "find_and_enter_observed_portal"
                ),
                activatedEndPortal.keySet()
        );

        assertEquals(
                java.util.Set.of("reach_end_island"),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        completionRuntime("REACH_END_ISLAND")
                ).keySet()
        );
        assertEquals(
                java.util.Set.of("reach_end_island"),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        completionRuntime("DEFEAT_ENDER_DRAGON")
                ).keySet()
        );
        assertEquals(
                java.util.Set.of("reach_end_island"),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        """
                        {
                          "skillName": "reach_end_island",
                          "terminalStatus": "COMPLETED",
                          "verifiedCompletionRouteData": {
                            "profile": "COMPLETION",
                            "nextObjectives": [
                              "DEFEAT_ENDER_DRAGON"
                            ],
                            "verifiedMilestones": []
                          }
                        }
                        """
                ).keySet()
        );
        final String verifiedIsland = """
            {
              "verifiedCompletionRouteData": {
                "profile": "COMPLETION",
                "nextObjectives": ["DEFEAT_ENDER_DRAGON"],
                "verifiedMilestones": ["END_ISLAND_REACHED"]
              }
            }
            """;
        assertEquals(
                java.util.Set.of("fight_ender_dragon"),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        verifiedIsland
                ).keySet()
        );
        assertEquals(
                java.util.Set.of("fight_ender_dragon"),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        """
                        {
                          "skillName": "fight_ender_dragon",
                          "terminalStatus": "FAILED",
                          "failureCode":
                            "fight_ender_dragon.no_visible_combat_target",
                          "verifiedCompletionRouteData": {
                            "profile": "COMPLETION",
                            "nextObjectives": [
                              "DEFEAT_ENDER_DRAGON"
                            ],
                            "verifiedMilestones": [
                              "END_ISLAND_REACHED"
                            ]
                          }
                        }
                        """
                ).keySet()
        );
        assertEquals(
                java.util.Set.of("reach_end_island"),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        """
                        {
                          "skillName": "fight_ender_dragon",
                          "terminalStatus": "FAILED",
                          "failureCode":
                            "fight_ender_dragon.end_island_ingress_required",
                          "verifiedCompletionRouteData": {
                            "profile": "COMPLETION",
                            "nextObjectives": [
                              "DEFEAT_ENDER_DRAGON"
                            ],
                            "verifiedMilestones": [
                              "END_ISLAND_REACHED"
                            ]
                          }
                        }
                        """
                ).keySet()
        );
        assertEquals(
                java.util.Set.of("reach_end_island"),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        """
                        {
                          "lastSkillStartRejectionCode":
                            "fight_ender_dragon.end_island_ingress_required",
                          "verifiedCompletionRouteData": {
                            "profile": "COMPLETION",
                            "nextObjectives": [
                              "DEFEAT_ENDER_DRAGON"
                            ],
                            "verifiedMilestones": [
                              "END_ISLAND_REACHED"
                            ]
                          }
                        }
                        """
                ).keySet()
        );
        final var returned = MinecraftPlannerInputFactory
                .completionPhaseSkills(
                        all,
                        completionRuntime("ENTER_RETURN_PORTAL")
                );
        assertTrue(
                returned.containsKey(
                        "find_and_enter_observed_portal"
                )
        );
        assertFalse(returned.containsKey("enter_observed_portal"));
        assertFalse(returned.containsKey("fight_ender_dragon"));
    }

    @Test
    void strongholdPhaseRequiresPortalReturnOutsideTheOverworld() {
        final SkillArgumentValidator accepts =
                arguments -> Optional.empty();
        final Map<String, SkillArgumentValidator> phaseSkills = Map.of(
                "triangulate_stronghold_search_area",
                accepts,
                "return_via_verified_portal",
                accepts,
                "consume_owned_food",
                accepts
        );
        final String runtime = completionRuntime(
                "TRACE_STRONGHOLD_BEARING"
        );

        assertEquals(
                java.util.Set.of(
                        "return_via_verified_portal",
                        "consume_owned_food"
                ),
                MinecraftPlannerInputFactory
                        .completionDimensionHandoffSkills(
                                phaseSkills,
                                """
                                {
                                  "self": {
                                    "dimension": "minecraft:the_nether"
                                  }
                                }
                                """,
                                runtime
                        ).keySet()
        );
        assertEquals(
                java.util.Set.of(
                        "triangulate_stronghold_search_area",
                        "consume_owned_food"
                ),
                MinecraftPlannerInputFactory
                        .completionDimensionHandoffSkills(
                                phaseSkills,
                                """
                                {
                                  "self": {
                                    "dimension": "minecraft:overworld"
                                  }
                                }
                                """,
                                runtime
                        ).keySet()
        );
        assertTrue(
                MinecraftPlannerInputFactory
                        .completionDimensionHandoffSkills(
                                phaseSkills,
                                "{}",
                                runtime
                        ).isEmpty()
        );
    }

    @Test
    void completedOrMalformedCompletionRouteFailsClosed() {
        final SkillArgumentValidator accepts =
                arguments -> Optional.empty();
        final Map<String, SkillArgumentValidator> all = Map.of(
                "fight_ender_dragon",
                accepts,
                "enter_observed_portal",
                accepts
        );
        assertTrue(
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        completionRuntime("")
                ).isEmpty()
        );
        assertTrue(
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        """
                        {
                          "verifiedCompletionRouteData": {
                            "profile": "COMPLETION"
                          }
                        }
                        """
                ).isEmpty()
        );
        assertEquals(
                all.keySet(),
                MinecraftPlannerInputFactory.completionPhaseSkills(
                        all,
                        "{}"
                ).keySet()
        );
    }

    @Test
    void completionPromptNamesOnlyTheCurrentPhysicalPhase() {
        final SkillRegistry skills = new SkillRegistry()
                .register(
                        "secure_nether_blaze_material",
                        noArgumentSkill()
                )
                .register(
                        "acquire_sheltered_ender_pearl",
                        noArgumentSkill()
                )
                .register(
                        "fight_ender_dragon",
                        noArgumentSkill()
                );
        final var input = new MinecraftPlannerInputFactory(
                skills,
                CompanionRuntime.coreSkillGuideForTests()
        ).create(
                "request-completion-blaze",
                new GoalSnapshot(
                        java.util.Optional.empty(),
                        7,
                        GoalStatus.RUNNING,
                        GoalSource.HARDCORE_EVALUATION,
                        "通关 Minecraft",
                        "",
                        java.time.Instant.EPOCH,
                        true
                ),
                new BrainObservation(
                        23,
                        new SkillContext(
                                7,
                                23,
                                800,
                                true,
                                true,
                                0.0
                        ),
                        "{}",
                        completionRuntime(
                                "FIND_AND_ACQUIRE_BLAZE_MATERIAL"
                        )
                )
        );

        assertEquals(
                java.util.Set.of(
                        "secure_nether_blaze_material"
                ),
                input.decisionContext().availableSkills().keySet()
        );
        assertTrue(input.systemPrompt().contains(
                "Current verified completion phase:"
        ));
        assertTrue(input.systemPrompt().contains(
                "FIND_AND_ACQUIRE_BLAZE_MATERIAL"
        ));
        assertTrue(input.systemPrompt().contains(
                "Available local skill names: "
                    + "[secure_nether_blaze_material]"
        ));
        assertTrue(input.systemPrompt().contains(
                "that requires a physical change must return START_SKILL"
        ));
        assertFalse(input.systemPrompt().contains(
                "acquire_sheltered_ender_pearl"
        ));
        assertFalse(input.systemPrompt().contains(
                "fight_ender_dragon"
        ));
    }

    @Test
    void shelterReadinessFailsClosedForPartialOrMalformedEvidence() {
        assertFalse(
                MinecraftPlannerInputFactory
                        .foundationWorkstationWoodReady("{}")
        );
        assertFalse(
                MinecraftPlannerInputFactory
                        .foundationWorkstationWoodReady("""
                            {
                              "verifiedCompletionRouteData": {
                                "profile": "FOUNDATION",
                                "criticalOwnedCounts": {
                                  "chest_plank_potential": 7
                                },
                                "currentMinimumTargets": {
                                  "chest_plank_potential": 8
                                }
                              }
                            }
                            """)
        );
        assertTrue(
                MinecraftPlannerInputFactory
                        .foundationWorkstationWoodReady("""
                            {
                              "verifiedCompletionRouteData": {
                                "profile": "FOUNDATION",
                                "criticalOwnedCounts": {
                                  "chest_plank_potential": 8
                                },
                                "currentMinimumTargets": {
                                  "chest_plank_potential": 8
                                }
                              }
                            }
                            """)
        );
        assertFalse(
                MinecraftPlannerInputFactory
                        .foundationShelterInputsReady("{}")
        );
        assertFalse(
                MinecraftPlannerInputFactory
                        .foundationShelterInputsReady("""
                            {
                              "verifiedCompletionRouteData": {
                                "profile": "FOUNDATION",
                                "criticalOwnedCounts": {
                                  "same_structural_item": 55,
                                  "safe_doors": 1,
                                  "shelter_lights": 0
                                },
                                "currentMinimumTargets": {
                                  "same_structural_item": 55,
                                  "safe_doors": 1,
                                  "shelter_lights": 1
                                }
                              }
                            }
                            """)
        );
        assertTrue(
                MinecraftPlannerInputFactory
                        .foundationShelterConstructionCommitted("""
                            {
                              "verifiedCompletionRouteData": {
                                "profile": "FOUNDATION",
                                "verifiedMilestones": [
                                  "SHELTER_MATERIALS_PREPARED"
                                ]
                              }
                            }
                            """)
        );
        assertFalse(
                MinecraftPlannerInputFactory
                        .foundationShelterConstructionCommitted("{}")
        );
    }

    @Test
    void completedFoundationAdvertisesCompletionWithoutRetiredSkills() {
        final String filteredGuide = MinecraftPlannerInputFactory
                .guideForAvailableSkills(
                        """
                        build_shelter_step: dimension,sampleSequence,scale
                        establish_foundation_workstations takes no arguments.
                        sleep_in_observed_bed: copy one visible bed face.
                        """,
                        java.util.Set.of(
                                "build_shelter_step",
                                "establish_foundation_workstations",
                                "sleep_in_observed_bed"
                        ),
                        java.util.Set.of("sleep_in_observed_bed")
                );
        assertFalse(filteredGuide.contains("build_shelter_step"));
        assertFalse(filteredGuide.contains(
                "establish_foundation_workstations"
        ));
        assertTrue(filteredGuide.contains("sleep_in_observed_bed"));

        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                2,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "建立安全据点并生存到第二天",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final var input = new MinecraftPlannerInputFactory(
                new SkillRegistry(),
                "guide"
        ).create(
                "request-foundation-complete",
                goal,
                new BrainObservation(
                        18,
                        new SkillContext(
                                2,
                                18,
                                12_100,
                                true,
                                true,
                                0.0
                        ),
                        "{}",
                        """
                        {
                          "verifiedCompletionRouteData": {
                            "profile": "FOUNDATION",
                            "verifiedMilestones": [
                              "SHELTER_COMPLETED",
                              "FIRST_NIGHT_SURVIVED"
                            ],
                            "nextObjectives": []
                          }
                        }
                        """
                )
        );

        assertTrue(input.systemPrompt().contains(
                "Choose COMPLETE_GOAL now"
        ));
        assertTrue(input.systemPrompt().contains(
                "TRUSTED_FOUNDATION_CURRENT_PHASE"
        ));
        assertFalse(input.systemPrompt().contains(
                "TRUSTED_FOUNDATION_ROUTE_PLAYBOOK"
        ));
    }

    @Test
    void stonePhaseHidesRetiredMicroSkillsFromPromptAndSchema() {
        final Skill<Unit> noArguments = noArgumentSkill();
        final SkillRegistry skills = new SkillRegistry()
                .register("prepare_stone_tools", noArguments)
                .register("craft_recipe", noArguments)
                .register("collect_observed_item", noArguments);
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                2,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "建立安全据点并生存到第二天",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final var input = new MinecraftPlannerInputFactory(
                skills,
                CompanionRuntime.coreSkillGuideForTests()
        ).create(
                "request-stone-phase",
                goal,
                new BrainObservation(
                        19,
                        new SkillContext(
                                2,
                                19,
                                12_200,
                                true,
                                true,
                                0.0
                        ),
                        """
                        {
                          "sampleSequence": 51,
                          "craftingAffordances": [{
                            "recipeId": "minecraft:oak_planks"
                          }],
                          "visibleEntities": [{
                            "observationId": "visible-drop",
                            "type": "minecraft:item"
                          }]
                        }
                        """,
                        """
                        {
                          "verifiedCompletionRouteData": {
                            "profile": "FOUNDATION",
                            "nextObjectives": [
                              "CRAFT_AND_MINE_STONE"
                            ]
                          }
                        }
                        """
                )
        );

        assertEquals(
                java.util.Set.of("prepare_stone_tools"),
                input.decisionContext().availableSkills().keySet()
        );
        assertTrue(input.systemPrompt().contains(
                "Available local skill names: [prepare_stone_tools]"
        ));
        assertTrue(input.systemPrompt().contains(
                "Current verified M1 phase: CRAFT_AND_MINE_STONE"
        ));
        assertFalse(input.systemPrompt().contains("craft_recipe"));
        assertFalse(input.systemPrompt().contains(
                "collect_observed_item"
        ));
    }

    @Test
    void genericSkillUsageGuidanceTracksTheCurrentAllowList() {
        assertFalse(
                MinecraftPlannerInputFactory.localSkillUsageGuidance(
                        java.util.Set.of()
                ).contains("craft_recipe")
        );
        assertTrue(
                MinecraftPlannerInputFactory.localSkillUsageGuidance(
                        java.util.Set.of("craft_recipe")
                ).contains("copy recipeId")
        );
        assertFalse(
                MinecraftPlannerInputFactory.localSkillUsageGuidance(
                        java.util.Set.of("use_block")
                ).contains("To place an owned block")
        );
        assertTrue(
                MinecraftPlannerInputFactory.localSkillUsageGuidance(
                        java.util.Set.of("use_block", "equip_item")
                ).contains("To place an owned block")
        );
    }

    @Test
    void allIndividuallyLegalContextComponentsFitTogether() {
        final String maximumGuide =
                "g".repeat(
                        MinecraftPlannerInputFactory
                                .MAX_SKILL_GUIDE_CHARACTERS
                );
        final String maximumOwnerPreference = "p".repeat(4_096);
        final String trustedRuntime = "{\"padding\":\""
                + "r".repeat(
                        BrainObservation
                                .MAX_TRUSTED_RUNTIME_JSON_CHARACTERS
                            - 14
                )
                + "\"}";
        final var factory = new MinecraftPlannerInputFactory(
                new SkillRegistry(),
                maximumGuide,
                512,
                () -> new MinecraftPlannerInputFactory
                        .AgentPromptSettings(
                                "ContextAgent",
                                0.2,
                                maximumOwnerPreference
                        )
        );

        assertDoesNotThrow(() -> factory.create(
                "request-maximum-context",
                new GoalSnapshot(
                        java.util.Optional.empty(),
                        1,
                        GoalStatus.RUNNING,
                        GoalSource.PLAYER_CHAT,
                        "continue the current task",
                        "",
                        java.time.Instant.EPOCH,
                        false
                ),
                new BrainObservation(
                        1,
                        new SkillContext(
                                1,
                                1,
                                1,
                                false,
                                true,
                                0.0
                        ),
                        "{}",
                        trustedRuntime
                )
        ));
    }

    @Test
    void trustedAgentPreferencesAndProviderTemperatureRemainSeparated() {
        final SkillRegistry skills = new SkillRegistry();
        final var input = new MinecraftPlannerInputFactory(
            skills,
            "guide",
            512,
            () -> new MinecraftPlannerInputFactory.AgentPromptSettings(
                "Builder_1",
                0.8,
                "Prefer spruce and explain material shortages."
            )
        ).create(
            "request-agent-style",
            new GoalSnapshot(
                java.util.Optional.of(java.util.UUID.randomUUID()),
                1,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "Build a shelter",
                "",
                java.time.Instant.EPOCH,
                false
            ),
            new BrainObservation(
                1,
                new SkillContext(1, 1, 1, false, true, 0.0),
                "{}",
                "{}"
            )
        );

        assertTrue(input.systemPrompt().contains("Builder_1"));
        assertTrue(input.systemPrompt().contains("Prefer spruce"));
        assertFalse(input.systemPrompt().contains("temperature"));
        assertEquals(0.8, input.temperature());
    }

    @Test
    void bindsTrustedGoalAndExactDecisionRevisions() {
        final GoalCoordinator goals = new GoalCoordinator(
            new InMemoryGoalRevisionStore()
        );
        goals.setGoal("建立一个安全庇护所", GoalSource.PLAYER_CHAT);
        final var goal = goals.snapshot();
        final var observation = new BrainObservation(
            9,
            new SkillContext(goal.revision(), 9, 100, false, true, 0.0),
            "{\"sampleSequence\":3}",
            "{\"skillState\":\"FAILED\",\"failureCode\":\"move_to.no_route\"}"
        );
        final var input = new MinecraftPlannerInputFactory(
            new SkillRegistry(),
            ""
        ).create("request-1", goal, observation);

        assertEquals("request-1", input.decisionContext().requestId());
        assertEquals(9, input.decisionContext().observedWorldRevision());
        assertEquals(goal.revision(), input.decisionContext().goalRevision());
        assertFalse(input.decisionContext().activeSkill());
        assertTrue(input.systemPrompt().contains("建立一个安全庇护所"));
        assertTrue(input.systemPrompt().contains("No local skills"));
        assertTrue(input.systemPrompt().contains("TRUSTED_LOCAL_EXECUTION"));
        assertTrue(input.systemPrompt().contains("move_to.no_route"));
        assertTrue(input.systemPrompt().contains(
            "recalledVerifiedPortalEdgeData"
        ));
        assertTrue(input.systemPrompt().contains(
            "Never infer a reverse edge"
        ));
        assertFalse(input.systemPrompt().contains("copy recipeId"));
        assertTrue(input.systemPrompt().contains(
            "lastSkillStartRejectionCode"
        ));
        assertEquals("{\"sampleSequence\":3}", input.observationJson());
    }

    @Test
    void lockedEvaluationExplicitlyDisallowsAskingForIntervention() {
        final GoalCoordinator goals = new GoalCoordinator(
            new InMemoryGoalRevisionStore()
        );
        goals.startHardcoreEvaluation("通关 Minecraft");
        final var goal = goals.snapshot();
        final var observation = new BrainObservation(
            0,
            new SkillContext(goal.revision(), 0, 1, true, true, 0.0),
            "{}"
        );
        final var input = new MinecraftPlannerInputFactory(
            new SkillRegistry(),
            ""
        ).create("request-locked", goal, observation);

        assertTrue(input.systemPrompt().contains("Never choose ASK_PLAYER"));
    }

    @Test
    void foundationGoalReceivesTheConditionalVerifiedRoutePlaybook() {
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                2,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "建立安全据点并生存到第二天",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final var input = new MinecraftPlannerInputFactory(
                new SkillRegistry(),
                "guide"
        ).create(
                "request-foundation",
                goal,
                new BrainObservation(
                        4,
                        new SkillContext(2, 4, 20, true, true, 0.0),
                        "{}",
                        "{}"
                )
        );

        assertTrue(input.systemPrompt().contains(
                "TRUSTED_FOUNDATION_ROUTE_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains(
                "seven iron ingots"
        ));
        assertTrue(input.systemPrompt().contains(
                "same_structural_item"
        ));
        assertTrue(input.systemPrompt().contains(
                "Never use a saved block"
        ));
        assertTrue(input.systemPrompt().contains(
                "never send scale alone"
        ));
        assertTrue(input.systemPrompt().contains(
                "all of dimension,"
        ));
        assertTrue(input.systemPrompt().contains(
                "cannot be made in the"
        ));
        assertTrue(input.systemPrompt().contains(
                "do not stockpile"
        ));
        assertTrue(input.systemPrompt().contains(
            "lastModelDecisionFailureCode"
        ));
        assertTrue(input.systemPrompt().contains(
                "For unknown_skill"
        ));
        assertTrue(input.systemPrompt().contains(
                "SAFE_IDLE decision permanently ends"
        ));
        assertTrue(input.systemPrompt().contains(
                "never use it as a pause"
        ));
        assertTrue(input.systemPrompt().contains(
                "When currentSafetyDeficits reports active contact"
        ));
        assertTrue(input.systemPrompt().contains(
                "do not return a speech-only CONTINUE or REPLAN"
        ));
        assertTrue(input.systemPrompt().contains(
                "never say that you are guarding"
        ));
        assertTrue(
                input.systemPrompt().indexOf(
                        "prepare_stone_tools"
                )
                < input.systemPrompt().indexOf(
                        "secure_visible_food_reserve"
                )
        );
    }

    @Test
    void ironPhaseDirectsTheModelIntoTheSelfContainedCompound() {
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                2,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "建立安全据点并生存到第二天",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final var input = new MinecraftPlannerInputFactory(
                new SkillRegistry(),
                MiningSkills.plannerGuide()
        ).create(
                "request-iron",
                goal,
                new BrainObservation(
                        6,
                        new SkillContext(
                                2,
                                6,
                                40,
                                true,
                                true,
                                0.0
                        ),
                        "{}",
                        """
                        {
                          "verifiedCompletionRouteData": {
                            "profile": "FOUNDATION",
                            "nextObjectives": [
                              "ACQUIRE_IRON_TOOLKIT"
                            ]
                          }
                        }
                        """
                )
        );

        assertTrue(input.systemPrompt().contains(
                "Choose prepare_iron_toolkit with no arguments now"
        ));
        assertTrue(input.systemPrompt().contains(
                "fair first-person scanning"
        ));
    }

    @Test
    void followGoalReceivesImmediateActionPlaybookButNotFoundationContext() {
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                2,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "跟我来",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final var input = new MinecraftPlannerInputFactory(
                new SkillRegistry(),
                "guide"
        ).create(
                "request-follow",
                goal,
                new BrainObservation(
                        4,
                        new SkillContext(2, 4, 20, false, true, 0.0),
                        "{}",
                        "{}"
                )
        );

        assertFalse(input.systemPrompt().contains(
                "TRUSTED_FOUNDATION_ROUTE_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains(
                "TRUSTED_IMMEDIATE_FOLLOW_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains(
                "choose START_SKILL follow_entity now"
        ));
        assertTrue(input.systemPrompt().contains(
                "properties.playerName"
        ));
        assertFalse(input.systemPrompt().contains("visibleProperties"));
        assertTrue(input.systemPrompt().contains(
                "Do not survey"
        ));
    }

    @Test
    void boundFollowGoalAdmitsOnlyObservedPlayerFollowAction() {
        final SkillRegistry skills = new SkillRegistry()
                .register("follow_entity", noArgumentSkill())
                .register("survey_surroundings", noArgumentSkill())
                .register("gather_visible_block_cluster", noArgumentSkill());
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                3,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "跟随发出请求的玩家并保持自然步行距离；"
                        + "serverBoundPlayerName=Alex;"
                        + " serverBoundPlayerUuid=00000000-0000-0000-0000-000000000001;"
                        + " 玩家原话：跟我走",
                "",
                java.time.Instant.EPOCH,
                false
        );

        final var input = new MinecraftPlannerInputFactory(skills, "guide")
                .create(
                        "request-bound-follow-visible",
                        goal,
                        new BrainObservation(
                                11,
                                new SkillContext(
                                        3,
                                        11,
                                        42,
                                        false,
                                        true,
                                        0.0
                                ),
                                """
                                {
                                  "sampleSequence": 73,
                                  "visibleEntities": [{
                                    "observationId": "visible-1",
                                    "type": "minecraft:player",
                                    "hostile": false,
                                    "properties": {
                                      "playerName": "alex"
                                    }
                                  }]
                                }
                                """,
                                "{}"
                        )
                );

        assertEquals(
                java.util.Set.of("follow_entity"),
                input.decisionContext().availableSkills().keySet()
        );
        assertTrue(input.systemPrompt().contains(
                "TRUSTED_IMMEDIATE_FOLLOW_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains("properties.playerName"));
        assertFalse(input.systemPrompt().contains("visibleProperties"));
        assertFalse(input.systemPrompt().contains(
                "gather_visible_block_cluster"
        ));
    }

    @Test
    void boundFollowGoalAdmitsOnlyBoundedSurveyUntilPlayerIsVisible() {
        final SkillRegistry skills = new SkillRegistry()
                .register("follow_entity", noArgumentSkill())
                .register("survey_surroundings", noArgumentSkill())
                .register("move_to", noArgumentSkill());
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                3,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "follow me;serverBoundPlayerName=Alex;"
                        + " serverBoundPlayerUuid=00000000-0000-0000-0000-000000000001;",
                "",
                java.time.Instant.EPOCH,
                false
        );

        final var input = new MinecraftPlannerInputFactory(skills, "guide")
                .create(
                        "request-bound-follow-reacquire",
                        goal,
                        new BrainObservation(
                                12,
                                new SkillContext(
                                        3,
                                        12,
                                        43,
                                        false,
                                        true,
                                        0.0
                                ),
                                """
                                {
                                  "sampleSequence": 74,
                                  "visibleEntities": []
                                }
                                """,
                                "{}"
                        )
                );

        assertEquals(
                java.util.Set.of("survey_surroundings"),
                input.decisionContext().availableSkills().keySet()
        );
        assertTrue(input.systemPrompt().contains(
                "first-person survey to reacquire the player"
        ));
        assertFalse(input.systemPrompt().contains("move_to"));
    }

    @Test
    void xaeroWaypointGoalReceivesImmediateCoordinateActionPlaybook() {
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                5,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "前往已授权玩家共享的坐标；不得传送或读取小地图隐藏数据。"
                        + " dimension=minecraft:overworld, x=120.000,"
                        + " y=64.000, z=-45.000。抵达目标三格内。",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final var input = new MinecraftPlannerInputFactory(
                new SkillRegistry(),
                "guide"
        ).create(
                "request-xaero",
                goal,
                new BrainObservation(
                        12,
                        new SkillContext(5, 12, 20, false, true, 0.0),
                        "{}",
                        "{}"
                )
        );

        assertTrue(input.systemPrompt().contains(
                "TRUSTED_IMMEDIATE_XAERO_WAYPOINT_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains(
                "choose START_SKILL move_to now"
        ));
        assertTrue(input.systemPrompt().contains(
                "verified portal edge"
        ));
        assertFalse(input.systemPrompt().contains(
                "TRUSTED_FOUNDATION_ROUTE_PLAYBOOK"
        ));
    }

    @Test
    void visibleItemGoalReceivesBoundedObservationBoundPickupPlaybook() {
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                3,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "请把你面前掉落的橡木原木捡进背包",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final var input = new MinecraftPlannerInputFactory(
                new SkillRegistry(),
                "guide"
        ).create(
                "request-item",
                goal,
                new BrainObservation(
                        9,
                        new SkillContext(
                                3,
                                9,
                                20,
                                false,
                                true,
                                0.0
                        ),
                        "{}",
                        """
                        {
                          "sampleSequence": 41,
                          "visibleEntities": [{
                            "observationId": "visible-0",
                            "type": "minecraft:item",
                            "properties": {
                              "itemId": "minecraft:oak_log"
                            }
                          }]
                        }
                        """
                )
        );

        assertTrue(input.systemPrompt().contains(
                "TRUSTED_IMMEDIATE_VISIBLE_ITEM_COLLECTION_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains(
                "choose START_SKILL collect_observed_item now"
        ));
        assertTrue(input.systemPrompt().contains(
                "exact observationId"
        ));
        assertTrue(input.systemPrompt().contains("properties.itemId"));
        assertFalse(input.systemPrompt().contains("visibleProperties"));
        assertFalse(input.systemPrompt().contains(
                "TRUSTED_FOUNDATION_ROUTE_PLAYBOOK"
        ));
    }

    @Test
    void visibleNamedWoodPickupAdmitsOnlyObservedItemCollection() {
        final SkillRegistry skills = new SkillRegistry()
                .register("collect_observed_item", noArgumentSkill())
                .register("follow_entity", noArgumentSkill())
                .register("move_to", noArgumentSkill());
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                3,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "把你面前掉落的橡木原木捡起来",
                "",
                java.time.Instant.EPOCH,
                false
        );

        final var input = new MinecraftPlannerInputFactory(skills, "guide")
                .create(
                        "request-item-schema",
                        goal,
                        new BrainObservation(
                                9,
                                new SkillContext(
                                        3,
                                        9,
                                        20,
                                        false,
                                        true,
                                        0.0
                                ),
                                """
                                {
                                  "sampleSequence": 41,
                                  "visibleEntities": [{
                                    "observationId": "visible-0",
                                    "type": "minecraft:item",
                                    "properties": {
                                      "itemId": "minecraft:oak_log"
                                    }
                                  }]
                                }
                                """,
                                "{}"
                        )
                );

        assertEquals(
                java.util.Set.of("collect_observed_item"),
                input.decisionContext().availableSkills().keySet()
        );
        assertFalse(input.decisionContext().availableSkills().containsKey(
                "move_to"
        ));
    }

    @Test
    void ordinaryItemRecoveryCopiesOnlyTheUniqueVisibleFairHandle() {
        final var handoff =
                MinecraftPlannerInputFactory
                        .immediateItemCollectionHandoffForRecovery(
                                "把你面前掉落的橡木原木捡进背包",
                                """
                                {
                                  "sampleSequence": 41,
                                  "visibleEntities": [{
                                    "observationId": "visible-0",
                                    "type": "minecraft:item",
                                    "properties": {
                                      "itemId": "minecraft:oak_log"
                                    }
                                  }]
                                }
                                """
                        );

        assertTrue(handoff.isPresent());
        assertEquals(41L, handoff.orElseThrow().sampleSequence());
        assertEquals(
                "visible-0",
                handoff.orElseThrow().observationId()
        );
        assertTrue(
                MinecraftPlannerInputFactory
                        .immediateItemCollectionHandoffForRecovery(
                                "把地上的东西捡起来",
                                """
                                {
                                  "sampleSequence": 42,
                                  "visibleEntities": [
                                    {
                                      "observationId": "visible-0",
                                      "type": "minecraft:item",
                                      "properties": {
                                        "itemId": "minecraft:oak_log"
                                      }
                                    },
                                    {
                                      "observationId": "visible-1",
                                      "type": "minecraft:item",
                                      "properties": {
                                        "itemId": "minecraft:cobblestone"
                                      }
                                    }
                                  ]
                                }
                                """
                        ).isEmpty()
        );
    }

    @Test
    void ambiguousDroppedItemsKeepTheBroaderSchemaInsteadOfGuessing() {
        final SkillRegistry skills = new SkillRegistry()
                .register("collect_observed_item", noArgumentSkill())
                .register("follow_entity", noArgumentSkill());
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                3,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "把地上的东西捡起来",
                "",
                java.time.Instant.EPOCH,
                false
        );

        final var input = new MinecraftPlannerInputFactory(skills, "guide")
                .create(
                        "request-item-ambiguous",
                        goal,
                        new BrainObservation(
                                9,
                                new SkillContext(3, 9, 20, false, true, 0.0),
                                """
                                {
                                  "sampleSequence": 41,
                                  "visibleEntities": [
                                    {
                                      "observationId": "visible-0",
                                      "type": "minecraft:item",
                                      "properties": {
                                        "itemId": "minecraft:oak_log"
                                      }
                                    },
                                    {
                                      "observationId": "visible-1",
                                      "type": "minecraft:item",
                                      "properties": {
                                        "itemId": "minecraft:cobblestone"
                                      }
                                    }
                                  ]
                                }
                                """,
                                "{}"
                        )
                );

        assertEquals(
                java.util.Set.of("collect_observed_item", "follow_entity"),
                input.decisionContext().availableSkills().keySet()
        );
    }

    @Test
    void englishNonDeicticWordsDoNotMasqueradeAsAnItemReference() {
        final SkillRegistry skills = new SkillRegistry()
                .register("collect_observed_item", noArgumentSkill())
                .register("follow_entity", noArgumentSkill());
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                3,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "Pick up with care.",
                "",
                java.time.Instant.EPOCH,
                false
        );

        final var input = new MinecraftPlannerInputFactory(skills, "guide")
                .create(
                        "request-item-non-deictic",
                        goal,
                        new BrainObservation(
                                9,
                                new SkillContext(3, 9, 20, false, true, 0.0),
                                """
                                {
                                  "sampleSequence": 41,
                                  "visibleEntities": [{
                                    "observationId": "visible-0",
                                    "type": "minecraft:item",
                                    "properties": {
                                      "itemId": "minecraft:oak_log"
                                    }
                                  }]
                                }
                                """,
                                "{}"
                        )
                );

        assertEquals(
                java.util.Set.of("collect_observed_item", "follow_entity"),
                input.decisionContext().availableSkills().keySet()
        );
    }

    @Test
    void visibleWoodTaskAdmitsOnlyObservationBoundGathering() {
        final SkillRegistry skills = new SkillRegistry()
                .register("gather_visible_block_cluster", noArgumentSkill())
                .register("gather_nearby_wood", noArgumentSkill())
                .register("survey_surroundings", noArgumentSkill())
                .register("move_to", noArgumentSkill())
                .register("craft_recipe", noArgumentSkill());
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                3,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "帮我砍点树，拿些木头回来",
                "",
                java.time.Instant.EPOCH,
                false
        );

        assertEquals(
                java.util.Set.of("gather_nearby_wood"),
                MinecraftPlannerInputFactory
                        .immediateVisibleBlockGatheringHandoffSkills(
                                skills.modelArgumentValidators(),
                                goal,
                                """
                                {
                                  "visibleBlockFaces": [{
                                    "block": {"x": 4, "y": 64, "z": -2},
                                    "type": "minecraft:oak_log",
                                    "face": "north"
                                  }]
                                }
                                """
                        ).keySet()
        );

        final var input = new MinecraftPlannerInputFactory(skills, "guide")
                .create(
                        "request-visible-wood",
                        goal,
                        new BrainObservation(
                                9,
                                new SkillContext(
                                        3,
                                        9,
                                        20,
                                        false,
                                        true,
                                        0.0
                                ),
                                """
                                {
                                  "sampleSequence": 51,
                                  "self": {
                                    "dimension": "minecraft:overworld"
                                  },
                                  "visibleBlockFaces": [{
                                    "block": {"x": 4, "y": 64, "z": -2},
                                    "type": "minecraft:oak_log",
                                    "face": "north"
                                  }]
                                }
                                """,
                                "{}"
                        )
                );

        assertEquals(
                java.util.Set.of("gather_nearby_wood"),
                input.decisionContext().availableSkills().keySet()
        );
        assertTrue(input.systemPrompt().contains(
                "TRUSTED_IMMEDIATE_VISIBLE_WOOD_GATHERING_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains(
                "gather_nearby_wood now"
        ));
        assertTrue(input.systemPrompt().contains(
                "with no arguments"
        ));
    }

    @Test
    void matureCropTaskAdmitsOnlyHarvestAndReplantWithExactSchema() {
        final SkillRegistry skills = new SkillRegistry()
                .register("harvest_and_replant_step", noArgumentSkill())
                .register("maintain_observed_crop_field", noArgumentSkill())
                .register("collect_observed_item", noArgumentSkill())
                .register("gather_visible_block_cluster", noArgumentSkill())
                .register("move_to", noArgumentSkill());
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                3,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "把你面前成熟的小麦全部收割，收完每一格都要重新种上；把收获物捡进背包",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final String semantic = """
                {
                  "sampleSequence": 41,
                  "visibleBlockFaces": [{
                    "block": {"x": 4, "y": 64, "z": -2},
                    "type": "minecraft:wheat",
                    "face": "up",
                    "state": {"age": "7"}
                  }]
                }
                """;
        assertEquals(
                java.util.Set.of(
                        "harvest_and_replant_step",
                        "maintain_observed_crop_field"
                ),
                MinecraftPlannerInputFactory
                        .immediateCropMaintenanceHandoffSkills(
                                skills.modelArgumentValidators(),
                                goal,
                                semantic
                        ).keySet()
        );

        final var input = new MinecraftPlannerInputFactory(skills, "guide")
                .create(
                        "request-crop-maintenance",
                        goal,
                        new BrainObservation(
                                9,
                                new SkillContext(3, 9, 20, false, true, 0.0),
                                semantic,
                                "{}"
                        )
                );
        assertEquals(
                java.util.Set.of(
                        "harvest_and_replant_step",
                        "maintain_observed_crop_field"
                ),
                input.decisionContext().availableSkills().keySet()
        );
        assertTrue(input.systemPrompt().contains(
                "TRUSTED_IMMEDIATE_CROP_MAINTENANCE_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains(
                "Do not use blockId, maxBlocks, clusterRadius"
        ));
        assertTrue(input.systemPrompt().contains(
                "TRUSTED_CURRENT_CROP_TARGETS"
        ));
        assertTrue(input.systemPrompt().contains(
                "maintain_observed_crop_field"
        ));
        assertTrue(input.systemPrompt().contains(
                "\"crop\":\"minecraft:wheat\",\"sampleSequence\":41"
        ));
        assertTrue(input.systemPrompt().contains(
                "\"x\":4,\"y\":64,\"z\":-2,\"face\":\"up\""
        ));
    }

    @Test
    void cropTaskRetainsSurveyCompoundWhenNoMatureFaceIsCurrent() {
        final SkillRegistry skills = new SkillRegistry()
                .register("harvest_and_replant_step", noArgumentSkill())
                .register("maintain_observed_crop_field", noArgumentSkill())
                .register("survey_surroundings", noArgumentSkill())
                .register("move_to", noArgumentSkill());
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                4,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "把三格小麦全部收割并重新种上",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final String semantic = """
                {
                  "sampleSequence": 42,
                  "visibleBlockFaces": [{
                    "block": {"x": 4, "y": 64, "z": -2},
                    "type": "minecraft:farmland",
                    "face": "up"
                  }]
                }
                """;

        final var input = new MinecraftPlannerInputFactory(skills, "guide")
                .create(
                        "request-crop-reacquire",
                        goal,
                        new BrainObservation(
                                9,
                                new SkillContext(3, 9, 20, false, true, 0.0),
                                semantic,
                                "{}"
                        )
                );

        assertEquals(
                java.util.Set.of("maintain_observed_crop_field"),
                input.decisionContext().availableSkills().keySet()
        );
        assertTrue(input.systemPrompt().contains(
                "choose START_SKILL maintain_observed_crop_field"
        ));
    }

    @Test
    void woodTaskWithoutVisibleLogAdmitsContinuousFairWoodCompound() {
        final SkillRegistry skills = new SkillRegistry()
                .register("gather_visible_block_cluster", noArgumentSkill())
                .register("gather_nearby_wood", noArgumentSkill())
                .register("survey_surroundings", noArgumentSkill())
                .register("move_to", noArgumentSkill());
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                3,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "Please chop some wood",
                "",
                java.time.Instant.EPOCH,
                false
        );

        final var input = new MinecraftPlannerInputFactory(skills, "guide")
                .create(
                        "request-wood-no-evidence",
                        goal,
                        new BrainObservation(
                                9,
                                new SkillContext(
                                        3,
                                        9,
                                        20,
                                        false,
                                        true,
                                        0.0
                                ),
                                "{}",
                                """
                                {
                                  "sampleSequence": 52,
                                  "self": {
                                    "dimension": "minecraft:overworld"
                                  },
                                  "visibleBlockFaces": [{
                                    "block": {"x": 4, "y": 64},
                                    "type": "minecraft:stone",
                                    "face": "north"
                                  }]
                                }
                                """
                        )
                );

        assertEquals(
                java.util.Set.of("gather_nearby_wood"),
                input.decisionContext().availableSkills().keySet()
        );
        assertTrue(input.systemPrompt().contains(
                "continuously scans through the player's own eyes"
        ));
    }

    @Test
    void completedWoodCompoundCannotBeStartedAgain() {
        final SkillRegistry skills = new SkillRegistry()
                .register("gather_visible_block_cluster", noArgumentSkill())
                .register("gather_nearby_wood", noArgumentSkill())
                .register("survey_surroundings", noArgumentSkill());
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                3,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "请砍一棵树，捡起木头，然后告诉我实际拿到了什么",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final var input = new MinecraftPlannerInputFactory(skills, "guide")
                .create(
                        "request-wood-complete",
                        goal,
                        new BrainObservation(
                                9,
                                new SkillContext(
                                        3,
                                        9,
                                        20,
                                        false,
                                        true,
                                        0.0
                                ),
                                """
                                {
                                  "self": {
                                    "inventory": [{
                                      "itemId": "minecraft:spruce_log",
                                      "count": 3
                                    }]
                                  }
                                }
                                """,
                                """
                                {
                                  "skillState": "TERMINAL",
                                  "skillName": "gather_nearby_wood",
                                  "terminalStatus": "COMPLETED"
                                }
                                """
                        )
                );

        assertTrue(input.decisionContext().availableSkills().isEmpty());
        assertTrue(input.systemPrompt().contains(
                "TRUSTED_COMPLETED_WOOD_GATHERING_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains("choose COMPLETE_GOAL"));
    }

    @Test
    void handedGoldenAppleStagesVisiblePickupBeforeVerifiedConsumption() {
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                3,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "我已经把金苹果丢给你了，快吃吧",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final SkillRegistry skills = new SkillRegistry()
                .register("collect_observed_item", noArgumentSkill())
                .register("consume_owned_food", noArgumentSkill());
        final var dropped = new MinecraftPlannerInputFactory(skills, "guide")
                .create(
                        "request-golden-apple-drop",
                        goal,
                        new BrainObservation(
                                9,
                                new SkillContext(
                                        3,
                                        9,
                                        20,
                                        false,
                                        true,
                                        0.0
                                ),
                                """
                                {
                                  "sampleSequence": 44,
                                  "self": {
                                    "dimension": "minecraft:overworld",
                                    "inventory": []
                                  },
                                  "visibleEntities": [{
                                    "observationId": "visible-2",
                                    "type": "minecraft:item",
                                    "properties": {
                                      "itemId": "minecraft:golden_apple"
                                    }
                                  }]
                                }
                                """,
                                "{}"
                        )
                );

        assertTrue(dropped.systemPrompt().contains(
                "TRUSTED_IMMEDIATE_FOOD_CONSUMPTION_PLAYBOOK"
        ));
        assertTrue(dropped.systemPrompt().contains(
                "START_SKILL collect_observed_item now"
        ));
        assertTrue(dropped.systemPrompt().contains("visible-2"));
        assertTrue(dropped.systemPrompt().contains(
                "Do not say it is in the inventory"
        ));
        assertFalse(dropped.systemPrompt().contains("visibleProperties"));
        assertEquals(
                java.util.Set.of("collect_observed_item"),
                dropped.decisionContext().availableSkills().keySet()
        );

        final var owned = new MinecraftPlannerInputFactory(skills, "guide")
                .create(
                        "request-golden-apple-owned",
                        goal,
                        new BrainObservation(
                                10,
                                new SkillContext(
                                        3,
                                        10,
                                        21,
                                        false,
                                        true,
                                        0.0
                                ),
                                """
                                {
                                  "sampleSequence": 45,
                                  "self": {
                                    "dimension": "minecraft:overworld",
                                    "inventory": [{
                                      "itemId": "minecraft:golden_apple",
                                      "count": 1
                                    }]
                                  },
                                  "visibleEntities": []
                                }
                                """,
                                "{}"
                        )
                );

        assertTrue(owned.systemPrompt().contains(
                "START_SKILL consume_owned_food"
        ));
        assertTrue(owned.systemPrompt().contains(
                "minecraft:golden_apple"
        ));
        assertTrue(owned.systemPrompt().contains(
                "Do not discuss saving it for later"
        ));
        assertEquals(
                java.util.Set.of("consume_owned_food"),
                owned.decisionContext().availableSkills().keySet()
        );
    }

    @Test
    void handedGoldenAppleWithoutCurrentEvidenceRequestsRefreshInsteadOfLying() {
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                3,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "I gave you a golden apple; please eat it.",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final var input = new MinecraftPlannerInputFactory(
                new SkillRegistry().register(
                        "consume_owned_food",
                        noArgumentSkill()
                ),
                "guide"
        ).create(
                "request-golden-apple-refresh",
                goal,
                new BrainObservation(
                        9,
                        new SkillContext(3, 9, 20, false, true, 0.0),
                        "{\"self\":{\"inventory\":[]}}",
                        "{}"
                )
        );

        assertTrue(input.systemPrompt().contains(
                "TRUSTED_IMMEDIATE_FOOD_CONSUMPTION_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains(
                "Request one SEMANTIC_REFRESH now"
        ));
        assertTrue(input.systemPrompt().contains(
                "present, consumed, or reserved"
        ));
        assertTrue(input.decisionContext().availableSkills().isEmpty());
    }

    @Test
    void chestWithdrawalReceivesTwoStageObservedMenuPlaybook() {
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                4,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "请从你面前的箱子里取出3块橡木木板放进背包",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final var input = new MinecraftPlannerInputFactory(
                new SkillRegistry(),
                "guide"
        ).create(
                "request-container",
                goal,
                new BrainObservation(
                        10,
                        new SkillContext(
                                4,
                                10,
                                20,
                                false,
                                true,
                                0.0
                        ),
                        "{}",
                        """
                        {
                          "sampleSequence": 52,
                          "self": {"dimension": "minecraft:overworld"},
                          "visibleBlockFaces": [{
                            "block": {"x": 3, "y": 64, "z": 0},
                            "type": "minecraft:chest",
                            "face": "west"
                          }]
                        }
                        """
                )
        );

        assertTrue(input.systemPrompt().contains(
                "TRUSTED_IMMEDIATE_CONTAINER_WITHDRAWAL_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains(
                "choose START_SKILL use_block now"
        ));
        assertTrue(input.systemPrompt().contains(
                "choose START_SKILL transfer_menu_item"
        ));
        assertTrue(input.systemPrompt().contains(
                "openMenu.containerId"
        ));
        assertTrue(input.systemPrompt().contains(
                "requested exact count"
        ));
        assertFalse(input.systemPrompt().contains(
                "TRUSTED_FOUNDATION_ROUTE_PLAYBOOK"
        ));
    }

    @Test
    void visibleChestWithdrawalSchemaAdmitsOnlyTheCurrentMenuStage() {
        final SkillArgumentValidator accepts =
                arguments -> Optional.empty();
        final Map<String, SkillArgumentValidator> all = Map.of(
                "use_block", accepts,
                "transfer_menu_item", accepts,
                "survey_surroundings", accepts,
                "move_to", accepts
        );
        final GoalSnapshot goal = new GoalSnapshot(
                Optional.empty(),
                8,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "请从你面前的箱子里取出3块橡木木板放进背包",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final String closedChest = """
                {
                  "sampleSequence": 52,
                  "self": {"dimension": "minecraft:overworld"},
                  "visibleBlockFaces": [{
                    "block": {"x": 3, "y": 64, "z": 0},
                    "type": "minecraft:chest",
                    "face": "west"
                  }]
                }
                """;
        assertEquals(
                java.util.Set.of("use_block"),
                MinecraftPlannerInputFactory
                        .immediateContainerWithdrawalHandoffSkills(
                                all,
                                goal,
                                closedChest
                        )
                        .keySet()
        );

        final String openChest = """
                {
                  "sampleSequence": 53,
                  "self": {"dimension": "minecraft:overworld"},
                  "openMenu": {
                    "containerId": 4,
                    "stateId": 7,
                    "slots": [
                      {"location":"MENU", "item":"minecraft:oak_planks", "count":5},
                      {"location":"PLAYER", "item":"minecraft:air", "count":0}
                    ]
                  }
                }
                """;
        assertEquals(
                java.util.Set.of("transfer_menu_item"),
                MinecraftPlannerInputFactory
                        .immediateContainerWithdrawalHandoffSkills(
                                all,
                                goal,
                                openChest
                        )
                        .keySet()
        );
    }

    @Test
    void ownedItemDropSchemaAdmitsOnlyTheFairThrowAction() {
        final SkillArgumentValidator accepts =
                arguments -> Optional.empty();
        final Map<String, SkillArgumentValidator> all = Map.of(
                "drop_item", accepts,
                "move_to", accepts,
                "survey_surroundings", accepts
        );
        final GoalSnapshot goal = new GoalSnapshot(
                Optional.empty(),
                9,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "请立即把背包里的3个橡木原木丢到地上",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final String owned = """
                {
                  "sampleSequence": 81,
                  "self": {
                    "dimension": "minecraft:overworld",
                    "inventory": [
                      {"item":"minecraft:oak_log","count":3}
                    ]
                  }
                }
                """;
        assertEquals(
                java.util.Set.of("drop_item"),
                MinecraftPlannerInputFactory
                        .immediateOwnedItemDropHandoffSkills(
                                all,
                                goal,
                                owned
                        )
                        .keySet()
        );

        final String insufficient = owned.replace("\"count\":3", "\"count\":2");
        assertEquals(
                all.keySet(),
                MinecraftPlannerInputFactory
                        .immediateOwnedItemDropHandoffSkills(
                                all,
                                goal,
                                insufficient
                        )
                        .keySet()
        );
    }

    @Test
    void ownedItemDropReceivesActionFirstPlaybook() {
        final GoalSnapshot goal = new GoalSnapshot(
                Optional.empty(),
                9,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "drop 3 oak logs now",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final var input = new MinecraftPlannerInputFactory(
                new SkillRegistry(),
                "guide"
        ).create(
                "request-drop",
                goal,
                new BrainObservation(
                        12,
                        new SkillContext(9, 12, 20, false, true, 0.0),
                        """
                        {
                          "sampleSequence": 81,
                          "self": {
                            "dimension": "minecraft:overworld",
                            "inventory": [
                              {"item":"minecraft:oak_log","count":3}
                            ]
                          }
                        }
                        """,
                        "{}"
                )
        );
        assertTrue(input.systemPrompt().contains(
                "TRUSTED_IMMEDIATE_OWNED_ITEM_DROP_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains(
                "Choose START_SKILL drop_item immediately"
        ));
    }

    @Test
    void visibleChestWithdrawalRecoveryCopiesOnlyCurrentFairFields() {
        final String goal =
                "请从你面前的箱子里取出3块橡木木板放进背包";
        final String closedChest = """
                {
                  "sampleSequence": 52,
                  "self": {"dimension": "minecraft:overworld"},
                  "visibleBlockFaces": [{
                    "block": {"x": 3, "y": 64, "z": 0},
                    "type": "minecraft:chest",
                    "face": "west"
                  }]
                }
                """;
        final var open = MinecraftPlannerInputFactory
                .immediateContainerWithdrawalHandoffForRecovery(
                        goal,
                        closedChest
                )
                .orElseThrow();
        assertFalse(open.open());
        assertEquals("minecraft:overworld", open.dimension());
        assertEquals(52L, open.sampleSequence());
        assertEquals(3, open.x());
        assertEquals("west", open.face());

        final String openChest = """
                {
                  "sampleSequence": 53,
                  "self": {"dimension": "minecraft:overworld"},
                  "openMenu": {
                    "containerId": 4,
                    "stateId": 7,
                    "slots": [
                      {"slot":0,"location":"MENU","item":"minecraft:oak_planks","count":5},
                      {"slot":9,"location":"PLAYER","item":"minecraft:air","count":0}
                    ]
                  }
                }
                """;
        final var transfer = MinecraftPlannerInputFactory
                .immediateContainerWithdrawalHandoffForRecovery(
                        goal,
                        openChest
                )
                .orElseThrow();
        assertTrue(transfer.open());
        assertEquals(53L, transfer.sampleSequence());
        assertEquals(4, transfer.containerId());
        assertEquals(7, transfer.stateId());
        assertEquals(0, transfer.sourceSlot());
        assertEquals(9, transfer.destinationSlot());
        assertEquals(3, transfer.count());
    }

    @Test
    void ownedItemDropRecoveryRequiresCurrentInventoryEvidence() {
        final String semantic = """
                {
                  "self": {
                    "inventory": [
                      {"itemId":"minecraft:oak_log","count":3}
                    ]
                  }
                }
                """;
        final var handoff = MinecraftPlannerInputFactory
                .immediateOwnedItemDropHandoffForRecovery(
                        "请立即把背包里的3个橡木原木丢到地上",
                        semantic
                )
                .orElseThrow();
        assertEquals("minecraft:oak_log", handoff.itemId());
        assertEquals(3, handoff.count());
        assertTrue(
                MinecraftPlannerInputFactory
                        .immediateOwnedItemDropHandoffForRecovery(
                                "请立即把背包里的4个橡木原木丢到地上",
                                semantic
                        )
                        .isEmpty()
        );
        final String legacy = semantic.replace("itemId", "item");
        assertEquals(
                handoff,
                MinecraftPlannerInputFactory
                        .immediateOwnedItemDropHandoffForRecovery(
                                "请立即把背包里的3个橡木原木丢到地上",
                                legacy
                        )
                        .orElseThrow()
        );
    }

    @Test
    void explicitEndPortalTaskRetiresActivationAfterPhysicalCompletion() {
        final SkillArgumentValidator accepts =
                arguments -> Optional.empty();
        final Map<String, SkillArgumentValidator> all = Map.of(
                "activate_observed_end_portal",
                accepts,
                "find_and_enter_observed_portal",
                accepts,
                "move_to",
                accepts
        );
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                5,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "请激活眼前的末地传送门，把末影之眼放进框架，"
                    + "然后进入传送门前往末地。",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final String withEyes = """
            {
              "self": {
                "dimension": "minecraft:overworld",
                "inventory": [{
                  "itemId": "minecraft:ender_eye",
                  "count": 12
                }]
              },
              "visibleBlockFaces": []
            }
            """;
        assertEquals(
                java.util.Set.of(
                        "activate_observed_end_portal"
                ),
                MinecraftPlannerInputFactory
                    .immediateEndPortalHandoffSkills(
                            all,
                            goal,
                            withEyes,
                            "{}"
                    ).keySet()
        );

        final String completedActivation = """
            {
              "skillState": "COMPLETED",
              "skillName": "activate_observed_end_portal",
              "terminalStatus": "COMPLETED"
            }
            """;
        assertEquals(
                java.util.Set.of(
                        "find_and_enter_observed_portal"
                ),
                MinecraftPlannerInputFactory
                    .immediateEndPortalHandoffSkills(
                            all,
                            goal,
                            """
                            {
                              "self": {
                                "dimension": "minecraft:overworld",
                                "inventory": []
                              },
                              "visibleBlockFaces": []
                            }
                            """,
                            completedActivation
                    ).keySet()
        );

        assertEquals(
                java.util.Set.of(
                        "find_and_enter_observed_portal"
                ),
                MinecraftPlannerInputFactory
                    .immediateEndPortalHandoffSkills(
                            all,
                            goal,
                            """
                            {
                              "self": {
                                "dimension": "minecraft:overworld",
                                "inventory": []
                              },
                              "visibleBlockFaces": [{
                                "type": "minecraft:end_portal"
                              }]
                            }
                            """,
                            "{}"
                    ).keySet()
        );

        assertTrue(
                MinecraftPlannerInputFactory
                    .immediateEndPortalHandoffSkills(
                            all,
                            goal,
                            """
                            {
                              "self": {
                                "dimension": "minecraft:the_end",
                                "inventory": []
                              },
                              "visibleBlockFaces": []
                            }
                            """,
                            completedActivation
                    ).isEmpty()
        );
    }

    @Test
    void externallyTriggeredWaterClutchWaitsForTheRealFall() {
        final GoalSnapshot goal = new GoalSnapshot(
                java.util.Optional.empty(),
                3,
                GoalStatus.RUNNING,
                GoalSource.PLAYER_CHAT,
                "做一次落地水训练；我会把你放到高处，然后让你下落。",
                "",
                java.time.Instant.EPOCH,
                false
        );
        final var input = new MinecraftPlannerInputFactory(
                new SkillRegistry(),
                "guide"
        ).create(
                "request-water-clutch",
                goal,
                new BrainObservation(
                        8,
                        new SkillContext(3, 8, 40, false, true, 0.0),
                        "{}",
                        "{}"
                )
        );

        assertTrue(input.systemPrompt().contains(
                "TRUSTED_EXTERNAL_WATER_CLUTCH_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains(
                "Do not call tower_up"
        ));
        assertTrue(input.systemPrompt().contains(
                "SEMANTIC_REFRESH"
        ));
        assertFalse(input.systemPrompt().contains(
                "TRUSTED_FOUNDATION_ROUTE_PLAYBOOK"
        ));
    }

    @Test
    void explicitCombatGoalReceivesHostileOnlyTargetPlaybook() {
        final var input = new MinecraftPlannerInputFactory(
                new SkillRegistry().register(
                        "engage_observed_entity",
                        noArgumentSkill()
                ),
                "guide"
        ).create(
                "request-combat-playbook",
                new GoalSnapshot(
                        Optional.empty(),
                        4L,
                        GoalStatus.RUNNING,
                        GoalSource.PLAYER_CHAT,
                        "请保护我，击退面前的僵尸和骷髅",
                        "",
                        java.time.Instant.EPOCH,
                        false
                ),
                new BrainObservation(
                        9L,
                        new SkillContext(4L, 9L, 20L, false, true, 0.0),
                        "{}",
                        """
                        {
                          "sampleSequence": 7,
                          "visibleEntities": [
                            {"observationId":"visible-0", "type":"minecraft:item", "hostile":false},
                            {"observationId":"visible-1", "type":"minecraft:zombie", "hostile":true}
                          ]
                        }
                        """
                )
        );

        assertTrue(input.systemPrompt().contains(
                "TRUSTED_IMMEDIATE_COMBAT_PLAYBOOK"
        ));
        assertTrue(input.systemPrompt().contains(
                "Never select minecraft:item"
        ));
        assertTrue(input.systemPrompt().contains(
                "return START_SKILL immediately"
        ));
    }

    private static String completionRuntime(final String objective) {
        final String objectives = objective.isEmpty()
                ? "[]"
                : "[\"" + objective + "\"]";
        return """
            {
              "verifiedCompletionRouteData": {
                "profile": "COMPLETION",
                "nextObjectives": %s
              }
            }
            """.formatted(objectives);
    }

    private static Skill<Unit> noArgumentSkill() {
        return new Skill<>() {
            @Override
            public SkillParameterParser<Unit> parameters() {
                return arguments -> arguments.isEmpty()
                        ? SkillParameterResult.valid(Unit.INSTANCE)
                        : SkillParameterResult.invalid(
                                "unexpected_arguments"
                        );
            }

            @Override
            public Optional<SkillFailure> preconditions(
                    final SkillContext context,
                    final Unit parameters
            ) {
                return Optional.empty();
            }

            @Override
            public void start(
                    final SkillContext context,
                    final Unit parameters
            ) {
            }

            @Override
            public SkillTickResult tick(
                    final SkillContext context,
                    final Unit parameters
            ) {
                return SkillTickResult.completed();
            }

            @Override
            public SkillCheckpoint checkpoint(
                    final SkillContext context,
                    final Unit parameters
            ) {
                return SkillCheckpoint.empty();
            }

            @Override
            public void cancel(
                    final SkillContext context,
                    final Unit parameters
            ) {
            }

            @Override
            public SkillResult result(
                    final SkillContext context,
                    final Unit parameters
            ) {
                return SkillResult.completed();
            }
        };
    }

    private enum Unit {
        INSTANCE
    }
}
