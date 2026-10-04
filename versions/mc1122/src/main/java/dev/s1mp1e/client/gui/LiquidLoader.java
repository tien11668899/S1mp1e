package dev.s1mp1e.client.gui;

import java.util.WeakHashMap;

/**
 * The liquid-glass loading indicator inside every {@link LoadingCard} (group 10) — a thin track with the same liquid
 * knob as the switches and sliders ({@link GlassWidgets#knobLens}). Indeterminate ({@code progress < 0}): the knob
 * glides end to end on a sine sweep, turning into a stretched glass lens with speed, a blue trail following on a
 * spring. Determinate (0..1): blue fills to the progress on a spring, the knob rides the edge and breathes, a sheen
 * sweeps the filled part. Identical constants and motion to mc1144 / 26.2 {@code LiquidLoader}; state per screen.
 */
public final class LiquidLoader {
    private LiquidLoader() {}

    public static final float TRACK_W = 128.0F;
    public static final float ROW_H = 8.0F;

    private static final float TRACK_HH = 2.0F;
    private static final float KNOB_HW = 7.0F, KNOB_HH = 4.0F;
    private static final int TRACK_COL = 0x40FFFFFF;
    private static final int FILL_COL = 0xFF0A84FF;
    private static final float SWEEP_S = 1.6F;
    private static final float TAIL_S = 0.42F;
    private static final float SHEEN_S = 1.5F;
    private static final float BREATH_S = 1.8F;
    private static final float FULL_LENS_SPEED = 150.0F;
    private static final float MAX_LENS = 0.55F;

    private static final class State {
        final Motion.Clock clock = new Motion.Clock();
        final Motion.Spring tail = new Motion.Spring(TAIL_S, 0.0F);
        final Motion.Spring fill = new Motion.Spring(Motion.SETTLE_S, 0.0F);
        final Motion.Spring morph = new Motion.Spring(Motion.MORPH_OUT_S, 0.0F);
        final Motion.Spring stretch = new Motion.Spring(Motion.STRETCH_S, 1.0F);
        final long t0 = System.nanoTime();
        float prevHead = Float.NaN;
        float speed;
        boolean init;
        boolean wasDeterminate;
    }

    private static final WeakHashMap<Object, State> STATES = new WeakHashMap<Object, State>();
    private static final float[] SHAPE = new float[3];

    public static void draw(Object key, float cx, float cy, float progress) {
        State s = STATES.get(key);
        if (s == null) { s = new State(); STATES.put(key, s); }
        float dt = s.clock.tick();
        float t = (System.nanoTime() - s.t0) / 1.0E9F;
        float x0 = cx - TRACK_W / 2.0F, x1 = cx + TRACK_W / 2.0F;
        float lo = x0 + KNOB_HW, hi = x1 - KNOB_HW;
        boolean determinate = progress >= 0.0F;

        GlassWidgets.fillRound(x0, cy - TRACK_HH, x1, cy + TRACK_HH, TRACK_COL, TRACK_HH);

        float head, from;
        if (determinate) {
            float target = lo + Motion.clamp01(progress) * (hi - lo);
            if (!s.init || !s.wasDeterminate) s.fill.snap(s.init ? Math.min(s.prevHead, target) : lo);
            s.fill.retarget(target);
            s.fill.update(dt);
            head = s.fill.x;
            from = x0;
        } else {
            float p = 0.5F - 0.5F * (float) Math.cos(2.0 * Math.PI * t / SWEEP_S);
            head = lo + p * (hi - lo);
            if (!s.init) s.tail.snap(head);
            s.tail.retarget(head);
            s.tail.update(dt);
            from = s.tail.x;
        }
        s.init = true;
        s.wasDeterminate = determinate;

        float v = Float.isNaN(s.prevHead) || dt <= 0.0F ? 0.0F : Math.abs(head - s.prevHead) / dt;
        s.prevHead = head;
        s.speed += (v - s.speed) * Motion.ema(dt, Motion.SPEED_TAU_S);
        s.stretch.retarget(Motion.stretchTarget(s.speed, 2.0F * KNOB_HW));
        s.stretch.update(dt);
        float breath = determinate ? 0.22F * (0.5F - 0.5F * (float) Math.cos(2.0 * Math.PI * t / BREATH_S)) : 0.0F;
        float morphTarget = Math.max(breath, MAX_LENS * Motion.clamp01(s.speed / FULL_LENS_SPEED));
        s.morph.tune(morphTarget > s.morph.x ? Motion.MORPH_IN_S : Motion.MORPH_OUT_S, 0.0F).retarget(morphTarget);
        s.morph.update(dt);

        float a = Math.max(x0, Math.min(from, head) - TRACK_HH);
        float b = Math.min(x1, Math.max(from, head) + TRACK_HH);
        GlassWidgets.fillRound(a, cy - TRACK_HH, b, cy + TRACK_HH, FILL_COL, TRACK_HH);

        if (determinate && head - x0 > 3.0F * KNOB_HW) {
            float len = 16.0F;
            float span = head - x0 + len;
            float sx = x0 - len + ((t % SHEEN_S) / SHEEN_S) * span;
            float sx0 = Math.max(x0, sx), sx1 = Math.min(head, sx + len);
            if (sx1 - sx0 > 1.0F) GlassWidgets.fillRound(sx0, cy - TRACK_HH, sx1, cy + TRACK_HH, 0x66FFFFFF, TRACK_HH);
        }

        Motion.lensShape(s.stretch.x, SHAPE);
        boolean blueLeft = determinate || from <= head;
        GlassWidgets.knobLens(head, cy, KNOB_HW, KNOB_HH, s.morph.x, SHAPE[0], SHAPE[1], SHAPE[2],
                x0, x1, TRACK_HH, head, blueLeft ? FILL_COL : TRACK_COL, blueLeft ? TRACK_COL : FILL_COL, 1.0F);
    }
}
