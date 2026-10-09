package dev.s1mp1e.glass.hook;

import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.render.GlassCorners;
import net.minecraft.client.gui.Gui;

/**
 * allglass #17 — the experience and jump (horse) bars become a glass capsule track with a round-ended coloured fill.
 * The coremod redirects the jump bar's blits inside {@code GuiIngameForge.renderJumpBar} here, and
 * {@link GlassHudHandler} (which owns the XP element) sends its own XP bar blits here; we switch on the vanilla
 * sprite's V to tell track from fill and XP from jump:
 * <ul>
 *   <li>v = 64 — XP track, v = 69 — XP fill (#30D158)</li>
 *   <li>v = 84 — jump track, v = 89 — jump fill (#FF9F0A)</li>
 * </ul>
 *
 * <p><b>Spec trap #1:</b> we paint at the exact (x, y) vanilla passes and never subtract a lift. 1.8.9 lifts the HUD
 * cluster with a GL translate, and the glass shader reads the model-view, so it follows that lift automatically —
 * subtracting one here would double-lift the bar onto the hearts (verify in-world that XP sits below the hearts).
 */
public final class ContextualBarHook {

    private ContextualBarHook() {}

    private static final int XP   = 0xFF30D158;   // systemGreen
    private static final int JUMP = 0xFFFF9F0A;   // systemOrange
    private static boolean reported;

    public static void bar(Gui gui, int x, int y, int u, int v, int w, int h) {
        try {
            float x0 = x, y0 = y, x1 = x + w, y1 = y + h;
            switch (v) {
                case 64:   // XP track
                case 84:   // jump track
                    GlassWidgets.capsule(x0, y0, x1, y1, GlassCorners.cornerKnob(w, h), 0f, 0.9f, true);
                    break;
                case 69:   // XP fill
                    if (w > 0) GlassWidgets.fillRound(x0, y0, x1, y1, XP, h / 2f);
                    break;
                case 89:   // jump fill
                    if (w > 0) GlassWidgets.fillRound(x0, y0, x1, y1, JUMP, h / 2f);
                    break;
                default:
                    gui.drawTexturedModalRect(x, y, u, v, w, h);   // unexpected sprite: leave vanilla
            }
        } catch (Throwable t) {
            if (!reported) { reported = true; System.out.println("[S1mp1e] ContextualBarHook failed, vanilla bar kept: " + t); t.printStackTrace(); }
            gui.drawTexturedModalRect(x, y, u, v, w, h);
        }
    }
}
