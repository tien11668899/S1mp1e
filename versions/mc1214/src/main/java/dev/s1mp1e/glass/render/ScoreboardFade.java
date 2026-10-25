package dev.s1mp1e.glass.render;

import net.minecraft.scoreboard.ScoreboardObjective;

/**
 * Fade the scoreboard sidebar in when it appears and out when it's hidden. {@code InGameHud.renderScoreboardSidebar} draws
 * the sidebar only while a sidebar objective is set, so it pops in and out. {@link #resolve(ScoreboardObjective)} is called
 * from that objective lookup with the real objective: it eases {@link #alpha()} up while one is set and, for {@link #OUT_S}
 * after it clears, keeps returning the last objective so the sidebar keeps drawing while its alpha eases to 0 (the objective
 * itself usually still exists — only its display slot was cleared — so its scores are still readable). Render thread only.
 * Ported from 26.2 (yarn {@code ScoreboardObjective} in place of Mojang {@code Objective}).
 */
public final class ScoreboardFade {
   private ScoreboardFade() {}

   private static final float IN_S = 0.15F;
   private static final float OUT_S = 0.15F;

   private static boolean prevPresent;
   private static long legNs;
   private static boolean out;
   private static float legFrom;
   private static float alpha;
   private static ScoreboardObjective last;

   /** @param real the current sidebar objective (or null); @return the objective to actually draw this frame (cached during fade-out). */
   public static ScoreboardObjective resolve(ScoreboardObjective real) {
      boolean present = real != null;
      long now = System.nanoTime();
      if (present) last = real;
      if (present != prevPresent) {
         legFrom = alpha;
         legNs = now;
         out = !present;
         prevPresent = present;
      }
      float dur = out ? OUT_S : IN_S;
      float t = (now - legNs) / 1.0e9F / dur;
      t = t < 0F ? 0F : (t > 1F ? 1F : t);
      alpha = legFrom + ((out ? 0F : 1F) - legFrom) * t;
      if (present) return real;
      return alpha > 0.004F ? last : null;   // keep drawing the cached objective through the fade-out
   }

   public static float alpha() {
      return alpha;
   }
}
