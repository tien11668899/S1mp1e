package dev.s1mp1e.o.glass.render;

/**
 * Damage trail for the health bar (fighting-game style, group 7): when health drops, the hearts just lost stay as white
 * ghost hearts for a short hold, then drain smoothly down to the current health while fading — instead of vanilla's
 * on/off blink. Healing never trails. The ghost range is handed to vanilla as its "display health"
 * ({@code healthLast}) with the highlight on, so vanilla draws the white hearts itself; this class only decides the
 * range and its opacity. Units are vanilla's: health points, 2 per heart. Pure math, byte-for-byte LiquidGlass26 /
 * mc1144. EntityRenderer thread only.
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

    public static void update(int health) {
        long now = System.nanoTime();
        float dt = lastNs == 0L ? 0F : Math.min(0.05F, (now - lastNs) / 1.0e9F);
        lastNs = now;
        if (Float.isNaN(trail) || health >= trail) {
            trail = health;
            drainFrom = health;
            lastHealth = health;
            return;
        }
        if (health < lastHealth) {
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

    public static int displayHealth(int health) {
        return active(health) ? (int) Math.ceil(trail) : health;
    }

    public static float ghostAlpha(int health) {
        if (!active(health)) return 1F;
        if (System.nanoTime() < holdUntil) return MAX_ALPHA;
        float span = drainFrom - health;
        float frac = span <= 0.01F ? 0F : (trail - health) / span;
        return MAX_ALPHA * Math.max(0F, Math.min(1F, frac));
    }
}
