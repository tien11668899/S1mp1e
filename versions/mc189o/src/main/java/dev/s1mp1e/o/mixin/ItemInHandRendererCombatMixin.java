package dev.s1mp1e.o.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.o.client.asm.CameraHooks;
import dev.s1mp1e.o.client.asm.CombatHooks;
import net.minecraft.client.render.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * coremod 在 ItemRenderer 上的修補：第一人稱手部位移（HandPosition：開頭記 partialTicks、第一個 pushMatrix 之後位移）、
 * 舊版揮劍動畫（OldAnimations：所有 transformFirstPersonItem 呼叫交給 CombatHooks，模組關閉時就是原版變換）。
 */
@Mixin(value = ItemInHandRenderer.class, priority = 1100)
public abstract class ItemInHandRendererCombatMixin {

    @Inject(method = "renderInFirstPerson", at = @At("HEAD"))
    private void s1mp1e$beginHand(float pt, CallbackInfo ci) {
        CameraHooks.beginFirstPerson(pt);
    }

    @Inject(method = "renderInFirstPerson", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/render/platform/GlStateManager;pushMatrix()V", shift = At.Shift.AFTER))
    private void s1mp1e$handOffset(float pt, CallbackInfo ci) {
        CameraHooks.handOffset();
    }

    @WrapOperation(method = "*", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/ItemInHandRenderer;applyFirstPersonTransform(FF)V"))
    private void s1mp1e$oldAnim(ItemInHandRenderer self, float equip, float swing, Operation<Void> op) {
        CombatHooks.transformFirstPersonItem(self, equip, swing);
    }
}
