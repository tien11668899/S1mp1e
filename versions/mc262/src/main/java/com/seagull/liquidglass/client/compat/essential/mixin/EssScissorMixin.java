package com.seagull.liquidglass.client.compat.essential.mixin;

import com.seagull.liquidglass.client.compat.essential.EssentialGlass;
import gg.essential.universal.UGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Track UniversalCraft's scissor (Elementa scroll areas) so recorded glass is clipped like the content it backs. */
@Pseudo
@Mixin(UGraphics.class)
public abstract class EssScissorMixin {

   @Inject(method = "enableScissor(IIII)V", at = @At("HEAD"))
   private static void lg$scissor(int x, int y, int w, int h, CallbackInfo ci) {
      EssentialGlass.scissor(x, y, w, h);
   }

   @Inject(method = "disableScissor()V", at = @At("HEAD"))
   private static void lg$noScissor(CallbackInfo ci) {
      EssentialGlass.noScissor();
   }
}
