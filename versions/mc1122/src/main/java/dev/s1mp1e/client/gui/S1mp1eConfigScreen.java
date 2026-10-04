package dev.s1mp1e.client.gui;

import dev.s1mp1e.client.KeybindHandler;
import dev.s1mp1e.client.LayoutEditable;
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

/**
 * In-game liquid-glass config screen: category tabs → module list → per-module settings.
 * 1.8.9 (Forge, LWJGL2, immediate-mode GL) port of mc1211's core-profile screen. The 4pt
 * spacing grid, three-stage cross-fades (page / detail / close), hidden-setting skip and
 * precise (sub-pixel) mouse routing all match the newer lines; the footer bind chips
 * (per-module toggle key + menu-open key) are a 1.8.9-only QoL, re-laid onto the same grid.
 */
public final class S1mp1eConfigScreen extends GuiScreen {

    private static final String[] TABS = { "Combat", "HUD", "Visual" };
    private static final String[] TAB_LABELS = { "戰鬥", "HUD", "視覺" };

    // --- Apple-style 4pt spacing grid: every inset/gap/size is a multiple of GRID ---
    private static final float GRID = 4f;    // base unit
    private static final float PAD  = 12f;   // 3·GRID  content inset from a panel/column edge
    private static final float GAP  = 8f;    // 2·GRID  gap between sibling sections
    private static final float HAIR = 4f;    // 1·GRID  fine inset (row edge / hairline offset)
    private static final float RAIL_W    = 160f; // 40·GRID  left module-rail width
    private static final float TAB_H     = 28f;  // 7·GRID
    private static final float CHIP_H    = 18f;  // header/footer chips
    private static final float LABEL_COL = 96f;  // 24·GRID  setting-name column width
    private static final float ROW_MOD = 32f, ROW_SET = 28f;   // 8·GRID / 7·GRID
    private static final float TOGGLE_W = 40f, TOGGLE_H = 18f;  // track ~2.25:1 (wide/flat, matched to iOS-26)
    private static final float ROW_PILL_M = 6f;                // module-row highlight margin (uniform L/R + inter-row gap)
    private static final float TAB_PILL_H = 20f;               // highlight pill height (centred in the 28px tab row)

    private static final Object MENU_TARGET = new Object();
    // chip hover-fade ids
    private static final int CHIP_EDIT_HUD = 0, CHIP_RESET = 1, CHIP_MOD_KEY = 2, CHIP_MENU_KEY = 3, CHIP_LAYOUT = 4;

    private final Fade openFade = new Fade(0f, 150f);
    private final Fade pageFade = new Fade(1f, 130f);    // content cross-fade on tab switch
    private final Fade detailFade = new Fade(1f, 120f);  // detail cross-fade on MODULE switch (name + settings + 編輯排版)
    private Module pendingSelect = null;                 // module to select once the detail has faded out
    private final Anim tabSlide = new Anim(0f);          // sliding tab-highlight (banks between tabs)
    private final Anim selSlide = new Anim(0f);          // sliding module-selection highlight
    // smooth hover animation for the chips: editHUD, resetAll, modKey, menuKey, layout
    private final Fade[] chipFade = { new Fade(0f, 120f), new Fade(0f, 120f), new Fade(0f, 120f),
                                      new Fade(0f, 120f), new Fade(0f, 120f) };
    private int tab = 0;
    private int pendingTab = -1;                         // tab to apply once the page has faded out
    private boolean closing;                             // close fade-out in progress
    private final List<Module> modules = new ArrayList<Module>();
    private final List<ToggleWidget> moduleToggles = new ArrayList<ToggleWidget>();
    private Module selected;
    private final List<Widget> settingWidgets = new ArrayList<Widget>();
    /** The settings that actually have a visible row, parallel to {@link #settingWidgets}
     *  (hidden settings are skipped, so this is NOT the same as {@code selected.settings}). */
    private final List<Setting> shownSettings = new ArrayList<Setting>();
    private float modScroll, setScroll;                 // eased (displayed) scroll offsets
    private float modScrollTarget, setScrollTarget;     // wheel targets; scroll eases toward these
    private long  lastScrollNanos;
    private Object captureTarget;   // a Module (its toggle key) or MENU_TARGET (open key)

    // geometry (filled by layout())
    private float px0, py0, px1, py1, railX1, listY0, listY1, detailX0, setY0, setY1;
    private final float[][] tabRect = new float[3][4];
    private final float[][] modRowRect = new float[64][4];
    private final float[] editHudRect = new float[4], resetAllRect = new float[4],
                          layoutRect  = new float[4],   // "編輯排版" — only for LayoutEditable modules
                          modKeyRect  = new float[4], menuKeyRect = new float[4];

    @Override public boolean doesGuiPauseGame() { return true; }
    @Override public void initGui() {
        // under a running screen cross-dissolve the page is complete from its first frame (ScreenOpenFade.held)
        openFade.snap(ScreenOpenFade.held() ? 1f : 0f); openFade.to(1f); rebuildTab();
    }

    private void rebuildTab() {
        modules.clear(); moduleToggles.clear();
        modules.addAll(ModuleManager.byCategory(TABS[tab]));
        for (Module m : modules) moduleToggles.add(ToggleWidget.forModule(m));
        // tab switch already cross-fades the whole page (pageFade) — swap the detail immediately, no extra fade.
        if (selected == null || !modules.contains(selected)) applySelect(modules.isEmpty() ? null : modules.get(0));
        selSlide.snap(Math.max(0, modules.indexOf(selected)));
        detailFade.snap(1f); pendingSelect = null;
        modScroll = modScrollTarget = 0;
    }

    /** Public entry: slides the left pill immediately, cross-fades the RIGHT detail when the module changes. */
    private void selectModule(Module m) {
        if (m == null) { applySelect(null); detailFade.snap(1f); pendingSelect = null; return; }
        int idx = modules.indexOf(m);
        if (idx >= 0) selSlide.to(idx);                 // left highlight slides to the new row right away
        if (selected == null || m == selected) {        // first pick / re-pick: no fade
            applySelect(m); detailFade.snap(1f); pendingSelect = null;
        } else {
            pendingSelect = m; detailFade.to(0f);       // fade the detail out; drawScreen() swaps once faded
        }
    }

    /** Actually swap the detail content (settings widgets) to module {@code m}. */
    private void applySelect(Module m) {
        selected = m;
        settingWidgets.clear();
        shownSettings.clear();
        if (m != null) for (Setting s : m.settings) {
            if (s.hidden) continue;   // persisted but not shown (e.g. per-key Keystrokes positions)
            Widget w = SettingWidgets.forSetting(s);
            if (w != null) { settingWidgets.add(w); shownSettings.add(s); }
        }
        setScroll = setScrollTarget = 0;
    }

    private String keyName(int code) { return code <= 0 ? "無" : Keyboard.getKeyName(code); }

    private void layout() {
        float pw = Math.min(470f, width - 40f), ph = Math.min(300f, height - 40f);
        px0 = (width - pw) / 2f; py0 = (height - ph) / 2f; px1 = px0 + pw; py1 = py0 + ph;
        railX1 = px0 + RAIL_W;

        // tabs span the rail, PAD inset each side, PAD below the panel top
        float tabY = py0 + PAD, tw = (railX1 - px0 - 2f * PAD) / 3f;
        for (int i = 0; i < 3; i++) {
            float tx0 = px0 + PAD + i * tw;
            tabRect[i][0] = tx0; tabRect[i][1] = tabY; tabRect[i][2] = tx0 + tw; tabRect[i][3] = tabY + TAB_H;
        }

        // footer chip band (1.8.9-only): sits PAD above the panel bottom; both lists end GAP above it
        float footY1 = py1 - PAD, footY0 = footY1 - CHIP_H;
        listY0 = tabY + TAB_H + GAP; listY1 = footY0 - GAP;
        for (int i = 0; i < modules.size() && i < modRowRect.length; i++) {
            float r0 = listY0 - modScroll + i * ROW_MOD, r1 = r0 + ROW_MOD;   // full pitch cell (content centres at r0+16)
            modRowRect[i][0] = px0 + HAIR; modRowRect[i][1] = r0; modRowRect[i][2] = railX1 - HAIR; modRowRect[i][3] = r1;
            float cy = (r0 + r1) / 2f;
            // toggle pulled in by ROW_PILL_M so its right edge sits inside the highlight pill (aligns with the tab band)
            moduleToggles.get(i).setBounds(railX1 - PAD - ROW_PILL_M - TOGGLE_W, cy - TOGGLE_H / 2f,
                                           railX1 - PAD - ROW_PILL_M, cy + TOGGLE_H / 2f);
        }

        detailX0 = railX1 + PAD;
        // detail header row: chips CENTRED in the same TAB_H band as the left tabs (header + tabs same height)
        float chipY = py0 + PAD + (TAB_H - CHIP_H) / 2f;
        resetAllRect[0] = px1 - PAD - chipW("重置全部"); resetAllRect[1] = chipY; resetAllRect[2] = px1 - PAD; resetAllRect[3] = chipY + CHIP_H;
        editHudRect[0]  = resetAllRect[0] - GAP - chipW("編輯 HUD"); editHudRect[1] = chipY; editHudRect[2] = resetAllRect[0] - GAP; editHudRect[3] = chipY + CHIP_H;
        layoutRect[0]   = editHudRect[0] - GAP - chipW("編輯排版"); layoutRect[1] = chipY; layoutRect[2] = editHudRect[0] - GAP; layoutRect[3] = chipY + CHIP_H;
        // detail list starts level with the module list (both = tabs bottom + GAP) so the two dividers line up
        setY0 = py0 + PAD + TAB_H + GAP; setY1 = footY0 - GAP;
        for (int i = 0; i < settingWidgets.size(); i++) {
            Widget w = settingWidgets.get(i);
            float r0 = setY0 - setScroll + i * ROW_SET, r1 = r0 + ROW_SET;
            if (w instanceof ToggleWidget) {
                float cy = (r0 + r1) / 2f;
                w.setBounds(px1 - PAD - TOGGLE_W, cy - TOGGLE_H / 2f, px1 - PAD, cy + TOGGLE_H / 2f);
            } else {
                w.setBounds(detailX0 + LABEL_COL, r0 + GRID + 1f, px1 - PAD, r1 - GRID - 1f);
            }
        }

        // footer bind chips (LWJGL2): module toggle-key on the left, menu-open key on the right
        String mk = selected == null ? "" :
            ("開關: " + keyName(KeybindHandler.bindingFor(selected.name) == null ? 0 : KeybindHandler.bindingFor(selected.name).getKeyCode()));
        modKeyRect[0] = detailX0; modKeyRect[1] = footY0; modKeyRect[2] = detailX0 + chipW(mk); modKeyRect[3] = footY1;
        String menu = "選單鍵: " + keyName(S1mp1eConfig.getMenuKey());
        menuKeyRect[0] = px1 - PAD - chipW(menu); menuKeyRect[1] = footY0; menuKeyRect[2] = px1 - PAD; menuKeyRect[3] = footY1;
    }

    private float chipW(String s) { return GlassWidgets.strW(s) + 12f; }

    @Override
    public void drawScreen(int mouseX, int mouseY, float pt) {
        // Close fade-out: requestClose() starts the fade; once it reaches 0 we actually dismiss.
        if (closing) {
            if (openFade.value() <= 0.02f) { mc.displayGuiScreen(null); return; }
        } else {
            openFade.to(1f);
        }
        // Tab page cross-fade: swap the tab only once the old page has faded out, then fade in.
        if (pendingTab >= 0 && pageFade.value() <= 0.05f) {
            tab = pendingTab; pendingTab = -1; rebuildTab(); pageFade.to(1f);
        }
        float a  = Math.max(0.001f, openFade.value());
        float pa = a * pageFade.value();   // page-content alpha (dips during a tab switch)
        // module cross-fade: swap the detail once it's faded out, then fade it back in.
        if (pendingSelect != null && detailFade.value() <= 0.05f) {
            applySelect(pendingSelect); pendingSelect = null; detailFade.to(1f);
        }
        float da = pa * detailFade.value();   // detail alpha (name + settings + 編輯排版; shared chips stay at pa)

        drawDefaultBackground();
        SceneCapture.forceGrab();
        easeScroll();
        layout();

        GlassWidgets.panel(px0, py0, px1, py1, a);
        // a is openFade 0..1 (a FLOAT) — must scale to a 0..255 alpha byte, else
        // round(a*0.15) is 0 for all a and the dividers never render.
        int div = (Math.round(a * 0.15f * 255f) << 24) | 0xFFFFFF;
        GlassWidgets.drawRect(railX1, py0 + PAD, railX1 + 1, py1 - PAD, div);                    // vertical rail
        GlassWidgets.drawRect(px0 + PAD, listY0 - GAP, railX1 - PAD, listY0 - GAP + 1, div);     // under tabs
        GlassWidgets.drawRect(detailX0, setY0 - GAP, px1 - PAD, setY0 - GAP + 1, div);           // under detail title
        GlassWidgets.resetColorCache();

        // tabs — sliding highlight (a pill vertically CENTRED in the tab row, not the
        // full row height, so it doesn't sit low against the divider) + label cross-fade
        float tw = tabRect[0][2] - tabRect[0][0];
        float tp = tabSlide.value();
        float hx0 = tabRect[0][0] + tp * tw;
        float tcy = (tabRect[0][1] + tabRect[0][3]) / 2f;
        GlassWidgets.capsule(hx0 + 2, tcy - TAB_PILL_H / 2f, hx0 + tw - 2, tcy + TAB_PILL_H / 2f, 1f, 0.6f, a, true);
        for (int i = 0; i < 3; i++) {
            float[] r = tabRect[i];
            float prox = Math.max(0f, 1f - Math.abs(tp - i));
            GlassWidgets.label(TAB_LABELS[i], r[0] + (r[2] - r[0] - GlassWidgets.strW(TAB_LABELS[i])) / 2f,
                    (r[1] + r[3]) / 2f - GlassWidgets.fontH() / 2f, lerpRGB(0x9A9AA0, 0xFFFFFF, prox), a);
        }

        // module list — sliding selection highlight; content at pa so the page cross-fades on a tab switch
        GlassWidgets.beginScissor(px0, listY0, railX1, listY1);
        float sp = selSlide.value();
        if (!modules.isEmpty()) {
            // Pill centred in the pitch cell with a UNIFORM margin (L/R = ROW_PILL_M, inter-row = ROW_PILL_M).
            float rowTop = listY0 - modScroll + sp * ROW_MOD;
            float sr0 = rowTop + ROW_PILL_M / 2f, sr1 = rowTop + ROW_MOD - ROW_PILL_M / 2f;
            GlassWidgets.capsule(px0 + PAD, sr0, railX1 - PAD, sr1, 0.4f, 0.5f, pa, true);
        }
        int rows = Math.min(modules.size(), modRowRect.length);
        for (int i = 0; i < rows; i++) {
            float[] r = modRowRect[i];
            Module m = modules.get(i);
            float prox = Math.max(0f, 1f - Math.abs(sp - i));
            // text pulled in by ROW_PILL_M so it sits inside the pill (which aligns with the tab band)
            GlassWidgets.label(Lang.module(m.name), px0 + PAD + ROW_PILL_M, (r[1] + r[3]) / 2f - GlassWidgets.fontH() / 2f,
                    lerpRGB(0xE0E0E6, 0xFFFFFF, prox), pa);
            moduleToggles.get(i).draw(mouseX, mouseY, pt, pa);
        }
        GlassWidgets.endScissor();

        // detail (page content at pa; module-specific detail at da)
        if (selected != null) {
            GlassWidgets.label(Lang.module(selected.name), detailX0,
                    py0 + PAD + TAB_H / 2f - GlassWidgets.fontH() / 2f, 0xF5F5F7, da);
            // shared header chips persist (drawn at pa) so they don't flicker when the module changes...
            drawChip("重置全部", resetAllRect, mouseX, mouseY, pa, 0xFFFFFF, CHIP_RESET);
            drawChip("編輯 HUD", editHudRect, mouseX, mouseY, pa, 0x0A84FF, CHIP_EDIT_HUD);
            // ...but 編輯排版 is module-specific, so it fades in/out with the detail (da).
            if (selected instanceof LayoutEditable) drawChip("編輯排版", layoutRect, mouseX, mouseY, da, 0x30D158, CHIP_LAYOUT);

            GlassWidgets.beginScissor(detailX0, setY0, px1 - PAD, setY1);
            for (int i = 0; i < settingWidgets.size(); i++) {
                Setting s = shownSettings.get(i);
                Widget w = settingWidgets.get(i);
                float cy = (w.y0 + w.y1) / 2f;
                GlassWidgets.label(Lang.setting(s.name), detailX0, cy - GlassWidgets.fontH() / 2f, 0xC7C7CC, da);
                w.draw(mouseX, mouseY, pt, da);
            }
            GlassWidgets.endScissor();

            // footer: per-module toggle-key chip
            String mk = "開關: " + keyName(KeybindHandler.bindingFor(selected.name) == null ? 0 : KeybindHandler.bindingFor(selected.name).getKeyCode());
            drawChip(mk, modKeyRect, mouseX, mouseY, pa, 0xFFFFFF, CHIP_MOD_KEY);
        }
        // footer: menu-open key chip (always present)
        String menu = "選單鍵: " + keyName(S1mp1eConfig.getMenuKey());
        drawChip(menu, menuKeyRect, mouseX, mouseY, a, 0xFFFFFF, CHIP_MENU_KEY);

        // iOS-26 scroll-edge: re-grab the finished UI, then lay a progressive blur + faint dim
        // over each list's top/bottom, each fading in with how far that end can still scroll.
        SceneCapture.forceGrab();
        final float EXT = 28f;
        float modMax = Math.max(0f, modules.size() * ROW_MOD - (listY1 - listY0));
        GlassWidgets.scrollEdges(px0 + HAIR, listY0, railX1 - HAIR, listY1, EXT,
                                 modScroll / EXT, (modMax - modScroll) / EXT, a);
        if (selected != null) {
            float setMax = Math.max(0f, settingWidgets.size() * ROW_SET - (setY1 - setY0));
            GlassWidgets.scrollEdges(detailX0, setY0, px1 - PAD, setY1, EXT,
                                     setScroll / EXT, (setMax - setScroll) / EXT, a);
        }

        // widget overlays (colour-picker popup) LAST + unclipped, so they sit on top.
        if (selected != null) for (int i = 0; i < settingWidgets.size(); i++) settingWidgets.get(i).drawOverlay(mouseX, mouseY, da);

        if (captureTarget != null) {
            GlassWidgets.drawRect(px0, py0, px1, py1, (Math.round(a * 0.55f * 255f) << 24) | 0x000000);
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

    // sub-pixel pointer position in scaled-GUI coordinates (Mouse origin is bottom-left, physical px)
    private double pmx() { return Mouse.getX() * (double) this.width / this.mc.displayWidth; }
    private double pmy() { return this.height - Mouse.getY() * (double) this.height / this.mc.displayHeight; }

    @Override
    protected void mouseClicked(int mx, int my, int btn) throws IOException {
        try { routeClick(mx, my, btn); }
        finally { for (Widget w : settingWidgets) w.clickDispatched(); }
    }

    private void routeClick(int mx, int my, int btn) {
        layout();
        double mxd = pmx(), myd = pmy();

        // Commit/close any text-editing widget (slider type-in) when clicking off it.
        for (Widget w : settingWidgets)
            if (w.editing() && !w.captures() && !GlassWidgets.inside(mx, my, w.x0, w.y0, w.x1, w.y1)) w.loseFocus();
        // A capturing widget (open colour popup) gets first refusal.
        for (Widget w : settingWidgets) if (w.captures()) { w.mouseClickedPrecise(mxd, myd, btn); return; }
        for (Widget w : moduleToggles) if (w.captures()) { w.mouseClickedPrecise(mxd, myd, btn); return; }

        if (captureTarget != null) return;   // waiting for a key

        // tabs: slide the highlight now, but fade the page out first (drawScreen swaps once faded)
        for (int i = 0; i < 3; i++) if (hit(tabRect[i], mx, my)) {
            if (i != tab && pendingTab != i) { tabSlide.to(i); pendingTab = i; pageFade.to(0f); }
            return;
        }

        // module rows: toggle first, else select
        if (GlassWidgets.inside(mx, my, px0, listY0, railX1, listY1)) {
            int rows = Math.min(modules.size(), modRowRect.length);
            for (int i = 0; i < rows; i++) {
                if (moduleToggles.get(i).mouseClickedPrecise(mxd, myd, btn)) return;
                if (hit(modRowRect[i], mx, my)) { selectModule(modules.get(i)); return; }
            }
            return;
        }

        if (selected != null) {
            if (selected instanceof LayoutEditable && hit(layoutRect, mx, my)) {
                mc.displayGuiScreen(((LayoutEditable) selected).openLayoutEditor()); return;
            }
            if (hit(editHudRect, mx, my)) { mc.displayGuiScreen(new S1mp1eHudEditScreen()); return; }
            if (hit(resetAllRect, mx, my)) { for (Setting s : selected.settings) s.reset(); S1mp1eConfig.save(); return; }
            if (hit(modKeyRect, mx, my)) { captureTarget = selected; return; }
            // settings region: route to the widgets (precise)
            if (GlassWidgets.inside(mx, my, detailX0, setY0, px1 - PAD, setY1)) {
                for (Widget w : settingWidgets) if (w.mouseClickedPrecise(mxd, myd, btn)) return;
                return;
            }
        }
        if (hit(menuKeyRect, mx, my)) { captureTarget = MENU_TARGET; return; }
    }

    private boolean hit(float[] r, int mx, int my) { return GlassWidgets.inside(mx, my, r[0], r[1], r[2], r[3]); }

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
        double mxd = pmx(), myd = pmy();
        for (Widget w : settingWidgets) w.mouseDraggedPrecise(mxd, myd);
        for (Widget w : moduleToggles) w.mouseDraggedPrecise(mxd, myd);
    }

    @Override
    protected void mouseReleased(int mx, int my, int state) {
        for (Widget w : settingWidgets) w.mouseReleased(state);
        for (Widget w : moduleToggles) w.mouseReleased(state);
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
        } else if (selected != null) {
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
        for (Widget w : settingWidgets) if (w.editing() && w.keyTyped(ch, code)) return;
        if (code == Keyboard.KEY_ESCAPE || code == S1mp1eConfig.getMenuKey()) { requestClose(); return; }
        super.keyTyped(ch, code);
    }

    /** 1.8.9 has no {@code GuiScreen.close()} — start the fade-out here; drawScreen() dismisses
     *  once openFade reaches 0. ESC and the menu key both route through this. */
    private void requestClose() {
        if (closing) return;
        // With the cross-dissolve available, switch immediately and let it carry the close (fading ourselves out
        // first would drop to the bare dimmed world and then snap to the game) — mc1144's onClose rule.
        if (dev.s1mp1e.glass.render.ScreenDissolve.canDissolve()) {
            S1mp1eConfig.save();
            mc.displayGuiScreen(null);
            return;
        }
        closing = true;
        openFade.to(0f);
        S1mp1eConfig.save();
    }

    @Override public void onGuiClosed() { S1mp1eConfig.save(); }
}
