package dev.s1mp1e.o.mixin;

import dev.s1mp1e.o.glass.render.GlassEffects;
import net.minecraft.client.gui.screen.inventory.menu.PlayerInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * coremod「InventoryEffectRenderer.drawActivePotionEffects」：玻璃效果條（在背景畫完時就畫好了）生效時，
 * 原版效果框不再畫——它在 super.render（含工具提示）之後才畫，不擋掉就會蓋在工具提示上。
 */
@Mixin(value = PlayerInventoryScreen.class, priority = 1100)
public abstract class PlayerInventoryScreenMixin {
    @Inject(method = "drawStatusEffects", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$effects(CallbackInfo ci) {
        if (GlassEffects.consumeArmed()) ci.cancel();
    }
}
