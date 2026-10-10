package dev.mcai.companion.mixin;

import dev.mcai.companion.skin.AiProfileMarker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents a marked clientless player from integrating movement twice.
 *
 * <p>A normal {@link ServerPlayer} is client-authoritative: the server-side
 * {@code LivingEntity.aiStep()} still advances velocity, while client movement
 * packets supply the authoritative position. The companion has no local
 * client. Its fair actuator therefore performs the one vanilla
 * {@code Player.travel} pass that a local client would have performed. Letting
 * the ordinary effective-AI pass run as well applies gravity and friction a
 * second time without a matching authoritative client position, shortening
 * jumps and changing all movement physics.</p>
 *
 * <p>Only the explicit, versioned companion profile marker activates this
 * override. Other players and every non-player entity retain the unmodified
 * vanilla path.</p>
 */
@Mixin(Player.class)
public abstract class HeadlessPlayerPhysicsMixin {
    @Inject(
        method = "isEffectiveAi",
        at = @At("HEAD"),
        cancellable = true
    )
    private void mcaiCompanion$useSingleFairPhysicsPass(
            final CallbackInfoReturnable<Boolean> callback
    ) {
        final Player player = (Player) (Object) this;
        if (player instanceof ServerPlayer serverPlayer
                && AiProfileMarker.isMarked(
                    serverPlayer.getGameProfile()
                )) {
            callback.setReturnValue(false);
        }
    }
}
