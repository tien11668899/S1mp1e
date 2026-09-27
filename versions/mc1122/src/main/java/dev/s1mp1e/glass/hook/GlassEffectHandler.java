package dev.s1mp1e.glass.hook;

import com.google.common.collect.Ordering;
import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.render.GlassEffects;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.InventoryEffectRenderer;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Collection;

/**
 * Feature (F) — draws the survival/creative inventory's status-effect list as one
 * continuous glass strip (see {@link GlassEffects}), and — crucially for rule R1
 * (tooltip on the very top layer) — draws the WHOLE effect display (glass strip +
 * icons + names + durations) BEFORE the container's hover tooltip.
 *
 * <h3>Why the whole display, and why here</h3>
 * 1.12.2's {@code InventoryEffectRenderer.drawScreen} calls
 * {@code super.drawScreen()} — which ends by drawing the hover tooltip — and only
 * THEN calls {@code drawActivePotionEffects()}. So vanilla draws the effect boxes
 * (and their icons/text) on top of the tooltip. If this port merely swapped the
 * box fill at that call site the glass strip would cover the tooltip, breaking R1.
 *
 * <p>Instead the whole effect display is drawn from
 * {@link net.minecraftforge.client.event.GuiScreenEvent.BackgroundDrawnEvent}
 * (fired inside {@code drawDefaultBackground()}, before any slot, item or tooltip),
 * and vanilla's {@code drawActivePotionEffects} is cancelled by the coremod
 * (its head calls {@link dev.s1mp1e.glass.asm.EffectStripHook#handled}). The
 * tooltip, drawn later in the same {@code drawScreen}, is then on top of the whole
 * strip — items, counts, icons and all.
 *
 * <h3>Backdrop (rule R4)</h3>
 * {@link GlassContainerHandler} runs at NORMAL priority on the same event and does
 * a {@code SceneCapture.forceGrab()} of the world+dim+HUD before drawing the
 * container panel. This handler runs at LOW priority (after it), so a fresh
 * backdrop for THIS frame already exists and it must NOT re-grab: re-grabbing here
 * would fold the just-drawn container glass panel into the backdrop and the strip
 * would self-sample where it tucks under the panel edge. It reuses that grab.
 */
public final class GlassEffectHandler {

    /** 1.12.2 potion box: fixed width and height, spacing from vanilla. */
    private static final int BOX_W = 140;
    private static final int BOX_H = 32;

    /** vanilla inventory sheet (holds both the box strip and the status icons). */
    private static final ResourceLocation INVENTORY =
            new ResourceLocation("textures/gui/container/inventory.png");

    /** Public {@link Gui} to reach {@code drawTexturedModalRect} for the icons. */
    private static final Gui BLIT = new Gui();

    /** The screen whose effect strip drew this frame (null = none / vanilla). */
    private static Object drewFor;

    /** Screen-open fade, mirroring the container panel's 150 ms fade so the two
     *  appear together (kept local so no coupling to GlassContainerHandler). */
    private final Fade openFade = new Fade(0f, 150f);
    private Object fadeOwner;

    // -----------------------------------------------------------------------
    // Frame boundary: clear the "handled" latch so a screen that stops drawing
    // its strip (pipeline down) gets its vanilla effect boxes back.
    // -----------------------------------------------------------------------
    @SubscribeEvent
    public void onDrawScreenPre(GuiScreenEvent.DrawScreenEvent.Pre e) {
        drewFor = null;
    }

    // -----------------------------------------------------------------------
    // Draw the whole effect display before the tooltip.
    // -----------------------------------------------------------------------
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onBackgroundDrawn(GuiScreenEvent.BackgroundDrawnEvent e) {
        GuiScreen gui = e.getGui();
        if (!(gui instanceof InventoryEffectRenderer)) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return;

        // Glass must be live AND a fresh backdrop must already exist this frame
        // (grabbed by GlassContainerHandler at NORMAL priority). If not, fall back
        // to vanilla: leave drewFor null so the coremod lets drawActivePotionEffects run.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        if (!SceneCapture.hasBackdrop()) return;

        int[] rect = GlassContainerHandler.panelRect(gui);
        if (rect == null) return;
        int guiLeft = rect[0], guiTop = rect[1];

        EntityPlayer player = mc.player;
        Collection<PotionEffect> all = player.getActivePotionEffects();
        if (all == null || all.isEmpty()) return;

        int total = all.size();
        int rendered = 0;
        for (PotionEffect pe : all) {
            if (pe.getPotion().shouldRender(pe)) rendered++;
        }
        if (rendered == 0) return;

        // vanilla spacing: 33, tightened to 132/(n-1) above five effects.
        int spacing = total > 5 ? 132 / (total - 1) : 33;
        int x = guiLeft - 124;          // vanilla: guiLeft - 124
        int stripBottom = guiTop + (rendered - 1) * spacing + BOX_H;

        // Per-instance open fade in step with the container panel.
        if (fadeOwner != gui) {
            fadeOwner = gui;
            openFade.snap(0f);
            openFade.to(1f);
        }
        float fade = openFade.value();

        // 1) the glass strip (frame-primary; reuses the container's fresh grab).
        GlassEffects.strip(x, guiTop, x + BOX_W, stripBottom, spacing, rendered, fade);

        // 2) the vanilla icons + names + durations, on top of the strip. Faithful
        //    to InventoryEffectRenderer.drawActivePotionEffects (icon at +6,+7;
        //    text at +10+18). Drawn here so the tooltip (later) stays above them.
        drawEntries(mc, all, x, guiTop, spacing);

        drewFor = gui;
    }

    /** Icons + names + durations, mirroring vanilla's drawer exactly. */
    private static void drawEntries(Minecraft mc, Collection<PotionEffect> all,
                                    int x, int top, int spacing) {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableLighting();
        int j = top;
        for (PotionEffect pe : Ordering.natural().sortedCopy(all)) {
            Potion potion = pe.getPotion();
            if (!potion.shouldRender(pe)) continue;
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            mc.getTextureManager().bindTexture(INVENTORY);
            if (potion.hasStatusIcon()) {
                int idx = potion.getStatusIconIndex();
                BLIT.drawTexturedModalRect(x + 6, j + 7, idx % 8 * 18, 198 + idx / 8 * 18, 18, 18);
            }
            potion.renderInventoryEffect(pe, mc.currentScreen instanceof Gui
                    ? (Gui) mc.currentScreen : BLIT, x, j, 0f);
            if (!potion.shouldRenderInvText(pe)) { j += spacing; continue; }

            String name = I18n.format(potion.getName());
            int amp = pe.getAmplifier();
            if (amp == 1)      name = name + " " + I18n.format("enchantment.level.2");
            else if (amp == 2) name = name + " " + I18n.format("enchantment.level.3");
            else if (amp == 3) name = name + " " + I18n.format("enchantment.level.4");
            mc.fontRenderer.drawStringWithShadow(name, (float) (x + 10 + 18), (float) (j + 6), 0xFFFFFF);
            String time = Potion.getPotionDurationString(pe, 1.0F);
            mc.fontRenderer.drawStringWithShadow(time, (float) (x + 10 + 18), (float) (j + 6 + 10), 0x7F7F7F);
            j += spacing;
        }
        // Re-sync the colour cache the font renderer left tinted (same trap the
        // hotbar / GlassRenderer document) so later draws are not multiplied.
        GlStateManager.color(0f, 0f, 0f, 0f);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** True when this handler already drew {@code screen}'s effect strip this
     *  frame — the coremod reads it to cancel vanilla's drawActivePotionEffects. */
    public static boolean handledThisFrame(Object screen) {
        return drewFor != null && drewFor == screen;
    }
}
