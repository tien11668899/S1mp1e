package com.seagull.liquidglass.client.render;

import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/**
 * Liquid glass for the status-effect boxes vanilla draws beside the survival / creative inventory
 * ({@code EffectsInInventory} — the panel to the right of the inventory that lists each active potion effect).
 *
 * <p>Vanilla paints each effect box with the {@code effect_background} / {@code effect_background_ambient} dark rounded
 * sprite. {@code EffectsInInventoryGlassMixin} redirects that single {@code blitSprite(...)} inside
 * {@code EffectsInInventory.extractBackground} to {@link #box}, which enqueues one refracting glass plate of the SAME
 * material as the inventory panel ({@link GlassPanels} / {@link GlassSurface}) at the same rectangle. Everything else
 * vanilla does for the box is left untouched:
 * <ul>
 *   <li>the effect ICON blit lives in {@code extractEffects} (a different method), so it still draws on top;</li>
 *   <li>the effect NAME + remaining-time TEXT are added after the background in the same stratum, so they stay on top
 *       and readable (insertion order = draw order within a stratum in 26.2);</li>
 *   <li>the COMPACT-layout hover tooltip goes through {@code setTooltipForNextFrame} → the deferred {@code tooltip}
 *       path, which {@code TooltipLayer} already promotes to the very top layer, so the project's "tooltips on top"
 *       rule holds unchanged;</li>
 *   <li>26.2's inventory effect panel has NO per-frame blink (the ending-effect blink lives only in
 *       {@code Hud.extractEffects}, the HUD overlay the mod hides), so a background-only swap cannot break it.</li>
 * </ul>
 *
 * <p>This is a single refracting quad per box (no slot separators), exactly like {@link GlassSurface#plate} — but with
 * the smaller {@link #PAD} that {@link TooltipGlass} uses for small cards, because the boxes are small and stack
 * vertically (with more than five effects vanilla itself packs them so they overlap). Every call gates on
 * {@link GlassPipeline#usable()}; a caller that gets {@code false} falls back to the vanilla sprite so a box never
 * vanishes. The backdrop grabbed once per frame at {@code GuiRenderer.render} HEAD is the world only, which is what a
 * panel-depth surface should refract — the box sits over the world to the right of the inventory.
 */
public final class GlassEffects {
   private GlassEffects() {
   }

   /** AA / edge-refraction bleed around a box, in GUI px — the small-card pad ({@link TooltipGlass} uses the same). */
   private static final int PAD = 8;

   /** Faint hotbar-style separator between two entries (same tint as the creative tab separators). */
   private static final int SEP_ARGB = 0x24000000;
   /** Separators stop short of the strip's sides, like the hotbar slot grooves. */
   private static final int SEP_INSET = 6;

   /**
    * The whole effect list as ONE continuous vertical glass strip (user choice "A 合成一整條"): a single plate from
    * {@code (x0, y0)} to {@code (x1, y1)} with the hotbar corner radius ({@link GlassCorners}), in the inventory panel
    * material, plus a faint separator at the top of every entry after the first ({@code y0 + i * spacing}).
    * Must be called before any entry's icon/text is extracted so the strip stays underneath them.
    *
    * @return {@code false} when the glass pipeline is not usable (caller keeps the vanilla per-box sprites)
    */
   public static boolean strip(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int spacing, int count) {
      if (x1 <= x0 || y1 <= y0 || count <= 0) {
         return false;
      }
      if (!(GlassPipeline.ensureReady() && GlassPipeline.usable())) {
         return false;
      }
      int opacity = GlassPanels.fadeByte() & 0xFF;
      int knobs = GlassCorners.withHotbarCorner(GlassSurface.PANEL_KNOBS, x1 - x0, y1 - y0);
      GuiRenderState rs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
      TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
      rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), x0, y0, x1, y1, PAD, knobs | opacity, null));
      int sepA = Math.round((SEP_ARGB >>> 24) * opacity / 255.0F);
      for (int i = 1; i < count; i++) {
         int sy = y0 + i * spacing;
         GlassSurface.scrim(g, x0 + SEP_INSET, sy - 1, x1 - SEP_INSET, sy, 0.5F, (sepA << 24) | (SEP_ARGB & 0xFFFFFF));
      }
      return true;
   }

   /**
    * Enqueue a refracting glass plate for one status-effect box at [{@code x},{@code y}] .. [{@code x+w},{@code y+h}]
    * (absolute scaled-GUI px under the extractor's current pose), matching the inventory panel material and fading in
    * with it ({@link GlassPanels#fadeByte()}).
    *
    * @return {@code true} when the plate was enqueued; {@code false} when the glass pipeline is not usable (the caller
    *         must then draw the vanilla sprite so the box never disappears).
    */
   public static boolean box(GuiGraphicsExtractor g, int x, int y, int w, int h) {
      if (w <= 0 || h <= 0) {
         return false;
      }
      if (GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         int opacity = GlassPanels.fadeByte();
         GuiRenderState rs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
         TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
         rs.addGuiElement(
            new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(), x, y, x + w, y + h, PAD, GlassSurface.PANEL_KNOBS | (opacity & 0xFF), null)
         );
         return true;
      }
      return false;
   }
}
