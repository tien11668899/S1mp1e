package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.seagull.liquidglass.client.render.TypingAnim;
import java.util.function.BiFunction;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Silky typing for every {@link EditBox} (chat input, search fields, anvil rename, world/server names …). All drawing and
 * state lives in {@link TypingAnim} (one per box): per-glyph entrance (blur→sharp + rise + fade on one spring), exit on
 * delete, neighbours gliding after a mid-string edit, eased horizontal scroll, and the Apple caret / selection locked to
 * the animated text.
 *
 * <p>Hooks inside {@code renderWidget} (1.21.6 / 1.21.7 / 1.21.8 javap — one jar serves all three; 26.x routes the
 * carets through {@code TextCursorUtils}):
 * <ol>
 *   <li>At the {@code Font.plainSubstrByWidth} call (after the box background, before highlight and caret) the whole text
 *       is drawn by {@link TypingAnim#renderText}; the full string is formatted with the box's own formatter
 *       ({@code formatter.apply(value, 0)}), so chat command highlighting keeps its colours.</li>
 *   <li>Vanilla's two {@code drawString(FormattedCharSequence …)} draws (before / after the caret) become no-ops.</li>
 *   <li>The blink ({@code (Util.getMillis() - focusedTime) / 300 % 2}) always reads "on" — the blink is an alpha now;
 *       {@code isFocused()} still gates the caret — and both carets (the insert bar {@code fill}, the append
 *       {@code "_"} = the second {@code drawString(String …)}), the selection highlight and the grey suggestion (the
 *       first {@code drawString(String …)}) are drawn in the animated text's coordinates. Vanilla's highlight is gated
 *       off rather than redirected, because its draw call differs between the three versions.</li>
 * </ol>
 * If the animated path ever throws, that box falls back to vanilla drawing for good ({@link TypingAnim#broken}).
 */
@Mixin(EditBox.class)
public abstract class EditBoxTypingMixin {

   @Shadow private String value;
   @Shadow private int displayPos;
   @Shadow private int cursorPos;
   @Shadow private int highlightPos;
   @Shadow private int textColor;
   @Shadow private int textColorUneditable;
   @Shadow private boolean isEditable;
   @Shadow private boolean textShadow;
   @Shadow private int textX;
   @Shadow private int textY;
   @Shadow private long focusedTime;
   @Shadow private BiFunction<String, Integer, FormattedCharSequence> formatter;
   @Shadow @Final private Font font;

   @Shadow public abstract int getInnerWidth();

   @Unique private TypingAnim lg$anim;
   /** This renderWidget call drew the animated text (it returns early for an invisible box). */
   @Unique private boolean lg$drawing;
   /** The selection was drawn this frame (before the caret, or at RETURN when there is no caret). */
   @Unique private boolean lg$selDrawn;

   @Unique
   private boolean lg$animated() {
      return lg$anim != null && !lg$anim.broken;
   }

   /** 1. The whole text, glyph by glyph, animated. */
   @Inject(
      method = "renderWidget",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;plainSubstrByWidth(Ljava/lang/String;I)Ljava/lang/String;")
   )
   private void lg$typingText(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (lg$anim == null) lg$anim = new TypingAnim();
      if (lg$anim.broken) return;
      lg$drawing = true;
      lg$selDrawn = false;
      try {
         int color = this.isEditable ? this.textColor : this.textColorUneditable;
         lg$anim.renderText(g, this.font, this.value, this.formatter.apply(this.value, 0), this.displayPos, this.cursorPos,
               this.textX, this.textY, this.getInnerWidth(), color, this.textShadow);
      } catch (Throwable t) {
         lg$anim.broken = true;
         System.out.println("[S1mp1e] typing animation disabled for an EditBox: " + t);
      }
   }

   /** 2. Vanilla's before/after-caret text draws are replaced by the pass above. */
   @Redirect(
      method = "renderWidget",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V")
   )
   private void lg$vanillaText(GuiGraphics g, Font font, FormattedCharSequence seq, int x, int y, int color, boolean shadow) {
      if (lg$animated()) return;
      g.drawString(font, seq, x, y, color, shadow);
   }

   /** 3a. Blink gate open (the caret blinks via alpha): the blink clock reads "just focused"; vanilla's on the fallback. */
   @Redirect(
      method = "renderWidget",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;getMillis()J")
   )
   private long lg$caretAlwaysDraw() {
      return lg$animated() ? this.focusedTime : Util.getMillis();
   }

   @Redirect(
      method = "renderWidget",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V")
   )
   private void lg$insertCaret(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
      if (lg$animated()) { lg$selection(g); lg$anim.drawCaret(g); }
      else g.fill(x0, y0, x1, y1, color);
   }

   @Redirect(
      method = "renderWidget",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
               ordinal = 1)
   )
   private void lg$appendCaret(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow) {
      if (lg$animated()) { lg$selection(g); lg$anim.drawCaret(g); }   // end-of-text caret: the same bar, never "_"
      else g.drawString(font, text, x, y, color, shadow);
   }

   /**
    * 3b. Vanilla's own selection highlight is skipped while animated: it is drawn when {@code l != j}, with
    * {@code l = Mth.clamp(highlightPos - displayPos, 0, len)} (the method's first clamp) and {@code j = cursorPos -
    * displayPos}; reading {@code l} as {@code j} closes that gate. The draw itself differs by version (1.21.6: the private
    * {@code renderHighlight}, 1.21.7+: {@code GuiGraphics.textHighlight}), the gate is the same in all three.
    */
   @ModifyExpressionValue(
      method = "renderWidget",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(III)I", ordinal = 0)
   )
   private int lg$noVanillaHighlight(int clamped) {
      return lg$animated() ? this.cursorPos - this.displayPos : clamped;
   }

   /** 3c. Selection highlight with gliding edges, in the animated text's coordinates: before the caret, as vanilla. */
   @Unique
   private void lg$selection(GuiGraphics g) {
      if (lg$selDrawn || !lg$animated() || this.highlightPos == this.cursorPos) return;
      lg$selDrawn = true;
      lg$anim.drawHighlight(g, this.highlightPos, this.textY - 1, this.textY + 1 + this.font.lineHeight, true);
   }

   /** No caret this frame (unfocused, or the caret is scrolled out of view): the selection still shows. */
   @Inject(method = "renderWidget", at = @At("RETURN"))
   private void lg$selectionWithoutCaret(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (lg$drawing) lg$selection(g);
      lg$drawing = false;
   }

   /** 3c. The grey command suggestion after the caret follows the eased scroll. */
   @Redirect(
      method = "renderWidget",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
               ordinal = 0)
   )
   private void lg$suggestion(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow) {
      float d = lg$animated() ? lg$anim.scrollDelta() : 0F;
      if (Math.abs(d) < 0.01F) { g.drawString(font, text, x, y, color, shadow); return; }
      Matrix3x2fStack pose = g.pose();
      pose.pushMatrix();
      pose.translate(d, 0F);
      try { g.drawString(font, text, x, y, color, shadow); } finally { pose.popMatrix(); }
   }
}
