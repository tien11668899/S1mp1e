package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.TypingAnim;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.TextCursorUtils;
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
 * <p>Hooks inside {@code extractWidgetRenderState}:
 * <ol>
 *   <li>At the {@code Font.plainSubstrByWidth} call (after the box background, before highlight and caret) the whole text
 *       is drawn by {@link TypingAnim#extractText}; the full string is formatted with the box's own formatters
 *       ({@code applyFormat(value, 0)}), so chat command highlighting keeps its colours.</li>
 *   <li>Vanilla's two {@code text(FormattedCharSequence …)} draws (before / after the caret) become no-ops.</li>
 *   <li>{@code isCursorVisible} → true (the blink is an alpha now; {@code isFocused()} still gates the caret), and both
 *       caret draws, the selection highlight and the grey suggestion are drawn in the animated text's coordinates.</li>
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
   @Shadow @Final private Font font;

   @Shadow public abstract int getInnerWidth();

   @Shadow
   private FormattedCharSequence applyFormat(String text, int offset) {
      throw new AssertionError();
   }

   @Unique private TypingAnim lg$anim;

   @Unique
   private boolean lg$animated() {
      return lg$anim != null && !lg$anim.broken;
   }

   /** 1. The whole text, glyph by glyph, animated. */
   @Inject(
      method = "extractWidgetRenderState",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;plainSubstrByWidth(Ljava/lang/String;I)Ljava/lang/String;")
   )
   private void lg$typingText(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (lg$anim == null) lg$anim = new TypingAnim();
      if (lg$anim.broken) return;
      try {
         int color = this.isEditable ? this.textColor : this.textColorUneditable;
         lg$anim.extractText(g, this.font, this.value, this.applyFormat(this.value, 0), this.displayPos, this.cursorPos,
               this.textX, this.textY, this.getInnerWidth(), color, this.textShadow);
      } catch (Throwable t) {
         lg$anim.broken = true;
         System.out.println("[S1mp1e] typing animation disabled for an EditBox: " + t);
      }
   }

   /** 2. Vanilla's before/after-caret text draws are replaced by the pass above. */
   @Redirect(
      method = "extractWidgetRenderState",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V")
   )
   private void lg$vanillaText(GuiGraphicsExtractor g, Font font, FormattedCharSequence seq, int x, int y, int color, boolean shadow) {
      if (lg$animated()) return;
      g.text(font, seq, x, y, color, shadow);
   }

   /** 3a. Blink gate open (the caret blinks via alpha); vanilla's gate is kept for the fallback path. */
   @Redirect(
      method = "extractWidgetRenderState",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/TextCursorUtils;isCursorVisible(J)Z")
   )
   private boolean lg$caretAlwaysDraw(long focusedTimeMs) {
      return lg$animated() || TextCursorUtils.isCursorVisible(focusedTimeMs);
   }

   @Redirect(
      method = "extractWidgetRenderState",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/components/TextCursorUtils;extractInsertCursor(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V")
   )
   private void lg$insertCaret(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
      if (lg$animated()) lg$anim.drawCaret(g);
      else TextCursorUtils.extractInsertCursor(g, x0, y0, x1, y1);
   }

   @Redirect(
      method = "extractWidgetRenderState",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/components/TextCursorUtils;extractAppendCursor(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIIZ)V")
   )
   private void lg$appendCaret(GuiGraphicsExtractor g, Font font, int x, int y, int color, boolean shadow) {
      if (lg$animated()) lg$anim.drawCaret(g);          // end-of-text caret is the same bar, never an underscore
      else TextCursorUtils.extractAppendCursor(g, font, x, y, color, shadow);
   }

   /** 3b. Selection highlight with gliding edges, in the animated text's coordinates. */
   @Redirect(
      method = "extractWidgetRenderState",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textHighlight(IIIIZ)V")
   )
   private void lg$highlight(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, boolean invert) {
      if (lg$animated()) lg$anim.drawHighlight(g, this.highlightPos, y0, y1, invert);
      else g.textHighlight(x0, y0, x1, y1, invert);
   }

   /** 3c. The grey command suggestion after the caret follows the eased scroll. */
   @Redirect(
      method = "extractWidgetRenderState",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V")
   )
   private void lg$suggestion(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color, boolean shadow) {
      float d = lg$animated() ? lg$anim.scrollDelta() : 0F;
      if (Math.abs(d) < 0.01F) { g.text(font, text, x, y, color, shadow); return; }
      Matrix3x2fStack pose = g.pose();
      pose.pushMatrix();
      pose.translate(d, 0F);
      try { g.text(font, text, x, y, color, shadow); } finally { pose.popMatrix(); }
   }
}
