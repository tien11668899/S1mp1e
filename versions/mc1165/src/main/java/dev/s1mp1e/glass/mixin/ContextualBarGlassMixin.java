package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * #17 — the XP bar and the horse-jump bar (same slot). 1.16.5 draws both from {@code gui/icons.png} with the instance
 * {@code drawTexture(MatrixStack,IIIIII)} (owner resolves to {@code InGameHud} — it extends DrawableHelper and calls it
 * unqualified; javap-verified rows): XP track v64 / fill v69, jump track v84 / fill v89, all 5 px tall. The track
 * becomes a glass-button capsule and the fill a round-capped colour bar (XP {@code #30D158}, jump {@code #FF9F0A}).
 *
 * <p><b>The lift is automatic — do NOT subtract it here.</b> {@code InGameHudMixin} lifts both bars by {@code DECO_LIFT}
 * with a LEGACY GL modelview {@code RenderSystem.translatef}, and our glass DOES follow that: {@code glass.vsh} transforms
 * through {@code gl_ModelViewProjectionMatrix}, so the quad {@link AllGlass} emits is lifted by the same legacy matrix.
 * The {@code MatrixStack m} passed in is vanilla's un-lifted HUD stack, so {@code AllGlass} must draw at vanilla's
 * {@code y} — exactly like the 1.15.2 line. A {@code y - DECO_LIFT} here lifts the glass twice and rides it onto the
 * hearts (the bug seen in {@code agallglass/ag01-xp.png} before this was reverted).
 */
@Mixin(InGameHud.class)
public abstract class ContextualBarGlassMixin {

    @Redirect(method = {"renderExperienceBar", "renderMountJumpBar"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/InGameHud;drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$bar(InGameHud self, MatrixStack m, int x, int y, int u, int v, int w, int h) {
        if (v == 64 || v == 84) {                                   // the track
            AllGlass.capsule(m, x - 0.5f, y - 0.5f, x + w + 0.5f, y + h + 0.5f, 1f, 0f, 0.9f);
            return;
        }
        int rgb;
        if (v == 69) rgb = 0x30D158;                                // XP fill
        else if (v == 89) rgb = 0xFF9F0A;                           // jump fill
        else { self.drawTexture(m, x, y, u, v, w, h); return; }
        if (w > 0) AllGlass.scrim(m, x, y + 0.5f, x + w, y + h - 0.5f, (h - 1) / 2f, 0xF2000000 | rgb);
    }
}
