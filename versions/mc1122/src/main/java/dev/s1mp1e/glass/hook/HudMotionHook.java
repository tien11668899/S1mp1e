package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.render.HealthTrail;
import dev.s1mp1e.glass.render.TabListFade;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;

/**
 * Static seams for the group-7 HUD motion on 1.12.2 (all spliced by {@code S1mp1eTransformer}):
 * <ul>
 *   <li><b>Tab list fade</b> — {@link #tabGate} replaces {@code keyBindPlayerList.isKeyDown()} in
 *       {@code GuiIngameForge.renderPlayerList}; {@link #tabColor} / {@link #tabText} replace the
 *       {@code GlStateManager.color} and {@code drawStringWithShadow} calls of {@code GuiPlayerTabOverlay}'s list,
 *       ping and score draws so heads, ping bars and names take {@link TabListFade#alpha()} (the glass plates take it
 *       in {@code GlassTabList}).</li>
 *   <li><b>Health damage trail</b> — in {@code GuiIngameForge.renderHealth} the {@code highlight} local is replaced
 *       by {@link #heartBlink} (= the trail is active) and {@code healthLast} by {@link #heartTop} (= the ghost top), so
 *       vanilla draws the white ghost hearts itself for as long as the trail drains; every heart blit goes through
 *       {@link #heartBlit}: the white "blinking" container frame (u 25) is never used, and the ghost hearts are drawn
 *       at {@link HealthTrail#ghostAlpha}. The CURRENT health ({@code health}) is never touched (the 1.21.1 lesson).</li>
 * </ul>
 */
public final class HudMotionHook {

    private HudMotionHook() {}

    // ---- tab list ----

    public static boolean tabGate(KeyBinding key) {
        return TabListFade.gate(key.isKeyDown());
    }

    public static void tabColor(float r, float g, float b, float a) {
        GlStateManager.color(r, g, b, a * TabListFade.alpha());
    }

    public static int tabText(FontRenderer fr, String s, float x, float y, int color) {
        float f = TabListFade.alpha();
        if (f < 0.999f) {
            int a = (color >>> 24) & 0xFF;
            if (a == 0) a = 0xFF;
            int na = Math.round(a * f);
            if (na < 4) return (int) x + fr.getStringWidth(s);
            color = (na << 24) | (color & 0xFFFFFF);
        }
        return fr.drawStringWithShadow(s, x, y, color);
    }

    // ---- health trail ----

    private static int curHealth;

    /** Replaces the {@code highlight} local: update the trail with the current health, blink = trail active. */
    public static boolean heartBlink(int health) {
        curHealth = health;
        HealthTrail.update(health);
        return HealthTrail.active(health);
    }

    /** Replaces the {@code healthLast} local: the ghost top. */
    public static int heartTop(int healthLast) {
        return HealthTrail.displayHealth(curHealth);
    }

    /** Every heart blit of renderHealth. */
    public static void heartBlit(Gui self, int x, int y, int u, int v, int w, int h) {
        if (u == 25) u = 16;                                  // never the white blinking container frame
        boolean ghost = u == 70 || u == 79 || u == 106 || u == 115 || u == 142 || u == 151;
        if (!ghost) { self.drawTexturedModalRect(x, y, u, v, w, h); return; }
        float a = HealthTrail.ghostAlpha(curHealth);
        if (a >= 0.999f) { self.drawTexturedModalRect(x, y, u, v, w, h); return; }
        GlStateManager.color(1f, 1f, 1f, a);
        try {
            self.drawTexturedModalRect(x, y, u, v, w, h);
        } finally {
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
    }
}
