package dev.s1mp1e.client;

/**
 * Shared "chroma" colour math: an animated rainbow hue driven by wall-clock time.
 *
 * <p>Ported verbatim from the 26.2 reference ({@code dev.s1mp1e.client.Chroma}); pure int/float
 * maths with no Minecraft types, so it is identical across every S1mp1e version. Used by
 * {@link dev.s1mp1e.client.module.BlockOutlineModule} (world-space outline colour) and
 * {@link dev.s1mp1e.client.module.ChromaHudModule} (HUD text / accents), so both cycle on the same
 * clock and read as one effect.
 *
 * <p>Time base: {@link System#nanoTime()} — frame-rate independent, and it keeps moving while the
 * game is paused. At speed 1.0 one full trip around the hue wheel takes {@link #BASE_PERIOD_S}
 * seconds.
 */
public final class Chroma {

    private Chroma() {}

    /** Seconds for one full hue cycle at speed 1.0 (so the 0.1..5.0 speed range spans 40 s .. 0.8 s). */
    public static final double BASE_PERIOD_S = 4.0;

    /**
     * Hue in [0,1) for "now", advanced by {@code speed} cycles per {@link #BASE_PERIOD_S} and shifted
     * by {@code offset} (in cycles). A positive offset makes that point run AHEAD of the base hue.
     */
    public static float hue(double speed, double offset) {
        double t = System.nanoTime() * 1.0e-9;
        double h = t * speed / BASE_PERIOD_S + offset;
        return (float) (h - Math.floor(h));
    }

    /** HSV (each 0..1; hue wraps) -> packed {@code 0xRRGGBB}. */
    public static int rgb(float h, float s, float v) {
        h -= (float) Math.floor(h);
        s = s < 0f ? 0f : (s > 1f ? 1f : s);
        v = v < 0f ? 0f : (v > 1f ? 1f : v);
        float h6 = h * 6f;
        int i = (int) h6 % 6;
        float f = h6 - (float) Math.floor(h6);
        float p = v * (1f - s), q = v * (1f - f * s), t = v * (1f - (1f - f) * s);
        float r, g, b;
        switch (i) {
            case 0:  r = v; g = t; b = p; break;
            case 1:  r = q; g = v; b = p; break;
            case 2:  r = p; g = v; b = t; break;
            case 3:  r = p; g = q; b = v; break;
            case 4:  r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }
        return (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
    }

    /** {@link #rgb} with an alpha byte (0..255) packed on top -> {@code 0xAARRGGBB}. */
    public static int argb(int alpha, float h, float s, float v) {
        return ((alpha & 0xFF) << 24) | rgb(h, s, v);
    }

    /** Alpha byte of a packed ARGB colour. */
    public static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }
}
