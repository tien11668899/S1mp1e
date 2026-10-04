package dev.s1mp1e.forge.mixin;

import net.minecraft.entity.living.LivingEntity;
import net.minecraftforge.common.ForgeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Forge 的 LivingUpdateEvent：EntityLivingBase.onUpdate 開頭，取消＝這 tick 不更新。 */
@Mixin(value = LivingEntity.class, priority = 1050)
public abstract class LivingTickMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void s1f$livingUpdate(CallbackInfo ci) {
        if (ForgeHooks.onLivingUpdate((LivingEntity) (Object) this)) ci.cancel();
    }
}
