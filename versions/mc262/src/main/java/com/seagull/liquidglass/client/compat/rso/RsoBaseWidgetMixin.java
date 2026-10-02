package com.seagull.liquidglass.client.compat.rso;

import com.seagull.liquidglass.client.compat.RsoGlass;
import me.flashyreese.mods.reeses_sodium_options.client.gui.widget.BaseWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every RSO fill / outline goes through these two helpers: fills are routed to {@link RsoGlass#rect} (glass or
 * nothing), outlines are dropped while the screen draws. (RSO's scissor is mirrored by {@code RsoScissorMixin}.)
 */
@Mixin(BaseWidget.class)
public abstract class RsoBaseWidgetMixin {
   @Inject(method = "drawRect", at = @At("HEAD"), cancellable = true)
   private void lg$rect(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color, CallbackInfo ci) {
      if (RsoGlass.rect((BaseWidget) (Object) this, g, x1, y1, x2, y2, color)) ci.cancel();
   }

   @Inject(method = "drawBorder", at = @At("HEAD"), cancellable = true)
   private void lg$border(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color, CallbackInfo ci) {
      if (RsoGlass.active) ci.cancel();
   }
}
