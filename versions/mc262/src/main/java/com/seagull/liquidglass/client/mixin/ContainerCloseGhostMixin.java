package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.animation.Fade;
import com.seagull.liquidglass.client.animation.Spring;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import com.seagull.liquidglass.client.render.PanelGhost;
import com.seagull.liquidglass.client.render.TooltipGlass;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({AbstractContainerScreen.class})
public abstract class ContainerCloseGhostMixin {
   @Shadow
   protected Slot hoveredSlot;
   private static Spring lg$hx1;
   private static Spring lg$hx2;
   private static Spring lg$hy1;
   private static Spring lg$hy2;
   private static long lg$hoverNanos = 0L;
   private static boolean lg$hoverActive = false;
   private static final Fade lg$hoverFade = new Fade(0.0F, 100.0F);
   private static final float HOVER_IN_MS = 100.0F;
   private static final float HOVER_OUT_MS = 150.0F;
   private static final HashMap<Long, Fade> lg$drag = new HashMap<>();
   private static final HashSet<Long> lg$dragActive = new HashSet<>();
   private static final float DRAG_IN_MS = 90.0F;
   private static final float DRAG_OUT_MS = 150.0F;

   @Inject(
      method = {"removed()V"},
      at = {@At("HEAD")}
   )
   private void lg$fadeOutStart(CallbackInfo ci) {
      lg$hoverActive = false;
      PanelGhost.trigger();
   }

   @Inject(
      method = {"extractSlotHighlightBack"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void lg$glassHoverBack(GuiGraphicsExtractor g, CallbackInfo ci) {
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         ci.cancel();
         Slot s = this.hoveredSlot;
         boolean hovering = s != null && s.isHighlightable();
         long now = System.nanoTime();
         float dt = lg$hoverNanos == 0L ? 0.016666668F : Math.min(0.1F, (float)(now - lg$hoverNanos) * 1.0E-9F);
         lg$hoverNanos = now;
         if (hovering) {
            float cx = (float)s.x + 8.0F;
            float cy = (float)s.y + 8.0F;
            if (lg$hoverActive && lg$hx1 != null) {
               lg$hx1.setTarget(cx);
               lg$hx2.setTarget(cx);
               lg$hy1.setTarget(cy);
               lg$hy2.setTarget(cy);
            } else {
               if (lg$hx1 != null && lg$hoverFade.value() > 0.05F) {
                  lg$hx1.setTarget(cx);
                  lg$hx2.setTarget(cx);
                  lg$hy1.setTarget(cy);
                  lg$hy2.setTarget(cy);
               } else {
                  lg$hx1 = new Spring(cx, 55.0F, 1.0F);
                  lg$hx2 = new Spring(cx, 30.0F, 1.0F);
                  lg$hy1 = new Spring(cy, 55.0F, 1.0F);
                  lg$hy2 = new Spring(cy, 30.0F, 1.0F);
               }

               lg$hoverActive = true;
            }

            lg$hoverFade.to(1.0F, 100.0F);
         } else {
            lg$hoverActive = false;
            lg$hoverFade.to(0.0F, 150.0F);
            if (!lg$hoverFade.isVisible() || lg$hx1 == null) {
               return;
            }
         }

         float rem = dt;

         while (rem > 0.0F) {
            float h = Math.min(rem, 0.008333334F);
            lg$hx1.update(h);
            lg$hx2.update(h);
            lg$hy1.update(h);
            lg$hy2.update(h);
            rem -= h;
         }

         if (!Float.isFinite(lg$hx1.value()) || !Float.isFinite(lg$hx2.value()) || !Float.isFinite(lg$hy1.value()) || !Float.isFinite(lg$hy2.value())) {
            lg$hx1.snap(lg$hx1.target());
            lg$hx2.snap(lg$hx2.target());
            lg$hy1.snap(lg$hy1.target());
            lg$hy2.snap(lg$hy2.target());
         }

         int x0 = Math.round(Math.min(lg$hx1.value(), lg$hx2.value()) - 12.0F);
         int x1 = Math.round(Math.max(lg$hx1.value(), lg$hx2.value()) + 12.0F);
         int y0 = Math.round(Math.min(lg$hy1.value(), lg$hy2.value()) - 12.0F);
         int y1 = Math.round(Math.max(lg$hy1.value(), lg$hy2.value()) + 12.0F);
         int ab = Math.round(lg$hoverFade.value() * 255.0F) & 0xFF;
         int col = -8192 | ab;
         TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
         ((GuiGraphicsExtractorAccessor)g)
            .liquidglass$guiRenderState()
            .addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), x0, y0, x1, y1, 8, col, null));
      }
   }

   @Inject(
      method = {"extractSlotHighlightFront"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void lg$glassHoverFront(GuiGraphicsExtractor g, CallbackInfo ci) {
      if (GlassPipeline.usable()) {
         ci.cancel();
      }
   }

   @Redirect(
      method = {"extractSlot"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"
      )
   )
   private void lg$dragHighlight(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
      if (!GlassPipeline.usable()) {
         g.fill(x0, y0, x1, y1, color);
      } else {
         long key = (long)x0 << 32 | (long)y0 & 4294967295L;
         Fade f = lg$drag.get(key);
         if (f == null) {
            f = new Fade(0.0F, 90.0F);
            lg$drag.put(key, f);
         }

         f.to(1.0F, 90.0F);
         lg$dragActive.add(key);
         lg$drawDragQuad(g, x0, y0, f.value());
      }
   }

   private static void lg$drawDragQuad(GuiGraphicsExtractor g, int x0, int y0, float a) {
      int b = Math.round(a * 127.0F) & 0xFF;
      int col = -65536 | b;
      TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
      ((GuiGraphicsExtractorAccessor)g)
         .liquidglass$guiRenderState()
         .addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), x0 - 1, y0 - 1, x0 + 17, y0 + 17, 0, col, null));
   }

   @Inject(
      method = {"extractSlotHighlightBack"},
      at = {@At("TAIL")}
   )
   private void lg$dragGhosts(GuiGraphicsExtractor g, CallbackInfo ci) {
      TooltipGlass.ghostPass(g);
      if (GlassPipeline.usable() && !lg$drag.isEmpty()) {
         Iterator<Entry<Long, Fade>> it = lg$drag.entrySet().iterator();

         while (it.hasNext()) {
            Entry<Long, Fade> e = it.next();
            if (!lg$dragActive.contains(e.getKey())) {
               Fade f = e.getValue();
               f.to(0.0F, 150.0F);
               float a = f.value();
               if (a <= 0.004F && f.isIdle()) {
                  it.remove();
               } else {
                  lg$drawDragQuad(g, (int)(e.getKey() >> 32), (int)e.getKey().longValue(), a);
               }
            }
         }

         lg$dragActive.clear();
      } else {
         lg$dragActive.clear();
      }
   }
}
