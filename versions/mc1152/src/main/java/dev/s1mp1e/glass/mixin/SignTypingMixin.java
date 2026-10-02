package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.render.GuiFlush;
import dev.s1mp1e.glass.render.TypingAnim;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.SelectionManager;
import net.minecraft.client.util.math.Matrix4f;
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
 * <p><b>1.15.2.</b> As on 1.16.5 the edit screen draws the sign itself (1.14.4 leaves it to the block entity renderer):
 * {@code SignEditScreen.render(int, int, float)} builds a LOCAL {@code MatrixStack} (the screen methods take none before
 * 1.16), queues the board model and then each line with the long
 * {@code TextRenderer.draw(String, x = -width/2, y, color, false, matrix, immediate, false, 0, light)} (ten arguments here,
 * no right-to-left flag) into the shared entity vertex consumers, queues a {@code "_"} the same way when the caret is at
 * the end of the current line, flushes the batch, and then draws the within-line caret as a
 * {@code fill(Matrix4f, ...)} bar plus the selection quad (javap-read). Here:
 * <ul>
 *   <li>the line draw is wrapped into {@link TypingAnim#extractLabel}. The label code draws immediately through the
 *       fixed-function model-view, and the sign transform only exists in that local stack, so the wrapped call's
 *       {@code Matrix4f} is multiplied onto the GL model-view for the label (the queued board is flushed first so the
 *       glyphs land on top of it). The row comes from the line's y ({@code row * 10 - 20}), so a skipped (null) row
 *       cannot shift the others;</li>
 *   <li>both vanilla caret forms are suppressed and the animated capsule is drawn once after the text, just before the
 *       sign transform is popped (its own alpha blink replaces vanilla's 6-tick on/off), under the same matrix.</li>
 * </ul>
 * If the animated path throws, that line falls back to vanilla for good.
 */
@Mixin(SignEditScreen.class)
public abstract class SignTypingMixin {

   private static final String DRAW = "Lnet/minecraft/client/font/TextRenderer;draw(Ljava/lang/String;FFIZ"
         + "Lnet/minecraft/client/util/math/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;ZII)I";

   @Shadow private int currentRow;
   @Shadow private SelectionManager selectionManager;

   @Unique private final TypingAnim[] lg$lines = new TypingAnim[4];
   @Unique private int lg$lineY;
   /** The sign's text-space matrix of this frame (from the first wrapped line draw); null until a line was drawn. */
   @Unique private Matrix4f lg$matrix;

   @Unique private final boolean[] lg$seen = new boolean[4];

   @Inject(method = "render", at = @At("HEAD"))
   private void lg$frame(int mouseX, int mouseY, float delta, CallbackInfo ci) {
      lg$matrix = null;
      java.util.Arrays.fill(lg$seen, false);
   }

   @WrapOperation(method = "render", at = @At(value = "INVOKE", target = DRAW))
   private int lg$line(TextRenderer font, String text, float x, float y, int color, boolean shadow, Matrix4f matrix,
                       VertexConsumerProvider consumers, boolean seeThrough, int background, int light,
                       Operation<Integer> op) {
      int i = Math.round((y + 20F) / 10F);                  // vanilla: y = row * 10 - text.length * 5 (4 rows)
      if (i < 0 || i >= lg$lines.length || text == null || matrix == null) {
         return op.call(font, text, x, y, color, shadow, matrix, consumers, seeThrough, background, light);
      }
      // The second string queued for a row in one frame is vanilla's end-of-line "_" caret (the first is the line,
      // even when the line itself reads "_"): the animated capsule replaces it.
      boolean second = lg$seen[i];
      lg$seen[i] = true;
      if (second) {
         if (lg$current() != null) return (int) x;
         return op.call(font, text, x, y, color, shadow, matrix, consumers, seeThrough, background, light);
      }
      if (lg$lines[i] == null) lg$lines[i] = new TypingAnim();
      TypingAnim anim = lg$lines[i];
      if (anim.broken) {
         return op.call(font, text, x, y, color, shadow, matrix, consumers, seeThrough, background, light);
      }
      if (i == this.currentRow) lg$lineY = Math.round(y);
      lg$matrix = matrix;
      GuiFlush.flush();                                     // the queued board first, or it would cover the glyphs
      RenderSystem.pushMatrix();
      RenderSystem.multMatrix(matrix);
      try {
         anim.extractLabel(font, text, text, 0, Math.round(y), -10000, 10000, color, false);
         return (int) x + font.getStringWidth(text);
      } catch (Throwable t) {
         anim.broken = true;
         System.out.println("[S1mp1e] sign typing animation disabled for a line: " + t);
         return op.call(font, text, x, y, color, shadow, matrix, consumers, seeThrough, background, light);
      } finally {
         RenderSystem.popMatrix();
      }
   }

   /** The within-line caret bar is suppressed when animated; the animated caret is drawn after the text. */
   @Redirect(
      method = "render",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/screen/ingame/SignEditScreen;fill(Lnet/minecraft/client/util/math/Matrix4f;IIIII)V")
   )
   private void lg$caretBar(Matrix4f m, int x0, int y0, int x1, int y1, int color) {
      if (lg$current() == null) DrawableHelper.fill(m, x0, y0, x1, y1, color);
   }

   /** Ordinal 1 = the pop that closes the sign transform (ordinal 0 closes the board model's own push). */
   @Inject(method = "render",
           at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;pop()V", ordinal = 1))
   private void lg$caret(int mouseX, int mouseY, float delta, CallbackInfo ci) {
      TypingAnim a = lg$current();
      if (a == null || lg$matrix == null) return;
      RenderSystem.pushMatrix();
      RenderSystem.multMatrix(lg$matrix);
      try {
         a.drawLabelCaret(this.selectionManager.getSelectionStart(), lg$lineY);
      } finally {
         RenderSystem.popMatrix();
      }
   }

   @Unique
   private TypingAnim lg$current() {
      int l = this.currentRow;
      if (l < 0 || l >= lg$lines.length) return null;
      TypingAnim a = lg$lines[l];
      return a == null || a.broken ? null : a;
   }
}
