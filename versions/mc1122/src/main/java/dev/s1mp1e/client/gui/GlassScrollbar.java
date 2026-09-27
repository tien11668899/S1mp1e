package dev.s1mp1e.client.gui;

/**
 * Shared VERTICAL liquid-glass scrollbar — the config screen's {@link SliderWidget} look and feel stood on its
 * side, so a vanilla container scrollbar (the creative item grid on 1.12.2) reads like the S1mp1e menu instead of
 * a flat sprite. Immediate-mode 1.12.2 (Forge fixed-function) port of the 26.2
 * {@code dev.s1mp1e.client.gui.GlassScrollbar}; the paint / motion are the exact primitives the config
 * {@code SliderWidget} uses, so nothing is reinvented:
 * <ul>
 *   <li>the non-drag glide is {@link GlassWidgets#approach} on the same exponential time constant as
 *       {@code S1mp1eConfigScreen.easeScroll} (&tau; = 90&nbsp;ms) — the menu-page feel;</li>
 *   <li>the thumb is {@link GlassWidgets#knobLens}: a white capsule that morphs into a refracting glass lens when
 *       held ({@link Motion#MORPH_IN_S}/{@link Motion#MORPH_OUT_S}), stretching with drag speed
 *       ({@link Motion#stretchTarget}/{@link Motion#lensShape}) — here rotated to the vertical axis;</li>
 *   <li>dragging is 1:1 and sub-pixel with the UIScrollView rubber-band ({@link Motion#rubberBand}).</li>
 * </ul>
 *
 * <p>Everything is float / sub-pixel so the glide is smooth, and it never samples itself (the lens refracts the
 * pre-GUI backdrop grabbed for the container panel this frame, exactly like the config slider — rule R4).
 *
 * <p>{@link #pos()} returns the eased drawn ratio; feature (D) reads it to slide the list content by the SAME
 * value, so thumb and content move as one. {@link #snapToTarget()} ends a glide immediately (a mid-glide click
 * calls it so the click acts on the row drawn under the cursor).
 */
public final class GlassScrollbar {

    /** Groove half-width (px); the thumb capsule is a little wider than this. */
    private static final float TRACK_HALF_W = 1.5f;

    /**
     * DEV/DevShot ONLY: force the thumb into the fully-lifted glass-lens state for a still capture, so the
     * refracting held-lens (which real interactive dragging morphs into over ~1/4 s) is visible in one frame.
     * Inert (false) in normal play; the same idea as {@code GlassCreativeTabs.devHover}. Never gates behaviour.
     */
    public static boolean DEV_FORCE_HELD = false;

    // ---- geometry (vanilla thumb-track coordinates), set every frame by the screen ----
    private float cx;         // track centre x
    private float trackTop;   // y where the thumb TOP sits at ratio 0
    private float travel;     // px the thumb TOP can move
    private float thumbLen;   // thumb length along the track

    // ---- motion: the same primitives SliderWidget uses ----
    private final Motion.Clock clock = new Motion.Clock();
    private final Motion.Spring lift    = new Motion.Spring(Motion.MORPH_IN_S, 0f);   // 0 = white pill, 1 = glass lens
    private final Motion.Spring stretch = new Motion.Spring(Motion.STRETCH_S, 1f).tune(Motion.STRETCH_S, Motion.STRETCH_BOUNCE);
    private final float[] lens = new float[3];

    private float pos = Float.NaN;   // eased drawn ratio 0..1 (thumb-top)
    private float target;            // logical ratio 0..1 (vanilla scroll position)
    private boolean dragging, lifted;
    private float dragRaw;           // raw pointer ratio while dragging (unclamped -> rubber-banded when drawn)
    private double grabDY;           // pointer y minus thumb-top at press (grab offset, no jump on the thumb)
    private long pressNano, holdUntilNano;
    private float lastThumbPx = Float.NaN, speed;

    /** As {@code geometry} but with the thumb-top travel distance given directly (vanilla's own thumb travel). */
    public void geometryTravel(float cx, float trackTop, float travelPx, float thumbLen) {
        this.cx = cx;
        this.trackTop = trackTop;
        this.thumbLen = thumbLen;
        this.travel = Math.max(1f, travelPx);
    }

    /**
     * The single entry point the scroller-sprite redirect calls: set the geometry, fold the vanilla drag flag into
     * press/drag/release (so a drag shows the 1:1 held lens and a release settles), point the thumb at the logical
     * scroll ratio and paint.
     */
    public static void run(GlassScrollbar bar, float cx, float trackTop, float travelPx, float thumbLen,
                           float ratio, boolean active, boolean dragging, double mouseY, float alpha) {
        bar.geometryTravel(cx, trackTop, travelPx, thumbLen);
        if (dragging && !bar.dragging) bar.press(mouseY);
        if (dragging) bar.drag(mouseY);
        else if (bar.dragging) bar.release();
        bar.setTarget(ratio);
        bar.draw(active, alpha);
    }

    /** Logical scroll position 0..1 (0 = top). The drawn thumb eases toward this when not dragging. */
    public void setTarget(float t01) {
        this.target = Motion.clamp01(t01);
        if (Float.isNaN(pos)) pos = this.target;
    }

    public boolean dragging() { return dragging; }

    /** Finish any glide immediately: snap the drawn position onto the logical target. */
    public void snapToTarget() { pos = target; }

    /** True when the eased drawn position has not yet reached the logical target within {@code epsRatio}. */
    public boolean glidingBeyond(float epsRatio) {
        return !Float.isNaN(pos) && Math.abs(pos - target) > epsRatio;
    }

    /** The eased drawn scroll ratio 0..1 this frame (thumb-top position); feature (D) slides content by this. */
    public float pos() { return Float.isNaN(pos) ? target : Motion.clamp01(pos); }

    private float thumbTopFor(float r01) { return trackTop + r01 * travel; }

    public void draw(boolean active, float alpha) {
        float dt = clock.tick();
        long now = System.nanoTime();

        // ---- where the thumb sits ----
        if (dragging) {
            float clamped = Motion.clamp01(dragRaw);
            float overPx = (dragRaw - clamped) * travel;
            pos = clamped + Math.signum(overPx) * Motion.rubberBand(Math.abs(overPx), thumbLen) / travel;   // 1:1 + rubber
        } else {
            if (Float.isNaN(pos)) pos = target;
            pos = GlassWidgets.approach(pos, target, dt * 1000f, 90f);   // menu-page glide (tau = 90 ms)
            if (Math.abs(target - pos) < 0.0006f) pos = target;
        }
        float cy = thumbTopFor(pos) + thumbLen / 2f;

        // ---- lens: morph (held) + speed stretch (vertical axis) ----
        if (!dragging && lifted && now >= holdUntilNano) {
            lifted = false;
            lift.tune(Motion.MORPH_OUT_S, 0f).retarget(0f);
        }
        lift.update(dt);
        lift.settle(0.002f);
        float L = Motion.clamp01(lift.x);
        if (DEV_FORCE_HELD) L = 1f;   // DevShot still: show the real refracting lens (see field doc)
        if (!Float.isNaN(lastThumbPx) && dt > 0f) {
            float inst = Math.abs(cy - lastThumbPx) / dt;
            speed += (inst - speed) * Motion.ema(dt, Motion.SPEED_TAU_S);
        }
        lastThumbPx = cy;
        stretch.retarget(Motion.stretchTarget(speed, thumbLen)).update(dt);
        Motion.lensShape(stretch.x, lens);   // [wideFactor, tallFactor, corner] for a HORIZONTAL slider

        // ---- paint ----
        int a = Math.round(alpha * 255f);
        float grooveA = active ? 0.30f : 0.16f;
        float gtop = trackTop, gbot = trackTop + travel + thumbLen;   // full groove length
        GlassWidgets.fillRound(cx - TRACK_HALF_W, gtop, cx + TRACK_HALF_W, gbot,
                ((int) (a * grooveA) << 24) | 0xFFFFFF, TRACK_HALF_W);

        // vertical knob: reuse the config slider's knobLens with a degenerate (skipped) horizontal band, and the
        // stretch axes swapped (width uses the div-lambda factor, height the mul-lambda factor) so a fast vertical
        // drag makes the lens taller and narrower — the mirror of the horizontal slider.
        float hw = Math.max(2.5f, thumbLen * 0.40f);
        float hh = thumbLen / 2f;
        GlassWidgets.knobLens(cx, cy, hw, hh, L, lens[1], lens[0], lens[2],
                cx, cx, TRACK_HALF_W, Float.NaN, 0xFF0A84FF, 0x4DFFFFFF, alpha * (active ? 1f : 0.7f));
    }

    // ---- input (optional; a caller that skips these still gets the wheel glide) ----

    /** Press at pointer y. The thumb recenters on the pointer so it tracks vanilla's own (clamped) scroll during
     *  the drag with no jump on release. */
    public void press(double mouseY) {
        dragging = true;
        lifted = true;
        pressNano = System.nanoTime();
        lift.tune(Motion.MORPH_IN_S, 0f).retarget(1f);
        grabDY = thumbLen / 2f;
        dragRaw = (float) ((mouseY - grabDY - trackTop) / travel);
    }

    /** Drag to pointer y; returns the CLAMPED logical ratio 0..1. */
    public float drag(double mouseY) {
        dragRaw = (float) ((mouseY - grabDY - trackTop) / travel);
        return Motion.clamp01(dragRaw);
    }

    public void release() {
        if (!dragging) return;
        dragging = false;
        long now = System.nanoTime();
        // a quick tap leaves the lens up briefly (like the slider), otherwise it collapses right away
        holdUntilNano = (now - pressNano) / 1.0e9f < Motion.TAP_S ? now + (long) (Motion.TAP_HOLD_S * 1.0e9f) : now;
    }
}
