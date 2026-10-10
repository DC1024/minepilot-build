package dev.mcai.companion.mixin;

import dev.mcai.companion.skills.transport.HeadlessBoatAuthority;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Supplies the one client-only control call inside vanilla's now
 * server-authoritative boat tick.
 */
@Mixin(AbstractBoat.class)
public abstract class HeadlessBoatControlMixin {
    @Unique
    private Vec3 mcaiCompanion$physicsStart;

    @Inject(method = "tick", at = @At("HEAD"))
    private void mcaiCompanion$captureHeadlessBoatStart(
            final CallbackInfo callback
    ) {
        final AbstractBoat boat =
                (AbstractBoat) (Object) this;
        mcaiCompanion$physicsStart =
                HeadlessBoatAuthority.usesServerLocalControl(boat)
                    ? boat.position()
                    : null;
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/vehicle/boat/"
                + "AbstractBoat;move(Lnet/minecraft/world/entity/"
                + "MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            shift = At.Shift.BEFORE
        )
    )
    private void mcaiCompanion$applyHeadlessBoatInput(
            final CallbackInfo callback
    ) {
        final AbstractBoat boat =
                (AbstractBoat) (Object) this;
        if (HeadlessBoatAuthority.usesServerLocalControl(boat)) {
            ((AbstractBoatControlInvoker) (Object) boat)
                .mcaiCompanion$invokeControlBoat();
        }
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/vehicle/boat/"
                + "AbstractBoat;move(Lnet/minecraft/world/entity/"
                + "MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            shift = At.Shift.AFTER
        )
    )
    private void mcaiCompanion$recordHeadlessBoatMovement(
            final CallbackInfo callback
    ) {
        final AbstractBoat boat =
                (AbstractBoat) (Object) this;
        final Vec3 start = mcaiCompanion$physicsStart;
        mcaiCompanion$physicsStart = null;
        if (start != null
                && HeadlessBoatAuthority
                    .usesServerLocalControl(boat)
                && boat.getControllingPassenger()
                    instanceof ServerPlayer controller) {
            final Vec3 moved = boat.position().subtract(start);
            controller.checkMovementStatistics(
                moved.x,
                moved.y,
                moved.z
            );
        }
    }
}
