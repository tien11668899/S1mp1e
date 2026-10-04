package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.render.ScreenDissolve;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Starts the menu-to-menu cross-dissolve ({@link ScreenDissolve}) on every real screen change. {@link GuiOpenEvent}
 * is posted at the head of {@code Minecraft.displayGuiScreen}, while {@code currentScreen} is still the OUTGOING screen
 * and the main framebuffer still holds its last finished frame — exactly what is snapshot. LOWEST priority so the
 * event's final screen is what we compare against. A resize re-sets the identical instance and must not dissolve.
 * The snapshot is DRAWN by {@link GlassTopLayer} (the frame's top layer).
 */
public final class GlassScreenFadeHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGuiOpen(GuiOpenEvent e) {
        if (e.isCanceled()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen == e.getGui()) return;
        // DEV-only diagnostics (DevShot active): who closes a settings page?
        if (e.getGui() == null && dev.s1mp1e.client.gui.SettingsShell.handles(mc.currentScreen)
                && System.getenv("S1MP1E_SHOT") != null) {
            new Throwable("[S1mp1e][diag] settings page closed").printStackTrace(System.out);
        }
        try {
            ScreenDissolve.onSetScreen(mc.currentScreen, e.getGui());
        } catch (Throwable t) {
            // cosmetic: a failed snapshot is just a hard cut
        }
    }
}
