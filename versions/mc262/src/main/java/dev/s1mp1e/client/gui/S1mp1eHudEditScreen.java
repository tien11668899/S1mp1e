package dev.s1mp1e.client.gui;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudBoundsProvider;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.S1mp1eConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Live drag-to-position editor for the HUD modules. The real HUD keeps painting behind it
 * (26.2's {@code Gui.extractRenderState} extracts the {@code Hud} — and so {@code HudDriverMixin} — every
 * in-world frame even with a screen open), and every coordinate space (HUD extract, this Screen's extract,
 * the mouse event coords, hudX/hudY) is scaled-GUI pixels — so a box at (hudX,hudY,hudW,hudH) overlays the
 * element exactly. 26.2 port of the mc1211 editor; drag/snap logic is in {@link HudElement} (unchanged).
 */
public final class S1mp1eHudEditScreen extends Screen {

    private final List<HudElement> elements = new ArrayList<HudElement>();
    private HudElement dragging;
    private int grabX, grabY;
    private boolean grid;
    private final float[] gridRect = new float[4], resetRect = new float[4];
    /** When non-null, edit ONLY these elements (e.g. one module's per-key boxes); else all HUD modules. */
    private final List<HudBounds> only;

    public S1mp1eHudEditScreen() { this(null); }
    public S1mp1eHudEditScreen(List<HudBounds> only) { super(Component.literal("HUD")); this.only = only; }

    @Override public boolean isPauseScreen() { return false; }   // keep the world + HUD live

    @Override
    protected void init() {
        elements.clear();
        if (only != null) {                                       // scoped: one module's own sub-elements
            for (HudBounds b : only) elements.add(new HudElement(b));
        } else {
            for (Module m : ModuleManager.all()) {
                if (!m.enabled) continue;
                if (m instanceof HudBoundsProvider) {             // a provider → one box per sub-element
                    for (HudBounds b : ((HudBoundsProvider) m).hudBoundsElements()) elements.add(new HudElement(b));
                } else if (m instanceof HudBounds) {
                    elements.add(new HudElement((HudBounds) m));
                }
            }
        }
    }

    /** No player → nothing to edit. (mc1211 did this inside init(); 26.2's Gui.setScreen is not re-entrancy safe
     *  from inside init, so it runs on the next tick instead.) */
    @Override
    public void tick() {
        if (this.minecraft.player == null) this.minecraft.gui.setScreen(null);
    }

    private void layout() {
        float y = height - 26f;
        gridRect[0] = width / 2f - 84f; gridRect[1] = y; gridRect[2] = width / 2f - 6f; gridRect[3] = y + 18f;
        resetRect[0] = width / 2f + 6f; resetRect[1] = y; resetRect[2] = width / 2f + 84f; resetRect[3] = y + 18f;
    }

    /** Light scrim only — NOT the vanilla blurred/menu background, which would hide the HUD you're positioning. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        GlassWidgets.fill(g, 0, 0, this.width, this.height, 0x33000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        GlassWidgets.resetScissorMirror();
        layout();

        if (this.minecraft.getDebugOverlay().showDebugScreen()) {
            String h = "關閉 F3 才能編輯 HUD";
            GlassWidgets.label(g, h, (this.width - GlassWidgets.strW(h)) / 2f, this.height / 2f, 0xFFD60A, 1f);
        }

        for (HudElement e : elements) {
            float x0 = e.x(), y0 = e.y(), x1 = x0 + e.w(), y1 = y0 + e.h();
            boolean hot = e == dragging || GlassWidgets.inside(mouseX, mouseY, x0, y0, x1, y1);
            GlassWidgets.capsule(g, x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0.2f, hot ? 0.7f : 0.3f, 1f, true);
            GlassWidgets.border(g, x0 - 2, y0 - 2, x1 + 2, y1 + 2, hot ? 0xFF0A84FF : 0x66FFFFFF);
            GlassWidgets.label(g, e.label(), x0, y0 - GlassWidgets.fontH() - 2, hot ? 0xFFFFFF : 0xB0B0B8, 1f);
        }

        // active snap guides while dragging
        if (dragging != null) {
            if (dragging.guideVX >= 0) GlassWidgets.fill(g, dragging.guideVX, 0, dragging.guideVX + 1, this.height, 0x880A84FF);
            if (dragging.guideHY >= 0) GlassWidgets.fill(g, 0, dragging.guideHY, this.width, dragging.guideHY + 1, 0x880A84FF);
        }

        // toolbar
        drawChip(g, grid ? "格線: 開" : "格線: 關", gridRect, mouseX, mouseY);
        drawChip(g, "重置全部", resetRect, mouseX, mouseY);
        String hint = "拖曳定位 · 邊緣/中心自動吸附 · ESC 完成";
        GlassWidgets.label(g, hint, (this.width - GlassWidgets.strW(hint)) / 2f, this.height - 40f, 0xC7C7CC, 1f);
    }

    private void drawChip(GuiGraphicsExtractor g, String text, float[] r, int mx, int my) {
        boolean hover = GlassWidgets.inside(mx, my, r[0], r[1], r[2], r[3]);
        GlassWidgets.capsule(g, r[0], r[1], r[2], r[3], 0.5f, hover ? 0.7f : 0.3f, 1f, true);
        GlassWidgets.label(g, text, r[0] + (r[2] - r[0] - GlassWidgets.strW(text)) / 2f,
                (r[1] + r[3]) / 2f - GlassWidgets.fontH() / 2f, 0xF5F5F7, 1f);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mx = (int) event.x(), my = (int) event.y();
        layout();
        if (GlassWidgets.inside(mx, my, gridRect[0], gridRect[1], gridRect[2], gridRect[3])) { grid = !grid; return true; }
        if (GlassWidgets.inside(mx, my, resetRect[0], resetRect[1], resetRect[2], resetRect[3])) {
            for (HudElement e : elements) e.reset(); return true;
        }
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement e = elements.get(i);
            if (GlassWidgets.inside(mx, my, e.x() - 2, e.y() - 2, e.x() + e.w() + 2, e.y() + e.h() + 2)) {
                dragging = e; grabX = mx - e.x(); grabY = my - e.y(); return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging != null) {
            dragging.moveTo((int) event.x() - grabX, (int) event.y() - grabY, this.width, this.height, elements, grid, 6);
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging != null) {
            dragging.guideVX = dragging.guideHY = -1;
            S1mp1eConfig.save();
            dragging = null;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        S1mp1eConfig.save();
        this.minecraft.gui.setScreen(new S1mp1eConfigScreen());
    }
}
