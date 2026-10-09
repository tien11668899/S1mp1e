package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHandSide;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Glass hotbar for 1.12.2 — the counterpart of LiquidGlass26's HudHotbarMixin,
 * geometry included (ported from the mc189 line with 1.12.2 names).
 *
 * <p>26.2's measured layout, reproduced here: the whole bar is lifted
 * {@link #LIFT} px off the bottom edge and scaled by {@link #SCALE} about its
 * bottom-centre, the strip spans {@code centre±91} and is 22 tall with a 20 px
 * slot pitch, and the selector is an 18x18 square that therefore clears by
 * exactly 2 px on every side — including the strip ends at slots 0 and 8. The
 * health/armour/food/air (and, on 1.12.2, mount-health and horse jump bar)
 * decorations and the XP bar are lifted by {@link #DECO_LIFT} so they clear the
 * enlarged bar; the XP level number moves up onto the health/food row.
 *
 * <p>1.12.2 has an off-hand: vanilla's {@code renderHotbar} also draws the
 * off-hand slot and, in "hotbar" attack-indicator mode, the attack-cooldown
 * gauge. Cancelling {@code Pre(HOTBAR)} drops both, so they are redrawn here
 * from the local player's own state only — the off-hand stack in a small glass
 * cell exactly where 26.2's HudHotbarMixin puts it, and the cooldown gauge at
 * vanilla's hotbar-mode position. The crosshair-mode indicator is never
 * reproduced here (vanilla's crosshair branch reads the pointed entity).
 *
 * <p>The backdrop is grabbed at the very start of the overlay pass (world drawn,
 * no GUI yet) so the glass never samples itself — the 1.12.2 equivalent of
 * 26.2's grab at GuiRenderer.render() HEAD. This is also the single per-frame
 * {@link SceneCapture#newFrame()} boundary.
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

    /** Plain {@link Gui} to reach {@code drawTexturedModalRect} (public on 1.12.2)
     *  for the XP-bar and attack-indicator sprites. */
    private static final Gui BLIT = new Gui();

    /** Apple-like off-hand slot fade / item-swap duration (ms). */
    private static final float OFF_FADE_MS = 180f;

    private Spring  lead, trail;
    private int     lastSlot = -1;
    private long    lastNanos;
    private boolean decoPushed;

    // ---- off-hand slot fade + item cross-swap -----------------------------
    // The off-hand cell is drawn from the glass strip's own material. When the
    // off-hand goes empty<->filled the WHOLE cell (glass + item) fades together;
    // when both hands stay filled the glass holds and only the item swaps. 1.12.2
    // GUI items bake alpha=255 into their vertices (DefaultVertexFormats.ITEM), so
    // glColor cannot alpha-fade an item — the item's half of every transition rides
    // a scale ease instead (the same trick vanilla's slot "pop" uses), synced to the
    // glass opacity so the two read as one motion.
    private final Fade offSlot = new Fade(0f, OFF_FADE_MS);   // 0 empty .. 1 filled (glass opacity)
    private final Fade offSwap = new Fade(1f, OFF_FADE_MS);   // 0..1 progress of an item cross-swap
    private ItemStack offShown = ItemStack.EMPTY;             // item currently on show
    private ItemStack offPrev  = ItemStack.EMPTY;             // outgoing item during a swap
    private ItemStack offLast  = ItemStack.EMPTY;             // last frame's raw off-hand stack

    // ---- backdrop grab: earliest point in the overlay pass ----------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onOverlayPre(RenderGameOverlayEvent.Pre e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        // Safety drain: onDecoPre pushes at HIGHEST, before any other mod can cancel
        // Pre(HEALTH/ARMOR/FOOD/AIR/...). A cancel means GuiIngameForge never fires
        // the matching Post, so our push would be stranded and the GL matrix stack
        // would grow one level per frame until GL_STACK_OVERFLOW. Pop any leftover
        // here, at the head of the next overlay pass (the GL stack survives between
        // frames).
        drainDeco();
        // Earliest point in the overlay pass: open the frame BEFORE anything can
        // grab, so per-frame freshness checks mean what they say.
        SceneCapture.newFrame();
        SceneCapture.grabOnce();   // world only — shared with the item-name popup
    }

    // ---- lift the decorations so they clear the scaled bar ----------------

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDecoPre(RenderGameOverlayEvent.Pre e) {
        RenderGameOverlayEvent.ElementType t = e.getType();
        // EXPERIENCE is fully owned here (the number is moved to the health/food row, so
        // vanilla's bar+number pair is cancelled and redrawn) — cancelling Pre also means
        // GuiIngameForge never fires Post(EXPERIENCE), so we must push AND pop in this call.
        if (t == RenderGameOverlayEvent.ElementType.EXPERIENCE) {
            drainDeco();
            renderExperience(e);
            return;
        }
        if (!isDecoration(t)) return;
        // The decoration elements are strictly sequential (Pre..Post, never nested),
        // so a push still open here belongs to an earlier element whose Pre another
        // mod cancelled after us: pop it now so the stack never grows.
        drainDeco();
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

    /** Pop a deco push whose Post never came. */
    private void drainDeco() {
        if (decoPushed) {
            GlStateManager.popMatrix();
            decoPushed = false;
        }
    }

    private static boolean isDecoration(RenderGameOverlayEvent.ElementType t) {
        return t == RenderGameOverlayEvent.ElementType.HEALTH
            || t == RenderGameOverlayEvent.ElementType.ARMOR
            || t == RenderGameOverlayEvent.ElementType.FOOD
            || t == RenderGameOverlayEvent.ElementType.AIR
            // 1.12.2: mount health takes the food row while riding, and the horse
            // jump bar takes the XP bar's spot — both would sit under the
            // enlarged bar, so they lift with the rest of the cluster.
            || t == RenderGameOverlayEvent.ElementType.HEALTHMOUNT
            || t == RenderGameOverlayEvent.ElementType.JUMPBAR;
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
        if (mc.player == null || mc.playerController == null) return;  // let vanilla handle it

        ScaledResolution sr = e.getResolution() != null ? e.getResolution() : new ScaledResolution(mc);
        int width  = sr.getScaledWidth();
        int height = sr.getScaledHeight();

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(0f, -DECO_LIFT, 0f);
            GlStateManager.color(1f, 1f, 1f, 1f);

            if (mc.playerController.gameIsSurvivalOrAdventure()) {
                // allglass #17: the XP bar as a glass capsule track + round-ended green fill (ContextualBarHook). This
                // handler owns the XP element (vanilla's is cancelled), so GuiIngameForge.renderExperience — where the
                // coremod redirects the jump bar's blits — never runs for XP: route our own two blits through the hook.
                // Drawn at vanilla's spot: the push above lifts it with the cluster and the glass follows the GL
                // model-view (never subtract the lift). The icons sheet stays bound for the hook's vanilla fallback.
                mc.getTextureManager().bindTexture(Gui.ICONS);
                int cap = mc.player.xpBarCap();
                int left = width / 2 - 91;
                if (cap > 0) {
                    int filled = (int) (mc.player.experience * 183f);
                    int top = height - 32 + 3;                       // vanilla XP bar top = h-29
                    ContextualBarHook.bar(BLIT, left, top, 0, 64, 182, 5);
                    if (filled > 0) ContextualBarHook.bar(BLIT, left, top, 0, 69, filled, 5);
                }
                GlStateManager.enableBlend();
                GlStateManager.color(1f, 1f, 1f, 1f);

                FontRenderer fr = mc.fontRenderer;
                if (mc.player.experienceLevel > 0 && fr != null) {
                    String s = "" + mc.player.experienceLevel;
                    int tx = (width - fr.getStringWidth(s)) / 2;
                    int ty = height - 39;                            // health/food row (lifted by the push)
                    fr.drawString(s, tx + 1, ty, 0);
                    fr.drawString(s, tx - 1, ty, 0);
                    fr.drawString(s, tx, ty + 1, 0);
                    fr.drawString(s, tx, ty - 1, 0);
                    fr.drawString(s, tx, ty, 0x80FF20);
                }
            }
        } finally {
            // Leave GL the way MC expects for the elements after us: blend on, colour
            // cache white (the font renderer left the real colour tinted). Blend is
            // never left off on any exit path.
            GlStateManager.enableBlend();
            GlStateManager.color(0f, 0f, 0f, 0f);
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.popMatrix();
        }
        e.setCanceled(true);
    }

    // ---- replace the vanilla hotbar --------------------------------------

    /**
     * allglass #15: Forge posts the F3 text lists in this event right before {@code renderHUDText} draws them. Hand
     * them (after every other listener, hence LOWEST) to {@link DebugCardHook} so it can paint ONE rounded plate per
     * group of consecutive lines instead of a strip per line. On 1.12.2 the F3 text is drawn by
     * {@code GuiIngameForge.renderHUDText} (its {@code GuiOverlayDebugForge} empties the vanilla left/right methods),
     * so the coremod redirects that method's per-line {@code drawRect}s into {@link DebugCardHook#rect}.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDebugText(RenderGameOverlayEvent.Text e) {
        if (e.isCanceled()) { DebugCardHook.cancel(); return; }
        DebugCardHook.prepare(e.getLeft(), e.getRight(), e.getResolution().getScaledWidth());
    }

    @SubscribeEvent
    public void onHotbar(RenderGameOverlayEvent.Pre e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.HOTBAR) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return;
        // Spectators get vanilla's spectator menu, not the glass hotbar; and a
        // non-player render view (camera entity, third-party mob) has no hotbar.
        // GuiIngameForge fires Pre(HOTBAR) BEFORE its own isSpectator branch, so
        // without this the glass bar + player items would replace the spectator
        // menu. Leave the event uncancelled so vanilla draws its own GUI.
        if (mc.playerController != null && mc.playerController.isSpectator()) return;
        Entity view = mc.getRenderViewEntity();
        if (!(view instanceof EntityPlayer)) return;
        // Glass pipeline down -> keep vanilla's hotbar instead of an empty strip.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        if (!SceneCapture.hasBackdrop()) return;
        EntityPlayer player = (EntityPlayer) view;

        ScaledResolution sr = e.getResolution() != null ? e.getResolution() : new ScaledResolution(mc);
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
            int   slot        = player.inventory.currentItem;
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

            // Off-hand cell (26.2 HudHotbarMixin geometry): the off hand sits
            // opposite the main arm. Per the off-hand spec the cell uses the SAME
            // material / shape / rounding as the hotbar strip (PAD_PILL, corner 1.0,
            // FROST_PANEL) — NOT a square box — so it reads as one extra hotbar slot:
            // 22x22, aligned to the strip row with a 4 px gap. Item inset 3 px,
            // exactly like the main slots.
            ItemStack offhand = player.getHeldItemOffhand();
            EnumHandSide offSide = player.getPrimaryHand().opposite();
            boolean offLeft = offSide == EnumHandSide.LEFT;
            int offBoxX0, offBoxX1;
            if (offLeft) { offBoxX1 = stripX0 - 4; offBoxX0 = offBoxX1 - 22; }
            else         { offBoxX0 = stripX1 + 4; offBoxX1 = offBoxX0 + 22; }
            int offItemX = offBoxX0 + 3;
            int offItemY = stripY0 + 3;

            // Advance the off-hand fade/swap state machine once per frame.
            updateOffHand(offhand);

            // Glass half of the transition: the cell fades its opacity in/out. The
            // exact same glass() call as the strip above (PAD_PILL, corner 1.0,
            // FROST_PANEL) — only the animated opacity differs, so the slot is the
            // hotbar's own material and rounding, never a square box.
            float slotA = offSlot.value();
            if (slotA > 0.004f) {
                GlassRenderer.glass(offBoxX0, stripY0, offBoxX1, stripY1,
                                    GlassRenderer.PAD_PILL, 1.0f, 0f, slotA,
                                    GlassRenderer.FROST_PANEL);
            }

            // Vanilla's renderHotbar draws the widget sprite AND the items in
            // one call, so cancelling it takes the items with it — draw them back
            // (the 9 main slots + the animated off-hand item).
            renderItems(mc, player, stripX0, stripY0, offItemX, offItemY,
                        slotA, offSwap.value(), e.getPartialTicks());

            // Hotbar-mode attack-cooldown gauge (own cooldown only).
            renderAttackIndicator(mc, center, bottom, offSide);
        } finally {
            if (screenOpen) GlassProgram.setShadowScale(1f);
            GlStateManager.popMatrix();
        }
        e.setCanceled(true);
    }

    /**
     * Re-draw the item stacks the cancelled vanilla pass owned: the 9 main slots,
     * then the animated off-hand item.
     *
     * <p>{@code slotA} is the off-hand cell's fade value (0..1) and {@code swapT} the
     * item cross-swap progress (0..1). The off-hand item rides a scale ease driven by
     * these — see {@link #drawOffHandItem}.
     */
    private void renderItems(Minecraft mc, EntityPlayer player, int x0, int y0,
                             int offX, int offY, float slotA, float swapT, float partialTicks) {
        RenderItem ri = mc.getRenderItem();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        try {
            for (int i = 0; i < 9; i++) {
                // 1.12.2: mainInventory is a NonNullList and empty slots hold
                // ItemStack.EMPTY rather than null.
                renderSlotItem(mc, ri, player, player.inventory.mainInventory.get(i),
                               x0 + 3 + i * 20, y0 + 3, partialTicks);
            }
            drawOffHandItem(mc, ri, player, offX, offY, slotA, swapT);
        } finally {
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

    /**
     * Off-hand item, with the transition motion. Three cases, all sharing one 22x22
     * cell centred on ({@code ix}+8, {@code iy}+8):
     * <ul>
     *   <li>steady (swap settled): the shown item at a scale that eases 0.7..1.0 with
     *       the cell's fade, so on an empty&lt;-&gt;filled transition item and glass
     *       grow/shrink together;</li>
     *   <li>cross-swap (both hands filled, item changed): the outgoing item shrinks
     *       to nothing over the first half, the incoming grows from nothing over the
     *       second — a scale cross-dissolve while the glass cell holds full.</li>
     * </ul>
     * The item's scale ease is the only channel available: 1.12.2 bakes alpha=255 into
     * item vertices, so a true alpha fade is impossible without reimplementing the item
     * pipeline.
     */
    private void drawOffHandItem(Minecraft mc, RenderItem ri, EntityPlayer player,
                                 int ix, int iy, float slotA, float swapT) {
        if (slotA <= 0.02f) return;
        float grow = ease(slotA);           // item grows/shrinks in step with the glass cell
        if (swapT < 1f && !offPrev.isEmpty()) {
            // scale cross-swap: old out (first half), new in (second half)
            if (swapT < 0.5f) {
                float s = 1f - swapT / 0.5f;
                drawScaledItem(mc, ri, player, offPrev, ix, iy, grow * ease(s));
            } else {
                float s = (swapT - 0.5f) / 0.5f;
                drawScaledItem(mc, ri, player, offShown, ix, iy, grow * ease(s));
            }
        } else {
            drawScaledItem(mc, ri, player, offShown, ix, iy, grow);
        }
    }

    /** Smoothstep, so the scale eases in/out instead of ramping linearly. */
    private static float ease(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return t * t * (3f - 2f * t);
    }

    /** One item drawn about its 16px-icon centre at {@code scale}, with count/durability overlays. */
    private static void drawScaledItem(Minecraft mc, RenderItem ri, EntityPlayer player,
                                       ItemStack stack, int ix, int iy, float scale) {
        if (stack == null || stack.isEmpty() || scale <= 0.02f) return;
        boolean scaled = scale < 0.999f;
        if (scaled) {
            float cx = ix + 8f, cy = iy + 8f;
            GlStateManager.pushMatrix();
            GlStateManager.translate(cx, cy, 0f);
            GlStateManager.scale(scale, scale, 1f);
            GlStateManager.translate(-cx, -cy, 0f);
        }
        try {
            ri.renderItemAndEffectIntoGUI(player, stack, ix, iy);
            ri.renderItemOverlays(mc.fontRenderer, stack, ix, iy);
        } finally {
            if (scaled) GlStateManager.popMatrix();
        }
    }

    /**
     * Off-hand fade/swap state machine, advanced once per hotbar frame.
     *
     * <p>empty-&gt;filled: the cell fades in with the new item; filled-&gt;empty: it
     * fades out while still showing the item it held; both filled with a different
     * item (the hand-swap key on a full main hand, or any off-hand item change): the
     * cell holds and the item cross-swaps. Count/NBT-only changes update silently.
     * {@code retarget} (not {@code to}) so rapid swaps continue from the live value
     * instead of snapping.
     */
    private void updateOffHand(ItemStack off) {
        boolean nowHas  = !off.isEmpty();
        boolean prevHas = !offLast.isEmpty();

        if (nowHas && !prevHas) {
            // empty -> filled: whole slot fades in with the new item
            offShown = off.copy();
            offPrev  = ItemStack.EMPTY;
            offSwap.snap(1f);
            offSlot.retarget(1f, OFF_FADE_MS);
        } else if (!nowHas && prevHas) {
            // filled -> empty: whole slot fades out, still showing the outgoing item
            offPrev  = ItemStack.EMPTY;
            offSwap.snap(1f);
            offSlot.retarget(0f, OFF_FADE_MS);
        } else if (nowHas && prevHas) {
            offSlot.retarget(1f, OFF_FADE_MS);            // stay visible (no-op if already 1)
            if (offShown.isEmpty() || !ItemStack.areItemsEqual(off, offShown)) {
                // different icon -> scale cross-swap, cell holds full
                offPrev  = offShown;
                offShown = off.copy();
                offSwap.snap(0f);
                offSwap.to(1f, OFF_FADE_MS);
            } else if (!ItemStack.areItemStacksEqual(off, offShown)) {
                // same icon, count/NBT change -> silent update
                offShown = off.copy();
            }
        }
        offLast = off.isEmpty() ? ItemStack.EMPTY : off.copy();
    }

    /** One slot's stack, with vanilla's pop animation, count and durability/cooldown overlay. */
    private static void renderSlotItem(Minecraft mc, RenderItem ri, EntityPlayer player,
                                       ItemStack stack, int ix, int iy, float partialTicks) {
        if (stack == null || stack.isEmpty()) return;

        // vanilla's pop animation when a slot's contents change
        float pop = stack.getAnimationsToGo() - partialTicks;
        if (pop > 0f) {
            float s = 1f + pop / 5f;
            GlStateManager.pushMatrix();
            GlStateManager.translate(ix + 8f, iy + 12f, 0f);
            GlStateManager.scale(1f / s, (s + 1f) / 2f, 1f);
            GlStateManager.translate(-(ix + 8f), -(iy + 12f), 0f);
        }
        try {
            ri.renderItemAndEffectIntoGUI(player, stack, ix, iy);
        } finally {
            if (pop > 0f) GlStateManager.popMatrix();
        }
        // count + durability bar + the 1.12.2 item-cooldown overlay
        ri.renderItemOverlays(mc.fontRenderer, stack, ix, iy);
    }

    /**
     * Vanilla's "hotbar" attack indicator ({@code attackIndicator == 2}): the
     * local player's own attack-cooldown gauge beside the bar, on the side
     * opposite the off-hand slot — the exact sprites and position vanilla's
     * {@code renderHotbar} uses (y = bottom-20; centre+91+6, or centre-91-22 when
     * the off-hand sits on the right). Reads nothing but the player's cooldown.
     */
    private static void renderAttackIndicator(Minecraft mc, int center, int bottom,
                                              EnumHandSide offSide) {
        if (mc.gameSettings == null || mc.gameSettings.attackIndicator != 2) return;
        if (mc.player == null) return;
        float strength = mc.player.getCooledAttackStrength(0f);
        if (strength >= 1f) return;

        int y = bottom - 20;
        int x = offSide == EnumHandSide.RIGHT ? center - 91 - 22 : center + 91 + 6;
        int k = (int) (strength * 19f);

        mc.getTextureManager().bindTexture(Gui.ICONS);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, 1f);
        BLIT.drawTexturedModalRect(x, y, 0, 94, 18, 18);
        if (k > 0) BLIT.drawTexturedModalRect(x, y + 18 - k, 18, 112 - k, 18, k);
    }
}
