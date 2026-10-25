package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.TypingAnim;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
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
 * {@code _} end caret, all {@code drawString(String …)} in 1.21.x) and its insert caret (the one {@code fill}) become
 * no-ops while the animated renderer is healthy; placeholder text and the selection highlight stay vanilla (26.x routes
 * the carets through {@code TextCursorUtils}). Extends the target's superclass only to reach its protected inner-area
 * getters.
 */
@Mixin(MultiLineEditBox.class)
public abstract class MultiLineTypingMixin extends AbstractTextAreaWidget {

   private MultiLineTypingMixin() {
      super(0, 0, 0, 0, null);
   }

   @Shadow @Final private MultilineTextField textField;
   @Shadow @Final private Font font;
   @Shadow @Final private int textColor;
   @Shadow @Final private boolean textShadow;

   @Unique private TypingAnim lg$anim;

   @Unique
   private boolean lg$animated() {
      return lg$anim != null && !lg$anim.broken;
   }

   @Inject(method = "renderContents", at = @At(value = "INVOKE", ordinal = 0,
         target = "Lnet/minecraft/client/gui/components/MultilineTextField;iterateLines()Ljava/lang/Iterable;"))
   private void lg$drawAnimated(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (lg$anim == null) lg$anim = new TypingAnim();
      if (lg$anim.broken) return;
      try {
         List<int[]> lines = new ArrayList<>();
         // StringView is a protected record: read it through an invoker (1.21.x runs on intermediary names, so the
         // by-name reflection 26.x uses would not find beginIndex / endIndex)
         Iterable<?> views = this.textField.iterateLines();
         for (Object v : views) {
            MultilineStringViewAccessor view = (MultilineStringViewAccessor) v;
            lines.add(new int[]{view.liquidglass$beginIndex(), view.liquidglass$endIndex()});
         }
         lg$anim.extractMultiline(g, this.font, this.textField.value(), lines, this.getInnerLeft(), this.getInnerTop(),
               this.font.lineHeight, this.textColor, this.textShadow, this.textField.cursor(),
               this.textField.getLineAtCursor(), this.isFocused());
      } catch (Throwable t) {
         lg$anim.broken = true;
         System.out.println("[S1mp1e] multi-line typing animation disabled: " + t);
      }
   }

   @Redirect(method = "renderContents", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V"))
   private void lg$vanillaText(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow) {
      if (!lg$animated()) g.drawString(font, text, x, y, color, shadow);
   }

   @Redirect(method = "renderContents", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
   private void lg$vanillaInsertCaret(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
      if (!lg$animated()) g.fill(x0, y0, x1, y1, color);
   }
}
