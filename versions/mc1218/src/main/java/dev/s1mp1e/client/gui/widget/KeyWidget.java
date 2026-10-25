package dev.s1mp1e.client.gui.widget;

import com.mojang.blaze3d.platform.InputConstants;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Press-a-key chip for key-binding settings ({@link Setting#isKey}), replacing the 0..512 slider that showed raw SDL
 * key codes (GLFW on 26.2). Shows the key's name the way vanilla's Controls screen does ({@code InputConstants.Key.getDisplayName},
 * localised — e.g. 左 Alt). Click → "按下按鍵…" and the next key press is the new binding; Esc cancels, Backspace /
 * Delete unbinds, right-click restores the default. While listening it reports {@link #editing()}, so the config
 * screen routes key presses here first (Esc and the menu key do not close the screen).
 */
public final class KeyWidget extends Widget {
    private final Setting s;
    private boolean listening;

    public KeyWidget(Setting s) { this.s = s; }

    private static String keyName(int code) {
        if (code <= 0) return "未設定";
        try {
            String n = InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
            if (n != null && !n.isBlank() && !n.startsWith("key.")) return n;
        } catch (Throwable ignored) {}
        return "按鍵 " + code;
    }

    private String text() { return listening ? "按下按鍵…（Esc 取消）" : keyName(s.intValue); }

    private float chipX0() { float w = GlassWidgets.strW(text()) + 22f; return Math.max(x0, x1 - w); }

    @Override public void draw(GuiGraphics g, int mouseX, int mouseY, float pt, float alpha) {
        float cx0 = chipX0();
        boolean hover = inBounds(mouseX, mouseY);
        GlassWidgets.capsule(g, cx0, y0, x1, y1, 0.5f, listening ? 0.95f : hover ? 0.7f : 0.35f, alpha, true);
        float cy = (y0 + y1) / 2f;
        int colour = listening ? 0x0A84FF : s.intValue <= 0 ? 0x8E8E93 : 0xF5F5F7;
        GlassWidgets.label(g, text(), cx0 + 11f, cy - GlassWidgets.fontH() / 2f, colour, alpha);
    }

    @Override public boolean mouseClicked(int mx, int my, int btn) {
        if (!inBounds(mx, my)) return false;
        if (btn == 1) { listening = false; s.reset(); S1mp1eConfig.save(); return true; }
        listening = !listening;
        return true;
    }

    @Override public boolean editing() { return listening; }

    @Override public boolean keyPressed(int keyCode) {
        if (!listening) return false;
        listening = false;
        if (keyCode == InputConstants.KEY_ESCAPE) return true;   // cancel, keep the old key
        s.setInt(keyCode == InputConstants.KEY_BACKSPACE || keyCode == InputConstants.KEY_DELETE ? 0 : keyCode);
        S1mp1eConfig.save();
        return true;
    }

    @Override public void loseFocus() { listening = false; }
}
