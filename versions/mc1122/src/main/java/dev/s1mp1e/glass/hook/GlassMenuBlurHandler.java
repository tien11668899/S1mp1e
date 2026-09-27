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
 *
 * <p>1.12.2 posts {@code BackgroundDrawnEvent} from {@code drawWorldBackground}
 * only when {@code world != null}, so the world-less path (title screen, the
 * GuiSlot list screens) never reaches this handler — that side is owned by
 * {@code MenuBackdropHook} / the panorama capture.
 */
public final class GlassMenuBlurHandler {

    /** In-world blur is a touch softer than the menu panorama; the gradient is
     *  already baked into the grab, so Dim is 0 here. */
    private static final float RADIUS_INWORLD = 14f;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBackgroundDrawn(GuiScreenEvent.BackgroundDrawnEvent e) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.world == null) return;                    // world-less: GuiSlot/dirt path owns it
            if (e.getGui() instanceof GuiContainer) return;  // containers keep the plain gradient
            if (!GlassProgram.ensureReady() || !GlassProgram.blurUsable()) return;
            MenuBackdrop.drawLive(RADIUS_INWORLD, 0f);
        } catch (Throwable t) {
            // Never take the frame down over the blur; fall back to the gradient.
            System.out.println("[S1mp1e] in-world menu blur failed: " + t);
        }
    }

    // ---- keep the world-less backdrop panorama animating --------------------

    /**
     * Drive {@code GuiMainMenu.panoramaTimer} while another world-less screen is
     * on top of the title screen, so the backdrop keeps drifting instead of
     * freezing the moment you open Options. {@link MenuBackdrop} also latches the
     * live {@code GuiMainMenu} here.
     *
     * <p>Render tick, not client tick: on 1.12.2 {@code panoramaTimer} is a
     * {@code float} that {@code GuiMainMenu.drawScreen} advances by
     * {@code partialTicks} once per FRAME (1.8.9's was an int stepped once per
     * 20 Hz tick in {@code updateScreen}). Driving it from
     * {@code ClientTickEvent} would pan several times slower than vanilla.
     */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.currentScreen instanceof GuiMainMenu) {
                MenuBackdrop.rememberMenu((GuiMainMenu) mc.currentScreen);
            }
            MenuBackdrop.tickPanorama();
        } catch (Throwable t) {
            // cosmetic only: a missed frame just makes the pan step
            System.out.println("[S1mp1e] panorama tick failed: " + t);
        }
    }
}
