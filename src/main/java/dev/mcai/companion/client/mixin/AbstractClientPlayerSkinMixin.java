package dev.mcai.companion.client.mixin;

import dev.mcai.companion.client.ClientAiSkinResolver;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces the marked AI skin before EntityRenderDispatcher chooses a WIDE or
 * SLIM AvatarRenderer from getSkin().model().
 */
@Mixin(AbstractClientPlayer.class)
abstract class AbstractClientPlayerSkinMixin {
    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void mcaiCompanion$resolveMarkedAiSkin(
        final CallbackInfoReturnable<PlayerSkin> callback
    ) {
        final AbstractClientPlayer player =
            (AbstractClientPlayer) (Object) this;
        ClientAiSkinResolver.overrideFor(player.getGameProfile())
            .ifPresent(callback::setReturnValue);
    }
}
