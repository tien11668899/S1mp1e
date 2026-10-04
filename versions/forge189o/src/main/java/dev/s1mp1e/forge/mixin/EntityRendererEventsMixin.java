package dev.s1mp1e.forge.mixin;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.living.LivingEntity;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge 的 RenderLivingEvent.Pre/Post（可取消）與名牌的 RenderLivingEvent.Specials.Pre/Post。 */
@Mixin(value = LivingEntityRenderer.class, priority = 1050)
public abstract class EntityRendererEventsMixin {
    @Inject(method = "render(Lnet/minecraft/entity/living/LivingEntity;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void s1f$pre(LivingEntity e, double x, double y, double z, float yaw, float pt, CallbackInfo ci) {
        if (MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Pre(e, (LivingEntityRenderer) (Object) this, x, y, z))) ci.cancel();
    }

    @Inject(method = "render(Lnet/minecraft/entity/living/LivingEntity;DDDFF)V", at = @At("RETURN"))
    private void s1f$post(LivingEntity e, double x, double y, double z, float yaw, float pt, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Post(e, (LivingEntityRenderer) (Object) this, x, y, z));
    }

    @Inject(method = "renderNameTag(Lnet/minecraft/entity/living/LivingEntity;DDD)V", at = @At("HEAD"), cancellable = true)
    private void s1f$specialsPre(LivingEntity e, double x, double y, double z, CallbackInfo ci) {
        if (MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Specials.Pre(e, (LivingEntityRenderer) (Object) this, x, y, z))) ci.cancel();
    }

    @Inject(method = "renderNameTag(Lnet/minecraft/entity/living/LivingEntity;DDD)V", at = @At("RETURN"))
    private void s1f$specialsPost(LivingEntity e, double x, double y, double z, CallbackInfo ci) {
        MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Specials.Post(e, (LivingEntityRenderer) (Object) this, x, y, z));
    }
}
