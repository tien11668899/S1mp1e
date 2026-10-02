package com.seagull.liquidglass.client.render;

import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import dev.s1mp1e.client.hud.HudGlass;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.network.chat.Component;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fade a boss bar OUT when its boss is removed. Vanilla drops a removed boss from its events map and
 * {@code BossHealthOverlay.extractRenderState} early-returns when that map is empty, so the bar pops. {@code BossBarGlassMixin}
 * reports each drawn boss and its last draw params here; the drawn-set is rotated at {@code Hud.extractBossOverlay} HEAD and,
 * at that method's TAIL (which always runs, even with no bosses left, right after the overlay's own extract), any boss that
 * stopped being drawn this frame starts a ghost. {@link #detectAndDraw} redraws each ghost's capsule + blue fill + name at
 * its cached position, fading over {@link #OUT_S}. Detection is one frame late — invisible against the fade, and with no gap
 * because the TAIL runs after the frame's bars are drawn. Render thread only.
 */
public final class BossGhost {
   private BossGhost() {}

   private static final float OUT_S = 0.15F;
   private static final int MARGIN = 2;
   private static final int GLASS_KNOBS = 0x80FFFF00;
   private static final int GLASS_OPACITY = 0xE6;
   private static final int FILL_ARGB = 0xFF0A84FF;
   private static final int BAR_HEIGHT_FALLBACK = 5;

   private static final Set<UUID> drawn = new HashSet<>();
   private static final Set<UUID> prevDrawn = new HashSet<>();
   private static final HashMap<UUID, float[]> cache = new HashMap<>();   // {x, y, fullWidth, fillWidth, h}
   private static final HashMap<UUID, Component> names = new HashMap<>();
   private static final HashMap<UUID, Long> ghosts = new HashMap<>();

   /** A boss was drawn this frame (so it is alive); cancel any pending ghost for it. */
   public static void markDrawn(UUID id) {
      drawn.add(id);
      ghosts.remove(id);
   }

   /** Cache the capsule (background) draw of a boss: full width + name. */
   public static void cacheBackground(UUID id, int x, int y, int fullWidth, int h, Component name) {
      float[] c = cache.computeIfAbsent(id, k -> new float[5]);
      c[0] = x; c[1] = y; c[2] = fullWidth; c[4] = h;
      names.put(id, name);
   }

   /** Cache the blue-fill (progress) draw of a boss: fill width. */
   public static void cacheFill(UUID id, int fillWidth, int y, int h) {
      float[] c = cache.computeIfAbsent(id, k -> new float[5]);
      c[3] = fillWidth; c[1] = y; c[4] = h;
   }

   /** {@code Hud.extractBossOverlay} HEAD: roll this frame's drawn set to previous, clear it for the coming frame. */
   public static void rotate() {
      prevDrawn.clear();
      prevDrawn.addAll(drawn);
      drawn.clear();
   }

   /** {@code Hud.extractBossOverlay} TAIL: start ghosts for bosses that stopped being drawn, then draw all fading ghosts. */
   public static void detectAndDraw(GuiGraphicsExtractor g) {
      long now = net.minecraft.util.Util.getNanos();
      for (UUID id : prevDrawn) {
         if (!drawn.contains(id) && !ghosts.containsKey(id) && cache.containsKey(id)) ghosts.put(id, now);
      }
      if (ghosts.isEmpty()) return;
      boolean glass = GlassPipeline.ensureReady() && GlassPipeline.usable();
      Iterator<Map.Entry<UUID, Long>> it = ghosts.entrySet().iterator();
      while (it.hasNext()) {
         Map.Entry<UUID, Long> e = it.next();
         UUID id = e.getKey();
         float t = (now - e.getValue()) / 1.0e9F / OUT_S;
         float[] c = cache.get(id);
         if (t >= 1F || c == null || !glass) { it.remove(); cache.remove(id); names.remove(id); continue; }
         draw(g, c, names.get(id), 1F - t);
      }
   }

   private static void draw(GuiGraphicsExtractor g, float[] c, Component name, float a) {
      int x = (int) c[0], y = (int) c[1], fullW = (int) c[2], fillW = (int) c[3], h = (int) c[4];
      if (h <= 0) h = BAR_HEIGHT_FALLBACK;
      int x0 = x - MARGIN, y0 = y - MARGIN, x1 = x + fullW + MARGIN, y1 = y + h + MARGIN;
      if (GlassPipeline.capsuleUsable()) {
         TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
         int opacity = Math.round(GLASS_OPACITY * a) & 0xFF;
         ((GuiGraphicsExtractorAccessor) g).liquidglass$guiRenderState().addGuiElement(
            new GlassRectRenderState(GlassPipeline.capsule(), ts, g.pose(), x0, y0, x1, y1, 8, GLASS_KNOBS | opacity, null));
      } else {
         HudGlass.glassBox(g, x0, y0, x1, y1, 0.9F * a);
      }
      if (fillW > 0) {
         int fill = (Math.round(0xFF * a) & 0xFF) << 24 | FILL_ARGB & 0xFFFFFF;
         HudGlass.roundRect(g, x, y, x + fillW, y + h, h * 0.5F, fill);
      }
      if (name != null) {
         Font font = Minecraft.getInstance().font;
         int nx = (Minecraft.getInstance().getWindow().getGuiScaledWidth() - font.width(name)) / 2;
         int col = (Math.round(0xFF * a) & 0xFF) << 24 | 0xFFFFFF;
         g.text(font, name, nx, y - 9, col);
      }
   }
}
