package dev.mcai.companion.action;

record InputFrame(
        double forward,
        double strafeLeft,
        boolean sprint,
        boolean sneak,
        boolean jump,
        float yaw,
        float pitch,
        boolean rotationChanged
) {
}
