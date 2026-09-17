package dev.s1mp1e.client.gui.widget;

import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.client.gui.Motion;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

/**
 * Glass slider for INT / DOUBLE settings: drag the track, OR click the value to TYPE it in.
 *
 * <p>Feel, per the iOS reference recordings and Apple's own motion rules ({@link Motion}):
 * <ul>
 *   <li>While dragging, the thumb rides the pointer 1:1 and sub-pixel. It keeps the offset you grabbed it at,
 *       and the committed value snaps (INT) independently of where the thumb is drawn.</li>
 *   <li>Past either end the thumb rubber-bands (UIScrollView's 0.55 curve) while the value stays clamped.</li>
 *   <li>On release it settles onto the committed value on a critically damped 0.5 s spring, with no bounce.
 *       A click on bare track, a typed value or a reset glide there the same way, but faster.</li>
 *   <li>Held, the white capsule becomes a glass lens (0.13 s in, 0.28 s out): a rounded rectangle 1.48× wide and
 *       1.58× tall. Dragging stretches it wider and flatter at constant area, lagging the speed with a slight
 *       overshoot when the drag reverses. The blue fill reaches the lens centre while held and the pill's leading
 *       edge at rest. A quick tap leaves the lens up for 0.2 s.</li>
 * </ul>
 */
public final class SliderWidget extends Widget {
    private static final float THUMB_HW = 9f, THUMB_HH = 6f;   // rest white pill 18x12 (iOS measures 111x72, 1.54 : 1)
    private static final float TRACK_H = 4f;
    private final Setting s;
    private boolean dragging, editing;
    private float dragT;          // raw pointer ratio while dragging (unclamped; rubber-banded when drawn)
    private double grabDX;        // pointer x minus thumb centre at press
    private boolean rebase;       // next frame: glide from the old drawn spot to the new base (track click)
    private boolean released;     // next frame: glide onto the committed value with the settle spring
    private boolean committedThisPress;   // the screen's click-off commit ran for the press being dispatched
    private long pressNano, holdUntilNano;
    private boolean lifted;
    private final Motion.Clock clock = new Motion.Clock();
    private final Motion.Spring lift = new Motion.Spring(Motion.MORPH_IN_S, 0f);   // 0 = white pill, 1 = glass lens
    private final Motion.Spring glide = new Motion.Spring(Motion.JUMP_S, 0f);      // drawn ratio minus base, decays to 0
    private final Motion.Spring stretch = new Motion.Spring(Motion.STRETCH_S, 1f).tune(Motion.STRETCH_S, Motion.STRETCH_BOUNCE);
    private final float[] lens = new float[3];
    private float lastBase = Float.NaN, drawnT = Float.NaN, lastThumbPx = Float.NaN, speed;
    private String buffer = "";
    private long editStart;   // for the enter animation

    private float editFade() {
        if (editStart == 0L) return 1f;
        float f = (System.nanoTime() - editStart) / 1.0e6f / 150f;
        return f < 0f ? 0f : (f > 1f ? 1f : f);
    }

    public SliderWidget(Setting s) { this.s = s; }

    private String fmt(double v) {
        if (s.type == Setting.Type.INT) return String.valueOf((int) Math.round(v));
        if (v == Math.floor(v)) return String.valueOf((int) v);
        return String.format(java.util.Locale.ROOT, "%.2f", v);   // '.' matches parse + charTyped
    }
    private String valueStr() { return s.type == Setting.Type.INT ? String.valueOf(s.intValue) : fmt(s.doubleValue); }
    private String shownStr() { return editing ? buffer : valueStr(); }
    private float valueW() { return GlassWidgets.strW(shownStr()) + (editing ? 5f : 0f); }
    /** Widest value this setting can show, so the track end doesn't move while the number changes under a drag. */
    private float reservedW() {
        float w = Math.max(GlassWidgets.strW(fmt(s.min)), GlassWidgets.strW(fmt(s.max)));
        if (s.type != Setting.Type.INT) {
            w = Math.max(w, GlassWidgets.strW(String.format(java.util.Locale.ROOT, "%.2f", s.min)));
            w = Math.max(w, GlassWidgets.strW(String.format(java.util.Locale.ROOT, "%.2f", s.max)));
        }
        return w;
    }
    /** Track end. Reserves the widest value plus the type-in caret gap and never depends on what is being typed, so
     *  the track (and a press mapped onto it) is the same in and out of type-in; a long typed value may overlap it. */
    private float trackX1() { return x1 - reservedW() - 15f; }

    @Override public void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float pt, float alpha) {
        float dt = clock.tick();
        long now = System.nanoTime();
        int a = Math.round(alpha * 255f);
        float tx0 = x0, tx1 = trackX1(), tw = Math.max(1f, tx1 - tx0);
        float cy = (y0 + y1) / 2f;
        float ty0 = cy - TRACK_H / 2f, ty1 = cy + TRACK_H / 2f;

        // ---- where the thumb sits ----
        float base;
        if (dragging) {
            float clamped = Motion.clamp01(dragT);
            float overPx = (dragT - clamped) * tw;
            base = clamped + Math.signum(overPx) * Motion.rubberBand(Math.abs(overPx), THUMB_HW * 2f) / tw;
        } else {
            base = (float) s.normalised();
        }
        if (!Float.isNaN(lastBase) && (!dragging || rebase) && base != lastBase) {
            if (released) glide.tune(Motion.SETTLE_S, 0f);                                  // let go
            else if (rebase || (glide.x == 0f && glide.v == 0f)) glide.tune(Motion.JUMP_S, 0f);   // track click / typed value / reset
            glide.x += lastBase - base;          // keep the drawn thumb where it was, then glide to the new base
            glide.retarget(0f);
            if (released) glide.settleMonotonic();   // e.g. let go mid track-click glide: no overshoot past the notch
            else glide.capOvershoot();
        }
        released = false;
        rebase = false;
        lastBase = base;
        glide.update(dt);
        glide.settle(0.0002f);
        float t = base + glide.x;
        drawnT = t;
        float fx = tx0 + tw * t;

        // ---- lens: morph + speed stretch ----
        if (!dragging && lifted && now >= holdUntilNano) {
            lifted = false;
            lift.tune(Motion.MORPH_OUT_S, 0f).retarget(0f);
        }
        lift.update(dt);
        lift.settle(0.002f);
        float L = Motion.clamp01(lift.x);
        if (!Float.isNaN(lastThumbPx) && dt > 0f) {
            float inst = Math.abs(fx - lastThumbPx) / dt;
            speed += (inst - speed) * Motion.ema(dt, Motion.SPEED_TAU_S);
        }
        lastThumbPx = fx;
        stretch.retarget(Motion.stretchTarget(speed, THUMB_HW * 2f)).update(dt);
        Motion.lensShape(stretch.x, lens);

        // ---- paint ----
        GlassWidgets.fillRound(g, tx0, ty0, tx1, ty1, ((int) (a * 0.30f) << 24) | 0xFFFFFF, TRACK_H / 2f);
        // rest: the fill's round end tucks just under the pill's left edge (no track sliver shows at the junction);
        // held: it reaches the lens centre. None at all while the thumb sits at / rubber-bands past the start.
        float fillEnd = Math.min(tx1, fx - (THUMB_HW - TRACK_H) * (1f - L));
        if (fillEnd > tx0 + TRACK_H)
            GlassWidgets.fillRound(g, tx0, ty0, Math.max(tx0 + TRACK_H, fillEnd), ty1, (a << 24) | 0x0A84FF, TRACK_H / 2f);
        GlassWidgets.knobLens(g, fx, cy, THUMB_HW, THUMB_HH, L, lens[0], lens[1], lens[2], tx0, tx1, TRACK_H / 2f,
                Math.min(tx1, fx), 0xFF0A84FF, 0x4DFFFFFF, alpha);

        String v = shownStr();
        int vcol = editing ? 0x0A84FF : 0xC7C7CC;
        float vx = x1 - GlassWidgets.strW(v) - (editing ? 5f : 0f);
        if (editing) {
            // typed-entry pill fades/springs in; caret pulses smoothly (coherent, not a hard blink)
            float ef = editFade();
            float pillL = vx - 4f, pillR = x1;
            float grown = pillL + (pillR - pillL) * (0.55f + 0.45f * ef);   // grows out from the value
            GlassWidgets.capsule(g, pillL, cy - 9, grown, cy + 9, 0.6f, 0.5f, alpha * ef, true);
            float pulse = 0.30f + 0.70f * (0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 1.0e9 * Math.PI * 2 / 0.9));
            int cA = Math.round(a * pulse * ef);
            GlassWidgets.fill(g, grown - 3, cy - 6, grown - 2, cy + 6, (cA << 24) | 0x0A84FF);
        }
        GlassWidgets.label(g, v, vx, cy - GlassWidgets.fontH() / 2f, vcol, alpha);
    }

    /** Pointer x → raw ratio (honouring the grab offset), and commit the clamped value. */
    private void apply(double mx) {
        float tx0 = x0, tw = Math.max(1f, trackX1() - tx0);
        dragT = (float) ((mx - grabDX - tx0) / tw);
        double val = s.min + Motion.clamp01(dragT) * (s.max - s.min);
        if (s.type == Setting.Type.INT) s.setInt((int) Math.round(val)); else s.setDouble(val);
    }

    private void commit() {
        try {
            String b = buffer.trim();
            if (!b.isEmpty() && !b.equals("-") && !b.equals(".")) {
                double val = Double.parseDouble(b);
                if (val < s.min) val = s.min; if (val > s.max) val = s.max;
                if (s.type == Setting.Type.INT) s.setInt((int) Math.round(val)); else s.setDouble(val);
                S1mp1eConfig.save();
            }
        } catch (NumberFormatException ignored) { /* keep old value */ }
        editing = false;
    }

    @Override public boolean mouseClickedPrecise(double mx, double my, int btn) {
        if (btn != 0) return false;
        if (my >= y0 && my < y1) {
            float tx0 = x0, tx1 = trackX1(), tw = Math.max(1f, tx1 - tx0);
            float t = Float.isNaN(drawnT) ? (float) s.normalised() : drawnT;
            double fx = tx0 + tw * t;
            // while typing, the value pill can grow left over the track end: pressing your own digits is a value press
            float valueX0 = editing ? Math.min(tx1 + 2f, x1 - GlassWidgets.strW(buffer) - 9f) : tx1 + 2f;
            boolean inValue = mx >= valueX0 && mx <= x1;
            boolean onThumb = Math.abs(mx - fx) <= THUMB_HW + 3 && !(editing && inValue);
            boolean onValue = inValue && !onThumb;
            boolean onTrack = mx >= tx0 - 4 && mx <= tx1 + 6;
            if (!onThumb && !onValue && !onTrack) return false;
            // A press that finishes type-in only finishes it, whether the commit runs here or ran in the screen's
            // click-off pass just before (that pass tests the widget bounds, narrower than the slop accepted here).
            if (committedThisPress) { committedThisPress = false; return true; }
            if (editing) {
                if (!onValue) commit();                      // on the value while typing: keep the buffer
                return true;
            }
            if (onValue) {                                   // click the value -> type it in
                editing = true; buffer = valueStr(); editStart = System.nanoTime(); return true;
            }
            {
                if (onThumb) {                               // on the thumb: keep the grab offset, no jump
                    // Measure the grab from the clamped position; any leftover rubber-band overshoot keeps easing out
                    // on the glide spring while dragging (no inward jump on the first frame, no dead zone).
                    float c = Motion.clamp01(t);
                    grabDX = mx - (tx0 + tw * c);
                    glide.tune(Motion.JUMP_S, 0f);
                    glide.x = t - c;
                    glide.v = 0f;
                    glide.retarget(0f);
                    lastBase = c;
                } else {                                     // bare track: the thumb glides to the pointer
                    grabDX = 0;
                    rebase = true;
                }
                dragging = true;
                pressNano = System.nanoTime();
                lifted = true;
                lift.tune(Motion.MORPH_IN_S, 0f).retarget(1f);
                apply(mx);
                return true;
            }
        }
        return false;
    }
    @Override public void mouseDraggedPrecise(double mx, double my, int btn) { if (dragging) apply(mx); }
    @Override public void clickDispatched() { committedThisPress = false; }
    @Override public void mouseReleased() {
        if (!dragging) return;
        dragging = false;
        released = true;
        long now = System.nanoTime();
        holdUntilNano = (now - pressNano) / 1.0e9f < Motion.TAP_S ? now + (long) (Motion.TAP_HOLD_S * 1.0e9f) : now;
        S1mp1eConfig.save();
    }

    @Override public boolean editing() { return editing; }
    @Override public void loseFocus() {
        if (editing) { commit(); committedThisPress = true; }
    }

    @Override public boolean keyPressed(int keyCode) {
        if (!editing) return false;
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) { commit(); return true; }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { editing = false; return true; }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) { if (!buffer.isEmpty()) buffer = buffer.substring(0, buffer.length() - 1); return true; }
        return false;   // let other keys (e.g. the menu-close bind) pass through to the screen
    }
    @Override public boolean charTyped(char c) {
        if (!editing) return false;
        if ((c >= '0' && c <= '9') || (c == '.' && s.type != Setting.Type.INT) || (c == '-' && buffer.isEmpty())) {
            if (buffer.length() < 8) buffer += c;
        }
        return true;
    }

    @Override public boolean captures() { return dragging; }
}
