package com.seagull.liquidglass.client.render;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.jspecify.annotations.Nullable;

/**
 * An ambient clip + opacity for the mod's own render states, active only while a caller draws a sub-tree that must be
 * clipped/faded as a whole (the recipe book sliding out from under the inventory). Vanilla draws already follow
 * {@code enableScissor} and {@code GuiAlpha}; the custom glass / round-rect states take an explicit scissor and encode
 * their opacity in the colour, so {@link GlassRectRenderState} and {@link RoundRectRenderState} consult this in their
 * constructors. Outside such a window it is a no-op. Render thread only.
 */
public final class GuiAmbient {
   private GuiAmbient() {}

   @Nullable private static ScreenRectangle clip;
   private static float fade = 1.0F;

   public static void set(@Nullable ScreenRectangle c, float f) {
      clip = c;
      fade = Math.max(0.0F, Math.min(1.0F, f));
   }

   public static void clear() {
      clip = null;
      fade = 1.0F;
   }

   /** The scissor a new state should use: its own, narrowed by the ambient clip. */
   @Nullable
   public static ScreenRectangle scissor(@Nullable ScreenRectangle own) {
      if (clip == null) return own;
      if (own == null) return clip;
      ScreenRectangle i = own.intersection(clip);
      return i != null ? i : new ScreenRectangle(clip.left(), clip.top(), 0, 0);
   }

   /** Glass colours carry their opacity in the LOW byte (see GlassPanels / ButtonGlassMixin). */
   public static int glassColor(int color) {
      if (fade >= 0.999F) return color;
      int a = Math.round((color & 0xFF) * fade) & 0xFF;
      return (color & 0xFFFFFF00) | a;
   }

   /** Plain ARGB (round rects): alpha in the high byte. */
   public static int argb(int color) {
      if (fade >= 0.999F) return color;
      int a = Math.round((color >>> 24) * fade) & 0xFF;
      return a << 24 | (color & 0xFFFFFF);
   }
}
