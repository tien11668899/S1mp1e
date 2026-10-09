package dev.s1mp1e.glass.compat.essential.mixin;

import dev.s1mp1e.glass.compat.essential.EssentialLang;
import gg.essential.elementa.components.UIWrappedText;
import gg.essential.elementa.state.MappedState;
import gg.essential.elementa.state.State;
import kotlin.jvm.functions.Function1;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Essential's wrapped (multi-line) text in Traditional Chinese — same seam as {@link EssUITextMixin}. */
@Pseudo
@Mixin(value = UIWrappedText.class, remap = false)
public abstract class EssUIWrappedTextMixin {

   @SuppressWarnings({"rawtypes", "unchecked"})
   @Redirect(method = "<init>(Lgg/essential/elementa/state/State;Lgg/essential/elementa/state/State;Lgg/essential/elementa/state/State;ZZFLjava/lang/String;)V",
             at = @At(value = "INVOKE",
                      target = "Lgg/essential/elementa/state/State;map(Lkotlin/jvm/functions/Function1;)Lgg/essential/elementa/state/MappedState;",
                      ordinal = 0))
   private MappedState lg$translate(State state, Function1 fn) {
      return state.map(EssentialLang.wrap(fn));
   }

   @ModifyVariable(method = "setText(Ljava/lang/String;)Lgg/essential/elementa/components/UIWrappedText;", at = @At("HEAD"), argsOnly = true)
   private String lg$translateSet(String text) {
      return EssentialLang.t(text);
   }
}
