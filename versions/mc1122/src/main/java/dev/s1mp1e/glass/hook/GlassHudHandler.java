package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Glass hotbar for 1.8.9 — the counterpart of LiquidGlass26's HudHotbarMixin,
 * geometry included.
 *
 * <p>26.2's measured layout, reproduced here: the whole bar is scaled by
 * {@link #SCALE} about its bottom-centre, the strip spans {@code centre±91} and
 * is 22 tall with a 20 px slot pitch, and the selector is an 18x18 square that
 * therefore clears by exactly 2 px on every side — including the strip ends at
 * slots 0 and 8. The health/armour/XP decorations are lifted by
 * {@link #DECO_LIFT} so they clear the enlarged bar.
 *
 * <p>The backdrop is grabbed at the very start of the overlay pass (world drawn,
 * no GUI yet) so the glass never samples itself — the 1.8.9 equivalent of 26.2's
 * grab at GuiRenderer.render() HEAD.
 *
 * <p>The selector rides the two-spring rig measured off real Apple footage
 * (lead omega 55 / trail omega 30, critically damped) which produced the ~130 ms
 * no-bounce slide; the pill spans lead..trail so a fast scroll stretches it.
 */
public final class GlassHudHandler {

    /** Proportional upscale of the whole hotbar, anchored bottom-centre. */
    private static final float SCALE     = 1.15f;
    /** How far the health/armour/XP row lifts to clear the enlarged bar. */
    private static final int   DECO_LIFT = 7;

    private Spring  lead, trail;
    private int     lastSlot = -1;
    private long    lastNanos;
    private boolean decoPushed;

    // ---- backdrop grab: earliest point in the overlay pass ----------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onOverlayPre(RenderGameOverlayEvent.Pre e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        SceneCapture.grab();
    }

    // ---- lift the decorations so they clear the scaled bar ----------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDecoPre(RenderGameOverlayEvent.Pre e) {
        if (!isDecoration(e.getType())) return;
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, -DECO_LIFT, 0f);
        decoPushed = true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDecoPost(RenderGameOverlayEvent.Post e) {
        if (!isDecoration(e.getType()) || !decoPushed) return;
        GlStateManager.popMatrix();
        decoPushed = false;
    }

    private static boolean isDecoration(RenderGameOverlayEvent.ElementType t) {
        return t == RenderGameOverlayEvent.ElementType.HEALTH
            || t == RenderGameOverlayEvent.ElementType.ARMOR
            || t == RenderGameOverlayEvent.ElementType.FOOD
            || t == RenderGameOverlayEvent.ElementType.AIR
            || t == RenderGameOverlayEvent.ElementType.EXPERIENCE;
    }

    // ---- replace the vanilla hotbar --------------------------------------

    @SubscribeEvent
    public void onHotbar(RenderGameOverlayEvent.Pre e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.HOTBAR) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return;
        if (!SceneCapture.hasBackdrop()) return;

        ScaledResolution sr = new ScaledResolution(mc);
        int center = sr.getScaledWidth() / 2;
        int bottom = sr.getScaledHeight();

        // fade-out ghosts of a just-closed container, drawn in HUD space
        PanelGhost.drawGhosts();

        // Proportional upscale of the whole bar (glass AND items follow the
        // matrix), anchored at the bar's bottom-centre — same as 26.2's pose.
        GlStateManager.pushMatrix();
        GlStateManager.translate(center, bottom, 0f);
        GlStateManager.scale(SCALE, SCALE, 1f);
        GlStateManager.translate(-center, -bottom, 0f);
        try {
            int stripX0 = center - 91;
            int stripY0 = bottom - 22;
            int stripX1 = center + 91;
            int stripY1 = bottom;

            // frosted base strip — corner 1 gives the capsule ends
            GlassRenderer.glass(stripX0, stripY0, stripX1, stripY1,
                                GlassRenderer.PAD_PILL, 1.0f, 0f, 1.0f,
                                GlassRenderer.FROST_PANEL);

            // selector: measured Apple liquid slide
            int   slot        = mc.player.inventory.currentItem;
            float slotCenterX = center - 80f + slot * 20f;
            long  now = System.nanoTime();
            float dt  = (lastNanos == 0L) ? (1f / 60f)
                                          : Math.min(0.1f, (now - lastNanos) * 1e-9f);
            lastNanos = now;
            if (lead == null) {
                lead     = new Spring(slotCenterX, Spring.OMEGA_SNAP, Spring.DAMPING);
                trail    = new Spring(slotCenterX, Spring.OMEGA_MED,  Spring.DAMPING);
                lastSlot = slot;
            } else if (slot != lastSlot) {
                lead.setTarget(slotCenterX);
                trail.setTarget(slotCenterX);
                lastSlot = slot;
            }
            lead.advance(dt);
            trail.advance(dt);

            float lo = Math.min(lead.value(), trail.value());
            float hi = Math.max(lead.value(), trail.value());
            // SQUARE 18x18 at rest -> exactly 2px clearance on all four sides
            int pillX0 = Math.round(lo - 9f);
            int pillX1 = Math.round(hi + 9f);
            int pillY0 = bottom - 20;
            int pillY1 = bottom - 2;
            GlassRenderer.glass(pillX0, pillY0, pillX1, pillY1,
                                6f, 1.0f, 0.12f, 1.0f, GlassRenderer.FROST_NONE);

            // Vanilla's renderHotbar draws the widget sprite AND the items in
            // one call, so cancelling it takes the items with it — draw them back.
            renderItems(mc, stripX0, stripY0, e.getPartialTicks());
        } finally {
            GlStateManager.popMatrix();
        }
        e.setCanceled(true);
    }

    /** Re-draw the hotbar item stacks that the cancelled vanilla pass owned. */
    private static void renderItems(Minecraft mc, int x0, int y0, float partialTicks) {
        RenderItem ri = mc.getRenderItem();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        for (int i = 0; i < 9; i++) {
            // 1.12.2: mainInventory is a NonNullList and empty slots hold
            // ItemStack.EMPTY rather than null.
            ItemStack stack = mc.player.inventory.mainInventory.get(i);
            if (stack.isEmpty()) continue;
            int ix = x0 + 3 + i * 20;
            int iy = y0 + 3;

            // vanilla's pop animation when a slot's contents change
            float pop = stack.getAnimationsToGo() - partialTicks;
            if (pop > 0f) {
                float s = 1f + pop / 5f;
                GlStateManager.pushMatrix();
                GlStateManager.translate(ix + 8f, iy + 12f, 0f);
                GlStateManager.scale(1f / s, (s + 1f) / 2f, 1f);
                GlStateManager.translate(-(ix + 8f), -(iy + 12f), 0f);
            }
            ri.renderItemAndEffectIntoGUI(stack, ix, iy);
            if (pop > 0f) GlStateManager.popMatrix();

            ri.renderItemOverlayIntoGUI(mc.fontRenderer, stack, ix, iy, null);
        }

        GlStateManager.disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
    }
}
