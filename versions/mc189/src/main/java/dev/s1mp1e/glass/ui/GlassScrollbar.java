package dev.s1mp1e.glass.ui;

import dev.s1mp1e.glass.render.GlassRenderer;

/**
 * The 1.8.9 counterpart of LiquidGlass26's {@code gui/GlassScrollbar} (PORT_SPEC
 * feature C): a vanilla container scrollbar restyled as the config-menu
 * {@code SliderWidget} stood vertical — a faint groove + a 15 px white capsule
 * thumb that morphs into a refracting glass LENS while held, with a settle glide,
 * a speed-driven lens stretch, and a rubber-band past the ends.
 *
 * <p>One instance per screen. The vanilla screen keeps ownership of the LOGICAL
 * scroll (its {@code currentScroll} float, updated 1:1 from the mouse while
 * dragging, and clamped) so clicks / hit-tests / the item grid stay row-correct;
 * this class only renders the thumb and eases the DRAWN position toward that
 * logical ratio (glide time constant tau = 90 ms — feature D's "silky" thumb).
 *
 * <p>Everything is drawn through {@link GlassRenderer}: the groove and the white
 * thumb through {@code roundRect} (flat AA SDF, never samples the backdrop, never
 * flickers), the held lens through {@code lens} (refracts the frame-primary
 * backdrop already grabbed by the container pass — R4). If the lens pipeline is
 * unavailable the thumb stays the white capsule, so the scrollbar never vanishes.
 */
public final class GlassScrollbar {

    // --- Apple/iOS constants (shared with the config slider) ---
    private static final float TRACK_HALF_W = 1.5f;
    private static final float GLIDE_TAU_MS = 90f;
    /** iOS systemBlue accent + white, as the config slider lens. */
    private static final int   ACCENT = 0xFF0A84FF;

    // 按住時的透鏡：和設定頁滑桿／26.2 GlassScrollbar 同一套 Motion 彈簧（0 = 白色藥丸，1 = 玻璃透鏡）
    private final dev.s1mp1e.client.gui.Motion.Spring lift =
            new dev.s1mp1e.client.gui.Motion.Spring(dev.s1mp1e.client.gui.Motion.MORPH_IN_S, 0f);
    private final dev.s1mp1e.client.gui.Motion.Spring stretch =
            new dev.s1mp1e.client.gui.Motion.Spring(dev.s1mp1e.client.gui.Motion.STRETCH_S, 1f)
                    .tune(dev.s1mp1e.client.gui.Motion.STRETCH_S, dev.s1mp1e.client.gui.Motion.STRETCH_BOUNCE);
    private final float[] lens = new float[3];
    private boolean lifted;
    private float  speed;                  // 指標速度（px/s，EMA），驅動透鏡拉長
    private float  lastThumbPx = Float.NaN;
    private float  drawnPos = Float.NaN;   // eased ratio 0..1
    private long   lastNanos = 0L;
    private float  lastTarget = 0f;        // the logical ratio last fed to run() (for snapToTarget)

    /** Reset to a fresh screen (snap, no glide from a stale spot). */
    public void reset() {
        drawnPos = Float.NaN; lastNanos = 0L;
        lastTarget = 0f;
        lift.snap(0f); lifted = false;
        stretch.snap(1f); speed = 0f; lastThumbPx = Float.NaN;
    }

    /** The eased drawn ratio (feature D moves the grid content by the SAME value). */
    public float pos() { return Float.isNaN(drawnPos) ? 0f : drawnPos; }

    /**
     * Finish any glide immediately: snap the drawn ratio onto the logical target
     * (26.2 {@code GlassScrollbar.snapToTarget}). Called from the creative screen's
     * {@code mouseClicked} while the grid is mid-glide, so the frame the click acts
     * on shows the target row — the row vanilla will hit-test — and the content
     * drawn under the cursor is exactly the item that gets picked up.
     */
    public void snapToTarget() { drawnPos = lastTarget; }

    /**
     * Draw the scrollbar.
     *
     * @param cx        thumb-column centre X (GUI px)
     * @param trackTop  Y of the top of the thumb's travel (GUI px)
     * @param travelPx  thumb-top travel length (trackHeight - thumbLen)
     * @param thumbLen  thumb length (px, vanilla 15)
     * @param ratio     logical scroll ratio 0..1 (the screen's currentScroll)
     * @param active    true when there is scrollable content
     * @param dragging  true while the vanilla drag flag is set
     * @param mouseY    cursor Y (for the rubber-band past the ends)
     * @param alpha     whole-scrollbar opacity (panel open fade)
     */
    public void run(float cx, float trackTop, float travelPx, float thumbLen,
                    float ratio, boolean active, boolean dragging, int mouseY, float alpha) {
        long now = System.nanoTime();
        float dt = (lastNanos == 0L) ? (1f / 60f) : Math.min(0.1f, (now - lastNanos) * 1e-9f);
        lastNanos = now;
        float dtMs = dt * 1000f;

        float target = clamp01(ratio);
        lastTarget = target;

        // Rubber-band the DRAWN thumb past the ends while dragging beyond the track.
        float over = 0f;
        if (dragging && travelPx > 0f) {
            float thumbTopAtTarget = trackTop + target * travelPx;
            float grab = thumbLen * 0.5f;
            float wanted = mouseY - grab;              // where the mouse wants the thumb top
            if (wanted < trackTop && target <= 0.001f) over = (trackTop - wanted);
            else if (wanted > trackTop + travelPx && target >= 0.999f) over = -(wanted - (trackTop + travelPx));
        }
        float rubberPx = rubberBand(Math.abs(over), thumbLen);
        if (over < 0f) rubberPx = -rubberPx;

        // Position: 1:1 while dragging, eased glide otherwise (tau 90 ms).
        if (Float.isNaN(drawnPos)) {
            drawnPos = target;
        } else if (dragging) {
            drawnPos = target;
        } else {
            drawnPos += (target - drawnPos) * (1f - (float) Math.exp(-dtMs / GLIDE_TAU_MS));
        }

        float thumbY = trackTop + drawnPos * travelPx + rubberPx;
        float cyT = thumbY + thumbLen * 0.5f;

        // ---- 按住的透鏡（和設定頁滑桿、26.2 GlassScrollbar 一樣）----
        // 2026-10-04 使用者回報：背包滑桿按住拖曳時「沒變大、而且是藍色」，設定頁的滑桿卻正常。原本這裡是硬切成
        // 原尺寸的透鏡、再疊一層 18% 的 iOS 藍；設定頁則是白色藥丸隨按住程度漸變成放大的透鏡、沒有藍色。
        // 現在照 26.2：knobLens 的形變（按住 0.13 s 變透鏡、放開 0.28 s 變回）、透鏡放大＋隨拖曳速度拉長，
        // 直式所以寬高係數對調（快速往下拖會變高變窄）；水平色帶是退化的（cx..cx），所以不會出現任何藍色。
        if (dragging && !lifted) {
            lifted = true;
            lift.tune(dev.s1mp1e.client.gui.Motion.MORPH_IN_S, 0f).retarget(1f);
        } else if (!dragging && lifted) {
            lifted = false;
            lift.tune(dev.s1mp1e.client.gui.Motion.MORPH_OUT_S, 0f).retarget(0f);
        }
        lift.update(dt);
        float L = clamp01(lift.x);
        if (!Float.isNaN(lastThumbPx) && dt > 0f) {
            float inst = Math.abs(cyT - lastThumbPx) / dt;
            speed += (inst - speed) * dev.s1mp1e.client.gui.Motion.ema(dt, dev.s1mp1e.client.gui.Motion.SPEED_TAU_S);
        }
        lastThumbPx = cyT;
        stretch.retarget(dev.s1mp1e.client.gui.Motion.stretchTarget(speed, thumbLen)).update(dt);
        dev.s1mp1e.client.gui.Motion.lensShape(stretch.x, lens);   // [寬係數, 高係數, 圓角]（水平滑桿的定義）

        // ---- groove: faint white capsule the full track length ----
        float grooveA = (active ? 0.30f : 0.16f) * alpha;
        int grooveArgb = (Math.round(grooveA * 255f) << 24) | 0xFFFFFF;
        GlassRenderer.roundRect(cx - TRACK_HALF_W, trackTop, cx + TRACK_HALF_W,
                                trackTop + travelPx + thumbLen, TRACK_HALF_W, grooveArgb);

        // ---- thumb：白色藥丸 → 按住時漸變成放大的折射透鏡 ----
        float hw = Math.max(2.5f, thumbLen * 0.40f);
        float hh = thumbLen * 0.5f;
        dev.s1mp1e.client.gui.GlassWidgets.knobLens(cx, cyT, hw, hh, L, lens[1], lens[0], lens[2],
                cx, cx, TRACK_HALF_W, Float.NaN, 0xFF000000 | (ACCENT & 0xFFFFFF), 0x4DFFFFFF, alpha);
        dev.s1mp1e.client.gui.GlassWidgets.resetColorCache();
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** {@code rubberBand(over, dim) = (1 - 1/(over*0.55/dim + 1))*dim}. */
    private static float rubberBand(float over, float dim) {
        if (over <= 0f || dim <= 0f) return 0f;
        return (1f - 1f / (over * 0.55f / dim + 1f)) * dim;
    }
}
