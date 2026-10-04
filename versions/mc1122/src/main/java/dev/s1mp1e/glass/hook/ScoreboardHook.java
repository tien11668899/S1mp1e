package dev.s1mp1e.glass.hook;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;

import java.util.ArrayList;

/**
 * Fade the scoreboard sidebar in when it appears and out when it is hidden (group 7) — the 1.12.2 counterpart of
 * mc1144's {@code ScoreboardFade} + {@code ScoreboardGlassMixin}. Vanilla draws the sidebar only while a sidebar
 * objective is set, so it pops in and out. Seams ({@code S1mp1eTransformer}):
 * <ul>
 *   <li>{@code GuiIngameForge.renderGameOverlay}: every {@code Scoreboard.getObjectiveInDisplaySlot(I)} goes through
 *       {@link #sidebar}; for slot 1 (the sidebar) it advances the fade and, while the sidebar is gone but still fading,
 *       replays the last recorded sidebar at the falling alpha (a vanilla server removes an undisplayed objective from
 *       the client at once, so "keep drawing the objective" would find no scores);</li>
 *   <li>{@code GuiIngame.renderScoreboard}: HEAD {@link #begin} / every RETURN {@link #end} bracket a recording; its
 *       three {@code drawRect} go through {@link #fill} and its three {@code drawString} through {@link #text}, both
 *       recorded and drawn at the fade alpha.</li>
 * </ul>
 * Fade state starts "out", so the first sidebar of a session fades in instead of popping (the slip mc1144 fixed).
 */
public final class ScoreboardHook {

    private ScoreboardHook() {}

    private static final float IN_S = 0.15F, OUT_S = 0.15F;
    private static boolean prevPresent;
    private static long legNs;
    private static boolean out = true;
    private static float legFrom, alpha;

    private static final class Op {
        final String text; final int x0, y0, x1, y1, color;
        Op(String text, int x0, int y0, int x1, int y1, int color) {
            this.text = text; this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.color = color;
        }
    }

    private static ArrayList<Op> recording = new ArrayList<Op>();
    private static ArrayList<Op> shown = new ArrayList<Op>();

    public static ScoreObjective sidebar(Scoreboard sb, int slot) {
        ScoreObjective real = sb.getObjectiveInDisplaySlot(slot);
        if (slot != 1) return real;
        try {
            boolean present = real != null;
            long now = System.nanoTime();
            if (present != prevPresent) { legFrom = alpha; legNs = now; out = !present; prevPresent = present; }
            float t = (now - legNs) / 1.0e9F / (out ? OUT_S : IN_S);
            t = t < 0F ? 0F : (t > 1F ? 1F : t);
            alpha = legFrom + ((out ? 0F : 1F) - legFrom) * t;
            if (!present && alpha <= 0.004F) shown.clear();
            if (!present && alpha > 0.004F && !shown.isEmpty()) {
                FontRenderer font = Minecraft.getMinecraft().fontRenderer;
                for (Op op : shown) {
                    if (op.text == null) Gui.drawRect(op.x0, op.y0, op.x1, op.y1, fadeFill(op.color, alpha));
                    else drawFaded(font, op.text, op.x0, op.y0, op.color, alpha);
                }
            }
        } catch (Throwable ignored) {}
        return real;
    }

    public static void begin() { recording.clear(); }

    public static void end() {
        ArrayList<Op> t = shown;
        shown = recording;
        recording = t;
    }

    public static void fill(int x0, int y0, int x1, int y1, int color) {
        recording.add(new Op(null, x0, y0, x1, y1, color));
        if (alpha <= 0.004F) return;
        Gui.drawRect(x0, y0, x1, y1, fadeFill(color, alpha));
    }

    public static int text(FontRenderer font, String s, int x, int y, int color) {
        recording.add(new Op(s, x, y, 0, 0, color));
        return drawFaded(font, s, x, y, color, alpha);
    }

    private static int fadeFill(int color, float a) {
        if (a >= 1F) return color;
        return (Math.round((color >>> 24 & 0xFF) * a) & 0xFF) << 24 | color & 0xFFFFFF;
    }

    private static int drawFaded(FontRenderer font, String text, int x, int y, int color, float a) {
        if (a <= 0.02F) return x + font.getStringWidth(text);
        // vanilla's sidebar colours carry a legacy alpha byte (0x20) that is never used: the text is opaque
        int al = Math.max(4, Math.round(0xFF * a)) & 0xFF;
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        try {
            return font.drawString(text, x, y, al << 24 | color & 0xFFFFFF);
        } finally {
            GlStateManager.disableBlend();
        }
    }
}
