package com.seagull.liquidglass.client.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.animation.Spring;
import com.seagull.liquidglass.client.render.GlassPanels;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import com.seagull.liquidglass.client.render.PanelGhost;
import com.seagull.liquidglass.client.render.RecipeBookSlide;
import dev.s1mp1e.client.gui.RecipeBookHost;
import com.seagull.liquidglass.client.render.ScreenTransition;
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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({RecipeBookComponent.class})
public abstract class RecipeBookGlassMixin implements RecipeBookHost {
   @Shadow
   private List<RecipeBookTabButton> tabButtons;
   @Shadow
   private RecipeBookTabButton selectedTab;
   private static Spring lg$py1;
   private static Spring lg$py2;
   private static Object lg$comp;
   private static long lg$lastNs;

   @Shadow private boolean widthTooNarrow;

   @Shadow
   private int getXOrigin() {
      throw new AssertionError();
   }

   @Override
   public int liquidglass$xOrigin() {
      return this.getXOrigin();
   }

   /** Book body is 147 wide from the x origin; the tab column sticks ~26 px further left, so the visual centre sits a
    *  touch left of the body centre. */
   @Override
   public int liquidglass$bookCenterX() {
      return this.getXOrigin() + 60;
   }

   @Override
   public int liquidglass$bookCenterY() {
      int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
      if (this.tabButtons != null) {
         for (RecipeBookTabButton t : this.tabButtons) {
            if (t.visible) {
               minY = Math.min(minY, t.getY());
               maxY = Math.max(maxY, t.getY() + t.getHeight());
            }
         }
      }
      if (minY > maxY) return 130;
      return (minY + maxY) / 2;
   }

   @Override
   public int liquidglass$bookRight() {
      return this.getXOrigin() + 147;   // vanilla recipe-book body width
   }

   /**
    * Opening/closing the book: arm the slide-from-behind-the-inventory ({@link RecipeBookSlide}); the screen starts it on
    * the frame it sees its leftPos move. In a window too narrow for the book to sit beside the inventory (it overlays the
    * inventory instead) there is nothing to slide out from under — keep the snapshot cross-dissolve there.
    */
   @Inject(method = "toggleVisibility", at = @At("HEAD"))
   private void lg$toggle(CallbackInfo ci) {
      if (this.widthTooNarrow) {
         ScreenTransition.onTabSwitch();
      } else {
         RecipeBookSlide.arm(this, !((RecipeBookComponent<?>) (Object) this).isVisible());
      }
   }

   /** While sliding shut, keep drawing although vanilla already marked the book hidden. */
   @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/screens/recipebook/RecipeBookComponent;isVisible()Z"))
   private boolean lg$visibleWhileClosing(RecipeBookComponent<?> self) {
      return self.isVisible() || RecipeBookSlide.closing(this);
   }

   /** The search field: its opaque vanilla frame sprite becomes a frosted glass scrim (EditBoxFrameGlassMixin). */
   @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
         target = "Lnet/minecraft/client/gui/components/EditBox;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
   private void lg$glassSearch(net.minecraft.client.gui.components.EditBox box, GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
      com.seagull.liquidglass.client.render.EditBoxGlass.frame = true;
      try {
         box.extractRenderState(g, mouseX, mouseY, delta);
      } finally {
         com.seagull.liquidglass.client.render.EditBoxGlass.frame = false;
      }
   }

   @Redirect(
      method = {"extractRenderState"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"
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
                  lg$lastNs = 0L;
               }

               // Advance by REAL elapsed time, as 1.21.1 does (Spring.advance: clamp 0.05 s, 1/120 s sub-steps). The
               // recovered code stepped a fixed 1/60 s per frame, so at 100+ fps the pill ran ~2x fast and the
               // lead/trail stretch (the liquid look) all but vanished; a second extraction in one frame is now free.
               long now = net.minecraft.util.Util.getNanos();
               float dt = lg$lastNs == 0L ? 1.0F / 60.0F : (now - lg$lastNs) / 1.0E9F;
               lg$lastNs = now;
               float rem = Math.min(dt, 0.05F) * RecipeBookSlide.timeScale;

               while (rem > 0.0F) {
                  float hh = Math.min(rem, 1.0F / 120.0F);
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
