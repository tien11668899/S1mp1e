package dev.s1mp1e.forge.mixin;

import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.client.render.entity.PlayerRenderer;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge 的 RenderPlayerEvent.Pre（可取消）/Post。 */
@Mixin(value = PlayerRenderer.class, priority = 1050)
public abstract class PlayerRendererMixin {
    @Inject(method = "render(Lnet/minecraft/client/entity/living/player/ClientPlayerEntity;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void s1f$pre(ClientPlayerEntity e, double x, double y, double z, float yaw, float pt, CallbackInfo ci) {
        if (MinecraftForge.EVENT_BUS.post(new RenderPlayerEvent.Pre(e, (PlayerRenderer) (Object) this, pt, x, y, z))) ci.cancel();
    }

    @Inject(method = "render(Lnet/minecraft/client/entity/living/player/ClientPlayerEntity;DDDFF)V", at = @At("RETURN"))
    private void s1f$post(ClientPlayerEntity e, double x, double y, double z, float yaw, float pt, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new RenderPlayerEvent.Post(e, (PlayerRenderer) (Object) this, pt, x, y, z));
    }
}
