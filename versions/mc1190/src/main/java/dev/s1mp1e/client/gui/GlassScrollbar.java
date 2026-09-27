package dev.s1mp1e.client.gui;

import net.minecraft.client.util.math.MatrixStack;

/**
 * Shared VERTICAL liquid-glass scrollbar — the config screen's {@code SliderWidget} look and feel stood on its side,
 * so every vanilla container scrollbar (creative item grid, stonecutter / loom recipe lists, villager trades) reads
 * like the S1mp1e menu instead of a flat sprite. The 1.19.2 core-profile port of 26.2's {@code GlassScrollbar}
 * (its {@code GuiGraphicsExtractor} swapped for the {@link MatrixStack} the older-line {@link GlassWidgets} takes).
 *
 * <p>One instance lives per screen (a {@code @Unique} field on that screen's glass mixin). The single entry point
 * {@link #run} sets the vanilla thumb-track geometry, folds the vanilla drag flag into press/drag/release, points the
 * thumb at the logical scroll ratio and paints. Screens that don't wire input still get the silky wheel glide because
 * {@link #draw} eases {@code pos} toward {@code target} on the config-menu time constant.
 *
 * <p><b>No copy-paste.</b> The motion and paint are the exact primitives the config slider uses:
 * <ul>
 *   <li>the non-drag glide is {@link GlassWidgets#approach} on the {@code easeScroll} time constant (τ = 90 ms);</li>
 *   <li>the thumb is {@link GlassWidgets#knobLens}: a white capsule that morphs into a refracting glass lens when held
 *       ({@link Motion#MORPH_IN_S}/{@link Motion#MORPH_OUT_S}), stretching with drag speed on the VERTICAL axis;</li>
 *   <li>dragging is 1:1 and sub-pixel with the same UIScrollView rubber-band ({@link Motion#rubberBand}).</li>
 * </ul>
 * Everything is float / sub-pixel, and the lens refracts the pre-GUI backdrop exactly like the config slider, so it
 * never samples itself.
 */
public final class GlassScrollbar {

    /** Groove half-width (px). The thumb capsule is a little wider than this. */
    private static final float TRACK_HALF_W = 1.5f;

    // ---- geometry (vanilla thumb-track coordinates), set every frame by the screen ----
    private float cx;         // track centre x
    private float trackTop;   // y where the thumb TOP sits at ratio 0
    private float travel;     // px the thumb TOP can move (trackHeight - thumbLen)
    private float thumbLen;   // thumb length along the track

    // ---- motion: the same primitives SliderWidget uses ----
    private final Motion.Clock clock = new Motion.Clock();
    private final Motion.Spring lift    = new Motion.Spring(Motion.MORPH_IN_S, 0f);
    private final Motion.Spring stretch = new Motion.Spring(Motion.STRETCH_S, 1f).tune(Motion.STRETCH_S, Motion.STRETCH_BOUNCE);
    private final float[] lens = new float[3];

    private float pos = Float.NaN;   // eased drawn ratio 0..1 (thumb-top)
    private float target;            // logical ratio 0..1 (vanilla scroll position)
    private boolean dragging, lifted;
    private float dragRaw;
    private double grabDY;
    private long pressNano, holdUntilNano;
    private float lastThumbPx = Float.NaN, speed;

    public void geometryTravel(float cx, float trackTop, float travelPx, float thumbLen) {
        this.cx = cx;
        this.trackTop = trackTop;
        this.thumbLen = thumbLen;
        this.travel = Math.max(1f, travelPx);
    }

    /**
     * The single entry point every screen's glass mixin calls from its scroller-sprite redirect: set the geometry,
     * fold the vanilla drag flag into press/drag/release, point the thumb at the logical scroll ratio and paint.
     */
    public static void run(GlassScrollbar bar, MatrixStack matrices, float cx, float trackTop, float travelPx,
                           float thumbLen, float ratio, boolean active, boolean dragging, double mouseY, float alpha) {
        bar.geometryTravel(cx, trackTop, travelPx, thumbLen);
        if (dragging && !bar.dragging) bar.press(mouseY);
        if (dragging) bar.drag(mouseY);
        else if (bar.dragging) bar.release();
        bar.setTarget(ratio);
        bar.draw(matrices, active, alpha);
    }

    public void setTarget(float t01) {
        this.target = Motion.clamp01(t01);
        if (Float.isNaN(pos)) pos = this.target;
    }

    public boolean dragging() { return dragging; }

    /** Finish any glide immediately: snap the drawn position onto the logical target (used on a mid-glide click). */
    public void snapToTarget() { pos = target; }

    /** True when the eased drawn position has not yet reached the logical target within {@code epsRatio}. */
    public boolean glidingBeyond(float epsRatio) {
        return !Float.isNaN(pos) && Math.abs(pos - target) > epsRatio;
    }

    /** The eased drawn scroll ratio 0..1 this frame (thumb-top) — feature (D) reads it to move content in step. */
    public float pos() { return Float.isNaN(pos) ? target : Motion.clamp01(pos); }

    private float thumbTopFor(float r01) { return trackTop + r01 * travel; }

    public void draw(MatrixStack matrices, boolean active, float alpha) {
        float dt = clock.tick();
        long now = System.nanoTime();

        if (dragging) {
            float clamped = Motion.clamp01(dragRaw);
            float overPx = (dragRaw - clamped) * travel;
            pos = clamped + Math.signum(overPx) * Motion.rubberBand(Math.abs(overPx), thumbLen) / travel;
        } else {
            if (Float.isNaN(pos)) pos = target;
            pos = GlassWidgets.approach(pos, target, dt * 1000f, 90f);
            if (Math.abs(target - pos) < 0.0006f) pos = target;
        }
        float cy = thumbTopFor(pos) + thumbLen / 2f;

        if (!dragging && lifted && now >= holdUntilNano) {
            lifted = false;
            lift.tune(Motion.MORPH_OUT_S, 0f).retarget(0f);
        }
        lift.update(dt);
        lift.settle(0.002f);
        float L = Motion.clamp01(lift.x);
        if (!Float.isNaN(lastThumbPx) && dt > 0f) {
            float inst = Math.abs(cy - lastThumbPx) / dt;
            speed += (inst - speed) * Motion.ema(dt, Motion.SPEED_TAU_S);
        }
        lastThumbPx = cy;
        stretch.retarget(Motion.stretchTarget(speed, thumbLen)).update(dt);
        Motion.lensShape(stretch.x, lens);   // [wideFactor, tallFactor, corner] for a horizontal slider

        int a = Math.round(alpha * 255f);
        float grooveA = active ? 0.30f : 0.16f;
        float gtop = trackTop, gbot = trackTop + travel + thumbLen;
        GlassWidgets.fillRound(matrices, cx - TRACK_HALF_W, gtop, cx + TRACK_HALF_W, gbot,
                ((int) (a * grooveA) << 24) | 0xFFFFFF, TRACK_HALF_W);

        // vertical knob: the config slider's knobLens with a degenerate (skipped) horizontal band and the stretch axes
        // swapped (width uses the ÷λ factor, height the ×λ factor) so a fast vertical drag makes the lens taller and
        // narrower — the mirror of the horizontal slider.
        float hw = Math.max(2.5f, thumbLen * 0.40f);
        float hh = thumbLen / 2f;
        GlassWidgets.knobLens(matrices, cx, cy, hw, hh, L, lens[1], lens[0], lens[2],
                cx, cx, TRACK_HALF_W, Float.NaN, 0xFF0A84FF, 0x4DFFFFFF, alpha * (active ? 1f : 0.7f));
    }

    // ---- input (optional; screens that skip these still get the wheel glide) ----

    public void press(double mouseY) {
        dragging = true;
        lifted = true;
        pressNano = System.nanoTime();
        lift.tune(Motion.MORPH_IN_S, 0f).retarget(1f);
        grabDY = thumbLen / 2f;
        dragRaw = (float) ((mouseY - grabDY - trackTop) / travel);
    }

    public float drag(double mouseY) {
        dragRaw = (float) ((mouseY - grabDY - trackTop) / travel);
        return Motion.clamp01(dragRaw);
    }

    public void release() {
        if (!dragging) return;
        dragging = false;
        long now = System.nanoTime();
        holdUntilNano = (now - pressNano) / 1.0e9f < Motion.TAP_S ? now + (long) (Motion.TAP_HOLD_S * 1.0e9f) : now;
    }
}
