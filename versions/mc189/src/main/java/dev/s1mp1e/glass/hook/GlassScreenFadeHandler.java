package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.render.ScreenFade;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Cross-dissolves every GUI screen change — title to Singleplayer, Multiplayer,
 * Options, the in-game menu, and closing back to the world.
 *
 * <p>{@link GuiOpenEvent} only STARTS the dissolve. It fires during tick/input,
 * outside the render pass, so capturing there produced an unfilled (and
 * therefore incomplete) texture, which OpenGL renders as a flat white quad —
 * the white flash. The frame is instead captured at the end of every rendered
 * frame, from inside the render pass, so the snapshot is always last frame's
 * finished image.
 *
 * <p>Draw runs before capture within a frame, and capture pauses for the
 * duration of a dissolve, so the fade is never captured into its own snapshot.
 */
public final class GlassScreenFadeHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGuiOpen(GuiOpenEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        // Only dissolve on a real change; MC re-sets the same screen on resize
        // and that must not flash.
        if (mc.currentScreen == e.gui) return;
        ScreenFade.trigger();
    }

    /** Screen open: dissolve above the screen, then snapshot the finished frame. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onScreenPost(GuiScreenEvent.DrawScreenEvent.Post e) {
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }

    /** No screen (back in the world): dissolve above the HUD, then snapshot. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onOverlayPost(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (Minecraft.getMinecraft().currentScreen != null) return;
        ScreenFade.draw();
        ScreenFade.captureFrame();
    }
}
