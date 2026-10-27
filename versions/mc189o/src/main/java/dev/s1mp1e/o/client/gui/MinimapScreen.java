package dev.s1mp1e.o.client.gui;

import dev.s1mp1e.o.client.KeyCodes;
import dev.s1mp1e.o.client.module.MinimapModule;
import dev.s1mp1e.o.minimap.MinimapHud;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * The full-screen glass map, opened with the minimap's key (M). North up, centred on the player; the mouse wheel zooms
 * around the cursor, dragging pans, right-click (or C) re-centres, M / Esc closes. It shows what this client has loaded
 * (the render-distance window the minimap texture holds): zoom and pan are kept inside that window, so the view never
 * runs past the edge of the data. The game keeps running behind it (not a pause screen), like the inventory.
 *
 * <p>1.8.9 port of mc1211's MinimapScreen: 1.8.9 screens get integer mouse positions and no drag delta or close(), so
 * the pointer is read at sub-pixel precision from {@link Mouse} (as {@link KnifeLockerScreen} does), the drag delta is
 * tracked here, and closing fades out before the screen is dismissed from render().
 */
public final class MinimapScreen extends Screen {
    private static final double MAX_PPB = 8.0;
    /** Zoom kept across openings (log pixels per block), so the map reopens the way it was left. */
    private static double savedLogPpb = Math.log(2.0);

    private double logPpb = savedLogPpb, targetLogPpb = savedLogPpb;
    /** View centre offset from the player, in blocks (follows the player while it is 0). */
    private double panX, panZ;
    private boolean dragging;
    private double dragX, dragY;
    private long openedAt, closingAt;
    private long lastFrame;
    private double[] cursorWorld;

    @Override public void init() { if (openedAt == 0L) openedAt = System.nanoTime(); }

    @Override public boolean shouldPauseGame() { return false; }

    // ---- geometry ----------------------------------------------------------------------------------------------

    private float x0() { return 16F; }
    private float y0() { return 16F; }
    private float x1() { return width - 16F; }
    private float y1() { return height - 30F; }
    private float halfW() { return (x1() - x0()) * 0.5F - 6F; }
    private float halfH() { return (y1() - y0()) * 0.5F - 6F; }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Smallest zoom at which the whole map rectangle still lies inside the loaded window. */
    private double minLogPpb() {
        double half = MinimapHud.loadedHalfBlocks();
        return Math.log(Math.max(halfW(), halfH()) / Math.max(16.0, half));
    }

    private void clampView() {
        double minLog = minLogPpb();
        targetLogPpb = clamp(targetLogPpb, minLog, Math.log(MAX_PPB));
        logPpb = clamp(logPpb, minLog, Math.log(MAX_PPB));
        double ppb = Math.exp(logPpb), half = MinimapHud.loadedHalfBlocks();
        double maxX = Math.max(0.0, half - halfW() / ppb), maxZ = Math.max(0.0, half - halfH() / ppb);
        panX = clamp(panX, -maxX, maxX);
        panZ = clamp(panZ, -maxZ, maxZ);
    }

    private float fade(long now) {
        float in = (float) clamp((now - openedAt) / 160_000_000.0, 0.0, 1.0);
        float out = closingAt == 0L ? 1F : 1F - (float) clamp((now - closingAt) / 120_000_000.0, 0.0, 1.0);
        float t = Math.min(in, out);
        return t * t * (3F - 2F * t);
    }

    // sub-pixel pointer position in scaled-GUI coordinates (Mouse origin is bottom-left, physical px)
    private double pmx() { return Mouse.getX() * (double) this.width / this.minecraft.width; }
    private double pmy() { return this.height - Mouse.getY() * (double) this.height / this.minecraft.height; }

    // ---- render ------------------------------------------------------------------------------------------------

    @Override
    public void render(int mouseX, int mouseY, float tickDelta) {
        long now = System.nanoTime();
        if (closingAt != 0L && now - closingAt > 120_000_000L) { minecraft.openScreen(null); return; }
        LocalClientPlayerEntity p = minecraft.player;
        if (p == null || minecraft.world == null) { minecraft.openScreen(null); return; }
        // the usual in-world screen background: the HUD behind the sheet stays out of the way
        renderBackground();
        double dt = lastFrame == 0L ? 1.0 / 60.0 : Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;

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
        double px = p.prevX + (p.x - p.prevX) * tickDelta, pz = p.prevZ + (p.z - p.prevZ) * tickDelta;
        cursorWorld = MinimapHud.renderFullscreen(x0(), y0(), x1(), y1(), Math.exp(logPpb),
                px + panX, pz + panZ, fade, tickDelta, mouseX, mouseY);

        // ---- footer: where the cursor points, and the controls ----
        int a = Math.round(fade * 255F);
        if (a > 8) {
            String where = cursorWorld != null && mouseX >= x0() && mouseX <= x1() && mouseY >= y0() && mouseY <= y1()
                    ? String.format("X %d   Z %d", (int) Math.floor(cursorWorld[0]), (int) Math.floor(cursorWorld[1]))
                    : String.format("X %d   Z %d", (int) Math.floor(px), (int) Math.floor(pz));
            String hint = "滾輪縮放 · 拖曳移動 · 右鍵回到自己 · M / Esc 關閉";
            float ty = height - 21F;
            GlassWidgets.label(where, x0() + 4F, ty, 0xF5F5F7, fade);
            GlassWidgets.label(hint, x1() - 4F - GlassWidgets.strW(hint), ty, 0xC7C7CC, fade);
        }
    }

    // ---- input -------------------------------------------------------------------------------------------------

    @Override
    public void handleMouse() {
        super.handleMouse();
        int d = Mouse.getEventDWheel();
        if (d == 0) return;
        targetLogPpb += (d > 0 ? 1 : -1) * Math.log(1.25);
        clampView();
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        if (button == 1) { recenter(); return; }
        if (button == 0) { dragging = true; dragX = pmx(); dragY = pmy(); return; }
        super.mouseClicked(mx, my, button);
    }

    @Override
    protected void mouseDragged(int mx, int my, int button, long time) {
        if (!dragging) return;
        double x = pmx(), y = pmy();
        double ppb = Math.exp(logPpb);
        panX -= (x - dragX) / ppb;
        panZ -= (y - dragY) / ppb;
        dragX = x;
        dragY = y;
        clampView();
    }

    @Override
    protected void mouseReleased(int mx, int my, int button) {
        if (button == 0) dragging = false;
        super.mouseReleased(mx, my, button);
    }

    @Override
    protected void keyPressed(char ch, int code) {
        MinimapModule m = MinimapModule.get();
        int full = m == null ? 0 : KeyCodes.glfwToLwjgl(m.fullscreenKey.intValue);
        if (code == Keyboard.KEY_ESCAPE || (full > 0 && code == full)) { requestClose(); return; }
        if (code == Keyboard.KEY_C) { recenter(); return; }
        super.keyPressed(ch, code);
    }

    private void recenter() { panX = 0.0; panZ = 0.0; }

    /** 1.8.9 has no {@code Screen.close()}: start the fade-out; render() dismisses the screen once it has run. */
    private void requestClose() {
        if (closingAt != 0L) return;
        savedLogPpb = targetLogPpb;
        closingAt = System.nanoTime();
    }
}
