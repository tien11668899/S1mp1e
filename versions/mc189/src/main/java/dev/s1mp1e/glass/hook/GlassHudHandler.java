package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.PanelGhost;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
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
    /** Lift the whole hotbar off the bottom screen edge (26.2's {@code guiHeight-4}). */
    public static final int   LIFT      = 4;
    /** How far the health/armour/XP row lifts to clear the enlarged bar.
     *  Kept at 26.2's measured 7 (the glass source of truth — the user fixed this
     *  value; mc1211 diverged to LIFT+4=8 but 26.2 wins here); public so other
     *  HUD packages can read it. */
    public static final int   DECO_LIFT = 7;

    /** Bare {@link Gui} subclass just to reach {@code drawTexturedModalRect} for the
     *  XP bar sprites (the method is protected on Gui). */
    private static final class Blit extends Gui {
        void rect(int x, int y, int u, int v, int w, int h) { drawTexturedModalRect(x, y, u, v, w, h); }
    }
    private static final Blit           XP_BLIT = new Blit();
    private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");

    private Spring  lead, trail;
    private int     lastSlot = -1;
    private long    lastNanos;
    private boolean decoPushed;

    // ---- backdrop grab: earliest point in the overlay pass ----------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onOverlayPre(RenderGameOverlayEvent.Pre e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return;
        // Safety drain: onDecoPre pushes at HIGHEST, before any other mod can cancel
        // Pre(HEALTH/ARMOR/FOOD/AIR). A cancel means GuiIngameForge never fires the
        // matching Post, so our push would be stranded and the GL matrix stack would
        // grow one level per frame until GL_STACK_OVERFLOW. Pop any leftover here, at
        // the head of the next overlay pass (the GL stack survives between frames).
        if (decoPushed) {
            GlStateManager.popMatrix();
            decoPushed = false;
        }
        // Earliest point in the overlay pass: open the frame BEFORE anything can
        // grab, so per-frame freshness checks mean what they say.
        SceneCapture.newFrame();
        SceneCapture.grabOnce();   // world only — shared with the item-name popup
    }

    // ---- lift the decorations so they clear the scaled bar ----------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDecoPre(RenderGameOverlayEvent.Pre e) {
        // EXPERIENCE is fully owned here (the number is moved to the health/food row, so
        // vanilla's bar+number pair is cancelled and redrawn) — cancelling Pre also means
        // GuiIngameForge never fires Post(EXPERIENCE), so we must push AND pop in this call.
        if (e.type == RenderGameOverlayEvent.ElementType.EXPERIENCE) {
            renderExperience(e);
            return;
        }
        if (!isDecoration(e.type)) return;
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, -DECO_LIFT, 0f);
        decoPushed = true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDecoPost(RenderGameOverlayEvent.Post e) {
        if (!isDecoration(e.type) || !decoPushed) return;
        GlStateManager.popMatrix();
        decoPushed = false;
    }

    private static boolean isDecoration(RenderGameOverlayEvent.ElementType t) {
        return t == RenderGameOverlayEvent.ElementType.HEALTH
            || t == RenderGameOverlayEvent.ElementType.ARMOR
            || t == RenderGameOverlayEvent.ElementType.FOOD
            || t == RenderGameOverlayEvent.ElementType.AIR;
    }

    /**
     * Own the XP element: lift it by {@link #DECO_LIFT} with the rest of the cluster, draw
     * the vanilla XP bar at its normal spot, but move the LEVEL number up onto the
     * health/food row (screen-centred, so it sits in the gap between the health bar on the
     * left and the food bar on the right). Same look as vanilla — black 4-way outline +
     * green {@code 0x80FF20} centre, no shadow. Then cancel vanilla's own EXPERIENCE draw.
     */
    private void renderExperience(RenderGameOverlayEvent.Pre e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.playerController == null) return;  // let vanilla handle it

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(0f, -DECO_LIFT, 0f);
            ScaledResolution sr = new ScaledResolution(mc);
            int width  = sr.getScaledWidth();
            int height = sr.getScaledHeight();

            GlStateManager.color(1f, 1f, 1f, 1f);
            // allglass #17: the XP bar as a glass capsule track + round-ended green fill (ContextualBarHook). This
            // handler owns the XP element (vanilla's is cancelled), so GuiIngameForge.renderExperience — where the
            // coremod redirects the jump bar's blits — never runs for XP: route our own two blits through the hook.
            // Glass needs blend ON (the old icons-sheet strip was drawn opaque). Drawn at vanilla's spot: the push
            // above lifts it with the cluster and the glass follows the GL model-view (never subtract the lift).
            GlStateManager.enableBlend();

            if (mc.playerController.gameIsSurvivalOrAdventure()) {
                mc.getTextureManager().bindTexture(ICONS);
                int cap = mc.thePlayer.xpBarCap();
                int left = width / 2 - 91;
                if (cap > 0) {
                    int filled = (int) (mc.thePlayer.experience * 183f);
                    int top = height - 32 + 3;                       // vanilla XP bar top = h-29
                    ContextualBarHook.bar(XP_BLIT, left, top, 0, 64, 182, 5);
                    if (filled > 0) ContextualBarHook.bar(XP_BLIT, left, top, 0, 69, filled, 5);
                }
                if (mc.thePlayer.experienceLevel > 0) {
                    String s = "" + mc.thePlayer.experienceLevel;
                    int tx = (width - mc.fontRendererObj.getStringWidth(s)) / 2;
                    int ty = height - 39;                            // health/food row (lifted by the push)
                    mc.fontRendererObj.drawString(s, tx + 1, ty, 0);
                    mc.fontRendererObj.drawString(s, tx - 1, ty, 0);
                    mc.fontRendererObj.drawString(s, tx, ty + 1, 0);
                    mc.fontRendererObj.drawString(s, tx, ty - 1, 0);
                    mc.fontRendererObj.drawString(s, tx, ty, 0x80FF20);
                }
            }

            // Leave GL the way MC expects for the elements after us: blend on, colour cache
            // white (the font renderer left the real colour tinted). NEVER disableBlend on exit.
            GlStateManager.enableBlend();
            GlStateManager.color(0f, 0f, 0f, 0f);
            GlStateManager.color(1f, 1f, 1f, 1f);
        } finally {
            GlStateManager.popMatrix();
        }
        e.setCanceled(true);
    }

    // ---- replace the vanilla hotbar --------------------------------------

    /**
     * allglass #15: Forge posts the F3 text lists in this event right before {@code renderHUDText} draws them. Hand
     * them (after every other listener, hence LOWEST) to {@link DebugCardHook} so it can paint ONE rounded plate per
     * group of consecutive lines instead of a strip per line.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDebugText(RenderGameOverlayEvent.Text e) {
        if (e.isCanceled()) { DebugCardHook.cancel(); return; }
        DebugCardHook.prepare(e.left, e.right, e.resolution.getScaledWidth());
    }

    @SubscribeEvent
    public void onHotbar(RenderGameOverlayEvent.Pre e) {
        if (e.type != RenderGameOverlayEvent.ElementType.HOTBAR) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        // Spectators get vanilla's spectator menu, not the glass hotbar; and a
        // non-player render view (camera entity, third-party mob) has no hotbar.
        // GuiIngameForge fires Pre(HOTBAR) BEFORE its own isSpectator branch, so
        // without this the glass bar + player items would replace the spectator
        // menu. Leave the event uncancelled so vanilla draws its own GUI.
        if (mc.playerController != null && mc.playerController.isSpectator()) return;
        if (!(mc.getRenderViewEntity() instanceof net.minecraft.entity.player.EntityPlayer)) return;
        if (!SceneCapture.hasBackdrop()) return;

        ScaledResolution sr = new ScaledResolution(mc);
        int center = sr.getScaledWidth() / 2;
        // Lift the whole bar off the bottom edge (26.2 pose: guiHeight - LIFT). Strip,
        // selector and items all derive from `bottom`, so they move together.
        int bottom = sr.getScaledHeight() - LIFT;

        // NOTE: the close-fade ghost is drawn ONCE per frame, from
        // GlassContainerHandler's Post(ALL). Drawing it here as well composited it
        // twice with slightly different alpha, which made the fade judder — and
        // this pass runs BEFORE the screen pass that cancels a stale ghost, so on a
        // container->container switch the old panel flashed for one frame.

        // Proportional upscale of the whole bar (glass AND items follow the
        // matrix), anchored at the bar's bottom-centre — same as 26.2's pose.
        // Suppress the glass drop-shadow ring while a screen dims the background (pause
        // menu, inventory, chat), so the hotbar leaves no black halo. Restored in finally.
        boolean screenOpen = mc.currentScreen != null;

        GlStateManager.pushMatrix();
        GlStateManager.translate(center, bottom, 0f);
        GlStateManager.scale(SCALE, SCALE, 1f);
        GlStateManager.translate(-center, -bottom, 0f);
        if (screenOpen) GlassProgram.setShadowScale(0f);
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
            int   slot        = mc.thePlayer.inventory.currentItem;
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
            renderItems(mc, stripX0, stripY0, e.partialTicks);
        } finally {
            if (screenOpen) GlassProgram.setShadowScale(1f);
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
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack == null) continue;
            int ix = x0 + 3 + i * 20;
            int iy = y0 + 3;

            // vanilla's pop animation when a slot's contents change
            float pop = stack.animationsToGo - partialTicks;
            if (pop > 0f) {
                float s = 1f + pop / 5f;
                GlStateManager.pushMatrix();
                GlStateManager.translate(ix + 8f, iy + 12f, 0f);
                GlStateManager.scale(1f / s, (s + 1f) / 2f, 1f);
                GlStateManager.translate(-(ix + 8f), -(iy + 12f), 0f);
            }
            ri.renderItemAndEffectIntoGUI(stack, ix, iy);
            if (pop > 0f) GlStateManager.popMatrix();

            ri.renderItemOverlayIntoGUI(mc.fontRendererObj, stack, ix, iy, null);
        }

        GlStateManager.disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
        // RenderItem leaves the real GL colour wherever the last item's overlay put
        // it while GlStateManager's cache still reads white, so a plain
        // color(1,1,1,1) would no-op (same trap as GlassRenderer.endBatch). Force
        // the cache so the HUD decorations drawn after us are not tinted.
        GlStateManager.color(0f, 0f, 0f, 0f);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
