package dev.s1mp1e.client.gui.widget;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.Motion;
import dev.s1mp1e.client.module.HudGlass;
import net.minecraft.client.gui.DrawContext;

/**
 * Liquid Glass switch, matched to the iOS 26 reference recordings (æ¶²æç»çåæ / æ¶²æç»çæéåæ).
 *
 * <p>At rest the knob is a solid white lozenge. On toggle it turns into a clear glass LENS while it slides â
 * larger than the knob, the track colour visible (magnified) through it â and the track crossfades off â on
 * underneath. Just before the knob arrives the lens shrinks back and frosts into the solid white knob. See
 * {@link GlassWidgets#knobLens} for how the lens is composed.
 *
 * <p>Two ways to use it, as on iOS: TAP anywhere on it to flip, or PRESS AND DRAG the knob across and let go â
 * the knob follows the pointer 1:1 (as the slider thumb does), and on release it settles to whichever side it
 * ended up nearest. Dragging the knob across and back before letting go leaves the switch unchanged, and so does
 * releasing away from the switch. While the finger is down but not moving the knob just swells slightly and stays
 * white; it only turns to glass once it actually moves, which is what the recording shows.
 *
 * <p>The throw is tiny (about 8 px on the config screen), so the tap/drag decision is made in POINTER pixels
 * ({@link #DRAG_SLOP_PX}), never as a fraction of the throw â otherwise a pixel of hand drift during a click
 * would count as a drag, commit back to the side it started on, and silently do nothing.
 *
 * <p>Motion ({@link Motion}): the knob travels on a critically damped 0.30 s spring; the lens forms on a 0.13 s
 * spring and re-forms on a 0.28 s one (the measured press / release asymmetry). Every spring runs on real frame
 * time and keeps its velocity when retargeted, so toggling again mid-flight turns the knob around smoothly with
 * the lens still up. A drag hands its own speed (per second, dropped when stale) to the settle, trimmed by
 * {@link Motion.Spring#settleMonotonic()} so the knob can never swing back past the side it committed to.
 */
public final class ToggleWidget extends Widget {
    public interface BoolBind { boolean get(); void set(boolean v); }
    private static final int OFF_TRACK = 0x78788A, ON_TRACK = 0x34C759;
    private static final float REST_RATIO = 1.55f;  // rest knob w/h (measured iOS-26: a wide lozenge, 0.85× track tall)
    // The lens is a symmetric BALLOON that OVERHANGS the track (measured): at peak 1.55× wide, 1.65× tall vs the
    // rest knob → 2.05× track-height wide, 1.40× track-height tall (overhangs top & bottom), ratio ~1.47, corner →~0.90.
    private static final float LENS_W_MUL = 1.55f, LENS_H_MUL = 1.65f, LENS_CORNER = 0.90f;
    private static final float SWITCH_MORPH_IN = 0.085f;   // solid→glass SNAP (~85 ms; slider keeps its own 0.13)
    private static final float REFORM_AT  = 0.08f;  // travel left (fraction of the throw) when the lens starts re-forming
    private static final float PRESS_GROW = 1.12f;  // finger down, not yet moving: the white knob swells a little
    private static final float DRAG_SLOP_PX  = 4f;  // pointer travel before a press counts as a drag
    private static final float CANCEL_SLOP_PX = 4f; // release this far outside the switch cancels a tap
    private static final float VEL_MAX = 8f;        // release speed cap, in throws per second
    private final BoolBind bind;
    private final Motion.Spring travel;             // 0 = off .. 1 = on
    private final Motion.Spring lift = new Motion.Spring(SWITCH_MORPH_IN, 0f);   // 0 = white knob, 1 = glass lens
    private final Motion.Spring press = new Motion.Spring(Motion.MORPH_IN_S, 0f);  // 0 = rest size, 1 = pressed swell
    private final Motion.Clock clock = new Motion.Clock();
    private boolean lifted;
    private boolean dragging, dragged;
    private double grabDX;                          // pointer x minus the knob centre it is anchored to
    private double pressMx, lastMx, lastMy, maxDragPx;
    private float pressPos;                         // knob position when the press started
    private float lastDragPos, dragVel;             // dragVel in throws per second
    private long lastDragNano;

    public ToggleWidget(BoolBind bind) { this.bind = bind; this.travel = new Motion.Spring(Motion.TRAVEL_S, bind.get() ? 1f : 0f); }
    public static ToggleWidget forSetting(final Setting s) { return new ToggleWidget(new BoolBind() { public boolean get() { return s.boolValue; } public void set(boolean v) { s.boolValue = v; } }); }
    public static ToggleWidget forModule(final Module m) { return new ToggleWidget(new BoolBind() { public boolean get() { return m.enabled; } public void set(boolean v) { m.setEnabled(v); } }); }

    private void flipTo(float goal) {
        travel.retarget(goal);
        lifted = true;
        lift.tune(SWITCH_MORPH_IN, 0f).retarget(1f);
    }

    private void setValue(boolean nv) {
        if (nv != bind.get()) {
            bind.set(nv);
            S1mp1eConfig.save();
        }
    }

    /** Knob half-sizes and centre travel for the current bounds (the geometry draw() uses). */
    private float knobHalfH() { return 0.85f * (y1 - y0) / 2f; }   // rest knob 0.85� the track height (measured)
    private float knobHalfW() { return knobHalfH() * REST_RATIO; }
    private float travelX0() { return x0 + 2f + knobHalfW(); }
    private float travelX1() { return x1 - 2f - knobHalfW(); }
    private float span() { return Math.max(1f, travelX1() - travelX0()); }

    @Override public void draw(DrawContext g, int mouseX, int mouseY, float pt, float alpha) {
        float dt = clock.tick();
        if (!dragging) {                                  // a change from elsewhere (keybind, reset) animates too
            float want = bind.get() ? 1f : 0f;
            if (travel.target != want) flipTo(want);
        }
        if (!dragged) {                                   // a held press must not freeze a flip already in flight
            travel.update(dt);
            travel.settle(0.0005f);
        }
        boolean moving = dragged || Math.abs(travel.target - travel.x) >= REFORM_AT;
        if (lifted && !moving) {
            lifted = false;
            lift.tune(Motion.MORPH_OUT_S, 0f).retarget(0f);
        }
        lift.update(dt);
        lift.settle(0.002f);
        press.retarget(dragging ? 1f : 0f).update(dt);
        press.settle(0.002f);

        int a = Math.round(alpha * 255f); if (a <= 0) return;
        float pos = Motion.clamp01(travel.x), morph = Motion.clamp01(lift.x);

        float h = y1 - y0, r = h / 2f, cy = (y0 + y1) / 2f;
        float baseH = knobHalfH(), baseW = knobHalfW();
        float swell = 1f + (PRESS_GROW - 1f) * Motion.clamp01(press.x) * (1f - morph);   // only while still white

        // track: OFF base always, ON colour crossfades in with travel
        GlassWidgets.fillRound(g, x0, y0, x1, y1, ((int) (a * 0.55f) << 24) | OFF_TRACK, r);
        if (pos > 0.003f) GlassWidgets.fillRound(g, x0, y0, x1, y1, ((int) (a * pos) << 24) | ON_TRACK, r);

        // knob â liquid-glass lens â knob, centred on its travelling position
        float cx = travelX0() + span() * pos;
        int band = HudGlass.lerpArgb(0x8C000000 | OFF_TRACK, 0xFF000000 | ON_TRACK, pos);   // the track the lens sees
        GlassWidgets.knobLens(g, cx, cy, baseW * swell, baseH * swell, morph, LENS_W_MUL, LENS_H_MUL, LENS_CORNER,
                              x0, x1, r, Float.NaN, band, band, alpha);
    }

    @Override public boolean mouseClickedPrecise(double mx, double my, int btn) {
        if (btn != 0 || !inBounds((int) mx, (int) my)) return false;
        dragging = true;
        dragged = false;
        pressMx = mx; lastMx = mx; lastMy = my; maxDragPx = 0.0;
        pressPos = Motion.clamp01(travel.x);
        // Anchor on the PRESS point, not on where the pointer is when the drag is recognised: a knob already
        // resting against its end would otherwise forget the movement the clamp swallowed, and coming back to
        // the press point would carry the knob all the way to the other side.
        grabDX = mx - (travelX0() + span() * pressPos);
        lastDragPos = pressPos;
        dragVel = 0f;
        lastDragNano = 0L;
        return true;
    }

    @Override public void mouseDraggedPrecise(double mx, double my, int btn) {
        if (!dragging) return;
        lastMx = mx; lastMy = my;
        maxDragPx = Math.max(maxDragPx, Math.abs(mx - pressMx));
        if (!dragged) {
            if (Math.abs(mx - pressMx) < DRAG_SLOP_PX) return;   // hand drift during a click is not a drag
            dragged = true;
            float here = Motion.clamp01(travel.x);
            if (Math.abs(here - pressPos) > 0.001f) {           // it kept flying while held: re-anchor, no jump
                grabDX = mx - (travelX0() + span() * here);
                lastDragPos = here;
            }
            lastDragNano = System.nanoTime();
            lifted = true;
            lift.tune(SWITCH_MORPH_IN, 0f).retarget(1f);
        }
        float pos = Motion.clamp01((float) ((mx - grabDX - travelX0()) / span()));
        long now = System.nanoTime();
        float edt = lastDragNano == 0L ? 1f / 60f
                : Math.max(1f / 240f, Math.min(0.05f, (now - lastDragNano) / 1.0e9f));
        lastDragNano = now;
        dragVel += ((pos - lastDragPos) / edt - dragVel) * Motion.ema(edt, 0.04f);   // throws per second
        lastDragPos = pos;
        travel.x = pos;                                  // 1:1 with the pointer, like the slider thumb
        travel.target = pos;
        travel.v = 0f;
    }

    @Override public void mouseReleased() {
        if (!dragging) return;
        dragging = false;
        boolean onWidget = lastMx >= x0 - CANCEL_SLOP_PX && lastMx <= x1 + CANCEL_SLOP_PX
                && lastMy >= y0 - CANCEL_SLOP_PX && lastMy <= y1 + CANCEL_SLOP_PX;
        if (!dragged) {
            if (onWidget) {                              // plain tap: flip
                boolean nv = !bind.get();
                setValue(nv);
                flipTo(nv ? 1f : 0f);
            }
            return;                                      // released away from the switch: cancelled
        }
        boolean nv = travel.x >= 0.5f;
        // A click with a little drift crosses the slop but not the halfway point, so it would commit back to the
        // side it started on and do nothing. If the pointer never really left the press point, treat it as a tap.
        if (nv == bind.get() && maxDragPx < knobHalfW() && onWidget) nv = !nv;
        float goal = nv ? 1f : 0f;
        travel.target = goal;
        float age = lastDragNano == 0L ? 1f : (System.nanoTime() - lastDragNano) / 1.0e9f;
        float v = age > 0.05f ? 0f : Math.max(-VEL_MAX, Math.min(VEL_MAX, dragVel));   // stale speed = no speed
        travel.v = travel.x == goal ? 0f : v;            // landed exactly on the end: nothing left to hand off
        travel.settleMonotonic();                        // never swing back past the side it committed to
        setValue(nv);
        lifted = true;
        lift.tune(SWITCH_MORPH_IN, 0f).retarget(1f);
        dragged = false;
    }

    @Override public boolean captures() { return dragging; }
}
