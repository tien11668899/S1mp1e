package dev.s1mp1e.client.gui;

import dev.s1mp1e.client.KeybindHandler;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.widget.ToggleWidget;
import dev.s1mp1e.client.gui.widget.Widget;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** In-game liquid-glass config screen: category tabs → module list → per-module settings. */
public final class S1mp1eConfigScreen extends GuiScreen {

    private static final String[] TABS = { "Combat", "HUD", "Visual" };
    private static final String[] TAB_LABELS = { "戰鬥", "HUD", "視覺" };
    private static final float ROW_MOD = 30f, ROW_SET = 28f;
    private static final Object MENU_TARGET = new Object();

    private final Fade openFade = new Fade(0f, 150f);
    private int tab = 0;
    private final List<Module> modules = new ArrayList<Module>();
    private final List<ToggleWidget> moduleToggles = new ArrayList<ToggleWidget>();
    private Module selected;
    private final List<Widget> settingWidgets = new ArrayList<Widget>();
    private float modScroll, setScroll;
    private Object captureTarget;   // a Module (its toggle key) or MENU_TARGET (open key)

    // geometry (filled by layout())
    private float px0, py0, px1, py1, railX1, listY0, listY1, detailX0, setY0, setY1;
    private final float[][] tabRect = new float[3][4];
    private final float[][] modRowRect = new float[64][4];
    private final float[] editHudRect = new float[4], resetAllRect = new float[4],
                          modKeyRect = new float[4], menuKeyRect = new float[4];

    @Override public boolean doesGuiPauseGame() { return true; }
    @Override public void initGui() { openFade.snap(0f); openFade.to(1f); rebuildTab(); }

    private void rebuildTab() {
        modules.clear(); moduleToggles.clear();
        modules.addAll(ModuleManager.byCategory(TABS[tab]));
        for (Module m : modules) moduleToggles.add(ToggleWidget.forModule(m));
        if (selected == null || !modules.contains(selected)) selectModule(modules.isEmpty() ? null : modules.get(0));
        modScroll = 0;
    }

    private void selectModule(Module m) {
        selected = m;
        settingWidgets.clear();
        if (m != null) for (Setting s : m.settings) {
            Widget w = SettingWidgets.forSetting(s);
            if (w != null) settingWidgets.add(w);
        }
        setScroll = 0;
    }

    private String keyName(int code) { return code <= 0 ? "無" : Keyboard.getKeyName(code); }

    private void layout() {
        float pw = Math.min(470f, width - 40f), ph = Math.min(300f, height - 40f);
        px0 = (width - pw) / 2f; py0 = (height - ph) / 2f; px1 = px0 + pw; py1 = py0 + ph;
        railX1 = px0 + 160f;

        float tabY = py0 + 34f, tabH = 26f, tw = (railX1 - px0 - 16f) / 3f;
        for (int i = 0; i < 3; i++) {
            float tx0 = px0 + 8f + i * tw;
            tabRect[i][0] = tx0; tabRect[i][1] = tabY; tabRect[i][2] = tx0 + tw; tabRect[i][3] = tabY + tabH;
        }
        listY0 = tabY + tabH + 8f; listY1 = py1 - 10f;
        for (int i = 0; i < modules.size() && i < modRowRect.length; i++) {
            float r0 = listY0 - modScroll + i * ROW_MOD, r1 = r0 + ROW_MOD - 4f;
            modRowRect[i][0] = px0 + 6f; modRowRect[i][1] = r0; modRowRect[i][2] = railX1 - 6f; modRowRect[i][3] = r1;
            ToggleWidget tg = moduleToggles.get(i);
            float cy = (r0 + r1) / 2f;
            tg.setBounds(railX1 - 8f - 32f, cy - 8f, railX1 - 8f, cy + 8f);
        }

        detailX0 = railX1 + 12f;
        editHudRect[0] = px1 - 8f - chipW("編輯 HUD"); editHudRect[1] = py0 + 9f; editHudRect[2] = px1 - 8f; editHudRect[3] = py0 + 27f;
        resetAllRect[0] = editHudRect[0] - 8f - chipW("重置全部"); resetAllRect[1] = editHudRect[1]; resetAllRect[2] = editHudRect[0] - 8f; resetAllRect[3] = editHudRect[3];
        setY0 = py0 + 34f; setY1 = py1 - 24f;
        for (int i = 0; i < settingWidgets.size(); i++) {
            float r0 = setY0 - setScroll + i * ROW_SET, r1 = r0 + ROW_SET;
            settingWidgets.get(i).setBounds(detailX0 + 96f, r0 + 5f, px1 - 26f, r1 - 5f);
        }
        // module toggle-key chip + menu-key chip along the footer
        float footY = py1 - 20f;
        String mk = selected == null ? "" : ("開關: " + keyName(KeybindHandler.bindingFor(selected.name) == null ? 0 : KeybindHandler.bindingFor(selected.name).getKeyCode()));
        modKeyRect[0] = detailX0; modKeyRect[1] = footY; modKeyRect[2] = detailX0 + chipW(mk); modKeyRect[3] = footY + 16f;
        String menu = "選單鍵: " + keyName(S1mp1eConfig.getMenuKey());
        menuKeyRect[0] = px1 - 8f - chipW(menu); menuKeyRect[1] = footY; menuKeyRect[2] = px1 - 8f; menuKeyRect[3] = footY + 16f;
    }

    private float chipW(String s) { return GlassWidgets.strW(s) + 16f; }

    @Override
    public void drawScreen(int mouseX, int mouseY, float pt) {
        openFade.to(1f);
        float a = Math.max(0.001f, openFade.value());
        drawDefaultBackground();
        SceneCapture.forceGrab();
        layout();

        GlassWidgets.panel(px0, py0, px1, py1, a);
        int div = (Math.round(a * 0.15f) << 24) | 0xFFFFFF;
        GlassWidgets.drawRect(railX1, py0 + 12, railX1 + 1, py1 - 12, div);
        GlassWidgets.resetColorCache();
        GlassWidgets.label("S1mp1e", px0 + 14, py0 + 13, 0xF5F5F7, a);

        // tabs
        for (int i = 0; i < 3; i++) {
            float[] r = tabRect[i];
            boolean act = i == tab;
            if (act) GlassWidgets.capsule(r[0] + 2, r[1], r[2] - 2, r[3], 0.5f, 0.6f, a, true);
            GlassWidgets.label(TAB_LABELS[i], r[0] + (r[2] - r[0] - GlassWidgets.strW(TAB_LABELS[i])) / 2f,
                    (r[1] + r[3]) / 2f - GlassWidgets.fontH() / 2f, act ? 0xFFFFFF : 0x9A9AA0, a);
        }

        // module list
        GlassWidgets.beginScissor(px0, listY0, railX1, listY1);
        for (int i = 0; i < modules.size(); i++) {
            float[] r = modRowRect[i];
            Module m = modules.get(i);
            if (m == selected) GlassWidgets.capsule(r[0], r[1], r[2], r[3], 0.4f, 0.5f, a, true);
            GlassWidgets.label(m.name, px0 + 14, (r[1] + r[3]) / 2f - GlassWidgets.fontH() / 2f,
                    m == selected ? 0xFFFFFF : 0xE0E0E6, a);
            moduleToggles.get(i).draw(mouseX, mouseY, pt, a);
        }
        GlassWidgets.endScissor();

        // detail header
        if (selected != null) {
            GlassWidgets.label(selected.name, detailX0, py0 + 13, 0xF5F5F7, a);
            drawChip("編輯 HUD", editHudRect, mouseX, mouseY, a, 0x0A84FF);
            drawChip("重置全部", resetAllRect, mouseX, mouseY, a, 0xFFFFFF);

            // settings
            GlassWidgets.beginScissor(detailX0, setY0, px1 - 8, setY1);
            for (int i = 0; i < settingWidgets.size(); i++) {
                Setting s = selected.settings.get(i);
                Widget w = settingWidgets.get(i);
                float cy = (w.y0 + w.y1) / 2f;
                GlassWidgets.label(s.name, detailX0, cy - GlassWidgets.fontH() / 2f, 0xC7C7CC, a);
                w.draw(mouseX, mouseY, pt, a);
                GlassWidgets.label("↺", px1 - 20, cy - GlassWidgets.fontH() / 2f,
                        GlassWidgets.inside(mouseX, mouseY, px1 - 22, w.y0, px1 - 8, w.y1) ? 0xFFFFFF : 0x8E8E93, a);
            }
            GlassWidgets.endScissor();
            for (int i = 0; i < settingWidgets.size(); i++) settingWidgets.get(i).drawOverlay(mouseX, mouseY, a);

            // footer chips
            String mk = "開關: " + keyName(KeybindHandler.bindingFor(selected.name) == null ? 0 : KeybindHandler.bindingFor(selected.name).getKeyCode());
            drawChip(mk, modKeyRect, mouseX, mouseY, a, 0xFFFFFF);
        }
        String menu = "選單鍵: " + keyName(S1mp1eConfig.getMenuKey());
        drawChip(menu, menuKeyRect, mouseX, mouseY, a, 0xFFFFFF);

        if (captureTarget != null) {
            GlassWidgets.drawRect(px0, py0, px1, py1, (Math.round(a * 0.55f) << 24) | 0x000000);
            GlassWidgets.resetColorCache();
            String t = "按一個鍵綁定…  ESC 取消";
            GlassWidgets.label(t, (width - GlassWidgets.strW(t)) / 2f, height / 2f - 4, 0xFFFFFF, a);
        }
    }

    private void drawChip(String text, float[] r, int mx, int my, float a, int rgb) {
        boolean hover = GlassWidgets.inside(mx, my, r[0], r[1], r[2], r[3]);
        GlassWidgets.capsule(r[0], r[1], r[2], r[3], 0.5f, hover ? 0.7f : 0.3f, a, true);
        GlassWidgets.label(text, r[0] + (r[2] - r[0] - GlassWidgets.strW(text)) / 2f,
                (r[1] + r[3]) / 2f - GlassWidgets.fontH() / 2f, rgb, a);
    }

    @Override
    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        if (captureTarget != null) return;   // waiting for a key
        layout();

        // capturing widget (open colour popup) gets first refusal
        for (Widget w : settingWidgets) if (w.captures()) { w.mouseClicked(mx, my, btn); return; }

        // tabs
        for (int i = 0; i < 3; i++) if (hit(tabRect[i], mx, my)) { if (i != tab) { tab = i; rebuildTab(); } return; }

        // module rows: toggle first, else select
        if (GlassWidgets.inside(mx, my, px0, listY0, railX1, listY1)) {
            for (int i = 0; i < modules.size(); i++) {
                if (moduleToggles.get(i).mouseClicked(mx, my, btn)) return;
                if (hit(modRowRect[i], mx, my)) { selectModule(modules.get(i)); return; }
            }
            return;
        }

        if (selected != null) {
            if (hit(editHudRect, mx, my)) { mc.displayGuiScreen(new S1mp1eHudEditScreen()); return; }
            if (hit(resetAllRect, mx, my)) { for (Setting s : selected.settings) s.reset(); S1mp1eConfig.save(); return; }
            if (hit(modKeyRect, mx, my)) { captureTarget = selected; return; }
            // settings region: widgets + reset glyphs
            if (GlassWidgets.inside(mx, my, detailX0, setY0, px1 - 8, setY1)) {
                for (int i = 0; i < settingWidgets.size(); i++) {
                    Widget w = settingWidgets.get(i);
                    if (GlassWidgets.inside(mx, my, px1 - 22, w.y0, px1 - 8, w.y1)) {
                        selected.settings.get(i).reset(); S1mp1eConfig.save(); return;
                    }
                    if (w.mouseClicked(mx, my, btn)) return;
                }
                return;
            }
        }
        if (hit(menuKeyRect, mx, my)) { captureTarget = MENU_TARGET; return; }
    }

    private boolean hit(float[] r, int mx, int my) { return GlassWidgets.inside(mx, my, r[0], r[1], r[2], r[3]); }

    @Override
    protected void mouseClickMove(int mx, int my, int btn, long time) {
        layout();
        for (Widget w : settingWidgets) if (w.captures()) { w.mouseDragged(mx, my, btn); return; }
        for (Widget w : settingWidgets) w.mouseDragged(mx, my, btn);
    }

    @Override
    protected void mouseReleased(int mx, int my, int state) {
        for (Widget w : settingWidgets) w.mouseReleased();
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        float step = d > 0 ? -ROW_SET : ROW_SET;
        if (GlassWidgets.inside(mx, my, px0, listY0, railX1, listY1)) {
            modScroll = clampScroll(modScroll + step, modules.size() * ROW_MOD, listY1 - listY0);
        } else {
            setScroll = clampScroll(setScroll + step, settingWidgets.size() * ROW_SET, setY1 - setY0);
        }
    }

    private float clampScroll(float v, float content, float view) {
        float max = Math.max(0f, content - view);
        return v < 0 ? 0 : (v > max ? max : v);
    }

    @Override
    protected void keyTyped(char ch, int code) throws IOException {
        if (captureTarget != null) {
            if (code != Keyboard.KEY_ESCAPE) {
                if (captureTarget == MENU_TARGET) {
                    S1mp1eConfig.setMenuKey(code);
                } else if (captureTarget instanceof Module) {
                    KeyBinding kb = KeybindHandler.bindingFor(((Module) captureTarget).name);
                    if (kb != null) {
                        kb.setKeyCode(code);
                        KeyBinding.resetKeyBindingArrayAndHash();
                        mc.gameSettings.saveOptions();
                    }
                }
            }
            captureTarget = null;
            return;
        }
        if (code == Keyboard.KEY_ESCAPE || code == S1mp1eConfig.getMenuKey()) { mc.displayGuiScreen(null); return; }
        super.keyTyped(ch, code);
    }

    @Override public void onGuiClosed() { S1mp1eConfig.save(); }
}
