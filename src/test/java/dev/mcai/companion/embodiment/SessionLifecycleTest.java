package dev.mcai.companion.embodiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SessionLifecycleTest {
    @Test
    void followsHappyPath() {
        SessionLifecycle lifecycle = new SessionLifecycle();

        lifecycle.beginSpawn();
        lifecycle.activate();
        lifecycle.beginStop();
        lifecycle.stopped();

        assertEquals(SessionState.ABSENT, lifecycle.state());
        assertEquals("", lifecycle.failureCode());
    }

    @Test
    void rejectsInvalidTransitions() {
        SessionLifecycle lifecycle = new SessionLifecycle();

        assertThrows(IllegalStateException.class, lifecycle::activate);
        assertThrows(IllegalStateException.class, lifecycle::beginStop);
        assertThrows(IllegalStateException.class, () -> lifecycle.fail("unexpected"));
    }

    @Test
    void failedSessionCanBeExplicitlyRemoved() {
        SessionLifecycle lifecycle = new SessionLifecycle();

        lifecycle.beginSpawn();
        lifecycle.fail("spawn_failed");
        lifecycle.beginStop();
        lifecycle.stopped();

        assertEquals(SessionState.ABSENT, lifecycle.state());
    }

    @Test
    void failedSessionCanBeReplacedByAFreshBody() {
        SessionLifecycle lifecycle = new SessionLifecycle();

        lifecycle.beginSpawn();
        lifecycle.activate();
        lifecycle.fail("connection_closed");

        assertEquals(SessionState.FAILED, lifecycle.state());
        assertEquals("connection_closed", lifecycle.failureCode());

        /*
         * AiPlayerManager retires a failed session before it admits a
         * replacement, so FAILED -> STOPPING -> ABSENT -> PREPARING -> ACTIVE
         * has to be legal end to end.  beginStop() accepts FAILED explicitly
         * for this path; without that edge an unattended world could never
         * regain its body after a dropped connection.
         */
        lifecycle.beginStop();
        lifecycle.stopped();

        assertEquals(SessionState.ABSENT, lifecycle.state());
        assertEquals("", lifecycle.failureCode());

        lifecycle.beginSpawn();
        lifecycle.activate();

        assertEquals(SessionState.ACTIVE, lifecycle.state());
        assertEquals("", lifecycle.failureCode());
    }

    @Test
    void hardcoreDeathRemainsTerminalUntilExplicitRemoval() {
        SessionLifecycle lifecycle = new SessionLifecycle();

        lifecycle.beginSpawn();
        lifecycle.activate();
        lifecycle.fail("hardcore_death");

        assertEquals(SessionState.FAILED, lifecycle.state());
        assertEquals("hardcore_death", lifecycle.failureCode());
        assertThrows(
                IllegalStateException.class,
                lifecycle::beginSpawn
        );

        lifecycle.beginStop();
        lifecycle.stopped();
        assertEquals(SessionState.ABSENT, lifecycle.state());
    }

    @Test
    void sanitizesFailureCodeBeforeStatusExposure() {
        assertEquals(
                "bad__users_secret_api_key",
                SessionLifecycle.sanitizeFailureCode("BAD /Users/secret API key")
        );
        assertEquals("unknown", SessionLifecycle.sanitizeFailureCode(""));
    }
}
