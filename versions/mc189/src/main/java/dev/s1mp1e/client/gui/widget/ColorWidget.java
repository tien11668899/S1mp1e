package dev.s1mp1e.client.gui.widget;

import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;

/** COLOR setting: a swatch chip that opens an HSB + alpha picker popup. */
public final class ColorWidget extends Widget {

    private final Setting s;
    private boolean open;
    private float h, sat, val;   // 0..1
    private int alpha;           // 0..255
    private int drag;            // 0 none, 1 sv, 2 hue, 3 alpha

    // popup geometry (computed in draw)
    private float svX, svY, svS, hueX, hueW, alX, alY, alW, alH;

    public ColorWidget(Setting s) { this.s = s; }

    private int swatchX0() { return (int) Math.max(x0, x1 - 44f); }

    private void loadFromSetting() {
        int c = s.colorValue;
        alpha = (c >>> 24) & 0xFF;
        if (alpha == 0) alpha = 255;
        float[] hsb = java.awt.Color.RGBtoHSB((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, null);
        h = hsb[0]; sat = hsb[1]; val = hsb[2];
    }

    private void writeToSetting() {
        int rgb = java.awt.Color.HSBtoRGB(h, sat, val) & 0xFFFFFF;
        int a = alpha < 1 ? 1 : alpha;
        s.colorValue = (a << 24) | rgb;
        S1mp1eConfig.save();
    }

    private int rgbNow() { return java.awt.Color.HSBtoRGB(h, sat, val) & 0xFFFFFF; }

    @Override
    public void draw(int mouseX, int mouseY, float pt, float alphaF) {
        int a = Math.round(alphaF * 255f);
        float sx0 = swatchX0();
        GlassWidgets.dropShadow(sx0, y0, x1, y1, 3f, alphaF);
        // checker behind, then colour on top (to show transparency)
        checker(sx0, y0, x1, y1, a);
        int cur = s.colorValue;
        int ca = ((cur >>> 24) & 0xFF);
        if (ca == 0) ca = 255;
        GlassWidgets.fillRoundSmooth(sx0, y0, x1, y1, (Math.round(ca * alphaF) << 24) | (cur & 0xFFFFFF), 3f);
        GlassWidgets.border(sx0, y0, x1, y1, (Math.round(a * 0.3f) << 24) | 0xFFFFFF);
    }

    @Override
    public void drawOverlay(int mouseX, int mouseY, float alphaF) {
        if (!open) return;
        int a = Math.round(alphaF * 255f);
        // ---- popup ----
        float pw = 150f, ph = 150f;
        float px0 = Math.min(x1, mc() - pw - 4f) - pw + pw;   // keep on-screen right edge
        px0 = Math.max(4f, Math.min(x1 - pw, mc() - pw - 4f));
        float py0 = y1 + 4f;
        GlassWidgets.panel(px0 - 4, py0 - 4, px0 + pw + 4, py0 + ph + 4, alphaF);
        GlassWidgets.drawRect(px0 - 4, py0 - 4, px0 + pw + 4, py0 + ph + 4, (Math.round(a * 0.35f) << 24) | 0x101012);

        svS = 96f; svX = px0 + 8f; svY = py0 + 8f;
        hueX = svX + svS + 8f; hueW = 12f;
        alX = svX; alY = svY + svS + 8f; alW = svS + 8f + hueW; alH = 12f;

        drawSV();
        drawHue();
        drawAlpha(a);

        // hex + preview
        int rgb = rgbNow();
        String hex = String.format("#%02X%02X%02X %d%%", (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF,
                                    Math.round(alpha / 255f * 100f));
        GlassWidgets.fillRoundSmooth(alX, alY + alH + 6, alX + 14, alY + alH + 20, (0xFF << 24) | rgb, 3f);
        GlassWidgets.label(hex, alX + 20, alY + alH + 6 + (14 - GlassWidgets.fontH()) / 2f, 0xF5F5F7, alphaF);
    }

    private void drawSV() {
        int hue = java.awt.Color.HSBtoRGB(h, 1f, 1f) & 0xFFFFFF;
        GlassWidgets.gradientH(svX, svY, svX + svS, svY + svS, 0xFFFFFFFF, 0xFF000000 | hue);
        GlassWidgets.gradientV(svX, svY, svX + svS, svY + svS, 0x00000000, 0xFF000000);
        float px = svX + sat * svS, py = svY + (1f - val) * svS;
        GlassWidgets.border(px - 3, py - 3, px + 3, py + 3, 0xFFFFFFFF);
    }

    private void drawHue() {
        int[] stops = { 0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000 };
        float seg = svS / 6f;
        for (int i = 0; i < 6; i++) {
            GlassWidgets.gradientV(hueX, svY + i * seg, hueX + hueW, svY + (i + 1) * seg, stops[i], stops[i + 1]);
        }
        float hy = svY + h * svS;
        GlassWidgets.border(hueX - 1, hy - 2, hueX + hueW + 1, hy + 2, 0xFFFFFFFF);
    }

    private void drawAlpha(int a) {
        checker(alX, alY, alX + alW, alY + alH, a);
        int rgb = rgbNow();
        GlassWidgets.gradientH(alX, alY, alX + alW, alY + alH, 0x00000000 | rgb, 0xFF000000 | rgb);
        float ax = alX + (alpha / 255f) * alW;
        GlassWidgets.border(ax - 2, alY - 1, ax + 2, alY + alH + 1, 0xFFFFFFFF);
    }

    private void checker(float x0, float y0, float x1, float y1, int a) {
        int light = (a << 24) | 0x808080, dark = (a << 24) | 0x545454;
        float cs = 4f;
        int rows = (int) Math.ceil((y1 - y0) / cs), cols = (int) Math.ceil((x1 - x0) / cs);
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                float qx = x0 + c * cs, qy = y0 + r * cs;
                GlassWidgets.drawRect(qx, qy, Math.min(qx + cs, x1), Math.min(qy + cs, y1),
                                      ((r + c) % 2 == 0) ? light : dark);
            }
        GlassWidgets.resetColorCache();
    }

    private static float mc() {
        net.minecraft.client.gui.ScaledResolution sr =
            new net.minecraft.client.gui.ScaledResolution(net.minecraft.client.Minecraft.getMinecraft());
        return sr.getScaledWidth();
    }

    @Override
    public boolean mouseClicked(int mx, int my, int btn) {
        if (open) {
            if (GlassWidgets.inside(mx, my, svX, svY, svX + svS, svY + svS)) { drag = 1; applyDrag(mx, my); return true; }
            if (GlassWidgets.inside(mx, my, hueX, svY, hueX + hueW, svY + svS)) { drag = 2; applyDrag(mx, my); return true; }
            if (GlassWidgets.inside(mx, my, alX, alY, alX + alW, alY + alH)) { drag = 3; applyDrag(mx, my); return true; }
            // click on the swatch again, or outside → close
            open = false;
            return inBounds(mx, my);
        }
        if (inBounds(mx, my)) { loadFromSetting(); open = true; return true; }
        return false;
    }

    private void applyDrag(int mx, int my) {
        if (drag == 1) {
            sat = clamp01((mx - svX) / svS);
            val = 1f - clamp01((my - svY) / svS);
        } else if (drag == 2) {
            h = clamp01((my - svY) / svS);
        } else if (drag == 3) {
            alpha = Math.round(clamp01((mx - alX) / alW) * 255f);
        }
        writeToSetting();
    }

    @Override
    public void mouseDragged(int mx, int my, int btn) {
        if (drag != 0) applyDrag(mx, my);
    }

    @Override
    public void mouseReleased() { drag = 0; }

    @Override
    public boolean captures() { return open; }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
