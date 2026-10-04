package dev.s1mp1e.client.gui;

import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;

/**
 * The one liquid-glass "status card" shared by every text loading screen (connect / downloading terrain / progress) —
 * group 10, the 1.12.2 port of mc1144 / 26.2 {@code LoadingCard}. Callers pass the TIGHT box of the screen's own text;
 * this pads it uniformly ({@link #PAD_X} / {@link #PAD_Y}) and draws it with the hotbar corner radius (rule R2) — a
 * refracting {@link GlassRenderer#glass} body plus a 0.28 dark readability scrim — so every loading card has identical
 * spacing and corners. Immediate GL: the screen's text, drawn after, lands on top.
 */
public final class LoadingCard {
    private LoadingCard() {}

    public static final float PAD_X = 26.0F;
    public static final float PAD_Y = 16.0F;
    public static final float TEXT_H = 9.0F;
    public static final float GAP = 8.0F;
    private static final float SCRIM = 0.28F;
    private static final int SCRIM_RGB = 0x1C1C1E;
    private static final float MIN_HALF_W = 46.0F;

    public static void box(float x0, float y0, float x1, float y1) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        float cx = (x0 + x1) * 0.5F;
        float halfW = Math.max((x1 - x0) * 0.5F, MIN_HALF_W);
        panel(cx - halfW - PAD_X, y0 - PAD_Y, cx + halfW + PAD_X, y1 + PAD_Y, 1.0F);
    }

    private static void panel(float x0, float y0, float x1, float y1, float alpha) {
        float w = x1 - x0, h = y1 - y0;
        if (GlassProgram.usable() && SceneCapture.hasBackdrop()) {
            GlassRenderer.glass(x0, y0, x1, y1, GlassRenderer.PAD_PANEL,
                    GlassCorners.hotbarCorner(w, h), 0.0F, alpha, GlassRenderer.FROST_PANEL);
            if (GlassProgram.roundUsable()) {
                GlassRenderer.roundRect(x0, y0, x1, y1, GlassCorners.hotbarRadiusPx(w, h),
                        (clampByte(alpha * SCRIM) << 24) | SCRIM_RGB);
            }
        } else if (GlassProgram.roundUsable()) {
            GlassRenderer.roundRect(x0, y0, x1, y1, GlassCorners.hotbarRadiusPx(w, h),
                    (clampByte(alpha * 0.58F) << 24) | SCRIM_RGB);
        }
        GlassWidgets.resetColorCache();
    }

    private static int clampByte(float a) {
        int v = Math.round(a * 255.0F);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
