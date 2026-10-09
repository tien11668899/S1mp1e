package com.seagull.liquidglass.client.mixin;

import com.seagull.liquidglass.client.render.GlassSurface;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.achievement.StatsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The statistics screen is a full-screen list that forces the tiled "menu background" (a dirt/header texture) instead of the
 * blurred world. {@link #lg$statsBg} replaces that whole background with one refracting glass plate spanning the screen, so
 * the header, tabs and stat rows sit on frosted glass over the world. Cancelling {@code extractMenuBackground} drops both the
 * header strip and the tiled body in one shot.
 *
 * <p>Because the stat rows are light text over a potentially bright world, a grey readability scrim is laid over the glass
 * (LOOK SPEC: grey scrim under text where needed) so every row stays legible while the frosted refraction still reads.
 *
 * <p>Only cancels when the glass pipeline is usable ({@link GlassSurface#plate} returns {@code true}); otherwise the vanilla
 * textured background is left untouched so the screen never renders empty.
 */
@Mixin(StatsScreen.class)
public abstract class StatsScreenGlassMixin {

   /** Grey readability scrim so the light stat rows read over any world (the vanilla dirt backing is fully opaque). */
   private static final int SCRIM_RGB = 0x0E0E14;
   private static final int SCRIM_ALPHA = 0xB4;

   @Inject(
      method = "extractMenuBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
      at = @At("HEAD"),
      cancellable = true
   )
   private void lg$statsBg(GuiGraphicsExtractor g, CallbackInfo ci) {
      int opacity = Math.round(0xFF * ScreenOpenFade.value(Minecraft.getInstance().gui.screen())) & 0xFF;
      int w = g.guiWidth();
      int h = g.guiHeight();
      if (GlassSurface.plate(g, 0, 0, w, h, opacity)) {
         int scrimA = SCRIM_ALPHA * opacity / 0xFF << 24;
         GlassSurface.scrim(g, 0, 0, w, h, 0.0F, scrimA | SCRIM_RGB);
         ci.cancel();
      }
   }
}
