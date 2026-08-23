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
 * System 3 (held-item name popup crossfade) — the Fabric port of
 * {@code GlassItemNameHandler} / LiquidGlass26's {@code lg$glassItemName}.
 *
 * <p>The Forge port had no cancellable event, so it stole and re-set the private
 * {@code remainingHighlightTicks} field around {@code renderGameOverlay} to
 * suppress the vanilla flat name. 1.16.5 exposes the draw as its own method, so we
 * simply cancel it and draw our frosted capsule instead — far cleaner, identical
 * result. The animation state (width spring omega 30, appear/prev fades 150 ms,
 * old/new crossfade) is 26.2 verbatim.
 *
 * <p>Fabric target: {@code InGameHud.renderHeldItemTooltip(MatrixStack)}
 * ({@code method_1749}, {@code (Lnet/minecraft/client/util/math/MatrixStack;)V}) at
 * {@code @At("HEAD")}, {@code cancellable = true}.
 *
 * <p>Field mapping: {@code remainingHighlightTicks -> heldItemTooltipFade}
 * ({@code field_2040}), {@code highlightingItemStack -> currentStack}
 * ({@code field_2031}); {@code !gameMode.canHurtPlayer() (+14)} ->
 * {@code !interactionManager.hasStatusBars()} ({@code method_2908}).
 */
@Mixin(InGameHud.class)
public abstract class InGameHudItemNameMixin {

    @Shadow private int heldItemTooltipFade;
    @Shadow private ItemStack currentStack;

    // --- 26.2 popup state (verbatim) ---------------------------------------
    private Spring s1mp1e$nameW;
    private Fade s1mp1e$inFade;
    private Fade s1mp1e$prevFade;
    private float s1mp1e$nameIn;
    private Text s1mp1e$nameCur;
    private Text s1mp1e$namePrev;
    private float s1mp1e$namePrevA;

    @Inject(method = "renderHeldItemTooltip", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassItemName(MatrixStack matrices, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        // Respect the vanilla setting / spectator branch: don't eat the name.
        if (mc.options == null || !mc.options.heldItemTooltips) return;
        if (mc.interactionManager != null
                && mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) return;
        // Shader unavailable -> let vanilla draw its flat name instead.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;

        if (s1mp1e$inFade == null) {
            s1mp1e$inFade   = new Fade(0f, 150f);
            s1mp1e$prevFade = new Fade(0f, 150f);
        }
        // A HUD element; InGameHudMixin grabs at render HEAD. Grab defensively.
        if (!SceneCapture.hasBackdrop()) SceneCapture.grab();

        s1mp1e$drawName(mc, matrices, this.heldItemTooltipFade, this.currentStack);
        ci.cancel();
    }

    private void s1mp1e$drawName(MinecraftClient mc, MatrixStack matrices,
                                 int ticks, ItemStack stack) {
        if (ticks <= 0 || stack == null || stack.isEmpty()) {
            s1mp1e$nameIn = 0f;
            s1mp1e$inFade.snap(0f);
            s1mp1e$nameCur = null;
            s1mp1e$namePrevA = 0f;
            return;
        }

        TextRenderer font = mc.textRenderer;
        // Vanilla's exact display text: rarity colour + italic for custom names.
        MutableText text = new LiteralText("").append(stack.getName())
                                              .formatted(stack.getRarity().formatting);
        if (stack.hasCustomName()) text.formatted(Formatting.ITALIC);
        int strWidth = font.getWidth(text);

        // name switch -> crossfade the OLD name out, spring the width to the new
        if (s1mp1e$nameCur == null || !s1mp1e$nameCur.getString().equals(text.getString())) {
            if (s1mp1e$nameCur != null && s1mp1e$nameIn > 0.1f) {
                s1mp1e$namePrev = s1mp1e$nameCur;
                s1mp1e$namePrevA = 1f;
                s1mp1e$prevFade.snap(1f);
            }
            s1mp1e$nameCur = text;
        }
        float halfW = strWidth * 0.5f;
        if (s1mp1e$nameW == null || s1mp1e$nameIn <= 0f) {
            s1mp1e$nameW = new Spring(halfW, Spring.OMEGA_MED, Spring.DAMPING); // omega 30
        } else {
            s1mp1e$nameW.setTarget(halfW);
        }
        s1mp1e$nameW.advance(1f / 60f);

        s1mp1e$inFade.to(1f);
        s1mp1e$nameIn = s1mp1e$inFade.value();
        s1mp1e$prevFade.to(0f);
        s1mp1e$namePrevA = s1mp1e$prevFade.value();

        float vanillaFade = Math.min(1f, ticks / 10f); // 1.16.5 timer supplies fade-out
        float a = s1mp1e$nameIn * vanillaFade;
        if (a <= 0.01f) return;

        int cx = mc.getWindow().getScaledWidth() / 2;
        int y = mc.getWindow().getScaledHeight() - 59;
        if (mc.interactionManager != null && !mc.interactionManager.hasStatusBars()) {
            y += 14; // creative/adventure has no health bar (vanilla's own branch)
        }

        int hw = Math.round(s1mp1e$nameW.value()) + 6;

        // frosted capsule: pad 10, corner 1.0, no lift, follows the width spring
        GlassRenderer.glass(cx - hw, y - 4, cx + hw, y + 13, 10f, 1.0f, 0f, a,
                            GlassRenderer.FROST_PANEL);

        // old name (crossfade out)
        if (s1mp1e$namePrev != null && s1mp1e$namePrevA > 0.02f) {
            int pa = Math.round(a * s1mp1e$namePrevA * 255f) & 0xFF;
            if (pa > 4) {
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
