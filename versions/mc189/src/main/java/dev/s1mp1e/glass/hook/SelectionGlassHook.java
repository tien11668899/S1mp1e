package dev.s1mp1e.glass.hook;

import dev.s1mp1e.client.gui.GlassWidgets;
import dev.s1mp1e.glass.render.GlassCorners;
import net.minecraft.client.gui.GuiSlot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * allglass #4 — the list's selected row becomes a liquid-glass capsule (hotbar corner radius, lift .81) instead of
 * vanilla's grey outline + black inset. The coremod redirects the {@code isSelected(j)} call inside
 * {@code GuiSlot.drawSelectionBox} to {@link #selected}: we paint the capsule for the genuinely-selected row (queried
 * through the real {@code isSelected}) and ALWAYS return {@code false}, so vanilla's selection quad is never built —
 * which sidesteps the stale-{@code WorldRenderer} corruption that biting into a filled buffer would cause (spec trap
 * #2). The capsule is drawn here, just before {@code drawSlot(j)}, so the row's icon and text land on top of it.
 *
 * <p>1.8.9 geometry matches {@code drawSelectionBox}: the caller passes {@code p_148120_2_ = top + 4 - amountScrolled}
 * and computes the row top {@code k = p_148120_2_ + index*slotHeight + headerPadding}; the quad spans {@code k-2 ..
 * k+l+2} where {@code l = slotHeight - 4}. headerPadding/slotHeight/top/left/width are public in 1.8.9; amountScrolled
 * and isSelected are read reflectively.
 */
public final class SelectionGlassHook {

    private SelectionGlassHook() {}

    private static Method mIsSelected;
    private static Field fAmount;
    private static boolean resolved;

    public static boolean selected(GuiSlot slot, int index) {
        try {
            resolve();
            if (slot == null || mIsSelected == null) return false;
            boolean sel = (Boolean) mIsSelected.invoke(slot, index);
            if (!sel) return false;                       // not selected: nothing to draw, vanilla also skips

            int slotHeight = slot.slotHeight;
            int headerPad = slot.headerPadding;
            int listWidth = slot.getListWidth();
            float amount = fAmount != null ? fAmount.getFloat(slot) : 0f;

            // Vanilla row-top maths from drawSelectionBox: insideTop = top + 4 - amountScrolled.
            int insideTop = slot.top + 4 - (int) amount;
            int k = insideTop + index * slotHeight + headerPad;    // row top
            int l = slotHeight - 4;                                 // row inner height
            int i1 = slot.left + (slot.width / 2 - listWidth / 2);
            int j1 = slot.left + slot.width / 2 + listWidth / 2;

            float x0 = i1, y0 = k - 2, x1 = j1, y1 = k + l + 2;
            GlassWidgets.capsule(x0, y0, x1, y1,
                    GlassCorners.cornerKnob(x1 - x0, y1 - y0), 0.81f, 1.0f, true);
        } catch (Throwable ignored) {
            // leave the row un-highlighted rather than crash the list; vanilla box stays suppressed either way
        }
        return false;   // vanilla never draws its own selection quad
    }

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        for (String n : new String[]{"isSelected", "func_148131_a"}) {
            try { mIsSelected = GuiSlot.class.getDeclaredMethod(n, int.class); mIsSelected.setAccessible(true); break; }
            catch (NoSuchMethodException ignored) {}
        }
        for (String n : new String[]{"amountScrolled", "field_148169_q"}) {
            try { fAmount = GuiSlot.class.getDeclaredField(n); fAmount.setAccessible(true); break; }
            catch (NoSuchFieldException ignored) {}
        }
    }
}
