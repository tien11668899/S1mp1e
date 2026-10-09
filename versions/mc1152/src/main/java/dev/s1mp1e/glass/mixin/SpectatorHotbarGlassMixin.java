package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.client.module.HudGlass;
import net.minecraft.client.gui.hud.SpectatorHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * #20 — the spectator hotbar (shown only while spectating, when the spectator menu is open). 1.15.2
 * {@code SpectatorHud.renderSpectatorMenu(float alpha, int, int, SpectatorMenuState)} draws the bar ({@code widgets.png}
 * v0 182x22) and the selected slot ({@code v22} 24x22) with the inherited instance {@code blit(IIIIII)V} (owner =
 * SpectatorHud, javap-verified). The bar becomes the HUD glass strip and the selection a lifted glass capsule, honouring
 * the menu's fade (the method's first {@code float} parameter, read with {@code @Local(argsOnly, ordinal 0)} — the
 * fixed pipeline has no shader colour to read back).
 */
@Mixin(SpectatorHud.class)
public abstract class SpectatorHotbarGlassMixin {

    @Redirect(method = "renderSpectatorMenu(FIILnet/minecraft/client/gui/hud/spectator/SpectatorMenuState;)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/hud/SpectatorHud;blit(IIIIII)V"))
    private void s1mp1e$bar(SpectatorHud self, int x, int y, int u, int v, int w, int h,
                            @Local(argsOnly = true, ordinal = 0) float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        int prevTex = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        if (v == 0 && w == 182 && h == 22) {
            HudGlass.glassBoxCtx(x, y, x + w, y + h, 0.85f * a);
        } else if (v == 22 && w == 24 && h == 22) {
            AllGlass.capsule(x + 1, y + 1, x + w - 1, y + h - 1, AllGlass.hotbarCorner(w - 2, h - 2), 0.81f, a);
        } else {
            self.blit(x, y, u, v, w, h);
            return;
        }
        // back to the widgets texture + the menu's fade colour for what vanilla blits next
        com.mojang.blaze3d.systems.RenderSystem.enableTexture();
        com.mojang.blaze3d.systems.RenderSystem.bindTexture(prevTex);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.color4f(1f, 1f, 1f, a);
    }
}
