package dev.s1mp1e.client.gui.widget;

import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;

/** Click-to-cycle chip for MODE settings (left = next, right = previous). */
public final class ModeWidget extends Widget {

    private final Setting s;

    public ModeWidget(Setting s) { this.s = s; }

    private float chipX0() {
        float w = GlassWidgets.strW(s.modeValue) + 22f;   // text + chevron padding
        return Math.max(x0, x1 - w);
    }

    @Override
    public void draw(int mouseX, int mouseY, float pt, float alpha) {
        int a = Math.round(alpha * 255f);
        float cx0 = chipX0();
        boolean hover = inBounds(mouseX, mouseY);
        GlassWidgets.capsule(cx0, y0, x1, y1, 0.5f, hover ? 0.7f : 0.35f, alpha, true);
        float cy = (y0 + y1) / 2f;
        GlassWidgets.label(s.modeValue, cx0 + 10f, cy - GlassWidgets.fontH() / 2f, 0xF5F5F7, alpha);
        GlassWidgets.label("›", x1 - 11f, cy - GlassWidgets.fontH() / 2f, 0x8E8E93, alpha);
    }

    @Override
    public boolean mouseClicked(int mx, int my, int btn) {
        if (!inBounds(mx, my)) return false;
        if (btn == 1) s.cycleModeBack(); else s.cycleMode();
        S1mp1eConfig.save();
        return true;
    }
}
