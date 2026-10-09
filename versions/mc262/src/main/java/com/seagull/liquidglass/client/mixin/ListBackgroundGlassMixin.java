package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every list drew vanilla's menu-list background texture: a darker band (out of a world, a dirt-tinted tile) between the
 * header and footer. Full-width lists (worlds, servers, game rules, …) now draw nothing there — the rows sit on the
 * blurred backdrop like an Apple sidebar-less list. A narrow list that is one pane of a multi-pane screen (the two
 * resource-pack columns) gets a glass pane instead, with a grey scrim for readable text.
 */
@Mixin(AbstractSelectionList.class)
public abstract class ListBackgroundGlassMixin {

   @Inject(method = "extractListBackground", at = @At("HEAD"), cancellable = true)
   private void lg$noBand(GuiGraphicsExtractor g, CallbackInfo ci) {
      if (!(GlassPipeline.ensureReady() && GlassPipeline.usable())) return;
      ci.cancel();
      AbstractWidget self = (AbstractWidget) (Object) this;
      int screenW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
      if (self.getWidth() < screenW * 0.7F) {
         int x0 = self.getX(), y0 = self.getY(), x1 = self.getRight(), y1 = self.getBottom();
         // hotbar corner (new glass pieces use it): the size-relative panel corner went huge on a tall fullscreen pane
         float r = com.seagull.liquidglass.client.render.GlassCorners.HOTBAR_RADIUS;
         if (GlassSurface.plate(g, x0, y0, x1, y1, 0xFF, r)) {
            GlassSurface.scrim(g, x0, y0, x1, y1, r, 0x30000000);
         }
      }
   }
}
