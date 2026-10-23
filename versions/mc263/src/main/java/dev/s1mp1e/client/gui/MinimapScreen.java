package dev.s1mp1e.client.gui;

import dev.s1mp1e.client.minimap.MinimapHud;
import dev.s1mp1e.client.module.MinimapModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The full-screen glass map, opened with the minimap's key (M). North up, centred on the player; the mouse wheel zooms
 * around the cursor, dragging pans, right-click (or C) re-centres, M / Esc closes. It shows what this client has loaded
 * (the render-distance window the minimap texture holds): zoom and pan are kept inside that window, so the view never
 * runs past the edge of the data. The game keeps running behind it (not a pause screen), like the inventory.
 */
public final class MinimapScreen extends Screen {
    private static final double MAX_PPB = 8.0;
    /** Zoom kept across openings (log pixels per block), so the map reopens the way it was left. */
    private static double savedLogPpb = Math.log(2.0);

    private double logPpb = savedLogPpb, targetLogPpb = savedLogPpb;
    /** View centre offset from the player, in blocks (follows the player while it is 0). */
    private double panX, panZ;
    private boolean dragging;
    private double dragLastX, dragLastY;
    private long openedAt, closingAt;
    private long lastFrame;
    private double[] cursorWorld;

    public MinimapScreen() { super(Component.literal("地圖")); }

    @Override protected void init() { if (openedAt == 0L) openedAt = System.nanoTime(); }

    @Override public boolean isPauseScreen() { return false; }

    // ---- geometry ----------------------------------------------------------------------------------------------

    private float x0() { return 16F; }
    private float y0() { return 16F; }
    private float x1() { return width - 16F; }
    private float y1() { return height - 30F; }
    private float halfW() { return (x1() - x0()) * 0.5F - 6F; }
    private float halfH() { return (y1() - y0()) * 0.5F - 6F; }

    /** Smallest zoom at which the whole map rectangle still lies inside the loaded window. */
    private double minLogPpb() {
        double half = MinimapHud.loadedHalfBlocks();
        return Math.log(Math.max(halfW(), halfH()) / Math.max(16.0, half));
    }

    private void clampView() {
        double minLog = minLogPpb();
        targetLogPpb = Mth.clamp(targetLogPpb, minLog, Math.log(MAX_PPB));
        logPpb = Mth.clamp(logPpb, minLog, Math.log(MAX_PPB));
        double ppb = Math.exp(logPpb), half = MinimapHud.loadedHalfBlocks();
        double maxX = Math.max(0.0, half - halfW() / ppb), maxZ = Math.max(0.0, half - halfH() / ppb);
        panX = Mth.clamp(panX, -maxX, maxX);
        panZ = Mth.clamp(panZ, -maxZ, maxZ);
    }

    private float fade(long now) {
        float in = Mth.clamp((now - openedAt) / 160_000_000F, 0F, 1F);
        float out = closingAt == 0L ? 1F : 1F - Mth.clamp((now - closingAt) / 120_000_000F, 0F, 1F);
        float t = Math.min(in, out);
        return t * t * (3F - 2F * t);
    }

    // ---- render ------------------------------------------------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        if (closingAt != 0L && now - closingAt > 120_000_000L) { super.onClose(); return; }
        double dt = lastFrame == 0L ? 1.0 / 60.0 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { super.onClose(); return; }

        // ease the zoom toward the wheel target, keeping the world point under the cursor still
        double before = Math.exp(logPpb);
        logPpb += (targetLogPpb - logPpb) * (1.0 - Math.exp(-dt * 14.0));
        if (Math.abs(targetLogPpb - logPpb) < 1e-4) logPpb = targetLogPpb;
        double after = Math.exp(logPpb);
        if (after != before && !dragging) {
            float cxs = (x0() + x1()) * 0.5F, cys = (y0() + y1()) * 0.5F;
            panX += (mouseX - cxs) * (1.0 / before - 1.0 / after);
            panZ += (mouseY - cys) * (1.0 / before - 1.0 / after);
        }
        clampView();

        float fade = fade(now);
        float pt = delta;
        double px = Mth.lerp(pt, p.xo, p.getX()), pz = Mth.lerp(pt, p.zo, p.getZ());
        float s = 0.96F + 0.04F * fade;   // a slight grow-in, like an iOS sheet
        float cx = width * 0.5F, cy = height * 0.5F;
        g.pose().pushMatrix();
        try {
            g.pose().translate(cx, cy);
            g.pose().scale(s, s);
            g.pose().translate(-cx, -cy);
            cursorWorld = MinimapHud.renderFullscreen(g, font, x0(), y0(), x1(), y1(), Math.exp(logPpb),
                    px + panX, pz + panZ, fade, pt, mouseX, mouseY);
        } finally {
            g.pose().popMatrix();
        }

        // ---- footer: where the cursor points, and the controls ----
        int a = Math.round(fade * 255F);
        if (a > 8) {
            String where = cursorWorld != null && mouseX >= x0() && mouseX <= x1() && mouseY >= y0() && mouseY <= y1()
                    ? String.format("X %d   Z %d", (int) Math.floor(cursorWorld[0]), (int) Math.floor(cursorWorld[1]))
                    : String.format("X %d   Z %d", (int) Math.floor(px), (int) Math.floor(pz));
            String hint = "滾輪縮放 · 拖曳移動 · 右鍵回到自己 · M / Esc 關閉";
            float ty = height - 21F;
            GlassWidgets.label(g, where, x0() + 4F, ty, 0xF5F5F7, fade);
            GlassWidgets.label(g, hint, x1() - 4F - GlassWidgets.strW(hint), ty, 0xC7C7CC, fade);
        }
    }

    // ---- input -------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        targetLogPpb += scrollY * Math.log(1.25);
        clampView();
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 1) { recenter(); return true; }
        if (event.button() == 0) { dragging = true; dragLastX = event.x(); dragLastY = event.y(); return true; }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (!dragging) return super.mouseDragged(event, dx, dy);
        double ppb = Math.exp(logPpb);
        panX -= (event.x() - dragLastX) / ppb;
        panZ -= (event.y() - dragLastY) / ppb;
        dragLastX = event.x();
        dragLastY = event.y();
        clampView();
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) dragging = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        MinimapModule m = MinimapModule.get();
        if (m != null && m.fullscreenKey.intValue > 0 && event.key() == m.fullscreenKey.intValue) { onClose(); return true; }
        if (event.key() == InputKeys.KEY_C) { recenter(); return true; }
        return super.keyPressed(event);   // Esc closes through onClose
    }

    private void recenter() { panX = 0.0; panZ = 0.0; }

    @Override
    public void onClose() {
        if (closingAt != 0L) return;
        savedLogPpb = targetLogPpb;
        closingAt = System.nanoTime();
    }

    /** Key codes this screen compares against KeyEvent.key() (SDL scancodes on 26.3). */
    private static final class InputKeys {
        static final int KEY_C = 6;   // SDL_SCANCODE_C
    }
}
