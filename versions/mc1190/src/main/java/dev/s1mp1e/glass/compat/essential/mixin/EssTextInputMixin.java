package dev.s1mp1e.glass.compat.essential.mixin;

import dev.s1mp1e.glass.compat.essential.EssentialLang;
import gg.essential.elementa.components.input.AbstractTextInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Placeholder text of Elementa text inputs ("Search" …) in Traditional Chinese — it is drawn directly, not by a UIText. */
@Pseudo
@Mixin(value = AbstractTextInput.class, remap = false)
public abstract class EssTextInputMixin {

   @Shadow private String placeholder;

   @Inject(method = "<init>(Ljava/lang/String;ZLjava/awt/Color;Ljava/awt/Color;ZLjava/awt/Color;Ljava/awt/Color;Ljava/awt/Color;)V", at = @At("RETURN"))
   private void lg$translatePlaceholder(CallbackInfo ci) {
      this.placeholder = EssentialLang.t(this.placeholder);
   }

   @ModifyVariable(method = "setPlaceholder(Ljava/lang/String;)V", at = @At("HEAD"), argsOnly = true)
   private String lg$translateSetPlaceholder(String value) {
      return EssentialLang.t(value);
   }
}
