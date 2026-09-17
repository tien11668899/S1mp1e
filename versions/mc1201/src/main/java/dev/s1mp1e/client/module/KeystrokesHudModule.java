package dev.s1mp1e.client.module;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.LayoutEditable;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.client.gui.S1mp1eHudEditScreen;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;

/**
 * WASD + mouse (L/R) + Sneak + Space as REAL liquid-glass key-caps (same refractive glass as the hotbar).
 * Each key has its OWN position, so every cap can be dragged SEPARATELY in the HUD editor (via
 * {@link HudBoundsProvider}) — the arrangement persists as hidden per-key settings. On press a cap lights
 * with a specular HIGHLIGHT (eased) and its label fades grey→white; labels are dead-centred. Which keys
 * show (mouse / sneak / space) is toggled from the config menu. OBSERVE-ONLY: reads own key state.
 */
public final class KeystrokesHudModule extends Module implements HudRenderer, HudBounds, LayoutEditable {

    // fixed cap metadata per key index: W,A,S,D,L,R,SHIFT,SPACE
    private static final String[] LABEL = { "W", "A", "S", "D", "L", "R", "SHIFT", "" };
    private static final int[] KW = { 16, 16, 16, 16, 25, 25, 34, 16 };
    private static final int[] KH = { 16, 16, 16, 16, 16, 16, 12, 12 };
    private static final int[] DX = { 22, 4, 22, 40, 4, 31, 4, 40 };    // default positions
    private static final int[] DY = { 90, 108, 108, 108, 126, 126, 144, 144 };
    private static final int TX_REST = 0xFFBAC0CA, TX_DOWN = 0xFFFFFFFF;

    public final Setting mouse = add(Setting.bool("Mouse (L/R)", true));
    public final Setting sneak = add(Setting.bool("Sneak", true));
    public final Setting space = add(Setting.bool("Space", true));
    public final Setting glow  = add(Setting.color("Highlight", 0xFFFFFFFF));
    private final Setting[] kx = new Setting[8], ky = new Setting[8];   // hidden per-key positions

    private final float[] fade = new float[8];   // eased press state
    private long lastNano = 0L;

    public KeystrokesHudModule() {
        super("Keystrokes", "HUD");
        this.enabled = false;
        for (int i = 0; i < 8; i++) {
            kx[i] = add(Setting.integer("kx" + i, DX[i], 0, 8000).hide());
            ky[i] = add(Setting.integer("ky" + i, DY[i], 0, 8000).hide());
        }
    }

    private boolean visible(int i) {
        if (i < 4) return true;
        if (i < 6) return mouse.boolValue;
        if (i == 6) return sneak.boolValue;
        return space.boolValue;   // i == 7
    }

    @Override
    public void renderHud(DrawContext ctx) {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options == null || mc.options.hudHidden) return;

        boolean[] down = {
            pressed(mc.options.forwardKey), pressed(mc.options.leftKey),
            pressed(mc.options.backKey),    pressed(mc.options.rightKey),
            pressed(mc.options.attackKey),  pressed(mc.options.useKey),
            pressed(mc.options.sneakKey),   pressed(mc.options.jumpKey),
        };
        long now = System.nanoTime();
        float dt = (lastNano == 0L) ? (1f / 60f) : Math.min(0.1f, (now - lastNano) / 1.0e9f);
        lastNano = now;
        float a = 1f - (float) Math.exp(-dt * 16.0);
        for (int i = 0; i < 8; i++) fade[i] += ((down[i] ? 1f : 0f) - fade[i]) * a;

        boolean glass = GlassProgram.ensureReady() && GlassProgram.usable();

        // pass 1: real refractive glass caps + press highlight, at each key's ABSOLUTE position.
        // RS model-view and the ctx matrix are both identity in the HUD pass, so absolute coords in
        // GlassRenderer (RS) and GlassFont (ctx) land on the same screen pixels — no matrix push needed.
        if (glass) {
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabNow();
            RenderSystem.disableDepthTest();
            ctx.draw();   // flush pending HUD draws before the raw-GL glass
            GlassProgram.setShadowScale(mc.currentScreen != null ? 0f : 1f);
            try {
                for (int i = 0; i < 8; i++) {
                    if (!visible(i)) continue;
                    int x = kx[i].intValue, y = ky[i].intValue, w = KW[i], h = KH[i];
                    GlassRenderer.glass(x, y, x + w, y + h, 6f, 0.9f, 0f, 1f, GlassRenderer.FROST_PANEL);
                    if (fade[i] > 0.02f) {
                        int ga = Math.round(fade[i] * 130f);
                        GlassRenderer.roundRect(x, y, x + w, y + h, Math.min(w, h) * 0.42f,
                                                (ga << 24) | (glow.colorValue & 0xFFFFFF));
                    }
                }
            } finally {
                GlassProgram.setShadowScale(1f);
                RenderSystem.enableDepthTest();
            }
        }

        // pass 2: labels (also absolute), centred in each cap.
        for (int i = 0; i < 8; i++) {
            if (!visible(i)) continue;
            int x = kx[i].intValue, y = ky[i].intValue, w = KW[i], h = KH[i];
            if (!glass) {   // fallback so it still reads as a key
                HudGlass.roundFill(ctx, x, y, w, h, Math.min(4, Math.min(w, h) / 2),
                                   HudGlass.lerpArgb(0x40D2D8E2, 0xB0989DA6, fade[i]));
            }
            if (LABEL[i].isEmpty()) continue;
            int tc = HudGlass.lerpArgb(TX_REST, TX_DOWN, fade[i]);
            float tw = GlassFont.width(LABEL[i]);
            float ty = y + (h - GlassFont.height()) / 2f;   // centred (no +1 nudge)
            GlassFont.drawARGB(ctx, LABEL[i], x + (w - tw) / 2f, ty, tc, false);
        }
    }

    // ---- whole-block HudBounds: the general HUD editor drags ALL keys together ----
    public int hudX() {
        int m = Integer.MAX_VALUE;
        for (int i = 0; i < 8; i++) if (visible(i)) m = Math.min(m, kx[i].intValue);
        return m == Integer.MAX_VALUE ? 0 : m;
    }
    public int hudY() {
        int m = Integer.MAX_VALUE;
        for (int i = 0; i < 8; i++) if (visible(i)) m = Math.min(m, ky[i].intValue);
        return m == Integer.MAX_VALUE ? 0 : m;
    }
    public int hudW() {
        int lo = hudX(), hi = Integer.MIN_VALUE;
        for (int i = 0; i < 8; i++) if (visible(i)) hi = Math.max(hi, kx[i].intValue + KW[i]);
        return Math.max(4, hi - lo);
    }
    public int hudH() {
        int lo = hudY(), hi = Integer.MIN_VALUE;
        for (int i = 0; i < 8; i++) if (visible(i)) hi = Math.max(hi, ky[i].intValue + KH[i]);
        return Math.max(4, hi - lo);
    }
    public void hudSetPos(int x, int y) {   // move the whole layout so the visible bbox lands at (x,y)
        int dx = x - hudX(), dy = y - hudY();
        for (int i = 0; i < 8; i++) { kx[i].setInt(kx[i].intValue + dx); ky[i].setInt(ky[i].intValue + dy); }
    }
    public void hudResetPos() { for (int i = 0; i < 8; i++) { kx[i].reset(); ky[i].reset(); } }
    public String hudLabel() { return name; }

    // ---- dedicated per-key layout editor (opened from the config menu) ----
    @Override
    public Screen openLayoutEditor() { return new S1mp1eHudEditScreen(keyBoundsList()); }

    /** One draggable box per VISIBLE key, for the dedicated layout editor. */
    public List<HudBounds> keyBoundsList() {
        List<HudBounds> l = new ArrayList<HudBounds>();
        for (int i = 0; i < 8; i++) if (visible(i)) l.add(new KeyBounds(i));
        return l;
    }

    private final class KeyBounds implements HudBounds {
        private final int i;
        KeyBounds(int i) { this.i = i; }
        public int hudX() { return kx[i].intValue; }
        public int hudY() { return ky[i].intValue; }
        public void hudSetPos(int x, int y) { kx[i].setInt(x); ky[i].setInt(y); }
        public int hudW() { return KW[i]; }
        public int hudH() { return KH[i]; }
        public void hudResetPos() { kx[i].reset(); ky[i].reset(); }
        public String hudLabel() { return LABEL[i].isEmpty() ? "Space" : LABEL[i]; }
    }

    private static boolean pressed(KeyBinding kb) { return kb != null && kb.isPressed(); }
}
