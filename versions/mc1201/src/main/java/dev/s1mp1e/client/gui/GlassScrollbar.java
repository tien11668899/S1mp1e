package dev.s1mp1e.client.gui;

import net.minecraft.client.gui.DrawContext;

/**
 * Shared VERTICAL liquid-glass scrollbar — the config screen's {@code SliderWidget} look and feel stood on its side, so
 * every vanilla container scrollbar (creative item grid, stonecutter / loom recipe lists, villager trades) reads like the
 * S1mp1e menu instead of a flat sprite. The 1.20.1 (DrawContext, immediate-mode) port of 26.2's
 * {@code dev.s1mp1e.client.gui.GlassScrollbar} — the motion is byte-for-byte the same; only the draw sink changes from the
 * retained {@code GuiGraphicsExtractor} to {@link DrawContext}.
 *
 * <p>One instance lives per screen (a {@code @Unique} field on that screen's glass mixin). Every frame the screen calls
 * {@link #run} with the vanilla thumb-track geometry and the logical scroll ratio; that folds the vanilla drag flag into
 * press/drag/release, eases the thumb toward the logical ratio (the config-menu τ = 90 ms glide) and paints:
 * <ul>
 *   <li>a faint groove + a 15 px white capsule thumb that morphs into a refracting glass lens when held
 *       ({@link Motion#MORPH_IN_S}/{@link Motion#MORPH_OUT_S}), stretching with drag speed on the vertical axis;</li>
 *   <li>1:1 sub-pixel drag with the UIScrollView rubber-band ({@link Motion#rubberBand}) past the ends.</li>
 * </ul>
 * It never samples itself (the lens refracts the pre-GUI backdrop like the config slider). {@link #pos()} is the eased
 * drawn ratio feature D reads to move the content by the SAME value; {@link #snapToTarget()} ends a glide immediately for
 * a mid-glide click.
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
    private float target;            // logical ratio 0..1 (vanilla scroll position)
    private boolean dragging, lifted;
    private float dragRaw;           // raw pointer ratio while dragging (unclamped -> rubber-banded when drawn)
    private double grabDY;           // pointer y minus thumb-top at press (grab offset, no jump on the thumb)
    private long pressNano, holdUntilNano;
    private float lastThumbPx = Float.NaN, speed;

    /** As {@link #geometry} but with the thumb-top travel distance given directly. */
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
    public static void run(GlassScrollbar bar, DrawContext ctx, float cx, float trackTop, float travelPx,
                           float thumbLen, float ratio, boolean active, boolean dragging, double mouseY, float alpha) {
        bar.geometryTravel(cx, trackTop, travelPx, thumbLen);
        if (dragging && !bar.dragging) bar.press(mouseY);
        if (dragging) bar.drag(mouseY);
        else if (bar.dragging) bar.release();
        bar.setTarget(ratio);
        bar.draw(ctx, active, alpha);
    }

    /** Logical scroll position 0..1 (0 = top). The drawn thumb eases toward this when not dragging. */
    public void setTarget(float t01) {
        this.target = Motion.clamp01(t01);
        if (Float.isNaN(pos)) pos = this.target;
    }

    public boolean dragging() { return dragging; }

    /** Finish any glide immediately: snap the drawn position onto the logical target (mid-glide click correctness). */
    public void snapToTarget() { pos = target; }

    /** True when the eased drawn position has not yet reached the logical target within {@code epsRatio}. */
    public boolean glidingBeyond(float epsRatio) {
        return !Float.isNaN(pos) && Math.abs(pos - target) > epsRatio;
    }

    /** The eased drawn scroll ratio 0..1 this frame (thumb-top). Content reads this to move as one with the thumb. */
    public float pos() { return Float.isNaN(pos) ? target : Motion.clamp01(pos); }

    private float thumbTopFor(float r01) { return trackTop + r01 * travel; }

    public void draw(DrawContext ctx, boolean active, float alpha) {
        float dt = clock.tick();
        long now = System.nanoTime();

        // ---- where the thumb sits ----
        if (dragging) {
            float clamped = Motion.clamp01(dragRaw);
            float overPx = (dragRaw - clamped) * travel;
            pos = clamped + Math.signum(overPx) * Motion.rubberBand(Math.abs(overPx), thumbLen) / travel;   // 1:1 + rubber
        } else {
            if (Float.isNaN(pos)) pos = target;
            pos = GlassWidgets.approach(pos, target, dt * 1000f, 90f);   // menu-page glide (τ = 90 ms)
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
        GlassWidgets.fillRound(ctx, cx - TRACK_HALF_W, gtop, cx + TRACK_HALF_W, gbot,
                ((int) (a * grooveA) << 24) | 0xFFFFFF, TRACK_HALF_W);

        // vertical knob: the config slider's knobLens with a degenerate (skipped) horizontal band, stretch axes swapped
        // (width uses the /lambda factor, height the *lambda factor) so a fast vertical drag makes the lens taller/narrower.
        float hw = Math.max(2.5f, thumbLen * 0.40f);
        float hh = thumbLen / 2f;
        GlassWidgets.knobLens(ctx, cx, cy, hw, hh, L, lens[1], lens[0], lens[2],
                cx, cx, TRACK_HALF_W, Float.NaN, 0xFF0A84FF, 0x4DFFFFFF, alpha * (active ? 1f : 0.7f));
    }

    // ---- input (optional; screens that skip these still get the wheel glide) ----

    /** Press at pointer y. The thumb recenters on the pointer (vanilla's grab) so the glass thumb tracks the clamped
     *  scroll value and there is no jump on release. */
    public void press(double mouseY) {
        dragging = true;
        lifted = true;
        pressNano = System.nanoTime();
        lift.tune(Motion.MORPH_IN_S, 0f).retarget(1f);
        grabDY = thumbLen / 2f;
        dragRaw = (float) ((mouseY - grabDY - trackTop) / travel);
    }

    /** Drag to pointer y; returns the CLAMPED logical ratio 0..1 for the screen to apply to its scroll field. */
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
