package dev.s1mp1e.client.module;

import java.util.ArrayDeque;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.S1mp1eHudCtx;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassFont;
import net.minecraft.client.Minecraft;

/**
 * Clicks-per-second readout for the left and right mouse buttons.
 *
 * <p><b>FAIR-PLAY GUARDRAIL — DO NOT "IMPROVE" THIS CLASS.</b> It ONLY OBSERVES:
 * it must never inject, shape, smooth, schedule, or suggest a click, and never
 * show click-timing advice. It reads input and draws a number.
 *
 * <p>Counting is a rolling one-second window: a click is stamped and stamps older
 * than 1000 ms are evicted, so the value is exactly "clicks in the last second".
 * Clicks arrive from {@code MouseClickMixin} (GLFW press on {@code MouseHandler
 * .onButton}) so every hardware press is seen even when several land in one
 * tick — polling would drop them.
 *
 * <p>26.2 port: the open screen moved from {@code mc.currentScreen} to {@code mc.gui.screen()}.
 */
public final class CpsModule extends Module implements HudBounds, HudRenderer {

    private static final long WINDOW_MS = 1000L;

    public final Setting posX      = add(Setting.integer("PosX", 4, 0, 2000));
    public final Setting posY      = add(Setting.integer("PosY", 4, 0, 2000));
    public final Setting showRight = add(Setting.bool("Show right CPS", false));
    public final Setting color     = add(Setting.color("Colour", 0xFFFFFFFF));
    public final Setting shadow    = add(Setting.bool("Shadow", true));
    public int lastW = 40, lastH = 10;

    private final ArrayDeque<Long> leftClicks  = new ArrayDeque<Long>();
    private final ArrayDeque<Long> rightClicks = new ArrayDeque<Long>();

    /** The live instance the click mixin feeds (ModuleManager builds exactly one). */
    private static CpsModule instance;

    public CpsModule() {
        super("CPS", "Combat");
        this.enabled = true;   // additive readout of your own data; on by default
        instance = this;
    }

    /** Called from {@code MouseClickMixin} on a hardware press (0 = left, 1 = right). */
    public static void recordClick(int button) {
        CpsModule m = instance;
        if (m == null || !m.enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() != null || mc.player == null) return;   // in-world clicks only
        long now = System.currentTimeMillis();
        if (button == 0)      m.leftClicks.addLast(Long.valueOf(now));
        else if (button == 1) m.rightClicks.addLast(Long.valueOf(now));
        prune(m.leftClicks, now);
        prune(m.rightClicks, now);
    }

    @Override
    public void renderHud(S1mp1eHudCtx c) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        long now = System.currentTimeMillis();
        int left  = prune(leftClicks, now);
        int right = prune(rightClicks, now);

        String text = showRight.boolValue ? (left + " | " + right + " CPS") : (left + " CPS");
        lastW = Math.round(GlassFont.width(text));
        lastH = Math.round(GlassFont.height());
        GlassFont.drawARGB(c.g(), text, posX.intValue, posY.intValue, color.colorValue, shadow.boolValue);
    }

    // ---- HudBounds (for the HUD editor) ----
    public int hudX() { return posX.intValue; }
    public int hudY() { return posY.intValue; }
    public void hudSetPos(int x, int y) { posX.setInt(x); posY.setInt(y); }
    public int hudW() { return lastW > 0 ? lastW : 40; }
    public int hudH() { return lastH > 0 ? lastH : 10; }
    public void hudResetPos() { posX.reset(); posY.reset(); }
    public String hudLabel() { return name; }

    private static int prune(ArrayDeque<Long> stamps, long now) {
        while (!stamps.isEmpty() && now - stamps.peekFirst().longValue() >= WINDOW_MS) stamps.pollFirst();
        return stamps.size();
    }
}
