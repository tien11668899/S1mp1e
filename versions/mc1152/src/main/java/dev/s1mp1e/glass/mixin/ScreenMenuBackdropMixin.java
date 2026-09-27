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

    @Shadow protected MinecraftClient minecraft;

    @Inject(method = "renderDirtBackground", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$menuBackdrop(int vOffset, CallbackInfo ci) {
        if (MenuBackdrop.draw()) {
            ci.cancel();
        }
    }

    /**
     * In-world screens (pause menu, options opened from it, advancements…): blur the world behind
     * the panel instead of leaving it merely dimmed — the 1.15.2 counterpart of 1.21's vanilla menu
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
