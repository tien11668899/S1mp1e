package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.client.asm.CombatHooks;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.living.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * coremod「RendererLivingEntity.setBrightness」：受傷/死亡那幾幀回傳 false，不畫紅色閃光；
 * 呼叫端用同一個回傳值決定要不要 unset，GL 狀態保持平衡。
 */
@Mixin(value = LivingEntityRenderer.class, priority = 1100)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "setupOverlayColor(Lnet/minecraft/entity/living/LivingEntity;FZ)Z", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$noHurtFlash(LivingEntity e, float pt, boolean b, CallbackInfoReturnable<Boolean> cir) {
        if (CombatHooks.suppressHurtFlash(e)) cir.setReturnValue(false);
    }
}
