package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.module.CrosshairModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the vanilla crosshair with {@link CrosshairModule}'s custom shape. Cancels
 * {@code InGameHud.renderCrosshair(MatrixStack)} at HEAD and delegates — inheriting vanilla's
 * screen-open / F1 hiding for free (renderCrosshair isn't reached then), only re-adding the
 * first-person gate that the HEAD-cancel skips.
 *
 * <p><b>FAIR PLAY.</b> This never reads {@code crosshairTarget}, {@code targetedEntity} or any hit
 * result, and does not reproduce vanilla's attack-cooldown indicator (which does read the target).
 * The custom crosshair is a pure function of its settings and the screen centre.
 *
 * <p>1.16.5: {@code renderCrosshair(MatrixStack)} is private (javap-verified); Java 8 cast form is used
 * instead of pattern-matching instanceof.
 */
@Mixin(InGameHud.class)
public class CrosshairMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$crosshair(MatrixStack matrices, CallbackInfo ci) {
        try {
            // Combat ring / hit marker go in BEFORE the crosshair (vanilla sprite or the custom shape below), so the
            // crosshair always stays on top of them.
            try {
                dev.s1mp1e.client.module.AttackRingModule.drawUnderCrosshair(matrices);
                dev.s1mp1e.client.module.HitMarkerModule.drawUnderCrosshair(matrices);
            } catch (Throwable t) {
                System.out.println("[S1mp1e] combat under-crosshair draw failed: " + t);
            }
            // fixed-function: the glass draws rebound GL_TEXTURE_2D; vanilla bound icons.png once in render() and blits the
            // crosshair from it, so put it back
            net.minecraft.client.MinecraftClient.getInstance().getTextureManager().bindTexture(net.minecraft.client.gui.DrawableHelper.GUI_ICONS_TEXTURE);
            Module m = ModuleManager.byName("Crosshair");
            if (!(m instanceof CrosshairModule)) return;      // vanilla draws
            CrosshairModule ch = (CrosshairModule) m;
            if (!ch.enabled) return;                          // vanilla draws
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player == null) return;
            if (!mc.options.getPerspective().isFirstPerson()) return;   // F5: vanilla shows none -> no-op
            ci.cancel();
            int cx = mc.getWindow().getScaledWidth() / 2;
            int cy = mc.getWindow().getScaledHeight() / 2;
            ch.draw(matrices, cx, cy);
        } catch (Throwable t) {
            // Any failure -> do NOT cancel; let vanilla draw its crosshair.
        }
    }
}
