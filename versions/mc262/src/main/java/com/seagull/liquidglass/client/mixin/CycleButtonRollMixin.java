package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seagull.liquidglass.client.render.TypingAnim;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
 */
@Mixin(CycleButton.class)
public abstract class CycleButtonRollMixin {

   @Unique private TypingAnim lg$label;
   @Unique private GuiGraphicsExtractor lg$g;

   @Inject(method = "extractContents", at = @At("HEAD"))
   private void lg$captureGraphics(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      this.lg$g = g;
   }

   @WrapOperation(
      method = "extractContents",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/components/CycleButton;extractDefaultLabel(Lnet/minecraft/client/gui/ActiveTextCollector;)V")
   )
   private void lg$rollLabel(CycleButton<?> self, ActiveTextCollector collector, Operation<Void> original) {
      GuiGraphicsExtractor g = this.lg$g;
      if (lg$label == null) lg$label = new TypingAnim();
      Font font = Minecraft.getInstance().font;
      Component msg = self.getMessage();
      FormattedCharSequence seq = msg.getVisualOrderText();
      int x0 = self.getX() + 2, x1 = self.getX() + self.getWidth() - 2;
      if (g == null || lg$label.broken || self.getAlpha() <= 0.004F || font.width(seq) > x1 - x0) {
         original.call(self, collector);
         return;
      }
      int y0 = self.getY(), y1 = self.getY() + self.getHeight();
      int textY = (y0 + y1 - font.lineHeight) / 2 + 1;
      int color = (Math.round(self.getAlpha() * 255F) & 0xFF) << 24 | 0xFFFFFF;
      try {
         lg$label.extractLabel(g, font, msg.getString(), seq, (x0 + x1) / 2, textY, x0, x1, color, true);
      } catch (Throwable t) {
         lg$label.broken = true;
         System.out.println("[S1mp1e] cycle-button roll disabled: " + t);
         original.call(self, collector);
      }
   }
}
