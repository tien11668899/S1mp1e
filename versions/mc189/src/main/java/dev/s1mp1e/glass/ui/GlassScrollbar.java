package dev.s1mp1e.glass.ui;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;

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
    private static final float MORPH_IN_S  = 0.13f;
    private static final float MORPH_OUT_S = 0.28f;
    private static final float TRACK_HALF_W = 1.5f;
    private static final float GLIDE_TAU_MS = 90f;
    /** iOS systemBlue accent + white, as the config slider lens. */
    private static final int   ACCENT = 0xFF0A84FF;
    private static final int   WHITE  = 0xE6FFFFFF;

    private final Fade  lift = new Fade(0f, MORPH_IN_S * 1000f); // 0 = capsule, 1 = lens
    private float  drawnPos = Float.NaN;   // eased ratio 0..1
    private float  velPos   = 0f;          // ratio units / s, for the stretch
    private long   lastNanos = 0L;
    private float  lensLambda = 1f;        // filtered vertical stretch factor
    private float  lastTarget = 0f;        // the logical ratio last fed to run() (for snapToTarget)

    /** Reset to a fresh screen (snap, no glide from a stale spot). */
    public void reset() {
        drawnPos = Float.NaN; velPos = 0f; lastNanos = 0L; lensLambda = 1f;
        lastTarget = 0f;
        lift.snap(0f);
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
    public void snapToTarget() { drawnPos = lastTarget; velPos = 0f; }

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
        float prev = Float.isNaN(drawnPos) ? target : drawnPos;
        if (Float.isNaN(drawnPos)) {
            drawnPos = target;
        } else if (dragging) {
            drawnPos = target;
        } else {
            drawnPos += (target - drawnPos) * (1f - (float) Math.exp(-dtMs / GLIDE_TAU_MS));
        }
        velPos = dt > 0f ? (drawnPos - prev) / dt : 0f;

        // Held lens morph.
        lift.to(dragging ? 1f : 0f, (dragging ? MORPH_IN_S : MORPH_OUT_S) * 1000f);
        float held = lift.value();

        // Speed-driven vertical stretch of the lens (area preserving: H x lambda, W / lambda).
        float speedFrac = Math.min(1f, Math.abs(velPos) * 6.3f);
        float stretchTarget = 1f + 0.18f * speedFrac;
        lensLambda += (stretchTarget - lensLambda) * (1f - (float) Math.exp(-dtMs / 280f));

        float thumbY = trackTop + drawnPos * travelPx + rubberPx;

        // ---- groove: faint white capsule the full track length ----
        float grooveA = (active ? 0.30f : 0.16f) * alpha;
        int grooveArgb = (Math.round(grooveA * 255f) << 24) | 0xFFFFFF;
        GlassRenderer.roundRect(cx - TRACK_HALF_W, trackTop, cx + TRACK_HALF_W,
                                trackTop + travelPx + thumbLen, TRACK_HALF_W, grooveArgb);

        // ---- thumb: white capsule, morphing to a refracting lens when held ----
        float baseHW = Math.max(2.5f, thumbLen * 0.40f);
        float lam = held > 0.02f ? lensLambda : 1f;
        float hh = (thumbLen * 0.5f) * lam;
        float hw = baseHW / (float) Math.sqrt(Math.max(0.5f, lam));
        float cyT = thumbY + thumbLen * 0.5f;

        boolean lensOk = held > 0.02f && GlassProgram.ensureReady() && GlassProgram.lensUsable()
                && SceneCapture.hasBackdrop();
        if (lensOk) {
            // refracting lens (full-capsule corner), slight neutral lift, accent rim via
            // an overlaid translucent accent capsule.
            GlassRenderer.lens(cx - hw, cyT - hh, cx + hw, cyT + hh, 1.0f, 0.12f, alpha, GlassRenderer.FROST_NONE);
            int accentA = Math.round(0.18f * held * alpha * 255f) & 0xFF;
            if (accentA > 1) {
                GlassRenderer.roundRect(cx - hw, cyT - hh, cx + hw, cyT + hh,
                                        Math.min(hw, hh), (accentA << 24) | (ACCENT & 0xFFFFFF));
            }
        } else {
            int wa = Math.round(((WHITE >>> 24) / 255f) * alpha * 255f) & 0xFF;
            GlassRenderer.roundRect(cx - hw, cyT - hh, cx + hw, cyT + hh,
                                    Math.min(hw, hh), (wa << 24) | (WHITE & 0xFFFFFF));
        }
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** {@code rubberBand(over, dim) = (1 - 1/(over*0.55/dim + 1))*dim}. */
    private static float rubberBand(float over, float dim) {
        if (over <= 0f || dim <= 0f) return 0f;
        return (1f - 1f / (over * 0.55f / dim + 1f)) * dim;
    }
}
