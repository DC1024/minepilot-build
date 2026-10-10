package dev.mcai.companion.mixin;

import dev.mcai.companion.skills.transport.HeadlessBoatAuthority;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps vanilla boat physics on the server for exactly one marked headless
 * controlling player.
 */
@Mixin(Entity.class)
public abstract class HeadlessBoatAuthorityMixin {
    @Inject(
        method = "isLocalInstanceAuthoritative",
        at = @At("HEAD"),
        cancellable = true
    )
    private void mcaiCompanion$useServerLocalBoatPhysics(
            final CallbackInfoReturnable<Boolean> callback
    ) {
        final Entity entity = (Entity) (Object) this;
        if (entity instanceof AbstractBoat boat
                && HeadlessBoatAuthority
                    .usesServerLocalControl(boat)) {
            callback.setReturnValue(true);
        }
    }
}
