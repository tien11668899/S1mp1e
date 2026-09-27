package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * The 1.21 in-world blur behind non-container screens (pause menu, Options, and
 * the world-less list screens reached in-world). Vanilla's
 * {@code GuiScreen.drawWorldBackground} only lays down the
 * {@code 0xC0101010→0xD0101010} darken gradient; here, right after that gradient
 * is drawn ({@link GuiScreenEvent.BackgroundDrawnEvent}), the current frame — the
 * world plus that gradient — is grabbed and redrawn blurred, so the paused world
 * reads like 1.21's blurred backdrop instead of a flat dim.
 *
 * <p>Containers are excluded (they own their glass panel and keep the plain
 * gradient, matching mc1211's HandledScreen). Registered BEFORE
 * {@link GlassContainerHandler} so the container path still wins for those.
 */
public final class GlassMenuBlurHandler {

    /** In-world blur is a touch softer than the menu panorama; the gradient is
     *  already baked into the grab, so Dim is 0 here. */
    private static final float RADIUS_INWORLD = 14f;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBackgroundDrawn(GuiScreenEvent.BackgroundDrawnEvent e) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null) return;                 // world-less: GuiSlot/dirt path owns it
            if (e.gui instanceof GuiContainer) return;       // containers keep the plain gradient
            if (!GlassProgram.ensureReady() || !GlassProgram.blurUsable()) return;
            MenuBackdrop.drawLive(RADIUS_INWORLD, 0f);
        } catch (Throwable t) {
            // Never take the frame down over the blur; fall back to the gradient.
            System.out.println("[S1mp1e] in-world menu blur failed: " + t);
        }
    }

    // ---- V-5: keep the world-less backdrop panorama animating ---------------

    /**
     * Drive {@code GuiMainMenu.panoramaTimer} while another world-less screen is
     * on top of the title screen, so the backdrop keeps drifting instead of
     * freezing the moment you open Options. {@link MenuBackdrop} also latches the
     * live {@code GuiMainMenu} here.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        try {
            MenuBackdrop.tickPanorama();
        } catch (Throwable t) {
            System.out.println("[S1mp1e] panorama tick failed: " + t);
        }
    }

    /**
     * {@code Minecraft.timer} is private in 1.8.9, so the panorama's partial ticks
     * come from the render tick — without them the pan would step at 20 Hz.
     */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        try {
            MenuBackdrop.setPartialTicks(e.renderTickTime);
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.currentScreen instanceof GuiMainMenu) {
                MenuBackdrop.rememberMenu((GuiMainMenu) mc.currentScreen);
            }
        } catch (Throwable ignored) {
            // cosmetic only: a missed partial tick just makes the pan step
        }
    }
}
