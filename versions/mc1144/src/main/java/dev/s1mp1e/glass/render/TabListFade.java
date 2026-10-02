package dev.s1mp1e.glass.render;

/**
 * The player tab list fades in on key-press AND out on release. Vanilla renders the list only while the player-list key is
 * held (in {@code InGameHud.renderPlayerList}) and pops it away the instant the key comes up. {@link #gate(boolean)} is
 * called from that render gate with the real key state: it keeps returning {@code true} for {@link #OUT_S} after the key is
 * released so the list keeps rendering while {@link #alpha()} eases from its current value down to 0, then returns
 * {@code false} and the list stops. The glass fills, names and header/footer all take {@link #alpha()} (see
 * {@code TabListGlassMixin}), so the whole list fades as one. Render thread only. Ported verbatim from 26.2.
 */
public final class TabListFade {
   private TabListFade() {}

   private static final float IN_S = 0.15F;
   private static final float OUT_S = 0.15F;

   private static boolean prevDown;
   private static long legNs;
   /** Starts true: before the first press the list is faded out. With false the idle gate (key up, no leg yet)
    *  computed alpha = 1, so the very first press of a session started from 1 and popped in instead of fading. */
   private static boolean out = true;
   private static float legFrom;
   private static float alpha;

   /** @param realDown the true player-list key state; @return whether the list should render this frame (held, or fading out). */
   public static boolean gate(boolean realDown) {
      long now = System.nanoTime();
      if (realDown != prevDown) {          // edge: start a new leg from wherever the alpha is now
         legFrom = alpha;
         legNs = now;
         out = !realDown;
         prevDown = realDown;
      }
      float dur = out ? OUT_S : IN_S;
      float t = (now - legNs) / 1.0e9F / dur;
      t = t < 0F ? 0F : (t > 1F ? 1F : t);
      float target = out ? 0F : 1F;
      alpha = legFrom + (target - legFrom) * t;
      return realDown || alpha > 0.004F;   // keep the render branch alive through the fade-out
   }

   /** Current list opacity, 0..1. */
   public static float alpha() {
      return alpha;
   }
}
