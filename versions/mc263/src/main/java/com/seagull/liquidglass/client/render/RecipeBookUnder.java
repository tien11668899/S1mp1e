package com.seagull.liquidglass.client.render;

import com.seagull.liquidglass.client.mixin.AbstractRecipeBookScreenAccessor;
import dev.s1mp1e.client.gui.GuiAlpha;
import dev.s1mp1e.client.gui.RecipeBookHost;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;

/**
 * Draws the sliding recipe book on the layer UNDER the inventory panel ("從背包下面那層").
 *
 * <p>The inventory's glass panel is opaque inside its rounded shape (the glass shader outputs alpha 1 there, showing
 * the blurred world backdrop) and draws only a faint drop shadow outside it. So while the book slides, it is extracted
 * in its own stratum just before the panel glass: the panel then hides exactly the part of the book beneath it — with
 * the panel's real rounded corners, not a square cut — and the part that has slid out shows, shadowed near the edge.
 * The vanilla book draw (above the inventory) is skipped for that frame. Render thread only.
 */
public final class RecipeBookUnder {
   private RecipeBookUnder() {}

   private static int mouseX, mouseY;
   private static float delta;
   private static Object drawnFor;

   /** Frame start (Screen wrapper HEAD): remember the mouse/partial tick for the book extraction. */
   public static void beginFrame(int mx, int my, float d) {
      mouseX = mx;
      mouseY = my;
      delta = d;
      drawnFor = null;
   }

   public static void endFrame() {
      drawnFor = null;
   }

   /** Called by the inventory panel right before its glass is added: slide in progress → the book goes underneath. */
   public static void drawBeneath(GuiGraphicsExtractor g, AbstractContainerScreen<?> screen) {
      if (drawnFor == screen || !RecipeBookSlide.active() || !(screen instanceof AbstractRecipeBookScreen<?>)) return;
      AbstractRecipeBookScreenAccessor acc = (AbstractRecipeBookScreenAccessor) screen;
      if (acc.liquidglass$widthTooNarrow()) return;
      RecipeBookComponent<?> book = acc.liquidglass$recipeBook();
      if (!((Object) book instanceof RecipeBookHost host)) return;
      float fade = RecipeBookSlide.bookFade();
      g.nextStratum();
      GuiAmbient.set(null, fade);
      g.pose().pushMatrix();
      g.pose().translate(RecipeBookSlide.bookShift(host.liquidglass$xOrigin()), 0F);
      GuiAlpha.push(fade);
      try {
         book.extractRenderState(g, mouseX, mouseY, delta);
      } finally {
         GuiAlpha.pop();
         g.pose().popMatrix();
         GuiAmbient.clear();
      }
      g.nextStratum();
      drawnFor = screen;
   }

   /** The vanilla (above-the-inventory) book draw: skip it if the book was already drawn underneath this frame. */
   public static boolean alreadyDrawn(Object screen) {
      return drawnFor == screen;
   }
}
