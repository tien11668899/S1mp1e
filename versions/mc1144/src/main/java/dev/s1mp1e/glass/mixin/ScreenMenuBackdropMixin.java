package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 5 (menu-backdrop blur) — the DRAW. 1.14.4 tier port of the 1.16.5 mixin.
 *
 * <p>Forge counterpart: LiquidGlass26's {@code MenuBackdrop.draw()} replaced the
 * tiled dirt on world-less screens with the blurred title panorama.
 *
 * <p><b>Tier delta (method rename).</b> 1.16.5 routes every world-less background
 * through {@code Screen.renderBackgroundTexture(int)}. That name does not exist in
 * 1.14.4; the yarn tiny shows the tiled-dirt draw is {@code renderDirtBackground(int)}
 * ({@code renderDirtBackground(I)V}, class_437) instead — {@code Screen.renderBackground()}
 * / {@code renderBackground(int)} delegate to it only when there is no world. So the
 * single behaviour-matching seam here is {@code renderDirtBackground}, at
 * {@code @At("HEAD")}, {@code cancellable = true}. {@link MenuBackdrop#draw()} returns
 * {@code true} once a panorama frame has been captured and the blur program is usable,
 * and we cancel the vanilla dirt; while it returns {@code false} the vanilla dirt
 * draws — exactly the helper's "false -> caller draws the dirt" contract. This single
 * point covers the main menu, options, multiplayer list, etc., without touching the
 * in-world dim.
 */
@Mixin(Screen.class)
public abstract class ScreenMenuBackdropMixin {

    @Inject(method = "renderDirtBackground", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$menuBackdrop(int vOffset, CallbackInfo ci) {
        if (MenuBackdrop.draw()) {
            ci.cancel();
        }
    }
}
