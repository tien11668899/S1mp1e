package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.render.ScreenDissolve;
import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * The very top GUI layer of every frame (design rule R1 + group 5). In 1.12.2's game loop
 * {@code updateCameraAndRender} (world, HUD, screen + its tooltip) is followed by {@code toastGui.drawToast} and only
 * then by {@code RenderTickEvent} END, with the main framebuffer still bound — so this is the one point above the
 * toasts. In order:
 * <ol>
 *   <li>the glass tooltip recorded during the screen pass ({@link GlassTooltip#drawDeferred}) — so the tooltip card
 *       sits above toasts, items, everything;</li>
 *   <li>the tooltip fade-out ghost ({@link GlassTooltip#ghostPass}) — every frame, also over the title screen;</li>
 *   <li>a running screen cross-dissolve snapshot ({@link ScreenDissolve#draw}) over all of it.</li>
 * </ol>
 * HIGHEST priority so it lands before DevShot's capture (same event, normal priority).
 */
public final class GlassTopLayer {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.entityRenderer == null) return;
        boolean any = GlassTooltip.hasDeferred() || GlassTooltip.ghostVisible() || ScreenDissolve.active();
        if (!any) return;
        try {
            mc.entityRenderer.setupOverlayRendering();
            GlassTooltip.drawDeferred();
            GlassTooltip.ghostPass();
            ScreenDissolve.draw();
        } catch (Throwable t) {
            // cosmetic top layer: never disturb the frame
        }
    }
}
