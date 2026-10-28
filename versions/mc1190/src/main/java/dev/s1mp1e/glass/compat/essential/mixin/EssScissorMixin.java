package dev.s1mp1e.glass.compat.essential.mixin;

import dev.s1mp1e.glass.compat.essential.EssentialGlass;
import gg.essential.universal.UGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Track UniversalCraft's scissor (Elementa scroll areas): glass scrolled out of a list is skipped, not counted. */
@Pseudo
@Mixin(value = UGraphics.class, remap = false)
public abstract class EssScissorMixin {

   @Inject(method = "enableScissor(IIII)V", at = @At("HEAD"), require = 0)
   private static void lg$scissor(int x, int y, int w, int h, CallbackInfo ci) {
      EssentialGlass.scissor(x, y, w, h);
   }

   @Inject(method = "disableScissor()V", at = @At("HEAD"), require = 0)
   private static void lg$noScissor(CallbackInfo ci) {
      EssentialGlass.noScissor();
   }
}
