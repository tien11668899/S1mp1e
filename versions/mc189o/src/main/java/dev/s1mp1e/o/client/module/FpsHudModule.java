package dev.s1mp1e.o.client.module;

import dev.s1mp1e.o.client.HudBounds;
import dev.s1mp1e.o.client.HudRenderer;
import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import dev.s1mp1e.o.client.gui.GlassFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.platform.GlStateManager;

/**
 * A glass FPS pill so the player doesn't need the full F3 overlay. Reads only
 * {@link Minecraft#getDebugFPS()} — pure client telemetry, no game state.
 *
 * <p>Ported from mc1211's {@code FpsHudModule}; 1.8.9 is immediate-mode so the text
 * is drawn inside a {@link GlStateManager} matrix (translate + scale) rather than a
 * {@code DrawContext}, exactly like {@link ArmorHudModule}. The background is REAL
 * liquid glass via {@link HudGlass#glassBox} at absolute coords, so it lines up with
 * the text drawn at the same coords in the HUD pass.
 */
public final class FpsHudModule extends Module implements HudBounds, HudRenderer {

    private static final int PAD = 3;
    /** Scale of the 1% low / stutter lines under the frame rate. */
    private static final float SUB = 0.7f;

    public final Setting posX   = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY   = add(Setting.integer("Y", 4, 0, 4000));
    public final Setting scale  = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting color  = add(Setting.color("Colour", 0xFFFFFFFF));
    public final Setting suffix = add(Setting.bool("Show 'FPS'", true));
    public final Setting bg     = add(Setting.bool("Background", true));
    // smoothness: the average frame rate of the slowest 1% of frames over the last 10 s, and the hitches in the last
    // minute (a frame over 3x the running average and over 25 ms)
    public final Setting low1     = add(Setting.bool("1% low", true));
    public final Setting stutters = add(Setting.bool("Stutters", true));
    public int lastW = 24, lastH = 12;

    private static final int RING = 2048;
    private static final long WINDOW_NS = 10_000_000_000L, STUTTER_WINDOW_NS = 60_000_000_000L;
    private final long[] frameNs = new long[RING], frameAt = new long[RING], sorted = new long[RING];
    private final long[] stutterAt = new long[256];
    private int ringPos, ringCount, stutterPos, stutterCount;
    private long lastFrame, lastCalc;
    private double avgNs;
    private int low1Fps = -1, stutterShown;

    public FpsHudModule() { super("FpsHUD", "HUD"); this.enabled = true; }

    /** Records this frame's time (the HUD is drawn once per frame) and refreshes the 1% low twice a second. */
    private void sample(long now) {
        long dt = lastFrame == 0L ? 0L : now - lastFrame;
        lastFrame = now;
        if (dt <= 0L || dt > 1_000_000_000L) return;   // first frame, or back from a pause / menu: not a frame time
        frameNs[ringPos] = dt;
        frameAt[ringPos] = now;
        ringPos = (ringPos + 1) % RING;
        if (ringCount < RING) ringCount++;
        if (avgNs > 0 && dt > 25_000_000L && dt > avgNs * 3.0) {
            stutterAt[stutterPos] = now;
            stutterPos = (stutterPos + 1) % stutterAt.length;
            if (stutterCount < stutterAt.length) stutterCount++;
        }
        avgNs = avgNs == 0 ? dt : avgNs * 0.95 + dt * 0.05;

        if (now - lastCalc < 500_000_000L) return;
        lastCalc = now;
        int n = 0;
        for (int i = 0; i < ringCount; i++) {
            int idx = (ringPos - 1 - i + RING) % RING;   // newest first
            if (now - frameAt[idx] > WINDOW_NS) break;
            sorted[n++] = frameNs[idx];
        }
        if (n >= 20) {
            java.util.Arrays.sort(sorted, 0, n);
            int k = Math.max(1, n / 100);
            long sum = 0L;
            for (int i = n - k; i < n; i++) sum += sorted[i];
            low1Fps = (int) Math.round(1e9 / (sum / (double) k));
        }
        int s = 0;
        for (int i = 0; i < stutterCount; i++) if (now - stutterAt[i] <= STUTTER_WINDOW_NS) s++;
        stutterShown = s;
    }

    /** The current 1% low (fps) and stutter count, for DevShot. */
    public int low1Fps() { return low1Fps; }
    public int stutterCount() { return stutterShown; }

    @Override
    public void renderHud() {
        // visibility (incl. the fade-out after switching off) is decided by HudRenderDispatcher via HudFade; no enabled-guard
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (mc.options.hideGui && mc.screen == null) return;
        sample(System.nanoTime());

        // the smoothness readouts go UNDER the frame rate in small type, so the pill keeps its width and never runs
        // into the HUD element to its right (the coordinates pill sits there by default)
        String s = Minecraft.getCurrentFps() + (suffix.boolValue ? " FPS" : "");
        String low = low1.boolValue ? "1% low " + (low1Fps > 0 ? Integer.toString(low1Fps) : "-") : null;
        String hitch = stutters.boolValue ? "卡頓 " + stutterShown : null;
        int th = Math.round(GlassFont.height());
        float w = GlassFont.width(s), h = th;
        if (low != null) { w = Math.max(w, GlassFont.width(low) * SUB); h += 1 + th * SUB; }
        if (hitch != null) { w = Math.max(w, GlassFont.width(hitch) * SUB); h += 1 + th * SUB; }
        int boxW = Math.round(w) + PAD * 2, boxH = Math.round(h) + PAD * 2;
        float sc = (float) scale.doubleValue;
        lastW = Math.round(boxW * sc);
        lastH = Math.round(boxH * sc);

        int x0 = posX.intValue, y0 = posY.intValue;
        if (bg.boolValue) HudGlass.glassBox(x0, y0, x0 + lastW, y0 + lastH, 0.85f);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translatef((float) x0, (float) y0, 0f);
            GlStateManager.scalef(sc, sc, 1f);
            dev.s1mp1e.o.client.hud.HudText.draw(s, PAD, PAD, color.colorValue, true);
            int dim = (color.colorValue & 0x00FFFFFF) | 0xC8000000;
            float y = PAD + th + 1;
            for (String line : new String[]{ low, hitch }) {
                if (line == null) continue;
                GlStateManager.pushMatrix();
                GlStateManager.translatef(PAD, y, 0f);
                GlStateManager.scalef(SUB, SUB, 1f);
                dev.s1mp1e.o.client.hud.HudText.draw(line, 0, 0, dim, true);
                GlStateManager.popMatrix();
                y += th * SUB + 1;
            }
        } finally {
            GlStateManager.popMatrix();
        }
    }

    // ---- HudBounds ----
    public int hudX() { return posX.intValue; }
    public int hudY() { return posY.intValue; }
    public void hudSetPos(int x, int y) { posX.setInt(x); posY.setInt(y); }
    public int hudW() { return lastW > 0 ? lastW : 24; }
    public int hudH() { return lastH > 0 ? lastH : 12; }
    public void hudResetPos() { posX.reset(); posY.reset(); }
    public String hudLabel() { return name; }
}
