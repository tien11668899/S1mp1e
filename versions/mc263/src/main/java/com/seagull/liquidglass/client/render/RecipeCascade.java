package com.seagull.liquidglass.client.render;

import java.util.List;
import java.util.WeakHashMap;

/**
 * Recipe-book items appear one after another ("逐個物品出來") whenever the page shows a different set of recipes — on
 * opening the book, turning a page, switching category, or a search refill: each recipe button grows from its centre on
 * a critically damped spring, {@link #STAGGER_NS} after the previous one. Item icons can't be faded in 26.2's GUI, so the
 * entrance is a scale-up (the glass cell and the icon grow together). Render thread only.
 */
public final class RecipeCascade {
   private RecipeCascade() {}

   private static final long STAGGER_NS = 18_000_000L;
   private static final float W = 17.0F;              // ~0.28 s per item

   /** Dev only (DevShot): slow motion. */
   public static float timeScale = 1.0F;

   private static final WeakHashMap<Object, Long> BORN = new WeakHashMap<>();

   /** Schedule {@code buttons} (in page order) to cascade in from {@code baseNs}. */
   public static void schedule(List<?> buttons, long baseNs) {
      long step = (long) (STAGGER_NS / Math.max(0.01F, timeScale));
      for (int i = 0; i < buttons.size(); i++) BORN.put(buttons.get(i), baseNs + i * step);
   }

   /** Scale for a button this frame: -1 = not born yet (draw nothing), 1 = settled, else 0..1. */
   public static float scale(Object button) {
      Long b = BORN.get(button);
      if (b == null) return 1F;
      float t = (net.minecraft.util.Util.getNanos() - b) / 1.0e9F * timeScale;
      if (t < 0F) return -1F;
      float p = 1F - (1F + W * t) * (float) Math.exp(-W * t);
      if (p >= 0.998F) { BORN.remove(button); return 1F; }
      return p;
   }
}
