package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.glass.render.TypingAnim;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.SelectionManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.util.math.Matrix4f;
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
 * <p><b>1.19.2.</b> There is no {@code AbstractSignEditScreen} / {@code renderSignText} yet: {@code SignEditScreen.render}
 * does it all inline (javap-read). Inside the sign's scaled space it queues each line with the long
 * {@code TextRenderer.draw(String, x = -width/2, y, color, false, matrix, immediate, false, 0, light, false)} into the
 * shared entity vertex consumers (the same batch the board model went into), queues a {@code "_"} the same way when the
 * caret is at the end of the current line, flushes the batch, and then draws the within-line caret as a
 * {@code fill(matrices, …)} bar plus the selection quad. Here:
 * <ul>
 *   <li>the line draw is wrapped into {@link TypingAnim#extractLabel} (drawn immediately through the frame's
 *       {@code MatrixStack}, kept from {@code render} HEAD — the wrapped call only carries the bare matrix; the label
 *       code flushes the queued board first so the glyphs land on top of it). The row comes from the line's y
 *       ({@code row * 10 - 20}), so a skipped (null) row cannot shift the others;</li>
 *   <li>both vanilla caret forms are suppressed and the animated capsule is drawn once after the text, just before the
 *       sign transform is popped (its own alpha blink replaces vanilla's 6-tick on/off).</li>
 * </ul>
 * If the animated path throws, that line falls back to vanilla for good.
 */
@Mixin(SignEditScreen.class)
public abstract class SignTypingMixin {

   @Shadow private int currentRow;
   @Shadow private SelectionManager selectionManager;

   @Unique private final TypingAnim[] lg$lines = new TypingAnim[4];
   @Unique private int lg$lineY;
   @Unique private MatrixStack lg$matrices;

   @Unique private final boolean[] lg$seen = new boolean[4];

   @Inject(method = "render", at = @At("HEAD"))
   private void lg$keepMatrices(MatrixStack matrices, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      lg$matrices = matrices;
      java.util.Arrays.fill(lg$seen, false);
   }

   @WrapOperation(
      method = "render",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;FFIZLnet/minecraft/util/math/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;ZIIZ)I")
   )
   private int lg$line(TextRenderer font, String text, float x, float y, int color, boolean shadow, Matrix4f matrix,
                       VertexConsumerProvider consumers, boolean seeThrough, int background, int light,
                       boolean rightToLeft, Operation<Integer> op) {
      if (lg$matrices == null) {
         return op.call(font, text, x, y, color, shadow, matrix, consumers, seeThrough, background, light, rightToLeft);
      }
      int i = Math.round((y + 20F) / 10F);                  // vanilla: y = row * 10 - text.length * 5 (4 rows)
      if (i < 0 || i >= lg$lines.length || text == null) {
         return op.call(font, text, x, y, color, shadow, matrix, consumers, seeThrough, background, light, rightToLeft);
      }
      // The second string queued for a row in one frame is vanilla's end-of-line "_" caret (the first is the line,
      // even when the line itself reads "_"): the animated capsule replaces it.
      boolean second = lg$seen[i];
      lg$seen[i] = true;
      if (second) {
         if (lg$current() != null) return (int) x;
         return op.call(font, text, x, y, color, shadow, matrix, consumers, seeThrough, background, light, rightToLeft);
      }
      if (text == null) {
         return op.call(font, text, x, y, color, shadow, matrix, consumers, seeThrough, background, light, rightToLeft);
      }
      if (lg$lines[i] == null) lg$lines[i] = new TypingAnim();
      TypingAnim anim = lg$lines[i];
      if (anim.broken) {
         return op.call(font, text, x, y, color, shadow, matrix, consumers, seeThrough, background, light, rightToLeft);
      }
      if (i == this.currentRow) lg$lineY = Math.round(y);
      try {
         anim.extractLabel(lg$matrices, font, text, OrderedText.styledForwardsVisitedString(text, Style.EMPTY),
               0, Math.round(y), -10000, 10000, color, false);
         return (int) x + font.getWidth(text);
      } catch (Throwable t) {
         anim.broken = true;
         System.out.println("[S1mp1e] sign typing animation disabled for a line: " + t);
         return op.call(font, text, x, y, color, shadow, matrix, consumers, seeThrough, background, light, rightToLeft);
      }
   }

   /** The within-line caret bar is suppressed when animated; the animated caret is drawn after the text. */
   @Redirect(
      method = "render",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/screen/ingame/SignEditScreen;fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V")
   )
   private void lg$caretBar(MatrixStack g, int x0, int y0, int x1, int y1, int color) {
      if (lg$current() == null) DrawableHelper.fill(g, x0, y0, x1, y1, color);
   }

   /** Ordinal 1 = the pop that closes the sign transform (ordinal 0 closes the board model's own push). */
   @Inject(method = "render",
           at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;pop()V", ordinal = 1))
   private void lg$caret(MatrixStack g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
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
