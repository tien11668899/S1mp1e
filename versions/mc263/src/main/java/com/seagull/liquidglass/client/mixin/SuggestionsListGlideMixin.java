package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Command-suggestion popup: a soft highlight bar glides to the selected suggestion (Tab / arrows / hover) and each row's
 * text colour follows it, easing between vanilla's grey and yellow instead of flipping. The popup paints row background
 * then row text, one row at a time, so a single bar drawn once would be covered by later rows' backgrounds: instead each
 * 12 px row fill is followed by the part of the (moving) bar that overlaps that row — a bar straddling two rows is drawn
 * as two pieces that meet exactly.
 */
@Mixin(CommandSuggestions.SuggestionsList.class)
public abstract class SuggestionsListGlideMixin {

   @Unique private static final float LG_TAU = 0.06F;
   @Unique private static final int LG_ROW = 12;
   @Unique private static final int LG_BAR = 0x26FFFFFF;
   @Unique private static final int LG_GREY = 0xFFAAAAAA;
   @Unique private static final int LG_YELLOW = 0xFFFFFF00;

   @Shadow @Final private Rect2i rect;
   @Shadow private int offset;
   @Shadow private int current;

   @Unique private float lg$sel = Float.NaN;   // eased selected index (list space)
   @Unique private long lg$ns;

   @Inject(method = "extractRenderState", at = @At("HEAD"))
   private void lg$easeSelection(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
      long now = net.minecraft.util.Util.getNanos();
      if (Float.isNaN(lg$sel)) {
         lg$sel = this.current;
      } else {
         float dt = lg$ns == 0L ? 1F / 60F : Math.min(0.05F, (now - lg$ns) / 1.0e9F);
         lg$sel += (this.current - lg$sel) * (1F - (float) Math.exp(-dt / LG_TAU));
         if (Math.abs(this.current - lg$sel) < 0.01F) lg$sel = this.current;
      }
      lg$ns = now;
   }

   /** After each 12 px row background, the slice of the gliding bar that lies over that row. */
   @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
   private void lg$rowFillThenBar(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color, Operation<Void> op) {
      op.call(g, x0, y0, x1, y1, color);
      if (y1 - y0 != LG_ROW || Float.isNaN(lg$sel)) return;          // only the row backgrounds (the "more" dots are 1 px)
      float barTop = this.rect.getY() + LG_ROW * (lg$sel - this.offset);
      int top = Math.max(y0, Math.round(barTop));
      int bottom = Math.min(y1, Math.round(barTop) + LG_ROW);
      if (bottom > top) g.fill(x0, top, x1, bottom, LG_BAR);
   }

   /** Row text colour: grey → yellow by how close the gliding highlight is to this row. */
   @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
   private void lg$rowText(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String text, int x, int y, int color,
                           Operation<Void> op) {
      if (Float.isNaN(lg$sel)) { op.call(g, font, text, x, y, color); return; }
      int k = (y - 2 - this.rect.getY()) / LG_ROW;
      float w = 1F - Math.abs(k + this.offset - lg$sel);
      w = w < 0F ? 0F : (w > 1F ? 1F : w);
      op.call(g, font, text, x, y, lerp(LG_GREY, LG_YELLOW, w));
   }

   @Unique
   private static int lerp(int a, int b, float t) {
      int ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
      int br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
      return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8 | Math.round(ab + (bb - ab) * t);
   }
}
