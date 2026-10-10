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
