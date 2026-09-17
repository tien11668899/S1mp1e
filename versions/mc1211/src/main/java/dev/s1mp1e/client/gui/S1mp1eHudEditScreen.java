package dev.s1mp1e.client.gui;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudBoundsProvider;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.S1mp1eConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Live drag-to-position editor for the HUD modules. The real HUD keeps painting behind it
 * (the HudRenderCallback fires in-world regardless of an open screen, and only gates on
 * player == null), and every coordinate space (HUD render, this Screen's render, the mouse
 * handler args, hudX/hudY) is scaled-GUI pixels — so a box at (hudX,hudY,hudW,hudH) overlays
 * the element exactly. 1.21.1 core-profile port of the 1.8.9 editor; drag/snap logic is in
 * {@link HudElement} (unchanged).
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
    public S1mp1eHudEditScreen(List<HudBounds> only) { super(Text.literal("HUD")); this.only = only; }

    @Override public boolean shouldPause() { return false; }   // keep the world + HUD live

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
        if (MinecraftClient.getInstance().player == null) MinecraftClient.getInstance().setScreen(null);
    }

    private void layout() {
        float y = height - 26f;
        gridRect[0] = width / 2f - 84f; gridRect[1] = y; gridRect[2] = width / 2f - 6f; gridRect[3] = y + 18f;
        resetRect[0] = width / 2f + 6f; resetRect[1] = y; resetRect[2] = width / 2f + 84f; resetRect[3] = y + 18f;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        layout();
        // Light scrim only (NOT renderBackground — its blur/darkening would hide the HUD
        // you're positioning).
        GlassWidgets.fill(ctx, 0, 0, this.width, this.height, 0x33000000);

        if (MinecraftClient.getInstance().getDebugHud().shouldShowDebugHud()) {
            String h = "關閉 F3 才能編輯 HUD";
            GlassWidgets.label(ctx, h, (this.width - GlassWidgets.strW(h)) / 2f, this.height / 2f, 0xFFD60A, 1f);
        }

        for (HudElement e : elements) {
            float x0 = e.x(), y0 = e.y(), x1 = x0 + e.w(), y1 = y0 + e.h();
            boolean hot = e == dragging || GlassWidgets.inside(mouseX, mouseY, x0, y0, x1, y1);
            GlassWidgets.capsule(ctx, x0 - 2, y0 - 2, x1 + 2, y1 + 2, 0.2f, hot ? 0.7f : 0.3f, 1f, true);
            GlassWidgets.border(ctx, x0 - 2, y0 - 2, x1 + 2, y1 + 2, hot ? 0xFF0A84FF : 0x66FFFFFF);
            GlassWidgets.label(ctx, e.label(), x0, y0 - GlassWidgets.fontH() - 2, hot ? 0xFFFFFF : 0xB0B0B8, 1f);
        }

        // active snap guides while dragging
        if (dragging != null) {
            if (dragging.guideVX >= 0) GlassWidgets.fill(ctx, dragging.guideVX, 0, dragging.guideVX + 1, this.height, 0x880A84FF);
            if (dragging.guideHY >= 0) GlassWidgets.fill(ctx, 0, dragging.guideHY, this.width, dragging.guideHY + 1, 0x880A84FF);
        }

        // toolbar
        drawChip(ctx, grid ? "格線: 開" : "格線: 關", gridRect, mouseX, mouseY);
        drawChip(ctx, "重置全部", resetRect, mouseX, mouseY);
        String hint = "拖曳定位 · 邊緣/中心自動吸附 · ESC 完成";
        GlassWidgets.label(ctx, hint, (this.width - GlassWidgets.strW(hint)) / 2f, this.height - 40f, 0xC7C7CC, 1f);
    }

    private void drawChip(DrawContext ctx, String text, float[] r, int mx, int my) {
        boolean hover = GlassWidgets.inside(mx, my, r[0], r[1], r[2], r[3]);
        GlassWidgets.capsule(ctx, r[0], r[1], r[2], r[3], 0.5f, hover ? 0.7f : 0.3f, 1f, true);
        GlassWidgets.label(ctx, text, r[0] + (r[2] - r[0] - GlassWidgets.strW(text)) / 2f,
                (r[1] + r[3]) / 2f - GlassWidgets.fontH() / 2f, 0xF5F5F7, 1f);
    }

    @Override
    public boolean mouseClicked(double mxd, double myd, int btn) {
        int mx = (int) mxd, my = (int) myd;
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
        return super.mouseClicked(mxd, myd, btn);
    }

    @Override
    public boolean mouseDragged(double mxd, double myd, int btn, double dx, double dy) {
        if (dragging != null) {
            dragging.moveTo((int) mxd - grabX, (int) myd - grabY, this.width, this.height, elements, grid, 6);
            return true;
        }
        return super.mouseDragged(mxd, myd, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mxd, double myd, int btn) {
        if (dragging != null) {
            dragging.guideVX = dragging.guideHY = -1;
            S1mp1eConfig.save();
            dragging = null;
            return true;
        }
        return super.mouseReleased(mxd, myd, btn);
    }

    @Override
    public void close() {
        S1mp1eConfig.save();
        MinecraftClient.getInstance().setScreen(new S1mp1eConfigScreen());
    }
}
