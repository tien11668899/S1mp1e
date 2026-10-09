package dev.s1mp1e.glass.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.client.module.HudGlass;
import net.minecraft.client.gui.hud.SpectatorHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * #20 — the spectator hotbar (shown only while spectating, when the spectator menu is open).
 *
 * <p><b>1.13.2 (javap-verified).</b> {@code SpectatorHud.method_9535(float alpha, int, float, SpectatorMenuState)}
 * (renderSpectatorMenu) draws the bar ({@code widgets.png} v0 182x22) and the selected slot ({@code v22} 24x22) with
 * the inherited instance {@code drawTexture(FFIIII)V} (owner = SpectatorHud; note the FLOAT x/y overload, not the
 * int one). The bar becomes the HUD glass strip and the selection a lifted glass capsule, honouring the menu's fade
 * (the method's first {@code float} parameter, read with {@code @Local(argsOnly, ordinal 0)} — the fixed pipeline has
 * no shader colour to read back). 1.13.2 deltas vs 1.14.4: method is {@code method_9535} with signature
 * {@code (F I F SpectatorMenuState)}; the blit is {@code drawTexture} and takes float x/y.
 */
@Mixin(SpectatorHud.class)
public abstract class SpectatorHotbarGlassMixin {

    @Redirect(method = "method_9535",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/hud/SpectatorHud;drawTexture(FFIIII)V"))
    private void s1mp1e$bar(SpectatorHud self, float x, float y, int u, int v, int w, int h,
                            @Local(argsOnly = true, ordinal = 0) float alpha) {
        float a = Math.max(0f, Math.min(1f, alpha));
        int prevTex = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        if (v == 0 && w == 182 && h == 22) {
            HudGlass.glassBoxCtx(x, y, x + w, y + h, 0.85f * a);
        } else if (v == 22 && w == 24 && h == 22) {
            AllGlass.capsule(x + 1, y + 1, x + w - 1, y + h - 1, AllGlass.hotbarCorner(w - 2, h - 2), 0.81f, a);
        } else {
            self.drawTexture(x, y, u, v, w, h);
            return;
        }
        // back to the widgets texture + the menu's fade colour for what vanilla blits next
        GlStateManager.enableTexture();
        GlStateManager.bindTexture(prevTex);
        GlStateManager.enableBlend();
        GlStateManager.color(1f, 1f, 1f, a);
    }
}
