package dev.mcai.companion.skills.core;

import dev.mcai.companion.action.ActionMath;
import dev.mcai.companion.action.LookIntent;
import dev.mcai.companion.perception.PerceptionVec3;

final class CoreSkillGeometry {
    private CoreSkillGeometry() {
    }

    static LookIntent lookAt(PerceptionVec3 eye, PerceptionVec3 target) {
        PerceptionVec3 delta = target.subtract(eye);
        if (delta.lengthSquared() <= 1.0E-12) {
            throw new IllegalArgumentException("Look target coincides with eye");
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x(), delta.z()));
        float pitch = (float) Math.toDegrees(Math.atan2(
                -delta.y(),
                Math.hypot(delta.x(), delta.z())
        ));
        return new LookIntent(yaw, pitch);
    }

    static LookIntent holdLook(PerceptionVec3 direction) {
        return lookAt(new PerceptionVec3(0.0, 0.0, 0.0), direction);
    }

    static double angularErrorDegrees(
            PerceptionVec3 currentDirection,
            PerceptionVec3 targetDirection
    ) {
        if (targetDirection.lengthSquared() <= 1.0E-12) {
            return 0.0;
        }
        double dot = currentDirection.normalized().dot(targetDirection.normalized());
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    static double horizontalAngularErrorDegrees(
            PerceptionVec3 currentDirection,
            PerceptionVec3 targetDirection
    ) {
        double currentLength = Math.hypot(
                currentDirection.x(),
                currentDirection.z()
        );
        double targetLength = Math.hypot(
                targetDirection.x(),
                targetDirection.z()
        );
        if (targetLength <= 1.0E-12) {
            return 0.0;
        }
        if (currentLength <= 1.0E-12) {
            return 180.0;
        }
        double dot = (
                currentDirection.x() * targetDirection.x()
                        + currentDirection.z() * targetDirection.z()
        ) / (currentLength * targetLength);
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    static LookIntent scanTarget(CoreSkillFrame frame, float yawDelta) {
        LookIntent current = holdLook(frame.lookDirection());
        return new LookIntent(
                ActionMath.wrapDegrees(current.yawDegrees() + yawDelta),
                Math.max(-35.0F, Math.min(35.0F, current.pitchDegrees()))
        );
    }

    /**
     * A route that is unknown because support/clearance evidence is missing
     * must inspect the floor, not merely spin on the horizon.
     */
    static LookIntent navigationScanTarget(
            CoreSkillFrame frame,
            PerceptionVec3 target,
            float yawOffset
    ) {
        final double deltaX =
                target.x() - frame.position().x();
        final double deltaZ =
                target.z() - frame.position().z();
        final double horizontalDistance =
                Math.hypot(deltaX, deltaZ);
        final double probeDistance =
                Math.min(0.85, horizontalDistance);
        final double horizontalScale =
                horizontalDistance <= 1.0E-9
                    ? 0.0
                    : probeDistance / horizontalDistance;
        /*
         * Inspect the next support surface, not a fixed angle below the
         * horizon. At one-block range the former 50-degree ray passed above
         * the destination floor, so a perfectly ordinary adjacent return
         * could exhaust every scan while its safe support evidence aged out.
         * The bounded 0.85-block probe refreshes only the immediate fair
         * corridor and therefore does not expose a remote route.
         */
        final PerceptionVec3 supportProbe = new PerceptionVec3(
                frame.position().x() + deltaX * horizontalScale,
                Math.min(frame.position().y(), target.y()) - 0.05,
                frame.position().z() + deltaZ * horizontalScale
        );
        final LookIntent towardSupport = lookAt(
                frame.eyePosition(),
                supportProbe
        );
        return new LookIntent(
                ActionMath.wrapDegrees(
                        towardSupport.yawDegrees() + yawOffset
                ),
                Math.max(
                        50.0F,
                        Math.min(
                                75.0F,
                                towardSupport.pitchDegrees()
                        )
                )
        );
    }
}
