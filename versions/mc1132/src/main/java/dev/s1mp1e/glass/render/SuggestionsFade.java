package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.GuiAlpha;

/**
 * Appear / disappear motion of the command suggestions list (1.13.2 form of the 1.16+ {@code CommandSuggestor}
 * hooks). On 1.13.2 the list is {@code ChatScreen$SuggestionWindow}, a package-private inner class of the chat screen
 * that cannot be named from here, so the state lives in this helper and the two mixins only report:
 * <ul>
 *   <li>{@code ChatScreen.render} HEAD / RETURN -> {@link #beginFrame()} / {@link #endFrame()};</li>
 *   <li>{@code SuggestionWindow.draw} HEAD / RETURN -> {@link #enter(Window)} / {@link #leave(boolean)}.</li>
 * </ul>
 * A list that shows up eases in (fade + 3 px rise, critically damped); when it goes away the last list is drawn as
 * a ghost for a short grace (retyping swaps lists every key press - that must not blink) and then fades out.
 */
public final class SuggestionsFade {

    private SuggestionsFade() {}

    /** The window, seen through its mixin. */
    public interface Window {
        void s1mp1e$drawWindow(int mouseX, int mouseY);
    }

    private static final long GRACE_NS = 70_000_000L;
    private static final float OUT_S = 0.12f;
    private static final float W = 22.0f;

    private static Window ghost;
    private static boolean had, drawn, ghosting;
    private static long gapNs, appearNs;

    public static void beginFrame() {
        drawn = false;
    }

    /** Called at the head of the list's own draw. True = a fade scope was opened ({@link #leave} closes it). */
    public static boolean enter(Window list) {
        if (ghosting) return false;
        long now = System.nanoTime();
        boolean continuous = had || (gapNs != 0L && now - gapNs < GRACE_NS);
        if (!continuous) appearNs = now;
        had = true;
        drawn = true;
        gapNs = 0L;
        ghost = list;
        float t = (now - appearNs) / 1.0e9f;
        float p = appearNs == 0L ? 1f : 1f - (1f + W * t) * (float) Math.exp(-W * t);
        if (p >= 0.998f) return false;
        float inv = 1f - p;
        GuiAlpha.push(1f - inv * inv);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, 3f * inv, 0f);
        return true;
    }

    public static void leave(boolean opened) {
        if (!opened) return;
        GuiAlpha.pop();
        GlStateManager.popMatrix();
    }

    /** End of the chat screen's render: no list was drawn this frame -> the ghost of the last one. */
    public static void endFrame() {
        if (drawn) return;
        long now = System.nanoTime();
        if (had) { had = false; gapNs = now; }
        if (ghost == null || gapNs == 0L) return;
        long gap = now - gapNs;
        float a;
        if (gap < GRACE_NS) {
            a = 1f;
        } else {
            float q = (gap - GRACE_NS) / 1.0e9f / OUT_S;
            if (q >= 1f) { ghost = null; return; }
            a = (1f - q) * (1f - q);
        }
        GuiAlpha.push(a);
        ghosting = true;
        try {
            ghost.s1mp1e$drawWindow(-10000, -10000);   // off-screen pointer: no hover, no tooltip
        } catch (Throwable t) {
            ghost = null;
        } finally {
            ghosting = false;
            GuiAlpha.pop();
        }
    }

    /** The chat screen closed: nothing to ghost on the next one. */
    public static void reset() {
        ghost = null;
        had = false;
        gapNs = 0L;
    }
}
