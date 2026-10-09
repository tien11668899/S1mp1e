package com.seagull.liquidglass.client.render;

import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/**
 * Shared liquid-glass surfaces for the SCREENS that are not {@link net.minecraft.client.gui.screens.inventory.AbstractContainerScreen}s
 * (advancements, book, stats, social, ...). {@link GlassPanels} already covers container screens (it also draws the slot
 * separators and owns the per-screen open fade); this class is the same refracting quad without the slot machinery, so a
 * non-container screen mixin can enqueue one panel with the identical look.
 *
 * <p>The look is kept identical to {@link GlassPanels}: the {@link #PANEL_KNOBS} colour int is the four glass shader knobs
 * (A = frost .5, R = corner .24, G = 1 &minus; lift, B = opacity), padded {@link #PANEL_PAD} px for the AA/edge-shadow bleed,
 * refracting the frame's backdrop grab. Every call gates on {@link GlassPipeline#usable()}; a caller that gets {@code false}
 * from {@link #plate} must fall back to {@link GlassPainter} so a surface never vanishes.
 *
 * <p>The grab happens once per frame at {@code GuiRenderer.render()} HEAD, before any GUI element is flushed, so glass never
 * samples glass here (all of these surfaces are render-states flushed after the grab). Ordering inside a frame is pure
 * insertion order (the pose is a 2-D {@code Matrix3x2f}, there is no Z), so a caller enqueues the plate BEFORE the text and
 * icons that must sit on top.
 */
public final class GlassSurface {
   private GlassSurface() {
   }

   /** Container-panel knob recipe (frost .5, corner .24, no lift); the low byte is the runtime opacity. Matches {@link GlassPanels}. */
   public static final int PANEL_KNOBS = 0x803DFF00;
   /** AA / edge-shadow bleed around a panel, in GUI px (same as {@link GlassPanels}). */
   public static final int PANEL_PAD = 12;

   /**
    * Enqueue a refracting glass panel spanning [{@code x0},{@code y0}] .. [{@code x1},{@code y1}] (absolute scaled-GUI px
    * under the current pose) at the given opacity byte.
    *
    * @return {@code true} when the panel was enqueued; {@code false} when the glass pipeline is not usable (the caller must
    *         then paint a {@link GlassPainter} fallback so the surface never disappears).
    */
   public static boolean plate(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int opacityByte) {
      if (x1 <= x0 || y1 <= y0) {
         return false;
      }
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         GuiRenderState rs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
         TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
         rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), x0, y0, x1, y1, PANEL_PAD, PANEL_KNOBS | (opacityByte & 0xFF), null));
         return true;
      }
      return false;
   }

   /**
    * {@link #plate} with an explicit corner radius in GUI px (capped at the shader's own 0.24 fraction), for panes whose
    * size varies with the window — the size-relative default corner grows far too round on a big, fullscreen pane.
    */
   public static boolean plate(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int opacityByte, float radiusPx) {
      if (x1 <= x0 || y1 <= y0) {
         return false;
      }
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         // shader radius = minHalf * 0.5 * corner  →  corner = radius / (minHalf * 0.5)
         float minHalf = Math.min(x1 - x0, y1 - y0) / 2.0F;
         float corner = Math.min(0x3D / 255.0F, radiusPx / Math.max(1.0F, minHalf * 0.5F));
         int knobs = (PANEL_KNOBS & 0xFF00FF00) | (Math.round(corner * 255.0F) & 0xFF) << 16;
         GuiRenderState rs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
         TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
         rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), x0, y0, x1, y1, PANEL_PAD, knobs | (opacityByte & 0xFF), null));
         return true;
      }
      return false;
   }

   /** {@link #plate} at full opacity. */
   public static boolean plate(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
      return plate(g, x0, y0, x1, y1, 0xFF);
   }

   /**
    * Enqueue the refracting panel, or paint a flat {@link GlassPainter} capsule when the pipeline is not usable — so callers
    * that always want <em>something</em> drawn never have to write the fallback themselves.
    */
   public static void plateOrPaint(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int opacityByte, int fallbackArgb) {
      if (!plate(g, x0, y0, x1, y1, opacityByte)) {
         int fa = Math.round((fallbackArgb >>> 24 & 0xFF) * (opacityByte & 0xFF) / 255.0F);
         GlassPainter.capsule(g, x0, y0, x1 - x0, y1 - y0, 3.0F, fa << 24 | fallbackArgb & 0xFFFFFF);
      }
   }

   /**
    * A flat anti-aliased rounded rect through the {@code round()} pipeline — a readability scrim under content, or a small
    * light knob (scrollbar thumb) that must not itself refract. Falls back to a stepped {@link GlassPainter} capsule.
    */
   public static void scrim(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, float radius, int argb) {
      if (x1 <= x0 || y1 <= y0) {
         return;
      }
      if (GlassPipeline.ensureReady() && GlassPipeline.roundUsable()) {
         ((GuiGraphicsExtractorAccessor)g)
            .liquidglass$guiRenderState()
            .addGuiElement(new RoundRectRenderState(GlassPipeline.round(), g.pose(), x0, y0, x1, y1, radius, argb, null));
      } else {
         GlassPainter.capsule(g, x0, y0, x1 - x0, y1 - y0, radius, argb);
      }
   }
}
