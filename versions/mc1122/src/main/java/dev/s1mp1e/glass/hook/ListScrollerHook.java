package dev.s1mp1e.glass.hook;

import dev.s1mp1e.client.gui.AppleScroller;
import net.minecraft.client.gui.GuiSlot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * allglass #1/#25 — the macOS overlay scroller for every {@link GuiSlot} list (world select, servers, resource packs,
 * language, every options sub-list). The coremod redirects the single {@code getMaxScroll()} call inside
 * {@code GuiSlot.drawScreen} to {@link #killMax} (→ 0), which drops vanilla's three grey scrollbar quads, then splices
 * {@link #draw} onto the method's RETURN. We recompute the knob with vanilla's exact maths so dragging — still handled
 * by the untouched {@code GuiSlot.handleMouseInput} over the real scrollbar x — stays 1:1 with the drawn knob.
 */
public final class ListScrollerHook {

    private ListScrollerHook() {}

    /** Redirect target for the ONE {@code getMaxScroll()} inside drawScreen: forces the vanilla scrollbar branch off. */
    public static int killMax(GuiSlot slot) { return 0; }

    private static Method mScrollBarX;
    private static Field fAmount;
    private static boolean resolvedX, resolvedAmt;

    public static void draw(GuiSlot slot, int mouseX, int mouseY) {
        try {
            if (slot == null) return;
            int top = slot.top, bottom = slot.bottom;
            int max = slot.getMaxScroll();          // the REAL value (this direct call is not the redirected site)
            if (max <= 0 || bottom - top < 4) return;

            int contentH = max + (bottom - top - 4);
            if (contentH <= 0) return;
            int knobLen = (bottom - top) * (bottom - top) / contentH;
            if (knobLen < 32) knobLen = 32;
            if (knobLen > bottom - top - 8) knobLen = bottom - top - 8;
            float amount = amountScrolled(slot);
            int knobTop = (int) amount * (bottom - top - knobLen) / max + top;
            if (knobTop < top) knobTop = top;

            int barX = scrollBarX(slot);            // vanilla thumb spans [barX, barX + 6]
            float right = barX + 6f;
            boolean hover = AppleScroller.near(mouseX, mouseY, right, top, bottom);

            AppleScroller.draw(slot, right, top, bottom, knobTop, knobLen, amount, hover, false, 1.0f);
        } catch (Throwable ignored) {
            // never break a list screen over the overlay scroller
        }
    }

    private static int scrollBarX(GuiSlot slot) {
        if (!resolvedX) {
            resolvedX = true;
            for (String n : new String[]{"getScrollBarX", "func_148137_d"}) {
                try { mScrollBarX = GuiSlot.class.getDeclaredMethod(n); mScrollBarX.setAccessible(true); break; }
                catch (NoSuchMethodException ignored) {}
            }
        }
        if (mScrollBarX != null) {
            try { return (Integer) mScrollBarX.invoke(slot); } catch (Throwable ignored) {}
        }
        return slot.width / 2 + 124;   // base GuiSlot formula
    }

    /** amountScrolled is protected, so read it reflectively (MCP in dev, SRG in production). */
    static float amountScrolled(GuiSlot slot) {
        if (!resolvedAmt) {
            resolvedAmt = true;
            for (String n : new String[]{"amountScrolled", "field_148169_q"}) {
                try { fAmount = GuiSlot.class.getDeclaredField(n); fAmount.setAccessible(true); break; }
                catch (NoSuchFieldException ignored) {}
            }
        }
        if (fAmount != null) {
            try { return fAmount.getFloat(slot); } catch (Throwable ignored) {}
        }
        return 0f;
    }
}
