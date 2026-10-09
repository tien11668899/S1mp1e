package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * All-glass #26: 1.20.1 {@code CreateWorldScreen} overrides {@code renderBackgroundTexture} with a full-screen tile of
 * {@code LIGHT_DIRT_BACKGROUND_TEXTURE} (so {@code ScreenMenuBackdropMixin}'s hook on the base method never runs for it).
 * World-less, it becomes the same blurred title panorama as every other menu.
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldBackdropMixin {

    @Inject(method = "renderBackgroundTexture", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$menuBackdrop(DrawContext context, CallbackInfo ci) {
        if (MenuBackdrop.cover(context)) ci.cancel();
    }
}
