package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.TypingAnim;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.TextCursorUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Multi-line text areas (the book &amp; quill page) type like the text box: glyphs rise / sharpen / fade in, deleted ones
 * float away, word-wrap moves glide in 2-D, and the caret is the gliding Apple capsule — see
 * {@link TypingAnim#extractMultiline}. Vanilla's per-line text draws (before / after the caret, other lines, and the
 * {@code _} end caret) and its insert caret become no-ops while the animated renderer is healthy; placeholder text and
 * the selection highlight stay vanilla. Extends the target's superclass only to reach its protected inner-area getters.
 */
@Mixin(MultiLineEditBox.class)
public abstract class MultiLineTypingMixin extends AbstractTextAreaWidget {

   private MultiLineTypingMixin() {
      super(0, 0, 0, 0, null, null);
   }

   @Shadow @Final private MultilineTextField textField;
   @Shadow @Final private Font font;
   @Shadow @Final private int textColor;
   @Shadow @Final private boolean textShadow;

   @Unique private TypingAnim lg$anim;
   @Unique private static java.lang.reflect.Method lg$begin, lg$end;

   @Unique
   private boolean lg$animated() {
      return lg$anim != null && !lg$anim.broken;
   }

   @Inject(method = "extractContents", at = @At(value = "INVOKE", ordinal = 0,
         target = "Lnet/minecraft/client/gui/components/MultilineTextField;iterateLines()Ljava/lang/Iterable;"))
   private void lg$drawAnimated(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (lg$anim == null) lg$anim = new TypingAnim();
      if (lg$anim.broken) return;
      try {
         List<int[]> lines = new ArrayList<>();
         Iterable<?> views = this.textField.iterateLines();       // StringView is protected: read it reflectively
         for (Object v : views) {
            if (lg$begin == null) {
               lg$begin = v.getClass().getDeclaredMethod("beginIndex"); lg$begin.setAccessible(true);
               lg$end = v.getClass().getDeclaredMethod("endIndex"); lg$end.setAccessible(true);
            }
            lines.add(new int[]{(Integer) lg$begin.invoke(v), (Integer) lg$end.invoke(v)});
         }
         lg$anim.extractMultiline(g, this.font, this.textField.value(), lines, this.getInnerLeft(), this.getInnerTop(),
               this.font.lineHeight, this.textColor, this.textShadow, this.textField.cursor(),
               this.textField.getLineAtCursor(), this.isFocused());
      } catch (Throwable t) {
         lg$anim.broken = true;
         System.out.println("[S1mp1e] multi-line typing animation disabled: " + t);
      }
   }

   @Redirect(method = "extractContents", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"))
   private void lg$vanillaText(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color, boolean shadow) {
      if (!lg$animated()) g.text(font, text, x, y, color, shadow);
   }

   @Redirect(method = "extractContents", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/components/TextCursorUtils;extractInsertCursor(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIII)V"))
   private void lg$vanillaInsertCaret(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
      if (!lg$animated()) TextCursorUtils.extractInsertCursor(g, x0, y0, x1, y1);
   }

   /** The end-of-line {@code _} caret: replaced by the animated capsule too. */
   @Redirect(method = "extractContents", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/components/TextCursorUtils;extractAppendCursor(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;IIIZ)V"))
   private void lg$vanillaAppendCaret(GuiGraphicsExtractor g, Font font, int x, int y, int color, boolean shadow) {
      if (!lg$animated()) TextCursorUtils.extractAppendCursor(g, font, x, y, color, shadow);
   }
}
