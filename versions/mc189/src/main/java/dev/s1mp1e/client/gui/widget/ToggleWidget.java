package dev.s1mp1e.client.gui.widget;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;

/**
 * Liquid-glass on/off switch, modelled on the iOS Control-Center toggle.
 *
 * <p>The knob does not rigidly slide: it runs on an <b>ease-out-back</b> clock so
 * it overshoots the far edge and settles with one gentle bounce; mid-travel it
 * <b>stretches</b> horizontally into a lozenge and <b>squashes</b> vertically
 * (jelly volume), then rounds back to a circle at rest. The knob is drawn
 * <b>frosted</b> (translucent white) so the track colour shows through it —
 * green-tinted over the on-track, grey over the off-track — the cheap-but-correct
 * stand-in for glass refraction on the white-only shader. All edges use the
 * arc-based {@link GlassWidgets#fillRoundSmooth} so nothing stair-steps.
 *
 * <p>Bindable to a BOOL {@link Setting} or a {@link Module}'s enabled flag.
 */
public final class ToggleWidget extends Widget {

    public interface BoolBind { boolean get(); void set(boolean v); }

    private static final int   OFF_TRACK   = 0x78788A;  // iOS off-track grey
    private static final int   ON_TRACK    = 0x34C759;  // iOS system green
    private static final float DURATION_MS = 260f;      // full flip time
    private static final float STRETCH     = 0.62f;     // peak knob widening (× rest radius)
    private static final float SQUASH      = 0.14f;     // peak vertical squash (× rest radius)

    private final BoolBind bind;

    // Self-contained eased clock: a "leg" runs posFrom → posTo over DURATION_MS.
    private float posFrom, posTo;   // 0 = off/left, 1 = on/right
    private long  legStart;         // nanos; 0L = settled (idle)
    private float settled;          // resting position when idle
    private float envCarry;         // stretch envelope captured at an interrupt, decays into the new leg

    public ToggleWidget(BoolBind bind) {
        this.bind    = bind;
        this.settled = bind.get() ? 1f : 0f;
        this.posFrom = this.posTo = this.settled;
        this.legStart = 0L;
    }

    public static ToggleWidget forSetting(final Setting s) {
        return new ToggleWidget(new BoolBind() {
            public boolean get() { return s.boolValue; }
            public void set(boolean v) { s.boolValue = v; }
        });
    }

    public static ToggleWidget forModule(final Module m) {
        return new ToggleWidget(new BoolBind() {
            public boolean get() { return m.enabled; }
            public void set(boolean v) { m.setEnabled(v); }
        });
    }

    /** Ease-out-back: 0→1 with one small overshoot past 1, settling exactly at 1. */
    private static float easeBack(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        final float c1 = 1.55f;          // overshoot strength (~+8%)
        final float c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    /** Linear leg progress; advances the clock and retires the leg when done. */
    private float legProgress() {
        if (legStart == 0L) return 1f;
        float lin = (System.nanoTime() - legStart) / 1.0e6f / DURATION_MS;
        if (lin >= 1f) { legStart = 0L; settled = posTo; return 1f; }
        return lin;
    }

    /** Live eased position (0..1, may briefly overshoot); used to interrupt mid-flip. */
    private float currentPos() {
        if (legStart == 0L) return settled;
        float lin = (System.nanoTime() - legStart) / 1.0e6f / DURATION_MS;
        if (lin >= 1f) return posTo;
        return posFrom + (posTo - posFrom) * easeBack(lin);
    }

    /** Live stretch envelope, mirroring what draw() computes; captured on interrupt so the
     *  blob decays smoothly into the new leg instead of snapping back to a circle. */
    private float currentEnv() {
        if (legStart == 0L) return 0f;
        float lin = (System.nanoTime() - legStart) / 1.0e6f / DURATION_MS;
        if (lin >= 1f) return 0f;
        return Math.max((float) Math.sin(Math.PI * lin), envCarry * (1f - lin));
    }

    @Override
    public void draw(int mouseX, int mouseY, float pt, float alpha) {
        int a = Math.round(alpha * 255f);
        if (a <= 0) return;

        float lin = legProgress();
        boolean moving = legStart != 0L;
        float pos = posFrom + (posTo - posFrom) * easeBack(lin);
        // Stretch envelope: fresh sin() ramp, blended with any carried-in value from an
        // interrupt so it never snaps down. 0 at both ends & at rest, peaks mid-travel.
        float env = moving ? Math.max((float) Math.sin(Math.PI * lin), envCarry * (1f - lin)) : 0f;

        float h   = y1 - y0;
        float r   = h / 2f;
        float pad = 2f;
        float baseR = (h - pad * 2f) / 2f;
        float cy = (y0 + y1) / 2f;

        // faint edge shadow grounds the control on the glass
        GlassWidgets.dropShadow(x0, y0, x1, y1, r, alpha * 0.85f);
        // ---- track: grey base, green greens-in as the knob crosses ----
        float gt = pos < 0f ? 0f : (pos > 1f ? 1f : pos);
        GlassWidgets.fillRoundSmooth(x0, y0, x1, y1, ((int) (a * 0.55f) << 24) | OFF_TRACK, r);
        if (gt > 0.003f) {
            GlassWidgets.fillRoundSmooth(x0, y0, x1, y1, ((int) (a * gt) << 24) | ON_TRACK, r);
        }

        // ---- knob with jelly stretch/squash ----
        float cxLeft  = x0 + pad + baseR;
        float cxRight = x1 - pad - baseR;
        float cx = cxLeft + (cxRight - cxLeft) * pos;
        float halfW = baseR + baseR * STRETCH * env;
        float halfH = baseR - baseR * SQUASH  * env;
        float kx0 = cx - halfW, kx1 = cx + halfW;
        float ky0 = cy - halfH, ky1 = cy + halfH;
        // keep the blob within the track interior — pressing an end reads as a bounce
        if (kx0 < x0 + pad) kx0 = x0 + pad;
        if (kx1 > x1 - pad) kx1 = x1 - pad;

        // soft drop shadow for depth
        int sh = (int) (a * 0.20f);
        if (sh > 0) GlassWidgets.fillRoundSmooth(kx0, ky0 + 1f, kx1, ky1 + 1f, sh << 24, halfH);

        // frosted knob: translucent while moving (track refracts through), crisp at rest
        float ka = 0.86f + 0.12f * (1f - env);
        GlassWidgets.fillRoundSmooth(kx0, ky0, kx1, ky1, (Math.round(a * ka) << 24) | 0xFFFFFF, halfH);
    }

    @Override
    public boolean mouseClicked(int mx, int my, int btn) {
        if (btn != 0 || !inBounds(mx, my)) return false;
        boolean nv = !bind.get();
        bind.set(nv);
        // launch a fresh eased leg from the live position/stretch so rapid clicks never snap
        envCarry = currentEnv();
        posFrom  = currentPos();
        posTo    = nv ? 1f : 0f;
        legStart = System.nanoTime();
        S1mp1eConfig.save();
        return true;
    }
}
