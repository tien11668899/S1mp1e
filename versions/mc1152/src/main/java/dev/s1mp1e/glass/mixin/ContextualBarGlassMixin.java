package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.AllGlass;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * #17 — the XP bar and the horse-jump bar (same slot). 1.15.2 draws both from {@code gui/icons.png} with the inherited
 * instance {@code blit(IIIIII)V} (owner = InGameHud; javap-verified rows): XP track v64 / fill v69, jump track v84 / fill
 * v89, all 5 px tall. The track becomes a glass-button capsule and the fill a round-capped colour bar (XP
 * {@code #30D158}, jump {@code #FF9F0A}).
 *
 * <p><b>1.15.2 vs 1.16.5.</b> {@code InGameHudMixin} lifts both bars by {@code DECO_LIFT} with a GL model-view
 * {@code translatef} around the call. On 1.15.2 the glass programs read {@code gl_ModelViewProjectionMatrix}
 * ({@code glass.vsh}) and {@link AllGlass} draws in the model-view's local space, so the glass bar follows that lift by
 * itself — it is drawn at vanilla's {@code y}, NOT {@code y - DECO_LIFT} (that would lift it twice, onto the hearts).
 */
@Mixin(InGameHud.class)
public abstract class ContextualBarGlassMixin {

    @Redirect(method = {"renderExperienceBar(I)V", "renderMountJumpBar(I)V"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/hud/InGameHud;blit(IIIIII)V"))
    private void s1mp1e$bar(InGameHud self, int x, int y, int u, int v, int w, int h) {
        int rgb;
        if (v == 64 || v == 84) rgb = -1;                           // the track
        else if (v == 69) rgb = 0x30D158;                           // XP fill
        else if (v == 89) rgb = 0xFF9F0A;                           // jump fill
        else { self.blit(x, y, u, v, w, h); return; }
        int prevTex = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        if (rgb == -1) {
            AllGlass.capsule(x - 0.5f, y - 0.5f, x + w + 0.5f, y + h + 0.5f, 1f, 0f, 0.9f);
        } else if (w > 0) {
            AllGlass.scrim(x, y + 0.5f, x + w, y + h - 0.5f, (h - 1) / 2f, 0xF2000000 | rgb);
        }
        // icons.png stays bound for the next vanilla blit; colour back to white
        RenderSystem.enableTexture();
        RenderSystem.bindTexture(prevTex);
        RenderSystem.color4f(1f, 1f, 1f, 1f);
    }
}
