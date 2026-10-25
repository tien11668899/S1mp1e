package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seagull.liquidglass.client.render.TypingAnim;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sign editing gets the same typing feel as text boxes: each of the four centred lines is drawn by its own
 * {@link TypingAnim} in centred-label mode (new glyphs rise/sharpen/fade in, deleted ones float away, the line re-centres
 * by gliding), and the caret is the Apple system-blue capsule with a smooth blink, riding the line's gliding origin.
 * Vanilla's line layout ({@code x = -width/2}, its y, colour, no shadow) is reproduced exactly.
 *
 * <p>1.21.8 {@code renderSignText(g)} (javap): the blink is {@code frame / 6 % 2}, each line is the first
 * {@code drawString(String …)}, the end caret {@code "_"} the second, the insert caret the one {@code fill}. 26.x routes
 * the blink and both carets through {@code TextCursorUtils}.
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class SignTypingMixin {

   @Shadow private int line;
   @Shadow private int frame;
   @Shadow private net.minecraft.client.gui.font.TextFieldHelper signField;

   @Unique private final TypingAnim[] lg$lines = new TypingAnim[4];
   @Unique private int lg$lineIdx;
   @Unique private int lg$lineY;

   @Inject(method = "renderSignText", at = @At("HEAD"))
   private void lg$resetLines(GuiGraphics g, CallbackInfo ci) {
      lg$lineIdx = 0;
   }

   @WrapOperation(method = "renderSignText", at = @At(value = "INVOKE", ordinal = 0,
         target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"))
   private void lg$line(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow, Operation<Void> op) {
      int i = lg$lineIdx++;
      if (i < 0 || i >= lg$lines.length) { op.call(g, font, text, x, y, color, shadow); return; }
      if (lg$lines[i] == null) lg$lines[i] = new TypingAnim();
      TypingAnim anim = lg$lines[i];
      if (anim.broken) { op.call(g, font, text, x, y, color, shadow); return; }
      if (i == this.line) lg$lineY = y;
      try {
         anim.extractLabel(g, font, text, FormattedCharSequence.forward(text, Style.EMPTY), 0, y, -10000, 10000, color, shadow);
      } catch (Throwable t) {
         anim.broken = true;
         op.call(g, font, text, x, y, color, shadow);
      }
   }

   /** The blink gate ({@code frame / 6 % 2 == 0}) reads "on" while animated: the caret blinks via alpha. */
   @Redirect(method = "renderSignText", at = @At(value = "FIELD",
         target = "Lnet/minecraft/client/gui/screens/inventory/AbstractSignEditScreen;frame:I"))
   private int lg$caretGate(AbstractSignEditScreen self) {
      return lg$current() != null ? 0 : this.frame;
   }

   @Redirect(method = "renderSignText", at = @At(value = "INVOKE", ordinal = 1,
         target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"))
   private void lg$appendCaret(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow) {
      TypingAnim a = lg$current();
      if (a == null) { g.drawString(font, text, x, y, color, shadow); return; }
      a.drawLabelCaret(g, this.signField.getCursorPos(), lg$lineY);
   }

   @Redirect(method = "renderSignText", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
   private void lg$insertCaret(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
      TypingAnim a = lg$current();
      if (a == null) { g.fill(x0, y0, x1, y1, color); return; }
      a.drawLabelCaret(g, this.signField.getCursorPos(), lg$lineY);
   }

   @Unique
   private TypingAnim lg$current() {
      int l = this.line;
      if (l < 0 || l >= lg$lines.length) return null;
      TypingAnim a = lg$lines[l];
      return a == null || a.broken ? null : a;
   }
}
