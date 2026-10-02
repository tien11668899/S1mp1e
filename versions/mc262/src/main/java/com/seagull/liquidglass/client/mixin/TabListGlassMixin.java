package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The player tab list becomes liquid glass. {@code PlayerTabOverlay.extractRenderState} paints its backgrounds with four
 * {@code g.fill} calls, drawn in insertion order:
 * <ol>
 *   <li>header background (only when a header is set),</li>
 *   <li>the player-list panel,</li>
 *   <li>the per-row name stripe (once per row, height 8), and</li>
 *   <li>footer background (only when a footer is set).</li>
 * </ol>
 * Each fill is redirected: the three tall structural panels (header / list / footer) become refracting glass plates with a
 * grey readability scrim, and the short per-row stripe becomes a thinned scrim so the row striping stays visible but gentle
 * over the glass. When the glass pipeline is not usable every fill falls back to the vanilla draw.
 *
 * <p><b>Appear fade.</b> Vanilla pops the whole list in on the first frame the player-list key is held. Here it fades in
 * over the same 150 ms as every glass screen-open fade: {@code setVisible}'s false → true edge (vanilla calls it every
 * frame with the key state) stamps the start, and the glass, names, header/footer, heads, ping bars, hearts and scores
 * all take that one alpha, so nothing on the list arrives ahead of the rest.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class TabListGlassMixin {

   /** Rects taller than this are structural panels; the short one is the per-row name stripe. */
   private static final int ROW_MAX_H = 10;
   /** Grey readability scrim under the names on the structural panels. */
   private static final int PANEL_SCRIM = 0x66101018;
   @Shadow private boolean visible;

   /**
    * List opacity 0..1. Now IN on key-press AND OUT on release, driven by the shared render-gate state in
    * {@link com.seagull.liquidglass.client.render.TabListFade} (its companion {@code TabListGateMixin} keeps this overlay
    * rendering through the fade-out). Everything on the list takes this one alpha, so it fades as a whole in both directions.
    */
   @Unique
   private static float lg$fade() {
      return com.seagull.liquidglass.client.render.TabListFade.alpha();
   }

   /** {@code argb} with its alpha scaled by the appear fade (unchanged once the fade has finished). */
   @Unique
   private static int lg$fadeArgb(int argb) {
      float f = lg$fade();
      if (f >= 1F) return argb;
      int a = Math.round((argb >>> 24 & 0xFF) * f) & 0xFF;
      return a << 24 | argb & 0xFFFFFF;
   }

   @Redirect(
      method = "extractRenderState",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"
      )
   )
   private void lg$glassFill(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
      if (!(GlassPipeline.ensureReady() && GlassPipeline.usable())) {
         g.fill(x0, y0, x1, y1, lg$fadeArgb(color));
         return;
      }
      float f = lg$fade();
      if (f <= 0.004F) return;
      if (y1 - y0 <= ROW_MAX_H) {
         // Per-row name stripe -> a softened scrim (keep row striping, gentle over glass).
         int a = Math.round((color >>> 24 & 0xFF) * 0.5F * f) & 0xFF;
         GlassSurface.scrim(g, x0, y0, x1, y1, 2.0F, a << 24 | color & 0xFFFFFF);
      } else {
         // Structural header / list / footer panel -> refracting glass plate + readability scrim.
         GlassSurface.plateOrPaint(g, x0, y0, x1, y1, Math.round(0xFF * f) & 0xFF, lg$fadeArgb(0x99101018));
         GlassSurface.scrim(g, x0, y0, x1, y1, 0.0F, lg$fadeArgb(PANEL_SCRIM));
      }
   }

   /** Names, header, objective score and the heart count all use this text overload. */
   @ModifyArg(
      method = {"extractRenderState", "extractTablistHearts", "extractTablistScore"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"
      ),
      index = 4
   )
   private int lg$fadeText(int color) {
      return lg$fadeArgb(color);
   }

   /** Header / footer lines. */
   @ModifyArg(
      method = "extractRenderState",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V"
      ),
      index = 4
   )
   private int lg$fadeLine(int color) {
      return lg$fadeArgb(color);
   }

   /** Player heads. */
   @ModifyArg(
      method = "extractRenderState",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/components/PlayerFaceExtractor;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/resources/Identifier;IIIZZI)V"
      ),
      index = 7
   )
   private int lg$fadeFace(int color) {
      return lg$fadeArgb(color);
   }

   /** Ping bars and hearts: route through the alpha overload (vanilla's own {@code ARGB.white(alpha)}) while fading. */
   @Redirect(
      method = {"extractPingIcon", "extractTablistHearts"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
      )
   )
   private void lg$fadeSprite(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier id, int x, int y, int w, int h) {
      float f = lg$fade();
      if (f >= 1F) {
         g.blitSprite(pipeline, id, x, y, w, h);
      } else if (f > 0.004F) {
         g.blitSprite(pipeline, id, x, y, w, h, f);
      }
   }
}
