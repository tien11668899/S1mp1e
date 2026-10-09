package dev.s1mp1e.glass.compat.essential.mixin;

import gg.essential.elementa.components.UIText;
import gg.essential.elementa.components.UIWrappedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** S1mp1e draws no text shadows anywhere: Elementa text (Essential's labels, titles, list rows) included. */
@Pseudo
@Mixin(value = {UIText.class, UIWrappedText.class}, remap = false)
public abstract class EssTextShadowMixin {

   @ModifyArg(method = "draw(Lgg/essential/universal/UMatrixStack;)V", at = @At(value = "INVOKE",
         target = "Lgg/essential/elementa/font/FontProvider;drawString(Lgg/essential/universal/UMatrixStack;Ljava/lang/String;Ljava/awt/Color;FFFFZLjava/awt/Color;)V"),
         index = 7, require = 0)
   private boolean lg$noShadow(boolean shadow) {
      return false;
   }
}
