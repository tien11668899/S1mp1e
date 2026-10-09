package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.client.module.HudGlass;
import net.minecraft.client.gui.hud.SpectatorHud;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * #20 — the spectator hotbar (shown only while spectating, when the spectator menu is open). 1.18.2
 * {@code SpectatorHud.renderSpectatorMenu(MatrixStack, float, int, int, SpectatorMenuState)} draws the bar
 * ({@code widgets.png} v0 182x22) and the selected slot ({@code v22} 24x22) with the instance
 * {@code drawTexture(MatrixStack,IIIIII)}. The bar becomes the HUD glass strip and the selection a lifted glass
 * capsule, honouring the menu's fade (shader-colour alpha). The slot icons draw separately and still show.
 */
@Mixin(SpectatorHud.class)
public abstract class SpectatorHotbarGlassMixin {

    @Redirect(method = "renderSpectatorMenu(Lnet/minecraft/client/util/math/MatrixStack;FIILnet/minecraft/client/gui/hud/spectator/SpectatorMenuState;)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/hud/SpectatorHud;drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIII)V"))
    private void s1mp1e$bar(SpectatorHud self, MatrixStack m, int x, int y, int u, int v, int w, int h) {
        float a = Math.max(0f, Math.min(1f, RenderSystem.getShaderColor()[3]));
        if (v == 0 && w == 182 && h == 22) {
            HudGlass.glassBoxCtx(m, x, y, x + w, y + h, 0.85f * a);
        } else if (v == 22 && w == 24 && h == 22) {
            AllGlass.capsule(m, x + 1, y + 1, x + w - 1, y + h - 1, AllGlass.hotbarCorner(w - 2, h - 2), 0.81f, a);
        } else {
            self.drawTexture(m, x, y, u, v, w, h);
        }
    }
}
