package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Held-item name popup (glass + appear-fade + width-morph + 150 ms crossfade) —
 * the 1.16.5 Fabric port of the 1.12.2 Forge {@code GlassItemNameHandler}, which
 * is itself the 1.8.9 counterpart of LiquidGlass26's {@code lg$glassItemName}.
 * Replaces vanilla's flat "item name over the hotbar" text with a frosted glass
 * capsule whose half-width follows a liquid spring (omega 30), plus an
 * old-name/new-name crossfade whenever the selected item changes.
 *
 * <p><b>Plumbing (the only thing that changes vs 1.12.2).</b> The Forge 1.12.2
 * handler had no cancellable per-element event, so it stole and zeroed the
 * private {@code remainingHighlightTicks} field at
 * {@code RenderGameOverlayEvent.Pre(ALL)} to make vanilla's
 * {@code renderSelectedItem} draw nothing, then restored the exact value and drew
 * its capsule at {@code Post(ALL)}. 1.16.5 exposes the draw as its own method
 * {@code InGameHud.renderHeldItemTooltip(MatrixStack)}, so we simply
 * {@code @Inject} at {@code HEAD} {@code cancellable}, draw the capsule, and
 * {@link CallbackInfo#cancel()} — cleaner, identical result. The
 * "let-vanilla-draw" branches (setting off, spectator, shader missing) return
 * <em>before</em> {@code cancel()}, so vanilla's flat name still renders exactly
 * as the Forge port left the timer alone in those cases.
 *
 * <p><b>Behaviour / geometry / spring / fade — byte-for-byte from 1.12.2.</b>
 * appear/prev {@link Fade}s 150 ms; width {@link Spring} {@code OMEGA_MED} (30),
 * critically damped, sub-stepped at {@code 1/60}; {@code vanillaFade = min(1,
 * ticks/10)}; centre {@code x = w/2}, {@code y = h - 59} (+14 when the gamemode
 * has no status bars); {@code hw = round(springW) + 6}; capsule
 * {@code glass(cx-hw, y-4, cx+hw, y+13, pad 10, corner 1.0, lift 0, a,
 * FROST_PANEL)}; old/new names drawn at {@code a*prevA} / {@code a*(1-prevA)}
 * with the {@code >4} alpha guard that dodges vanilla's {@code alpha<4 = opaque}
 * font quirk. Only the text reproduction tracks per-version vanilla: 1.16.5
 * builds {@code LiteralText("").append(getName()).formatted(rarity)} +
 * italic-if-custom, matching what {@code renderHeldItemTooltip} itself draws.
 *
 * <p><b>Field mapping vs 1.12.2 / 26.2.</b>
 * {@code remainingHighlightTicks (field_92017_k) -> heldItemTooltipFade
 * (field_2040)}; {@code highlightingItemStack (field_92016_l) -> currentStack
 * (field_2031)}; {@code !playerController.shouldDrawHUD() (+14) ->
 * !interactionManager.hasStatusBars() (method_2908)}.
 *
 * <p>Not registered in {@code s1mp1e.mixins.json} on purpose — enabled by hand,
 * one system at a time, to verify.
 */
@Mixin(InGameHud.class)
public abstract class ItemNameGlassMixin {

    // Vanilla's popup timer + stack (verified yarn 1.16.5).
    @Shadow private int heldItemTooltipFade;   // field_2040 (was remainingHighlightTicks)
    @Shadow private ItemStack currentStack;    // field_2031 (was highlightingItemStack)

    // --- popup animation state (1.12.2 GlassItemNameHandler, verbatim) ------
    /** Half-width spring, omega 30 (liquid resize on name change). */
    private Spring s1mp1e$nameW;
    /** Eased, interruptible appear fade (150 ms); rapid switches continue from
     *  the current opacity instead of restarting the ramp. */
    private Fade s1mp1e$inFade;
    /** Old name easing out during a crossfade (150 ms). */
    private Fade s1mp1e$prevFade;
    private float s1mp1e$nameIn;    // cached inFade value from last frame
    private Text  s1mp1e$nameCur;   // current name (rarity colour + italic codes)
    private Text  s1mp1e$namePrev;  // previous name, crossfading out
    private float s1mp1e$namePrevA; // old-name alpha

    @Inject(method = "renderHeldItemTooltip", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassItemName(MatrixStack matrices, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        // Respect the user setting / spectator branch: if vanilla wouldn't draw
        // the name, leave it be (don't cancel -> vanilla draws its flat name).
        if (mc.options == null || !mc.options.heldItemTooltips) return;
        if (mc.interactionManager != null
                && mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) return;
        // Shader unavailable -> let vanilla draw its flat name instead of eating it.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;

        // Mixin fields cannot use inline initialisers safely; lazy-init like the
        // reference ButtonGlassMixin does with its hover fades.
        if (s1mp1e$inFade == null) {
            s1mp1e$inFade   = new Fade(0f, 150f);
            s1mp1e$prevFade = new Fade(0f, 150f);
        }
        // The name is a HUD element; InGameHudMixin grabs the scene backdrop at
        // render HEAD. Grab defensively if nothing has grabbed yet this frame.
        if (!SceneCapture.hasBackdrop()) SceneCapture.grab();

        s1mp1e$drawName(mc, matrices, this.heldItemTooltipFade, this.currentStack);
        ci.cancel();
    }

    private void s1mp1e$drawName(MinecraftClient mc, MatrixStack matrices,
                                 int ticks, ItemStack stack) {
        if (ticks <= 0 || stack == null || stack.isEmpty()) {
            // nothing highlighted -> reset so the next appear fades in fresh
            s1mp1e$nameIn = 0f;
            s1mp1e$inFade.snap(0f);
            s1mp1e$nameCur = null;
            s1mp1e$namePrevA = 0f;
            return;
        }

        TextRenderer font = mc.textRenderer;
        // Vanilla 1.16.5's exact display text: rarity-coloured name, italic for
        // custom-named stacks (renderHeldItemTooltip builds it the same way).
        MutableText text = new LiteralText("").append(stack.getName())
                                              .formatted(stack.getRarity().formatting);
        if (stack.hasCustomName()) {
            text.formatted(Formatting.ITALIC);
        }
        int strWidth = font.getWidth(text);

        // name switch -> crossfade the OLD name out, spring the width to the new
        if (s1mp1e$nameCur == null
                || !s1mp1e$nameCur.getString().equals(text.getString())) {
            if (s1mp1e$nameCur != null && s1mp1e$nameIn > 0.1f) {
                s1mp1e$namePrev = s1mp1e$nameCur;
                s1mp1e$namePrevA = 1f;
                s1mp1e$prevFade.snap(1f);
            }
            s1mp1e$nameCur = text;
        }
        float halfW = strWidth * 0.5f;
        if (s1mp1e$nameW == null || s1mp1e$nameIn <= 0f) {
            s1mp1e$nameW = new Spring(halfW, Spring.OMEGA_MED, Spring.DAMPING); // omega 30, fresh appear
        } else {
            s1mp1e$nameW.setTarget(halfW);
        }
        s1mp1e$nameW.advance(1f / 60f); // sub-stepped + NaN-guarded

        s1mp1e$inFade.to(1f);
        s1mp1e$nameIn = s1mp1e$inFade.value();      // eased, interruptible
        s1mp1e$prevFade.to(0f);
        s1mp1e$namePrevA = s1mp1e$prevFade.value(); // old name eases out

        float vanillaFade = Math.min(1f, ticks / 10f); // 1.16.5 timer supplies fade-out
        float a = s1mp1e$nameIn * vanillaFade;
        if (a <= 0.01f) return;

        int cx = mc.getWindow().getScaledWidth() / 2;
        int y = mc.getWindow().getScaledHeight() - 59;
        // creative/adventure has no health bar, so the name slides down 14 px to
        // fill the gap — vanilla's own !hasStatusBars() branch.
        if (mc.interactionManager != null && !mc.interactionManager.hasStatusBars()) {
            y += 14;
        }

        int hw = Math.round(s1mp1e$nameW.value()) + 6;

        // frosted capsule: pad 10, corner 1.0 (full capsule), no lift, follows
        // the width spring. GlassRenderer manages blend/depth state internally.
        GlassRenderer.glass(cx - hw, y - 4, cx + hw, y + 13, 10f, 1.0f, 0f, a,
                            GlassRenderer.FROST_PANEL);

        // old name (crossfade out)
        if (s1mp1e$namePrev != null && s1mp1e$namePrevA > 0.02f) {
            int pa = Math.round(a * s1mp1e$namePrevA * 255f) & 0xFF;
            if (pa > 4) { // avoid the alpha<4 = opaque font quirk
                int pw = font.getWidth(s1mp1e$namePrev);
                font.drawWithShadow(matrices, s1mp1e$namePrev, cx - pw / 2f, (float) y,
                                    (pa << 24) | 0xFFFFFF);
            }
        }
        // new name (crossfade in)
        int na = Math.round(a * (1f - s1mp1e$namePrevA) * 255f) & 0xFF;
        if (na > 4) {
            font.drawWithShadow(matrices, s1mp1e$nameCur, cx - strWidth / 2f, (float) y,
                                (na << 24) | 0xFFFFFF);
        }
    }
}
