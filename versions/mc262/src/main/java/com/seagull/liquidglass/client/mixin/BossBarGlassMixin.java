package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import dev.s1mp1e.client.hud.HudGlass;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.resources.Identifier;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boss bar = the S1mp1e slider look (user: "血量的顏色要跟滑桿底部一樣是藍的, 藍色血量的下一層就放形狀一樣(照比例稍大,
 * 最左右邊比現在更圓)的液態玻璃"):
 * <ul>
 *   <li>the health fill is the config slider's blue ({@code 0x0A84FF}) as a capsule with fully round ends;</li>
 *   <li>under it, a liquid-glass capsule of the same shape, {@link #MARGIN} GUI px larger on every side, so the two are
 *       concentric (glass radius = fill radius + margin) and the ends are true semicircles
 *       ({@link GlassPipeline#capsule()}; the regular glass program caps the corner at a quarter of the short side).</li>
 * </ul>
 * Vanilla calls {@code extractBar} twice per boss: first with the background sprites (full width) -> the glass capsule,
 * then with the progress sprites (health width) -> the blue fill, so the fill always lies on top of the glass.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossBarGlassMixin {

   @Shadow @Final private static Identifier[] BAR_BACKGROUND_SPRITES;
   @Shadow @Final private static int BAR_HEIGHT;

   /** The config slider's fill blue (iOS systemBlue), opaque. */
   private static final int FILL_ARGB = 0xFF0A84FF;
   /** Glass capsule reaches this far past the fill on every side (concentric). */
   private static final int MARGIN = 2;
   /** Frost 0.5, full-capsule corner knob, no lift; low byte = opacity. */
   private static final int GLASS_KNOBS = 0x80FFFF00;
   private static final int GLASS_OPACITY = 0xE6;

   @Inject(
      method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;I[Lnet/minecraft/resources/Identifier;[Lnet/minecraft/resources/Identifier;)V",
      at = @At("HEAD"),
      cancellable = true
   )
   private void lg$glassBar(GuiGraphicsExtractor g, int x, int y, BossEvent boss, int width,
                            Identifier[] bar, Identifier[] overlay, CallbackInfo ci) {
      if (!(GlassPipeline.ensureReady() && GlassPipeline.usable())) return;
      int h = BAR_HEIGHT;
      if (bar == BAR_BACKGROUND_SPRITES) {
         int x0 = x - MARGIN, y0 = y - MARGIN, x1 = x + width + MARGIN, y1 = y + h + MARGIN;
         if (GlassPipeline.capsuleUsable()) {
            TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
            ((GuiGraphicsExtractorAccessor) g).liquidglass$guiRenderState().addGuiElement(
               new GlassRectRenderState(GlassPipeline.capsule(), ts, g.pose(), x0, y0, x1, y1, 8, GLASS_KNOBS | GLASS_OPACITY, null));
         } else {
            HudGlass.glassBox(g, x0, y0, x1, y1, 0.9F);
         }
      } else if (width > 0) {
         HudGlass.roundRect(g, x, y, x + width, y + h, h * 0.5F, FILL_ARGB);
      }
      ci.cancel();
   }
}
