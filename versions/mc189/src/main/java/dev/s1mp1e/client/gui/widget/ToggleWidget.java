package dev.s1mp1e.client.gui.widget;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.anim.Fade;

/** iOS-style on/off switch. Binds to a BOOL {@link Setting} or a {@link Module}'s enabled flag. */
public final class ToggleWidget extends Widget {

    public interface BoolBind { boolean get(); void set(boolean v); }

    private final BoolBind bind;
    private final Fade knob;

    public ToggleWidget(BoolBind bind) {
        this.bind = bind;
        this.knob = new Fade(bind.get() ? 1f : 0f, 130f);
    }

    public static ToggleWidget forSetting(final Setting s) {
        return new ToggleWidget(new BoolBind() {
            public boolean get() { return s.boolValue; }
            public void set(boolean v) { s.boolValue = v; }
        });
    }

    public static ToggleWidget forModule(final Module m) {
        return new ToggleWidget(new BoolBind() {
            public boolean get() { return m.enabled; }
            public void set(boolean v) { m.setEnabled(v); }
        });
    }

    @Override
    public void draw(int mouseX, int mouseY, float pt, float alpha) {
        float t = knob.value();
        int a = Math.round(alpha * 255f);
        // track: grey when off, green when on (cross-fade via alpha)
        float h = y1 - y0;
        float r = h / 2f;
        GlassWidgets.fillRound(x0, y0, x1, y1, ((int) (a * 0.45f) << 24) | 0xFFFFFF, r);
        if (t > 0.01f) {
            int ga = (int) (a * t);
            GlassWidgets.fillRound(x0, y0, x1, y1, (ga << 24) | 0x34C759, r);
        }
        // knob
        float pad = 2f;
        float kd = h - pad * 2f;
        float kx0 = x0 + pad + (x1 - x0 - pad * 2f - kd) * t;
        GlassWidgets.fillRound(kx0, y0 + pad, kx0 + kd, y1 - pad, (a << 24) | 0xFFFFFF, kd / 2f);
    }

    @Override
    public boolean mouseClicked(int mx, int my, int btn) {
        if (btn != 0 || !inBounds(mx, my)) return false;
        boolean nv = !bind.get();
        bind.set(nv);
        knob.retarget(nv ? 1f : 0f);
        S1mp1eConfig.save();
        return true;
    }
}
