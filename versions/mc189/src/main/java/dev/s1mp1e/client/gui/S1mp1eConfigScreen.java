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
    private static final float TOGGLE_W = 44f, TOGGLE_H = 20f;   // flat iOS-style pill, used everywhere
    private static final Object MENU_TARGET = new Object();

    private final Fade openFade = new Fade(0f, 150f);
    private final Anim tabSlide = new Anim(0f);    // sliding tab-highlight (banks between tabs)
    private final Anim selSlide = new Anim(0f);    // sliding module-selection highlight
    // smooth hover animation for the footer/header chips: editHUD, resetAll, modKey, menuKey
    private final Fade[] chipFade = { new Fade(0f, 120f), new Fade(0f, 120f), new Fade(0f, 120f), new Fade(0f, 120f) };
    private int tab = 0;
    private final List<Module> modules = new ArrayList<Module>();
    private final List<ToggleWidget> moduleToggles = new ArrayList<ToggleWidget>();
    private Module selected;
    private final List<Widget> settingWidgets = new ArrayList<Widget>();
    private float modScroll, setScroll;                 // eased (displayed) scroll offsets
    private float modScrollTarget, setScrollTarget;     // wheel targets; scroll eases toward these
    private long  lastScrollNanos;
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
        // list content changed entirely — a slide across different modules is meaningless, so snap
        selSlide.snap(Math.max(0, modules.indexOf(selected)));
        modScroll = modScrollTarget = 0;
    }

    private void selectModule(Module m) {
        selected = m;
        int idx = modules.indexOf(m);
        if (idx >= 0) selSlide.to(idx);          // bank the highlight to the newly-selected row
        settingWidgets.clear();
        if (m != null) for (Setting s : m.settings) {
            Widget w = SettingWidgets.forSetting(s);
            if (w != null) settingWidgets.add(w);
        }
        setScroll = setScrollTarget = 0;
    }

    private String keyName(int code) { return code <= 0 ? "無" : Keyboard.getKeyName(code); }

    private void layout() {
        float pw = Math.min(470f, width - 40f), ph = Math.min(300f, height - 40f);
        px0 = (width - pw) / 2f; py0 = (height - ph) / 2f; px1 = px0 + pw; py1 = py0 + ph;
        railX1 = px0 + 160f;

        float tabY = py0 + 12f, tabH = 26f, tw = (railX1 - px0 - 16f) / 3f;
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
            tg.setBounds(railX1 - 8f - TOGGLE_W, cy - TOGGLE_H / 2f, railX1 - 8f, cy + TOGGLE_H / 2f);
        }

        detailX0 = railX1 + 12f;
        editHudRect[0] = px1 - 8f - chipW("編輯 HUD"); editHudRect[1] = py0 + 9f; editHudRect[2] = px1 - 8f; editHudRect[3] = py0 + 27f;
        resetAllRect[0] = editHudRect[0] - 8f - chipW("重置全部"); resetAllRect[1] = editHudRect[1]; resetAllRect[2] = editHudRect[0] - 8f; resetAllRect[3] = editHudRect[3];
        setY0 = py0 + 34f; setY1 = py1 - 24f;
        for (int i = 0; i < settingWidgets.size(); i++) {
            Widget w = settingWidgets.get(i);
            float r0 = setY0 - setScroll + i * ROW_SET, r1 = r0 + ROW_SET;
            if (w instanceof ToggleWidget) {
                // compact flat pill right-aligned (not a row-spanning bar)
                float cy = (r0 + r1) / 2f;
                w.setBounds(px1 - 12f - TOGGLE_W, cy - TOGGLE_H / 2f, px1 - 12f, cy + TOGGLE_H / 2f);
            } else {
                w.setBounds(detailX0 + 96f, r0 + 5f, px1 - 12f, r1 - 5f);
            }
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
        easeScroll();
        layout();

        GlassWidgets.panel(px0, py0, px1, py1, a);
        int div = (Math.round(a * 0.15f) << 24) | 0xFFFFFF;
        // vertical rail divider, plus a hairline under each column's header
        GlassWidgets.drawRect(railX1, py0 + 12, railX1 + 1, py1 - 12, div);
        GlassWidgets.drawRect(px0 + 10, listY0 - 6, railX1 - 10, listY0 - 5, div);
        GlassWidgets.drawRect(detailX0, setY0 - 6, px1 - 8, setY0 - 5, div);
        GlassWidgets.resetColorCache();

        // tabs: one glass highlight that slides between tabs, labels cross-fading as it passes
        float tw = tabRect[0][2] - tabRect[0][0];
        float tp = tabSlide.value();
        float hx0 = tabRect[0][0] + tp * tw;
        GlassWidgets.capsule(hx0 + 2, tabRect[0][1], hx0 + tw - 2, tabRect[0][3], 0.5f, 0.6f, a, true);
        for (int i = 0; i < 3; i++) {
            float[] r = tabRect[i];
            float prox = Math.max(0f, 1f - Math.abs(tp - i));
            GlassWidgets.label(TAB_LABELS[i], r[0] + (r[2] - r[0] - GlassWidgets.strW(TAB_LABELS[i])) / 2f,
                    (r[1] + r[3]) / 2f - GlassWidgets.fontH() / 2f, lerpRGB(0x9A9AA0, 0xFFFFFF, prox), a);
        }

        // module list: one glass highlight that slides between rows, labels cross-fading
        GlassWidgets.beginScissor(px0, listY0, railX1, listY1);
        float sp = selSlide.value();
        if (!modules.isEmpty()) {
            float sr0 = listY0 - modScroll + sp * ROW_MOD, sr1 = sr0 + ROW_MOD - 4f;
            GlassWidgets.capsule(px0 + 6, sr0, railX1 - 6, sr1, 0.4f, 0.5f, a, true);
        }
        int rows = Math.min(modules.size(), modRowRect.length);
        for (int i = 0; i < rows; i++) {
            float[] r = modRowRect[i];
            Module m = modules.get(i);
            float prox = Math.max(0f, 1f - Math.abs(sp - i));
            GlassWidgets.label(Lang.module(m.name), px0 + 14, (r[1] + r[3]) / 2f - GlassWidgets.fontH() / 2f,
                    lerpRGB(0xE0E0E6, 0xFFFFFF, prox), a);
            moduleToggles.get(i).draw(mouseX, mouseY, pt, a);
        }
        GlassWidgets.endScissor();

        // detail header
        if (selected != null) {
            GlassWidgets.label(Lang.module(selected.name), detailX0, py0 + 13, 0xF5F5F7, a);
            drawChip("編輯 HUD", editHudRect, mouseX, mouseY, a, 0x0A84FF, 0);
            drawChip("重置全部", resetAllRect, mouseX, mouseY, a, 0xFFFFFF, 1);

            // settings
            GlassWidgets.beginScissor(detailX0, setY0, px1 - 8, setY1);
            for (int i = 0; i < settingWidgets.size(); i++) {
                Setting s = selected.settings.get(i);
                Widget w = settingWidgets.get(i);
                float cy = (w.y0 + w.y1) / 2f;
                GlassWidgets.label(Lang.setting(s.name), detailX0, cy - GlassWidgets.fontH() / 2f, 0xC7C7CC, a);
                w.draw(mouseX, mouseY, pt, a);
            }
            GlassWidgets.endScissor();
            for (int i = 0; i < settingWidgets.size(); i++) settingWidgets.get(i).drawOverlay(mouseX, mouseY, a);

            // footer chips
            String mk = "開關: " + keyName(KeybindHandler.bindingFor(selected.name) == null ? 0 : KeybindHandler.bindingFor(selected.name).getKeyCode());
            drawChip(mk, modKeyRect, mouseX, mouseY, a, 0xFFFFFF, 2);
        }
        String menu = "選單鍵: " + keyName(S1mp1eConfig.getMenuKey());
        drawChip(menu, menuKeyRect, mouseX, mouseY, a, 0xFFFFFF, 3);

        // iOS-26 scroll edge effect: re-grab the finished UI, then dissolve each list's top/
        // bottom with a progressive blur + adaptive dim, faded in by how far it's scrolled.
        float ext = 26f, rad = 16f, dim = 0.32f, ramp = 10f;
        float maxMod = Math.max(0f, modules.size() * ROW_MOD - (listY1 - listY0));
        float maxSet = selected == null ? 0f : Math.max(0f, settingWidgets.size() * ROW_SET - (setY1 - setY0));
        SceneCapture.forceGrab();
        GlassWidgets.edgeFade(px0 + 6, listY0, railX1 - 6, listY0 + ext, true,  rad, dim, a * c01(modScroll / ramp));
        GlassWidgets.edgeFade(px0 + 6, listY1 - ext, railX1 - 6, listY1, false, rad, dim, a * c01((maxMod - modScroll) / ramp));
        if (selected != null) {
            GlassWidgets.edgeFade(detailX0, setY0, px1 - 8, setY0 + ext, true,  rad, dim, a * c01(setScroll / ramp));
            GlassWidgets.edgeFade(detailX0, setY1 - ext, px1 - 8, setY1, false, rad, dim, a * c01((maxSet - setScroll) / ramp));
        }

        if (captureTarget != null) {
            GlassWidgets.drawRect(px0, py0, px1, py1, (Math.round(a * 0.55f) << 24) | 0x000000);
            GlassWidgets.resetColorCache();
            String t = "按一個鍵綁定…  ESC 取消";
            GlassWidgets.label(t, (width - GlassWidgets.strW(t)) / 2f, height / 2f - 4, 0xFFFFFF, a);
        }
    }

    private void drawChip(String text, float[] r, int mx, int my, float a, int rgb, int id) {
        boolean hover = GlassWidgets.inside(mx, my, r[0], r[1], r[2], r[3]);
        chipFade[id].to(hover ? 1f : 0f);
        float hv = chipFade[id].value();
        float rad = (r[3] - r[1]) / 2f;
        GlassWidgets.dropShadow(r[0], r[1], r[2], r[3], rad, a * (0.6f + 0.4f * hv));
        GlassWidgets.capsule(r[0], r[1], r[2], r[3], 0.5f, 0.32f + 0.38f * hv, a, true);
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
        for (int i = 0; i < 3; i++) if (hit(tabRect[i], mx, my)) { if (i != tab) { tab = i; tabSlide.to(i); rebuildTab(); } return; }

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
                    if (settingWidgets.get(i).mouseClicked(mx, my, btn)) return;
                }
                return;
            }
        }
        if (hit(menuKeyRect, mx, my)) { captureTarget = MENU_TARGET; return; }
    }

    private boolean hit(float[] r, int mx, int my) { return GlassWidgets.inside(mx, my, r[0], r[1], r[2], r[3]); }

    private static float c01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** Blend two RGB colours; t=0 → c0, t=1 → c1. Used to cross-fade a label as the sliding
     *  highlight passes under it (so nothing pops between selected/unselected states). */
    private static int lerpRGB(int c0, int c1, float t) {
        int r = (int) (((c0 >> 16) & 255) + (((c1 >> 16) & 255) - ((c0 >> 16) & 255)) * t);
        int g = (int) (((c0 >> 8) & 255) + (((c1 >> 8) & 255) - ((c0 >> 8) & 255)) * t);
        int b = (int) ((c0 & 255) + ((c1 & 255) - (c0 & 255)) * t);
        return (r << 16) | (g << 8) | b;
    }

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
            modScrollTarget = clampScroll(modScrollTarget + step, modules.size() * ROW_MOD, listY1 - listY0);
        } else {
            setScrollTarget = clampScroll(setScrollTarget + step, settingWidgets.size() * ROW_SET, setY1 - setY0);
        }
    }

    /** Ease the displayed scroll toward the wheel target — framerate-independent exponential
     *  smoothing (~90ms) so the list glides instead of stepping. */
    private void easeScroll() {
        long now = System.nanoTime();
        float dt = lastScrollNanos == 0L ? 0f : (now - lastScrollNanos) / 1.0e9f;
        lastScrollNanos = now;
        if (dt > 0.1f) dt = 0.1f;                       // clamp a lag/pause spike
        float k = 1f - (float) Math.exp(-dt / 0.09f);
        modScroll += (modScrollTarget - modScroll) * k;
        setScroll += (setScrollTarget - setScroll) * k;
        if (Math.abs(modScrollTarget - modScroll) < 0.25f) modScroll = modScrollTarget;
        if (Math.abs(setScrollTarget - setScroll) < 0.25f) setScroll = setScrollTarget;
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
        // a widget being edited (slider number entry) gets keys first — so ESC/Enter/digits
        // reach it instead of closing the screen
        for (Widget w : settingWidgets) if (w.captures() && w.keyTyped(ch, code)) return;
        if (code == Keyboard.KEY_ESCAPE || code == S1mp1eConfig.getMenuKey()) { mc.displayGuiScreen(null); return; }
        super.keyTyped(ch, code);
    }

    @Override public void onGuiClosed() { S1mp1eConfig.save(); }
}
