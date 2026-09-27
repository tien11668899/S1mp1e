package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.MenuBackdrop;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 *
 * <p><b>In-world blur.</b> The second hook blurs the world behind in-world screens (pause
 * menu, options opened from it, advancements…) instead of leaving it merely dimmed — the
 * 1.13.2 counterpart of mc1144's {@code renderBackground(int)} RETURN hook and of 1.21's
 * native menu blur.
 */
@Mixin(Screen.class)
public abstract class ScreenMenuBackdropMixin {

    @Shadow protected MinecraftClient client;

    @Inject(method = "renderDirtBackground", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$menuBackdrop(int vOffset, CallbackInfo ci) {
        if (MenuBackdrop.draw()) {
            ci.cancel();
        }
    }

    /**
     * In-world screens: re-blit the frame blurred behind the panel.
     *
     * <p>1.13.2 {@code renderBackground(int)} (javap-verified, one return) is
     * {@code world != null ? fillGradient(0xC0101010, 0xD0101010) : renderDirtBackground(vOffset)}.
     * At {@code RETURN} the gradient has already darkened the frame, so
     * {@link MenuBackdrop#drawLive} grabs that composite and re-blits it blurred with
     * {@code dim = 0} — dimming again would double-darken. The world-less path is untouched: it
     * already went through the {@code renderDirtBackground} hook above.
     *
     * <p>Container screens ({@link HandledScreen}) are skipped: their own glass panel grabs and
     * refracts the scene itself, and blurring underneath it would both double up and fight the
     * panel's backdrop grab. A failure leaves vanilla's gradient exactly as it was.
     */
    @Inject(method = "renderBackground(I)V", at = @At("RETURN"))
    private void s1mp1e$inWorldBlur(int vOffset, CallbackInfo ci) {
        try {
            MinecraftClient mc = this.client != null ? this.client : MinecraftClient.getInstance();
            if (mc == null || mc.world == null) return;
            if ((Object) this instanceof HandledScreen) return;
            MenuBackdrop.drawLive(MenuBackdrop.RADIUS, 0f);
        } catch (Throwable ignored) {
            // a failed blur leaves vanilla's gradient exactly as it was
        }
    }
}
