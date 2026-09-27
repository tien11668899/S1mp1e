package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

    @Shadow protected MinecraftClient minecraft;

    @Inject(method = "renderDirtBackground", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$menuBackdrop(int vOffset, CallbackInfo ci) {
        if (MenuBackdrop.draw()) {
            ci.cancel();
        }
    }

    /**
     * In-world screens (pause menu, options opened from it, advancements…): blur the world behind
     * the panel instead of leaving it merely dimmed — the 1.14.4 counterpart of 1.21's vanilla menu
     * blur and of the 1.8.9 checklist's V-4 {@code drawLive} half.
     *
     * <p>{@code renderBackground(int)} is {@code world != null ? fillGradient(…) :
     * renderDirtBackground(vOffset)}. At {@code RETURN} the gradient has already darkened the frame,
     * so {@link MenuBackdrop#drawLive} grabs that composite and re-blits it blurred with
     * {@code dim = 0} — dimming again would double-darken. The world-less path is untouched: it
     * already went through the {@code renderDirtBackground} hook above.
     *
     * <p>Container screens are skipped: their own glass panel grabs and refracts the scene itself,
     * and blurring underneath it would both double up and fight the panel's backdrop grab.
     */
    @Inject(method = "renderBackground(I)V", at = @At("RETURN"))
    private void s1mp1e$inWorldBlur(int vOffset, CallbackInfo ci) {
        try {
            MinecraftClient mc = this.minecraft != null ? this.minecraft : MinecraftClient.getInstance();
            if (mc == null || mc.world == null) return;
            if ((Object) this instanceof ContainerScreen) return;
            MenuBackdrop.drawLive(MenuBackdrop.RADIUS, 0f);
        } catch (Throwable ignored) {
            // a failed blur leaves vanilla's gradient exactly as it was
        }
    }
}
