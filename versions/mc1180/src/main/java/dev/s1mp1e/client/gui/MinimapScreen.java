package dev.s1mp1e.client.gui;

import dev.s1mp1e.client.minimap.MinimapHud;
import dev.s1mp1e.client.module.MinimapModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

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
    private long openedAt, closingAt;
    private long lastFrame;
    private double[] cursorWorld;

    public MinimapScreen() { super(new LiteralText("地圖")); }

    @Override protected void init() { if (openedAt == 0L) openedAt = System.nanoTime(); }

    @Override public boolean shouldPause() { return false; }

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
        targetLogPpb = MathHelper.clamp(targetLogPpb, minLog, Math.log(MAX_PPB));
        logPpb = MathHelper.clamp(logPpb, minLog, Math.log(MAX_PPB));
        double ppb = Math.exp(logPpb), half = MinimapHud.loadedHalfBlocks();
        double maxX = Math.max(0.0, half - halfW() / ppb), maxZ = Math.max(0.0, half - halfH() / ppb);
        panX = MathHelper.clamp(panX, -maxX, maxX);
        panZ = MathHelper.clamp(panZ, -maxZ, maxZ);
    }

    private float fade(long now) {
        float in = MathHelper.clamp((now - openedAt) / 160_000_000F, 0F, 1F);
        float out = closingAt == 0L ? 1F : 1F - MathHelper.clamp((now - closingAt) / 120_000_000F, 0F, 1F);
        float t = Math.min(in, out);
        return t * t * (3F - 2F * t);
    }

    // ---- render ------------------------------------------------------------------------------------------------

    @Override
    public void render(MatrixStack g, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        if (closingAt != 0L && now - closingAt > 120_000_000L) { super.close(); return; }
        // the usual in-world screen background (1.20.1: the dark gradient): the HUD behind the sheet stays out of the way
        renderBackground(g);
        double dt = lastFrame == 0L ? 1.0 / 60.0 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) { super.close(); return; }

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
        float pt = mc.getTickDelta();
        double px = MathHelper.lerp(pt, p.prevX, p.getX()), pz = MathHelper.lerp(pt, p.prevZ, p.getZ());
        cursorWorld = MinimapHud.renderFullscreen(g, x0(), y0(), x1(), y1(), Math.exp(logPpb),
                px + panX, pz + panZ, fade, pt, mouseX, mouseY);

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
    public boolean mouseScrolled(double mx, double my, double scrollY) {
        targetLogPpb += scrollY * Math.log(1.25);
        clampView();
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 1) { recenter(); return true; }
        if (button == 0) { dragging = true; return true; }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!dragging) return super.mouseDragged(mx, my, button, dx, dy);
        double ppb = Math.exp(logPpb);
        panX -= dx / ppb;
        panZ -= dy / ppb;
        clampView();
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) dragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        MinimapModule m = MinimapModule.get();
        if (m != null && m.fullscreenKey.intValue > 0 && keyCode == m.fullscreenKey.intValue) { close(); return true; }
        if (keyCode == GLFW.GLFW_KEY_C) { recenter(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);   // Esc closes through close()
    }

    private void recenter() { panX = 0.0; panZ = 0.0; }

    @Override
    public void close() {
        if (closingAt != 0L) return;
        savedLogPpb = targetLogPpb;
        closingAt = System.nanoTime();
    }
}
