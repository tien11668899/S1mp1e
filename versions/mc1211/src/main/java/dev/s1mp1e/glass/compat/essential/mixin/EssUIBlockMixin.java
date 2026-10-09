package dev.s1mp1e.glass.compat.essential.mixin;

import java.awt.Color;

import dev.s1mp1e.glass.compat.essential.EssentialGlass;
import gg.essential.elementa.components.UIBlock;
import gg.essential.universal.UMatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Elementa's solid rectangle (every {@code UIBlock}): classified by {@link EssentialGlass} while an Essential UI paints. */
@Pseudo
@Mixin(value = UIBlock.Companion.class, remap = false)
public abstract class EssUIBlockMixin {

   @Inject(method = "drawBlock(Lgg/essential/universal/UMatrixStack;Ljava/awt/Color;DDDD)V", at = @At("HEAD"), cancellable = true)
   private void lg$glass(UMatrixStack stack, Color color, double x1, double y1, double x2, double y2, CallbackInfo ci) {
      if (!EssentialGlass.recording()) { EssentialGlass.idle(stack, color, x1, y1, x2, y2, 0F); return; }
      Color out = EssentialGlass.onRect(stack, color, x1, y1, x2, y2, 0F);
      if (out == color) return;
      ci.cancel();
      if (out == null) return;
      EssentialGlass.guard = true;
      try {
         ((UIBlock.Companion) (Object) this).drawBlock(stack, out, x1, y1, x2, y2);
      } finally {
         EssentialGlass.guard = false;
      }
   }
}
