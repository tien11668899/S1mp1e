package com.seagull.liquidglass.client.compat.essential.mixin;

import java.awt.Color;

import com.seagull.liquidglass.client.compat.essential.EssentialGlass;
import gg.essential.elementa.components.GradientComponent;
import gg.essential.universal.UMatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Elementa gradients drawn while an Essential UI paints: the grey ones are scroll fades into the old flat panel colour
 * (a dark band over the glass, e.g. the bottom of "Select world to host") → dropped. Coloured gradients stay.
 */
@Pseudo
@Mixin(GradientComponent.Companion.class)
public abstract class EssGradientMixin {

   @Inject(method = "drawGradientBlock(Lgg/essential/universal/UMatrixStack;DDDDLjava/awt/Color;Ljava/awt/Color;Lgg/essential/elementa/components/GradientComponent$GradientDirection;)V",
         at = @At("HEAD"), cancellable = true)
   private void lg$dropGreyFade(UMatrixStack stack, double x1, double y1, double x2, double y2, Color start, Color end,
                                GradientComponent.GradientDirection dir, CallbackInfo ci) {
      if (EssentialGlass.recording() && EssentialGlass.isGreyFade(start, end)) ci.cancel();
   }
}
