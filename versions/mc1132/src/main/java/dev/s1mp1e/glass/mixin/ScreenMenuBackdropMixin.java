package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 5 (menu-backdrop blur) — the DRAW. 1.13.2 (Legacy Fabric) port of the
 * 1.16.5 mixin of the same name.
 *
 * <p>Forge counterpart: LiquidGlass26's {@code MenuBackdrop.draw()} replaced the
 * tiled dirt on world-less screens with the blurred title panorama; the Forge line
 * called it where the dirt would be drawn.
 *
 * <h3>1.13.2 tier delta vs the 1.16.5 source</h3>
 * 1.16.5 tiled the dirt in {@code Screen.renderBackgroundTexture(int)}. In 1.13.2 the
 * dirt tiler is {@code Screen.renderDirtBackground(int)} — intermediary
 * {@code method_1034}, descriptor {@code (I)V} — reached only from
 * {@code renderBackground(int)} when {@code world == null} (menu screens), so this
 * single point covers the main menu, options, multiplayer list, etc., without
 * touching the in-world dim. At {@code @At("HEAD")}, {@code cancellable = true}:
 * {@link MenuBackdrop#draw()} returns {@code true} once a panorama frame is captured
 * and the blur program is usable, and we cancel the vanilla dirt; while it returns
 * {@code false} the vanilla dirt draws.
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
