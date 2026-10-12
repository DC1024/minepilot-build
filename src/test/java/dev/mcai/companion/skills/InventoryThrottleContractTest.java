package dev.mcai.companion.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards the container-operation budget against being quietly undone.
 *
 * <p>Both container actuators are never exercised by the JUnit suite at all:
 * their GameTest fixtures live under {@code src/main/java} behind Forge's
 * {@code @GameTest}, and {@code ./gradlew test} runs only {@code src/test} on
 * the JUnit platform. A behavioural change here therefore ships with no test
 * watching it. These assertions read the production sources directly so the
 * ordering and vocabulary the budget depends on are at least pinned.</p>
 */
final class InventoryThrottleContractTest {
    private static final Path INVENTORY = Path.of(
            "src/main/java/dev/mcai/companion/skills/inventory"
    );
    private static final Path MENU = Path.of(
            "src/main/java/dev/mcai/companion/skills/menu"
    );
    private static final String GATE = "throttledOperation(player)";

    @Test
    void everyMutatingInventoryOperationConsultsTheBudget() throws IOException {
        final String source = read(INVENTORY, "ServerInventorySkillActuator.java");

        assertEquals(
                3,
                occurrences(source, GATE).size(),
                "equip, drop and craftOnce must each gate before mutating"
        );
        assertEquals(
                2,
                occurrences(source, "useInventoryMenu(player)").size(),
                "only equip and drop may close a foreign menu, which is why a "
                        + "throttled call cannot close one as a side effect"
        );
        assertTrue(
                eachOccurrenceIsPrecededByTheGate(
                        source,
                        "useInventoryMenu(player)",
                        GATE
                ),
                "every menu-closing site must sit behind the budget check, or "
                        + "a refused operation could still close a menu"
        );
    }

    @Test
    void everyMutatingMenuOperationConsultsTheBudget() throws IOException {
        final String source = read(MENU, "ServerMenuSkillActuator.java");

        assertEquals(
                3,
                occurrences(source, GATE).size(),
                "transfer, quickMove and selectOption must each gate"
        );
    }

    @Test
    void theGateIsCheckedBeforeTheFirstClick() throws IOException {
        assertGateBeforeFirstClick(
                read(INVENTORY, "ServerInventorySkillActuator.java")
        );
        assertGateBeforeFirstClick(
                read(MENU, "ServerMenuSkillActuator.java")
        );
    }

    @Test
    void theTwoResultTypesShareOneThrottledCode() throws IOException {
        final String inventory = read(
                INVENTORY,
                "InventoryOperationResult.java"
        );
        final String menu = read(MENU, "MenuOperationResult.java");

        assertTrue(
                inventory.contains(
                        "THROTTLED_CODE = \"inventory.operation_throttled\""
                )
        );
        assertTrue(
                menu.contains(
                        "THROTTLED_CODE = \"inventory.operation_throttled\""
                )
        );
        assertTrue(
                inventory.contains(
                        "public static InventoryOperationResult throttled()"
                )
        );
        assertTrue(
                menu.contains("public static MenuOperationResult throttled()")
        );
    }

    @Test
    void closingAMenuStaysExemptAndSaysWhy() throws IOException {
        final String source = read(MENU, "ServerMenuSkillActuator.java");
        final int closeStart = source.indexOf(
                "public MenuOperationResult close("
        );
        final int nextMethod = source.indexOf(
                "public MenuOperationResult checkBinding("
        );
        assertTrue(closeStart > 0 && nextMethod > closeStart);

        final String closeBody = source.substring(closeStart, nextMethod);
        assertFalse(
                closeBody.contains("throttledOperation"),
                "close() must stay exempt, or a menu could be stranded open "
                        + "after a throttled operation"
        );
        assertTrue(
                closeBody.contains("resetLastActionTime"),
                "close() must still reset the vanilla action clock"
        );
        assertTrue(
                source.contains("Closing is deliberately exempt"),
                "the exemption must stay documented where it is made"
        );
    }

    @Test
    void aThrottledEquipIsNotReportedAsAnInvalidPlayerState()
            throws IOException {
        final String source = read(
                Path.of("src/main/java/dev/mcai/companion/skills/core"),
                "InventoryEmergencyEquipmentActuator.java"
        );

        assertTrue(
                source.contains(
                        "if (InventoryOperationResult.THROTTLED_CODE.equals(code)) {\n"
                                + "            return ActionOutcome.RATE_LIMITED;\n"
                                + "        }"
                ),
                "a spent budget must map to RATE_LIMITED, not the "
                        + "INVALID_PLAYER_STATE fallback callers read as "
                        + "'give up'"
        );
    }

    private static void assertGateBeforeFirstClick(final String source) {
        final List<Integer> gates = occurrences(source, GATE);
        final List<Integer> clicks = occurrences(source, "menu.clicked(");

        assertFalse(gates.isEmpty(), "the gate must be present");
        assertFalse(clicks.isEmpty(), "the actuator must still click");
        assertTrue(
                gates.get(0) < clicks.get(0),
                "the first gate must precede the first click, so a refused "
                        + "call leaves the cursor and every slot untouched"
        );
    }

    private static boolean eachOccurrenceIsPrecededByTheGate(
            final String source,
            final String gatedCall,
            final String gateCall
    ) {
        final List<Integer> gates = occurrences(source, gateCall);
        for (final int call : occurrences(source, gatedCall)) {
            boolean preceded = false;
            for (final int gate : gates) {
                if (gate < call) {
                    preceded = true;
                    break;
                }
            }
            if (!preceded) {
                return false;
            }
        }
        return true;
    }

    private static List<Integer> occurrences(
            final String source,
            final String needle
    ) {
        final List<Integer> found = new ArrayList<>();
        int from = 0;
        while (true) {
            final int at = source.indexOf(needle, from);
            if (at < 0) {
                return found;
            }
            found.add(at);
            from = at + needle.length();
        }
    }

    private static String read(final Path directory, final String fileName)
            throws IOException {
        final List<Path> paths;
        try (var stream = Files.list(directory)) {
            paths = stream.sorted().collect(Collectors.toList());
        }
        final Path target = paths.stream()
                .filter(path -> path.getFileName().toString().equals(fileName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "missing source: " + fileName
                ));
        return Files.readString(target);
    }
}
