package dev.s1mp1e.client.gui;

import net.minecraft.client.util.math.MatrixStack;

/**
 * Feature C — shared VERTICAL liquid-glass scrollbar: the config screen's {@code SliderWidget} look and feel stood on
 * its side, so every vanilla container scrollbar (creative item grid, stonecutter recipe list, loom pattern grid,
 * villager trades) reads like the S1mp1e menu instead of a flat sprite. 1.16.5 ({@link MatrixStack}, legacy
 * fixed-function GL) port of 26.2's {@code GlassScrollbar}, structured like the verified 1.17.1 sibling (motion
 * {@link #step} split from {@link #paint} so the merchant can know the eased value before its trade buttons draw).
 *
 * <p>One instance lives per screen (an {@code @Unique} field on that screen's glass mixin). {@link #run} is the single
 * entry point a scroller-sprite redirect calls: it sets the vanilla thumb-track geometry, folds the vanilla drag flag
 * into press/drag/release (1:1 grab-at-centre drag with a rubber band past the ends and a held glass lens), points the
 * thumb at the logical (row-aligned) scroll ratio and paints.
 *
 * <p><b>No copy-paste of new motion.</b> Everything is the config slider's own primitives:
 * <ul>
 *   <li>the non-drag glide is {@link GlassWidgets#approach} on the config menu's {@code easeScroll} time constant
 *       (tau = 90 ms);</li>
 *   <li>the thumb is {@link GlassWidgets#knobLens}: a 15 px white capsule that morphs into a refracting glass lens
 *       when held ({@link Motion#MORPH_IN_S} / {@link Motion#MORPH_OUT_S}), stretching with drag speed on the VERTICAL
 *       axis (width / lambda, height * lambda — the mirror of the horizontal slider);</li>
 *   <li>dragging is 1:1 and sub-pixel with the same UIScrollView {@link Motion#rubberBand}; a short tap keeps the lens
 *       up for {@link Motion#TAP_HOLD_S}.</li>
 * </ul>
 * The lens refracts the frame's pre-GUI backdrop (the panel's own fresh grab), exactly like the config slider, so it
 * never samples itself (R4). {@link #pos} feeds feature D so the list content and the glass thumb move as one.
 */
public final class GlassScrollbar {

    /** Groove half-width (px). The thumb capsule is a little wider than this. */
    private static final float TRACK_HALF_W = 1.5f;

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
    private float target;            // logical ratio 0..1 (vanilla scroll position, row-aligned)
    private boolean dragging, lifted;
    private float dragRaw;           // raw pointer ratio while dragging (unclamped -> rubber-banded when drawn)
    private double grabDY;           // pointer y minus thumb-top at press
    private long pressNano, holdUntilNano;
    private float lastThumbPx = Float.NaN, speed;

    /** Vanilla thumb-track geometry with the thumb-top travel distance given directly. */
    public void geometryTravel(float cx, float trackTop, float travelPx, float thumbLen) {
        this.cx = cx;
        this.trackTop = trackTop;
        this.thumbLen = thumbLen;
        this.travel = Math.max(1f, travelPx);
    }

    /**
     * The single entry point every screen's glass mixin calls from its scroller-sprite redirect: set the geometry, fold
     * the vanilla drag flag into press/drag/release, point the thumb at the logical scroll ratio and paint.
     */
    public static void run(GlassScrollbar bar, MatrixStack matrices, float cx, float trackTop, float travelPx,
                           float thumbLen, float ratio, boolean active, boolean dragging, double mouseY, float alpha) {
        step(bar, cx, trackTop, travelPx, thumbLen, ratio, dragging, mouseY);
        bar.paint(matrices, active, alpha);
    }

    /**
     * The motion half of {@link #run} without painting: geometry, drag fold-in, target and one motion step. For a screen
     * that must know the eased value ({@link #pos}) BEFORE the frame's content is drawn but paints the thumb later in the
     * frame (the merchant: its trade buttons draw before its scroller) — call this early, then {@link #paint}.
     */
    public static void step(GlassScrollbar bar, float cx, float trackTop, float travelPx, float thumbLen,
                            float ratio, boolean dragging, double mouseY) {
        bar.geometryTravel(cx, trackTop, travelPx, thumbLen);
        if (dragging && !bar.dragging) bar.press(mouseY);
        if (dragging) bar.drag(mouseY);
        else if (bar.dragging) bar.release();
        bar.setTarget(ratio);
        bar.update();
    }

    /** Logical scroll position 0..1 (0 = top). The drawn thumb eases toward this when not dragging. */
    public void setTarget(float t01) {
        this.target = Motion.clamp01(t01);
        if (Float.isNaN(pos)) pos = this.target;
    }

    public boolean dragging() { return dragging; }

    /**
     * Finish any glide immediately: snap the drawn position onto the logical target. Called from a screen's
     * {@code mouseClicked} when the content is mid-glide, so the click acts on the row vanilla will hit-test, which is
     * the row drawn from the very next frame on ("snap to the target row, then let vanilla handle the click").
     */
    public void snapToTarget() { pos = target; }

    /** True when the eased drawn position has not yet reached the logical target within {@code epsRatio}. */
    public boolean glidingBeyond(float epsRatio) {
        return !Float.isNaN(pos) && Math.abs(pos - target) > epsRatio;
    }

    /** The eased drawn scroll ratio 0..1 this frame (thumb-top) — feature D slides the content by the SAME value. */
    public float pos() { return Float.isNaN(pos) ? target : Motion.clamp01(pos); }

    private float thumbTopFor(float r01) { return trackTop + r01 * travel; }

    public void draw(MatrixStack matrices, boolean active, float alpha) {
        update();
        paint(matrices, active, alpha);
    }

    /** Advance the thumb motion one frame (position glide / drag + rubber, lens morph, speed stretch). */
    public void update() {
        float dt = clock.tick();
        long now = System.nanoTime();

        // ---- where the thumb sits ----
        if (dragging) {
            float clamped = Motion.clamp01(dragRaw);
            float overPx = (dragRaw - clamped) * travel;
            pos = clamped + Math.signum(overPx) * Motion.rubberBand(Math.abs(overPx), thumbLen) / travel;   // 1:1 + rubber
        } else {
            if (Float.isNaN(pos)) pos = target;
            pos = GlassWidgets.approach(pos, target, dt * 1000f, 90f);   // config-menu glide (tau = 90 ms)
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
        if (!Float.isNaN(lastThumbPx) && dt > 0f) {
            float inst = Math.abs(cy - lastThumbPx) / dt;
            speed += (inst - speed) * Motion.ema(dt, Motion.SPEED_TAU_S);
        }
        lastThumbPx = cy;
        stretch.retarget(Motion.stretchTarget(speed, thumbLen)).update(dt);
        Motion.lensShape(stretch.x, lens);   // [wideFactor, tallFactor, corner] for a HORIZONTAL slider
    }

    /** Current lens morph 0 (white pill) .. 1 (glass lens) — dev probe only. */
    public float liftValue() { return Motion.clamp01(lift.x); }

    /** Paint the groove + thumb at the current (already updated) motion state. */
    public void paint(MatrixStack matrices, boolean active, float alpha) {
        float cy = thumbTopFor(Float.isNaN(pos) ? target : pos) + thumbLen / 2f;
        float L = Motion.clamp01(lift.x);
        int a = Math.round(alpha * 255f);
        float grooveA = active ? 0.30f : 0.16f;
        float gtop = trackTop, gbot = trackTop + travel + thumbLen;   // full groove length
        GlassWidgets.fillRound(matrices, cx - TRACK_HALF_W, gtop, cx + TRACK_HALF_W, gbot,
                ((int) (a * grooveA) << 24) | 0xFFFFFF, TRACK_HALF_W);

        // vertical knob: the config slider's knobLens with a degenerate (skipped) horizontal band and the stretch axes
        // swapped (width uses the / lambda factor, height the * lambda factor) so a fast vertical drag makes the lens
        // taller and narrower — the mirror of the horizontal slider.
        float hw = Math.max(2.5f, thumbLen * 0.40f);
        float hh = thumbLen / 2f;
        GlassWidgets.knobLens(matrices, cx, cy, hw, hh, L, lens[1], lens[0], lens[2],
                cx, cx, TRACK_HALF_W, Float.NaN, 0xFF0A84FF, 0x4DFFFFFF, alpha * (active ? 1f : 0.7f));
    }

    // ---- input (screens that skip these still get the wheel glide) ----

    /** Press at pointer y. The thumb recentres on the pointer so the glass thumb tracks vanilla's clamped scroll value
     *  during the drag and there is no jump on release. */
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
        holdUntilNano = (now - pressNano) / 1.0e9f < Motion.TAP_S ? now + (long) (Motion.TAP_HOLD_S * 1.0e9f) : now;
    }
}
