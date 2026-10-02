package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.module.HudGlass;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

/**
 * Fade a boss bar OUT when its boss is removed. Vanilla drops a removed boss from its map and {@code BossBarHud.render}
 * early-returns when that map is empty, so the bar pops. {@code BossBarGlassMixin} reports each drawn boss and its last draw
 * params here; the drawn-set is rotated at the boss-bar HUD layer's HEAD and, at that layer's TAIL — right after the layer's
 * own {@code bossBarHud.render()} call, which always runs even with no bosses left — any boss that stopped being drawn this
 * frame starts a ghost. {@link #detectAndDraw} redraws each ghost's capsule + blue fill + name at its cached position, fading
 * over {@link #OUT_S}. Detection is one frame late — invisible against the fade, and with no gap because the TAIL runs after
 * the frame's bars are drawn. Render thread only. 1.15.2 port (immediate {@link HudGlass} glass in place of 26.2's render
 * states; the same absolute-coordinate {@code capsule} / {@code colorCapsule} calls this line's {@code BossBarGlassMixin}
 * draws the live bar with).
 */
public final class BossGhost {
   private BossGhost() {}

   private static final float OUT_S = 0.15F;
   private static final int MARGIN = 2;
   private static final float GLASS_OPACITY = 0xE6 / 255f;
   private static final float GLASS_FROST = 0x80 / 255f;
   private static final int FILL_ARGB = 0xFF0A84FF;
   private static final int BAR_HEIGHT_FALLBACK = 5;

   private static final Set<UUID> drawn = new HashSet<>();
   private static final Set<UUID> prevDrawn = new HashSet<>();
   private static final HashMap<UUID, float[]> cache = new HashMap<>();   // {x, y, fullWidth, fillWidth, h}
   private static final HashMap<UUID, Text> names = new HashMap<>();
   private static final HashMap<UUID, Long> ghosts = new HashMap<>();

   /** A boss was drawn this frame (so it is alive); cancel any pending ghost for it. */
   public static void markDrawn(UUID id) {
      drawn.add(id);
      ghosts.remove(id);
   }

   /** Cache the capsule (background) draw of a boss: full width + name. */
   public static void cacheBackground(UUID id, int x, int y, int fullWidth, int h, Text name) {
      float[] c = cache.computeIfAbsent(id, k -> new float[5]);
      c[0] = x; c[1] = y; c[2] = fullWidth; c[4] = h;
      names.put(id, name);
   }

   /** Cache the blue-fill (progress) draw of a boss: fill width. */
   public static void cacheFill(UUID id, int fillWidth, int y, int h) {
      float[] c = cache.computeIfAbsent(id, k -> new float[5]);
      c[3] = fillWidth; c[1] = y; c[4] = h;
   }

   /** Boss-bar layer HEAD: roll this frame's drawn set to previous, clear it for the coming frame. */
   public static void rotate() {
      prevDrawn.clear();
      prevDrawn.addAll(drawn);
      drawn.clear();
   }

   /** Boss-bar layer TAIL: start ghosts for bosses that stopped being drawn, then draw all fading ghosts. */
   public static void detectAndDraw() {
      long now = System.nanoTime();
      for (UUID id : prevDrawn) {
         if (!drawn.contains(id) && !ghosts.containsKey(id) && cache.containsKey(id)) ghosts.put(id, now);
      }
      if (ghosts.isEmpty()) return;
      boolean glass = GlassProgram.ensureReady() && GlassProgram.usable();
      Iterator<Map.Entry<UUID, Long>> it = ghosts.entrySet().iterator();
      while (it.hasNext()) {
         Map.Entry<UUID, Long> e = it.next();
         UUID id = e.getKey();
         float t = (now - e.getValue()) / 1.0e9F / OUT_S;
         float[] c = cache.get(id);
         if (t >= 1F || c == null || !glass) { it.remove(); cache.remove(id); names.remove(id); continue; }
         draw(c, names.get(id), 1F - t);
      }
   }

   private static void draw(float[] c, Text name, float a) {
      int x = (int) c[0], y = (int) c[1], fullW = (int) c[2], fillW = (int) c[3], h = (int) c[4];
      if (h <= 0) h = BAR_HEIGHT_FALLBACK;
      HudGlass.capsule(x - MARGIN, y - MARGIN, x + fullW + MARGIN, y + h + MARGIN, GLASS_OPACITY * a, GLASS_FROST);
      if (fillW > 0) {
         int fill = (Math.round(0xFF * a) & 0xFF) << 24 | FILL_ARGB & 0xFFFFFF;
         HudGlass.colorCapsule(x, y, x + fillW, y + h, h * 0.5F, fill);
      }
      if (name != null) {
         MinecraftClient mc = MinecraftClient.getInstance();
         TextRenderer font = mc.textRenderer;
         int nx = (mc.getWindow().getScaledWidth() - font.getStringWidth(name.asFormattedString())) / 2;
         int col = (Math.round(0xFF * a) & 0xFF) << 24 | 0xFFFFFF;
         if ((col >>> 24) >= 4) {                               // alpha 0..3 would be read as opaque
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            font.draw(name.asFormattedString(), (float) nx, (float) (y - 9), col);
         }
      }
   }
}
