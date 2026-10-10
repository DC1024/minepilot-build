package dev.mcai.companion.mixin;

import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Version-pinned access to the private local-client boat control stage.
 *
 * <p>No behavior is injected into ordinary boats. The server actuator invokes
 * these methods only for the marked companion's currently controlled boat and
 * only when vanilla reports that the server instance is non-authoritative.</p>
 */
@Mixin(AbstractBoat.class)
public interface AbstractBoatControlInvoker {
    @Invoker("controlBoat")
    void mcaiCompanion$invokeControlBoat();
}
