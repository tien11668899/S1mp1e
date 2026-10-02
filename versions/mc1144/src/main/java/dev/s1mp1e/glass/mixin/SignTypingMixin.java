package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.s1mp1e.glass.render.TypingAnim;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import net.minecraft.client.render.block.entity.SignBlockEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sign editing gets the same typing feel as text boxes: each of the four centred lines is drawn by its own
 * {@link TypingAnim} in centred-label mode (new glyphs rise/sharpen/fade in, deleted ones float away, the line
 * re-centres by gliding), and the caret is the Apple system-blue capsule with a smooth blink, riding the line's gliding
 * origin.
 *
 * <p><b>1.14.4.</b> The edit screen draws no text itself: {@code SignEditScreen.render} hands the sign to
 * {@code BlockEntityRenderDispatcher} after {@code sign.setSelectionState(row, start, end, caretVisible)}, and
 * {@code SignBlockEntityRenderer.render} (javap-read) draws, inside the sign's scaled space, each line with
 * {@code TextRenderer.draw(String, x = -width/2, y = row*10 - 20, color)} and — for the current row, while
 * {@code getSelectionStart() >= 0} — the caret ({@code DrawableHelper.fill} bar inside the line, a {@code "_"} at its
 * end) and the selection quad. So the hooks live in the renderer and are inert unless the sign carries a selection
 * state (only the sign being edited does) AND the sign edit screen is open:
 * <ul>
 *   <li>the line draw is wrapped into {@link TypingAnim#extractLabel} (drawn immediately, under the same GL matrix).
 *       The row comes from the line's y, so a skipped (null) row cannot shift the others;</li>
 *   <li>both vanilla caret forms are suppressed and the animated capsule is drawn once after the text loop, before
 *       the sign transform is popped (its own alpha blink replaces vanilla's 6-tick on/off).</li>
 * </ul>
 * If the animated path throws, that line falls back to vanilla for good.
 */
@Mixin(SignBlockEntityRenderer.class)
public abstract class SignTypingMixin {

   @Unique private static final String S1_RENDER = "render(Lnet/minecraft/block/entity/SignBlockEntity;DDDFI)V";

   /** One editing session = one sign instance (the edit screen keeps drawing the same block entity). */
   @Unique private static SignBlockEntity lg$sign;
   @Unique private static final TypingAnim[] lg$lines = new TypingAnim[4];
   @Unique private static int lg$lineY;

   @Unique
   private static boolean lg$editing(SignBlockEntity sign) {
      if (sign == null || sign.getSelectionStart() < 0) return false;
      if (!(MinecraftClient.getInstance().currentScreen instanceof SignEditScreen)) return false;
      if (sign != lg$sign) {                                  // a new sign: its lines start settled, not animating in
         lg$sign = sign;
         java.util.Arrays.fill(lg$lines, null);
      }
      return true;
   }

   @Unique
   private static TypingAnim lg$current(SignBlockEntity sign) {
      int l = sign.getCurrentRow();
      if (l < 0 || l >= lg$lines.length) return null;
      TypingAnim a = lg$lines[l];
      return a == null || a.broken ? null : a;
   }

   /** Ordinal 0 = a line of text; ordinal 1 = the end-of-line "_" caret. */
   @WrapOperation(method = S1_RENDER,
         at = @At(value = "INVOKE", ordinal = 0,
                  target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;FFI)I"))
   private int lg$line(TextRenderer font, String text, float x, float y, int color, Operation<Integer> op,
                       @Local(argsOnly = true) SignBlockEntity sign) {
      if (text == null || !lg$editing(sign)) return op.call(font, text, x, y, color);
      int i = Math.round((y + 20F) / 10F);                    // y = row * 10 - 20
      if (i < 0 || i >= lg$lines.length) return op.call(font, text, x, y, color);
      if (lg$lines[i] == null) lg$lines[i] = new TypingAnim();
      TypingAnim anim = lg$lines[i];
      if (anim.broken) return op.call(font, text, x, y, color);
      if (i == sign.getCurrentRow()) lg$lineY = Math.round(y);
      try {
         anim.extractLabel(font, text, text, 0, Math.round(y), -10000, 10000, color, false);
         return (int) x + font.getStringWidth(text);
      } catch (Throwable t) {
         anim.broken = true;
         System.out.println("[S1mp1e] sign typing animation disabled for a line: " + t);
         return op.call(font, text, x, y, color);
      }
   }

   @WrapOperation(method = S1_RENDER,
         at = @At(value = "INVOKE", ordinal = 1,
                  target = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;FFI)I"))
   private int lg$caretUnderscore(TextRenderer font, String text, float x, float y, int color, Operation<Integer> op,
                                  @Local(argsOnly = true) SignBlockEntity sign) {
      if (lg$editing(sign) && lg$current(sign) != null) return (int) x;
      return op.call(font, text, x, y, color);
   }

   @WrapOperation(method = S1_RENDER,
         at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawableHelper;fill(IIIII)V"))
   private void lg$caretBar(int x0, int y0, int x1, int y1, int color, Operation<Void> op,
                            @Local(argsOnly = true) SignBlockEntity sign) {
      if (lg$editing(sign) && lg$current(sign) != null) return;
      op.call(x0, y0, x1, y1, color);
   }

   /** After the four lines (the {@code depthMask(true)} that closes the text block; the sign matrix is still pushed). */
   @Inject(method = S1_RENDER,
           at = @At(value = "INVOKE", ordinal = 1,
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;depthMask(Z)V"))
   private void lg$caret(SignBlockEntity sign, double x, double y, double z, float tickDelta, int destroyStage,
                         CallbackInfo ci) {
      if (!lg$editing(sign)) return;
      TypingAnim a = lg$current(sign);
      if (a != null) a.drawLabelCaret(sign.getSelectionStart(), lg$lineY);
   }
}
