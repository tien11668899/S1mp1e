package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 5 (menu-backdrop blur) — the DRAW.
 *
 * <p>Forge counterpart: LiquidGlass26's {@code MenuBackdrop.draw()} replaced the
 * tiled dirt on world-less screens with the blurred title panorama; the Forge line
 * called it where the dirt would be drawn. In 1.16.5 every world-less screen paints
 * its background via {@code Screen.renderBackgroundTexture(int)} — the tiled
 * {@code OPTIONS_BACKGROUND_TEXTURE} (dirt).
 *
 * <p>Fabric target: {@code Screen.renderBackgroundTexture(int)} — intermediary
 * {@code method_25434}, descriptor {@code (I)V} — at {@code @At("HEAD")},
 * {@code cancellable = true}. {@link MenuBackdrop#draw()} returns {@code true} once
 * a panorama frame has been captured and the blur program is usable, and we cancel
 * the vanilla dirt; while it returns {@code false} the vanilla dirt draws — exactly
 * the helper's "false -> caller draws the dirt" contract.
 *
 * <p>(The parent {@code renderBackground(MatrixStack)}/{@code (MatrixStack,int)}
 * delegate here only when there is no world, so this single point covers the main
 * menu, options, multiplayer list, etc., without touching the in-world dim.)
 */
@Mixin(Screen.class)
public abstract class ScreenMenuBackdropMixin {

    @Inject(method = "renderBackgroundTexture", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$menuBackdrop(int vOffset, CallbackInfo ci) {
        if (MenuBackdrop.draw()) {
            ci.cancel();
        }
    }
}
