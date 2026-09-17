package dev.s1mp1e.client.gui.widget;

import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.anim.Fade;
import org.lwjgl.input.Keyboard;

/** Glass slider for INT / DOUBLE settings. Track + accent fill + thumb + value readout.
 *  Drag the track for coarse changes, or CLICK THE VALUE to type an exact number for fine
 *  control. The thumb is a refractive glass capsule that pops slightly while grabbed. */
public final class SliderWidget extends Widget {

    private final Setting s;
    private boolean dragging;
    private boolean editing;
    private String  buf = "";
    private final Fade grab = new Fade(0f, 120f);   // 0 rest → 1 grabbed: thumb pop

    public SliderWidget(Setting s) { this.s = s; }

    private String valueStr() {
        if (s.type == Setting.Type.INT) return String.valueOf(s.intValue);
        double v = s.doubleValue;
        if (v == Math.floor(v)) return String.valueOf((int) v);
        return String.format("%.2f", v);
    }

    /** The right-hand text (edit buffer while typing, else the live value). */
    private String shown() { return editing ? buf : valueStr(); }

    private float trackX1() {
        return x1 - GlassWidgets.strW(shown()) - 10f;
    }

    @Override
    public void draw(int mouseX, int mouseY, float pt, float alpha) {
        int a = Math.round(alpha * 255f);
        float tx0 = x0, tx1 = trackX1();
        float cy = (y0 + y1) / 2f;
        float th = 4f;                                   // track thickness
        float ty0 = cy - th / 2f, ty1 = cy + th / 2f;
        // track (AA rounded)
        GlassWidgets.fillRoundSmooth(tx0, ty0, tx1, ty1, ((int) (a * 0.30f) << 24) | 0xFFFFFF, th / 2f);
        // accent fill up to the thumb
        float t = (float) s.normalised();
        float fx = tx0 + (tx1 - tx0) * t;
        GlassWidgets.fillRoundSmooth(tx0, ty0, Math.max(tx0 + th, fx), ty1, (a << 24) | 0x0A84FF, th / 2f);
        // thumb: refractive glass capsule that pops on grab (track shows through)
        float g  = grab.value();
        float tr = Math.min(8.5f, (y1 - y0) / 2f - 1f) * (1f + 0.16f * g);
        GlassWidgets.dropShadow(fx - tr, cy - tr, fx + tr, cy + tr, tr, alpha);
        GlassWidgets.capsule(fx - tr, cy - tr, fx + tr, cy + tr, 1f, 0.55f + 0.30f * g, alpha, true);
        // value readout — accent + caret while editing
        String v = shown();
        float vx = x1 - GlassWidgets.strW(v);
        int col = editing ? 0x0A84FF : 0xC7C7CC;
        GlassWidgets.label(v, vx, cy - GlassWidgets.fontH() / 2f, col, alpha);
        if (editing) {
            GlassWidgets.drawRect(x1 + 1f, cy - GlassWidgets.fontH() / 2f, x1 + 2f, cy + GlassWidgets.fontH() / 2f,
                    (a << 24) | 0x0A84FF);   // caret
            GlassWidgets.resetColorCache();
        }
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

    private void commit() {
        try {
            double val = Double.parseDouble(buf.trim());
            if (val < s.min) val = s.min;
            if (val > s.max) val = s.max;
            if (s.type == Setting.Type.INT) s.setInt((int) Math.round(val));
            else s.setDouble(val);
            S1mp1eConfig.save();
        } catch (NumberFormatException ignored) {
            // leave the value unchanged on a garbage entry
        }
        editing = false;
    }

    @Override
    public boolean mouseClicked(int mx, int my, int btn) {
        if (btn != 0) { if (editing) commit(); return false; }
        boolean inRow = my >= y0 && my < y1;
        // click the value area → type an exact number
        if (inRow && mx >= trackX1() && mx <= x1 + 6f) {
            editing = true;
            buf = valueStr();
            dragging = false;
            return true;
        }
        if (editing) { commit(); return true; }   // click elsewhere commits the edit
        // otherwise drag the track
        if (mx >= x0 - 4 && mx <= trackX1() + 6 && inRow) {
            dragging = true;
            grab.retarget(1f);
            apply(mx);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyTyped(char ch, int code) {
        if (!editing) return false;
        if (code == Keyboard.KEY_RETURN || code == Keyboard.KEY_NUMPADENTER) { commit(); return true; }
        if (code == Keyboard.KEY_ESCAPE) { editing = false; return true; }   // cancel
        if (code == Keyboard.KEY_BACK) {
            if (buf.length() > 0) buf = buf.substring(0, buf.length() - 1);
            return true;
        }
        if ((ch >= '0' && ch <= '9')
                || (ch == '.' && s.type == Setting.Type.DOUBLE && buf.indexOf('.') < 0)
                || (ch == '-' && buf.isEmpty() && s.min < 0)) {
            if (buf.length() < 9) buf += ch;
            return true;
        }
        return true;   // swallow everything else while editing
    }

    @Override
    public void mouseDragged(int mx, int my, int btn) {
        if (dragging) apply(mx);
    }

    @Override
    public void mouseReleased() { dragging = false; grab.retarget(0f); }

    @Override
    public boolean captures() { return dragging || editing; }
}
