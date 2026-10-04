package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.client.gui.BrandIntro;
import dev.s1mp1e.o.event.GuiOpenEvent;
import dev.s1mp1e.o.event.EventPriority;
import dev.s1mp1e.o.event.SubscribeEvent;

/**
 * Shows the S1mp1e brand intro once per session, right before the first title screen (group 10, 1.12.2 adaptation —
 * see {@link BrandIntro}). HIGH priority so the screen-change dissolve (LOWEST) already sees the swapped screen; at boot
 * there is no world and no finished frame, so no dissolve runs into the intro, and the intro's hand-over to the title
 * IS dissolved.
 */
public final class BrandIntroHandler {

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onGuiOpen(GuiOpenEvent e) {
        try {
            e.gui = BrandIntro.maybeIntro(e.gui);
        } catch (Throwable ignored) {}
    }
}
