package dev.s1mp1e.glass.asm;

import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.Gui;

import java.lang.reflect.Method;

/**
 * Stands in for vanilla's flat white slot-hover square.
 *
 * <p>{@code GuiContainer.drawScreen} paints a {@code drawGradientRect} of
 * 0x80FFFFFF over the hovered slot. The liquid-glass hover pill already conveys
 * hover, and the two together read as a white box sitting on the glass — which
 * is what the user saw. So while the glass path is live this draws nothing; if
 * glass is unavailable it calls the original through so vanilla looks normal.
 */
public final class HoverHook {

    private HoverHook() {}

    private static Method original;
    private static boolean resolved;

    /**
     * Stands in for vanilla's flat white drag-distribute square.
     *
     * <p>{@code GuiContainer.drawSlot} paints {@code drawRect(x, y, x+16, y+16,
     * 0x80FFFFFF)} over every slot in the drag set — a hard-cornered white box
     * that landed ON TOP of our rounded glass highlight. The glass version is
     * already drawn during BackgroundDrawnEvent, so while the glass path is
     * live this draws nothing.
     */
    public static void dragHighlight(int left, int top, int right, int bottom, int color) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) return;
        net.minecraft.client.gui.Gui.drawRect(left, top, right, bottom, color);
    }

    public static void slotHighlight(Gui gui, int left, int top, int right, int bottom,
                                     int startColor, int endColor) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) return;  // glass owns hover
        if (!resolved) {
            resolved = true;
            String[] names = { "drawGradientRect", "func_73733_a" };
            for (int i = 0; i < names.length && original == null; i++) {
                try {
                    original = Gui.class.getDeclaredMethod(names[i],
                            int.class, int.class, int.class, int.class, int.class, int.class);
                    original.setAccessible(true);
                } catch (NoSuchMethodException ignored) { }
            }
        }
        if (original == null) return;
        try {
            original.invoke(gui, Integer.valueOf(left), Integer.valueOf(top),
                    Integer.valueOf(right), Integer.valueOf(bottom),
                    Integer.valueOf(startColor), Integer.valueOf(endColor));
        } catch (Throwable ignored) { }
    }
}
