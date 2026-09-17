package dev.s1mp1e.client.gui.widget;

import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * COLOR setting: a swatch that opens an HSV picker (saturation/value square + hue bar +
 * alpha bar). The SV square is drawn as per-column VERTICAL gradients ({@code fillGradient}
 * is vertical-only) from HSV(h,s,1)→black; hue/alpha are drawn as thin solid strips. The
 * picker draws in {@link #drawOverlay} (the screen calls it OUTSIDE the list scissor and in a
 * later stratum, so it is never clipped and always on top) and captures the mouse while open.
 */
public final class ColorWidget extends Widget {
    private final Setting s;
    private boolean open;
    private int grab = 0;   // 0 none, 1 SV, 2 hue, 3 alpha
    private float H, S, V, A;

    private static final float PW = 108f, SVH = 64f, BARH = 12f, PAD = 6f;

    public ColorWidget(Setting s) { this.s = s; }

    private float swatchX0() { return Math.max(x0, x1 - 44f); }
    private float pX0() { return x1 - PW; }
    private float pY0() { return y1 + 4f; }

    @Override public void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float pt, float alphaF) {
        int a = Math.round(alphaF * 255f);
        float x = swatchX0();
        int light = (a << 24) | 0x808080, dark = (a << 24) | 0x545454;
        float cs = 4f;
        int rows = (int) Math.ceil((y1 - y0) / cs), cols = (int) Math.ceil((x1 - x) / cs);
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                float qx = x + c * cs, qy = y0 + r * cs;
                GlassWidgets.fill(g, qx, qy, Math.min(qx + cs, x1), Math.min(qy + cs, y1), ((r + c) % 2 == 0) ? light : dark);
            }
        int cur = s.colorValue;
        int ca = (cur >>> 24) & 0xFF; if (ca == 0) ca = 255;
        GlassWidgets.fillRound(g, x, y0, x1, y1, (Math.round(ca * alphaF) << 24) | (cur & 0xFFFFFF), 3f);
        GlassWidgets.border(g, x, y0, x1, y1, (Math.round(a * (open ? 0.9f : 0.3f)) << 24) | (open ? 0x0A84FF : 0xFFFFFF));
    }

    @Override public void drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float alphaF) {
        if (!open) return;
        float px = pX0(), py = pY0();
        float ph = PAD * 4 + SVH + BARH * 2;
        // rounded panel (border ring behind + dark body)
        GlassWidgets.fillRound(g, px - PAD - 1, py - PAD - 1, px + PW + PAD + 1, py + ph - PAD + 1, 0x600A84FF, 8f);
        GlassWidgets.fillRound(g, px - PAD, py - PAD, px + PW + PAD, py + ph - PAD, 0xF01C1C1E, 7f);

        // --- SV square (x = saturation, y = value; per-column vertical gradient) ---
        float svy0 = py, svy1 = py + SVH;
        int STEP = 3;
        for (int i = 0; i < PW; i += STEP) {
            float sat = i / PW;
            int top = 0xFF000000 | hsvRgb(H, sat, 1f);
            GlassWidgets.gradientV(g, px + i, svy0, Math.min(px + i + STEP, px + PW), svy1, top, 0xFF000000);
        }
        GlassWidgets.border(g, px, svy0, px + PW, svy1, 0x30FFFFFF);
        // SV marker
        float mx0 = px + S * PW, my0 = svy1 - V * SVH;
        GlassWidgets.border(g, mx0 - 3, my0 - 3, mx0 + 3, my0 + 3, 0xFFFFFFFF);

        // --- hue bar ---
        float hy0 = svy1 + PAD, hy1 = hy0 + BARH;
        for (int i = 0; i < PW; i += STEP) {
            int c = 0xFF000000 | hsvRgb(i / PW, 1f, 1f);
            GlassWidgets.fill(g, px + i, hy0, Math.min(px + i + STEP, px + PW), hy1, c);
        }
        GlassWidgets.fill(g, px + H * PW - 1, hy0 - 1, px + H * PW + 1, hy1 + 1, 0xFFFFFFFF);

        // --- alpha bar (checker + the colour ramped in alpha) ---
        float ay0 = hy1 + PAD, ay1 = ay0 + BARH;
        int rgb = hsvRgb(H, S, V);
        for (int i = 0; i < PW; i += STEP) {
            boolean chk = ((int) (i / 6) % 2) == 0;
            GlassWidgets.fill(g, px + i, ay0, Math.min(px + i + STEP, px + PW), ay1, chk ? 0xFF808080 : 0xFF545454);
            int al = Math.round(i / PW * 255f);
            GlassWidgets.fill(g, px + i, ay0, Math.min(px + i + STEP, px + PW), ay1, (al << 24) | rgb);
        }
        GlassWidgets.fill(g, px + A * PW - 1, ay0 - 1, px + A * PW + 1, ay1 + 1, 0xFFFFFFFF);
    }

    private void syncFromSetting() {
        int cur = s.colorValue;
        int al = (cur >>> 24) & 0xFF; if (al == 0) al = 255;
        A = al / 255f;
        float[] hsv = rgbHsv(cur & 0xFFFFFF);
        H = hsv[0]; S = hsv[1]; V = hsv[2];
    }
    private void writeBack() {
        int rgb = hsvRgb(H, S, V);
        s.colorValue = (Math.round(A * 255f) << 24) | rgb;
        S1mp1eConfig.save();
    }

    @Override public boolean mouseClicked(int mx, int my, int btn) {
        if (btn != 0) return false;
        // swatch toggles the picker
        if (mx >= swatchX0() && mx <= x1 && my >= y0 && my < y1) {
            open = !open; grab = 0; if (open) syncFromSetting(); return true;
        }
        if (!open) return false;
        float px = pX0(), py = pY0();
        float svy1 = py + SVH, hy0 = svy1 + PAD, hy1 = hy0 + BARH, ay0 = hy1 + PAD, ay1 = ay0 + BARH;
        if (mx >= px && mx <= px + PW && my >= py && my <= svy1)      { grab = 1; applyGrab(mx, my); return true; }
        if (mx >= px && mx <= px + PW && my >= hy0 && my <= hy1)      { grab = 2; applyGrab(mx, my); return true; }
        if (mx >= px && mx <= px + PW && my >= ay0 && my <= ay1)      { grab = 3; applyGrab(mx, my); return true; }
        // click outside the panel closes it
        if (mx < px - PAD || mx > px + PW + PAD || my < py - PAD || my > py + PAD * 4 + SVH + BARH * 2) { open = false; }
        return true;
    }
    private void applyGrab(int mx, int my) {
        float px = pX0(), py = pY0();
        float c = Math.max(0f, Math.min(1f, (mx - px) / PW));
        if (grab == 1) { S = c; V = Math.max(0f, Math.min(1f, 1f - (my - py) / SVH)); }
        else if (grab == 2) H = c;
        else if (grab == 3) A = c;
        writeBack();
    }
    @Override public void mouseDragged(int mx, int my, int btn) { if (open && grab != 0) applyGrab(mx, my); }
    @Override public void mouseReleased() { grab = 0; }

    @Override public boolean captures() { return open; }
    @Override public boolean editing() { return open; }
    @Override public void loseFocus() { open = false; grab = 0; }
    @Override public boolean keyPressed(int keyCode) {
        if (open && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { open = false; return true; }
        return false;
    }

    // ---- HSV <-> RGB ----
    private static int hsvRgb(float h, float s, float v) {
        float r, g, b;
        int i = (int) (h * 6f) % 6; if (i < 0) i += 6;
        float f = h * 6f - (float) Math.floor(h * 6f);
        float p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        switch (i) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q;
        }
        return (Math.round(r * 255f) << 16) | (Math.round(g * 255f) << 8) | Math.round(b * 255f);
    }
    private static float[] rgbHsv(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0f;
        if (d > 1e-5f) {
            if (max == r) h = ((g - b) / d) % 6f;
            else if (max == g) h = (b - r) / d + 2f;
            else h = (r - g) / d + 4f;
            h /= 6f; if (h < 0) h += 1f;
        }
        float sv = max <= 0f ? 0f : d / max;
        return new float[] { h, sv, max };
    }
}
