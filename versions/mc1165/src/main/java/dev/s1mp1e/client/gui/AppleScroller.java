package dev.s1mp1e.client.gui;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.client.util.math.MatrixStack;

/**
 * The macOS overlay scroller, for every scrolling page: no permanent track, just a thin round-ended knob floating at the
 * right edge of the content. It snaps in while the content moves (and briefly when a page first appears, like
 * {@code NSScrollView.flashScrollers}), lingers 1 s after the last movement and then fades out over 0.3 s. With the pointer
 * on the strip the knob widens and a liquid-glass track (the glass-button material, a true capsule) rises behind it.
 *
 * <p>Numbers (GUI px; x2 at the usual GUI scale): knob 3 -> 5 wide, 1.5 px off the edge, track 1 px of air around the
 * widened knob. The knob is white with a faint dark halo so it reads on both the frosted glass panels and the dark world
 * behind them.
 *
 * <p>1.18.2 port of the 1.20.1 line: painted through {@link AllGlass} (which bakes the current {@link MatrixStack},
 * flushes the buffered draws and disables depth test), so it layers above the rows.
 *
 * <p>The caller passes the knob position from its own scroll maths, so dragging stays exactly 1:1 with where the knob is
 * drawn; only the painting changes. State is per owner (the scroll area / page object) and dies with it.
 */
public final class AppleScroller {

    private AppleScroller() {}

    /** Knob width at rest and with the pointer on the strip, in GUI px. */
    public static final float IDLE_W = 3.0F, HOVER_W = 5.0F;
    /** Knob distance from the strip's right edge, and the track's air around the widened knob. */
    public static final float EDGE = 1.5F, TRACK_PAD = 1.0F;
    /** Shortest knob, in GUI px. */
    public static final float MIN_LEN = 12.0F;
    /** Track ends pulled in past the glass panels' corner radius (hotbar radius 6.3) so the scroller never
     *  runs into a rounded corner (user, 2026-10-05). */
    public static final float END_INSET = 8.0F;
    /** Linger after the last movement, fade-out, fade-in (macOS snaps in), widen/narrow time constant. */
    private static final float LINGER_S = 1.0F, FADE_OUT_S = 0.30F, FADE_IN_S = 0.08F, EXPAND_TAU = 0.07F;
    /** Not drawn for this long = the page was away; flash the scroller again when it comes back. */
    private static final long AWAY_NS = 500_000_000L;

    private static final class State {
        double lastAmount = Double.NaN;
        long lastActive, lastFrame;
        float vis, expand;
    }

    private static final Map<Object, State> STATES = new WeakHashMap<Object, State>();

    /**
     * Draw one overlay scroller.
     *
     * @param owner     identity of the scrolling thing (state key)
     * @param right     the strip's right edge
     * @param top       the strip's top
     * @param bottom    the strip's bottom
     * @param knobTop   knob top from the caller's scroll maths
     * @param knobLen   knob length from the caller's scroll maths
     * @param amount    current scroll position (any change shows the scroller)
     * @param hover     pointer on the strip
     * @param drag      knob being dragged
     * @param alpha     screen / panel fade multiplier
     */
    public static void draw(MatrixStack g, Object owner, float right, float top, float bottom,
                            float knobTop, float knobLen, double amount, boolean hover, boolean drag, float alpha) {
        if (owner == null || bottom - top < 4.0F) return;
        long now = System.nanoTime();
        State s = STATES.get(owner);
        if (s == null) {
            s = new State();
            STATES.put(owner, s);
        }
        if (s.lastFrame == 0L || now - s.lastFrame > AWAY_NS) {
            s.lastActive = now;      // page (re)appeared: flash
            s.lastFrame = now;
        }
        float dt = Math.min(0.1F, (now - s.lastFrame) / 1.0e9F);
        s.lastFrame = now;
        if (!Double.isNaN(s.lastAmount) && Math.abs(amount - s.lastAmount) > 0.01) s.lastActive = now;
        s.lastAmount = amount;
        if (hover || drag) s.lastActive = now;

        boolean up = (now - s.lastActive) / 1.0e9F < LINGER_S;
        s.vis = up ? Math.min(1.0F, s.vis + dt / FADE_IN_S) : Math.max(0.0F, s.vis - dt / FADE_OUT_S);
        float want = hover || drag ? 1.0F : 0.0F;
        s.expand += (want - s.expand) * (1.0F - (float) Math.exp(-dt / EXPAND_TAU));

        float v = smooth(s.vis) * clamp01(alpha);
        if (v <= 0.004F) return;
        float ex = s.expand;

        float w = IDLE_W + (HOVER_W - IDLE_W) * ex;
        float kx1 = right - EDGE, kx0 = kx1 - w;
        // Shorten the strip at both ends (clear of the glass corners) and map the caller's knob onto it proportionally.
        float inset = Math.min(END_INSET, (bottom - top) * 0.15F);
        float sTop = top + inset, sBot = bottom - inset, full = bottom - top, part = sBot - sTop;
        float len = Math.max(MIN_LEN, Math.min(knobLen * part / full, part));
        float travel = full - knobLen, ratio = travel > 0.0F ? clamp01((knobTop - top) / travel) : 0.0F;
        float ky0 = sTop + ratio * (part - len), ky1 = ky0 + len;

        // Track: a glass capsule behind the knob while the pointer is on the strip, with the macOS hairline on its
        // content side.
        if (ex > 0.02F) {
            float te = ex * v;
            float tx0 = kx1 - HOVER_W - TRACK_PAD, tx1 = kx1 + TRACK_PAD;
            AllGlass.capsule(g, tx0, sTop, tx1, sBot, 1.0F, 0.0F, 0.55F * te);
            AllGlass.scrim(g, tx0 - 0.5F, sTop + 2.0F, tx0, sBot - 2.0F, 0.0F, byteA(0.10F * te) | 0xFFFFFF);
        }
        // Knob: faint dark halo (contrast on the light frosted panels), then the white capsule.
        AllGlass.scrim(g, kx0 - 0.5F, ky0 - 0.5F, kx1 + 0.5F, ky1 + 0.5F, (w + 1.0F) / 2.0F, byteA(0.22F * v));
        AllGlass.scrim(g, kx0, ky0, kx1, ky1, w / 2.0F, byteA((0.62F + 0.20F * ex) * v) | 0xFFFFFF);
    }

    /** Whether the pointer is close enough to the strip to count as hovering it (a little wider than the knob). */
    public static boolean near(double mx, double my, float right, float top, float bottom) {
        return mx >= right - HOVER_W - EDGE - TRACK_PAD - 3.0F && mx <= right + 1.0F && my >= top && my < bottom;
    }

    private static int byteA(float a) {
        return Math.round(clamp01(a) * 255.0F) << 24;
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : (v > 1.0F ? 1.0F : v);
    }

    private static float smooth(float t) {
        t = clamp01(t);
        return t * t * (3.0F - 2.0F * t);
    }
}
