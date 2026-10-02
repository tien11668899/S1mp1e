package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.glass.render.TypingAnim;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.util.SelectionManager;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sign editing gets the same typing feel as text boxes: each of the four centred lines is drawn by its own {@link TypingAnim}
 * in centred-label mode (new glyphs rise/sharpen/fade in, deleted ones float away, the line re-centres by gliding), and the
 * caret is the Apple system-blue capsule with a smooth blink, riding the line's gliding origin.
 *
 * <p>1.21.1 {@code AbstractSignEditScreen.renderSignText} draws each line with {@code drawText(String, x=-width/2, y, …)}
 * (centred at 0 in the scaled sign space) and its caret as either a {@code fill(…)} vertical bar (cursor within the line) or
 * a {@code "_"} {@code drawText} (cursor at the end). The line draw is wrapped into {@link TypingAnim#extractLabel}; both
 * vanilla caret forms are suppressed and the animated capsule is drawn once at TAIL (its own alpha blink handles the blink).
 * If the animated path throws, that line falls back to vanilla for good.
 */
@Mixin(AbstractSignEditScreen.class)
public abstract class SignTypingMixin {

   @Shadow private int currentRow;
   @Shadow private SelectionManager selectionManager;

   @Unique private final TypingAnim[] lg$lines = new TypingAnim[4];
   @Unique private int lg$lineIdx;
   @Unique private int lg$lineY;

   @Inject(method = "renderSignText", at = @At("HEAD"))
   private void lg$resetLines(DrawContext g, CallbackInfo ci) {
      lg$lineIdx = 0;
   }

   @WrapOperation(
      method = "renderSignText",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/DrawContext;drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)I")
   )
   private int lg$line(DrawContext g, TextRenderer font, String text, int x, int y, int color, boolean shadow, Operation<Integer> op) {
      if ("_".equals(text)) return x;                       // end-of-line caret: the animated capsule replaces it
      int i = lg$lineIdx++;
      if (i < 0 || i >= lg$lines.length) return op.call(g, font, text, x, y, color, shadow);
      if (lg$lines[i] == null) lg$lines[i] = new TypingAnim();
      TypingAnim anim = lg$lines[i];
      if (anim.broken) return op.call(g, font, text, x, y, color, shadow);
      if (i == this.currentRow) lg$lineY = y;
      try {
         anim.extractLabel(g, font, text, OrderedText.styledForwardsVisitedString(text, Style.EMPTY),
               0, y, -10000, 10000, color, shadow);
         return x + font.getWidth(text);
      } catch (Throwable t) {
         anim.broken = true;
         return op.call(g, font, text, x, y, color, shadow);
      }
   }

   /** The within-line caret bar is suppressed when animated; the animated caret is drawn at TAIL. */
   @Redirect(
      method = "renderSignText",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V")
   )
   private void lg$caretBar(DrawContext g, int x0, int y0, int x1, int y1, int color) {
      if (lg$current() == null) g.fill(x0, y0, x1, y1, color);
   }

   @Inject(method = "renderSignText", at = @At("TAIL"))
   private void lg$caret(DrawContext g, CallbackInfo ci) {
      TypingAnim a = lg$current();
      if (a != null) a.drawLabelCaret(g, this.selectionManager.getSelectionStart(), lg$lineY);
   }

   @Unique
   private TypingAnim lg$current() {
      int l = this.currentRow;
      if (l < 0 || l >= lg$lines.length) return null;
      TypingAnim a = lg$lines[l];
      return a == null || a.broken ? null : a;
   }
}
