package com.seagull.liquidglass.client.compat.essential.mixin;

import com.seagull.liquidglass.client.compat.essential.EssentialLang;
import gg.essential.elementa.components.UIText;
import gg.essential.elementa.state.MappedState;
import gg.essential.elementa.state.State;
import kotlin.jvm.functions.Function1;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Essential's single-line text ({@code UIText}, and its {@code EssentialUIText} subclass) in Traditional Chinese. The
 * component keeps one {@code textState} that BOTH the drawing and the width measurement read, so translating at the
 * point that state is built (and in {@code setText}, which writes it directly) means layout sizes the Chinese text.
 */
@Pseudo
@Mixin(UIText.class)
public abstract class EssUITextMixin {

   @SuppressWarnings({"rawtypes", "unchecked"})
   @Redirect(method = "<init>(Lgg/essential/elementa/state/State;Lgg/essential/elementa/state/State;Lgg/essential/elementa/state/State;)V",
             at = @At(value = "INVOKE",
                      target = "Lgg/essential/elementa/state/State;map(Lkotlin/jvm/functions/Function1;)Lgg/essential/elementa/state/MappedState;",
                      ordinal = 0))
   private MappedState lg$translate(State state, Function1 fn) {
      return state.map(EssentialLang.wrap(fn));
   }

   @ModifyVariable(method = "setText(Ljava/lang/String;)Lgg/essential/elementa/components/UIText;", at = @At("HEAD"), argsOnly = true)
   private String lg$translateSet(String text) {
      return EssentialLang.t(text);
   }
}
