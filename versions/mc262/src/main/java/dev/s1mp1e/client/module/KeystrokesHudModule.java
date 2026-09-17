package dev.s1mp1e.client.module;

import java.util.ArrayList;
import java.util.List;

import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import com.seagull.liquidglass.client.render.GlassPipeline;
import com.seagull.liquidglass.client.render.GlassRectRenderState;
import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.LayoutEditable;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.S1mp1eHudCtx;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassFont;
import dev.s1mp1e.client.gui.S1mp1eHudEditScreen;
import dev.s1mp1e.client.hud.HudGlass;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/**
 * WASD + mouse (L/R) + Sneak + Space as REAL liquid-glass key-caps (same refractive glass as the hotbar).
 * Each key has its OWN position, so every cap can be dragged SEPARATELY in the dedicated layout editor —
 * the arrangement persists as hidden per-key settings. On press a cap lights with a specular HIGHLIGHT
 * (eased) and its label fades grey→white; labels are dead-centred. Which keys show (mouse / sneak / space)
 * is toggled from the config menu. OBSERVE-ONLY: reads the player's own key-mapping state.
 *
 * <p>26.2 port of mc1211's module. The immediate-mode glass pass (flush, depth off, raw-GL
 * {@code GlassRenderer.glass}, shadow scale) becomes an enqueued {@link GlassRectRenderState} with the exact
 * same knobs (pad 6, corner 0.9, no lift, opacity 1, frost 0.5). The SDF highlight {@code roundRect} becomes
 * {@link HudGlass#roundFill}. {@code GuiRenderState} lifts every element whose bounds intersect an earlier one
 * into a higher node, so highlight-over-glass and label-over-highlight layering matches mc1211.
 */
public final class KeystrokesHudModule extends Module implements HudRenderer, HudBounds, LayoutEditable {

    // fixed cap metadata per key index: W,A,S,D,L,R,SHIFT,SPACE
    private static final String[] LABEL = { "W", "A", "S", "D", "L", "R", "SHIFT", "" };
    private static final int[] KW = { 16, 16, 16, 16, 25, 25, 34, 16 };
    private static final int[] KH = { 16, 16, 16, 16, 16, 16, 12, 12 };
    private static final int[] DX = { 22, 4, 22, 40, 4, 31, 4, 40 };    // default positions
    private static final int[] DY = { 90, 108, 108, 108, 126, 126, 144, 144 };
    private static final int TX_REST = 0xFFBAC0CA, TX_DOWN = 0xFFFFFFFF;

    /** mc1211 GlassRenderer.glass(..., pad 6, corner 0.9, lift 0, opacity 1, FROST_PANEL) as 26.2 knobs:
     *  A = frost 0.5 (0x80), R = corner 0.9 (0xE6), G = 1 − lift (0xFF), B = opacity 1 (0xFF). */
    private static final int CAP_KNOBS = 0x80E6FFFF;
    private static final int CAP_PAD = 6;

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
    public void renderHud(S1mp1eHudCtx c) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options == null) return;   // F1 handled by HudDriverMixin
        GuiGraphicsExtractor g = c.g();

        boolean[] down = {
            pressed(mc.options.keyUp),     pressed(mc.options.keyLeft),
            pressed(mc.options.keyDown),   pressed(mc.options.keyRight),
            pressed(mc.options.keyAttack), pressed(mc.options.keyUse),
            pressed(mc.options.keyShift),  pressed(mc.options.keyJump),
        };
        long now = System.nanoTime();
        float dt = (lastNano == 0L) ? (1f / 60f) : Math.min(0.1f, (now - lastNano) / 1.0e9f);
        lastNano = now;
        float a = 1f - (float) Math.exp(-dt * 16.0);
        for (int i = 0; i < 8; i++) fade[i] += ((down[i] ? 1f : 0f) - fade[i]) * a;

        boolean glass = GlassPipeline.ensureReady() && GlassPipeline.usable();

        // pass 1: real refractive glass caps + press highlight, at each key's ABSOLUTE position.
        if (glass) {
            GuiRenderState rs = ((GuiGraphicsExtractorAccessor) g).liquidglass$guiRenderState();
            TextureSetup ts = TextureSetup.singleTexture(GlassPipeline.backdropView(), GlassPipeline.sampler());
            for (int i = 0; i < 8; i++) {
                if (!visible(i)) continue;
                int x = kx[i].intValue, y = ky[i].intValue, w = KW[i], h = KH[i];
                rs.addGuiElement(new GlassRectRenderState(GlassPipeline.glass(), ts, g.pose(),
                        x, y, x + w, y + h, CAP_PAD, CAP_KNOBS, null));
                if (fade[i] > 0.02f) {
                    int ga = Math.round(fade[i] * 130f);
                    // Anti-aliased SDF rounded rect, exactly 1.21.1's GlassRenderer.roundRect(min(w,h)*0.42). The
                    // stepped roundFill it replaces came out as a 4-3-2-1px chamfer on a 16px key — cut corners,
                    // not a curve.
                    HudGlass.roundRect(g, x, y, x + w, y + h, Math.min(w, h) * 0.42f,
                                       (ga << 24) | (glow.colorValue & 0xFFFFFF));
                }
            }
        }

        // pass 2: labels (also absolute), centred in each cap.
        for (int i = 0; i < 8; i++) {
            if (!visible(i)) continue;
            int x = kx[i].intValue, y = ky[i].intValue, w = KW[i], h = KH[i];
            if (!glass) {   // fallback so it still reads as a key
                HudGlass.roundFill(g, x, y, w, h, Math.min(4, Math.min(w, h) / 2),
                                   HudGlass.lerpArgb(0x40D2D8E2, 0xB0989DA6, fade[i]));
            }
            if (LABEL[i].isEmpty()) continue;
            int tc = HudGlass.lerpArgb(TX_REST, TX_DOWN, fade[i]);
            float tw = GlassFont.width(LABEL[i]);
            float ty = y + (h - GlassFont.height()) / 2f;   // centred (no +1 nudge)
            GlassFont.drawARGB(g, LABEL[i], x + (w - tw) / 2f, ty, tc, false);
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

    private static boolean pressed(KeyMapping kb) { return kb != null && kb.isDown(); }
}
