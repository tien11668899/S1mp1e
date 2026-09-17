package dev.s1mp1e.client.gui.widget;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.Motion;
import dev.s1mp1e.client.module.HudGlass;
import net.minecraft.client.gui.DrawContext;

/**
 * Liquid Glass switch, matched to the iOS 26 reference recordings (液態玻璃切換 / 液態玻璃按鈕切換).
 *
 * <p>At rest the knob is a solid white lozenge. On toggle it turns into a clear glass LENS while it slides —
 * larger than the knob, the track colour visible (magnified) through it — and the track crossfades off ↔ on
 * underneath. Just before the knob arrives the lens shrinks back and frosts into the solid white knob. See
 * {@link GlassWidgets#knobLens} for how the lens is composed.
 *
 * <p>Motion ({@link Motion}): the knob travels on a critically damped 0.30 s spring; the lens forms on a 0.13 s
 * spring and re-forms on a 0.28 s one (the measured press / release asymmetry). Every spring runs on real frame
 * time and keeps its velocity when retargeted, so toggling again mid-flight turns the knob around smoothly with
 * the lens still up. A change made elsewhere (e.g. a module keybind while the menu is open) animates too.
 */
public final class ToggleWidget extends Widget {
    public interface BoolBind { boolean get(); void set(boolean v); }
    private static final int OFF_TRACK = 0x78788A, ON_TRACK = 0x34C759;
    private static final float REST_RATIO = 1.4f;   // knob w/h at rest (launcher 28/20)
    private static final float LENS_SCALE = 1.75f;  // lens size vs the rest knob: ~1.4x the TRACK height, as in the reference
    private static final float REFORM_AT  = 0.08f;  // travel left (fraction of the throw) when the lens starts re-forming
    private final BoolBind bind;
    private final Motion.Spring travel;             // 0 = off .. 1 = on
    private final Motion.Spring lift = new Motion.Spring(Motion.MORPH_IN_S, 0f);   // 0 = white knob, 1 = glass lens
    private final Motion.Clock clock = new Motion.Clock();
    private boolean lifted;

    public ToggleWidget(BoolBind bind) { this.bind = bind; this.travel = new Motion.Spring(Motion.TRAVEL_S, bind.get() ? 1f : 0f); }
    public static ToggleWidget forSetting(final Setting s) { return new ToggleWidget(new BoolBind() { public boolean get() { return s.boolValue; } public void set(boolean v) { s.boolValue = v; } }); }
    public static ToggleWidget forModule(final Module m) { return new ToggleWidget(new BoolBind() { public boolean get() { return m.enabled; } public void set(boolean v) { m.setEnabled(v); } }); }

    private void flipTo(float goal) {
        travel.retarget(goal);
        lifted = true;
        lift.tune(Motion.MORPH_IN_S, 0f).retarget(1f);
    }

    @Override public void draw(DrawContext g, int mouseX, int mouseY, float pt, float alpha) {
        float dt = clock.tick();
        float want = bind.get() ? 1f : 0f;
        if (travel.target != want) flipTo(want);
        travel.update(dt);
        travel.settle(0.0005f);
        if (lifted && Math.abs(travel.target - travel.x) < REFORM_AT) {
            lifted = false;
            lift.tune(Motion.MORPH_OUT_S, 0f).retarget(0f);
        }
        lift.update(dt);
        lift.settle(0.002f);

        int a = Math.round(alpha * 255f); if (a <= 0) return;
        float pos = Motion.clamp01(travel.x), morph = Motion.clamp01(lift.x);

        float h = y1 - y0, r = h / 2f, pad = 2f, cy = (y0 + y1) / 2f;
        float baseH = (h - pad * 2f) / 2f;      // knob half-height at rest
        float baseW = baseH * REST_RATIO;       // knob half-width at rest (lozenge)

        // track: OFF base always, ON colour crossfades in with travel
        GlassWidgets.fillRound(g, x0, y0, x1, y1, ((int) (a * 0.55f) << 24) | OFF_TRACK, r);
        if (pos > 0.003f) GlassWidgets.fillRound(g, x0, y0, x1, y1, ((int) (a * pos) << 24) | ON_TRACK, r);

        // knob → liquid-glass lens → knob, centred on its travelling position
        float cxL = x0 + pad + baseW, cxR = x1 - pad - baseW, cx = cxL + (cxR - cxL) * pos;
        int band = HudGlass.lerpArgb(0x8C000000 | OFF_TRACK, 0xFF000000 | ON_TRACK, pos);   // the track the lens sees
        GlassWidgets.knobLens(g, cx, cy, baseW, baseH, morph, LENS_SCALE, x0, x1, r, Float.NaN, band, band, alpha);
    }

    @Override public boolean mouseClicked(int mx, int my, int btn) {
        if (btn != 0 || !inBounds(mx, my)) return false;
        boolean nv = !bind.get(); bind.set(nv);
        flipTo(nv ? 1f : 0f);
        S1mp1eConfig.save(); return true;
    }
}
