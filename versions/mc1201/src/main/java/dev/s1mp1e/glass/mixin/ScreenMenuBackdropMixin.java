package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * All-glass #26: world-less screens sit on the blurred, dimmed title panorama ({@link MenuBackdrop}) instead of tiled
 * dirt. It is painted once at the start of every world-less screen's frame ({@code renderWithTooltip} HEAD — needed
 * because 1.20.1's world / server / pack list screens never call {@code renderBackground}: their list's dirt WAS the
 * background), and the dirt {@code renderBackgroundTexture} would paint (reached from {@code renderBackground} without a
 * world, or called directly by PackScreen, TelemetryInfoScreen, MessageScreen, …) is then skipped. With a world loaded
 * or without the blur program everything stays vanilla. CreateWorldScreen overrides {@code renderBackgroundTexture} —
 * see {@code CreateWorldBackdropMixin}.
 */
@Mixin(Screen.class)
public abstract class ScreenMenuBackdropMixin {

    @Inject(method = "renderWithTooltip", at = @At("HEAD"))
    private void s1mp1e$frameBackdrop(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MenuBackdrop.beginScreen(context, (Screen) (Object) this);
    }

    @Inject(method = "renderWithTooltip", at = @At("RETURN"))
    private void s1mp1e$frameDone(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        MenuBackdrop.endScreen();
    }

    @Inject(method = "renderBackgroundTexture", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$menuBackdrop(DrawContext context, CallbackInfo ci) {
        if (MenuBackdrop.cover(context)) ci.cancel();
    }
}
