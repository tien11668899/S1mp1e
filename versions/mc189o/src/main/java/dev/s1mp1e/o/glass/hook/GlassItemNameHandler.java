package dev.s1mp1e.o.glass.hook;

import java.lang.reflect.Field;

import dev.s1mp1e.o.glass.anim.Fade;
import dev.s1mp1e.o.glass.anim.Spring;
import dev.s1mp1e.o.glass.render.GlassProgram;
import dev.s1mp1e.o.glass.render.GlassRenderer;
import dev.s1mp1e.o.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.GameGui;
import net.minecraft.client.render.Window;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Formatting;
import dev.s1mp1e.o.event.RenderGameOverlayEvent;
import dev.s1mp1e.o.event.SubscribeEvent;
import dev.s1mp1e.o.util.ReflectionHelper;

/**
 * Held-item name popup — the 1.8.9 counterpart of LiquidGlass26's
 * {@code HudHotbarMixin#lg$glassItemName}. Replaces vanilla's flat "item name
 * over the hotbar" text with a frosted glass capsule whose width follows a
 * liquid spring, plus an old-name/new-name crossfade on a switch.
 *
 * <p>26.2 could simply cancel {@code extractSelectedItemName}. 1.8.9 draws the
 * name inside {@code GameGui.renderMainHandMessage}, guarded by the private
 * {@code remainingHighlightTicks}/{@code highlightingItemStack} fields, with no
 * dedicated {@code ElementType} to cancel. So we use the suppression trick:
 *
 * <ol>
 *   <li>at {@code RenderGameOverlayEvent.Pre(ALL)} we read the timer, then zero
 *       the reflected {@code remainingHighlightTicks} field. Later in the SAME
 *       {@code renderGameOverlay} pass, {@code renderSelectedItem}'s
 *       {@code remainingHighlightTicks > 0} guard fails and vanilla draws
 *       nothing;</li>
 *   <li>at {@code RenderGameOverlayEvent.Post(ALL)} we RESTORE the exact value
 *       we stole and draw our capsule. The restore happens before the next
 *       client tick's {@code updateTick()} touches the field, so the vanilla
 *       countdown is completely undisturbed — we only borrowed it for one render
 *       pass.</li>
 * </ol>
 *
 * <p>Mapping vs 26.2: {@code toolHighlightTimer -> remainingHighlightTicks},
 * {@code lastToolHighlight -> highlightingItemStack}, and
 * {@code !gameMode.canHurtPlayer()} is vanilla's own
 * {@code !playerController.hasStatusBars()} (+14 in creative/adventure).
 */
public final class GlassItemNameHandler {

    // Reflected once. MCP (dev) name first, SRG (reobf/production) name second so
    // ReflectionHelper.findField resolves in both workspaces.
    private static final Field F_TICKS =
            findField("remainingHighlightTicks", dev.s1mp1e.o.util.Names.of("mainHandMessageTimer", "f_18761244"));   // int
    private static final Field F_STACK =
            findField("highlightingItemStack", dev.s1mp1e.o.util.Names.of("itemInMainHand", "f_98474179"));     // ItemStack

    private static Field findField(String mcp, String srg) {
        try {
            return ReflectionHelper.findField(GameGui.class, mcp, srg);
        } catch (Throwable t) {
            System.out.println("[S1mp1e] item-name: could not reflect " + mcp + " (" + t + ")");
            return null;
        }
    }

    // --- item-name popup state (26.2 HudHotbarMixin, verbatim) --------------
    private Spring nameW = null;      // half-width spring, omega 30 (liquid resize)
    /** Eased, interruptible appear fade — switching items rapidly continues
     *  from the current opacity instead of restarting the ramp. */
    private final Fade inFade   = new Fade(0f, 150f);
    /** Old name easing out during a crossfade. */
    private final Fade prevFade = new Fade(0f, 150f);
    private float nameIn = 0f;        // cached inFade value this frame
    private String nameCur = null;    // current name (with colour/italic codes)
    private String namePrev = null;   // previous name, crossfading out
    private float namePrevA = 0f;     // old-name alpha

    // --- suppression bookkeeping -------------------------------------------
    private boolean zeroed = false;
    private int savedTicks = 0;
    private ItemStack savedStack = null;

    // ---- Pre(ALL): steal + zero the vanilla timer -------------------------

    @SubscribeEvent
    public void onOverlayPre(RenderGameOverlayEvent.Pre e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return;
        zeroed = false;
        if (F_TICKS == null || F_STACK == null) return;

        Minecraft mc = Minecraft.getInstance();
        GameGui gui = mc.gui;
        if (gui == null || mc.player == null) return;
        // Respect the user setting / spectator branch: if vanilla wouldn't draw
        // the name, leave everything alone (don't steal, don't draw).
        if (mc.options == null || !mc.options.itemInHandTooltips) return;
        if (mc.interactionManager != null && mc.interactionManager.isSpectator()) return;
        // PostPass unavailable -> let vanilla draw its flat name instead of eating it.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;

        try {
            savedTicks = F_TICKS.getInt(gui);
            savedStack = (ItemStack) F_STACK.get(gui);
            F_TICKS.setInt(gui, 0); // suppress vanilla renderSelectedItem this pass
            zeroed = true;
        } catch (Exception ex) {
            zeroed = false;
        }

        // The name is a HUD element; GlassHudHandler grabs the scene backdrop at
        // Pre(ALL) HIGHEST. Grab defensively if nothing has grabbed yet.
        SceneCapture.grabOnce();
    }

    // ---- Post(ALL): restore the timer, draw our capsule -------------------

    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!zeroed) return;

        Minecraft mc = Minecraft.getInstance();
        GameGui gui = mc.gui;
        if (gui != null && F_TICKS != null) {
            try {
                F_TICKS.setInt(gui, savedTicks); // hand the timer back untouched
            } catch (Exception ex) {
                /* ignore */
            }
        }
        zeroed = false;
        drawName(mc, e.resolution, savedTicks, savedStack);
    }

    private void drawName(Minecraft mc, Window res, int ticks, ItemStack stack) {
        if (ticks <= 0 || stack == null) {
            // nothing highlighted -> reset so the next appear fades in fresh
            nameIn = 0f;
            inFade.snap(0f);
            nameCur = null;
            namePrevA = 0f;
            return;
        }

        // mc1211's display string: rarity-COLOURED name (colour code FIRST so it doesn't
        // reset the italic that follows), italic for custom-named stacks, then the Forge
        // Item.getHighlightTip pass that GuiIngameForge.renderToolHightlight also applies.
        String s = "" + stack.getRarity().formatting
                 + (stack.hasCustomHoverName() ? Formatting.ITALIC.toString() : "")
                 + stack.getHoverName();
        try {
            /* Forge Item.getHighlightTip：原版沒有，等同不變 */
        } catch (Throwable t) {
            // a mod's getHighlightTip threw -> keep the plain rarity-coloured name
        }
        // Prefer the item's own TextRenderer (a few items override it), like vanilla does.
        TextRenderer font = mc.textRenderer;
        try {
            TextRenderer itemFont = null;   // Forge Item.getFontRenderer：原版沒有
            if (itemFont != null) font = itemFont;
        } catch (Throwable t) {
            // keep the default font renderer
        }
        int strWidth = font.getWidth(s);

        // name switch -> crossfade the OLD name out, spring the width to the new
        if (nameCur == null || !nameCur.equals(s)) {
            if (nameCur != null && nameIn > 0.1f) {
                namePrev = nameCur;
                namePrevA = 1f;
                prevFade.snap(1f);
            }
            nameCur = s;
        }
        float halfW = strWidth * 0.5f;
        if (nameW == null || nameIn <= 0f) {
            nameW = new Spring(halfW, Spring.OMEGA_MED, Spring.DAMPING); // omega 30, fresh appear
        } else {
            nameW.setTarget(halfW);
        }
        nameW.advance(1f / 60f); // sub-stepped + NaN-guarded

        inFade.to(1f);
        nameIn = inFade.value();      // eased, interruptible
        prevFade.to(0f);
        namePrevA = prevFade.value(); // old name eases out

        float vanillaFade = Math.min(1f, ticks / 10f); // 1.8.9 timer supplies fade-out
        float a = nameIn * vanillaFade;
        if (a <= 0.01f) return;

        int cx = res.getWidth() / 2;
        int y = res.getHeight() - 59;
        // creative/adventure has no health bar, so the name slides down 14 px to
        // fill the gap — this is vanilla's own !shouldDrawHUD() branch.
        if (mc.interactionManager != null && !mc.interactionManager.hasStatusBars()) {
            y += 14;
        }

        int hw = Math.round(nameW.value()) + 6;

        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        // frosted capsule: pad 10, corner 1.0 (full capsule), no lift, follows spring
        GlassRenderer.glass(cx - hw, y - 4, cx + hw, y + 13, 10f, 1.0f, 0f, a,
                            GlassRenderer.FROST_PANEL);
        GlStateManager.color4f(1f, 1f, 1f, 1f);

        // old name (crossfade out)
        if (namePrev != null && namePrevA > 0.02f) {
            int pa = Math.round(a * namePrevA * 255f) & 0xFF;
            if (pa > 4) { // avoid the alpha<4 = opaque font quirk
                int pw = font.getWidth(namePrev);
                font.drawWithShadow(namePrev, cx - pw / 2f, (float) y, (pa << 24) | 0xFFFFFF);
            }
        }
        // new name (crossfade in)
        int na = Math.round(a * (1f - namePrevA) * 255f) & 0xFF;
        if (na > 4) {
            font.drawWithShadow(s, cx - strWidth / 2f, (float) y, (na << 24) | 0xFFFFFF);
        }
        // Never disableBlend on exit (project GL rule): the font renderer left the
        // real GL colour tinted while GlStateManager's cache still reads white, so
        // force the cache to reset instead of tearing blend down.
        GlStateManager.color4f(0f, 0f, 0f, 0f);
        GlStateManager.color4f(1f, 1f, 1f, 1f);
    }
}
