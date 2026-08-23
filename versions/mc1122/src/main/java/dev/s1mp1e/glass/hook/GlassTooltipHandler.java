package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Drives {@link GlassTooltip#ghostPass()} once per frame so the tooltip fade-out
 * runs even when no tooltip is being drawn.
 *
 * <p>1.8.9 has no per-tooltip event we could tail off, so we use the overlay
 * pass, which fires every frame the world is shown (including while a container
 * screen is open — the HUD renders behind the screen). The ghost pass fires
 * BEFORE the screen's own {@code drawScreen} in the same frame, which means:
 * while a tooltip keeps drawing, {@code ghostPass} always sees the "active"
 * flag the previous frame set and skips; once the tooltip stops, the flag stays
 * clear and the panel decays {@code alpha} to 0 — resetting the state machine so
 * the next appear snaps in fresh rather than morphing from a stale box.
 *
 * <p>We deliberately do NOT drive this (or draw tooltips) from
 * {@code GuiScreenEvent.DrawScreenEvent.Post}: the real tooltip draw is the
 * {@link GlassTooltip#draw} helper called in place of vanilla
 * {@code GuiUtils.drawHoveringText}, and the Post hook cannot intercept it.
 */
public final class GlassTooltipHandler {

    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        GlassTooltip.ghostPass();
    }
}
