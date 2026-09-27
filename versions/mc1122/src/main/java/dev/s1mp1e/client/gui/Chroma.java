package dev.s1mp1e.client.gui;

/**
 * Shared rainbow maths for the Chroma HUD module (H2) and the Block Outline chroma option (H1).
 * No Minecraft types — this is a verbatim port of 26.2's {@code Chroma} and stays identical across
 * every version so the look matches.
 *
 * <p>The hue at a screen position is
 * <pre>hue = time&middot;speed/{@link #BASE_PERIOD_S} &minus; (screenX+screenY)&middot;wave/240</pre>
 * so {@code speed} sweeps the whole thing (period {@code BASE_PERIOD_S/speed} s, i.e. 40 s at speed
 * 0.1 down to 0.8 s at speed 5) and {@code wave} gives each pixel column/row its own phase for a
 * diagonal per-character sweep; {@code wave == 0} makes one uniform cycling hue. The time base is
 * {@link System#nanoTime()} so it animates even while the game is paused.
 */
public final class Chroma {

    private Chroma() {}

    /** One full hue cycle at speed 1.0 takes this many seconds (26.2 constant). */
    public static final double BASE_PERIOD_S = 4.0;

    /**
     * The hue (0..1) at a screen position for the given speed/wave, sampled from the shared nanosecond
     * clock. {@code wave == 0} drops the spatial term (uniform hue).
     */
    public static float hue(double speed, double wave, double screenX, double screenY) {
        double t = System.nanoTime() / 1.0e9;
        double h = t * speed / BASE_PERIOD_S - (screenX + screenY) * wave / 240.0;
        h = h - Math.floor(h);
        return (float) h;
    }

    /** Convenience: a full ARGB colour cycling at {@code speed}, no spatial wave, alpha 0xFF. */
    public static int argb(double speed, float saturation) {
        return 0xFF000000 | rgb(hue(speed, 0.0, 0.0, 0.0), saturation);
    }

    /**
     * HSV&rarr;RGB with value 1.0, returning a packed {@code 0xRRGGBB} (no alpha). {@code hue} and
     * {@code sat} are clamped to 0..1.
     */
    public static int rgb(float hue, float sat) {
        hue = hue - (float) Math.floor(hue);
        if (sat < 0f) sat = 0f; else if (sat > 1f) sat = 1f;
        float h6 = hue * 6f;
        int i = (int) Math.floor(h6);
        float f = h6 - i;
        float p = 1f - sat;
        float q = 1f - sat * f;
        float t = 1f - sat * (1f - f);
        float r, g, b;
        switch (i % 6) {
            case 0:  r = 1f; g = t;  b = p;  break;
            case 1:  r = q;  g = 1f; b = p;  break;
            case 2:  r = p;  g = 1f; b = t;  break;
            case 3:  r = p;  g = q;  b = 1f; break;
            case 4:  r = t;  g = p;  b = 1f; break;
            default: r = 1f; g = p;  b = q;  break;
        }
        int ri = Math.round(r * 255f), gi = Math.round(g * 255f), bi = Math.round(b * 255f);
        return (ri << 16) | (gi << 8) | bi;
    }
}
