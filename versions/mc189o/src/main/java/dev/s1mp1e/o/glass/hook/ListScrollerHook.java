package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.client.gui.AppleScroller;
import net.minecraft.client.gui.widget.ListWidget;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * allglass #1/#25 — the macOS overlay scroller for every {@link ListWidget} list (world select, servers, resource packs,
 * language, every options sub-list). The coremod redirects the single {@code getMaxScroll()} (getMaxScroll) call
 * inside {@code ListWidget.render} to {@link #killMax} (&rarr; 0), which drops vanilla's three grey scrollbar quads
 * (the {@code if (j1 > 0)} block), then splices {@link #draw} onto the method's RETURN. We recompute the knob with
 * vanilla's exact maths so dragging — still handled by the untouched {@code ListWidget.handleMouse} over the real
 * scrollbar x — stays 1:1 with the drawn knob.
 *
 * <p>1.8.9 note: {@code getMaxScroll} is unmapped in {@code stable_22}, so it is {@code getMaxScroll()} in both dev
 * and production. The direct call below sees the REAL value (only the one call physically inside drawScreen is
 * redirected by the transformer).
 */
public final class ListScrollerHook {

    private ListScrollerHook() {}

    /** Redirect target for the ONE {@code getMaxScroll()} inside drawScreen: forces the vanilla scrollbar branch off. */
    public static int killMax(ListWidget slot) { return 0; }

    private static Method mScrollBarX;
    private static Field fAmount;
    private static boolean resolvedX, resolvedAmt;

    public static void draw(ListWidget slot, int mouseX, int mouseY) {
        try {
            if (slot == null) return;
            int top = slot.minY, bottom = slot.maxY;
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

    private static int scrollBarX(ListWidget slot) {
        if (!resolvedX) {
            resolvedX = true;
            for (String n : new String[]{"getScrollBarX", dev.s1mp1e.o.util.Names.of("getScrollbarPosition", "m_11670522")}) {
                try { mScrollBarX = ListWidget.class.getDeclaredMethod(n); mScrollBarX.setAccessible(true); break; }
                catch (NoSuchMethodException ignored) {}
            }
        }
        if (mScrollBarX != null) {
            try { return (Integer) mScrollBarX.invoke(slot); } catch (Throwable ignored) {}
        }
        return slot.width / 2 + 124;   // base ListWidget formula
    }

    /** amountScrolled is protected, so read it reflectively (MCP in dev, SRG in production). */
    static float amountScrolled(ListWidget slot) {
        if (!resolvedAmt) {
            resolvedAmt = true;
            for (String n : new String[]{"amountScrolled", dev.s1mp1e.o.util.Names.of("scrollAmount", "f_41255119")}) {
                try { fAmount = ListWidget.class.getDeclaredField(n); fAmount.setAccessible(true); break; }
                catch (NoSuchFieldException ignored) {}
            }
        }
        if (fAmount != null) {
            try { return fAmount.getFloat(slot); } catch (Throwable ignored) {}
        }
        return 0f;
    }
}
