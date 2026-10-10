package dev.mcai.companion.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Prevents a live GameTest from silently disappearing from Forge's data-driven
 * selector. A Java method alone is insufficient on Forge 26.2: the matching
 * test-instance and test-environment resources are part of the public fixture
 * contract.
 */
final class LiveModelHordeFixtureSourceContractTest {
    private static final Path INSTANCE = Path.of(
            "src/main/resources/data/mcai_companion/test_instance/"
                    + "real_player_task_to_live_model_horde_defense.json"
    );
    private static final Path ENVIRONMENT = Path.of(
            "src/main/resources/data/mcai_companion/test_environment/"
                    + "exclusive_live_model_horde_combat.json"
    );
    private static final Path GOLEM_INSTANCE = Path.of(
            "src/main/resources/data/mcai_companion/test_instance/"
                    + "real_player_task_to_live_model_iron_golem_duel.json"
    );
    private static final Path GOLEM_ENVIRONMENT = Path.of(
            "src/main/resources/data/mcai_companion/test_environment/"
                    + "exclusive_live_model_iron_golem_duel.json"
    );
    private static final Path TEN_PLUS_TEN_INSTANCE = Path.of(
            "src/main/resources/data/mcai_companion/test_instance/"
                    + "real_player_task_to_live_model_ten_plus_ten_horde.json"
    );
    private static final Path TEN_PLUS_TEN_ENVIRONMENT = Path.of(
            "src/main/resources/data/mcai_companion/test_environment/"
                    + "exclusive_live_model_ten_plus_ten_horde.json"
    );

    @Test
    void hordeFixtureHasInstanceAndEnvironmentRegistration() throws Exception {
        assertTrue(Files.isRegularFile(INSTANCE));
        assertTrue(Files.isRegularFile(ENVIRONMENT));
        final JsonObject instance = JsonParser.parseString(Files.readString(
                INSTANCE, StandardCharsets.UTF_8
        )).getAsJsonObject();
        assertEquals(
                "mcai_companion:real_player_task_to_live_model_horde_defense",
                instance.get("function").getAsString()
        );
        assertEquals(
                "mcai_companion:exclusive_live_model_horde_combat",
                instance.get("environment").getAsString()
        );
        final JsonObject environment = JsonParser.parseString(
                Files.readString(ENVIRONMENT, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        assertEquals("minecraft:all_of", environment.get("type").getAsString());
    }

    @Test
    void hordeFunctionUsesAnchorAwareFixtureImplementation() throws Exception {
        final String source = Files.readString(
                Path.of(
                        "src/main/java/dev/mcai/companion/communication/"
                                + "LiveModelChatGameTests.java"
                ),
                StandardCharsets.UTF_8
        );
        final int horde = source.indexOf("class LiveHordeCombatScenario");
        assertTrue(horde >= 0, "horde scenario must exist");
        final int end = source.indexOf("enum HordeStage", horde);
        assertTrue(end > horde, "horde scenario must have a bounded stage enum");
        final String scenario = source.substring(horde, end);
        assertTrue(
                scenario.contains("reanchorTargets("),
                "targets must follow the authoritative body after initial anchoring"
        );
        assertTrue(
                scenario.contains("START_SKILL") || scenario.contains("sawCombatSkill"),
                "horde fixture must require a model-selected combat skill"
        );
    }

    @Test
    void golemFixtureHasInstanceAndEnvironmentRegistration() throws Exception {
        assertTrue(Files.isRegularFile(GOLEM_INSTANCE));
        assertTrue(Files.isRegularFile(GOLEM_ENVIRONMENT));
        final JsonObject instance = JsonParser.parseString(Files.readString(
                GOLEM_INSTANCE, StandardCharsets.UTF_8
        )).getAsJsonObject();
        assertEquals(
                "mcai_companion:real_player_task_to_live_model_iron_golem_duel",
                instance.get("function").getAsString()
        );
        assertEquals(
                "mcai_companion:exclusive_live_model_iron_golem_duel",
                instance.get("environment").getAsString()
        );
    }

    @Test
    void tenPlusTenFixtureHasInstanceAndEnvironmentRegistration()
            throws Exception {
        assertTrue(Files.isRegularFile(TEN_PLUS_TEN_INSTANCE));
        assertTrue(Files.isRegularFile(TEN_PLUS_TEN_ENVIRONMENT));
        final JsonObject instance = JsonParser.parseString(Files.readString(
                TEN_PLUS_TEN_INSTANCE, StandardCharsets.UTF_8
        )).getAsJsonObject();
        assertEquals(
                "mcai_companion:real_player_task_to_live_model_ten_plus_ten_horde",
                instance.get("function").getAsString()
        );
        assertEquals(
                "mcai_companion:exclusive_live_model_ten_plus_ten_horde",
                instance.get("environment").getAsString()
        );
        final JsonObject environment = JsonParser.parseString(
                Files.readString(TEN_PLUS_TEN_ENVIRONMENT, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        assertEquals("minecraft:all_of", environment.get("type").getAsString());
    }

    @Test
    void tenPlusTenScenarioKeepsBoundedModelAndTargetEvidence() throws Exception {
        final String source = Files.readString(
                Path.of(
                        "src/main/java/dev/mcai/companion/communication/"
                                + "LiveModelChatGameTests.java"
                ),
                StandardCharsets.UTF_8
        );
        assertTrue(
                source.contains("realPlayerTaskToLiveModelTenPlusTenHorde"),
                "ten-plus-ten live entry point must exist"
        );
        assertTrue(
                source.contains("targetCount=20")
                        || source.contains("\n                        20,"),
                "ten-plus-ten scenario must create twenty targets"
        );
        assertTrue(
                source.contains("minimumDamagedTargets")
                        && source.contains("targetRadius"),
                "horde evidence must stay parameterized and bounded"
        );
    }
}
