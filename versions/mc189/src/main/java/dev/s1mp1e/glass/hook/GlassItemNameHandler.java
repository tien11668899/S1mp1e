package dev.s1mp1e.glass.hook;

import java.lang.reflect.Field;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;

/**
 * Held-item name popup — the 1.8.9 counterpart of LiquidGlass26's
 * {@code HudHotbarMixin#lg$glassItemName}. Replaces vanilla's flat "item name
 * over the hotbar" text with a frosted glass capsule whose width follows a
 * liquid spring, plus an old-name/new-name crossfade on a switch.
 *
 * <p>26.2 could simply cancel {@code extractSelectedItemName}. 1.8.9 draws the
 * name inside {@code GuiIngame.renderSelectedItem}, guarded by the private
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
 * {@code !playerController.shouldDrawHUD()} (+14 in creative/adventure).
 */
public final class GlassItemNameHandler {

    // Reflected once. MCP (dev) name first, SRG (reobf/production) name second so
    // ReflectionHelper.findField resolves in both workspaces.
    private static final Field F_TICKS =
            findField("remainingHighlightTicks", "field_92017_k");   // int
    private static final Field F_STACK =
            findField("highlightingItemStack", "field_92016_l");     // ItemStack

    private static Field findField(String mcp, String srg) {
        try {
            return ReflectionHelper.findField(GuiIngame.class, mcp, srg);
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

        Minecraft mc = Minecraft.getMinecraft();
        GuiIngame gui = mc.ingameGUI;
        if (gui == null || mc.thePlayer == null) return;
        // Respect the user setting / spectator branch: if vanilla wouldn't draw
        // the name, leave everything alone (don't steal, don't draw).
        if (mc.gameSettings == null || !mc.gameSettings.heldItemTooltips) return;
        if (mc.playerController != null && mc.playerController.isSpectator()) return;
        // Shader unavailable -> let vanilla draw its flat name instead of eating it.
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
        if (!SceneCapture.hasBackdrop()) SceneCapture.grab();
    }

    // ---- Post(ALL): restore the timer, draw our capsule -------------------

    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post e) {
        if (e.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!zeroed) return;

        Minecraft mc = Minecraft.getMinecraft();
        GuiIngame gui = mc.ingameGUI;
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

    private void drawName(Minecraft mc, ScaledResolution res, int ticks, ItemStack stack) {
        if (ticks <= 0 || stack == null) {
            // nothing highlighted -> reset so the next appear fades in fresh
            nameIn = 0f;
            inFade.snap(0f);
            nameCur = null;
            namePrevA = 0f;
            return;
        }

        // Vanilla's exact display string: rarity-coloured name, italic prefix for
        // custom-named stacks.
        String s = stack.getDisplayName();
        if (stack.hasDisplayName()) {
            s = EnumChatFormatting.ITALIC + s;
        }
        FontRenderer font = mc.fontRendererObj;
        int strWidth = font.getStringWidth(s);

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

        int cx = res.getScaledWidth() / 2;
        int y = res.getScaledHeight() - 59;
        // creative/adventure has no health bar, so the name slides down 14 px to
        // fill the gap — this is vanilla's own !shouldDrawHUD() branch.
        if (mc.playerController != null && !mc.playerController.shouldDrawHUD()) {
            y += 14;
        }

        int hw = Math.round(nameW.value()) + 6;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        // frosted capsule: pad 10, corner 1.0 (full capsule), no lift, follows spring
        GlassRenderer.glass(cx - hw, y - 4, cx + hw, y + 13, 10f, 1.0f, 0f, a,
                            GlassRenderer.FROST_PANEL);
        GlStateManager.color(1f, 1f, 1f, 1f);

        // old name (crossfade out)
        if (namePrev != null && namePrevA > 0.02f) {
            int pa = Math.round(a * namePrevA * 255f) & 0xFF;
            if (pa > 4) { // avoid the alpha<4 = opaque font quirk
                int pw = font.getStringWidth(namePrev);
                font.drawStringWithShadow(namePrev, cx - pw / 2f, (float) y, (pa << 24) | 0xFFFFFF);
            }
        }
        // new name (crossfade in)
        int na = Math.round(a * (1f - namePrevA) * 255f) & 0xFF;
        if (na > 4) {
            font.drawStringWithShadow(s, cx - strWidth / 2f, (float) y, (na << 24) | 0xFFFFFF);
        }
        GlStateManager.disableBlend();
    }
}
