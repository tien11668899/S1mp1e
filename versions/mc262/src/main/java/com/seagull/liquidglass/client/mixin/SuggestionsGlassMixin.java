package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.seagull.liquidglass.client.render.GlassCorners;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The command auto-complete popup (chat {@code /…}, command blocks) was a stack of opaque black row fills with dotted
 * white "more above / below" rules and the selected suggestion in yellow. It becomes one glass panel (grey scrim on top
 * so the text stays readable), the selected suggestion sits on a glass highlight in white, and the dotted rules go —
 * the popup still scrolls with the wheel / arrows exactly as before.
 */
@Mixin(targets = "net.minecraft.client.gui.components.CommandSuggestions$SuggestionsList")
public abstract class SuggestionsGlassMixin {

   @Shadow @Final private Rect2i rect;
   @Shadow private int offset;
   @Shadow private int current;

   @Unique private boolean lg$panelDrawn;

   @Inject(method = "extractRenderState", at = @At("HEAD"))
   private void lg$panel(GuiGraphicsExtractor g, int mouseX, int mouseY, CallbackInfo ci) {
      this.lg$panelDrawn = false;
      int x0 = this.rect.getX() - 1, y0 = this.rect.getY() - 1;
      int x1 = this.rect.getX() + this.rect.getWidth() + 1, y1 = this.rect.getY() + this.rect.getHeight() + 1;
      if (!GlassSurface.plate(g, x0, y0, x1, y1)) return;
      this.lg$panelDrawn = true;
      GlassSurface.scrim(g, x0, y0, x1, y1, Math.min(x1 - x0, y1 - y0) / 2.0F * 0.5F * 0.24F, 0x78000000);
      int row = this.current - this.offset;
      int rows = this.rect.getHeight() / 12;
      if (row >= 0 && row < rows && GlassPipeline.btnUsable()) {
         float ry = this.rect.getY() + row * 12;
         float corner = Math.min(1.0F, GlassCorners.HOTBAR_RADIUS / 6.0F);
         GlassWidgets.capsule(g, this.rect.getX(), ry, this.rect.getX() + this.rect.getWidth(), ry + 12.0F, corner, 0.81F, 1.0F, true);
      }
   }

   /** Every background row and dotted indicator is replaced by the panel drawn at HEAD. */
   @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
   private void lg$noRows(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argb, Operation<Void> original) {
      if (!this.lg$panelDrawn) original.call(g, x0, y0, x1, y1, argb);
   }

   /** Selected suggestion: white on the highlight instead of yellow. */
   @WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"))
   private void lg$selectedWhite(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color, Operation<Void> original) {
      int c = color;
      if (this.lg$panelDrawn) c = color == 0xFFFFFF00 ? 0xFFFFFFFF : (color == 0xFFAAAAAA ? 0xFFE0E0E0 : color);   // readable on glass
      original.call(g, font, s, x, y, c);
   }
}
