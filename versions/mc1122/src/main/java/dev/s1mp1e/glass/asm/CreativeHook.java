package dev.s1mp1e.glass.asm;

import dev.s1mp1e.glass.hook.CreativeGlideHook;
import dev.s1mp1e.glass.hook.GlassContainerHandler;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiContainerCreative;

import java.lang.reflect.Field;

/**
 * Feature (C) — replaces the creative item-grid scrollbar thumb blit (a single
 * {@code drawTexturedModalRect(i, y, u, 0, 12, 15)} in
 * {@code GuiContainerCreative.drawGuiContainerBackgroundLayer}) with the glass
 * scrollbar. The coremod swaps just that one blit — identified by its 12&times;15
 * size constants — for {@link #scrollbar}; the body texture (already dropped by
 * {@link BlitSuppressor}), the tab sprites and every other draw are untouched.
 *
 * <p>Runs INSIDE the background layer, before the items and the tooltip, so the
 * glass scrollbar is under the tooltip (rule R1). Reads only the screen's own
 * {@code currentScroll}/{@code isScrolling} fields, so what is drawn tracks what
 * vanilla scrolled. If the glass pipeline is down it draws the vanilla thumb
 * (the tabs texture is already bound at the call site), so the bar never vanishes.
 */
public final class CreativeHook {

    private CreativeHook() {}

    /** Public {@link Gui} for the vanilla-thumb fallback blit. */
    private static final Gui BLIT = new Gui();

    private static boolean reflectTried;
    private static Field fScroll, fScrolling;

    /** Draws the glass scrollbar in place of the vanilla thumb (or the vanilla
     *  thumb itself if the glass path is unavailable). */
    public static void scrollbar(GuiContainerCreative screen, int x, int y, int u, int v, int w, int h) {
        try {
            if (GlassProgram.ensureReady() && GlassProgram.usable() && SceneCapture.hasBackdrop()) {
                int[] rect = GlassContainerHandler.panelRect(screen);
                if (rect != null) {
                    ensureReflect();
                    float scroll = fScroll != null ? fScroll.getFloat(screen) : 0f;
                    boolean held = fScrolling != null && fScrolling.getBoolean(screen);
                    // u = 232 when the grid can scroll (draggable), 244 when greyed.
                    boolean active = u <= 232;
                    // Feature (C) glass scrollbar + feature (D) sub-pixel glide state, in one call.
                    CreativeGlideHook.drawScrollbar(screen, rect[0], rect[1], scroll, held, active);
                    return;
                }
            }
        } catch (Throwable t) {
            // fall through to the vanilla thumb
        }
        try {
            BLIT.drawTexturedModalRect(x, y, u, v, w, h);
        } catch (Throwable ignored) {
        }
    }

    private static void ensureReflect() {
        if (reflectTried) return;
        reflectTried = true;
        fScroll    = field("field_147067_x", "currentScroll");
        fScrolling = field("field_147066_y", "isScrolling");
    }

    private static Field field(String srg, String mcp) {
        String[] names = { srg, mcp };
        for (int i = 0; i < names.length; i++) {
            try {
                Field f = GuiContainerCreative.class.getDeclaredField(names[i]);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
