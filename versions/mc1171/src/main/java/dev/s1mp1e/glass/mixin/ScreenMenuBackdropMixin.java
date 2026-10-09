package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * All-glass #26: world-less screens sit on the blurred, dimmed title panorama ({@link MenuBackdrop}) instead of tiled
 * dirt. The panorama itself is painted once per frame BEFORE the screen renders, at the single
 * {@code currentScreen.render} call in {@code GameRenderer.render} ({@code GameRendererTooltipLayerMixin} — 1.18.2 has
 * no {@code renderWithTooltip}, and a {@code Screen.render} hook is too late because list screens call
 * {@code super.render} last). Here, the tiled dirt a screen would then paint through
 * {@code renderBackgroundTexture(int)} (from {@code renderBackground} without a world, or called directly by
 * PackScreen, …) is skipped once the backdrop is up. With a world loaded, on the title screen, or without the blur
 * program {@link MenuBackdrop#cover()} returns false and the vanilla dirt is left intact.
 */
@Mixin(Screen.class)
public abstract class ScreenMenuBackdropMixin {

    @Inject(method = "renderBackgroundTexture", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$menuBackdrop(int vOffset, CallbackInfo ci) {
        if (MenuBackdrop.cover()) ci.cancel();
    }
}
