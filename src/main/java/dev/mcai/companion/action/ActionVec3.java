package dev.mcai.companion.action;

public record ActionVec3(double x, double y, double z) {
    public ActionVec3 {
        x = ActionValidation.finite(x, "x");
        y = ActionValidation.finite(y, "y");
        z = ActionValidation.finite(z, "z");
    }
}
