package com.seagull.liquidglass.client.compat.essential.mixin;

import com.seagull.liquidglass.client.compat.essential.EssentialLang;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Placeholder of Essential's own text-input fork (search bars …) in Traditional Chinese — drawn directly, not a UIText. */
@Pseudo
@Mixin(targets = "gg.essential.gui.common.input.AbstractTextInput")
public abstract class EssInputPlaceholderMixin {

   @Shadow private String placeholder;

   @Inject(method = "<init>(Ljava/lang/String;ZLjava/awt/Color;Ljava/awt/Color;Ljava/awt/Color;ZLjava/awt/Color;Ljava/awt/Color;Ljava/awt/Color;I)V",
           at = @At("RETURN"))
   private void lg$translatePlaceholder(CallbackInfo ci) {
      this.placeholder = EssentialLang.t(this.placeholder);
   }

   @ModifyVariable(method = "setPlaceholder(Ljava/lang/String;)V", at = @At("HEAD"), argsOnly = true)
   private String lg$translateSetPlaceholder(String value) {
      return EssentialLang.t(value);
   }
}
