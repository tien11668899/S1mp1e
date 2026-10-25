package com.seagull.liquidglass.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassSurface;
import dev.s1mp1e.client.gui.ScreenOpenFade;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.client.gui.screens.inventory.BookSignScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The book page (view / edit / sign, and the lectern via {@code BookViewScreen}) is drawn on liquid glass instead of the
 * wooden {@code book.png} texture. The vanilla blit draws a 192&times;192 region whose opaque page (measured on
 * {@code book.png}) is the {@value #PAGE_W}&times;{@value #PAGE_H} rect at ({@value #PAGE_X}, {@value #PAGE_Y}), so the
 * redirect enqueues a glass plate over exactly that page rectangle. (It used to assume the page was the top-left
 * 146&times;180: the plate sat 20 px left of the real page, and the page text and counter ran off its right edge.)
 *
 * <p>Book text is DARK ink, which would be unreadable on frosted (dark) glass, so a LIGHT warm scrim is laid over the glass
 * (the documented book exception to the usual grey scrim); the page text then draws on the scrim, and the glass shows as a
 * thin refracting frame around the parchment.
 */
@Mixin({BookViewScreen.class, BookEditScreen.class, BookSignScreen.class})
public abstract class BookGlassMixin {

   /** The opaque page inside the 192-wide blit: book.png's page art is the rect (20,1)-(166,181). */
   private static final int PAGE_X = 20;
   private static final int PAGE_Y = 1;
   private static final int PAGE_W = 146;
   private static final int PAGE_H = 180;
   /** Warm, mostly-opaque parchment scrim so dark ink stays readable; the glass frames it at the edge. */
   private static final int PARCHMENT = 0xD8EFE7D6;

   @Redirect(
      method = "extractBackground",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"
      )
   )
   private void lg$bookPage(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier tex, int x, int y, float u, float v, int w, int h, int tw, int th) {
      int opacity = Math.round(0xFF * ScreenOpenFade.value(Minecraft.getInstance().gui.screen())) & 0xFF;
      int x0 = x + PAGE_X, y0 = y + PAGE_Y, x1 = x0 + PAGE_W, y1 = y0 + PAGE_H;
      if (GlassPipeline.ensureReady() && GlassPipeline.usable() && GlassSurface.plate(g, x0, y0, x1, y1, opacity)) {
         int scrimA = (PARCHMENT >>> 24) * opacity / 0xFF << 24;
         GlassSurface.scrim(g, x0 + 4, y0 + 4, x1 - 4, y1 - 4, 4.0F, scrimA | PARCHMENT & 0xFFFFFF);
      } else {
         g.blit(pipeline, tex, x, y, u, v, w, h, tw, th);
      }
   }
}
