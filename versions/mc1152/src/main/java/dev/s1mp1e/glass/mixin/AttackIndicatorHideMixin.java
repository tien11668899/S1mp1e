package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.AttackRingModule;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * While {@link AttackRingModule} is on (and the ring can actually draw), drop ONLY vanilla's crosshair attack-indicator
 * blits in {@code InGameHud.renderCrosshair}. 1.15.2 draws them from {@code icons.png} with the inherited
 * {@code drawTexture(MatrixStack, x, y, u, v, w, h)} (javap-verified): full {@code (68,94)}, background {@code (36,94)},
 * progress {@code (52,94)}; the crosshair itself is the {@code (0,0)} blit and passes through unchanged.
 */
@Mixin(InGameHud.class)
public abstract class AttackIndicatorHideMixin {

    @Redirect(method = "renderCrosshair",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/hud/InGameHud;blit(IIIIII)V"))
    private void s1mp1e$hideIndicator(InGameHud self, int x, int y, int u, int v, int w, int h) {
        boolean indicator = v == 94 && (u == 36 || u == 52 || u == 68);
        if (indicator && AttackRingModule.active() && GlassProgram.arcUsable()) return;
        self.blit(x, y, u, v, w, h);
    }
}
