package dev.s1mp1e.client.module;

import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;

/**
 * Rounded-pill / real-glass background for text HUD modules (FPS, coordinates, inventory peek).
 *
 * <p>Ported from mc1211's {@code HudGlass} via the mc189 line, and simpler because 1.12.2 is
 * immediate-mode: there is no {@code DrawContext} buffer to flush, and the HUD pass is already bound
 * to the scaled-GUI projection, so a caller draws its glass here at absolute coords and its
 * text/icons at the SAME coords and the two line up (the Keystrokes recipe). Falls back to a flat
 * rounded fill when the glass pipeline is unavailable, so a HUD element never vanishes.
 */
public final class HudGlass {

    private HudGlass() {}

    /**
     * REAL refractive liquid glass for a HUD element's background — the SAME quality as the hotbar
     * (drawn through {@link GlassRenderer} which refracts the frame-primary world backdrop grabbed at
     * the overlay-pass HEAD by {@code GlassHudHandler}). Coordinates are ABSOLUTE scaled-GUI pixels.
     * Falls back to the flat rounded pill when the glass pipeline is unavailable.
     *
     * @param alpha panel opacity 0..1
     */
    public static void glassBox(int x0, int y0, int x1, int y1, float alpha) {
        if (x1 <= x0 || y1 <= y0) return;
        if (SceneCapture.hasBackdrop() && GlassProgram.ensureReady() && GlassProgram.usable()) {
            Minecraft mc = Minecraft.getMinecraft();
            // Suppress the drop shadow while a screen darkens the scene, like the hotbar does.
            GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
            GlassRenderer.glass(x0, y0, x1, y1, 6f, 0.9f, 0f, alpha, GlassRenderer.FROST_PANEL);
            // shadowScale is PERSISTENT (applied on every GLASS bind), and the HUD pass runs
            // BEFORE currentScreen.drawScreen — leaving it at 0 would strip the drop shadow off
            // the inventory panel drawn right after. Restore immediately, like Keystrokes does.
            GlassProgram.setShadowScale(1f);
        } else {
            int a = Math.max(0, Math.min(255, Math.round(alpha * 0x88)));
            roundFill(x0, y0, x1 - x0, y1 - y0, 3, (a << 24) | 0x101014);
        }
    }

    /**
     * Is the real refractive glass pipeline live right now (backdrop grabbed this frame + program ready)?
     * BATCH-B HUD hooks gate on this so a surface either draws real glass or falls back to a flat fill —
     * it never vanishes and never suppresses vanilla when it could not replace it.
     */
    public static boolean glassLive() {
        return SceneCapture.hasBackdrop() && GlassProgram.ensureReady() && GlassProgram.usable();
    }

    /**
     * REAL refractive glass at ABSOLUTE (or current-matrix) coords with the HUD hotbar corner radius
     * (R2 — every NEW BATCH-B glass piece rounds like the hotbar). Returns true when it drew, false when
     * the pipeline is down (the caller then falls back). Suppresses the drop shadow while a screen dims
     * the scene, exactly like {@link #glassBox}, and restores it immediately (the HUD pass runs before the
     * screen's own glass).
     */
    public static boolean glassHotbar(float x0, float y0, float x1, float y1, float alpha, float frost) {
        if (x1 <= x0 || y1 <= y0) return false;
        if (!glassLive()) return false;
        Minecraft mc = Minecraft.getMinecraft();
        GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
        float corner = GlassCorners.hotbarCorner(x1 - x0, y1 - y0);
        GlassRenderer.glass(x0, y0, x1, y1, GlassRenderer.PAD_PANEL, corner, 0f, alpha, frost);
        GlassProgram.setShadowScale(1f);
        return true;
    }

    /**
     * REAL refractive glass CAPSULE (true semicircle ends) at the given rect — the boss-bar concentric
     * glass (G3). Returns true when it drew, false if the capsule program is down (caller falls back to a
     * rounded pill). Shadow suppressed while a screen dims the scene.
     */
    public static boolean glassCapsule(float x0, float y0, float x1, float y1, float alpha, float frost) {
        if (x1 <= x0 || y1 <= y0) return false;
        if (!(SceneCapture.hasBackdrop() && GlassProgram.ensureReady() && GlassProgram.capsuleUsable())) return false;
        Minecraft mc = Minecraft.getMinecraft();
        GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
        GlassRenderer.capsule(x0, y0, x1, y1, GlassRenderer.PAD_PILL, 0f, alpha, frost);
        GlassProgram.setShadowScale(1f);
        return true;
    }

    /** Flat AA-rounded coloured rect (no backdrop, never flickers) — the readability scrim between glass
     *  and text on the new HUD panels. Delegates to the ROUND SDF program. */
    public static void scrim(float x0, float y0, float x1, float y1, float radiusPx, int argb) {
        GlassRenderer.roundRect(x0, y0, x1, y1, radiusPx, argb);
    }

    /** Rounded translucent pill from (x,y) size (w,h) in the current matrix space. */
    public static void pill(int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        int r = Math.min(3, Math.min(w, h) / 2);
        if (r <= 0) { Gui.drawRect(x, y, x + w, y + h, argb); return; }
        Gui.drawRect(x + r,     y,        x + w - r, y + h,     argb);  // centre band, full height
        Gui.drawRect(x,         y + r,    x + r,     y + h - r, argb);  // left band
        Gui.drawRect(x + w - r, y + r,    x + w,     y + h - r, argb);  // right band
    }

    /**
     * A properly ROUNDED rectangle (quarter-circle corners of radius {@code r}) drawn entirely through
     * {@link Gui#drawRect} rows so it winds front-facing and survives the GUI-pass cull. The centre band
     * is one fill; each of the {@code r} corner rows is inset by the circle profile. Same quarter-circle
     * inset maths as mc1211's {@code roundFill}, integer coords.
     */
    public static void roundFill(int x, int y, int w, int h, int r, int argb) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { Gui.drawRect(x, y, x + w, y + h, argb); return; }
        Gui.drawRect(x, y + r, x + w, y + h - r, argb);   // centre band (full width)
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;                       // vertical distance from corner centre to this row
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, r * r - dy * dy)));
            Gui.drawRect(x + inset, y + i,         x + w - inset, y + i + 1,   argb);   // top row
            Gui.drawRect(x + inset, y + h - 1 - i, x + w - inset, y + h - i,   argb);   // bottom row
        }
    }

    /** Linear-interpolate two ARGB colours (t in [0,1]); delegates to {@link GlassWidgets#lerpArgb}. */
    public static int lerpArgb(int c0, int c1, float t) {
        return GlassWidgets.lerpArgb(c0, c1, t);
    }
}
