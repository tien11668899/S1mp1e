package dev.s1mp1e.client.gui.widget;

import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;

/** Glass slider for INT / DOUBLE settings. Track + accent fill + thumb + value readout. */
public final class SliderWidget extends Widget {

    private final Setting s;
    private boolean dragging;

    public SliderWidget(Setting s) { this.s = s; }

    private String valueStr() {
        if (s.type == Setting.Type.INT) return String.valueOf(s.intValue);
        double v = s.doubleValue;
        if (v == Math.floor(v)) return String.valueOf((int) v);
        return String.format("%.2f", v);
    }

    private float trackX1() {
        return x1 - GlassWidgets.strW(valueStr()) - 10f;
    }

    @Override
    public void draw(int mouseX, int mouseY, float pt, float alpha) {
        int a = Math.round(alpha * 255f);
        float tx0 = x0, tx1 = trackX1();
        float cy = (y0 + y1) / 2f;
        float th = 4f;                                   // track thickness
        float ty0 = cy - th / 2f, ty1 = cy + th / 2f;
        // track
        GlassWidgets.fillRound(tx0, ty0, tx1, ty1, ((int) (a * 0.30f) << 24) | 0xFFFFFF, th / 2f);
        // fill
        float t = (float) s.normalised();
        float fx = tx0 + (tx1 - tx0) * t;
        GlassWidgets.fillRound(tx0, ty0, Math.max(tx0 + th, fx), ty1, (a << 24) | 0x0A84FF, th / 2f);
        // thumb (glass capsule)
        float tr = 6f;
        GlassWidgets.capsule(fx - tr, cy - tr, fx + tr, cy + tr, 1f, dragging ? 0.85f : 0.6f, alpha, true);
        // value
        String v = valueStr();
        GlassWidgets.label(v, x1 - GlassWidgets.strW(v), cy - GlassWidgets.fontH() / 2f, 0xC7C7CC, alpha);
    }

    private void apply(int mx) {
        float tx0 = x0, tx1 = trackX1();
        float t = (tx1 <= tx0) ? 0f : (mx - tx0) / (tx1 - tx0);
        if (t < 0f) t = 0f; if (t > 1f) t = 1f;
        double val = s.min + t * (s.max - s.min);
        if (s.type == Setting.Type.INT) s.setInt((int) Math.round(val));
        else s.setDouble(val);
        S1mp1eConfig.save();
    }

    @Override
    public boolean mouseClicked(int mx, int my, int btn) {
        if (btn != 0) return false;
        // generous vertical hit region over the whole row height
        if (mx >= x0 - 4 && mx <= trackX1() + 6 && my >= y0 && my < y1) {
            dragging = true;
            apply(mx);
            return true;
        }
        return false;
    }

    @Override
    public void mouseDragged(int mx, int my, int btn) {
        if (dragging) apply(mx);
    }

    @Override
    public void mouseReleased() { dragging = false; }

    @Override
    public boolean captures() { return dragging; }
}
