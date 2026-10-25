package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.TypingAnim;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cycle buttons ("Difficulty: Normal", "Clouds: Fancy", on/off toggles …) roll their value like an odometer when it
 * changes, instead of swapping the label in one frame: the unchanged "Name: " stays put, the old value floats up and
 * fades, the new one rises in glyph by glyph, and the label re-centres by gliding. Drawn by {@link TypingAnim#extractLabel}
 * at exactly vanilla's centred position/colour/shadow; a label too wide for the button (vanilla's marquee) or an
 * invisible button falls back to vanilla drawing.
 *
 * <p>1.21.8: {@link CycleButton} has no label method of its own — {@code AbstractButton.renderWidget} calls
 * {@code renderString(g, font, color)} (→ {@code renderScrollingString}, inset 2) for every button, so the hook sits
 * there and only acts for cycle buttons. The colour vanilla passes (white, grey when inactive, with the widget alpha)
 * is kept. 26.x wraps {@code CycleButton.extractDefaultLabel} instead.
 */
@Mixin(AbstractButton.class)
public abstract class CycleButtonRollMixin {

   @Unique private TypingAnim lg$label;

   @Inject(method = "renderString", at = @At("HEAD"), cancellable = true)
   private void lg$rollLabel(GuiGraphics g, Font font, int color, CallbackInfo ci) {
      if (!((Object) this instanceof CycleButton<?> self)) return;
      if (lg$label == null) lg$label = new TypingAnim();
      Component msg = self.getMessage();
      FormattedCharSequence seq = msg.getVisualOrderText();
      int x0 = self.getX() + 2, x1 = self.getX() + self.getWidth() - 2;
      float alpha = ((AbstractWidgetTextInvoker) self).liquidglass$alpha();
      if (lg$label.broken || alpha <= 0.004F || font.width(seq) > x1 - x0) {
         return;   // vanilla: marquee / invisible
      }
      int y0 = self.getY(), y1 = self.getY() + self.getHeight();
      int textY = (y0 + y1 - font.lineHeight) / 2 + 1;
      try {
         lg$label.extractLabel(g, font, msg.getString(), seq, (x0 + x1) / 2, textY, x0, x1, color, true);
         ci.cancel();
      } catch (Throwable t) {
         lg$label.broken = true;
         System.out.println("[S1mp1e] cycle-button roll disabled: " + t);
      }
   }
}
