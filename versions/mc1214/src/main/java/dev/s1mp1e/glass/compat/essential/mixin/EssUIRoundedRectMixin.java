package dev.s1mp1e.glass.compat.essential.mixin;

import java.awt.Color;

import dev.s1mp1e.glass.compat.essential.EssentialGlass;
import gg.essential.elementa.components.UIRoundedRectangle;
import gg.essential.universal.UMatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Elementa's rounded rectangle (Essential's newer panels and buttons): same classification as {@link EssUIBlockMixin}. */
@Pseudo
@Mixin(value = UIRoundedRectangle.Companion.class, remap = false)
public abstract class EssUIRoundedRectMixin {

   @Inject(method = "drawRoundedRectangle(Lgg/essential/universal/UMatrixStack;FFFFFLjava/awt/Color;)V", at = @At("HEAD"), cancellable = true)
   private void lg$glass(UMatrixStack stack, float left, float top, float right, float bottom, float radius, Color color,
                         CallbackInfo ci) {
      if (!EssentialGlass.recording()) { EssentialGlass.idle(stack, color, left, top, right, bottom, radius); return; }
      Color out = EssentialGlass.onRect(stack, color, left, top, right, bottom, radius);
      if (out == color) return;
      ci.cancel();
      if (out == null) return;
      EssentialGlass.guard = true;
      try {
         ((UIRoundedRectangle.Companion) (Object) this).drawRoundedRectangle(stack, left, top, right, bottom, radius, out);
      } finally {
         EssentialGlass.guard = false;
      }
   }
}
