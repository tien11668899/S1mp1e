package dev.s1mp1e.glass.hook;

import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.render.GlassCorners;
import net.minecraft.client.gui.Gui;

import java.lang.reflect.Method;

/**
 * allglass #23 — the advancements window's tab chrome matches the creative inventory: no vanilla tab plate at all,
 * only the selected tab shows an inset glass pill (its icon keeps drawing). The coremod head-splices
 * {@code if (AdvTabHook.draw(this, gui, x, y, selected, index)) return;} onto {@code AdvancementTabType.draw}
 * (the method that blits the tab background). We always return {@code true} to drop vanilla's sprite; for the selected
 * tab we draw the pill. The window-panel extension is handled by the existing {@code AdvancementsHook}.
 *
 * <p>{@code AdvancementTabType} is a package-private enum, so it arrives as {@link Object}; the tab side comes from its
 * {@code ordinal()} (0 ABOVE, 1 BELOW, 2 LEFT, 3 RIGHT) and the per-index offset from its {@code getX/getY(int)}.
 */
public final class AdvTabHook {

    private AdvTabHook() {}

    private static Method mGetX, mGetY;
    private static Class<?> cached;

    public static boolean draw(Object type, Gui gui, int x, int y, boolean selected, int index) {
        try {
            if (!selected || type == null) return true;      // unselected tabs: no plate (icon still draws)
            int ord = ((Enum<?>) type).ordinal();            // 0 ABOVE 1 BELOW 2 LEFT 3 RIGHT
            resolve(type.getClass());
            int ox = mGetX != null ? (Integer) mGetX.invoke(type, index) : 0;
            int oy = mGetY != null ? (Integer) mGetY.invoke(type, index) : 0;
            // ABOVE/BELOW tabs are 28x32, LEFT/RIGHT are 32x28.
            int w = (ord <= 1) ? 28 : 32, h = (ord <= 1) ? 32 : 28;
            float tx0 = x + ox, ty0 = y + oy, tx1 = tx0 + w, ty1 = ty0 + h;

            // Inset pill: 3px all round, plus 7px on the edge facing the window (spec #23).
            float in = 3f, deep = 7f;
            float px0 = tx0 + in, py0 = ty0 + in, px1 = tx1 - in, py1 = ty1 - in;
            switch (ord) {
                case 0: py1 -= deep; break;   // ABOVE  -> window below  -> pull bottom in
                case 1: py0 += deep; break;   // BELOW  -> window above  -> pull top in
                case 2: px1 -= deep; break;   // LEFT   -> window right  -> pull right in
                default: px0 += deep; break;  // RIGHT  -> window left   -> pull left in
            }
            if (px1 - px0 > 1 && py1 - py0 > 1) {
                GlassWidgets.capsule(px0, py0, px1, py1,
                        GlassCorners.hotbarCorner(px1 - px0, py1 - py0), 0.81f, 1.0f, true);
            }
        } catch (Throwable ignored) {
            // fall through: still return true so no half-drawn vanilla plate remains
        }
        return true;
    }

    private static void resolve(Class<?> cls) {
        if (cached == cls) return;
        cached = cls;
        mGetX = method(cls, "getX", "func_192648_a");
        mGetY = method(cls, "getY", "func_192653_b");
    }

    private static Method method(Class<?> cls, String mcp, String srg) {
        for (String n : new String[]{mcp, srg}) {
            try { Method m = cls.getDeclaredMethod(n, int.class); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignored) {}
        }
        return null;
    }
}
