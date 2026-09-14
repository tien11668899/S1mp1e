package dev.s1mp1e.client.gui;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Live drag-to-position editor for HUD modules (world + real HUD render behind). */
public final class S1mp1eHudEditScreen extends GuiScreen {

    private final List<HudElement> elements = new ArrayList<HudElement>();
    private HudElement dragging;
    private int grabX, grabY;
    private boolean grid;
    private final float[] gridRect = new float[4], resetRect = new float[4];

    @Override public boolean doesGuiPauseGame() { return false; }

    @Override
    public void initGui() {
        elements.clear();
        for (Module m : ModuleManager.all()) {
            if (m.enabled && m instanceof HudBounds) elements.add(new HudElement((HudBounds) m));
        }
        if (mc.thePlayer == null) mc.displayGuiScreen(null);
    }

    private void layout() {
        float y = height - 26f;
        gridRect[0] = width / 2f - 84f; gridRect[1] = y; gridRect[2] = width / 2f - 6f; gridRect[3] = y + 18f;
        resetRect[0] = width / 2f + 6f; resetRect[1] = y; resetRect[2] = width / 2f + 84f; resetRect[3] = y + 18f;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float pt) {
        layout();
        GlassWidgets.drawRect(0, 0, width, height, 0x33000000);
        GlassWidgets.resetColorCache();
        SceneCapture.forceGrab();

        if (mc.gameSettings.showDebugInfo) {
            String h = "關閉 F3 才能編輯 HUD";
            GlassWidgets.label(h, (width - GlassWidgets.strW(h)) / 2f, height / 2f, 0xFFD60A, 1f);
        }

        for (HudElement e : elements) {
            float x0 = e.x(), y0 = e.y(), x1 = x0 + e.w(), y1 = y0 + e.h();
            boolean hot = e == dragging || GlassWidgets.inside(mouseX, mouseY, x0, y0, x1, y1);
            GlassWidgets.capsule(x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0.2f, hot ? 0.7f : 0.3f, 1f, true);
            GlassWidgets.border(x0 - 2, y0 - 2, x1 + 2, y1 + 2, (hot ? 0xFF0A84FF : 0x66FFFFFF));
            GlassWidgets.resetColorCache();
            GlassWidgets.label(e.label(), x0, y0 - GlassWidgets.fontH() - 2, hot ? 0xFFFFFF : 0xB0B0B8, 1f);
        }

        // active snap guides
        if (dragging != null) {
            if (dragging.guideVX >= 0) { GlassWidgets.drawRect(dragging.guideVX, 0, dragging.guideVX + 1, height, 0x880A84FF); }
            if (dragging.guideHY >= 0) { GlassWidgets.drawRect(0, dragging.guideHY, width, dragging.guideHY + 1, 0x880A84FF); }
            GlassWidgets.resetColorCache();
        }

        // toolbar
        drawChip(grid ? "格線: 開" : "格線: 關", gridRect, mouseX, mouseY);
        drawChip("重置全部", resetRect, mouseX, mouseY);
        String hint = "拖曳定位 · 邊緣/中心自動吸附 · ESC 完成";
        GlassWidgets.label(hint, (width - GlassWidgets.strW(hint)) / 2f, height - 40f, 0xC7C7CC, 1f);
    }

    private void drawChip(String text, float[] r, int mx, int my) {
        boolean hover = GlassWidgets.inside(mx, my, r[0], r[1], r[2], r[3]);
        GlassWidgets.capsule(r[0], r[1], r[2], r[3], 0.5f, hover ? 0.7f : 0.3f, 1f, true);
        GlassWidgets.label(text, r[0] + (r[2] - r[0] - GlassWidgets.strW(text)) / 2f,
                (r[1] + r[3]) / 2f - GlassWidgets.fontH() / 2f, 0xF5F5F7, 1f);
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        layout();
        if (GlassWidgets.inside(mx, my, gridRect[0], gridRect[1], gridRect[2], gridRect[3])) { grid = !grid; return; }
        if (GlassWidgets.inside(mx, my, resetRect[0], resetRect[1], resetRect[2], resetRect[3])) {
            for (HudElement e : elements) e.reset(); return;
        }
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement e = elements.get(i);
            if (GlassWidgets.inside(mx, my, e.x() - 2, e.y() - 2, e.x() + e.w() + 2, e.y() + e.h() + 2)) {
                dragging = e; grabX = mx - e.x(); grabY = my - e.y(); return;
            }
        }
    }

    @Override
    protected void mouseClickMove(int mx, int my, int btn, long time) {
        if (dragging != null) dragging.moveTo(mx - grabX, my - grabY, width, height, elements, grid, 6);
    }

    @Override
    protected void mouseReleased(int mx, int my, int state) {
        if (dragging != null) { dragging.guideVX = dragging.guideHY = -1; S1mp1eConfig.save(); dragging = null; }
    }

    @Override
    protected void keyTyped(char ch, int code) throws IOException {
        if (code == Keyboard.KEY_ESCAPE) { S1mp1eConfig.save(); mc.displayGuiScreen(new S1mp1eConfigScreen()); return; }
        super.keyTyped(ch, code);
    }
}
