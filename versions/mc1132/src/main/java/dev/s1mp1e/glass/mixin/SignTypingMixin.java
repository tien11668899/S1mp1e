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
 * <p><b>1.13.2.</b> The edit screen draws no text itself: {@code SignEditScreen.render} hands the sign to
 * {@code BlockEntityRenderDispatcher}, and {@code SignBlockEntityRenderer.method_10107} (javap-read) draws, inside the
 * sign's scaled space, each line with ONE {@code TextRenderer.method_18355(String, x = -width/2, y = row*10 - 20,
 * color)}. There is no cursor and no selection yet (typing appends, backspace removes the last char); the edited row is
 * marked by wrapping it in {@code "> "} / {@code " <"}, blinking every 6 ticks through {@code sign.lineBeingEdited}.
 * So, only while the sign edit screen is open and for the sign it edits:
 * <ul>
 *   <li>the line draw is wrapped into {@link TypingAnim#extractLabel} (drawn immediately, under the same GL matrix),
 *       without the vanilla markers. The row comes from the line's y;</li>
 *   <li>the animated capsule caret is drawn once after the text loop at the end of the row being typed into (the edit
 *       screen's {@code currentRow}), before the sign transform is popped — it replaces the blinking markers.</li>
 * </ul>
 * If the animated path throws, that line falls back to vanilla for good.
 */
@Mixin(SignBlockEntityRenderer.class)
public abstract class SignTypingMixin {
   @Unique private static final String S1_RENDER = "method_10107(Lnet/minecraft/block/entity/SignBlockEntity;DDDFI)V";

   /** One editing session = one sign instance (the edit screen keeps drawing the same block entity). */
   @Unique private static SignBlockEntity lg$sign;
   @Unique private static final TypingAnim[] lg$lines = new TypingAnim[4];
   @Unique private static int lg$lineY;
   @Unique private static int lg$caretAt;
   @Unique private static int lg$row = -1;

   @Unique
   private static boolean lg$editing(SignBlockEntity sign) {
      if (sign == null) return false;
      net.minecraft.client.gui.screen.Screen scr = MinecraftClient.getInstance().currentScreen;
      if (!(scr instanceof SignEditScreen)) return false;
      SignEditScreenAccessor acc = (SignEditScreenAccessor) scr;
      if (acc.s1mp1e$sign() != sign) return false;
      lg$row = acc.s1mp1e$currentRow();
      if (sign != lg$sign) {                                  // a new sign: its lines start settled, not animating in
         lg$sign = sign;
         java.util.Arrays.fill(lg$lines, null);
      }
      return true;
   }

   @Unique
   private static TypingAnim lg$current() {
      int l = lg$row;
      if (l < 0 || l >= lg$lines.length) return null;
      TypingAnim a = lg$lines[l];
      return a == null || a.broken ? null : a;
   }

   @WrapOperation(method = S1_RENDER,
         at = @At(value = "INVOKE",
                  target = "Lnet/minecraft/client/font/TextRenderer;method_18355(Ljava/lang/String;FFI)I"))
   private int lg$line(TextRenderer font, String text, float x, float y, int color, Operation<Integer> op,
                       @Local(argsOnly = true) SignBlockEntity sign) {
      if (text == null || !lg$editing(sign)) return op.call(font, text, x, y, color);
      int i = Math.round((y + 20F) / 10F);                    // y = row * 10 - 20
      if (i < 0 || i >= lg$lines.length) return op.call(font, text, x, y, color);
      if (lg$lines[i] == null) lg$lines[i] = new TypingAnim();
      TypingAnim anim = lg$lines[i];
      if (anim.broken) return op.call(font, text, x, y, color);
      String plain = text;
      if (i == sign.lineBeingEdited && plain.length() >= 4 && plain.startsWith("> ") && plain.endsWith(" <")) {
         plain = plain.substring(2, plain.length() - 2);      // the vanilla blink markers: the capsule caret stands in
      }
      if (i == lg$row) { lg$lineY = Math.round(y); lg$caretAt = plain.length(); }
      try {
         anim.extractLabel(font, plain, plain, 0, Math.round(y), -10000, 10000, color, false);
         return (int) x + font.getStringWidth(text);
      } catch (Throwable t) {
         anim.broken = true;
         System.out.println("[S1mp1e] sign typing animation disabled for a line: " + t);
         return op.call(font, text, x, y, color);
      }
   }

   /** After the four lines (the {@code depthMask(true)} that closes the text block; the sign matrix is still pushed). */
   @Inject(method = S1_RENDER,
           at = @At(value = "INVOKE", ordinal = 1,
                    target = "Lcom/mojang/blaze3d/platform/GlStateManager;depthMask(Z)V"))
   private void lg$caret(SignBlockEntity sign, double x, double y, double z, float tickDelta, int destroyStage,
                         CallbackInfo ci) {
      if (!lg$editing(sign)) return;
      TypingAnim a = lg$current();
      if (a != null) a.drawLabelCaret(lg$caretAt, lg$lineY);
   }
}
