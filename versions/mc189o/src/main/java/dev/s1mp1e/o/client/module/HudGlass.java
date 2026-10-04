package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.gui.GlassWidgets;
import dev.s1mp1e.o.glass.render.GlassProgram;
import dev.s1mp1e.o.glass.render.GlassRenderer;
import dev.s1mp1e.o.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;

/**
 * Rounded-pill / real-glass background for text HUD modules (FPS, coordinates, inventory peek).
 *
 * <p>Ported from mc1211's {@code HudGlass}, but simpler because 1.8.9 is immediate-mode: there is no
 * {@code DrawContext} buffer to flush, and the HUD pass is already bound to the scaled-GUI projection,
 * so a caller draws its glass here at absolute coords and its text/icons at the SAME coords and the two
 * line up (the Keystrokes recipe). Falls back to a flat rounded fill when the glass pipeline is
 * unavailable, so a HUD element never vanishes.
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
            Minecraft mc = Minecraft.getInstance();
            // Suppress the drop shadow while a screen darkens the scene, like the hotbar does.
            GlassProgram.setShadowScale(mc.screen != null ? 0f : 1f);
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

    /** Rounded translucent pill from (x,y) size (w,h) in the current matrix space. */
    public static void pill(int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        int r = Math.min(3, Math.min(w, h) / 2);
        if (r <= 0) { GuiElement.fill(x, y, x + w, y + h, argb); return; }
        GuiElement.fill(x + r,     y,        x + w - r, y + h,     argb);  // centre band, full height
        GuiElement.fill(x,         y + r,    x + r,     y + h - r, argb);  // left band
        GuiElement.fill(x + w - r, y + r,    x + w,     y + h - r, argb);  // right band
    }

    /**
     * A properly ROUNDED rectangle (quarter-circle corners of radius {@code r}) drawn entirely through
     * {@link GuiElement#drawRect} rows so it winds front-facing and survives the GUI-pass cull. The centre band
     * is one fill; each of the {@code r} corner rows is inset by the circle profile. Same quarter-circle
     * inset maths as mc1211's {@code roundFill}, integer coords.
     */
    public static void roundFill(int x, int y, int w, int h, int r, int argb) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { GuiElement.fill(x, y, x + w, y + h, argb); return; }
        GuiElement.fill(x, y + r, x + w, y + h - r, argb);   // centre band (full width)
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;                       // vertical distance from corner centre to this row
            int inset = (int) Math.round(r - Math.sqrt(Math.max(0.0, r * r - dy * dy)));
            GuiElement.fill(x + inset, y + i,         x + w - inset, y + i + 1,   argb);   // top row
            GuiElement.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i,   argb);   // bottom row
        }
    }

    /** Linear-interpolate two ARGB colours (t in [0,1]); delegates to {@link GlassWidgets#lerpArgb}. */
    public static int lerpArgb(int c0, int c1, float t) {
        return GlassWidgets.lerpArgb(c0, c1, t);
    }
}
