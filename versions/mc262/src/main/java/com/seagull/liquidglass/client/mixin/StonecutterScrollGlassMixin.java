package com.seagull.liquidglass.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.seagull.liquidglass.client.render.GlassPanels;
import com.seagull.liquidglass.client.render.GlassPipeline;
import dev.s1mp1e.client.gui.GlassScrollbar;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.StonecutterScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Stonecutter recipe list → the config-menu silky scroll + the shared vertical glass slider ({@link GlassScrollbar}).
 *
 * <p><b>Scrollbar</b> — the vanilla 12x15 thumb (the only {@code blitSprite} in {@code extractBackground}) is replaced
 * by the config-slider glass thumb: it eases to the row-snapped scroll position, becomes a held glass lens while the
 * vanilla {@code scrolling} flag is set, and rubber-bands past the ends.
 *
 * <p><b>Silky content</b> — vanilla snaps the 4x3 recipe grid by whole rows. Here the grid is slid sub-pixel by the
 * SAME eased value the glass thumb uses ({@link GlassScrollbar#pos()}), so the recipes glide exactly like the S1mp1e
 * config menu instead of stepping. {@code extractButtons}/{@code extractRecipes} are wrapped: their {@code startIndex}
 * is forced to 0 so they draw EVERY recipe at its absolute row, the whole grid is translated up by the eased scroll
 * offset, and a scissor over the 3-row window (54&nbsp;px) hides everything outside — so a partial row peeking in never
 * shows a gap and rows leaving the top are clipped cleanly. The vanilla scroll state ({@code scrollOffs}/
 * {@code startIndex}) is left untouched for click / tooltip hit-testing, and the eased draw settles exactly onto the
 * vanilla row at rest, so a recipe click always lands on the recipe under the cursor.
 */
@Mixin(StonecutterScreen.class)
public abstract class StonecutterScrollGlassMixin {

   @Shadow private float scrollOffs;
   @Shadow private boolean scrolling;
   @Shadow private int startIndex;
   @Shadow private boolean isScrollBarActive() { return false; }
   @Shadow protected int getOffscreenRows() { return 0; }

   @Unique private GlassScrollbar liquidglass$bar;
   /** true this frame when the grid should be slid sub-pixel (pipeline usable + an active scrollbar). */
   @Unique private boolean liquidglass$sliding;
   /** eased scroll offset in px (0 = top), the same value the glass thumb sits at. */
   @Unique private float liquidglass$scrollPx;

   @Redirect(
      method = {"extractBackground"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
      )
   )
   private void lg$stonecutterScroller(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h,
                                       GuiGraphicsExtractor gEnc, int mouseX, int mouseY, float delta) {
      liquidglass$sliding = false;
      liquidglass$scrollPx = 0f;
      boolean active = isScrollBarActive();
      if (w == 12 && h == 15 && GlassPipeline.ensureReady() && GlassPipeline.usable()) {
         if (liquidglass$bar == null) liquidglass$bar = new GlassScrollbar();
         float top = ((AbstractContainerScreenAccessor)(Object)this).liquidglass$topPos();
         int offscreen = Math.max(0, getOffscreenRows());
         float ratio = offscreen <= 0 ? 0f : (startIndex / 4f) / offscreen;
         GlassScrollbar.run(liquidglass$bar, g, x + w / 2f, top + 15f, 41f, 15f,
                 ratio, active, scrolling && active, mouseY, GlassPanels.fadeByte() / 255f);
         if (active && offscreen > 0) {
            liquidglass$sliding = true;
            liquidglass$scrollPx = liquidglass$bar.pos() * offscreen * 18f;
         }
      } else {
         g.blitSprite(pipeline, sprite, x, y, w, h);
      }
   }

   /** Draw every recipe button at its absolute row, translated by the eased scroll, clipped to the 3-row window. */
   @WrapMethod(method = "extractButtons")
   private void lg$slideButtons(GuiGraphicsExtractor g, int mouseX, int mouseY, int x, int y, int maxIndex, Operation<Void> original) {
      if (!liquidglass$sliding) { original.call(g, mouseX, mouseY, x, y, maxIndex); return; }
      int saved = startIndex;
      startIndex = 0;
      g.enableScissor(x, y, x + 64, y + 55);
      g.pose().pushMatrix();
      g.pose().translate(0f, -liquidglass$scrollPx);
      try {
         original.call(g, mouseX, mouseY, x, y, Integer.MAX_VALUE);
      } finally {
         g.pose().popMatrix();
         g.disableScissor();
         startIndex = saved;
      }
   }

   /** Same sub-pixel slide + clip for the recipe RESULT icons drawn on top of the buttons. */
   @WrapMethod(method = "extractRecipes")
   private void lg$slideRecipes(GuiGraphicsExtractor g, int x, int y, int maxIndex, Operation<Void> original) {
      if (!liquidglass$sliding) { original.call(g, x, y, maxIndex); return; }
      int saved = startIndex;
      startIndex = 0;
      g.enableScissor(x, y, x + 64, y + 55);
      g.pose().pushMatrix();
      g.pose().translate(0f, -liquidglass$scrollPx);
      try {
         original.call(g, x, y, Integer.MAX_VALUE);
      } finally {
         g.pose().popMatrix();
         g.disableScissor();
         startIndex = saved;
      }
   }
}
