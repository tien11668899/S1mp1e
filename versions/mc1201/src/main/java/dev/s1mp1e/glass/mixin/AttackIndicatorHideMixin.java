package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.AttackRingModule;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * While {@link AttackRingModule} is on (and the ring can actually draw), drop ONLY vanilla's crosshair attack-indicator
 * blits in {@code InGameHud.renderCrosshair}. 1.20.1 draws them from {@code icons.png} with
 * {@code DrawContext.drawTexture(Identifier, x, y, u, v, w, h)} (javap-verified): full {@code (68,94)}, background
 * {@code (36,94)}, progress {@code (52,94)}; the crosshair itself is the {@code (0,0)} 15x15 blit and every other blit
 * passes through unchanged. (When the S1mp1e Crosshair module is on, {@code CrosshairMixin} cancels the method anyway.)
 */
@Mixin(InGameHud.class)
public abstract class AttackIndicatorHideMixin {

    @Redirect(method = "renderCrosshair",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/DrawContext;drawTexture(Lnet/minecraft/util/Identifier;IIIIII)V"))
    private void s1mp1e$hideIndicator(DrawContext ctx, Identifier tex, int x, int y, int u, int v, int w, int h) {
        boolean indicator = v == 94 && (u == 36 || u == 52 || u == 68);
        if (indicator && AttackRingModule.active() && GlassProgram.arcUsable()) return;
        ctx.drawTexture(tex, x, y, u, v, w, h);
    }
}
