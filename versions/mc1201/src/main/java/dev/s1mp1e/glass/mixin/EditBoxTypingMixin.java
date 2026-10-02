package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.s1mp1e.glass.render.TypingAnim;
import java.util.function.BiFunction;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Silky typing for every {@link TextFieldWidget} (chat input, search fields, anvil rename, world/server names …). All
 * drawing and state lives in {@link TypingAnim} (one per box): per-glyph entrance (blur&rarr;sharp + rise + fade on one
 * spring), exit on delete, neighbours gliding after a mid-string edit, eased horizontal scroll, and the Apple caret /
 * selection locked to the animated text.
 *
 * <p>1.20.1 / 1.21.1 are immediate-mode (DrawContext), so instead of the 26.2 deferred-render hooks the mixin works on the direct
 * draw calls of {@code renderButton}:
 * <ol>
 *   <li>At the {@code TextRenderer.trimToWidth} call (after the box background, before caret / highlight) the whole text is
 *       drawn by {@link TypingAnim#extractText}; the full string is formatted with the box's own {@code renderTextProvider}
 *       so chat command highlighting keeps its colours.</li>
 *   <li>Vanilla's two {@code drawTextWithShadow(OrderedText)} draws (before / after the caret) are replaced by that pass —
 *       they return the position vanilla would, so the (unused) cursor arithmetic stays sane.</li>
 *   <li>The vanilla caret (the {@code fill(RenderLayer …)} vertical bar and the {@code "_"} append) is suppressed; the
 *       animated Apple caret is drawn at TAIL when the box is focused (its own smooth alpha blink replaces vanilla's on/off
 *       blink, so it need not be gated by vanilla's blink phase). The grey suggestion follows the eased scroll.</li>
 *   <li>The selection highlight is drawn with gliding edges in the animated text's coordinates.</li>
 * </ol>
 * If the animated path ever throws, that box falls back to vanilla drawing for good ({@link TypingAnim#broken}).
 */
@Mixin(TextFieldWidget.class)
public abstract class EditBoxTypingMixin {

   @Shadow @org.spongepowered.asm.mixin.Final private TextRenderer textRenderer;
   @Shadow private String text;
   @Shadow private int firstCharacterIndex;
   @Shadow private int selectionStart;
   @Shadow private int selectionEnd;
   @Shadow private int editableColor;
   @Shadow private int uneditableColor;
   @Shadow private boolean editable;
   @Shadow private boolean drawsBackground;
   @Shadow private BiFunction<String, Integer, OrderedText> renderTextProvider;

   @Shadow public abstract int getInnerWidth();

   @Unique private TypingAnim lg$anim;

   @Unique
   private boolean lg$animated() {
      return lg$anim != null && !lg$anim.broken;
   }

   @Unique
   private int lg$textX() {
      TextFieldWidget self = (TextFieldWidget) (Object) this;
      return this.drawsBackground ? self.getX() + 4 : self.getX();
   }

   @Unique
   private int lg$textY() {
      TextFieldWidget self = (TextFieldWidget) (Object) this;
      return this.drawsBackground ? self.getY() + (self.getHeight() - 8) / 2 : self.getY();
   }

   /** 1. The whole text, glyph by glyph, animated. */
   @Inject(
      method = "renderButton",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/font/TextRenderer;trimToWidth(Ljava/lang/String;I)Ljava/lang/String;")
   )
   private void lg$typingText(DrawContext g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (lg$anim == null) lg$anim = new TypingAnim();
      if (lg$anim.broken) return;
      try {
         int color = this.editable ? this.editableColor : this.uneditableColor;
         OrderedText formatted = this.renderTextProvider.apply(this.text, 0);
         lg$anim.extractText(g, this.textRenderer, this.text, formatted, this.firstCharacterIndex,
               this.selectionStart, lg$textX(), lg$textY(), this.getInnerWidth(), color, false);
      } catch (Throwable t) {
         lg$anim.broken = true;
         System.out.println("[S1mp1e] typing animation disabled for a TextFieldWidget: " + t);
      }
   }

   /** 2. Vanilla's before/after-caret text draws are replaced by the pass above (return vanilla's x so cursor maths hold). */
   @Redirect(
      method = "renderButton",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;III)I")
   )
   private int lg$vanillaText(DrawContext g, TextRenderer font, OrderedText seq, int x, int y, int color) {
      if (lg$animated()) return x + font.getWidth(seq);
      return g.drawTextWithShadow(font, seq, x, y, color);
   }

   /** 3a. The vanilla within-text caret bar is suppressed; the animated caret draws at TAIL. */
   @Redirect(
      method = "renderButton",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/DrawContext;fill(Lnet/minecraft/client/render/RenderLayer;IIIII)V")
   )
   private void lg$caretBar(DrawContext g, RenderLayer layer, int x0, int y0, int x1, int y1, int color) {
      if (lg$animated()) return;
      g.fill(layer, x0, y0, x1, y1, color);
   }

   /** 3b. The {@code "_"} append caret is suppressed too; the grey suggestion follows the eased scroll. */
   @Redirect(
      method = "renderButton",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)I")
   )
   private int lg$stringText(DrawContext g, TextRenderer font, String text, int x, int y, int color) {
      if (!lg$animated()) return g.drawTextWithShadow(font, text, x, y, color);
      if ("_".equals(text)) return x;                       // end-of-text caret: the animated capsule replaces it
      float d = lg$anim.scrollDelta();
      if (Math.abs(d) < 0.01F) return g.drawTextWithShadow(font, text, x, y, color);
      g.getMatrices().push();
      g.getMatrices().translate(d, 0F, 0F);
      try { return g.drawTextWithShadow(font, text, x, y, color); } finally { g.getMatrices().pop(); }
   }

   /** 3c. Selection highlight with gliding edges, in the animated text's coordinates. */
   @WrapOperation(
      method = "renderButton",
      at = @At(value = "INVOKE",
               target = "Lnet/minecraft/client/gui/widget/TextFieldWidget;drawSelectionHighlight(Lnet/minecraft/client/gui/DrawContext;IIII)V")
   )
   private void lg$highlight(TextFieldWidget self, DrawContext g, int x0, int y0, int x1, int y1, Operation<Void> op) {
      if (lg$animated()) lg$anim.drawHighlight(g, this.selectionEnd, y0, y1);
      else op.call(self, g, x0, y0, x1, y1);
   }

   /**
    * 5. Frame glass (26.2's {@code EditBoxFrameGlassMixin}; merged here because both hook {@code renderButton} of the
    * same class). A bordered text field draws its frame as an opaque black box with a 1 px outline — in 1.20.1 two
    * plain {@code fill}s (outline rect, then the black inside; the only two {@code fill(IIIII)} of
    * {@code renderButton}, the caret bar is the {@code fill(RenderLayer…)} overload) where later versions blit one
    * sprite. While {@link dev.s1mp1e.glass.render.EditBoxGlass#frame} is set — around the recipe book's search
    * field, see {@code RecipeBookGlassMixin} — and on the world-select screen (its search box), the outline fill
    * becomes a rounded frosted scrim and the black inside is dropped, so the field reads as part of the glass. Text,
    * hint and caret are untouched (and keep the bordered inset). Every other text field keeps its vanilla frame.
    */
   @Redirect(
      method = "renderButton",
      at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V")
   )
   private void lg$glassFrame(DrawContext g, int x0, int y0, int x1, int y1, int color) {
      if (dev.s1mp1e.glass.render.GlassProgram.roundUsable()
            && (dev.s1mp1e.glass.render.EditBoxGlass.frame
                || net.minecraft.client.MinecraftClient.getInstance().currentScreen
                      instanceof net.minecraft.client.gui.screen.world.SelectWorldScreen)) {
         if (color == 0xFF000000) return;                 // the black inside: the scrim below stands in for both
         boolean focused = ((TextFieldWidget) (Object) this).isFocused();
         g.draw();   // the immediate rounded scrim must land over what was queued before it
         dev.s1mp1e.client.gui.GlassWidgets.fillRound(g, x0, y0, x1, y1, focused ? 0x4DFFFFFF : 0x2EFFFFFF, 4.0F);
      } else {
         g.fill(x0, y0, x1, y1, color);
      }
   }

   /** 4. The animated Apple caret, on top, when focused (its own alpha blink handles visibility). */
   @Inject(method = "renderButton", at = @At("TAIL"))
   private void lg$caret(DrawContext g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
      if (lg$animated() && ((TextFieldWidget) (Object) this).isFocused()) lg$anim.drawCaret(g);
   }
}
