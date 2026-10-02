package dev.s1mp1e.glass.render;

/**
 * Damage trail for the health bar (fighting-game style): when health drops, the hearts just lost stay as white ghost
 * hearts for a short hold, then drain smoothly down to the current health while fading — instead of vanilla's on/off
 * blink. Healing never trails. The ghost range is handed to vanilla as its "display health" ({@code lastHealth}) with
 * blinking on, so vanilla draws the white hearts itself ({@code HeartTrailMixin}); this class only decides the range and
 * its opacity. Units are vanilla's: health points, 2 per heart. Render thread only.
 *
 * <p>Pure math — ported byte-for-byte from LiquidGlass26's {@code HealthTrail}; only the package changed. The 26.2
 * mixin drove it through the deferred {@code Hud.extractHearts}; the 1.21.1 mixin drives the identical values through
 * {@code InGameHud.renderHealthBar} (same param roles: {@code lastHealth} = the ghost top, {@code blinking} = active).
 */
public final class HealthTrail {
   private HealthTrail() {}

   private static final long HOLD_NS = 350_000_000L;
   private static final float DRAIN_TAU = 0.12F;
   private static final float MAX_ALPHA = 0.9F;

   private static float trail = Float.NaN;
   private static int lastHealth = -1;
   private static float drainFrom;
   private static long holdUntil;
   private static long lastNs;

   /** Once per frame with the current (ceil'd) health. */
   public static void update(int health) {
      long now = System.nanoTime();
      float dt = lastNs == 0L ? 0F : Math.min(0.05F, (now - lastNs) / 1.0e9F);
      lastNs = now;
      if (Float.isNaN(trail) || health >= trail) {        // first frame or healed past the trail: nothing to show
         trail = health;
         drainFrom = health;
         lastHealth = health;
         return;
      }
      if (health < lastHealth) {                           // a (new) hit: hold the ghost, remember where it started
         holdUntil = now + HOLD_NS;
         drainFrom = Math.max(drainFrom, trail);
      }
      lastHealth = health;
      if (now >= holdUntil) trail += (health - trail) * (1F - (float) Math.exp(-dt / DRAIN_TAU));
      if (trail - health < 0.05F) { trail = health; drainFrom = health; }
   }

   public static boolean active(int health) {
      return !Float.isNaN(trail) && trail > health + 0.01F;
   }

   /** The ghost's top, as vanilla's display-health (half-heart units, rounded up so the last half heart still shows). */
   public static int displayHealth(int health) {
      return active(health) ? (int) Math.ceil(trail) : health;
   }

   /** Ghost opacity: steady during the hold, then fading as it drains. */
   public static float ghostAlpha(int health) {
      if (!active(health)) return 1F;
      if (System.nanoTime() < holdUntil) return MAX_ALPHA;
      float span = drainFrom - health;
      float frac = span <= 0.01F ? 0F : (trail - health) / span;
      return MAX_ALPHA * Math.max(0F, Math.min(1F, frac));
   }
}
