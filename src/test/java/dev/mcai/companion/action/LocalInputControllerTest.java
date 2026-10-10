package dev.mcai.companion.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LocalInputControllerTest {
    private static final ActionLimits LIMITS =
            new ActionLimits(0.25, 20.0F, 15.0F, 100);

    @Test
    void smoothsMovementAndNeverExceedsUnitMagnitude() {
        LocalInputController controller = new LocalInputController(LIMITS);
        controller.setMovement(new MovementIntent(1.0, 1.0, true, false));

        InputFrame first = controller.nextFrame(0.0F, 0.0F);
        assertEquals(0.25, first.forward());
        assertEquals(0.25, first.strafeLeft());

        for (int tick = 0; tick < 10; tick++) {
            InputFrame frame = controller.nextFrame(0.0F, 0.0F);
            assertTrue(Math.hypot(frame.forward(), frame.strafeLeft()) <= 1.0);
        }
    }

    @Test
    void jumpIsExactlyOneQueuedFrame() {
        LocalInputController controller = new LocalInputController(LIMITS);
        controller.queueJump();

        assertTrue(controller.nextFrame(0.0F, 0.0F).jump());
        assertFalse(controller.nextFrame(0.0F, 0.0F).jump());
    }

    @Test
    void stopClearsAppliedInputAndJumpImmediately() {
        LocalInputController controller = new LocalInputController(LIMITS);
        controller.setMovement(new MovementIntent(1.0, 0.0, true, true));
        controller.queueJump();
        controller.nextFrame(0.0F, 0.0F);
        controller.queueJump();

        controller.stopImmediately();
        InputFrame stopped = controller.nextFrame(0.0F, 0.0F);

        assertEquals(0.0, stopped.forward());
        assertEquals(0.0, stopped.strafeLeft());
        assertFalse(stopped.sprint());
        assertFalse(stopped.sneak());
        assertFalse(stopped.jump());
    }

    @Test
    void limitsTurnRateAndSnapshotsIntent() {
        LocalInputController controller = new LocalInputController(LIMITS);
        controller.setLook(new LookIntent(90.0F, -45.0F));

        InputFrame first = controller.nextFrame(0.0F, 0.0F);
        assertEquals(20.0F, first.yaw());
        assertEquals(-15.0F, first.pitch());
        assertTrue(first.rotationChanged());

        ActionState state = controller.snapshot(Optional.empty());
        assertEquals(1, state.actuatorTicks());
        assertEquals(new LookIntent(90.0F, -45.0F), state.requestedLook().orElseThrow());
    }
}
