package dev.mcai.companion.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LocalInputControllerTest {
    private static final ActionLimits LIMITS =
            new ActionLimits(0.25, 20.0F, 15.0F, 100, 1);

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
        assertEquals(5.0F, first.yaw());
        assertEquals(-5.0F, first.pitch());
        assertTrue(first.rotationChanged());

        ActionState state = controller.snapshot(Optional.empty());
        assertEquals(1, state.actuatorTicks());
        assertEquals(new LookIntent(90.0F, -45.0F), state.requestedLook().orElseThrow());
    }

    @Test
    void turnRateAcceleratesToTheCeilingInsteadOfJumpingToIt() {
        LocalInputController controller = new LocalInputController(LIMITS);
        controller.setLook(new LookIntent(120.0F, 0.0F));

        float yaw = 0.0F;
        float firstStep = 0.0F;
        float steadyStep = 0.0F;
        for (int tick = 0; tick < 4; tick++) {
            InputFrame frame = controller.nextFrame(yaw, 0.0F);
            float step = ActionMath.wrapDegrees(frame.yaw() - yaw);
            assertTrue(step >= 0.0F, "a turn must not back away from its target");
            assertTrue(step <= 20.0F + 1.0E-3F, "step must respect the per-tick ceiling");
            if (tick == 0) {
                firstStep = step;
            }
            steadyStep = step;
            yaw = frame.yaw();
        }

        assertEquals(5.0F, firstStep);
        assertEquals(20.0F, steadyStep);
        assertTrue(firstStep * 2.0F < steadyStep, "first tick must stay below steady state");
    }

    @Test
    void acceleratedTurnStillConvergesOnTheRequestedLook() {
        LocalInputController controller = new LocalInputController(LIMITS);
        controller.setLook(new LookIntent(90.0F, -45.0F));

        InputFrame frame = controller.nextFrame(0.0F, 0.0F);
        for (int tick = 0; tick < 20; tick++) {
            frame = controller.nextFrame(frame.yaw(), frame.pitch());
        }

        assertEquals(90.0F, frame.yaw());
        assertEquals(-45.0F, frame.pitch());
        assertFalse(frame.rotationChanged());
    }

    @Test
    void partiallyAdoptedTurnIsCorrectedOnTheFollowingTick() {
        LocalInputController controller = new LocalInputController(LIMITS);
        controller.setLook(new LookIntent(90.0F, 0.0F));

        float actualYaw = 0.0F;
        for (int tick = 0; tick < 40; tick++) {
            InputFrame frame = controller.nextFrame(actualYaw, 0.0F);
            // Emulate a server that only adopts half of the requested turn.
            actualYaw += ActionMath.wrapDegrees(frame.yaw() - actualYaw) * 0.5F;
        }

        assertTrue(Math.abs(ActionMath.wrapDegrees(90.0F - actualYaw)) < 1.0F);
    }
}
