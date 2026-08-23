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
 * called it where the dirt would be drawn.
 *
 * <p>Fabric target: {@code Screen.renderDirtBackground(int)} — the 1.15.2 tiled
 * {@code OPTIONS_BACKGROUND_TEXTURE} (dirt) drawer — at {@code @At("HEAD")},
 * {@code cancellable = true}. Verified against the mapped 1.15.2 jar:
 * {@code Screen.renderBackground(int)} calls {@code renderDirtBackground(int)} only
 * on the {@code minecraft.world == null} branch (the in-world branch draws the
 * {@code fillGradient} dim instead), so this single point covers every world-less
 * screen — main menu, options, multiplayer list — without touching the in-world
 * dim. {@link MenuBackdrop#draw()} returns {@code true} once a panorama frame has
 * been captured and the blur program is usable, and we cancel the vanilla dirt;
 * while it returns {@code false} the vanilla dirt draws — exactly the helper's
 * "false -> caller draws the dirt" contract.
 *
 * <p><b>1.15.2 delta vs 1.16.5:</b> the dirt drawer is {@code renderDirtBackground(
 * int)} here, not {@code renderBackgroundTexture(int)}; both are {@code (I)V} at
 * {@code HEAD} and neither carries a {@code MatrixStack}, so only the method name
 * changes.
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
