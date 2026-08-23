package dev.s1mp1e.glass.render;

import dev.s1mp1e.glass.anim.Fade;

import java.util.ArrayList;
import java.util.List;

/**
 * Fade-out ghost of a just-closed glass container panel — the 1.8.9 port of
 * LiquidGlass26's PanelGhost.
 *
 * <p>A container screen stops rendering the instant it closes, so its glass
 * panel would pop out of existence. Instead each frame the panel draw calls
 * {@link #beginFrame()} then {@link #remember(int, int, int, int)} to record its
 * rect; when the screen closes {@link #trigger()} snapshots those rects and
 * stamps the time, and the still-running HUD overlay pass keeps painting them
 * for the last {@link #FADE_MS} ms via {@link #drawGhosts()} — the one moment
 * the glass panel is actually visible unobstructed by the vanilla GUI texture.
 *
 * <p>All state is static because there is only ever one open container screen
 * and the HUD path (a different handler) must read it after the screen is gone.
 */
public final class PanelGhost {

    /** 26.2's fade-out duration, identical to the 150 ms open fade. */
    public static final float FADE_MS = 150f;

    // Multiple rects per screen (container panel + any future recipe-book /
    // tab-bar panels): the pending list is rebuilt every frame, snapshotted
    // into active on close.
    private static final ArrayList<int[]> pending = new ArrayList<int[]>();
    private static final ArrayList<int[]> active  = new ArrayList<int[]>();
    /** Plain linear fade — no easing, no mid-flight interruption. */
    private static final Fade fade = new Fade(0f, FADE_MS);

    private PanelGhost() {}

    /** The first glass draw of the screen each frame resets the rect list. */
    public static void beginFrame() {
        pending.clear();
    }

    /** Every glass panel draw records its rect (x, y, w, h) each frame. */
    public static void remember(int px, int py, int pw, int ph) {
        pending.add(new int[] { px, py, pw, ph });
    }

    /** Called when the screen closes: start fading ALL remembered rects. */
    public static void trigger() {
        if (pending.isEmpty()) return;
        active.clear();
        active.addAll(pending);
        pending.clear();
        fade.snap(1f);
        fade.to(0f);
    }

    /** Reopened before the ghost finished — drop it outright. */
    public static void cancel() {
        fade.snap(0f);
        active.clear();
    }

    /** The rects currently fading out. */
    public static List<int[]> rects() {
        return active;
    }

    /** 1..0 fade remaining, or 0 (and cleared) when finished. */
    public static float alpha() {
        float a = fade.value();
        if (a <= 0.004f && !active.isEmpty() && fade.isIdle()) active.clear();
        return a;
    }

    /**
     * Draw the fading panels — call once from the HUD overlay pass (a fresh
     * backdrop has already been grabbed there). No-op while a screen is open or
     * once the fade has finished. {@link GlassRenderer#panel} internally gates
     * on the pipeline being usable and the backdrop being present.
     */
    public static void drawGhosts() {
        float a = alpha();
        if (a <= 0f) return;
        for (int i = 0; i < active.size(); i++) {
            int[] r = active.get(i);
            GlassRenderer.panel(r[0], r[1], r[0] + r[2], r[1] + r[3], a);
        }
    }
}
