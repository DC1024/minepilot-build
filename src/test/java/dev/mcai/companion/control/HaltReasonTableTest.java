package dev.mcai.companion.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Holds the two stop tables to the claim that makes them worth having:
 * <em>there is nowhere left to hide a stop</em>.
 *
 * <p>Without this test the table is documentation. A new failure path would
 * compile, run, and leave a pause in the companion's behaviour that no table
 * member describes. The scans below read the production sources and fail on
 * any stop the table does not own.</p>
 *
 * <h2>Stop codes reach the goal by three different roads</h2>
 *
 * <p>The first version of this test only recognised one of them, which made
 * it pass while most stops went unchecked. The roads are:</p>
 *
 * <ul>
 *   <li><b>A — literal at the call site.</b> A code written directly beside
 *       the terminal status, as {@code GoalCoordinator.markTerminal(
 *       GoalStatus.FAILED, "body_absent")} or its {@code BrainOrchestrator
 *       .terminal} equivalent.</li>
 *   <li><b>B — literal through a forwarding helper.</b> {@code
 *       ModelBootstrapCoordinator.failRunningGoal} and {@code
 *       EvaluationVictoryTracker.contaminateEvaluation} accept a code and hand
 *       it to {@code markTerminal} themselves. The callers pass the literal,
 *       so the call sites must be scanned too — the helper's own body contains
 *       only a variable.</li>
 *   <li><b>C — enum constant.</b> A {@code HaltReason.X} reference. The
 *       compiler enforces that the constant exists, but not that anything
 *       produces it, so this road is counted separately.</li>
 * </ul>
 *
 * <p>Two further vocabularies deliberately stay out of the table and are
 * scanned only so the boundary is asserted rather than assumed: {@code
 * SessionLifecycle.fail} (session transport state) and {@code announce}
 * notices (operator messages). Their codes describe facts about the session
 * or the log, not about a goal reaching a stop.</p>
 *
 * <p>Test fixtures under {@code *GameTests.java} are excluded on purpose:
 * they mark a goal terminal to tear down a scenario, which is test
 * bookkeeping rather than a gameplay outcome a player can observe. Including
 * them would force the table to absorb names like {@code slime_gate_cleanup}
 * that describe nothing about the companion.</p>
 */
final class HaltReasonTableTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java");

    /** Terminal statuses; a live goal is not a stop and has no reason. */
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "COMPLETED",
            "FAILED",
            "SAFE_IDLE"
    );

    /**
     * Helpers that accept a stop code and forward it to {@code markTerminal}
     * themselves.
     *
     * <p>Adding a new one means adding it here: the scan can only see literals
     * that sit at a call site, so a helper left off this list becomes a road
     * with no checkpoint. {@link #theForwardingHelperListMatchesTheSource()}
     * fails if a name here stops existing, so a rename cannot silently empty
     * the list.</p>
     */
    private static final List<String> CODE_FORWARDING_HELPERS = List.of(
            "failRunningGoal",
            "contaminateEvaluation",
            "markRunningGoalSafeIdle"
    );

    private static final Pattern TERMINATION_CALL = Pattern.compile(
            "\\b(?:mark)?[Tt]erminal\\s*\\("
    );

    /** Road A, form one: a code immediately after a terminal status. */
    private static final Pattern LITERAL_CODE = Pattern.compile(
            "GoalStatus\\.([A-Z_]+)\\s*,\\s*\"([^\"]*)\"",
            Pattern.DOTALL
    );

    /** Road A, form two: the enum constant passed instead of a string. */
    private static final Pattern ENUM_REFERENCE = Pattern.compile(
            "HaltReason\\.([A-Z][A-Z0-9_]*)"
    );

    /**
     * Road B: a wire-shaped literal inside a forwarding helper's call.
     *
     * <p>Requiring an underscore keeps operator prose and single-word tokens
     * out; every stop code in this project contains one.</p>
     */
    private static final Pattern FORWARDED_CODE = Pattern.compile(
            "\"([a-z][a-z0-9_.-]*_[a-z0-9_.-]*)\""
    );

    /** The session vocabulary, kept out of the goal table on purpose. */
    private static final Pattern SESSION_FAILURE = Pattern.compile(
            "lifecycle\\.fail\\(\"([^\"]*)\"\\)"
    );

    @Test
    void everyProductionTerminationCodeIsRegisteredInTheTable()
            throws Exception {
        final Stops stops = scanProductionStops();
        final List<String> unregistered = new ArrayList<>();
        for (String code : stops.reachingTheGoal()) {
            if (HaltReason.resolve(code).isEmpty()) {
                unregistered.add(code);
            }
        }
        assertEquals(
                List.of(),
                unregistered,
                "Every stop code that can reach GoalCoordinator.markTerminal "
                        + "must be a HaltReason member. Unregistered: "
                        + unregistered
                        + ". Add the constant (with its four columns) rather "
                        + "than an unlisted string."
        );
    }

    @Test
    void everyRegisteredCodeIsActuallyProducedSomewhere() {
        /*
         * The other direction, and the reason this test earns its keep: a
         * table constant that nothing produces is an invented fact. Its four
         * columns then describe a stop that cannot happen, and the next reader
         * has no way to tell that from a real one.
         */
        final Stops stops;
        try {
            stops = scanProductionStops();
        } catch (IOException scanFailure) {
            throw new AssertionError(scanFailure);
        }
        final List<String> invented = new ArrayList<>();
        for (HaltReason reason : HaltReason.values()) {
            final boolean referencedByEnum = stops.enumConstants()
                    .contains(reason.name());
            final boolean reachingTheGoal = stops.reachingTheGoal()
                    .contains(reason.code());
            final boolean sessionVocabulary = stops.sessionCodes()
                    .contains(reason.code());
            if (!referencedByEnum && !reachingTheGoal && !sessionVocabulary) {
                invented.add(reason.name() + " (" + reason.code() + ")");
            }
        }
        assertEquals(
                List.of(),
                invented,
                "Every table entry must be produced by production source, "
                        + "either as a HaltReason reference, as a code that "
                        + "reaches markTerminal, or as a session failure code. "
                        + "Unproduced: " + invented
        );
    }

    @Test
    void theScanActuallyFindsTheKnownTerminationCodes() throws Exception {
        /*
         * A scan that silently matches nothing would make the contract above
         * vacuously true. Pin a sample of each road so a broken pattern fails
         * loudly instead of passing. Road A is pinned through both forms
         * because the enum refactor moved almost every code from form one to
         * form two without changing behaviour.
         */
        final Stops stops = scanProductionStops();
        for (String expected : List.of(
                "body_absent",
                "embodiment_failed",
                "runtime_tick_failure",
                "foundation_route_verified",
                "dragon_killed_and_returned"
        )) {
            assertTrue(
                    stops.literalCodes().contains(expected),
                    "Road A stopped finding " + expected
                            + "; the contract test is no longer meaningful"
            );
        }
        for (String expected : List.of(
                "SERVER_VERIFIED_COMPLETE",
                "GOAL_CANCELLED",
                "MODEL_SAFE_IDLE",
                "SKILL_CANCEL_FAILED",
                "REPEATED_SKILL_FAILURE_WITHOUT_PROGRESS"
        )) {
            assertTrue(
                    stops.enumConstants().contains(expected),
                    "Road C stopped finding " + expected
                            + "; the contract test is no longer meaningful"
            );
        }
        for (String expected : List.of(
                "model_bootstrap_invalid_configuration",
                "evaluation_observer_or_body_state_changed",
                "evaluation_command_attempted",
                "evaluation_model_restore_"
        )) {
            assertTrue(
                    stops.forwardedCodes().contains(expected),
                    "Road B stopped finding " + expected
                            + "; the forwarding-helper scan is no longer "
                            + "meaningful"
            );
        }
        for (String expected : List.of(
                "hardcore_death",
                "spawn_failed",
                "session_tick_failed",
                "connection_closed"
        )) {
            assertTrue(
                    stops.sessionCodes().contains(expected),
                    "The session-vocabulary scan stopped finding " + expected
            );
        }
    }

    @Test
    void theForwardingHelperListMatchesTheSource() throws Exception {
        /*
         * Road B is the one road a new code can travel invisibly, because the
         * literal lives at the caller rather than beside the status. The list
         * above is therefore load-bearing: if a helper is renamed the list
         * would keep a stale name and quietly stop scanning anything, so each
         * entry must still exist as a method declaration.
         */
        final StringBuilder sources = new StringBuilder();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path source : files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isTestFixture(path))
                    .toList()) {
                sources.append(
                        Files.readString(source, StandardCharsets.UTF_8)
                ).append('\n');
            }
        }
        for (String helper : CODE_FORWARDING_HELPERS) {
            assertTrue(
                    sources.indexOf(helper + "(") >= 0,
                    helper + " is listed as a code-forwarding helper but no "
                            + "such method exists in production source. "
                            + "Update CODE_FORWARDING_HELPERS so the "
                            + "forwarded codes stay scanned."
            );
        }
    }

    @Test
    void goalHaltsAndSessionFailuresAreDifferentVocabularies() {
        /*
         * A hardcore death is recorded by the embodiment as a session
         * failure, and the runtime maps the resulting FAILED session to the
         * goal-level code embodiment_failed. The table owns the goal-level
         * name; the session keeps its own. This overlap is asserted rather
         * than left to chance, because the two codes read alike and a future
         * reader could reasonably assume they are the same code.
         */
        assertTrue(
                HaltReason.resolve("hardcore_death").isPresent(),
                "The session's hardcore-death code is part of the stop "
                        + "vocabulary"
        );
        assertEquals(
                Hold.DEAD,
                HaltReason.HARDCORE_DEATH.hold(),
                "A hardcore death leaves a body nothing but a respawn clears"
        );
        /*
         * The remaining session codes are transport facts: no goal is
         * halted by them, and none of them may be mistaken for a stop reason.
         */
        for (String sessionOnly : List.of(
                "connection_closed",
                "hardcore_permanent_death",
                "session_tick_failed",
                "spawn_failed",
                "spawn_initialization_failed"
        )) {
            assertFalse(
                    HaltReason.exactCodes().contains(sessionOnly),
                    sessionOnly + " is a session transport fact and must not "
                            + "be registered as a goal stop"
            );
        }
    }

    @Test
    void everyRegisteredCodeIsUniqueAndResolvable() {
        final Set<String> seen = new LinkedHashSet<>();
        for (HaltReason reason : HaltReason.values()) {
            assertTrue(
                    seen.add(reason.code()),
                    "Duplicate halt code in the table: " + reason.code()
            );
            assertEquals(
                    Optional.of(reason),
                    HaltReason.resolve(reason.code()),
                    "A registered code must resolve back to its own constant"
            );
        }
        assertEquals(
                HaltReason.values().length,
                seen.size(),
                "Every constant must own a distinct code"
        );
    }

    @Test
    void registeredCodesStayInTheirWireSpelling() {
        /*
         * Codes are persisted in GoalSnapshot.detailCode and emitted in
         * notices, so the table must not quietly rename one. The pattern
         * mirrors GoalCoordinator.sanitizeCode: lowercase, digits, '_', '-'
         * and '.'. A family prefix is allowed to end with '_'.
         */
        final Pattern wire = Pattern.compile("[a-z0-9]+[a-z0-9_.-]*");
        for (HaltReason reason : HaltReason.values()) {
            assertTrue(
                    wire.matcher(reason.code()).matches(),
                    "Code is not wire-safe: " + reason.code()
            );
        }
    }

    @Test
    void theDynamicEvaluationRestoreFamilyResolvesByPrefix() {
        /*
         * The frozen-restore path appends a bounded sub-code known only at
         * runtime. The table owns the namespace by prefix rather than by
         * enumerating every spelling.
         */
        final HaltReason family = HaltReason.EVALUATION_MODEL_RESTORE;
        assertTrue(family.isFamily());
        assertEquals(
                Optional.of(family),
                HaltReason.resolve("evaluation_model_restore_gateway_error")
        );
        assertEquals(
                Optional.of(family),
                HaltReason.resolve("evaluation_model_restore_")
        );
        assertFalse(
                HaltReason.exactCodes().contains(family.code()),
                "A family prefix is not a complete code"
        );
    }

    @Test
    void anUnknownCodeDoesNotResolveToSomethingElse() {
        assertEquals(
                Optional.empty(),
                HaltReason.resolve("a_code_that_was_never_registered")
        );
        assertEquals(
                Optional.empty(),
                HaltReason.resolve("")
        );
        assertEquals(
                Optional.empty(),
                HaltReason.resolve(null)
        );
    }

    @Test
    void everyHoldIsSelfConsistent() {
        for (Hold hold : Hold.values()) {
            assertFalse(
                    hold.selfClearing() && hold.curableByOwner(),
                    "A hold cannot be both self-clearing and owner-curable: "
                            + hold
            );
        }
        /*
         * EXTERNAL is the one hold the autonomy driver is allowed to leave
         * on its own. If that ever changes, autonomy silently stops
         * proposing objectives, which is the exact field failure the
         * AutonomyDriver doc comment describes.
         */
        assertTrue(Hold.EXTERNAL.selfClearing());
        assertFalse(Hold.EXTERNAL.curableByOwner());
        assertFalse(Hold.DEAD.selfClearing());
        assertFalse(
                Hold.DEAD.curableByOwner(),
                "No message may clear an absent body"
        );
    }

    @Test
    void holdOfRejectsALiveGoalRatherThanGuessing() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Hold.of(GoalStatus.RUNNING)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> Hold.of(GoalStatus.CANCEL_PENDING)
        );
        assertFalse(Hold.isHold(GoalStatus.RUNNING));
        assertFalse(Hold.isHold(GoalStatus.CANCEL_PENDING));
        assertTrue(Hold.isHold(GoalStatus.IDLE));
        assertTrue(Hold.isHold(GoalStatus.COMPLETED));
        assertTrue(Hold.isHold(GoalStatus.FAILED));
        assertTrue(Hold.isHold(GoalStatus.SAFE_IDLE));
    }

    @Test
    void statusColumnAgreesWithTheHoldColumn() {
        /*
         * The four columns must tell one coherent story: a stop that ends
         * the long-term goal cannot report a still-running status, and a
         * stop that does not end the goal must not be marked terminal.
         */
        for (HaltReason reason : HaltReason.values()) {
            if (reason.endsLongTermGoal()) {
                assertTrue(
                        TERMINAL_STATUSES.contains(reason.status().name()),
                        reason + " ends the goal but reports a live status"
                );
            }
            /*
             * A hold that is only reachable through a specific reason must
             * be a real hold: RUNNING/CANCEL_PENDING are live, not held.
             */
            assertTrue(
                    Hold.isHold(reason.status()),
                    reason + " reports a live status but is a stop reason"
            );
        }
    }

    @Test
    void anAbsentBodyIsHeldDeadEvenThoughItsStatusIsSafeIdle() {
        /*
         * This is the exact collapse the two tables exist to prevent:
         * BODY_ABSENT and GOAL_CANCELLED share the SAFE_IDLE status, but one
         * needs a respawn and the other only needs the owner to speak. A
         * caller that looked at the status alone would treat them as the
         * same event.
         */
        assertEquals(
                GoalStatus.SAFE_IDLE,
                HaltReason.BODY_ABSENT.status()
        );
        assertEquals(
                GoalStatus.SAFE_IDLE,
                HaltReason.GOAL_CANCELLED.status()
        );
        assertEquals(Hold.DEAD, HaltReason.BODY_ABSENT.hold());
        assertEquals(Hold.OWNER_STOP, HaltReason.GOAL_CANCELLED.hold());
        assertFalse(HaltReason.BODY_ABSENT.hold().curableByOwner());
        assertTrue(HaltReason.GOAL_CANCELLED.hold().curableByOwner());
    }

    /** Every stop code observed in production source, by the road it takes. */
    private record Stops(
            Set<String> literalCodes,
            Set<String> forwardedCodes,
            Set<String> enumConstants,
            Set<String> sessionCodes
    ) {
        /** Codes that can reach {@code markTerminal} as a value. */
        List<String> reachingTheGoal() {
            final Set<String> all = new LinkedHashSet<>(literalCodes);
            all.addAll(forwardedCodes);
            return List.copyOf(all);
        }
    }

    /**
     * Reads every production Java source and collects the stop codes it can
     * produce, keeping the roads apart so {@link
     * #everyRegisteredCodeIsActuallyProducedSomewhere()} can distinguish a
     * literal that reaches the goal from a mere enum reference.
     *
     * <p>Invocation bodies are delimited by bracket matching rather than a
     * regular expression so a call spread over four lines is still read as
     * one unit, and so a nested call in an argument does not cut the body
     * short.</p>
     */
    private static Stops scanProductionStops() throws IOException {
        final Set<String> literal = new LinkedHashSet<>();
        final Set<String> forwarded = new LinkedHashSet<>();
        final Set<String> enums = new LinkedHashSet<>();
        final Set<String> session = new LinkedHashSet<>();

        final Pattern forwardingCall = Pattern.compile(
                "\\b(?:" + String.join(
                        "|",
                        CODE_FORWARDING_HELPERS
                ) + ")\\s*\\("
        );

        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            for (Path source : sources
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isTestFixture(path))
                    .toList()) {
                final String text = Files.readString(
                        source,
                        StandardCharsets.UTF_8
                );

                final Matcher calls = TERMINATION_CALL.matcher(text);
                while (calls.find()) {
                    final String body = invocationBody(text, calls.end() - 1);
                    final Matcher pairs = LITERAL_CODE.matcher(body);
                    while (pairs.find()) {
                        if (TERMINAL_STATUSES.contains(pairs.group(1))) {
                            literal.add(
                                    pairs.group(2).toLowerCase(Locale.ROOT)
                            );
                        }
                    }
                    final Matcher constants = ENUM_REFERENCE.matcher(body);
                    while (constants.find()) {
                        enums.add(constants.group(1));
                    }
                }

                final Matcher helpers = forwardingCall.matcher(text);
                while (helpers.find()) {
                    final String body =
                            invocationBody(text, helpers.end() - 1);
                    final Matcher codes = FORWARDED_CODE.matcher(body);
                    while (codes.find()) {
                        forwarded.add(codes.group(1));
                    }
                }

                final Matcher failures = SESSION_FAILURE.matcher(text);
                while (failures.find()) {
                    session.add(failures.group(1));
                }
            }
        }
        return new Stops(literal, forwarded, enums, session);
    }

    /**
     * Returns the balanced {@code (...)} span starting at {@code open},
     * bounded so a malformed file cannot cause an unbounded scan.
     */
    private static String invocationBody(
            final String source,
            final int open
    ) {
        final int limit = Math.min(source.length(), open + 800);
        int depth = 0;
        for (int index = open; index < limit; index++) {
            final char character = source.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, index + 1);
                }
            }
        }
        return source.substring(open, limit);
    }

    private static boolean isTestFixture(final Path path) {
        final String name = path.getFileName().toString();
        return name.endsWith("GameTests.java")
                || name.startsWith("GameTest")
                || path.toString().replace('\\', '/').contains("/gametest/");
    }
}
