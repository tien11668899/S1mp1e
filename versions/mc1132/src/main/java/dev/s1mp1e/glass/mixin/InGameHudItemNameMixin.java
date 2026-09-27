package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.anim.Fade;
import dev.s1mp1e.glass.anim.Spring;
import dev.s1mp1e.glass.compat.Mc1132;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.item.ItemStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 3 (held-item name popup crossfade) — the 1.13.2 port of mc1144's mixin (itself the
 * Fabric port of {@code GlassItemNameHandler} / LiquidGlass26's {@code lg$glassItemName}). All
 * animation state (width spring omega 30, appear/prev fades 150 ms, old/new crossfade) is 26.2
 * verbatim and unchanged.
 *
 * <p><b>1.13.2 target (javap-verified, legacy yarn 1.13.2+build.604-v2).</b> The held-item name is
 * drawn by the public, UNMAPPED {@code InGameHud.method_18365()V} (1.14.4's
 * {@code renderHeldItemTooltip()}; profiler section {@code "selectedItemName"}). {@code render(F)V}
 * calls it exactly once, already gated on {@code options.heldItemTooltips && gameMode != SPECTATOR};
 * the gates are repeated here for parity. {@code @At("HEAD")}, {@code cancellable = true}: the cancel
 * happens before vanilla's profiler {@code push}, so push/pop stay balanced.
 *
 * <p>Fields: {@code private int heldItemTooltipFade} and {@code private ItemStack heldItem} (1.14.4's
 * {@code currentStack}). The display text is built exactly as vanilla builds it (verified from
 * {@code method_18365}'s bytecode): {@code new LiteralText("").append(stack.getName())
 * .formatted(stack.getRarity().formatting)}, {@code ITALIC} when {@code hasCustomName()}, measured and
 * drawn via {@code asFormattedString()} (the §-coded string — {@code TextRenderer} has only String
 * overloads here), compared via the plain {@code getString()}. The position is vanilla's:
 * {@code scaledH - 59}, {@code +14} when {@code !interactionManager.hasStatusBars()}, centred on
 * {@code scaledW / 2} (read through the {@link Mc1132} bridge — the same values vanilla reads from its
 * cached {@code field_20061}/{@code field_20062}).
 *
 * <p>If the glass pipeline is unusable, or the draw throws, the HEAD is not cancelled and vanilla's
 * flat name draws.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudItemNameMixin {

    @Shadow private int heldItemTooltipFade;
    @Shadow private ItemStack heldItem;

    // --- 26.2 popup state (verbatim) ---------------------------------------
    @Unique private Spring s1mp1e$nameW;
    @Unique private Fade s1mp1e$inFade;
    @Unique private Fade s1mp1e$prevFade;
    @Unique private float s1mp1e$nameIn;
    @Unique private Text s1mp1e$nameCur;
    @Unique private Text s1mp1e$namePrev;
    @Unique private float s1mp1e$namePrevA;
    @Unique private static boolean s1mp1e$errorLogged;

    @Inject(method = "method_18365", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassItemName(CallbackInfo ci) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null) return;
            // Respect the vanilla setting / spectator branch: don't eat the name.
            if (mc.options == null || !mc.options.heldItemTooltips) return;
            if (Mc1132.gameMode() == GameMode.SPECTATOR) return;
            // Shader unavailable -> let vanilla draw its flat name instead.
            if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
            if (mc.textRenderer == null) return;

            if (s1mp1e$inFade == null) {
                s1mp1e$inFade   = new Fade(0f, 150f);
                s1mp1e$prevFade = new Fade(0f, 150f);
            }
            // A HUD element; InGameHudMixin grabs at render HEAD. Grab defensively.
            if (!SceneCapture.hasBackdrop()) SceneCapture.grab();

            s1mp1e$drawName(mc, this.heldItemTooltipFade, this.heldItem);
            ci.cancel();
        } catch (Throwable t) {
            // not cancelled -> vanilla draws its flat name
            if (!s1mp1e$errorLogged) {
                s1mp1e$errorLogged = true;
                LogManager.getLogger("S1mp1e").error("[S1mp1e] glass item name failed, drawing vanilla", t);
            }
        }
    }

    @Unique
    private void s1mp1e$drawName(MinecraftClient mc, int ticks, ItemStack stack) {
        if (ticks <= 0 || stack == null || stack.isEmpty()) {
            s1mp1e$nameIn = 0f;
            s1mp1e$inFade.snap(0f);
            s1mp1e$nameCur = null;
            s1mp1e$namePrevA = 0f;
            return;
        }

        TextRenderer font = mc.textRenderer;
        // Vanilla's exact display text: rarity colour + italic for custom names.
        Text text = new LiteralText("").append(stack.getName())
                                       .formatted(stack.getRarity().formatting);
        if (stack.hasCustomName()) text.formatted(Formatting.ITALIC);
        int strWidth = font.getStringWidth(text.asFormattedString());

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

        float vanillaFade = Math.min(1f, ticks / 10f); // the vanilla timer supplies the fade-out
        float a = s1mp1e$nameIn * vanillaFade;
        if (a <= 0.01f) return;

        int cx = Mc1132.scaledW() / 2;
        int y = Mc1132.scaledH() - 59;
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
                String prevStr = s1mp1e$namePrev.asFormattedString();
                int pw = font.getStringWidth(prevStr);
                font.drawWithShadow(prevStr, cx - pw / 2f, (float) y,
                                    (pa << 24) | 0xFFFFFF);
            }
        }
        // new name (crossfade in)
        int na = Math.round(a * (1f - s1mp1e$namePrevA) * 255f) & 0xFF;
        if (na > 4) {
            font.drawWithShadow(s1mp1e$nameCur.asFormattedString(), cx - strWidth / 2f, (float) y,
                                (na << 24) | 0xFFFFFF);
        }
    }
}
