package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.animation.Spring;
import com.seagull.liquidglass.client.render.GlassPanels;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import com.seagull.liquidglass.client.render.PanelGhost;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookTabButton;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({RecipeBookComponent.class})
public abstract class RecipeBookGlassMixin {
   @Shadow
   private List<RecipeBookTabButton> tabButtons;
   @Shadow
   private RecipeBookTabButton selectedTab;
   private static Spring lg$py1;
   private static Spring lg$py2;
   private static Object lg$comp;

   @Redirect(
      method = {"extractRenderState"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"
      )
   )
   private void lg$glassBook(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier tex, int x, int y, float u, float v, int w, int h, int tw, int th) {
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
         GuiRenderState rs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
         int fb = GlassPanels.fadeByte();
         PanelGhost.remember(x, y, w, h);
         rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), x, y, x + w, y + h, 12, -2144207104 | fb, null));
         if (this.tabButtons != null && !this.tabButtons.isEmpty()) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxY = Integer.MIN_VALUE;
            int tabH = 27;
            int visibleCount = 0;

            for (RecipeBookTabButton t : this.tabButtons) {
               if (t.visible) {
                  visibleCount++;
                  minX = Math.min(minX, t.getX());
                  minY = Math.min(minY, t.getY());
                  maxY = Math.max(maxY, t.getY() + t.getHeight());
                  tabH = t.getHeight();
               }
            }

            if (visibleCount == 0) {
               return;
            }

            int barX0 = minX + 1;
            int barX1 = minX + 28;
            PanelGhost.remember(barX0, minY, barX1 - barX0, maxY - minY);
            rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), barX0, minY, barX1, maxY, 10, -2130706688 | fb, null));
            if (this.selectedTab != null) {
               float ty = (float)this.selectedTab.getY();
               if (lg$comp == this && lg$py1 != null) {
                  lg$py1.setTarget(ty);
                  lg$py2.setTarget(ty);
               } else {
                  lg$comp = this;
                  lg$py1 = new Spring(ty, 55.0F, 1.0F);
                  lg$py2 = new Spring(ty, 30.0F, 1.0F);
               }

               float rem = 0.016666668F;

               while (rem > 0.0F) {
                  float hh = Math.min(rem, 0.008333334F);
                  lg$py1.update(hh);
                  lg$py2.update(hh);
                  rem -= hh;
               }

               if (!Float.isFinite(lg$py1.value()) || !Float.isFinite(lg$py2.value())) {
                  lg$py1.snap(ty);
                  lg$py2.snap(ty);
               }

               int py0 = Math.round(Math.min(lg$py1.value(), lg$py2.value())) + 2;
               int py1v = Math.round(Math.max(lg$py1.value(), lg$py2.value())) + tabH - 2;
               rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), barX0 + 2, py0, barX1 - 2, py1v, 8, -10240 | fb, null));
            }
         }
      } else {
         g.blit(pipeline, tex, x, y, u, v, w, h, tw, th);
      }
   }
}
