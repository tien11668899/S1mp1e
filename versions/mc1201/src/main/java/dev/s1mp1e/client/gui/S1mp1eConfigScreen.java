package dev.s1mp1e.client.gui;

import dev.s1mp1e.client.LayoutEditable;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.S1mp1eConfig;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.widget.ToggleWidget;
import dev.s1mp1e.client.gui.widget.Widget;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game liquid-glass config screen for 1.21.1 (core-profile). Rounded fills go through
 * the ROUND/BTN batch programs (true AA corners, no backdrop sampling → no flicker) over
 * vanilla's blurred screen background; lists are scissor-clipped to the panel and scroll
 * with exponential smoothing. Colour-picker, HUD editor, PingFang and slider text-input
 * are the remaining passes.
 */
public final class S1mp1eConfigScreen extends Screen {

    private static final String[] TABS = { "Combat", "HUD", "Visual" };
    private static final String[] TAB_LABELS = { "戰鬥", "HUD", "視覺" };

    // --- Apple-style 4pt spacing grid: every inset/gap/size is a multiple of GRID ---
    private static final float GRID = 4f;    // base unit
    private static final float PAD  = 12f;   // 3·GRID  content inset from a panel/column edge
    private static final float GAP  = 8f;    // 2·GRID  gap between sibling sections
    private static final float HAIR = 4f;    // 1·GRID  fine inset (row edge / hairline offset)
    private static final float RAIL_W    = 160f; // 40·GRID  left module-rail width
    private static final float TAB_H     = 28f;  // 7·GRID
    private static final float CHIP_H    = 18f;   // header chips (smaller so the header spacing reads right)
    private static final float LABEL_COL = 96f;  // 24·GRID  setting-name column width
    private static final float ROW_MOD = 32f, ROW_SET = 28f;   // 8·GRID / 7·GRID
    private static final float TOGGLE_W = 32f, TOGGLE_H = 18f;  // track (lozenge knob) — sized to the label text
    private static final float ROW_PILL_M = 6f;                // module-row highlight margin (uniform L/R + inter-row gap)
    private static final float TAB_PILL_H = 20f;               // highlight pill height (centred in the 28px tab row)

    private final Fade openFade = new Fade(0f, 150f);
    private final Fade pageFade = new Fade(1f, 130f);   // content cross-fade on tab switch
    private final Fade detailFade = new Fade(1f, 120f); // detail cross-fade on MODULE switch (name + settings + 編輯排版)
    private Module pendingSelect = null;                // module to select once the detail has faded out
    private final Anim tabSlide = new Anim(0f);
    private final Anim selSlide = new Anim(0f);
    private int tab = 0;
    private int pendingTab = -1;                        // tab to apply once the page has faded out
    private boolean closing;                            // close fade-out in progress
    private final List<Module> modules = new ArrayList<Module>();
    private final List<ToggleWidget> moduleToggles = new ArrayList<ToggleWidget>();
    private Module selected;
    private final List<Widget> settingWidgets = new ArrayList<Widget>();
    /** The settings that actually have a visible row, parallel to {@link #settingWidgets} (hidden
     *  settings are skipped, so this is NOT the same as {@code selected.settings}). */
    private final List<Setting> shownSettings = new ArrayList<Setting>();
    private float modScroll, setScroll, modScrollTarget, setScrollTarget;
    private long lastScrollNanos;

    private float px0, py0, px1, py1, railX1, listY0, listY1, detailX0, setY0, setY1;
    private final float[][] tabRect = new float[3][4];
    private final float[][] modRowRect = new float[64][4];
    private final float[] resetAllRect = new float[4];
    private final float[] editHudRect  = new float[4];
    private final float[] layoutRect   = new float[4];   // "編輯排版" — only for LayoutEditable modules

    public S1mp1eConfigScreen() { super(Text.literal("S1mp1e")); }

    @Override protected void init() { openFade.snap(0f); openFade.to(1f); rebuildTab(); }
    @Override public boolean shouldPause() { return true; }

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
            pendingSelect = m; detailFade.to(0f);        // fade the detail out; render() swaps once faded
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

    private void easeScroll() {
        long now = System.nanoTime();
        float dt = lastScrollNanos == 0L ? 0f : (now - lastScrollNanos) / 1.0e9f;
        lastScrollNanos = now;
        if (dt > 0.1f) dt = 0.1f;
        float k = 1f - (float) Math.exp(-dt / 0.09f);
        modScroll += (modScrollTarget - modScroll) * k;
        setScroll += (setScrollTarget - setScroll) * k;
        if (Math.abs(modScrollTarget - modScroll) < 0.25f) modScroll = modScrollTarget;
        if (Math.abs(setScrollTarget - setScroll) < 0.25f) setScroll = setScrollTarget;
    }

    private static float clampScroll(float v, float content, float view) {
        float max = Math.max(0f, content - view);
        return v < 0 ? 0 : (v > max ? max : v);
    }

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
        listY0 = tabY + TAB_H + GAP; listY1 = py1 - PAD;
        for (int i = 0; i < modules.size() && i < modRowRect.length; i++) {
            float r0 = listY0 - modScroll + i * ROW_MOD, r1 = r0 + ROW_MOD;   // full pitch cell (content centres at r0+16)
            modRowRect[i][0] = px0 + HAIR; modRowRect[i][1] = r0; modRowRect[i][2] = railX1 - HAIR; modRowRect[i][3] = r1;
            float cy = (r0 + r1) / 2f;
            // toggle pulled in by ROW_PILL_M so its right edge sits inside the highlight pill (aligns with the tab band)
            moduleToggles.get(i).setBounds(railX1 - PAD - ROW_PILL_M - TOGGLE_W, cy - TOGGLE_H / 2f, railX1 - PAD - ROW_PILL_M, cy + TOGGLE_H / 2f);
        }

        detailX0 = railX1 + PAD;
        // detail header row: chips CENTRED in the same TAB_H band as the left tabs (so header + tabs are same height)
        float chipY = py0 + PAD + (TAB_H - CHIP_H) / 2f;
        resetAllRect[0] = px1 - PAD - chipW("重置全部"); resetAllRect[1] = chipY; resetAllRect[2] = px1 - PAD; resetAllRect[3] = chipY + CHIP_H;
        editHudRect[0] = resetAllRect[0] - GAP - chipW("編輯 HUD"); editHudRect[1] = resetAllRect[1]; editHudRect[2] = resetAllRect[0] - GAP; editHudRect[3] = resetAllRect[3];
        layoutRect[0] = editHudRect[0] - GAP - chipW("編輯排版"); layoutRect[1] = editHudRect[1]; layoutRect[2] = editHudRect[0] - GAP; layoutRect[3] = editHudRect[3];
        // detail list starts level with the module list (both = tabs bottom + GAP) so the two dividers line up
        setY0 = py0 + PAD + TAB_H + GAP; setY1 = py1 - PAD;
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
    }

    private float chipW(String s) { return GlassWidgets.strW(s) + 12f; }

    @Override public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Close fade-out: close() starts the fade; once it reaches 0 we actually dismiss.
        if (closing) {
            if (openFade.value() <= 0.02f) { super.close(); return; }
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
        this.renderBackground(ctx);   // 1.20.1: Screen.renderBackground(DrawContext) — single arg
        // Flush vanilla's blur + darkening into the framebuffer, then capture it so the
        // GLASS panel refracts the dimmed background. Deterministic every frame (grabNow,
        // not the time-deduped grab) → the panel never flickers between stages.
        ctx.draw();
        SceneCapture.grabNow();
        easeScroll();
        layout();

        GlassWidgets.panel(ctx, px0, py0, px1, py1, a);
        // a is openFade 0..1 (a FLOAT) — must scale to a 0..255 alpha byte, else
        // round(a*0.15) is 0 for all a and the dividers never render.
        int div = (Math.round(a * 0.15f * 255f) << 24) | 0xFFFFFF;
        GlassWidgets.fill(ctx, railX1, py0 + PAD, railX1 + 1, py1 - PAD, div);            // vertical rail
        GlassWidgets.fill(ctx, px0 + PAD, listY0 - GAP, railX1 - PAD, listY0 - GAP + 1, div); // under tabs
        GlassWidgets.fill(ctx, detailX0, setY0 - GAP, px1 - PAD, setY0 - GAP + 1, div);       // under detail title

        // tabs — sliding highlight (a pill vertically CENTRED in the tab row, not the
        // full row height, so it doesn't sit low against the divider) + label cross-fade
        float tw = tabRect[0][2] - tabRect[0][0];
        float tp = tabSlide.value();
        float hx0 = tabRect[0][0] + tp * tw;
        float tcy = (tabRect[0][1] + tabRect[0][3]) / 2f;
        GlassWidgets.capsule(ctx, hx0 + 2, tcy - TAB_PILL_H / 2f, hx0 + tw - 2, tcy + TAB_PILL_H / 2f, 1f, 0.6f, a, true);
        for (int i = 0; i < 3; i++) {
            float[] r = tabRect[i];
            float prox = Math.max(0f, 1f - Math.abs(tp - i));
            GlassWidgets.label(ctx, TAB_LABELS[i], r[0] + (r[2] - r[0] - GlassWidgets.strW(TAB_LABELS[i])) / 2f,
                    (r[1] + r[3]) / 2f - GlassWidgets.fontH() / 2f, lerpRGB(0x9A9AA0, 0xFFFFFF, prox), a);
        }

        // module list (scissor-clipped, sliding selection highlight) — content at pa so
        // the whole page cross-fades on a tab switch
        ctx.enableScissor((int) px0, (int) listY0, (int) railX1, (int) listY1);
        float sp = selSlide.value();
        if (!modules.isEmpty()) {
            // Pill centred in the pitch cell with a UNIFORM margin: left/right = ROW_PILL_M
            // and inter-row gap = ROW_PILL_M (height = ROW_MOD - M, inset M/2 top+bottom),
            // so the spacing looks the same horizontally and vertically, and it stays
            // centred on the row's label/toggle (cell centre).
            float rowTop = listY0 - modScroll + sp * ROW_MOD;
            float sr0 = rowTop + ROW_PILL_M / 2f, sr1 = rowTop + ROW_MOD - ROW_PILL_M / 2f;
            // pill L/R aligned with the tab band (px0+PAD .. railX1-PAD), same as the tabs above
            GlassWidgets.capsule(ctx, px0 + PAD, sr0, railX1 - PAD, sr1, 0.4f, 0.5f, pa, true);
        }
        int rows = Math.min(modules.size(), modRowRect.length);
        for (int i = 0; i < rows; i++) {
            float[] r = modRowRect[i];
            Module m = modules.get(i);
            float prox = Math.max(0f, 1f - Math.abs(sp - i));
            // text pulled in by ROW_PILL_M so it sits inside the pill (which aligns with the tab band)
            GlassWidgets.label(ctx, Lang.module(m.name), px0 + PAD + ROW_PILL_M, (r[1] + r[3]) / 2f - GlassWidgets.fontH() / 2f,
                    lerpRGB(0xE0E0E6, 0xFFFFFF, prox), pa);
            moduleToggles.get(i).draw(ctx, mouseX, mouseY, delta, pa);
        }
        ctx.disableScissor();

        // detail (page content at pa)
        if (selected != null) {
            GlassWidgets.label(ctx, Lang.module(selected.name), detailX0,
                    py0 + PAD + TAB_H / 2f - GlassWidgets.fontH() / 2f, 0xF5F5F7, da);
            // shared header chips persist (drawn at pa) so they don't flicker when the module changes...
            drawChip(ctx, "重置全部", resetAllRect, mouseX, mouseY, pa, 0xFFFFFF);
            drawChip(ctx, "編輯 HUD", editHudRect, mouseX, mouseY, pa, 0x0A84FF);
            // ...but 編輯排版 is module-specific, so it fades in/out with the detail (da).
            if (selected instanceof LayoutEditable) drawChip(ctx, "編輯排版", layoutRect, mouseX, mouseY, da, 0x30D158);
            ctx.enableScissor((int) detailX0, (int) setY0, (int) (px1 - PAD), (int) setY1);
            for (int i = 0; i < settingWidgets.size(); i++) {
                Setting s = shownSettings.get(i);
                Widget w = settingWidgets.get(i);
                float cy = (w.y0 + w.y1) / 2f;
                GlassWidgets.label(ctx, Lang.setting(s.name), detailX0, cy - GlassWidgets.fontH() / 2f, 0xC7C7CC, da);
                w.draw(ctx, mouseX, mouseY, delta, da);
            }
            ctx.disableScissor();
        }

        // iOS-26 scroll-edge: flush the list content into the framebuffer, grab the
        // composite, then lay the feathered progressive-blur bands over each list's
        // top/bottom. Each end fades in with how far that end can still scroll.
        ctx.draw();
        SceneCapture.grabNow();
        // EXT = 2× the panel corner radius (~14) so edge.fsh's rounded-corner mask
        // (radius = band height / 2) curves the fade's outer corners to match the panel.
        final float EXT = 28f;
        float modMax = Math.max(0f, modules.size() * ROW_MOD - (listY1 - listY0));
        GlassWidgets.scrollEdges(px0 + HAIR, listY0, railX1 - HAIR, listY1, EXT,
                                 modScroll / EXT, (modMax - modScroll) / EXT, a);
        if (selected != null) {
            float setMax = Math.max(0f, settingWidgets.size() * ROW_SET - (setY1 - setY0));
            GlassWidgets.scrollEdges(detailX0, setY0, px1 - PAD, setY1, EXT,
                                     setScroll / EXT, (setMax - setScroll) / EXT, a);
        }

        // widget overlays (colour picker popup) LAST + unclipped, so they sit on top.
        if (selected != null) for (Widget w : settingWidgets) w.drawOverlay(ctx, mouseX, mouseY, da);
    }

    private void drawChip(DrawContext ctx, String text, float[] r, int mx, int my, float a, int rgb) {
        boolean hover = GlassWidgets.inside(mx, my, r[0], r[1], r[2], r[3]);
        GlassWidgets.capsule(ctx, r[0], r[1], r[2], r[3], 0.5f, hover ? 0.7f : 0.3f, a, true);
        GlassWidgets.label(ctx, text, r[0] + (r[2] - r[0] - GlassWidgets.strW(text)) / 2f,
                (r[1] + r[3]) / 2f - GlassWidgets.fontH() / 2f, rgb, a);
    }

    @Override public boolean mouseClicked(double mxd, double myd, int btn) {
        try {
            return s1mp1e$routeClick(mxd, myd, btn);
        } finally {
            for (Widget w : settingWidgets) w.clickDispatched();
        }
    }

    private boolean s1mp1e$routeClick(double mxd, double myd, int btn) {
        int mx = (int) mxd, my = (int) myd;
        layout();
        // Commit/close any text-editing widget (slider type-in) when clicking off it.
        for (Widget w : settingWidgets)
            if (w.editing() && !w.captures() && !GlassWidgets.inside(mx, my, w.x0, w.y0, w.x1, w.y1)) w.loseFocus();
        for (Widget w : settingWidgets) if (w.captures()) { w.mouseClickedPrecise(mxd, myd, btn); return true; }
        // Tab switch: slide the highlight now, but fade the page out first — render()
        // swaps to the new tab once pageFade hits 0, then fades it back in.
        for (int i = 0; i < 3; i++) if (hit(tabRect[i], mx, my)) {
            if (i != tab && pendingTab != i) { tabSlide.to(i); pendingTab = i; pageFade.to(0f); }
            return true;
        }
        if (GlassWidgets.inside(mx, my, px0, listY0, railX1, listY1)) {
            int rows = Math.min(modules.size(), modRowRect.length);
            for (int i = 0; i < rows; i++) {
                if (moduleToggles.get(i).mouseClicked(mx, my, btn)) return true;
                if (hit(modRowRect[i], mx, my)) { selectModule(modules.get(i)); return true; }
            }
            return true;
        }
        if (selected != null) {
            if (selected instanceof LayoutEditable && hit(layoutRect, mx, my)) {
                this.client.setScreen(((LayoutEditable) selected).openLayoutEditor()); return true;
            }
            if (hit(editHudRect, mx, my)) { this.client.setScreen(new S1mp1eHudEditScreen()); return true; }
            if (hit(resetAllRect, mx, my)) { for (Setting s : selected.settings) s.reset(); S1mp1eConfig.save(); return true; }
            if (GlassWidgets.inside(mx, my, detailX0, setY0, px1 - PAD, setY1)) {
                for (Widget w : settingWidgets) if (w.mouseClickedPrecise(mxd, myd, btn)) return true;
                return true;
            }
        }
        return super.mouseClicked(mxd, myd, btn);
    }

    @Override public boolean mouseDragged(double mxd, double myd, int btn, double dx, double dy) {
        for (Widget w : settingWidgets) w.mouseDraggedPrecise(mxd, myd, btn);
        return true;
    }

    @Override public boolean mouseReleased(double mxd, double myd, int btn) {
        for (Widget w : settingWidgets) w.mouseReleased();
        return super.mouseReleased(mxd, myd, btn);
    }

    @Override public boolean mouseScrolled(double mxd, double myd, double amount) {   // 1.20.1: 3-arg (no horizontal)
        int mx = (int) mxd, my = (int) myd;
        float step = (float) (-amount * ROW_SET);
        if (GlassWidgets.inside(mx, my, px0, listY0, railX1, listY1)) {
            modScrollTarget = clampScroll(modScrollTarget + step, modules.size() * ROW_MOD, listY1 - listY0);
        } else if (selected != null) {
            setScrollTarget = clampScroll(setScrollTarget + step, settingWidgets.size() * ROW_SET, setY1 - setY0);
        }
        return true;
    }

    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // A widget in text/picker edit mode gets first dibs (Enter/Esc/Backspace) so ESC
        // closes the editor instead of the whole screen.
        for (Widget w : settingWidgets) if (w.editing() && w.keyPressed(keyCode)) return true;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == S1mp1eConfig.getMenuKey()) { this.close(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override public boolean charTyped(char chr, int modifiers) {
        for (Widget w : settingWidgets) if (w.editing() && w.charTyped(chr)) return true;
        return super.charTyped(chr, modifiers);
    }

    // Don't dismiss instantly — start the fade-out; render() calls super.close() once
    // openFade reaches 0 (the close animation).
    @Override public void close() {
        if (closing) return;
        closing = true;
        openFade.to(0f);
        S1mp1eConfig.save();
    }

    private boolean hit(float[] r, int mx, int my) { return GlassWidgets.inside(mx, my, r[0], r[1], r[2], r[3]); }

    private static int lerpRGB(int c0, int c1, float t) {
        int r = (int) (((c0 >> 16) & 255) + (((c1 >> 16) & 255) - ((c0 >> 16) & 255)) * t);
        int g = (int) (((c0 >> 8) & 255) + (((c1 >> 8) & 255) - ((c0 >> 8) & 255)) * t);
        int b = (int) ((c0 & 255) + ((c1 & 255) - (c0 & 255)) * t);
        return (r << 16) | (g << 8) | b;
    }
}
